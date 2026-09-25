package io.github.wanhkjd.cloudnovel.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.wanhkjd.cloudnovel.core.storage.NovelFileStorage;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/** 集成用例只清理专用测试库和本次原件，不执行建库或 DROP。 */
@SpringBootTest
@ContextConfiguration(initializers = TestInfrastructure.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class DatabaseIntegrationTest {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected Environment environment;
    @Autowired private NovelFileStorage storage;

    @BeforeEach
    @AfterEach
    void cleanOnlyIsolatedFixtures() throws IOException {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .as("Never clean a runtime database")
                .isEqualTo("cloud_novel_test");
        for (String id : jdbc.queryForList("SELECT id FROM books", String.class)) {
            storage.delete(id);
        }
        jdbc.update("DELETE FROM books");
    }
}
