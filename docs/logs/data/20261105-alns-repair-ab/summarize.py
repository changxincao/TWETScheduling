import csv
import json
from pathlib import Path
import statistics
import subprocess

root = Path.cwd()
here = root / '.codex-tmp/alns-ab-20261105'
out = here / 'results'
rows = json.loads((out/'results.json').read_text(encoding='utf-8'))
manifest = json.loads((out/'manifest.json').read_text(encoding='utf-8'))
assert len(rows) == 24 and all(r['status'] == 'FINISHED' and r['same_initial'] for r in rows)
cp = ';'.join([str(here/'regression-classes'), str(here/'after-classes'),
               'D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar'])
oracle = []
for row in rows:
    path = next(i['path'] for i in manifest['inputs'] if i['case'] == row['case'])
    log = out / f"{row['case']}-{row['seed']}-{row['version']}.log"
    final = next(l[6:] for l in log.read_text(encoding='utf-8', errors='replace').splitlines() if l.startswith('FINAL '))
    routes = json.loads(final)
    encoded = '/'.join(','.join(map(str, route)) for route in routes)
    result = subprocess.run(['D:/软件/Java/jdk_22/bin/java.exe', '-Xmx512m', '-cp', cp,
                             'HEU.ALNSResultOracle', path, encoded, row['final']],
                            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
    assert result.returncode == 0, result.stdout + result.stderr
    oracle.append(dict(case=row['case'], seed=row['seed'], version=row['version'], result=result.stdout.strip()))
(out/'oracle.json').write_text(json.dumps(oracle, ensure_ascii=False, indent=2), encoding='utf-8')
summary = []
for item in manifest['inputs']:
    case = item['case']
    selected = [r for r in rows if r['case'] == case]
    line = dict(case=case)
    for version in ('before','after'):
        group = [r for r in selected if r['version'] == version]
        assert len(group) == 2
        for metric in ('final','seconds','cpu','rounds'):
            line[version+'_'+metric] = statistics.mean(float(r[metric]) for r in group)
    line['cost_reduction_percent'] = 100*(line['before_final']-line['after_final'])/line['before_final']
    line['wins'] = line['ties'] = line['losses'] = 0
    for seed in manifest['seeds']:
        b = next(r for r in selected if int(r['seed']) == seed and r['version'] == 'before')
        a = next(r for r in selected if int(r['seed']) == seed and r['version'] == 'after')
        delta = float(a['final'])-float(b['final'])
        line['ties' if abs(delta) < 1e-5 else ('wins' if delta < 0 else 'losses')] += 1
    summary.append(line)
with (out/'summary.csv').open('w', encoding='utf-8-sig', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(summary[0]))
    w.writeheader()
    w.writerows(summary)
print(json.dumps(summary, ensure_ascii=False, indent=2))
print('INDEPENDENT_ORACLE_OK', len(oracle))
