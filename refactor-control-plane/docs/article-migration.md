# 旧 posts 数据迁移与对照验收

本期实现单项目 H2 参考迁移：读取控制平面数据库中的旧 `posts` 快照，显式预检后复制到 `modern_articles`。不会自动连接 MySQL、运行旧仓库 SQL、导入线上数据、改写或删除旧表。它证明文章数据与 CRUD 的迁移能力，不代表整个旧博客的用户、菜单、评论、路由或富文本展示已完成迁移。

## 来源与映射

固定样例：`5e62608af333b5757bf767e05b19e2b0d56f0afb` 的 `Blog/src/main/resources/db/blogdb.sql`。`LegacyPostMigrationContractTest` 从该 Git 对象读取 **posts INSERT**，仅在隔离测试库重放；将该固定导出使用的 MySQL `\r\n` 转义还原为实际换行，不执行其他旧库语句。

| 旧字段 | 目标 | 处理 |
|---|---|---|
| Id | legacy_post_import.legacy_id → article_id | 新文章使用数据库生成 ID，避免覆盖已有文章；旧 ID 单独唯一映射 |
| PostTitle | title + source_title | 原样保留，标题上限扩展为 255；空白或超限整批拒绝，不截断 |
| PostContent | content + source_content | 原样保留 HTML、Unicode 和换行，不将 HTML 当作可直接执行的页面内容 |
| RequestMapping | request_mapping | 保留来源路由；本期不启用旧 URL 跳转 |
| BlogMenuId | blog_menu_id | 保留原关联 ID；本期不迁移菜单或据此授予权限 |
| DateTime | source_datetime + source_zone → updated_at | 原始时间保留到纳秒；必须显式指定时区，再转换成 Instant |
| 无对应字段 | status | 必须显式指定 DRAFT / PUBLISHED / ARCHIVED，不推断发布状态 |

`updated_at` 延续已有 H2 无时区列及 JDBC 读写方式；服务部署应保持 JVM 默认时区一致。本期保存了原始时间与显式来源时区，但没有迁移既有文章时间列的历史时区语义。真实 MySQL 迁移前仍需确认备份编码、来源时区和状态策略。

## 本地准备

1. 先备份本项目 H2 数据库，停止向待迁移快照写入。通过已有可信导出/导入流程，将需要迁移的单个旧库 `posts` 放入同一个本地参考数据库。不要直接在控制平面库执行整个旧仓库 SQL dump。
2. `posts` 必须具有 `Id, PostTitle, PostContent, RequestMapping, BlogMenuId, DateTime` 列。时间列为无时区本地时间；标题/正文/路由编码必须已确认。来源身份固定为本项目的单个 SpringMvcBlog 快照，不能把另一个旧库复用到已有映射上。
3. 启动应用会幂等执行 V1/V2 的目标扩展 DDL；不会启动数据复制。配置项目本地经理账号，详见 [治理操作](governance-operations.md)。

## 预检与执行

两个端点均要求当前项目的 `PROJECT_MANAGER`，通过 `Authorization: Bearer ...` 传入项目本地凭据。没有默认凭据。下列时区与 DRAFT 仅为示例，必须按实际备份选择。

```http
POST /api/v1/article-migration/preview
Content-Type: application/json
Authorization: Bearer <project-manager-token>

{"sourceZone":"Europe/Istanbul","targetStatus":"DRAFT"}
```

预检返回 `fingerprint, sourceCount, pending, alreadyImported, imported, issues`，不写文章、映射或迁移审计。`issues` 非空时不可执行。DST 重叠/缺失时间会报错，需人工核实后选择明确的固定偏移；不会自动选择较早或较晚的时间。

```http
POST /api/v1/article-migration/apply
Content-Type: application/json
Authorization: Bearer <project-manager-token>

{"sourceZone":"Europe/Istanbul","targetStatus":"DRAFT","expectedFingerprint":"<preview 返回的 64 位 SHA-256>"}
```

指纹绑定有序来源行和完整迁移策略。执行时重新读取并比对；来源/策略变化、非法数据、旧映射冲突、目标已编辑或已删除、已导入来源消失均拒绝整批执行。重试保留目标编辑和删除，不自动恢复或覆盖。迁移成功后不再让原来源表与新文章双向同步。

文章、映射和成功审计在同一数据库事务提交。数据库行锁协调并发执行；同一批重试 `imported=0`，不产生第二条成功审计。失败整批回滚；失败请求不承诺生成成功审计。请求超时后先重新预检/核对映射与 `article_migration_run`，不能仅凭超时判断未执行。

`alreadyImported` 只计算执行前已迁移且校验一致的来源行，`imported` 只计算本次新增；`pending` 在成功执行响应中为 0。来源消失的冲突也会进入 issues，因此不能用 issues 数量推算来源行数。

## 边界和验收

- 单批最多 10,000 行、16 MiB UTF-8 文本；SQL 先统计行数/字符长度，再读取 LOB 并计算 UTF-8 大小；事务超时 30 秒。
- 保留映射墓碑，故意不设置会级联删除来源记录的文章外键。
- 不提供破坏性回滚端点。需要撤销时，先对照目标指纹及审计，审批具体受影响行；不得批量删除已被用户编辑的数据。
- 验收覆盖固定样例数据、已有目标 ID、255 字符标题、重复/并发请求、来源变更、目标编辑/删除、原子回滚、时区歧义、纳秒精度和权限。
- OpenAPI [1.1.0 契约](contracts/articles.openapi.yaml) 包含 CRUD 和迁移请求/响应。尚未验收真实 MySQL 驱动、跨库事务、生产备份恢复或旧博客路由/菜单/评论完整行为；MVP-08 仍不能标记全部完成。
