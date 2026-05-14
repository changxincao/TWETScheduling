package TWETBPC.CUT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import TWETBPC.Model.TWETCut;

/**
 * 一次割分离调用的结果对象。
 * <p>
 * 和 {@link TWETBPC.GC.PricingResult} 类似，
 * 它把“是否分离到新割”和“这些割是什么”集中到一起。
 */
public final class CutGenerationResult {

	/** 新分离得到的割集合。 */
	private final ArrayList<TWETCut> cuts;
	/** 本次是否真的找到新割。 */
	private final boolean separated;
	/** 调试/说明信息。 */
	private final String message;

	/**
	 * 构造一个 cut 结果对象。
	 */
	public CutGenerationResult(List<TWETCut> cuts, boolean separated, String message) {
		this.cuts = new ArrayList<TWETCut>(cuts);
		this.separated = separated;
		this.message = message;
	}

	/**
	 * 返回“没有分离到新割”的标准空结果。
	 */
	public static CutGenerationResult empty(String message) {
		return new CutGenerationResult(Collections.<TWETCut>emptyList(), false, message);
	}

	/** @return 分离得到的 cuts（只读） */
	public List<TWETCut> getCuts() {
		return Collections.unmodifiableList(cuts);
	}

	/** @return 是否分离到新割 */
	public boolean isSeparated() {
		return separated;
	}

	/** @return 补充说明 */
	public String getMessage() {
		return message;
	}

}
