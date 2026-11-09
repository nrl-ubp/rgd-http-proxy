package com.ubp.rgd.proxy.sqlgateway.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration of the Flight SQL transformation: the RPS contexts of each phase of an exchange and
 * the table/column to RPS class/property mappings.
 * <p>
 * The contexts are grouped per phase, {@code before} the query reaches the database server and
 * {@code after} the result set comes back, because the two directions use opposite actions. The
 * {@code before} phase drives the {@code transform()} SQL extension, the {@code after} phase drives
 * the detokenization of the result sets.
 * <p>
 * Loaded from the JSON file pointed to by {@code proxy.flight-sql.mapping-config-file}.
 *
 * @see PhaseConfig
 */
@RegisterForReflection
public class MappingConfig {

    /**
     * Phase applied to the query on its way to the database server, that is the {@code transform()}
     * SQL extension.
     *
     * @see com.ubp.rgd.proxy.flightsql.FlightSqlTokenizeService
     */
    @JsonProperty(value = "before")
    private PhaseConfig before = new PhaseConfig();

    /**
     * Phase applied to the result set on its way back, that is the detokenization.
     * <p>
     * Defaults to an active phase rather than to {@code null}, so a configuration built in code, with
     * no file behind it, transforms as it always did.
     */
    @JsonProperty(value = "after")
    private PhaseConfig after = new PhaseConfig();

    @JsonProperty(value = "column-mappings")
    private List<ColumnMapping> columnMappings = new ArrayList<>();

    @JsonProperty(value = "data-mappings")
    private List<DataMapping> dataMappings = new ArrayList<>();

    /** Lazily built lookup index: {@code table|column} (lower-case) -> mapping. */
    private transient Map<String, ColumnMapping> index;

    /** Lazily filtered list of the data mappings that can actually be used. */
    private transient List<DataMapping> usableDataMappings;

    public PhaseConfig getBefore() {
        return before;
    }

    public void setBefore(PhaseConfig before) {
        this.before = before;
    }

    public PhaseConfig getAfter() {
        return after;
    }

    public void setAfter(PhaseConfig after) {
        this.after = after;
    }

    /**
     * Whether the result set coming back from the database server has to be transformed at all.
     *
     * @return true when the {@code after} phase is declared and active
     */
    public boolean isAfterActive() {
        return after != null && after.isActive();
    }

    public List<ColumnMapping> getColumnMappings() {
        return columnMappings;
    }

    public void setColumnMappings(List<ColumnMapping> columnMappings) {
        this.columnMappings = columnMappings;
        this.index = null;
    }

    public List<DataMapping> getDataMappings() {
        return dataMappings;
    }

    public void setDataMappings(List<DataMapping> dataMappings) {
        this.dataMappings = dataMappings;
        this.usableDataMappings = null;
    }

    /**
     * The data mappings driving the implicit detokenization, in declaration order — which is also
     * their priority order when several of them match overlapping segments of a same value. Mappings
     * with an invalid regex or missing RPS names are filtered out.
     *
     * @return the usable data mappings, never null
     */
    public synchronized List<DataMapping> getUsableDataMappings() {
        if (usableDataMappings == null) {
            usableDataMappings = dataMappings == null
                    ? List.of()
                    : dataMappings.stream().filter(m -> m != null && m.isUsable()).toList();
        }
        return usableDataMappings;
    }

    /**
     * Resolve the RPS mapping of a result-set column. An exact {@code table.column} match wins over a
     * wildcard {@code *.column} mapping. The lookup is case-insensitive.
     *
     * @param table the table name reported by the result-set metadata (may be null/empty)
     * @param column the column name / label
     * @return the matching mapping, or {@code null} when the column must not be detokenized
     */
    public ColumnMapping findMapping(String table, String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        Map<String, ColumnMapping> lookup = index();
        if (table != null && !table.isBlank()) {
            ColumnMapping exact = lookup.get(key(table, column));
            if (exact != null) {
                return exact;
            }
        }
        return lookup.get(key(ColumnMapping.ANY_TABLE, column));
    }

    private synchronized Map<String, ColumnMapping> index() {
        if (index == null) {
            Map<String, ColumnMapping> built = new HashMap<>();
            if (columnMappings != null) {
                for (ColumnMapping mapping : columnMappings) {
                    if (mapping == null || mapping.getColumn() == null || mapping.getColumn().isBlank()) {
                        continue;
                    }
                    String table = mapping.isWildcardTable()
                            ? ColumnMapping.ANY_TABLE
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
