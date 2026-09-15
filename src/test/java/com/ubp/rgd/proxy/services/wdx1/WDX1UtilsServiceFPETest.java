package com.ubp.rgd.proxy.services.wdx1;

import ch.regdata.rps.engine.client.Context;
import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.enginecontext.ProcessingContext;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.transform.FPEEndPointTransformer;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link WDX1UtilsService} with the FPE transformer, end to end and without any running RPS
 * engine, so the whole {@code tokenConcat} flow is exercised in both directions.
 * <p>
 * This is what makes the service pluggable visible: the tokens are really produced and consumed by
 * the configured transformer, not by the RegData engine.
 */
class WDX1UtilsServiceFPETest {

    private static final String KEY = "000102030405060708090A0B0C0D0E0F";

    private FPEEndPointTransformer transformer;
    private WDX1UtilsService service;

    @BeforeEach
    void setUp() throws Exception {
        transformer = FPEEndPointTransformer.withKey(KEY);

        service = new WDX1UtilsService();
        service.transformer = transformer;
        // The Kerberos SPN check is what this property is there to switch off.
        service.preFilterAuthEnabled = "false";
        service.wdx1ConcatConfigFile = "./config/wdx1_concat_config.json";
        service.init();
    }

    /**
     * Protect a value through the public transformer API, the way the proxy would, so the test data
     * is real FPE output rather than a hand written token.
     */
    private String protect(String clear, String className, String propertyName) throws Exception {
        return transform(clear, className, propertyName, "Protect");
    }

    private String unprotect(String token, String className, String propertyName) throws Exception {
        return transform(token, className, propertyName, "Unprotect");
    }

    private String transform(String value, String className, String propertyName, String action)
            throws Exception {
        RPSValue rpsValue = new RPSValue(new RPSMapping(className, propertyName), value);
        ProcessingContext processingContext = new ProcessingContext();
        processingContext.addEvidence(new Evidence("Action", action));

        transformer.transformData(new RPSValue[] { rpsValue }, new Context(), processingContext);

        return rpsValue.getTransformed();
    }

    private WDX1ConcatRequest request(String firstName, String lastName, String birthDate) {
        WDX1ConcatRequest request = new WDX1ConcatRequest();
        request.setCountry("CH");
        request.setFirstName(firstName);
        request.setLastName(lastName);
        request.setBirthDate(birthDate);
        return request;
    }

    @Test
    @DisplayName("The name tokens are detokenized by the configured transformer")
    void detokenizesTheNamesWithTheConfiguredTransformer() throws Exception {
        WDX1ConcatRequest request = request(
                protect("John", "Person", "ShortString"),
                protect("Kennedy", "Person", "ShortString"),
                protect("1917-05-29", "Person", "Date"));

        assertTrue(request.getFirstName().startsWith("RG{"), request.getFirstName());

        service.tokenConcat(request);

        // tokenConcat detokenizes then sanitizes in place, which upper cases the names.
        assertEquals("JOHN", request.getFirstName());
        assertEquals("KENNEDY", request.getLastName());
    }

    @Test
    @DisplayName("A field holding several tokens is detokenized token by token")
    void detokenizesEveryTokenOfAField() throws Exception {
        String john = protect("John", "Person", "ShortString");
        String fitzgerald = protect("Fitzgerald", "Person", "ShortString");

        WDX1ConcatRequest request = request(
                john + " " + fitzgerald,
                protect("Kennedy", "Person", "ShortString"),
                protect("1917-05-29", "Person", "Date"));

        service.tokenConcat(request);

        // Both tokens were replaced in place; the sanitizer then removes the separator, which is what
        // makes the concatenation key stable whatever the spacing of the name.
        assertEquals("JOHNFITZGERALD", request.getFirstName());
    }

    @Test
    @DisplayName("The concatenation key is protected by the configured transformer")
    void protectsTheConcatenationKey() throws Exception {
        WDX1ConcatRequest request = request(
                protect("John", "Person", "ShortString"),
                protect("Kennedy", "Person", "ShortString"),
                protect("1917-05-29", "Person", "Date"));

        String concatToken = service.tokenConcat(request);

        assertTrue(concatToken.startsWith("RG{"), concatToken);

        // Reading the key back proves both directions really went through the transformer: the
        // country, the birth date, then five characters of each name, hash padded.
        String clearKey = unprotect(concatToken, "Person", "ShortString");
        assertEquals("CH" + "19170529" + "JOHN#" + "KENNE", clearKey);
    }

    @Test
    @DisplayName("The same request always gives the same concatenation token")
    void isDeterministic() throws Exception {
        String first = service.tokenConcat(request(
                protect("John", "Person", "ShortString"),
                protect("Kennedy", "Person", "ShortString"),
                protect("1917-05-29", "Person", "Date")));

        String second = service.tokenConcat(request(
                protect("John", "Person", "ShortString"),
                protect("Kennedy", "Person", "ShortString"),
                protect("1917-05-29", "Person", "Date")));

        assertEquals(first, second);
    }

    @Test
    @DisplayName("A token the transformer cannot detokenize is rejected instead of being used as a name")
    void rejectsATokenThatCameBackUntransformed() throws Exception {
        // An RPS shaped token: the FPE transformer does not recognise its header and returns it
        // unchanged. Without the guard it would silently become the "clear" first name and end up in
        // the concatenation key.
        WDX1ConcatRequest request = request(
                "RG{Bx12345678aa}",
                protect("Kennedy", "Person", "ShortString"),
                protect("1917-05-29", "Person", "Date"));

        RPSTransformException thrown =
                assertThrows(RPSTransformException.class, () -> service.tokenConcat(request));

        assertTrue(thrown.getMessage().contains("was not detokenized"), thrown.getMessage());
    }

    @Test
    @DisplayName("The birth date token is detokenized and rendered as YYYYMMDD")
    void detokenizesAndNormalisesTheBirthDate() throws Exception {
        // Regression test. getRPSValues() used to apply getTokenizationFormattedDate() to
        // request.getBirthDate() while it was still a token; SimpleDateFormat cannot parse "RG{...}"
        // so the method returned its "PARSE ERROR" fallback, and that string was sent to the
        // tokenizer and ended up in the concatenation key instead of the date.
        WDX1ConcatRequest request = request(
                protect("John", "Person", "ShortString"),
                protect("Kennedy", "Person", "ShortString"),
                protect("1917-05-29", "Person", "Date"));

        service.tokenConcat(request);

        assertEquals("19170529", request.getBirthDate());
    }

    @Test
    @DisplayName("The date format declared by the caller is honoured")
    void honoursTheRequestDateFormat() throws Exception {
        WDX1ConcatRequest request = request(
                protect("John", "Person", "ShortString"),
                protect("Kennedy", "Person", "ShortString"),
                protect("29/05/1917", "Person", "Date"));
        request.setDateFormat("dd/MM/yyyy");

        service.tokenConcat(request);

        // Same key component as the ISO example above: the caller's format only describes the input.
        assertEquals("19170529", request.getBirthDate());
    }

    @Test
    @DisplayName("A detokenized birth date that is not a valid date is rejected")
    void rejectsAnUnparseableBirthDate() throws Exception {
        WDX1ConcatRequest request = request(
                protect("John", "Person", "ShortString"),
                protect("Kennedy", "Person", "ShortString"),
                protect("not-a-date", "Person", "Date"));

        RPSTransformException thrown =
                assertThrows(RPSTransformException.class, () -> service.tokenConcat(request));

        assertTrue(thrown.getMessage().contains("is not a valid date"), thrown.getMessage());
    }

    @Test
    @DisplayName("An impossible date is rejected rather than rolled over")
    void rejectsAnImpossibleBirthDate() throws Exception {
        // Lenient parsing would silently turn 2024-13-45 into 2025-01-14, a plausible looking date
        // that would then be stored in the concatenation key.
        WDX1ConcatRequest request = request(
                protect("John", "Person", "ShortString"),
                protect("Kennedy", "Person", "ShortString"),
                protect("2024-13-45", "Person", "Date"));

        assertThrows(RPSTransformException.class, () -> service.tokenConcat(request));
    }
}
