"""仅构建9项OOM补跑包；不连接远端，不启动，不覆盖原结果。"""
import base64
import csv
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.codex-tmp/ng-oom-retry-20261108'
JAVA = Path('D:/软件/Java/jdk_22/bin')
JARS = ['D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar',
        'D:/软件/cplex/ILOG/CPLEX_Studio2211/cpoptimizer/lib/ILOG.CP.jar']
OLD = '20261104-ng-mainline-a-prepared'
FROZEN = '1b00188438f7a38ddacb809ebe2f7dd736dfb5a31d734a2e0df484713a6e432a'
HOSTS = {'first': ('100.68.243.112', 'D:/ccx_work/考虑交付的机器调度', 'D:\\software\\Java\\jdk-21\\bin\\java.exe', 7),
         'second': ('100.68.243.192', 'D:/ccx', 'D:\\Java\\jdk-21\\bin\\java.exe', 2)}


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


def command(args, log, cwd=ROOT):
    with log.open('w', encoding='utf-8') as stream:
        subprocess.run([str(a) for a in args], cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=180)


commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
assert not subprocess.check_output(['git', 'diff', '--ignore-space-at-eol', '--name-only', '--', 'src'], cwd=ROOT)
assert not subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', 'src'], cwd=ROOT)
name = '20261108-ng-n60-oom-retry-' + commit[:8]
build = WORK / 'build'
assert not build.exists(), 'Refuse to overwrite an earlier build'
build.mkdir()
source = build / 'source-snapshot.zip'
subprocess.run(['git', 'archive', '--format=zip', '--output=' + str(source), commit, 'src'], cwd=ROOT, check=True)
snapshot = build / 'source'
with zipfile.ZipFile(source) as archive:
    assert all(n.startswith('src/') and '..' not in Path(n).parts for n in archive.namelist())
    archive.extractall(snapshot)
classes = build / 'classes'
classes.mkdir()
entries = [snapshot / 'src' / p for p in ['HEU/Move.java', 'Common/formal/FormalExperimentRunner.java', 'HEU/ExperimentBatchScheduler.java']]
command([JAVA / 'javac.exe', '--release', '21', '-encoding', 'UTF-8', '-sourcepath', snapshot / 'src',
         '-cp', ';'.join(JARS), '-d', classes, *entries], build / 'compile.log')
class_files = list(classes.rglob('*.class'))
assert class_files and all(int.from_bytes(p.read_bytes()[6:8], 'big') <= 65 for p in class_files)
jar = build / 'solver.jar'
command([JAVA / 'jar.exe', '--create', '--file', jar, '-C', classes, '.'], build / 'jar.log')
tools = build / 'tools'
tools.mkdir()
command([JAVA / 'javac.exe', '--release', '21', '-encoding', 'UTF-8', '-cp', str(jar) + ';' + ';'.join(JARS),
         '-d', tools, ROOT / 'scripts/remote/NgMainlineConfigAudit.java', ROOT / 'scripts/remote/NgMainlineStatus.java'],
        build / 'tools-compile.log')
command([JAVA / 'jar.exe', '--create', '--file', build / 'tools.jar', '-C', tools, '.'], build / 'tools-jar.log')
cases = {r['scenario']: r for r in tsv(ROOT / 'experiment-suite/formal/manifests/20261104-ng-mainline-a-prepared/second/case-map.tsv')}
receipt = dict(sourceCommit=commit, batch=name, solverJarSha256=sha(jar), sourceArchiveSha256=sha(source),
               javaRelease=21, classes=len(class_files), parallelPerHost=2, solverHeapGiB=24, assignments={})
for host, (ip, allowed, java, count) in HOSTS.items():
    lines = (WORK / (host + '-before.tsv')).read_text(encoding='utf-8-sig').splitlines()
    meta = {line.split('\t')[0]: line.split('\t')[1:] for line in lines if not line.startswith('RUN\t') and '\t' in line}
    assert meta['JAR'] == [FROZEN]
    running = base64.b64decode(meta['JVMS_BASE64'][0]).decode('utf-8')
    assert 'HEU.ExperimentBatchScheduler' not in running and 'Common.formal.FormalExperimentRunner' not in running
    assert int(meta['HOST'][1]) >= 60 * 2**30 and int(meta['HOST'][2]) >= 50 * 2**30
    state = {p[1]: p for line in lines if line.startswith('RUN\t') for p in [line.split('\t')]}
    failed = [rid for rid, p in state.items() if p[3] == 'FAILED']
    assert len(failed) == count and all(state[rid][2] == 'false' for rid in failed)
    original_file = ROOT / '.codex-tmp/ng-resume-20261105' / (host + '-original.tsv')
    assert sha(original_file) == meta['SOLVE'][0]
    originals = {r['runId']: r for r in tsv(original_file)}
    stage = WORK / host
    stage.mkdir()
    remote = allowed + '/work1/experiments/' + name
    old = allowed + '/work1/experiments/' + OLD
    rows, selected_cases, source_hashes = [], [], [{'file': old + '/solver.jar', 'sha256': FROZEN}]
    for rid in failed:
        row = dict(originals[rid])
        scenario = rid.removeprefix('pricing-').removesuffix('-ng_dssr-mainline-a')
        case = cases[scenario]
        assert state[rid][4:6] == [case['inputSha'], case['seedSha']]
        options = dict((k, a or b) for k, a, b in re.findall(r'--([A-Za-z0-9]+)=(?:"([^"]*)"|(\S+))', row['args']))
        assert options['timeLimitSeconds'] == '10800' and options['maxNodes'] == '100000'
        assert options['timeIndexedCompletionBoundNodeArcFixingMaxDepth'] == '2147483647'
        assert options['enableCutSetBranching'] == 'false'
        previous = row['outputDir']
        assert previous == old + '/runs/' + rid
        row['outputDir'] = remote + '/runs/' + rid
        row['args'] = row['args'].replace('--outputDir="' + previous + '"', '--outputDir="' + row['outputDir'] + '"')
        assert row['args'] != originals[rid]['args'] and row['args'].replace(row['outputDir'], previous) == originals[rid]['args']
        rows.append(row)
        selected_cases.append(case)
        source_hashes += [dict(file=options['instance'], sha256=case['inputSha']), dict(file=options['seedFile'], sha256=case['seedSha'])]
        stderr = ROOT / '.codex-tmp/remote-progress-20261107' / (host + '-' + rid + '.stderr.log')
        assert b'OutOfMemoryError' in stderr.read_bytes()
        source_hashes.append(dict(file=previous + '/stderr.log', sha256=sha(stderr)))
    source_hashes += [dict(file=old + '/runs/' + rid + '/SUCCESS', sha256=p[6]) for rid, p in state.items() if p[2] == 'true']
    write_tsv(stage / 'solve.tsv', rows)
    write_tsv(stage / 'case-map.tsv', selected_cases)
    write_tsv(stage / 'source-hashes.tsv', source_hashes)
    for base in ['family', 'random']:
        shutil.copyfile(ROOT / 'experiment-suite/formal/manifests/20261104-ng-mainline-a-prepared/second' / ('profile-' + base + '.properties'), stage / ('profile-' + base + '.properties'))
    for file in ['solver.jar', 'tools.jar', 'source-snapshot.zip']:
        shutil.copyfile(build / file, stage / file)
    cplex = 'D:\\software\\IBM\\ILOG\\CPLEX_Studio2211'
    run = f'@echo off\nsetlocal\ncd /d "%~dp0"\nset "JAVA={java}"\nset "CPLEX={cplex}"\nset "CP=%~dp0solver.jar;%CPLEX%\\cplex\\lib\\cplex.jar;%CPLEX%\\cpoptimizer\\lib\\ILOG.CP.jar"\n"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\\cplex\\bin\\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler %*\nexit /b %ERRORLEVEL%\n'
    worker = '@echo off\nsetlocal\ncd /d "%~dp0"\nset "JAVA_TOOL_OPTIONS=-Xmx24g -Dtwet.bpc.fullDomainCompare.liveTrace=true -Dtwet.bpc.fullDomainCompare.stageHeartbeat=true -Dtwet.bpc.fullDomainCompare.nodeProgressSummary=true"\nmkdir worker.lock 2>nul\nif errorlevel 1 exit /b 19\nif not exist scheduler-logs mkdir scheduler-logs\necho SOLVE_RUNNING>experiment.status\ncall "%~dp0run.cmd" "%~dp0solve.tsv" 2 1>"%~dp0scheduler-logs\\solve.out.log" 2>"%~dp0scheduler-logs\\solve.err.log"\nset "WORKER_RC=%ERRORLEVEL%"\necho %WORKER_RC%>worker.exit-code\nif "%WORKER_RC%"=="0" (echo FINISHED>experiment.status) else (echo FAILED>experiment.status)\nexit /b %WORKER_RC%\n'
    for file, text in [('run.cmd', run), ('worker.cmd', worker)]:
        (stage / file).write_bytes(text.replace('\n', '\r\n').encode('ascii'))
    (stage / 'experiment.properties').write_text(f'tasks={count}\nmaxParallel=2\nsolverHeapGiB=24\nsourceCommit={commit}\nsolverJarSha256={sha(jar)}\ntimeLimitSeconds=10800\noriginalBatch={old}\n', encoding='utf-8')
    (stage / 'experiment.status').write_text('PREPARED_NOT_STARTED\n', encoding='ascii')
    (stage / 'README.md').write_text(f'# n60 NG-DSSR OOM补跑\n\n源提交`{commit}`，JAR SHA-256 `{sha(jar)}`，Java release21。\n\n只补跑原批次{count}项OOM，各项3小时、单线程，两并发、24GiB堆；family Cluster→Arc、random Arc，全节点fixing，其他有效参数按原A核对。原输入、seed、失败现场和已完成结果不动，新输出在本目录runs下。新旧版本结果须分开标记。\n\n原批次：`{old}`。\n', encoding='utf-8')
    write_tsv(stage / 'asset-hashes.tsv', [dict(file=p.name, sha256=sha(p)) for p in sorted(stage.iterdir()) if p.is_file() and p.name != 'experiment.status'])
    receipt['assignments'][host] = dict(ip=ip, remote=remote, tasks=failed, parallel=2, preservedSuccess=sum(p[2] == 'true' for p in state.values()))
(WORK / 'build-receipt.json').write_text(json.dumps(receipt, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps(receipt, indent=2, ensure_ascii=False))
