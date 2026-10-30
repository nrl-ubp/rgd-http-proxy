package com.ubp.rgd.proxy.queue;

import com.ubp.rgd.proxy.queue.config.QueueCredentials;
import com.ubp.rgd.proxy.queue.config.QueueManagerKey;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;

/**
 * Creates the Jakarta Messaging connection factory of a queue manager.
 * <p>
 * The IBM MQ implementation is the default bean; the tests provide another one, backed by an embedded
 * broker, since nothing else in the queue proxy depends on IBM MQ.
 */
public interface MqConnectionFactoryProvider {

    /**
     * @param queueManager the queue manager to connect to
     * @param credentials  the user and password, {@code null} to send none
     * @return a connection factory whose {@code createConnection()} connects with the credentials
     * @throws JMSException when the factory cannot be configured
     */
    ConnectionFactory connectionFactory(QueueManagerKey queueManager, QueueCredentials credentials) throws JMSException;
}
