package com.ubp.rgd.proxy.transform;

import java.util.UUID;

/**
 * Gives the secrets manager to protect with when nothing in the request selects one.
 * <p>
 * Implemented by the application from its {@code proxy.transform.http-secrets-manager-mapping-file}, so
 * that the modules which transform outside of an HTTP request, such as the file transformation, use
 * the same default as the HTTP endpoints.
 */
public interface DefaultSecretsManagerProvider {

    /** @return the default secrets manager, {@code null} to let the engine use its own default */
    UUID defaultSecretsManager();
}
