package com.ubp.rgd.proxy.security;

import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guard-level tests for {@link SecurityUtils#getClientToServiceToken(Subject, String)}.
 * <p>
 * A full, successful acquisition requires a live KDC (TGS-REQ), so it is verified through a manual
 * integration run. These tests cover the defensive behavior that must hold without any Kerberos
 * infrastructure: invalid inputs return {@code null} rather than throwing.
 */
class SecurityUtilsClientToServiceTest {

    @Test
    void testNullSubjectReturnsNull() {
        assertNull(SecurityUtils.getClientToServiceToken(null, "HTTP/host.corp.ubp.ch"));
    }

    @Test
    void testNullSpnReturnsNull() {
        assertNull(SecurityUtils.getClientToServiceToken(new Subject(), null));
    }

    @Test
    void testEmptySpnReturnsNull() {
        assertNull(SecurityUtils.getClientToServiceToken(new Subject(), ""));
    }

    @Test
    void testSubjectWithoutTgtReturnsNullNotThrow() {
        // An empty Subject holds no TGT, so no client-to-service ticket can be issued; the method
        // must fail gracefully (null) instead of propagating a GSSException.
        assertNull(SecurityUtils.getClientToServiceToken(new Subject(), "HTTP/host.corp.ubp.ch"));
    }
}
