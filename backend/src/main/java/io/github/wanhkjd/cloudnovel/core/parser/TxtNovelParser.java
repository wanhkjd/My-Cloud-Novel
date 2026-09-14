package io.github.wanhkjd.cloudnovel.core.parser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** TXT 解析器：严格识别编码、分卷与章节；不依赖数据库，也不修改原始文件。 */
@Component
public final class TxtNovelParser {
    /** 单文件上限 25 MiB，与 HTTP 上传限制保持一致。 */
    public static final int MAX_BYTES = 25 * 1024 * 1024;

    /** 标题必须独占一行且有明确边界，避免把正文中的“第一章……”误当目录。 */
    private static final Pattern HEADING =
            Pattern.compile(
                    "^第[0-9零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]{1,12}([章回节卷部集])(?:[\\t \\u3000：:、].{0,79})?$");

    /** 只在文件开头查找作者行，避免从正文中猜测作者。 */
    private static final Pattern AUTHOR =
            Pattern.compile("(?m)^[\\t \\u3000]*作者[：:][\\t \\u3000]*(.{1,80})$");

    /** 创建无状态解析器；可由 Spring 注入，也可在单元测试中独立使用。 */
    public TxtNovelParser() {}

    /**
     * 解析得到的单个章节，不持有持久化编号。
     *
     * @param index 从零开始的连续章节索引
     * @param title 原始章节标题
     * @param volume 所属卷名，无卷时为空
     * @param content 标准化换行后的纯文本正文
     * @param characterCount 非空白正文码点数
     */
    public record Chapter(
            int index, String title, String volume, String content, int characterCount) {}

    /**
     * 一次完整解析的不可变结果。
     *
     * @param title 从原始文件名提取的书名
     * @param author 文件开头识别出的作者，缺失时为未知作者
     * @param encoding 实际使用的解码字符集
     * @param preface 第一章前的文本及卷前文本
     * @param volumes 按出现顺序保存的卷名
     * @param chapters 连续索引的章节
     * @param characterCount 所有章节的非空白正文码点数
     */
    public record ParsedNovel(
            String title,
            String author,
            String encoding,
            String preface,
            List<String> volumes,
            List<Chapter> chapters,
            long characterCount) {}

    /**
     * 将 TXT 原始字节解析为章节结构；没有标题时退化为一章“正文”。
     *
     * @param bytes 原始文件字节，最多 25 MiB
     * @param filename 仅用于扩展名校验与提取书名的原始文件名
     * @return 已解码且按顺序分章的小说
     * @throws IllegalArgumentException 文件为空、编码不支持、含二进制控制字符或格式超限
     */
    public ParsedNovel parse(byte[] bytes, String filename) {
        if (filename == null || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".txt"))
            throw new IllegalArgumentException("第一版仅支持 TXT 文件。");
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("文件为空。");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("文件不能超过 25 MiB。");
        var decoded = decode(bytes);
        String text = decoded.text().replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.isBlank()) throw new IllegalArgumentException("文件没有可阅读的内容。");
        if (text.codePoints()
                .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\f'))
            throw new IllegalArgumentException("文件包含二进制控制字符，请上传纯文本 TXT。");
        var chapters = new ArrayList<Chapter>();
        var volumes = new ArrayList<String>();
        var preface = new StringBuilder();
        var content = new StringBuilder();
        String heading = null;
        String volume = "";
        String chapterVolume = "";
        for (String line : text.split("\n", -1)) {
            String candidate = line.strip();
            var match = HEADING.matcher(candidate);
            if (match.matches()) {
                if (heading != null) {
                    chapters.add(chapter(chapters.size(), heading, chapterVolume, content));
                    heading = null;
                } else {
                    preface.append(content);
                }
                content.setLength(0);
                if ("卷部集".contains(match.group(1))) {
                    volume = candidate;
                    volumes.add(volume);
                } else {
                    heading = candidate;
                    chapterVolume = volume;
                }
            } else {
                content.append(line).append('\n');
            }
        }
        if (heading != null)
            chapters.add(chapter(chapters.size(), heading, chapterVolume, content));
        else preface.append(content);
        if (chapters.isEmpty()) {
            chapters.add(chapter(0, "正文", "", new StringBuilder(text)));
            preface.setLength(0);
        }
        if (chapters.size() > 10_000) throw new IllegalArgumentException("章节数量超过 10000，请检查章节格式。");
        String title = filename.replace('\\', '/');
        title = title.substring(title.lastIndexOf('/') + 1).replaceFirst("(?i)\\.txt$", "");
        if (title.startsWith("《") && title.endsWith("》"))
            title = title.substring(1, title.length() - 1);
        var author = AUTHOR.matcher(text.substring(0, Math.min(text.length(), 8192)));
        return new ParsedNovel(
                title,
                author.find() ? author.group(1).strip() : "未知作者",
                decoded.encoding(),
                preface.toString().strip(),
                List.copyOf(volumes),
                List.copyOf(chapters),
                chapters.stream().mapToLong(Chapter::characterCount).sum());
    }

    private record Decoded(String text, String encoding) {}

    private static Decoded decode(byte[] bytes) {
        for (Charset charset : List.of(StandardCharsets.UTF_8, Charset.forName("GB18030"))) {
            try {
                String text =
                        charset.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes))
                                .toString();
                return new Decoded(text, charset.name());
            } catch (CharacterCodingException ignored) {
                // 严格解码失败后再尝试 GB18030，不静默替换乱码，以免造成正文损坏。
            }
        }
        throw new IllegalArgumentException("无法识别文件编码，请另存为 UTF-8 或 GBK 格式的 TXT。");
    }

    private static Chapter chapter(int index, String title, String volume, StringBuilder content) {
        String body = content.toString().strip();
        int count = (int) body.codePoints().filter(c -> !Character.isWhitespace(c)).count();
        return new Chapter(index, title, volume, body, count);
    }
}
