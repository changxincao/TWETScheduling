"""验收现成A并准备两机清单；只写新准备目录，不启动求解。"""
import base64
import csv
import hashlib
import io
import json
import math
import re
import shutil
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OLD = ROOT / '.codex-tmp/ng-mainline-20261103-prepared'
STAGE = ROOT / '.codex-tmp/ng-mainline-a-20261104-prepared'
BATCH = '20261104-ng-mainline-a-prepared'
JAR_SHA = '1b00188438f7a38ddacb809ebe2f7dd736dfb5a31d734a2e0df484713a6e432a'
HOSTS = {'second': 'D:/ccx', 'third': 'D:/ccxWork'}
ARG = re.compile(r'--([A-Za-z0-9]+)=(?:"([^"]*)"|(\S+))')
VARIABLE = {'config.instanceName', 'config.bpcOutputRoot', 'config.liveTraceLogPath',
            'config.fixedInitialColumnSeed', 'config.solveTimeLimitSeconds'}
COMMON = ('runALNSForSeed alnsMaxRuntimeMillis alnsMaxNoImproveIterations '
          'alnsUseSimulatedAnnealingAcceptance initialHeuristicColumnHistoryMode '
          'acceptedSolutionHistoryLimit cplexRootAlgorithm enableTwoStageStrongBranching '
          'strongBranchingCandidateLimit strongBranchingPhase2CandidateLimit '
          'strongBranchingPhase2MaxHeuristicPasses strongBranchingScoreEpsilon '
          'enableStrongBranchingLightweightRepair enableStrongBranchingBranchImpliedPenalty '
          'enableDualBoundPruning dualBoundPruningTolerance enableDualStabilization '
          'enableRestrictedMasterIntegerHeuristic enableRouteEnumeration branchSeedColumnLimit '
          'branchSeedReducedCostAllowance branchingTolerance pseudoCostInf solveTimeLimitSeconds maxNodes').split()


def read(path):
    with path.open(encoding='utf-8-sig', newline='') as f:
        return list(csv.DictReader(f, delimiter='\t'))


def write(path, rows, delimiter='\t'):
    with path.open('x', encoding='utf-8', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]), delimiter=delimiter,
                                lineterminator='\n', quoting=csv.QUOTE_MINIMAL if delimiter == ',' else csv.QUOTE_NONE,
                                quotechar='"' if delimiter == ',' else None)
        writer.writeheader(); writer.writerows(rows)


def props(text):
    return dict(line.split('=', 1) for line in text.splitlines()
                if line and not line.startswith('#') and '=' in line)


def decode(text):
    return base64.b64decode(text).decode('utf-8-sig') if text else ''


def options(text):
    matches = ARG.findall(text)
    result = {k: a or b for k, a, b in matches}
    assert len(result) == len(matches), 'Duplicate arguments'
    return result


def key(row):
    return tuple(row[k] for k in ('taskSetId', 'size', 'machines', 'setupType', 'scaleLevel', 'windowLevel'))


def load(directory, host):
    contracts = {c['contractId']: props(decode(c['propertiesB64'])) for c in read(directory / 'audit-configs.tsv')}
    result = read(directory / 'audit-rows.tsv')
    for row in result:
        row['host'] = host
        row['config'] = contracts.get(row['contractId'], {})
        row['props'] = props(decode(row['runPropsB64']))
        row['summary'] = next(csv.DictReader(io.StringIO(decode(row['summaryB64']))), {}) if row['summaryB64'] else {}
    return result


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


ng = load(STAGE / 'second', 'second')
b_rows = {key(r): r for r in load(OLD / 'second', 'second')}
ti = load(OLD / 'first', 'first') + load(STAGE / 'third', 'third')
by_key = defaultdict(list)
for row in ti:
    by_key[key(row)].append(row)
assert len(ng) == 810 and len({key(r) for r in ng}) == 810
audit = []
templates = {}
config_tables = {}
for row in ng:
    refs = by_key[key(row)]
    primary_host = 'third' if row['setupType'] == 'family' else 'first'
    primary = [r for r in refs if r['host'] == primary_host]
    assert primary and {r['inputSha'] for r in refs} == {row['inputSha']}, row['runId']
    assert {r['seedFingerprint'] for r in primary} == {row['seedFingerprint']}, row['runId']
    normal = row['success'] == 'true' and bool(row['summary'])
    differences = []
    b = b_rows[key(row)]
    # 同场景A/B的显式参数只允许fixing深度不同，其他输入及seed均须一致。
    ignored = {'runId', 'outputDir', 'timeIndexedCompletionBoundNodeArcFixingMaxDepth'}
    ao, bo = options(row['args']), options(b['args'])
    assert {k: v for k, v in ao.items() if k not in ignored} == {k: v for k, v in bo.items() if k not in ignored}, row['runId']
    assert ao['timeIndexedCompletionBoundNodeArcFixingMaxDepth'] == '2147483647'
    assert row['seedFingerprint'] == b['seedFingerprint'] and row['inputSha'] == b['inputSha']
    if normal:
        for ref in primary:
            if ref['success'] != 'true' or not ref['config']:
                continue
            differences.extend(name for name in COMMON
                               if row['config'].get('config.' + name) != ref['config'].get('config.' + name))
        p, s = row['props'], row['summary']
        assert p.get('seedFingerprint') == row['seedFingerprint']
        assert p.get('profileVersion') == '2026-09-12-v8' and p.get('cplexThreads') == '1'
        assert p.get('outsourcingModel') == 'not-applicable'
        assert p.get('status') == s['status'] and s['status'] in {'FINISHED', 'ROOT_PROCESSED', 'TIME_LIMIT', 'NODE_LIMIT'}
        assert s['validationFeasible'] == s['validationObjectiveConsistent'] == 'true'
        assert math.isfinite(float(s['incumbentCost'])) and float(s['solveTimeSeconds']) > 0
        assert row['config']['config.timeIndexedCompletionBoundNodeArcFixingMaxDepth'] == '2147483647'
        assert row['config']['config.enableCutSetBranching'] == 'false'
        assert row['config']['config.enableSuccessorSetBranching'] == 'false'
        actual = {k: v for k, v in row['config'].items() if k not in VARIABLE}
        setup = row['setupType']
        if setup in config_tables:
            assert actual == config_tables[setup], ('Nonuniform effective A configuration', row['runId'])
        else:
            config_tables[setup] = actual; templates[setup] = row
        assert not differences, (row['runId'], differences)
    audit.append(dict(runId=row['runId'], n=row['size'], m=row['machines'], setup=row['setupType'],
                      scale=row['scaleLevel'], window=row['windowLevel'], normal=normal, reusable=normal,
                      inputMatchTI=True, seedContentMatchTI=True, loadedSeedMatches=normal,
                      commonParameterDifferences=','.join(sorted(set(differences))),
                      status=row['summary'].get('status', 'FAILED'), gap=row['summary'].get('gapPercent', ''),
                      inputSha=row['inputSha'], seedSha=row['seedSha'], seedFingerprint=row['seedFingerprint'],
                      contractId=row['contractId'], sourceOutput=row['outputDir']))
assert sum(a['reusable'] for a in audit) == 807
report = dict(variant='A', planned=810, reusable=807, failures=3,
              bySizeSetup=[dict(n=n, setup=setup, reusable=sum(a['reusable'] for a in audit if a['n'] == n and a['setup'] == setup))
                           for n in ('20', '40', '50') for setup in ('family', 'random')],
              statuses=dict(Counter(a['status'] for a in audit if a['normal'])),
              missingGap=sum(a['normal'] and not a['gap'] for a in audit),
              configFields={k: len(v) for k, v in config_tables.items()},
              firstHostReference='2026-11-03 archived audit; no live connection',
              jarSha256=JAR_SHA)
write(STAGE / 'reuse-audit.csv', audit, ',')
(STAGE / 'reuse-summary.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')

cases = json.loads((OLD / 'prepared-cases.json').read_text(encoding='utf-8'))
assert len(cases) == 540 and len({c['scenario'] for c in cases}) == 540
schema = list(read(OLD / 'second/ready/solve.tsv')[0])
all_ids = set()
for host, allowed in HOSTS.items():
    size = '60' if host == 'second' else '80'
    selected = [dict(c, target=host) for c in cases if c['scenario'].startswith('n0' + size)]
    assert len(selected) == 270
    payload = STAGE / host / 'ready'
    payload.mkdir()
    remote = allowed + '/work1/experiments/' + BATCH
    tasks = []
    for case in selected:
        scenario = case['scenario']
        source_candidates = [r for r in ti if r['runId'] == case['referenceRun'] and r['manifest'] == case['referenceManifest']]
        # 七项旧Arc复用的真实清单来自已保存的extra审计，不访问第一机。
        if not source_candidates and case['assetSourceHost'] == 'first':
            source_candidates = [r for r in read(OLD / 'first/audit-extra-rows.tsv')
                                 if r['runId'] == case['referenceRun'] and r['manifest'] == case['referenceManifest']]
        assert source_candidates and all(r['inputSha'] == case['inputSha'] and r['seedSha'] == case['seedSha']
                                         and r['seedFingerprint'] == case['seedFingerprint'] for r in source_candidates), scenario
        source = source_candidates[0]
        setup = source['setupType']
        for kind, ext, field in (('inputs', 'dat', 'inputSha'), ('seeds', 'seed', 'seedSha')):
            origin = OLD / f'asset-cache/export-assets/{kind}/{scenario}.{ext}'
            assert sha(origin) == case[field], origin
            destination = payload / f'assets/{kind}/{scenario}.{ext}'
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(origin, destination)
        run_id = f'pricing-{scenario}-ng_dssr-mainline-a'
        assert run_id not in all_ids; all_ids.add(run_id)
        opt = options(templates[setup]['args'])
        original = dict(opt)
        opt.update(action='solve', runId=run_id, instance=f'{remote}/assets/inputs/{scenario}.dat',
                   seedFile=f'{remote}/assets/seeds/{scenario}.seed', outputDir=f'{remote}/runs/{run_id}',
                   timeLimitSeconds='10800' if size == '60' else '18000')
        permitted = {'runId', 'instance', 'seedFile', 'outputDir', 'timeLimitSeconds'}
        assert {k: v for k, v in opt.items() if k not in permitted} == {k: v for k, v in original.items() if k not in permitted}
        row = dict.fromkeys(schema, '')
        row.update(runId=run_id, mainClass='Common.formal.FormalExperimentRunner',
                   args=' '.join(f'--{k}="{v}"' for k, v in opt.items()), outputDir=opt['outputDir'],
                   block='NG-A', action='solve', algorithm='NG_DSSR', discountLevel='not-applicable')
        row.update({k: source[k] for k in ('taskSetId', 'size', 'machines', 'setupType', 'scaleLevel', 'windowLevel')})
        tasks.append(row)
    family_count = 3 if size == '60' else 4
    tasks.sort(key=lambda r: (0 if r['setupType'] == 'family' and int(r['machines']) > family_count
                             else 1 if r['setupType'] == 'family' and int(r['machines']) == family_count
                             else 2 if r['setupType'] == 'family' else 3,
                             r['taskSetId'], r['scaleLevel'], r['windowLevel'], int(r['machines'])))
    write(payload / 'solve.tsv', tasks)
    write(payload / 'case-map.tsv', selected)
    write(payload / 'reuse-index.csv', [a for a in audit if a['reusable']], ',')
    for setup, config in config_tables.items():
        (payload / f'profile-{setup}.properties').write_text(
            '# Audit reference; runtime reads frozen JAR and solve.tsv.\n' +
            '\n'.join(f'{k}={v}' for k, v in sorted(config.items())) + '\n', encoding='utf-8')
    run = r'''@echo off
setlocal
cd /d "%~dp0"
set "JAVA=D:\Java\jdk-21\bin\java.exe"
set "CPLEX=D:\software\IBM\ILOG\CPLEX_Studio2211"
set "CP=%~dp0solver.jar;%CPLEX%\cplex\lib\cplex.jar;%CPLEX%\cpoptimizer\lib\ILOG.CP.jar"
"%JAVA%" "-Dfile.encoding=UTF-8" "-Djava.library.path=%CPLEX%\cplex\bin\x64_win64" -cp "%CP%" HEU.ExperimentBatchScheduler %*
exit /b %ERRORLEVEL%
'''
    worker = r'''@echo off
setlocal
cd /d "%~dp0"
mkdir worker.lock 2>nul
if errorlevel 1 exit /b 19
if not exist scheduler-logs mkdir scheduler-logs
echo SOLVE_RUNNING>experiment.status
call "%~dp0run.cmd" "%~dp0solve.tsv" 5 1>"%~dp0scheduler-logs\solve.out.log" 2>"%~dp0scheduler-logs\solve.err.log"
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" (echo FINISHED>experiment.status) else (echo FAILED>experiment.status)
echo %RC%>worker.exit-code
exit /b %RC%
'''
    for name, text in (('run.cmd', run), ('worker.cmd', worker)):
        (payload / name).write_bytes(text.replace('\n', '\r\n').encode('utf-8'))
    (payload / 'experiment.status').write_text('PREPARED_NOT_STARTED\n', encoding='ascii')
    (payload / 'experiment.properties').write_text(
        f'profileVersion=2026-09-12-v8\nsolverJarSha256={JAR_SHA}\nvariant=A\nmaxParallel=5\nsolverThreads=1\n'
        f'rootOnly=false\ncutSet=false\ntasks=270\nsize={size}\nstatus=PREPARED_NOT_STARTED\n', encoding='utf-8')
    (payload / 'preparation-summary.json').write_text(json.dumps(dict(host=host, size=size, tasks=270,
        status='PREPARED_NOT_STARTED', variant='A', maxParallel=5, solverThreads=1, reused=807,
        historicalFailures=3, solverJarSha256=JAR_SHA), indent=2), encoding='utf-8')
    (payload / 'README.md').write_text(
        f'# NG-DSSR A主线准备\n\n本机n{size}共270项，只准备未启动。5个独立JVM、每模型/CPLEX线程1，'
        'family按F<m、F=m、F>m顺序优先，然后random。全节点scalar TI fixing，family Cluster→Arc、random Arc；'
        'CutSet/SuccessorSet关闭。其余逐项等同原A参数。\n\n807项旧A只引用、不移动、不重算；'
        '3项历史失败不进入常规队列。solver.jar沿用A--D冻结包，不编译本地算法改动。'
        '\n\nprofile-family/random.properties仅为验收参考，实际由冻结JAR与solve.tsv决定。'
        '\n\n准备阶段不得有runs、worker.lock或worker.exit-code，不运行worker。\n', encoding='utf-8')
    hashes = [dict(relative='solver.jar', sha256=JAR_SHA)]
    hashes += [dict(relative=p.relative_to(payload).as_posix(), sha256=sha(p))
               for p in sorted(payload.rglob('*')) if p.is_file()]
    write(payload / 'asset-hashes.tsv', hashes)
    with zipfile.ZipFile(STAGE / host / 'ready-package.zip', 'x', zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(payload.rglob('*')):
            if file.is_file(): archive.write(file, file.relative_to(payload).as_posix())
assert len(all_ids) == 540
print(json.dumps(report, ensure_ascii=False, indent=2))
print('PACKAGED second=270 third=270 maxParallel=5 PREPARED_NOT_STARTED')
