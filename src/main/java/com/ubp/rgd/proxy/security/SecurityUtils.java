package com.ubp.rgd.proxy.security;

import com.sun.security.auth.module.Krb5LoginModule;
import com.sun.security.jgss.ExtendedGSSContext;
import org.ietf.jgss.*;
import org.jboss.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosTicket;
import java.io.File;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Please see <a href="https://stackoverflow.com/questions/39743700/java-spnego-authentication-kerberos-constrained-delegation-kcd-to-backend-se">...</a>
 * for more explanations.
 */
@SuppressWarnings("restriction")
public class SecurityUtils {

    private static final Logger LOGGER = Logger.getLogger(SecurityUtils.class.getName());

    private static final String jaasConfigFile = "./config/login.conf";
    private static final String krb5ConfFile = "./config/krb5.ini";

    private static final String technicalUser = "SA_GVA_RGD_EVX";
    private static final int MAX_ATTEMPT = 2;

    public static void setup() {
        LOGGER.debug("KRB5 init...");

        File configFile = new File(jaasConfigFile);
        if (!configFile.exists()) {
            LOGGER.error("ERROR : Config file not found : " + jaasConfigFile);
        }
        System.setProperty("java.security.auth.login.config", jaasConfigFile);

        configFile = new File(krb5ConfFile);
        if (!configFile.exists()) {
            LOGGER.error("ERROR : Config file not found : " + krb5ConfFile);
        }
        System.setProperty("java.security.krb5.conf", krb5ConfFile);
    }

    public static Subject loginService(String keyTabFile, String servicePrincipal) throws Exception {
        Subject subject = new Subject();
        Krb5LoginModule krb5LoginModule = new Krb5LoginModule();
        Map<String, String> optionMap = getLoginOptionsMap(keyTabFile, servicePrincipal);

        krb5LoginModule.initialize(subject, null, new HashMap<String, String>(), optionMap);

        if (!krb5LoginModule.login()) {
            LOGGER.error("Service login failed !");
            return null;
        }

        if (!krb5LoginModule.commit()) {
            LOGGER.error("Service login commit failed !");
            return null;
        }

        return subject;
    }

    private static Map<String, String> getLoginOptionsMap(String keyTabFile, String servicePrincipal) {
        Map<String, String> optionMap = new HashMap<>();

        optionMap.put("keyTab", keyTabFile);
        optionMap.put("principal", servicePrincipal); // SPN you mapped to the service user while creating the keytab file
        optionMap.put("doNotPrompt", "true");
        optionMap.put("refreshKrb5Config", "true");
        optionMap.put("useTicketCache", "false");
        optionMap.put("forwardable", "true");
        optionMap.put("renewable", "false");
        optionMap.put("useKeyTab", "true");
        optionMap.put("storeKey", "true");
        optionMap.put("isInitiator", "true"); // needed for delegation
        optionMap.put("debug", "true"); // trace will be printed on console

        return optionMap;
    }

    public static GSSCredential validateTicket(byte[] token, Subject serviceSubject) throws Exception {
        return Subject.callAs(serviceSubject, new KerberosValidateCallable(token));
    }

    public static Object getServiceTicket(GSSCredential clientCred, String servicePrincipalName) {
        Callable<Object> call = () -> {
            GSSManager manager = GSSManager.getInstance();
            // service to which the service user is allowed to delegate credentials
            GSSName servicePrincipal = manager.createName(servicePrincipalName, KerberosConstants.KRB5_PRINCIPAL_OID);
            ExtendedGSSContext extendedContext = (ExtendedGSSContext) manager.createContext(servicePrincipal,
                    KerberosConstants.SPNEGO_OID, clientCred, GSSContext.DEFAULT_LIFETIME);
            extendedContext.requestCredDeleg(true);
            extendedContext.requestMutualAuth(false);
            extendedContext.requestReplayDet(false);

            // this token is the end user's TGS for "HTTP/TEST" service, you can pass this to the actual HTTP/TEST service endpoint in "Authorization" header.
            byte[] token = new byte[0];
            token = extendedContext.initSecContext(token, 0, token.length);

            return token;
        };

        return Subject.callAs(new Subject(), call);
    }

    static GSSCredential getServerCredential(final Subject subject, final GSSName serverSpn) throws Exception {
        final Callable<GSSCredential> action = () -> {
                    GSSManager manager = GSSManager.getInstance();
                    Oid oid = new Oid(KerberosConstants.SPNEGO_MECHANISM);
                    return manager.createCredential(
                            serverSpn
                            , GSSCredential.INDEFINITE_LIFETIME
                            , oid
                            , GSSCredential.INITIATE_AND_ACCEPT);
                };
        return Subject.callAs(subject, action);
    }

    /**
     * Get the encoded byte[] of the service kerberos ticket.
     * @param serviceSubject initialized service subject (obtained after service login from keytab)
     * @return byte array representation of the kerberos ticket from the credentials of the subject
     * @see #loginService(String, String)
     */
    public static byte[] getServiceTicket(Subject serviceSubject) {
        KerberosTicket serviceTicket = null;
        for(Object obj : serviceSubject.getPrivateCredentials()) {
            System.out.println(obj.getClass().getName());
            if (obj instanceof KerberosTicket) {
                serviceTicket = (KerberosTicket)obj;
            }
        }
        return serviceTicket == null ? null : serviceTicket.getEncoded();
    }

    private static byte[] createServiceTokenTechnicalUSer(String serviceName, int attempts, Oid oid) throws Exception {

        LOGGER.info("createServiceTokenTechnicalUSer(" + serviceName + ") for User " + technicalUser);

        try {
            GSSManager manager = GSSManager.getInstance();
            GSSName gssUserName = manager.createName(technicalUser, GSSName.NT_USER_NAME);
            LOGGER.debug("gssUserName: " + gssUserName.toString());
            GSSCredential clientGSSCreds = manager.createCredential(gssUserName, GSSCredential.INDEFINITE_LIFETIME, oid,
                    GSSCredential.INITIATE_ONLY);
            LOGGER.debug("clientGSSCreds: " + clientGSSCreds.toString());
            GSSName gssServerName = manager.createName(serviceName, GSSName.NT_USER_NAME);
            LOGGER.debug("gssServerName: " + gssServerName.toString());
            GSSContext clientContext = manager.createContext(gssServerName, oid, clientGSSCreds,
                    GSSContext.DEFAULT_LIFETIME);
            // Optional: enable GSS credential delegation
            clientContext.requestCredDeleg(true);
            clientContext.requestReplayDet(false);
            // Create a SPNEGO token for the target server
            byte[] serviceToken = new byte[0];

            serviceToken = clientContext.initSecContext(serviceToken, 0, serviceToken.length);

            String result = new String(Base64.getEncoder().encode(serviceToken));
            LOGGER.debug("Token for technical user: " + result);

            return serviceToken;

        } catch (Exception e) {
            LOGGER.error("Error getting token to for " + e.getMessage(), e);
            if (attempts == 0) {
                LOGGER.error("Cannot create kerberos token", e);
                throw e;
            }
            --attempts;
            LOGGER.warn("Error during ticket generation. Attempt: " + (MAX_ATTEMPT - attempts));
            return createServiceTokenTechnicalUSer(serviceName, attempts, oid);
        }
    }

    /**
     * Delegates the token from the end user to the service token using kerberos.
     * Please read carrefully this post to understand what's going on:
     * <a href="https://stackoverflow.com/questions/39743700/java-spnego-authentication-kerberos-constrained-delegation-kcd-to-backend-se">
     *     Stackoverflow</a>
     */
    public static String getDelegationTicket(String userTokenStr, String kerbServicePrincipal, String keytabFilePath, String targetServicePrincipal) {
        try {

            // STEP 1
            // Login using service user credentials and get its TGT

            File keyTabFile = new File(keytabFilePath);
            if (!keyTabFile.exists() || !keyTabFile.canRead()) {
                String message = "Keytab file does not exist or is not readable: " + keyTabFile;
                LOGGER.error(message);
                return null;
            }

            if (kerbServicePrincipal == null || kerbServicePrincipal.isEmpty()) {
                String message = "Keytab principal name is not found in the config !";
                LOGGER.error(message);
                return null;
            }

            Subject serviceSubject = SecurityUtils.loginService(keytabFilePath, kerbServicePrincipal);

            if (serviceSubject == null) {
                LOGGER.error("Could NOT get service subject : " + keytabFilePath + " for " + kerbServicePrincipal);
                return null;
            }

            LOGGER.debug("We have the security subject for service !");

            // STEP 1.1
            // Get end user token from request
            byte[] endUserTicket = SecurityUtils.getEndUserTicket(userTokenStr);
            byte[] serviceToken;
            if (endUserTicket == null || endUserTicket.length == 0) {
                return null;
            }
            // STEP 2
            // Use login context of this service user, accept the kerberos token (TGS) coming from end user
            GSSCredential clientCred = SecurityUtils.validateTicket(endUserTicket, serviceSubject);

            if (clientCred == null) {
                LOGGER.error("Could not get a client credential.");
                return null;
            }

            LOGGER.debug("We have a delegated credential for end user to use the service !");

            // STEP 3
            // Initiate TGS request for another service using delegated credentials obtained in previous step
            Object obj = SecurityUtils.getServiceTicket(clientCred, targetServicePrincipal);
            if (obj == null) {
                LOGGER.error("Could not have the service ticket to put in WWW-Authorization header! NULL.");
                return null;
            }

            // array copy instead?
            serviceToken = (byte[]) obj;

            return new String(Base64.getEncoder().encode(serviceToken));

        } catch (Exception ex) {
            LOGGER.error("Could not get the delegated service token", ex);
            return null;
        }
    }

    private static byte[] getEndUserTicket(String endUserTicketB64) {
        if (endUserTicketB64 == null || endUserTicketB64.isEmpty()) {
            LOGGER.error("Cannot decode null or empty enduser ticket");
            return null;
        }

        byte[] endUserTicket;
        String negotiate = "Negotiate ";
        if (endUserTicketB64.startsWith(negotiate)) {
            endUserTicketB64 = endUserTicketB64.substring(negotiate.length());
        }
        try {
            endUserTicket = Base64.getDecoder().decode(endUserTicketB64);
        } catch (Exception ex) {
            LOGGER.error("Could not decode the end user token", ex);
            return null;
        }

        return endUserTicket;
    }
}
