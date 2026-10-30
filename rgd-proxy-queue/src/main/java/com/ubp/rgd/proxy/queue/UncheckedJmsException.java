package com.ubp.rgd.proxy.queue;

import jakarta.jms.JMSException;

/**
 * A Jakarta Messaging failure crossing a functional interface. Always transient, see {@link FailureClassifier}.
 */
public class UncheckedJmsException extends RuntimeException {

    public UncheckedJmsException(JMSException cause) {
        super(cause.getMessage(), cause);
    }
}
