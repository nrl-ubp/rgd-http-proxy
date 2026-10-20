package com.ubp.rgd.proxy.rps.deployment;

import com.ubp.rgd.proxy.rps.SecretsManagerNotFoundException;
import com.ubp.rgd.proxy.rps.TokenSecretsManagerResolver;
import com.ubp.rgd.proxy.rps.TokenSecretsManagerResolver.RPSConfigProperty;

import com.mongodb.client.MongoClient;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.UuidRepresentation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks, inside Quarkus, that the extension wires the lookups and that the resolved secrets managers
 * are cached while the failures are not.
 * <p>
 * No database is reachable: the lookups are answered by {@link CountingResolver}, which replaces the
 * resolver bean but keeps its cached {@code secretsManagerIdResolve}. It lives in another package than the
 * resolver: the extension and the test archive have different class loaders, so ArC must not access the
 * inherited package-private injection points directly.
 */
class TokenSecretsManagerResolverCacheTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(CountingResolver.class))
            .overrideConfigKey("proxy.transform.config-file", "src/test/resources/empty_transform_config.json")
            .overrideConfigKey("proxy.transform.client-id", "3150a79e-3084-4946-aeb5-e7e73aea3904")
            .overrideConfigKey("proxy.transform.client-secret", "test-secret")
            .overrideConfigKey("proxy.transform.identity-url", "http://localhost:1/identity")
            .overrideConfigKey("proxy.transform.engine-url", "http://localhost:1/engine")
            .overrideConfigKey("proxy.secrets-manager-lookup.mongodb.database", "rgd-test");

    private static final UUID CH = UUID.fromString("16ec8462-e8d5-4a2c-b8df-f253e09bd274");

    /**
     * Answers the lookups from memory and counts the MongoDB ones.
     */
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class CountingResolver extends TokenSecretsManagerResolver {

        private final Map<String, Optional<BsonDocument>> mongoAnswers = new HashMap<>();
        private final Map<String, Integer> mongoLookups = new HashMap<>();

        // Accessed through methods: the test gets a client proxy, whose fields are not the bean's.
        public void answer(String token, BsonDocument document) {
            mongoAnswers.put(token, Optional.of(document));
        }

        public int mongoLookups(String token) {
            return mongoLookups.getOrDefault(token, 0);
        }

        public void reset() {
            mongoAnswers.clear();
            mongoLookups.clear();
        }

        @Override
        protected RPSConfigProperty findProperty(String propertyClassName, String propertyName) {
            RPSConfigProperty prop = new RPSConfigProperty();
            prop.id = UUID.randomUUID().toString();
            prop.configurationId = UUID.randomUUID().toString();
            prop.className = propertyClassName;
            prop.propertyName = propertyName;
            prop.mapping = 3L;
            return prop;
        }

        @Override
        protected Optional<BsonDocument> lookupInMongoDb(String token, RPSConfigProperty configProperty) {
            mongoLookups.merge(token, 1, Integer::sum);
            return mongoAnswers.getOrDefault(token, Optional.empty());
        }

        @Override
        protected Optional<UUID> lookupInSqlServer(String token, String secretVersionId) {
            return Optional.of(CH);
        }
    }

    @Inject
    TokenSecretsManagerResolver resolver;

    @Inject
    CountingResolver counting;

    @CacheName(TokenSecretsManagerResolver.CACHE_NAME)
    Cache cache;

    @Inject
    Instance<MongoClient> mongoClient;

    @Inject
    @DataSource(TokenSecretsManagerResolver.DATASOURCE_NAME)
    Instance<AgroalDataSource> sqlServerDataSource;

    @BeforeEach
    void reset() {
        cache.invalidateAll().await().indefinitely();
        counting.reset();
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
        String token = "RG{BxAbCd1234Cached-AB}";
        counting.answer(token, new BsonDocument("SecretVersionId",
                new BsonBinary(UUID.randomUUID(), UuidRepresentation.C_SHARP_LEGACY)));

        assertEquals(CH, resolver.secretsManagerIdResolve(token, "Person", "ShortString"));
        assertEquals(CH, resolver.secretsManagerIdResolve(token, "Person", "ShortString"));

        assertEquals(1, counting.mongoLookups(token));
    }

    @Test
    @DisplayName("A failed resolution is retried on the next call")
    void failureIsNotCached() {
        String token = "RG{BxAbCd1234Missing-AB}";

        assertThrows(SecretsManagerNotFoundException.class, () -> resolver.secretsManagerIdResolve(token, "Person", "ShortString"));
        assertThrows(SecretsManagerNotFoundException.class, () -> resolver.secretsManagerIdResolve(token, "Person", "ShortString"));

        assertEquals(2, counting.mongoLookups(token));
    }
}
