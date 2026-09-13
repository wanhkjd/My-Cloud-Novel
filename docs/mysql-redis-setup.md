# MySQL / Redis 操作步骤（由你执行）

## 先明确当前状态

项目现在只使用 **MySQL + Redis**，没有 H2 依赖、自动建表或内存数据库回退。原始 TXT 仍在服务器私有磁盘，章节正文和全部主人阅读数据保存在 MySQL。Redis 只负责登录会话 / CSRF。

2026-09-13 本机只读检查结果：

- MySQL 8.0.43 可连接；`cloud_novel`、`cloud_novel_test`、`cloud_novel_e2e` 及其专用账户尚未创建。
- Redis 在 6379 运行，版本为 Windows 3.2.100；认证返回“未设置密码”，说明提供的密码尚未在当前进程生效。监听地址包含所有网卡，需由你限制到回环。
- 本机旧版项目进程已停止，当前不能据此宣称新网站已启动；旧数据已有停机备份，未执行跨库迁移。
- 助手没有执行本机建库、授权或写业务数据，也没有安装 / 修改 MySQL、Redis、Docker 或 WSL。

推荐 Redis **7.4**（CI 使用此版本），MySQL **8.0.16+ / 8.4**。Redis 3.2 是长期未维护的旧 Windows 移植版，不建议部署；若暂时保留，只限回环本机调试，仍需独立兼容性验收。

> 不要把密码、实际 `.env`、生成的 SQL、Cookie 或小说正文发到聊天 / GitHub。根目录 `.env`、`.env.test`、`.local/` 均已被 Git 忽略。

## 1. 准备私有配置（不会操作服务或数据库）

在项目根目录执行。如果已有 `.local/setup` 输出，请直接检查和复用，脚本会拒绝覆盖，不要为了重跑而删除数据库或重置用户。

```powershell
cd D:\javaweb\workspace\MyCloudNovel

# 已有 Redis：用隐藏输入传入你准备启用的密码，不写在命令参数中
$redisPassword = Read-Host '本机 Redis 密码' -AsSecureString
.\scripts\Prepare-LocalEnvironment.ps1 -RedisPassword $redisPassword
Remove-Variable redisPassword

# 如果是全新 Redis 而不是已有服务，可改为不传参数，生成独立强密码：
# .\scripts\Prepare-LocalEnvironment.ps1
```

只生成以下文件，不连接数据库、不启动 Redis、不覆盖现有 `.env`：

| 文件                           | 用途                                                                    |
| ------------------------------ | ----------------------------------------------------------------------- |
| `.local/setup/application.env` | 网站管理员、MySQL 应用账户、Redis 连接、私有 TXT 目录                   |
| `.local/setup/test.env`        | 独立后端 / 浏览器测试账户及 Redis 测试连接                              |
| `.local/setup/bootstrap.sql`   | 由你审核后执行的三套新库 / 账户 / 表结构                                |
| `.local/setup/redis.conf`      | **容器专用** Redis 配置，含密码、AOF、noeviction；不是 Windows 原生配置 |

MySQL 应用 / 后端测试 / 浏览器测试密码独立随机生成。有效的已有网站管理员身份会保留，没有时才生成。传入的 Redis 密码用于连接已有服务，不代表脚本替你启用了服务端密码。

为兼容本地生成文件的三种语法，生成器接受 1–128 位字母、数字、下划线或连字符的已有 Redis 密码；其他字符请手工配置。运行程序没有这个字符限制。弱密码仅用于隔离本机练习，正式环境请改成长随机密码。

## 2. 由你审核并初始化 MySQL

本机已有 MySQL，无需重新安装。用交互式 `-p` 输入 root 密码，不要写进 SQL 或命令行：

```powershell
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
& $mysql --protocol=TCP -h 127.0.0.1 -u root -p --default-character-set=utf8mb4
```

先在 MySQL 控制台检查：

```sql
SELECT VERSION(), CURRENT_USER();
SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME IN ('cloud_novel', 'cloud_novel_test', 'cloud_novel_e2e');
SELECT User, Host FROM mysql.user
WHERE User IN ('cloud_novel_app', 'cloud_novel_test', 'cloud_novel_e2e');
```

**若任一同名库 / 账户已存在，先停止并检查，不要直接导入或删除重建。** 生成的 SQL 是新安装初始化，不是可重复执行的迁移脚本；中途出错也不要盲目重跑。

确认没有冲突、在本机编辑器检查 `bootstrap.sql` 后，执行：

```sql
SOURCE D:/javaweb/workspace/MyCloudNovel/.local/setup/bootstrap.sql;
SHOW TABLES FROM cloud_novel;
SHOW TABLES FROM cloud_novel_test;
SHOW TABLES FROM cloud_novel_e2e;
SHOW GRANTS FOR 'cloud_novel_app'@'127.0.0.1';
SHOW GRANTS FOR 'cloud_novel_test'@'127.0.0.1';
SHOW GRANTS FOR 'cloud_novel_e2e'@'127.0.0.1';
```

每个库应有 **books、chapters、reading_progress、reading_sessions、bookmarks** 五张表。应用连接 `cloud_novel`，后端测试连接 `cloud_novel_test`，浏览器连接 `cloud_novel_e2e`；各账户只有自己库的 SELECT / INSERT / UPDATE / DELETE 权限。root 只用于你手工管理，不能用来运行网站或测试。

新表统一使用 InnoDB / utf8mb4，章节正文为 LONGTEXT，一章一条记录。应用固定 `spring.sql.init.mode=never`，不拥有 CREATE / DROP / GRANT 权限。没有 Flyway 自动迁移，后续表结构变更需另行审核脚本。

## 3. 激活本地应用和测试配置

在 PowerShell 项目根目录备份旧配置，然后由你复制待审核配置。数据库、原件和旧备份不要删除。

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (Test-Path -LiteralPath .env) {
    Copy-Item -LiteralPath .env -Destination ".local/setup/previous-$stamp.env"
}
if (Test-Path -LiteralPath .env.test) {
    Copy-Item -LiteralPath .env.test -Destination ".local/setup/previous-test-$stamp.env"
}
Copy-Item -LiteralPath .local/setup/application.env -Destination .env
Copy-Item -LiteralPath .local/setup/test.env -Destination .env.test
```

检查 `BOOK_STORAGE` 指向预期的私有目录。默认值 `./data/books` 是相对**后端工作目录**的路径；脚本从 backend 启动，所以实际为 `backend/data/books`。不要指向前端 public、Web 服务器静态目录或公开共享盘。

这套 JDBC 参数关闭 TLS 并允许本机公钥检索，**仅适用于 127.0.0.1 开发**。远程 MySQL 必须走可信私网和验证证书的 TLS（例如 sslMode=VERIFY_IDENTITY），不要照搬本机配置到公网。

## 4. 由你配置 Redis（二选一）

### A. 暂时沿用本机已有 Windows Redis

你当前的目录为 `D:\javaweb\Redis`，存在 `redis.windows.conf` 和 `redis.windows-service.conf`。先确认实际启动命令 / Windows 服务使用哪个文件，不要只改一个未被加载的配置文件。

1. 备份实际配置，停止你启动的 Redis 控制台或对应服务（不要停止其他不相关的实例）。
2. 在该配置中设置 `bind 127.0.0.1`、`protected-mode yes`，并用 `requirepass` 设置你在第 1 步输入的密码。不要只在客户端工具里保存密码。
3. 使用**明确指定配置文件**的方式重新启动；Windows 服务应使用它实际配置的文件。保留已有持久化路径，不要覆盖现有 Redis 数据。
4. 防火墙不要开放 6379 到公网。确认匿名 PING 被拒绝，认证 PING 返回 PONG。

可以打开原生客户端，在交互模式中验证（用真实密码替换占位文字，不要把认证输出分享出去）：

```powershell
& 'D:\javaweb\Redis\redis-cli.exe' -h 127.0.0.1 -p 6379
```

```text
PING
# 预期：NOAUTH Authentication required.
AUTH <你的Redis密码>
# 预期：OK
PING
# 预期：PONG
QUIT
```

这是旧版本的临时本机操作方式，不等于对 Redis 3.2 的生产支持承诺。建议改用下面的受维护版本，再执行完整集成验收。

### B. 推荐：Redis 7.4 容器，不接管你现有 MySQL

自行安装并启动 [Docker Desktop for Windows](https://docs.docker.com/desktop/setup/install/windows-install/)，按官方要求启用所需虚拟化 / WSL 环境。助手不会替你安装、启用系统功能或修改现有服务。

**先确认 6379 没有被旧 Redis 占用**；不要同时启动两个同端口实例。项目的 Compose 只包含 Redis，不会创建或改动现有 MySQL。第 1 步生成的 `redis.conf` 绑定容器内部所有网卡，但主机端口只发布到 `127.0.0.1`，并启用密码 / AOF / noeviction。

```powershell
docker version
docker compose version
docker compose --env-file .env -f deploy/compose.redis.yml up -d
docker compose --env-file .env -f deploy/compose.redis.yml ps
docker compose --env-file .env -f deploy/compose.redis.yml exec -T redis redis-cli ping
# 最后一条应为 PONG，认证从容器环境传入，不需要 -a 明文参数
```

本机尚未运行这份 Compose，不能把文件存在当作容器已验收。不要使用 `down -v` 删除持久化卷。已有远程 Redis 请自行配置私网、ACL / TLS 和连接参数；当前自动测试配置用于本机 / 隔离 CI，不用于公网服务。

## 5. 验证后启动网站

先安装 Playwright 的 Chromium（只需首次执行），再做完整验证：

```powershell
cd D:\javaweb\workspace\MyCloudNovel\frontend
npx.cmd playwright install chromium
cd ..
.\scripts\Test.ps1 -BrowserTests
.\scripts\Start-Local.ps1
```

可加 `-NovelPath '.\《人道至尊》.txt'` 做只读解析，不会向 MySQL 导入真实小说。测试必须使用独立库 / DML 账户；没有基础设施时 `-UnitOnly` 只跑单元、静态和构建检查，**不是完整验收**。

运行后分别检查：

- [依赖就绪](http://127.0.0.1:8080/api/ready)：MySQL / Redis 正常时为 UP；不可用时不能宣称就绪。
- [进程存活](http://127.0.0.1:8080/api/health)：仅证明进程可响应，不代表数据库已准备好。
- [网站](http://127.0.0.1:5173)：登录、导入你有权使用的测试文本、核对权限 / 书签 / 时长，再停止和重启核对持久化。

Cookie 为 `CLOUDNOVEL_SESSION`。Redis 会话失效后需要重新登录，但 MySQL 中的书籍和主人阅读记录不应丢失。共享设备应退出登录。生产使用 HTTPS 和 `COOKIE_SECURE=true`，Redis 会话共享不代表阅读业务已支持多实例。

## 6. 旧数据、备份与后续扩容

- 切换前的停机备份位于 `backend/backups/before-mysql-redis-20260913-213112/data`。旧 H2 文件**仅归档保留，不再由项目运行 / 测试加载**；原始小说未修改。
- **本轮没有迁移旧书目、章节或阅读历史。** 新 MySQL 初始化后是空库。重新导入 TXT 不能恢复原书籍 ID、历史、书签或感想；若要迁移这些数据，需要先设计专门的只读导出 / 核对方案，不能直接删除旧文件。
- 更新前先停止网站写入，再用 MySQL 工具导出业务库，并备份 `BOOK_STORAGE` 的原件目录和私有配置。Windows 推荐 `mysqldump --result-file=<本机备份文件>`，避免 PowerShell 重定向改变 SQL 文件编码；使用交互式 `-p`，不要把备份推送到 Git。
- TXT 与数据库一起备份：只备份 TXT 不含阅读历史，只备份 MySQL 不含原始字节下载。Redis AOF 只帮助恢复会话，不能替代这两份备份。
- 未来独立扩容时实现 `NovelFileStorage` 的 OSS / COS / S3 适配器，使用私有桶和授权下载；当前只实现私有磁盘，不需要你开通云存储。

配置完成后可以让助手继续只读检查连接，然后用专用测试账户执行自动化验收；无需发送任何真实密码或配置文件内容。
