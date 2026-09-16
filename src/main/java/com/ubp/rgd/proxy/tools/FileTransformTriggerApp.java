package com.ubp.rgd.proxy.tools;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Command-line application to trigger a file transformation synchronously on a <b>running</b>
 * proxy server.
 * <p>
 * The transformation itself needs the application context (the transformer, the transformation
 * configuration, the engine), so this app does not transform anything by itself: it calls the
 * {@code POST /api/file-transform/trigger/{configName}} endpoint and reports what the server did.
 * The server owns the configurations, so the configuration name given here only has to match one it
 * has loaded from its own {@code proxy.file-transform.config-file}.
 * <p>
 * Usage:
 * <pre>
 *   java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp &lt;config-name&gt; [host:port]
 * </pre>
 * <p>
 * Exit codes:
 * <ul>
 *   <li>0 - success, the files have been processed</li>
 *   <li>1 - configuration not found on the server</li>
 *   <li>2 - invalid arguments</li>
 *   <li>3 - server unreachable</li>
 *   <li>4 - processing error reported by the server</li>
 * </ul>
 */
public class FileTransformTriggerApp {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_CONFIG_NOT_FOUND = 1;
    static final int EXIT_INVALID_ARGS = 2;
    static final int EXIT_SERVER_UNREACHABLE = 3;
    static final int EXIT_PROCESSING_ERROR = 4;

    private static final String DEFAULT_HOST_PORT = "localhost:8080";
    private static final String TRIGGER_PATH = "/api/file-transform/trigger/";
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

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

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return report(response);
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

    private static int report(HttpResponse<String> response) {
        String body = response.body();

        switch (response.statusCode()) {
            case 200 -> {
                System.out.println();
                System.out.println("Processing complete!");
                System.out.println(body);
                return EXIT_SUCCESS;
            }
            case 404 -> {
                System.err.println("ERROR: the server does not know this configuration.");
                System.err.println(body);
                return EXIT_CONFIG_NOT_FOUND;
            }
            default -> {
                System.err.println("ERROR: the server failed to process the configuration (HTTP "
                        + response.statusCode() + ").");
                System.err.println(body);
                return EXIT_PROCESSING_ERROR;
            }
        }
    }

    private static void printUsage() {
        System.out.println("File Transform Trigger App");
        System.out.println("=========================");
        System.out.println();
        System.out.println("Triggers a file transformation on a RUNNING proxy server, through");
        System.out.println("POST /api/file-transform/trigger/<config-name>.");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp <config-name> [host:port]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  config-name  Name of the file transform configuration, as loaded by the server");
        System.out.println("  host:port    Server to call (default: " + DEFAULT_HOST_PORT + ")");
        System.out.println();
        System.out.println("Exit codes:");
        System.out.println("  0 - Success");
        System.out.println("  1 - Configuration not found on the server");
        System.out.println("  2 - Invalid arguments");
        System.out.println("  3 - Server unreachable");
        System.out.println("  4 - Processing error");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp \"Person Data Protection\"");
        System.out.println("  java -jar rgd-http-proxy.jar com.ubp.rgd.proxy.tools.FileTransformTriggerApp \"Person Data Protection\" proxy-host:8443");
    }
}
