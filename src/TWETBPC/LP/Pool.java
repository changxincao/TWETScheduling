package TWETBPC.LP;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import Basic.Data;
import TWETBPC.Model.ColumnSource;
import TWETBPC.Model.TWETColumn;
import TWETBPC.Util.SequenceSignature;

/**
 * 全局列池。
 * <p>
 * 这层是旧 BPC 中 route pool 的直接对应物，
 * 只不过对象从“路径列”换成了“单机序列列”。
 * <p>
 * 列池的职责：
 * <ul>
 * <li>保存所有已知列；</li>
 * <li>通过序列签名快速判重；</li>
 * <li>给每条新列分配稳定 id；</li>
 * <li>让 LP / branching / cut / debug 层都能通过 id 访问列。</li>
 * </ul>
 */
public class Pool {

	/** 当前实例数据。 */
	private final Data data;
	/** 所有列的线性存储。 */
	private final ArrayList<TWETColumn> columns;
	/** 序列签名到列 id 的映射，用于判重。 */
	private final HashMap<SequenceSignature, Integer> signatureToId;

	/** 构造一个空列池。 */
	public Pool(Data data) {
		this.data = data;
		this.columns = new ArrayList<TWETColumn>();
		this.signatureToId = new HashMap<SequenceSignature, Integer>();
	}

	/**
	 * 向全局列池加入一条列。
	 * <p>
	 * 如果同序列的列已存在，则直接复用旧 id。
	 */
	public int addColumn(List<Integer> sequence, double cost, ColumnSource source, boolean seedColumn) {
		SequenceSignature signature = new SequenceSignature(sequence);
		Integer existing = signatureToId.get(signature);
		if (existing != null) {
			return existing.intValue();
		}
		int id = columns.size();
		TWETColumn column = new TWETColumn(id, sequence, data.n, cost, source, seedColumn);
		columns.add(column);
		signatureToId.put(column.getSignature(), Integer.valueOf(id));
		return id;
	}

	/** 2026-06-24: route enumeration 需要先按签名判断全局池中是否已有同序列列。 */
	public int getColumnIdBySignature(SequenceSignature signature) {
		Integer existing = signatureToId.get(signature);
		return existing == null ? -1 : existing.intValue();
	}

	/** @return 按 id 取列 */
	public TWETColumn getColumn(int id) {
		return columns.get(id);
	}

	/** @return 当前列池大小 */
	public int size() {
		return columns.size();
	}

	/** @return 当前列池中的所有列 */
	public List<TWETColumn> getColumns() {
		return columns;
	}

}
