package com.ubp.rgd.proxy.flightsql;

import org.apache.arrow.flight.sql.FlightSqlProducer.Schemas;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The translation of JDBC types into the ODBC layout of Flight SQL, and the typed row writer used to
 * stream that kind of metadata.
 */
class XdbcTypesTest {

    private BufferAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new RootAllocator(Long.MAX_VALUE);
    }

    @AfterEach
    void tearDown() {
        allocator.close();
    }

    // ---------------------------------------------------------------------------------------------
    // Type codes
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldKeepTheCodesJdbcSharesWithOdbc() {
        for (int code : new int[]{Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.INTEGER,
                Types.BIGINT, Types.DECIMAL, Types.DOUBLE, Types.DATE, Types.TIME, Types.TIMESTAMP,
                Types.BINARY, Types.VARBINARY, Types.BIT, Types.NVARCHAR}) {
            assertEquals(Optional.of(code), XdbcTypes.toXdbcType(code), "JDBC code " + code);
        }
    }

    @Test
    void shouldTranslateTheJdbcOnlyCodes() {
        assertEquals(Optional.of(-8), XdbcTypes.toXdbcType(Types.NCHAR));
        assertEquals(Optional.of(-9), XdbcTypes.toXdbcType(Types.LONGNVARCHAR));
        assertEquals(Optional.of(-9), XdbcTypes.toXdbcType(Types.NCLOB));
        assertEquals(Optional.of(-7), XdbcTypes.toXdbcType(Types.BOOLEAN));
        assertEquals(Optional.of(-1), XdbcTypes.toXdbcType(Types.CLOB));
        assertEquals(Optional.of(-4), XdbcTypes.toXdbcType(Types.BLOB));
        assertEquals(Optional.of(92), XdbcTypes.toXdbcType(Types.TIME_WITH_TIMEZONE));
        assertEquals(Optional.of(93), XdbcTypes.toXdbcType(Types.TIMESTAMP_WITH_TIMEZONE));
    }

    @Test
    void shouldNotPassRowidThroughAsAUnicodeCharType() {
        // ROWID is -8 in JDBC, which ODBC reads as WCHAR.
        assertEquals(-8, Types.ROWID);
        assertTrue(XdbcTypes.toXdbcType(Types.ROWID).isEmpty());
    }

    @Test
    void shouldLeaveOutTheTypesOdbcCannotRepresent() {
        for (int code : new int[]{Types.ARRAY, Types.OTHER, Types.JAVA_OBJECT, Types.STRUCT, Types.REF,
                Types.DISTINCT, Types.NULL, -155, -156}) {
            assertTrue(XdbcTypes.toXdbcType(code).isEmpty(), "JDBC code " + code);
        }
    }

    @Test
    void shouldGiveTheDatetimeSubcodeKeepingTheTimeZone() {
        assertEquals(1, XdbcTypes.datetimeSubcode(Types.DATE));
        assertEquals(2, XdbcTypes.datetimeSubcode(Types.TIME));
        assertEquals(3, XdbcTypes.datetimeSubcode(Types.TIMESTAMP));
        assertEquals(4, XdbcTypes.datetimeSubcode(Types.TIME_WITH_TIMEZONE));
        assertEquals(5, XdbcTypes.datetimeSubcode(Types.TIMESTAMP_WITH_TIMEZONE));
        assertNull(XdbcTypes.datetimeSubcode(Types.INTEGER));
    }

    @Test
    void shouldSplitTheCreationParameters() {
        assertEquals(List.of("precision", "scale"), XdbcTypes.createParams("precision, scale"));
        assertEquals(List.of("length"), XdbcTypes.createParams("length"));
        assertNull(XdbcTypes.createParams(null));
        assertNull(XdbcTypes.createParams("  "));
    }

    // ---------------------------------------------------------------------------------------------
    // Row writer
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldWriteEveryKindOfTypeInfoColumn() {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(Schemas.GET_TYPE_INFO_SCHEMA, allocator)) {
            MetadataRowWriter.fill(root, List.<Object[]>of(
                    typeInfo("DECIMAL", 3, List.of("precision", "scale"), true),
                    typeInfo("INTEGER", 4, null, null)));

            assertEquals(2, root.getRowCount());
            assertEquals("DECIMAL", root.getVector("type_name").getObject(0).toString());
            assertEquals(3, root.getVector("data_type").getObject(0));
            assertEquals("[\"precision\",\"scale\"]", root.getVector("create_params").getObject(0).toString());
            assertEquals(true, root.getVector("unsigned_attribute").getObject(0));
            assertNull(root.getVector("create_params").getObject(1));
            assertNull(root.getVector("unsigned_attribute").getObject(1));
            assertNull(root.getVector("column_size").getObject(1));
        }
    }

    @Test
    void shouldWriteAnUnsignedByteRule() {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(Schemas.GET_IMPORTED_KEYS_SCHEMA, allocator)) {
            MetadataRowWriter.fill(root, List.<Object[]>of(foreignKey(3)));

            assertEquals((byte) 3, root.getVector("delete_rule").getObject(0));
        }
    }

    @Test
    void shouldRefuseAValueTooLargeForAnUnsignedByte() {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(Schemas.GET_IMPORTED_KEYS_SCHEMA, allocator)) {
            assertThrows(IllegalArgumentException.class,
                    () -> MetadataRowWriter.fill(root, List.<Object[]>of(foreignKey(256))));
        }
    }

    @Test
    void shouldRefuseARowOfTheWrongWidth() {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(Schemas.GET_PRIMARY_KEYS_SCHEMA, allocator)) {
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{"cat", "schema", "table"});

            assertThrows(IllegalArgumentException.class, () -> MetadataRowWriter.fill(root, rows));
        }
    }

    private static Object[] typeInfo(String name, int code, List<String> params, Boolean unsigned) {
        return new Object[]{name, code, unsigned == null ? null : 10, null, null, params, 1, false, 3,
                unsigned, false, null, null, null, null, code, null, 10, null};
    }

    private static Object[] foreignKey(int deleteRule) {
        return new Object[]{null, "PUBLIC", "CUSTOMER", "ID", null, "PUBLIC", "PURCHASE", "CUSTOMER_ID",
                1, "FK", "PK", 1, deleteRule};
    }
}
