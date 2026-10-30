package com.ubp.rgd.proxy.queue;

import com.jayway.jsonpath.InvalidJsonException;
import com.ubp.rgd.proxy.transform.TransformException;
import jakarta.jms.JMSException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureClassifierTest {

    /** Named like the MongoDB driver exceptions, which are runtime exceptions. */
    static class MongoTimeoutException extends RuntimeException {
    }

    @Test
    @DisplayName("Unreachable infrastructure is transient, wherever it is in the cause chain")
    void infrastructureIsTransient() {
        assertTrue(FailureClassifier.isTransient(new TransformException(new ConnectException("engine down"))));
        assertTrue(FailureClassifier.isTransient(new UncheckedJmsException(new JMSException("2009"))));
        assertTrue(FailureClassifier.isTransient(new TransformException(new RuntimeException(new MongoTimeoutException()))));
        assertTrue(FailureClassifier.isTransient(new TransformException(new WebApplicationException(503))));
    }

    @Test
    @DisplayName("Failures caused by the message itself are permanent")
    void dataIsPermanent() {
        assertTrue(FailureClassifier.isPermanent(new InvalidMessageException("not JSON")));
        assertTrue(FailureClassifier.isPermanent(new TransformException(new InvalidJsonException("broken"))));
        assertTrue(FailureClassifier.isPermanent(new TransformException(new WebApplicationException(400))));
        assertFalse(FailureClassifier.isTransient(new TransformException("rejected by the engine")));
    }
}
