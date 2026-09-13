package io.github.wanhkjd.cloudnovel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** Redis HTTP 会话的 Cookie 配置；会话生命周期交给 Spring Session，不混入阅读业务数据。 */
@Configuration
public class SessionConfig {
    /** 独立 Cookie 名称，避免与同主机上其他 Java 应用混淆。 */
    public static final String COOKIE_NAME = "CLOUDNOVEL_SESSION";

    /** 创建会话传输配置。 */
    public SessionConfig() {}

    /**
     * 配置仅由 HTTP 传输的会话标识；生产 HTTPS 环境必须开启 Secure。
     *
     * @param secure 是否仅通过 HTTPS 传送 Cookie
     * @return Spring Session 使用的 Cookie 序列化器
     */
    @Bean
    public CookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.secure}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(COOKIE_NAME);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite("Lax");
        return serializer;
    }
}
