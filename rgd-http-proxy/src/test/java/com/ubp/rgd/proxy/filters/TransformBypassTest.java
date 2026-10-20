package com.ubp.rgd.proxy.filters;

import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransformBypassTest {

    @Test
    void shouldRequestTheBypassOnTheEnabledValueWhateverItsCase() {
        assertTrue(TransformBypass.isRequested(withHeader("true")));
        assertTrue(TransformBypass.isRequested(withHeader("TRUE")));
        assertTrue(TransformBypass.isRequested(withHeader("True")));
    }

    @Test
    void shouldIgnoreTheWhitespaceSurroundingTheValue() {
        assertTrue(TransformBypass.isRequested(withHeader("  true  ")));
    }

    @Test
    void shouldNotRequestTheBypassOnAnyOtherValue() {
        assertFalse(TransformBypass.isRequested(withHeader("false")));
        assertFalse(TransformBypass.isRequested(withHeader("yes")));
        assertFalse(TransformBypass.isRequested(withHeader("1")));
        assertFalse(TransformBypass.isRequested(withHeader("")));
    }

    @Test
    void shouldNotRequestTheBypassWithoutTheHeader() {
        assertFalse(TransformBypass.isRequested(withHeader(null)));
        assertFalse(TransformBypass.isRequested(null));
    }

    @Test
    void shouldDetectTheHeaderWhateverItsValue() {
        assertTrue(TransformBypass.isPresent(withHeader("true")));
        assertTrue(TransformBypass.isPresent(withHeader("false")));
        // An empty header was still sent: the caller did ask for a bypass.
        assertTrue(TransformBypass.isPresent(withHeader("")));
    }

    @Test
    void shouldDetectTheAbsenceOfTheHeader() {
        assertFalse(TransformBypass.isPresent(withHeader(null)));
        assertFalse(TransformBypass.isPresent(null));
    }

    private static ContainerRequestContext withHeader(String value) {
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        when(requestContext.getHeaderString(TransformBypass.HEADER_NAME)).thenReturn(value);
        return requestContext;
    }
}
