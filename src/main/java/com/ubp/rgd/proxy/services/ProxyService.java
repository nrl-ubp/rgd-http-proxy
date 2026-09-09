package com.ubp.rgd.proxy.services;

import com.ubp.rgd.proxy.filters.TransformBypass;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.Map;

@ApplicationScoped
public class ProxyService {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyService.class);

    @ConfigProperty(name = "proxy.target.base-url")
    String targetBaseUrl;

    @ConfigProperty(name = "proxy.ssl.trust-all", defaultValue = "true")
    boolean trustAllCertificates;

    @ConfigProperty(name = "proxy.ssl.keystore.path", defaultValue = "./config/certs/client-keystore.p12")
    String keystorePath;

    @ConfigProperty(name = "proxy.ssl.keystore.password", defaultValue = "changeit")
    String keystorePassword;

    @ConfigProperty(name = "proxy.ssl.keystore.type", defaultValue = "PKCS12")
    String keystoreType;

    @ConfigProperty(name = "proxy.ssl.truststore.path", defaultValue = "./config/certs/client-truststore.p12")
    String truststorePath;

    @ConfigProperty(name = "proxy.ssl.truststore.password", defaultValue = "changeit")
    String truststorePassword;

    @ConfigProperty(name = "proxy.ssl.truststore.type", defaultValue = "JKS")
    String truststoreType;

    @ConfigProperty(name = "proxy.kerberos.service-principal-name")
    String kerbServicePrincipal;

    @ConfigProperty(name = "proxy.kerberos.keytab-path")
    String kerbKeytabPath;

    @ConfigProperty(name = "proxy.kerberos.target-service-principal")
    String targetServicePrincipal;

    @ConfigProperty(name = "proxy.kerberos.delegation-enabled", defaultValue = "false")
    boolean delegationEnabled;

    /**
     * Prometheus metrics registry.
     */
    @Inject
    MeterRegistry metricsRegistry;

    /**
     * Internal security context
     */
    @Inject
    com.ubp.rgd.proxy.security.SecurityContext proxySecurityContext;

    private final Client client;

    /**
     * Per-endpoint (method + path) forward-duration aggregates, exposed through the
     * {@code ubp_proxy_forward} gauges (stat = min|max|avg). Keyed by "METHOD path".
     */
    private final ConcurrentHashMap<String, DurationStats> forwardDurationStats = new ConcurrentHashMap<>();

    public ProxyService() {
        this.client = createHttpsClient();
    }

    private Client createHttpsClient() {
        try {
            ClientBuilder clientBuilder = ClientBuilder.newBuilder();

            // Configuration SSL/TLS
            SSLContext sslContext = createSSLContext();

            // Configuration du hostname verifier
            if (trustAllCertificates) {
                LOG.warn("WARNING: SSL Certificate validation is disabled.");
                clientBuilder = clientBuilder.hostnameVerifier(createTrustAllHostnameVerifier());
            }

            return clientBuilder.build();

        } catch (Exception e) {
            LOG.error("Error while creating gHTTPS client: {}", e.getMessage(), e);
            return ClientBuilder.newClient();
        }
    }

    private SSLContext createSSLContext() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");

        // Configuration du KeyManager (certificat client)
        KeyManager[] keyManagers = null;
        if (keystorePath != null) {
            KeyStore keyStore = loadKeyStore(keystorePath, keystorePassword, keystoreType);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keystorePassword.toCharArray());
            keyManagers = kmf.getKeyManagers();
            LOG.info("Keystore chargÃ©: " + keystorePath);
        }

        // Configuration du TrustManager (certificats de confiance)
        TrustManager[] trustManagers = null;
        if (trustAllCertificates) {
            trustManagers = new TrustManager[]{createTrustAllTrustManager()};
        } else if (truststorePath != null) {
            KeyStore trustStore = loadKeyStore(truststorePath, truststorePassword, truststoreType);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            trustManagers = tmf.getTrustManagers();
            LOG.info("Truststore chargÃ©: " + truststorePath);
        }

        sslContext.init(keyManagers, trustManagers, new SecureRandom());
        return sslContext;
    }

    private KeyStore loadKeyStore(String path, String password, String type) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(type);
        try (FileInputStream fis = new FileInputStream(path)) {
            keyStore.load(fis, password.toCharArray());
        }
        return keyStore;
    }

    private TrustManager createTrustAllTrustManager() {
        return new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(X509Certificate[] certs, String authType) {
                // Accepter tous les certificats
            }

            @Override
            public void checkServerTrusted(X509Certificate[] certs, String authType) {
                // Accepter tous les certificats
            }
        };
    }

    private HostnameVerifier createTrustAllHostnameVerifier() {
        return (hostname, session) -> true;
    }

    public Response forwardRequest(String method, String path, String body,
                                   UriInfo uriInfo, HttpHeaders headers) {
        try {
            // Build target URL
            String targetUrl = buildTargetUrl(path, uriInfo);
            LOG.info("Forwarding {} request to: {}", method, targetUrl);

            WebTarget target = client.target(targetUrl);

            // Add request parameters
            for (Map.Entry<String, List<String>> param : uriInfo.getQueryParameters().entrySet()) {
                for (String value : param.getValue()) {
                    target = target.queryParam(param.getKey(), value);
                }
            }

            // Prepare the request
            var requestBuilder = target.request();

            // Copy headers (system headers filtered out)
            copyHeaders(headers, requestBuilder);

            // Resolve and set the downstream Authorization header explicitly (the inbound
            // Authorization header is excluded from copyHeaders so we fully control it here).
            String downstreamAuth = resolveDownstreamAuthorization(headers.getHeaderString("Authorization"));
            if (downstreamAuth != null) {
                requestBuilder.header("Authorization", downstreamAuth);
            }

            long startTime = System.currentTimeMillis();

            // Execute request ising indicated method and body
            Response response = executeRequest(method, requestBuilder, body);

            long duration = System.currentTimeMillis() - startTime;

            // Record per-endpoint (method + path) duration for the ubp_proxy_forward metrics.
            recordForwardDuration(method, path, duration);

            LOG.info("Forwarded request took {} ms with return code: {} - {}", duration, response.getStatusInfo().getStatusCode(), response.getStatusInfo().getReasonPhrase());

            // Now build the proxy response
            return buildProxyResponse(response);

        } catch (Exception e) {
            LOG.error("Erreur lors du proxy de la requÃªte {} {}", method, path, e);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("Erreur du proxy: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Determine the {@code Authorization} header to send to the proxified (downstream) API.
     * <ol>
     *     <li>If a basic-auth user identity (Subject) was retained for this request, obtain a
     *         client-to-service ticket <em>as that user</em> for the configured target SPN and send
     *         it as {@code Negotiate}.</li>
     *     <li>Otherwise, if Kerberos delegation is enabled and the inbound request carried a
     *         {@code Negotiate} token, perform constrained delegation to the target SPN.</li>
     *     <li>Otherwise, forward the inbound {@code Authorization} header unchanged.</li>
     * </ol>
     * @param inboundAuth the inbound Authorization header value (may be {@code null})
     * @return the Authorization header value to send downstream, or {@code null} if none
     */
    private String resolveDownstreamAuthorization(String inboundAuth) {
        // 1) Basic-auth identity available -> client-to-service ticket as this user.
        javax.security.auth.Subject userSubject =
                proxySecurityContext != null ? proxySecurityContext.getUserSubject() : null;
        if (userSubject != null) {
            String token = com.ubp.rgd.proxy.security.SecurityUtils
                    .getClientToServiceToken(userSubject, targetServicePrincipal);
            if (token != null) {
                LOG.debug("Obtained client-to-service ticket for downstream call to SPN: {}", targetServicePrincipal);
                return "Negotiate " + token;
            }
            LOG.warn("Could not obtain a client-to-service ticket for the basic-auth user; falling back.");
        }

        // 2) Incoming Kerberos + delegation enabled -> constrained delegation (existing behavior).
        if (delegationEnabled && inboundAuth != null && inboundAuth.startsWith("Negotiate ")) {
            String token = inboundAuth.substring("Negotiate ".length());
            String delegatedTokenStr = com.ubp.rgd.proxy.security.SecurityUtils.getDelegationTicket(
                    token, kerbServicePrincipal, kerbKeytabPath, targetServicePrincipal);
            if (delegatedTokenStr != null) {
                LOG.info("Kerberos delegation successful, adding delegated token to request.");
                return "Negotiate " + delegatedTokenStr;
            }
            LOG.warn("Kerberos delegation failed to obtain a service ticket.");
        }

        // 3) Default: forward the inbound Authorization header unchanged (may be null).
        LOG.warn("No user subject nor delegation has been found in original request. Keeping Authorization header unchanged.");
        return inboundAuth;
    }

    private String buildTargetUrl(String path, UriInfo uriInfo) {
        String format = path.startsWith("/") ? "%s%s" : "%s/%s";
        return String.format(format, targetBaseUrl, path);
    }

    private void copyHeaders(HttpHeaders headers,
                             jakarta.ws.rs.client.Invocation.Builder requestBuilder) {

        // Headers Ã  exclure du proxy
        // "authorization" is excluded here because the downstream Authorization header is set
        // explicitly by resolveDownstreamAuthorization (basic-auth client-to-service ticket,
        // Kerberos delegation, or pass-through).
        List<String> excludedHeaders = List.of(
                "host", "content-length", "connection", "transfer-encoding",
                "authorization", "x-proxy-processed", "x-proxy-timestamp",
                TransformBypass.HEADER_NAME.toLowerCase()
        );

        for (Map.Entry<String, List<String>> header : headers.getRequestHeaders().entrySet()) {
            String headerName = header.getKey().toLowerCase();

            if (!excludedHeaders.contains(headerName)) {
                for (String value : header.getValue()) {
                    requestBuilder.header(header.getKey(), value);
                }
            }
        }
    }

    protected Response executeRequest(String method,
                                    jakarta.ws.rs.client.Invocation.Builder requestBuilder,
                                    String body) {
        return switch (method.toUpperCase()) {
            case "GET" -> requestBuilder.get();
            case "POST" -> requestBuilder.post(body != null ? Entity.json(body) : Entity.json(""));
            case "PUT" -> requestBuilder.put(body != null ? Entity.json(body) : Entity.json(""));
            case "DELETE" -> requestBuilder.delete();
            case "HEAD" -> requestBuilder.head();
            case "OPTIONS" -> requestBuilder.options();
            case "PATCH" -> requestBuilder.method("PATCH", body != null ? Entity.json(body) : Entity.json(""));
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        };
    }

    private Response buildProxyResponse(Response originalResponse) {
        try {
            // Read the downstream body as raw bytes and decompress it if it is gzip-encoded.
            // The proxy always emits an uncompressed body to the client (see copyResponseHeaders,
            // which drops the stale Content-Encoding header), which also lets the PostFilter
            // AFTER-tokenization operate on readable text.
            byte[] rawBytes = originalResponse.readEntity(byte[].class);
            byte[] bodyBytes = gunzipIfNeeded(rawBytes);
            String responseBody = bodyBytes == null ? "" : new String(bodyBytes, StandardCharsets.UTF_8);

            // log response body for debug if not SUCCESS family
            if (originalResponse.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                LOG.debug("Forwarded request failed: {} - {}.\n{}",
                        originalResponse.getStatus(),
                        originalResponse.getStatusInfo().getReasonPhrase(),
                        responseBody);
            }

            // Build the new response
            Response.ResponseBuilder responseBuilder = Response
                    .status(originalResponse.getStatus())
                    .entity(responseBody);

            // Copy response headers while filtering some to align with eg content encoding
            copyResponseHeaders(originalResponse, responseBuilder);

            return responseBuilder.build();

        } catch (Exception e) {
            LOG.error("Error while building proxy response: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(String.format("Error while building proxy response: %s", e.getMessage()))
                    .build();
        }
    }

    /**
     * Decompress the given bytes when they are GZIP-encoded, otherwise return them unchanged.
     * <p>
     * Detection relies on the GZIP magic number ({@code 0x1f 0x8b}) rather than the
     * {@code Content-Encoding} header, so it works whether or not the underlying HTTP client already
     * decoded the stream (avoiding a double-decompression).
     *
     * @param data the raw response body bytes (may be null)
     * @return the decompressed bytes if gzip-encoded, otherwise the original bytes
     */
    static byte[] gunzipIfNeeded(byte[] data) {
        if (data == null || data.length < 2) {
            return data;
        }

        boolean isGzip = (data[0] == (byte) 0x1f) && (data[1] == (byte) 0x8b);
        if (!isGzip) {
            return data;
        }

        try (GZIPInputStream gzipStream = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = gzipStream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Exception e) {
            LOG.warn("Failed to gunzip response body; returning raw bytes.", e);
            return data;
        }
    }

    private void copyResponseHeaders(Response originalResponse,
                                     Response.ResponseBuilder responseBuilder) {

        // Headers Ã  exclure.
        // "content-encoding" is dropped because the proxy always emits an uncompressed body
        // (see buildProxyResponse / gunzipIfNeeded); advertising gzip would break the client.
        List<String> excludedHeaders = List.of(
                "content-length", "transfer-encoding", "connection", "content-encoding"
        );

        for (Map.Entry<String, List<Object>> header : originalResponse.getHeaders().entrySet()) {
            String headerName = header.getKey().toLowerCase();

            if (!excludedHeaders.contains(headerName)) {
                for (Object value : header.getValue()) {
                    responseBuilder.header(header.getKey(), value);
                }
            }
        }
    }

    /**
     * Record a forward-request duration for the given HTTP method and (query-string-free) path, and
     * expose it through the {@code ubp_proxy_forward} gauges.
     * <p>
     * On the first call for a (method, path) key, three gauges are registered under the same name
     * {@code ubp_proxy_forward}, differentiated by the {@code stat} tag ({@code min}, {@code max},
     * {@code avg}) and both bound to the same {@link DurationStats}; subsequent calls only update
     * the aggregate, which the gauges reflect automatically.
     *
     * @param method the HTTP method (GET, POST, ...)
     * @param path the proxified path without the query string
     * @param durationMs the forward duration in milliseconds
     */
    void recordForwardDuration(String method, String path, long durationMs) {
        String key = method + " " + path;
        DurationStats stats = forwardDurationStats.computeIfAbsent(key, k -> {
            DurationStats created = new DurationStats();
            Tags baseTags = Tags.of("method", method, "uri", path);
            metricsRegistry.gauge("ubp_proxy_forward", baseTags.and("stat", "min"), created, DurationStats::getMin);
            metricsRegistry.gauge("ubp_proxy_forward", baseTags.and("stat", "max"), created, DurationStats::getMax);
            metricsRegistry.gauge("ubp_proxy_forward", baseTags.and("stat", "avg"), created, DurationStats::getAvg);
            return created;
        });
        stats.record(durationMs);
    }

    /**
     * Thread-safe accumulator of forward-request durations (milliseconds) tracking the minimum,
     * maximum, count and running sum, from which the average is derived. All values are 0 until the
     * first duration is recorded.
     */
    static final class DurationStats {
        private long min = 0;
        private long max = 0;
        private long count = 0;
        private long sum = 0;

        synchronized void record(long durationMs) {
            if (count == 0) {
                min = durationMs;
                max = durationMs;
            } else {
                if (durationMs < min) {
                    min = durationMs;
                }
                if (durationMs > max) {
                    max = durationMs;
                }
            }
            count++;
            sum += durationMs;
        }

        synchronized double getMin() {
            return min;
        }

        synchronized double getMax() {
            return max;
        }

        synchronized double getAvg() {
            return count == 0 ? 0.0 : (double) sum / count;
        }
    }
}
