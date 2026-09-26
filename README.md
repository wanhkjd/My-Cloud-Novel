# 云上书房 · My Cloud Novel

一个像个人博客一样开放的云书库：**访客可以浏览主人公开的书目与正文，只有主人能管理藏书**。用 Java 后端与独立 Vue 前端，记录小说、阅读位置、阅读时间以及读书时的感想。

当前版本是可在本机运行的 MVP，**尚未部署到公网**。新上传的内容默认私有，不会因为上传成功而自动向访客开放。

## 第一版已有功能

| 功能           | 范围                                                                                   |
| -------------- | -------------------------------------------------------------------------------------- |
| 小说书库       | 书名/作者搜索、书籍详情、文字书封、最近阅读入口、真实空状态                            |
| TXT 上传与解析 | UTF-8 / UTF-8 BOM / GB18030（兼容常见 GBK）、章节与分卷识别、重复文件检测，最大 30 MiB（可配置） |
| 在线阅读       | 按章加载、分页/搜索目录、上一章/下一章、段落定位、字号、纸色/夜间模式、手机布局        |
| 阅读足迹       | 自动保存进度、继续阅读、最近 200 段阅读历史                                            |
| 时间统计       | 累计/今日时长、近 14 天图表、按书统计；以北京时间归属日期                              |
| 书签与感想     | 段落级书签、备注、编辑/删除；主人的感想可单独设置公开                                  |
| 后台管理       | 单管理员登录、上传、编辑书名/作者/简介、公开范围、删除藏书                             |

不做访客注册、社区评论、自动抓取、EPUB/PDF、OCR、离线全文缓存或多用户账户系统。

## 权限与隐私

- **书目公开**：访客能看到书名、作者、简介等元数据；不代表能看正文。
- **正文公开**：必须先公开书目；允许访客阅读章节、前言和下载原始 TXT。仅在你有权传播时开启。
- **感想公开**：每条主人书签默认私密。勾选公开后，也只随已公开的正文对访客展示。
- **主人阅读记录**：保存在后端，登录后可在不同设备继续阅读。失败时会提示，不会静默写入访客记录。
- **访客阅读记录**：只在当前浏览器的 localStorage 中，不上传服务器，不与主人记录合并；清理浏览器数据会丢失。使用共享设备时注意清理。
- 关闭公开权限只能阻止新的访问，无法收回访客已经打开、缓存或下载的内容。

## 技术栈

- 后端：Java 21、Spring Boot 3.5、Spring Security、**MyBatis（Controller / Service / Mapper 三层）**、Bean Validation、Maven。
- 前端：Vue 3、TypeScript、Vue Router、Vite、Lucide 图标、原生 CSS。
- 持久化：**MySQL 8.0.16+ / 8.4（InnoDB、utf8mb4）** 保存业务数据；原始 TXT 保存在服务器私有目录。
- Servlet 容器（Tomcat）内存会话：保存管理员 HTTP 会话 / CSRF，12 小时空闲过期，进程重启即失效；不作为书籍、进度或感想的唯一存储。
- 测试与规范：JUnit / MockMvc、ArchUnit 分层检查、Spotless 格式检查、严格 JavaDoc 校验；Vitest / Vue Test Utils、Playwright Chromium + axe 无障碍检查。

**运行和集成测试都必须使用真实 MySQL；已移除 H2 依赖和配置，没有嵌入式回退。** 建库 / 建表 / 授权由你手工执行，应用不会自动初始化数据库。首次配置请先按 [MySQL 操作步骤](docs/mysql-setup.md) 完成环境准备。旧数据库只保留归档，切换配置不会自动迁移历史数据。

后端各层职责与接口见 [架构说明](docs/architecture.md)，编码、中文注释和新增功能流程见 [后端开发规范](docs/backend-development.md)。

## 目录与配置

后端按 `controller → service/impl → dao/mapper` 分层，数据库实体集中在 `dao/entity`，请求和响应分别在 `dto/req`、`dto/resp`；Spring 配置、身份、异常、解析和原件存储集中在 `core`。完整目录树见 [架构说明](docs/architecture.md#后端目录与职责)。前后端继续独立构建，不引入爬虫、支付、多用户账户或分库分表。

- [application.yml](backend/src/main/resources/application.yml)：**唯一 Spring Boot 运行配置**。统一管理 MySQL、会话 Cookie、上传限制和私有原件路径，不再保留 properties 或重复的环境 profile。
- `.env` / `.env.test`：仅供本机脚本把私有参数注入环境变量，不是另一套 Spring 配置；真实密码继续留在 Git 之外，不能写进 YAML。
- [deploy/mysql/schema.sql](deploy/mysql/schema.sql)：手工建表脚本，已移出运行资源目录，不随 JAR 自动执行；表结构没有因目录整理而改变。
- Maven POM 和 MyBatis Mapper XML 保持各自工具要求的格式，不强行改成 YAML。

启动与验收脚本会先执行 `mvn clean verify`，清除旧包的 class 和旧配置副本；不会清理 `data`、`backups` 或小说原件。

## 本机启动（Windows）

准备好 PATH 中的 **JDK 21、Maven 3.9+、Node.js 24 LTS 和 npm**，并完成 [MySQL 初始化](docs/mysql-setup.md)，把私有运行配置保存到根目录 `.env`。

```powershell
cd D:\javaweb\workspace\MyCloudNovel
.\scripts\Start-Local.ps1
```

启动脚本执行后端单元测试、格式/分层/JavaDoc 和打包（明确跳过专用库集成测试）、前端测试与构建，再隐藏启动本地进程。完整验收使用 `scripts/Test.ps1`。MySQL 和书目接口都就绪后才提示启动成功：

- 前端：[http://127.0.0.1:5173](http://127.0.0.1:5173)
- 依赖就绪检查：[http://127.0.0.1:8080/api/ready](http://127.0.0.1:8080/api/ready)（MySQL，无连接详情）
- 进程存活检查：[http://127.0.0.1:8080/api/health](http://127.0.0.1:8080/api/health)
- 管理入口：[http://127.0.0.1:5173/admin](http://127.0.0.1:5173/admin)

### 管理员账号

`Prepare-LocalEnvironment.ps1` 只生成待审核的私有配置，不会改库或覆盖现有 `.env`。它保留有效的现有管理员身份；没有时生成随机强密码，用户名默认 **admin**。启动脚本不再自动生成环境或使用默认数据库。密码只在本机配置中查看，不会打印到控制台。

也可以参考 `.env.example` 自行配置，但实际密码至少 12 个字符，不能保留示例中的空值。不要将真实配置、密码或会话 Cookie 提交到 Git。

```powershell
# 已构建且没有需要重新编译的修改时
.\scripts\Start-Local.ps1 -SkipBuild

# 停止由本工作区脚本启动的进程，不删除书库数据
.\scripts\Stop-Local.ps1
```

启动时若 8080 或 5173 已被占用，脚本会退出，**不会自动杀掉占用端口的进程**。进程记录在 `.local-dev.json`；日志在 `backend/data/local-*.log`。删除记录前请确认关联进程已经停止。

开发时也可在两个终端分别运行后端和前端：

```powershell
# 终端 A：在项目根目录加载完整的 MySQL / 管理员配置
.\scripts\Import-LocalConfig.ps1 -Path .\.env
cd backend
mvn clean spring-boot:run

# 终端 B
cd frontend
npm.cmd ci
npm.cmd run dev
```

注意：**Java / Maven 本身不读取根目录 `.env`**；启动脚本会调用安全的配置加载器。直接启动时需先加载配置或设置环境变量，不能遗漏 MySQL 凭据。

## 如何开始使用

1. 从“主人入口”登录，在后台选择 TXT，点击“导入为私有草稿”。
2. 检查识别出的书名、作者、编码、卷数和章节数，用私有预览核对首章与末章。
3. 打开阅读器；点击段落旁的书签图标即可记录位置与感想。左右方向键可切换章节。
4. 在“阅读足迹”查看历史、时长和书签。主人与访客各自看到自己的记录。
5. 若只想分享书单，只勾选“向访客展示这本书的书目”。不要无意开启正文下载权限。

## 验证与测试

```powershell
# 先准备独立测试库和 .env.test；此命令运行真实 MySQL 集成测试
.\scripts\Test.ps1

# 首次运行浏览器测试前安装 Chromium
cd frontend
npx.cmd playwright install chromium
cd ..

# 包括桌面/手机端到端测试和 axe 检查
.\scripts\Test.ps1 -BrowserTests

# 可选：只在本机提供真实小说，文件不会被复制到仓库或推送
.\scripts\Test.ps1 -BrowserTests -NovelPath '.\《人道至尊》.txt'

# 尚未准备数据库时，仅检查单元测试、格式、JavaDoc、类型和打包
# 明确不等于完整验收，不会使用替代数据库
.\scripts\Test.ps1 -UnitOnly -NovelPath '.\《人道至尊》.txt'
```

测试只读取 `.env.test` / `TEST_*` / `E2E_*`，不读取真实 `.env`。后端集成测试固定使用 `cloud_novel_test`，浏览器固定使用 `cloud_novel_e2e` 与 **18080 / 15173** 端口；两个账户仅能读写各自专用库。普通测试只用原创文本，不需要真实小说。

详细命令、验收范围和局限见 [测试说明](docs/testing.md)。GitHub Actions 会在推送和 PR 时执行自动检查；以 Actions 实际结果为准。

## 数据保存与备份

| 内容                                | 当前存储                                                         |
| ----------------------------------- | ---------------------------------------------------------------- |
| 原始 TXT                            | `backend/data/books/<UUID>.txt`（`BOOK_STORAGE` 可指定私有目录） |
| 章节正文、目录                      | MySQL `chapters`，一章一条记录，正文 `LONGTEXT`                  |
| 书名、作者、简介、权限              | MySQL `books`                                                    |
| 主人阅读进度、书签 / 笔记、阅读时长 | MySQL `reading_progress` / `bookmarks` / `reading_sessions`      |
| 管理员登录会话 / CSRF               | Servlet 容器内存会话，失效不删除 MySQL 业务数据                  |

需要独立扩容时，可实现 `NovelFileStorage` 接口接入 OSS / COS / S3；**当前只实现私有磁盘，不包含云存储配置**。任何原件读取都经后端权限检查，不把磁盘目录挂成静态资源。

**停写后同时备份 MySQL 和私有原件目录**，并妥善保管配置。仅备份 TXT 不含阅读历史 / 感想；仅备份 MySQL 不能恢复原始下载。会话仅存于 Servlet 容器内存，进程重启即失效，无需备份。具体导出和核对方法见 [环境操作说明](docs/mysql-setup.md)。

根目录 TXT、数据目录、运行日志、截图与测试产物、.env、node_modules 和构建产物均被 Git 忽略。删除书籍会删除相关章节、阅读记录、书签和私有原件，请先备份。

## 已知边界与上线前要求

- 阅读时间是**活跃阅读估算**，不是精确计费或防作弊数据。失焦/隐藏/暂停/打开弹窗/两分钟无交互不累计；超长采样间隔不补计休眠时间。一般每 15 秒保存，切章/离开时再保存。
- 断网、强制关闭浏览器或未完成的页面退出请求，仍可能丢失最后一小段记录；没有跨重启的离线同步队列。多设备同时阅读也不做冲突合并或计时去重。
- 会话按开始时刻的北京时间日期统计，每段最多 30 分钟；跨午夜的片段归属开始那一天。界面显示近 200 段历史，但总时长计算全部已保存的片段。
- TXT 章节标题需独占一行，且“章/回/节/卷”等单位后有空白/分隔符或行尾；特殊排版请先调整。无识别章节的文本会作为一章“正文”，空白文件、明显二进制文件和超过 10,000 章的文件拒绝导入。
- 未实现生产登录限流、自动备份、多实例协调或数据库版本迁移机制。当前使用单进程、手工新库建表 SQL；会话存于单进程内存，多实例 / 重启后需重新登录。
- 公网部署需用 HTTPS、设置 COOKIE_SECURE=true、配置同源反向代理与登录限流、保护数据目录并配置备份。**Vite 开发服务不能当生产服务器暴露到公网**；不要开放数据库控制台或原件目录。
- 生产前请进一步做安全审查、真实移动设备兼容性检查和容量测试；数据库字段变更需先备份再设计迁移。

模块边界和 API 索引见 [架构说明](docs/architecture.md)。

## 协作约定

遵守根目录 [AGENTS.md](AGENTS.md)：每次改动更新相关测试，验证通过后创建对应 Git commit，并推送到 GitHub。不要提交真实小说、个人数据或凭据。
