# Claude Code Skills — QA 知识库 + 类比学习法

两个可分享的 Claude Code 技能（skill），安装后可在 Claude Code 对话中直接触发。

---

## 技能列表

| 技能 | 触发词 | 用途 |
|------|--------|------|
| `qa-git-track` | `/qa-git`、`记录到QA里` | 将技术问答与 git commit 绑定，通过 MkDocs + GitHub Pages 发布为可检索的知识库网站 |
| `learn-with-analogy` | `/learn`、`用类比解释` | 用四步结构（概念前置→类比锚定→故事深化→类比边界）讲解研究生水平专业概念 |
| `iter-fix` | `/iter-fix`、`迭代修复` | 自动化"改代码→构建→运行→查日志→分析→再改"的闭环调试流程 |

---

## 快速安装

### Linux / macOS

```bash
git clone https://github.com/this1is1i/claude-skills.git
cd claude-skills
chmod +x install.sh
./install.sh
```

### Windows (PowerShell)

```powershell
git clone https://github.com/this1is1i/claude-skills.git
cd claude-skills
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
Claude: [从分布式系统中挑选 CAP 定理，按四步结构讲解]
        第一步：概念前置 → CAP 定理，一句话定义
        第二步：类比锚定 → 银行柜台 vs ATM
        第三步：故事深化 → 《小李的银行系统升级记》
        第四步：类比边界 → 核心要点 + 类比失效之处

用户: 用类比解释一下 MVCC
Claude: [直接用 MVCC 概念，跳过领域选择，进入四步结构]
```

### Skill 3: 迭代纠错

```
用户: /iter-fix 黑洞显示正常，点击能退出
Claude: [启动 /iter-fix 循环]
        [第1轮] 阅读日志 → glTexImage2D 为 null → 添加回退 → 构建✅ 运行❌ 壁纸crash
        [第2轮] 阅读日志 → ComPtr 不兼容 MinGW → 改用原生 COM → 构建✅ 运行✅
        [完成] 共 2 轮迭代，3 个 bug 修复
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

用类比方法深入讲解研究生水平概念：

- **第一步 — 概念前置**：开篇点明领域、概念名称、一句话定义
- **第二步 — 类比锚定**：从日常经验找类比，建立「旧知→新知」映射表
- **第三步 — 故事深化**：300-800 字叙事，让概念在场景中"活起来"
- **第四步 — 类比边界**：核心要点总结 + 明确指出类比在哪些地方失效

### iter-fix

自动化闭环调试流程，迭代修复直到达到预期效果：

- **Step 0 — 环境准备**：创建日志目录、检测 git 仓库、确认预期效果
- **Step 1 — 阅读阶段**：通读源码、查阅改动记录和最新日志
- **Step 2 — 修改阶段**：基于日志分析确定根因、做出最小化修改
- **Step 3 — 构建阶段**：执行构建命令、失败则回到修改阶段
- **Step 4 — 运行阶段**：运行程序（带超时）、收集退出码
- **Step 5 — 验证阶段**：对比预期效果、未达到则回到阅读阶段
- **Step 6 — 收尾**：清理调试代码、提交改动、汇报结果

---

## License

MIT
