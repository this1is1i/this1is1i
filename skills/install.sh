#!/bin/bash
# ============================================================
# Claude Code Skills 安装脚本 (Linux / macOS)
# 将技能 .md 文件复制到 ~/.claude/skills/ 目录
# ============================================================

set -e

SKILLS_DIR="$HOME/.claude/skills"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "============================================"
echo "  Claude Code Skills 安装"
echo "============================================"
echo ""

# 创建 skills 目录（如不存在）
mkdir -p "$SKILLS_DIR"

# 复制技能文件
echo "→ 安装 qa-git-track.md ..."
cp "$SCRIPT_DIR/qa-git-track.md" "$SKILLS_DIR/"
echo "  ✓ QA Git-Tracked Knowledge Base"

echo "→ 安装 learn-with-analogy.md ..."
cp "$SCRIPT_DIR/learn-with-analogy.md" "$SKILLS_DIR/"
echo "  ✓ Learning Through Analogy（类比学习法）"

echo ""
echo "============================================"
echo "  安装完成！"
echo "============================================"
echo ""
echo "已安装的技能:"
echo "  1. qa-git-track — QA 版本追踪 + 知识库发布"
echo "  2. learn-with-analogy — 类比学习法"
echo ""
echo "使用方式:"
echo "  在 Claude Code 对话中输入 /qa-git 或 /learn 即可触发"
echo ""
echo "如需卸载，删除以下文件即可:"
echo "  rm $SKILLS_DIR/qa-git-track.md"
echo "  rm $SKILLS_DIR/learn-with-analogy.md"
