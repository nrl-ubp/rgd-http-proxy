package com.ubp.rgd.proxy.tds;

import com.ubp.rgd.proxy.sqlgateway.MappingConfigLoader;
import com.ubp.rgd.proxy.sqlgateway.PhaseContexts;
import com.ubp.rgd.proxy.sqlgateway.SqlTransformRewriter;
import com.ubp.rgd.proxy.sqlgateway.TlsMaterialLoader;
import com.ubp.rgd.proxy.sqlgateway.TokenIndexResolver;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import com.ubp.rgd.proxy.sqlgateway.config.PhaseConfig;
import com.ubp.rgd.proxy.tds.gateway.GatewaySettings;
import com.ubp.rgd.proxy.tds.gateway.RequestRewriter;
import com.ubp.rgd.proxy.tds.gateway.ResponseProcessor;
import com.ubp.rgd.proxy.tds.gateway.TdsGateway;
import com.ubp.rgd.proxy.tds.kerberos.JgssKerberosAuthenticator;
import com.ubp.rgd.proxy.tds.kerberos.KerberosAuthenticator;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.TransformException;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Starts the TDS gateway when {@code proxy.tds.enabled=true}: SQL Server clients connect to it as to
 * a SQL Server, and it relays them to the real one, rewriting the {@code transform()} calls of their
 * queries and detokenizing the results, with the mapping file of the Flight SQL server.
 */
@ApplicationScoped
public class TdsServerService {

    private static final Logger LOG = LoggerFactory.getLogger(TdsServerService.class);

    /** Names the gateway in the messages. */
    public static final String FEATURE_LABEL = "TDS gateway";

    static final TlsMaterialLoader.Feature TLS_FEATURE = new TlsMaterialLoader.Feature(FEATURE_LABEL, "proxy.tds.tls.");

    @ConfigProperty(name = "proxy.tds.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "proxy.tds.host", defaultValue = "0.0.0.0")
    String host;

    @ConfigProperty(name = "proxy.tds.port", defaultValue = "11433")
    int port;

    @ConfigProperty(name = "proxy.tds.backend.host", defaultValue = "localhost")
    String backendHost;

    @ConfigProperty(name = "proxy.tds.backend.port", defaultValue = "1433")
    int backendPort;

    @ConfigProperty(name = "proxy.tds.backend.encrypt", defaultValue = "true")
    String backendEncrypt;

    @ConfigProperty(name = "proxy.tds.backend.trust-server-certificate", defaultValue = "false")
    boolean backendTrustServerCertificate;

    @ConfigProperty(name = "proxy.tds.backend.trust-store-file")
    Optional<String> backendTrustStoreFile;

    @ConfigProperty(name = "proxy.tds.backend.trust-store-password")
    Optional<String> backendTrustStorePassword;

    @ConfigProperty(name = "proxy.tds.backend.connect-timeout", defaultValue = "15s")
    Duration connectTimeout;

    @ConfigProperty(name = "proxy.tds.encryption", defaultValue = "required")
    String encryption;

    @ConfigProperty(name = "proxy.tds.tls.key-store-file")
    Optional<String> tlsKeyStoreFile;

    @ConfigProperty(name = "proxy.tds.tls.key-store-password")
    Optional<String> tlsKeyStorePassword;

    @ConfigProperty(name = "proxy.tds.tls.key-store-alias")
    Optional<String> tlsKeyStoreAlias;

    @ConfigProperty(name = "proxy.tds.tls.cert-chain-file")
    Optional<String> tlsCertChainFile;

    @ConfigProperty(name = "proxy.tds.tls.private-key-file")
    Optional<String> tlsPrivateKeyFile;

    @ConfigProperty(name = "proxy.tds.batch-size", defaultValue = "1024")
    int batchSize;

    @ConfigProperty(name = "proxy.tds.mapping-config-file")
    Optional<String> mappingConfigFile;

    @ConfigProperty(name = "proxy.flight-sql.mapping-config-file",
            defaultValue = "./config/flight_sql_mapping_config.json")
    String flightSqlMappingConfigFile;

    @ConfigProperty(name = "proxy.tds.max-connections", defaultValue = "200")
    int maxConnections;

    @ConfigProperty(name = "proxy.tds.idle-timeout", defaultValue = "30m")
    Duration idleTimeout;

    @ConfigProperty(name = "proxy.tds.kerberos.enabled", defaultValue = "false")
    boolean kerberosEnabled;

    @ConfigProperty(name = "proxy.tds.kerberos.service-principal")
    Optional<String> kerberosServicePrincipal;

    @ConfigProperty(name = "proxy.tds.kerberos.keytab-path")
    Optional<String> kerberosKeytab;

    @ConfigProperty(name = "proxy.tds.kerberos.backend-service-principal")
    Optional<String> kerberosBackendPrincipal;

    @ConfigProperty(name = "proxy.transform.client-id")
    Optional<String> transformClientId;

    @Inject
    EndPointTransformer transformer;

    private volatile MappingConfig mappingConfig = new MappingConfig();
    private TdsGateway gateway;
    private volatile int boundPort = -1;
    private volatile String failure;

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.info("TDS gateway is disabled (proxy.tds.enabled=false).");
            return;
        }
        try {
            start();
        } catch (IOException | RuntimeException e) {
            failure = e.getMessage();
            LOG.error("Failed to start the TDS gateway", e);
            stop();
        }
    }

    void start() throws IOException {
        String file = mappingConfigFile.filter(f -> !f.isBlank()).orElse(flightSqlMappingConfigFile);
        mappingConfig = MappingConfigLoader.load(file, FEATURE_LABEL, "proxy.tds.mapping-config-file", true);
        TokenIndexResolver.load(transformer.supportsTokenMappingIndex(), transformClientId.orElse(null),
                TokenIndexResolver::registerClientMappings);
        GatewaySettings settings = new GatewaySettings(backendHost, backendPort,
                GatewaySettings.BackendEncryption.parse(backendEncrypt), !backendTrustServerCertificate,
                GatewaySettings.ClientEncryption.parse(encryption), connectTimeout);
        SSLContext serverTls = serverTls();
        SSLContext backendTls = backendTls(backendTrustServerCertificate, backendTrustStoreFile, backendTrustStorePassword);
        KerberosAuthenticator kerberos = kerberos();
        gateway = new TdsGateway(settings, serverTls, backendTls, kerberos, requestRewriter(),
                this::responseProcessor, maxConnections, idleTimeout);
        boundPort = gateway.start(host, port);
        LOG.info("TDS gateway listening on {}:{}, relaying to {}:{} (client encryption {}, {}, server encryption {})",
                host, boundPort, backendHost, backendPort, settings.clientEncryption(),
                serverTls != null ? "TLS certificate loaded" : "no TLS certificate", settings.backendEncryption());
    }

    RequestRewriter requestRewriter() {
        return new RequestRewriter(new SqlTransformRewriter(FEATURE_LABEL), (values, right, processing) -> {
            try {
                transformer.transformData(values, right, processing);
            } catch (TransformException e) {
                throw e;
            } catch (Exception e) {
                throw new TransformException(e);
            }
        }, () -> mappingConfig.getBefore());
    }

    ResponseProcessor responseProcessor() {
        return new ResponseProcessor(transformer, () -> mappingConfig, this::detokenize, batchSize);
    }

    private void detokenize(List<MappedValue> values) throws TransformException {
        PhaseConfig after = mappingConfig.getAfter() == null ? new PhaseConfig() : mappingConfig.getAfter();
        try {
            transformer.transformData(values.toArray(new MappedValue[0]), PhaseContexts.rightContext(after),
                    PhaseContexts.processingContext(after, "Unprotect", FEATURE_LABEL + " 'after'"));
        } catch (TransformException e) {
            throw e;
        } catch (Exception e) {
            throw new TransformException(e);
        }
    }

    private SSLContext serverTls() {
        TlsMaterialLoader.Settings tls = new TlsMaterialLoader.Settings(tlsKeyStoreFile, tlsKeyStorePassword,
                tlsKeyStoreAlias, tlsCertChainFile, tlsPrivateKeyFile);
        if (tls.keyStoreFile().isEmpty() && tls.certChainFile().isEmpty() && tls.privateKeyFile().isEmpty()) {
            LOG.warn("No TDS gateway TLS certificate (proxy.tds.tls.*): clients cannot encrypt, their logins and"
                    + " data travel in clear text.");
            return null;
        }
        TlsMaterialLoader.TlsMaterial material = TlsMaterialLoader.load(tls, TLS_FEATURE);
        LOG.info("TDS gateway TLS certificate loaded from {}: {} (valid until {})", material.description(),
                material.leaf().getSubjectX500Principal().getName(), material.leaf().getNotAfter());
        return material.sslContext();
    }

    /** The TLS context towards the database server. */
    public static SSLContext backendTls(boolean trustServerCertificate, Optional<String> trustStoreFile,
                                 Optional<String> trustStorePassword) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            if (trustServerCertificate) {
                context.init(null, new TrustManager[]{new TrustAll()}, null);
                return context;
            }
            Optional<String> file = trustStoreFile.filter(f -> !f.isBlank());
            if (file.isEmpty()) {
                context.init(null, null, null);
                return context;
            }
            KeyStore store = KeyStore.getInstance("PKCS12");
            char[] password = trustStorePassword.map(String::toCharArray).orElse(null);
            try (InputStream in = Files.newInputStream(Path.of(file.get()))) {
                store.load(in, password);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot build the TLS context towards the database server"
                    + " (proxy.tds.backend.trust-store-file): " + e.getMessage(), e);
        }
    }

    private KerberosAuthenticator kerberos() {
        if (!kerberosEnabled) {
            return null;
        }
        String principal = kerberosServicePrincipal.filter(p -> !p.isBlank()).orElseThrow(() ->
                new IllegalStateException("proxy.tds.kerberos.service-principal is required with proxy.tds.kerberos.enabled=true"));
        String keytab = kerberosKeytab.filter(p -> !p.isBlank()).orElseThrow(() ->
                new IllegalStateException("proxy.tds.kerberos.keytab-path is required with proxy.tds.kerberos.enabled=true"));
        String backendPrincipal = kerberosBackendPrincipal.filter(p -> !p.isBlank())
                .orElse("MSSQLSvc/" + backendHost + ":" + backendPort);
        LOG.info("TDS gateway integrated logins: Kerberos as {}, delegated to {}", principal, backendPrincipal);
        return new JgssKerberosAuthenticator(keytab, principal, backendPrincipal);
    }

    void onStop(@Observes ShutdownEvent event) {
        stop();
    }

    synchronized void stop() {
        if (gateway != null) {
            LOG.info("Shutting down the TDS gateway");
            gateway.close();
            gateway = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRunning() {
        TdsGateway current = gateway;
        return current != null && current.isRunning();
    }

    public int port() {
        return boundPort;
    }

    public int activeSessions() {
        TdsGateway current = gateway;
        return current == null ? 0 : current.activeSessions();
    }

    public String failure() {
        return failure;
    }

    /** Accepts any certificate: proxy.tds.backend.trust-server-certificate=true. */
    private static final class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Not a client.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Trusted on purpose.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
