package com.ubp.rgd.proxy.transform;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Checks that an RPS deployment does not have to carry an FPE key.
 * <p>
 * The key is decoded in the {@code @PostConstruct} of {@link FPEEndPointTransformer} and a missing
 * one is an error, so the application would refuse to start if that bean were created even when it
 * is not the selected one. This test runs the default implementation with **no** FPE key at all: it
 * passes only as long as the unused implementation stays lazy.
 */
@QuarkusTest
@TestProfile(RPSTransformerWiringTest.RPSWithoutFpeKeyProfile.class)
class RPSTransformerWiringTest {

    /**
     * Runs the proxy with the RPS engine and without any FPE key configured.
     */
    public static class RPSWithoutFpeKeyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "proxy.transform.impl", "RPS",
                    "proxy.transform.fpe.key", "",
                    "quarkus.log.console.json.enabled", "false",
                    "proxy.prefilter.auth-enabled", "false",
                    "proxy.transform.config-file", "./src/test/resources/rps_transform_config.json");
        }
    }

    @Inject
    EndPointTransformer transformer;

    @Test
    @DisplayName("RPS is wired without an FPE key, the unused implementation stays lazy")
    void rpsIsInjectedWithoutAnFpeKey() {
        assertInstanceOf(RPSEndPointTransformer.class, ClientProxy.unwrap(transformer));
    }
}
