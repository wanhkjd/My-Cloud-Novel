package io.github.wanhkjd.cloudnovel.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wanhkjd.cloudnovel.service.LibraryService;
import io.github.wanhkjd.cloudnovel.support.DatabaseIntegrationTest;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;

/** 使用真实 HTTP Cookie 和 Redis，而不是 MockHttpSession，验证登录、失效和业务数据隔离。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisSessionIT extends DatabaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions;
    @Autowired LibraryService library;
    CookieManager cookies;
    HttpClient client;

    @BeforeEach
    void prepareBrowserSession() {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client =
                HttpClient.newBuilder()
                        .cookieHandler(cookies)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
    }

    @Test
    void csrfLoginFixationProtectionCookieTtlAndLogoutUseRedis() throws Exception {
        assertThat(sessions).isInstanceOf(RedisSessionRepository.class);
        assertThat(get("/api/me/progress").statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/login", "username=admin&password=incorrect", null).statusCode())
                .isEqualTo(403);
        JsonNode csrf = json.readTree(get("/api/auth/csrf").body());
        String anonymousId = sessionId();
        var login =
                post(
                        "/api/auth/login",
                        "username=admin&password=only-for-isolated-tests-123",
                        csrf);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(String.join(";", login.headers().allValues("set-cookie")))
                .contains("CLOUDNOVEL_SESSION" + "=", "HttpOnly", "SameSite=Lax", "Path=/");
        String authenticatedId = sessionId();
        String oldCookie = sessionCookie();
        assertThat(authenticatedId).isNotEqualTo(anonymousId);
        assertThat(sessions.findById(anonymousId)).isNull();
        assertThat(sessions.findById(authenticatedId)).isNotNull();
        assertThat(redis.getExpire(redisKey(authenticatedId))).isBetween(1L, 43200L);
        assertThat(json.readTree(get("/api/auth/me").body()).path("authenticated").asBoolean())
                .isTrue();
        JsonNode logoutCsrf = json.readTree(get("/api/auth/csrf").body());
        var logout = post("/api/auth/logout", "", logoutCsrf);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(String.join(";", logout.headers().allValues("set-cookie")))
                .contains("CLOUDNOVEL_SESSION=", "Max-Age=0", "Path=/", "HttpOnly", "SameSite=Lax")
                .doesNotContain("JSESSIONID");
        assertThat(sessions.findById(authenticatedId)).isNull();
        assertThat(requestWithCookie(oldCookie).statusCode()).isEqualTo(401);
        JsonNode invalidCsrf = json.readTree(get("/api/auth/csrf").body());
        assertThat(
                        post("/api/auth/login", "username=admin&password=incorrect", invalidCsrf)
                                .statusCode())
                .isEqualTo(401);
    }

    @Test
    void independentClientCanUsePersistedSessionAndRedisDeletionRevokesIt() throws Exception {
        login();
        String cookie = sessionCookie();
        assertThat(requestWithCookie(cookie).statusCode()).isEqualTo(200);
        // 删除的只是本用例的随机会话键：如果应用回退到本机内存会话，此处就不能撤销访问。
        sessions.deleteById(sessionId());
        assertThat(requestWithCookie(cookie).statusCode()).isEqualTo(401);
    }

    @Test
    void expiredLoginDoesNotDeleteMySqlBookOrItsPrivateOriginal() throws Exception {
        var book =
                library.importNovel(
                        "第1章 原创\n会话过期不会删除业务数据。".getBytes(StandardCharsets.UTF_8), "Redis隔离.txt");
        login();
        String cookie = sessionCookie();
        String key = redisKey(sessionId());
        assertThat(redis.expire(key, Duration.ofMillis(1))).isTrue();
        Thread.sleep(50);
        assertThat(requestWithCookie(cookie).statusCode()).isEqualTo(401);
        assertThat(library.getBook(book.id(), true).id()).isEqualTo(book.id());
        assertThat(library.getChapter(book.id(), 0, true).paragraphs()).contains("会话过期不会删除业务数据。");
        assertThat(library.download(book.id(), true).bytes()).isNotEmpty();
    }

    @Test
    void readinessChecksRealDependenciesWithoutDisclosingConnectionDetails() throws Exception {
        var response = get("/api/ready");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode health = json.readTree(response.body());
        assertThat(health.path("status").asText()).isEqualTo("UP");
        assertThat(health.has("components")).isFalse();
        assertThat(health.has("details")).isFalse();
        assertThat(get("/api/health").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("cloud_novel_test");
        try (var connection = redis.getConnectionFactory().getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    private void login() throws Exception {
        JsonNode csrf = json.readTree(get("/api/auth/csrf").body());
        assertThat(
                        post(
                                        "/api/auth/login",
                                        "username=admin&password=only-for-isolated-tests-123",
                                        csrf)
                                .statusCode())
                .isEqualTo(200);
    }

    private HttpResponse<String> get(String route) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(route)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String route, String form, JsonNode csrf) throws Exception {
        var request =
                HttpRequest.newBuilder(uri(route))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form));
        if (csrf != null) {
            request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        }
        return client.send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> requestWithCookie(String cookie) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(uri("/api/me/progress"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Cookie", cookie)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String route) {
        return URI.create("http://127.0.0.1:" + port + route);
    }

    private String cookieValue() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("CLOUDNOVEL_SESSION"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow();
    }

    private String sessionId() {
        return new String(Base64.getDecoder().decode(cookieValue()), StandardCharsets.UTF_8);
    }

    private String sessionCookie() {
        return "CLOUDNOVEL_SESSION" + "=" + cookieValue();
    }

    private String redisKey(String id) {
        return environment.getRequiredProperty("spring.session.redis.namespace")
                + ":sessions:"
                + id;
    }
}
