package com.ubp.rgd.proxy.queue.config;

/**
 * Identifies a connection to a queue manager: the endpoints with the same key share one connection.
 */
public record QueueManagerKey(String host, int port, String queueManager, String channel,
                              String credentials, String cipherSuite) {

    @Override
    public String toString() {
        return String.format("%s(%s:%d, channel %s)", queueManager, host, port, channel);
    }
}
