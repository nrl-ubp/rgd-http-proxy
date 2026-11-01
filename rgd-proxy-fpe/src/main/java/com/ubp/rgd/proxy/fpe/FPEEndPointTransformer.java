package com.ubp.rgd.proxy.fpe;

import com.ubp.rgd.proxy.transform.AbstractEndPointTransformer;
import com.ubp.rgd.proxy.transform.DateValues;
import com.ubp.rgd.proxy.transform.model.ValueKind;
import com.ubp.rgd.proxy.transform.TransformerImpl;
import com.ubp.rgd.proxy.transform.ValuePlan;
import com.ubp.rgd.proxy.transform.model.RightContext;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.ValueMapping;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.crypto.fpe.FPEFF1Engine;
import org.bouncycastle.crypto.params.FPEParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms data with Format Preserving Encryption instead of calling the RegData engine.
 * <p>
 * The encryption is the NIST SP 800-38G <b>FF1</b> mode, provided by BouncyCastle. Unlike
 * tokenization, FPE needs no remote engine and no token vault: protecting and unprotecting are two
 * directions of the same deterministic cipher, keyed by {@code proxy.transform.fpe.key}.
 *
 * <h2>Token format</h2>
 * A text value is wrapped like an RPS token, <code>RG{...}</code>, because that is how
 * {@link ValuePlan} locates the values to unprotect. Inside the braces comes a two character header
 * followed by the ciphertext:
 * <pre>
 *     RG{ &lt;radix marker&gt; &lt;padding length&gt; &lt;ciphertext&gt; }
 * </pre>
 * The radix marker is needed because the alphabet is chosen from the clear value when protecting,
 * but has to be recovered from the ciphertext when unprotecting. The padding length tells how many
 * filler characters were appended to reach the minimum length the cipher requires.
 *
 * <h2>What is encrypted</h2>
 * Only the characters belonging to the alphabet are encrypted. Everything else, separators,
 * punctuation, accented letters, stays exactly where it was, which is what makes the transformation
 * reversible:
 * <pre>
 *     "12-34"  ->  "RG{N4" + encrypted digits with the dash back in place + "}"
 * </pre>
 *
 * <h2>Dates and numbers</h2>
 * A date stays a date and a number stays a number, neither is wrapped in <code>RG{...}</code>:
 * <ul>
 *     <li>A date in one of the formats of {@link DateValues} ({@code yyyy-mm-dd}, {@code dd-mm-yyyy}
 *     with {@code -}, {@code .} or {@code /}, an optional {@code Thh:mm:ss} time, or {@code ddmmyyyy})
 *     is encrypted into another valid date written in the same format. Every protected date is of
 *     year 3000 or later, which is what identifies it as protected: on unprotect, an earlier date is
 *     left untouched. A clear date of year 3000 or later is protected as text, in a token.</li>
 *     <li>A value read as a number ({@link ValueKind#NUMBER}, a JSON number for instance) is
 *     encrypted into another number with the same sign, the same number of digits before and after
 *     the decimal point, and the same exponent. A number has no marker, so every number of a
 *     configured property is decrypted on unprotect. A numeric <em>string</em> is text and gets a
 *     token.</li>
 * </ul>
 * A short number lives in a tiny domain, nine values for a single digit: its encryption only hides
 * which of those values it is, it cannot resist an enumeration.
 *
 * <h2>Determinism</h2>
 * The same clear value always gives the same token within the same property. That is a property of
 * FPE, not a defect: it is what allows encrypted values to be compared and joined. Values of two
 * different properties never collide though, because the tweak is derived from the RPS class and
 * property they belong to.
 */
@ApplicationScoped
@TransformerImpl(TransformerImpl.FPE)
public class FPEEndPointTransformer extends AbstractEndPointTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(FPEEndPointTransformer.class);

    private static final String ACTION_PROTECT = "Protect";
    private static final String ACTION_UNPROTECT = "Unprotect";

    private static final String TOKEN_PREFIX = "RG{";
    private static final String TOKEN_SUFFIX = "}";

    /** Number of header characters inside the braces: the radix marker and the padding length. */
    private static final int HEADER_LENGTH = 2;

    /**
     * Character appended to values that are shorter than the minimum length of their alphabet. It is
     * the character of index 0 in both alphabets, so the same filler fits them all.
     */
    private static final char PADDING_CHAR = '0';

    /**
     * Length of the tweak derived from the mapping. FF1 accepts a tweak of any length, 8 bytes is
     * plenty to separate the properties from one another.
     */
    private static final int TWEAK_LENGTH = 8;

    /**
     * Locates the values to unprotect: an <code>RG{...}</code> token, or a date of year 3000 or
     * later in one of the formats of {@link DateValues}.
     */
    static final Pattern TOKEN_PATTERN = Pattern.compile(
            ValuePlan.TOKEN_PATTERN.pattern()
                    + "|(?<![\\w./-])(?:[3-9]\\d{3}([-./])\\d{2}\\1\\d{2}(?:T\\d{2}:\\d{2}:\\d{2})?"
                    + "|\\d{2}([-./])\\d{2}\\2[3-9]\\d{3})(?!\\w|[-./]\\w)"
                    + "|^\\d{4}[3-9]\\d{3}$");

    /** Day number of the first date that can be protected, 0001-01-01. */
    private static final long FIRST_CLEAR_DAY = LocalDate.of(1, 1, 1).toEpochDay();

    /** Day number of the first protected date, 3000-01-01. Every protected date is on or after it. */
    private static final long FIRST_PROTECTED_DAY = LocalDate.of(3000, 1, 1).toEpochDay();

    /** Number of dates that can be protected, from 0001-01-01 to 2999-12-31. */
    private static final long DATE_DOMAIN = FIRST_PROTECTED_DAY - FIRST_CLEAR_DAY;

    /**
     * Largest domain permuted with a precomputed table rather than with FF1 directly. FF1 requires a
     * million values at least, and reaching a small domain by cycle walking would take too many
     * rounds.
     */
    private static final int TABLE_DOMAIN_LIMIT = 10_000;

    /** Digits FF1 needs at least with radix 10, BouncyCastle requiring a million values. */
    private static final int MIN_FF1_DIGITS = 6;

    /** Upper bound of {@link #tables}, cleared beyond: properties come from configuration and queries. */
    private static final int MAX_CACHED_TABLES = 1024;

    /** Tweak tags, so that a date, an integer part and a fraction never share a permutation. */
    private static final byte TAG_DATE = 'D';
    private static final byte TAG_INTEGER = 'I';
    private static final byte TAG_FRACTION = 'F';

    /** A number as written by {@link String#valueOf(Object)}: sign, integer part, fraction, exponent. */
    private static final Pattern NUMBER = Pattern.compile("^(-?)(\\d+)(?:\\.(\\d+))?([eE][+-]?\\d+)?$");

    /** Permutations of the small domains, keyed by tweak and domain size. */
    private final Map<String, Permutation> tables = new ConcurrentHashMap<>();

    /** A permutation of {@code [0, n)} and its inverse. */
    private record Permutation(int[] forward, int[] inverse) {
    }

    /**
     * An alphabet, its radix and the shortest input the cipher accepts for it.
     * <p>
     * BouncyCastle requires {@code length >= 2} and {@code radix^length >= 1_000_000}, which gives 6
     * characters for the digits and 4 for the alphanumerics.
     */
    private enum Alphabet {

        /** Digits only, so that a numeric value stays numeric once the braces are removed. */
        NUMERIC('N', "0123456789"),

        /** Digits and letters, for everything else. */
        ALPHANUMERIC('A', "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz");

        private final char marker;
        private final String characters;
        private final int minimumLength;

        Alphabet(char marker, String characters) {
            this.marker = marker;
            this.characters = characters;
            this.minimumLength = minimumLength(characters.length());
        }

        /**
         * Shortest input accepted for a radix, i.e. the smallest length whose domain holds at least
         * a million values, with the two characters BouncyCastle requires as a floor.
         *
         * @param radix the size of the alphabet
         * @return the minimum number of characters
         */
        private static int minimumLength(int radix) {
            int length = 2;
            while (Math.pow(radix, length) < 1_000_000d) {
                length++;
            }
            return length;
        }

        int indexOf(char c) {
            return characters.indexOf(c);
        }

        char charAt(int index) {
            return characters.charAt(index);
        }

        int radix() {
            return characters.length();
        }

        static Alphabet ofMarker(char marker) {
            for (Alphabet alphabet : values()) {
                if (alphabet.marker == marker) {
                    return alphabet;
                }
            }
            return null;
        }
    }

    /**
     * Hex encoded AES key, 16, 24 or 32 bytes once decoded.
     * <p>
     * Being a regular Quarkus property it is overridden by the {@code PROXY_TRANSFORM_FPE_KEY}
     * environment variable, which is how a real deployment should provide it.
     */
    @ConfigProperty(name = "proxy.transform.fpe.key")
    Optional<String> fpeKey;

    private KeyParameter key;

    /**
     * Build a transformer with the given key, for the callers creating one outside of CDI.
     *
     * @param hexKey the hex encoded AES key, 32, 48 or 64 characters
     * @return a ready to use transformer
     * @throws IllegalStateException when the key is missing or is not a valid AES key
     */
    public static FPEEndPointTransformer withKey(String hexKey) {
        FPEEndPointTransformer transformer = new FPEEndPointTransformer();
        transformer.fpeKey = Optional.ofNullable(hexKey);
        transformer.initKey();
        return transformer;
    }

    /**
     * Decode the key once, so that a misconfigured key is reported at startup rather than on the
     * first request carrying sensitive data.
     *
     * @throws IllegalStateException when the key is missing or is not a valid AES key
     */
    @PostConstruct
    public void initKey() {
        String hexKey = fpeKey.map(String::trim).orElse("");
        if (hexKey.isEmpty()) {
            throw new IllegalStateException("proxy.transform.fpe.key is required when proxy.transform.impl is "
                    + TransformerImpl.FPE + ". Provide a hex encoded AES key of 16, 24 or 32 bytes.");
        }

        byte[] keyBytes;
        try {
            keyBytes = decodeHex(hexKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("proxy.transform.fpe.key is not valid hexadecimal: " + e.getMessage(), e);
        }

        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException("proxy.transform.fpe.key decodes to " + keyBytes.length
                    + " bytes. An AES key must be 16, 24 or 32 bytes, so 32, 48 or 64 hexadecimal characters.");
        }

        key = new KeyParameter(keyBytes);
        LOG.info("Format Preserving Encryption initialized with a {} bits key.", keyBytes.length * 8);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The permissive <code>RG{...}</code> pattern, because an FPE token keeps the characters outside
     * its alphabet at their exact position: a dash, a space or punctuation can legitimately appear
     * inside it. A brace is the only character it can never contain, which is what this pattern
     * expresses.
     * <p>
     * A protected date is not wrapped, so the pattern also matches the dates of year 3000 or later,
     * which is where every protected date lands. The compact {@code ddmmyyyy} form is only matched
     * as a whole value, since any run of eight digits could otherwise be taken for a date.
     *
     * @return the FPE token pattern
     */
    @Override
    public Pattern tokenPattern() {
        return TOKEN_PATTERN;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}, a protected date is a date of year 3000 or later and a protected number
     *         is a number with as many digits
     */
    @Override
    public boolean preservesValueTypes() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code false}, the first two characters of an FPE token are the radix marker and the
     *         padding length, not a mapping index
     */
    @Override
    public boolean supportsTokenMappingIndex() {
        return false;
    }

    /**
     * Encrypt or decrypt the given values, depending on the action of the processing context.
     * <p>
     * A value that cannot be transformed is returned unchanged rather than failing the request: the
     * proxy must not reject a whole payload because of one unexpected value.
     *
     * @param values            the values to transform
     * @param rightContext      unused, FPE grants no right of its own
     * @param processingContext carries the {@code Action} evidence driving the direction
     * @param secretsManager    unused, FPE works with the single configured key
     */
    @Override
    public void transformData(MappedValue[] values, RightContext rightContext, ProcessingContext processingContext,
                              UUID secretsManager) {
        String action = actionOf(processingContext);
        boolean protect = !ACTION_UNPROTECT.equalsIgnoreCase(action);
        if (!ACTION_PROTECT.equalsIgnoreCase(action) && !ACTION_UNPROTECT.equalsIgnoreCase(action)) {
            LOG.warn("Unsupported action {} for Format Preserving Encryption. Protecting the values.", action);
        }

        for (MappedValue value : values) {
            String original = value.getOriginal();
            if (original == null) {
                continue;
            }
            byte[] tweak = tweakOf(value.getMapping());
            // every value must get a transformed value, otherwise
            // reassembling the payload fails.
            value.setTransformed(transform(original, value.getKind(), tweak, protect));
        }
    }

    /**
     * Protect or unprotect a single value, depending on its type.
     * <ul>
     *     <li>a number, i.e. a value read as a number, stays a number with as many digits;</li>
     *     <li>a date in one of the formats of {@link DateValues} stays a date in the same format;</li>
     *     <li>anything else is wrapped in an <code>RG{...}</code> token.</li>
     * </ul>
     *
     * @param original the value to transform
     * @param kind     the type the value was read with
     * @param tweak    the tweak of the property the value belongs to
     * @param protect  whether to protect or to unprotect
     * @return the transformed value, or the value unchanged when it cannot be transformed
     */
    String transform(String original, ValueKind kind, byte[] tweak, boolean protect) {
        if (kind == ValueKind.NUMBER) {
            return transformNumber(original, tweak, protect);
        }
        DateValues.ParsedDate date = DateValues.parse(original);
        if (date != null) {
            boolean protectedDate = date.date().getYear() >= 3000;
            if (protect && !protectedDate) {
                return transformDate(original, date, tweak, true);
            }
            if (!protect) {
                // A date before the year 3000 was never protected: it is clear already.
                return protectedDate ? transformDate(original, date, tweak, false) : original;
            }
            // A date of year 3000 or later would be taken for a protected one on the way back, so it
            // is protected as text instead.
        }
        return protect ? protect(original, tweak) : unprotect(original, tweak);
    }

    /**
     * Encrypt or decrypt a date into another valid date, written in the very same format.
     * <p>
     * The day is numbered from 0001-01-01, permuted among the days up to 2999-12-31 and written from
     * 3000-01-01 onwards: a protected date is therefore always of year 3000 or later, which is how it
     * is recognised on the way back. A time following the date is kept as is.
     *
     * @param original the value, for the value returned unchanged
     * @param date     the parsed date
     * @param tweak    the tweak of the property
     * @param protect  whether to encrypt or to decrypt
     * @return the transformed date, or the value unchanged when it is out of the protected range
     */
    private String transformDate(String original, DateValues.ParsedDate date, byte[] tweak, boolean protect) {
        long day = date.date().toEpochDay();
        long index = day - (protect ? FIRST_CLEAR_DAY : FIRST_PROTECTED_DAY);
        if (index < 0 || index >= DATE_DOMAIN) {
            LOG.debug("Date out of the protected range, leaving it unchanged.");
            return original;
        }
        try {
            long permuted = permute(index, DATE_DOMAIN, subTweak(tweak, TAG_DATE, 0), protect);
            long result = permuted + (protect ? FIRST_PROTECTED_DAY : FIRST_CLEAR_DAY);
            return date.format(LocalDate.ofEpochDay(result));
        } catch (RuntimeException e) {
            LOG.warn("Could not {} a date with FPE, leaving it unchanged: {}",
                    protect ? "protect" : "unprotect", e.getMessage());
            return original;
        }
    }

    /**
     * Encrypt or decrypt a number into another number of the same form.
     * <p>
     * The sign, the decimal point and the exponent stay where they are. The integer part and the
     * fraction are permuted separately, each among the values of the same number of digits, so that
     * the result still reads as the same number once parsed again:
     * <ul>
     *     <li>the integer part keeps its leading zeros and never gains one;</li>
     *     <li>the fraction keeps its trailing zeros and never gains one, so that a JSON parser
     *     dropping insignificant zeros cannot change the number of digits to decrypt.</li>
     * </ul>
     * A number made of zeros only is left unchanged.
     *
     * @param original the number as text
     * @param tweak    the tweak of the property
     * @param protect  whether to encrypt or to decrypt
     * @return the transformed number, or the value unchanged when it is not a plain number
     */
    String transformNumber(String original, byte[] tweak, boolean protect) {
        Matcher matcher = NUMBER.matcher(original);
        if (!matcher.matches()) {
            LOG.debug("Numeric value is not a plain number, leaving it unchanged.");
            return original;
        }
        try {
            String integer = transformInteger(matcher.group(2), tweak, protect);
            String fraction = matcher.group(3) == null ? "" : "." + transformFraction(matcher.group(3), tweak, protect);
            String exponent = matcher.group(4) == null ? "" : matcher.group(4);
            return matcher.group(1) + integer + fraction + exponent;
        } catch (RuntimeException e) {
            LOG.warn("Could not {} a number with FPE, leaving it unchanged: {}",
                    protect ? "protect" : "unprotect", e.getMessage());
            return original;
        }
    }

    /**
     * Permute the significant digits of an integer part among the integers of as many digits.
     */
    private String transformInteger(String digits, byte[] tweak, boolean protect) {
        int zeros = 0;
        while (zeros < digits.length() && digits.charAt(zeros) == '0') {
            zeros++;
        }
        String significant = digits.substring(zeros);
        if (significant.isEmpty()) {
            return digits;
        }
        int length = significant.length();
        byte[] subTweak = subTweak(tweak, TAG_INTEGER, length);
        String permuted;
        if (length >= MIN_FF1_DIGITS) {
            permuted = walk(significant, subTweak, protect, d -> d.charAt(0) != '0');
        } else {
            // Integers of that length without leading zero: [10^(n-1), 10^n), numbered from 0.
            long low = pow10(length - 1);
            long index = Long.parseLong(significant) - low;
            permuted = Long.toString(permute(index, 9 * low, subTweak, protect) + low);
        }
        return digits.substring(0, zeros) + permuted;
    }

    /**
     * Permute the significant digits of a fraction among the fractions of as many digits.
     */
    private String transformFraction(String digits, byte[] tweak, boolean protect) {
        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
        }
        String significant = digits.substring(0, end);
        if (significant.isEmpty()) {
            return digits;
        }
        int length = significant.length();
        byte[] subTweak = subTweak(tweak, TAG_FRACTION, length);
        String permuted;
        if (length >= MIN_FF1_DIGITS) {
            permuted = walk(significant, subTweak, protect, d -> d.charAt(d.length() - 1) != '0');
        } else {
            // Fractions of that length without trailing zero, numbered from 0: 9 of them per tens.
            long value = Long.parseLong(significant);
            long index = (value / 10) * 9 + (value % 10 - 1);
            long result = permute(index, 9 * pow10(length - 1), subTweak, protect);
            long fraction = (result / 9) * 10 + result % 9 + 1;
            permuted = String.format("%0" + length + "d", fraction);
        }
        return permuted + digits.substring(end);
    }

    /**
     * Permute an index among {@code [0, domain)}.
     * <p>
     * A small domain is permuted with a table, a large one with FF1 over enough digits, cycle
     * walking until the result falls back inside the domain.
     *
     * @param index   the index to permute, inside the domain
     * @param domain  the size of the domain
     * @param tweak   the tweak, specific to the domain
     * @param forward whether to apply the permutation or its inverse
     * @return the permuted index, inside the domain
     */
    private long permute(long index, long domain, byte[] tweak, boolean forward) {
        if (domain <= TABLE_DOMAIN_LIMIT) {
            Permutation table = tableOf(tweak, (int) domain);
            return forward ? table.forward[(int) index] : table.inverse[(int) index];
        }
        int digits = Math.max(MIN_FF1_DIGITS, Long.toString(domain - 1).length());
        String padded = String.format("%0" + digits + "d", index);
        return Long.parseLong(walk(padded, tweak, forward, d -> Long.parseLong(d) < domain));
    }

    /**
     * Run FF1 with radix 10 over the given digits, again and again until the result belongs to the
     * domain. This is cycle walking: since the start belongs to the domain, the walk always ends.
     *
     * @param digits   the digits to transform, at least {@value #MIN_FF1_DIGITS}
     * @param tweak    the tweak
     * @param encrypt  whether to encrypt or to decrypt
     * @param inDomain whether a result belongs to the domain
     * @return the transformed digits, of the same length
     */
    private String walk(String digits, byte[] tweak, boolean encrypt, Predicate<String> inDomain) {
        FPEFF1Engine engine = new FPEFF1Engine();
        engine.init(encrypt, new FPEParameters(key, 10, tweak));
        byte[] input = new byte[digits.length()];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (digits.charAt(i) - '0');
        }
        byte[] output = new byte[input.length];
        String result;
        do {
            engine.processBlock(input, 0, input.length, output, 0);
            StringBuilder text = new StringBuilder(output.length);
            for (byte digit : output) {
                text.append((char) ('0' + digit));
            }
            result = text.toString();
            byte[] swap = input;
            input = output;
            output = swap;
        } while (!inDomain.test(result));
        return result;
    }

    /**
     * The keyed permutation of a small domain, built once and cached.
     * <p>
     * Every index is encrypted with FF1 over {@value #MIN_FF1_DIGITS} digits, then the indexes are
     * ranked by their ciphertext. FF1 being a pseudo random permutation, so is the ranking.
     */
    private Permutation tableOf(byte[] tweak, int domain) {
        String cacheKey = HexFormat.of().formatHex(tweak) + ':' + domain;
        Permutation cached = tables.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        FPEFF1Engine engine = new FPEFF1Engine();
        engine.init(true, new FPEParameters(key, 10, tweak));
        long[] ranked = new long[domain];
        byte[] input = new byte[MIN_FF1_DIGITS];
        byte[] output = new byte[MIN_FF1_DIGITS];
        for (int i = 0; i < domain; i++) {
            int value = i;
            for (int d = MIN_FF1_DIGITS - 1; d >= 0; d--) {
                input[d] = (byte) (value % 10);
                value /= 10;
            }
            engine.processBlock(input, 0, MIN_FF1_DIGITS, output, 0);
            long encrypted = 0;
            for (byte digit : output) {
                encrypted = encrypted * 10 + digit;
            }
            // The ciphertexts are distinct, so sorting on them alone gives a strict order.
            ranked[i] = encrypted * TABLE_DOMAIN_LIMIT + i;
        }
        Arrays.sort(ranked);

        int[] forward = new int[domain];
        int[] inverse = new int[domain];
        for (int rank = 0; rank < domain; rank++) {
            int index = (int) (ranked[rank] % TABLE_DOMAIN_LIMIT);
            forward[index] = rank;
            inverse[rank] = index;
        }

        if (tables.size() >= MAX_CACHED_TABLES) {
            tables.clear();
        }
        Permutation permutation = new Permutation(forward, inverse);
        tables.put(cacheKey, permutation);
        return permutation;
    }

    /**
     * Derive the tweak of a domain from the tweak of a property, so that the same property never
     * reuses a permutation across the dates, the integer parts and the fractions of each length.
     */
    private static byte[] subTweak(byte[] tweak, byte tag, int length) {
        byte[] derived = Arrays.copyOf(tweak, tweak.length + 2);
        derived[tweak.length] = tag;
        derived[tweak.length + 1] = (byte) length;
        return derived;
    }

    private static long pow10(int exponent) {
        long result = 1;
        for (int i = 0; i < exponent; i++) {
            result *= 10;
        }
        return result;
    }

    /**
     * Encrypt a value and wrap it as a token.
     *
     * @param clearValue the value to protect
     * @param tweak      the tweak of the property the value belongs to
     * @return the token, or the value unchanged when it cannot be encrypted
     */
    String protect(String clearValue, byte[] tweak) {
        if (clearValue.isEmpty()) {
            return clearValue;
        }
        if (clearValue.indexOf('{') >= 0 || clearValue.indexOf('}') >= 0) {
            // A brace kept in place inside the token would cut it short when it is located again on
            // the way back, so the value would come back truncated. Better leave it alone. This also
            // keeps an already protected value from being protected a second time.
            LOG.warn("Value carrying a brace cannot be protected with FPE, leaving it unchanged.");
            return clearValue;
        }

        Alphabet alphabet = alphabetOf(clearValue);
        String encryptable = keep(clearValue, alphabet);
        if (encryptable.isEmpty()) {
            // Nothing in the alphabet, for instance "---": there is simply nothing to encrypt.
            LOG.debug("No character of the alphabet in the value, leaving it unchanged.");
            return clearValue;
        }

        int padding = Math.max(0, alphabet.minimumLength - encryptable.length());
        String padded = encryptable + String.valueOf(PADDING_CHAR).repeat(padding);

        String encrypted;
        try {
            encrypted = cipher(padded, alphabet, tweak, true);
        } catch (RuntimeException e) {
            LOG.warn("Could not protect a value of {} characters with FPE, leaving it unchanged: {}",
                    padded.length(), e.getMessage());
            return clearValue;
        }

        // Put the characters that are not part of the alphabet back where they were, so that the
        // clear value can be rebuilt exactly when unprotecting.
        String formatted = merge(clearValue, encrypted, alphabet);
        return TOKEN_PREFIX + alphabet.marker + padding + formatted + TOKEN_SUFFIX;
    }

    /**
     * Decrypt a token back into its clear value.
     *
     * @param token the token to unprotect
     * @param tweak the tweak of the property the value belongs to
     * @return the clear value, or the input unchanged when it is not a token this class produced
     */
    String unprotect(String token, byte[] tweak) {
        if (!isToken(token)) {
            // Mixed payloads happen, a value that was never protected is not an error.
            LOG.debug("Value is not a token, leaving it unchanged.");
            return token;
        }

        String payload = token.substring(TOKEN_PREFIX.length(), token.length() - TOKEN_SUFFIX.length());
        if (payload.length() < HEADER_LENGTH) {
            LOG.warn("Token is too short to carry its header, leaving it unchanged.");
            return token;
        }

        Alphabet alphabet = Alphabet.ofMarker(payload.charAt(0));
        int padding = Character.digit(payload.charAt(1), 10);
        if (alphabet == null || padding < 0) {
            // Typically an RPS token, which has a different header: it is not ours to decrypt.
            LOG.warn("Token header {} was not produced by FPE, leaving the value unchanged.",
                    payload.substring(0, HEADER_LENGTH));
            return token;
        }

        String formatted = payload.substring(HEADER_LENGTH);
        String encrypted = keep(formatted, alphabet);
        if (encrypted.length() <= padding) {
            LOG.warn("Token announces {} padding characters but carries only {}, leaving the value unchanged.",
                    padding, encrypted.length());
            return token;
        }

        String decrypted;
        try {
            decrypted = cipher(encrypted, alphabet, tweak, false);
        } catch (RuntimeException e) {
            LOG.warn("Could not unprotect a token with FPE, leaving it unchanged: {}", e.getMessage());
            return token;
        }

        // Drop the filler that was appended to reach the minimum length, then restore the formatting.
        String clear = decrypted.substring(0, decrypted.length() - padding);
        return merge(formatted, clear, alphabet);
    }

    /**
     * Run FF1 over the characters of an alphabet.
     *
     * @param value         the characters to transform, all belonging to the alphabet
     * @param alphabet      the alphabet giving the radix
     * @param tweak         the tweak of the property
     * @param forEncryption whether to encrypt or to decrypt
     * @return the transformed characters
     */
    private String cipher(String value, Alphabet alphabet, byte[] tweak, boolean forEncryption) {
        byte[] indexes = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            indexes[i] = (byte) alphabet.indexOf(value.charAt(i));
        }

        FPEFF1Engine engine = new FPEFF1Engine();
        engine.init(forEncryption, new FPEParameters(key, alphabet.radix(), tweak));

        byte[] result = new byte[indexes.length];
        engine.processBlock(indexes, 0, indexes.length, result, 0);

        StringBuilder transformed = new StringBuilder(result.length);
        for (byte index : result) {
            transformed.append(alphabet.charAt(index & 0xFF));
        }
        return transformed.toString();
    }

    /**
     * Choose the alphabet of a value from the characters it holds.
     * <p>
     * A value made of digits only keeps the numeric alphabet, so that its token stays digits and
     * separators once the braces are removed.
     *
     * @param value the value to inspect
     * @return the alphabet to encrypt it with
     */
    private static Alphabet alphabetOf(String value) {
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (Alphabet.ALPHANUMERIC.indexOf(c) >= 0) {
                // A letter of the alphabet: the value cannot be handled as a number.
                return Alphabet.ALPHANUMERIC;
            }
        }
        return hasDigit ? Alphabet.NUMERIC : Alphabet.ALPHANUMERIC;
    }

    /**
     * Keep only the characters belonging to an alphabet.
     *
     * @param value    the value to filter
     * @param alphabet the alphabet to keep
     * @return the characters to hand over to the cipher
     */
    private static String keep(String value, Alphabet alphabet) {
        StringBuilder kept = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            if (alphabet.indexOf(value.charAt(i)) >= 0) {
                kept.append(value.charAt(i));
            }
        }
        return kept.toString();
    }

    /**
     * Rebuild a value by putting transformed characters back into the layout of another one.
     * <p>
     * The characters that do not belong to the alphabet are taken from the template, the others are
     * consumed in order from the transformed characters. This is what preserves the separators of
     * the original value through a round trip.
     * <p>
     * Transformed characters left over once the template is exhausted are appended at the end. That
     * happens when protecting a value that had to be padded: the ciphertext is then longer than the
     * clear value, and dropping the extra characters would make the token impossible to decrypt.
     *
     * @param template    the value giving the layout
     * @param transformed the transformed characters, in order
     * @param alphabet    the alphabet telling which characters were transformed
     * @return the rebuilt value
     */
    private static String merge(String template, String transformed, Alphabet alphabet) {
        StringBuilder merged = new StringBuilder(template.length());
        int next = 0;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (alphabet.indexOf(c) < 0) {
                merged.append(c);
            } else if (next < transformed.length()) {
                merged.append(transformed.charAt(next++));
            }
            // Beyond the transformed characters the padding was dropped: nothing left to write.
        }
        // Padding characters of a protected value, which the clear layout has no room for.
        merged.append(transformed, next, transformed.length());
        return merged.toString();
    }

    /**
     * Whether a value is wrapped like a token.
     *
     * @param value the value to inspect
     * @return {@code true} when the value is wrapped in <code>RG{</code> and <code>}</code>
     */
    private static boolean isToken(String value) {
        return value.startsWith(TOKEN_PREFIX) && value.endsWith(TOKEN_SUFFIX)
                && value.length() > TOKEN_PREFIX.length();
    }

    /**
     * Derive the tweak of a property.
     * <p>
     * Giving each property its own tweak means the same clear value encrypts differently in two
     * different properties, so an encrypted value cannot be correlated across fields.
     *
     * @param mapping the RPS class and property of the value, may be {@code null}
     * @return the tweak, empty when the value carries no mapping
     */
    static byte[] tweakOf(ValueMapping mapping) {
        if (mapping == null || mapping.getClassName() == null || mapping.getPropertyName() == null) {
            LOG.debug("Value without mapping, using an empty FPE tweak.");
            return new byte[0];
        }
        String qualifiedName = mapping.getClassName() + "." + mapping.getPropertyName();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(qualifiedName.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(digest, TWEAK_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK, so this cannot happen on a supported runtime.
            throw new IllegalStateException("SHA-256 is not available to derive the FPE tweak", e);
        }
    }

    /**
     * Decode a hexadecimal string.
     *
     * @param hex the string to decode, of even length
     * @return the decoded bytes
     * @throws IllegalArgumentException when the string is not valid hexadecimal
     */
    private static byte[] decodeHex(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("an hexadecimal string must have an even number of characters");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid character at position " + (i * 2));
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}
