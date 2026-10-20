package com.ubp.rgd.proxy.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ubp.rgd.proxy.exception.UnknownSecretsManagerException;
import com.ubp.rgd.proxy.transform.config.SecretsManagerMappingConfig;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the RPS secrets manager a request must be transformed with.
 * <p>
 * The caller names it through the {@code proxy.transform.http-secrets-manager-header} header (for
 * instance {@code X-Proxy-Jurisdiction: LU}) on {@code /proxy} and {@code /utils/wdx1/concat}, or
 * through the {@code jurisdiction} of each set on {@code /transform}. The value is looked up, case-insensitively, in
 * {@code proxy.transform.http-secrets-manager-mapping-file}; a request without the header uses the
 * file's {@code default-mapping}. A value missing from the file is rejected with a 400 rather than
 * silently transformed with another secrets manager.
 * <p>
 * The file is read and validated at startup, and any defect stops the application: a proxy that
 * cannot tell which secrets manager to use must not tokenize anything.
 */
@Startup
@ApplicationScoped
public class SecretsManagerResolver {

    private static final Logger LOG = LoggerFactory.getLogger(SecretsManagerResolver.class);

    /** Request property where the PreFilter leaves the resolved id for the PostFilter. */
    public static final String REQUEST_PROPERTY = "proxy.secrets-manager";

    @ConfigProperty(name = "proxy.transform.http-secrets-manager-header", defaultValue = "X-Proxy-Jurisdiction")
    String headerName;

    @ConfigProperty(name = "proxy.transform.http-secrets-manager-mapping-file",
            defaultValue = "./config/secrets_manager_mapping.json")
    String mappingFile;

    /** Keyed by the upper-cased header value. */
    private Map<String, UUID> mappings = Collections.emptyMap();
    private String defaultKey;
    private UUID defaultSecretsManager;

    @PostConstruct
    void init() {
        load();
    }

    /** @return the name of the header selecting the secrets manager */
    public String headerName() {
        return headerName;
    }

    /** @return the secrets manager used when a request does not carry the header */
    public UUID defaultSecretsManager() {
        return defaultSecretsManager;
    }

    /**
     * @param headerValue the value of the header, {@code null} or blank when absent
     * @return the secrets manager mapped to the value, or the default one when there is no value
     * @throws UnknownSecretsManagerException when the value is not in the mapping file
     */
    public UUID resolve(String headerValue) {
        return resolve(headerValue, headerName);
    }

    /**
     * @param value  the value naming the secrets manager, {@code null} or blank when absent
     * @param source where the value was read from, quoted in the error, e.g. {@code jurisdiction of set #2}
     * @return the secrets manager mapped to the value, or the default one when there is no value
     * @throws UnknownSecretsManagerException when the value is not in the mapping file
     */
    public UUID resolve(String value, String source) {
        if (value == null || value.isBlank()) {
            return defaultSecretsManager;
        }
        UUID id = mappings.get(normalize(value));
        if (id == null) {
            LOG.warn("Rejecting a request with unknown {} value '{}'", source, value);
            throw new UnknownSecretsManagerException(source, value);
        }
        return id;
    }

    /** @see #resolve(String) */
    public UUID resolve(HttpHeaders headers) {
        return resolve(headers == null ? null : headers.getHeaderString(headerName));
    }

    /** @see #resolve(String) */
    public UUID resolve(ContainerRequestContext requestContext) {
        return resolve(requestContext.getHeaderString(headerName));
    }

    /** Read and validate the mapping file; throws {@link IllegalStateException} on any defect. */
    void load() {
        File file = new File(mappingFile);
        if (!file.isFile()) {
            throw fail("the secrets manager mapping file %s does not exist", mappingFile);
        }
        SecretsManagerMappingConfig config;
        try {
            config = new ObjectMapper().readValue(file, SecretsManagerMappingConfig.class);
        } catch (IOException e) {
            throw fail("the secrets manager mapping file %s cannot be read: %s", mappingFile, e.getMessage());
        }

        Map<String, UUID> loaded = new LinkedHashMap<>();
        for (Map<String, String> entry : config.getMappings()) {
            if (entry == null) {
                continue;
            }
            for (Map.Entry<String, String> mapping : entry.entrySet()) {
                String key = mapping.getKey();
                if (key == null || key.isBlank()) {
                    throw fail("the secrets manager mapping file %s has a blank key", mappingFile);
                }
                UUID id;
                try {
                    id = UUID.fromString(String.valueOf(mapping.getValue()).trim());
                } catch (IllegalArgumentException e) {
                    throw fail("the secrets manager mapping file %s maps '%s' to '%s', which is not a UUID",
                            mappingFile, key, mapping.getValue());
                }
                if (loaded.putIfAbsent(normalize(key), id) != null) {
                    throw fail("the secrets manager mapping file %s maps '%s' more than once (keys are"
                            + " case-insensitive)", mappingFile, key);
                }
            }
        }

        String defaultMapping = config.getDefaultMapping();
        if (defaultMapping == null || defaultMapping.isBlank()) {
            throw fail("the secrets manager mapping file %s has no 'default-mapping'", mappingFile);
        }
        UUID defaultId = loaded.get(normalize(defaultMapping));
        if (defaultId == null) {
            throw fail("the 'default-mapping' '%s' of the secrets manager mapping file %s is not one of"
                    + " its mappings %s", defaultMapping, mappingFile, loaded.keySet());
        }

        mappings = Collections.unmodifiableMap(loaded);
        defaultKey = defaultMapping.trim();
        defaultSecretsManager = defaultId;
        LOG.info("Loaded secrets manager mappings {} from {}, selected by header {}, default {}",
                mappings.keySet(), mappingFile, headerName, defaultKey);
    }

    private static String normalize(String key) {
        return key.trim().toUpperCase(Locale.ROOT);
    }

    private static IllegalStateException fail(String format, Object... args) {
        String message = "Invalid secrets manager configuration: " + String.format(format, args);
        LOG.error(message);
        return new IllegalStateException(message);
    }
}
