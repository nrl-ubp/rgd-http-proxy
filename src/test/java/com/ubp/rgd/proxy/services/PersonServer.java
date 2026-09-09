package com.ubp.rgd.proxy.services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public abstract class PersonServer {
    private static final Logger LOG = LoggerFactory.getLogger(PersonServer.class);

    static class PersonHandler implements HttpHandler {

        /**
         * Send the contents as a HTTP response over the given HTTPExchange
         * @param exchange the HTTP Exchange being answered
         * @param contents to send in the response body with application/json content tpe header
         * @param additionalHeaders optional HTTP RESPONSE additional headers to include in the response. Can be null
         * @throws IOException in case of any problem
         */
        private void sendJSONResponse(HttpExchange exchange, byte[] contents, Map<String, String> additionalHeaders) throws IOException {
            if (additionalHeaders != null) {
                additionalHeaders.forEach((name, value) -> {
                    LOG.info("Adding header to response: {} : {}", name, value);
                    exchange.getResponseHeaders().add(name, value);
                });
            }

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, contents.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(contents);
            }
        }

        /**
         * GET returns the contents of the person.json file
         * @param exchange the HTTP exchange being answered
         * @throws IOException in case of any problem
         */
        private void handleGET(HttpExchange exchange) throws IOException {
            LOG.info("GET person handler is invoked.");
            byte[] contents = Person.getPersonsFileContents();

            LOG.info("GET Person response: {}", new String(contents));
            Map<String, String> headers = new HashMap<>();
            headers.put("X-TRANSFORM-HEADER", "Theodore ROOSEVELT");
            headers.put("X-NO-TRANSFORM-HEADER", "Winston CHURCHILL");

            sendJSONResponse(exchange, contents, headers);
        }

        /**
         * POST will return the contents of the request's body to simulate some save operation
         * @param exchange the exchange being answered
         * @throws IOException in case of any problem
         */
        private void handlePOST(HttpExchange exchange) throws IOException {
            byte[] contents = exchange.getRequestBody().readAllBytes();

            LOG.info("POST Person response: {}", new String(contents));

            Map<String, String> headers = new HashMap<>();
            headers.put("X-TRANSFORM-HEADER", "Et les chadocks pompaient...");
            headers.put("X-NO-TRANSFORM-HEADER", "Pas de transformation pour ce header");

            sendJSONResponse(exchange, contents, headers);
        }

        /**
         * Depending of the request verb (aka method) we invoke the corresponding handler.
         * GET will return the contents of the persons file and POST, PUT methods will return the request body
         * to simulate a save operation
         * @param exchange the exchange containing the request from the lient and used to send the response
         * @throws IOException in case of any problem
         */
        @Override
        public void handle(HttpExchange exchange) throws IOException {

            exchange.getRequestHeaders().forEach((name, value) -> LOG.info("Request Header: {} = {}", name, value));

            switch (exchange.getRequestMethod()) {
                case "GET":
                    handleGET(exchange);
                    break;

                case "POST":
                case "PUT":
                    handlePOST(exchange);
                    break;

                default:
                    exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                    break;
            }
        }
    }

    public static HttpServer create(String apiPathUrl) throws IOException {
        // Create an HTTP server on port 37200
        HttpServer server = HttpServer.create(new InetSocketAddress(37200), 0);

        // Define REST endpoints
        server.createContext(apiPathUrl, new PersonHandler());

        return server;
    }

    public static void main(String... args) {
        final String apiPathUrl = "/api/v1/persons/1";

        HttpServer server = null;
        try {
            server = PersonServer.create(apiPathUrl);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Start the server
        server.setExecutor(null); // Use the default executor
        server.start();
        LOG.info("TEST Mocking Server is running on http://localhost:37200/");

    }
}
