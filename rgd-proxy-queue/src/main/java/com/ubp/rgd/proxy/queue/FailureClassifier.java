package com.ubp.rgd.proxy.queue;

import jakarta.jms.JMSException;
import jakarta.jms.JMSRuntimeException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import java.io.IOException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Tells the failures that come from the message itself from those of the infrastructure.
 * <p>
 * A <b>transient</b> failure (a queue manager, the transformation engine or a database that cannot be
 * reached) says nothing about the message: it is rolled back and transformed again once the route has
 * reconnected. A <b>permanent</b> one (invalid JSON, a value the transformer rejects, an unsupported
 * message type) would fail every time: the message goes to the error queue.
 * <p>
 * A failure is transient when its cause chain holds an I/O error, a timeout, a messaging error, a
 * transient SQL error, a REST client failure or 5xx/429 response, or an exception named after a
 * connection problem ({@code *Timeout*}, {@code *Socket*}, {@code *Connect*}, {@code *Unavailable*},
 * which covers the MongoDB driver). Anything else is permanent.
 */
public final class FailureClassifier {

    private static final Pattern TRANSIENT_NAME = Pattern.compile("Timeout|Socket|Connect|Unavailable");
    private static final int MAX_DEPTH = 20;

    private FailureClassifier() {
    }

    public static boolean isTransient(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof IOException || t instanceof TimeoutException
                    || t instanceof JMSException || t instanceof JMSRuntimeException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException
                    || t instanceof ProcessingException
                    || TRANSIENT_NAME.matcher(t.getClass().getSimpleName()).find()) {
                return true;
            }
            if (t instanceof WebApplicationException web && web.getResponse() != null) {
                int status = web.getResponse().getStatus();
                if (status >= 500 || status == 429) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isPermanent(Throwable failure) {
        return !isTransient(failure);
    }

    /** @return the deepest cause, which usually says best what went wrong */
    static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < MAX_DEPTH) {
            root = root.getCause();
        }
        return root;
    }
}
