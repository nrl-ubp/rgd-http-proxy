package com.ubp.rgd.proxy.services;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.jboss.logging.Logger;

import java.util.Map;

public class TestProfile implements QuarkusTestProfile {
    private static final Logger LOG = Logger.getLogger(TestProfile.class);

    public TestProfile() {
        LOG.debug("New test profile override loaded.");
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "proxy.target.base-url", "http://localhost:37200",
                "quarkus.log.console.json.enabled", "false",
                "proxy.prefilter.auth-enabled", "false",
                "token.utils.wdx1.authorized-spn", "HTTP/wdx1e01.corp.ubp.ch"
        );
    }
}
