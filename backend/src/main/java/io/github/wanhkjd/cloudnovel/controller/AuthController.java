package io.github.wanhkjd.cloudnovel.controller;

import io.github.wanhkjd.cloudnovel.security.CurrentUser;
import io.github.wanhkjd.cloudnovel.vo.AuthView;
import io.github.wanhkjd.cloudnovel.vo.CsrfView;
import io.github.wanhkjd.cloudnovel.vo.HealthView;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证状态与健康检查入口；登录、退出和密码校验由 Spring Security 过滤器处理。 */
@RestController
public class AuthController {
    /** 创建由 Spring Security 提供身份上下文的认证查询控制器。 */
    public AuthController() {}

    /**
     * 获取当前会话 CSRF 信息，登录成功后客户端需重新获取。
     *
     * @param token 安全框架注入的 token
     * @return token 与请求头名
     */
    @GetMapping("/api/auth/csrf")
    public CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getToken(), token.getHeaderName());
    }

    /**
     * 获取当前会话身份。
     *
     * @param authentication 当前认证信息
     * @return 管理员身份或访客状态
     */
    @GetMapping("/api/auth/me")
    public AuthView me(Authentication authentication) {
        boolean owner = CurrentUser.isOwner(authentication);
        return new AuthView(owner, owner ? authentication.getName() : "");
    }

    /**
     * 检查服务进程是否能响应请求，不作为数据库容量或就绪探针。
     *
     * @return 固定健康标识
     */
    @GetMapping("/api/health")
    public HealthView health() {
        return new HealthView("ok");
    }
}
