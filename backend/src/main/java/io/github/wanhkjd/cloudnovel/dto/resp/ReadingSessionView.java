package io.github.wanhkjd.cloudnovel.dto.resp;

/**
 * 阅读历史的只读投影。
 *
 * @param id 会话 UUID
 * @param bookId 书籍 UUID
 * @param bookTitle 书名
 * @param chapterIndex 章节索引
 * @param paragraphIndex 段落索引
 * @param chapterTitle 章节标题
 * @param startedAt 开始时间，Unix 毫秒
 * @param elapsedSeconds 累计有效秒数
 * @param updatedAt 更新时间，Unix 毫秒
 */
public record ReadingSessionView(
        String id,
        String bookId,
        String bookTitle,
        int chapterIndex,
        int paragraphIndex,
        String chapterTitle,
        long startedAt,
        int elapsedSeconds,
        long updatedAt) {}
