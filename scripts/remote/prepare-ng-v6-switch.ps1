param([string]$Output)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../..").Path
if (!$Output) { $Output=Join-Path $repo '.codex-tmp/ng-v6-switch' }
New-Item -ItemType Directory -Force -Path "$Output/src/HEU","$Output/classes" | Out-Null
$source=[IO.File]::ReadAllText("$repo/src/HEU/ExperimentBatchScheduler.java")
$needle='command.add(classPath);'
if ([regex]::Matches($source,[regex]::Escape($needle)).Count -ne 1) { throw 'Unexpected scheduler source' }
$replacement=@'
String ngClasspath = System.getProperty("twet.scheduler.ngV6Classpath");
        command.add("NG_DSSR".equalsIgnoreCase(runSpec.fieldValue("algorithm"))
                && ngClasspath != null ? ngClasspath : classPath);
'@
$source=$source.Replace($needle,$replacement)
$retry=@'
RunResult result = future.get();
            if ("FAILED".equals(result.state) && result.runId.equals(System.getProperty("twet.scheduler.retryNgOnce"))) {
                System.clearProperty("twet.scheduler.retryNgOnce");
                Path manifest = Path.of(System.getProperty("twet.scheduler.retryManifest")).toAbsolutePath().normalize();
                RunSpec spec = null;
                for (RunSpec candidate : readManifest(manifest)) {
                    if (candidate.runId.equals(result.runId)) { spec = candidate; break; }
                }
                if (spec == null) throw new IllegalStateException("Retry run missing");
                if (!"NG_DSSR".equals(spec.fieldValue("algorithm"))) throw new IllegalStateException("Retry is not NG");
                Path output = result.outputDir.toAbsolutePath().normalize();
                if (!output.startsWith(manifest.getParent()) || Files.exists(output.resolve("SUCCESS")))
                    throw new IllegalStateException("Unsafe retry output");
                Files.move(output, output.resolveSibling(output.getFileName() + ".before-v6"));
                System.out.println("V6_CUTOVER_RETRY=" + result.runId);
                return new RunTask(manifest.getParent(), spec).call();
            }
            return result;
'@
if ([regex]::Matches($source,[regex]::Escape('return future.get();')).Count -ne 1) {throw 'Unexpected awaitResult'}
$source=$source.Replace('return future.get();',$retry)
[IO.File]::WriteAllText("$Output/src/HEU/ExperimentBatchScheduler.java",$source,[Text.UTF8Encoding]::new($false))
& 'D:/软件/Java/jdk_22/bin/javac.exe' --release 21 -encoding UTF-8 -d "$Output/classes" "$Output/src/HEU/ExperimentBatchScheduler.java" "$PSScriptRoot/NgV6SchedulerSwitch.java"
if ($LASTEXITCODE) { throw 'Compile failed' }
[IO.File]::WriteAllText("$Output/agent.mf","Manifest-Version: 1.0`nAgent-Class: NgV6SchedulerSwitch`nCan-Redefine-Classes: true`n`n",[Text.Encoding]::ASCII)
& 'D:/软件/Java/jdk_22/bin/jar.exe' cfm "$Output/agent.jar" "$Output/agent.mf" -C "$Output/classes" NgV6SchedulerSwitch.class
if ($LASTEXITCODE) { throw 'Package failed' }
& 'D:/软件/Java/jdk_22/bin/javac.exe' --release 21 -encoding UTF-8 -cp "$Output/classes" -d "$Output/classes" "$PSScriptRoot/NgV6Continuation.java"
if ($LASTEXITCODE) { throw 'Continuation compile failed' }
& 'D:/软件/Java/jdk_22/bin/jar.exe' cf "$Output/control.jar" -C "$Output/classes" HEU -C "$Output/classes" NgV6Continuation.class
if ($LASTEXITCODE) { throw 'Control package failed' }
