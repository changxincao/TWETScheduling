#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$stage = Join-Path $repo '.codex-tmp/ng-mainline-20261103-prepared'
$frozen = Join-Path $repo '.codex-tmp/ng-fixing-cutset-le50-20260913/payload/work1/deployments/20260913-ng-cutset-supernode-v8'
$expected = '1b00188438f7a38ddacb809ebe2f7dd736dfb5a31d734a2e0df484713a6e432a'
if ((Get-FileHash "$frozen/solver.jar" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Frozen JAR mismatch' }
New-Item -ItemType Directory -Force "$stage/tools" | Out-Null
& 'D:/软件/Java/jdk_22/bin/javac.exe' --release 21 -encoding UTF-8 -cp "$frozen/solver.jar" -d "$stage/tools" "$PSScriptRoot/NgMainlinePreparationAudit.java"
if ($LASTEXITCODE -ne 0) { throw 'Audit helper compilation failed' }
$hosts = @(
    @{ name='first'; ip='100.68.243.112'; root='D:/ccx_work/考虑交付的机器调度'; known='C:/Users/Changxin/Downloads/solver_known_hosts'; java='D:\software\Java\jdk-21\bin\java.exe' },
    @{ name='second'; ip='100.68.243.192'; root='D:/ccx'; known='C:/Users/Changxin/.ssh/known_hosts'; java='D:\Java\jdk-21\bin\java.exe' },
    @{ name='third'; ip='100.79.236.222'; root='D:/ccxWork'; known='C:/Users/Changxin/.ssh/known_hosts'; java='D:\Java\jdk-21\bin\java.exe' }
)
foreach ($spec in $hosts) {
    $local = Join-Path $stage $spec.name
    if (Test-Path -LiteralPath $local) { throw "Existing local stage: $local" }
    New-Item -ItemType Directory $local | Out-Null
    $remote = "$($spec.root)/work1/experiments/20261103-ng-mainline-prepared"
    $catalog = @("label`tmanifest`tselection")
    if ($spec.name -eq 'second') {
        $catalog += "ng-old`t$($spec.root)/work1/experiments/20260913-ng-fixing-cutset-le50/solve.tsv`tNG_B"
        $catalog += "ng-f3`t$($spec.root)/work1/experiments/20260914-balanced-family-ng-n50/solve.tsv`tNG_B"
    } else {
        $catalog += "ti-unchanged`t$($spec.root)/work1/experiments/20260914-balanced-family-ti-le60/unchanged-resume.tsv`tTI"
        $catalog += "ti-f3`t$($spec.root)/work1/experiments/20260914-balanced-family-ti-le60/solve.tsv`tTI"
        $catalog += "ti-n80`t$($spec.root)/work1/experiments/20260928-balanced-n80-ti-5h/solve.tsv`tTI"
        if ($spec.name -eq 'first') { $catalog += "ti-cluster-continuation`t$($spec.root)/work1/experiments/20261014-n80-cluster-continuation/solve.tsv`tTI" }
    }
    [IO.File]::WriteAllLines("$local/audit-catalog.tsv", $catalog, [Text.UTF8Encoding]::new($false))
    $ssh = @('-i','C:/Users/Changxin/.ssh/codex_solver_auto_ed25519','-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o',"UserKnownHostsFile=$($spec.known)",'-o','ConnectTimeout=15')
    $target = "codex-runner@$($spec.ip)"
    $native = $remote.Replace('/','\')
    # 新目录只存本次准备文件；禁止覆盖既有实验或调用worker。
    & ssh.exe @ssh $target "cmd.exe /d /c `"if exist $native (exit /b 17) else (mkdir $native\tools & mkdir $native\tmp)`""
    if ($LASTEXITCODE -ne 0) { throw "Cannot create new remote stage: $target" }
    foreach ($pair in @(
        @("$local/audit-catalog.tsv",'audit-catalog.tsv'),
        @("$stage/tools/NgMainlinePreparationAudit.class",'tools/NgMainlinePreparationAudit.class'),
        @("$frozen/solver.jar",'solver.jar'),
        @("$frozen/deployment.properties",'frozen-source.properties')
    )) {
        & scp.exe @ssh $pair[0] "${target}:$remote/$($pair[1])"
        if ($LASTEXITCODE -ne 0) { throw "Upload failed: $($pair[1])" }
    }
    $auditCommand = "cmd.exe /d /c `"cd /d $native && $($spec.java) -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$native\tmp -cp $native\tools;$native\solver.jar NgMainlinePreparationAudit $($spec.root) $remote`""
    & ssh.exe @ssh $target $auditCommand
    if ($LASTEXITCODE -ne 0) { throw "Read-only audit failed: $target" }
    foreach ($file in @('audit-rows.tsv','audit-configs.tsv')) {
        & scp.exe @ssh "${target}:$remote/$file" "$local/$file"
        if ($LASTEXITCODE -ne 0) { throw "Audit download failed: $target $file" }
    }
    "AUDITED $($spec.name) $target"
}
