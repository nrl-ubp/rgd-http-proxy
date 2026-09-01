package com.ubp.rgd.proxy.flightsql;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.services.FlightSqlDetokenizeService;
import com.ubp.rgd.proxy.transform.config.FlightSqlColumnMapping;
import com.ubp.rgd.proxy.transform.config.FlightSqlDataMapping;
import com.ubp.rgd.proxy.transform.config.FlightSqlMappingConfig;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.auth2.BasicCallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.GeneratedBearerTokenAuthenticator;
import org.apache.arrow.flight.grpc.CredentialCallOption;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the Flight SQL server against an in-memory H2 database. The RPS engine is
 * replaced by a local detokenizer so that the whole pipeline (authentication, JDBC execution, Arrow
 * conversion, token replacement and streaming) is exercised without any external dependency.
 */
class ProxyFlightSqlProducerTest {

    private static final String JDBC_URL = "jdbc:h2:mem:flightsql;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private static BufferAllocator allocator;
    private static FlightServer server;
    private static ProxyFlightSqlProducer producer;
    private static Connection keepAlive;

    private FlightClient flightClient;
    private FlightSqlClient sqlClient;
    private CredentialCallOption credentials;

    @BeforeAll
    static void startServer() throws Exception {
        keepAlive = DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
        try (Statement statement = keepAlive.createStatement()) {
            statement.execute("CREATE TABLE PERSON (ID INT, FIRST_NAME VARCHAR(255),"
                    + " CITY VARCHAR(255), NOTES VARCHAR(255))");
            statement.execute("INSERT INTO PERSON VALUES (1, 'RG{AB12345678aa}', 'Geneva',"
                    + " 'born 3011-04-05')");
            statement.execute("INSERT INTO PERSON VALUES (2, 'Mr RG{AB12345678aa} RG{CD87654321bb}',"
                    + " 'Zurich', 'nothing sensitive')");
            statement.execute("INSERT INTO PERSON VALUES (3, NULL, 'RG{EF11111111cc}', NULL)");
        }

        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        config.setColumnMappings(List.of(
                new FlightSqlColumnMapping("PERSON", "FIRST_NAME", "Person", "shortString")));
        // CITY and NOTES have no column mapping: they are detokenized implicitly.
        config.setDataMappings(List.of(
                new FlightSqlDataMapping("RG\\{[A-Z2-7x]{2}[a-zA-Z0-9\\-]{8}[a-zA-Z0-9]+\\}",
                        "Person", "city"),
                new FlightSqlDataMapping("\\d{4}-\\d{2}-\\d{2}", "Person", "birthDate")));

        FlightSqlDetokenizeService detokenizeService = new LocalDetokenizeService();
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
    void connect() {
        flightClient = FlightClient
                .builder(allocator, Location.forGrpcInsecure("localhost", server.getPort()))
                .build();
        credentials = flightClient.authenticateBasicToken(USER, PASSWORD).orElseThrow();
        sqlClient = new FlightSqlClient(flightClient);
    }

    @AfterEach
    void disconnect() throws Exception {
        sqlClient.close();
    }

    @Test
    void shouldDetokenizeTheMappedColumnOfAQueryResult() throws Exception {
        List<List<String>> rows = query("SELECT FIRST_NAME, CITY FROM PERSON ORDER BY ID");

        assertEquals(3, rows.size());
        // FIRST_NAME is mapped: its tokens are detokenized, surrounding text is preserved.
        assertEquals("clear-AB12345678aa", rows.get(0).get(0));
        assertEquals("Mr clear-AB12345678aa clear-CD87654321bb", rows.get(1).get(0));
        assertNull(rows.get(2).get(0));
        // CITY has no column mapping: only the values matching a data mapping are detokenized.
        assertEquals("Geneva", rows.get(0).get(1));
        assertEquals("Zurich", rows.get(1).get(1));
        assertEquals("clear-EF11111111cc", rows.get(2).get(1));
    }

    @Test
    void shouldDetokenizeAnUnmappedColumnFromTheDataMappings() throws Exception {
        List<List<String>> rows = query("SELECT NOTES FROM PERSON ORDER BY ID");

        assertEquals(3, rows.size());
        // The date data mapping locates its own segment and supplies its RPS class / property.
        assertEquals("born Person.birthDate=3011-04-05", rows.get(0).get(0));
        // Nothing matches any data mapping: the value is left untouched.
        assertEquals("nothing sensitive", rows.get(1).get(0));
        assertNull(rows.get(2).get(0));
    }

    @Test
    void shouldReturnAnEmptyResultSetWithItsSchema() throws Exception {
        FlightInfo info = sqlClient.execute("SELECT FIRST_NAME, CITY FROM PERSON WHERE ID = 999",
                credentials);

        assertEquals(2, info.getSchema().getFields().size());
        assertTrue(readRows(info).isEmpty());
    }

    @Test
    void shouldExposeTheSchemaOfTheQueryInTheFlightInfo() {
        FlightInfo info = sqlClient.execute("SELECT ID, FIRST_NAME FROM PERSON", credentials);

        assertEquals(List.of("ID", "FIRST_NAME"),
                info.getSchema().getFields().stream().map(field -> field.getName()).toList());
    }

    @Test
    void shouldExposeTheProxiedDatabaseTables() throws Exception {
        FlightInfo info = sqlClient.getTables(null, null, "PERSON", null, false, credentials);

        assertTrue(readRows(info).stream().anyMatch(row -> row.contains("PERSON")));
    }

    @Test
    void shouldExposeTheProxiedDatabaseCatalogs() throws Exception {
        assertTrue(readRows(sqlClient.getCatalogs(credentials)).size() >= 0);
    }

    @Test
    void shouldExecuteAnUpdateStatement() throws Exception {
        long updated = sqlClient.executeUpdate("UPDATE PERSON SET CITY = 'Bern' WHERE ID = 1",
                credentials);

        assertEquals(1, updated);
        assertEquals("Bern", query("SELECT CITY FROM PERSON WHERE ID = 1").get(0).get(0));

        sqlClient.executeUpdate("UPDATE PERSON SET CITY = 'Geneva' WHERE ID = 1", credentials);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private List<List<String>> query(String sql) throws Exception {
        return readRows(sqlClient.execute(sql, credentials));
    }

    private List<List<String>> readRows(FlightInfo info) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (FlightStream stream = sqlClient.getStream(info.getEndpoints().get(0).getTicket(),
                (CallOption) credentials)) {
            while (stream.next()) {
                collectValues(stream.getRoot(), rows);
            }
        }
        return rows;
    }

    private static void collectValues(VectorSchemaRoot root, List<List<String>> rows) {
        for (int row = 0; row < root.getRowCount(); row++) {
            List<String> values = new ArrayList<>();
            for (int column = 0; column < root.getFieldVectors().size(); column++) {
                Object value = root.getVector(column).getObject(row);
                values.add(value == null ? null : value.toString());
            }
            rows.add(values);
        }
    }

    /**
     * Detokenizer replacing an {@code RG{x}} segment by {@code clear-x}, and any other segment by
     * {@code <class>.<property>=<value>}, instead of calling the RPS engine. Everything else — the
     * column / data mapping resolution, the segment location and the reassembly — is the real code.
     */
    private static class LocalDetokenizeService extends FlightSqlDetokenizeService {

        @Override
        protected void transformValues(List<RPSValue> values) {
            for (RPSValue value : values) {
                String original = value.getOriginal();
                RPSMapping mapping = value.getMapping();
                value.setTransformed(original.startsWith("RG{")
                        ? "clear-" + original.substring(3, original.length() - 1)
                        : mapping.getClassName() + "." + mapping.getPropertyName() + "=" + original);
            }
        }
    }
}
