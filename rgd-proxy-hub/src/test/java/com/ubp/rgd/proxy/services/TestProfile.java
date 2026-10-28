package com.ubp.rgd.proxy.services;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Map;

public class TestProfile implements QuarkusTestProfile {
    private static final Logger LOG = LoggerFactory.getLogger(TestProfile.class);

    public TestProfile() {
        LOG.debug("New test profile override loaded.");
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> props = new java.util.HashMap<>(Map.of(
                "proxy.target.base-url", "http://localhost:37200",
                "quarkus.log.console.json.enabled", "false",
                "proxy.prefilter.auth-enabled", "false",
                "token.utils.wdx1.authorized-spn", "HTTP/wdxe01.corp.ubp.ch",
                "proxy.transform.endpoint.authorized-spn", "HTTP/dcle01.corp.ubp.ch",
                "proxy.transform.endpoint.right-context-target", "WDX1",
                "proxy.transform.endpoint.right-context-module", "WDX1Proxy",
                "proxy.transform.endpoint.right-context-right", "Transform"
        ));

        props.put("proxy.transform.config-file", "./src/test/resources/rps_transform_config.json");
        props.put("proxy.transform.http-secrets-manager-header", "X-Proxy-Jurisdiction");
        props.put("proxy.transform.http-secrets-manager-mapping-file", "./src/test/resources/secrets_manager_mapping.json");
        // Token secrets manager resolution: no database is reachable in the tests, the lookups are mocked
        props.put("proxy.secrets-manager-lookup.mongodb.database", "rgd-test");
        props.put("proxy.transform.client-id", "3150a79e-3084-4946-aeb5-e7e73aea3904");

        props.putAll(Map.of("proxy.file-transform.enabled","true",
                            "proxy.file-transform.config-file","./src/test/resources/file_transform_config.json"));
        // Flight SQL stays disabled unless a profile enables it; a free port keeps the tests away from
        // a proxy that may be running on the machine
        props.put("proxy.flight-sql.port", Integer.toString(freePort()));

        return props;
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
