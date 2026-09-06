# 核心架构与并发模型

## 1. 两类问题必须分离

### 防止代码互相覆盖

由 Git worktree、task branch、路径所有权和逻辑租约共同承担：

- Worktree 隔离物理工作目录。
- Branch 隔离提交历史。
- Ownership 表达计划修改范围。
- Lease 协调运行时修改权并处理异常退出。

### 让执行者理解彼此的变化

由 Shared Context、Change Event、Project MCP 和语义资产版本承担：

- Git 只能识别引用和文本差异。
- API Contract、DB Schema、ADR 和依赖图描述语义影响。
- 事件传播变化，依赖影响分析决定哪些任务需要阻塞或重新验证。

## 2. 执行生命周期

1. 调度器从 `READY` 工作包中选择依赖和资源条件满足的任务。
2. 创建具有唯一 execution ID 的 task branch 和 worktree。
3. 使用版本条件申请路径及语义资源租约。
4. 生成绑定不可变版本的任务包并交给人工或智能体。
5. 执行者通过心跳续租，通过 Project MCP 发布语义变化。
6. 提交时先验证 commit、branch、租约和上下文版本。
7. 记录幂等提交事件并运行质量门禁。
8. Integration AI 生成冲突报告和候选集成分支。
9. CI 与人工 Review 决定是否允许人工合并。

## 3. 跨 Git 与数据库的一致性

Git 引用和平台数据库无法共享单一事务，因此采用可恢复提交协议：

- 每次写入携带 operation ID 和幂等键。
- Git 引用更新使用预期旧 SHA 的 compare-and-swap。
- 首个外部可见写入前保存待恢复操作记录。
- 每一步后重新读取实际状态，而不是依赖调用返回值推断。
- 通过 outbox 发布 Change Event，并由 reconciliation worker 对账。
- 后续步骤失败时，不回退已由其他执行者推进的共享引用。

## 4. Integration AI 权限边界

允许：读取已提交变更、依赖图、契约、Schema、ADR 和测试证据；生成冲突结论、解决建议、集成顺序和候选分支。

禁止：覆盖任务分支、绕过门禁、批准自己的输出、直接更新 `main` 或部署生产。
