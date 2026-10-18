# 独立文章样例

此目录是可复制到受管任务工作树的独立样例。后端依赖 Spring Boot/JDBC，支持 H2 开发夹具和显式 MySQL profile，前端依赖 Vue；不依赖平台运行时、身份库或治理数据库。

- 后端：Java 21，`backend/pom.xml`，默认 `127.0.0.1:8081`。
- 前端：Node 22，`frontend/package-lock.json`，开发端口 `127.0.0.1:5174`，代理到样例 8081。
- 业务库：以样例后端为工作目录运行时使用 `backend/data/articles`。不能指向平台治理库。
- 用户已选择保留 MySQL。10A/10B1/10B2/10C 均已完成：归属隔离、HTML 清洗、精确稳定 URL、版本编辑/软删除、独立旧库迁移及实际受管候选验收，见平台 `docs/acceptance/milestone10-article.md`。H2 仍是开发夹具，完整 MVP 发布复验属于第 11 里程碑。

在平台根目录，已有项目离线依赖时运行：

```powershell
python -X utf8 scripts/article_sample.py verify --app samples/article-app
```

脚本使用项目 Maven 缓存；前端首次验证从相同锁文件的项目缓存复制依赖到样例本地，不下载依赖。独立使用时可在后端运行 `mvn verify`，在前端显式执行 `npm.cmd ci --cache .cache/npm`、`npm.cmd test`、`npm.cmd run build`。

启动后端（工作目录为 `backend`）：

```powershell
java -jar target/article-sample-0.1.0-SNAPSHOT.jar --article.accounts-file=<项目内账号文件的绝对路径>
```

启动前端（另一个终端，工作目录为 `frontend`）：

```powershell
npm.cmd run dev
```

没有默认管理账号。不指定账号文件时只提供匿名阅读；账号文件是 UTF-8 JSON 数组，每项仅允许 `subject`（字母/数字/下划线/连字符，最多 100）和 `passwordHash`（`{bcrypt}` 前缀的完整 bcrypt 散列）。在项目忽略的 `.runtime` 中保存实际文件；不要提交密码、账号配置或数据库凭据，不复用旧博客密码。散列由本地可信密码工具生成，例如以下结构中的占位符必须替换为完整散列：

```json
[{"subject":"author-local","passwordHash":"{bcrypt}<完整 bcrypt 散列>"}]
```

新目标库先由操作者明确配置博客到新账号的归属和菜单，不自动把所有文章分配给第一个账号。下面仅是新库的初始化例子，使用已在账号文件中配置的主体；已有同名记录会拒绝，不覆盖：

```sql
INSERT INTO article_blogs(owner_subject,blog_url) VALUES ('author-local','author-blog');
INSERT INTO article_menus(blog_id,menu_name)
SELECT id,'Anasayfa' FROM article_blogs WHERE blog_url='author-blog';
```

MySQL 8.0 目标采用 `schema-mysql.sql`，显式使用 `mysql` profile 和项目内连接配置，沿用 `application-mysql.properties` 要求的 URL/凭据。三个路径字段需要 `utf8mb4_0900_bin` 的精确且 NO PAD 比较。旧无归属 schema 会拒绝启动；保留原库，在新目标中显式迁移，不能删库或猜测所有者。H2 开发夹具为支持 255 个 Unicode 码点使用更宽的物理标题列，业务上限仍由服务端统一检查。

| 接口 | 行为 |
|---|---|
| `GET /api/session` | 当前会话、`csrfToken` 与 `csrfHeader`；不缓存 |
| `POST /api/session/login`、`/logout` | 同源会话；登录使用表单 `username/password`，两者都要求 CSRF，完成后重新读取会话令牌 |
| `GET /api/manage/menus`、`/articles`、`/articles/{id}` | 登录主体自己的菜单与未删除文章，管理响应含原文及版本 |
| `POST /api/manage/articles` | `title/content/status/menuId`；所属菜单必须属于当前主体 |
| `PUT /api/manage/articles/{id}` | `title/content/status/expectedVersion`；可带原 `menuId`，不可改归属或 URL |
| `DELETE /api/manage/articles/{id}?expectedVersion=N` | 软删除，不清理原文、菜单或 URL；过期版本 409 |
| `GET /api/articles`、`/api/articles/{id}` | 匿名已发布且未删除的安全投影，只含清洗后的 `html`，无原始 `content` |
| `GET /{blog}/{menu}/{slug}`、`/api/public/{blog}/{menu}/{slug}` | 相同精确旧格式路径的安全投影；Vue 开发服务器的旧路径页面由 `/api/public` 代理读取 |

页面在 `127.0.0.1:5174` 提供登录/管理/公开阅读。编辑正文保留原 HTML，只有服务端清洗结果可进入阅读区。未知写入需要刷新核对，不自动重发；软删除隐藏文章并保留记录。本地回环运行是当前边界；本批未做公网部署或浏览器端到端验收。

受管仓库流程见平台 `docs/project-plans.md`。初始化只从固定的本地旧仓库读取对象，保留其 MIT LICENSE；脚手架由任务执行者放入 `modern/article-app`，经平台组合验证、人工验收，再形成后续计划基线。

独立迁移入口：`java -jar <样例后端jar> --migration preview <配置.json>`，再以返回指纹执行 `--migration apply <配置.json> <fingerprint>`。不启动 Web 服务。必须显式指定独立 MySQL 源/目标、已有新账号文件、时区、目标状态和旧博客归属；不读取旧密码。映射和成功审计与文章同事务，目标编辑或软删除后不会被重试覆盖。完整配置与回滚/重试边界见平台 `docs/article-migration.md`。本 README 的接口表是独立样例契约；平台 `docs/contracts/articles.openapi.yaml` 只描述历史 demo。
