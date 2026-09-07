#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$stage = "$root/.codex-tmp/large-20260907"
$remote = 'D:/ccx/work1'
$exp = "$remote/experiments/20260907-formal-n80-n100"
$deploy = "$remote/deployments/20260907-93685d4a"
$utf8 = [Text.UTF8Encoding]::new($false)
if (Test-Path $stage) { throw 'Stage already exists' }
New-Item -ItemType Directory -Path "$stage/payload/work1/experiments/20260907-formal-n80-n100/scheduler-logs","$stage/payload/work1/deployments/20260907-93685d4a" -Force | Out-Null
$out = "$stage/payload/work1/experiments/20260907-formal-n80-n100"
$dep = "$stage/payload/work1/deployments/20260907-93685d4a"
$all = @(Import-Csv "$root/experiment-suite/formal/manifests/pricing-comparison.tsv" -Delimiter "`t")
$rows = @($all | Where-Object { [int]$_.size -in 80,100 })
$smoke = @($all | Where-Object { $_.taskSetId -eq 'n020-set01' -and $_.setupType -eq 'random' -and $_.scaleLevel -eq 'base' -and $_.windowLevel -eq 'zero' -and $_.machines -eq '2' })
function Write-Tsv($name,$items) {
 $h = $items[0].PSObject.Properties.Name
 $lines = @($h -join "`t") + @($items | ForEach-Object { $r=$_; ($h | ForEach-Object { [string]$r.$_ }) -join "`t" })
 [IO.File]::WriteAllLines("$out/$name",$lines,$utf8)
}
$audit = @()
foreach($row in @($rows)+@($smoke)) {
 $prefix = '${WORKSPACE}/experiment-suite/formal'
 $row.args=$row.args.Replace("$prefix/instances/data","$remote/instances/no_outsourcing/data").Replace("$prefix/seeds","$exp/seeds").Replace("$prefix/runs","$exp/runs")
 $row.outputDir=$row.outputDir.Replace("$prefix/runs","$exp/runs")
 if($row.action -eq 'solve') {
  $row.args=$row.args.Replace('--timeLimitSeconds="10800"','--timeLimitSeconds="18000"')+' --enableClusterBranching="false" --structuredArcStrictTypePriority="false"'
  $row.dependsOn=''
  if([int]$row.size -eq 20) { $row.args=$row.args.Replace('--timeLimitSeconds="18000"','--timeLimitSeconds="120"').Replace('--maxNodes="100000"','--maxNodes="2"') }
 } else {
  $p=[regex]::Match($row.args,'--instance="([^"]+)"').Groups[1].Value
  $local=$p.Replace("$remote/instances/no_outsourcing/data","$root/experiment-suite/formal/instances/data")
  $dest=$p.Replace('D:/ccx',"$stage/payload")
  New-Item -ItemType Directory -Path (Split-Path $dest) -Force | Out-Null
  Copy-Item -LiteralPath $local -Destination $dest
  $audit += [pscustomobject]@{path=$p;sha256=(Get-FileHash $local).Hash.ToLowerInvariant()}
 }
}
if($rows.Count -ne 2160 -or $smoke.Count -ne 4) {throw 'Wrong row count'}
$seeds=@($rows|Where-Object action -eq seed);$solves=@($rows|Where-Object action -eq solve)
if($seeds.Count -ne 540 -or $solves.Count -ne 1620 -or @($solves|Where-Object {$_.args -notmatch '--timeLimitSeconds="18000"'}).Count) {throw 'Invalid budget'}
Write-Tsv 'seed.tsv' $seeds
Write-Tsv 'solve.tsv' $solves
Write-Tsv 'smoke-seed.tsv' @($smoke|Where-Object action -eq seed)
Write-Tsv 'smoke.tsv' @($smoke|Where-Object action -eq solve)
Write-Tsv 'input-sha256.tsv' $audit
foreach($f in 'solver.jar','source-93685d4a.zip') {Copy-Item "$root/.codex-tmp/formal-93685d4a/$f" $dep}
$run=[IO.File]::ReadAllText("$root/.codex-tmp/formal-93685d4a/run.cmd").Replace('D:\software\Java\jdk-21','D:\Java\jdk-21').Replace('20260905-93685d4a','20260907-93685d4a')
[IO.File]::WriteAllText("$out/run.cmd",$run,$utf8)
$old="$root/experiment-suite/formal/manifests/20260905-formal-scheduling-le60"
foreach($f in 'worker.cmd','pipeline.cmd','guard.cmd') {
 $s=[IO.File]::ReadAllText("$old/$f").Replace('D:\software\Java\jdk-21','D:\Java\jdk-21').Replace('1080','540')
 [IO.File]::WriteAllText("$out/$f",$s,$utf8)
}
$guard=[IO.File]::ReadAllText("$root/scripts/remote/FormalBatchGuard.java").Replace('D:/ccx_work/考虑交付的机器调度','D:/ccx')
[IO.File]::WriteAllText("$out/FormalBatchGuard.java",$guard,$utf8)
$verify=[IO.File]::ReadAllText("$root/.codex-tmp/formal-93685d4a/VerifyInputs.java").Replace('D:/ccx_work/考虑交付的机器调度','D:/ccx')
[IO.File]::WriteAllText("$out/VerifyInputs.java",$verify,$utf8)
Write-Output "Prepared $stage : 540 seeds, 1620 solves, 541 audited input files"
