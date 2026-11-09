package com.ubp.rgd.proxy.tds.protocol;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A TYPE_INFO of [MS-TDS] 2.2.5.6, as found in COLMETADATA, RETURNVALUE and the parameters of an RPC
 * request, with the encoding of its values (2.2.5.2): every type of the specification is known, so
 * that the boundaries of a row are always found.
 */
public final class TypeInfo {

    /** How the length of a value is announced. */
    public enum LengthKind {
        /** No length: the type has a fixed size. */
        FIXED,
        /** BYTELEN, 0 (or 0xFF for the legacy character and binary types) meaning NULL. */
        BYTE,
        /** USHORTLEN, 0xFFFF meaning NULL. */
        USHORT,
        /** {@code text}, {@code ntext} and {@code image}: a text pointer and a LONGLEN value. */
        TEXT,
        /** {@code sql_variant}: LONGLEN, 0 meaning NULL. */
        VARIANT,
        /** Partially length-prefixed: {@code max} types, {@code xml} and UDT. */
        PLP
    }

    private final int type;
    private LengthKind lengthKind;
    private int fixedLength;
    private long maxLength;
    private int precision;
    private int scale;
    private Collation collation;
    private byte[] extra = new byte[0];
    private boolean inRpc;

    private TypeInfo(int type) {
        this.type = type;
    }

    public int type() {
        return type;
    }

    public LengthKind lengthKind() {
        return lengthKind;
    }

    public long maxLength() {
        return maxLength;
    }

    public int precision() {
        return precision;
    }

    public int scale() {
        return scale;
    }

    public Collation collation() {
        return collation;
    }

    /** @return whether the type holds text: char, varchar, nchar, nvarchar (max or not), text, ntext */
    public boolean isText() {
        return switch (type) {
            case Tds.BIGCHAR, Tds.BIGVARCHAR, Tds.NCHAR, Tds.NVARCHAR, Tds.TEXT, Tds.NTEXT, Tds.CHAR, Tds.VARCHAR -> true;
            default -> false;
        };
    }

    public boolean isUnicode() {
        return type == Tds.NCHAR || type == Tds.NVARCHAR || type == Tds.NTEXT;
    }

    /** @return whether COLMETADATA follows the TYPE_INFO of the column with the name of its table */
    public boolean hasTableName() {
        return type == Tds.TEXT || type == Tds.NTEXT || type == Tds.IMAGE;
    }

    /** @return the charset of the text values of the column */
    public Charset charset() {
        if (isUnicode()) {
            return StandardCharsets.UTF_16LE;
        }
        return collation == null ? Charset.forName("windows-1252") : collation.charset();
    }

    /**
     * Read a TYPE_INFO.
     *
     * @throws TdsProtocolException for a type the gateway does not know, or a table-valued parameter
     */
    public static TypeInfo read(TdsReader reader) throws IOException {
        return read(reader, false);
    }

    /**
     * Read a TYPE_INFO.
     *
     * @param inRpc whether it is the type of an RPC parameter, where a UDT is described by its names
     *              only (UDT_INFO_IN_RPC)
     * @throws TdsProtocolException for a type the gateway does not know, or a table-valued parameter
     */
    public static TypeInfo read(TdsReader reader, boolean inRpc) throws IOException {
        TypeInfo info = new TypeInfo(reader.readByte());
        info.inRpc = inRpc;
        switch (info.type) {
            case Tds.NULLTYPE -> info.fixed(0);
            case Tds.INT1, Tds.BIT -> info.fixed(1);
            case Tds.INT2 -> info.fixed(2);
            case Tds.INT4, Tds.DATETIM4, Tds.FLT4, Tds.MONEY4 -> info.fixed(4);
            case Tds.MONEY, Tds.DATETIME, Tds.FLT8, Tds.INT8 -> info.fixed(8);
            case Tds.GUID, Tds.INTN, Tds.BITN, Tds.FLTN, Tds.MONEYN, Tds.DATETIMN,
                 Tds.CHAR, Tds.VARCHAR, Tds.BINARY, Tds.VARBINARY -> {
                info.lengthKind = LengthKind.BYTE;
                info.maxLength = reader.readByte();
            }
            case Tds.DECIMAL, Tds.NUMERIC, Tds.DECIMALN, Tds.NUMERICN -> {
                info.lengthKind = LengthKind.BYTE;
                info.maxLength = reader.readByte();
                info.precision = reader.readByte();
                info.scale = reader.readByte();
            }
            case Tds.DATEN -> info.lengthKind = LengthKind.BYTE;
            case Tds.TIMEN, Tds.DATETIME2N, Tds.DATETIMEOFFSETN -> {
                info.lengthKind = LengthKind.BYTE;
                info.scale = reader.readByte();
            }
            case Tds.BIGVARBINARY, Tds.BIGBINARY -> info.ushortLength(reader);
            case Tds.BIGVARCHAR, Tds.BIGCHAR, Tds.NVARCHAR, Tds.NCHAR -> {
                info.ushortLength(reader);
                info.collation = Collation.read(reader);
            }
            case Tds.TEXT, Tds.NTEXT -> {
                info.lengthKind = LengthKind.TEXT;
                info.maxLength = reader.readUInt();
                info.collation = Collation.read(reader);
            }
            case Tds.IMAGE -> {
                info.lengthKind = LengthKind.TEXT;
                info.maxLength = reader.readUInt();
            }
            case Tds.SSVARIANT -> {
                info.lengthKind = LengthKind.VARIANT;
                info.maxLength = reader.readUInt();
            }
            case Tds.XML -> {
                info.lengthKind = LengthKind.PLP;
                TdsBuffer schema = new TdsBuffer(16);
                int present = reader.readByte();
                schema.writeByte(present);
                if (present != 0) {
                    schema.writeBVarchar(reader.readBVarchar()).writeBVarchar(reader.readBVarchar())
                            .writeUsVarchar(reader.readUsVarchar());
                }
                info.extra = schema.toByteArray();
            }
            case Tds.UDT -> {
                info.lengthKind = LengthKind.PLP;
                if (inRpc) {
                    info.extra = new TdsBuffer(64).writeBVarchar(reader.readBVarchar())
                            .writeBVarchar(reader.readBVarchar()).writeBVarchar(reader.readBVarchar()).toByteArray();
                    break;
                }
                info.maxLength = reader.readUShort();
                info.extra = new TdsBuffer(64).writeBVarchar(reader.readBVarchar()).writeBVarchar(reader.readBVarchar())
                        .writeBVarchar(reader.readBVarchar()).writeUsVarchar(reader.readUsVarchar()).toByteArray();
            }
            case Tds.TVP -> throw new TdsProtocolException("Table-valued parameters are not supported");
            default -> throw new TdsProtocolException(String.format("Unknown TDS data type 0x%02X", info.type));
        }
        return info;
    }

    private void fixed(int length) {
        lengthKind = LengthKind.FIXED;
        fixedLength = length;
    }

    private void ushortLength(TdsReader reader) throws IOException {
        maxLength = reader.readUShort();
        lengthKind = maxLength == Tds.MAX_LENGTH ? LengthKind.PLP : LengthKind.USHORT;
    }

    public void write(TdsBuffer buffer) {
        buffer.writeByte(type);
        switch (type) {
            case Tds.GUID, Tds.INTN, Tds.BITN, Tds.FLTN, Tds.MONEYN, Tds.DATETIMN,
                 Tds.CHAR, Tds.VARCHAR, Tds.BINARY, Tds.VARBINARY -> buffer.writeByte((int) maxLength);
            case Tds.DECIMAL, Tds.NUMERIC, Tds.DECIMALN, Tds.NUMERICN ->
                    buffer.writeByte((int) maxLength).writeByte(precision).writeByte(scale);
            case Tds.TIMEN, Tds.DATETIME2N, Tds.DATETIMEOFFSETN -> buffer.writeByte(scale);
            case Tds.BIGVARBINARY, Tds.BIGBINARY -> buffer.writeUShort((int) maxLength);
            case Tds.BIGVARCHAR, Tds.BIGCHAR, Tds.NVARCHAR, Tds.NCHAR -> {
                buffer.writeUShort((int) maxLength);
                collation.write(buffer);
            }
            case Tds.TEXT, Tds.NTEXT -> {
                buffer.writeInt((int) maxLength);
                collation.write(buffer);
            }
            case Tds.IMAGE, Tds.SSVARIANT -> buffer.writeInt((int) maxLength);
            case Tds.XML -> buffer.writeBytes(extra);
            case Tds.UDT -> {
                if (!inRpc) {
                    buffer.writeUShort((int) maxLength);
                }
                buffer.writeBytes(extra);
            }
            default -> {
                // Fixed-length types and DATEN carry nothing more.
            }
        }
    }

    /**
     * The type a text column is announced with once its values may be detokenized: a fixed-length
     * {@code char} / {@code nchar} becomes variable-length, and the column the largest a non
     * {@code max} column can be, so that a clear value longer than its token still fits. A
     * {@code max}, {@code text} or {@code ntext} column is kept.
     */
    public TypeInfo widenedForText() {
        if (lengthKind != LengthKind.USHORT || !isText()) {
            return this;
        }
        TypeInfo widened = new TypeInfo(type == Tds.BIGCHAR ? Tds.BIGVARCHAR : type == Tds.NCHAR ? Tds.NVARCHAR : type);
        widened.lengthKind = LengthKind.USHORT;
        widened.maxLength = Tds.MAX_SHORT_LENGTH;
        widened.collation = collation;
        return widened;
    }

    /** An {@code nvarchar(max)} with the given collation, for a rewritten statement parameter. */
    public static TypeInfo nvarcharMax(Collation collation) {
        TypeInfo info = new TypeInfo(Tds.NVARCHAR);
        info.lengthKind = LengthKind.PLP;
        info.maxLength = Tds.MAX_LENGTH;
        info.collation = collation == null ? new Collation(0x00D00409, 52) : collation;
        return info;
    }

    // -------------------------------------------------------------------------------------------------
    // Values
    // -------------------------------------------------------------------------------------------------

    /**
     * Read a value of this type.
     *
     * @param inRow whether the value belongs to a row: a {@code text} value is then preceded by its
     *              text pointer; in an RPC parameter it is a plain LONGLEN value
     */
    public Value readValue(TdsReader reader, boolean inRow) throws IOException {
        return switch (lengthKind) {
            case FIXED -> fixedLength == 0 ? Value.NULL : new Value(reader.readBytes(fixedLength));
            case BYTE -> {
                int length = reader.readByte();
                boolean legacy = type == Tds.CHAR || type == Tds.VARCHAR || type == Tds.BINARY || type == Tds.VARBINARY;
                if (legacy ? length == 0xFF : length == 0) {
                    yield Value.NULL;
                }
                yield new Value(reader.readBytes(length));
            }
            case USHORT -> {
                int length = reader.readUShort();
                yield length == Tds.USHORT_NULL ? Value.NULL : new Value(reader.readBytes(length));
            }
            case TEXT -> {
                if (!inRow) {
                    long length = reader.readUInt();
                    yield length == 0xFFFFFFFFL ? Value.NULL : new Value(reader.readBytes(Math.toIntExact(length)));
                }
                int pointerLength = reader.readByte();
                if (pointerLength == 0) {
                    yield Value.NULL;
                }
                byte[] pointer = reader.readBytes(pointerLength);
                byte[] timestamp = reader.readBytes(8);
                int length = Math.toIntExact(reader.readUInt());
                yield new Value(reader.readBytes(length), pointer, timestamp);
            }
            case VARIANT -> {
                int length = Math.toIntExact(reader.readUInt());
                yield length == 0 ? Value.NULL : new Value(reader.readBytes(length));
            }
            case PLP -> readPlp(reader);
        };
    }

    private static Value readPlp(TdsReader reader) throws IOException {
        long total = reader.readLong();
        if (total == Tds.PLP_NULL) {
            return Value.NULL;
        }
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream(
                total == Tds.PLP_UNKNOWN ? 256 : (int) Math.min(total, 1 << 20));
        while (true) {
            int chunk = Math.toIntExact(reader.readUInt());
            if (chunk == 0) {
                break;
            }
            data.write(reader.readBytes(chunk), 0, chunk);
        }
        return new Value(data.toByteArray());
    }

    /**
     * Write a value of this type.
     *
     * @param inRow whether the value belongs to a row, see {@link #readValue}
     */
    public void writeValue(TdsBuffer buffer, Value value, boolean inRow) {
        byte[] data = value.data();
        switch (lengthKind) {
            case FIXED -> {
                if (data != null) {
                    buffer.writeBytes(data);
                }
            }
            case BYTE -> {
                boolean legacy = type == Tds.CHAR || type == Tds.VARCHAR || type == Tds.BINARY || type == Tds.VARBINARY;
                if (data == null) {
                    buffer.writeByte(legacy ? 0xFF : 0);
                } else {
                    buffer.writeByte(data.length).writeBytes(data);
                }
            }
            case USHORT -> {
                if (data == null) {
                    buffer.writeUShort(Tds.USHORT_NULL);
                } else {
                    buffer.writeUShort(data.length).writeBytes(data);
                }
            }
            case TEXT -> {
                if (!inRow) {
                    buffer.writeInt(data == null ? -1 : data.length);
                    if (data != null) {
                        buffer.writeBytes(data);
                    }
                } else if (data == null) {
                    buffer.writeByte(0);
                } else {
                    buffer.writeByte(value.textPointer().length).writeBytes(value.textPointer())
                            .writeBytes(value.timestamp()).writeInt(data.length).writeBytes(data);
                }
            }
            case VARIANT -> {
                buffer.writeInt(data == null ? 0 : data.length);
                if (data != null) {
                    buffer.writeBytes(data);
                }
            }
            case PLP -> {
                if (data == null) {
                    buffer.writeLong(Tds.PLP_NULL);
                } else {
                    buffer.writeLong(data.length);
                    if (data.length > 0) {
                        buffer.writeInt(data.length).writeBytes(data);
                    }
                    buffer.writeInt(0);
                }
            }
        }
    }

    /**
     * A value: its bytes without their length prefix, {@code null} for NULL.
     *
     * @param textPointer the text pointer of a {@code text}, {@code ntext} or {@code image} value of a row
     * @param timestamp   its timestamp
     */
    public record Value(byte[] data, byte[] textPointer, byte[] timestamp) {

        public static final Value NULL = new Value(null);

        public Value(byte[] data) {
            this(data, null, null);
        }

        public boolean isNull() {
            return data == null;
        }

        /** The same value, with other bytes. */
        public Value with(byte[] other) {
            return new Value(other, textPointer, timestamp);
        }
    }

    @Override
    public String toString() {
        return String.format("TypeInfo[0x%02X %s max=%d]", type, lengthKind, maxLength);
    }
}
