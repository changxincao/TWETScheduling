#requires -Version 7.0
param([ValidateSet('Audit','Deploy')][string]$Phase='Audit')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../..").Path
$stage=Join-Path $repo '.codex-tmp/ng-mainline-a-20261104-prepared'
$frozen=Join-Path $repo '.codex-tmp/ng-fixing-cutset-le50-20260913/payload/work1/deployments/20260913-ng-cutset-supernode-v8'
$batch='20261104-ng-mainline-a-prepared'
$expected='1b00188438f7a38ddacb809ebe2f7dd736dfb5a31d734a2e0df484713a6e432a'
if((Get-FileHash "$frozen/solver.jar").Hash.ToLowerInvariant()-ne $expected){throw 'Frozen JAR mismatch'}
$specs=@(
 @{name='second';ip='100.68.243.192';root='D:/ccx'},
 @{name='third';ip='100.79.236.222';root='D:/ccxWork'}
)
function SshOptions { @('-i','C:/Users/Changxin/.ssh/codex_solver_auto_ed25519','-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile=C:/Users/Changxin/.ssh/known_hosts','-o','ConnectTimeout=15') }
function Remote($s,[string]$script){
 $opts=SshOptions
 $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("`$ErrorActionPreference='Stop';`$ProgressPreference='SilentlyContinue';"+$script))
 & ssh.exe @opts "codex-runner@$($s.ip)" powershell.exe -NoProfile -NonInteractive -EncodedCommand $encoded
 if($LASTEXITCODE-ne 0){throw "Remote preparation failed: $($s.name)"}
}
function Transfer($s,[string]$source,[string]$destination,[switch]$Download){
 $opts=SshOptions
 if($Download){& scp.exe @opts "codex-runner@$($s.ip):$source" $destination}
 else{& scp.exe @opts $source "codex-runner@$($s.ip):$destination"}
 if($LASTEXITCODE-ne 0){throw "Transfer failed: $source"}
}
function Helper($s,[string]$mode){
 $p="$($s.root)/work1/experiments/$batch"; $native=$p.Replace('/','\')
 Remote $s "Set-Location -LiteralPath '$native'; & 'D:\Java\jdk-21\bin\java.exe' '-Dfile.encoding=UTF-8' '-Djava.io.tmpdir=$native\tmp' -cp '$native\tools;$native\solver.jar' NgMainlinePreparationAudit '$($s.root)' '$p' '$mode'; if (`$LASTEXITCODE -ne 0) { throw 'Audit helper failed' }"
}
if($Phase-eq 'Audit'){
 if(Test-Path -LiteralPath $stage){throw 'Local stage already exists; do not overwrite'}
 New-Item -ItemType Directory -Path "$stage/tools" | Out-Null
 & 'D:/软件/Java/jdk_22/bin/javac.exe' --release 21 -encoding UTF-8 -cp "$frozen/solver.jar" -d "$stage/tools" "$PSScriptRoot/NgMainlinePreparationAudit.java" "$PSScriptRoot/NgMainlineConfigAudit.java"
 if($LASTEXITCODE-ne 0){throw 'Audit helper compilation failed'}
 foreach($s in $specs){
  $local=Join-Path $stage $s.name; New-Item -ItemType Directory -Path $local | Out-Null
  $remote="$($s.root)/work1/experiments/$batch"; $native=$remote.Replace('/','\')
  # 仅在各主机已授权工作树中新建目录；旧实验及已撤回准备均不覆盖。
  Remote $s "hostname; whoami; Get-Process -Name java,javaw -ErrorAction SilentlyContinue | Select-Object Id,CPU,Path | ConvertTo-Json; if(Test-Path -LiteralPath '$native'){throw 'Stage already exists'}; New-Item -ItemType Directory -Path '$native\tools','$native\tmp' | Out-Null"
  $catalog=@("label`tmanifest`tselection")
  if($s.name-eq 'second'){
   $catalog+="ng-old`t$($s.root)/work1/experiments/20260913-ng-fixing-cutset-le50/solve.tsv`tNG_A"
   $catalog+="ng-f3`t$($s.root)/work1/experiments/20260914-balanced-family-ng-n50/solve.tsv`tNG_A"
  }else{
   $catalog+="ti-unchanged`t$($s.root)/work1/experiments/20260914-balanced-family-ti-le60/unchanged-resume.tsv`tTI"
   $catalog+="ti-f3`t$($s.root)/work1/experiments/20260914-balanced-family-ti-le60/solve.tsv`tTI"
   $catalog+="ti-n80`t$($s.root)/work1/experiments/20260928-balanced-n80-ti-5h/solve.tsv`tTI"
  }
  [IO.File]::WriteAllLines("$local/audit-catalog.tsv",$catalog,[Text.UTF8Encoding]::new($false))
  foreach($file in @('NgMainlinePreparationAudit.class','NgMainlineConfigAudit.class')){Transfer $s "$stage/tools/$file" "$remote/tools/$file"}
  Transfer $s "$local/audit-catalog.tsv" "$remote/audit-catalog.tsv"
  Transfer $s "$frozen/solver.jar" "$remote/solver.jar"
  Transfer $s "$frozen/deployment.properties" "$remote/frozen-source.properties"
  Helper $s 'audit'
  foreach($file in @('audit-rows.tsv','audit-configs.tsv')){Transfer $s "$remote/$file" "$local/$file" -Download}
 }
}else{
 foreach($s in $specs){
  $remote="$($s.root)/work1/experiments/$batch"; $native=$remote.Replace('/','\')
  $installed=Remote $s "if(Test-Path -LiteralPath '$native\solve.tsv'){ 'INSTALLED' }else{ 'EMPTY' }; exit 0"
  if(($installed -join '').Trim()-eq 'INSTALLED'){
   $hash=(Get-FileHash "$stage/$($s.name)/ready-package.zip").Hash
   Remote $s "if((Get-FileHash -LiteralPath '$native\ready-package.zip').Hash -ne '$hash'){throw 'Installed package differs'}; 'Same preparation package; verify without overwrite'; exit 0"
  }else{
   Transfer $s "$stage/$($s.name)/ready-package.zip" "$remote/ready-package.zip"
   Helper $s '--install-ready'
  }
  Helper $s '--verify-ready'
  Remote $s "Set-Location -LiteralPath '$native'; & 'D:\Java\jdk-21\bin\java.exe' '-Dfile.encoding=UTF-8' -cp '$native\tools;$native\solver.jar' NgMainlineConfigAudit '$native'; if (`$LASTEXITCODE -ne 0) {throw 'Effective config audit failed'}; if (!(Test-Path -LiteralPath 'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64\cplex2211.dll')) {throw 'Missing CPLEX runtime'}; & .\run.cmd .\solve.tsv 5 --scan-only; if (`$LASTEXITCODE -ne 0) {throw 'Manifest scan failed'}; Get-Content -LiteralPath .\experiment.status; Get-Process -Name java,javaw -ErrorAction SilentlyContinue | Select-Object Id,CPU,Path | ConvertTo-Json; exit 0"
 }
}
'PREPARATION_ONLY: no worker started'
