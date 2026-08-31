package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightSqlMappingConfigTest {

    private static final String JSON = """
            {
              "right-context": { "Target": "WDX1", "Module": "RoseGarden", "Right": "Transform" },
              "processing-context": { "Action": "Unprotect", "Target": "WDX1" },
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

        assertEquals("WDX1", config.getRightContextEvidences().get("Target"));
        assertEquals("Unprotect", config.getProcessingContextEvidences().get("Action"));
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
    void shouldReturnNullWhenNoMappingIsConfigured() {
        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        config.setColumnMappings(List.of());

        assertNull(config.findMapping("PERSON", "FIRST_NAME"));
    }
}
