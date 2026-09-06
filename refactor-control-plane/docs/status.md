# 当前状态

- 更新时间：2026-09-06
- 规格：v0.1 已批准并持久化
- 阶段：项目初始化（持久化基线已建立）

## 已确认

- 核心产品边界、角色、权限和人工合并策略。
- Worktree/branch + ownership/lease 的并发隔离方案。
- Shared Context + Change Event + MCP 的语义协作方案。
- OpenAI-compatible 企业模型网关、Mock 和通用 HTTP 执行器。
- SpringMvcBlog 固定提交作为遗留项目样例。

## 当前里程碑

已建立：

- Spring Boot 3.5 / Java 21 后端构建骨架与应用入口。
- 工作包状态集合和批准转换矩阵。
- 三个测试驱动红绿循环形成的自动化契约测试。
- 样例项目来源清单与固定版本工作区；当前 HEAD 为
  `5e62608af333b5757bf767e05b19e2b0d56f0afb`。

尚未建立：

- Vue 3 前端骨架与角色工作台。
- 数据库持久化、MCP、模型网关和 Git 执行服务。

## 下一实现顺序

1. 工作包状态机与领域不变量。
2. 路径所有权及带版本逻辑租约。
3. Change Event 与任务失效传播。
4. Git task branch/worktree 执行适配器。
5. Project MCP 的只读上下文和任务领取接口。

## 已知边界

- 当前主机具备 Java 21、Maven 3.9.2、Node 22 和 npm 10。
- 样例项目使用 Java 7 时代工具链，后续需要独立 legacy runner，不能依赖控制平面的 Java 21 运行时直接启动。
- Maven 首次执行需在项目内使用 `backend/.m2-repository`；PowerShell 调用批处理入口时应使用停止解析标记确保 `-D` 参数不丢失。
- 父工作区存在与本项目无关的用户改动；不得修改或清理。
