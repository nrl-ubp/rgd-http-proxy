package com.ubp.rgd.tools;

import com.ubp.rgd.proxy.cli.CliArgs;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone batch program that reads {@code fake_persons.csv} and POSTs
 * each row to the {@code POST /api/fuzzy-search/persons} endpoint of the proxy.
 *
 * <h2>Usage</h2>
 * <pre>
 *   mvn -Pfuzzy-search-tools -pl rgd-proxy-fuzzysearch test-compile exec:exec -Dtool=LoadFakePersons \
 *       -Dtool.args="--csv models/fake_persons.csv --url http://localhost:8080 --user u --password p"
 * </pre>
 *
 * <h2>Switches (all optional)</h2>
 * <ul>
 *   <li>{@code --csv}      – path to the CSV file (default: {@code models/fake_persons.csv})</li>
 *   <li>{@code --url}      – base URL of the proxy (default: {@code http://localhost:8080})</li>
 *   <li>{@code --user}, {@code --password} – Basic authentication of the proxy, for a local call</li>
 *   <li>{@code --threads}  – number of parallel HTTP threads (default: {@code 4})</li>
 * </ul>
 * Every person is scoped to the {@code fuzzy-search.tenant-id} of the proxy.
 *
 * <h2>CSV format</h2>
 * The first line must be the header:
 * {@code firstName,lastName,email,birthDate,phone,country,iban}
 * Values may be RFC 4180-quoted (double-quoted, embedded quotes escaped as {@code ""}).
 * The {@code birthDate} column must be in ISO 8601 format ({@code YYYY-MM-DD}).
 *
 * <h2>HTTP request</h2>
 * The columns are mapped onto the {@code PERSON} table of the rgd-http-proxy:
 * the country becomes the city, the phone and the IBAN go into the notes.
 * <pre>
 *   POST /api/fuzzy-search/persons
 *   Content-Type: application/json
 *   {
 *     "firstName":  "…",
 *     "lastName":   "…",
 *     "birthDate":  "YYYY-MM-DD",
 *     "email":      "…",
 *     "city":       "&lt;country&gt;",
 *     "notes":      "Phone &lt;phone&gt;, IBAN &lt;iban&gt;"
 *   }
 * </pre>
 * HTTP 2xx responses are counted as success; anything else is counted as an
 * error and printed to stderr.  A single row failure never aborts the run.
 */
public class LoadFakePersons {

    // -----------------------------------------------------------------------
    // Column indices in the CSV (after header skip)
    // -----------------------------------------------------------------------
    private static final int COL_FIRST_NAME = 0;
    private static final int COL_LAST_NAME  = 1;
    private static final int COL_EMAIL      = 2;
    private static final int COL_BIRTH_DATE = 3;
    private static final int COL_PHONE      = 4;
    private static final int COL_COUNTRY    = 5;
    private static final int COL_IBAN       = 6;
    private static final int MIN_COLUMNS    = 7;

    // -----------------------------------------------------------------------
    // Defaults
    // -----------------------------------------------------------------------

    private static final String DEFAULT_CSV_PATH  = "models/fake_persons.csv";
    private static final int    DEFAULT_THREADS   = 4;

    /** Log a progress line every this many successful requests. */
    private static final int PROGRESS_STEP = 500;

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {

        // ── Parse CLI arguments ──────────────────────────────────────────────
        CliArgs cli = new CliArgs(args);
        String csvPath  = cli.switchValue("--csv", DEFAULT_CSV_PATH);
        String baseUrl  = cli.switchValue("--url", FuzzySearchClient.DEFAULT_URL);
        String user     = cli.switchValue("--user", null);
        String password = cli.switchValue("--password", null);
        int    threads  = Integer.parseInt(cli.switchValue("--threads", String.valueOf(DEFAULT_THREADS)));
        String endpoint = FuzzySearchClient.endpoint(baseUrl, "/persons").toString();

        System.out.println("=== LoadFakePersons ===");
        System.out.println("  CSV file  : " + Path.of(csvPath).toAbsolutePath());
        System.out.println("  Endpoint  : " + endpoint);
        System.out.println("  Threads   : " + threads);
        System.out.println();

        // ── Load CSV ────────────────────────────────────────────────────────
        List<String[]> rows = readCsv(Path.of(csvPath));
        System.out.println("  Loaded " + rows.size() + " rows from CSV.");
        System.out.println();

        if (rows.isEmpty()) {
            System.out.println("Nothing to do – CSV is empty.");
            System.exit(-1);
        }

        // ── Send requests ───────────────────────────────────────────────────
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newFixedThreadPool(threads))
                .build();

        AtomicInteger ok     = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        long startMs = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>(rows.size());

        for (String[] row : rows) {
            final String firstName = row[COL_FIRST_NAME];
            final String lastName  = row[COL_LAST_NAME];
            final String email     = row.length > COL_EMAIL      ? row[COL_EMAIL]      : "";
            final String birthDate = row.length > COL_BIRTH_DATE ? row[COL_BIRTH_DATE] : null;
            final String phone     = row.length > COL_PHONE      ? row[COL_PHONE]      : "";
            final String country   = row.length > COL_COUNTRY    ? row[COL_COUNTRY]    : "";
            final String iban      = row.length > COL_IBAN       ? row[COL_IBAN]       : "";

            futures.add(pool.submit(() -> {
                // Build JSON body manually (no external library needed)
                StringBuilder body = new StringBuilder();
                body.append("{");
                body.append("\"firstName\":").append(jsonString(firstName));
                body.append(",\"lastName\":").append(jsonString(lastName));
                // birthDate is ISO 8601 "YYYY-MM-DD", protected as a date by FPE.
                if (birthDate != null && !birthDate.isBlank()) {
                    body.append(",\"birthDate\":").append(jsonString(birthDate));
                } else {
                    body.append(",\"birthDate\":null");
                }
                body.append(",\"email\":").append(jsonString(email));
                body.append(",\"city\":").append(jsonString(country));
                body.append(",\"notes\":").append(jsonString("Phone " + phone + ", IBAN " + iban));
                body.append("}");

                try {
                    HttpRequest req = FuzzySearchClient.authenticate(HttpRequest.newBuilder(), user, password)
                            .uri(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .header("Accept",       "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            .build();

                    HttpResponse<String> resp =
                            http.send(req, HttpResponse.BodyHandlers.ofString());

                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        int n = ok.incrementAndGet();
                        if (n % PROGRESS_STEP == 0) {
                            System.out.printf("  Progress: %d / %d sent (%.1f%%)%n",
                                    n, rows.size(), 100.0 * n / rows.size());
                        }
                    } else {
                        int n = errors.incrementAndGet();
                        System.err.printf("  [ERROR #%d] HTTP %d for (%s, %s) – %s%n",
                                n, resp.statusCode(), firstName, lastName,
                                resp.body().lines().findFirst().orElse(""));
                    }

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    errors.incrementAndGet();
                } catch (Exception ex) {
                    int n = errors.incrementAndGet();
                    System.err.printf("  [ERROR #%d] %s (%s, %s)%n",
                            n, ex.getMessage(), firstName, lastName);
                }
            }));
        }

        // Wait for all submitted tasks to complete
        int submitErrors = 0;
        for (Future<?> f : futures) {
            try { f.get(); }
            catch (Exception e) { submitErrors++; }
        }

        pool.shutdown();
        long durationMs = System.currentTimeMillis() - startMs;

        // ── Final report ────────────────────────────────────────────────────
        System.out.println();
        System.out.println("=== Done ===");
        System.out.printf("  Total    : %d%n",  rows.size());
        System.out.printf("  OK       : %d%n",  ok.get());
        System.out.printf("  Errors   : %d%n",  errors.get() + submitErrors);
        System.out.printf("  Duration : %.1f s  (%.0f req/s)%n",
                durationMs / 1000.0,
                durationMs > 0 ? ok.get() * 1000.0 / durationMs : 0.0);

        System.exit(submitErrors);
    }

    // -----------------------------------------------------------------------
    // CSV parsing – RFC 4180-compliant
    // -----------------------------------------------------------------------

    /**
     * Reads all data rows from a CSV file, skipping the header line.
     * Handles RFC 4180 quoting: fields may be double-quoted, and an embedded
     * double-quote is escaped as {@code ""}.
     *
     * <p>Expected header: {@code firstName,lastName,email,birthDate,phone,country,iban}
     *
     * @param path path to the CSV file
     * @return list of {@code String[]} rows (at least {@value #MIN_COLUMNS} elements each)
     * @throws IOException on file read failure
     */
    private static List<String[]> readCsv(Path path) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String header = br.readLine(); // skip header line
            if (header == null) return rows;

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = parseCsvLine(line);
                if (fields.length >= MIN_COLUMNS) {
                    rows.add(fields);
                } else if (fields.length >= 2) {
                    // Legacy CSV without the extra columns: pad with empty strings
                    String[] padded = new String[MIN_COLUMNS];
                    System.arraycopy(fields, 0, padded, 0, fields.length);
                    for (int i = fields.length; i < MIN_COLUMNS; i++) padded[i] = "";
                    rows.add(padded);
                }
            }
        }
        return rows;
    }

    /**
     * Parses one RFC 4180 CSV line into an array of field strings.
     *
     * <ul>
     *   <li>Unquoted fields: everything up to the next comma.</li>
     *   <li>Quoted fields: content between double-quotes; {@code ""} → {@code "}.</li>
     * </ul>
     */
    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        int len = line.length();

        while (i <= len) {
            if (i == len) {
                fields.add("");
                break;
            }

            if (line.charAt(i) == '"') {
                // Quoted field
                StringBuilder sb = new StringBuilder();
                i++; // skip opening quote
                while (i < len) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < len && line.charAt(i + 1) == '"') {
                            sb.append('"'); // escaped quote ""
                            i += 2;
                        } else {
                            i++; // closing quote
                            break;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                fields.add(sb.toString());
                if (i < len && line.charAt(i) == ',') i++;

            } else {
                // Unquoted field
                int end = line.indexOf(',', i);
                if (end < 0) end = len;
                fields.add(line.substring(i, end).trim());
                i = end + 1;
            }
        }

        return fields.toArray(new String[0]);
    }

    // -----------------------------------------------------------------------
    // JSON helpers (no external library required)
    // -----------------------------------------------------------------------

    /**
     * Returns a properly JSON-escaped string literal with surrounding quotes.
     * Handles backslash, double-quote, newline, carriage-return, tab, and
     * control characters below U+0020 (emitted as unicode codepoint escapes).
     *
     * @param value plain-text value (may be {@code null})
     * @return JSON string literal, e.g. {@code "Mc\"Henry"}
     */
    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
