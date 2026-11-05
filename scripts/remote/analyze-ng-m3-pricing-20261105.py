"""只读拆分固定9项F=m的NG定价，并读取对应TI已完成结果的组件计时。"""
import base64
import csv
import io
import json
import re
import subprocess
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.codex-tmp/ng-m3-pricing-20261105'
WORK.mkdir(exist_ok=True)
PLAN = json.loads((ROOT / 'docs/logs/data/20261105-ng-resume-plan.json').read_text(encoding='utf-8'))
IDS = [rid for rid in PLAN['completedPreserved'] if '-m3-' in rid]
assert len(IDS) == 9 and all('-set01-' in rid for rid in IDS)
HOSTS = {
    'ng': ('100.68.243.112', 'C:/Users/Changxin/Downloads/solver_known_hosts'),
    'ti': ('100.79.236.222', 'C:/Users/Changxin/.ssh/known_hosts'),
}


def fetch(host, pairs):
    ip, known = HOSTS[host]
    commands = [f'get "{source}" "{target.as_posix()}"' for source, target in pairs if not target.exists()]
    if not commands:
        return
    file = WORK / (host + '-read.sftp')
    file.write_text('\n'.join(commands) + '\n', encoding='utf-8')
    cmd = ['sftp', '-b', str(file), '-i', 'C:/Users/Changxin/.ssh/codex_solver_auto_ed25519',
           '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
           '-o', 'UserKnownHostsFile=' + known, '-o', 'ConnectTimeout=12', 'codex-runner@' + ip]
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    (WORK / (host + '-fetch.log')).write_bytes(p.stdout)
    if p.returncode:
        print(host, 'FETCH_FAILED', p.stdout.decode('utf-8', errors='replace')[-1200:])


pairs = []
for rid in IDS:
    run = f'D:/ccx_work/考虑交付的机器调度/work1/experiments/20261104-ng-mainline-a-prepared/runs/{rid}'
    for suffix in ['components.csv', 'nodes.csv', 'config.properties']:
        pairs.append((f'{run}/ngDssr/{rid}.{suffix}', WORK / f'{rid}.{suffix}'))
    pairs += [(f'{run}/live.log', WORK / f'{rid}.live.log'),
              (f'{run}/stdout.log', WORK / f'{rid}.stdout.log')]
fetch('ng', pairs)

cached = list(csv.DictReader((ROOT / '.codex-tmp/ng-mainline-a-20261104-prepared/third/audit-rows.tsv')
                            .open(encoding='utf-8-sig'), delimiter='\t'))
ti_rows = [r for r in cached if r['size'] == '60' and r['machines'] == '3'
           and r['taskSetId'] == 'n060-set01' and r['setupType'] == 'family']
assert len(ti_rows) == 18 and all(r['success'] == 'true' for r in ti_rows)
pairs = []
for r in ti_rows:
    core = r['corefile'].replace('\\', '/')
    for suffix in ['components.csv', 'nodes.csv']:
        pairs.append((core.replace('core-summary.csv', suffix), WORK / f'{r["runId"]}.{suffix}'))
fetch('ti', pairs)
print('DOWNLOADED', len(list(WORK.iterdir())), 'files')

def component_rows(rid):
    return list(csv.DictReader((WORK / (rid + '.components.csv')).open(encoding='utf-8-sig')))


def component(data, category, name, field='totalSeconds'):
    return float(next(r[field] for r in data if r['category'] == category and r['component'] == name))


def values(line, prefix):
    m = re.search(re.escape(prefix) + r'([0-9.]+(?:/[0-9.]+)*)', line)
    return [float(x) for x in m[1].split('/')] if m else None


results, calls = [], []
paired = list(csv.DictReader((ROOT / 'docs/logs/data/20261105-ng49-ti-paired.csv').open(encoding='utf-8-sig')))
for rid in IDS:
    summary = next(r for r in paired if r['runId'] == rid)
    data = component_rows(rid)
    lines = (WORK / (rid + '.live.log')).read_text(encoding='utf-8', errors='replace').splitlines()
    exact = [l for l in lines if l.startswith('Pricing[GCNGBBStyleNgDssrPricing]')]
    phases = [0.] * 8
    init = [0.] * 10
    rounds = []
    seen = set()
    for line in exact:
        call_id = re.search(r'\| ng-DSSR .*?pricingCall=(\d+)', line)
        assert call_id and call_id[1] not in seen
        seen.add(call_id[1])
        count = int(re.search(r'\| ng-DSSR .*?rounds=(\d+)', line)[1])
        time = float(re.search(r' time=([0-9.]+) ms', line)[1]) / 1000
        p = values(line, 'exactPhaseMs total/init/sink/fw/bw/compact/join/finalize=')
        d = values(line, 'exactInitDetailMs setup/diag/sri/window/ng/cb/preCert/probe/state/fullProbe=')
        prefilter = values(line, 'joinEnvelopePrefilter groups/pruned/skippedPairs/funcEval/buildMs/joinMs=')
        if p:
            phases = [a+b/1000 for a,b in zip(phases,p)]
            init = [a+b/1000 for a,b in zip(init,d)]
        rounds.append(count)
        calls.append({'runId': rid, 'pricingCall': int(call_id[1]), 'rounds': count,
                      'seconds': time, 'secondsPerRound': time/count,
                      'phaseCovered': p is not None, 'joinSeconds': p[6]/1000 if p else None,
                      'lastRoundPrefilterPairs': int(prefilter[0]) if prefilter else None,
                      'lastRoundPrefilterJoinSeconds': prefilter[5]/1000 if prefilter else None,
                      'repair': 'repairPricing=true' in line})
    exact_calls = int(component(data, 'pricing', 'GCNGBBStyleNgDssrPricing', 'calls'))
    assert len(exact) == exact_calls
    stats = {'runId': rid, 'scale': summary['scale'], 'window': summary['window'],
             'totalSeconds': float(summary['seconds']), 'nodes': int(float(summary['nodes'])),
             'pricingSeconds': component(data, 'pricing', 'TOTAL'),
             'exactSeconds': component(data, 'pricing', 'GCNGBBStyleNgDssrPricing'),
             'exactCalls': exact_calls, 'dssrRounds': sum(rounds),
             'roundsPerCall': statistics.mean(rounds), 'roundsMedian': statistics.median(rounds),
             'roundsMax': max(rounds), 'phaseSeconds': phases, 'initDetailSeconds': init,
             'masterSeconds': component(data, 'masterLp', 'TOTAL'),
             'masterBuildSeconds': component(data, 'masterLpBuild', 'TOTAL'),
             'gcEvidenceAvailable': False}
    stats['exactMeanSeconds'] = stats['exactSeconds'] / exact_calls
    stats['relaxedRoundMeanSeconds'] = stats['exactSeconds'] / sum(rounds)
    for algorithm, suffix in [('TI', 'time_indexed'), ('TI+SRI', 'time_indexed_sri')]:
        row = next(r for r in ti_rows if r['scaleLevel'] == summary['scale']
                   and r['windowLevel'] == summary['window']
                   and r['algorithm'] == ('TIME_INDEXED' if algorithm == 'TI' else 'TIME_INDEXED_SRI'))
        td = component_rows(row['runId'])
        ss = next(r for r in paired if r['runId'] == row['runId'])
        main = 'TimeIndexedGraphPricing' if algorithm == 'TI' else 'TimeIndexedGraphRank1CutPricing'
        stats[algorithm] = {'totalSeconds': float(ss['seconds']), 'nodes': int(float(ss['nodes'])),
            'pricingSeconds': component(td, 'pricing', 'TOTAL'),
            'pricingCalls': int(component(td, 'pricing', 'TOTAL', 'calls')),
            'exactSeconds': component(td, 'pricing', main),
            'exactCalls': int(component(td, 'pricing', main, 'calls')),
            'masterSeconds': component(td, 'masterLp', 'TOTAL'),
            'masterBuildSeconds': component(td, 'masterLpBuild', 'TOTAL')}
    results.append(stats)

output = ROOT / 'docs/logs/data'
(output / '20261105-ng-m3-pricing-breakdown.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
with (output / '20261105-ng-m3-pricing-calls.csv').open('w', encoding='utf-8-sig', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=list(calls[0])); writer.writeheader(); writer.writerows(calls)
for r in results:
    print(r['scale'], r['window'], 'total',round(r['totalSeconds'],1),'exact',round(r['exactSeconds'],1),
          'calls',r['exactCalls'],'rounds',r['dssrRounds'],'round/call',round(r['roundsPerCall'],1),
          'max',r['roundsMax'],'s/round',round(r['relaxedRoundMeanSeconds'],2),
          'F/B/probe/join',*[round(x,1) for x in [r['phaseSeconds'][3],r['phaseSeconds'][4],r['initDetailSeconds'][7],r['phaseSeconds'][6]]],
          'TI',r['TI'],'TISRI',r['TI+SRI'])
