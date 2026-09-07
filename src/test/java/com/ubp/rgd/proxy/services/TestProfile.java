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
        return Map.of(
                "proxy.target.base-url", "http://localhost:37200",
                "quarkus.log.console.json.enabled", "false",
                "proxy.prefilter.auth-enabled", "false",
                "token.utils.wdx1.authorized-spn", "HTTP/wdxe01.corp.ubp.ch",
                "proxy.transform.endpoint.authorized-spn", "HTTP/dcle01.corp.ubp.ch",
                "proxy.transform.endpoint.right-context-target", "WDX1",
                "proxy.transform.endpoint.right-context-module", "WDX1Proxy",
                "proxy.transform.endpoint.right-context-right", "Transform"
        );
    }
}
