package io.github.wanhkjd.cloudnovel.service.impl;

import io.github.wanhkjd.cloudnovel.dto.BookEditRequest;
import io.github.wanhkjd.cloudnovel.entity.BookEntity;
import io.github.wanhkjd.cloudnovel.entity.ChapterEntity;
import io.github.wanhkjd.cloudnovel.exception.BusinessException;
import io.github.wanhkjd.cloudnovel.mapper.BookMapper;
import io.github.wanhkjd.cloudnovel.mapper.ChapterMapper;
import io.github.wanhkjd.cloudnovel.parser.TxtNovelParser;
import io.github.wanhkjd.cloudnovel.service.LibraryService;
import io.github.wanhkjd.cloudnovel.storage.NovelFileStorage;
import io.github.wanhkjd.cloudnovel.vo.BookView;
import io.github.wanhkjd.cloudnovel.vo.ChapterSummaryView;
import io.github.wanhkjd.cloudnovel.vo.ChapterView;
import io.github.wanhkjd.cloudnovel.vo.DownloadFile;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 书库业务实现：统一公开规则、导入事务和文件补偿，不包含 SQL。
 *
 * <p>文件系统不参与数据库事务，因此导入使用显式事务模板，在提交失败后仍能删除新原件； 删除则先提交数据库，再清理磁盘，避免回滚后只剩书目而没有原件。
 */
@Service
public class LibraryServiceImpl implements LibraryService {
    /** 每次 INSERT 的章节上限，避免生成过大的批量 SQL。 */
    private static final int CHAPTER_BATCH_SIZE = 200;

    /** 仅记录基础设施故障，不记录小说正文或密码。 */
    private static final Logger LOG = LoggerFactory.getLogger(LibraryServiceImpl.class);

    /** 书籍持久化接口。 */
    private final BookMapper bookMapper;

    /** 章节持久化接口。 */
    private final ChapterMapper chapterMapper;

    /** 纯文本解析器，可独立测试。 */
    private final TxtNovelParser parser;

    /** 私有原件存储，封装路径规则。 */
    private final NovelFileStorage storage;

    /** 保证所有数据库批次共同提交的事务模板。 */
    private final TransactionTemplate transactions;

    /** 可注入时钟，所有创建时间使用服务器时间。 */
    private final Clock clock;

    /**
     * 注入书库所需的持久化、解析和基础设施依赖。
     *
     * @param bookMapper 书籍 Mapper
     * @param chapterMapper 章节 Mapper
     * @param parser TXT 解析器
     * @param storage 私有原件存储
     * @param transactions 事务模板
     * @param clock 服务器时钟
     */
    public LibraryServiceImpl(
            BookMapper bookMapper,
            ChapterMapper chapterMapper,
            TxtNovelParser parser,
            NovelFileStorage storage,
            TransactionTemplate transactions,
            Clock clock) {
        this.bookMapper = bookMapper;
        this.chapterMapper = chapterMapper;
        this.parser = parser;
        this.storage = storage;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public List<BookView> listBooks(boolean owner) {
        return bookMapper.findAll(!owner).stream()
                .filter(book -> owner || book.catalogPublished())
                .map(book -> toView(book, owner, false))
                .toList();
    }

    @Override
    public BookView getBook(String id, boolean owner) {
        BookEntity book = requireBook(id);
        if (!owner && !book.catalogPublished()) {
            throw unavailable();
        }
        return toView(book, owner, true);
    }

    @Override
    public BookView requireReadableBook(String id, boolean owner) {
        BookView book = getBook(id, owner);
        if (!book.canRead()) {
            throw unavailable();
        }
        return book;
    }

    @Override
    public List<ChapterSummaryView> listChapters(String id, boolean owner) {
        requireReadableBook(id, owner);
        return chapterMapper.findSummaries(id).stream()
                .map(
                        chapter ->
                                new ChapterSummaryView(
                                        chapter.chapterIndex(),
                                        chapter.title(),
                                        chapter.volume(),
                                        chapter.characterCount()))
                .toList();
    }

    @Override
    public ChapterView getChapter(String id, int index, boolean owner) {
        requireReadableBook(id, owner);
        ChapterEntity chapter =
                chapterMapper
                        .findByPosition(id, index)
                        .orElseThrow(LibraryServiceImpl::unavailable);
        List<String> paragraphs =
                chapter.content()
                        .lines()
                        .map(String::strip)
                        .filter(line -> !line.isBlank())
                        .toList();
        return new ChapterView(
                chapter.chapterIndex(),
                chapter.title(),
                chapter.volume(),
                paragraphs,
                chapter.characterCount());
    }

    @Override
    public void validatePosition(String id, int chapterIndex, int paragraphIndex) {
        ChapterView chapter = getChapter(id, chapterIndex, true);
        // 空正文章节也允许保存第零段位置，以便恢复到该章节的开头。
        if (paragraphIndex < 0 || paragraphIndex >= Math.max(1, chapter.paragraphs().size())) {
            throw new IllegalArgumentException("阅读位置超出章节范围。");
        }
    }

    @Override
    public BookView importNovel(byte[] bytes, String filename) throws IOException {
        TxtNovelParser.ParsedNovel parsed = parser.parse(bytes, filename);
        String title = requiredText(parsed.title(), 120, "文件名（书名）");
        String id = UUID.randomUUID().toString();
        BookEntity book =
                new BookEntity(
                        id,
                        title,
                        parsed.author(),
                        "",
                        parsed.encoding(),
                        parsed.chapters().size(),
                        parsed.volumes().size(),
                        parsed.characterCount(),
                        parsed.preface(),
                        sha256(bytes),
                        false,
                        false,
                        clock.millis());
        storage.writeNew(id, bytes);
        try {
            return transactions.execute(
                    status -> {
                        bookMapper.insert(book);
                        List<ChapterEntity> chapters =
                                parsed.chapters().stream()
                                        .map(
                                                chapter ->
                                                        new ChapterEntity(
                                                                id,
                                                                chapter.index(),
                                                                chapter.title(),
                                                                chapter.volume(),
                                                                chapter.content(),
                                                                chapter.characterCount()))
                                        .toList();
                        for (int start = 0; start < chapters.size(); start += CHAPTER_BATCH_SIZE) {
                            chapterMapper.insertBatch(
                                    chapters.subList(
                                            start,
                                            Math.min(start + CHAPTER_BATCH_SIZE, chapters.size())));
                        }
                        return toView(book, true, true);
                    });
        } catch (RuntimeException error) {
            // 此处也能捕获 commit 阶段失败；不能用方法内 try/catch 包住一个延迟提交的注解事务。
            try {
                storage.delete(id);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            if (error instanceof DuplicateKeyException) {
                throw BusinessException.conflict("同一份小说已经导入，无需重复上传。");
            }
            throw error;
        }
    }

    @Override
    public BookView updateBook(String id, BookEditRequest edit) {
        if (edit.textPublished() && !edit.catalogPublished()) {
            throw new IllegalArgumentException("公开正文前，请先公开书目。");
        }
        String title = requiredText(edit.title(), 120, "书名");
        String author = requiredText(edit.author(), 100, "作者");
        String description = edit.description() == null ? "" : edit.description().strip();
        if (description.length() > 4000) {
            throw new IllegalArgumentException("简介不能超过 4000 字符。");
        }
        return transactions.execute(
                status -> {
                    BookEntity old = requireBook(id);
                    BookEntity updated =
                            new BookEntity(
                                    old.id(),
                                    title,
                                    author,
                                    description,
                                    old.encoding(),
                                    old.chapterCount(),
                                    old.volumeCount(),
                                    old.characterCount(),
                                    old.preface(),
                                    old.sha256(),
                                    edit.catalogPublished(),
                                    edit.textPublished(),
                                    old.createdAt());
                    if (bookMapper.updateMetadata(updated) == 0) {
                        throw unavailable();
                    }
                    return toView(updated, true, true);
                });
    }

    @Override
    public void deleteBook(String id) {
        transactions.executeWithoutResult(
                status -> {
                    if (bookMapper.deleteById(id) == 0) {
                        throw unavailable();
                    }
                });
        try {
            storage.delete(id);
        } catch (IOException error) {
            LOG.warn("Private file cleanup failed for book {}", id, error);
        }
    }

    @Override
    public DownloadFile download(String id, boolean owner) throws IOException {
        BookView book = requireReadableBook(id, owner);
        try {
            return new DownloadFile(book, storage.read(id));
        } catch (NoSuchFileException error) {
            throw unavailable();
        }
    }

    private BookEntity requireBook(String id) {
        return bookMapper.findById(id).orElseThrow(LibraryServiceImpl::unavailable);
    }

    private static BookView toView(BookEntity book, boolean owner, boolean includePreface) {
        boolean canRead = owner || (book.catalogPublished() && book.textPublished());
        return new BookView(
                book.id(),
                book.title(),
                book.author(),
                book.description(),
                book.encoding(),
                book.chapterCount(),
                book.volumeCount(),
                book.characterCount(),
                book.catalogPublished(),
                book.textPublished(),
                book.createdAt(),
                canRead,
                includePreface && canRead ? book.preface() : "");
    }

    private static String requiredText(String text, int maxLength, String label) {
        if (text == null || text.isBlank() || text.length() > maxLength) {
            throw new IllegalArgumentException(label + "需为 1 至 " + maxLength + " 个字符。");
        }
        return text.strip();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现。", error);
        }
    }

    private static BusinessException unavailable() {
        return BusinessException.notFound("书籍或章节不存在，或尚未公开。");
    }
}
