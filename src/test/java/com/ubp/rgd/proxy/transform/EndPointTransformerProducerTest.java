package com.ubp.rgd.proxy.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Covers the selection performed by {@link EndPointTransformerProducer}.
 * <p>
 * Picking the wrong implementation would protect the data with another algorithm than the one the
 * environment expects, and the two are not interchangeable, so an unusable value must stop the
 * application rather than be silently ignored.
 */
class EndPointTransformerProducerTest {

    private final EndPointTransformer rps = mock(EndPointTransformer.class);
    private final EndPointTransformer fpe = mock(EndPointTransformer.class);

    private EndPointTransformerProducer producerFor(String impl) {
        EndPointTransformerProducer producer = new EndPointTransformerProducer();
        producer.transformerImpl = impl;
        producer.rpsTransformer = rps;
        producer.fpeTransformer = fpe;
        return producer;
    }

    @Test
    @DisplayName("RPS selects the engine transformer")
    void rpsIsSelected() {
        assertSame(rps, producerFor("RPS").endPointTransformer());
    }

    @Test
    @DisplayName("FPE selects the Format Preserving Encryption transformer")
    void fpeIsSelected() {
        assertSame(fpe, producerFor("FPE").endPointTransformer());
    }

    @Test
    @DisplayName("The implementation name is read whatever its case and spacing")
    void nameIsReadLeniently() {
        assertSame(fpe, producerFor("fpe").endPointTransformer());
        assertSame(fpe, producerFor(" Fpe ").endPointTransformer());
        assertSame(rps, producerFor("rps").endPointTransformer());
    }

    @Test
    @DisplayName("An unknown implementation stops the application instead of falling back")
    void unknownImplementationFails() {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> producerFor("Vault").endPointTransformer());

        assertTrue(failure.getMessage().contains("Vault"), "must name the offending value");
        assertTrue(failure.getMessage().contains("RPS") && failure.getMessage().contains("FPE"),
                "must list the accepted values");
    }

    @Test
    @DisplayName("An empty or missing implementation is an error, not a silent default")
    void emptyImplementationFails() {
        assertThrows(IllegalStateException.class, () -> producerFor("").endPointTransformer());
        assertThrows(IllegalStateException.class, () -> producerFor(null).endPointTransformer());
    }

    @Test
    @DisplayName("The qualifier constants match the accepted property values")
    void qualifierConstantsMatchThePropertyValues() {
        // The producer compares the property against these constants, and the implementations are
        // qualified with them: a typo would leave a whole implementation unreachable.
        assertEquals("RPS", TransformerImpl.RPS);
        assertEquals("FPE", TransformerImpl.FPE);
        assertEquals(TransformerImpl.RPS,
                RPSEndPointTransformer.class.getAnnotation(TransformerImpl.class).value());
        assertEquals(TransformerImpl.FPE,
                FPEEndPointTransformer.class.getAnnotation(TransformerImpl.class).value());
    }
}
