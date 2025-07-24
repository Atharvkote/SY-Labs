package excelo;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
//import configs.MySQLConnect;
import configs.MySQLConnect;

public class DataBaseManager {

    // Method to create a table dynamically
    public static void createTable(Connection conn, String tableName, List<String> columnNames) throws Exception {
        StringBuilder createTableSql = new StringBuilder("CREATE TABLE `").append(tableName).append("` (");

        for (int i = 0; i < columnNames.size(); i++) {
            createTableSql.append("`").append(columnNames.get(i)).append("` VARCHAR(255)");  // Wrap column name in backticks
            if (i < columnNames.size() - 1) createTableSql.append(", ");
        }

        createTableSql.append(")");

        // Execute the CREATE TABLE query
        Statement stmt = conn.createStatement();
        stmt.execute(createTableSql.toString());
    }

    // Method to insert data into the database dynamically
    public static void insertIntoDatabase(Connection conn, String tableName, List<String> columnNames, List<List<Object>> data) throws Exception {
        StringBuilder sql = new StringBuilder("INSERT INTO `").append(tableName).append("` (");

        // Add columns to the SQL query
        for (int i = 0; i < columnNames.size(); i++) {
            sql.append("`").append(columnNames.get(i)).append("`");  // Wrap column name in backticks
            if (i < columnNames.size() - 1) sql.append(", ");
        }
        sql.append(") VALUES (");

        // Add placeholders for values
        for (int i = 0; i < columnNames.size(); i++) {
            sql.append("?");
            if (i < columnNames.size() - 1) sql.append(", ");
        }
        sql.append(")");

        PreparedStatement stmt = conn.prepareStatement(sql.toString());

        // Insert data into the table
        for (List<Object> row : data) {
            for (int col = 0; col < columnNames.size(); col++) {
                stmt.setObject(col + 1, row.get(col));
            }
            stmt.addBatch();
        }
        stmt.executeBatch();
    }
    // Method to fetch all Excel file names from the database
    public static List<String> getAllExcelFiles(Connection conn) throws SQLException {
        List<String> files = new ArrayList<>();
        String query = "SELECT file_name FROM excel_files";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                files.add(rs.getString("file_name"));
            }
        }
        return files;
    }

    // Method to retrieve Excel file data based on the file name
    public static byte[] getExcelFileData(Connection conn, String fileName) throws SQLException {
        String query = "SELECT file_path FROM excel_files WHERE file_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, fileName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String filePath = rs.getString("file_path");
                return readFileData(filePath);
            }
        }
        return null; // File not found
    }
    public static boolean tableExists(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    // Helper method to read the file content as bytes (for file storage)
    private static byte[] readFileData(String filePath) throws SQLException {
        File file = new File(filePath);
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return data;
        } catch (Exception ex) {
            throw new SQLException("Error reading file: " + ex.getMessage());
        }
    }
}
