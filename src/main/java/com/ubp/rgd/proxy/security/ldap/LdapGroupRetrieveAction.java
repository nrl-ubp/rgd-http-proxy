package com.ubp.rgd.proxy.security.ldap;

import com.ubp.rgd.proxy.security.KerberosConstants;

import javax.naming.directory.DirContext;
import java.util.List;

/**
 * Priviledged Action to retreive the AD groups of a given username (without domain)
 * To be used with <code>Subject.doAs(loginServiceSubject, new LdapGroupRetreiveAction(searchUserName)</code>
 * Shortcut constructor refers to KerberosConstant class to get default values for parameters.
 * @see KerberosConstants
 */
public class LdapGroupRetrieveAction implements java.security.PrivilegedAction<List<String>>{
    private final String ldapServerUrl;
    private final String searchedUserName;
    private final String searchBaseDn;

    public LdapGroupRetrieveAction(String searchedUserName) {
        this(null, searchedUserName);
    }

    public LdapGroupRetrieveAction(String ldapServerUrl, String searchedUserName) {
        this(ldapServerUrl, searchedUserName, null);
    }

    public LdapGroupRetrieveAction(String ldapServerUrl, String searchedUserName, String searchBaseDn) {
        this.ldapServerUrl = ldapServerUrl == null ? KerberosConstants.DEFAULT_LDAP_URL : ldapServerUrl;
        this.searchedUserName = searchedUserName;
        this.searchBaseDn = searchBaseDn == null ? KerberosConstants.DEFAULT_BASE_DN : searchBaseDn;
    }

    @Override
    public List<String> run() {
        DirContext ctx = LdapClient.login(ldapServerUrl);
        return LdapClient.listUserGroups(ctx, searchBaseDn, searchedUserName);
    }
}
