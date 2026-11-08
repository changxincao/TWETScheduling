"""克隆原n60慢例为独立OR/set诊断；不连接远端、不修改旧任务。"""
import csv
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CODE = "caecd7db"
NAME = "20261108-ng-n60-join-bitmap-ab-" + CODE
WORK = ROOT / ".codex-tmp" / NAME
PAYLOAD = WORK / "payload"
REMOTE = "D:/ccx_work/考虑交付的机器调度/work1/experiments/" + NAME
ORIGINAL = ROOT / ".codex-tmp/ng-join-remote-20261108"
SIX = ROOT / ".codex-tmp/20261108-ng-join-bitmap-ab-caecd7db/payload"
EXPECTED_JAR = "ed774ae39b15995faee88aac36adda5ca795ac62e104fc4271f2229e5b36a63c"
JAVA = Path("D:/软件/Java/jdk_22/bin")
LIBS = ["D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar",
        "D:/软件/cplex/ILOG/CPLEX_Studio2211/cpoptimizer/lib/ILOG.CP.jar"]


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_tsv(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def write_tsv(path, rows):
    with path.open("x", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter="\t", quoting=csv.QUOTE_NONE,
                                quotechar=None, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


row, = read_tsv(ORIGINAL / "payload/solve.tsv")
old_args = dict(re.findall(r'--([A-Za-z0-9]+)="([^"]*)"', row["args"]))
assert old_args["timeLimitSeconds"] == "10800" and old_args["maxNodes"] == "100000"
rid = row["runId"]
mapping = read_tsv(ROOT / "experiment-suite/formal/manifests/20261108-ng-n60-oom-retry-81e144e4/first/case-map.tsv")
case, = [c for c in mapping if c["scenario"] == "n060-set02-m3-family-high-wwide-f3"]
data, seed = ORIGINAL / "original/input.dat", ORIGINAL / "original/seed.seed"
assert sha(data) == case["inputSha"] and sha(seed) == case["seedSha"]
assert sha(SIX / "solver.jar") == EXPECTED_JAR
WORK.mkdir()
PAYLOAD.mkdir()
(PAYLOAD / "assets").mkdir()
shutil.copy2(SIX / "solver.jar", PAYLOAD / "solver.jar")
shutil.copy2(data, PAYLOAD / "assets" / (rid + ".dat"))
shutil.copy2(seed, PAYLOAD / "assets" / (rid + ".seed"))
shutil.copy2(ORIGINAL / "original/config.properties", PAYLOAD / "original-config.properties")
new_args = dict(old_args)
new_args.update(instance=REMOTE + "/assets/" + rid + ".dat", seedFile=REMOTE + "/assets/" + rid + ".seed",
                outputDir=REMOTE + "/runs/" + rid)
assert {k: v for k, v in old_args.items() if k not in ("instance", "seedFile", "outputDir")} == {
    k: v for k, v in new_args.items() if k not in ("instance", "seedFile", "outputDir")}
row["args"] = " ".join(f'--{k}="{v}"' for k, v in new_args.items())
row["outputDir"] = new_args["outputDir"]
write_tsv(PAYLOAD / "solve.tsv", [row])
write_tsv(PAYLOAD / "cases.tsv", [dict(runId=rid, n=60, m=3, kind="family", scale="high", window="wide",
    sourceInput=str(data.relative_to(ROOT)), sourceSeed=str(seed.relative_to(ROOT)),
    inputSha256=sha(data), seedSha256=sha(seed))])
build = WORK / "tools"
build.mkdir()
with (WORK / "tools-compile.log").open("w", encoding="utf-8") as log:
    subprocess.run([str(JAVA / "javac.exe"), "--release", "21", "-encoding", "UTF-8", "-cp",
                    str(PAYLOAD / "solver.jar") + ";" + ";".join(LIBS), "-d", str(build),
                    str(ROOT / "scripts/remote/NgBitmapExperimentAudit.java")], cwd=ROOT,
                   stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
subprocess.run([str(JAVA / "jar.exe"), "--create", "--file", str(PAYLOAD / "tools.jar"), "-C", str(build), "."], check=True)
for name in ("run.cmd", "audit.cmd"):
    shutil.copy2(SIX / name, PAYLOAD / name)
worker = '@echo off\nsetlocal\ncd /d "%~dp0"\n'
worker += 'set "JAVA_TOOL_OPTIONS=-Xmx24g -Dtwet.bpc.ngDssrJoinBitmapCompare=true -Dtwet.bpc.fullDomainCompare.liveTrace=true -Dtwet.bpc.fullDomainCompare.stageHeartbeat=true -Dtwet.bpc.fullDomainCompare.nodeProgressSummary=true"\n'
worker += 'mkdir worker.lock 2>nul\nif errorlevel 1 exit /b 19\nif not exist scheduler-logs mkdir scheduler-logs\n'
worker += '>experiment.status echo SOLVE_RUNNING\ncall "%~dp0run.cmd" "%~dp0solve.tsv" 1 1>"%~dp0scheduler-logs\\solve.out.log" 2>"%~dp0scheduler-logs\\solve.err.log"\n'
worker += 'if errorlevel 1 goto failed\n>worker.exit-code echo 0\n>experiment.status echo FINISHED\nexit /b 0\n:failed\n>worker.exit-code echo 1\n>experiment.status echo FAILED\nexit /b 1\n'
with (PAYLOAD / "worker.cmd").open("w", encoding="utf-8", newline="\r\n") as stream:
    stream.write(worker)
receipt = dict(sourceCommit=CODE, solverJarSha256=EXPECTED_JAR, remote=REMOTE, parallel=1, heapGiB=24,
               nominalSeconds=10800, maxNodes=100000, phaseTiming=False, bitmapCompare=True,
               originalArgs=old_args, diagnosticArgs=new_args, unchangedArgumentCount=len(old_args)-3,
               inputSha256=sha(data), seedSha256=sha(seed), expectedSeedFingerprint=case["seedFingerprint"],
               note="only input/seed/output addresses and diagnostic JVM flag change; original task untouched")
(PAYLOAD / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(PAYLOAD / "README.md").write_text("# n60长join同状态OR/set诊断\n\n原n60/set02/F3/m3/high/wide输入、seed、runId及求解参数保持；"
    "只改新目录中的输入/seed/output地址和JVM诊断开关。一个pricing内labeling、建组、组对判定各一次，"
    "全OR/全set消费相同可剪名单，逐位核对，只OR进入真实join。不设置稀疏边界，不改正式逻辑。\n\n"
    "1并发、24GiB堆、CPLEX1线程，原10800秒名义预算和100000节点上限；旧join时限检查缺口未改，可能明显超时。"
    "双算会改变wall-time及probe反馈，因此不保证重现相同pricing编号/dual，只保证每次A/B同状态。"
    "原任务不停止、原输出不覆盖。本次整体时间含B及统计，不是优化后正式求解时间。\n\n源码提交：" + CODE
    + "\n\n部署与独立连接验收见startup-record.json。\n", encoding="utf-8")
(PAYLOAD / "experiment.status").write_text("PREPARED\n", encoding="utf-8")
write_tsv(PAYLOAD / "asset-hashes.tsv", [dict(file=str(p.relative_to(PAYLOAD)).replace("\\", "/"), sha256=sha(p))
    for p in sorted(PAYLOAD.rglob("*")) if p.is_file() and p.name != "experiment.status"])
print(json.dumps(dict(directory=str(PAYLOAD), tasks=1, solverJarSha256=EXPECTED_JAR,
                      unchangedArguments=receipt["unchangedArgumentCount"], inputSha256=sha(data),
                      seedSha256=sha(seed)), ensure_ascii=False))
