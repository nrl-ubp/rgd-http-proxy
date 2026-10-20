package com.ubp.rgd.proxy.security;

import com.ubp.rgd.proxy.security.ldap.LdapClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.directory.DirContext;
import javax.security.auth.Subject;
import java.io.File;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;

@ApplicationScoped
public class KerberosToken extends SecurityToken {
    private static final Logger LOG = LoggerFactory.getLogger(KerberosToken.class);

    private static final String NEGOTIATE = "NEGOTIATE ";

    @ConfigProperty(name = "proxy.kerberos.keytab-path")
    String keytabFileName;

    @ConfigProperty(name = "proxy.kerberos.service-principal-name")
    String servicePrincipalName;

    @Inject
    LdapClient ldapClient;

    private byte[] serviceToken;
    private byte[] acceptedToken;


    public KerberosToken() {
        super();
    }

    public byte[] getServiceToken() {
        return this.serviceToken;
    }

    public byte[] getToken() {
        return this.acceptedToken;
    }

    @Override
    public void decode(String b64RawToken) throws Exception {
        LOG.debug("Starting kerb token validation for service principal: {} and with keytab {}", servicePrincipalName, keytabFileName);

        if (b64RawToken.toUpperCase().startsWith(NEGOTIATE)) {
            b64RawToken = b64RawToken.substring(NEGOTIATE.length());
        }

        LOG.debug("Now decoding and verifying kerberos token: {}...", b64RawToken.substring(0, 16));

        byte[] token = Base64.getDecoder().decode(b64RawToken);

        File keytablFile = new File(this.keytabFileName);
        if (!keytablFile.exists()) {
            LOG.error("NOT FOUND KEY TAB FILE : {}", this.keytabFileName);
            return;
        }

        LOG.debug("Login service...");
        Subject serviceSubject = SecurityUtils.loginService(this.keytabFileName, servicePrincipalName);
        assert serviceSubject != null;
        this.serviceToken = SecurityUtils.getServiceTicket(serviceSubject);

        LOG.debug("Decoding token ({} bytes)...", token.length);
        GSSName gssName = Subject.callAs(serviceSubject, (Callable<? extends GSSName>) () -> {
            GSSManager manager = GSSManager.getInstance();
            try {
                // Creating the service name.
                GSSName server = manager.createName(servicePrincipalName, GSSName.NT_HOSTBASED_SERVICE);
                Oid spnegoOid = KerberosConstants.SPNEGO_OID;
                // Create the context and validate the ticket.
                GSSContext context = manager.createContext(server, spnegoOid, null, GSSContext.DEFAULT_LIFETIME);
                this.acceptedToken = context.acceptSecContext(token, 0, token.length);

                GSSName userGssName = context.getSrcName();
                decodeUser(userGssName.toString());

                // Capture the end user's delegated credential (only available when the browser
                // negotiated SPNEGO credential delegation and the service account is trusted for
                // delegation). This is the only way, in the acceptor flow, to later obtain a
                // client-to-service ticket as the user for the downstream API.
                captureDelegatedCredential(context);

                LOG.debug("Now loading AD groups for user: {}", this.user);
                DirContext ctx = ldapClient.login();
                List<String> theRoles = ldapClient.listUserGroups(ctx, this.user);
                assert theRoles != null;
                setRoles(new HashSet<>(theRoles));

                return userGssName;
            } catch(Exception e) {
                LOG.error("Error during ticket processing", e);
            }
            // Null if an error occurred.
            return null;
        });

        if (gssName == null) {
            LOG.error("Error while decoding token.");
        } else {
            LOG.debug("Decoded user info: {}", this.user);
        }
    }

    /**
     * Capture the end user's delegated credential from the accepted SPNEGO context and store it in
     * {@link #userSubject} as a private credential, so a downstream client-to-service ticket can be
     * obtained as the user (see
     * {@link SecurityUtils#getClientToServiceToken(Subject, String)}).
     * <p>
     * A delegated credential is only present when the browser negotiated SPNEGO credential
     * delegation (forwardable TGT) and the service account is trusted for delegation. When it is
     * absent, {@code userSubject} is left null and the caller falls back to the delegation-enabled
     * path.
     *
     * @param context the established acceptor GSSContext
     */
    private void captureDelegatedCredential(GSSContext context) {
        try {
            GSSCredential delegatedCred = context.getDelegCred();
            if (delegatedCred == null) {
                LOG.warn("No delegated credential available for user {}; " +
                        "downstream call as this user will not be possible (credential delegation not negotiated).",
                        this.user);
                return;
            }

            Subject subject = new Subject();
            subject.getPrivateCredentials().add(delegatedCred);
            this.userSubject = subject;
            LOG.debug("Captured delegated credential for user: {}", this.user);
        } catch (Exception e) {
            LOG.error("Could not capture the delegated credential from the SPNEGO context.", e);
        }
    }

    void decodeUser(String userName) throws IndexOutOfBoundsException {
        int index = userName.indexOf("@");
        if (index > 0) {
            this.user = userName.substring(0, index);
            this.domainName = userName.substring(index + 1);
        } else {
            this.user = userName;
            this.domainName = "";
            this.hostname = "";
        }
    }
}
