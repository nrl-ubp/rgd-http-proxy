package com.ubp.rgd.proxy.queue.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueConfigLoaderTest {

    private static final Function<String, Optional<String>> PROPERTIES = name -> Optional.ofNullable(Map.of(
            "proxy.queue.credentials.gva.user", "rgdapp",
            "proxy.queue.credentials.gva.password", "secret").get(name));

    @TempDir
    Path dir;

    @Test
    @DisplayName("The sample configuration of the application is valid")
    void sampleConfigurationLoads() {
        QueueConfiguration configuration = QueueConfigLoader.load(
                "../rgd-proxy-hub/config/mq_transform_config.json", PROPERTIES.andThen(value -> value.or(() -> Optional.of("x"))));

        assertTrue(configuration.routes().size() >= 1);
    }

    @Test
    @DisplayName("A route is read with its endpoints, contexts and transformations, and its credentials resolved")
    void routeIsRead() throws Exception {
        Path file = dir.resolve("routes.json");
        Files.writeString(file, """
                [{
                  "name": "protect_clients",
                  "concurrency": 2,
                  "input":  {"host": "mq-gva", "port": 1414, "queue-manager": "QMGVA01", "channel": "RGD.SVRCONN",
                             "queue": "RGD.CLIENTS.IN", "credentials": "gva", "cipher-suite": "TLS_AES_256_GCM_SHA384"},
                  "output": {"host": "mq-lux", "queue-manager": "QMLUX01", "channel": "RGD.SVRCONN", "queue": "RGD.CLIENTS.OUT"},
                  "right-context": {"Module": "QueueProxy"},
                  "processing-context": {"Action": "Protect"},
                  "entity-transform-configs": [
                    {"json-path": "$.client.name", "rps-class-name": "Person", "rps-property-name": "ShortString"}
                  ]
                }]
                """);

        QueueConfiguration configuration = QueueConfigLoader.load(file.toString(), PROPERTIES);

        QueueRouteConfig route = configuration.routes().get(0);
        assertEquals("protect_clients", route.getName());
        assertEquals(2, route.getConcurrency());
        assertEquals("QMGVA01", route.getInput().getQueueManager());
        assertEquals(1414, route.getOutput().getPort(), "the port defaults to 1414");
        assertNull(route.getError());
        assertEquals("ShortString", route.sortAttributeTransformsConfig().get("$.client.name").getRpsPropertyName());
        assertEquals(new QueueCredentials("rgdapp", "secret"), configuration.credentials("gva"));
        assertNull(configuration.credentials(route.getOutput().getCredentials()));
    }

    @Test
    @DisplayName("Every defect is reported, and stops the application")
    void defectsAreReportedTogether() throws Exception {
        Path file = dir.resolve("routes.json");
        Files.writeString(file, """
                [
                  {"name": "a", "concurrency": 0,
                   "input":  {"host": "h", "queue-manager": "QM", "channel": "C", "queue": "Q", "credentials": "unknown"},
                   "output": {"host": "h", "queue-manager": "QM", "channel": "C", "queue": "Q", "credentials": "unknown"},
                   "processing-context": {"Action": "Encrypt"}},
                  {"name": "a",
                   "input":  {"host": "h", "queue-manager": "QM", "channel": "C", "queue": "IN"},
                   "processing-context": {"Action": "Unprotect"}}
                ]
                """);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> QueueConfigLoader.load(file.toString(), PROPERTIES));

        String message = failure.getMessage();
        assertTrue(message.contains("concurrency must be at least 1"), message);
        assertTrue(message.contains("Action must be Protect or Unprotect"), message);
        assertTrue(message.contains("credentials 'unknown' has no proxy.queue.credentials.unknown.user"), message);
        assertTrue(message.contains("input and output are the same queue"), message);
        assertTrue(message.contains("the name is used by another route"), message);
        assertTrue(message.contains("route 'a': output is missing"), message);
    }

    @Test
    @DisplayName("A missing file stops the application")
    void missingFile() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> QueueConfigLoader.load(dir.resolve("nope.json").toString(), PROPERTIES));
        assertTrue(failure.getMessage().contains("does not exist"), failure.getMessage());
    }
}
