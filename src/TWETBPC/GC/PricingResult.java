package TWETBPC.GC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import TWETBPC.Model.TWETColumn;

/**
 * 一次 pricing 调用的结果对象。
 * <p>
 * 该对象把“有没有找到改进列”和“找到哪些列”打包到一起，
 * 让 PC 层不需要关心具体定价器的内部细节。
 */
public final class PricingResult {

	/** 本次 pricing 返回的列集合。 */
	private final ArrayList<TWETColumn> columns;
	/** 本次是否真正找到可加入主问题的改进列。 */
	private final boolean improved;
	/** 调试信息或备注。 */
	private final String message;

	/**
	 * 构造一个 pricing 结果。
	 */
	public PricingResult(List<TWETColumn> columns, boolean improved, String message) {
		this.columns = new ArrayList<TWETColumn>(columns);
		this.improved = improved;
		this.message = message;
	}

	/**
	 * 返回“无改进列”的标准结果对象。
	 */
	public static PricingResult noImprovement(String message) {
		return new PricingResult(Collections.<TWETColumn>emptyList(), false, message);
	}

	/** @return 本次 pricing 得到的列（只读） */
	public List<TWETColumn> getColumns() {
		return Collections.unmodifiableList(columns);
	}

	/** @return 是否有改进 */
	public boolean isImproved() {
		return improved;
	}

	/** @return 附加信息 */
	public String getMessage() {
		return message;
	}

}
