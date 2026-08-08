package com.intelligent.agent.web.ai.tool.builtin.database;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DatabaseTool 测试（H2 内存库，MySQL 兼容模式）：只读查询/结构/白名单。
 */
class DatabaseToolTest {

    private DatabaseTool tool() {
        return new DatabaseTool("mysql", "mem", 0, "test", "sa", "");
    }

    @Test
    void unconfiguredToolReturnsError() {
        DatabaseTool unconfigured = new DatabaseTool("", "", 0, "", "", "");

        Map<String, Object> result = (Map<String, Object>) unconfigured.execute(
                Map.of("action", "query", "sql", "SELECT 1"));

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(String.valueOf(result.get("message"))).contains("未初始化");
    }

    @Test
    void queryRunsSelectAndReturnsRows() throws Exception {
        String url = initH2();
        DatabaseTool tool = DatabaseTool.fromJdbcUrl(url, "sa", "");

        Map<String, Object> result = (Map<String, Object>) tool.execute(
                Map.of("action", "query", "sql", "SELECT * FROM users"));

        assertThat(result.get("success")).isEqualTo(true);
        assertThat((Integer) result.get("count")).isEqualTo(2);
    }

    @Test
    void rejectsWriteStatements() throws Exception {
        String url = initH2();
        DatabaseTool tool = DatabaseTool.fromJdbcUrl(url, "sa", "");

        Map<String, Object> result = (Map<String, Object>) tool.execute(
                Map.of("action", "query", "sql", "DROP TABLE users"));

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(String.valueOf(result.get("message"))).contains("只允许");
    }

    @Test
    void listTablesAndDescribe() throws Exception {
        String url = initH2();
        DatabaseTool tool = DatabaseTool.fromJdbcUrl(url, "sa", "");

        Map<String, Object> tables = (Map<String, Object>) tool.execute(
                Map.of("action", "list_tables"));
        assertThat((List<?>) tables.get("tables"))
                .extracting(t -> String.valueOf(t).toLowerCase())
                .contains("users");

        Map<String, Object> describe = (Map<String, Object>) tool.execute(
                Map.of("action", "describe", "table", "users"));
        assertThat((List<?>) describe.get("columns")).isNotEmpty();
    }

    private static String initH2() throws Exception {
        String url = "jdbc:h2:mem:db" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO users VALUES (1, 'alice'), (2, 'bob')");
        }
        return url;
    }
}
