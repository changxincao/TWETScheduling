"""按清单核对第二机NG变体；保留失败，按同一算例共同最优配对。"""
import csv
import json
import math
import re
import statistics as st
import sys
from collections import Counter, defaultdict
from pathlib import Path


def number(value):
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (ValueError, TypeError):
        return None


def mean(values):
    values = [v for v in values if v is not None]
    return st.mean(values) if values else None


def fmt(value):
    return '-' if value is None else f'{value:.2f}'


raw = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
out = Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
rows = []
for item in raw:
    summary = item['summary'] or {}
    config = item['config']
    row = {k: item[k] for k in ('batch', 'runId', 'variant', 'n', 'm', 'setup', 'scale', 'window', 'taskSet', 'success', 'state')}
    row['dataset'] = 'new50F3' if '20260914' in item['batch'] else 'old'
    row['F'] = (3 if row['dataset'] == 'new50F3' or row['n'] in (20, 40) else 4) if row['setup'] == 'family' else None
    row['relation'] = 'random' if row['F'] is None else ('F>m' if row['F'] > row['m'] else 'F=m' if row['F'] == row['m'] else 'F<m')
    row['status'] = summary.get('status', 'NO_SUMMARY')
    row['seconds'] = number(summary.get('solveTimeSeconds'))
    row['nodes'] = number(summary.get('processedNodes'))
    row['gap'] = number(summary.get('gapPercent'))
    row['ub'] = number(summary.get('incumbentCost'))
    row['lb'] = number(summary.get('bestBound'))
    row['normal'] = bool(row['success'] and row['seconds'] is not None and row['seconds'] > 0)
    row['optimal'] = bool(row['normal'] and row['status'] in ('FINISHED', 'ROOT_PROCESSED') and row['gap'] is not None and abs(row['gap']) <= 1e-5)
    row['clusterCount'] = config.get('run.clusterDiagnostics.clusterCount')
    rows.append(row)

assert len(rows) == 2970
assert len({(r['batch'], r['runId']) for r in rows}) == len(rows)
for row in rows:
    if row['optimal']:
        assert row['ub'] is not None and row['lb'] is not None
        assert abs(row['ub'] - row['lb']) <= max(0.01, abs(row['ub']) * 1e-6), row

# 仅移除实验定义中的分支/fixing参数，验证其余命令行选项逐实例相同。
changes = ('runId', 'outputDir', 'enableClusterBranching', 'structuredArcStrictTypePriority',
           'enableCutSetBranching', 'cutSetSupernodeSeeds', 'clusterMstTheta',
           'clusterTemporalWeight', 'timeIndexedCompletionBoundNodeArcFixingMaxDepth')
contracts = defaultdict(set)
snapshots = defaultdict(list)
for item in raw:
    args = item['args']
    for key in changes:
        args = re.sub(r'--' + key + r'=(?:"[^"]*"|\S+)', '', args)
    key = (item['batch'], item['taskSet'], item['machines'] if 'machines' in item else item['m'], item['setup'], item['scale'], item['window'])
    contracts[key].add(' '.join(args.split()))
    if item['summary']:
        snapshots[key].append(item['config'])
assert all(len(values) == 1 for values in contracts.values()), 'Nonexperimental command differences'
allowed_snapshot_diffs = {
    'config.cutSetSupernodeSeeds', 'config.timeIndexedCompletionBoundNodeArcFixingMaxDepth',
    'config.enableCutSetBranching', 'config.instanceName', 'config.bpcOutputRoot',
    'config.liveTraceLogPath',
}
for key, configs in snapshots.items():
    different = {k for k in set().union(*(set(c) for c in configs)) if k.startswith('config.')
                 and len({c.get(k) for c in configs}) > 1}
    assert different <= allowed_snapshot_diffs, (key, different - allowed_snapshot_diffs)

with (out / 'ng-variants-20261019-rows.csv').open('w', newline='', encoding='utf-8-sig') as handle:
    writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)


def collect(data, dims):
    groups = defaultdict(list)
    for row in data:
        groups[tuple(row[d] for d in dims)].append(row)
    return sorted(groups.items(), key=lambda pair: tuple(str(v) for v in pair[0]))


def full_table(data, dims):
    result = ['| ' + ' | '.join(dims + ['N', '最优', '时限', '异常/缺失', '有效gap数', '平均gap%', '平均时间s', '平均节点']) + ' |',
              '| ' + ' | '.join(['---'] * (len(dims) + 8)) + ' |']
    for key, group in collect(data, dims):
        normal = [r for r in group if r['normal']]
        result.append('| ' + ' | '.join(map(str, key)) + ' | ' + ' | '.join([
            str(len(group)), str(sum(r['optimal'] for r in group)),
            str(sum(r['normal'] and r['status'] == 'TIME_LIMIT' for r in group)),
            str(len(group) - len(normal)), str(sum(r['gap'] is not None for r in normal)),
            f"{mean([r['gap'] for r in normal]):.4f}" if all(r['gap'] is not None for r in normal) and normal else '-',
            fmt(mean([r['seconds'] for r in normal])), fmt(mean([r['nodes'] for r in normal]))]) + ' |')
    return '\n'.join(result)


def paired(data, dims, variants, min_nodes=2):
    cases = defaultdict(dict)
    for row in data:
        key = (row['dataset'], row['taskSet'], row['n'], row['m'], row['setup'], row['scale'], row['window'])
        assert row['variant'] not in cases[key]
        cases[key][row['variant']] = row
    admitted = []
    for case in cases.values():
        if all(v in case and case[v]['optimal'] and case[v]['nodes'] >= min_nodes for v in variants):
            assert max(case[v]['ub'] for v in variants) - min(case[v]['ub'] for v in variants) < 0.01
            admitted.append(case)
    groups = defaultdict(list)
    for case in admitted:
        groups[tuple(case[variants[0]][d] for d in dims)].append(case)
    headers = dims + ['共同N'] + [f'{v}时间/节点' for v in variants]
    pairs = [('F-B', 'F-A'), ('F-C', 'F-A'), ('F-D', 'F-B')] if len(variants) == 4 else [('R-B', 'R-A')]
    headers += [f'{a}/{b}几何比' for a, b in pairs]
    lines = ['| ' + ' | '.join(headers) + ' |', '| ' + ' | '.join(['---'] * len(headers)) + ' |']
    for key, group in sorted(groups.items(), key=lambda pair: tuple(str(v) for v in pair[0])):
        values = list(map(str, key)) + [str(len(group))]
        for v in variants:
            values.append(fmt(mean([case[v]['seconds'] for case in group])) + '/' + fmt(mean([case[v]['nodes'] for case in group])))
        for a, b in pairs:
            ratio = math.exp(st.mean(math.log(case[a]['seconds'] / case[b]['seconds']) for case in group))
            values.append(f'{ratio:.3f}')
        lines.append('| ' + ' | '.join(values) + ' |')
    return '\n'.join(lines)


family = [r for r in rows if r['setup'] == 'family']
random = [r for r in rows if r['setup'] == 'random']
# 当前正式数据：20/40沿用原数据，50 family换为F3；旧50/F4单列。
current = [r for r in rows if r['setup'] == 'random' or r['n'] != 50 or r['dataset'] == 'new50F3']
current_family = [r for r in current if r['setup'] == 'family']
large_family = [r for r in current_family if r['n'] >= 40]
large_random = [r for r in random if r['n'] >= 40]
report = [
    '# 第二机NG-DSSR变体分类复查（2026-10-19）',
    '只读来源为第二机两套正式solve.tsv及逐项SUCCESS/core-summary/config快照，共2970项。未运行求解或修改远端。',
    'A/B：Cluster→Arc，全节点/仅根节点scalar TI fixing；C/D：Cluster→supernode CutSet→Arc，全节点/仅根节点fixing。random仅R-A/R-B，均Arc。除这些实验定义参数及runId/outputDir外，同实例命令行其余参数一致。',
    '全体表时间采用正常结束项均值，正常时限按实际时间计入；异常/缺失单列，不填0秒、不把崩溃前证书当最终结果。平均gap仅在该行全部正常结果都有gap时给出，否则记为缺失并列出有效gap数，避免只平均容易项。最优包括ROOT_PROCESSED且gap=0。单节点项保留在全体表；共同最优配对表要求每个变体均至少2节点，避免无分支样本的probe波动。表中几何比小于1表示前者更快。实际config快照除输出标识/路径和三个实验开关外逐实例一致，所有共同最优配对的目标值一致。',
    '旧批次family的F为n20/n40=3、n50=4；新增n50为F3。当前数据表仅替换n50 family，不重复计入旧F4。窗口zero表示单点due，不是zero setup。',
    '## 1. 完整性及各批次总览', full_table(rows, ['dataset', 'setup', 'variant']),
    '## 2. 当前family按规模和F/m关系（全体）', full_table(current_family, ['n', 'relation', 'variant']),
    '## 3. 当前family按时间尺度（全体）', full_table(current_family, ['scale', 'variant']),
    '## 4. 当前family按窗口（全体）', full_table(current_family, ['window', 'variant']),
    '## 5. Random按时间尺度（全体）', full_table(random, ['scale', 'variant']),
    '## 6. Random按窗口（全体）', full_table(random, ['window', 'variant']),
    '## 7. 当前family共同最优、多节点配对',
    paired(current_family, ['relation'], ['F-A', 'F-B', 'F-C', 'F-D']),
    paired(current_family, ['scale'], ['F-A', 'F-B', 'F-C', 'F-D']),
    paired(current_family, ['window'], ['F-A', 'F-B', 'F-C', 'F-D']),
    '## 8. 当前family时间尺度与F/m交叉（配对）',
    paired(current_family, ['relation', 'scale'], ['F-A', 'F-B', 'F-C', 'F-D']),
    '## 9. 当前family窗口与F/m交叉（配对）',
    paired(current_family, ['relation', 'window'], ['F-A', 'F-B', 'F-C', 'F-D']),
    '## 10. Random共同最优、多节点配对',
    paired(random, ['scale'], ['R-A', 'R-B']), paired(random, ['window'], ['R-A', 'R-B']),
    '## 11. 旧n50/F4单独保留（全体）',
    full_table([r for r in family if r['n'] == 50 and r['dataset'] == 'old'], ['relation', 'variant']),
    '## 12. 至少5节点敏感性（配对）',
    paired(current_family, ['relation', 'scale'], ['F-A', 'F-B', 'F-C', 'F-D'], 5),
    paired(random, ['scale'], ['R-A', 'R-B'], 5),
    '## 13. n40/n50主要比较，排除n20的极短运行',
    '全体表仍包含n40/n50根节点最优项；配对表才要求所有对应变体至少2节点。',
    full_table(large_family, ['scale', 'variant']), full_table(large_family, ['window', 'variant']),
    paired(large_family, ['scale'], ['F-A', 'F-B', 'F-C', 'F-D']),
    paired(large_family, ['window'], ['F-A', 'F-B', 'F-C', 'F-D']),
    paired(large_family, ['relation'], ['F-A', 'F-B', 'F-C', 'F-D']),
    paired(large_family, ['relation', 'scale'], ['F-A', 'F-B', 'F-C', 'F-D']),
    full_table(large_random, ['scale', 'variant']), full_table(large_random, ['window', 'variant']),
    paired(large_random, ['scale'], ['R-A', 'R-B']), paired(large_random, ['window'], ['R-A', 'R-B']),
    '## 14. n40/n50至少5节点敏感性',
    paired(large_family, ['relation', 'scale'], ['F-A', 'F-B', 'F-C', 'F-D'], 5),
    paired(large_random, ['scale'], ['R-A', 'R-B'], 5),
]
(out / 'ng-variants-20261019-tables.md').write_text('\n\n'.join(report) + '\n', encoding='utf-8')
print('MANIFEST', len(rows), 'NORMAL', sum(r['normal'] for r in rows), 'OPTIMAL', sum(r['optimal'] for r in rows))
print('ABNORMAL', Counter((r['dataset'], r['variant'], r['status'], r['state']) for r in rows if not r['normal']))
print('CLUSTERS', Counter((r['n'], r['dataset'], r['clusterCount']) for r in family))
print('CONFIG_KEYS', list(raw[0]['config'])[:20])
print('CONTRACTS', len(contracts), 'PASS')
print(out / 'ng-variants-20261019-tables.md')
