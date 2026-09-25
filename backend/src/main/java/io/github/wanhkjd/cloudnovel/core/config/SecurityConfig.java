package io.github.wanhkjd.cloudnovel.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wanhkjd.cloudnovel.dto.resp.AuthView;
import io.github.wanhkjd.cloudnovel.dto.resp.ErrorView;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/** 单管理员安全配置；保留会话认证和 CSRF，不开放注册或数据库管理账号。 */
@Configuration
public class SecurityConfig {
    /** 创建单管理员安全配置。 */
    public SecurityConfig() {}

    /**
     * 从外部配置构建唯一管理员，密码仅在内存中转换为 BCrypt 哈希。
     *
     * @param environment 外部环境配置，不应在日志中打印
     * @return 仅包含一个 ADMIN 用户的认证服务
     * @throws IllegalStateException 密码未配置或短于十二字符
     */
    @Bean
    public UserDetailsService administrator(Environment environment) {
        String password = environment.getRequiredProperty("app.admin.password");
        if (password.isBlank() || password.length() < 12) {
            throw new IllegalStateException("请设置至少 12 位的 ADMIN_PASSWORD 环境变量后再启动。");
        }
        String username = environment.getProperty("app.admin.username", "admin");
        return new InMemoryUserDetailsManager(
                User.withUsername(username)
                        .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password))
                        .roles("ADMIN")
                        .build());
    }

    /**
     * 配置 URL 授权、JSON 登录结果和安全退出；CSRF 保持框架默认启用。
     *
     * <p>退出时由 Servlet 容器（Tomcat {@code HttpSession}）失效会话并按 YAML 中的设置清除 Cookie，不另设旧会话 Cookie。
     *
     * @param http Spring Security 配置入口
     * @param json 统一 JSON 序列化器
     * @return 安全过滤器链
     * @throws Exception 安全配置无法构建
     */
    @Bean
    public SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
        return http.authorizeHttpRequests(
                        auth ->
                                auth
                                        // 必须先匹配主人路径，不能被下方公开 GET 规则覆盖。
                                        .requestMatchers("/api/me/**")
                                        .hasRole("ADMIN")
                                        .requestMatchers(HttpMethod.GET, "/api/**")
                                        .permitAll()
                                        .requestMatchers("/api/auth/login", "/api/auth/logout")
                                        .permitAll()
                                        .requestMatchers("/api/**")
                                        .hasRole("ADMIN")
                                        .anyRequest()
                                        .denyAll())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        writeJson(
                                                                json,
                                                                response,
                                                                401,
                                                                new ErrorView("请先登录管理员账号。")))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        writeJson(
                                                                json,
                                                                response,
                                                                403,
                                                                new ErrorView("请求校验失败，请刷新页面后重试。"))))
                .formLogin(
                        form ->
                                form.loginProcessingUrl("/api/auth/login")
                                        .successHandler(
                                                (request, response, authentication) ->
                                                        writeJson(
                                                                json,
                                                                response,
                                                                200,
                                                                new AuthView(
                                                                        true,
                                                                        authentication.getName())))
                                        .failureHandler(
                                                (request, response, exception) ->
                                                        writeJson(
                                                                json,
                                                                response,
                                                                401,
                                                                new ErrorView("用户名或密码不正确。"))))
                .logout(
                        logout ->
                                logout.logoutUrl("/api/auth/logout")
                                        .logoutSuccessHandler(
                                                (request, response, authentication) ->
                                                        response.setStatus(204)))
                .build();
    }

    private static void writeJson(
            ObjectMapper json, HttpServletResponse response, int status, Object body)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), body);
    }
}
