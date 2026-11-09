package com.ubp.rgd.proxy.tds.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The payload of a message, read packet after packet as it arrives: a response is processed while
 * the server is still producing it.
 */
public final class MessageInputStream extends InputStream {

    private final TdsChannel channel;
    private TdsPacket packet;
    private int position;
    private final int spid;

    MessageInputStream(TdsChannel channel, TdsPacket first) {
        this.channel = channel;
        this.packet = first;
        this.spid = first.spid();
    }

    /** @return the SPID of the first packet, the server process id of a response */
    public int spid() {
        return spid;
    }

    private boolean ensure() throws IOException {
        while (position >= packet.payload().length) {
            if (packet.isEndOfMessage()) {
                return false;
            }
            TdsPacket next = channel.readPacket();
            if (next == null) {
                throw new EOFException(channel.name() + ": connection closed inside a TDS message");
            }
            packet = next;
            position = 0;
        }
        return true;
    }

    @Override
    public int read() throws IOException {
        if (!ensure()) {
            return -1;
        }
        return packet.payload()[position++] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (!ensure()) {
            return -1;
        }
        int count = Math.min(length, packet.payload().length - position);
        System.arraycopy(packet.payload(), position, buffer, offset, count);
        position += count;
        return count;
    }
}
