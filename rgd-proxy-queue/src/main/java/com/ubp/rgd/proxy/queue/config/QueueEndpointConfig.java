package com.ubp.rgd.proxy.queue.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A queue of a route, with the queue manager hosting it.
 * <p>
 * Every endpoint is self-contained, so that the input, output and error queues of a route may live on
 * different hosts and queue managers. Endpoints naming the same queue manager (host, port, queue
 * manager, channel, credentials and cipher suite) share one connection, see {@link #queueManagerKey()}.
 */
@RegisterForReflection
public class QueueEndpointConfig {

    @JsonProperty("host")
    private String host;

    @JsonProperty("port")
    private int port = 1414;

    @JsonProperty("queue-manager")
    private String queueManager;

    @JsonProperty("channel")
    private String channel;

    /**
     * The queue name, or an IBM MQ queue URI such as {@code queue:///RGD.OUT?targetClient=1} to send
     * messages without JMS headers to a non-JMS application.
     */
    @JsonProperty("queue")
    private String queue;

    /** Alias of the {@code proxy.queue.credentials.<alias>.user/password} properties, none when absent. */
    @JsonProperty("credentials")
    private String credentials;

    /** TLS cipher suite of the channel, plaintext when absent. */
    @JsonProperty("cipher-suite")
    private String cipherSuite;

    public QueueEndpointConfig() {
    }

    public QueueEndpointConfig(String host, int port, String queueManager, String channel, String queue) {
        this.host = host;
        this.port = port;
        this.queueManager = queueManager;
        this.channel = channel;
        this.queue = queue;
    }

    /** @return what identifies the connection to the queue manager, shared by the endpoints naming it */
    public QueueManagerKey queueManagerKey() {
        return new QueueManagerKey(host, port, queueManager, channel, credentials, cipherSuite);
    }

    /** @return the endpoint as {@code QUEUE@QMGR(host:port)}, for logs and the REST endpoints */
    public String describe() {
        return String.format("%s@%s(%s:%d)", queue, queueManager, host, port);
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getQueueManager() {
        return queueManager;
    }

    public void setQueueManager(String queueManager) {
        this.queueManager = queueManager;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getCredentials() {
        return credentials;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials;
    }

    public String getCipherSuite() {
        return cipherSuite;
    }

    public void setCipherSuite(String cipherSuite) {
        this.cipherSuite = cipherSuite;
    }
}
