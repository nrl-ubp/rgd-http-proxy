package com.ubp.rgd.proxy.wiring;

import com.ubp.rgd.proxy.fpe.FPEEndPointTransformer;
import com.ubp.rgd.proxy.transform.EndPointTransformer;
import com.ubp.rgd.proxy.transform.model.Evidence;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.ValueMapping;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the SPI producer hands out the FPE transformer when {@code proxy.transform.impl} names it,
 * and that the key was read at startup.
 */
@QuarkusTest
@TestProfile(FPETransformerWiringTest.FpeProfile.class)
class FPETransformerWiringTest {

    public static class FpeProfile extends com.ubp.rgd.proxy.services.TestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> props = super.getConfigOverrides();
            props.put("proxy.transform.impl", "FPE");
            props.put("proxy.transform.fpe.key", "2b7e151628aed2a6abf7158809cf4f3c");
            return props;
        }
    }

    @Inject
    EndPointTransformer transformer;

    @Test
    @DisplayName("The injected transformer is the one named by proxy.transform.impl")
    void fpeIsInjected() {
        // An unqualified injection point must resolve to the produced bean, not to the
        // implementation, otherwise the configuration would simply be ignored.
        assertInstanceOf(FPEEndPointTransformer.class, ClientProxy.unwrap(transformer));
    }

    @Test
    @DisplayName("The wired transformer really encrypts, and the key was read at startup")
    void fpeTransformerIsUsable() throws Exception {
        MappedValue value = new MappedValue(new ValueMapping("Person", "FirstName"), "Bernadette");
        transformer.transformData(new MappedValue[]{value}, null, action("Protect"));

        assertTrue(value.getTransformed().startsWith("RG{A0"), "must be an FPE token: " + value.getTransformed());

        MappedValue back = new MappedValue(new ValueMapping("Person", "FirstName"), value.getTransformed());
        transformer.transformData(new MappedValue[]{back}, null, action("Unprotect"));

        assertEquals("Bernadette", back.getTransformed());
    }

    private static ProcessingContext action(String action) {
        ProcessingContext context = new ProcessingContext();
        context.addEvidence(new Evidence("Action", action));
        return context;
    }
}
