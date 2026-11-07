"""在隔离目录原样复跑旧反例；旧源码/输入路径保持不变，日志另存。"""
from pathlib import Path
import json
import re
import shutil
import subprocess

from run_regressions import ROOT, OUT, JAVA, JARS, NATIVE


def main():
    replay = OUT / 'replay'
    fixture = replay / '.codex-tmp/ng-second-review-20261105'
    fixture.mkdir(parents=True, exist_ok=True)
    old = ROOT / 'docs/logs/data/20261105-ng-second-review/ng-second-review-20261105'
    shutil.copy2(old / 'input-8.dat', fixture / 'input-8.dat')
    rows = []
    for version in ['before', 'after']:
        classes = [str(OUT / 'classes')]
        if version == 'before':
            classes.insert(0, str(OUT / 'before-classes'))
        for test, mode in [('EndpointMini', True), ('EndpointProbe', True), ('EndpointProbe', False)]:
            command = [str(JAVA), '-Xmx2g', f'-Djava.library.path={NATIVE}',
                       f'-Dtwet.bpc.completionBoundNativeIntervalDelta={str(mode).lower()}',
                       '-cp', ';'.join([*classes, *JARS]), f'TWETBPC.GC.{test}']
            name = f'{version}-{test}-native-{mode}'
            result = subprocess.run(command, cwd=replay, capture_output=True, text=True,
                                    encoding='utf-8', errors='replace', timeout=120)
            (OUT / f'{name}.log').write_text(result.stdout + result.stderr, encoding='utf-8')
            signals = [line.split(' GCNGBB-style', 1)[0] for line in result.stdout.splitlines()
                       if line.startswith(('seq=', 'phaseOne=', 'PRICE ', 'FIRST_DP_MISMATCH', 'FMIN '))]
            row = {'version': version, 'test': test, 'native': mode, 'exit': result.returncode,
                   'signals': signals}
            rows.append(row)
            print(json.dumps(row, ensure_ascii=False), flush=True)
            if result.returncode:
                raise RuntimeError(name + ' failed; see log')
            if version == 'after' and test == 'EndpointMini':
                if not any('expected=2 evaluator=2.0' in line for line in signals):
                    raise AssertionError(name + ': evaluator did not recover true cost 2')
                prices = [line for line in signals if line.startswith('phaseOne=')]
                if len(prices) != 4 or any('columns=0 ' in line or 'cert=-1.0' not in line for line in prices):
                    raise AssertionError(name + ': two-job exact pricing did not recover negative route')
            if version == 'after' and test == 'EndpointProbe':
                if any(line.startswith('FIRST_DP_MISMATCH') for line in signals):
                    raise AssertionError(name + ': completion bound exceeds independent DP')
                prices = [line for line in signals if line.startswith('PRICE ')]
                if len(prices) != 4 or any('count=0 ' in line or 'certificate=-38.0' not in line for line in prices):
                    raise AssertionError(name + ': missing negative pricing column')
                for line in signals:
                    match = re.search(r'cb=([-+\d.eE]+) dp=([-+\d.eE]+)', line)
                    if match and abs(float(match[1]) - float(match[2])) > 1e-5:
                        raise AssertionError(name + ': FMin mismatch: ' + line)
    (OUT / 'historical-replay.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
