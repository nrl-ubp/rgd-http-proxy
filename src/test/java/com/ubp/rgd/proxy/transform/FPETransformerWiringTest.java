package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that {@code proxy.transform.impl=FPE} really wires the FPE transformer in a running
 * container.
 * <p>
 * The unit tests of {@link EndPointTransformerProducer} cover the selection itself, but not the CDI
 * part of it: the qualifiers removing the implementations from the default beans, the produced bean
 * being the one injected, and the FPE key being read at startup. Those only show up once Arc has
 * resolved the injection points for real.
 */
@QuarkusTest
@TestProfile(FPETransformerWiringTest.FPEProfile.class)
class FPETransformerWiringTest {

    /**
     * Runs the proxy with Format Preserving Encryption instead of the RPS engine.
     */
    public static class FPEProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "proxy.transform.impl", "FPE",
                    "proxy.transform.fpe.key", "2b7e151628aed2a6abf7158809cf4f3c",
                    "quarkus.log.console.json.enabled", "false",
                    "proxy.prefilter.auth-enabled", "false",
                    "proxy.transform.config-file", "./src/test/resources/rps_transform_config.json");
        }
    }

    @Inject
    EndPointTransformer transformer;

    @Test
    @DisplayName("The injected transformer is the one named by proxy.transform.impl")
    void fpeIsInjected() {
        // An unqualified injection point must resolve to the produced bean, not to one of the two
        // implementations, otherwise the configuration would simply be ignored.
        assertInstanceOf(FPEEndPointTransformer.class, ClientProxy.unwrap(transformer));
    }

    @Test
    @DisplayName("The wired transformer really encrypts, and the key was read at startup")
    void fpeTransformerIsUsable() throws Exception {
        RPSValue value = new RPSValue(new RPSMapping("Person", "FirstName"), "Bernadette");

        ProcessingContext processingContext = new ProcessingContext();
        processingContext.addEvidence(new Evidence("Action", "Protect"));
        transformer.transformData(new IRPSValue[]{value}, null, processingContext);

        assertTrue(value.getTransformed().startsWith("RG{A0"),
                "must be an FPE token: " + value.getTransformed());

        RPSValue back = new RPSValue(new RPSMapping("Person", "FirstName"), value.getTransformed());
        ProcessingContext unprotect = new ProcessingContext();
        unprotect.addEvidence(new Evidence("Action", "Unprotect"));
        transformer.transformData(new IRPSValue[]{back}, null, unprotect);

        assertEquals("Bernadette", back.getTransformed());
    }
}
