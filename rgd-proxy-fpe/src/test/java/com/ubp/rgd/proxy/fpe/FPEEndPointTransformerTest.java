package com.ubp.rgd.proxy.fpe;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.ubp.rgd.proxy.transform.DateValues;
import com.ubp.rgd.proxy.transform.ValuePlan;
import com.ubp.rgd.proxy.transform.config.HeaderTransformConfig;
import com.ubp.rgd.proxy.transform.model.Evidence;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.ValueKind;
import com.ubp.rgd.proxy.transform.model.ValueMapping;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final byte[] TWEAK = FPEEndPointTransformer.tweakOf(new ValueMapping("Person", "Name"));

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
        byte[] firstName = FPEEndPointTransformer.tweakOf(new ValueMapping("Person", "FirstName"));
        byte[] lastName = FPEEndPointTransformer.tweakOf(new ValueMapping("Person", "LastName"));

        assertNotEquals(transformer.protect("Bernadette", firstName),
                transformer.protect("Bernadette", lastName));
    }

    @Test
    @DisplayName("A token is only readable with the tweak of its own property")
    void tweakIsNeededToUnprotect() {
        byte[] firstName = FPEEndPointTransformer.tweakOf(new ValueMapping("Person", "FirstName"));
        byte[] lastName = FPEEndPointTransformer.tweakOf(new ValueMapping("Person", "LastName"));

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
        assertEquals(0, FPEEndPointTransformer.tweakOf(new ValueMapping(null, null)).length);
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
        MappedValue[] values = {
                new MappedValue(new ValueMapping("Person", "Name"), "Bernadette"),
                new MappedValue(new ValueMapping("Person", "Age"), "42"),
                new MappedValue(new ValueMapping("Person", "Note"), "---")
        };

        transformer.transformData(values, null, processingContext("Protect"));

        for (MappedValue value : values) {
            assertTrue(value.getTransformed() != null,
                    "a value without transformed value breaks the reassembling of the payload");
        }
    }

    @Test
    @DisplayName("The action evidence drives the direction of the transformation")
    void actionEvidenceDrivesTheDirection() throws Exception {
        MappedValue protect = new MappedValue(new ValueMapping("Person", "Name"), "Bernadette");
        transformer.transformData(new MappedValue[]{protect}, null, processingContext("Protect"));
        assertTrue(protect.getTransformed().startsWith("RG{"));

        MappedValue unprotect = new MappedValue(new ValueMapping("Person", "Name"), protect.getTransformed());
        transformer.transformData(new MappedValue[]{unprotect}, null, processingContext("Unprotect"));
        assertEquals("Bernadette", unprotect.getTransformed());
    }

    @Test
    @DisplayName("A null value is skipped without failing")
    void nullValueIsSkipped() throws Exception {
        MappedValue value = new MappedValue(new ValueMapping("Person", "Name"), null);
        transformer.transformData(new MappedValue[]{value}, null, processingContext("Protect"));
        assertEquals(null, value.getTransformed());
    }

    // ---------------------------------------------------------------- dates

    private String protectAs(String clear, ValueKind kind) {
        return transformer.transform(clear, kind, TWEAK, true);
    }

    private String unprotectAs(String token, ValueKind kind) {
        return transformer.transform(token, kind, TWEAK, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-01-15", "2024.01.15", "2024/01/15", "15-01-2024", "15.01.2024", "15/01/2024",
            "15012024", "2024-01-15T10:20:30", "0001-01-01", "2999-12-31", "29/02/2000"})
    @DisplayName("A date is protected into a date of year 3000 or later, in the same format, and round trips")
    void dateStaysADate(String clearDate) {
        String protectedDate = protectAs(clearDate, ValueKind.TEXT);

        assertFalse(protectedDate.contains("RG{"), "a date must not be wrapped: " + protectedDate);
        assertNotEquals(clearDate, protectedDate);
        DateValues.ParsedDate parsed = DateValues.parse(protectedDate);
        assertNotNull(parsed, "must still be a valid date: " + protectedDate);
        assertTrue(parsed.date().getYear() >= 3000, "must land in year 3000 or later: " + protectedDate);
        assertEquals(DateValues.parse(clearDate).order(), parsed.order());
        assertEquals(DateValues.parse(clearDate).separator(), parsed.separator());
        assertEquals(DateValues.parse(clearDate).time(), parsed.time());

        assertEquals(clearDate, unprotectAs(protectedDate, ValueKind.TEXT));
    }

    @Test
    @DisplayName("A protected date is located by the token pattern, a clear one is not")
    void tokenPatternFindsProtectedDates() {
        String protectedDate = protectAs("1980-05-12", ValueKind.TEXT);

        assertTrue(transformer.tokenPattern().matcher(protectedDate).matches(), protectedDate);
        assertTrue(transformer.tokenPattern().matcher("Born on " + protectedDate + ".").find());
        assertFalse(transformer.tokenPattern().matcher("1980-05-12").find());
        assertFalse(transformer.tokenPattern().matcher("born 12345678901").find());
    }

    @Test
    @DisplayName("Unprotecting a clear date, before year 3000, leaves it untouched")
    void clearDateIsLeftUntouchedOnUnprotect() {
        assertEquals("1980-05-12", unprotectAs("1980-05-12", ValueKind.TEXT));
        assertEquals("12/05/1980", unprotectAs("12/05/1980", ValueKind.DATE));
    }

    @Test
    @DisplayName("A clear date of year 3000 or later is protected as text, never mistaken for a protected one")
    void farFutureDateIsProtectedAsText() {
        String token = protectAs("3500-01-01", ValueKind.TEXT);

        assertTrue(token.startsWith("RG{"), token);
        assertEquals("3500-01-01", unprotectAs(token, ValueKind.TEXT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-02-30", "2024-13-01", "2024-01/15", "0000-01-01", "12345678"})
    @DisplayName("Something that only looks like a date is protected as text")
    void invalidDateIsText(String value) {
        assertTrue(protectAs(value, ValueKind.TEXT).startsWith("RG{"));
    }

    // ---------------------------------------------------------------- numbers

    @ParameterizedTest
    @ValueSource(strings = {"0", "7", "42", "-17", "100", "4096", "12345", "123456", "1234567890",
            "12345678901234567890", "3.14", "-0.5", "0.001", "1.0", "1.50", "123.456789", "1.0E10", "-2.5E-7",
            "99999.99999"})
    @DisplayName("A number is protected into a number with the same form, and round trips")
    void numberStaysANumber(String clearNumber) {
        String protectedNumber = protectAs(clearNumber, ValueKind.NUMBER);

        assertFalse(protectedNumber.contains("RG{"), "a number must not be wrapped: " + protectedNumber);
        assertEquals(clearNumber.length(), protectedNumber.length(), protectedNumber);
        assertEquals(clearNumber.replaceAll("\\d", "9"), protectedNumber.replaceAll("\\d", "9"),
                "sign, point and exponent must stay in place: " + protectedNumber);
        new BigDecimal(protectedNumber);

        assertEquals(clearNumber, unprotectAs(protectedNumber, ValueKind.NUMBER));
    }

    @Test
    @DisplayName("A number is really encrypted, not just copied")
    void numberIsEncrypted() {
        assertNotEquals("1234567890", protectAs("1234567890", ValueKind.NUMBER));
        assertNotEquals("3.14159", protectAs("3.14159", ValueKind.NUMBER));
    }

    @Test
    @DisplayName("Small numbers are permuted within their own length, one to one")
    void smallNumbersArePermutedOneToOne() {
        Set<String> seen = new HashSet<>();
        for (int i = 100; i < 1000; i++) {
            String protectedNumber = protectAs(Integer.toString(i), ValueKind.NUMBER);
            assertEquals(3, protectedNumber.length());
            assertNotEquals('0', protectedNumber.charAt(0));
            assertTrue(seen.add(protectedNumber), "two numbers encrypted to " + protectedNumber);
            assertEquals(Integer.toString(i), unprotectAs(protectedNumber, ValueKind.NUMBER));
        }
    }

    @Test
    @DisplayName("A protected fraction survives a parser dropping insignificant zeros")
    void protectedFractionSurvivesNormalization() {
        for (int i = 1; i < 100; i++) {
            String clear = "0." + String.format("%02d", i);
            String reparsed = Double.toString(Double.parseDouble(protectAs(clear, ValueKind.NUMBER)));
            assertEquals(Double.parseDouble(clear), Double.parseDouble(unprotectAs(reparsed, ValueKind.NUMBER)));
        }
    }

    @Test
    @DisplayName("A numeric string is text: it keeps its token")
    void numericStringKeepsItsToken() {
        assertTrue(protectAs("00412", ValueKind.TEXT).startsWith("RG{N"));
    }

    @Test
    @DisplayName("Something that is not a plain number is left untouched")
    void notAPlainNumberIsLeftUntouched() {
        assertEquals("NaN", protectAs("NaN", ValueKind.NUMBER));
        assertEquals("Infinity", protectAs("Infinity", ValueKind.NUMBER));
    }

    // ---------------------------------------------------------------- end to end

    @Test
    @DisplayName("Dates and numbers keep their JSON type through a payload round trip")
    void payloadKeepsDatesAndNumbers() throws Exception {
        String json = "{\"birth\":\"1980-05-12\",\"since\":\"12.05.1980\",\"age\":42,\"balance\":-1234.56,"
                + "\"account\":\"00412\"}";
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        String protectedJson = transformer.transform(json, headers, params, typedConfig("Protect"));
        DocumentContext document = JsonPath.parse(protectedJson);

        String birth = document.read("$.birth");
        assertTrue(DateValues.parse(birth).date().getYear() >= 3000, protectedJson);
        String since = document.read("$.since");
        assertTrue(since.matches("\\d{2}\\.\\d{2}\\.\\d{4}"), protectedJson);
        assertTrue(document.read("$.age") instanceof Number, protectedJson);
        assertTrue(document.read("$.balance") instanceof Number, protectedJson);
        assertTrue(document.<String>read("$.account").startsWith("RG{N"), protectedJson);

        String clearJson = transformer.transform(protectedJson, headers, params, typedConfig("Unprotect"));
        assertEquals(json, clearJson);
    }

    @Test
    @DisplayName("A date in a header round trips and stays a date")
    void headerDateRoundTrips() throws Exception {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("X-Birth", "1980-05-12");
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

        EndPointTransformConfig protect = typedConfig("Protect");
        protect.setHeaderTransformConfigs(Set.of(header()));
        transformer.transform("{}", headers, params, protect);
        String protectedDate = headers.getFirst("X-Birth");
        assertTrue(DateValues.parse(protectedDate).date().getYear() >= 3000, protectedDate);

        EndPointTransformConfig unprotect = typedConfig("Unprotect");
        unprotect.setHeaderTransformConfigs(Set.of(header()));
        transformer.transform("{}", headers, params, unprotect);
        assertEquals("1980-05-12", headers.getFirst("X-Birth"));
    }

    private static HeaderTransformConfig header() {
        HeaderTransformConfig header = new HeaderTransformConfig();
        header.setName("X-Birth");
        header.setRpsClassName("Person");
        header.setRpsPropertyName("Birth");
        return header;
    }

    private static EndPointTransformConfig typedConfig(String action) {
        EndPointTransformConfig cfg = new EndPointTransformConfig();
        cfg.setEndpointTransformWhen("Protect".equals(action) ? "BEFORE" : "AFTER");
        Map<String, String> evidences = new HashMap<>();
        evidences.put("Action", action);
        cfg.setProcessingContextEvidences(evidences);
        cfg.setEntityTransformConfigs(Set.of(
                entity("$.birth", "Birth", null),
                entity("$.since", "Since", null),
                entity("$.age", "Age", null),
                entity("$.balance", "Balance", null),
                entity("$.account", "Account", null)));
        return cfg;
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
