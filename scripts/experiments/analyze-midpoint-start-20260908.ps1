param([string]$WorkDir="$PSScriptRoot/../../.codex-tmp/midpoint-reuse-20260908")
$ErrorActionPreference='Stop'
$rows=foreach($d in Get-ChildItem $WorkDir -Directory){
 $sf=Get-ChildItem $d.FullName -Recurse -Filter *.core-summary.csv | Select-Object -First 1
 if(!$sf){continue}
 $s=Import-Csv $sf.FullName
 $cf=Get-ChildItem $d.FullName -Recurse -Filter *.components.csv | Select-Object -First 1
 $e=Import-Csv $cf.FullName | Where-Object component -eq GCNGBBStyleNgDssrPricing
 $probe=0.;$rounds=0;$initialCount=0;$reuse=0;$calls=0;$fullF=0.;$fullB=0.;$post=0.
 foreach($l in [IO.File]::ReadLines((Join-Path $d.FullName live.log))){
  if(!$l.StartsWith('Pricing[GCNGBBStyleNgDssrPricing]')){continue};$calls++
  if($l -match 'rounds=(\d+)'){$rounds+=[int]$Matches[1]}
  if($l -match 'exactInitDetailMs setup/diag/sri/window/ng/cb/preCert/probe/state/fullProbe=([\d./]+)'){$probe+=[double]$Matches[1].Split('/')[7]}
  if($l -match 'midpointByDssrRound=r1/[^,]+?probeI=(\d+)'){$initialCount+=[int]$Matches[1]}
  if($l -match 'midpointByDssrRound=r1/[^,]+?seedSource=previousPricing'){$reuse++}
  foreach($m in [regex]::Matches($l,'/ms([\d.]+)-([\d.]+)')){$fullF+=[double]$m.Groups[1].Value;$fullB+=[double]$m.Groups[2].Value}
  if($l -match 'exactPhaseMs total/init/sink/fw/bw/compact/join/finalize=([\d./]+)'){$v=$Matches[1].Split('/');$post+=[double]$v[3]+[double]$v[4]}
 }
 [pscustomobject]@{run=$d.Name;status=$s.status;objective=$s.incumbentCost;gap=$s.gapPercent;nodes=$s.processedNodes;time=[double]$s.solveTimeSeconds;root=[double]$s.rootSolveTimeSeconds;exact=[double]$e.totalSeconds;exactCalls=$calls;rounds=$rounds;probe=$probe/1000;firstCandidates=$initialCount;reusedCalls=$reuse;fullF=$fullF/1000;fullB=$fullB/1000;nonReuseProbeResidual=($probe-$fullF-$fullB+$post)/1000;valid=$s.validationFeasible+'/'+$s.validationObjectiveConsistent}
}
$rows|Export-Csv "$WorkDir/summary.csv" -NoTypeInformation -Encoding utf8
$rows|ConvertTo-Csv -NoTypeInformation
