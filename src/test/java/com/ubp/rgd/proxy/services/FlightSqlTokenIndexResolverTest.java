package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlightSqlTokenIndexResolverTest {

    @BeforeEach
    void seedTheTable() {
        // The table is JVM-wide and populated at startup by the service, so seed it explicitly here.
        FlightSqlTokenIndexResolver.clear();
        FlightSqlTokenIndexResolver.register("B", "Person.ShortString");
    }

    @AfterEach
    void cleanUp() {
        FlightSqlTokenIndexResolver.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // Encoding
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldDecodeTheFilledRangeOfTheEncoding() {
        assertEquals(0, FlightSqlTokenIndexResolver.decodeMappingIndex("Ax"));
        assertEquals(1, FlightSqlTokenIndexResolver.decodeMappingIndex("Bx"));
        assertEquals(25, FlightSqlTokenIndexResolver.decodeMappingIndex("Zx"));
    }

    @Test
    void shouldDecodeTheEscapedRangeOfTheEncoding() {
        assertEquals(26, FlightSqlTokenIndexResolver.decodeMappingIndex("ZA"));
        assertEquals(27, FlightSqlTokenIndexResolver.decodeMappingIndex("ZB"));
        assertEquals(51, FlightSqlTokenIndexResolver.decodeMappingIndex("ZZ"));
    }

    @Test
    void shouldNotConfuseTheLowercaseFillerWithTheEscapedRange() {
        // Zx is 25 while ZA is 26: the filler being lowercase, the two ranges never collide.
        assertEquals(25, FlightSqlTokenIndexResolver.decodeMappingIndex("Zx"));
        assertEquals(26, FlightSqlTokenIndexResolver.decodeMappingIndex("ZA"));
    }

    @Test
    void shouldRejectASymbolNotFollowingTheEncoding() {
        // Only the escape letter introduces a second uppercase character.
        assertEquals(-1, FlightSqlTokenIndexResolver.decodeMappingIndex("BA"));
        assertEquals(-1, FlightSqlTokenIndexResolver.decodeMappingIndex("A3"));
        assertEquals(-1, FlightSqlTokenIndexResolver.decodeMappingIndex("2x"));
        assertEquals(-1, FlightSqlTokenIndexResolver.decodeMappingIndex("ax"));
        assertEquals(-1, FlightSqlTokenIndexResolver.decodeMappingIndex("A"));
        assertEquals(-1, FlightSqlTokenIndexResolver.decodeMappingIndex("Axx"));
        assertEquals(-1, FlightSqlTokenIndexResolver.decodeMappingIndex(null));
    }

    @Test
    void shouldEncodeEveryRepresentableIndexBackToItsSymbol() {
        assertEquals("Ax", FlightSqlTokenIndexResolver.encodeMappingIndex(0));
        assertEquals("Zx", FlightSqlTokenIndexResolver.encodeMappingIndex(25));
        assertEquals("ZA", FlightSqlTokenIndexResolver.encodeMappingIndex(26));
        assertEquals("ZZ", FlightSqlTokenIndexResolver.encodeMappingIndex(51));
    }

    @Test
    void shouldRoundTripEveryIndex() {
        for (int index = 0; index <= FlightSqlTokenIndexResolver.MAX_INDEX; index++) {
            String symbol = FlightSqlTokenIndexResolver.encodeMappingIndex(index);
            assertEquals(2, symbol.length());
            assertEquals(index, FlightSqlTokenIndexResolver.decodeMappingIndex(symbol));
        }
    }

    @Test
    void shouldRefuseToEncodeAnIndexOutOfTheRepresentableRange() {
        assertThrows(IllegalArgumentException.class,
                () -> FlightSqlTokenIndexResolver.encodeMappingIndex(-1));
        assertThrows(IllegalArgumentException.class,
                () -> FlightSqlTokenIndexResolver.encodeMappingIndex(52));
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the index out of a token
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldReadTheMappingIndexOfAToken() {
        assertEquals("Bx", FlightSqlTokenIndexResolver.mappingIndexOf("RG{Bx12345678aa}"));
        assertEquals("ZA", FlightSqlTokenIndexResolver.mappingIndexOf("RG{ZA12345678aa}"));
    }

    @Test
    void shouldReadNoMappingIndexFromAValueThatIsNotAToken() {
        assertNull(FlightSqlTokenIndexResolver.mappingIndexOf("John Doe"));
        assertNull(FlightSqlTokenIndexResolver.mappingIndexOf("RG{B"));
        assertNull(FlightSqlTokenIndexResolver.mappingIndexOf(null));
    }

    // ---------------------------------------------------------------------------------------------
    // Table lookup
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldResolveTheQualifiedPropertyNameOfADeclaredIndex() {
        assertEquals("Person.ShortString",
                FlightSqlTokenIndexResolver.resolveMappingName("RG{Bx12345678aa}"));
    }

    @Test
    void shouldResolveADeclaredIndexOfTheEscapedRange() {
        FlightSqlTokenIndexResolver.register("ZA", "Account.Number");

        assertEquals("Account.Number",
                FlightSqlTokenIndexResolver.resolveMappingName("RG{ZA12345678aa}"));
    }

    @Test
    void shouldTreatAOneLetterDeclarationAsItsPaddedForm() {
        FlightSqlTokenIndexResolver.register("C", "Person.BirthDate");

        assertEquals("Person.BirthDate",
                FlightSqlTokenIndexResolver.resolveMappingName("RG{Cx12345678aa}"));
    }

    @Test
    void shouldResolveNothingForAnUndeclaredOrInvalidIndex() {
        assertNull(FlightSqlTokenIndexResolver.resolveMappingName("RG{Ax12345678aa}"));
        assertNull(FlightSqlTokenIndexResolver.resolveMappingName("RG{ZZ12345678aa}"));
        assertNull(FlightSqlTokenIndexResolver.resolveMappingName("RG{2x12345678aa}"));
        assertNull(FlightSqlTokenIndexResolver.resolveMappingName("John Doe"));
    }

    @Test
    void shouldForgetEveryDeclarationWhenTheTableIsCleared() {
        assertEquals(1, FlightSqlTokenIndexResolver.size());

        FlightSqlTokenIndexResolver.clear();

        assertEquals(0, FlightSqlTokenIndexResolver.size());
        assertNull(FlightSqlTokenIndexResolver.resolveMappingName("RG{Bx12345678aa}"));
    }

    @Test
    void shouldBuildTheRpsMappingOfADeclaredIndex() {
        RPSMapping mapping = FlightSqlTokenIndexResolver.resolveMapping("RG{Bx12345678aa}");

        assertNotNull(mapping);
        assertEquals("Person", mapping.getClassName());
        assertEquals("ShortString", mapping.getPropertyName());
    }

    @Test
    void shouldBuildNoRpsMappingFromAMalformedDeclaration() {
        FlightSqlTokenIndexResolver.register("ZA", "NoPropertySeparator");

        assertNull(FlightSqlTokenIndexResolver.resolveMapping("RG{ZA12345678aa}"));
    }

    @Test
    void shouldSplitTheDeclarationOnItsLastSeparator() {
        FlightSqlTokenIndexResolver.register("ZA", "com.ubp.Person.Number");

        RPSMapping mapping = FlightSqlTokenIndexResolver.resolveMapping("RG{ZA12345678aa}");

        assertNotNull(mapping);
        assertEquals("com.ubp.Person", mapping.getClassName());
        assertEquals("Number", mapping.getPropertyName());
    }
}
