package TWETBPC.LP;

import java.util.ArrayList;
import java.util.List;

import Basic.Data;

/**
 * 分支树节点状态。
 * <p>
 * 这层对应旧参考代码中的 Node：
 * 节点本身不直接存一个 CPLEX 模型，而是保存“描述这个节点子问题所需的轻量状态”。
 * <p>
 * 当前保留的状态包括：
 * <ul>
 * <li>机器数上下界；</li>
 * <li>当前节点允许使用的 seed 列 / incumbent 列；</li>
 * <li>当前节点激活的 cut id；</li>
 * <li>弧状态矩阵（自由 / 禁止 / 必须）。</li>
 * </ul>
 */
public class Node implements Comparable<Node> {

	/** 弧未被分支约束触碰，仍然自由。 */
	public static final byte ARC_FREE = 0;
	/** 弧被明确禁止。 */
	public static final byte ARC_FORBIDDEN = -1;
	/** 弧被明确要求。 */
	public static final byte ARC_REQUIRED = 1;

	/** 当前实例数据。 */
	private final Data data;
	/** 节点编号。 */
	public int id;
	/** 节点深度。 */
	public int depth;
	/** 用于队列排序的伪成本/估计值。 */
	public double pseudoCost;
	/** 当前节点允许的最少机器数。 */
	public int minMachineCount;
	/** 当前节点允许的最多机器数。 */
	public int maxMachineCount;
	/** 当前节点构建 LP 时默认带入的列集合。 */
	public ArrayList<Integer> seedColumnIds;
	/** 当前 incumbent 对应的列集合。 */
	public ArrayList<Integer> incumbentColumnIds;
	/** 当前节点激活的 cut id。 */
	public ArrayList<Integer> activeCutIds;
	/** 弧状态矩阵。 */
	private byte[][] arcState;

	/**
	 * 构造一个节点状态对象。
	 */
	public Node(Data data, List<Integer> seedColumnIds, List<Integer> incumbentColumnIds, double pseudoCost) {
		this.data = data;
		this.id = 0;
		this.depth = 0;
		this.pseudoCost = pseudoCost;
		this.minMachineCount = 0;
		this.maxMachineCount = data.m;
		this.seedColumnIds = new ArrayList<Integer>(seedColumnIds);
		this.incumbentColumnIds = new ArrayList<Integer>(incumbentColumnIds);
		this.activeCutIds = new ArrayList<Integer>();
		this.arcState = new byte[data.n + 2][data.n + 2];
	}

	/**
	 * 深拷贝当前节点。
	 * <p>
	 * 分支时通常以当前节点为模板生成左右子节点，
	 * 因此这里需要完整复制可变状态。
	 */
	public Node copy() {
		Node copy = new Node(data, seedColumnIds, incumbentColumnIds, pseudoCost);
		copy.id = id;
		copy.depth = depth;
		copy.minMachineCount = minMachineCount;
		copy.maxMachineCount = maxMachineCount;
		copy.activeCutIds = new ArrayList<Integer>(activeCutIds);
		copy.arcState = new byte[arcState.length][];
		for (int i = 0; i < arcState.length; i++) {
			copy.arcState[i] = arcState[i].clone();
		}
		return copy;
	}

	/**
	 * 返回虚拟汇点 id。
	 * <p>
	 * 这里约定 sink 编号为 {@code data.n + 1}，
	 * 与真实 job 编号空间错开。
	 */
	public int sinkId() {
		return data.n + 1;
	}

	/** 读取给定弧的状态。 */
	public byte getArcState(int from, int to) {
		return arcState[from][to];
	}

	/** 将给定弧标记为禁止。 */
	public void forbidArc(int from, int to) {
		arcState[from][to] = ARC_FORBIDDEN;
	}

	/** 将给定弧标记为必须出现。 */
	public void requireArc(int from, int to) {
		arcState[from][to] = ARC_REQUIRED;
	}

	/** @return 当前节点是否存在被强制要求的弧 */
	public boolean hasRequiredArcs() {
		for (int from = 0; from < arcState.length; from++) {
			for (int to = 0; to < arcState[from].length; to++) {
				if (arcState[from][to] == ARC_REQUIRED) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 收集所有被要求的弧。
	 * <p>
	 * 该接口主要供 LP 检查 incumbent 是否满足节点约束时使用。
	 */
	public List<int[]> getRequiredArcs() {
		ArrayList<int[]> arcs = new ArrayList<int[]>();
		for (int from = 0; from < arcState.length; from++) {
			for (int to = 0; to < arcState[from].length; to++) {
				if (arcState[from][to] == ARC_REQUIRED) {
					arcs.add(new int[] { from, to });
				}
			}
		}
		return arcs;
	}

	@Override
	/**
	 * 按伪成本从小到大排序。
	 * <p>
	 * 这样 PriorityQueue 弹出的就是“当前估计最值得优先处理”的节点。
	 */
	public int compareTo(Node other) {
		if (pseudoCost < other.pseudoCost) {
			return -1;
		}
		if (pseudoCost > other.pseudoCost) {
			return 1;
		}
		return Integer.compare(id, other.id);
	}

}
