package com.ubp.rgd.proxy.tds.protocol;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes a message packet by packet: a packet leaves as soon as it is full, and closing the stream
 * sends the last one, flagged end of message. The write lock of the channel is held from the first
 * packet sent to the last one.
 */
public final class MessageOutputStream extends OutputStream {

    private final TdsChannel channel;
    private final int type;
    private int status;
    private final byte[] buffer;
    private int count;
    private boolean locked;
    private boolean closed;

    MessageOutputStream(TdsChannel channel, int type, int firstStatus) {
        this.channel = channel;
        this.type = type;
        this.status = firstStatus & ~Tds.STATUS_EOM;
        this.buffer = new byte[channel.packetSize() - Tds.HEADER_LENGTH];
    }

    @Override
    public void write(int value) throws IOException {
        if (count == buffer.length) {
            sendFrame(false);
        }
        buffer[count++] = (byte) value;
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        int written = 0;
        while (written < length) {
            if (count == buffer.length) {
                sendFrame(false);
            }
            int chunk = Math.min(length - written, buffer.length - count);
            System.arraycopy(data, offset + written, buffer, count, chunk);
            count += chunk;
            written += chunk;
        }
    }

    private void sendFrame(boolean last) throws IOException {
        if (!locked) {
            lock();
        }
        channel.writeFrame(type, status | (last ? Tds.STATUS_EOM : 0), buffer, count);
        // RESETCONNECTION only belongs to the first packet of the message.
        status = 0;
        count = 0;
    }

    private void lock() {
        channel.messageLock().lock();
        locked = true;
    }

    /** Send what is buffered and end the message. */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            sendFrame(true);
        } finally {
            if (locked) {
                channel.messageLock().unlock();
            }
        }
    }

    /** Release the channel without ending the message, when the connection is going to be closed. */
    public void abandon() {
        if (!closed) {
            closed = true;
            if (locked) {
                channel.messageLock().unlock();
            }
        }
    }
}
