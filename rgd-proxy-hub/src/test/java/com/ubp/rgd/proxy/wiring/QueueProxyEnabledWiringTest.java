package com.ubp.rgd.proxy.wiring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.queue.QueueRoutesHealthCheck;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the queue proxy of the application end to end, with an embedded broker instead of IBM MQ and
 * the FPE transformer: a message put on the input queue comes out protected on the output queue.
 */
@QuarkusTest
@TestProfile(QueueProxyEnabledWiringTest.QueueProxyProfile.class)
class QueueProxyEnabledWiringTest {

    public static class QueueProxyProfile extends FPETransformerWiringTest.FpeProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> props = super.getConfigOverrides();
            props.put("proxy.queue.enabled", "true");
            props.put("proxy.queue.config-file", "./src/test/resources/mq_transform_config.json");
            props.put("proxy.queue.receive-timeout", "200ms");
            return props;
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(EmbeddedBrokerConnectionFactoryProvider.class);
        }
    }

    @Inject
    EmbeddedBrokerConnectionFactoryProvider broker;

    @Inject
    @Readiness
    QueueRoutesHealthCheck readiness;

    @Test
    @DisplayName("A message of the input queue is protected onto the output queue, and the route reports it")
    void messageIsProtectedEndToEnd() throws Exception {
        try (Connection connection = broker.connectionFactory(null, null).createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createProducer(session.createQueue("RGD.PERSONS.IN"))
                    .send(session.createTextMessage("{\"name\":\"Bernadette\"}"));

            Message out = session.createConsumer(session.createQueue("RGD.PERSONS.OUT")).receive(15_000);
            assertNotNull(out, "nothing on the output queue");
            JsonNode person = new ObjectMapper().readTree(assertInstanceOf(TextMessage.class, out).getText());
            assertTrue(person.get("name").asText().startsWith("RG{A0"), person.toString());
            assertFalse(person.toString().contains("Bernadette"));
        }

        JsonPath route = RestAssured.given().get("/api/queue/routes/protect_persons").then().statusCode(200)
                .extract().jsonPath();
        assertEquals("RUNNING", route.getString("state"));
        assertEquals(1, route.getInt("transformed"));
        assertEquals("RGD.PERSONS.IN@QM1(localhost:1414)", route.getString("input"));

        HealthCheckResponse health = readiness.call();
        assertEquals(HealthCheckResponse.Status.UP, health.getStatus());
        assertEquals("RUNNING", health.getData().orElseThrow().get("protect_persons"));
    }
}
