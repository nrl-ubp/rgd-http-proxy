package com.ubp.rgd.proxy.queue;

import com.ibm.msg.client.jakarta.jms.JmsConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import com.ubp.rgd.proxy.queue.config.QueueCredentials;
import com.ubp.rgd.proxy.queue.config.QueueManagerKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks how an endpoint is turned into an IBM MQ client connection factory. No queue manager is
 * contacted: a factory only connects in {@code createConnection()}.
 */
class IbmMqConnectionFactoryProviderTest {

    private final IbmMqConnectionFactoryProvider provider = new IbmMqConnectionFactoryProvider();

    {
        provider.applicationName = "rgd-proxy-queue";
        provider.clientLog = "target/mq-client/mqjms.log";
    }

    @Test
    @DisplayName("Host, port, queue manager, channel, credentials and cipher suite are set on the factory")
    void clientConnectionWithCredentialsAndTls() throws Exception {
        QueueManagerKey key = new QueueManagerKey("mq-gva.example.com", 1415, "QMGVA01", "RGD.SVRCONN", "gva",
                "TLS_AES_256_GCM_SHA384");

        JmsConnectionFactory factory = (JmsConnectionFactory) provider.connectionFactory(key,
                new QueueCredentials("rgdapp", "secret"));

        assertEquals(WMQConstants.WMQ_CM_CLIENT, factory.getIntProperty(WMQConstants.WMQ_CONNECTION_MODE));
        assertEquals("mq-gva.example.com", factory.getStringProperty(WMQConstants.WMQ_HOST_NAME));
        assertEquals(1415, factory.getIntProperty(WMQConstants.WMQ_PORT));
        assertEquals("QMGVA01", factory.getStringProperty(WMQConstants.WMQ_QUEUE_MANAGER));
        assertEquals("RGD.SVRCONN", factory.getStringProperty(WMQConstants.WMQ_CHANNEL));
        assertTrue(factory.getBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP));
        assertEquals("rgdapp", factory.getStringProperty(WMQConstants.USERID));
        assertEquals("TLS_AES_256_GCM_SHA384", factory.getStringProperty(WMQConstants.WMQ_SSL_CIPHER_SUITE));
        assertTrue(System.getProperty(IbmMqConnectionFactoryProvider.CLIENT_LOG_PROPERTY).endsWith("mq-client/mqjms.log"),
                "the client log is not redirected");
    }

    @Test
    @DisplayName("Without credentials nor cipher suite, none is set")
    void plainConnectionWithoutCredentials() throws Exception {
        QueueManagerKey key = new QueueManagerKey("localhost", 1414, "QM1", "DEV.APP.SVRCONN", null, null);

        JmsConnectionFactory factory = (JmsConnectionFactory) provider.connectionFactory(key, null);

        assertFalse(factory.getBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP));
        assertTrue(factory.getStringProperty(WMQConstants.WMQ_SSL_CIPHER_SUITE) == null
                || factory.getStringProperty(WMQConstants.WMQ_SSL_CIPHER_SUITE).isEmpty());
    }
}
