package com.ubp.rgd.proxy.queue;

import com.ubp.rgd.proxy.queue.config.QueueConfiguration;
import com.ubp.rgd.proxy.queue.config.QueueEndpointConfig;
import com.ubp.rgd.proxy.queue.config.QueueManagerKey;
import com.ubp.rgd.proxy.queue.config.QueueRouteConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The connections to the queue managers, one per queue manager whatever the number of routes and
 * consumers using it. Each consumer opens its own sessions on them, see {@link RouteSession}.
 * <p>
 * A connection reported broken, by its exception listener or because no session can be opened on it
 * any more, is dropped: the consumers using it fail, roll back, and reconnect through a new one.
 */
@ApplicationScoped
public class QueueConnections {

    private static final Logger LOG = LoggerFactory.getLogger(QueueConnections.class);

    @Inject
    MqConnectionFactoryProvider factories;

    private final Map<QueueManagerKey, Connection> connections = new HashMap<>();
    private boolean closed;

    /**
     * Opens the sessions, consumer and producers of one consumer of a route.
     *
     * @param route         the route
     * @param configuration the configuration, giving the credentials of the endpoints
     * @return the opened sessions
     * @throws JMSException when a queue manager or a queue cannot be reached
     */
    public RouteSession open(QueueRouteConfig route, QueueConfiguration configuration) throws JMSException {
        Map<QueueManagerKey, Session> sessions = new LinkedHashMap<>();
        try {
            Session input = session(sessions, route.getInput(), configuration);
            Session output = session(sessions, route.getOutput(), configuration);
            Session error = route.getError() == null ? null : session(sessions, route.getError(), configuration);

            MessageConsumer consumer = input.createConsumer(input.createQueue(route.getInput().getQueue()));
            MessageProducer outputProducer = output.createProducer(output.createQueue(route.getOutput().getQueue()));
            MessageProducer errorProducer = error == null ? null
                    : error.createProducer(error.createQueue(route.getError().getQueue()));

            List<Session> commitOrder = new ArrayList<>(sessions.values());
            commitOrder.remove(input);
            commitOrder.add(input);
            return new RouteSession(input, consumer, output, outputProducer, error, errorProducer, commitOrder);
        } catch (JMSException | RuntimeException e) {
            sessions.values().forEach(QueueConnections::closeQuietly);
            throw e;
        }
    }

    private Session session(Map<QueueManagerKey, Session> sessions, QueueEndpointConfig endpoint,
                            QueueConfiguration configuration) throws JMSException {
        QueueManagerKey key = endpoint.queueManagerKey();
        Session session = sessions.get(key);
        if (session == null) {
            Connection connection = connection(key, configuration);
            try {
                session = connection.createSession(true, Session.SESSION_TRANSACTED);
            } catch (JMSException | RuntimeException e) {
                // A connection that cannot open a session any more is broken: the next attempt reconnects
                invalidate(key, connection);
                throw e;
            }
            sessions.put(key, session);
        }
        return session;
    }

    private synchronized Connection connection(QueueManagerKey key, QueueConfiguration configuration)
            throws JMSException {
        if (closed) {
            throw new jakarta.jms.IllegalStateException("The queue proxy is stopping");
        }
        Connection connection = connections.get(key);
        if (connection == null) {
            connection = factories.connectionFactory(key, configuration.credentials(key.credentials()))
                    .createConnection();
            Connection created = connection;
            try {
                connection.setExceptionListener(e -> {
                    LOG.warn("Connection to queue manager {} lost: {}", key, e.getMessage());
                    invalidate(key, created);
                });
                connection.start();
            } catch (JMSException | RuntimeException e) {
                closeQuietly(connection);
                throw e;
            }
            connections.put(key, connection);
            LOG.info("Connected to queue manager {}", key);
        }
        return connection;
    }

    /** Drops a connection if it is still the current one of its queue manager. */
    void invalidate(QueueManagerKey key, Connection failed) {
        boolean dropped;
        synchronized (this) {
            dropped = connections.get(key) == failed;
            if (dropped) {
                connections.remove(key);
            }
        }
        if (dropped) {
            // Closing a broken connection may block: never while holding the lock, nor on the caller's thread
            CompletableFuture.runAsync(() -> closeQuietly(failed));
        }
    }

    /** @return the number of open connections */
    public synchronized int openConnections() {
        return connections.size();
    }

    /** Closes every connection; no new one can be opened afterwards. */
    public void closeAll() {
        List<Connection> toClose;
        synchronized (this) {
            closed = true;
            toClose = new ArrayList<>(connections.values());
            connections.clear();
        }
        toClose.forEach(QueueConnections::closeQuietly);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            LOG.debug("Closing failed: {}", e.getMessage());
        }
    }
}
