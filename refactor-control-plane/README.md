# Refactor Control Plane

面向大型企业遗留系统重构的治理与协作控制平面。它把“项目理解 → 架构决策 → 工作拆分 → 隔离执行 → 冲突分析 → 质量验收”连接成可追踪闭环；人工开发者、企业模型与闭源智能体只是可替换执行器。

当前仓库是可运行的 v0.1 参考实现，核心治理链、分析快照、本地文章数据迁移与接口契约已升级；真实旧库兼容与工作台真实性仍有待完成项，见[当前状态](docs/status.md)。它不会自动合并 `main`，不会把源码发往公网模型，也不替代企业的身份平台、代码托管或 CI/CD。

## 已实现能力

面向非技术评审与赞助沟通，参见[项目介绍与阶段成果（2026-10-11）](docs/project-introduction-for-sponsors.md)，其中区分已实现能力、验证证据及后续交付。

- 从固定 Git tree/blob 读取不可变快照，识别 Maven/npm 声明和源码类型，绑定提交与对象证据，区分事实、推断和未知；识别范围见[分析快照说明](docs/analysis-snapshots.md)。
- 为文章管理迁移生成 7 个带依赖、资源、允许路径和验收命令的工作包，三个实现任务可并行。
- 每个执行创建独立 `codex/task-*` branch 与 worktree；重复请求幂等，创建中断后只在分支仍精确指向原始基线时补全元数据，主分支不被推进。
- 可在批准计划中配置执行者并发容量和环境槽位；容量不足阻断领取，未知执行保留占用，独立项目经理确认停止后释放。配置与边界见[执行调度说明](docs/execution-scheduling.md)。
- `READ`、`SHARED_WRITE`、`EXCLUSIVE_WRITE` 路径租约，支持 TTL、服务端单调时钟、心跳、版本条件和大小写策略。
- 追加式 Change Event、API/Schema 变更失效传播、越权路径拒绝与审计。
- 代码 Review、单元/集成/业务验收四类门禁；配置模式采信平台实际验证与独立人工说明，绑定精确执行、提交及不可变证据，验收前重查内容与时效；全部通过后只产生人工合并建议。见[证据门禁说明](docs/evidence-store.md)。
- Mock 模型网关与 OpenAI-compatible 企业 HTTP 网关处理文本请求；模型审计保存上下文/输出哈希，不保存密钥或源码正文。
- Mock/HTTP 任务执行器接通真实工作树修改、检查和提交；工作台支持启动与状态查询，结果未知时保留容量、只查询原 ID。配置和协议见[任务执行器](docs/task-executors.md)；[三执行器并发与恢复验收](docs/acceptance/milestone8-execution.md)已通过。
- Integration AI 冲突报告，包括路径、API Contract、DB Schema、影响任务和建议顺序。
- MCP Streamable HTTP JSON-RPC 入口，提供规格批准的 15 个工具及具体输入/输出 Schema；写操作强制 task token、幂等键和预期版本，日志写入前校验参数，见[工具契约](docs/contracts/project-mcp.md)。
- H2 项目级审计/操作日志，使用数据库权威幂等键、单调 journal sequence、请求/结果日志与版本条件推进；重启按执行全序恢复任务、语义版本和未过期租约，并完成可安全重放的 `PENDING` 操作；随机任务令牌不落库。
- Vue 3 八角色工作台和一个可运行的文章管理迁移切片（H2 持久化 Spring Boot CRUD、OpenAPI、启动时执行的非破坏性 SQL、Vue 3 增删改查视图）。
- 旧 `posts` 本地快照预检/迁移，显式时区与状态、原字段与 ID 映射、事务和幂等审计；遇到目标编辑/删除保留现状。操作与未覆盖范围见[文章迁移说明](docs/article-migration.md)。

## 架构

```text
Vue 3 Role Workbenches
          │ REST / MCP
Spring Boot Control Plane
          ├─ Analysis & Planning
          ├─ Workflow / Quality Gates
          ├─ Ownership & Lease Registry
          ├─ Git Worktree Manager
          ├─ Model Gateway
          ├─ Integration Conflict Analyzer
          └─ H2 Audit / Operation Journal
                    │
        task branch + isolated worktree
                    │
             Human review only
                    │
                   main
```

代码隔离和语义协作是两套机制：Git worktree/branch + lease 防止覆盖，Shared Context + Change Event + MCP 传播 API、Schema、ADR 和依赖影响。

## 环境要求

- Java 21
- Maven 3.9+
- Node.js `>=22.12 <23`
- npm 10+
- Git（须支持 `--no-lazy-fetch`；本期验证 2.49.0）

所有运行数据、依赖缓存和样例均位于项目目录；无需修改系统或用户级配置。

## 首次启动

通用目标仓库可使用[项目级可执行计划](docs/project-plans.md)：配置固定基线及任务，完成架构师和项目经理确认后执行。以下步骤继续保留原文章 demo，不能替代独立样例应用的交付验收。

先初始化固定版本的公开 MIT 样例：

```powershell
cd refactor-control-plane
pwsh -NoProfile -File .\scripts\bootstrap-sample.ps1
```

Linux/macOS 使用 `bash ./scripts/bootstrap-sample.sh`。脚本会校验样例 HEAD 必须是 `5e62608af333b5757bf767e05b19e2b0d56f0afb`。

启动后端：

```powershell
cd backend
mvn.cmd spring-boot:run
```

后端地址：

- 项目概览：`http://127.0.0.1:8080/api/v1/demo/overview`
- 文章 API：`http://127.0.0.1:8080/api/articles`
- MCP：`http://127.0.0.1:8080/mcp`
- 健康检查：`http://127.0.0.1:8080/actuator/health`

另开终端启动前端：

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

打开 `http://127.0.0.1:5173`。Vite 会把 `/api` 和 `/mcp` 代理到本地后端；连接成功前界面明确显示“静态演示”。

## MCP 示例

人工账号配置、任务令牌签发、真实 Git 提交和审批顺序见[本地治理操作说明](docs/governance-operations.md)。默认没有启用账号；任务令牌不能用于审批。`submit_changeset` 现在要求真实 `commitSha`，不信任客户端文件列表。

初始化：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
```

读取工具目录：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

写工具参数必须包含治理信封：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "report_change_event",
    "arguments": {
      "taskToken": "<由可信调度器带外签发的随机任务令牌>",
      "idempotencyKey": "全局唯一操作键",
      "expectedVersion": 1,
      "taskId": "WP-120",
      "type": "AgentStatusChanged"
    }
  }
}
```

## 企业模型网关

`OpenAiCompatibleModelGateway` 调用企业内网的 `/v1/chat/completions`，构造时必须提供精确的企业网关主机白名单；非白名单地址会在任何请求发出前被拒绝，非回环地址还必须使用 HTTPS。API key 仅进入 `Authorization` 请求头，不进入模型请求对象、任务包、返回对象或审计记录。生产接入时应从企业密钥管理服务注入构造参数，禁止提交 `.env` 或密钥文件。

离线开发和测试使用 `MockModelGateway`，不会产生网络请求。

## 验证

后端契约测试也使用 Node 22，独立检查 OpenAPI/MCP 模式中的正则能被 JavaScript 客户端解析。

```powershell
cd backend
mvn.cmd verify

cd ..\frontend
npm.cmd ci
npm.cmd test
npm.cmd run typecheck
npm.cmd run build
```

Windows 沙箱环境如需项目内 Maven 缓存：

```powershell
mvn.cmd --% -Dmaven.repo.local=.m2-repository verify
```

关机脚本的安全决策测试：

```powershell
cd ..
pwsh -NoProfile -File .\scripts\tests\power-off-decision.tests.ps1
```

## 明确完成任务后关机

这里刻意不使用常驻 watcher、可伪造 marker、系统计划任务、服务或全局配置。只有用户明确说出完整口令“做完任务关机”后，执行流程才可直接调用一次性脚本；脚本会重新检查本项目目录无未提交变更，并确认当前 `HEAD` 与 upstream 完全同步：

```powershell
pwsh -NoProfile -File .\scripts\power-off-after-task.ps1 -ConfirmationPhrase '做完任务关机' -Verified -Pushed
```

精确口令、验证确认、推送确认、干净项目和远端同步缺一不可。普通退出、超时、额度变化、近似表达和项目内文件均不能触发它。脚本默认预留 60 秒取消窗口；安全演练不会调用 `shutdown.exe`：

```powershell
pwsh -NoProfile -File .\scripts\power-off-after-task.ps1 -ConfirmationPhrase '做完任务关机' -Verified -Pushed -DryRun
shutdown.exe /a
```

脚本没有跳过脏工作树或未同步远端检查的参数。本次交付只运行决策测试；由于提交前项目必然是脏状态，不会执行真实关机或伪造通过结果。

## 目录

```text
backend/                 Spring Boot 控制平面、MCP、H2 审计（demo 保留历史文章 API）
frontend/                Vue 3 / TypeScript 计划审批与八角色集成工作台
docs/                    产品规格、架构、OpenAPI、验收标准和证据
samples/catalog/         固定样例来源、许可证与 SHA
samples/article-app/     独立 Vue/Spring Boot 文章脚手架与业务库
samples/workspaces/      本地样例（脚本生成，不提交）
scripts/                 可复现的项目级初始化脚本
```

## 安全与权限边界

- 智能体只可修改任务包允许路径，禁止路径优先于允许路径。
- Project MCP 的写操作要求由可信调度器带外分发的随机 task token、幂等键和预期版本；MCP 读取接口不返回令牌。
- 模型输出必须形成任务 patch/branch；Integration AI 的结果也不能更新 `main`。
- H2 适用于本地 MVP；企业部署应替换为受管数据库并接入 OIDC/LDAP、集中审计和组织级密钥管理。
- 对外部署前必须在反向代理或服务网格实施 TLS、身份认证、速率限制和网络隔离。

## 权威文档

- [产品规格 v0.1](docs/product-spec-v0.1.md)
- [核心架构与并发模型](docs/architecture/core-architecture.md)
- [MVP 验收基线](docs/acceptance/mvp-acceptance.md)
- [MVP 验收证据](docs/acceptance/v0.1-evidence.md)
- [当前状态](docs/status.md)
- [多任务集成与本机组合验证](docs/integration-candidates.md)
- [真实集成工作台操作](docs/integration-workbench.md)
- [项目计划、修订审批与组合基线](docs/project-plans.md)
- [独立文章样例启动](samples/article-app/README.md)
- [文章行为对照与待确认项](docs/article-parity-checklist.md)
- [新执行者接手入口](AGENTS.md)

## 许可证

本项目代码尚未声明公共开源许可证。样例 `SpringMvcBlog` 使用 MIT License，其来源与固定提交记录在 `samples/catalog/springmvc-blog.yaml`；样例源码不随本仓库提交。
