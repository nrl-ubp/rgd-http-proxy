package com.ubp.rgd.proxy.services;

import com.ubp.rgd.proxy.flightsql.FlightSqlConnectionManager;
import com.ubp.rgd.proxy.flightsql.ProxyFlightSqlProducer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.auth2.BasicCallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.GeneratedBearerTokenAuthenticator;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Starts and stops the Apache Arrow Flight SQL server proxying the configured JDBC datasource.
 * <p>
 * The server is disabled by default and enabled with {@code proxy.flight-sql.enabled=true}.
 */
@ApplicationScoped
public class FlightSqlServerService {

    private static final Logger LOG = LoggerFactory.getLogger(FlightSqlServerService.class);

    @ConfigProperty(name = "proxy.flight-sql.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "proxy.flight-sql.host", defaultValue = "0.0.0.0")
    String host;

    @ConfigProperty(name = "proxy.flight-sql.port", defaultValue = "32010")
    int port;

    @ConfigProperty(name = "proxy.flight-sql.batch-size", defaultValue = "1024")
    int batchSize;

    @Inject
    FlightSqlConnectionManager connectionManager;

    @Inject
    FlightSqlDetokenizeService detokenizeService;

    private BufferAllocator allocator;
    private ProxyFlightSqlProducer producer;
    private FlightServer server;

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.info("Flight SQL server is disabled (proxy.flight-sql.enabled=false).");
            return;
        }
        try {
            // The detokenizer is lazy: touch it so its mappings are loaded before the first query
            // rather than during it.
            LOG.info("Flight SQL detokenizer ready with {} column mapping(s)",
                    detokenizeService.getMappingConfig().getColumnMappings().size());
            allocator = new RootAllocator(Long.MAX_VALUE);
            producer = new ProxyFlightSqlProducer(allocator, connectionManager, detokenizeService, batchSize);
            server = FlightServer.builder(allocator, Location.forGrpcInsecure(host, port), producer)
                    .headerAuthenticator(new GeneratedBearerTokenAuthenticator(
                            new BasicCallHeaderAuthenticator(connectionManager)))
                    .build();
            server.start();
            LOG.info("Flight SQL server listening on {}:{}", host, server.getPort());
        } catch (IOException e) {
            LOG.error("Failed to start the Flight SQL server", e);
            closeQuietly();
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        if (server != null) {
            LOG.info("Shutting down the Flight SQL server");
        }
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (server != null) {
                server.shutdown();
                server.awaitTermination();
                server.close();
            }
        } catch (Exception e) {
            LOG.warn("Error while stopping the Flight SQL server: {}", e.getMessage());
        } finally {
            server = null;
        }
        try {
            if (producer != null) {
                producer.close();
            }
        } catch (Exception e) {
            LOG.warn("Error while closing the Flight SQL producer: {}", e.getMessage());
        } finally {
            producer = null;
        }
        if (allocator != null) {
            allocator.close();
            allocator = null;
        }
    }
}
