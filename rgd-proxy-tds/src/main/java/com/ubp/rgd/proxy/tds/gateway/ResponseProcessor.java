package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.sqlgateway.ValueDetokenizer;
import com.ubp.rgd.proxy.sqlgateway.ValueDetokenizer.Segment;
import com.ubp.rgd.proxy.sqlgateway.config.ColumnMapping;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import com.ubp.rgd.proxy.tds.protocol.ColumnMetadata;
import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TdsBuffer;
import com.ubp.rgd.proxy.tds.protocol.TdsProtocolException;
import com.ubp.rgd.proxy.tds.protocol.TdsReader;
import com.ubp.rgd.proxy.tds.protocol.TypeInfo;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.TransformException;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.model.ValueKind;
import com.ubp.rgd.proxy.transform.model.ValueMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relays the responses of the database server to the client, detokenizing the values of the rows on
 * the way, see [MS-TDS] 2.2.7 for the tokens.
 * <p>
 * The response is processed while it arrives. The tokens the gateway does not change are copied byte
 * for byte. The rows of a result set whose columns may hold tokens are buffered by batches: one
 * transformer call per batch, then the rows are re-encoded. A column whose values may be detokenized
 * is announced, in COLMETADATA, as variable-length and as long as a non {@code max} column can be, so
 * that a clear value longer than its token still fits.
 * <p>
 * A session keeps one processor: it remembers the metadata of the cursors, which the server does
 * not send again when the client fetches their rows.
 */
public final class ResponseProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseProcessor.class);

    private static final int MAX_CURSORS = 1024;
    private static final int COLINFO_DIFFERENT_NAME = 0x20;

    /** Hands the values of a batch over to the transformer, which fills their transformed value in. */
    @FunctionalInterface
    public interface BatchTransformer {
        void transform(List<MappedValue> values) throws TransformException;
    }

    private final EndPointTransformer transformer;
    private final java.util.function.Supplier<MappingConfig> mappingConfig;
    private final BatchTransformer batchTransformer;
    private final int batchSize;

    private ResultPlan lastPlan;
    private final Map<Integer, ResultPlan> cursorPlans = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, ResultPlan> eldest) {
            return size() > MAX_CURSORS;
        }
    };

    public ResponseProcessor(EndPointTransformer transformer, java.util.function.Supplier<MappingConfig> mappingConfig,
                             BatchTransformer batchTransformer, int batchSize) {
        this.transformer = transformer;
        this.mappingConfig = mappingConfig;
        this.batchTransformer = batchTransformer;
        this.batchSize = Math.max(1, batchSize);
    }

    /** @return whether responses have to be processed at all: the 'after' phase is active */
    public boolean isActive() {
        return mappingConfig.get().isAfterActive();
    }

    /**
     * Relay one response.
     *
     * @param in   the payload of the response, as it arrives
     * @param out  the response to the client
     * @param info what the request being answered tells about cursors
     */
    public void process(InputStream in, OutputStream out, RequestRewriter.RequestInfo info)
            throws IOException, TransformException {
        new Run(new TdsReader(in), out, info == null ? RequestRewriter.RequestInfo.NONE : info).process();
    }

    /** The processing of one response. */
    private final class Run {
        private final TdsReader reader;
        private final OutputStream out;
        private final RequestRewriter.RequestInfo info;
        private final MappingConfig config = mappingConfig.get();

        private ResultPlan current;
        private PendingResult pending;
        private final List<Row> batch = new ArrayList<>();

        Run(TdsReader reader, OutputStream out, RequestRewriter.RequestInfo info) {
            this.reader = reader;
            this.out = out;
            this.info = info;
        }

        void process() throws IOException, TransformException {
            while (true) {
                reader.startCapture();
                int token = reader.readByteOrEnd();
                if (token < 0) {
                    break;
                }
                switch (token) {
                    case Tds.TOKEN_COLMETADATA -> columnMetadata();
                    case Tds.TOKEN_TABNAME, Tds.TOKEN_COLINFO, Tds.TOKEN_ORDER -> {
                        reader.skip(reader.readUShort());
                        byte[] raw = reader.stopCapture();
                        if (pending != null) {
                            pending.add(token, raw);
                        } else {
                            out.write(raw);
                        }
                    }
                    case Tds.TOKEN_ROW, Tds.TOKEN_NBCROW -> row(token);
                    case Tds.TOKEN_RETURNVALUE -> returnValue();
                    default -> {
                        endResult();
                        TokenLengths.skipSimpleToken(token, reader);
                        out.write(reader.stopCapture());
                    }
                }
            }
            reader.stopCapture();
            endResult();
            if (info.closedCursor() != null) {
                cursorPlans.remove(info.closedCursor());
            }
        }

        private void columnMetadata() throws IOException, TransformException {
            endResult();
            int count = reader.readUShort();
            if (count == Tds.NO_METADATA) {
                out.write(reader.stopCapture());
                ResultPlan plan = info.fetchedCursor() != null ? cursorPlans.get(info.fetchedCursor()) : null;
                current = plan != null ? plan : lastPlan;
                if (current == null) {
                    throw new TdsProtocolException("The server reused metadata the gateway never saw");
                }
                return;
            }
            List<ColumnMetadata> columns = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                columns.add(ColumnMetadata.read(reader));
            }
            pending = new PendingResult(columns, reader.stopCapture());
        }

        private void row(int token) throws IOException, TransformException {
            if (pending != null) {
                startResult();
            }
            if (current == null) {
                throw new TdsProtocolException("Row received before any column metadata");
            }
            List<ColumnMetadata> columns = current.columns;
            boolean[] nulls = null;
            byte[] bitmap = null;
            if (token == Tds.TOKEN_NBCROW) {
                bitmap = reader.readBytes((columns.size() + 7) / 8);
                nulls = new boolean[columns.size()];
                for (int i = 0; i < columns.size(); i++) {
                    nulls[i] = (bitmap[i / 8] & (1 << (i % 8))) != 0;
                }
            }
            if (!current.active) {
                for (int i = 0; i < columns.size(); i++) {
                    if (nulls == null || !nulls[i]) {
                        columns.get(i).typeInfo().readValue(reader, true);
                    }
                }
                out.write(reader.stopCapture());
                return;
            }
            Row row = new Row(token, bitmap, new Object[columns.size()]);
            for (int i = 0; i < columns.size(); i++) {
                if (nulls != null && nulls[i]) {
                    continue;
                }
                int start = reader.capturePosition();
                TypeInfo type = columns.get(i).typeInfo();
                TypeInfo.Value value = type.readValue(reader, true);
                ColumnRule rule = current.rules[i];
                Cell cell = rule == null || value.isNull() ? null : plan(rule, type, value);
                row.cells[i] = cell != null ? cell : reader.captured(start, reader.capturePosition());
            }
            reader.stopCapture();
            batch.add(row);
            if (batch.size() >= batchSize) {
                flushRows();
            }
        }

        private Cell plan(ColumnRule rule, TypeInfo type, TypeInfo.Value value) {
            if (rule.kind == null) {
                String text = new String(value.data(), type.charset());
                List<Segment> segments = ValueDetokenizer.segmentsOf(text, rule.mapping, config, transformer);
                return segments.isEmpty() ? null : new Cell(rule, value, text, segments, null);
            }
            String text = TypedValues.read(type, value.data());
            ValueMapping mapping = ValueDetokenizer.typedMapping(text, rule.mapping, config);
            return mapping == null ? null : new Cell(rule, value, text, null, new MappedValue(mapping, text, rule.kind));
        }

        private void returnValue() throws IOException, TransformException {
            endResult();
            int ordinal = reader.readUShort();
            reader.readBVarchar();
            reader.readByte();
            reader.readInt();
            reader.readUShort();
            TypeInfo type = TypeInfo.read(reader);
            TypeInfo.Value value = type.readValue(reader, true);
            out.write(reader.stopCapture());
            if (ordinal == info.cursorOutputOrdinal() && !value.isNull() && value.data().length == 4
                    && lastPlan != null) {
                cursorPlans.put(RequestRewriter.littleEndianInt(value.data()), lastPlan);
            }
        }

        /** The result set ends: its last rows leave, and metadata still pending is sent. */
        private void endResult() throws IOException, TransformException {
            flushRows();
            if (pending != null) {
                startResult();
            }
        }

        /** Rows are coming: the column metadata is decided, then sent with the tokens held with it. */
        private void startResult() throws IOException {
            PendingResult result = pending;
            pending = null;
            ResultPlan plan = buildPlan(result, config);
            out.write(plan.metadataBytes != null ? plan.metadataBytes : result.raw);
            for (byte[] raw : result.held) {
                out.write(raw);
            }
            current = plan;
            lastPlan = plan;
        }

        private void flushRows() throws IOException, TransformException {
            if (batch.isEmpty()) {
                return;
            }
            List<MappedValue> values = new ArrayList<>();
            for (Row row : batch) {
                for (Object cell : row.cells) {
                    if (cell instanceof Cell planned) {
                        if (planned.segments != null) {
                            planned.segments.forEach(segment -> values.add(segment.rpsValue()));
                        } else {
                            values.add(planned.typedValue);
                        }
                    }
                }
            }
            if (!values.isEmpty()) {
                LOG.debug("Detokenizing {} value(s) over {} row(s)", values.size(), batch.size());
                batchTransformer.transform(values);
            }
            List<ColumnMetadata> columns = current.announced;
            for (Row row : batch) {
                TdsBuffer encoded = new TdsBuffer(256);
                encoded.writeByte(row.token);
                if (row.bitmap != null) {
                    encoded.writeBytes(row.bitmap);
                }
                for (int i = 0; i < row.cells.length; i++) {
                    Object cell = row.cells[i];
                    if (cell instanceof byte[] raw) {
                        encoded.writeBytes(raw);
                    } else if (cell instanceof Cell planned) {
                        TypeInfo type = columns.get(i).typeInfo();
                        type.writeValue(encoded, planned.clear(type, columns.get(i).name()), true);
                    }
                }
                out.write(encoded.toByteArray());
            }
            batch.clear();
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Result plans
    // -------------------------------------------------------------------------------------------------

    /** How a column is detokenized: text when {@code kind} is null, typed otherwise. */
    private record ColumnRule(ValueMapping mapping, ValueKind kind) {
    }

    /**
     * The decisions taken for a result set.
     *
     * @param columns       its columns, as the server described them
     * @param announced     its columns, as announced to the client
     * @param rules         the rule of each column, {@code null} when the column is relayed as it is
     * @param active        whether any column may be detokenized
     * @param metadataBytes the COLMETADATA announced to the client, {@code null} to send the server's
     */
    private record ResultPlan(List<ColumnMetadata> columns, List<ColumnMetadata> announced, ColumnRule[] rules,
                              boolean active, byte[] metadataBytes) {
    }

    /** A COLMETADATA waiting for the TABNAME / COLINFO tokens that name the tables of its columns. */
    private static final class PendingResult {
        final List<ColumnMetadata> columns;
        final byte[] raw;
        final List<byte[]> held = new ArrayList<>();
        final List<String> tables = new ArrayList<>();
        final Map<Integer, String[]> columnInfo = new HashMap<>();

        PendingResult(List<ColumnMetadata> columns, byte[] raw) {
            this.columns = columns;
            this.raw = raw;
        }

        void add(int token, byte[] raw) throws IOException {
            held.add(raw);
            TdsReader reader = TdsReader.of(raw);
            reader.readByte();
            int length = reader.readUShort();
            byte[] body = reader.readBytes(length);
            if (token == Tds.TOKEN_TABNAME) {
                TdsReader names = TdsReader.of(body);
                int consumed = 0;
                while (consumed < body.length) {
                    names.startCapture();
                    int parts = names.readByte();
                    String last = null;
                    for (int i = 0; i < parts; i++) {
                        last = names.readUsVarchar();
                    }
                    tables.add(last);
                    consumed += names.stopCapture().length;
                }
            } else if (token == Tds.TOKEN_COLINFO) {
                TdsReader entries = TdsReader.of(body);
                int consumed = 0;
                while (consumed < body.length) {
                    entries.startCapture();
                    int column = entries.readByte();
                    int table = entries.readByte();
                    int status = entries.readByte();
                    String baseName = (status & COLINFO_DIFFERENT_NAME) != 0 ? entries.readBVarchar() : null;
                    String tableName = table > 0 && table <= tables.size() ? tables.get(table - 1) : null;
                    columnInfo.put(column - 1, new String[]{tableName, baseName});
                    consumed += entries.stopCapture().length;
                }
            }
        }
    }

    private ResultPlan buildPlan(PendingResult result, MappingConfig config) {
        List<ColumnMetadata> columns = result.columns;
        ColumnRule[] rules = new ColumnRule[columns.size()];
        List<ColumnMetadata> announced = new ArrayList<>(columns);
        boolean active = false;
        boolean widened = false;
        boolean implicitText = !config.getUsableDataMappings().isEmpty() || transformer.supportsTokenMappingIndex();
        boolean implicitTyped = !config.getUsableDataMappings().isEmpty();
        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata column = columns.get(i);
            String[] colInfo = result.columnInfo.get(i);
            String table = colInfo != null && colInfo[0] != null ? colInfo[0] : column.table();
            ColumnMapping mapping = config.findMapping(table, column.name());
            if (mapping == null && colInfo != null && colInfo[1] != null) {
                mapping = config.findMapping(table, colInfo[1]);
            }
            ValueMapping columnMapping = mapping == null ? null
                    : new ValueMapping(mapping.getRpsClassName(), mapping.getRpsPropertyName());
            TypeInfo type = column.typeInfo();
            if (type.isText() && (columnMapping != null || implicitText)) {
                rules[i] = new ColumnRule(columnMapping, null);
                TypeInfo wide = type.widenedForText();
                if (wide != type) {
                    announced.set(i, column.withType(wide));
                    widened = true;
                }
            } else if (transformer.preservesValueTypes() && TypedValues.kindOf(type) != null
                    && (columnMapping != null || implicitTyped)) {
                rules[i] = new ColumnRule(columnMapping, TypedValues.kindOf(type));
            }
            if (rules[i] != null) {
                active = true;
                LOG.debug("Column {} of table {} may be detokenized{}", column.name(), table,
                        columnMapping != null ? " as " + mapping : " implicitly");
            }
        }
        byte[] metadataBytes = null;
        if (widened) {
            TdsBuffer buffer = new TdsBuffer(result.raw.length + 16);
            buffer.writeByte(Tds.TOKEN_COLMETADATA).writeUShort(announced.size());
            announced.forEach(column -> column.write(buffer));
            metadataBytes = buffer.toByteArray();
        }
        return new ResultPlan(columns, announced, rules, active, metadataBytes);
    }

    // -------------------------------------------------------------------------------------------------
    // Rows
    // -------------------------------------------------------------------------------------------------

    /**
     * A buffered row.
     *
     * @param cells per column: the raw bytes of a value relayed as it is, a {@link Cell} to detokenize,
     *              or {@code null} for a NULL of an NBCROW
     */
    private record Row(int token, byte[] bitmap, Object[] cells) {
    }

    /** A value to detokenize. */
    private record Cell(ColumnRule rule, TypeInfo.Value value, String text, List<Segment> segments,
                        MappedValue typedValue) {

        /** @return the value to send: the clear one, or the original one when it cannot be sent */
        TypeInfo.Value clear(TypeInfo type, String column) {
            if (typedValue != null) {
                String transformed = typedValue.getTransformed();
                if (transformed == null || transformed.equals(text)) {
                    return value;
                }
                try {
                    return value.with(TypedValues.write(type, value.data(), transformed));
                } catch (RuntimeException e) {
                    LOG.warn("The detokenized value of column {} does not fit its type, it is left protected: {}",
                            column, e.getMessage());
                    return value;
                }
            }
            String clear = ValueDetokenizer.clearValue(text, segments);
            if (clear.equals(text)) {
                return value;
            }
            byte[] bytes;
            try {
                bytes = toArray(type.charset().newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(clear)));
            } catch (CharacterCodingException e) {
                LOG.warn("The detokenized value of column {} cannot be written in the code page of the column,"
                        + " it is left protected", column);
                return value;
            }
            if (type.lengthKind() == TypeInfo.LengthKind.USHORT && bytes.length > type.maxLength()) {
                LOG.warn("The detokenized value of column {} is longer than the column can announce, it is left"
                        + " protected", column);
                return value;
            }
            return value.with(bytes);
        }
    }

    private static byte[] toArray(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
