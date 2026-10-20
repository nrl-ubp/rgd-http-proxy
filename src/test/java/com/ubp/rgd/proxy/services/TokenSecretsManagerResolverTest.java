package com.ubp.rgd.proxy.services;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.ubp.rgd.proxy.exception.SecretsManagerNotFoundException;
import com.ubp.rgd.proxy.security.HashUtils;
import com.ubp.rgd.proxy.services.TokenSecretsManagerResolver.RPSConfigProperty;
import com.ubp.rgd.proxy.services.TokenSecretsManagerResolver.TokenKind;
import io.agroal.api.AgroalDataSource;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks {@link TokenSecretsManagerResolver}: the loading of the client configuration, the chaining
 * of its MongoDB and SQL Server lookups, and the queries those lookups send. No database is reached:
 * the clients are mocked.
 */
class TokenSecretsManagerResolverTest {

    private static final String TOKEN = "RG{BxAbCd1234SecretPart-AB}";
    private static final UUID CH = UUID.fromString("16ec8462-e8d5-4a2c-b8df-f253e09bd274");
    private static final UUID SM_CONFIG = UUID.fromString("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9");
    private static final UUID SECRET_VERSION = UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00");
    private static final UUID CONFIGURATION_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID PROPERTY_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    private static RPSConfigProperty property() {
        RPSConfigProperty prop = new RPSConfigProperty();
        prop.id = PROPERTY_ID.toString();
        prop.className = "Person";
        prop.propertyName = "ShortString";
        prop.mapping = 3L;
        prop.configurationId = CONFIGURATION_ID.toString();
        return prop;
    }

    private static BsonDocument documentWith(String key, UUID uuid) {
        BsonDocument doc = new BsonDocument();
        doc.put(key, new BsonBinary(uuid, UuidRepresentation.C_SHARP_LEGACY));
        return doc;
    }

    /** A {@link ResultSet} over the given rows, read by column index. */
    private static ResultSet rows(List<Object[]> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] cursor = {-1};
        when(rs.next()).thenAnswer(i -> ++cursor[0] < rows.size());
        when(rs.getString(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> String.valueOf(rows.get(cursor[0])[(int) i.getArgument(0) - 1]));
        when(rs.getLong(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> (Long) rows.get(cursor[0])[(int) i.getArgument(0) - 1]);
        return rs;
    }

    /** A datasource answering each query with the rows of the first matching fragment. */
    private static AgroalDataSource dataSource(String fragment1, List<Object[]> rows1,
                                               String fragment2, List<Object[]> rows2) throws SQLException {
        AgroalDataSource ds = mock(AgroalDataSource.class);
        Connection con = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(con);
        when(con.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenAnswer(i -> {
            String sql = i.getArgument(0);
            if (sql.contains(fragment1)) {
                return rows(rows1);
            }
            if (fragment2 != null && sql.contains(fragment2)) {
                return rows(rows2);
            }
            return rows(List.of());
        });
        return ds;
    }

    /** Records the lookups and answers with the configured results. */
    private static class StubResolver extends TokenSecretsManagerResolver {
        final List<String> calls = new ArrayList<>();
        RPSConfigProperty property = property();
        Optional<BsonDocument> mongoResult = Optional.empty();
        Optional<UUID> sqlServerResult = Optional.empty();

        @Override
        RPSConfigProperty findProperty(String propertyClassName, String propertyName) {
            return property;
        }

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

    @Nested
    @DisplayName("Loading the client configuration")
    class Init {

        @Test
        @DisplayName("The properties and secrets manager associations of the client are loaded")
        void loadsTheClientConfiguration() throws SQLException {
            AgroalDataSource ds = dataSource(
                    "config.Property", List.<Object[]>of(new Object[]{"Person", "ShortString", PROPERTY_ID.toString(), 3L, CONFIGURATION_ID.toString()}),
                    "AccountSecretsManager", List.<Object[]>of(new Object[]{SM_CONFIG.toString().toUpperCase(), CH.toString()}));
            TokenSecretsManagerResolver resolver = new TokenSecretsManagerResolver() {
                @Override
                AgroalDataSource sqlServerDataSource() {
                    return ds;
                }

                @Override
                Optional<BsonDocument> lookupInMongoDb(String token, RPSConfigProperty prop) {
                    return Optional.of(documentWith("SecretsManagerId", SM_CONFIG));
                }
            };
            resolver.clientId = "client-1";

            resolver.init();

            RPSConfigProperty prop = resolver.findProperty("Person", "ShortString");
            assertEquals(PROPERTY_ID.toString(), prop.id);
            assertEquals(3L, prop.mapping);
            assertEquals(CONFIGURATION_ID.toString(), prop.configurationId);
            assertEquals(null, resolver.findProperty("Person", "Unknown"));
            // the association loaded above remaps the secrets manager configuration of the token
            assertEquals(CH, resolver.secretsManagerIdResolve(TOKEN, "Person", "ShortString"));
        }

        @Test
        @DisplayName("An unavailable datasource does not prevent the start, resolutions fail instead")
        void toleratesAnUnavailableDatasource() {
            TokenSecretsManagerResolver resolver = new TokenSecretsManagerResolver() {
                @Override
                AgroalDataSource sqlServerDataSource() {
                    throw new IllegalStateException("datasource inactive");
                }
            };

            resolver.init();

            assertThrows(SecretsManagerNotFoundException.class,
                    () -> resolver.secretsManagerIdResolve(TOKEN, "Person", "ShortString"));
        }
    }

    @Nested
    @DisplayName("Chaining the lookups")
    class Chain {

        @Test
        @DisplayName("A property unknown to the client fails without querying MongoDB")
        void unknownProperty() {
            StubResolver resolver = new StubResolver();
            resolver.property = null;

            SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                    () -> resolver.secretsManagerIdResolve(TOKEN, "Person", "Unknown"));

            assertTrue(e.getMessage().contains("Person.Unknown"), e.getMessage());
            assertTrue(resolver.calls.isEmpty());
        }

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
        @DisplayName("A SecretsManagerId is remapped to the secrets manager associated with the client")
        void secretsManagerIdShortcut() throws Exception {
            StubResolver resolver = new StubResolver();
            resolver.mongoResult = Optional.of(documentWith("SecretsManagerId", SM_CONFIG));
            associate(resolver, SM_CONFIG.toString().toUpperCase(), CH.toString());

            assertEquals(CH, resolver.secretsManagerIdResolve(TOKEN, "Person", "ShortString"));
            assertEquals(List.of("mongo:" + TOKEN), resolver.calls);
        }

        @Test
        @DisplayName("A SecretsManagerId without association with the client fails")
        void secretsManagerIdWithoutAssociation() {
            StubResolver resolver = new StubResolver();
            resolver.mongoResult = Optional.of(documentWith("SecretsManagerId", SM_CONFIG));

            SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                    () -> resolver.secretsManagerIdResolve(TOKEN, "Person", "ShortString"));

            assertTrue(e.getMessage().contains("no association"), e.getMessage());
        }

        @Test
        @DisplayName("A SecretVersionId is turned into a secrets manager by SQL Server")
        void secretVersionIdGoesToSqlServer() {
            StubResolver resolver = new StubResolver();
            resolver.mongoResult = Optional.of(documentWith("SecretVersionId", SECRET_VERSION));
            resolver.sqlServerResult = Optional.of(CH);

            assertEquals(CH, resolver.secretsManagerIdResolve(TOKEN, "Person", "ShortString"));
            assertEquals(List.of("mongo:" + TOKEN, "sql:" + TOKEN + ":" + SECRET_VERSION), resolver.calls);
        }

        @Test
        @DisplayName("A secret version SQL Server does not know fails")
        void notFoundInSqlServer() {
            StubResolver resolver = new StubResolver();
            resolver.mongoResult = Optional.of(documentWith("SecretVersionId", SECRET_VERSION));

            SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                    () -> resolver.secretsManagerIdResolve(TOKEN, "Person", "ShortString"));

            assertTrue(e.getMessage().contains("SQL Server"), e.getMessage());
        }

        @Test
        @DisplayName("A MongoDB document without any secrets manager reference fails")
        void documentWithoutReference() {
            StubResolver resolver = new StubResolver();
            resolver.mongoResult = Optional.of(BsonDocument.parse("{ other: 'x' }"));

            SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                    () -> resolver.secretsManagerIdResolve(TOKEN, "Person", "ShortString"));

            assertTrue(e.getMessage().contains("neither"), e.getMessage());
            assertEquals(List.of("mongo:" + TOKEN), resolver.calls);
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

        /** Fill the association map the way {@link TokenSecretsManagerResolver#init()} does. */
        private void associate(TokenSecretsManagerResolver resolver, String smConfigId, String smId) throws Exception {
            var field = TokenSecretsManagerResolver.class.getDeclaredField("secretsManagersConfigByClientIds");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, String>) field.get(resolver);
            map.put(smConfigId, smId);
        }
    }

    @Nested
    @DisplayName("Querying MongoDB")
    class MongoDb {

        private final ArgumentCaptor<String> collection = ArgumentCaptor.forClass(String.class);
        private final ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);

        /** A resolver whose MongoDB client returns the given document for any query. */
        @SuppressWarnings("unchecked")
        private TokenSecretsManagerResolver resolver(Document found) {
            MongoClient client = mock(MongoClient.class);
            MongoDatabase database = mock(MongoDatabase.class);
            MongoCollection<Document> coll = mock(MongoCollection.class);
            FindIterable<Document> iterable = mock(FindIterable.class);
            when(client.getDatabase("rgd")).thenReturn(database);
            when(database.getCollection(collection.capture())).thenReturn(coll);
            when(coll.find(filter.capture())).thenReturn(iterable);
            when(iterable.first()).thenReturn(found);

            TokenSecretsManagerResolver resolver = new TokenSecretsManagerResolver() {
                @Override
                MongoClient mongoClient() {
                    return client;
                }
            };
            resolver.mongoDatabase = "rgd";
            return resolver;
        }

        /** The query sent, its {@code $and} conditions merged into one document. */
        private BsonDocument query() {
            BsonDocument rendered = filter.getValue().toBsonDocument();
            if (!rendered.containsKey("$and")) {
                return rendered;
            }
            BsonDocument merged = new BsonDocument();
            rendered.getArray("$and").forEach(condition -> merged.putAll(condition.asDocument()));
            return merged;
        }

        private void assertScopedToTheProperty(BsonDocument query) {
            assertEquals(new BsonBinary(CONFIGURATION_ID, UuidRepresentation.C_SHARP_LEGACY), query.get("ConfigurationId"));
            assertEquals(new BsonBinary(PROPERTY_ID, UuidRepresentation.C_SHARP_LEGACY), query.get("PropertyId"));
        }

        @Test
        @DisplayName("A shifted OPE token is found by its value and its number")
        void shiftedOpe() {
            Document found = new Document("SecretVersionId", "x");
            Optional<BsonDocument> result = resolver(found).lookupInMongoDb("RG{BxCDEF-AB}", property());

            assertEquals("x", result.orElseThrow().getString("SecretVersionId").getValue());
            assertEquals("ShiftedOPEShortTokens", collection.getValue());
            BsonDocument query = query();
            assertEquals("CDEF", query.getString("TokenValue").getValue());
            assertEquals(1, query.getInt32("Number").getValue());
            assertScopedToTheProperty(query);
        }

        @Test
        @DisplayName("A token whose value is a double dash keeps the part before the first dash")
        void shiftedOpeWithDoubleDash() {
            resolver(null).lookupInMongoDb("RG{BxCDEF--AAA}", property());

            assertEquals("CDEF", query().getString("TokenValue").getValue());
            assertEquals(676, query().getInt32("Number").getValue());
        }

        @Test
        @DisplayName("An e-mail token is found without its RG wrapper")
        void email() {
            resolver(null).lookupInMongoDb("RG{abc123}@example.com", property());

            assertEquals("EmailUidTokens", collection.getValue());
            assertEquals("abc123@example.com", query().getString("TokenValue").getValue());
            assertScopedToTheProperty(query());
        }

        @Test
        @DisplayName("An RPSDOC token is found by the MD5 of its value")
        void rpsdoc() {
            resolver(null).lookupInMongoDb("RPSDOC+payload+RPSDOC", property());

            assertEquals("SecretManagerTransformations", collection.getValue());
            assertEquals(HashUtils.md5("payload"), query().getString("ValueHash").getValue());
            assertScopedToTheProperty(query());
        }

        @Test
        @DisplayName("A sequence token is found without its wrapper")
        void sequence() {
            resolver(null).lookupInMongoDb("-888123456.8888", property());

            assertEquals("SequenceToken", collection.getValue());
            assertEquals("123456", query().getString("TokenValue").getValue());
        }

        @Test
        @DisplayName("A date token is found as a midnight UTC date, not as a shifted OPE token")
        void date() {
            resolver(null).lookupInMongoDb("1985-03-12", property());

            assertEquals("DateTokenV2s", collection.getValue());
            assertEquals("1985-03-12T00:00:00.0000000Z", query().getString("TokenValue").getValue());
            assertFalse(query().containsKey("Number"));
        }

        @Test
        @DisplayName("A token MongoDB does not know gives an empty result")
        void notFound() {
            assertTrue(resolver(null).lookupInMongoDb("RG{BxCDEF-AB}", property()).isEmpty());
        }

        @Test
        @DisplayName("A token of an unsupported format fails without querying MongoDB")
        void unsupportedFormat() {
            TokenSecretsManagerResolver resolver = resolver(null);

            for (String token : List.of("RG{nodash}", "RG{BxCDEF-A1}", "RG{X-AB}")) {
                SecretsManagerNotFoundException e = assertThrows(SecretsManagerNotFoundException.class,
                        () -> resolver.lookupInMongoDb(token, property()), token);
                assertTrue(e.getMessage().contains("not supported"), e.getMessage());
            }
            assertTrue(collection.getAllValues().isEmpty());
        }
    }

    @Nested
    @DisplayName("Querying SQL Server")
    class SqlServer {

        private TokenSecretsManagerResolver resolver(AgroalDataSource ds) {
            return new TokenSecretsManagerResolver() {
                @Override
                AgroalDataSource sqlServerDataSource() {
                    return ds;
                }
            };
        }

        @Test
        @DisplayName("The secret version is turned into the secrets manager id")
        void found() throws SQLException {
            AgroalDataSource ds = dataSource(SECRET_VERSION.toString(), List.<Object[]>of(new Object[]{CH.toString()}), null, null);

            assertEquals(Optional.of(CH), resolver(ds).lookupInSqlServer(TOKEN, SECRET_VERSION.toString()));
        }

        @Test
        @DisplayName("An unknown secret version gives an empty result")
        void notFound() throws SQLException {
            AgroalDataSource ds = dataSource("never-matched", List.of(), null, null);

            assertTrue(resolver(ds).lookupInSqlServer(TOKEN, SECRET_VERSION.toString()).isEmpty());
        }

        @Test
        @DisplayName("A database error gives an empty result")
        void databaseError() throws SQLException {
            AgroalDataSource ds = mock(AgroalDataSource.class);
            when(ds.getConnection()).thenThrow(new SQLException("down"));

            assertTrue(resolver(ds).lookupInSqlServer(TOKEN, SECRET_VERSION.toString()).isEmpty());
        }
    }

    @Nested
    @DisplayName("Helpers")
    class Helpers {

        @Test
        @DisplayName("A C# legacy binary UUID is decoded")
        void legacyUuid() {
            byte[] data = new BsonBinary(SECRET_VERSION, UuidRepresentation.C_SHARP_LEGACY).getData();

            assertEquals(SECRET_VERSION.toString(), new TokenSecretsManagerResolver().legacyCSharpBinaryToUuid(data));
            assertThrows(IllegalArgumentException.class, () -> new TokenSecretsManagerResolver().legacyCSharpBinaryToUuid(new byte[3]));
            assertThrows(IllegalArgumentException.class, () -> new TokenSecretsManagerResolver().legacyCSharpBinaryToUuid(null));
        }

        @Test
        @DisplayName("Test the number suffix resolve")
        void suffixeResolverTest() {
            assertEquals(0, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-AA"));
            assertEquals(1, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-AB"));
            assertEquals(2, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-AC"));
            assertEquals(25, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-AZ"));
            assertEquals(26, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-BA"));
            assertEquals(675, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-ZZ"));
            assertEquals(676, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-AAA"));
            assertEquals(677, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-AAB"));
            assertEquals(678, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-AAC"));
            assertEquals(676 + 26 * 26 * 26, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-AAAA"));
            assertEquals(1, TokenSecretsManagerResolver.getIndexFromLabel("abcdef-ab"));
        }

        @Test
        @DisplayName("A suffix that is not 2 to 6 letters has no number")
        void invalidSuffix() {
            assertEquals(-1, TokenSecretsManagerResolver.getIndexFromLabel(null));
            assertEquals(-1, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-A"));
            assertEquals(-1, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-A1"));
            assertEquals(-1, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-AAAAAAA"));
            assertEquals(-1, TokenSecretsManagerResolver.getIndexFromLabel("ABCDEF-"));
        }

        @Test
        @DisplayName("Each token format is recognized, plain values are not tokens")
        void tokenKinds() {
            assertEquals(Optional.of(TokenKind.RPSDOC), TokenSecretsManagerResolver.kindOf("RPSDOC+x+RPSDOC"));
            assertEquals(Optional.of(TokenKind.SEQUENCE), TokenSecretsManagerResolver.kindOf("-8881.8888"));
            assertEquals(Optional.of(TokenKind.EMAIL_UID), TokenSecretsManagerResolver.kindOf("RG{a-b}@example.com"));
            assertEquals(Optional.of(TokenKind.DATE), TokenSecretsManagerResolver.kindOf("2024-01-31"));
            assertEquals(Optional.of(TokenKind.SHIFTED_OPE), TokenSecretsManagerResolver.kindOf("RG{BxCDEF-AB}"));
            assertEquals(Optional.of(TokenKind.RG_OTHER), TokenSecretsManagerResolver.kindOf("RG{short}"));

            for (String plain : List.of("Bernadette", "", " ", "RPSDOC++RPSDOC", "-888.8888", "RG{}", "x RG{a-b}")) {
                assertFalse(TokenSecretsManagerResolver.isToken(plain), plain);
            }
            assertFalse(TokenSecretsManagerResolver.isToken(null));
        }

        @Test
        @DisplayName("Short or missing tokens are masked safely")
        void maskEdgeCases() {
            assertEquals("null", TokenSecretsManagerResolver.mask(null));
            assertEquals("RG{}", TokenSecretsManagerResolver.mask("RG{}"));
            assertEquals("RG{abc...", TokenSecretsManagerResolver.mask("RG{abcdef}"));
        }
    }
}
