param([Parameter(Mandatory)][string]$Csv)
$ErrorActionPreference='Stop'
$rows=@(Import-Csv $Csv)
foreach($r in $rows){
 if($r.runId -notmatch '^pricing-n(\d+)-set\d+-m\d+-(random|family)-(base|medium|high)-.*-w(zero|narrow|wide)-(ng_dssr|time_indexed_sri|time_indexed)$'){throw "Unexpected id $($r.runId)"}
 $r | Add-Member NoteProperty n ([int]$Matches[1])
 $r | Add-Member NoteProperty setup $Matches[2]
 $r | Add-Member NoteProperty scale $Matches[3]
 $r | Add-Member NoteProperty window $Matches[4]
 $r | Add-Member NoteProperty algorithm $Matches[5]
 $r | Add-Member NoteProperty scenario ($r.runId -replace '-(ng_dssr|time_indexed_sri|time_indexed)$','')
}
"COMPLETED=$($rows.Count)"
$rows | Group-Object n,algorithm,status | ForEach-Object {"COUNT $($_.Name) $($_.Count)"}
$common=@($rows | Group-Object scenario | Where-Object {$_.Count -eq 3 -and @($_.Group.algorithm | Select-Object -Unique).Count -eq 3} | ForEach-Object {$_.Group})
"COMMON_SCENARIOS=$($common.Count/3)"
foreach($dimensions in @(@('n','setup','algorithm'),@('n','setup','scale','algorithm'))){
 $common | Group-Object -Property $dimensions | Sort-Object Name | ForEach-Object {
  $g=$_.Group
  $time=($g | Measure-Object seconds -Average).Average
  $gap=($g | Measure-Object gap -Average).Average
  $nodes=($g | Measure-Object nodes -Average).Average
  $optimal=@($g | Where-Object { $_.gap -ne '' -and [double]$_.gap -ge 0 -and [double]$_.gap -le 0.000001 }).Count
  '{0} count={1} optimal={2} seconds={3:F2} gap={4:F4} nodes={5:F2}' -f $_.Name,$g.Count,$optimal,$time,$gap,$nodes
 }
}
