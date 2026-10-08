package com.ubp.rgd.proxy.exception;

/**
 * The secrets manager that created a token could not be resolved, so the token cannot be
 * detokenized.
 * <p>
 * Unchecked so that it can leave {@code @CacheResult} methods untouched: a failed resolution is
 * never cached and is retried on the next detokenization.
 */
public class SecretsManagerNotFoundException extends RuntimeException {

    public SecretsManagerNotFoundException(String message) {
        super(message);
    }
}
