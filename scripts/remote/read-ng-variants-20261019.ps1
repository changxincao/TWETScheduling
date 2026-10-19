#requires -Version 7.0
param([Parameter(Mandatory)][string]$Output)
$ErrorActionPreference = 'Stop'
# 只读读取第二机清单及小型汇总，不复制列池、完整日志或改变远端任务。
$remote = @'
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$batches = @('20260913-ng-fixing-cutset-le50', '20260914-balanced-family-ng-n50')
$results = foreach ($batch in $batches) {
    $root = Join-Path 'D:\ccx\work1\experiments' $batch
    foreach ($task in (Import-Csv -LiteralPath (Join-Path $root 'solve.tsv') -Delimiter "`t")) {
        $dir = $task.outputDir
        $files = @(Get-ChildItem -LiteralPath $dir -Filter '*.core-summary.csv' -File -Recurse -ErrorAction SilentlyContinue)
        if ($files.Count -gt 1) { throw "Multiple summaries: $dir" }
        $summary = if ($files.Count -eq 1) { @(Import-Csv -LiteralPath $files[0].FullName)[0] } else { $null }
        $stateFile = Join-Path $dir 'status.txt'
        $state = if (Test-Path -LiteralPath $stateFile) { (Get-Content -LiteralPath $stateFile | Where-Object { $_ -like 'state=*' }) -join '' } else { 'MISSING' }
        $configFiles = @(Get-ChildItem -LiteralPath $dir -Filter '*.config.properties' -File -Recurse -ErrorAction SilentlyContinue)
        $config = @{}
        if ($configFiles.Count -eq 1) {
            foreach ($line in (Get-Content -LiteralPath $configFiles[0].FullName)) {
                if ($line -match '^([^#=]+)=(.*)$') { $config[$Matches[1]] = $Matches[2] }
            }
        }
        [pscustomobject]@{
            batch = $batch; runId = $task.runId; variant = $task.block
            n = [int]$task.size; m = [int]$task.machines; setup = $task.setupType
            scale = $task.scaleLevel; window = $task.windowLevel; taskSet = $task.taskSetId
            success = (Test-Path -LiteralPath (Join-Path $dir 'SUCCESS'))
            state = $state; summary = $summary; config = $config; args = $task.args
        }
    }
}
ConvertTo-Json -InputObject @($results) -Depth 5 -Compress
'@
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($remote))
$data = & ssh.exe -i C:\Users\Changxin\.ssh\codex_solver_auto_ed25519 `
    -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes `
    -o UserKnownHostsFile=C:\Users\Changxin\.ssh\known_hosts -o ConnectTimeout=15 `
    codex-runner@100.68.243.192 powershell.exe -NoProfile -NonInteractive -EncodedCommand $encoded
if ($LASTEXITCODE -ne 0) { throw 'Remote summary read failed' }
$json = $data -join "`n"
$parsed = ConvertFrom-Json -InputObject $json
if (@($parsed).Count -ne 2970) { throw "Unexpected manifest count: $(@($parsed).Count)" }
[IO.File]::WriteAllText([IO.Path]::GetFullPath($Output), $json, [Text.UTF8Encoding]::new($false))
"Read $(@($parsed).Count) manifest entries into $Output"
