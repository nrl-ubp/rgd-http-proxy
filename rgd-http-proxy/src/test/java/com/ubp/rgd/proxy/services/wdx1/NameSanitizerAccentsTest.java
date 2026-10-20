package com.ubp.rgd.proxy.services.wdx1;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class NameSanitizerAccentsTest {

    @Test
    void testAGroup() {
        String input    = "ÄäÀàÁáÂâÃãÅåǍǎĄąĂăÆæ";
        // Ligatures: Æ/æ -> A/a (single base letter)
        String expected = "AAAAAAAAAAAAAAAAAAAA";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testCGroup() {
        String input    = "ÇçĆćĈĉČč";
        String expected = "CCCCCCCC";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testDGroup() {
        String input    = "ĎđĐďð";
        // đ/Đ -> d/D; ð -> d
        String expected = "DDD";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testEGroup() {
        String input    = "ÈèÉéÊêËëĚěĘę";
        String expected = "EEEEEEEEEEEE";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testGGroup() {
        String input = "ĜĝĢģĞğ";
        String expected = "GGGGGG";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testHGroup() {
        String input = "Ĥĥ";
        String expected = "HH";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testIGroup() {
        String input = "ÌìÍíÎîÏïı"; // includes dotless i
        // ı -> i
        String expected = "IIIIIIIII";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testJGroup() {
        String input = "Ĵĵ";
        String expected = "JJ";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testKGroup() {
        String input = "Ķķ";
        String expected = "KK";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testLGroup() {
        String input = "ĹĺĻļŁłĽľ";
        // Ł/ł -> L/l
        String expected = "LLLLLLLL";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testNGroup() {
        String input = "ÑñŃńŇň";
        String expected = "NNNNNN";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testOGroup() {
        String input    = "ÖöÒòÓóÔôÕõŐőØøŒœ";
        // Ligatures: Œ/œ -> O/o (single base letter), Ø/ø -> O/o
        String expected = "OOOOOOOOOOOOOOOO";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testRGroup() {
        String input = "ŔŕŘř";
        String expected = "RRRR";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testSGroup() {
        String input    = "ẞßŚśŜŝŞşŠšȘș";
        // ẞ -> S, ß -> s (single base letter)
        String expected = "SSSSSSSSSSSS";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testTGroup() {
        String input = "ŤťŢţÞþ Țț";
        // Þ/þ -> T/t; Ț/ț -> T/t
        String expected = "TTTTTTTT";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testUGroup() {
        String input = "ÜüÙùÚúÛûŰűŨũŲųŮů";
        String expected = "UUUUUUUUUUUUUUUU";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testWGroup() {
        String input = "Ŵŵ";
        String expected = "WW";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testYGroup() {
        String input = "ÝýŸÿŶŷ";
        String expected = "YYYYYY";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testZGroup() {
        String input = "ŹźŽžŻż";
        String expected = "ZZZZZZ";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }

    @Test
    void testDeleteOthers() {
        // Characters not A–Z/a–z or those listed as accented letters should be removed,
        // but now ligatures fold to base letters only.
        String input = "John-Dœ! Ænnä ✅ № 123 ~ Łódź… — O’Connor Œuvre ßeta ẞIG Ωmega";
        // Expectations with single-letter ligatures:
        // - dœ -> dO
        // - Ænnä -> Anna
        // - Łódź -> Lodz
        // - O’Connor -> OConnor
        // - Œuvre -> Ouvre
        // - ßeta -> seta
        // - ẞIG -> SIG
        // - Ωmega -> mega (Ω removed)
        String expected = "JOHNDOANNALODZOCONNOROUVRESETASIGMEGA";
        assertEquals(expected, NameSanitizer.sanitizeName(input));
    }
}
