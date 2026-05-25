package HEU;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import Basic.Data;
import Common.Utility;

/**
 * 2026-05-26: 离线计算 bidirectional pricing 的 midpoint workload 指标。
 * 只用于诊断候选 tau，不接入正式 pricing 流程。
 */
public class WorkloadMidpointDiagnostic {

	public static void main(String[] args) throws Exception {
		Path instanceDir = Path.of(System.getProperty("twet.bpc.midpoint.dir",
				"test-results/bpc/2026-05-23-no-outsourcing-15"));
		String resultStem = System.getProperty("twet.bpc.midpoint.name", "2026-05-26-workload-midpoint");
		Path csv = Path.of("test-results", "bpc", resultStem + ".csv");
		Files.createDirectories(csv.getParent());

		List<Path> instances = listInstances(instanceDir);
		if (instances.isEmpty()) {
			throw new IllegalStateException("No .dat instance found under " + instanceDir);
		}

		ArrayList<String> lines = new ArrayList<String>();
		lines.add("case,n,m,H,ratio,tau,WF,WB,log_imbalance,best");
		for (Path instance : instances) {
			Data data = TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine(instance.toString(), false);
			double horizon = staticPricingHorizon(data);
			Result best = null;
			ArrayList<Result> results = new ArrayList<Result>();
			for (int ratioInt = 20; ratioInt <= 80; ratioInt += 5) {
				Result result = evaluate(data, horizon, ratioInt / 100.0);
				results.add(result);
				if (best == null || Utility.compareLt(result.logImbalance, best.logImbalance)) {
					best = result;
				}
			}
			for (Result result : results) {
				lines.add(toCsv(instance, data, horizon, result, result == best));
			}
			System.out.printf(Locale.US, "%s H=%.6f bestRatio=%.2f tau=%.6f WF=%d WB=%d logImbalance=%.6f%n",
					instance.getFileName(), horizon, best.ratio, best.tau, best.forwardWorkload,
					best.backwardWorkload, best.logImbalance);
		}
		Files.write(csv, lines);
		System.out.println("WorkloadMidpointDiagnostic csv=" + csv);
	}

	private static List<Path> listInstances(Path instanceDir) throws IOException {
		ArrayList<Path> instances = new ArrayList<Path>();
		try (var stream = Files.newDirectoryStream(instanceDir, "*.dat")) {
			for (Path path : stream) {
				instances.add(path);
			}
		}
		instances.sort(Comparator.comparing(path -> path.getFileName().toString()));
		return instances;
	}

	private static double staticPricingHorizon(Data data) {
		double horizon = 0.0;
		boolean found = false;
		for (int job = 1; job <= data.n; job++) {
			if (!Utility.compareGt(data.hardWindowStart[job], data.hardWindowEnd[job])
					&& Double.isFinite(data.hardWindowEnd[job])) {
				horizon = Math.max(horizon, data.hardWindowEnd[job]);
				found = true;
			}
		}
		return found ? Math.min(data.CmaxH, horizon) : data.CmaxH;
	}

	private static Result evaluate(Data data, double horizon, double ratio) {
		double tau = ratio * horizon;
		long forward = 0L;
		long backward = 0L;
		int sink = data.n + 1;
		for (int i = 0; i <= data.n; i++) {
			double hMinusI = i == 0 ? 0.0 : data.hardWindowStart[i];
			for (int j = 1; j <= data.n; j++) {
				if (i == j || data.isPreprocessedArcForbidden(i, j)) {
					continue;
				}
				double earliestJ = hMinusI + data.getSetUp(i, j) + data.getProcessT(j);
				if (!Utility.compareGt(earliestJ, Math.min(data.hardWindowEnd[j], tau))) {
					forward++;
				}
			}
		}
		for (int j = 1; j <= data.n; j++) {
			for (int k = 1; k <= sink; k++) {
				if (j == k || data.isPreprocessedArcForbidden(j, k)) {
					continue;
				}
				double hPlusK = k == sink ? horizon : data.hardWindowEnd[k];
				double processK = k == sink ? 0.0 : data.getProcessT(k);
				double earliestK = Math.max(data.hardWindowStart[j], tau) + data.getSetUp(j, k) + processK;
				if (!Utility.compareGt(earliestK, hPlusK)) {
					backward++;
				}
			}
		}
		double logImbalance = Math.abs(Math.log((forward + 1.0) / (backward + 1.0)));
		return new Result(ratio, tau, forward, backward, logImbalance);
	}

	private static String toCsv(Path instance, Data data, double horizon, Result result, boolean best) {
		return String.format(Locale.US, "%s,%d,%d,%.6f,%.2f,%.6f,%d,%d,%.9f,%s",
				instance.getFileName().toString().replace(".dat", ""), data.n, data.m, horizon, result.ratio,
				result.tau, result.forwardWorkload, result.backwardWorkload, result.logImbalance,
				Boolean.toString(best));
	}

	private static final class Result {
		final double ratio;
		final double tau;
		final long forwardWorkload;
		final long backwardWorkload;
		final double logImbalance;

		Result(double ratio, double tau, long forwardWorkload, long backwardWorkload, double logImbalance) {
			this.ratio = ratio;
			this.tau = tau;
			this.forwardWorkload = forwardWorkload;
			this.backwardWorkload = backwardWorkload;
			this.logImbalance = logImbalance;
		}
	}
}
