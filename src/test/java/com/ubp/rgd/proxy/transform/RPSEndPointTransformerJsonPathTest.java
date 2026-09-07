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
    private static void fakeTransform(Map<String, RPSValue[]> rpsValues) {
        rpsValues.values().forEach(values -> {
            for (RPSValue value : values) {
                value.setTransformed("TOK_" + value.getOriginal());
            }
        });
    }

    /**
     * Runs the full read / transform / write round trip and returns the resulting JSON.
     */
    private String roundTrip(String json, String... jsonPaths) {
        DocumentContext documentContext = JsonPath.parse(json);
        Map<String, RPSValue[]> rpsValues = transformer.getRPSValuesFromBody(documentContext, config(jsonPaths));
        fakeTransform(rpsValues);
        transformer.setRPSValuesToBody(documentContext, rpsValues);
        return documentContext.jsonString();
    }

    @Test
    @DisplayName("A definite path returns a single String, not a list")
    void definiteStringPath() {
        assertEquals("{\"name\":\"TOK_Bob\"}", roundTrip("{\"name\":\"Bob\"}", "$.name"));
    }

    @Test
    @DisplayName("A definite path pointing to a number is tokenized as a String")
    void definiteNumericPath() {
        Map<String, RPSValue[]> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"age\":42}"), config("$.age"));

        assertEquals(1, values.size());
        assertEquals("42", values.values().iterator().next()[0].getOriginal());

        // the value is written back as a JSON string: the type changes from number to string
        assertEquals("{\"age\":\"TOK_42\"}", roundTrip("{\"age\":42}", "$.age"));
    }

    @Test
    @DisplayName("A boolean value is tokenized as a String too")
    void definiteBooleanPath() {
        assertEquals("{\"flag\":\"TOK_true\"}", roundTrip("{\"flag\":true}", "$.flag"));
    }

    @Test
    @DisplayName("A path absent from the document is skipped instead of throwing")
    void missingPathIsSkipped() {
        Map<String, RPSValue[]> values = transformer.getRPSValuesFromBody(
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
        Map<String, RPSValue[]> values = transformer.getRPSValuesFromBody(
                JsonPath.parse("{\"name\":\"Bob\"}"), config("$.persons[*].name"));

        assertTrue(values.isEmpty());
    }

    @Test
    @DisplayName("A null value is skipped, nothing to protect")
    void nullValueIsSkipped() {
        Map<String, RPSValue[]> values = transformer.getRPSValuesFromBody(
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
        Map<String, RPSValue[]> values = transformer.getRPSValuesFromBody(
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
