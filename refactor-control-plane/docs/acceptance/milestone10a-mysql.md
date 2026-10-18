# 10A：独立 MySQL 存储基线

验收日期：2026-10-18。数据库策略 A 已批准：保留 MySQL。当前交付仅为存储与真实引擎基线；完整第 10 里程碑、数据迁移工具及文章行为尚未完成。决定和后续范围见[业务决定](../article-business-decisions.md)。

## 交付和证据

- 独立样例增加 Spring Boot 3.5.0 管理的 Connector/J 9.2.0、显式 MySQL profile 和新业务库 DDL。平台治理数据库与平台依赖均未改动。
- MySQL 新表使用 InnoDB、utf8mb4、LONGTEXT 和 UTC 约定的 DATETIME(6)。JDBC 显式传 UTC Calendar，API 写入前统一到微秒，创建响应与读取结果相同。旧实现的 JVM 时区偏移及纳秒/微秒不一致已有失败反例。
- 使用本机现有 MySQL **8.0.31** 可执行文件，初始化全新的项目内数据目录，以独立回环端口和随机专用凭据运行；没有安装服务、修改全局配置或连接现有数据库。
- 从本机已固定 Git 对象 `5e62608af333b5757bf767e05b19e2b0d56f0afb` 提取 posts 的建表与插入语句，七条旧文章进入全新只读来源 schema。不是执行整个旧 dump，也不读取客户数据。来源账号只有 SELECT；目标 CRUD 后原行各文本字段 HEX 及时间/关联逐项相等，来源 UPDATE 被数据库权限拒绝。
- REST、存储和 MySQL 引擎三类共 **10 项**真实 MySQL 测试通过，打包成功；覆盖中文/emoji、大于 MySQL TEXT 容量的 HTML、跨 JVM 时区、2070 年时间、微秒精度、InnoDB 事务回滚及来源只读隔离。H2 兼容七项另行通过，不能与 MySQL 计数累加成 17 个不同功能。
- Python 六项测试覆盖目录/输出链接越界、拒绝已有目录、SQL 字符串内分号和引号、客户端登录文件隔离，以及“旧绿报告＋本次未执行”不得产生新 PASS 证据。每轮报告目录独立，并检查非零测试数、零跳过和当前配置路径绑定。
- 样例计划生成的五项既有回归同步通过，含真实嵌套 Git 工作树路径；与新脚本合计十一项 Python 检查。新计划文字已记录 MySQL 选择，同时明确脚手架 H2 构建不替代受管 MySQL 业务验收。

本轮证据包为 [milestone10a-mysql-evidence.json](milestone10a-mysql-evidence.json)。运行目录含私有凭据，仅留本机忽略目录；不把客户端配置或密码复制进验收包。

## 复现

项目 Maven 缓存缺少新 JDBC 依赖时，仅显式使用一次项目在线配置获取；日常验收继续离线，不修改用户 Maven 设置。从 `samples/article-app/backend` 执行：

```powershell
mvn.cmd --% -s ../../../scripts/maven-online-settings.xml -Dmaven.repo.local=../../../backend/.m2-repository -Dtest=ArticleApiContractTest,ArticleDatabaseContractTest verify
```

MySQL 已安装且本地固定样例 Git 对象存在时，从项目根运行（路径按实际安装填写，目标目录必须全新）：

```powershell
python -X utf8 scripts/mysql_sample.py --mysqld "D:/Database/MySQL/MySQL Server 8.0/bin/mysqld.exe" --mysql "D:/Database/MySQL/MySQL Server 8.0/bin/mysql.exe" --destination .runtime/my-new-mysql-proof
python -X utf8 -m unittest scripts.tests.test_mysql_sample
```

测试 JVM 使用 `Pacific/Auckland`，验收日期按用户时区记为 2026-10-18；日志中的 JVM 日期可能因 +13 时区跨日。MySQL 接口测试必须核对实际产品为 MySQL。最终日志为 `.runtime/phase10a-mysql-final/maven.log`，报告在该目录独立 `reports/`，正常关闭证明在 `manifest.json` 与 `server.log`。

若初始化、启动或关闭失败，保留目录和 manifest 读取实际状态，不重复使用目录、不初始化现有 datadir、不按模糊进程名杀服务。脚本关闭前核对实际数据目录及端口；Windows 服务父子 PID 可能不同，不能据此断言未关闭。

## 正常连接与边界

MySQL profile 没有默认 URL、账号或密码；通过项目私有外部 properties 提供 `article.mysql.url`、`article.mysql.username`、`article.mysql.password`，显式启用 `mysql` profile。先核对新业务 schema 并应用 `schema-mysql.sql`；profile 不自动执行 DDL。测试用的回环连接参数不能照搬为生产 TLS 配置。

本期未把七条来源文章导入目标功能、未迁移博客/菜单/作者关系，未实现业务权限、HTML 展示或旧 URL，也未生成新的平台受管候选。**不得据这些存储测试宣称文章迁移、权限或完整 MVP 已验收。**

实现依据：[MySQL 8.0 独立初始化](https://dev.mysql.com/doc/refman/8.0/en/data-directory-initialization.html)、[微秒精度](https://dev.mysql.com/doc/refman/8.0/en/fractional-seconds.html)、[JDBC 时间处理](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html)、[客户端登录文件环境变量](https://dev.mysql.com/doc/refman/8.0/en/environment-variables.html)。
