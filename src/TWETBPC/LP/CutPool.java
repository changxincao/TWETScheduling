package TWETBPC.LP;

import java.util.ArrayList;
import java.util.Arrays;
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
	/** 按 cut/column ID 分页保存已经计算过的 SRI 系数；页只在实际访问时分配。 */
	private final ArrayList<short[][]> subsetRowCoefficientPages;
	private final boolean cacheSubsetRowCoefficients;
	private final long maxSubsetRowCoefficientCacheEntries;
	private long allocatedSubsetRowCoefficientCacheEntries;

	private static final int COEFFICIENT_PAGE_SHIFT = 12;
	private static final int COEFFICIENT_PAGE_SIZE = 1 << COEFFICIENT_PAGE_SHIFT;
	private static final int COEFFICIENT_PAGE_MASK = COEFFICIENT_PAGE_SIZE - 1;
	private static final short COEFFICIENT_NOT_CACHED = -1;

	/** 构造一个空的 cut pool。 */
	public CutPool() {
		this.cuts = new ArrayList<TWETCut>();
		this.signatureToId = new HashMap<String, Integer>();
		this.subsetRowCoefficientPages = new ArrayList<short[][]>();
		this.cacheSubsetRowCoefficients =
				Boolean.parseBoolean(System.getProperty("twet.bpc.cacheSubsetRowCoefficients", "true"));
		this.maxSubsetRowCoefficientCacheEntries =
				Math.max(0L, Long.getLong("twet.bpc.maxSubsetRowCoefficientCacheEntries", 16_777_216L));
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
		// 2026-07-26: cut ID 一旦被节点继承或进入 CPLEX 模型，其系数定义必须保持不变。
		// 不同节点为同一 rank-1 base 得到的 limited memory 可能不同，不能原地扩大旧 ID 的 memory；
		// 否则排队节点重建时会读取到与 strong trial 不同的 cut，并且当前模型的行与 pricing 口径也会失配。
		int id = cuts.size();
		cuts.add(cut);
		subsetRowCoefficientPages.add(null);
		signatureToId.put(signature, Integer.valueOf(id));
		return id;
	}

	/**
	 * @return 已缓存系数；尚未缓存时返回 -1。
	 */
	public int getSubsetRowCoefficient(int cutId, int columnId) {
		if (!cacheSubsetRowCoefficients || cutId < 0 || cutId >= subsetRowCoefficientPages.size()
				|| columnId < 0) {
			return -1;
		}
		short[][] pages = subsetRowCoefficientPages.get(cutId);
		int pageIndex = columnId >>> COEFFICIENT_PAGE_SHIFT;
		if (pages == null || pageIndex >= pages.length || pages[pageIndex] == null) {
			return -1;
		}
		return pages[pageIndex][columnId & COEFFICIENT_PAGE_MASK];
	}

	/**
	 * 缓存不可变 cut/column ID 对应的非负整数系数。超过全局页容量时停止分配新页，已有页继续使用。
	 */
	public void cacheSubsetRowCoefficient(int cutId, int columnId, int coefficient) {
		if (!cacheSubsetRowCoefficients || cutId < 0 || cutId >= subsetRowCoefficientPages.size()
				|| columnId < 0 || coefficient < 0 || coefficient > Short.MAX_VALUE) {
			return;
		}
		int pageIndex = columnId >>> COEFFICIENT_PAGE_SHIFT;
		short[][] pages = subsetRowCoefficientPages.get(cutId);
		short[] page = pages != null && pageIndex < pages.length ? pages[pageIndex] : null;
		if (page == null) {
			if (allocatedSubsetRowCoefficientCacheEntries + COEFFICIENT_PAGE_SIZE
					> maxSubsetRowCoefficientCacheEntries) {
				return;
			}
			if (pages == null || pageIndex >= pages.length) {
				int newLength = Math.max(pageIndex + 1, pages == null ? 1 : pages.length << 1);
				pages = pages == null ? new short[newLength][] : Arrays.copyOf(pages, newLength);
				subsetRowCoefficientPages.set(cutId, pages);
			}
			page = new short[COEFFICIENT_PAGE_SIZE];
			Arrays.fill(page, COEFFICIENT_NOT_CACHED);
			pages[pageIndex] = page;
			allocatedSubsetRowCoefficientCacheEntries += COEFFICIENT_PAGE_SIZE;
		}
		page[columnId & COEFFICIENT_PAGE_MASK] = (short) coefficient;
	}

	/**
	 * 缓存剩余容量不足一整页后，释放当前模型不再使用的 cut 页。
	 * <p>
	 * queued node 后续再次启用被释放的 cut 时会重新计算系数；cut 定义和已建 CPLEX row
	 * 都不受影响。未达到容量时直接返回，避免给普通热路径增加 active-set 扫描。
	 *
	 * @return 实际释放的缓存页数
	 */
	public int reclaimInactiveSubsetRowCoefficientPages(List<Integer> activeCutIds) {
		if (!cacheSubsetRowCoefficients || maxSubsetRowCoefficientCacheEntries < COEFFICIENT_PAGE_SIZE
				|| allocatedSubsetRowCoefficientCacheEntries
						<= maxSubsetRowCoefficientCacheEntries - COEFFICIENT_PAGE_SIZE) {
			return 0;
		}
		boolean[] active = new boolean[subsetRowCoefficientPages.size()];
		for (int cutId : activeCutIds) {
			if (cutId >= 0 && cutId < active.length) {
				active[cutId] = true;
			}
		}
		int releasedPages = 0;
		for (int cutId = 0; cutId < subsetRowCoefficientPages.size(); cutId++) {
			if (active[cutId]) {
				continue;
			}
			short[][] pages = subsetRowCoefficientPages.get(cutId);
			if (pages == null) {
				continue;
			}
			for (short[] page : pages) {
				if (page != null) {
					releasedPages++;
				}
			}
			subsetRowCoefficientPages.set(cutId, null);
		}
		allocatedSubsetRowCoefficientCacheEntries -= (long) releasedPages * COEFFICIENT_PAGE_SIZE;
		return releasedPages;
	}

	/**
	 * 将同一 rank-1 multiplier 的当前 active memory 版本合并为一个新版本。
	 * <p>
	 * 2026-07-26: 论文要求重复分离到同一 multiplier 时扩大已有 memory。这里不能原地修改全局
	 * cut ID，否则已经排队的 child 会读取到变化后的系数；因此只改调用方提供的当前节点 active
	 * ID 列表，并把 memory 并集作为新的不可变 cut 放入池中。
	 */
	public Rank1MemoryMergeResult mergeRank1MemoryIntoActive(List<Integer> activeCutIds, TWETCut candidate) {
		TWETCut merged = candidate;
		ArrayList<Integer> matchingIds = new ArrayList<Integer>();
		for (int cutId : activeCutIds) {
			TWETCut active = getCut(cutId);
			if (active.hasSameRank1Base(candidate)) {
				matchingIds.add(Integer.valueOf(cutId));
				merged = merged.mergedMemoryWith(active);
			}
		}
		int mergedId = addCut(merged);
		if (matchingIds.size() == 1 && matchingIds.get(0).intValue() == mergedId) {
			return new Rank1MemoryMergeResult(false, 1, 0);
		}
		activeCutIds.removeAll(matchingIds);
		if (!activeCutIds.contains(Integer.valueOf(mergedId))) {
			activeCutIds.add(Integer.valueOf(mergedId));
		}
		int removedVersions = matchingIds.size();
		if (matchingIds.contains(Integer.valueOf(mergedId))) {
			removedVersions--;
		}
		return new Rank1MemoryMergeResult(true, matchingIds.size(), removedVersions);
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

	/** 当前节点执行一次同 base memory 合并后的结果。 */
	public static final class Rank1MemoryMergeResult {

		private final boolean changed;
		private final int matchedActiveVersions;
		private final int removedActiveVersions;

		private Rank1MemoryMergeResult(boolean changed, int matchedActiveVersions, int removedActiveVersions) {
			this.changed = changed;
			this.matchedActiveVersions = matchedActiveVersions;
			this.removedActiveVersions = removedActiveVersions;
		}

		public boolean isChanged() {
			return changed;
		}

		public int getMatchedActiveVersions() {
			return matchedActiveVersions;
		}

		public int getRemovedActiveVersions() {
			return removedActiveVersions;
		}
	}

}
