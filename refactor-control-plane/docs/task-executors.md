# Mock 与 HTTP 任务执行器（8B）

执行边界采用已确认的 A：内网执行服务直接修改平台分配的任务工作树，平台持久化投递、查询同一执行 ID，并校验真实 Git 提交。模型网关仍只负责文本；任务执行器负责文件、检查与提交。平台不会自动合并 main。

## 项目配置与启动

配置模式的计划须已通过双角色确认，并包含 `scheduling` 容量配置、任务允许路径和 `taskProfiles` 验证方案。项目经理先分配负责人，负责人在工作台领取任务，再选择执行器启动；人工开发仍可使用 MCP。

在项目的 `.runtime/executors.json` 保存管理员配置（UTF-8，不提交真实凭据）：

```json
[
  {
    "id": "mock-demo",
    "kind": "MOCK",
    "outputs": { "src/executor-result.txt": "controlled mock output\n" }
  },
  {
    "id": "internal-worker",
    "kind": "HTTP",
    "baseUrl": "https://executor.internal/control",
    "token": "replace-with-project-secret",
    "timeoutSeconds": 10
  }
]
```

启动后端时传入 Spring 参数 `--control-plane.executors.file=<此文件绝对路径>`。默认未配置执行器，不会连接外部服务。配置在进程启动时读取；地址、类型或 Mock 输出变化后，历史执行需恢复原绑定才能查询，令牌可轮换。查询周期默认为 5000 毫秒，可用项目级启动参数 `--control-plane.executors.poll-ms=5000` 修改；`--control-plane.executors.auto-poll=false` 关闭自动查询。

HTTP 地址要求 HTTPS，仅本机 loopback 调试允许 HTTP；不跟随重定向。超时取 1–30 秒，限制完整响应为 64 KiB。配置文件最多 256 KiB、100 个执行器。工作台只展示 ID 和类型；请求票据、审计和状态响应不包含执行服务令牌或任务 MCP 令牌。

Mock 是确定性的真实执行夹具：在授权路径以 CREATE_NEW 创建配置文件，真实提交任务分支，再执行批准的检查，检查通过后确认 HEAD 及已跟踪文件未变化。已有文件、路径越界、工作树不洁或检查失败都会失败并保留现场。它不生成智能代码，也不覆盖已有文件。

## 平台接口

全部接口使用平台账号 Bearer 身份。项目成员可读，启动与查询远端状态仅允许本次任务负责人。

| 方法与路径 | 行为 |
|---|---|
| GET `/api/v1/executors` | 返回配置的 ID 与类型 |
| GET `/api/v1/executions/{id}` | 读取持久化运行记录，不访问执行服务 |
| POST `/api/v1/executions/{id}/dispatch` | 为已领取的本次执行准备票据并首次投递 |
| POST `/api/v1/executions/{id}/refresh` | 查询原执行并核对结果；无需请求体 |

投递正文严格为以下四个字段：

```json
{
  "taskId": "CAT-1",
  "executorId": "internal-worker",
  "expectedVersion": 1,
  "idempotencyKey": "dispatch-cat-1-unique-key"
}
```

运行记录包含 `request`、`state`、`result` 和 `observedAt`。`state` 为 UNKNOWN/RUNNING/SUCCEEDED/FAILED/STOPPED；`observedAt` 是平台最近持久化观察时间，UNKNOWN 的时间不代表执行器仍存活。工作台展示这些状态及检查摘要；浏览器结果不明时保留原请求，核对重试使用原键。

## 内网执行服务协议

平台发送 `PUT <baseUrl>/executions/<executionId>`，后续只发送 `GET` 同一路径，均带执行服务专用 Bearer token。服务必须持久化 execution ID 与 requestHash 的唯一绑定；同 ID、不同 hash 必须拒绝，相同 ID 不能启动第二个进程。平台只首次 PUT，不依赖重投来恢复。

PUT 的完整 Request 字段：

| 字段 | 含义 |
|---|---|
| `requestHash` | 平台生成的不可变票据 SHA-256；执行服务原样回传 |
| `planScope`、`contextVersion`、`assignmentEpoch` | 计划指纹、语义版本、负责人分配轮次 |
| `owner`、`executorId`、`executorFingerprint` | 负责人及管理员执行器绑定 |
| `execution` | `taskId`、`executionId`、`branch`、绝对 `path`、`baseCommit`、初始空 `commitSha` |
| `allowedPaths`、`forbiddenPaths` | 相对仓库根的写入授权范围 |
| `checks` | 已批准的检查：`id`、`argv`、`workingDirectory`、`timeoutSeconds` |
| `deadline` | 固定截止时间；执行服务必须到期停止运行，不得因查询而延长 |

执行服务须使用票据指定的共享工作树和分支，确认基线及授权范围，执行全部检查并持久化结果。截止预算是检查超时总和加 120 秒，至少 300 秒；只支持一天以内的预算。终态意味着工作进程及其子进程已经停止，不再修改工作树。

成功的 PUT/GET 返回同一 Result 结构，例如：

```json
{
  "executionId": "platform-assigned-id",
  "requestHash": "echo-the-exact-request-hash",
  "sequence": 2,
  "state": "SUCCEEDED",
  "commitSha": "actual-task-branch-commit-sha",
  "checks": [
    { "id": "diff", "exitCode": 0, "timedOut": false, "truncated": false }
  ]
}
```

远端状态只接受 RUNNING/SUCCEEDED/FAILED。序号是从 1 开始递增的 JSON 整数；同序号必须对应相同内容。RUNNING 可以没有 commitSha，checks 可为空。SUCCEEDED 必须带真实提交和恰好覆盖所有批准检查的成功结果。FAILED 表示确定停止，保留已有检查结果供排查。未找到执行可返回 404；202 无正文或其他非成功响应都只能让平台保留 UNKNOWN，不能证明未执行。

## 恢复与授权边界

1. 投递前先持久化票据及 UNKNOWN，并为全部允许路径取得独占租约，再在运行时锁外访问执行服务。首次投递在途时拒绝人工停止确认，避免停止后才启动旧任务。
2. 丢失响应、超时、404 或查询失联后只查询原 ID，保留容量。查询到相同序号且相同 RUNNING 结果可恢复心跳。期限到达、平台重启和租约失效均不证明执行器已停止。
3. 成功结果须匹配执行 ID、票据 hash、当前计划、版本、负责人及分配轮次，并通过真实 Git 基线、分支 HEAD、差异授权和批准检查结果校验。租约到期时只能为这份终态结果重新申请无冲突租约；竞争方持有的租约不被覆盖。平台受管执行不能绕过结果通道直接用 MCP 提交。
4. FAILED 释放本次容量及租约并撤销旧凭据；SUCCEEDED 转任务 SUBMITTED，后续仍需人工门禁。终态或人工确认停止后，迟到查询不会替换已记录状态，也不会污染下一次执行。
5. 平台重启恢复持久票据，但绝不重新 PUT。Mock 留在 RUNNING 的中断作业也不会自动重新执行；人工核对实际进程停止后，通过[停止确认](execution-scheduling.md)解除占用，再创建新执行。停止确认不发送终止信号。

当前实现面向单节点平台与可信内网执行服务，工作树不是操作系统沙箱。HTTP 检查结果是该服务的声明，平台额外校验真实 Git 结果；独立质量证据强化属于第 9 里程碑。使用本机 HTTP 工作者验证协议，不等于已对接企业真实开发智能体。8C 三执行器重叠运行及持久恢复已通过，[验收说明](acceptance/milestone8-execution.md)明确故障注入和数据库重开边界。
