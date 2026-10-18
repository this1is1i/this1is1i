# 不可变分析快照与证据

分析器先将 HEAD 解析成提交 SHA，再读取该提交的 tree 与 blob。它不读取工作区、索引或未跟踪文件，也不创建 checkout。源码修改、暂存、删除以及分析期间 HEAD 移动不会改变已捕获快照。linked worktree 也可作为输入。

`GitAnalysisSnapshot.capture(repository)` 得到不可变内容，`LegacyProjectAnalyzer.analyze(snapshot)` 可重复分析同一快照。REST 概览与 MCP 的项目上下文继续使用原报告字段，并追加 `findings`。

## 结果语义

| 字段 | 含义 |
| --- | --- |
| `snapshotCommit` | 本次读取的固定 Git 提交 |
| `technologyStack` | 已识别的配置声明或源码文件类型；不等于生产运行环境 |
| `evidence` | 每项含稳定顺序的 `id`、`claim`、`path`、`snapshotCommit`、`blobId` |
| `findings` | `kind`、`category`、`statement`、`evidenceIds`；区分事实、推断、未知 |
| `modules` | 实际存在的构建目录、页面目录及源码候选组件，无虚构的 `not-detected` 模块 |
| `currentArchitectureMermaid` | 有证据的组件清单；调用关系尚未证明时不绘制连接边 |
| `risks` / `upgradePoints` | 兼容原接口的推断摘要；对应 findings 中保留证据关系 |

- **FACT**：提交中可观察的声明或文件，例如依赖坐标、编译配置、JSP 文件。依赖声明不证明已安装版本或正在运行该组件。
- **INFERENCE**：需要进一步确认的结论，例如控制器注解提示请求处理组件，或低于项目目标 Java 21 的编译目标需要迁移兼容性验证。
- **UNKNOWN**：缺少证据、格式不支持或超出资源限制。未知不会被替换为旧样例的 MySQL、Spring 或 JSP 架构。

普通文件的证据可用 `git --no-replace-objects show <snapshotCommit>:<path>` 检查，也可通过 `git cat-file blob <blobId>` 查看原对象。不要以当前工作区内容代替历史证据。子模块引用指向 commit 而非 blob，因此其 UNKNOWN 证据的 `blobId` 为空，引用 SHA 保存在说明中。

## 当前识别范围

- Maven：扫描所有 POM，读取当前文件中的直接依赖、Spring Boot parent 和标准 compiler plugin 配置，解析当前 POM 的属性引用。显式 compiler release 优先，不把普通未使用的 `java.version` 属性当作编译目标。
- npm：读取 package.json 的 dependencies、devDependencies、peerDependencies，保留原始版本范围；区分 Vue、React、Vite、jQuery 的声明。空字符串版本范围保留为有效声明。
- 源码：Java、JSP、JavaScript、TypeScript、Python、Vue 单文件组件、SQL 文件清单；Java 控制器和仓储注解仅作为组件推断，不证明调用关系。
- 关系：Maven 标准模块声明、Java import、配置中的 JDBC 协议、SQL 表及引用、控制器路由片段和视图匹配。每条边携带 FACT/INFERENCE 及固定 commit/path/blob 证据；路由和视图是文本推断，不冒充运行时调用图。JDBC 只保留协议，不输出连接地址或凭据。
- Gradle 等未实现的构建求值、Maven parent/profile 的有效配置、依赖传递解析和部署信息明确作为未覆盖能力。控制器扫描是轻量文本识别，不是完整 AST/调用图分析。
- 不依据驱动版本猜测数据库服务器版本，不输出未经核查的安全支持期限或漏洞结论。固定文章迁移计划仍是样例专用，不因分析器扩展而自动成为通用规划器。

## 执行与资源边界

- Git 必须支持 `--no-lazy-fetch`；本期验证版本为 2.49.0。读取禁用 replace refs 和 partial-clone 隐式抓取，缺失对象明确失败，禁止自动联网补齐。
- 只调用本地 Git 对象读取命令，不运行 Maven/npm、仓库脚本、hooks 或模型。没有新增网络依赖。
- 单个分析文件上限 1 MiB，累计内容上限 16 MiB；超过限制的候选文件记录 UNKNOWN。tree 输出也有 16 MiB 上限，超出时整个快照读取失败。
- Git stdout/stderr/stdin 并发处理并有大小限制；单命令 30 秒超时，失败后清理本次仍在运行的子进程。
- UTF-8 严格解码；候选文件为二进制或其他编码时记录 UNKNOWN。非法路径编码或损坏的对象协议使快照读取失败。
- 跳过 target、node_modules、.git、.m2-repository 目录。符号链接和子模块不跟随，只记录覆盖限制。
- XML 禁止 DOCTYPE、外部实体及外部 Schema，最大嵌套深度 128；JSON 拒绝重复键和尾随 token。整个依赖声明验证完成后才发布事实。
- 关系扫描最多检查 10,000 个候选、输出 500 条边；Java/SQL 注释按语言排除。无匹配的大段空白配置、无终止符 SQL 和大量字面量也受回归约束，不能仅靠结果数量限制计算量。

这些限制属于当前分析覆盖范围，不能据此宣称仓库没有其他语言、组件、风险或依赖。
