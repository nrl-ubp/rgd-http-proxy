package com.ubp.rgd.proxy.wiring;

import com.ubp.rgd.proxy.tds.TdsGatewayHealthCheck;
import com.ubp.rgd.proxy.tds.TdsServerService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The TDS gateway ships with the proxy but is off by default: nothing listens and the proxy stays ready. */
@QuarkusTest
@TestProfile(FuzzySearchDisabledWiringTest.NoMongoHealthProfile.class)
class TdsGatewayWiringTest {

    @Inject
    TdsServerService service;

    @Inject
    @Readiness
    TdsGatewayHealthCheck readiness;

    @ConfigProperty(name = "proxy.tds.port")
    int port;

    @Test
    @DisplayName("A disabled TDS gateway listens on nothing and is ready")
    void disabledGatewayIsReady() {
        assertFalse(service.isRunning());
        assertFalse(FlightSqlWiringTest.listening(port), "something listens on port " + port);
        HealthCheckResponse response = readiness.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(Boolean.FALSE, response.getData().orElseThrow().get("enabled"));
        assertTrue(RestAssured.get("/health/ready").then().extract().asString().contains("\"TDS gateway\""));
    }
}
