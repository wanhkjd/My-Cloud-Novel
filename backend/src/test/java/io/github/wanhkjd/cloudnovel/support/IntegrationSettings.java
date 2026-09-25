package io.github.wanhkjd.cloudnovel.support;

import java.util.LinkedHashMap;
import java.util.Map;

/** 仅供集成测试使用的连接配置：固定测试库，绝不继承业务库凭据。 */
public final class IntegrationSettings {
    private IntegrationSettings() {}

    public static Map<String, Object> properties(
            Map<String, String> environment, String storageDirectory) {
        String host = host(environment.getOrDefault("TEST_MYSQL_HOST", "127.0.0.1"));
        int port = port(environment.getOrDefault("TEST_MYSQL_PORT", "3306"));
        String username = environment.getOrDefault("TEST_DB_USERNAME", "cloud_novel_test");
        if (!username.equals("cloud_novel_test")) {
            throw new IllegalArgumentException(
                    "Integration tests require the cloud_novel_test account, not a runtime/root account.");
        }
        String password = environment.get("TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "Set TEST_DB_PASSWORD for the dedicated MySQL test database; no database fallback is available.");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put(
                "spring.datasource.url",
                "jdbc:mysql://"
                        + host
                        + ":"
                        + port
                        + "/cloud_novel_test?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=DISABLED&allowPublicKeyRetrieval=true");
        properties.put("spring.datasource.username", username);
        properties.put("spring.datasource.password", password);
        properties.put("spring.sql.init.mode", "never");
        properties.put("server.servlet.session.cookie.secure", false);
        properties.put("app.admin.username", "admin");
        properties.put("app.admin.password", "only-for-isolated-tests-123");
        properties.put("app.storage-directory", storageDirectory);
        return properties;
    }

    private static String host(String value) {
        if (!value.matches("[A-Za-z0-9.-]+")) {
            throw new IllegalArgumentException(
                    "Test hosts must be a hostname or IPv4 address, not a JDBC URL.");
        }
        return value;
    }

    private static int port(String value) {
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Test port must be a number.");
        }
        if (number < 1 || number > 65535) {
            throw new IllegalArgumentException("Test port must be between 1 and 65535.");
        }
        return number;
    }
}
