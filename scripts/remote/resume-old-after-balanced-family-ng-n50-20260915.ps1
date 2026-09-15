$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$work = 'D:\ccx\work1'
$newBatch = Join-Path $work 'experiments\20260914-balanced-family-ng-n50'
$oldBatch = Join-Path $work 'experiments\20260913-ng-fixing-cutset-le50'
$statusPath = Join-Path $newBatch 'resume-old.status'
$lockPath = Join-Path $newBatch 'resume-old.lock'

try {
    if (-not (Test-Path -LiteralPath $newBatch -PathType Container) -or
        -not (Test-Path -LiteralPath $oldBatch -PathType Container) -or
        -not (Test-Path -LiteralPath (Join-Path $oldBatch 'solve.tsv') -PathType Leaf)) {
        throw 'Expected new batch, old batch, or old solve manifest is missing'
    }
    if (Test-Path -LiteralPath $lockPath) {
        throw "Resume lock already exists: $lockPath"
    }

    New-Item -ItemType Directory -Path $lockPath | Out-Null
    Set-Content -LiteralPath $statusPath -Value 'WAITING_FOR_BALANCED_F3' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $newBatch 'resume-old.pid') -Value $PID -Encoding ASCII

    while ($true) {
        $newStatusPath = Join-Path $newBatch 'experiment.status'
        if (-not (Test-Path -LiteralPath $newStatusPath -PathType Leaf)) {
            Set-Content -LiteralPath (Join-Path $newBatch 'resume-old.heartbeat') `
                -Value (Get-Date -Format o) -Encoding ASCII
            Start-Sleep -Seconds 10
            continue
        }
        $newState = (Get-Content -LiteralPath $newStatusPath -Raw).Trim()
        if ($newState -in @('FINISHED', 'FAILED')) {
            break
        }
        Set-Content -LiteralPath (Join-Path $newBatch 'resume-old.heartbeat') `
            -Value (Get-Date -Format o) -Encoding ASCII
        Start-Sleep -Seconds 60
    }

    # Let the final new-batch child and scheduler exit before reusing six slots.
    Start-Sleep -Seconds 30
    Remove-Item -LiteralPath (Join-Path $oldBatch 'worker.exit-code') -ErrorAction SilentlyContinue
    Set-Content -LiteralPath (Join-Path $oldBatch 'experiment.status') `
        -Value 'RESUME_RUNNING_AFTER_BALANCED_F3' -Encoding ASCII
    Set-Content -LiteralPath $statusPath -Value 'RUNNING_OLD_BATCH' -Encoding ASCII

    $logDir = Join-Path $oldBatch 'scheduler-logs'
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    & (Join-Path $oldBatch 'run.cmd') 'solve.tsv' '6' `
        1> (Join-Path $logDir 'solve-after-balanced-f3.log') `
        2> (Join-Path $logDir 'solve-after-balanced-f3.err.log')
    $exitCode = $LASTEXITCODE

    Set-Content -LiteralPath (Join-Path $oldBatch 'worker.exit-code') `
        -Value $exitCode -Encoding ASCII
    if ($exitCode -eq 0) {
        Set-Content -LiteralPath (Join-Path $oldBatch 'experiment.status') `
            -Value 'FINISHED' -Encoding ASCII
        Set-Content -LiteralPath $statusPath -Value 'FINISHED' -Encoding ASCII
        exit 0
    }

    Set-Content -LiteralPath (Join-Path $oldBatch 'experiment.status') `
        -Value 'FAILED' -Encoding ASCII
    throw "Old batch resume failed: $exitCode"
} catch {
    Set-Content -LiteralPath $statusPath -Value ('FAILED: ' + $_.Exception.Message) -Encoding UTF8
    Write-Error $_
    exit 1
}
