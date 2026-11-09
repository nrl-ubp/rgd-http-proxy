package com.ubp.rgd.proxy.tds.protocol;

/** Builds the tokens the gateway synthesises itself. */
public final class Tokens {

    /** The server name the gateway signs its own messages with. */
    public static final String SERVER_NAME = "rgd-tds-gateway";

    private Tokens() {
    }

    /**
     * An ERROR (or INFO) token, see [MS-TDS] 2.2.7.10.
     *
     * @param number   the message number
     * @param state    the state
     * @param severity the class: 11 to 16 for user errors, 20 and above for fatal ones
     */
    public static void error(TdsBuffer buffer, int number, int state, int severity, String message) {
        message(buffer, Tds.TOKEN_ERROR, number, state, severity, message);
    }

    public static void info(TdsBuffer buffer, int number, int state, int severity, String message) {
        message(buffer, Tds.TOKEN_INFO, number, state, severity, message);
    }

    private static void message(TdsBuffer buffer, int token, int number, int state, int severity, String message) {
        String text = message.length() > 4000 ? message.substring(0, 4000) : message;
        TdsBuffer body = new TdsBuffer(64 + text.length() * 2);
        body.writeInt(number).writeByte(state).writeByte(severity).writeUsVarchar(text)
                .writeBVarchar(SERVER_NAME).writeBVarchar("").writeInt(1);
        byte[] bytes = body.toByteArray();
        buffer.writeByte(token).writeUShort(bytes.length).writeBytes(bytes);
    }

    /** A DONE token, see [MS-TDS] 2.2.7.6. */
    public static void done(TdsBuffer buffer, int status, long rowCount) {
        buffer.writeByte(Tds.TOKEN_DONE).writeUShort(status).writeUShort(0).writeLong(rowCount);
    }

    /** An SSPI token carrying a security blob for the client. */
    public static void sspi(TdsBuffer buffer, byte[] blob) {
        buffer.writeByte(Tds.TOKEN_SSPI).writeUShort(blob.length).writeBytes(blob);
    }

    /** A whole response: an error and the DONE flagging it. */
    public static byte[] errorResponse(int number, int severity, String message) {
        TdsBuffer buffer = new TdsBuffer(256);
        error(buffer, number, 1, severity, message);
        done(buffer, Tds.DONE_ERROR, 0);
        return buffer.toByteArray();
    }
}
