param([switch]$Repeat, [switch]$ExtraOnly)
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$work="$root/.codex-tmp/midpoint-reuse-20260908"
Set-Location $root
$cp="$work/classes;$root/.codex-tmp/formal-93685d4a/solver.jar;D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar"
$vm=@('-Djava.library.path=D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64','-Dfile.encoding=UTF-8')
$cases=@(@('n040-set01',3,'zero'),@('n040-set02',3,'zero'),@('n050-set01',4,'zero'))
if($ExtraOnly){$cases=,@('n050-set01',2,'zero')}
foreach($c in $cases){
 $id="$($c[0])-m$($c[1])-random-base-$($c[2])"
 $instance="$root/experiment-suite/formal/instances/data/$($c[0])/random/base/$($c[2])/m$($c[1]).dat"
 $seed="$work/$id.seed"
 if(!(Test-Path $seed)){
  & java @vm -cp $cp Common.formal.FormalExperimentRunner --action=seed "--runId=$id" "--instance=$instance" "--seedFile=$seed" "--outputDir=$work/seed-$id" *> "$work/seed-$id.log"
  if($LASTEXITCODE -ne 0){throw "seed failed $id"}
 }
 $modes=if($Repeat){@('reuse','baseline')}else{@('baseline','reuse')}
 foreach($mode in $modes){
  $suffix=if($Repeat){'-repeat'}else{''}
  $out="$work/$id-$mode$suffix"
  if(Test-Path $out){throw "exists $out"}
  $enabled=($mode -eq 'reuse').ToString().ToLowerInvariant()
  Write-Output "START $id $mode $(Get-Date -Format s)"
  & java @vm "-Dtwet.bpc.midpointPreviousPricing=$enabled" -cp $cp Common.formal.FormalExperimentRunner --action=solve "--runId=$id" "--instance=$instance" "--seedFile=$seed" "--outputDir=$out" --algorithm=NG_DSSR --timeLimitSeconds=1800 --maxNodes=100000 --enableClusterBranching=false --structuredArcStrictTypePriority=false *> "$work/$id-$mode$suffix.console.log"
  if($LASTEXITCODE -ne 0){throw "solve failed $id $mode"}
  Get-ChildItem $out -Recurse -Filter *.core-summary.csv | Get-Content
 }
}
