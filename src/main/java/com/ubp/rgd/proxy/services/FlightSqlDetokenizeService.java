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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detokenizes, through the RPS engine, the {@code RG{...}} tokens found in the Arrow record batches
 * produced by the Flight SQL server.
 * <p>
 * Result sets carry no RPS metadata, so the RPS class / property names are resolved from the
 * table/column mapping file configured by {@code proxy.flight-sql.mapping-config-file}. Only the
 * variable-width (string) columns having a mapping are scanned; all the tokens of a record batch are
 * batched into a single engine call.
 */
@ApplicationScoped
public class FlightSqlDetokenizeService {

    private static final Logger LOG = Logger.getLogger(FlightSqlDetokenizeService.class);

    /** Fixed delimiter pattern of an RPS token ({@code RG{...}}). */
    static final Pattern TOKEN_PATTERN = Pattern.compile("RG\\{[^}]*\\}");

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
     *
     * @param metaData the JDBC result-set metadata
     * @return a list aligned on the column indexes (0-based); an entry is {@code null} when the
     *         column has no mapping and must therefore be returned untouched
     * @throws SQLException when the metadata cannot be read
     */
    public List<FlightSqlColumnMapping> resolveMappings(ResultSetMetaData metaData) throws SQLException {
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
            }
            mappings.add(mapping);
        }
        return mappings;
    }

    /**
     * Detokenize, in place, every mapped string column of the given record batch. Values without any
     * {@code RG{...}} token are left untouched, and the vectors of the batch are replaced only when
     * at least one token was found.
     *
     * @param root the record batch to detokenize (modified in place)
     * @param mappings the per-column mappings resolved by {@link #resolveMappings(ResultSetMetaData)}
     * @param allocator the allocator used for the temporary vectors
     * @throws RPSTransformException when the RPS engine fails to detokenize a value
     */
    public void detokenize(VectorSchemaRoot root, List<FlightSqlColumnMapping> mappings,
                           BufferAllocator allocator) throws RPSTransformException {
        if (root == null || mappings == null || root.getRowCount() == 0) {
            return;
        }

        List<ColumnPlan> plans = new ArrayList<>();
        List<RPSValue> flatValues = new ArrayList<>();

        for (int col = 0; col < root.getFieldVectors().size() && col < mappings.size(); col++) {
            FlightSqlColumnMapping mapping = mappings.get(col);
            FieldVector vector = root.getVector(col);
            if (mapping == null || !(vector instanceof VariableWidthFieldVector textVector)) {
                continue;
            }
            ColumnPlan plan = buildColumnPlan(col, textVector, mapping, root.getRowCount());
            if (plan != null) {
                plans.add(plan);
                plan.cells.forEach(cell -> flatValues.addAll(cell.rpsValues));
            }
        }

        if (flatValues.isEmpty()) {
            return;
        }

        LOG.debugf("Detokenizing %d token(s) over %d column(s)", flatValues.size(), plans.size());
        try {
            transformData(rpsClientEngineProvider.getClientEngineProvider(),
                    flatValues.toArray(new RPSValue[0]),
                    buildRightContext(),
                    buildProcessingContext());
        } catch (Exception e) {
            LOG.error("Flight SQL detokenization failed", e);
            throw new RPSTransformException(e);
        }

        for (ColumnPlan plan : plans) {
            applyColumnPlan(root, plan, allocator);
        }
    }

    /**
     * Scan a string column and build the list of cells containing at least one token.
     *
     * @return the plan, or {@code null} when the column holds no token at all
     */
    private ColumnPlan buildColumnPlan(int columnIndex, VariableWidthFieldVector vector,
                                       FlightSqlColumnMapping mapping, int rowCount) {
        RPSMapping rpsMapping = new RPSMapping(mapping.getRpsClassName(), mapping.getRpsPropertyName());
        List<CellPlan> cells = new ArrayList<>();

        for (int row = 0; row < rowCount; row++) {
            if (vector.isNull(row)) {
                continue;
            }
            String value = new String(vector.get(row), StandardCharsets.UTF_8);
            List<RPSValue> tokens = extractTokens(value, rpsMapping);
            if (!tokens.isEmpty()) {
                cells.add(new CellPlan(row, value, tokens));
            }
        }
        return cells.isEmpty() ? null : new ColumnPlan(columnIndex, cells);
    }

    /**
     * @return one {@link RPSValue} per {@code RG{...}} token found in the value, in order
     */
    static List<RPSValue> extractTokens(String value, RPSMapping mapping) {
        List<RPSValue> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(value);
        while (matcher.find()) {
            tokens.add(new RPSValue(mapping, matcher.group()));
        }
        return tokens;
    }

    /**
     * Rebuild a value by replacing each of its tokens with the corresponding clear value, preserving
     * everything surrounding the tokens.
     */
    static String rebuild(String original, List<String> clearValues) {
        Matcher matcher = TOKEN_PATTERN.matcher(original);
        StringBuilder rebuilt = new StringBuilder();
        int index = 0;
        while (matcher.find() && index < clearValues.size()) {
            matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(clearValues.get(index++)));
        }
        matcher.appendTail(rebuilt);
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

    /** All the cells of a single column that hold at least one token. */
    private record ColumnPlan(int columnIndex, List<CellPlan> cells) {
    }

    /** A single cell (row of a column) and the tokens extracted from its value. */
    private record CellPlan(int row, String original, List<RPSValue> rpsValues) {

        String detokenize() throws RPSTransformException {
            List<String> clearValues = new ArrayList<>(rpsValues.size());
            for (RPSValue rpsValue : rpsValues) {
                String transformed = rpsValue.getTransformed();
                if (transformed == null) {
                    throw new RPSTransformException(
                            "Detokenization did not return a value for: " + rpsValue.getOriginal());
                }
                clearValues.add(transformed);
            }
            return rebuild(original, clearValues);
        }
    }
}
