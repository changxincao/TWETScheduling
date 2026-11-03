"""从已审计TI资产和冻结NG-B参数生成三机清单；不运行调度器。"""
import base64
import csv
import hashlib
import io
import json
import re
import shutil
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGE = ROOT / '.codex-tmp/ng-mainline-20261103-prepared'
HOSTS = {
    'first': ('D:/ccx_work/考虑交付的机器调度', r'D:\software\Java\jdk-21\bin\java.exe'),
    'second': ('D:/ccx', r'D:\Java\jdk-21\bin\java.exe'),
    'third': ('D:/ccxWork', r'D:\Java\jdk-21\bin\java.exe'),
}
PATTERN = re.compile(r'--([A-Za-z0-9]+)=(?:"([^"]*)"|(\S+))')


def tsv_read(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def tsv_write(path, rows):
    with path.open('w', encoding='utf-8', newline='') as stream:
        for row in rows:
            assert all('\t' not in str(v) and '\n' not in str(v) and '\r' not in str(v) for v in row.values())
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t', lineterminator='\n', quoting=csv.QUOTE_NONE, quotechar=None)
        writer.writeheader(); writer.writerows(rows)


def key(row):
    return tuple(row[k] for k in ('taskSetId', 'size', 'machines', 'setupType', 'scaleLevel', 'windowLevel'))


def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def options(text):
    return {m[0]: m[1] or m[2] for m in PATTERN.findall(text)}


if sys.argv[1] == 'plan':
    groups = defaultdict(list)
    for host in ('first', 'third'):
        source = tsv_read(STAGE / host / 'audit-rows.tsv')
        if host == 'first':
            source += tsv_read(STAGE / host / 'audit-extra-rows.tsv')
        for row in source:
            row['host'] = host
            if row['size'] in ('60', '80'):
                groups[key(row)].append(row)
    assert len(groups) == 540
    templates = {r['setupType']: r for r in tsv_read(STAGE / 'second/audit-rows.tsv') if r['size'] == '20'}
    schemas = ['runId', 'mainClass', 'args', 'outputDir', 'dependsOn', 'block', 'action',
               'taskSetId', 'size', 'machines', 'setupType', 'scaleLevel', 'windowLevel',
               'algorithm', 'outsourcingModel', 'outsourcingRate', 'discountLevel']
    tasks = defaultdict(list); exports = defaultdict(dict); cases = []
    for case, references in sorted(groups.items()):
        first_arc = [r for r in references if r['host'] == 'first' and r['label'] != 'ti-cluster-continuation']
        third_cluster = [r for r in references if r['host'] == 'third']
        setup = references[0]['setupType']
        primary = third_cluster if setup == 'family' else first_arc
        assert primary, case
        assert len({r['inputSha'] for r in references}) == 1, case
        # 跨Arc/Cluster的seed差异显式保留；主比较采用相同分支TI的seed。
        assert len({r['seedFingerprint'] for r in primary}) == 1, ('Primary TI seed conflict', case)
        primary.sort(key=lambda r: (r['label'] == 'ti-reused-seven', r['algorithm'] == 'TIME_INDEXED_SRI'))
        source = primary[0]
        n, m = int(source['size']), int(source['machines'])
        family_count = 3 if n == 60 else 4
        target = 'first' if setup == 'random' else 'second' if family_count > m else 'third'
        source_host = source['host']
        scenario = f"{source['taskSetId']}-m{m}-{setup}-{source['scaleLevel']}-w{source['windowLevel']}"
        if setup == 'family': scenario += f'-f{family_count}'
        remote = HOSTS[target][0] + '/work1/experiments/20261103-ng-mainline-prepared'
        run_id = 'pricing-' + scenario + '-ng_dssr-mainline-b'
        opt = options(templates[setup]['args'])
        opt.update(action='solve', runId=run_id, instance=f'{remote}/assets/inputs/{scenario}.dat',
                   seedFile=f'{remote}/assets/seeds/{scenario}.seed', outputDir=f'{remote}/runs/{run_id}',
                   timeLimitSeconds='10800' if n == 60 else '18000')
        assert opt['timeIndexedCompletionBoundNodeArcFixingMaxDepth'] == '0'
        assert opt['enableCutSetBranching'] == 'false' and opt['cutSetSupernodeSeeds'] == 'false'
        assert opt['enableClusterBranching'] == str(setup == 'family').lower()
        row = dict.fromkeys(schemas, '')
        row.update(runId=run_id, mainClass='Common.formal.FormalExperimentRunner',
                   args=' '.join(f'--{k}="{v}"' for k, v in opt.items()), outputDir=opt['outputDir'],
                   block='NG-B', action='solve', algorithm='NG_DSSR', discountLevel='not-applicable')
        row.update({k: source[k] for k in ('taskSetId', 'size', 'machines', 'setupType', 'scaleLevel', 'windowLevel')})
        tasks[target].append(row)
        for kind, ext, origin, expected in (
                ('inputs', 'dat', source['input'], source['inputSha']),
                ('seeds', 'seed', source['seed'], source['seedSha'])):
            relative = f'export-assets/{kind}/{scenario}.{ext}'
            assert relative not in exports[source_host]
            exports[source_host][relative] = dict(source=origin, relative=relative, sha256=expected)
        cases.append(dict(scenario=scenario, target=target, assetSourceHost=source_host,
                          inputSha=source['inputSha'], seedSha=source['seedSha'], seedFingerprint=source['seedFingerprint'],
                          referenceRun=source['runId'], referenceManifest=source['manifest'],
                          arcClusterSeedSame=len({r['seedFingerprint'] for r in references}) == 1))
    assert {h: len(t) for h, t in tasks.items()} == dict(first=270, second=90, third=180)
    for host in HOSTS:
        tsv_write(STAGE / host / 'solve-prepared.tsv', tasks[host])
    for host, entries in exports.items():
        tsv_write(STAGE / host / 'asset-export.tsv', list(entries.values()))
    (STAGE / 'prepared-cases.json').write_text(json.dumps(cases, indent=2, ensure_ascii=False), encoding='utf-8')
    print('PLANNED', {h: len(t) for h, t in tasks.items()}, 'crossBranchSeedDifferences', sum(not r['arcClusterSeedSame'] for r in cases))
elif sys.argv[1] == 'package':
    cache = STAGE / 'asset-cache'
    if cache.exists(): raise RuntimeError('Refuse to overwrite asset cache')
    cache.mkdir()
    for host in ('first', 'third'):
        with zipfile.ZipFile(STAGE / host / 'prepared-assets.zip') as archive:
            for info in archive.infolist():
                assert info.filename.startswith('export-assets/') and '..' not in Path(info.filename).parts
                dst = cache / info.filename
                assert not dst.exists()
                dst.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as src, dst.open('wb') as dest: shutil.copyfileobj(src, dest)
    cases = json.loads((STAGE / 'prepared-cases.json').read_text(encoding='utf-8'))
    reuse = list(csv.DictReader((STAGE / 'reuse-audit.csv').open(encoding='utf-8-sig')))
    reused = [r for r in reuse if r['reusable'] == 'True']
    assert len(reused) == 807
    config_table = tsv_read(STAGE / 'second/audit-configs.tsv')
    config_by_id = {r['contractId']: base64.b64decode(r['propertiesB64']).decode('utf-8') for r in config_table}
    ng_rows = tsv_read(STAGE / 'second/audit-rows.tsv')
    for host, (allowed, java) in HOSTS.items():
        payload = STAGE / host / 'ready'
        if payload.exists(): raise RuntimeError('Refuse to overwrite ready payload')
        payload.mkdir()
        selected = [c for c in cases if c['target'] == host]
        for case in selected:
            for kind, ext, field in (('inputs', 'dat', 'inputSha'), ('seeds', 'seed', 'seedSha')):
                rel = f"{kind}/{case['scenario']}.{ext}"
                src = cache / 'export-assets' / rel
                assert sha(src) == case[field]
                dst = payload / 'assets' / rel
                dst.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(src, dst)
        shutil.copyfile(STAGE / host / 'solve-prepared.tsv', payload / 'solve.tsv')
        tsv_write(payload / 'case-map.tsv', selected)
        with (payload / 'reuse-index.csv').open('w', newline='', encoding='utf-8-sig') as stream:
            writer = csv.DictWriter(stream, fieldnames=list(reused[0])); writer.writeheader(); writer.writerows(reused)
        family = host != 'first'
        template = next(r for r in ng_rows if r['size'] == '20' and (r['setupType'] == 'family') == family)
        text = config_by_id[template['contractId']]
        text = '\n'.join(s for s in text.splitlines() if not s.startswith(('config.fixedInitialColumnSeed=', 'config.solveTimeLimitSeconds=')))
        (payload / 'profile-lock.properties').write_text('# Audit reference only; runtime reads frozen JAR and solve.tsv.\n' + text + '\n', encoding='utf-8')
        run = fr'''@echo off
setlocal
cd /d "%~dp0"
set "JAVA={java}"
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
call "%~dp0run.cmd" "%~dp0solve.tsv" 6 1>"%~dp0scheduler-logs\solve.out.log" 2>"%~dp0scheduler-logs\solve.err.log"
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" (echo FINISHED>experiment.status) else (echo FAILED>experiment.status)
echo %RC%>worker.exit-code
exit /b %RC%
'''
        (payload / 'run.cmd').write_bytes(run.replace('\n', '\r\n').encode('utf-8'))
        (payload / 'worker.cmd').write_bytes(worker.replace('\n', '\r\n').encode('utf-8'))
        (payload / 'experiment.status').write_text('PREPARED_NOT_STARTED\n', encoding='ascii')
        (payload / 'experiment.properties').write_text('profileVersion=2026-09-12-v8\nsolverJarSha256=1b00188438f7a38ddacb809ebe2f7dd736dfb5a31d734a2e0df484713a6e432a\nvariant=B\nmaxParallel=6\nsolverThreads=1\nrootOnly=true\ncutSet=false\nstatus=PREPARED_NOT_STARTED\n', encoding='utf-8')
        summary = dict(host=host, tasks=len(selected), status='PREPARED_NOT_STARTED', reused=807,
                       historicalFailures=3, sourceVersion='20260913-ng-cutset-supernode-v8',
                       crossBranchSeedDifferences=sum(not c['arcClusterSeedSame'] for c in selected))
        (payload / 'preparation-summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
        (payload / 'README.md').write_text(
            '# NG-DSSR B 正式主线准备\n\n仅准备，尚未启动。配置由冻结solver.jar中的命名profile与solve.tsv覆盖项共同决定，profile-lock仅供审计。'
            '\n\n复用索引只引用第二机807项原结果；3项历史OOM未加入常规六并发。新任务与对应分支的TI共用输入和seed：random对Arc、family对Cluster。'
            f'\n\n本机{len(selected)}项；n60限10800秒，n80限18000秒，100000节点、单模型单CPLEX线程；worker以6并发调用调度器。'
            '\n\nworker.lock、worker.exit-code和runs均不应在准备阶段出现。后续经用户确认后按既有远程后台SOP启动，不在本次执行。\n', encoding='utf-8')
        hashed = [dict(relative='solver.jar', sha256='1b00188438f7a38ddacb809ebe2f7dd736dfb5a31d734a2e0df484713a6e432a')]
        hashed += [dict(relative=p.relative_to(payload).as_posix(), sha256=sha(p)) for p in sorted(payload.rglob('*')) if p.is_file()]
        tsv_write(payload / 'asset-hashes.tsv', hashed)
        with zipfile.ZipFile(STAGE / host / 'ready-package.zip', 'x', zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(payload.rglob('*')):
                if file.is_file(): archive.write(file, file.relative_to(payload).as_posix())
        print('PACKAGED', summary)
else:
    raise ValueError('Expected plan or package')
