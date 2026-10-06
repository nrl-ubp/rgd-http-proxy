package com.ubp.rgd.proxy.flightsql;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.ubp.rgd.proxy.services.FlightSqlDetokenizeService;
import com.ubp.rgd.proxy.services.FlightSqlTokenizeService;
import com.ubp.rgd.proxy.transform.RPSTransformException;
import com.ubp.rgd.proxy.transform.config.FlightSqlColumnMapping;
import org.apache.arrow.adapter.jdbc.JdbcParameterBinder;
import org.apache.arrow.adapter.jdbc.JdbcToArrow;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfig;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfigBuilder;
import org.apache.arrow.adapter.jdbc.JdbcToArrowUtils;
import org.apache.arrow.adapter.jdbc.ArrowVectorIterator;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.sql.BasicFlightSqlProducer;
import org.apache.arrow.flight.sql.SqlInfoBuilder;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.WriteChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flight SQL producer proxying an entire JDBC datasource. Every statement is executed on the proxied
 * database under the caller's own credentials, and the resulting Arrow record batches are scanned for
 * {@code RG{...}} tokens which are detokenized through the RPS engine before being returned.
 */
public class ProxyFlightSqlProducer extends BasicFlightSqlProducer {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyFlightSqlProducer.class);

    private final BufferAllocator allocator;
    private final FlightSqlConnectionManager connectionManager;
    private final FlightSqlDetokenizeService detokenizeService;
    private final FlightSqlTokenizeService tokenizeService;
    private final int batchSize;
    private final SqlInfoBuilder sqlInfoBuilder = new SqlInfoBuilder();

    /** Statements prepared by {@code getFlightInfo}, awaiting their {@code getStream} call. */
    private final Map<String, StatementHandle> statements = new ConcurrentHashMap<>();

    public ProxyFlightSqlProducer(BufferAllocator allocator,
                                  FlightSqlConnectionManager connectionManager,
                                  FlightSqlDetokenizeService detokenizeService,
                                  FlightSqlTokenizeService tokenizeService,
                                  int batchSize) {
        this.allocator = allocator;
        this.connectionManager = connectionManager;
        this.detokenizeService = detokenizeService;
        this.tokenizeService = tokenizeService;
        this.batchSize = batchSize > 0 ? batchSize : 1024;
        this.sqlInfoBuilder
                .withFlightSqlServerName("UBP RGD HTTP Proxy Flight SQL")
                .withFlightSqlServerVersion("1.0")
                .withFlightSqlServerArrowVersion("19.0.0")
                .withFlightSqlServerReadOnly(false)
                .withSqlIdentifierQuoteChar("\"")
                .withSqlDdlCatalog(false)
                .withSqlDdlSchema(false)
                .withSqlDdlTable(false)
                .withSqlSearchStringEscape("\\");
    }

    // ---------------------------------------------------------------------------------------------
    // Endpoints
    // ---------------------------------------------------------------------------------------------

    @Override
    protected <T extends Message> List<FlightEndpoint> determineEndpoints(T request,
                                                                         FlightDescriptor descriptor,
                                                                         Schema schema) {
        return List.of(new FlightEndpoint(new Ticket(Any.pack(request).toByteArray())));
    }

    // ---------------------------------------------------------------------------------------------
    // Ad-hoc statements
    // ---------------------------------------------------------------------------------------------

    @Override
    public FlightInfo getFlightInfoStatement(FlightSql.CommandStatementQuery command, CallContext context,
                                             FlightDescriptor descriptor) {
        String handle = UUID.randomUUID().toString();
        String query = rewrite(command.getQuery());
        Schema schema = describeQuery(context.peerIdentity(), query);
        statements.put(handle, new StatementHandle(context.peerIdentity(), query));

        FlightSql.TicketStatementQuery ticket = FlightSql.TicketStatementQuery.newBuilder()
                .setStatementHandle(ByteString.copyFromUtf8(handle))
                .build();
        return new FlightInfo(schema, descriptor,
                List.of(new FlightEndpoint(new Ticket(Any.pack(ticket).toByteArray()))), -1, -1);
    }

    @Override
    public SchemaResult getSchemaStatement(FlightSql.CommandStatementQuery command, CallContext context,
                                           FlightDescriptor descriptor) {
        return new SchemaResult(describeQuery(context.peerIdentity(), rewrite(command.getQuery())));
    }

    @Override
    public void getStreamStatement(FlightSql.TicketStatementQuery ticket, CallContext context,
                                   ServerStreamListener listener) {
        String handle = ticket.getStatementHandle().toStringUtf8();
        StatementHandle statement = statements.remove(handle);
        if (statement == null) {
            listener.error(CallStatus.NOT_FOUND
                    .withDescription("Unknown statement handle: " + handle).toRuntimeException());
            return;
        }
        executeQuery(statement.peerIdentity(), statement.query(), listener);
    }

    @Override
    public Runnable acceptPutStatement(FlightSql.CommandStatementUpdate command, CallContext context,
                                       FlightStream flightStream, StreamListener<PutResult> ackStream) {
        String peerIdentity = context.peerIdentity();
        return () -> {
            try (Connection connection = connectionManager.openConnection(peerIdentity);
                 Statement statement = connection.createStatement()) {
                long updated = statement.executeLargeUpdate(rewrite(command.getQuery()));
                sendUpdateResult(ackStream, updated);
            } catch (SQLException e) {
                ackStream.onError(CallStatus.INTERNAL
                        .withDescription(e.getMessage()).withCause(e).toRuntimeException());
            } catch (FlightRuntimeException e) {
                // A rejected transform() call: report it on the stream rather than letting it escape
                // the Runnable, where the client would only see a broken connection.
                ackStream.onError(e);
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Prepared statements
    // ---------------------------------------------------------------------------------------------

    @Override
    public void createPreparedStatement(FlightSql.ActionCreatePreparedStatementRequest request,
                                        CallContext context, StreamListener<Result> listener) {
        try {
            String handle = UUID.randomUUID().toString();
            String query = rewrite(request.getQuery());
            Schema datasetSchema = describeQuery(context.peerIdentity(), query);
            Schema parameterSchema = describeParameters(context.peerIdentity(), query);
            statements.put(handle, new StatementHandle(context.peerIdentity(), query));

            FlightSql.ActionCreatePreparedStatementResult result =
                    FlightSql.ActionCreatePreparedStatementResult.newBuilder()
                            .setPreparedStatementHandle(ByteString.copyFromUtf8(handle))
                            .setDatasetSchema(ByteString.copyFrom(serializeSchema(datasetSchema)))
                            .setParameterSchema(ByteString.copyFrom(serializeSchema(parameterSchema)))
                            .build();
            listener.onNext(new Result(Any.pack(result).toByteArray()));
            listener.onCompleted();
        } catch (RuntimeException e) {
            listener.onError(e);
        }
    }

    @Override
    public void closePreparedStatement(FlightSql.ActionClosePreparedStatementRequest request,
                                       CallContext context, StreamListener<Result> listener) {
        statements.remove(request.getPreparedStatementHandle().toStringUtf8());
        listener.onCompleted();
    }

    @Override
    public FlightInfo getFlightInfoPreparedStatement(FlightSql.CommandPreparedStatementQuery command,
                                                     CallContext context, FlightDescriptor descriptor) {
        StatementHandle statement = requireStatement(command.getPreparedStatementHandle().toStringUtf8());
        Schema schema = describeQuery(context.peerIdentity(), statement.query());
        return new FlightInfo(schema, descriptor,
                List.of(new FlightEndpoint(new Ticket(Any.pack(command).toByteArray()))), -1, -1);
    }

    @Override
    public SchemaResult getSchemaPreparedStatement(FlightSql.CommandPreparedStatementQuery command,
                                                   CallContext context, FlightDescriptor descriptor) {
        StatementHandle statement = requireStatement(command.getPreparedStatementHandle().toStringUtf8());
        return new SchemaResult(describeQuery(context.peerIdentity(), statement.query()));
    }

    @Override
    public void getStreamPreparedStatement(FlightSql.CommandPreparedStatementQuery command,
                                           CallContext context, ServerStreamListener listener) {
        try {
            StatementHandle statement =
                    requireStatement(command.getPreparedStatementHandle().toStringUtf8());
            executeQuery(statement.peerIdentity(), statement.query(), statement.parameters(), listener);
        } catch (RuntimeException e) {
            listener.error(e);
        }
    }

    @Override
    public Runnable acceptPutPreparedStatementUpdate(FlightSql.CommandPreparedStatementUpdate command,
                                                     CallContext context, FlightStream flightStream,
                                                     StreamListener<PutResult> ackStream) {
        String handle = command.getPreparedStatementHandle().toStringUtf8();
        return () -> {
            try {
                StatementHandle statement = requireStatement(handle);
                try (Connection connection = connectionManager.openConnection(statement.peerIdentity());
                     PreparedStatement preparedStatement = connection.prepareStatement(statement.query())) {

                    long updated = 0;
                    boolean bound = false;
                    // The client sends its parameters as Arrow batches: one row per execution, which
                    // is what turns a multi-row insert into a single round trip.
                    while (flightStream.next()) {
                        VectorSchemaRoot root = flightStream.getRoot();
                        if (root.getFieldVectors().isEmpty() || root.getRowCount() == 0) {
                            continue;
                        }
                        bound = true;
                        JdbcParameterBinder binder = JdbcParameterBinder
                                .builder(preparedStatement, root).bindAll().build();
                        while (binder.next()) {
                            preparedStatement.addBatch();
                        }
                        for (long count : preparedStatement.executeLargeBatch()) {
                            // A driver may answer SUCCESS_NO_INFO (-2) rather than a row count.
                            updated += count > 0 ? count : 0;
                        }
                    }
                    if (!bound) {
                        // No parameters at all: the statement is executed as-is, as it always was.
                        updated = preparedStatement.executeLargeUpdate();
                    }
                    sendUpdateResult(ackStream, updated);
                }
            } catch (SQLException e) {
                ackStream.onError(CallStatus.INTERNAL
                        .withDescription(e.getMessage()).withCause(e).toRuntimeException());
            } catch (RuntimeException e) {
                ackStream.onError(e);
            }
        };
    }

    @Override
    public Runnable acceptPutPreparedStatementQuery(FlightSql.CommandPreparedStatementQuery command,
                                                    CallContext context, FlightStream flightStream,
                                                    StreamListener<PutResult> ackStream) {
        String handle = command.getPreparedStatementHandle().toStringUtf8();
        return () -> {
            try {
                StatementHandle statement = requireStatement(handle);
                List<Object[]> parameters = new ArrayList<>();
                while (flightStream.next()) {
                    VectorSchemaRoot root = flightStream.getRoot();
                    if (!root.getFieldVectors().isEmpty() && root.getRowCount() > 0) {
                        parameters.addAll(readParameters(root));
                    }
                }
                // Kept for the getStream call that executes this statement right after.
                statement.setParameters(parameters);
                // The client replaces its handle with the one carried by this result, so it must be
                // echoed back: an empty PutResult would leave the client with an empty handle.
                sendPreparedStatementResult(ackStream, handle);
            } catch (RuntimeException e) {
                ackStream.onError(e);
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------------------------------

    @Override
    public void getStreamSqlInfo(FlightSql.CommandGetSqlInfo command, CallContext context,
                                 ServerStreamListener listener) {
        sqlInfoBuilder.send(command.getInfoList(), listener);
    }

    @Override
    public void getStreamCatalogs(CallContext context, ServerStreamListener listener) {
        streamJdbcMetaData(context, listener, Schemas.GET_CATALOGS_SCHEMA, metaData -> {
            List<List<String>> rows = new ArrayList<>();
            try (ResultSet resultSet = metaData.getCatalogs()) {
                while (resultSet.next()) {
                    rows.add(List.of(nullSafe(resultSet.getString("TABLE_CAT"))));
                }
            }
            return rows;
        });
    }

    @Override
    public void getStreamSchemas(FlightSql.CommandGetDbSchemas command, CallContext context,
                                 ServerStreamListener listener) {
        streamJdbcMetaData(context, listener, Schemas.GET_SCHEMAS_SCHEMA, metaData -> {
            List<List<String>> rows = new ArrayList<>();
            try (ResultSet resultSet = metaData.getSchemas(
                    command.hasCatalog() ? command.getCatalog() : null,
                    command.hasDbSchemaFilterPattern() ? command.getDbSchemaFilterPattern() : null)) {
                while (resultSet.next()) {
                    rows.add(asRow(resultSet.getString("TABLE_CATALOG"), resultSet.getString("TABLE_SCHEM")));
                }
            }
            return rows;
        });
    }

    @Override
    public void getStreamTableTypes(CallContext context, ServerStreamListener listener) {
        streamJdbcMetaData(context, listener, Schemas.GET_TABLE_TYPES_SCHEMA, metaData -> {
            List<List<String>> rows = new ArrayList<>();
            try (ResultSet resultSet = metaData.getTableTypes()) {
                while (resultSet.next()) {
                    rows.add(List.of(nullSafe(resultSet.getString("TABLE_TYPE"))));
                }
            }
            return rows;
        });
    }

    @Override
    public void getStreamTables(FlightSql.CommandGetTables command, CallContext context,
                                ServerStreamListener listener) {
        String peerIdentity = context.peerIdentity();
        try (Connection connection = connectionManager.openConnection(peerIdentity)) {
            String[] types = command.getTableTypesList().isEmpty()
                    ? null
                    : command.getTableTypesList().toArray(new String[0]);
            List<List<String>> rows = new ArrayList<>();
            try (ResultSet resultSet = connection.getMetaData().getTables(
                    command.hasCatalog() ? command.getCatalog() : null,
                    command.hasDbSchemaFilterPattern() ? command.getDbSchemaFilterPattern() : null,
                    command.hasTableNameFilterPattern() ? command.getTableNameFilterPattern() : null,
                    types)) {
                while (resultSet.next()) {
                    rows.add(asRow(resultSet.getString("TABLE_CAT"),
                            resultSet.getString("TABLE_SCHEM"),
                            resultSet.getString("TABLE_NAME"),
                            resultSet.getString("TABLE_TYPE")));
                }
            }

            if (!command.getIncludeSchema()) {
                streamStringRows(listener, Schemas.GET_TABLES_SCHEMA_NO_SCHEMA, rows);
                return;
            }
            streamTablesWithSchema(connection, listener, rows);
        } catch (SQLException | IOException e) {
            listener.error(CallStatus.INTERNAL
                    .withDescription(e.getMessage()).withCause(e).toRuntimeException());
        }
    }

    /**
     * The data types of the database, which back the ODBC {@code SQLGetTypeInfo} call.
     * <p>
     * JDBC and ODBC type codes coincide for the classic types, but JDBC also has codes ODBC does not
     * know ({@code NCHAR}, {@code BOOLEAN}, the LOBs, the time zone types...). Those are translated,
     * see {@link XdbcTypes}, and a type that has no ODBC equivalent at all is left out: an ODBC
     * application could do nothing with it. The rows are sorted by data type, as ODBC requires.
     */
    @Override
    public void getStreamTypeInfo(FlightSql.CommandGetXdbcTypeInfo command, CallContext context,
                                  ServerStreamListener listener) {
        streamTypedMetaData(context, listener, Schemas.GET_TYPE_INFO_SCHEMA, metaData -> {
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet resultSet = metaData.getTypeInfo()) {
                while (resultSet.next()) {
                    XdbcTypes.typeInfoRow(resultSet).ifPresent(rows::add);
                }
            }
            if (command.hasDataType()) {
                rows.removeIf(row -> (Integer) row[XdbcTypes.DATA_TYPE_COLUMN] != command.getDataType());
            }
            // Stable: JDBC lists the types of one code from the closest match to the farthest.
            rows.sort(Comparator.comparingInt(row -> (Integer) row[XdbcTypes.DATA_TYPE_COLUMN]));
            return rows;
        });
    }

    @Override
    public void getStreamPrimaryKeys(FlightSql.CommandGetPrimaryKeys command, CallContext context,
                                     ServerStreamListener listener) {
        streamTypedMetaData(context, listener, Schemas.GET_PRIMARY_KEYS_SCHEMA, metaData -> {
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet resultSet = metaData.getPrimaryKeys(
                    command.hasCatalog() ? command.getCatalog() : null,
                    command.hasDbSchema() ? command.getDbSchema() : null,
                    command.getTable())) {
                while (resultSet.next()) {
                    rows.add(new Object[]{
                            resultSet.getString("TABLE_CAT"),
                            resultSet.getString("TABLE_SCHEM"),
                            resultSet.getString("TABLE_NAME"),
                            resultSet.getString("COLUMN_NAME"),
                            resultSet.getInt("KEY_SEQ"),
                            resultSet.getString("PK_NAME")});
                }
            }
            // JDBC orders by column name, Flight SQL clients expect the key order.
            rows.sort(Comparator.comparingInt(row -> (Integer) row[4]));
            return rows;
        });
    }

    @Override
    public void getStreamImportedKeys(FlightSql.CommandGetImportedKeys command, CallContext context,
                                      ServerStreamListener listener) {
        streamTypedMetaData(context, listener, Schemas.GET_IMPORTED_KEYS_SCHEMA, metaData ->
                keyRows(metaData.getImportedKeys(
                        command.hasCatalog() ? command.getCatalog() : null,
                        command.hasDbSchema() ? command.getDbSchema() : null,
                        command.getTable())));
    }

    @Override
    public void getStreamExportedKeys(FlightSql.CommandGetExportedKeys command, CallContext context,
                                      ServerStreamListener listener) {
        streamTypedMetaData(context, listener, Schemas.GET_EXPORTED_KEYS_SCHEMA, metaData ->
                keyRows(metaData.getExportedKeys(
                        command.hasCatalog() ? command.getCatalog() : null,
                        command.hasDbSchema() ? command.getDbSchema() : null,
                        command.getTable())));
    }

    @Override
    public void getStreamCrossReference(FlightSql.CommandGetCrossReference command, CallContext context,
                                        ServerStreamListener listener) {
        streamTypedMetaData(context, listener, Schemas.GET_CROSS_REFERENCE_SCHEMA, metaData ->
                keyRows(metaData.getCrossReference(
                        command.hasPkCatalog() ? command.getPkCatalog() : null,
                        command.hasPkDbSchema() ? command.getPkDbSchema() : null,
                        command.getPkTable(),
                        command.hasFkCatalog() ? command.getFkCatalog() : null,
                        command.hasFkDbSchema() ? command.getFkDbSchema() : null,
                        command.getFkTable())));
    }

    /**
     * Read a JDBC foreign key description into the Flight SQL layout. The JDBC update and delete
     * rule codes are, by design, the Flight SQL {@code UpdateDeleteRules} values.
     */
    private static List<Object[]> keyRows(ResultSet resultSet) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (resultSet) {
            while (resultSet.next()) {
                rows.add(new Object[]{
                        resultSet.getString("PKTABLE_CAT"),
                        resultSet.getString("PKTABLE_SCHEM"),
                        resultSet.getString("PKTABLE_NAME"),
                        resultSet.getString("PKCOLUMN_NAME"),
                        resultSet.getString("FKTABLE_CAT"),
                        resultSet.getString("FKTABLE_SCHEM"),
                        resultSet.getString("FKTABLE_NAME"),
                        resultSet.getString("FKCOLUMN_NAME"),
                        resultSet.getInt("KEY_SEQ"),
                        resultSet.getString("FK_NAME"),
                        resultSet.getString("PK_NAME"),
                        resultSet.getInt("UPDATE_RULE"),
                        resultSet.getInt("DELETE_RULE")});
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------------------------------------
    // Execution
    // ---------------------------------------------------------------------------------------------

    /**
     * Apply the {@code transform()} SQL extension to a statement arriving from a client, before
     * anything else looks at it.
     * <p>
     * Called once per entry point receiving a raw query, so the token of a statement is computed once
     * rather than once per use: the schema description, the parameter description and the execution
     * all work on the rewritten text held by the {@link StatementHandle}.
     *
     * @param query the statement as the client wrote it
     * @return the statement to hand to the database server
     */
    private String rewrite(String query) {
        try {
            return tokenizeService.rewrite(query);
        } catch (FlightSqlTokenizeService.FlightSqlTransformSyntaxException e) {
            // The client wrote something the proxy cannot make sense of: say so, rather than letting
            // the database complain about a function it has never heard of.
            LOG.warn("Rejecting a query using the transform() extension: {}", e.getMessage());
            throw CallStatus.INVALID_ARGUMENT
                    .withDescription(e.getMessage()).withCause(e).toRuntimeException();
        } catch (RPSTransformException e) {
            LOG.error("Failed to apply the transform() extension to: {}", query, e);
            throw CallStatus.INTERNAL
                    .withDescription("Cannot transform the values of the query: " + e.getMessage())
                    .withCause(e).toRuntimeException();
        }
    }

    /**
     * Execute the query on the proxied database and stream the detokenized Arrow batches back.
     */
    private void executeQuery(String peerIdentity, String query, ServerStreamListener listener) {
        executeQuery(peerIdentity, query, List.of(), listener);
    }

    /**
     * Execute the query on the proxied database, binding the given parameter row when the statement
     * was prepared with placeholders, and stream the detokenized Arrow batches back.
     */
    private void executeQuery(String peerIdentity, String query, List<Object[]> parameters,
                              ServerStreamListener listener) {
        LOG.debug("Flight SQL executing for {}: {}", peerIdentity, query);
        try (Connection connection = connectionManager.openConnection(peerIdentity);
             PreparedStatement statement = prepare(connection, query, parameters);
             ResultSet resultSet = statement.executeQuery()) {

            ResultSetMetaData metaData = resultSet.getMetaData();
            List<FlightSqlColumnMapping> mappings = detokenizeService.resolveMappings(metaData);

            JdbcToArrowConfig config = new JdbcToArrowConfigBuilder()
                    .setAllocator(allocator)
                    .setCalendar(JdbcToArrowUtils.getUtcCalendar())
                    .setTargetBatchSize(batchSize)
                    .setReuseVectorSchemaRoot(true)
                    .build();

            try (ArrowVectorIterator iterator = JdbcToArrow.sqlToArrowVectorIterator(resultSet, config)) {
                boolean started = false;
                while (iterator.hasNext()) {
                    VectorSchemaRoot root = iterator.next();
                    detokenizeService.detokenize(root, mappings, allocator);
                    if (!started) {
                        listener.start(root);
                        started = true;
                    }
                    listener.putNext();
                }
                if (!started) {
                    // Empty result set: still publish the schema so that the client sees the columns.
                    try (VectorSchemaRoot empty = VectorSchemaRoot.create(
                            JdbcToArrowUtils.jdbcToArrowSchema(metaData, config), allocator)) {
                        empty.setRowCount(0);
                        listener.start(empty);
                        listener.putNext();
                    }
                }
            }
            listener.completed();
        } catch (Exception e) {
            LOG.error("Flight SQL query failed: {}", query, e);
            listener.error(CallStatus.INTERNAL
                    .withDescription(e.getMessage()).withCause(e).toRuntimeException());
        }
    }

    /**
     * Prepare the query without executing it to derive the Arrow schema of its result set.
     */
    private Schema describeQuery(String peerIdentity, String query) {
        try (Connection connection = connectionManager.openConnection(peerIdentity);
             PreparedStatement statement = connection.prepareStatement(query)) {
            ResultSetMetaData metaData = statement.getMetaData();
            if (metaData == null) {
                return new Schema(List.of());
            }
            JdbcToArrowConfig config = new JdbcToArrowConfigBuilder()
                    .setAllocator(allocator)
                    .setCalendar(JdbcToArrowUtils.getUtcCalendar())
                    .build();
            return JdbcToArrowUtils.jdbcToArrowSchema(metaData, config);
        } catch (SQLException e) {
            throw CallStatus.INVALID_ARGUMENT
                    .withDescription("Cannot prepare the query: " + e.getMessage())
                    .withCause(e).toRuntimeException();
        }
    }

    /**
     * Prepare the query without executing it to derive the Arrow schema of its parameters.
     *
     * <p>The Flight SQL clients rely on this schema to know how many parameters they may bind: when
     * it is missing they assume the statement takes none and reject every {@code setXxx} call. A
     * JDBC driver that cannot describe its parameters yields an empty schema, which leaves the
     * parameter-less statements working exactly as they did before.</p>
     */
    private Schema describeParameters(String peerIdentity, String query) {
        try (Connection connection = connectionManager.openConnection(peerIdentity);
             PreparedStatement statement = connection.prepareStatement(query)) {
            ParameterMetaData metaData = statement.getParameterMetaData();
            if (metaData == null || metaData.getParameterCount() == 0) {
                return new Schema(List.of());
            }
            return JdbcToArrowUtils.jdbcToArrowSchema(metaData, JdbcToArrowUtils.getUtcCalendar());
        } catch (SQLException | RuntimeException e) {
            LOG.debug("Cannot describe the parameters of the query, assuming it takes none: {}",
                    query, e);
            return new Schema(List.of());
        }
    }

    /**
     * Copy the parameter rows of an Arrow batch out into plain Java objects, so that they outlive
     * the {@link FlightStream} they were read from.
     */
    private static List<Object[]> readParameters(VectorSchemaRoot root) {
        List<Object[]> rows = new ArrayList<>();
        int columns = root.getFieldVectors().size();
        for (int rowIndex = 0; rowIndex < root.getRowCount(); rowIndex++) {
            Object[] row = new Object[columns];
            for (int column = 0; column < columns; column++) {
                Object value = root.getVector(column).getObject(rowIndex);
                // Arrow hands text back as Text, which no JDBC driver knows how to bind.
                row[column] = value instanceof Text text ? text.toString() : value;
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Prepare the query and bind the first parameter row on it, if any. A query yields a single
     * result set, so only the first row of the parameter batch can be honoured.
     */
    private static PreparedStatement prepare(Connection connection, String query,
                                             List<Object[]> parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(query);
        try {
            if (!parameters.isEmpty()) {
                Object[] row = parameters.getFirst();
                for (int i = 0; i < row.length; i++) {
                    statement.setObject(i + 1, row[i]);
                }
            }
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
        return statement;
    }

    private StatementHandle requireStatement(String handle) {
        StatementHandle statement = statements.get(handle);
        if (statement == null) {
            throw CallStatus.NOT_FOUND
                    .withDescription("Unknown prepared statement handle: " + handle).toRuntimeException();
        }
        return statement;
    }

    @Override
    public void close() {
        statements.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void streamJdbcMetaData(CallContext context, ServerStreamListener listener, Schema schema,
                                    MetaDataReader reader) {
        try (Connection connection = connectionManager.openConnection(context.peerIdentity())) {
            streamStringRows(listener, schema, reader.read(connection.getMetaData()));
        } catch (SQLException e) {
            listener.error(CallStatus.INTERNAL
                    .withDescription(e.getMessage()).withCause(e).toRuntimeException());
        }
    }

    /** The counterpart of {@link #streamJdbcMetaData} for schemas whose columns are not all text. */
    private void streamTypedMetaData(CallContext context, ServerStreamListener listener, Schema schema,
                                     TypedMetaDataReader reader) {
        try (Connection connection = connectionManager.openConnection(context.peerIdentity())) {
            MetadataRowWriter.stream(allocator, listener, schema, reader.read(connection.getMetaData()));
        } catch (SQLException e) {
            listener.error(CallStatus.INTERNAL
                    .withDescription(e.getMessage()).withCause(e).toRuntimeException());
        }
    }

    /**
     * Stream rows made only of (nullable) UTF-8 values, following the given Flight SQL schema.
     */
    private void streamStringRows(ServerStreamListener listener, Schema schema, List<List<String>> rows) {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
            root.allocateNew();
            for (int column = 0; column < schema.getFields().size(); column++) {
                VarCharVector vector = (VarCharVector) root.getVector(column);
                for (int row = 0; row < rows.size(); row++) {
                    String value = rows.get(row).get(column);
                    if (value == null) {
                        vector.setNull(row);
                    } else {
                        vector.setSafe(row, value.getBytes(StandardCharsets.UTF_8));
                    }
                }
                vector.setValueCount(rows.size());
            }
            root.setRowCount(rows.size());
            listener.start(root);
            listener.putNext();
            listener.completed();
        }
    }

    /**
     * Stream the tables together with the serialized Arrow schema of each of them.
     */
    private void streamTablesWithSchema(Connection connection, ServerStreamListener listener,
                                        List<List<String>> rows) throws IOException {
        Schema schema = Schemas.GET_TABLES_SCHEMA;
        try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
            root.allocateNew();
            for (int column = 0; column < 4; column++) {
                VarCharVector vector = (VarCharVector) root.getVector(column);
                for (int row = 0; row < rows.size(); row++) {
                    String value = rows.get(row).get(column);
                    if (value == null) {
                        vector.setNull(row);
                    } else {
                        vector.setSafe(row, value.getBytes(StandardCharsets.UTF_8));
                    }
                }
                vector.setValueCount(rows.size());
            }

            VarBinaryVector schemaVector = (VarBinaryVector) root.getVector(4);
            for (int row = 0; row < rows.size(); row++) {
                schemaVector.setSafe(row, serializeSchema(tableSchema(connection, rows.get(row))));
            }
            schemaVector.setValueCount(rows.size());

            root.setRowCount(rows.size());
            listener.start(root);
            listener.putNext();
            listener.completed();
        }
    }

    /**
     * Derive the Arrow schema of a table by preparing (without executing) a {@code SELECT *} on it.
     */
    private Schema tableSchema(Connection connection, List<String> tableRow) {
        StringBuilder qualifiedName = new StringBuilder();
        if (tableRow.get(1) != null && !tableRow.get(1).isBlank()) {
            qualifiedName.append('"').append(tableRow.get(1)).append("\".");
        }
        qualifiedName.append('"').append(tableRow.get(2)).append('"');
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT * FROM " + qualifiedName + " WHERE 1 = 0")) {
            ResultSetMetaData metaData = statement.getMetaData();
            if (metaData == null) {
                return new Schema(List.of());
            }
            return JdbcToArrowUtils.jdbcToArrowSchema(metaData, new JdbcToArrowConfigBuilder()
                    .setAllocator(allocator)
                    .setCalendar(JdbcToArrowUtils.getUtcCalendar())
                    .build());
        } catch (SQLException e) {
            LOG.debug("Cannot describe table {}: {}", qualifiedName, e.getMessage());
            return new Schema(List.of());
        }
    }

    private static byte[] serializeSchema(Schema schema) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MessageSerializer.serialize(new WriteChannel(Channels.newChannel(out)), schema);
            return out.toByteArray();
        } catch (IOException e) {
            throw CallStatus.INTERNAL
                    .withDescription("Cannot serialize the Arrow schema.").withCause(e).toRuntimeException();
        }
    }

    /**
     * Acknowledge an update statement with the number of affected records.
     */
    private void sendUpdateResult(StreamListener<PutResult> ackStream, long updated) {
        FlightSql.DoPutUpdateResult result = FlightSql.DoPutUpdateResult.newBuilder()
                .setRecordCount(updated)
                .build();
        try (ArrowBuf buffer = allocator.buffer(result.getSerializedSize())) {
            buffer.writeBytes(result.toByteArray());
            ackStream.onNext(PutResult.metadata(buffer));
            ackStream.onCompleted();
        }
    }
    /**
     * Acknowledge a prepared statement {@code doPut} by echoing its handle back. The Flight SQL
     * clients overwrite their own handle with the one found in this result before calling
     * {@code getFlightInfo}, so omitting it leaves them with an empty handle.
     */
    private void sendPreparedStatementResult(StreamListener<PutResult> ackStream, String handle) {
        FlightSql.DoPutPreparedStatementResult result = FlightSql.DoPutPreparedStatementResult
                .newBuilder()
                .setPreparedStatementHandle(ByteString.copyFromUtf8(handle))
                .build();
        try (ArrowBuf buffer = allocator.buffer(result.getSerializedSize())) {
            buffer.writeBytes(result.toByteArray());
            ackStream.onNext(PutResult.metadata(buffer));
            ackStream.onCompleted();
        }
    }

    private static List<String> asRow(String... values) {
        List<String> row = new ArrayList<>(values.length);
        for (String value : values) {
            row.add(value);
        }
        return row;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * A statement prepared by a {@code getFlightInfo} / {@code createPreparedStatement} call.
     *
     * <p>Prepared queries receive their parameters through a separate {@code doPut} call, before the
     * {@code getStream} call that actually executes them. The bound parameters are therefore kept
     * here, as plain Java objects rather than Arrow buffers, so that their lifetime is not tied to
     * the allocator of the {@link FlightStream} they came from.</p>
     */
    private static final class StatementHandle {

        private final String peerIdentity;
        private final String query;
        private volatile List<Object[]> parameters = List.of();

        private StatementHandle(String peerIdentity, String query) {
            this.peerIdentity = peerIdentity;
            this.query = query;
        }

        private String peerIdentity() {
            return peerIdentity;
        }

        private String query() {
            return query;
        }

        private List<Object[]> parameters() {
            return parameters;
        }

        private void setParameters(List<Object[]> parameters) {
            this.parameters = parameters == null ? List.of() : parameters;
        }
    }

    /** Reads rows of UTF-8 values out of the JDBC database metadata. */
    @FunctionalInterface
    private interface MetaDataReader {
        List<List<String>> read(DatabaseMetaData metaData) throws SQLException;
    }

    @FunctionalInterface
    private interface TypedMetaDataReader {
        List<Object[]> read(DatabaseMetaData metaData) throws SQLException;
    }
}
