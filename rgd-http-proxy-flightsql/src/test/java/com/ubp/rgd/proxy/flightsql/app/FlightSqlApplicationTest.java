package com.ubp.rgd.proxy.flightsql.app;

import com.ubp.rgd.proxy.flightsql.FlightSqlServerService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Smoke test of the runner with the Flight SQL server: the application classes, which come from the
 * rgd-http-proxy jar, and the extension are both active.
 */
@QuarkusTest
@TestProfile(FlightSqlApplicationTestProfile.class)
class FlightSqlApplicationTest {

    @Inject
    FlightSqlServerService flightSqlServer;

    @ConfigProperty(name = "proxy.flight-sql.port")
    int flightSqlPort;

    @Test
    @DisplayName("The REST endpoints of rgd-http-proxy are served")
    void applicationEndpointsAreServed() {
        RestAssured.given()
                .get("/openapi?format=json")
                .then()
                .statusCode(200)
                .body("paths", hasKey("/transform"))
                .body("paths", hasKey("/utils/wdx1/concat"));
    }

    @Test
    @DisplayName("The Flight SQL server is started")
    void flightSqlServerIsListening() {
        assertNotNull(flightSqlServer);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", flightSqlPort), 1000);
        } catch (IOException e) {
            fail("the Flight SQL server does not listen on port " + flightSqlPort + ": " + e.getMessage());
        }
    }
}
