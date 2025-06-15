import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DbConnect {
    private static final String DB_URL = "jdbc:mysql://localhost:3306/medicine_db?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "123456";
    
    // 改為每次都建立新連接，避免單例連接問題
    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            Connection conn = DriverManager.getConnection(DB_URL, USERNAME, PASSWORD);
            
            // 設置為自動提交模式（預設是true，但明確設置）
            conn.setAutoCommit(true);
            
            System.out.println("資料庫連接成功");
            return conn;
        } catch (ClassNotFoundException e) {
            System.err.println("MySQL Driver not found: " + e.getMessage());
            throw new SQLException("Driver not found", e);
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            throw e;
        }
    }
    
    // 測試連接的方法
    public static boolean testConnection() {
        try (Connection conn = getConnection()) {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            System.err.println("Connection test failed: " + e.getMessage());
            return false;
        }
    }
}