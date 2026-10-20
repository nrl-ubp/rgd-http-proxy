package com.ubp.rgd.proxy.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the selection performed by {@link EndPointTransformerProducer}.
 * <p>
 * Picking the wrong implementation would protect the data with another algorithm than the one the
 * environment expects, and the two are not interchangeable, so an unusable value must stop the
 * application rather than be silently ignored. The CDI lookup itself is covered by the wiring tests
 * of the extensions.
 */
class EndPointTransformerProducerTest {

    private static final List<String> BOTH = List.of(TransformerImpl.FPE, TransformerImpl.RPS);

    @Test
    @DisplayName("The configured implementation is selected among the available ones")
    void configuredImplementationIsSelected() {
        assertEquals("RPS", EndPointTransformerProducer.select("RPS", BOTH));
        assertEquals("FPE", EndPointTransformerProducer.select("FPE", BOTH));
    }

    @Test
    @DisplayName("The implementation name is read whatever its case and spacing")
    void nameIsReadLeniently() {
        assertEquals("FPE", EndPointTransformerProducer.select("fpe", BOTH));
        assertEquals("FPE", EndPointTransformerProducer.select(" Fpe ", BOTH));
        assertEquals("RPS", EndPointTransformerProducer.select("rps", BOTH));
    }

    @Test
    @DisplayName("An unknown implementation stops the application instead of falling back")
    void unknownImplementationFails() {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> EndPointTransformerProducer.select("Vault", BOTH));

        assertTrue(failure.getMessage().contains("Vault"), "must name the offending value");
        assertTrue(failure.getMessage().contains("RPS") && failure.getMessage().contains("FPE"),
                "must list the accepted values");
    }

    @Test
    @DisplayName("An implementation whose extension is not in the application is rejected")
    void absentImplementationFails() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> EndPointTransformerProducer.select("FPE", List.of(TransformerImpl.RPS)));

        assertTrue(failure.getMessage().contains("accepts RPS."), failure.getMessage());
        assertThrows(IllegalStateException.class, () -> EndPointTransformerProducer.select("RPS", List.of()));
    }

    @Test
    @DisplayName("An empty or missing implementation is an error, not a silent default")
    void emptyImplementationFails() {
        assertThrows(IllegalStateException.class, () -> EndPointTransformerProducer.select("", BOTH));
        assertThrows(IllegalStateException.class, () -> EndPointTransformerProducer.select(null, BOTH));
    }
}
