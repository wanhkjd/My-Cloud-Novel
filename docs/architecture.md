# MVP 架构与接口

## 范围

这不是公共电子书聚合平台，也不是多用户网盘。站点只有一位主人；访客免登录访问经过明确公开的内容，并在各自浏览器保留自己的阅读记录。

后端采用 **Controller → Service 接口 / 实现 → MyBatis Mapper / XML** 三层架构。此次重构保留原 HTTP 路径、JSON 字段和数据库表结构，不要求清空或重新导入已有书库。

```text
浏览器（Vue / TypeScript）
  ├─ 书架 / 详情 / 阅读器 / 阅读足迹 / 主人后台
  ├─ 访客 Journal → localStorage
  └─ 主人 Journal → 同源 /api
                       │
                 Spring Security
                 会话 + CSRF + ADMIN
                       │
     Controller：接收 DTO、身份判断、返回 VO / HTTP
                       │
     Service 接口：Library / Reading / Bookmark
                       │
     service.impl：业务规则、权限、事务、DTO/Entity/VO 转换
          ├─ parser / storage / Clock
          │    解码分章 / 私有原件 / 可测试时钟
          └─ Mapper 接口 + resources/mapper/*.xml
                       │
           默认 H2 / 可选 MySQL（需单独验收）
```

本地 Vite 将 /api 代理到 Java 服务；生产应由反向代理提供静态前端和同源 API，而不是启用宽泛的跨域许可。

## 后端目录与职责

Java 包根为 `io.github.wanhkjd.cloudnovel`。

| 包 / 目录         | 职责与约束                                                                                                                                       |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| controller        | LibraryController、ReadingController、BookmarkController：只做请求校验、身份判断、HTTP 映射，依赖 Service 接口；不写 SQL，不直接操作数据库或原件 |
| controller/advice | ApiExceptionHandler：把业务异常映射到状态码和统一的 ErrorView                                                                                    |
| service           | LibraryService、ReadingService、BookmarkService：声明业务能力和调用约束，公开方法有 JavaDoc                                                      |
| service/impl      | 三个 ServiceImpl：统一隐私、位置、幂等及事务规则；不依赖 Servlet、Controller、HTTP 或 JDBC                                                       |
| mapper            | BookMapper、ChapterMapper、ReadingMapper、BookmarkMapper：MyBatis 接口，用 @Mapper 注册；不承载业务规则                                          |
| resources/mapper  | 四个对应的 XML：参数绑定 SQL、唯一约束下的写入、列表/统计投影；SQL 不散落在 Controller/Service 中                                                |
| dto               | 带 Bean Validation 的请求 record，例如 BookEditRequest、PositionRequest；不能当数据库实体使用                                                    |
| entity            | 与表对应的不可变 record，例如 BookEntity；不直接序列化返回给客户端                                                                               |
| vo                | 面向客户端的响应与只读查询投影；BookView 不含文件路径、SHA-256 等内部字段                                                                        |
| parser            | TxtNovelParser：无数据库依赖的编码识别、章节/卷识别与字数统计                                                                                    |
| storage           | NovelFileStorage：私有 TXT 原件，UUID 路径校验、禁止覆盖、原字节读取                                                                             |
| security          | 单管理员配置与 CurrentUser 身份工具；公开内容的业务权限仍由 Service 检查                                                                         |
| exception         | BusinessException：表达“不存在 / 冲突”，不携带 HTTP 类型或底层错误详情                                                                           |
| config            | Clock Bean 等基础设施配置，业务可注入固定时钟测试                                                                                                |

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
- `chapters`：以 (book_id, chapter_index) 唯一标识章节，保存卷名、标题和正文。
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

初始 SQL 使用 CREATE TABLE IF NOT EXISTS，**不负责升级已存在的表**。后续改表需要显式迁移方案。本轮没有改表；默认仍为 H2、单实例。MySQL 切换说明见 [MySQL / Redis 环境说明](mysql-redis-setup.md)，开发规范见 [后端开发规范](backend-development.md)。

## HTTP API

章与段落索引从 **0** 开始，毫秒时间戳为 Unix epoch。响应中的 canRead 是当前调用身份的可读性结果，不是额外的公开开关。

| 方法 / 路径                           | 说明                                                                 |
| ------------------------------------- | -------------------------------------------------------------------- |
| GET /api/health                       | 健康状态                                                             |
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

主要错误：400 参数/格式错误，401 未登录或错误凭据，403 角色/CSRF 不满足，404 不存在或不可见，409 重复文件/会话冲突。错误响应不包含服务器堆栈。

## 一次阅读的生命周期

1. 读取书目和目录，再请求目标章节；只把当前章正文渲染成文本，不执行小说中的 HTML。
2. 用 URL 段落参数或保存的进度恢复位置；书签同样使用章节/段落坐标。
3. 浏览器处于前台、聚焦、没有暂停/模态弹窗，且最近两分钟有交互时计时。睡眠产生的长间隔被舍弃。
4. 每 15 秒串行保存位置与会话，切章/离开时再次保存。会话按 UUID 和累计秒数重试，服务器不会重复增加旧请求中的秒数。
5. 保存失败给出提示；页面仍打开时可以重试。未实现持久化离线 outbox，多标签/多设备间也不做同步锁。

主人会话存活 12 小时。服务器重启或密码修改后需要重新登录；不会丢失数据库中的书籍和记录。运行配置与部署注意事项以根 README 为准。
