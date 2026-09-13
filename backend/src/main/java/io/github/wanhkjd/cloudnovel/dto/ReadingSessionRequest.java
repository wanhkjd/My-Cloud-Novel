package io.github.wanhkjd.cloudnovel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 阅读会话累计上报请求；服务端不会把重试秒数重复相加。
 *
 * @param bookId 所属书籍 UUID
 * @param chapterIndex 最新章节索引
 * @param paragraphIndex 最新段落索引
 * @param elapsedSeconds 累计有效秒数，最多 30 分钟
 * @param startedAt 开始时间，Unix 毫秒
 */
public record ReadingSessionRequest(
        @NotBlank String bookId,
        @Min(0) int chapterIndex,
        @Min(0) int paragraphIndex,
        @Min(1) @Max(1800) int elapsedSeconds,
        @Min(1) long startedAt) {}
