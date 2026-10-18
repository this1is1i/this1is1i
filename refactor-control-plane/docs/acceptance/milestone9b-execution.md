# 9B 执行、冲突与模型证据验收

范围：已批准配置计划、可信本机 HTTP 执行服务、真实 Git 分支和固定基线、平台组合验证。模型使用本机 MockModelGateway；不宣称已连接企业模型或开发智能体。

## 验收场景

| 场景 | 检查与证据 |
|---|---|
| 模型关联 | HTTP worker 实际调用模型、使用返回内容修改文件、检查并提交；证据绑定当前执行、真实 SHA、计划、请求和执行器指纹 |
| 工具真实性边界 | 请求包含两个工具，只记录真正执行的一个工具；请求清单不会自动变为调用轨迹；来源为 EXECUTOR_REPORTED |
| 两种持久完成故障 | 证据已写但 journal 未完成，以及 journal 完成响应丢失；文件 H2 重开后保持同一证据、一次 PUT、一次模型调用和一个任务提交 |
| 内容与引用保护 | 改写证据正文拒绝读取；保留引用而移除模型列表仍拒绝；终态重复回报不丢失证据 ID |
| 内容限额 | 模型证据、合法大计划的语义证据超限在 PENDING 前拒绝；还覆盖 HTTP 正好 65536 字节、加平台元数据后超限 |
| 同路径冲突 | 后到执行被租约阻断，HTTP 投递数不增加；释放后得到两个真实分支提交，组合形成 CONFLICTED 并阻断验证 |
| 不同路径行为冲突 | 同语义版本下，各任务检查通过、Git 组合成功；组合 Java 检查发现行为不兼容，验证 FAILED，人工建议拒绝 |
| 契约/Schema 变更 | 真实契约/DDL 提交生成 SEMANTIC_SOURCE_CHANGE；漏报的资源使用者仍被自动纳入影响；错误路径和旧执行拒绝 |
| 语义失效与恢复 | 已提交及待回报的真实执行进入 STALE；H2 重开后旧结果、旧候选及人工建议仍拒绝；确认旧执行停止后重新验证并实际提交新执行，旧结果不能覆盖；main 不变 |

场景实现为 `ExecutorConflictAcceptanceTest`；协议与数值边界由 `HttpExecutorModelTraceContractTest`、`ExecutionModelEvidenceContractTest` 覆盖。已有执行器 API、MCP Schema/恢复、证据存储、9A 门禁及 8C 并发作为受影响回归。工作台 SSR 用例验证来源说明、未报告/空报告区别，以及请求工具与实际记录分开显示。

## 复现

在 `backend`，使用项目缓存和无 profile PowerShell：

```powershell
mvn.cmd --% -o -s ../scripts/maven-offline-settings.xml -Dmaven.repo.local=.m2-repository -Dtest=ExecutorConflictAcceptanceTest,ExecutionModelEvidenceContractTest,HttpExecutorModelTraceContractTest,TaskExecutorApiContractTest,McpSchemaContractTest,McpRuntimeRecoveryContractTest,EvidenceStoreContractTest,ExecutorConcurrencyAcceptanceTest,GateEvidenceApiContractTest verify -l ../.runtime/phase9b-reproduce.log
```

在 `frontend`：

```powershell
npm.cmd test -- tests/model-receipts.test.ts tests/integration-workbench.test.ts tests/app-workbench.test.ts
npm.cmd run build
```

前端 build 同时执行类型检查。部分沙箱会阻止 JVM loopback 或 Vite 临时 SSR 文件，须使用命令级项目构建权限；不修改全局环境或安全策略。最终本轮命令与结果见[当前状态](../status.md)。

## 身份与限制

验收运行生成 `backend/target/phase9b-evidence/*.json`，留存模型恢复、文本冲突、行为冲突、契约和 Schema 场景的执行、提交、候选或证据身份，且各场景断言 main 与基线相同。可提交摘要副本位于本目录的 `milestone9b-execution-evidence.json`；本机完整日志位于 `.runtime`，不是克隆产物。

证据摘要不证明外部模型或工具陈述真实。语义资源到文件的关联是已认证任务的声明，平台验证真实 diff 和授权，不据此承诺自动识别业务语义。文件 H2 重开不是断电耐久性证明；完整审计角色导出留在 9C，业务等价在第 10 里程碑，最终全链验收在第 11 里程碑。
