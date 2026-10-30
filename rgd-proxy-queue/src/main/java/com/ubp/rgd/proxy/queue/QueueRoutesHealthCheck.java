package com.ubp.rgd.proxy.queue;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Ready when the queue proxy is disabled, or when every consumer of every route is connected. The
 * state of each route is given in the data of the check.
 */
@Readiness
@ApplicationScoped
public class QueueRoutesHealthCheck implements HealthCheck {

    static final String NAME = "rgd-queue-routes";

    @Inject
    QueueProxyService service;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder response = HealthCheckResponse.named(NAME).withData("enabled", service.enabled());
        boolean up = true;
        for (QueueRoute route : service.routes()) {
            QueueRoute.State state = route.state();
            response.withData(route.name(), state.name());
            up &= state == QueueRoute.State.RUNNING;
        }
        return response.status(up).build();
    }
}
