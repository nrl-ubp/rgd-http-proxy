package com.ubp.rgd.proxy.flightsql;

import com.ubp.rgd.proxy.transform.model.ValueKind;
import org.apache.arrow.vector.BaseIntVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Reads a date or numeric Arrow column as text and writes a transformed value back with the column
 * type, for the transformers that keep a date a date and a number a number once protected.
 * <p>
 * A date is read in ISO {@code yyyy-mm-dd} form, a number as {@link String#valueOf} writes it.
 */
abstract class TypedColumn {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    /**
     * The adapter of a vector.
     *
     * @param vector the column
     * @return the adapter, or {@code null} when the column type is not supported
     */
    static TypedColumn of(FieldVector vector) {
        if (vector instanceof DateDayVector dates) {
            return new DateDay(dates);
        }
        if (vector instanceof DateMilliVector dates) {
            return new DateMilli(dates);
        }
        if (vector instanceof BaseIntVector integers) {
            return new Integers(integers);
        }
        if (vector instanceof Float8Vector doubles) {
            return new Doubles(doubles);
        }
        if (vector instanceof Float4Vector floats) {
            return new Floats(floats);
        }
        if (vector instanceof DecimalVector decimals) {
            return new Decimals(decimals);
        }
        return null;
    }

    /** @return the type of the values of the column */
    abstract ValueKind kind();

    /**
     * @param row a non null row
     * @return the value of the row as text
     */
    abstract String read(int row);

    /**
     * Write a transformed value.
     *
     * @param row   the row
     * @param value the transformed value as text
     * @throws IllegalArgumentException when the value does not fit the column type
     */
    abstract void write(int row, String value);

    private static final class DateDay extends TypedColumn {
        private final DateDayVector vector;

        DateDay(DateDayVector vector) {
            this.vector = vector;
        }

        @Override
        ValueKind kind() {
            return ValueKind.DATE;
        }

        @Override
        String read(int row) {
            return LocalDate.ofEpochDay(vector.get(row)).toString();
        }

        @Override
        void write(int row, String value) {
            vector.set(row, Math.toIntExact(LocalDate.parse(value).toEpochDay()));
        }
    }

    private static final class DateMilli extends TypedColumn {
        private final DateMilliVector vector;

        DateMilli(DateMilliVector vector) {
            this.vector = vector;
        }

        @Override
        ValueKind kind() {
            return ValueKind.DATE;
        }

        @Override
        String read(int row) {
            return LocalDate.ofEpochDay(Math.floorDiv(vector.get(row), MILLIS_PER_DAY)).toString();
        }

        @Override
        void write(int row, String value) {
            // The time of the day, normally midnight, is kept as it was.
            long timeOfDay = Math.floorMod(vector.get(row), MILLIS_PER_DAY);
            vector.set(row, LocalDate.parse(value).toEpochDay() * MILLIS_PER_DAY + timeOfDay);
        }
    }

    private static final class Integers extends TypedColumn {
        private final BaseIntVector vector;

        Integers(BaseIntVector vector) {
            this.vector = vector;
        }

        @Override
        ValueKind kind() {
            return ValueKind.NUMBER;
        }

        @Override
        String read(int row) {
            return Long.toString(vector.getValueAsLong(row));
        }

        @Override
        void write(int row, String value) {
            long number = Long.parseLong(value);
            long previous = vector.getValueAsLong(row);
            vector.setWithPossibleTruncate(row, number);
            if (vector.getValueAsLong(row) != number) {
                vector.setWithPossibleTruncate(row, previous);
                throw new IllegalArgumentException("the value does not fit the integer column");
            }
        }
    }

    private static final class Doubles extends TypedColumn {
        private final Float8Vector vector;

        Doubles(Float8Vector vector) {
            this.vector = vector;
        }

        @Override
        ValueKind kind() {
            return ValueKind.NUMBER;
        }

        @Override
        String read(int row) {
            return Double.toString(vector.get(row));
        }

        @Override
        void write(int row, String value) {
            vector.set(row, Double.parseDouble(value));
        }
    }

    private static final class Floats extends TypedColumn {
        private final Float4Vector vector;

        Floats(Float4Vector vector) {
            this.vector = vector;
        }

        @Override
        ValueKind kind() {
            return ValueKind.NUMBER;
        }

        @Override
        String read(int row) {
            return Float.toString(vector.get(row));
        }

        @Override
        void write(int row, String value) {
            vector.set(row, Float.parseFloat(value));
        }
    }

    private static final class Decimals extends TypedColumn {
        private final DecimalVector vector;

        Decimals(DecimalVector vector) {
            this.vector = vector;
        }

        @Override
        ValueKind kind() {
            return ValueKind.NUMBER;
        }

        @Override
        String read(int row) {
            return vector.getObject(row).toPlainString();
        }

        @Override
        void write(int row, String value) {
            // The number of digits is preserved, so the scale is exact and nothing is rounded.
            BigDecimal number = new BigDecimal(value).setScale(vector.getScale(), RoundingMode.UNNECESSARY);
            if (number.precision() > vector.getPrecision()) {
                throw new IllegalArgumentException("the value does not fit the decimal column");
            }
            vector.set(row, number);
        }
    }
}
