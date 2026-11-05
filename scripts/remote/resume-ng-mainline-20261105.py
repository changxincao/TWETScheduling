"""检修后按4:2拆分n60剩余A任务；原清单/完成结果不改，失败输出原地备份。"""
import base64
import csv
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.codex-tmp/ng-resume-20261105'
KEY = 'C:/Users/Changxin/.ssh/codex_solver_auto_ed25519'
BATCH = 'work1/experiments/20261104-ng-mainline-a-prepared'
SHA = '1b00188438f7a38ddacb809ebe2f7dd736dfb5a31d734a2e0df484713a6e432a'
HOSTS = {
    'first': ('100.68.243.112', 'C:/Users/Changxin/Downloads/solver_known_hosts',
              'D:/ccx_work/考虑交付的机器调度', 'D:/software/Java/jdk-21/bin/java.exe', 4, 128),
    'second': ('100.68.243.192', 'C:/Users/Changxin/.ssh/known_hosts',
               'D:/ccx', 'D:/Java/jdk-21/bin/java.exe', 2, 64),
}


def command(program, host, *args):
    ip, known, *_ = HOSTS[host]
    return [program, '-i', KEY, '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=' + known, *args]


def run(cmd):
    return subprocess.check_output(cmd, encoding='utf-8', errors='replace', timeout=150)


def inspect(host, allow_running=False):
    ip, _, root, java, *_ = HOSTS[host]
    stage = root + '/' + BATCH
    remote = f'cmd.exe /d /c "cd /d {stage} && {java} -cp tools NgMainlineResume {stage}"'
    text = run(command('ssh', host, 'codex-runner@' + ip, remote))
    (WORK / (host + '-inspect.txt')).write_text(text, encoding='utf-8')
    meta, states = {}, {}
    for line in text.splitlines():
        parts = line.split('\t')
        if parts[0] == 'RUN':
            states[parts[1]] = {'success': parts[2] == 'true', 'state': parts[3],
                                 'inputSha': parts[4], 'seedSha': parts[5], 'markerSha': parts[6],
                                 'pid': int(parts[7]), 'cpuMs': int(parts[8]),
                                 'liveBytes': int(parts[9]), 'stderrBytes': int(parts[10])}
        else:
            meta[parts[0]] = parts[1:]
    assert meta['JAR'] == [SHA]
    assert abs(int(meta['HOST'][1]) / 2**30 - HOSTS[host][5]) < 2
    jvms = base64.b64decode(meta['JVMS_BASE64'][0]).decode('utf-8')
    if not allow_running:
        assert 'HEU.ExperimentBatchScheduler' not in jvms and 'Common.formal.FormalExperimentRunner' not in jvms
    local = WORK / (host + '-original.tsv')
    run(command('scp', host, f'codex-runner@{ip}:{stage}/solve.tsv', str(local)))
    assert hashlib.sha256(local.read_bytes()).hexdigest() == meta['SOLVE'][0]
    lines = local.read_text(encoding='utf-8-sig').splitlines()
    rows = list(csv.DictReader(lines, delimiter='\t'))
    assert set(states) == {r['runId'] for r in rows}
    for r in rows:
        assert r['size'] == '60' and r['algorithm'] == 'NG_DSSR'
    reference = ROOT / 'experiment-suite/formal/manifests/20261104-ng-mainline-a-prepared' / host / 'case-map.tsv'
    cases = {r['scenario']: r for r in csv.DictReader(reference.open(encoding='utf-8-sig'), delimiter='\t')}
    for row in rows:
        case = cases[Path(re.search(r'--instance="([^"]+)"', row['args'])[1]).stem]
        assert states[row['runId']]['inputSha'] == case['inputSha']
        assert states[row['runId']]['seedSha'] == case['seedSha']
    return meta, states, lines, rows


if '--verify' in sys.argv:
    import datetime
    plan = json.loads((WORK / 'resume-plan.json').read_text(encoding='utf-8'))
    result = {'time': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(), 'hosts': {}}
    for h, (ip, _, root, java, parallel, _) in HOSTS.items():
        meta, states, _, _ = inspect(h, allow_running=True)
        assert meta['SOLVE'] == [plan['originalManifestSha'][h]]
        for rid, sha in plan['completedMarkerHashes'][h].items():
            assert states[rid]['success'] and states[rid]['markerSha'] == sha
        jvms = base64.b64decode(meta['JVMS_BASE64'][0]).decode('utf-8')
        schedulers = [l for l in jvms.splitlines() if ' HEU.ExperimentBatchScheduler ' in l]
        solvers = [l for l in jvms.splitlines() if ' Common.formal.FormalExperimentRunner ' in l]
        assert len(schedulers) == 1 and len(solvers) == parallel
        assert f'resume-selected-20261105.tsv {parallel}' in schedulers[0]
        running = {rid: s for rid, s in states.items() if s['state'] == 'RUNNING'}
        assert len(running) == parallel and set(running) <= set(plan['assignments'][h])
        flags = {}
        for line in solvers:
            pid = int(line.split()[0])
            value = run(command('ssh', h, 'codex-runner@' + ip, java.replace('java.exe', 'jcmd.exe') + f' {pid} VM.flags'))
            assert '-XX:MaxHeapSize=25769803776' in value
            assert f'--timeLimitSeconds=10800' in line and '--algorithm=NG_DSSR' in line
            flags[pid] = value
        assert all(s['liveBytes'] > 0 and s['cpuMs'] > 0 for s in running.values())
        result['hosts'][h] = {'host': meta['HOST'][0], 'memoryGiB': int(meta['HOST'][1]) / 2**30,
            'freeGiB': int(meta['HOST'][2]) / 2**30, 'batch': meta['BATCH'][0],
            'scheduler': schedulers[0], 'solvers': solvers, 'running': running,
            'maxHeapGiB': 24, 'completedPreserved': len(plan['completedMarkerHashes'][h])}
        print(h, json.dumps(result['hosts'][h], ensure_ascii=False))
    name = 'verification-' + datetime.datetime.now().strftime('%H%M%S') + '.json'
    (WORK / name).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    sys.exit(0)

data = {h: inspect(h) for h in HOSTS}
completed = {i for _, states, _, _ in data.values() for i, s in states.items() if s['success']}
all_rows = data['second'][3]
assert len(all_rows) == 270 and len(data['first'][3]) == 266
assert len(completed) == 49
lookup = {h: {r['runId']: (r, line) for r, line in zip(v[3], v[2][1:])} for h, v in data.items()}
for rid in set(lookup['first']) & set(lookup['second']):
    a, b = lookup['first'][rid][0], lookup['second'][rid][0]
    assert a['args'].replace(HOSTS['first'][2], '${ROOT}') == b['args'].replace(HOSTS['second'][2], '${ROOT}')
    assert data['first'][1][rid]['inputSha'] == data['second'][1][rid]['inputSha']
    assert data['first'][1][rid]['seedSha'] == data['second'][1][rid]['seedSha']
pending = [r['runId'] for r in all_rows if r['runId'] not in completed]
# 优先补跑已有失败/中断，再保持原family优先顺序；轮转分配4:2，不能双机重算同场景。
pending.sort(key=lambda rid: not any(v[1].get(rid, {}).get('state') in {'FAILED', 'INTERRUPTED'} for v in data.values()))
assignments = {'first': [], 'second': []}
for i, rid in enumerate(pending):
    assignments['first' if i % 6 < 4 else 'second'].append(rid)
assert not set(assignments['first']) & set(assignments['second'])
assert set(pending) | completed == set(lookup['second'])
summary = {'scope': 'n60 NG-DSSR A only', 'completedPreserved': sorted(completed),
           'pendingTotal': len(pending), 'heapGiB': 24, 'assignments': assignments,
           'originalManifestSha': {h: data[h][0]['SOLVE'][0] for h in HOSTS},
           'completedMarkerHashes': {h: {i: s['markerSha'] for i, s in data[h][1].items() if s['success']} for h in HOSTS}}
for h, ids in assignments.items():
    (WORK / (h + '-resume-selected-20261105.tsv')).write_text(
        data[h][2][0] + '\n' + '\n'.join(lookup[h][i][1] for i in ids) + '\n', encoding='utf-8')
(WORK / 'resume-plan.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'completedPreserved': len(completed), 'remaining': len(pending),
                  'assigned': {h: len(v) for h, v in assignments.items()}}, ensure_ascii=False))
if '--apply' in sys.argv:
    for h, (_, _, root, java, parallel, memory) in HOSTS.items():
        ip = HOSTS[h][0]; stage = root + '/' + BATCH
        exists = run(command('ssh', h, 'codex-runner@' + ip,
                             f'cmd.exe /d /c if exist "{stage}/resume-selected-20261105.tsv" (echo EXISTS) else (echo NEW)'))
        assert exists.strip() == 'NEW', 'Do not overwrite an existing resume manifest'
        run(command('scp', h, str(WORK / (h + '-resume-selected-20261105.tsv')),
                    f'codex-runner@{ip}:{stage}/resume-selected-20261105.tsv'))
        run(command('scp', h, str(WORK / 'resume-plan.json'), f'codex-runner@{ip}:{stage}/resume-plan-20261105.json'))
        remote = f'cmd.exe /d /c "cd /d {stage} && {java} -cp tools NgMainlineResume {stage} prepare {parallel} {memory} {data[h][0]["SOLVE"][0]}"'
        result = run(command('ssh', h, 'codex-runner@' + ip, remote))
        (WORK / (h + '-prepare.txt')).write_text(result, encoding='utf-8')
        print(h, result[result.rfind('host='):])
