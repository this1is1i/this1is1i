# 第 11 里程碑：v0.1 MVP 发布验收

验收日期：2026-10-19。第 7–11 里程碑退出条件已完成，v0.1 单项目、单节点 MVP 验收通过。范围以[已批准规格](../product-spec-v0.1.md)和[12 项基线](mvp-acceptance.md)为准，生产接入不包含在此结论内。

本轮重新执行全部平台测试、实际候选文章测试及角色浏览器流程。版本化[机器证据](milestone11-evidence.json)、[浏览器下载的审计原件](milestone11-audit.json)、[平台截图](milestone11-platform.png)和[文章截图](milestone11-article.png)一并保存；原始运行日志和报告在忽略目录 `.runtime/phase11-full`、`.runtime/phase11-browser`，新克隆不会包含这些运行目录。

## 本轮结果

| 检查 | 新鲜结果 | 本地原始证据 |
|---|---|---|
| 平台后端完整 `verify` | 40 类 / **202 项**，零失败、错误、跳过；打包成功 | `phase11-full/backend.log`、`backend-reports/TEST-*.xml` |
| 平台前端 | 9 个测试文件 / **37 项**通过，类型与生产构建通过 | `phase11-full/frontend-tests.json`、`frontend/dist` |
| Python 脚本边界 | 最终 **20 项**通过，含发布夹具 5 项；不执行电源管理脚本 | `phase11-full/python-tests-release.log` |
| 精确文章候选 | 实际 MySQL **21 项**，另 **6 项 H2**；独立 Vue **16 项**及构建通过 | 候选验证 `ec9d32c9-6b72-40bb-b483-24808569696f` |
| 启动入口 | 配置模式 health/MCP 正常，业务接口 404；独立 demo health、overview、15 个 MCP 工具正常 | `phase11-browser/capture.json`、`phase11-full/demo-retry.log` |
| 退出及复核 | 平台、文章、候选 MySQL、浏览器 MySQL、修复后新夹具均已关闭；审计摘要复算通过 | 两个 `sample-stopped.json`、各 MySQL manifest `STOPPED` |

不把不同引擎上的重叠测试、历史测试或不同层的测试数量累加成“功能总数”。本轮后端一次完整运行成功，耗时 13:32；Maven 的编译插件兼容性提示未产生构建失败。

环境为 Java 21.0.11、Maven 3.9.2、Node 22.20.0、npm 10.9.3、MySQL 8.0.31。MySQL 使用项目内新 datadir 和独立进程，无现有服务或全局设置变更。浏览器运行包的应用类和资源已与全量构建包逐项比对相同。

## 12 项验收映射

以下均来自本轮新报告。机器证据中的 `baselineAssertions` 保存精确类名和测试方法，`backend.suites` 保存全部 202 项方法及逐类报告摘要。

| 基线 | 本轮证据及结果 |
|---|---|
| MVP-01 | `LegacyAnalysisAndPlanContractTest`、`AnalysisTrustContractTest`、`ArchitectureRelationsContractTest`：固定旧 Git 对象、技术栈/模块/配置/路由/视图关系和源证据通过；浏览器显示 57 条架构关系。 |
| MVP-02 | 固定文章迁移 DAG、资源/路径/验收契约、双角色批准及修订测试通过；浏览器先架构师确认、再项目经理批准，随后分配。 |
| MVP-03 | `ExecutorConcurrencyAcceptanceTest` 的三个真实执行器重叠运行、实际修改/检查/提交及持久恢复通过，独立工作树不推进 main。 |
| MVP-04 | `PathLeaseRegistryContractTest`、`SchedulingContractTest` 和真实 HTTP 冲突场景通过：不兼容路径/容量拒绝额外执行，明确原因。 |
| MVP-05 | `ExecutorConflictAcceptanceTest` 的实际 API/Schema 改动、下游失效、重启及重验证两种参数场景通过，旧结果不能污染新执行。 |
| MVP-06 | 实际越权 HTTP 提交、旧凭据及路径授权回归通过，拒绝提交并保留失败审计。 |
| MVP-07 | 同路径真实 Git 文本冲突、不同文件/相同版本但组合行为失败场景通过，保留影响任务和建议顺序，拒绝建议。 |
| MVP-08 | 同一受管候选运行实际 MySQL 和独立 Vue 测试；固定七行迁移的原文、URL、ID/归属、时间、回滚和编辑/软删除保护通过；浏览器功能见下文。 |
| MVP-09 | `GateEvidenceApiContractTest`：缺失、篡改、过期、错误执行或自批在发布边界被拒绝；浏览器测试门禁绑定精确候选最新验证。 |
| MVP-10 | 精确组合、独立主体和全部门禁重新检查通过；浏览器生成建议并验收任务，审计记录 HUMAN_ONLY；样例 main 前后相同。 |
| MVP-11 | `IntegrationGitRecoveryTest`、`IntegrationWorkflowTest`、`McpGovernanceBoundaryContractTest`、执行器恢复测试：中断/未知结果/引用及目录竞争反例通过，不覆盖外部胜者。 |
| MVP-12 | `AuditExportApiContractTest` 的权限、历史执行、模型、scope、容量和只读回归通过；浏览器审计主体下载原件，独立 Python 复算摘要通过。 |

## 浏览器实际流程

平台使用全新配置仓库和治理库，固定旧基线 `5e62608af333b5757bf767e05b19e2b0d56f0afb`。架构师、项目经理、开发人员、独立工程负责人、测试负责人、业务验收人、审计员均以各自测试凭据操作；八角色页面及角色内容另有前端自动化覆盖。侧栏角色选择不授予服务端权限。

1. 架构师确认架构/ADR，项目经理批准执行、将 `SCAFFOLD` 分配给 `developer`。
2. 开发人员在页面领取，服务端创建独立工作树；CLI 夹具在该工作树生成实际文章代码，经 Git 提交和 MCP 提交变更。
3. 开发人员即使兼有工程负责人角色，其自己的候选创建复选框仍禁用；独立 `lead` 生成候选。
4. `qa` 在页面运行组合验证，实际候选执行 MySQL/Vue 检查；`lead` 提交评审说明，`qa` 进入测试并将单元/集成门禁绑定同一验证 ID。
5. 独立文章应用从该候选产物启动，源七篇文章迁移到新的业务库。匿名可读七篇旧文章及含土耳其文编码的旧 URL；`author-one` 只见所属六篇/两个菜单，`author-two` 只见所属一篇/一个菜单。
6. 本轮新增临时文章 #8 并发布，修改标题/HTML 正文后为版本 2、原地址不变，页面保留标题和加粗排版；软删除该新文章后公开/管理列表隐藏，原七篇仍在。
7. `business` 记录本轮观察说明并验收；`lead` 生成人工合并建议；`auditor` 选择精确候选并下载 JSON，页面显示下载成功。

所有凭据和说明明确属于测试夹具。这证明角色隔离、证据采集及门禁机制，不冒充真实生产人工评审或业务签署。

| 绑定 | 本轮值 |
|---|---|
| 计划 scope | `41cbcb50239b4a85b9c7dadc6b0cfca2c6927c580bdde35ae3d1f78b92078481` |
| 执行 ID | `6917caa2-9cf3-4ddc-887f-4a6d34723a58` |
| 任务提交 | `e4e84c3d9d80160e6281e34c5232dc4067168736` |
| 候选 ID | `246a5e89-8fa5-4be7-8cfb-0c8b9bb7c25b` |
| 候选提交 | `638c8073a0508d7938548c693146fac64949b3fd` |
| 最新验证 | `ec9d32c9-6b72-40bb-b483-24808569696f` |
| 审计 payload SHA-256 | `db819e6ca1504f27665a4086bee1f5b9c3859f7d5fa037b9ffab56b72fbe73ee` |
| 样例 main 前后 | 均为 `5e62608af333b5757bf767e05b19e2b0d56f0afb` |

审计原件包含本轮需求、决策、任务、执行、验证和人工说明；该手工执行夹具没有模型调用，模型链由本轮真实 HTTP 执行器/模型回报测试独立覆盖，不能在原件中伪造一条模型运行。

## 复现与失败处理

完整命令、缓存准备、浏览器操作及关闭步骤见[发布演示手册](../release-demo.md)。核心命令如下（项目根；PowerShell 无 profile）：

```powershell
cd backend
mvn.cmd --% -o -s ../scripts/maven-offline-settings.xml -Dmaven.repo.local=.m2-repository -Dcontrol-plane.test.reports=../.runtime/phase11-full/backend-reports -DskipTests=false -Dmaven.test.skip=false verify -l ../.runtime/phase11-full/backend.log
cd ../frontend
npm.cmd test -- --pool=threads --reporter=default --reporter=json --outputFile=../.runtime/phase11-full/frontend-tests.json
npm.cmd run build
cd ..
python -X utf8 -m unittest discover -s scripts/tests -p test_*.py
python -X utf8 scripts/verify-audit-export.py docs/acceptance/milestone11-audit.json
git diff --check
```

复验应选择新的报告和夹具目录，不能覆盖本轮原始证据；第一次运行前先创建报告父目录。离线参数仅适用于项目缓存已完整的环境。

独立复核发现并修复：代理启动失败后遗漏文章子进程清理；启动异常被误记为 `sampleStopped:true`。当前真实成功路径已使用另一个新 MySQL 夹具复验启动/关闭，五项脚本反例通过。

**已知运行边界**：Windows MySQL 的 launcher PID 和 server PID 不同。若启动不完整、SQL 身份或优雅关闭无法核实，夹具保留文件及 `cleanup-unknown.json`，写 `UNCONFIRMED` / `sampleStopped:null` 并报错停止；不会强杀 launcher 后宣称数据库已关闭，也不会自动重试。此类故障需核对 manifest、datadir、端口和进程后人工处理，不承诺任意部分启动都能自动清理。本轮实际实例均已正常关闭，没有这一未决状态。

仍保留 v0.1 边界：本地测试身份、H2 治理存储和进程内协调，尚无生产 OIDC/LDAP、多节点/企业部署、任意语言语义等价或客户数据库切换认证。下一阶段需按真实试点项目确定范围。
