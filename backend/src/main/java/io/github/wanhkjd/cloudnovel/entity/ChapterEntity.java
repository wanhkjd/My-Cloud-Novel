package io.github.wanhkjd.cloudnovel.entity;

/**
 * 章节持久化实体，对应 chapters 表。
 *
 * @param bookId 所属书籍 UUID
 * @param chapterIndex 从零开始的章节索引
 * @param title 章节标题
 * @param volume 卷名；无卷时为空字符串
 * @param content 未拆分段落的纯文本正文；目录查询时为空
 * @param characterCount 非空白正文码点数
 */
public record ChapterEntity(
        String bookId,
        int chapterIndex,
        String title,
        String volume,
        String content,
        int characterCount) {}
