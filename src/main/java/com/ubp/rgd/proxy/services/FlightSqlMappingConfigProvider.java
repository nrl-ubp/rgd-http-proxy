package com.ubp.rgd.proxy.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.transform.config.FlightSqlMappingConfig;
import com.ubp.rgd.proxy.transform.config.FlightSqlPhaseConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Single owner of {@code proxy.flight-sql.mapping-config-file}.
 * <p>
 * The file describes both phases of a Flight SQL exchange, and both are consumed by a different
 * service: {@link FlightSqlTokenizeService} reads the {@code before} phase to tokenize the values a
 * query carries, {@link FlightSqlDetokenizeService} reads the {@code after} phase to detokenize the
 * result set. Parsing it here means it is read, validated and reported on exactly once.
 */
@ApplicationScoped
public class FlightSqlMappingConfigProvider {

    private static final Logger LOG = LoggerFactory.getLogger(FlightSqlMappingConfigProvider.class);

    @ConfigProperty(name = "proxy.flight-sql.mapping-config-file",
            defaultValue = "./config/flight_sql_mapping_config.json")
    String mappingConfigFile;

    /**
     * Only used to decide whether a misconfigured file must stop the application: the transformations
     * themselves are driven by the configuration, not by this flag.
     */
    @ConfigProperty(name = "proxy.flight-sql.enabled", defaultValue = "false")
    boolean flightSqlEnabled;

    private FlightSqlMappingConfig mappingConfig = new FlightSqlMappingConfig();

    @PostConstruct
    void init() {
        load();
    }

    /**
     * @return the parsed configuration, never null. An empty configuration is returned when no file
     *         is deployed, which leaves every transformation inert.
     */
    public FlightSqlMappingConfig get() {
        return mappingConfig;
    }

    /** Read the contexts and the mappings from {@code proxy.flight-sql.mapping-config-file}. */
    void load() {
        File configFile = new File(mappingConfigFile);
        if (!configFile.exists()) {
            // The normal state of a deployment that does not use Flight SQL: nothing to complain
            // about beyond a warning.
            LOG.warn("Flight SQL mapping config file not found: {}. No column will be detokenized.",
                    mappingConfigFile);
            return;
        }
        try {
            // Unknown properties are tolerated on purpose: a key added to the file must never wipe
            // the whole configuration and leave the result sets silently untransformed.
            ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            mappingConfig = mapper.readValue(configFile, FlightSqlMappingConfig.class);
        } catch (IOException e) {
            LOG.error("Failed to load the Flight SQL mapping configuration from {}", mappingConfigFile, e);
            mappingConfig = new FlightSqlMappingConfig();
            failIfFlightSqlEnabled(String.format(
                    "The Flight SQL mapping configuration %s cannot be read: %s",
                    mappingConfigFile, e.getMessage()));
            return;
        }

        if (mappingConfig.getAfter() == null) {
            // Without the 'after' phase there is no context to detokenize with, and the service would
            // quietly hand back tokens.
            failIfFlightSqlEnabled(String.format(
                    "The Flight SQL mapping configuration %s declares no 'after' section, so a result"
                            + " set could not be detokenized. Add an \"after\" section with its"
                            + " right-context and processing-context.", mappingConfigFile));
            mappingConfig.setAfter(new FlightSqlPhaseConfig());
        }

        LOG.info("Loaded {} Flight SQL column mapping(s) from {}, 'after' phase active: {}",
                mappingConfig.getColumnMappings().size(), mappingConfigFile,
                mappingConfig.isAfterActive());
    }

    /**
     * Refuse to start on a misconfigured file, but only when the feature is actually in use: an
     * application that never serves Flight SQL must not fail because of a file it does not read.
     *
     * @param message what is wrong with the configuration
     */
    private void failIfFlightSqlEnabled(String message) {
        if (!flightSqlEnabled) {
            LOG.warn("{} (ignored, proxy.flight-sql.enabled=false)", message);
            return;
        }
        LOG.error(message);
        throw new IllegalStateException(message);
    }
}
