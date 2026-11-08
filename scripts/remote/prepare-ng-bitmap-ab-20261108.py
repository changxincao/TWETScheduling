"""准备六例独立join诊断包；不连接远端、不启动、不覆盖已有输出。"""
import csv
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMIT = "caecd7db"
NAME = "20261108-ng-join-bitmap-ab-" + COMMIT
WORK = ROOT / ".codex-tmp" / NAME
REMOTE = "D:/ccx_work/考虑交付的机器调度/work1/experiments/" + NAME
JAVA = Path("D:/软件/Java/jdk_22/bin")
LIBS = ["D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar",
        "D:/软件/cplex/ILOG/CPLEX_Studio2211/cpoptimizer/lib/ILOG.CP.jar"]
SEEDS = ROOT / ".codex-tmp/ng-fixing-cutset-le50-20260913/payload/work1/experiments/20260913-ng-fixing-cutset-le50/seeds"
CASES = [
    (50, 2, 4, "family", "high", SEEDS / "n050-set02-m4-family-high-n20-wwide.seed"),
    (40, 1, 4, "random", "base", SEEDS / "n040-set01-m4-random-base-n1-wwide.seed"),
    (40, 4, 2, "family", "base", ROOT / "experiment-suite/formal/seeds/20260902-cluster-f40s04-wide-m2.seed"),
    (40, 1, 4, "random", "high", SEEDS / "n040-set01-m4-random-high-n20-wwide.seed"),
    (40, 2, 4, "family", "high", SEEDS / "n040-set02-m4-family-high-n20-wwide.seed"),
    (50, 1, 4, "random", "high", SEEDS / "n050-set01-m4-random-high-n20-wwide.seed"),
]


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def command(args, log):
    with log.open("w", encoding="utf-8") as stream:
        subprocess.run([str(a) for a in args], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                       check=True, timeout=180)


def tsv(path, rows):
    with path.open("x", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter="\t", quoting=csv.QUOTE_NONE,
                                quotechar=None, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


WORK.mkdir()
build = WORK / "build"
build.mkdir()
archive = build / "source.zip"
subprocess.run(["git", "archive", "--format=zip", "--output=" + str(archive), COMMIT, "src"], cwd=ROOT, check=True)
source = build / "source"
with zipfile.ZipFile(archive) as zipped:
    assert all(p.startswith("src/") and ".." not in Path(p).parts for p in zipped.namelist())
    zipped.extractall(source)
classes = build / "classes"
classes.mkdir()
command([JAVA / "javac.exe", "--release", "21", "-encoding", "UTF-8", "-sourcepath", source / "src",
         "-cp", ";".join(LIBS), "-d", classes,
         *[source / "src" / p for p in ["HEU/Move.java", "Common/formal/FormalExperimentRunner.java",
                                        "HEU/ExperimentBatchScheduler.java"]]], build / "compile.log")
payload = WORK / "payload"
payload.mkdir()
command([JAVA / "jar.exe", "--create", "--file", payload / "solver.jar", "-C", classes, "."], build / "jar.log")
tools = build / "tools"
tools.mkdir()
command([JAVA / "javac.exe", "--release", "21", "-encoding", "UTF-8", "-cp",
         str(payload / "solver.jar") + ";" + ";".join(LIBS), "-d", tools,
         ROOT / "scripts/remote/NgBitmapExperimentAudit.java"], build / "audit-compile.log")
command([JAVA / "jar.exe", "--create", "--file", payload / "tools.jar", "-C", tools, "."], build / "tools-jar.log")
(payload / "assets").mkdir()
manifest, cases = [], []
for n, sid, m, kind, scale, seed in CASES:
    rid = f"bitmap-n{n:03}-set{sid:02}-{kind}-{scale}-wide-m{m}"
    level = "base" if scale == "base" else "high-heterogeneous"
    data = ROOT / f"experiment-suite/formal/instances/data/n{n:03}-set{sid:02}/{kind}/{level}/wide/m{m}.dat"
    assert data.is_file() and seed.is_file(), (data, seed)
    shutil.copy2(data, payload / "assets" / (rid + ".dat"))
    shutil.copy2(seed, payload / "assets" / (rid + ".seed"))
    opts = dict(action="solve", runId=rid, instance=f"{REMOTE}/assets/{rid}.dat", algorithm="NG_DSSR",
                outputDir=f"{REMOTE}/runs/{rid}", seedFile=f"{REMOTE}/assets/{rid}.seed", timeLimitSeconds="600",
                maxNodes="1", enableClusterBranching=str(kind == "family").lower(),
                structuredArcStrictTypePriority="true", enableCutSetBranching="false", cutSetSupernodeSeeds="false",
                enableSuccessorSetBranching="false", clusterMstTheta="0.5", clusterTemporalWeight="0.0",
                timeIndexedCompletionBoundNodeArcFixing="true", timeIndexedCompletionBoundNodeArcFixingMaxDepth="2147483647")
    manifest.append(dict(runId=rid, mainClass="Common.formal.FormalExperimentRunner",
                         args=" ".join(f'--{k}="{v}"' for k, v in opts.items()), outputDir=opts["outputDir"],
                         dependsOn="", action="solve", algorithm="NG_DSSR"))
    cases.append(dict(runId=rid, n=n, m=m, kind=kind, scale=scale, window="wide",
                      sourceInput=str(data.relative_to(ROOT)), sourceSeed=str(seed.relative_to(ROOT)),
                      inputSha256=sha(data), seedSha256=sha(seed)))
tsv(payload / "solve.tsv", manifest)
tsv(payload / "cases.tsv", cases)
common = '@echo off\nsetlocal\ncd /d "%~dp0"\n'
common += 'set "JAVA=D:\\software\\Java\\jdk-21\\bin\\java.exe"\n'
common += 'set "CPLEX=D:\\software\\IBM\\ILOG\\CPLEX_Studio2211"\n'
common += 'set "CP=%~dp0solver.jar;%CPLEX%\\cplex\\lib\\cplex.jar;%CPLEX%\\cpoptimizer\\lib\\ILOG.CP.jar"\n'
(payload / "run.cmd").write_text(common + '"%JAVA%" -Dfile.encoding=UTF-8 "-Djava.library.path=%CPLEX%\\cplex\\bin\\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler %*\nexit /b %ERRORLEVEL%\n', encoding="utf-8")
worker = '@echo off\nsetlocal\ncd /d "%~dp0"\n'
worker += 'set "JAVA_TOOL_OPTIONS=-Xmx4g -XX:ActiveProcessorCount=2 -XX:ParallelGCThreads=1 -XX:ConcGCThreads=1 -Dtwet.bpc.ngDssrJoinBitmapCompare=true"\n'
worker += 'mkdir worker.lock 2>nul\nif errorlevel 1 exit /b 19\nif not exist scheduler-logs mkdir scheduler-logs\n'
worker += 'echo SOLVE_RUNNING>experiment.status\ncall "%~dp0run.cmd" "%~dp0solve.tsv" 6 1>"%~dp0scheduler-logs\\solve.out.log" 2>"%~dp0scheduler-logs\\solve.err.log"\n'
worker += 'set "RC=%ERRORLEVEL%"\necho %RC%>worker.exit-code\nif "%RC%"=="0" (echo FINISHED>experiment.status) else (echo FAILED>experiment.status)\nexit /b %RC%\n'
(payload / "worker.cmd").write_text(worker, encoding="utf-8")
(payload / "audit.cmd").write_text(common + '"%JAVA%" -Xmx512m -Dfile.encoding=UTF-8 -cp "%~dp0tools.jar;%CP%" NgBitmapExperimentAudit "%~dp0."\nexit /b %ERRORLEVEL%\n', encoding="utf-8")
receipt = dict(sourceCommit=COMMIT, remote=REMOTE, parallel=6, heapGiB=4, cplexThreads=1,
               nominalSeconds=600, maxNodes=1, diagnostic="allOR/allSet; A only feeds real join",
               phaseTiming=False, solverJarSha256=sha(payload / "solver.jar"), sourceZipSha256=sha(archive), cases=cases)
(payload / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(payload / "README.md").write_text("# 六例join位图同状态对照\n\n仅NG-DSSR组件诊断，不纳入正式求解总时间。"
    "每例labeling/组对判断一次，全部OR与全部成员set各做一次并核对，仅OR结果进入真实join。"
    "每轮输出q×k静态/调用加权分布及解释性分档抽样时间，不设置算法切换阈值。"
    "六并发，每JVM4GiB、CPLEX1线程，最多1节点/600秒名义预算；现有长join时限检查缺口未改，可能超时。"
    "输入含前期诊断系列，不能混入F3正式主线汇总。已有任务不停止，已有结果不覆盖。\n\n"
    + "源码提交：" + COMMIT + "\n\n启动、独立连接和退出结果见后续启动验收记录。\n", encoding="utf-8")
(payload / "experiment.status").write_text("PREPARED\n", encoding="utf-8")
tsv(payload / "asset-hashes.tsv", [dict(file=str(p.relative_to(payload)).replace("\\", "/"), sha256=sha(p))
    for p in sorted(payload.rglob("*")) if p.is_file() and p.name != "experiment.status"])
print(json.dumps(dict(directory=str(payload), sourceCommit=COMMIT, solverJarSha256=receipt["solverJarSha256"], tasks=len(cases)), ensure_ascii=False))
