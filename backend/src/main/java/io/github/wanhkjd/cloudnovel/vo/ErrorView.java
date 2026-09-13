package io.github.wanhkjd.cloudnovel.vo;

/**
 * 统一错误响应，保持前端既有 message 字段约定。
 *
 * @param message 可安全展示给用户的错误说明
 */
public record ErrorView(String message) {}
