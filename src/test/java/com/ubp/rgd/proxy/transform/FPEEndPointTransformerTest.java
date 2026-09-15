package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.IRPSValue;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link FPEEndPointTransformer}.
 * <p>
 * The essential property is that protecting then unprotecting a value gives the value back
 * unchanged, whatever its length, its alphabet and the characters it carries. FF1 rejects inputs
 * shorter than a million possible values, so short values are padded: these tests walk every length
 * from one character up, which is exactly where that padding is exercised.
 */
class FPEEndPointTransformerTest {

    /** The AES-128 test key of NIST SP 800-38G. */
    private static final String KEY = "2b7e151628aed2a6abf7158809cf4f3c";

    private static final byte[] TWEAK = FPEEndPointTransformer.tweakOf(new RPSMapping("Person", "Name"));

    private final FPEEndPointTransformer transformer = transformerWith(KEY);

    private static FPEEndPointTransformer transformerWith(String key) {
        return FPEEndPointTransformer.withKey(key);
    }

    private String roundTrip(String clearValue) {
        return transformer.unprotect(transformer.protect(clearValue, TWEAK), TWEAK);
    }

    // ---------------------------------------------------------------- round trips

    @ParameterizedTest
    @ValueSource(strings = {"1", "42", "007", "1234", "12345", "123456", "1234567890",
            "12345678901234567890"})
    @DisplayName("A numeric value of any length round trips, padding included")
    void numericRoundTrip(String clearValue) {
        assertEquals(clearValue, roundTrip(clearValue));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "Jo", "Bob", "Jean", "DUSSE", "Bernadette", "aB3", "x1y2z3"})
    @DisplayName("An alphanumeric value of any length round trips, padding included")
    void alphanumericRoundTrip(String clearValue) {
        assertEquals(clearValue, roundTrip(clearValue));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Jean-Claude DUSSE", "12-34", "2024-01-15", "+41 22 555 10 10",
            "jean.dusse@ubp.ch", "Genève", "N° 4"})
    @DisplayName("A formatted value round trips with its separators in place")
    void formattedRoundTrip(String clearValue) {
        assertEquals(clearValue, roundTrip(clearValue));
    }

    @Test
    @DisplayName("Leading zeros survive the round trip")
    void leadingZerosArePreserved() {
        assertEquals("000042", roundTrip("000042"));
        assertEquals("007", roundTrip("007"));
    }

    // ---------------------------------------------------------------- token shape

    @Test
    @DisplayName("A protected value is wrapped like an RPS token, so unprotect can find it")
    void protectedValueIsAToken() {
        String token = transformer.protect("Bernadette", TWEAK);

        assertTrue(token.startsWith("RG{"), "must be wrapped: " + token);
        assertTrue(token.endsWith("}"), "must be wrapped: " + token);
        assertTrue(ValuePlan.TOKEN_PATTERN.matcher(token).matches(),
                "must match the pattern used to locate values to unprotect: " + token);
    }

    @Test
    @DisplayName("The token header carries the alphabet and the number of padding characters")
    void tokenHeaderCarriesTheAlphabetAndThePadding() {
        // 10 digits, above the minimum of 6: numeric alphabet, no padding.
        assertTrue(transformer.protect("1234567890", TWEAK).startsWith("RG{N0"));
        // 2 digits: numeric alphabet, padded with 4 characters to reach the minimum.
        assertTrue(transformer.protect("42", TWEAK).startsWith("RG{N4"));
        // Letters: alphanumeric alphabet, above its minimum of 4.
        assertTrue(transformer.protect("Bernadette", TWEAK).startsWith("RG{A0"));
        // 2 letters: alphanumeric alphabet, padded with 2 characters.
        assertTrue(transformer.protect("Jo", TWEAK).startsWith("RG{A2"));
    }

    @Test
    @DisplayName("A numeric value encrypts to digits, so its format is preserved inside the token")
    void numericValueEncryptsToDigits() {
        String token = transformer.protect("1234567890", TWEAK);
        String payload = token.substring("RG{N0".length(), token.length() - 1);

        assertEquals(10, payload.length(), "the ciphertext must have the length of the clear value");
        assertTrue(payload.chars().allMatch(Character::isDigit), "must stay digits: " + payload);
    }

    @Test
    @DisplayName("Characters outside the alphabet keep their exact position inside the token")
    void separatorsKeepTheirPosition() {
        String token = transformer.protect("12-34", TWEAK);
        String payload = token.substring("RG{N4".length(), token.length() - 1);

        assertEquals('-', payload.charAt(2), "the dash must stay where it was: " + payload);
    }

    @Test
    @DisplayName("The value is really encrypted, not just wrapped")
    void valueIsEncrypted() {
        String token = transformer.protect("1234567890", TWEAK);
        assertFalse(token.contains("1234567890"), "the clear value must not appear in the token: " + token);
    }

    // ---------------------------------------------------------------- keys and tweaks

    @Test
    @DisplayName("The same value gives the same token, which is what makes FPE searchable")
    void encryptionIsDeterministic() {
        assertEquals(transformer.protect("Bernadette", TWEAK), transformer.protect("Bernadette", TWEAK));
    }

    @Test
    @DisplayName("The same value in two properties gives two different tokens")
    void tweakSeparatesTheProperties() {
        byte[] firstName = FPEEndPointTransformer.tweakOf(new RPSMapping("Person", "FirstName"));
        byte[] lastName = FPEEndPointTransformer.tweakOf(new RPSMapping("Person", "LastName"));

        assertNotEquals(transformer.protect("Bernadette", firstName),
                transformer.protect("Bernadette", lastName));
    }

    @Test
    @DisplayName("A token is only readable with the tweak of its own property")
    void tweakIsNeededToUnprotect() {
        byte[] firstName = FPEEndPointTransformer.tweakOf(new RPSMapping("Person", "FirstName"));
        byte[] lastName = FPEEndPointTransformer.tweakOf(new RPSMapping("Person", "LastName"));

        assertNotEquals("Bernadette", transformer.unprotect(transformer.protect("Bernadette", firstName), lastName));
    }

    @Test
    @DisplayName("A token is only readable with the key that produced it")
    void keyIsNeededToUnprotect() {
        FPEEndPointTransformer other = transformerWith("00112233445566778899aabbccddeeff");
        assertNotEquals("Bernadette", other.unprotect(transformer.protect("Bernadette", TWEAK), TWEAK));
    }

    @Test
    @DisplayName("A value without mapping uses an empty tweak rather than failing")
    void valueWithoutMappingUsesAnEmptyTweak() {
        assertEquals(0, FPEEndPointTransformer.tweakOf(null).length);
        assertEquals(0, FPEEndPointTransformer.tweakOf(new RPSMapping(null, null)).length);
    }

    // ---------------------------------------------------------------- values left alone

    @Test
    @DisplayName("An empty value is left untouched")
    void emptyValueIsLeftUntouched() {
        assertEquals("", transformer.protect("", TWEAK));
    }

    @Test
    @DisplayName("A value carrying a brace is left untouched, it would break the token")
    void braceBearingValueIsLeftUntouched() {
        // A brace kept in place inside the token would close it early and truncate the value.
        assertEquals("a{b}c", transformer.protect("a{b}c", TWEAK));
    }

    @Test
    @DisplayName("A value holding nothing of the alphabet is left untouched")
    void valueWithoutEncryptableCharacterIsLeftUntouched() {
        assertEquals("---", transformer.protect("---", TWEAK));
        assertEquals("...", transformer.protect("...", TWEAK));
    }

    @Test
    @DisplayName("Unprotecting a value that is not a token leaves it untouched")
    void clearValueIsLeftUntouchedOnUnprotect() {
        assertEquals("Bernadette", transformer.unprotect("Bernadette", TWEAK));
        assertEquals("", transformer.unprotect("", TWEAK));
    }

    @Test
    @DisplayName("An RPS token is left untouched, its header is not an FPE one")
    void rpsTokenIsLeftUntouched() {
        // Mixed data happens when switching implementation: it must not be corrupted.
        String rpsToken = "RG{AB12345678aa}";
        assertEquals(rpsToken, transformer.unprotect(rpsToken, TWEAK));
    }

    @Test
    @DisplayName("A truncated token is left untouched rather than failing")
    void truncatedTokenIsLeftUntouched() {
        assertEquals("RG{}", transformer.unprotect("RG{}", TWEAK));
        assertEquals("RG{N}", transformer.unprotect("RG{N}", TWEAK));
        assertEquals("RG{N4}", transformer.unprotect("RG{N4}", TWEAK));
    }

    // ---------------------------------------------------------------- key configuration

    @Test
    @DisplayName("A missing key fails at startup, not on the first sensitive payload")
    void missingKeyFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> transformerWith(null));
        assertThrows(IllegalStateException.class, () -> transformerWith("  "));
    }

    @Test
    @DisplayName("A key that is not valid hexadecimal fails at startup")
    void invalidKeyFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> transformerWith("not hexadecimal at all!!"));
        assertThrows(IllegalStateException.class, () -> transformerWith("abc"));
    }

    @Test
    @DisplayName("A key of the wrong size fails at startup")
    void wrongKeySizeFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> transformerWith("2b7e151628aed2a6"));
    }

    @Test
    @DisplayName("192 and 256 bits keys are accepted")
    void longerKeysAreAccepted() {
        assertEquals("Bernadette", transformerWith("2b7e151628aed2a6abf7158809cf4f3cef4359d8d580aa4f")
                .unprotect(transformerWith("2b7e151628aed2a6abf7158809cf4f3cef4359d8d580aa4f")
                        .protect("Bernadette", TWEAK), TWEAK));
    }

    // ---------------------------------------------------------------- transformData

    @Test
    @DisplayName("Every value comes back with a transformed value, as reassembling requires")
    void everyValueGetsATransformedValue() throws Exception {
        RPSValue[] values = {
                new RPSValue(new RPSMapping("Person", "Name"), "Bernadette"),
                new RPSValue(new RPSMapping("Person", "Age"), "42"),
                new RPSValue(new RPSMapping("Person", "Note"), "---")
        };

        transformer.transformData(values, null, processingContext("Protect"));

        for (RPSValue value : values) {
            assertTrue(value.getTransformed() != null,
                    "a value without transformed value breaks the reassembling of the payload");
        }
    }

    @Test
    @DisplayName("The action evidence drives the direction of the transformation")
    void actionEvidenceDrivesTheDirection() throws Exception {
        RPSValue protect = new RPSValue(new RPSMapping("Person", "Name"), "Bernadette");
        transformer.transformData(new IRPSValue[]{protect}, null, processingContext("Protect"));
        assertTrue(protect.getTransformed().startsWith("RG{"));

        RPSValue unprotect = new RPSValue(new RPSMapping("Person", "Name"), protect.getTransformed());
        transformer.transformData(new IRPSValue[]{unprotect}, null, processingContext("Unprotect"));
        assertEquals("Bernadette", unprotect.getTransformed());
    }

    @Test
    @DisplayName("A null value is skipped without failing")
    void nullValueIsSkipped() throws Exception {
        RPSValue value = new RPSValue(new RPSMapping("Person", "Name"), null);
        transformer.transformData(new IRPSValue[]{value}, null, processingContext("Protect"));
        assertEquals(null, value.getTransformed());
    }

    // ---------------------------------------------------------------- end to end

    @Test
    @DisplayName("A payload round trips through the full transform, headers and query included")
    void payloadRoundTrip() throws Exception {
        String json = "{\"name\":\"Bernadette\",\"city\":\"Genève\"}";

        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        String protectedJson = transformer.transform(json, headers, params, config("Protect", null));
        assertFalse(protectedJson.contains("Bernadette"), "must be protected: " + protectedJson);

        String clearJson = transformer.transform(protectedJson, headers, params, config("Unprotect", null));
        assertEquals(json, clearJson);
    }

    @Test
    @DisplayName("A payload round trips when the value is tokenized word by word")
    void payloadRoundTripWithExtractRegex() throws Exception {
        String json = "{\"name\":\"Jean-Claude DUSSE\",\"city\":\"Genève\"}";

        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        String protectedJson = transformer.transform(json, headers, params, config("Protect", "[A-Za-z]+"));
        assertFalse(protectedJson.contains("Jean"), "must be protected: " + protectedJson);
        assertTrue(protectedJson.contains("-"), "the formatting must be preserved: " + protectedJson);

        String clearJson = transformer.transform(protectedJson, headers, params, config("Unprotect", null));
        assertEquals(json, clearJson);
    }

    private static ProcessingContext processingContext(String action) {
        ProcessingContext processingContext = new ProcessingContext();
        processingContext.addEvidence(new Evidence("Action", action));
        return processingContext;
    }

    /**
     * Configuration protecting {@code $.name} and {@code $.city} of the test payload.
     */
    private static EndPointTransformConfig config(String action, String extractRegex) {
        EndPointTransformConfig cfg = new EndPointTransformConfig();
        cfg.setEndpointTransformWhen("Protect".equals(action) ? "BEFORE" : "AFTER");

        Map<String, String> evidences = new HashMap<>();
        evidences.put("Action", action);
        cfg.setProcessingContextEvidences(evidences);

        cfg.setEntityTransformConfigs(Set.of(
                entity("$.name", "FirstName", extractRegex),
                entity("$.city", "City", extractRegex)));
        return cfg;
    }

    private static EntityTransformConfig entity(String jsonPath, String property, String extractRegex) {
        EntityTransformConfig entity = new EntityTransformConfig();
        entity.setJsonPath(jsonPath);
        entity.setRpsClassName("Person");
        entity.setRpsPropertyName(property);
        entity.setExtractRegex(extractRegex);
        return entity;
    }
}
