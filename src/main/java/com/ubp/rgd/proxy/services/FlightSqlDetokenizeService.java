package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.RPSEngine;
import ch.regdata.rps.engine.client.RPSEngineConverter;
import ch.regdata.rps.engine.client.RequestContext;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.enginecontext.RightContext;
import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.transform.RPSClientEngineProvider;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.transform.config.FlightSqlColumnMapping;
import com.ubp.rgd.proxy.transform.config.FlightSqlDataMapping;
import com.ubp.rgd.proxy.transform.config.FlightSqlMappingConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VariableWidthFieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.util.TransferPair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detokenizes, through the RPS engine, the tokens found in the Arrow record batches produced by the
 * Flight SQL server.
 * <p>
 * Result sets carry no RPS metadata, so the RPS class / property names are resolved from the mapping
 * file configured by {@code proxy.flight-sql.mapping-config-file}, in this order:
 * <ol>
 *     <li><b>Column mappings</b> — when the table/column of a result-set column is mapped, its
 *     {@code RG{...}} tokens (see {@link #TOKEN_PATTERN}) are all detokenized with that mapping.</li>
 *     <li><b>Data mappings</b> — otherwise the column is detokenized implicitly: each configured
 *     regex locates its own segments in the values and supplies their class / property names.</li>
 * </ol>
 * Only the variable-width (string) columns are scanned, and all the tokens of a record batch are
 * batched into a single engine call.
 */
@ApplicationScoped
public class FlightSqlDetokenizeService {

    private static final Logger LOG = Logger.getLogger(FlightSqlDetokenizeService.class);

    /** Fixed delimiter pattern of an RPS token ({@code RG{...}}). */
    // static final Pattern TOKEN_PATTERN = Pattern.compile("RG\\{[^}]*\\}");
    static final Pattern TOKEN_PATTERN = Pattern.compile("(RG\\{[A-Z2-7x]{2}[a-zA-Z0-9\\-]{8}[a-zA-Z0-9]+\\})");

    @Inject
    RPSClientEngineProvider rpsClientEngineProvider;

    @ConfigProperty(name = "proxy.flight-sql.mapping-config-file",
            defaultValue = "./config/flight_sql_mapping_config.json")
    String mappingConfigFile;

    private FlightSqlMappingConfig mappingConfig = new FlightSqlMappingConfig();

    @PostConstruct
    void init() {
        File configFile = new File(mappingConfigFile);
        if (!configFile.exists()) {
            LOG.warnf("Flight SQL mapping config file not found: %s. No column will be detokenized.",
                    mappingConfigFile);
            return;
        }
        try {
            mappingConfig = new ObjectMapper().readValue(configFile, FlightSqlMappingConfig.class);
            LOG.infof("Loaded %d Flight SQL column mapping(s) from %s",
                    mappingConfig.getColumnMappings().size(), mappingConfigFile);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to load the Flight SQL mapping configuration from %s", mappingConfigFile);
            mappingConfig = new FlightSqlMappingConfig();
        }
    }

    FlightSqlMappingConfig getMappingConfig() {
        return mappingConfig;
    }

    public void setMappingConfig(FlightSqlMappingConfig mappingConfig) {
        this.mappingConfig = mappingConfig;
    }

    /**
     * Resolve, once per query, the RPS mapping of every result-set column.
     * <p>
     * Only the explicit {@code column-mappings} can be resolved from the metadata: the data mappings
     * driving the implicit detokenization depend on the values themselves and are therefore applied
     * later, while scanning the record batches.
     *
     * @param metaData the JDBC result-set metadata
     * @return a list aligned on the column indexes (0-based); an entry is {@code null} when the column
     *         has no explicit mapping, in which case it is detokenized implicitly through the
     *         configured data mappings (and left untouched when there is none)
     * @throws SQLException when the metadata cannot be read
     */
    public List<FlightSqlColumnMapping> resolveMappings(ResultSetMetaData metaData) throws SQLException {
        boolean implicitEnabled = !mappingConfig.getUsableDataMappings().isEmpty();
        List<FlightSqlColumnMapping> mappings = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String table = metaData.getTableName(i);
            // Prefer the label (alias) then fall back to the physical column name.
            FlightSqlColumnMapping mapping = mappingConfig.findMapping(table, metaData.getColumnLabel(i));
            if (mapping == null) {
                mapping = mappingConfig.findMapping(table, metaData.getColumnName(i));
            }
            if (mapping != null) {
                LOG.debugf("Column %s.%s will be detokenized as %s",
                        table, metaData.getColumnLabel(i), mapping);
            } else if (implicitEnabled) {
                LOG.debugf("Column %s.%s has no column mapping and will be detokenized implicitly.",
                        table, metaData.getColumnLabel(i));
            } else {
                LOG.debugf("Column %s.%s has no mapping and will be returned untouched.",
                        table, metaData.getColumnLabel(i));
            }
            mappings.add(mapping);
        }
        return mappings;
    }

    /**
     * Detokenize, in place, the string columns of the given record batch. A column having an explicit
     * {@link FlightSqlColumnMapping} has its {@code RG{...}} tokens detokenized with that mapping; any
     * other column falls back to the implicit mode driven by the configured data mappings. Values
     * without any recognized segment are left untouched, and the vectors of the batch are replaced
     * only when at least one segment was found.
     *
     * @param root the record batch to detokenize (modified in place)
     * @param mappings the per-column mappings resolved by {@link #resolveMappings(ResultSetMetaData)};
     *                 a {@code null} entry selects the implicit mode for that column
     * @param allocator the allocator used for the temporary vectors
     * @throws RPSTransformException when the RPS engine fails to detokenize a value
     */
    public void detokenize(VectorSchemaRoot root, List<FlightSqlColumnMapping> mappings,
                           BufferAllocator allocator) throws RPSTransformException {
        if (root == null || mappings == null || root.getRowCount() == 0) {
            return;
        }

        boolean implicitEnabled = !mappingConfig.getUsableDataMappings().isEmpty();
        List<ColumnPlan> plans = new ArrayList<>();
        List<RPSValue> flatValues = new ArrayList<>();

        for (int col = 0; col < root.getFieldVectors().size() && col < mappings.size(); col++) {
            FlightSqlColumnMapping mapping = mappings.get(col);
            FieldVector vector = root.getVector(col);
            if (!(vector instanceof VariableWidthFieldVector textVector)) {
                continue;
            }
            // Without a column mapping the column is only scanned when data mappings are configured.
            if (mapping == null && !implicitEnabled) {
                continue;
            }
            ColumnPlan plan = buildColumnPlan(col, textVector, mapping, root.getRowCount());
            if (plan != null) {
                plans.add(plan);
                plan.cells.forEach(cell -> flatValues.addAll(cell.rpsValues()));
            }
        }

        if (flatValues.isEmpty()) {
            return;
        }

        LOG.debugf("Detokenizing %d token(s) over %d column(s)", flatValues.size(), plans.size());
        transformValues(flatValues);

        for (ColumnPlan plan : plans) {
            applyColumnPlan(root, plan, allocator);
        }
    }

    /**
     * Hand the located segments over to the RPS engine, which replaces their transformed value in
     * place. Extracted so that it can be substituted in the tests.
     *
     * @param values the segments of the whole record batch, explicit and implicit mixed
     */
    protected void transformValues(List<RPSValue> values) throws RPSTransformException {
        try {
            transformData(rpsClientEngineProvider.getClientEngineProvider(),
                    values.toArray(new RPSValue[0]),
                    buildRightContext(),
                    buildProcessingContext());
        } catch (Exception e) {
            LOG.error("Flight SQL detokenization failed", e);
            throw new RPSTransformException(e);
        }
    }

    /**
     * Scan a string column and build the list of cells holding at least one segment to detokenize.
     *
     * @param columnMapping the explicit column mapping, or {@code null} to run in implicit mode
     * @return the plan, or {@code null} when nothing has to be detokenized in that column
     */
    private ColumnPlan buildColumnPlan(int columnIndex, VariableWidthFieldVector vector,
                                       FlightSqlColumnMapping columnMapping, int rowCount) {
        RPSMapping rpsMapping = columnMapping == null ? null
                : new RPSMapping(columnMapping.getRpsClassName(), columnMapping.getRpsPropertyName());
        List<FlightSqlDataMapping> dataMappings = mappingConfig.getUsableDataMappings();
        List<CellPlan> cells = new ArrayList<>();

        for (int row = 0; row < rowCount; row++) {
            if (vector.isNull(row)) {
                continue;
            }
            String value = new String(vector.get(row), StandardCharsets.UTF_8);
            List<Segment> segments = rpsMapping != null
                    ? extractTokens(value, rpsMapping)
                    : extractDataMappedSegments(value, dataMappings);
            if (!segments.isEmpty()) {
                cells.add(new CellPlan(row, value, segments));
            }
        }
        return cells.isEmpty() ? null : new ColumnPlan(columnIndex, cells);
    }

    /**
     * Locate the {@code RG{...}} tokens of a value, all mapped to the same RPS class / property. Used
     * for the columns having an explicit {@link FlightSqlColumnMapping}.
     *
     * @return the segments to detokenize, in order of appearance (empty when the value holds no token)
     */
    static List<Segment> extractTokens(String value, RPSMapping mapping) {
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(value);
        while (matcher.find()) {
            segments.add(new Segment(matcher.start(), matcher.end(),
                    new RPSValue(mapping, matcher.group())));
        }
        return segments;
    }

    /**
     * Locate the segments of a value using the data mappings — the implicit detokenization applied to
     * the columns without an explicit {@link FlightSqlColumnMapping}.
     * <p>
     * Every mapping is applied in declaration order, which is therefore its priority: a match
     * overlapping a segment already kept by an earlier mapping is discarded. The segment handed to the
     * engine is the whole match, not a capturing group.
     *
     * @param value the value to scan
     * @param dataMappings the usable data mappings, in declaration order
     * @return the kept segments ordered by position (empty when nothing matched)
     */
    static List<Segment> extractDataMappedSegments(String value,
                                                   List<FlightSqlDataMapping> dataMappings) {
        List<Segment> segments = new ArrayList<>();
        for (FlightSqlDataMapping dataMapping : dataMappings) {
            RPSMapping rpsMapping =
                    new RPSMapping(dataMapping.getRpsClassName(), dataMapping.getRpsPropertyName());
            Matcher matcher = dataMapping.getPattern().matcher(value);
            while (matcher.find()) {
                if (matcher.end() == matcher.start()) {
                    // Guard against a regex able to match an empty string, which would never advance.
                    continue;
                }
                Segment candidate = new Segment(matcher.start(), matcher.end(),
                        new RPSValue(rpsMapping, matcher.group()));
                if (segments.stream().noneMatch(candidate::overlaps)) {
                    segments.add(candidate);
                }
            }
        }
        segments.sort(Comparator.comparingInt(Segment::start));
        return segments;
    }

    /**
     * Rebuild a value by replacing each of its segments with the corresponding clear value, preserving
     * everything surrounding them.
     *
     * @param original the original value
     * @param segments the segments located in that value, ordered by position and non-overlapping
     * @param clearValues the clear value of each segment, in the same order
     */
    static String rebuild(String original, List<Segment> segments, List<String> clearValues) {
        StringBuilder rebuilt = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < segments.size() && i < clearValues.size(); i++) {
            Segment segment = segments.get(i);
            rebuilt.append(original, cursor, segment.start()).append(clearValues.get(i));
            cursor = segment.end();
        }
        rebuilt.append(original, cursor, original.length());
        return rebuilt.toString();
    }

    /**
     * Replace the content of a column with its detokenized values. A temporary vector is filled then
     * transferred into the original one, so that the batch keeps the very same vector instances (the
     * Flight listener is started on a single, reused {@link VectorSchemaRoot}).
     */
    private void applyColumnPlan(VectorSchemaRoot root, ColumnPlan plan, BufferAllocator allocator)
            throws RPSTransformException {
        Map<Integer, String> newValues = new HashMap<>();
        for (CellPlan cell : plan.cells) {
            newValues.put(cell.row, cell.detokenize());
        }
        rewriteColumn(root, plan.columnIndex, newValues, allocator);
    }

    /**
     * Rewrite a variable-width column of a record batch, replacing the values of the given rows and
     * copying all the others untouched. The rebuilt content is transferred into the original vector so
     * that the batch keeps its vector instances.
     *
     * @param root the record batch
     * @param columnIndex the 0-based index of the column to rewrite
     * @param newValues the new value of each row to replace, keyed by row index
     * @param allocator the allocator used for the temporary vector
     */
    public static void rewriteColumn(VectorSchemaRoot root, int columnIndex, Map<Integer, String> newValues,
                              BufferAllocator allocator) {
        int rowCount = root.getRowCount();
        FieldVector target = root.getVector(columnIndex);
        FieldVector temp = target.getField().createVector(allocator);
        try {
            VariableWidthFieldVector source = (VariableWidthFieldVector) target;
            VariableWidthFieldVector rebuiltVector = (VariableWidthFieldVector) temp;
            rebuiltVector.allocateNew(rowCount);

            for (int row = 0; row < rowCount; row++) {
                String newValue = newValues.get(row);
                if (newValue != null) {
                    rebuiltVector.setSafe(row, newValue.getBytes(StandardCharsets.UTF_8));
                } else if (source.isNull(row)) {
                    rebuiltVector.setNull(row);
                } else {
                    rebuiltVector.setSafe(row, source.get(row));
                }
            }
            temp.setValueCount(rowCount);

            TransferPair transferPair = temp.makeTransferPair(target);
            transferPair.transfer();
        } finally {
            temp.close();
        }
        root.setRowCount(rowCount);
    }

    private RightContext buildRightContext() {
        RightContext context = new RightContext();
        addEvidences(context, mappingConfig.getRightContextEvidences());
        return context;
    }

    private ProcessingContext buildProcessingContext() {
        ProcessingContext context = new ProcessingContext();
        addEvidences(context, mappingConfig.getProcessingContextEvidences());
        return context;
    }

    private void addEvidences(Context context, Map<String, String> evidences) {
        if (evidences == null) {
            return;
        }
        evidences.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null && !value.isEmpty()) {
                context.addEvidence(new Evidence(name, value));
            }
        });
    }

    /**
     * Calls the RPS engine to transform the given values in place.
     */
    private void transformData(HttpClientEngineProvider engineProvider, IRPSValue<String>[] values,
                               Context rightContext, ProcessingContext processingContext) throws Exception {
        RPSEngine engine = new RPSEngine(engineProvider,
                new RPSEngineConverter(),
                new RPSEngineContextResolver(null));

        RequestContext requestContext = new RequestContext(engine, new RPSEngineContextResolver(null));
        requestContext.withRequest(values, rightContext, processingContext, null);
        requestContext.transform();
    }

    /** All the cells of a single column that hold at least one segment to detokenize. */
    private record ColumnPlan(int columnIndex, List<CellPlan> cells) {
    }

    /**
     * A part of a value to detokenize, located by its half-open {@code [start, end)} range in the
     * original value and carrying its own RPS mapping.
     */
    record Segment(int start, int end, RPSValue rpsValue) {

        boolean overlaps(Segment other) {
            return start < other.end && other.start < end;
        }
    }

    /** A single cell (row of a column) and the segments extracted from its value. */
    private record CellPlan(int row, String original, List<Segment> segments) {

        List<RPSValue> rpsValues() {
            return segments.stream().map(Segment::rpsValue).toList();
        }

        String detokenize() throws RPSTransformException {
            List<String> clearValues = new ArrayList<>(segments.size());
            for (Segment segment : segments) {
                RPSValue rpsValue = segment.rpsValue();
                String transformed = rpsValue.getTransformed();
                if (transformed == null) {
                    throw new RPSTransformException(
                            "Detokenization did not return a value for: " + rpsValue.getOriginal());
                }
                clearValues.add(transformed);
            }
            return rebuild(original, segments, clearValues);
        }
    }
}
