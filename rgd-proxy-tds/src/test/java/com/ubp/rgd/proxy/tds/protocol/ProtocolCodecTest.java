package com.ubp.rgd.proxy.tds.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCodecTest {

    @Test
    void messagesAreSplitIntoPacketsAndJoinedBack() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        TdsChannel writer = new TdsChannel("w", new ByteArrayInputStream(new byte[0]), wire);
        writer.setPacketSize(512);
        byte[] payload = new byte[1500];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        writer.writeMessage(Tds.PKT_SQL_BATCH, Tds.STATUS_RESETCONNECTION, payload);

        TdsChannel reader = new TdsChannel("r", new ByteArrayInputStream(wire.toByteArray()), new ByteArrayOutputStream());
        TdsMessage message = reader.readMessage();
        assertEquals(3, message.packets().size(), "504 bytes of payload per 512-byte packet");
        assertEquals(Tds.STATUS_RESETCONNECTION, message.status());
        assertEquals(0, message.packets().get(1).status(), "RESETCONNECTION only on the first packet");
        assertTrue(message.packets().get(2).isEndOfMessage());
        assertArrayEquals(payload, message.payload());
        assertNull(reader.readMessage(), "end of stream between messages");
    }

    @Test
    void preLoginRoundTrips() throws Exception {
        PreLogin preLogin = new PreLogin().set(Tds.PL_VERSION, new byte[]{16, 0, 0x10, 0, 0, 0})
                .encryption(Tds.ENCRYPT_ON).set(Tds.PL_INSTOPT, new byte[]{0}).set(Tds.PL_MARS, new byte[]{1});
        PreLogin parsed = PreLogin.parse(preLogin.encode());
        assertEquals(Tds.ENCRYPT_ON, parsed.encryption());
        assertTrue(parsed.mars());
        assertFalse(parsed.fedAuthRequired());
        assertArrayEquals(new byte[]{16, 0, 0x10, 0, 0, 0}, parsed.get(Tds.PL_VERSION));
    }

    @Test
    void login7IsReEncodedWithoutTheFeaturesTheGatewayRemoves() throws Exception {
        byte[] password = Login7.obfuscatePassword("S3cret!");
        byte[] payload = ServerTokens.login7("alice", password, "app", "gw-host", "db1", new int[]{
                Tds.FEATURE_SESSIONRECOVERY, Tds.FEATURE_COLUMNENCRYPTION, Tds.FEATURE_UTF8_SUPPORT});

        Login7 login = Login7.parse(payload);
        assertEquals("alice", login.text(Login7.Field.USER_NAME));
        assertEquals(3, login.features().size());
        assertThrows(IllegalArgumentException.class, () -> login.text(Login7.Field.PASSWORD));

        login.keepFeatures(Set.of(Tds.FEATURE_UTF8_SUPPORT));
        login.setText(Login7.Field.SERVER_NAME, "backend.example");
        login.setTdsVersion(Tds.VERSION_7_4);
        Login7 reparsed = Login7.parse(login.encode());

        assertEquals("alice", reparsed.text(Login7.Field.USER_NAME));
        assertArrayEquals(password, reparsed.field(Login7.Field.PASSWORD), "password relayed as obfuscated");
        assertEquals("backend.example", reparsed.text(Login7.Field.SERVER_NAME));
        assertEquals("db1", reparsed.text(Login7.Field.DATABASE));
        assertEquals(1, reparsed.features().size());
        assertEquals(Tds.FEATURE_UTF8_SUPPORT, reparsed.features().get(0).id());
        assertEquals(Tds.VERSION_7_4, reparsed.tdsVersion());
        assertEquals(8000, reparsed.packetSize());
    }

    @Test
    void login7WithAnOldTdsVersionIsRefused() {
        byte[] payload = ServerTokens.login7("bob", new byte[0], "app", "gw", "db", new int[0]);
        payload[4] = 0x01;
        payload[5] = 0x00;
        payload[6] = 0x00;
        payload[7] = 0x71;
        assertThrows(TdsProtocolException.class, () -> Login7.parse(payload));
    }

    @Test
    void collationsGiveTheirCodePage() {
        assertEquals("windows-1252", new Collation(0x00D00409, 52).charset().name(), "SQL_Latin1_General_CP1_CI_AS");
        assertEquals("windows-1251", new Collation(0x00D00419, 0).charset().name(), "Cyrillic_General_CI_AS");
        assertEquals("windows-1250", new Collation(0x00D00405, 0).charset().name(), "Czech_CI_AS");
        assertEquals("windows-31j", new Collation(0x00D00411, 0).charset().name(), "Japanese_CI_AS");
        assertEquals("UTF-8", new Collation(0x04D00409, 0).charset().name(), "Latin1_General_100_CI_AS_SC_UTF8");
        assertEquals("IBM850", new Collation(0x00D00409, 40).charset().name(), "SQL_Latin1_General_CP850_BIN");
    }

    @Test
    void typeInfosAndValuesRoundTrip() throws Exception {
        byte[][] typeInfos = {ServerTokens.nvarchar(50), ServerTokens.varchar(20), ServerTokens.nvarcharMax(),
                ServerTokens.intN(), ServerTokens.date(), ServerTokens.datetime2(3), ServerTokens.decimal(10, 2),
                {(byte) Tds.INT8}, {(byte) Tds.XML, 0},
                new TdsBuffer().writeByte(Tds.NTEXT).writeInt(0x7FFFFFFF).writeBytes(ServerTokens.COLLATION).toByteArray()};
        for (byte[] bytes : typeInfos) {
            TypeInfo info = TypeInfo.read(TdsReader.of(bytes));
            TdsBuffer encoded = new TdsBuffer();
            info.write(encoded);
            assertArrayEquals(bytes, encoded.toByteArray(), info.toString());
        }

        TypeInfo max = TypeInfo.read(TdsReader.of(ServerTokens.nvarcharMax()));
        assertEquals(TypeInfo.LengthKind.PLP, max.lengthKind());
        TypeInfo.Value value = max.readValue(TdsReader.of(ServerTokens.plpValue("chunked value")), true);
        assertEquals("chunked value", new String(value.data(), StandardCharsets.UTF_16LE));

        TypeInfo ntext = TypeInfo.read(TdsReader.of(typeInfos[9]));
        byte[] textValue = new TdsBuffer().writeByte(16).writeBytes(new byte[16]).writeBytes(new byte[8])
                .writeInt(4).writeBytes("ab".getBytes(StandardCharsets.UTF_16LE)).toByteArray();
        TypeInfo.Value text = ntext.readValue(TdsReader.of(textValue), true);
        TdsBuffer reencoded = new TdsBuffer();
        ntext.writeValue(reencoded, text, true);
        assertArrayEquals(textValue, reencoded.toByteArray());
    }

    @Test
    void textColumnsAreWidened() throws Exception {
        TypeInfo nchar = TypeInfo.read(TdsReader.of(ServerTokens.nchar(10)));
        TypeInfo wide = nchar.widenedForText();
        assertEquals(Tds.NVARCHAR, wide.type());
        assertEquals(Tds.MAX_SHORT_LENGTH, wide.maxLength());
        assertEquals(nchar.collation(), wide.collation());
        TypeInfo max = TypeInfo.read(TdsReader.of(ServerTokens.nvarcharMax()));
        assertTrue(max == max.widenedForText(), "a max column is kept");
    }

    @Test
    void unknownTypesAreRefused() {
        assertThrows(TdsProtocolException.class, () -> TypeInfo.read(TdsReader.of(new byte[]{(byte) 0x99})));
    }
}
