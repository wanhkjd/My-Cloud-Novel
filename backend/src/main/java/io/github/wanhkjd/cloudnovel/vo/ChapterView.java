package io.github.wanhkjd.cloudnovel.vo;

import java.util.List;

/**
 * 阅读器需要的单章信息；段落始终按纯文本处理。
 *
 * @param index 章节索引
 * @param title 章节标题
 * @param volume 所属卷名
 * @param paragraphs 按原文顺序排列的非空段落
 * @param characterCount 非空白正文码点数
 */
public record ChapterView(
        int index, String title, String volume, List<String> paragraphs, int characterCount) {}
