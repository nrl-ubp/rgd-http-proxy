package com.ubp.rgd.proxy.tds;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/** Ready when the TDS gateway listens, or when it is disabled. */
@Readiness
@ApplicationScoped
public class TdsGatewayHealthCheck implements HealthCheck {

    @Inject
    TdsServerService service;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("TDS gateway")
                .withData("enabled", service.isEnabled());
        if (!service.isEnabled()) {
            return builder.up().build();
        }
        if (service.isRunning()) {
            return builder.up().withData("port", service.port()).withData("sessions", service.activeSessions()).build();
        }
        return builder.down().withData("error", String.valueOf(service.failure())).build();
    }
}
