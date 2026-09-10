param([switch]$Retry)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../..").Path
$root=Join-Path $repo ('.codex-tmp/ng-v6-switch/test-'+[DateTime]::Now.ToString('HHmmss'))
New-Item -ItemType Directory -Path "$root/classes" -Force | Out-Null
$java='D:/软件/Java/jdk_22/bin/java.exe'
& 'D:/软件/Java/jdk_22/bin/javac.exe' --release 21 -d "$root/classes" "$PSScriptRoot/NgV6SwitchProbe.java"
& 'D:/软件/Java/jdk_22/bin/jar.exe' cf "$root/probe.jar" -C "$root/classes" .
$old="$repo/.codex-tmp/formal-93685d4a/solver.jar;$root/probe.jar"
$next="$repo/.codex-tmp/deployment-20260909-3038d8a0-v2/solver.jar;$root/probe.jar"
$manifest="$root/solve.tsv"
$rows=@("runId`tmainClass`targs`toutputDir`talgorithm",
 "first`tNgV6SwitchProbe`t15000`t$root/first`tTIME_INDEXED",
 "ng`tNgV6SwitchProbe`t1`t$root/ng`tNG_DSSR",
 "ti`tNgV6SwitchProbe`t1`t$root/ti`tTIME_INDEXED_SRI")
[IO.File]::WriteAllLines($manifest,$rows,[Text.UTF8Encoding]::new($false))
if($Retry) { $rows[1]="first`tNgV6SwitchProbe`t15000 fail-once`t$root/first`tNG_DSSR"; [IO.File]::WriteAllLines($manifest,$rows,[Text.UTF8Encoding]::new($false)) }
$settings=@("manifest=$manifest","classpath=$next","replacement=$repo/.codex-tmp/ng-v6-switch/classes/HEU/ExperimentBatchScheduler.class","receipt=$root/applied.txt")
if($Retry){$settings+= 'retryRunId=first'}
[IO.File]::WriteAllLines("$root/settings.properties",@($settings | ForEach-Object {$_.Replace('\','/')}),[Text.UTF8Encoding]::new($false))
$p=Start-Process -FilePath $java -ArgumentList @('-cp',('"'+$old+'"'),'HEU.ExperimentBatchScheduler',('"'+$manifest+'"'),'1') -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$root/scheduler.log" -RedirectStandardError "$root/scheduler.err"
try {
 for($i=0;$i -lt 60 -and !(Test-Path "$root/first/stdout.log");$i++){ Start-Sleep -Milliseconds 200 }
 & $java -cp "$repo/.codex-tmp/ng-v6-switch/agent.jar" NgV6SchedulerSwitch $p.Id '../agent.jar' 'settings.properties'
 if($LASTEXITCODE){throw 'Attach failed'}
 if(!$p.WaitForExit(45000)){throw 'Test scheduler timeout'}
 foreach($id in @('first','ng','ti')) { if(!(Test-Path "$root/$id/SUCCESS")){throw "Missing SUCCESS: $id"} }
 function Read-Cp($id) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String((Get-Content "$root/$id/stdout.log" -Raw).Trim())).Replace('\','/') }
 if($Retry) {
   if(!(Test-Path "$root/first.before-v6/stdout.log")){throw 'Missing retry archive'}
   if((Read-Cp 'first') -ne $next.Replace('\','/')){throw 'Retry did not use v6'}
 } elseif((Read-Cp 'first') -ne $old.Replace('\','/')){throw 'Running TI changed'}
 if((Read-Cp 'ng') -ne $next.Replace('\','/')){throw 'NG did not switch'}
 if((Read-Cp 'ti') -ne $old.Replace('\','/')){throw 'Future TI changed'}
 "PASS: running TI preserved; future NG v6; future TI old; root=$root"
} finally { if(!$p.HasExited){$p.Kill($true)} }
