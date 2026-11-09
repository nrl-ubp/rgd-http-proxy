package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.sqlgateway.TlsMaterialLoader;
import com.ubp.rgd.proxy.sqlgateway.config.ColumnMapping;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import com.ubp.rgd.proxy.tds.TdsServerService;
import com.ubp.rgd.proxy.tds.protocol.Login7;
import com.ubp.rgd.proxy.tds.protocol.ServerTokens;
import com.ubp.rgd.proxy.tds.protocol.ServerTokens.Column;
import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.transform.model.ValueKind;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gateway between the real Microsoft JDBC driver and a scripted SQL Server: pre-login, TLS in all
 * its TDS forms, login, statement rewriting and result detokenization, end to end.
 */
class TdsGatewayEndToEndTest {

    private static final String PASSWORD = "changeit";

    @TempDir
    static Path certificates;

    private static SSLContext tls;
    private static Path trustStore;

    private final MappingConfig config = new MappingConfig();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private FakeSqlServer server;

    private String nameToken;
    private LocalDate birthToken;
    private BigDecimal salaryToken;

    @BeforeAll
    static void certificate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=localhost,O=UBP Test");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(System.nanoTime()),
                Date.from(Instant.now().minus(Duration.ofDays(1))), Date.from(Instant.now().plus(Duration.ofDays(30))),
                subject, keyPair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                new GeneralName(GeneralName.dNSName, "localhost"), new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate())));

        Path chain = Files.writeString(certificates.resolve("chain.pem"), pem("CERTIFICATE", certificate.getEncoded()));
        Path key = Files.writeString(certificates.resolve("key.pem"), pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        tls = TlsMaterialLoader.load(new TlsMaterialLoader.Settings(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(chain.toString()), Optional.of(key.toString())),
                new TlsMaterialLoader.Feature("TDS gateway", "proxy.tds.tls.")).sslContext();

        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("gateway", certificate);
        trustStore = certificates.resolve("truststore.p12");
        try (OutputStream out = Files.newOutputStream(trustStore)) {
            store.store(out, PASSWORD.toCharArray());
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    @BeforeEach
    void setUp() throws Exception {
        config.setColumnMappings(List.of(
                new ColumnMapping("*", "NAME", "Person", "FirstName"),
                new ColumnMapping("*", "BIRTH_DATE", "Person", "BirthDate"),
                new ColumnMapping("*", "SALARY", "Person", "Salary")));
        nameToken = Fpe.protect("Bernadette", "Person", "FirstName");
        birthToken = LocalDate.parse(Fpe.protect("1980-05-17", "Person", "BirthDate", ValueKind.DATE));
        salaryToken = new BigDecimal(Fpe.protect("12345.67", "Person", "Salary", ValueKind.NUMBER));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    /** Start a scripted server and a gateway in front of it. @return the gateway port */
    private int gateway(boolean serverTls, GatewaySettings.BackendEncryption backendEncryption, boolean gatewayTls,
                        GatewaySettings.ClientEncryption clientEncryption) throws Exception {
        server = new FakeSqlServer(serverTls ? tls : null);
        resources.add(server);
        server.respond(this::personResult);
        GatewaySettings settings = new GatewaySettings("127.0.0.1", server.port(), backendEncryption, false,
                clientEncryption, Duration.ofSeconds(5));
        TdsGateway gateway = new TdsGateway(settings, gatewayTls ? tls : null,
                TdsServerService.backendTls(true, Optional.empty(), Optional.empty()), null,
                Fpe.requestRewriter(config), () -> Fpe.responseProcessor(config, 1024, new ArrayList<>()), 10,
                Duration.ofMinutes(1));
        resources.add(gateway);
        return gateway.start("127.0.0.1", 0);
    }

    private byte[] personResult(String sql) {
        if (sql.contains("FROM PERSON")) {
            return ServerTokens.join(
                    ServerTokens.colMetadata(new Column("ID", ServerTokens.intN()), new Column("NAME", ServerTokens.nvarchar(10)),
                            new Column("BIRTH_DATE", ServerTokens.date()), new Column("SALARY", ServerTokens.decimal(10, 2))),
                    ServerTokens.row(ServerTokens.intValue(1), ServerTokens.nvarcharValue(nameToken),
                            ServerTokens.dateValue(birthToken), ServerTokens.decimalValue(salaryToken, 10, 2)),
                    ServerTokens.nbcRow(ServerTokens.intValue(2), null, null, null),
                    ServerTokens.done(0x11, 2));
        }
        if (sql.contains("FROM MISSING")) {
            return ServerTokens.error(208, 16, "Invalid object name 'MISSING'.");
        }
        if (sql.contains("TWO RESULTS")) {
            return ServerTokens.join(
                    ServerTokens.colMetadata(new Column("A", ServerTokens.intN())), ServerTokens.row(ServerTokens.intValue(1)),
                    ServerTokens.done(0x11, 1),
                    ServerTokens.colMetadata(new Column("NAME", ServerTokens.nvarchar(30))),
                    ServerTokens.row(ServerTokens.nvarcharValue("Dear " + nameToken)), ServerTokens.done(0x11, 1));
        }
        return new byte[0];
    }

    private Connection connect(int port, String options) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlserver://127.0.0.1:" + port + ";databaseName=WDX1;user=alice;"
                + "password=s3cret;loginTimeout=10;" + options);
    }

    private void assertPersonRow(ResultSet rs) throws SQLException {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("ID"));
        assertEquals("Bernadette", rs.getString("NAME"));
        assertEquals(LocalDate.of(1980, 5, 17), rs.getObject("BIRTH_DATE", LocalDate.class));
        assertEquals(new BigDecimal("12345.67"), rs.getBigDecimal("SALARY"));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt("ID"));
        rs.getString("NAME");
        assertTrue(rs.wasNull());
        assertFalse(rs.next());
    }

    @Test
    void encryptedQueriesAreRewrittenAndTheirResultsDetokenized() throws Exception {
        int port = gateway(true, GatewaySettings.BackendEncryption.ON, true, GatewaySettings.ClientEncryption.REQUIRED);
        try (Connection connection = connect(port, "encrypt=true;trustServerCertificate=true");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT * FROM PERSON WHERE NAME = transform('Person', 'FirstName', 'CH', 'Bernadette')")) {
            ResultSetMetaData metaData = rs.getMetaData();
            assertEquals("nvarchar", metaData.getColumnTypeName(2));
            assertEquals(4000, metaData.getPrecision(2), "the detokenized column is announced widened");
            assertEquals("date", metaData.getColumnTypeName(3));
            assertPersonRow(rs);
        }
        assertEquals("SELECT * FROM PERSON WHERE NAME = '" + nameToken + "'", server.statements.get(0));
        assertEquals(List.of(Tds.ENCRYPT_ON), server.encryptionRequests);
        Login7 login = server.logins.get(0);
        assertEquals("alice", login.text(Login7.Field.USER_NAME));
        assertEquals("127.0.0.1", login.text(Login7.Field.SERVER_NAME));
        assertTrue(login.features().stream().allMatch(f -> TdsSession.RELAYED_FEATURES.contains(f.id())),
                "only the relayed feature extensions reach the server");
    }

    @Test
    void preparedStatementsAreRewrittenInTheirRpcCall() throws Exception {
        int port = gateway(false, GatewaySettings.BackendEncryption.LOGIN_ONLY, false, GatewaySettings.ClientEncryption.OPTIONAL);
        try (Connection connection = connect(port, "encrypt=false");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM PERSON WHERE NAME = transform('Person', 'FirstName', 'CH', N'Bernadette') AND ID > ?")) {
            statement.setInt(1, 0);
            try (ResultSet rs = statement.executeQuery()) {
                assertPersonRow(rs);
            }
        }
        String received = server.statements.get(0);
        assertTrue(received.matches("SELECT \\* FROM PERSON WHERE NAME = '\\Q" + nameToken + "\\E' AND ID > +@P0 *"), received);
    }

    @Test
    void multipleResultSetsAndServerErrorsAreRelayed() throws Exception {
        int port = gateway(true, GatewaySettings.BackendEncryption.LOGIN_ONLY, true, GatewaySettings.ClientEncryption.OPTIONAL);
        try (Connection connection = connect(port, "encrypt=false");
             Statement statement = connection.createStatement()) {
            assertTrue(statement.execute("SELECT TWO RESULTS"));
            try (ResultSet rs = statement.getResultSet()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            assertTrue(statement.getMoreResults());
            try (ResultSet rs = statement.getResultSet()) {
                assertTrue(rs.next());
                assertEquals("Dear Bernadette", rs.getString(1));
            }
            SQLException error = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM MISSING"));
            assertEquals(208, error.getErrorCode());
            assertEquals("Invalid object name 'MISSING'.", error.getMessage());
        }
        assertEquals(List.of(Tds.ENCRYPT_OFF), server.encryptionRequests, "login-only towards the server");
    }

    @Test
    void theGatewayAnswersItsOwnErrors() throws Exception {
        int port = gateway(false, GatewaySettings.BackendEncryption.LOGIN_ONLY, false, GatewaySettings.ClientEncryption.OPTIONAL);
        config.getBefore().setActive(false);
        try (Connection connection = connect(port, "encrypt=false");
             Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.executeQuery(
                    "SELECT * FROM PERSON WHERE NAME = transform('Person', 'FirstName', 'CH', 'x')"));
            assertEquals(TdsSession.ERROR_GATEWAY, error.getErrorCode());
            assertTrue(error.getMessage().contains("'before' phase"), error.getMessage());
            // The session goes on.
            try (ResultSet rs = statement.executeQuery("SELECT * FROM PERSON")) {
                assertPersonRow(rs);
            }
        }
        assertEquals(List.of("SELECT * FROM PERSON"), server.statements, "nothing reached the server");
    }

    @Test
    void aConnectionResetIsCarriedOverARequestTheGatewayAnswersItself() throws Exception {
        int port = gateway(false, GatewaySettings.BackendEncryption.LOGIN_ONLY, false, GatewaySettings.ClientEncryption.OFF);
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            com.ubp.rgd.proxy.tds.protocol.TdsChannel channel = new com.ubp.rgd.proxy.tds.protocol.TdsChannel("client",
                    socket.getInputStream(), socket.getOutputStream());
            channel.writeMessage(Tds.PKT_PRELOGIN, new com.ubp.rgd.proxy.tds.protocol.PreLogin()
                    .set(Tds.PL_VERSION, new byte[6]).encryption(Tds.ENCRYPT_NOT_SUP).encode());
            channel.readMessage();
            channel.writeMessage(Tds.PKT_LOGIN7, ServerTokens.login7("alice", Login7.obfuscatePassword("x"), "app",
                    "gw", "WDX1", new int[0]));
            channel.readMessage();
            byte[] headers = new com.ubp.rgd.proxy.tds.protocol.TdsBuffer().writeInt(22).writeInt(18).writeUShort(2)
                    .writeLong(0).writeInt(1).toByteArray();
            // The first request of a reused pooled connection is malformed: the gateway answers it.
            channel.writeMessage(Tds.PKT_SQL_BATCH, Tds.STATUS_RESETCONNECTION, ServerTokens.join(headers,
                    "SELECT transform('Person')".getBytes(StandardCharsets.UTF_16LE)));
            channel.readMessage();
            channel.writeMessage(Tds.PKT_SQL_BATCH, 0, ServerTokens.join(headers,
                    "SELECT 1".getBytes(StandardCharsets.UTF_16LE)));
            channel.readMessage();
        }
        assertEquals(List.of("SELECT 1"), server.statements);
        assertEquals(Tds.STATUS_RESETCONNECTION | Tds.STATUS_EOM, server.requestStatuses.get(0),
                "the reset reaches the server with the next request");
    }

    @Test
    void theLoginReachesTheServerAsTheClientSentIt() throws Exception {
        int port = gateway(false, GatewaySettings.BackendEncryption.LOGIN_ONLY, false, GatewaySettings.ClientEncryption.OFF);
        String url = ";databaseName=secure_datamart;user=bad;password=P@ss w0rd-é€;encrypt=false;loginTimeout=10;"
                + "applicationName=probe";
        // Directly to the scripted server, then through the gateway: the same login twice.
        assertThrows(SQLException.class, () -> DriverManager.getConnection("jdbc:sqlserver://127.0.0.1:" + server.port() + url));
        SQLException error = assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:sqlserver://127.0.0.1:" + port + url));
        assertEquals(18456, error.getErrorCode());

        Login7 direct = Login7.parse(server.loginPayloads.get(0));
        Login7 relayed = Login7.parse(server.loginPayloads.get(1));
        for (Login7.Field field : Login7.Field.values()) {
            if (field != Login7.Field.EXTENSION && field != Login7.Field.SERVER_NAME) {
                assertArrayEquals(direct.field(field), relayed.field(field), field.name());
            }
        }
        assertEquals("secure_datamart", relayed.text(Login7.Field.DATABASE));
        assertEquals("P@ss w0rd-é€", deobfuscate(relayed.field(Login7.Field.PASSWORD)));
        assertEquals(direct.tdsVersion(), relayed.tdsVersion());
        assertEquals(direct.packetSize(), relayed.packetSize());
    }

    private static String deobfuscate(byte[] password) {
        byte[] bytes = password.clone();
        for (int i = 0; i < bytes.length; i++) {
            int value = (bytes[i] & 0xFF) ^ 0xA5;
            bytes[i] = (byte) (((value & 0x0F) << 4) | (value >>> 4));
        }
        return new String(bytes, StandardCharsets.UTF_16LE);
    }

    @Test
    void aLoginFailureIsRelayed() throws Exception {
        int port = gateway(false, GatewaySettings.BackendEncryption.LOGIN_ONLY, false, GatewaySettings.ClientEncryption.OPTIONAL);
        SQLException error = assertThrows(SQLException.class, () -> DriverManager.getConnection(
                "jdbc:sqlserver://127.0.0.1:" + port + ";user=bad;password=x;encrypt=false;loginTimeout=10"));
        assertEquals(18456, error.getErrorCode());
        assertTrue(error.getMessage().contains("Login failed for user 'bad'."), error.getMessage());
    }

    @Test
    void aQueryTimeoutSendsAnAttention() throws Exception {
        int port = gateway(false, GatewaySettings.BackendEncryption.LOGIN_ONLY, false, GatewaySettings.ClientEncryption.OPTIONAL);
        try (Connection connection = connect(port, "encrypt=false");
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            assertThrows(SQLException.class, () -> statement.execute(FakeSqlServer.WAIT_FOR_ATTENTION));
            assertEquals(1, server.attentions.size());
            try (ResultSet rs = statement.executeQuery("SELECT * FROM PERSON")) {
                assertPersonRow(rs);
            }
        }
    }

    @Test
    void strictClientsUseTds8() throws Exception {
        int port = gateway(true, GatewaySettings.BackendEncryption.ON, true, GatewaySettings.ClientEncryption.REQUIRED);
        try (Connection connection = connect(port, "encrypt=strict;hostNameInCertificate=localhost;trustStore="
                + trustStore + ";trustStorePassword=" + PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM PERSON")) {
            assertPersonRow(rs);
        }
        assertEquals(Tds.VERSION_7_4, server.logins.get(0).tdsVersion(), "TDS 7.4 towards a non strict server");
    }

    @Test
    void clientsThatCannotEncryptAreRefusedWhenEncryptionIsRequired() {
        assertEquals(Tds.ENCRYPT_REQ, TdsSession.decideEncryption(Tds.ENCRYPT_NOT_SUP, true,
                GatewaySettings.ClientEncryption.REQUIRED));
        assertEquals(Tds.ENCRYPT_REQ, TdsSession.decideEncryption(Tds.ENCRYPT_OFF, true,
                GatewaySettings.ClientEncryption.REQUIRED));
        assertEquals(Tds.ENCRYPT_OFF, TdsSession.decideEncryption(Tds.ENCRYPT_OFF, true,
                GatewaySettings.ClientEncryption.OPTIONAL));
        assertEquals(Tds.ENCRYPT_ON, TdsSession.decideEncryption(Tds.ENCRYPT_ON, true,
                GatewaySettings.ClientEncryption.OPTIONAL));
        assertEquals(Tds.ENCRYPT_NOT_SUP, TdsSession.decideEncryption(Tds.ENCRYPT_ON, false,
                GatewaySettings.ClientEncryption.REQUIRED));
    }
}
