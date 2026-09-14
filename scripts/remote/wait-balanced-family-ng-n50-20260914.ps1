$ErrorActionPreference = 'Stop'

$work = 'D:\ccx\work1'
$old = Join-Path $work 'experiments\20260913-ng-fixing-cutset-le50'
$next = Join-Path $work 'experiments\20260914-balanced-family-ng-n50'
$status = Join-Path $next 'continuation.status'
try {
    if (-not (Test-Path -LiteralPath (Join-Path $old 'worker.lock')) -or
        -not (Test-Path -LiteralPath (Join-Path $old 'retry-fd-after-batch.ps1')) -or
        -not (Test-Path -LiteralPath (Join-Path $next 'solve.tsv'))) {
        throw 'Expected old batch, retry, or new manifest is missing'
    }
    Set-Location -LiteralPath $next
    Set-Content -LiteralPath $status -Value 'WAITING_FOR_OLD_AND_RETRY' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $next 'continuation.pid') -Value $PID -Encoding ASCII
    while ($true) {
        $oldState = (Get-Content -LiteralPath (Join-Path $old 'experiment.status') -Raw).Trim()
        $retryState = (Get-Content -LiteralPath (Join-Path $old 'retry-fd.status') -Raw).Trim()
        $oldDone = Test-Path -LiteralPath (Join-Path $old 'worker.exit-code')
        if ($oldDone -and $oldState -in @('FINISHED', 'FAILED') -and
            ($retryState -eq 'SUCCEEDED' -or $retryState.StartsWith('FAILED'))) {
            break
        }
        Set-Content -LiteralPath (Join-Path $next 'continuation.heartbeat') -Value (Get-Date -Format o) -Encoding ASCII
        Start-Sleep -Seconds 60
    }
    # 两个旧进程在写入结束状态后还需退出，避免新批次与重试短暂重叠。
    Start-Sleep -Seconds 30
    Set-Content -LiteralPath $status -Value 'RUNNING' -Encoding ASCII
    & (Join-Path $next 'worker.cmd') 1> (Join-Path $next 'scheduler-logs\worker.log') `
        2> (Join-Path $next 'scheduler-logs\worker.err.log')
    $code = $LASTEXITCODE
    Set-Content -LiteralPath (Join-Path $next 'continuation.exit-code') -Value $code -Encoding ASCII
    if ($code -ne 0) { throw "New batch failed: $code" }
    Set-Content -LiteralPath $status -Value 'FINISHED' -Encoding ASCII
} catch {
    Set-Content -LiteralPath $status -Value ('FAILED: ' + $_.Exception.Message) -Encoding UTF8
    Write-Error $_
    exit 1
}
