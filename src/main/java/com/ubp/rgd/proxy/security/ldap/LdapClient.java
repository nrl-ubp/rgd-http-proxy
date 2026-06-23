package com.ubp.rgd.proxy.security.ldap;

import com.ubp.rgd.proxy.services.ProxyService;
import org.jboss.logging.Logger;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;

/**
 * Use the LDAP client to connect to a LDAP server and start exploring objects
 * One must provide a valid user and password to connect to the LDAP url.
 * Once connected a DirContext object is returned. This context must be reused to search into the LDAP tree.
 * This class provides utility methods to return specific objects.
 */
public class LdapClient {
    private static final Logger LOG = Logger.getLogger(ProxyService.class);

    /**
     * Use GSS API to  login to LDAP. This method MUST be called inside a PriviledgedAction.run() method.
     * @param ldapUrl the LDAP server url
     * @return a DirContext allowing to query LDPA tree with other methods of this LDAP Client class.
     */
    public static DirContext login(String ldapUrl) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, ldapUrl);
        env.put(Context.SECURITY_AUTHENTICATION, "GSSAPI");

        try {
            // Attempt to connect and authenticate
            return new InitialDirContext(env);
        } catch (Exception e) {
            LOG.errorf("Cannot connect to LDAP server using GSSAPI. %s", e.getMessage());
            LOG.error(e);
            return null;
        }
    }

    /**
     * Login against LDAP server using username and password. This does NOT use any kerberos procedures
     * and should be used only with service accounts with non expiring password.
     * @param ldapUrl the LDAP server url
     * @param userDn the domain name of the user
     * @param password password associated to user name-
     * @return a DirContext allowing to query LDPA tree with other methods of this LDAP Client class.
     */
    public static DirContext login(String ldapUrl, String userDn, String password, String domainName) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, ldapUrl);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, String.format("%s@%s", userDn, domainName));
        env.put(Context.SECURITY_CREDENTIALS, password);

        try {
            // Attempt to connect and authenticate
            return new InitialDirContext(env);
        } catch (Exception e) {
            LOG.errorf("Cannot connect to LDAP server using user/password for: %s\\%s", domainName, userDn);
            LOG.error(e);
            return null;
        }
    }

    /**
     * Query active directory to find user's groups. This method issues a LDAP query ADAPTED to active directory.
     * The query is : <code>(sAMAccountName=%s)</code> where %s is the user account name we want the AD groups for.
     * @param dirCtx connection context already initialized with the login method
     * @param baseDn the base Dn to search into.
     * @param searchAccountName account name of the user we want to get the groups.
     * @return List of String with user's groups. Empty list if no groups for this user, null if user not found or any other problem
     */
    public static List<String> listUserGroups(DirContext dirCtx, String baseDn, String searchAccountName) {

        if (dirCtx == null) {
            LOG.error("Must provide a initialized DirContext with the login method");
            return null;
        }

        try {
            // Search for the user object
            // String searchFilter = "(distinguishedName=" + userDnFull + ")";
            String searchFilter = String.format("(sAMAccountName=%s)", searchAccountName);
            SearchControls searchControls = new SearchControls();
            searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);

            NamingEnumeration<SearchResult> results = dirCtx.search(baseDn, searchFilter, searchControls);

            if (results.hasMore()) {
                SearchResult result = results.next();

                Attributes attributes = result.getAttributes();

                LOG.debugf("Found user: %s\n", attributes.get("distinguishedName"));

                // Get the "memberOf" attribute, which contains the groups
                Attribute memberOf = attributes.get("memberOf");

                if (memberOf != null) {
                    LOG.debugf("Groups the user %s belongs to:%n", searchAccountName);
                    NamingEnumeration<?> groups = memberOf.getAll();
                    List<String> listGroups = new ArrayList<>();
                    while (groups.hasMore()) {
                        String group = (String) groups.next();
                        listGroups.add(group);
                        LOG.debug(group);
                    }
                    return listGroups;
                } else {
                    LOG.error("The user does not belong to any groups.");
                    return Collections.emptyList();
                }
            } else {
                LOG.error("User not found in the directory.");
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("Cannot retrieve LDAP user groups: %s", e.getMessage());
            LOG.error(e);
            return null;
        }
    }
}

