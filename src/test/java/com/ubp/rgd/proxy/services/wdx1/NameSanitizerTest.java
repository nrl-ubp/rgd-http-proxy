package com.ubp.rgd.proxy.services.wdx1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NameSanitizerTest {

    @Test
    void testVanDerWaals() {
        // "van der" removed, then diacritics/punct/spaces removed (none remain), output keeps letters only
        assertEquals("WAALS", NameSanitizer.sanitizeName("van der Waals"));
    }

    @Test
    void testVonNeumann() {
        assertEquals("NEUMANN", NameSanitizer.sanitizeName("Von    Neumann"));
    }

    @Test
    void testDeLaCruz() {
        assertEquals("CRUZ", NameSanitizer.sanitizeName("de la Cruz"));
    }

    @Test
    void testMcDonaldWithSpace() {
        // Note: In the provided sanitizer, "mc" is in the removable prefix list.
        // Because "Mc Donald" has "Mc" as a separate token, it WILL be removed.
        // Final step removes space -> "Donald"
        assertEquals("DONALD", NameSanitizer.sanitizeName("Mc Donald"));
    }

    @Test
    void testBrianAttachedByApostrophe() {
        // Attached prefix not removed; apostrophe stripped; letters preserved
        assertEquals("OBRIAN", NameSanitizer.sanitizeName("O'Brian"));
    }

    @Test
    void testOAcuteConchobhair() {
        // "Ó" is a listed prefix; it is removed as a separate token.
        // Remaining "Conchobhair" has diacritics removed (if any) and spaces removed.
        assertEquals("CONCHOBHAIR", NameSanitizer.sanitizeName("Ó Conchobhair"));
    }

    @Test
    void testCyrillicNikolaiGogol() {
        assertEquals("NIKOLAJGOGOL", NameSanitizer.sanitizeName("Николай Гоголь"));
    }

    @Test
    void testGreekAristoteles() {
        assertEquals("ARISTOTELES", NameSanitizer.sanitizeName("Αριστοτέλης"));
    }

    @Test
    void testHyphenatedVanDenBerg() {
        // "van-den" is not separate-token prefix (hyphenated), so prefix is NOT removed.
        // Hyphen is removed; letters preserved -> "vandenBerg"
        assertEquals("VANDENBERG", NameSanitizer.sanitizeName("van-den Berg"));
    }

    @Test
    void testMhicGiollaBrighde() {
        // "mhic giolla" is in the list (as a multi-word prefix), so it should be removed.
        // Remaining "Bríghde" -> diacritics removed -> "Brighde"
        assertEquals("BRIGHDE", NameSanitizer.sanitizeName("mhic giolla Bríghde"));
    }
}
