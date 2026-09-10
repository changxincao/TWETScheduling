$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../..").Path
$root=Join-Path $repo ('.codex-tmp/ng-v6-switch/plan-test-'+[DateTime]::Now.ToString('HHmmss'))
New-Item -ItemType Directory -Force -Path "$root/formal","$root/ab","$root/control" | Out-Null
$rows=[Collections.Generic.List[string]]::new()
$rows.Add("runId`tmainClass`targs`toutputDir`talgorithm")
foreach($pair in @(@('old','NG_DSSR'),@('mean','NG_DSSR'),@('pending','NG_DSSR'),@('ti-done','TIME_INDEXED'),@('ti-pending','TIME_INDEXED_SRI'))){
 $id=$pair[0]; $output="$root/formal/runs/$id"
 $rows.Add("$id`tCommon.formal.FormalExperimentRunner`t--outputDir=`"$output`" --algorithm=$($pair[1])`t$output`t$($pair[1])")
}
[IO.File]::WriteAllLines("$root/formal/solve.tsv",$rows,[Text.UTF8Encoding]::new($false))
foreach($path in @('formal/runs/old','formal/runs/mean','ab/runs/mean-mean','formal/runs/ti-done')){
 New-Item -ItemType Directory -Force -Path "$root/$path" | Out-Null
 [IO.File]::WriteAllText("$root/$path/SUCCESS",'test')
}
[IO.File]::WriteAllLines("$root/control/continuation.properties",@("formal=$root/formal","ab=$root/ab").Replace('\','/'),[Text.UTF8Encoding]::new($false))
& 'D:/软件/Java/jdk_22/bin/java.exe' -cp "$repo/.codex-tmp/ng-v6-switch/control.jar" NgV6Continuation "$root/control" plan
if($LASTEXITCODE){throw 'Plan execution failed'}
$p=@(Import-Csv "$root/control/plan.tsv" -Delimiter "`t")
$index=@(Import-Csv "$root/control/plan-index.tsv" -Delimiter "`t")
if($p.Count -ne 2 -or $index.Count -ne 5){throw 'Wrong row counts'}
if(($p | Where-Object runId -eq pending).outputDir.Replace('\','/') -ne "$root/control/runs/pending".Replace('\','/')){throw 'NG output not redirected'}
if(($p | Where-Object runId -eq ti-pending).outputDir -ne "$root/formal/runs/ti-pending"){throw 'TI output changed'}
if(($index | Where-Object runId -eq mean).source -ne 'mean-experiment'){throw 'Mean not reused'}
'PASS: old success retained, mean preferred, pending NG redirected, TI unchanged'
