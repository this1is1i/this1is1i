# QA Git-Tracked Knowledge Base

将项目中的技术问答（QA）与 git 提交版本绑定，并通过 MkDocs + GitHub Pages 发布为可检索的知识库网站，同时支持 Obsidian 本地编辑与图谱浏览。

## 触发短语

- "记录到QA里" / "把这个问题记下来" / "记录这个问答"
- "创建QA知识库" / "搭建QA文档站" / "初始化QA追踪系统"
- "QA版本追踪" / "更新QA索引" / "重建QA元数据"
- "/qa-track" / "/qa-git"

## 何时使用

1. 项目开发过程中积累了大量技术决策、架构讨论、bug 修复经验，需要系统化沉淀
2. 需要将问答与代码版本绑定 —— 知道某个回答在哪个 commit 之后可能已过时
3. 需要将 QA 文档发布为可公开访问的网站（团队成员无需 clone 仓库即可检索）
4. 同时使用 Obsidian 本地编辑（wikilink 跳转 + 图谱可视化）+ MkDocs 线上发布

## 核心概念

### Git 哈希标注

每个 Q# 标题行末尾标注该回答最后一次被验证/更新时的 git commit 短哈希：

```markdown
## Q42: Actor-Critic 排序打分完整流程（2026-06-08）（git: b8cf5ce） ^q42
```

`（git: b8cf5ce）` 表示该回答在 commit `b8cf5ce` 的代码版本上是准确的。读者可以通过 `git diff b8cf5ce..HEAD` 查看自那时以来的代码变更，判断回答是否可能已过时。

### 块锚点（Block Anchor）

标题行末尾的 `^qN` 是 Obsidian 块锚点，用于精确定位到某个问答条目：

```markdown
## Q42: 标题 ^q42
```

在 Obsidian 中，`[[#^q42]]` 可以直接跳转到这个标题。构建 MkDocs 时，`hooks.py` 将其转换为 `{#q42}` 标准锚点。

### Wikilink 交叉引用

QA 条目之间通过 Obsidian wikilink 互相关联：

```markdown
**关联**: [[QA_2026-06-07_2026-06-08_v3#^q42|Q42]], [[QA_2026-05-16_2026-06-02_v1#^q2|Q2]]
```

构建时 `hooks.py` 将 wikilink 转换为标准 Markdown 链接：

```
[Q42](QA_2026-06-07_2026-06-08_v3.md#q42)
```

### 模块 → Q# 映射

在 `.claude/rules/qa-tracking.md` 中维护代码路径到 Q# 的映射表。每次 `git commit` 后，通过 `git diff` 找出变更文件，对照映射表自动识别受影响的 Q#，判断是否需要更新回答内容和 hash。

### 标注原则

| 场景 | 操作 |
|------|------|
| 新增 Q | 标注当前提交 hash |
| 编辑已有 Q 的内容 | 更新为本次提交 hash |
| 代码改动但 Q 结论不变 | hash 保持不变 |
| 旧 Q（创建时未标注） | 标注为上一提交 hash，不向前回溯 |
| 一个 Q 多次修改 | hash 始终为最近一次修改该 Q 的提交 |

## QA 条目格式规范

### 完整模板

```markdown
## Q{N}: {中文标题}（{YYYY-MM-DD}）（git: {7位短hash}） ^q{n}

**标签**: #主题1 #主题2 #主题3
**关联**: [[file1#^qA|QA]], [[file2#^qB|QB]]

### 背景/场景
（为什么会有这个问题，当时的上下文）

### 回答正文
（通俗解释 + 具体实例 + 代码引用）

### 关键文件
- `path/to/file.py:行号` — 相关代码位置
- `path/to/another.java:行号` — 另一个相关位置
```

### 标题行各要素

| 要素 | 必填 | 说明 |
|------|------|------|
| `Q{N}` | ✅ | 序号，全局唯一，不重复。支持 `Q5追问` 形式 |
| 中文标题 | ✅ | 一句话概括问答内容 |
| 日期 `（YYYY-MM-DD）` | ✅ | 问答创建或最后更新日期 |
| git hash `（git: xxxxxxx）` | ✅ | `git log --oneline` 的 7 位短 hash |
| 块锚点 `^q{n}` | ✅ | Obsidian 锚点，小写 q + 数字，如 `^q42` |

### 文件命名规则

```
docs/QA_{首条日期}_{末条日期}_v{版本号}.md
```

示例：`docs/QA_2026-06-02_2026-06-05_v2.md`

- 单个文件不超过 **1000 行**。超过时创建新文件，版本号递增，日期范围以实际首末条为准。
- 每个文件开头和末尾各维护一个索引表：`| Q# | 主题 | 日期 |`

## 工作流程

### 日常：记录问答

```
用户提问 → Claude 回答 → 用户说"记录到QA里"
                              │
                              ▼
                    判断技术深度（架构/算法/设计决策 → 记录；简单确认 → 跳过）
                              │
                              ▼
                    生成 Q# 标题（自动生成或用户指定）
                              │
                              ▼
                    追加到当前活跃 QA 文件末尾
                              │
                              ▼
                    检查文件是否超过 1000 行（超过则创建新卷）
                              │
                              ▼
                    标注当前 git commit hash
                              │
                              ▼
                    git commit
```

### 提交后：版本追踪

```
git commit 完成
      │
      ▼
git diff HEAD~1..HEAD 查看变更文件
      │
      ▼
对照 .claude/rules/qa-tracking.md 模块→Q# 映射表
      │
      ├── 有匹配 Q# → 检查回答是否仍准确
      │                ├── 需要更新 → 编辑内容 + 更新 hash
      │                └── 结论不变 → hash 保持不变
      │
      └── 无匹配 → 结束
```

### 定期：元数据维护

```bash
# 添加或编辑 Q# 条目后运行（幂等，可重复执行）
python scripts/build_qa_metadata.py
```

该脚本自动完成：
1. 为每个 `## Q{N}:` 标题追加 `^q{n}` 块锚点
2. 在标题下方插入 `**标签**` 和 `**关联**` 元数据行
3. 将文件内目录章节的 markdown 链接转为 Obsidian wikilink
4. 重建 `docs/索引.md` MOC 文件（按主题簇分类）

### 部署：发布到 GitHub Pages

```bash
# 本地预览
mkdocs serve          # http://localhost:8000

# 构建
mkdocs build          # 生成 site/ 目录

# 部署（或通过 GitHub Actions 自动部署）
mkdocs gh-deploy
```

推送 `main` 分支且 `docs/**`、`mkdocs.yml` 或 `.github/workflows/docs.yml` 变更时，GitHub Actions 自动构建并部署。

## 项目初始化清单

从头搭建 QA 知识库的步骤：

### 1. 创建目录结构

```
project/
├── docs/                          # QA 文档根目录
│   └── QA_YYYY-MM-DD_v1.md        # 第一个 QA 文件
├── scripts/
│   └── build_qa_metadata.py       # 元数据维护脚本
├── .claude/rules/
│   └── qa-tracking.md             # 项目级 QA 追踪规则
├── .github/workflows/
│   └── docs.yml                   # GitHub Pages 自动部署
├── hooks.py                       # Obsidian → MkDocs 转换钩子
├── mkdocs.yml                     # MkDocs 配置
└── .gitignore                     # docs/* 默认忽略，仅放行必要文件
```

### 2. 创建第一个 QA 文件

```markdown
# QA 问答记录 v1

> Q0 – Q{N} | {首条日期} → {末条日期}

## 索引

| Q# | 主题 | 日期 |
|----|------|------|

---

## Q0: 项目技术栈总览（2026-06-01）（git: abc1234） ^q0

**标签**: #架构 #技术栈
**关联**: Q4, Q29

### 背景/场景
项目启动时的技术选型讨论。

### 回答正文
（内容）
```

### 3. 创建 mkdocs.yml

```yaml
site_name: 项目知识库
site_url: https://<username>.github.io/<repo>/
theme:
  name: material
  language: zh
  features:
    - navigation.top
    - search.suggest
    - content.code.copy
  palette:
    - scheme: default
      toggle:
        icon: material/brightness-7
        name: 深色模式
    - scheme: slate
      toggle:
        icon: material/brightness-4
        name: 浅色模式

plugins:
  - search:
      lang: [zh, en]

markdown_extensions:
  - pymdownx.highlight
  - pymdownx.superfences
  - pymdownx.inlinehilite
  - pymdownx.tasklist
  - toc:
      permalink: true
  - admonition
  - footnotes
  - md_in_html

hooks:
  - hooks.py

nav:
  - 首页: 索引.md
  - QA v4: QA_YYYY-MM-DD_v4.md
  - QA v3: QA_YYYY-MM-DD_v3.md
  # ... 按时间倒序排列
```

### 4. 创建 hooks.py

```python
"""
MkDocs 构建钩子：转换 Obsidian 格式到 MkDocs 格式。
  1. 标题行的 ^qN 块锚点 → {#qN} MkDocs 锚点
  2. [[file#^qN|QN]] wikilink → [QN](file.md#qN) 标准链接

源文件保持 Obsidian 兼容，仅在构建时转换。
"""
import re

WIKILINK_RE = re.compile(r'\[\[([a-zA-Z0-9_./-]+)#\^([a-zA-Z0-9_-]+)\|([^\]]+)\]\]')
BLOCK_ANCHOR_RE = re.compile(r'\s*\^([a-zA-Z0-9_-]+)\s*$', re.MULTILINE)


def on_page_markdown(markdown, page, config, files):
    # Step 1: ^q5 → {#q5}
    def replace_block_anchor(m):
        return f' {{#{m.group(1)}}}'
    markdown = BLOCK_ANCHOR_RE.sub(replace_block_anchor, markdown)

    # Step 2: [[file#^anchor|text]] → [text](file.md#anchor)
    def replace_wikilink(m):
        fname = m.group(1)
        if not fname.endswith('.md'):
            fname += '.md'
        return f'[{m.group(3)}]({fname}#{m.group(2)})'
    markdown = WIKILINK_RE.sub(replace_wikilink, markdown)

    return markdown
```

### 5. 创建 GitHub Actions 部署文件

`.github/workflows/docs.yml`:

```yaml
name: 部署文档到 GitHub Pages

on:
  push:
    branches: [main]
    paths:
      - 'docs/**'
      - 'mkdocs.yml'
      - '.github/workflows/docs.yml'
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: false

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.11'
      - name: 安装 MkDocs Material
        run: pip install mkdocs-material
      - name: 构建静态站点
        run: mkdocs build
      - name: 上传 Pages artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: site

  deploy:
    needs: build
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - name: 部署到 GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v4
```

在 GitHub 仓库的 **Settings → Pages** 中将 Source 设为 **GitHub Actions**。

### 6. 创建项目级 QA 追踪规则

`.claude/rules/qa-tracking.md`:

```markdown
# QA 问答版本追踪规则

> 本文件继承自 common/qa-tracking.md 系统级通用规则。

## 本项目的 QA 文件索引

| 文件 | Q# 范围 | 状态 |
|------|---------|------|
| `docs/QA_v1.md` | Q0 – Q16 | 活跃 |

## 模块 → Q# 映射

| 代码路径 | 关联 Q# |
|---------|--------|
| `src/core/engine.py` | Q5, Q12 |
| `src/api/handlers.py` | Q3, Q8 |
```

### 7. 配置 .gitignore

```gitignore
# 默认忽略 docs 下所有内容
/docs/*
# 仅放行 QA 文件和架构图
!/docs/QA_*.md
!/docs/索引.md
!/docs/draw/
```

### 8. 验证

```bash
# 本地预览文档站
mkdocs serve
# 浏览器打开 http://localhost:8000
# 检查：wikilink 是否可点击、搜索是否正常、索引页是否完整
```

## 本地查看方式

### 方式一：MkDocs 本地预览

```bash
pip install mkdocs-material
mkdocs serve
# 打开 http://localhost:8000
```

支持全文搜索、深色/浅色模式切换、代码高亮。

### 方式二：Obsidian Vault

1. 打开 Obsidian
2. 点击 "Open folder as vault"
3. 选择 `docs/` 目录
4. 使用 `Ctrl+O` 快速跳转到任意 Q#，`Ctrl+悬停` 预览引用
5. 打开图谱视图（Graph View）查看 Q# 之间的引用网络

两种方式**共享同一套 Markdown 源文件**，无需额外维护。

## 维护脚本参考

### build_qa_metadata.py 核心逻辑

```python
# 1. 正则匹配 Q# 标题行
HEADING_RE = re.compile(r'^(#{1,6}\s+)(Q\d+(?:追问)?)([：:]\s*.+)$')

# 2. 为标题追加块锚点（幂等 — 已有则跳过）
if not line.rstrip().endswith(f'^{anchor}'):
    clean = re.sub(r'\s*\^[a-zA-Z0-9_-]+\s*$', '', line.rstrip())
    line = f'{clean} ^{anchor}'

# 3. 插入元数据（跳过已有元数据行防止重复）
out.append(f'**标签**: {" ".join(tags)}')
out.append(f'**关联**: {", ".join(wikilinks)}')

# 4. 重建索引（按主题簇分类）
# 每个簇列出其下 Q# 的 Obsidian wikilink
```

脚本设计要点：
- **幂等**：重复运行不会产生重复的锚点或元数据行
- **非破坏性**：只修改标题行、元数据区和索引文件，正文内容完全不触碰
- **wikilink 生成**：自动根据 Q# → 文件映射表生成正确的 `[[file#^qN|QN]]` 链接

## 时效性说明

- QA 回答**仅对其标注的 git hash 版本代码绝对可靠**
- 未标注 hash 的 Q 视为内容可能已过期，需优先核实
- 读者可通过 `git diff <hash>..HEAD` 查看自上次验证以来的代码变更
- 建议在 QA 文件 README 或索引页添加时效性声明

## 参考实现

完整的工作示例：`https://github.com/this1is1i/ACSR`

- `docs/QA_*.md` — 四个 QA 卷，51 个问答条目，均带 git hash 标注
- `scripts/build_qa_metadata.py` — 元数据维护脚本
- `hooks.py` — Obsidian → MkDocs 转换钩子
- `mkdocs.yml` — MkDocs Material 配置
- `.github/workflows/docs.yml` — GitHub Pages 自动部署
- 发布站点：`https://this1is1i.github.io/ACSR/`

## 常见问题

**Q: 为什么不用 Confluence/Notion/Wiki？**
A: QA 与代码在同一仓库中，天然绑定 git 版本。不需要额外的账号、权限或服务。Markdown 纯文本，任何编辑器都能打开。

**Q: 哈希标注是手动还是自动？**
A: 手动标注。提交后用 `git diff` 检查受影响的 Q#，手动判断是否需要更新内容，然后更新 hash。这保证了每次 hash 更新都经过了人工审核。

**Q: 已有项目的旧 QA 怎么迁移？**
A: 按当前格式创建新的 QA 文件，旧内容逐条迁移时标注**当前最新 commit 的 hash**（不向前回溯）。在 Q 内容中注明"迁移自旧文档，原始日期为 YYYY-MM-DD"。

**Q: 多个子项目共享一个 QA 知识库？**
A: 在模块 → Q# 映射表中可以按子项目分组，索引页也可以按子项目分簇。Monorepo 场景下特别适用。
