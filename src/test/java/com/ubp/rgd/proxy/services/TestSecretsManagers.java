package com.ubp.rgd.proxy.services;

import java.util.UUID;

/**
 * A {@link SecretsManagerResolver} built outside CDI, on {@code src/test/resources/secrets_manager_mapping.json}.
 */
public final class TestSecretsManagers {

    public static final String HEADER = "X-Proxy-Jurisdiction";
    public static final String MAPPING_FILE = "./src/test/resources/secrets_manager_mapping.json";
    public static final UUID CH = UUID.fromString("16ec8462-e8d5-4a2c-b8df-f253e09bd274");
    public static final UUID LU = UUID.fromString("b9f72aef-6c1b-4556-bf65-9813f122cf8b");

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
