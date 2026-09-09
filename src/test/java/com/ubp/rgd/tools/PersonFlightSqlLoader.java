package com.ubp.rgd.tools;

import com.github.javafaker.Faker;
import com.ubp.rgd.proxy.utils.CliArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Loads randomly generated persons into a {@code PERSON} table <b>through the Flight SQL server</b>
 * of this proxy, using the Apache Arrow Flight SQL JDBC driver, then reads them back to check that
 * the tokens really come back detokenized.
 *
 * <p>This is the real-life counterpart of {@code ProxyFlightSqlProducerTest}: it talks to a running
 * Quarkus instance rather than to an embedded server, so it exercises the whole chain — Flight
 * authentication, the proxied datasource, the RPS detokenization and the Arrow conversion.</p>
 *
 * <p>The table deliberately mirrors {@code config/flight_sql_mapping_config.json} so that the three
 * ways of resolving a mapping are all covered:</p>
 * <ul>
 *   <li>{@code FIRST_NAME}, {@code LAST_NAME}, {@code BIRTH_DATE} and {@code EMAIL} resolve through
 *       the <b>column mappings</b>;</li>
 *   <li>{@code CITY} has no column mapping and resolves through the <b>data mappings</b> regexes;</li>
 *   <li>{@code NOTES} carries tokens embedded in free text and resolves through the
 *       <b>token mapping index</b> held by the first two characters of each token.</li>
 * </ul>
 *
 * <p>Run it against a proxy whose {@code proxy.flight-sql.enabled} is {@code true}:</p>
 * <pre>
 * mvn -Pflight-sql-loader test-compile exec:exec -Dloader.args="--user sa --password secret --rows 1000"
 * </pre>
 *
 * <p>Only plain SQL is used, no JPA.</p>
 */
public class PersonFlightSqlLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PersonFlightSqlLoader.class);

    /**
     * Same shape as {@code FlightSqlDetokenizeService.TOKEN_PATTERN}: a value is only detokenized
     * when it matches this, and the first two characters carry the mapping index.
     */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("RG\\{[A-Z2-7x]{2}[a-zA-Z0-9\\-]{8}[a-zA-Z0-9]+\\}");

    /** Characters allowed in the 2 character mapping index prefix of a token. */
    private static final String INDEX_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final String BODY_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final String[] COLUMNS =
            {"ID", "FIRST_NAME", "LAST_NAME", "BIRTH_DATE", "EMAIL", "CITY", "NOTES"};

    public static void main(String[] args) {
        CliArgs cliArgs = new CliArgs(args);

        if (cliArgs.switchPresent("--help")) {
            printUsage();
            return;
        }

        String host = cliArgs.switchValue("--host", "localhost");
        String port = cliArgs.switchValue("--port", "32010");
        String user = cliArgs.switchValue("--user", "sa");
        String password = cliArgs.switchValue("--password", "");
        String table = cliArgs.switchValue("--table", "PERSON");
        String locale = cliArgs.switchValue("--locale", "de-CH");
        String dataMode = cliArgs.switchValue("--data-mode", "tokens");
        String insertMode = cliArgs.switchValue("--insert-mode", "prepared");
        long rows = cliArgs.switchLongValue("--rows", 10000L);
        long batchSize = cliArgs.switchLongValue("--batch-size", 1000L);
        long readBackRows = cliArgs.switchLongValue("--read-back-rows", 10L);
        boolean createTable = cliArgs.switchPresent("--create-table");
        boolean readBack = !cliArgs.switchPresent("--no-read-back");

        if (!"tokens".equalsIgnoreCase(dataMode) && !"clear".equalsIgnoreCase(dataMode)) {
            LOG.error("Invalid --data-mode value, expected tokens or clear: {}", dataMode);
            System.exit(-1);
        }
        if (!"prepared".equalsIgnoreCase(insertMode) && !"literal".equalsIgnoreCase(insertMode)) {
            LOG.error("Invalid --insert-mode value, expected prepared or literal: {}", insertMode);
            System.exit(-1);
        }

        String url = String.format("jdbc:arrow-flight-sql://%s:%s?useEncryption=false", host, port);
        LOG.info("Flight SQL person loader starting...");
        LOG.info("--host / --port  : {}:{}", host, port);
        LOG.info("--user           : {}", user);
        LOG.info("--table          : {}", table);
        LOG.info("--rows           : {}", rows);
        LOG.info("--data-mode      : {} (tokens or clear)", dataMode);
        LOG.info("--insert-mode    : {} (prepared or literal)", insertMode);
        LOG.info("--batch-size     : {} (prepared mode only)", batchSize);
        LOG.info("--create-table   : {}", createTable);
        LOG.info("--read-back      : {} (first {} rows)", readBack, readBackRows);

        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);

        try (Connection connection = DriverManager.getConnection(url, properties)) {
            LOG.info("Connected to {}", connection.getMetaData().getDatabaseProductName());

            if (createTable) {
                createTable(connection, table);
            }

            long startTime = System.currentTimeMillis();
            long inserted = "prepared".equalsIgnoreCase(insertMode)
                    ? insertPrepared(connection, table, rows, (int) batchSize, dataMode, locale)
                    : insertLiteral(connection, table, rows, dataMode, locale);
            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("Inserted {} rows in {} ms ({} rows/s)", inserted, elapsed,
                    elapsed == 0 ? inserted : (inserted * 1000 / elapsed));

            if (readBack) {
                readBack(connection, table, readBackRows);
            }
        } catch (SQLException e) {
            LOG.error("Flight SQL person loading failed", e);
            System.exit(-1);
        }
    }

    private static void printUsage() {
        LOG.info("""
                Loads random persons into a PERSON table through the proxy's Flight SQL server.

                  --host             Flight SQL host (default localhost)
                  --port             Flight SQL port (default 32010)
                  --user             database user, validated by the proxy (default sa)
                  --password         database password (default empty)
                  --table            target table name (default PERSON)
                  --rows             number of persons to generate (default 10000)
                  --locale           faker locale (default de-CH)
                  --data-mode        tokens (default) or clear
                  --insert-mode      prepared (default) or literal
                  --batch-size       rows per prepared batch (default 1000)
                  --create-table     drop and recreate the target table first
                  --no-read-back     skip the verification query
                  --read-back-rows   rows to display when reading back (default 10)
                  --help             print this help
                """);
    }

    /**
     * Create the target table with portable DDL, so that the same program runs against H2 and
     * SQL Server alike. Every token bearing column is a VARCHAR: a tokenized date does not fit a
     * DATE column.
     */
    private static void createTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            LOG.info("Dropping and recreating table {}", table);
            statement.executeUpdate("DROP TABLE IF EXISTS " + table);
            statement.executeUpdate("CREATE TABLE " + table + " ("
                    + "ID INT NOT NULL, "
                    + "FIRST_NAME VARCHAR(255), "
                    + "LAST_NAME VARCHAR(255), "
                    + "BIRTH_DATE VARCHAR(64), "
                    + "EMAIL VARCHAR(255), "
                    + "CITY VARCHAR(255), "
                    + "NOTES VARCHAR(1024))");
        }
    }

    /**
     * Insert with a {@link PreparedStatement}, which the Flight SQL server binds from the Arrow
     * parameter batch sent by the driver. Batching several rows into one execution turns the insert
     * into a single round trip per batch.
     */
    private static long insertPrepared(Connection connection, String table, long rows, int batchSize,
                                       String dataMode, String locale) throws SQLException {
        String sql = "INSERT INTO " + table + " (" + String.join(", ", COLUMNS)
                + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
        Generator generator = new Generator(locale, dataMode);
        long inserted = 0;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int pending = 0;
            for (long row = 0; row < rows; row++) {
                Object[] values = generator.next(row);
                for (int i = 0; i < values.length; i++) {
                    statement.setObject(i + 1, values[i]);
                }
                statement.addBatch();
                pending++;

                if (pending == batchSize) {
                    inserted += countOf(statement.executeBatch(), pending);
                    pending = 0;
                    LOG.info("Inserted {} / {} rows", inserted, rows);
                }
            }
            if (pending > 0) {
                inserted += countOf(statement.executeBatch(), pending);
            }
        }
        return inserted;
    }

    /**
     * Insert with a plain {@link Statement} and literal values. Note that the driver does not
     * support {@code Statement.addBatch}, so every row costs one round trip.
     */
    private static long insertLiteral(Connection connection, String table, long rows,
                                      String dataMode, String locale) throws SQLException {
        Generator generator = new Generator(locale, dataMode);
        long inserted = 0;

        try (Statement statement = connection.createStatement()) {
            for (long row = 0; row < rows; row++) {
                Object[] values = generator.next(row);
                StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                        .append(" (").append(String.join(", ", COLUMNS)).append(") VALUES (");
                for (int i = 0; i < values.length; i++) {
                    sql.append(i == 0 ? "" : ", ").append(literal(values[i]));
                }
                sql.append(")");

                inserted += statement.executeUpdate(sql.toString());
                if (inserted % 100 == 0) {
                    LOG.info("Inserted {} / {} rows", inserted, rows);
                }
            }
        }
        return inserted;
    }

    /**
     * Render a value as a SQL literal. Quotes are doubled: the generated names really do contain
     * apostrophes, which would otherwise break the statement.
     */
    private static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    /**
     * Sum the update counts of a batch, falling back to the number of statements when the driver
     * answers {@code SUCCESS_NO_INFO} instead of a row count.
     */
    private static long countOf(int[] updateCounts, int expected) {
        long total = 0;
        for (int count : updateCounts) {
            total += count > 0 ? count : 0;
        }
        return total == 0 ? expected : total;
    }

    /**
     * Query the rows back through the proxy and report how many values came back detokenized versus
     * how many still look like a token.
     *
     * <p>No {@code LIMIT} or {@code TOP} is used: neither is portable across H2 and SQL Server, so
     * the displayed rows are simply counted on the client side.</p>
     */
    private static void readBack(Connection connection, String table, long displayRows)
            throws SQLException {
        String sql = "SELECT " + String.join(", ", COLUMNS) + " FROM " + table;
        LOG.info("Reading back: {}", sql);

        long total = 0;
        long stillTokenized = 0;
        long values = 0;

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {

            while (resultSet.next()) {
                List<String> displayed = new ArrayList<>();
                for (int column = 2; column <= COLUMNS.length; column++) {
                    String value = resultSet.getString(column);
                    if (value == null) {
                        continue;
                    }
                    values++;
                    if (TOKEN_PATTERN.matcher(value).find()) {
                        stillTokenized++;
                    }
                    if (total < displayRows) {
                        displayed.add(COLUMNS[column - 1] + "=" + value);
                    }
                }
                if (total < displayRows) {
                    LOG.info("  row {}: {}", resultSet.getInt(1), String.join(", ", displayed));
                }
                total++;
            }
        }

        LOG.info("Read back {} rows, {} non null values, {} still holding a token",
                total, values, stillTokenized);
        if (stillTokenized > 0) {
            LOG.warn("{} values were not detokenized: check the mapping configuration and that the"
                    + " RPS engine knows these tokens.", stillTokenized);
        }
    }

    /** Generates the column values of one person, either as tokens or in clear. */
    private static final class Generator {

        private final Faker faker;
        private final Random random = new Random();
        private final boolean tokens;

        private Generator(String locale, String dataMode) {
            this.faker = new Faker(Locale.forLanguageTag(locale));
            this.tokens = "tokens".equalsIgnoreCase(dataMode);
        }

        private Object[] next(long row) {
            int id = (int) row + 1;
            if (!tokens) {
                return new Object[]{
                        id,
                        faker.name().firstName(),
                        faker.name().lastName(),
                        faker.date().birthday(18, 95).toInstant().toString().substring(0, 10),
                        faker.internet().emailAddress(),
                        faker.address().city(),
                        "Client met on " + faker.date().birthday(1, 5).toInstant().toString()
                                .substring(0, 10) + " in " + faker.address().city()
                };
            }
            return new Object[]{
                    id,
                    token(),
                    token(),
                    token(),
                    token(),
                    token(),
                    // Tokens embedded in free text: only the token itself must be replaced.
                    "Client " + token() + " met in " + token() + ", follow up required"
            };
        }

        /**
         * Build a value matching the token pattern of the detokenize service, with a valid two
         * character mapping index so that the index resolver can name a class and a property.
         */
        private String token() {
            StringBuilder token = new StringBuilder("RG{");
            token.append(INDEX_CHARS.charAt(random.nextInt(INDEX_CHARS.length())));
            token.append(INDEX_CHARS.charAt(random.nextInt(INDEX_CHARS.length())));
            for (int i = 0; i < 10; i++) {
                token.append(BODY_CHARS.charAt(random.nextInt(BODY_CHARS.length())));
            }
            return token.append('}').toString();
        }
    }
}
