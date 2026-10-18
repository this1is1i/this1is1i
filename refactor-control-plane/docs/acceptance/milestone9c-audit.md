# 第 9C：审计导出验收

日期：2026-10-18。9A、9B、9C 共同完成第 9 里程碑；第 10、11 里程碑及完整 MVP 仍待交付。协议与采信边界见[审计导出](../audit-export.md)。

## 已验证范围

| 场景 | 新鲜证据 |
|---|---|
| 需求/ADR 到真实执行、提交、平台测试和人工决策 | `GateEvidenceApiContractTest` 在固定本地 Git 仓库生成真实提交、组合候选、两次验证和四类门禁；报告包含声明映射、源码 blob、执行/提交、验证、人工建议和主体 |
| 精确候选和历史资格 | 新验证后旧测试门禁使报告资格为 NOT_ELIGIBLE；续签后为 ELIGIBLE_AT_OBSERVATION，人工建议仍 `automaticallyMergeMain=false`，main SHA 不变 |
| 只读与验证并发 | 导出前后运行时纯投影及集成 payload 字节相同；真实 Java 检查进程进入文件屏障后，导出在 5 秒限内返回 RUNNING，释放后真实验证 PASSED |
| 权限、scope、完整性 | 无身份、非审计角色、跨项目拒绝；旧计划指纹、跨 scope 候选/验证/模型引用拒绝或不具当前资格；篡改内容返回 409 且不泄露底层错误 |
| 历史追溯 | 同提交、不同执行的两条历史审批各自绑定当条投影；PENDING 与 UNKNOWN 主体保留；旧模型回报标为历史引用，模型请求工具不被冒充成实际调用 |
| 声明边界 | 孤立内容标 UNREFERENCED；错语义版本声明未被标成完整绑定通过；旧计划业务文字明确 UNMAPPED |
| 白名单和容量 | 令牌、绝对工作路径、模型/工具/门禁未知嵌套字段哨兵不进入报告；解析前记录数/CLOB 上限、中文导致 UTF-8 最终输出超限均整份拒绝 |
| 前端下载 | 仅内存凭据/请求头；保留原始 JSON 大整数；退出、刷新或候选切换屏蔽迟到响应；报告计划不匹配不保存 |
| 独立摘要 | 实际主链与模型样本由 Python 标准库独立递归排序、UTF-8 编码和 SHA-256 复算 |

主链实际样本：[milestone9c-audit-example.json](milestone9c-audit-example.json)。模型引用/请求工具边界样本：[milestone9c-model-example.json](milestone9c-model-example.json)。后者是同 scope 类型化执行回报夹具，真实 HTTP/Mock 模型调用证据仍来自 [9B](milestone9b-execution.md)，不把夹具升级为新企业模型连接证明。

逐类计数、实际 scope/执行/证据/候选 ID 和摘要保存在[9C 证据包](milestone9c-evidence.json)。

## 验证命令与计数

Windows 无 profile PowerShell，显式使用 `.cmd`。后端从 `backend` 执行：

```text
mvn.cmd --% -o -s ../scripts/maven-offline-settings.xml -Dmaven.repo.local=.m2-repository -Dtest=AuditExportApiContractTest,RequirementPlanContractTest,GateEvidenceApiContractTest,ProjectRevisionApiContractTest,ExecutionModelEvidenceContractTest,HttpExecutorModelTraceContractTest,EvidenceStoreContractTest verify -l ../.runtime/phase9c-final.log
```

首批为 17 项；随后只复验变化范围。最终按最新逐类报告合计 **19 个不同后端测试**、0 失败/错误/跳过，`verify` 打包成功：

| 类 | 项数 | 最新本地日志 |
|---|---:|---|
| AuditExportApiContractTest | 7 | `phase9c-export-final.log` |
| GateEvidenceApiContractTest | 1 | `phase9c-final-recheck.log` |
| ProjectRevisionApiContractTest | 1 | `phase9c-final.log` |
| RequirementPlanContractTest | 1 | `phase9c-final.log` |
| ExecutionModelEvidenceContractTest | 1 | `phase9c-final.log` |
| HttpExecutorModelTraceContractTest | 4 | `phase9c-final.log` |
| EvidenceStoreContractTest | 4 | `phase9c-final.log` |

前端从 `frontend` 执行 `npm.cmd test -- tests/audit-export.test.ts tests/integration-workbench.test.ts tests/model-receipts.test.ts tests/app-workbench.test.ts`：**22 项通过**。`npm.cmd run build` 包含类型检查及生产构建，成功。

红灯依据位于 `.runtime/phase9c-entry-red.log`、`phase9c-boundaries-red.log`、`phase9c-history-red.log`、`phase9c-model-history-red.log`、`phase9c-gate-reference-red.log`；分别确认入口缺失、跨 scope/嵌套白名单、历史执行关联、旧模型分类及历史门禁未知字段缺陷。均已修复后复验。`.runtime` 为本机忽略目录；仓库保留上面的实际导出样本，可重新独立复算。

未重新进行浏览器端到端交互或文章全量验收。本期验证配置模式单节点审计，历史语义声明不是平台独立证明；跨计划业务历史聚合和企业模型连接未纳入本期。
