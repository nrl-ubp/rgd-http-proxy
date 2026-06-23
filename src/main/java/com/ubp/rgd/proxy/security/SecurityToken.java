package com.ubp.rgd.proxy.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import org.jboss.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosTicket;
import java.util.HashSet;
import java.util.Set;

@JsonRootName("security-token")
public abstract class SecurityToken {
    private static final Logger LOGGER = Logger.getLogger(SecurityToken.class.getName());

    protected Subject userSubject;

    protected String user;
    protected String domainName;
    protected String hostname;

    protected Set<String> roles = new HashSet<>();

    public SecurityToken() {
    }

    public abstract void decode(String b64RawToken) throws Exception;

    public Subject getUserSubject() {
        return this.userSubject;
    }

    public KerberosTicket getKerbTicket(String serverPrincipalName) {
        if (this.userSubject == null) {
            return null;
        }

        Set<KerberosTicket> ticketSet = this.userSubject.getPrivateCredentials(KerberosTicket.class);
        for (KerberosTicket ticket : ticketSet) {
            if (ticket.getServer().getName().equalsIgnoreCase(serverPrincipalName)) {
                return ticket;
            }
        }

        LOGGER.warn("Kerberos ticket not found for user: " + this.user);
        return null;
    }

    @JsonProperty("username")
    public String getUser() {
        return this.user;
    }

    @JsonProperty("domain")
    public String getDomainName() {
        return this.domainName;
    }

    @JsonProperty("hostname")
    public String getHostname() {
        return this.hostname;
    }

    @JsonProperty("roles")
    public Set<String> getRoles() {
        return this.roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles;
    }

    /**
     * Check if a user is in one role. the role list is populated by the underlying
     * implementation.
     * @return true is the current security token has the provided role.
     */
    public boolean hasRole(String role) {
        return this.roles != null && this.roles.contains(role);
    }
}
