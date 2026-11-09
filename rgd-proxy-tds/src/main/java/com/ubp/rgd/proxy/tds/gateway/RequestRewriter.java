package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.sqlgateway.SqlTransformRewriter;
import com.ubp.rgd.proxy.sqlgateway.config.PhaseConfig;
import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TdsBuffer;
import com.ubp.rgd.proxy.tds.protocol.TdsProtocolException;
import com.ubp.rgd.proxy.tds.protocol.TdsReader;
import com.ubp.rgd.proxy.tds.protocol.TypeInfo;
import com.ubp.rgd.proxy.transform.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Rewrites the {@code transform()} / {@code transform_search()} calls of the requests of a client:
 * the text of a SQL batch, and the statement parameter of the RPC calls that carry one
 * ({@code sp_executesql}, {@code sp_prepare}, {@code sp_prepexec} and the cursor procedures).
 * Everything else is relayed byte for byte.
 */
public final class RequestRewriter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestRewriter.class);

    /** The index of the statement parameter, by procedure id. */
    private static final Map<Integer, Integer> STATEMENT_BY_ID = Map.of(
            Tds.PROC_EXECUTESQL, 0, Tds.PROC_PREPARE, 2, Tds.PROC_PREPEXEC, 2,
            Tds.PROC_CURSOROPEN, 1, Tds.PROC_CURSORPREPARE, 2, Tds.PROC_CURSORPREPEXEC, 3);

    /** The same, by procedure name. */
    private static final Map<String, Integer> STATEMENT_BY_NAME = Map.of(
            "sp_executesql", 0, "sp_prepare", 2, "sp_prepexec", 2,
            "sp_cursoropen", 1, "sp_cursorprepare", 2, "sp_cursorprepexec", 3);

    private static final Map<String, Integer> PROC_IDS = Map.of(
            "sp_cursor", 1, "sp_cursoropen", 2, "sp_cursorexecute", 4, "sp_cursorprepexec", 5,
            "sp_cursorfetch", 7, "sp_cursorclose", 9);

    /** The {@code @cursor} output parameter of the procedures opening a cursor, by procedure id. */
    private static final Map<Integer, Integer> CURSOR_OUTPUT_BY_ID = Map.of(2, 0, 4, 1, 5, 1);

    /** Procedure ids whose first parameter is a cursor handle that may produce rows. */
    private static final java.util.Set<Integer> CURSOR_FETCH_IDS = java.util.Set.of(1, 7);

    private static final int PROC_CURSORCLOSE = 9;

    /** What the response processor needs to know about the request being answered. */
    public record RequestInfo(int cursorOutputOrdinal, Integer fetchedCursor, Integer closedCursor) {
        public static final RequestInfo NONE = new RequestInfo(-1, null, null);
    }

    /** The outcome of a rewrite: the payload to send, {@code null} when unchanged. */
    public record Result(byte[] payload, RequestInfo info) {
    }

    private final SqlTransformRewriter rewriter;
    private final SqlTransformRewriter.ValuesTransformer transformer;
    private final java.util.function.Supplier<PhaseConfig> beforePhase;

    public RequestRewriter(SqlTransformRewriter rewriter, SqlTransformRewriter.ValuesTransformer transformer,
                           java.util.function.Supplier<PhaseConfig> beforePhase) {
        this.rewriter = rewriter;
        this.transformer = transformer;
        this.beforePhase = beforePhase;
    }

    /**
     * Rewrite a SQL batch: ALL_HEADERS, then the UCS-2 text of the batch.
     *
     * @return the new payload, or {@code null} when the batch has nothing to rewrite
     */
    public byte[] rewriteBatch(byte[] payload) throws IOException, TransformException {
        int headersLength = allHeadersLength(payload);
        String sql = new String(payload, headersLength, payload.length - headersLength, StandardCharsets.UTF_16LE);
        String rewritten = rewriter.rewrite(sql, beforePhase.get(), transformer);
        if (rewritten == null || rewritten.equals(sql)) {
            return null;
        }
        LOG.debug("SQL batch rewritten");
        byte[] text = rewritten.getBytes(StandardCharsets.UTF_16LE);
        byte[] result = new byte[headersLength + text.length];
        System.arraycopy(payload, 0, result, 0, headersLength);
        System.arraycopy(text, 0, result, headersLength, text.length);
        return result;
    }

    /** @return the length of the ALL_HEADERS of a request, which starts with its total length */
    static int allHeadersLength(byte[] payload) throws TdsProtocolException {
        if (payload.length < 4) {
            return 0;
        }
        int length = (payload[0] & 0xFF) | (payload[1] & 0xFF) << 8 | (payload[2] & 0xFF) << 16 | (payload[3] & 0xFF) << 24;
        if (length < 4 || length > payload.length) {
            throw new TdsProtocolException("Invalid ALL_HEADERS length " + length);
        }
        return length;
    }

    /**
     * Rewrite an RPC request message, which may batch several calls. The flags asking the server to
     * leave out or reuse the metadata are cleared: the gateway needs it to find its way in the rows.
     *
     * @return the new payload (always rebuilt), and what the response processor needs to know
     */
    public Result rewriteRpc(byte[] payload) throws IOException, TransformException {
        int headersLength = allHeadersLength(payload);
        TdsReader reader = TdsReader.of(payload);
        // Recording from the start: the capture positions are the offsets in the payload.
        reader.startCapture();
        reader.skip(headersLength);
        TdsBuffer out = new TdsBuffer(payload.length + 64);
        out.writeBytes(payload, 0, headersLength);
        RequestInfo info = RequestInfo.NONE;
        boolean first = true;
        while (reader.capturePosition() < payload.length) {
            if (!first) {
                int separator = reader.readByte();
                if (separator != Tds.RPC_BATCH_FLAG && separator != Tds.RPC_NO_EXEC_FLAG) {
                    throw new TdsProtocolException(String.format("Unexpected RPC separator 0x%02X", separator));
                }
                out.writeByte(separator);
                if (reader.capturePosition() >= payload.length) {
                    break;
                }
            }
            first = false;
            RequestInfo callInfo = rewriteCall(reader, payload, out);
            if (callInfo == null) {
                // A table-valued parameter: the rest of the message was relayed as it is.
                break;
            }
            if (info == RequestInfo.NONE) {
                info = callInfo;
            }
        }
        return new Result(out.toByteArray(), info);
    }

    /**
     * Rewrite one call of an RPC request into {@code out}.
     *
     * @return what the response processor needs to know, {@code null} when a table-valued parameter
     *         made the gateway copy the rest of the message as it is
     */
    private RequestInfo rewriteCall(TdsReader reader, byte[] payload, TdsBuffer out)
            throws IOException, TransformException {
        int nameLength = reader.readUShort();
        Integer procId = null;
        Integer statementIndex;
        out.writeUShort(nameLength);
        if (nameLength == Tds.RPC_PROC_ID) {
            procId = reader.readUShort();
            out.writeUShort(procId);
            statementIndex = STATEMENT_BY_ID.get(procId);
        } else {
            String procName = reader.readUcs2(nameLength);
            out.writeUcs2(procName);
            String normalised = normalise(procName);
            procId = PROC_IDS.get(normalised);
            statementIndex = STATEMENT_BY_NAME.get(normalised);
        }
        int flags = reader.readUShort();
        out.writeUShort(flags & ~(Tds.RPC_NO_METADATA | Tds.RPC_REUSE_METADATA));
        Integer firstIntParameter = null;
        int index = 0;
        while (reader.capturePosition() < payload.length) {
            int parameterStart = reader.capturePosition();
            int next = payload[parameterStart] & 0xFF;
            if (next == Tds.RPC_BATCH_FLAG || next == Tds.RPC_NO_EXEC_FLAG) {
                break;
            }
            String name = reader.readBVarchar();
            int status = reader.readByte();
            if ((payload[reader.capturePosition()] & 0xFF) == Tds.TVP) {
                LOG.warn("RPC request with a table-valued parameter: the rest of the request is relayed untouched");
                out.writeBytes(payload, parameterStart, payload.length - parameterStart);
                reader.skip(payload.length - reader.capturePosition());
                return null;
            }
            TypeInfo type = TypeInfo.read(reader, true);
            TypeInfo.Value value = type.readValue(reader, false);
            String rewritten = statementIndex != null && index == statementIndex && type.isText() && !value.isNull()
                    ? rewriteStatement(type, value) : null;
            if (rewritten != null) {
                LOG.debug("Statement parameter of an RPC call rewritten");
                out.writeBVarchar(name).writeByte(status);
                TypeInfo nvarchar = TypeInfo.nvarcharMax(type.collation());
                nvarchar.write(out);
                nvarchar.writeValue(out, new TypeInfo.Value(rewritten.getBytes(StandardCharsets.UTF_16LE)), false);
            } else {
                out.writeBytes(payload, parameterStart, reader.capturePosition() - parameterStart);
            }
            if (index == 0 && !value.isNull() && value.data().length == 4
                    && (type.type() == Tds.INTN || type.type() == Tds.INT4)) {
                firstIntParameter = littleEndianInt(value.data());
            }
            index++;
        }
        return requestInfo(procId, firstIntParameter);
    }

    static int littleEndianInt(byte[] data) {
        return (data[0] & 0xFF) | (data[1] & 0xFF) << 8 | (data[2] & 0xFF) << 16 | (data[3] & 0xFF) << 24;
    }

    private static RequestInfo requestInfo(Integer procId, Integer firstIntParameter) {
        if (procId == null) {
            return RequestInfo.NONE;
        }
        Integer cursorOutput = CURSOR_OUTPUT_BY_ID.get(procId);
        if (cursorOutput != null) {
            return new RequestInfo(cursorOutput, null, null);
        }
        if (CURSOR_FETCH_IDS.contains(procId) && firstIntParameter != null) {
            return new RequestInfo(-1, firstIntParameter, null);
        }
        if (procId == PROC_CURSORCLOSE && firstIntParameter != null) {
            return new RequestInfo(-1, null, firstIntParameter);
        }
        return RequestInfo.NONE;
    }

    private String rewriteStatement(TypeInfo type, TypeInfo.Value value) throws TransformException {
        String sql = new String(value.data(), type.charset());
        String rewritten = rewriter.rewrite(sql, beforePhase.get(), transformer);
        return rewritten == null || rewritten.equals(sql) ? null : rewritten;
    }

    /** {@code [sys].[sp_executesql]}, {@code master..sp_executesql}... become {@code sp_executesql}. */
    static String normalise(String procName) {
        String name = procName.trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
