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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 用真实 HTTP Cookie（Tomcat 内存会话，无 Redis）验证登录、会话固定防护、登出失效与业务数据隔离。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpSessionIT extends DatabaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
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
    void loginRotatesSessionIdSetsCookieFlagsAndLogoutRevokesAccess() throws Exception {
        assertThat(get("/api/me/progress").statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/login", "username=admin&password=incorrect", null).statusCode())
                .isEqualTo(403);
        JsonNode csrf = json.readTree(get("/api/auth/csrf").body());
        String anonymousCookie = cookieValue();
        var login =
                post(
                        "/api/auth/login",
                        "username=admin&password=only-for-isolated-tests-123",
                        csrf);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(String.join(";", login.headers().allValues("set-cookie")))
                .contains("CLOUDNOVEL_SESSION=", "HttpOnly", "SameSite=Lax", "Path=/");
        assertThat(cookieValue()).isNotEqualTo(anonymousCookie); // 会话固定防护：登录后 id 轮换
        assertThat(json.readTree(get("/api/auth/me").body()).path("authenticated").asBoolean())
                .isTrue();
        String authenticated = sessionCookie();
        JsonNode logoutCsrf = json.readTree(get("/api/auth/csrf").body());
        var logout = post("/api/auth/logout", "", logoutCsrf);
        assertThat(logout.statusCode()).isEqualTo(204);
        // 内存会话由容器失效，登出不再下发清除 Cookie 的 Set-Cookie；验证旧会话在服务端确已作废。
        assertThat(logout.headers().allValues("set-cookie")).isEmpty();
        assertThat(requestWithCookie(authenticated).statusCode()).isEqualTo(401); // 旧会话已失效
    }

    @Test
    void logoutDoesNotDeleteMySqlBookOrItsPrivateOriginal() throws Exception {
        var book =
                library.importNovel(
                        "第1章 原创\n登出不会删除业务数据。".getBytes(StandardCharsets.UTF_8), "内存隔离.txt");
        JsonNode csrf = json.readTree(get("/api/auth/csrf").body());
        post("/api/auth/login", "username=admin&password=only-for-isolated-tests-123", csrf);
        String cookie = sessionCookie();
        post("/api/auth/logout", "", json.readTree(get("/api/auth/csrf").body()));
        assertThat(requestWithCookie(cookie).statusCode()).isEqualTo(401);
        assertThat(library.getBook(book.id(), true).id()).isEqualTo(book.id());
        assertThat(library.download(book.id(), true).bytes()).isNotEmpty();
    }

    @Test
    void readinessReportsOnlyDatabaseWithoutDisclosingDetails() throws Exception {
        var response = get("/api/ready");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode health = json.readTree(response.body());
        assertThat(health.path("status").asText()).isEqualTo("UP");
        assertThat(health.has("components")).isFalse();
        assertThat(health.has("details")).isFalse();
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
                .filter(c -> c.getName().equals("CLOUDNOVEL_SESSION"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse("");
    }

    private String sessionCookie() {
        return "CLOUDNOVEL_SESSION=" + cookieValue();
    }
}
