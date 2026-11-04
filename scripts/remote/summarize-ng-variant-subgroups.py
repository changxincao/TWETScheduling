"""逐细分组汇总已有NG正式结果，不连接远端或运行求解器。"""
import csv
import hashlib
import json
import math
import statistics as st
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'docs/logs/data/ng-variants-20261019-rows.csv'
RAW = ROOT / '.codex-tmp/ng-variants-20261019.json'
DEST = ROOT / 'docs/logs/data/20261104-ng-variants-full-breakdown'


def average(values):
    return st.mean(values) if values else None


def fmt(value, digits=2):
    return '-' if value is None else f'{value:.{digits}f}'


def main():
    with SOURCE.open(encoding='utf-8-sig', newline='') as handle:
        rows = list(csv.DictReader(handle))
    assert len(rows) == 2970
    raw = {(r['batch'], r['runId']): r for r in json.loads(RAW.read_text(encoding='utf-8'))}
    assert len(raw) == len(rows)
    groups = defaultdict(list)
    for row in rows:
        original = raw[(row['batch'], row['runId'])]
        for key in ('n', 'm'):
            row[key] = int(row[key])
        for key in ('seconds', 'nodes', 'gap', 'ub', 'lb'):
            row[key] = float(row[key]) if row[key] else None
        for key in ('normal', 'optimal'):
            row[key] = row[key] == 'True'
        summary = original['summary'] or {}
        for key, source_key in [('seconds', 'solveTimeSeconds'), ('nodes', 'processedNodes'), ('gap', 'gapPercent')]:
            value = summary.get(source_key)
            value = float(value) if value not in (None, '') else None
            value = value if value is None or math.isfinite(value) else None
            assert row[key] == value, (row['runId'], key)
        assert row['normal'] == bool(original['success'] and row['seconds'] is not None and row['seconds'] > 0)
        assert row['optimal'] == bool(row['normal'] and row['status'] in ('FINISHED', 'ROOT_PROCESSED')
                                      and row['gap'] is not None and abs(row['gap']) <= 1e-5)
        historical = row['setup'] == 'family' and row['n'] == 50 and row['dataset'] == 'old'
        row['scope'] = 'historical-n50-F4' if historical else 'current'
        key = (row['scope'], row['n'], row['setup'], row['F'], row['m'], row['relation'], row['scale'], row['window'])
        groups[key].append(row)
    aggregates = []
    records = {}
    for key, members in groups.items():
        variants = ['F-A', 'F-B', 'F-C', 'F-D'] if key[2] == 'family' else ['R-A', 'R-B']
        cases = defaultdict(dict)
        for row in members:
            assert row['variant'] not in cases[row['taskSet']]
            cases[row['taskSet']][row['variant']] = row
        assert len(cases) == 5 and all(set(case) == set(variants) for case in cases.values())
        common = [case for case in cases.values() if all(r['optimal'] for r in case.values())]
        multi = [case for case in common if all(r['nodes'] >= 2 for r in case.values())]
        for variant in variants:
            selected = [r for r in members if r['variant'] == variant]
            normal = [r for r in selected if r['normal']]
            known_gap = [r['gap'] for r in normal if r['gap'] is not None]
            record = dict(zip(('scope', 'n', 'setup', 'F', 'm', 'relation', 'scale', 'window'), key))
            record.update(variant=variant, planned=5, normal=len(normal), optimal=sum(r['optimal'] for r in normal),
                          time_limit=sum(r['status'] == 'TIME_LIMIT' for r in normal), failed_or_missing=5-len(normal),
                          mean_seconds=average([r['seconds'] for r in normal]),
                          median_seconds=st.median([r['seconds'] for r in normal]) if normal else None,
                          mean_nodes=average([r['nodes'] for r in normal]),
                          mean_gap_percent=average(known_gap) if len(known_gap) == len(normal) else None,
                          valid_gap_count=len(known_gap), missing_gap_count=len(normal)-len(known_gap),
                          nodes_one_count=sum(r['nodes'] == 1 for r in normal),
                          common_optimal_count=len(common),
                          common_optimal_mean_seconds=average([c[variant]['seconds'] for c in common]),
                          common_optimal_mean_nodes=average([c[variant]['nodes'] for c in common]),
                          common_optimal_multinode_count=len(multi),
                          common_multinode_mean_seconds=average([c[variant]['seconds'] for c in multi]))
            first = 'F-B' if key[2] == 'family' else 'R-B'
            second = 'F-A' if key[2] == 'family' else 'R-A'
            pairs = {'root_only_geom_ratio': (first, second)}
            if key[2] == 'family':
                pairs['cutset_geom_ratio'] = ('F-D', 'F-B')
            for field in ('root_only_geom_ratio', 'cutset_geom_ratio'):
                pair = pairs.get(field)
                record[field] = math.exp(st.mean(math.log(c[pair[0]]['seconds']/c[pair[1]]['seconds'])
                                                for c in multi)) if multi and pair else None
            records[(key, variant)] = record
            aggregates.append(record)
    scale_order = {'base': 0, 'medium': 1, 'high': 2}
    window_order = {'zero': 0, 'narrow': 1, 'wide': 2}
    group_order = lambda k: (k[0], k[2], k[1], k[4], scale_order[k[6]], window_order[k[7]])
    aggregates.sort(key=lambda r: (r['scope'], r['setup'], r['n'], r['m'], scale_order[r['scale']], window_order[r['window']], r['variant']))
    assert len(groups) == 189 and len(aggregates) == 594
    assert sum(r['scope'] == 'current' for r in aggregates) == 486
    assert sum(r['planned'] for r in aggregates) == 2970
    assert sum(r['normal'] for r in aggregates) == 2958
    assert sum(r['failed_or_missing'] for r in aggregates) == 12
    with DEST.with_suffix('.csv').open('w', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=list(aggregates[0]))
        writer.writeheader()
        writer.writerows(aggregates)
    report = [
        '# NG-DSSR全部细分组统计（2026-11-04）',
        '本表从2026-10-19第二机逐项快照重新汇总，没有新求解。主表使用当前n20/n40/n50数据；family均F3，旧n50/F4只列附表。A/B为Cluster→Arc加全节点/root-only scalar fixing，C/D为Cluster→supernode CutSet→Arc加全节点/root-only；random只比较Arc下A/B。全部为同一冻结v8，不混入旧v5/v6。n60/n80没有此A--D全交叉结果，不补造数据。',
        '细分维度为规模、setup类型、family数、机器数及F/m关系、时间尺度和窗口宽度。每细分组设计5个set。表内单元依次为【平均总时间秒 / 平均节点 / 最优数(正常数) / 平均gap%】。正常数少于5即有失败，失败不填0或时限；TIME_LIMIT按实际时间计入。gap只在该组所有正常项均有证书数值时给平均，否则写缺失及有效gap数/正常数。zero是单点due window，不是zero setup。',
        '下方为便于横向比较的紧凑表。CSV逐行保留计划/正常/最优/超时/失败数、时间均值/中位数、节点、gap完整性、根节点运行数、共同最优数及其平均时间/节点、共同最优多节点配对的root-only/CutSet几何比。配对仅针对当前表参与的全部变体；family为四臂共同最优，random为两臂；因此不同setup的配对样本不能混称同一集合。单个细分组最多5例，不能凭少量几何比直接确定通用配置。',
        'CSV: `20261104-ng-variants-full-breakdown.csv`。输入CSV SHA-256: `' + hashlib.sha256(SOURCE.read_bytes()).hexdigest() + '`。原始JSON已逐项复核time/node/gap及正常/最优判定，189个细分组、594条变体统计覆盖2970次原运行；当前主表486条，旧F4附表108条，12次失败不计正常均值。',
    ]
    last_section = None
    for key in sorted(groups, key=group_order):
        scope, n, setup, family, machines, relation, scale, window = key
        section = (scope, setup, n, machines)
        variants = ['F-A', 'F-B', 'F-C', 'F-D'] if setup == 'family' else ['R-A', 'R-B']
        if section != last_section:
            title = f'{"历史附表" if scope != "current" else "当前主表"}：n{n} / {setup} / m{machines}'
            if setup == 'family':
                title += f' / F{family}（{relation}）'
            report += ['## ' + title, '| 时间尺度/窗口 | ' + ' | '.join(variants) + ' |',
                       '| --- | ' + ' | '.join(['---:'] * len(variants)) + ' |']
            last_section = section
        cells = []
        for variant in variants:
            r = records[(key, variant)]
            gap = fmt(r['mean_gap_percent'], 4) if r['mean_gap_percent'] is not None else f'缺失({r["valid_gap_count"]}/{r["normal"]})'
            cells.append(f'{fmt(r["mean_seconds"])} / {fmt(r["mean_nodes"])} / {r["optimal"]}({r["normal"]}) / {gap}')
        report.append('| ' + scale + '/' + window + ' | ' + ' | '.join(cells) + ' |')
    rendered = []
    for entry in report:
        if entry.startswith('|'):
            rendered.append(entry)
        else:
            if rendered and rendered[-1] != '':
                rendered.append('')
            rendered.extend([entry, ''])
    DEST.with_suffix('.md').write_text('\n'.join(rendered).lstrip() + '\n', encoding='utf-8')
    # 对话正文的n40/n50 family表；其余分类全部保留于完整报告。
    print('| n/m | 尺度/窗口 | A | B | C | D |')
    print('| --- | --- | ---: | ---: | ---: | ---: |')
    for key in sorted(groups, key=group_order):
        if key[0] != 'current' or key[2] != 'family' or key[1] not in (40, 50):
            continue
        cells = []
        for variant in ('F-A', 'F-B', 'F-C', 'F-D'):
            r = records[(key, variant)]
            gap = fmt(r['mean_gap_percent'], 3) if r['mean_gap_percent'] is not None else f'?{r["valid_gap_count"]}/{r["normal"]}'
            cells.append(f'{fmt(r["mean_seconds"],1)}/{fmt(r["mean_nodes"],1)}/{r["optimal"]}({r["normal"]})/{gap}')
        print(f'| {key[1]}/{key[4]} | {key[6]}/{key[7]} | ' + ' | '.join(cells) + ' |')
    print('PASS: groups=189 aggregate_rows=594 current=486 historical=108 normal=2958 failures=12')


if __name__ == '__main__':
    main()
