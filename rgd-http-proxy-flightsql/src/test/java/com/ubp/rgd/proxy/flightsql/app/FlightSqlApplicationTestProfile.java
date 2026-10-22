package com.ubp.rgd.proxy.flightsql.app;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

/**
 * The application as the rgd-http-proxy tests configure it, plus the Flight SQL server on a free port in
 * front of an in-memory database. The files come from the rgd-http-proxy module.
 */
public class FlightSqlApplicationTestProfile implements QuarkusTestProfile {

    private static final String APP = "../rgd-http-proxy/";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> props = new HashMap<>();
        props.put("quarkus.log.console.json.enabled", "false");
        props.put("quarkus.http.ssl.certificate.key-store-file", APP + "config/certs/serverkeystore.p12");
        props.put("proxy.kerberos.keytab-path", APP + "config/certs/rgd.keytab");
        props.put("proxy.prefilter.auth-enabled", "false");
        props.put("proxy.transform.config-file", APP + "src/test/resources/rps_transform_config.json");
        props.put("proxy.transform.http-secrets-manager-header", "X-Proxy-Jurisdiction");
        props.put("proxy.transform.http-secrets-manager-mapping-file", APP + "src/test/resources/secrets_manager_mapping.json");
        props.put("proxy.secrets-manager-lookup.mongodb.database", "rgd-test");
        props.put("proxy.transform.client-id", "3150a79e-3084-4946-aeb5-e7e73aea3904");
        props.put("proxy.file-transform.enabled", "false");

        props.put("proxy.flight-sql.enabled", "true");
        props.put("proxy.flight-sql.host", "localhost");
        props.put("proxy.flight-sql.port", Integer.toString(freePort()));
        props.put("proxy.flight-sql.tls.enabled", "false");
        props.put("proxy.flight-sql.jdbc-url", "jdbc:h2:mem:flightsql-app");
        props.put("proxy.flight-sql.mapping-config-file", APP + "config/flight_sql_mapping_config.json");
        return props;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
