package io.github.wanhkjd.cloudnovel.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.wanhkjd.cloudnovel.entity.BookEntity;
import io.github.wanhkjd.cloudnovel.entity.BookmarkEntity;
import io.github.wanhkjd.cloudnovel.entity.ChapterEntity;
import io.github.wanhkjd.cloudnovel.entity.ProgressEntity;
import io.github.wanhkjd.cloudnovel.entity.ReadingSessionEntity;
import io.github.wanhkjd.cloudnovel.support.DatabaseIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/** 用真实 MySQL 和 MyBatis XML 验证实体映射、查询投影、唯一约束及外键级联。 */
@Transactional
class MapperIT extends DatabaseIntegrationTest {
    @Autowired BookMapper books;
    @Autowired ChapterMapper chapters;
    @Autowired ReadingMapper reading;
    @Autowired BookmarkMapper bookmarks;

    @Test
    void bookAndChapterMappingsPreserveRawDataWithoutLoadingBodiesInLists() {
        BookEntity book = seedBook();
        assertThat(books.findById(book.id())).contains(book);
        assertThat(books.findAll(true)).isEmpty();
        assertThat(books.findAll(false).getFirst().preface()).isEmpty();
        assertThat(chapters.findSummaries(book.id()))
                .hasSize(2)
                .allSatisfy(chapter -> assertThat(chapter.content()).isEmpty());
        assertThat(chapters.findByPosition(book.id(), 1).orElseThrow().content())
                .isEqualTo("第二章原创正文。");
        assertThat(chapters.findByPosition(book.id(), 99)).isEmpty();
        BookEntity published =
                new BookEntity(
                        book.id(),
                        "新书名",
                        "新作者",
                        "简介",
                        book.encoding(),
                        2,
                        1,
                        20,
                        book.preface(),
                        book.sha256(),
                        true,
                        false,
                        book.createdAt());
        assertThat(books.updateMetadata(published)).isEqualTo(1);
        assertThat(books.findAll(true)).extracting(BookEntity::title).containsExactly("新书名");
        assertThat(books.findById(book.id()).orElseThrow().textPublished()).isFalse();
        BookEntity duplicate =
                new BookEntity(
                        UUID.randomUUID().toString(),
                        book.title(),
                        book.author(),
                        "",
                        "UTF-8",
                        2,
                        1,
                        20,
                        "",
                        book.sha256(),
                        false,
                        false,
                        1);
        assertThatThrownBy(() -> books.insert(duplicate)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void readingProjectionsAndConditionalUpdatesPreserveMonotonicTime() {
        BookEntity book = seedBook();
        assertThat(reading.sumSeconds()).isZero();
        assertThat(reading.findProgressByBook(book.id())).isEmpty();
        assertThat(reading.updateProgress(new ProgressEntity(book.id(), 0, 0, 1))).isZero();
        reading.insertProgress(new ProgressEntity(book.id(), 0, 0, 1));
        reading.updateProgress(new ProgressEntity(book.id(), 1, 0, 2));
        assertThat(reading.findProgress().getFirst().chapterTitle()).isEqualTo("第二章 回声");
        String id = UUID.randomUUID().toString();
        ReadingSessionEntity session =
                new ReadingSessionEntity(id, book.id(), 0, 0, 1000, 60, "2026-09-13", 61000);
        reading.insertSession(session);
        assertThat(reading.findSession(id)).contains(session);
        assertThat(
                        reading.advanceSession(
                                new ReadingSessionEntity(
                                        id, book.id(), 1, 0, 1000, 30, "2026-09-13", 62000)))
                .isZero();
        assertThat(
                        reading.advanceSession(
                                new ReadingSessionEntity(
                                        id, book.id(), 1, 0, 1000, 90, "2026-09-13", 91000)))
                .isEqualTo(1);
        assertThat(reading.findSessionView(id).orElseThrow().bookTitle()).isEqualTo(book.title());
        assertThat(reading.findHistory(1).getFirst().chapterTitle()).isEqualTo("第二章 回声");
        assertThat(reading.sumSeconds()).isEqualTo(90);
        assertThat(reading.sumSecondsOnDay("2026-09-13")).isEqualTo(90);
        assertThat(reading.sumSecondsOnDay("2026-09-12")).isZero();
        assertThat(reading.countSessions()).isEqualTo(1);
        assertThat(reading.findDailySecondsSince("2026-09-01").getFirst().date())
                .isEqualTo("2026-09-13");
        assertThat(reading.findBookTimes().getFirst().seconds()).isEqualTo(90);
        books.deleteById(book.id());
        assertThat(reading.findProgress()).isEmpty();
        assertThat(reading.countSessions()).isZero();
    }

    @Test
    void bookmarkQueriesRespectFiltersAndDatabaseCascade() {
        BookEntity book = seedBook();
        String privateId = UUID.randomUUID().toString();
        String publicId = UUID.randomUUID().toString();
        bookmarks.insert(new BookmarkEntity(privateId, book.id(), 0, 0, "私密", false, 1, 1));
        bookmarks.insert(new BookmarkEntity(publicId, book.id(), 1, 0, "公开", true, 2, 2));
        assertThat(bookmarks.findAll()).hasSize(2);
        assertThat(bookmarks.findByBook(book.id(), true))
                .extracting(view -> view.id())
                .containsExactly(publicId);
        assertThat(bookmarks.findByBook(book.id(), false))
                .extracting(view -> view.id())
                .containsExactly(privateId, publicId);
        bookmarks.update(new BookmarkEntity(privateId, book.id(), 0, 0, "已编辑", true, 1, 3));
        assertThat(bookmarks.findById(privateId).orElseThrow().note()).isEqualTo("已编辑");
        assertThatThrownBy(
                        () ->
                                bookmarks.insert(
                                        new BookmarkEntity(
                                                UUID.randomUUID().toString(),
                                                book.id(),
                                                0,
                                                0,
                                                "重复",
                                                false,
                                                4,
                                                4)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(bookmarks.deleteById(publicId)).isEqualTo(1);
        assertThat(bookmarks.findById(publicId)).isEmpty();
        books.deleteById(book.id());
        assertThat(bookmarks.findAll()).isEmpty();
        assertThat(chapters.findSummaries(book.id())).isEmpty();
    }

    @Test
    void usesActualMySqlInnoDbAndStoresFourByteUnicode() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(
                        jdbc.queryForList(
                                "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                                String.class))
                .hasSize(5)
                .allMatch("InnoDB"::equals);
        BookEntity book = seedBook();
        jdbc.update(
                "UPDATE chapters SET content = ? WHERE book_id = ? AND chapter_index = 0",
                "正文含四字节字符：📚𠮷",
                book.id());
        assertThat(chapters.findByPosition(book.id(), 0).orElseThrow().content())
                .isEqualTo("正文含四字节字符：📚𠮷");
    }

    private BookEntity seedBook() {
        BookEntity book =
                new BookEntity(
                        UUID.randomUUID().toString(),
                        "Mapper 原创测试",
                        "测试作者",
                        "简介",
                        "UTF-8",
                        2,
                        1,
                        20,
                        "原始前言",
                        UUID.randomUUID().toString().replace("-", "").repeat(2),
                        false,
                        false,
                        1000);
        books.insert(book);
        chapters.insertBatch(
                List.of(
                        new ChapterEntity(book.id(), 0, "第一章 来信", "第一卷", "第一章原创正文。", 10),
                        new ChapterEntity(book.id(), 1, "第二章 回声", "第一卷", "第二章原创正文。", 10)));
        return book;
    }
}
