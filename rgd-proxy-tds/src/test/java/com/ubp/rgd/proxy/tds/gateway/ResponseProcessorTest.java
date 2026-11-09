package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.sqlgateway.config.ColumnMapping;
import com.ubp.rgd.proxy.sqlgateway.config.DataMapping;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import com.ubp.rgd.proxy.tds.protocol.ColumnMetadata;
import com.ubp.rgd.proxy.tds.protocol.ServerTokens;
import com.ubp.rgd.proxy.tds.protocol.ServerTokens.Column;
import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TdsProtocolException;
import com.ubp.rgd.proxy.tds.protocol.TdsReader;
import com.ubp.rgd.proxy.tds.protocol.TypeInfo;
import com.ubp.rgd.proxy.transform.model.ValueKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResponseProcessorTest {

    private final MappingConfig config = new MappingConfig();
    private final List<Integer> calls = new ArrayList<>();

    @BeforeEach
    void mappings() {
        config.setColumnMappings(List.of(
                new ColumnMapping("*", "NAME", "Person", "FirstName"),
                new ColumnMapping("*", "BIRTH_DATE", "Person", "BirthDate"),
                new ColumnMapping("*", "SALARY", "Person", "Salary"),
                new ColumnMapping("*", "UPDATED", "Person", "Updated")));
    }

    @Test
    void textAndTypedColumnsAreDetokenizedAndTextColumnsWidened() throws Exception {
        String name = Fpe.protect("Bernadette", "Person", "FirstName");
        LocalDate birth = LocalDate.parse(Fpe.protect("1980-05-17", "Person", "BirthDate", ValueKind.DATE));
        BigDecimal salary = new BigDecimal(Fpe.protect("12345.67", "Person", "Salary", ValueKind.NUMBER));
        LocalDate updated = LocalDate.parse(Fpe.protect("2024-02-29", "Person", "Updated", ValueKind.DATE));
        byte[] response = ServerTokens.join(
                ServerTokens.colMetadata(new Column("ID", ServerTokens.intN()), new Column("NAME", ServerTokens.nchar(12)),
                        new Column("BIRTH_DATE", ServerTokens.date()), new Column("SALARY", ServerTokens.decimal(10, 2)),
                        new Column("UPDATED", ServerTokens.datetime2(0)), new Column("NOTE", ServerTokens.nvarcharMax())),
                ServerTokens.row(ServerTokens.intValue(1), ServerTokens.nvarcharValue(name), ServerTokens.dateValue(birth),
                        ServerTokens.decimalValue(salary, 10, 2), ServerTokens.datetime2Value(updated, 3600),
                        ServerTokens.plpValue("no token here")),
                ServerTokens.nbcRow(ServerTokens.intValue(2), null, null, null, null, null),
                ServerTokens.done(0x10, 2));

        Parsed parsed = process(Fpe.responseProcessor(config, 1024, calls), response);

        List<ColumnMetadata> columns = parsed.columns;
        assertEquals(Tds.NVARCHAR, columns.get(1).typeInfo().type(), "nchar announced as nvarchar");
        assertEquals(Tds.MAX_SHORT_LENGTH, columns.get(1).typeInfo().maxLength());
        assertEquals(Tds.DATEN, columns.get(2).typeInfo().type(), "typed columns keep their type");
        assertEquals("Bernadette", text(parsed.rows.get(0).get(1)));
        assertEquals("1980-05-17", TypedValues.read(columns.get(2).typeInfo(), parsed.rows.get(0).get(2)));
        assertEquals("12345.67", TypedValues.read(columns.get(3).typeInfo(), parsed.rows.get(0).get(3)));
        byte[] datetime2 = parsed.rows.get(0).get(4);
        assertEquals("2024-02-29", TypedValues.read(columns.get(4).typeInfo(), datetime2));
        assertEquals(3600, (datetime2[0] & 0xFF) | (datetime2[1] & 0xFF) << 8, "the time of day is kept");
        assertEquals("no token here", text(parsed.rows.get(0).get(5)));
        assertNull(parsed.rows.get(1).get(1), "NBCROW nulls kept");
        assertEquals(List.of(4), calls, "one transformer call for the batch");
        assertEquals(Tds.TOKEN_DONE, parsed.tokens.get(parsed.tokens.size() - 1));
    }

    @Test
    void implicitDetokenizationThroughDataMappingsAndBatching() throws Exception {
        config.setColumnMappings(List.of());
        DataMapping mapping = new DataMapping();
        mapping.setRegex("RG\\{[^}]+\\}");
        mapping.setRpsClassName("Person");
        mapping.setRpsPropertyName("FirstName");
        config.setDataMappings(List.of(mapping));
        String token = Fpe.protect("Jean", "Person", "FirstName");
        List<byte[]> parts = new ArrayList<>();
        parts.add(ServerTokens.colMetadata(new Column("ALIAS", ServerTokens.varchar(30))));
        for (int i = 0; i < 5; i++) {
            parts.add(ServerTokens.row(ServerTokens.varcharValue("Hello " + token + "!")));
        }
        parts.add(ServerTokens.done(0x10, 5));

        Parsed parsed = process(Fpe.responseProcessor(config, 2, calls), ServerTokens.join(parts));

        assertEquals(5, parsed.rows.size());
        parsed.rows.forEach(row -> assertEquals("Hello Jean!", new String(row.get(0), StandardCharsets.ISO_8859_1)));
        assertEquals(List.of(2, 2, 1), calls, "rows are batched by proxy.tds.batch-size");
    }

    @Test
    void reusedMetadataOfACursorIsFound() throws Exception {
        String token = Fpe.protect("Bernadette", "Person", "FirstName");
        ResponseProcessor processor = Fpe.responseProcessor(config, 1024, calls);
        byte[] open = ServerTokens.join(
                ServerTokens.colMetadata(new Column("NAME", ServerTokens.nvarchar(20))),
                ServerTokens.doneInProc(0x10, 0),
                returnValueInt(0, 180150003), ServerTokens.doneProc(0));
        process(processor, open, new RequestRewriter.RequestInfo(0, null, null));
        // Another result set in between: the cursor's metadata must still be found.
        process(processor, ServerTokens.join(ServerTokens.colMetadata(new Column("X", ServerTokens.intN())),
                ServerTokens.row(ServerTokens.intValue(1)), ServerTokens.done(0x10, 1)));

        byte[] fetch = ServerTokens.join(new byte[]{(byte) Tds.TOKEN_COLMETADATA, (byte) 0xFF, (byte) 0xFF},
                ServerTokens.row(ServerTokens.nvarcharValue(token)), ServerTokens.doneInProc(0x10, 1),
                ServerTokens.doneProc(0));
        Parsed parsed = process(processor, fetch, new RequestRewriter.RequestInfo(-1, 180150003, null),
                List.of(new ColumnMetadata(0, 0, TypeInfo.read(TdsReader.of(ServerTokens.nvarchar(20))).widenedForText(),
                        null, "NAME")));

        assertEquals("Bernadette", text(parsed.rows.get(0).get(0)));
    }

    @Test
    void responsesAreRelayedByteForByteWhenNothingIsDetokenized() throws Exception {
        config.setColumnMappings(List.of());
        byte[] response = ServerTokens.join(
                ServerTokens.colMetadata(new Column("ID", ServerTokens.intN()), new Column("D", ServerTokens.date())),
                ServerTokens.row(ServerTokens.intValue(7), ServerTokens.dateValue(LocalDate.of(2020, 1, 1))),
                ServerTokens.error(208, 16, "Invalid object name 'X'."), ServerTokens.returnStatus(0),
                ServerTokens.done(0x12, 0));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Fpe.responseProcessor(config, 1024, calls).process(new ByteArrayInputStream(response), out,
                RequestRewriter.RequestInfo.NONE);
        assertArrayEquals(response, out.toByteArray());
    }

    @Test
    void unsupportedTokensCloseTheSession() {
        byte[] response = {(byte) Tds.TOKEN_ALTMETADATA, 0, 0};
        assertThrows(TdsProtocolException.class, () -> Fpe.responseProcessor(config, 1024, calls)
                .process(new ByteArrayInputStream(response), new ByteArrayOutputStream(), RequestRewriter.RequestInfo.NONE));
    }

    private static byte[] returnValueInt(int ordinal, int value) {
        return ServerTokens.join(new com.ubp.rgd.proxy.tds.protocol.TdsBuffer().writeByte(Tds.TOKEN_RETURNVALUE)
                .writeUShort(ordinal).writeBVarchar("@cursor").writeByte(1).writeInt(0).writeUShort(0).toByteArray(),
                ServerTokens.intN(), ServerTokens.intValue(value));
    }

    private static String text(byte[] data) {
        return data == null ? null : new String(data, StandardCharsets.UTF_16LE);
    }

    record Parsed(List<ColumnMetadata> columns, List<List<byte[]>> rows, List<Integer> tokens) {
    }

    private static Parsed process(ResponseProcessor processor, byte[] response) throws Exception {
        return process(processor, response, RequestRewriter.RequestInfo.NONE);
    }

    private static Parsed process(ResponseProcessor processor, byte[] response, RequestRewriter.RequestInfo info)
            throws Exception {
        return process(processor, response, info, null);
    }

    /** Process a response, then parse what the client receives. */
    private static Parsed process(ResponseProcessor processor, byte[] response, RequestRewriter.RequestInfo info,
                                  List<ColumnMetadata> known) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        processor.process(new ByteArrayInputStream(response), out, info);
        TdsReader reader = TdsReader.of(out.toByteArray());
        List<ColumnMetadata> columns = known;
        List<List<byte[]>> rows = new ArrayList<>();
        List<Integer> tokens = new ArrayList<>();
        int token;
        while ((token = reader.readByteOrEnd()) >= 0) {
            tokens.add(token);
            switch (token) {
                case Tds.TOKEN_COLMETADATA -> {
                    int count = reader.readUShort();
                    if (count != Tds.NO_METADATA) {
                        columns = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            columns.add(ColumnMetadata.read(reader));
                        }
                    }
                }
                case Tds.TOKEN_ROW, Tds.TOKEN_NBCROW -> {
                    byte[] bitmap = token == Tds.TOKEN_NBCROW ? reader.readBytes((columns.size() + 7) / 8) : null;
                    List<byte[]> row = new ArrayList<>();
                    for (int i = 0; i < columns.size(); i++) {
                        if (bitmap != null && (bitmap[i / 8] & (1 << (i % 8))) != 0) {
                            row.add(null);
                        } else {
                            row.add(columns.get(i).typeInfo().readValue(reader, true).data());
                        }
                    }
                    rows.add(row);
                }
                case Tds.TOKEN_RETURNVALUE -> {
                    reader.readUShort();
                    reader.readBVarchar();
                    reader.readByte();
                    reader.readInt();
                    reader.readUShort();
                    TypeInfo.read(reader).readValue(reader, true);
                }
                default -> TokenLengths.skipSimpleToken(token, reader);
            }
        }
        return new Parsed(columns, rows, tokens);
    }
}
