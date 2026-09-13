package io.github.wanhkjd.cloudnovel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 编辑书目请求；正文公开的前置条件由 Service 校验。
 *
 * @param title 书名，1 至 120 字符
 * @param author 作者，1 至 100 字符
 * @param description 简介，最多 4000 字符，null 按空字符串处理
 * @param catalogPublished 是否公开书目
 * @param textPublished 是否公开正文及原始下载
 */
public record BookEditRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 100) String author,
        @Size(max = 4000) String description,
        boolean catalogPublished,
        boolean textPublished) {}
