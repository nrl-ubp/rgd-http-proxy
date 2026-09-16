package com.ubp.rgd.proxy.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

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
}
