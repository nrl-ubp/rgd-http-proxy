package com.ubp.rgd.proxy.wiring;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that {@code proxy.flight-sql.enabled=true} starts the Flight SQL server, in front of an
 * in-memory database.
 */
@QuarkusTest
@TestProfile(FlightSqlEnabledWiringTest.FlightSqlEnabledProfile.class)
class FlightSqlEnabledWiringTest {

    public static class FlightSqlEnabledProfile extends com.ubp.rgd.proxy.services.TestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> props = super.getConfigOverrides();
            props.put("proxy.flight-sql.enabled", "true");
            props.put("proxy.flight-sql.host", "localhost");
            props.put("proxy.flight-sql.tls.enabled", "false");
            props.put("proxy.flight-sql.jdbc-url", "jdbc:h2:mem:flightsql-wiring");
            return props;
        }
    }

    @ConfigProperty(name = "proxy.flight-sql.port")
    int port;

    @Test
    @DisplayName("proxy.flight-sql.enabled=true starts the server on the configured port")
    void serverIsListening() {
        assertTrue(FlightSqlWiringTest.listening(port), "nothing listens on port " + port);
    }
}
