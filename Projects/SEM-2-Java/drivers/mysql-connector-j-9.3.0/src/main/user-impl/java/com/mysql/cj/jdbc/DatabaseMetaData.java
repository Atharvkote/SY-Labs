/*
 * Copyright (c) 2002, 2025, Oracle and/or its affiliates.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License, version 2.0, as published by
 * the Free Software Foundation.
 *
 * This program is designed to work with certain software that is licensed under separate terms, as designated in a particular file or component or in
 * included license documentation. The authors of MySQL hereby grant you an additional permission to link the program and your derivative works with the
 * separately licensed software that they have either included with the program or referenced in the documentation.
 *
 * Without limiting anything contained in the foregoing, this file, which is part of MySQL Connector/J, is also subject to the Universal FOSS Exception,
 * version 1.0, a copy of which can be found at http://oss.oracle.com/licenses/universal-foss-exception.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License, version 2.0, for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA
 */

package com.mysql.cj.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.mysql.cj.Constants;
import com.mysql.cj.Messages;
import com.mysql.cj.MysqlType;
import com.mysql.cj.NativeSession;
import com.mysql.cj.QueryInfo;
import com.mysql.cj.ServerVersion;
import com.mysql.cj.conf.PropertyDefinitions.DatabaseTerm;
import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.conf.RuntimeProperty;
import com.mysql.cj.exceptions.AssertionFailedException;
import com.mysql.cj.exceptions.CJException;
import com.mysql.cj.exceptions.ExceptionInterceptor;
import com.mysql.cj.exceptions.MysqlErrorNumbers;
import com.mysql.cj.jdbc.exceptions.SQLError;
import com.mysql.cj.jdbc.exceptions.SQLExceptionsMapping;
import com.mysql.cj.jdbc.result.ResultSetFactory;
import com.mysql.cj.protocol.ColumnDefinition;
import com.mysql.cj.protocol.a.result.ByteArrayRow;
import com.mysql.cj.protocol.a.result.ResultsetRowsStatic;
import com.mysql.cj.result.DefaultColumnDefinition;
import com.mysql.cj.result.Field;
import com.mysql.cj.result.Row;
import com.mysql.cj.telemetry.TelemetryAttribute;
import com.mysql.cj.telemetry.TelemetryScope;
import com.mysql.cj.telemetry.TelemetrySpan;
import com.mysql.cj.telemetry.TelemetrySpanName;
import com.mysql.cj.util.LRUCache;
import com.mysql.cj.util.StringUtils;

public abstract class DatabaseMetaData implements java.sql.DatabaseMetaData {

    @FunctionalInterface
    interface ThrowingSupplier<T, E extends Exception> {

        T get() throws E;

    }

    /**
     * Enumeration for Table Types.
     */
    enum TableType {

        LOCAL_TEMPORARY("LOCAL TEMPORARY"), //
        SYSTEM_TABLE("SYSTEM TABLE"), //
        SYSTEM_VIEW("SYSTEM VIEW"), //
        TABLE("TABLE", new String[] { "BASE TABLE" }), //
        VIEW("VIEW"), //
        UNKNOWN("UNKNOWN");

        private String name;
        private byte[] nameAsBytes;
        private String[] synonyms;

        TableType(String tableTypeName) {
            this(tableTypeName, null);
        }

        TableType(String tableTypeName, String[] tableTypeSynonyms) {
            this.name = tableTypeName;
            this.nameAsBytes = tableTypeName.getBytes();
            this.synonyms = tableTypeSynonyms;
        }

        String getName() {
            return this.name;
        }

        byte[] asBytes() {
            return this.nameAsBytes;
        }

        boolean equalsTo(String tableTypeName) {
            return this.name.equalsIgnoreCase(tableTypeName);
        }

        static TableType getTableTypeEqualTo(String tableTypeName) {
            for (TableType tableType : TableType.values()) {
                if (tableType.equalsTo(tableTypeName)) {
                    return tableType;
                }
            }
            return UNKNOWN;
        }

        boolean compliesWith(String tableTypeName) {
            if (equalsTo(tableTypeName)) {
                return true;
            }
            if (this.synonyms != null) {
                for (String synonym : this.synonyms) {
                    if (synonym.equalsIgnoreCase(tableTypeName)) {
                        return true;
                    }
                }
            }
            return false;
        }

        static TableType getTableTypeCompliantWith(String tableTypeName) {
            for (TableType tableType : TableType.values()) {
                if (tableType.compliesWith(tableTypeName)) {
                    return tableType;
                }
            }
            return UNKNOWN;
        }

    }

    // MySQL reserved words (all versions superset).
    private static final List<String> MYSQL_KEYWORDS = Arrays.asList("ACCESSIBLE", "ADD", "ALL", "ALTER", "ANALYZE", "AND", "AS", "ASC", "ASENSITIVE", "BEFORE",
            "BETWEEN", "BIGINT", "BINARY", "BLOB", "BOTH", "BY", "CALL", "CASCADE", "CASE", "CHANGE", "CHAR", "CHARACTER", "CHECK", "COLLATE", "COLUMN",
            "CONDITION", "CONSTRAINT", "CONTINUE", "CONVERT", "CREATE", "CROSS", "CUBE", "CUME_DIST", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "CURRENT_USER", "CURSOR", "DATABASE", "DATABASES", "DAY_HOUR", "DAY_MICROSECOND", "DAY_MINUTE", "DAY_SECOND", "DEC", "DECIMAL", "DECLARE",
            "DEFAULT", "DELAYED", "DELETE", "DENSE_RANK", "DESC", "DESCRIBE", "DETERMINISTIC", "DISTINCT", "DISTINCTROW", "DIV", "DOUBLE", "DROP", "DUAL",
            "EACH", "ELSE", "ELSEIF", "EMPTY", "ENCLOSED", "ESCAPED", "EXCEPT", "EXISTS", "EXIT", "EXPLAIN", "FALSE", "FETCH", "FIRST_VALUE", "FLOAT", "FLOAT4",
            "FLOAT8", "FOR", "FORCE", "FOREIGN", "FROM", "FULLTEXT", "FUNCTION", "GENERATED", "GET", "GRANT", "GROUP", "GROUPING", "GROUPS", "HAVING",
            "HIGH_PRIORITY", "HOUR_MICROSECOND", "HOUR_MINUTE", "HOUR_SECOND", "IF", "IGNORE", "IN", "INDEX", "INFILE", "INNER", "INOUT", "INSENSITIVE",
            "INSERT", "INT", "INT1", "INT2", "INT3", "INT4", "INT8", "INTEGER", "INTERSECT", "INTERVAL", "INTO", "IO_AFTER_GTIDS", "IO_BEFORE_GTIDS", "IS",
            "ITERATE", "JOIN", "JSON_TABLE", "KEY", "KEYS", "KILL", "LAG", "LAST_VALUE", "LATERAL", "LEAD", "LEADING", "LEAVE", "LEFT", "LIKE", "LIMIT",
            "LINEAR", "LINES", "LOAD", "LOCALTIME", "LOCALTIMESTAMP", "LOCK", "LONG", "LONGBLOB", "LONGTEXT", "LOOP", "LOW_PRIORITY", "MANUAL", "MASTER_BIND",
            "MASTER_SSL_VERIFY_SERVER_CERT", "MATCH", "MAXVALUE", "MEDIUMBLOB", "MEDIUMINT", "MEDIUMTEXT", "MIDDLEINT", "MINUTE_MICROSECOND", "MINUTE_SECOND",
            "MOD", "MODIFIES", "NATURAL", "NOT", "NO_WRITE_TO_BINLOG", "NTH_VALUE", "NTILE", "NULL", "NUMERIC", "OF", "ON", "OPTIMIZE", "OPTIMIZER_COSTS",
            "OPTION", "OPTIONALLY", "OR", "ORDER", "OUT", "OUTER", "OUTFILE", "OVER", "PARALLEL", "PARTITION", "PERCENT_RANK", "PRECISION", "PRIMARY",
            "PROCEDURE", "PURGE", "QUALIFY", "RANGE", "RANK", "READ", "READS", "READ_WRITE", "REAL", "RECURSIVE", "REFERENCES", "REGEXP", "RELEASE", "RENAME",
            "REPEAT", "REPLACE", "REQUIRE", "RESIGNAL", "RESTRICT", "RETURN", "REVOKE", "RIGHT", "RLIKE", "ROW", "ROWS", "ROW_NUMBER", "SCHEMA", "SCHEMAS",
            "SECOND_MICROSECOND", "SELECT", "SENSITIVE", "SEPARATOR", "SET", "SHOW", "SIGNAL", "SMALLINT", "SPATIAL", "SPECIFIC", "SQL", "SQLEXCEPTION",
            "SQLSTATE", "SQLWARNING", "SQL_BIG_RESULT", "SQL_CALC_FOUND_ROWS", "SQL_SMALL_RESULT", "SSL", "STARTING", "STORED", "STRAIGHT_JOIN", "SYSTEM",
            "TABLE", "TABLESAMPLE", "TERMINATED", "THEN", "TINYBLOB", "TINYINT", "TINYTEXT", "TO", "TRAILING", "TRIGGER", "TRUE", "UNDO", "UNION", "UNIQUE",
            "UNLOCK", "UNSIGNED", "UPDATE", "USAGE", "USE", "USING", "UTC_DATE", "UTC_TIME", "UTC_TIMESTAMP", "VALUES", "VARBINARY", "VARCHAR", "VARCHARACTER",
            "VARYING", "VIRTUAL", "WHEN", "WHERE", "WHILE", "WINDOW", "WITH", "WRITE", "XOR", "YEAR_MONTH", "ZEROFILL");

    // SQL:2003 reserved words from 'ISO/IEC 9075-2:2003 (E), 2003-07-25'.
    /* package private */ static final List<String> SQL2003_KEYWORDS = Arrays.asList("ABS", "ALL", "ALLOCATE", "ALTER", "AND", "ANY", "ARE", "ARRAY", "AS",
            "ASENSITIVE", "ASYMMETRIC", "AT", "ATOMIC", "AUTHORIZATION", "AVG", "BEGIN", "BETWEEN", "BIGINT", "BINARY", "BLOB", "BOOLEAN", "BOTH", "BY", "CALL",
            "CALLED", "CARDINALITY", "CASCADED", "CASE", "CAST", "CEIL", "CEILING", "CHAR", "CHARACTER", "CHARACTER_LENGTH", "CHAR_LENGTH", "CHECK", "CLOB",
            "CLOSE", "COALESCE", "COLLATE", "COLLECT", "COLUMN", "COMMIT", "CONDITION", "CONNECT", "CONSTRAINT", "CONVERT", "CORR", "CORRESPONDING", "COUNT",
            "COVAR_POP", "COVAR_SAMP", "CREATE", "CROSS", "CUBE", "CUME_DIST", "CURRENT", "CURRENT_DATE", "CURRENT_DEFAULT_TRANSFORM_GROUP", "CURRENT_PATH",
            "CURRENT_ROLE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_TRANSFORM_GROUP_FOR_TYPE", "CURRENT_USER", "CURSOR", "CYCLE", "DATE", "DAY",
            "DEALLOCATE", "DEC", "DECIMAL", "DECLARE", "DEFAULT", "DELETE", "DENSE_RANK", "DEREF", "DESCRIBE", "DETERMINISTIC", "DISCONNECT", "DISTINCT",
            "DOUBLE", "DROP", "DYNAMIC", "EACH", "ELEMENT", "ELSE", "END", "END-EXEC", "ESCAPE", "EVERY", "EXCEPT", "EXEC", "EXECUTE", "EXISTS", "EXP",
            "EXTERNAL", "EXTRACT", "FALSE", "FETCH", "FILTER", "FLOAT", "FLOOR", "FOR", "FOREIGN", "FREE", "FROM", "FULL", "FUNCTION", "FUSION", "GET",
            "GLOBAL", "GRANT", "GROUP", "GROUPING", "HAVING", "HOLD", "HOUR", "IDENTITY", "IN", "INDICATOR", "INNER", "INOUT", "INSENSITIVE", "INSERT", "INT",
            "INTEGER", "INTERSECT", "INTERSECTION", "INTERVAL", "INTO", "IS", "JOIN", "LANGUAGE", "LARGE", "LATERAL", "LEADING", "LEFT", "LIKE", "LN", "LOCAL",
            "LOCALTIME", "LOCALTIMESTAMP", "LOWER", "MATCH", "MAX", "MEMBER", "MERGE", "METHOD", "MIN", "MINUTE", "MOD", "MODIFIES", "MODULE", "MONTH",
            "MULTISET", "NATIONAL", "NATURAL", "NCHAR", "NCLOB", "NEW", "NO", "NONE", "NORMALIZE", "NOT", "NULL", "NULLIF", "NUMERIC", "OCTET_LENGTH", "OF",
            "OLD", "ON", "ONLY", "OPEN", "OR", "ORDER", "OUT", "OUTER", "OVER", "OVERLAPS", "OVERLAY", "PARAMETER", "PARTITION", "PERCENTILE_CONT",
            "PERCENTILE_DISC", "PERCENT_RANK", "POSITION", "POWER", "PRECISION", "PREPARE", "PRIMARY", "PROCEDURE", "RANGE", "RANK", "READS", "REAL",
            "RECURSIVE", "REF", "REFERENCES", "REFERENCING", "REGR_AVGX", "REGR_AVGY", "REGR_COUNT", "REGR_INTERCEPT", "REGR_R2", "REGR_SLOPE", "REGR_SXX",
            "REGR_SXY", "REGR_SYY", "RELEASE", "RESULT", "RETURN", "RETURNS", "REVOKE", "RIGHT", "ROLLBACK", "ROLLUP", "ROW", "ROWS", "ROW_NUMBER", "SAVEPOINT",
            "SCOPE", "SCROLL", "SEARCH", "SECOND", "SELECT", "SENSITIVE", "SESSION_USER", "SET", "SIMILAR", "SMALLINT", "SOME", "SPECIFIC", "SPECIFICTYPE",
            "SQL", "SQLEXCEPTION", "SQLSTATE", "SQLWARNING", "SQRT", "START", "STATIC", "STDDEV_POP", "STDDEV_SAMP", "SUBMULTISET", "SUBSTRING", "SUM",
            "SYMMETRIC", "SYSTEM", "SYSTEM_USER", "TABLE", "TABLESAMPLE", "THEN", "TIME", "TIMESTAMP", "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "TO", "TRAILING",
            "TRANSLATE", "TRANSLATION", "TREAT", "TRIGGER", "TRIM", "TRUE", "UESCAPE", "UNION", "UNIQUE", "UNKNOWN", "UNNEST", "UPDATE", "UPPER", "USER",
            "USING", "VALUE", "VALUES", "VARCHAR", "VARYING", "VAR_POP", "VAR_SAMP", "WHEN", "WHENEVER", "WHERE", "WIDTH_BUCKET", "WINDOW", "WITH", "WITHIN",
            "WITHOUT", "YEAR");

    static final Lock KEYWORDS_CACHE_LOCK = new ReentrantLock();
    private static final ServerVersion DEFAULT_SERVER_VERSION = new ServerVersion(0, 0, 0);
    static Map<ServerVersion, String> keywordsCache = Collections.synchronizedMap(new LRUCache<>(10));

    /** Default max buffer size. See {@link PropertyKey#maxAllowedPacket}. */
    static int MAX_BUFFER_SIZE = 65535; // TODO find a way to use actual (not default) value.

    private final JdbcConnection conn;
    private final NativeSession session;
    private final ExceptionInterceptor exceptionInterceptor;
    private final ResultSetFactory resultSetFactory;
    private final String database;
    private final String quoteId;

    private final RuntimeProperty<DatabaseTerm> databaseTermProp;
    private final RuntimeProperty<Boolean> getProceduresReturnsFunctionsProp;
    private final RuntimeProperty<Boolean> noAccessToProcedureBodiesProp;
    private final RuntimeProperty<Boolean> nullDatabaseMeansCurrentProp;
    private final RuntimeProperty<Boolean> pedanticProp;
    private final RuntimeProperty<Boolean> tinyInt1IsBitProp;
    private final RuntimeProperty<Boolean> transformedBitIsBooleanProp;
    private final RuntimeProperty<Boolean> useHostsInPrivilegesProp;
    private final RuntimeProperty<Boolean> yearIsDateTypeProp;

    private String metaDataEncoding = null;
    private int metaDataCollationIndex = -1;

    protected static DatabaseMetaData getInstance(JdbcConnection conn, String database, ResultSetFactory resultSetFactory) throws SQLException {
        if (conn.getPropertySet().getBooleanProperty(PropertyKey.useInformationSchema).getValue()) {
            return new DatabaseMetaDataInformationSchema(conn, database, resultSetFactory);
        }
        return new DatabaseMetaDataMysqlSchema(conn, database, resultSetFactory);
    }

    DatabaseMetaData(JdbcConnection conn, String database, ResultSetFactory resultSetFactory) {
        this.conn = conn;
        this.session = (NativeSession) this.conn.getSession();
        this.exceptionInterceptor = this.conn.getExceptionInterceptor();
        this.database = normalizeIdentifierCase(database);
        this.resultSetFactory = resultSetFactory;
        this.quoteId = this.session.getIdentifierQuoteString();

        this.databaseTermProp = this.conn.getPropertySet().<DatabaseTerm>getEnumProperty(PropertyKey.databaseTerm);
        this.getProceduresReturnsFunctionsProp = this.conn.getPropertySet().getBooleanProperty(PropertyKey.getProceduresReturnsFunctions);
        this.noAccessToProcedureBodiesProp = this.conn.getPropertySet().getBooleanProperty(PropertyKey.noAccessToProcedureBodies);
        this.nullDatabaseMeansCurrentProp = this.conn.getPropertySet().getBooleanProperty(PropertyKey.nullDatabaseMeansCurrent);
        this.pedanticProp = this.conn.getPropertySet().getBooleanProperty(PropertyKey.pedantic);
        this.tinyInt1IsBitProp = this.conn.getPropertySet().getBooleanProperty(PropertyKey.tinyInt1isBit);
        this.transformedBitIsBooleanProp = this.conn.getPropertySet().getBooleanProperty(PropertyKey.transformedBitIsBoolean);
        this.useHostsInPrivilegesProp = this.conn.getPropertySet().getBooleanProperty(PropertyKey.useHostsInPrivileges);
        this.yearIsDateTypeProp = this.conn.getPropertySet().getBooleanProperty(PropertyKey.yearIsDateType);
    }

    JdbcConnection getJdbcConnection() {
        return this.conn;
    }

    NativeSession getSession() {
        return this.session;
    }

    ExceptionInterceptor getExceptionInterceptor() {
        return this.exceptionInterceptor;
    }

    ResultSetFactory getResultSetFactory() {
        return this.resultSetFactory;
    }

    String getDatabase() {
        return this.database;
    }

    String getQuoteId() {
        return this.quoteId;
    }

    /**
     * Returns the value of the {@code databaseTerm} property.
     *
     * @return
     *         The value of the {@code databaseTerm} property.
     */
    DatabaseTerm databaseTermValue() {
        return this.databaseTermProp.getValue();
    }

    /**
     * Returns the value of the {@code getProceduresReturnsFunctions} property.
     *
     * @return
     *         The value of the {@code getProceduresReturnsFunctions} property.
     */
    Boolean getProceduresReturnsFunctionsValue() {
        return this.getProceduresReturnsFunctionsProp.getValue();
    }

    /**
     * Returns the value of the {@code noAccessToProcedureBodies} property.
     *
     * @return
     *         The value of the {@code noAccessToProcedureBodies} property.
     */
    Boolean noAccessToProcedureBodiesValue() {
        return this.noAccessToProcedureBodiesProp.getValue();
    }

    /**
     * Returns the value of the {@code nullDatabaseMeansCurrent} property.
     *
     * @return
     *         The value of the {@code nullDatabaseMeansCurrent} property.
     */
    Boolean nullDatabaseMeansCurrentValue() {
        return this.nullDatabaseMeansCurrentProp.getValue();
    }

    /**
     * Returns the value of the {@code pedantic} property.
     *
     * @return
     *         The value of the {@code pedantic} property.
     */
    Boolean pedanticValue() {
        return this.pedanticProp.getValue();
    }

    /**
     * Returns the value of the {@code tinyInt1isBit} property.
     *
     * @return
     *         The value of the {@code tinyInt1isBit} property.
     */
    Boolean tinyInt1IsBitValue() {
        return this.tinyInt1IsBitProp.getValue();
    }

    /**
     * Returns the value of the {@code transformedBitIsBoolean} property.
     *
     * @return
     *         The value of the {@code transformedBitIsBoolean} property.
     */
    Boolean transformedBitIsBooleanValue() {
        return this.transformedBitIsBooleanProp.getValue();
    }

    /**
     * Returns the value of the {@code useHostsInPrivileges} property.
     *
     * @return
     *         The value of the {@code useHostsInPrivileges} property.
     */
    Boolean useHostsInPrivilegesValue() {
        return this.useHostsInPrivilegesProp.getValue();
    }

    /**
     * Returns the value of the {@code yearIsDateType} property.
     *
     * @return
     *         The value of the {@code yearIsDateType} property.
     */
    Boolean yearIsDateTypeValue() {
        return this.yearIsDateTypeProp.getValue();
    }

    public String getMetaDataEncoding() {
        return this.metaDataEncoding;
    }

    public void setMetaDataEncoding(String metadataEncoding) {
        this.metaDataEncoding = metadataEncoding;
    }

    public int getMetaDataCollationIndex() {
        return this.metaDataCollationIndex;
    }

    public void setMetadataCollationIndex(int metadataCollationIndex) {
        this.metaDataCollationIndex = metadataCollationIndex;
    }

    /**
     * Creates and returns a (client-side) prepared statement.
     *
     * @param sql
     *            The query to prepare.
     * @return PreparedStatement
     *         A client prepared statement safe to use in the Metadata methods.
     * @throws SQLException
     *             If a database access error occurs.
     */
    PreparedStatement prepareMetaDataSafeStatement(String sql) throws SQLException {
        TelemetrySpan span = this.session.getTelemetryHandler().startSpan(TelemetrySpanName.STMT_PREPARE);
        try (TelemetryScope scope = span.makeCurrent()) {
            String dbOperation = QueryInfo.getStatementKeyword(sql, this.session.getServerSession().isNoBackslashEscapesSet());
            span.setAttribute(TelemetryAttribute.DB_NAME, () -> this.conn.getDatabase());
            span.setAttribute(TelemetryAttribute.DB_OPERATION, dbOperation);
            span.setAttribute(TelemetryAttribute.DB_STATEMENT, dbOperation + TelemetryAttribute.STATEMENT_SUFFIX);
            span.setAttribute(TelemetryAttribute.DB_SYSTEM, TelemetryAttribute.DB_SYSTEM_DEFAULT);
            span.setAttribute(TelemetryAttribute.DB_USER, () -> this.conn.getUser());
            span.setAttribute(TelemetryAttribute.THREAD_ID, () -> Thread.currentThread().getId());
            span.setAttribute(TelemetryAttribute.THREAD_NAME, () -> Thread.currentThread().getName());

            // Can't use server-side here as a lot of types are coerced to match the spec.
            PreparedStatement pStmt = this.conn.clientPrepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);

            if (pStmt.getMaxRows() != 0) {
                pStmt.setMaxRows(0);
            }

            ((com.mysql.cj.jdbc.JdbcStatement) pStmt).setHoldResultsOpenOverClose(true);
            return pStmt;
        } catch (Throwable t) {
            span.setError(t);
            throw t;
        } finally {
            span.end();
        }
    }

    /**
     * Converts the given string to bytes, using the connection's character encoding or, if not available, the JVM default encoding.
     *
     * @param s
     *            The string to convert.
     * @return
     *         The string as byte array.
     * @throws SQLException
     *             If a conversion error occurs.
     */
    byte[] s2b(String s) throws SQLException {
        if (s == null) {
            return null;
        }

        try {
            return StringUtils.getBytes(s, getMetaDataEncoding());
        } catch (CJException e) {
            throw SQLExceptionsMapping.translateException(e, getExceptionInterceptor());
        }
    }

    /**
     * Converts String representation of the given number to bytes.
     *
     * @param n
     *            the number to convert.
     * @return
     *         The number converted to String as a byte array.
     */
    byte[] n2b(Number n) {
        return n.toString().getBytes();
    }

    /**
     * Normalizes the specified identifier considering how they are stored in relation to their case.
     *
     * @param identifier
     *            The entity name to normalize.
     * @return
     *         A case-normalized version of the specified entity name.
     */
    String normalizeIdentifierCase(String identifier) {
        if (identifier == null) {
            return null;
        }
        try {
            return storesLowerCaseIdentifiers() ? identifier.toLowerCase(Locale.ROOT) : identifier;
        } catch (SQLException e) {
            return identifier;
        }
    }

    /**
     * Normalizes the specified identifier considering quoting and whether pedantic mode is "on" or "off". In pedantic mode "on", specified identifiers are used
     * as-is. In pedantic mode "off", the driver does a "smart" unquoting, i.e., only well formed quoted identifiers can be unquoted.
     *
     * @param identifier
     *            The identifier to unquote.
     * @return
     *         The unquoted version of the identifier name, depending on the pedantic mode set.
     */
    String normalizeIdentifierQuoting(String identifier) {
        return pedanticValue() ? identifier : StringUtils.unquoteIdentifier(identifier, this.quoteId);
    }

    /**
     * Quote the specified identifier regardless the pedantic mode in use.
     *
     * @param identifier
     *            The identifier to quote.
     * @return
     *         A quoted version of the identifier.
     */
    String quoteIdentifier(String identifier) {
        return StringUtils.quoteIdentifier(identifier, this.quoteId, true);
    }

    /**
     * Chooses the database name from the specified catalog and schema depending on the value of the connection property {@link PropertyKey#databaseTerm}.
     *
     * @param catalog
     *            The catalog name to choose from.
     * @param schema
     *            The schema name to choose from.
     * @return
     *         The curated database name depending on the configured value for the connection property {@link PropertyKey#databaseTerm}.
     */
    String chooseDatabaseTerm(String catalog, String schema) {
        String databaseName = chooseBasedOnDatabaseTerm(() -> catalog, () -> schema);
        if (databaseName == null && nullDatabaseMeansCurrentValue()) {
            return normalizeIdentifierCase(this.database);
        }
        return databaseName;
    }

    /**
     * Chooses between two options that depend on the value of the connection property {@link PropertyKey#databaseTerm}.
     *
     * @param <T>
     *            The type of the values to choose from.
     * @param <E>
     *            The exception type the specified suppliers might throw.
     * @param catalogChoice
     *            The choice when database term is "CATALOG".
     * @param schemaChoice
     *            The choice when the database term is "SCHEMA".
     * @return
     *         One of the two specified choices.
     * @throws E
     *             If the chosen option throws an exception when being evaluated.
     */
    <T, E extends Exception> T chooseBasedOnDatabaseTerm(ThrowingSupplier<T, E> catalogChoice, ThrowingSupplier<T, E> schemaChoice) throws E {
        return databaseTermValue() == DatabaseTerm.CATALOG ? catalogChoice.get() : schemaChoice.get();
    }

    /**
     * Returns the metadata information for the specified MySQL type.
     *
     * @param mysqlTypeName
     *            String name is used here to allow aliases for the same MysqlType to be listed too.
     * @return bytes
     *         The type name as a byte array.
     * @throws SQLException
     *             If a conversion error occurs.
     */
    private byte[][] getTypeInfo(String mysqlTypeName) throws SQLException {
        MysqlType mt = MysqlType.getByName(mysqlTypeName);
        byte[][] row = new byte[18][];

        row[0] = s2b(mysqlTypeName);                                                                                        // TYPE_NAME
        row[1] = n2b(mt == MysqlType.YEAR && !yearIsDateTypeValue() ? //
                Types.SMALLINT : mt.getJdbcType());                                                                         // DATA_TYPE
        // JDBC spec reserved only 'int' type for precision.
        row[2] = n2b(mt.getPrecision() > Integer.MAX_VALUE ? Integer.MAX_VALUE : mt.getPrecision().intValue());             // PRECISION
        switch (mt) {
            case TINYBLOB:
            case BLOB:
            case MEDIUMBLOB:
            case LONGBLOB:
            case TINYTEXT:
            case TEXT:
            case MEDIUMTEXT:
            case LONGTEXT:
            case JSON:
            case BINARY:
            case VARBINARY:
            case CHAR:
            case VARCHAR:
            case ENUM:
            case SET:
            case DATE:
            case TIME:
            case DATETIME:
            case TIMESTAMP:
            case GEOMETRY:
            case VECTOR:
            case UNKNOWN:
                row[3] = s2b("'");                                                                                          // LITERAL_PREFIX
                row[4] = s2b("'");                                                                                          // LITERAL_SUFFIX
                break;
            default:
                row[3] = s2b("");                                                                                           // LITERAL_PREFIX
                row[4] = s2b("");                                                                                           // LITERAL_SUFFIX
        }
        row[5] = s2b(mt.getCreateParams());                                                                                 // CREATE_PARAMS
        row[6] = n2b(typeNullable);                                                                                         // NULLABLE
        row[7] = s2b("true");                                                                                               // CASE_SENSITIVE
        row[8] = n2b(typeSearchable);                                                                                       // SEARCHABLE
        row[9] = s2b(mt.isAllowed(MysqlType.FIELD_FLAG_UNSIGNED) ? "true" : "false");                                       // UNSIGNED_ATTRIBUTE
        row[10] = s2b("false");                                                                                             // FIXED_PREC_SCALE
        switch (mt) {
            case BIGINT:
            case BIGINT_UNSIGNED:
            case BOOLEAN:
            case INT:
            case INT_UNSIGNED:
            case MEDIUMINT:
            case MEDIUMINT_UNSIGNED:
            case SMALLINT:
            case SMALLINT_UNSIGNED:
            case TINYINT:
            case TINYINT_UNSIGNED:
                row[11] = s2b("true");                                                                                      // AUTO_INCREMENT
                break;
            case DOUBLE:
            case DOUBLE_UNSIGNED:
            case FLOAT:
            case FLOAT_UNSIGNED:
                boolean supportsAutoIncrement = !this.session.versionMeetsMinimum(8, 4, 0);
                row[11] = supportsAutoIncrement ? s2b("true") : s2b("false");                                               // AUTO_INCREMENT
                break;
            default:
                row[11] = s2b("false");                                                                                     // AUTO_INCREMENT
                break;
        }
        row[12] = s2b(mt.getName());                                                                                        // LOCAL_TYPE_NAME
        switch (mt) {
            case DECIMAL: // TODO is it right? DECIMAL isn't a floating-point number...
            case DECIMAL_UNSIGNED:
            case DOUBLE:
            case DOUBLE_UNSIGNED:
                row[13] = s2b("-308");                                                                                      // MINIMUM_SCALE
                row[14] = s2b("308");                                                                                       // MAXIMUM_SCALE
                break;
            case FLOAT:
            case FLOAT_UNSIGNED:
                row[13] = s2b("-38");                                                                                       // MINIMUM_SCALE
                row[14] = s2b("38");                                                                                        // MAXIMUM_SCALE
                break;
            default:
                row[13] = s2b("0");                                                                                         // MINIMUM_SCALE
                row[14] = s2b("0");                                                                                         // MAXIMUM_SCALE
        }
        row[15] = s2b("0");                                                                                                 // SQL_DATA_TYPE (not used)
        row[16] = s2b("0");                                                                                                 // SQL_DATETIME_SUB (not used)
        row[17] = s2b("10");                                                                                                // NUM_PREC_RADIX

        return row;
    }

    Field[] createBestRowIdentifierFields() {
        Field[] fields = new Field[8];
        fields[0] = new Field("", "SCOPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 5);
        fields[1] = new Field("", "COLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 4);
        fields[3] = new Field("", "TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "COLUMN_SIZE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[5] = new Field("", "BUFFER_LENGTH", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[6] = new Field("", "DECIMAL_DIGITS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 10);
        fields[7] = new Field("", "PSEUDO_COLUMN", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 5);
        return fields;
    }

    Field[] createCatalogsFields() {
        Field[] fields = new Field[1];
        fields[0] = new Field("", "TABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        return fields;
    }

    Field[] createColumnPrivilegesFields() {
        Field[] fields = new Field[8];
        fields[0] = new Field("", "TABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "TABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "COLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "GRANTOR", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[5] = new Field("", "GRANTEE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[6] = new Field("", "PRIVILEGE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[7] = new Field("", "IS_GRANTABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 3);
        return fields;
    }

    Field[] createColumnsFields() {
        Field[] fields = new Field[24];
        fields[0] = new Field("", "TABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "TABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "COLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 5);
        fields[5] = new Field("", "TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[6] = new Field("", "COLUMN_SIZE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT,
                Integer.toString(Integer.MAX_VALUE).length());
        fields[7] = new Field("", "BUFFER_LENGTH", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[8] = new Field("", "DECIMAL_DIGITS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[9] = new Field("", "NUM_PREC_RADIX", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[10] = new Field("", "NULLABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[11] = new Field("", "REMARKS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 0);
        fields[12] = new Field("", "COLUMN_DEF", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 0);
        fields[13] = new Field("", "SQL_DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[14] = new Field("", "SQL_DATETIME_SUB", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[15] = new Field("", "CHAR_OCTET_LENGTH", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT,
                Integer.toString(Integer.MAX_VALUE).length());
        fields[16] = new Field("", "ORDINAL_POSITION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[17] = new Field("", "IS_NULLABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 3);
        fields[18] = new Field("", "SCOPE_CATALOG", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[19] = new Field("", "SCOPE_SCHEMA", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[20] = new Field("", "SCOPE_TABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[21] = new Field("", "SOURCE_DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 10);
        fields[22] = new Field("", "IS_AUTOINCREMENT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 3);
        fields[23] = new Field("", "IS_GENERATEDCOLUMN", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 3);
        return fields;
    }

    Field[] createForeignKeysFields() {
        Field[] fields = new Field[14];
        fields[0] = new Field("", "PKTABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "PKTABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "PKTABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "PKCOLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "FKTABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[5] = new Field("", "FKTABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[6] = new Field("", "FKTABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[7] = new Field("", "FKCOLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[8] = new Field("", "KEY_SEQ", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 2);
        fields[9] = new Field("", "UPDATE_RULE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 2);
        fields[10] = new Field("", "DELETE_RULE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 2);
        fields[11] = new Field("", "FK_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 0);
        fields[12] = new Field("", "PK_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 0);
        fields[13] = new Field("", "DEFERRABILITY", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 2);
        return fields;
    }

    Field[] createFunctionColumnsFields() {
        Field[] fields = { new Field("", "FUNCTION_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "FUNCTION_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "FUNCTION_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "COLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "COLUMN_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 6),
                new Field("", "TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "PRECISION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12),
                new Field("", "LENGTH", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12),
                new Field("", "SCALE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 12),
                new Field("", "RADIX", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 6),
                new Field("", "NULLABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 6),
                new Field("", "REMARKS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "CHAR_OCTET_LENGTH", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 32),
                new Field("", "ORDINAL_POSITION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 32),
                new Field("", "IS_NULLABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 12),
                new Field("", "SPECIFIC_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64) };
        return fields;
    }

    Field[] createFunctionsFields() {
        Field[] fields = new Field[6];
        fields[0] = new Field("", "FUNCTION_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "FUNCTION_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "FUNCTION_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "REMARKS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 512);
        fields[4] = new Field("", "FUNCTION_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 6);
        fields[5] = new Field("", "SPECIFIC_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        return fields;
    }

    Field[] createIndexInfoFields() {
        Field[] fields = new Field[13];
        fields[0] = new Field("", "TABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "TABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "NON_UNIQUE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.BOOLEAN, 4);
        fields[4] = new Field("", "INDEX_QUALIFIER", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 1);
        fields[5] = new Field("", "INDEX_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[6] = new Field("", "TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 4);
        fields[7] = new Field("", "ORDINAL_POSITION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 4);
        fields[8] = new Field("", "COLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[9] = new Field("", "ASC_OR_DESC", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 1);
        fields[10] = new Field("", "CARDINALITY", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.BIGINT, 20);
        fields[11] = new Field("", "PAGES", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.BIGINT, 20);
        fields[12] = new Field("", "FILTER_CONDITION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 32);
        return fields;
    }

    Field[] createPrimaryKeysFields() {
        Field[] fields = new Field[6];
        fields[0] = new Field("", "TABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "TABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "COLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "KEY_SEQ", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 5);
        fields[5] = new Field("", "PK_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        return fields;
    }

    Field[] createProcedureColumnsFields() {
        Field[] fields = new Field[20];
        fields[0] = new Field("", "PROCEDURE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "PROCEDURE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "PROCEDURE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "COLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "COLUMN_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[5] = new Field("", "DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 4);
        fields[6] = new Field("", "TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[7] = new Field("", "PRECISION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12);
        fields[8] = new Field("", "LENGTH", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12);
        fields[9] = new Field("", "SCALE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 12);
        fields[10] = new Field("", "RADIX", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 6);
        fields[11] = new Field("", "NULLABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 6);
        fields[12] = new Field("", "REMARKS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 512);
        fields[13] = new Field("", "COLUMN_DEF", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 512);
        fields[14] = new Field("", "SQL_DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12);
        fields[15] = new Field("", "SQL_DATETIME_SUB", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12);
        fields[16] = new Field("", "CHAR_OCTET_LENGTH", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12);
        fields[17] = new Field("", "ORDINAL_POSITION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12);
        fields[18] = new Field("", "IS_NULLABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 4);
        fields[19] = new Field("", "SPECIFIC_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        return fields;
    }

    Field[] createProceduresFields() {
        Field[] fields = new Field[9];
        fields[0] = new Field("", "PROCEDURE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "PROCEDURE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "PROCEDURE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "reserved1", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 0);
        fields[4] = new Field("", "reserved2", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 0);
        fields[5] = new Field("", "reserved3", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 0);
        fields[6] = new Field("", "REMARKS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 512);
        fields[7] = new Field("", "PROCEDURE_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 6);
        fields[8] = new Field("", "SPECIFIC_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);

        return fields;
    }

    Field[] createSchemasFields() {
        Field[] fields = new Field[2];
        fields[0] = new Field("", "TABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TABLE_CATALOG", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        return fields;
    }

    Field[] createTablePrivilegesFields() {
        Field[] fields = new Field[7];
        fields[0] = new Field("", "TABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "TABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "GRANTOR", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "GRANTEE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[5] = new Field("", "PRIVILEGE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[6] = new Field("", "IS_GRANTABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 4);

        return fields;
    }

    ColumnDefinition createTablesFields() {
        Field[] fields = new Field[10];
        fields[0] = new Field("", "TABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[1] = new Field("", "TABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[2] = new Field("", "TABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[3] = new Field("", "TABLE_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[4] = new Field("", "REMARKS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 512);
        fields[5] = new Field("", "TYPE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[6] = new Field("", "TYPE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[7] = new Field("", "TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[8] = new Field("", "SELF_REFERENCING_COL_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[9] = new Field("", "REF_GENERATION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 0);
        return new DefaultColumnDefinition(fields);
    }

    Field[] createVersionColumnsFields() {
        Field[] fields = new Field[8];
        fields[0] = new Field("", "SCOPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 5);
        fields[1] = new Field("", "COLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 5);
        fields[3] = new Field("", "TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "COLUMN_SIZE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 16);
        fields[5] = new Field("", "BUFFER_LENGTH", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 16);
        fields[6] = new Field("", "DECIMAL_DIGITS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 16);
        fields[7] = new Field("", "PSEUDO_COLUMN", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 5);
        return fields;
    }

    /*
     * API methods.
     */

    @Override
    public boolean allProceduresAreCallable() throws SQLException {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        return true;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
        return true;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws SQLException {
        return true;
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) throws SQLException {
        Field[] fields = new Field[21];
        fields[0] = new Field("", "TYPE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TYPE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "ATTR_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 4);
        fields[5] = new Field("", "ATTR_TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[6] = new Field("", "ATTR_SIZE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 4);
        fields[7] = new Field("", "DECIMAL_DIGITS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 32);
        fields[8] = new Field("", "NUM_PREC_RADIX", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 32);
        fields[9] = new Field("", "NULLABLE ", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 4);
        fields[10] = new Field("", "REMARKS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 512);
        fields[11] = new Field("", "ATTR_DEF", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[12] = new Field("", "SQL_DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 4);
        fields[13] = new Field("", "SQL_DATETIME_SUB", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 4);
        fields[14] = new Field("", "CHAR_OCTET_LENGTH", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 32);
        fields[15] = new Field("", "ORDINAL_POSITION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 32);
        fields[16] = new Field("", "IS_NULLABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 8);
        fields[17] = new Field("", "SCOPE_CATALOG", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[18] = new Field("", "SCOPE_SCHEMA", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[19] = new Field("", "SCOPE_TABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[20] = new Field("", "SOURCE_DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 4);

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    // @Override
    // public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException;

    // @Override
    // public ResultSet getCatalogs() throws SQLException;

    @Override
    public String getCatalogSeparator() throws SQLException {
        return ".";
    }

    @Override
    public String getCatalogTerm() throws SQLException {
        return chooseBasedOnDatabaseTerm(() -> "database", () -> "CATALOG");
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        // No fixed built-in client info properties. MySQL supports whatever the client wants to provide, however there's no way to express this with the
        // interface given.
        Field[] fields = new Field[4];
        fields[0] = new Field("", "NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[1] = new Field("", "MAX_LEN", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[2] = new Field("", "DEFAULT_VALUE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 255);
        fields[3] = new Field("", "DESCRIPTION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 255);

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    // @Override
    // public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException;

    // @Override
    // public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException;

    @Override
    public Connection getConnection() throws SQLException {
        return this.conn;
    }

    // @Override
    // public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog, String foreignSchema,
    //         String foreignTable) throws SQLException;

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return this.conn.getServerVersion().getMajor();
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return this.conn.getServerVersion().getMinor();
    }

    @Override
    public String getDatabaseProductName() throws SQLException {
        return "MySQL";
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        return this.conn.getServerVersion().toString();
    }

    @Override
    public int getDefaultTransactionIsolation() throws SQLException {
        return Connection.TRANSACTION_REPEATABLE_READ;
    }

    @Override
    public int getDriverMajorVersion() {
        return NonRegisteringDriver.getMajorVersionInternal();
    }

    @Override
    public int getDriverMinorVersion() {
        return NonRegisteringDriver.getMinorVersionInternal();
    }

    @Override
    public String getDriverName() throws SQLException {
        return Constants.CJ_NAME;
    }

    @Override
    public String getDriverVersion() throws SQLException {
        return Constants.CJ_FULL_NAME + " (Revision: " + Constants.CJ_REVISION + ")";
    }

    // @Override
    // public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException;

    @Override
    public String getExtraNameCharacters() throws SQLException {
        return "$";
    }

    // @Override
    // public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException;

    // @Override
    // public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException;

    @Override
    public String getIdentifierQuoteString() throws SQLException {
        return this.quoteId;
    }

    // @Override
    // public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException;

    // @Override
    // public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException;

    @Override
    public int getJDBCMajorVersion() throws SQLException {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() throws SQLException {
        return 2;
    }

    @Override
    public int getMaxBinaryLiteralLength() throws SQLException {
        return 16777208;
    }

    @Override
    public int getMaxCatalogNameLength() throws SQLException {
        return 32;
    }

    @Override
    public int getMaxCharLiteralLength() throws SQLException {
        return 16777208;
    }

    @Override
    public int getMaxColumnNameLength() throws SQLException {
        return 64;
    }

    @Override
    public int getMaxColumnsInGroupBy() throws SQLException {
        return 64;
    }

    @Override
    public int getMaxColumnsInIndex() throws SQLException {
        return 16;
    }

    @Override
    public int getMaxColumnsInOrderBy() throws SQLException {
        return 64;
    }

    @Override
    public int getMaxColumnsInSelect() throws SQLException {
        return 256;
    }

    @Override
    public int getMaxColumnsInTable() throws SQLException {
        return 512;
    }

    @Override
    public int getMaxConnections() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() throws SQLException {
        return 64;
    }

    @Override
    public int getMaxIndexLength() throws SQLException {
        return 256;
    }

    // @Override
    // public long getMaxLogicalLobSize() throws SQLException;

    @Override
    public int getMaxProcedureNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxRowSize() throws SQLException {
        return Integer.MAX_VALUE - 8; // Max buffer size - HEADER
    }

    @Override
    public int getMaxSchemaNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxStatementLength() throws SQLException {
        return MAX_BUFFER_SIZE - 4; // Max buffer - header
    }

    @Override
    public int getMaxStatements() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() throws SQLException {
        return 64;
    }

    @Override
    public int getMaxTablesInSelect() throws SQLException {
        return 256;
    }

    @Override
    public int getMaxUserNameLength() throws SQLException {
        return 16;
    }

    @Override
    public String getNumericFunctions() throws SQLException {
        return "ABS,ACOS,ASIN,ATAN,ATAN2,BIT_COUNT,CEILING,COS,COT,DEGREES,EXP,FLOOR,LOG,LOG10,MAX,MIN,MOD,PI,POW,"
                + "POWER,RADIANS,RAND,ROUND,SIN,SQRT,TAN,TRUNCATE";
    }

    // @Override
    // public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException;

    // @Override
    // public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException;

    // @Override
    // public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException;

    @Override
    public String getProcedureTerm() throws SQLException {
        return "PROCEDURE";
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        Field[] fields = { new Field("", "TABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "TABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "TABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "COLUMN_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64),
                new Field("", "DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12),
                new Field("", "COLUMN_SIZE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12),
                new Field("", "DECIMAL_DIGITS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12),
                new Field("", "NUM_PREC_RADIX", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12),
                new Field("", "COLUMN_USAGE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "REMARKS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 512),
                new Field("", "CHAR_OCTET_LENGTH", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 12),
                new Field("", "IS_NULLABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 8) };

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    // @Override
    // public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException;

    @Override
    public String getSchemaTerm() throws SQLException {
        return chooseBasedOnDatabaseTerm(() -> "", () -> "SCHEMA");
    }

    @Override
    public String getSearchStringEscape() throws SQLException {
        return "\\";
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        String keywords = keywordsCache.get(DEFAULT_SERVER_VERSION);
        if (keywords != null) {
            return keywords;
        }

        KEYWORDS_CACHE_LOCK.lock();
        try {
            // Double check, maybe it's already set.
            keywords = keywordsCache.get(DEFAULT_SERVER_VERSION);
            if (keywords != null) {
                return keywords;
            }

            Set<String> mysqlKeywordSet = new TreeSet<>();
            mysqlKeywordSet.addAll(MYSQL_KEYWORDS);
            mysqlKeywordSet.removeAll(SQL2003_KEYWORDS);

            keywords = mysqlKeywordSet.stream().collect(Collectors.joining(","));
            keywordsCache.put(this.conn.getServerVersion(), keywords);
            return keywords;
        } finally {
            KEYWORDS_CACHE_LOCK.unlock();
        }
    }

    @Override
    public int getSQLStateType() throws SQLException {
        return sqlStateSQL;
    }

    @Override
    public String getStringFunctions() throws SQLException {
        return "ASCII,BIN,BIT_LENGTH,CHAR,CHARACTER_LENGTH,CHAR_LENGTH,CONCAT,CONCAT_WS,CONV,ELT,EXPORT_SET,FIELD,FIND_IN_SET,HEX,INSERT,"
                + "INSTR,LCASE,LEFT,LENGTH,LOAD_FILE,LOCATE,LOCATE,LOWER,LPAD,LTRIM,MAKE_SET,MATCH,MID,OCT,OCTET_LENGTH,ORD,POSITION,"
                + "QUOTE,REPEAT,REPLACE,REVERSE,RIGHT,RPAD,RTRIM,SOUNDEX,SPACE,STRCMP,SUBSTRING,SUBSTRING,SUBSTRING,SUBSTRING,"
                + "SUBSTRING_INDEX,TRIM,UCASE,UPPER";
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        Field[] fields = new Field[4];
        fields[0] = new Field("", "TABLE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TABLE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "TABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "SUPERTABLE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        Field[] fields = new Field[6];
        fields[0] = new Field("", "TYPE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "TYPE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[2] = new Field("", "TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[3] = new Field("", "SUPERTYPE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[4] = new Field("", "SUPERTYPE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[5] = new Field("", "SUPERTYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    @Override
    public String getSystemFunctions() throws SQLException {
        return "DATABASE,USER,SYSTEM_USER,SESSION_USER,PASSWORD,ENCRYPT,LAST_INSERT_ID,VERSION";
    }

    // @Override
    // public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException;

    // @Override
    // public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException;

    @Override
    public ResultSet getTableTypes() throws SQLException {
        ArrayList<Row> rows = new ArrayList<>();
        Field[] fields = new Field[] { new Field("", "TABLE_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64) };

        rows.add(new ByteArrayRow(new byte[][] { TableType.LOCAL_TEMPORARY.asBytes() }, getExceptionInterceptor()));
        rows.add(new ByteArrayRow(new byte[][] { TableType.SYSTEM_TABLE.asBytes() }, getExceptionInterceptor()));
        rows.add(new ByteArrayRow(new byte[][] { TableType.SYSTEM_VIEW.asBytes() }, getExceptionInterceptor()));
        rows.add(new ByteArrayRow(new byte[][] { TableType.TABLE.asBytes() }, getExceptionInterceptor()));
        rows.add(new ByteArrayRow(new byte[][] { TableType.VIEW.asBytes() }, getExceptionInterceptor()));

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public String getTimeDateFunctions() throws SQLException {
        return "DAYOFWEEK,WEEKDAY,DAYOFMONTH,DAYOFYEAR,MONTH,DAYNAME,MONTHNAME,QUARTER,WEEK,YEAR,HOUR,MINUTE,SECOND,PERIOD_ADD,"
                + "PERIOD_DIFF,TO_DAYS,FROM_DAYS,DATE_FORMAT,TIME_FORMAT,CURDATE,CURRENT_DATE,CURTIME,CURRENT_TIME,NOW,SYSDATE,"
                + "CURRENT_TIMESTAMP,UNIX_TIMESTAMP,FROM_UNIXTIME,SEC_TO_TIME,TIME_TO_SEC";
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        Field[] fields = new Field[18];
        fields[0] = new Field("", "TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[1] = new Field("", "DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 5);
        fields[2] = new Field("", "PRECISION", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[3] = new Field("", "LITERAL_PREFIX", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 8);
        fields[4] = new Field("", "LITERAL_SUFFIX", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 8);
        fields[5] = new Field("", "CREATE_PARAMS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[6] = new Field("", "NULLABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 5);
        fields[7] = new Field("", "CASE_SENSITIVE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.BOOLEAN, 3);
        fields[8] = new Field("", "SEARCHABLE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 3);
        fields[9] = new Field("", "UNSIGNED_ATTRIBUTE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.BOOLEAN, 3);
        fields[10] = new Field("", "FIXED_PREC_SCALE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.BOOLEAN, 3);
        fields[11] = new Field("", "AUTO_INCREMENT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.BOOLEAN, 3);
        fields[12] = new Field("", "LOCAL_TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.CHAR, 64);
        fields[13] = new Field("", "MINIMUM_SCALE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 5);
        fields[14] = new Field("", "MAXIMUM_SCALE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 5);
        fields[15] = new Field("", "SQL_DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[16] = new Field("", "SQL_DATETIME_SUB", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[17] = new Field("", "NUM_PREC_RADIX", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);

        ArrayList<Row> rows = new ArrayList<>();

        // Ordered by DATA_TYPE and then by how closely the data type maps to the corresponding JDBC SQL type.
        // java.sql.Types.BIT = -7
        rows.add(new ByteArrayRow(getTypeInfo("BIT"), getExceptionInterceptor()));
        // java.sql.Types.TINYINT = -6
        rows.add(new ByteArrayRow(getTypeInfo("TINYINT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("TINYINT UNSIGNED"), getExceptionInterceptor()));
        // java.sql.Types.BIGINT = -5
        rows.add(new ByteArrayRow(getTypeInfo("BIGINT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("BIGINT UNSIGNED"), getExceptionInterceptor()));
        // java.sql.Types.LONGVARBINARY = -4
        rows.add(new ByteArrayRow(getTypeInfo("LONG VARBINARY"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("MEDIUMBLOB"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("LONGBLOB"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("BLOB"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("VECTOR"), getExceptionInterceptor()));
        // java.sql.Types.VARBINARY = -3
        rows.add(new ByteArrayRow(getTypeInfo("VARBINARY"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("TINYBLOB"), getExceptionInterceptor()));
        // java.sql.Types.BINARY = -2
        rows.add(new ByteArrayRow(getTypeInfo("BINARY"), getExceptionInterceptor()));
        // java.sql.Types.LONGVARCHAR = -1
        rows.add(new ByteArrayRow(getTypeInfo("LONG VARCHAR"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("MEDIUMTEXT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("LONGTEXT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("TEXT"), getExceptionInterceptor()));
        // java.sql.Types.CHAR = 1
        rows.add(new ByteArrayRow(getTypeInfo("CHAR"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("ENUM"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("SET"), getExceptionInterceptor()));
        // java.sql.Types.DECIMAL = 3
        rows.add(new ByteArrayRow(getTypeInfo("DECIMAL"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("NUMERIC"), getExceptionInterceptor()));
        // java.sql.Types.INTEGER = 4
        rows.add(new ByteArrayRow(getTypeInfo("INTEGER"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("INT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("MEDIUMINT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("INTEGER UNSIGNED"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("INT UNSIGNED"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("MEDIUMINT UNSIGNED"), getExceptionInterceptor()));
        // java.sql.Types.SMALLINT = 5
        rows.add(new ByteArrayRow(getTypeInfo("SMALLINT"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("SMALLINT UNSIGNED"), getExceptionInterceptor()));
        if (!yearIsDateTypeValue()) {
            rows.add(new ByteArrayRow(getTypeInfo("YEAR"), getExceptionInterceptor()));
        }
        // java.sql.Types.REAL = 7
        rows.add(new ByteArrayRow(getTypeInfo("FLOAT"), getExceptionInterceptor()));
        // java.sql.Types.DOUBLE = 8
        rows.add(new ByteArrayRow(getTypeInfo("DOUBLE"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("DOUBLE PRECISION"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("REAL"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("DOUBLE UNSIGNED"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("DOUBLE PRECISION UNSIGNED"), getExceptionInterceptor()));
        // java.sql.Types.VARCHAR = 12
        rows.add(new ByteArrayRow(getTypeInfo("VARCHAR"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("TINYTEXT"), getExceptionInterceptor()));
        // java.sql.Types.BOOLEAN = 16
        rows.add(new ByteArrayRow(getTypeInfo("BOOL"), getExceptionInterceptor()));
        // java.sql.Types.DATE = 91
        rows.add(new ByteArrayRow(getTypeInfo("DATE"), getExceptionInterceptor()));
        if (yearIsDateTypeValue()) {
            rows.add(new ByteArrayRow(getTypeInfo("YEAR"), getExceptionInterceptor()));
        }
        // java.sql.Types.TIME = 92
        rows.add(new ByteArrayRow(getTypeInfo("TIME"), getExceptionInterceptor()));
        // java.sql.Types.TIMESTAMP = 93
        rows.add(new ByteArrayRow(getTypeInfo("DATETIME"), getExceptionInterceptor()));
        rows.add(new ByteArrayRow(getTypeInfo("TIMESTAMP"), getExceptionInterceptor()));

        // TODO add missed types (aliases)

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException {
        Field[] fields = new Field[7];
        fields[0] = new Field("", "TYPE_CAT", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[1] = new Field("", "TYPE_SCHEM", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[2] = new Field("", "TYPE_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[3] = new Field("", "CLASS_NAME", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 64);
        fields[4] = new Field("", "DATA_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.INT, 10);
        fields[5] = new Field("", "REMARKS", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.VARCHAR, 512);
        fields[6] = new Field("", "BASE_TYPE", this.metaDataCollationIndex, this.metaDataEncoding, MysqlType.SMALLINT, 10);

        return this.resultSetFactory.createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(new ArrayList<>(), new DefaultColumnDefinition(fields)));
    }

    @Override
    public String getURL() throws SQLException {
        return this.conn.getURL();
    }

    @Override
    public String getUserName() throws SQLException {
        if (useHostsInPrivilegesValue()) {
            Statement stmt = null;
            ResultSet rs = null;

            try {
                stmt = this.conn.getMetaDataSafeStatement();
                rs = stmt.executeQuery("SELECT USER()");
                rs.next();
                return rs.getString(1);
            } finally {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (Exception ex) {
                        AssertionFailedException.shouldNotHappen(ex);
                    }
                    rs = null;
                }
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (Exception ex) {
                        AssertionFailedException.shouldNotHappen(ex);
                    }
                    stmt = null;
                }
            }
        }
        return this.conn.getUser();
    }

    // @Override
    // public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException;

    @Override
    public boolean insertsAreDetected(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean isCatalogAtStart() throws SQLException {
        return true; // There is no similar method for SCHEMA.
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() throws SQLException {
        return !this.conn.getPropertySet().getBooleanProperty(PropertyKey.emulateLocators).getValue();
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException {
        return true;
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        return !nullsAreSortedHigh();
    }

    @Override
    public boolean othersDeletesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException {
        return this.conn.storesLowerCaseTableName();
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
        return this.conn.storesLowerCaseTableName();
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException {
        return !this.conn.storesLowerCaseTableName();
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
        return !this.conn.storesLowerCaseTableName();
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92FullSQL() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        return databaseTermValue() == DatabaseTerm.CATALOG;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        return databaseTermValue() == DatabaseTerm.CATALOG;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
        return databaseTermValue() == DatabaseTerm.CATALOG;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        return databaseTermValue() == DatabaseTerm.CATALOG;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        return databaseTermValue() == DatabaseTerm.CATALOG;
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsConvert() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) throws SQLException {
        return MysqlType.supportsConvert(fromType, toType);
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        if (!this.conn.getPropertySet().getBooleanProperty(PropertyKey.overrideSupportsIntegrityEnhancementFacility).getValue()) {
            return false;
        }
        return true;
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException {
        return !this.conn.lowerCaseTableNames();
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
        return !this.conn.lowerCaseTableNames();
    }

    @Override
    public boolean supportsMultipleOpenResults() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMultipleResultSets() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMultipleTransactions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsNamedParameters() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsPositionedDelete() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() throws SQLException {
        return false;
    }

    // @Override
    // public boolean supportsRefCursors() throws SQLException;

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
        if ((type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE)
                && (concurrency == ResultSet.CONCUR_READ_ONLY || concurrency == ResultSet.CONCUR_UPDATABLE)) {
            return true;
        } else if (type == ResultSet.TYPE_SCROLL_SENSITIVE) {
            return false;
        }
        throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.20"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                getExceptionInterceptor());
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) throws SQLException {
        return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public boolean supportsResultSetType(int type) throws SQLException {
        return type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE;
    }

    @Override
    public boolean supportsSavepoints() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException {
        return databaseTermValue() == DatabaseTerm.SCHEMA;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        return databaseTermValue() == DatabaseTerm.SCHEMA;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        return databaseTermValue() == DatabaseTerm.SCHEMA;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        return databaseTermValue() == DatabaseTerm.SCHEMA;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        return databaseTermValue() == DatabaseTerm.SCHEMA;
    }

    @Override
    public boolean supportsSelectForUpdate() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsStatementPooling() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsStoredProcedures() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsTableCorrelationNames() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        switch (level) {
            case Connection.TRANSACTION_READ_COMMITTED:
            case Connection.TRANSACTION_READ_UNCOMMITTED:
            case Connection.TRANSACTION_REPEATABLE_READ:
            case Connection.TRANSACTION_SERIALIZABLE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean supportsTransactions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        return true;
    }

    @Override
    public boolean updatesAreDetected(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() throws SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFiles() throws SQLException {
        return false;
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> iface) throws SQLException {
        try {
            // This works for classes that aren't actually wrapping anything.
            return iface.cast(this);
        } catch (ClassCastException cce) {
            throw SQLError.createSQLException(Messages.getString("Common.UnableToUnwrap", new Object[] { iface.toString() }),
                    MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT, this.conn.getExceptionInterceptor());
        }
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        // This works for classes that aren't actually wrapping anything.
        return iface.isInstance(this);
    }

}