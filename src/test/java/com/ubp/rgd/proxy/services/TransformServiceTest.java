package com.ubp.rgd.proxy.services;

import ch.regdata.rps.engine.client.model.api.value.RPSValue;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.transform.ValuePlan;
import com.ubp.rgd.proxy.transform.api.TransformRequest;
import com.ubp.rgd.proxy.transform.api.TransformValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the segmentation / reassembly seam of {@link TransformService} (no live RPS engine).
 */
class TransformServiceTest {

    private final TransformService service = new TransformService();

    private TransformValue value(String v, String cls, String prop, String regex) {
        TransformValue tv = new TransformValue();
        tv.setValue(v);
        tv.setClassName(cls);
        tv.setPropertyName(prop);
        tv.setExtractRegExp(regex);
        return tv;
    }

    private List<String> originals(ValuePlan plan) {
        return plan.rpsValues().stream().map(RPSValue::getOriginal).toList();
    }

    @Test
    void testProtectWithExtractRegexSplitsWordByWord() throws RPSTransformException {
        TransformValue tv = value("John Doe", "Person", "Name", "\\w+");

        ValuePlan plan = service.buildValuePlan("Protect", tv);

        assertEquals(List.of("John", "Doe"), originals(plan));
        // className / propertyName carried onto each segment's mapping.
        assertEquals("Person", plan.rpsValues().get(0).getMapping().getClassName());
        assertEquals("Name", plan.rpsValues().get(0).getMapping().getPropertyName());
    }

    @Test
    void testProtectWithoutRegexIsSingleSegment() throws RPSTransformException {
        TransformValue tv = value("John Doe", "Person", "Name", null);

        ValuePlan plan = service.buildValuePlan("Protect", tv);

        assertEquals(List.of("John Doe"), originals(plan));
    }

    @Test
    void testUnprotectSplitsOnTokenDelimiter() throws RPSTransformException {
        // extract-regex is ignored for unprotect; tokens located by the fixed RG{...} delimiter.
        TransformValue tv = value("RG{aaa} RG{bbb}", "Person", "Name", "\\w+");

        ValuePlan plan = service.buildValuePlan("Unprotect", tv);

        assertEquals(List.of("RG{aaa}", "RG{bbb}"), originals(plan));
    }

    @Test
    void testReassembleProtectPreservesSeparators() throws RPSTransformException {
        TransformValue tv = value("John Doe", "Person", "Name", "\\w+");
        ValuePlan plan = service.buildValuePlan("Protect", tv);

        plan.rpsValues().get(0).setTransformed("RG{111}");
        plan.rpsValues().get(1).setTransformed("RG{222}");

        assertEquals("RG{111} RG{222}", plan.reassemble());
    }

    @Test
    void testReassembleUnprotectReplacesTokensInPlace() throws RPSTransformException {
        TransformValue tv = value("Mr RG{aaa}-RG{bbb}!", "Person", "Name", null);
        ValuePlan plan = service.buildValuePlan("Unprotect", tv);

        plan.rpsValues().get(0).setTransformed("John");
        plan.rpsValues().get(1).setTransformed("Doe");

        assertEquals("Mr John-Doe!", plan.reassemble());
    }

    @Test
    void testReassembleSingleSegmentReturnsTransformed() throws RPSTransformException {
        TransformValue tv = value("secret", "Person", "Name", null);
        ValuePlan plan = service.buildValuePlan("Protect", tv);

        plan.rpsValues().get(0).setTransformed("RG{zzz}");

        assertEquals("RG{zzz}", plan.reassemble());
    }

    @Test
    void testReassembleNoMatchReturnsOriginalUnchanged() throws RPSTransformException {
        // Protect regex that matches nothing in the value -> value returned unchanged.
        TransformValue tv = value("!!!", "Person", "Name", "\\w+");
        ValuePlan plan = service.buildValuePlan("Protect", tv);

        assertTrue(plan.rpsValues().isEmpty());
        assertEquals("!!!", plan.reassemble());
    }

    @Test
    void testReassembleNullTransformedThrows() throws RPSTransformException {
        TransformValue tv = value("John", "Person", "Name", null);
        ValuePlan plan = service.buildValuePlan("Protect", tv);
        // getTransformed() left null (no engine call) -> reassemble must fail clearly.
        assertThrows(RPSTransformException.class, plan::reassemble);
    }

    @Test
    void testNullRequestThrows() {
        assertThrows(RPSTransformException.class, () -> service.transform(null));
    }

    @Test
    void testNullSetsThrows() {
        TransformRequest request = new TransformRequest();
        request.setSets(null);
        assertThrows(RPSTransformException.class, () -> service.transform(request));
    }

    @Test
    void testNullValueThrows() {
        assertThrows(RPSTransformException.class,
                () -> service.buildValuePlan("Protect", value(null, "Person", "Name", null)));
    }

    @Test
    void testParseAuthorizedListTrimsLowercasesAndDropsEmpties() {
        Set<String> parsed = TransformService.parseAuthorizedList("  HTTP/svc1 , user2 ,, USER3 ");
        assertEquals(Set.of("http/svc1", "user2", "user3"), parsed);
    }

    @Test
    void testParseAuthorizedListEmptyOrNull() {
        assertTrue(TransformService.parseAuthorizedList(null).isEmpty());
        assertTrue(TransformService.parseAuthorizedList("   ").isEmpty());
    }

    @Test
    void testIsAuthorizedIsCaseInsensitive() {
        Set<String> authorized = TransformService.parseAuthorizedList("HTTP/svc1,user2");
        assertTrue(TransformService.isAuthorized("HTTP/svc1", authorized));
        assertTrue(TransformService.isAuthorized("http/SVC1", authorized));
        assertTrue(TransformService.isAuthorized("USER2", authorized));
        assertFalse(TransformService.isAuthorized("intruder", authorized));
        assertFalse(TransformService.isAuthorized(null, authorized));
    }

    @Test
    void testCheckAuthorizationSkippedWhenAuthDisabled() {
        // With auth globally disabled, the check must be a no-op (no security context needed).
        service.preFilterAuthEnabled = "false";
        assertDoesNotThrow(service::checkAuthorization);
    }
}
