# MVP 架构与接口

## 范围

这不是公共电子书聚合平台，也不是多用户网盘。站点只有一位主人；访客免登录访问经过明确公开的内容，并在各自浏览器保留自己的阅读记录。

后端采用 **Controller → Service 接口 / 实现 → MyBatis Mapper / XML** 三层架构。当前存储固定为 MySQL + 私有磁盘。HTTP 路径与 JSON 保持兼容，但数据库切换不是自动迁移：旧数据只归档保留，不能把换连接地址当作已完成历史数据迁移。

```text
浏览器（Vue / TypeScript）
  ├─ 书架 / 详情 / 阅读器 / 阅读足迹 / 主人后台
  ├─ 访客 Journal → localStorage
  └─ 主人 Journal → 同源 /api
                       │
                 Spring Security
          Servlet 容器 HttpSession → 会话 / CSRF
                       ADMIN
                       │
     Controller：接收 DTO、身份判断、返回 VO / HTTP
                       │
     Service 接口：Library / Reading / Bookmark
                       │
     service.impl：业务规则、权限、事务、DTO/Entity/VO 转换
          ├─ core.parser / core.storage / Clock
          │    解码分章 / 私有原件 / 可测试时钟
          └─ dao.mapper 接口 + resources/mapper/*.xml
                       │
             MySQL（InnoDB / utf8mb4）
```

本地 Vite 将 /api 代理到 Java 服务；生产应由反向代理提供静态前端和同源 API，而不是启用宽泛的跨域许可。

## 后端目录与职责

Java 包根为 `io.github.wanhkjd.cloudnovel`。

参考 novel 项目的分组方式，但不复制多用户平台模块。只保留五个一级包，测试目录与生产包对应：

```text
io.github.wanhkjd.cloudnovel
├── CloudNovelApplication.java
├── controller          # HTTP 入口
├── service
│   └── impl            # 业务实现与事务
├── dao
│   ├── entity          # 数据库记录
│   └── mapper          # MyBatis 接口
├── dto
│   ├── req             # 请求模型
│   └── resp            # 响应 / 查询投影
└── core
    ├── config          # Spring 配置
    ├── auth            # 当前身份
    ├── exception       # 业务异常与统一 HTTP 转换
    ├── parser          # TXT 解析
    └── storage         # 私有原件存储
```

| 包 / 目录                             | 职责与约束                                                                                           |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| controller                            | Library、Reading、Bookmark 入口只依赖 Service 接口；Auth 查询身份，Health 仅检查进程，不混入业务逻辑 |
| service                               | 三个业务接口，声明能力、权限和参数约束；公开方法有中文 JavaDoc                                       |
| service/impl                          | 统一隐私、位置、幂等及事务规则；不依赖 Servlet、Controller、HTTP 或 JDBC                             |
| dao/entity                            | 与表对应的不可变 record，例如 BookEntity；不直接返回客户端                                           |
| dao/mapper                            | Book、Chapter、Reading、Bookmark 的 MyBatis 接口；使用 @Mapper，不接收 Web 请求 DTO                  |
| dto/req                               | 带 Bean Validation 的请求 record；不能当数据库实体使用                                               |
| dto/resp                              | 面向客户端的响应与只读查询投影；不泄漏私有路径、SHA-256 等内部字段                                   |
| core/config                           | SecurityConfig 与 TimeConfig；集中 Spring 配置和可测试时钟，不再手写重复的 Session Cookie Bean       |
| core/auth                             | CurrentUser：把安全上下文转为主人身份；业务公开权限仍由 Service 判断                                 |
| core/exception                        | BusinessException 不依赖 HTTP；ApiExceptionHandler 负责统一 HTTP 错误转换                            |
| core/parser                           | TxtNovelParser：独立的编码识别、分章 / 分卷与字数统计，无数据库依赖                                  |
| core/storage                          | NovelFileStorage 与 LocalNovelFileStorage：私有原字节、UUID 路径校验、禁止覆盖                       |
| src/main/resources/mapper             | 四个 Mapper XML，namespace 对应 dao.mapper，实体 / 投影对应 dao.entity / dto.resp                    |
| src/main/resources/application.yml    | 唯一 Spring Boot 运行配置，凭据由环境变量提供                                                        |
| deploy/mysql/schema.sql（项目根目录） | 手工初始化 MySQL 的部署脚本，不打进 JAR，不由应用执行                                                |

SQL 查询可返回 Entity 或明确的只读 VO 投影，不能接收 Web 请求 DTO。所有值使用 MyBatis 参数绑定，不能把用户输入拼接成 SQL。列表和目录查询不加载完整前言/正文，章节按需读取。构造器注入依赖，不使用字段注入的生产代码。

## 前端边界（本次保持兼容）

| 位置                              | 职责                                                            |
| --------------------------------- | --------------------------------------------------------------- |
| frontend/src/lib/api.ts           | 同源请求、CSRF 缓存、HTTP 错误处理                              |
| frontend/src/lib/journal.ts       | 用统一接口区分服务器 Journal 与浏览器 Journal，不跨身份静默降级 |
| frontend/src/lib/reading-clock.ts | 用单调时钟计算活跃阅读时间，可独立测试                          |
| frontend/src/views/ReaderView.vue | 按章阅读、串行保存队列、书签与位置恢复、计时事件                |

不把数据库行格式、磁盘路径或公开判断分散到前端页面中。所有对正文、前言、下载、公开感想的访问都先经过 LibraryService 可读性检查。前端隐藏按钮不承担安全责任。

## 持久化与事务

- `books`：元数据、编码、SHA-256、章/卷/字数、前言、两种公开标志。
- `chapters`：以 (book_id, chapter_index) 唯一标识章节，保存卷名、标题与 LONGTEXT 正文，一章一条记录。
- `reading_progress`：每本书一份主人阅读位置。
- `reading_sessions`：累计会话秒数、开始时刻、最新章节/段落和北京时间日期。
- `bookmarks`：主人感想与段落位置；(book_id, chapter_index, paragraph_index) 唯一。

事务由 Service 控制，不由 Controller 或 Mapper 自行提交：

1. **导入**：解析并计算指纹 → 以新 UUID 写私有原件 → 一个数据库事务写书目和全部章节（每批最多 200 章）→ 提交成功才返回。数据库写入或提交失败后补偿删除本次新文件；原件创建失败时不会误删已有文件。
2. **删除**：数据库先提交，外键级联删除章节、进度、会话与书签，再清理原件。回滚时不能提前删原件；文件清理失败记日志，不把已提交的数据库操作伪装成回滚。
3. **进度 / 时长**：单实例同步锁覆盖 TransactionTemplate 执行与 commit，避免同时首次写入相同主键；累计值只能增加，XML 更新条件也禁止回退。
4. **书签 / 编辑书目**：写入和返回查询在同一事务内，保留唯一约束；重复位置映射为业务冲突。
5. **统计**：只读事务；使用注入的 Clock 和 Asia/Shanghai 日期，补齐近 14 天的零值日期。

文件系统与数据库不是分布式事务。异常关机、磁盘权限问题或提交结果不确定时仍可能产生孤立文件 / 缺失原件，需要结合备份和日志人工核对；不要自动清理未知文件。内存同步锁不支持多实例部署，也不解决多设备同时阅读造成的时间重叠。

所有业务表使用 MySQL InnoDB / utf8mb4；以外键、唯一约束和 CHECK 保证基本一致性。`deploy/mysql/schema.sql` 使用 CREATE TABLE IF NOT EXISTS，**只供你手工初始化新库，不负责升级已存在的表**。应用固定 `spring.sql.init.mode=never`，运行账户只需 SELECT / INSERT / UPDATE / DELETE。没有 H2 依赖或测试回退。步骤见 [MySQL 环境说明](mysql-setup.md)。

## 登录会话与原件边界

- Servlet 容器（Tomcat）HttpSession 保存 HTTP 会话（安全上下文 / CSRF），空闲超时 12 小时；CSRF 令牌随会话（`HttpSessionCsrfTokenRepository`）。Cookie 为 `CLOUDNOVEL_SESSION`，HttpOnly、SameSite=Lax；公网 HTTPS 必须开启 Secure。登录轮换会话 ID，退出使会话失效。
- 会话只存于 Tomcat 进程内存，不保存书籍、阅读进度、书签或时长的唯一副本。进程重启或多实例部署时会话即失效，需重新登录；MySQL 数据不会因此丢失。
- 当前并未引入章节缓存、分布式锁或多实例阅读协调；会话存于单进程内存，单实例事务同步锁仍是明确的部署边界。
- 原始 TXT 使用 `LocalNovelFileStorage`，默认从 backend 启动时写入 `data/books/<UUID>.txt`，不丢失原编码字节，不放进前端 public / static。
- `NovelFileStorage` 是原件读写 / 删除的适配接口。独立扩容时才新增 OSS / COS / S3 实现，并补充私有桶、权限下载、失败补偿与契约测试；当前没有任何云端桶或上传行为。

## HTTP API

章与段落索引从 **0** 开始，毫秒时间戳为 Unix epoch。响应中的 canRead 是当前调用身份的可读性结果，不是额外的公开开关。

| 方法 / 路径                           | 说明                                                                 |
| ------------------------------------- | -------------------------------------------------------------------- |
| GET /api/health                       | 进程存活状态                                                         |
| GET /api/ready                        | MySQL 就绪状态，不公开连接详情                                       |
| GET /api/config                       | 公开上传约束：单文件字节上限，随 multipart 配置变化，供前端预检与提示 |
| GET /api/auth/csrf                    | CSRF token 与 headerName                                             |
| GET /api/auth/me                      | 当前会话是否为主人                                                   |
| POST /api/auth/login                  | form-urlencoded 的 username / password，需 CSRF                      |
| POST /api/auth/logout                 | 退出并失效会话，需 CSRF                                              |
| GET /api/books                        | 主人看到全部，访客仅看到公开书目；列表不包含前言                     |
| POST /api/books                       | 主人 multipart 上传，字段 file；成功创建私有草稿                     |
| GET /api/books/{id}                   | 当前身份可见的详情；正文私有时访客拿不到前言                         |
| PATCH /api/books/{id}                 | 主人更新 title、author、description、catalogPublished、textPublished |
| DELETE /api/books/{id}                | 主人删除书籍及附属记录                                               |
| GET /api/books/{id}/chapters          | 可读者获取章节目录                                                   |
| GET /api/books/{id}/chapters/{index}  | 可读者获取单章段落                                                   |
| GET /api/books/{id}/download          | 可读者下载原始 TXT，不由静态目录直接分发                             |
| GET /api/books/{id}/bookmarks         | 仅公开正文关联的公开感想                                             |
| GET /api/me/progress                  | 主人的全部最近阅读位置                                               |
| GET / PUT /api/me/progress/{bookId}   | 主人获取/保存 chapterIndex、paragraphIndex                           |
| PUT /api/me/sessions/{UUID}           | 主人累计上报 bookId、位置、startedAt、elapsedSeconds                 |
| GET /api/me/history                   | 主人最近 200 段历史                                                  |
| GET /api/me/stats                     | 主人全部时长、今日、14 天趋势、按书汇总                              |
| GET / POST /api/me/bookmarks          | 主人列出/新增书签                                                    |
| PATCH / DELETE /api/me/bookmarks/{id} | 主人编辑/删除书签                                                    |

除公开 GET、登录、退出外，修改接口需要 ADMIN；所有修改请求还需同会话的 CSRF token。登录成功后 token 会轮换，客户端必须重新获取。不存在的进度当前以 HTTP 200 空响应表示；前端请求层将其处理为 null。

TXT 上传体积上限由 `spring.servlet.multipart` 承载，并引用外部变量 `cloudnovel.upload.max-file-size` / `max-request-size`（缺省 30MB / 31MB；Spring 按 1024 进制解析，故 30MB 即 30 MiB），可在 `application-dev.yml` 灵活覆盖。运行期该上限经 `GET /api/config` 下发，前端据此渲染“最大 N MiB”提示并做上传前预检；真正超限的上传仍由后端返回 413（文案随配置的上限自动变化）。

主要错误：400 参数/格式错误，401 未登录或错误凭据，403 角色/CSRF 不满足，404 不存在或不可见，409 重复文件/会话冲突。错误响应不包含服务器堆栈。

## 一次阅读的生命周期

1. 读取书目和目录，再请求目标章节；只把当前章正文渲染成文本，不执行小说中的 HTML。
2. 用 URL 段落参数或保存的进度恢复位置；书签同样使用章节/段落坐标。
3. 浏览器处于前台、聚焦、没有暂停/模态弹窗，且最近两分钟有交互时计时。睡眠产生的长间隔被舍弃。
4. 每 15 秒串行保存位置与会话，切章/离开时再次保存。会话按 UUID 和累计秒数重试，服务器不会重复增加旧请求中的秒数。
5. 保存失败给出提示；页面仍打开时可以重试。未实现持久化离线 outbox，多标签/多设备间也不做同步锁。

主人会话空闲超过 12 小时或主动退出后需要重新登录；会话存于 Tomcat 进程内存，应用重启即失效、需重新登录。修改管理员密码不会自动撤销已有会话，必要时应另行安排会话失效；不会因此删除 MySQL 中的书籍和记录。Cookie 名称、路径和安全属性统一由 application.yml 配置，Servlet 容器负责创建和清除。运行配置与部署注意事项以根 README 为准。
