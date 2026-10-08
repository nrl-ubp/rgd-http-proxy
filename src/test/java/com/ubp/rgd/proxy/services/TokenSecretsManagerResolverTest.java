package com.ubp.rgd.proxy.services;

import com.ubp.rgd.proxy.exception.SecretsManagerNotFoundException;
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
        Optional<String> mongoResult = Optional.empty();
        Optional<UUID> sqlServerResult = Optional.empty();

        @Override
        Optional<String> lookupInMongoDb(String token) {
            calls.add("mongo:" + token);
            return mongoResult;
        }

        @Override
        Optional<UUID> lookupInSqlServer(String token, String mongoResult) {
            calls.add("sql:" + token + ":" + mongoResult);
            return sqlServerResult;
        }
    }

    @Test
    @DisplayName("MongoDB is queried first, its result feeds the SQL Server lookup")
    void chainsTheLookups() {
        StubResolver resolver = new StubResolver();
        resolver.mongoResult = Optional.of("key-42");
        resolver.sqlServerResult = Optional.of(CH);

        assertEquals(CH, resolver.secretsManagerIdResolve(TOKEN));
        assertEquals(List.of("mongo:" + TOKEN, "sql:" + TOKEN + ":key-42"), resolver.calls);
    }

    @Test
    @DisplayName("A token unknown to MongoDB fails without querying SQL Server")
    void notFoundInMongoDb() {
        StubResolver resolver = new StubResolver();
        resolver.sqlServerResult = Optional.of(CH);

        SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                () -> resolver.secretsManagerIdResolve(TOKEN));

        assertTrue(e.getMessage().contains("MongoDB"), e.getMessage());
        assertEquals(List.of("mongo:" + TOKEN), resolver.calls);
    }

    @Test
    @DisplayName("A token SQL Server has no secrets manager for fails")
    void notFoundInSqlServer() {
        StubResolver resolver = new StubResolver();
        resolver.mongoResult = Optional.of("key-42");

        SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                () -> resolver.secretsManagerIdResolve(TOKEN));

        assertTrue(e.getMessage().contains("SQL Server"), e.getMessage());
    }

    @Test
    @DisplayName("The error message never carries the whole token")
    void tokenIsMasked() {
        SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                () -> new StubResolver().secretsManagerIdResolve(TOKEN));

        assertFalse(e.getMessage().contains(TOKEN), e.getMessage());
        assertFalse(e.getMessage().contains("SecretPart"), e.getMessage());
        assertTrue(e.getMessage().contains("RG{BxA..."), e.getMessage());
    }

    @Test
    @DisplayName("Until they are implemented, the lookups find nothing")
    void stubsFindNothing() {
        TokenSecretsManagerResolver resolver = new TokenSecretsManagerResolver();

        assertTrue(resolver.lookupInMongoDb(TOKEN).isEmpty());
        assertTrue(resolver.lookupInSqlServer(TOKEN, "key").isEmpty());
        assertThrows(SecretsManagerNotFoundException.class, () -> resolver.secretsManagerIdResolve(TOKEN));
    }

    @Test
    @DisplayName("Short or missing tokens are masked safely")
    void maskEdgeCases() {
        assertEquals("null", TokenSecretsManagerResolver.mask(null));
        assertEquals("RG{}", TokenSecretsManagerResolver.mask("RG{}"));
    }
}
