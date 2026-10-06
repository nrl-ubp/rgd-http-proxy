package com.ubp.rgd.proxy.services;

import com.ubp.rgd.proxy.flightsql.FlightSqlConnectionManager;
import com.ubp.rgd.proxy.flightsql.FlightSqlTlsSupport;
import com.ubp.rgd.proxy.flightsql.FlightSqlTlsSupport.TlsMaterial;
import com.ubp.rgd.proxy.flightsql.ProxyFlightSqlProducer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.auth2.BasicCallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.GeneratedBearerTokenAuthenticator;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * Starts and stops the Apache Arrow Flight SQL server proxying the configured JDBC datasource.
 * <p>
 * The server is disabled by default and enabled with {@code proxy.flight-sql.enabled=true}. It
 * listens in plaintext unless {@code proxy.flight-sql.tls.enabled=true}, see
 * {@link FlightSqlTlsSupport}.
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

    @ConfigProperty(name = "proxy.flight-sql.tls.enabled", defaultValue = "false")
    boolean tlsEnabled;

    @ConfigProperty(name = "proxy.flight-sql.tls.key-store-file")
    Optional<String> tlsKeyStoreFile;

    @ConfigProperty(name = "proxy.flight-sql.tls.key-store-password")
    Optional<String> tlsKeyStorePassword;

    @ConfigProperty(name = "proxy.flight-sql.tls.key-store-alias")
    Optional<String> tlsKeyStoreAlias;

    @ConfigProperty(name = "proxy.flight-sql.tls.cert-chain-file")
    Optional<String> tlsCertChainFile;

    @ConfigProperty(name = "proxy.flight-sql.tls.private-key-file")
    Optional<String> tlsPrivateKeyFile;

    @Inject
    FlightSqlConnectionManager connectionManager;

    @Inject
    FlightSqlDetokenizeService detokenizeService;

    @Inject
    FlightSqlTokenizeService tokenizeService;

    private BufferAllocator allocator;
    private ProxyFlightSqlProducer producer;
    private FlightServer server;

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.info("Flight SQL server is disabled (proxy.flight-sql.enabled=false).");
            return;
        }
        // Before anything is allocated, and deliberately not caught: a server asked for TLS that
        // started in plaintext would receive its clients' database credentials in clear text.
        Optional<TlsMaterial> tls = loadTls();
        try {
            // The detokenizer is lazy: touch it so its mappings are loaded before the first query
            // rather than during it.
            LOG.info("Flight SQL detokenizer ready with {} column mapping(s)",
                    detokenizeService.getMappingConfig().getColumnMappings().size());
            allocator = new RootAllocator(Long.MAX_VALUE);
            producer = new ProxyFlightSqlProducer(allocator, connectionManager, detokenizeService,
                    tokenizeService, batchSize);
            server = newServerBuilder(allocator, host, port, producer,
                    new GeneratedBearerTokenAuthenticator(new BasicCallHeaderAuthenticator(connectionManager)),
                    tls)
                    .build();
            server.start();
            LOG.info("Flight SQL server listening on {}:{} ({})", host, server.getPort(),
                    tls.map(material -> "TLS, " + material.leaf().getSubjectX500Principal().getName())
                            .orElse("plaintext"));
        } catch (IOException e) {
            LOG.error("Failed to start the Flight SQL server", e);
            closeQuietly();
        }
    }

    private Optional<TlsMaterial> loadTls() {
        if (!tlsEnabled) {
            LOG.warn("Flight SQL TLS is disabled (proxy.flight-sql.tls.enabled=false): client"
                    + " credentials and data travel in clear text.");
            return Optional.empty();
        }
        TlsMaterial material = FlightSqlTlsSupport.load(new FlightSqlTlsSupport.Settings(
                tlsKeyStoreFile, tlsKeyStorePassword, tlsKeyStoreAlias, tlsCertChainFile, tlsPrivateKeyFile));
        LOG.info("Flight SQL TLS certificate loaded from {}: {} (valid until {})",
                material.description(), material.leaf().getSubjectX500Principal().getName(),
                material.leaf().getNotAfter());
        return Optional.of(material);
    }

    /**
     * Build the Flight server on the right transport: TLS when material is given, plaintext gRPC
     * otherwise. Shared with the tests, so that they exercise the very wiring the proxy uses.
     */
    public static FlightServer.Builder newServerBuilder(BufferAllocator allocator, String host, int port,
                                                        FlightProducer producer,
                                                        CallHeaderAuthenticator authenticator,
                                                        Optional<TlsMaterial> tls) throws IOException {
        Location location = tls.isPresent()
                ? Location.forGrpcTls(host, port)
                : Location.forGrpcInsecure(host, port);
        FlightServer.Builder builder = FlightServer.builder(allocator, location, producer)
                .headerAuthenticator(authenticator);
        if (tls.isPresent()) {
            builder.useTls(tls.get().certificateChainStream(), tls.get().privateKeyStream());
        }
        return builder;
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
