package com.ubp.rgd.proxy.wiring;

import com.ubp.rgd.proxy.filetransform.FileTransformService;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the application contains the file transformation service and its
 * {@code /api/file-transform} endpoints, documented in the OpenAPI document.
 */
@QuarkusTest
@TestProfile(com.ubp.rgd.proxy.services.TestProfile.class)
class FileTransformWiringTest {

    @Test
    @DisplayName("The application contains the file transformation service")
    void serviceIsPresent() {
        assertTrue(Arc.container().instance(FileTransformService.class).isAvailable());
    }

    @Test
    @DisplayName("The endpoints serve the configurations of the test configuration file")
    void endpointsAreServed() {
        RestAssured.given()
                .get("/api/file-transform/configurations")
                .then()
                .statusCode(200)
                .body("count", equalTo(3));
    }

    @Test
    @DisplayName("The endpoints are in the OpenAPI document")
    void endpointsAreDocumented() {
        RestAssured.given()
                .get("/openapi?format=json")
                .then()
                .statusCode(200)
                .body("paths", hasKey("/api/file-transform/configurations"))
                .body("paths", hasKey("/api/file-transform/trigger/{configName}"))
                .body("paths", hasKey("/api/file-transform/configuration/{configName}"));
    }
}
