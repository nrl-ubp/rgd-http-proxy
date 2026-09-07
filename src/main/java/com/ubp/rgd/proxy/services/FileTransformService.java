package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.*;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.transform.JsonPathValue;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.transform.ValuePlan;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.ubp.rgd.proxy.transform.RPSClientEngineProvider;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import com.ubp.rgd.proxy.transform.config.FileTransformConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@ApplicationScoped
public class FileTransformService {

    private static final Logger LOG = LoggerFactory.getLogger(FileTransformService.class);

    /**
     * Makes {@code read()} return the concrete path of every match instead of its value.
     */
    private static final Configuration PATH_LIST_CONFIG = Configuration.builder()
            .options(Option.AS_PATH_LIST)
            .build();

    /**
     * Convert a transformed value into the JSON value to write back.
     * <p>
     * A value read as a JSON number must stay a JSON number: RPS returns a number when it
     * tokenizes a number, so writing the token as text would change the file structure.
     *
     * @param transformed the value returned by the RPS engine
     * @param numeric     whether the original value was read as a JSON number
     * @param jsonPath    the concrete path, for logging only
     * @return the value to hand over to {@code DocumentContext.set()}
     */
    private static Object toJsonValue(String transformed, boolean numeric, String jsonPath) {
        if (!numeric || transformed == null) {
            return transformed;
        }
        try {
            Number number = parseJsonNumber(transformed);
            if (!transformed.equals(String.valueOf(number))) {
                // Typically a leading zero, which JSON numbers cannot carry: 007 is written as 7.
                LOG.debug("Numeric value {} normalized to {} for json path {}", transformed, number, jsonPath);
            }
            return number;
        } catch (NumberFormatException e) {
            // Keep the value rather than losing it, even though the JSON type changes.
            LOG.warn("Transformed value for the numeric json path {} is not a number. Writing it as a"
                    + " string, which changes the json type of this field.", jsonPath);
            return transformed;
        }
    }

    /**
     * Build a number out of the exact digits of the given value.
     * <p>
     * {@link BigDecimal} and {@link BigInteger} are used rather than {@code double} or {@code long}
     * so that neither precision nor width is lost, whatever the size of the value.
     *
     * @param value the textual value to convert
     * @return the value as a number
     * @throws NumberFormatException when the value is not a valid number
     */
    private static Number parseJsonNumber(String value) {
        if (value.indexOf('.') >= 0 || value.indexOf('e') >= 0 || value.indexOf('E') >= 0) {
            return new BigDecimal(value);
        }
        return new BigInteger(value);
    }

    /**
     * Read a JSON path and always return a list of values.
     * <p>
     * {@link DocumentContext#read(String)} only returns a list for indefinite paths (containing a
     * wildcard, a deep scan or a filter). A definite path such as {@code $.name} returns the raw
     * value itself, which may be a String, a number, a boolean or {@code null}.
     *
     * @param documentContext the parsed JSON document
     * @param jsonPath        the configured JSON path
     * @return the matching values, never {@code null}
     * @throws PathNotFoundException when the document does not carry the path
     */
    private static List<Object> readValues(DocumentContext documentContext, String jsonPath) {
        Object result = documentContext.read(jsonPath);
        if (result instanceof List) {
            return (List<Object>) result;
        }
        // Definite path: a single value, possibly null.
        return Collections.singletonList(result);
    }

    @Inject
    RPSClientEngineProvider engineProvider;

    @ConfigProperty(name = "proxy.file-transform.config-file", defaultValue = "./config/file_transform_config.json")
    String fileTransformConfigFile;

    @ConfigProperty(name = "proxy.file-transform.enabled", defaultValue = "false")
    boolean fileTransformEnabled;

    private List<FileTransformConfig> fileTransformConfigs;
    private Map<String, ScheduledExecutorService> schedulers = new HashMap<>();

    @PostConstruct
    public void init() {
        if (!fileTransformEnabled) {
            LOG.info("File transformation is disabled");
            return;
        }

        try {
            LOG.info("Loading file transform configuration from {}", fileTransformConfigFile);
            ObjectMapper objectMapper = new ObjectMapper();
            File configFile = new File(fileTransformConfigFile);
            
            if (!configFile.exists()) {
                LOG.warn("File transform config file not found: {}", fileTransformConfigFile);
                return;
            }

            fileTransformConfigs = objectMapper.readValue(configFile, new TypeReference<>() {});
            
            if (fileTransformConfigs == null || fileTransformConfigs.isEmpty()) {
                LOG.warn("No file transform configurations loaded");
                return;
            }

            LOG.info("Loaded {} file transform configuration(s)", fileTransformConfigs.size());
            
            // Validate and create directories
            for (FileTransformConfig config : fileTransformConfigs) {
                validateAndCreateDirectories(config);
            }

            // Start the file processing scheduler
            startScheduler();
            
        } catch (IOException e) {
            LOG.error("Failed to load file transform configuration", e);
        }
    }

    private void validateAndCreateDirectories(FileTransformConfig config) {
        createDirectoryIfNotExists(config.getSourceDirectory(), "source");
        createDirectoryIfNotExists(config.getTargetDirectory(), "target");
        createDirectoryIfNotExists(config.getErrorDirectory(), "error");
    }

    private void createDirectoryIfNotExists(String directory, String type) {
        try {
            Path path = Paths.get(directory);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                LOG.info("Created {} directory: {}", type, directory);
            }
        } catch (IOException e) {
            LOG.error("Failed to create {} directory: {}", type, directory, e);
        }
    }

    private void startScheduler() {
        for (FileTransformConfig config : fileTransformConfigs) {
            if (config.getScanIntervalSeconds() > 0) {
                ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
                scheduler.scheduleAtFixedRate(
                        () -> processConfiguration(config),
                        0,
                        config.getScanIntervalSeconds(),
                        TimeUnit.SECONDS
                );
                schedulers.put(config.getName(), scheduler);
                LOG.info("Started scheduler for '{}' with interval: {} seconds", 
                    config.getName(), config.getScanIntervalSeconds());
            } else {
                LOG.info("Configuration '{}' has scan interval {} - will only process on-demand", 
                    config.getName(), config.getScanIntervalSeconds());
            }
        }
    }

    /**
     * Process a specific configuration synchronously.
     * This method can be called from external applications for on-demand processing.
     * @param configName the name of the configuration to process
     * @return number of files processed
     * @throws IllegalArgumentException if configuration not found
     */
    public int processConfigurationByName(String configName) {
        FileTransformConfig config = fileTransformConfigs.stream()
                .filter(cfg -> cfg.getName().equals(configName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + configName));
        
        LOG.info("Processing configuration '{}' synchronously", configName);
        return processConfiguration(config);
    }

    /**
     * Get list of all configuration names
     */
    public List<String> getConfigurationNames() {
        if (fileTransformConfigs == null) {
            LOG.warn("No file transform configurations loaded, probably because proxy.file-transform.enabled property is false.");
            return Collections.emptyList();
        }
        return fileTransformConfigs.stream()
                .map(FileTransformConfig::getName)
                .toList();
    }

    private int processConfiguration(FileTransformConfig config) {
        Path sourceDir = Paths.get(config.getSourceDirectory());
        Pattern filePattern = Pattern.compile(config.getFilePattern());
        final int[] processedCount = {0};

        try {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    
                    // Skip files that are being processed
                    if (fileName.endsWith(config.getWorkInProgressSuffix())) {
                        return FileVisitResult.CONTINUE;
                    }

                    // Check if file matches the pattern
                    if (filePattern.matcher(fileName).matches()) {
                        processFile(file, config);
                        processedCount[0]++;
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(@NonNull Path file, @NonNull IOException exc) {
                    LOG.warn("Failed to visit file: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.error("Error scanning directory: {}", sourceDir, e);
        }
        
        return processedCount[0];
    }

    private void processFile(Path file, FileTransformConfig config) {
        Path wipFile = null;
        
        try {
            // Rename file to indicate work in progress
            wipFile = file.resolveSibling(file.getFileName() + config.getWorkInProgressSuffix());
            Files.move(file, wipFile, StandardCopyOption.ATOMIC_MOVE);
            
            LOG.info("Processing file: {}", file.getFileName());

            // Read the file content
            String content = Files.readString(wipFile);

            // Transform the content
            String transformedContent = transformContent(content, config);

            // Calculate target path
            Path targetPath = calculateTargetPath(file, wipFile, config, config.getTargetDirectory());

            // Write to target directory
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, transformedContent);

            // Delete the work-in-progress file
            Files.delete(wipFile);

            LOG.info("Successfully processed file: {} -> {}", file.getFileName(), targetPath);

        } catch (Exception e) {
            LOG.error("Error processing file: {}", file.getFileName(), e);
            
            // Move file to error directory
            if (wipFile != null && Files.exists(wipFile)) {
                try {
                    Path errorPath = calculateTargetPath(file, wipFile, config, config.getErrorDirectory());
                    Files.createDirectories(errorPath.getParent());
                    Files.move(wipFile, errorPath, StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("Moved failed file to error directory: {}", errorPath);
                } catch (IOException moveException) {
                    LOG.error("Failed to move file to error directory: {}", wipFile, moveException);
                }
            }
        }
    }

    private Path calculateTargetPath(Path originalFile, Path wipFile, FileTransformConfig config, String targetBaseDir) {
        Path sourceDir = Paths.get(config.getSourceDirectory());
        Path targetDir = Paths.get(targetBaseDir);
        
        // Remove the work-in-progress suffix to get the original filename
        String fileName = wipFile.getFileName().toString();
        if (fileName.endsWith(config.getWorkInProgressSuffix())) {
            fileName = fileName.substring(0, fileName.length() - config.getWorkInProgressSuffix().length());
        }

        if (config.isPreserveDirectoryStructure()) {
            // Calculate relative path from source directory
            Path relativePath = sourceDir.relativize(originalFile.getParent());
            return targetDir.resolve(relativePath).resolve(fileName);
        } else {
            return targetDir.resolve(fileName);
        }
    }

    private String transformContent(String jsonContent, FileTransformConfig config) throws Exception {
        Map<String, EntityTransformConfig> attributesConfigs = config.sortAttributeTransformsConfig();

        if (attributesConfigs.isEmpty()) {
            LOG.debug("No entity transform configs, returning original content");
            return jsonContent;
        }

        // Parse JSON as a document
        DocumentContext documentContext = JsonPath.parse(jsonContent);

        Map<String, JsonPathValue> rpsValuesByJsonPath =
                getRPSValuesFromBody(documentContext, attributesConfigs, getAction(config));

        // Create a flat list of RPS Values
        List<RPSValue> flatList = new ArrayList<>();
        rpsValuesByJsonPath.values().forEach(pathValue -> flatList.addAll(pathValue.plan().rpsValues()));

        if (flatList.isEmpty()) {
            LOG.debug("No values to transform, returning original content");
            return jsonContent;
        }

        // Call transform API
        transformData(
                engineProvider.getClientEngineProvider(),
                flatList.toArray(new RPSValue[0]),
                getRightContext(config),
                getProcessingContext(config)
        );

        // Replace the tokenized values in the document
        setRPSValuesToBody(documentContext, rpsValuesByJsonPath);

        // Return transformed JSON
        return documentContext.jsonString();
    }

    private Context getRightContext(FileTransformConfig cfg) {
        Context rightContext = new Context();

        cfg.getRightContextEvidences().forEach((key, value) -> {
            Evidence moduleEvidence = new Evidence();
            moduleEvidence.setName(key);
            moduleEvidence.setValue(value);
            rightContext.addEvidence(moduleEvidence);
            LOG.debug("Right context: {} = {}", key, value);
        });

        return rightContext;
    }

    private ProcessingContext getProcessingContext(FileTransformConfig cfg) {
        ProcessingContext processingContext = new ProcessingContext();

        cfg.getProcessingContextEvidences().forEach((key, value) -> {
            Evidence evidence = new Evidence();
            evidence.setName(key);
            evidence.setValue(value);
            processingContext.addEvidence(evidence);
            LOG.debug("Processing context: {} = {}", key, value);
        });

        return processingContext;
    }

    /**
     * Read every value matching the configured JSON paths and build the RPS values to transform.
     * <p>
     * The returned map is keyed by the <b>concrete</b> JSON path of each match (for instance
     * {@code $['persons'][1]['name']}) so that the transformed value can later be written back
     * exactly where it was read from.
     *
     * @param documentContext   the parsed JSON document
     * @param attributesConfigs the transformation configuration, keyed by configured JSON path
     * @return the RPS values to transform, keyed by concrete JSON path
     */
    protected Map<String, JsonPathValue> getRPSValuesFromBody(DocumentContext documentContext,
                                                            Map<String, EntityTransformConfig> attributesConfigs,
                                                            String action) throws RPSTransformException {
        Map<String, JsonPathValue> rpsValuesByJsonPath = new HashMap<>();

        // Reuses the already parsed document, so the file content is not parsed a second time.
        DocumentContext pathContext = JsonPath.using(PATH_LIST_CONFIG).parse((Object) documentContext.json());

        for (String jsonPath : attributesConfigs.keySet()) {
            List<Object> values;
            List<String> matchedPaths;
            try {
                values = readValues(documentContext, jsonPath);
                matchedPaths = pathContext.read(jsonPath);
            } catch (PathNotFoundException e) {
                // The document simply does not carry this path: nothing to transform.
                LOG.debug("Json path not found in the document, skipping it: {}", jsonPath);
                continue;
            }

            if (values.size() != matchedPaths.size()) {
                LOG.warn("Json path {} matched {} value(s) but {} path(s). Skipping it to avoid writing a value"
                        + " to the wrong place.", jsonPath, values.size(), matchedPaths.size());
                continue;
            }

            EntityTransformConfig attrCfg = attributesConfigs.get(jsonPath);
            RPSMapping mapping = new RPSMapping(attrCfg.getRpsClassName(), attrCfg.getRpsPropertyName());
            // Compiled once per configured path, not once per matched value.
            Pattern extractionPattern = ValuePlan.extractionPattern(action, attrCfg.getExtractRegex());

            for (int i = 0; i < values.size(); i++) {
                Object rawValue = values.get(i);
                if (rawValue == null) {
                    // A null value holds nothing to protect nor to unprotect.
                    LOG.debug("Null value for json path {}, skipping it.", matchedPaths.get(i));
                    continue;
                }
                boolean numeric = rawValue instanceof Number;
                String oldValue = String.valueOf(rawValue);
                LOG.debug("RPSValue: {} = {} : {}", oldValue, attrCfg.getRpsClassName(), attrCfg.getRpsPropertyName());

                // A number carries neither separators nor RG{} tokens, so it is always transformed
                // as a whole. Segmenting it would break the tokenization of numeric values.
                ValuePlan plan = ValuePlan.of(mapping, oldValue, numeric ? null : extractionPattern);
                if (plan.rpsValues().isEmpty()) {
                    LOG.debug("Nothing to transform in the value of json path {}, leaving it untouched.",
                            matchedPaths.get(i));
                    continue;
                }
                rpsValuesByJsonPath.put(matchedPaths.get(i), new JsonPathValue(plan, numeric));
            }
        }

        return rpsValuesByJsonPath;
    }

    /**
     * Write the transformed values back into the document.
     * <p>
     * Each entry is keyed by a concrete JSON path pointing to a single match, so the value is
     * written exactly where it was read from and the document does not need to be read again.
     *
     * @param documentContext     the parsed JSON document to update
     * @param rpsValuesByJsonPath the transformed values, keyed by concrete JSON path
     */
    protected void setRPSValuesToBody(DocumentContext documentContext, Map<String, JsonPathValue> rpsValuesByJsonPath)
            throws RPSTransformException {
        for (Map.Entry<String, JsonPathValue> entry : rpsValuesByJsonPath.entrySet()) {
            String jsonPath = entry.getKey();
            JsonPathValue pathValue = entry.getValue();
            Object newValue = toJsonValue(pathValue.plan().reassemble(), pathValue.numeric(), jsonPath);
            documentContext.set(jsonPath, newValue);
        }
    }

    /**
     * Get the RPS action of a file transformation configuration, taken from its {@code Action}
     * processing context evidence.
     *
     * @param cfg the file transformation configuration
     * @return the action, or {@code null} when the configuration declares none
     */
    private static String getAction(FileTransformConfig cfg) {
        return cfg.getProcessingContextEvidences().get("Action");
    }

    private void transformData(HttpClientEngineProvider engineProvider, 
                               IRPSValue<String>[] values, 
                               Context rightContext, 
                               ProcessingContext processingContext) throws Exception {

        RPSEngine engine = new RPSEngine(
                engineProvider,
                new RPSEngineConverter(),
                new RPSEngineContextResolver(null)
        );

        RequestContext requestContext = new RequestContext(engine, new RPSEngineContextResolver(null));

        requestContext.withRequest(
                values,
                rightContext,
                processingContext,
                null
        );

        requestContext.transform();
    }

    public void shutdown() {
        if (!schedulers.isEmpty()) {
            LOG.info("Shutting down file transform schedulers");
            schedulers.forEach((name, scheduler) -> {
                if (scheduler != null && !scheduler.isShutdown()) {
                    LOG.info("Shutting down scheduler for: {}", name);
                    scheduler.shutdown();
                    try {
                        if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                            scheduler.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        scheduler.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }
            });
            schedulers.clear();
        }
    }

    /**
     * Get the configuration by name (for external access)
     */
    public FileTransformConfig getConfigurationByName(String configName) {
        return fileTransformConfigs.stream()
                .filter(cfg -> cfg.getName().equals(configName))
                .findFirst()
                .orElse(null);
    }
}
