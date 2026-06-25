package com.ubp.rgd.proxy.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.transform.config.FileTransformConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Command-line application to trigger file transformation synchronously.
 * 
 * Usage: java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp <config-name>
 * 
 * Exit codes:
 * 0 - Success (files processed)
 * 1 - Configuration not found
 * 2 - Invalid arguments
 * 3 - Configuration file not found or invalid
 * 4 - Processing error
 */
public class FileTransformTriggerApp {

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_CONFIG_NOT_FOUND = 1;
    private static final int EXIT_INVALID_ARGS = 2;
    private static final int EXIT_CONFIG_FILE_ERROR = 3;
    private static final int EXIT_PROCESSING_ERROR = 4;

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(EXIT_INVALID_ARGS);
        }

        String configName = args[0];
        String configFile = System.getProperty("proxy.file-transform.config-file", "./config/file_transform_config.json");

        System.out.println("File Transform Trigger App");
        System.out.println("=========================");
        System.out.println("Configuration file: " + configFile);
        System.out.println("Configuration name: " + configName);
        System.out.println();

        try {
            // Load configuration
            List<FileTransformConfig> configs = loadConfigurations(configFile);
            
            // Find the requested configuration
            FileTransformConfig targetConfig = configs.stream()
                    .filter(cfg -> cfg.getName().equals(configName))
                    .findFirst()
                    .orElse(null);

            if (targetConfig == null) {
                System.err.println("ERROR: Configuration '" + configName + "' not found!");
                System.err.println("Available configurations:");
                configs.forEach(cfg -> System.err.println("  - " + cfg.getName()));
                System.exit(EXIT_CONFIG_NOT_FOUND);
            }

            System.out.println("Found configuration: " + targetConfig.getName());
            System.out.println("Source directory: " + targetConfig.getSourceDirectory());
            System.out.println("Target directory: " + targetConfig.getTargetDirectory());
            System.out.println("Error directory: " + targetConfig.getErrorDirectory());
            System.out.println("Scan interval: " + targetConfig.getScanIntervalSeconds() + " seconds");
            System.out.println();

            // Process files synchronously
            int processedCount = processConfiguration(targetConfig);

            System.out.println();
            System.out.println("Processing complete!");
            System.out.println("Files processed: " + processedCount);
            System.exit(EXIT_SUCCESS);

        } catch (ConfigurationException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(EXIT_CONFIG_FILE_ERROR);
        } catch (Exception e) {
            System.err.println("ERROR: Processing failed - " + e.getMessage());
            e.printStackTrace();
            System.exit(EXIT_PROCESSING_ERROR);
        }
    }

    private static void printUsage() {
        System.out.println("File Transform Trigger App");
        System.out.println("=========================");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp <config-name>");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -Dproxy.file-transform.config-file=<path>  Path to config file (default: ./config/file_transform_config.json)");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  config-name  Name of the file transform configuration to process");
        System.out.println();
        System.out.println("Exit codes:");
        System.out.println("  0 - Success");
        System.out.println("  1 - Configuration not found");
        System.out.println("  2 - Invalid arguments");
        System.out.println("  3 - Configuration file error");
        System.out.println("  4 - Processing error");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp \"Person Data Protection\"");
        System.out.println("  java -Dproxy.file-transform.config-file=/custom/path/config.json -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp \"My Config\"");
    }

    private static List<FileTransformConfig> loadConfigurations(String configFilePath) throws ConfigurationException {
        try {
            File configFile = new File(configFilePath);
            if (!configFile.exists()) {
                throw new ConfigurationException("Configuration file not found: " + configFilePath);
            }

            ObjectMapper objectMapper = new ObjectMapper();
            List<FileTransformConfig> configs = objectMapper.readValue(configFile, new TypeReference<>() {});
            
            if (configs == null || configs.isEmpty()) {
                throw new ConfigurationException("No configurations found in file: " + configFilePath);
            }

            return configs;
        } catch (Exception e) {
            throw new ConfigurationException("Failed to load configuration file: " + e.getMessage(), e);
        }
    }

    private static int processConfiguration(FileTransformConfig config) throws Exception {
        System.out.println("Starting file processing...");
        
        // Validate directories
        validateDirectory(config.getSourceDirectory(), "Source");
        ensureDirectory(config.getTargetDirectory(), "Target");
        ensureDirectory(config.getErrorDirectory(), "Error");

        // Create a simple processor (without RPS engine for standalone execution)
        FileProcessor processor = new FileProcessor(config);
        int count = processor.processFiles();
        
        return count;
    }

    private static void validateDirectory(String directory, String type) throws Exception {
        Path path = Paths.get(directory);
        if (!Files.exists(path)) {
            throw new Exception(type + " directory does not exist: " + directory);
        }
        if (!Files.isDirectory(path)) {
            throw new Exception(type + " path is not a directory: " + directory);
        }
    }

    private static void ensureDirectory(String directory, String type) throws Exception {
        Path path = Paths.get(directory);
        if (!Files.exists(path)) {
            System.out.println("Creating " + type.toLowerCase() + " directory: " + directory);
            Files.createDirectories(path);
        }
    }

    static class ConfigurationException extends Exception {
        public ConfigurationException(String message) {
            super(message);
        }

        public ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static class FileProcessor {
        private final FileTransformConfig config;

        public FileProcessor(FileTransformConfig config) {
            this.config = config;
        }

        public int processFiles() throws Exception {
            System.out.println("Note: This standalone app scans for files but requires the full application");
            System.out.println("      context (with RPS engine) to perform actual transformations.");
            System.out.println("      Please use the REST API or run within the Quarkus application.");
            
            // For now, just count files that would be processed
            Path sourceDir = Paths.get(config.getSourceDirectory());
            int[] count = {0};
            
            Files.walk(sourceDir)
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().matches(config.getFilePattern()))
                .filter(path -> !path.getFileName().toString().endsWith(config.getWorkInProgressSuffix()))
                .forEach(path -> {
                    System.out.println("  Found: " + path.getFileName());
                    count[0]++;
                });
            
            return count[0];
        }
    }
}
