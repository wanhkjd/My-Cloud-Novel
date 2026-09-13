package io.github.wanhkjd.cloudnovel.support;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 在创建连接池前覆盖生产配置，测试只使用 TEST_* 凭据、固定测试库和独立原件目录。 */
public class TestInfrastructure
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
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
}
