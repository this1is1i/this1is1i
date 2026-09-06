# Refactor Control Plane

面向企业级遗留系统重构的治理与协作控制平面。它负责项目理解、架构决策、工作拆分、并发执行协调、质量门禁和验收证据；开发者、模型与智能体是可替换的执行器。

## 当前阶段

- 产品规格：v0.1，已批准
- 当前里程碑：建立项目骨架、领域模型与可执行验收基线
- 代码权限：只允许任务分支/补丁，必须人工合并
- 数据边界：代码不出内网，只允许调用企业模型网关
- 并发模型：每次任务执行拥有独立 Git worktree 与 branch

新聊天应先阅读 [AGENTS.md](AGENTS.md) 和 [产品规格 v0.1](docs/product-spec-v0.1.md)。实时进展见 [docs/status.md](docs/status.md)。

## 目录约定

```text
backend/                 Spring Boot 控制平面
frontend/                Vue 3 角色工作台（后续里程碑）
docs/                    规格、架构和验收标准
samples/                 固定版本的遗留项目样例及来源清单
```
