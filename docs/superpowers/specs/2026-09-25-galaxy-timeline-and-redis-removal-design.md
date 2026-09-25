# 银河时间轴首页 + 去 Redis 设计（个人书籍博客）

- 日期：2026-09-25
- 状态：设计（已获会话内确认，待规格评审）
- 范围：后端去 Redis + 会话内存化；books 增自定义时间轴日期与封面；首页改为滚动驱动的银河时间轴；Redis 痕迹清理（文档/CI/脚本/示例）。

## 1. 背景与目标

项目重新定位为**个人书籍博客**（单管理员）。本轮两个主目标：

1. **彻底移除 Redis**：会话改用 Servlet 容器内存（Tomcat `HttpSession`），MySQL 成为唯一外部数据存储。Redis 目前只承载管理员会话/CSRF，不承载业务数据。
2. **银河时间轴首页**：动态星空背景 + 一条像藤蔓般向上生长的银河（时间线）；每本书按其日期落在时间节点上，最早的书在底部，向上滚动逐本揭示（书名、作者、封面、时间、简介）。

附带落地：真实封面上传/存储/展示；每本书可设自定义时间轴日期；一层交互动效（借鉴 OriginKit 交互种类，手写实现）。

**非目标**：不迁移旧数据（新库为空库）；不引入前端框架/UI 库/构建依赖；不做多实例会话共享；不在本轮完成 Phase-1 的 `.env`→`cloudnovel.*` profile 旧账（继续单独延后）。

## 2. 已确认决策

1. 会话存储：**内存**（Tomcat `HttpSession`），删除 Spring Session。
2. 封面：**上传真实图片**，存磁盘、DB 存标记；无封面回退现有 CSS 假封面。
3. 首页：**删除书架网格**，银河即时间轴，书卡在藤蔓两侧。
4. 渲染：**Canvas 2D 星空 + SVG 银河藤蔓 + DOM 书卡 + IntersectionObserver 揭示**。
5. 时间轴时间：新增每本书**可空自定义日期**，管理员可编辑；排序/标签用它，为空回退 `createdAt`。
6. Redis 痕迹：删 `deploy/compose.redis.yml` 与 Redis 文档，清理 CI/脚本/`.env` 示例；`.env`→profile 延后。
7. 主题：深色星空**仅首页**；阅读/详情/日志/后台保持暖纸浅色。
8. 交互动效：借鉴 [OriginKit](https://originkit.dev)（React 动画库）交互种类，用 Vue+CSS+Canvas 手写，尊重 `prefers-reduced-motion`，零新增前端依赖。

## 3. 架构总览

- 后端保持既有分层：`controller → service → dao(mapper/entity) + core(storage/config/...)`；本轮只在既有边界内新增“封面存储”组件与三个封面端点、并给 `books` 增两列。去 Redis 只动 `pom.xml` + `application*.yml` + 测试，无 Java 业务改动。
- 前端保持 Vue 3 + `<script setup>` + vue-router + `lib/api.ts` fetch 封装 + 全局 `style.css`。新增首页组件族与一个封面图组件；银河深色样式**作用域化**，不污染全局暖纸主题。

## 4. 详细设计

### A. 去 Redis（会话内存化）

- **A1 `backend/pom.xml`**：删除 `spring-boot-starter-data-redis`、`spring-session-data-redis`；保留 `spring-boot-starter-actuator`；更新第 79 行注释为“集成测试只连接专用 MySQL”。
- **A2 `application.yml`**：删除 `spring.data.redis` 整块与 `spring.session.redis`；把会话超时移到 `server.servlet.session.timeout: 12h`（删除 `spring.session` 整节）；`management` 注释改为“/api/ready 检查真实 MySQL”；**保留** `server.servlet.session.cookie`（`CLOUDNOVEL_SESSION`）与 `spring.servlet.multipart`。
- **A3 dev 配置**：`application-dev.yml`（git 忽略）与 `application-dev.yml.example` 删除 `cloudnovel.redis.*` 块（示例文件同步删占位）。
- **A4 Java**：无 import 依赖，业务代码不改。仅将 `SecurityConfig` 中“由 Spring Session 失效会话”的 Javadoc 改为“由 Servlet 容器失效会话”。
- **A5 `/api/ready`**：随依赖移除，Boot 自动去掉 `RedisHealthContributor`，健康项只剩 `db`（及默认项）；`show-details/components: never` 不变，不泄露详情。
- **A6 会话语义**：进程重启或多实例会导致会话丢失、需重新登录；MySQL 中的书籍/阅读数据不受影响。CSRF 令牌随会话（默认 `HttpSessionCsrfTokenRepository`），存于内存会话。
- **A7 测试**：删除 `core/auth/RedisSessionIT.java`；从 `support/DatabaseIntegrationTest`（去 `StringRedisTemplate` 注入与前后置命名空间清理）、`support/IntegrationSettings`、`support/TestInfrastructure`、`support/IntegrationSettingsTest`、`core/config/ApplicationConfigurationTest` 移除 Redis 断言/构建/拒绝列表；配置测试改断言 `server.servlet.session.timeout` 且不再出现 `spring.data.redis.*`（含 `connectionCredentialsHaveNoHardcodedDefaults` 的 redis 密码行）。

### B. 自定义时间轴日期

- **B1 `deploy/mysql/schema.sql`**：`books` 增 `timeline_date DATE NULL`（置于 `created_at` 后）。文件仍声明“不升级既有表”，另在文档给出手工迁移：`ALTER TABLE books ADD COLUMN timeline_date DATE NULL AFTER created_at;`。
- **B2 `BookEntity`**：增 `java.time.LocalDate timelineDate`（可空）。
- **B3 `BookMapper.xml`**：`findAll`/`findById` select 增 `timeline_date`；`insert` 增列与 `#{timelineDate}`（导入时传 `null`）；`updateMetadata` 增 `timeline_date = #{timelineDate}`。
- **B4 `BookEditRequest`**：增 `LocalDate timelineDate`（可空；Jackson 默认 ISO `yyyy-MM-dd` 解析；前端空串提交为 `null`）。
- **B5 `LibraryServiceImpl`**：`updateBook` 构造的新 `BookEntity` 带 `edit.timelineDate()`；`importNovel` 传 `null`；`toView` 带 `timelineDate`。
- **B6 `BookView`**：增 `LocalDate timelineDate`（序列化为 `"yyyy-MM-dd"` 或 `null`）。
- **B7 前端 `types.ts`**：`Book` 增 `timelineDate: string | null`。
- **B8 `AdminView`**：编辑对话框增 `<input type="date">` 绑定 `form.timelineDate`（`Object.assign(form, book)` 已带该字段；`reactive` 初值加 `timelineDate: ''`，保存前空串→`null`）。`save()` 仍整体 PATCH `form`。管理列表行可附带显示该日期。
- **B9 前端有效日期**：`effectiveTime(book) = book.timelineDate ? Date.parse(book.timelineDate) : book.createdAt`；时间轴按升序（旧→新）排列，见 D 节。

### C. 封面上传 / 存储 / 展示

- **C1 存储组件**：新增 `core/storage/CoverImageStorage` 接口 + `LocalCoverImageStorage` 实现，根目录 `${app.storage-directory}/covers/`，对象名 `{id}.{ext}`（`ext ∈ jpg/png/webp`）。只接受合法 UUID（复用现有路径校验思路，禁止穿越/符号链接）；`write` 为**覆盖式**（替换封面先删旧扩展名文件），`delete` 幂等，`read` 返回字节+内容类型。与 `NovelFileStorage` 并列：TXT 原件在存储根、封面在 `covers/` 子目录，互不干扰。
- **C2 DB**：`books` 增 `cover_path VARCHAR(255) NULL`，存对象键（如 `covers/{id}.jpg`），**绝不下发前端**。schema.sql 加列 + 文档给出 `ALTER TABLE books ADD COLUMN cover_path VARCHAR(255) NULL;`。
- **C3 Entity/Mapper**：`BookEntity` 增 `String coverPath`；`insert` 写 `null`；**封面不走 `updateMetadata`**（避免编辑元数据误清空封面），`BookMapper` 增独立 `updateCover(@Param("id") String, @Param("coverPath") String)`。
- **C4 `BookView`**：增 `boolean hasCover`（=`coverPath != null`）；不下发 `coverPath`。
- **C5 端点（`LibraryController` @ `/api/books`）**：
  - `POST /api/books/{id}/cover`（multipart `file`，管理员+CSRF）：服务端**按魔数嗅探**真实图片类型（jpeg/png/webp，**拒绝 svg/html**）、限 **≤2 MiB**；写存储并 `updateCover`；返回更新后的 `BookView`。
  - `DELETE /api/books/{id}/cover`（管理员）：删文件、`cover_path` 置空；`204`。
  - `GET /api/books/{id}/cover`（公开，可见性同 `getBook`：owner 或 `catalogPublished`；否则/无封面→`404`）：流式返回图片，`Content-Type` 按 ext，加 `X-Content-Type-Options: nosniff` 与短期 `Cache-Control`。`404` 触发前端回退到假封面。
  - `LibraryService` 增 `setCover(id, bytes, declaredType)` / `removeCover(id)` / `readCover(id, owner)` 方法。
- **C6 安全/体积**：`SecurityConfig` 无需改（`GET /api/**`→permitAll；非 GET `/api/**`→`hasRole(ADMIN)` 已覆盖封面写）。封面另在服务层限 2 MiB；沿用现有 25MB multipart 上限（TXT 用），不改。
- **C7 前端**：新增 `components/CoverImage.vue`——`<img :src="'/api/books/'+id+'/cover'">`，`@error` 回退渲染 `BookCover`（现有 CSS 假封面）；`hasCover=false` 时直接渲染假封面不发请求。`BookCover.vue` 保留为回退。`AdminView` 编辑对话框增封面区：当前封面预览、选图上传、移除。

### D. 首页银河时间轴

- **D1 路由**：`router.ts` 的 `/` 改指 `GalaxyView`（删除 `shelf` 路由与 `ShelfView.vue`），`meta` 增 `galaxy: true`；`AdminView` 里“查看书架”等入口指向 `/`。为让首页占满深色星空，`meta.galaxy` 用于在 `App` 布局上切换透明/深色页头页脚与去除暖纸底。
- **D2 组件树**：
  - `GalaxyView.vue`：容器。拉 `/api/books`（访客得公开、主人得全部），算有效日期并**升序**排序，管理布局、初始滚动定位与 `prefers-reduced-motion` 协调；空态/加载/错误（深色变体，复用 notice 语义）。
  - `StarfieldCanvas.vue`：全屏 `position: fixed` Canvas 2D 星空（多层视差、缓慢漂移、可选指针视差）；按 `devicePixelRatio` 自适应；`aria-hidden`；reduced-motion 时画静态星场、停 rAF。
  - `GalaxyVine.vue`：SVG 发光曲线（纵向贯穿）+ 每个时间节点光点；路径随滚动进度“生长”（`stroke-dashoffset` 绑定滚动）。
  - `TimelineBook.vue`：单节点——日期标签 + 书卡（`CoverImage`、书名、作者、简介、章节/字数、状态标签），点击进 `/books/:id`；沿藤蔓左右交替；进入视口时 `.revealed` 揭示。
  - 复用：`CoverImage`/`BookCover`、`format.ts`（新增日期格式化）、`auth`、`api`。
- **D3 布局与方向**：纵向长页；DOM 顺序最新在上、最早在下（`flex-direction: column-reverse` 或倒序渲染），最早书位于藤蔓根部（页面底部）。`GalaxyView` 挂载后将滚动**定位到底部**（reduced-motion 直接跳、无平滑；否则可轻微平滑），用户向上滚动沿藤蔓穿越时间、逐本揭示较新的书。（备选：顶部=最新、下滑回溯，实现成本相同；默认按用户“从底部开始、向上滑动”。）
- **D4 揭示**：单个 `IntersectionObserver`（阈值≈0.2）给入视口节点加 `.revealed`（CSS 过渡 opacity/transform），只触发一次；reduced-motion 时节点默认可见、无过渡；**无 JS/Observer 时全部可见**（渐进增强）。
- **D5 交互层（借鉴 OriginKit 交互种类，手写）**：星空指针视差；藤蔓随滚动生长、节点渐次点亮；书卡悬停轻微上浮/发光/封面视差倾斜（桌面 hover，触摸不启用倾斜）；日期/书名入场文字揭示；可选相邻节点“星座连线”。全部在 `@media (prefers-reduced-motion: reduce)` 下降级为静态。
- **D6 主题作用域**：银河深色样式仅作用于首页作用域根类（如 `body.galaxy-route` 或 `.galaxy-view`），不改全局 CSS tokens；离开首页恢复暖纸浅色；页头页脚在首页用深色/透明变体并保证对比度。
- **D7 可访问性**：星空 Canvas `aria-hidden`；时间轴用有序语义（`<ol>`）、书卡为可聚焦链接、键盘可达；对比 ≥ WCAG AA；尊重 reduced-motion；内容不依赖动画即可读。
- **D8 性能与清理**：Canvas 用 `requestAnimationFrame` + 星数按视口/DPR 上限；视差/滚动用被动事件并节流；组件卸载时取消 rAF、断开 Observer、移除监听。

### E. Redis 痕迹清理（文档 / CI / 脚本 / 示例）

- **E1** 删除 `deploy/compose.redis.yml`。
- **E2** `docs/mysql-redis-setup.md`：删 Redis 部分（含第 4 节“配置 Redis”、`redis.conf`/compose 步骤及散落提及），保留 MySQL，改名为 `docs/mysql-setup.md`，更新内部链接与 README 引用。
- **E3** 文档 `README.md`、`docs/backend-development.md`、`docs/testing.md`、`docs/architecture.md`：移除 Redis 描述，改述“会话内存化、MySQL 唯一存储”。
- **E4** CI `.github/workflows/ci.yml`：移除 Redis service 容器与相关 env；后端集成测试只保留 MySQL service。
- **E5** 脚本 `Prepare-LocalEnvironment.ps1`（不再生成 `redis.conf`/`REDIS_*`/redis 连接）、`Import-LocalConfig.ps1`、`Start-Local.ps1`、`Test.ps1`、`Test-Scripts.ps1`：移除 Redis。**最小化触碰**、不顺带做 `.env`→profile 迁移（见风险 6）。
- **E6** `.env.example`、`.env.test.example`：删 `REDIS_*`。
- **E7** 前端 `frontend/testing/infrastructure.ts` 及其测试：移除 Redis 依赖检查。

### F. 测试策略

- **后端**：先 `mvn spotless:apply`。更新配置/单元测试（无 Redis；`session.timeout` 位置）；新增封面服务/控制器测试（魔数类型校验、可见性、`404`、覆盖与删除）与 `timelineDate` 更新测试；集成测试只需 MySQL；archunit 分层规则不受影响（新增类落在既有分层）。`mvn verify` 走 spotless:check + failsafe + javadoc（doclint all / failOnWarnings）——新增公共类型需补全 Javadoc。
- **前端**：Vitest——有效日期排序（`timelineDate` 优先、回退 `createdAt`）、reduced-motion 分支、`CoverImage` 回退、揭示 Observer（mock）、组件渲染。Playwright + axe——新首页可访问性、键盘可达、无严重违规、reduced-motion 下无动画；管理员封面上传流程。
- **验收**：`/api/ready` 只含 `db`；启动后首页银河可见、逐本揭示、封面/回退正确。

## 5. 变更速查

- **DB `books`**：`+ timeline_date DATE NULL`、`+ cover_path VARCHAR(255) NULL`（schema.sql 建列；既有库手工 ALTER）。
- **新接口**：`POST` / `DELETE` / `GET /api/books/{id}/cover`。
- **`BookView`**：`+ timelineDate`、`+ hasCover`（不加 `coverPath`）。
- **`BookEditRequest`**：`+ timelineDate`。`BookMapper`：`+ updateCover`。
- **路由**：`/` → `GalaxyView`（删 `shelf`/`ShelfView.vue`）。
- **删依赖**：`spring-boot-starter-data-redis`、`spring-session-data-redis`。

## 6. 风险与权衡

1. **内存会话**：进程重启/多实例会话丢失、需重新登录（个人博客单实例，可接受）；业务数据在 MySQL 不丢。
2. **滚动方向**（底部起、上滑）非 Web 常规，需初始滚动定位；已给可切换备选。
3. **动效性能/晕动**：严格 reduced-motion 降级、星数上限、rAF 与监听清理。
4. **用户上传封面**：服务端魔数嗅探（拒 SVG/HTML）、`nosniff`、2 MiB 限；单管理员风险低。
5. **OriginKit 为 React 库**，不直接引入（Vue+纯 CSS 栈）；仅借鉴交互种类、手写实现、零新增前端依赖。
6. **`.env`→profile 旧账仍延后**：本轮脚本改动只去 Redis；若脚本与 `.env` 强绑定产生冲突，最小化触碰并在实施计划中标注，不扩大范围。
7. **schema.sql 不升级既有表**：既有库需手工 ALTER；本地新库可直接建表。

## 7. 交付顺序（供实施计划参考）

1. 去 Redis（pom/yml/dev/tests，验证 `/api/ready`）
2. DB 两列 + 后端封面&日期（entity/mapper/service/controller/dto + 测试）
3. 前端 `CoverImage`、`AdminView` 封面&日期
4. `GalaxyView` 及子组件 + 路由替换 + 深色作用域样式
5. 交互层与 reduced-motion 降级
6. 文档 / CI / 脚本 / 示例 清理
7. 全量验证（后端 `mvn verify`、前端 Vitest + Playwright/axe）
