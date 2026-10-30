package com.ubp.rgd.proxy.queue.config;

import java.util.List;
import java.util.Map;

/**
 * The validated routes, and the credentials of the aliases they name.
 */
public record QueueConfiguration(List<QueueRouteConfig> routes, Map<String, QueueCredentials> credentials) {

    /** @return the credentials of an alias, {@code null} for none */
    public QueueCredentials credentials(String alias) {
        return alias == null ? null : credentials.get(alias);
    }
}
