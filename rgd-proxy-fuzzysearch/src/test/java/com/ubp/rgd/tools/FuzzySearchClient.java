package com.ubp.rgd.tools;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * What the fuzzy search tools share to call the proxy: the endpoints under {@code /api/fuzzy-search} and
 * the Basic authentication the proxy's pre-filter expects from a local caller.
 */
final class FuzzySearchClient {

    static final String DEFAULT_URL = "http://localhost:8080";
    static final String BASE_PATH = "/api/fuzzy-search";

    private FuzzySearchClient() {
    }

    /**
     * @param baseUrl the base URL of the proxy, with or without a trailing slash
     * @param path    the path below {@code /api/fuzzy-search}, starting with a slash
     * @return the endpoint
     */
    static URI endpoint(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(base + BASE_PATH + path);
    }

    /**
     * Adds the Basic {@code Authorization} header when a user is given.
     *
     * @param builder  the request to authenticate
     * @param user     the user, {@code null} to send no credentials (proxy authentication disabled)
     * @param password the password, may be {@code null}
     * @return the builder
     */
    static HttpRequest.Builder authenticate(HttpRequest.Builder builder, String user, String password) {
        if (user == null || user.isBlank()) {
            return builder;
        }
        String credentials = user + ":" + (password == null ? "" : password);
        return builder.header("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }
}
