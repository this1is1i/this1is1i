# Project MCP 工具契约

协议握手仍为 `2025-03-26`。`tools/list` 的每个工具返回具体 `inputSchema`，并提供本项目的 `outputSchema` 扩展；执行成功仍在 `result.structuredContent` 返回业务对象，`content` 保留文本摘要。依赖仅接受旧协议字段的客户端应只使用标准输入描述；本期未升级协议协商。

权威定义为 `backend/src/main/java/io/refactorcontrolplane/mcp/McpToolSchemas.java`，`tools/list` 直接使用同一注册表，避免静态文档与运行时副本漂移。输出 Schema 在真实 HTTP 读操作及 Git/租约写入回归中校验；不会在写操作已提交后再以 Schema 失败伪装为未执行。

## 通用输入

所有参数对象拒绝未知字段。无参数读工具允许省略 arguments，其余必须提供 object。写工具均要求非空字符串 `taskId, taskToken, idempotencyKey` 及正整型 `expectedVersion`；幂等键最长 200 个 Unicode 码点。任务、令牌、版本、路径与角色的业务授权继续由治理层校验。

H2 幂等键列扩展为 400 个 UTF-16 单元以容纳 200 个补充平面字符；现有键与唯一性保持不变。

| 工具 | 其他输入 | structuredContent |
|---|---|---|
| get_project_context | 无 | projectId、analysis、plan、mergePolicy、dataBoundary |
| get_ready_tasks | 无 | tasks、contextVersion |
| get_task_package | taskId 必填 | 任务版本、路径、状态、有效性；存在执行时含 execution |
| get_dependency_impact | 无 | criticalPath |
| get_integration_status | 无 | contextVersion、activeLeases、events、reconciliationRequired、合并策略 |
| claim_task | 无 | task、contextVersion |
| reserve_paths | ownerId、resourcePath 必填；mode、ttlSeconds、taskExecutionId 可选 | accepted、lease（拒绝时 null）、conflicts |
| heartbeat | leaseId、leaseVersion 必填；ttlSeconds、taskExecutionId 可选 | 更新后的租约 |
| release_paths | leaseId、leaseVersion 必填；taskExecutionId 可选 | released |
| publish_contract_change | assetVersion、affectedTaskIds 可选 | published、affectedTaskIds、contextVersion |
| publish_schema_change | 同上 | 同上 |
| record_architecture_decision | 同上 | 同上 |
| report_change_event | type 必填；TaskStatusChanged 还要求 targetStatus | recorded、type、contextVersion，以及 task 或 taskId |
| submit_changeset | commitSha 必填；changedPaths 为废弃兼容字段，忽略其授权意义 | accepted、unauthorizedPaths |
| report_blocker | 无 | blocked、task |

租约 mode 为 `READ, SHARED_WRITE, EXCLUSIVE_WRITE`。TTL 只接受 1–86400 秒的整数，缺省 300。leaseId 为标准 UUID 字符串，leaseVersion 为正整数。客户端可提供 taskExecutionId，但必须与服务器绑定一致。`role, gate, passed` 仅兼容识别旧 report_change_event 审批尝试，不能提升权限，实际审批仍要求认证人工身份。

## 错误与验证

- `-32602`：未知工具、参数形状/类型/字段/范围不符合契约。
- `-32001`：写请求缺少有效 taskToken、idempotencyKey 或正 expectedVersion。
- `-32002`：身份、令牌或授权拒绝。
- `-32003`：领域状态、版本、幂等或租约冲突。

参数 Schema 在日志记录之前校验，数字和布尔值不当作文本，字符串/小数不当作版本整数。验证器仅实现本项目实际使用的 Schema 子集（object/array/string/integer/boolean/null、required、properties、additionalProperties、enum、长度、pattern、最小/最大值），不是通用 JSON Schema 引擎。

正则使用 Java/ECMAScript 兼容语法；`McpSchemaContractTest` 调用项目 Node 脚本独立编译所有已发布模式。后端契约验证因此也需要项目声明的 Node 22 运行时。Schema 给出了数据结构，不替代真实 Git、角色、租约、版本或人工审批门禁。
