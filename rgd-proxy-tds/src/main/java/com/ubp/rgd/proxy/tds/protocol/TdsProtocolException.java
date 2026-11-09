package com.ubp.rgd.proxy.tds.protocol;

import java.io.IOException;

/** A TDS stream the gateway cannot understand: the session is closed rather than corrupted. */
public class TdsProtocolException extends IOException {

    public TdsProtocolException(String message) {
        super(message);
    }
}
