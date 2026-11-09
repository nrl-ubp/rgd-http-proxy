package com.ubp.rgd.proxy.tds.kerberos;

/** An integrated (Kerberos) login the gateway cannot carry out; the message is sent to the client. */
public class KerberosLoginException extends Exception {

    public KerberosLoginException(String message) {
        super(message);
    }

    public KerberosLoginException(String message, Throwable cause) {
        super(message, cause);
    }
}
