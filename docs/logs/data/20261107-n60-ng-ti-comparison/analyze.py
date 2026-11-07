"""配对本轮270个n60场景；只读既有快照，不连接或修改远端。"""
import base64
import collections
import csv
import hashlib
import io
import json
import math
import re
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
LIVE = ROOT / '.codex-tmp/remote-progress-20261107'
CASE_MAP = ROOT / 'experiment-suite/formal/manifests/20261104-ng-mainline-a-prepared/second/case-map.tsv'
TI_SOURCES = {
    'random': ROOT / '.codex-tmp/ng-mainline-20261103-prepared/first/audit-rows.tsv',
    'family': ROOT / '.codex-tmp/ng-mainline-a-20261104-prepared/third/audit-rows.tsv',
}
ALGORITHMS = ['NG-DSSR', 'TI', 'TI+SRI']
NORMAL = {'FINISHED', 'ROOT_PROCESSED', 'TIME_LIMIT'}


def read_csv(path, delimiter=','):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream, delimiter=delimiter))


def number(value):
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (ValueError, TypeError):
        return None


def properties(value):
    return dict(line.split('=', 1) for line in value.splitlines() if '=' in line and not line.startswith('#'))


def decode(value):
    return base64.b64decode(value).decode('utf-8-sig')


def case_key(case):
    return tuple(case[k] for k in ['taskSetId', 'machines', 'setup', 'scale', 'window'])


def record(case, algorithm, summary, success, run_id, source):
    normal = success and summary.get('status') in NORMAL
    if normal:
        assert summary['validationFeasible'] == 'true'
        assert summary['validationObjectiveConsistent'] == 'true'
    gap = number(summary.get('gapPercent')) if normal else None
    ub, lb = (number(summary.get(k)) if normal else None for k in ['incumbentCost', 'bestBound'])
    optimal = normal and summary['status'] in {'FINISHED', 'ROOT_PROCESSED'} and gap is not None and gap <= 1e-6
    if optimal:
        assert ub is not None and lb is not None and math.isclose(ub, lb, rel_tol=1e-8, abs_tol=1e-4)
    return {**case, 'algorithm': algorithm, 'normal': normal, 'optimal': optimal,
            'status': summary.get('status') if normal else 'FAILED',
            'seconds': number(summary.get('solveTimeSeconds')) if normal else None,
            'nodes': number(summary.get('processedNodes')) if normal else None,
            'gap': gap, 'incumbent': ub, 'bound': lb,
            'rootSeconds': number(summary.get('rootSolveTimeSeconds')) if normal else None,
            'runId': run_id, 'source': source}


cases, audit = {}, {}
for row in read_csv(CASE_MAP, '\t'):
    match = re.fullmatch(r'(n060-set\d+)-m(\d+)-(family|random)-(base|medium|high)-w(zero|narrow|wide)(?:-f3)?', row['scenario'])
    assert match, row['scenario']
    taskset, machines, setup, scale, window = match.groups()
    cases[row['scenario']] = dict(scenario=row['scenario'], taskSetId=taskset, machines=machines,
                                  setup=setup, scale=scale, window=window)
    audit[row['scenario']] = row
assert len(cases) == 270

ti = {}
for setup, path in TI_SOURCES.items():
    for row in read_csv(path, '\t'):
        if row['size'] != '60' or row['setupType'] != setup:
            continue
        key = (row['taskSetId'], row['machines'], setup, row['scaleLevel'], row['windowLevel'], row['algorithm'])
        assert key not in ti
        ti[key] = row
assert len(ti) == 540

ng_states, ng_summaries = collections.defaultdict(list), {}
for host in ['first', 'second']:
    for line in (LIVE / (host + '-inspect.tsv')).read_text(encoding='utf-8-sig').splitlines():
        if line.startswith('RUN\t'):
            parts = line.split('\t')
            ng_states[parts[1]].append(dict(host=host, success=parts[2] == 'true', state=parts[3],
                                          inputSha=parts[4], seedSha=parts[5]))
    for row in read_csv(LIVE / (host + '-core-summary-stream.csv')):
        if row['instanceName'].startswith('pricing-n060-'):
            key = (host, row['instanceName'])
            assert key not in ng_summaries
            ng_summaries[key] = row

rows, pair_audit = [], []
for scenario, case in cases.items():
    rid = 'pricing-' + scenario + '-ng_dssr-mainline-a'
    states = ng_states[rid]
    successes = [s for s in states if s['success']]
    assert len(successes) <= 1
    state = successes[0] if successes else next(s for s in states if s['state'] == 'FAILED')
    reference = audit[scenario]
    assert state['inputSha'] == reference['inputSha'] and state['seedSha'] == reference['seedSha']
    rows.append(record(case, 'NG-DSSR', ng_summaries.get((state['host'], rid), {}), state['success'], rid, state['host']))
    for algorithm, label in [('TIME_INDEXED', 'TI'), ('TIME_INDEXED_SRI', 'TI+SRI')]:
        original = ti[(*case_key(case), algorithm)]
        assert original['inputSha'] == reference['inputSha']
        assert original['seedFingerprint'] == reference['seedFingerprint']
        prop = properties(decode(original['runPropsB64']))
        assert prop['seedFingerprint'] == reference['seedFingerprint'] and prop['cplexThreads'] == '1'
        expected_cluster = 'true' if case['setup'] == 'family' else 'false'
        assert prop['enableClusterBranchingOverride'] == expected_cluster
        if expected_cluster == 'true':
            assert prop['structuredArcStrictTypePriorityOverride'] == 'true'
            assert prop['clusterMstThetaOverride'] == '0.5' and prop['clusterTemporalWeightOverride'] == '0.0'
        summary = next(csv.DictReader(io.StringIO(decode(original['summaryB64']))), {})
        rows.append(record(case, label, summary, original['success'] == 'true', original['runId'], str(TI_SOURCES[case['setup']].relative_to(ROOT))))
        pair_audit.append(dict(scenario=scenario, algorithm=label, inputSha=original['inputSha'],
                               seedFingerprint=original['seedFingerprint'], cluster=expected_cluster,
                               profileVersion=prop['profileVersion'], sourceRunId=original['runId'],
                               sourceManifest=original['manifest']))
assert len(rows) == 810 and len(pair_audit) == 540

by_case = collections.defaultdict(list)
for row in rows:
    by_case[row['scenario']].append(row)
for scenario, group in by_case.items():
    assert len(group) == 3 and {r['algorithm'] for r in group} == set(ALGORITHMS)
    optima = [r['incumbent'] for r in group if r['optimal']]
    assert not optima or all(math.isclose(v, optima[0], rel_tol=1e-8, abs_tol=1e-4) for v in optima), scenario
    for r in group:
        if r['normal'] and r['bound'] is not None:
            for other in group:
                if other['normal'] and other['incumbent'] is not None:
                    assert r['bound'] <= other['incumbent'] + max(1e-4, abs(other['incumbent']) * 1e-8), scenario


def mean(group, field):
    values = [r[field] for r in group if r[field] is not None]
    return statistics.mean(values) if values else None


def describe(group):
    ids = {r['scenario'] for r in group}
    common = {s for s in ids if all(r['normal'] for r in by_case[s])}
    gap_ids = {s for s in common if all(r['gap'] is not None for r in by_case[s])}
    optimum = {s for s in common if all(r['optimal'] for r in by_case[s])}
    result = dict(cases=len(ids), pairedN=len(common), gapN=len(gap_ids), commonOptimalN=len(optimum))
    for algorithm in ALGORITHMS:
        current = [r for r in group if r['algorithm'] == algorithm]
        paired = [r for r in current if r['scenario'] in common]
        optimal = [r for r in current if r['scenario'] in optimum]
        result[algorithm] = dict(normal=sum(r['normal'] for r in current), optimal=sum(r['optimal'] for r in current),
                                 failed=sum(not r['normal'] for r in current),
                                 timeLimit=sum(r['status'] == 'TIME_LIMIT' for r in current),
                                 missingGap=sum(r['normal'] and r['gap'] is None for r in current),
                                 pairedSeconds=mean(paired, 'seconds'), pairedNodes=mean(paired, 'nodes'),
                                 gapMean=mean([r for r in current if r['scenario'] in gap_ids], 'gap'),
                                 commonOptimalSeconds=mean(optimal, 'seconds'))
    return result


report = dict(scope='270 n60 scenarios: family F3 Cluster, random Arc; NG A/v8, archived formal TI v5/v6',
              timeLimitSeconds=10800, all=describe(rows), setup={}, familyMachines={}, scale={}, window={}, setupScale={})
for setup in ['family', 'random']:
    subset = [r for r in rows if r['setup'] == setup]
    report['setup'][setup] = describe(subset)
    report['setupScale'][setup] = {v: describe([r for r in subset if r['scale'] == v]) for v in ['base', 'medium', 'high']}
for machines in ['2', '3', '4']:
    report['familyMachines'][machines] = describe([r for r in rows if r['setup'] == 'family' and r['machines'] == machines])
for field in ['scale', 'window']:
    report[field] = {v: describe([r for r in rows if r[field] == v]) for v in sorted({r[field] for r in rows})}
report['sources'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in [CASE_MAP, *TI_SOURCES.values(), *[LIVE / (h + '-inspect.tsv') for h in ['first', 'second']],
                               *[LIVE / (h + '-core-summary-stream.csv') for h in ['first', 'second']]]}
for name, items in [('paired.csv', rows), ('pair-audit.csv', pair_audit)]:
    with (OUT / name).open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(items[0]))
        writer.writeheader()
        writer.writerows(items)
(OUT / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k: report[k] for k in ['all', 'setup', 'familyMachines']}, ensure_ascii=False, indent=2))
