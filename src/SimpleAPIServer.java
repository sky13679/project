import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.net.URLDecoder;

public class SimpleAPIServer {
    private static MedicineService medicineService = new MedicineService();
    
    public static void main(String[] args) throws IOException {
        // 測試資料庫連接
        System.out.println("正在測試資料庫連接...");
        if (DbConnect.testConnection()) {
            System.out.println("✅ 資料庫連接成功！");
        } else {
            System.out.println("❌ 資料庫連接失敗！請檢查資料庫設定");
            return;
        }
        
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
        // 靜態文件處理
        server.createContext("/", new StaticFileHandler());
        
        // API 路由
        server.createContext("/create-patient-order", new CreatePatientOrderHandler());
        server.createContext("/prepare-order", new PrepareOrderHandler());
        server.createContext("/cabinet", new CabinetHandler());
        server.createContext("/patients", new PatientsHandler());
        server.createContext("/medicines", new MedicinesHandler());
        
        server.setExecutor(null);
        server.start();
        System.out.println("🚀 伺服器已啟動在 http://localhost:8080");
    }
    
    // 靜態文件處理器
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            
            try {
                String filePath = "." + path;
                byte[] response = Files.readAllBytes(Paths.get(filePath));
                
                String contentType = getContentType(path);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, response.length);
                OutputStream os = exchange.getResponseBody();
                os.write(response);
                os.close();
            } catch (IOException e) {
                String notFound = "404 Not Found";
                exchange.sendResponseHeaders(404, notFound.length());
                OutputStream os = exchange.getResponseBody();
                os.write(notFound.getBytes());
                os.close();
            }
        }
        
        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".css")) return "text/css";
            if (path.endsWith(".js")) return "application/javascript";
            return "text/plain";
        }
    }
    
    // 建立醫囑處理器
    static class CreatePatientOrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleCORSPreflight(exchange);
                return;
            }
            
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String requestBody = getRequestBody(exchange);
                    System.out.println("Received request body: " + requestBody); // Debug log
                    
                    Map<String, Object> data = parseFormData(requestBody);
                    System.out.println("Parsed data: " + data); // Debug log
                    
                    String patientName = (String) data.get("patientName");
                    String diagnosis = (String) data.get("diagnosis");
                    String phone = (String) data.get("phone");
                    String gender = (String) data.get("gender");
                    
                    List<Map<String, Object>> medicines = new ArrayList<>();
                    int medicineCount = Integer.parseInt((String) data.get("medicineCount"));
                    
                    for (int i = 0; i < medicineCount; i++) {
                        String medicineIdStr = (String) data.get("medicineId" + i);
                        String quantityStr = (String) data.get("quantity" + i);
                        
                        if (medicineIdStr != null && quantityStr != null) {
                            Map<String, Object> medicine = new HashMap<>();
                            medicine.put("medicineId", Integer.parseInt(medicineIdStr));
                            medicine.put("quantity", Integer.parseInt(quantityStr));
                            medicines.add(medicine);
                        }
                    }
                    
                    System.out.println("處理醫囑，病人: " + patientName + ", 藥品數量: " + medicines.size());
                    
                    String response = medicineService.createPatientOrder(patientName, diagnosis, phone, gender, medicines);
                    sendResponse(exchange, response);
                } catch (Exception e) {
                    e.printStackTrace();
                    sendResponse(exchange, "{\"success\": false, \"message\": \"處理請求時發生錯誤: " + e.getMessage() + "\"}");
                }
            } else {
                sendResponse(exchange, "{\"error\": \"Only POST method allowed\"}");
            }
        }
    }
    
    // 包藥處理器
    static class PrepareOrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleCORSPreflight(exchange);
                return;
            }
            
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String requestBody = getRequestBody(exchange);
                    System.out.println("包藥請求: " + requestBody);
                    
                    Map<String, Object> data = parseFormData(requestBody);
                    
                    int orderId = Integer.parseInt((String) data.get("orderId"));
                    String response = medicineService.prepareOrder(orderId);
                    sendResponse(exchange, response);
                } catch (Exception e) {
                    e.printStackTrace();
                    sendResponse(exchange, "{\"success\": false, \"message\": \"處理請求時發生錯誤: " + e.getMessage() + "\"}");
                }
            } else {
                sendResponse(exchange, "{\"error\": \"Only POST method allowed\"}");
            }
        }
    }
    
    // 藥櫃處理器
    static class CabinetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleCORSPreflight(exchange);
                return;
            }
            
            String method = exchange.getRequestMethod();
            
            try {
                switch (method) {
                    case "GET":
                        String response = medicineService.getAllCabinets();
                        sendResponse(exchange, response);
                        break;
                        
                    case "POST":
                        String requestBody = getRequestBody(exchange);
                        System.out.println("新增藥櫃請求: " + requestBody);
                        
                        Map<String, Object> data = parseFormData(requestBody);
                        
                        String slotName = (String) data.get("slotName");
                        int quantity = Integer.parseInt((String) data.get("quantity"));
                        int medicineId = Integer.parseInt((String) data.get("medicineId"));
                        
                        String postResponse = medicineService.addCabinet(slotName, quantity, medicineId);
                        sendResponse(exchange, postResponse);
                        break;
                        
                    case "PUT":
                        String putBody = getRequestBody(exchange);
                        System.out.println("修改藥櫃請求: " + putBody);
                        
                        Map<String, Object> putData = parseFormData(putBody);
                        
                        int putId = Integer.parseInt((String) putData.get("id"));
                        String putSlotName = (String) putData.get("slotName");
                        int putQuantity = Integer.parseInt((String) putData.get("quantity"));
                        int putMedicineId = Integer.parseInt((String) putData.get("medicineId"));
                        
                        String putResponse = medicineService.updateCabinet(putId, putSlotName, putQuantity, putMedicineId);
                        sendResponse(exchange, putResponse);
                        break;

                        
                    case "DELETE":
                        String deleteBody = getRequestBody(exchange);
                        System.out.println("刪除藥櫃請求: " + deleteBody);
                        
                        Map<String, Object> deleteData = parseFormData(deleteBody);
                        
                        int deleteId = Integer.parseInt((String) deleteData.get("id"));
                        String deleteResponse = medicineService.deleteCabinet(deleteId);
                        sendResponse(exchange, deleteResponse);
                        break;
                        
                    default:
                        sendResponse(exchange, "{\"error\": \"Method not allowed\"}");
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, "{\"success\": false, \"message\": \"處理請求時發生錯誤: " + e.getMessage() + "\"}");
            }
        }
    }
    
    // 病人資料處理器
    static class PatientsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleCORSPreflight(exchange);
                return;
            }
            
            if ("GET".equals(exchange.getRequestMethod())) {
                try {
                    String response = medicineService.getAllPatients();
                    sendResponse(exchange, response);
                } catch (Exception e) {
                    e.printStackTrace();
                    sendResponse(exchange, "{\"error\": \"獲取病人資料失敗: " + e.getMessage() + "\"}");
                }
            } else {
                sendResponse(exchange, "{\"error\": \"Only GET method allowed\"}");
            }
        }
    }
    
    // 藥品處理器
    static class MedicinesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handleCORSPreflight(exchange);
                return;
            }
            
            String method = exchange.getRequestMethod();
            
            try {
                switch (method) {
                    case "GET":
                        String response = medicineService.getAllMedicines();
                        sendResponse(exchange, response);
                        break;
                        
                    case "POST":
                        String requestBody = getRequestBody(exchange);
                        System.out.println("新增藥品請求: " + requestBody);
                        
                        Map<String, Object> data = parseFormData(requestBody);
                        
                        String name = (String) data.get("name");
                        String dosage = (String) data.get("dosage");
                        String description = (String) data.get("description");
                        
                        String postResponse = medicineService.addMedicine(name, dosage, description);
                        sendResponse(exchange, postResponse);
                        break;
                        
                    case "DELETE":
                        String deleteBody = getRequestBody(exchange);
                        System.out.println("刪除藥品請求: " + deleteBody);
                        
                        Map<String, Object> deleteData = parseFormData(deleteBody);
                        
                        int medicineId = Integer.parseInt((String) deleteData.get("medicineId"));
                        String deleteResponse = medicineService.deleteMedicine(medicineId);
                        sendResponse(exchange, deleteResponse);
                        break;
                        
                    default:
                        sendResponse(exchange, "{\"error\": \"Method not allowed\"}");
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, "{\"success\": false, \"message\": \"處理請求時發生錯誤: " + e.getMessage() + "\"}");
            }
        }
    }
    
    // 工具方法
    private static String getRequestBody(HttpExchange exchange) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody()));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }
        return body.toString();
    }
    
    private static Map<String, Object> parseFormData(String formData) {
        Map<String, Object> result = new HashMap<>();
        if (formData == null || formData.isEmpty()) {
            return result;
        }
        
        String[] pairs = formData.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=");
            if (keyValue.length == 2) {
                try {
                    String key = URLDecoder.decode(keyValue[0], "UTF-8");
                    String value = URLDecoder.decode(keyValue[1], "UTF-8");
                    result.put(key, value);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
        }
        return result;
    }
    
    private static void sendResponse(HttpExchange exchange, String response) throws IOException {
        // 設置CORS標頭
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
        
        byte[] responseBytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }
    
    // CORS預檢請求處理
    private static void handleCORSPreflight(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
        exchange.sendResponseHeaders(204, -1);
    }
}