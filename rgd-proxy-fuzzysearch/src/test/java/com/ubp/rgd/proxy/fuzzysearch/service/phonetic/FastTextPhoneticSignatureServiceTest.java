package com.ubp.rgd.proxy.fuzzysearch.service.phonetic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastTextPhoneticSignatureServiceTest {

    @Test
    @DisplayName("Without vocabulary yet, the neighbour lookups return nothing instead of failing")
    void noVocabularyMeansNoNeighbour() {
        // No FastText model loaded: the lookup must stop before needing it.
        FastTextPhoneticSignatureService service = new FastTextPhoneticSignatureService();

        assertTrue(service.neighbourTokens("jean", 5).isEmpty());
        assertTrue(service.neighbourSignatures("jean").isEmpty());
        assertEquals(0, service.vocabularySize());
        assertEquals(0, service.rebuildVocabulary(List.of(" ", "")));
    }
}
