package com.ubp.rgd.proxy.wiring;

import com.ubp.rgd.proxy.fuzzysearch.service.FuzzySearchHealthCheck;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fuzzy search ships with the proxy but is off by default: it must neither start anything nor
 * report the proxy down, and its endpoints must say it is disabled. Its logic is covered by the unit
 * tests of rgd-proxy-fuzzysearch; running it enabled needs SQL Server, OpenSearch and the FastText model.
 */
@QuarkusTest
@TestProfile(FuzzySearchDisabledWiringTest.NoMongoHealthProfile.class)
class FuzzySearchDisabledWiringTest {

    /** No MongoDB in the tests: its health check would make /health/ready wait for it. */
    public static class NoMongoHealthProfile extends com.ubp.rgd.proxy.services.TestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> props = super.getConfigOverrides();
            props.put("quarkus.mongodb.health.enabled", "false");
            return props;
        }
    }

    @Inject
    @Readiness
    FuzzySearchHealthCheck readiness;

    @Test
    @DisplayName("The fuzzy search endpoints answer 503 while the module is disabled")
    void endpointsAreUnavailable() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"query\":\"Jean\"}")
                .post("/api/fuzzy-search/persons/search")
                .then()
                .statusCode(503)
                .body(containsString("fuzzy-search.enabled=false"));

        RestAssured.given()
                .post("/api/fuzzy-search/admin/reindex")
                .then()
                .statusCode(503);
    }

    @Test
    @DisplayName("A disabled fuzzy search is ready, and its datasource and OpenSearch are not health checked")
    void disabledModuleIsReady() {
        HealthCheckResponse response = readiness.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(Boolean.FALSE, response.getData().orElseThrow().get("enabled"));

        // Neither the inactive fuzzy-search datasource nor OpenSearch may report the proxy down
        String health = RestAssured.get("/health/ready").then().extract().asString();
        assertFalse(health.contains("fuzzy-search"), "the inactive datasource must not be checked: " + health);
        assertFalse(health.contains("Elasticsearch"), "the OpenSearch health check must be off: " + health);
        assertTrue(health.contains("\"Fuzzy search\""), health);
    }
}
