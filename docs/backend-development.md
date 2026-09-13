# 后端开发规范

## 一、三层结构必须是真正的依赖边界

业务调用方向固定为 **Controller → Service 接口 → ServiceImpl → Mapper → 数据库**。

- Controller 接收 DTO、执行 `@Valid`、从安全上下文判断是否为主人，调用 Service 并返回 VO。HTTP 状态码、文件下载响应头只放在 Web 层。
- Service 接口表达业务能力；实现类放在 `service.impl` 并标记 `@Service`。业务权限、参数边界、事务、累计会话幂等不能只依赖 Controller 的校验。
- Mapper 使用 `@Mapper`；SQL 放入 `src/main/resources/mapper` 下同名 XML，只负责存取，不决定能否向访客公开。
- Entity 表示数据库记录；请求用 DTO，响应使用 VO。禁止将 BookEntity 直接返回给前端，以免泄露内部字段。
- 对书籍、章节、原件和公开感想的读取，复用 LibraryService 的可读性校验，不能各自实现一套相似但有差异的权限判断。
- 依赖通过构造器注入，字段为 `private final`。避免通用 BaseService / BaseMapper 大继承树、无意义的工具类和只改名称的“假分层”。

ArchUnit 在测试中检查层间依赖、Service 接口位置、实现类位置、Mapper 注解与接口类型，防止后续不小心跨层。

## 二、Java 与注释约定

采用 Java 21，生产代码和测试统一使用 **Google Java Format 的 AOSP 风格（四空格缩进）**，由 Spotless 执行。不要手工混用不同排版风格。

- 类 / 接口写中文 JavaDoc，描述职责、边界，必要时解释事务或隐私前提。
- 公开方法写用途、`@param`、非 void 方法的 `@return`，已知异常写 `@throws`；覆盖方法继承接口文档，存在额外约束时再补充。
- record 的每个组件用类级 `@param` 文档说明，替代重复字段和 getter 注释。
- 关键字段写含义，时间单位明确为秒或 Unix 毫秒；索引明确从 0 开始。
- 行内注释解释“为什么”，例如锁为何必须覆盖 commit、为何不能把私有前言返回给访客，而非逐句翻译 Java。
- 变更代码时同步更新注释，不编造作者、版本或未实现的保证；不为加注释而引入 Lombok / Swagger 等无关依赖。

示例来自现有服务接口的风格：

```java
/**
 * 查询当前身份可读的书籍，统一用于正文、目录、下载与公开感想检查。
 *
 * @param id 书籍 UUID
 * @param owner 是否为已认证的主人，由 Web 层确定
 * @return 当前身份可读的书籍视图
 * @throws BusinessException 书籍不存在或该身份无权读取正文
 */
BookView requireReadableBook(String id, boolean owner);
```

## 三、业务规则和事务

- 默认私有：新导入的书目与正文均不公开；公开正文必须先公开书目。
- 阅读会话以 UUID 幂等，只保存更大的累计秒数，不把重试当成新时长；使用服务器 Clock 验证时间窗口。
- 所有时长统计按北京时间归属到会话开始日。单段最多 1,800 秒，允许七天内开始的片段和五秒计时容差。
- 字符串最大长度既要有 Bean Validation，也要在直接调用 Service 时保护关键业务边界。
- 使用 `TransactionTemplate` 协调导入、删除、书签和阅读写入。其 execute 返回时已经完成提交，因此能够在提交失败后补偿原件。
- 不在可能提前结束的方法体 try/catch 中假定 `@Transactional` 的 commit 已完成。只读统计使用 `@Transactional(readOnly = true)`。
- SQL 一律使用 `#{parameter}` 绑定；列表不装载小说全文；章节插入以 200 条为一批，所有批次共用一个事务。
- 不捕获异常后假装成功。可预期的“不存在 / 冲突”使用 BusinessException，交由 ApiExceptionHandler 返回 404 / 409；未知错误返回通用提示，日志用于诊断，不打印正文或密码。
- 当前仅针对单应用实例。扩展到多实例时先重做并发和会话方案，不能把内存同步锁当分布式锁。

## 四、每次新增或调整功能的顺序

1. 确认 API 与权限：访客能否访问，主人哪些数据必须保密。
2. 定义或更新 DTO / VO、Service 接口和中文 JavaDoc。
3. 实现业务规则，在 Mapper XML 中实现需要的 SQL，不改动无关数据。
4. 补充 Service 单元测试；SQL 改动补 Mapper 集成测试；HTTP 或权限改动补 MockMvc / 浏览器回归。
5. 如果改表，提交显式迁移方案与备份说明；`schema.sql` 的 IF NOT EXISTS 不是迁移工具。
6. 执行格式、测试、打包、文档校验，检查 Git diff 中没有小说、密码、数据库或运行产物。
7. 创建可追踪的 Git commit 并推送 GitHub，遵守根目录 AGENTS.md。

## 五、常用命令

在 backend 目录执行：

```powershell
# 自动格式化 main 与 test 下的 Java
mvn -B -ntp spotless:apply

# 提交前必须通过：格式检查、编译、JUnit/ArchUnit、打包、严格 JavaDoc 检查
mvn -B -ntp verify

# 排查时可单独运行一个测试；不能替代最终完整验证
mvn -B -ntp -Dtest=ReadingServiceTest test
```

JavaDoc 启用 doclint=all 和 failOnWarnings；缺失公开 API 文档或错误标签会让 verify 失败。 生成文档入口为 `backend/target/reports/apidocs/index.html`。不要通过关闭检查来绕过失败。完整项目（含前端、可选浏览器）使用根目录的 `scripts/Test.ps1`，覆盖范围见 [测试说明](testing.md)。

## 六、数据与环境安全

开发、JUnit 和浏览器测试使用各自隔离的 H2/临时原件目录。测试不读取根 .env，不操作实际书库。真实小说只在显式设置 NOVEL_TEST_FILE 时进入只读解析测试，绝不随代码提交。

生产数据位于 backend/data（或显式配置的存储目录），更新代码前应停机备份。H2 → MySQL 不会自动迁移书目、章节、进度和书签；操作步骤见 [MySQL / Redis 环境说明](mysql-redis-setup.md)。
