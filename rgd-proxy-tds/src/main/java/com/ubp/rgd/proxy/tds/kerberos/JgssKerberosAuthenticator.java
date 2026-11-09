package com.ubp.rgd.proxy.tds.kerberos;

import com.ubp.rgd.proxy.kerberos.KerberosLogin;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.login.LoginException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;

/**
 * {@link KerberosAuthenticator} on JGSS: the gateway logs in from its keytab (as initiator too, which
 * S4U2Proxy needs), accepts SPNEGO tokens of clients, and initiates SPNEGO towards the database server
 * with the credential the acceptor obtained for the client.
 * <p>
 * The client must use a ticket for the SPN of the gateway. The account of the gateway must be trusted
 * in Active Directory for constrained delegation to the SPN of the database server.
 */
public final class JgssKerberosAuthenticator implements KerberosAuthenticator {

    private static final Logger LOG = LoggerFactory.getLogger(JgssKerberosAuthenticator.class);

    /** The service login is renewed this long before its ticket expires. */
    private static final Duration RENEWAL_MARGIN = Duration.ofMinutes(5);

    private final String keytab;
    private final String servicePrincipal;
    private final String backendPrincipal;
    private Subject subject;

    public JgssKerberosAuthenticator(String keytab, String servicePrincipal, String backendPrincipal) {
        this.keytab = keytab;
        this.servicePrincipal = servicePrincipal;
        this.backendPrincipal = backendPrincipal;
    }

    private synchronized Subject subject() throws KerberosLoginException {
        if (subject == null || expiresSoon(subject)) {
            try {
                Subject renewed = KerberosLogin.loginService(keytab, servicePrincipal);
                if (renewed == null) {
                    throw new KerberosLoginException("The Kerberos login of the gateway as " + servicePrincipal + " failed");
                }
                subject = renewed;
                LOG.info("TDS gateway logged in to Kerberos as {}", servicePrincipal);
            } catch (LoginException e) {
                throw new KerberosLoginException("The Kerberos login of the gateway as " + servicePrincipal
                        + " failed: " + e.getMessage(), e);
            }
        }
        return subject;
    }

    private static boolean expiresSoon(Subject subject) {
        Instant limit = Instant.now().plus(RENEWAL_MARGIN);
        return subject.getPrivateCredentials(KerberosTicket.class).stream()
                .filter(ticket -> ticket.getServer().getName().startsWith("krbtgt/"))
                .noneMatch(ticket -> ticket.getEndTime() != null && ticket.getEndTime().toInstant().isAfter(limit));
    }

    @Override
    public Exchange start() throws KerberosLoginException {
        return new JgssExchange(subject());
    }

    private final class JgssExchange implements Exchange {
        private final Subject serviceSubject;
        private final GSSManager manager = GSSManager.getInstance();
        private GSSContext acceptor;
        private GSSContext initiator;
        private String clientName;

        JgssExchange(Subject serviceSubject) {
            this.serviceSubject = serviceSubject;
        }

        @Override
        public byte[] accept(byte[] clientToken) throws KerberosLoginException {
            byte[] answer = call(() -> {
                acceptor = manager.createContext((GSSCredential) null);
                return acceptor.acceptSecContext(clientToken, 0, clientToken.length);
            }, "The integrated login of the client could not be accepted: only Kerberos is supported, with a"
                    + " ticket for the SPN " + servicePrincipal + " (NTLM is refused)");
            if (!acceptor.isEstablished()) {
                throw new KerberosLoginException("The integrated login of the client needs more than one round trip:"
                        + " only Kerberos is supported (NTLM is refused). Check that the client asks a ticket for "
                        + servicePrincipal);
            }
            try {
                clientName = acceptor.getSrcName().toString();
            } catch (GSSException e) {
                clientName = "?";
            }
            return answer == null ? new byte[0] : answer;
        }

        @Override
        public String clientName() {
            return clientName;
        }

        @Override
        public byte[] initiateBackend() throws KerberosLoginException {
            GSSCredential delegated;
            try {
                delegated = acceptor.getDelegCred();
            } catch (GSSException e) {
                delegated = null;
            }
            if (delegated == null) {
                throw new KerberosLoginException("No credential of " + clientName + " can be delegated to the"
                        + " database server: the account of " + servicePrincipal + " must be trusted for"
                        + " constrained delegation to " + backendPrincipal);
            }
            GSSCredential credential = delegated;
            byte[] token = call(() -> {
                GSSName server = manager.createName(backendPrincipal, KerberosLogin.krb5PrincipalOid());
                initiator = manager.createContext(server, KerberosLogin.spnegoOid(), credential,
                        GSSContext.DEFAULT_LIFETIME);
                initiator.requestMutualAuth(true);
                return initiator.initSecContext(new byte[0], 0, 0);
            }, "The gateway could not obtain a ticket of " + clientName + " for " + backendPrincipal
                    + " (constrained delegation)");
            return token == null ? new byte[0] : token;
        }

        @Override
        public byte[] continueBackend(byte[] serverToken) throws KerberosLoginException {
            if (initiator == null || initiator.isEstablished()) {
                return new byte[0];
            }
            byte[] token = call(() -> initiator.initSecContext(serverToken, 0, serverToken.length),
                    "The database server's answer to the Kerberos login could not be processed");
            return token == null ? new byte[0] : token;
        }

        private byte[] call(Callable<byte[]> action, String failure) throws KerberosLoginException {
            try {
                return Subject.callAs(serviceSubject, action);
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                LOG.warn("{}: {}", failure, cause.getMessage());
                throw new KerberosLoginException(failure + ": " + cause.getMessage(), cause);
            }
        }

        @Override
        public void close() {
            dispose(acceptor);
            dispose(initiator);
        }

        private void dispose(GSSContext context) {
            if (context != null) {
                try {
                    context.dispose();
                } catch (GSSException e) {
                    LOG.debug("Cannot dispose a GSS context: {}", e.getMessage());
                }
            }
        }
    }
}
