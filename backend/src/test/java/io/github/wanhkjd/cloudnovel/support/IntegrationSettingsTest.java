package io.github.wanhkjd.cloudnovel.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntegrationSettingsTest {
    private static final String NAMESPACE = "cloud-novel:it:11111111-1111-1111-1111-111111111111";

    @Test
    void neverInheritsRuntimeCredentialsDatabaseOrStorage() {
        Map<String, String> environment = new HashMap<>();
        environment.put("DB_URL", "jdbc:mysql://production/cloud_novel");
        environment.put("DB_USERNAME", "root");
        environment.put("DB_PASSWORD", "runtime-secret-never-use");
        environment.put("REDIS_DATABASE", "0");
        environment.put("SPRING_DATA_REDIS_URL", "redis://production:6379/0");
        environment.put("REDIS_NAMESPACE", "production");
        environment.put("BOOK_STORAGE", "/private/real-books");
        environment.put("TEST_DB_PASSWORD", "synthetic-test-only");
        var settings =
                IntegrationSettings.properties(environment, "/isolated-test-books", NAMESPACE);
        assertThat(settings.get("spring.datasource.url").toString()).contains("/cloud_novel_test?");
        assertThat(settings.get("spring.datasource.username")).isEqualTo("cloud_novel_test");
        assertThat(settings.get("spring.datasource.password")).isEqualTo("synthetic-test-only");
        assertThat(settings.get("spring.data.redis.database")).isEqualTo(15);
        assertThat(settings.get("spring.data.redis.url")).isEqualTo("");
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
}
