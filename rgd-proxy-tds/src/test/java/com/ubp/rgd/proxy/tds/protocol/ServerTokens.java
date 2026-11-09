package com.ubp.rgd.proxy.tds.protocol;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** Builds the token streams a SQL Server sends, for the tests. */
public final class ServerTokens {

    /** Latin1_General_CI_AS. */
    public static final byte[] COLLATION = {0x09, 0x04, (byte) 0xD0, 0x00, 0x34};

    private ServerTokens() {
    }

    /** A column: its name and TYPE_INFO bytes. */
    public record Column(String name, byte[] typeInfo) {
    }

    public static byte[] nvarchar(int chars) {
        return new TdsBuffer().writeByte(Tds.NVARCHAR).writeUShort(chars * 2).writeBytes(COLLATION).toByteArray();
    }

    public static byte[] nchar(int chars) {
        return new TdsBuffer().writeByte(Tds.NCHAR).writeUShort(chars * 2).writeBytes(COLLATION).toByteArray();
    }

    public static byte[] varchar(int length) {
        return new TdsBuffer().writeByte(Tds.BIGVARCHAR).writeUShort(length).writeBytes(COLLATION).toByteArray();
    }

    public static byte[] nvarcharMax() {
        return new TdsBuffer().writeByte(Tds.NVARCHAR).writeUShort(Tds.MAX_LENGTH).writeBytes(COLLATION).toByteArray();
    }

    public static byte[] intN() {
        return new byte[]{(byte) Tds.INTN, 4};
    }

    public static byte[] date() {
        return new byte[]{(byte) Tds.DATEN};
    }

    public static byte[] datetime() {
        return new byte[]{(byte) Tds.DATETIMN, 8};
    }

    public static byte[] datetime2(int scale) {
        return new byte[]{(byte) Tds.DATETIME2N, (byte) scale};
    }

    public static byte[] decimal(int precision, int scale) {
        return new byte[]{(byte) Tds.DECIMALN, (byte) decimalLength(precision), (byte) precision, (byte) scale};
    }

    static int decimalLength(int precision) {
        return precision <= 9 ? 5 : precision <= 19 ? 9 : precision <= 28 ? 13 : 17;
    }

    public static byte[] colMetadata(Column... columns) {
        TdsBuffer buffer = new TdsBuffer();
        buffer.writeByte(Tds.TOKEN_COLMETADATA).writeUShort(columns.length);
        for (Column column : columns) {
            buffer.writeInt(0).writeUShort(0x0009).writeBytes(column.typeInfo()).writeBVarchar(column.name());
        }
        return buffer.toByteArray();
    }

    /** A ROW of already encoded values. */
    public static byte[] row(byte[]... values) {
        TdsBuffer buffer = new TdsBuffer().writeByte(Tds.TOKEN_ROW);
        for (byte[] value : values) {
            buffer.writeBytes(value);
        }
        return buffer.toByteArray();
    }

    /** An NBCROW: {@code null} values are flagged in the bitmap. */
    public static byte[] nbcRow(byte[]... values) {
        TdsBuffer buffer = new TdsBuffer().writeByte(Tds.TOKEN_NBCROW);
        byte[] bitmap = new byte[(values.length + 7) / 8];
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                bitmap[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        buffer.writeBytes(bitmap);
        for (byte[] value : values) {
            if (value != null) {
                buffer.writeBytes(value);
            }
        }
        return buffer.toByteArray();
    }

    public static byte[] nvarcharValue(String value) {
        if (value == null) {
            return new TdsBuffer().writeUShort(Tds.USHORT_NULL).toByteArray();
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
        return new TdsBuffer().writeUShort(bytes.length).writeBytes(bytes).toByteArray();
    }

    public static byte[] varcharValue(String value) {
        byte[] bytes = value.getBytes(java.nio.charset.Charset.forName("windows-1252"));
        return new TdsBuffer().writeUShort(bytes.length).writeBytes(bytes).toByteArray();
    }

    public static byte[] plpValue(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
        TdsBuffer buffer = new TdsBuffer().writeLong(Tds.PLP_UNKNOWN);
        int half = bytes.length / 2 / 2 * 2;
        buffer.writeInt(half).writeBytes(bytes, 0, half);
        buffer.writeInt(bytes.length - half).writeBytes(bytes, half, bytes.length - half);
        return buffer.writeInt(0).toByteArray();
    }

    public static byte[] intValue(Integer value) {
        return value == null ? new byte[]{0} : new TdsBuffer().writeByte(4).writeInt(value).toByteArray();
    }

    public static byte[] dateValue(LocalDate date) {
        long days = date.toEpochDay() - LocalDate.of(1, 1, 1).toEpochDay();
        return new byte[]{3, (byte) days, (byte) (days >>> 8), (byte) (days >>> 16)};
    }

    /** A datetime2(0) value. */
    public static byte[] datetime2Value(LocalDate date, int secondsOfDay) {
        long days = date.toEpochDay() - LocalDate.of(1, 1, 1).toEpochDay();
        return new byte[]{6, (byte) secondsOfDay, (byte) (secondsOfDay >>> 8), (byte) (secondsOfDay >>> 16),
                (byte) days, (byte) (days >>> 8), (byte) (days >>> 16)};
    }

    public static byte[] datetimeValue(LocalDate date, int ticks) {
        int days = (int) (date.toEpochDay() - LocalDate.of(1900, 1, 1).toEpochDay());
        return new TdsBuffer().writeByte(8).writeInt(days).writeInt(ticks).toByteArray();
    }

    public static byte[] decimalValue(BigDecimal value, int precision, int scale) {
        int length = decimalLength(precision);
        byte[] magnitude = value.setScale(scale).unscaledValue().abs().toByteArray();
        byte[] bytes = new byte[length];
        bytes[0] = (byte) (value.signum() < 0 ? 0 : 1);
        for (int i = 0; i < length - 1 && i < magnitude.length; i++) {
            bytes[1 + i] = magnitude[magnitude.length - 1 - i];
        }
        return new TdsBuffer().writeByte(length).writeBytes(bytes).toByteArray();
    }

    public static byte[] done(int status, long rows) {
        return new TdsBuffer().writeByte(Tds.TOKEN_DONE).writeUShort(status).writeUShort(0xC1).writeLong(rows).toByteArray();
    }

    public static byte[] doneInProc(int status, long rows) {
        return new TdsBuffer().writeByte(Tds.TOKEN_DONEINPROC).writeUShort(status).writeUShort(0xC1).writeLong(rows)
                .toByteArray();
    }

    public static byte[] doneProc(int status) {
        return new TdsBuffer().writeByte(Tds.TOKEN_DONEPROC).writeUShort(status).writeUShort(0xE0).writeLong(0)
                .toByteArray();
    }

    public static byte[] returnStatus(int status) {
        return new TdsBuffer().writeByte(Tds.TOKEN_RETURNSTATUS).writeInt(status).toByteArray();
    }

    public static byte[] error(int number, int severity, String message) {
        TdsBuffer buffer = new TdsBuffer();
        Tokens.error(buffer, number, 1, severity, message);
        return buffer.toByteArray();
    }

    public static byte[] join(List<byte[]> parts) {
        TdsBuffer buffer = new TdsBuffer();
        parts.forEach(buffer::writeBytes);
        return buffer.toByteArray();
    }

    public static byte[] join(byte[]... parts) {
        return join(List.of(parts));
    }

    /** A LOGIN7 as a client would build it. */
    public static byte[] login7(String user, byte[] password, String app, String server, String database, int[] features) {
        TdsBuffer fixed = new TdsBuffer();
        String[] texts = {"client-host", user, null, app, server, null, "jdbc", "", database, null, "", null};
        byte[][] values = new byte[texts.length][];
        for (int i = 0; i < texts.length; i++) {
            values[i] = texts[i] == null ? new byte[0] : texts[i].getBytes(StandardCharsets.UTF_16LE);
        }
        values[2] = password;
        boolean extension = features.length > 0;
        values[5] = extension ? new byte[4] : new byte[0];
        int[] offsets = {36, 40, 44, 48, 52, 56, 60, 64, 68, 78, 82, 86};
        boolean[] characters = {true, true, true, true, true, false, true, true, true, false, true, true};
        byte[] head = new byte[Login7.FIXED_LENGTH];
        TdsBuffer data = new TdsBuffer();
        int position = Login7.FIXED_LENGTH;
        int extensionPosition = 0;
        for (int i = 0; i < offsets.length; i++) {
            if (i == 5) {
                extensionPosition = position;
            }
            put(head, offsets[i], position, 2);
            put(head, offsets[i] + 2, characters[i] ? values[i].length / 2 : values[i].length, 2);
            data.writeBytes(values[i]);
            position += values[i].length;
        }
        byte[] body = data.toByteArray();
        TdsBuffer featureData = new TdsBuffer();
        for (int feature : features) {
            featureData.writeByte(feature).writeInt(1).writeByte(1);
        }
        if (extension) {
            featureData.writeByte(Tds.FEATURE_TERMINATOR);
            put(body, extensionPosition - Login7.FIXED_LENGTH, position, 4);
            head[27] = (byte) Tds.LOGIN_EXTENSION;
        }
        byte[] featureBytes = featureData.toByteArray();
        put(head, 4, Tds.VERSION_7_4, 4);
        put(head, 8, 8000, 4);
        int total = Login7.FIXED_LENGTH + body.length + featureBytes.length;
        put(head, 0, total, 4);
        fixed.writeBytes(head).writeBytes(body).writeBytes(featureBytes);
        return fixed.toByteArray();
    }

    private static void put(byte[] bytes, int offset, int value, int length) {
        for (int i = 0; i < length; i++) {
            bytes[offset + i] = (byte) (value >>> (8 * i));
        }
    }
}
