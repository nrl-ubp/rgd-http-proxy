package com.ubp.rgd.proxy.tds.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds little-endian TDS data in memory: a message, a token, or a re-encoded part of either.
 */
public final class TdsBuffer {

    private final ByteArrayOutputStream bytes;

    public TdsBuffer() {
        this(256);
    }

    public TdsBuffer(int capacity) {
        bytes = new ByteArrayOutputStream(capacity);
    }

    public TdsBuffer writeByte(int value) {
        bytes.write(value & 0xFF);
        return this;
    }

    public TdsBuffer writeUShort(int value) {
        bytes.write(value & 0xFF);
        bytes.write((value >>> 8) & 0xFF);
        return this;
    }

    public TdsBuffer writeUShortBigEndian(int value) {
        bytes.write((value >>> 8) & 0xFF);
        bytes.write(value & 0xFF);
        return this;
    }

    public TdsBuffer writeInt(int value) {
        writeUShort(value);
        writeUShort(value >>> 16);
        return this;
    }

    public TdsBuffer writeLong(long value) {
        writeInt((int) value);
        writeInt((int) (value >>> 32));
        return this;
    }

    public TdsBuffer writeBytes(byte[] value) {
        bytes.write(value, 0, value.length);
        return this;
    }

    public TdsBuffer writeBytes(byte[] value, int offset, int length) {
        bytes.write(value, offset, length);
        return this;
    }

    public TdsBuffer writeUcs2(String value) {
        return writeBytes(value.getBytes(StandardCharsets.UTF_16LE));
    }

    /** B_VARCHAR: a BYTE length in characters, then UCS-2. */
    public TdsBuffer writeBVarchar(String value) {
        String text = value == null ? "" : value;
        writeByte(text.length());
        return writeUcs2(text);
    }

    /** US_VARCHAR: a USHORT length in characters, then UCS-2. */
    public TdsBuffer writeUsVarchar(String value) {
        String text = value == null ? "" : value;
        writeUShort(text.length());
        return writeUcs2(text);
    }

    public int size() {
        return bytes.size();
    }

    public byte[] toByteArray() {
        return bytes.toByteArray();
    }
}
