package io.github.wanhkjd.cloudnovel.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class IntegrationSettingsTest {

    @Test
    void neverInheritsRuntimeCredentialsDatabaseOrStorage() {
        Map<String, String> environment = new HashMap<>();
        environment.put("DB_URL", "jdbc:mysql://production/cloud_novel");
        environment.put("DB_USERNAME", "root");
        environment.put("DB_PASSWORD", "runtime-secret-never-use");
        environment.put("BOOK_STORAGE", "/private/real-books");
        environment.put("TEST_DB_PASSWORD", "synthetic-test-only");
        var settings = IntegrationSettings.properties(environment, "/isolated-test-books");
        assertThat(settings.get("spring.datasource.url").toString()).contains("/cloud_novel_test?");
        assertThat(settings.get("spring.datasource.username")).isEqualTo("cloud_novel_test");
        assertThat(settings.get("spring.datasource.password")).isEqualTo("synthetic-test-only");
        assertThat(settings.get("app.storage-directory")).isEqualTo("/isolated-test-books");
        assertThat(settings.get("spring.sql.init.mode")).isEqualTo("never");
    }

    @Test
    void missingTestPasswordFailsRatherThanSkippingOrFallingBack() {
        assertThatThrownBy(() -> IntegrationSettings.properties(Map.of(), "/test"))
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
                                            "/test"))
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
                        Map.of("TEST_MYSQL_PORT", "0", "TEST_DB_PASSWORD", "synthetic"))) {
            assertThatThrownBy(() -> IntegrationSettings.properties(input, "/test"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsConnectionOverridesBeforeOpeningConnections() {
        for (String property : List.of("spring.datasource.jndi-name")) {
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
