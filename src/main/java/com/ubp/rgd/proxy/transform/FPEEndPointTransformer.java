package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
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
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Transforms data with Format Preserving Encryption instead of calling the RegData engine.
 * <p>
 * The encryption is the NIST SP 800-38G <b>FF1</b> mode, provided by BouncyCastle. Unlike
 * tokenization, FPE needs no remote engine and no token vault: protecting and unprotecting are two
 * directions of the same deterministic cipher, keyed by {@code proxy.transform.fpe.key}.
 *
 * <h2>Token format</h2>
 * The ciphertext is wrapped like an RPS token, <code>RG{...}</code>, because that is how
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
     *
     * @return the FPE token pattern
     */
    @Override
    public Pattern tokenPattern() {
        return ValuePlan.TOKEN_PATTERN;
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
     */
    @Override
    public void transformData(IRPSValue<String>[] values, Context rightContext, ProcessingContext processingContext) {
        String action = actionOf(processingContext);
        boolean protect = !ACTION_UNPROTECT.equalsIgnoreCase(action);
        if (!ACTION_PROTECT.equalsIgnoreCase(action) && !ACTION_UNPROTECT.equalsIgnoreCase(action)) {
            LOG.warn("Unsupported action {} for Format Preserving Encryption. Protecting the values.", action);
        }

        for (IRPSValue<String> value : values) {
            String original = value.getOriginal();
            if (original == null) {
                continue;
            }
            byte[] tweak = tweakOf(value.getMapping());
            // setValue() is what sets the transformed value: every value must get one, otherwise
            // reassembling the payload fails.
            value.setValue(protect ? protect(original, tweak) : unprotect(original, tweak));
        }
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
    static byte[] tweakOf(RPSMapping mapping) {
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
     * Read the action of a processing context.
     *
     * @param processingContext the processing context of the endpoint
     * @return the value of the {@code Action} evidence, or {@code null} when there is none
     */
    private static String actionOf(ProcessingContext processingContext) {
        if (processingContext == null || processingContext.getEvidences() == null) {
            return null;
        }
        return processingContext.getEvidences().stream()
                .filter(evidence -> "Action".equalsIgnoreCase(evidence.getName()))
                .map(Evidence::getValue)
                .findFirst()
                .orElse(null);
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
