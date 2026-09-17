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
import java.util.regex.Pattern;

/**
 * Loads randomly generated persons into a {@code PERSON} table <b>through the Flight SQL server</b>
 * of this proxy, using the Apache Arrow Flight SQL JDBC driver, then reads them back to check that
 * the tokens really come back detokenized.
 *
 * <p>This is the real-life counterpart of {@code ProxyFlightSqlProducerTest}: it talks to a running
 * Quarkus instance rather than to an embedded server, so it exercises the whole chain â€” Flight
 * authentication, the proxied datasource, the RPS detokenization and the Arrow conversion.</p>
 *
 * <p>In its default {@code transform} mode the loader does not fabricate tokens: it generates clear
 * values and wraps each of them in a call of the <b>{@code transform()} SQL extension</b>, so the
 * proxy tokenizes them for real on the way in:</p>
 * <pre>
 * INSERT INTO PERSON (ID, FIRST_NAME, ...)
 * VALUES (1, transform('Person', 'ShortString', 'LU', 'Jean-Claude'), ...)
 * </pre>
 *
 * <p>That makes the whole round trip meaningful: the values are tokenized by the engine on the way
 * in and detokenized on the way out, instead of being random strings the engine has never seen.</p>
 *
 * <p>The class, the property and the jurisdiction are the same for every column, and overridable
 * through {@code --transform-class}, {@code --transform-property} and
 * {@code --transform-jurisdiction}: a {@code transform()} call states its mapping explicitly, the
 * column mappings of {@code config/flight_sql_mapping_config.json} only existing for the implicit
 * detokenization of the columns that declare nothing.</p>
 *
 * <p><b>The read-back is a different matter.</b> It resolves each column through the configured
 * column mappings, so {@code BIRTH_DATE} and {@code EMAIL}, mapped to {@code Person.birthDate} and
 * {@code Person.email}, are detokenized with a different mapping than the one they were tokenized
 * with. That is a property of the configuration, not of this loader.</p>
 *
 * <p>Run it against a proxy whose {@code proxy.flight-sql.enabled} is {@code true}:</p>
 * <pre>
 * mvn -Pflight-sql-loader test-compile exec:exec \
 *     -Dloader.args="--user sa --password secret --rows 1000 --insert-mode literal"
 * </pre>
 *
 * <p>Only plain SQL is used, no JPA.</p>
 */
public class PersonFlightSqlLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PersonFlightSqlLoader.class);

    /**
     * Same shape as {@code FlightSqlDetokenizeService.TOKEN_PATTERN}. Used by the read-back only, to
     * report the values that came back still holding a token.
     */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("RG\\{[A-Z2-7x]{2}[a-zA-Z0-9\\-]{8}[a-zA-Z0-9]+\\}");

    private static final String[] COLUMNS =
            {"ID", "FIRST_NAME", "LAST_NAME", "BIRTH_DATE", "EMAIL", "CITY", "NOTES"};

    public static void main(String[] args) {
        int status = run(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Run the loader and report whether it succeeded.
     * <p>
     * Separated from {@link #main(String[])} so the tests can call it: a {@code System.exit} inside a
     * surefire fork kills the whole test class and reports no test at all.
     *
     * @param args the command line
     * @return 0 when the rows were loaded, -1 otherwise
     */
    public static int run(String[] args) {
        CliArgs cliArgs = new CliArgs(args);

        if (cliArgs.switchPresent("--help")) {
            printUsage();
            return 0;
        }

        String host = cliArgs.switchValue("--host", "localhost");
        String port = cliArgs.switchValue("--port", "32010");
        String user = cliArgs.switchValue("--user", "CDM_DBO");
        String password = cliArgs.switchValue("--password", "HIGnkjpihFhWjdvobHMiyz");
        String table = cliArgs.switchValue("--table", "PERSON");
        String locale = cliArgs.switchValue("--locale", "de-CH");
        String dataMode = cliArgs.switchValue("--data-mode", "transform");
        String insertMode = cliArgs.switchValue("--insert-mode", "prepared");
        String transformClass = cliArgs.switchValue("--transform-class", "Person");
        String transformProperty = cliArgs.switchValue("--transform-property", "ShortString");
        String transformJurisdiction = cliArgs.switchValue("--transform-jurisdiction", "LU");
        long rows = cliArgs.switchLongValue("--rows", 10000L);
        long batchSize = cliArgs.switchLongValue("--batch-size", 1000L);
        long readBackRows = cliArgs.switchLongValue("--read-back-rows", 10L);
        boolean createTable = cliArgs.switchPresent("--create-table");
        boolean readBack = !cliArgs.switchPresent("--no-read-back");

        if ("tokens".equalsIgnoreCase(dataMode)) {
            // The loader no longer fabricates tokens: it asks the proxy for real ones.
            LOG.error("The --data-mode tokens value no longer exists. Use --data-mode transform,"
                    + " which sends clear values through the transform() SQL extension so the proxy"
                    + " tokenizes them for real.");
            return -1;
        }
        if (!"transform".equalsIgnoreCase(dataMode) && !"clear".equalsIgnoreCase(dataMode)) {
            LOG.error("Invalid --data-mode value, expected transform or clear: {}", dataMode);
            return -1;
        }
        if (!"prepared".equalsIgnoreCase(insertMode) && !"literal".equalsIgnoreCase(insertMode)) {
            LOG.error("Invalid --insert-mode value, expected prepared or literal: {}", insertMode);
            return -1;
        }
        if ("transform".equalsIgnoreCase(dataMode) && "prepared".equalsIgnoreCase(insertMode)) {
            // A prepared parameter is bound as an Arrow value: the call would be stored as text and
            // never evaluated, filling the table with inert function calls.
            LOG.error("--data-mode transform requires --insert-mode literal: a transform() call must"
                    + " be part of the statement, a bound parameter is never evaluated.");
            return -1;
        }

        String url = String.format("jdbc:arrow-flight-sql://%s:%s?useEncryption=false", host, port);
        LOG.info("Flight SQL person loader starting...");
        LOG.info("--host / --port  : {}:{}", host, port);
        LOG.info("--user           : {}", user);
        LOG.info("--table          : {}", table);
        LOG.info("--rows           : {}", rows);
        LOG.info("--data-mode      : {} (transform or clear)", dataMode);
        LOG.info("--insert-mode    : {} (prepared or literal)", insertMode);
        LOG.info("--batch-size     : {} (prepared mode only)", batchSize);
        LOG.info("--create-table   : {}", createTable);
        LOG.info("--read-back      : {} (first {} rows)", readBack, readBackRows);
        if ("transform".equalsIgnoreCase(dataMode)) {
            LOG.info("--transform-*    : transform('{}', '{}', '{}', <clear value>)",
                    transformClass, transformProperty, transformJurisdiction);
        }

        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);

        try (Connection connection = DriverManager.getConnection(url, properties)) {
            LOG.info("Connected to {}", connection.getMetaData().getDatabaseProductName());

            if (createTable) {
                createTable(connection, table);
            }

            Generator generator = new Generator(locale, dataMode,
                    transformClass, transformProperty, transformJurisdiction);

            long startTime = System.currentTimeMillis();
            long inserted = "prepared".equalsIgnoreCase(insertMode)
                    ? insertPrepared(connection, table, rows, (int) batchSize, generator)
                    : insertLiteral(connection, table, rows, generator);
            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("Inserted {} rows in {} ms ({} rows/s)", inserted, elapsed,
                    elapsed == 0 ? inserted : (inserted * 1000 / elapsed));

            if (readBack) {
                readBack(connection, table, readBackRows);
            }
        } catch (SQLException e) {
            LOG.error("Flight SQL person loading failed", e);
            return -1;
        }
        return 0;
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
                  --data-mode        transform (default) or clear
                                     transform sends the clear values through the transform() SQL
                                     extension, so the proxy tokenizes them; it requires
                                     --insert-mode literal
                  --insert-mode      prepared (default) or literal
                  --transform-class        RPS class name of the transform() calls (default Person)
                  --transform-property     RPS property name (default ShortString)
                  --transform-jurisdiction jurisdiction, ignored by the proxy for now (default LU)
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
                                       Generator generator) throws SQLException {
        String sql = "INSERT INTO " + table + " (" + String.join(", ", COLUMNS)
                + ") VALUES (?, ?, ?, ?, ?, ?, ?)";
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
                                      Generator generator) throws SQLException {
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
    public static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof SqlExpression expression) {
            // Already SQL: quoting it would turn the transform() call into an inert string.
            return expression.sql();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return quote(value.toString());
    }

    /** Quote a value as a SQL text literal, the apostrophes the generated names carry doubled. */
    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** A value that is already a SQL fragment and must be emitted as it is. */
    public record SqlExpression(String sql) {
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

    /**
     * Generates the column values of one person, either in clear or wrapped in a {@code transform()}
     * call so the proxy tokenizes them on the way in.
     */
    public static final class Generator {

        private final Faker faker;
        private final boolean transform;
        private final String className;
        private final String propertyName;
        private final String jurisdiction;

        public Generator(String locale, String dataMode,
                  String className, String propertyName, String jurisdiction) {
            this.faker = new Faker(Locale.forLanguageTag(locale));
            this.transform = "transform".equalsIgnoreCase(dataMode);
            this.className = className;
            this.propertyName = propertyName;
            this.jurisdiction = jurisdiction;
        }

        Object[] next(long row) {
            int id = (int) row + 1;
            Object[] clear = {
                    id,
                    faker.name().firstName(),
                    faker.name().lastName(),
                    faker.date().birthday(18, 95).toInstant().toString().substring(0, 10),
                    faker.internet().emailAddress(),
                    faker.address().city(),
                    "Client met on " + faker.date().birthday(1, 5).toInstant().toString()
                            .substring(0, 10) + " in " + faker.address().city()
            };
            if (!transform) {
                return clear;
            }
            // The ID stays a plain number; every other column is handed to the proxy to be tokenized.
            Object[] values = new Object[clear.length];
            values[0] = clear[0];
            for (int i = 1; i < clear.length; i++) {
                values[i] = transformCall(clear[i].toString());
            }
            return values;
        }

        /**
         * Wrap a clear value in a call of the {@code transform()} SQL extension, which the proxy
         * replaces by the token of that value before the statement reaches the database server.
         */
        public SqlExpression transformCall(String clearValue) {
            return new SqlExpression("transform(" + quote(className) + ", " + quote(propertyName)
                    + ", " + quote(jurisdiction) + ", " + quote(clearValue) + ")");
        }
    }
}
