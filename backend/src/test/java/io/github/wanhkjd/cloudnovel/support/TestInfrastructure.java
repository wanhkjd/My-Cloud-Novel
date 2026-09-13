package io.github.wanhkjd.cloudnovel.support;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/** 在创建连接池前覆盖生产配置，测试只使用 TEST_* 凭据、固定测试库和独立原件目录。 */
public class TestInfrastructure
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        rejectExternalConnectionOverrides(context.getEnvironment());
        try {
            String directory = Files.createTempDirectory("cloud-novel-it-").toString();
            var settings =
                    IntegrationSettings.properties(
                            System.getenv(), directory, "cloud-novel:it:" + UUID.randomUUID());
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource("isolated-test-infrastructure", settings));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot create isolated test storage.", error);
        }
    }

    /** 高优先级 URL / JNDI 等连接方式不能靠空字符串屏蔽，必须在创建任何连接前拒绝。 */
    static void rejectExternalConnectionOverrides(Environment environment) {
        for (String property :
                List.of(
                        "spring.data.redis.url",
                        "spring.data.redis.cluster.nodes",
                        "spring.data.redis.cluster.nodes[0]",
                        "spring.data.redis.sentinel.master",
                        "spring.datasource.jndi-name")) {
            if (environment.containsProperty(property)) {
                throw new IllegalArgumentException(
                        "Unset " + property + " before running isolated integration tests.");
            }
        }
    }
}
