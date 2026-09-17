package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.transform.FPEEndPointTransformer;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link FlightSqlDetokenizeService} with the FPE transformer, end to end and without any
 * running RPS engine: values are really protected with FPE, written into a real Arrow record batch,
 * and the service is expected to hand them back in clear.
 * <p>
 * The point of these tests is that what a token looks like is now asked to the transformer. The
 * previous hardcoded RPS pattern required at least eleven characters inside the braces, while an FPE
 * token holds six to eight, so it would have matched nothing at all and returned every record batch
 * untouched — silently.
 */
class FlightSqlDetokenizeFPETest {

    private static final String KEY = "000102030405060708090A0B0C0D0E0F";

    private BufferAllocator allocator;
    private FPEEndPointTransformer transformer;
    private FlightSqlDetokenizeService service;

    @BeforeEach
    void setUp() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        transformer = FPEEndPointTransformer.withKey(KEY);
        service = new FlightSqlDetokenizeService();
        service.setTransformer(transformer);
        service.setMappingConfig(new FlightSqlMappingConfig());
        service.loadTokenIndexMappings();
    }

    @AfterEach
    void tearDown() {
        FlightSqlTokenIndexResolver.clear();
        allocator.close();
    }

    /**
     * Protect a value the way the proxy would, so the test data is real FPE output rather than a
     * hand written token.
     */
    private String protect(String clear, String className, String propertyName) throws Exception {
        RPSValue value = new RPSValue(new RPSMapping(className, propertyName), clear);
        ProcessingContext processingContext = new ProcessingContext();
        processingContext.addEvidence(new Evidence("Action", "Protect"));

        transformer.transformData(new RPSValue[] { value }, new Context(), processingContext);

        return value.getTransformed();
    }

    private VectorSchemaRoot newRoot(String columnName, String... values) {
        Field field = new Field(columnName, FieldType.nullable(new ArrowType.Utf8()), null);
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

    private static String readString(VectorSchemaRoot root, int row) {
        VarCharVector vector = (VarCharVector) root.getVector(0);
        return vector.isNull(row) ? null : new String(vector.get(row), StandardCharsets.UTF_8);
    }

    private static FlightSqlMappingConfig configWith(List<FlightSqlDataMapping> dataMappings) {
        FlightSqlMappingConfig config = new FlightSqlMappingConfig();
        config.setDataMappings(dataMappings);
        config.getAfter().setProcessingContextEvidences(new HashMap<>(Map.of("Action", "Unprotect")));
        return config;
    }

    @Test
    @DisplayName("An FPE token is detokenized through its column mapping")
    void detokenizesWithTheColumnMapping() throws Exception {
        String token = protect("Bernadette", "Person", "FirstName");
        assertTrue(token.startsWith("RG{"), token);

        try (VectorSchemaRoot root = newRoot("FIRST_NAME", token)) {
            service.detokenize(root,
                    List.of(new FlightSqlColumnMapping("PERSON", "FIRST_NAME", "Person", "FirstName")),
                    allocator);

            assertEquals("Bernadette", readString(root, 0));
        }
    }

    @Test
    @DisplayName("The RPS token pattern would have missed an FPE token entirely")
    void theRpsPatternWouldNotHaveMatched() throws Exception {
        String token = protect("Bernadette", "Person", "FirstName");

        assertFalse(com.ubp.rgd.proxy.transform.RPSEndPointTransformer.TOKEN_PATTERN
                        .matcher(token).find(),
                "This test only makes sense while the two token formats differ: " + token);
        assertTrue(transformer.tokenPattern().matcher(token).find());
    }

    @Test
    @DisplayName("Every row and every kind of value of a column is detokenized")
    void detokenizesEveryRow() throws Exception {
        String bernadette = protect("Bernadette", "Person", "FirstName");
        String jean = protect("Jean", "Person", "FirstName");

        try (VectorSchemaRoot root = newRoot("FIRST_NAME", bernadette, null, "already clear", jean)) {
            service.detokenize(root,
                    List.of(new FlightSqlColumnMapping("PERSON", "FIRST_NAME", "Person", "FirstName")),
                    allocator);

            assertEquals("Bernadette", readString(root, 0));
            assertEquals(null, readString(root, 1));
            assertEquals("already clear", readString(root, 2));
            assertEquals("Jean", readString(root, 3));
        }
    }

    @Test
    @DisplayName("A token embedded in free text is detokenized in place")
    void detokenizesATokenInsideFreeText() throws Exception {
        String token = protect("Bernadette", "Person", "FirstName");

        try (VectorSchemaRoot root = newRoot("COMMENT", "Hello " + token + " !")) {
            service.detokenize(root,
                    List.of(new FlightSqlColumnMapping("PERSON", "COMMENT", "Person", "FirstName")),
                    allocator);

            assertEquals("Hello Bernadette !", readString(root, 0));
        }
    }

    @Test
    @DisplayName("An unmapped column is detokenized implicitly through a data mapping")
    void detokenizesWithADataMapping() throws Exception {
        String token = protect("Bernadette", "Person", "FirstName");
        // The regex claims any token, and supplies the class and property the column mapping would have.
        service.setMappingConfig(configWith(
                List.of(new FlightSqlDataMapping("RG\\{[^}]+\\}", "Person", "FirstName"))));

        try (VectorSchemaRoot root = newRoot("ANYTHING", token)) {
            service.detokenize(root, Collections.singletonList(null), allocator);

            assertEquals("Bernadette", readString(root, 0));
        }
    }

    @Test
    @DisplayName("The token mapping index tier is disabled for a transformer without one")
    void skipsTheMappingIndexTier() throws Exception {
        // The resolver is left empty on purpose: loadTokenIndexMappings must not even try to fill it.
        assertEquals(0, FlightSqlTokenIndexResolver.size());

        // Registering an index by hand proves the tier is skipped rather than merely unfed: "Bx" is
        // declared, yet an unmapped column with no data mapping must still come back untouched.
        FlightSqlTokenIndexResolver.register("B", "Person.FirstName");
        String token = protect("Bernadette", "Person", "FirstName");

        try (VectorSchemaRoot root = newRoot("ANYTHING", token)) {
            service.detokenize(root, Collections.singletonList(null), allocator);

            assertEquals(token, readString(root, 0), "Nothing should have been detokenized");
        }
    }

    @Test
    @DisplayName("A value protected in a file or through the endpoint is readable over Flight SQL")
    void isInteroperableWithTheOtherCallers() throws Exception {
        // All the callers share the transformer and the per property tweak, so a value protected
        // anywhere in the proxy is detokenized here.
        String token = protect("123456789", "Person", "Account");

        try (VectorSchemaRoot root = newRoot("ACCOUNT", token)) {
            service.detokenize(root,
                    List.of(new FlightSqlColumnMapping("PERSON", "ACCOUNT", "Person", "Account")),
                    allocator);

            assertEquals("123456789", readString(root, 0));
        }
    }

    @Test
    @DisplayName("A batch holding no token is left untouched")
    void leavesAClearBatchUntouched() throws Exception {
        List<String> before = new ArrayList<>(List.of("John", "Doe", ""));

        try (VectorSchemaRoot root = newRoot("FIRST_NAME", before.toArray(new String[0]))) {
            service.detokenize(root,
                    List.of(new FlightSqlColumnMapping("PERSON", "FIRST_NAME", "Person", "FirstName")),
                    allocator);

            for (int row = 0; row < before.size(); row++) {
                assertEquals(before.get(row), readString(root, row));
            }
        }
    }
}
