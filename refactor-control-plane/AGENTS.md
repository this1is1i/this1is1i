# Refactor Control Plane — Agent Handoff

本文件是新聊天和新执行者的首要接手入口。

## 已确认目标

构建一个企业级遗留系统重构控制平面，将以下阶段形成可审计闭环：

1. 项目理解与证据化架构分析。
2. 目标架构、ADR、升级风险与迁移计划。
3. 工作包 DAG、资源协调与并发调度。
4. 人工开发者或智能体在隔离任务分支中执行。
5. 代码 Review、测试、质量门禁、人工验收与证据导出。

平台不是自动重写器，不自动合并 `main`，也不把 Git 当作智能体共享语义上下文。

## 已批准约束

- 产品形态：Vue 3 Web 工作台 + Spring Boot 控制平面 + Project MCP + 可插拔执行器。
- 数据边界：代码不出内网；模型只能通过 OpenAI-compatible 企业模型网关访问。
- 执行器：首版包含 Mock 与通用 HTTP 适配器。
- 身份：首版使用本地账号模拟角色，权限模型兼容未来 OIDC/LDAP。
- 执行权限：智能体只能产出任务 branch/patch；必须人工合并。
- 隔离：每次任务执行创建独立 worktree 和 branch。
- 协作：Shared Context + Change Event + MCP 负责语义协作。
- 样例：`eyupgevenim/SpringMvcBlog`，固定 MIT License 提交
  `5e62608af333b5757bf767e05b19e2b0d56f0afb`。
- 首个重构切片：文章管理，从 JSP/Spring MVC 迁移到 Vue 3/Spring Boot。

## 不可破坏的不变量

1. Worktree/branch 按任务执行创建，不按人员永久绑定。
2. 文件锁是带 TTL、心跳、版本号的逻辑租约，不是操作系统锁。
3. 每个任务绑定不可变的 base commit、context、API contract、DB schema 和 ADR 版本。
4. 上游语义资产变化时，下游任务必须进入 `STALE` 并重新验证。
5. Integration AI 只能分析冲突和生成集成候选，不能合并 `main`。
6. 所有 AI 结论必须能追溯到源码、配置、决策或测试证据。
7. Git 与数据库跨资源写入必须幂等、使用引用前置条件，并支持状态对账；不得用失败回滚覆盖并发胜者。
8. 所有配置和依赖改动严格限制在本项目目录。

## 开发规则

- 功能与缺陷修复使用测试驱动开发；先观察预期失败，再写最小实现。
- 完成声明必须有当前轮次的新鲜构建、测试和结构校验证据。
- 保留父工作区及本项目中与当前任务无关的用户改动。
- 不直接下载或调用外部模型；公共 GitHub 样例只在显式样例初始化时下载一次。
- 文本文件统一使用 UTF-8。

## 权威文档

- `docs/product-spec-v0.1.md`：已批准产品规格，修改需用户确认并升版。
- `docs/architecture/core-architecture.md`：并发执行与共享上下文设计。
- `docs/acceptance/mvp-acceptance.md`：MVP 验收条件。
- `docs/status.md`：当前进展、下一步和已知阻塞。
