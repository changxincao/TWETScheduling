package TWETBPC.GC;

import java.util.ArrayList;

import TWETBPC.Util.PackedBitSet;

/**
 * 单个 terminal job 下 label 占优结构的统一接口。
 * <p>
 * 2026-05-17: 这里把旧的全量扫描 dominance graph 和按论文伪代码实现的 graph
 * 隔离开，便于后续在同一套 pricing 主流程下比较两种占优结构的效率。
 */
interface DominanceStore {

	/**
	 * 尝试插入一个 label。
	 *
	 * @return true 表示该 label 已被当前结构完整占优并丢弃；false 表示它被保留。
	 */
	boolean insertOrDominate(Label label);

	/**
	 * 返回当前 graph 中仍然 active 的真实 label。用于双向 pricing 的 join 枚举。
	 */
	ArrayList<Label> getActiveLabels();

	/**
	 * 2026-05-23: 高频 join 中避免每次新建 label 列表。
	 * 调用方负责先清空 buffer，本方法只追加当前仍 active 的真实 label。
	 */
	void collectActiveLabels(ArrayList<Label> buffer);

	/**
	 * 2026-05-25: single-point label 不再插入普通 dominance graph，但仍可复用 graph 现有的 reachable-set
	 * 层次搜索，判断它是否已经被某个 active 普通 label 在给定时间点压住。
	 *
	 * @return true 表示当前 graph 中已存在可支配该单点 label 的普通 label；false 表示未找到。
	 */
	boolean dominatesSinglePoint(PackedBitSet reachableSet, int reachableCardinality, double pointTime,
			double pointValue);

}
