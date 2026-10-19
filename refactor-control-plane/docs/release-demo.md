# v0.1 发布演示与复现

本手册运行独立受管项目、独立文章应用和项目内 MySQL 夹具。验收结果见[第 11 里程碑](acceptance/milestone11-release.md)。仅使用测试身份和固定 MIT 样例，不接入现有业务库或操作系统服务。

## 准备

项目根为 `refactor-control-plane`，使用 Java 21、Maven 3.9+、Node `>=22.12 <23`、npm 10+、Python 3.11+、Git 和已有 MySQL 8.0 二进制。脚本不安装或修改全局工具；PowerShell 使用无 profile 模式，npm/Maven 明确使用 `.cmd`。Linux 可使用对应本地可执行文件路径。

先按 [README](../README.md) 的固定样例初始化命令准备 `samples/workspaces/springmvc-blog`；脚本后续只读取本地固定提交，不抓取远端。已有完整项目依赖缓存时，按第 11 里程碑命令执行全部构建和测试。新环境先在项目 `backend/.m2-repository` 准备 Maven 依赖；使用项目 `scripts/maven-online-settings.xml`，不写用户级 Maven 设置。前端在各自目录执行 `npm.cmd ci --cache ../.npm-cache`，再运行测试与构建。

受管候选还需独立样例依赖。首次可执行以下命令预热项目缓存：

```powershell
cd samples/article-app/backend
mvn.cmd --% -s ../../../scripts/maven-online-settings.xml -Dmaven.repo.local=../../../backend/.m2-repository verify
cd ../frontend
npm.cmd ci --cache ../.npm-cache
npm.cmd test
npm.cmd run build
cd ../../..
```

默认 H2 测试只用于预热/兼容回归，不能替代候选的真实 MySQL 测试。根后端/前端也须先按 README 构建。遇到依赖下载问题保留实际错误，不能把空/旧报告作为通过证据。

## 启动受管平台

在项目根新终端运行（替换本机二进制位置，目录名每次新建）：

```powershell
python -X utf8 scripts/release_preview.py serve --case .runtime/release-demo-001 --java "C:/Program Files/Java/jdk-21.0.11/bin/java.exe" --mysqld "D:/Database/MySQL/MySQL Server 8.0/bin/mysqld.exe" --mysql "D:/Database/MySQL/MySQL Server 8.0/bin/mysql.exe"
```

打开输出的 `platformUi`。平台/API 和静态代理仅监听 `127.0.0.1`，端口随机；地址保存在该 case 的 `urls.json`。此命令保持运行，关闭页面不代表停止服务。禁止重用已有 case 或修改其已批准计划文件。

本地测试凭据为 `architect-test-token`、`manager-test-token`、`developer-test-token`、`lead-test-token`、`qa-test-token`、`business-test-token`、`auditor-test-token`。它们只在该夹具的身份文件中有效，不能作为生产账号配置。侧栏选择角色不会改变实际权限。

## 角色操作

1. 项目计划连接 `architect` 凭据，确认架构及 ADR；换 `manager` 凭据批准执行计划。工作台单独连接 manager，把 `SCAFFOLD` 分配给 developer。
2. 工作台退出后连接 developer，点击“领取并创建工作树”。确认已领取后，在第二终端执行：

   ```powershell
   python -X utf8 scripts/release_preview.py materialize --case .runtime/release-demo-001
   ```

   该命令仅在已领取的工作树生成代码、Git 提交并经 MCP 提交一次。结果不明时先查工作台、`submitted.json` 和 Git 状态，不自动重发。
3. 切换独立 lead，选择已提交任务和配置的 `sample-build`，生成候选。记录页面完整候选 ID；developer 的自批入口应禁用。
4. 切换 qa，选择候选并运行组合验证。真实检查包含 MySQL 21 项、另 H2 6 项及 Vue 16 项/构建。等待“通过”和退出码 0，不以按钮点击代替完成。
5. lead 开始评审并填写明确标记为夹具的说明后通过代码门禁。qa 选择相同候选、进入测试，核对最新验证 ID 后记录单元和集成门禁。
6. 完成下方文章浏览器检查后，business 填写夹具业务说明、通过业务门禁并验收任务。
7. lead 对精确候选生成人工合并建议；auditor 选择候选、下载审计 JSON。用 `verify-audit-export.py` 复算保存原件，核对 main 仍为固定旧提交。平台不会执行 merge。

测试说明不是生产人工签署。可在完整后端回归中复现路径/容量拒绝、API/Schema 失效、越权提交、文本/行为冲突、未知写入和并发竞争，具体方法见验收文档的基线映射；不要在成功夹具里随意修改批准配置以制造失败。

## 启动通过验证的文章候选

在第三终端运行，`--candidate` 使用本次页面所见完整 ID：

```powershell
python -X utf8 scripts/release_preview.py sample --case .runtime/release-demo-001 --candidate <本次候选ID> --java "C:/Program Files/Java/jdk-21.0.11/bin/java.exe" --mysqld "D:/Database/MySQL/MySQL Server 8.0/bin/mysqld.exe" --mysql "D:/Database/MySQL/MySQL Server 8.0/bin/mysql.exe"
```

此命令确认候选当前有效且最新验证通过，从其实际打包产物启动，另建新的 MySQL/datadir。只读迁移七篇源文章，显式采用 Europe/Istanbul、PUBLISHED 和两个新测试账号映射；不复制旧密码。新 `sampleUi` 写入同一 `urls.json`。

打开 `sampleUi` 检查匿名阅读、旧地址导航。测试账号 `author-one` / `author-two` 的密码均为 `browser-fixture-only`，仅用于本轮回环夹具：前者管理博客 3 的六篇/两个菜单，后者管理博客 8 的一篇/一个菜单。

只新增临时验收文章，发布后编辑标题/HTML，确认 URL、菜单和博客不可变，再软删除该临时文章，确认公开和管理列表隐藏。保留原七篇以核对来源；换账号验证归属隔离。清洗、CSRF、CAS、软删除后迁移重试保护由候选真实测试覆盖。

## 关闭与异常

在第四终端写入该夹具的停止标记：

```powershell
python -X utf8 scripts/release_preview.py stop --case .runtime/release-demo-001
```

等待平台和文章两个终端退出码均为 0；检查 `stopped.json` 的 `platformStopped:true`、`sample-stopped.json` 的 `sampleStopped:true`，及 `sample-database/manifest.json` 的 `STOPPED`。只保存证据，不删除工作树或数据库；下次用新 case。

若 SQL 身份或优雅关闭失败，脚本报错并保留 `cleanup-unknown.json` 的 `UNCONFIRMED`，关闭结论为 `null`。Windows mysqld launcher 和真正 server 可能有不同 PID，不得仅结束 launcher 就认定全部退出。按 manifest/datadir/端口/进程读取实际状态后再人工处理，禁止自动重试、复用目录或无备份删除。

`.runtime` 中含随机数据库凭据、测试会话和本地路径，不能整体提交到 Git。只提交经检查的测试审计原件、无敏感内容摘要和截图；内容哈希不等于数字签名或人工陈述真实性证明。
