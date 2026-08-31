package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration of the Flight SQL detokenization: the RPS contexts to use and the table/column to
 * RPS class/property mappings.
 * <p>
 * Loaded from the JSON file pointed to by {@code proxy.flight-sql.mapping-config-file}.
 */
public class FlightSqlMappingConfig {

    @JsonProperty(value = "right-context")
    private Map<String, String> rightContextEvidences = new HashMap<>();

    @JsonProperty(value = "processing-context")
    private Map<String, String> processingContextEvidences = new HashMap<>();

    @JsonProperty(value = "column-mappings")
    private List<FlightSqlColumnMapping> columnMappings = new ArrayList<>();

    /** Lazily built lookup index: {@code table|column} (lower-case) -> mapping. */
    private transient Map<String, FlightSqlColumnMapping> index;

    public Map<String, String> getRightContextEvidences() {
        return rightContextEvidences;
    }

    public void setRightContextEvidences(Map<String, String> rightContextEvidences) {
        this.rightContextEvidences = rightContextEvidences;
    }

    public Map<String, String> getProcessingContextEvidences() {
        return processingContextEvidences;
    }

    public void setProcessingContextEvidences(Map<String, String> processingContextEvidences) {
        this.processingContextEvidences = processingContextEvidences;
    }

    public List<FlightSqlColumnMapping> getColumnMappings() {
        return columnMappings;
    }

    public void setColumnMappings(List<FlightSqlColumnMapping> columnMappings) {
        this.columnMappings = columnMappings;
        this.index = null;
    }

    /**
     * Resolve the RPS mapping of a result-set column. An exact {@code table.column} match wins over a
     * wildcard {@code *.column} mapping. The lookup is case-insensitive.
     *
     * @param table the table name reported by the result-set metadata (may be null/empty)
     * @param column the column name / label
     * @return the matching mapping, or {@code null} when the column must not be detokenized
     */
    public FlightSqlColumnMapping findMapping(String table, String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        Map<String, FlightSqlColumnMapping> lookup = index();
        if (table != null && !table.isBlank()) {
            FlightSqlColumnMapping exact = lookup.get(key(table, column));
            if (exact != null) {
                return exact;
            }
        }
        return lookup.get(key(FlightSqlColumnMapping.ANY_TABLE, column));
    }

    private synchronized Map<String, FlightSqlColumnMapping> index() {
        if (index == null) {
            Map<String, FlightSqlColumnMapping> built = new HashMap<>();
            if (columnMappings != null) {
                for (FlightSqlColumnMapping mapping : columnMappings) {
                    if (mapping == null || mapping.getColumn() == null || mapping.getColumn().isBlank()) {
                        continue;
                    }
                    String table = mapping.isWildcardTable()
                            ? FlightSqlColumnMapping.ANY_TABLE
                            : mapping.getTable().trim();
                    built.put(key(table, mapping.getColumn()), mapping);
                }
            }
            index = built;
        }
        return index;
    }

    private static String key(String table, String column) {
        return table.toLowerCase(Locale.ROOT).trim() + "|" + column.toLowerCase(Locale.ROOT).trim();
    }
}
