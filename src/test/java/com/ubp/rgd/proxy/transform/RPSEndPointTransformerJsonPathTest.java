package com.ubp.rgd.proxy.transform;

import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Map<String, EntityTransformConfig> configs = new LinkedHashMap<>();
        for (String jsonPath : jsonPaths) {
            EntityTransformConfig cfg = new EntityTransformConfig();
            cfg.setJsonPath(jsonPath);
            cfg.setRpsClassName("Person");
            cfg.setRpsPropertyName("ShortString");
            configs.put(jsonPath, cfg);
        }
        return configs;
    }

    /**
     * Simulates the RPS engine: every value comes back prefixed, so we can tell in the resulting
     * document which values were actually transformed and where they landed.
     */
    private static void fakeTransform(Map<String, JsonPathValue> rpsValues) {
        rpsValues.values().forEach(value ->
                value.rpsValue().setTransformed("TOK_" + value.rpsValue().getOriginal()));
    }

    /**
     * Runs the full read / transform / write round trip and returns the resulting JSON.
     */
    private String roundTrip(String json, String... jsonPaths) {
        DocumentContext documentContext = JsonPath.parse(json);
        Map<String, JsonPathValue> rpsValues = transformer.getRPSValuesFromBody(documentContext, config(jsonPaths));
        fakeTransform(rpsValues);
        transformer.setRPSValuesToBody(documentContext, rpsValues);
        return documentContext.jsonString();
    }

    /**
     * Same round trip, but the engine returns the transformed value given by the caller.
     */
    private String roundTripReturning(String json, String jsonPath, String transformed) {
        DocumentContext documentContext = JsonPath.parse(json);
        Map<String, JsonPathValue> rpsValues = transformer.getRPSValuesFromBody(documentContext, config(jsonPath));
        rpsValues.values().forEach(value -> value.rpsValue().setTransformed(transformed));
        transformer.setRPSValuesToBody(documentContext, rpsValues);
        return documentContext.jsonString();
    }

    @Test
    @DisplayName("A definite path returns a single String, not a list")
    void definiteStringPath() {
        assertEquals("{\"name\":\"TOK_Bob\"}", roundTrip("{\"name\":\"Bob\"}", "$.name"));
    }

    @Test
    @DisplayName("A number is handed to RPS as text but flagged as numeric")
    void definiteNumericPath() {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"age\":42}"), config("$.age"));

        assertEquals(1, values.size());
        JsonPathValue value = values.values().iterator().next();
        assertEquals("42", value.rpsValue().getOriginal());
        assertTrue(value.numeric(), "a JSON number must be flagged as numeric");
    }

    @Test
    @DisplayName("A tokenized number stays a JSON number, not a string")
    void numericValueStaysANumber() {
        assertEquals("{\"age\":8371}", roundTripReturning("{\"age\":42}", "$.age", "8371"));
    }

    @Test
    @DisplayName("A decimal keeps its exact value, without floating point drift")
    void decimalKeepsItsExactValue() {
        assertEquals("{\"amount\":1234.5678}",
                roundTripReturning("{\"amount\":4.25}", "$.amount", "1234.5678"));
    }

    @Test
    @DisplayName("A decimal token keeps its trailing zeros")
    void decimalKeepsTrailingZeros() {
        assertEquals("{\"amount\":12.340}",
                roundTripReturning("{\"amount\":4.25}", "$.amount", "12.340"));
    }

    @Test
    @DisplayName("A value wider than a long is not truncated")
    void veryLargeNumberIsNotTruncated() {
        String huge = "123456789012345678901234567890";
        assertEquals("{\"id\":" + huge + "}", roundTripReturning("{\"id\":42}", "$.id", huge));
    }

    @Test
    @DisplayName("A negative token stays a negative JSON number")
    void negativeNumber() {
        assertEquals("{\"balance\":-99}", roundTripReturning("{\"balance\":7}", "$.balance", "-99"));
    }

    @Test
    @DisplayName("A number read from a nested array is written back as a number")
    void numbersInsideAnArray() {
        DocumentContext documentContext = JsonPath.parse("{\"accounts\":[{\"no\":11},{\"no\":22}]}");
        Map<String, JsonPathValue> values =
                transformer.getRPSValuesFromBody(documentContext, config("$.accounts[*].no"));
        values.values().forEach(v -> v.rpsValue().setTransformed("99" + v.rpsValue().getOriginal()));
        transformer.setRPSValuesToBody(documentContext, values);

        assertEquals("{\"accounts\":[{\"no\":9911},{\"no\":9922}]}", documentContext.jsonString());
    }

    @Test
    @DisplayName("A token with leading zeros loses them: JSON numbers cannot carry a leading zero")
    void leadingZerosAreDropped() {
        assertEquals("{\"code\":7}", roundTripReturning("{\"code\":42}", "$.code", "007"));
    }

    @Test
    @DisplayName("A non numeric token for a numeric field falls back to a JSON string")
    void nonNumericTokenFallsBackToString() {
        assertEquals("{\"age\":\"TOK_42\"}", roundTrip("{\"age\":42}", "$.age"));
    }

    @Test
    @DisplayName("Unprotect direction: a numeric token detokenizes back to a JSON number")
    void unprotectKeepsTheNumber() {
        // the payload holds a numeric token, the engine gives back the clear numeric value
        assertEquals("{\"age\":42}", roundTripReturning("{\"age\":8371}", "$.age", "42"));
    }

    @Test
    @DisplayName("A string field is never turned into a number, even when the token is numeric")
    void stringFieldStaysAString() {
        assertEquals("{\"ref\":\"12345\"}", roundTripReturning("{\"ref\":\"abc\"}", "$.ref", "12345"));
    }

    @Test
    @DisplayName("A boolean value is tokenized as a String too")
    void definiteBooleanPath() {
        assertEquals("{\"flag\":\"TOK_true\"}", roundTrip("{\"flag\":true}", "$.flag"));
    }

    @Test
    @DisplayName("A path absent from the document is skipped instead of throwing")
    void missingPathIsSkipped() {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":\"Bob\"}"), config("$.missing"));

        assertTrue(values.isEmpty());
    }

    @Test
    @DisplayName("An absent path does not prevent the other paths from being transformed")
    void missingPathDoesNotStopTheOthers() {
        assertEquals("{\"name\":\"TOK_Bob\"}",
                roundTrip("{\"name\":\"Bob\"}", "$.missing", "$.name"));
    }

    @Test
    @DisplayName("An indefinite path whose parent is absent is skipped instead of throwing")
    void missingParentIsSkipped() {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":\"Bob\"}"), config("$.persons[*].name"));

        assertTrue(values.isEmpty());
    }

    @Test
    @DisplayName("A null value is skipped, nothing to protect")
    void nullValueIsSkipped() {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":null}"), config("$.name"));

        assertTrue(values.isEmpty());
        assertEquals("{\"name\":null}", roundTrip("{\"name\":null}", "$.name"));
    }

    @Test
    @DisplayName("An indefinite path transforms every match")
    void indefinitePath() {
        assertEquals("{\"persons\":[{\"name\":\"TOK_x\"},{\"name\":\"TOK_y\"}]}",
                roundTrip("{\"persons\":[{\"name\":\"x\"},{\"name\":\"y\"}]}", "$.persons[*].name"));
    }

    @Test
    @DisplayName("An indefinite path matching nothing yields no value and no error")
    void indefinitePathWithoutMatch() {
        Map<String, JsonPathValue> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"persons\":[]}"), config("$.persons[*].name"));

        assertTrue(values.isEmpty());
    }

    @Test
    @DisplayName("Values are written back to the element they were read from, not to index 0")
    void writeBackKeepsMatchesAligned() {
        // the first element carries no "name": matches come from index 1 and 2.
        // Rebuilding the path as persons[0].name used to write to the wrong element and throw,
        // leaving the whole payload untokenized.
        String json = "{\"persons\":[{\"other\":1},{\"name\":\"x\"},{\"name\":\"y\"}]}";

        assertEquals("{\"persons\":[{\"other\":1},{\"name\":\"TOK_x\"},{\"name\":\"TOK_y\"}]}",
                roundTrip(json, "$.persons[*].name"));
    }

    @Test
    @DisplayName("A deep scan writes every match back to its own location")
    void deepScanWriteBack() {
        String json = "{\"a\":{\"name\":\"1\"},\"b\":{\"c\":{\"name\":\"2\"}},\"name\":\"0\"}";

        assertEquals("{\"a\":{\"name\":\"TOK_1\"},\"b\":{\"c\":{\"name\":\"TOK_2\"}},\"name\":\"TOK_0\"}",
                roundTrip(json, "$..name"));
    }

    @Test
    @DisplayName("Mixed definite, indefinite and absent paths are all handled in one pass")
    void mixedPaths() {
        String json = "{\"name\":\"Bob\",\"age\":42,\"persons\":[{\"other\":1},{\"n\":\"x\"}]}";

        assertEquals("{\"name\":\"TOK_Bob\",\"age\":\"TOK_42\",\"persons\":[{\"other\":1},{\"n\":\"TOK_x\"}]}",
                roundTrip(json, "$.name", "$.age", "$.persons[*].n", "$.absent"));
    }
}
