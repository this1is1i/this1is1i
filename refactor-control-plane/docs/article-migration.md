# 独立文章迁移与第十里程碑验收

10B2 的业务迁移工具位于 `samples/article-app`，以本地命令运行；源 MySQL 和目标 MySQL 使用独立 schema、独立连接。控制平台只保存任务、候选、验证、门禁与审计，不读取或迁移业务数据。历史平台内 H2 demo 的 `LegacyPostMigrationService` 和 `/api/v1/article-migration` 仅保留兼容，不是当前交付路径。

## 原始数据与策略

固定来源为 SpringMvcBlog `5e62608af333b5757bf767e05b19e2b0d56f0afb` 的 SQL 对象。测试只提取 `blogs, menus, blogsmenus, posts` 的建表和 INSERT，不执行整个 dump、不导入用户密码。七篇文章引用：

| 旧 BlogMenuId | 旧博客 Id | 旧菜单 Id | 篇数 |
|---|---|---|---:|
| 15 | 3 | 1 | 5 |
| 25 | 8 | 1 | 1 |
| 48 | 3 | 3 | 1 |

不引用的旧博客、菜单和评论产品不随文章切片重写。博客到新本地账号的映射必须显式给出并覆盖所有文章来源博客，账号必须存在于显式账号文件；不从旧用户 ID 推导新身份。测试中的 `Europe/Istanbul`、PUBLISHED 和两个测试账号都是显式夹具策略，不证明历史生产库采用该时区或状态。

## 本地命令

先在新业务目标库显式应用样例 `backend/src/main/resources/schema-mysql.sql`。源账号仅授予 SELECT；目标运行账号只需读写业务表，DDL 由操作者预先执行。不得指向治理库，不复用现有服务/datadir，不自动分配无归属旧目标数据。

在项目忽略的 `.runtime` 保存 UTF-8 JSON 配置，密码和账号文件不得提交：

```json
{
  "source": {"url":"jdbc:mysql://127.0.0.1:3306/legacy_snapshot?connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=30000","username":"source_reader","password":"<source password>"},
  "target": {"url":"jdbc:mysql://127.0.0.1:3306/new_article_target?connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=30000","username":"article_writer","password":"<target password>"},
  "accountsFile":"accounts.json",
  "sourceZone":"Europe/Istanbul",
  "targetStatus":"DRAFT",
  "owners":{"3":"author-one","8":"author-two"},
  "operator":"migration-operator"
}
```

账号格式与散列要求见[样例说明](../samples/article-app/README.md)。相对账号路径以配置文件所在目录解析。连接 TLS 策略由项目配置明确提供；工具不自动降级 TLS。

```powershell
java -jar <样例后端jar绝对路径> --migration preview <配置绝对路径>
java -jar <样例后端jar绝对路径> --migration apply <配置绝对路径> <预检返回的64位fingerprint>
```

此分支不启动 Spring Web，不新增 HTTP 迁移端点。命令输出只含指纹、数量和问题代码，不输出凭据或原文；出错时结果未确认，先重新预检并核对目标映射/成功审计，不自动重发。operator 是持有数据库凭据的本地操作者声明，不冒充平台认证或实际人工签署。

## 保留与拒绝行为

- 旧 ID → 数据库生成的新文章 ID 保存在 `legacy_post_import`，不覆盖已有文章。`source_json` 保留标题、原 HTML、slug、旧 BlogMenuId/博客/用户/菜单 ID、博客 URL、菜单名及原始本地时间；`policy_json` 保留显式策略。目标文章 → 菜单 → 博客保存新关联，旧新关联可完整对照。
- 原文不清洗写回；公开接口仍按 10B1 白名单显示。源时间先拒绝 DST 重叠/缺失，再按显式时区转换成 UTC 微秒；原纳秒值留在映射中。标题、关联或路由非法/歧义时整批拒绝，不 trim、截断或猜测修复。
- 三段路由保留原值，使用 NO PAD 唯一键；已有路由（包括已软删除的占用）不得被新导入覆盖。博客原有归属冲突拒绝。
- 预检不写目标。指纹绑定源服务器/实际 schema、目标、完整策略和有序源快照；MySQL 身份取服务器 `DATABASE()` 与大小写规则，不依赖可被 JDBC 元数据选项改变的 catalog。
- 执行取得目标数据库行锁，再重新读取一致的只读源快照。文章、博客/菜单、原文与 ID 映射、源身份绑定和成功审计在**同一目标事务**提交。源只读，不宣称跨库分布式事务；需要冻结的客户源快照由操作者提供。
- 并发/重复执行只导入一次、只新增一次成功审计。来源变化/消失、策略变化、目标编辑或删除均拒绝后续覆盖；墓碑和映射保留，不恢复已删除文章，不提供破坏性回滚接口。
- 单批 10,000 个连接后行、16 MiB 编码文本预算，单正文 1 MiB。先聚合连接后的行数/字符长度，避免关联重复放大 LOB；事务/语句有时间边界。超过规模需要具体迁移计划，不静默拆分。

## 真实引擎与平台绑定

`scripts/mysql_sample.py` 可在**新的项目目录**创建已有 MySQL 二进制的独立进程，使用源只读账号、新目标及独立故障目标；测试后核对 datadir/端口并正常关闭。可用 `--app` 指向项目内实际候选中的样例，不能用根目录样例报告替代候选报告。

`scripts/verify_milestone10.py governed` 复现新治理库、固定旧 Git 基线、任务工作树、提交、候选、本机 MySQL/Vue 验证、独立测试角色门禁、人工建议和审计导出。测试身份只验证角色隔离和门禁机制，不代表生产人工签署；样例 main 保持固定旧提交，不自动合并。

最终逐项结论和精确候选身份见[第十里程碑验收](acceptance/milestone10-article.md)。客户生产备份、停写切换、容量认证和完整博客产品不属于本次隔离样例证明。
