package com.ubp.rgd.proxy.services;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
                "proxy.transform.endpoint.right-context-right", "Transform",
                "proxy.flight-sql.jdbc-url", "jdbc:jtds:sqlserver://msvgvzwdxev004s/CDM_Datamart;encrypt=true;trustServerCertificate=true",
                "proxy.flight-sql.enabled", "true"
        ));

        props.put("proxy.transform.config-file", "./src/test/resources/rps_transform_config.json");
        props.put("proxy.transform.http-secrets-manager-header", "X-Proxy-Jurisdiction");
        props.put("proxy.transform.http-secrets-manager-mapping-file", "./src/test/resources/secrets_manager_mapping.json");

        // Token secrets manager resolution: no database is reachable in the tests, the lookups are mocked
        props.put("proxy.secrets-manager-lookup.mongodb.database", "rgd-test");

        // can have the connection to RPS engine set by env variables, this defaults to Shenzo dev anonymous environment
        String clientId = System.getProperty("proxy.transform.client-id", "3689c3d7-5f1e-4cda-967b-8b5b979c40e8");
        String clientSecret = System.getProperty("proxy.transform.client-secret", "46094e476a0840d3abdfaf583c633dec0a49323c256247f98ebc0f06a966e060");
        String engineUrl = System.getProperty("proxy.transform.engine-url", "https://engine-rgd-poc.corp.ubp.ch");
        String identityUrl = System.getProperty("proxy.transform.identity-url", "https://identity-rgd-poc.corp.ubp.ch");

        props.put("proxy.transform.client-id", clientId);
        props.put("proxy.transform.client-secret", clientSecret);
        props.put("proxy.transform.engine-url", engineUrl);
        props.put("proxy.transform.identity-url", identityUrl);

        props.putAll(Map.of("proxy.file-transform.enabled","true",
                            "proxy.file-transform.config-file","./src/test/resources/file_transform_config.json"));

        return props;
    }
}
