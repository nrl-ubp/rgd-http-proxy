package com.ubp.rgd.proxy.transform;

/**
 * A transformation could not be performed, whatever the transformer implementation.
 */
public class TransformException extends Exception {

    public TransformException(String message) {
        super(message);
    }

    public TransformException(String message, Throwable cause) {
        super(message, cause);
    }

    public TransformException(Exception e) {
        super(e);
    }
}
