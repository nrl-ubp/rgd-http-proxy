package com.ubp.rgd.proxy.transform.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Content of {@code proxy.transform.http-secrets-manager-mapping-file}: which RPS secrets manager
 * a request uses, according to the value of the {@code proxy.transform.http-secrets-manager-header}
 * header.
 * <pre>
 * {
 *   "default-mapping": "LU",
 *   "mappings": [
 *     {"CH": "1d2f6c4e-...-..."},
 *     {"LU": "7a9b0e3c-...-..."}
 *   ]
 * }
 * </pre>
 * Each entry of {@code mappings} maps one header value to the UUID of a secrets manager.
 * {@code default-mapping} names the entry used when a request does not carry the header.
 *
 * @see com.ubp.rgd.proxy.services.SecretsManagerResolver
 */
public class SecretsManagerMappingConfig {

    @JsonProperty("default-mapping")
    private String defaultMapping;

    @JsonProperty("mappings")
    private List<Map<String, String>> mappings = new ArrayList<>();

    public String getDefaultMapping() {
        return defaultMapping;
    }

    public void setDefaultMapping(String defaultMapping) {
        this.defaultMapping = defaultMapping;
    }

    public List<Map<String, String>> getMappings() {
        return mappings;
    }

    public void setMappings(List<Map<String, String>> mappings) {
        this.mappings = mappings == null ? new ArrayList<>() : mappings;
    }
}
