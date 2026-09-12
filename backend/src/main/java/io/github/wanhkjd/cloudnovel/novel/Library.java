package io.github.wanhkjd.cloudnovel.novel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Owns import atomicity, private storage and publication policy; callers never build storage paths. */
@Service
public class Library {
    public record Book(String id, String title, String author, String description, String encoding,
                       int chapterCount, int volumeCount, long characterCount, boolean catalogPublished,
                       boolean textPublished, long createdAt, boolean canRead, String preface) {}
    public record Edit(@NotBlank @Size(max=120) String title, @NotBlank @Size(max=100) String author,
                       @Size(max=4000) String description, boolean catalogPublished, boolean textPublished) {}
    public record ChapterSummary(int index, String title, String volume, int characterCount) {}
    public record Chapter(int index, String title, String volume, List<String> paragraphs, int characterCount) {}
    public record Download(Book book, byte[] bytes) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Path storage;
    private final TxtNovelParser parser = new TxtNovelParser();

    public Library(JdbcTemplate jdbc, TransactionTemplate transactions, Environment environment) throws IOException {
        this.jdbc = jdbc; this.transactions = transactions;
        this.storage = Path.of(environment.getRequiredProperty("app.storage-directory")).toAbsolutePath().normalize();
        Files.createDirectories(storage);
    }

    private RowMapper<Book> mapper(boolean owner, boolean includeText) {
        return (r, n) -> {
            boolean canRead = owner || (r.getBoolean("catalog_published") && r.getBoolean("text_published"));
            return new Book(r.getString("id"), r.getString("title"), r.getString("author"), r.getString("description"),
                r.getString("encoding"), r.getInt("chapter_count"), r.getInt("volume_count"), r.getLong("character_count"),
                r.getBoolean("catalog_published"), r.getBoolean("text_published"), r.getLong("created_at"), canRead,
                includeText && canRead ? r.getString("preface") : "");
        };
    }
    public List<Book> list(boolean owner) {
        return jdbc.query("SELECT id,title,author,description,encoding,chapter_count,volume_count,character_count,catalog_published,text_published,created_at FROM books "
            + (owner ? "" : "WHERE catalog_published=TRUE ") + "ORDER BY created_at DESC, id", mapper(owner, false));
    }
    public Book book(String id, boolean owner) {
        return jdbc.query("SELECT * FROM books WHERE id=?" + (owner ? "" : " AND catalog_published=TRUE"), mapper(owner, true), id)
            .stream().findFirst().orElseThrow(Library::notFound);
    }
    public Book readable(String id, boolean owner) {
        Book book = book(id, owner);
        if (!book.canRead()) throw notFound();
        return book;
    }
    public List<ChapterSummary> chapters(String id, boolean owner) {
        readable(id, owner);
        return jdbc.query("SELECT chapter_index,title,volume,character_count FROM chapters WHERE book_id=? ORDER BY chapter_index",
            (r, n) -> new ChapterSummary(r.getInt(1), r.getString(2), r.getString(3), r.getInt(4)), id);
    }
    public Chapter chapter(String id, int index, boolean owner) {
        readable(id, owner);
        return jdbc.query("SELECT chapter_index,title,volume,content,character_count FROM chapters WHERE book_id=? AND chapter_index=?",
            (r, n) -> new Chapter(r.getInt(1), r.getString(2), r.getString(3), paragraphs(r.getString(4)), r.getInt(5)), id, index)
            .stream().findFirst().orElseThrow(Library::notFound);
    }
    private static List<String> paragraphs(String content) {
        return content.lines().map(String::strip).filter(s -> !s.isBlank()).toList();
    }
    public void validatePosition(String id, int chapter, int paragraph) {
        var content = chapter(id, chapter, true);
        if (paragraph < 0 || paragraph >= Math.max(1, content.paragraphs().size()))
            throw new IllegalArgumentException("阅读位置超出章节范围。");
    }
    public Book upload(byte[] bytes, String filename) throws IOException {
        var parsed = parser.parse(bytes, filename);
        if (parsed.title().isBlank() || parsed.title().length() > 120)
            throw new IllegalArgumentException("文件名（书名）需为 1 至 120 个字符。");
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        String id = UUID.randomUUID().toString();
        Path file = storedFile(id);
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        try {
            return transactions.execute(status -> {
                jdbc.update("INSERT INTO books(id,title,author,description,encoding,chapter_count,volume_count,character_count,preface,sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    id, parsed.title(), parsed.author(), "", parsed.encoding(), parsed.chapters().size(), parsed.volumes().size(), parsed.characterCount(), parsed.preface(), hash, System.currentTimeMillis());
                jdbc.batchUpdate("INSERT INTO chapters(book_id,chapter_index,title,volume,content,character_count) VALUES(?,?,?,?,?,?)", parsed.chapters(), 200,
                    (statement, chapter) -> { statement.setString(1, id); statement.setInt(2, chapter.index()); statement.setString(3, chapter.title());
                        statement.setString(4, chapter.volume()); statement.setString(5, chapter.content()); statement.setInt(6, chapter.characterCount()); });
                return book(id, true);
            });
        } catch (RuntimeException error) {
            try { Files.deleteIfExists(file); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            if (error instanceof DuplicateKeyException) throw new ResponseStatusException(HttpStatus.CONFLICT, "同一份小说已经导入，无需重复上传。");
            throw error;
        }
    }
    public Book edit(String id, Edit edit) {
        book(id, true);
        if (edit.textPublished() && !edit.catalogPublished()) throw new IllegalArgumentException("公开正文前，请先公开书目。");
        jdbc.update("UPDATE books SET title=?,author=?,description=?,catalog_published=?,text_published=? WHERE id=?",
            edit.title().strip(), edit.author().strip(), edit.description() == null ? "" : edit.description().strip(), edit.catalogPublished(), edit.textPublished(), id);
        return book(id, true);
    }
    public void delete(String id) {
        book(id, true);
        jdbc.update("DELETE FROM books WHERE id=?", id);
        try { Files.deleteIfExists(storedFile(id)); }
        catch (IOException error) { LoggerFactory.getLogger(Library.class).warn("Private file cleanup failed for book {}", id, error); }
    }
    public Download download(String id, boolean owner) throws IOException {
        var book = readable(id, owner);
        Path file = storedFile(id);
        if (!Files.isRegularFile(file)) throw notFound();
        return new Download(book, Files.readAllBytes(file));
    }
    private Path storedFile(String id) {
        if (!UUID.fromString(id).toString().equals(id)) throw notFound();
        return storage.resolve(id + ".txt");
    }
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "书籍或章节不存在，或尚未公开。");
    }
}
