# 执行、模型与语义变更证据（9B）

平台沿用已批准的共享工作树 HTTP 执行边界。执行服务负责模型与文件操作；平台校验真实 Git 结果、保存执行服务回报，并独立运行批准的组合验证。模型摘要不赋予测试通过或人工验收资格。

## HTTP 模型回报

成功结果可增加 `modelRuns`。省略或 `null` 表示未报告，空数组表示执行服务报告未使用模型，不能把历史缺失值解释为零调用。每次成功执行最多 20 条；只接受成功终态的非空模型回报。

```json
{
  "id": "model-call-1",
  "model": "enterprise-model",
  "promptVersion": "migration-v1",
  "inputHash": "<完整模型请求的 SHA-256>",
  "contextHash": "<源码上下文 SHA-256>",
  "outputHash": "<模型输出 SHA-256>",
  "totalTokens": 42,
  "requestedTools": ["read_file", "write_file"],
  "toolCalls": [
    {"id":"tool-call-1","name":"read_file","inputHash":"<SHA-256>","outputHash":"<SHA-256>","state":"SUCCEEDED"}
  ]
}
```

`inputHash` 必须覆盖提示词版本、指令、上下文及请求工具元数据，不能只复制 `contextHash`。当前 `ModelAudit.contextHash` 仅覆盖 `codeContext`。哈希采用 UTF-8；执行服务应固定完整请求的序列化方式。

`requestedTools` 只描述请求元数据；`toolCalls` 只能由执行服务记录已发生的调用，每条含唯一 ID、名称、输入/输出摘要和 SUCCEEDED/FAILED 状态。没有轨迹时提交空列表，平台不会根据请求清单生成调用记录。每个列表最多 50 条，工具名最多 100 字符，token 是精确非负 32 位整数，拒绝小数与溢出。模型 ID、提示词版本最多 100 字符，模型名称最多 200 字符；摘要为小写 64 位十六进制。

HTTP 响应仍限制 64 KiB；写日志前另检查加入平台元数据后的完整模型证据是否超过 64 KiB。未知字段、重复调用 ID、非法数值和超限内容不得产生 PENDING 操作。

成功提交后，服务端生成 `EXECUTOR_MODEL_RUNS` 不可变证据，绑定真实提交、任务、执行、计划和语义版本；内容还绑定投递请求哈希、执行器配置指纹、负责人及分配轮次。运行响应新增 `modelEvidenceRef`，项目成员沿用 `/api/v1/evidence/{id}` 鉴权读取。执行读取、工作台和集成输入会重新检查完整性；重复终态回报保留同一证据引用。

证据来源固定为 `EXECUTOR_REPORTED`：它证明平台收到并保存了哪份声明，不独立证明外部模型实际运行或工具报告真实。源码、指令、模型输出正文和密钥不进入该回报。HTTP 执行器成功摘要仍不能替代平台测试门禁。

## 契约与 Schema 的源码关联

配置模式的 `publish_contract_change` / `publish_schema_change` 增加必填条件：

- `taskExecutionId` 与 `commitSha` 必须匹配当前已提交执行。
- `resourceIds` 是任务在批准计划中声明的对应 API_CONTRACT / DB_SCHEMA 资源，1–20 个。
- `artifactPaths` 是该提交相对固定基线真实改动的规范仓库路径，1–20 个；不能引用未修改的文件或其他执行的产物。

原 `assetVersion` 与 `affectedTaskIds` 保留。服务端将声明的影响任务、资源的全部使用任务以及下游依赖闭包合并，避免执行者漏报共享资源的使用者。发布前追加 `SEMANTIC_SOURCE_CHANGE` 证据，返回 `sourceEvidenceRef`；事件中保留执行、提交与证据引用。旧 demo 保留版本发布兼容，不能用作配置模式的源码关联证明。ADR 发布接口本期不改变。

这里记录的是 `TASK_DECLARED_SOURCE_LINK`：平台验证资源授权、真实提交和改动路径，资源与文件的语义对应仍需 Review。它不自动理解任意契约或 DDL 的兼容性。

## 验收与限制

`ExecutorConflictAcceptanceTest` 使用本机 HTTP 工作者实际调用 MockModelGateway，应用返回内容、执行批准检查并提交：

- 模型证据先落库而 journal 尚未完成，以及完成已落库但响应丢失，两种边界重启后均不得重复调用模型、PUT 或提交。
- 同路径租约阻断额外投递；两个真实提交的 Git 组合留下文本冲突。
- 相同契约/Schema 版本、不同文件的任务各自检查通过，组合树中的真实 Java 行为检查失败，人工建议被拒绝。
- 实际契约/DDL 提交关联语义发布，使真实已提交/待回报执行和旧候选失效；重启后旧结果与建议仍被拒绝。

测试生成 `backend/target/phase9b-evidence/*.json` 保存场景身份与提交。文件 H2 重开及故障注入不等于操作系统断电耐久性认证；本机 Mock 模型不代表已接入企业模型或开发智能体。完整审计角色导出属于 9C，文章业务等价属于第 10 里程碑。
