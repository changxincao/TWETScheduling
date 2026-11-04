"""将已验收n60 A清单迁移至06主机，引用已完成项，不启动求解。"""
import base64
import csv
import hashlib
import io
import json
import re
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGE = ROOT / '.codex-tmp/ng-mainline-a-20261104-prepared'
SOURCE = STAGE / 'second/ready'
TARGET = STAGE / 'first/ready'
REMOTE = 'D:/ccx_work/考虑交付的机器调度/work1/experiments/20261104-ng-mainline-a-prepared'
PREVIOUS = 'D:/ccx/work1/experiments/20261104-ng-mainline-a-prepared'
SHA = '1b00188438f7a38ddacb809ebe2f7dd736dfb5a31d734a2e0df484713a6e432a'
VARIABLE = {'config.instanceName', 'config.bpcOutputRoot', 'config.liveTraceLogPath',
            'config.fixedInitialColumnSeed', 'config.solveTimeLimitSeconds'}
ARG = re.compile(r'--([A-Za-z0-9]+)=(?:"([^"]*)"|(\S+))')


def read(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def write(path, rows):
    with path.open('x', encoding='utf-8', newline='') as stream:
        out = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t',
                             quoting=csv.QUOTE_NONE, quotechar=None, lineterminator='\n')
        out.writeheader()
        out.writerows(rows)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def props(text):
    return dict(line.split('=', 1) for line in text.splitlines()
                if line and not line.startswith('#') and '=' in line)


def decode(value):
    return base64.b64decode(value).decode('utf-8-sig') if value else ''


def options(text):
    pairs = ARG.findall(text)
    result = {key: a or b for key, a, b in pairs}
    assert len(pairs) == len(result)
    return result


tasks = read(SOURCE / 'solve.tsv')
cases = read(SOURCE / 'case-map.tsv')
audits = read(STAGE / 'first/today-audit-rows.tsv')
contracts = {row['contractId']: props(decode(row['propertiesB64']))
             for row in read(STAGE / 'first/today-audit-configs.tsv')}
assert len(tasks) == len(cases) == len(audits) == 270
assert len({row['runId'] for row in audits}) == 270
audit_by_id = {row['runId']: row for row in audits}
assert {row['runId'] for row in tasks} == set(audit_by_id)
assert {row['size'] for row in tasks} == {'60'}
skipped = []
pending = []
for row in tasks:
    audit = audit_by_id[row['runId']]
    assert audit['args'] == row['args'] and audit['outputDir'] == row['outputDir']
    if audit['success'] == 'true':
        summary = next(csv.DictReader(io.StringIO(decode(audit['summaryB64']))))
        run_props = props(decode(audit['runPropsB64']))
        assert summary['status'] == 'FINISHED' and float(summary['gapPercent']) == 0
        assert summary['validationFeasible'] == summary['validationObjectiveConsistent'] == 'true'
        assert run_props['seedFingerprint'] == audit['seedFingerprint']
        expected = props((SOURCE / f"profile-{row['setupType']}.properties").read_text(encoding='utf-8'))
        actual = contracts[audit['contractId']]
        assert {k: v for k, v in actual.items() if k not in VARIABLE} == {
            k: v for k, v in expected.items() if k not in VARIABLE}
        skipped.append(dict(runId=row['runId'], sourceHost='100.68.243.192',
                            sourceOutput=row['outputDir'], inputSha=audit['inputSha'],
                            seedSha=audit['seedSha'], seedFingerprint=audit['seedFingerprint'],
                            status=summary['status'], solveTimeSeconds=summary['solveTimeSeconds'],
                            nodes=summary['processedNodes'], gapPercent=summary['gapPercent']))
    else:
        new = dict(row)
        new['args'] = row['args'].replace(PREVIOUS, REMOTE)
        new['outputDir'] = row['outputDir'].replace(PREVIOUS, REMOTE)
        before, after = options(row['args']), options(new['args'])
        paths = {'instance', 'seedFile', 'outputDir'}
        assert {k: v for k, v in before.items() if k not in paths} == {
            k: v for k, v in after.items() if k not in paths}
        assert all(after[k].startswith(REMOTE + '/') for k in paths)
        assert after['timeLimitSeconds'] == '10800'
        assert after['timeIndexedCompletionBoundNodeArcFixingMaxDepth'] == '2147483647'
        pending.append(new)
assert len(skipped) == 4 and len(pending) == 266
assert sum(row['state'] == 'state=FAILED' for row in audits) == 2
assert sum(row['state'] == 'state=INTERRUPTED' for row in audits) == 5
assert not TARGET.exists(), 'Refuse to overwrite preparation'
TARGET.mkdir()
for row in read(SOURCE / 'asset-hashes.tsv'):
    if row['relative'].startswith('assets/'):
        origin = SOURCE / row['relative']
        assert sha(origin) == row['sha256']
        destination = TARGET / row['relative']
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(origin, destination)
for name in ('profile-family.properties', 'profile-random.properties', 'reuse-index.csv', 'worker.cmd'):
    shutil.copyfile(SOURCE / name, TARGET / name)
run = (SOURCE / 'run.cmd').read_bytes().replace(b'D:\\Java\\jdk-21', b'D:\\software\\Java\\jdk-21')
(TARGET / 'run.cmd').write_bytes(run)
write(TARGET / 'solve.tsv', pending)
write(TARGET / 'case-map.tsv', [dict(row, target='host06') for row in cases])
metadata = dict(host='DESKTOP-06K8JI8', ip='100.68.243.112', size=60, planned=270,
                tasks=266, skippedCompleted=skipped, previousFailuresQueued=2,
                previousInterruptedQueued=5, variant='A', maxParallel=5, solverThreads=1,
                rootOnly=False, cutSet=False, timeLimitSeconds=10800,
                solverJarSha256=SHA, javaVersion='21.0.3', status='PREPARED_NOT_STARTED')
(TARGET / 'preparation-summary.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
(TARGET / 'experiment.properties').write_text(
    f'profileVersion=2026-09-12-v8\nsolverJarSha256={SHA}\nvariant=A\nmaxParallel=5\n'
    'solverThreads=1\nrootOnly=false\ncutSet=false\ntasks=266\nsize=60\n'
    'planned=270\nreusedN60=4\nstatus=PREPARED_NOT_STARTED\n', encoding='utf-8')
(TARGET / 'experiment.status').write_text('PREPARED_NOT_STARTED\n', encoding='ascii')
(TARGET / 'README.md').write_text(
    '# NG-DSSR A / n60 / DESKTOP-06K8JI8\n\n'
    '270个场景中4个已验证完成，原结果保留在.192且不重跑；此目录排队其余266项，'
    '含2项失败和5项中断的重跑。仅n60，不自动接n80。5个JVM并行，每模型单线程，'
    '3小时时限、100000节点、全节点scalar fixing；family Cluster→Arc、random Arc，'
    'CutSet关闭。其他参数、输入和seed沿用已验收A，不重编译算法。'
    '\n\nJava沿用本机21.0.3，与原.192的21.0.7不同；未修改系统环境。'
    'profile文件仅供审计，实际配置由冻结JAR和solve.tsv决定。\n', encoding='utf-8')
hashes = [dict(relative='solver.jar', sha256=SHA)]
hashes += [dict(relative=p.relative_to(TARGET).as_posix(), sha256=sha(p))
           for p in sorted(TARGET.rglob('*')) if p.is_file()]
write(TARGET / 'asset-hashes.tsv', hashes)
with zipfile.ZipFile(STAGE / 'first/ready-package.zip', 'x', zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(TARGET.rglob('*')):
        if file.is_file():
            archive.write(file, file.relative_to(TARGET).as_posix())
print(json.dumps(dict(tasks=len(pending), skipped=len(skipped), n80=0,
                      rerunFailed=2, rerunInterrupted=5, remote=REMOTE), ensure_ascii=False))
