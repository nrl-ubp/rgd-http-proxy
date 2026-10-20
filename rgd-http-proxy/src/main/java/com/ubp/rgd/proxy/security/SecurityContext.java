package com.ubp.rgd.proxy.security;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import jakarta.enterprise.context.RequestScoped;

import javax.security.auth.Subject;
import java.util.Set;

@RequestScoped
public class SecurityContext {
    @CacheName(("ubp_user_ad_groups"))
    Cache userAdGroupsCache;

    private SecurityToken token;

    /**
     * The authenticated user's Subject captured for the lifetime of the request. Holding it here
     * (request scope) avoids reading per-request Kerberos state off the @ApplicationScoped token
     * beans, and lets downstream components (e.g. ProxyService) obtain a client-to-service ticket.
     */
    private Subject userSubject;

    public SecurityToken getToken() {
        return token;
    }

    public void setToken(SecurityToken token) {
        this.token = token;
    }

    public Subject getUserSubject() {
        return userSubject;
    }

    public void setUserSubject(Subject userSubject) {
        this.userSubject = userSubject;
    }

    public Set<String> getRoles() {
        return token.roles;
    }

    public Cache getUserAdGroupsCache() {
        return this.userAdGroupsCache;
    }
}
