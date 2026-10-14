#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$stage = Join-Path $repo '.codex-tmp/n80-cluster-continuation-20261014'
$audit = Get-Content -LiteralPath "$stage/source-audit.json" -Raw | ConvertFrom-Json
$pending = @($audit.items | Where-Object { -not $_.done })
if ($audit.count -ne 540 -or $pending.Count -ne 180) { throw 'Unexpected source task count' }
$source = 'D:/ccxWork/work1/experiments/20261014-n80-cluster-transfer'
$target = 'D:/ccx_work/考虑交付的机器调度/work1/experiments/20261014-n80-cluster-continuation'
$utf8 = [Text.UTF8Encoding]::new($false)

function Write-Tsv($path, $rows) {
    $columns = @($rows[0].PSObject.Properties.Name)
    $lines = @($columns -join "`t") + @($rows | ForEach-Object {
        $row = $_
        ($columns | ForEach-Object { [string]$row.$_ }) -join "`t"
    })
    [IO.File]::WriteAllLines($path, $lines, $utf8)
}

# 仅改路径，算法参数和任务顺序逐项保留；同一物理实例的两算法复用源 seed。
$assets = [ordered]@{}
$rows = @()
$origins = @()
foreach ($item in $pending) {
    $row = $item.row
    $argsText = $row.args
    $input = [regex]::Match($argsText, '--instance="([^"]+)"').Groups[1].Value
    $seed = [regex]::Match($argsText, '--seedFile="([^"]+)"').Groups[1].Value
    if (-not $input.StartsWith('D:/ccxWork/work1/') -or -not $seed.StartsWith('D:/ccxWork/work1/')) {
        throw "Invalid source asset path: $($row.runId)"
    }
    $scenario = [IO.Path]::GetFileNameWithoutExtension($seed)
    $inputRelative = "assets/inputs/$scenario.dat"
    $seedRelative = "assets/seeds/$scenario.seed"
    foreach ($pair in @(@($input, $inputRelative), @($seed, $seedRelative))) {
        if ($assets.Contains($pair[1]) -and $assets[$pair[1]] -ne $pair[0]) { throw 'Asset collision' }
        $assets[$pair[1]] = $pair[0]
    }
    if ($row.size -ne '80' -or $row.algorithm -notin @('TIME_INDEXED','TIME_INDEXED_SRI') -or
        $argsText -notmatch '--timeLimitSeconds="18000"' -or
        $argsText -notmatch '--enableClusterBranching="true"' -or
        $argsText -notmatch '--structuredArcStrictTypePriority="true"') { throw 'Config mismatch' }
    $oldOutput = $row.outputDir
    $newOutput = "$target/runs/pricing-comparison/$($row.runId)"
    $row.args = $argsText.Replace($input, "$target/$inputRelative").Replace($seed, "$target/$seedRelative").Replace($oldOutput, $newOutput)
    $row.outputDir = $newOutput
    $origins += [pscustomobject][ordered]@{runId=$row.runId; sourceOutput=$oldOutput; sourceState=$item.state; targetOutput=$newOutput; sourceFrozenDeployment='15570109-cluster-v6'; continuationDeployment='bbe53192-ti-retry'}
    $rows += $row
}
Write-Tsv "$stage/solve.tsv" $rows
Write-Tsv "$stage/origin-map.tsv" $origins
Write-Tsv "$stage/assets.tsv" @($assets.GetEnumerator() | ForEach-Object {
    [pscustomobject][ordered]@{source=$_.Value;relative=$_.Key}
})

$exportScript = @'
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$root='D:\ccxWork\work1\experiments\20261014-n80-cluster-transfer'
if (Test-Path -LiteralPath "$root\assets.zip") { throw 'Transfer archive already exists; audit before retry' }
$rows=Import-Csv -LiteralPath "$root\assets.tsv" -Delimiter "`t"
$hashes=@(foreach($r in $rows){
 $src=[IO.Path]::GetFullPath($r.source)
 $dst=[IO.Path]::GetFullPath((Join-Path $root $r.relative))
 if(-not $src.StartsWith('D:\ccxWork\work1\',[StringComparison]::OrdinalIgnoreCase) -or
    -not $dst.StartsWith("$root\assets\",[StringComparison]::OrdinalIgnoreCase)){throw 'Asset path outside allowed root'}
 New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($dst))|Out-Null
 Copy-Item -LiteralPath $src -Destination $dst
 [pscustomobject]@{relative=$r.relative;sha256=(Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $src).Length}
})
$hashes|Export-Csv -LiteralPath "$root\asset-hashes.csv" -NoTypeInformation -Encoding UTF8
Compress-Archive -LiteralPath "$root\assets","$root\asset-hashes.csv" -DestinationPath "$root\assets.zip"
"exported=$($hashes.Count)"
'@
[IO.File]::WriteAllText("$stage/export-assets.ps1", $exportScript, $utf8)

$importScript = @'
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$root=$PSScriptRoot
if(-not ([IO.Path]::GetFullPath($root)).StartsWith('D:\ccx_work\考虑交付的机器调度\work1\experiments\',[StringComparison]::OrdinalIgnoreCase)){throw 'Invalid target root'}
if(Test-Path -LiteralPath "$root\assets"){throw 'Assets already exist; do not overwrite'}
Expand-Archive -LiteralPath "$root\assets.zip" -DestinationPath $root
$rows=Import-Csv -LiteralPath "$root\asset-hashes.csv"
foreach($r in $rows){
 $path=[IO.Path]::GetFullPath((Join-Path $root $r.relative))
 if(-not $path.StartsWith("$root\assets\",[StringComparison]::OrdinalIgnoreCase)){throw 'Invalid asset path'}
 if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $r.sha256){throw "Hash mismatch: $path"}
}
"verified=$($rows.Count)"
'@
[IO.File]::WriteAllText("$stage/import-assets.ps1", $importScript, $utf8)
$run = @'
@echo off
setlocal
cd /d "%~dp0"
set "JAVA=D:\software\Java\jdk-21\bin\java.exe"
set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CP=%~dp0../../deployments/20260927-bbe53192-ti-retry/solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"
"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\cplex\bin\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler "%~dp0solve.tsv" 6 %1
exit /b %ERRORLEVEL%
'@
[IO.File]::WriteAllText("$stage/run.cmd", $run.Replace("`n","`r`n"), $utf8)
$worker = @'
@echo off
setlocal
cd /d "%~dp0"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 90
if not exist scheduler-logs mkdir scheduler-logs
echo SOLVE_RUNNING>experiment.status
call "%~dp0run.cmd" >"%~dp0scheduler-logs/solve.out.log" 2>"%~dp0scheduler-logs/solve.err.log"
set "RESULT=%ERRORLEVEL%"
if "%RESULT%"=="0" (echo FINISHED>experiment.status) else (echo FAILED>experiment.status)
rmdir worker.lock
exit /b %RESULT%
'@
[IO.File]::WriteAllText("$stage/worker.cmd", $worker.Replace("`n","`r`n"), $utf8)
Write-Output "Prepared $($rows.Count) tasks, $($assets.Count) assets; source=$source; target=$target"
