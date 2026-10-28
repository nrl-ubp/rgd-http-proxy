package com.ubp.rgd.proxy.wiring;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a configuration with a scan interval is scanned as soon as the application has started,
 * without anything calling {@code /api/file-transform} or injecting the service. The files are
 * transformed with FPE, which needs no engine.
 */
@QuarkusTest
@TestProfile(FileTransformStartupTest.ScheduledFileProfile.class)
class FileTransformStartupTest {

    private static final Path ROOT = Path.of("target", "file-transform-startup");
    private static final Path CONFIG = ROOT.resolve("file_transform_config.json");
    private static final String CLEAR_NAME = "Bernadette";

    public static class ScheduledFileProfile extends FPETransformerWiringTest.FpeProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> props = super.getConfigOverrides();
            props.put("proxy.file-transform.config-file", CONFIG.toString());
            return props;
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(WaitingFile.class));
        }
    }

    /** Drops the file and its configuration before the application starts, since its scheduler picks it up straight away. */
    public static class WaitingFile implements QuarkusTestResourceLifecycleManager {

        @Override
        public Map<String, String> start() {
            try {
                if (Files.exists(ROOT)) {
                    try (Stream<Path> paths = Files.walk(ROOT)) {
                        paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                    }
                }
                Files.createDirectories(ROOT.resolve("in"));
                Files.writeString(ROOT.resolve("in/person.json"), "{\"name\":\"" + CLEAR_NAME + "\"}");
                Files.writeString(CONFIG, """
                        [
                          {
                            "name": "scheduled",
                            "source-directory": "%1$s/in",
                            "target-directory": "%1$s/out",
                            "error-directory": "%1$s/error",
                            "scan-interval-seconds": 3600,
                            "right-context": {"Module": "FileTransform"},
                            "processing-context": {"Action": "Protect"},
                            "entity-transform-configs": [
                              {"json-path": "$.name", "rps-class-name": "Person", "rps-property-name": "FirstName"}
                            ]
                          }
                        ]
                        """.formatted(ROOT.toString().replace('\\', '/')));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return Map.of();
        }

        @Override
        public void stop() {
        }
    }

    @Test
    @DisplayName("A scheduled configuration transforms the waiting file at startup")
    void waitingFileIsTransformedAtStartup() throws Exception {
        Path target = ROOT.resolve("out/person.json");
        long deadline = System.currentTimeMillis() + 15_000;
        while (!Files.exists(target) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertTrue(Files.exists(target), "the file was not transformed");
        assertFalse(Files.readString(target).contains(CLEAR_NAME), "the name is still in clear");
        assertFalse(Files.exists(ROOT.resolve("in/person.json")), "the file is still in the source directory");
    }
}
