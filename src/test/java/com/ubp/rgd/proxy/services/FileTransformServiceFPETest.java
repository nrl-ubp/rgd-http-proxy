package com.ubp.rgd.proxy.services;

import com.ubp.rgd.proxy.transform.FPEEndPointTransformer;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import com.ubp.rgd.proxy.transform.config.FileTransformConfig;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link FileTransformService} with the FPE transformer, so the file transformation is
 * checked end to end without a running RPS engine.
 * <p>
 * The point of these tests is that the file transformation now goes through the very same
 * {@code EndPointTransformer} as the HTTP proxy and the {@code /transform} endpoint, which is what
 * makes {@code proxy.transform.impl} apply to files as well.
 */
class FileTransformServiceFPETest {

    private static final String KEY = "000102030405060708090A0B0C0D0E0F";

    @TempDir
    Path root;

    private FileTransformService service;
    private FPEEndPointTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = FPEEndPointTransformer.withKey(KEY);
        service = new FileTransformService();
        service.transformer = transformer;
    }

    /**
     * Build a configuration rooted in the temporary directory.
     *
     * @param name   the configuration name, also used as the directory prefix
     * @param action {@code Protect} or {@code Unprotect}
     * @return the configuration, with its directories created
     */
    private FileTransformConfig config(String name, String action) throws IOException {
        FileTransformConfig cfg = new FileTransformConfig();
        cfg.setName(name);
        cfg.setSourceDirectory(root.resolve(name + "/in").toString());
        cfg.setTargetDirectory(root.resolve(name + "/out").toString());
        cfg.setErrorDirectory(root.resolve(name + "/error").toString());
        cfg.setWorkInProgressSuffix(".wip");
        cfg.setFilePattern(".*\\.json");
        cfg.setScanIntervalSeconds(0);
        cfg.setRightContextEvidences(new HashMap<>(Map.of("Module", "FileTransform")));
        cfg.setProcessingContextEvidences(new HashMap<>(Map.of("Action", action)));

        EntityTransformConfig name0 = new EntityTransformConfig();
        name0.setJsonPath("$.name");
        name0.setRpsClassName("Person");
        name0.setRpsPropertyName("FirstName");

        EntityTransformConfig account = new EntityTransformConfig();
        account.setJsonPath("$.account");
        account.setRpsClassName("Person");
        account.setRpsPropertyName("Account");

        cfg.setEntityTransformConfigs(Set.of(name0, account));

        Files.createDirectories(Path.of(cfg.getSourceDirectory()));
        Files.createDirectories(Path.of(cfg.getTargetDirectory()));
        Files.createDirectories(Path.of(cfg.getErrorDirectory()));
        return cfg;
    }

    /**
     * Drop a file in the source directory and run the configuration once.
     *
     * @param cfg          the configuration to run
     * @param relativePath the path of the file, relative to the source directory
     * @param content      the content of the file
     * @return the outcome of the run
     */
    private FileTransformResult run(FileTransformConfig cfg, String relativePath, String content) throws IOException {
        Path file = Path.of(cfg.getSourceDirectory()).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);

        service.fileTransformConfigs = List.of(cfg);
        return service.processConfigurationByName(cfg.getName());
    }

    private static String read(FileTransformConfig cfg, String relativePath) throws IOException {
        return Files.readString(Path.of(cfg.getTargetDirectory()).resolve(relativePath));
    }

    @Test
    @DisplayName("A file is protected with the configured transformer and lands in the target directory")
    void protectsAFile() throws Exception {
        FileTransformConfig cfg = config("protect", "Protect");

        assertEquals(1, run(cfg, "person.json", "{\"name\":\"Bernadette\",\"account\":\"123456789\"}").filesSucceeded());

        String result = read(cfg, "person.json");
        assertTrue(result.contains("RG{"), "Values should be protected: " + result);
        assertFalse(result.contains("Bernadette"), "The clear value should be gone: " + result);
        assertFalse(result.contains("123456789"), "The clear account should be gone: " + result);

        // Neither the source file nor the work in progress file survives a successful run.
        assertFalse(Files.exists(Path.of(cfg.getSourceDirectory(), "person.json")));
        assertFalse(Files.exists(Path.of(cfg.getSourceDirectory(), "person.json.wip")));
        try (var errors = Files.list(Path.of(cfg.getErrorDirectory()))) {
            assertEquals(0, errors.count());
        }
    }

    @Test
    @DisplayName("Protecting then unprotecting a file gives the original content back")
    void roundTripsAFile() throws Exception {
        FileTransformConfig protect = config("rt-protect", "Protect");
        run(protect, "person.json", "{\"name\":\"Bernadette\",\"account\":\"123456789\"}");
        String protectedContent = read(protect, "person.json");

        FileTransformConfig unprotect = config("rt-unprotect", "Unprotect");
        assertEquals(1, run(unprotect, "person.json", protectedContent).filesSucceeded());

        String clear = read(unprotect, "person.json");
        assertTrue(clear.contains("Bernadette"), clear);
        assertTrue(clear.contains("123456789"), clear);
        assertFalse(clear.contains("RG{"), clear);
    }

    @Test
    @DisplayName("A file that cannot be transformed is moved to the error directory")
    void movesAFailedFileToTheErrorDirectory() throws Exception {
        FileTransformConfig cfg = config("error", "Protect");

        FileTransformResult result = run(cfg, "broken.json", "{ this is not json");

        // The run must report the failure: a file that failed is not a file that was processed.
        assertEquals(0, result.filesSucceeded());
        assertEquals(1, result.filesFailed());
        assertTrue(result.hasFailures());
        assertEquals("error", result.status());
        assertEquals(1, result.errors().size(), "The failure should be described: " + result.errors());
        assertTrue(result.errors().getFirst().contains("broken.json"), result.errors().getFirst());

        assertTrue(Files.exists(Path.of(cfg.getErrorDirectory(), "broken.json")),
                "The failed file should be in the error directory");
        assertFalse(Files.exists(Path.of(cfg.getTargetDirectory(), "broken.json")),
                "A failed file must not reach the target directory");
        // The work in progress file is moved, not copied, so nothing is left behind in the source.
        assertFalse(Files.exists(Path.of(cfg.getSourceDirectory(), "broken.json.wip")));
    }

    @Test
    @DisplayName("A run mixing good and bad files is reported as partial")
    void reportsAPartialRun() throws Exception {
        FileTransformConfig cfg = config("partial", "Protect");

        Path source = Path.of(cfg.getSourceDirectory());
        Files.writeString(source.resolve("good.json"), "{\"name\":\"Bernadette\",\"account\":\"1\"}");
        Files.writeString(source.resolve("broken.json"), "{ this is not json");

        service.fileTransformConfigs = List.of(cfg);
        FileTransformResult result = service.processConfigurationByName(cfg.getName());

        assertEquals(1, result.filesSucceeded());
        assertEquals(1, result.filesFailed());
        assertEquals(2, result.filesProcessed());
        assertEquals("partial", result.status());
    }

    @Test
    @DisplayName("A configuration matching no file is reported as empty, not as a success")
    void reportsAnEmptyRun() throws Exception {
        FileTransformConfig cfg = config("empty", "Protect");

        service.fileTransformConfigs = List.of(cfg);
        FileTransformResult result = service.processConfigurationByName(cfg.getName());

        assertEquals(0, result.filesProcessed());
        assertTrue(result.isEmpty());
        assertFalse(result.hasFailures());
        assertEquals("empty", result.status());
    }

    @Test
    @DisplayName("A source directory that cannot be scanned is a failed run, not an empty one")
    void reportsAnUnscannableSourceDirectory() throws Exception {
        FileTransformConfig cfg = config("unscannable", "Protect");
        Files.delete(Path.of(cfg.getSourceDirectory()));

        service.fileTransformConfigs = List.of(cfg);
        FileTransformResult result = service.processConfigurationByName(cfg.getName());

        assertTrue(result.hasFailures(), "A missing source directory must not look like an empty run");
        assertFalse(result.isEmpty());
        assertEquals("error", result.status());
    }

    @Test
    @DisplayName("A configuration driven by the scheduler cannot be triggered on demand")
    void refusesToTriggerAScheduledConfiguration() throws Exception {
        FileTransformConfig cfg = config("scheduled", "Protect");
        cfg.setScanIntervalSeconds(30);

        service.fileTransformConfigs = List.of(cfg);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.processConfigurationByName(cfg.getName()));

        assertTrue(thrown.getMessage().contains("scheduler"), thrown.getMessage());
    }

    @Test
    @DisplayName("The directory tree of the source is preserved in the target")
    void preservesTheDirectoryTree() throws Exception {
        FileTransformConfig cfg = config("tree", "Protect");
        cfg.setPreserveDirectoryStructure(true);

        assertEquals(1, run(cfg, "2026/09/person.json", "{\"name\":\"Bernadette\",\"account\":\"123456789\"}").filesSucceeded());

        assertTrue(read(cfg, "2026/09/person.json").contains("RG{"));
    }

    @Test
    @DisplayName("A value protected in a file is readable by the proxy transformer")
    void fileAndProxyAreInteroperable() throws Exception {
        // The file transformation and the HTTP proxy share the same transformer instance and the
        // same per property tweak, so a file protected offline can be served detokenized online.
        FileTransformConfig cfg = config("interop", "Protect");
        run(cfg, "person.json", "{\"name\":\"Bernadette\",\"account\":\"123456789\"}");
        String protectedContent = read(cfg, "person.json");

        EndPointTransformConfig endpoint = new EndPointTransformConfig();
        endpoint.setEndpointTransformWhen("AFTER");
        endpoint.setProcessingContextEvidences(Map.of("Action", "Unprotect"));

        EntityTransformConfig entity = new EntityTransformConfig();
        entity.setJsonPath("$.name");
        entity.setRpsClassName("Person");
        entity.setRpsPropertyName("FirstName");
        endpoint.setEntityTransformConfigs(Set.of(entity));

        String clear = transformer.transform(protectedContent,
                new MultivaluedHashMap<>(), new MultivaluedHashMap<>(), endpoint);

        assertTrue(clear.contains("Bernadette"), clear);
    }

    @Test
    @DisplayName("A configuration without any entity transform leaves the file untouched")
    void leavesAFileWithoutConfigurationUntouched() throws Exception {
        FileTransformConfig cfg = config("noop", "Protect");
        cfg.setEntityTransformConfigs(Set.of());

        String content = "{\"name\":\"Bernadette\"}";
        assertEquals(1, run(cfg, "person.json", content).filesSucceeded());

        assertEquals(content, read(cfg, "person.json"));
    }
}
