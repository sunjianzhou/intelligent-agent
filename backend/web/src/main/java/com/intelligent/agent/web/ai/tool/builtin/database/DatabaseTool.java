package com.intelligent.agent.web.ai.tool.builtin.database;

import com.intelligent.agent.web.ai.tool.AgentTool;
import com.intelligent.agent.web.ai.tool.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据库只读查询工具（TODO-110 Task 1）：query / list_tables / describe / sample。
 * 安全：仅允许 SELECT/SHOW/DESCRIBE/DESC 开头的 SQL，写操作一律拒绝；
 * DB_* 未配置时工具返回"未初始化"。
 */
@Slf4j
public class DatabaseTool implements AgentTool {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public DatabaseTool(String dbType, String host, int port, String database,
                        String user, String password) {
        boolean configured = database != null && !database.isBlank()
                && host != null && !host.isBlank();
        if (configured && "mysql".equalsIgnoreCase(dbType)) {
            this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&serverTimezone=UTC&connectTimeout=5000";
            this.username = user;
            this.password = password == null ? "" : password;
        } else {
            this.jdbcUrl = null;
            this.username = null;
            this.password = null;
        }
    }

    /** 测试/高级场景：直接提供 JDBC URL。 */
    public static DatabaseTool fromJdbcUrl(String jdbcUrl, String user, String password) {
        DatabaseTool tool = new DatabaseTool("", "", 0, "", "", "");
        tool.jdbcUrlOverride = jdbcUrl;
        tool.usernameOverride = user;
        tool.passwordOverride = password == null ? "" : password;
        return tool;
    }

    private String jdbcUrlOverride;
    private String usernameOverride;
    private String passwordOverride;

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "database_tool", "数据库只读查询工具。action: query(执行SELECT/SHOW/DESCRIBE SQL),"
                        + " list_tables(列出所有表), describe(查看表结构,需table), sample(样本数据,需table)。"
                        + " 禁止 INSERT/UPDATE/DELETE/DROP 等写操作。", true, null, null);
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        if (jdbcUrl == null && jdbcUrlOverride == null) {
            return Map.of("success", false, "message",
                    "数据库工具未初始化，请检查 DB_* 配置");
        }
        String action = String.valueOf(arguments.getOrDefault("action", "query")).toLowerCase(Locale.ROOT);
        String sql = String.valueOf(arguments.getOrDefault("sql", "")).trim();
        String table = String.valueOf(arguments.getOrDefault("table", "")).trim();
        int limit = arguments.get("limit") instanceof Number
                ? ((Number) arguments.get("limit")).intValue() : 10;
        String effectiveUrl = jdbcUrlOverride != null ? jdbcUrlOverride : jdbcUrl;
        String effectiveUser = jdbcUrlOverride != null ? usernameOverride : username;
        String effectivePassword = jdbcUrlOverride != null ? passwordOverride : password;
        try (Connection connection = DriverManager.getConnection(
                effectiveUrl, effectiveUser, effectivePassword)) {
            return switch (action) {
                case "list_tables" -> listTables(connection);
                case "describe" -> table.isEmpty()
                        ? Map.of("success", false, "message", "describe 操作需要提供 table 参数")
                        : describe(connection, table);
                case "sample" -> table.isEmpty()
                        ? Map.of("success", false, "message", "sample 操作需要提供 table 参数")
                        : sample(connection, table, Math.min(Math.max(1, limit), 100));
                case "query" -> sql.isEmpty()
                        ? Map.of("success", false, "message", "query 操作需要提供 sql 参数")
                        : query(connection, sql);
                default -> Map.of("success", false, "message",
                        "不支持的 action: " + action + "，可选: query / list_tables / describe / sample");
            };
        } catch (Exception e) {
            log.warn("数据库工具执行失败: {}", e.getMessage());
            return Map.of("success", false, "message", "数据库执行失败: " + e.getMessage());
        }
    }

    private Map<String, Object> query(Connection connection, String sql) {
        String trimmed = sql.trim().toLowerCase(Locale.ROOT);
        if (!(trimmed.startsWith("select") || trimmed.startsWith("show")
                || trimmed.startsWith("describe") || trimmed.startsWith("desc"))) {
            return Map.of("success", false, "message", "只允许 SELECT/SHOW/DESCRIBE 只读 SQL");
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            List<Map<String, Object>> rows = toRows(rs);
            return Map.of("success", true, "rows", rows, "count", rows.size());
        } catch (Exception e) {
            return Map.of("success", false, "message", "SQL 执行失败: " + e.getMessage());
        }
    }

    private Map<String, Object> listTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW TABLES")) {
            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            return Map.of("success", true, "tables", tables, "count", tables.size());
        }
    }

    private Map<String, Object> describe(Connection connection, String table) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE "
                             + "FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE UPPER(TABLE_NAME) = UPPER('" + table.replace("'", "") + "')")) {
            List<Map<String, Object>> raw = toRows(rs);
            List<Map<String, Object>> columns = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("Field", row.get("COLUMN_NAME"));
                column.put("Type", row.get("DATA_TYPE"));
                column.put("Null", row.get("IS_NULLABLE"));
                columns.add(column);
            }
            return Map.of("success", true, "columns", columns, "table", table);
        } catch (Exception e) {
            return Map.of("success", false, "message", "查看表结构失败: " + e.getMessage());
        }
    }

    private Map<String, Object> sample(Connection connection, String table, int limit) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT * FROM " + quote(table) + " LIMIT " + limit)) {
            List<Map<String, Object>> rows = toRows(rs);
            return Map.of("success", true, "rows", rows, "count", rows.size(), "table", table);
        } catch (Exception e) {
            return Map.of("success", false, "message", "样本查询失败: " + e.getMessage());
        }
    }

    private static List<Map<String, Object>> toRows(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "") + "`";
    }
}
