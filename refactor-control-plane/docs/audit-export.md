# 9C 审计导出

配置模式的项目 `AUDITOR` 可在集成工作台下载 JSON，或调用：

```text
GET /api/v1/audit/export?expectedPlanScope=<当前计划 SHA-256>[&candidateId=<精确候选 ID>]
Authorization: Bearer <项目凭据>
```

响应使用 `application/json`、附件文件名和 `Cache-Control: no-store`。凭据仅放请求头。其他项目或无审计角色返回 403；计划已激活但运行时未重启、完整性异常等返回 409；越界候选/未知参数返回 400；超出预算返回 413，均不返回部分报告。demo 不提供此接口。

## 关系与范围

`schemaVersion=refactor-control-plane.audit.v1`；`payload` 包含以下可对照记录：

| 记录 | 关联方式 |
|---|---|
| 计划、审批与修订元数据 | `plan.scope`，修订指纹、父指纹、架构/执行审批主体及时间 |
| 需求与 ADR | `requirements[].taskIds`、`architectureDecisions[].taskIds`；ADR 保留固定源码 blob 引用 |
| 任务、历史执行与提交 | 任务 ID、execution ID、base/commit SHA；任务保留当前语义版本、状态及门禁引用 |
| 人工操作 | 日志序号、状态、平台注入主体/角色；每条已完成 V2 操作保留当时投影的精确执行、任务版本和门禁引用 |
| 模型、工具、语义证据 | 不可变 evidence ID、完整声明绑定、执行器请求摘要、模型输入/上下文/输出摘要、请求工具与实际调用记录；语义资源及源码相对路径 |
| 候选、验证与人工建议 | 候选指纹和输入提交，最新 validation ID、各次验证及检查摘要、建议对应候选/提交/验证与人工主体 |

只导出当前加载计划 scope 的业务历史；修订表仅给跨版本元数据，不混入其他计划日志。PENDING/FAILED、旧执行、孤立证据和旧门禁均保留其实际状态。缺少平台注入主体时写 `UNKNOWN`。旧版非 V2 日志不虚构投影。

新增可选的计划字段：

```json
{"requirements":[{"id":"REQ-1","statement":"保留文章查询行为","taskIds":["CAT-1"]}]}
```

最多 100 条、ID 唯一、说明不超过 4000 字符、任务集合非空且必须存在。它参与原有计划指纹及双角色审批，修订后按既有流程重新审批。旧计划省略字段继续兼容，原指纹不被补写；`functionalParity` 仍是计划级业务对照，标为 `UNMAPPED`，不推断需求已被某任务实现。声明任务映射不证明功能已满足。

## 采信与只读边界

导出在运行时锁内读取专用纯快照，再在只读 REPEATABLE_READ 事务中读取数据库；不调用会清理租约的普通快照，不追加事件、修改门禁或创建建议，也不等待集成服务执行真实检查的 monitor。Git 校验是观察时事实，与数据库不是跨资源事务。

未选候选时 `selectedCandidate.eligibility=NOT_EVALUATED`。选定时检查当前 scope/kind、输入/目标/分支/profile、最新已完成 PASSED 验证及完整批准 checks，再重查 ACCEPTED 和当前人工门禁。新验证会使旧测试门禁失效；观察结果不是后续合并授权。建议仍须独立工程负责人通过原 API 创建，`automaticallyMergeMain=false`，平台不合并 main。

证据读取重验内容摘要。`TASK_EXECUTION_COMMIT_MATCHED` 只表示任务/执行/提交三项相符；历史语义绑定明确标为 `DECLARED_NOT_INDEPENDENTLY_REVERIFIED`，不可把它解读成当前门禁通过。当前门禁引用、当前任务执行引用、历史引用和孤立记录分开标记。模型保持 `EXECUTOR_REPORTED`，语义保持 `TASK_DECLARED_SOURCE_LINK`；平台没有据此独立证明模型调用或人工陈述真实。模型历史记录必须属于当前 scope，且其不可变回报与执行请求一致。

导出使用字段白名单，不包含令牌、工作树/仓库绝对路径、命令正文、stdout/stderr、原始错误或完整原始投影。检查保存命令及输出摘要。人工说明和计划/ADR 声明本身属于应审计内容，仍会导出。

解析前限制当前 scope 日志/集成/证据及修订元数据合计 2000 条、原始 CLOB 合计 8 Mi 字符；输出另有 8 MiB UTF-8 上限。超限整份拒绝，无静默截断或分页遗漏。

## 摘要复算

`integrity.payloadSha256` 对递归按 UTF-16 码元排序对象键后的紧凑 UTF-8 `payload` 计算，数组顺序保留，v1 指标及版本为整数；摘要不是签名。下载文件保留服务端原始 JSON 文本，避免浏览器重编码大整数。可独立运行：

```text
python -X utf8 scripts/verify-audit-export.py <审计文件.json>
```

验收场景与真实样本见 [9C 验收](acceptance/milestone9c-audit.md)。
