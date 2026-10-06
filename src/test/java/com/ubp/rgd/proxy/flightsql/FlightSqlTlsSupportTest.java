package com.ubp.rgd.proxy.flightsql;

import com.ubp.rgd.proxy.flightsql.FlightSqlTlsSupport.FlightSqlTlsException;
import com.ubp.rgd.proxy.flightsql.FlightSqlTlsSupport.Settings;
import com.ubp.rgd.proxy.flightsql.FlightSqlTlsSupport.TlsMaterial;
import com.ubp.rgd.proxy.services.FlightSqlDetokenizeService;
import com.ubp.rgd.proxy.services.FlightSqlServerService;
import com.ubp.rgd.proxy.transform.RPSEndPointTransformer;
import com.ubp.rgd.proxy.transform.config.FlightSqlMappingConfig;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.auth2.BasicCallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.GeneratedBearerTokenAuthenticator;
import org.apache.arrow.flight.grpc.CredentialCallOption;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional TLS transport of the Flight SQL server: how its settings are validated and turned
 * into PEM material, and that a real Flight SQL client can reach the resulting server over TLS only.
 */
class FlightSqlTlsSupportTest {

    private static final String JDBC_URL = "jdbc:h2:mem:flightsqltls;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private static TestCertificates certificates;
    private static BufferAllocator allocator;
    private static Connection keepAlive;

    @TempDir
    Path directory;

    private FlightServer server;
    private ProxyFlightSqlProducer producer;

    @BeforeAll
    static void setUp() throws Exception {
        certificates = TestCertificates.generate("localhost");
        allocator = new RootAllocator(Long.MAX_VALUE);
        keepAlive = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
    }

    @AfterAll
    static void tearDown() throws Exception {
        allocator.close();
        keepAlive.close();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.shutdown();
            server.awaitTermination();
            server.close();
        }
        if (producer != null) {
            producer.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PKCS12 keystore
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldConvertAKeyStoreEntryToAPemChainAndAPkcs8Key() throws Exception {
        Path keyStore = certificates.writeKeyStore(directory.resolve("server.p12"), "server");

        TlsMaterial material = FlightSqlTlsSupport.load(keyStoreSettings(keyStore, null));

        String chain = new String(material.certificateChainPem(), StandardCharsets.US_ASCII);
        String key = new String(material.privateKeyPem(), StandardCharsets.US_ASCII);
        assertTrue(chain.startsWith("-----BEGIN CERTIFICATE-----"), chain);
        assertTrue(key.startsWith("-----BEGIN PRIVATE KEY-----"), key);
        assertEquals(certificates.certificate, material.leaf());

        // The key that comes out is the very key that went in, readable as PKCS#8.
        String body = key.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        PrivateKey decoded = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        assertArrayEquals(certificates.keyPair.getPrivate().getEncoded(), decoded.getEncoded());
    }

    @Test
    void shouldUseTheConfiguredAliasAmongSeveralKeys() throws Exception {
        TestCertificates other = TestCertificates.generate("other");
        Path keyStore = certificates.writeKeyStore(directory.resolve("server.p12"), "server", other);

        TlsMaterial material = FlightSqlTlsSupport.load(keyStoreSettings(keyStore, "server"));

        assertEquals(certificates.certificate, material.leaf());
        assertTrue(material.description().contains("'server'"), material.description());
    }

    @Test
    void shouldRefuseToGuessAmongSeveralKeys() throws Exception {
        TestCertificates other = TestCertificates.generate("other");
        Path keyStore = certificates.writeKeyStore(directory.resolve("server.p12"), "server", other);

        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(keyStoreSettings(keyStore, null)));

        assertTrue(error.getMessage().contains("key-store-alias"), error.getMessage());
    }

    @Test
    void shouldRefuseAnUnknownAlias() throws Exception {
        Path keyStore = certificates.writeKeyStore(directory.resolve("server.p12"), "server");

        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(keyStoreSettings(keyStore, "missing")));

        assertTrue(error.getMessage().contains("'missing'"), error.getMessage());
    }

    @Test
    void shouldRefuseAKeyStoreWithoutAnyPrivateKey() throws Exception {
        Path trustStore = certificates.writeTrustStore(directory.resolve("trust.p12"));

        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(keyStoreSettings(trustStore, null)));

        assertTrue(error.getMessage().contains("no private key"), error.getMessage());
    }

    @Test
    void shouldRefuseAWrongKeyStorePassword() throws Exception {
        Path keyStore = certificates.writeKeyStore(directory.resolve("server.p12"), "server");
        Settings settings = new Settings(Optional.of(keyStore.toString()), Optional.of("wrong"),
                Optional.empty(), Optional.empty(), Optional.empty());

        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(settings));

        assertTrue(error.getMessage().contains("key-store-password"), error.getMessage());
    }

    @Test
    void shouldRefuseAMissingKeyStore() {
        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(keyStoreSettings(directory.resolve("absent.p12"), null)));

        assertTrue(error.getMessage().contains("does not exist"), error.getMessage());
    }

    // ---------------------------------------------------------------------------------------------
    // PEM pair
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldAcceptAPemPairAsItIs() throws Exception {
        Path chain = certificates.writeCertificatePem(directory.resolve("server.crt"));
        Path key = certificates.writePkcs8KeyPem(directory.resolve("server.key"));

        TlsMaterial material = FlightSqlTlsSupport.load(pemSettings(chain, key));

        assertArrayEquals(Files.readAllBytes(chain), material.certificateChainPem());
        assertArrayEquals(Files.readAllBytes(key), material.privateKeyPem());
        assertEquals(certificates.certificate, material.leaf());
    }

    @Test
    void shouldExplainHowToConvertAPkcs1Key() throws Exception {
        Path chain = certificates.writeCertificatePem(directory.resolve("server.crt"));
        Path key = certificates.writePkcs1KeyPem(directory.resolve("server.key"));

        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(pemSettings(chain, key)));

        assertTrue(error.getMessage().contains("PKCS#8"), error.getMessage());
        assertTrue(error.getMessage().contains("openssl pkcs8 -topk8 -nocrypt"), error.getMessage());
    }

    @Test
    void shouldRefuseAnEncryptedKey() throws Exception {
        Path chain = certificates.writeCertificatePem(directory.resolve("server.crt"));
        Path key = Files.writeString(directory.resolve("server.key"),
                "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n");

        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(pemSettings(chain, key)));

        assertTrue(error.getMessage().contains("encrypted"), error.getMessage());
    }

    @Test
    void shouldRefuseAChainFileWithoutCertificate() throws Exception {
        Path chain = Files.writeString(directory.resolve("server.crt"), "not a certificate");
        Path key = certificates.writePkcs8KeyPem(directory.resolve("server.key"));

        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(pemSettings(chain, key)));

        assertTrue(error.getMessage().contains("no PEM certificate"), error.getMessage());
    }

    @Test
    void shouldRefuseAHalfConfiguredPemPair() throws Exception {
        Path chain = certificates.writeCertificatePem(directory.resolve("server.crt"));
        Settings settings = new Settings(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(chain.toString()), Optional.empty());

        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(settings));

        assertTrue(error.getMessage().contains("needs both"), error.getMessage());
    }

    // ---------------------------------------------------------------------------------------------
    // Choosing the source
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldRefuseTlsWithoutAnyCertificate() {
        Settings settings = new Settings(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());

        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(settings));

        assertTrue(error.getMessage().contains("no certificate is configured"), error.getMessage());
    }

    @Test
    void shouldRefuseBothSourcesAtOnce() throws Exception {
        Path keyStore = certificates.writeKeyStore(directory.resolve("server.p12"), "server");
        Path chain = certificates.writeCertificatePem(directory.resolve("server.crt"));
        Settings settings = new Settings(Optional.of(keyStore.toString()), Optional.of(TestCertificates.PASSWORD),
                Optional.empty(), Optional.of(chain.toString()), Optional.empty());

        FlightSqlTlsException error = assertThrows(FlightSqlTlsException.class,
                () -> FlightSqlTlsSupport.load(settings));

        assertTrue(error.getMessage().contains("configured twice"), error.getMessage());
    }

    @Test
    void shouldTreatBlankSettingsAsAbsent() throws Exception {
        // An empty "proxy.flight-sql.tls.cert-chain-file=" line must not count as a second source.
        Path keyStore = certificates.writeKeyStore(directory.resolve("server.p12"), "server");
        Settings settings = new Settings(Optional.of(keyStore.toString()), Optional.of(TestCertificates.PASSWORD),
                Optional.of("  "), Optional.of(""), Optional.of(" "));

        assertEquals(certificates.certificate, FlightSqlTlsSupport.load(settings).leaf());
    }

    @Test
    void shouldStillLoadAnExpiredCertificate() throws Exception {
        // Refused by the clients, but reported in the logs rather than stopping the whole proxy.
        TestCertificates expired = TestCertificates.generate("localhost",
                Instant.now().minus(Duration.ofDays(30)), Instant.now().minus(Duration.ofDays(1)));
        Path keyStore = expired.writeKeyStore(directory.resolve("expired.p12"), "server");

        assertEquals(expired.certificate, FlightSqlTlsSupport.load(keyStoreSettings(keyStore, null)).leaf());
    }

    // ---------------------------------------------------------------------------------------------
    // Against a real server
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldServeQueriesOverTlsWithAKeyStore() throws Exception {
        Path keyStore = certificates.writeKeyStore(directory.resolve("server.p12"), "server");
        startServer(Optional.of(FlightSqlTlsSupport.load(keyStoreSettings(keyStore, null))));

        assertEquals("42", queryOverTls(certificates.certificatePem()));
    }

    @Test
    void shouldServeQueriesOverTlsWithAPemPair() throws Exception {
        Path chain = certificates.writeCertificatePem(directory.resolve("server.crt"));
        Path key = certificates.writePkcs8KeyPem(directory.resolve("server.key"));
        startServer(Optional.of(FlightSqlTlsSupport.load(pemSettings(chain, key))));

        assertEquals("42", queryOverTls(certificates.certificatePem()));
    }

    @Test
    void shouldRefuseAPlaintextClientOnATlsServer() throws Exception {
        Path keyStore = certificates.writeKeyStore(directory.resolve("server.p12"), "server");
        startServer(Optional.of(FlightSqlTlsSupport.load(keyStoreSettings(keyStore, null))));

        try (FlightClient client = FlightClient.builder(allocator,
                Location.forGrpcInsecure("localhost", server.getPort())).build()) {
            // The credentials never reach the server in clear text: the handshake itself fails.
            assertThrows(FlightRuntimeException.class, () -> client.authenticateBasicToken(USER, PASSWORD));
        }
    }

    @Test
    void shouldRefuseAClientThatDoesNotTrustTheCertificate() throws Exception {
        Path keyStore = certificates.writeKeyStore(directory.resolve("server.p12"), "server");
        startServer(Optional.of(FlightSqlTlsSupport.load(keyStoreSettings(keyStore, null))));
        TestCertificates stranger = TestCertificates.generate("localhost");

        assertThrows(FlightRuntimeException.class, () -> queryOverTls(stranger.certificatePem()));
    }

    @Test
    void shouldStayInPlaintextWithoutTlsMaterial() throws Exception {
        startServer(Optional.empty());

        try (FlightClient client = FlightClient.builder(allocator,
                Location.forGrpcInsecure("localhost", server.getPort())).build()) {
            CredentialCallOption credentials = client.authenticateBasicToken(USER, PASSWORD).orElseThrow();
            assertEquals("42", firstValue(new FlightSqlClient(client), credentials));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static Settings keyStoreSettings(Path keyStore, String alias) {
        return new Settings(Optional.of(keyStore.toString()), Optional.of(TestCertificates.PASSWORD),
                Optional.ofNullable(alias), Optional.empty(), Optional.empty());
    }

    private static Settings pemSettings(Path chain, Path key) {
        return new Settings(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(chain.toString()), Optional.of(key.toString()));
    }

    /** Start the server exactly as the proxy does, through {@link FlightSqlServerService}. */
    private void startServer(Optional<TlsMaterial> tls) throws Exception {
        FlightSqlConnectionManager connectionManager = new FlightSqlConnectionManager();
        connectionManager.jdbcUrl = JDBC_URL;

        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        // Nothing to detokenize here, and no engine: the transport is what is under test.
        config.getAfter().setActive(false);
        FlightSqlDetokenizeService detokenizeService = new FlightSqlDetokenizeService();
        detokenizeService.setTransformer(new RPSEndPointTransformer());
        detokenizeService.setMappingConfig(config);

        producer = new ProxyFlightSqlProducer(allocator, connectionManager, detokenizeService,
                new LocalTokenizeService(), 1024);
        server = FlightSqlServerService.newServerBuilder(allocator, "localhost", 0, producer,
                        new GeneratedBearerTokenAuthenticator(new BasicCallHeaderAuthenticator(connectionManager)),
                        tls)
                .build();
        server.start();
    }

    private String queryOverTls(byte[] trustedCertificate) throws Exception {
        try (FlightClient client = FlightClient.builder(allocator,
                        Location.forGrpcTls("localhost", server.getPort()))
                .trustedCertificates(new ByteArrayInputStream(trustedCertificate))
                .build()) {
            CredentialCallOption credentials = client.authenticateBasicToken(USER, PASSWORD).orElseThrow();
            return firstValue(new FlightSqlClient(client), credentials);
        }
    }

    private static String firstValue(FlightSqlClient sqlClient, CredentialCallOption credentials)
            throws Exception {
        var info = sqlClient.execute("SELECT 42 AS ANSWER", credentials);
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().get(0).getTicket(), credentials)) {
            assertTrue(stream.next());
            return String.valueOf(stream.getRoot().getVector(0).getObject(0));
        }
    }
}
