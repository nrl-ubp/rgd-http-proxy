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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code transform()} and {@code transform_search()} SQL extensions: what the scanner
 * recognizes, what it refuses, and what reaches the engine.
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
    // The search variant
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldReplaceASearchCallByATruncatedLikePattern() throws Exception {
        String rewritten = service.rewrite(
                "SELECT * FROM PERSON WHERE FIRST_NAME LIKE"
                        + " transform_search('Person','shortString','CH','Jean')");

        // "TOK(Person.shortString:Jean)" truncated after its 9th character, then the wildcard.
        assertEquals("SELECT * FROM PERSON WHERE FIRST_NAME LIKE 'TOK(Perso%'", rewritten);
    }

    @Test
    void shouldTokenizeTheTextOfASearchCallLikeAnyOther() throws Exception {
        service.rewrite("SELECT transform_search('Person','firstName','CH','Jean')");

        // The search variant differs only in the substitution: the engine sees the same value.
        assertEquals(1, service.callCount);
        assertEquals("Jean", service.submitted.get(0).getOriginal());
        assertEquals("Person", service.submitted.get(0).getMapping().getClassName());
        assertEquals("firstName", service.submitted.get(0).getMapping().getPropertyName());
    }

    @Test
    void shouldKeepATokenShorterThanThePrefixWhole() throws Exception {
        service.fixedToken = "RG{12}";

        assertEquals("SELECT 'RG{12}%'",
                service.rewrite("SELECT transform_search('Person','p','CH','Jean')"));
    }

    @Test
    void shouldNotTruncateATokenOfExactlyThePrefixLength() throws Exception {
        service.fixedToken = "RG{123456";

        assertEquals(9, service.fixedToken.length());
        assertEquals("SELECT 'RG{123456%'",
                service.rewrite("SELECT transform_search('Person','p','CH','Jean')"));
    }

    @Test
    void shouldEscapeAQuoteOfTheProducedPattern() throws Exception {
        service.fixedToken = "it's a token";

        // The quote is doubled inside the pattern, exactly as for a plain token.
        assertEquals("SELECT 'it''s a to%'",
                service.rewrite("SELECT transform_search('Person','p','CH','x')"));
    }

    @Test
    void shouldRecognizeTheSearchNameInAnyCaseAndWithSpaces() throws Exception {
        String rewritten = service.rewrite(
                "SELECT * FROM PERSON WHERE NAME LIKE Transform_Search ('Person','p','CH','Jean')");

        assertEquals("SELECT * FROM PERSON WHERE NAME LIKE 'TOK(Perso%'", rewritten);
    }

    // ---------------------------------------------------------------------------------------------
    // Wildcards inside the token
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldEscapeAPercentOfTheTokenAndDeclareTheEscapeCharacter() throws Exception {
        service.fixedToken = "RG{10%20}";

        // Left alone, the percent would match anything and widen the search.
        assertEquals("SELECT 'RG{10\\%20}%' ESCAPE '\\'",
                service.rewrite("SELECT transform_search('Person','p','CH','x')"));
    }

    @Test
    void shouldEscapeAnUnderscoreOfTheToken() throws Exception {
        service.fixedToken = "RG{a_b_c}";

        assertEquals("SELECT 'RG{a\\_b\\_c}%' ESCAPE '\\'",
                service.rewrite("SELECT transform_search('Person','p','CH','x')"));
    }

    @Test
    void shouldEscapeTheEscapeCharacterItselfWhenTheClauseIsEmitted() throws Exception {
        service.fixedToken = "RG{a\\%b}";

        // Once the clause is there, a backslash of the token would swallow the character after it.
        assertEquals("SELECT 'RG{a\\\\\\%b}%' ESCAPE '\\'",
                service.rewrite("SELECT transform_search('Person','p','CH','x')"));
    }

    @Test
    void shouldLeaveABackslashAloneWhenNoClauseIsEmitted() throws Exception {
        service.fixedToken = "RG{a\\b}";

        // Without the clause a backslash is an ordinary character: escaping it would change the match.
        assertEquals("SELECT 'RG{a\\b}%'",
                service.rewrite("SELECT transform_search('Person','p','CH','x')"));
    }

    @Test
    void shouldNotEmitTheEscapeClauseWhenTheTokenHoldsNoWildcard() throws Exception {
        String rewritten = service.rewrite("SELECT transform_search('Person','p','CH','Jean')");

        // The clause is valid in a LIKE predicate and nowhere else, so it is only added when needed.
        assertFalse(rewritten.contains("ESCAPE"), rewritten);
    }

    @Test
    void shouldOnlyEscapeTheWildcardsOfThePrefixNotThoseBeyondIt() throws Exception {
        service.fixedToken = "RG{123456789%}";

        // The percent sits past the 9th character, so it is truncated away rather than escaped.
        assertEquals("SELECT 'RG{123456%'",
                service.rewrite("SELECT transform_search('Person','p','CH','x')"));
    }

    @Test
    void shouldEscapeAWildcardAndAQuoteTogether() throws Exception {
        service.fixedToken = "it's 50%";

        assertEquals("SELECT 'it''s 50\\%%' ESCAPE '\\'",
                service.rewrite("SELECT transform_search('Person','p','CH','x')"));
    }

    @Test
    void shouldNotEscapeTheWildcardOfAPlainTransformCall() throws Exception {
        service.fixedToken = "RG{10%20}";

        // A plain token is a value, not a pattern: it is quoted as it is.
        assertEquals("SELECT 'RG{10%20}'",
                service.rewrite("SELECT transform('Person','p','CH','x')"));
    }

    @Test
    void shouldNotMistakeASearchCallForAPlainTransformCall() throws Exception {
        // "transform" is the prefix of "transform_search": the whole-word rule must not split it.
        String rewritten = service.rewrite("SELECT transform_search('Person','p','CH','Jean')");

        assertTrue(rewritten.endsWith("%'"), rewritten);
        assertEquals(1, service.callCount);
    }

    @Test
    void shouldNotTreatALongerNameThanTheSearchVariantAsACall() throws Exception {
        String sql = "SELECT transform_searches('a','b','c','d'), my_transform_search('x') FROM DUAL";

        assertEquals(sql, service.rewrite(sql));
        assertEquals(0, service.callCount);
    }

    @Test
    void shouldLeaveAColumnNamedTransformSearchAlone() throws Exception {
        String sql = "SELECT transform_search FROM PERSON ORDER BY transform_search";

        assertEquals(sql, service.rewrite(sql));
    }

    @Test
    void shouldMixBothFunctionsInASingleEngineCall() throws Exception {
        String rewritten = service.rewrite(
                "SELECT * FROM PERSON WHERE LAST_NAME = transform('Person','lastName','CH','Dusse')"
                        + " AND FIRST_NAME LIKE transform_search('Person','firstName','CH','Jean')");

        assertEquals(1, service.callCount);
        assertEquals(2, service.submitted.size());
        assertEquals("SELECT * FROM PERSON WHERE LAST_NAME = 'TOK(Person.lastName:Dusse)'"
                + " AND FIRST_NAME LIKE 'TOK(Perso%'", rewritten);
    }

    @Test
    void shouldKeepTheOffsetsValidWhenASearchCallPrecedesAPlainOne() throws Exception {
        // The pattern is shorter than the token it replaces, so a left-to-right substitution would
        // shift the following call.
        String rewritten = service.rewrite(
                "SELECT transform_search('Person','p','CH','Jean'), transform('Person','q','CH','Dusse')");

        assertEquals("SELECT 'TOK(Perso%', 'TOK(Person.q:Dusse)'", rewritten);
    }

    @Test
    void shouldNotRewriteASearchCallWrittenInsideAStringLiteralOrAComment() throws Exception {
        String sql = "SELECT 'transform_search(1)' -- transform_search('a','b','c','d')\nFROM DUAL";

        assertEquals(sql, service.rewrite(sql));
        assertEquals(0, service.callCount);
    }

    @Test
    void shouldNameTheSearchFunctionInTheErrorOfAMalformedCall() {
        FlightSqlTransformSyntaxException error = assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform_search('Person','p','Jean')"));

        // Reporting the error against transform() would send the user looking at the wrong call.
        assertTrue(error.getMessage().contains("transform_search()"), error.getMessage());
    }

    @Test
    void shouldRejectTheSearchVariantWhenTheBeforePhaseIsInactive() {
        config.getBefore().setActive(false);

        assertThrows(FlightSqlTransformSyntaxException.class,
                () -> service.rewrite("SELECT transform_search('Person','p','CH','Jean')"));
        assertEquals(0, service.callCount);
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
        private String fixedToken;
        private RuntimeException failure;

        @Override
        protected void transformValues(RPSValue[] values) throws RPSTransformException {
            callCount++;
            submitted.addAll(Arrays.asList(values));
            if (failure != null) {
                throw new RPSTransformException(failure);
            }
            for (RPSValue value : values) {
                if (fixedToken != null) {
                    value.setTransformed(fixedToken);
                    continue;
                }
                RPSMapping mapping = value.getMapping();
                value.setTransformed(tokenPrefix + "TOK(" + mapping.getClassName() + "."
                        + mapping.getPropertyName() + ":" + value.getOriginal() + ")");
            }
        }
    }
}
