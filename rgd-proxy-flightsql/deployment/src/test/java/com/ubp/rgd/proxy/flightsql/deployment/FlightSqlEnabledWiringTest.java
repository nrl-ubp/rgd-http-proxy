package com.ubp.rgd.proxy.flightsql.deployment;

import io.quarkus.test.QuarkusUnitTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that an application with the extension starts the Flight SQL server when it is enabled.
 */
class FlightSqlEnabledWiringTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = FlightSqlWiringTestSupport.application(true,
            FlightSqlWiringTestSupport.freePort());

    // read back from the configuration: this class is loaded again by the application class loader,
    // where a static free port would be drawn again
    @ConfigProperty(name = "proxy.flight-sql.port")
    int port;

    @Test
    @DisplayName("proxy.flight-sql.enabled=true starts the server on the configured port")
    void serverIsListening() {
        assertTrue(FlightSqlWiringTestSupport.listening(port), "nothing listens on port " + port);
    }
}
