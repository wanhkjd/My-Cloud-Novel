package io.github.wanhkjd.cloudnovel.dto.req;

import jakarta.validation.constraints.Min;

/**
 * 保存最近阅读位置的请求。
 *
 * @param chapterIndex 从零开始的章节索引
 * @param paragraphIndex 从零开始的段落索引
 */
public record PositionRequest(@Min(0) int chapterIndex, @Min(0) int paragraphIndex) {}
