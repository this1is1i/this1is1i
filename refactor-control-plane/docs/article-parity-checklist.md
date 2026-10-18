# 文章切片行为对照

固定来源：SpringMvcBlog `5e62608af333b5757bf767e05b19e2b0d56f0afb`。以下来自该提交的 Git 对象；是声明/实现证据，不代表旧应用已在 Java 21 或真实 MySQL 上运行。第 7 里程碑交付边界和对照清单，第 10 里程碑完成业务决定与引擎兼容验收。

下表保留第 7 里程碑的脚手架基线和验证要求；当前增量为 [10B1 文章行为](acceptance/milestone10b1-article-behavior.md)，10B2/10C 现已完成，最终证据见[第十里程碑验收](acceptance/milestone10-article.md)，不能按下表旧现状推断当前实现。

| 行为 | 固定源码依据 | 第 7 里程碑脚手架基线 | 第 10 里程碑确认及验证 |
|---|---|---|---|
| 文章管理权限 | `spring-security.xml` 对 `/user/**` 要求 USER，启用 CSRF；`UserController` 从认证上下文取用户 | 本地开发 CRUD 未接入旧用户/角色，绑定 loopback | 确认认证与作者/博客所有权规则；逐条验证未登录、跨用户读写及 CSRF。不能把旧控制器缺失的所有权检查当作应保留的漏洞 |
| 新增与菜单归属 | `UserController.addPost` 获取博客的 `Anasayfa` 菜单，写入 BlogMenuId；DAO 插入标题、正文、路由和菜单 ID | 文章表仅含标题、正文、状态及时间 | 保留菜单/博客关联及 ID 映射；无法映射时拒绝或进入待处理，不默默丢弃 |
| 富文本 | 新增 JSP 使用 Summernote 和隐藏字段 `post-content`；`blog-sharing.jsp` 输出 `${post.postContent}` | textarea 编辑、Vue 文本显示；保存原正文字符串 | 确认 HTML 保留、清洗、图片与链接策略；测试旧正文的存储与显示。当前纯文本显示不是已批准替代方案 |
| 旧 URL | `UserController.setRequestMapping` 从标题生成 slug；`BlogController` 声明 `/{blogUrl}/{menuName}/{requestMapping}` 等路由 | REST `/api/articles` 和独立页面，无旧 URL 重定向 | 明确原 slug 稳定性、重名处理、旧链接解析/重定向、修改标题是否更改 URL |
| 编辑行为 | `editMenuSave` 读取菜单现有第一篇文章；`PostDaoImpl.updatePost` 只更新 PostContent | CRUD 同时允许改标题、正文和状态 | 确认文章编辑与菜单页面编辑的区别，不能据新 CRUD 推断旧功能已等价 |
| 时间、标题与状态 | 旧表与 DAO 使用 DateTime；DAO 部分读取为 `getDate`；源标题上限 255 | 独立 H2 标题 255、UTC Instant、DRAFT/PUBLISHED/ARCHIVED | 确认源时区、原始精度、旧记录状态映射；保留原值证据，不复制旧读取截断为新行为 |
| 删除与引用 | 文章通过菜单 ID 关联，评论按 PostId 引用；控制器有菜单移除入口 | 新 CRUD 可按新 ID 删除；未实现旧关联级联策略 | 明确软删除/硬删除、评论和菜单引用、历史 URL 处理后再验收。不得默认级联删除旧数据 |

2026-10-18 用户已批准保留 MySQL、按博客归属隔离、HTML 白名单展示、稳定旧 URL，以及编辑标题/正文/状态但 URL 与归属不变。软删除随后也获明确批准。10B1 已完成权限、HTML、安全公开投影、精确稳定 URL、归属保护与软删除；10B2 已完成七篇原文/旧地址/关联/时间对照及失败恢复，10C 实际受管候选已通过。批准与实现状态分开，详见[业务决定](article-business-decisions.md)。

## 固定对象索引

所有路径相对于样例仓库 `Blog/`，可用 `git show <固定提交>:<完整路径>` 核对。旧文件存在非 UTF-8 文本时，平台分析保留 UNKNOWN；本清单仅引用可核对的 ASCII 标识符和声明。

| 文件（`Blog/` 之后） | Blob ID |
|---|---|
| `src/main/java/com/springframework/mvc/controllers/UserController.java` | `4adba7867f5efca72e235bd4a554eb635961d997` |
| `src/main/java/com/springframework/mvc/controllers/BlogController.java` | `3e4736701ace8f9c5393958a3f78e3b2e4b85658` |
| `src/main/java/com/springframework/mvc/dao/impl/PostDaoImpl.java` | `45d49c74df0ed5043d9d975bc7abfaf619e23978` |
| `src/main/webapp/WEB-INF/spring/spring-security.xml` | `7ea6a90600548ff5bddc4eddda833738f1ed7a87` |
| `src/main/webapp/WEB-INF/views/users/manages/user-blog-posts-add.jsp` | `def1fd25d6f51ec203da76ed738f84ff37c14f8e` |
| `src/main/webapp/WEB-INF/views/blogs/blog-sharing.jsp` | `fee7a4ddca8514ee24f4bcb4bc166b59d39b9a4f` |
