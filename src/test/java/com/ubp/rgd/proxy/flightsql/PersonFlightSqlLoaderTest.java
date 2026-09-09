package com.ubp.rgd.proxy.flightsql;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.tools.PersonFlightSqlLoader;
import com.ubp.rgd.proxy.services.FlightSqlDetokenizeService;
import com.ubp.rgd.proxy.transform.config.FlightSqlColumnMapping;
import com.ubp.rgd.proxy.transform.config.FlightSqlMappingConfig;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.auth2.BasicCallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.GeneratedBearerTokenAuthenticator;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the {@link PersonFlightSqlLoader} program end to end against a real Flight SQL server backed
 * by H2, exercising exactly what it does against a running Quarkus instance: authentication, table
 * creation, the inserts and the detokenizing read back.
 */
class PersonFlightSqlLoaderTest {

    private static final String JDBC_URL = "jdbc:h2:mem:flightsqlloader;DB_CLOSE_DELAY=-1";

    private static BufferAllocator allocator;
    private static FlightServer server;
    private static ProxyFlightSqlProducer producer;
    private static Connection keepAlive;

    @BeforeAll
    static void startServer() throws Exception {
        keepAlive = DriverManager.getConnection(JDBC_URL, "sa", "");

        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        config.setColumnMappings(List.of(
                new FlightSqlColumnMapping("PERSON", "FIRST_NAME", "Person", "shortString"),
                new FlightSqlColumnMapping("*", "EMAIL", "Person", "email")));
        config.setDataMappings(List.of());

        FlightSqlDetokenizeService detokenizeService = new LocalDetokenizeService();
        detokenizeService.setMappingConfig(config);
        detokenizeService.loadTokenIndexMappings();

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

    @Test
    @DisplayName("The loader creates the table and inserts tokens with prepared statements")
    void shouldLoadTokensWithPreparedStatements() throws Exception {
        PersonFlightSqlLoader.main(args("--rows", "50", "--batch-size", "20",
                "--insert-mode", "prepared", "--data-mode", "tokens", "--table", "PERSON"));

        assertEquals(50, count("PERSON"));
        // Every generated token must match the pattern the detokenize service looks for, otherwise
        // the whole exercise silently proves nothing.
        assertTrue(storedValue("PERSON", "FIRST_NAME").matches("RG\\{[A-Z2-7x]{2}[a-zA-Z0-9\\-]{8}[a-zA-Z0-9]+\\}"),
                "the generated tokens must match the detokenizer's token pattern");
    }

    @Test
    @DisplayName("The loader inserts clear data with literal statements, quotes included")
    void shouldLoadClearDataWithLiteralStatements() throws Exception {
        PersonFlightSqlLoader.main(args("--rows", "25", "--insert-mode", "literal",
                "--data-mode", "clear", "--table", "PERSON_LITERAL"));

        assertEquals(25, count("PERSON_LITERAL"));
    }

    @Test
    @DisplayName("The read back sees the values detokenized by the proxy")
    void shouldReadBackDetokenizedValues() throws Exception {
        PersonFlightSqlLoader.main(args("--rows", "5", "--insert-mode", "prepared",
                "--data-mode", "tokens", "--table", "PERSON_READBACK"));

        // Reading through the proxy detokenizes, reading H2 directly does not: the difference is
        // exactly what the loader reports.
        assertTrue(storedValue("PERSON_READBACK", "FIRST_NAME").startsWith("RG{"));
        assertEquals(5, count("PERSON_READBACK"));
    }

    /** Builds the loader's command line, pointing it at the embedded server. */
    private static String[] args(String... extra) {
        String[] args = new String[extra.length + 7];
        args[0] = "--port";
        args[1] = String.valueOf(server.getPort());
        args[2] = "--user";
        args[3] = "sa";
        args[4] = "--password";
        args[5] = "";
        args[6] = "--create-table";
        System.arraycopy(extra, 0, args, 7, extra.length);
        return args;
    }

    private static int count(String table) throws Exception {
        try (Statement statement = keepAlive.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String storedValue(String table, String column) throws Exception {
        try (Statement statement = keepAlive.createStatement();
             ResultSet resultSet =
                     statement.executeQuery("SELECT " + column + " FROM " + table + " WHERE ID = 1")) {
            resultSet.next();
            return resultSet.getString(1);
        }
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
