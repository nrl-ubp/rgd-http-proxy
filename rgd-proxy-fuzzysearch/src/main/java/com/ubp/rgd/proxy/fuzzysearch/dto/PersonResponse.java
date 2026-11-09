package com.ubp.rgd.proxy.fuzzysearch.dto;

/**
 * Response body returned by person endpoints, in clear.
 *
 * @param id        identifier of the row
 * @param firstName first name
 * @param lastName  last name
 * @param tenantId  tenant scope, the configured {@code fuzzy-search.tenant-id}
 * @param birthDate date of birth, in the format it was protected with
 * @param email     contact e-mail address
 * @param city      city
 * @param notes     free text notes
 */
public record PersonResponse(
        Integer id,
        String firstName,
        String lastName,
        String tenantId,
        String birthDate,
        String email,
        String city,
        String notes
) {}
