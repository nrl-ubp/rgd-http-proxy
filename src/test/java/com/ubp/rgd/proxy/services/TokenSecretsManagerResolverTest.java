package com.ubp.rgd.proxy.services;

import com.ubp.rgd.proxy.exception.SecretsManagerNotFoundException;
import org.bson.BsonDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks how {@link TokenSecretsManagerResolver} chains its MongoDB and SQL Server lookups. The
 * lookups themselves are substituted.
 */
class TokenSecretsManagerResolverTest {

    private static final String TOKEN = "RG{BxAbCd1234SecretPart}";
    private static final UUID CH = UUID.fromString("16ec8462-e8d5-4a2c-b8df-f253e09bd274");

    /** Records the lookups and answers with the configured results. */
    private static class StubResolver extends TokenSecretsManagerResolver {
        final List<String> calls = new ArrayList<>();
        Optional<BsonDocument> mongoResult = Optional.empty();
        Optional<UUID> sqlServerResult = Optional.empty();

        @Override
        Optional<BsonDocument> lookupInMongoDb(String token, RPSConfigProperty prop) {
            calls.add("mongo:" + token);
            return mongoResult;
        }

        @Override
        Optional<UUID> lookupInSqlServer(String token, String secretVersionId) {
            calls.add("sql:" + token + ":" + secretVersionId);
            return sqlServerResult;
        }
    }

//    @Test
//    @DisplayName("MongoDB is queried first, its result feeds the SQL Server lookup")
//    void chainsTheLookups() {
//        StubResolver resolver = new StubResolver();
//        resolver.mongoResult = Optional.of("key-42");
//        resolver.sqlServerResult = Optional.of(CH);
//
//        assertEquals(CH, resolver.secretsManagerIdResolve(TOKEN));
//        assertEquals(List.of("mongo:" + TOKEN, "sql:" + TOKEN + ":key-42"), resolver.calls);
//    }

    @Test
    @DisplayName("A token unknown to MongoDB fails without querying SQL Server")
    void notFoundInMongoDb() {
        StubResolver resolver = new StubResolver();
        resolver.sqlServerResult = Optional.of(CH);

        SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                () -> resolver.secretsManagerIdResolve(TOKEN, "Person", "ShortString"));

        assertTrue(e.getMessage().contains("MongoDB"), e.getMessage());
        assertEquals(List.of("mongo:" + TOKEN), resolver.calls);
    }

    @Test
    @DisplayName("A token SQL Server has no secrets manager for fails")
    void notFoundInSqlServer() {
        StubResolver resolver = new StubResolver();
        resolver.mongoResult = Optional.of(BsonDocument.parse("{ key: 'key-42'}"));

        SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                () -> resolver.secretsManagerIdResolve(TOKEN, "Person", "ShortString"));

        assertTrue(e.getMessage().contains("SQL Server"), e.getMessage());
    }

    @Test
    @DisplayName("The error message never carries the whole token")
    void tokenIsMasked() {
        SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                () -> new StubResolver().secretsManagerIdResolve(TOKEN, "Person", "ShortString"));

        assertFalse(e.getMessage().contains(TOKEN), e.getMessage());
        assertFalse(e.getMessage().contains("SecretPart"), e.getMessage());
        assertTrue(e.getMessage().contains("RG{BxA..."), e.getMessage());
    }

    @Test
    @DisplayName("Until they are implemented, the lookups find nothing")
    void stubsFindNothing() {
        TokenSecretsManagerResolver resolver = new TokenSecretsManagerResolver();

        var cfg = new TokenSecretsManagerResolver.RPSConfigProperty();
        cfg.id = "1111";
        cfg.className = "Person";
        cfg.propertyName = "ShortString";
        cfg.mapping = 3L;

        assertTrue(resolver.lookupInMongoDb(TOKEN, cfg).isEmpty());
        assertTrue(resolver.lookupInSqlServer(TOKEN, "key").isEmpty());
        assertThrows(SecretsManagerNotFoundException.class, () -> resolver.secretsManagerIdResolve(TOKEN, cfg.className, cfg.propertyName));
    }

    @Test
    @DisplayName("Short or missing tokens are masked safely")
    void maskEdgeCases() {
        assertEquals("null", TokenSecretsManagerResolver.mask(null));
        assertEquals("RG{}", TokenSecretsManagerResolver.mask("RG{}"));
    }

    @Test
    @DisplayName("Test the number suffix resolve")
    void suffixeResolverTest() {
        TokenSecretsManagerResolver resolver = new TokenSecretsManagerResolver();
        assertEquals(1, resolver.getIndexFromLabel("ABCDEF-AB"));  // 1
        assertEquals(2, resolver.getIndexFromLabel("ABCDEF-AC"));  // 2
        assertEquals(25, resolver.getIndexFromLabel("ABCDEF-AZ"));  // 25
        assertEquals(26, resolver.getIndexFromLabel("ABCDEF-BA"));  // 26
        assertEquals(675, resolver.getIndexFromLabel("ABCDEF-ZZ"));  // 675
        assertEquals(676, resolver.getIndexFromLabel("ABCDEF-AAA")); // 676
        assertEquals(677, resolver.getIndexFromLabel("ABCDEF-AAB")); // 677
        assertEquals(678, resolver.getIndexFromLabel("ABCDEF-AAC")); // 678
    }
}
