# 第十里程碑：文章切片业务验收完成

日期：2026-10-18。10A、10B1、10B2、10C 均已交付；第 11 里程碑的完整 MVP 发布复验仍待进行。本结论针对隔离样例和受管候选，不是客户生产迁移或实际业务人员签署。

## 退出条件

| 要求 | 结果与证据 |
|---|---|
| 在被管控仓库交付独立 Vue 3/Spring Boot 切片 | 平台创建真实任务工作树，将样例代码提交到隔离仓库，再组装候选；验证命令读取候选目录内后端、DDL 与 Vue 源码，回传 HEAD 与候选提交核对 |
| 固定旧数据逐项对照 | 固定 SQL 七篇，两个博客、三个 BlogMenu 关联；标题/原 HTML 不改写，旧新 ID、菜单/博客、原始本地时间→UTC、旧地址逐行验证并保存机器记录；已有目标文章未覆盖 |
| 已确认保留及变化项 | 博客归属隔离、账号会话/CSRF、白名单 HTML、精确稳定 URL、标题/正文/状态编辑和软删除均按已批准决定实现；不迁移旧密码、不重写评论产品 |
| 真实数据库兼容与失败恢复 | MySQL 8.0.31、Connector/J 9.2.0；Unicode/大正文/微秒/UTC/2070 年时间。SELECT-only 独立源，真实 MySQL 的映射插入失败使文章/博客/菜单/映射/审计/源绑定整体回滚；并发只导入七篇、一次成功审计，已编辑/软删除目标不覆盖、不复活 |
| 证据绑定实际候选 | 平台最新验证 ID 供 UNIT/INTEGRATION 门禁引用；独立测试角色提交评审和业务说明，进入 ACCEPTED 并产生 HUMAN_ONLY 建议；审计导出绑定需求/ADR、执行/提交、候选/验证及门禁，并独立复算摘要 |
| 主分支与环境边界 | 受管样例 main 前后均为固定旧提交；只生成建议。治理库和业务源/目标物理隔离；本轮平台与 MySQL 已正常关闭，不修改现有服务、全局配置或生产库 |

## 最终精确身份

- 计划 scope：`0d343456277b00079690c81ca19abc5fe87119854e17c336878f1018fee43c30`
- task commit：`1db7a8a3c801909ce9fc760298d6bb0c6fa8a6b5`
- execution：`36eed72d-7c64-40be-9525-0155ce284e38`
- candidate：`5da8f5ef-2979-4eb8-85a7-15cc7e0ba24c`
- candidate commit：`951346bb36a29eaf97486ce792bf82543514d202`
- validation：`32ac092c-2cb1-492f-b0c6-fca23690c1ec`
- audit payload SHA-256：`101e7e677634d7d1b90c46ab5726a95e9dd13fbfe804b646f4e42bdabd1dbcd4`

[完整机器证据和七行映射](milestone10-evidence.json)、[可独立复算的审计报告](milestone10-audit.json)。`.runtime/phase10-accepted` 保留本机日志、Git 与治理库；业务实例在机器证据的 `mysqlFixture`，状态 `STOPPED`。这些 `.runtime` 路径不随克隆分发，版本化 JSON 和复现脚本保留必要身份及结论。

## 本轮检查

- 实际 MySQL **21 项**全部通过、零跳过：API 4、存储 3、引擎 4、文章行为 7、会话 1、真实登录 1、七行多博客迁移综合场景 1。
- 同一 Maven verify 另有 **6 项 H2**通过：独立迁移边界 5、旧 schema guard 1；总数 27，不冒充 27 项真实 MySQL，也不与 10A/10B1 历史计数累加。
- 候选 Vue **16 项**、类型检查和生产构建通过；后端打包及平台当前包构建成功。
- Python **13 项**通过：MySQL 夹具边界 6、样例/嵌套工作树 5、受管脚本边界 2。
- 独立代码复核无剩余重要问题。先以真实 MySQL 观察 JDBC `databaseTerm=SCHEMA` 可绕过 catalog 隔离的红灯，再改用服务器实际 schema/大小写规则，回归验证同 schema 拒绝、不同 schema 别名指纹一致。原文、纳秒保留、UTC 微秒映射、DST 歧义、来源变化、目标编辑/删除、并发和事务边界均有测试。

受管门禁使用不同测试身份，只证明平台角色与绑定机制正确，不代表实际业务审批；报告和门禁说明均保留 fixture 标记。未宣称新的浏览器交互验收、生产备份切换、多节点耐久性或完整 MVP 完成。

## 复现

在平台根目录，使用新目录及已有项目依赖；脚本不安装系统服务或全局工具：

```powershell
python -X utf8 scripts/verify_milestone10.py governed --destination .runtime/phase10-new --mysqld "D:/Database/MySQL/MySQL Server 8.0/bin/mysqld.exe" --mysql "D:/Database/MySQL/MySQL Server 8.0/bin/mysql.exe" --java "C:/Program Files/Java/jdk-21.0.11/bin/java.exe"
python -X utf8 scripts/verify-audit-export.py docs/acceptance/milestone10-audit.json
```

保留旧库，新目标先显式应用业务 DDL；本地迁移配置、预检/执行命令和恢复边界见[迁移说明](../article-migration.md)。10B1 行为细节见[前期验收](milestone10b1-article-behavior.md)。下一阶段为第 11 里程碑整体发布复验。
