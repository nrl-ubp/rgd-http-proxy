package com.ubp.rgd.proxy.services;

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
import org.jboss.logging.Logger;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ProxyService {

    private static final Logger LOG = Logger.getLogger(ProxyService.class);

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

    @Inject
    SecurityContext securityContext;

    private final Client client;

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
            LOG.errorf(e, "Error while creating gHTTPS client: %s", e.getMessage());
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
            LOG.info("Keystore chargé: " + keystorePath);
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
            LOG.info("Truststore chargé: " + truststorePath);
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
            // Construire l'URL de destination
            String targetUrl = buildTargetUrl(path, uriInfo);
            LOG.infof("Forwarding %s request to: %s", method, targetUrl);

            WebTarget target = client.target(targetUrl);

            // Ajouter les paramètres de requête
            for (Map.Entry<String, List<String>> param : uriInfo.getQueryParameters().entrySet()) {
                for (String value : param.getValue()) {
                    target = target.queryParam(param.getKey(), value);
                }
            }

            // Préparer la requête
            var requestBuilder = target.request();

            // Copier les headers (en filtrant certains headers système)
            copyHeaders(headers, requestBuilder);

            // Implement Kerberos Delegation
            if (delegationEnabled) {
                String authHeader = headers.getHeaderString("Authorization");
                if (authHeader != null && authHeader.startsWith("Negotiate ")) {
                    String token = authHeader.substring("Negotiate ".length());
                    String delegatedTokenStr = com.ubp.rgd.proxy.security.SecurityUtils.getDelegationTicket(token, kerbServicePrincipal, kerbKeytabPath, targetServicePrincipal);

                    if (delegatedTokenStr != null) {
                        LOG.info("Kerberos delegation successful, adding delegated token to request.");
                        requestBuilder.header("Authorization", "Negotiate " + delegatedTokenStr);
                    } else {
                        LOG.warn("Kerberos delegation failed to obtain a service ticket.");
                    }
                }
            }

            // Exécuter la requête selon la méthode HTTP
            Response response = executeRequest(method, requestBuilder, body);

            // Construire la réponse proxy
            return buildProxyResponse(response);

        } catch (Exception e) {
            LOG.errorf(e, "Erreur lors du proxy de la requête %s %s", method, path);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("Erreur du proxy: " + e.getMessage())
                    .build();
        }
        }

    private String buildTargetUrl(String path, UriInfo uriInfo) {
        String format = path.startsWith("/") ? "%s%s" : "%s/%s";
        return String.format(format, targetBaseUrl, path);
    }

    private void copyHeaders(HttpHeaders headers,
                             jakarta.ws.rs.client.Invocation.Builder requestBuilder) {

        // Headers à exclure du proxy
        List<String> excludedHeaders = List.of(
                "host", "content-length", "connection", "transfer-encoding",
                "x-proxy-processed", "x-proxy-timestamp"
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
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        };
    }

    private Response buildProxyResponse(Response originalResponse) {
        try {
            // Lire le contenu de la réponse
            String responseBody = originalResponse.readEntity(String.class);

            // Construire la nouvelle réponse
            Response.ResponseBuilder responseBuilder = Response
                    .status(originalResponse.getStatus())
                    .entity(responseBody);

            // Copier les headers de réponse (en filtrant certains)
            copyResponseHeaders(originalResponse, responseBuilder);

            return responseBuilder.build();

        } catch (Exception e) {
            LOG.errorf(e, "Erreur lors de la construction de la réponse proxy");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Erreur lors du traitement de la réponse")
                    .build();
        }
    }

    private void copyResponseHeaders(Response originalResponse,
                                     Response.ResponseBuilder responseBuilder) {

        // Headers à exclure
        List<String> excludedHeaders = List.of(
                "content-length", "transfer-encoding", "connection"
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
}