"""准备单项n60 join计时包；不连接远端、不启动、不覆盖旧输出。"""
import csv
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.codex-tmp/ng-join-remote-20261108'
COMMIT = '4ac6cb5ae3ed1ee30209c4c140db18154bfb10fd'
NAME = '20261108-ng-n60-join-timing-' + COMMIT[:8]
REMOTE = 'D:/ccx_work/考虑交付的机器调度/work1/experiments/' + NAME
OLD = 'D:/ccx_work/考虑交付的机器调度/work1/experiments/20261108-ng-n60-oom-retry-81e144e4'
RID = 'pricing-n060-set02-m3-family-high-wwide-f3-ng_dssr-mainline-a'
SCENARIO = 'n060-set02-m3-family-high-wwide-f3'
JAVA = Path('D:/软件/Java/jdk_22/bin')
LIBS = ['D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar',
        'D:/软件/cplex/ILOG/CPLEX_Studio2211/cpoptimizer/lib/ILOG.CP.jar']


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def tsv(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def write_tsv(path, rows):
    with path.open('x', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t',
                                quoting=csv.QUOTE_NONE, quotechar=None, lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)


def command(args, log):
    with log.open('w', encoding='utf-8') as stream:
        subprocess.run([str(a) for a in args], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                       check=True, timeout=180)


original = WORK / 'original'
rows = [r for r in tsv(original / 'solve.tsv') if r['runId'] == RID]
assert len(rows) == 1 and rows[0]['algorithm'] == 'NG_DSSR'
row = rows[0].copy()
old_output = row['outputDir']
old_options = dict(re.findall(r'--([A-Za-z0-9]+)="([^"]*)"', row['args']))
assert old_options['timeLimitSeconds'] == '10800' and old_options['maxNodes'] == '100000'
assert old_options['timeIndexedCompletionBoundNodeArcFixingMaxDepth'] == '2147483647'
row['outputDir'] = REMOTE + '/runs/' + RID
row['args'], count = re.subn(r'--outputDir="[^"]*"', '--outputDir="' + row['outputDir'] + '"', row['args'])
assert count == 1
new_options = dict(re.findall(r'--([A-Za-z0-9]+)="([^"]*)"', row['args']))
assert {k: v for k, v in old_options.items() if k != 'outputDir'} == {
    k: v for k, v in new_options.items() if k != 'outputDir'}
source_map = ROOT / 'experiment-suite/formal/manifests/20261108-ng-n60-oom-retry-81e144e4/first/case-map.tsv'
case = [r for r in tsv(source_map) if r['scenario'] == SCENARIO]
assert len(case) == 1
assert sha(original / 'input.dat') == case[0]['inputSha']
assert sha(original / 'seed.seed') == case[0]['seedSha']

build = WORK / 'build'
build.mkdir()
archive = build / 'source.zip'
subprocess.run(['git', 'archive', '--format=zip', '--output=' + str(archive), COMMIT, 'src'],
               cwd=ROOT, check=True)
source = build / 'source'
with zipfile.ZipFile(archive) as zipped:
    assert all(p.startswith('src/') and '..' not in Path(p).parts for p in zipped.namelist())
    zipped.extractall(source)
classes = build / 'classes'
classes.mkdir()
entries = [source / 'src' / p for p in ['HEU/Move.java', 'Common/formal/FormalExperimentRunner.java',
                                      'HEU/ExperimentBatchScheduler.java']]
command([JAVA / 'javac.exe', '--release', '21', '-encoding', 'UTF-8', '-sourcepath', source / 'src',
         '-cp', ';'.join(LIBS), '-d', classes, *entries], build / 'compile.log')
class_files = list(classes.rglob('*.class'))
assert class_files and all(int.from_bytes(p.read_bytes()[6:8], 'big') <= 65 for p in class_files)
payload = WORK / 'payload'
payload.mkdir()
command([JAVA / 'jar.exe', '--create', '--file', payload / 'solver.jar', '-C', classes, '.'], build / 'jar.log')
tools = build / 'tools'
tools.mkdir()
command([JAVA / 'javac.exe', '--release', '21', '-encoding', 'UTF-8', '-cp', str(payload / 'solver.jar')
         + ';' + ';'.join(LIBS), '-d', tools, ROOT / 'scripts/remote/NgMainlineConfigAudit.java',
         ROOT / 'scripts/remote/NgMainlineStatus.java'], build / 'tools-compile.log')
command([JAVA / 'jar.exe', '--create', '--file', payload / 'tools.jar', '-C', tools, '.'], build / 'tools-jar.log')
write_tsv(payload / 'solve.tsv', [row])
write_tsv(payload / 'case-map.tsv', case)
shutil.copy2(original / 'config.properties', payload / 'profile-family.properties')
write_tsv(payload / 'source-hashes.tsv', [
    {'file': old_options['instance'], 'sha256': sha(original / 'input.dat')},
    {'file': old_options['seedFile'], 'sha256': sha(original / 'seed.seed')},
    {'file': old_output + '/run.properties', 'sha256': sha(original / 'run.properties')},
    {'file': old_output + '/ngDssr/' + RID + '.config.properties', 'sha256': sha(original / 'config.properties')},
    {'file': OLD + '/solver.jar', 'sha256': '483134956c345a2cf4fc77fc2a73c470466aab1ce7283706b278a41e2afae4b7'}])

runner = (ROOT / 'experiment-suite/formal/manifests/20261108-ng-n60-oom-retry-81e144e4/first/run.cmd').read_text(encoding='utf-8')
(payload / 'run.cmd').write_text(runner, encoding='utf-8')
worker = (ROOT / 'experiment-suite/formal/manifests/20261108-ng-n60-oom-retry-81e144e4/first/worker.cmd').read_text(encoding='utf-8')
worker = worker.replace('-Xmx24g ', '-Xmx24g -Dtwet.bpc.ngDssrJoinPhaseTiming=true ', 1)
worker = worker.replace('solve.tsv" 2 ', 'solve.tsv" 1 ', 1)
assert 'solve.tsv" 1 ' in worker and '-Dtwet.bpc.ngDssrJoinPhaseTiming=true' in worker
(payload / 'worker.cmd').write_text(worker, encoding='utf-8')
common = '@echo off\nsetlocal\ncd /d "%~dp0"\n'
common += 'set "JAVA=D:\\software\\Java\\jdk-21\\bin\\java.exe"\n'
common += 'set "CPLEX=D:\\software\\IBM\\ILOG\\CPLEX_Studio2211"\n'
common += 'set "CP=%~dp0tools.jar;%~dp0solver.jar;%CPLEX%\\cplex\\lib\\cplex.jar;%CPLEX%\\cpoptimizer\\lib\\ILOG.CP.jar"\n'
(payload / 'audit.cmd').write_text(common + '"%JAVA%" -Xmx1g -Dfile.encoding=UTF-8 -cp "%CP%" NgMainlineConfigAudit "%~dp0."\nexit /b %ERRORLEVEL%\n', encoding='utf-8')
(payload / 'status.cmd').write_text(common + '"%JAVA%" -Xmx256m -Dfile.encoding=UTF-8 -cp "%CP%" NgMainlineStatus "%~dp0."\nexit /b %ERRORLEVEL%\n', encoding='utf-8')
receipt = dict(sourceCommit=COMMIT, name=NAME, remote=REMOTE, task=RID, heapGiB=24, parallel=1,
               timeLimitSeconds=10800, solverJarSha256=sha(payload / 'solver.jar'),
               sourceZipSha256=sha(archive), classes=len(class_files), originalArgs=rows[0]['args'],
               diagnosticArgs=row['args'], seedFingerprint=case[0]['seedFingerprint'])
(payload / 'experiment.properties').write_text(
    'tasks=1\nmaxParallel=1\nsolverHeapGiB=24\ntimeLimitSeconds=10800\nsourceCommit=' + COMMIT
    + '\nsolverJarSha256=' + receipt['solverJarSha256'] + '\njoinPhaseTiming=true\n', encoding='utf-8')
(payload / 'experiment.status').write_text('PREPARED\n', encoding='utf-8')
(payload / 'README.md').write_text(
    '# n60长join单项计时\n\n只补跑n60/set02/F3/m3/high/wide，原输入、seed、runId及求解参数不变。'
    '新JAR仅包含默认关闭的join计时补充，本worker显式开启；1并发、24GiB堆、10800秒名义时限。'
    '已知时限检查缺口未改，可能明显超时；逐项读时钟也会带来开销。旧结果不覆盖，不纳入正式性能比较。'
    '\n\n代码提交：' + COMMIT + '\n\n实际启动与断开验收见后续startup记录。\n', encoding='utf-8')
write_tsv(payload / 'asset-hashes.tsv', [{'file': f.name, 'sha256': sha(f)}
                                      for f in sorted(payload.iterdir()) if f.is_file()])
(WORK / 'build-receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(receipt, ensure_ascii=False))
