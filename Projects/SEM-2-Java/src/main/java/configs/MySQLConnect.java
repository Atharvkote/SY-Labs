package configs;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class MySQLConnect {

    // Method to establish and return a MySQL connection
    public static Connection getConnection() {
        // Database credentials
        String url = "jdbc:mysql://localhost:3306/atharva";
        String username = "root";
        String password = "atharva99";

        Connection connection = null;
        try {
            // Try establishing a connection
            connection = DriverManager.getConnection(url, username, password);
            System.out.println("Connection successful!");
        } catch (SQLException e) {
            // Handle connection failure
            System.out.println("Connection failed!");
            e.printStackTrace();
        }

        return connection; // Return the connection object


    }
}

