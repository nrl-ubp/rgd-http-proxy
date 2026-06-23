package com.ubp.rgd.proxy.security;

import com.ubp.rgd.proxy.security.ldap.LdapClient;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.jboss.logging.Logger;

import javax.naming.directory.DirContext;
import javax.security.auth.Subject;
import java.io.File;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;

@ApplicationScoped
public class KerberosToken extends SecurityToken {
    private static final Logger LOGGER = Logger.getLogger(KerberosToken.class.getName());

    private static final String NEGOTIATE = "NEGOTIATE ";

    @ConfigProperty(name = "proxy.kerberos.keytab-path")
    String keytabFileName;

    @ConfigProperty(name = "proxy.kerberos.service-principal-name")
    String servicePrincipalName;

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
        LOGGER.infof("Starting kerb token validation for service principal: %s and with keytab %s", servicePrincipalName, keytabFileName);

        if (b64RawToken.toUpperCase().startsWith(NEGOTIATE)) {
            b64RawToken = b64RawToken.substring(NEGOTIATE.length());
        }

        LOGGER.infof("Now decoding and verifying kerberos token: %s...", b64RawToken.substring(0, 16));

        byte[] token = Base64.getDecoder().decode(b64RawToken);

        File keytablFile = new File(this.keytabFileName);
        if (!keytablFile.exists()) {
            LOGGER.errorf("NOT FOUND KEY TAB FILE : %s", this.keytabFileName);
            return;
        }

        LOGGER.info("Login service...");
        Subject serviceSubject = SecurityUtils.loginService(this.keytabFileName, servicePrincipalName);
        assert serviceSubject != null;
        this.serviceToken = SecurityUtils.getServiceTicket(serviceSubject);

        LOGGER.infof("Decoding token (%d bytes)...", token.length);
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

                LOGGER.infof("Now loading AD groups for user: %s", this.user);
                DirContext ctx = LdapClient.login(KerberosConstants.DEFAULT_LDAP_URL);
                List<String> theRoles = LdapClient.listUserGroups(ctx, KerberosConstants.DEFAULT_BASE_DN, this.user);
                assert theRoles != null;
                setRoles(new HashSet<>(theRoles));

                return userGssName;
            } catch(Exception e) {
                LOGGER.error("Error during ticket processing", e);
            }
            // Null if an error occurred.
            return null;
        });

        if (gssName == null) {
            LOGGER.error("Error while decoding token.");
        } else {
            LOGGER.infof("Decoded user info: %s", this.user);
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
