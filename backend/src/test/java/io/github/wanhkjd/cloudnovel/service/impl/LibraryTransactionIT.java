package io.github.wanhkjd.cloudnovel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.wanhkjd.cloudnovel.core.parser.TxtNovelParser;
import io.github.wanhkjd.cloudnovel.core.storage.LocalCoverImageStorage;
import io.github.wanhkjd.cloudnovel.core.storage.LocalNovelFileStorage;
import io.github.wanhkjd.cloudnovel.dao.entity.ChapterEntity;
import io.github.wanhkjd.cloudnovel.dao.mapper.BookMapper;
import io.github.wanhkjd.cloudnovel.dao.mapper.ChapterMapper;
import io.github.wanhkjd.cloudnovel.service.LibraryService;
import io.github.wanhkjd.cloudnovel.support.DatabaseIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 验证真实 MyBatis 事务中第二批章节失败时，首批章节、书目和新原件共同撤销。 */
class LibraryTransactionIT extends DatabaseIntegrationTest {
    @Autowired BookMapper books;
    @Autowired ChapterMapper chapters;
    @Autowired TransactionTemplate transactions;
    @TempDir Path directory;

    @Test
    void secondBatchFailureRollsBackRowsAlreadyWrittenAndRemovesOnlyTheNewFile() throws Exception {
        ChapterMapper failingChapters = mock(ChapterMapper.class);
        AtomicInteger batches = new AtomicInteger();
        AtomicReference<String> bookId = new AtomicReference<>();
        when(failingChapters.insertBatch(any()))
                .thenAnswer(
                        invocation -> {
                            List<ChapterEntity> batch = invocation.getArgument(0);
                            bookId.set(batch.getFirst().bookId());
                            if (batches.incrementAndGet() == 2) {
                                assertThat(books.findById(bookId.get())).isPresent();
                                assertThat(chapters.findSummaries(bookId.get())).hasSize(200);
                                throw new IllegalStateException("simulated second batch failure");
                            }
                            return chapters.insertBatch(batch);
                        });
        LibraryService service =
                new LibraryServiceImpl(
                        books,
                        failingChapters,
                        new TxtNovelParser(),
                        new LocalNovelFileStorage(directory.toString()),
                        new LocalCoverImageStorage(directory.toString()),
                        transactions,
                        Clock.systemUTC());
        StringBuilder text = new StringBuilder();
        for (int index = 1; index <= 201; index++) {
            text.append("第").append(index).append("章 原创测试\n用于事务测试的段落。\n");
        }
        assertThatThrownBy(
                        () ->
                                service.importNovel(
                                        text.toString().getBytes(StandardCharsets.UTF_8),
                                        "事务测试.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated second batch failure");
        assertThat(batches.get()).isEqualTo(2);
        assertThat(books.findAll(false)).isEmpty();
        assertThat(chapters.findSummaries(bookId.get())).isEmpty();
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }
}
