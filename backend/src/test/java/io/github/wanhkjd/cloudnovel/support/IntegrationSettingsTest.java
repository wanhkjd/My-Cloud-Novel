package io.github.wanhkjd.cloudnovel.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class IntegrationSettingsTest {
    private static final String NAMESPACE = "cloud-novel:it:11111111-1111-1111-1111-111111111111";

    @Test
    void neverInheritsRuntimeCredentialsDatabaseOrStorage() {
        Map<String, String> environment = new HashMap<>();
        environment.put("DB_URL", "jdbc:mysql://production/cloud_novel");
        environment.put("DB_USERNAME", "root");
        environment.put("DB_PASSWORD", "runtime-secret-never-use");
        environment.put("REDIS_DATABASE", "0");
        environment.put("REDIS_NAMESPACE", "production");
        environment.put("BOOK_STORAGE", "/private/real-books");
        environment.put("TEST_DB_PASSWORD", "synthetic-test-only");
        var settings =
                IntegrationSettings.properties(environment, "/isolated-test-books", NAMESPACE);
        assertThat(settings.get("spring.datasource.url").toString()).contains("/cloud_novel_test?");
        assertThat(settings.get("spring.datasource.username")).isEqualTo("cloud_novel_test");
        assertThat(settings.get("spring.datasource.password")).isEqualTo("synthetic-test-only");
        assertThat(settings.get("spring.data.redis.database")).isEqualTo(15);
        assertThat(settings).doesNotContainKey("spring.data.redis.url");
        assertThat(settings.get("spring.session.redis.namespace")).isEqualTo(NAMESPACE);
        assertThat(settings.get("app.storage-directory")).isEqualTo("/isolated-test-books");
        assertThat(settings.get("spring.sql.init.mode")).isEqualTo("never");
    }

    @Test
    void missingTestPasswordFailsRatherThanSkippingOrFallingBack() {
        assertThatThrownBy(() -> IntegrationSettings.properties(Map.of(), "/test", NAMESPACE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TEST_DB_PASSWORD");
    }

    @Test
    void refusesAdministrativeOrRuntimeTestAccounts() {
        for (String username : new String[] {"root", "cloud_novel_app", ""}) {
            assertThatThrownBy(
                            () ->
                                    IntegrationSettings.properties(
                                            Map.of(
                                                    "TEST_DB_USERNAME",
                                                    username,
                                                    "TEST_DB_PASSWORD",
                                                    "synthetic"),
                                            "/test",
                                            NAMESPACE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsHostInjectionAndInvalidPortsBeforeOpeningAConnection() {
        for (Map<String, String> input :
                List.of(
                        Map.of(
                                "TEST_MYSQL_HOST",
                                "localhost/cloud_novel?x=1",
                                "TEST_DB_PASSWORD",
                                "synthetic"),
                        Map.of("TEST_MYSQL_PORT", "0", "TEST_DB_PASSWORD", "synthetic"),
                        Map.of("TEST_REDIS_PORT", "65536", "TEST_DB_PASSWORD", "synthetic"),
                        Map.of(
                                "TEST_REDIS_HOST",
                                "redis://localhost",
                                "TEST_DB_PASSWORD",
                                "synthetic"))) {
            assertThatThrownBy(() -> IntegrationSettings.properties(input, "/test", NAMESPACE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void refusesSharedRedisNamespace() {
        assertThatThrownBy(
                        () ->
                                IntegrationSettings.properties(
                                        Map.of("TEST_DB_PASSWORD", "synthetic"),
                                        "/test",
                                        "cloud-novel:session"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redisAutoConfigurationBindsIsolatedSettingsWithoutOpeningAConnection() {
        var settings =
                IntegrationSettings.properties(
                        Map.of(
                                "TEST_DB_PASSWORD",
                                "synthetic-test-only",
                                "TEST_REDIS_HOST",
                                "127.0.0.1",
                                "TEST_REDIS_PORT",
                                "16379",
                                "TEST_REDIS_USERNAME",
                                "test-only-user",
                                "TEST_REDIS_PASSWORD",
                                "test-only-p@ssword"),
                        "/isolated-test-books",
                        NAMESPACE);
        String[] values =
                settings.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .toArray(String[]::new);
        // 只启动 Redis 自动配置，不请求连接；本地没有 Redis / MySQL 也能发现属性绑定错误。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(values)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var details = context.getBean(RedisConnectionDetails.class);
                            assertThat(details.getStandalone().getHost()).isEqualTo("127.0.0.1");
                            assertThat(details.getStandalone().getPort()).isEqualTo(16379);
                            assertThat(details.getStandalone().getDatabase()).isEqualTo(15);
                            assertThat(details.getUsername()).isEqualTo("test-only-user");
                            assertThat(details.getPassword()).isEqualTo("test-only-p@ssword");
                        });
    }

    @Test
    void rejectsConnectionOverridesIncludingEmptyRedisUrlBeforeOpeningConnections() {
        for (String property :
                List.of(
                        "spring.data.redis.url",
                        "spring.data.redis.cluster.nodes",
                        "spring.data.redis.cluster.nodes[0]",
                        "spring.data.redis.sentinel.master",
                        "spring.datasource.jndi-name")) {
            for (String value : List.of("", "runtime-connection-must-not-be-used")) {
                var environment = new MockEnvironment().withProperty(property, value);
                assertThatThrownBy(
                                () ->
                                        TestInfrastructure.rejectExternalConnectionOverrides(
                                                environment))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(property);
            }
        }
    }
}
