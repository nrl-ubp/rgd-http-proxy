package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
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
public final class FlightSqlTokenIndexResolver {

    private static final Logger LOG = LoggerFactory.getLogger(FlightSqlTokenIndexResolver.class);

    /** Prefix introducing the mapping index inside a token. */
    private static final String TOKEN_PREFIX = "RG{";

    /** Fixed width of the mapping index. */
    static final int INDEX_LENGTH = 2;

    /** Filler padding a one-letter index to {@link #INDEX_LENGTH}. */
    static final char FILLER = 'x';

    /** Letter escaping the second range of the encoding. */
    private static final char ESCAPE = 'Z';

    /** Number of indexes the encoding can represent, {@code Ax} to {@code ZZ}. */
    static final int MAX_INDEX = 51;

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

    private FlightSqlTokenIndexResolver() {
    }

    /**
     * Declare the RPS class / property of a mapping index.
     *
     * @param symbol the index symbol, padded ({@code "Bx"}) or not ({@code "B"})
     * @param mappingName the {@code "ClassName.PropertyName"} it resolves to
     */
    static void register(String symbol, String mappingName) {
        INDEX_MAPPINGS.put(normalize(symbol), mappingName);
    }

    /** Withdraw a declared mapping index. Used by the tests. */
    static void unregister(String symbol) {
        INDEX_MAPPINGS.remove(normalize(symbol));
    }

    /**
     * Withdraw every declared mapping index, so that the table can be repopulated from scratch. The
     * symbols already warned about are forgotten too: after a reload an unknown symbol is worth
     * reporting again.
     */
    static void clear() {
        INDEX_MAPPINGS.clear();
        resetWarnings();
    }

    /** @return the number of declared mapping indexes */
    static int size() {
        return INDEX_MAPPINGS.size();
    }

    /**
     * Pad a one-character symbol with the filler, leaving anything else untouched.
     *
     * @param symbol the symbol to normalize
     * @return the symbol at its fixed width, or the input when it cannot be padded
     */
    static String normalize(String symbol) {
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
    static String mappingIndexOf(String token) {
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
    static int decodeMappingIndex(String symbol) {
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
    static String encodeMappingIndex(int index) {
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
    public static RPSMapping resolveMapping(String token) {
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
        return new RPSMapping(mappingName.substring(0, separator), mappingName.substring(separator + 1));
    }

    /** Forget the symbols already warned about, so that they are reported again. */
    private static void resetWarnings() {
        WARNED_SYMBOLS.clear();
    }
}
