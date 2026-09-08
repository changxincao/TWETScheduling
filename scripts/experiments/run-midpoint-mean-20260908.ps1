param([switch]$Extra50)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$classes="$root/.codex-tmp/midpoint-mean-20260908/classes"
$work=if($Extra50){"$root/.codex-tmp/midpoint-mean-extra50-20260908"}else{"$root/.codex-tmp/midpoint-mean-20260908"}
New-Item -ItemType Directory -Force $work | Out-Null
Set-Location $root
$cp="$classes;$root/.codex-tmp/formal-93685d4a/solver.jar;D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar"
$vm=@('-Djava.library.path=D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64','-Dfile.encoding=UTF-8')
$cases=if($Extra50){@(@('n050-set02',2),@('n050-set03',2),@('n050-set05',2))}else{@(@('n050-set01',2),@('n040-set01',3),@('n050-set01',4))}
foreach($c in $cases){
 $id="$($c[0])-m$($c[1])-random-base-zero"
 $instance="$root/experiment-suite/formal/instances/data/$($c[0])/random/base/zero/m$($c[1]).dat"
 $seed=if($Extra50){"$work/$id.seed"}else{"$root/.codex-tmp/midpoint-reuse-20260908/$id.seed"}
 if(!(Test-Path $seed)){
  if(!$Extra50){throw "Missing shared seed $seed"}
  Write-Output "SEED $id $(Get-Date -Format s)"
  & java @vm -cp $cp Common.formal.FormalExperimentRunner --action=seed "--runId=$id" "--instance=$instance" "--seedFile=$seed" "--outputDir=$work/seed-$id" *> "$work/seed-$id.log"
  if($LASTEXITCODE -ne 0){throw "Seed failed $id"}
 }
 foreach($rep in 1,2){
  $modes=if($rep -eq 1){@('baseline','mean')}else{@('mean','baseline')}
  foreach($mode in $modes){
   $out="$work/$id-$mode-r$rep"
   if(Test-Path $out){throw "Output already exists $out"}
   $enabled=($mode -eq 'mean').ToString().ToLowerInvariant()
   Write-Output "START $id $mode r$rep $(Get-Date -Format s)"
   & java @vm '-Dtwet.bpc.midpointPreviousPricing=false' "-Dtwet.bpc.midpointFirstRoundMean=$enabled" -cp $cp Common.formal.FormalExperimentRunner --action=solve "--runId=$id" "--instance=$instance" "--seedFile=$seed" "--outputDir=$out" --algorithm=NG_DSSR --timeLimitSeconds=1800 --maxNodes=100000 --enableClusterBranching=false --structuredArcStrictTypePriority=false *> "$work/$id-$mode-r$rep.console.log"
   if($LASTEXITCODE -ne 0){throw "Solve failed $out"}
   Get-ChildItem $out -Recurse -Filter *.core-summary.csv | Get-Content
  }
 }
}
