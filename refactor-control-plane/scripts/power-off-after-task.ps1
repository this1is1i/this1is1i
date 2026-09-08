[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ConfirmationPhrase,

    [Parameter(Mandatory)]
    [switch]$Verified,

    [Parameter(Mandatory)]
    [switch]$Pushed,

    [ValidateRange(1, 3600)]
    [int]$ShutdownDelaySeconds = 60,

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Import-Module (Join-Path $PSScriptRoot 'PowerOffDecision.psm1') -Force

$gitStatusOutput = & git -C $projectRoot status --porcelain -- . 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Cannot verify project Git status: $($gitStatusOutput -join [Environment]::NewLine)"
}
$upstreamOutput = & git -C $projectRoot rev-list --left-right --count 'HEAD...@{upstream}' 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Cannot verify the upstream branch: $($upstreamOutput -join [Environment]::NewLine)"
}

$decision = Get-PowerOffDecision `
    -ConfirmationPhrase $ConfirmationPhrase `
    -Verified:$Verified `
    -Pushed:$Pushed `
    -GitStatus ($gitStatusOutput -join [Environment]::NewLine) `
    -UpstreamDivergence ($upstreamOutput -join ' ')

if (-not $decision.ShouldPowerOff) {
    throw "Power-off refused: $($decision.Reason)"
}

if ($DryRun) {
    Write-Host "DRY RUN: explicit power-off approved with ${ShutdownDelaySeconds}s delay; shutdown.exe was not called."
    exit 0
}

$shutdownExecutable = Join-Path $env:SystemRoot 'System32\shutdown.exe'
& $shutdownExecutable /s /t $ShutdownDelaySeconds /c 'Refactor Control Plane: verified task completed and pushed'
if ($LASTEXITCODE -ne 0) {
    throw "shutdown.exe failed with exit code $LASTEXITCODE"
}
Write-Host "Shutdown scheduled in $ShutdownDelaySeconds seconds. Run 'shutdown.exe /a' to cancel."
