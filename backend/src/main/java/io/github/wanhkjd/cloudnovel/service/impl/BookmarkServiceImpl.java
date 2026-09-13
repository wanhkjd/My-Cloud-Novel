package io.github.wanhkjd.cloudnovel.service.impl;

import io.github.wanhkjd.cloudnovel.dto.BookmarkCreateRequest;
import io.github.wanhkjd.cloudnovel.dto.BookmarkEditRequest;
import io.github.wanhkjd.cloudnovel.entity.BookmarkEntity;
import io.github.wanhkjd.cloudnovel.exception.BusinessException;
import io.github.wanhkjd.cloudnovel.mapper.BookmarkMapper;
import io.github.wanhkjd.cloudnovel.service.BookmarkService;
import io.github.wanhkjd.cloudnovel.service.LibraryService;
import io.github.wanhkjd.cloudnovel.vo.BookmarkView;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 书签业务实现：验证段落、规范感想文本并控制对访客的公开范围。 */
@Service
public class BookmarkServiceImpl implements BookmarkService {
    /** 书签持久化接口。 */
    private final BookmarkMapper bookmarkMapper;

    /** 统一的书籍可读性与位置校验接口。 */
    private final LibraryService libraryService;

    /** 书签写入与返回投影共享的事务。 */
    private final TransactionTemplate transactions;

    /** 感想创建与修改时间使用的服务器时钟。 */
    private final Clock clock;

    /**
     * 注入书签业务依赖。
     *
     * @param bookmarkMapper 书签 Mapper
     * @param libraryService 书库业务接口
     * @param transactions 事务模板
     * @param clock 服务器时钟
     */
    public BookmarkServiceImpl(
            BookmarkMapper bookmarkMapper,
            LibraryService libraryService,
            TransactionTemplate transactions,
            Clock clock) {
        this.bookmarkMapper = bookmarkMapper;
        this.libraryService = libraryService;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public List<BookmarkView> listAll() {
        return bookmarkMapper.findAll();
    }

    @Override
    public List<BookmarkView> listForBook(String bookId, boolean owner) {
        // 公开感想不能绕过书籍正文权限，否则可能间接泄露私人阅读内容。
        libraryService.requireReadableBook(bookId, owner);
        return bookmarkMapper.findByBook(bookId, !owner);
    }

    @Override
    public BookmarkView create(BookmarkCreateRequest input) {
        libraryService.validatePosition(
                input.bookId(), input.chapterIndex(), input.paragraphIndex());
        String id = UUID.randomUUID().toString();
        long now = clock.millis();
        BookmarkEntity entity =
                new BookmarkEntity(
                        id,
                        input.bookId(),
                        input.chapterIndex(),
                        input.paragraphIndex(),
                        normalizeNote(input.note()),
                        input.published(),
                        now,
                        now);
        try {
            return transactions.execute(
                    status -> {
                        bookmarkMapper.insert(entity);
                        return requireBookmark(id);
                    });
        } catch (DuplicateKeyException error) {
            throw BusinessException.conflict("这个位置已经有书签，请编辑原书签。");
        }
    }

    @Override
    public BookmarkView update(String id, BookmarkEditRequest edit) {
        String note = normalizeNote(edit.note());
        return transactions.execute(
                status -> {
                    BookmarkView old = requireBookmark(id);
                    BookmarkEntity updated =
                            new BookmarkEntity(
                                    id,
                                    old.bookId(),
                                    old.chapterIndex(),
                                    old.paragraphIndex(),
                                    note,
                                    edit.published(),
                                    old.createdAt(),
                                    clock.millis());
                    if (bookmarkMapper.update(updated) == 0) {
                        throw missing();
                    }
                    return requireBookmark(id);
                });
    }

    @Override
    public void delete(String id) {
        transactions.executeWithoutResult(
                status -> {
                    if (bookmarkMapper.deleteById(id) == 0) {
                        throw missing();
                    }
                });
    }

    private BookmarkView requireBookmark(String id) {
        return bookmarkMapper.findById(id).orElseThrow(BookmarkServiceImpl::missing);
    }

    private static String normalizeNote(String note) {
        if (note != null && note.length() > 4000) {
            throw new IllegalArgumentException("感想不能超过 4000 字符。");
        }
        return note == null ? "" : note.strip();
    }

    private static BusinessException missing() {
        return BusinessException.notFound("书签不存在。");
    }
}
