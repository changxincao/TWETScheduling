$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../..").Path
$local="$repo/.codex-tmp/ng-v6-switch"
$key='C:/Users/Changxin/.ssh/codex_solver_auto_ed25519'
$common=@('-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-i',$key,'-o','UserKnownHostsFile=C:/Users/Changxin/Downloads/solver_known_hosts')
$remoteHost='codex-runner@100.68.243.112'
$root='D:/ccx_work/考虑交付的机器调度/work1'
$tool="$root/tools/ng-v6-switch-20260909"
$formal="$root/experiments/20260905-formal-scheduling-le60"
$cp="$root/deployments/20260909-3038d8a0-v6/solver.jar;D:/software/IBM/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar;D:/software/IBM/ILOG/CPLEX_Studio2211/cpoptimizer/lib/ILOG.CP.jar"
[IO.File]::WriteAllLines("$local/old-settings.properties",@("manifest=$formal/solve.tsv","classpath=$cp","replacement=$tool/ExperimentBatchScheduler.class","receipt=$tool/applied.txt"),[Text.UTF8Encoding]::new($false))
& ssh @common $remoteHost ('cmd.exe /d /c "cd /d '+$root.Replace('/','\')+' && mkdir tools\ng-v6-switch-20260909"')
if($LASTEXITCODE){throw 'mkdir failed'}
foreach($pair in @(@("$local/agent.jar","$tool/agent.jar"),@("$local/classes/HEU/ExperimentBatchScheduler.class","$tool/ExperimentBatchScheduler.class"),@("$local/old-settings.properties","$tool/settings.properties"))) {
 & scp @common $pair[0] ($remoteHost+':'+$pair[1]); if($LASTEXITCODE){throw 'upload failed'}
}
$cmd='cmd.exe /d /c "cd /d '+$formal.Replace('/','\')+' && D:\software\Java\jdk-21\bin\java.exe -cp ../../tools/ng-v6-switch-20260909/agent.jar NgV6SchedulerSwitch 18052 ../../tools/ng-v6-switch-20260909/agent.jar ../../tools/ng-v6-switch-20260909/settings.properties"'
& ssh @common $remoteHost $cmd
if($LASTEXITCODE){throw 'Attach failed; check receipt before retry'}
& ssh @common $remoteHost ('cmd.exe /d /c "cd /d '+$root.Replace('/','\')+' && type tools\ng-v6-switch-20260909\applied.txt && D:\software\Java\jdk-21\bin\jcmd.exe -l"')
