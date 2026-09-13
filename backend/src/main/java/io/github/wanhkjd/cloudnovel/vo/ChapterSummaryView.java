package io.github.wanhkjd.cloudnovel.vo;

/**
 * 不携带正文的章节目录条目。
 *
 * @param index 从零开始的章节索引
 * @param title 章节标题
 * @param volume 所属卷名
 * @param characterCount 非空白正文码点数
 */
public record ChapterSummaryView(int index, String title, String volume, int characterCount) {}
