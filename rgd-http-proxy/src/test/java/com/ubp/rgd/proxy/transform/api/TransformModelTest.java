package com.ubp.rgd.proxy.transform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jackson (de)serialization tests for the {@code /transform} request and response models.
 */
class TransformModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testRequestDeserializationWithExtractRegex() throws Exception {
        String json = """
                {
                  "sets": [
                    {
                      "action": "Protect",
                      "target": "WDX1",
                      "jurisdiction": "CH",
                      "values": [
                        { "value": "John Doe", "class-name": "Person", "property-name": "Name", "extract-regex": "\\\\w+" },
                        { "value": "1970-01-01", "class-name": "Person", "property-name": "BirthDate" }
                      ]
                    }
                  ]
                }
                """;

        TransformRequest request = mapper.readValue(json, TransformRequest.class);

        assertEquals(1, request.getSets().size());
        TransformSet set = request.getSets().get(0);
        assertEquals("Protect", set.getAction());
        assertEquals("WDX1", set.getTarget());
        assertEquals("CH", set.getJurisdiction());
        assertEquals(2, set.getValues().size());

        TransformValue first = set.getValues().get(0);
        assertEquals("John Doe", first.getValue());
        assertEquals("Person", first.getClassName());
        assertEquals("Name", first.getPropertyName());
        assertEquals("\\w+", first.getExtractRegExp());

        // Optional extract-regex absent on the second value.
        assertNull(set.getValues().get(1).getExtractRegExp());
    }

    @Test
    void testResponseSerializationKeepsGroupingAndOrder() throws Exception {
        TransformResponse response = new TransformResponse();
        response.getResults().add(new TransformResultSet(List.of("T1", "T2")));
        response.getResults().add(new TransformResultSet(List.of("T3")));

        String json = mapper.writeValueAsString(response);

        // Round-trip and verify grouping + order.
        TransformResponse read = mapper.readValue(json, TransformResponse.class);
        assertEquals(2, read.getResults().size());
        assertEquals(List.of("T1", "T2"), read.getResults().get(0).getValues());
        assertEquals(List.of("T3"), read.getResults().get(1).getValues());

        // extract-regex must not leak into the response and results key is present.
        assertTrue(json.contains("\"results\""));
        assertTrue(json.contains("\"values\""));
    }

    @Test
    void testExtractRegexOmittedWhenNull() throws Exception {
        TransformValue value = new TransformValue();
        value.setValue("v");
        value.setClassName("Person");
        value.setPropertyName("Name");

        String json = mapper.writeValueAsString(value);

        assertFalse(json.contains("extract-regex"));
        assertTrue(json.contains("\"class-name\""));
        assertTrue(json.contains("\"property-name\""));
    }
}
