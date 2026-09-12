package io.github.wanhkjd.cloudnovel.novel;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** TXT decoding and chapter recognition live behind one format-independent result. */
public final class TxtNovelParser {
    private static final Pattern HEADING = Pattern.compile("^第[0-9零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]{1,12}([章回节卷部集])(?:[\\t \\u3000：:、].{0,79})?$");
    private static final Pattern AUTHOR = Pattern.compile("(?m)^[\\t \\u3000]*作者[：:][\\t \\u3000]*(.{1,80})$");
    public record Chapter(int index, String title, String volume, String content, int characterCount) {}
    public record ParsedNovel(String title, String author, String encoding, String preface, List<String> volumes, List<Chapter> chapters, long characterCount) {}

    public static final int MAX_BYTES = 25 * 1024 * 1024;
    public ParsedNovel parse(byte[] bytes, String filename) {
        if (filename == null || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".txt"))
            throw new IllegalArgumentException("第一版仅支持 TXT 文件。");
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("文件为空。");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("文件不能超过 25 MiB。");
        var decoded = decode(bytes);
        String text = decoded.text().replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.isBlank()) throw new IllegalArgumentException("文件没有可阅读的内容。");
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\f'))
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
        if (heading != null) chapters.add(chapter(chapters.size(), heading, chapterVolume, content));
        else preface.append(content);
        if (chapters.isEmpty()) {
            chapters.add(chapter(0, "正文", "", new StringBuilder(text)));
            preface.setLength(0);
        }
        if (chapters.size() > 10_000) throw new IllegalArgumentException("章节数量超过 10000，请检查章节格式。");
        String title = filename.replace('\\', '/');
        title = title.substring(title.lastIndexOf('/') + 1).replaceFirst("(?i)\\.txt$", "");
        if (title.startsWith("《") && title.endsWith("》")) title = title.substring(1, title.length() - 1);
        var author = AUTHOR.matcher(text.substring(0, Math.min(text.length(), 8192)));
        return new ParsedNovel(title, author.find() ? author.group(1).strip() : "未知作者", decoded.encoding(), preface.toString().strip(), List.copyOf(volumes), List.copyOf(chapters), chapters.stream().mapToLong(Chapter::characterCount).sum());
    }

    private record Decoded(String text, String encoding) {}
    private static Decoded decode(byte[] bytes) {
        for (Charset charset : List.of(StandardCharsets.UTF_8, Charset.forName("GB18030"))) {
            try {
                String text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                return new Decoded(text, charset.name());
            } catch (CharacterCodingException ignored) {
                // Try the next supported encoding rather than silently replacing characters.
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
