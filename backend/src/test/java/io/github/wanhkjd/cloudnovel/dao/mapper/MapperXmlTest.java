package io.github.wanhkjd.cloudnovel.dao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 不建立数据库连接，检查移动包后 XML 的 namespace、实体和投影仍能正确解析。 */
class MapperXmlTest {
    @Test
    void everyMapperMethodHasAResolvableXmlStatement() throws Exception {
        var resources =
                new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml");
        assertThat(resources).hasSize(4);
        var configuration = new Configuration();
        for (var resource : resources) {
            try (var input = resource.getInputStream()) {
                new XMLMapperBuilder(
                                input,
                                configuration,
                                resource.getDescription(),
                                configuration.getSqlFragments())
                        .parse();
            }
        }
        for (var mapper :
                List.of(
                        BookMapper.class,
                        ChapterMapper.class,
                        ReadingMapper.class,
                        BookmarkMapper.class)) {
            assertThat(configuration.hasMapper(mapper)).as(mapper.getName()).isTrue();
            for (var method : mapper.getDeclaredMethods()) {
                String statement = mapper.getName() + "." + method.getName();
                assertThat(configuration.hasStatement(statement)).as(statement).isTrue();
            }
        }
    }
}
