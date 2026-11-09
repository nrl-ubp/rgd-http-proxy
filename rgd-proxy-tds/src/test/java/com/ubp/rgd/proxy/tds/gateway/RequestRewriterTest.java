package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.sqlgateway.SqlTransformRewriter.SqlTransformSyntaxException;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import com.ubp.rgd.proxy.tds.protocol.ServerTokens;
import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TdsBuffer;
import com.ubp.rgd.proxy.tds.protocol.TdsReader;
import com.ubp.rgd.proxy.tds.protocol.TypeInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRewriterTest {

    private static final byte[] ALL_HEADERS = new TdsBuffer().writeInt(22).writeInt(18).writeUShort(2)
            .writeLong(0).writeInt(1).toByteArray();

    private final MappingConfig config = new MappingConfig();
    private final RequestRewriter rewriter = Fpe.requestRewriter(config);

    @Test
    void sqlBatchCallsAreReplacedByTheirToken() throws Exception {
        String token = Fpe.protect("Bernadette", "Person", "FirstName");
        byte[] batch = batch("SELECT * FROM PERSON WHERE NAME = transform('Person', 'FirstName', 'CH', N'Bernadette')");

        byte[] rewritten = rewriter.rewriteBatch(batch);

        assertArrayEquals(ALL_HEADERS, Arrays.copyOf(rewritten, ALL_HEADERS.length), "ALL_HEADERS kept");
        assertEquals("SELECT * FROM PERSON WHERE NAME = '" + token + "'", text(rewritten, ALL_HEADERS.length));
    }

    @Test
    void sqlBatchWithoutCallsIsLeftAlone() throws Exception {
        assertNull(rewriter.rewriteBatch(batch("SELECT 'transform(' FROM T")));
    }

    @Test
    void inactiveBeforePhaseIsAnError() {
        config.getBefore().setActive(false);
        assertThrows(SqlTransformSyntaxException.class,
                () -> rewriter.rewriteBatch(batch("SELECT transform('P', 'F', 'CH', 'x')")));
    }

    @Test
    void statementParameterOfSpPrepExecIsRewrittenAndOtherParametersRelayed() throws Exception {
        String token = Fpe.protect("Bernadette", "Person", "FirstName");
        byte[] handle = new TdsBuffer().writeBVarchar("@handle").writeByte(1).writeBytes(ServerTokens.intN())
                .writeBytes(ServerTokens.intValue(null)).toByteArray();
        byte[] params = nvarcharParameter("@params", "@P0 int");
        byte[] statement = nvarcharParameter("@stmt",
                "SELECT * FROM PERSON WHERE NAME = transform('Person', 'FirstName', 'CH', 'Bernadette') AND ID = @P0");
        byte[] p0 = new TdsBuffer().writeBVarchar("@P0").writeByte(0).writeBytes(ServerTokens.intN())
                .writeBytes(ServerTokens.intValue(42)).toByteArray();
        byte[] call = new TdsBuffer().writeUShort(Tds.RPC_PROC_ID).writeUShort(Tds.PROC_PREPEXEC)
                .writeUShort(Tds.RPC_NO_METADATA).writeBytes(handle).writeBytes(params).writeBytes(statement)
                .writeBytes(p0).toByteArray();
        byte[] second = new TdsBuffer().writeUShort(Tds.RPC_PROC_ID).writeUShort(12).writeUShort(0)
                .writeBytes(p0).toByteArray();
        byte[] payload = ServerTokens.join(ALL_HEADERS, call, new byte[]{(byte) Tds.RPC_BATCH_FLAG}, second);

        RequestRewriter.Result result = rewriter.rewriteRpc(payload);

        TdsReader reader = TdsReader.of(result.payload());
        reader.skip(ALL_HEADERS.length);
        assertEquals(Tds.RPC_PROC_ID, reader.readUShort());
        assertEquals(Tds.PROC_PREPEXEC, reader.readUShort());
        assertEquals(0, reader.readUShort(), "fNoMetaData cleared");
        assertArrayEquals(handle, reader.readBytes(handle.length));
        assertArrayEquals(params, reader.readBytes(params.length));
        assertEquals("@stmt", reader.readBVarchar());
        reader.readByte();
        TypeInfo type = TypeInfo.read(reader);
        assertEquals(TypeInfo.LengthKind.PLP, type.lengthKind(), "rewritten as nvarchar(max)");
        String sql = new String(type.readValue(reader, false).data(), StandardCharsets.UTF_16LE);
        assertEquals("SELECT * FROM PERSON WHERE NAME = '" + token + "' AND ID = @P0", sql);
        assertArrayEquals(p0, reader.readBytes(p0.length));
        assertEquals(Tds.RPC_BATCH_FLAG, reader.readByte());
        assertArrayEquals(second, reader.readRemaining(), "the second call is relayed untouched");
    }

    @Test
    void udtParametersOfAnRpcAreRelayed() throws Exception {
        String token = Fpe.protect("Bernadette", "Person", "FirstName");
        // A hierarchyid parameter, as SqlClient sends it: UDT_INFO_IN_RPC (names only), then a PLP value.
        byte[] udt = new TdsBuffer().writeBVarchar("@P1").writeByte(0).writeByte(Tds.UDT)
                .writeBVarchar("").writeBVarchar("sys").writeBVarchar("hierarchyid")
                .writeLong(2).writeInt(2).writeBytes(new byte[]{0x58, 0x40}).writeInt(0).toByteArray();
        byte[] call = new TdsBuffer().writeUShort(Tds.RPC_PROC_ID).writeUShort(Tds.PROC_EXECUTESQL).writeUShort(0)
                .writeBytes(nvarcharParameter("@stmt",
                        "SELECT * FROM T WHERE N = transform('Person', 'FirstName', 'CH', 'Bernadette') AND H = @P1"))
                .writeBytes(nvarcharParameter("@params", "@P1 hierarchyid")).writeBytes(udt).toByteArray();

        byte[] rewritten = rewriter.rewriteRpc(ServerTokens.join(ALL_HEADERS, call)).payload();

        String text = new String(rewritten, StandardCharsets.UTF_16LE);
        assertTrue(text.contains(token), "statement rewritten");
        assertArrayEquals(udt, Arrays.copyOfRange(rewritten, rewritten.length - udt.length, rewritten.length),
                "the UDT parameter is relayed byte for byte");
    }

    @Test
    void lengthsBeyondTheMessageAreRefusedWithoutAllocatingThem() {
        byte[] call = new TdsBuffer().writeUShort(Tds.RPC_PROC_ID).writeUShort(Tds.PROC_EXECUTESQL).writeUShort(0)
                .writeBVarchar("@stmt").writeByte(0).writeBytes(ServerTokens.nvarcharMax())
                .writeLong(Tds.PLP_UNKNOWN).writeInt(Integer.MAX_VALUE - 8).toByteArray();
        assertThrows(java.io.EOFException.class, () -> rewriter.rewriteRpc(ServerTokens.join(ALL_HEADERS, call)));
    }

    @Test
    void cursorProceduresAreReported() throws Exception {
        byte[] cursorOpen = new TdsBuffer().writeUShort(Tds.RPC_PROC_ID).writeUShort(Tds.PROC_CURSOROPEN).writeUShort(0)
                .writeBVarchar("@cursor").writeByte(1).writeBytes(ServerTokens.intN()).writeBytes(ServerTokens.intValue(null))
                .writeBytes(nvarcharParameter("@stmt", "SELECT 1")).toByteArray();
        assertEquals(0, rewriter.rewriteRpc(ServerTokens.join(ALL_HEADERS, cursorOpen)).info().cursorOutputOrdinal());

        byte[] fetch = new TdsBuffer().writeUShort(Tds.RPC_PROC_ID).writeUShort(7).writeUShort(0)
                .writeBVarchar("").writeByte(0).writeBytes(ServerTokens.intN()).writeBytes(ServerTokens.intValue(180150003))
                .toByteArray();
        assertEquals(180150003, rewriter.rewriteRpc(ServerTokens.join(ALL_HEADERS, fetch)).info().fetchedCursor());

        byte[] named = new TdsBuffer().writeUShort("sys.sp_cursorclose".length()).writeUcs2("sys.sp_cursorclose")
                .writeUShort(0).writeBVarchar("").writeByte(0).writeBytes(ServerTokens.intN())
                .writeBytes(ServerTokens.intValue(7)).toByteArray();
        assertEquals(7, rewriter.rewriteRpc(ServerTokens.join(ALL_HEADERS, named)).info().closedCursor());
    }

    @Test
    void procedureNamesAreNormalised() {
        assertEquals("sp_executesql", RequestRewriter.normalise("[sys].[sp_executesql]"));
        assertEquals("sp_prepexec", RequestRewriter.normalise("master..SP_PREPEXEC"));
    }

    private static byte[] nvarcharParameter(String name, String value) {
        return new TdsBuffer().writeBVarchar(name).writeByte(0).writeBytes(ServerTokens.nvarchar(4000))
                .writeBytes(ServerTokens.nvarcharValue(value)).toByteArray();
    }

    private static byte[] batch(String sql) {
        return ServerTokens.join(ALL_HEADERS, sql.getBytes(StandardCharsets.UTF_16LE));
    }

    private static String text(byte[] payload, int offset) {
        return new String(payload, offset, payload.length - offset, StandardCharsets.UTF_16LE);
    }
}
