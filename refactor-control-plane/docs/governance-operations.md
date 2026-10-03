# 本地治理与真实 Git 执行

本轮升级覆盖核心治理和执行链。MCP 保留 15 个批准工具；人工操作走独立的 `POST /api/v1/governance/commands`。工作台的角色切换只改变展示，不授予服务端权限。

## 配置本地身份

默认没有启用的账号，治理接口返回 403。将账号配置放入本项目已忽略的 `.runtime/local-identities.json`，不要使用测试目录的固定凭据。每个账号需要独立的高熵 Bearer token；配置只存 token 的 SHA-256，不存明文。

在 PowerShell 7 中，为每个人生成独立 token 和摘要：

```powershell
$taskToken = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$taskHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($taskToken))).ToLowerInvariant()
# 将 token 单独交给对应使用者，将 taskHash 写入账号配置；不要提交凭据。
```

JSON 格式如下，`tokenSha256` 必须替换为生成的 64 位小写摘要。按需添加 `DEVELOPER`、`ARCHITECT`、`ENGINEERING_LEAD`、`QA_LEAD`、`BUSINESS_APPROVER` 账号；执行者与审批者必须是不同 subject。

```json
[
  {"subjectId":"manager","projectId":"legacy-blog","roles":["PROJECT_MANAGER"],"tokenSha256":"<manager 的 SHA-256>"},
  {"subjectId":"developer","projectId":"legacy-blog","roles":["DEVELOPER"],"tokenSha256":"<developer 的 SHA-256>"}
]
```

在 `backend` 目录启动，参数只作用于当前进程：

```powershell
mvn.cmd --% -Dspring-boot.run.arguments=--control-plane.identity-file=../.runtime/local-identities.json spring-boot:run
```

当前是本地账号实现，不是 OIDC。只有身份文件定义的项目成员可以接收分配；角色来自服务端配置，请求中的角色字段不能提升权限。

## 执行与审批

所有治理命令携带 `Authorization: Bearer <个人凭据>`。请求使用 JSON，包含 `action`、`taskId`、唯一 `idempotencyKey`、当前全局 `expectedVersion`。通过 MCP `get_integration_status` 获取全局版本；任务包的 `contextVersion` 是该次执行绑定的不可变语义版本，二者用途不同。

| 命令 | 服务端角色 | 额外参数 / 前置条件 |
| --- | --- | --- |
| `assign_task` | PROJECT_MANAGER | `assignee`；返回任务令牌，仅交给执行者。已有执行不能换人，同一人可重新领取凭据 |
| `start_review` | ENGINEERING_LEAD | `commitSha`；任务已 SUBMITTED |
| `record_gate` | 对应门禁审批角色 | `commitSha`、`gate`、布尔 `passed`、非空 `evidenceRef` |
| `start_testing` | QA_LEAD | `commitSha`；CODE_REVIEW 已通过 |
| `accept_task` | BUSINESS_APPROVER | `commitSha`；四类门禁全部通过 |
| `merge_proposal` | ENGINEERING_LEAD | `commitSha`；已验收且当前有效，仅生成人工合并建议 |
| `revalidate_task` | ARCHITECT | 语义失效或旧执行未验证；生成当前语义版本的新任务包 |
| `retry_task` | PROJECT_MANAGER | 当前有效且 BLOCKED / REJECTED；允许新的执行尝试 |

门禁对应角色：CODE_REVIEW → ENGINEERING_LEAD；UNIT_TEST / INTEGRATION_TEST → QA_LEAD；BUSINESS_ACCEPTANCE → BUSINESS_APPROVER。拥有审批角色的执行者也不能批准自己的任务。门禁失败使任务进入 REJECTED 并释放租约，项目经理可通过 retry_task 发起返工。证据引用是人工提供的追溯入口，本轮没有自动执行 CI 或验证外部证据内容。

分配示例：

```json
{"action":"assign_task","taskId":"WP-100","assignee":"developer","idempotencyKey":"assignment-001","expectedVersion":1}
```

执行者将返回的 `taskToken` 放入 MCP 写操作信封：

1. 用 `get_ready_tasks` 查询依赖已验收且有效的工作包，调用 `claim_task`。返回 `task.execution` 中真实的目录、分支、execution ID 和 base commit。
2. 对允许路径调用 `reserve_paths`，在返回的 worktree 中完成修改并提交到其任务分支。租约 execution ID 由服务端绑定，不能指定其他任务的执行身份。
3. 调用 `submit_changeset`，提供完整 40 位 `commitSha`。服务端读取真实 Git diff，要求 SHA 等于任务分支 HEAD、属于原仓库且继承绑定基线，所有路径在允许范围内且持有有效写租约。客户端 `changedPaths` 不能替代校验。
4. 由独立人员按表完成 Review、测试、业务验收与合并建议。每一步绑定同一提交，分支被推进后旧提交不能再获得新审批。平台不合并 main。

语义发布按依赖图传播失效，已 ACCEPTED 的历史状态保留，但 `currentlyValid=false`，旧门禁和合并资格立即撤销。重新验证或重试会撤销旧任务令牌；下一次领取使用新 branch/worktree，旧目录保留供人工检查，不自动删除或覆盖。

任务令牌不写入审计日志，重启后全部失效。项目经理可以用新的幂等键对同一 assignee 再次 `assign_task` 取得当前令牌。READY 阶段仍兼容预留路径；claim 切换到真实执行时释放这批临时预留，执行者需在新执行中重新申请租约。终态或失效任务不能申请或续期租约。

## 日志恢复与升级边界

- 写操作先持久化 PENDING，再执行；claim 的 execution ID 和 base SHA 在 Git 写入之前持久化。
- 完成结果包含版本 2 状态投影；只有数据库完成记录成功后，内存状态才对外生效。已完成记录按事实恢复，不重新执行 Git 或审批。
- 日志状态不确定时冻结后续写入，`get_integration_status.reconciliationRequired=true`；修复存储或 Git 边界后重启对账。不要删除日志、重置分支或覆盖未知目录。
- Git 创建部分成功时，重启复用同一个执行计划；遇到归属冲突停止对账，保留已有分支和目录。
- 过期心跳等已无法完成的 PENDING 操作进入 FAILED。FAILED 幂等键不会复用，确认原因后使用新键。
- 旧版已完成日志保留历史任务状态；旧提交和审批没有可信身份与 Git 绑定，因此标为未验证，必须重新验证后重新执行。旧语义版本的重复或回退只作为历史事实读取，新请求仍强制递增。

仍为单项目、单节点实现；多进程共同写同一数据库、后台自动对账、企业身份与 CI 集成不在本轮交付范围。
