package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import com.ubp.rgd.proxy.services.FlightSqlDetokenizeService.Segment;
import com.ubp.rgd.proxy.transform.config.FlightSqlColumnMapping;
import com.ubp.rgd.proxy.transform.config.FlightSqlDataMapping;
import com.ubp.rgd.proxy.transform.config.FlightSqlMappingConfig;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlightSqlDetokenizeServiceTest {

    private static final RPSMapping MAPPING = new RPSMapping("Person", "shortString");

    private BufferAllocator allocator;
    private FlightSqlDetokenizeService service;

    @BeforeEach
    void setUp() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        service = new FlightSqlDetokenizeService();
        service.setMappingConfig(new FlightSqlMappingConfig());
        // Built outside of CDI, so @PostConstruct never ran: populate the token index table by hand.
        service.loadTokenIndexMappings();
    }

    @AfterEach
    void tearDown() {
        FlightSqlTokenIndexResolver.clear();
        allocator.close();
    }

    // ---------------------------------------------------------------------------------------------
    // Token extraction / reassembly
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldExtractEveryTokenOfAValue() {
        List<Segment> segments =
                FlightSqlDetokenizeService.extractTokens("RG{AB12345678aa} de RG{CD87654321bb}", MAPPING);

        assertEquals(2, segments.size());
        assertEquals("RG{AB12345678aa}", segments.get(0).rpsValue().getOriginal());
        assertEquals("RG{CD87654321bb}", segments.get(1).rpsValue().getOriginal());
        assertEquals(0, segments.get(0).start());
        assertEquals(16, segments.get(0).end());
        assertEquals("Person", segments.get(0).rpsValue().getMapping().getClassName());
    }

    @Test
    void shouldExtractNoTokenFromAClearValue() {
        assertTrue(FlightSqlDetokenizeService.extractTokens("John Doe", MAPPING).isEmpty());
        assertTrue(FlightSqlDetokenizeService.extractTokens("", MAPPING).isEmpty());
    }

    @Test
    void shouldRebuildAValuePreservingItsFormat() {
        String original = "Mr RG{AB12345678aa} RG{CD87654321bb} (Geneva)";
        String rebuilt = FlightSqlDetokenizeService.rebuild(original,
                FlightSqlDetokenizeService.extractTokens(original, MAPPING), List.of("John", "Doe"));

        assertEquals("Mr John Doe (Geneva)", rebuilt);
    }

    @Test
    void shouldRebuildAValueWithRegexSpecialCharactersInTheClearValue() {
        String original = "RG{AB12345678aa}";
        String rebuilt = FlightSqlDetokenizeService.rebuild(original,
                FlightSqlDetokenizeService.extractTokens(original, MAPPING), List.of("a\\b$c"));

        assertEquals("a\\b$c", rebuilt);
    }

    @Test
    void shouldLeaveTheValueUnchangedWhenNoClearValueIsAvailable() {
        String original = "RG{AB12345678aa}";
        assertEquals(original, FlightSqlDetokenizeService.rebuild(original,
                FlightSqlDetokenizeService.extractTokens(original, MAPPING), List.of()));
    }

    // ---------------------------------------------------------------------------------------------
    // Implicit detokenization (data mappings)
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldLocateASegmentWithItsOwnDataMappingClassAndProperty() {
        List<Segment> segments = FlightSqlDetokenizeService.extractDataMappedSegments(
                "born 3011-04-05 in Geneva",
                List.of(dataMapping("\\d{4}-\\d{2}-\\d{2}", "Person", "birthDate")));

        assertEquals(1, segments.size());
        assertEquals("3011-04-05", segments.get(0).rpsValue().getOriginal());
        assertEquals("Person", segments.get(0).rpsValue().getMapping().getClassName());
        assertEquals("birthDate", segments.get(0).rpsValue().getMapping().getPropertyName());
    }

    @Test
    void shouldExtractTheWholeMatchAndNotACapturingGroup() {
        List<Segment> segments = FlightSqlDetokenizeService.extractDataMappedSegments(
                "3011-04-05",
                List.of(dataMapping("(?<Year>\\d{4})-(?<Month>\\d{2})-(?<Day>\\d{2})", "Person", "birthDate")));

        assertEquals(1, segments.size());
        assertEquals("3011-04-05", segments.get(0).rpsValue().getOriginal());
    }

    @Test
    void shouldApplyEveryDataMappingAndOrderTheSegmentsByPosition() {
        List<Segment> segments = FlightSqlDetokenizeService.extractDataMappedSegments(
                "date 3011-04-05 mail RG{AB12345678aa}",
                List.of(dataMapping("RG\\{[^}]+\\}", "Person", "email"),
                        dataMapping("\\d{4}-\\d{2}-\\d{2}", "Person", "birthDate")));

        assertEquals(2, segments.size());
        assertEquals("3011-04-05", segments.get(0).rpsValue().getOriginal());
        assertEquals("RG{AB12345678aa}", segments.get(1).rpsValue().getOriginal());
    }

    @Test
    void shouldDiscardAMatchOverlappingASegmentKeptByAnEarlierDataMapping() {
        List<Segment> segments = FlightSqlDetokenizeService.extractDataMappedSegments(
                "3011-04-05",
                List.of(dataMapping("\\d{4}-\\d{2}-\\d{2}", "Person", "birthDate"),
                        dataMapping("\\d{4}", "Other", "number")));

        assertEquals(1, segments.size());
        assertEquals("3011-04-05", segments.get(0).rpsValue().getOriginal());
        assertEquals("birthDate", segments.get(0).rpsValue().getMapping().getPropertyName());
    }

    @Test
    void shouldLeaveAValueMatchingNoDataMappingUntouched() {
        assertTrue(FlightSqlDetokenizeService.extractDataMappedSegments("John Doe",
                List.of(dataMapping("\\d{4}-\\d{2}-\\d{2}", "Person", "birthDate"))).isEmpty());
        assertTrue(FlightSqlDetokenizeService.extractDataMappedSegments("anything", List.of()).isEmpty());
    }

    @Test
    void shouldIgnoreADataMappingWhoseRegexDoesNotCompile() {
        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        config.setDataMappings(List.of(dataMapping("[unclosed", "Person", "email"),
                dataMapping("\\d{4}-\\d{2}-\\d{2}", "Person", "birthDate")));

        List<FlightSqlDataMapping> usable = config.getUsableDataMappings();

        assertEquals(1, usable.size());
        assertEquals("birthDate", usable.get(0).getRpsPropertyName());
    }

    @Test
    void shouldIgnoreADataMappingAbleToMatchAnEmptyString() {
        assertTrue(FlightSqlDetokenizeService.extractDataMappedSegments("John",
                List.of(dataMapping("\\d*", "Other", "number"))).isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Token mapping index (last resort)
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldResolveATokenFromItsMappingIndexWhenNoDataMappingMatches() {
        List<Segment> segments = FlightSqlDetokenizeService.extractImplicitSegments(
                "Mr RG{Bx12345678aa}", List.of());

        assertEquals(1, segments.size());
        assertEquals("RG{Bx12345678aa}", segments.get(0).rpsValue().getOriginal());
        assertEquals("Person", segments.get(0).rpsValue().getMapping().getClassName());
        assertEquals("ShortString", segments.get(0).rpsValue().getMapping().getPropertyName());
    }

    @Test
    void shouldLeaveATokenCarryingAnUndeclaredMappingIndexUntouched() {
        // AB does not follow the encoding, and Ax is not declared in the hardcoded table.
        assertTrue(FlightSqlDetokenizeService.extractImplicitSegments(
                "RG{AB12345678aa} RG{Ax12345678aa}", List.of()).isEmpty());
    }

    @Test
    void shouldPreferTheDataMappingOverTheMappingIndexOfAToken() {
        List<Segment> segments = FlightSqlDetokenizeService.extractImplicitSegments(
                "RG{Bx12345678aa}",
                List.of(dataMapping("RG\\{[^}]+\\}", "Other", "number")));

        assertEquals(1, segments.size());
        assertEquals("Other", segments.get(0).rpsValue().getMapping().getClassName());
    }

    @Test
    void shouldCombineDataMappedAndIndexMappedSegmentsInOrder() {
        List<Segment> segments = FlightSqlDetokenizeService.extractImplicitSegments(
                "on 3011-04-05 for RG{Bx12345678aa}",
                List.of(dataMapping("\\d{4}-\\d{2}-\\d{2}", "Person", "birthDate")));

        assertEquals(2, segments.size());
        assertEquals("3011-04-05", segments.get(0).rpsValue().getOriginal());
        assertEquals("birthDate", segments.get(0).rpsValue().getMapping().getPropertyName());
        assertEquals("RG{Bx12345678aa}", segments.get(1).rpsValue().getOriginal());
        assertEquals("ShortString", segments.get(1).rpsValue().getMapping().getPropertyName());
    }

    @Test
    void shouldSkipATokenOverlappingASegmentAlreadyClaimed() {
        List<Segment> claimed = List.of(new Segment(0, 16, null));

        assertTrue(FlightSqlDetokenizeService
                .extractIndexMappedSegments("RG{Bx12345678aa}", claimed).isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Column mapping resolution
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldResolveTheMappingOfEachResultSetColumn() throws Exception {
        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        config.setColumnMappings(List.of(
                new FlightSqlColumnMapping("PERSON", "FIRST_NAME", "Person", "shortString")));
        service.setMappingConfig(config);

        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(2);
        when(metaData.getTableName(1)).thenReturn("PERSON");
        when(metaData.getColumnLabel(1)).thenReturn("FIRST_NAME");
        when(metaData.getColumnName(1)).thenReturn("FIRST_NAME");
        when(metaData.getTableName(2)).thenReturn("PERSON");
        when(metaData.getColumnLabel(2)).thenReturn("AGE");
        when(metaData.getColumnName(2)).thenReturn("AGE");

        List<FlightSqlColumnMapping> mappings = service.resolveMappings(metaData);

        assertEquals(2, mappings.size());
        assertNotNull(mappings.get(0));
        assertEquals("shortString", mappings.get(0).getRpsPropertyName());
        assertNull(mappings.get(1));
    }

    @Test
    void shouldResolveTheMappingFromThePhysicalColumnNameWhenTheLabelIsAnAlias() throws Exception {
        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        config.setColumnMappings(List.of(
                new FlightSqlColumnMapping("PERSON", "FIRST_NAME", "Person", "shortString")));
        service.setMappingConfig(config);

        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getTableName(1)).thenReturn("PERSON");
        when(metaData.getColumnLabel(1)).thenReturn("GIVEN_NAME");
        when(metaData.getColumnName(1)).thenReturn("FIRST_NAME");

        assertNotNull(service.resolveMappings(metaData).get(0));
    }

    // ---------------------------------------------------------------------------------------------
    // Record batch rewriting
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldRewriteOnlyTheRequestedRowsOfAColumn() {
        try (VectorSchemaRoot root = newRoot("RG{AB12345678aa}", null, "clear")) {
            FlightSqlDetokenizeService.rewriteColumn(root, 0, Map.of(0, "John"), allocator);

            assertEquals(3, root.getRowCount());
            VarCharVector vector = (VarCharVector) root.getVector(0);
            assertEquals("John", readString(vector, 0));
            assertTrue(vector.isNull(1));
            assertEquals("clear", readString(vector, 2));
        }
    }

    @Test
    void shouldRewriteWithValuesLongerThanTheOriginalOnes() {
        try (VectorSchemaRoot root = newRoot("RG{AB12345678aa}", "RG{CD87654321bb}")) {
            FlightSqlDetokenizeService.rewriteColumn(root, 0,
                    Map.of(0, "a much much longer clear value", 1, "x"), allocator);

            VarCharVector vector = (VarCharVector) root.getVector(0);
            assertEquals("a much much longer clear value", readString(vector, 0));
            assertEquals("x", readString(vector, 1));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Detokenization short-circuits (no RPS engine involved)
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldNotCallTheEngineWhenNothingResolvesTheTokensOfAnUnmappedColumn() throws Exception {
        // No data mapping is configured, and the AB / CD mapping indexes are not declared either.
        service.setMappingConfig(new FlightSqlMappingConfig());
        try (VectorSchemaRoot root = newRoot("RG{AB12345678aa}", "RG{CD87654321bb}")) {
            // The engine provider is not injected: any engine call would raise a NullPointerException.
            service.detokenize(root, java.util.Collections.singletonList(null), allocator);

            assertEquals("RG{AB12345678aa}", readString((VarCharVector) root.getVector(0), 0));
        }
    }

    @Test
    void shouldNotCallTheEngineWhenNoValueHoldsAToken() throws Exception {
        try (VectorSchemaRoot root = newRoot("John", "Doe")) {
            service.detokenize(root,
                    List.of(new FlightSqlColumnMapping("PERSON", "NAME", "Person", "shortString")),
                    allocator);

            assertEquals("John", readString((VarCharVector) root.getVector(0), 0));
        }
    }

    @Test
    void shouldNotCallTheEngineWhenNoValueMatchesADataMapping() throws Exception {
        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        config.setDataMappings(List.of(dataMapping("\\d{4}-\\d{2}-\\d{2}", "Person", "birthDate")));
        service.setMappingConfig(config);

        try (VectorSchemaRoot root = newRoot("John", "Doe")) {
            service.detokenize(root, java.util.Collections.singletonList(null), allocator);

            assertEquals("John", readString((VarCharVector) root.getVector(0), 0));
        }
    }

    @Test
    void shouldLoadTheTokenIndexTableFromTheConfiguredClientId() {
        assertTrue(FlightSqlTokenIndexResolver.size() > 0);
        assertEquals("Person.ShortString",
                FlightSqlTokenIndexResolver.resolveMappingName("RG{Bx12345678aa}"));
    }

    @Test
    void shouldLeaveTheTokenIndexTableEmptyWhenItsInitializationFails() {
        FlightSqlDetokenizeService failing = new FlightSqlDetokenizeService() {
            @Override
            void initTokenIndexMappings(String clientId) {
                FlightSqlTokenIndexResolver.register("C", "Person.BirthDate");
                throw new IllegalStateException("RPS is unreachable");
            }
        };

        failing.loadTokenIndexMappings();

        assertEquals(0, FlightSqlTokenIndexResolver.size());
    }

    @Test
    void shouldIgnoreAnEmptyOrNullBatch() throws Exception {
        service.detokenize(null, List.of(), allocator);
        try (VectorSchemaRoot root = newRoot()) {
            service.detokenize(root, List.of(), allocator);
            assertEquals(0, root.getRowCount());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private VectorSchemaRoot newRoot(String... values) {
        Field field = new Field("NAME", FieldType.nullable(new ArrowType.Utf8()), null);
        VectorSchemaRoot root = VectorSchemaRoot.create(new Schema(List.of(field)), allocator);
        root.allocateNew();
        VarCharVector vector = (VarCharVector) root.getVector(0);
        for (int row = 0; row < values.length; row++) {
            if (values[row] == null) {
                vector.setNull(row);
            } else {
                vector.setSafe(row, values[row].getBytes(StandardCharsets.UTF_8));
            }
        }
        vector.setValueCount(values.length);
        root.setRowCount(values.length);
        return root;
    }

    private static FlightSqlDataMapping dataMapping(String regex, String className, String propertyName) {
        return new FlightSqlDataMapping(regex, className, propertyName);
    }

    private static String readString(VarCharVector vector, int row) {
        return new String(vector.get(row), StandardCharsets.UTF_8);
    }
}
