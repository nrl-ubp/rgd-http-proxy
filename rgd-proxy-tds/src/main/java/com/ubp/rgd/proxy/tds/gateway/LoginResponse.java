package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TdsBuffer;
import com.ubp.rgd.proxy.tds.protocol.TdsReader;

import java.io.IOException;

/**
 * The response of the database server to a login: the tokens the gateway relays to the client, and
 * what it needs to know of them.
 */
final class LoginResponse {

    /** The tokens to relay, without the SSPI ones, which belong to the gateway's own context. */
    final TdsBuffer relayed = new TdsBuffer(512);
    boolean loginAck;
    boolean routing;
    int packetSize;
    byte[] sspi;

    static LoginResponse parse(byte[] payload) throws IOException {
        LoginResponse response = new LoginResponse();
        TdsReader reader = TdsReader.of(payload);
        while (true) {
            reader.startCapture();
            int token = reader.readByteOrEnd();
            if (token < 0) {
                break;
            }
            switch (token) {
                case Tds.TOKEN_SSPI -> {
                    response.sspi = reader.readBytes(reader.readUShort());
                    reader.stopCapture();
                    continue;
                }
                case Tds.TOKEN_ENVCHANGE -> {
                    byte[] body = reader.readBytes(reader.readUShort());
                    int type = body[0] & 0xFF;
                    if (type == Tds.ENV_ROUTING) {
                        response.routing = true;
                    } else if (type == Tds.ENV_PACKET_SIZE) {
                        TdsReader value = TdsReader.of(body);
                        value.readByte();
                        response.packetSize = Integer.parseInt(value.readBVarchar().trim());
                    }
                }
                case Tds.TOKEN_LOGINACK -> {
                    response.loginAck = true;
                    reader.skip(reader.readUShort());
                }
                default -> TokenLengths.skipSimpleToken(token, reader);
            }
            response.relayed.writeBytes(reader.stopCapture());
        }
        return response;
    }
}
