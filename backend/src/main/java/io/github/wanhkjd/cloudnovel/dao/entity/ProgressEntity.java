package io.github.wanhkjd.cloudnovel.dao.entity;

/**
 * 主人最近阅读位置，对应 reading_progress 表。
 *
 * @param bookId 所属书籍 UUID
 * @param chapterIndex 从零开始的章节索引
 * @param paragraphIndex 从零开始的非空段落索引
 * @param updatedAt 更新时间，Unix 毫秒
 */
public record ProgressEntity(String bookId, int chapterIndex, int paragraphIndex, long updatedAt) {}
