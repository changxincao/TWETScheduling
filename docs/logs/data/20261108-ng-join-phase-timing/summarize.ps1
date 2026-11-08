param([Parameter(Mandatory=$true)][string]$InputLog,
      [Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$culture=[Globalization.CultureInfo]::InvariantCulture
$records=@(Get-Content -LiteralPath $InputLog | Where-Object { $_.StartsWith('[NG join timing]') } | ForEach-Object {
    $v=@{}
    foreach($m in [regex]::Matches($_,'(\w+)=([^ ]+)')) { $v[$m.Groups[1].Value]=$m.Groups[2].Value }
    $row=[ordered]@{}
    foreach($key in @('node','pricingCall','nodePricingCall','dssrRound','repair')) { $row[$key]=$v[$key] }
    foreach($key in @('totalMs','boundMs','bitmapMs','remainingLabelsMs','prefilterOtherMs','otherMs',
                     'functionWithinBoundMs','buildWithinOtherMs')) {
        $row[$key]=[double]::Parse($v[$key],$culture)
        if($row[$key] -lt 0) { throw "Negative timing $key : $_" }
    }
    foreach($key in @('groupChecks','groupsPruned','functionCalls','fwGroups','bwGroups')) {
        $row[$key]=[long]::Parse($v[$key],$culture)
    }
    $sum=$row.boundMs+$row.bitmapMs+$row.remainingLabelsMs+$row.prefilterOtherMs+$row.otherMs
    if([Math]::Abs($sum-$row.totalMs) -gt 0.00001) { throw 'Round timing does not close.' }
    if($row.functionWithinBoundMs -gt $row.boundMs+0.00001) { throw 'Nested function time exceeds bound time.' }
    if($row.buildWithinOtherMs -gt $row.otherMs+0.00001) { throw 'Index build time exceeds other time.' }
    [pscustomobject]$row
})
if($records.Count -eq 0) { throw 'No completed join timing records.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$records | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'rounds.csv') -NoTypeInformation -Encoding UTF8
$summary=[ordered]@{sourceLog=(Resolve-Path -LiteralPath $InputLog).Path; accountingChecks='passed'; groups=@()}
foreach($minimum in @(0,1000,5000)) {
    $selected=@($records | Where-Object { $_.totalMs -ge $minimum })
    $group=[ordered]@{minimumJoinMs=$minimum;rounds=$selected.Count}
    foreach($key in @('totalMs','boundMs','bitmapMs','remainingLabelsMs','prefilterOtherMs','otherMs',
                     'functionWithinBoundMs','buildWithinOtherMs','groupChecks','groupsPruned','functionCalls')) {
        $group[$key]=($selected | Measure-Object -Property $key -Sum).Sum
    }
    foreach($key in @('boundMs','bitmapMs','remainingLabelsMs','prefilterOtherMs','otherMs')) {
        $group[$key+'Percent']=if($group.totalMs -gt 0) {100*$group[$key]/$group.totalMs} else {$null}
    }
    $summary.groups+=([pscustomobject]$group)
}
$summary.slowest=@($records | Sort-Object totalMs -Descending | Select-Object -First 5)
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding UTF8
$summary | ConvertTo-Json -Depth 6
