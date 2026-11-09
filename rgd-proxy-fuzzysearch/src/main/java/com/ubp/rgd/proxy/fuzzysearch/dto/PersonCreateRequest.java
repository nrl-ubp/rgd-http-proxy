package com.ubp.rgd.proxy.fuzzysearch.dto;

/**
 * Request body for {@code POST /persons}, in clear.
 *
 * @param firstName first name
 * @param lastName  last name
 * @param birthDate date of birth, for instance {@code 1980-05-12} or {@code 12.05.1980}
 * @param email     contact e-mail address
 * @param city      city
 * @param notes     free text notes
 */
public record PersonCreateRequest(
        String firstName,
        String lastName,
        String birthDate,
        String email,
        String city,
        String notes
) {}
