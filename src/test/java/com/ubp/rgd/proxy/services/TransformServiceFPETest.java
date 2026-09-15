package com.ubp.rgd.proxy.services;

import com.ubp.rgd.proxy.transform.FPEEndPointTransformer;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.transform.api.TransformRequest;
import com.ubp.rgd.proxy.transform.api.TransformResponse;
import com.ubp.rgd.proxy.transform.api.TransformSet;
import com.ubp.rgd.proxy.transform.api.TransformValue;
import com.ubp.rgd.proxy.transform.config.EndPointTransformConfig;
import com.ubp.rgd.proxy.transform.config.EntityTransformConfig;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code /transform} endpoint running on Format Preserving Encryption.
 * <p>
 * The endpoint hands its values to the transformer selected by {@code proxy.transform.impl}, so it
 * protects the data exactly like the proxy filters do. These tests run the real
 * {@link FPEEndPointTransformer} rather than a stub, which is what makes the round trips meaningful:
 * no RPS engine is contacted, so the whole endpoint is exercised offline.
 */
class TransformServiceFPETest {

    /** The AES-128 test key of NIST SP 800-38G. */
    private static final String KEY = "2b7e151628aed2a6abf7158809cf4f3c";

    private final TransformService service = fpeService();

    private static TransformService fpeService() {
        TransformService service = new TransformService();
        service.transformer = FPEEndPointTransformer.withKey(KEY);
        // Authentication is covered by TransformServiceTest and needs a live KDC.
        service.preFilterAuthEnabled = "false";
        service.rightContextTarget = "WDX1";
        service.rightContextModule = "RoseGarden";
        service.rightContextRight = "Transform";
        return service;
    }

    private static TransformValue value(String v, String property, String regex) {
        TransformValue transformValue = new TransformValue();
        transformValue.setValue(v);
        transformValue.setClassName("Person");
        transformValue.setPropertyName(property);
        transformValue.setExtractRegExp(regex);
        return transformValue;
    }

    private static TransformRequest request(String action, TransformValue... values) {
        TransformSet set = new TransformSet();
        set.setAction(action);
        set.setTarget("WDX1");
        set.setJurisdiction("CH");
        set.setValues(List.of(values));

        TransformRequest request = new TransformRequest();
        request.setSets(List.of(set));
        return request;
    }

    private List<String> transform(String action, TransformValue... values) throws RPSTransformException {
        TransformResponse response = service.transform(request(action, values));
        assertEquals(1, response.getResults().size());
        return response.getResults().getFirst().getValues();
    }

    @Test
    @DisplayName("A value protected by the endpoint is unprotected by the endpoint")
    void roundTrip() throws RPSTransformException {
        List<String> protectedValues = transform("Protect",
                value("Bernadette", "FirstName", null),
                value("1234567890", "Account", null));

        assertTrue(protectedValues.getFirst().startsWith("RG{"), "must be a token: " + protectedValues);
        assertFalse(protectedValues.contains("Bernadette"));

        List<String> clearValues = transform("Unprotect",
                value(protectedValues.get(0), "FirstName", null),
                value(protectedValues.get(1), "Account", null));

        assertEquals(List.of("Bernadette", "1234567890"), clearValues);
    }

    @Test
    @DisplayName("A value is tokenized word by word when it carries an extract regex")
    void roundTripWithExtractRegex() throws RPSTransformException {
        String protectedValue = transform("Protect", value("Jean-Claude DUSSE", "Name", "[A-Za-z]+")).getFirst();

        // One token per word, with the original punctuation and spacing untouched.
        assertEquals(3, protectedValue.split("RG\\{", -1).length - 1,
                "one token per word was expected: " + protectedValue);
        assertTrue(protectedValue.contains("-"), "the formatting must be preserved: " + protectedValue);
        assertTrue(protectedValue.contains(" "), "the formatting must be preserved: " + protectedValue);
        assertFalse(protectedValue.contains("Jean"));

        assertEquals("Jean-Claude DUSSE",
                transform("Unprotect", value(protectedValue, "Name", null)).getFirst());
    }

    @Test
    @DisplayName("Short values round trip, the padding is handled by the endpoint too")
    void shortValuesRoundTrip() throws RPSTransformException {
        List<String> values = List.of("1", "42", "007", "Jo", "CH");

        for (String clearValue : values) {
            String protectedValue = transform("Protect", value(clearValue, "Code", null)).getFirst();
            assertNotEquals(clearValue, protectedValue);
            assertEquals(clearValue, transform("Unprotect", value(protectedValue, "Code", null)).getFirst());
        }
    }

    @Test
    @DisplayName("The same value gives different tokens in two different properties")
    void propertiesAreSeparated() throws RPSTransformException {
        List<String> protectedValues = transform("Protect",
                value("Bernadette", "FirstName", null),
                value("Bernadette", "LastName", null));

        assertNotEquals(protectedValues.get(0), protectedValues.get(1),
                "the tweak must separate the properties");
    }

    @Test
    @DisplayName("A value protected by the endpoint is readable by the proxy filters")
    void endpointAndProxyAreInteroperable() throws Exception {
        // Both go through the same transformer, keyed by the RPS class and property, so a value
        // protected through the endpoint must come back clear through the proxy, and the other way
        // round. That is what makes the two usable on the same data.
        String token = transform("Protect", value("Bernadette", "FirstName", null)).getFirst();

        EndPointTransformConfig cfg = new EndPointTransformConfig();
        cfg.setEndpointTransformWhen("AFTER");
        cfg.setProcessingContextEvidences(Map.of("Action", "Unprotect"));

        EntityTransformConfig entity = new EntityTransformConfig();
        entity.setJsonPath("$.name");
        entity.setRpsClassName("Person");
        entity.setRpsPropertyName("FirstName");
        cfg.setEntityTransformConfigs(Set.of(entity));

        String clearJson = service.transformer.transform("{\"name\":\"" + token + "\"}",
                new MultivaluedHashMap<>(), new MultivaluedHashMap<>(), cfg);

        assertEquals("{\"name\":\"Bernadette\"}", clearJson);
    }

    @Test
    @DisplayName("Several sets are transformed independently, in input order")
    void severalSets() throws RPSTransformException {
        TransformSet first = new TransformSet();
        first.setAction("Protect");
        first.setValues(List.of(value("Bernadette", "FirstName", null)));

        TransformSet second = new TransformSet();
        second.setAction("Protect");
        second.setValues(List.of(value("1234567890", "Account", null)));

        TransformRequest request = new TransformRequest();
        request.setSets(List.of(first, second));

        TransformResponse response = service.transform(request);

        assertEquals(2, response.getResults().size());
        assertTrue(response.getResults().get(0).getValues().getFirst().startsWith("RG{A"));
        assertTrue(response.getResults().get(1).getValues().getFirst().startsWith("RG{N"));
    }
}
