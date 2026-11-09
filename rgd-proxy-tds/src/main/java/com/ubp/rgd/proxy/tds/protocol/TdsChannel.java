package com.ubp.rgd.proxy.tds.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One leg of a TDS connection: reads and writes packets on the streams of a socket. The streams can
 * be switched, when TLS starts or stops on the leg.
 * <p>
 * Reads are done by a single thread. Writes are serialised: {@link #writePackets} and the
 * {@link MessageOutputStream} of a whole message hold the message lock, so that a message the gateway
 * synthesises never interleaves with a message being relayed.
 */
public final class TdsChannel {

    private final String name;
    private final ReentrantLock messageLock = new ReentrantLock();
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile int packetSize = Tds.DEFAULT_PACKET_SIZE;
    private volatile int spid;
    private int nextPacketId = 1;

    public TdsChannel(String name, InputStream input, OutputStream output) {
        this.name = name;
        this.input = input;
        this.output = output;
    }

    public String name() {
        return name;
    }

    public void setStreams(InputStream input, OutputStream output) {
        this.input = input;
        this.output = output;
    }

    public InputStream input() {
        return input;
    }

    public OutputStream output() {
        return output;
    }

    public int packetSize() {
        return packetSize;
    }

    public void setPacketSize(int packetSize) {
        this.packetSize = Math.max(512, Math.min(packetSize, Tds.MAX_PACKET_SIZE));
    }

    /** @param spid the SPID written in the header of the packets sent on this leg */
    public void setSpid(int spid) {
        this.spid = spid;
    }

    /** The lock serialising the messages written on this leg. */
    public ReentrantLock messageLock() {
        return messageLock;
    }

    /**
     * Read the next packet.
     *
     * @return the packet, or {@code null} when the peer closed the connection between two packets
     * @throws IOException on a read error or when the connection is closed inside a packet
     */
    public TdsPacket readPacket() throws IOException {
        InputStream in = input;
        byte[] header = new byte[Tds.HEADER_LENGTH];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        header[0] = (byte) first;
        readFully(in, header, 1, Tds.HEADER_LENGTH - 1);
        int length = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (length < Tds.HEADER_LENGTH) {
            throw new TdsProtocolException(name + ": invalid TDS packet length " + length);
        }
        byte[] payload = new byte[length - Tds.HEADER_LENGTH];
        readFully(in, payload, 0, payload.length);
        return new TdsPacket(header[0] & 0xFF, header[1] & 0xFF,
                ((header[4] & 0xFF) << 8) | (header[5] & 0xFF), header[6] & 0xFF, header[7] & 0xFF, payload);
    }

    /**
     * Read a whole message, starting with a packet already read.
     *
     * @param first the first packet of the message
     */
    public TdsMessage readMessage(TdsPacket first) throws IOException {
        List<TdsPacket> packets = new ArrayList<>();
        packets.add(first);
        TdsPacket packet = first;
        while (!packet.isEndOfMessage()) {
            packet = readPacket();
            if (packet == null) {
                throw new EOFException(name + ": connection closed inside a TDS message");
            }
            packets.add(packet);
        }
        return new TdsMessage(first.type(), first.status(), packets);
    }

    /** @return the next whole message, or {@code null} when the peer closed the connection */
    public TdsMessage readMessage() throws IOException {
        TdsPacket first = readPacket();
        return first == null ? null : readMessage(first);
    }

    /** A stream over the payload of a message, packet after packet, starting with a packet already read. */
    public MessageInputStream messageInput(TdsPacket first) {
        return new MessageInputStream(this, first);
    }

    /** Write packets as they are. */
    public void writePackets(List<TdsPacket> packets) throws IOException {
        messageLock.lock();
        try {
            OutputStream out = output;
            for (TdsPacket packet : packets) {
                out.write(packet.toBytes());
            }
            out.flush();
        } finally {
            messageLock.unlock();
        }
    }

    /** Write a packet as it is. */
    public void writePacket(TdsPacket packet) throws IOException {
        writePackets(List.of(packet));
    }

    /** Write a message, split into packets of the negotiated size. */
    public void writeMessage(int type, byte[] payload) throws IOException {
        writeMessage(type, 0, payload);
    }

    /**
     * Write a message, split into packets of the negotiated size.
     *
     * @param firstStatus status bits of the first packet, such as RESETCONNECTION
     */
    public void writeMessage(int type, int firstStatus, byte[] payload) throws IOException {
        try (MessageOutputStream out = openMessage(type, firstStatus)) {
            out.write(payload);
        }
    }

    /**
     * Start a message streamed packet by packet. The message lock is held from its first packet
     * until the stream is closed, which ends the message.
     */
    public MessageOutputStream openMessage(int type, int firstStatus) {
        return new MessageOutputStream(this, type, firstStatus);
    }

    void writeFrame(int type, int status, byte[] data, int length) throws IOException {
        TdsPacket packet = new TdsPacket(type, status, spid, nextPacketId(), 0,
                length == data.length ? data : java.util.Arrays.copyOf(data, length));
        OutputStream out = output;
        out.write(packet.toBytes());
        if (packet.isEndOfMessage()) {
            out.flush();
        }
    }

    private int nextPacketId() {
        int id = nextPacketId;
        nextPacketId = (nextPacketId + 1) & 0xFF;
        return id;
    }

    private static void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = in.read(buffer, offset + read, length - read);
            if (count < 0) {
                throw new EOFException("Connection closed inside a TDS packet");
            }
            read += count;
        }
    }
}
