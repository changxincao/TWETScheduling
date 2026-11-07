"""只读分析已保存的正式快照；不连接远端，不把重复配对当独立算例。"""
import base64
import csv
import io
import json
import re
import hashlib
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent


def read_csv(path, delimiter=','):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream, delimiter=delimiter))


def audit_snapshot(relative):
    path = ROOT / relative
    rows = read_csv(path, '\t')
    groups = {}
    for row in rows:
        if row['algorithm'] != 'NG_DSSR':
            continue
        label = row['label']
        group = groups.setdefault(label, dict(rows=0, success=0, summaries=0, preprocessing=Counter(), statuses=Counter()))
        group['rows'] += 1
        group['success'] += row['success'] == 'true'
        if not row['summaryB64']:
            continue
        text = base64.b64decode(row['summaryB64']).decode('utf-8-sig')
        summaries = list(csv.DictReader(io.StringIO(text)))
        if not summaries:
            continue
        summary = summaries[0]
        group['summaries'] += 1
        group['preprocessing'][summary.get('rootPreprocessingApplied', 'MISSING')] += 1
        group['statuses'][summary.get('status', 'MISSING')] += 1
    return dict(source=relative, groups=groups)


def live_snapshots():
    work = ROOT / '.codex-tmp/ng-m3-pricing-20261105'
    result, excerpts = [], []
    for path in sorted(work.glob('*ng_dssr-mainline-a.live.log')):
        calls = Counter()
        horizons = []
        root = []
        for line in path.open(encoding='utf-8', errors='replace'):
            if 'phase=timeIndexedRootPreprocess.done' in line:
                root.append(line.strip())
            # 前面的TI预处理也有piWindow，不能把TI行混入NG统计。
            if line.startswith('Pricing[GCNGBBStyleNgDssrPricing]'):
                match = re.search(r'piWindow=(enabled|disabled)', line)
                calls[match[1] if match else 'MISSING'] += 1
                horizon = re.search(r'\bpricingHorizon=([0-9.]+)', line)
                if horizon:
                    horizons.append(float(horizon[1]))
        assert len(root) == 1, (path, len(root))
        fields = {name: int(re.search(r'\b' + name + r'=(\d+)', root[0])[1])
                  for name in ['timeArcs', 'promotedOrdinaryArcs', 'windowJobs']}
        excerpts.extend([str(path.relative_to(ROOT)), root[0], ''])
        result.append(dict(runId=path.name.removesuffix('.live.log'), source=str(path.relative_to(ROOT)),
                           sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                           **fields, ngPricingCalls=sum(calls.values()), piEnabled=calls['enabled'],
                           piDisabled=calls['disabled'], piMissing=calls['MISSING'],
                           reportedHorizons=len(horizons), fractionalHorizons=sum(not h.is_integer() for h in horizons)))
    (OUT / 'root-preprocess-excerpts.txt').write_text('\n'.join(excerpts), encoding='utf-8')
    return result


def paired_results():
    path = ROOT / 'docs/logs/data/20261105-finite-m-history-audit/pairs.csv'
    rows = read_csv(path)
    summaries = {}
    for label, subset in [('allSameSeed', rows), ('primarySameSeed', [r for r in rows if r['primary'] == 'True'])]:
        valid = [r for r in subset if r['sameSeed'] == r['ngValid'] == r['tiValid'] == 'True']
        common = [r for r in valid if r['commonOptimal'] == 'True']
        differences = [abs(float(r['ngUb']) - float(r['tiUb'])) for r in common]
        keys = {(r['inputSha'], r['size'], r['machines'], r['setupType'], r['scaleLevel'], r['windowLevel']) for r in common}
        summaries[label] = dict(pairs=len(valid), commonOptimalPairs=len(common), objectiveTolerance=1e-5,
                                distinctCommonOptimalScenarios=len(keys),
                                objectiveMismatches=sum(d > 1e-5 for d in differences),
                                maxObjectiveDifference=max(differences, default=None),
                                contradictions=sum(bool(r['contradiction']) for r in valid))
    return dict(source=str(path.relative_to(ROOT)), **summaries)


summary = dict(
    scope='Local snapshots captured on 2026-11-04/05; not current progress or all historical runs.',
    audits=[audit_snapshot(p) for p in [
        '.codex-tmp/ng-mainline-a-20261104-prepared/second/audit-rows.tsv',
        '.codex-tmp/ng-mainline-a-20261104-prepared/first/today-audit-rows.tsv']],
    liveSnapshots=live_snapshots(), pairs=paired_results())
summary['liveTotals'] = {field: sum(r[field] for r in summary['liveSnapshots'])
                         for field in ['ngPricingCalls', 'piEnabled', 'piDisabled', 'piMissing']}
(OUT / 'history-impact-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
with (OUT / 'formal-live-exposure.csv').open('w', encoding='utf-8-sig', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=summary['liveSnapshots'][0].keys())
    writer.writeheader()
    writer.writerows(summary['liveSnapshots'])
print(json.dumps(summary, ensure_ascii=False, indent=2))
