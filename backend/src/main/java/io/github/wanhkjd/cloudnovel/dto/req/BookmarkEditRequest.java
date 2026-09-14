package io.github.wanhkjd.cloudnovel.dto.req;

import jakarta.validation.constraints.Size;

/**
 * 编辑已有书签；不改变绑定的书籍或阅读位置。
 *
 * @param note 新感想，null 按空字符串处理
 * @param published 是否允许公开
 */
public record BookmarkEditRequest(@Size(max = 4000) String note, boolean published) {}
