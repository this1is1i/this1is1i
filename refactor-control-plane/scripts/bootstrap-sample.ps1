$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$target = Join-Path $projectRoot "samples\workspaces\springmvc-blog"
$repository = "https://github.com/eyupgevenim/SpringMvcBlog.git"
$commit = "5e62608af333b5757bf767e05b19e2b0d56f0afb"

if (-not (Test-Path -LiteralPath (Join-Path $target ".git"))) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    git clone --no-checkout $repository $target
    if ($LASTEXITCODE -ne 0) { throw "Failed to clone legacy sample" }
}

git -C $target fetch origin
if ($LASTEXITCODE -ne 0) { throw "Failed to fetch legacy sample" }
git -C $target checkout --detach $commit
if ($LASTEXITCODE -ne 0) { throw "Failed to checkout pinned legacy sample commit" }
$actual = git -C $target rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actual.Trim() -ne $commit) {
    throw "Legacy sample SHA mismatch: expected $commit, got $actual"
}

Write-Output "Legacy sample ready at $target ($commit)"
