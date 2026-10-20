package com.ubp.rgd.proxy.services;

import com.ubp.rgd.proxy.transform.config.FlightSqlMappingConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link FlightSqlMappingConfigProvider} reads the mapping file: the phases it understands, the
 * unknown keys it tolerates, and the misconfigurations it refuses to start with.
 */
class FlightSqlMappingConfigProviderTest {

    @TempDir
    Path tempDir;

    private FlightSqlMappingConfigProvider providerFor(String fileName, boolean enabled) {
        FlightSqlMappingConfigProvider provider = new FlightSqlMappingConfigProvider();
        provider.mappingConfigFile = tempDir.resolve(fileName).toString();
        provider.flightSqlEnabled = enabled;
        return provider;
    }

    private FlightSqlMappingConfigProvider providerWith(String json, boolean enabled) throws Exception {
        Path file = tempDir.resolve("mapping.json");
        Files.writeString(file, json);
        return providerFor(file.getFileName().toString(), enabled);
    }

    @Test
    void shouldLoadBothPhases() throws Exception {
        FlightSqlMappingConfigProvider provider = providerWith("""
                {
                  "before": { "processing-context": { "Action": "Protect" } },
                  "after": {
                    "right-context": { "Target": "WDX1" },
                    "processing-context": { "Action": "Unprotect" }
                  },
                  "column-mappings": []
                }
                """, true);

        provider.load();
        FlightSqlMappingConfig config = provider.get();

        assertEquals("Unprotect", config.getAfter().getAction());
        assertEquals("WDX1", config.getAfter().getRightContextEvidences().get("Target"));
        assertEquals("Protect", config.getBefore().getAction());
        assertTrue(config.isAfterActive());
    }

    @Test
    void shouldKeepAnInactiveAfterPhaseWithoutFailing() throws Exception {
        FlightSqlMappingConfigProvider provider = providerWith("""
                { "after": { "active": false }, "column-mappings": [] }
                """, true);

        provider.load();

        assertFalse(provider.get().isAfterActive());
    }

    @Test
    void shouldIgnoreAnUnknownPropertyRatherThanDroppingTheWholeConfiguration() throws Exception {
        // The exact accident the before/after split caused: one unknown key used to wipe everything.
        FlightSqlMappingConfigProvider provider = providerWith("""
                {
                  "some-future-section": { "whatever": true },
                  "after": { "processing-context": { "Action": "Unprotect" } },
                  "column-mappings": [
                    { "table": "PERSON", "column": "FIRST_NAME",
                      "rps-class-name": "Person", "rps-property-name": "shortString" }
                  ]
                }
                """, true);

        provider.load();

        assertEquals(1, provider.get().getColumnMappings().size());
        assertEquals("Unprotect", provider.get().getAfter().getAction());
    }

    @Test
    void shouldFailToStartOnAMalformedFileWhenFlightSqlIsEnabled() throws Exception {
        FlightSqlMappingConfigProvider provider = providerWith("{ not json at all", true);

        IllegalStateException error = assertThrows(IllegalStateException.class, provider::load);

        assertTrue(error.getMessage().contains("cannot be read"));
    }

    @Test
    void shouldOnlyWarnOnAMalformedFileWhenFlightSqlIsDisabled() throws Exception {
        // An application that never serves Flight SQL must not fail on a file it does not read.
        FlightSqlMappingConfigProvider provider = providerWith("{ not json at all", false);

        provider.load();

        assertNotNull(provider.get());
        assertTrue(provider.get().getColumnMappings().isEmpty());
    }

    @Test
    void shouldFailToStartWhenTheAfterSectionIsExplicitlyNull() throws Exception {
        FlightSqlMappingConfigProvider provider = providerWith("""
                { "after": null, "column-mappings": [] }
                """, true);

        IllegalStateException error = assertThrows(IllegalStateException.class, provider::load);

        assertTrue(error.getMessage().contains("'after'"));
    }

    @Test
    void shouldDefaultTheAfterPhaseWhenTheFileOmitsIt() throws Exception {
        // An omitted section is not a null one: Jackson leaves the field initializer in place.
        FlightSqlMappingConfigProvider provider = providerWith("""
                { "column-mappings": [] }
                """, true);

        provider.load();

        assertTrue(provider.get().isAfterActive());
    }

    @Test
    void shouldOnlyWarnWhenTheFileDoesNotExist() {
        FlightSqlMappingConfigProvider provider = providerFor("no-such-file.json", true);

        provider.load();

        assertTrue(provider.get().getColumnMappings().isEmpty());
        assertTrue(provider.get().isAfterActive());
    }
}
