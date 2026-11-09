package com.ubp.rgd.proxy.fuzzysearch.service.protection;

import java.util.List;

/**
 * Protects and unprotects the columns of the {@code PERSON} table.
 * <p>
 * Every value is transformed with the RPS class and property of its column, so the implementation
 * must use the same mappings as whatever wrote the table: the rgd-http-proxy, through its Flight SQL
 * {@code transform()} extension, or this application through {@code POST /persons}.
 */
public interface ProtectionService {

    /**
     * A value along with the column it belongs to.
     *
     * @param column the column, such as {@code FIRST_NAME}
     * @param value  the value, may be {@code null}
     */
    record ColumnValue(String column, String value) {
    }

    /**
     * Splits {@code clear} on whitespace and lowercases each part.
     * E.g. {@code "Mohamed Ali"} → {@code ["mohamed", "ali"]}.
     *
     * @param clear raw input string
     * @return ordered list of lowercase tokens; empty list for blank/null input
     */
    List<String> tokenize(String clear);

    /**
     * Protect a value of a column, as a whole.
     *
     * @param column the column
     * @param clear  the clear value, may be {@code null}
     * @return the protected value, {@code null} for {@code null}
     */
    default String protect(String column, String clear) {
        return protectAll(List.of(new ColumnValue(column, clear))).get(0);
    }

    /**
     * Unprotect a value of a column: the whole value or every token it holds.
     *
     * @param column the column
     * @param value  the stored value, may be {@code null}
     * @return the clear value, {@code null} for {@code null}
     */
    default String unprotect(String column, String value) {
        return unprotectAll(List.of(new ColumnValue(column, value))).get(0);
    }

    /**
     * Protect several values in a single call to the transformer.
     *
     * @param values the values and their column
     * @return the protected values, in the same order
     */
    List<String> protectAll(List<ColumnValue> values);

    /**
     * Unprotect several values in a single call to the transformer.
     *
     * @param values the values and their column
     * @return the clear values, in the same order
     */
    List<String> unprotectAll(List<ColumnValue> values);
}
