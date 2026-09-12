package io.github.wanhkjd.cloudnovel.reading;

import io.github.wanhkjd.cloudnovel.novel.Library;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The one owner's journal. Guests never use this persistence boundary. */
@Service
public class ReadingLog {
    public record Position(@Min(0) int chapterIndex, @Min(0) int paragraphIndex) {}
    public record Progress(String bookId, String bookTitle, int chapterCount, int chapterIndex,
                           int paragraphIndex, String chapterTitle, long updatedAt) {}
    public record SessionInput(@NotBlank String bookId, @Min(0) int chapterIndex, @Min(0) int paragraphIndex,
                               @Min(1) @Max(1800) int elapsedSeconds, @Min(1) long startedAt) {}
    public record Session(String id, String bookId, String bookTitle, int chapterIndex, int paragraphIndex,
                          String chapterTitle, long startedAt, int elapsedSeconds, long updatedAt) {}
    public record Day(String date, long seconds) {}
    public record BookTime(String bookId, String title, long seconds) {}
    public record Stats(long totalSeconds, long todaySeconds, long sessionCount, List<Day> days, List<BookTime> books) {}
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final Library library;
    private final TransactionTemplate transactions;
    public ReadingLog(JdbcTemplate jdbc, Library library, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.library = library; this.transactions = transactions;
    }
    private List<Progress> queryProgress(String suffix, Object... args) {
        return jdbc.query("SELECT p.book_id,b.title,b.chapter_count,p.chapter_index,p.paragraph_index,c.title,p.updated_at FROM reading_progress p JOIN books b ON p.book_id=b.id JOIN chapters c ON c.book_id=p.book_id AND c.chapter_index=p.chapter_index " + suffix,
            (r, n) -> new Progress(r.getString(1), r.getString(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getString(6), r.getLong(7)), args);
    }
    public List<Progress> progress() { return queryProgress("ORDER BY p.updated_at DESC"); }
    public Progress progress(String book) {
        library.book(book, true);
        return queryProgress("WHERE p.book_id=?", book).stream().findFirst().orElse(null);
    }
    public synchronized Progress saveProgress(String book, Position position) {
        library.validatePosition(book, position.chapterIndex(), position.paragraphIndex());
        return transactions.execute(tx -> {
            int changed = jdbc.update("UPDATE reading_progress SET chapter_index=?,paragraph_index=?,updated_at=? WHERE book_id=?", position.chapterIndex(), position.paragraphIndex(), System.currentTimeMillis(), book);
            if (changed == 0) jdbc.update("INSERT INTO reading_progress(book_id,chapter_index,paragraph_index,updated_at) VALUES(?,?,?,?)", book, position.chapterIndex(), position.paragraphIndex(), System.currentTimeMillis());
            return progress(book);
        });
    }
    public synchronized Session saveSession(String id, SessionInput input) {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("阅读会话编号不正确。");
        library.validatePosition(input.bookId(), input.chapterIndex(), input.paragraphIndex());
        long now = System.currentTimeMillis();
        if (input.startedAt() > now || input.startedAt() < now - 7L * 86400_000
                || input.elapsedSeconds() * 1000L > now - input.startedAt() + 5000)
            throw new IllegalArgumentException("阅读时间不正确，请检查设备时间。");
        return transactions.execute(tx -> {
            var existing = querySessions("WHERE s.id=?", id);
            if (existing.isEmpty()) {
                jdbc.update("INSERT INTO reading_sessions(id,book_id,chapter_index,paragraph_index,started_at,elapsed_seconds,reading_day,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    id, input.bookId(), input.chapterIndex(), input.paragraphIndex(), input.startedAt(), input.elapsedSeconds(),
                    Instant.ofEpochMilli(input.startedAt()).atZone(ZONE).toLocalDate().toString(), now);
            } else {
                Session old = existing.getFirst();
                if (!old.bookId().equals(input.bookId()) || old.startedAt() != input.startedAt())
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "阅读会话编号已被使用。");
                // Cumulative seconds only advance: retries and out-of-order requests never double-count.
                if (input.elapsedSeconds() > old.elapsedSeconds())
                    jdbc.update("UPDATE reading_sessions SET elapsed_seconds=?,chapter_index=?,paragraph_index=?,updated_at=? WHERE id=?",
                        input.elapsedSeconds(), input.chapterIndex(), input.paragraphIndex(), now, id);
            }
            return querySessions("WHERE s.id=?", id).getFirst();
        });
    }
    private List<Session> querySessions(String suffix, Object... args) {
        return jdbc.query("SELECT s.id,s.book_id,b.title,s.chapter_index,s.paragraph_index,c.title,s.started_at,s.elapsed_seconds,s.updated_at FROM reading_sessions s JOIN books b ON s.book_id=b.id JOIN chapters c ON c.book_id=s.book_id AND c.chapter_index=s.chapter_index " + suffix,
            (r, n) -> new Session(r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5), r.getString(6), r.getLong(7), r.getInt(8), r.getLong(9)), args);
    }
    public List<Session> history() { return querySessions("ORDER BY s.updated_at DESC LIMIT 200"); }
    public Stats stats() {
        LocalDate today = LocalDate.now(ZONE);
        long total = jdbc.queryForObject("SELECT COALESCE(SUM(elapsed_seconds),0) FROM reading_sessions", Long.class);
        long todaySeconds = jdbc.queryForObject("SELECT COALESCE(SUM(elapsed_seconds),0) FROM reading_sessions WHERE reading_day=?", Long.class, today.toString());
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM reading_sessions", Long.class);
        var recent = jdbc.query("SELECT reading_day,SUM(elapsed_seconds) FROM reading_sessions WHERE reading_day>=? GROUP BY reading_day", (r,n) -> new Day(r.getString(1),r.getLong(2)), today.minusDays(13).toString());
        var days = new ArrayList<Day>();
        for (int i=13; i>=0; i--) {
            String date = today.minusDays(i).toString();
            days.add(new Day(date, recent.stream().filter(d -> d.date().equals(date)).mapToLong(Day::seconds).sum()));
        }
        var books = jdbc.query("SELECT b.id,b.title,SUM(s.elapsed_seconds) AS seconds FROM reading_sessions s JOIN books b ON b.id=s.book_id GROUP BY b.id,b.title ORDER BY seconds DESC", (r,n) -> new BookTime(r.getString(1),r.getString(2),r.getLong(3)));
        return new Stats(total, todaySeconds, count, days, books);
    }
}
