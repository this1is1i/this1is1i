$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\PowerOffDecision.psm1') -Force

function Assert-Decision {
    param([bool]$Expected, [string]$ExpectedReason, [object]$Actual, [string]$Message)
    if ($Expected -ne $Actual.ShouldPowerOff -or $ExpectedReason -ne $Actual.Reason) {
        throw "$Message. Expected '$Expected/$ExpectedReason', got '$($Actual.ShouldPowerOff)/$($Actual.Reason)'."
    }
}

$approved = Get-PowerOffDecision -ConfirmationPhrase '做完任务关机' -Verified -Pushed `
    -GitStatus '' -UpstreamDivergence "0`t0"
Assert-Decision $true 'explicit-completed-task' $approved 'Exact phrase and fresh evidence must approve'

$missingPhrase = Get-PowerOffDecision -Verified -Pushed -GitStatus '' -UpstreamDivergence "0`t0"
Assert-Decision $false 'missing-explicit-phrase' $missingPhrase 'Missing phrase must refuse'

$similarPhrase = Get-PowerOffDecision -ConfirmationPhrase '做完任务后关机' -Verified -Pushed `
    -GitStatus '' -UpstreamDivergence "0`t0"
Assert-Decision $false 'missing-explicit-phrase' $similarPhrase 'Similar phrase must refuse'

$unverified = Get-PowerOffDecision -ConfirmationPhrase '做完任务关机' -Pushed `
    -GitStatus '' -UpstreamDivergence "0`t0"
Assert-Decision $false 'verification-not-confirmed' $unverified 'Unverified work must refuse'

$unpushed = Get-PowerOffDecision -ConfirmationPhrase '做完任务关机' -Verified `
    -GitStatus '' -UpstreamDivergence "0`t0"
Assert-Decision $false 'push-not-confirmed' $unpushed 'Unpushed work must refuse'

$dirty = Get-PowerOffDecision -ConfirmationPhrase '做完任务关机' -Verified -Pushed `
    -GitStatus ' M README.md' -UpstreamDivergence "0`t0"
Assert-Decision $false 'dirty-project' $dirty 'Dirty project must refuse'

$ahead = Get-PowerOffDecision -ConfirmationPhrase '做完任务关机' -Verified -Pushed `
    -GitStatus '' -UpstreamDivergence "1`t0"
Assert-Decision $false 'branch-not-synced' $ahead 'Unsynced branch must refuse'

Write-Host 'Power-off decision tests passed: 7/7'
