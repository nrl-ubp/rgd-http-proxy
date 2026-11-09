package com.ubp.rgd.proxy.tds.transport;

import com.ubp.rgd.proxy.tds.protocol.Tds;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The streams of a TDS 7.x TLS handshake, see [MS-TDS] 2.2.6.5: the TLS records travel as the payload
 * of PRELOGIN packets.
 * <p>
 * A flight of handshake records must leave as a single message: the peer reads a whole message, then
 * expects to answer. The flushes of the TLS layer are therefore ignored, and what it wrote leaves when
 * it is about to read the answer, or when the handshake is over.
 */
final class PreLoginWrappedStreams {

    private PreLoginWrappedStreams() {
    }

    /**
     * Reads the payload of the packets, whatever their type, ignoring their boundaries. The records
     * written so far leave before the next packet is read.
     */
    static final class Input extends InputStream {
        private final InputStream raw;
        private final Output output;
        private int remaining;

        Input(InputStream raw, Output output) {
            this.raw = raw;
            this.output = output;
        }

        private boolean ensure() throws IOException {
            while (remaining == 0) {
                output.send();
                byte[] header = raw.readNBytes(Tds.HEADER_LENGTH);
                if (header.length == 0) {
                    return false;
                }
                if (header.length < Tds.HEADER_LENGTH) {
                    throw new EOFException("Connection closed inside a TLS handshake packet");
                }
                remaining = (((header[2] & 0xFF) << 8) | (header[3] & 0xFF)) - Tds.HEADER_LENGTH;
                if (remaining < 0) {
                    throw new IOException("Invalid TLS handshake packet length");
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!ensure()) {
                return -1;
            }
            int value = raw.read();
            if (value < 0) {
                throw new EOFException("Connection closed inside a TLS handshake packet");
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (!ensure()) {
                return -1;
            }
            int count = raw.read(buffer, offset, Math.min(length, remaining));
            if (count < 0) {
                throw new EOFException("Connection closed inside a TLS handshake packet");
            }
            remaining -= count;
            return count;
        }
    }

    /** Buffers the records of a flight, sent as one PRELOGIN message by {@link #send()}. */
    static final class Output extends OutputStream {
        private final OutputStream raw;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final int packetSize;

        Output(OutputStream raw, int packetSize) {
            this.raw = raw;
            this.packetSize = packetSize;
        }

        @Override
        public void write(int value) {
            pending.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            pending.write(buffer, offset, length);
        }

        @Override
        public void flush() {
            // Ignored: see the class comment.
        }

        /** Send the records written so far as one message. */
        void send() throws IOException {
            if (pending.size() == 0) {
                return;
            }
            byte[] data = pending.toByteArray();
            pending.reset();
            int chunkMax = packetSize - Tds.HEADER_LENGTH;
            int position = 0;
            int packetId = 1;
            while (position < data.length) {
                int chunk = Math.min(chunkMax, data.length - position);
                boolean last = position + chunk == data.length;
                int length = chunk + Tds.HEADER_LENGTH;
                raw.write(new byte[]{(byte) Tds.PKT_PRELOGIN, (byte) (last ? Tds.STATUS_EOM : 0),
                        (byte) (length >>> 8), (byte) length, 0, 0, (byte) packetId++, 0});
                raw.write(data, position, chunk);
                position += chunk;
            }
            raw.flush();
        }
    }
}
