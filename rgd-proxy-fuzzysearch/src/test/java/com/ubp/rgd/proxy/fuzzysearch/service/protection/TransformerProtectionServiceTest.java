package com.ubp.rgd.proxy.fuzzysearch.service.protection;

import com.ubp.rgd.proxy.fpe.FPEEndPointTransformer;
import com.ubp.rgd.proxy.transform.AbstractEndPointTransformer;
import com.ubp.rgd.proxy.transform.DateValues;
import com.ubp.rgd.proxy.transform.ValuePlan;
import com.ubp.rgd.proxy.transform.model.Evidence;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.model.ProcessingContext;
import com.ubp.rgd.proxy.transform.model.RightContext;
import com.ubp.rgd.proxy.transform.model.ValueMapping;
import com.ubp.rgd.proxy.fuzzysearch.entity.PersonEntity;
import com.ubp.rgd.proxy.fuzzysearch.service.protection.ProtectionService.ColumnValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link TransformerProtectionService} with the real FPE transformer of the proxy, and with a
 * recording transformer standing in for RPS.
 */
class TransformerProtectionServiceTest {

    private static final String KEY = "2b7e151628aed2a6abf7158809cf4f3c";

    private final FPEEndPointTransformer fpe = FPEEndPointTransformer.withKey(KEY);

    private final ProtectionService service = TransformerProtectionService.of(fpe,
            ColumnMappingConfig.load(ColumnMappingConfigTest.TEST_MAPPINGS));

    @Test
    @DisplayName("Every column round trips, each with its own mapping")
    void everyColumnRoundTrips() {
        List<ColumnValue> clear = List.of(
                new ColumnValue(PersonEntity.FIRST_NAME, "Jean-Claude"),
                new ColumnValue(PersonEntity.LAST_NAME, "Dusse"),
                new ColumnValue(PersonEntity.BIRTH_DATE, "1980-05-12"),
                new ColumnValue(PersonEntity.EMAIL, "jean.dusse@ubp.ch"),
                new ColumnValue(PersonEntity.CITY, "Genève"),
                new ColumnValue(PersonEntity.NOTES, "Client met on 2024-01-15 in Lausanne"));

        List<String> protectedValues = service.protectAll(clear);
        for (int i = 0; i < clear.size(); i++) {
            assertNotEquals(clear.get(i).value(), protectedValues.get(i));
        }

        List<ColumnValue> stored = new ArrayList<>();
        for (int i = 0; i < clear.size(); i++) {
            stored.add(new ColumnValue(clear.get(i).column(), protectedValues.get(i)));
        }
        assertEquals(clear.stream().map(ColumnValue::value).toList(), service.unprotectAll(stored));
    }

    @Test
    @DisplayName("A birth date stays a date, a name gets a token")
    void dateStaysADate() {
        String date = service.protect(PersonEntity.BIRTH_DATE, "1980-05-12");
        assertTrue(DateValues.parse(date).date().getYear() >= 3000, date);

        assertTrue(service.protect(PersonEntity.FIRST_NAME, "Bernadette").startsWith("RG{"));
    }

    @Test
    @DisplayName("A value protected by the proxy's transform() extension is unprotected here")
    void readsValuesProtectedByTheProxy() throws Exception {
        // Exactly what FlightSqlTokenizeService does for transform('Person', 'shortString', ..., 'Jean-Claude')
        MappedValue value = new MappedValue(new ValueMapping("Person", "shortString"), "Jean-Claude");
        fpe.transformData(new MappedValue[]{value}, new RightContext(), action("Protect"));

        assertEquals("Jean-Claude", service.unprotect(PersonEntity.FIRST_NAME, value.getTransformed()));
        assertEquals("Jean-Claude", service.unprotect(PersonEntity.LAST_NAME, value.getTransformed()));
    }

    @Test
    @DisplayName("A value protected here is unprotected by the proxy")
    void proxyReadsValuesProtectedHere() throws Exception {
        MappedValue value = new MappedValue(new ValueMapping("Person", "birthDate"),
                service.protect(PersonEntity.BIRTH_DATE, "12.05.1980"));
        fpe.transformData(new MappedValue[]{value}, new RightContext(), action("Unprotect"));

        assertEquals("12.05.1980", value.getTransformed());
    }

    @Test
    @DisplayName("Exact search works: protecting the same value twice gives the same stored value")
    void protectionIsDeterministic() {
        assertEquals(service.protect(PersonEntity.FIRST_NAME, "Jean"), service.protect(PersonEntity.FIRST_NAME, "Jean"));
        assertNotEquals(service.protect(PersonEntity.FIRST_NAME, "Jean"), service.protect(PersonEntity.FIRST_NAME, "jean"));
    }

    @Test
    @DisplayName("Nulls stay null, clear values and clear dates are left untouched on unprotect")
    void nullsAndClearValues() {
        List<String> result = service.unprotectAll(Arrays.asList(
                new ColumnValue(PersonEntity.EMAIL, null),
                new ColumnValue(PersonEntity.CITY, "Genève"),
                new ColumnValue(PersonEntity.BIRTH_DATE, "1980-05-12")));

        assertNull(result.get(0));
        assertEquals("Genève", result.get(1));
        assertEquals("1980-05-12", result.get(2));
        assertNull(service.protect(PersonEntity.EMAIL, null));
    }

    @Test
    @DisplayName("A protected column without mapping fails at construction")
    void missingMappingFailsAtConstruction() {
        ColumnMappingConfig incomplete = new ColumnMappingConfig();
        incomplete.setColumnMappings(List.of(new ColumnMappingConfig.ColumnMapping("*", "FIRST_NAME", "Person", "shortString")));

        assertThrows(IllegalStateException.class, () -> TransformerProtectionService.of(fpe, incomplete));
    }

    @Test
    @DisplayName("With RPS: RG{} tokens are located, mappings and contexts come from the mapping file")
    void worksWithAnRpsLikeTransformer() {
        RecordingTransformer rps = new RecordingTransformer();
        ProtectionService rpsService = TransformerProtectionService.of(rps,
                ColumnMappingConfig.load(ColumnMappingConfigTest.TEST_MAPPINGS));

        assertEquals("<shortString:Jean> <shortString:Paul>",
                rpsService.unprotect(PersonEntity.FIRST_NAME, "RG{AB12345678aa} RG{AB12345678bb}"));
        assertEquals("Unprotect", rps.processingContext.get("Action"));
        assertEquals("WDX1Proxy", rps.processingContext.get("Module"));
        assertEquals("Transform", rps.rightContext.get("Right"));

        assertEquals("<personNotes:hello>", rpsService.protect(PersonEntity.NOTES, "hello"));
        assertEquals("Protect", rps.processingContext.get("Action"));
    }

    @Test
    @DisplayName("The action always follows the direction, whatever the mapping file says")
    void actionFollowsTheDirection() {
        ColumnMappingConfig config = ColumnMappingConfig.load(ColumnMappingConfigTest.TEST_MAPPINGS);
        config.after().processingContext.put("Action", "Protect");
        ProtectionService misconfigured = TransformerProtectionService.of(fpe, config);

        String token = misconfigured.protect(PersonEntity.FIRST_NAME, "Bernadette");
        assertEquals("Bernadette", misconfigured.unprotect(PersonEntity.FIRST_NAME, token));
    }

    @Test
    @DisplayName("A query is split on whitespace and lowercased")
    void tokenize() {
        assertEquals(List.of("jean-claude", "dusse"), service.tokenize("  Jean-Claude   DUSSE "));
        assertTrue(service.tokenize(" ").isEmpty());
        assertFalse(service.tokenize("a").isEmpty());
    }

    private static ProcessingContext action(String action) {
        ProcessingContext context = new ProcessingContext();
        context.addEvidence(new Evidence("Action", action));
        return context;
    }

    /**
     * Stands in for the RPS transformer: RG{} tokens, no value type preserved, and every value
     * transformed into {@code <property:value>} where the value of a token is a fixed name.
     */
    private static class RecordingTransformer extends AbstractEndPointTransformer {

        ProcessingContext processingContext;
        RightContext rightContext;

        @Override
        public void transformData(MappedValue[] values, RightContext rightContext, ProcessingContext processingContext,
                                  UUID secretsManager) {
            this.rightContext = rightContext;
            this.processingContext = processingContext;
            for (MappedValue value : values) {
                String original = value.getOriginal();
                String clear = original.endsWith("aa}") ? "Jean" : original.endsWith("bb}") ? "Paul" : original;
                value.setTransformed("<" + value.getMapping().getPropertyName() + ":" + clear + ">");
            }
        }

        @Override
        public Pattern tokenPattern() {
            return ValuePlan.TOKEN_PATTERN;
        }

        @Override
        public boolean supportsTokenMappingIndex() {
            return true;
        }
    }
}
