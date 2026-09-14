package io.github.wanhkjd.cloudnovel.dao.entity;

/**
 * 累计阅读会话，对应 reading_sessions 表；同一 UUID 的时长只能增加。
 *
 * @param id 客户端生成的会话 UUID
 * @param bookId 所属书籍 UUID
 * @param chapterIndex 最新章节索引
 * @param paragraphIndex 最新段落索引
 * @param startedAt 会话开始时间，Unix 毫秒
 * @param elapsedSeconds 累计有效秒数，范围 1 至 1800
 * @param readingDay 开始时刻对应的北京时间日期，格式 yyyy-MM-dd
 * @param updatedAt 服务器更新时间，Unix 毫秒
 */
public record ReadingSessionEntity(
        String id,
        String bookId,
        int chapterIndex,
        int paragraphIndex,
        long startedAt,
        int elapsedSeconds,
        String readingDay,
        long updatedAt) {}
