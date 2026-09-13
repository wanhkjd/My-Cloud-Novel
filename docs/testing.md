# 测试与验收

## 一键验证（Windows）

在项目根目录运行：

```powershell
.\scripts\Test.ps1
.\scripts\Test.ps1 -BrowserTests
.\scripts\Test.ps1 -BrowserTests -NovelPath '.\《人道至尊》.txt'
```

浏览器首次安装：在 frontend 执行 `npx.cmd playwright install chromium`。Linux CI 使用 `npx playwright install --with-deps chromium`。

测试脚本不会读取 .env、不自动公开任何书籍。指定真实小说只启用解析器验收，不会把小说上传到 GitHub，也不在测试报告里打印其正文。

## 分别运行

```powershell
cd backend
mvn -B -ntp verify

# 可选：为本地解析器验收提供只读文件
$env:NOVEL_TEST_FILE = 'D:\javaweb\workspace\MyCloudNovel\《人道至尊》.txt'
mvn -q -ntp test
Remove-Item Env:NOVEL_TEST_FILE

cd ..\frontend
npm.cmd ci
npm.cmd run format:check
npm.cmd test
npm.cmd run build
npm.cmd run test:e2e
npm.cmd audit --registry=https://registry.npmjs.org --audit-level=moderate
```

端到端测试启动已打包的 backend/target/cloud-novel-0.1.0.jar，因此修改 Java 后要先运行 Maven package/verify。CI 不提供任何真实小说；LocalNovelTest 缺少环境变量时会跳过，其余测试必须通过。

## 自动覆盖

### 后端

- ArchitectureTest：Controller 不越过 Service 访问 Mapper/存储；Service 不依赖 Web/JDBC；Mapper 不依赖上层；接口、实现类与注解位置。
- MapperIntegrationTest：真实 MyBatis XML / H2，实体和投影映射、列表不加载正文、唯一约束、条件累计更新、聚合与外键级联。
- LibraryServiceTest：书目/正文隔离、200 章批次、默认私有、参数边界、提交失败后的文件补偿、先提交后删文件。
- LibraryTransactionTest：真实数据库事务中先成功写入 200 章，再模拟第二批失败，核对书目、首批章节和新原件均撤销。
- ReadingServiceTest：固定 Clock，进度保存、UUID 冲突、累计乱序、时长上下限、七天窗口/五秒容差、北京时间跨午夜、14 天补零。
- BookmarkServiceTest：正文权限优先、主人/访客过滤、感想规范化/长度、同位置冲突、编辑与不存在处理。
- NovelFileStorageTest：临时目录原字节读写、禁止覆盖、非法 UUID/路径穿越、重复删除、缺失原件。
- Spotless + JavaDoc：统一 Java 格式；doclint=all、failOnWarnings 校验公开 API 文档。
- TxtNovelParserTest：UTF-8/BOM、GB18030、章节/分卷、标题边界、无章节退化、空白/二进制/超限校验。
- LocalNovelTest：环境变量显式提供本地小说；核对 GB18030、1,507 章、3 卷、连续索引和首末章，原件哈希不变。**此用例针对用户提供的《人道至尊》测试版本，不是任意 TXT 的通用断言**。
- LibraryApiTest：真实上传/下载/编辑/删除、重复文件、错误格式、书目/正文/前言/目录隔离、公开感想、真正表单登录/CSRF/会话/退出、主人历史/统计、非法位置、累计秒数幂等与乱序、清理关联数据。

### 本地脚本与说明文档

- Test-Scripts.ps1：只使用合成 .env，验证 PowerShell AST、配置白名单、DB_INIT_MODE、JDBC URL 的 &、密码中的 =、空格路径、注释忽略和启动 verify 约定；自动恢复进程环境变量，不读真实 .env 或启动服务。
- Prettier：检查根 README 与 docs 下的 Markdown，和前端检查一起在 Test.ps1 / CI 执行。

### 前端

- ReadingClock：正常计时、隐藏/暂停、长睡眠与空闲截止。
- Journal：访客浏览器数据持久化、管理员请求路由、网络失败不污染访客记录。
- ShelfView：空书架不会伪造书籍或统计；书架搜索由浏览器测试覆盖。
- vue-tsc + Vite：TypeScript 和生产打包；Prettier 检查格式。

### 浏览器端到端

使用独立后端 **18080**（内存 H2、原件在 backend/target/e2e-books）与独立前端 **15173**。端口已占用会失败，不复用已有服务。测试专用密码只用于隔离环境，绝不能拿来部署真实站点。每个用例开始前清理该隔离书库，避免失败残留污染下一用例。

1. 主人真实 UI 登录、上传原创 TXT、私有预览、段落书签、切章、恢复位置、仅公开书目、再公开正文、单独公开感想、核对服务器统计和删除。
2. 390 × 844 手机访客搜索、阅读、夜间模式、本地书签、继续阅读、刷新恢复设置、无水平溢出、访问主人接口被拒绝且主人记录未混入访客数据。
3. 公开空书架的 axe WCAG A/AA 检查；其他流程还检查登录、管理、详情、阅读器日/夜模式、书签弹窗和阅读足迹。

原创测试文本含 HTML 注入探针，确认以文本显示、没有执行脚本；主要浏览器流程监听 pageerror。axe 无违规不等于完整无障碍认证，还需键盘/屏幕阅读器和真人检查。

## MVP 首次本机验收记录

在 2026-09-12 至 2026-09-13 本机开发环境中执行：

- 后端 **12 项**、前端 **6 项**、浏览器 **3 个完整流程**通过；类型检查、构建、格式检查通过。
- 升级测试工具到已修复版本后，全量 npm 依赖审计在执行时为 **0 漏洞**。

- 用户提供的《人道至尊》约 9.77 MiB，GB18030，**1,507 章 / 3 卷 / 4,844,982 个非空白正文码点**。
- 通过实际后台 UI 导入，核对首章、第 1,507 章、目录搜索、跨章书签恢复第 3 段、桌面纸色/手机夜间模式。
- 上传前后原件与受保护下载的 SHA-256 相同；私有状态下访客列表为空，正文与下载返回 404。
- 手动 axe 检查修正了书架装饰、目录序号、书封与标签对比度；详情、阅读器和书签弹窗的复验无违规。
- 真实数据验收用的阅读历史/书签已清理，本地仅保留一份干净的私有小说草稿。
- 实测占用端口时启动会安全拒绝；停止再启动后，同一书籍、1,507 章与下载哈希保留，仍为私有状态，测试时长为零。

原始小说、私人会话、数据库与这些页面的截图不随代码提交。可复现测试仅使用仓库内的原创合成文本。

## 三层重构本机验收（2026-09-13）

- 后端 **80 项**测试（含真实小说只读解析）全部通过，无失败、错误或跳过；MyBatis / ArchUnit / Spotless / 严格 JavaDoc 和打包均通过。
- PowerShell 脚本 **26 项**、前端单元测试 **6 项**、Playwright 桌面 / 手机 / 无障碍流程 **3 项**全部通过；TypeScript、Vite、前端和 Markdown 格式检查通过，npm audit 为 **0 漏洞**。
- 完整复验在父进程显式设置 `DB_INIT_MODE=never` 后运行，测试仍使用隔离 H2 自动初始化，未读取真实 .env 或接入真实书库。
- 原数据库表结构、HTTP 路径与响应字段保持兼容；重构前已停机备份 backend/data，未执行跨库迁移或重新导入真实小说。
- 新版本已从原有 H2 书库成功启动，后端健康检查与前端首页均为 200；原《人道至尊》保留原书籍 ID、**1,507 章 / 3 卷 / 4,844,982 非空白正文码点**，仍为私有，目录与首末章读取通过，受保护下载的 SHA-256 与原 TXT 一致。
- 原书库验收仅执行登录、退出及只读查询，没有进入阅读器或写入阅读记录；查询前后进度、历史、统计、书签完全一致。匿名请求无法枚举该私有书目，其详情、目录、首末章、下载和感想均返回 404，主人记录接口返回 401。

## 未覆盖的范围

未做公网部署验收、MySQL 验证、Safari/Firefox 与真实移动设备矩阵、长期负载测试、浏览器崩溃后的精确最后秒恢复或正式安全渗透测试。CI 的通过状态以 GitHub Actions 当前执行结果为准。
