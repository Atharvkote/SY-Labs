/*
 * Copyright (c) 2025, Oracle and/or its affiliates.
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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.mysql.cj.Messages;
import com.mysql.cj.MysqlType;
import com.mysql.cj.ServerVersion;
import com.mysql.cj.conf.PropertyDefinitions.DatabaseTerm;
import com.mysql.cj.exceptions.MysqlErrorNumbers;
import com.mysql.cj.jdbc.exceptions.SQLError;
import com.mysql.cj.jdbc.result.ResultSetFactory;
import com.mysql.cj.util.StringUtils;

/**
 * DatabaseMetaData implementation that uses INFORMATION_SCHEMA
 */
public class DatabaseMetaDataInformationSchema extends DatabaseMetaData {

    protected DatabaseMetaDataInformationSchema(JdbcConnection connToSet, String databaseToSet, ResultSetFactory resultSetFactory) {
        super(connToSet, databaseToSet, resultSetFactory);
    }

    private ResultSet executeMetadataQuery(PreparedStatement pStmt) throws SQLException {
        ResultSet rs = pStmt.executeQuery();
        ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).setOwningStatement(null);
        return rs;
    }

    private StringBuilder appendOptionalRefContraintsJoin(StringBuilder query) {
        query.append(" JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS R ON (R.CONSTRAINT_NAME = B.CONSTRAINT_NAME");
        query.append(" AND R.TABLE_NAME = B.TABLE_NAME AND R.CONSTRAINT_SCHEMA = B.TABLE_SCHEMA)");
        return query;
    }

    private StringBuilder appendUpdateRuleClause(StringBuilder query) {
        query.append(" CASE WHEN R.UPDATE_RULE = 'CASCADE' THEN ").append(importedKeyCascade);
        query.append(" WHEN R.UPDATE_RULE = 'SET NULL' THEN ").append(importedKeySetNull);
        query.append(" WHEN R.UPDATE_RULE = 'SET DEFAULT' THEN ").append(importedKeySetDefault);
        query.append(" WHEN R.UPDATE_RULE = 'RESTRICT' THEN ").append(importedKeyRestrict);
        query.append(" WHEN R.UPDATE_RULE = 'NO ACTION' THEN ").append(importedKeyRestrict);
        query.append(" ELSE ").append(importedKeyRestrict).append(" END");
        return query;
    }

    private StringBuilder appendDeleteRuleClause(StringBuilder query) {
        query.append(" CASE WHEN R.DELETE_RULE = 'CASCADE' THEN ").append(importedKeyCascade);
        query.append(" WHEN R.DELETE_RULE = 'SET NULL' THEN ").append(importedKeySetNull);
        query.append(" WHEN R.DELETE_RULE = 'SET DEFAULT' THEN ").append(importedKeySetDefault);
        query.append(" WHEN R.DELETE_RULE = 'RESTRICT' THEN ").append(importedKeyRestrict);
        query.append(" WHEN R.DELETE_RULE = 'NO ACTION' THEN ").append(importedKeyRestrict);
        query.append(" ELSE ").append(importedKeyRestrict).append(" END");
        return query;
    }

    private StringBuilder appendDataTypeClause(StringBuilder query, String fullMysqlTypeColumn) {
        query.append(" CASE");
        for (MysqlType mysqlType : MysqlType.values()) {
            query.append(" WHEN UPPER(DATA_TYPE) = '").append(mysqlType.getName()).append("' THEN");
            switch (mysqlType) {
                case TINYINT:
                case TINYINT_UNSIGNED:
                    if (tinyInt1IsBitValue()) {
                        query.append(" IF(LOCATE('ZEROFILL', UPPER(").append(fullMysqlTypeColumn).append(")) = 0");
                        query.append(" AND LOCATE('UNSIGNED', UPPER(").append(fullMysqlTypeColumn).append(")) = 0");
                        query.append(" AND LOCATE('(1)', ").append(fullMysqlTypeColumn).append(") != 0,");
                        query.append(" ").append(transformedBitIsBooleanValue() ? "16" : "-7").append(", -6)");
                    } else {
                        query.append(" ").append(mysqlType.getJdbcType());
                    }
                    break;
                case YEAR:
                    query.append(" ").append(yearIsDateTypeValue() ? mysqlType.getJdbcType() : Types.SMALLINT);
                    break;
                default:
                    query.append(" ").append(mysqlType.getJdbcType());
            }
        }
        query.append(" WHEN UPPER(DATA_TYPE) = 'POINT' THEN -2");
        query.append(" WHEN UPPER(DATA_TYPE) = 'LINESTRING' THEN -2");
        query.append(" WHEN UPPER(DATA_TYPE) = 'POLYGON' THEN -2");
        query.append(" WHEN UPPER(DATA_TYPE) = 'MULTIPOINT' THEN -2");
        query.append(" WHEN UPPER(DATA_TYPE) = 'MULTILINESTRING' THEN -2");
        query.append(" WHEN UPPER(DATA_TYPE) = 'MULTIPOLYGON' THEN -2");
        query.append(" WHEN UPPER(DATA_TYPE) = 'GEOMETRYCOLLECTION' THEN -2");
        query.append(" WHEN UPPER(DATA_TYPE) = 'GEOMCOLLECTION' THEN -2");
        query.append(" ELSE 1111 END");
        return query;
    }

    private StringBuilder appendTypeNameClause(StringBuilder query, String fullMysqlTypeColumn) {
        query.append(" UPPER(CASE");
        // BIT vs TINYINT
        if (tinyInt1IsBitValue()) {
            query.append(" WHEN UPPER(DATA_TYPE) = 'TINYINT' THEN CASE");
            query.append(" WHEN LOCATE('ZEROFILL', UPPER(").append(fullMysqlTypeColumn).append(")) = 0");
            query.append(" AND LOCATE('UNSIGNED', UPPER(").append(fullMysqlTypeColumn).append(")) = 0");
            query.append(" AND LOCATE('(1)', ").append(fullMysqlTypeColumn).append(") != 0");
            query.append(" THEN ").append(transformedBitIsBooleanValue() ? "'BOOLEAN'" : "'BIT'");
            query.append(" WHEN LOCATE('UNSIGNED', UPPER(").append(fullMysqlTypeColumn).append(")) != 0");
            query.append(" AND LOCATE('UNSIGNED', UPPER(DATA_TYPE)) = 0 THEN 'TINYINT UNSIGNED'");
            query.append(" ELSE DATA_TYPE END");
        }
        // Unsigned
        query.append(" WHEN LOCATE('UNSIGNED', UPPER(").append(fullMysqlTypeColumn).append(")) != 0 AND LOCATE('UNSIGNED', UPPER(DATA_TYPE)) = 0");
        query.append(" AND LOCATE('SET', UPPER(DATA_TYPE)) <> 1 AND LOCATE('ENUM', UPPER(DATA_TYPE)) <> 1 THEN CONCAT(DATA_TYPE, ' UNSIGNED')");
        // Spatial data types
        query.append(" WHEN UPPER(DATA_TYPE) = 'POINT' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE) = 'LINESTRING' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE) = 'POLYGON' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE) = 'MULTIPOINT' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE) = 'MULTILINESTRING' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE) = 'MULTIPOLYGON' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE) = 'GEOMETRYCOLLECTION' THEN 'GEOMETRY'");
        query.append(" WHEN UPPER(DATA_TYPE) = 'GEOMCOLLECTION' THEN 'GEOMETRY'");
        // Else
        query.append(" ELSE UPPER(DATA_TYPE) END)");
        return query;
    }

    private StringBuilder appendColumnSizeClause(StringBuilder query) {
        final boolean supportsFractSeconds = getJdbcConnection().getServerVersion().meetsMinimum(ServerVersion.parseVersion("5.6.4"));
        query.append(" UPPER(CASE");
        // Temporal types
        query.append(" WHEN UPPER(DATA_TYPE) = 'YEAR' THEN 4");
        query.append(" WHEN UPPER(DATA_TYPE) = 'DATE' THEN 10"); // '1000-01-01' to '9999-12-31'
        if (supportsFractSeconds) {
            query.append(" WHEN UPPER(DATA_TYPE) = 'DATETIME'"); // '1000-01-01 00:00:00.000000' to '9999-12-31 23:59:59.999999'
            query.append(" OR UPPER(DATA_TYPE) = 'TIMESTAMP'"); // '1970-01-01 00:00:01.000000' UTC to '2038-01-19 03:14:07.999999' UTC
            query.append(" THEN 19 + IF(DATETIME_PRECISION > 0, DATETIME_PRECISION + 1, DATETIME_PRECISION)");
            query.append(" WHEN UPPER(DATA_TYPE) = 'TIME'"); // '-838:59:59.000000' to '838:59:59.000000'
            query.append(" THEN 8 + IF(DATETIME_PRECISION > 0, DATETIME_PRECISION + 1, DATETIME_PRECISION)");
        } else {
            query.append(" WHEN UPPER(DATA_TYPE) = 'DATETIME' OR"); // '1000-01-01 00:00:00.000000' to '9999-12-31 23:59:59.999999'
            query.append(" UPPER(DATA_TYPE) = 'TIMESTAMP'"); // '1970-01-01 00:00:01.000000' UTC to '2038-01-19 03:14:07.999999' UTC
            query.append(" THEN 19");
            query.append(" WHEN UPPER(DATA_TYPE) = 'TIME' THEN 8"); // '-838:59:59.000000' to '838:59:59.000000'
        }
        // BIT vs TINYINT
        if (tinyInt1IsBitValue() && !transformedBitIsBooleanValue()) {
            query.append(" WHEN UPPER(DATA_TYPE) = 'TINYINT' AND LOCATE('ZEROFILL', UPPER(COLUMN_TYPE)) = 0");
            query.append(" AND LOCATE('UNSIGNED', UPPER(COLUMN_TYPE)) = 0 AND LOCATE('(1)', COLUMN_TYPE) != 0 THEN 1");
        }
        // Workaround for Bug#69042 (16712664), "MEDIUMINT PRECISION/TYPE INCORRECT IN INFORMATION_SCHEMA.COLUMNS".
        // I_S bug returns NUMERIC_PRECISION=7 for MEDIUMINT UNSIGNED when it must be 8.
        query.append(" WHEN UPPER(DATA_TYPE) = 'MEDIUMINT' AND LOCATE('UNSIGNED', UPPER(COLUMN_TYPE)) != 0 THEN 8");
        // JSON
        query.append(" WHEN UPPER(DATA_TYPE) = 'JSON' THEN 1073741824"); // JSON columns are limited to the value of the max_allowed_packet (1073741824).
        // Spatial data types
        query.append(" WHEN UPPER(DATA_TYPE) = 'GEOMETRY' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE) = 'POINT' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE) = 'LINESTRING' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE) = 'POLYGON' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE) = 'MULTIPOINT' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE) = 'MULTILINESTRING' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE) = 'MULTIPOLYGON' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE) = 'GEOMETRYCOLLECTION' THEN 65535");
        query.append(" WHEN UPPER(DATA_TYPE) = 'GEOMCOLLECTION' THEN 65535");
        // Else
        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH IS NULL THEN NUMERIC_PRECISION");
        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH > ").append(Integer.MAX_VALUE).append(" THEN ").append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_MAXIMUM_LENGTH END)");
        return query;
    }

    private StringBuilder appendPrecisionClause(StringBuilder query) {
        final boolean supportsFractSeconds = getJdbcConnection().getServerVersion().meetsMinimum(ServerVersion.parseVersion("5.6.4"));
        query.append(" CASE WHEN LCASE(DATA_TYPE) = 'date' THEN 0");
        if (supportsFractSeconds) {
            query.append(" WHEN LCASE(DATA_TYPE) = 'time' OR LCASE(DATA_TYPE) = 'datetime' OR LCASE(DATA_TYPE) = 'timestamp' THEN DATETIME_PRECISION");
        } else {
            query.append(" WHEN LCASE(DATA_TYPE) = 'time' OR LCASE(DATA_TYPE) = 'datetime' OR LCASE(DATA_TYPE) = 'timestamp' THEN 0");
        }
        if (tinyInt1IsBitValue() && !transformedBitIsBooleanValue()) {
            query.append(" WHEN UPPER(DATA_TYPE) = 'TINYINT' AND LOCATE('ZEROFILL', UPPER(DTD_IDENTIFIER)) = 0");
            query.append(" AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('(1)', DTD_IDENTIFIER) != 0 THEN 1");
        }
        // workaround for Bug#69042 (16712664), "MEDIUMINT PRECISION/TYPE INCORRECT IN INFORMATION_SCHEMA.COLUMNS",
        // I_S bug returns NUMERIC_PRECISION=7 for MEDIUMINT UNSIGNED when it must be 8.
        query.append(" WHEN UPPER(DATA_TYPE) = 'MEDIUMINT' AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) != 0 THEN 8");
        query.append(" WHEN UPPER(DATA_TYPE) = 'JSON' THEN 1073741824"); // JSON columns are limited to the value of the max_allowed_packet (1073741824).
        query.append(" ELSE NUMERIC_PRECISION END");
        return query;
    }

    private StringBuilder appendLengthClause(StringBuilder query) {
        final boolean supportsFractSeconds = getJdbcConnection().getServerVersion().meetsMinimum(ServerVersion.parseVersion("5.6.4"));
        query.append(" CASE WHEN LCASE(DATA_TYPE) = 'date' THEN 10");
        if (supportsFractSeconds) {
            query.append(" WHEN LCASE(DATA_TYPE) = 'time' THEN 8 + IF(DATETIME_PRECISION > 0, DATETIME_PRECISION + 1, DATETIME_PRECISION)");
            query.append(" WHEN LCASE(DATA_TYPE) = 'datetime' OR LCASE(DATA_TYPE) = 'timestamp' THEN");
            query.append(" 19 + IF(DATETIME_PRECISION > 0, DATETIME_PRECISION + 1, DATETIME_PRECISION)");
        } else {
            query.append(" WHEN LCASE(DATA_TYPE) = 'time' THEN 8");
            query.append(" WHEN LCASE(DATA_TYPE) = 'datetime' OR LCASE(DATA_TYPE) = 'timestamp' THEN 19");
        }
        if (tinyInt1IsBitValue() && !transformedBitIsBooleanValue()) {
            query.append(" WHEN (UPPER(DATA_TYPE) = 'TINYINT' OR UPPER(DATA_TYPE) = 'TINYINT UNSIGNED')");
            query.append(" AND LOCATE('ZEROFILL', UPPER(DTD_IDENTIFIER)) = 0 AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) = 0");
            query.append(" AND LOCATE('(1)', DTD_IDENTIFIER) != 0 THEN 1");
        }
        // workaround for Bug#69042 (16712664), "MEDIUMINT PRECISION/TYPE INCORRECT IN INFORMATION_SCHEMA.COLUMNS",
        // I_S bug returns NUMERIC_PRECISION=7 for MEDIUMINT UNSIGNED when it must be 8.
        query.append(" WHEN UPPER(DATA_TYPE) = 'MEDIUMINT' AND LOCATE('UNSIGNED', UPPER(DTD_IDENTIFIER)) != 0 THEN 8");
        query.append(" WHEN UPPER(DATA_TYPE) = 'JSON' THEN 1073741824"); // JSON columns are limited to the value of the max_allowed_packet (1073741824).
        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH IS NULL THEN NUMERIC_PRECISION");
        query.append(" WHEN CHARACTER_MAXIMUM_LENGTH > ").append(Integer.MAX_VALUE).append(" THEN ").append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_MAXIMUM_LENGTH END");
        return query;
    }

    private StringBuilder appendDecimalDigitsClause(StringBuilder query) {
        query.append(" UPPER(CASE");
        query.append(" WHEN UPPER(DATA_TYPE) = 'DECIMAL' THEN NUMERIC_SCALE");
        query.append(" WHEN UPPER(DATA_TYPE) = 'FLOAT' OR UPPER(DATA_TYPE) = 'DOUBLE' THEN IF(NUMERIC_SCALE IS NULL, 0, NUMERIC_SCALE)");
        query.append(" ELSE NULL END)");
        return query;
    }

    /*
     * API methods.
     */

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        String dbFilter = chooseDatabaseTerm(catalog, schema);
        final String tableFilter = normalizeIdentifierQuoting(table);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(" ").append(bestRowSession).append(" AS SCOPE,");                                                      // SCOPE
        query.append(" COLUMN_NAME,");                                                                                      // COLUMN_NAME
        appendDataTypeClause(query, "COLUMN_TYPE").append(" AS DATA_TYPE,");                                                // DATA_TYPE
        appendTypeNameClause(query, "COLUMN_TYPE").append(" AS TYPE_NAME,");                                                // TYPE_NAME
        appendColumnSizeClause(query).append(" AS COLUMN_SIZE,");                                                           // COLUMN_SIZE
        query.append(" ").append(MAX_BUFFER_SIZE).append(" AS BUFFER_LENGTH,");                                             // BUFFER_LENGTH
        appendDecimalDigitsClause(query).append(" AS DECIMAL_DIGITS,");                                                     // DECIMAL_DIGITS
        query.append(" ").append(bestRowNotPseudo).append(" AS PSEUDO_COLUMN");                                             // PSEUDO_COLUMN
        query.append(" FROM INFORMATION_SCHEMA.COLUMNS");

        StringBuilder condition = new StringBuilder();
        if (dbFilter != null) {
            condition.append(" TABLE_SCHEMA = ?");
        }
        if (condition.length() > 0) {
            condition.append(" AND");
        }
        condition.append(" TABLE_NAME = ?");
        condition.append(" AND COLUMN_KEY = 'PRI'");

        query.append(" WHERE").append(condition);

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            pStmt.setString(nextIdx++, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createBestRowIdentifierFields());
            return rs;
        }
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        Statement stmt = getJdbcConnection().getMetaDataSafeStatement();

        StringBuilder query = new StringBuilder("SELECT SCHEMA_NAME AS TABLE_CAT FROM INFORMATION_SCHEMA.SCHEMATA");        // TABLE_CAT
        if (databaseTermValue() != DatabaseTerm.CATALOG) {
            query.append(" WHERE FALSE");
        }
        ResultSet rs = stmt.executeQuery(query.toString());

        ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createCatalogsFields());
        return rs;
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException {
        final String dbFromTerm = chooseDatabaseTerm(catalog, schema);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String tableFilter = normalizeIdentifierQuoting(table);
        final String columnNameFilter = normalizeIdentifierQuoting(columnNamePattern);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(chooseBasedOnDatabaseTerm(() -> " TABLE_SCHEMA, NULL,", () -> " TABLE_CATALOG, TABLE_SCHEMA,"));       // TABLE_CAT, TABLE_SCHEM
        query.append(" TABLE_NAME,");                                                                                       // TABLE_NAME
        query.append(" COLUMN_NAME,");                                                                                      // COLUMN_NAME
        query.append(" NULL AS GRANTOR,");                                                                                  // GRANTOR
        query.append(" GRANTEE,");                                                                                          // GRANTEE
        query.append(" PRIVILEGE_TYPE AS PRIVILEGE,");                                                                      // PRIVILEGE
        query.append(" IS_GRANTABLE");                                                                                      // IS_GRANTABLE
        query.append(" FROM INFORMATION_SCHEMA.COLUMN_PRIVILEGES WHERE");
        if (dbFilter != null) {
            query.append(" TABLE_SCHEMA = ? AND");
        }
        query.append(" TABLE_NAME = ?");
        if (columnNamePattern != null) {
            query.append(" AND COLUMN_NAME LIKE ?");
        }
        query.append(" ORDER BY COLUMN_NAME, PRIVILEGE_TYPE");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            pStmt.setString(nextIdx++, dbFilter);
            pStmt.setString(nextIdx++, tableFilter);
            if (columnNameFilter != null) {
                pStmt.setString(nextIdx, columnNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createColumnPrivilegesFields());
            return rs;
        }
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        final String dbFilter = normalizeIdentifierQuoting(chooseDatabaseTerm(catalog, schemaPattern));
        final String tableNameFilter = normalizeIdentifierQuoting(tableNamePattern);
        final String columnNameFilter = normalizeIdentifierQuoting(columnNamePattern);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(chooseBasedOnDatabaseTerm(() -> " TABLE_SCHEMA, NULL,", () -> " TABLE_CATALOG, TABLE_SCHEMA,"));       // TABLE_CAT, TABLE_SCHEM
        query.append(" TABLE_NAME,");                                                                                       // TABLE_NAME
        query.append(" COLUMN_NAME,");                                                                                      // COLUMN_NAME
        appendDataTypeClause(query, "COLUMN_TYPE").append(" AS DATA_TYPE,");                                                // DATA_TYPE
        appendTypeNameClause(query, "COLUMN_TYPE").append(" AS TYPE_NAME,");                                                // TYPE_NAME
        appendColumnSizeClause(query).append(" AS COLUMN_SIZE,");                                                           // COLUMN_SIZE
        query.append(" ").append(MAX_BUFFER_SIZE).append(" AS BUFFER_LENGTH,");                                             // BUFFER_LENGTH
        appendDecimalDigitsClause(query).append(" AS DECIMAL_DIGITS,");                                                     // DECIMAL_DIGITS
        query.append(" 10 AS NUM_PREC_RADIX,");                                                                             // NUM_PREC_RADIX
        query.append(" CASE WHEN IS_NULLABLE = 'NO' THEN ").append(columnNoNulls);
        query.append(" ELSE CASE WHEN IS_NULLABLE = 'YES' THEN ").append(columnNullable);
        query.append(" ELSE ").append(columnNullableUnknown).append(" END END AS NULLABLE,");                               // NULLABLE
        query.append(" COLUMN_COMMENT AS REMARKS,");                                                                        // REMARKS
        query.append(" COLUMN_DEFAULT AS COLUMN_DEF,");                                                                     // COLUMN_DEF
        query.append(" 0 AS SQL_DATA_TYPE,");                                                                               // SQL_DATA_TYPE
        query.append(" 0 AS SQL_DATETIME_SUB,");                                                                            // SQL_DATETIME_SUB
        query.append(" CASE WHEN CHARACTER_OCTET_LENGTH > ").append(Integer.MAX_VALUE).append(" THEN ").append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_OCTET_LENGTH END AS CHAR_OCTET_LENGTH,");                                             // CHAR_OCTET_LENGTH
        query.append(" ORDINAL_POSITION,");                                                                                 // ORDINAL_POSITION
        query.append(" IS_NULLABLE,");                                                                                      // IS_NULLABLE
        query.append(" NULL AS SCOPE_CATALOG,");                                                                            // SCOPE_CATALOG
        query.append(" NULL AS SCOPE_SCHEMA,");                                                                             // SCOPE_SCHEMA
        query.append(" NULL AS SCOPE_TABLE,");                                                                              // SCOPE_TABLE
        query.append(" NULL AS SOURCE_DATA_TYPE,");                                                                         // SOURCE_DATA_TYPE
        query.append(" IF (EXTRA LIKE '%auto_increment%','YES','NO') AS IS_AUTOINCREMENT,");                                // IS_AUTOINCREMENT
        query.append(" IF (EXTRA LIKE '%GENERATED%','YES','NO') AS IS_GENERATEDCOLUMN");                                    // IS_GENERATEDCOLUMN
        query.append(" FROM INFORMATION_SCHEMA.COLUMNS");

        StringBuilder condition = new StringBuilder();
        if (dbFilter != null) {
            condition.append(chooseBasedOnDatabaseTerm(() -> " TABLE_SCHEMA = ?",
                    () -> StringUtils.hasWildcards(dbFilter) ? " TABLE_SCHEMA LIKE ?" : " TABLE_SCHEMA = ?"));
        }
        if (tableNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(StringUtils.hasWildcards(tableNameFilter) ? " TABLE_NAME LIKE ?" : " TABLE_NAME = ?");
        }
        if (columnNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(StringUtils.hasWildcards(columnNameFilter) ? " COLUMN_NAME LIKE ?" : " COLUMN_NAME = ?");
        }

        if (condition.length() > 0) {
            query.append(" WHERE");
            query.append(condition);
        }
        query.append(" ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            if (tableNameFilter != null) {
                pStmt.setString(nextIdx++, tableNameFilter);
            }
            if (columnNameFilter != null) {
                pStmt.setString(nextIdx, columnNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createColumnsFields());
            return rs;
        }
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog, final String foreignSchema,
            final String foreignTable) throws SQLException {
        if (parentTable == null || foreignTable == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String parentDbFromTerm = chooseDatabaseTerm(parentCatalog, parentSchema);
        final String parentDbFilter = normalizeIdentifierQuoting(parentDbFromTerm);
        final String parentTableFilter = normalizeIdentifierQuoting(parentTable);
        final String foreignDbFilter = chooseDatabaseTerm(foreignCatalog, foreignSchema);
        final String foreignTableFilter = normalizeIdentifierQuoting(foreignTable);

        StringBuilder query = new StringBuilder("SELECT DISTINCT");
        query.append(chooseBasedOnDatabaseTerm(() -> " A.REFERENCED_TABLE_SCHEMA AS PKTABLE_CAT, NULL AS PKTABLE_SCHEM,",
                () -> " A.CONSTRAINT_CATALOG AS PKTABLE_CAT, A.REFERENCED_TABLE_SCHEMA AS PKTABLE_SCHEM,"));                // PKTABLE_CAT, PKTABLE_SCHEM
        query.append(" A.REFERENCED_TABLE_NAME AS PKTABLE_NAME,");                                                          // PKTABLE_NAME
        query.append(" A.REFERENCED_COLUMN_NAME AS PKCOLUMN_NAME,");                                                        // PKCOLUMN_NAME
        query.append(chooseBasedOnDatabaseTerm(() -> " A.TABLE_SCHEMA AS FKTABLE_CAT, NULL AS FKTABLE_SCHEM,",
                () -> " A.TABLE_CATALOG AS FKTABLE_CAT, A.TABLE_SCHEMA AS FKTABLE_SCHEM,"));                                // FKTABLE_CAT, FKTABLE_SCHEM
        query.append(" A.TABLE_NAME AS FKTABLE_NAME,");                                                                     // FKTABLE_NAME
        query.append(" A.COLUMN_NAME AS FKCOLUMN_NAME,");                                                                   // FKCOLUMN_NAME
        query.append(" A.ORDINAL_POSITION AS KEY_SEQ,");                                                                    // KEY_SEQ
        appendUpdateRuleClause(query).append(" AS UPDATE_RULE,");                                                           // UPDATE_RULE
        appendDeleteRuleClause(query).append(" AS DELETE_RULE,");                                                           // DELETE_RULE
        query.append(" A.CONSTRAINT_NAME AS FK_NAME,");                                                                     // FK_NAME
        query.append(" TC.CONSTRAINT_NAME AS PK_NAME,");                                                                    // PK_NAME
        query.append(" ").append(importedKeyNotDeferrable).append(" AS DEFERRABILITY");                                     // DEFERRABILITY
        query.append(" FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE A JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS B");
        query.append(" USING (TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_NAME)");
        appendOptionalRefContraintsJoin(query);
        query.append(" LEFT JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC ON (A.REFERENCED_TABLE_SCHEMA = TC.TABLE_SCHEMA");
        query.append(" AND A.REFERENCED_TABLE_NAME = TC.TABLE_NAME AND TC.CONSTRAINT_TYPE IN ('UNIQUE', 'PRIMARY KEY'))");
        query.append(" WHERE B.CONSTRAINT_TYPE = 'FOREIGN KEY'");
        if (parentDbFilter != null) {
            query.append(" AND A.REFERENCED_TABLE_SCHEMA = ?");
        }
        query.append(" AND A.REFERENCED_TABLE_NAME = ?");
        if (foreignDbFilter != null) {
            query.append(" AND A.TABLE_SCHEMA = ?");
        }
        query.append(" AND A.TABLE_NAME = ?");
        query.append(" ORDER BY FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME, KEY_SEQ");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (parentDbFilter != null) {
                pStmt.setString(nextIdx++, parentDbFilter);
            }
            pStmt.setString(nextIdx++, parentTableFilter);
            if (foreignDbFilter != null) {
                pStmt.setString(nextIdx++, foreignDbFilter);
            }
            pStmt.setString(nextIdx, foreignTableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createForeignKeysFields());
            return rs;
        }
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFromTerm = chooseDatabaseTerm(catalog, schema);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String tableFilter = normalizeIdentifierQuoting(table);

        StringBuilder query = new StringBuilder("SELECT DISTINCT");
        query.append(chooseBasedOnDatabaseTerm(() -> " A.REFERENCED_TABLE_SCHEMA AS PKTABLE_CAT, NULL AS PKTABLE_SCHEM,",
                () -> " A.CONSTRAINT_CATALOG AS PKTABLE_CAT, A.REFERENCED_TABLE_SCHEMA AS PKTABLE_SCHEM,"));                // PKTABLE_CAT, PKTABLE_SCHEM
        query.append(" A.REFERENCED_TABLE_NAME AS PKTABLE_NAME,");                                                          // PKTABLE_NAME
        query.append(" A.REFERENCED_COLUMN_NAME AS PKCOLUMN_NAME,");                                                        // PKCOLUMN_NAME
        query.append(chooseBasedOnDatabaseTerm(() -> " A.TABLE_SCHEMA AS FKTABLE_CAT, NULL AS FKTABLE_SCHEM,",
                () -> " A.TABLE_CATALOG AS FKTABLE_CAT, A.TABLE_SCHEMA AS FKTABLE_SCHEM,"));                                // FKTABLE_CAT, FKTABLE_SCHEM
        query.append(" A.TABLE_NAME AS FKTABLE_NAME,");                                                                     // FKTABLE_NAME
        query.append(" A.COLUMN_NAME AS FKCOLUMN_NAME,");                                                                   // FKCOLUMN_NAME
        query.append(" A.ORDINAL_POSITION AS KEY_SEQ,");                                                                    // KEY_SEQ
        appendUpdateRuleClause(query).append(" AS UPDATE_RULE,");                                                           // UPDATE_RULE
        appendDeleteRuleClause(query).append(" AS DELETE_RULE,");                                                           // DELETE_RULE
        query.append(" A.CONSTRAINT_NAME AS FK_NAME,");                                                                     // FK_NAME
        query.append(" TC.CONSTRAINT_NAME AS PK_NAME,");                                                                    // PK_NAME
        query.append(" ").append(importedKeyNotDeferrable).append(" AS DEFERRABILITY");                                     // DEFERRABILITY
        query.append(" FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE A JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS B");
        query.append(" USING (TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_NAME)");
        appendOptionalRefContraintsJoin(query);
        query.append(" LEFT JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC ON (A.REFERENCED_TABLE_SCHEMA = TC.TABLE_SCHEMA");
        query.append(" AND A.REFERENCED_TABLE_NAME = TC.TABLE_NAME AND TC.CONSTRAINT_TYPE IN ('UNIQUE', 'PRIMARY KEY'))");
        query.append(" WHERE B.CONSTRAINT_TYPE = 'FOREIGN KEY'");
        if (dbFilter != null) {
            query.append(" AND A.REFERENCED_TABLE_SCHEMA = ?");
        }
        query.append(" AND A.REFERENCED_TABLE_NAME = ?");
        query.append(" ORDER BY FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME, KEY_SEQ");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            pStmt.setString(nextIdx, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createForeignKeysFields());
            return rs;
        }
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException {
        final String dbFromTerm = chooseDatabaseTerm(catalog, schemaPattern);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String functionNameFilter = normalizeIdentifierQuoting(functionNamePattern);
        final String columnNameFilter = normalizeIdentifierQuoting(columnNamePattern);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(chooseBasedOnDatabaseTerm(() -> " SPECIFIC_SCHEMA AS FUNCTION_CAT, NULL AS FUNCTION_SCHEM,",
                () -> " SPECIFIC_CATALOG AS FUNCTION_CAT, SPECIFIC_SCHEMA AS FUNCTION_SCHEM,"));                            // FUNCTION_CAT, FUNCTION_SCHEM
        query.append(" SPECIFIC_NAME AS FUNCTION_NAME,");                                                                   // FUNCTION_NAME
        query.append(" IFNULL(PARAMETER_NAME, '') AS COLUMN_NAME,");                                                        // COLUMN_NAME
        query.append(" CASE WHEN PARAMETER_MODE = 'IN' THEN ").append(functionColumnIn);
        query.append(" WHEN PARAMETER_MODE = 'OUT' THEN ").append(functionColumnOut);
        query.append(" WHEN PARAMETER_MODE = 'INOUT' THEN ").append(functionColumnInOut);
        query.append(" WHEN ORDINAL_POSITION = 0 THEN ").append(functionReturn);
        query.append(" ELSE ").append(functionColumnUnknown).append(" END AS COLUMN_TYPE,");                                // COLUMN_TYPE
        appendDataTypeClause(query, "DTD_IDENTIFIER").append(" AS DATA_TYPE,");                                             // DATA_TYPE
        appendTypeNameClause(query, "DTD_IDENTIFIER").append(" AS TYPE_NAME,");                                             // TYPE_NAME
        appendPrecisionClause(query).append(" AS `PRECISION`,");                                                            // PRECISION
        appendLengthClause(query).append(" AS LENGTH,");                                                                    // LENGTH
        query.append(" NUMERIC_SCALE AS SCALE,");                                                                           // SCALE
        query.append(" 10 AS RADIX,");                                                                                      // RADIX
        query.append(" ").append(functionNullable).append(" AS NULLABLE,");                                                 // NULLABLE
        query.append(" NULL AS REMARKS,");                                                                                  // REMARKS
        query.append(" CASE WHEN CHARACTER_OCTET_LENGTH > ").append(Integer.MAX_VALUE).append(" THEN ").append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_OCTET_LENGTH END AS CHAR_OCTET_LENGTH,");                                             // CHAR_OCTET_LENGTH
        query.append(" ORDINAL_POSITION,");                                                                                 // ORDINAL_POSITION
        query.append(" 'YES' AS IS_NULLABLE,");                                                                             // IS_NULLABLE
        query.append(" SPECIFIC_NAME");                                                                                     // SPECIFIC_NAME
        query.append(" FROM INFORMATION_SCHEMA.PARAMETERS WHERE");

        StringBuilder condition = new StringBuilder();
        if (dbFilter != null) {
            condition.append(chooseBasedOnDatabaseTerm(() -> " SPECIFIC_SCHEMA = ?", () -> " SPECIFIC_SCHEMA LIKE ?"));
        }
        if (functionNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" SPECIFIC_NAME LIKE ?");
        }
        if (columnNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" (PARAMETER_NAME LIKE ? OR PARAMETER_NAME IS NULL)");
        }
        if (condition.length() > 0) {
            condition.append(" AND");
        }
        condition.append(" ROUTINE_TYPE = 'FUNCTION'");

        query.append(condition);
        query.append(" ORDER BY SPECIFIC_SCHEMA, SPECIFIC_NAME, ORDINAL_POSITION");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            if (functionNameFilter != null) {
                pStmt.setString(nextIdx++, functionNameFilter);
            }
            if (columnNameFilter != null) {
                pStmt.setString(nextIdx, columnNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createFunctionColumnsFields());
            return rs;
        }
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException {
        final String dbFromTerm = chooseDatabaseTerm(catalog, schemaPattern);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String functionNameFilter = normalizeIdentifierQuoting(functionNamePattern);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(chooseBasedOnDatabaseTerm(() -> " ROUTINE_SCHEMA AS FUNCTION_CAT, NULL AS FUNCTION_SCHEM,",
                () -> " ROUTINE_CATALOG AS FUNCTION_CAT, ROUTINE_SCHEMA AS FUNCTION_SCHEM,"));                              // FUNCTION_CAT, FUNCTION_SCHEM
        query.append(" ROUTINE_NAME AS FUNCTION_NAME,");                                                                    // FUNCTION_NAME
        query.append(" ROUTINE_COMMENT AS REMARKS,");                                                                       // REMARKS
        query.append(" ").append(functionNoTable).append(" AS FUNCTION_TYPE,");                                             // FUNCTION_TYPE
        query.append(" ROUTINE_NAME AS SPECIFIC_NAME");                                                                     // SPECIFIC_NAME
        query.append(" FROM INFORMATION_SCHEMA.ROUTINES");
        query.append(" WHERE ROUTINE_TYPE LIKE 'FUNCTION'");
        if (dbFilter != null) {
            query.append(chooseBasedOnDatabaseTerm(() -> " AND ROUTINE_SCHEMA = ?", () -> " AND ROUTINE_SCHEMA LIKE ?"));
        }
        if (functionNameFilter != null) {
            query.append(" AND ROUTINE_NAME LIKE ?");
        }
        query.append(" ORDER BY FUNCTION_CAT, FUNCTION_SCHEM, FUNCTION_NAME, SPECIFIC_NAME");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            if (functionNameFilter != null) {
                pStmt.setString(nextIdx, functionNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createFunctionsFields());
            return rs;
        }
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFromTerm = chooseDatabaseTerm(catalog, schema);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String tableFilter = normalizeIdentifierQuoting(table);

        StringBuilder query = new StringBuilder("SELECT DISTINCT");
        query.append(chooseBasedOnDatabaseTerm(() -> " A.REFERENCED_TABLE_SCHEMA AS PKTABLE_CAT, NULL AS PKTABLE_SCHEM,",
                () -> " A.CONSTRAINT_CATALOG AS PKTABLE_CAT, A.REFERENCED_TABLE_SCHEMA AS PKTABLE_SCHEM,"));                // PKTABLE_CAT, PKTABLE_SCHEM
        query.append(" A.REFERENCED_TABLE_NAME AS PKTABLE_NAME,");                                                          // PKTABLE_NAME
        query.append(" A.REFERENCED_COLUMN_NAME AS PKCOLUMN_NAME,");                                                        // PKCOLUMN_NAME
        query.append(chooseBasedOnDatabaseTerm(() -> " A.TABLE_SCHEMA AS FKTABLE_CAT, NULL AS FKTABLE_SCHEM,",
                () -> " A.TABLE_CATALOG AS FKTABLE_CAT, A.TABLE_SCHEMA AS FKTABLE_SCHEM,"));                                // FKTABLE_CAT, FKTABLE_SCHEM
        query.append(" A.TABLE_NAME AS FKTABLE_NAME,");                                                                     // FKTABLE_NAME
        query.append(" A.COLUMN_NAME AS FKCOLUMN_NAME,");                                                                   // FKCOLUMN_NAME
        query.append(" A.ORDINAL_POSITION AS KEY_SEQ,");                                                                    // KEY_SEQ
        appendUpdateRuleClause(query).append(" AS UPDATE_RULE,");                                                           // UPDATE_RULE
        appendDeleteRuleClause(query).append(" AS DELETE_RULE,");                                                           // DELETE_RULE
        query.append(" A.CONSTRAINT_NAME AS FK_NAME,");                                                                     // FK_NAME
        query.append(" R.UNIQUE_CONSTRAINT_NAME AS PK_NAME,");                                                              // PK_NAME
        query.append(" ").append(importedKeyNotDeferrable).append(" AS DEFERRABILITY");                                     // DEFERRABILITY
        query.append(" FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE A");
        query.append(" JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS B USING (CONSTRAINT_SCHEMA, CONSTRAINT_NAME, TABLE_NAME)");
        appendOptionalRefContraintsJoin(query);
        query.append("WHERE B.CONSTRAINT_TYPE = 'FOREIGN KEY'");
        if (dbFilter != null) {
            query.append(" AND A.TABLE_SCHEMA = ?");
        }
        query.append(" AND A.TABLE_NAME = ?");
        query.append(" AND A.REFERENCED_TABLE_SCHEMA IS NOT NULL");
        query.append(" ORDER BY A.REFERENCED_TABLE_SCHEMA, A.REFERENCED_TABLE_NAME, A.ORDINAL_POSITION");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            pStmt.setString(nextIdx, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createForeignKeysFields());
            return rs;
        }
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFromTerm = chooseDatabaseTerm(catalog, schema);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String tableFilter = normalizeIdentifierQuoting(table);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(chooseBasedOnDatabaseTerm(() -> " TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM,",
                () -> " TABLE_CATALOG AS TABLE_CAT, TABLE_SCHEMA AS TABLE_SCHEM,"));                                        // TABLE_CAT, TABLE_SCHEM
        query.append(" TABLE_NAME,");                                                                                       // TABLE_NAME
        query.append(" NON_UNIQUE,");                                                                                       // NON_UNIQUE
        query.append(" NULL AS INDEX_QUALIFIER,");                                                                          // INDEX_QUALIFIER
        query.append(" INDEX_NAME,");                                                                                       // INDEX_NAME
        query.append(" ").append(tableIndexOther).append(" AS TYPE,");                                                      // TYPE
        query.append(" SEQ_IN_INDEX AS ORDINAL_POSITION,");                                                                 // ORDINAL_POSITION
        query.append(" COLUMN_NAME,");                                                                                      // COLUMN_NAME
        query.append(" COLLATION AS ASC_OR_DESC,");                                                                         // ASC_OR_DESC
        query.append(" CARDINALITY,");                                                                                      // CARDINALITY
        query.append(" 0 AS PAGES,");                                                                                       // PAGES
        query.append(" NULL AS FILTER_CONDITION");                                                                          // FILTER_CONDITION
        query.append(" FROM INFORMATION_SCHEMA.STATISTICS WHERE");
        if (dbFilter != null) {
            query.append(" TABLE_SCHEMA = ? AND");
        }
        query.append(" TABLE_NAME = ?");
        if (unique) {
            query.append(" AND NON_UNIQUE = 0");
        }
        query.append(" ORDER BY NON_UNIQUE, INDEX_NAME, SEQ_IN_INDEX");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            pStmt.setString(nextIdx, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createIndexInfoFields());
            return rs;
        }
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFromTerm = chooseDatabaseTerm(catalog, schema);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String tableFilter = normalizeIdentifierQuoting(table);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(chooseBasedOnDatabaseTerm(() -> " TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM,",
                () -> " TABLE_CATALOG AS TABLE_CAT, TABLE_SCHEMA AS TABLE_SCHEM,"));                                        // TABLE_CAT, TABLE_SCHEM
        query.append(" TABLE_NAME,");                                                                                       // TABLE_NAME
        query.append(" COLUMN_NAME,");                                                                                      // COLUMN_NAME
        query.append(" SEQ_IN_INDEX AS KEY_SEQ,");                                                                          // KEY_SEQ
        query.append(" 'PRIMARY' AS PK_NAME");                                                                              // PK_NAME
        query.append(" FROM INFORMATION_SCHEMA.STATISTICS WHERE");
        if (dbFilter != null) {
            query.append(" TABLE_SCHEMA = ? AND");
        }
        query.append(" TABLE_NAME = ?");
        query.append(" AND INDEX_NAME = 'PRIMARY' ORDER BY TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, SEQ_IN_INDEX");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            pStmt.setString(nextIdx, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createPrimaryKeysFields());
            return rs;
        }
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException {
        final String dbFromTerm = chooseDatabaseTerm(catalog, schemaPattern);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String procedureNameFilter = normalizeIdentifierQuoting(procedureNamePattern);
        final String columnNameFilter = normalizeIdentifierQuoting(columnNamePattern);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(chooseBasedOnDatabaseTerm(() -> " SPECIFIC_SCHEMA AS PROCEDURE_CAT, NULL AS PROCEDURE_SCHEM,",
                () -> " SPECIFIC_CATALOG AS PROCEDURE_CAT, SPECIFIC_SCHEMA AS PROCEDURE_SCHEM,"));                          // PROCEDURE_CAT, PROCEDURE_SCHEM
        query.append(" SPECIFIC_NAME AS PROCEDURE_NAME,");                                                                  // PROCEDURE_NAME
        query.append(" IFNULL(PARAMETER_NAME, '') AS COLUMN_NAME,");                                                        // COLUMN_NAME
        query.append(" CASE WHEN PARAMETER_MODE = 'IN' THEN ").append(procedureColumnIn);
        query.append(" WHEN PARAMETER_MODE = 'OUT' THEN ").append(procedureColumnOut);
        query.append(" WHEN PARAMETER_MODE = 'INOUT' THEN ").append(procedureColumnInOut);
        query.append(" WHEN ORDINAL_POSITION = 0 THEN ").append(procedureColumnReturn);
        query.append(" ELSE ").append(procedureColumnUnknown).append(" END AS COLUMN_TYPE,");                               // COLUMN_TYPE
        appendDataTypeClause(query, "DTD_IDENTIFIER").append(" AS DATA_TYPE,");                                             // DATA_TYPE
        appendTypeNameClause(query, "DTD_IDENTIFIER").append(" AS TYPE_NAME,");                                             // TYPE_NAME
        appendPrecisionClause(query).append(" AS `PRECISION`,");                                                            // PRECISION
        appendLengthClause(query).append(" AS LENGTH,");                                                                    // LENGTH
        query.append(" NUMERIC_SCALE AS SCALE,");                                                                           // SCALE
        query.append(" 10 AS RADIX,");                                                                                      // RADIX
        query.append(" ").append(procedureNullable).append(" AS NULLABLE,");                                                // NULLABLE
        query.append(" NULL AS REMARKS,");                                                                                  // REMARKS
        query.append(" NULL AS COLUMN_DEF,");                                                                               // COLUMN_DEF
        query.append(" NULL AS SQL_DATA_TYPE,");                                                                            // SQL_DATA_TYPE
        query.append(" NULL AS SQL_DATETIME_SUB,");                                                                         // SQL_DATETIME_SUB
        query.append(" CASE WHEN CHARACTER_OCTET_LENGTH > ").append(Integer.MAX_VALUE).append(" THEN ").append(Integer.MAX_VALUE);
        query.append(" ELSE CHARACTER_OCTET_LENGTH END AS CHAR_OCTET_LENGTH,");                                             // CHAR_OCTET_LENGTH
        query.append(" ORDINAL_POSITION,");                                                                                 // ORDINAL_POSITION
        query.append(" 'YES' AS IS_NULLABLE,");                                                                             // IS_NULLABLE
        query.append(" SPECIFIC_NAME");                                                                                     // SPECIFIC_NAME
        query.append(" FROM INFORMATION_SCHEMA.PARAMETERS");

        StringBuilder condition = new StringBuilder();
        if (!getProceduresReturnsFunctionsValue()) {
            condition.append(" ROUTINE_TYPE = 'PROCEDURE'");
        }
        if (dbFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(chooseBasedOnDatabaseTerm(() -> " SPECIFIC_SCHEMA = ?", () -> " SPECIFIC_SCHEMA LIKE ?"));
        }
        if (procedureNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" SPECIFIC_NAME LIKE ?");
        }
        if (columnNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" (PARAMETER_NAME LIKE ? OR PARAMETER_NAME IS NULL)");
        }

        if (condition.length() > 0) {
            query.append(" WHERE");
            query.append(condition);
        }
        query.append(" ORDER BY SPECIFIC_SCHEMA, SPECIFIC_NAME, ROUTINE_TYPE, ORDINAL_POSITION");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            if (procedureNameFilter != null) {
                pStmt.setString(nextIdx++, procedureNameFilter);
            }
            if (columnNameFilter != null) {
                pStmt.setString(nextIdx, columnNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createProcedureColumnsFields());
            return rs;
        }
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        final String dbFromTerm = chooseDatabaseTerm(catalog, schemaPattern);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String procedureNameFilter = normalizeIdentifierQuoting(procedureNamePattern);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(chooseBasedOnDatabaseTerm(() -> " ROUTINE_SCHEMA AS PROCEDURE_CAT, NULL AS PROCEDURE_SCHEM,",
                () -> " ROUTINE_CATALOG AS PROCEDURE_CAT, ROUTINE_SCHEMA AS PROCEDURE_SCHEM,"));                            // PROCEDURE_CAT, PROCEDURE_SCHEM
        query.append(" ROUTINE_NAME AS PROCEDURE_NAME,");                                                                   // PROCEDURE_NAME
        query.append(" NULL AS RESERVED_1,");                                                                               // reserved for future use
        query.append(" NULL AS RESERVED_2,");                                                                               // reserved for future use
        query.append(" NULL AS RESERVED_3,");                                                                               // reserved for future use
        query.append(" ROUTINE_COMMENT AS REMARKS,");                                                                       // REMARKS
        query.append(" CASE WHEN ROUTINE_TYPE = 'PROCEDURE' THEN ").append(procedureNoResult);
        query.append(" WHEN ROUTINE_TYPE = 'FUNCTION' THEN ").append(procedureReturnsResult);
        query.append(" ELSE ").append(procedureResultUnknown).append(" END AS PROCEDURE_TYPE,");                            // PROCEDURE_TYPE
        query.append(" ROUTINE_NAME AS SPECIFIC_NAME");                                                                     // SPECIFIC_NAME
        query.append(" FROM INFORMATION_SCHEMA.ROUTINES");

        StringBuilder condition = new StringBuilder();
        if (!getProceduresReturnsFunctionsValue()) {
            condition.append(" ROUTINE_TYPE = 'PROCEDURE'");
        }
        if (dbFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(chooseBasedOnDatabaseTerm(() -> " ROUTINE_SCHEMA = ?", () -> " ROUTINE_SCHEMA LIKE ?"));
        }
        if (procedureNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" ROUTINE_NAME LIKE ?");
        }

        if (condition.length() > 0) {
            query.append(" WHERE");
            query.append(condition);
        }
        query.append(" ORDER BY ROUTINE_SCHEMA, ROUTINE_NAME, ROUTINE_TYPE");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            if (procedureNameFilter != null) {
                pStmt.setString(nextIdx, procedureNameFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createProceduresFields());
            return rs;
        }
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        final String dbFromTerm = chooseDatabaseTerm(catalog, schemaPattern);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(" SCHEMA_NAME AS TABLE_SCHEM,");                                                                       // TABLE_SCHEM
        query.append(" CATALOG_NAME AS TABLE_CATALOG");                                                                     // TABLE_CATALOG
        query.append(" FROM INFORMATION_SCHEMA.SCHEMATA");
        query.append(chooseBasedOnDatabaseTerm(() -> " WHERE FALSE",
                () -> dbFilter == null ? "" : StringUtils.hasWildcards(dbFilter) ? " WHERE SCHEMA_NAME LIKE ?" : " WHERE SCHEMA_NAME = ?"));

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            if (dbFilter != null) {
                pStmt.setString(1, dbFilter);
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createSchemasFields());
            return rs;
        }
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        if (!getJdbcConnection().getServerVersion().meetsMinimum(ServerVersion.parseVersion("8.0.11"))) {
            return super.getSQLKeywords();
        }

        String keywords = keywordsCache.get(getJdbcConnection().getServerVersion());
        if (keywords != null) {
            return keywords;
        }

        KEYWORDS_CACHE_LOCK.lock();
        try {
            // Double check, maybe another thread already added it.
            keywords = keywordsCache.get(getJdbcConnection().getServerVersion());
            if (keywords != null) {
                return keywords;
            }

            List<String> keywordsFromServer = new ArrayList<>();
            Statement stmt = getJdbcConnection().getMetaDataSafeStatement();
            ResultSet rs = stmt.executeQuery("SELECT WORD FROM INFORMATION_SCHEMA.KEYWORDS WHERE RESERVED = 1 ORDER BY WORD");
            while (rs.next()) {
                keywordsFromServer.add(rs.getString(1));
            }
            stmt.close();

            keywordsFromServer.removeAll(SQL2003_KEYWORDS);
            keywords = keywordsFromServer.stream().collect(Collectors.joining(","));

            keywordsCache.put(getJdbcConnection().getServerVersion(), keywords);
            return keywords;
        } finally {
            KEYWORDS_CACHE_LOCK.unlock();
        }
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        final String dbFromTerm = chooseDatabaseTerm(catalog, schemaPattern);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String tableNameFilter = normalizeIdentifierQuoting(tableNamePattern);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(chooseBasedOnDatabaseTerm(() -> " TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM,",
                () -> " TABLE_CATALOG AS TABLE_CAT, TABLE_SCHEMA AS TABLE_SCHEM,"));                                        // TABLE_CAT, TABLE_SCHEM
        query.append(" TABLE_NAME,");                                                                                       // TABLE_NAME
        query.append(" NULL AS GRANTOR,");                                                                                  // GRANTOR
        query.append(" GRANTEE,");                                                                                          // GRANTEE
        query.append(" PRIVILEGE_TYPE AS PRIVILEGE,");                                                                      // PRIVILEGE
        query.append(" IS_GRANTABLE");                                                                                      // IS_GRANTABLE
        query.append(" FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES");

        StringBuilder condition = new StringBuilder();
        if (dbFilter != null) {
            condition.append(chooseBasedOnDatabaseTerm(() -> " TABLE_SCHEMA = ?",
                    () -> StringUtils.hasWildcards(dbFilter) ? " TABLE_SCHEMA LIKE ?" : " TABLE_SCHEMA = ?"));
        }
        if (tableNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(StringUtils.hasWildcards(tableNameFilter) ? " TABLE_NAME LIKE ?" : " TABLE_NAME = ?");
        }

        if (condition.length() > 0) {
            query.append(" WHERE");
            query.append(condition);
        }

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            if (tableNameFilter != null) {
                pStmt.setString(nextIdx++, tableNameFilter);
            }
            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createTablePrivilegesFields());
            return rs;
        }
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        final String dbFilter = normalizeIdentifierQuoting(chooseDatabaseTerm(catalog, schemaPattern));
        final String tableNameFilter = normalizeIdentifierQuoting(tableNamePattern);

        StringBuilder query = new StringBuilder("SELECT");
        query.append(chooseBasedOnDatabaseTerm(() -> " TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM,",
                () -> " TABLE_CATALOG AS TABLE_CAT, TABLE_SCHEMA AS TABLE_SCHEM,"));                                        // TABLE_CAT, TABLE_SCHEM
        query.append(" TABLE_NAME,");                                                                                       // TABLE_NAME
        query.append(" CASE WHEN TABLE_TYPE = 'BASE TABLE' THEN");
        query.append(" CASE WHEN TABLE_SCHEMA = 'mysql' OR TABLE_SCHEMA = 'performance_schema' OR TABLE_SCHEMA = 'sys' THEN 'SYSTEM TABLE' ELSE 'TABLE' END");
        query.append(" WHEN TABLE_TYPE = 'TEMPORARY' THEN 'LOCAL_TEMPORARY' ELSE TABLE_TYPE END AS TABLE_TYPE,");           // TABLE_TYPE
        query.append(" TABLE_COMMENT AS REMARKS,");                                                                         // REMARKS
        query.append(" NULL AS TYPE_CAT,");                                                                                 // TYPE_CAT
        query.append(" NULL AS TYPE_SCHEM,");                                                                               // TYPE_SCHEM
        query.append(" NULL AS TYPE_NAME,");                                                                                // TYPE_NAME
        query.append(" NULL AS SELF_REFERENCING_COL_NAME,");                                                                // SELF_REFERENCING_COL_NAME
        query.append(" NULL AS REF_GENERATION");                                                                            // REF_GENERATION
        query.append(" FROM INFORMATION_SCHEMA.TABLES");

        StringBuilder condition = new StringBuilder();
        if (dbFilter != null) {
            condition.append(chooseBasedOnDatabaseTerm(() -> " TABLE_SCHEMA = ?",
                    () -> StringUtils.hasWildcards(dbFilter) ? " TABLE_SCHEMA LIKE ?" : " TABLE_SCHEMA = ?"));
        }
        if (tableNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(StringUtils.hasWildcards(tableNameFilter) ? " TABLE_NAME LIKE ?" : " TABLE_NAME = ?");
        }
        if (types != null && types.length > 0) {
            condition.append(" HAVING TABLE_TYPE IN (?,?,?,?,?)");
        }

        if (condition.length() > 0) {
            query.append(" WHERE");
            query.append(condition);
        }
        query.append(" ORDER BY TABLE_TYPE, TABLE_SCHEMA, TABLE_NAME");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter != null ? dbFilter : "%");
            }
            if (tableNameFilter != null) {
                pStmt.setString(nextIdx++, tableNameFilter);
            }

            if (types != null && types.length > 0) {
                for (int i = 0; i < 5; i++) {
                    pStmt.setNull(nextIdx + i, MysqlType.VARCHAR.getJdbcType());
                }
                for (int i = 0; i < types.length; i++) {
                    TableType tableType = TableType.getTableTypeEqualTo(types[i]);
                    if (tableType != TableType.UNKNOWN) {
                        pStmt.setString(nextIdx++, tableType.getName());
                    }
                }
            }

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).setColumnDefinition(createTablesFields());
            return rs;
        }
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFromTerm = chooseDatabaseTerm(catalog, schema);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String tableFilter = normalizeIdentifierQuoting(table);

        StringBuilder query = new StringBuilder("SELECT NULL AS SCOPE,");                                                   // SCOPE
        query.append(" COLUMN_NAME,");                                                                                      // COLUMN_NAME
        appendDataTypeClause(query, "COLUMN_TYPE").append(" AS DATA_TYPE,");                                                // DATA_TYPE
        appendTypeNameClause(query, "COLUMN_TYPE").append(" AS TYPE_NAME,");                                                // TYPE_NAME
        appendColumnSizeClause(query).append(" AS COLUMN_SIZE,");                                                           // COLUMN_SIZE
        query.append(" ").append(MAX_BUFFER_SIZE).append(" AS BUFFER_LENGTH,");                                             // BUFFER_LENGTH
        appendDecimalDigitsClause(query).append(" AS DECIMAL_DIGITS,");                                                     // DECIMAL_DIGITS
        query.append(" ").append(versionColumnNotPseudo).append(" AS PSEUDO_COLUMN");                                       // PSEUDO_COLUMN
        query.append(" FROM INFORMATION_SCHEMA.COLUMNS WHERE");
        if (dbFilter != null) {
            query.append(" TABLE_SCHEMA = ? AND");
        }
        query.append(" TABLE_NAME = ?");
        query.append(" AND EXTRA LIKE '%on update CURRENT_TIMESTAMP%'");

        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, dbFilter);
            }
            pStmt.setString(nextIdx, tableFilter);

            ResultSet rs = executeMetadataQuery(pStmt);
            ((com.mysql.cj.jdbc.result.ResultSetInternalMethods) rs).getColumnDefinition().setFields(createVersionColumnsFields());
            return rs;
        }
    }

}
