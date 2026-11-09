package com.ubp.rgd.proxy.flightsql;

import com.ubp.rgd.proxy.sqlgateway.MappingConfigLoader;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;


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

    @ConfigProperty(name = "proxy.flight-sql.mapping-config-file",
            defaultValue = "./config/flight_sql_mapping_config.json")
    String mappingConfigFile;

    /**
     * Only used to decide whether a misconfigured file must stop the application: the transformations
     * themselves are driven by the configuration, not by this flag.
     */
    @ConfigProperty(name = "proxy.flight-sql.enabled", defaultValue = "false")
    boolean flightSqlEnabled;

    private MappingConfig mappingConfig = new MappingConfig();

    @PostConstruct
    void init() {
        load();
    }

    /**
     * @return the parsed configuration, never null. An empty configuration is returned when no file
     *         is deployed, which leaves every transformation inert.
     */
    public MappingConfig get() {
        return mappingConfig;
    }

    /** Read the contexts and the mappings from {@code proxy.flight-sql.mapping-config-file}. */
    void load() {
        mappingConfig = MappingConfigLoader.load(mappingConfigFile, "Flight SQL",
                "proxy.flight-sql.mapping-config-file", flightSqlEnabled);
    }
}
