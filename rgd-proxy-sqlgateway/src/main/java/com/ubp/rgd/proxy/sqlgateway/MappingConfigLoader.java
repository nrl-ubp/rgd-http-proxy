package com.ubp.rgd.proxy.sqlgateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import com.ubp.rgd.proxy.sqlgateway.config.PhaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Reads a SQL gateway mapping file: the {@code before} and {@code after} phases and the column and
 * data mappings. The Flight SQL server and the TDS gateway read the same format.
 */
public final class MappingConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(MappingConfigLoader.class);

    private MappingConfigLoader() {
    }

    /**
     * Read a mapping file.
     *
     * @param file          the file, a missing one gives an empty configuration
     * @param featureLabel  names the gateway in the messages, such as {@code Flight SQL}
     * @param property      the property naming the file, for the messages
     * @param failOnError   whether an unreadable or incomplete file must stop the application: only
     *                      when the gateway is enabled, an application must not fail because of a
     *                      file it does not read
     * @return the configuration, never null
     * @throws IllegalStateException when the file is unusable and {@code failOnError} is set
     */
    public static MappingConfig load(String file, String featureLabel, String property, boolean failOnError) {
        File configFile = new File(file);
        if (!configFile.exists()) {
            // The normal state of a deployment that does not use the gateway.
            LOG.warn("{} mapping config file not found: {}. No column will be detokenized.", featureLabel, file);
            return new MappingConfig();
        }
        MappingConfig config;
        try {
            // Unknown properties are tolerated on purpose: a key added to the file must never wipe
            // the whole configuration and leave the results silently untransformed.
            ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            config = mapper.readValue(configFile, MappingConfig.class);
        } catch (IOException e) {
            LOG.error("Failed to load the {} mapping configuration from {}", featureLabel, file, e);
            fail(failOnError, String.format("The %s mapping configuration %s cannot be read: %s",
                    featureLabel, file, e.getMessage()), property);
            return new MappingConfig();
        }

        if (config.getAfter() == null) {
            // Without the 'after' phase there is no context to detokenize with, and the gateway would
            // quietly hand back tokens.
            fail(failOnError, String.format("The %s mapping configuration %s declares no 'after' section, so a"
                    + " result could not be detokenized. Add an \"after\" section with its right-context and"
                    + " processing-context.", featureLabel, file), property);
            config.setAfter(new PhaseConfig());
        }

        LOG.info("Loaded {} {} column mapping(s) from {}, 'after' phase active: {}",
                config.getColumnMappings().size(), featureLabel, file, config.isAfterActive());
        return config;
    }

    private static void fail(boolean failOnError, String message, String property) {
        if (!failOnError) {
            LOG.warn("{} (ignored, the gateway reading {} is disabled)", message, property);
            return;
        }
        LOG.error(message);
        throw new IllegalStateException(message);
    }
}
