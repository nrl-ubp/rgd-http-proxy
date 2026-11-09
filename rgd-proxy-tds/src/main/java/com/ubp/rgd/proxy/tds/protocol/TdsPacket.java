package com.ubp.rgd.proxy.tds.protocol;

/**
 * A TDS packet: its 8-byte header and its payload, see [MS-TDS] 2.2.3.
 *
 * @param type     the message type
 * @param status   the status bits (EOM, IGNORE, RESETCONNECTION...)
 * @param spid     the server process id
 * @param packetId the packet number, modulo 256
 * @param window   unused, 0
 * @param payload  the data following the header
 */
public record TdsPacket(int type, int status, int spid, int packetId, int window, byte[] payload) {

    public boolean isEndOfMessage() {
        return (status & Tds.STATUS_EOM) != 0;
    }

    /** @return the packet as sent on the wire, header included */
    public byte[] toBytes() {
        int length = Tds.HEADER_LENGTH + payload.length;
        byte[] bytes = new byte[length];
        bytes[0] = (byte) type;
        bytes[1] = (byte) status;
        bytes[2] = (byte) (length >>> 8);
        bytes[3] = (byte) length;
        bytes[4] = (byte) (spid >>> 8);
        bytes[5] = (byte) spid;
        bytes[6] = (byte) packetId;
        bytes[7] = (byte) window;
        System.arraycopy(payload, 0, bytes, Tds.HEADER_LENGTH, payload.length);
        return bytes;
    }
}
