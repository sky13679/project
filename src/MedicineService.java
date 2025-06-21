import java.sql.*;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class MedicineService {
    
    // 建立醫囑與病人資料
    public String createPatientOrder(String patientName, String diagnosis, String phone, String gender, List<Map<String, Object>> medicines) {
        Connection conn = null;
        try {
            conn = DbConnect.getConnection();
            conn.setAutoCommit(false); // 開始交易
            
            System.out.println("開始建立醫囑: " + patientName);
            
            // 檢查病人是否已存在
            int patientId = getOrCreatePatient(conn, patientName, diagnosis, phone, gender);
            System.out.println("病人ID: " + patientId);
            
            // 建立醫囑主表
            String insertOrderSql = "INSERT INTO MedicalOrder (patient_id, created_at) VALUES (?, ?)";
            PreparedStatement orderStmt = conn.prepareStatement(insertOrderSql, Statement.RETURN_GENERATED_KEYS);
            orderStmt.setInt(1, patientId);
            orderStmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            orderStmt.executeUpdate();
            
            ResultSet orderKeys = orderStmt.getGeneratedKeys();
            int orderId = 0;
            if (orderKeys.next()) {
                orderId = orderKeys.getInt(1);
            }
            System.out.println("醫囑ID: " + orderId);
            
            // 建立醫囑明細
            String insertOrderItemSql = "INSERT INTO MedicalOrderItem (order_id, medicine_id, quantity) VALUES (?, ?, ?)";
            PreparedStatement itemStmt = conn.prepareStatement(insertOrderItemSql);
            
            for (Map<String, Object> medicine : medicines) {
                itemStmt.setInt(1, orderId);
                itemStmt.setInt(2, (Integer) medicine.get("medicineId"));
                itemStmt.setInt(3, (Integer) medicine.get("quantity"));
                itemStmt.addBatch();
                System.out.println("加入藥品: " + medicine.get("medicineId") + ", 數量: " + medicine.get("quantity"));
            }
            itemStmt.executeBatch();
            
            conn.commit(); // 提交交易
            System.out.println("醫囑建立成功，交易已提交");
            return "{\"success\": true, \"orderId\": " + orderId + ", \"message\": \"醫囑建立成功\"}";
            
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback(); // 回滾交易
                    System.out.println("交易已回滾");
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            System.err.println("建立醫囑失敗: " + e.getMessage());
            e.printStackTrace();
            return "{\"success\": false, \"message\": \"醫囑建立失敗: " + e.getMessage() + "\"}";
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    private int getOrCreatePatient(Connection conn, String name, String diagnosis, String phone, String gender) throws SQLException {
        // 檢查病人是否存在
        String checkSql = "SELECT id FROM Patient WHERE name = ? AND phone = ?";
        PreparedStatement checkStmt = conn.prepareStatement(checkSql);
        checkStmt.setString(1, name);
        checkStmt.setString(2, phone);
        ResultSet rs = checkStmt.executeQuery();
        
        if (rs.next()) {
            int existingId = rs.getInt("id");
            System.out.println("找到現有病人，ID: " + existingId);
            return existingId;
        } else {
            // 建立新病人
            String insertSql = "INSERT INTO Patient (name, diagnosis, phone, gender) VALUES (?, ?, ?, ?)";
            PreparedStatement insertStmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS);
            insertStmt.setString(1, name);
            insertStmt.setString(2, diagnosis);
            insertStmt.setString(3, phone);
            insertStmt.setString(4, gender);
            insertStmt.executeUpdate();
            
            ResultSet keys = insertStmt.getGeneratedKeys();
            if (keys.next()) {
                int newId = keys.getInt(1);
                System.out.println("建立新病人，ID: " + newId);
                return newId;
            }
        }
        return 0;
    }
    
    // 處理藥局扣藥（包藥）
    public String prepareOrder(int orderId) {
        Connection conn = null;
        try {
            conn = DbConnect.getConnection();
            conn.setAutoCommit(false);
            
            System.out.println("開始包藥，醫囑ID: " + orderId);
            
            // 取得醫囑明細
            String getOrderItemsSql = "SELECT medicine_id, quantity FROM MedicalOrderItem WHERE order_id = ?";
            PreparedStatement stmt = conn.prepareStatement(getOrderItemsSql);
            stmt.setInt(1, orderId);
            ResultSet rs = stmt.executeQuery();
            
            List<Map<String, Object>> orderItems = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> item = new HashMap<>();
                item.put("medicineId", rs.getInt("medicine_id"));
                item.put("quantity", rs.getInt("quantity"));
                orderItems.add(item);
            }
            
            if (orderItems.isEmpty()) {
                return "{\"success\": false, \"message\": \"找不到醫囑資料\"}";
            }
            
            StringBuilder resultMessage = new StringBuilder();
            resultMessage.append("包藥完成詳情:\\n");
            
            // 處理每個藥品的扣藥
            for (Map<String, Object> item : orderItems) {
                int medicineId = (Integer) item.get("medicineId");
                int requiredQuantity = (Integer) item.get("quantity");
                
                // 取得該藥品的藥櫃資料（按數量排序）
                String getCabinetsSql = "SELECT c.id, c.slot_name, c.quantity, m.name as medicine_name " +
                                       "FROM Cabinet c JOIN Medicine m ON c.medicine_id = m.id " +
                                       "WHERE c.medicine_id = ? AND c.quantity > 0 ORDER BY c.quantity DESC";
                PreparedStatement cabinetStmt = conn.prepareStatement(getCabinetsSql);
                cabinetStmt.setInt(1, medicineId);
                ResultSet cabinetRs = cabinetStmt.executeQuery();
                
                List<Map<String, Object>> cabinets = new ArrayList<>();
                String medicineName = "";
                while (cabinetRs.next()) {
                    Map<String, Object> cabinet = new HashMap<>();
                    cabinet.put("id", cabinetRs.getInt("id"));
                    cabinet.put("slotName", cabinetRs.getString("slot_name"));
                    cabinet.put("quantity", cabinetRs.getInt("quantity"));
                    medicineName = cabinetRs.getString("medicine_name");
                    cabinets.add(cabinet);
                }
                
                // 檢查總庫存是否足夠
                int totalAvailable = cabinets.stream().mapToInt(c -> (Integer) c.get("quantity")).sum();
                if (totalAvailable < requiredQuantity) {
                    conn.rollback();
                    return "{\"success\": false, \"message\": \"" + medicineName + " 庫存不足，需要 " + 
                           requiredQuantity + " 個，但只有 " + totalAvailable + " 個可用\"}";
                }
                
                // 執行扣藥
                int remainingQuantity = requiredQuantity;
                resultMessage.append(medicineName).append(" (需要 ").append(requiredQuantity).append(" 個):\\n");
                
                for (Map<String, Object> cabinet : cabinets) {
                    if (remainingQuantity <= 0) break;
                    
                    int cabinetId = (Integer) cabinet.get("id");
                    String slotName = (String) cabinet.get("slotName");
                    int availableQuantity = (Integer) cabinet.get("quantity");
                    
                    int deductQuantity = Math.min(remainingQuantity, availableQuantity);
                    
                    // 更新藥櫃數量
                    String updateCabinetSql = "UPDATE Cabinet SET quantity = quantity - ? WHERE id = ?";
                    PreparedStatement updateStmt = conn.prepareStatement(updateCabinetSql);
                    updateStmt.setInt(1, deductQuantity);
                    updateStmt.setInt(2, cabinetId);
                    updateStmt.executeUpdate();
                    
                    resultMessage.append("  - 從 ").append(slotName).append(" 取出 ").append(deductQuantity).append(" 個\\n");
                    remainingQuantity -= deductQuantity;
                }
            }
            
            conn.commit();
            System.out.println("包藥完成，交易已提交");
            return "{\"success\": true, \"message\": \"" + resultMessage.toString() + "\"}";
            
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            System.err.println("包藥失敗: " + e.getMessage());
            e.printStackTrace();
            return "{\"success\": false, \"message\": \"包藥失敗: " + e.getMessage() + "\"}";
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    // 取得所有病人資料
    public String getAllPatients() {
        try (Connection conn = DbConnect.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT id, name, diagnosis, phone, gender FROM Patient ORDER BY name");
             ResultSet rs = stmt.executeQuery()) {
            
            StringBuilder json = new StringBuilder();
            json.append("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) json.append(",");
                json.append("{")
                    .append("\"id\": ").append(rs.getInt("id")).append(",")
                    .append("\"name\": \"").append(rs.getString("name")).append("\",")
                    .append("\"diagnosis\": \"").append(rs.getString("diagnosis")).append("\",")
                    .append("\"phone\": \"").append(rs.getString("phone")).append("\",")
                    .append("\"gender\": \"").append(rs.getString("gender")).append("\"")
                    .append("}");
                first = false;
            }
            json.append("]");
            return json.toString();
        } catch (SQLException e) {
            System.err.println("取得病人資料失敗: " + e.getMessage());
            e.printStackTrace();
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    // 取得所有藥品資料
    public String getAllMedicines() {
        try (Connection conn = DbConnect.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT id, name, dosage, description FROM Medicine ORDER BY name");
             ResultSet rs = stmt.executeQuery()) {
            
            StringBuilder json = new StringBuilder();
            json.append("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) json.append(",");
                json.append("{")
                    .append("\"id\": ").append(rs.getInt("id")).append(",")
                    .append("\"name\": \"").append(rs.getString("name")).append("\",")
                    .append("\"dosage\": \"").append(rs.getString("dosage")).append("\",")
                    .append("\"description\": \"").append(rs.getString("description")).append("\"")
                    .append("}");
                first = false;
            }
            json.append("]");
            return json.toString();
        } catch (SQLException e) {
            System.err.println("取得藥品資料失敗: " + e.getMessage());
            e.printStackTrace();
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    // 新增藥品
    public String addMedicine(String name, String dosage, String description) {
        String sql = "INSERT INTO Medicine (name, dosage, description) VALUES (?, ?, ?)";
        
        try (Connection conn = DbConnect.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            System.out.println("新增藥品: " + name + ", " + dosage + ", " + description);
            
            stmt.setString(1, name);
            stmt.setString(2, dosage);
            stmt.setString(3, description);
            
            int result = stmt.executeUpdate();
            
            if (result > 0) {
                System.out.println("藥品新增成功");
                return "{\"success\": true, \"message\": \"藥品新增成功\"}";
            } else {
                System.out.println("藥品新增失敗 - 沒有影響任何行");
                return "{\"success\": false, \"message\": \"藥品新增失敗\"}";
            }
            
        } catch (SQLException e) {
            System.err.println("新增藥品失敗: " + e.getMessage());
            e.printStackTrace();
            return "{\"success\": false, \"message\": \"藥品新增失敗: " + e.getMessage() + "\"}";
        }
    }
    
    // 刪除藥品
    public String deleteMedicine(int medicineId) {
        String sql = "DELETE FROM Medicine WHERE id = ?";
        
        try (Connection conn = DbConnect.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            System.out.println("刪除藥品ID: " + medicineId);
            
            stmt.setInt(1, medicineId);
            int result = stmt.executeUpdate();
            
            if (result > 0) {
                System.out.println("藥品刪除成功");
                return "{\"success\": true, \"message\": \"藥品刪除成功\"}";
            } else {
                System.out.println("找不到指定藥品");
                return "{\"success\": false, \"message\": \"找不到指定藥品\"}";
            }
            
        } catch (SQLException e) {
            System.err.println("刪除藥品失敗: " + e.getMessage());
            e.printStackTrace();
            return "{\"success\": false, \"message\": \"藥品刪除失敗: " + e.getMessage() + "\"}";
        }
    }
    
    // 取得所有藥櫃資料
    public String getAllCabinets() {
        try (Connection conn = DbConnect.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT c.id, c.slot_name, c.quantity, c.medicine_id, m.name as medicine_name FROM Cabinet c LEFT JOIN Medicine m ON c.medicine_id = m.id ORDER BY c.slot_name");
             ResultSet rs = stmt.executeQuery()) {
            
            StringBuilder json = new StringBuilder();
            json.append("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) json.append(",");
                json.append("{")
                    .append("\"id\": ").append(rs.getInt("id")).append(",")
                    .append("\"slot_name\": \"").append(rs.getString("slot_name")).append("\",")
                    .append("\"quantity\": ").append(rs.getInt("quantity")).append(",")
                    .append("\"medicine_id\": ").append(rs.getInt("medicine_id")).append(",")
                    .append("\"medicine_name\": \"").append(rs.getString("medicine_name") != null ? rs.getString("medicine_name") : "").append("\"")
                    .append("}");
                first = false;
            }
            json.append("]");
            return json.toString();
        } catch (SQLException e) {
            System.err.println("取得藥櫃資料失敗: " + e.getMessage());
            e.printStackTrace();
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
            // 新增藥櫃
            public String addCabinet(String slotName, int quantity, int medicineId) {
                String sql = "INSERT INTO Cabinet (slot_name, quantity, medicine_id) VALUES (?, ?, ?)";
                
                try (Connection conn = DbConnect.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                    
                    System.out.println("新增藥櫃: " + slotName + ", 數量: " + quantity + ", 藥品ID: " + medicineId);
                    
                    stmt.setString(1, slotName);
                    stmt.setInt(2, quantity);
                    stmt.setInt(3, medicineId);
                    
                    int result = stmt.executeUpdate();
                    
                    if (result > 0) {
                        System.out.println("藥櫃新增成功");
                        return "{\"success\": true, \"message\": \"藥櫃新增成功\"}";
                    } else {
                        System.out.println("藥櫃新增失敗 - 沒有影響任何行");
                        return "{\"success\": false, \"message\": \"藥櫃新增失敗\"}";
                    }
                    
                } catch (SQLException e) {
                    System.err.println("新增藥櫃失敗: " + e.getMessage());
                    e.printStackTrace();
                    return "{\"success\": false, \"message\": \"藥櫃新增失敗: " + e.getMessage() + "\"}";
                }
            }
            
            public String updateCabinet(int id, String slotName, int quantity, int medicineId) {
            String sql = "UPDATE Cabinet SET slot_name = ?, quantity = ?, medicine_id = ? WHERE id = ?";
            
            try (Connection conn = DbConnect.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                System.out.println("修改藥櫃ID: " + id + ", 新資料: " + slotName + ", " + quantity + ", " + medicineId);
                
                stmt.setString(1, slotName);
                stmt.setInt(2, quantity);
                stmt.setInt(3, medicineId);
                stmt.setInt(4, id);
                
                int result = stmt.executeUpdate();
                
                if (result > 0) {
                    System.out.println("藥櫃修改成功");
                    return "{\"success\": true, \"message\": \"藥櫃修改成功\"}";
                } else {
                    System.out.println("找不到指定藥櫃");
                    return "{\"success\": false, \"message\": \"找不到指定藥櫃\"}";
                }
                
            } catch (SQLException e) {
                System.err.println("修改藥櫃失敗: " + e.getMessage());
                e.printStackTrace();
                return "{\"success\": false, \"message\": \"藥櫃修改失敗: " + e.getMessage() + "\"}";
            }
        }
    
    // 刪除藥櫃
    public String deleteCabinet(int id) {
        String sql = "DELETE FROM Cabinet WHERE id = ?";
        
        try (Connection conn = DbConnect.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            System.out.println("刪除藥櫃ID: " + id);
            
            stmt.setInt(1, id);
            int result = stmt.executeUpdate();
            
            if (result > 0) {
                System.out.println("藥櫃刪除成功");
                return "{\"success\": true, \"message\": \"藥櫃刪除成功\"}";
            } else {
                System.out.println("找不到指定藥櫃");
                return "{\"success\": false, \"message\": \"找不到指定藥櫃\"}";
            }
            
        } catch (SQLException e) {
            System.err.println("刪除藥櫃失敗: " + e.getMessage());
            e.printStackTrace();
            return "{\"success\": false, \"message\": \"藥櫃刪除失敗: " + e.getMessage() + "\"}";
        }
    }
}