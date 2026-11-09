package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TypeInfo;
import com.ubp.rgd.proxy.transform.model.ValueKind;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The dates and numbers of a row in their text form, for a transformer that keeps the value types
 * (FPE): a protected date is still a date, a protected number still a number, stored as such.
 * <p>
 * Only the date part of a date and time is transformed, its time is kept. A value that does not fit
 * its type once transformed is refused, and the column keeps its protected value.
 */
final class TypedValues {

    private static final LocalDate DAY_ZERO = LocalDate.of(1, 1, 1);
    private static final LocalDate DATETIME_ZERO = LocalDate.of(1900, 1, 1);
    private static final LocalDate DATETIME_MIN = LocalDate.of(1753, 1, 1);
    private static final BigDecimal MONEY_SCALE = BigDecimal.valueOf(10_000);

    private TypedValues() {
    }

    /** @return the kind of values of the type, or {@code null} when it is not handled */
    static ValueKind kindOf(TypeInfo type) {
        return switch (type.type()) {
            case Tds.INT1, Tds.INT2, Tds.INT4, Tds.INT8, Tds.INTN, Tds.FLT4, Tds.FLT8, Tds.FLTN,
                 Tds.DECIMAL, Tds.NUMERIC, Tds.DECIMALN, Tds.NUMERICN,
                 Tds.MONEY, Tds.MONEY4, Tds.MONEYN -> ValueKind.NUMBER;
            case Tds.DATEN, Tds.DATETIME2N, Tds.DATETIME, Tds.DATETIM4, Tds.DATETIMN -> ValueKind.DATE;
            default -> null;
        };
    }

    /** @return the text form of a non NULL value */
    static String read(TypeInfo type, byte[] data) {
        return switch (type.type()) {
            case Tds.INT1, Tds.INT2, Tds.INT4, Tds.INT8, Tds.INTN -> Long.toString(readInteger(type, data));
            case Tds.FLT4 -> Float.toString(Float.intBitsToFloat((int) littleEndian(data, 0, 4)));
            case Tds.FLT8 -> Double.toString(Double.longBitsToDouble(littleEndian(data, 0, 8)));
            case Tds.FLTN -> data.length == 4
                    ? Float.toString(Float.intBitsToFloat((int) littleEndian(data, 0, 4)))
                    : Double.toString(Double.longBitsToDouble(littleEndian(data, 0, 8)));
            case Tds.DECIMAL, Tds.NUMERIC, Tds.DECIMALN, Tds.NUMERICN -> readDecimal(type, data).toPlainString();
            case Tds.MONEY, Tds.MONEY4, Tds.MONEYN -> BigDecimal.valueOf(readMoney(data), 4).toPlainString();
            case Tds.DATEN -> DAY_ZERO.plusDays(littleEndian(data, 0, 3)).toString();
            case Tds.DATETIME2N -> DAY_ZERO.plusDays(littleEndian(data, data.length - 3, 3)).toString();
            case Tds.DATETIME, Tds.DATETIM4, Tds.DATETIMN -> data.length == 4
                    ? DATETIME_ZERO.plusDays(littleEndian(data, 0, 2)).toString()
                    : DATETIME_ZERO.plusDays((int) littleEndian(data, 0, 4)).toString();
            default -> throw new IllegalArgumentException("Unsupported type " + type);
        };
    }

    /**
     * Encode a transformed value in place of the original one.
     *
     * @return the new bytes, of the same length as the original ones
     * @throws IllegalArgumentException when the value does not fit the type
     */
    static byte[] write(TypeInfo type, byte[] original, String text) {
        return switch (type.type()) {
            case Tds.INT1, Tds.INT2, Tds.INT4, Tds.INT8, Tds.INTN -> writeInteger(original.length, type.type(), Long.parseLong(text));
            case Tds.FLT4 -> toLittleEndian(Float.floatToIntBits(Float.parseFloat(text)), 4);
            case Tds.FLT8 -> toLittleEndian(Double.doubleToLongBits(Double.parseDouble(text)), 8);
            case Tds.FLTN -> original.length == 4
                    ? toLittleEndian(Float.floatToIntBits(Float.parseFloat(text)), 4)
                    : toLittleEndian(Double.doubleToLongBits(Double.parseDouble(text)), 8);
            case Tds.DECIMAL, Tds.NUMERIC, Tds.DECIMALN, Tds.NUMERICN -> writeDecimal(type, original.length, text);
            case Tds.MONEY, Tds.MONEY4, Tds.MONEYN -> writeMoney(original.length, text);
            case Tds.DATEN -> toLittleEndian(days(LocalDate.parse(text), DAY_ZERO), 3);
            case Tds.DATETIME2N -> {
                byte[] bytes = original.clone();
                byte[] date = toLittleEndian(days(LocalDate.parse(text), DAY_ZERO), 3);
                System.arraycopy(date, 0, bytes, bytes.length - 3, 3);
                yield bytes;
            }
            case Tds.DATETIME, Tds.DATETIM4, Tds.DATETIMN -> writeDateTime(original, LocalDate.parse(text));
            default -> throw new IllegalArgumentException("Unsupported type " + type);
        };
    }

    private static long readInteger(TypeInfo type, byte[] data) {
        long value = littleEndian(data, 0, data.length);
        return switch (data.length) {
            case 1 -> value & 0xFF;
            case 2 -> (short) value;
            case 4 -> (int) value;
            default -> value;
        };
    }

    private static byte[] writeInteger(int length, int type, long value) {
        boolean fits = switch (length) {
            case 1 -> value >= 0 && value <= 0xFF;
            case 2 -> value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
            case 4 -> value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
            default -> true;
        };
        if (!fits) {
            throw new IllegalArgumentException("the value does not fit the integer column");
        }
        return toLittleEndian(value, length);
    }

    private static BigDecimal readDecimal(TypeInfo type, byte[] data) {
        byte[] magnitude = new byte[data.length - 1];
        for (int i = 1; i < data.length; i++) {
            magnitude[data.length - 1 - i] = data[i];
        }
        BigInteger unscaled = new BigInteger(1, magnitude);
        return new BigDecimal(data[0] == 0 ? unscaled.negate() : unscaled, type.scale());
    }

    private static byte[] writeDecimal(TypeInfo type, int length, String text) {
        // The number of digits is preserved, so the scale is exact and nothing is rounded.
        BigDecimal number = new BigDecimal(text).setScale(type.scale(), RoundingMode.UNNECESSARY);
        if (number.precision() > type.precision()) {
            throw new IllegalArgumentException("the value does not fit the decimal column");
        }
        BigInteger magnitude = number.unscaledValue().abs();
        if (magnitude.bitLength() > (length - 1) * 8) {
            throw new IllegalArgumentException("the value does not fit the decimal column");
        }
        byte[] bigEndian = magnitude.toByteArray();
        byte[] bytes = new byte[length];
        bytes[0] = (byte) (number.signum() < 0 ? 0 : 1);
        for (int i = 0; i < length - 1; i++) {
            int source = bigEndian.length - 1 - i;
            bytes[1 + i] = source >= 0 ? bigEndian[source] : 0;
        }
        return bytes;
    }

    private static long readMoney(byte[] data) {
        if (data.length == 4) {
            return (int) littleEndian(data, 0, 4);
        }
        // The high 32 bits come first.
        return (littleEndian(data, 0, 4) << 32) | (littleEndian(data, 4, 4) & 0xFFFFFFFFL);
    }

    private static byte[] writeMoney(int length, String text) {
        long units = new BigDecimal(text).multiply(MONEY_SCALE).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        if (length == 4) {
            if (units < Integer.MIN_VALUE || units > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("the value does not fit the smallmoney column");
            }
            return toLittleEndian(units, 4);
        }
        byte[] bytes = new byte[8];
        System.arraycopy(toLittleEndian(units >>> 32, 4), 0, bytes, 0, 4);
        System.arraycopy(toLittleEndian(units, 4), 0, bytes, 4, 4);
        return bytes;
    }

    private static byte[] writeDateTime(byte[] original, LocalDate date) {
        byte[] bytes = original.clone();
        long days = date.toEpochDay() - DATETIME_ZERO.toEpochDay();
        if (original.length == 4) {
            if (days < 0 || days > 0xFFFF) {
                throw new IllegalArgumentException("the date does not fit the smalldatetime column");
            }
            System.arraycopy(toLittleEndian(days, 2), 0, bytes, 0, 2);
        } else {
            if (date.isBefore(DATETIME_MIN) || date.getYear() > 9999) {
                throw new IllegalArgumentException("the date does not fit the datetime column");
            }
            System.arraycopy(toLittleEndian(days, 4), 0, bytes, 0, 4);
        }
        return bytes;
    }

    private static long days(LocalDate date, LocalDate zero) {
        if (date.getYear() < 1 || date.getYear() > 9999) {
            throw new IllegalArgumentException("the date does not fit the date column");
        }
        return date.toEpochDay() - zero.toEpochDay();
    }

    private static long littleEndian(byte[] data, int offset, int length) {
        long value = 0;
        for (int i = length - 1; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private static byte[] toLittleEndian(long value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (value >>> (8 * i));
        }
        return bytes;
    }
}
