package com.ubp.rgd.proxy.resources;

import com.ubp.rgd.proxy.services.SecretsManagerResolver;
import com.ubp.rgd.proxy.services.TestSecretsManagers;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the application and checks that every endpoint selecting a secrets manager rejects an unknown
 * value of the header with a 400, before anything is transformed or forwarded.
 */
@QuarkusTest
@TestProfile(com.ubp.rgd.proxy.services.TestProfile.class)
class SecretsManagerEndpointTest {

    @Inject
    SecretsManagerResolver resolver;

    @Test
    @DisplayName("The resolver is loaded from the configured mapping file")
    void resolverIsConfigured() {
        assertEquals(TestSecretsManagers.HEADER, resolver.headerName());
        assertEquals(TestSecretsManagers.LU, resolver.defaultSecretsManager());
        assertEquals(TestSecretsManagers.CH, resolver.resolve("ch"));
    }

    @Test
    @DisplayName("/transform rejects an unknown secrets manager")
    void transformRejectsUnknownValue() {
        RestAssured.given()
                .header(TestSecretsManagers.HEADER, "MC")
                .contentType(ContentType.JSON)
                .body("{\"sets\":[]}")
                .post("/transform")
                .then()
                .statusCode(400)
                .body(containsString("Unknown " + TestSecretsManagers.HEADER + " value: MC"));
    }

    @Test
    @DisplayName("/utils/wdx1/concat rejects an unknown secrets manager")
    void concatRejectsUnknownValue() {
        RestAssured.given()
                .header(TestSecretsManagers.HEADER, "MC")
                .contentType(ContentType.JSON)
                .body("{}")
                .post("/utils/wdx1/concat")
                .then()
                .statusCode(400)
                .body(containsString("MC"));
    }

    @Test
    @DisplayName("/proxy rejects an unknown secrets manager before forwarding")
    void proxyRejectsUnknownValue() {
        RestAssured.given()
                .header(TestSecretsManagers.HEADER, "MC")
                .get("/proxy/persons")
                .then()
                .statusCode(400)
                .body(containsString("MC"));
    }

    @Test
    @DisplayName("/transform accepts a known value, whatever its case")
    void transformAcceptsKnownValue() {
        RestAssured.given()
                .header(TestSecretsManagers.HEADER, "lu")
                .contentType(ContentType.JSON)
                .body("{\"sets\":[]}")
                .post("/transform")
                .then()
                .statusCode(200);
    }
}
