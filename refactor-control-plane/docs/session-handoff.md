# 新会话开发交接

更新时间：2026-10-18。项目目录：`D:/AAWorkSpeace/this1is1i/refactor-control-plane`。

## 从哪里继续

第 7、8 里程碑和 **9A、9B 均已完成**。下一阶段是 **9C 审计导出**。不要重做 9A/9B，也不要将第 9 里程碑或完整 MVP 标记为完成。

先读本项目 `AGENTS.md`、[当前状态](status.md)、[开发计划](development-plan.md)，再按需读[执行证据](execution-evidence.md)、[9B 验收](acceptance/milestone9b-execution.md)、[证据门禁](evidence-store.md)和[执行器协议](task-executors.md)。旧阶段记录属于历史证据，以最新状态为准。

## 已确定的三项 A 选择

- 第 7 里程碑：独立样例应用及数据库，平台调度验收；样例在 `samples/article-app`，配置模式不注册文章业务接口或初始化业务表。
- 第 8 里程碑：内网 HTTP 执行服务直接操作平台分配的工作树，通过执行身份查询进度与结果。
- 第 9A：测试门禁只采信平台实际执行的验证；代码评审与业务验收由对应独立人工角色提交说明。无需再次询问 A/B，不加入外部 CI 签发者和验签范围。

用户要求加快进度、避免过度冗余测试。按实际改动做最小必要的失败反例和定向回归；通过后不无故重复。只修改项目级配置，不修改全局环境或工具设置。平台只能生成人工合并建议，不能自动合并 main。

## 9A 已交付与关键边界

- `EvidenceStore` / `evidence_record` 追加保存不可变 JSON，绑定计划、任务、精确执行、提交及语义版本；内容寻址、无损数值规范化、幂等冲突与读取完整性检查。
- `GateEvidencePolicy` 使用当前候选及最新平台验证；人工证据使用本人说明。HTTP 成功摘要、外部 URL 或“测试通过”文字均不能代替平台测试。
- 配置模式 `record_gate` 要求 `taskExecutionId` 和 `commitSha`；服务端生成权威版本绑定，执行者不能自批。开始测试、验收、合并建议及候选基线准入重新核对有效证据。
- 门禁证据引用进入运行时投影并随日志恢复。新验证会使旧测试证据失效；ACCEPTED 可更新通过证据，TESTING 可续签已有通过的代码评审说明。
- 构造器恢复 PENDING 命令前安装集成约束，查询显式计划 scope，避免服务构造顺序造成单任务合并建议绕过组合验收。
- 工作台支持说明/平台验证两类输入及证据 ID 展示，`GET /api/v1/evidence/{id}` 提供当前项目/计划鉴权读取。旧 demo 保留自由文本兼容，不能作为 9A 验收证明。

实现入口（相对项目根）：

| 关注点 | 文件 |
|---|---|
| 不可变内容、绑定和摘要 | `backend/src/main/java/io/refactorcontrolplane/governance/EvidenceStore.java` |
| 可信验证及门禁采信 | `backend/src/main/java/io/refactorcontrolplane/integration/GateEvidencePolicy.java` |
| 执行、审批、投影及恢复 | `backend/src/main/java/io/refactorcontrolplane/mcp/McpProjectRuntime.java` |
| 候选验证、合并建议与存储 | `backend/src/main/java/io/refactorcontrolplane/integration/IntegrationService.java`、`IntegrationStore.java` |
| 证据读取 | `backend/src/main/java/io/refactorcontrolplane/api/EvidenceController.java` |
| 工作台 | `frontend/src/integration/workbench.ts`、`IntegrationWorkspace.vue` |
| 9A 核心回归 | `backend/src/test/java/io/refactorcontrolplane/api/GateEvidenceApiContractTest.java`、`backend/src/test/java/io/refactorcontrolplane/governance/EvidenceStoreContractTest.java` |

## 最近验证结果

2026-10-18 后端最新逐类结果共 **32 项**通过，`verify` 打包成功；前端 **20 项**相关测试、类型检查及生产构建通过。后端分两批 24/16 项，重复项不累加；最后只补充语义重新验证 2 项并通过。日志为 `.runtime/phase9b-final.log`、`phase9b-final-recheck.log`、`phase9b-revalidation-final.log`。六场景身份摘要在 `docs/acceptance/milestone9b-execution-evidence.json`，覆盖本机 HTTP/Mock 模型、真实 Git、不可变证据、冲突/行为拒绝、持久恢复、语义重新验证和 8C/9A 回归。

反例日志：`.runtime/phase9a-execution-red.log`（旧执行 ID 审批原先错误放行）、`.runtime/phase9a-recovery-red.log`（PENDING 合并建议恢复原先绕过集成守卫）。两者已修复并纳入最终通过结果；独立复核无剩余阻塞。

未做新一轮浏览器端到端验收，未重跑文章或执行器全量测试。运行时重建不是断电或多节点耐久性认证。`.runtime` 为本地忽略目录，日志存在于当前工作区，不能假设新克隆中也存在。新会话读取文档无需重跑上述全部测试；后续改变相关实现时再选受影响检查。

## 下一阶段范围与入口

9B 已将模型回报和真实执行持久关联：`ExecutionModelEvidence` / `ExecutorModelRun`、`ExecutionProtocol`、`McpProjectRuntime`，工作台为 `ModelReceipts.vue`。配置模式契约/Schema 发布用 `resourceIds`、真实 `artifactPaths`、执行 ID 与提交生成证据，并由计划补齐资源使用者。所有证据入口在日志前复用 EvidenceStore 纯校验。模型来源是可信执行服务声明；平台不直接驱动 ModelGateway，也没有已验证的企业模型/开发智能体连接。请求工具和实际调用记录必须保持区分。

9C 提供审计角色可用的需求/ADR → 任务 → 执行/模型/语义证据 → 提交 → 验证 → 人工决策导出，并复验精确候选只生成人工建议。优先复用现有证据和投递/journal，不能把 HTTP 声明升级为平台独立证明。第 10 里程碑的数据库升级策略尚待用户选择，富文本、旧 URL、权限等业务差异按 `docs/article-parity-checklist.md` 确认。

## 工作区与运行注意事项

- 当前分支 `main`；第六期至 9A 的保存提交为 `ed298f1`，9B 为 `2b8c34c`。用户已明确批准保存到 `https://github.com/this1is1i/this1is1i.git` 的 `main`；6 个提交已推送并核对远端 SHA 一致。该授权已解决先前自动审批阻断，不重复询问同一保存操作；后续读取真实 Git 状态。保留未跟踪和父目录无关改动。
- 父目录如有无关用户改动必须保留；保存成果只暂存本项目路径，不把父工作区文件混入提交。
- Windows PowerShell 自动化使用 `login:false` / 无 profile，文本显式 UTF-8。精确传参用 `npm.cmd`、`mvn.cmd`；Maven `-D` 参数按状态文档使用 `--%`，本地依赖仓库为 `backend/.m2-repository`。
- 测试和构建曾因沙箱阻止 Vite/Maven 写项目产物而需要命令级权限；只为必要的项目构建申请最小范围权限，不改系统策略。遇到超时先查日志、进程及产物状态，不盲目重复执行。

新会话可直接使用：

> 请阅读 refactor-control-plane/AGENTS.md 和 docs/session-handoff.md，从已批准计划的 9C 继续开发；9A/9B 已验收且成果已推送，沿用三项 A 决策，保留用户改动，避免冗余测试。用户已明确批准当前仓库/main 的成果保存，不重复询问相同授权。
