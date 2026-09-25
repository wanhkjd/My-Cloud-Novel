# 银河时间轴首页 + 去 Redis 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把项目落定为单管理员的个人书籍博客——彻底移除 Redis 改用 Tomcat 内存会话，为每本书增加自定义时间轴日期与真实封面，并把首页改造成滚动驱动的动态星空银河时间轴。

**Architecture:** 后端保持既有 `controller → service → dao + core` 分层：去 Redis 只动 `pom.xml`/`application*.yml`/测试，业务代码零改；封面作为独立 `core/storage` 组件 + 三个 `/api/books/{id}/cover` 端点；`books` 增两列（`timeline_date`、`cover_path`）。前端保持 Vue 3 `<script setup>` + vue-router + `lib/api.ts`，首页换成 `GalaxyView` 组件族（Canvas 2D 星空 + SVG 银河藤蔓 + DOM 书卡 + IntersectionObserver 揭示），深色样式作用域化，不污染全局暖纸主题。

**Tech Stack:** Spring Boot 3.5.16 / Java 21 / MyBatis 3.0.5 / MySQL；JUnit5 + AssertJ + spring-security-test + ArchUnit + failsafe；Vue 3 + Vite + TypeScript + vue-router；Vitest + Playwright + axe-core。

**Spec:** [docs/superpowers/specs/2026-09-25-galaxy-timeline-and-redis-removal-design.md](../specs/2026-09-25-galaxy-timeline-and-redis-removal-design.md)

## Global Constraints

- **零新增前端依赖**：星空/藤蔓/交互动效一律 Vue + 原生 CSS + Canvas/SVG 手写，不引入动画库（OriginKit 是 React 库，仅借鉴交互种类）。
- **不迁移旧数据**：新库为空库；`schema.sql` 不升级既有表，既有库通过文档中的手工 `ALTER` 升级。
- **不做 `.env`→`cloudnovel.*` profile 迁移**：本轮脚本改动只去 Redis，最小化触碰。
- **深色主题仅首页**：银河深色样式只作用于首页作用域根（`.galaxy-view` / `body.galaxy-route`）；阅读/详情/日志/后台保持暖纸浅色。
- **尊重 `prefers-reduced-motion: reduce`**：所有动效在该媒体查询下降级为静态；内容不依赖动画即可读。
- **封面私有键绝不下发**：`cover_path` 只存 DB，`BookView` 只暴露 `hasCover`（布尔）。
- **后端交付检查**：`mvn verify` 跑 spotless:check（googleJavaFormat AOSP）+ failsafe + javadoc（`doclint all` / `failOnWarnings=true`）——新增公共类型必须有完整 Javadoc。写代码前先 `mvn spotless:apply`。
- **会话 Cookie 名固定 `CLOUDNOVEL_SESSION`**，`HttpOnly` / `SameSite=Lax` / `Path=/`；会话超时 `12h`。
- **集成测试只连接专用 MySQL `cloud_novel_test`**，账号必须是 `cloud_novel_test`，密码来自 `TEST_DB_PASSWORD`；无内存库回退。

## Review Focus

规格是愿景文档，以下 5 类输入/失败模式规格未明确交给任何任务的测试，且最可能伤到真实使用者（最可能在前）。每条已在其归属任务里补了对应测试步骤：

1. **时间轴日期并列**：两本书有效日期（`timelineDate` 或回退 `createdAt`）相同时，若排序不稳定，书卡会在刷新间跳位。有效日期相同必须用 `createdAt`、再 `id` 稳定兜底升序。→ 后端 `findAll` 在 `created_at` 并列时按 `id` 确定序（Task 4 的 Mapper IT）；前端有效日期比较器兜底（Task 14 的 `timeline.test.ts` “orders ascending with deterministic tie-breaking”）。
2. **清空已设的自定义日期**：管理员把日期输入框清空（前端把空串转成 `null`）提交，必须把 `timeline_date` 落成 SQL `NULL`（而非空串或保留旧值），有效日期回退到 `createdAt`。→ 测试见 Task 4（服务层 `LibraryServiceTest.updateBookAppliesThenClearsTimelineDateAndNeverTouchesCover` 断言 `null` 直通 + `MapperIT.updateMetadataClearsTimelineDateToSqlNull` 断言 `updateMetadata` 落 `NULL`）。前端空串→`null` 的转换见 Task 12。
3. **封面 GET 的缺失/越权/畸形 id**：不存在的书、合法 UUID 但无封面、非 UUID 畸形 id、以及未发布书被匿名请求，都必须返回 `404`（绝不 `500`/不泄露），且带 `X-Content-Type-Options: nosniff`。→ 测试见 Task 11。
4. **空书库首页**：一本书都没有时，银河藤蔓 0 节点，滚动生长/初始定位数学不能除零或抛错，必须显示优雅空态。→ 测试见 Task 17（`nodes: []` 时藤蔓仍为装饰 SVG、不渲染任何节点）与 Task 18（`shows a dark empty notice when the galaxy has no books` 断言深色空态提示；`nodes` 计算以 `n <= 1 ? 0` 守卫，空库/单本不除零）。
5. **无 IntersectionObserver / reduced-motion**：老浏览器无 `IntersectionObserver` 时书卡必须默认全部可见（绝不永久隐藏）；reduced-motion 下无动画但内容齐全。→ 测试见 Task 18。

---

## Phase 1 — 去 Redis（会话内存化）

产出可独立验证：`mvn verify` 通过，启动后 `/api/ready` 只含 `db`，登录/登出/CSRF 用内存会话正常。

### Task 1: 移除 Redis / Spring Session 依赖与 YAML

**Files:**
- Modify: `backend/pom.xml:39-46`（删两个依赖）、`backend/pom.xml:79`（改注释）
- Modify: `backend/src/main/resources/application.yml:33-49`（删 redis 块与 session 块）、`:4-11`（会话超时并入 server.servlet.session）、`:56`（改注释）
- Modify: `backend/src/main/resources/application-dev.yml.example:11-16`（删 redis 块）

**Interfaces:**
- Produces: 运行期不再存在 `spring.data.redis.*` / `spring.session.*` 属性；新增 `server.servlet.session.timeout: 12h`；`RedisHealthContributor` 随依赖消失，`/api/ready` 只剩 `db`。

- [ ] **Step 1: 先改 `ApplicationConfigurationTest` 到去 Redis 后的期望（红）**

在 `runtimeConnectionSettingsComposeFromDevNamespace()` 里删掉 redis 绑定与 `spring.session` 断言（原第 85-103 行整段），替换为：

```java
        // 会话超时改由 Servlet 容器承载；运行期不应再出现 Redis / Spring Session 属性。
        var server = binder.bind("server", ServerProperties.class).get();
        assertThat(server.getServlet().getSession().getTimeout()).isEqualTo(Duration.ofHours(12));
        assertThat(environment.getProperty("spring.data.redis.host")).isNull();
        assertThat(environment.containsProperty("spring.session.timeout")).isFalse();
        assertThat(environment.containsProperty("spring.session.redis.namespace")).isFalse();
```

- [ ] **Step 2: 把 cookie 测试改成纯 YAML 绑定断言**

删除 `bootCreatesTheSessionCookieDirectlyFromYaml(boolean secure)` 整个方法（原第 183-222 行），新增：

```java
    @Test
    void sessionCookieAndTimeoutBindFromYamlWithoutSpringSession() throws IOException {
        var server = Binder.get(configuredEnvironment()).bind("server", ServerProperties.class).get();
        var cookie = server.getServlet().getSession().getCookie();
        assertThat(cookie.getName()).isEqualTo("CLOUDNOVEL_SESSION");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo(org.springframework.boot.web.servlet.server.Cookie.SameSite.LAX);
        assertThat(cookie.getSecure()).isFalse();
        assertThat(server.getServlet().getSession().getTimeout()).isEqualTo(Duration.ofHours(12));
    }
```

- [ ] **Step 3: 清掉 redis 覆盖断言与凭据行**

- `devNamespaceOverridesRecomposeRuntimeSettings()`：删除设置 `cloudnovel.redis.*` 的 4 个 `.withProperty(...)`（原第 145-148 行）与断言 `spring.data.redis.*` 的 4 段（原第 156-161 行）。
- `connectionCredentialsHaveNoHardcodedDefaults` 的 `@CsvSource` 删除 `"spring.data.redis.password,cloudnovel.redis.password"` 行（原第 173 行）。
- `configuredEnvironment()` 删除 5 个 `cloudnovel.redis.*` 的 `.withProperty(...)`（原第 232-236 行）。
- 删除现在未使用的 import：`RedisProperties`、`SessionProperties`、`SessionAutoConfiguration`、`SessionRepository`、`CookieSerializer`、`AutoConfigurations`、`ServletWebServerFactoryAutoConfiguration`、`ConfigDataApplicationContextInitializer`、`WebApplicationContextRunner`、`MockHttpServletRequest`、`MockHttpServletResponse`、`HttpHeaders`、静态 `mock`。

- [ ] **Step 4: 跑配置测试确认红**

Run: `mvn -q -pl backend test -Dtest=ApplicationConfigurationTest`
Expected: FAIL——`server.servlet.session.timeout` 尚未定义（当前为 `spring.session.timeout`），且 `spring.data.redis.host` 仍存在。

- [ ] **Step 5: 改 `application.yml`——会话超时并入 server、删 redis/session 块**

把 `server.servlet.session` 改为（新增 `timeout`，保留 cookie）：

```yaml
  servlet:
    session:
      timeout: 12h
      cookie:
        name: CLOUDNOVEL_SESSION
        path: /
        http-only: true
        same-site: lax
        secure: false
```

删除 `spring` 下从 `# Redis 只保存...` 注释起到 `spring.session` 块结束的整段（原第 33-49 行：`data.redis.*` 与 `session.*`），使 `spring` 直接由 `datasource` → `sql` → `servlet.multipart` 组成。把原第 56 行注释改为：`# /api/health 仅检查进程；/api/ready 检查真实 MySQL，不暴露连接详情。`

- [ ] **Step 6: 改 `backend/pom.xml`——删两个依赖、修注释**

删除 `spring-boot-starter-data-redis`（原第 39-42 行）与 `spring-session-data-redis`（原第 43-46 行）两个 `<dependency>`。把原第 79 行注释改为：`<!-- 集成测试只连接专用 MySQL；没有内存数据库替代或自动跳过。 -->`

- [ ] **Step 7: 改 `application-dev.yml.example`——删 redis 占位块**

删除 `redis:` 整块（原第 11-16 行），只留 `datasource` / `admin` / `storage-directory`。

- [ ] **Step 8: 改 `SecurityConfig` / `HealthController` / `HealthControllerTest` 残留 Redis/Spring Session 的 Javadoc**

- `SecurityConfig.java:50`：把 `<p>退出时由 Spring Session 失效会话并按 YAML 中的设置清除 Cookie，不另设旧会话 Cookie。` 改为 `<p>退出时由 Servlet 容器（Tomcat `HttpSession`）失效会话并按 YAML 中的设置清除 Cookie，不另设旧会话 Cookie。`
- `HealthController.java:14`：把 Javadoc `检查服务进程是否能响应请求，不触发数据库或 Redis 连接。` 改为 `检查服务进程是否能响应请求，不触发数据库连接。`
- `HealthControllerTest.java:10`：把 Javadoc `/** 存活端点迁移后保持原 HTTP 契约，且无需初始化 MySQL、Redis 或认证服务。 */` 改为 `/** 存活端点迁移后保持原 HTTP 契约，且无需初始化 MySQL 或认证服务。 */`
- 用 `Grep -n "Redis|Spring Session"` 在 `backend/src/main/java` 复核无其它残留（预期为 0——主代码里 `SecurityConfig`/`HealthController` 两处已改完）。`backend/src/test/java` 仍有 Redis 字样属正常：`ApplicationConfigurationTest` 的“断言 redis 不存在”负向断言（含 `spring.data.redis.host` / `spring.session.redis.namespace` 字符串）保留，support 类与 `RedisSessionIT` 由 Step 9 处理。

- [ ] **Step 9: 从测试支撑类移除 Redis**

- `support/DatabaseIntegrationTest.java`：删 `@Autowired protected StringRedisTemplate redis;` 与 `StringRedisTemplate`/`ScanOptions` import；删 `cleanOnlyIsolatedFixtures()` 里 namespace 断言与 redis 扫描删除段（原第 38-45 行），只保留清库 + 删原件。
- `support/IntegrationSettings.java`：删 `properties(...)` 的 `namespace` 形参与其正则校验（原第 24-27 行）、所有 `spring.data.redis.*` 与 `spring.session.redis.namespace` 键（原第 40-52 行）。
- `support/TestInfrastructure.java`：`rejectExternalConnectionOverrides` 的列表只留 `spring.datasource.jndi-name`（删 4 个 redis 项）；调用改为 `IntegrationSettings.properties(System.getenv(), directory)`。
- `support/IntegrationSettingsTest.java`：删 `refusesSharedRedisNamespace`、`redisAutoConfigurationBindsIsolatedSettingsWithoutOpeningAConnection` 两个方法与 redis 相关 import；`rejectsConnectionOverrides...` 列表只留 `spring.datasource.jndi-name`；`neverInheritsRuntimeCredentials...` 删 redis 断言；所有 `properties(...)` 调用去掉 `namespace` 实参。
- 删除 `backend/src/test/java/io/github/wanhkjd/cloudnovel/core/auth/RedisSessionIT.java`（其功能由 Task 2 的内存版接管）。

- [ ] **Step 10: 格式化并跑全量后端测试（绿）**

Run: `mvn -q -pl backend spotless:apply && mvn -q -pl backend test`
Expected: PASS，且无 `spring-data-redis` / `spring-session` 编译引用残留。

- [ ] **Step 11: 提交**

```bash
git add backend/pom.xml backend/src/main/resources backend/src/test/java
git commit -m "refactor: drop Redis and use in-memory servlet sessions

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 2: 内存会话的登录/登出端到端测试（替代 RedisSessionIT）

删除 RedisSessionIT 会丢掉唯一覆盖“真实登录 Cookie → 会话固定防护 → 登出失效 → CSRF”的用例（`LibraryApiIT` 用的是 `.with(user(...))` 模拟认证，不走真实会话）。本任务用内存会话补回，且不依赖任何会话仓库 Bean。

**Files:**
- Create: `backend/src/test/java/io/github/wanhkjd/cloudnovel/core/auth/HttpSessionIT.java`

**Interfaces:**
- Consumes: Task 1 后的内存会话；`DatabaseIntegrationTest`（不再注入 redis）；`LibraryService.importNovel`。

- [ ] **Step 1: 写失败测试（真实 HTTP，无 Redis）**

```java
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
```

（续下一步——同一文件。）

```java
    @BeforeEach
    void prepareBrowserSession() {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Test
    void loginRotatesSessionIdSetsCookieFlagsAndLogoutRevokesAccess() throws Exception {
        assertThat(get("/api/me/progress").statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/login", "username=admin&password=incorrect", null).statusCode()).isEqualTo(403);
        JsonNode csrf = json.readTree(get("/api/auth/csrf").body());
        String anonymousCookie = cookieValue();
        var login = post("/api/auth/login", "username=admin&password=only-for-isolated-tests-123", csrf);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(String.join(";", login.headers().allValues("set-cookie")))
                .contains("CLOUDNOVEL_SESSION=", "HttpOnly", "SameSite=Lax", "Path=/");
        assertThat(cookieValue()).isNotEqualTo(anonymousCookie); // 会话固定防护：登录后 id 轮换
        assertThat(json.readTree(get("/api/auth/me").body()).path("authenticated").asBoolean()).isTrue();
        String authenticated = sessionCookie();
        JsonNode logoutCsrf = json.readTree(get("/api/auth/csrf").body());
        var logout = post("/api/auth/logout", "", logoutCsrf);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(String.join(";", logout.headers().allValues("set-cookie")))
                .contains("CLOUDNOVEL_SESSION=", "Max-Age=0", "Path=/");
        assertThat(requestWithCookie(authenticated).statusCode()).isEqualTo(401); // 旧会话已失效
    }

    @Test
    void logoutDoesNotDeleteMySqlBookOrItsPrivateOriginal() throws Exception {
        var book = library.importNovel("第1章 原创\n登出不会删除业务数据。".getBytes(StandardCharsets.UTF_8), "内存隔离.txt");
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
```

（辅助方法见下一步。）

- [ ] **Step 2: 补齐辅助方法并收尾类**

```java
    private HttpResponse<String> get(String route) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(route)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String route, String form, JsonNode csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri(route)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (csrf != null) request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> requestWithCookie(String cookie) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/api/me/progress")).timeout(Duration.ofSeconds(10))
                        .header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String route) {
        return URI.create("http://127.0.0.1:" + port + route);
    }

    private String cookieValue() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("CLOUDNOVEL_SESSION"))
                .map(HttpCookie::getValue).findFirst().orElse("");
    }

    private String sessionCookie() {
        return "CLOUDNOVEL_SESSION=" + cookieValue();
    }
}
```

- [ ] **Step 3: 跑测试确认通过（需专用 MySQL 与 `TEST_DB_PASSWORD`）**

Run: `mvn -q -pl backend verify -Dit.test=HttpSessionIT -DfailIfNoTests=false`
Expected: PASS——登录后 cookie 值改变、登出返回 204 且旧 cookie 401、`/api/ready` 只报 `db`。

- [ ] **Step 4: 提交**

```bash
git add backend/src/test/java/io/github/wanhkjd/cloudnovel/core/auth/HttpSessionIT.java
git commit -m "test: cover in-memory session login and logout end to end

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 2 — DB 两列 + 后端封面与时间轴日期

产出可独立验证：`mvn -pl backend verify` 通过；`books` 有 `timeline_date`/`cover_path` 两列；`BookView` 多出 `timelineDate`/`hasCover`（无 `coverPath`）；三个 `/api/books/{id}/cover` 端点按魔数校验、可见性与 `nosniff` 工作。

> **迁移前置（每个集成测试任务开跑前一次性执行）**：Phase 2 给 `books` 加了两列，而 `schema.sql` 声明“不升级既有表”且 `spring.sql.init.mode=never`。因此**在跑任何 Mapper IT / API IT 前**，必须先对专用测试库执行 Task 3 给出的 `ALTER`（`cloud_novel_test`），否则集成测试会因缺列而红。单元测试（`LibraryServiceTest`/`CoverFormatTest`/`CoverImageStorageTest`）不连库、不受影响。

### Task 3: `books` 加 `timeline_date` / `cover_path` 两列（schema.sql + 手工迁移文档）

**Files:**
- Modify: `deploy/mysql/schema.sql`（`books` 建表：`created_at` 后加 `timeline_date DATE NULL`，`preface` 保持；末尾加 `cover_path VARCHAR(255) NULL`；扩展迁移注释）

**Interfaces:**
- Produces: 新库 `books` 含 `timeline_date DATE NULL`、`cover_path VARCHAR(255) NULL`；既有库（含 `cloud_novel_test`）的手工 `ALTER` 语句。被 Task 4/5/8/10/11 的集成测试消费。

- [ ] **Step 1: 在 `books` 建表语句加两列**

在 `books` 的 `CREATE TABLE` 中，`created_at BIGINT ...` 之后新增一行 `timeline_date DATE NULL COMMENT '自定义时间轴日期，可空；为空时前端回退 created_at',`，并在 `sha256` 之后（或 `text_published` 之后、约束之前）新增 `cover_path VARCHAR(255) NULL COMMENT '封面对象键，如 covers/{id}.jpg；绝不下发前端',`。不改既有 `INDEX idx_books_created (created_at, id)` 与可见性 `CHECK`。

- [ ] **Step 2: 扩展文件顶部“不升级既有表”注释，补两条 ALTER**

在 schema.sql 顶部“不升级既有表”说明处，追加既有库手工迁移块（同样适用于 `cloud_novel_test`）：

```sql
-- 既有库升级（新库直接建表，无需执行）：
--   ALTER TABLE books ADD COLUMN timeline_date DATE NULL AFTER created_at;
--   ALTER TABLE books ADD COLUMN cover_path VARCHAR(255) NULL;
```

- [ ] **Step 3: 对专用测试库执行迁移（为后续集成测试铺路）**

Run: `mysql --host=127.0.0.1 --user=cloud_novel_test --password="$TEST_DB_PASSWORD" cloud_novel_test -e "ALTER TABLE books ADD COLUMN timeline_date DATE NULL AFTER created_at; ALTER TABLE books ADD COLUMN cover_path VARCHAR(255) NULL;"`
Expected: 两列添加成功（若列已存在报 1060，可忽略）。此步为运维动作，非自动化测试。

- [ ] **Step 4: 提交**

```bash
git add deploy/mysql/schema.sql
git commit -m "feat: add timeline date and cover path columns to books schema

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 4: 领域模型贯通 `timeline_date` / `cover_path`（entity/dto/xml/service + 现有 fixture）

一次性把两列加进 `BookEntity`（位置构造，改一处即破全部调用点，必须原子）。`timelineDate` 可编辑并进 `BookView`；`coverPath` 只留在实体、`BookView` 只暴露 `hasCover`。`updateMetadata` 写 `timeline_date`、**不写** `cover_path`（编辑元数据不得清空封面）。

**Files:**
- Modify: `backend/src/main/java/io/github/wanhkjd/cloudnovel/dao/entity/BookEntity.java`
- Modify: `backend/src/main/java/io/github/wanhkjd/cloudnovel/dto/resp/BookView.java`
- Modify: `backend/src/main/java/io/github/wanhkjd/cloudnovel/dto/req/BookEditRequest.java`
- Modify: `backend/src/main/resources/mapper/BookMapper.xml`
- Modify: `backend/src/main/java/io/github/wanhkjd/cloudnovel/service/impl/LibraryServiceImpl.java`（`importNovel`:161-175、`updateBook`:229-243、`toView`:280-296）
- Modify: `backend/src/test/java/io/github/wanhkjd/cloudnovel/service/impl/LibraryServiceTest.java`
- Modify: `backend/src/test/java/io/github/wanhkjd/cloudnovel/dao/mapper/MapperIT.java`

**Interfaces:**
- Consumes: Task 3 两列（含已迁移的 `cloud_novel_test`）。
- Produces:
  - `BookEntity(String id, String title, String author, String description, String encoding, int chapterCount, int volumeCount, long characterCount, String preface, String sha256, boolean catalogPublished, boolean textPublished, long createdAt, LocalDate timelineDate, String coverPath)` — 15 参。
  - `BookView(..., long createdAt, boolean canRead, String preface, LocalDate timelineDate, boolean hasCover)` — 15 参；**无** `coverPath()` 访问器。
  - `BookEditRequest(..., boolean catalogPublished, boolean textPublished, LocalDate timelineDate)` — 6 参，`timelineDate` 可空。
  - `updateMetadata` SQL 现含 `timeline_date = #{timelineDate}`，不含 `cover_path`。

- [ ] **Step 1: 改 `LibraryServiceTest`——fixture 拆分、补日期/封面断言（红）**

顶部加 `import java.time.LocalDate;`。把 `book(boolean, boolean)` 换成委托，并新增可指定日期/封面的 `bookWith`：

```java
    private BookEntity book(boolean catalogue, boolean text) {
        return bookWith(catalogue, text, null, null);
    }

    private BookEntity bookWith(
            boolean catalogue, boolean text, LocalDate timelineDate, String coverPath) {
        return new BookEntity(
                id, "测试", "作者", "", "UTF-8", 1, 0, 10, "私有前言", "a".repeat(64),
                catalogue, text, 1, timelineDate, coverPath);
    }
```

把 `cannotPublishTextWithoutCatalogueOrAcceptInvalidMetadata` 里两个 `new BookEditRequest(...)` 补第 6 参 `null`：`new BookEditRequest("书名", "作者", "", false, true, null)` 与 `new BookEditRequest(" ", "作者", "", false, false, null)`。

新增三个测试（`ArgumentCaptor` / `verify` / `times` 已在本类用到，`ArgumentCaptor.captor()` 是 Mockito 5 静态工厂）：

```java
    @Test
    void updateBookAppliesThenClearsTimelineDateAndNeverTouchesCover() {
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, false, null, "covers/" + id + ".jpg")));
        when(books.updateMetadata(any())).thenReturn(1);
        ArgumentCaptor<BookEntity> saved = ArgumentCaptor.captor();

        var set = service.updateBook(id, new BookEditRequest("书名", "作者", "", true, false, LocalDate.parse("2026-09-25")));
        var cleared = service.updateBook(id, new BookEditRequest("书名", "作者", "", true, false, null));

        verify(books, times(2)).updateMetadata(saved.capture());
        assertThat(saved.getAllValues()).extracting(BookEntity::timelineDate)
                .containsExactly(LocalDate.parse("2026-09-25"), null); // 清空的自定义日期落 null
        assertThat(saved.getAllValues())
                .allSatisfy(e -> assertThat(e.coverPath()).isEqualTo("covers/" + id + ".jpg")); // 编辑元数据不动封面
        assertThat(set.timelineDate()).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(cleared.timelineDate()).isNull();
    }

    @Test
    void viewReportsHasCoverWithoutExposingPath() {
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, true, null, "covers/" + id + ".webp")));
        assertThat(service.getBook(id, true).hasCover()).isTrue();
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, true, null, null)));
        assertThat(service.getBook(id, true).hasCover()).isFalse(); // 无封面 → false，BookView 无 coverPath() 可泄露
    }

    @Test
    void importLeavesTimelineAndCoverUnset() throws Exception {
        service.importNovel("第一章 测试\n原创段落。".getBytes(StandardCharsets.UTF_8), "测试.txt");
        ArgumentCaptor<BookEntity> inserted = ArgumentCaptor.captor();
        verify(books).insert(inserted.capture());
        assertThat(inserted.getValue().timelineDate()).isNull();
        assertThat(inserted.getValue().coverPath()).isNull();
    }
```

- [ ] **Step 2: 改 `MapperIT`——fixture 补参、加日期/封面/并列排序 IT（红）**

顶部加 `import java.time.LocalDate;`。`seedBook()`（170-184）末尾两个位置补 `, null, null`；`published`（40-53）末尾补 `, book.timelineDate(), book.coverPath()`；`duplicate`（57-71）末尾补 `, null, null`。新增三个测试：

```java
    @Test
    void persistsTimelineDateAndCoverPathRoundTrip() {
        String id = UUID.randomUUID().toString();
        BookEntity book = new BookEntity(id, "时间轴书", "作者", "", "UTF-8", 1, 0, 5, "前言",
                "a".repeat(64), true, false, 2000, LocalDate.parse("2026-09-25"), "covers/" + id + ".jpg");
        books.insert(book);
        BookEntity stored = books.findById(id).orElseThrow();
        assertThat(stored.timelineDate()).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(stored.coverPath()).isEqualTo("covers/" + id + ".jpg");
    }

    @Test
    void updateMetadataClearsTimelineDateToSqlNull() {
        String id = UUID.randomUUID().toString();
        books.insert(new BookEntity(id, "有日期", "作者", "", "UTF-8", 1, 0, 5, "",
                "b".repeat(64), true, false, 3000, LocalDate.parse("2026-01-02"), null));
        BookEntity cleared = new BookEntity(id, "有日期", "作者", "", "UTF-8", 1, 0, 5, "",
                "b".repeat(64), true, false, 3000, null, null);
        assertThat(books.updateMetadata(cleared)).isEqualTo(1);
        assertThat(books.findById(id).orElseThrow().timelineDate()).isNull(); // 空日期落 SQL NULL
    }

    @Test
    void findAllBreaksCreatedAtTiesDeterministicallyById() {
        // created_at 并列时须给确定的 created_at DESC, id 次序，否则银河书卡刷新会跳位。
        String low = "00000000-0000-0000-0000-000000000000";
        String high = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        books.insert(new BookEntity(high, "同刻B", "作者", "", "UTF-8", 1, 0, 5, "",
                "c".repeat(64), true, false, 7000, null, null));
        books.insert(new BookEntity(low, "同刻A", "作者", "", "UTF-8", 1, 0, 5, "",
                "d".repeat(64), true, false, 7000, null, null));
        assertThat(books.findAll(true)).extracting(BookEntity::id).containsExactly(low, high);
    }
```

- [ ] **Step 3: 跑测试确认红**

Run: `mvn -q -pl backend test -Dtest=LibraryServiceTest`
Expected: FAIL——编译失败：`BookEntity`/`BookView`/`BookEditRequest` 构造参数数量与 `timelineDate()`/`hasCover()` 访问器尚不存在。

- [ ] **Step 4: 给 `BookEntity` 加两列（绿的第一步）**

在 `long createdAt` 后追加两个组件，并补 Javadoc `@param`：

```java
        boolean textPublished,
        long createdAt,
        java.time.LocalDate timelineDate,
        String coverPath) {}
```

Javadoc 在 `@param createdAt ...` 后加：

```java
 * @param timelineDate 自定义时间轴日期，可空；为空时前端回退 createdAt
 * @param coverPath 封面对象键（如 covers/{id}.jpg），仅存储用，绝不下发前端
```

（MyBatis `map-underscore-to-camel-case` + `arg-name-based-constructor-auto-mapping` 把 `timeline_date`→`timelineDate`；mybatis-spring-boot-starter 内置 JSR-310 `LocalDateTypeHandler`，MySQL `DATE` ↔ `LocalDate` 自动。）

- [ ] **Step 5: 给 `BookView` 加 `timelineDate` / `hasCover`（不加 `coverPath`）**

在 `String preface` 后追加：

```java
        boolean canRead,
        String preface,
        java.time.LocalDate timelineDate,
        boolean hasCover) {}
```

Javadoc 在 `@param preface ...` 后加：

```java
 * @param timelineDate 自定义时间轴日期，序列化为 "yyyy-MM-dd" 或 null
 * @param hasCover 是否已上传封面；前端据此决定是否请求 /api/books/{id}/cover
```

- [ ] **Step 6: 给 `BookEditRequest` 加可空 `timelineDate`**

在 `boolean textPublished` 后追加（无校验注解，Jackson 按 ISO `yyyy-MM-dd` 解析，前端空串提交为 `null`）：

```java
        boolean catalogPublished,
        boolean textPublished,
        java.time.LocalDate timelineDate) {}
```

Javadoc 在 `@param textPublished ...` 后加：

```java
 * @param timelineDate 自定义时间轴日期，可空；前端清空日期框时提交 null
```

- [ ] **Step 7: 改 `BookMapper.xml`——findAll/findById 增选列、insert 增列与值、updateMetadata 增 `timeline_date`**

`findAll` 与 `findById` 的 select 列尾（`created_at` 后）都追加 `, timeline_date, cover_path`（`findAll` 仍保留 `'' AS preface`）。`insert` 列清单与 VALUES 各在 `created_at` / `#{createdAt}` 后追加 `, timeline_date, cover_path` 与 `, #{timelineDate}, #{coverPath}`。`updateMetadata` 在 `text_published = #{textPublished}` 后追加 `, timeline_date = #{timelineDate}`（**不加** `cover_path`）：

```xml
  <update id="updateMetadata">
    UPDATE books SET title = #{title}, author = #{author}, description = #{description},
                     catalog_published = #{catalogPublished}, text_published = #{textPublished},
                     timeline_date = #{timelineDate}
    WHERE id = #{id}
  </update>
```

- [ ] **Step 8: 改 `LibraryServiceImpl` 三处构造**

- `importNovel`（约 161-175）末尾 `clock.millis())` 改为 `clock.millis(), null, null)`（导入时日期与封面皆空）。
- `updateBook`（约 229-243）末尾 `old.createdAt())` 改为 `old.createdAt(), edit.timelineDate(), old.coverPath())`（写入编辑日期，保留既有封面）。
- `toView`（约 280-296）末尾 `includePreface && canRead ? book.preface() : "")` 改为 `includePreface && canRead ? book.preface() : "", book.timelineDate(), book.coverPath() != null)`。

- [ ] **Step 9: 格式化并跑单元测试（绿）**

Run: `mvn -q -pl backend spotless:apply && mvn -q -pl backend test -Dtest=LibraryServiceTest`
Expected: PASS——日期应用/清空为 `null`、导入日期封面为空、`hasCover` 由 `coverPath` 派生且 `BookView` 无 `coverPath()` 泄露。

- [ ] **Step 10: 跑 Mapper 集成测试（绿；需已迁移的 `cloud_novel_test`）**

Run: `mvn -q -pl backend verify -Dit.test=MapperIT -DfailIfNoTests=false`
Expected: PASS——两列往返、`updateMetadata` 清日期落 `NULL`、`created_at` 并列时按 `id` 确定序（`low` 先于 `high`）。

- [ ] **Step 11: 提交**

```bash
git add backend/src/main/java backend/src/main/resources/mapper/BookMapper.xml backend/src/test/java
git commit -m "feat: thread timeline date and cover path through the book model

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 5: `BookMapper.updateCover` 独立更新封面键（不走 `updateMetadata`）

封面键必须与元数据编辑解耦，否则管理员改书名会误清空封面（spec C3）。本任务加独立 `updateCover`，用 `MapperXmlTest`（断言每个声明方法都有可解析语句）驱动红。

**Files:**
- Modify: `backend/src/main/java/io/github/wanhkjd/cloudnovel/dao/mapper/BookMapper.java`
- Modify: `backend/src/main/resources/mapper/BookMapper.xml`
- Modify: `backend/src/test/java/io/github/wanhkjd/cloudnovel/dao/mapper/MapperIT.java`

**Interfaces:**
- Consumes: Task 4 的 `cover_path` 列。
- Produces: `int updateCover(@Param("id") String id, @Param("coverPath") String coverPath)`（`coverPath` 可为 `null`）。被 Task 8 消费。

- [ ] **Step 1: 在 `BookMapper` 接口加 `updateCover`（红）**

在 `deleteById` 之前加：

```java
    /**
     * 仅更新封面对象键，与元数据编辑解耦，避免编辑书目时误清空封面。
     *
     * @param id 书籍 UUID
     * @param coverPath 封面对象键，或 null 表示清除封面
     * @return 受影响行数
     */
    int updateCover(@Param("id") String id, @Param("coverPath") String coverPath);
```

- [ ] **Step 2: 跑 `MapperXmlTest` 确认红**

Run: `mvn -q -pl backend test -Dtest=MapperXmlTest`
Expected: FAIL——`BookMapper.updateCover` 无可解析语句（`hasStatement` 断言失败）。

- [ ] **Step 3: 在 `BookMapper.xml` 加 `updateCover` 语句（绿）**

在 `deleteById` 之前加：

```xml
  <update id="updateCover">
    UPDATE books SET cover_path = #{coverPath} WHERE id = #{id}
  </update>
```

- [ ] **Step 4: 在 `MapperIT` 加封面往返与“编辑不清封面”IT**

```java
    @Test
    void updateCoverSetsThenClearsCoverPathIndependently() {
        String id = seedBook(true, false).id();
        assertThat(books.updateCover(id, "covers/" + id + ".jpg")).isEqualTo(1);
        assertThat(books.findById(id).orElseThrow().coverPath()).isEqualTo("covers/" + id + ".jpg");
        assertThat(books.updateCover(id, null)).isEqualTo(1);
        assertThat(books.findById(id).orElseThrow().coverPath()).isNull();
    }

    @Test
    void updateMetadataNeverTouchesCoverPath() {
        BookEntity seeded = seedBook(true, false);
        books.updateCover(seeded.id(), "covers/" + seeded.id() + ".png");
        BookEntity edit = new BookEntity(seeded.id(), "改名后", "作者", "新简介", seeded.encoding(),
                seeded.chapterCount(), seeded.volumeCount(), seeded.characterCount(), seeded.preface(),
                seeded.sha256(), true, false, seeded.createdAt(), null, null);
        assertThat(books.updateMetadata(edit)).isEqualTo(1);
        assertThat(books.findById(seeded.id()).orElseThrow().coverPath())
                .isEqualTo("covers/" + seeded.id() + ".png"); // 编辑元数据保留封面
    }
```

（`seedBook(boolean, boolean)` 是 Task 2 已补 `, null, null` 的现有夹具，返回持久化后的 `BookEntity`；若其签名不含公开标志，改用 `seedBook()` 并按需 `updateMetadata` 公开。）

- [ ] **Step 5: 跑测试确认绿（需已迁移的 `cloud_novel_test`）**

Run: `mvn -q -pl backend verify -Dit.test=MapperIT -Dtest=MapperXmlTest -DfailIfNoTests=false`
Expected: PASS——`updateCover` 独立置/清 `cover_path`，`updateMetadata` 不动 `cover_path`。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/github/wanhkjd/cloudnovel/dao/mapper/BookMapper.java backend/src/main/resources/mapper/BookMapper.xml backend/src/test/java/io/github/wanhkjd/cloudnovel/dao/mapper/MapperIT.java
git commit -m "feat: add independent cover path update to book mapper

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 6: `CoverFormat` 魔数嗅探 + `StoredImage` 记录

服务端**只信魔数**判定图片真实类型（拒 svg/html/gif），不信客户端声明的 `Content-Type`（spec C5、风险 4）。本任务是纯逻辑，先写全覆盖单测。

**Files:**
- Create: `backend/src/main/java/io/github/wanhkjd/cloudnovel/core/storage/CoverFormat.java`
- Create: `backend/src/main/java/io/github/wanhkjd/cloudnovel/core/storage/StoredImage.java`
- Test: `backend/src/test/java/io/github/wanhkjd/cloudnovel/core/storage/CoverFormatTest.java`

**Interfaces:**
- Produces:
  - `enum CoverFormat { JPEG, PNG, WEBP }`，实例方法 `String extension()`（`jpg`/`png`/`webp`）、`String contentType()`（`image/jpeg`/`image/png`/`image/webp`）；静态 `Optional<CoverFormat> detect(byte[] bytes)`、`Optional<CoverFormat> fromExtension(String extension)`。
  - `record StoredImage(byte[] bytes, String contentType)`。
- 被 Task 7、Task 8 消费。

- [ ] **Step 1: 写 `CoverFormatTest`（红）**

```java
package io.github.wanhkjd.cloudnovel.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CoverFormatTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A, 0, 0, 0, 0
    };
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @Test
    void detectsRealImageMagicBytes() {
        assertThat(CoverFormat.detect(JPEG)).contains(CoverFormat.JPEG);
        assertThat(CoverFormat.detect(PNG)).contains(CoverFormat.PNG);
        assertThat(CoverFormat.detect(WEBP)).contains(CoverFormat.WEBP);
    }

    @Test
    void rejectsDisallowedOrMalformedContent() {
        assertThat(CoverFormat.detect("GIF89a-----".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(CoverFormat.detect("<svg xmlns=".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(CoverFormat.detect("<!DOCTYPE h".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(CoverFormat.detect("RIFF____XXXX".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(CoverFormat.detect(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty(); // 太短
        assertThat(CoverFormat.detect(new byte[0])).isEmpty();
        assertThat(CoverFormat.detect(null)).isEmpty();
    }

    @Test
    void exposesExtensionAndContentType() {
        assertThat(CoverFormat.JPEG.extension()).isEqualTo("jpg");
        assertThat(CoverFormat.JPEG.contentType()).isEqualTo("image/jpeg");
        assertThat(CoverFormat.PNG.extension()).isEqualTo("png");
        assertThat(CoverFormat.PNG.contentType()).isEqualTo("image/png");
        assertThat(CoverFormat.WEBP.extension()).isEqualTo("webp");
        assertThat(CoverFormat.WEBP.contentType()).isEqualTo("image/webp");
    }

    @Test
    void resolvesFormatFromStoredExtension() {
        assertThat(CoverFormat.fromExtension("jpg")).contains(CoverFormat.JPEG);
        assertThat(CoverFormat.fromExtension("png")).contains(CoverFormat.PNG);
        assertThat(CoverFormat.fromExtension("webp")).contains(CoverFormat.WEBP);
        assertThat(CoverFormat.fromExtension("gif")).isEmpty();
        assertThat(CoverFormat.fromExtension(null)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -q -pl backend test -Dtest=CoverFormatTest`
Expected: FAIL——`CoverFormat` / `StoredImage` 尚不存在（编译错误）。

- [ ] **Step 3: 写 `StoredImage`**

```java
package io.github.wanhkjd.cloudnovel.core.storage;

/**
 * 从存储读回的封面图片：原始字节与其真实内容类型（由 {@link CoverFormat} 决定）。
 *
 * @param bytes 图片字节
 * @param contentType HTTP {@code Content-Type}，如 {@code image/png}
 */
public record StoredImage(byte[] bytes, String contentType) {}
```

- [ ] **Step 4: 写 `CoverFormat`（绿）**

```java
package io.github.wanhkjd.cloudnovel.core.storage;

import java.util.Optional;

/**
 * 允许的封面图片格式。真实类型只由字节魔数判定，绝不信任客户端声明的 Content-Type，
 * 以拒绝伪装成图片的 SVG/HTML 等可执行内容（安全要求）。
 */
public enum CoverFormat {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    private final String extension;
    private final String contentType;

    CoverFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /**
     * @return 存储用的小写扩展名（不含点），如 {@code jpg}
     */
    public String extension() {
        return extension;
    }

    /**
     * @return 下发时的 HTTP 内容类型，如 {@code image/jpeg}
     */
    public String contentType() {
        return contentType;
    }

    /**
     * 按前 12 字节魔数判定真实图片格式；无法判定或被拒类型返回空。
     *
     * @param bytes 待判定的原始字节，可为 null
     * @return 命中的格式，或 {@link Optional#empty()}
     */
    public static Optional<CoverFormat> detect(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Optional.empty();
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if ((bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G'
                && (bytes[4] & 0xFF) == 0x0D
                && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A
                && (bytes[7] & 0xFF) == 0x0A) {
            return Optional.of(PNG);
        }
        if (bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /**
     * 按存储扩展名反查格式，用于读回时决定内容类型。
     *
     * @param extension 小写扩展名（不含点），可为 null
     * @return 命中的格式，或 {@link Optional#empty()}
     */
    public static Optional<CoverFormat> fromExtension(String extension) {
        for (CoverFormat format : values()) {
            if (format.extension.equals(extension)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 5: 跑测试确认绿**

Run: `mvn -q -pl backend test -Dtest=CoverFormatTest`
Expected: PASS——魔数命中 jpeg/png/webp，拒 gif/svg/html/伪 RIFF/过短/空/null。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/github/wanhkjd/cloudnovel/core/storage/CoverFormat.java backend/src/main/java/io/github/wanhkjd/cloudnovel/core/storage/StoredImage.java backend/src/test/java/io/github/wanhkjd/cloudnovel/core/storage/CoverFormatTest.java
git commit -m "feat: sniff cover image format from magic bytes

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 7: `CoverImageStorage` 接口 + `LocalCoverImageStorage` 覆盖式落盘

封面存 `${app.storage-directory}/covers/{id}.{ext}`，与 TXT 原件（存储根）并列互不干扰（spec C1）。写为**覆盖式**（换格式时先删旧扩展名文件，避免 `{id}.jpg` 与 `{id}.png` 并存）；复用 `LocalNovelFileStorage` 的 UUID/穿越/符号链接防护。

**Files:**
- Create: `backend/src/main/java/io/github/wanhkjd/cloudnovel/core/storage/CoverImageStorage.java`
- Create: `backend/src/main/java/io/github/wanhkjd/cloudnovel/core/storage/LocalCoverImageStorage.java`
- Test: `backend/src/test/java/io/github/wanhkjd/cloudnovel/core/storage/CoverImageStorageTest.java`

**Interfaces:**
- Consumes: `CoverFormat`、`StoredImage`（Task 6）。
- Produces: `interface CoverImageStorage { void write(String id, CoverFormat format, byte[] bytes) throws IOException; Optional<StoredImage> read(String id) throws IOException; void delete(String id) throws IOException; }`；`@Component LocalCoverImageStorage` 实现之。被 Task 8 注入。

- [ ] **Step 1: 写接口 `CoverImageStorage`**

```java
package io.github.wanhkjd.cloudnovel.core.storage;

import java.io.IOException;
import java.util.Optional;

/** 封面图片的持久化：与原始 TXT 存储分离，落在存储根下的 covers/ 子目录。 */
public interface CoverImageStorage {
    /**
     * 覆盖式写入封面；写入前会清除该 id 其它扩展名的旧封面，保证每本书至多一个封面文件。
     *
     * @param id 书籍 UUID
     * @param format 由魔数判定的真实格式，决定落盘扩展名
     * @param bytes 图片字节
     * @throws IOException 写入失败
     */
    void write(String id, CoverFormat format, byte[] bytes) throws IOException;

    /**
     * 读回封面；不存在时返回空而非抛异常，便于上层回退到 404。
     *
     * @param id 书籍 UUID
     * @return 图片字节与内容类型，或 {@link Optional#empty()}
     * @throws IOException 读取失败（非“不存在”）
     */
    Optional<StoredImage> read(String id) throws IOException;

    /**
     * 幂等删除该 id 的全部封面文件；不存在视为成功。
     *
     * @param id 书籍 UUID
     * @throws IOException 删除失败
     */
    void delete(String id) throws IOException;
}
```

- [ ] **Step 2: 写 `CoverImageStorageTest`（红）**

```java
package io.github.wanhkjd.cloudnovel.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CoverImageStorageTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 4, 5, 6};

    @TempDir Path directory;

    @Test
    void writeThenReadRoundTripsBytesAndContentType() throws IOException {
        CoverImageStorage storage = new LocalCoverImageStorage(directory.toString());
        storage.write(ID, CoverFormat.JPEG, JPEG_BYTES);
        StoredImage image = storage.read(ID).orElseThrow();
        assertThat(image.bytes()).isEqualTo(JPEG_BYTES);
        assertThat(image.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void writeOverwritesAcrossFormatsLeavingSingleFile() throws IOException {
        CoverImageStorage storage = new LocalCoverImageStorage(directory.toString());
        storage.write(ID, CoverFormat.JPEG, JPEG_BYTES);
        storage.write(ID, CoverFormat.PNG, PNG_BYTES);
        assertThat(storage.read(ID).orElseThrow().contentType()).isEqualTo("image/png");
        try (Stream<Path> files = Files.list(directory.resolve("covers"))) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith(ID))).hasSize(1);
        }
    }

    @Test
    void readReturnsEmptyWhenNoCoverAndDeleteIsIdempotent() throws IOException {
        CoverImageStorage storage = new LocalCoverImageStorage(directory.toString());
        assertThat(storage.read(ID)).isEmpty();
        storage.delete(ID); // 目录尚不存在也不抛
        storage.write(ID, CoverFormat.PNG, PNG_BYTES);
        storage.delete(ID);
        storage.delete(ID); // 幂等
        assertThat(storage.read(ID)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "",
                "../private",
                "..\\private",
                "C:\\private",
                "1-1-1-1-1",
                "123E4567-E89B-12D3-A456-426614174000"
            })
    void rejectsNonCanonicalUuid(String id) {
        CoverImageStorage storage = new LocalCoverImageStorage(directory.toString());
        assertThatThrownBy(() -> storage.write(id, CoverFormat.PNG, PNG_BYTES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read(id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(id)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: 跑测试确认红**

Run: `mvn -q -pl backend test -Dtest=CoverImageStorageTest`
Expected: FAIL——`LocalCoverImageStorage` 尚不存在（编译错误）。

- [ ] **Step 4: 写 `LocalCoverImageStorage`（绿）**

```java
package io.github.wanhkjd.cloudnovel.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于本地磁盘的封面存储：文件落在 {@code ${app.storage-directory}/covers/{id}.{ext}}，
 * 与原始 TXT（存储根）分离。写入为覆盖式（先删同 id 其它扩展名），
 * 并以规范 UUID 校验阻止路径穿越与符号链接逃逸。
 */
@Component
public class LocalCoverImageStorage implements CoverImageStorage {
    private final Path directory;

    /**
     * @param storageDirectory 存储根目录；封面写入其下的 covers/ 子目录
     */
    public LocalCoverImageStorage(@Value("${app.storage-directory}") String storageDirectory) {
        this.directory = Path.of(storageDirectory).toAbsolutePath().normalize().resolve("covers");
    }

    @Override
    public void write(String id, CoverFormat format, byte[] bytes) throws IOException {
        Path target = resolve(id, format.extension());
        Files.createDirectories(directory);
        deleteAllFormats(id); // 覆盖式：换格式时不残留旧扩展名文件
        Files.write(target, bytes);
    }

    @Override
    public Optional<StoredImage> read(String id) throws IOException {
        for (CoverFormat format : CoverFormat.values()) {
            Path candidate = resolve(id, format.extension());
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(
                        new StoredImage(Files.readAllBytes(candidate), format.contentType()));
            }
        }
        return Optional.empty();
    }

    @Override
    public void delete(String id) throws IOException {
        deleteAllFormats(id);
    }

    private void deleteAllFormats(String id) throws IOException {
        for (CoverFormat format : CoverFormat.values()) {
            Files.deleteIfExists(resolve(id, format.extension()));
        }
    }

    private Path resolve(String id, String extension) {
        if (id == null || !isCanonicalUuid(id)) {
            throw new IllegalArgumentException("非法的书籍标识：" + id);
        }
        Path candidate = directory.resolve(id + "." + extension).normalize();
        if (!directory.equals(candidate.getParent())) {
            throw new IllegalArgumentException("非法的书籍标识：" + id);
        }
        return candidate;
    }

    private static boolean isCanonicalUuid(String id) {
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
```

- [ ] **Step 5: 跑测试确认绿**

Run: `mvn -q -pl backend test -Dtest=CoverImageStorageTest`
Expected: PASS——往返一致、覆盖式仅留单文件、缺失返回空、删除幂等、非规范 UUID 抛 `IllegalArgumentException`。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/io/github/wanhkjd/cloudnovel/core/storage/CoverImageStorage.java backend/src/main/java/io/github/wanhkjd/cloudnovel/core/storage/LocalCoverImageStorage.java backend/src/test/java/io/github/wanhkjd/cloudnovel/core/storage/CoverImageStorageTest.java
git commit -m "feat: store cover images on disk with overwrite semantics

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 8: `LibraryService` 封面方法（`setCover`/`removeCover`/`readCover`）+ 构造器扩展

把封面存储接进业务层。`CoverImageStorage` 成为 `LibraryServiceImpl` 第 7 个构造参数（放在 `NovelFileStorage` 之后），因此 `LibraryServiceTest` 与 `LibraryTransactionIT` 两处 `new LibraryServiceImpl(...)` 都要补第 5 个实参。封面读取对“缺失/未公开/无封面”一律返回空，让控制器永不 500（spec C5、Review #3）。

**Files:**
- Modify: `backend/src/main/java/io/github/wanhkjd/cloudnovel/service/LibraryService.java`
- Modify: `backend/src/main/java/io/github/wanhkjd/cloudnovel/service/impl/LibraryServiceImpl.java`
- Test: `backend/src/test/java/io/github/wanhkjd/cloudnovel/service/impl/LibraryServiceTest.java`
- Modify: `backend/src/test/java/io/github/wanhkjd/cloudnovel/service/impl/LibraryTransactionIT.java`

**Interfaces:**
- Consumes: `CoverImageStorage`/`CoverFormat`/`StoredImage`（Task 6/7）、`BookMapper.updateCover`（Task 5）、`BookView.hasCover`（Task 4）、Task 4 后的 `bookWith(boolean catalogue, boolean text, LocalDate timelineDate, String coverPath)` 夹具。
- Produces：
  - `BookView setCover(String id, byte[] bytes) throws IOException`
  - `void removeCover(String id) throws IOException`
  - `Optional<StoredImage> readCover(String id, boolean owner) throws IOException`
  - 构造器变为 `(BookMapper, ChapterMapper, TxtNovelParser, NovelFileStorage, CoverImageStorage, TransactionTemplate, Clock)`。被 Task 9–11 消费。

- [ ] **Step 1: 改 `LibraryServiceTest`——加 `coverStorage` mock、第 5 个构造实参、封面用例（红）**

在字段区（`storage` 之后）加：

```java
    CoverImageStorage coverStorage = mock(CoverImageStorage.class);
```

追加静态导入：

```java
import io.github.wanhkjd.cloudnovel.core.storage.CoverFormat;
import io.github.wanhkjd.cloudnovel.core.storage.CoverImageStorage;
import io.github.wanhkjd.cloudnovel.core.storage.StoredImage;
```

把 `setUp()` 的构造调用改为 7 参（`coverStorage` 位于 `storage` 与 `TransactionTemplate` 之间）：

```java
        service =
                new LibraryServiceImpl(
                        books,
                        chapters,
                        new TxtNovelParser(),
                        storage,
                        coverStorage,
                        new TransactionTemplate(manager),
                        Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC));
```

在 `deleteCleansFileOnlyAfterDatabaseCommit` 里，让删除同时清理封面：在 `ordered.verify(storage).delete(id);` 之后加 `verify(coverStorage).delete(id);`，把 `reset(storage);` 改为 `reset(storage, coverStorage);`，把末尾 `verifyNoInteractions(storage);` 改为 `verifyNoInteractions(storage, coverStorage);`。

在 `book(...)` 夹具前加以下封面用例：

```java
    @Test
    void setCoverDetectsFormatWritesFileAndExposesHasCoverWithoutPath() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A, 0, 0, 0, 0};
        when(books.findById(id))
                .thenReturn(
                        Optional.of(bookWith(true, false, null, null)),
                        Optional.of(bookWith(true, false, null, "covers/" + id + ".png")));
        BookView view = service.setCover(id, png);
        verify(coverStorage).write(id, CoverFormat.PNG, png);
        verify(books).updateCover(id, "covers/" + id + ".png");
        assertThat(view.hasCover()).isTrue();
    }

    @Test
    void setCoverRejectsDisallowedTypeBeforeTouchingStorage() {
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, false, null, null)));
        assertThatThrownBy(() -> service.setCover(id, "<svg xmlns=".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(coverStorage);
        verify(books, never()).updateCover(any(), any());
    }

    @Test
    void setCoverRejectsCoverLargerThanTwoMebibytes() throws Exception {
        byte[] oversize = new byte[2 * 1024 * 1024 + 1];
        byte[] pngMagic = {(byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A};
        System.arraycopy(pngMagic, 0, oversize, 0, pngMagic.length);
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, false, null, null)));
        assertThatThrownBy(() -> service.setCover(id, oversize))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(coverStorage);
        verify(books, never()).updateCover(any(), any());
    }

    @Test
    void removeCoverClearsPathAndDeletesFileIdempotently() throws Exception {
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, false, null, "covers/" + id + ".jpg")));
        service.removeCover(id);
        service.removeCover(id);
        verify(coverStorage, times(2)).delete(id);
        verify(books, times(2)).updateCover(id, null);
    }

```java
    @Test
    void readCoverHidesMissingUnpublishedOrCoverlessBooks() throws Exception {
        when(books.findById(id)).thenReturn(Optional.empty());
        assertThat(service.readCover(id, false)).isEmpty();
        when(books.findById(id)).thenReturn(Optional.of(bookWith(false, false, null, "covers/" + id + ".png")));
        assertThat(service.readCover(id, false)).isEmpty(); // 未公开 + 访客
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, false, null, null)));
        assertThat(service.readCover(id, true)).isEmpty(); // 无封面
        verify(coverStorage, never()).read(any());
    }

    @Test
    void readCoverStreamsForOwnerOrPublishedBookWithCover() throws Exception {
        StoredImage stored = new StoredImage(new byte[] {1, 2, 3}, "image/jpeg");
        when(coverStorage.read(id)).thenReturn(Optional.of(stored));
        when(books.findById(id)).thenReturn(Optional.of(bookWith(true, false, null, "covers/" + id + ".jpg")));
        assertThat(service.readCover(id, false).orElseThrow().contentType()).isEqualTo("image/jpeg");
        when(books.findById(id)).thenReturn(Optional.of(bookWith(false, false, null, "covers/" + id + ".jpg")));
        assertThat(service.readCover(id, true)).contains(stored); // 主人可见未公开书封面
    }
```

- [ ] **Step 2: 跑 `LibraryServiceTest` 确认红**

Run: `mvn -q -pl backend test -Dtest=LibraryServiceTest`
Expected: FAIL——编译错误：构造器只接受 6 参，`setCover`/`removeCover`/`readCover` 未定义。

- [ ] **Step 3: 在 `LibraryService` 接口加三方法**

在 `download` 之前加：

```java
    /**
     * 上传封面：按字节魔数判定真实类型（拒非 JPEG/PNG/WebP），限 2 MiB，覆盖式落盘并记键。
     *
     * @param id 书籍 UUID
     * @param bytes 上传的图片字节
     * @return 更新后的书籍（{@code hasCover} 为真）
     * @throws IOException 封面写入失败
     * @throws IllegalArgumentException 类型不被允许或超过 2 MiB
     */
    BookView setCover(String id, byte[] bytes) throws IOException;

    /**
     * 移除封面：删除封面文件并清空封面键，重复调用安全。
     *
     * @param id 书籍 UUID
     * @throws IOException 封面删除失败
     */
    void removeCover(String id) throws IOException;

    /**
     * 读取封面字节；书籍缺失、对当前身份不可见或无封面时返回空，便于控制器回退 404。
     *
     * @param id 书籍 UUID
     * @param owner 是否为管理员
     * @return 封面字节与内容类型，或 {@link Optional#empty()}
     * @throws IOException 封面读取发生 I/O 故障
     */
    Optional<StoredImage> readCover(String id, boolean owner) throws IOException;
```

`LibraryService` 追加导入：`io.github.wanhkjd.cloudnovel.core.storage.StoredImage`、`java.util.Optional`。

- [ ] **Step 4: 扩展 `LibraryServiceImpl`——注入 `CoverImageStorage`、加三方法、删除时清理封面**

追加导入：

```java
import io.github.wanhkjd.cloudnovel.core.storage.CoverFormat;
import io.github.wanhkjd.cloudnovel.core.storage.CoverImageStorage;
import io.github.wanhkjd.cloudnovel.core.storage.StoredImage;
import java.util.Optional;
```

在 `CHAPTER_BATCH_SIZE` 常量后加封面上限：

```java
    /** 封面体积上限：2 MiB，独立于 TXT 的 multipart 上限。 */
    private static final long MAX_COVER_BYTES = 2L * 1024 * 1024;
```

在 `storage` 字段后加字段，并在构造器补参、赋值、Javadoc：

```java
    /** 封面图片存储，与原件存储分离。 */
    private final CoverImageStorage coverStorage;
```

构造器签名在 `NovelFileStorage storage,` 后插入 `CoverImageStorage coverStorage,`，方法体加 `this.coverStorage = coverStorage;`，Javadoc 加 `@param coverStorage 封面图片存储`。

在 `download` 方法之后（`requireBook` 之前）加三个实现：

```java
    @Override
    public BookView setCover(String id, byte[] bytes) throws IOException {
        requireBook(id); // 未知书籍先 404，避免写出孤儿封面文件
        CoverFormat format =
                CoverFormat.detect(bytes)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "封面必须是 JPEG、PNG 或 WebP 图片。"));
        if (bytes.length > MAX_COVER_BYTES) {
            throw new IllegalArgumentException("封面大小不能超过 2 MiB。");
        }
        coverStorage.write(id, format, bytes);
        bookMapper.updateCover(id, "covers/" + id + "." + format.extension());
        return toView(requireBook(id), true, true);
    }

    @Override
    public void removeCover(String id) throws IOException {
        requireBook(id);
        coverStorage.delete(id);
        bookMapper.updateCover(id, null);
    }

    @Override
    public Optional<StoredImage> readCover(String id, boolean owner) throws IOException {
        Optional<BookEntity> found = bookMapper.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BookEntity book = found.get();
        if ((!owner && !book.catalogPublished()) || book.coverPath() == null) {
            return Optional.empty();
        }
        return coverStorage.read(id);
    }
```

在 `deleteBook` 的原件清理 `catch` 之后，追加封面清理（提交成功后再清盘，与原件一致）：

```java
        try {
            coverStorage.delete(id);
        } catch (IOException error) {
            LOG.warn("Cover cleanup failed for book {}", id, error);
        }
```

- [ ] **Step 5: 修 `LibraryTransactionIT` 构造器（第 5 个实参）**

追加导入 `io.github.wanhkjd.cloudnovel.core.storage.LocalCoverImageStorage`，把 `new LibraryServiceImpl(...)` 的 `new LocalNovelFileStorage(directory.toString()),` 之后插入一行：

```java
                        new LocalCoverImageStorage(directory.toString()),
```

（该测试导入失败即回滚，从不写封面，`covers/` 子目录不会创建，末尾 `Files.list(directory)` 仍为空——断言不变。）

- [ ] **Step 6: `spotless:apply` 后跑 `LibraryServiceTest` 确认绿**

Run: `mvn -q -pl backend spotless:apply && mvn -q -pl backend test -Dtest=LibraryServiceTest`
Expected: PASS——封面格式嗅探、2 MiB 上限、`hasCover` 不泄露 `coverPath`、可见性规则、删除清理封面全部通过。

- [ ] **Step 7: 跑 `LibraryTransactionIT` 确认绿（需已迁移的 `cloud_novel_test`）**

Run: `mvn -q -pl backend verify -Dit.test=LibraryTransactionIT -DfailIfNoTests=false`
Expected: PASS——回滚后临时目录仍为空，封面存储未引入残留。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/io/github/wanhkjd/cloudnovel/service/LibraryService.java backend/src/main/java/io/github/wanhkjd/cloudnovel/service/impl/LibraryServiceImpl.java backend/src/test/java/io/github/wanhkjd/cloudnovel/service/impl/LibraryServiceTest.java backend/src/test/java/io/github/wanhkjd/cloudnovel/service/impl/LibraryTransactionIT.java
git commit -m "feat: manage book covers through the library service

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 9: `POST /api/books/{id}/cover` 上传封面

**Files:**
- Modify: `backend/src/main/java/io/github/wanhkjd/cloudnovel/controller/LibraryController.java`
- Test: `backend/src/test/java/io/github/wanhkjd/cloudnovel/controller/LibraryApiIT.java`

**Interfaces:**
- Consumes: `LibraryService.setCover`（Task 8）；沿用 `@RequestParam("file") MultipartFile`、`CurrentUser`。
- Produces: `POST /api/books/{id}/cover` → `200` + 更新后的 `BookView`（`hasCover=true`，无 `coverPath`）。管理员+CSRF 由安全层保证（非 GET `/api/**`→`hasRole(ADMIN)`）。封面 IT 复用的 `png()` 夹具在本任务引入。

- [ ] **Step 1: 在 `LibraryApiIT` 加 `png()` 夹具与上传用例（红）**

在 `upload()` 之前加夹具，并在类末尾（`unreadBookProgressRemainsAnEmptySuccessfulResponse` 之后）加用例：

```java
    byte[] png() {
        return new byte[] {
            (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A, 0, 0, 0, 0
        };
    }

    @Test
    void ownerUploadsCoverThenBookReportsHasCoverWithoutExposingPath() throws Exception {
        String id = upload();
        publish(id, false);
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCover").value(true))
                .andExpect(jsonPath("$.coverPath").doesNotExist());
        mvc.perform(get("/api/books/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCover").value(true))
                .andExpect(jsonPath("$.coverPath").doesNotExist());
    }

    @Test
    void coverUploadRejectsNonImageOversizeAndUnauthorizedCallers() throws Exception {
        String id = upload();
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(user("visitor").roles("USER"))
                                .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "x.png",
                                                "image/png",
                                                "<svg xmlns=\"a\"></svg>"
                                                        .getBytes(StandardCharsets.UTF_8)))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
        byte[] oversize = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(png(), 0, oversize, 0, 8);
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "big.png", "image/png", oversize))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: 跑 IT 确认红（需已迁移的 `cloud_novel_test`）**

Run: `mvn -q -pl backend verify -Dit.test=LibraryApiIT#ownerUploadsCoverThenBookReportsHasCoverWithoutExposingPath -DfailIfNoTests=false`
Expected: FAIL——尚无 `POST /{id}/cover` 处理器，返回 `404` 而非 `200`。

- [ ] **Step 3: 在 `LibraryController` 加处理器（绿）**

在 `upload` 方法之后插入：

```java
    /**
     * 为指定书籍上传或替换封面；服务层按魔数校验真实类型并限制体积。
     *
     * @param id 目标书籍标识
     * @param file 上传的图片（jpeg/png/webp，≤2 MiB）
     * @return 更新后的书籍视图（{@code hasCover=true}，不含存储路径）
     * @throws IOException 写入封面文件失败时抛出
     */
    @PostMapping("/{id}/cover")
    public BookView uploadCover(@PathVariable String id, @RequestParam("file") MultipartFile file)
            throws IOException {
        return libraryService.setCover(id, file.getBytes());
    }
```

- [ ] **Step 4: 跑 IT 确认绿**

Run: `mvn -q -pl backend verify -Dit.test=LibraryApiIT#ownerUploadsCoverThenBookReportsHasCoverWithoutExposingPath+coverUploadRejectsNonImageOversizeAndUnauthorizedCallers -DfailIfNoTests=false`
Expected: PASS——上传返回 `200`、`hasCover=true`、无 `coverPath`；伪装/超限均 `400`；未授权 `401/403`。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/github/wanhkjd/cloudnovel/controller/LibraryController.java backend/src/test/java/io/github/wanhkjd/cloudnovel/controller/LibraryApiIT.java
git commit -m "feat: accept book cover uploads over the library API

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 10: `DELETE /api/books/{id}/cover` 移除封面

**Files:**
- Modify: `backend/src/main/java/io/github/wanhkjd/cloudnovel/controller/LibraryController.java`
- Test: `backend/src/test/java/io/github/wanhkjd/cloudnovel/controller/LibraryApiIT.java`

**Interfaces:**
- Consumes: `LibraryService.removeCover`（Task 8，幂等）；Task 9 的 `png()` 夹具。
- Produces: `DELETE /api/books/{id}/cover` → `204`（无论此前有无封面）；删除后 `hasCover=false`，`GET .../cover`→`404`。管理员+CSRF 由安全层保证。

- [ ] **Step 1: 加删除用例（红）**

在类末尾追加：

```java
    @Test
    void ownerRemovesCoverAndSubsequentReadsFallBackIdempotently() throws Exception {
        String id = upload();
        publish(id, false);
        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(
                        delete("/api/books/" + id + "/cover")
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/books/" + id)).andExpect(jsonPath("$.hasCover").value(false));
        mvc.perform(get("/api/books/" + id + "/cover")).andExpect(status().isNotFound());
        mvc.perform(
                        delete("/api/books/" + id + "/cover")
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isNoContent());
    }
```

- [ ] **Step 2: 跑 IT 确认红**

Run: `mvn -q -pl backend verify -Dit.test=LibraryApiIT#ownerRemovesCoverAndSubsequentReadsFallBackIdempotently -DfailIfNoTests=false`
Expected: FAIL——尚无 `DELETE /{id}/cover` 处理器（`405`/`404`），且此时 `GET .../cover` 亦未实现。

- [ ] **Step 3: 加处理器（绿）**

在 `uploadCover` 之后插入：

```java
    /**
     * 移除指定书籍的封面；无封面时同样返回成功（幂等）。
     *
     * @param id 目标书籍标识
     * @throws IOException 删除封面文件失败时抛出
     */
    @DeleteMapping("/{id}/cover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCover(@PathVariable String id) throws IOException {
        libraryService.removeCover(id);
    }
```

- [ ] **Step 4: 跑 IT 确认绿（依赖 Task 11 的 GET 处理器）**

> 注：本用例末尾断言 `GET .../cover`→`404`，与 Task 11 同批变绿；先实现 Task 11 的 GET 再统一验证，或本步仅验证 `204`+`hasCover=false` 两条。

Run: `mvn -q -pl backend verify -Dit.test=LibraryApiIT#ownerRemovesCoverAndSubsequentReadsFallBackIdempotently -DfailIfNoTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/github/wanhkjd/cloudnovel/controller/LibraryController.java backend/src/test/java/io/github/wanhkjd/cloudnovel/controller/LibraryApiIT.java
git commit -m "feat: remove book covers over the library API

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 11: `GET /api/books/{id}/cover` 流式返回封面（Review Focus #3）

**Files:**
- Modify: `backend/src/main/java/io/github/wanhkjd/cloudnovel/controller/LibraryController.java`
- Test: `backend/src/test/java/io/github/wanhkjd/cloudnovel/controller/LibraryApiIT.java`

**Interfaces:**
- Consumes: `LibraryService.readCover(id, owner)`（Task 8，缺失/不可见/无封面均返回 `Optional.empty()`，从不抛业务异常）；`CurrentUser.isOwner(Authentication)`；`StoredImage.bytes()/contentType()`。
- Produces: `GET /api/books/{id}/cover`（公开，可见性同 `getBook`）——命中→`200` + `Content-Type`（按扩展名）+ `X-Content-Type-Options: nosniff` + `Cache-Control: public, max-age=300`；未命中（未知 UUID、非 UUID 路径变量、无封面、未公开且访客）→`404` + `nosniff`，**绝不 500**。

- [ ] **Step 1: 加覆盖全部不可见分支的用例（红）**

在类末尾追加（穷举 Review Focus #3：未知 UUID / 非 UUID / 无封面 / 未公开+访客 → 均 `404`+`nosniff`；主人可见 / 公开后访客 → `200`）：

```java
    @Test
    void coverGetIsAlways404WithNosniffWhenUnavailableAndStreamsWhenVisible() throws Exception {
        mvc.perform(get("/api/books/" + java.util.UUID.randomUUID() + "/cover"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/books/not-a-uuid/cover"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        String id = upload();
        mvc.perform(get("/api/books/" + id + "/cover").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mvc.perform(
                        multipart("/api/books/" + id + "/cover")
                                .file(new MockMultipartFile("file", "c.png", "image/png", png()))
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/books/" + id + "/cover"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mvc.perform(get("/api/books/" + id + "/cover").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "public, max-age=300"));

        publish(id, false);
        mvc.perform(get("/api/books/" + id + "/cover"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
    }
```

- [ ] **Step 2: 跑 IT 确认红**

Run: `mvn -q -pl backend verify -Dit.test=LibraryApiIT#coverGetIsAlways404WithNosniffWhenUnavailableAndStreamsWhenVisible -DfailIfNoTests=false`
Expected: FAIL——尚无 `GET /{id}/cover` 处理器（`404` 无 `nosniff` 头，或命中分支不存在）。

- [ ] **Step 3: 加处理器（绿）**

在 `deleteCover` 之后插入。`readCover` 对未知/非 UUID/无封面/未公开访客均返回空 → 统一 `404`+`nosniff`，命中则附类型与缓存头：

```java
    /**
     * 流式返回指定书籍的封面，可见性同 {@code getBook}（主人或已公开目录）。
     *
     * <p>未知或非法标识、无封面、以及未公开书籍对访客，均返回 {@code 404} 且带 {@code nosniff}，
     * 从不返回 {@code 500}；命中时按扩展名给出 {@code Content-Type} 并允许短期公共缓存。
     *
     * @param id 目标书籍标识
     * @param authentication 当前认证信息，用于判定是否为主人
     * @return 图片字节（{@code 200}）或空体（{@code 404}）
     * @throws IOException 读取封面文件失败时抛出
     */
    @GetMapping("/{id}/cover")
    public ResponseEntity<byte[]> cover(
            @PathVariable String id, Authentication authentication) throws IOException {
        return libraryService
                .readCover(id, CurrentUser.isOwner(authentication))
                .map(
                        image ->
                                ResponseEntity.ok()
                                        .header(HttpHeaders.CONTENT_TYPE, image.contentType())
                                        .header("X-Content-Type-Options", "nosniff")
                                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                                        .body(image.bytes()))
                .orElseGet(
                        () ->
                                ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .header("X-Content-Type-Options", "nosniff")
                                        .<byte[]>build());
    }
```

> 若 `StoredImage` 与控制器不同包，补 `import io.github.wanhkjd.cloudnovel.service.dto.resp.StoredImage;` 之类的引用；此处无需在方法签名出现 `StoredImage`，`.map` 的 lambda 参数由类型推断得出，无需新增 import。

- [ ] **Step 4: 跑 IT 确认绿（并回收 Task 10 的 GET 断言）**

Run: `mvn -q -pl backend verify -Dit.test=LibraryApiIT#coverGetIsAlways404WithNosniffWhenUnavailableAndStreamsWhenVisible+ownerRemovesCoverAndSubsequentReadsFallBackIdempotently -DfailIfNoTests=false`
Expected: PASS——全部不可见分支 `404`+`nosniff`，可见分支 `200`+正确头，删除后 `GET`→`404`。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/github/wanhkjd/cloudnovel/controller/LibraryController.java backend/src/test/java/io/github/wanhkjd/cloudnovel/controller/LibraryApiIT.java
git commit -m "feat: stream book covers with visibility and nosniff guards

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Phase 3 — 前端封面组件与后台编辑

> 命令：`npm test`（Vitest 全量）、`npx vitest run <file>`（单文件）、`npm run build`（`vue-tsc -b` 类型检查 + 构建）、`npm run format`（Prettier，提交前跑）。工作目录 `frontend/`。

### Task 12: 扩展 `Book` 契约并新增 `CoverImage.vue`

**Files:**
- Modify: `frontend/src/lib/types.ts`
- Modify: `frontend/src/views/AdminView.test.ts:18-32`（既有 `Book` 字面量补两字段，保证套件编译）
- Create: `frontend/src/components/CoverImage.vue`
- Test: `frontend/src/components/CoverImage.test.ts`

**Interfaces:**
- Consumes: 后端 `BookView` 新增的 `hasCover: boolean` 与 `timelineDate`（Phase 1/2）；既有 `components/BookCover.vue`（`props: { title, author, small? }`，装饰性 `aria-hidden` 假封面）。
- Produces: `Book` 类型新增 `timelineDate: string | null`、`hasCover: boolean`；`CoverImage.vue` 组件 `props: { id: string; title: string; author: string; hasCover: boolean; small?: boolean }`——`hasCover=true` 时渲染 `<img class="book-cover-image" src="/api/books/{id}/cover">`，`@error` 或 `hasCover=false` 回退 `BookCover`。供 Task 13、Phase 4 复用。

- [ ] **Step 1: 写 `CoverImage.test.ts`（红）**

```ts
import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import CoverImage from './CoverImage.vue';

it('skips the network and shows the fallback cover when the book has none', () => {
  const page = mount(CoverImage, {
    props: { id: 'b1', title: '空封面之书', author: '作者', hasCover: false },
  });
  expect(page.find('img.book-cover-image').exists()).toBe(false);
  expect(page.get('.book-cover').text()).toContain('空封面之书');
});

it('requests the real cover then falls back when the image fails to load', async () => {
  const page = mount(CoverImage, {
    props: { id: 'b2', title: '有封面之书', author: '作者', hasCover: true },
  });
  const img = page.get('img.book-cover-image');
  expect(img.attributes('src')).toBe('/api/books/b2/cover');
  await img.trigger('error');
  expect(page.find('img.book-cover-image').exists()).toBe(false);
  expect(page.get('.book-cover').text()).toContain('有封面之书');
});
```

- [ ] **Step 2: 跑单测确认红**

Run: `npx vitest run src/components/CoverImage.test.ts`
Expected: FAIL——`CoverImage.vue` 不存在（解析失败）。

- [ ] **Step 3: 扩展 `Book` 类型、修既有字面量、建 `CoverImage.vue`（绿）**

`types.ts` 在 `Book` 接口 `createdAt: number;` 之后加两行：

```ts
  timelineDate: string | null;
  hasCover: boolean;
```

`AdminView.test.ts` 既有 `Book` 字面量（`createdAt: 0,` 之后）补两字段，使套件仍能编译：

```ts
    createdAt: 0,
    timelineDate: null,
    hasCover: false,
    canRead: true,
    preface: '',
```

新建 `frontend/src/components/CoverImage.vue`：

```vue
<script setup lang="ts">
import { ref, watch } from 'vue';
import BookCover from './BookCover.vue';
const props = defineProps<{
  id: string;
  title: string;
  author: string;
  hasCover: boolean;
  small?: boolean;
}>();
// 声明无封面时根本不发请求；真实封面加载失败则回退到装饰性 CSS 假封面。
const failed = ref(false);
watch(
  () => [props.id, props.hasCover],
  () => {
    failed.value = false;
  },
);
</script>
<template>
  <img
    v-if="hasCover && !failed"
    class="book-cover-image"
    :class="{ small }"
    :src="'/api/books/' + id + '/cover'"
    alt=""
    loading="lazy"
    @error="failed = true"
  />
  <BookCover v-else :title="title" :author="author" :small="small" />
</template>
```

- [ ] **Step 4: 跑单测与类型检查确认绿**

Run: `npx vitest run src/components/CoverImage.test.ts && npm run build`
Expected: PASS——两用例通过；`vue-tsc` 无类型错误（`Book` 字面量已补齐）。

- [ ] **Step 5: 格式化并提交**

```bash
npm run format
git add frontend/src/lib/types.ts frontend/src/views/AdminView.test.ts frontend/src/components/CoverImage.vue frontend/src/components/CoverImage.test.ts
git commit -m "feat: render real book covers with a graceful fallback

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 13: `AdminView` 封面与时间轴日期编辑

**Files:**
- Modify: `frontend/src/views/AdminView.vue`
- Test: `frontend/src/views/AdminView.test.ts`

**Interfaces:**
- Consumes: `CoverImage`（Task 12）；`request`/`jsonRequest`（既有）；后端 `POST`/`DELETE /api/books/{id}/cover`（Tasks 9/10）与 `BookEditRequest.timelineDate`（Task 4）。
- Produces: 编辑对话框内新增 `<input type="date" v-model="form.timelineDate">`（保存时空串→`null`）与封面区（`CoverImage` 预览 + 选图上传 + 移除）。`save()` 仍整体 PATCH，`timelineDate` 以 `form.timelineDate || null` 提交。

- [ ] **Step 1: 在 `AdminView.test.ts` 加对话框夹具与两用例（红）**

把首行 vitest 导入改为含 `beforeAll`：

```ts
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
```

在 `mountAdmin()` 之后追加对话框垫片、`makeBook` 工厂与用例：

```ts
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
});

function makeBook(overrides: Partial<Book> = {}): Book {
  return {
    id: 'book-1',
    title: '测试书',
    author: '作者',
    description: '',
    encoding: 'UTF-8',
    chapterCount: 1,
    volumeCount: 1,
    characterCount: 10,
    catalogPublished: false,
    textPublished: false,
    createdAt: 0,
    timelineDate: null,
    hasCover: false,
    canRead: true,
    preface: '',
    ...overrides,
  };
}

it('submits a cleared timeline date to the API as null rather than an empty string', async () => {
  const book = makeBook({ timelineDate: '2019-05-01' });
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(new Response(JSON.stringify([book])))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ token: 't', headerName: 'X-CSRF-TOKEN' })),
    )
    .mockResolvedValueOnce(new Response('', { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([book])));
  vi.stubGlobal('fetch', fetchMock);
  const page = mountAdmin();
  await flushPromises();
  await page.get('[aria-label="编辑测试书"]').trigger('click');
  await flushPromises();
  await page.get('input[type="date"]').setValue('');
  await page.get('.editor-dialog form').trigger('submit');
  await flushPromises();
  const patch = fetchMock.mock.calls.find((call) => call[1]?.method === 'PATCH');
  expect(patch).toBeTruthy();
  const body = JSON.parse(patch![1].body as string);
  expect(body.timelineDate).toBeNull();
  expect(body.title).toBe('测试书');
});

it('uploads a chosen cover image as multipart and refreshes the list', async () => {
  const before = makeBook({ hasCover: false });
  const after = makeBook({ hasCover: true });
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(new Response(JSON.stringify([before])))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ token: 't', headerName: 'X-CSRF-TOKEN' })),
    )
    .mockResolvedValueOnce(new Response(JSON.stringify(after), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([after])));
  vi.stubGlobal('fetch', fetchMock);
  const page = mountAdmin();
  await flushPromises();
  await page.get('[aria-label="编辑测试书"]').trigger('click');
  await flushPromises();
  const cover = page.get('input[aria-label="选择封面图片"]');
  Object.defineProperty(cover.element, 'files', {
    value: [new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'c.png', { type: 'image/png' })],
    configurable: true,
  });
  await cover.trigger('change');
  await flushPromises();
  const post = fetchMock.mock.calls.find((call) => String(call[0]).endsWith('/book-1/cover'));
  expect(post).toBeTruthy();
  expect(post![1].method).toBe('POST');
  expect(post![1].body).toBeInstanceOf(FormData);
});
```

- [ ] **Step 2: 跑单测确认红**

Run: `npx vitest run src/views/AdminView.test.ts`
Expected: FAIL——无 `input[type="date"]` 与 `选择封面图片`，`form.timelineDate` 未转换。

- [ ] **Step 3a: `AdminView.vue` 脚本改动（绿）**

新增组件导入（在 `import type { Book }...` 之前）：

```ts
import CoverImage from '../components/CoverImage.vue';
```

`form` reactive 增字段（在 `textPublished: false,` 之后）：

```ts
  textPublished: false,
  timelineDate: '',
```

新增封面文件输入引用（在 `const dialog = ref<HTMLDialogElement>();` 之后）：

```ts
const coverFile = ref<HTMLInputElement>();
```

`edit()` 内在 `Object.assign(form, book);` 后补一行，把可空日期规整为输入框可用的字符串：

```ts
  Object.assign(form, book);
  form.timelineDate = book.timelineDate ?? '';
```

`save()` 中把 PATCH 载荷替换为带空串→`null` 转换：

```ts
    await jsonRequest('/api/books/' + selected.value.id, 'PATCH', {
      ...form,
      timelineDate: form.timelineDate || null,
    });
```

在 `save()` 与 `remove()` 之间插入封面上传/移除函数：

```ts
async function uploadCover(event: Event) {
  const picked = (event.target as HTMLInputElement).files?.[0];
  if (!picked || !selected.value) return;
  editError.value = '';
  if (!picked.type.startsWith('image/') || picked.size > 2 * 1024 * 1024) {
    editError.value = '请选择不超过 2 MiB 的 JPEG / PNG / WebP 图片作为封面。';
    if (coverFile.value) coverFile.value.value = '';
    return;
  }
  try {
    const data = new FormData();
    data.append('file', picked);
    const updated = await request<Book>('/api/books/' + selected.value.id + '/cover', {
      method: 'POST',
      body: data,
    });
    selected.value = updated;
    Object.assign(form, updated);
    form.timelineDate = updated.timelineDate ?? '';
    message.value = '封面已更新。';
    await load();
  } catch (e) {
    editError.value = errorMessage(e);
  } finally {
    if (coverFile.value) coverFile.value.value = '';
  }
}
async function removeCover() {
  if (!selected.value) return;
  editError.value = '';
  try {
    await jsonRequest('/api/books/' + selected.value.id + '/cover', 'DELETE');
    selected.value = { ...selected.value, hasCover: false };
    message.value = '封面已移除。';
    await load();
  } catch (e) {
    editError.value = errorMessage(e);
  }
}
```

- [ ] **Step 3b: `AdminView.vue` 模板改动（绿）**

在对话框简介 `<label>` 之后、`<fieldset class="publication-fields">` 之前，插入日期输入与封面区：

```html
        ><label>简介<textarea v-model="form.description" rows="4" maxlength="4000" /></label
        ><label
          >时间轴日期<input v-model="form.timelineDate" type="date" /><span class="muted small"
            >留空则按导入时间排列于银河时间轴。</span
          ></label
        >
        <fieldset class="cover-fields">
          <legend>封面</legend>
          <div class="cover-editor">
            <CoverImage
              :id="selected.id"
              :title="form.title"
              :author="form.author"
              :has-cover="selected.hasCover"
              small
            />
            <div class="cover-actions">
              <label class="file-label"
                ><Upload :size="18" /><span>{{
                  selected.hasCover ? '更换封面' : '上传封面'
                }}</span
                ><input
                  ref="coverFile"
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  aria-label="选择封面图片"
                  @change="uploadCover"
              /></label>
              <button
                v-if="selected.hasCover"
                type="button"
                class="icon-button danger-text"
                @click="removeCover"
              >
                移除封面
              </button>
              <p class="muted small">JPEG / PNG / WebP · 最大 2 MiB · 立即生效。</p>
            </div>
          </div>
        </fieldset>
        <fieldset class="publication-fields">
```

- [ ] **Step 4: 跑单测与类型检查确认绿**

Run: `npx vitest run src/views/AdminView.test.ts && npm run build`
Expected: PASS——清空日期提交为 `null`；选图触发 `POST .../cover`（FormData）；类型检查通过。

- [ ] **Step 5: 格式化并提交**

```bash
npm run format
git add frontend/src/views/AdminView.vue frontend/src/views/AdminView.test.ts
git commit -m "feat: edit book covers and timeline dates from the admin room

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

## Phase 4 — 银河时间轴首页

> 深色星空仅作用于首页作用域（`body.galaxy-route` / `.galaxy-view`），不改全局暖纸 tokens。全部动效在 `@media (prefers-reduced-motion: reduce)` 下降级为静态。命令同 Phase 3。

### Task 14: 时间轴排序与日期标签工具

**Files:**
- Create: `frontend/src/lib/timeline.ts`
- Modify: `frontend/src/lib/format.ts`（追加 `dayLabel`）
- Test: `frontend/src/lib/timeline.test.ts`

**Interfaces:**
- Consumes: `Book`（`timelineDate: string | null`、`createdAt: number`、`id: string`）。
- Produces: `effectiveTime(book): number`（优先自定义日期，回退 `createdAt`，非法日期亦回退）；`orderByTimeline<T>(books): T[]`（升序、`createdAt`→`id` 决胜的稳定序）；`dayLabel(ms: number): string`（`zh-CN` 年月日、Asia/Shanghai）。供 Task 15/18 使用。

- [ ] **Step 1: 写 `timeline.test.ts`（红）**

```ts
import { expect, it } from 'vitest';
import { effectiveTime, orderByTimeline } from './timeline';

const book = (id: string, createdAt: number, timelineDate: string | null = null) => ({
  id,
  createdAt,
  timelineDate,
});

it('prefers the custom timeline date and falls back to createdAt', () => {
  expect(effectiveTime(book('a', 1000, '2020-01-01'))).toBe(Date.parse('2020-01-01'));
  expect(effectiveTime(book('b', 1000, null))).toBe(1000);
  expect(effectiveTime(book('c', 2000, 'not-a-date'))).toBe(2000);
});

it('orders ascending with deterministic tie-breaking', () => {
  const later = book('later', 50, '2021-06-01');
  const earlier = book('earlier', 40, '2019-06-01');
  const tieB = book('b', 30);
  const tieA = book('a', 30);
  expect(orderByTimeline([later, tieB, earlier, tieA]).map((x) => x.id)).toEqual([
    'a',
    'b',
    'earlier',
    'later',
  ]);
});
```

- [ ] **Step 2: 跑单测确认红**

Run: `npx vitest run src/lib/timeline.test.ts`
Expected: FAIL——`timeline.ts` 不存在。

- [ ] **Step 3: 实现 `timeline.ts` 与 `format.dayLabel`（绿）**

新建 `frontend/src/lib/timeline.ts`：

```ts
import type { Book } from './types';

type Dated = Pick<Book, 'timelineDate' | 'createdAt'>;

/** 时间轴有效时刻：优先自定义日期（ISO yyyy-MM-dd），否则/非法则回退导入时间。 */
export function effectiveTime(book: Dated): number {
  const parsed = book.timelineDate ? Date.parse(book.timelineDate) : Number.NaN;
  return Number.isNaN(parsed) ? book.createdAt : parsed;
}

/** 升序（旧→新）稳定排序；同刻按 createdAt、再按 id 决胜，保证渲染确定。 */
export function orderByTimeline<T extends Dated & Pick<Book, 'id'>>(books: readonly T[]): T[] {
  return [...books].sort((a, b) => {
    const byTime = effectiveTime(a) - effectiveTime(b);
    if (byTime !== 0) return byTime;
    const byCreated = a.createdAt - b.createdAt;
    return byCreated !== 0 ? byCreated : a.id.localeCompare(b.id);
  });
}
```

在 `format.ts` 末尾追加：

```ts
export const dayLabel = (ms: number) =>
  new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    timeZone: 'Asia/Shanghai',
  }).format(ms);
```

- [ ] **Step 4: 跑单测确认绿**

Run: `npx vitest run src/lib/timeline.test.ts && npm run build`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
npm run format
git add frontend/src/lib/timeline.ts frontend/src/lib/timeline.test.ts frontend/src/lib/format.ts
git commit -m "feat: order books along a timeline with a stable comparator

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 15: `TimelineBook.vue`——藤蔓上的单个书卡节点

**Files:**
- Create: `frontend/src/components/TimelineBook.vue`
- Test: `frontend/src/components/TimelineBook.test.ts`

**Interfaces:**
- Consumes: `Book`；`effectiveTime`（Task 14）；`dayLabel`/`number`/`words`（`format.ts`）；`CoverImage`（Task 12）。
- Produces: 组件 `TimelineBook`，props `{ book: Book; side: 'left' | 'right'; revealed: boolean }`；渲染 `<li class="timeline-book">`，含日期 `<time>`、指向 `/books/:id` 的 `RouterLink`、封面与书目元信息。供 Task 18 沿藤蔓左右交替渲染。

- [ ] **Step 1: 写 `TimelineBook.test.ts`（红）**

```ts
import { RouterLinkStub, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import TimelineBook from './TimelineBook.vue';
import CoverImage from './CoverImage.vue';
import type { Book } from '../lib/types';

const book: Book = {
  id: 'b7', title: '夜航', author: '林澈', description: '一段向上的旅程',
  encoding: 'UTF-8', chapterCount: 12, volumeCount: 1, characterCount: 42000,
  catalogPublished: true, textPublished: true, createdAt: 1700000000000,
  canRead: true, preface: null, timelineDate: '2020-03-01', hasCover: true,
};

const mountNode = (over: Partial<{ side: 'left' | 'right'; revealed: boolean }> = {}) =>
  mount(TimelineBook, {
    props: { book, side: 'right', revealed: false, ...over },
    global: { stubs: { RouterLink: RouterLinkStub } },
  });

it('links to the book and shows its title, author and timeline date', () => {
  const node = mountNode();
  expect(node.findComponent(RouterLinkStub).props('to')).toBe('/books/b7');
  expect(node.text()).toContain('夜航');
  expect(node.text()).toContain('林澈');
  expect(node.text()).toContain('2020');
});

it('forwards cover state and reflects side and reveal flags', () => {
  const node = mountNode({ side: 'left', revealed: true });
  expect(node.get('.timeline-book').classes()).toEqual(
    expect.arrayContaining(['left', 'revealed']),
  );
  expect(node.findComponent(CoverImage).props('hasCover')).toBe(true);
});
```

- [ ] **Step 2: 跑单测确认红**

Run: `npx vitest run src/components/TimelineBook.test.ts`
Expected: FAIL——`TimelineBook.vue` 不存在。

- [ ] **Step 3: 实现 `TimelineBook.vue`（绿）**

```vue
<script setup lang="ts">
import { RouterLink } from 'vue-router';
import type { Book } from '../lib/types';
import { effectiveTime } from '../lib/timeline';
import { dayLabel, number, words } from '../lib/format';
import CoverImage from './CoverImage.vue';

const props = defineProps<{ book: Book; side: 'left' | 'right'; revealed: boolean }>();
</script>

<template>
  <li class="timeline-book" :class="[props.side, { revealed: props.revealed }]">
    <time class="timeline-date" :datetime="book.timelineDate ?? undefined">{{
      dayLabel(effectiveTime(book))
    }}</time>
    <RouterLink class="timeline-card" :to="'/books/' + book.id">
      <CoverImage
        :id="book.id"
        :title="book.title"
        :author="book.author"
        :has-cover="book.hasCover"
        small
      />
      <span class="timeline-card-copy">
        <span class="timeline-title">{{ book.title }}</span>
        <span class="timeline-author">{{ book.author }}</span>
        <span v-if="book.description" class="timeline-description">{{ book.description }}</span>
        <span class="timeline-meta"
          >{{ number(book.chapterCount) }} 章 · {{ words(book.characterCount) }}
          <span v-if="book.textPublished" class="timeline-flag">可阅读</span></span
        >
      </span>
    </RouterLink>
  </li>
</template>
```

- [ ] **Step 4: 跑单测确认绿**

Run: `npx vitest run src/components/TimelineBook.test.ts && npm run build`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
npm run format
git add frontend/src/components/TimelineBook.vue frontend/src/components/TimelineBook.test.ts
git commit -m "feat: render a timeline book node with cover and date

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 16: `StarfieldCanvas.vue`——全屏星空背景

**Files:**
- Create: `frontend/src/components/StarfieldCanvas.vue`
- Test: `frontend/src/components/StarfieldCanvas.test.ts`

**Interfaces:**
- Consumes: 无（自持）。读取 `window.matchMedia('(prefers-reduced-motion: reduce)')`、`devicePixelRatio`、`innerWidth/innerHeight`。
- Produces: 组件 `StarfieldCanvas`，渲染 `<canvas class="starfield" aria-hidden="true">`；motion 允许时跑 `requestAnimationFrame` 漂移动画，reduced-motion 时只画一次静态星场且不启动 rAF；卸载时 `cancelAnimationFrame` 并移除 resize 监听。供 Task 18 作为 `position: fixed` 背景层。

- [ ] **Step 1: 写 `StarfieldCanvas.test.ts`（红）**

```ts
import { mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import StarfieldCanvas from './StarfieldCanvas.vue';

const fakeCtx = () => ({
  setTransform: vi.fn(), clearRect: vi.fn(), fillRect: vi.fn(), fillStyle: '', globalAlpha: 1,
});
const stubMatchMedia = (reduced: boolean) =>
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: reduced }));

beforeEach(() =>
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(fakeCtx() as never),
);
afterEach(() => vi.unstubAllGlobals());

it('renders an aria-hidden canvas and animates when motion is allowed', () => {
  stubMatchMedia(false);
  const raf = vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(1 as never);
  const node = mount(StarfieldCanvas);
  expect(node.get('canvas.starfield').attributes('aria-hidden')).toBe('true');
  expect(raf).toHaveBeenCalled();
});

it('paints a static field without a rAF loop under reduced motion', () => {
  stubMatchMedia(true);
  const raf = vi.spyOn(window, 'requestAnimationFrame');
  mount(StarfieldCanvas);
  expect(raf).not.toHaveBeenCalled();
});

it('cancels the frame on unmount', () => {
  stubMatchMedia(false);
  vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(7 as never);
  const cancel = vi.spyOn(window, 'cancelAnimationFrame');
  mount(StarfieldCanvas).unmount();
  expect(cancel).toHaveBeenCalledWith(7);
});
```

- [ ] **Step 2: 跑单测确认红**

Run: `npx vitest run src/components/StarfieldCanvas.test.ts`
Expected: FAIL——组件不存在。

- [ ] **Step 3: 实现 `StarfieldCanvas.vue`（绿）**

```vue
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';

const canvas = ref<HTMLCanvasElement>();
let frame = 0;
let stars: { x: number; y: number; z: number; r: number }[] = [];
let onResize: (() => void) | null = null;

const reducedMotion = () =>
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

onMounted(() => {
  const el = canvas.value;
  const ctx = el?.getContext('2d');
  if (!el || !ctx) return; // jsdom / 无 Canvas 环境：安全退出，渐进增强。
  const dpr = Math.min(window.devicePixelRatio || 1, 2);

  const seed = () => {
    const w = window.innerWidth;
    const h = window.innerHeight;
    el.width = Math.floor(w * dpr);
    el.height = Math.floor(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const count = Math.min(220, Math.floor((w * h) / 9000));
    stars = Array.from({ length: count }, () => ({
      x: Math.random() * w, y: Math.random() * h,
      z: 0.3 + Math.random() * 0.7, r: 0.4 + Math.random() * 1.1,
    }));
  };
  const paint = () => {
    ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);
    for (const s of stars) {
      ctx.globalAlpha = 0.35 + s.z * 0.5;
      ctx.fillStyle = '#dfe7ff';
      ctx.fillRect(s.x, s.y, s.r, s.r);
    }
    ctx.globalAlpha = 1;
  };

  const tick = () => {
    for (const s of stars) {
      s.y -= s.z * 0.15; // 缓慢上升，呼应向上生长的银河。
      if (s.y < 0) s.y = window.innerHeight;
    }
    paint();
    frame = window.requestAnimationFrame(tick);
  };

  onResize = () => {
    seed();
    paint();
  };
  window.addEventListener('resize', onResize, { passive: true });
  seed();
  paint();
  if (!reducedMotion()) frame = window.requestAnimationFrame(tick);
});

onBeforeUnmount(() => {
  if (frame) window.cancelAnimationFrame(frame);
  if (onResize) window.removeEventListener('resize', onResize);
});
</script>

<template>
  <canvas ref="canvas" class="starfield" aria-hidden="true"></canvas>
</template>
```

- [ ] **Step 4: 跑单测确认绿**

Run: `npx vitest run src/components/StarfieldCanvas.test.ts && npm run build`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
npm run format
git add frontend/src/components/StarfieldCanvas.vue frontend/src/components/StarfieldCanvas.test.ts
git commit -m "feat: draw a drifting starfield that stills under reduced motion

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 17: `GalaxyVine.vue`——随滚动生长的银河藤蔓

**Files:**
- Create: `frontend/src/components/GalaxyVine.vue`
- Test: `frontend/src/components/GalaxyVine.test.ts`

**Interfaces:**
- Consumes: props `{ progress: number; nodes: number[] }`——`progress` 为滚动生长进度 `0..1`；`nodes` 为各书自底向上的归一化位置 `0..1`。
- Produces: 组件 `GalaxyVine`，渲染 `aria-hidden` 的 `<svg class="galaxy-vine">`：一条 `pathLength="1"` 的发光 `<path class="vine-path">`（`stroke-dashoffset = 1 - progress`，从底部向上生长），以及每个节点一个 `<circle class="vine-node">`（`at <= progress` 时加 `.lit`）。供 Task 18 传入滚动进度与节点位置。

- [ ] **Step 1: 写 `GalaxyVine.test.ts`（红）**

```ts
import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import GalaxyVine from './GalaxyVine.vue';

it('is decorative and binds growth to scroll progress', () => {
  const node = mount(GalaxyVine, { props: { progress: 0.25, nodes: [] } });
  expect(node.get('svg.galaxy-vine').attributes('aria-hidden')).toBe('true');
  expect(node.get('path.vine-path').attributes('style')).toContain('stroke-dashoffset: 0.75');
});

it('lights nodes the growing vine has already reached', () => {
  const node = mount(GalaxyVine, { props: { progress: 0.5, nodes: [0.2, 0.8] } });
  const dots = node.findAll('circle.vine-node');
  expect(dots).toHaveLength(2);
  expect(dots[0].classes()).toContain('lit');
  expect(dots[1].classes()).not.toContain('lit');
});
```

- [ ] **Step 2: 跑单测确认红**

Run: `npx vitest run src/components/GalaxyVine.test.ts`
Expected: FAIL——组件不存在。

- [ ] **Step 3: 实现 `GalaxyVine.vue`（绿）**

```vue
<script setup lang="ts">
defineProps<{ progress: number; nodes: number[] }>();
</script>

<template>
  <svg class="galaxy-vine" viewBox="0 0 100 1000" preserveAspectRatio="none" aria-hidden="true">
    <path
      class="vine-path"
      pathLength="1"
      :style="{ strokeDashoffset: 1 - progress }"
      d="M50 1000 C 20 800, 80 620, 50 500 S 20 260, 50 60"
    />
    <circle
      v-for="(at, i) in nodes"
      :key="i"
      class="vine-node"
      :class="{ lit: at <= progress }"
      cx="50"
      :cy="1000 - at * 1000"
      r="6"
    />
  </svg>
</template>
```

- [ ] **Step 4: 跑单测确认绿**

Run: `npx vitest run src/components/GalaxyVine.test.ts && npm run build`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
npm run format
git add frontend/src/components/GalaxyVine.vue frontend/src/components/GalaxyVine.test.ts
git commit -m "feat: grow an SVG galaxy vine that lights nodes by progress

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 18: `GalaxyView.vue`——银河时间轴首页容器

**Files:**
- Create: `frontend/src/views/GalaxyView.vue`
- Test: `frontend/src/views/GalaxyView.test.ts`

**Interfaces:**
- Consumes: `jsonRequest`/`errorMessage`（`api.ts`）；`orderByTimeline`（Task 14）；`StarfieldCanvas`/`GalaxyVine`/`TimelineBook`（Task 15–17）。后端 `GET /api/books` 依会话可见性返回访客的公开书或主人的全部书。
- Produces: 路由组件 `GalaxyView`（Task 19 挂到 `/`）。拉取并 `orderByTimeline` 升序，**倒序渲染**使最早的书落在页面底部（藤蔓根部）；单个 `IntersectionObserver`（阈值 0.2）逐本 `.revealed`；滚动更新藤蔓 `progress`；挂载后即时定位到底部；`prefers-reduced-motion` 或无 `IntersectionObserver` 时全部可见（渐进增强）；加载/错误/空态为深色变体；根 `.galaxy-view` + `body.galaxy-route` 作用域，卸载时清理观察者、监听与 body class。

- [ ] **Step 1: 写 `GalaxyView.test.ts`（红）**

```ts
import { RouterLinkStub, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import GalaxyView from './GalaxyView.vue';
import type { Book } from '../lib/types';

const res = (body: unknown) =>
  ({
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'application/json' }),
    json: async () => body,
    text: async () => JSON.stringify(body),
  }) as unknown as Response;

const makeBook = (over: Partial<Book>): Book => ({
  id: 'b', title: '书', author: '佚名', description: '', encoding: 'UTF-8',
  chapterCount: 1, volumeCount: 1, characterCount: 100, catalogPublished: true,
  textPublished: false, createdAt: 1, canRead: false, preface: null,
  timelineDate: null, hasCover: false, ...over,
});

const mountView = () => mount(GalaxyView, { global: { stubs: { RouterLink: RouterLinkStub } } });

beforeEach(() => vi.stubGlobal('scrollTo', vi.fn()));
afterEach(() => vi.unstubAllGlobals());
it('renders one node per book, newest first in the DOM', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(res([
    makeBook({ id: 'old', title: '起点', timelineDate: '2018-01-01' }),
    makeBook({ id: 'new', title: '现在', timelineDate: '2022-01-01' }),
  ])));
  const view = mountView();
  await flushPromises();
  await flushPromises();
  const nodes = view.findAll('.timeline-book');
  expect(nodes).toHaveLength(2);
  expect(nodes[0].text()).toContain('现在');
  expect(nodes[1].text()).toContain('起点');
});

it('shows a dark empty notice when the galaxy has no books', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(res([])));
  const view = mountView();
  await flushPromises();
  await flushPromises();
  expect(view.find('.timeline-book').exists()).toBe(false);
  expect(view.get('.galaxy-notice').text()).toContain('还没有');
});

it('reveals every book when IntersectionObserver is unavailable', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(res([
    makeBook({ id: 'a', title: 'A' }),
    makeBook({ id: 'z', title: 'Z' }),
  ])));
  const view = mountView();
  await flushPromises();
  await flushPromises();
  expect(view.findAll('.timeline-book.revealed')).toHaveLength(2);
});

it('reveals all and skips the observer under reduced motion', async () => {
  const IO = vi.fn(function (this: Record<string, unknown>) {
    this.observe = vi.fn();
    this.disconnect = vi.fn();
  });
  vi.stubGlobal('IntersectionObserver', IO);
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true }));
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(res([makeBook({ id: 'a', title: 'A' })])));
  const view = mountView();
  await flushPromises();
  await flushPromises();
  expect(IO).not.toHaveBeenCalled();
  expect(view.findAll('.timeline-book.revealed')).toHaveLength(1);
});
```

- [ ] **Step 2: 跑单测确认红**

Run: `npx vitest run src/views/GalaxyView.test.ts`
Expected: FAIL——`GalaxyView.vue` 不存在。

- [ ] **Step 3: 实现 `GalaxyView.vue`（绿）**

```vue
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import type { Book } from '../lib/types';
import { errorMessage, jsonRequest } from '../lib/api';
import { orderByTimeline } from '../lib/timeline';
import StarfieldCanvas from '../components/StarfieldCanvas.vue';
import GalaxyVine from '../components/GalaxyVine.vue';
import TimelineBook from '../components/TimelineBook.vue';

const books = ref<Book[]>([]);
const status = ref<'loading' | 'ready' | 'error'>('loading');
const error = ref('');
const revealed = ref(new Set<string>());
const progress = ref(0);

// 升序（旧→新）；倒序渲染让最早的书落在藤蔓根部（页面底部）。
const ordered = computed(() => orderByTimeline(books.value));
const rendered = computed(() => [...ordered.value].reverse());
// 节点自底向上归一化：最早→0、最新→1。
const nodes = computed(() => {
  const n = ordered.value.length;
  return ordered.value.map((_, i) => (n <= 1 ? 0 : i / (n - 1)));
});

const reducedMotion = () =>
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

let observer: IntersectionObserver | null = null;
let onScroll: (() => void) | null = null;

function updateProgress() {
  const max = document.documentElement.scrollHeight - window.innerHeight;
  // 底部（scrollY=max，最早）生长为 0；向上滚动趋近 1。
  progress.value = max <= 0 ? 1 : 1 - window.scrollY / max;
}

function revealAll() {
  revealed.value = new Set(books.value.map((b) => b.id));
}
onMounted(async () => {
  try {
    books.value = await jsonRequest<Book[]>('/api/books');
    status.value = 'ready';
  } catch (e) {
    error.value = errorMessage(e);
    status.value = 'error';
    return;
  }
  document.body.classList.add('galaxy-route');
  await nextTick();
  window.scrollTo(0, document.documentElement.scrollHeight); // 初始定位到底部（最早）。
  updateProgress();

  const reduce = reducedMotion();
  if (reduce || !('IntersectionObserver' in window)) {
    revealAll(); // 降级 / 无观察者：渐进增强，全部可见。
  } else {
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          const id = (entry.target as HTMLElement).dataset.book;
          if (id) revealed.value = new Set(revealed.value).add(id);
          observer?.unobserve(entry.target);
        }
      },
      { threshold: 0.2 },
    );
    for (const el of document.querySelectorAll<HTMLElement>('.timeline-book')) observer.observe(el);
  }

  if (reduce) {
    progress.value = 1; // 静态：藤蔓画满，不装滚动监听。
    return;
  }
  onScroll = () => window.requestAnimationFrame(updateProgress);
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll, { passive: true });
});

onBeforeUnmount(() => {
  observer?.disconnect();
  if (onScroll) {
    window.removeEventListener('scroll', onScroll);
    window.removeEventListener('resize', onScroll);
  }
  document.body.classList.remove('galaxy-route');
});
</script>

<template>
  <div class="galaxy-view">
    <StarfieldCanvas />
    <p v-if="status === 'loading'" class="galaxy-notice" role="status">正在点亮星河…</p>
    <p v-else-if="status === 'error'" class="galaxy-notice galaxy-error" role="alert">{{ error }}</p>
    <p v-else-if="ordered.length === 0" class="galaxy-notice">星河尚在孕育，还没有公开的书。</p>
    <div v-else class="galaxy-timeline">
      <GalaxyVine :progress="progress" :nodes="nodes" />
      <ol class="timeline-list">
        <TimelineBook
          v-for="(book, i) in rendered"
          :key="book.id"
          :book="book"
          :side="i % 2 === 0 ? 'right' : 'left'"
          :revealed="revealed.has(book.id)"
          :data-book="book.id"
        />
      </ol>
    </div>
  </div>
</template>
```

- [ ] **Step 4: 跑单测确认绿**

Run: `npx vitest run src/views/GalaxyView.test.ts && npm run build`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
npm run format
git add frontend/src/views/GalaxyView.vue frontend/src/views/GalaxyView.test.ts
git commit -m "feat: assemble the galaxy timeline homepage

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 19: 首页路由切换与深色页头页脚

**Files:**
- Modify: `frontend/src/router.ts:7-12`（`/` 路由）
- Modify: `frontend/src/App.vue:21`（header）、`:27`（导航 selected）、`:49`（footer）
- Modify: `frontend/src/views/AdminView.vue:113`（“查看书架”入口）
- Delete: `frontend/src/views/ShelfView.vue`、`frontend/src/views/ShelfView.test.ts`

**Interfaces:**
- Consumes: `GalaxyView`（Task 18）。
- Produces: `/` 命名路由 `galaxy`（`meta: { title: '星河', galaxy: true }`）指向 `GalaxyView`；`App.vue` 在 `route.meta.galaxy` 时给页头页脚加 `galaxy-chrome` 类（深色/透明变体，样式见 Task 20），并让“书库”导航项在首页高亮。删除旧 `ShelfView` 及其测试。

- [ ] **Step 1: 删除 `ShelfView.vue` 与 `ShelfView.test.ts`**

```bash
git rm frontend/src/views/ShelfView.vue frontend/src/views/ShelfView.test.ts
```

- [ ] **Step 2: 跑构建确认红**

Run: `npm run build`
Expected: FAIL——`router.ts` 仍 `import('./views/ShelfView.vue')`，vue-tsc 报找不到模块。

- [ ] **Step 3: 把 `/` 指向 `GalaxyView`（router.ts）**

将 `router.ts:7-12` 的 `/` 路由替换为：

```ts
    {
      path: '/',
      name: 'galaxy',
      component: () => import('./views/GalaxyView.vue'),
      meta: { title: '星河', galaxy: true },
    },
```

- [ ] **Step 4: 首页深色页头页脚与导航高亮（App.vue）**

`App.vue:21` header 加 `galaxy-chrome` 条件类：

```html
  <header
    v-if="!route.meta.reader"
    class="site-header"
    :class="{ 'galaxy-chrome': route.meta.galaxy }"
  >
```

`App.vue:27` 让“书库”项在首页也高亮：

```html
    <RouterLink to="/" :class="{ selected: route.name === 'book' || route.name === 'galaxy' }"
      >书库</RouterLink
```

`App.vue:49` footer 同样加条件类：

```html
  <footer
    v-if="!route.meta.reader"
    class="site-footer"
    :class="{ 'galaxy-chrome': route.meta.galaxy }"
  >
```

- [ ] **Step 5: 管理书房“查看书架”改为“回到星河”（AdminView.vue:113）**

```html
      <RouterLink to="/" class="button secondary">回到星河 <ArrowUpRight :size="17" /></RouterLink>
```

- [ ] **Step 6: 跑测试与构建确认绿**

Run: `npm test && npm run build`
Expected: PASS——`ShelfView.test.ts` 已随组件删除，其余单测（含 `GalaxyView.test.ts`）通过，vue-tsc 通过。

- [ ] **Step 7: 提交**

```bash
npm run format
git add -A frontend/src/router.ts frontend/src/App.vue frontend/src/views/AdminView.vue
git commit -m "feat: make the galaxy timeline the homepage

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 20: 首页银河深色作用域样式

**Files:**
- Modify: `frontend/src/style.css`（在文件末尾**追加**银河样式；不改任何既有规则）

**Interfaces:**
- Consumes: Task 15–19 产出的类名（`.galaxy-view`/`.starfield`/`.galaxy-vine`/`.vine-path`/`.vine-node`/`.timeline-list`/`.timeline-book`(`.left`/`.right`/`.revealed`)/`.timeline-date`/`.timeline-card`/`.timeline-card-copy`/`.timeline-title`/`.timeline-author`/`.timeline-description`/`.timeline-meta`/`.timeline-flag`/`.book-cover-image`）、`body.galaxy-route`、`.galaxy-chrome`。
- Produces: 深色星空作用域样式：仅在 `body.galaxy-route` 下改背景与页头页脚配色，不动全局暖纸 tokens；书卡揭示/悬停过渡；`prefers-reduced-motion` 降级为静态；`≤720px` 单列并让藤蔓贴左。颜色按 WCAG AA 对比选取（Task 21 axe 校验）。

> 旧 `ShelfView` 专用的 `.shelf-*` 规则此刻成为死代码，但本任务不删除它们（散落多处、删除易误伤），以保持 diff 聚焦；作为独立清理留待后续（见 §风险）。

- [ ] **Step 1: 在 `style.css` 末尾追加银河样式**

```css
/* ===== 银河时间轴首页（作用域：body.galaxy-route / .galaxy-view） ===== */
body.galaxy-route {
  background: radial-gradient(1200px 800px at 50% -10%, #16213e 0%, #0b1026 55%, #05060f 100%);
  color: #e8ecff;
}
body.galaxy-route .site-header.galaxy-chrome,
body.galaxy-route .site-footer.galaxy-chrome {
  background: transparent;
  border-color: rgba(200, 214, 255, 0.16);
  color: #e8ecff;
}
body.galaxy-route .site-header.galaxy-chrome a,
body.galaxy-route .site-header.galaxy-chrome .brand,
body.galaxy-route .site-footer.galaxy-chrome {
  color: #e8ecff;
}

.galaxy-view {
  position: relative;
  min-height: 100vh;
  overflow-x: hidden;
}
.starfield {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
  pointer-events: none;
}
.galaxy-notice {
  position: relative;
  z-index: 1;
  text-align: center;
  padding: 6rem 1.5rem;
  color: #c8d3ff;
  font-size: 1.05rem;
}
.galaxy-error {
  color: #ffb4b4;
}
.galaxy-timeline {
  position: relative;
  z-index: 1;
  padding: 12vh 0 16vh;
}
.galaxy-vine {
  position: absolute;
  left: 50%;
  top: 0;
  transform: translateX(-50%);
  width: min(120px, 22vw);
  height: 100%;
  overflow: visible;
  pointer-events: none;
}
.vine-path {
  fill: none;
  stroke: #8ea2ff;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-dasharray: 1;
  filter: drop-shadow(0 0 6px rgba(142, 162, 255, 0.9));
  transition: stroke-dashoffset 0.2s linear;
}
.vine-node {
  fill: #24305e;
  stroke: #8ea2ff;
  stroke-width: 1.5;
  transition:
    fill 0.4s ease,
    filter 0.4s ease;
}
.vine-node.lit {
  fill: #cfe0ff;
  filter: drop-shadow(0 0 6px rgba(207, 224, 255, 0.9));
}
.timeline-list {
  list-style: none;
  margin: 0;
  padding: 0;
  position: relative;
  z-index: 1;
}
.timeline-book {
  position: relative;
  width: min(420px, 82vw);
  margin: 8vh auto;
  opacity: 0;
  transform: translateY(28px);
  transition:
    opacity 0.7s ease,
    transform 0.7s ease;
}
.timeline-book.right {
  margin-right: 0;
  margin-left: calc(50% + 40px);
}
.timeline-book.left {
  margin-left: 0;
  margin-right: calc(50% + 40px);
}
.timeline-book.revealed {
  opacity: 1;
  transform: none;
}
.timeline-date {
  display: block;
  font-size: 0.85rem;
  letter-spacing: 0.08em;
  color: #9fb0ff;
  margin-bottom: 0.5rem;
}
.timeline-card {
  display: flex;
  gap: 1rem;
  padding: 1rem;
  border-radius: 16px;
  background: rgba(20, 28, 58, 0.72);
  border: 1px solid rgba(142, 162, 255, 0.22);
  color: #e8ecff;
  text-decoration: none;
  backdrop-filter: blur(6px);
  transition:
    transform 0.3s ease,
    box-shadow 0.3s ease,
    border-color 0.3s ease;
}
.timeline-card:hover {
  transform: translateY(-4px);
  border-color: rgba(142, 162, 255, 0.55);
  box-shadow: 0 12px 40px rgba(10, 16, 40, 0.6);
}
.timeline-card-copy {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  min-width: 0;
}
.timeline-title {
  font-size: 1.15rem;
  font-weight: 600;
}
.timeline-author {
  color: #c2ccf2;
  font-size: 0.9rem;
}
.timeline-description {
  color: #ccd5f5;
  font-size: 0.9rem;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.timeline-meta {
  color: #aeb9e4;
  font-size: 0.82rem;
  margin-top: auto;
}
.timeline-flag {
  margin-left: 0.5rem;
  padding: 0.05rem 0.5rem;
  border-radius: 999px;
  background: rgba(142, 162, 255, 0.18);
  color: #cfe0ff;
}
.book-cover-image {
  width: 84px;
  height: 118px;
  object-fit: cover;
  border-radius: 10px;
  flex: none;
}
.book-cover-image.small {
  width: 56px;
  height: 78px;
}

@media (max-width: 720px) {
  .timeline-book,
  .timeline-book.left,
  .timeline-book.right {
    width: min(460px, 86vw);
    margin-left: auto;
    margin-right: auto;
    padding-left: 44px;
  }
  .galaxy-vine {
    left: 20px;
    transform: none;
    width: 40px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .timeline-book {
    opacity: 1;
    transform: none;
    transition: none;
  }
  .vine-path,
  .vine-node,
  .timeline-card {
    transition: none;
  }
  .timeline-card:hover {
    transform: none;
    box-shadow: none;
  }
}
```

- [ ] **Step 2: 构建，确认 CSS 能被打包解析且类型检查通过**

Run: `cd frontend && npm run build`
Expected: PASS（`vue-tsc -b` 无类型错误、`vite build` 成功打包新样式；无 CSS 语法报错）。

- [ ] **Step 3: 人工目视核对（不阻断，但建议执行）**

Run: `cd frontend && npm run dev`，浏览器打开 `/`：
- 首页为深色星空、页头/页脚透明深色变体、对比度可读；
- 藤蔓居中纵向发光、节点随滚动点亮；书卡左右交替、进入视口时淡入上浮；
- 离开首页（进入 `/books/:id`、`/admin` 等）恢复暖纸浅色（`body.galaxy-route` 被移除）；
- 系统开启“减弱动态效果”后：无过渡、藤蔓满格、全部书卡直接可见。
（自动化可访问性/降级校验在 Task 21 的 Playwright + axe 完成。）

- [ ] **Step 4: 格式化并提交**

```bash
cd frontend && npm run format
git add frontend/src/style.css
git commit -m "$(cat <<'EOF'
feat: theme the galaxy homepage in a scoped dark starfield

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

### Task 21: 端到端用例改指银河首页

**Files:**
- Modify: `frontend/e2e/library.spec.ts`（仅 Test 2 的 `L189–194` 落地段、Test 3 整体；**不动** Test 1，它从不访问 `/`）

**Interfaces:**
- Consumes: Task 18–20 产出的银河首页——公开书渲染为一个包裹 `CoverImage` + 文案的 `RouterLink :to="/books/:id"`（无障碍名包含书名）；空态为 `<p class="galaxy-notice">星河尚在孕育，还没有公开的书。</p>`（无 heading）；`prefers-reduced-motion: reduce` 时 `GalaxyView` 走 `revealAll()`、书卡静态可见、藤蔓满格。
- Produces: 两条访问 `/` 的用例改用银河语义并**强制减弱动态**（`page.emulateMedia({ reducedMotion: 'reduce' })`）使揭示确定化、axe 校验稳定；`expectAccessible` 保留。

> 旧断言依赖 `ShelfView`：`我的书架` 标题、`搜索书名或作者` 搜索框、`.book-card`、空态 `书架还在整理中` 标题——银河首页均不存在。改为点击时间轴节点链接、匹配空态提示文案。WCAG 2a/2aa 不要求 `h1`，空态用段落即可通过 `expectAccessible`。

- [ ] **Step 1: 改写 Test 2 的落地段（当前 `L189–194`）**

把这 6 行：

```ts
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '我的书架' })).toBeVisible();
  await expectAccessible(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByLabel('搜索书名或作者').fill('手机验收');
  await page.locator('.book-card').click();
```

替换为：

```ts
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  await expect(page.getByRole('link', { name: /手机验收读本/ })).toBeVisible();
  await expectAccessible(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('link', { name: /手机验收读本/ }).click();
```

其后 `L195` 起（`await expectAccessible(page);` → `开始阅读` → 阅读器流程）保持不变：节点链接与旧 `.book-card` 一样导向 `/books/:id`（`BookView`），后续步骤照旧。

- [ ] **Step 2: 改写 Test 3（空态可访问性，当前 `L233–237`）**

把整段：

```ts
test('empty public shelf has no detected WCAG A/AA violations', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '书架还在整理中' })).toBeVisible();
  await expectAccessible(page);
});
```

替换为：

```ts
test('empty public galaxy has no detected WCAG A/AA violations', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  await expect(page.getByText('还没有公开的书')).toBeVisible();
  await expectAccessible(page);
});
```

`getByText` 默认子串匹配，命中完整文案“星河尚在孕育，还没有公开的书。”。

- [ ] **Step 3: 运行端到端套件，确认三条用例全绿**

Run: `cd frontend && npm run test:e2e`
Expected: PASS——Test 1（书主导入/书签/发布，不访问 `/`）不受影响；Test 2 移动端访客经银河节点进入阅读、无横向滚动、axe 无违规；Test 3 空态银河 axe 无 WCAG A/AA 违规。
（`npm run test:e2e` 依既有 Playwright 配置拉起前后端与隔离库；沿用现有 `expectAccessible` / `apiLogin` / `createPublic` 基建。）

- [ ] **Step 4: 格式化并提交**

```bash
cd frontend && npm run format
git add frontend/e2e/library.spec.ts
git commit -m "$(cat <<'EOF'
test: point library e2e at the galaxy homepage

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

## Phase 5：交互层增强（借鉴 OriginKit 交互种类，手写；reduced-motion 降级）

> 交互清单落位（对照 spec §D5，避免遗漏或越界）：
> - **星空指针视差** → 本 Task 22（`StarfieldCanvas` 增指针偏移，仅桌面细指针 + 允许动效时启用）。
> - **藤蔓随滚动生长 / 节点渐次点亮** → 已在 Task 17（`GalaxyVine`）+ Task 18（`GalaxyView` 滚动进度）落地。
> - **书卡悬停上浮 / 发光** → 已在 Task 20 CSS `.timeline-card:hover` 落地（纯 CSS 手写，`prefers-reduced-motion` 下 `:hover` 变换归零）。
> - **入场揭示** → 已在 Task 18 `IntersectionObserver` + Task 20 `.timeline-book.revealed` 过渡落地。
> - **封面 3D 指针倾斜、相邻节点星座连线** → **本轮不做**（spec §D5 标“可选”、§风险 5 要求零新增依赖与手写；以 CSS 悬停上浮/发光作为等价的轻量手写效果，指针跟随倾斜留待后续，不扩大范围）。

### Task 22: 星空指针视差（桌面 hover，触摸与 reduced-motion 不启用）

**Files:**
- Modify: `frontend/src/components/StarfieldCanvas.vue`（在 Task 16 的组件上增补指针视差）
- Modify: `frontend/src/components/StarfieldCanvas.test.ts`（追加三条断言）

**Interfaces:**
- Consumes: Task 16 的 `StarfieldCanvas`（`canvas` ref、`stars`、`reducedMotion()`、rAF `tick`/`paint`、resize 清理）。
- Produces: 允许动效**且** `(pointer: fine)`（桌面鼠标）时，监听被动 `pointermove`，按指针相对屏幕中心的偏移让近处（`z` 大）星点位移更多，形成纵深视差；触摸（coarse）或 reduced-motion 时**不**监听；卸载时移除 `pointermove`。视差偏移在既有 rAF `paint()` 中读取，无需额外循环。

- [ ] **Step 1: 追加三条单测（红）——写入 `StarfieldCanvas.test.ts` 末尾**

```ts
it('follows the pointer with parallax on a fine pointer', () => {
  vi.stubGlobal('matchMedia', vi.fn((q: string) => ({ matches: q.includes('pointer: fine') })));
  vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(1 as never);
  const add = vi.spyOn(window, 'addEventListener');
  const node = mount(StarfieldCanvas);
  expect(add).toHaveBeenCalledWith('pointermove', expect.any(Function), { passive: true });
  const remove = vi.spyOn(window, 'removeEventListener');
  node.unmount();
  expect(remove).toHaveBeenCalledWith('pointermove', expect.any(Function));
});

it('skips pointer parallax on a coarse (touch) pointer', () => {
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false })));
  vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(1 as never);
  const add = vi.spyOn(window, 'addEventListener');
  mount(StarfieldCanvas);
  expect(add).not.toHaveBeenCalledWith('pointermove', expect.any(Function), expect.anything());
});

it('skips pointer parallax under reduced motion', () => {
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })));
  const add = vi.spyOn(window, 'addEventListener');
  mount(StarfieldCanvas);
  expect(add).not.toHaveBeenCalledWith('pointermove', expect.any(Function), expect.anything());
});
```

> 复用文件已有的 `beforeEach`（stub `getContext`）与 `afterEach`（`vi.unstubAllGlobals()`）。这三条自带查询感知的 `matchMedia`，覆盖 Task 16 里对所有查询返回同一 `matches` 的简单桩，不影响原三条断言。

- [ ] **Step 2: 跑单测确认红**

Run: `cd frontend && npx vitest run src/components/StarfieldCanvas.test.ts`
Expected: 新增三条中至少 `follows the pointer with parallax on a fine pointer` FAIL——当前组件从不监听 `pointermove`。

- [ ] **Step 3: 用带指针视差的版本替换 `StarfieldCanvas.vue` 的 `<script setup>`（绿）**

将 Task 16 的整个 `<script setup lang="ts"> ... </script>` 替换为下面这段（`<template>` 不变）：

```vue
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';

const canvas = ref<HTMLCanvasElement>();
let frame = 0;
let stars: { x: number; y: number; z: number; r: number }[] = [];
let onResize: (() => void) | null = null;
let onPointer: ((e: PointerEvent) => void) | null = null;

const reducedMotion = () =>
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
const finePointer = () => window.matchMedia?.('(pointer: fine)').matches ?? false;

onMounted(() => {
  const el = canvas.value;
  const ctx = el?.getContext('2d');
  if (!el || !ctx) return; // jsdom / 无 Canvas 环境：安全退出，渐进增强。
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const parallax = { x: 0, y: 0 }; // 指针相对屏幕中心的偏移（约 -0.5..0.5）。

  const seed = () => {
    const w = window.innerWidth;
    const h = window.innerHeight;
    el.width = Math.floor(w * dpr);
    el.height = Math.floor(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const count = Math.min(220, Math.floor((w * h) / 9000));
    stars = Array.from({ length: count }, () => ({
      x: Math.random() * w, y: Math.random() * h,
      z: 0.3 + Math.random() * 0.7, r: 0.4 + Math.random() * 1.1,
    }));
  };
  const paint = () => {
    ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);
    for (const s of stars) {
      ctx.globalAlpha = 0.35 + s.z * 0.5;
      ctx.fillStyle = '#dfe7ff';
      // 近处（z 大）的星随指针偏移更多，形成纵深视差。
      ctx.fillRect(s.x + parallax.x * s.z * 18, s.y + parallax.y * s.z * 18, s.r, s.r);
    }
    ctx.globalAlpha = 1;
  };
  const tick = () => {
    for (const s of stars) {
      s.y -= s.z * 0.15; // 缓慢上升，呼应向上生长的银河。
      if (s.y < 0) s.y = window.innerHeight;
    }
    paint();
    frame = window.requestAnimationFrame(tick);
  };

  onResize = () => {
    seed();
    paint();
  };
  window.addEventListener('resize', onResize, { passive: true });
  seed();
  paint();
  if (!reducedMotion()) {
    frame = window.requestAnimationFrame(tick);
    if (finePointer()) {
      onPointer = (e) => {
        parallax.x = e.clientX / window.innerWidth - 0.5;
        parallax.y = e.clientY / window.innerHeight - 0.5;
      };
      window.addEventListener('pointermove', onPointer, { passive: true });
    }
  }
});

onBeforeUnmount(() => {
  if (frame) window.cancelAnimationFrame(frame);
  if (onResize) window.removeEventListener('resize', onResize);
  if (onPointer) window.removeEventListener('pointermove', onPointer);
});
</script>
```

- [ ] **Step 4: 跑单测与构建确认绿**

Run: `cd frontend && npx vitest run src/components/StarfieldCanvas.test.ts && npm run build`
Expected: PASS——六条断言（Task 16 的三条 + 本任务三条）全绿；`vue-tsc -b` 类型检查通过（`onPointer` 的 `PointerEvent` 参数类型无隐式 any）。

- [ ] **Step 5: 格式化并提交**

```bash
cd frontend && npm run format
git add frontend/src/components/StarfieldCanvas.vue frontend/src/components/StarfieldCanvas.test.ts
git commit -m "$(cat <<'EOF'
feat: add desktop pointer parallax to the starfield

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

## Phase 6 — Redis 痕迹清理（脚本 / CI / 示例 / 文档）

> 后端 Java / YAML 的去 Redis 已在 Phase 1（Task 1）完成并由其 `mvn test` 验证。本阶段只清理**脚本、CI、`.env` 示例、前端 E2E 基建与文档**（规格 §E）。**不做** `.env`→`cloudnovel.*` profile 迁移（见 Global Constraints）；脚本仅最小化删除 Redis，`.env`/`DB_URL` 注入机制原样保留。
>
> **执行顺序有依赖**：Task 23 先让本地会真正执行的 `Test.ps1` / `Test-Scripts.ps1` 不再引用 `deploy/compose.redis.yml`，Task 24 才 `git rm` 该文件；全仓“无 Redis 残留”复核放在最后的 Task 26。

### Task 23: 从五个 PowerShell 脚本移除 Redis

五个脚本彼此耦合，且唯一校验入口是 `Test-Scripts.ps1`（它解析并断言其余四个脚本的输出与内容），因此必须作为一个原子任务、以 `Test-Scripts.ps1` 通过为验收。**安全不回退**：删除 Redis 合成项时，把“配置值不得作为 PowerShell 表达式执行”的探针从 `REDIS_PASSWORD` 迁移到保留字段 `DB_PASSWORD`，覆盖不丢失（见 Step 5 与 Review Focus 补充）。

**Files:**
- Modify: `scripts/Prepare-LocalEnvironment.ps1`（删 `-RedisPassword` 形参、redis 密钥与校验、`$app`/`$test` 的 REDIS_* 行、`$redis` heredoc、`redis.conf` 生成、文档指向）
- Modify: `scripts/Import-LocalConfig.ps1`（白名单删 REDIS_* / TEST_REDIS_*）
- Modify: `scripts/Start-Local.ps1`（不再要求 `REDIS_PASSWORD`、文案/注释去 Redis）
- Modify: `scripts/Test.ps1`（`$testNames` 删 TEST_REDIS_*、prettier 列表删 `compose.redis.yml`、文案去 Redis）
- Modify: `scripts/Test-Scripts.ps1`（删所有 redis 名单/合成行/断言、密码数 4→3、删两个 `-RedisPassword` 子用例、Start 就绪断言去 Redis、执行探针迁移到 `DB_PASSWORD`）

**Interfaces:**
- Consumes: Task 3 已给 `books` 加两列并向 `schema.sql` 追加 `-- ALTER ...` 注释——`Test-Scripts.ps1` 的 `^CREATE TABLE IF NOT EXISTS` 计数仍为 15、`^\s*(DROP|...|ALTER|...)` 负向断言仍成立（注释行以 `--` 开头，不匹配）。
- Produces: 生成的 `.local/setup` 只含 `application.env` / `test.env` / `bootstrap.sql`（无 `redis.conf`）；`.env` 白名单不含任何 `REDIS_*`。

- [ ] **Step 1: `Prepare-LocalEnvironment.ps1` 去 Redis**

- 形参：删除 `[Security.SecureString]$RedisPassword`，`param(...)` 只剩一行 `[string]$ProjectRoot = (Join-Path $PSScriptRoot '..')`（去掉其后逗号）。
- `$names`（约第 8 行）改为 `$names = @('application.env', 'test.env', 'bootstrap.sql')`。
- 删除 `$redisSecret = if ($RedisPassword) {...} else { New-LocalSecret }`（约第 42 行）与其后两行注释 + token 字母表校验 `if ($redisSecret -cnotmatch ...) { throw ... }`（约第 43-47 行）。
- `$app` heredoc（约第 50-65 行）删除 `REDIS_HOST` / `REDIS_PORT` / `REDIS_USERNAME` / `REDIS_PASSWORD` / `REDIS_DATABASE` / `REDIS_NAMESPACE` / `REDIS_SSL` 七行，保留 `BOOK_STORAGE=./data/books`。
- `$test` heredoc（约第 66-77 行）删除 `TEST_REDIS_HOST` / `TEST_REDIS_PORT` / `TEST_REDIS_USERNAME` / `TEST_REDIS_PASSWORD` 四行。
- 删除 `$redis = @"..."@` 整段（约第 95-106 行）与 `Write-NewPrivateFile 'redis.conf' ($redis + [Environment]::NewLine)`（约第 110 行）。
- 末行提示（约第 113 行）`docs/mysql-redis-setup.md` 改为 `docs/mysql-setup.md`。

- [ ] **Step 2: `Import-LocalConfig.ps1` 白名单去 Redis**

- `$runtimeNames`（约第 6 行）删除 `'REDIS_HOST', 'REDIS_PORT', 'REDIS_USERNAME', 'REDIS_PASSWORD', 'REDIS_DATABASE', 'REDIS_NAMESPACE', 'REDIS_SSL'`，结果为 `@('ADMIN_USERNAME', 'ADMIN_PASSWORD', 'COOKIE_SECURE', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'BOOK_STORAGE')`。
- `$testNames`（约第 7 行）删除 `'TEST_REDIS_HOST', 'TEST_REDIS_PORT', 'TEST_REDIS_USERNAME', 'TEST_REDIS_PASSWORD'`，结果为 `@('TEST_MYSQL_HOST', 'TEST_MYSQL_PORT', 'TEST_DB_USERNAME', 'TEST_DB_PASSWORD', 'E2E_DB_USERNAME', 'E2E_DB_PASSWORD')`。

- [ ] **Step 3: `Start-Local.ps1` 去 Redis**

- 第 15 行 `throw 'Prepare MySQL / Redis first: see docs/mysql-redis-setup.md. There is no embedded database fallback.'` 改为 `throw 'Prepare MySQL first: see docs/mysql-setup.md. There is no embedded database fallback.'`
- 第 21 行 `foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'REDIS_PASSWORD'))` 改为 `foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD'))`。
- 第 23 行 `throw "Missing $name. Follow docs/mysql-redis-setup.md; no service was started."` 改为 `throw "Missing $name. Follow docs/mysql-setup.md; no service was started."`
- 第 33 行注释 `Full isolated MySQL/Redis verification is scripts/Test.ps1.` 改为 `Full isolated MySQL verification is scripts/Test.ps1.`

- [ ] **Step 4: `Test.ps1` 去 Redis**

- 第 8 行 `throw 'BrowserTests requires real MySQL/Redis and cannot be combined with UnitOnly.'` 改为 `throw 'BrowserTests requires real MySQL and cannot be combined with UnitOnly.'`
- 第 9 行 `$testNames` 删除 `'TEST_REDIS_HOST', 'TEST_REDIS_PORT', 'TEST_REDIS_USERNAME', 'TEST_REDIS_PASSWORD'`（保留 `'NOVEL_TEST_FILE'`），结果为 `@('TEST_MYSQL_HOST', 'TEST_MYSQL_PORT', 'TEST_DB_USERNAME', 'TEST_DB_PASSWORD', 'E2E_DB_USERNAME', 'E2E_DB_PASSWORD', 'NOVEL_TEST_FILE')`。
- 第 20 行 `throw 'Prepare the dedicated MySQL test schema/account and .env.test first. See docs/mysql-redis-setup.md. UnitOnly is explicit and is not full verification.'` 把 `docs/mysql-redis-setup.md` 改为 `docs/mysql-setup.md`。
- 第 44 行 prettier 列表删除 `'../deploy/compose.redis.yml'` 实参（此后本地执行的 `Test.ps1` 不再引用该文件，Task 24 才能安全删除它）。
- 第 55 行 `Write-Host 'Unit/static/build verification passed. Real MySQL/Redis integration and browser tests were NOT run.'` 把 `MySQL/Redis` 改为 `MySQL`。
- 第 56 行 `else { Write-Host 'All requested MySQL/Redis verification passed.' }` 把 `MySQL/Redis` 改为 `MySQL`。

- [ ] **Step 5: `Test-Scripts.ps1` 去 Redis（含执行探针迁移）**

这是唯一校验入口，改动必须与 Step 1-4 后的脚本内容/输出**逐字一致**，否则脚本自身会 `throw`。

- `$names`（约第 29 行，环境快照/恢复用）删除 7 个 `REDIS_*` 与 4 个 `TEST_REDIS_*` 名称。
- 合成 `application.env` 的 `$lines`（约第 42-55 行）删除 `REDIS_HOST` / `REDIS_PORT` / `REDIS_USERNAME` / `REDIS_DATABASE` / `REDIS_NAMESPACE` / `REDIS_SSL` 行。
- **执行探针迁移**：把合成 `'DB_PASSWORD=synthetic=password=with=equals'`（约第 46 行）改为 `'DB_PASSWORD=$(throw "must not execute")=synthetic=with=equals'`；随后删除 `'REDIS_PASSWORD=$(throw "must not execute")=literal'` 合成行（约第 52 行）。
- 合成 `test.env` 的行（约第 62-65 行）删除 `TEST_REDIS_HOST` / `TEST_REDIS_PORT` / `TEST_REDIS_USERNAME` / `TEST_REDIS_PASSWORD`。
- 运行时导入断言（约第 72-83 行）：
  - 把等号字面量断言（约第 73 行）与执行不发生断言（原第 79 行）**合并为一条**：
    ```powershell
    Assert-Check ($env:DB_PASSWORD -eq '$(throw "must not execute")=synthetic=with=equals') 'Password equals signs must remain literal and configuration must not execute PowerShell expressions.'
    ```
  - 删除原 `REDIS_PASSWORD` 执行断言行（约第 79 行）、`REDIS_HOST`/`REDIS_NAMESPACE` 等运行时断言（约第 77-78、80 行）与 `TEST_REDIS_*` 断言（约第 89 行）。
- 生成产物名单 `$outputs`（约第 109 行）改为 `@('application.env', 'test.env', 'bootstrap.sql')`。
- 删除 `$redis = [IO.File]::ReadAllText((Join-Path $setup 'redis.conf'))`（约第 119 行）及其后所有以 `$redis` 为主语的断言（约第 126-127 行）。
- 存储默认值断言（约第 122 行）改为 `Assert-Check ($app.BOOK_STORAGE -eq './data/books') 'Default business storage must remain local and private.'`
- 密码独立性：`$secrets`（约第 123 行）删除 `$app.REDIS_PASSWORD`；计数断言（约第 125 行）`($secrets | Select-Object -Unique).Count -eq 4` 改为 `-eq 3`，消息改为 `'Application, integration and browser passwords must be independent.'`
- bootstrap.sql 断言**保持不变**：`^CREATE TABLE IF NOT EXISTS` 计数 `-eq 15`、三库存在、以及无 `DROP|TRUNCATE|GRANT|ALTER|...` 负向断言（Task 3 只加列不加表；ALTER 仅出现在 `--` 注释里，不匹配行首）。
- 删除 `-RedisPassword` 相关的两个子进程用例：`existing-redis-project` 块（约第 153-159 行）与 `invalid-redis-project` 块（约第 160-167 行）。
- Start 就绪断言（约第 171 行）改为：
  ```powershell
  Assert-Check ($start.Contains('/api/ready') -and $start.Contains('/api/books')) 'Startup must verify MySQL and schema readiness.'
  ```
  （删除原 `$start.Contains("'REDIS_PASSWORD'") -and` 片段。）
- 结尾的环境恢复循环沿用收缩后的 `$names`，无需另改。

- [ ] **Step 6: 运行脚本自校验（唯一验收）**

Run:
```bash
pwsh -NoProfile ./scripts/Test-Scripts.ps1
```
Expected: 全部 `Assert-Check` 通过、无 `throw`、退出码 0；输出中不含 `REDIS`/`redis.conf` 字样。若因某处 redis 名单/断言/合成值遗漏而 `throw`，按报错定位补齐 Step 1-5，再重跑。

- [ ] **Step 7: 提交**

```bash
git add scripts/Prepare-LocalEnvironment.ps1 scripts/Import-LocalConfig.ps1 scripts/Start-Local.ps1 scripts/Test.ps1 scripts/Test-Scripts.ps1
git commit -m "$(printf 'refactor: drop Redis provisioning from local scripts\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

### Task 24: 删除 `compose.redis.yml`、清理 CI 与 `.env` 示例、`.gitignore`

**必须在 Task 23 之后**：本地执行的 `Test.ps1` / `Test-Scripts.ps1` 到此已不再引用 `deploy/compose.redis.yml`，现在删除它才不会让任一本地校验指向缺失文件。

**Files:**
- Delete: `deploy/compose.redis.yml`
- Modify: `.github/workflows/ci.yml`（删 `redis` service 容器与 `TEST_REDIS_*` env、步骤名与 prettier 列表去 Redis）
- Modify: `.env.example`、`.env.test.example`（删 REDIS_* / TEST_REDIS_* 与相关注释）
- Modify: `.gitignore`（注释去掉 Redis 措辞；`/.local/` 忽略保留）

**Interfaces:**
- Consumes: Task 23 已移除 `Test.ps1` prettier 列表里的 `compose.redis.yml`。
- Produces: CI 后端集成测试仅起 `mysql` service；`.env` 示例无任何 Redis 键。

- [ ] **Step 1: 删除 `deploy/compose.redis.yml`**

```bash
git rm deploy/compose.redis.yml
```

- [ ] **Step 2: `.github/workflows/ci.yml` 去 Redis**

- 删除后端集成测试 job 的 `TEST_REDIS_HOST` / `TEST_REDIS_PORT` 两条 `env`（约第 20-21 行）。
- 删除整段 `redis:` service 定义（`image: redis:7.4-alpine` 及其 `ports` / `options` 健康检查，约第 36-44 行）。
- 步骤名 `Backend verify (real MySQL and Redis integration, JavaDoc, ...)`（约第 75 行）删去 `and Redis`。
- 前端 prettier 检查列表（约第 88 行）删除 `../deploy/compose.redis.yml`。
- 步骤名 `... against real MySQL and Redis`（约第 94 行）删去 `and Redis`。

- [ ] **Step 3: `.env.example` / `.env.test.example` 去 Redis**

- `.env.example`：首行注释 `# MySQL + Redis are required ...` 改为 `# MySQL is required ...`；删除 `REDIS_HOST` / `REDIS_PORT` / `REDIS_USERNAME` / `REDIS_PASSWORD` / `REDIS_DATABASE` / `REDIS_NAMESPACE` / `REDIS_SSL` 七行（约第 11-17 行）。
- `.env.test.example`：删除 `TEST_REDIS_HOST` / `TEST_REDIS_PORT` / `TEST_REDIS_USERNAME` / `TEST_REDIS_PASSWORD` 四行（约第 9-12 行）与其后注释 `# Integration uses Redis DB 15, E2E uses DB 14, each with a unique namespace.`（约第 13 行）。

- [ ] **Step 4: `.gitignore` 注释去 Redis**

第 26 行注释 `# Generated credentials, provisioning SQL and local Redis configuration` 改为 `# Generated credentials and provisioning SQL`；下一行 `/.local/` 忽略规则保持不变。

- [ ] **Step 5: 校验 CI 格式、无残留、删除生效**

Run:
```bash
cd frontend && npx prettier --check ../.github/workflows/ci.yml
```
Expected: `ci.yml` 通过 prettier（无 YAML 缩进/尾随问题）。

Run:
```bash
grep -rin "redis" .github .env.example .env.test.example .gitignore deploy
```
Expected: 无输出（`deploy/compose.redis.yml` 已删；其余文件已清）。

Run:
```bash
git status --short deploy/compose.redis.yml
```
Expected: 显示 `D  deploy/compose.redis.yml`（已 staged 删除）。

- [ ] **Step 6: 提交**

```bash
git add .github/workflows/ci.yml .env.example .env.test.example .gitignore
git commit -m "$(printf 'ci: remove Redis service and env sample keys\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

### Task 25: 前端 E2E 基建移除 Redis

`frontend/testing/infrastructure.ts` 为 Playwright 构造后端环境变量覆盖；去 Redis 后不再注入任何 `SPRING_DATA_REDIS_*` / `SPRING_SESSION_REDIS_*` / `REDIS_*`，也不再校验 `TEST_REDIS_*`。同步收敛其单测。

**Files:**
- Modify: `frontend/testing/infrastructure.ts`
- Test: `frontend/testing/infrastructure.test.ts`

**Interfaces:**
- Consumes: 无（独立于后端 Java）。
- Produces: `buildBackendEnv()`（或现有导出名，按文件实际）返回对象仅含 MySQL/datasource、`SPRING_SQL_INIT_MODE`、storage、`COOKIE_SECURE`、`SERVER_SERVLET_SESSION_COOKIE_SECURE`，无 Redis 键。

- [ ] **Step 1: 先改测试（RED）——`infrastructure.test.ts` 去 Redis 期望**

- `describe` 标题（约第 4 行）改为 `'real MySQL E2E isolation'`。
- Test 1（组装覆盖）：标题去掉 “Redis database”；删除输入 `REDIS_DATABASE: '0'`（约第 12 行）；删除断言 `SPRING_DATA_REDIS_DATABASE`（约第 21 行）与 `SPRING_SESSION_REDIS_NAMESPACE`（约第 22 行）。
- Test 3（非法值拒绝）：删除用例 `{ TEST_REDIS_PORT: '65536' }`（约第 35 行）与 `{ TEST_REDIS_HOST: 'redis://production' }`（约第 36 行）。
- Test 4（暴露键名集合）：删除三个 `SPRING_DATA_REDIS_*` 期望名（约第 54-56 行）。
- **删除 Test 5** `'uses a fresh Redis namespace on every run'`（约第 63-67 行）整块。

- [ ] **Step 2: 运行测试确认失败（RED）**

Run:
```bash
cd frontend && npx vitest run testing/infrastructure.test.ts
```
Expected: FAIL——现有 `infrastructure.ts` 仍产出 Redis 键，新期望（不含 Redis）与之不符。

- [ ] **Step 3: 改实现——`infrastructure.ts` 去 Redis**

- 删除 `import { randomUUID } from 'node:crypto';`（第 1 行；namespace 随机化已随 Test 5 移除，不再需要）。
- `overrides` 常量（约第 19-21 行）删除 `SPRING_DATA_REDIS_URL` / `SPRING_DATA_REDIS_CLUSTER_NODES` / `SPRING_DATA_REDIS_SENTINEL_MASTER`，保留其余 8 个 JVM/Spring-config/profile/JNDI 项。
- 删除 `const redisHost = ...`（约第 29 行）；把 `if (![host, redisHost].every(...))`（约第 30 行）改为 `if (![host].every(...))`。
- 删除 `redisPort` / `redisPassword` / `redisUsername` / `namespace` 局部变量（约第 51-54 行）。
- 返回对象（约第 70-83 行）删除全部 `REDIS_*`、`SPRING_DATA_REDIS_*`、`SPRING_SESSION_REDIS_NAMESPACE` 键；保留 MySQL/datasource、`SPRING_SQL_INIT_MODE`、storage、`COOKIE_SECURE`、`SERVER_SERVLET_SESSION_COOKIE_SECURE`。

- [ ] **Step 4: 运行测试确认通过（GREEN）**

Run:
```bash
cd frontend && npx vitest run testing/infrastructure.test.ts
```
Expected: PASS，全部用例通过。

- [ ] **Step 5: 提交**

```bash
git add frontend/testing/infrastructure.ts frontend/testing/infrastructure.test.ts
git commit -m "$(printf 'test: drop Redis wiring from E2E infrastructure\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

### Task 26: 文档去 Redis + 重命名 `mysql-redis-setup.md` → `mysql-setup.md`（含 schema.sql 注释与全仓复核）

本任务把面向读者的散文改为“会话内存化、MySQL 唯一外部存储”，重命名安装文档并修正所有内部链接，最后做**全仓 Redis 残留复核**（白名单：`docs/superpowers/**` 计划/规格本身、以及 `ApplicationConfigurationTest.java` 的负向断言，二者按设计保留 redis 字样）。

**Files:**
- Rename: `docs/mysql-redis-setup.md` → `docs/mysql-setup.md`（并删其 Redis 内容）
- Modify: `README.md`、`docs/architecture.md`、`docs/backend-development.md`、`docs/testing.md`（散文去 Redis、更新安装链接）
- Modify: `deploy/mysql/schema.sql`（第 58 行注释去 Redis；与 Task 3 的加列改动互不重叠）

**Interfaces:**
- Consumes: Task 23-25 已改脚本/CI/前端；文档措辞与之一致（脚本不再要 `-RedisPassword`、CI 无 redis service）。
- Produces: 全仓除白名单外无 `redis` 字样；所有安装链接指向 `docs/mysql-setup.md`。

- [ ] **Step 1: `git mv` 重命名安装文档**

```bash
git mv docs/mysql-redis-setup.md docs/mysql-setup.md
```

- [ ] **Step 2: `docs/mysql-setup.md` 删 Redis 内容**

- 标题（第 1 行）`# MySQL / Redis 操作步骤` 改为 `# MySQL 操作步骤`。
- **删除整节 §4「由你配置 Redis」**（约第 111-155 行，含 `redis.conf` 说明与 `docs compose.redis.yml` 用法）。
- 状态自检段（约第 5、10、12、14 行）、配置位置段（约第 20、23 行）：删去 Redis 条目与并列措辞。
- `Prepare-LocalEnvironment.ps1` 调用示例（约第 30-52 行）：删除 `-RedisPassword $redisPassword`（及为其准备 `$redisPassword` 的行）、表格中 Redis 行（约第 46、48 行）与相邻 Redis 说明（约第 50-52 行）。
- 收尾（约第 173、177、181、184 行）：`/api/ready` 的「MySQL / Redis」改为「MySQL」；会话行、备份注记改述为「会话在 Servlet 容器内存中，进程重启即失效；MySQL 为唯一需备份的外部存储」。

- [ ] **Step 3: `README.md` 去 Redis**

- 删除专述 Redis + Spring Session 的行（约第 35 行）。
- 约第 38 行「真实 MySQL + Redis」改为「真实 MySQL」，链接 `docs/mysql-redis-setup.md` 改为 `docs/mysql-setup.md`。
- 约第 46、49 行：application.yml 「管理 Redis」与 `.conf` 行删除/改述为会话内存化。
- 安装链接与并列词（约第 55、62、65、88、99 行）：`docs/mysql-redis-setup.md`→`docs/mysql-setup.md`，「MySQL、Redis」→「MySQL」。
- 约第 131 行 Redis DB 15/14 说明删除。
- 存储职责表（约第 143 行）「管理员登录会话 / CSRF ｜ Redis」改为「管理员登录会话 / CSRF ｜ Servlet 容器内存会话」。
- 约第 147 行 Redis AOF 备份注记删除。
- 约第 157 行「Redis 共享登录会话…」多实例说明改为「会话在单进程内存中，多实例/重启需重新登录」。

- [ ] **Step 4: `docs/architecture.md` 去 Redis**

- 约第 7 行「MySQL + Redis + 私有磁盘」改为「MySQL + 私有磁盘」。
- 约第 16 行架构图「Spring Session → Redis 会话/CSRF」改为「Servlet 容器 HttpSession 会话/CSRF」。
- 约第 108 行安装链接 `docs/mysql-redis-setup.md`→`docs/mysql-setup.md`。
- 约第 112-114 行会话要点改写为「HttpSession 存于 Tomcat 进程内存；CSRF 令牌随会话（`HttpSessionCsrfTokenRepository`）」。
- 约第 125 行 `/api/ready`「MySQL / Redis」改为「MySQL」。
- 约第 159 行会话段落改写为内存会话语义（重启/多实例即失效、需重新登录）。

- [ ] **Step 5: `docs/backend-development.md` 去 Redis**

- 约第 51 行多实例注记改述为「单进程内存会话」。
- 约第 72 行 `mvn verify` 注释「真实 MySQL/Redis」改为「真实 MySQL」。
- 约第 88 行「必须配置 MySQL 和 Redis」改为「必须配置 MySQL」。
- 约第 90 行集成测试描述「Redis DB 15…」整句删除。
- 约第 94 行安装链接 `docs/mysql-redis-setup.md`→`docs/mysql-setup.md`。

- [ ] **Step 6: `docs/testing.md` 去 Redis**

- 约第 3 行标题「只使用真实 MySQL / Redis」改为「只使用真实 MySQL」。
- 约第 5 行安装链接改 `docs/mysql-setup.md`。
- 约第 27 行 Redis 提及删除。
- 隔离表（约第 31-45 行）删除 Redis 列与 Redis 相关条目（约第 37、39、40、43、45 行）。
- 约第 77 行 `ApplicationConfigurationTest` 描述：改为「断言 `server.servlet.session.timeout` 且不出现 `spring.data.redis.*`」（与 Task 1 后的实际断言一致）。
- 约第 85 行 `IntegrationSettingsTest` 描述去 Redis。
- 约第 89-94 行小节「真 MySQL / Redis 集成」改为「真 MySQL 集成」，删除 `RedisSessionIT` 条目（约第 94 行）。
- 约第 98、100 行 `Test-Scripts` / Vitest 描述去 Redis。
- 约第 106 行「MySQL 8.4、Redis 7.4」改为「MySQL 8.4」。
- 约第 116-119 行 Redis 3.2 发现与恢复空白条目删除/改述为仅 MySQL。

- [ ] **Step 7: `deploy/mysql/schema.sql` 注释去 Redis**

第 58 行注释 `-- Notes / reading impressions are persisted here, not in Redis.` 改为 `-- Notes / reading impressions are persisted here in MySQL.`（与 Task 3 在本文件的加列改动位于不同区域，互不冲突）。

- [ ] **Step 8: 全仓复核 + 文档格式校验**

Run:
```bash
grep -rin "redis" . --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target --exclude-dir=dist
```
Expected: 命中项**仅限**白名单——`docs/superpowers/**`（本计划与设计/规格文档本身论述去 Redis）与 `backend/src/test/java/io/github/wanhkjd/cloudnovel/core/config/ApplicationConfigurationTest.java`（Task 1 保留的负向断言，如 `assertThat(environment.getProperty("spring.data.redis.host")).isNull();`）。若出现其它文件，回到对应 Step 补删。

Run:
```bash
cd frontend && npx prettier --check ../README.md ../docs/architecture.md ../docs/backend-development.md ../docs/testing.md ../docs/mysql-setup.md
```
Expected: 全部通过。

Run:
```bash
git status --short docs/mysql-redis-setup.md docs/mysql-setup.md
```
Expected: `R  docs/mysql-redis-setup.md -> docs/mysql-setup.md`（重命名被识别为 rename）。

- [ ] **Step 9: 提交**

```bash
git add -A docs README.md deploy/mysql/schema.sql
git commit -m "$(printf 'docs: describe in-memory sessions and rename setup guide\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```

## Phase 7 — 全量验证与验收

### Task 27: 端到端验证（后端 `mvn verify` + 前端 test/build/e2e + `/api/ready` 验收）

前六个阶段每个任务都自带局部验证；本任务做一次跨栈完整回归，确认去 Redis、封面、时间轴日期与银河首页共同工作，且没有引入回归。**不含新代码**，只运行既有校验并核对验收标准；任何失败都在本任务内定位修复后重跑，全绿方可结束。

**Files:**
- 无新增/修改（纯验证）。失败时回到相应 Phase 的任务修复。

**Interfaces:**
- Consumes: 全部 Task 1-26 的产物。
- Produces: 一次全绿的跨栈验证记录，作为交付依据。

- [ ] **Step 1: 后端全量验证**

Run:
```bash
cd backend && mvn -B -ntp clean verify
```
Expected: PASS——spotless:check、单元测试（含 Task 1 的配置断言、Task 3-11 的封面/时间轴日期测试）、failsafe 集成测试（仅连接专用 MySQL `cloud_novel_test`，无 Redis）、javadoc（doclint all / failOnWarnings，新增公共类型 Javadoc 齐全）、archunit 分层规则全部通过。

- [ ] **Step 2: 前端单测**

Run:
```bash
cd frontend && npm test
```
Expected: PASS——有效日期排序（`timelineDate` 优先、回退 `createdAt`）、`CoverImage` 回退、reduced-motion 分支、揭示 Observer（mock）、E2E 基建（Task 25，无 Redis）等全部通过。

- [ ] **Step 3: 前端构建**

Run:
```bash
cd frontend && npm run build
```
Expected: 构建成功，无 TypeScript / vite 错误（`Book.timelineDate`、`hasCover` 等新类型编译通过）。

- [ ] **Step 4: 端到端（Playwright + axe）**

Run:
```bash
cd frontend && npm run test:e2e
```
Expected: PASS——银河首页可访问性无严重违规、键盘可达、reduced-motion 下无动画；管理员封面上传/移除流程；书籍按有效日期排列。（需已按 `docs/mysql-setup.md` 备好 `cloud_novel_e2e` 库与 `.env.test`。）

- [ ] **Step 5: `/api/ready` 验收**

启动本地后端（`pwsh ./scripts/Start-Local.ps1`）后：
```bash
curl -s http://127.0.0.1:8080/api/ready
```
Expected: 健康聚合仅体现 `db`（MySQL）——不含 `redis` 健康项；`show-details/show-components: never`，不泄露组件详情（HTTP 200 且响应体不含连接细节）。

- [ ] **Step 6: 人工验收清单（逐项确认）**

- [ ] 访问 `/`：动态星空 + 银河藤蔓可见；最早的书在底部，向上滚动逐本揭示（书名/作者/封面/日期/简介）。
- [ ] 有封面的书显示真实封面；`404`/无封面回退到 CSS 假封面（`CoverImage` `@error`）。
- [ ] 管理员编辑对话框可设 `timeline_date` 与上传/移除封面；空日期回退 `createdAt`。
- [ ] 开启系统 reduced-motion 后重载：无动画、星场静态、内容仍可读可达。
- [ ] 离开首页进入阅读/详情/后台：恢复暖纸浅色，深色样式未泄漏到全局。

- [ ] **Step 7: 收尾提交（如验证过程产生修复）**

若 Step 1-5 未触发任何修改，则无需提交；若有修复，按其所属 Phase 语义单独提交，例如：
```bash
git commit -am "$(printf 'fix: address cross-stack verification findings\n\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>')"
```
Expected: 工作区干净（`git status` 无未跟踪/未提交的计划外文件；临时验证产物已清理）。
















































