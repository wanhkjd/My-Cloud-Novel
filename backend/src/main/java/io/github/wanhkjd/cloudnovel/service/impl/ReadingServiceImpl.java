package io.github.wanhkjd.cloudnovel.service.impl;

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
import io.github.wanhkjd.cloudnovel.dto.resp.ReadingStatsView;
import io.github.wanhkjd.cloudnovel.service.LibraryService;
import io.github.wanhkjd.cloudnovel.service.ReadingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 主人阅读业务实现，负责位置校验、累计会话幂等与北京时间统计。 */
@Service
public class ReadingServiceImpl implements ReadingService {
    /** 每段最多 30 分钟，与前端会话切分规则一致。 */
    private static final int MAX_SESSION_SECONDS = 1800;

    /** 允许上报最近七天开始的片段，不接受任意历史回填。 */
    private static final long SESSION_WINDOW_MILLIS = 7L * 86_400_000;

    /** 统计统一使用北京时间，而不是服务器默认时区。 */
    private static final ZoneId READING_ZONE = ZoneId.of("Asia/Shanghai");

    /** 阅读数据访问接口。 */
    private final ReadingMapper readingMapper;

    /** 书籍与段落位置校验接口。 */
    private final LibraryService libraryService;

    /** 事务模板，确保同步锁覆盖数据库提交阶段。 */
    private final TransactionTemplate transactions;

    /** 可替换的服务器时钟。 */
    private final Clock clock;

    /**
     * 注入阅读业务所需依赖。
     *
     * @param readingMapper 阅读 Mapper
     * @param libraryService 书库业务接口
     * @param transactions 事务模板
     * @param clock 服务器时钟
     */
    public ReadingServiceImpl(
            ReadingMapper readingMapper,
            LibraryService libraryService,
            TransactionTemplate transactions,
            Clock clock) {
        this.readingMapper = readingMapper;
        this.libraryService = libraryService;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public List<ProgressView> listProgress() {
        return readingMapper.findProgress();
    }

    @Override
    public ProgressView getProgress(String bookId) {
        libraryService.getBook(bookId, true);
        return readingMapper.findProgressByBook(bookId).orElse(null);
    }

    @Override
    public synchronized ProgressView saveProgress(String bookId, PositionRequest position) {
        libraryService.validatePosition(bookId, position.chapterIndex(), position.paragraphIndex());
        ProgressEntity progress =
                new ProgressEntity(
                        bookId, position.chapterIndex(), position.paragraphIndex(), clock.millis());
        // 单实例下锁保持到 commit 完成，避免同时首次写入相同主键。此锁不是分布式锁。
        return transactions.execute(
                status -> {
                    if (readingMapper.updateProgress(progress) == 0) {
                        readingMapper.insertProgress(progress);
                    }
                    return readingMapper.findProgressByBook(bookId).orElseThrow();
                });
    }

    @Override
    public synchronized ReadingSessionView saveSession(String id, ReadingSessionRequest input) {
        validateSessionId(id);
        libraryService.validatePosition(
                input.bookId(), input.chapterIndex(), input.paragraphIndex());
        long now = clock.millis();
        validateTime(input, now);
        return transactions.execute(
                status -> {
                    ReadingSessionEntity existing = readingMapper.findSession(id).orElse(null);
                    ReadingSessionEntity session =
                            new ReadingSessionEntity(
                                    id,
                                    input.bookId(),
                                    input.chapterIndex(),
                                    input.paragraphIndex(),
                                    input.startedAt(),
                                    input.elapsedSeconds(),
                                    Instant.ofEpochMilli(input.startedAt())
                                            .atZone(READING_ZONE)
                                            .toLocalDate()
                                            .toString(),
                                    now);
                    if (existing == null) {
                        readingMapper.insertSession(session);
                    } else {
                        if (!existing.bookId().equals(input.bookId())
                                || existing.startedAt() != input.startedAt()) {
                            throw BusinessException.conflict("阅读会话编号已被使用。");
                        }
                        // 只保存更大的累计值，重复与乱序上报不新增时长，也不回退旧段落位置。
                        if (input.elapsedSeconds() > existing.elapsedSeconds()) {
                            readingMapper.advanceSession(session);
                        }
                    }
                    return readingMapper.findSessionView(id).orElseThrow();
                });
    }

    @Override
    public List<ReadingSessionView> listHistory() {
        return readingMapper.findHistory(200);
    }

    @Override
    @Transactional(readOnly = true)
    public ReadingStatsView getStats() {
        LocalDate today = LocalDate.now(clock.withZone(READING_ZONE));
        Map<String, Long> recent =
                readingMapper.findDailySecondsSince(today.minusDays(13).toString()).stream()
                        .collect(Collectors.toMap(DayReadingView::date, DayReadingView::seconds));
        List<DayReadingView> days = new ArrayList<>();
        for (int offset = 13; offset >= 0; offset--) {
            String date = today.minusDays(offset).toString();
            days.add(new DayReadingView(date, recent.getOrDefault(date, 0L)));
        }
        List<BookTimeView> books = readingMapper.findBookTimes();
        return new ReadingStatsView(
                readingMapper.sumSeconds(),
                readingMapper.sumSecondsOnDay(today.toString()),
                readingMapper.countSessions(),
                List.copyOf(days),
                books);
    }

    private static void validateSessionId(String id) {
        try {
            if (!UUID.fromString(id).toString().equals(id)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IllegalArgumentException("阅读会话编号不正确。");
        }
    }

    private static void validateTime(ReadingSessionRequest input, long now) {
        if (input.elapsedSeconds() < 1
                || input.elapsedSeconds() > MAX_SESSION_SECONDS
                || input.startedAt() <= 0
                || input.startedAt() > now
                || input.startedAt() < now - SESSION_WINDOW_MILLIS
                || input.elapsedSeconds() * 1000L > now - input.startedAt() + 5000) {
            throw new IllegalArgumentException("阅读时间不正确，请检查设备时间。");
        }
    }
}
