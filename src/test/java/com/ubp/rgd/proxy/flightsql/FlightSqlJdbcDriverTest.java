package com.ubp.rgd.proxy.flightsql;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.services.FlightSqlDetokenizeService;
import com.ubp.rgd.proxy.transform.config.FlightSqlColumnMapping;
import com.ubp.rgd.proxy.transform.config.FlightSqlDataMapping;
import com.ubp.rgd.proxy.transform.config.FlightSqlMappingConfig;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.auth2.BasicCallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.GeneratedBearerTokenAuthenticator;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the Flight SQL server with the real Apache Arrow Flight SQL <b>JDBC driver</b>, the way
 * {@code PersonFlightSqlLoader} does, rather than with the lower level {@code FlightSqlClient}.
 *
 * <p>This covers the client side of the prepared statement parameter binding: the driver refuses
 * every {@code setXxx} call unless the server advertises a parameter schema, so these tests would
 * fail if {@code createPreparedStatement} stopped publishing one.</p>
 */
class FlightSqlJdbcDriverTest {

    private static final String JDBC_URL = "jdbc:h2:mem:flightsqljdbc;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    /** A value matching the token pattern, with a valid 2 character mapping index. */
    private static final String TOKEN = "RG{AB12345678aa}";

    private static BufferAllocator allocator;
    private static FlightServer server;
    private static ProxyFlightSqlProducer producer;
    private static FlightSqlDetokenizeService detokenizeService;
    private static Connection keepAlive;

    private Connection connection;

    @BeforeAll
    static void startServer() throws Exception {
        keepAlive = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);

        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        config.setColumnMappings(List.of(
                new FlightSqlColumnMapping("PERSON", "FIRST_NAME", "Person", "shortString")));
        config.setDataMappings(List.of(
                new FlightSqlDataMapping("RG\\{EF[a-zA-Z0-9\\-]{8}[a-zA-Z0-9]+\\}", "Person", "city")));

        detokenizeService = new LocalDetokenizeService();
        detokenizeService.setMappingConfig(config);

        FlightSqlConnectionManager connectionManager = new FlightSqlConnectionManager();
        connectionManager.jdbcUrl = JDBC_URL;

        allocator = new RootAllocator(Long.MAX_VALUE);
        producer = new ProxyFlightSqlProducer(allocator, connectionManager, detokenizeService, 1024);
        server = FlightServer.builder(allocator, Location.forGrpcInsecure("localhost", 0), producer)
                .headerAuthenticator(new GeneratedBearerTokenAuthenticator(
                        new BasicCallHeaderAuthenticator(connectionManager)))
                .build();
        server.start();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.shutdown();
            server.awaitTermination();
            server.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (allocator != null) {
            allocator.close();
        }
        if (keepAlive != null) {
            keepAlive.close();
        }
    }

    @BeforeEach
    void connect() throws Exception {
        // The service is built outside of CDI here, so @PostConstruct never ran.
        detokenizeService.loadTokenIndexMappings();

        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS PERSON");
            statement.execute("CREATE TABLE PERSON (ID INT, FIRST_NAME VARCHAR(255),"
                    + " CITY VARCHAR(255))");
        }

        Properties properties = new Properties();
        properties.setProperty("user", USER);
        properties.setProperty("password", PASSWORD);
        connection = DriverManager.getConnection(
                "jdbc:arrow-flight-sql://localhost:" + server.getPort() + "?useEncryption=false",
                properties);
    }

    @org.junit.jupiter.api.AfterEach
    void disconnect() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("A literal insert goes through a plain statement")
    void shouldInsertWithALiteralStatement() throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "INSERT INTO PERSON VALUES (1, 'Bob', 'Geneva')"));
        }
        // "Bob" is not a token, so the detokenizer leaves it alone.
        assertEquals(Arrays.asList("1", "Bob", "Geneva"), rows().getFirst());
    }

    @Test
    @DisplayName("A prepared statement binds its parameters on the proxied database")
    void shouldInsertWithAPreparedStatement() throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("INSERT INTO PERSON VALUES (?, ?, ?)")) {
            statement.setInt(1, 7);
            statement.setString(2, "Alice");
            statement.setString(3, "Zurich");

            assertEquals(1, statement.executeUpdate());
        }

        List<List<String>> rows = rows();
        assertEquals(1, rows.size());
        // The values really reached the database rather than being silently dropped.
        assertEquals(Arrays.asList("7", "Alice", "Zurich"), rows.getFirst());
    }

    @Test
    @DisplayName("A prepared batch inserts every row of the parameter batch")
    void shouldInsertABatchOfPreparedRows() throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("INSERT INTO PERSON VALUES (?, ?, ?)")) {
            for (int i = 1; i <= 5; i++) {
                statement.setInt(1, i);
                statement.setString(2, "Name" + i);
                statement.setString(3, "City" + i);
                statement.addBatch();
            }
            statement.executeBatch();
        }

        List<List<String>> rows = rows();
        assertEquals(5, rows.size(), "every row of the parameter batch must be inserted");
        assertEquals(Arrays.asList("3", "Name3", "City3"), rows.get(2));
    }

    @Test
    @DisplayName("A null parameter is bound as a SQL NULL")
    void shouldBindANullParameter() throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("INSERT INTO PERSON VALUES (?, ?, ?)")) {
            statement.setInt(1, 1);
            statement.setString(2, null);
            statement.setString(3, "Geneva");
            statement.executeUpdate();
        }

        assertNull(rows().getFirst().get(1));
    }

    @Test
    @DisplayName("A value carrying a token comes back detokenized through the driver")
    void shouldDetokenizeThroughTheDriver() throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("INSERT INTO PERSON VALUES (?, ?, ?)")) {
            statement.setInt(1, 1);
            statement.setString(2, TOKEN);
            statement.setString(3, "RG{EF11111111cc}");
            statement.executeUpdate();
        }

        List<List<String>> rows = rows();
        // FIRST_NAME resolves through the column mapping, CITY through the data mapping regex.
        assertEquals("Person.shortString=" + TOKEN, rows.getFirst().get(1));
        assertEquals("Person.city=RG{EF11111111cc}", rows.getFirst().get(2));
    }

    @Test
    @DisplayName("A prepared query binds its parameters instead of ignoring them")
    void shouldBindTheParametersOfAPreparedQuery() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO PERSON VALUES (1, 'Bob', 'Geneva')");
            statement.executeUpdate("INSERT INTO PERSON VALUES (2, 'Alice', 'Zurich')");
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT ID, CITY FROM PERSON WHERE ID = ?")) {
            statement.setInt(1, 2);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "the parameter must filter the result set");
                assertEquals(2, resultSet.getInt(1));
                assertEquals("Zurich", resultSet.getString(2));
                assertFalse(resultSet.next(), "only the matching row must be returned");
            }
        }
    }

    @Test
    @DisplayName("DDL is forwarded to the proxied database")
    void shouldExecuteDdl() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE ANOTHER_TABLE (ID INT)");
        }
        try (Statement statement = keepAlive.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM ANOTHER_TABLE")) {
            assertTrue(resultSet.next());
        }
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("DROP TABLE ANOTHER_TABLE");
        }
    }

    @Test
    @DisplayName("Bad credentials are rejected by the proxied database")
    void shouldRejectBadCredentials() {
        Properties properties = new Properties();
        properties.setProperty("user", "nobody");
        properties.setProperty("password", "wrong");

        assertThrows(SQLException.class, () -> DriverManager.getConnection(
                "jdbc:arrow-flight-sql://localhost:" + server.getPort() + "?useEncryption=false",
                properties));
    }

    private List<List<String>> rows() throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet =
                     statement.executeQuery("SELECT ID, FIRST_NAME, CITY FROM PERSON ORDER BY ID")) {
            while (resultSet.next()) {
                // Arrays.asList, not List.of: a detokenized column may legitimately be null.
                rows.add(Arrays.asList(resultSet.getString(1), resultSet.getString(2),
                        resultSet.getString(3)));
            }
        }
        return rows;
    }

    /** Replaces each located segment by {@code <class>.<property>=<value>} instead of calling RPS. */
    private static class LocalDetokenizeService extends FlightSqlDetokenizeService {

        @Override
        protected void transformValues(List<RPSValue> values) {
            for (RPSValue value : values) {
                RPSMapping mapping = value.getMapping();
                value.setTransformed(mapping.getClassName() + "." + mapping.getPropertyName()
                        + "=" + value.getOriginal());
            }
        }
    }
}
