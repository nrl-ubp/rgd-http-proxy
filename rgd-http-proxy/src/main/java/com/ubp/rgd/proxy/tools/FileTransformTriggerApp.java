package com.ubp.rgd.proxy.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Command-line application to trigger a file transformation synchronously on a <b>running</b>
 * proxy server, suitable for chaining as a step of a batch scheduler.
 * <p>
 * The transformation itself needs the application context (the transformer, the transformation
 * configuration, the engine), so this app does not transform anything by itself: it calls the
 * {@code POST /api/file-transform/trigger/{configName}} endpoint and reports what the server did.
 * The server owns the configurations, so the configuration name given here only has to match one it
 * has loaded from its own {@code proxy.file-transform.config-file}.
 * <p>
 * The endpoint is synchronous, so the call returns only once every file has been transformed or
 * moved to the error directory. The exit code then tells what happened, 0 meaning that everything
 * was transformed and anything else that the batch must not carry on blindly.
 * <p>
 * A configuration whose {@code scan-interval-seconds} is greater than zero is driven by the server's
 * own scheduler and is refused here, so that a triggered run can never overlap with a scheduled one.
 * A configuration meant to be driven by a batch must declare an interval of 0.
 * <p>
 * Usage:
 * <pre>
 *   java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp &lt;config-name&gt; [host:port]
 * </pre>
 * <p>
 * Exit codes:
 * <ul>
 *   <li>0 - success, every file was transformed</li>
 *   <li>1 - configuration not found on the server</li>
 *   <li>2 - invalid arguments</li>
 *   <li>3 - server unreachable, or the call timed out</li>
 *   <li>4 - every file failed</li>
 *   <li>5 - some files were transformed and others failed</li>
 *   <li>6 - the configuration matched no file</li>
 *   <li>7 - the configuration is driven by the scheduler and cannot be triggered</li>
 * </ul>
 */
public class FileTransformTriggerApp {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_CONFIG_NOT_FOUND = 1;
    static final int EXIT_INVALID_ARGS = 2;
    static final int EXIT_SERVER_UNREACHABLE = 3;
    static final int EXIT_PROCESSING_ERROR = 4;
    static final int EXIT_PARTIAL_FAILURE = 5;
    static final int EXIT_NO_FILE = 6;
    static final int EXIT_SCHEDULED_CONFIG = 7;

    private static final String DEFAULT_HOST_PORT = "localhost:8080";
    private static final String TRIGGER_PATH = "/api/file-transform/trigger/";
    private static final String TIMEOUT_PROPERTY = "proxy.trigger.timeout-minutes";
    private static final int DEFAULT_TIMEOUT_MINUTES = 10;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Run the trigger and return the exit code instead of terminating the JVM, so that the app can
     * also be driven from a test.
     *
     * @param args the configuration name, then optionally {@code host:port}
     * @return the exit code, see the class documentation
     */
    public static int run(String[] args) {
        if (args.length == 0 || args[0] == null || args[0].isBlank()) {
            printUsage();
            return EXIT_INVALID_ARGS;
        }

        if (args.length > 2) {
            System.err.println("ERROR: too many arguments.");
            printUsage();
            return EXIT_INVALID_ARGS;
        }

        String configName = args[0];
        String hostPort = args.length > 1 ? args[1] : DEFAULT_HOST_PORT;

        URI uri;
        try {
            uri = triggerUri(hostPort, configName);
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            printUsage();
            return EXIT_INVALID_ARGS;
        }

        System.out.println("File Transform Trigger App");
        System.out.println("=========================");
        System.out.println("Server: " + hostPort);
        System.out.println("Configuration name: " + configName);
        System.out.println();

        return trigger(uri);
    }

    /**
     * Build the endpoint URI. The configuration name is URL encoded because the configurations are
     * named in plain language and usually contain spaces.
     *
     * @param hostPort the {@code host:port} of the server
     * @param configName the name of the configuration to trigger
     * @return the URI of the trigger endpoint for that configuration
     * @throws IllegalArgumentException if the {@code host:port} is malformed
     */
    static URI triggerUri(String hostPort, String configName) {
        int separator = hostPort.lastIndexOf(':');
        if (separator < 1 || separator == hostPort.length() - 1) {
            throw new IllegalArgumentException("Expected a server as 'host:port' but got: " + hostPort);
        }

        String host = hostPort.substring(0, separator);
        String port = hostPort.substring(separator + 1);

        try {
            int portNumber = Integer.parseInt(port);
            if (portNumber < 1 || portNumber > 65535) {
                throw new IllegalArgumentException("Port out of range in: " + hostPort);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port is not a number in: " + hostPort);
        }

        return URI.create(String.format("http://%s:%s%s%s", host, port, TRIGGER_PATH,
                URLEncoder.encode(configName, StandardCharsets.UTF_8).replace("+", "%20")));
    }

    private static int trigger(URI uri) {
        System.out.println("Triggering: POST " + uri);

        Duration timeout = timeout();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return report(response);
        } catch (HttpTimeoutException e) {
            // The run may well still be going on server side, so nothing can be concluded about it.
            System.err.println("ERROR: no answer within " + timeout.toMinutes() + " minutes."
                    + " The server may still be processing the files.");
            System.err.println("       Raise -D" + TIMEOUT_PROPERTY + " if the batch is a long one.");
            return EXIT_SERVER_UNREACHABLE;
        } catch (IOException e) {
            System.err.println("ERROR: cannot reach the server at " + uri.getAuthority()
                    + " - " + e.getMessage());
            System.err.println("       Is the proxy running, and is its HTTP port the one given?");
            return EXIT_SERVER_UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("ERROR: interrupted while waiting for the server.");
            return EXIT_PROCESSING_ERROR;
        }
    }

    /**
     * How long to wait for the run to finish. A batch can legitimately take a long time, so the
     * limit is configurable; an unusable value falls back to the default rather than failing the run.
     *
     * @return the read timeout
     */
    private static Duration timeout() {
        String configured = System.getProperty(TIMEOUT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                int minutes = Integer.parseInt(configured.trim());
                if (minutes > 0) {
                    return Duration.ofMinutes(minutes);
                }
                System.err.println("WARNING: " + TIMEOUT_PROPERTY + " must be positive, using "
                        + DEFAULT_TIMEOUT_MINUTES + " minutes.");
            } catch (NumberFormatException e) {
                System.err.println("WARNING: " + TIMEOUT_PROPERTY + " is not a number ('" + configured
                        + "'), using " + DEFAULT_TIMEOUT_MINUTES + " minutes.");
            }
        }
        return Duration.ofMinutes(DEFAULT_TIMEOUT_MINUTES);
    }

    /**
     * Turn the answer of the server into an exit code.
     * <p>
     * The counts are read from the body in both the 200 and the 500 case, because they are what
     * distinguishes a batch where everything failed from one where a few files did. A body that
     * cannot be understood is reported as a processing error: the one thing this must never do is
     * answer 0 when the outcome is unknown.
     *
     * @param response the answer of the trigger endpoint
     * @return the exit code, see the class documentation
     */
    private static int report(HttpResponse<String> response) {
        String body = response.body();

        if (response.statusCode() == 404) {
            System.err.println("ERROR: the server does not know this configuration.");
            System.err.println(body);
            return EXIT_CONFIG_NOT_FOUND;
        }

        if (response.statusCode() == 409) {
            System.err.println("ERROR: this configuration cannot be triggered.");
            System.err.println(body);
            return EXIT_SCHEDULED_CONFIG;
        }

        if (response.statusCode() != 200 && response.statusCode() != 500) {
            System.err.println("ERROR: unexpected answer from the server (HTTP "
                    + response.statusCode() + ").");
            System.err.println(body);
            return EXIT_PROCESSING_ERROR;
        }

        JsonNode outcome;
        try {
            outcome = new ObjectMapper().readTree(body);
        } catch (JsonProcessingException e) {
            System.err.println("ERROR: cannot read the answer of the server - " + e.getMessage());
            System.err.println(body);
            return EXIT_PROCESSING_ERROR;
        }

        if (!outcome.hasNonNull("filesSucceeded") || !outcome.hasNonNull("filesFailed")) {
            System.err.println("ERROR: the server failed to process the configuration (HTTP "
                    + response.statusCode() + ").");
            System.err.println(body);
            return EXIT_PROCESSING_ERROR;
        }

        int succeeded = outcome.get("filesSucceeded").asInt();
        int failed = outcome.get("filesFailed").asInt();

        System.out.println();
        System.out.println("Files transformed: " + succeeded);
        System.out.println("Files failed     : " + failed);

        printErrors(outcome);

        if (failed == 0 && succeeded == 0) {
            // Nothing matched the file pattern. Not an error, but not a transformation either.
            if (hasErrors(outcome)) {
                System.err.println("The run could not be completed.");
                return EXIT_PROCESSING_ERROR;
            }
            System.out.println("Nothing to do: no file matched the configuration.");
            return EXIT_NO_FILE;
        }

        if (failed == 0 && !hasErrors(outcome)) {
            System.out.println("Processing complete!");
            return EXIT_SUCCESS;
        }

        if (succeeded > 0) {
            System.err.println("Some files could not be transformed, see the error directory.");
            return EXIT_PARTIAL_FAILURE;
        }

        System.err.println("No file could be transformed, see the error directory.");
        return EXIT_PROCESSING_ERROR;
    }

    private static boolean hasErrors(JsonNode outcome) {
        JsonNode errors = outcome.get("errors");
        return errors != null && errors.isArray() && !errors.isEmpty();
    }

    private static void printErrors(JsonNode outcome) {
        if (!hasErrors(outcome)) {
            return;
        }

        System.err.println("Failures:");
        outcome.get("errors").forEach(error -> System.err.println("  - " + error.asText()));
    }

    private static void printUsage() {
        System.out.println("File Transform Trigger App");
        System.out.println("=========================");
        System.out.println();
        System.out.println("Triggers a file transformation on a RUNNING proxy server, through");
        System.out.println("POST /api/file-transform/trigger/<config-name>, and waits for it to finish.");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp <config-name> [host:port]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  config-name  Name of the file transform configuration, as loaded by the server");
        System.out.println("  host:port    Server to call (default: " + DEFAULT_HOST_PORT + ")");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -D" + TIMEOUT_PROPERTY + "=<n>  How long to wait for the run (default: "
                + DEFAULT_TIMEOUT_MINUTES + ")");
        System.out.println();
        System.out.println("Note:");
        System.out.println("  Only configurations with scan-interval-seconds = 0 can be triggered. Any other");
        System.out.println("  value means the server polls the directory itself, and driving it from a batch");
        System.out.println("  would run the same configuration twice at the same time.");
        System.out.println();
        System.out.println("Exit codes:");
        System.out.println("  0 - Success, every file was transformed");
        System.out.println("  1 - Configuration not found on the server");
        System.out.println("  2 - Invalid arguments");
        System.out.println("  3 - Server unreachable or call timed out");
        System.out.println("  4 - Every file failed");
        System.out.println("  5 - Some files were transformed, others failed");
        System.out.println("  6 - No file matched the configuration");
        System.out.println("  7 - Configuration is driven by the scheduler and cannot be triggered");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp \"Person Data Protection\"");
        System.out.println("  java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp \"Person Data Protection\" proxy-host:8443");
    }
}
