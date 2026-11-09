package com.ubp.rgd.tools;

import com.ubp.rgd.proxy.cli.CliArgs;
import net.datafaker.Faker;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Generates the search training data.
 *
 * <h2>{@code --source db} (default): from the protected database</h2>
 * The training vocabulary of the fuzzy pass is built <strong>by the fuzzy
 * search of the running proxy</strong>: it reads the {@code PERSON} table,
 * unprotects the names with the transformer of the rgd-http-proxy (FPE or RPS,
 * the one it is configured with) and keeps the vocabulary in memory. This
 * program only triggers it, so the key, the engine and the mappings stay in
 * the service configuration:
 * <ul>
 *   <li>without {@code --reindex}: {@code POST /api/fuzzy-search/admin/training-data}, the
 *       vocabulary only;</li>
 *   <li>with {@code --reindex}: {@code POST /api/fuzzy-search/admin/reindex}, the clean
 *       reindex of OpenSearch and the vocabulary in a single pass.</li>
 * </ul>
 * <pre>
 *   mvn -Pfuzzy-search-tools -pl rgd-proxy-fuzzysearch test-compile exec:exec \
 *       -Dtool.args="--source db --url http://localhost:8080 --user u --password p --reindex"
 * </pre>
 *
 * <h2>{@code --source faker}: fake persons for an empty database</h2>
 * Writes {@code models/fake_persons.csv}
 * ({@code firstName,lastName,email,birthDate,phone,country,iban}), names
 * sampled from 15 locales. Load it with {@link LoadFakePersons}, which protects
 * every person through {@code POST /persons}, then run this program with
 * {@code --source db}.
 * <pre>
 *   mvn -Pfuzzy-search-tools -pl rgd-proxy-fuzzysearch test-compile exec:exec \
 *       -Dtool.args="--source faker --rows 20000 --csv models/fake_persons.csv"
 * </pre>
 */
public class GenerateTrainingData {

    // -----------------------------------------------------------------------
    // Defaults
    // -----------------------------------------------------------------------

    private static final int    DEFAULT_NUM_PERSONS   = 20_000;
    private static final String DEFAULT_CSV_FILE      = "models/fake_persons.csv";

    /**
     * Locales used for generating names with cultural diversity.
     * DataFaker supports a wide range of locales; each person is randomly
     * assigned one of these.
     */
    private static final String[] LOCALES = {
            "en", "fr", "de", "es", "it", "pt", "ar", "zh-CN", "ja", "ko",
            "ru", "tr", "pl", "nl", "sv"
    };

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    /**
     * Runs the program.
     *
     * @param args the command line
     * @return 0 on success, 1 on failure, 2 on a usage error
     */
    public static int run(String[] args) throws Exception {
        CliArgs cli = new CliArgs(args);
        if (cli.switchPresent("--help")) {
            printUsage();
            return 0;
        }
        String source = cli.switchValue("--source", "db");
        if ("faker".equalsIgnoreCase(source)) {
            int rows = Integer.parseInt(cli.switchValue("--rows", String.valueOf(DEFAULT_NUM_PERSONS)));
            generateFaker(rows, cli.switchValue("--csv", DEFAULT_CSV_FILE));
            return 0;
        }
        if (!"db".equalsIgnoreCase(source)) {
            System.err.println("Unknown --source " + source + ", expected db or faker.");
            printUsage();
            return 2;
        }

        URI uri = endpoint(cli.switchValue("--url", FuzzySearchClient.DEFAULT_URL), cli.switchPresent("--reindex"),
                cli.switchValue("--batch-size", null));
        System.out.println("=== GenerateTrainingData (database) ===");
        System.out.println("  POST " + uri);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = FuzzySearchClient.authenticate(HttpRequest.newBuilder(uri),
                        cli.switchValue("--user", null), cli.switchValue("--password", null))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("  HTTP " + response.statusCode() + ": " + response.body());
        if (response.statusCode() == 409) {
            System.err.println("A run is already in progress on the service, try again later.");
        }
        return response.statusCode() / 100 == 2 ? 0 : 1;
    }

    /**
     * The endpoint to call for the database source.
     *
     * @param baseUrl   the base URL of the service
     * @param reindex   whether to run the clean reindex rather than the vocabulary only
     * @param batchSize the rows per page, {@code null} for the service default
     * @return the endpoint URI
     */
    static URI endpoint(String baseUrl, boolean reindex, String batchSize) {
        String path = reindex ? "/admin/reindex" : "/admin/training-data";
        String query = batchSize == null ? "" : "?batchSize=" + Integer.parseInt(batchSize.trim());
        return FuzzySearchClient.endpoint(baseUrl, path + query);
    }

    private static void printUsage() {
        System.out.println("""
                GenerateTrainingData
                  --source db       (default) ask the running service to build the training vocabulary
                                    from the PERSON table, unprotected with the proxy transformer
                    --url URL       base URL of the proxy (default http://localhost:8080)
                    --user U        user of the Basic authentication of the proxy (local calls)
                    --password P    its password
                    --reindex       clean reindex of OpenSearch and the vocabulary in one pass
                    --batch-size N  rows read and unprotected per page (service default 500)
                  --source faker    write fake persons to a CSV, to load with LoadFakePersons
                    --rows N        persons to generate (default 20000)
                    --csv FILE      output file (default models/fake_persons.csv)
                  --help            print this help
                """);
    }

    // -----------------------------------------------------------------------
    // Faker source
    // -----------------------------------------------------------------------

    private static void generateFaker(int numPersons, String csvOut) throws IOException {
        System.out.println("=== GenerateTrainingData (faker) ===");
        System.out.println("  Persons to generate : " + numPersons);
        System.out.println("  CSV output          : " + csvOut);
        System.out.println("  Locales             : " + Arrays.toString(LOCALES));
        System.out.println();

        Random random = new Random(42); // fixed seed for reproducibility

        // Date formatter for birthDate column (ISO 8601)
        SimpleDateFormat isoDate = new SimpleDateFormat("yyyy-MM-dd");

        // Pre-build one Faker per locale (thread-safe, reusable)
        Map<String, Faker> fakers = new LinkedHashMap<>();
        for (String loc : LOCALES) {
            fakers.put(loc, new Faker(Locale.forLanguageTag(loc), random));
        }

        // -----------------------------------------------------------------
        // Generate persons
        // -----------------------------------------------------------------
        // Each element: [firstName, lastName, email, birthDate, phone, country, iban]
        List<String[]> persons   = new ArrayList<>(numPersons);

        for (int i = 0; i < numPersons; i++) {
            String locale = LOCALES[random.nextInt(LOCALES.length)];
            Faker faker   = fakers.get(locale);

            String firstName = faker.name().firstName();
            String lastName  = faker.name().lastName();
            String someEmail = faker.internet().emailAddress();
            String email     = String.format("%s.%s@%s", firstName, lastName, someEmail.substring(someEmail.lastIndexOf('@')+1)).toLowerCase();
            String birthDate = isoDate.format(faker.date().birthday(0, 100));
            String phone     = faker.phoneNumber().phoneNumber();
            String country   = faker.address().countryCode();
            String iban      = faker.finance().iban();

            persons.add(new String[]{ firstName, lastName, email, birthDate, phone, country, iban });

            if ((i + 1) % 5000 == 0) {
                System.out.println("  Generated " + (i + 1) + " / " + numPersons + " persons...");
            }
        }

        // -----------------------------------------------------------------
        // Write CSV file (firstName,lastName,email,birthDate,phone,country,iban)
        // -----------------------------------------------------------------
        Path csvPath = Path.of(csvOut).toAbsolutePath();
        Files.createDirectories(csvPath.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(csvPath)) {
            w.write("firstName,lastName,email,birthDate,phone,country,iban");
            w.newLine();
            for (String[] p : persons) {
                w.write(escapeCsv(p[0])   // firstName
                        + "," + escapeCsv(p[1])   // lastName
                        + "," + escapeCsv(p[2])   // email
                        + "," + escapeCsv(p[3])   // birthDate (ISO)
                        + "," + escapeCsv(p[4])   // phone
                        + "," + escapeCsv(p[5])   // country
                        + "," + escapeCsv(p[6])   // iban
                );
                w.newLine();
            }
        }
        System.out.println("  CSV file written    : " + csvPath.toAbsolutePath());
        System.out.println("  Total persons       : " + persons.size());

        System.out.println("\n  Load it with LoadFakePersons, then run GenerateTrainingData --source db.");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Minimal CSV escaping: if the value contains a comma, quote, or newline, wrap in quotes. */
    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
