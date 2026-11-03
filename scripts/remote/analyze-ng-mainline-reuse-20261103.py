"""核对正式NG-B与TI的输入、实际seed和共同参数，不修改远端结果。"""
import base64
import csv
import io
import json
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGE = ROOT / '.codex-tmp/ng-mainline-20261103-prepared'
COMMON = [
    'runALNSForSeed', 'alnsMaxRuntimeMillis', 'alnsMaxNoImproveIterations',
    'alnsUseSimulatedAnnealingAcceptance', 'initialHeuristicColumnHistoryMode',
    'acceptedSolutionHistoryLimit', 'cplexRootAlgorithm', 'enableTwoStageStrongBranching',
    'strongBranchingCandidateLimit', 'strongBranchingPhase2CandidateLimit',
    'strongBranchingPhase2MaxHeuristicPasses', 'strongBranchingScoreEpsilon',
    'enableStrongBranchingLightweightRepair', 'enableStrongBranchingBranchImpliedPenalty',
    'enableDualBoundPruning', 'dualBoundPruningTolerance', 'enableDualStabilization',
    'enableRestrictedMasterIntegerHeuristic', 'enableRouteEnumeration',
    'branchSeedColumnLimit', 'branchSeedReducedCostAllowance', 'branchingTolerance',
    'pseudoCostInf', 'solveTimeLimitSeconds', 'maxNodes',
]


def decoded(value):
    return base64.b64decode(value).decode('utf-8-sig') if value else ''


def properties(text):
    return dict(line.split('=', 1) for line in text.splitlines() if line and not line.startswith('#') and '=' in line)


def key(row):
    return tuple(row[k] for k in ('taskSetId', 'size', 'machines', 'setupType', 'scaleLevel', 'windowLevel'))


rows = []
contracts = {}
for host in ('first', 'second', 'third'):
    with (STAGE / host / 'audit-configs.tsv').open(encoding='utf-8-sig', newline='') as stream:
        for item in csv.DictReader(stream, delimiter='\t'):
            contracts[item['contractId']] = properties(decoded(item['propertiesB64']))
    with (STAGE / host / 'audit-rows.tsv').open(encoding='utf-8-sig', newline='') as stream:
        for row in csv.DictReader(stream, delimiter='\t'):
            row['host'] = host
            row['props'] = properties(decoded(row['runPropsB64']))
            row['summary'] = next(csv.DictReader(io.StringIO(decoded(row['summaryB64']))), {}) if row['summaryB64'] else {}
            row['config'] = contracts.get(row['contractId'], {})
            rows.append(row)

ng = [r for r in rows if r['host'] == 'second']
ti = [r for r in rows if r['host'] != 'second']
assert len(ng) == 810 and len({key(r) for r in ng}) == 810
normal = [r for r in ng if r['success'] == 'true' and r['summary']]
assert len(normal) == 807
ti_cases = defaultdict(list)
for row in ti:
    ti_cases[key(row)].append(row)
audit = []
parameter_diffs = Counter()
all_diffs = Counter()
for row in ng:
    references = ti_cases[key(row)]
    assert references, row['runId']
    # 正式主比较按family对Cluster-TI、random对Arc-TI；另保留跨两种分支的严格核对。
    primary = [t for t in references if (t['host'] == 'third' if row['setupType'] == 'family' else t['host'] == 'first')]
    assert primary, row['runId']
    targets = [t for t in primary if t['success'] == 'true' and t['config']]
    expected_inputs = {t['inputSha'] for t in references}
    primary_fps = {t['seedFingerprint'] for t in primary}
    is_normal = row['success'] == 'true' and bool(row['summary'])
    input_match = expected_inputs == {row['inputSha']}
    primary_match = primary_fps == {row['seedFingerprint']}
    all_seed_match = {t['seedFingerprint'] for t in references} == {row['seedFingerprint']}
    raw_seed_match = {t['seedSha'] for t in primary} == {row['seedSha']}
    actual_seed_match = row['props'].get('seedFingerprint') == row['seedFingerprint'] if is_normal else False
    differences = []
    for t in targets if is_normal else []:
        for name in COMMON:
            k = 'config.' + name
            if row['config'].get(k) != t['config'].get(k):
                differences.append(name)
                parameter_diffs[(name, row['config'].get(k), t['config'].get(k))] += 1
        for k in row['config'].keys() | t['config'].keys():
            if row['config'].get(k) != t['config'].get(k):
                all_diffs[(k, row['config'].get(k), t['config'].get(k))] += 1
    valid_output = (row['summary'].get('validationFeasible') == 'true'
                    and row['summary'].get('validationObjectiveConsistent') == 'true') if is_normal else False
    reusable = is_normal and input_match and primary_match and actual_seed_match and valid_output and bool(targets) and not differences
    audit.append({
        'runId': row['runId'], 'taskSetId': row['taskSetId'], 'n': row['size'],
        'm': row['machines'], 'setup': row['setupType'], 'scale': row['scaleLevel'],
        'window': row['windowLevel'], 'normal': is_normal, 'inputMatchAllTI': input_match,
        'seedMatchPrimaryTI': primary_match, 'seedMatchAllTI': all_seed_match,
        'seedBytesMatchPrimaryTI': raw_seed_match, 'loadedSeedFingerprintMatchesFile': actual_seed_match,
        'outputValidated': valid_output, 'tiEffectiveConfigCount': len(targets),
        'commonParameterDifferences': ','.join(sorted(set(differences))), 'reusable': reusable,
        'ngInputSha': row['inputSha'], 'ngSeedFingerprint': row['seedFingerprint'],
        'tiPrimarySeedFingerprints': ','.join(sorted(primary_fps)), 'sourceOutput': row['outputDir'],
    })

with (STAGE / 'reuse-audit.csv').open('w', encoding='utf-8-sig', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=list(audit[0])); writer.writeheader(); writer.writerows(audit)
report = {
    'manifestCounts': dict(Counter(r['host'] for r in rows)),
    'ngNormal': len(normal), 'reusableStrictPrimary': sum(r['reusable'] for r in audit),
    'normalInputMatched': sum(r['normal'] and r['inputMatchAllTI'] for r in audit),
    'normalSeedPrimaryMatched': sum(r['normal'] and r['seedMatchPrimaryTI'] for r in audit),
    'normalSeedAllTIMatched': sum(r['normal'] and r['seedMatchAllTI'] for r in audit),
    'normalActualSeedConsistent': sum(r['normal'] and r['loadedSeedFingerprintMatchesFile'] for r in audit),
    'bySizeSetup': [],
    'commonParameterDifferences': [dict(key=k[0], ng=k[1], ti=k[2], comparisons=n) for k, n in parameter_diffs.items()],
    'allConfigDifferences': [dict(key=k[0], ng=k[1], ti=k[2], comparisons=n) for k, n in all_diffs.items()],
}
for n in ('20', '40', '50'):
    for setup in ('random', 'family'):
        group = [a for a in audit if a['n'] == n and a['setup'] == setup]
        report['bySizeSetup'].append(dict(n=n, setup=setup, planned=len(group), normal=sum(a['normal'] for a in group),
            inputMatched=sum(a['normal'] and a['inputMatchAllTI'] for a in group),
            primarySeedMatched=sum(a['normal'] and a['seedMatchPrimaryTI'] for a in group),
            reusable=sum(a['reusable'] for a in group)))
(STAGE / 'reuse-summary.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps({k: v for k, v in report.items() if k != 'allConfigDifferences'}, indent=2, ensure_ascii=False))
