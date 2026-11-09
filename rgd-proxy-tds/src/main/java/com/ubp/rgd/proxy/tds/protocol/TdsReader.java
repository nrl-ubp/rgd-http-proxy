package com.ubp.rgd.proxy.tds.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads the little-endian values of a TDS stream: a whole message held in memory, or the payload of a
 * response streamed packet by packet.
 * <p>
 * Every byte read can be captured, so that a part the gateway leaves untouched is copied to the other
 * side exactly as it was received.
 */
public final class TdsReader {

    private final InputStream input;
    private Recording capture;

    public TdsReader(InputStream input) {
        this.input = input;
    }

    public static TdsReader of(byte[] bytes) {
        return new TdsReader(new java.io.ByteArrayInputStream(bytes));
    }

    /** Start recording every byte read from now on, the previous recording being discarded. */
    public void startCapture() {
        capture = new Recording();
    }

    /** @return the number of bytes recorded since {@link #startCapture()} */
    public int capturePosition() {
        return capture == null ? 0 : capture.size();
    }

    /** @return the bytes recorded between two positions */
    public byte[] captured(int from, int to) {
        return java.util.Arrays.copyOfRange(capture.buffer(), from, to);
    }

    /** Stop recording. @return the bytes read since {@link #startCapture()} */
    public byte[] stopCapture() {
        byte[] bytes = capture == null ? new byte[0] : capture.toByteArray();
        capture = null;
        return bytes;
    }

    /** A recording whose buffer can be sliced without copying it whole. */
    private static final class Recording extends ByteArrayOutputStream {
        Recording() {
            super(64);
        }

        byte[] buffer() {
            return buf;
        }
    }

    /** @return the next byte, or -1 at the end of the message, which is consumed */
    public int readByteOrEnd() throws IOException {
        int value = input.read();
        if (value >= 0 && capture != null) {
            capture.write(value);
        }
        return value;
    }

    public int readByte() throws IOException {
        int value = readByteOrEnd();
        if (value < 0) {
            throw new EOFException("Truncated TDS message");
        }
        return value;
    }

    public int readUShort() throws IOException {
        return readByte() | (readByte() << 8);
    }

    public int readInt() throws IOException {
        return readByte() | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
    }

    public long readUInt() throws IOException {
        return readInt() & 0xFFFFFFFFL;
    }

    public long readLong() throws IOException {
        return (readUInt()) | (readUInt() << 32);
    }

    public byte[] readBytes(int length) throws IOException {
        if (length < 0 || input instanceof java.io.ByteArrayInputStream memory && length > memory.available()) {
            // A message held in memory announces no more than it holds: a length claimed by a peer
            // must not make the gateway allocate it.
            throw new EOFException("Truncated TDS message");
        }
        byte[] bytes = new byte[length];
        int read = 0;
        while (read < length) {
            int count = input.read(bytes, read, length - read);
            if (count < 0) {
                throw new EOFException("Truncated TDS message");
            }
            read += count;
        }
        if (capture != null) {
            capture.write(bytes, 0, length);
        }
        return bytes;
    }

    /** Skip bytes, which are still captured when a recording is going on. */
    public void skip(long length) throws IOException {
        long left = length;
        while (left > 0) {
            int chunk = (int) Math.min(left, 8192);
            readBytes(chunk);
            left -= chunk;
        }
    }

    /** A string of {@code chars} UCS-2 characters. */
    public String readUcs2(int chars) throws IOException {
        return new String(readBytes(chars * 2), StandardCharsets.UTF_16LE);
    }

    /** B_VARCHAR: a BYTE length in characters, then UCS-2. */
    public String readBVarchar() throws IOException {
        return readUcs2(readByte());
    }

    /** US_VARCHAR: a USHORT length in characters, then UCS-2. */
    public String readUsVarchar() throws IOException {
        return readUcs2(readUShort());
    }

    /** @return the rest of the message, captured as well */
    public byte[] readRemaining() throws IOException {
        ByteArrayOutputStream rest = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            rest.write(buffer, 0, count);
        }
        byte[] bytes = rest.toByteArray();
        if (capture != null) {
            capture.write(bytes, 0, bytes.length);
        }
        return bytes;
    }
}
