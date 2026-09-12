package io.github.wanhkjd.cloudnovel.reading;

import io.github.wanhkjd.cloudnovel.novel.Library;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@Service
public class Bookmarks {
    public record Input(@NotBlank String bookId, @Min(0) int chapterIndex, @Min(0) int paragraphIndex,
                        @Size(max=4000) String note, boolean published) {}
    public record Edit(@Size(max=4000) String note, boolean published) {}
    public record Bookmark(String id, String bookId, String bookTitle, int chapterIndex, int paragraphIndex,
                           String chapterTitle, String note, boolean published, long createdAt, long updatedAt) {}
    private final JdbcTemplate jdbc;
    private final Library library;
    public Bookmarks(JdbcTemplate jdbc, Library library) { this.jdbc = jdbc; this.library = library; }
    private List<Bookmark> query(String suffix, Object... args) {
        return jdbc.query("SELECT m.id,m.book_id,b.title,m.chapter_index,m.paragraph_index,c.title,m.note,m.published,m.created_at,m.updated_at FROM bookmarks m JOIN books b ON b.id=m.book_id JOIN chapters c ON c.book_id=m.book_id AND c.chapter_index=m.chapter_index " + suffix,
            (r,n) -> new Bookmark(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getString(6),r.getString(7),r.getBoolean(8),r.getLong(9),r.getLong(10)), args);
    }
    public List<Bookmark> all() { return query("ORDER BY m.updated_at DESC"); }
    public List<Bookmark> forBook(String book, boolean owner) {
        library.readable(book, owner);
        return query("WHERE m.book_id=? " + (owner ? "" : "AND m.published=TRUE ") + "ORDER BY m.chapter_index,m.paragraph_index", book);
    }
    private Bookmark get(String id) {
        return query("WHERE m.id=?", id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "书签不存在。"));
    }
    public Bookmark create(Input input) {
        library.validatePosition(input.bookId(), input.chapterIndex(), input.paragraphIndex());
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try {
            jdbc.update("INSERT INTO bookmarks(id,book_id,chapter_index,paragraph_index,note,published,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                id,input.bookId(),input.chapterIndex(),input.paragraphIndex(),input.note()==null ? "" : input.note().strip(),input.published(),now,now);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这个位置已经有书签，请编辑原书签。");
        }
        return get(id);
    }
    public Bookmark edit(String id, Edit edit) {
        get(id);
        jdbc.update("UPDATE bookmarks SET note=?,published=?,updated_at=? WHERE id=?", edit.note()==null ? "" : edit.note().strip(), edit.published(), System.currentTimeMillis(), id);
        return get(id);
    }
    public void delete(String id) { get(id); jdbc.update("DELETE FROM bookmarks WHERE id=?", id); }
}
