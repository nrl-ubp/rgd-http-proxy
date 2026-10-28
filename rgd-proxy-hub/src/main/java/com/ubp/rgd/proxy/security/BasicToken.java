package com.ubp.rgd.proxy.security;

import com.sun.security.auth.module.Krb5LoginModule;
import com.ubp.rgd.proxy.security.ldap.LdapClient;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.directory.DirContext;
import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosTicket;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class BasicToken extends SecurityToken {

    private static final Logger LOG = LoggerFactory.getLogger(BasicToken.class);

    private static final String basic = "BASIC ";

    /**
     * The ticket obtained after login against Active Directory.
     */
    private KerberosTicket kerbTicket = null;

    @Inject
    LdapClient ldapClient;

    @Inject
    @CacheName(("ubp_user_ad_groups"))
    public Cache userAdGroupsCache;

    @ConfigProperty(name = "proxy.kerberos.service-principal-realm", defaultValue = KerberosConstants.DEFAULT_REALM)
    String domainName;

    public BasicToken() {
    }

    @Override
    public void decode(String b64RawToken) throws Exception {
        String token = b64RawToken;
        if (token.toUpperCase().startsWith(basic)) {
            token = token.substring(basic.length());
        }

        byte[] basicToken = Base64.getDecoder().decode(token);
        String basicAuth = new String(basicToken);
        String[] userPass = basicAuth.split(":");

        if (userPass.length != 2) {
            LOG.error("Invalid basic auth provided. Cannot decode the user and password.");
            throw new Exception("Invalid basic auth token provided. Cannot decode user and password.");
        }

        this.userSubject = validateUserPass(userPass);
        if (this.userSubject != null) {
            this.user = userPass[0];
            this.domainName = "CORP.UBP.CH";
            this.hostname = "localhost";

            LOG.info("Now checking cache to get AD groups...");
            // query the cache and populate if nothing found, otherwise use the cache
            CompletableFuture<List<String>> futUserAdGroups = userAdGroupsCache.as(CaffeineCache.class).getIfPresent(this.user);
            if (futUserAdGroups == null) {
                LOG.info("User AD groups not found in cache. Requesting LDAP / AD for user: {}", this.user);
                futUserAdGroups = getUserGroups(userPass[0], userPass[1]);
                userAdGroupsCache.as(CaffeineCache.class).put(this.user, futUserAdGroups);
            }
            this.setRoles(new HashSet<>(futUserAdGroups.get()));
        }
    }

    private CompletableFuture<List<String>> getUserGroups(String user, String pass) {
        return CompletableFuture.supplyAsync(() -> {
            DirContext ctx = ldapClient.login(user, pass, this.domainName);
            return ldapClient.listUserGroups(ctx, user);
        });
    }

    private synchronized Subject validateUserPass(String[] userPass) {
        try {
            Subject subject = new Subject();
            Krb5LoginModule krb5LoginModule = new Krb5LoginModule();
            Map<String, String> optionMap = getKrb5LoginModuleOptionMap();
            MockingCallbackHandler callbackHandler = new MockingCallbackHandler();
            callbackHandler.setUser(userPass[0]);
            callbackHandler.setPassword(userPass[1].toCharArray());

            krb5LoginModule.initialize(subject, callbackHandler, new HashMap<String, String>(), optionMap);

            if (!krb5LoginModule.login()) {
                LOG.error("Could not login for user: {}", userPass[0]);
                return null;
            }

            krb5LoginModule.commit();

            for(Object obj: subject.getPrivateCredentials()) {
                LOG.debug("Private creds: " + obj.getClass().getName());
                if (obj instanceof KerberosTicket) {
                    this.kerbTicket = (KerberosTicket)obj;
                    LOG.debug("Kerb ticket client principal: " + this.kerbTicket.getClient().getName());
                    LOG.debug("Kerb ticket server principal: " + this.kerbTicket.getServer().getName());
                }
            }

            for (Principal principal : subject.getPrincipals()) {
                LOG.debug(principal.getClass().getName() + " = " + principal.getName());
            }

            return subject;
        } catch (Exception ex) {
            LOG.error("Cannot validate BASIC user/pass against active directory.", ex);
            return null;
        }
    }

    private Map<String, String> getKrb5LoginModuleOptionMap() {
        Map<String, String> optionMap = new HashMap<>();

        optionMap.put("doNotPrompt", "false");
        optionMap.put("refreshKrb5Config", "true");
        optionMap.put("useTicketCache", "true");
        optionMap.put("renewTGT", "true");
        optionMap.put("useKeyTab", "true");
        optionMap.put("storeKey", "true");
        optionMap.put("isInitiator", "true"); // needed for delegation
        optionMap.put("debug", "false"); // trace will be printed on console
        return optionMap;
    }

    public KerberosTicket getKerbTicket() {
        return this.kerbTicket;
    }
}
