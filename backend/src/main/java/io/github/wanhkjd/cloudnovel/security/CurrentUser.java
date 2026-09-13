package io.github.wanhkjd.cloudnovel.security;

import org.springframework.security.core.Authentication;

/** 身份转换工具，将框架认证信息转换为业务层需要的主人身份标志。 */
public final class CurrentUser {
    private CurrentUser() {}

    /**
     * 判断当前认证是否属于唯一管理员。
     *
     * @param authentication Spring Security 认证对象，允许为 null
     * @return 仅已认证且持有 ADMIN 角色时为 true
     */
    public static boolean isOwner(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
