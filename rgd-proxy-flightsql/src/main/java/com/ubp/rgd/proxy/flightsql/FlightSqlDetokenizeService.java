package com.ubp.rgd.proxy.flightsql;

import com.ubp.rgd.proxy.sqlgateway.PhaseContexts;
import com.ubp.rgd.proxy.sqlgateway.ValueDetokenizer.Segment;

import static com.ubp.rgd.proxy.sqlgateway.ValueDetokenizer.extractImplicitSegments;
import static com.ubp.rgd.proxy.sqlgateway.ValueDetokenizer.extractTokens;
import static com.ubp.rgd.proxy.sqlgateway.ValueDetokenizer.rebuild;
import static com.ubp.rgd.proxy.sqlgateway.ValueDetokenizer.resolveTypedMapping;

import com.ubp.rgd.proxy.sqlgateway.TokenIndexResolver;

import com.ubp.rgd.proxy.transform.model.EvidenceContext;
import com.ubp.rgd.proxy.transform.model.RightContext;
import com.ubp.rgd.proxy.transform.model.Evidence;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.ValueMapping;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.TransformException;
import com.ubp.rgd.proxy.sqlgateway.config.ColumnMapping;
import com.ubp.rgd.proxy.sqlgateway.config.DataMapping;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import com.ubp.rgd.proxy.sqlgateway.config.PhaseConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VariableWidthFieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.util.TransferPair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Detokenizes the tokens found in the Arrow record batches produced by the Flight SQL server, with
 * the transformer selected by {@code proxy.transform.impl}.
 * <p>
 * Result sets carry no RPS metadata, so the RPS class / property names are resolved from the mapping
 * file configured by {@code proxy.flight-sql.mapping-config-file}, in this order:
 * <ol>
 *     <li><b>Column mappings</b> â€” when the table/column of a result-set column is mapped, its
 *     tokens are all detokenized with that mapping.</li>
 *     <li><b>Data mappings</b> â€” otherwise the column is detokenized implicitly: each configured
 *     regex locates its own segments in the values and supplies their class / property names.</li>
 *     <li><b>Token mapping index</b> â€” the tokens no data mapping claimed are finally resolved from
 *     the mapping index they carry in their first two characters, see
 *     {@link TokenIndexResolver}. This tier is <b>RPS only</b>: it is skipped when the
 *     selected transformer produces tokens without a mapping index.</li>
 * </ol>
 * What a token looks like is asked to the transformer rather than hardcoded, because it differs from
 * one implementation to the next.
 * <p>
 * Only the variable-width (string) columns are scanned for tokens, and all the tokens of a record
 * batch are batched into a single transformation call. When the transformer keeps the dates and the
 * numbers of their type once protected, the date and numeric columns are detokenized too, value by
 * value, and keep their Arrow type: through their column mapping, or implicitly through the first
 * data mapping whose regex matches the whole value.
 */
@ApplicationScoped
public class FlightSqlDetokenizeService {

    private static final Logger LOG = LoggerFactory.getLogger(FlightSqlDetokenizeService.class);

    @Inject
    EndPointTransformer transformer;

    /** Owner of the mapping file; this service only consumes its {@code after} phase. */
    @Inject
    FlightSqlMappingConfigProvider mappingConfigProvider;

    @ConfigProperty(name = "proxy.transform.client-id")
    String transformClientId;

    private MappingConfig mappingConfig = new MappingConfig();

    @PostConstruct
    void init() {
        mappingConfig = mappingConfigProvider.get();
        loadTokenIndexMappings();
    }

    /**
     * (Re)build the token mapping index table of {@link TokenIndexResolver} for the RPS client
     * the proxy is configured for.
     * <p>
     * The table is cleared first, so a reload can never leave a stale index behind. A failure is logged
     * and leaves the table empty rather than preventing the proxy from starting: the mapping index is
     * only the last detokenization tier, and without it the tokens are simply returned untouched.
     * <p>
     * Public and re-runnable so that the table can be refreshed at any time.
     */
    public void loadTokenIndexMappings() {
        TokenIndexResolver.load(transformer.supportsTokenMappingIndex(), transformClientId, this::initTokenIndexMappings);
    }

    /**
     * Declare the mapping indexes of an RPS client, by calling
     * {@link TokenIndexResolver#register(String, String)} for each of them with the index
     * symbol and its {@code "ClassName.PropertyName"}.
     * <p>
     * A symbol may be declared padded ({@code "Bx"}) or not ({@code "B"}), both being the same index â€”
     * see {@link TokenIndexResolver} for the encoding.
     * <p>
     * The implementation may query the RPS catalogue of the client. It must tolerate a {@code null} client id, which happens when the
     * service is built outside of CDI. Any exception it raises is caught by
     * {@link #loadTokenIndexMappings()}.
     *
     * @param clientId the value of {@code proxy.transform.client-id}, may be {@code null}
     */
    void initTokenIndexMappings(String clientId) {
        TokenIndexResolver.registerClientMappings(clientId);
    }

    MappingConfig getMappingConfig() {
        return mappingConfig;
    }

    public void setMappingConfig(MappingConfig mappingConfig) {
        this.mappingConfig = mappingConfig;
    }

    /**
     * Set the transformer, for the callers building this service outside of CDI.
     *
     * @param transformer the transformer to detokenize with
     */
    public void setTransformer(EndPointTransformer transformer) {
        this.transformer = transformer;
    }

    /**
     * Resolve, once per query, the RPS mapping of every result-set column.
     * <p>
     * Only the explicit {@code column-mappings} can be resolved from the metadata: the data mappings
     * and the token mapping indexes driving the implicit detokenization depend on the values
     * themselves and are therefore applied later, while scanning the record batches.
     *
     * @param metaData the JDBC result-set metadata
     * @return a list aligned on the column indexes (0-based); an entry is {@code null} when the column
     *         has no explicit mapping, in which case it is detokenized implicitly through the
     *         configured data mappings, then through the mapping index carried by its tokens
     * @throws SQLException when the metadata cannot be read
     */
    public List<ColumnMapping> resolveMappings(ResultSetMetaData metaData) throws SQLException {
        List<ColumnMapping> mappings = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String table = metaData.getTableName(i);
            // Prefer the label (alias) then fall back to the physical column name.
            ColumnMapping mapping = mappingConfig.findMapping(table, metaData.getColumnLabel(i));
            if (mapping == null) {
                mapping = mappingConfig.findMapping(table, metaData.getColumnName(i));
            }
            if (mapping != null) {
                LOG.info("Column {}.{} will be detokenized as {}",
                        table, metaData.getColumnLabel(i), mapping);
            } else {
                LOG.info("Column {}.{} has no column mapping and will be detokenized implicitly.",
                        table, metaData.getColumnLabel(i));
            }
            mappings.add(mapping);
        }
        return mappings;
    }

    /**
     * Detokenize, in place, the string columns of the given record batch. A column having an explicit
     * {@link ColumnMapping} has its {@code RG{...}} tokens detokenized with that mapping; any
     * other column falls back to the implicit mode driven by the configured data mappings, then by the
     * mapping index carried by the remaining tokens. Values
     * without any recognized segment are left untouched, and the vectors of the batch are replaced
     * only when at least one segment was found.
     *
     * @param root the record batch to detokenize (modified in place)
     * @param mappings the per-column mappings resolved by {@link #resolveMappings(ResultSetMetaData)};
     *                 a {@code null} entry selects the implicit mode for that column
     * @param allocator the allocator used for the temporary vectors
     * @throws TransformException when the RPS engine fails to detokenize a value
     */
    public void detokenize(VectorSchemaRoot root, List<ColumnMapping> mappings,
                           BufferAllocator allocator) throws TransformException {
        if (root == null || mappings == null || root.getRowCount() == 0) {
            return;
        }

        if (!mappingConfig.isAfterActive()) {
            // The 'after' phase is switched off: the result set is handed back as the database
            // server returned it, tokens included.
            LOG.debug("The Flight SQL 'after' phase is not active, the result set is left untouched.");
            return;
        }

        List<ColumnPlan> plans = new ArrayList<>();
        List<TypedColumnPlan> typedPlans = new ArrayList<>();
        List<MappedValue> flatValues = new ArrayList<>();

        for (int col = 0; col < root.getFieldVectors().size() && col < mappings.size(); col++) {
            ColumnMapping mapping = mappings.get(col);
            FieldVector vector = root.getVector(col);
            if (!(vector instanceof VariableWidthFieldVector textVector)) {
                TypedColumnPlan typedPlan = buildTypedColumnPlan(vector, mapping, root.getRowCount());
                if (typedPlan != null) {
                    typedPlans.add(typedPlan);
                    flatValues.addAll(typedPlan.values().values());
                }
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

        LOG.debug("Detokenizing {} token(s) over {} column(s)", flatValues.size(), plans.size() + typedPlans.size());
        transformValues(flatValues);

        for (ColumnPlan plan : plans) {
            applyColumnPlan(root, plan, allocator);
        }
        for (TypedColumnPlan typedPlan : typedPlans) {
            typedPlan.apply();
        }
    }

    /**
     * Scan a date or numeric column, for a transformer that keeps the dates and the numbers of their
     * type once protected: their protected values are still dates and numbers, stored as such.
     * <p>
     * A typed value can only be replaced as a whole. With an explicit column mapping every non null
     * value is detokenized with it. Without one the column is detokenized implicitly: each value, in
     * its text form ({@code yyyy-mm-dd} for a date), is claimed by the first data mapping whose regex
     * matches it <b>whole</b>, and a value no data mapping claims is left untouched. The token mapping
     * index tier does not apply, a typed value carries no index.
     *
     * @param vector        the column
     * @param columnMapping the explicit column mapping, {@code null} to run in implicit mode
     * @param rowCount      the number of rows of the batch
     * @return the plan, or {@code null} when the column holds nothing to detokenize
     */
    private TypedColumnPlan buildTypedColumnPlan(FieldVector vector, ColumnMapping columnMapping,
                                                 int rowCount) {
        if (!transformer.preservesValueTypes()) {
            return null;
        }
        TypedColumn column = TypedColumn.of(vector);
        if (column == null) {
            return null;
        }
        ValueMapping columnRpsMapping = columnMapping == null ? null
                : new ValueMapping(columnMapping.getRpsClassName(), columnMapping.getRpsPropertyName());
        List<DataMapping> dataMappings = mappingConfig.getUsableDataMappings();
        if (columnRpsMapping == null && dataMappings.isEmpty()) {
            return null;
        }
        Map<Integer, MappedValue> values = new HashMap<>();
        for (int row = 0; row < rowCount; row++) {
            if (vector.isNull(row)) {
                continue;
            }
            String text = column.read(row);
            ValueMapping rpsMapping = columnRpsMapping != null ? columnRpsMapping
                    : resolveTypedMapping(text, dataMappings);
            if (rpsMapping != null) {
                values.put(row, new MappedValue(rpsMapping, text, column.kind()));
            }
        }
        return values.isEmpty() ? null : new TypedColumnPlan(column, values);
    }

    /**
     * Hand the located segments over to the RPS engine, which replaces their transformed value in
     * place. Extracted so that it can be substituted in the tests.
     *
     * @param values the segments of the whole record batch, explicit and implicit mixed
     */
    protected void transformValues(List<MappedValue> values) throws TransformException {
        try {
            transformer.transformData(values.toArray(new MappedValue[0]),
                    PhaseContexts.rightContext(afterPhase()),
                    PhaseContexts.processingContext(afterPhase(), "Unprotect", "Flight SQL 'after'"));
        } catch (Exception e) {
            LOG.error("Flight SQL detokenization failed", e);
            throw new TransformException(e);
        }
    }

    /**
     * Scan a string column and build the list of cells holding at least one segment to detokenize.
     *
     * @param columnMapping the explicit column mapping, or {@code null} to run in implicit mode
     * @return the plan, or {@code null} when nothing has to be detokenized in that column
     */
    private ColumnPlan buildColumnPlan(int columnIndex, VariableWidthFieldVector vector,
                                       ColumnMapping columnMapping, int rowCount) {
        ValueMapping rpsMapping = columnMapping == null ? null
                : new ValueMapping(columnMapping.getRpsClassName(), columnMapping.getRpsPropertyName());
        List<DataMapping> dataMappings = mappingConfig.getUsableDataMappings();
        List<CellPlan> cells = new ArrayList<>();

        for (int row = 0; row < rowCount; row++) {
            if (vector.isNull(row)) {
                continue;
            }
            String value = new String(vector.get(row), StandardCharsets.UTF_8);
            List<Segment> segments = rpsMapping != null
                    ? extractTokens(value, rpsMapping, transformer.tokenPattern())
                    : extractImplicitSegments(value, dataMappings, transformer.tokenPattern(),
                            transformer.supportsTokenMappingIndex());
            if (!segments.isEmpty()) {
                cells.add(new CellPlan(row, value, segments));
            }
        }
        return cells.isEmpty() ? null : new ColumnPlan(columnIndex, cells);
    }

    /**
     * Replace the content of a column with its detokenized values. A temporary vector is filled then
     * transferred into the original one, so that the batch keeps the very same vector instances (the
     * Flight listener is started on a single, reused {@link VectorSchemaRoot}).
     */
    private void applyColumnPlan(VectorSchemaRoot root, ColumnPlan plan, BufferAllocator allocator)
            throws TransformException {
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

    /**
     * The phase that applies to a result set coming back from the database server.
     *
     * @return the configured {@code after} phase, never null
     */
    private PhaseConfig afterPhase() {
        PhaseConfig phase = mappingConfig.getAfter();
        return phase == null ? new PhaseConfig() : phase;
    }

    /** All the non null values of a date or numeric column, keyed by row. */
    private record TypedColumnPlan(TypedColumn column, Map<Integer, MappedValue> values) {

        /** Write the detokenized values back, leaving a value that cannot be written untouched. */
        void apply() {
            values.forEach((row, value) -> {
                String transformed = value.getTransformed();
                if (transformed == null || transformed.equals(value.getOriginal())) {
                    return;
                }
                try {
                    column.write(row, transformed);
                } catch (RuntimeException e) {
                    LOG.warn("Could not write the detokenized value of row {} back into its column, leaving it"
                            + " untouched: {}", row, e.getMessage());
                }
            });
        }
    }

    /** All the cells of a single column that hold at least one segment to detokenize. */
    private record ColumnPlan(int columnIndex, List<CellPlan> cells) {
    }

    /** A single cell (row of a column) and the segments extracted from its value. */
    private record CellPlan(int row, String original, List<Segment> segments) {

        List<MappedValue> rpsValues() {
            return segments.stream().map(Segment::rpsValue).toList();
        }

        String detokenize() throws TransformException {
            List<String> clearValues = new ArrayList<>(segments.size());
            for (Segment segment : segments) {
                MappedValue rpsValue = segment.rpsValue();
                String transformed = rpsValue.getTransformed();
                if (transformed == null) {
                    LOG.error("Detokenization did not return a value for: {} with property as {}.{}",
                            rpsValue.getOriginal(), rpsValue.getMapping().getClassName(), rpsValue.getMapping().getPropertyName());
                    transformed = rpsValue.getOriginal();
                }
                clearValues.add(transformed);
            }
            return rebuild(original, segments, clearValues);
        }
    }
}
