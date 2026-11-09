package com.ubp.rgd.proxy.fuzzysearch.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersonSearchResourceTest {

    @Test
    @DisplayName("A query is tried in the spellings a name may have been stored with")
    void caseVariants() {
        assertEquals(List.of("jean-claude", "Jean-Claude", "JEAN-CLAUDE"),
                PersonSearchResource.caseVariants("jean-claude"));
        assertEquals(List.of("le gall", "Le Gall", "LE GALL"), PersonSearchResource.caseVariants("le gall"));
        assertEquals(List.of("O'brien", "O'Brien", "o'brien", "O'BRIEN"),
                PersonSearchResource.caseVariants("O'brien"));
    }
}
