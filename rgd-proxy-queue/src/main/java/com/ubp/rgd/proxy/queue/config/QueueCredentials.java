package com.ubp.rgd.proxy.queue.config;

/**
 * The user and password of a credentials alias, read from
 * {@code proxy.queue.credentials.<alias>.user} and {@code .password}.
 */
public record QueueCredentials(String user, String password) {

    @Override
    public String toString() {
        return "QueueCredentials[user=" + user + ", password=***]";
    }
}
