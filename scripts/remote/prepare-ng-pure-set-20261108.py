"""准备七例独立OR/set完整求解；只写本地新包，不连接或启动远端。"""
import csv
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CODE = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
NAME = "20261108-ng-join-pure-set-" + CODE[:8]
WORK = ROOT / ".codex-tmp" / NAME
REMOTE = "D:/ccx_work/考虑交付的机器调度/work1/experiments/" + NAME
SOURCE = ROOT / ".codex-tmp/ng-mainline-a-20261104-prepared/second/ready"
JAVA = Path("D:/软件/Java/jdk_22/bin")
LIBS = ["D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar",
        "D:/软件/cplex/ILOG/CPLEX_Studio2211/cpoptimizer/lib/ILOG.CP.jar"]
# 原慢例优先，其余同为n60/F3/high/wide，覆盖F>m、F=m、F<m。
CASES = [(2, 3), (2, 4), (3, 4), (1, 3), (3, 3), (1, 2), (2, 2)]
TESTS = ["NgDssrJoinPrefilterCacheTest", "NgJoinBitmapComparisonTest", "NgDssrJoinExtensionBoundaryTest",
         "NgTracedJoinPhaseOneEndpointRegressionTest", "NgHistoricalEndpointPricingTest",
         "CompletionBoundPointReceptionTest", "NgDssrHeartbeatDiagnosticsTest"]


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def rows(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def tsv(path, records):
    with path.open("x", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(records[0]), delimiter="\t", quoting=csv.QUOTE_NONE,
                                quotechar=None, lineterminator="\n")
        writer.writeheader()
        writer.writerows(records)


def command(args, log):
    with log.open("w", encoding="utf-8") as stream:
        subprocess.run([str(a) for a in args], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                       check=True, timeout=180)


def cmd(path, text):
    with path.open("x", encoding="utf-8", newline="\r\n") as stream:
        stream.write(text)


WORK.mkdir()
build = WORK / "build"
build.mkdir()
archive = build / "source.zip"
subprocess.run(["git", "archive", "--format=zip", "--output=" + str(archive), CODE, "src"], cwd=ROOT, check=True)
source = build / "source"
with zipfile.ZipFile(archive) as zipped:
    assert all(p.startswith("src/") and ".." not in Path(p).parts for p in zipped.namelist())
    zipped.extractall(source)
classes = build / "classes"
classes.mkdir()
command([JAVA / "javac.exe", "--release", "21", "-encoding", "UTF-8", "-sourcepath", source / "src",
         "-cp", ";".join(LIBS), "-d", classes,
         source / "src/HEU/Move.java", source / "src/Common/formal/FormalExperimentRunner.java",
         source / "src/HEU/ExperimentBatchScheduler.java",
         *[source / "src/TWETBPC/GC" / (test + ".java") for test in TESTS]], build / "compile.txt")
payload = WORK / "payload"
payload.mkdir()
command([JAVA / "jar.exe", "--create", "--file", payload / "solver.jar", "-C", classes, "."], build / "jar.txt")
tools = build / "tools"
tools.mkdir()
command([JAVA / "javac.exe", "--release", "21", "-encoding", "UTF-8", "-cp",
         str(payload / "solver.jar") + ";" + ";".join(LIBS), "-d", tools,
         ROOT / "scripts/remote/NgJoinSetExperimentRunner.java",
         ROOT / "scripts/remote/NgBitmapExperimentAudit.java"], build / "tools-compile.txt")
command([JAVA / "jar.exe", "--create", "--file", payload / "tools.jar", "-C", tools, "."], build / "tools-jar.txt")
with zipfile.ZipFile(payload / "solver.jar") as jar:
    class_files = [p for p in jar.namelist() if p.endswith(".class")]
    assert all(int.from_bytes(jar.read(p)[6:8], "big") <= 65 for p in class_files)

reference_rows = {r["runId"]: r for r in rows(SOURCE / "solve.tsv")}
reference_map = {r["scenario"]: r for r in rows(SOURCE / "case-map.tsv")}
(payload / "assets").mkdir()
manifest, cases = [], []
for case_index, (sid, m) in enumerate(CASES):
    scenario = f"n060-set{sid:02}-m{m}-family-high-wwide-f3"
    ref = reference_rows[f"pricing-{scenario}-ng_dssr-mainline-a"]
    mapping = reference_map[scenario]
    original = dict(re.findall(r'--([A-Za-z0-9]+)="([^"]*)"', ref["args"]))
    assert original["timeLimitSeconds"] == "10800" and original["maxNodes"] == "100000"
    assert original["enableClusterBranching"] == "true" and original["enableCutSetBranching"] == "false"
    assert original["timeIndexedCompletionBoundNodeArcFixingMaxDepth"] == "2147483647"
    data = SOURCE / "assets/inputs" / (scenario + ".dat")
    seed = SOURCE / "assets/seeds" / (scenario + ".seed")
    assert sha(data) == mapping["inputSha"] and sha(seed) == mapping["seedSha"]
    for mode in (("SET", "OR") if case_index % 2 == 0 else ("OR", "SET")):
        rid = f"join-{scenario}-{mode.lower()}"
        shutil.copy2(data, payload / "assets" / (rid + ".dat"))
        shutil.copy2(seed, payload / "assets" / (rid + ".seed"))
        opts = dict(original)
        opts.update(runId=rid, instance=REMOTE + "/assets/" + rid + ".dat",
                    seedFile=REMOTE + "/assets/" + rid + ".seed", outputDir=REMOTE + "/runs/" + rid)
        excluded = {"runId", "instance", "seedFile", "outputDir"}
        assert {k: v for k, v in original.items() if k not in excluded} == {
            k: v for k, v in opts.items() if k not in excluded}
        manifest.append(dict(runId=rid, mainClass="NgJoinSetExperimentRunner",
                             args=mode + " " + " ".join(f'--{k}="{v}"' for k, v in opts.items()),
                             outputDir=opts["outputDir"], dependsOn="", action="solve", algorithm="NG_DSSR"))
        cases.append(dict(runId=rid, n=60, m=m, kind="family", scale="high", window="wide", mode=mode,
                          scenario=scenario, sourceInput=str(data.relative_to(ROOT)), sourceSeed=str(seed.relative_to(ROOT)),
                          inputSha256=sha(data), seedSha256=sha(seed), seedFingerprint=mapping["seedFingerprint"],
                          referenceRun=ref["runId"], referenceManifest=str(SOURCE.relative_to(ROOT) / "solve.tsv")))
tsv(payload / "solve.tsv", manifest)
tsv(payload / "cases.tsv", cases)
common = '@echo off\nsetlocal\ncd /d "%~dp0"\n'
common += 'set "JAVA=D:\\software\\Java\\jdk-21\\bin\\java.exe"\n'
common += 'set "CPLEX=D:\\software\\IBM\\ILOG\\CPLEX_Studio2211"\n'
common += 'set "CP=%~dp0tools.jar;%~dp0solver.jar;%CPLEX%\\cplex\\lib\\cplex.jar;%CPLEX%\\cpoptimizer\\lib\\ILOG.CP.jar"\n'
cmd(payload / "run.cmd", common + '"%JAVA%" -Dfile.encoding=UTF-8 "-Djava.library.path=%CPLEX%\\cplex\\bin\\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler %*\nexit /b %ERRORLEVEL%\n')
cmd(payload / "audit.cmd", common + '"%JAVA%" -Xmx512m -Dfile.encoding=UTF-8 -cp "%CP%" NgBitmapExperimentAudit "%~dp0."\nexit /b %ERRORLEVEL%\n')
worker = '@echo off\nsetlocal\ncd /d "%~dp0"\n'
worker += 'set "JAVA_TOOL_OPTIONS=-Xmx24g -Dtwet.bpc.fullDomainCompare.liveTrace=true -Dtwet.bpc.fullDomainCompare.stageHeartbeat=true -Dtwet.bpc.fullDomainCompare.nodeProgressSummary=true"\n'
worker += 'mkdir worker.lock 2>nul\nif errorlevel 1 exit /b 19\nif not exist scheduler-logs mkdir scheduler-logs\n'
worker += '>experiment.status echo SOLVE_RUNNING\ncall "%~dp0run.cmd" "%~dp0solve.tsv" 2 1>"%~dp0scheduler-logs\\solve.out.log" 2>"%~dp0scheduler-logs\\solve.err.log"\n'
worker += 'if errorlevel 1 goto failed\n>worker.exit-code echo 0\n>experiment.status echo FINISHED\nexit /b 0\n:failed\n>worker.exit-code echo 1\n>experiment.status echo FAILED\nexit /b 1\n'
cmd(payload / "worker.cmd", worker)
receipt = dict(sourceCommit=CODE, solverJarSha256=sha(payload / "solver.jar"), toolsJarSha256=sha(payload / "tools.jar"),
               remote=REMOTE, cases=7, runs=14, parallel=2, heapGiB=24, cplexThreads=1, nominalSeconds=10800,
               maxNodes=100000, bitmapCompare=False, joinPhaseTiming=True, defaultStrategy="OR",
               familyBranching="Cluster then Arc", cutSet=False, scalarFixingDepth=2147483647,
               classCount=len(class_files), note="same JAR/input/seed/config, only member aggregation strategy differs; no reuse")
(payload / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(payload / "README.md").write_text("# 七例纯set与OR独立求解\n\n7个n60/F3/high/wide算例各运行OR与SET，"
    "共14项，不复用历史计时或旧输出。原set02/m3优先，另为set02/m4、set03/m4、set01/m3、set03/m3、set01/m2、set02/m2。"
    "每对使用同一JAR、数据和seed，其余CLI沿用原NG-A。Cluster→Arc、CutSet关闭、全节点scalar fixing、"
    "probe及完整求解流程不改。纯set仍输出原格式的剪枝BitSet，只用紧凑成员索引填充；不改变组对判断或label拼接。\n\n"
    "两并发，每JVM24GiB、CPLEX1线程、10800秒名义预算和100000节点。两组均开原join分项计时，"
    "关闭同状态双算。逐组读时钟有开销，报告整体时间时保留此口径；旧join超时响应缺口未改，不能保证严格3小时退出。"
    "solver TIME_LIMIT与child SUCCEEDED需区分，不将本诊断并入正式总表。\n\n源码：" + CODE
    + "\n\n启动和断开验收另记startup-record.json；旧任务和结果均不动。\n", encoding="utf-8")
(payload / "experiment.status").write_text("PREPARED\n", encoding="utf-8")
tsv(payload / "asset-hashes.tsv", [dict(file=str(p.relative_to(payload)).replace("\\", "/"), sha256=sha(p))
    for p in sorted(payload.rglob("*")) if p.is_file() and p.name != "experiment.status"])
print(json.dumps(receipt, ensure_ascii=False))
