package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightSqlMappingConfigTest {

    private static final String JSON = """
            {
              "before": {
                "right-context": { "Target": "WDX1", "Right": "Transform" },
                "processing-context": { "Action": "Protect", "Target": "WDX1" }
              },
              "after": {
                "right-context": { "Target": "WDX1", "Module": "RoseGarden", "Right": "Transform" },
                "processing-context": { "Action": "Unprotect", "Target": "WDX1" }
              },
              "column-mappings": [
                { "table": "PERSON", "column": "FIRST_NAME",
                  "rps-class-name": "Person", "rps-property-name": "shortString" },
                { "table": "*", "column": "EMAIL",
                  "rps-class-name": "Person", "rps-property-name": "email" },
                { "column": "IBAN",
                  "rps-class-name": "Account", "rps-property-name": "iban" }
              ]
            }
            """;

    private FlightSqlMappingConfig load() throws Exception {
        return new ObjectMapper().readValue(JSON, FlightSqlMappingConfig.class);
    }

    @Test
    void shouldDeserializeTheWholeConfiguration() throws Exception {
        FlightSqlMappingConfig config = load();

        assertEquals("WDX1", config.getAfter().getRightContextEvidences().get("Target"));
        assertEquals("RoseGarden", config.getAfter().getRightContextEvidences().get("Module"));
        assertEquals("Unprotect", config.getAfter().getProcessingContextEvidences().get("Action"));
        assertEquals("Protect", config.getBefore().getProcessingContextEvidences().get("Action"));
        assertEquals("Transform", config.getBefore().getRightContextEvidences().get("Right"));
        assertEquals(3, config.getColumnMappings().size());
        assertEquals("Person", config.getColumnMappings().get(0).getRpsClassName());
        assertEquals("shortString", config.getColumnMappings().get(0).getRpsPropertyName());
    }

    @Test
    void shouldFindAnExactTableAndColumnMappingIgnoringCase() throws Exception {
        FlightSqlColumnMapping mapping = load().findMapping("person", "first_name");

        assertNotNull(mapping);
        assertEquals("shortString", mapping.getRpsPropertyName());
    }

    @Test
    void shouldFallBackToTheWildcardTableMapping() throws Exception {
        FlightSqlMappingConfig config = load();

        assertEquals("email", config.findMapping("CUSTOMER", "EMAIL").getRpsPropertyName());
        assertEquals("email", config.findMapping(null, "EMAIL").getRpsPropertyName());
    }

    @Test
    void shouldTreatAMissingTableAsAWildcard() throws Exception {
        FlightSqlMappingConfig config = load();
        FlightSqlColumnMapping mapping = config.findMapping("ANY_TABLE", "IBAN");

        assertNotNull(mapping);
        assertEquals("Account", mapping.getRpsClassName());
        assertTrue(config.getColumnMappings().get(2).isWildcardTable());
    }

    @Test
    void shouldReturnNullWhenTheColumnIsNotMapped() throws Exception {
        FlightSqlMappingConfig config = load();

        assertNull(config.findMapping("PERSON", "AGE"));
        assertNull(config.findMapping("PERSON", null));
        // FIRST_NAME is only mapped for the PERSON table.
        assertNull(config.findMapping("CUSTOMER", "FIRST_NAME"));
    }

    @Test
    void shouldConsiderAPhaseActiveByDefault() throws Exception {
        FlightSqlMappingConfig config = load();

        // The file declares no "active" flag: a configured phase is on unless it says otherwise.
        assertTrue(config.getAfter().isActive());
        assertTrue(config.getBefore().isActive());
        assertTrue(config.isAfterActive());
    }

    @Test
    void shouldExposeAnActiveEmptyAfterPhaseByDefault() {
        FlightSqlMappingConfig config = new FlightSqlMappingConfig();

        assertNotNull(config.getAfter());
        assertTrue(config.isAfterActive());
        assertTrue(config.getAfter().getRightContextEvidences().isEmpty());
        assertTrue(config.getAfter().getProcessingContextEvidences().isEmpty());
    }

    @Test
    void shouldReadAnExplicitlyInactivePhase() throws Exception {
        String json = """
                {
                  "after": { "active": false,
                    "processing-context": { "Action": "Unprotect" } },
                  "column-mappings": []
                }
                """;
        FlightSqlMappingConfig config = new ObjectMapper().readValue(json, FlightSqlMappingConfig.class);

        assertFalse(config.getAfter().isActive());
        assertFalse(config.isAfterActive());
    }

    @Test
    void shouldIgnoreUnknownPropertiesOfAPhase() throws Exception {
        String json = """
                {
                  "after": { "active": true, "some-future-key": "value",
                    "processing-context": { "Action": "Unprotect" } }
                }
                """;
        FlightSqlMappingConfig config = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(json, FlightSqlMappingConfig.class);

        assertEquals("Unprotect", config.getAfter().getProcessingContextEvidences().get("Action"));
    }

    @Test
    void shouldLookUpAnActionIgnoringCase() throws Exception {
        assertEquals("Unprotect", load().getAfter().getAction());
    }

    @Test
    void shouldReturnNullWhenNoMappingIsConfigured() {
        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        config.setColumnMappings(List.of());

        assertNull(config.findMapping("PERSON", "FIRST_NAME"));
    }
}
