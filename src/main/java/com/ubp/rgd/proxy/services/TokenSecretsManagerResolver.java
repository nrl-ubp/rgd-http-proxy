package com.ubp.rgd.proxy.services;

import com.mongodb.client.MongoClient;
import com.ubp.rgd.proxy.exception.SecretsManagerNotFoundException;
import com.ubp.rgd.proxy.security.HashUtils;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.*;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

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
    private static final int TOKEN_PREFIX_LENGTH = 6000;

    /** Fetched lazily: the client is only built when a lookup actually needs it. */
    @Inject
    Instance<MongoClient> mongoClient;

    /** Fetched lazily: the datasource stays inactive as long as no JDBC URL is configured. */
    @Inject
    @DataSource(DATASOURCE_NAME)
    Instance<AgroalDataSource> sqlServerDataSource;

    @ConfigProperty(name = "proxy.secrets-manager-lookup.mongodb.database")
    String mongoDatabase;

    @ConfigProperty(name = "proxy.transform.client-id")
    String clientId;

    final String SQL_LOAD_PROPERTIES = "SELECT convert(nvarchar, c.name) as className, convert(nvarchar, p.name) as propertyName, p.propertyId, p.mapping, p.configurationId " +
                    "FROM config.Property p " +
                    "left join config.Class c on p.classId = c.classId " +
                    "left join config.ConfigurationClient cfgclt on cfgclt.configurationId = c.configurationId " +
                    "where cfgclt.clientId = '%s' " +
                    "order by c.name, p.name";

    final String SQL_LOAD_SECRETS_MANAGER_IDS = "SELECT asm.secretsManagerConfigId, asm.SecretsManagerId \n" +
            "FROM config.AccountSecretsManager asm \n" +
            "LEFT JOIN config.ConfigurationSecretsManager csm ON csm.SecretsManagerId = asm.SecretsManagerId \n" +
            "LEFT JOIN config.ConfigurationClient cc ON cc.ConfigurationId = csm.ConfigurationId \n" +
            "WHERE cc.ClientId = '%s'";


    static class RPSConfigProperty {
        public String id;
        public String className;
        public String propertyName;
        public Long mapping;
        public String configurationId;
    }

    /**
     * Loaded post construct to get the properties from the client id in the application.properties file.
     */
    private final Map<String, RPSConfigProperty> configPropertyByName = new HashMap<>();

    /**
     * Loaded secrets CONFIG manager IDs by CLIENT associations.
     * Beware the order: key = secrets managers config id, value the association with client
     */
    private Map<String, String> secretsManagersConfigByClientIds = new HashMap<>();

    /**
     * Initialize configuration id from the client id property.
     * Load from the database so that we do not request it over again and again
     */
    @PostConstruct
    public void init() {
        String query = String.format(SQL_LOAD_PROPERTIES, clientId);
        try (Connection con = sqlServerDataSource().getConnection();
             Statement stmt = con.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            if (!rs.next()) {
                throw new SQLException("Cannot load configuration id. Query did not return any result.");
            }

            do {
                RPSConfigProperty prop = new RPSConfigProperty();
                prop.className = rs.getString(1);
                prop.propertyName = rs.getString(2);
                prop.id = rs.getString(3);
                prop.mapping = rs.getLong(4);
                prop.configurationId = rs.getString(5);

                configPropertyByName.put(String.format("%s.%s", prop.className, prop.propertyName), prop);
            } while (rs.next());

        } catch (SQLException ex) {
            LOG.error("Cannot load configuration id from client id.", ex);
        }

        LOG.info("Loaded {} properties for this client: {}", configPropertyByName.size(), clientId);

        query = String.format(SQL_LOAD_SECRETS_MANAGER_IDS, clientId);
        try (Connection con = sqlServerDataSource().getConnection();
             Statement stmt = con.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            if (!rs.next()) {
                throw new SQLException("Cannot load secrets manager config IDs. Query did not return any result.");
            }

            do {
                // beware the order: key = secrets maangers config id, value the association with client
                secretsManagersConfigByClientIds.put(rs.getString(1), rs.getString(2));
            } while (rs.next());

            LOG.info("Loaded {} secrets manager ID mappings for this client: {}", secretsManagersConfigByClientIds.size(), clientId);

        } catch (SQLException ex) {
            LOG.error("Cannot load configuration id from client id.", ex);
        }

    }

    /**
     * Resolve the secrets manager that created a token.
     *
     * @param token the whole token, including its <code>RG{...}</code> wrapper
     * @return the id of the secrets manager to detokenize the token with
     * @throws SecretsManagerNotFoundException when either lookup finds nothing
     */
    @CacheResult(cacheName = CACHE_NAME)
    public UUID secretsManagerIdResolve(String token, String propertyClassName, String propertyName) {

        RPSConfigProperty prop = configPropertyByName.get(String.format("%s.%s", propertyClassName, propertyName));
        if (prop == null) {
            LOG.error("Could not find property for class and name: {}.{} and client ID: {}",
                    propertyClassName, propertyName, clientId);
            return null;
        }

        BsonDocument mongoResult = lookupInMongoDb(token, prop).orElseThrow(() -> notFound("MongoDB", token));

        // if mongoResult has key secretsManagerId then no need to go further,
        // otherwise search the MSSQL with provided SecretVersionId
        if (mongoResult.containsKey("SecretsManagerId")) {
            UUID secretsManager = mongoResult.get("SecretsManagerId").asBinary().asUuid(UuidRepresentation.C_SHARP_LEGACY);

            // now we need to remap this secret manager to the ASSOCIATION with the client.
            String clientSecretManagerIdStr = secretsManagersConfigByClientIds.get(secretsManager.toString().toUpperCase());

            if (clientSecretManagerIdStr == null) {
                LOG.error("Secrets manager configuration {} did not match a secrets manager CLIENT association for client {}", secretsManager.toString(), clientId);
                return null;
            }

            secretsManager = UUID.fromString(clientSecretManagerIdStr);

            LOG.debug("Token {} resolved to the secrets manager {}", mask(token), secretsManager);
            return secretsManager;
        }

        if (mongoResult.containsKey("SecretVersionId")) {
            String secretVersionId = legacyCSharpBinaryToUuid(mongoResult.get("SecretVersionId").asBinary().getData());
            UUID secretsManager = lookupInSqlServer(token, secretVersionId).orElseThrow(() -> notFound("SQL Server", token));
            LOG.debug("Token {} resolved to the secrets manager {}", mask(token), secretsManager);
            return secretsManager;
        }

        throw notFound("SQL Server", token);
    }

    /**
     * First lookup: read from MongoDB the information about the token that the SQL Server lookup
     * needs.
     *
     * @param token the whole token
     * @return what the SQL Server lookup needs, empty when the token is unknown to MongoDB
     */
    Optional<BsonDocument> lookupInMongoDb(String token, RPSConfigProperty configProperty) {
        // TODO query MongoDB, e.g. mongoClient().getDatabase(mongoDatabase.orElseThrow())
        //      .getCollection(mongoCollection.orElseThrow()).find(...)

        String collectionName = "";

        // build search token whatever wrapper is.
        String searchToken = token.replaceAll("RG\\{", "")
                .replaceAll("}", "")
                .replaceAll("RPSDOC\\+", "").replaceAll("\\+RPSDOC", "")
                .replaceAll("-888", "").replaceAll(".8888", "");

        // remove dash suffix if any
        int dashIndex = searchToken.lastIndexOf("-");

        Bson filter = null;

        if (dashIndex >= 0) {
            collectionName = "ShiftedOPEShortTokens";

            long number = getIndexFromLabel(searchToken);

            // there can be cases when token is of the form : ABCDEF--AB
            // so we use this time the first occurrence of dash - character
            int firstDash = searchToken.indexOf('-');
            searchToken = searchToken.substring(0, firstDash);

            // remove prefix of the property compressed inserter
            searchToken = searchToken.substring(2);

            filter = and(
                    eq("TokenValue", searchToken),
                    eq("Number", number),
                    eq("ConfigurationId", new BsonBinary(UUID.fromString(configProperty.configurationId), UuidRepresentation.C_SHARP_LEGACY)),
                    eq("PropertyId", new BsonBinary(UUID.fromString(configProperty.id), UuidRepresentation.C_SHARP_LEGACY))
            );

        } else if (token.startsWith("RG{") && token.endsWith("@example.com")) {
            collectionName = "EmailUidTokens";

            filter = and(
                    eq("TokenValue", searchToken),
                    eq("ConfigurationId", new BsonBinary(UUID.fromString(configProperty.configurationId), UuidRepresentation.C_SHARP_LEGACY)),
                    eq("PropertyId", new BsonBinary(UUID.fromString(configProperty.id), UuidRepresentation.C_SHARP_LEGACY))
            );

        } else if (token.startsWith("RPSDOC+") && token.endsWith("+RPSDOC")) {
            collectionName = "SecretManagerTransformations";

            // compute MD5 for token value and use it as criteria
            String md5Hash = HashUtils.md5(searchToken);

            filter = and(
                    eq("ValueHash", md5Hash),
                    eq("ConfigurationId", new BsonBinary(UUID.fromString(configProperty.configurationId), UuidRepresentation.C_SHARP_LEGACY)),
                    eq("PropertyId", new BsonBinary(UUID.fromString(configProperty.id), UuidRepresentation.C_SHARP_LEGACY))
            );

        } else if (token.startsWith("-888") && token.endsWith(".8888")) {
            collectionName = "SequenceToken";

            filter = and(
                    eq("TokenValue", searchToken),
                    eq("ConfigurationId", new BsonBinary(UUID.fromString(configProperty.configurationId), UuidRepresentation.C_SHARP_LEGACY)),
                    eq("PropertyId", new BsonBinary(UUID.fromString(configProperty.id), UuidRepresentation.C_SHARP_LEGACY))
            );

        } else {
            collectionName = "DateTokenV2s";
            searchToken = String.format("%sT00:00:00.0000000Z", token);

            filter = and(
                    eq("TokenValue", searchToken),
                    eq("ConfigurationId", new BsonBinary(UUID.fromString(configProperty.configurationId), UuidRepresentation.C_SHARP_LEGACY)),
                    eq("PropertyId", new BsonBinary(UUID.fromString(configProperty.id), UuidRepresentation.C_SHARP_LEGACY))
            );
        }

        Document resultDoc = mongoClient().getDatabase(mongoDatabase)
                .getCollection(collectionName)
                .find(filter).first();

        return resultDoc == null ? Optional.empty() : Optional.of(BsonDocument.parse(resultDoc.toJson()));
    }

    /**
     * Second lookup: read from SQL Server the secrets manager matching the MongoDB result.
     *
     * @param token       the whole token
     * @param secretVersionId what method lookupInMongoDb() returned for that token
     * @return the secrets manager id, empty when SQL Server has no match
     */
    Optional<UUID> lookupInSqlServer(String token, String secretVersionId) {
        // TODO query SQL Server, e.g. try (Connection c = sqlServerDataSource().getConnection();
        //      PreparedStatement s = c.prepareStatement("...")) { ... }

        String query = String.format("SELECT asm.SecretsManagerId as SecretsManagerId" +
                "  FROM [CoreConfiguration_poc].[config].[SecretCurrentVersion] sv" +
                "  LEFT JOIN config.AccountSecretsManager asm ON asm.SecretsManagerConfigId = sv.SecretsManagerId" +
                "  WHERE sv.SecretVersionId = '%s'", secretVersionId);

        Optional<UUID> result = Optional.empty();

        try (Connection c = sqlServerDataSource().getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            if (rs.next()) {
                result = Optional.of(UUID.fromString(rs.getString(1)));
            }
        } catch (SQLException sqle) {
            LOG.error("Cannot query the RegData configuration database", sqle);
        }

        return result;
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

    public String legacyCSharpBinaryToUuid(byte[] source) {

        if (source == null || source.length != 16) {
            throw new IllegalArgumentException(
                    "Un UUID BSON doit contenir exactement 16 octets"
            );
        }

        byte[] uuidBytes = source.clone();

        // Inversion des trois premiers composants UUID :
        // 4 octets, puis 2 octets, puis 2 octets.
        reverse(uuidBytes, 0, 4);
        reverse(uuidBytes, 4, 6);
        reverse(uuidBytes, 6, 8);

        ByteBuffer buffer = ByteBuffer.wrap(uuidBytes);

        long mostSignificantBits = buffer.getLong();
        long leastSignificantBits = buffer.getLong();

        return new UUID(mostSignificantBits, leastSignificantBits).toString();
    }

    private void reverse(byte[] bytes, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive, j = toExclusive - 1; i < j; i++, j--) {
            byte temporary = bytes[i];
            bytes[i] = bytes[j];
            bytes[j] = temporary;
        }
    }

    /**
     * Resolves the potential suffix of the token of the form ABCDE-AB.
     * If not the right form, then returns -1
     * @param token the token to get the suffix number
     * @return number of the token to be used in MongoDb search to get the credentialsManagerId
     */
    public static int getIndexFromLabel(String token) {
        if (token == null || token.length() < 2) {
            return -1;
        }

        String suffixe = token.substring(token.length() - 2).toUpperCase();

        char premiereLettre = suffixe.charAt(0);
        char deuxiemeLettre = suffixe.charAt(1);

        if (premiereLettre < 'A' || premiereLettre > 'Z'
                || deuxiemeLettre < 'A' || deuxiemeLettre > 'Z') {
            return -1;
        }

        int valeurPremiereLettre = premiereLettre - 'A';
        int valeurDeuxiemeLettre = deuxiemeLettre - 'A';

        return valeurPremiereLettre * 26 + valeurDeuxiemeLettre;
    }

}
