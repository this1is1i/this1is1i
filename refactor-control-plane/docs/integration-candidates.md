# 多任务集成候选与本机组合验证

本期把真实任务提交合成一个可审查的 Git 候选，在该候选的独立工作树中执行项目配置的命令。所有参与者仍通过原任务审批链完成 Review、测试门禁和业务验收；最后输出人工合并建议，不更新目标分支。

## 三层冲突处理

| 层次 | 检查依据 | 结果与处理 |
| --- | --- | --- |
| 修改面 | 实际提交 diff、执行租约、任务依赖闭包 | 同路径写租约互斥；候选展示 PATH 重叠预警。路径重叠不必然等于文本冲突。 |
| 静态集成 | 绑定的 API/Schema 版本、Git `merge-tree` | 版本不一致或真实文本冲突产生 `CONFLICTED`，记录任务与路径；由任务负责人返工。 |
| 组合行为 | 精确候选提交上的项目配置命令 | 编译、接口测试或行为断言失败即 `FAILED`；不同文件之间的不兼容可在这里暴露。检测能力取决于配置的检查。 |

候选要求完整依赖闭包，包括已经验收但尚未合并的前置任务；不能把“已验收”当成“已进入目标分支”。执行顺序按 DAG 排序，同层按任务 ID 排序。最多 20 个任务。

## 项目配置

默认禁用本机验证。先在本项目 `.runtime/integration-checks.json` 配置可信命令，再在项目启动参数中设置：

```properties
control-plane.integration-checks-file=../.runtime/integration-checks.json
control-plane.integration-target=refs/heads/main
```

下例适用于含 `src/Api.java`、`Client.java`、`Schema.java`、`Check.java` 的最小演示仓库；替换为该项目实际工具的绝对路径和验收命令。它不是旧 SpringMvcBlog 的可用构建配置。

```json
[
  {
    "id": "java-combination",
    "version": "1",
    "checks": [
      {
        "id": "compile",
        "argv": ["C:/Program Files/Java/jdk-21.0.11/bin/javac.exe", "-proc:none", "src/Api.java", "src/Client.java", "src/Schema.java", "src/Check.java"],
        "workingDirectory": ".",
        "timeoutSeconds": 30
      },
      {
        "id": "behavior",
        "argv": ["C:/Program Files/Java/jdk-21.0.11/bin/java.exe", "-cp", "src", "Check"],
        "workingDirectory": ".",
        "timeoutSeconds": 30
      }
    ]
  }
]
```

命令以参数数组执行，API 不接受命令、环境变量或任意工作目录覆盖。Windows 批处理需在可信配置中显式配置解释器及参数；优先配置实际 `.exe`。不修改全局环境或 Git 配置，不自动安装依赖。

每个 profile 最多 10 条检查，每条 1–300 秒，stdout/stderr 分别最多保留 64 KiB；超时、输出超限、运行异常、后台子进程遗留、已跟踪源码/索引/HEAD 被修改均不能记为通过。未跟踪的编译产物允许存在。工作目录经真实路径解析后必须位于本次工作树内。

这是可信代码的本机执行器，**不是操作系统安全沙箱**。命令及被测代码拥有服务账号的本机权限；独立工作树、最小继承环境和子进程清理不能隔离恶意代码。不要在该模式中执行不可信仓库。日志可能包含被测代码的输出，仅项目成员可读。

## API 与角色

使用项目本地身份文件签发的 Bearer 凭据，配置方法见 [治理操作](governance-operations.md)。所有写请求需要非空、最长 160 字符的 `idempotencyKey`；相同 key 只能由同一主体重放同一操作。

| 方法与路径 | 身份 | 请求 |
| --- | --- | --- |
| `GET /api/v1/integrations/candidates` | 项目成员 | 返回候选 ID 数组 |
| `GET /api/v1/integrations/candidates/{id}` | 项目成员 | 返回计划、静态报告、真实冲突、当前有效性与最新验证记录 |
| `POST /api/v1/integrations/candidates` | 工程负责人 | 下方创建请求 |
| `POST /api/v1/integrations/candidates/{id}/validate` | QA 负责人 | 下方验证请求 |
| `POST /api/v1/integrations/candidates/{id}/proposal` | 工程负责人 | 下方合并建议请求 |

创建：

```json
{"idempotencyKey":"integration-01","expectedVersion":1,"taskIds":["A","B","C"],"profileId":"java-combination"}
```

任务必须已真实提交，且仍处于 SUBMITTED / CODE_REVIEW / TESTING / ACCEPTED。服务端读取提交、执行身份、工作树和语义版本，不接受客户端自报 SHA。返回 `candidate.plan.id` 和 `candidate.plan.fingerprint`。

验证：

```json
{"idempotencyKey":"validation-01","expectedFingerprint":"复制候选的完整指纹"}
```

返回验证 ID、候选 SHA、角色主体、检查命令、退出码、超时标志、输出和完成状态。每次新验证使用新的独立工作树。若某条检查失败，保留结果并停止后续检查。

合并建议：

```json
{"idempotencyKey":"proposal-01","expectedFingerprint":"复制候选的完整指纹","validationId":"最新通过的验证 ID"}
```

所有参与任务必须完成原有四类门禁及人工验收，最新组合验证必须通过；负责创建、验证或建议的主体不能是任何参与任务的执行者。结果包含候选 branch/SHA、目标 ref/SHA、验证 ID、`mergePolicy=HUMAN_ONLY`、`automaticallyMergeMain=false`。实际人工合并前应再次核对有效性和目标 SHA。

一旦任务被纳入候选，它的单任务 `merge_proposal` 入口会拒绝请求；必须通过组合入口。拒绝以 HTTP 400（请求错误）、403（角色或项目权限）或 409（状态、版本、幂等冲突）表达。

现有 15 个 MCP 工具保持兼容；集成执行通过上述 REST 接口调用，原 `get_integration_status` 仍返回治理摘要。第五期已接入[真实集成工作台](integration-workbench.md)，可在页面创建候选、运行组合验证、查看冲突并生成人工合并建议。

## 指纹与过期

指纹绑定项目仓库路径、目标 ref/SHA、排序后的任务集合、执行 ID/branch/path/base/SHA/负责人、任务 context/API/schema/ADR 版本及完整验证 profile。查询与写操作都会重新核对真实执行和 Git 引用。

- 任一任务换执行、提交分支漂移、语义失效、目标分支变化或 profile 内容变化，旧候选显示 `STALE`。
- 候选发布分支被移动或删除也会使其失效。
- 历史 `PASSED` 保留供审计，不能覆盖 `currentlyValid=false`。
- 新验证开始即替代旧验证。最新验证 FAILED 或 RUNNING 时，旧 PASS 和旧幂等合并建议都不能重新获得批准。
- 返工通过原 `start_review → record_gate(passed=false) → retry_task → claim_task → submit_changeset` 流程；生成新候选并重新验证，不覆盖旧执行。

## 恢复与文件边界

数据库 V3 表保存候选计划、任务成员关系、验证与建议。计划在 Git 副作用之前提交。候选依次经历 `PREPARING → ASSEMBLED → READY`，或停在 `CONFLICTED`；候选提交先存档，再用 `update-ref` 的旧值条件发布 `refs/heads/codex/integration-{UUID}`。

构建使用仓库旁 `.refactor-integrations/{UUID}/objects.git` 独立 bare 仓库，只以对象 alternates 读取输入；不加载来源仓库的 merge driver。固定作者/日期使同一计划重建出相同对象。新对象经有大小上限的 pack 导回来源仓库；不移动任何任务分支或目标分支。

同一创建 key 重试会恢复原计划。引用已是预期 SHA 可补记 READY；不同 SHA 保留外部胜者并拒绝。目录先在临时位置写完归属标记，再以不覆盖方式重命名发布；发布前后中断均可重试。没有归属标记的既有目录不会被接管；目录和历史验证产物保留，不自动删除。

验证在同一数据库事务内记录 RUNNING 并切换最新验证 ID，提交成功后才运行命令。服务中断后 RUNNING 表示完成情况未知，不自动重跑；先检查本次 `validation-{UUID}` 目录，再显式使用新 key 发起新尝试。当前为单节点串行写入，各候选读取接口可用；不是分布式执行调度器。

## 可复现演示

`IntegrationWorkflowTest` 使用临时 Git 仓库、真实 MCP 领取/租约/提交、真实 javac/java 以及 H2 持久记录，演示：

1. A 改 API 返回 10，B 改 Client 乘 2，C 改 Schema 返回 1，组合断言得到 21；独立审批后生成建议，main 不变。
2. A 将 `value` 改名，B 仍调用旧名：无文本冲突但编译失败；拒绝 B、创建新执行并修正后，新候选验证通过，旧候选失效。
3. A/B 同文件竞争租约先被阻断；先后提交不兼容修改后，真实 Git 合并定位冲突路径及任务。
4. 配置、目标、源提交变化与发布中断、外部引用竞争、最新未知验证均有拒绝用例。

从 `backend` 执行（使用当前项目的离线缓存和设置）：

```powershell
mvn.cmd --% -o -s ../.runtime/review-20260910/maven-settings.xml -gs ../.runtime/review-20260910/maven-settings.xml -Dmaven.repo.local=.m2-repository -Dtest=IntegrationWorkflowTest,ControlledProcessTest,IntegrationApiContractTest test
```

上述演示验证的是集成机制；没有运行旧 Java 7 应用、真实 MySQL，也不声称已经覆盖任意语言的 AST、接口语义或业务兼容性。
