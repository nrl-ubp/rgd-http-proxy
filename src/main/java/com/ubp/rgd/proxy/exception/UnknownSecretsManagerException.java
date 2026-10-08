package com.ubp.rgd.proxy.exception;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * A request named, through the secrets manager header or a set's {@code jurisdiction}, a value that
 * has no entry in the secrets manager mapping file. Answered with a 400: transforming with another secrets manager than the one
 * the caller asked for would produce tokens nobody can read back.
 */
public class UnknownSecretsManagerException extends BadRequestException {

    /**
     * @param source where the value was read from, e.g. the header name or {@code jurisdiction of set #2}
     * @param value  the unknown value
     */
    public UnknownSecretsManagerException(String source, String value) {
        super(message(source, value), Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN_TYPE)
                .entity(message(source, value))
                .build());
    }

    private static String message(String source, String value) {
        return String.format("Unknown %s value: %s", source, value);
    }
}
