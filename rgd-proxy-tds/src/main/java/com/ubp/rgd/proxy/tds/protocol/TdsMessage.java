package com.ubp.rgd.proxy.tds.protocol;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * A whole TDS message read in memory: the packets it was received in, kept to relay it unchanged, and
 * their payloads joined.
 *
 * @param type    the message type
 * @param status  the status bits of its first packet (RESETCONNECTION...)
 * @param packets the packets as received
 */
public record TdsMessage(int type, int status, List<TdsPacket> packets) {

    /** @return the payloads of all the packets, joined */
    public byte[] payload() {
        if (packets.size() == 1) {
            return packets.get(0).payload();
        }
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (TdsPacket packet : packets) {
            joined.write(packet.payload(), 0, packet.payload().length);
        }
        return joined.toByteArray();
    }

    /** @return whether the client asked the server to ignore the message, see [MS-TDS] 2.2.3.1.2 */
    public boolean isIgnored() {
        return (packets.get(packets.size() - 1).status() & Tds.STATUS_IGNORE) != 0;
    }
}
