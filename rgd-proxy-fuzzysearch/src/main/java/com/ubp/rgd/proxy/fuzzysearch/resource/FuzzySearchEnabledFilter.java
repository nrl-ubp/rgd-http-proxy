package com.ubp.rgd.proxy.fuzzysearch.resource;

import com.ubp.rgd.proxy.fuzzysearch.FuzzySearch;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Answers {@code 503} on every fuzzy search endpoint while {@code fuzzy-search.enabled=false}: the
 * module ships with the proxy, but neither its database, nor OpenSearch, nor its FastText model may be
 * touched until it is switched on.
 */
@Provider
@FuzzySearchEndpoint
@Priority(Priorities.USER)
public class FuzzySearchEnabledFilter implements ContainerRequestFilter {

    @ConfigProperty(name = FuzzySearch.ENABLED_PROPERTY, defaultValue = "false")
    boolean enabled;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!enabled) {
            requestContext.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("The fuzzy search is disabled (" + FuzzySearch.ENABLED_PROPERTY + "=false).")
                    .build());
        }
    }
}
