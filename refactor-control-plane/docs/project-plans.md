# 项目级可执行计划与版本演进（第 7 里程碑）

配置模式将目标仓库、固定基线、任务 DAG、授权路径、资源声明及本机验证命令作为一个不可变计划。MCP、工作台和集成服务通过 `ProjectSource` 读取该计划，不依赖文章样例工厂。原有 demo 模式继续供历史演示使用。

第 8A 增加可选 `scheduling`，配置负责人的并发容量与环境槽位，纳入同一审批指纹；旧计划省略时保持兼容。格式、阻断和停止恢复见[执行容量说明](execution-scheduling.md)。

第 9C 增加可选 `requirements`（ID、说明、taskIds），纳入原有指纹及双角色审批。旧计划不补写、业务文字不自动映射任务；格式与审计采信边界见[审计导出](audit-export.md)。

## 启动配置

在项目目录中创建配置文件，以 Spring 的项目级配置参数加载，不修改环境变量或全局设置：

```properties
control-plane.project.mode=configured
control-plane.project.definition-file=../config/project.json
control-plane.identity-file=../config/identities.json
spring.datasource.url=jdbc:h2:file:./data/catalog-control-plane
```

身份文件格式沿用[本地治理操作](governance-operations.md)。账号的 `projectId` 必须与计划相同；至少需要不同主体的 `ARCHITECT` 和 `PROJECT_MANAGER`。默认无账号，不生成或保存明文令牌。

启动后，既有 `/api/v1/demo/overview` 兼容入口、MCP 和 `/api/v1/workbench` 都读取已选择的项目源。`configured` 模式仅加载定义中的 `validationProfiles`，不会混入 `control-plane.integration-checks-file` 的另一份命令。

## 计划格式

下例要求目标仓库在固定提交中已有 `services/catalog`。将 `repository` 和 `baseCommit` 替换为本机真实仓库路径及完整 SHA。相对仓库路径以 JSON 文件所在目录为基准；仓库必须是主 checkout，且 HEAD 和目标分支均等于该基线。

```json
{
  "projectId": "catalog",
  "repository": "../repositories/catalog",
  "targetRef": "refs/heads/main",
  "forbiddenPaths": [".git", "private-data"],
  "plan": {
    "baseCommit": "REPLACE_WITH_FULL_COMMIT_SHA",
    "targetArchitectureMermaid": "flowchart LR\n UI --> API",
    "workPackages": [{
      "id": "CAT-1",
      "title": "实现目录接口",
      "role": "DEVELOPER",
      "dependencies": [],
      "allowedPaths": ["services/catalog"],
      "estimatedHours": 8,
      "workingDirectory": "services/catalog",
      "acceptanceCommands": ["mvn.cmd verify"]
    }],
    "criticalPath": ["CAT-1"],
    "parallelExecutionGroups": {},
    "apiContract": "catalog-v1，保留既有响应语义",
    "databaseCompatibility": "本任务不改变存储结构",
    "functionalParity": {"目录查询": "保持既有过滤和排序规则"},
    "requiredEvidence": ["unit-tests", "code-review"]
  },
  "resources": {
    "catalog-source": {"kind": "PATH", "locator": "services/catalog", "version": "1"},
    "catalog-api": {"kind": "API_CONTRACT", "locator": "catalog-v1", "version": "1"}
  },
  "taskResources": {"CAT-1": ["catalog-source", "catalog-api"]},
  "directoryCreators": {},
  "taskProfiles": {"CAT-1": "catalog-check"},
  "validationProfiles": [{
    "id": "catalog-check",
    "version": "1",
    "checks": [{
      "id": "backend-tests",
      "argv": ["mvn.cmd", "verify"],
      "workingDirectory": "services/catalog",
      "timeoutSeconds": 300
    }]
  }]
}
```

命令在配置阶段不执行，也不安装依赖；它必须适用于目标应用自身的构建链。`argv` 是实际参数数组，`acceptanceCommands` 是用空格连接后的展示文本，两者及工作目录必须一致。集成验证配置必须覆盖选中任务各自声明的检查；组合 profile 可以增加检查，不能以无关 profile 替代任务检查。

资源种类支持 `PATH`、`API_CONTRACT`、`DB_SCHEMA`、`ADR` 和 `ENVIRONMENT`；每个任务须引用已声明资源，PATH 资源不得超出任务授权。当前这些是规划声明；环境容量调度属于第 8 里程碑，资源声明与真实变更证据的完整关联属于第 9 里程碑。

导入会拒绝 DAG 环、未知依赖/资源/角色、重复任务、越界路径、受保护路径、缺失工作目录和不匹配的验证方案。目录判断来自固定 Git tree；未跟踪目录、符号链接或本机残留目录不能证明执行工作树具备该目录。

`directoryCreators` 只能声明由特定授权任务新建的产物目录。前置任务验收并不使它的提交自动进入下游工作树。创建任务从已有目录（如 `.`）执行；使用新目录前，需按下文从已验收且验证通过的集成候选形成新基线，并重新评审计划。

## 确认与执行

1. 携带项目身份调用 `GET /api/v1/project/plan`，读取完整定义、分析结果、`fingerprint` 和 `status`。
2. 架构师调用 `POST /api/v1/project/plan/decisions`：

   ```json
   {"expectedFingerprint":"读取的完整指纹","decision":"ARCHITECTURE"}
   ```

3. 另一主体的项目经理调用相同接口，`decision` 为 `EXECUTION`。状态变为 `APPROVED` 后，沿用工作台分配/领取及 MCP 执行入口。

批准前不返回就绪任务，也不允许写执行日志。相同主体重复提交相同决策返回已有状态；不同主体不能覆盖已有决策。无身份/越权为 403，格式错误为 400，过期指纹、未确认架构或基线变化为 409。响应带 `Cache-Control: no-store`。浏览器“项目计划”提供身份连接、版本选择、源证据、目标/ADR、任务命令、JSON 文件导入、审批与激活。角色导航不授予权限；身份切换和读取失败会禁用旧状态写入，未知写入保留原请求供核对重试。

## 持久化与恢复

`project_plan` 保存当前完整规范化 JSON、SHA-256 指纹、确认主体和时间；`project_revision` 保留不可变版本内容、父指纹和历史审批。单节点进程固定加载一个版本，更改任务、基线或命令必须重新审批。原 demo 数据库已有日志时禁止切到配置模式；配置模式数据库也禁止作为 demo 打开。

为新项目使用独立控制平台数据库，保留原库。首次升级已有 7A 库时，只有精确配置绑定匹配才会在同一事务中登记原版本，并将旧日志/集成记录绑定其指纹；不接受未绑定历史日志。后续按指纹隔离任务快照、幂等键和集成成员，保留历史查询，禁止跨版本重放写入。不要删除绑定行或清空日志规避校验。

运行中 HEAD 或目标分支改变会阻止新任务写入及集成操作，旧候选失效。该实现不自动重验证、改写计划或合并 main。

## 原项目中的计划修订

1. 架构师调用 `POST /api/v1/project/plan/revisions`，传入 `{expectedFingerprint, definition}`。新定义保持项目、仓库及固定目标分支提交，重新校验全部 DAG/资源/命令和目录。
2. 两名独立主体在 `POST /api/v1/project/plan/revisions/{fingerprint}/decisions` 分别提交 `ARCHITECTURE`、`EXECUTION`，请求仍为 `{expectedFingerprint, decision}`。
3. 项目经理调用 `POST /api/v1/project/plan/revisions/{fingerprint}/activate`，请求 `{expectedFingerprint: 当前父版本指纹}`。运行中、待审任务、活跃租约、待对账日志和最新未知验证会阻止切换；只允许 READY、ACCEPTED、CANCELLED 停点。
4. 激活在单个数据库事务中完成，重复同一请求返回已有结果。旧运行时立即拒绝写入；**重启服务**后加载新版本。可继续使用原启动 JSON，前提是其内容与已激活历史版本精确匹配。旧任务状态不覆盖新计划，新令牌重新生成。

历史：`GET /api/v1/project/plan/revisions`；指定版本及其基线分析：`GET /api/v1/project/plan/revisions/{fingerprint}`。保留旧工作树和记录，不自动清理，不合并 main。

使用脚手架产物时，新定义设置 `plan.baseCommit` 为集成候选真实组合提交，`targetCommit` 为未改变的目标提交，`baselineCandidateId` 为该候选 ID。候选必须当前有效、完整前置任务闭包已 ACCEPTED、最新组合验证 PASSED；导入、确认、激活都会检查，任意普通分支提交不能冒充已验收基线。

可添加 `architectureDecisions`：每项包含 `id`、`statement`、`evidence: [{path, blobId}]`、`taskIds`。路径和 blob 必须匹配当前计划基线的真实对象，任务必须存在；审批绑定整个定义。分析报告提供 Maven 模块、配置数据库协议、SQL 表/引用、Java import、声明路由、视图候选关系。关系区分 FACT/INFERENCE，未覆盖项标 UNKNOWN；不是完整运行时调用图。

若服务崩溃使验证保持 RUNNING，QA 可在集成工作台检查进程/验证目录并填写对账说明，调用 `POST /api/v1/integrations/candidates/{id}/reconcile-validation`，传入 `{idempotencyKey, expectedFingerprint, validationId, reason}`。即使候选 STALE 也可对账；追加 ABANDONED 记录并保留原未知证据，不算通过，不能产生人工合并建议。

## 独立样例与受管构建

`samples/article-app` 是独立后端/前端与业务库，启动说明见其 README。配置模式平台不注册文章控制器、不初始化业务表；历史 demo 模式保留兼容入口。平台页面只显示治理工作台。

在项目根目录运行以下命令创建新隔离仓库与待审批计划，不会下载样例或改动已有仓库：

```powershell
python -X utf8 scripts/article_sample.py prepare --destination samples/workspaces/article-m7
```

以生成的 `project.json` 和全新治理库启动配置模式平台，完成双审批并领取 SCAFFOLD 任务。在返回的真实任务工作树中执行：

```powershell
python -X utf8 scripts/article_sample.py materialize --worktree <任务工作树绝对路径>
```

通过 MCP 租约与提交、集成候选以及配置好的 `sample-build` 执行独立后端测试/打包、Vue 测试/类型检查/构建。脚本仅使用已有项目依赖，不修改全局配置；新建样例仓库固定本地换行策略，Windows 启用仓库级长路径，平台独立集成库使用命令级长路径支持。所有实际构建/复制目录须位于所属项目，外部链接与现有目标均拒绝。随后按计划修订流程接续下游，工作目录才能设为候选中的 `modern/article-app/backend` 等新目录。

可运行 `python -X utf8 scripts/verify_milestone7.py --java <Java21绝对路径>` 在新隔离夹具中自动复验上述链路。它只使用仓库中的测试身份文件；输出固定基线、候选、验证和执行证据，并停止自己的服务。`--keep-serving` 额外启动本地验收页面，检查结束后在输出夹具目录创建 `stop` 文件。

## 验收边界

第 7 里程碑已完成：两个仓库布局、真实领取/重启恢复、批准顺序与权限、指纹及持久化内容变更、基线变化、配置与日志混用、非法计划拒绝、候选产物接续及浏览器审批均有证据，详见[状态记录](status.md)。文本资源声明不代表执行容量已实现。

物理隔离 A 已实现；[文章行为清单](article-parity-checklist.md)保留所有待确认项。数据库引擎策略与完整业务等价验收属于第 10 里程碑，多执行者自动执行与容量调度属于第 8 里程碑；本实现不把脚手架构建通过算作这些后续条件通过。
