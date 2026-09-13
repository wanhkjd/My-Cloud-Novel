package io.github.wanhkjd.cloudnovel.entity;

/**
 * 主人书签持久化实体，对应 bookmarks 表。
 *
 * @param id 书签 UUID
 * @param bookId 所属书籍 UUID
 * @param chapterIndex 从零开始的章节索引
 * @param paragraphIndex 从零开始的段落索引
 * @param note 主人感想，可为空字符串
 * @param published 是否允许随公开正文展示
 * @param createdAt 创建时间，Unix 毫秒
 * @param updatedAt 更新时间，Unix 毫秒
 */
public record BookmarkEntity(
        String id,
        String bookId,
        int chapterIndex,
        int paragraphIndex,
        String note,
        boolean published,
        long createdAt,
        long updatedAt) {}
