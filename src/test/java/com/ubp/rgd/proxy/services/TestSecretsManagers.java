package com.ubp.rgd.proxy.services;

import java.util.UUID;

/**
 * A {@link SecretsManagerResolver} built outside CDI, on {@code src/test/resources/secrets_manager_mapping.json}.
 */
public final class TestSecretsManagers {

    public static final String HEADER = "X-Proxy-Jurisdiction";
    public static final String MAPPING_FILE = "./src/test/resources/secrets_manager_mapping.json";
    public static final UUID CH = UUID.fromString("479d4a15-1412-4fc1-9498-efee9dc4af3a");
    public static final UUID LU = UUID.fromString("f234b749-1415-4c94-ac91-accf9f6ce5d2");

    private TestSecretsManagers() {
    }

    /** @return a resolver loaded from the test mapping file, whose default is {@link #LU} */
    public static SecretsManagerResolver resolver() {
        return resolver(MAPPING_FILE);
    }

    /** @return a resolver loaded from the given mapping file */
    public static SecretsManagerResolver resolver(String mappingFile) {
        SecretsManagerResolver resolver = new SecretsManagerResolver();
        resolver.headerName = HEADER;
        resolver.mappingFile = mappingFile;
        resolver.load();
        return resolver;
    }
}
