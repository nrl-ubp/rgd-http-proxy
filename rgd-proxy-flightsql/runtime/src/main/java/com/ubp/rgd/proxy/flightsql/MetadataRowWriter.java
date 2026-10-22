package com.ubp.rgd.proxy.flightsql;

import org.apache.arrow.flight.FlightProducer.ServerStreamListener;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.types.pojo.Schema;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Streams metadata rows whose columns are not all text, such as the type info and the key
 * descriptions of Flight SQL, which mix {@code utf8}, {@code int32}, {@code bool}, {@code uint8} and
 * {@code list<utf8>}.
 * <p>
 * Each row is an {@code Object[]} following the schema's column order. A value is written according
 * to the vector of its column:
 *
 * <table>
 *     <tr><th>Vector</th><th>Accepted value</th></tr>
 *     <tr><td>{@code VarCharVector}</td><td>{@link String}</td></tr>
 *     <tr><td>{@code IntVector}</td><td>{@link Number}</td></tr>
 *     <tr><td>{@code UInt1Vector}</td><td>{@link Number}, 0 to 255</td></tr>
 *     <tr><td>{@code BitVector}</td><td>{@link Boolean}</td></tr>
 *     <tr><td>{@code ListVector} of {@code VarCharVector}</td><td>{@code List<String>}</td></tr>
 * </table>
 *
 * A {@code null} is written as a null. Any other vector type is a programming error.
 */
final class MetadataRowWriter {

    private MetadataRowWriter() {
    }

    static void stream(BufferAllocator allocator, ServerStreamListener listener, Schema schema,
                       List<Object[]> rows) {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
            fill(root, rows);
            listener.start(root);
            listener.putNext();
            listener.completed();
        }
    }

    /** Fill an empty root with the rows; package-visible for the tests. */
    static void fill(VectorSchemaRoot root, List<Object[]> rows) {
        root.allocateNew();
        List<FieldVector> vectors = root.getFieldVectors();
        for (int column = 0; column < vectors.size(); column++) {
            FieldVector vector = vectors.get(column);
            for (int row = 0; row < rows.size(); row++) {
                Object[] values = rows.get(row);
                if (values.length != vectors.size()) {
                    throw new IllegalArgumentException("Row " + row + " has " + values.length
                            + " values but the schema has " + vectors.size() + " columns.");
                }
                write(vector, row, values[column]);
            }
            vector.setValueCount(rows.size());
        }
        root.setRowCount(rows.size());
    }

    private static void write(FieldVector vector, int row, Object value) {
        if (vector instanceof VarCharVector text) {
            if (value == null) {
                text.setNull(row);
            } else {
                text.setSafe(row, ((String) value).getBytes(StandardCharsets.UTF_8));
            }
        } else if (vector instanceof IntVector integer) {
            if (value == null) {
                integer.setNull(row);
            } else {
                integer.setSafe(row, ((Number) value).intValue());
            }
        } else if (vector instanceof UInt1Vector unsigned) {
            if (value == null) {
                unsigned.setNull(row);
            } else {
                int number = ((Number) value).intValue();
                if (number < 0 || number > 255) {
                    throw new IllegalArgumentException("Value " + number + " does not fit the uint8"
                            + " column " + vector.getName() + ".");
                }
                unsigned.setSafe(row, number);
            }
        } else if (vector instanceof BitVector bit) {
            if (value == null) {
                bit.setNull(row);
            } else {
                bit.setSafe(row, ((Boolean) value) ? 1 : 0);
            }
        } else if (vector instanceof ListVector list
                && list.getDataVector() instanceof VarCharVector items) {
            writeTextList(list, items, row, value);
        } else {
            throw new IllegalArgumentException("Unsupported metadata column " + vector.getName()
                    + " of type " + vector.getField().getType() + ".");
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeTextList(ListVector list, VarCharVector items, int row, Object value) {
        if (value == null) {
            list.setNull(row);
            return;
        }
        List<String> texts = (List<String>) value;
        int offset = list.startNewValue(row);
        for (int i = 0; i < texts.size(); i++) {
            items.setSafe(offset + i, texts.get(i).getBytes(StandardCharsets.UTF_8));
        }
        list.endValue(row, texts.size());
    }
}
