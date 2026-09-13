# MySQL / Redis 环境说明（由你操作）

## 先说结论

- **现在就能用 H2 运行，不必安装 MySQL 或 Redis。** 默认 H2 是文件数据库，重启后书籍、阅读记录和书签仍保留。
- 后端已使用 MyBatis Mapper，并包含 MySQL JDBC 驱动；如你希望学习或使用 MySQL，可按下面步骤创建一个独立数据库。
- **目前验证的是 H2，不代表真实 MySQL 已验收。** 完成配置后还要检查连接、导入、权限、会话统计、书签和重启持久化。
- 本次只读检测到这台开发机已有 MySQL 8.0.43，Windows 服务 MySQL80 正在运行；没有连接你的数据库、获取密码或修改服务。若你继续在这台机器操作，通常不用重新安装。
- **Redis 暂不引入。** 单管理员、单实例的 MVP 不需要为“使用技术”额外增加服务。

## 1. 准备与备份

先停止本项目的本地进程，然后备份原书库；不要删除旧的 H2 文件或原始小说。

```powershell
cd D:\javaweb\workspace\MyCloudNovel
.\scripts\Stop-Local.ps1

# 只复制到被 Git 忽略的备份目录，不移动或删除原数据。
$backup = Join-Path '.\backend\backups' ('before-mysql-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup | Out-Null
Copy-Item -LiteralPath '.\backend\data' -Destination $backup -Recurse

# 只检查服务，不在这里自动启动、重启或重装它。
Get-Service -Name MySQL80
```

若没有 MySQL，可自行安装 MySQL Community Server 8.0 / 8.4，初始化 root 密码，保持数据库仅本机或可信私网可达。不要把 3306 暴露到公网。下面以这台机器的 8.0 安装路径为例，其他版本自行替换路径。

## 2. 创建专用库和最小权限账户

在自己的 PowerShell 终端运行，`-p` 会在本机提示输入密码，**不要把密码发到聊天里，也不要写到命令行参数中**。

```powershell
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
& $mysql --default-character-set=utf8mb4 -u root -p
```

进入 MySQL 控制台后执行。把应用用户密码占位文本换成自己保管的独立强密码，不要复用网站管理员密码。若同名库或用户已经存在，请先检查，不要删除它来强行重建。

```sql
CREATE DATABASE cloud_novel
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'cloud_novel_app'@'127.0.0.1'
  IDENTIFIED BY '替换成你自己生成的强密码';

GRANT SELECT, INSERT, UPDATE, DELETE
  ON cloud_novel.* TO 'cloud_novel_app'@'127.0.0.1';

USE cloud_novel;
SOURCE D:/javaweb/workspace/MyCloudNovel/backend/src/main/resources/schema.sql;
SHOW TABLES;
SHOW GRANTS FOR 'cloud_novel_app'@'127.0.0.1';
```

应能看到 books、chapters、reading_progress、reading_sessions、bookmarks 共五张表。建表由你的管理账户手工执行，应用账户只拥有读写数据的权限；应用运行时不使用 root，也不需要授予 CREATE / DROP。新表使用支持外键的 InnoDB（MySQL 默认），可通过 `SHOW TABLE STATUS FROM cloud_novel;` 确认。

如果连接地址不再是 127.0.0.1，请先重新设计对应来源地址的权限与 TLS，而不是直接给账户授权 `%`。

## 3. 在本机 .env 中配置

保留原来的 ADMIN_USERNAME / ADMIN_PASSWORD / COOKIE_SECURE 配置，只在根目录 .env 中添加或更新下面四项。**.env 是私有文件；.env.example 中不得保存真实密码。**

```dotenv
# 以下 URL 只用于本机回环开发，不可照搬到远程生产连接。
DB_URL=jdbc:mysql://127.0.0.1:3306/cloud_novel?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=DISABLED&allowPublicKeyRetrieval=true
DB_USERNAME=cloud_novel_app
DB_PASSWORD=你刚刚设置的应用数据库密码
DB_INIT_MODE=never
```

重要说明：

- `DB_INIT_MODE=never` 表示不让应用自动建表，因为你已手工执行 schema.sql，且应用账户没有建表权限。
- 默认 H2 使用 `DB_INIT_MODE=always`。恢复 H2 时，在 .env 中移除/注释全部四个 DB_* 配置，并确保当前终端没有旧的同名环境变量后，再启动。
- 启动脚本把等号后面的内容当作原值，**不要额外加引号，不要在同一行追加注释**。密码中可有等号，但不能换行。
- 只有 scripts/Start-Local.ps1 会读取根 .env；IDE 或直接运行 java / Maven 时，需要自行设置进程环境变量。
- 上述开发 URL 为回环连接关闭 TLS 并允许获取认证公钥。远程环境必须使用 TLS（例如 sslMode=VERIFY_IDENTITY 和可信 CA 配置），不要关闭校验或在不可信网络上照抄此 URL。
- 原件路径 BOOK_STORAGE 默认仍为 backend 工作目录下的 ./data/books。不要因为切换数据库而删除或改名原件。

## 4. 连接与应用验收

可先使用应用账户验证连接，密码仍在本机交互输入：

```powershell
& $mysql --protocol=TCP -h 127.0.0.1 -u cloud_novel_app -p --default-character-set=utf8mb4 cloud_novel
```

MySQL 控制台里执行 `SHOW TABLES;` 与 `SELECT COUNT(*) FROM books;`，退出后启动应用：

```powershell
cd D:\javaweb\workspace\MyCloudNovel
.\scripts\Start-Local.ps1
```

在新空库中，用一份你自己编写、几段文字的 TXT 做验收，不要以“先删除 H2 数据重导真实小说”代替迁移：

1. 网站能启动、登录；上传合成 TXT 后默认私有，目录和正文正常，下载字节不变。
2. 只公开书目时，访客拿不到正文、目录、原件、前言和感想。
3. 公开正文后访客才能阅读；主人进度、书签、时长仍需登录，访客记录不会写入服务器。
4. 编辑感想、重复上报累计时长没有产生重复时长；重启后新库数据仍在。
5. 只删除自己创建的验收书目，确认相关记录级联清理；不要操作你已有的真实书籍。

完成后只需告诉我“数据库和用户已创建、表已导入、已填写本机 .env”，以及脱敏后的错误提示（如果有）。不要提供密码、完整 .env 或会话 Cookie；后续可以针对你配置好的环境验证。

常见问题：`Access denied` 检查账户来源地址和密码；`Unknown database` 检查建库；`Table doesn't exist` 检查手工建表；`Communications link failure` 检查服务/端口。不要通过给予应用 root 权限或开放公网端口解决这些问题。

## 5. H2 → MySQL 不等于自动迁移

**切换 DB_URL 后看到空书库是正常的：这是一个全新的数据库。** H2 中的旧书目、章节、阅读位置、会话与书签没有消失，只是当前应用不再连接它们；原件也仍保存在原目录。

本轮没有实现自动跨库迁移。真正迁移应在停写与备份后完成，保留原书籍 UUID、章节/段落坐标、时间戳、公开标志与 SHA-256，按外键顺序复制数据，再逐项核对总数、私有权限和原件映射。**只重传 TXT 无法迁移既有阅读历程与感想。** 需要迁移时应单独制定和测试可回滚脚本，不手动删除旧库。

## 6. Redis 什么时候再考虑

现在 Spring Security 会话驻留在应用内存，持久化业务数据在数据库；单管理员、单实例已足够。Redis 不是阅读数据的持久化替代品，也不会自动解决并发重复计时。

只有确实需要多实例共享会话、跨实例限流或有证据表明需要缓存时，再考虑 Spring Session / Redis 等方案，并补 TTL、权限隔离、故障降级和清理测试。引入时另行提供安装、ACL、绑定地址、密码和持久化配置步骤；本轮没有加入 Redis 依赖或要求你安装 Redis。
