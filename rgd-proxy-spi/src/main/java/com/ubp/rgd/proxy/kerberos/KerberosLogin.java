package com.ubp.rgd.proxy.kerberos;

import com.sun.security.auth.module.Krb5LoginModule;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.Oid;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;
import java.util.HashMap;
import java.util.Map;

/**
 * Stateless Kerberos helpers shared by the modules authenticating with a service keytab: the hub
 * (SPNEGO on HTTP) and the TDS gateway (integrated security of SQL Server clients).
 */
public final class KerberosLogin {

    /** The SPNEGO mechanism, as negotiated by HTTP Negotiate and by SQL Server SSPI. */
    public static final String SPNEGO_MECHANISM = "1.3.6.1.5.5.2";

    /** The name type of a Kerberos principal name, such as {@code MSSQLSvc/host.domain:1433}. */
    public static final String KRB5_PRINCIPAL = "1.2.840.113554.1.2.2.1";

    private KerberosLogin() {
    }

    public static Oid spnegoOid() {
        return oid(SPNEGO_MECHANISM);
    }

    public static Oid krb5PrincipalOid() {
        return oid(KRB5_PRINCIPAL);
    }

    private static Oid oid(String value) {
        try {
            return new Oid(value);
        } catch (GSSException e) {
            throw new IllegalStateException("Invalid OID " + value, e);
        }
    }

    /**
     * Log a service in from its keytab.
     * <p>
     * The service is logged in as an initiator too, with a forwardable ticket, which is what the
     * constrained delegation (S4U2Proxy) of the credentials it accepts requires.
     *
     * @param keyTabFile       the keytab of the service
     * @param servicePrincipal the principal of the service in the keytab
     * @return the subject holding the keys and the ticket of the service, or {@code null} when the
     *         login module refused the login
     * @throws LoginException when the login fails
     */
    public static Subject loginService(String keyTabFile, String servicePrincipal) throws LoginException {
        Subject subject = new Subject();
        Krb5LoginModule krb5LoginModule = new Krb5LoginModule();
        krb5LoginModule.initialize(subject, null, new HashMap<>(), loginOptions(keyTabFile, servicePrincipal));
        if (!krb5LoginModule.login() || !krb5LoginModule.commit()) {
            return null;
        }
        return subject;
    }

    static Map<String, String> loginOptions(String keyTabFile, String servicePrincipal) {
        Map<String, String> options = new HashMap<>();
        options.put("keyTab", keyTabFile);
        options.put("principal", servicePrincipal);
        options.put("doNotPrompt", "true");
        options.put("refreshKrb5Config", "true");
        options.put("useTicketCache", "false");
        options.put("forwardable", "true");
        options.put("renewable", "false");
        options.put("useKeyTab", "true");
        options.put("storeKey", "true");
        // Needed for the delegation.
        options.put("isInitiator", "true");
        options.put("debug", "false");
        return options;
    }
}
