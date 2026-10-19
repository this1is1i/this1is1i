# 新会话开发交接

更新时间：2026-10-19。项目目录：`D:/AAWorkSpeace/this1is1i/refactor-control-plane`。

## 从哪里继续

第 7–11 里程碑已完成，**v0.1 单项目、单节点 MVP 验收通过**。先读[最终发布验收](acceptance/milestone11-release.md)、[版本化证据](acceptance/milestone11-evidence.json)及[演示手册](release-demo.md)，不要重做已完成阶段。后续真实试点/生产身份、多节点或客户库切换需用户确定目标，不从“继续”推导无限扩展博客或企业部署。

先读本项目 `AGENTS.md`、[当前状态](status.md)、[开发计划](development-plan.md)，再按需读[执行证据](execution-evidence.md)、[9B 验收](acceptance/milestone9b-execution.md)、[证据门禁](evidence-store.md)和[执行器协议](task-executors.md)。旧阶段记录属于历史证据，以最新状态为准。

## 已确定的三项 A 选择

- 第 7 里程碑：独立样例应用及数据库，平台调度验收；样例在 `samples/article-app`，配置模式不注册文章业务接口或初始化业务表。
- 第 8 里程碑：内网 HTTP 执行服务直接操作平台分配的工作树，通过执行身份查询进度与结果。
- 第 9A：测试门禁只采信平台实际执行的验证；代码评审与业务验收由对应独立人工角色提交说明。无需再次询问 A/B，不加入外部 CI 签发者和验签范围。

用户要求加快进度、避免过度冗余测试。按实际改动做最小必要的失败反例和定向回归；通过后不无故重复。只修改项目级配置，不修改全局环境或工具设置。平台只能生成人工合并建议，不能自动合并 main。

2026-10-18 追加批准：保留 MySQL；按博客归属隔离（所属用户管理、匿名只读已发布）；保存原 HTML 但白名单展示；旧 URL 稳定且改标题不变；可编辑标题/正文/状态但归属和 URL 不变。用户随后明确选择软删除，已在 10B1 实现；不重复询问。完整选项见[决定记录](article-business-decisions.md)。

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

## 最近验证结果（第 11 里程碑）

2026-10-19 平台后端完整 **202 项 / 40 类**、前端 **37 项**、Python 最终 **20 项**及构建通过；受管文章候选实际 MySQL **21 项**、另 H2 **6 项**、Vue **16 项**及构建通过。12 项基线均有本轮精确测试方法/报告摘要映射，不与旧阶段数量累加。浏览器已实际操作双审批、分配/领取、真实提交、候选/验证、四项门禁、业务验收、人工建议、审计下载和独立文章功能；八角色页面另有自动化覆盖。

本轮候选 `246a5e89-8fa5-4be7-8cfb-0c8b9bb7c25b`、提交 `638c8073a0508d7938548c693146fac64949b3fd`、验证 `ec9d32c9-6b72-40bb-b483-24808569696f`。浏览器下载审计原件可复算摘要；测试身份不代表生产签署。平台/文章/MySQL 实际夹具均正常关闭，样例 main 保持固定旧提交。脚本入口为 `scripts/release_preview.py`；未知 MySQL 关闭结果保留 UNCONFIRMED/null 并停止，不能强杀 launcher 后冒充数据库退出。

原始日志在忽略目录 `.runtime/phase11-full`、`.runtime/phase11-browser`；新克隆只包含版本化摘要、审计原件和截图。后续改动仅补受影响检查，无需因读文档重复全量测试。

## 以前阶段的验证记录

9C 最新逐类结果共 **19 项后端、22 项前端**通过，后端 `verify`、前端类型/生产构建成功。覆盖审计鉴权、scope、历史精确执行、模型声明、平台门禁、旧证据失效、只读、真实阻塞验证并发及容量；日志与逐类计数见 [9C 验收](acceptance/milestone9c-audit.md)。9B 的 32 项及两个语义重验证场景属于上一阶段，不与本期重叠计数累加；旧证据包继续保留。

反例日志：`.runtime/phase9a-execution-red.log`（旧执行 ID 审批原先错误放行）、`.runtime/phase9a-recovery-red.log`（PENDING 合并建议恢复原先绕过集成守卫）。两者已修复并纳入最终通过结果；独立复核无剩余阻塞。

以上为第 9 里程碑历史记录；第 11 里程碑已补齐浏览器、文章及执行器全量复验。运行时重建不是断电或多节点耐久性认证。

## 下一阶段范围与入口

9B 已将模型回报和真实执行持久关联：`ExecutionModelEvidence` / `ExecutorModelRun`、`ExecutionProtocol`、`McpProjectRuntime`，工作台为 `ModelReceipts.vue`。配置模式契约/Schema 发布用 `resourceIds`、真实 `artifactPaths`、执行 ID 与提交生成证据，并由计划补齐资源使用者。所有证据入口在日志前复用 EvidenceStore 纯校验。模型来源是可信执行服务声明；平台不直接驱动 ModelGateway，也没有已验证的企业模型/开发智能体连接。请求工具和实际调用记录必须保持区分。

9C 已交付：[导出协议](audit-export.md) 和 [验收/真实样本](acceptance/milestone9c-audit.md)。实现入口为 `AuditExportService`、`AuditExportController`、`runtime.auditSnapshot()` 和工作台 `exportAudit()`。需求映射是批准计划内声明，历史语义绑定明确未独立重验；不得把导出摘要视为签名、HTTP 回报视为平台证明或历史审批视为当前资格。

第十里程碑独立迁移入口为 `LegacyArticleMigration` / `ArticleMigrationCommand`，`java -jar ... --migration preview|apply`，配置与边界见[迁移说明](article-migration.md)。CLI 复现入口 `scripts/verify_milestone10.py governed`；必须新建项目内夹具目录，不能重用已存在数据目录。第 11 里程碑浏览器复现入口另见最新验收，不重复询问已批准决定。

## 工作区与运行注意事项

- 当前分支 `main`；第六期至 9A 的保存提交为 `ed298f1`，9B 为 `2b8c34c`。用户已明确批准保存到 `https://github.com/this1is1i/this1is1i.git` 的 `main`；6 个提交已推送并核对远端 SHA 一致。该授权已解决先前自动审批阻断，不重复询问同一保存操作；后续读取真实 Git 状态。保留未跟踪和父目录无关改动。
- 父目录如有无关用户改动必须保留；保存成果只暂存本项目路径，不把父工作区文件混入提交。
- Windows PowerShell 自动化使用 `login:false` / 无 profile，文本显式 UTF-8。精确传参用 `npm.cmd`、`mvn.cmd`；Maven `-D` 参数按状态文档使用 `--%`，本地依赖仓库为 `backend/.m2-repository`。
- 测试和构建曾因沙箱阻止 Vite/Maven 写项目产物而需要命令级权限；只为必要的项目构建申请最小范围权限，不改系统策略。遇到超时先查日志、进程及产物状态，不盲目重复执行。

新会话可直接使用：

> 请阅读 refactor-control-plane/AGENTS.md、docs/session-handoff.md 和 docs/acceptance/milestone11-release.md。第 7–11 里程碑及单节点 v0.1 MVP 已验收通过，不重复实施；后续先明确真实试点目标，沿用全部已批准业务决定。保留用户改动，避免冗余测试；当前仓库/main 的成果保存已经授权，不重复询问。
