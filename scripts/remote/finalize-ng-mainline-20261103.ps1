#requires -Version 7.0
$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../..").Path
$stage=Join-Path $repo '.codex-tmp/ng-mainline-20261103-prepared'
$frozen=Join-Path $repo '.codex-tmp/ng-fixing-cutset-le50-20260913/payload/work1/deployments/20260913-ng-cutset-supernode-v8/solver.jar'
& 'D:/软件/Java/jdk_22/bin/javac.exe' --release 21 -encoding UTF-8 -cp $frozen -d "$stage/tools" "$PSScriptRoot/NgMainlinePreparationAudit.java"
if($LASTEXITCODE-ne 0){throw 'Compilation failed'}
$specs=@(
 @{name='first';ip='100.68.243.112';root='D:/ccx_work/考虑交付的机器调度';known='C:/Users/Changxin/Downloads/solver_known_hosts';java='D:\software\Java\jdk-21\bin\java.exe'},
 @{name='second';ip='100.68.243.192';root='D:/ccx';known='C:/Users/Changxin/.ssh/known_hosts';java='D:\Java\jdk-21\bin\java.exe'},
 @{name='third';ip='100.79.236.222';root='D:/ccxWork';known='C:/Users/Changxin/.ssh/known_hosts';java='D:\Java\jdk-21\bin\java.exe'}
)
function Options($s){@('-i','C:/Users/Changxin/.ssh/codex_solver_auto_ed25519','-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o',"UserKnownHostsFile=$($s.known)",'-o','ConnectTimeout=15')}
function Upload($s,$from,$name){
 $opts=Options $s; $to="codex-runner@$($s.ip):$($s.root)/work1/experiments/20261103-ng-mainline-prepared/$name"
 & scp.exe @opts $from $to
 if($LASTEXITCODE-ne 0){throw "Upload failed $to"}
}
function Download($s,$name,$to){
 $opts=Options $s
 & scp.exe @opts "codex-runner@$($s.ip):$($s.root)/work1/experiments/20261103-ng-mainline-prepared/$name" $to
 if($LASTEXITCODE-ne 0){throw "Download failed $name"}
}
function AuditMode($s,$mode){
 $opts=Options $s; $remote="$($s.root)/work1/experiments/20261103-ng-mainline-prepared"; $native=$remote.Replace('/','\')
 & ssh.exe @opts "codex-runner@$($s.ip)" "cmd.exe /d /c `"cd /d $native && $($s.java) -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$native\tmp -cp $native\tools;$native\solver.jar NgMainlinePreparationAudit $($s.root) $remote $mode`""
 if($LASTEXITCODE-ne 0){throw "Preparation helper failed $($s.name) $mode"}
}
foreach($s in $specs){Upload $s "$stage/tools/NgMainlinePreparationAudit.class" 'tools/NgMainlinePreparationAudit.class'}

# 七项历史最优来自显式复用映射，补入真实输入/seed来源，不能用另一个新seed代替。
$schema=(Import-Csv "$stage/first-family-ti.tsv" -Delimiter "`t" | Select-Object -First 1).PSObject.Properties.Name
$extra=@(foreach($r in (Import-Csv "$stage/first-n80-reused.tsv" -Delimiter "`t")){
 $opts=[ordered]@{}
 foreach($line in (Get-Content -LiteralPath "$stage/reused-args/$($r.sourceRunId).txt")){
  if($line-match '^\[\d+\] --([^=]+)=(.*)$'){$opts[$Matches[1]]=$Matches[2]}
 }
 if(-not $opts.Contains('instance')-or -not $opts.Contains('seedFile')){throw 'Missing historical args'}
 $row=[ordered]@{};foreach($name in $schema){$row[$name]=''}
 foreach($k in @('taskSetId','machines','setupType','scaleLevel','windowLevel','algorithm')){$row[$k]=$r.$k}
 $row.runId=$r.sourceRunId;$row.size='80';$row.mainClass='Common.formal.FormalExperimentRunner';$row.action='solve';$row.outputDir=$r.sourceOutputDir
 $row.args=($opts.GetEnumerator()|ForEach-Object {'--'+$_.Key+'="'+$_.Value+'"'}) -join ' '
 [pscustomobject]$row
})
$columns=@($schema)
$lines=@($columns -join "`t")+@($extra|ForEach-Object {$r=$_;($columns|ForEach-Object {[string]$r.$_})-join "`t"})
[IO.File]::WriteAllLines("$stage/first/ti-reused-seven.tsv",$lines,[Text.UTF8Encoding]::new($false))
$catalog=@("label`tmanifest`tselection","ti-reused-seven`t$($specs[0].root)/work1/experiments/20261103-ng-mainline-prepared/ti-reused-seven.tsv`tTI")
[IO.File]::WriteAllLines("$stage/first/audit-extra-catalog.tsv",$catalog,[Text.UTF8Encoding]::new($false))
Upload $specs[0] "$stage/first/ti-reused-seven.tsv" 'ti-reused-seven.tsv'
Upload $specs[0] "$stage/first/audit-extra-catalog.tsv" 'audit-extra-catalog.tsv'
if(Test-Path -LiteralPath "$stage/first/audit-extra-rows.tsv"){
 $already=@(Import-Csv "$stage/first/audit-extra-rows.tsv" -Delimiter "`t")
 if($already.Count-ne 7 -or -not (Test-Path -LiteralPath "$stage/first/audit-extra-configs.tsv")){throw 'Incomplete extra audit'}
 if((Compare-Object @($already.runId|Sort-Object) @($extra.runId|Sort-Object)).Count-ne 0){throw 'Extra audit source changed'}
 'Reusing the completed seven-row read-only audit'
}else{
 AuditMode $specs[0] '--extra'
 foreach($file in @('audit-extra-rows.tsv','audit-extra-configs.tsv')){Download $specs[0] $file "$stage/first/$file"}
}
& python "$PSScriptRoot/analyze-ng-mainline-reuse-20261103.py"
if($LASTEXITCODE-ne 0){throw 'Reuse analysis failed'}
& python "$PSScriptRoot/build-ng-mainline-ready-20261103.py" plan
if($LASTEXITCODE-ne 0){throw 'Task planning failed'}
foreach($s in @($specs[0],$specs[2])){
 Upload $s "$stage/$($s.name)/asset-export.tsv" 'asset-export.tsv'
 AuditMode $s '--export-assets'
 Download $s 'prepared-assets.zip' "$stage/$($s.name)/prepared-assets.zip"
}
& python "$PSScriptRoot/build-ng-mainline-ready-20261103.py" package
if($LASTEXITCODE-ne 0){throw 'Ready package failed'}
foreach($s in $specs){
 Upload $s "$stage/$($s.name)/ready-package.zip" 'ready-package.zip'
 AuditMode $s '--install-ready'
 AuditMode $s '--verify-ready'
}
'ALL_THREE_PREPARED_NOT_STARTED'
