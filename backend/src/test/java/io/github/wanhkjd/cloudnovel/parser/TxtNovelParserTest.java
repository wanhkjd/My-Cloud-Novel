package io.github.wanhkjd.cloudnovel.parser;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TxtNovelParserTest {
    private final TxtNovelParser parser = new TxtNovelParser();

    @Test
    void readsUtf8ChaptersWithoutTreatingVolumesAsChapters() {
        var parsed =
                parser.parse(
                        "作者：测试作者\n内容简介：原创测试文本\n第一卷 山中\n第0001章 来信\n这是一段原创测试正文。\n第0002章 回声\n另一段测试正文。"
                                .getBytes(StandardCharsets.UTF_8),
                        "《测试故事》.txt");
        assertThat(parsed.title()).isEqualTo("测试故事");
        assertThat(parsed.author()).isEqualTo("测试作者");
        assertThat(parsed.encoding()).isEqualTo("UTF-8");
        assertThat(parsed.chapters())
                .extracting(TxtNovelParser.Chapter::title)
                .containsExactly("第0001章 来信", "第0002章 回声");
        assertThat(parsed.volumes()).containsExactly("第一卷 山中");
        assertThat(parsed.chapters().getFirst().volume()).isEqualTo("第一卷 山中");
        assertThat(parsed.preface()).contains("原创测试文本");
        assertThat(parsed.chapters().getFirst().content()).isEqualTo("这是一段原创测试正文。");
    }

    @Test
    void readsGbkTextAndDoesNotMistakeAChapterTitleContainingJuanForAVolume() {
        String input = "第一卷 山路\n第0001章 来客\n原创测试正文。\n第0002章 风卷残云\n仍在第一卷。\n第二卷 归途\n第0003章 归来\n故事结束。";
        var parsed =
                parser.parse(input.getBytes(java.nio.charset.Charset.forName("GBK")), "测试.txt");
        assertThat(parsed.encoding()).isEqualTo("GB18030");
        assertThat(parsed.chapters()).hasSize(3);
        assertThat(parsed.volumes()).containsExactly("第一卷 山路", "第二卷 归途");
        assertThat(parsed.chapters().get(1).volume()).isEqualTo("第一卷 山路");
        assertThat(parsed.chapters().get(2).content()).isEqualTo("故事结束。");
    }

    @Test
    void preservesUnstructuredTextAsReadableBodyAndStripsUtf8Bom() {
        var parsed =
                parser.parse(
                        "\uFEFF一封没有章节标题的信。\r\n\r\n这是第二段。".getBytes(StandardCharsets.UTF_8),
                        "随笔.TXT");
        assertThat(parsed.chapters()).hasSize(1);
        assertThat(parsed.chapters().getFirst().title()).isEqualTo("正文");
        assertThat(parsed.chapters().getFirst().content()).isEqualTo("一封没有章节标题的信。\n\n这是第二段。");
        assertThat(parsed.preface()).isEmpty();
    }

    @Test
    void rejectsEmptyBinaryOversizedAndNonTxtFiles() {
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(new byte[0], "空.txt"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse("  \n\t".getBytes(StandardCharsets.UTF_8), "空.txt"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(new byte[] {0, 1, 2, 3}, "伪装.txt"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse(new byte[25 * 1024 * 1024 + 1], "大.txt"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parse("正文".getBytes(StandardCharsets.UTF_8), "book.pdf"));
    }
}
