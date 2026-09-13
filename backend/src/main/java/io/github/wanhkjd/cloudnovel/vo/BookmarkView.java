package io.github.wanhkjd.cloudnovel.vo;

/**
 * 书签的只读投影；是否能返回给访客由 Service 决定。
 *
 * @param id 书签 UUID
 * @param bookId 书籍 UUID
 * @param bookTitle 书名
 * @param chapterIndex 章节索引
 * @param paragraphIndex 段落索引
 * @param chapterTitle 章节标题
 * @param note 阅读感想
 * @param published 单条感想的公开标记
 * @param createdAt 创建时间，Unix 毫秒
 * @param updatedAt 更新时间，Unix 毫秒
 */
public record BookmarkView(
        String id,
        String bookId,
        String bookTitle,
        int chapterIndex,
        int paragraphIndex,
        String chapterTitle,
        String note,
        boolean published,
        long createdAt,
        long updatedAt) {}
