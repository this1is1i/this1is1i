# Refactor Control Plane 产品规格 v0.1

- 状态：已批准
- 批准日期：2026-09-06
- 变更规则：本文件是 v0.1 基线；后续变更通过新版本或 ADR 记录，不静默改写已批准决策。

## 1. 产品定位

Refactor Control Plane 是企业级遗留系统重构的治理和协作控制平面。它掌握重构工作的权威流程状态，将“项目理解 → 架构决策 → 工作拆分 → 隔离执行 → 集成分析 → 质量验收”连接为可追踪、可审计、可恢复的闭环。

它不是 IDE、代码托管平台或全自动重写器，也不替代现有 Git、CI/CD 和项目管理系统。模型、闭源智能体和人工开发者均作为可替换执行器接入。

## 2. 首版范围

### 2.1 项目理解

- 导入本地 Git 仓库并创建不可变分析快照。
- 识别语言、框架、构建器、依赖、配置、数据库、页面、控制器和模块关系。
- 生成带源码证据链接的现状架构图、依赖图、风险和升级要点。
- 保存事实、推断、人工确认和模型结论之间的来源关系。

### 2.2 重构规划

- 记录目标架构、迁移策略和 Architecture Decision Record（ADR）。
- 生成工作包 DAG、关键路径、资源需求、前置条件和验收契约。
- 建立需求、决策、任务、代码、测试和验收证据的追踪链。

### 2.3 并发执行

- 每次任务执行创建独立 Git worktree 和 task branch。
- 通过路径所有权和逻辑租约协调修改意图。
- 通过 Project MCP 共享任务、Agent 状态、变更事件、契约、Schema、ADR 和依赖影响。
- 支持至少三个互不冲突的任务并发执行。

### 2.4 智能体与模型

- 通过 OpenAI-compatible 企业模型网关调用内网模型。
- 首版提供 Mock 执行器和通用 HTTP 执行器。
- 记录模型、提示词版本、上下文摘要、工具调用、输出哈希和成本元数据。
- 密钥不得进入任务包、模型内容或明文日志。

### 2.5 验收治理

- 支持代码 Review、单元测试、集成测试、端到端测试及可配置质量门禁。
- Integration AI 负责冲突分析、集成顺序建议和候选集成分支，不得合并 `main`。
- 只有全部门禁和必需审批通过后，任务才能进入 `ACCEPTED`。
- 平台只生成“可人工合并”结果，不执行最终合并。

## 3. 总体架构

```text
Vue 3 Role Workbenches
          │
Spring Boot Control Plane
          ├── Project & Workflow Service
          ├── Planning / Scheduling Service
          ├── Ownership & Lease Service
          ├── Contract / Schema Registry
          ├── Quality Gate Service
          ├── Model Gateway
          ├── Audit & Evidence Service
          └── Project MCP Server
                    │
        ┌───────────┼───────────┐
        ↓           ↓           ↓
  Execution A  Execution B  Execution C
  Agent/Dev    Agent/Dev    Agent/Dev
  Worktree     Worktree     Worktree
  Task Branch  Task Branch  Task Branch
        └───────────┬───────────┘
                    ↓
             Integration AI
                    ↓
              CI / Human Review
                    ↓
                   main
```

## 4. 角色与工作台

| 角色 | 主要工作台 | 关键权限 |
|---|---|---|
| 平台管理员 | 模型、执行器、规则模板、权限 | 配置平台，不参与业务审批 |
| 项目经理 | 计划、关键路径、资源冲突、风险 | 分派和调整任务，不批准架构 |
| 架构师 | 代码地图、架构图、ADR、升级路线 | 批准技术方案和架构例外 |
| 开发负责人 | 工作包、契约、Review、集成候选 | 批准变更进入测试 |
| 开发者/智能体操作者 | 任务包、Worktree、租约、验收命令 | 仅修改任务授权范围 |
| 测试负责人 | 测试矩阵、环境、质量门禁 | 判定质量门禁结果 |
| 业务验收人 | 业务场景与结果 | 最终业务签字 |
| 审计/安全角色 | 时间线、模型调用、审批记录 | 只读或否决高风险操作 |

权限模型采用 RBAC + 项目级授权 + 阶段审批规则。

## 5. 共享上下文

共享上下文采用追加式 Change Event 作为事实记录，并由投影生成 Tasks、Agent Status、File Ownership、Locks 等当前视图。首版核心事件包括：

- `TaskCreated`
- `TaskClaimed`
- `LeaseAcquired`
- `FileScopeChanged`
- `ContractPublished`
- `SchemaChanged`
- `DecisionAccepted`
- `CommitSubmitted`
- `GatePassed`
- `ConflictDetected`
- `TaskInvalidated`

任务包必须绑定基准 Commit SHA、上下文版本、API Contract 版本、DB Schema 版本、ADR 版本、允许/禁止路径和验收命令。上游语义资产变化会使相关执行进入 `STALE`。

## 6. 任务状态机

```text
DRAFT → READY → CLAIMED → RUNNING → SUBMITTED
                                      ↓
                                 CODE_REVIEW
                                      ↓
                                   TESTING
                                      ↓
                                  ACCEPTED

执行态可按规则进入 BLOCKED、STALE、REJECTED 或 CANCELLED。
```

只有 `ACCEPTED` 可以生成可人工合并建议。

## 7. 并发与资源协调

调度任务前必须同时满足：

- 前置任务达到要求状态。
- 路径范围不存在不兼容租约。
- API、Schema、共享模型和公共配置没有未协调变更。
- 人员/智能体容量及测试环境槽位可用。
- 任务绑定的上下文版本仍有效。

所有权模式：

- `READ`：允许共享。
- `SHARED_WRITE`：仅用于明确可合并的生成物。
- `EXCLUSIVE_WRITE`：业务源码、Schema 和公共配置默认模式。

租约必须包含所有者、任务执行 ID、资源、模式、版本、开始时间、TTL 和心跳时间。过期回收必须通过版本条件更新，防止旧执行释放新执行的租约。

## 8. Project MCP 首版能力

- `get_project_context`
- `get_ready_tasks`
- `claim_task`
- `heartbeat`
- `get_task_package`
- `reserve_paths`
- `release_paths`
- `publish_contract_change`
- `publish_schema_change`
- `record_architecture_decision`
- `report_change_event`
- `submit_changeset`
- `report_blocker`
- `get_dependency_impact`
- `get_integration_status`

所有写操作要求任务令牌、幂等键和预期版本号。智能体不得直接读写平台数据库。

## 9. 资源协调表

| 分类 | 字段 |
|---|---|
| 任务 | 工作包、优先级、阶段、预计工时、状态 |
| 人员 | 负责人、审核人、测试人、智能体执行器 |
| Git | 基准 SHA、任务分支、Worktree、提交 SHA |
| 依赖 | 前置任务、阻塞原因、关键路径 |
| 所有权 | 路径、文件、租约类型、版本、过期时间 |
| 语义资源 | API、Schema、公共模型、配置、ADR |
| 环境 | 数据库实例、测试环境、执行槽位 |
| 验收 | Gate、证据、审批角色 |
| 风险 | 冲突概率、影响范围、回退方案 |

## 10. 样例与首个切片

- 来源：`https://github.com/eyupgevenim/SpringMvcBlog`
- 固定提交：`5e62608af333b5757bf767e05b19e2b0d56f0afb`
- 许可证：MIT
- 遗留栈：Java 7、Spring MVC 4.1、JSP、Spring JDBC、XML 配置、MySQL 5.6、jQuery、Bootstrap 3。
- 首个切片：文章管理迁移到 Vue 3 + Spring Boot，并保留可对照的功能与测试证据。

样例是验收夹具；平台领域模型和分析器不得写死该仓库结构或 Java 技术栈。

## 11. 明确不在 v0.1 范围

- 自动合并或部署生产。
- 一次性重写整个遗留系统。
- 自动替代架构师和业务验收人的决策。
- 首版适配所有代码托管、项目管理和通信平台。
- 自研完整代码生成模型。

## 12. MVP 验收摘要

MVP 的可执行验收基线见 `docs/acceptance/mvp-acceptance.md`。任何“完成”声明必须同时提供需求追踪、测试输出、变更清单和未解决风险。
