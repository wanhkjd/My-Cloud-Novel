package io.github.wanhkjd.cloudnovel.dto.resp;

/**
 * 当前登录会话的公开信息，不返回密码或会话标识。
 *
 * @param authenticated 是否以管理员身份登录
 * @param username 管理员用户名；访客为空
 */
public record AuthView(boolean authenticated, String username) {}
