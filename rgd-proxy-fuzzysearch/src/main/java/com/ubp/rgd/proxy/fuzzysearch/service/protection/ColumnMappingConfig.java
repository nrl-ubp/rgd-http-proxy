package com.ubp.rgd.proxy.fuzzysearch.service.protection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.transform.model.ValueMapping;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The RPS class and property of each column, and the contexts to transform with, read from the
 * Flight SQL mapping file of the proxy ({@code flight_sql_mapping_config.json}).
 * <p>
 * Reading the very same file as the proxy is what makes a value protected through the Flight SQL
 * {@code transform()} extension readable here: with FPE the class and property are the tweak, and
 * with RPS they select the protection, so both sides must agree on them.
 * <p>
 * A column is resolved like the proxy does: an exact {@code table.column} mapping first, then a
 * {@code *.column} one, case-insensitively.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ColumnMappingConfig {

    /** The table name matching every table. */
    public static final String ANY_TABLE = "*";

    @JsonProperty("before")
    private Phase before = new Phase();

    @JsonProperty("after")
    private Phase after = new Phase();

    @JsonProperty("column-mappings")
    private List<ColumnMapping> columnMappings = List.of();

    private Map<String, ValueMapping> index;

    /**
     * Read a mapping file.
     *
     * @param path the path of the Flight SQL mapping file
     * @return the configuration
     * @throws IllegalStateException when the file cannot be read
     */
    public static ColumnMappingConfig load(String path) {
        try {
            ColumnMappingConfig config = new ObjectMapper().readValue(new File(path), ColumnMappingConfig.class);
            return config == null ? new ColumnMappingConfig() : config;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the column mappings of " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * The mapping of a column.
     *
     * @param table  the table, may be {@code null}
     * @param column the column
     * @return the mapping, or {@code null} when the column is not mapped
     */
    public ValueMapping find(String table, String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        Map<String, ValueMapping> lookup = index();
        if (table != null && !table.isBlank()) {
            ValueMapping exact = lookup.get(key(table, column));
            if (exact != null) {
                return exact;
            }
        }
        return lookup.get(key(ANY_TABLE, column));
    }

    /**
     * The mapping of a column that must be mapped.
     *
     * @throws IllegalStateException when the column is not mapped
     */
    public ValueMapping require(String table, String column) {
        ValueMapping mapping = find(table, column);
        if (mapping == null) {
            throw new IllegalStateException("No column mapping for " + table + "." + column
                    + ": add one to the column-mappings of the Flight SQL mapping file.");
        }
        return mapping;
    }

    /** @return the evidences used to protect, the {@code before} phase */
    public Phase before() {
        return before == null ? new Phase() : before;
    }

    /** @return the evidences used to unprotect, the {@code after} phase */
    public Phase after() {
        return after == null ? new Phase() : after;
    }

    public void setColumnMappings(List<ColumnMapping> columnMappings) {
        this.columnMappings = columnMappings;
        this.index = null;
    }

    private synchronized Map<String, ValueMapping> index() {
        if (index == null) {
            Map<String, ValueMapping> built = new HashMap<>();
            if (columnMappings != null) {
                for (ColumnMapping mapping : columnMappings) {
                    if (mapping == null || mapping.column == null || mapping.column.isBlank()
                            || mapping.className == null || mapping.propertyName == null) {
                        continue;
                    }
                    String table = mapping.table == null || mapping.table.isBlank() ? ANY_TABLE : mapping.table.trim();
                    built.put(key(table, mapping.column), new ValueMapping(mapping.className, mapping.propertyName));
                }
            }
            index = built;
        }
        return index;
    }

    private static String key(String table, String column) {
        return table.trim().toUpperCase(Locale.ROOT) + "." + column.trim().toUpperCase(Locale.ROOT);
    }

    /** A column and the RPS class and property it is protected with. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnMapping {

        @JsonProperty("table")
        public String table;

        @JsonProperty("column")
        public String column;

        @JsonProperty("rps-class-name")
        public String className;

        @JsonProperty("rps-property-name")
        public String propertyName;

        public ColumnMapping() {
        }

        public ColumnMapping(String table, String column, String className, String propertyName) {
            this.table = table;
            this.column = column;
            this.className = className;
            this.propertyName = propertyName;
        }
    }

    /** The evidences of one direction. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Phase {

        @JsonProperty("right-context")
        public Map<String, String> rightContext = new HashMap<>();

        @JsonProperty("processing-context")
        public Map<String, String> processingContext = new HashMap<>();
    }
}
