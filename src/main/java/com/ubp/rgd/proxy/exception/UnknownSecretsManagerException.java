package com.ubp.rgd.proxy.exception;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * A request named, through the secrets manager header, a value that has no entry in the secrets
 * manager mapping file. Answered with a 400: transforming with another secrets manager than the one
 * the caller asked for would produce tokens nobody can read back.
 */
public class UnknownSecretsManagerException extends BadRequestException {

    public UnknownSecretsManagerException(String headerName, String headerValue) {
        super(message(headerName, headerValue), Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.TEXT_PLAIN_TYPE)
                .entity(message(headerName, headerValue))
                .build());
    }

    private static String message(String headerName, String headerValue) {
        return String.format("Unknown %s value: %s", headerName, headerValue);
    }
}
