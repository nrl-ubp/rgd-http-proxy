package com.ubp.rgd.proxy.queue;

import com.ibm.msg.client.jakarta.jms.JmsConnectionFactory;
import com.ibm.msg.client.jakarta.jms.JmsFactoryFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import com.ubp.rgd.proxy.queue.config.QueueCredentials;
import com.ubp.rgd.proxy.queue.config.QueueManagerKey;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Connects to IBM MQ queue managers in client mode, through their server-connection channel.
 * <p>
 * The user and password are sent with MQCSP authentication. With a cipher suite, the channel is
 * secured with TLS, using the key and trust stores of the JVM ({@code javax.net.ssl.*} properties).
 * Reconnection is left to the queue proxy, which rolls back and reopens its sessions, so the client
 * automatic reconnection is not enabled.
 * <p>
 * The client keeps its own log (connection problems, client warnings), written by default in the
 * working directory; it goes to {@code proxy.queue.client-log} instead, unless the
 * {@value #CLIENT_LOG_PROPERTY} system property already says where.
 */
@DefaultBean
@ApplicationScoped
public class IbmMqConnectionFactoryProvider implements MqConnectionFactoryProvider {

    static final String CLIENT_LOG_PROPERTY = "com.ibm.msg.client.commonservices.log.outputName";

    @ConfigProperty(name = "proxy.queue.application-name", defaultValue = "rgd-proxy-queue")
    String applicationName;

    @ConfigProperty(name = "proxy.queue.client-log", defaultValue = "./logs/mqjms.log")
    String clientLog;

    private boolean clientLogConfigured;

    @Override
    public ConnectionFactory connectionFactory(QueueManagerKey queueManager, QueueCredentials credentials)
            throws JMSException {
        configureClientLog();
        JmsConnectionFactory factory = JmsFactoryFactory.getInstance(WMQConstants.JAKARTA_WMQ_PROVIDER)
                .createConnectionFactory();
        factory.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        factory.setStringProperty(WMQConstants.WMQ_HOST_NAME, queueManager.host());
        factory.setIntProperty(WMQConstants.WMQ_PORT, queueManager.port());
        factory.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, queueManager.queueManager());
        factory.setStringProperty(WMQConstants.WMQ_CHANNEL, queueManager.channel());
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, applicationName);
        if (credentials != null) {
            factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
            factory.setStringProperty(WMQConstants.USERID, credentials.user());
            factory.setStringProperty(WMQConstants.PASSWORD, credentials.password());
        }
        if (queueManager.cipherSuite() != null && !queueManager.cipherSuite().isBlank()) {
            factory.setStringProperty(WMQConstants.WMQ_SSL_CIPHER_SUITE, queueManager.cipherSuite());
        }
        return factory;
    }

    /** Must happen before the first use of the client, which reads the property once. */
    private synchronized void configureClientLog() {
        if (clientLogConfigured) {
            return;
        }
        if (System.getProperty(CLIENT_LOG_PROPERTY) == null) {
            Path log = Path.of(clientLog).toAbsolutePath();
            try {
                Files.createDirectories(log.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create the directory of the IBM MQ client log " + log, e);
            }
            System.setProperty(CLIENT_LOG_PROPERTY, log.toString());
        }
        clientLogConfigured = true;
    }
}
