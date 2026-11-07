"""本地端点补丁回归；所有运行输出与旧结果隔离，不连接远端。"""
from pathlib import Path
import csv
import json
import subprocess
import time

ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / '.codex-tmp/endpoint-patches-20261107'
JAVA = Path('D:/软件/Java/jdk_22/bin/java.exe')
JARS = ['D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar',
        'D:/软件/cplex/ILOG/CPLEX_Studio2211/cpoptimizer/lib/ILOG.CP.jar']
NATIVE = 'D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64;D:/软件/cplex/ILOG/CPLEX_Studio2211/cpoptimizer/bin/x64_win64'
NAMES = '''PiecewiseLinearEndpointPatchTest NgTracedJoinPhaseOneEndpointRegressionTest
CompletionBoundPointReceptionTest PiecewiseLinearMinimalSumViewTest PiecewiseLinearInsertScalarTest
NgHistoricalEndpointPricingTest
CompletionBoundPreparedBoundsCompatibilityTest NgDssrJoinExtensionBoundaryTest
NgDssrJoinPrefilterCacheTest PaperDominanceGraphConsistencyTest
IncrementalSourcedDominanceGraphConsistencyTest StrongBranchingPhaseOnePricingTest
AfterCutPhaseOneRepairTest PhaseOneRepairTimeoutTest FiniteMRepairConfirmationTest
LPRestrictedColumnMembershipTest CertifiedNodeBoundTrackingTest TreeFinalBoundTest
BestBpcProfilesTest StructuredArcFlowBranchingTest SuccessorSetBranchingTest
TWETBPCContextEffectiveNgDssrConfigurationTest NgDssrInitialNgSetSizeTest
NgDssrMinimumRepeatedSegmentUpdateTest NgDssrHeartbeatDiagnosticsTest
NgDssrFirstRoundMidpointHistoryTest NgDssrMidpointProbePolicyTest
NgDssrMidpointProbeConfigurationTest LabelQueueOrderingFallbackTest
TimeIndexedGraphOptimizationTest'''.split()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sources = {p.stem: p for p in (ROOT / 'src').rglob('*Test.java') if p.stem in NAMES}
    missing = set(NAMES) - sources.keys()
    if missing:
        raise RuntimeError(f'missing tests: {missing}')
    cp = ';'.join([str(OUT / 'classes'), *JARS])
    command = [str(JAVA.with_name('javac.exe')), '-J-Duser.language=en', '-Xprefer:source',
               '-encoding', 'UTF-8', '-sourcepath', 'src', '-d', str(OUT / 'classes'),
               '-cp', cp, 'src/HEU/Move.java', *[str(sources[n]) for n in NAMES]]
    with (OUT / 'compile.log').open('w', encoding='utf-8') as log:
        subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
    rows = []
    for name in NAMES:
        source = sources[name]
        cls = '.'.join(source.relative_to(ROOT / 'src').with_suffix('').parts)
        command = [str(JAVA), '-Xmx2g', f'-Djava.library.path={NATIVE}',
                   '-Dtwet.pwlf.minSumView.randomCases=500000',
                   '-Dtwet.pwlf.scalarInsert.randomCases=2000',
                   '-Dtwet.pwlf.scalarInsert.benchmarkIterations=20000', '-cp', cp, cls]
        start = time.monotonic()
        with (OUT / f'{name}.log').open('w', encoding='utf-8') as log:
            try:
                status = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                        timeout=150).returncode
            except subprocess.TimeoutExpired:
                status = 'TIMEOUT'
        row = {'test': name, 'status': status, 'seconds': round(time.monotonic() - start, 3)}
        rows.append(row)
        print(row, flush=True)
        with (OUT / 'regressions.csv').open('w', newline='', encoding='utf-8') as log:
            writer = csv.DictWriter(log, fieldnames=['test', 'status', 'seconds'])
            writer.writeheader()
            writer.writerows(rows)
    (OUT / 'regressions.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')
    raise SystemExit(1 if any(row['status'] != 0 for row in rows) else 0)


if __name__ == '__main__':
    main()
