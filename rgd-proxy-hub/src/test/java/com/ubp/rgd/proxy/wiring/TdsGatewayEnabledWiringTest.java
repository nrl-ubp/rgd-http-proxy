package com.ubp.rgd.proxy.wiring;

import com.ubp.rgd.proxy.tds.TdsGatewayHealthCheck;
import com.ubp.rgd.proxy.tds.TdsServerService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code proxy.tds.enabled=true} starts the TDS gateway with the default settings of the module. */
@QuarkusTest
@TestProfile(TdsGatewayEnabledWiringTest.TdsEnabledProfile.class)
class TdsGatewayEnabledWiringTest {

    public static class TdsEnabledProfile extends FuzzySearchDisabledWiringTest.NoMongoHealthProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> props = super.getConfigOverrides();
            props.put("proxy.tds.enabled", "true");
            props.put("proxy.tds.host", "localhost");
            props.put("proxy.tds.backend.host", "localhost");
            props.put("proxy.tds.backend.port", Integer.toString(com.ubp.rgd.proxy.services.TestProfile.freePort()));
            return props;
        }
    }

    @Inject
    TdsServerService service;

    @Inject
    @Readiness
    TdsGatewayHealthCheck readiness;

    @ConfigProperty(name = "proxy.tds.port")
    int port;

    @Test
    @DisplayName("proxy.tds.enabled=true starts the gateway on the configured port")
    void gatewayIsListening() {
        assertTrue(service.isRunning());
        assertEquals(port, service.port());
        assertTrue(FlightSqlWiringTest.listening(port), "nothing listens on port " + port);
        assertEquals(HealthCheckResponse.Status.UP, readiness.call().getStatus());
    }
}
