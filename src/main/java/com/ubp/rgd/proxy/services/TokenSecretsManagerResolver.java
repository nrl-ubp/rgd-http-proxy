package com.ubp.rgd.proxy.services;

import com.mongodb.client.MongoClient;
import com.ubp.rgd.proxy.exception.SecretsManagerNotFoundException;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the RPS secrets manager that created a token.
 * <p>
 * Used by the RPS transformer to detokenize the values whose secrets manager the caller does not
 * know, that is the Flight SQL result sets and the file transformations: their tokens may come from
 * different jurisdictions, each one protected by its own secrets manager.
 * <p>
 * The resolution takes two chained lookups, in this order: MongoDB holds part of the information,
 * and SQL Server turns it into the secrets manager id. A token found in neither fails the
 * detokenization with a {@link SecretsManagerNotFoundException}.
 * <p>
 * Successful resolutions are cached (cache {@value #CACHE_NAME}, sized in the configuration);
 * failures are not, so they are retried on the next call.
 */
@ApplicationScoped
public class TokenSecretsManagerResolver {

    private static final Logger LOG = LoggerFactory.getLogger(TokenSecretsManagerResolver.class);

    public static final String CACHE_NAME = "token-secrets-manager";

    /** Name of the Quarkus datasource queried by {@link #lookupInSqlServer(String, String)}. */
    public static final String DATASOURCE_NAME = "secrets-manager-lookup";

    /** Number of token characters kept in the logs and error messages. */
    private static final int TOKEN_PREFIX_LENGTH = 6;

    /** Fetched lazily: the client is only built when a lookup actually needs it. */
    @Inject
    Instance<MongoClient> mongoClient;

    /** Fetched lazily: the datasource stays inactive as long as no JDBC URL is configured. */
    @Inject
    @DataSource(DATASOURCE_NAME)
    Instance<AgroalDataSource> sqlServerDataSource;

    @ConfigProperty(name = "proxy.secrets-manager-lookup.mongodb.database")
    Optional<String> mongoDatabase;

    @ConfigProperty(name = "proxy.secrets-manager-lookup.mongodb.collection")
    Optional<String> mongoCollection;

    /**
     * Resolve the secrets manager that created a token.
     *
     * @param token the whole token, including its <code>RG{...}</code> wrapper
     * @return the id of the secrets manager to detokenize the token with
     * @throws SecretsManagerNotFoundException when either lookup finds nothing
     */
    @CacheResult(cacheName = CACHE_NAME)
    public UUID secretsManagerIdResolve(String token) {
        String mongoResult = lookupInMongoDb(token).orElseThrow(() -> notFound("MongoDB", token));
        UUID secretsManager = lookupInSqlServer(token, mongoResult).orElseThrow(() -> notFound("SQL Server", token));
        LOG.debug("Token {} resolved to the secrets manager {}", mask(token), secretsManager);
        return secretsManager;
    }

    /**
     * First lookup: read from MongoDB the information about the token that the SQL Server lookup
     * needs.
     *
     * @param token the whole token
     * @return what the SQL Server lookup needs, empty when the token is unknown to MongoDB
     */
    Optional<String> lookupInMongoDb(String token) {
        // TODO query MongoDB, e.g. mongoClient().getDatabase(mongoDatabase.orElseThrow())
        //      .getCollection(mongoCollection.orElseThrow()).find(...)
        return Optional.empty();
    }

    /**
     * Second lookup: read from SQL Server the secrets manager matching the MongoDB result.
     *
     * @param token       the whole token
     * @param mongoResult what {@link #lookupInMongoDb(String)} returned for that token
     * @return the secrets manager id, empty when SQL Server has no match
     */
    Optional<UUID> lookupInSqlServer(String token, String mongoResult) {
        // TODO query SQL Server, e.g. try (Connection c = sqlServerDataSource().getConnection();
        //      PreparedStatement s = c.prepareStatement("...")) { ... }
        return Optional.empty();
    }

    /** The MongoDB client configured by {@code quarkus.mongodb.*}. */
    MongoClient mongoClient() {
        return mongoClient.get();
    }

    /** The SQL Server datasource configured by {@code quarkus.datasource."secrets-manager-lookup".*}. */
    AgroalDataSource sqlServerDataSource() {
        return sqlServerDataSource.get();
    }

    private static SecretsManagerNotFoundException notFound(String source, String token) {
        return new SecretsManagerNotFoundException(
                "No secrets manager found in " + source + " for the token " + mask(token));
    }

    /** Keep only the start of a token, so that it never ends up whole in a log or an error. */
    static String mask(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= TOKEN_PREFIX_LENGTH ? token : token.substring(0, TOKEN_PREFIX_LENGTH) + "...";
    }
}
