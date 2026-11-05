"""固定比较检修前保留的49个n60 NG场景，不将续跑中新结果混入。"""
import base64
import collections
import csv
import io
import json
import math
import re
import statistics
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.codex-tmp/ng49-ti-20261105'
WORK.mkdir(exist_ok=True)
PLAN = json.loads((ROOT / 'docs/logs/data/20261105-ng-resume-plan.json').read_text(encoding='utf-8'))
HOSTS = {
    'first': ('100.68.243.112', 'C:/Users/Changxin/Downloads/solver_known_hosts', 'D:/ccx_work/考虑交付的机器调度'),
    'second': ('100.68.243.192', 'C:/Users/Changxin/.ssh/known_hosts', 'D:/ccx'),
}
STAGE = 'work1/experiments/20261104-ng-mainline-a-prepared'


def properties(text):
    return dict(line.split('=', 1) for line in text.splitlines() if '=' in line and not line.startswith('#'))


def number(value):
    try:
        v = float(value)
        return v if math.isfinite(v) else None
    except (TypeError, ValueError):
        return None


def key(row):
    return row['taskSetId'], row['machines'], row['scaleLevel'], row['windowLevel']


def decode(value):
    return base64.b64decode(value).decode('utf-8-sig')


for host, ids in PLAN['completedMarkerHashes'].items():
    ip, known, root = HOSTS[host]
    if all((WORK / (rid + suffix)).is_file() for rid in ids for suffix in ['.csv', '.properties']):
        continue
    commands = []
    for rid in ids:
        remote = f'{root}/{STAGE}/runs/{rid}'
        commands += [f'get "{remote}/ngDssr/{rid}.core-summary.csv" "{(WORK / (rid + ".csv")).as_posix()}"',
                     f'get "{remote}/run.properties" "{(WORK / (rid + ".properties")).as_posix()}"']
    batch = WORK / (host + '-read.sftp')
    batch.write_text('\n'.join(commands) + '\n', encoding='utf-8')
    command = ['sftp', '-b', str(batch), '-i', 'C:/Users/Changxin/.ssh/codex_solver_auto_ed25519',
               '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
               '-o', 'UserKnownHostsFile=' + known, 'codex-runner@' + ip]
    out = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    (WORK / (host + '-fetch.log')).write_bytes(out.stdout)
    assert out.returncode == 0, (host, out.stdout.decode('utf-8', errors='replace')[-2000:])

ti_rows = list(csv.DictReader((ROOT / '.codex-tmp/ng-mainline-a-20261104-prepared/third/audit-rows.tsv')
                             .open(encoding='utf-8-sig'), delimiter='\t'))
ti = {}
for row in ti_rows:
    if row['size'] == '60' and row['setupType'] == 'family':
        k = (key(row), row['algorithm'])
        assert k not in ti
        ti[k] = row
cases = {r['scenario']: r for r in csv.DictReader(
    (ROOT / 'experiment-suite/formal/manifests/20261104-ng-mainline-a-prepared/first/case-map.tsv')
    .open(encoding='utf-8-sig'), delimiter='\t')}
rows = []
for rid in PLAN['completedPreserved']:
    match = re.fullmatch(r'pricing-(n060-set\d+)-m(\d+)-family-(base|medium|high)-w(zero|narrow|wide)-f3-ng_dssr-mainline-a', rid)
    assert match, rid
    taskset, machines, scale, window = match.groups()
    scenario = f'{taskset}-m{machines}-family-{scale}-w{window}-f3'
    case = cases[scenario]
    prop = properties((WORK / (rid + '.properties')).read_text(encoding='utf-8-sig'))
    assert prop['seedFingerprint'] == case['seedFingerprint'] and prop['cplexThreads'] == '1'
    assert prop['enableClusterBranchingOverride'] == 'true'
    assert prop['structuredArcStrictTypePriorityOverride'] == 'true'
    ng = next(csv.DictReader((WORK / (rid + '.csv')).open(encoding='utf-8-sig')))
    records = [('NG-DSSR', ng, True, rid, '06' if rid in PLAN['completedMarkerHashes']['first'] else '42976CD')]
    for algorithm, label in [('TIME_INDEXED', 'TI'), ('TIME_INDEXED_SRI', 'TI+SRI')]:
        original = ti[((taskset, machines, scale, window), algorithm)]
        assert original['inputSha'] == case['inputSha']
        assert original['seedFingerprint'] == case['seedFingerprint']
        p = properties(decode(original['runPropsB64']))
        assert p['seedFingerprint'] == prop['seedFingerprint'] and p['cplexThreads'] == '1'
        assert p['enableClusterBranchingOverride'] == 'true'
        assert p['structuredArcStrictTypePriorityOverride'] == 'true'
        summary = next(csv.DictReader(io.StringIO(decode(original['summaryB64']))), {})
        records.append((label, summary, original['success'] == 'true', original['runId'], 'AS7L732 cached Nov4'))
    for algorithm, summary, success, runid, host in records:
        normal = success and summary.get('status') in {'FINISHED', 'TIME_LIMIT', 'ROOT_PROCESSED'}
        if normal:
            assert summary['validationFeasible'] == 'true' and summary['validationObjectiveConsistent'] == 'true'
        gap = number(summary.get('gapPercent')) if normal else None
        rows.append({'scenario': scenario, 'taskSetId': taskset, 'machines': int(machines), 'scale': scale,
                     'window': window, 'algorithm': algorithm, 'success': normal,
                     'status': summary.get('status', 'FAILED') if normal else 'FAILED',
                     'optimal': normal and summary['status'] in {'FINISHED', 'ROOT_PROCESSED'}
                     and gap is not None and gap <= 1e-6
                     and number(summary.get('incumbentCost')) == number(summary.get('bestBound')),
                     'seconds': number(summary.get('solveTimeSeconds')) if normal else None,
                     'nodes': number(summary.get('processedNodes')) if normal else None,
                     'gap': gap, 'rootSeconds': number(summary.get('rootSolveTimeSeconds')) if normal else None,
                     'incumbent': number(summary.get('incumbentCost')) if normal else None,
                     'bound': number(summary.get('bestBound')) if normal else None,
                     'runId': runid, 'host': host})
assert len(rows) == 147 and len({r['scenario'] for r in rows}) == 49
by_case = collections.defaultdict(list)
for row in rows:
    by_case[row['scenario']].append(row)
common = {k for k, v in by_case.items() if all(r['success'] for r in v)}
common_gap = {k for k in common if all(r['gap'] is not None for r in by_case[k])}
common_optimal = {k for k in common if all(r['optimal'] for r in by_case[k])}


def mean(items, field):
    values = [r[field] for r in items if r[field] is not None]
    return statistics.mean(values) if values else None


def describe(subset):
    result = {}
    for algorithm in ['NG-DSSR', 'TI', 'TI+SRI']:
        group = [r for r in subset if r['algorithm'] == algorithm]
        paired = [r for r in group if r['scenario'] in common]
        gaps = [r for r in group if r['scenario'] in common_gap]
        optimum = [r for r in group if r['scenario'] in common_optimal]
        result[algorithm] = {'n': len(group), 'normal': sum(r['success'] for r in group),
            'optimal': sum(r['optimal'] for r in group), 'failed': sum(not r['success'] for r in group),
            'gapMissing': sum(r['success'] and r['gap'] is None for r in group),
            'pairedN': len(paired), 'pairedSeconds': mean(paired, 'seconds'), 'pairedNodes': mean(paired, 'nodes'),
            'pairedRootSeconds': mean(paired, 'rootSeconds'),
            'gapN': len(gaps), 'gapMean': mean(gaps, 'gap'),
            'commonOptimalN': len(optimum), 'commonOptimalSeconds': mean(optimum, 'seconds')}
    return result


report = {'scope': 'fixed 49 completed NG n60 family F3 cases, not all n60',
          'tiSource': 'local frozen Nov4 third/audit-rows.tsv, no connection to handed-off host',
          'all': describe(rows), 'machines': {}, 'scale': {}, 'window': {}}
for dimension in ['machines', 'scale', 'window']:
    for value in sorted({r[dimension] for r in rows}):
        report[dimension][value] = describe([r for r in rows if r[dimension] == value])
output = ROOT / 'docs/logs/data'
csv_path = output / '20261105-ng49-ti-paired.csv'
with csv_path.open('w', newline='', encoding='utf-8-sig') as stream:
    writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
    writer.writeheader(); writer.writerows(rows)
(output / '20261105-ng49-ti-summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
