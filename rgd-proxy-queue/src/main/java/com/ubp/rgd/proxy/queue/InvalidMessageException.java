package com.ubp.rgd.proxy.queue;

/**
 * A message the proxy cannot transform whatever the number of attempts: neither a text nor a bytes
 * message, or a payload that is not a JSON object or array. Always permanent, see {@link FailureClassifier}:
 * it deliberately carries no cause, so that a parser {@code IOException} is not taken for a transient failure.
 */
public class InvalidMessageException extends Exception {

    public InvalidMessageException(String message) {
        super(message);
    }
}
