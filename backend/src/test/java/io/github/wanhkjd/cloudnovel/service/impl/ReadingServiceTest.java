package io.github.wanhkjd.cloudnovel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.wanhkjd.cloudnovel.core.exception.BusinessException;
import io.github.wanhkjd.cloudnovel.dao.entity.ProgressEntity;
import io.github.wanhkjd.cloudnovel.dao.entity.ReadingSessionEntity;
import io.github.wanhkjd.cloudnovel.dao.mapper.ReadingMapper;
import io.github.wanhkjd.cloudnovel.dto.req.PositionRequest;
import io.github.wanhkjd.cloudnovel.dto.req.ReadingSessionRequest;
import io.github.wanhkjd.cloudnovel.dto.resp.BookTimeView;
import io.github.wanhkjd.cloudnovel.dto.resp.DayReadingView;
import io.github.wanhkjd.cloudnovel.dto.resp.ProgressView;
import io.github.wanhkjd.cloudnovel.dto.resp.ReadingSessionView;
import io.github.wanhkjd.cloudnovel.service.LibraryService;
import io.github.wanhkjd.cloudnovel.service.ReadingService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用固定时钟验证阅读幂等、边界时间和北京时间归属，不依赖系统当前日期。 */
class ReadingServiceTest {
    private static final long NOW = Instant.parse("2026-09-13T16:00:10Z").toEpochMilli();
    private static final long STARTED = NOW - 20_000;
    private final ReadingMapper mapper = mock(ReadingMapper.class);
    private final LibraryService library = mock(LibraryService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final String bookId = UUID.randomUUID().toString();
    private final String sessionId = UUID.randomUUID().toString();
    private ReadingService service;

    @BeforeEach
    void setUp() {
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        service =
                new ReadingServiceImpl(
                        mapper,
                        library,
                        new TransactionTemplate(manager),
                        Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void absentProgressRemainsNullAfterVerifyingTheBook() {
        when(mapper.findProgressByBook(bookId)).thenReturn(Optional.empty());
        assertThat(service.getProgress(bookId)).isNull();
        var ordered = inOrder(library, mapper);
        ordered.verify(library).getBook(bookId, true);
        ordered.verify(mapper).findProgressByBook(bookId);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void progressUsesServerTimeAndInsertsOnlyWhenNoRowWasUpdated(int updatedRows) {
        when(mapper.updateProgress(any())).thenReturn(updatedRows);
        ProgressView view = new ProgressView(bookId, "测试小说", 2, 1, 3, "第二章", NOW);
        when(mapper.findProgressByBook(bookId)).thenReturn(Optional.of(view));
        assertThat(service.saveProgress(bookId, new PositionRequest(1, 3))).isEqualTo(view);
        verify(library).validatePosition(bookId, 1, 3);
        verify(mapper).updateProgress(new ProgressEntity(bookId, 1, 3, NOW));
        verify(mapper, times(updatedRows == 0 ? 1 : 0)).insertProgress(any());
        verify(manager).commit(any());
    }

    @Test
    void newSessionBelongsToItsStartDayEvenWhenSavedAfterBeijingMidnight() {
        when(mapper.findSession(sessionId)).thenReturn(Optional.empty());
        when(mapper.findSessionView(sessionId)).thenReturn(Optional.of(view(20)));
        assertThat(service.saveSession(sessionId, input(20, STARTED))).isEqualTo(view(20));
        ArgumentCaptor<ReadingSessionEntity> saved =
                ArgumentCaptor.forClass(ReadingSessionEntity.class);
        verify(mapper).insertSession(saved.capture());
        assertThat(saved.getValue().readingDay()).isEqualTo("2026-09-13");
        assertThat(saved.getValue().updatedAt()).isEqualTo(NOW);
        verify(mapper, never()).advanceSession(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 20})
    void duplicateAndOutOfOrderReportsDoNotChangeTheSavedTimeOrPosition(int reported) {
        when(mapper.findSession(sessionId)).thenReturn(Optional.of(entity(20)));
        when(mapper.findSessionView(sessionId)).thenReturn(Optional.of(view(20)));
        assertThat(service.saveSession(sessionId, input(reported, STARTED)).elapsedSeconds())
                .isEqualTo(20);
        verify(mapper, never()).insertSession(any());
        verify(mapper, never()).advanceSession(any());
    }

    @Test
    void largerCumulativeReportAdvancesInsteadOfAddingAnotherSession() {
        when(mapper.findSession(sessionId)).thenReturn(Optional.of(entity(10)));
        when(mapper.findSessionView(sessionId)).thenReturn(Optional.of(view(20)));
        service.saveSession(sessionId, input(20, STARTED));
        verify(mapper)
                .advanceSession(
                        new ReadingSessionEntity(
                                sessionId, bookId, 1, 3, STARTED, 20, "2026-09-13", NOW));
        verify(mapper, never()).insertSession(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sessionIdentityCannotBeReusedForAnotherBookOrStartTime(boolean differentBook) {
        when(mapper.findSession(sessionId)).thenReturn(Optional.of(entity(10)));
        var input =
                new ReadingSessionRequest(
                        differentBook ? UUID.randomUUID().toString() : bookId,
                        1,
                        3,
                        20,
                        differentBook ? STARTED : STARTED - 1);
        assertThatThrownBy(() -> service.saveSession(sessionId, input))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getKind())
                                        .isEqualTo(BusinessException.Kind.CONFLICT));
        verify(mapper, never()).advanceSession(any());
        verify(mapper, never()).insertSession(any());
        verify(manager).rollback(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not-a-uuid", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"})
    void malformedSessionIdIsRejectedBeforeLookingUpAnyBook(String id) {
        assertThatThrownBy(() -> service.saveSession(id, input(20, STARTED)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(library, mapper, manager);
    }

    @ParameterizedTest
    @MethodSource("invalidTimes")
    void impossibleOrStaleTimeDoesNotReachTheDatabase(int seconds, long startedAt) {
        assertThatThrownBy(() -> service.saveSession(sessionId, input(seconds, startedAt)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper, manager);
    }

    private static Stream<Arguments> invalidTimes() {
        return Stream.of(
                Arguments.of(0, STARTED),
                Arguments.of(1801, NOW - 2_000_000),
                Arguments.of(1, 0L),
                Arguments.of(1, NOW + 1),
                Arguments.of(1, NOW - 7L * 86_400_000 - 1),
                Arguments.of(26, STARTED));
    }

    @ParameterizedTest
    @MethodSource("acceptedTimeBoundaries")
    void exactDurationWindowAndClockToleranceBoundariesAreAccepted(int seconds, long startedAt) {
        when(mapper.findSessionView(sessionId)).thenReturn(Optional.of(view(seconds)));
        service.saveSession(sessionId, input(seconds, startedAt));
        verify(mapper).insertSession(any());
    }

    private static Stream<Arguments> acceptedTimeBoundaries() {
        return Stream.of(
                Arguments.of(1800, NOW - 1_800_000),
                Arguments.of(1, NOW - 7L * 86_400_000),
                Arguments.of(25, STARTED));
    }

    @Test
    void statisticsUseBeijingTodayAndFillAllFourteenDays() {
        when(mapper.findDailySecondsSince("2026-09-01"))
                .thenReturn(
                        List.of(
                                new DayReadingView("2026-09-13", 20),
                                new DayReadingView("2026-09-14", 5)));
        when(mapper.sumSeconds()).thenReturn(125L);
        when(mapper.sumSecondsOnDay("2026-09-14")).thenReturn(5L);
        when(mapper.countSessions()).thenReturn(3L);
        when(mapper.findBookTimes()).thenReturn(List.of(new BookTimeView(bookId, "测试小说", 125)));
        var stats = service.getStats();
        assertThat(stats.totalSeconds()).isEqualTo(125);
        assertThat(stats.todaySeconds()).isEqualTo(5);
        assertThat(stats.sessionCount()).isEqualTo(3);
        assertThat(stats.days()).hasSize(14);
        assertThat(stats.days().getFirst()).isEqualTo(new DayReadingView("2026-09-01", 0));
        assertThat(stats.days().get(12)).isEqualTo(new DayReadingView("2026-09-13", 20));
        assertThat(stats.days().getLast()).isEqualTo(new DayReadingView("2026-09-14", 5));
        assertThat(stats.days().stream().mapToLong(DayReadingView::seconds).sum()).isEqualTo(25);
        assertThat(stats.books()).containsExactly(new BookTimeView(bookId, "测试小说", 125));
    }

    @Test
    void historyRemainsLimitedToTwoHundredSessions() {
        when(mapper.findHistory(200)).thenReturn(List.of(view(20)));
        assertThat(service.listHistory()).containsExactly(view(20));
    }

    private ReadingSessionRequest input(int seconds, long startedAt) {
        return new ReadingSessionRequest(bookId, 1, 3, seconds, startedAt);
    }

    private ReadingSessionEntity entity(int seconds) {
        return new ReadingSessionEntity(
                sessionId, bookId, 0, 0, STARTED, seconds, "2026-09-13", NOW - 1);
    }

    private ReadingSessionView view(int seconds) {
        return new ReadingSessionView(
                sessionId, bookId, "测试小说", 1, 3, "第二章", STARTED, seconds, NOW);
    }
}
