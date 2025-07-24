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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mysql.cj.Messages;
import com.mysql.cj.MysqlType;
import com.mysql.cj.exceptions.AssertionFailedException;
import com.mysql.cj.exceptions.MysqlErrorNumbers;
import com.mysql.cj.jdbc.exceptions.SQLError;
import com.mysql.cj.jdbc.result.ResultSetFactory;
import com.mysql.cj.protocol.a.result.ByteArrayRow;
import com.mysql.cj.protocol.a.result.ResultsetRowsStatic;
import com.mysql.cj.result.DefaultColumnDefinition;
import com.mysql.cj.result.Field;
import com.mysql.cj.result.Row;
import com.mysql.cj.util.SearchMode;
import com.mysql.cj.util.StringUtils;

/**
 * DatabaseMetaData implementation that uses MYSQL schema tables and SHOW commands.
 */
public class DatabaseMetaDataMysqlSchema extends DatabaseMetaData {

    /**
     * Enumeration for stored routine Types. The order matters for sorting stored routines elements.
     */
    private enum StoredRoutineType {
        FUNCTION, PROCEDURE;
    }

    /**
     * Parses and represents common data type information used by various column/parameter methods.
     */
    private class TypeDescriptor {

        int bufferLength;
        Integer datetimePrecision = null;
        Integer columnSize = null;
        Integer charOctetLength = null;
        Integer decimalDigits = null;
        String isNullable;
        int nullability;
        int numPrecRadix = 10;
        MysqlType mysqlType;

        TypeDescriptor(String typeInfo, String nullabilityInfo) throws SQLException {
            if (typeInfo == null) {
                throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.0"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                        getExceptionInterceptor());
            }

            this.mysqlType = MysqlType.getByName(typeInfo);

            String temp;
            java.util.StringTokenizer tokenizer;
            int maxLength = 0;
            int fract;

            switch (this.mysqlType) {
                case ENUM:
                    temp = typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.lastIndexOf(")"));
                    tokenizer = new java.util.StringTokenizer(temp, ",");
                    while (tokenizer.hasMoreTokens()) {
                        String nextToken = tokenizer.nextToken();
                        maxLength = Math.max(maxLength, nextToken.length() - 2);
                    }
                    this.columnSize = maxLength;
                    break;

                case SET:
                    temp = typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.lastIndexOf(")"));
                    tokenizer = new java.util.StringTokenizer(temp, ",");
                    int numElements = tokenizer.countTokens();
                    if (numElements > 0) {
                        maxLength += numElements - 1;
                    }
                    while (tokenizer.hasMoreTokens()) {
                        String setMember = tokenizer.nextToken().trim();

                        if (setMember.startsWith("'") && setMember.endsWith("'")) {
                            maxLength += setMember.length() - 2;
                        } else {
                            maxLength += setMember.length();
                        }
                    }
                    this.columnSize = maxLength;
                    break;

                case FLOAT:
                case FLOAT_UNSIGNED:
                    if (typeInfo.indexOf(",") != -1) {
                        // Numeric with decimals.
                        this.columnSize = Integer.valueOf(typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.indexOf(",")).trim());
                        this.decimalDigits = Integer.valueOf(typeInfo.substring(typeInfo.indexOf(",") + 1, typeInfo.indexOf(")")).trim());
                    } else if (typeInfo.indexOf("(") != -1) {
                        int size = Integer.parseInt(typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.indexOf(")")).trim());
                        if (size > 23) {
                            this.mysqlType = this.mysqlType == MysqlType.FLOAT ? MysqlType.DOUBLE : MysqlType.DOUBLE_UNSIGNED;
                            this.columnSize = 22;
                            this.decimalDigits = 0;
                        }
                    } else {
                        this.columnSize = 12;
                        this.decimalDigits = 0;
                    }
                    break;

                case DECIMAL:
                case DECIMAL_UNSIGNED:
                case DOUBLE:
                case DOUBLE_UNSIGNED:
                    if (typeInfo.indexOf(",") != -1) {
                        // Numeric with decimals.
                        this.columnSize = Integer.valueOf(typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.indexOf(",")).trim());
                        this.decimalDigits = Integer.valueOf(typeInfo.substring(typeInfo.indexOf(",") + 1, typeInfo.indexOf(")")).trim());
                    } else {
                        switch (this.mysqlType) {
                            case DECIMAL:
                            case DECIMAL_UNSIGNED:
                                this.columnSize = 65;
                                break;
                            case DOUBLE:
                            case DOUBLE_UNSIGNED:
                                this.columnSize = 22;
                                break;
                            default:
                                break;
                        }
                        this.decimalDigits = 0;
                    }
                    break;

                case CHAR:
                case VARCHAR:
                case TINYTEXT:
                case MEDIUMTEXT:
                case LONGTEXT:
                case JSON:
                case TEXT:
                case TINYBLOB:
                case MEDIUMBLOB:
                case LONGBLOB:
                case BLOB:
                case BINARY:
                case VARBINARY:
                case BIT:
                    if (this.mysqlType == MysqlType.CHAR) {
                        this.columnSize = 1;
                    }
                    if (typeInfo.indexOf("(") != -1) {
                        int endParenIndex = typeInfo.indexOf(")");
                        if (endParenIndex == -1) {
                            endParenIndex = typeInfo.length();
                        }
                        this.columnSize = Integer.valueOf(typeInfo.substring(typeInfo.indexOf("(") + 1, endParenIndex).trim());
                        // Adjust for pseudo-boolean.
                        if (tinyInt1IsBitValue() && this.columnSize.intValue() == 1 && StringUtils.startsWithIgnoreCase(typeInfo, "tinyint")) {
                            if (transformedBitIsBooleanValue()) {
                                this.mysqlType = MysqlType.BOOLEAN;
                            } else {
                                this.mysqlType = MysqlType.BIT;
                            }
                        }
                    }
                    break;

                case TINYINT:
                    if (tinyInt1IsBitValue() && typeInfo.indexOf("(1)") != -1) {
                        if (transformedBitIsBooleanValue()) {
                            this.mysqlType = MysqlType.BOOLEAN;
                        } else {
                            this.mysqlType = MysqlType.BIT;
                        }
                    } else {
                        this.columnSize = 3;
                    }
                    break;

                case TINYINT_UNSIGNED:
                    this.columnSize = 3;
                    break;

                case DATE:
                    this.datetimePrecision = 0;
                    this.columnSize = 10;
                    break;

                case TIME:
                    this.datetimePrecision = 0;
                    this.columnSize = 8;
                    if (typeInfo.indexOf("(") != -1
                            && (fract = Integer.parseInt(typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.indexOf(")")).trim())) > 0) {
                        // With fractional seconds.
                        this.datetimePrecision = fract;
                        this.columnSize += fract + 1;
                    }
                    break;

                case DATETIME:
                case TIMESTAMP:
                    this.datetimePrecision = 0;
                    this.columnSize = 19;
                    if (typeInfo.indexOf("(") != -1
                            && (fract = Integer.parseInt(typeInfo.substring(typeInfo.indexOf("(") + 1, typeInfo.indexOf(")")).trim())) > 0) {
                        // With fractional seconds.
                        this.datetimePrecision = fract;
                        this.columnSize += fract + 1;
                    }
                    break;

                case BOOLEAN:
                case GEOMETRY:
                case NULL:
                case UNKNOWN:
                case YEAR:

                default:
            }

            // If not defined explicitly take the max precision.
            if (this.columnSize == null) {
                // JDBC spec reserved only 'int' type for precision, thus longer values need to be cut.
                this.columnSize = this.mysqlType.getPrecision() > Integer.MAX_VALUE ? Integer.MAX_VALUE : this.mysqlType.getPrecision().intValue();
            }

            switch (this.mysqlType) {
                case CHAR:
                case VARCHAR:
                case TINYTEXT:
                case MEDIUMTEXT:
                case LONGTEXT:
                case JSON:
                case TEXT:
                case TINYBLOB:
                case MEDIUMBLOB:
                case LONGBLOB:
                case BLOB:
                case BINARY:
                case VARBINARY:
                case BIT:
                    this.charOctetLength = this.columnSize;
                    break;
                default:
                    break;
            }

            // BUFFER_LENGTH
            this.bufferLength = MAX_BUFFER_SIZE;

            // NUM_PREC_RADIX (is this right for char?)
            this.numPrecRadix = 10;

            // IS_NULLABLE
            if (nullabilityInfo != null) {
                if (nullabilityInfo.equals("YES")) {
                    this.nullability = columnNullable;
                    this.isNullable = "YES";
                } else if (nullabilityInfo.equals("UNKNOWN")) {
                    this.nullability = columnNullableUnknown;
                    this.isNullable = "";
                } else {
                    this.nullability = columnNoNulls;
                    this.isNullable = "NO";
                }
            } else {
                this.nullability = columnNoNulls;
                this.isNullable = "NO";
            }
        }

        int getJdbcType() {
            return this.mysqlType == MysqlType.YEAR && !yearIsDateTypeValue() ? Types.SMALLINT : this.mysqlType.getJdbcType();
        }

    }

    /**
     * Helper class that represents a composite object that is comparable based on the lexicographic comparison of its elements. The comparison is performed in
     * the order the elements are supplied, following the rules of {@link Comparable#compareTo(Object)}.
     */
    private class MultiComparable implements Comparable<MultiComparable> {

        private final List<Comparable<?>> elements;

        public MultiComparable(Comparable<?>... elements) {
            this.elements = Arrays.asList(elements);
        }

        @SuppressWarnings("unchecked")
        public <T> T getElement(int index) {
            if (index < 0 || index >= this.elements.size()) {
                throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + this.elements.size());
            }
            return (T) this.elements.get(index);
        }

        @Override
        public int compareTo(MultiComparable other) {
            int size = Math.min(this.elements.size(), other.elements.size());
            for (int i = 0; i < size; i++) {
                Comparable<?> thisElement = this.elements.get(i);
                Comparable<?> otherElement = other.elements.get(i);

                if (thisElement == null && otherElement == null) {
                    continue;
                }
                if (thisElement == null) {
                    return -1;
                }
                if (otherElement == null) {
                    return 1;
                }

                @SuppressWarnings("unchecked")
                int result = ((Comparable<Object>) thisElement).compareTo(otherElement);
                if (result != 0) {
                    return result;
                }
            }
            // If all compared elements are equal, compare based on size.
            return Integer.compare(this.elements.size(), other.elements.size());
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            MultiComparable other = (MultiComparable) obj;
            return Objects.equals(this.elements, other.elements);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.elements);
        }

        @Override
        public String toString() {
            return this.elements.toString();
        }

    }

    DatabaseMetaDataMysqlSchema(JdbcConnection connToSet, String databaseToSet, ResultSetFactory resultSetFactory) {
        super(connToSet, databaseToSet, resultSetFactory);
    }

    /**
     * Structure for holding foreign key constraints information.
     */
    private class ForeignKeyConstraintInfo {

        final String constraintName;
        final String referencingDatabase;
        final String referencingTable;
        final List<String> referencingColumnsList;
        final String referencedDatabase;
        final String referencedTable;
        final List<String> referencedColumnsList;
        final int referentialActionOnDelete;
        final int referentialActionOnUpdate;

        ForeignKeyConstraintInfo(String constraintName, String referencingDatabase, String referencingTable, List<String> referencingColumns,
                String referencedDatabase, String referencedTable, List<String> referencedColumns, int referentialActionOnDelete,
                int referentialActionOnUpdate) {
            this.constraintName = constraintName;
            this.referencingDatabase = referencingDatabase;
            this.referencingTable = referencingTable;
            this.referencingColumnsList = referencingColumns;
            this.referencedColumnsList = referencedColumns;
            this.referencedTable = referencedTable;
            this.referencedDatabase = referencedDatabase;
            this.referentialActionOnDelete = referentialActionOnDelete;
            this.referentialActionOnUpdate = referentialActionOnUpdate;
        }

    }

    /**
     * Extracts the foreign key constraints information from the specified table or from all the tables from the specified database.
     *
     * @param dbName
     *            The database name to fetch the tables from.
     * @param tableName
     *            The table to fetch. If {@code null} then all tables from the database are fetched.
     * @return
     *         A list with all foreign key constraints information.
     * @throws SQLException
     *             If an error occurs.
     */
    private List<ForeignKeyConstraintInfo> extractForeignKeysForTable(String dbName, String tableName) throws SQLException {
        List<String> tableList = new ArrayList<>();
        if (tableName != null) {
            tableList.add(tableName);
        } else {
            ResultSet rs = null;
            try {
                // If not pedantic mode then quote database name before calling #getTables().
                String quotedDbName = pedanticValue() ? dbName : StringUtils.quoteIdentifier(dbName, getQuoteId(), true);
                rs = chooseBasedOnDatabaseTerm(() -> getTables(quotedDbName, null, null, new String[] { "TABLE" }),
                        () -> getTables(null, quotedDbName, null, new String[] { "TABLE" }));
                while (rs.next()) {
                    tableList.add(rs.getString("TABLE_NAME"));
                }
            } finally {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (SQLException e) {
                        AssertionFailedException.shouldNotHappen(e);
                    }
                    rs = null;
                }
            }
        }

        List<ForeignKeyConstraintInfo> tableForeignKeysList = new ArrayList<>();
        ResultSet rs = null;
        try (Statement stmt = getJdbcConnection().getMetaDataSafeStatement()) {
            for (String tableToExtract : tableList) {
                StringBuilder query = new StringBuilder("SHOW CREATE TABLE ");
                query.append(StringUtils.getFullyQualifiedName(dbName, tableToExtract, getQuoteId(), true));
                try {
                    rs = stmt.executeQuery(query.toString());
                } catch (SQLException e) {
                    String sqlState = e.getSQLState(); // Ignore exception if SQLState is 42S02 or 42000 - table/database doesn't exist.
                    int errorCode = e.getErrorCode(); // Ignore exception if ErrorCode is 1146, 1109, or 1149 - table/database doesn't exist.
                    if (!(MysqlErrorNumbers.SQLSTATE_MYSQL_BASE_TABLE_OR_VIEW_NOT_FOUND.equals(sqlState)
                            && (errorCode == MysqlErrorNumbers.ER_NO_SUCH_TABLE || errorCode == MysqlErrorNumbers.ER_UNKNOWN_TABLE)
                            || MysqlErrorNumbers.SQLSTATE_SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION_NO_SUBCLASS.equals(sqlState)
                                    && errorCode == MysqlErrorNumbers.ER_BAD_DB_ERROR)) {
                        throw e;
                    }
                    continue;
                }

                while (rs.next()) {
                    tableForeignKeysList.addAll(extractForeignKeysFromCreateTable(dbName, rs.getString(1), rs.getString(2)));
                }
            }
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    AssertionFailedException.shouldNotHappen(e);
                }
                rs = null;
            }
        }

        return tableForeignKeysList;
    }

    /**
     * Extracts the foreign key constraints information from the specified create table command, parsing the following syntax:<br/>
     * "CONSTRAINT `&lt;constraint_name&gt;` FOREIGN KEY (`&lt;referencing_col_1&gt;`, `&lt;referencing_col_2&gt;`, ...) REFERENCES `&lt;referenced_table&gt;`
     * (`&lt;referenced_col_1&gt;`, `&lt;referenced_col_2&gt;`, ...) ON DELETE &lt;action&gt; ON UPDATE &lt;action&gt;"
     *
     * @param dbName
     *            The referencing database.
     * @param tableName
     *            The referencing table.
     * @param createTableSql
     *            The CREATE TABLE sql for the referencing table.
     * @return
     *         A list with all foreign key constraints information.
     * @throws SQLException
     *             If an error occurs while parsing the foreign key constraints SQL.
     */
    private List<ForeignKeyConstraintInfo> extractForeignKeysFromCreateTable(String dbName, String tableName, String createTableSql) throws SQLException {
        Function<String, Integer> mysqlActionToJdbc = action -> {
            if (action.startsWith("NO ACTION")) {
                return importedKeyNoAction;
            } else if (action.startsWith("CASCADE")) {
                return importedKeyCascade;
            } else if (action.startsWith("SET NULL")) {
                return importedKeySetNull;
            } else if (action.startsWith("SET DEFAULT")) {
                return importedKeySetDefault;
            }
            return importedKeyRestrict; // RESTRICT
        };
        List<ForeignKeyConstraintInfo> tableForeignKeysList = new ArrayList<>();

        StringTokenizer lineTokenizer = new StringTokenizer(createTableSql, "\n");
        while (lineTokenizer.hasMoreTokens()) {
            String line = lineTokenizer.nextToken().trim();
            String constraintName = null;
            List<String> referencingColumnNames;
            String referencedDbName = StringUtils.quoteIdentifier(dbName, getQuoteId(), true);
            String referencedTableName = "";
            List<String> referencedColumnNames;
            int referentialActionOnDelete = importedKeyRestrict;
            int referentialActionOnUpdate = importedKeyRestrict;

            if (StringUtils.startsWithIgnoreCase(line, "CONSTRAINT")) {
                int beginPos = line.indexOf(getQuoteId());
                if (beginPos != -1) {
                    int endPos = -1;
                    endPos = StringUtils.indexOfQuoteDoubleAware(line, getQuoteId(), beginPos + 1);
                    if (endPos != -1) {
                        constraintName = StringUtils.unquoteIdentifier(line.substring(beginPos + 1, endPos), getQuoteId());
                        line = line.substring(endPos + 1, line.length()).trim();
                    }
                }
            }
            if (line.startsWith("FOREIGN KEY")) {
                int afterFk = "FOREIGN KEY".length();
                int referencingColumnsBegin = StringUtils.indexOfIgnoreCase(afterFk, line, "(", getQuoteId(), getQuoteId(),
                        SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
                if (referencingColumnsBegin == -1) {
                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.14"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                            getExceptionInterceptor());
                }
                int referencingColumnsEnd = StringUtils.indexOfIgnoreCase(referencingColumnsBegin, line, ")", getQuoteId(), getQuoteId(),
                        SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
                if (referencingColumnsEnd == -1) {
                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.15"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                            getExceptionInterceptor());
                }
                String referencingColumnNamesToken = line.substring(referencingColumnsBegin + 1, referencingColumnsEnd);
                referencingColumnNames = StringUtils.split(referencingColumnNamesToken, ",", getQuoteId(), getQuoteId(), false).stream()
                        .map(c -> StringUtils.unquoteIdentifier(c, getQuoteId())).collect(Collectors.toList());

                int indexOfRef = StringUtils.indexOfIgnoreCase(afterFk, line, "REFERENCES", getQuoteId(), getQuoteId(), SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
                if (indexOfRef == -1) {
                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.16"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                            getExceptionInterceptor());
                }
                int afterRef = indexOfRef + "REFERENCES".length();
                int referencedColumnsBegin = StringUtils.indexOfIgnoreCase(afterRef, line, "(", getQuoteId(), getQuoteId(),
                        SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
                if (referencedColumnsBegin == -1) {
                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.17"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                            getExceptionInterceptor());
                }
                referencedTableName = line.substring(afterRef, referencedColumnsBegin).trim();
                int referencedColumnsEnd = StringUtils.indexOfIgnoreCase(referencedColumnsBegin + 1, line, ")", getQuoteId(), getQuoteId(),
                        SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
                if (referencedColumnsEnd == -1) {
                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.18"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                            getExceptionInterceptor());
                }
                String referencedColumnNamesToken = line.substring(referencedColumnsBegin + 1, referencedColumnsEnd);
                referencedColumnNames = StringUtils.split(referencedColumnNamesToken, ",", getQuoteId(), getQuoteId(), false).stream()
                        .map(c -> StringUtils.unquoteIdentifier(c, getQuoteId())).collect(Collectors.toList());
                if (referencedColumnNames.size() != referencingColumnNames.size()) {
                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.12"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                            getExceptionInterceptor());
                }

                List<String> tableRef = StringUtils.splitDBdotName(referencedTableName, referencedDbName, getQuoteId(), true);
                referencedDbName = StringUtils.unquoteIdentifier(tableRef.get(0), getQuoteId());
                referencedTableName = StringUtils.unquoteIdentifier(tableRef.get(1), getQuoteId());
                String referentialActions = line.substring(referencedColumnsEnd + 1);
                int onDeletePos = referentialActions.indexOf("ON DELETE");
                if (onDeletePos != -1) {
                    int afterOnDelete = onDeletePos + "ON DELETE ".length();
                    String onDeleteAction = referentialActions.substring(afterOnDelete);
                    referentialActionOnDelete = mysqlActionToJdbc.apply(onDeleteAction);
                }
                int onUpdatePos = referentialActions.indexOf("ON UPDATE");
                if (onUpdatePos != -1) {
                    int afterOnUpdate = onUpdatePos + "ON UPDATE ".length();
                    String onUpdateAction = referentialActions.substring(afterOnUpdate);
                    referentialActionOnUpdate = mysqlActionToJdbc.apply(onUpdateAction);
                }

                tableForeignKeysList.add(new ForeignKeyConstraintInfo(constraintName, dbName, tableName, referencingColumnNames, referencedDbName,
                        referencedTableName, referencedColumnNames, referentialActionOnDelete, referentialActionOnUpdate));
            }
        }
        return tableForeignKeysList;
    }

    /**
     * Retrieves stored routines (procedures and/or functions) metadata.
     *
     * @param catalog
     *            The catalog name.
     * @param schemaPattern
     *            The schema pattern.
     * @param routineNamePattern
     *            The procedure name pattern.
     * @param targetMetaData
     *            The type of metadata to produce.
     * @return
     *         The result set containing the metadata. If {@code targetMetada == BOTH} then returns the metadata structure for procedures as it supports
     *         functions too (getFunction* methods were added later).
     * @throws SQLException
     *             If a database access error occurs.
     */
    private ResultSet getStoredRoutines(String catalog, String schemaPattern, String routineNamePattern, StoredRoutineType targetMetaData) throws SQLException {
        final String dbFilter = chooseDatabaseTerm(catalog, schemaPattern);
        final String routineNameFilter = normalizeIdentifierQuoting(routineNamePattern);

        final SortedMap<MultiComparable, Row> sortedRows = new TreeMap<>();
        List<String> dbList = chooseBasedOnDatabaseTerm(() -> getDatabasesByLiteral(dbFilter), () -> getDatabasesByPattern(dbFilter));
        for (String db : dbList) {
            // Collect functions metadata.
            if (targetMetaData == StoredRoutineType.FUNCTION || getProceduresReturnsFunctionsValue()) {
                StringBuilder query = new StringBuilder("SHOW FUNCTION STATUS WHERE ");
                query.append(chooseBasedOnDatabaseTerm(() -> "Db = ?", () -> "Db LIKE ?"));
                if (!StringUtils.isNullOrEmpty(routineNameFilter)) {
                    query.append(" AND Name LIKE ?");
                }
                try (PreparedStatement functionsStmt = prepareMetaDataSafeStatement(query.toString())) {
                    functionsStmt.setString(1, db);
                    if (!StringUtils.isNullOrEmpty(routineNameFilter)) {
                        functionsStmt.setString(2, routineNameFilter);
                    }
                    ResultSet functionsRs = functionsStmt.executeQuery();
                    while (functionsRs.next()) {
                        String funcDb = functionsRs.getString("db");
                        String functionName = functionsRs.getString("name");
                        byte[][] row = null;
                        if (targetMetaData == StoredRoutineType.PROCEDURE) { // Exposing functions as procedures.
                            row = new byte[9][];
                            row[0] = chooseBasedOnDatabaseTerm(() -> s2b(funcDb), () -> s2b("def"));                        // PROCEDURE_CAT
                            row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(funcDb));                              // PROCEDURE_SCHEM
                            row[2] = s2b(functionName);                                                                     // PROCEDURE_NAME
                            row[3] = null;                                                                                  // reserved for future use
                            row[4] = null;                                                                                  // reserved for future use
                            row[5] = null;                                                                                  // reserved for future use
                            row[6] = s2b(functionsRs.getString("comment"));                                                 // REMARKS
                            row[7] = n2b(procedureReturnsResult);                                                           // PROCEDURE_TYPE
                            row[8] = s2b(functionName);                                                                     // SPECFIC_NAME
                        } else {
                            row = new byte[6][];
                            row[0] = chooseBasedOnDatabaseTerm(() -> s2b(funcDb), () -> s2b("def"));                        // FUNCTION_CAT
                            row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(funcDb));                              // FUNCTION_SCHEM
                            row[2] = s2b(functionName);                                                                     // FUNCTION_NAME
                            row[3] = s2b(functionsRs.getString("comment"));                                                 // REMARKS
                            row[4] = n2b(functionNoTable);                                                                  // FUNCTION_TYPE
                            row[5] = s2b(functionName);                                                                     // SPECFIC_NAME
                        }
                        sortedRows.put(new MultiComparable(funcDb, functionName, StoredRoutineType.FUNCTION), new ByteArrayRow(row, getExceptionInterceptor()));
                    }
                    try {
                        functionsRs.close();
                    } catch (Exception sqlEx) {
                        AssertionFailedException.shouldNotHappen(sqlEx);
                    }
                    functionsRs = null;
                }
            }

            // Collect procedures metadata.
            if (targetMetaData == StoredRoutineType.PROCEDURE) {
                StringBuilder query = new StringBuilder("SHOW PROCEDURE STATUS WHERE ");
                query.append(chooseBasedOnDatabaseTerm(() -> "Db = ?", () -> "Db LIKE ?"));
                if (!StringUtils.isNullOrEmpty(routineNameFilter)) {
                    query.append(" AND Name LIKE ?");
                }
                try (PreparedStatement proceduresStmt = prepareMetaDataSafeStatement(query.toString())) {
                    proceduresStmt.setString(1, db);
                    if (!StringUtils.isNullOrEmpty(routineNameFilter)) {
                        proceduresStmt.setString(2, routineNameFilter);
                    }
                    ResultSet proceduresRs = proceduresStmt.executeQuery();
                    while (proceduresRs.next()) {
                        String procDb = proceduresRs.getString("db");
                        String procedureName = proceduresRs.getString("name");
                        byte[][] row = new byte[9][];
                        row[0] = chooseBasedOnDatabaseTerm(() -> s2b(procDb), () -> s2b("def"));                            // PROCEDURE_CAT
                        row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(procDb));                                  // PROCEDURE_SCHEM
                        row[2] = s2b(procedureName);                                                                        // PROCEDURE_NAME
                        row[3] = null;                                                                                      // reserved for future use
                        row[4] = null;                                                                                      // reserved for future use
                        row[5] = null;                                                                                      // reserved for future use
                        row[6] = s2b(proceduresRs.getString("comment"));                                                    // REMARKS
                        row[7] = n2b(procedureNoResult);                                                                    // PROCEDURE_TYPE
                        row[8] = s2b(procedureName);                                                                        // SPECFIC_NAME

                        sortedRows.put(new MultiComparable(procDb, procedureName, StoredRoutineType.PROCEDURE),
                                new ByteArrayRow(row, getExceptionInterceptor()));
                    }
                    try {
                        proceduresRs.close();
                    } catch (Exception sqlEx) {
                        AssertionFailedException.shouldNotHappen(sqlEx);
                    }
                    proceduresRs = null;
                }
            }
        }

        final List<Row> rows = new ArrayList<>(sortedRows.values());
        // Procedures metadata support both but not the other way around.
        final Field[] fields = targetMetaData == StoredRoutineType.PROCEDURE ? createProceduresFields() : createFunctionsFields();
        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    /**
     * Retrieves stored routines columns metadata.
     *
     * @param catalog
     *            The catalog name.
     * @param schemaPattern
     *            The schema pattern.
     * @param routineNamePattern
     *            The procedure name pattern.
     * @param columnNamePattern
     *            The column (parameter) name pattern.
     * @param targetMetaData
     *            The type of metadata to produce.
     * @return
     *         The result set containing the metadata. If {@code targetMetada == BOTH} then returns the metadata structure for procedures as it supports
     *         functions too (getFunction* methods were added later).
     *
     * @throws SQLException
     *             If a database access error occurs.
     */
    private ResultSet getStoredRoutineColumns(String catalog, String schemaPattern, String routineNamePattern, String columnNamePattern,
            StoredRoutineType targetMetaData) throws SQLException {
        final List<MultiComparable> routinesToExtract = new ArrayList<>();

        ResultSet routinesRs = null;
        try {
            routinesRs = getStoredRoutines(catalog, schemaPattern, routineNamePattern, targetMetaData);
            while (routinesRs.next()) {
                if (targetMetaData == StoredRoutineType.PROCEDURE) {
                    String dbField = chooseBasedOnDatabaseTerm(() -> "PROCEDURE_CAT", () -> "PROCEDURE_SCHEM");
                    StoredRoutineType routineType = routinesRs.getShort("PROCEDURE_TYPE") == procedureNoResult ? StoredRoutineType.PROCEDURE
                            : StoredRoutineType.FUNCTION;
                    routinesToExtract.add(new MultiComparable(routinesRs.getString(dbField), routinesRs.getString("PROCEDURE_NAME"), routineType));
                } else {
                    String dbField = chooseBasedOnDatabaseTerm(() -> "FUNCTION_CAT", () -> "FUNCTION_SCHEM");
                    routinesToExtract
                            .add(new MultiComparable(routinesRs.getString(dbField), routinesRs.getString("FUNCTION_NAME"), StoredRoutineType.FUNCTION));
                }
            }
        } finally {
            if (routinesRs != null) {
                try {
                    routinesRs.close();
                } catch (SQLException sqlEx) {
                    AssertionFailedException.shouldNotHappen(sqlEx);
                }
                routinesRs = null;
            }
        }

        final ArrayList<Row> rows = new ArrayList<>();
        for (MultiComparable routine : routinesToExtract) {
            rows.addAll(extractStoredRoutineColumnsTypeInfo(routine.getElement(0), routine.getElement(1), routine.getElement(2), columnNamePattern,
                    targetMetaData));
        }

        // Procedure columns metadata support both but not the other way around.
        final Field[] fields = targetMetaData == StoredRoutineType.PROCEDURE ? createProcedureColumnsFields() : createFunctionColumnsFields();
        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(fields)));
    }

    /**
     * Extract parameter details for stored routines by parsing the DDL query obtained from SHOW CREATE [PROCEDURE|FUNCTION] ... statements.
     * The result rows returned follow the required structure for getProcedureColumns() and getFunctionColumns() methods.
     *
     * Internal use only.
     *
     * @param dbName
     *            The database name.
     * @param routineName
     *            The stored routine name, either a stored procedure or a function.
     * @param routineType
     *            The type of the stored routine.
     * @param parameterNamePattern
     *            The parameter name pattern to filter by.
     * @param targetMetaData
     *            The type of metadata to produce.
     * @return
     *         A list of {@link Row}s containing all the routine parameters type information.
     * @throws SQLException
     *             If an error occurs while fetching the metadata or while parsing the object creation DDL.
     */
    private List<Row> extractStoredRoutineColumnsTypeInfo(String dbName, String routineName, StoredRoutineType routineType, String parameterNamePattern,
            StoredRoutineType targetMetaData) throws SQLException {
        final List<Row> rows = new ArrayList<>();

        boolean isRoutineInAnsiMode = false;
        String openingDelimiters = null;
        String closingDelimiters = null;
        String parameterDef = null;

        ResultSet paramRetrievalRs = null;
        try (Statement paramRetrievalStmt = getJdbcConnection().getMetaDataSafeStatement()) {
            String fieldName = null;
            StringBuilder query = new StringBuilder();
            if (routineType == StoredRoutineType.PROCEDURE) {
                fieldName = "Create Procedure";
                query.append("SHOW CREATE PROCEDURE ");
            } else {
                fieldName = "Create Function";
                query.append("SHOW CREATE FUNCTION ");
            }
            query.append(StringUtils.quoteIdentifier(dbName, getQuoteId(), true));
            query.append('.');
            query.append(StringUtils.quoteIdentifier(routineName, getQuoteId(), true));

            paramRetrievalRs = paramRetrievalStmt.executeQuery(query.toString());
            if (paramRetrievalRs.next()) {
                String routineCode = paramRetrievalRs.getString(fieldName);
                if (!noAccessToProcedureBodiesValue() && StringUtils.isNullOrEmpty(routineCode)) {
                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.4"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                            getExceptionInterceptor());
                }

                try {
                    String sqlMode = paramRetrievalRs.getString("sql_mode");
                    isRoutineInAnsiMode = StringUtils.indexOfIgnoreCase(sqlMode, "ANSI") != -1;
                } catch (SQLException e) {
                    AssertionFailedException.shouldNotHappen(e);
                }
                String identifierMarkers = isRoutineInAnsiMode ? "`\"" : "`";
                String identifierAndStringMarkers = "'" + identifierMarkers;
                openingDelimiters = "(" + identifierMarkers;
                closingDelimiters = ")" + identifierMarkers;

                if (!StringUtils.isNullOrEmpty(routineCode)) {
                    // Sanitize/normalize by stripping out comments.
                    routineCode = StringUtils.stripCommentsAndHints(routineCode, identifierAndStringMarkers, identifierAndStringMarkers,
                            !getSession().getServerSession().isNoBackslashEscapesSet());
                    int startOfParamDeclaration = StringUtils.indexOfIgnoreCase(0, routineCode, "(", getQuoteId(), getQuoteId(),
                            getSession().getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__FULL);
                    int endOfParamDeclaration = indexOfParameterDeclarationEnd(startOfParamDeclaration, routineCode, identifierMarkers);

                    if (routineType == StoredRoutineType.FUNCTION) {
                        // Grab the return column since it needs to go first in the output result set.
                        int returnsIndex = StringUtils.indexOfIgnoreCase(0, routineCode, " RETURNS ", getQuoteId(), getQuoteId(),
                                getSession().getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__FULL);
                        int endReturnsDef = indexOfEndOfReturnsClause(routineCode, returnsIndex, identifierMarkers);

                        // Trim off whitespace after "RETURNS".
                        int declarationStart = returnsIndex + "RETURNS ".length();
                        while (declarationStart < routineCode.length()) {
                            if (Character.isWhitespace(routineCode.charAt(declarationStart))) {
                                declarationStart++;
                            } else {
                                break;
                            }
                        }

                        String returnsDefn = routineCode.substring(declarationStart, endReturnsDef).trim();
                        TypeDescriptor returnsTypeDescriptor = new TypeDescriptor(returnsDefn, "YES");
                        rows.add(typeDescriptorToStoredRoutineRow(dbName, routineName, "", false, false, true, returnsTypeDescriptor, 0, targetMetaData));
                    }

                    if (startOfParamDeclaration == -1 || endOfParamDeclaration == -1) {
                        throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.5"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                getExceptionInterceptor());
                    }
                    parameterDef = routineCode.substring(startOfParamDeclaration + 1, endOfParamDeclaration);
                }

            }
        } finally {
            if (paramRetrievalRs != null) {
                try {
                    paramRetrievalRs.close();
                } catch (SQLException sqlEx) {
                    AssertionFailedException.shouldNotHappen(sqlEx);
                }
                paramRetrievalRs = null;
            }
        }

        if (parameterDef != null) {
            int ordinal = 1;
            List<String> parseList = StringUtils.split(parameterDef, ",", openingDelimiters, closingDelimiters, true);
            for (String declaration : parseList) {
                if (declaration.trim().length() == 0) {
                    break; // No parameters actually declared, but whitespace spans lines.
                }

                String paramName = null;
                boolean isOutParam = false;
                boolean isInParam = false;

                // Bug#52167, tokenizer will break if declaration contains special characters like '\n'.
                declaration = declaration.replaceAll("[\\t\\n\\x0B\\f\\r]", " ");
                StringTokenizer declarationTok = new StringTokenizer(declaration, " ");
                if (declarationTok.hasMoreTokens()) {
                    String possibleParamName = declarationTok.nextToken();
                    if (possibleParamName.equalsIgnoreCase("OUT")) {
                        isOutParam = true;
                        isInParam = false;
                        if (declarationTok.hasMoreTokens()) {
                            paramName = declarationTok.nextToken();
                        } else {
                            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.6"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                    getExceptionInterceptor());
                        }
                    } else if (possibleParamName.equalsIgnoreCase("INOUT")) {
                        isOutParam = true;
                        isInParam = true;
                        if (declarationTok.hasMoreTokens()) {
                            paramName = declarationTok.nextToken();
                        } else {
                            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.6"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                    getExceptionInterceptor());
                        }
                    } else if (possibleParamName.equalsIgnoreCase("IN")) {
                        isOutParam = false;
                        isInParam = true;
                        if (declarationTok.hasMoreTokens()) {
                            paramName = declarationTok.nextToken();
                        } else {
                            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.6"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                    getExceptionInterceptor());
                        }
                    } else {
                        isOutParam = false;
                        isInParam = true;
                        paramName = possibleParamName;
                    }

                    TypeDescriptor typeDesc = null;
                    if (declarationTok.hasMoreTokens()) {
                        StringBuilder typeInfo = new StringBuilder(declarationTok.nextToken());
                        while (declarationTok.hasMoreTokens()) {
                            typeInfo.append(" ");
                            typeInfo.append(declarationTok.nextToken());
                        }
                        typeDesc = new TypeDescriptor(typeInfo.toString(), "YES");
                    } else {
                        throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.7"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                getExceptionInterceptor());
                    }

                    if (paramName.startsWith("`") && paramName.endsWith("`")) {
                        paramName = StringUtils.unquoteIdentifier(paramName, "`");
                    } else if (isRoutineInAnsiMode && paramName.startsWith("\"") && paramName.endsWith("\"")) {
                        paramName = StringUtils.unquoteIdentifier(paramName, "\"");
                    }

                    final String paramNameFilter = normalizeIdentifierQuoting(parameterNamePattern);
                    if (paramNameFilter == null || StringUtils.wildCompareIgnoreCase(paramName, paramNameFilter)) {
                        rows.add(typeDescriptorToStoredRoutineRow(dbName, routineName, paramName, isOutParam, isInParam, false, typeDesc, ordinal++,
                                targetMetaData));
                    }
                } else {
                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.8"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                            getExceptionInterceptor());
                }
            }
        }
        return rows;
    }

    /**
     * Finds the end of the parameter declaration from the output of "SHOW CREATE FUNCTION/PROCEDURE".
     *
     * @param beginIndex
     *            Should be the index of the first "(" in the procedure body.
     * @param procedureDef
     *            The procedure body.
     * @param identifierMarkers
     *            The identifier quote character(s) in use.
     * @return
     *         The index of the end of the parameter declaration, not including the closing ")".
     * @throws SQLException
     *             If a parse error occurs.
     */
    private int indexOfParameterDeclarationEnd(int beginIndex, String procedureDef, String identifierMarkers) throws SQLException {
        int openParenIndex = beginIndex;
        int closeParenIndex = beginIndex;
        boolean betweenParens = true;

        while (betweenParens && closeParenIndex < procedureDef.length()) {
            int nextClosedParenIndex = StringUtils.indexOfIgnoreCase(closeParenIndex + 1, procedureDef, ")", identifierMarkers, identifierMarkers,
                    getSession().getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__BSE_MRK_COM_MYM_HNT_WS);

            if (nextClosedParenIndex != -1) {
                int nextOpenParenIndex = StringUtils.indexOfIgnoreCase(openParenIndex + 1, procedureDef, "(", identifierMarkers, identifierMarkers,
                        getSession().getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__BSE_MRK_COM_MYM_HNT_WS);

                if (nextOpenParenIndex != -1 && nextOpenParenIndex < nextClosedParenIndex) {
                    openParenIndex = nextOpenParenIndex;
                    closeParenIndex = nextClosedParenIndex;
                } else {
                    betweenParens = false;
                    closeParenIndex = nextClosedParenIndex;
                }
            } else {
                // There should always be a closed paren of some sort.
                throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.5"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                        getExceptionInterceptor());
            }
        }

        return closeParenIndex;
    }

    /**
     * Finds index of the end of the RETURNS clause from the output of "SHOW CREATE FUNCTION" by using any of the keywords allowed after the RETURNS clause, or
     * a label.
     *
     * @param procedureDefn
     *            The function body containing the definition of the function.
     * @param positionOfReturnKeyword
     *            The position of "RETURNS" in the definition.
     * @param identifierMarkers
     *            The identifier quote character(s) in use.
     * @return
     *         The index of the end of the returns clause.
     * @throws SQLException
     *             If a parse error occurs.
     */
    private int indexOfEndOfReturnsClause(String procedureDefn, int positionOfReturnKeyword, String identifierMarkers) throws SQLException {
        /*
         * characteristic: {
         * COMMENT 'string'
         * | LANGUAGE SQL
         * | [NOT] DETERMINISTIC
         * | { CONTAINS SQL | NO SQL | READS SQL DATA | MODIFIES SQL DATA }
         * | SQL SECURITY { DEFINER | INVOKER }
         * }
         */
        String openingMarkers = identifierMarkers + "(";
        String closingMarkers = identifierMarkers + ")";
        String[] tokens = new String[] { "COMMENT", "LANGUAGE", "NOT", "DETERMINISTIC", "CONTAINS", "NO", "READS", "MODIFIES", "SQL", "BEGIN", "RETURN" };
        int startLookingAt = positionOfReturnKeyword + "RETURNS".length() + 1;
        int endOfReturn = -1;
        for (int i = 0; i < tokens.length; i++) {
            int nextEndOfReturn = StringUtils.indexOfIgnoreCase(startLookingAt, procedureDefn, tokens[i], openingMarkers, closingMarkers,
                    getSession().getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
            if (nextEndOfReturn != -1) {
                if (endOfReturn == -1 || nextEndOfReturn < endOfReturn) {
                    endOfReturn = nextEndOfReturn;
                }
            }
        }

        if (endOfReturn != -1) {
            return endOfReturn;
        }

        // Is it a Label?
        endOfReturn = StringUtils.indexOfIgnoreCase(startLookingAt, procedureDefn, ":", openingMarkers, closingMarkers,
                getSession().getServerSession().isNoBackslashEscapesSet() ? SearchMode.__MRK_COM_MYM_HNT_WS : SearchMode.__BSE_MRK_COM_MYM_HNT_WS);
        if (endOfReturn != -1) {
            // Seek back until whitespace.
            for (int i = endOfReturn; i > startLookingAt; i--) {
                if (Character.isWhitespace(procedureDefn.charAt(i))) {
                    return i;
                }
            }
        }

        // Can't parse it.
        throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.5"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR, getExceptionInterceptor());
    }

    /**
     * Converts TypeDescriptor into a Row instance for the specified stored routine parameter.
     *
     * @param dbName
     *            The database name.
     * @param routineName
     *            The stored routine name.
     * @param paramName
     *            The parameter name.
     * @param isOutParam
     *            Is this an OUT parameter?
     * @param isInParam
     *            Is this an IN parameter?
     * @param isReturnParam
     *            Is this a return parameter?
     * @param typeDesc
     *            The TypeDescriptor instance to convert.
     * @param ordinal
     *            Ordinal position in the rows list.
     * @param targetMetaData
     *            The type of metadata to produce.
     * @return
     *         A {@link Row} instance containing the routine parameter type information.
     * @throws SQLException
     */
    private Row typeDescriptorToStoredRoutineRow(String dbName, String routineName, String paramName, boolean isOutParam, boolean isInParam,
            boolean isReturnParam, TypeDescriptor typeDesc, int ordinal, StoredRoutineType targetMetaData) throws SQLException {
        byte[][] row = targetMetaData == StoredRoutineType.PROCEDURE ? new byte[20][] : new byte[17][];
        row[0] = chooseBasedOnDatabaseTerm(() -> s2b(dbName), () -> s2b("def"));                                            // PROCEDURE_CAT/FUNCTION_CAT
        row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(dbName));                                                  // PROCEDURE_SCHEM/FUNCTION_SCHEM
        row[2] = s2b(routineName);                                                                                          // PROCEDURE_NAME/FUNCTION_NAME
        row[3] = s2b(paramName);                                                                                            // COLUMN_NAME
        int columnType = getStoredRoutineColumnType(isOutParam, isInParam, isReturnParam, targetMetaData);
        row[4] = n2b(columnType);                                                                                           // COLUMN_TYPE
        row[5] = n2b(typeDesc.getJdbcType());                                                                               // DATA_TYPE
        row[6] = s2b(typeDesc.mysqlType.getName());                                                                         // TYPE_NAME
        row[7] = typeDesc.datetimePrecision == null ? n2b(typeDesc.columnSize) : n2b(typeDesc.datetimePrecision);           // PRECISION
        row[8] = typeDesc.columnSize == null ? null : n2b(typeDesc.columnSize);                                             // LENGTH
        row[9] = typeDesc.decimalDigits == null ? null : n2b(typeDesc.decimalDigits);                                       // SCALE
        row[10] = n2b(typeDesc.numPrecRadix);                                                                               // RADIX
        switch (typeDesc.nullability) {                                                                                     // NULLABLE
            case columnNoNulls:
                row[11] = n2b(targetMetaData == StoredRoutineType.PROCEDURE ? procedureNoNulls : functionNoNulls);
                break;
            case columnNullable:
                row[11] = n2b(targetMetaData == StoredRoutineType.PROCEDURE ? procedureNullable : functionNullable);
                break;
            case columnNullableUnknown:
                row[11] = n2b(targetMetaData == StoredRoutineType.PROCEDURE ? procedureNullableUnknown : functionNullableUnknown);
                break;
            default:
                throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.1"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                        getExceptionInterceptor());
        }
        row[12] = null;                                                                                                     // REMARKS
        if (targetMetaData == StoredRoutineType.PROCEDURE) {
            row[13] = null;                                                                                                 // COLUMN_DEF
            row[14] = null;                                                                                                 // SQL_DATA_TYPE (future use)
            row[15] = null;                                                                                                 // SQL_DATETIME_SUB (future use)
            row[16] = typeDesc.charOctetLength == null ? null : n2b(typeDesc.charOctetLength);                              // CHAR_OCTET_LENGTH
            row[17] = n2b(ordinal);                                                                                         // ORDINAL_POSITION
            row[18] = s2b(typeDesc.isNullable);                                                                             // IS_NULLABLE
            row[19] = s2b(routineName);                                                                                     // SPECIFIC_NAME
        } else {
            row[13] = typeDesc.charOctetLength == null ? null : n2b(typeDesc.charOctetLength);                              // CHAR_OCTET_LENGTH
            row[14] = n2b(ordinal);                                                                                         // ORDINAL_POSITION
            row[15] = s2b(typeDesc.isNullable);                                                                             // IS_NULLABLE
            row[16] = s2b(routineName);                                                                                     // SPECIFIC_NAME
        }
        return new ByteArrayRow(row, getExceptionInterceptor());
    }

    /**
     * Determines the COLUMN_TYPE information based on parameter type (IN, OUT or INOUT) or function return parameter.
     *
     * @param isOutParam
     *            Indicates whether it's an output parameter.
     * @param isInParam
     *            Indicates whether it's an input parameter.
     * @param isReturnParam
     *            Indicates whether it's a function return parameter.
     * @param targetMetaData
     *            The type of metadata to produce.
     * @return
     *         The corresponding COLUMN_TYPE as in {@link java.sql.DatabaseMetaData#getProcedureColumns(String, String, String, String)} or
     *         {@link java.sql.DatabaseMetaData#getFunctionColumns(String, String, String, String)} API.
     */
    private int getStoredRoutineColumnType(boolean isOutParam, boolean isInParam, boolean isReturnParam, StoredRoutineType targetMetaData) {
        if (isInParam && isOutParam) {
            return targetMetaData == StoredRoutineType.PROCEDURE ? procedureColumnInOut : functionColumnInOut;
        } else if (isInParam) {
            return targetMetaData == StoredRoutineType.PROCEDURE ? procedureColumnIn : functionColumnIn;
        } else if (isOutParam) {
            return targetMetaData == StoredRoutineType.PROCEDURE ? procedureColumnOut : functionColumnOut;
        } else if (isReturnParam) {
            return targetMetaData == StoredRoutineType.PROCEDURE ? procedureColumnReturn : functionReturn;
        } else {
            return targetMetaData == StoredRoutineType.PROCEDURE ? procedureColumnUnknown : functionColumnUnknown;
        }
    }

    /**
     * Retrieves the database names available on this server. The results are ordered by database name.
     *
     * @return
     *         List of database names.
     * @throws SQLException
     *             If an error occurs.
     */
    private List<String> getDatabases() throws SQLException {
        return getDatabasesByPattern(null);
    }

    /**
     * Retrieves the database names matching the dbPattern available on this server. The results are ordered alphabetically by database name.
     *
     * @param dbPattern
     *            The database name pattern.
     * @return
     *         List of database names.
     * @throws SQLException
     *             If an error occurs.
     */
    private List<String> getDatabasesByPattern(String dbPattern) throws SQLException {
        String dbFilter = normalizeIdentifierQuoting(dbPattern);
        String query = dbFilter == null ? "SHOW DATABASES" : "SHOW DATABASES LIKE ?";
        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query)) {
            if (dbFilter != null) {
                pStmt.setString(1, dbFilter);
            }
            ResultSet rs = pStmt.executeQuery();

            List<String> dbList = new ArrayList<>();
            while (rs.next()) {
                dbList.add(rs.getString(1));
            }
            Collections.sort(dbList);
            return dbList;
        }
    }

    /**
     * Returns a list of database names that match the specified literal. If the provided dbName is not {@code null}, the returned list of database names is not
     * verified to exist on the server. The results are ordered alphabetically by database name.
     *
     * @param dbName
     *            The database name.
     * @return
     *         List of database names.
     * @throws SQLException
     *             If an error occurs.
     */
    private List<String> getDatabasesByLiteral(String dbName) throws SQLException {
        String dbFilter = normalizeIdentifierCase(normalizeIdentifierQuoting(dbName));
        return dbFilter == null ? getDatabases() : Collections.singletonList(dbFilter);
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

        final String dbFilter = chooseDatabaseTerm(catalog, schema);
        final String tableFilter = normalizeIdentifierQuoting(table);

        final ArrayList<Row> rows = new ArrayList<>();
        try (Statement stmt = getJdbcConnection().getMetaDataSafeStatement()) {
            List<String> dbList = getDatabasesByLiteral(dbFilter);
            for (String db : dbList) {
                ResultSet rs = null;
                try {
                    StringBuilder query = new StringBuilder("SHOW COLUMNS FROM ");
                    query.append(quoteIdentifier(tableFilter));
                    query.append(" FROM ");
                    query.append(quoteIdentifier(db));

                    rs = stmt.executeQuery(query.toString());
                    while (rs.next()) {
                        String keyType = rs.getString("Key");
                        if (keyType != null) {
                            if (StringUtils.startsWithIgnoreCase(keyType, "PRI")) {
                                byte[][] row = new byte[8][];
                                row[0] = n2b(bestRowSession);                                                               // SCOPE
                                row[1] = rs.getBytes("Field");                                                              // COLUMN_NAME
                                TypeDescriptor typeDesc = new TypeDescriptor(rs.getString("Type"), rs.getString("Null"));
                                row[2] = n2b(typeDesc.getJdbcType());                                                       // DATA_TYPE
                                row[3] = s2b(typeDesc.mysqlType.getName());                                                 // TYPE_NAME
                                row[4] = n2b(typeDesc.columnSize);                                                          // COLUMN_SIZE
                                row[5] = n2b(MAX_BUFFER_SIZE);                                                              // BUFFER_LENGTH
                                row[6] = typeDesc.decimalDigits == null ? null : n2b(typeDesc.decimalDigits);               // DECIMAL_DIGITS
                                row[7] = n2b(bestRowNotPseudo);                                                             // PSEUDO_COLUMN
                                rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                            }
                        }
                    }
                } catch (SQLException e) {
                    String sqlState = e.getSQLState(); // Ignore exception if SQLState is 42S02 or 42000 - table/database doesn't exist.
                    int errorCode = e.getErrorCode(); // Ignore exception if ErrorCode is 1146, 1109, or 1149 - table/database doesn't exist.
                    if (!(MysqlErrorNumbers.SQLSTATE_MYSQL_BASE_TABLE_OR_VIEW_NOT_FOUND.equals(sqlState)
                            && (errorCode == MysqlErrorNumbers.ER_NO_SUCH_TABLE || errorCode == MysqlErrorNumbers.ER_UNKNOWN_TABLE)
                            || MysqlErrorNumbers.SQLSTATE_SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION_NO_SUBCLASS.equals(sqlState)
                                    && errorCode == MysqlErrorNumbers.ER_BAD_DB_ERROR)) {
                        throw e;
                    }
                } finally {
                    if (rs != null) {
                        try {
                            rs.close();
                        } catch (SQLException e) {
                            AssertionFailedException.shouldNotHappen(e);
                        }
                        rs = null;
                    }
                }
            }
        }

        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createBestRowIdentifierFields())));
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        final List<String> dbList = chooseBasedOnDatabaseTerm(this::getDatabases, Collections::emptyList);
        final ArrayList<Row> rows = new ArrayList<>(dbList.size());
        for (String db : dbList) {
            byte[][] row = new byte[1][];
            row[0] = s2b(db);                                                                                               // TABLE_CAT
            rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
        }

        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createCatalogsFields())));
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException {
        final String dbFromTerm = chooseDatabaseTerm(catalog, schema);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String tableFilter = normalizeIdentifierQuoting(table);
        final String columnNameFilter = normalizeIdentifierQuoting(columnNamePattern);

        StringBuilder query = new StringBuilder("SELECT c.host, c.db, t.grantor, c.user, c.table_name, c.column_name, c.column_priv");
        query.append(" FROM mysql.columns_priv c, mysql.tables_priv t");
        query.append(" WHERE c.host = t.host AND c.db = t.db AND c.table_name = t.table_name");
        if (dbFilter != null) {
            query.append(" AND c.db = ?");
        }
        query.append(" AND c.table_name = ?");
        if (columnNameFilter != null) {
            query.append(" AND c.column_name LIKE ?");
        }

        final ArrayList<Row> rows = new ArrayList<>();
        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, storesLowerCaseIdentifiers() ? dbFilter.toLowerCase(Locale.ROOT) : dbFilter);
            }
            pStmt.setString(nextIdx++, storesLowerCaseIdentifiers() ? tableFilter.toLowerCase(Locale.ROOT) : tableFilter);
            if (columnNameFilter != null) {
                pStmt.setString(nextIdx, columnNameFilter);
            }

            ResultSet rs = null;
            try {
                rs = pStmt.executeQuery();
                while (rs.next()) {
                    String host = rs.getString(1);
                    String db = rs.getString(2);
                    String grantor = rs.getString(3);
                    String user = rs.getString(4);
                    if (user == null || user.length() == 0) {
                        user = "%";
                    }
                    StringBuilder fullUser = new StringBuilder(user);
                    if (host != null && useHostsInPrivilegesValue()) {
                        fullUser.append("@");
                        fullUser.append(host);
                    }
                    String tableName = rs.getString(5);
                    String columnName = rs.getString(6);
                    String allPrivileges = rs.getString(7);
                    if (allPrivileges != null) {
                        allPrivileges = allPrivileges.toUpperCase(Locale.ROOT);
                        StringTokenizer st = new StringTokenizer(allPrivileges, ",");
                        while (st.hasMoreTokens()) {
                            String privilege = st.nextToken().trim();
                            byte[][] row = new byte[8][];
                            row[0] = chooseBasedOnDatabaseTerm(() -> s2b(db), () -> s2b("def"));                            // TABLE_CAT
                            row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(db));                                  // TABLE_SCHEM
                            row[2] = s2b(tableName);                                                                        // TABLE_NAME
                            row[3] = s2b(columnName);                                                                       // COLUMN_NAME
                            row[4] = s2b(grantor);                                                                          // GRANTOR
                            row[5] = s2b(fullUser.toString());                                                              // GRANTEE
                            row[6] = s2b(privilege);                                                                        // PRIVILEGE
                            row[7] = null;                                                                                  // IS_GRANTABLE
                            rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                        }
                    }
                }
            } finally {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (Exception e) {
                        AssertionFailedException.shouldNotHappen(e);
                    }
                    rs = null;
                }
            }
        }

        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createColumnPrivilegesFields())));
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        final String columnNameFilter = normalizeIdentifierQuoting(columnNamePattern);

        final List<Row> rows = new ArrayList<>();
        try (final Statement stmt = getJdbcConnection().getMetaDataSafeStatement()) {
            SortedMap<String, List<String>> tableNamesPerDb = new TreeMap<>();
            ResultSet tables = null;
            try {
                tables = getTables(catalog, schemaPattern, tableNamePattern, null);
                while (tables.next()) {
                    final String db = tables.getString(chooseBasedOnDatabaseTerm(() -> "TABLE_CAT", () -> "TABLE_SCHEM"));
                    List<String> tableNames = tableNamesPerDb.get(db);
                    if (tableNames == null) {
                        tableNames = new ArrayList<>();
                    }
                    tableNames.add(tables.getString("TABLE_NAME"));
                    tableNamesPerDb.put(db, tableNames);
                }
            } finally {
                if (tables != null) {
                    try {
                        tables.close();
                    } catch (Exception sqlEx) {
                        AssertionFailedException.shouldNotHappen(sqlEx);
                    }
                    tables = null;
                }
            }

            for (String dbName : tableNamesPerDb.keySet()) {
                for (String tableName : tableNamesPerDb.get(dbName)) {
                    ResultSet rs = null;
                    try {
                        StringBuilder query = new StringBuilder("SHOW FULL COLUMNS FROM ");
                        query.append(StringUtils.quoteIdentifier(tableName, getQuoteId(), true));
                        query.append(" FROM ");
                        query.append(StringUtils.quoteIdentifier(dbName, getQuoteId(), true));

                        // Find column ordinals if column name pattern is not '%'.
                        // SHOW COLUMNS does not include ordinal information so another round trip is required to return all columns in the table and compute
                        // their ordinal positions.
                        boolean fixUpOrdinalsRequired = false;
                        Map<String, Integer> ordinalFixUpMap = null;
                        if (columnNameFilter != null && !columnNameFilter.equals("%")) {
                            fixUpOrdinalsRequired = true;
                            ordinalFixUpMap = new HashMap<>();
                            rs = stmt.executeQuery(query.toString());
                            int ordinalPos = 1;
                            while (rs.next()) {
                                String columnName = rs.getString("Field");
                                ordinalFixUpMap.put(columnName, ordinalPos++);
                            }
                            rs.close();
                        }

                        if (columnNameFilter != null) {
                            query.append(" LIKE ");
                            query.append(StringUtils.quoteIdentifier(columnNameFilter, "'", true));
                        }
                        rs = stmt.executeQuery(query.toString());

                        int ordPos = 1;
                        while (rs.next()) {
                            TypeDescriptor typeDesc = new TypeDescriptor(rs.getString("Type"), rs.getString("Null"));

                            byte[][] row = new byte[24][];
                            row[0] = chooseBasedOnDatabaseTerm(() -> s2b(dbName), () -> s2b("def"));                        // TABLE_CAT
                            row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(dbName));                              // TABLE_SCHEM
                            row[2] = s2b(tableName);                                                                        // TABLE_NAME
                            row[3] = rs.getBytes("Field");                                                                  // COLUMN_NAME
                            row[4] = n2b(typeDesc.getJdbcType());                                                           // DATA_TYPE
                            row[5] = s2b(typeDesc.mysqlType.getName());                                                     // TYPE_NAME
                            if (typeDesc.columnSize == null) {
                                row[6] = null;                                                                              // COLUMN_SIZE
                            } else {
                                String collation = rs.getString("Collation");
                                int mbminlen = 1;
                                if (collation != null) {
                                    // Not null collation could only be returned by server for character types, so no need to check type name.
                                    if (collation.indexOf("ucs2") > -1 || collation.indexOf("utf16") > -1) {
                                        mbminlen = 2;
                                    } else if (collation.indexOf("utf32") > -1) {
                                        mbminlen = 4;
                                    }
                                }
                                row[6] = mbminlen == 1 ? n2b(typeDesc.columnSize) : n2b(typeDesc.columnSize / mbminlen);    // COLUMN_SIZE
                            }
                            row[7] = n2b(typeDesc.bufferLength);                                                            // BUFFER_LENGTH
                            row[8] = typeDesc.decimalDigits == null ? null : n2b(typeDesc.decimalDigits);                   // DECIMAL_DIGITS
                            row[9] = n2b(typeDesc.numPrecRadix);                                                            // NUM_PREC_RADIX
                            row[10] = n2b(typeDesc.nullability);                                                            // NULLABLE
                            row[11] = rs.getBytes("Comment");                                                               // REMARKS
                            row[12] = rs.getBytes("Default");                                                               // COLUMN_DEF
                            row[13] = new byte[] { (byte) '0' };                                                            // SQL_DATA_TYPE
                            row[14] = new byte[] { (byte) '0' };                                                            // SQL_DATETIME_SUB
                            if (StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "CHAR") != -1
                                    || StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "BLOB") != -1
                                    || StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "TEXT") != -1
                                    || StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "ENUM") != -1
                                    || StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "SET") != -1
                                    || StringUtils.indexOfIgnoreCase(typeDesc.mysqlType.getName(), "BINARY") != -1) {
                                row[15] = row[6];                                                                           // CHAR_OCTET_LENGTH
                            } else {
                                row[15] = null;                                                                             // CHAR_OCTET_LENGTH
                            }
                            if (!fixUpOrdinalsRequired) {
                                row[16] = n2b(ordPos++);                                                                    // ORDINAL_POSITION
                            } else {
                                String origColName = rs.getString("Field");
                                Integer realOrdinal = ordinalFixUpMap.get(origColName);
                                if (realOrdinal != null) {
                                    row[16] = n2b(realOrdinal);                                                             // ORDINAL_POSITION
                                } else {
                                    throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.10"), MysqlErrorNumbers.SQLSTATE_CONNJ_GENERAL_ERROR,
                                            getExceptionInterceptor());
                                }
                            }
                            row[17] = s2b(typeDesc.isNullable);                                                             // IS_NULLABLE
                            row[18] = null;                                                                                 // SCOPE_CATALOG
                            row[19] = null;                                                                                 // SCOPE_SCHEMA
                            row[20] = null;                                                                                 // SCOPE_TABLE
                            row[21] = null;                                                                                 // SOURCE_DATA_TYPE
                            String extra = rs.getString("Extra");
                            if (extra != null) {
                                row[22] = s2b(StringUtils.indexOfIgnoreCase(extra, "auto_increment") != -1 ? "YES" : "NO"); // IS_AUTOINCREMENT
                                row[23] = s2b(StringUtils.indexOfIgnoreCase(extra, "generated") != -1 ? "YES" : "NO");      // IS_GENERATEDCOLUMN
                            } else {
                                row[22] = s2b("");                                                                          // IS_AUTOINCREMENT
                                row[23] = s2b("");                                                                          // IS_GENERATEDCOLUMN
                            }

                            rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                        }
                    } finally {
                        if (rs != null) {
                            try {
                                rs.close();
                            } catch (Exception e) {
                            }
                            rs = null;
                        }
                    }
                }
            }
        }

        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createColumnsFields())));
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog, String foreignSchema,
            String foreignTable) throws SQLException {
        if (parentTable == null || foreignTable == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String parentDbFromTerm = chooseDatabaseTerm(parentCatalog, parentSchema);
        final String parentDbFilter = normalizeIdentifierQuoting(parentDbFromTerm);
        final String parentTableFilter = normalizeIdentifierQuoting(parentTable);
        final String foreignDbFilter = chooseDatabaseTerm(foreignCatalog, foreignSchema);
        final String foreignTableFilter = normalizeIdentifierQuoting(foreignTable);

        String parentDatabaseName = parentDbFilter == null && nullDatabaseMeansCurrentValue() ? getDatabase() : normalizeIdentifierCase(parentDbFilter);
        String parentTableName = normalizeIdentifierCase(parentTableFilter);

        final ArrayList<Row> rows = new ArrayList<>();
        try (Statement stmt = getJdbcConnection().getMetaDataSafeStatement()) {
            List<String> dbList = getDatabasesByLiteral(foreignDbFilter);
            for (String db : dbList) {
                // Get foreign key information for table.
                List<ForeignKeyConstraintInfo> foreignKeys = extractForeignKeysForTable(db, normalizeIdentifierCase(foreignTableFilter));
                for (ForeignKeyConstraintInfo foreignKey : foreignKeys) {
                    if (!foreignKey.referencingTable.contentEquals(normalizeIdentifierCase(foreignTableFilter))
                            || !foreignKey.referencedTable.contentEquals(parentTableName)
                            || parentDatabaseName != null && !foreignKey.referencedDatabase.contentEquals(parentDatabaseName)) {
                        continue; // This key does not refer to the right table.
                    }

                    int keySeq = 1;
                    Iterator<String> referencingColumns = foreignKey.referencingColumnsList.iterator();
                    Iterator<String> referencedColumns = foreignKey.referencedColumnsList.iterator();
                    while (referencingColumns.hasNext()) {
                        byte[][] row = new byte[14][];
                        row[0] = chooseBasedOnDatabaseTerm(() -> s2b(foreignKey.referencedDatabase), () -> s2b("def"));     // PKTABLE_CAT
                        row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(foreignKey.referencedDatabase));           // PKTABLE_SCHEM
                        row[2] = s2b(foreignKey.referencedTable);                                                           // PKTABLE_NAME
                        row[3] = s2b(StringUtils.unquoteIdentifier(referencedColumns.next(), getQuoteId()));                // PKCOLUMN_NAME
                        row[4] = chooseBasedOnDatabaseTerm(() -> s2b(foreignKey.referencingDatabase), () -> s2b("def"));    // FKTABLE_CAT
                        row[5] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(foreignKey.referencingDatabase));          // FKTABLE_SCHEM
                        row[6] = s2b(foreignKey.referencingTable);                                                          // FKTABLE_NAME
                        row[7] = s2b(StringUtils.unquoteIdentifier(referencingColumns.next(), getQuoteId()));               // FKCOLUMN_NAME
                        row[8] = n2b(keySeq++);                                                                             // KEY_SEQ
                        row[9] = n2b(foreignKey.referentialActionOnUpdate);                                                 // UPDATE_RULE
                        row[10] = n2b(foreignKey.referentialActionOnDelete);                                                // DELETE_RULE
                        row[11] = s2b(foreignKey.constraintName);                                                           // FK_NAME
                        row[12] = null;                                                                                     // PK_NAME, not available
                        row[13] = n2b(importedKeyNotDeferrable);                                                            // DEFERRABILITY
                        rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                    }
                }
            }
        }

        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createForeignKeysFields())));
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

        String parentDatabase = dbFilter == null && nullDatabaseMeansCurrentValue() ? getDatabase() : normalizeIdentifierCase(dbFilter);
        String parentTable = normalizeIdentifierCase(tableFilter);

        final ArrayList<Row> rows = new ArrayList<>();
        try (Statement stmt = getJdbcConnection().getMetaDataSafeStatement()) {
            List<String> dbList = getDatabases();
            for (String db : dbList) {
                // Get foreign key information for table.
                List<ForeignKeyConstraintInfo> foreignKeys = extractForeignKeysForTable(db, null);
                for (ForeignKeyConstraintInfo foreignKey : foreignKeys) {
                    if (!foreignKey.referencedTable.contentEquals(parentTable)
                            || parentDatabase != null && !foreignKey.referencedDatabase.contentEquals(parentDatabase)) {
                        continue; // This key does not refer to the right table.
                    }

                    int keySeq = 1;
                    Iterator<String> referencingColumns = foreignKey.referencingColumnsList.iterator();
                    Iterator<String> referencedColumns = foreignKey.referencedColumnsList.iterator();
                    while (referencingColumns.hasNext()) {
                        byte[][] row = new byte[14][];
                        row[0] = chooseBasedOnDatabaseTerm(() -> s2b(foreignKey.referencedDatabase), () -> s2b("def"));     // PKTABLE_CAT
                        row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(foreignKey.referencedDatabase));           // PKTABLE_SCHEM
                        row[2] = s2b(foreignKey.referencedTable);                                                           // PKTABLE_NAME
                        row[3] = s2b(StringUtils.unquoteIdentifier(referencedColumns.next(), getQuoteId()));                // PKCOLUMN_NAME
                        row[4] = chooseBasedOnDatabaseTerm(() -> s2b(foreignKey.referencingDatabase), () -> s2b("def"));    // FKTABLE_CAT
                        row[5] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(foreignKey.referencingDatabase));          // FKTABLE_SCHEM
                        row[6] = s2b(foreignKey.referencingTable);                                                          // FKTABLE_NAME
                        row[7] = s2b(StringUtils.unquoteIdentifier(referencingColumns.next(), getQuoteId()));               // FKCOLUMN_NAME
                        row[8] = n2b(keySeq++);                                                                             // KEY_SEQ
                        row[9] = n2b(foreignKey.referentialActionOnUpdate);                                                 // UPDATE_RULE
                        row[10] = n2b(foreignKey.referentialActionOnDelete);                                                // DELETE_RULE
                        row[11] = s2b(foreignKey.constraintName);                                                           // FK_NAME
                        row[12] = null;                                                                                     // PK_NAME, not available
                        row[13] = n2b(importedKeyNotDeferrable);                                                            // DEFERRABILITY
                        rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                    }
                }
            }
        }

        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createForeignKeysFields())));
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException {
        return getStoredRoutineColumns(catalog, schemaPattern, functionNamePattern, columnNamePattern, StoredRoutineType.FUNCTION);
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException {
        return getStoredRoutines(catalog, schemaPattern, functionNamePattern, StoredRoutineType.FUNCTION);
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFilter = chooseDatabaseTerm(catalog, schema);
        final String tableFilter = normalizeIdentifierQuoting(table);

        final ArrayList<Row> rows = new ArrayList<>();
        try (Statement stmt = getJdbcConnection().getMetaDataSafeStatement()) {
            List<String> dbList = getDatabasesByLiteral(dbFilter);
            for (String db : dbList) {
                // Get foreign key information for table.
                List<ForeignKeyConstraintInfo> foreignKeys = extractForeignKeysForTable(db, normalizeIdentifierCase(tableFilter));
                for (ForeignKeyConstraintInfo foreignKey : foreignKeys) {
                    int keySeq = 1;
                    Iterator<String> referencingColumns = foreignKey.referencingColumnsList.iterator();
                    Iterator<String> referencedColumns = foreignKey.referencedColumnsList.iterator();
                    while (referencingColumns.hasNext()) {
                        byte[][] row = new byte[14][];
                        row[0] = chooseBasedOnDatabaseTerm(() -> s2b(foreignKey.referencedDatabase), () -> s2b("def"));     // PKTABLE_CAT
                        row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(foreignKey.referencedDatabase));           // PKTABLE_SCHEM
                        row[2] = s2b(foreignKey.referencedTable);                                                           // PKTABLE_NAME
                        row[3] = s2b(StringUtils.unquoteIdentifier(referencedColumns.next(), getQuoteId()));                // PKCOLUMN_NAME
                        row[4] = chooseBasedOnDatabaseTerm(() -> s2b(foreignKey.referencingDatabase), () -> s2b("def"));    // FKTABLE_CAT
                        row[5] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(foreignKey.referencingDatabase));          // FKTABLE_SCHEM
                        row[6] = s2b(foreignKey.referencingTable);                                                          // FKTABLE_NAME
                        row[7] = s2b(StringUtils.unquoteIdentifier(referencingColumns.next(), getQuoteId()));               // FKCOLUMN_NAME
                        row[8] = n2b(keySeq++);                                                                             // KEY_SEQ
                        row[9] = n2b(foreignKey.referentialActionOnUpdate);                                                 // UPDATE_RULE
                        row[10] = n2b(foreignKey.referentialActionOnDelete);                                                // DELETE_RULE
                        row[11] = s2b(foreignKey.constraintName);                                                           // FK_NAME
                        row[12] = null;                                                                                     // PK_NAME, not available
                        row[13] = n2b(importedKeyNotDeferrable);                                                            // DEFERRABILITY
                        rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                    }
                }
            }
        }

        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createForeignKeysFields())));
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFilter = chooseDatabaseTerm(catalog, schema);
        final String tableFilter = normalizeIdentifierQuoting(table);

        final SortedMap<MultiComparable, Row> sortedRows = new TreeMap<>();
        try (Statement stmt = getJdbcConnection().getMetaDataSafeStatement()) {
            List<String> dbList = getDatabasesByLiteral(dbFilter);
            for (String db : dbList) {
                ResultSet rs = null;
                try {
                    // MySQL stores index information in the fields: Table Non_unique Key_name Seq_in_index Column_name Collation Cardinality Sub_part.
                    StringBuilder query = new StringBuilder("SHOW INDEX FROM ");
                    query.append(StringUtils.quoteIdentifier(tableFilter, getQuoteId(), pedanticValue()));
                    query.append(" FROM ");
                    query.append(StringUtils.quoteIdentifier(db, getQuoteId(), true));

                    rs = stmt.executeQuery(query.toString());
                    while (rs.next()) {
                        byte[][] row = new byte[14][];
                        row[0] = chooseBasedOnDatabaseTerm(() -> s2b(db), () -> s2b("def"));                                // TABLE_CAT
                        row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(db));                                      // TABLE_SCHEM
                        row[2] = rs.getBytes("Table");                                                                      // TABLE_NAME
                        boolean indexIsUnique = rs.getInt("Non_unique") == 0;
                        row[3] = !indexIsUnique ? s2b("true") : s2b("false");                                               // NON_UNIQUE
                        row[4] = null;                                                                                      // INDEX_QUALIFIER
                        row[5] = rs.getBytes("Key_name");                                                                   // INDEX_NAME
                        short indexType = tableIndexOther;
                        row[6] = n2b(indexType);                                                                            // TYPE
                        row[7] = rs.getBytes("Seq_in_index");                                                               // ORDINAL_POSITION
                        row[8] = rs.getBytes("Column_name");                                                                // COLUMN_NAME
                        row[9] = rs.getBytes("Collation");                                                                  // ASC_OR_DESC
                        row[10] = n2b(rs.getLong("Cardinality"));                                                           // CARDINALITY
                        row[11] = s2b("0");                                                                                 // PAGES
                        row[12] = null;                                                                                     // FILTER_CONDITION

                        MultiComparable indexInfoKey = new MultiComparable(!indexIsUnique, indexType, rs.getString("Key_name").toLowerCase(Locale.ROOT),
                                rs.getShort("Seq_in_index"));

                        if (unique) {
                            if (indexIsUnique) {
                                sortedRows.put(indexInfoKey, new ByteArrayRow(row, getExceptionInterceptor()));
                            }
                        } else {
                            // All rows match.
                            sortedRows.put(indexInfoKey, new ByteArrayRow(row, getExceptionInterceptor()));
                        }
                    }
                } catch (SQLException e) {
                    String sqlState = e.getSQLState(); // Ignore exception if SQLState is 42S02 or 42000 - table/database doesn't exist.
                    int errorCode = e.getErrorCode(); // Ignore exception if ErrorCode is 1146, 1109, or 1149 - table/database doesn't exist.
                    if (!(MysqlErrorNumbers.SQLSTATE_MYSQL_BASE_TABLE_OR_VIEW_NOT_FOUND.equals(sqlState)
                            && (errorCode == MysqlErrorNumbers.ER_NO_SUCH_TABLE || errorCode == MysqlErrorNumbers.ER_UNKNOWN_TABLE)
                            || MysqlErrorNumbers.SQLSTATE_SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION_NO_SUBCLASS.equals(sqlState)
                                    && errorCode == MysqlErrorNumbers.ER_BAD_DB_ERROR)) {
                        throw e;
                    }
                } finally {
                    if (rs != null) {
                        try {
                            rs.close();
                        } catch (Exception ex) {
                        }
                        rs = null;
                    }
                }
            }
        }

        final ArrayList<Row> rows = new ArrayList<>(sortedRows.values());
        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createIndexInfoFields())));
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFilter = chooseDatabaseTerm(catalog, schema);
        final String tableFilter = normalizeIdentifierQuoting(table);

        final SortedMap<String, Row> sortedRows = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (Statement stmt = getJdbcConnection().getMetaDataSafeStatement()) {
            List<String> dbList = getDatabasesByLiteral(dbFilter);
            for (String db : dbList) {
                ResultSet rs = null;
                try {
                    StringBuilder query = new StringBuilder("SHOW KEYS FROM ");
                    query.append(StringUtils.quoteIdentifier(tableFilter, getQuoteId(), true));
                    query.append(" FROM ");
                    query.append(StringUtils.quoteIdentifier(db, getQuoteId(), true));

                    rs = stmt.executeQuery(query.toString());
                    while (rs.next()) {
                        String keyType = rs.getString("Key_name");
                        if (keyType != null) {
                            if (keyType.equalsIgnoreCase("PRIMARY") || keyType.equalsIgnoreCase("PRI")) {
                                byte[][] row = new byte[6][];
                                row[0] = chooseBasedOnDatabaseTerm(() -> s2b(db), () -> s2b("def"));                        // TABLE_CAT
                                row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(db));                              // TABLE_SCHEM
                                row[2] = s2b(rs.getString("Table"));                                                        // TABLE_NAME
                                String columnName = rs.getString("Column_name");
                                row[3] = s2b(columnName);                                                                   // COLUMN_NAME
                                row[4] = s2b(rs.getString("Seq_in_index"));                                                 // KEY_SEQ
                                row[5] = s2b(keyType);                                                                      // PK_NAME
                                sortedRows.put(columnName, new ByteArrayRow(row, getExceptionInterceptor()));
                            }
                        }
                    }
                } catch (SQLException e) {
                    String sqlState = e.getSQLState(); // Ignore exception if SQLState is 42S02 or 42000 - table/database doesn't exist.
                    int errorCode = e.getErrorCode(); // Ignore exception if ErrorCode is 1146, 1109, or 1149 - table/database doesn't exist.
                    if (!(MysqlErrorNumbers.SQLSTATE_MYSQL_BASE_TABLE_OR_VIEW_NOT_FOUND.equals(sqlState)
                            && (errorCode == MysqlErrorNumbers.ER_NO_SUCH_TABLE || errorCode == MysqlErrorNumbers.ER_UNKNOWN_TABLE)
                            || MysqlErrorNumbers.SQLSTATE_SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION_NO_SUBCLASS.equals(sqlState)
                                    && errorCode == MysqlErrorNumbers.ER_BAD_DB_ERROR)) {
                        throw e;
                    }
                } finally {
                    if (rs != null) {
                        try {
                            rs.close();
                        } catch (Exception ex) {
                        }
                        rs = null;
                    }
                }
            }
        }

        final ArrayList<Row> rows = new ArrayList<>(sortedRows.values());
        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createPrimaryKeysFields())));
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException {
        return getStoredRoutineColumns(catalog, schemaPattern, procedureNamePattern, columnNamePattern, StoredRoutineType.PROCEDURE);
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        return getStoredRoutines(catalog, schemaPattern, procedureNamePattern, StoredRoutineType.PROCEDURE);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        List<String> dbList = chooseBasedOnDatabaseTerm(Collections::emptyList, () -> getDatabasesByPattern(schemaPattern));

        ArrayList<Row> rows = new ArrayList<>(dbList.size());
        for (String db : dbList) {
            byte[][] row = new byte[2][];
            row[0] = s2b(db);                                                                                               // TABLE_SCHEM
            row[1] = s2b("def");                                                                                            // TABLE_CATALOG
            rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
        }

        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createSchemasFields())));
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        final String dbFromTerm = chooseDatabaseTerm(catalog, schemaPattern);
        final String dbFilter = normalizeIdentifierQuoting(dbFromTerm);
        final String tableNameFilter = normalizeIdentifierQuoting(tableNamePattern);

        StringBuilder query = new StringBuilder("SELECT host, db, table_name, grantor, user, table_priv FROM mysql.tables_priv");

        StringBuilder condition = new StringBuilder();
        if (dbFilter != null) {
            condition.append(chooseBasedOnDatabaseTerm(() -> " db = ?", () -> " db LIKE ?"));
        }
        if (tableNameFilter != null) {
            if (condition.length() > 0) {
                condition.append(" AND");
            }
            condition.append(" table_name LIKE ?");
        }

        if (condition.length() > 0) {
            query.append(" WHERE");
            query.append(condition);
        }

        ResultSet rs = null;
        ArrayList<Row> rows = new ArrayList<>();
        try (PreparedStatement pStmt = prepareMetaDataSafeStatement(query.toString())) {
            int nextIdx = 1;
            if (dbFilter != null) {
                pStmt.setString(nextIdx++, storesLowerCaseIdentifiers() ? dbFilter.toLowerCase(Locale.ROOT) : dbFilter);
            }
            if (tableNameFilter != null) {
                pStmt.setString(nextIdx, storesLowerCaseIdentifiers() ? tableNameFilter.toLowerCase(Locale.ROOT) : tableNameFilter);
            }

            try {
                rs = pStmt.executeQuery();
                while (rs.next()) {
                    String host = rs.getString(1);
                    String db = rs.getString(2);
                    String table = rs.getString(3);
                    String grantor = rs.getString(4);
                    String user = rs.getString(5);
                    if (user == null || user.length() == 0) {
                        user = "%";
                    }
                    StringBuilder fullUser = new StringBuilder(user);
                    if (host != null && useHostsInPrivilegesValue()) {
                        fullUser.append("@");
                        fullUser.append(host);
                    }
                    String allPrivileges = rs.getString(6);

                    if (allPrivileges != null) {
                        allPrivileges = allPrivileges.toUpperCase(Locale.ENGLISH);
                        StringTokenizer st = new StringTokenizer(allPrivileges, ",");
                        while (st.hasMoreTokens()) {
                            String privilege = st.nextToken().trim();

                            // Loop through every column in the table.
                            ResultSet columnRs = null;
                            try {
                                columnRs = getColumns(catalog, schemaPattern, StringUtils.quoteIdentifier(table, getQuoteId(), !pedanticValue()), null);
                                while (columnRs.next()) {
                                    byte[][] row = new byte[8][];
                                    row[0] = chooseBasedOnDatabaseTerm(() -> s2b(db), () -> s2b("def"));                    // TABLE_CAT
                                    row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(db));                          // TABLE_SCHEM
                                    row[2] = s2b(table);                                                                    // TABLE_NAME
                                    row[3] = grantor != null ? s2b(grantor) : null;                                         // GRANTOR
                                    row[4] = s2b(fullUser.toString());                                                      // GRANTEE
                                    row[5] = s2b(privilege);                                                                // PRIVILEGE
                                    row[6] = null;                                                                          // IS_GRANTABLE
                                    rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                                }
                            } finally {
                                if (columnRs != null) {
                                    try {
                                        columnRs.close();
                                    } catch (Exception e) {
                                        AssertionFailedException.shouldNotHappen(e);
                                    }
                                    columnRs = null;
                                }
                            }
                        }
                    }
                }
            } finally {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (Exception e) {
                        AssertionFailedException.shouldNotHappen(e);
                    }
                    rs = null;
                }
            }
        }

        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createTablePrivilegesFields())));
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        final String dbFilter = chooseDatabaseTerm(catalog, schemaPattern);
        final String tableNameFilter = normalizeIdentifierQuoting(tableNamePattern);

        final SortedMap<MultiComparable, Row> sortedRows = new TreeMap<>();
        try (Statement stmt = getJdbcConnection().getMetaDataSafeStatement()) {
            List<String> dbList = chooseBasedOnDatabaseTerm(() -> getDatabasesByLiteral(dbFilter), () -> getDatabasesByPattern(dbFilter));
            for (String db : dbList) {
                boolean operatingOnSystemDB = "information_schema".equalsIgnoreCase(db) || "mysql".equalsIgnoreCase(db)
                        || "performance_schema".equalsIgnoreCase(db) || "sys".equalsIgnoreCase(db);

                ResultSet rs = null;
                try {
                    StringBuilder query = new StringBuilder("SHOW FULL TABLES FROM ");
                    query.append(StringUtils.quoteIdentifier(db, getQuoteId(), true));
                    if (tableNameFilter != null) {
                        query.append(" LIKE ");
                        query.append(StringUtils.quoteIdentifier(tableNameFilter, "'", true));
                    }
                    rs = stmt.executeQuery(query.toString());

                    boolean shouldReportTables = false;
                    boolean shouldReportViews = false;
                    boolean shouldReportSystemTables = false;
                    boolean shouldReportSystemViews = false;
                    boolean shouldReportLocalTemporaries = false;

                    if (types == null || types.length == 0) {
                        shouldReportTables = true;
                        shouldReportViews = true;
                        shouldReportSystemTables = true;
                        shouldReportSystemViews = true;
                        shouldReportLocalTemporaries = true;
                    } else {
                        for (String type : types) {
                            if (TableType.TABLE.equalsTo(type)) {
                                shouldReportTables = true;
                            } else if (TableType.VIEW.equalsTo(type)) {
                                shouldReportViews = true;
                            } else if (TableType.SYSTEM_TABLE.equalsTo(type)) {
                                shouldReportSystemTables = true;
                            } else if (TableType.SYSTEM_VIEW.equalsTo(type)) {
                                shouldReportSystemViews = true;
                            } else if (TableType.LOCAL_TEMPORARY.equalsTo(type)) {
                                shouldReportLocalTemporaries = true;
                            }
                        }
                    }

                    while (rs.next()) {
                        boolean toAdd = false;
                        MultiComparable tableKey = null;

                        byte[][] row = new byte[10][];
                        row[0] = chooseBasedOnDatabaseTerm(() -> s2b(db), () -> s2b("def"));                                // TABLE_CAT
                        row[1] = chooseBasedOnDatabaseTerm(() -> null, () -> s2b(db));                                      // TABLE_SCHEM
                        row[2] = rs.getBytes(1);                                                                            // TABLE_NAME
                        String tableType = rs.getString(2);
                        switch (TableType.getTableTypeCompliantWith(tableType)) {
                            case TABLE:
                                if (operatingOnSystemDB && shouldReportSystemTables) {
                                    row[3] = TableType.SYSTEM_TABLE.asBytes();                                              // TABLE_TYPE
                                    tableKey = new MultiComparable(TableType.SYSTEM_TABLE.getName(), db, null, rs.getString(1));
                                    toAdd = true;
                                } else if (!operatingOnSystemDB && shouldReportTables) {
                                    row[3] = TableType.TABLE.asBytes();                                                     // TABLE_TYPE
                                    tableKey = new MultiComparable(TableType.TABLE.getName(), db, null, rs.getString(1));
                                    toAdd = true;
                                }
                                break;
                            case VIEW:
                                if (shouldReportViews) {
                                    row[3] = TableType.VIEW.asBytes();                                                      // TABLE_TYPE
                                    tableKey = new MultiComparable(TableType.VIEW.getName(), db, null, rs.getString(1));
                                    toAdd = true;
                                }
                                break;
                            case SYSTEM_TABLE:
                                if (shouldReportSystemTables) {
                                    row[3] = TableType.SYSTEM_TABLE.asBytes();                                              // TABLE_TYPE
                                    tableKey = new MultiComparable(TableType.SYSTEM_TABLE.getName(), db, null, rs.getString(1));
                                    toAdd = true;
                                }
                                break;
                            case SYSTEM_VIEW:
                                if (shouldReportSystemViews) {
                                    row[3] = TableType.SYSTEM_VIEW.asBytes();                                               // TABLE_TYPE
                                    tableKey = new MultiComparable(TableType.SYSTEM_VIEW.getName(), db, null, rs.getString(1));
                                    toAdd = true;
                                }
                                break;
                            case LOCAL_TEMPORARY:
                                if (shouldReportLocalTemporaries) {
                                    row[3] = TableType.LOCAL_TEMPORARY.asBytes();                                           // TABLE_TYPE
                                    tableKey = new MultiComparable(TableType.LOCAL_TEMPORARY.getName(), db, null, rs.getString(1));
                                    toAdd = true;
                                }
                                break;
                            default:
                                row[3] = TableType.TABLE.asBytes();                                                         // TABLE_TYPE
                                tableKey = new MultiComparable(TableType.TABLE.getName(), db, null, rs.getString(1));
                                toAdd = true;
                                break;
                        }
                        row[4] = new byte[0];                                                                               // REMARKS
                        row[5] = null;                                                                                      // TYPE_CAT
                        row[6] = null;                                                                                      // TYPE_SCHEM
                        row[7] = null;                                                                                      // TYPE_NAME
                        row[8] = null;                                                                                      // SELF_REFERENCING_COL_NAME
                        row[9] = null;                                                                                      // REF_GENERATION

                        if (toAdd) {
                            sortedRows.put(tableKey, new ByteArrayRow(row, getExceptionInterceptor()));
                        }
                    }
                } catch (SQLException e) {
                    if (MysqlErrorNumbers.SQLSTATE_MYSQL_COMMUNICATION_LINK_FAILURE.equals(e.getSQLState())) {
                        throw e;
                    }
                    break;
                } finally {
                    if (rs != null) {
                        try {
                            rs.close();
                        } catch (Exception e) {
                            AssertionFailedException.shouldNotHappen(e);
                        }
                        rs = null;
                    }
                }
            }
        }

        final ArrayList<Row> rows = new ArrayList<>(sortedRows.values());
        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, createTablesFields()));
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        if (table == null) {
            throw SQLError.createSQLException(Messages.getString("DatabaseMetaData.2"), MysqlErrorNumbers.SQLSTATE_CONNJ_ILLEGAL_ARGUMENT,
                    getExceptionInterceptor());
        }

        final String dbFilter = chooseDatabaseTerm(catalog, schema);
        final String tableFilter = normalizeIdentifierQuoting(table);

        final ArrayList<Row> rows = new ArrayList<>();
        try (Statement stmt = getJdbcConnection().getMetaDataSafeStatement()) {
            List<String> dbList = getDatabasesByLiteral(dbFilter);
            for (String db : dbList) {
                ResultSet rs = null;
                try {
                    StringBuilder query = new StringBuilder("SHOW COLUMNS FROM ");
                    query.append(StringUtils.quoteIdentifier(tableFilter, getQuoteId(), true));
                    query.append(" FROM ");
                    query.append(StringUtils.quoteIdentifier(db, getQuoteId(), true));
                    query.append(" WHERE Extra LIKE '%on update CURRENT_TIMESTAMP%'");

                    rs = stmt.executeQuery(query.toString());
                    while (rs.next()) {
                        TypeDescriptor typeDesc = new TypeDescriptor(rs.getString("Type"), rs.getString("Null"));
                        byte[][] row = new byte[8][];
                        row[0] = null;                                                                                      // SCOPE
                        row[1] = rs.getBytes("Field");                                                                      // COLUMN_NAME
                        row[2] = n2b(typeDesc.getJdbcType());                                                               // DATA_TYPE
                        row[3] = s2b(typeDesc.mysqlType.getName());                                                         // TYPE_NAME
                        row[4] = typeDesc.columnSize == null ? null : n2b(typeDesc.columnSize);                             // COLUMN_SIZE
                        row[5] = n2b(typeDesc.bufferLength);                                                                // BUFFER_LENGTH
                        row[6] = typeDesc.decimalDigits == null ? null : n2b(typeDesc.decimalDigits);                       // DECIMAL_DIGITS
                        row[7] = n2b(versionColumnNotPseudo);                                                               // PSEUDO_COLUMN
                        rows.add(new ByteArrayRow(row, getExceptionInterceptor()));
                    }
                } catch (SQLException e) {
                    String sqlState = e.getSQLState(); // Ignore exception if SQLState is 42S02 or 42000 - table/database doesn't exist.
                    int errorCode = e.getErrorCode(); // Ignore exception if ErrorCode is 1146, 1109, or 1149 - table/database doesn't exist.
                    if (!(MysqlErrorNumbers.SQLSTATE_MYSQL_BASE_TABLE_OR_VIEW_NOT_FOUND.equals(sqlState)
                            && (errorCode == MysqlErrorNumbers.ER_NO_SUCH_TABLE || errorCode == MysqlErrorNumbers.ER_UNKNOWN_TABLE)
                            || MysqlErrorNumbers.SQLSTATE_SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION_NO_SUBCLASS.equals(sqlState)
                                    && errorCode == MysqlErrorNumbers.ER_BAD_DB_ERROR)) {
                        throw e;
                    }
                } finally {
                    if (rs != null) {
                        try {
                            rs.close();
                        } catch (Exception e) {
                            AssertionFailedException.shouldNotHappen(e);
                        }
                        rs = null;
                    }
                }
            }
        }

        return getResultSetFactory().createFromResultsetRows(ResultSet.CONCUR_READ_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
                new ResultsetRowsStatic(rows, new DefaultColumnDefinition(createVersionColumnsFields())));
    }

}
