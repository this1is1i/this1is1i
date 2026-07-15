# ============================================================
# Claude Code Skills 安装脚本 (Windows PowerShell)
# 将技能 .md 文件复制到 ~\.claude\skills\ 目录
# ============================================================

$ErrorActionPreference = "Stop"

$SkillsDir = "$env:USERPROFILE\.claude\skills"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Claude Code Skills 安装" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# 创建 skills 目录（如不存在）
New-Item -ItemType Directory -Force -Path $SkillsDir | Out-Null

# 复制技能文件
Write-Host "→ 安装 qa-git-track.md ..."
Copy-Item "$ScriptDir\qa-git-track.md" -Destination "$SkillsDir\" -Force
Write-Host "  ✓ QA Git-Tracked Knowledge Base" -ForegroundColor Green

Write-Host "→ 安装 learn-with-analogy.md ..."
Copy-Item "$ScriptDir\learn-with-analogy.md" -Destination "$SkillsDir\" -Force
Write-Host "  ✓ Learning Through Analogy（类比学习法）" -ForegroundColor Green

Write-Host "→ 安装 iter-fix.md ..."
Copy-Item "$ScriptDir\iter-fix.md" -Destination "$SkillsDir\" -Force
Write-Host "  ✓ Iter-Fix（证据驱动的迭代修复）" -ForegroundColor Green

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  安装完成！" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "已安装的技能:"
Write-Host "  1. qa-git-track — QA 版本追踪 + 知识库发布"
Write-Host "  2. learn-with-analogy — 类比学习法"
Write-Host "  3. iter-fix — 证据驱动的迭代修复"
Write-Host ""
Write-Host "使用方式:"
Write-Host "  在 Claude Code 对话中输入 /qa-git、/learn 或 /iter-fix 即可触发"
Write-Host ""
Write-Host "如需卸载，删除以下文件即可:"
Write-Host "  Remove-Item $SkillsDir\qa-git-track.md"
Write-Host "  Remove-Item $SkillsDir\learn-with-analogy.md"
Write-Host "  Remove-Item $SkillsDir\iter-fix.md"
