package io.github.wanhkjd.cloudnovel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.wanhkjd.cloudnovel.dto.BookEditRequest;
import io.github.wanhkjd.cloudnovel.entity.BookEntity;
import io.github.wanhkjd.cloudnovel.entity.ChapterEntity;
import io.github.wanhkjd.cloudnovel.exception.BusinessException;
import io.github.wanhkjd.cloudnovel.mapper.BookMapper;
import io.github.wanhkjd.cloudnovel.mapper.ChapterMapper;
import io.github.wanhkjd.cloudnovel.parser.TxtNovelParser;
import io.github.wanhkjd.cloudnovel.service.impl.LibraryServiceImpl;
import io.github.wanhkjd.cloudnovel.storage.NovelFileStorage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 直接测试业务接口，覆盖不依赖 HTTP 或数据库的隐私规则与事务补偿。 */
class LibraryServiceTest {
    BookMapper books = mock(BookMapper.class);
    ChapterMapper chapters = mock(ChapterMapper.class);
    NovelFileStorage storage = mock(NovelFileStorage.class);
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    LibraryService service;
    String id = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        service =
                new LibraryServiceImpl(
                        books,
                        chapters,
                        new TxtNovelParser(),
                        storage,
                        new TransactionTemplate(manager),
                        Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void hiddenBooksAndCatalogueOnlyBooksCannotExposeTextOrFiles() throws Exception {
        when(books.findById(id)).thenReturn(Optional.of(book(false, false)));
        assertThatThrownBy(() -> service.getBook(id, false)).isInstanceOf(BusinessException.class);
        when(books.findById(id)).thenReturn(Optional.of(book(true, false)));
        assertThat(service.getBook(id, false).preface()).isEmpty();
        assertThat(service.getBook(id, false).canRead()).isFalse();
        assertThatThrownBy(() -> service.listChapters(id, false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.download(id, false)).isInstanceOf(BusinessException.class);
        assertThat(service.getBook(id, true).preface()).isEqualTo("私有前言");
        verifyNoInteractions(chapters, storage);
    }

    @Test
    void shelfNeverIncludesPrefaceEvenForOwner() {
        when(books.findAll(false)).thenReturn(List.of(book(false, false)));
        assertThat(service.listBooks(true).getFirst().preface()).isEmpty();
        when(books.findAll(true)).thenReturn(List.of(book(false, false)));
        assertThat(service.listBooks(false)).isEmpty();
    }

    @Test
    void importStartsPrivateAndSplitsChaptersIntoBoundedBatches() throws Exception {
        StringBuilder text = new StringBuilder("作者：测试作者\n");
        for (int index = 1; index <= 201; index++) {
            text.append("第").append(index).append("章 测试\n原创段落。\n");
        }
        var imported =
                service.importNovel(text.toString().getBytes(StandardCharsets.UTF_8), "原创批次.txt");
        assertThat(imported.chapterCount()).isEqualTo(201);
        assertThat(imported.catalogPublished()).isFalse();
        assertThat(imported.textPublished()).isFalse();
        ArgumentCaptor<List<ChapterEntity>> batches = ArgumentCaptor.captor();
        verify(chapters, times(2)).insertBatch(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(200, 1);
        var ordered = inOrder(storage, manager, books, chapters);
        ordered.verify(storage).writeNew(eq(imported.id()), any());
        ordered.verify(manager).getTransaction(any());
        ordered.verify(books).insert(any());
        ordered.verify(chapters, times(2)).insertBatch(any());
        ordered.verify(manager).commit(any());
        verify(storage, never()).delete(any());
    }

    @Test
    void failedDatabaseWriteRollsBackAndDeletesOnlyNewOriginal() throws Exception {
        doThrow(new DuplicateKeyException("duplicate")).when(books).insert(any());
        assertThatThrownBy(
                        () ->
                                service.importNovel(
                                        "第一章 测试\n原创段落".getBytes(StandardCharsets.UTF_8), "测试.txt"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getKind())
                                        .isEqualTo(BusinessException.Kind.CONFLICT));
        var ordered = inOrder(storage, manager);
        ordered.verify(storage).writeNew(any(), any());
        ordered.verify(manager).getTransaction(any());
        ordered.verify(manager).rollback(any());
        ordered.verify(storage).delete(any());
        verifyNoInteractions(chapters);
    }

    @Test
    void failureDuringCommitAlsoCompensatesTheOriginal() throws Exception {
        doThrow(new UnexpectedRollbackException("test commit failure")).when(manager).commit(any());
        assertThatThrownBy(
                        () ->
                                service.importNovel(
                                        "第一章 测试\n原创段落".getBytes(StandardCharsets.UTF_8), "测试.txt"))
                .isInstanceOf(UnexpectedRollbackException.class);
        var ordered = inOrder(manager, storage);
        ordered.verify(storage).writeNew(any(), any());
        ordered.verify(manager).getTransaction(any());
        ordered.verify(manager).commit(any());
        ordered.verify(storage).delete(any());
    }

    @Test
    void cannotPublishTextWithoutCatalogueOrAcceptInvalidMetadata() {
        assertThatThrownBy(
                        () ->
                                service.updateBook(
                                        id, new BookEditRequest("书名", "作者", "", false, true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.updateBook(
                                        id, new BookEditRequest(" ", "作者", "", false, false)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(books, chapters, storage);
    }

    @Test
    void deleteCleansFileOnlyAfterDatabaseCommit() throws Exception {
        when(books.deleteById(id)).thenReturn(1);
        service.deleteBook(id);
        var ordered = inOrder(manager, storage);
        ordered.verify(manager).getTransaction(any());
        ordered.verify(manager).commit(any());
        ordered.verify(storage).delete(id);
        reset(storage);
        doThrow(new UnexpectedRollbackException("test commit failure")).when(manager).commit(any());
        assertThatThrownBy(() -> service.deleteBook(id))
                .isInstanceOf(UnexpectedRollbackException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void emptyChapterAllowsOnlyParagraphZero() {
        when(books.findById(id)).thenReturn(Optional.of(book(false, false)));
        when(chapters.findByPosition(id, 0))
                .thenReturn(Optional.of(new ChapterEntity(id, 0, "第一章", "", " \n", 0)));
        service.validatePosition(id, 0, 0);
        assertThatThrownBy(() -> service.validatePosition(id, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BookEntity book(boolean catalogue, boolean text) {
        return new BookEntity(
                id, "测试", "作者", "", "UTF-8", 1, 0, 10, "私有前言", "a".repeat(64), catalogue, text, 1);
    }
}
