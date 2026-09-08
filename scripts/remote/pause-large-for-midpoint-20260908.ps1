$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$root='D:\ccx\work1\experiments\20260907-formal-n80-n100'
Set-Location -LiteralPath $root
$backup=Join-Path $root 'pause-20260908-midpoint-mean'
if(Test-Path -LiteralPath $backup){throw 'Pause already recorded; inspect before retrying.'}
New-Item -ItemType Directory -Path $backup | Out-Null
$jcmd='D:\Java\jdk-21\bin\jcmd.exe'
$before=@(& $jcmd -l)
$before | Set-Content -LiteralPath "$backup\jvms-before.txt"
$schedulers=@($before | Where-Object {$_ -match '^\d+ HEU\.ExperimentBatchScheduler ' -and $_.Contains("$root\solve.tsv")})
if($schedulers.Count -ne 1){throw "Expected one formal scheduler, got $($schedulers.Count)"}
$workers=@($before | Where-Object {$_ -match '^\d+ Common\.formal\.FormalExperimentRunner ' -and $_.Contains($root.Replace('\','/'))})
Stop-Process -Id ([int]($schedulers[0].Split(' ')[0])) -Force
Start-Sleep -Seconds 2
$remaining=@(& $jcmd -l)
foreach($line in $workers){
    $id=[int]($line.Split(' ')[0])
    if($remaining -contains $line){Stop-Process -Id $id -Force}
}
Start-Sleep -Seconds 3
$after=@(& $jcmd -l)
$after | Set-Content -LiteralPath "$backup\jvms-after.txt"
if(@($after | Where-Object {$_.Contains($root) -or $_.Contains($root.Replace('\','/'))}).Count){throw 'Formal JVMs still present'}
# 仅移动本次中断且没有SUCCESS的输出，给未来续跑留下干净目录。
foreach($line in $workers){
    if($line -notmatch '--outputDir=([^ ]+)'){throw 'Cannot resolve worker output'}
    $path=[IO.Path]::GetFullPath($Matches[1])
    if(!$path.StartsWith("$root\runs\pricing-comparison\",[StringComparison]::OrdinalIgnoreCase)){throw "Unsafe output $path"}
    if((Test-Path -LiteralPath $path) -and !(Test-Path -LiteralPath "$path\SUCCESS")){
        $destination=Join-Path $backup ([IO.Path]::GetFileName($path))
        if(![IO.Path]::GetFullPath($destination).StartsWith("$backup\",[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe destination'}
        Move-Item -LiteralPath $path -Destination $destination
    }
}
Copy-Item -LiteralPath "$root\pipeline.status" -Destination "$backup\pipeline.status.before-pause"
'PAUSED_FOR_MIDPOINT_AB' | Set-Content -LiteralPath "$root\pipeline.status"
$selected=@(Import-Csv -LiteralPath "$root\solve.tsv" -Delimiter "`t" | Where-Object {
    $_.runId -match '^pricing-n(080|100)-.*-ng_dssr$' -and (Test-Path -LiteralPath (Join-Path $_.outputDir 'SUCCESS'))
})
$selected | Export-Csv -LiteralPath "$backup\selected.tsv" -Delimiter "`t" -NoTypeInformation -Encoding UTF8
$results=@(foreach($task in $selected){Get-ChildItem -LiteralPath $task.outputDir -Recurse -Filter *.core-summary.csv | ForEach-Object {Import-Csv -LiteralPath $_.FullName}})
$results | Export-Csv -LiteralPath "$backup\original-results.csv" -NoTypeInformation -Encoding UTF8
"Stopped scheduler=$($schedulers.Count) workers=$($workers.Count); selected=$($selected.Count)"
$results | Select-Object instanceName,status,gapPercent,solveTimeSeconds,processedNodes | Format-Table -AutoSize
