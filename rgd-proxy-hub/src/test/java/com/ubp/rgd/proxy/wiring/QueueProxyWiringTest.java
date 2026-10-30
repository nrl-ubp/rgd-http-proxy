package com.ubp.rgd.proxy.wiring;

import com.ubp.rgd.proxy.queue.QueueProxyService;
import com.ubp.rgd.proxy.queue.QueueRoutesHealthCheck;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the application contains the queue proxy, its endpoints and its readiness check, and
 * that it does nothing while {@code proxy.queue.enabled} is false, which is the default.
 */
@QuarkusTest
@TestProfile(com.ubp.rgd.proxy.services.TestProfile.class)
class QueueProxyWiringTest {

    // the readiness endpoint itself waits for the MongoDB and SQL Server checks, unreachable in the tests
    @Inject
    @Readiness
    QueueRoutesHealthCheck readiness;

    @Test
    @DisplayName("The application contains the queue proxy, disabled")
    void serviceIsPresentAndDisabled() {
        QueueProxyService service = Arc.container().instance(QueueProxyService.class).get();
        assertFalse(service.enabled());
        assertTrue(service.routes().isEmpty());
    }

    @Test
    @DisplayName("The routes endpoint answers, with no route while disabled, and is documented")
    void routesEndpoint() {
        RestAssured.given().get("/api/queue/routes").then().statusCode(200);
        assertEquals(0, RestAssured.given().get("/api/queue/routes").jsonPath().getList("$").size());
        RestAssured.given().get("/api/queue/routes/unknown").then().statusCode(404);
        RestAssured.given()
                .get("/openapi?format=json")
                .then()
                .statusCode(200)
                .body("paths", hasKey("/api/queue/routes"))
                .body("paths", hasKey("/api/queue/routes/{name}"));
    }

    @Test
    @DisplayName("The readiness check of the routes is up while the queue proxy is disabled")
    void readinessIsUp() {
        assertEquals(HealthCheckResponse.Status.UP, readiness.call().getStatus());
    }
}
