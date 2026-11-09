package com.ubp.rgd.proxy.tds.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A PRELOGIN message, see [MS-TDS] 2.2.6.5: option tokens pointing to their data, offsets being
 * big-endian and relative to the start of the payload.
 */
public final class PreLogin {

    private final Map<Integer, byte[]> options = new LinkedHashMap<>();

    public static PreLogin parse(byte[] payload) throws TdsProtocolException {
        PreLogin preLogin = new PreLogin();
        int position = 0;
        while (true) {
            if (position >= payload.length) {
                throw new TdsProtocolException("Truncated PRELOGIN message");
            }
            int token = payload[position] & 0xFF;
            if (token == Tds.PL_TERMINATOR) {
                break;
            }
            if (position + 5 > payload.length) {
                throw new TdsProtocolException("Truncated PRELOGIN option");
            }
            int offset = ((payload[position + 1] & 0xFF) << 8) | (payload[position + 2] & 0xFF);
            int length = ((payload[position + 3] & 0xFF) << 8) | (payload[position + 4] & 0xFF);
            if (offset + length > payload.length) {
                throw new TdsProtocolException("PRELOGIN option " + token + " out of the message");
            }
            byte[] data = new byte[length];
            System.arraycopy(payload, offset, data, 0, length);
            preLogin.options.put(token, data);
            position += 5;
        }
        return preLogin;
    }

    public PreLogin set(int option, byte[] data) {
        options.put(option, data);
        return this;
    }

    public byte[] get(int option) {
        return options.get(option);
    }

    public boolean has(int option) {
        return options.containsKey(option);
    }

    /** @return the ENCRYPTION option, {@link Tds#ENCRYPT_NOT_SUP} when absent */
    public int encryption() {
        byte[] data = options.get(Tds.PL_ENCRYPTION);
        return data == null || data.length == 0 ? Tds.ENCRYPT_NOT_SUP : data[0] & 0xFF;
    }

    public PreLogin encryption(int value) {
        return set(Tds.PL_ENCRYPTION, new byte[]{(byte) value});
    }

    /** @return whether the client asks for federated authentication */
    public boolean fedAuthRequired() {
        byte[] data = options.get(Tds.PL_FEDAUTHREQUIRED);
        return data != null && data.length > 0 && data[0] != 0;
    }

    /** @return whether the client asks for MARS */
    public boolean mars() {
        byte[] data = options.get(Tds.PL_MARS);
        return data != null && data.length > 0 && data[0] != 0;
    }

    public byte[] encode() {
        int headerLength = options.size() * 5 + 1;
        TdsBuffer header = new TdsBuffer(headerLength);
        TdsBuffer data = new TdsBuffer(64);
        for (Map.Entry<Integer, byte[]> option : options.entrySet()) {
            header.writeByte(option.getKey())
                    .writeUShortBigEndian(headerLength + data.size())
                    .writeUShortBigEndian(option.getValue().length);
            data.writeBytes(option.getValue());
        }
        header.writeByte(Tds.PL_TERMINATOR);
        return header.writeBytes(data.toByteArray()).toByteArray();
    }
}
