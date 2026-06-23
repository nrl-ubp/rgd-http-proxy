package com.ubp.rgd.proxy.security;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import jakarta.enterprise.context.RequestScoped;

import java.util.Set;

@RequestScoped
public class SecurityContext {
    @CacheName(("ubp_user_ad_groups"))
    Cache userAdGroupsCache;

    private SecurityToken token;

    public SecurityToken getToken() {
        return token;
    }

    public void setToken(SecurityToken token) {
        this.token = token;
    }

    public Set<String> getRoles() {
        return token.roles;
    }

    public Cache getUserAdGroupsCache() {
        return this.userAdGroupsCache;
    }
}
