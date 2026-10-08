package com.ubp.rgd.proxy.services;

import com.mongodb.client.MongoClient;
import com.ubp.rgd.proxy.exception.SecretsManagerNotFoundException;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Checks, inside Quarkus, that the lookups are wired and that the resolved secrets managers are cached
 * while the failures are not.
 */
@QuarkusTest
@io.quarkus.test.junit.TestProfile(TestProfile.class)
class TokenSecretsManagerResolverCacheTest {

    private static final UUID CH = UUID.fromString("16ec8462-e8d5-4a2c-b8df-f253e09bd274");

    @InjectSpy
    TokenSecretsManagerResolver resolver;

    @CacheName(TokenSecretsManagerResolver.CACHE_NAME)
    Cache cache;

    @Inject
    Instance<MongoClient> mongoClient;

    @Inject
    @DataSource(TokenSecretsManagerResolver.DATASOURCE_NAME)
    Instance<AgroalDataSource> sqlServerDataSource;

    @BeforeEach
    void clearCache() {
        cache.invalidateAll().await().indefinitely();
    }

    @Test
    @DisplayName("The MongoDB client and the SQL Server datasource of the lookups are available")
    void lookupClientsAreWired() {
        assertTrue(mongoClient.isResolvable());
        assertTrue(sqlServerDataSource.isResolvable());
    }

    @Test
    @DisplayName("A resolved token is looked up once, then served from the cache")
    void resolutionIsCached() {
        String token = "RG{BxAbCd1234Cached}";
        doReturn(Optional.of("key")).when(resolver).lookupInMongoDb(token);
        doReturn(Optional.of(CH)).when(resolver).lookupInSqlServer(token, "key");

        assertEquals(CH, resolver.secretsManagerIdResolve(token));
        assertEquals(CH, resolver.secretsManagerIdResolve(token));

        verify(resolver, times(1)).lookupInMongoDb(token);
    }

    @Test
    @DisplayName("A failed resolution is retried on the next call")
    void failureIsNotCached() {
        String token = "RG{BxAbCd1234Missing}";
        doReturn(Optional.empty()).when(resolver).lookupInMongoDb(anyString());

        assertThrows(SecretsManagerNotFoundException.class, () -> resolver.secretsManagerIdResolve(token));
        assertThrows(SecretsManagerNotFoundException.class, () -> resolver.secretsManagerIdResolve(token));

        verify(resolver, times(2)).lookupInMongoDb(token);
    }
}
