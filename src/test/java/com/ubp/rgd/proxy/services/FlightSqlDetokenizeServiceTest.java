package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.transform.config.FlightSqlColumnMapping;
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
    }

    @AfterEach
    void tearDown() {
        allocator.close();
    }

    // ---------------------------------------------------------------------------------------------
    // Token extraction / reassembly
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldExtractEveryTokenOfAValue() {
        List<RPSValue> tokens =
                FlightSqlDetokenizeService.extractTokens("RG{abc} de RG{xyz}", MAPPING);

        assertEquals(2, tokens.size());
        assertEquals("RG{abc}", tokens.get(0).getOriginal());
        assertEquals("RG{xyz}", tokens.get(1).getOriginal());
        assertEquals("Person", tokens.get(0).getMapping().getClassName());
    }

    @Test
    void shouldExtractNoTokenFromAClearValue() {
        assertTrue(FlightSqlDetokenizeService.extractTokens("John Doe", MAPPING).isEmpty());
        assertTrue(FlightSqlDetokenizeService.extractTokens("", MAPPING).isEmpty());
    }

    @Test
    void shouldRebuildAValuePreservingItsFormat() {
        String rebuilt = FlightSqlDetokenizeService.rebuild(
                "Mr RG{aaa} RG{bbb} (Geneva)", List.of("John", "Doe"));

        assertEquals("Mr John Doe (Geneva)", rebuilt);
    }

    @Test
    void shouldRebuildAValueWithRegexSpecialCharactersInTheClearValue() {
        String rebuilt = FlightSqlDetokenizeService.rebuild("RG{aaa}", List.of("a\\b$c"));

        assertEquals("a\\b$c", rebuilt);
    }

    @Test
    void shouldLeaveTheValueUnchangedWhenNoClearValueIsAvailable() {
        assertEquals("RG{aaa}", FlightSqlDetokenizeService.rebuild("RG{aaa}", List.of()));
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
        try (VectorSchemaRoot root = newRoot("RG{aaa}", null, "clear")) {
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
        try (VectorSchemaRoot root = newRoot("RG{a}", "RG{b}")) {
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
    void shouldNotCallTheEngineWhenNoColumnIsMapped() throws Exception {
        try (VectorSchemaRoot root = newRoot("RG{aaa}", "RG{bbb}")) {
            // The engine provider is not injected: any engine call would raise a NullPointerException.
            service.detokenize(root, java.util.Collections.singletonList(null), allocator);

            assertEquals("RG{aaa}", readString((VarCharVector) root.getVector(0), 0));
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

    private static String readString(VarCharVector vector, int row) {
        return new String(vector.get(row), StandardCharsets.UTF_8);
    }
}
