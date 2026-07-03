package com.ubp.rgd.proxy.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SwaggerTransformConfigGeneratorTest {

    private static final String SWAGGER_FILE = "./config/wdx1-swagger.json";

    private List<EndPointTransformConfig> generate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.readTree(new File(SWAGGER_FILE));
        return new SwaggerTransformConfigGenerator(root).generate();
    }

    @Test
    void testPathRegexConversion() {
        assertEquals("/v1/persons", SwaggerTransformConfigGenerator.toPathRegex("/v1/persons"));
        assertEquals("/v1/persons/[^/]+", SwaggerTransformConfigGenerator.toPathRegex("/v1/persons/{id}"));
        assertEquals("/v1/a/[^/]+/b/[^/]+",
                SwaggerTransformConfigGenerator.toPathRegex("/v1/a/{x}/b/{y}"));
    }

    @Test
    void testPathPrefixIsApplied() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.readTree(new File(SWAGGER_FILE));
        List<EndPointTransformConfig> configs =
                new SwaggerTransformConfigGenerator(root, "/api").generate();

        assertEquals(2, configs.size());
        assertTrue(configs.stream().anyMatch(c -> c.getEndpointPath().equals("/api/v1/persons")));
        assertTrue(configs.stream().anyMatch(c -> c.getEndpointPath().equals("/api/v1/persons/[^/]+")));
    }

    @Test
    void testGeneratesConfigForAnnotatedPersonEndpoints() throws Exception {
        List<EndPointTransformConfig> configs = generate();

        // Only the two GET endpoints referencing the annotated PersonResponse should be produced.
        assertEquals(2, configs.size(), "Expected exactly two endpoint configurations");

        // All discovered endpoints are GET -> AFTER.
        configs.forEach(cfg -> {
            assertEquals(List.of("GET"), cfg.getEndpointMethods());
            assertEquals("AFTER", cfg.getEndpointTransformWhen());
            assertTrue(cfg.getRightContextEvidences().isEmpty(), "right-context must be empty");
            assertTrue(cfg.getProcessingContextEvidences().isEmpty(), "processing-context must be empty");
            assertEquals(9, cfg.getEntityTransformConfigs().size(),
                    "PersonResponse exposes 9 sensitive fields");
        });
    }

    @Test
    void testCollectionEndpointJsonPaths() throws Exception {
        EndPointTransformConfig collectionCfg = generate().stream()
                .filter(c -> c.getEndpointPath().equals("/v1/persons"))
                .findFirst()
                .orElseThrow();

        Map<String, EntityTransformConfig> byPath = collectionCfg.getEntityTransformConfigs().stream()
                .collect(Collectors.toMap(EntityTransformConfig::getJsonPath, e -> e));

        // Collection responses are wrapped in items[*].
        assertTrue(byPath.containsKey("$.items[*].firstName"));
        assertTrue(byPath.containsKey("$.items[*].email"));

        EntityTransformConfig email = byPath.get("$.items[*].email");
        assertEquals("Person", email.getRpsClassName());
        assertEquals("email", email.getRpsPropertyName());

        EntityTransformConfig phone = byPath.get("$.items[*].mobilePhone");
        assertEquals("Person", phone.getRpsClassName());
        assertEquals("phoneNumber", phone.getRpsPropertyName());
    }

    @Test
    void testSingleObjectEndpointJsonPaths() throws Exception {
        EndPointTransformConfig byIdCfg = generate().stream()
                .filter(c -> c.getEndpointPath().equals("/v1/persons/[^/]+"))
                .findFirst()
                .orElseThrow();

        Map<String, EntityTransformConfig> byPath = byIdCfg.getEntityTransformConfigs().stream()
                .collect(Collectors.toMap(EntityTransformConfig::getJsonPath, e -> e));

        // Single object responses are addressed directly from the root.
        assertTrue(byPath.containsKey("$.lastName"));
        assertTrue(byPath.containsKey("$.birthDate"));

        EntityTransformConfig birthDate = byPath.get("$.birthDate");
        assertEquals("Person", birthDate.getRpsClassName());
        assertEquals("birthDate", birthDate.getRpsPropertyName());
    }

    @Test
    void testFileNameFromUrl() {
        assertEquals("wdx1-swagger.json",
                SwaggerTransformConfigGenerator.fileNameFromUrl("http://localhost:8080/wdx1-swagger.json"));
        // No .json extension -> one is appended.
        assertEquals("openapi.json",
                SwaggerTransformConfigGenerator.fileNameFromUrl("http://localhost:8080/q/openapi"));
        // No usable last segment -> default name.
        assertEquals("swagger.json",
                SwaggerTransformConfigGenerator.fileNameFromUrl("http://localhost:8080/"));
        // Query string is ignored (not part of the path).
        assertEquals("spec.json",
                SwaggerTransformConfigGenerator.fileNameFromUrl("http://host/a/b/spec.json?format=json"));
    }

    @Test
    void testDownloadSwaggerFromLocalServer() throws Exception {
        byte[] payload = "{\"openapi\":\"3.0.1\",\"paths\":{}}".getBytes();

        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/downloaded-swagger.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (var os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();

        // The file is downloaded into the current working directory; clean it up afterwards.
        try {
            int port = server.getAddress().getPort();
            String url = "http://localhost:" + port + "/downloaded-swagger.json";

            String localFile = SwaggerTransformConfigGenerator.downloadSwagger(url);

            assertTrue(new File(localFile).exists(), "Downloaded file should exist");
            assertTrue(localFile.endsWith("downloaded-swagger.json"));
            assertArrayEquals(payload, Files.readAllBytes(Path.of(localFile)));
        } finally {
            server.stop(0);
            Files.deleteIfExists(Path.of("downloaded-swagger.json"));
        }
    }
}
