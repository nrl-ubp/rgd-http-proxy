package com.ubp.rgd.proxy.flightsql;

import org.apache.arrow.flight.sql.impl.FlightSql.XdbcDataType;
import org.apache.arrow.flight.sql.impl.FlightSql.XdbcDatetimeSubcode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Translates the JDBC type information into the Flight SQL {@code GetXdbcTypeInfo} layout, which
 * follows ODBC.
 * <p>
 * JDBC borrowed the ODBC codes for the classic types, so most of them pass through unchanged. JDBC
 * added its own afterwards, and those need care:
 * <ul>
 *     <li>{@code NCHAR}, {@code LONGNVARCHAR}, {@code NCLOB}, {@code SQLXML}, {@code BOOLEAN},
 *     {@code CLOB}, {@code BLOB} and the time zone types are translated to their closest ODBC
 *     type;</li>
 *     <li>{@code ROWID} is <b>-8</b> in JDBC, which is {@code WCHAR} in ODBC: passing codes through
 *     blindly would advertise it as a Unicode text type. Hence an explicit allow-list;</li>
 *     <li>a type with no ODBC equivalent ({@code ARRAY}, {@code OTHER}, vendor codes...) is left
 *     out.</li>
 * </ul>
 * The ODBC {@code SQL_DATA_TYPE} / {@code SQL_DATETIME_SUB} pair is computed from the translated code,
 * since JDBC drivers rarely fill those columns meaningfully.
 */
final class XdbcTypes {

    private static final Logger LOG = LoggerFactory.getLogger(XdbcTypes.class);

    /** The index of {@code data_type} in a type info row. */
    static final int DATA_TYPE_COLUMN = 1;

    private XdbcTypes() {
    }

    /**
     * The XDBC data type of a JDBC type code.
     *
     * @return the code, or empty when the type has no ODBC equivalent
     */
    static Optional<Integer> toXdbcType(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR, Types.NUMERIC, Types.DECIMAL, Types.INTEGER, Types.SMALLINT, Types.FLOAT,
                 Types.REAL, Types.DOUBLE, Types.VARCHAR, Types.DATE, Types.TIME, Types.TIMESTAMP,
                 Types.LONGVARCHAR, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BIGINT,
                 Types.TINYINT, Types.BIT -> Optional.of(jdbcType);
            case Types.NVARCHAR, Types.LONGNVARCHAR, Types.NCLOB, Types.SQLXML ->
                    Optional.of(XdbcDataType.XDBC_WVARCHAR_VALUE);
            case Types.NCHAR -> Optional.of(XdbcDataType.XDBC_WCHAR_VALUE);
            case Types.BOOLEAN -> Optional.of(XdbcDataType.XDBC_BIT_VALUE);
            case Types.CLOB -> Optional.of(XdbcDataType.XDBC_LONGVARCHAR_VALUE);
            case Types.BLOB -> Optional.of(XdbcDataType.XDBC_LONGVARBINARY_VALUE);
            case Types.TIME_WITH_TIMEZONE -> Optional.of(XdbcDataType.XDBC_TIME_VALUE);
            case Types.TIMESTAMP_WITH_TIMEZONE -> Optional.of(XdbcDataType.XDBC_TIMESTAMP_VALUE);
            default -> Optional.empty();
        };
    }

    /**
     * The ODBC datetime subcode of a JDBC type, null for a non datetime type. Taken from the JDBC
     * code rather than the translated one, which has lost the time zone.
     */
    static Integer datetimeSubcode(int jdbcType) {
        return switch (jdbcType) {
            case Types.DATE -> XdbcDatetimeSubcode.XDBC_SUBCODE_DATE_VALUE;
            case Types.TIME -> XdbcDatetimeSubcode.XDBC_SUBCODE_TIME_VALUE;
            case Types.TIMESTAMP -> XdbcDatetimeSubcode.XDBC_SUBCODE_TIMESTAMP_VALUE;
            case Types.TIME_WITH_TIMEZONE -> XdbcDatetimeSubcode.XDBC_SUBCODE_TIME_WITH_TIMEZONE_VALUE;
            case Types.TIMESTAMP_WITH_TIMEZONE ->
                    XdbcDatetimeSubcode.XDBC_SUBCODE_TIMESTAMP_WITH_TIMEZONE_VALUE;
            default -> null;
        };
    }

    /**
     * Read the current row of {@link DatabaseMetaData#getTypeInfo()} into a
     * {@code GET_TYPE_INFO_SCHEMA} row.
     *
     * @return the row, or empty when the type has no ODBC equivalent
     */
    static Optional<Object[]> typeInfoRow(ResultSet resultSet) throws SQLException {
        String typeName = resultSet.getString("TYPE_NAME");
        int jdbcType = resultSet.getInt("DATA_TYPE");
        Optional<Integer> xdbcType = toXdbcType(jdbcType);
        if (xdbcType.isEmpty()) {
            LOG.debug("Type {} (JDBC code {}) has no ODBC equivalent: not listed", typeName, jdbcType);
            return Optional.empty();
        }

        Integer subcode = datetimeSubcode(jdbcType);
        // ODBC's "verbose" type: the generic SQL_DATETIME for every datetime type, the type itself
        // otherwise.
        int sqlDataType = subcode != null ? XdbcDataType.XDBC_DATETIME_VALUE : xdbcType.get();

        Integer nullable = nullableInt(resultSet, "NULLABLE");
        Integer searchable = nullableInt(resultSet, "SEARCHABLE");

        return Optional.of(new Object[]{
                typeName,
                xdbcType.get(),
                nullableInt(resultSet, "PRECISION"),
                resultSet.getString("LITERAL_PREFIX"),
                resultSet.getString("LITERAL_SUFFIX"),
                createParams(resultSet.getString("CREATE_PARAMS")),
                nullable == null ? DatabaseMetaData.typeNullableUnknown : nullable,
                resultSet.getBoolean("CASE_SENSITIVE"),
                searchable == null ? DatabaseMetaData.typeSearchable : searchable,
                nullableBoolean(resultSet, "UNSIGNED_ATTRIBUTE"),
                resultSet.getBoolean("FIXED_PREC_SCALE"),
                nullableBoolean(resultSet, "AUTO_INCREMENT"),
                resultSet.getString("LOCAL_TYPE_NAME"),
                nullableInt(resultSet, "MINIMUM_SCALE"),
                nullableInt(resultSet, "MAXIMUM_SCALE"),
                sqlDataType,
                subcode,
                nullableInt(resultSet, "NUM_PREC_RADIX"),
                null});
    }

    /** JDBC gives the creation parameters as one comma-separated text, Flight SQL as a list. */
    static List<String> createParams(String params) {
        if (params == null || params.isBlank()) {
            return null;
        }
        return Arrays.stream(params.split(","))
                .map(String::trim)
                .filter(param -> !param.isEmpty())
                .toList();
    }

    private static Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet resultSet, String column) throws SQLException {
        boolean value = resultSet.getBoolean(column);
        return resultSet.wasNull() ? null : value;
    }
}
