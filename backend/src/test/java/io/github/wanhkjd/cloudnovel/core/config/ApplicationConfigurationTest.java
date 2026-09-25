package io.github.wanhkjd.cloudnovel.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

/**
 * 验证 application.yml 通过 cloudnovel.* 命名空间（application-dev.yml，被 git 忽略）组合出运行配置，
 * 并保留框架默认会话行为；所有取值为合成值，不连接任何基础设施。
 */
class ApplicationConfigurationTest {
    @Test
    void onlyCommittedYamlAndNoPackagedDdl() throws IOException {
        var resources = new PathMatchingResourcePatternResolver();
        // application-dev.yml 被 git 忽略、随环境存在与否变化，因此只断言已提交的 application.yml。
        assertThat(resources.getResources("classpath*:application*.yml"))
                .extracting(resource -> resource.getFilename())
                .contains("application.yml");
        assertThat(resources.getResources("classpath*:application*.properties")).isEmpty();
        assertThat(resources.getResources("classpath*:application*.yaml")).isEmpty();
        assertThat(new ClassPathResource("schema.sql").exists()).isFalse();
        assertThat(new ClassPathResource("data.sql").exists()).isFalse();
    }

    @Test
    void activeProfileIsDev() throws IOException {
        assertThat(yamlEnvironment().getProperty("spring.profiles.active")).isEqualTo("dev");
    }

    @Test
    void runtimeConnectionSettingsComposeFromDevNamespace() throws IOException {
        var environment = configuredEnvironment();
        var binder = Binder.get(environment);
        var datasource = binder.bind("spring.datasource", DataSourceProperties.class).get();
        assertThat(datasource.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(datasource.getUrl())
                .isEqualTo(
                        "jdbc:mysql://127.0.0.1:13306/config_test"
                                + "?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
                                + "&sslMode=DISABLED&allowPublicKeyRetrieval=true");
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
        // 会话超时改由 Servlet 容器承载；运行期不应再出现 Redis / Spring Session 属性。
        var server = binder.bind("server", ServerProperties.class).get();
        assertThat(server.getServlet().getSession().getTimeout()).isEqualTo(Duration.ofHours(12));
        assertThat(environment.getProperty("spring.data.redis.host")).isNull();
        assertThat(environment.containsProperty("spring.session.timeout")).isFalse();
        assertThat(environment.containsProperty("spring.session.redis.namespace")).isFalse();

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
        assertThat(environment.getProperty("app.admin.username")).isEqualTo("synthetic-owner");
        assertThat(environment.getProperty("app.admin.password"))
                .isEqualTo("synthetic:admin#password=literal");
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
    void devNamespaceOverridesRecomposeRuntimeSettings() throws IOException {
        var environment =
                yamlEnvironment()
                        .withProperty("cloudnovel.datasource.host", "db.example.invalid")
                        .withProperty("cloudnovel.datasource.port", "23306")
                        .withProperty("cloudnovel.datasource.database", "override_db")
                        .withProperty("cloudnovel.admin.username", "synthetic-owner")
                        .withProperty("cloudnovel.storage-directory", "D:/private books/原件");
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo(
                        "jdbc:mysql://db.example.invalid:23306/override_db"
                                + "?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
                                + "&sslMode=DISABLED&allowPublicKeyRetrieval=true");
        assertThat(environment.getProperty("app.storage-directory"))
                .isEqualTo("D:/private books/原件");
        assertThat(environment.getProperty("app.admin.username")).isEqualTo("synthetic-owner");
    }

    @ParameterizedTest
    @CsvSource({
        // url 由 host/port/database 组合而成，首个无法解析的占位符即 host。
        "spring.datasource.url,cloudnovel.datasource.host",
        "spring.datasource.username,cloudnovel.datasource.username",
        "spring.datasource.password,cloudnovel.datasource.password"
    })
    void connectionCredentialsHaveNoHardcodedDefaults(String property, String placeholder)
            throws IOException {
        var environment = yamlEnvironment();
        assertThatThrownBy(() -> environment.getRequiredProperty(property))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(placeholder);
    }

    @Test
    void sessionCookieAndTimeoutBindFromYamlWithoutSpringSession() throws IOException {
        var server =
                Binder.get(configuredEnvironment()).bind("server", ServerProperties.class).get();
        var cookie = server.getServlet().getSession().getCookie();
        assertThat(cookie.getName()).isEqualTo("CLOUDNOVEL_SESSION");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getHttpOnly()).isTrue();
        assertThat(cookie.getSameSite())
                .isEqualTo(org.springframework.boot.web.server.Cookie.SameSite.LAX);
        assertThat(cookie.getSecure()).isFalse();
        assertThat(server.getServlet().getSession().getTimeout()).isEqualTo(Duration.ofHours(12));
    }

    private static MockEnvironment configuredEnvironment() throws IOException {
        return yamlEnvironment()
                .withProperty("cloudnovel.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")
                .withProperty("cloudnovel.datasource.host", "127.0.0.1")
                .withProperty("cloudnovel.datasource.port", "13306")
                .withProperty("cloudnovel.datasource.database", "config_test")
                .withProperty("cloudnovel.datasource.username", "synthetic-config-user")
                .withProperty("cloudnovel.datasource.password", "synthetic:db#password=literal")
                .withProperty("cloudnovel.admin.username", "synthetic-owner")
                .withProperty("cloudnovel.admin.password", "synthetic:admin#password=literal")
                .withProperty("cloudnovel.storage-directory", "./data/books");
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
