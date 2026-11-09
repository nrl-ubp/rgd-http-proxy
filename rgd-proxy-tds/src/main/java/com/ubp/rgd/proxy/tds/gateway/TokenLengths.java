package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TdsProtocolException;
import com.ubp.rgd.proxy.tds.protocol.TdsReader;

import java.io.IOException;

/** Skips the tokens whose length can be found without knowing the result set they belong to. */
final class TokenLengths {

    private TokenLengths() {
    }

    /**
     * Skip the body of a token whose type was just read.
     *
     * @throws TdsProtocolException for a token the gateway does not support, or does not know
     */
    static void skipSimpleToken(int token, TdsReader reader) throws IOException {
        switch (token) {
            case Tds.TOKEN_DONE, Tds.TOKEN_DONEPROC, Tds.TOKEN_DONEINPROC -> reader.skip(12);
            case Tds.TOKEN_RETURNSTATUS -> reader.skip(4);
            case Tds.TOKEN_ERROR, Tds.TOKEN_INFO, Tds.TOKEN_LOGINACK, Tds.TOKEN_ENVCHANGE, Tds.TOKEN_SSPI,
                 Tds.TOKEN_ORDER, Tds.TOKEN_TABNAME, Tds.TOKEN_COLINFO -> reader.skip(reader.readUShort());
            case Tds.TOKEN_SESSIONSTATE, Tds.TOKEN_FEDAUTHINFO -> reader.skip(reader.readUInt());
            case Tds.TOKEN_FEATUREEXTACK -> {
                while (true) {
                    int feature = reader.readByte();
                    if (feature == Tds.FEATURE_TERMINATOR) {
                        break;
                    }
                    reader.skip(reader.readUInt());
                }
            }
            case Tds.TOKEN_ALTMETADATA, Tds.TOKEN_ALTROW ->
                    throw new TdsProtocolException("COMPUTE BY results (ALTMETADATA / ALTROW) are not supported");
            default -> throw new TdsProtocolException(String.format("Unknown TDS token 0x%02X", token));
        }
    }
}
