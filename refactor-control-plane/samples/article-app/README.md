# 独立文章样例

此目录是可复制到受管任务工作树的独立样例。后端依赖 Spring Boot/JDBC，支持 H2 开发夹具和显式 MySQL profile，前端依赖 Vue；不依赖平台运行时、身份库或治理数据库。

- 后端：Java 21，`backend/pom.xml`，默认 `127.0.0.1:8081`。
- 前端：Node 22，`frontend/package-lock.json`，开发端口 `127.0.0.1:5174`，代理到样例 8081。
- 业务库：以样例后端为工作目录运行时使用 `backend/data/articles`。不能指向平台治理库。
- 用户已选择保留 MySQL；10A 新库 DDL、真实引擎存储和时间精度基线已验证，见平台 `docs/acceptance/milestone10a-mysql.md`。H2 仍是开发夹具。权限、富文本、稳定 URL 及独立迁移工具仍待 10B，当前 CRUD 脚手架不代表业务等价或可公开部署。

在平台根目录，已有项目离线依赖时运行：

```powershell
python -X utf8 scripts/article_sample.py verify --app samples/article-app
```

脚本使用项目 Maven 缓存；前端首次验证从相同锁文件的项目缓存复制依赖到样例本地，不下载依赖。独立使用时可在后端运行 `mvn verify`，在前端显式执行 `npm.cmd ci --cache .cache/npm`、`npm.cmd test`、`npm.cmd run build`。

启动后端（工作目录为 `backend`）：

```powershell
java -jar target/article-sample-0.1.0-SNAPSHOT.jar
```

启动前端（另一个终端，工作目录为 `frontend`）：

```powershell
npm.cmd run dev
```

受管仓库流程见平台 `docs/project-plans.md`。初始化只从固定的本地旧仓库读取对象，保留其 MIT LICENSE；脚手架由任务执行者放入 `modern/article-app`，经平台组合验证、人工验收，再形成后续计划基线。
