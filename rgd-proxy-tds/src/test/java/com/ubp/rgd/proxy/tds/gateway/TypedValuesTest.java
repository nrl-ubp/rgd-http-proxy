package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.tds.protocol.ServerTokens;
import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TdsReader;
import com.ubp.rgd.proxy.tds.protocol.TypeInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypedValuesTest {

    @Test
    void decimalsRoundTripAndMustFit() throws Exception {
        TypeInfo type = type(ServerTokens.decimal(10, 2));
        byte[] data = value(ServerTokens.decimalValue(new BigDecimal("-98765.43"), 10, 2));
        assertEquals("-98765.43", TypedValues.read(type, data));
        assertArrayEquals(value(ServerTokens.decimalValue(new BigDecimal("12345.67"), 10, 2)),
                TypedValues.write(type, data, "12345.67"));
        assertThrows(IllegalArgumentException.class, () -> TypedValues.write(type, data, "123456789.12"));
    }

    @Test
    void datetimeKeepsItsTime() throws Exception {
        TypeInfo type = type(ServerTokens.datetime());
        byte[] data = value(ServerTokens.datetimeValue(LocalDate.of(3021, 7, 4), 12345));
        assertEquals("3021-07-04", TypedValues.read(type, data));
        byte[] written = TypedValues.write(type, data, "1980-05-17");
        assertEquals("1980-05-17", TypedValues.read(type, written));
        assertArrayEquals(Arrays.copyOfRange(data, 4, 8), Arrays.copyOfRange(written, 4, 8));
        assertThrows(IllegalArgumentException.class, () -> TypedValues.write(type, data, "1600-01-01"));
    }

    @Test
    void integersMustFitTheirSize() throws Exception {
        TypeInfo tinyint = type(new byte[]{(byte) Tds.INT1});
        assertEquals("200", TypedValues.read(tinyint, new byte[]{(byte) 200}));
        assertThrows(IllegalArgumentException.class, () -> TypedValues.write(tinyint, new byte[]{1}, "300"));
        TypeInfo intN = type(ServerTokens.intN());
        assertEquals("-5", TypedValues.read(intN, TypedValues.write(intN, new byte[4], "-5")));
    }

    @Test
    void moneyAndFloats() throws Exception {
        TypeInfo money = type(new byte[]{(byte) Tds.MONEYN, 8});
        byte[] written = TypedValues.write(money, new byte[8], "-1234567.8901");
        assertEquals("-1234567.8901", TypedValues.read(money, written));
        TypeInfo real = type(new byte[]{(byte) Tds.FLTN, 4});
        assertEquals("1.5", TypedValues.read(real, TypedValues.write(real, new byte[4], "1.5")));
    }

    private static TypeInfo type(byte[] bytes) throws Exception {
        return TypeInfo.read(TdsReader.of(bytes));
    }

    /** The value bytes, without their BYTELEN prefix. */
    private static byte[] value(byte[] prefixed) {
        return Arrays.copyOfRange(prefixed, 1, prefixed.length);
    }
}
