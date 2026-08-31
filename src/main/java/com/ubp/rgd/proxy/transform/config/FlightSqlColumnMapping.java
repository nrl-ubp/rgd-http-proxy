package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps a database table / column to the RPS class and property names used to detokenize the values
 * returned by the Flight SQL server.
 * <p>
 * The {@code table} entry is optional: when omitted (or set to {@code *}) the mapping applies to any
 * table exposing a column with that name.
 */
public class FlightSqlColumnMapping {

    /** Wildcard accepted for the {@code table} entry. */
    public static final String ANY_TABLE = "*";

    @JsonProperty(value = "table")
    private String table;

    @JsonProperty(value = "column")
    private String column;

    @JsonProperty(value = "rps-class-name")
    private String rpsClassName;

    @JsonProperty(value = "rps-property-name")
    private String rpsPropertyName;

    public FlightSqlColumnMapping() {
        // Jackson
    }

    public FlightSqlColumnMapping(String table, String column, String rpsClassName, String rpsPropertyName) {
        this.table = table;
        this.column = column;
        this.rpsClassName = rpsClassName;
        this.rpsPropertyName = rpsPropertyName;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getRpsClassName() {
        return rpsClassName;
    }

    public void setRpsClassName(String rpsClassName) {
        this.rpsClassName = rpsClassName;
    }

    public String getRpsPropertyName() {
        return rpsPropertyName;
    }

    public void setRpsPropertyName(String rpsPropertyName) {
        this.rpsPropertyName = rpsPropertyName;
    }

    /**
     * @return true when this mapping applies to any table (no table set, empty or {@code *})
     */
    public boolean isWildcardTable() {
        return table == null || table.isBlank() || ANY_TABLE.equals(table.trim());
    }

    @Override
    public String toString() {
        return (isWildcardTable() ? ANY_TABLE : table) + "." + column
                + " -> " + rpsClassName + "/" + rpsPropertyName;
    }
}
