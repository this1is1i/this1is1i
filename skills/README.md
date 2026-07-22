# Claude Code Skills — QA 知识库、类比学习与迭代修复

三个可分享的 Claude Code 技能（skill），安装后可在 Claude Code 对话中直接触发。

---

## 技能列表

| 技能 | 触发词 | 用途 |
|------|--------|------|
| `qa-git-track` | `/qa-git`、`记录到QA里` | 将技术问答与 git commit 绑定，通过 MkDocs + GitHub Pages 发布为可检索的知识库网站 |
| `learn-with-analogy` | `/learn`、`用类比解释` | 用结构映射帮助学习者建立可迁移的精确心智模型，并明确类比边界 |
| `iter-fix` | `/iter-fix`、`迭代修复` | 基于失败复现与可证伪假设，循环诊断、最小修复并验证到验收通过 |

---

## 快速安装

### Linux / macOS

```bash
git clone https://github.com/this1is1i/this1is1i.git
cd this1is1i/skills
chmod +x install.sh
./install.sh
```

### Windows (PowerShell)

```powershell
git clone https://github.com/this1is1i/this1is1i.git
cd this1is1i\skills
.\install.ps1
```

### 手动安装

将 `qa-git-track.md`、`learn-with-analogy.md` 和 `iter-fix.md` 复制到 `~/.claude/skills/` 目录：

```bash
# Linux / macOS
cp qa-git-track.md ~/.claude/skills/
cp learn-with-analogy.md ~/.claude/skills/
cp iter-fix.md ~/.claude/skills/

# Windows (PowerShell)
Copy-Item qa-git-track.md "$env:USERPROFILE\.claude\skills\"
Copy-Item learn-with-analogy.md "$env:USERPROFILE\.claude\skills\"
Copy-Item iter-fix.md "$env:USERPROFILE\.claude\skills\"
```

---

## 使用示例

### Skill 1: QA 知识库

```
用户: 创建QA知识库，帮我初始化整套系统
Claude: [按 qa-git-track 技能指引，创建 docs/QA_v1.md、mkdocs.yml、hooks.py、
        .github/workflows/docs.yml、scripts/build_qa_metadata.py 等文件]

用户: 记录到QA里
Claude: [将当前对话中的深度问答追加到 QA 文件，标注 git hash]
```

### Skill 2: 类比学习

```
用户: /learn 分布式系统
Claude: [先确认具体概念、学习目标与所需深度]
        [建立精确模型] CAP 解决什么问题、有哪些约束和权衡
        [选择表达方式] 仅在因果结构吻合时引入熟悉类比并逐项映射
        [拆除脚手架] 回到真实术语，用反例说明边界并检查理解迁移

用户: 用类比解释一下 MVCC
Claude: [根据用户背景选择 Quick、Standard 或 Deep 深度；若类比会失真，则改用事务时间线或代码轨迹]
```

### Skill 3: 迭代纠错

```
用户: /iter-fix 黑洞显示正常，点击能退出
Claude: [建立修复约定：复现方式、预期行为、验收检查与范围]
        [第1轮] 复现崩溃 → 定位 glTexImage2D 为 null → 添加回退 → 窄验证通过
        [回归验证] 构建、运行与退出检查全部通过
        [完成] 汇报根因、改动文件、验证命令和结果
```

---

## 卸载

删除 `~/.claude/skills/` 下的对应文件：

```bash
rm ~/.claude/skills/qa-git-track.md
rm ~/.claude/skills/learn-with-analogy.md
rm ~/.claude/skills/iter-fix.md
```

---

## 依赖

- [Claude Code](https://claude.ai/code) 已安装
- Skill 1（qa-git-track）完整功能需要：Python 3.10+、MkDocs Material、GitHub 仓库

---

## 技能详情

### qa-git-track

将项目技术问答与 git 版本绑定的完整方案：

- **Git 哈希标注**：每个 Q# 标题行标注 `（git: <7位短hash>）`，回答只对其标注版本绝对可靠
- **Obsidian 兼容**：块锚点 `^qN` + wikilink `[[file#^qN|QN]]`，本地可用 Obsidian 编辑和图谱浏览
- **MkDocs 发布**：hooks.py 自动转换 Obsidian 语法 → 标准 Markdown → Material 主题网站
- **GitHub Pages 自动部署**：推送即部署，团队成员无需 clone 仓库即可搜索问答
- **模块映射**：代码路径 → Q# 映射表，每次提交自动识别受影响的问答

参考实现：`https://this1is1i.github.io/ACSR/`

### learn-with-analogy

通过结构映射建立可迁移的精确心智模型，而不是机械套用类比模板：

- **校准学习目标**：确认具体概念、用途、学习者背景与期望深度
- **精确模型优先**：先拆解问题、实体、因果过程、约束、失败模式和权衡
- **按需选择表达**：类比不是必选项；例子、反例、图、公式或代码轨迹更准确时优先使用
- **双轨讲解**：让熟悉场景与真实概念按相同顺序运行，并逐步回到专业术语
- **拆除类比脚手架**：用精确定义或技术轨迹重新说明机制，使理解不依赖类比
- **质量与边界检查**：验证熟悉度、因果对应、动态对应和低失真，并指出最可能产生的误解
- **自适应深度**：根据目标选择 Quick、Standard 或 Deep；故事只在时序和状态变化真正重要时使用

### iter-fix

证据驱动的修复循环，以验收检查通过而不是“代码已修改”作为完成标准：

- **建立修复约定**：明确已观察到的失败、预期行为、验收检查与改动范围
- **保护工作区**：保留既有改动，只读取和修改与故障有关的文件
- **诊断根因**：从最窄的可靠复现出发，提出一个可证伪假设
- **最小完整修复**：修正被破坏的约束，必要时补充经济有效的回归测试
- **分层验证**：先运行能否证伪假设的窄检查，再执行受影响范围的回归检查
- **明确停止条件**：验收全部通过才完成；遇到权限、外部状态或架构决策阻塞时如实停止
- **证据化汇报**：给出根因、改动行为、实际执行的验证命令与剩余限制

---

## License

MIT
