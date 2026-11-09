package com.ubp.rgd.proxy.sqlgateway;

import com.ubp.rgd.proxy.transform.model.ValueMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenIndexResolverTest {

    @BeforeEach
    void seedTheTable() {
        // The table is JVM-wide and populated at startup by the service, so seed it explicitly here.
        TokenIndexResolver.clear();
        TokenIndexResolver.register("B", "Person.ShortString");
    }

    @AfterEach
    void cleanUp() {
        TokenIndexResolver.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // Encoding
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldDecodeTheFilledRangeOfTheEncoding() {
        assertEquals(0, TokenIndexResolver.decodeMappingIndex("Ax"));
        assertEquals(1, TokenIndexResolver.decodeMappingIndex("Bx"));
        assertEquals(25, TokenIndexResolver.decodeMappingIndex("Zx"));
    }

    @Test
    void shouldDecodeTheEscapedRangeOfTheEncoding() {
        assertEquals(26, TokenIndexResolver.decodeMappingIndex("ZA"));
        assertEquals(27, TokenIndexResolver.decodeMappingIndex("ZB"));
        assertEquals(51, TokenIndexResolver.decodeMappingIndex("ZZ"));
    }

    @Test
    void shouldNotConfuseTheLowercaseFillerWithTheEscapedRange() {
        // Zx is 25 while ZA is 26: the filler being lowercase, the two ranges never collide.
        assertEquals(25, TokenIndexResolver.decodeMappingIndex("Zx"));
        assertEquals(26, TokenIndexResolver.decodeMappingIndex("ZA"));
    }

    @Test
    void shouldRejectASymbolNotFollowingTheEncoding() {
        // Only the escape letter introduces a second uppercase character.
        assertEquals(-1, TokenIndexResolver.decodeMappingIndex("BA"));
        assertEquals(-1, TokenIndexResolver.decodeMappingIndex("A3"));
        assertEquals(-1, TokenIndexResolver.decodeMappingIndex("2x"));
        assertEquals(-1, TokenIndexResolver.decodeMappingIndex("ax"));
        assertEquals(-1, TokenIndexResolver.decodeMappingIndex("A"));
        assertEquals(-1, TokenIndexResolver.decodeMappingIndex("Axx"));
        assertEquals(-1, TokenIndexResolver.decodeMappingIndex(null));
    }

    @Test
    void shouldEncodeEveryRepresentableIndexBackToItsSymbol() {
        assertEquals("Ax", TokenIndexResolver.encodeMappingIndex(0));
        assertEquals("Zx", TokenIndexResolver.encodeMappingIndex(25));
        assertEquals("ZA", TokenIndexResolver.encodeMappingIndex(26));
        assertEquals("ZZ", TokenIndexResolver.encodeMappingIndex(51));
    }

    @Test
    void shouldRoundTripEveryIndex() {
        for (int index = 0; index <= TokenIndexResolver.MAX_INDEX; index++) {
            String symbol = TokenIndexResolver.encodeMappingIndex(index);
            assertEquals(2, symbol.length());
            assertEquals(index, TokenIndexResolver.decodeMappingIndex(symbol));
        }
    }

    @Test
    void shouldRefuseToEncodeAnIndexOutOfTheRepresentableRange() {
        assertThrows(IllegalArgumentException.class,
                () -> TokenIndexResolver.encodeMappingIndex(-1));
        assertThrows(IllegalArgumentException.class,
                () -> TokenIndexResolver.encodeMappingIndex(52));
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the index out of a token
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldReadTheMappingIndexOfAToken() {
        assertEquals("Bx", TokenIndexResolver.mappingIndexOf("RG{Bx12345678aa}"));
        assertEquals("ZA", TokenIndexResolver.mappingIndexOf("RG{ZA12345678aa}"));
    }

    @Test
    void shouldReadNoMappingIndexFromAValueThatIsNotAToken() {
        assertNull(TokenIndexResolver.mappingIndexOf("John Doe"));
        assertNull(TokenIndexResolver.mappingIndexOf("RG{B"));
        assertNull(TokenIndexResolver.mappingIndexOf(null));
    }

    // ---------------------------------------------------------------------------------------------
    // Table lookup
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldResolveTheQualifiedPropertyNameOfADeclaredIndex() {
        assertEquals("Person.ShortString",
                TokenIndexResolver.resolveMappingName("RG{Bx12345678aa}"));
    }

    @Test
    void shouldResolveADeclaredIndexOfTheEscapedRange() {
        TokenIndexResolver.register("ZA", "Account.Number");

        assertEquals("Account.Number",
                TokenIndexResolver.resolveMappingName("RG{ZA12345678aa}"));
    }

    @Test
    void shouldTreatAOneLetterDeclarationAsItsPaddedForm() {
        TokenIndexResolver.register("C", "Person.BirthDate");

        assertEquals("Person.BirthDate",
                TokenIndexResolver.resolveMappingName("RG{Cx12345678aa}"));
    }

    @Test
    void shouldResolveNothingForAnUndeclaredOrInvalidIndex() {
        assertNull(TokenIndexResolver.resolveMappingName("RG{Ax12345678aa}"));
        assertNull(TokenIndexResolver.resolveMappingName("RG{ZZ12345678aa}"));
        assertNull(TokenIndexResolver.resolveMappingName("RG{2x12345678aa}"));
        assertNull(TokenIndexResolver.resolveMappingName("John Doe"));
    }

    @Test
    void shouldForgetEveryDeclarationWhenTheTableIsCleared() {
        assertEquals(1, TokenIndexResolver.size());

        TokenIndexResolver.clear();

        assertEquals(0, TokenIndexResolver.size());
        assertNull(TokenIndexResolver.resolveMappingName("RG{Bx12345678aa}"));
    }

    @Test
    void shouldBuildTheRpsMappingOfADeclaredIndex() {
        ValueMapping mapping = TokenIndexResolver.resolveMapping("RG{Bx12345678aa}");

        assertNotNull(mapping);
        assertEquals("Person", mapping.getClassName());
        assertEquals("ShortString", mapping.getPropertyName());
    }

    @Test
    void shouldBuildNoRpsMappingFromAMalformedDeclaration() {
        TokenIndexResolver.register("ZA", "NoPropertySeparator");

        assertNull(TokenIndexResolver.resolveMapping("RG{ZA12345678aa}"));
    }

    @Test
    void shouldSplitTheDeclarationOnItsLastSeparator() {
        TokenIndexResolver.register("ZA", "com.ubp.Person.Number");

        ValueMapping mapping = TokenIndexResolver.resolveMapping("RG{ZA12345678aa}");

        assertNotNull(mapping);
        assertEquals("com.ubp.Person", mapping.getClassName());
        assertEquals("Number", mapping.getPropertyName());
    }
}
