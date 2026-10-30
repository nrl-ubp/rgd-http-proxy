package com.ubp.rgd.proxy.queue;

import com.ubp.rgd.proxy.queue.config.QueueEndpointConfig;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded ActiveMQ Artemis brokers standing in for IBM MQ queue managers, one per queue manager name,
 * reached in-VM: the queue proxy only talks Jakarta Messaging, so the provider does not matter to it.
 */
final class TestBrokers implements AutoCloseable {

    private static final AtomicInteger NEXT_ID = new AtomicInteger(100);

    /** A message read back from a queue, copied before its connection is closed. */
    record Received(String type, String body, String correlationId, int priority, Map<String, Object> properties) {
    }

    @FunctionalInterface
    interface MessageCreator {
        Message create(Session session) throws JMSException;
    }

    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<String, EmbeddedActiveMQ> servers = new HashMap<>();

    TestBrokers(String... queueManagers) throws Exception {
        for (String queueManager : queueManagers) {
            ids.put(queueManager, NEXT_ID.incrementAndGet());
            start(queueManager);
        }
    }

    void start(String queueManager) throws Exception {
        ConfigurationImpl configuration = new ConfigurationImpl();
        configuration.setName(queueManager)
                .setPersistenceEnabled(false)
                .setSecurityEnabled(false)
                .setJMXManagementEnabled(false)
                // rolled back messages stay on the queue, whatever the number of attempts
                .addAddressSetting("#", new AddressSettings().setMaxDeliveryAttempts(-1).setRedeliveryDelay(0));
        configuration.addAcceptorConfiguration("in-vm", "vm://" + ids.get(queueManager));
        servers.put(queueManager, new EmbeddedActiveMQ().setConfiguration(configuration).start());
    }

    void stop(String queueManager) throws Exception {
        EmbeddedActiveMQ server = servers.remove(queueManager);
        if (server != null) {
            server.stop();
        }
    }

    MqConnectionFactoryProvider provider() {
        return (queueManager, credentials) -> factory(queueManager.queueManager());
    }

    ConnectionFactory factory(String queueManager) {
        return new ActiveMQConnectionFactory("vm://" + ids.get(queueManager));
    }

    static QueueEndpointConfig endpoint(String queueManager, String queue) {
        return new QueueEndpointConfig("localhost", 1414, queueManager, "TEST.SVRCONN", queue);
    }

    void send(String queueManager, String queue, MessageCreator creator) throws JMSException {
        send(queueManager, queue, creator, Message.DEFAULT_PRIORITY);
    }

    void send(String queueManager, String queue, MessageCreator creator, int priority) throws JMSException {
        try (Connection connection = factory(queueManager).createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createProducer(session.createQueue(queue))
                    .send(creator.create(session), Message.DEFAULT_DELIVERY_MODE, priority, Message.DEFAULT_TIME_TO_LIVE);
        }
    }

    void sendText(String queueManager, String queue, String text) throws JMSException {
        send(queueManager, queue, session -> session.createTextMessage(text));
    }

    /** @return the next message of the queue, {@code null} when none arrives in time */
    Received receive(String queueManager, String queue, long timeoutMillis) throws JMSException {
        try (Connection connection = factory(queueManager).createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
            Message message = consumer.receive(timeoutMillis);
            return message == null ? null : copy(message);
        }
    }

    private static Received copy(Message message) throws JMSException {
        Map<String, Object> properties = new HashMap<>();
        for (Object name : Collections.list(message.getPropertyNames())) {
            properties.put((String) name, message.getObjectProperty((String) name));
        }
        String body;
        String type;
        if (message instanceof TextMessage text) {
            type = "text";
            body = text.getText();
        } else if (message instanceof BytesMessage) {
            type = "bytes";
            byte[] bytes = message.getBody(byte[].class);
            body = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        } else {
            type = message.getClass().getSimpleName();
            body = null;
        }
        return new Received(type, body, message.getJMSCorrelationID(), message.getJMSPriority(), properties);
    }

    @Override
    public void close() throws Exception {
        for (String queueManager : Map.copyOf(servers).keySet()) {
            stop(queueManager);
        }
    }
}
