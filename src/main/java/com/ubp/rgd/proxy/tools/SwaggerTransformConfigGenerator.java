package com.ubp.rgd.proxy.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import com.ubp.rgd.proxy.utils.JSONFile;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates an rps_transform_config.json (the ProxyService transform configuration)
 * from a Swagger / OpenAPI v3 specification.
 * <p>
 * Rules implemented:
 * <ul>
 *     <li>GET operations produce a transform config applied AFTER (the response is protected).</li>
 *     <li>Any other operation (POST, PUT, PATCH, DELETE ...) produces a config applied BEFORE
 *         (the request payload is transformed before being forwarded).</li>
 *     <li>{@code processing-context} and {@code right-context} are left empty for each endpoint.</li>
 *     <li>Sensitive fields are discovered through the {@code x-cid-classname},
 *         {@code x-cid-propertyname} and {@code x-cid-extractregex} vendor extensions found on
 *         schema properties. Each one produces an entry under {@code entity-transform-configs}
 *         with the matching JSON path (and the extract regex when present).</li>
 * </ul>
 * Only endpoints that expose at least one sensitive field generate a configuration entry.
 * <p>
 * Usage:
 * <pre>
 *   java com.ubp.rgd.proxy.tools.SwaggerTransformConfigGenerator \
 *        --swagger-file swagger_file_name.json \
 *        --output-file another_transform_config.json
 * </pre>
 */
public class SwaggerTransformConfigGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(SwaggerTransformConfigGenerator.class);

    // CID (sensitivity) vendor extensions describing a protected field.
    private static final String CID_CLASSNAME = "x-cid-classname";
    private static final String CID_PROPERTYNAME = "x-cid-propertyname";
    private static final String CID_EXTRACT_REGEX = "x-cid-extractregex";
    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "options", "head", "trace");
    private static final int MAX_DEPTH = 30;

    /** Root of the loaded OpenAPI document, used to resolve {@code $ref} pointers. */
    private final JsonNode root;

    /** Optional prefix prepended to every generated {@code endpoint-path} (may be empty). */
    private final String pathPrefix;

    public SwaggerTransformConfigGenerator(JsonNode root) {
        this(root, "");
    }

    public SwaggerTransformConfigGenerator(JsonNode root, String pathPrefix) {
        this.root = root;
        this.pathPrefix = pathPrefix == null ? "" : pathPrefix;
    }

    public static void main(String... args) {
        String swaggerFile = null;
        String swaggerUrl = null;
        String outputFile = null;
        String pathPrefix = "";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--swagger-file" -> swaggerFile = valueOf(args, ++i);
                case "--swagger-url" -> swaggerUrl = valueOf(args, ++i);
                case "--output-file" -> outputFile = valueOf(args, ++i);
                case "--path-prefix" -> pathPrefix = valueOf(args, ++i);
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    LOG.error("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(2);
                }
            }
        }

        if (outputFile == null) {
            LOG.error("--output-file is required.");
            printUsage();
            System.exit(2);
        }

        if (swaggerFile == null && swaggerUrl == null) {
            LOG.error("One of --swagger-file or --swagger-url is required.");
            printUsage();
            System.exit(2);
        }

        if (swaggerFile != null && swaggerUrl != null) {
            LOG.error("--swagger-file and --swagger-url are mutually exclusive.");
            printUsage();
            System.exit(2);
        }

        try {
            // When a URL is provided, download the Swagger file into the current
            // working directory and process that downloaded file.
            if (swaggerUrl != null) {
                swaggerFile = downloadSwagger(swaggerUrl);
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new File(swaggerFile));

            SwaggerTransformConfigGenerator generator = new SwaggerTransformConfigGenerator(root, pathPrefix);
            List<EndPointTransformConfig> configs = generator.generate();

            JSONFile.saveAs(new File(outputFile), configs);

            LOG.info("Generated {} endpoint transform configuration(s) into: {}",
                    configs.size(), outputFile);
        } catch (Exception e) {
            LOG.error("Failed to generate transform configuration", e);
            System.exit(1);
        }
    }

    /**
     * Download a Swagger / OpenAPI JSON document from the given URL into the current
     * working directory and return the local file path.
     *
     * @param swaggerUrl the URL to download the Swagger document from
     * @return the name of the downloaded file (relative to the current working directory)
     * @throws Exception if the download fails or returns a non-2xx status
     */
    static String downloadSwagger(String swaggerUrl) throws Exception {
        String fileName = fileNameFromUrl(swaggerUrl);
        Path target = Paths.get(fileName).toAbsolutePath();

        LOG.info("Downloading Swagger document from {}", swaggerUrl);

        HttpResponse<byte[]> response;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(swaggerUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());


            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "Failed to download Swagger document. HTTP status: " + response.statusCode());
            }

            Files.write(target, response.body());
            LOG.info("Downloaded Swagger document into: {}", target);

            return target.toString();
        }
    }

    /**
     * Derive a local file name from a Swagger URL. Falls back to {@code swagger.json}
     * when the URL has no usable last path segment, and ensures a {@code .json} extension.
     */
    static String fileNameFromUrl(String swaggerUrl) {
        String path = URI.create(swaggerUrl).getPath();
        String name = "";
        if (path != null && !path.isBlank()) {
            int slash = path.lastIndexOf('/');
            name = slash >= 0 ? path.substring(slash + 1) : path;
        }
        if (name.isBlank()) {
            name = "swagger.json";
        }
        if (!name.toLowerCase().endsWith(".json")) {
            name = name + ".json";
        }
        return name;
    }

    private static String valueOf(String[] args, int index) {
        if (index >= args.length) {
            LOG.error("Missing value for argument: " + args[index - 1]);
            printUsage();
            System.exit(2);
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("""
                Swagger -> RPS transform configuration generator

                Usage:
                  java com.ubp.rgd.proxy.tools.SwaggerTransformConfigGenerator \\
                       (--swagger-file <swagger_openapi.json> | --swagger-url <url>) \\
                       --output-file <rps_transform_config.json> \\
                       [--path-prefix <prefix>]

                Arguments:
                  --swagger-file  Path to the input Swagger / OpenAPI v3 JSON file
                  --swagger-url   URL to download the Swagger / OpenAPI v3 JSON file from.
                                  The file is saved in the current working directory and
                                  then processed. Mutually exclusive with --swagger-file.
                  --output-file   Path to the transform configuration file to generate
                  --path-prefix   Optional prefix prepended to every generated endpoint-path
                                  (e.g. "/api"). Defaults to none.
                """);
    }

    /**
     * Walk every operation of the OpenAPI document and build the list of endpoint transform configs.
     */
    public List<EndPointTransformConfig> generate() {
        List<EndPointTransformConfig> results = new ArrayList<>();

        JsonNode paths = root.path("paths");
        if (paths.isMissingNode() || !paths.isObject()) {
            LOG.warn("No 'paths' object found in the Swagger document.");
            return results;
        }

        for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
            String pathTemplate = pathEntry.getKey();
            JsonNode operations = pathEntry.getValue();

            for (Map.Entry<String, JsonNode> opEntry : operations.properties()) {
                String method = opEntry.getKey().toLowerCase();
                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }

                EndPointTransformConfig cfg = buildEndpointConfig(pathTemplate, method, opEntry.getValue());
                if (cfg != null) {
                    results.add(cfg);
                }
            }
        }

        return results;
    }

    private EndPointTransformConfig buildEndpointConfig(String pathTemplate, String method, JsonNode operation) {
        boolean isGet = "get".equals(method);
        String when = isGet ? "AFTER" : "BEFORE";

        // GET -> protect the response payload; other verbs -> transform the request payload.
        JsonNode payloadSchema = isGet
                ? findResponseSchema(operation)
                : findRequestSchema(operation);

        if (payloadSchema == null || payloadSchema.isMissingNode()) {
            return null;
        }

        Set<EntityTransformConfig> entityConfigs = new LinkedHashSet<>();
        collectSensitiveFields(payloadSchema, "$", new LinkedHashSet<>(), 0, entityConfigs);

        if (entityConfigs.isEmpty()) {
            return null;
        }

        EndPointTransformConfig cfg = new EndPointTransformConfig();
        cfg.setEndpointPath(toPathRegex(pathPrefix + pathTemplate));
        cfg.setEndpointMethods(List.of(method.toUpperCase()));
        cfg.setEndpointTransformWhen(when);
        cfg.setEntityTransformConfigs(entityConfigs);
        // right-context and processing-context are intentionally left empty.

        LOG.info(String.format("%-6s %-40s -> %s (%d sensitive field(s))",
                method.toUpperCase(), pathTemplate, when, entityConfigs.size()));

        return cfg;
    }

    /**
     * Locate the schema of the first successful (2xx) response, preferring application/json.
     */
    private JsonNode findResponseSchema(JsonNode operation) {
        JsonNode responses = operation.path("responses");
        if (!responses.isObject()) {
            return null;
        }

        JsonNode chosen = null;
        for (Map.Entry<String, JsonNode> entry : responses.properties()) {
            String status = entry.getKey();
            if (status.startsWith("2")) {
                if ("200".equals(status)) {
                    chosen = entry.getValue();
                    break;
                }
                if (chosen == null) {
                    chosen = entry.getValue();
                }
            }
        }
        return chosen == null ? null : extractContentSchema(chosen.path("content"));
    }

    private JsonNode findRequestSchema(JsonNode operation) {
        return extractContentSchema(operation.path("requestBody").path("content"));
    }

    /**
     * From a {@code content} node, return the schema node, preferring {@code application/json}.
     */
    private JsonNode extractContentSchema(JsonNode content) {
        if (!content.isObject()) {
            return null;
        }
        JsonNode json = content.path("application/json");
        if (json.isObject() && json.has("schema")) {
            return json.path("schema");
        }
        for (Map.Entry<String, JsonNode> entry : content.properties()) {
            JsonNode media = entry.getValue();
            if (media.has("schema")) {
                return media.path("schema");
            }
        }
        return null;
    }

    /**
     * Recursively walk a schema, appending to {@code jsonPath} and collecting an
     * {@link EntityTransformConfig} for every property carrying an {@code x-sensitivity} extension.
     *
     * @param schema      current schema node (may be a {@code $ref})
     * @param jsonPath    JSON path accumulated so far (starts at {@code $})
     * @param refStack    set of {@code $ref} pointers currently being expanded (cycle guard)
     * @param depth       recursion depth guard
     * @param results     accumulator of discovered entity transform configs
     */
    private void collectSensitiveFields(JsonNode schema, String jsonPath,
                                        Set<String> refStack, int depth,
                                        Set<EntityTransformConfig> results) {
        if (schema == null || schema.isMissingNode() || depth > MAX_DEPTH) {
            return;
        }

        // Resolve $ref, guarding against cycles.
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            if (refStack.contains(ref)) {
                return; // cycle detected
            }
            JsonNode resolved = resolveRef(ref);
            if (resolved == null) {
                return;
            }
            refStack.add(ref);
            collectSensitiveFields(resolved, jsonPath, refStack, depth + 1, results);
            refStack.remove(ref);
            return;
        }

        // Composed schemas.
        for (String key : new String[]{"allOf", "oneOf", "anyOf"}) {
            JsonNode composed = schema.path(key);
            if (composed.isArray()) {
                for (JsonNode sub : composed) {
                    collectSensitiveFields(sub, jsonPath, refStack, depth + 1, results);
                }
            }
        }

        // Arrays.
        JsonNode items = schema.path("items");
        if (items.isObject()) {
            collectSensitiveFields(items, jsonPath + "[*]", refStack, depth + 1, results);
        }

        // Object properties.
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            for (Map.Entry<String, JsonNode> entry : properties.properties()) {
                String propertyName = entry.getKey();
                JsonNode propertySchema = entry.getValue();
                String propertyPath = jsonPath + "." + propertyName;

                JsonNode className = propertySchema.path(CID_CLASSNAME);
                if (className.isTextual()) {
                    EntityTransformConfig entity = new EntityTransformConfig();
                    entity.setJsonPath(propertyPath);
                    entity.setRpsClassName(className.asText(""));
                    entity.setRpsPropertyName(propertySchema.path(CID_PROPERTYNAME).asText(""));

                    JsonNode extractRegex = propertySchema.path(CID_EXTRACT_REGEX);
                    if (extractRegex.isTextual()) {
                        entity.setExtractRegex(extractRegex.asText());
                    }

                    results.add(entity);
                }

                // Continue descending (nested objects / arrays).
                collectSensitiveFields(propertySchema, propertyPath, refStack, depth + 1, results);
            }
        }
    }

    private JsonNode resolveRef(String ref) {
        if (ref == null || !ref.startsWith(SCHEMA_REF_PREFIX)) {
            return null;
        }
        String schemaName = ref.substring(SCHEMA_REF_PREFIX.length());
        JsonNode schema = root.path("components").path("schemas").path(schemaName);
        return schema.isMissingNode() ? null : schema;
    }

    /**
     * Convert an OpenAPI path template into a regular expression usable as {@code endpoint-path}.
     * Path parameters ({@code {id}}) become {@code [^/]+}; literal regex metacharacters are escaped.
     */
    static String toPathRegex(String pathTemplate) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < pathTemplate.length()) {
            char c = pathTemplate.charAt(i);
            if (c == '{') {
                int close = pathTemplate.indexOf('}', i);
                if (close > i) {
                    sb.append("[^/]+");
                    i = close + 1;
                    continue;
                }
            }
            if ("\\.[]()*+?^$|{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
