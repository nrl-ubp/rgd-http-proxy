package com.ubp.rgd.proxy.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the argument handling and the exit codes of {@link FileTransformTriggerApp}, without a
 * server. The successful path is exercised against a real running server by
 * {@code ProxyServiceTest.testTriggerFileProtection}.
 */
class FileTransformTriggerAppTest {

    @Test
    @DisplayName("The server defaults to localhost:8080")
    void defaultsToLocalhost8080() {
        assertEquals(URI.create("http://localhost:8080/api/file-transform/trigger/My%20Config"),
                FileTransformTriggerApp.triggerUri("localhost:8080", "My Config"));
    }

    @Test
    @DisplayName("The configuration name is URL encoded, since names contain spaces")
    void encodesTheConfigurationName() {
        // A raw '+' would be read back as a space by the server, so the encoder's '+' must become %20.
        URI uri = FileTransformTriggerApp.triggerUri("proxy-host:8443", "Person Data Protection");

        assertEquals("http://proxy-host:8443/api/file-transform/trigger/Person%20Data%20Protection",
                uri.toString());
    }

    @Test
    @DisplayName("A malformed server is rejected")
    void rejectsAMalformedServer() {
        assertThrows(IllegalArgumentException.class,
                () -> FileTransformTriggerApp.triggerUri("localhost", "cfg"));
        assertThrows(IllegalArgumentException.class,
                () -> FileTransformTriggerApp.triggerUri("localhost:", "cfg"));
        assertThrows(IllegalArgumentException.class,
                () -> FileTransformTriggerApp.triggerUri("localhost:not-a-port", "cfg"));
        assertThrows(IllegalArgumentException.class,
                () -> FileTransformTriggerApp.triggerUri("localhost:70000", "cfg"));
    }

    @Test
    @DisplayName("Missing or excess arguments are an argument error")
    void rejectsWrongArguments() {
        assertEquals(FileTransformTriggerApp.EXIT_INVALID_ARGS,
                FileTransformTriggerApp.run(new String[0]));
        assertEquals(FileTransformTriggerApp.EXIT_INVALID_ARGS,
                FileTransformTriggerApp.run(new String[]{"  "}));
        assertEquals(FileTransformTriggerApp.EXIT_INVALID_ARGS,
                FileTransformTriggerApp.run(new String[]{"cfg", "localhost:8080", "extra"}));
        assertEquals(FileTransformTriggerApp.EXIT_INVALID_ARGS,
                FileTransformTriggerApp.run(new String[]{"cfg", "localhost"}));
    }

    @Test
    @DisplayName("A server that is not there is reported as unreachable, not as a success")
    void reportsAnUnreachableServer() {
        // Port 1 is reserved and nothing listens on it, so the connection is refused immediately.
        assertEquals(FileTransformTriggerApp.EXIT_SERVER_UNREACHABLE,
                FileTransformTriggerApp.run(new String[]{"whatever", "localhost:1"}));
    }

    /**
     * Serve one canned answer on a free port, so the exit code mapping can be checked without a
     * Quarkus server.
     */
    private int exitCodeFor(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] answer = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.start();

        try {
            return FileTransformTriggerApp.run(
                    new String[]{"a config", "localhost:" + server.getAddress().getPort()});
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("A run where every file succeeded exits 0")
    void exitsZeroWhenEverythingSucceeded() throws IOException {
        assertEquals(FileTransformTriggerApp.EXIT_SUCCESS,
                exitCodeFor(200, "{\"filesProcessed\":3,\"filesSucceeded\":3,\"filesFailed\":0,"
                        + "\"errors\":[],\"status\":\"success\"}"));
    }

    @Test
    @DisplayName("A run where every file failed exits 4")
    void exitsFourWhenEverythingFailed() throws IOException {
        assertEquals(FileTransformTriggerApp.EXIT_PROCESSING_ERROR,
                exitCodeFor(500, "{\"filesProcessed\":2,\"filesSucceeded\":0,\"filesFailed\":2,"
                        + "\"errors\":[\"a.json: broken\",\"b.json: broken\"],\"status\":\"error\"}"));
    }

    @Test
    @DisplayName("A run where only some files failed exits 5")
    void exitsFiveOnAPartialRun() throws IOException {
        assertEquals(FileTransformTriggerApp.EXIT_PARTIAL_FAILURE,
                exitCodeFor(500, "{\"filesProcessed\":3,\"filesSucceeded\":2,\"filesFailed\":1,"
                        + "\"errors\":[\"b.json: broken\"],\"status\":\"partial\"}"));
    }

    @Test
    @DisplayName("A run that matched no file exits 6")
    void exitsSixWhenNothingWasFound() throws IOException {
        assertEquals(FileTransformTriggerApp.EXIT_NO_FILE,
                exitCodeFor(200, "{\"filesProcessed\":0,\"filesSucceeded\":0,\"filesFailed\":0,"
                        + "\"errors\":[],\"status\":\"empty\"}"));
    }

    @Test
    @DisplayName("A source directory that could not be scanned exits 4, not 6")
    void exitsFourWhenTheRunCouldNotStart() throws IOException {
        // No file failed because none could even be listed: without looking at the errors this would
        // be indistinguishable from an empty run.
        assertEquals(FileTransformTriggerApp.EXIT_PROCESSING_ERROR,
                exitCodeFor(500, "{\"filesProcessed\":0,\"filesSucceeded\":0,\"filesFailed\":0,"
                        + "\"errors\":[\"Cannot scan the source directory /nope\"],\"status\":\"error\"}"));
    }

    @Test
    @DisplayName("A configuration driven by the scheduler exits 7")
    void exitsSevenOnAScheduledConfiguration() throws IOException {
        assertEquals(FileTransformTriggerApp.EXIT_SCHEDULED_CONFIG,
                exitCodeFor(409, "{\"error\":\"Configuration 'x' is processed by the scheduler\"}"));
    }

    @Test
    @DisplayName("An unknown configuration exits 1")
    void exitsOneOnAnUnknownConfiguration() throws IOException {
        assertEquals(FileTransformTriggerApp.EXIT_CONFIG_NOT_FOUND,
                exitCodeFor(404, "{\"error\":\"Configuration not found: x\"}"));
    }

    @Test
    @DisplayName("An answer that cannot be understood is an error, never a success")
    void neverExitsZeroOnAnUnreadableAnswer() throws IOException {
        assertEquals(FileTransformTriggerApp.EXIT_PROCESSING_ERROR,
                exitCodeFor(200, "this is not json"));
        // A 200 without the counts: the server did something else than what this app expects.
        assertEquals(FileTransformTriggerApp.EXIT_PROCESSING_ERROR,
                exitCodeFor(200, "{\"status\":\"success\"}"));
        assertEquals(FileTransformTriggerApp.EXIT_PROCESSING_ERROR,
                exitCodeFor(503, "Service Unavailable"));
    }
}
