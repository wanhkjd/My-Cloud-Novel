package io.github.wanhkjd.cloudnovel.parser;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 仅在显式提供环境变量时验收本地小说，真实文件不作为可提交测试夹具。 */
@EnabledIfEnvironmentVariable(named = "NOVEL_TEST_FILE", matches = ".+")
class LocalNovelTest {
    @Test
    void parsesTheProvidedLocalNovelWithoutChangingItsBytes() throws Exception {
        Path file = Path.of(System.getenv("NOVEL_TEST_FILE"));
        byte[] bytes = Files.readAllBytes(file);
        String before =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        long started = System.nanoTime();
        var parsed = new TxtNovelParser().parse(bytes, file.getFileName().toString());
        assertThat(parsed.title()).isEqualTo("人道至尊");
        assertThat(parsed.author()).isEqualTo("宅猪");
        assertThat(parsed.encoding()).isEqualTo("GB18030");
        assertThat(parsed.chapters()).hasSize(1507);
        assertThat(parsed.volumes()).hasSize(3);
        for (int i = 0; i < 1507; i++) {
            var number =
                    java.util.regex.Pattern.compile("^第([0-9]+)章")
                            .matcher(parsed.chapters().get(i).title());
            assertThat(number.find()).isTrue();
            assertThat(Integer.parseInt(number.group(1))).isEqualTo(i + 1);
            assertThat(parsed.chapters().get(i).content()).isNotBlank();
        }
        assertThat(parsed.characterCount()).isGreaterThan(4_000_000);
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        System.out.printf(
                "Local novel: bytes=%d, encoding=%s, chapters=%d, volumes=%d, bodyCharacters=%d, elapsedMs=%d, sha256=%s%n",
                bytes.length,
                parsed.encoding(),
                parsed.chapters().size(),
                parsed.volumes().size(),
                parsed.characterCount(),
                (System.nanoTime() - started) / 1_000_000,
                before);
    }
}
