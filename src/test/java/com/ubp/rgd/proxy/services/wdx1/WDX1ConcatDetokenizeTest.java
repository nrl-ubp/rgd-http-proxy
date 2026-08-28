package com.ubp.rgd.proxy.services.wdx1;

import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code firstNameToken} / {@code lastNameToken} fields containing one or more
 * {@code RG{...}} tokens are properly detokenized, each token being replaced in place by its
 * clear value while preserving the original field layout.
 */
class WDX1ConcatDetokenizeTest {

    private static final RPSMapping NAME_MAPPING = new RPSMapping("Person", "ShortString");
    private static final RPSMapping DATE_MAPPING = new RPSMapping("Person", "Date");

    private RPSValue token(String original, String transformed) {
        RPSValue value = new RPSValue(NAME_MAPPING, original);
        value.setTransformed(transformed);
        return value;
    }

    private RPSValue dateToken(String transformed) {
        RPSValue value = new RPSValue(DATE_MAPPING, "RG{date}");
        value.setTransformed(transformed);
        return value;
    }

    private Map<String, RPSValue[]> values(RPSValue[] firstName, RPSValue[] lastName, RPSValue birthDate) {
        Map<String, RPSValue[]> map = new HashMap<>();
        map.put("firstName", firstName);
        map.put("lastName", lastName);
        map.put("birthDate", new RPSValue[]{birthDate});
        return map;
    }

    @Test
    void testMultipleSpaceSeparatedTokensAreDetokenized() throws RPSTransformException {
        WDX1UtilsService service = new WDX1UtilsService();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setFirstName("RG{aaa} RG{bbb}");
        request.setLastName("RG{ccc}");
        request.setBirthDate("RG{ddd}");

        Map<String, RPSValue[]> values = values(
                new RPSValue[]{token("RG{aaa}", "John"), token("RG{bbb}", "Fitzgerald")},
                new RPSValue[]{token("RG{ccc}", "Kennedy")},
                dateToken("19170529"));

        service.setRPSValuesTransformed(values, request);

        assertEquals("John Fitzgerald", request.getFirstName());
        assertEquals("Kennedy", request.getLastName());
        assertEquals("19170529", request.getBirthDate());
    }

    @Test
    void testAdjacentTokensWithoutSeparatorAreDetokenized() throws RPSTransformException {
        WDX1UtilsService service = new WDX1UtilsService();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setFirstName("RG{aaa}RG{bbb}");
        request.setLastName("RG{ccc}");
        request.setBirthDate("RG{ddd}");

        Map<String, RPSValue[]> values = values(
                new RPSValue[]{token("RG{aaa}", "John"), token("RG{bbb}", "Paul")},
                new RPSValue[]{token("RG{ccc}", "Jones")},
                dateToken("20000101"));

        service.setRPSValuesTransformed(values, request);

        assertEquals("JohnPaul", request.getFirstName());
        assertEquals("Jones", request.getLastName());
    }

    @Test
    void testSurroundingTextIsPreserved() throws RPSTransformException {
        WDX1UtilsService service = new WDX1UtilsService();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setFirstName("Mr RG{aaa} van RG{bbb}");
        request.setLastName("RG{ccc}-RG{ddd}");
        request.setBirthDate("RG{eee}");

        Map<String, RPSValue[]> values = values(
                new RPSValue[]{token("RG{aaa}", "Jean"), token("RG{bbb}", "Pierre")},
                new RPSValue[]{token("RG{ccc}", "de"), token("RG{ddd}", "la")},
                dateToken("19851231"));

        service.setRPSValuesTransformed(values, request);

        assertEquals("Mr Jean van Pierre", request.getFirstName());
        assertEquals("de-la", request.getLastName());
    }

    @Test
    void testClearValueWithRegexSpecialCharactersIsPreserved() throws RPSTransformException {
        WDX1UtilsService service = new WDX1UtilsService();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setFirstName("RG{aaa}");
        request.setLastName("RG{bbb}");
        request.setBirthDate("RG{ccc}");

        Map<String, RPSValue[]> values = values(
                new RPSValue[]{token("RG{aaa}", "O'$Neil\\Doe")},
                new RPSValue[]{token("RG{bbb}", "Smith")},
                dateToken("19700101"));

        service.setRPSValuesTransformed(values, request);

        assertEquals("O'$Neil\\Doe", request.getFirstName());
    }

    @Test
    void testMissingTokensThrows() {
        WDX1UtilsService service = new WDX1UtilsService();

        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setFirstName("");
        request.setLastName("RG{ccc}");
        request.setBirthDate("RG{ddd}");

        Map<String, RPSValue[]> values = values(
                new RPSValue[0],
                new RPSValue[]{token("RG{ccc}", "Kennedy")},
                dateToken("19170529"));

        assertThrows(RPSTransformException.class, () -> service.setRPSValuesTransformed(values, request));
    }
}
