package com.ubp.rgd.proxy.queue;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.jms.JMSException;
import jakarta.jms.Message;

import java.time.Duration;
import java.util.Optional;

/**
 * Listens on the input queue of a route consumer.
 * <p>
 * The messages are pulled, one receive per request of the downstream: a message is only taken from the
 * queue once the previous one has been transformed, sent and committed. The pace of the route is thus
 * set by the slowest of the queue managers and the transformer, and the transacted session never holds
 * more than the message being processed.
 */
@ApplicationScoped
public class QueueListener {

    /**
     * @param session     the sessions of the consumer
     * @param pollTimeout how long a receive waits for a message; a cancellation is noticed at the latest after it
     * @param onIdlePoll  called when a receive returned no message: the queue manager is reachable
     * @return the messages of the input queue, indefinitely; fails when the queue manager cannot be read
     */
    public Multi<Message> messages(RouteSession session, Duration pollTimeout, Runnable onIdlePoll) {
        return Multi.createBy().repeating()
                .supplier(() -> poll(session, pollTimeout))
                .indefinitely()
                .onItem().invoke(polled -> {
                    if (polled.isEmpty()) {
                        onIdlePoll.run();
                    }
                })
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    private static Optional<Message> poll(RouteSession session, Duration pollTimeout) {
        try {
            return Optional.ofNullable(session.receive(pollTimeout));
        } catch (JMSException e) {
            throw new UncheckedJmsException(e);
        }
    }
}
