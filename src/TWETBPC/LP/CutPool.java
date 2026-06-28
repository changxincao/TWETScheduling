package TWETBPC.LP;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import TWETBPC.Model.TWETCut;

/**
 * 全局割池。
 * <p>
 * 这层对应旧 BPC 里的 CutPool / SRCutPool / CliCutPool 的“全局池化”思想，
 * 当前先统一成一个轻量 cut pool。
 * <p>
 * 职责很简单：
 * <ul>
 * <li>存放所有已经生成过的 cut 描述；</li>
 * <li>按 signature 去重；</li>
 * <li>给 cut 分配全局 id。</li>
 * </ul>
 */
public class CutPool {

	/** 所有 cut 的线性存储。 */
	private final ArrayList<TWETCut> cuts;
	/** cut signature 到全局 id 的映射，用于去重。 */
	private final HashMap<String, Integer> signatureToId;

	/** 构造一个空的 cut pool。 */
	public CutPool() {
		this.cuts = new ArrayList<TWETCut>();
		this.signatureToId = new HashMap<String, Integer>();
	}

	/**
	 * 向 cut pool 中加入一个 cut。
	 * <p>
	 * 如果同 signature 的 cut 已经存在，则直接复用旧 id。
	 */
	public int addCut(TWETCut cut) {
		String signature = cut.signature();
		Integer existing = signatureToId.get(signature);
		if (existing != null) {
			return existing.intValue();
		}
		if (cut.hasLimitedMemory()) {
			for (int id = 0; id < cuts.size(); id++) {
				TWETCut old = cuts.get(id);
				if (old.hasSameRank1Base(cut)) {
					TWETCut merged = old.mergedMemoryWith(cut);
					cuts.set(id, merged);
					signatureToId.put(merged.signature(), Integer.valueOf(id));
					return id;
				}
			}
		}
		int id = cuts.size();
		cuts.add(cut);
		signatureToId.put(signature, Integer.valueOf(id));
		return id;
	}

	/** @return 根据 id 取 cut */
	public TWETCut getCut(int id) {
		return cuts.get(id);
	}

	/** @return 当前 cut pool 的大小 */
	public int size() {
		return cuts.size();
	}

	/** @return 当前所有 cut 的存储列表 */
	public List<TWETCut> getCuts() {
		return cuts;
	}

}
