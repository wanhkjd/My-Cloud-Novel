# MVP 架构与接口

## 范围

这不是公共电子书聚合平台，也不是多用户网盘。站点只有一位主人；访客免登录访问经过明确公开的内容，并在各自浏览器保留自己的阅读记录。

```text
浏览器（Vue / TypeScript）
  ├─ 书架 / 详情 / 阅读器 / 阅读足迹 / 主人后台
  ├─ 访客 Journal → localStorage
  └─ 主人 Journal → 同源 /api
                       │
                 Spring Security
                 会话 + CSRF + ADMIN
                       │
       ┌───────────────┼────────────────┐
       Library         ReadingLog       Bookmarks
       导入/公开策略    位置/累计会话     段落感想/公开策略
          │                 │               │
          └──────── Spring JDBC / H2 ────────┘
          └─ 私有原件目录（UUID 文件名）
```

本地 Vite 将 /api 代理到 Java 服务；生产应由反向代理提供静态前端和同源 API，而不是启用宽泛的跨域许可。

## 模块边界

| 位置                              | 职责                                                      |
| --------------------------------- | --------------------------------------------------------- |
| backend/.../security              | 单管理员、会话登录/退出、CSRF、角色校验                   |
| backend/.../novel/TxtNovelParser  | 无数据库依赖的字节解码、章节/卷识别、字数统计             |
| backend/.../novel/Library         | 文件/数据库导入补偿、原件下载、书目与正文公开策略         |
| backend/.../reading/ReadingLog    | 验证位置、保存进度、幂等累计时长、历史/统计               |
| backend/.../reading/Bookmarks     | 书签增删改、同位置去重、主人/访客可见性                   |
| frontend/src/lib/api.ts           | 同源请求、CSRF 缓存、HTTP 错误处理                        |
| frontend/src/lib/journal.ts       | 统一接口区分服务器 Journal 和浏览器 Journal；不跨身份降级 |
| frontend/src/lib/reading-clock.ts | 使用单调时钟计算活跃阅读时间，可独立测试                  |
| frontend/src/views/ReaderView.vue | 按章阅读、串行保存队列、书签和位置恢复、计时事件          |

不把数据库行格式、磁盘路径或公开判断分散到前端页面中。所有对正文的访问必须经过 Library 的可读性检查。前端隐藏按钮不承担安全责任。

## 持久化

- `books`：元数据、编码、SHA-256、章/卷/字数、前言、两种公开标志。
- `chapters`：以 (book_id, chapter_index) 唯一标识章节，保存卷名、标题和正文。
- `reading_progress`：每本书一份主人阅读位置。
- `reading_sessions`：累计会话秒数、开始时刻、最新章节/段落和北京时间日期。
- `bookmarks`：主人感想与段落位置；(book_id, chapter_index, paragraph_index) 唯一。

数据库外键在删书时级联清理相关行；应用随后删除该书私有原件。文件与数据库不是分布式事务：导入数据库失败时会尝试删除已写文件；异常关机或文件权限问题可能留下孤立原件，应结合日志人工维护。

初始 SQL 使用 CREATE TABLE IF NOT EXISTS，**不负责升级已存在的表**。后续改表需要显式迁移方案。当前仅验收了 H2、单实例运行。

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
