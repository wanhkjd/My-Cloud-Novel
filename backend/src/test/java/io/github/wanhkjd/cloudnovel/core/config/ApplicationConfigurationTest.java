package io.github.wanhkjd.cloudnovel.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionProperties;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.util.unit.DataSize;

/** 验证真实 YAML 的绑定和框架默认会话配置；所有凭据为合成值，不连接基础设施。 */
class ApplicationConfigurationTest {
    @Test
    void yamlIsTheOnlyRuntimeConfigurationAndDdlIsNotPackaged() throws IOException {
        var resources = new PathMatchingResourcePatternResolver();
        assertThat(resources.getResources("classpath*:application*.yml"))
                .extracting(resource -> resource.getFilename())
                .containsExactly("application.yml");
        assertThat(resources.getResources("classpath*:application*.properties")).isEmpty();
        assertThat(resources.getResources("classpath*:application*.yaml")).isEmpty();
        assertThat(loadYaml()).hasSize(1);
        assertThat(new ClassPathResource("schema.sql").exists()).isFalse();
        assertThat(new ClassPathResource("data.sql").exists()).isFalse();
    }

    @Test
    void mysqlRedisAndMapperSettingsBindWithoutEmbeddedFallbacks() throws IOException {
        var environment = configuredEnvironment();
        var binder = Binder.get(environment);
        var datasource = binder.bind("spring.datasource", DataSourceProperties.class).get();
        assertThat(datasource.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(datasource.getUrl()).isEqualTo("jdbc:mysql://127.0.0.1:13306/config_test");
        assertThat(datasource.getUsername()).isEqualTo("synthetic-config-user");
        assertThat(datasource.getPassword()).isEqualTo("synthetic:db#password=literal");
        assertThat(
                        environment.getProperty(
                                "spring.datasource.hikari.connection-timeout", Integer.class))
                .isEqualTo(5000);
        assertThat(
                        binder.bind("spring.sql.init", SqlInitializationProperties.class)
                                .get()
                                .getMode())
                .isEqualTo(DatabaseInitializationMode.NEVER);

        var redis = binder.bind("spring.data.redis", RedisProperties.class).get();
        assertThat(redis.getHost()).isEqualTo("127.0.0.1");
        assertThat(redis.getPort()).isEqualTo(6379);
        assertThat(redis.getDatabase()).isZero();
        assertThat(redis.getUsername()).isEmpty();
        assertThat(redis.getPassword()).isEqualTo("synthetic:redis#password=literal");
        assertThat(redis.getUrl()).isNull();
        assertThat(redis.getSsl().isEnabled()).isFalse();
        assertThat(redis.getTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(redis.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(binder.bind("spring.session", SessionProperties.class).get().getTimeout())
                .isEqualTo(Duration.ofHours(12));
        assertThat(environment.getProperty("spring.session.redis.namespace"))
                .isEqualTo("cloud-novel:session");
        assertThat(environment.getProperty("spring.session.redis.repository-type"))
                .isEqualTo("default");
        assertThat(environment.getProperty("spring.session.redis.configure-action"))
                .isEqualTo("none");
        assertThat(environment.containsProperty("server.servlet.session.timeout")).isFalse();

        var mybatis = binder.bind("mybatis", MybatisProperties.class).get();
        assertThat(mybatis.getMapperLocations()).containsExactly("classpath:mapper/*.xml");
        assertThat(mybatis.getConfiguration().getMapUnderscoreToCamelCase()).isTrue();
        assertThat(mybatis.getConfiguration().getArgNameBasedConstructorAutoMapping()).isTrue();
    }

    @Test
    void privateStorageUploadLimitsAndRestrictedReadinessRemainConfigured() throws IOException {
        var environment = configuredEnvironment();
        var binder = Binder.get(environment);
        var server = binder.bind("server", ServerProperties.class).get();
        assertThat(server.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(server.getPort()).isEqualTo(8080);
        var multipart = binder.bind("spring.servlet.multipart", MultipartProperties.class).get();
        assertThat(multipart.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(25));
        assertThat(multipart.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(26));
        assertThat(environment.getProperty("app.storage-directory")).isEqualTo("./data/books");
        assertThat(environment.getProperty("app.admin.username")).isEqualTo("admin");
        assertThat(environment.getProperty("app.admin.password")).isEmpty();
        assertThat(environment.getProperty("management.endpoints.web.base-path")).isEqualTo("/api");
        assertThat(environment.getProperty("management.endpoints.web.path-mapping.health"))
                .isEqualTo("ready");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
        assertThat(environment.getProperty("management.endpoint.health.show-components"))
                .isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-stacktrace")).isEqualTo("never");
    }

    @Test
    void environmentOverridesAreLiteralAndDoNotNeedAnotherProfile() throws IOException {
        var environment =
                configuredEnvironment()
                        .withProperty("SERVER_PORT", "18080")
                        .withProperty("BOOK_STORAGE", "D:/private books/原件")
                        .withProperty("ADMIN_USERNAME", "synthetic-owner")
                        .withProperty("ADMIN_PASSWORD", "synthetic:admin#password=literal")
                        .withProperty("REDIS_HOST", "redis.example.invalid")
                        .withProperty("REDIS_PORT", "16379")
                        .withProperty("REDIS_USERNAME", "synthetic-redis-user")
                        .withProperty("REDIS_DATABASE", "5")
                        .withProperty("REDIS_NAMESPACE", "synthetic:session")
                        .withProperty("REDIS_SSL", "true");
        var binder = Binder.get(environment);
        assertThat(binder.bind("server", ServerProperties.class).get().getPort()).isEqualTo(18080);
        assertThat(environment.getProperty("app.storage-directory"))
                .isEqualTo("D:/private books/原件");
        assertThat(environment.getProperty("app.admin.username")).isEqualTo("synthetic-owner");
        assertThat(environment.getProperty("app.admin.password"))
                .isEqualTo("synthetic:admin#password=literal");
        var redis = binder.bind("spring.data.redis", RedisProperties.class).get();
        assertThat(redis.getHost()).isEqualTo("redis.example.invalid");
        assertThat(redis.getPort()).isEqualTo(16379);
        assertThat(redis.getUsername()).isEqualTo("synthetic-redis-user");
        assertThat(redis.getDatabase()).isEqualTo(5);
        assertThat(redis.getSsl().isEnabled()).isTrue();
        assertThat(environment.getProperty("spring.session.redis.namespace"))
                .isEqualTo("synthetic:session");
    }

    @ParameterizedTest
    @CsvSource({
        "spring.datasource.url,DB_URL",
        "spring.datasource.username,DB_USERNAME",
        "spring.datasource.password,DB_PASSWORD",
        "spring.data.redis.password,REDIS_PASSWORD"
    })
    void connectionCredentialsHaveNoHardcodedDefaults(String property, String variable)
            throws IOException {
        var environment = yamlEnvironment();
        assertThatThrownBy(() -> environment.getRequiredProperty(property))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(variable);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bootCreatesTheSessionCookieDirectlyFromYaml(boolean secure) {
        // 只替代仓库接口，不启动 Redis；CookieSerializer 必须来自 Boot 自动配置而非自定义 Bean。
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(
                        AutoConfigurations.of(
                                ServletWebServerFactoryAutoConfiguration.class,
                                SessionAutoConfiguration.class))
                .withBean(SessionRepository.class, () -> mock(SessionRepository.class))
                .withPropertyValues(
                        "spring.config.location=classpath:/application.yml",
                        "COOKIE_SECURE=" + secure)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(CookieSerializer.class);
                            var response = new MockHttpServletResponse();
                            context.getBean(CookieSerializer.class)
                                    .writeCookieValue(
                                            new CookieSerializer.CookieValue(
                                                    new MockHttpServletRequest(),
                                                    response,
                                                    "synthetic-session-id"));
                            String cookie = response.getHeader(HttpHeaders.SET_COOKIE);
                            assertThat(cookie)
                                    .contains(
                                            "CLOUDNOVEL_SESSION=",
                                            "Path=/",
                                            "HttpOnly",
                                            "SameSite=Lax")
                                    .doesNotContain("JSESSIONID");
                            if (secure) {
                                assertThat(cookie).contains("Secure");
                            } else {
                                assertThat(cookie).doesNotContain("Secure");
                            }
                        });
    }

    private static MockEnvironment configuredEnvironment() throws IOException {
        return yamlEnvironment()
                .withProperty("DB_URL", "jdbc:mysql://127.0.0.1:13306/config_test")
                .withProperty("DB_USERNAME", "synthetic-config-user")
                .withProperty("DB_PASSWORD", "synthetic:db#password=literal")
                .withProperty("REDIS_PASSWORD", "synthetic:redis#password=literal");
    }

    private static MockEnvironment yamlEnvironment() throws IOException {
        var environment = new MockEnvironment();
        // 不继承本机系统变量，避免把私有运行凭据带入单元测试或错误输出。
        environment.getPropertySources().addLast(loadYaml().getFirst());
        return environment;
    }

    private static List<PropertySource<?>> loadYaml() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
    }
}
