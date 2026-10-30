package com.ubp.rgd.proxy.queue;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * The transacted sessions of one consumer of a route: one per queue manager the route uses.
 * <p>
 * When the output (or error) queue is on the queue manager of the input queue, they share the input
 * session, so that receiving a message and sending its transformation are a single transaction. On
 * other queue managers, the sessions are committed before the input session: a failure between the
 * two commits leaves the message on the input queue, to be transformed again (at-least-once).
 * <p>
 * Like any Jakarta Messaging session, it must only be used by one thread, see {@code QueueProxyService}.
 */
public final class RouteSession implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RouteSession.class);

    private final Session input;
    private final MessageConsumer consumer;
    private final Session output;
    private final MessageProducer outputProducer;
    private final Session error;
    private final MessageProducer errorProducer;
    /** The distinct sessions, the input one last. */
    private final List<Session> commitOrder;

    RouteSession(Session input, MessageConsumer consumer, Session output, MessageProducer outputProducer,
                 Session error, MessageProducer errorProducer, List<Session> commitOrder) {
        this.input = input;
        this.consumer = consumer;
        this.output = output;
        this.outputProducer = outputProducer;
        this.error = error;
        this.errorProducer = errorProducer;
        this.commitOrder = commitOrder;
    }

    /**
     * @param timeout how long to wait for a message
     * @return the next message of the input queue, {@code null} when none arrived in time
     */
    public Message receive(Duration timeout) throws JMSException {
        return consumer.receive(Math.max(1, timeout.toMillis()));
    }

    /** Commits the other queue managers first, then the input one, which removes the message. */
    public void commit() throws JMSException {
        for (Session session : commitOrder) {
            session.commit();
        }
    }

    /** Puts the received message back on the input queue and discards what was sent. */
    public void rollback() {
        for (Session session : commitOrder) {
            try {
                session.rollback();
            } catch (JMSException | RuntimeException e) {
                LOG.debug("Rollback failed: {}", e.getMessage());
            }
        }
    }

    /** Rolls back what is not committed and closes the sessions. */
    @Override
    public void close() {
        rollback();
        for (Session session : commitOrder) {
            try {
                session.close();
            } catch (JMSException | RuntimeException e) {
                LOG.debug("Closing a session failed: {}", e.getMessage());
            }
        }
    }

    public boolean hasErrorQueue() {
        return errorProducer != null;
    }

    Session outputSession() {
        return output;
    }

    MessageProducer outputProducer() {
        return outputProducer;
    }

    Session errorSession() {
        return error;
    }

    MessageProducer errorProducer() {
        return errorProducer;
    }

    Session inputSession() {
        return input;
    }
}
