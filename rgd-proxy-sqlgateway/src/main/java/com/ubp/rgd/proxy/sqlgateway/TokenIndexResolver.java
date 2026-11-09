package com.ubp.rgd.proxy.sqlgateway;

import com.ubp.rgd.proxy.transform.model.ValueMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the RPS class / property of a token from the <b>mapping index</b> it carries in the first
 * two characters of its value, right after {@code RG&#123;}.
 * <p>
 * This is the last resort of the Flight SQL detokenization: it applies to the tokens whose column has
 * no mapping and which no data-mapping regex matched.
 *
 * <h2>Mapping index encoding</h2>
 * The index is a fixed width of 2 characters: an uppercase base26 number, left-aligned and padded with
 * a lowercase {@code x} filler.
 * <table border="1">
 *     <caption>Encoding of the mapping index</caption>
 *     <tr><th>Symbol</th><th>Index</th><th>Rule</th></tr>
 *     <tr><td>{@code Ax} … {@code Zx}</td><td>0 … 25</td><td>one letter followed by the filler</td></tr>
 *     <tr><td>{@code ZA} … {@code ZZ}</td><td>26 … 51</td>
 *         <td>{@code Z} is an escape prefix, the second letter carries the value</td></tr>
 * </table>
 * The filler being lowercase, {@code Zx} (25) and {@code ZA} (26) are unambiguous. Any other shape
 * ({@code BA}, {@code A3}, {@code 2x}, …) resolves to nothing: the resolution is lenient and never
 * fails a query, it only logs a warning.
 */
public final class TokenIndexResolver {

    private static final Logger LOG = LoggerFactory.getLogger(TokenIndexResolver.class);

    /** Prefix introducing the mapping index inside a token. */
    private static final String TOKEN_PREFIX = "RG{";

    /** Fixed width of the mapping index. */
    public static final int INDEX_LENGTH = 2;

    /** Filler padding a one-letter index to {@link #INDEX_LENGTH}. */
    public static final char FILLER = 'x';

    /** Letter escaping the second range of the encoding. */
    private static final char ESCAPE = 'Z';

    /** Number of indexes the encoding can represent, {@code Ax} to {@code ZZ}. */
    public static final int MAX_INDEX = 51;

    /** Index above which the escaped form is used. */
    private static final int ESCAPE_THRESHOLD = 26;

    /**
     * Mapping index to {@code "ClassName.PropertyName"} table, keyed by the padded symbol.
     * <p>
     * The table starts empty and is populated at startup by
     * {@link FlightSqlDetokenizeService#loadTokenIndexMappings()}, since its content depends on the RPS
     * client the proxy is configured for. While it is empty, this last resolution tier simply resolves
     * nothing and the tokens are returned untouched.
     * <p>
     * Being static, the table is JVM-wide: a test populating it must clear it afterwards.
     */
    private static final Map<String, String> INDEX_MAPPINGS = new LinkedHashMap<>();

    /** Symbols already reported as unresolvable, so that each one is only warned about once. */
    private static final Set<String> WARNED_SYMBOLS = ConcurrentHashMap.newKeySet();

    private TokenIndexResolver() {
    }

    /**
     * Declare the RPS class / property of a mapping index.
     *
     * @param symbol the index symbol, padded ({@code "Bx"}) or not ({@code "B"})
     * @param mappingName the {@code "ClassName.PropertyName"} it resolves to
     */
    public static void register(String symbol, String mappingName) {
        INDEX_MAPPINGS.put(normalize(symbol), mappingName);
    }

    /** Withdraw a declared mapping index. Used by the tests. */
    public static void unregister(String symbol) {
        INDEX_MAPPINGS.remove(normalize(symbol));
    }

    /**
     * Withdraw every declared mapping index, so that the table can be repopulated from scratch. The
     * symbols already warned about are forgotten too: after a reload an unknown symbol is worth
     * reporting again.
     */
    public static void clear() {
        INDEX_MAPPINGS.clear();
        resetWarnings();
    }

    /** @return the number of declared mapping indexes */
    public static int size() {
        return INDEX_MAPPINGS.size();
    }

    /**
     * Pad a one-character symbol with the filler, leaving anything else untouched.
     *
     * @param symbol the symbol to normalize
     * @return the symbol at its fixed width, or the input when it cannot be padded
     */
    public static String normalize(String symbol) {
        if (symbol != null && symbol.length() == INDEX_LENGTH - 1) {
            return symbol + FILLER;
        }
        return symbol;
    }

    /**
     * Read the mapping index symbol carried by a token.
     *
     * @param token the token, expected to start with {@code RG&#123;}
     * @return the two characters following the prefix, or {@code null} when the value is too short or
     *         is not a token
     */
    public static String mappingIndexOf(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)
                || token.length() < TOKEN_PREFIX.length() + INDEX_LENGTH) {
            return null;
        }
        return token.substring(TOKEN_PREFIX.length(), TOKEN_PREFIX.length() + INDEX_LENGTH);
    }

    /**
     * Decode a mapping index symbol into its numeric value.
     *
     * @param symbol the two-character symbol
     * @return the index in {@code [0, 51]}, or {@code -1} when the symbol does not follow the encoding
     */
    public static int decodeMappingIndex(String symbol) {
        if (symbol == null || symbol.length() != INDEX_LENGTH) {
            return -1;
        }
        char first = symbol.charAt(0);
        char second = symbol.charAt(1);
        if (first < 'A' || first > 'Z') {
            return -1;
        }
        if (second == FILLER) {
            return first - 'A';
        }
        // Only the escape letter introduces the second range, so Zx (25) and ZA (26) never collide.
        if (first == ESCAPE && second >= 'A' && second <= 'Z') {
            return ESCAPE_THRESHOLD + (second - 'A');
        }
        return -1;
    }

    /**
     * Encode a numeric mapping index back into its symbol. Mainly used by the tests and the log
     * messages.
     *
     * @param index the index to encode
     * @return the two-character symbol
     * @throws IllegalArgumentException when the index cannot be represented
     */
    public static String encodeMappingIndex(int index) {
        if (index < 0 || index > MAX_INDEX) {
            throw new IllegalArgumentException(
                    "Mapping index out of the [0, " + MAX_INDEX + "] range: " + index);
        }
        if (index < ESCAPE_THRESHOLD) {
            return String.valueOf((char) ('A' + index)) + FILLER;
        }
        return String.valueOf(ESCAPE) + (char) ('A' + index - ESCAPE_THRESHOLD);
    }

    /**
     * Resolve the {@code "ClassName.PropertyName"} a token belongs to, from the mapping index it
     * carries.
     *
     * @param token the token, {@code RG&#123;<i>index</i>…&#125;}
     * @return the qualified property name, or {@code null} when the token carries no usable index or
     *         when that index is not declared in the table
     */
    public static String resolveMappingName(String token) {
        String symbol = mappingIndexOf(token);
        if (symbol == null) {
            return null;
        }
        String mappingName = INDEX_MAPPINGS.get(symbol);
        if (mappingName == null) {
            // The symbol may have been declared through the other range of the encoding.
            int index = decodeMappingIndex(symbol);
            if (index >= 0) {
                mappingName = INDEX_MAPPINGS.get(encodeMappingIndex(index));
            }
        }
        if (mappingName == null && WARNED_SYMBOLS.add(symbol)) {
            LOG.warn("No RPS mapping declared for the token mapping index '{}' (index {});"
                            + " the matching tokens are returned untouched.",
                    symbol, decodeMappingIndex(symbol));
        }
        return mappingName;
    }

    /**
     * Resolve the RPS mapping of a token from the mapping index it carries.
     *
     * @param token the token
     * @return the mapping, or {@code null} when the index resolves to nothing or to a name that is not
     *         qualified by a class
     */
    public static ValueMapping resolveMapping(String token) {
        String mappingName = resolveMappingName(token);
        if (mappingName == null) {
            return null;
        }
        // Split on the last separator so that a dotted class name keeps working.
        int separator = mappingName.lastIndexOf('.');
        if (separator <= 0 || separator == mappingName.length() - 1) {
            LOG.warn("Ignoring the malformed mapping '{}' declared for the token mapping index '{}':"
                    + " expected ClassName.PropertyName.", mappingName, mappingIndexOf(token));
            return null;
        }
        return new ValueMapping(mappingName.substring(0, separator), mappingName.substring(separator + 1));
    }

    /** Forget the symbols already warned about, so that they are reported again. */
    private static void resetWarnings() {
        WARNED_SYMBOLS.clear();
    }

    /**
     * (Re)build the table for the RPS client the proxy is configured for. The table is cleared first,
     * and stays empty for a transformer whose tokens carry no mapping index. A failure leaves it
     * empty rather than preventing the proxy from starting: the mapping index is only the last
     * detokenization tier.
     *
     * @param supportsMappingIndex whether the selected transformer writes a mapping index
     * @param clientId the value of {@code proxy.transform.client-id}, may be {@code null}
     * @param loader registers the indexes of the client, typically {@link #registerClientMappings}
     */
    public static void load(boolean supportsMappingIndex, String clientId, java.util.function.Consumer<String> loader) {
        clear();
        if (!supportsMappingIndex) {
            LOG.info("The selected transformer produces tokens without a mapping index."
                    + " The token mapping index tier is disabled.");
            return;
        }
        try {
            loader.accept(clientId);
            LOG.info("Loaded {} token mapping index(es) for the RPS client {}", size(), clientId);
        } catch (Exception e) {
            LOG.error("Failed to load the token mapping indexes for the RPS client {}."
                    + " The tokens resolved by their mapping index will be returned untouched.", clientId, e);
            clear();
        }
    }

    /**
     * Declare the mapping indexes of an RPS client.
     * <p>
     * A symbol may be declared padded ({@code "Bx"}) or not ({@code "B"}), both being the same index.
     *
     * @param clientId the value of {@code proxy.transform.client-id}, may be {@code null}
     */
    public static void registerClientMappings(String clientId) {
        // TODO build the table for the given client id, for example by querying the RPS catalogue.
        TokenIndexResolver.register("B", "Person.ShortString");
        TokenIndexResolver.register("C", "Person.Date");
        TokenIndexResolver.register("D", "Person.Email");
        TokenIndexResolver.register("E", "Person.PhoneNumber");
        TokenIndexResolver.register("G", "Person.LongString");
        TokenIndexResolver.register("J", "Other.Number");
        TokenIndexResolver.register("K", "Other.LongText");
        TokenIndexResolver.register("L", "Other.ShortText");
        TokenIndexResolver.register("M", "Other.UID");
        TokenIndexResolver.register("N", "Person.TIN");
        TokenIndexResolver.register("O", "Person.VAT");
        TokenIndexResolver.register("P", "Person.SocialSecurityNumber");
        TokenIndexResolver.register("Q", "Address.AddPart");
        TokenIndexResolver.register("R", "Address.PostalCode");
        TokenIndexResolver.register("S", "Address.RegionOrState");
        TokenIndexResolver.register("T", "Address.City");
        TokenIndexResolver.register("U", "Address.GeoCoding");
        TokenIndexResolver.register("V", "Financial.SwiftPart");
        TokenIndexResolver.register("W", "Financial.IBAN");
        TokenIndexResolver.register("X", "Financial.CreditCard");
        TokenIndexResolver.register("Y", "Person.AccountName");
    }
}
