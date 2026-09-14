package io.github.wanhkjd.cloudnovel.dto.req;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增段落书签的请求；同一本书的同一位置只能有一条书签。
 *
 * @param bookId 所属书籍 UUID
 * @param chapterIndex 章节索引
 * @param paragraphIndex 段落索引
 * @param note 感想，null 按空字符串处理
 * @param published 是否允许公开，缺省为 false
 */
public record BookmarkCreateRequest(
        @NotBlank String bookId,
        @Min(0) int chapterIndex,
        @Min(0) int paragraphIndex,
        @Size(max = 4000) String note,
        boolean published) {}
