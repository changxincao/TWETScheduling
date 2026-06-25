package TWETBPC.LP;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

import Basic.Data;
import Common.Utility;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETOutsourcingColumn;
import TWETBPC.Util.SequenceSignature;

/**
 * SP1 外包列池。
 * <p>
 * 和内部机器列池分开维护，避免把“机器序列列”和“外包集合列”的语义混在同一个 Pool 中。
 */
public class OutsourcingPool {

	private final Data data;
	private final ArrayList<TWETOutsourcingColumn> columns;
	private final HashMap<SequenceSignature, Integer> signatureToId;

	public OutsourcingPool(Data data) {
		this.data = data;
		this.columns = new ArrayList<TWETOutsourcingColumn>();
		this.signatureToId = new HashMap<SequenceSignature, Integer>();
	}

	public int addColumn(List<Integer> jobs, ColumnSource source, boolean seedColumn) {
		ArrayList<Integer> normalized = normalizeJobs(jobs);
		if (normalized.isEmpty()) {
			return -1;
		}
		SequenceSignature signature = new SequenceSignature(normalized);
		Integer existing = signatureToId.get(signature);
		if (existing != null) {
			return existing.intValue();
		}
		double baseline = baseline(normalized);
		double cost = data.evaluateOutsourcingCost(baseline);
		int id = columns.size();
		TWETOutsourcingColumn column = new TWETOutsourcingColumn(id, normalized, data.n, baseline, cost, source,
				seedColumn);
		columns.add(column);
		signatureToId.put(column.getSignature(), Integer.valueOf(id));
		return id;
	}

	public int addColumn(TWETOutsourcingColumn column) {
		SequenceSignature signature = column.getSignature();
		Integer existing = signatureToId.get(signature);
		if (existing != null) {
			return existing.intValue();
		}
		int id = columns.size();
		// 2026-06-25: pricing / route enumeration 已经按同一 jobs 集合算出 baseline 与 tariff cost，
		// 这里只分配全局 id，不再重新扫描 job 和重算 G(baseline)。
		TWETOutsourcingColumn stored = new TWETOutsourcingColumn(id, column.getJobs(), data.n, column.getBaseline(),
				column.getCost(), column.getSource(), column.isSeedColumn());
		columns.add(stored);
		signatureToId.put(signature, Integer.valueOf(id));
		return id;
	}

	/** 2026-06-24: route enumeration 用于区分外包列是否已经在全局池中存在。 */
	public int getColumnIdBySignature(SequenceSignature signature) {
		Integer existing = signatureToId.get(signature);
		return existing == null ? -1 : existing.intValue();
	}

	public boolean isOutsourceable(int job) {
		return job >= 1 && job <= data.n && !Utility.isBigMValue(data.outsourcingCost[job]);
	}

	public TWETOutsourcingColumn getColumn(int id) {
		return columns.get(id);
	}

	public int size() {
		return columns.size();
	}

	public List<TWETOutsourcingColumn> getColumns() {
		return columns;
	}

	private ArrayList<Integer> normalizeJobs(List<Integer> jobs) {
		LinkedHashSet<Integer> unique = new LinkedHashSet<Integer>();
		for (int job : jobs) {
			if (!isOutsourceable(job)) {
				throw new IllegalArgumentException("Non-outsourceable job " + job + " in outsourcing column");
			}
			unique.add(Integer.valueOf(job));
		}
		ArrayList<Integer> normalized = new ArrayList<Integer>(unique);
		java.util.Collections.sort(normalized);
		return normalized;
	}

	private double baseline(List<Integer> jobs) {
		double total = 0.0;
		for (int job : jobs) {
			total += data.outsourcingCost[job];
		}
		return total;
	}
}
