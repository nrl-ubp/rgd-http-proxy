package com.ubp.rgd.proxy.transform;

public class RPSTransformException extends Exception {
    public RPSTransformException(String message) {
        super(message);
    }

    public RPSTransformException(Exception e) {
        super(e);
    }
}
