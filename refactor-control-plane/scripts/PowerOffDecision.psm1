Set-StrictMode -Version Latest

function Get-PowerOffDecision {
    [CmdletBinding()]
    param(
        [AllowEmptyString()]
        [string]$ConfirmationPhrase = '',

        [switch]$Verified,

        [switch]$Pushed,

        [AllowEmptyString()]
        [string]$GitStatus = '',

        [AllowEmptyString()]
        [string]$UpstreamDivergence = ''
    )

    if ($ConfirmationPhrase -cne '做完任务关机') {
        return [pscustomobject]@{ ShouldPowerOff = $false; Reason = 'missing-explicit-phrase' }
    }
    if (-not $Verified) {
        return [pscustomobject]@{ ShouldPowerOff = $false; Reason = 'verification-not-confirmed' }
    }
    if (-not $Pushed) {
        return [pscustomobject]@{ ShouldPowerOff = $false; Reason = 'push-not-confirmed' }
    }
    if (-not [string]::IsNullOrWhiteSpace($GitStatus)) {
        return [pscustomobject]@{ ShouldPowerOff = $false; Reason = 'dirty-project' }
    }
    if ($UpstreamDivergence.Trim() -notmatch '^0\s+0$') {
        return [pscustomobject]@{ ShouldPowerOff = $false; Reason = 'branch-not-synced' }
    }

    return [pscustomobject]@{ ShouldPowerOff = $true; Reason = 'explicit-completed-task' }
}

Export-ModuleMember -Function Get-PowerOffDecision
