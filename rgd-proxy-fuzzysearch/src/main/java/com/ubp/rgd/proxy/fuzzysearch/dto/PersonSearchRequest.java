package com.ubp.rgd.proxy.fuzzysearch.dto;

/**
 * Request body of {@code POST /persons/search}.
 *
 * @param query    the clear text to search for
 * @param tenantId the tenant, the configured {@code fuzzy-search.tenant-id} when {@code null}
 */
public record PersonSearchRequest(
        String query,
        String tenantId
) {}
