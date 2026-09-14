# 测试与验收

## 完整验证：只使用真实 MySQL / Redis

先按 [环境步骤](mysql-redis-setup.md) 准备两个**专用空库**、最小权限账户和根目录 `.env.test`，再运行：

```powershell
cd D:\javaweb\workspace\MyCloudNovel
.\scripts\Test.ps1
.\scripts\Test.ps1 -BrowserTests
.\scripts\Test.ps1 -BrowserTests -NovelPath '.\《人道至尊》.txt'
```

浏览器首次安装：在 frontend 执行 `npx.cmd playwright install chromium`；Linux CI 使用 `npx playwright install --with-deps chromium`。端到端测试使用已打包的后端 JAR，因此修改 Java 后不能只运行旧 JAR 的浏览器测试。

每次完整构建都先执行 `clean`，避免被移动的旧 class、application.properties 或 schema.sql 留在 target 中；只删除构建产物，不清理业务目录。测试 Java 包跟随生产层级：controller、core、dao/mapper 和 service/impl，共用隔离夹具仍在 support。

测试脚本只加载 `.env.test` 的 `TEST_*` / `E2E_*` 白名单，**不读取运行用 `.env`**。真实小说只进入只读解析器验收，不会自动导入数据库、上传 GitHub 或在报告中打印正文。普通测试全部使用原创合成文本。

### 尚未准备基础设施时

```powershell
.\scripts\Test.ps1 -UnitOnly
.\scripts\Test.ps1 -UnitOnly -NovelPath '.\《人道至尊》.txt'
```

这是显式的部分验证：单元测试、脚本、格式、ArchUnit、JavaDoc、前端类型检查与构建。**不运行真实数据库 / Redis 集成或浏览器流程，不等于完整验收**；禁止与 `-BrowserTests` 组合。没有 H2 / 其他嵌入式替代；完整模式缺少专用凭据或服务时必须失败，而不是静默跳过。

## 测试隔离约定

| 范围       | MySQL schema / 账户                 | Redis                            | 原件目录                 |
| ---------- | ----------------------------------- | -------------------------------- | ------------------------ |
| 网站运行   | cloud_novel / cloud_novel_app       | DB 0，cloud-novel:session        | BOOK_STORAGE 私有目录    |
| 后端集成   | cloud_novel_test / cloud_novel_test | DB 15，本次 cloud-novel:it:UUID  | JVM 新建的临时目录       |
| 浏览器流程 | cloud_novel_e2e / cloud_novel_e2e   | DB 14，本次 cloud-novel:e2e:UUID | backend/target/e2e-books |

- 浏览器测试若检测到 JVM 启动参数、Spring JSON / 外部配置、JNDI 或 Redis URL / 集群覆盖，会在启动前拒绝。请清除这些继承配置后再运行，不能让高优先级配置绕过隔离连接。
- 测试固定 schema 与账户名，拒绝 root / 业务账户；不得把正式数据放进这两个测试库。两个测试账户各自仅有本库的 DML 权限。
- 后端集成测试在创建连接前拒绝继承的 Redis URL / 集群 / Sentinel / JNDI 配置，包括空 URL；空字符串不能表示禁用 Redis URL。
- `IntegrationSettings` 在创建连接池前覆盖运行配置；`e2eEnvironment` 明确覆盖数据库、Redis、管理员和原件路径，测试目标不能从真实 DB_URL 派生。
- 本地和 CI 的表结构由 DBA / 初始化步骤显式导入 `deploy/mysql/schema.sql`。所有运行 / 测试都使用 `spring.sql.init.mode=never`，不授予测试账户 CREATE / DROP。
- 后端清理前核对 `SELECT DATABASE()`，只删除专用库内合成书目及其外键关联记录；不会连接 / 清空真实业务库。
- 后端只用 SCAN / DELETE 清理**当前随机 Redis 前缀**。浏览器会话有 TTL；不使用 FLUSHDB / FLUSHALL，也不删除其他项目的会话。
- E2E 使用独立端口 **18080 / 15173**，不复用 8080 / 5173 的个人书库。每个用例开始前清理隔离书库，禁止多个测试进程并发共用同一个 schema。
- 测试连接参数面向本机或隔离 CI，不能把关闭 TLS 的测试配置用于公网 MySQL / Redis。

## 分别运行

```powershell
# 项目根目录：仅导入测试白名单，不导入业务配置
.\scripts\Import-LocalConfig.ps1 -Path .\.env.test -Mode Test

cd backend
mvn -B -ntp clean verify

# *Test 由 Surefire 跑单元测试，*IT 由 Failsafe 在 verify 跑真实集成
# 排查时的部分检查，不能替代完整 verify
mvn -B -ntp -Dtest=ReadingServiceTest test
mvn -B -ntp clean verify -DskipITs

cd ..\frontend
npm.cmd ci
npm.cmd run format:check
npm.cmd test
npm.cmd run build
npm.cmd run test:e2e
npm.cmd audit --registry=https://registry.npmjs.org --audit-level=moderate
```

`LocalNovelTest` 只针对用户提供的《人道至尊》版本，不是任意 TXT 的通用断言。CI 没有真实小说，该用例缺少 `NOVEL_TEST_FILE` 时允许跳过，其他单元 / 集成测试不得因此跳过。

## 自动覆盖

### 后端单元、架构与存储

- ArchitectureTest：固定包布局、一级包无循环依赖、Controller → Service → Mapper、DTO / Entity 隔离、集中配置与异常转换；禁止旧包和直接 JDBC 访问。
- ApplicationConfigurationTest：只有 application.yml，无旧 properties / 环境 profile / 运行 DDL；真实 YAML 的 MySQL、Redis、上传、私有存储和健康检查绑定，凭据无默认值；使用 Boot 自动配置验证 HTTP / HTTPS Cookie，无需数据库连接。
- MapperXmlTest：无数据库连接时解析全部 Mapper XML，校验新 namespace、实体 / 投影类型和每个 Mapper 方法都有对应 SQL。
- HealthControllerTest：独立存活检查控制器保持 `/api/health` 契约，不依赖认证或基础设施。
- LibraryServiceTest：默认私有、书目 / 正文权限隔离、参数边界、200 章批次、事务提交失败的文件补偿、数据库提交后再删原件。
- ReadingServiceTest：固定 Clock，进度、UUID 冲突、累计乱序 / 幂等、时间上下限、七天窗口 / 五秒容差、北京时间跨午夜、14 天补零。
- BookmarkServiceTest：权限优先、主人 / 访客过滤、感想规范化 / 长度、同位置冲突、编辑和不存在处理。
- NovelFileStorageTest：LocalNovelFileStorage 在临时目录内的原字节读写、禁止覆盖、UUID / 路径校验、缺失原件、重复删除和符号链接防护。
- TxtNovelParserTest：UTF-8 / BOM、GB18030、章节 / 卷识别、标题边界、无章节退化、空白 / 二进制 / 超限校验。
- IntegrationSettingsTest：不继承正式连接 / 原件目录，拒绝 root、缺凭据、连接参数注入、共享 Redis 前缀和外部连接覆盖；不连接服务器的 Redis 自动配置回归测试校验最终 host / port / database / 凭据绑定。
- LocalNovelTest：可选只读解析，核对编码、1,507 章 / 3 卷、连续索引与原件哈希不变。
- Spotless + JavaDoc：生产 / 测试统一格式，公开 API 的中文 JavaDoc 通过 doclint=all / failOnWarnings。

### 真 MySQL / Redis 集成

- MapperIT：验证服务端产品确为 MySQL、表引擎为 InnoDB、四字节 Unicode；运行真实 MyBatis XML，检查实体 / 投影映射、唯一约束、聚合、条件累计更新和外键级联。
- LibraryTransactionIT：真实 MySQL 事务中先写入 200 章，再模拟第二批失败，确认书目 / 已写章节回滚、新原件补偿删除。
- LibraryApiIT：MockMvc 配合真实 MySQL / Redis，覆盖上传 / 下载 / 删除、重复文件、错误格式、公开范围、前言泄露、感想、进度 / 历史 / 统计、非法位置及幂等。
- RedisSessionIT：真实随机端口 HTTP 与 Cookie，不使用 MockHttpSession 替代 Redis。检查 CSRF、错误登录、会话 ID 轮换、HttpOnly / SameSite / Path、12 小时 TTL、退出并清除正确 Cookie（不再生成旧 JSESSIONID）、独立客户端共享会话、删除或过期 Redis 会话立即撤销认证，且 MySQL 书籍 / 章节 / 原件仍存在；检查就绪状态不泄露连接详情。

### PowerShell / 前端 / 浏览器

- Test-Scripts.ps1：不依赖 Pester，不启动服务，不读取真实配置。验证全部脚本 AST、运行 / 测试白名单、JDBC 的 &、密码中的 =、空格路径、字面量不执行、环境恢复；用临时项目验证生成独立凭据、只保留管理员身份、不覆盖文件、最小 SQL 权限和已有 Redis 密码输入；检查唯一 YAML、部署 SQL 位置和 clean 构建流程。
- Prettier 检查 README、docs、前端、CI、Compose 和 application.yml 格式；vue-tsc / Vite 检查类型与生产构建。
- Vitest：阅读时钟、访客本地数据 / 管理员请求隔离、真实空状态、E2E 配置不接入正式库和随机 Redis 前缀。
- Playwright Chromium 三个流程：主人登录 / 上传 / 私有预览 / 书签 / 切章 / 恢复 / 公开 / 统计 / 删除；390 × 844 手机访客的搜索 / 夜间模式 / 本地书签 / 刷新恢复与权限拒绝；公开空书架及主要页面 / 弹窗的 axe WCAG A/AA 检查。
- 原创 E2E 文本带 HTML 注入探针，确认作为文本显示；主要流程监控 pageerror。axe 无违规不等于完整无障碍认证，仍需真人键盘 / 屏幕阅读器测试。

## GitHub CI

`.github/workflows/ci.yml` 在 push / PR 时启动可丢弃的 **MySQL 8.4、Redis 7.4** 服务。只使用明确标注的 CI 合成凭据，绝不连接本机数据库。root 仅在初始化步骤建专用库 / 表和最小权限用户；Maven / 应用 / 浏览器阶段只拿对应 DML 账户。

CI 执行脚本测试、`mvn clean verify`（不能带 skipITs）、前端依赖审计、格式 / 类型 / 单测 / 打包、完整 Playwright 流程。后端和浏览器报告作为 7 天保留的构建产物。通过状态以 [对应提交的 Actions](https://github.com/wanhkjd/My-Cloud-Novel/actions) 为准，不能把本机 UnitOnly 通过当作 CI 或完整验收通过。

## 本机记录与明确限制

2026-09-13 切换过程中：

- 已完成无基础设施的后端单元 / 架构 / 存储检查与《人道至尊》只读解析，原件为 **10,244,779 字节 / GB18030 / 1,507 章 / 3 卷 / 4,844,982 非空白正文码点**；未向新库导入小说。
- MySQL 8.0.43 仅做 root 只读连接 / 版本 / schema 查询；项目和专用测试库未创建，没有执行建库 / 授权或业务写入。
- 后续检测到 Redis 3.2.100 在本机运行，但未启用服务端密码；未擅自执行 CONFIG SET 或修改服务配置。真实本机集成、重启持久化和 Compose 启动仍需用户配置后验证。
- 旧版本的本机 MVP / 三层重构验收记录可从 Git 历史查看；那是旧存储版本的验证，**不能代替本轮真实 MySQL / Redis 验收**。旧数据库仅归档，网站已停机，阅读历史未自动迁移。

尚未覆盖公网部署、长期负载、Redis 故障恢复 / 自动高可用、多实例业务并发、Safari / Firefox 与真实移动设备矩阵、精确最后秒恢复或正式安全渗透测试。Redis Session 只负责会话共享，不解决阅读时长的跨实例 / 多设备冲突合并。
