package com.ubp.rgd.proxy.wiring;

import com.ubp.rgd.proxy.flightsql.FlightSqlServerService;
import com.ubp.rgd.proxy.flightsql.FlightSqlTokenizeService;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the application contains the Flight SQL beans, but that the server stays down while
 * {@code proxy.flight-sql.enabled} is false, which is the default.
 */
@QuarkusTest
@TestProfile(com.ubp.rgd.proxy.services.TestProfile.class)
class FlightSqlWiringTest {

    @ConfigProperty(name = "proxy.flight-sql.port")
    int port;

    @Test
    @DisplayName("The application contains the Flight SQL beans")
    void beansArePresent() {
        assertTrue(Arc.container().instance(FlightSqlServerService.class).isAvailable());
        assertTrue(Arc.container().instance(FlightSqlTokenizeService.class).isAvailable());
    }

    @Test
    @DisplayName("proxy.flight-sql.enabled=false leaves the server down")
    void serverIsNotListening() {
        assertFalse(listening(port), "something listens on port " + port);
    }

    static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
