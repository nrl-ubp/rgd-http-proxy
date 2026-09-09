package com.ubp.rgd.proxy.security;

import org.ietf.jgss.Oid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KerberosConstants {
    public static Oid SPNEGO_OID;
    public static Oid KRB5_PRINCIPAL_OID;

    public static final String SPNEGO_MECHANISM = "1.3.6.1.5.5.2";
    public static final String KRB5_PRINCIPAL = "1.2.840.113554.1.2.2.1";

    public static final Logger LOG = LoggerFactory.getLogger(KerberosConstants.class);

    static {
        try {
            LOG.debug("Init OID for Kerberos...");
            SPNEGO_OID = new Oid(SPNEGO_MECHANISM);
            KRB5_PRINCIPAL_OID = new Oid(KRB5_PRINCIPAL);
        } catch (Exception ex) {
            LOG.error("Cannot initialize OID objects", ex);
        }
    }

    public static final String DEFAULT_LDAP_URL = "ldap://srvdcnas01.corp.ubp.ch:389"; // Replace with your LDAP server URL
    public static final String DEFAULT_BASE_DN = "OU=Site-GVA,OU=ORGANIZATION,DC=corp,DC=ubp,DC=ch"; // Replace with your base DN

    public static final String DEFAULT_REALM = "CORP.UBP.CH";
}
