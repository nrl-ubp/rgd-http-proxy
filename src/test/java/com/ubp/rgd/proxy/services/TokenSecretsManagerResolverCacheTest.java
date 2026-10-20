package com.ubp.rgd.proxy.services;

import com.mongodb.client.MongoClient;
import com.ubp.rgd.proxy.exception.SecretsManagerNotFoundException;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.UuidRepresentation;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    /** A property of the client, as {@link TokenSecretsManagerResolver#init()} would load it. */
    private void clientHasProperty() {
        var prop = new TokenSecretsManagerResolver.RPSConfigProperty();
        prop.id = UUID.randomUUID().toString();
        prop.configurationId = UUID.randomUUID().toString();
        prop.className = "Person";
        prop.propertyName = "ShortString";
        prop.mapping = 3L;
        doReturn(prop).when(resolver).findProperty(anyString(), anyString());
    }

    @Test
    @DisplayName("A resolved token is looked up once, then served from the cache")
    void resolutionIsCached() {
        String token = "RG{BxAbCd1234Cached-AB}";
        clientHasProperty();
        BsonDocument doc = new BsonDocument("SecretVersionId", new BsonBinary(UUID.randomUUID(), UuidRepresentation.C_SHARP_LEGACY));
        doReturn(Optional.of(doc)).when(resolver).lookupInMongoDb(eq(token), any());
        doReturn(Optional.of(CH)).when(resolver).lookupInSqlServer(eq(token), anyString());

        assertEquals(CH, resolver.secretsManagerIdResolve(token, "Person", "ShortString"));
        assertEquals(CH, resolver.secretsManagerIdResolve(token, "Person", "ShortString"));

        verify(resolver, times(1)).lookupInMongoDb(eq(token), any());
    }

    @Test
    @DisplayName("A failed resolution is retried on the next call")
    void failureIsNotCached() {
        String token = "RG{BxAbCd1234Missing-AB}";
        clientHasProperty();
        doReturn(Optional.empty()).when(resolver).lookupInMongoDb(eq(token), any());

        assertThrows(SecretsManagerNotFoundException.class, () -> resolver.secretsManagerIdResolve(token, "Person", "ShortString"));
        assertThrows(SecretsManagerNotFoundException.class, () -> resolver.secretsManagerIdResolve(token, "Person", "ShortString"));

        verify(resolver, times(2)).lookupInMongoDb(eq(token), any());
    }
}
