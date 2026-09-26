package io.github.wanhkjd.cloudnovel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.wanhkjd.cloudnovel.core.exception.BusinessException;
import io.github.wanhkjd.cloudnovel.core.parser.TxtNovelParser;
import io.github.wanhkjd.cloudnovel.core.storage.CoverFormat;
import io.github.wanhkjd.cloudnovel.core.storage.CoverImageStorage;
import io.github.wanhkjd.cloudnovel.core.storage.NovelFileStorage;
import io.github.wanhkjd.cloudnovel.core.storage.StoredImage;
import io.github.wanhkjd.cloudnovel.dao.entity.BookEntity;
import io.github.wanhkjd.cloudnovel.dao.entity.ChapterEntity;
import io.github.wanhkjd.cloudnovel.dao.mapper.BookMapper;
import io.github.wanhkjd.cloudnovel.dao.mapper.ChapterMapper;
import io.github.wanhkjd.cloudnovel.dto.req.BookEditRequest;
import io.github.wanhkjd.cloudnovel.service.LibraryService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
    CoverImageStorage coverStorage = mock(CoverImageStorage.class);
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
                        coverStorage,
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
                                        id, new BookEditRequest("书名", "作者", "", false, true, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.updateBook(
                                        id, new BookEditRequest(" ", "作者", "", false, false, null)))
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
        verify(coverStorage).delete(id);
        reset(storage, coverStorage);
        doThrow(new UnexpectedRollbackException("test commit failure")).when(manager).commit(any());
        assertThatThrownBy(() -> service.deleteBook(id))
                .isInstanceOf(UnexpectedRollbackException.class);
        verifyNoInteractions(storage, coverStorage);
    }

    @Test
    void updateBookAppliesThenClearsTimelineDateAndNeverTouchesCover() {
        when(books.findById(id))
                .thenReturn(Optional.of(bookWith(true, false, null, "covers/" + id + ".jpg")));
        when(books.updateMetadata(any())).thenReturn(1);
        ArgumentCaptor<BookEntity> saved = ArgumentCaptor.captor();

        var set =
                service.updateBook(
                        id,
                        new BookEditRequest(
                                "书名", "作者", "", true, false, LocalDate.parse("2026-09-25")));
        var cleared =
                service.updateBook(id, new BookEditRequest("书名", "作者", "", true, false, null));

        verify(books, times(2)).updateMetadata(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(BookEntity::timelineDate)
                .containsExactly(LocalDate.parse("2026-09-25"), null);
        assertThat(saved.getAllValues())
                .allSatisfy(e -> assertThat(e.coverPath()).isEqualTo("covers/" + id + ".jpg"));
        assertThat(set.timelineDate()).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(cleared.timelineDate()).isNull();
    }

    @Test
    void viewReportsHasCoverWithoutExposingPath() {
        when(books.findById(id))
                .thenReturn(Optional.of(bookWith(true, true, null, "covers/" + id + ".webp")));
        assertThat(service.getBook(id, true).hasCover()).isTrue();
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, true, null, null)));
        assertThat(service.getBook(id, true).hasCover()).isFalse();
    }

    @Test
    void importLeavesTimelineAndCoverUnset() throws Exception {
        service.importNovel("第一章 测试\n原创段落。".getBytes(StandardCharsets.UTF_8), "测试.txt");
        ArgumentCaptor<BookEntity> inserted = ArgumentCaptor.captor();
        verify(books).insert(inserted.capture());
        assertThat(inserted.getValue().timelineDate()).isNull();
        assertThat(inserted.getValue().coverPath()).isNull();
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

    @Test
    void setCoverDetectsFormatWritesFileAndExposesHasCoverWithoutPath() throws Exception {
        byte[] png = {
            (byte) 0x89,
            'P',
            'N',
            'G',
            (byte) 0x0D,
            (byte) 0x0A,
            (byte) 0x1A,
            (byte) 0x0A,
            0,
            0,
            0,
            0
        };
        when(books.findById(id))
                .thenReturn(
                        Optional.of(bookWith(true, false, null, null)),
                        Optional.of(bookWith(true, false, null, "covers/" + id + ".png")));
        var view = service.setCover(id, png);
        verify(coverStorage).write(id, CoverFormat.PNG, png);
        verify(books).updateCover(id, "covers/" + id + ".png");
        assertThat(view.hasCover()).isTrue();
    }

    @Test
    void setCoverRejectsDisallowedTypeBeforeTouchingStorage() {
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, false, null, null)));
        assertThatThrownBy(
                        () -> service.setCover(id, "<svg xmlns=".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(coverStorage);
        verify(books, never()).updateCover(any(), any());
    }

    @Test
    void setCoverRejectsCoverLargerThanTwoMebibytes() throws Exception {
        byte[] oversize = new byte[2 * 1024 * 1024 + 1];
        byte[] pngMagic = {
            (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
        };
        System.arraycopy(pngMagic, 0, oversize, 0, pngMagic.length);
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, false, null, null)));
        assertThatThrownBy(() -> service.setCover(id, oversize))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(coverStorage);
        verify(books, never()).updateCover(any(), any());
    }

    private BookEntity book(boolean catalogue, boolean text) {
        return bookWith(catalogue, text, null, null);
    }

    @Test
    void removeCoverClearsPathAndDeletesFileIdempotently() throws Exception {
        when(books.findById(id))
                .thenReturn(Optional.of(bookWith(true, false, null, "covers/" + id + ".jpg")));
        service.removeCover(id);
        service.removeCover(id);
        verify(coverStorage, times(2)).delete(id);
        verify(books, times(2)).updateCover(id, null);
    }

    @Test
    void readCoverHidesMissingUnpublishedOrCoverlessBooks() throws Exception {
        when(books.findById(id)).thenReturn(Optional.empty());
        assertThat(service.readCover(id, false)).isEmpty();
        when(books.findById(id))
                .thenReturn(Optional.of(bookWith(false, false, null, "covers/" + id + ".png")));
        assertThat(service.readCover(id, false)).isEmpty();
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, false, null, null)));
        assertThat(service.readCover(id, true)).isEmpty();
        verify(coverStorage, never()).read(any());
    }

    @Test
    void readCoverStreamsForOwnerOrPublishedBookWithCover() throws Exception {
        StoredImage stored = new StoredImage(new byte[] {1, 2, 3}, "image/jpeg");
        when(coverStorage.read(id)).thenReturn(Optional.of(stored));
        when(books.findById(id))
                .thenReturn(Optional.of(bookWith(true, false, null, "covers/" + id + ".jpg")));
        assertThat(service.readCover(id, false).orElseThrow().contentType())
                .isEqualTo("image/jpeg");
        when(books.findById(id))
                .thenReturn(Optional.of(bookWith(false, false, null, "covers/" + id + ".jpg")));
        assertThat(service.readCover(id, true)).contains(stored);
    }

    private BookEntity bookWith(
            boolean catalogue, boolean text, LocalDate timelineDate, String coverPath) {
        return new BookEntity(
                id,
                "测试",
                "作者",
                "",
                "UTF-8",
                1,
                0,
                10,
                "私有前言",
                "a".repeat(64),
                catalogue,
                text,
                1,
                timelineDate,
                coverPath);
    }
}
