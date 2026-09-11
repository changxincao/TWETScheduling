#requires -Version 7.0
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$sourceCommit = (git -C $repo rev-parse HEAD).Trim()
$sourceShort = $sourceCommit.Substring(0, 8)
$batchName = '20260911-cluster-le60'
$deploymentName = "20260911-$sourceShort-cluster-v6"
$stage = Join-Path $repo ".codex-tmp/third-cluster-le60-20260911"
$seedSource = Join-Path $repo '.codex-tmp/third-host-seeds-le60'
$sourceManifest = Join-Path $repo 'experiment-suite/formal/manifests/20260905-formal-scheduling-le60/solve.tsv'
$remoteWork = 'D:/ccxWork/work1'
$remoteExperiment = "$remoteWork/experiments/$batchName"
$remoteDeployment = "$remoteWork/deployments/$deploymentName"
$oldWork = 'D:/ccx_work/考虑交付的机器调度/work1'
$oldExperiment = "$oldWork/experiments/20260905-formal-scheduling-le60"
$utf8 = [Text.UTF8Encoding]::new($false)

if (Test-Path -LiteralPath $stage) {
    throw "Stage already exists: $stage"
}
if ((git -C $repo status --short -- src).Count -ne 0) {
    throw 'Source tree has uncommitted src changes; refusing to build an untraceable solver.'
}
$seedFiles = @(Get-ChildItem -LiteralPath $seedSource -File)
if ($seedFiles.Count -ne 1080) {
    throw "Expected 1080 shared seed files, found $($seedFiles.Count)"
}

$payload = Join-Path $stage 'payload/work1'
$experiment = Join-Path $payload "experiments/$batchName"
$deployment = Join-Path $payload "deployments/$deploymentName"
$inputRoot = Join-Path $payload 'instances/no_outsourcing/data'
$classes = Join-Path $stage 'build/classes'
New-Item -ItemType Directory -Path $experiment, $deployment, $inputRoot, $classes,
    (Join-Path $experiment 'seeds'), (Join-Path $experiment 'scheduler-logs') -Force | Out-Null

function Write-Tsv([string]$path, [object[]]$items) {
    if ($items.Count -eq 0) { throw "Cannot write empty TSV: $path" }
    $header = $items[0].PSObject.Properties.Name
    $lines = @($header -join "`t") + @($items | ForEach-Object {
        $row = $_
        ($header | ForEach-Object { [string]$row.$_ }) -join "`t"
    })
    [IO.File]::WriteAllLines($path, $lines, $utf8)
}

function Copy-Row([psobject]$row) {
    $copy = [ordered]@{}
    foreach ($property in $row.PSObject.Properties) { $copy[$property.Name] = [string]$property.Value }
    [pscustomobject]$copy
}

$rows = @(Import-Csv -LiteralPath $sourceManifest -Delimiter "`t" | ForEach-Object { Copy-Row $_ })
if ($rows.Count -ne 3240) { throw "Expected 3240 solves, found $($rows.Count)" }

$familyCountBySize = @{ 20 = 3; 40 = 3; 50 = 4; 60 = 4 }
$inputAudit = [ordered]@{}
foreach ($row in $rows) {
    $row.args = $row.args.Replace($oldExperiment, $remoteExperiment).Replace($oldWork, $remoteWork)
    $row.outputDir = $row.outputDir.Replace($oldExperiment, $remoteExperiment).Replace($oldWork, $remoteWork)
    $row.args = $row.args.Replace('--enableClusterBranching="false"', '--enableClusterBranching="true"')
    $row.args = $row.args.Replace('--structuredArcStrictTypePriority="false"', '--structuredArcStrictTypePriority="true"')
    $row.args += ' --clusterMstTheta="0.5" --clusterTemporalWeight="0.0"'
    $row.dependsOn = ''

    $instanceMatch = [regex]::Match($row.args, '--instance="([^"]+)"')
    if (!$instanceMatch.Success) { throw "Missing instance in $($row.runId)" }
    $remoteInstance = $instanceMatch.Groups[1].Value
    if (!$inputAudit.Contains($remoteInstance)) {
        $relative = $remoteInstance.Substring("$remoteWork/instances/no_outsourcing/data/".Length)
        $localInstance = Join-Path $repo "experiment-suite/formal/instances/data/$relative"
        if (!(Test-Path -LiteralPath $localInstance -PathType Leaf)) { throw "Missing input: $localInstance" }
        $destination = Join-Path $inputRoot $relative
        New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
        Copy-Item -LiteralPath $localInstance -Destination $destination
        $inputAudit[$remoteInstance] = (Get-FileHash -LiteralPath $localInstance -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$priority = @($rows | Where-Object {
    $_.setupType -eq 'family' -and [int]$_.machines -gt $familyCountBySize[[int]$_.size]
})
$remainingFamily = @($rows | Where-Object {
    $_.setupType -eq 'family' -and !([int]$_.machines -gt $familyCountBySize[[int]$_.size])
})
$random = @($rows | Where-Object { $_.setupType -ne 'family' })
$orderedRows = @($priority) + @($remainingFamily) + @($random)

if ($priority.Count -ne 270) { throw "Expected 270 F<m priority solves, found $($priority.Count)" }
if (($priority.Count + $remainingFamily.Count) -ne 1620) { throw 'Expected 1620 family solves' }
if (@($orderedRows | Group-Object runId | Where-Object Count -ne 1).Count -ne 0) { throw 'Duplicate runId' }
foreach ($algorithm in 'NG_DSSR', 'TIME_INDEXED', 'TIME_INDEXED_SRI') {
    $count = @($orderedRows | Where-Object algorithm -eq $algorithm).Count
    if ($count -ne 1080) { throw "Unexpected $algorithm count: $count" }
}
if (@($orderedRows | Where-Object {
    $_.args -notmatch '--timeLimitSeconds="10800"' -or
    $_.args -notmatch '--maxNodes="100000"' -or
    $_.args -notmatch '--enableClusterBranching="true"' -or
    $_.args -notmatch '--structuredArcStrictTypePriority="true"' -or
    $_.args -notmatch '--clusterMstTheta="0.5"' -or
    $_.args -notmatch '--clusterTemporalWeight="0.0"'
}).Count -ne 0) { throw 'Solve configuration validation failed' }

Copy-Item -LiteralPath $seedFiles.FullName -Destination (Join-Path $experiment 'seeds')
$seedAudit = foreach ($seed in $seedFiles | Sort-Object Name) {
    [pscustomobject]@{
        path = "$remoteExperiment/seeds/$($seed.Name)"
        sha256 = (Get-FileHash -LiteralPath $seed.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$inputAuditRows = foreach ($entry in $inputAudit.GetEnumerator()) {
    [pscustomobject]@{ path = $entry.Key; sha256 = $entry.Value }
}

Write-Tsv (Join-Path $experiment 'solve.tsv') $orderedRows
Write-Tsv (Join-Path $experiment 'input-sha256.tsv') $inputAuditRows
Write-Tsv (Join-Path $experiment 'seed-sha256.tsv') @($seedAudit)

$smoke = @($orderedRows | Select-Object -First 3 | ForEach-Object {
    $copy = Copy-Row $_
    $copy.args = $copy.args.Replace('--timeLimitSeconds="10800"', '--timeLimitSeconds="120"')
    $copy.args = $copy.args.Replace('--maxNodes="100000"', '--maxNodes="2"')
    $copy.args = $copy.args.Replace("$remoteExperiment/runs/pricing-comparison", "$remoteExperiment/smoke")
    $copy.outputDir = $copy.outputDir.Replace("$remoteExperiment/runs/pricing-comparison", "$remoteExperiment/smoke")
    $copy
})
if ((@($smoke.algorithm | Sort-Object -Unique) -join ',') -ne 'NG_DSSR,TIME_INDEXED,TIME_INDEXED_SRI') {
    throw 'Smoke rows are not one complete three-algorithm group'
}
Write-Tsv (Join-Path $experiment 'smoke.tsv') $smoke

$cplex = 'D:/软件/cplex/ILOG/CPLEX_Studio2211'
$compileClasspath = "$cplex/cplex/lib/cplex.jar;$cplex/cpoptimizer/lib/ILOG.CP.jar"
$entrypoints = @(
    "$repo/src/HEU/Move.java",
    "$repo/src/HEU/ExperimentBatchScheduler.java",
    "$repo/src/Common/formal/FormalExperimentRunner.java",
    "$repo/src/Common/formal/ClusterPartitionDiagnosticsRunner.java",
    "$repo/src/TWETBPC/BestBpcProfilesTest.java",
    "$repo/src/TWETBPC/BP/StructuredArcFlowBranchingTest.java",
    "$repo/src/TWETBPC/GC/NgDssrFirstRoundMidpointHistoryTest.java",
    "$repo/src/TWETBPC/GC/NgDssrMidpointProbePolicyTest.java",
    "$repo/src/TWETBPC/GC/NgDssrMidpointProbeConfigurationTest.java",
    "$repo/src/TWETBPC/GC/TimeIndexedGraphOptimizationTest.java",
    "$repo/src/TWETBPC/GC/TimeIndexedReuseTest.java"
)
& 'D:/软件/Java/jdk_22/bin/javac.exe' --release 21 -encoding UTF-8 -cp $compileClasspath -sourcepath "$repo/src" -d $classes $entrypoints
if ($LASTEXITCODE -ne 0) { throw 'Solver compilation failed' }

$verifySource = Join-Path $stage 'build/VerifyArtifacts.java'
$verifyCode = @'
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 校验第三主机正式批次的输入和共享seed，拒绝读取允许目录之外的文件。 */
public final class VerifyArtifacts {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("manifest expected allowedRoot");
        Path allowed = Path.of(args[2]).toRealPath();
        Path manifest = Path.of(args[0]).toRealPath();
        if (!manifest.startsWith(allowed)) throw new IllegalArgumentException("Manifest outside allowed root");
        List<String> lines = Files.readAllLines(manifest);
        int expected = Integer.parseInt(args[1]);
        if (lines.size() != expected + 1) throw new IllegalStateException("Unexpected row count: " + (lines.size() - 1));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int count = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\\t", -1);
            Path path = Path.of(fields[0]).toRealPath();
            if (!path.startsWith(allowed)) throw new IllegalArgumentException("Artifact outside allowed root: " + path);
            String actual = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
            if (!actual.equals(fields[1])) throw new IllegalStateException("Hash mismatch: " + path);
            count++;
        }
        System.out.println("Verified artifacts=" + count + " manifest=" + manifest.getFileName());
    }
}
'@
[IO.File]::WriteAllText($verifySource, $verifyCode, $utf8)
& 'D:/软件/Java/jdk_22/bin/javac.exe' --release 21 -encoding UTF-8 -d $classes $verifySource
if ($LASTEXITCODE -ne 0) { throw 'Artifact verifier compilation failed' }
& 'D:/软件/Java/jdk_22/bin/jar.exe' --create --file (Join-Path $deployment 'solver.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Solver jar creation failed' }
$sourceArchive = Join-Path $deployment "source-$sourceShort.zip"
git -C $repo archive --format=zip "--output=$sourceArchive" HEAD src
if ($LASTEXITCODE -ne 0) { throw 'Source archive creation failed' }

$run = @"
@echo off
setlocal
cd /d "%~dp0"
set "JAVA=D:\Java\jdk-21\bin\java.exe"
set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CP=%~dp0../../deployments/$deploymentName/solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"
"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\cplex\bin\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler "%~dp0%~1" %2 %3
exit /b %ERRORLEVEL%
"@
$worker = @'
@echo off
setlocal
cd /d "%~dp0"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 19
echo SOLVE_RUNNING>experiment.status
call "%~dp0run.cmd" solve.tsv 6 1>"%~dp0scheduler-logs\solve.log" 2>"%~dp0scheduler-logs\solve.err.log"
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" (echo FINISHED>experiment.status) else (echo FAILED>experiment.status)
echo %RC%>worker.exit-code
exit /b %RC%
'@
$smokeCmd = @'
@echo off
setlocal
cd /d "%~dp0"
call "%~dp0run.cmd" smoke.tsv 3 1>"%~dp0scheduler-logs\smoke.log" 2>"%~dp0scheduler-logs\smoke.err.log"
exit /b %ERRORLEVEL%
'@
$verifyCmd = @"
@echo off
setlocal
cd /d "%~dp0"
set "CP=%~dp0../../deployments/$deploymentName/solver.jar"
D:\Java\jdk-21\bin\java.exe -cp "%CP%" VerifyArtifacts input-sha256.tsv 1080 D:\ccxWork
if errorlevel 1 exit /b %ERRORLEVEL%
D:\Java\jdk-21\bin\java.exe -cp "%CP%" VerifyArtifacts seed-sha256.tsv 1080 D:\ccxWork
exit /b %ERRORLEVEL%
"@
[IO.File]::WriteAllText((Join-Path $experiment 'run.cmd'), $run.Replace("`n", "`r`n"), $utf8)
[IO.File]::WriteAllText((Join-Path $experiment 'worker.cmd'), $worker.Replace("`n", "`r`n"), $utf8)
[IO.File]::WriteAllText((Join-Path $experiment 'smoke.cmd'), $smokeCmd.Replace("`n", "`r`n"), $utf8)
[IO.File]::WriteAllText((Join-Path $experiment 'verify.cmd'), $verifyCmd.Replace("`n", "`r`n"), $utf8)

$solverHash = (Get-FileHash -LiteralPath (Join-Path $deployment 'solver.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
$properties = @(
    "sourceCommit=$sourceCommit",
    "profileVersion=2026-09-09-v6",
    "solverJarSha256=$solverHash",
    'scope=pure-scheduling-n20-n40-n50-n60',
    'solveCount=3240',
    'solvesPerAlgorithm=1080',
    'priorityRule=family-first; within-family familyCount<machines first',
    'prioritySolveCount=270',
    'familySolveCount=1620',
    'cluster=true',
    'structuredArcStrictTypePriority=true',
    'clusterMstTheta=0.5',
    'clusterTemporalWeight=0.0',
    'solverThreads=1',
    'maxParallel=6',
    'timeLimitSeconds=10800',
    'maxNodes=100000',
    'outsourcing=false'
)
[IO.File]::WriteAllLines((Join-Path $deployment 'deployment.properties'), $properties, $utf8)
[IO.File]::WriteAllLines((Join-Path $experiment 'experiment.properties'), $properties, $utf8)

$archive = Join-Path $stage 'third-cluster-le60.tar.gz'
& tar.exe -czf $archive -C (Join-Path $stage 'payload') work1
if ($LASTEXITCODE -ne 0) { throw 'Payload archive creation failed' }

Write-Output "Prepared $archive"
Write-Output "sourceCommit=$sourceCommit solverJarSha256=$solverHash"
Write-Output "inputs=$($inputAuditRows.Count) seeds=$($seedAudit.Count) priority=$($priority.Count) solves=$($orderedRows.Count)"
