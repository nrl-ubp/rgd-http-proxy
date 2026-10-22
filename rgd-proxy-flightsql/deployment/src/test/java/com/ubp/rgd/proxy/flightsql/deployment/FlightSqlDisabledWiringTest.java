package com.ubp.rgd.proxy.flightsql.deployment;

import com.ubp.rgd.proxy.flightsql.FlightSqlServerService;
import com.ubp.rgd.proxy.flightsql.FlightSqlTokenizeService;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusUnitTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the extension contributes the Flight SQL beans, but that the server stays down while
 * {@code proxy.flight-sql.enabled} is false.
 */
class FlightSqlDisabledWiringTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = FlightSqlWiringTestSupport.application(false,
            FlightSqlWiringTestSupport.freePort());

    // read back from the configuration: this class is loaded again by the application class loader,
    // where a static free port would be drawn again
    @ConfigProperty(name = "proxy.flight-sql.port")
    int port;

    @Test
    @DisplayName("The extension contributes the Flight SQL beans")
    void beansArePresent() {
        assertTrue(Arc.container().instance(FlightSqlServerService.class).isAvailable());
        assertTrue(Arc.container().instance(FlightSqlTokenizeService.class).isAvailable());
    }

    @Test
    @DisplayName("proxy.flight-sql.enabled=false leaves the server down")
    void serverIsNotListening() {
        assertFalse(FlightSqlWiringTestSupport.listening(port), "something listens on port " + port);
    }
}
