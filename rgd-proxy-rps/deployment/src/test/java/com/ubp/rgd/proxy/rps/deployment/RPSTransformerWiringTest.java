package com.ubp.rgd.proxy.rps.deployment;

import com.ubp.rgd.proxy.rps.RPSEndPointTransformer;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the extension contributes the RPS transformer and that the SPI producer hands it out.
 */
class RPSTransformerWiringTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .overrideConfigKey("proxy.transform.impl", "rps")
            .overrideConfigKey("proxy.transform.config-file", "src/test/resources/empty_transform_config.json")
            .overrideConfigKey("proxy.transform.client-id", "3150a79e-3084-4946-aeb5-e7e73aea3904")
            .overrideConfigKey("proxy.transform.client-secret", "test-secret")
            .overrideConfigKey("proxy.transform.identity-url", "http://localhost:1/identity")
            .overrideConfigKey("proxy.transform.engine-url", "http://localhost:1/engine")
            .overrideConfigKey("proxy.secrets-manager-lookup.mongodb.database", "rgd-test");

    @Inject
    EndPointTransformer transformer;

    @Test
    @DisplayName("The injected transformer is the RPS one")
    void rpsIsInjected() {
        assertInstanceOf(RPSEndPointTransformer.class, ClientProxy.unwrap(transformer));
        assertTrue(transformer.supportsTokenMappingIndex());
    }
}
