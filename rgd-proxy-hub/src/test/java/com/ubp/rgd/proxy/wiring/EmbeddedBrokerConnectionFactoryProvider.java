package com.ubp.rgd.proxy.wiring;

import com.ubp.rgd.proxy.queue.MqConnectionFactoryProvider;
import com.ubp.rgd.proxy.queue.config.QueueCredentials;
import com.ubp.rgd.proxy.queue.config.QueueManagerKey;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

/**
 * Replaces IBM MQ with an embedded ActiveMQ Artemis broker, reached in-VM, whatever the queue manager.
 * Enabled by the profiles of the queue proxy tests only.
 */
@Alternative
@ApplicationScoped
public class EmbeddedBrokerConnectionFactoryProvider implements MqConnectionFactoryProvider {

    private static final String URL = "vm://77";

    private EmbeddedActiveMQ broker;

    @Override
    public synchronized ConnectionFactory connectionFactory(QueueManagerKey queueManager, QueueCredentials credentials) {
        if (broker == null) {
            try {
                ConfigurationImpl configuration = new ConfigurationImpl();
                configuration.setPersistenceEnabled(false).setSecurityEnabled(false).setJMXManagementEnabled(false);
                configuration.addAcceptorConfiguration("in-vm", URL);
                broker = new EmbeddedActiveMQ().setConfiguration(configuration).start();
            } catch (Exception e) {
                throw new IllegalStateException("The embedded broker does not start", e);
            }
        }
        return new ActiveMQConnectionFactory(URL);
    }

    @PreDestroy
    synchronized void stop() throws Exception {
        if (broker != null) {
            broker.stop();
            broker = null;
        }
    }
}
