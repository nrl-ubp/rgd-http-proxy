package com.ubp.rgd.proxy.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
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
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@ApplicationScoped
public class FileTransformService {

    private static final Logger LOG = LoggerFactory.getLogger(FileTransformService.class);

    @Inject
    EndPointTransformer transformer;

    @ConfigProperty(name = "proxy.file-transform.config-file", defaultValue = "./config/file_transform_config.json")
    String fileTransformConfigFile;

    @ConfigProperty(name = "proxy.file-transform.enabled", defaultValue = "false")
    boolean fileTransformEnabled;

    // Package private so that the tests can drive a configuration without a configuration file.
    List<FileTransformConfig> fileTransformConfigs;
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
     * <p>
     * A configuration driven by the scheduler cannot be triggered: the server is already processing
     * it every {@code scan-interval-seconds}, so an on-demand run would walk the same directory at
     * the same time as a scheduled one, and neither run would then describe what really happened. A
     * configuration meant to be driven by a batch must declare an interval of 0.
     *
     * @param configName the name of the configuration to process
     * @return the outcome of the run
     * @throws IllegalArgumentException if configuration not found
     * @throws IllegalStateException if the configuration is driven by the scheduler
     */
    public FileTransformResult processConfigurationByName(String configName) {
        FileTransformConfig config = fileTransformConfigs.stream()
                .filter(cfg -> cfg.getName().equals(configName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Configuration not found: " + configName));

        if (config.getScanIntervalSeconds() > 0) {
            throw new IllegalStateException(String.format(
                    "Configuration '%s' is processed by the scheduler every %d seconds and cannot be"
                            + " triggered on demand. Set its scan-interval-seconds to 0 to drive it from a batch.",
                    configName, config.getScanIntervalSeconds()));
        }

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

    private FileTransformResult processConfiguration(FileTransformConfig config) {
        Path sourceDir = Paths.get(config.getSourceDirectory());
        Pattern filePattern = Pattern.compile(config.getFilePattern());
        final int[] succeeded = {0};
        final int[] failed = {0};
        final List<String> errors = new ArrayList<>();

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
                        String failure = processFile(file, config);
                        if (failure == null) {
                            succeeded[0]++;
                        } else {
                            failed[0]++;
                            errors.add(failure);
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(@NonNull Path file, @NonNull IOException exc) {
                    LOG.warn("Failed to visit file: {}", file, exc);
                    errors.add(String.format("%s: cannot be read - %s", file, exc.getMessage()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Not being able to scan the source directory is a failed run, not an empty one.
            LOG.error("Error scanning directory: {}", sourceDir, e);
            errors.add(String.format("Cannot scan the source directory %s - %s", sourceDir, e.getMessage()));
        }

        FileTransformResult result =
                new FileTransformResult(config.getName(), succeeded[0], failed[0], List.copyOf(errors));

        LOG.info("Configuration '{}' finished with status {}: {} succeeded, {} failed",
                config.getName(), result.status(), result.filesSucceeded(), result.filesFailed());

        return result;
    }

    /**
     * Transform one file: rename it to mark it in progress, write the result to the target
     * directory, and move it to the error directory if anything goes wrong.
     *
     * @param file   the file to process
     * @param config the configuration being run
     * @return {@code null} when the file was transformed, otherwise a message describing the failure
     */
    private String processFile(Path file, FileTransformConfig config) {
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
            return null;

        } catch (Exception e) {
            LOG.error("Error processing file: {}", file.getFileName(), e);

            String failure = String.format("%s: %s", file.getFileName(), e.getMessage());

            // Move file to error directory
            if (wipFile != null && Files.exists(wipFile)) {
                try {
                    Path errorPath = calculateTargetPath(file, wipFile, config, config.getErrorDirectory());
                    Files.createDirectories(errorPath.getParent());
                    Files.move(wipFile, errorPath, StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("Moved failed file to error directory: {}", errorPath);
                } catch (IOException moveException) {
                    LOG.error("Failed to move file to error directory: {}", wipFile, moveException);
                    failure += String.format(" (and could not be moved to the error directory: %s)",
                            moveException.getMessage());
                }
            }

            return failure;
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

    /**
     * Transform the content of a file with the configured tokenizer.
     * <p>
     * The transformation itself is delegated to the injected {@link EndPointTransformer}, so a file
     * is protected exactly the same way as an HTTP payload, with the implementation selected by
     * {@code proxy.transform.impl}.
     *
     * @param jsonContent the content of the file
     * @param config      the file transformation configuration
     * @return the transformed content
     * @throws Exception when the transformation fails
     */
    private String transformContent(String jsonContent, FileTransformConfig config) throws Exception {
        return transformer.transformJson(
                jsonContent,
                config.sortAttributeTransformsConfig(),
                config.getRightContextEvidences(),
                config.getProcessingContextEvidences()
        );
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
