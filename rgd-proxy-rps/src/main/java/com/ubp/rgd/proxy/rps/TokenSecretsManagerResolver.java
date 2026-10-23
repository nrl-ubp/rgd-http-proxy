package com.ubp.rgd.proxy.rps;

import com.mongodb.client.MongoClient;
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
import java.util.regex.Pattern;

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
 * The resolution takes up to two chained lookups. MongoDB is queried first, in the collection of
 * the token format (see {@link TokenKind}), for the RPS configuration and property the token belongs
 * to. Its document either names the secrets manager configuration, then mapped to the secrets manager
 * associated with the client, or a secret version, then turned into a secrets manager id by SQL
 * Server. The client configuration (properties and secrets manager associations) is loaded once at
 * startup. Any failure, such as an unknown property, an unsupported token format or a token found
 * nowhere, fails the detokenization with a {@link SecretsManagerNotFoundException}.
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

    private static final String RPSDOC_PREFIX = "RPSDOC+";
    private static final String RPSDOC_SUFFIX = "+RPSDOC";
    private static final String SEQUENCE_PREFIX = "-888";
    private static final String SEQUENCE_SUFFIX = ".8888";
    private static final Pattern RG_TOKEN = Pattern.compile("RG\\{[^{}]+}");
    private static final Pattern EMAIL_TOKEN = Pattern.compile("RG\\{[^{}]+}@example\\.com");
    private static final Pattern DATE_TOKEN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * The token formats, each one stored in its own MongoDB collection. The wrapper tells them apart.
     */
    public enum TokenKind {
        /** {@code RPSDOC+...+RPSDOC}, found by the MD5 of its value. */
        RPSDOC("SecretManagerTransformations"),
        /** {@code -888...8888}. */
        SEQUENCE("SequenceToken"),
        /** {@code RG{...}@example.com}. */
        EMAIL_UID("EmailUidTokens"),
        /** A raw {@code yyyy-MM-dd} date. */
        DATE("DateTokenV2s"),
        /** {@code RG{...-XY}}: the letters after the last dash are the token number. */
        SHIFTED_OPE("ShiftedOPEShortTokens"),
        /** Any other {@code RG{...}}: no collection is known for it, so it cannot be resolved. */
        RG_OTHER(null);

        final String collection;

        TokenKind(String collection) {
            this.collection = collection;
        }
    }

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


    public static class RPSConfigProperty {
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

        } catch (Exception ex) {
            // also catches an unavailable datasource: the resolutions will then fail one by one
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

        } catch (Exception ex) {
            LOG.error("Cannot load the secrets manager ids of the client.", ex);
        }

    }

    /**
     * Resolve the secrets manager that created a token.
     *
     * @param token             the whole token, with its wrapper (see {@link TokenKind})
     * @param propertyClassName the RPS class the token belongs to
     * @param propertyName      the RPS property the token belongs to
     * @return the id of the secrets manager to detokenize the token with, never {@code null}
     * @throws SecretsManagerNotFoundException when the property is unknown to the client, the token
     *         format is not supported, or a lookup finds nothing
     */
    @CacheResult(cacheName = CACHE_NAME)
    public UUID secretsManagerIdResolve(String token, String propertyClassName, String propertyName) {

        RPSConfigProperty prop = findProperty(propertyClassName, propertyName);
        if (prop == null) {
            throw new SecretsManagerNotFoundException(String.format(
                    "The RPS property %s.%s is not configured for the client %s, cannot resolve the secrets manager of the token %s",
                    propertyClassName, propertyName, clientId, mask(token)));
        }

        BsonDocument mongoResult = lookupInMongoDb(token, prop).orElseThrow(() -> notFound("MongoDB", token));

        // if mongoResult has key secretsManagerId then no need to go further,
        // otherwise search the MSSQL with provided SecretVersionId
        if (mongoResult.containsKey("SecretsManagerId")) {
            UUID secretsManagerConfig = mongoResult.get("SecretsManagerId").asBinary().asUuid(UuidRepresentation.C_SHARP_LEGACY);

            // now we need to remap this secret manager to the ASSOCIATION with the client.
            String clientSecretManagerIdStr = secretsManagersConfigByClientIds.get(secretsManagerConfig.toString().toUpperCase());

            if (clientSecretManagerIdStr == null) {
                throw new SecretsManagerNotFoundException(String.format(
                        "The secrets manager configuration %s of the token %s has no association with the client %s",
                        secretsManagerConfig, mask(token), clientId));
            }

            UUID secretsManager = UUID.fromString(clientSecretManagerIdStr);
            LOG.debug("Token {} resolved to the secrets manager {}", mask(token), secretsManager);
            return secretsManager;
        }

        if (mongoResult.containsKey("SecretVersionId")) {
            String secretVersionId = legacyCSharpBinaryToUuid(mongoResult.get("SecretVersionId").asBinary().getData());
            UUID secretsManager = lookupInSqlServer(token, secretVersionId).orElseThrow(() -> notFound("SQL Server", token));
            LOG.debug("Token {} resolved to the secrets manager {}", mask(token), secretsManager);
            return secretsManager;
        }

        throw new SecretsManagerNotFoundException(
                "The MongoDB document of the token " + mask(token) + " has neither a SecretsManagerId nor a SecretVersionId");
    }

    /**
     * The RPS property of the client, loaded by {@link #init()}.
     *
     * @return the property, {@code null} when the client has no such property
     */
    protected RPSConfigProperty findProperty(String propertyClassName, String propertyName) {
        return configPropertyByName.get(String.format("%s.%s", propertyClassName, propertyName));
    }

    /**
     * First lookup: find in MongoDB the document of the token, in the collection of its format.
     *
     * @param token          the whole token
     * @param configProperty the RPS property the token belongs to
     * @return the document, empty when the token is unknown to MongoDB
     * @throws SecretsManagerNotFoundException when the token format is not supported
     */
    protected Optional<BsonDocument> lookupInMongoDb(String token, RPSConfigProperty configProperty) {
        TokenKind kind = kindOf(token).orElseThrow(() -> unsupported(token));

        BsonBinary configurationId = new BsonBinary(UUID.fromString(configProperty.configurationId), UuidRepresentation.C_SHARP_LEGACY);
        BsonBinary propertyId = new BsonBinary(UUID.fromString(configProperty.id), UuidRepresentation.C_SHARP_LEGACY);

        Bson filter = switch (kind) {
            case SHIFTED_OPE -> {
                String searchToken = unwrapRg(token);
                int number = getIndexFromLabel(searchToken);
                // there can be cases when token is of the form : ABCDEF--AB
                // so we use this time the first occurrence of dash - character
                int firstDash = searchToken.indexOf('-');
                if (number < 0 || firstDash < 2) {
                    throw unsupported(token);
                }
                // remove prefix of the property compressed inserter
                searchToken = searchToken.substring(2, firstDash);
                yield and(eq("TokenValue", searchToken), eq("Number", number),
                        eq("ConfigurationId", configurationId), eq("PropertyId", propertyId));
            }
            case EMAIL_UID -> and(eq("TokenValue", token.replace("RG{", "").replace("}", "")),
                    eq("ConfigurationId", configurationId), eq("PropertyId", propertyId));
            case RPSDOC -> and(
                    // the documents are found by the MD5 of the token value
                    eq("ValueHash", HashUtils.md5(token.substring(RPSDOC_PREFIX.length(), token.length() - RPSDOC_SUFFIX.length()))),
                    eq("ConfigurationId", configurationId), eq("PropertyId", propertyId));
            case SEQUENCE -> and(eq("TokenValue", token.substring(SEQUENCE_PREFIX.length(), token.length() - SEQUENCE_SUFFIX.length())),
                    eq("ConfigurationId", configurationId), eq("PropertyId", propertyId));
            case DATE -> and(eq("TokenValue", String.format("%sT00:00:00.0000000Z", token)),
                    eq("ConfigurationId", configurationId), eq("PropertyId", propertyId));
            case RG_OTHER -> throw unsupported(token);
        };

        Document resultDoc = mongoClient().getDatabase(mongoDatabase)
                .getCollection(kind.collection)
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
    protected Optional<UUID> lookupInSqlServer(String token, String secretVersionId) {
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

    /**
     * Tell the format of a token. The specific wrappers are checked first: a date or an e-mail token
     * also contains characters, such as a dash, that the other formats rely on.
     *
     * @param value the value to classify
     * @return its format, empty when the value is not a token
     */
    public static Optional<TokenKind> kindOf(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > RPSDOC_PREFIX.length() + RPSDOC_SUFFIX.length()
                && value.startsWith(RPSDOC_PREFIX) && value.endsWith(RPSDOC_SUFFIX)) {
            return Optional.of(TokenKind.RPSDOC);
        }
        if (value.length() > SEQUENCE_PREFIX.length() + SEQUENCE_SUFFIX.length()
                && value.startsWith(SEQUENCE_PREFIX) && value.endsWith(SEQUENCE_SUFFIX)) {
            return Optional.of(TokenKind.SEQUENCE);
        }
        if (EMAIL_TOKEN.matcher(value).matches()) {
            return Optional.of(TokenKind.EMAIL_UID);
        }
        if (DATE_TOKEN.matcher(value).matches()) {
            return Optional.of(TokenKind.DATE);
        }
        if (RG_TOKEN.matcher(value).matches()) {
            return Optional.of(unwrapRg(value).contains("-") ? TokenKind.SHIFTED_OPE : TokenKind.RG_OTHER);
        }
        return Optional.empty();
    }

    /**
     * Whether a value is a token whose secrets manager has to be resolved.
     *
     * @param value the value to check
     * @return {@code true} for any {@link TokenKind}, even one that cannot be resolved
     */
    public static boolean isToken(String value) {
        return kindOf(value).isPresent();
    }

    private static String unwrapRg(String token) {
        return token.substring(3, token.length() - 1);
    }

    private static SecretsManagerNotFoundException unsupported(String token) {
        return new SecretsManagerNotFoundException(
                "The format of the token " + mask(token) + " is not supported, cannot resolve its secrets manager");
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
     * Read the token number carried by the letters after the last dash, e.g. {@code ABCDEF-AB}.
     * <p>
     * Two letters count from {@code AA} = 0 to {@code ZZ} = 675, three letters go on from
     * {@code AAA} = 676, and so on: each length starts where the previous one ended.
     *
     * @param token the token, or its value, ending with {@code -} and at least two letters
     * @return number of the token to be used in MongoDb search, -1 when the token has no such suffix
     */
    public static int getIndexFromLabel(String token) {
        if (token == null) {
            return -1;
        }
        String suffix = token.substring(token.lastIndexOf('-') + 1).toUpperCase();
        if (suffix.length() < 2 || suffix.length() > 6) {
            return -1;
        }

        long offset = 0;
        long size = 26L * 26L;
        for (int length = 2; length < suffix.length(); length++) {
            offset += size;
            size *= 26L;
        }

        long value = 0;
        for (char letter : suffix.toCharArray()) {
            if (letter < 'A' || letter > 'Z') {
                return -1;
            }
            value = value * 26 + (letter - 'A');
        }
        return (int) (offset + value);
    }

}
