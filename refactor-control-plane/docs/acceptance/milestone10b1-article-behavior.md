# 10B1：文章行为与软删除

日期：2026-10-18。独立样例 `samples/article-app` 已完成本批行为；10B2 独立旧库迁移工具、10C 平台受管验收仍待完成，第 10 里程碑与 MVP 不因此关闭。

## 交付行为

- 本地账号从显式 JSON 文件加载 bcrypt 散列，无默认账号、旧密码迁移或平台身份库依赖。会话登录/退出保留 CSRF，登录后重新获取令牌；匿名仅阅读已发布且未删除的内容。
- 管理查询和所有写入按博客所属主体校验。新增必须指定自己的菜单；标题、原 HTML 和状态可编辑，菜单/博客/slug 不可通过编辑改变，未知字段拒绝。编辑和软删除按版本比较，过期版本返回 409。
- 软删除只设置 tombstone 并推进版本，保留原始正文、文章 ID、菜单、博客、slug 和地址占用；管理列表/详情、公开列表/详情和旧路径均隐藏。不新增恢复、清理或旧评论级联行为。迁移映射与删除重试的对照留给 10B2。
- 公开 DTO 不返回原始 `content`，使用 jsoup 白名单清洗后提供 `html`；保留常见排版、图片与链接，去除脚本、事件和危险协议。Vue 只将公开端点的 `html` 绑定到 `v-html`，编辑区保留原文。正文上限 1 MiB，标题按 Unicode 码点限制 255。
- 新文章使用与标题无关的 UUID slug；已有 slug 改标题后不变。三段路径大小写和尾随空格精确匹配，MySQL 三个路径字段使用 `utf8mb4_0900_bin`，唯一键同样不折叠尾随空格。启动拒绝无归属的旧 schema 和不满足精确路由语义的 MySQL schema，不推测旧数据的所有者。
- 页面接通登录、自己的菜单/文章、版本编辑、软删除和公开阅读。迟到读取/预览不能恢复已隐藏内容；会话不确定时隐藏私有数据，草稿暂存于内存，仅在同一主体重新确认后恢复。未知写入不自动重发，需刷新核对并明确确认。

## 新鲜验证

| 验证 | 本轮结果 | 范围 |
|---|---:|---|
| 实际 MySQL 8.0.31 | **20 项通过，零失败/错误/跳过** | API 4、存储 3、真实引擎 4、行为 7、匿名会话 1、真实登录 1 |
| H2 开发夹具 | **17 项通过** | API 4、存储 3、行为 7、会话 1、登录 1、旧 schema 拒绝启动 1；与 MySQL 重叠，不累加业务覆盖数 |
| Vue 模型及 SSR | **16 项通过** | 登录/CSRF/版本、身份及预览乱序、未知结果、选择变化、在途编辑/新增接续等 |
| Python | **11 项通过** | MySQL 夹具边界 6、样例计划/嵌套工作树 5 |
| 产物 | 后端 `verify`、前端类型与生产构建通过 | 无平台核心变更，无本轮浏览器端到端声明 |

机器证据见 [MySQL 摘要](milestone10b1-mysql-evidence.json)。Maven 总报告还有一项独立 H2 schema guard，**不计为第 21 项 MySQL 测试**。测试由配置绑定的独立报告目录采信，保留实际引擎、非默认 JVM 时区和固定来源七行只读对照。最终实例 `.runtime/phase10b1-mysql-accepted` 已核对 datadir/端口后关闭，状态 `STOPPED`；没有修改现有 MySQL 服务、治理库或全局配置。

主要命令（平台根目录；Windows 自动化禁用 profile）：

```powershell
python -X utf8 scripts/mysql_sample.py --mysqld "D:/Database/MySQL/MySQL Server 8.0/bin/mysqld.exe" --mysql "D:/Database/MySQL/MySQL Server 8.0/bin/mysql.exe" --destination .runtime/phase10b1-mysql-accepted
python -X utf8 -m unittest discover -s scripts/tests -p test_mysql_sample.py
python -X utf8 -m unittest discover -s scripts/tests -p test_article_sample.py
```

MySQL 命令要求**新的**目录，不复用上述已有数据。H2 日志 `.runtime/phase10b1-h2-accepted.log`；前端在 `samples/article-app/frontend` 运行 `npm.cmd test` 和 `npm.cmd run build`。Vitest 在本项目使用 threads pool，避免 Windows 沙箱 fork 进程不能共享系统临时转译文件；未更改全局临时目录或工具设置。

## 失败反例与复核

先观察无归属 schema、未知字段、码点标题/正文边界及旧 schema 拒绝启动的红灯，再实现。独立评审发现 MySQL PAD SPACE 折叠路径；`.runtime/phase10b1-route-red/maven.log` 明确出现期望 404、实际 200。根因修复到字段 collation 和唯一键，新增三段路径、旧 slug 和可区分尾随空格的唯一键对照，最终真实 MySQL 通过。公开预览与会话不确定读取的两个前端反例也先失败再修复；复核无剩余重要问题。

## 后续边界

本批的旧 slug 是明确构造的旧格式夹具，不等于固定来源七篇文章已经迁移。10B2 必须实现独立源读取、预检指纹、显式时区/状态及博客到账号映射、原时间与旧新 ID/关联映射、原文对照、幂等重试与事务回滚；目标已编辑或软删除时不得覆盖或复活。旧用户密码、全博客和评论产品重写不在本批范围。10C 再将结果进入真实受管任务、候选验证、独立门禁和审计导出。

实现依据：[Spring Security 6.5 CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)、[jsoup 1.23.2](https://jsoup.org/news/release-1.23.2)。
