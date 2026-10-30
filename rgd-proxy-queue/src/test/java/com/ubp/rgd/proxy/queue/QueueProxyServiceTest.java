package com.ubp.rgd.proxy.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.queue.config.QueueConfigLoader;
import com.ubp.rgd.proxy.queue.config.QueueEndpointConfig;
import com.ubp.rgd.proxy.queue.config.QueueRouteConfig;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.BytesMessage;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.ubp.rgd.proxy.queue.TestBrokers.endpoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the queue proxy pipelines against embedded brokers standing in for the queue managers QM1 and
 * QM2, with the real FPE transformer.
 */
class QueueProxyServiceTest {

    private static final UUID DEFAULT_SECRETS_MANAGER = UUID.fromString("f234b749-1415-4c94-ac91-accf9f6ce5d2");
    private static final String PERSON = "{\"name\":\"Bernadette\",\"account\":\"123456789\"}";
    private static final ObjectMapper JSON = new ObjectMapper();

    private TestBrokers brokers;
    private ScriptedTransformer transformer;
    private QueueProxyService service;
    private QueueConnections connections;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        brokers = new TestBrokers("QM1", "QM2");
        transformer = new ScriptedTransformer();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            service.shutdown();
        }
        brokers.close();
    }

    private void start(QueueRouteConfig... routes) {
        service = new QueueProxyService();
        service.enabled = true;
        service.configFile = "test routes";
        service.receiveTimeout = Duration.ofMillis(100);
        service.initialDelay = Duration.ofMillis(20);
        service.maxDelay = Duration.ofMillis(200);
        service.registry = registry = new SimpleMeterRegistry();
        connections = new QueueConnections();
        connections.factories = brokers.provider();
        service.connections = connections;
        service.listener = new QueueListener();
        service.producer = new QueueProducer();
        QueueMessageTransformer messages = new QueueMessageTransformer();
        messages.transformer = transformer;
        messages.secretsManagers = () -> DEFAULT_SECRETS_MANAGER;
        service.transformer = messages;

        service.start(QueueConfigLoader.validate("test routes", List.of(routes), name -> Optional.empty()));
        Await.until("the routes are connected",
                () -> service.routes().stream().allMatch(route -> route.state() == QueueRoute.State.RUNNING));
    }

    static QueueRouteConfig route(String name, String action, QueueEndpointConfig input, QueueEndpointConfig output,
                                  QueueEndpointConfig error) {
        QueueRouteConfig route = new QueueRouteConfig();
        route.setName(name);
        route.setInput(input);
        route.setOutput(output);
        route.setError(error);
        route.setRightContextEvidences(new HashMap<>(Map.of("Module", "QueueProxy")));
        route.setProcessingContextEvidences(new HashMap<>(Map.of("Action", action)));
        route.setEntityTransformConfigs(List.of(entity("$.name", "FirstName"), entity("$.account", "Account")));
        return route;
    }

    private static EntityTransformConfig entity(String jsonPath, String property) {
        EntityTransformConfig entity = new EntityTransformConfig();
        entity.setJsonPath(jsonPath);
        entity.setRpsClassName("Person");
        entity.setRpsPropertyName(property);
        return entity;
    }

    private QueueRouteStatus status(String route) {
        return service.route(route).orElseThrow().status();
    }

    private static void assertProtected(String body) throws IOException {
        JsonNode person = JSON.readTree(body);
        assertTrue(person.get("name").asText().startsWith("RG{A0"), body);
        assertTrue(person.get("account").asText().startsWith("RG{"), body);
        assertFalse(body.contains("123456789"), body);
        assertFalse(body.contains("Bernadette"), body);
    }

    @Test
    @DisplayName("A text message is protected and sent with its headers to the output queue")
    void protectsATextMessage() throws Exception {
        start(route("protect", "Protect", endpoint("QM1", "IN"), endpoint("QM1", "OUT"), endpoint("QM1", "ERR")));

        brokers.send("QM1", "IN", session -> {
            TextMessage message = session.createTextMessage(PERSON);
            message.setJMSCorrelationID("corr-1");
            message.setJMSType("person");
            message.setStringProperty("Origin", "unit-test");
            return message;
        }, 7);

        TestBrokers.Received out = brokers.receive("QM1", "OUT", 10_000);
        assertNotNull(out, "nothing on the output queue");
        assertEquals("text", out.type());
        assertProtected(out.body());
        assertEquals("corr-1", out.correlationId());
        assertEquals("unit-test", out.properties().get("Origin"));
        assertEquals(7, out.priority());

        assertNull(brokers.receive("QM1", "IN", 200), "the message is still on the input queue");
        assertNull(brokers.receive("QM1", "ERR", 200), "something went to the error queue");
        Await.until("the message is counted", () -> status("protect").transformed() == 1);
        assertEquals(1.0, registry.get(QueueRoute.MESSAGES_METER)
                .tags("route", "protect", "outcome", "transformed").counter().count());
        assertEquals(1, registry.get(QueueRoute.TRANSFORM_METER).tags("route", "protect").timer().count());
        assertEquals(1.0, registry.get(QueueRoute.UP_METER).tags("route", "protect").gauge().value());
    }

    @Test
    @DisplayName("A bytes message is read and sent back as UTF-8 bytes")
    void protectsABytesMessage() throws Exception {
        start(route("protect", "Protect", endpoint("QM1", "IN"), endpoint("QM1", "OUT"), null));

        brokers.send("QM1", "IN", session -> {
            BytesMessage message = session.createBytesMessage();
            message.writeBytes(PERSON.getBytes(StandardCharsets.UTF_8));
            return message;
        });

        TestBrokers.Received out = brokers.receive("QM1", "OUT", 10_000);
        assertNotNull(out, "nothing on the output queue");
        assertEquals("bytes", out.type());
        assertProtected(out.body());
    }

    @Test
    @DisplayName("Protecting then unprotecting across two queue managers gives the original document back")
    void roundTripAcrossQueueManagers() throws Exception {
        start(route("protect", "Protect", endpoint("QM1", "IN"), endpoint("QM1", "MIDDLE"), null),
                route("unprotect", "Unprotect", endpoint("QM1", "MIDDLE"), endpoint("QM2", "OUT"), null));

        brokers.sendText("QM1", "IN", PERSON);

        TestBrokers.Received out = brokers.receive("QM2", "OUT", 10_000);
        assertNotNull(out, "nothing on the output queue of QM2");
        assertEquals(JSON.readTree(PERSON), JSON.readTree(out.body()));
        assertEquals(2, connections.openConnections(), "one connection per queue manager");
    }

    @Test
    @DisplayName("A message that is not JSON goes, unchanged, to the error queue with the reason")
    void invalidMessageGoesToTheErrorQueue() throws Exception {
        start(route("protect", "Protect", endpoint("QM1", "IN"), endpoint("QM1", "OUT"), endpoint("QM2", "ERR")));

        brokers.send("QM1", "IN", session -> {
            TextMessage message = session.createTextMessage("this is not JSON");
            message.setJMSCorrelationID("corr-bad");
            return message;
        });

        TestBrokers.Received error = brokers.receive("QM2", "ERR", 10_000);
        assertNotNull(error, "nothing on the error queue");
        assertEquals("this is not JSON", error.body());
        assertEquals("corr-bad", error.correlationId());
        assertEquals("protect", error.properties().get(QueueProducer.ROUTE_PROPERTY));
        assertEquals(InvalidMessageException.class.getName(), error.properties().get(QueueProducer.ERROR_CLASS_PROPERTY));
        assertTrue(String.valueOf(error.properties().get(QueueProducer.ERROR_REASON_PROPERTY)).contains("not valid JSON"),
                String.valueOf(error.properties()));
        assertNotNull(error.properties().get(QueueProducer.ERROR_REASON_PROPERTY));
        assertNotNull(error.properties().get(QueueProducer.ORIGINAL_ID_PROPERTY));

        assertNull(brokers.receive("QM1", "OUT", 200), "something went to the output queue");
        assertNull(brokers.receive("QM1", "IN", 200), "the message is still on the input queue");
        Await.until("the message is counted", () -> status("protect").sentToErrorQueue() == 1);
        assertNotNull(status("protect").lastError());
    }

    @Test
    @DisplayName("Without an error queue, a message that cannot be transformed is rolled back, not lost")
    void invalidMessageIsRolledBackWithoutErrorQueue() throws Exception {
        start(route("protect", "Protect", endpoint("QM1", "IN"), endpoint("QM1", "OUT"), null));

        brokers.sendText("QM1", "IN", "this is not JSON");

        Await.until("the message is rolled back several times", () -> status("protect").rolledBack() >= 2);
        service.shutdown();

        TestBrokers.Received back = brokers.receive("QM1", "IN", 2_000);
        assertNotNull(back, "the message was lost");
        assertEquals("this is not JSON", back.body());
        assertNull(brokers.receive("QM1", "OUT", 200), "something went to the output queue");
    }

    @Test
    @DisplayName("A transient failure rolls back, reconnects and transforms again, in order and without loss")
    void transientFailureIsRetried() throws Exception {
        start(route("protect", "Protect", endpoint("QM1", "IN"), endpoint("QM1", "OUT"), endpoint("QM1", "ERR")));
        transformer.failNext(2, payload -> payload.contains("second"), new IOException("engine unreachable"));

        brokers.send("QM1", "IN", session -> correlated(session.createTextMessage("{\"name\":\"first\"}"), "1"));
        brokers.send("QM1", "IN", session -> correlated(session.createTextMessage("{\"name\":\"second\"}"), "2"));

        TestBrokers.Received first = brokers.receive("QM1", "OUT", 10_000);
        TestBrokers.Received second = brokers.receive("QM1", "OUT", 10_000);
        assertNotNull(first);
        assertNotNull(second, "the failing message was lost");
        assertEquals("1", first.correlationId());
        assertEquals("2", second.correlationId());
        assertNull(brokers.receive("QM1", "OUT", 300), "a message was transformed twice");
        assertNull(brokers.receive("QM1", "ERR", 200), "a transient failure went to the error queue");

        Await.until("the route is back", () -> service.route("protect").orElseThrow().state() == QueueRoute.State.RUNNING);
        assertTrue(status("protect").lastError().contains("engine unreachable"), status("protect").lastError());
        assertEquals(2, status("protect").transformed());
    }

    @Test
    @DisplayName("A queue manager going down pauses the route, which resumes once it is back")
    void reconnectsToAQueueManagerThatComesBack() throws Exception {
        start(route("protect", "Protect", endpoint("QM1", "IN"), endpoint("QM2", "OUT"), null));

        brokers.stop("QM2");
        brokers.sendText("QM1", "IN", PERSON);
        Await.until("the route reconnects",
                () -> service.route("protect").orElseThrow().state() == QueueRoute.State.RECONNECTING);

        brokers.start("QM2");
        TestBrokers.Received out = brokers.receive("QM2", "OUT", 10_000);
        assertNotNull(out, "the message was not transformed once QM2 was back");
        assertProtected(out.body());
        Await.until("the route is running again",
                () -> service.route("protect").orElseThrow().state() == QueueRoute.State.RUNNING);
    }

    @Test
    @DisplayName("Several consumers share the connection and transform every message once")
    void concurrentConsumers() throws Exception {
        QueueRouteConfig route = route("protect", "Protect", endpoint("QM1", "IN"), endpoint("QM1", "OUT"), null);
        route.setConcurrency(3);
        start(route);
        assertEquals(3, status("protect").connectedConsumers());

        for (int i = 0; i < 30; i++) {
            String id = String.valueOf(i);
            brokers.send("QM1", "IN", session -> correlated(session.createTextMessage(PERSON), id));
        }
        Set<String> received = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            TestBrokers.Received out = brokers.receive("QM1", "OUT", 10_000);
            assertNotNull(out, "only " + received.size() + " messages transformed");
            assertTrue(received.add(out.correlationId()), "transformed twice: " + out.correlationId());
        }
        assertEquals(1, connections.openConnections());
    }

    @Test
    @DisplayName("Stopping closes the consumers and the connections")
    void shutdownStopsEverything() {
        start(route("protect", "Protect", endpoint("QM1", "IN"), endpoint("QM2", "OUT"), null));
        assertEquals(2, connections.openConnections());

        service.shutdown();

        assertEquals(QueueRoute.State.STOPPED, service.route("protect").orElseThrow().state());
        assertEquals(0, connections.openConnections());
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.getName().startsWith("rgd-queue-") && thread.isAlive()),
                "a consumer thread is still alive");
    }

    private static TextMessage correlated(TextMessage message, String id) throws jakarta.jms.JMSException {
        message.setJMSCorrelationID(id);
        return message;
    }
}
