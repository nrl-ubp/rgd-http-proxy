package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how {@link RPSEndPointTransformer} reads and writes JSON paths.
 * <p>
 * {@code DocumentContext.read()} does not always return a list: a definite path returns the raw
 * value (String, number, null) and an absent path throws. These tests pin that behaviour down and
 * guard the write-back against writing a value to the wrong element.
 */
class RPSEndPointTransformerJsonPathTest {

    private final RPSEndPointTransformer transformer = new RPSEndPointTransformer();

    private static Map<String, EntityTransformConfig> config(String... jsonPaths) {
        return configWithRegex(null, jsonPaths);
    }

    private static Map<String, EntityTransformConfig> configWithRegex(String extractRegex, String... jsonPaths) {
        Map<String, EntityTransformConfig> configs = new LinkedHashMap<>();
        for (String jsonPath : jsonPaths) {
            EntityTransformConfig cfg = new EntityTransformConfig();
            cfg.setJsonPath(jsonPath);
            cfg.setRpsClassName("Person");
            cfg.setRpsPropertyName("ShortString");
            cfg.setExtractRegex(extractRegex);
            configs.put(jsonPath, cfg);
        }
        return configs;
    }

    /**
     * Simulates the RPS engine: every value comes back prefixed, so we can tell in the resulting
     * document which values were actually transformed and where they landed.
     */
    private static void fakeTransform(Map<String, JsonPathValue> rpsValues) {
        rpsValues.values().forEach(value -> value.plan().rpsValues()
                .forEach(rpsValue -> rpsValue.setTransformed("TOK_" + rpsValue.getOriginal())));
    }

    /**
     * Runs the full read / transform / write round trip and returns the resulting JSON.
     */
    private String roundTrip(String json, String... jsonPaths) throws RPSTransformException {
        return roundTripWith(json, "Protect", null, jsonPaths);
    }

    /**
     * Round trip using the given action and extract regex, so the segmentation can be exercised.
     */
    private String roundTripWith(String json, String action, String extractRegex, String... jsonPaths)
            throws RPSTransformException {
        DocumentContext documentContext = JsonPath.parse(json);
        Map<String, JsonPathValue> rpsValues =
                transformer.getRPSValuesFromBody(documentContext, configWithRegex(extractRegex, jsonPaths), action);
        fakeTransform(rpsValues);
        transformer.setRPSValuesToBody(documentContext, rpsValues);
        return documentContext.jsonString();
    }

    /**
     * Same round trip, but the engine returns the transformed value given by the caller.
     */
    private String roundTripReturning(String json, String jsonPath, String transformed)
            throws RPSTransformException {
        DocumentContext documentContext = JsonPath.parse(json);
        Map<String, JsonPathValue> rpsValues =
                transformer.getRPSValuesFromBody(documentContext, config(jsonPath), "Protect");
        rpsValues.values().forEach(value ->
                value.plan().rpsValues().forEach(rpsValue -> rpsValue.setTransformed(transformed)));
        transformer.setRPSValuesToBody(documentContext, rpsValues);
        return documentContext.jsonString();
    }

    @Test
    @DisplayName("A definite path returns a single String, not a list")
    void definiteStringPath() throws RPSTransformException {
        assertEquals("{\"name\":\"TOK_Bob\"}", roundTrip("{\"name\":\"Bob\"}", "$.name"));
    }

    @Test
    @DisplayName("A number is handed to RPS as text but flagged as numeric")
    void definiteNumericPath() throws RPSTransformException {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"age\":42}"), config("$.age"), null);

        assertEquals(1, values.size());
        JsonPathValue value = values.values().iterator().next();
        assertEquals("42", value.plan().rpsValues().getFirst().getOriginal());
        assertTrue(value.numeric(), "a JSON number must be flagged as numeric");
    }

    @Test
    @DisplayName("A tokenized number stays a JSON number, not a string")
    void numericValueStaysANumber() throws RPSTransformException {
        assertEquals("{\"age\":8371}", roundTripReturning("{\"age\":42}", "$.age", "8371"));
    }

    @Test
    @DisplayName("A decimal keeps its exact value, without floating point drift")
    void decimalKeepsItsExactValue() throws RPSTransformException {
        assertEquals("{\"amount\":1234.5678}",
                roundTripReturning("{\"amount\":4.25}", "$.amount", "1234.5678"));
    }

    @Test
    @DisplayName("A decimal token keeps its trailing zeros")
    void decimalKeepsTrailingZeros() throws RPSTransformException {
        assertEquals("{\"amount\":12.340}",
                roundTripReturning("{\"amount\":4.25}", "$.amount", "12.340"));
    }

    @Test
    @DisplayName("A value wider than a long is not truncated")
    void veryLargeNumberIsNotTruncated() throws RPSTransformException {
        String huge = "123456789012345678901234567890";
        assertEquals("{\"id\":" + huge + "}", roundTripReturning("{\"id\":42}", "$.id", huge));
    }

    @Test
    @DisplayName("A negative token stays a negative JSON number")
    void negativeNumber() throws RPSTransformException {
        assertEquals("{\"balance\":-99}", roundTripReturning("{\"balance\":7}", "$.balance", "-99"));
    }

    @Test
    @DisplayName("A number read from a nested array is written back as a number")
    void numbersInsideAnArray() throws RPSTransformException {
        DocumentContext documentContext = JsonPath.parse("{\"accounts\":[{\"no\":11},{\"no\":22}]}");
        Map<String, JsonPathValue> values =
                transformer.getRPSValuesFromBody(documentContext, config("$.accounts[*].no"), null);
        values.values().forEach(v -> v.plan().rpsValues()
                .forEach(rpsValue -> rpsValue.setTransformed("99" + rpsValue.getOriginal())));
        transformer.setRPSValuesToBody(documentContext, values);

        assertEquals("{\"accounts\":[{\"no\":9911},{\"no\":9922}]}", documentContext.jsonString());
    }

    @Test
    @DisplayName("A token with leading zeros loses them: JSON numbers cannot carry a leading zero")
    void leadingZerosAreDropped() throws RPSTransformException {
        assertEquals("{\"code\":7}", roundTripReturning("{\"code\":42}", "$.code", "007"));
    }

    @Test
    @DisplayName("A non numeric token for a numeric field falls back to a JSON string")
    void nonNumericTokenFallsBackToString() throws RPSTransformException {
        assertEquals("{\"age\":\"TOK_42\"}", roundTrip("{\"age\":42}", "$.age"));
    }

    @Test
    @DisplayName("Unprotect direction: a numeric token detokenizes back to a JSON number")
    void unprotectKeepsTheNumber() throws RPSTransformException {
        // the payload holds a numeric token, the engine gives back the clear numeric value
        assertEquals("{\"age\":42}", roundTripReturning("{\"age\":8371}", "$.age", "42"));
    }

    @Test
    @DisplayName("A string field is never turned into a number, even when the token is numeric")
    void stringFieldStaysAString() throws RPSTransformException {
        assertEquals("{\"ref\":\"12345\"}", roundTripReturning("{\"ref\":\"abc\"}", "$.ref", "12345"));
    }

    @Test
    @DisplayName("A boolean value is tokenized as a String too")
    void definiteBooleanPath() throws RPSTransformException {
        assertEquals("{\"flag\":\"TOK_true\"}", roundTrip("{\"flag\":true}", "$.flag"));
    }

    @Test
    @DisplayName("A path absent from the document is skipped instead of throwing")
    void missingPathIsSkipped() throws RPSTransformException {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":\"Bob\"}"), config("$.missing"), null);

        assertTrue(values.isEmpty());
    }

    @Test
    @DisplayName("An absent path does not prevent the other paths from being transformed")
    void missingPathDoesNotStopTheOthers() throws RPSTransformException {
        assertEquals("{\"name\":\"TOK_Bob\"}",
                roundTrip("{\"name\":\"Bob\"}", "$.missing", "$.name"));
    }

    @Test
    @DisplayName("An indefinite path whose parent is absent is skipped instead of throwing")
    void missingParentIsSkipped() throws RPSTransformException {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":\"Bob\"}"), config("$.persons[*].name"), null);

        assertTrue(values.isEmpty());
    }

    @Test
    @DisplayName("A null value is skipped, nothing to protect")
    void nullValueIsSkipped() throws RPSTransformException {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":null}"), config("$.name"), null);

        assertTrue(values.isEmpty());
        assertEquals("{\"name\":null}", roundTrip("{\"name\":null}", "$.name"));
    }

    @Test
    @DisplayName("An indefinite path transforms every match")
    void indefinitePath() throws RPSTransformException {
        assertEquals("{\"persons\":[{\"name\":\"TOK_x\"},{\"name\":\"TOK_y\"}]}",
                roundTrip("{\"persons\":[{\"name\":\"x\"},{\"name\":\"y\"}]}", "$.persons[*].name"));
    }

    @Test
    @DisplayName("An indefinite path matching nothing yields no value and no error")
    void indefinitePathWithoutMatch() throws RPSTransformException {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"persons\":[]}"), config("$.persons[*].name"), null);

        assertTrue(values.isEmpty());
    }

    @Test
    @DisplayName("Values are written back to the element they were read from, not to index 0")
    void writeBackKeepsMatchesAligned() throws RPSTransformException {
        // the first element carries no "name": matches come from index 1 and 2.
        // Rebuilding the path as persons[0].name used to write to the wrong element and throw,
        // leaving the whole payload untokenized.
        String json = "{\"persons\":[{\"other\":1},{\"name\":\"x\"},{\"name\":\"y\"}]}";

        assertEquals("{\"persons\":[{\"other\":1},{\"name\":\"TOK_x\"},{\"name\":\"TOK_y\"}]}",
                roundTrip(json, "$.persons[*].name"));
    }

    @Test
    @DisplayName("A deep scan writes every match back to its own location")
    void deepScanWriteBack() throws RPSTransformException {
        String json = "{\"a\":{\"name\":\"1\"},\"b\":{\"c\":{\"name\":\"2\"}},\"name\":\"0\"}";

        assertEquals("{\"a\":{\"name\":\"TOK_1\"},\"b\":{\"c\":{\"name\":\"TOK_2\"}},\"name\":\"TOK_0\"}",
                roundTrip(json, "$..name"));
    }

    @Test
    @DisplayName("Mixed definite, indefinite and absent paths are all handled in one pass")
    void mixedPaths() throws RPSTransformException {
        String json = "{\"name\":\"Bob\",\"age\":42,\"persons\":[{\"other\":1},{\"n\":\"x\"}]}";

        assertEquals("{\"name\":\"TOK_Bob\",\"age\":\"TOK_42\",\"persons\":[{\"other\":1},{\"n\":\"TOK_x\"}]}",
                roundTrip(json, "$.name", "$.age", "$.persons[*].n", "$.absent"));
    }

    // ---------------------------------------------------------------------------------------
    // extract-regex : word by word tokenization keeping the original formatting
    // ---------------------------------------------------------------------------------------

    /** The regex carried by most properties of the WDX1 swagger, matching runs of word chars. */
    private static final String WORD_REGEX = "(?:\\w(?<!_)|~)+";

    @Test
    @DisplayName("Protect with an extract regex tokenizes word by word and keeps the separators")
    void protectSplitsWordByWord() throws RPSTransformException {
        assertEquals("{\"name\":\"TOK_Jean-TOK_Claude TOK_DUSSE\"}",
                roundTripWith("{\"name\":\"Jean-Claude DUSSE\"}", "Protect", WORD_REGEX, "$.name"));
    }

    @Test
    @DisplayName("Each word becomes its own RPS value, the separators are never sent to the engine")
    void protectSendsOneValuePerWord() throws RPSTransformException {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":\"Jean-Claude DUSSE\"}"),
                configWithRegex(WORD_REGEX, "$.name"), "Protect");

        assertEquals(1, values.size());
        assertEquals(List.of("Jean", "Claude", "DUSSE"),
                values.values().iterator().next().plan().rpsValues().stream()
                        .map(RPSValue::getOriginal).toList());
    }

    @Test
    @DisplayName("Leading and trailing spacing is preserved when reassembling")
    void protectPreservesSpacing() throws RPSTransformException {
        assertEquals("{\"name\":\"  TOK_spaced  TOK_out  \"}",
                roundTripWith("{\"name\":\"  spaced  out  \"}", "Protect", WORD_REGEX, "$.name"));
    }

    @Test
    @DisplayName("Without an extract regex the whole value is tokenized, as before")
    void protectWithoutRegexTokenizesTheWholeValue() throws RPSTransformException {
        assertEquals("{\"name\":\"TOK_Jean-Claude DUSSE\"}",
                roundTripWith("{\"name\":\"Jean-Claude DUSSE\"}", "Protect", null, "$.name"));
    }

    @Test
    @DisplayName("A value the extract regex does not match at all is left untouched")
    void protectWithoutAnyMatchLeavesTheValueUntouched() throws RPSTransformException {
        assertEquals("{\"name\":\"---\"}",
                roundTripWith("{\"name\":\"---\"}", "Protect", WORD_REGEX, "$.name"));
    }

    @Test
    @DisplayName("Unprotect replaces each token and restores the original formatting")
    void unprotectReplacesEachToken() throws RPSTransformException {
        DocumentContext documentContext = JsonPath.parse("{\"name\":\"RG{aaa}-RG{bbb} RG{ccc}\"}");
        Map<String, JsonPathValue> values =
                transformer.getRPSValuesFromBody(documentContext, config("$.name"), "Unprotect");

        assertEquals(List.of("RG{aaa}", "RG{bbb}", "RG{ccc}"),
                values.values().iterator().next().plan().rpsValues().stream()
                        .map(RPSValue::getOriginal).toList());

        values.values().iterator().next().plan().rpsValues().get(0).setTransformed("Jean");
        values.values().iterator().next().plan().rpsValues().get(1).setTransformed("Claude");
        values.values().iterator().next().plan().rpsValues().get(2).setTransformed("DUSSE");
        transformer.setRPSValuesToBody(documentContext, values);

        assertEquals("{\"name\":\"Jean-Claude DUSSE\"}", documentContext.jsonString());
    }

    @Test
    @DisplayName("Unprotect splits tokens even without an extract regex configured")
    void unprotectSplitsWithoutExtractRegex() throws RPSTransformException {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":\"RG{aaa} RG{bbb}\"}"), configWithRegex(null, "$.name"), "Unprotect");

        assertEquals(2, values.values().iterator().next().plan().rpsValues().size());
    }

    @Test
    @DisplayName("Unprotect of a value holding no token sends nothing to the engine")
    void unprotectWithoutTokenSendsNothing() throws RPSTransformException {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":\"no tokens here\"}"), config("$.name"), "Unprotect");

        assertTrue(values.isEmpty(), "a value without any token must not reach the engine");
    }

    @Test
    @DisplayName("A number is never segmented on protect, even with an extract regex")
    void numbersAreNeverSegmentedOnProtect() throws RPSTransformException {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"account\":123456}"), configWithRegex(WORD_REGEX, "$.account"), "Protect");

        assertEquals(1, values.values().iterator().next().plan().rpsValues().size());
        assertEquals("123456",
                values.values().iterator().next().plan().rpsValues().getFirst().getOriginal());
    }

    @Test
    @DisplayName("A numeric token is still detokenized on unprotect, it holds no RG{} marker")
    void numbersAreNeverSegmentedOnUnprotect() throws RPSTransformException {
        DocumentContext documentContext = JsonPath.parse("{\"account\":8371}");
        Map<String, JsonPathValue> values =
                transformer.getRPSValuesFromBody(documentContext, config("$.account"), "Unprotect");

        assertEquals(1, values.size(), "a numeric token must still be sent to the engine");
        values.values().iterator().next().plan().rpsValues().getFirst().setTransformed("123456");
        transformer.setRPSValuesToBody(documentContext, values);

        assertEquals("{\"account\":123456}", documentContext.jsonString());
    }

    @Test
    @DisplayName("An invalid extract regex fails the transformation")
    void invalidExtractRegexIsRejected() {
        assertThrows(RPSTransformException.class, () -> transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":\"Bob\"}"), configWithRegex("[unclosed", "$.name"), "Protect"));
    }

    @Test
    @DisplayName("An unknown action transforms the whole value, as before")
    void unknownActionTransformsTheWholeValue() throws RPSTransformException {
        assertEquals("{\"name\":\"TOK_Jean-Claude DUSSE\"}",
                roundTripWith("{\"name\":\"Jean-Claude DUSSE\"}", "Something", WORD_REGEX, "$.name"));
    }
}
