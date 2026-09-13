package io.github.wanhkjd.cloudnovel.vo;

/**
 * 最近阅读位置的只读投影，包含关联书名与章节标题。
 *
 * @param bookId 书籍 UUID
 * @param bookTitle 书名
 * @param chapterCount 总章节数
 * @param chapterIndex 章节索引
 * @param paragraphIndex 段落索引
 * @param chapterTitle 章节标题
 * @param updatedAt 更新时间，Unix 毫秒
 */
public record ProgressView(
        String bookId,
        String bookTitle,
        int chapterCount,
        int chapterIndex,
        int paragraphIndex,
        String chapterTitle,
        long updatedAt) {}
