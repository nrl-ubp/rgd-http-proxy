package com.ubp.rgd.proxy.fuzzysearch.service.protection;

import com.ubp.rgd.proxy.transform.model.ValueMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ColumnMappingConfigTest {

    static final String TEST_MAPPINGS = "src/test/resources/flight_sql_mapping_config.json";

    /** The mapping file the proxy really runs with. */
    static final String PROXY_MAPPINGS = "../rgd-proxy-hub/config/flight_sql_mapping_config.json";

    @Test
    @DisplayName("A table.column mapping wins over a *.column one, case-insensitively")
    void exactMappingWinsOverWildcard() {
        ColumnMappingConfig config = ColumnMappingConfig.load(TEST_MAPPINGS);

        assertEquals(new ValueMapping("Person", "personNotes"), config.find("person", "notes"));
        assertEquals(new ValueMapping("Person", "notes"), config.find("OTHER", "NOTES"));
        assertEquals(new ValueMapping("Person", "shortString"), config.find("PERSON", "first_name"));
        assertNull(config.find("PERSON", "ID"));
    }

    @Test
    @DisplayName("A column that must be mapped and is not fails explicitly")
    void missingRequiredMappingFails() {
        ColumnMappingConfig config = new ColumnMappingConfig();
        config.setColumnMappings(List.of(new ColumnMappingConfig.ColumnMapping("*", "FIRST_NAME", "Person", "shortString")));

        assertThrows(IllegalStateException.class, () -> config.require("PERSON", "LAST_NAME"));
    }

    @Test
    @DisplayName("The contexts of both phases are read")
    void phasesAreRead() {
        ColumnMappingConfig config = ColumnMappingConfig.load(TEST_MAPPINGS);

        assertEquals("WDX1Proxy", config.before().processingContext.get("Module"));
        assertEquals("Unprotect", config.after().processingContext.get("Action"));
        assertEquals("Transform", config.after().rightContext.get("Right"));
    }

    @Test
    @DisplayName("The mapping file of the proxy maps every PERSON column the search engine protects")
    void proxyMappingFileMapsEveryColumn() {
        ColumnMappingConfig config = ColumnMappingConfig.load(PROXY_MAPPINGS);

        for (String column : com.ubp.rgd.proxy.fuzzysearch.entity.PersonEntity.PROTECTED_COLUMNS) {
            config.require("PERSON", column);
        }
    }

    @Test
    @DisplayName("An unreadable file fails explicitly")
    void unreadableFileFails() {
        assertThrows(IllegalStateException.class, () -> ColumnMappingConfig.load("does/not/exist.json"));
    }
}
