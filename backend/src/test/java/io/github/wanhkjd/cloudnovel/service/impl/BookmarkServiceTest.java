package io.github.wanhkjd.cloudnovel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.wanhkjd.cloudnovel.core.exception.BusinessException;
import io.github.wanhkjd.cloudnovel.dao.entity.BookmarkEntity;
import io.github.wanhkjd.cloudnovel.dao.mapper.BookmarkMapper;
import io.github.wanhkjd.cloudnovel.dto.req.BookmarkCreateRequest;
import io.github.wanhkjd.cloudnovel.dto.req.BookmarkEditRequest;
import io.github.wanhkjd.cloudnovel.dto.resp.BookmarkView;
import io.github.wanhkjd.cloudnovel.service.BookmarkService;
import io.github.wanhkjd.cloudnovel.service.LibraryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 测试书签权限、文本边界和业务冲突，避免把隐私规则放进控制器或 SQL。 */
class BookmarkServiceTest {
    private static final long NOW = Instant.parse("2026-09-13T00:00:00Z").toEpochMilli();
    private final BookmarkMapper mapper = mock(BookmarkMapper.class);
    private final LibraryService library = mock(LibraryService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final String bookId = UUID.randomUUID().toString();
    private final String bookmarkId = UUID.randomUUID().toString();
    private BookmarkService service;

    @BeforeEach
    void setUp() {
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        service =
                new BookmarkServiceImpl(
                        mapper,
                        library,
                        new TransactionTemplate(manager),
                        Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void unpublishedTextPreventsEvenPublishedNotesFromBeingQueried() {
        doThrow(BusinessException.notFound("正文未公开。"))
                .when(library)
                .requireReadableBook(bookId, false);
        assertThatThrownBy(() -> service.listForBook(bookId, false))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onlyVisitorsGetThePublishedFilterAfterReadabilityCheck(boolean owner) {
        when(mapper.findByBook(bookId, !owner)).thenReturn(List.of(view("公开感想", true)));
        assertThat(service.listForBook(bookId, owner)).hasSize(1);
        var ordered = inOrder(library, mapper);
        ordered.verify(library).requireReadableBook(bookId, owner);
        ordered.verify(mapper).findByBook(bookId, !owner);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"  感想  ", ""})
    void newBookmarkNormalizesTheNoteAndPreservesPrivateVisibility(String note) {
        AtomicReference<BookmarkEntity> inserted = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            inserted.set(invocation.getArgument(0));
                            return 1;
                        })
                .when(mapper)
                .insert(any());
        when(mapper.findById(any()))
                .thenAnswer(
                        invocation -> {
                            var entity = inserted.get();
                            return Optional.of(
                                    new BookmarkView(
                                            entity.id(),
                                            bookId,
                                            "测试小说",
                                            0,
                                            2,
                                            "第一章",
                                            entity.note(),
                                            entity.published(),
                                            entity.createdAt(),
                                            entity.updatedAt()));
                        });
        var created = service.create(new BookmarkCreateRequest(bookId, 0, 2, note, false));
        assertThat(created.note()).isEqualTo(note == null ? "" : note.strip());
        assertThat(created.published()).isFalse();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);
        assertThat(UUID.fromString(created.id()).toString()).isEqualTo(created.id());
        verify(library).validatePosition(bookId, 0, 2);
        verify(manager).commit(any());
    }

    @Test
    void duplicatePositionIsARollbackAndBusinessConflict() {
        doThrow(new DuplicateKeyException("duplicate position")).when(mapper).insert(any());
        assertThatThrownBy(
                        () -> service.create(new BookmarkCreateRequest(bookId, 0, 2, "感想", false)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getKind())
                                        .isEqualTo(BusinessException.Kind.CONFLICT));
        verify(manager).rollback(any());
    }

    @Test
    void invalidPositionCannotCreateABookmark() {
        doThrow(new IllegalArgumentException("段落不存在。"))
                .when(library)
                .validatePosition(bookId, 0, 2);
        assertThatThrownBy(
                        () -> service.create(new BookmarkCreateRequest(bookId, 0, 2, "感想", false)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper, manager);
    }

    @Test
    void oversizedNotesAreRejectedByTheServiceEvenWithoutWebValidation() {
        String longNote = "字".repeat(4001);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new BookmarkCreateRequest(bookId, 0, 2, longNote, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> service.update(bookmarkId, new BookmarkEditRequest(longNote, false)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper, manager);
    }

    @Test
    void editingOnlyChangesNoteVisibilityAndUpdateTime() {
        when(mapper.findById(bookmarkId))
                .thenReturn(Optional.of(view("旧感想", false)))
                .thenReturn(Optional.of(view("新感想", true)));
        when(mapper.update(any())).thenReturn(1);
        service.update(bookmarkId, new BookmarkEditRequest("  新感想  ", true));
        ArgumentCaptor<BookmarkEntity> saved = ArgumentCaptor.forClass(BookmarkEntity.class);
        verify(mapper).update(saved.capture());
        assertThat(saved.getValue())
                .isEqualTo(
                        new BookmarkEntity(bookmarkId, bookId, 0, 2, "新感想", true, NOW - 1000, NOW));
        verify(manager).commit(any());
    }

    @Test
    void missingBookmarkCannotBeUpdatedOrDeleted() {
        when(mapper.findById(bookmarkId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(bookmarkId, new BookmarkEditRequest("感想", false)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getKind())
                                        .isEqualTo(BusinessException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> service.delete(bookmarkId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getKind())
                                        .isEqualTo(BusinessException.Kind.NOT_FOUND));
        verify(mapper, never()).update(any());
    }

    private BookmarkView view(String note, boolean published) {
        return new BookmarkView(
                bookmarkId, bookId, "测试小说", 0, 2, "第一章", note, published, NOW - 1000, NOW);
    }
}
