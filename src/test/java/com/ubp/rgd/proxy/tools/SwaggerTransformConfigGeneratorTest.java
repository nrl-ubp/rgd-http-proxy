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

        assertTrue(configs.stream()
                .anyMatch(c -> c.getEndpointPath().equals("/api/v1/persons")
                        && c.getEndpointMethods().equals(List.of("POST"))));
        assertTrue(configs.stream()
                .anyMatch(c -> c.getEndpointPath().equals("/api/v1/persons/[^/]+")
                        && c.getEndpointMethods().equals(List.of("PATCH"))));
    }

    private EndPointTransformConfig personConfig(String path, String method) throws Exception {
        return generate().stream()
                .filter(c -> c.getEndpointPath().equals(path)
                        && c.getEndpointMethods().equals(List.of(method)))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void testGeneratesConfigForAnnotatedPersonEndpoints() throws Exception {
        // Sensitive fields are annotated on the request payloads (PersonCreateRequest / PersonPatch),
        // so the person endpoints are the non-GET operations, applied BEFORE.
        EndPointTransformConfig post = personConfig("/v1/persons", "POST");
        EndPointTransformConfig patch = personConfig("/v1/persons/[^/]+", "PATCH");

        for (EndPointTransformConfig cfg : List.of(post, patch)) {
            assertEquals("BEFORE", cfg.getEndpointTransformWhen());
            assertFalse(cfg.getRightContextEvidences().isEmpty(), "right-context must NOT be empty");
            assertFalse(cfg.getProcessingContextEvidences().isEmpty(), "processing-context must NOT be empty");
            assertEquals(3, cfg.getEntityTransformConfigs().size(),
                    "PersonCreateRequest/PersonPatch expose 3 sensitive fields");
        }
    }

    @Test
    void testCidAnnotationsMapToEntityConfig() throws Exception {
        Map<String, EntityTransformConfig> byPath = personConfig("/v1/persons", "POST")
                .getEntityTransformConfigs().stream()
                .collect(Collectors.toMap(EntityTransformConfig::getJsonPath, e -> e));

        // x-cid-classname / x-cid-propertyname map to rps-class-name / rps-property-name.
        EntityTransformConfig firstName = byPath.get("$.firstName");
        assertNotNull(firstName);
        assertEquals("Person", firstName.getRpsClassName());
        assertEquals("ShortString", firstName.getRpsPropertyName());
        // x-cid-extractregex is captured into the new extract-regex field.
        assertEquals("(?:\\w(?<!_)|~)+", firstName.getExtractRegex());

        EntityTransformConfig lastName = byPath.get("$.lastName");
        assertNotNull(lastName);
        assertEquals("(?:\\w(?<!_)|~)+", lastName.getExtractRegex());
    }

    @Test
    void testNullExtractRegexIsOmitted() throws Exception {
        Map<String, EntityTransformConfig> byPath = personConfig("/v1/persons", "POST")
                .getEntityTransformConfigs().stream()
                .collect(Collectors.toMap(EntityTransformConfig::getJsonPath, e -> e));

        // birthDate has x-cid-extractregex: null -> the field stays null (and is not serialized).
        EntityTransformConfig birthDate = byPath.get("$.birthDate");
        assertNotNull(birthDate);
        assertEquals("Person", birthDate.getRpsClassName());
        assertEquals("Date", birthDate.getRpsPropertyName());
        assertNull(birthDate.getExtractRegex());
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
