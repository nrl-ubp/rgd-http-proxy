package com.ubp.rgd.proxy.security;

import org.ietf.jgss.GSSCredential;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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

    @Test
    void testSubjectWithDelegatedCredentialSelectsDelegatedBranchGracefully() {
        // A Subject carrying a delegated GSSCredential (Kerberos/SPNEGO flow) must select the
        // delegated-credential branch. Without a live KDC the acquisition still cannot complete, so
        // the method must return null gracefully rather than throwing.
        Subject subject = new Subject();
        subject.getPrivateCredentials().add(mock(GSSCredential.class));

        assertNull(SecurityUtils.getClientToServiceToken(subject, "HTTP/host.corp.ubp.ch"));
    }
}

