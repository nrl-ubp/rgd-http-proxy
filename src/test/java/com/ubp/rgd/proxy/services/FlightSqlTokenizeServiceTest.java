package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.Evidence;
import ch.regdata.rps.engine.client.mapping.RPSMapping;
import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.services.FlightSqlTokenizeService.FlightSqlTransformSyntaxException;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.transform.config.FlightSqlMappingConfig;
import com.ubp.rgd.proxy.transform.config.FlightSqlPhaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code transform()} SQL extension: what the scanner recognizes, what it refuses, and what
 * reaches the engine.
 */
class FlightSqlTokenizeServiceTest {

    private RecordingTokenizeService service;
    private FlightSqlMappingConfig config;

    @BeforeEach
    void setUp() {
        config = new FlightSqlMappingConfig();
        config.getBefore().setRightContextEvidences(new HashMap<>(Map.of("Target", "WDX1")));
        config.getBefore().setProcessingContextEvidences(new HashMap<>(Map.of("Action", "Protect")));
        service = new RecordingTokenizeService();
        service.setMappingConfigProvider(providerOf(config));
    }

    private static FlightSqlMappingConfigProvider providerOf(FlightSqlMappingConfig config) {
        return new FlightSqlMappingConfigProvider() {
            @Override
            public FlightSqlMappingConfig get() {
                return config;
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Rewriting
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldReplaceACallByAQuotedToken() throws Exception {
        String rewritten = service.rewrite(
                "SELECT * FROM PERSON WHERE FIRST_NAME = transform('Person','shortString','CH','Jean')");

        assertEquals("SELECT * FROM PERSON WHERE FIRST_NAME = 'TOK(Person.shortString:Jean)'", rewritten);
    }

    @Test
    void shouldRecognizeTheFunctionNameInAnyCaseAndWithSpaces() throws Exception {
        String rewritten = service.rewrite(
                "SELECT * FROM PERSON WHERE NAME = TransForm ('Person','shortString','CH','Jean')");

        assertEquals("SELECT * FROM PERSON WHERE NAME = 'TOK(Person.shortString:Jean)'", rewritten);
    }

    @Test
    void shouldEscapeAQuoteOfTheProducedToken() throws Exception {
        service.tokenPrefix = "it's ";
        String rewritten = service.rewrite("SELECT transform('Person','p','CH','x')");

        // The quote of the token is doubled, otherwise the literal would end in the middle.
        assertEquals("SELECT 'it''s TOK(Person.p:x)'", rewritten);
    }

    @Test
    void shouldReadAnEscapedQuoteOfTheTextArgument() throws Exception {
        service.rewrite("SELECT transform('Person','p','CH','O''Brian')");

        assertEquals("O'Brian", service.submitted.get(0).getOriginal());
    }

    @Test
    void shouldReadATextArgumentHoldingCommasAndParentheses() throws Exception {
        service.rewrite("SELECT transform('Person','p','CH','Dusse, Jean-Claude (Geneva)')");

        assertEquals("Dusse, Jean-Claude (Geneva)", service.submitted.get(0).getOriginal());
    }

    @Test
    void shouldTransformEveryCallOfAStatementInASingleEngineCall() throws Exception {
        String rewritten = service.rewrite(
                "SELECT * FROM PERSON WHERE FIRST_NAME = transform('Person','firstName','CH','Jean')"
                        + " AND LAST_NAME = transform('Person','lastName','CH','Dusse')"
                        + " AND CITY = transform('Person','city','CH','Geneva')");

        // The performance contract of the feature: one round trip per statement, not one per call.
        assertEquals(1, service.callCount);
        assertEquals(3, service.submitted.size());
        assertEquals("SELECT * FROM PERSON WHERE FIRST_NAME = 'TOK(Person.firstName:Jean)'"
                + " AND LAST_NAME = 'TOK(Person.lastName:Dusse)'"
                + " AND CITY = 'TOK(Person.city:Geneva)'", rewritten);
    }

    @Test
    void shouldPassTheClassAndPropertyOfEachCallAsItsOwnMapping() throws Exception {
        service.rewrite("SELECT transform('Person','firstName','CH','Jean'),"
                + " transform('Account','iban','CH','CH99')");

        RPSMapping first = service.submitted.get(0).getMapping();
        RPSMapping second = service.submitted.get(1).getMapping();
        assertEquals("Person", first.getClassName());
        assertEquals("firstName", first.getPropertyName());
        assertEquals("Account", second.getClassName());
        assertEquals("iban", second.getPropertyName());
    }

    @Test
    void shouldLeaveAStatementWithoutTheExtensionUntouchedAndNeverCallTheEngine() throws Exception {
        String sql = "SELECT * FROM PERSON WHERE FIRST_NAME = 'Jean'";

        assertSame(sql, service.rewrite(sql));
        assertEquals(0, service.callCount);
    }

    @Test
    void shouldIgnoreANullOrEmptyStatement() throws Exception {
        assertEquals(null, service.rewrite(null));
        assertEquals("", service.rewrite(""));
        assertEquals(0, service.callCount);
    }

    // ---------------------------------------------------------------------------------------------
    // What is not a call
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldNotRewriteACallWrittenInsideAStringLiteral() throws Exception {
        String sql = "SELECT 'transform(''Person'',''p'',''CH'',''Jean'')' FROM DUAL";

        assertEquals(sql, service.rewrite(sql));
        assertEquals(0, service.callCount);
    }

    @Test
    void shouldNotRewriteACallWrittenInsideALineComment() throws Exception {
        String sql = "SELECT 1 -- transform('Person','p','CH','Jean')\nFROM DUAL";

        assertEquals(sql, service.rewrite(sql));
    }

    @Test
    void shouldNotRewriteACallWrittenInsideABlockComment() throws Exception {
        String sql = "SELECT /* transform('Person','p','CH','Jean') */ 1 FROM DUAL";

        assertEquals(sql, service.rewrite(sql));
    }

    @Test
    void shouldStillRewriteACallFollowingACommentedOutOne() throws Exception {
        String rewritten = service.rewrite(
                "SELECT /* transform('a','b','c','d') */ transform('Person','p','CH','Jean') FROM DUAL");

        assertEquals("SELECT /* transform('a','b','c','d') */ 'TOK(Person.p:Jean)' FROM DUAL", rewritten);
    }

    @Test
    void shouldNotRewriteACallWrittenInsideAQuotedIdentifier() throws Exception {
        String sql = "SELECT \"transform('a','b','c','d')\" FROM PERSON";

        assertEquals(sql, service.rewrite(sql));
    }

    @Test
    void shouldNotTreatALongerNameAsACall() throws Exception {
        String sql = "SELECT my_transform('a','b','c','d'), transformed('x') FROM DUAL";

        assertEquals(sql, service.rewrite(sql));
        assertEquals(0, service.callCount);
    }

    @Test
    void shouldLeaveAColumnNamedTransformAlone() throws Exception {
        String sql = "SELECT transform FROM PERSON ORDER BY transform";

        assertEquals(sql, service.rewrite(sql));
    }

    // ---------------------------------------------------------------------------------------------
    // Malformed calls
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldRejectACallWithTooFewArguments() {
        FlightSqlTransformSyntaxException error = assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform('Person','p','Jean')"));

        assertTrue(error.getMessage().contains("takes 4 arguments"), error.getMessage());
    }

    @Test
    void shouldRejectACallWithTooManyArguments() {
        assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform('Person','p','CH','Jean','extra')"));
    }

    @Test
    void shouldRejectACallWithNoArgumentAtAll() {
        assertThrows(FlightSqlTransformSyntaxException.class, () -> service.rewrite("SELECT transform()"));
    }

    @Test
    void shouldRejectAParameterPlaceholderAsAnArgument() {
        FlightSqlTransformSyntaxException error = assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform('Person','p','CH',?)"));

        // A prepared statement parameter has no value yet when the query is rewritten.
        assertTrue(error.getMessage().contains("placeholder"), error.getMessage());
    }

    @Test
    void shouldRejectAnUnquotedArgument() {
        assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform(Person,'p','CH','Jean')"));
    }

    @Test
    void shouldRejectAnUnterminatedCall() {
        assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform('Person','p','CH','Jean'"));
    }

    @Test
    void shouldRejectAnUnterminatedLiteral() {
        assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform('Person','p','CH','Jean)"));
    }

    @Test
    void shouldRejectAnEmptyClassNameOrPropertyName() {
        assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform('','p','CH','Jean')"));
        assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform('Person','  ','CH','Jean')"));
    }

    @Test
    void shouldRejectAMissingSeparator() {
        assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform('Person' 'p','CH','Jean')"));
    }

    @Test
    void shouldNotCallTheEngineWhenACallIsMalformed() {
        assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform('Person','p','CH','Jean'),"
                        + " transform('bad')"));

        // The whole statement is rejected: nothing is transformed, nothing is half-rewritten.
        assertEquals(0, service.callCount);
    }

    // ---------------------------------------------------------------------------------------------
    // Phase and contexts
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldRejectTheExtensionWhenTheBeforePhaseIsInactive() {
        config.getBefore().setActive(false);

        FlightSqlTransformSyntaxException error = assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform('Person','p','CH','Jean')"));

        assertTrue(error.getMessage().contains("'before' phase"), error.getMessage());
        assertEquals(0, service.callCount);
    }

    @Test
    void shouldLeaveAStatementWithoutTheExtensionAloneEvenWhenThePhaseIsInactive() throws Exception {
        config.getBefore().setActive(false);
        String sql = "SELECT * FROM PERSON";

        assertEquals(sql, service.rewrite(sql));
    }

    @Test
    void shouldSendTheEvidencesOfTheBeforePhase() {
        config.getBefore().setRightContextEvidences(
                new HashMap<>(Map.of("Target", "WDX1", "Module", "WDX1Proxy")));
        config.getBefore().setProcessingContextEvidences(
                new HashMap<>(Map.of("Action", "Protect", "Target", "WDX1")));

        assertEquals("WDX1", evidence(service.buildRightContext().getEvidences(), "Target"));
        assertEquals("WDX1Proxy", evidence(service.buildRightContext().getEvidences(), "Module"));
        assertEquals("Protect", evidence(service.buildProcessingContext().getEvidences(), "Action"));
    }

    @Test
    void shouldAssumeProtectWhenThePhaseDeclaresNoAction() {
        config.getBefore().setProcessingContextEvidences(new HashMap<>(Map.of("Target", "WDX1")));

        assertEquals("Protect", evidence(service.buildProcessingContext().getEvidences(), "Action"));
    }

    @Test
    void shouldUseAnEmptyPhaseWhenBeforeIsAbsent() throws Exception {
        config.setBefore(null);

        String rewritten = service.rewrite("SELECT transform('Person','p','CH','Jean')");

        // An absent section is an active, empty phase: the default Action carries the transformation.
        assertEquals("SELECT 'TOK(Person.p:Jean)'", rewritten);
        assertEquals("Protect", evidence(service.buildProcessingContext().getEvidences(), "Action"));
    }

    @Test
    void shouldIgnoreTheJurisdictionForNow() throws Exception {
        String withJurisdiction = service.rewrite("SELECT transform('Person','p','CH','Jean')");
        String withAnother = service.rewrite("SELECT transform('Person','p','LU','Jean')");

        assertEquals(withJurisdiction, withAnother);
    }

    // ---------------------------------------------------------------------------------------------
    // Engine failures
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldReportAnEngineFailureAsATransformException() {
        service.failure = new IllegalStateException("RPS is unreachable");

        assertThrows(RPSTransformException.class,
                () -> service.rewrite("SELECT transform('Person','p','CH','Jean')"));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static String evidence(List<Evidence> evidences, String name) {
        return evidences.stream()
                .filter(evidence -> name.equalsIgnoreCase(evidence.getName()))
                .map(Evidence::getValue)
                .findFirst()
                .orElse(null);
    }

    /** Stands in for the engine, recording what it was asked and how many times. */
    private static class RecordingTokenizeService extends FlightSqlTokenizeService {

        private final List<RPSValue> submitted = new ArrayList<>();
        private int callCount;
        private String tokenPrefix = "";
        private RuntimeException failure;

        @Override
        protected void transformValues(RPSValue[] values) throws RPSTransformException {
            callCount++;
            submitted.addAll(Arrays.asList(values));
            if (failure != null) {
                throw new RPSTransformException(failure);
            }
            for (RPSValue value : values) {
                RPSMapping mapping = value.getMapping();
                value.setTransformed(tokenPrefix + "TOK(" + mapping.getClassName() + "."
                        + mapping.getPropertyName() + ":" + value.getOriginal() + ")");
            }
        }
    }
}
