package io.github.wanhkjd.cloudnovel.dto.resp;

/**
 * 当前会话使用的 CSRF 校验信息。
 *
 * @param token 提交修改请求时携带的 CSRF token
 * @param headerName 承载 token 的请求头名称
 */
public record CsrfView(String token, String headerName) {}
