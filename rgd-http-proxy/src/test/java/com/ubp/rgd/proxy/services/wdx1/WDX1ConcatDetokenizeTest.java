package com.ubp.rgd.proxy.services.wdx1;

import com.ubp.rgd.proxy.transform.model.ValueMapping;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.transform.TransformException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code firstNameToken} / {@code lastNameToken} fields containing one or more
 * {@code RG{...}} tokens are properly detokenized, each token being replaced in place by its
 * clear value while preserving the original field layout.
 */
class WDX1ConcatDetokenizeTest {

    private static final ValueMapping NAME_MAPPING = new ValueMapping("Person", "ShortString");
    private static final ValueMapping DATE_MAPPING = new ValueMapping("Person", "Date");

    /**
     * A service loaded with the real concat configuration. Only the configuration is needed here: no
     * transformation is performed, the tests call {@code setRPSValuesTransformed} with values that
     * are already detokenized.
     */
    private WDX1UtilsService service() throws IOException {
        WDX1UtilsService service = new WDX1UtilsService();
        service.wdx1ConcatConfigFile = "./config/wdx1_concat_config.json";
        service.init();
        return service;
    }

    private MappedValue token(String original, String transformed) {
        MappedValue value = new MappedValue(NAME_MAPPING, original);
        value.setTransformed(transformed);
        return value;
    }

    private MappedValue dateToken(String transformed) {
        MappedValue value = new MappedValue(DATE_MAPPING, "RG{date}");
        value.setTransformed(transformed);
        return value;
    }

    private Map<String, MappedValue[]> values(MappedValue[] firstName, MappedValue[] lastName, MappedValue birthDate) {
        Map<String, MappedValue[]> map = new HashMap<>();
        map.put("firstName", firstName);
        map.put("lastName", lastName);
        map.put("birthDate", new MappedValue[]{birthDate});
        return map;
    }

    @Test
    void testMultipleSpaceSeparatedTokensAreDetokenized() throws Exception {
        WDX1UtilsService service = service();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setFirstName("RG{aaa} RG{bbb}");
        request.setLastName("RG{ccc}");
        request.setBirthDate("RG{ddd}");

        Map<String, MappedValue[]> values = values(
                new MappedValue[]{token("RG{aaa}", "John"), token("RG{bbb}", "Fitzgerald")},
                new MappedValue[]{token("RG{ccc}", "Kennedy")},
                dateToken("1917-05-29"));

        service.setRPSValuesTransformed(values, request);

        assertEquals("John Fitzgerald", request.getFirstName());
        assertEquals("Kennedy", request.getLastName());
        assertEquals("19170529", request.getBirthDate());
    }

    @Test
    void testAdjacentTokensWithoutSeparatorAreDetokenized() throws Exception {
        WDX1UtilsService service = service();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setFirstName("RG{aaa}RG{bbb}");
        request.setLastName("RG{ccc}");
        request.setBirthDate("RG{ddd}");

        Map<String, MappedValue[]> values = values(
                new MappedValue[]{token("RG{aaa}", "John"), token("RG{bbb}", "Paul")},
                new MappedValue[]{token("RG{ccc}", "Jones")},
                dateToken("2000-01-01"));

        service.setRPSValuesTransformed(values, request);

        assertEquals("JohnPaul", request.getFirstName());
        assertEquals("Jones", request.getLastName());
    }

    @Test
    void testSurroundingTextIsPreserved() throws Exception {
        WDX1UtilsService service = service();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setFirstName("Mr RG{aaa} van RG{bbb}");
        request.setLastName("RG{ccc}-RG{ddd}");
        request.setBirthDate("RG{eee}");

        Map<String, MappedValue[]> values = values(
                new MappedValue[]{token("RG{aaa}", "Jean"), token("RG{bbb}", "Pierre")},
                new MappedValue[]{token("RG{ccc}", "de"), token("RG{ddd}", "la")},
                dateToken("1985-12-31"));

        service.setRPSValuesTransformed(values, request);

        assertEquals("Mr Jean van Pierre", request.getFirstName());
        assertEquals("de-la", request.getLastName());
    }

    @Test
    void testClearValueWithRegexSpecialCharactersIsPreserved() throws Exception {
        WDX1UtilsService service = service();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setFirstName("RG{aaa}");
        request.setLastName("RG{bbb}");
        request.setBirthDate("RG{ccc}");

        Map<String, MappedValue[]> values = values(
                new MappedValue[]{token("RG{aaa}", "O'$Neil\\Doe")},
                new MappedValue[]{token("RG{bbb}", "Smith")},
                dateToken("1970-01-01"));

        service.setRPSValuesTransformed(values, request);

        assertEquals("O'$Neil\\Doe", request.getFirstName());
    }

    @Test
    void testMissingTokensThrows() throws Exception {
        WDX1UtilsService service = service();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setFirstName("");
        request.setLastName("RG{ccc}");
        request.setBirthDate("RG{ddd}");

        Map<String, MappedValue[]> values = values(
                new MappedValue[0],
                new MappedValue[]{token("RG{ccc}", "Kennedy")},
                dateToken("1917-05-29"));

        assertThrows(TransformException.class, () -> service.setRPSValuesTransformed(values, request));
    }
}
