package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.*;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.enginecontext.RPSEngineContextResolver;
import ch.regdata.rps.engine.client.http.HttpClientEngineProvider;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.ubp.rgd.proxy.transform.RPSClientEngineProvider;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import com.ubp.rgd.proxy.transform.config.FileTransformConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@ApplicationScoped
public class FileTransformService {

    private static final Logger LOG = Logger.getLogger(FileTransformService.class);

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
            LOG.infof("Loading file transform configuration from %s", fileTransformConfigFile);
            ObjectMapper objectMapper = new ObjectMapper();
            File configFile = new File(fileTransformConfigFile);
            
            if (!configFile.exists()) {
                LOG.warnf("File transform config file not found: %s", fileTransformConfigFile);
                return;
            }

            fileTransformConfigs = objectMapper.readValue(configFile, new TypeReference<>() {});
            
            if (fileTransformConfigs == null || fileTransformConfigs.isEmpty()) {
                LOG.warn("No file transform configurations loaded");
                return;
            }

            LOG.infof("Loaded %d file transform configuration(s)", fileTransformConfigs.size());
            
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
                LOG.infof("Created %s directory: %s", type, directory);
            }
        } catch (IOException e) {
            LOG.errorf(e, "Failed to create %s directory: %s", type, directory);
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
                LOG.infof("Started scheduler for '%s' with interval: %d seconds", 
                    config.getName(), config.getScanIntervalSeconds());
            } else {
                LOG.infof("Configuration '%s' has scan interval %d - will only process on-demand", 
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
        
        LOG.infof("Processing configuration '%s' synchronously", configName);
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
                    LOG.warnf(exc, "Failed to visit file: %s", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.errorf(e, "Error scanning directory: %s", sourceDir);
        }
        
        return processedCount[0];
    }

    private void processFile(Path file, FileTransformConfig config) {
        Path wipFile = null;
        
        try {
            // Rename file to indicate work in progress
            wipFile = file.resolveSibling(file.getFileName() + config.getWorkInProgressSuffix());
            Files.move(file, wipFile, StandardCopyOption.ATOMIC_MOVE);
            
            LOG.infof("Processing file: %s", file.getFileName());

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

            LOG.infof("Successfully processed file: %s -> %s", file.getFileName(), targetPath);

        } catch (Exception e) {
            LOG.errorf(e, "Error processing file: %s", file.getFileName());
            
            // Move file to error directory
            if (wipFile != null && Files.exists(wipFile)) {
                try {
                    Path errorPath = calculateTargetPath(file, wipFile, config, config.getErrorDirectory());
                    Files.createDirectories(errorPath.getParent());
                    Files.move(wipFile, errorPath, StandardCopyOption.REPLACE_EXISTING);
                    LOG.infof("Moved failed file to error directory: %s", errorPath);
                } catch (IOException moveException) {
                    LOG.errorf(moveException, "Failed to move file to error directory: %s", wipFile);
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

        Map<String, RPSValue[]> rpsValuesByJsonPath = getRPSValuesFromBody(documentContext, attributesConfigs);

        // Create a flat list of RPS Values
        List<RPSValue> flatList = new ArrayList<>();
        rpsValuesByJsonPath.values().forEach(rpsJsonValues -> flatList.addAll(Arrays.asList(rpsJsonValues)));

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
            LOG.debugf("Right context: %s = %s", key, value);
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
            LOG.debugf("Processing context: %s = %s", key, value);
        });

        return processingContext;
    }

    protected Map<String, RPSValue[]> getRPSValuesFromBody(DocumentContext documentContext, 
                                                            Map<String, EntityTransformConfig> attributesConfigs) {
        Map<String, RPSValue[]> rpsValuesByJsonPath = new HashMap<>();

        for (String jsonPath : attributesConfigs.keySet()) {
            try {
                List<String> values = documentContext.read(jsonPath);
                EntityTransformConfig attrCfg = attributesConfigs.get(jsonPath);
                RPSValue[] valuesForPath = new RPSValue[values.size()];
                
                for (int i = 0; i < values.size(); i++) {
                    String oldValue = values.get(i);
                    LOG.debugf("RPSValue: %s = %s : %s", oldValue, attrCfg.getRpsClassName(), attrCfg.getRpsPropertyName());
                    valuesForPath[i] = attrCfg.getRPSValue(oldValue);
                }
                
                rpsValuesByJsonPath.put(jsonPath, valuesForPath);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to read json path: %s", jsonPath);
            }
        }

        return rpsValuesByJsonPath;
    }

    protected void setRPSValuesToBody(DocumentContext documentContext, Map<String, RPSValue[]> rpsValuesByJsonPath) {
        rpsValuesByJsonPath.forEach((jsonPath, rpsJsonValues) -> {
            try {
                List<String> values = documentContext.read(jsonPath);
                for (int i = 0; i < values.size(); i++) {
                    RPSValue rpsValue = rpsJsonValues[i];
                    String newValue = rpsValue.getTransformed();
                    documentContext.set(jsonPath.replace("*", String.valueOf(i)), newValue);
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to set values for json path: %s", jsonPath);
            }
        });
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
                    LOG.infof("Shutting down scheduler for: %s", name);
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
