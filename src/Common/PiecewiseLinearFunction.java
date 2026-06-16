// PiecewiseLinearFunction.java
package Common;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.Deque;
import java.util.List;

import Common.Utility.TimerManager;

//假设各段之间区间连续的
public class PiecewiseLinearFunction {
	// 2026-05-16: 只用于人为粗硬窗退化为单点时的轻微放宽。
	// 粗硬窗本身是预处理剪枝，不是真实物理可行域；把 [d,d] 放宽到 d±0.1 只会少剪一点，
	// 不会因为这个扰动删掉最优解，同时能避免长度为 0 的点段进入 add/merge/normalize。
	private static final double WINDOW_EPSILON = 0.1;

	/**
	 * 分段函数集合操作的闭包方向。
	 * FORWARD 表示按前缀最小值维护 [a,T] 语义；BACKWARD 表示按后缀最小值维护 [0,b] 语义。
	 */
	public enum Direction {
		FORWARD,
		BACKWARD
	}

	/**
	 * partial dominance 裁剪结果。NO_CHANGE 表示没有正长度区间被裁掉，
	 * PARTIAL 表示函数仍非空但被改写，EMPTY 表示函数已被完全裁空。
	 */
	public enum TrimResult {
		NO_CHANGE,
		PARTIAL,
		EMPTY
	}

	/**
	 * 2026-06-16: mergeMinimum 的增量结果。多个离散改善区间先合并成一个 hull，
	 * 供 completion-bound delta propagation 实验路径使用。
	 */
	public static final class MergeResult {
		public boolean changed;
		public double changedStart = Double.POSITIVE_INFINITY;
		public double changedEnd = Double.NEGATIVE_INFINITY;

		public boolean hasPositiveChangedInterval() {
			return changed && Utility.compareLt(changedStart, changedEnd);
		}

		private void markChanged(double start, double end) {
			if (!Utility.compareLt(start, end)) {
				return;
			}
			changed = true;
			changedStart = Math.min(changedStart, start);
			changedEnd = Math.max(changedEnd, end);
		}
	}

	public static class Segment {
		public double start, end; // local coordinates
		public double slope;
		public double intercept;
		public Segment next;

		public Segment(double s, double e, double a, double b) {
			TimerManager.start("Segment初始化");
			start = s;
			end = e;
			slope = a;
			intercept = b;
			next = null;
			TimerManager.end("Segment初始化");
		}

		public double getValue(double t) {

//			if(Utility.compareLt(t, start)||Utility.compareLt(end,t)) {
//				TimerManager.end("Segment取值");
//				return Utility.big_M;
//			}
			// 感觉似乎不判断也行，使用的时候都是基于当前段的边界算的
			return slope * t + intercept;
		}
	}

	/**
	 * 简单的链表节点复用池，用来缓存和重用 Segment 实例。
	 * 防止过大的垃圾回收开销，次数是减少了，感觉上时间区别不大
	 * 没有使用Pool的话把Utility里边的Pool关掉就好了，原始代码见save.PiecewiseLinearFunction
	 * 其实就是用Segemnt.obtain()代替了new Segment，并增加了几个地方的release函数将没有用的一些临时函数释放掉
	 */
	
	public int getSegmentNum() {
		int num=0;
		
		Segment p=head;
		if(p==null) return num;
		while(p.next!=null) {
			num++;
			p=p.next;
		}
		if(num==1&&Utility.compareEq(p.intercept,Utility.curUpperBound)&&Utility.compareEq(p.slope,0)) {
			Utility.debugMap.put("PWLF_Only1Seg_Equal_UB",Utility.debugMap.getOrDefault("PWLF_Only1Seg_Equal_UB", 0)+1);
			
		}
		Utility.debugMap.put("segmentNum",Utility.debugMap.getOrDefault("segmentNum", 0)+num);

		return num;
	}
	//做了测试，这玩意慢了很多很多，GPT说java对小尺寸对象回收很快，不需要。。
	public static class SegmentPool {
		private static final int MAX_SIZE=10000;
		private static final Deque<Segment> pool = new ArrayDeque<>(MAX_SIZE);

		
		/**
		 * 从池里拿一个 Segment，如果没有就 new 一个。
		 */
		public static Segment obtain(double start, double end, double slope, double intercept) {
			Segment seg;
			if (Configure.SegmentPool) {
				if (!pool.isEmpty()) {
					seg = pool.pop();
					// 重置字段
					seg.start = start;
					seg.end = end;
					seg.slope = slope;
					seg.intercept = intercept;
					seg.next = null;
				} else {
					seg = new Segment(start, end, slope, intercept);
				}
			} else {
				seg = new Segment(start, end, slope, intercept);

			}
			return seg;
		}

		/**
		 * 将一个（或一条链） Segment 放回池中，清空 next 引用以便重用。
		 */
		public static void release(Segment seg) {
			if(!Configure.SegmentPool) return;
			while (seg != null) {
				Segment next = seg.next;
				seg.next = null;
				if(pool.size()<MAX_SIZE) {
				pool.push(seg);
				}
				seg = next;
			}
		}
	}

	public Segment head, tail;
	public double domainStart = 0; // 全局定义域起点
	public double domainEnd = Double.POSITIVE_INFINITY; // 全局定义域终点
	public double[] minValuePairsLeft;// 存储函数全局最小cost以及对应的函数取值 [0]=cost [1]=t,t取所有取最小cost的t中最左边的
	public double[] minValuePairsRight;// 存储函数全局最小cost以及对应的函数取值 [0]=cost [1]=t,t取所有取最小cost的t中最右边的
	// 这俩copy的时候不复制，从而对那些操作中基于一个复制的函数做操作的，不需要处理
	// 比如shift\add
	// 对前向取小和后向取小，暂时不处理，感觉应该不会在这里冲突
	// 基本应该只会对同一个函数多次取它的最小值，变化以后取最小的应该没啥

	public PiecewiseLinearFunction() {
		head = tail = null;
	}

	public PiecewiseLinearFunction(double domainStart, double domainEnd) {
		TimerManager.start("分段线性函数初始化");
		head = tail = null;
		if (Utility.compareLt(domainStart, domainEnd)) {
			this.domainStart = domainStart;
			this.domainEnd = domainEnd;
		} else {
			throw new IllegalArgumentException("Invalid domain: start >= end");
		}
		// TODO 这个判断后期关了吧
		TimerManager.end("分段线性函数初始化");
	}

	public PiecewiseLinearFunction copy() {
		PiecewiseLinearFunction res = new PiecewiseLinearFunction(domainStart, domainEnd);
		if (this.head == null)
			return res;
		TimerManager.start("分段线性函数复制");
		res.head = SegmentPool.obtain(head.start, head.end, head.slope, head.intercept);
		Segment q = res.head;
		Segment p = head.next;
		while (p != null) {
			q.next = SegmentPool.obtain(p.start, p.end, p.slope, p.intercept);
			p = p.next;
			q = q.next;
		}
		res.tail = q;
		TimerManager.end("分段线性函数复制");
		return res;
	}
	

	// 设置定义域
	public PiecewiseLinearFunction setDomain(double domainStart, double domainEnd) {
		Utility.debugCheckPWLFRightBound("setDomain.input", this);
		// 不会改变当前函数，会复制一个产生新的，不需要copy()以后
		TimerManager.start("分段线性函数设置定义域");
		PiecewiseLinearFunction res = this.copy();
		if (Utility.compareLe(domainStart, domainEnd)) {
			res.domainStart = domainStart;
			res.domainEnd = domainEnd;
			res.trimToDomain();
		} else {
			//TODO 是否这么做
			res.head=res.tail=null;//此时直接设置为空函数
//			throw new IllegalArgumentException("Invalid domain: start >= end");
		}
		res.resetDomain(this.domainStart, this.domainEnd);
		// 2026-05-14: setDomain 是最容易主动裁掉右端 T 的入口。
		// 这里必须在 resetDomain 后检查，才能发现 tail.end 被裁到 end<T、但函数元数据又恢复为原 T 的情况。
		Utility.debugCheckPWLFRightBound("setDomain.output", res);
		// TODO 是否需要

		TimerManager.end("分段线性函数设置定义域");
		return res;
	}

	/**
	 * 2026-05-15: 带窗口外 big_M 填充的定义域限制。
	 * false 时保持旧版 setDomain 语义：物理裁掉窗口外 segment，主要给启发式回推 completion 使用。
	 * true 时不裁掉右端定义域，而是把窗口外区间写成 big_M 段，保持 tail.end 到原来的 T；
	 * 这个语义用于 BPC pricing 中的 profitable completion window，避免后续 add/merge 破坏 [a,T] 契约。
	 */
	public PiecewiseLinearFunction setDomain(double domainStart, double domainEnd, boolean fillOutsideWithBigM) {
		if (!fillOutsideWithBigM) {
			return setDomain(domainStart, domainEnd);
		}
		Utility.debugCheckPWLFRightBound("setDomainBigM.input", this);
		TimerManager.start("分段线性函数设置定义域");
		PiecewiseLinearFunction res = new PiecewiseLinearFunction(this.domainStart, this.domainEnd);
		if (this.head == null) {
			TimerManager.end("分段线性函数设置定义域");
			return res;
		}

		double actualStart = this.head.start;
		double actualEnd = this.tail.end;
		double keepStart = Math.max(actualStart, domainStart);
		double keepEnd = Math.min(actualEnd, domainEnd);
		if (!Utility.compareLt(keepStart, keepEnd)) {
			// 2026-05-16: 三参数 setDomain 用于硬窗/定价窗，窗外填 big_M，不物理删除定义域。
			// 若窗口退化成单点，不把内部单点作为特殊段传播，而是扩成一个很小区间。
			// 当前算例时间基本为整数，0.1 的扰动只用于统一函数结构；普通两参数 setDomain 不做这个处理。
			double center = 0.5 * (keepStart + keepEnd);
			keepStart = Math.max(actualStart, center - WINDOW_EPSILON);
			keepEnd = Math.min(actualEnd, center + WINDOW_EPSILON);
		}

		if (!Utility.compareLt(keepStart, keepEnd)) {
			// 窗口与当前函数没有正长度交集时，整段都视作不可行，但仍保留右端到 T。
			res.addSegment(actualStart, actualEnd, 0, Utility.big_M);
			Utility.debugCheckPWLFRightBound("setDomainBigM.output", res);
			TimerManager.end("分段线性函数设置定义域");
			return res;
		}

		if (Utility.compareLt(actualStart, keepStart)) {
			res.addSegment(actualStart, keepStart, 0, Utility.big_M);
		}

		for (Segment seg = this.head; seg != null; seg = seg.next) {
			double start = Math.max(seg.start, keepStart);
			double end = Math.min(seg.end, keepEnd);
			if (Utility.compareLt(start, end)) {
				res.addSegment(start, end, seg.slope, seg.intercept);
			}
		}

		if (Utility.compareLt(keepEnd, actualEnd)) {
			// 当前 Segment 采用左闭右开、tail.end 特判的约定。
			// 窗口右端点若需要严格闭区间语义，应由后续 prefix-min 或 label 层单点逻辑处理。
			res.addSegment(keepEnd, actualEnd, 0, Utility.big_M);
		}

		Utility.debugCheckPWLFRightBound("setDomainBigM.output", res);
		TimerManager.end("分段线性函数设置定义域");
		return res;
	}

	public void resetDomain(double domainStart, double domainEnd) {
		this.domainStart = domainStart;
		this.domainEnd = domainEnd;
	}

	public boolean isEmpty() {
		return head == null;
	}

	public void addSegment(double start, double end, double slope, double intercept) {
		TimerManager.start("分段线性函数加入Segment");
//			if (Utility.compareLt(end, domainStart) || Utility.compareLt(domainEnd, start)) {
//				return; // 片段完全在定义域外，忽略
//			}

//			start = Math.max(start, domainStart);
//			end = Math.min(end, domainEnd);
//			if (tail != null && !Utility.compareEq(tail.end, start)) {
//				throw new IllegalArgumentException("Non-contiguous segment");
//			}
		// 不太可能

		Segment seg = SegmentPool.obtain(start, end, slope, intercept);
		if (head == null)
			head = tail = seg;
		else {
			tail.next = seg;
			tail = seg;
		}
		TimerManager.end("分段线性函数加入Segment");
	}

	// 未检查片段是否按时间顺序添加，或是否存在重叠/间隙。假设调用者保证片段连续且不重叠。

	public void addSegment(Segment s) {
		// 这个函数不新建对象，直接复用已有对象

		if (head == null)
			head = tail = s;
		else {
			tail.next = s;
			tail = s;
		}
	}

	public PiecewiseLinearFunction shiftX(double delta) {
		Utility.debugCheckPWLFRightBound("shiftX.input", this);
		//X 水平方向平移
		TimerManager.start("分段线性函数shiftX");
		PiecewiseLinearFunction res = copy();
		Segment p = res.head;
		while (p != null) {
			p.start += delta;
			p.end += delta;
			p.intercept -= p.slope * delta;
			p = p.next;
		}
		res.trimToDomain();
		Utility.debugCheckPWLFRightBound("shiftX.output", res);
		TimerManager.end("分段线性函数shiftX");
		return res;
	}
	
	public PiecewiseLinearFunction shiftY(double delta) {
		//Y 竖直方向平移
		TimerManager.start("分段线性函数shiftY");
		PiecewiseLinearFunction res = copy();
		res.shiftYInPlace(delta);
		TimerManager.end("分段线性函数shiftY");
		return res;
	}

	public PiecewiseLinearFunction shiftYInPlace(double delta) {
		// 2026-05-15: setup cost 是弧上的固定目标成本，只需要把整条函数纵向平移。
		// 这里提供原地版本，避免在大量递推中为了常数项额外复制函数。
		if (Utility.compareEq(delta, 0.0)) {
			return this;
		}
		Segment p = head;
		while (p != null) {
			p.intercept += delta;
			p = p.next;
		}
		minValuePairsLeft = null;
		minValuePairsRight = null;
		return this;
	}

	public double evaluate(double t) {

		if (head == null)
			return Utility.curUpperBound;//不采用返回upperBound，这个值可能用于和别的一些较差解去比较，如果这个采用upperBound,那可能反而一个很差的解因为采用了upperBound的值反而被接受
			//虽然整体可能不应该超过upperBound，但通过3Segment合并的时候，不能使用upperBound判断
			//见Move的注释，暂时认为没啥区别，可以混用
		if (!(Utility.compareLe(t, tail.end) && Utility.compareLe(head.start, t)))
			return Utility.curUpperBound;
		TimerManager.start("分段线性函数evaluate");
		Segment prev = null;
		for (Segment cur = head; cur != null; cur = cur.next) {
			if (prev != null && Utility.compareEq(prev.end, cur.start) && Utility.compareEq(t, cur.start)) {
				// 2026-05-15: 内部断点处按左右极限的较小值解释函数值。
				// partial dominance 可能在断点两侧产生 vertical gap；不维护内部零长度点时，
				// 最终评价仍应允许取到左右两侧中更优的那个端点值。
				double leftValue = prev.slope * t + prev.intercept;
				double rightValue = cur.slope * t + cur.intercept;
				TimerManager.end("分段线性函数evaluate");
				return Math.min(leftValue, rightValue);
			}

			if (Utility.compareGe(t, cur.start) && Utility.compareLt(t, cur.end)) {
				TimerManager.end("分段线性函数evaluate");
				return cur.slope * t + cur.intercept;
			}
			prev = cur;
		}
		if (Utility.compareEq(t, tail.end)) {
			TimerManager.end("分段线性函数evaluate");
			return tail.slope * t + tail.intercept;
		}

		throw new IllegalArgumentException("t out of domain: " + t);

	}

//	private double slopeAt(double t) {
//		if (head == null)
//			return Utility.big_M;
//		if (!(Utility.compareLe(t, tail.end) && Utility.compareLe(head.start, t)))
//			return Utility.big_M;
//
//		for (Segment cur = head; cur != null; cur = cur.next) {
//			if (Utility.compareGe(t, cur.start) && Utility.compareLt(t, cur.end)) {
//				return cur.slope;
//			}
//		}
//		if (Utility.compareEq(t, tail.end)) {
//			return tail.slope;
//		}
//		throw new IllegalArgumentException("t out of domain: " + t);
//	}

	public PiecewiseLinearFunction add(PiecewiseLinearFunction g) {
		Utility.debugCheckPWLFRightBoundPair("add.input", this, g);
		PiecewiseLinearFunction res = new PiecewiseLinearFunction(domainStart, domainEnd);

		// 空链表检查
		if (this.head == null || g.head == null)
			return res;

		// 计算共有定义域
		double fStart = this.head.start;
		double fEnd = this.tail.end;
		double gStart = g.head.start;
		double gEnd = g.tail.end;
		double start = Math.max(fStart, gStart); // 共有起点
		double end = Math.min(fEnd, gEnd); // 共有终点

		// 如果定义域无交集，返回空函数
		if (Utility.compareLt(end, start))
			return res;
		TimerManager.start("分段线性函数相加");

		Segment p = this.head, q = g.head;
		double cur = start; // 当前区间起点

		// 跳到第一个有效片段
		while (p != null && Utility.compareLt(p.end, start))
			p = p.next;
		while (q != null && Utility.compareLt(q.end, start))
			q = q.next;

		// 处理共有区间
		while (p != null && q != null && Utility.compareLe(cur, end)) {
			double pEnd = p.end;
			double qEnd = q.end;
			double nxt = Math.min(Math.min(pEnd, qEnd), end); // 区间终点
			if (Utility.compareLe(cur, nxt)) { // 有效区间
				// 只有某个函数的某一段只有单点，此处才会存在单点应该，而函数单点应该基本只会出现在shift以后被砍掉可行域？
				// 比如都没有库存成本，且d都为90，那此时最优解无等待，Cmax就是任务的最大设置和本身时间的和（单机器），此时假设总时间为480，随着函数迭代最后一个任务的可行域为[480-..]，但由于事先限制了上限为480，此时就会出现单点
				// 2025.4.23
//					wet040_021_2m.dat 算例 任务只取2个时，顺序[1,2]，此时计算此处会出现单点，算例满足前边说的情况、若不改为le，就会导致一个空函数，从而报错
				// 另一种处理方式就是只要Cmax比最优解的稍微大一些，应该就不会出现单点了。设置Cmax为其上界+1即可
				double ms = p.slope + q.slope;
				double mi = (p.intercept + q.intercept);
				res.addSegment(cur, nxt, ms, mi);
				cur = nxt;
			}
			// 移动指针
			if (Utility.compareEq(nxt, pEnd))
				p = p.next;
			if (Utility.compareEq(nxt, qEnd))
				q = q.next;
		}
		TimerManager.end("分段线性函数相加");
		res.getSegmentNum();
		Utility.debugCheckPWLFRightBound("add.output", res);
		return res;
	}
	// 未检查输入函数的定义域是否连续。

	public void minimizePrefixInPlace() {
		Utility.debugCheckPWLFRightBound("minimizePrefix.input", this);
		if (head == null)
			return;
		TimerManager.start("分段线性函数前缀最小化");

		double runningMin = Utility.curUpperBound;//Double.POSITIVE_INFINITY;
		double prevT = head.start;
		Segment dummy = SegmentPool.obtain(0, 0, 0, 0), write = dummy;

		for (Segment seg = head; seg != null; seg = seg.next) {
			double s = seg.start, e = seg.end;
			double a = seg.slope, b = seg.intercept;
			double fgs = a * seg.start + b;
			// 当前段增函数或水平？
			if (Utility.compareGe(a, 0)) {
				if (Utility.compareLe(runningMin, fgs)) {

					continue;// 不做处理
				} else {
					if (!Utility.compareEq(prevT, s)) {
						write.next = SegmentPool.obtain(prevT, s, 0.0, runningMin);
						write = write.next;
						prevT = s;
					}
					runningMin = fgs;

				}

			} else {
				// 递减函数
				double fge = a * seg.end + b;
				if (Utility.compareLe(runningMin, fge)) {
					continue;
				}

				// 段内穿越
				if (Utility.compareGt(fgs, runningMin)) {
					double tCross = (runningMin - b) / a;
					if (Utility.compareLt(prevT, tCross)) {
						if(!Utility.compareEq(runningMin, Utility.curUpperBound)) {
							// 2026-05-14: 只跳过 curUpperBound 形成的人工边界段；真实最小值即使贴着边界也必须补回。
							// 详细讨论见 docs/plans/PiecewiseLinearFunction 专题记录。
							// 2025.5.10 历史注释保留：新增一个内部的if，将所有的big_M替换成了upperbound，此时可能存在端内穿越有交点。
							// 在此基础上，当要插入的是一条直线且该段的开始时间为head.start，原思路认为此时不需要插入。
							// 不过在我们的问题下，这里段内穿越应该最多就开始一次，相当于会截断一部分。
							// 这个if要是没有，就相当于对这个函数大于上界的那部分做了替换。
							write.next = SegmentPool.obtain(prevT, tCross, 0.0, runningMin);
							write = write.next;
						}
					}
					write.next = SegmentPool.obtain(tCross, seg.end, a, b);
					write = write.next;
					runningMin = fge;
					prevT = e;
					continue;
				} else {
					// 递减且开头比runningMin更小
					if (!Utility.compareEq(prevT, s)) {
						write.next = SegmentPool.obtain(prevT, s, 0.0, runningMin);
						write = write.next;
					}
					write.next = SegmentPool.obtain(seg.start, seg.end, seg.slope, seg.intercept);
					write = write.next;
					runningMin = fge;
					prevT = e;
				}

			}

		}
		double lastEnd = tail.end;
		if (Utility.compareLt(prevT, lastEnd)) {
			
			write.next = SegmentPool.obtain(prevT, lastEnd, 0.0, runningMin);
			write = write.next;

		}
		//2025.5.10 如果整个函数都比upperBound更高的话，暂时处理为一条水平线，不直接砍掉
		//TODO 小心normalize可能把这条线给删了，现在normalize是否有需要呢？
		if (Utility.compareLe(prevT, lastEnd) && dummy.next == null) {
			// 只包含一个点且该段首尾相同，是个单点
			// 2025.4.23 这个地方是相对上边多加的，应该只会当一个段有一个单点的时候才会进入这里
			// 或者这段if也可以不加入，直接Cmax在算好的基础上+1也行，但不知道会不会别的地方出问题，感觉应该不会，先这样
			// 这段不能直接加在上边改成le，不然任何情况下都会在末尾产生一个单点
			
			write.next = SegmentPool.obtain(prevT, lastEnd, 0.0, runningMin);
			write = write.next;
		}

		this.head = dummy.next;
		this.tail = write;
		Utility.debugCheckPWLFRightBound("minimizePrefix.output", this);
		TimerManager.end("分段线性函数前缀最小化");
		getSegmentNum();
	}

	// 这个取min的操作应该是通用的了，不管函数的分段线性是什么样子，不连续、增减交替
	// 凹凸等，对我们的问题，应该是那个判断交点的步骤是不需要的，不可能存在一个段的开始点比前段的最低要更大
	// 因为我们的函数是一个非增函数（不一定连续）加上一个连续的分段线性函数g，此时对g的递减和平的部分，加上完全不会导致一个
	// 在后边的段比前边更高，对其递增的部分，由于是连续的，因此两个段的交界处在相加以后肯定还是后段比前段低的关系，不可能更高的。
	// 对于凸的连续的函数，这个操作可以通过二分去处理，但这种就要用arraylist存储了。

	/**
	 * 将本函数做 suffix‐minimize： 令 g(t)=min_{u≥t} f(u)，保证所得 g 在整体上是非递减的（即 f(t) ≤
	 * runningMin）。 与前向版本对称地“倒着”扫一遍，然后重建链表。
	 */
	// 这个使用列表辅助，相当于创建了新的分段线性函数,这个经过多次验证应该是对的
//	public void minimizeSuffixInPlace() {
//		// 函数 反向取小
//		if (head == null)
//			return;
//		TimerManager.start("分段线性函数后缀最小化");
//		// （1）把链表扫一遍装到数组里，便于倒序访问
//		List<Segment> segs = new ArrayList<>();
//		for (Segment s = head; s != null; s = s.next) {
//			segs.add(s);
//		}
//
//		// （2）倒序处理，每次产出新段，先累到 newSegs（逆序）。
//		double runningMin = Double.POSITIVE_INFINITY;
//		List<Segment> newSegs = new ArrayList<>();
//		double lastT = tail.end;
//		for (int i = segs.size() - 1; i >= 0; i--) {
//			Segment seg = segs.get(i);
//			double s = seg.start, e = seg.end;
//			double a = seg.slope, b = seg.intercept;
//			double fs = a * s + b;
//			double fe = a * e + b;
//
//			if (Utility.compareLe(a, 0)) {
//				// **单调递减段**：f(s) ≥ f(t) ≥ f(e)
//				if (Utility.compareLe(runningMin, fe)) {
//					// 整段都 ≥ runningMin → 全常数段
//					continue;
//				} else {
//					if (!Utility.compareEq(lastT, e)) {
//						newSegs.add(new Segment(e, lastT, 0, runningMin));
//						lastT = e;
//					}
//					runningMin = fe;
//
//				}
//			} else {
//				// **单调递增段**：f(s) ≤ f(t) ≤ f(e)
//				if (Utility.compareLe(runningMin, fs)) {
//
//					continue;
//				}
//				// 段内穿越
//				if (Utility.compareGt(fe, runningMin)) {
//					double tCross = (runningMin - b) / a;
//					if (Utility.compareGt(lastT, tCross)) {
//						newSegs.add(new Segment(tCross, lastT, 0.0, runningMin));
//					}
//					newSegs.add(new Segment(seg.start, tCross, a, b));
//
//					runningMin = fs;
//					lastT = s;
//					continue;
//				}
//
//				else {
//					// 比起始段更高
//					if (!Utility.compareEq(lastT, e)) {
//						newSegs.add(new Segment(e, lastT, 0, runningMin));
//
//					}
//					newSegs.add(new Segment(seg.start, seg.end, seg.slope, seg.intercept));
//
//					runningMin = fs;
//					lastT = s;
//				}
//
//			}
//		}
//		double prevStart = head.start;
//		if (Utility.compareLt(prevStart, lastT)) {
//			newSegs.add(new Segment(prevStart, lastT,0.0, runningMin));
//
//		}
//
//		if (Utility.compareGe(lastT, prevStart) && newSegs.size()==0) {
//			//suffix整个和prefix对称
//			newSegs.add(new Segment(prevStart, lastT,0.0, runningMin));
//		}
//
//		// （3）把 newSegs 倒转回正序，重建链表
////		Collections.reverse(newSegs);
//		Segment dummy = new Segment(0, 0, 0, 0), w = dummy;
//		for (int i=newSegs.size()-1;i>=0;i--) {
//			Segment seg=newSegs.get(i);
//			w.next = seg;
//			w = seg;
//		}
//		w.next = null;
//
//		// 更新 head/tail
//		head = dummy.next;
//		tail = w;
//		TimerManager.end("分段线性函数后缀最小化");
//	}

	// 尝试直接在当前链表修改,初步测试略微快一丢丢丢,10w次快了几毫秒
	// 正确性简单验证，还需要测试
	// segment初始化变少了，因为会复用，不过在规模40上时间变化不大
	public void minimizeSuffixInPlace() {
		Utility.debugCheckPWLFRightBound("minimizeSuffix.input", this);
		// 函数 反向取小
		if (head == null)
			return;
		TimerManager.start("分段线性函数后缀最小化");
		// （1）把链表扫一遍装到数组里，便于倒序访问
		List<Segment> segs = new ArrayList<>();
		for (Segment s = head; s != null; s = s.next) {
			segs.add(s);
		}
		int addTimes = 0;// 记录拼接段的次数
		Segment nextSeg = null;
		// （2）倒序处理，每次产出新段，先累到 newSegs（逆序）。
		double runningMin = Utility.curUpperBound;//Double.POSITIVE_INFINITY;
		double lastT = tail.end;
		for (int i = segs.size() - 1; i >= 0; i--) {
			Segment seg = segs.get(i);
			double s = seg.start, e = seg.end;
			double a = seg.slope, b = seg.intercept;
			double fs = a * s + b;
			double fe = a * e + b;

			if (Utility.compareLe(a, 0)) {
				// **单调递减段**：f(s) ≥ f(t) ≥ f(e)
				if (Utility.compareLe(runningMin, fe)) {
					// 整段都 ≥ runningMin → 全常数段
					continue;
				} else {
					if (!Utility.compareEq(lastT, e)) {
						Segment curSeg = SegmentPool.obtain(e, lastT, 0, runningMin);
						addTimes++;
						nextSeg = insertSegment(curSeg, nextSeg, addTimes);
						lastT = e;
					}
					runningMin = fe;

				}
			} else {
				// **单调递增段**：f(s) ≤ f(t) ≤ f(e)
				if (Utility.compareLe(runningMin, fs)) {

					continue;
				}
				// 段内穿越
				if (Utility.compareGt(fe, runningMin)) {
					double tCross = (runningMin - b) / a;
					if (Utility.compareGt(lastT, tCross)) {
						if(!Utility.compareEq(runningMin, Utility.curUpperBound)) {
							// 2026-05-14: suffix 同样只跳过上界假段；真实尾部最小值不能因 lastT==tail.end 被漏掉。
							// 详细讨论见 docs/plans/PiecewiseLinearFunction 专题记录。
							// 2025.5.10 历史注释保留：新加if，原思路同prefix最小化。
							Segment curSeg = SegmentPool.obtain(tCross, lastT, 0.0, runningMin);
							addTimes++;
							nextSeg = insertSegment(curSeg, nextSeg, addTimes);
						}
					}
//					Segment curSeg=new Segment(seg.start, tCross, a, b);
					Segment curSeg = seg;
					seg.end = tCross;// 直接复用
					addTimes++;
					nextSeg = insertSegment(curSeg, nextSeg, addTimes);

					runningMin = fs;
					lastT = s;
					continue;
				}

				else {
					// 比起始段更高
					if (!Utility.compareEq(lastT, e)) {
						Segment curSeg = SegmentPool.obtain(e, lastT, 0, runningMin);
						addTimes++;
						nextSeg = insertSegment(curSeg, nextSeg, addTimes);

					}
					Segment curSeg = seg;
					addTimes++;
					nextSeg = insertSegment(curSeg, nextSeg, addTimes);
					runningMin = fs;
					lastT = s;
				}

			}
			getSegmentNum();
		}
		double prevStart = head.start;
		if (Utility.compareLt(prevStart, lastT)) {
			Segment curSeg = SegmentPool.obtain(prevStart, lastT, 0.0, runningMin);
			addTimes++;
			nextSeg = insertSegment(curSeg, nextSeg, addTimes);

		}
		//同prefixMinimize，若整个函数比上界还大，设置为水平线

		if (Utility.compareGe(lastT, prevStart) && nextSeg == null) {
			// suffix整个和prefix对称
			Segment curSeg = SegmentPool.obtain(prevStart, lastT, 0.0, runningMin);
			addTimes++;
			nextSeg = insertSegment(curSeg, nextSeg, addTimes);
		}

		head = nextSeg;// 此时curSeg和nextSeg相等
		Utility.debugCheckPWLFRightBound("minimizeSuffix.output", this);
		TimerManager.end("分段线性函数后缀最小化");
	}

	public Segment insertSegment(Segment curSeg, Segment nextSeg, int addTimes) {

		curSeg.next = nextSeg;
		if (addTimes == 1)
			tail = curSeg;
		return curSeg;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (Segment cur = head; cur != null; cur = cur.next) {
			double gs = cur.start, ge = cur.end;
			sb.append(String.format("[%.3f,%.3f): %.3f·t+%.3f%n", gs, ge, cur.slope, cur.intercept));
		}
		return sb.toString();
	}

	private void trimToDomain() {
		// 这函数切除多余的可行域，感觉也就会用在shift函数和每个节点初始的惩罚函数（使用时间窗限制更紧一点）

		if (head == null || !Utility.compareLt(domainStart, domainEnd)) {
			head = tail = null;
			return;
		}
		boolean fromS = !Utility.compareGe(head.start, domainStart);
		boolean fromE = !Utility.compareLe(tail.end, domainEnd);
		if (!fromS && !fromE) {
			return;
			// 不需要修剪
		}

		if (fromS) {
			// 从头部修剪 [end <= domainStart)
			while (head != null && Utility.compareLe(head.end, domainStart)) {
				head = head.next;
			}
			if (head == null) {
				tail = null;
				return;
			}
			// 修剪头部部分重叠的 Segment
			if (Utility.compareLt(head.start, domainStart)) {
				head.start = domainStart;
			}
		}
		TimerManager.start("分段线性函数切割可行域");
		if (fromE) {
			// 从尾部修剪 [start >= domainEnd)
			Segment prev = null;
			Segment curr = head;
			while (curr != null) {
				if (Utility.compareLt(domainEnd, curr.start)) {
					// 2025.4.23 等于的时候先不砍？相当于最后一个段允许单个点，eg:[480,480]是个闭区间，前边其他都认为是开区间?
					if (prev == null) {
						head = tail = null;
					} else {
						prev.next = null;
						tail = prev;
					}
					break;
				}
				// 修剪尾部部分重叠的 Segment
				if (Utility.compareLe(curr.start, domainEnd) && Utility.compareLt(domainEnd, curr.end)) {
					// start=end的时候也做裁剪，此时相当于这个段被切为一个单点，认为是一个闭区间
					curr.end = domainEnd;
					tail = curr;
					curr.next = null;
					break;
				}
				prev = curr;
				curr = curr.next;
			}
			if (curr == null) {
				tail = prev;
			}
		}
		TimerManager.end("分段线性函数切割可行域");
		getSegmentNum();
	}

	/**
	 * 检查此函数是否完整占优 g。
	 * <p>
	 * 这里仅判断函数层面的完整占优：在 g 的整个定义域上，this(t) <= g(t)。
	 * 不处理 partial dominance，也不要求至少一点严格更优；BPC label 层若需要严格支配或 tie-breaking，
	 * 应在外层结合标签状态单独判断。
	 *
	 * @param g 要比较的函数（L2）。
	 * @return 如果此函数在 g 的完整定义域上不大于 g，则返回 true，否则返回 false。
	 */
	public boolean dominates(PiecewiseLinearFunction g) {
		if (this.head == null)
			return false;
		if (g.head == null)
			return true;

		if (Utility.compareGt(head.start, g.head.start)) {
			return false;
		}
		if (Utility.compareLt(tail.end, g.tail.end)) {
			// 2026-05-15: 完整占优必须覆盖 g 的整个定义域。
			// 原先这里尝试用右端两个点近似判断 tail.end 之后的区间，但这不能证明整段都被占优；
			// 后续 pricing 统一要求 [a,T] 右端一致，因此契约外的右端短缺直接判为不占优。
			return false;
		}
		// f定义域比g还小，肯定无法支配
		//TODO 不一定 可以认为f.end-g.end这段f仍然有值，前段则不太好处理，但这样需要去算函数某个点的值，O(n)
		//开头不好说了,如果使用upperbound砍的话，也不好对齐，因为精确里边存在负数，怎么做还得想想
		//然后这个dominate有这么几种办法：
		//1、最粗暴，原始做法，只看当前两者定义域内的比较
		//2、只对平缓的那一段做延伸，这个定义域应该没影响，正向传播的反正end定义域影响不大
		//3、对有斜率段做延伸（这个应该要小心，不能超过本身真实的定义域） 这个如果使用了bound砍掉函数的段的话可能要用，没砍的话应该不需要，就是本身的定义域
		//此外，这两个如果要使用2、3的话，就要区分前向和后向了
		
		//TODO 反向的函数其实可以看成右侧定义域始终无穷大,想想反向咋占优,先不管反向,反向的要做就先认为定义域固定的（其实可看作0-无穷，反向移动只有右侧在动，而右侧可视为正无穷）
		// 确定共同定义域
		double gStart = g.head.start;
		double gEnd = g.tail.end;

		// 检查此函数的值是否在所有点上 <= g 的值
		Segment p = this.head;
		Segment q = g.head;

		// 跳过共同定义域之前的段
		while (p != null && Utility.compareLt(p.end, gStart)) {
			p = p.next;
		}
		while (q != null && Utility.compareLt(q.end, gStart)) {
			q = q.next;
		}

		double cur = gStart;
		while (p != null && q != null && Utility.compareLt(cur, gEnd)) {
			double pEnd = p.end;
			double qEnd = q.end;
			double nxt = Math.min(Math.min(pEnd, qEnd), gEnd);

			if (Utility.compareLt(cur, nxt)) {
				// 在区间起点和终点评估以确保支配
				double l1Start = p.slope * cur + p.intercept;
				double l2Start = q.slope * cur + q.intercept;
				double l1End = p.slope * nxt + p.intercept;
				double l2End = q.slope * nxt + q.intercept;

				if (Utility.compareGt(l1Start, l2Start) || Utility.compareGt(l1End, l2End)) {
					return false; // L1 不支配 L2
				}
			}

			cur = nxt;

			// 移动指针
			if (Utility.compareEq(nxt, pEnd)) {
				p = p.next;
			}
			if (Utility.compareEq(nxt, qEnd)) {
				q = q.next;
			}
		}

		// 2026-05-15: 正常 [a,T] 连续链表输入下，右端覆盖检查已经保证能扫到 gEnd。
		// 这里再做一次兜底，避免链表被意外物理裁断或指针推进异常时没有扫完整个 g 定义域却返回 true。
		if (Utility.compareLt(cur, gEnd)) {
			return false;
		}
		return true;
	}

	/**
	 * 对 L2（this）进行“被 L1（g）支配区间置∞”的就地更新： 在公共定义域上，凡 L1(t) <= L2(t) 的区间， 将 L2
	 * 替换为前面段的最低值（保持非增性）。
	 */
	public boolean updateDominatedIntervals(PiecewiseLinearFunction g, Direction direction) {
		return updateDominatedIntervalsDetailed(g, direction) == TrimResult.EMPTY;
	}

	public TrimResult updateDominatedIntervalsDetailed(PiecewiseLinearFunction g, Direction direction) {
		if (direction == Direction.BACKWARD) {
			Utility.debugCheckPWLFLeftBound("updateDominatedIntervals.input.left", this);
			Utility.debugCheckPWLFLeftBound("updateDominatedIntervals.input.right", g);
		} else {
			Utility.debugCheckPWLFRightBoundPair("updateDominatedIntervals.input", this, g);
		}
		//TODO 2025.5.15感觉上dominate和这个可以合并，不然如果dominate为false的话，还得重新走一次这个流程?
		//先留着，不一定使用这种部分支配的操作
		//先修改这个函数，返回值为boolean,
		//该函数被调用，应该是f函数属于的label L1其他属性占优label L2(g),此时需要调用g.updateDominatedIntervals(f)，使用f更新L2的g
		//如果更新以后函数g空了，此时f完全占优g，返回true，此时标签L2可删除? 否则返回false, 函数g部分被干掉
		//TODO 待测试
		if (this.head == null) {
			// 如果 L2 为空，直接拿过来 L1 的整条链（nonono)
//			this.head = g.head;
//			this.tail = g.tail;//不是取小
			return TrimResult.EMPTY;
		}
		if (g.head == null) {
			// 如果 L1 为空，无事可做
			return TrimResult.NO_CHANGE;
		}
		// 公共定义域
		double start = Math.max(this.head.start, g.head.start);
		double end = Math.min(this.tail.end, g.tail.end);
		if (!Utility.compareLt(start, end))
			return TrimResult.NO_CHANGE;
		// 2026-06-12: partial dominance 热路径先做只读扫描。若没有任何正长度
		// 被支配区间，直接返回，避免为了扫描对齐拆分 segment 并 normalize。
		if (!hasDominatedInterval(g, start, end)) {
			return TrimResult.NO_CHANGE;
		}
		resetMinimum();

		// 跳到 L2（this）上第一个与 start 重叠的分段，并累计起始前最小值
		Segment p = this.head, prev = null;
		while (p != null && Utility.compareLt(p.end, start)) {
			prev = p;
			p = p.next;
		}
		// 3) 跳到 L1 第一段与 start 重叠处
		Segment q = g.head;
		while (q != null && Utility.compareLt(q.end, start)) {
			q = q.next;
		}

		double cur = start;
		boolean cross = false;
		double nxt = 0;

		// 线性扫描 [start, end), 只在 p、q 都未耗尽时扫描 [start, end)
		while (p != null && q != null && Utility.compareLt(cur, end)) {
			double pEnd = p.end;
			double qEnd = q.end;

			if (!cross) {
				nxt = Math.min(Math.min(pEnd, qEnd), end);
				// 存在交点回来重算
			} else {
				cross = false;
			}

			// 端点值
			double l2s = p.slope * cur + p.intercept;
			double l2e = p.slope * nxt + p.intercept;
			double l1s = q.slope * cur + q.intercept;
			double l1e = q.slope * nxt + q.intercept;

			// 判断端点是否均被支配
			if (Utility.compareLe(l1s, l2s) && Utility.compareLe(l1e, l2e)) {
				// 整段被支配 → 常数段替换
				Segment mid = replaceWithBigM(prev, p, cur, nxt);
				prev = mid;
				p = mid.next;
				cur = nxt;
			} else {
				// 检查段内交点
				double ds = p.slope - q.slope;
				double di = q.intercept - p.intercept;
				double tCross = Double.POSITIVE_INFINITY;
				if (!Utility.compareEq(ds, 0.0)) {
					double tc = di / ds;
					if (Utility.compareLt(cur, tc) && Utility.compareLt(tc, nxt)) {
						// 端点处不算
						tCross = tc;
					}
				}
				if (Utility.compareLt(tCross, nxt)) {
					// 段内有交点 → 先拆前半段 [cur,tCross)
					nxt = tCross;
					// 本次循环重做：先处理 [cur,nxt)
					cross = true;
					continue;
				}
				// 无交点或交点不在内部 → 保留原段，并更新 lastMin

				
				//TODO 这里边拆前半段和后半段应该都是用于当前函数左侧定义域和右侧定义域超过了g函数，从而需要再拼一次
				//感觉可以进一步简化，放在外边只做一次，while内部只处理共有部分
				// 若需要拆前半段
				if (!Utility.compareEq(p.start, cur)) {
					Segment left = SegmentPool.obtain(p.start, cur, p.slope, p.intercept);
					left.next = p;
					if (prev == null)
						this.head = left;
					else
						prev.next = left;
					p.start = cur;
					prev = left;
					continue;
				} else {
					prev = p;
				}
				// 拆前半段的 应该只可能出现在开头吧 应该是
				// 若需要拆后半段
				if (!Utility.compareEq(p.end, nxt)) {
					Segment right = SegmentPool.obtain(nxt, p.end, p.slope, p.intercept);
					right.next = p.next;
					p.next = right;
					p.end = nxt;
				}
				p = p.next;
				cur = nxt;
			}
			if (Utility.compareEq(qEnd, nxt)) {
				q = q.next;
//	            	            if (q == null) break;//可写可不写
			}
		}

		// 2026-05-14: partial dominance 里被支配区间用 big_M 标记，但 forward 函数
		// 的右端定义域仍应保持到全局 T。这里不能像通用 normalize() 那样先裁掉右侧
		// big_M 尾段，否则后续 prefix-min 没机会把右尾按前端最小值闭包补回来。
		// 注意 replaceWithBigM 按半开区间 [cur,nxt) 替换，nxt 端点归右侧片段；
		// 这里不为单个端点维护零长度段，和 pricing 中“单点 label 直接收尾”的约束一致。
		// 全部被支配时，左侧 head big_M 会被 normalize() 清空，仍会返回 true。
		normalize(direction);
		if (direction == Direction.BACKWARD) {
			Utility.debugCheckPWLFLeftBound("updateDominatedIntervals.output", this);
		} else {
			Utility.debugCheckPWLFRightBound("updateDominatedIntervals.output", this);
		}
		if(this.isEmpty()) return TrimResult.EMPTY;
		return TrimResult.PARTIAL;
	}

	private boolean hasDominatedInterval(PiecewiseLinearFunction g, double start, double end) {
		Segment p = this.head;
		while (p != null && Utility.compareLt(p.end, start)) {
			p = p.next;
		}
		Segment q = g.head;
		while (q != null && Utility.compareLt(q.end, start)) {
			q = q.next;
		}

		double cur = start;
		while (p != null && q != null && Utility.compareLt(cur, end)) {
			double nxt = Math.min(Math.min(p.end, q.end), end);
			double l2s = p.slope * cur + p.intercept;
			double l2e = p.slope * nxt + p.intercept;
			double l1s = q.slope * cur + q.intercept;
			double l1e = q.slope * nxt + q.intercept;
			if (Utility.compareLe(l1s, l2s) && Utility.compareLe(l1e, l2e)) {
				return true;
			}

			double ds = p.slope - q.slope;
			if (!Utility.compareEq(ds, 0.0)) {
				double tCross = (q.intercept - p.intercept) / ds;
				if (Utility.compareLt(cur, tCross) && Utility.compareLt(tCross, nxt)) {
					if (Utility.compareLe(l1s, l2s) || Utility.compareLe(l1e, l2e)) {
						return true;
					}
				}
			}

			cur = nxt;
			if (Utility.compareEq(p.end, cur)) {
				p = p.next;
			}
			if (Utility.compareEq(q.end, cur)) {
				q = q.next;
			}
		}
		return false;
	}

	/**
	 * 1) 裁掉头部 intercept==∞ 的“无穷”段 2) 合并相邻 slope/intercept 完全相同的段
	 * 3) 再调用 minimizePrefixInPlace() 保证整体非增
	 */
	//TODO 2025.5.10将M替换为全局上界，但不知道normalize使用时是否有影响，可能把某些平行线等于upperBound的给删了，导致函数为空
	//之后测试一下 ，normalize函数是否还有用？
	public void normalize(Direction direction) {
		if (direction == Direction.BACKWARD) {
			normalizeBackward();
		} else {
			normalizeForward();
		}
	}

	private void compactAdjacentEqualSegments() {
		if (head == null) {
			tail = null;
			return;
		}
		Segment cur = head;
		tail = head;
		while (cur.next != null) {
			if (Utility.compareEq(cur.slope, cur.next.slope) && Utility.compareEq(cur.intercept, cur.next.intercept)
					&& Utility.compareEq(cur.end, cur.next.start)) {
				cur.end = cur.next.end;
				cur.next = cur.next.next;
			} else {
				cur = cur.next;
				tail = cur;
			}
		}
		tail = cur;
	}

	private void normalizeForward() {
		Utility.debugCheckPWLFRightBound("normalize.forward.input", this);
		//这个的作用还在于将tail复位
		// 1) 删除头部无穷段
		while (head != null && Utility.isBigMValue(head.intercept)) {
			head = head.next;
		}
		if (head == null) {
			tail = null;
			return;
		}

		// 2026-05-14: 这里不再删除右侧 big_M 尾段。原来的 normalize() 会裁掉首尾 M 段，
		// 这在启发式中可以缩短无效定义域，但不适合后续 BPC pricing 中统一保持 [a,T]
		// 右端定义域的约束。特别是 updateDominatedIntervals 把被支配区间打成 big_M 后，
		// 若尾部 M 被提前物理删除，后续 minimizePrefixInPlace() 就没机会用前缀最小值
		// 把右尾闭包补回来。现在的语义是：左侧连续 M 仍然表示不可达起点并删除；
		// 右侧 M 保留到 prefix-min 阶段处理，从而保持 forward 函数右端到 T。
		Segment cur = head;
		tail = null;
		while (cur != null) {
			tail = cur;
			cur = cur.next;
		}
		// 妙啊

		// 3) 合并相邻完全相同的段
		cur = head;
		while (cur.next != null) {
			if (Utility.compareEq(cur.slope, cur.next.slope) && Utility.compareEq(cur.intercept, cur.next.intercept)) {
				cur.end = cur.next.end;
				cur.next = cur.next.next;
				if (cur.next == null)
					tail = cur;
			} else {
				cur = cur.next;
			}
		}

		// 4) 保证非增
		minimizePrefixInPlace();
		compactAdjacentEqualSegments();
		Utility.debugCheckPWLFRightBound("normalize.forward.output", this);
	}

	private void normalizeBackward() {
		Utility.debugCheckPWLFLeftBound("normalize.backward.input", this);
		if (head == null) {
			tail = null;
			return;
		}

		// 2026-05-15: backward 方向与 forward normalize 对称。
		// backward label 的固定端点是左端 0/domainStart，因此左侧 big_M 不能物理删除；
		// 右侧连续 big_M 尾段表示后续无法接上的过晚完成时间，可以在 suffix-min 前裁掉。
		Segment cur = head;
		Segment lastNonBigM = null;
		while (cur != null) {
			if (!Utility.isBigMValue(cur.intercept)) {
				lastNonBigM = cur;
			}
			cur = cur.next;
		}
		if (lastNonBigM == null) {
			head = tail = null;
			return;
		}
		lastNonBigM.next = null;
		tail = lastNonBigM;

		cur = head;
		while (cur.next != null) {
			if (Utility.compareEq(cur.slope, cur.next.slope) && Utility.compareEq(cur.intercept, cur.next.intercept)) {
				cur.end = cur.next.end;
				cur.next = cur.next.next;
				if (cur.next == null) {
					tail = cur;
				}
			} else {
				cur = cur.next;
			}
		}

		minimizeSuffixInPlace();
		compactAdjacentEqualSegments();
		Utility.debugCheckPWLFLeftBound("normalize.backward.output", this);
	}

	/**
	 * 将 p 段上的 [cur,nxt) 替换成常数段 lastMin，并保持链表连通。 prev 是 p 在拆分前的前驱（可能为 null）。
	 */
	private void replaceSegment(Segment prev, Segment p, double cur, double nxt, double lastMin) {
		// 拆前半段
		if (!Utility.compareEq(p.start, cur)) {
			Segment left = SegmentPool.obtain(p.start, cur, p.slope, p.intercept);
			left.next = p;
			if (prev == null)
				this.head = left;
			else
				prev.next = left;
			p.start = cur;
			prev = left;
		}
		// 拆常数段
		Segment mid = SegmentPool.obtain(cur, nxt, 0.0, lastMin);
		// 拆后半段或直接接续
		if (Utility.compareEq(p.end, nxt)) {
			mid.next = p.next;
			if (mid.next == null)
				tail = mid;
		} else {
			Segment right = SegmentPool.obtain(nxt, p.end, p.slope, p.intercept);
			right.next = p.next;
			mid.next = right;
		}
		if (prev == null)
			this.head = mid;
		else
			prev.next = mid;
	}

	/**
	 * 从 head 开始找到紧接在 [cur, ... ) 常数段前面的那一段，用于更新 prev 指针。
	 */
	// for updateDominatedIntervals
	private Segment replaceWithBigM(Segment prev, Segment p, double cur, double nxt) {
		// 和原 replaceSegment 类似，只是 intercept = Utility.big_M
		if (!Utility.compareEq(p.start, cur)) {
			Segment left = SegmentPool.obtain(p.start, cur, p.slope, p.intercept);
			left.next = p;
			if (prev == null)
				head = left;
			else
				prev.next = left;
			p.start = cur;
			prev = left;
		}
		Segment mid = SegmentPool.obtain(cur, nxt, 0.0, Utility.big_M);
		if (Utility.compareEq(p.end, nxt)) {
			mid.next = p.next;
		} else {
			Segment right = SegmentPool.obtain(nxt, p.end, p.slope, p.intercept);
			right.next = p.next;
			mid.next = right;
		}
		if (prev == null)
			head = mid;
		else
			prev.next = mid;
		if (mid.next == null)
			tail = mid;
		return mid;
	}

	// for mergeMinimum
	private Segment replaceWithSegment(Segment prev, Segment p, double cur, double nxt, double newSlope,
			double newIntercept) {
		// 和原 replaceSegment 类似，用 newSlope/newIntercept
		if (!Utility.compareEq(p.start, cur)) {
			Segment left = SegmentPool.obtain(p.start, cur, p.slope, p.intercept);
			left.next = p;
			if (prev == null)
				head = left;
			else
				prev.next = left;
			p.start = cur;
			prev = left;
		}
		Segment mid = SegmentPool.obtain(cur, nxt, newSlope, newIntercept);
		if (Utility.compareEq(p.end, nxt)) {
			mid.next = p.next;
		} else {
			Segment right = SegmentPool.obtain(nxt, p.end, p.slope, p.intercept);
			right.next = p.next;
			mid.next = right;
		}
		if (prev == null)
			head = mid;
		else
			prev.next = mid;
		if (mid.next == null)
			tail = mid;
		return mid;
	}

	/**
	 * 将 this（L1）与 g（L2）合并，在它们的定义域并集上取逐点最小值： 如果 L2 在某段 ≤ L1，则就地用 L2 的片段替换这部分 L1。
	 */
	// TODO 2025.5.15: 这个函数最初不是按“任意两个分段线性函数取下包络”的通用工具写的，
	// 而是按标签函数合并场景写的。这里默认两个函数至少存在公共定义域，并且 forward 函数
	// 的右端公共上界相同；反向函数若复用类似逻辑，则应有对称的左端公共边界。
	//
	// 2026-05-14: 重新梳理 mergeMinimum 的输入前提和边界风险。前面测试中看到的 5 类问题，
	// 本质都来自“把该函数当作通用下包络合并器”使用，而当前 pricing/标签函数语义不应这样用。
	//
	// 1) g 完全在 this 左边。例如 this=[20,40]，g=[0,10]。此时 start=20,end=10，
	//    公共区间不存在，但当前左前缀截取逻辑会一直推进 gh，直到 gh=null，随后访问
	//    gh.start 构造 lastGSegBeforeDomain，可能触发 NPE。
	// 2) g 完全在 this 右边。例如 this=[0,10]，g=[20,40]。此时扫描循环不会进入，
	//    但 q!=null 的右尾拼接会执行 q.start=end，把 g 从 [20,40] 人为扩成 [10,40]，
	//    进而让本来不存在的 [10,20] 参与后续 normalize/prefix-min。
	// 3) 左端错位但有公共区间。例如 this=[20,T]，g=[10,T]。这是当前函数真正要支持的
	//    正常情况：先截取 g 在 [10,20] 的左前缀，再在 [20,T] 上逐段取小，最后把前缀
	//    接回 head。这里的风险主要是链表拼接错位，所以 prevGH 必须记录刚刚跳过的上一段。
	// 4) 右端错位但有公共区间。例如 this=[0,50]，g=[0,80]。当前代码会在公共区间
	//    处理完以后把 g 的右尾接进来。这个逻辑只有在 q.start<=end 时才合理；若 q.start>end，
	//    就会和第 2 类一样凭空扩展定义域。
	// 5) 只有一个单点交集。例如 this=[10,T]，g=[T,T]。数学上有交集，但公共部分没有
	//    正长度区间，while(cur<end) 不会进入，所以不会真正比较 T 点处两者谁更小。若要
	//    严格支持这种输入，就必须维护长度为 0 的单点段，后续 add/shift/dominance 都会
	//    变复杂。因此当前策略是不让这种 label 进入 mergeMinimum：pricing 中若 label 的
	//    函数定义域退化为 [T,T]，直接尝试连接终点生成完整列，不再参与普通 merge/dominance。
	//
	// 基于 2011 年 soft time window pricing 的标签函数语义，以及当前 TWET pricing 的预期设定，
	// 进入这里的 forward 函数应都满足 [a,T] 形式：左端 a 可以不同，右端都取到全局最大时间 T。
	// 即使某些区间在 dominance 后被删掉，也应通过 big_M 水平段或左/右端取小操作保持整体定义域
	// 仍覆盖到 T，而不是让函数变成任意零散定义域。这样前 1、2、4 类问题在有效输入下不会发生，
	// 第 3 类是正常输入，第 5 类由 pricing 层提前拦截。
	//
	// 后续如果要启用运行期检查，可打开下面的 mergeMinimumDomainViolationCount 字段和方法内
	// 的调试块，用来验证每次进入的两个函数是否满足“有正长度交集、右端同为 T、没有单点退化”
	// 这些约束。
//	private static long mergeMinimumDomainViolationCount = 0;
	
	public void mergeMinimum(PiecewiseLinearFunction g, Direction direction) {
		mergeMinimum(g, direction, false);
	}

	public boolean mergeMinimum(PiecewiseLinearFunction g, Direction direction, boolean reportChanged) {
		return mergeMinimum(g, direction, reportChanged, null);
	}

	public MergeResult mergeMinimumWithChangeHull(PiecewiseLinearFunction g, Direction direction) {
		MergeResult result = new MergeResult();
		mergeMinimum(g, direction, true, result);
		return result;
	}

	private boolean canSkipMergeMinimum(PiecewiseLinearFunction g) {
		if (this.head == null || g == null || g.head == null) {
			return false;
		}
		if (Utility.compareLt(g.head.start, this.head.start) || Utility.compareGt(g.tail.end, this.tail.end)) {
			return false;
		}
		double start = Math.max(this.head.start, g.head.start);
		double end = Math.min(this.tail.end, g.tail.end);
		if (!Utility.compareLt(start, end)) {
			return false;
		}
		Segment p = this.head;
		while (p != null && Utility.compareLe(p.end, start)) {
			p = p.next;
		}
		Segment q = g.head;
		while (q != null && Utility.compareLe(q.end, start)) {
			q = q.next;
		}
		double cur = start;
		while (p != null && q != null && Utility.compareLt(cur, end)) {
			double nxt = Math.min(Math.min(p.end, q.end), end);
			double diffStart = (q.slope - p.slope) * cur + (q.intercept - p.intercept);
			double diffEnd = (q.slope - p.slope) * nxt + (q.intercept - p.intercept);
			if (Utility.compareLt(diffStart, 0.0) || Utility.compareLt(diffEnd, 0.0)) {
				return false;
			}
			cur = nxt;
			if (Utility.compareEq(p.end, cur)) {
				p = p.next;
			}
			if (Utility.compareEq(q.end, cur)) {
				q = q.next;
			}
		}
		return true;
	}

	private boolean mergeMinimum(PiecewiseLinearFunction g, Direction direction, boolean reportChanged,
			MergeResult changeHull) {
		if (direction == Direction.FORWARD) {
			Utility.debugCheckPWLFMergeContract("mergeMinimum.input", this, g);
		} else {
			Utility.debugCheckPWLFLeftBound("mergeMinimum.input.left", this);
			Utility.debugCheckPWLFLeftBound("mergeMinimum.input.right", g);
		}
		// 2026-05-26: 当前正式调用方把 label/frontier/envelope 缓存作为右参数传入，
		// 因此 mergeMinimum 不能消耗或改写输入函数。2011 partial-dominance 那类
		// “同一 S 的两个 label 直接取下包络并删除一个 label”理论上可以允许破坏式输入，
		// 但合并后下包络已混合两条路径，难以恢复真实列序列，当前不采用这种语义。
		// 如果 L1 为空，直接变成 L2 的拷贝
		if (this.head == null) {
			if (g.head != null) {
				if (changeHull != null) {
					changeHull.markChanged(g.head.start, g.tail.end);
				}
				PiecewiseLinearFunction copied = g.copy();
				this.head = copied.head;
				this.tail = copied.tail;
				return true;
			}
			return false;
		}
		// 如果 L2 为空，不用动
		if (g.head == null) {
			return false;
		}
		// 2026-06-16: completion bound 中大量候选函数只是在公共定义域上不优，
		// 原实现仍会 copy 右函数、拆分链表并 normalize。这里先做只读扫描；若 g
		// 没扩展定义域，且在所有正长度公共区间上都不低于当前 envelope，则直接返回。
		if (canSkipMergeMinimum(g)) {
			return false;
		}
		boolean changed = false;
		double originalHeadStart = this.head.start;
		double originalTailEnd = this.tail.end;
		// 2026-05-25: mergeMinimum 内部会截断并拼接右参数的 Segment 链。
		// dominance graph 会把 label/frontier/envelope 缓存作为右参数传入，因此这里必须先复制，
		// 避免把右参数的物理 head/tail 改坏，造成 domainStart 与 head.start 不一致。
		g = g.copy();

		resetMinimum();
		// 1) 定位公共定义域 这里两个函数取小因该是要取并集的  不能取交集
		double start = Math.max(this.head.start, g.head.start);
		double end = Math.min(this.tail.end, g.tail.end);
		if (!Utility.compareLt(start, end)) {
			throw new IllegalArgumentException("mergeMinimum requires positive overlap: this=[" + this.head.start
					+ "," + this.tail.end + "], g=[" + g.head.start + "," + g.tail.end + "]");
		}
//		// 2026-05-14: pricing 调试开关。mergeMinimum 的有效输入应满足：
//		// 1. 两个函数存在正长度公共区间，即 max(a1,a2)<min(end1,end2)；
//		// 2. forward 标签函数右端应同为全局最大时间 T，因此 this.tail.end 与 g.tail.end 应相等；
//		// 3. 输入函数本身不应退化成 [T,T] 这种单点 label。单点 label 应在 pricing 层直接收尾到终点。
//		// 这里暂时注释掉，不改变当前启发式行为；后续接入 BPC pricing 时可以打开，用于统计异常输入。
//		boolean noPositiveOverlap = !Utility.compareLt(start, end);
//		boolean differentRightBound = !Utility.compareEq(this.tail.end, g.tail.end);
//		boolean thisSinglePoint = Utility.compareEq(this.head.start, this.tail.end);
//		boolean gSinglePoint = Utility.compareEq(g.head.start, g.tail.end);
//		if (noPositiveOverlap || differentRightBound || thisSinglePoint || gSinglePoint) {
//			mergeMinimumDomainViolationCount++;
//			System.out.println("[PWLF.mergeMinimum] domain violation #" + mergeMinimumDomainViolationCount
//					+ ": this=[" + this.head.start + "," + this.tail.end + "]"
//					+ ", g=[" + g.head.start + "," + g.tail.end + "]"
//					+ ", overlap=[" + start + "," + end + "]"
//					+ ", noPositiveOverlap=" + noPositiveOverlap
//					+ ", differentRightBound=" + differentRightBound
//					+ ", thisSinglePoint=" + thisSinglePoint
//					+ ", gSinglePoint=" + gSinglePoint);
//		}
		//不对，无交集是可以合并的 取小操作 所以前边取了并集以后相当于扩充了定义域
		//但函数定义域不存在的地方需特殊处理
		
		Segment gHeads=null ;//截取g函数前边可能多出来的一段
		Segment lastGSegBeforeDomain=null;
		Segment prevGH=null;
		if(Utility.compareLt(g.head.start,start)) {
			//截取g.head.start---start这部分的段
			gHeads=g.head;
			Segment gh=gHeads;
			
			while(Utility.compareLe(gh.end, start)) {
				// 2026-05-14: prevGH 必须记录“刚刚跳过的前一段”，后面才能把
				// 截出来的 [gh.start, start] 正确接到 g 的左侧前缀末尾。原来先
				// gh=gh.next 再 prevGH=gh，会把 prevGH 记成下一段，导致拼链错位。
				prevGH=gh;
				gh=gh.next;
			}
			lastGSegBeforeDomain=SegmentPool.obtain(gh.start, start, gh.slope, gh.intercept);
			if (reportChanged && Utility.compareLt(g.head.start, originalHeadStart)) {
				changed = true;
			}
			if (changeHull != null) {
				changeHull.markChanged(g.head.start, originalHeadStart);
			}
			//不管是否相等了gh.start=start
			
			//先记录下来，while以后再拼上去，不然直接拼head后边被改变了
			
		}
		
		
		//现在下边while的处理是基于当前函数做的，所以只能处理当前函数定义域比g函数定义域更大的情况，当g定义域比f更大时，需要将多出来的部分截取下来并拼接导公共部分
		//只需要处理g再左侧定义域超出的部分，其他的while处处理
		// 2) 跳到第一个重叠的片段
		Segment p = this.head, prev = null;
		while (p != null && Utility.compareLt(p.end, start)) {
			prev = p;
			p = p.next;
		}
		Segment q = g.head;
		while (q != null && Utility.compareLt(q.end, start)) {
			q = q.next;
		}
		
		
		

		double cur = start;
		boolean cross = false;
		double nxt = 0;

		// 3) 逐段扫描拆分
		while (p != null && q != null && Utility.compareLt(cur, end)) {
			double pEnd = p.end, qEnd = q.end;
			if (!cross) {
				nxt = Math.min(Math.min(pEnd, qEnd), end);
			} else {
				// 上轮拆分到交点后重做，不再重新取交点
				cross = false;
			}

			// 计算端点值
			double f1s = p.slope * cur + p.intercept;
			double f1e = p.slope * nxt + p.intercept;
			double f2s = q.slope * cur + q.intercept;
			double f2e = q.slope * nxt + q.intercept;

			boolean gNoWorse = Utility.compareLe(f2s, f1s) && Utility.compareLe(f2e, f1e);
			boolean gStrictlyBetter = Utility.compareLt(f2s, f1s) || Utility.compareLt(f2e, f1e);
			if (gNoWorse && gStrictlyBetter) {
				if (reportChanged && Utility.compareLt(cur, nxt)) {
					changed = true;
				}
				if (changeHull != null) {
					changeHull.markChanged(cur, nxt);
				}
				// L2 整段支配 L1 --> 用 L2 片段替换 this 上 [cur, nxt)
//	                replaceSegment(prev, p, cur, nxt, q.slope, q.intercept);
				// 更新 prev/p 到刚插入的常量段后
				Segment mid = replaceWithSegment(prev, p, cur, nxt, q.slope, q.intercept);
				prev = mid;
				p = mid.next;
				cur = nxt;
			} else {
				// 要么 L1 支配，要么需要拆交点
				double ds = p.slope - q.slope;
				double di = q.intercept - p.intercept;
				double tc = Double.POSITIVE_INFINITY;
				if (!Utility.compareEq(ds, 0.0)) {
					double crossT = di / ds;
					if (Utility.compareLt(cur, crossT) && Utility.compareLt(crossT, nxt)) {
						tc = crossT;
					}
				}
				if (Utility.compareLt(tc, nxt)) {
					// 段内有交点，先拆前半段 [cur, tc)
					nxt = tc;
					cross = true;
					continue;
					// 本轮只把 L1 原片段保留在 [cur, nxt)，不做替换
				}
				// 6) 无交点也不替换 → 保留 this 的这一段 [cur,nxt)
				// 按照 update 中的逻辑：先拆头，再拆尾，推进指针
				if (!Utility.compareEq(p.start, cur)) {
					Segment left = SegmentPool.obtain(p.start, cur, p.slope, p.intercept);
					left.next = p;
					if (prev == null)
						head = left;
					else
						prev.next = left;
					p.start = cur;
					prev = left;
					continue;
				} else {
					prev = p;
				}
				if (!Utility.compareEq(p.end, nxt)) {
					Segment right = SegmentPool.obtain(nxt, p.end, p.slope, p.intercept);
					right.next = p.next;
					p.next = right;
					p.end = nxt;
				}
				// 推进
				p = p.next;
				cur = nxt;
			}

			// 7) 同时推进 g 的指针
			if (Utility.compareEq(qEnd, nxt)) {
				q = q.next;
			}
		}

		if (q != null) {
			if (reportChanged && Utility.compareGt(g.tail.end, originalTailEnd)) {
				changed = true;
			}
			if (changeHull != null) {
				changeHull.markChanged(originalTailEnd, g.tail.end);
			}
			// 函数g末尾还有一段，需要合并进来
			if(Utility.compareEq(q.slope, prev.slope)&&Utility.compareEq(q.intercept, prev.intercept)) {
				prev.end=q.end;
				prev.next=q.next;
				//tail的复位最后重新刷一次吧
			}
			else {
				q.start = end;// 此时相当于把函数g破坏了，但g也没用了
				prev.next = q;
				tail = g.tail;
				// 直接接过来
			}
			
		}
		if(lastGSegBeforeDomain!=null) {
			//如果进入，肯定g函数前边有一段
			if(prevGH!=null) prevGH.next=lastGSegBeforeDomain;
			else gHeads=lastGSegBeforeDomain;
			if((Utility.compareEq(lastGSegBeforeDomain.slope, head.slope))&&(Utility.compareEq(lastGSegBeforeDomain.intercept, head.intercept))) {
				lastGSegBeforeDomain.next=head.next;
				lastGSegBeforeDomain.end=head.end;
			}else {
				lastGSegBeforeDomain.next=head;
			}
			
			head=gHeads;
		}
		
		// 8) 末尾合并相邻同值同斜率片段、保持非增
		//这个进入的时候还是有可能存在连续段的斜率和intercept是相同的，比如this函数最后一个和g相邻的部分，此时相当于前半部分取小，再和最后剩下的拼接，还是原来的东西
		normalize(direction);
		if (direction == Direction.BACKWARD) {
			Utility.debugCheckPWLFLeftBound("mergeMinimum.output", this);
		} else {
			Utility.debugCheckPWLFRightBound("mergeMinimum.output", this);
		}
		return changed;
	}

	public void release() {
		if(!Configure.SegmentPool) return;
		SegmentPool.release(this.head);
	}
	
	/**
	 * 实验版双指针扫描下包络。
	 * <p>
	 * 2026-05-15: 该函数不作为 BPC pricing 的正式入口，不参与 forward/backward 方向化改造。
	 * 后续正式合并统一使用 {@link #mergeMinimum(PiecewiseLinearFunction, Direction)}。
	 */
	@Deprecated
	public void mergeMinimum2(PiecewiseLinearFunction g) {
		Utility.debugCheckPWLFMergeContract("mergeMinimum2.input", this, g);
		// 1) 空函数处理
		if (this.head == null) {
			this.head = g.head;
			this.tail = g.tail;
			return;
		}
		if (g.head == null)
			return;

		resetMinimum();
		// 2) 公共定义域
		double commonS = Math.max(this.head.start, g.head.start);
		double commonE = Math.min(this.tail.end, g.tail.end);

		// 3) 前缀：处理公共域之前的段
		PiecewiseLinearFunction res = new PiecewiseLinearFunction(domainStart, domainEnd);
		Segment p = this.head, q = g.head;
		// copy this 里在 [head.start, commonS) 的段
		while (p != null && Utility.compareLe(p.end, commonS)) {
			res.addSegment(p);
			p = p.next;
		}
		// copy g 里在 [head.start, commonS) 的段
		while (q != null && Utility.compareLe(q.end, commonS)) {
			res.addSegment(q);
			q = q.next;
		}
		// 截断横跨 commonS 的那段
		if (p != null && Utility.compareLt(p.start, commonS)) {
			res.addSegment(p.start, commonS, p.slope, p.intercept);
			p.start = commonS;
		}
		if (q != null && Utility.compareLt(q.start, commonS)) {
			res.addSegment(q.start, commonS, q.slope, q.intercept);
			q.start = commonS;
		}

		// 4) 公共域：双指针扫描
		while (p != null && q != null) {

			// 4.1) 计算当前重叠区间 [lo,hi)
			double lo = Math.max(p.start, q.start);
			double hi = Math.min(Math.min(p.end, q.end), commonE);

			// 4.2) 段内交点拆分
			double a1 = p.slope, b1 = p.intercept;
			double a2 = q.slope, b2 = q.intercept;
			if (!Utility.compareEq(a1, a2)) {
				double tc = (b2 - b1) / (a1 - a2);
				if (Utility.compareLt(lo, tc) && Utility.compareLt(tc, hi)) {
					// [lo,tc) 上取最小
					double fLo = a1 * lo + b1, fTc = a1 * tc + b1;
					double gLo = a2 * lo + b2, gTc = a2 * tc + b2;
					boolean useQ = Utility.compareLe(gLo, fLo) && Utility.compareLe(gTc, fTc);
					double a = useQ ? a2 : a1;
					double b = useQ ? b2 : b1;
					res.addSegment(lo, tc, a, b);
					lo = tc;
				}
			}

			// 4.3) 在 [lo,hi) 上端点比较
			double fLo = p.slope * lo + p.intercept, fHi = p.slope * hi + p.intercept;
			double gLo = q.slope * lo + q.intercept, gHi = q.slope * hi + q.intercept;
			boolean useQ = Utility.compareLe(gLo, fLo) && Utility.compareLe(gHi, fHi);
			double a = useQ ? q.slope : p.slope;
			double b = useQ ? q.intercept : p.intercept;
			res.addSegment(lo, hi, a, b);

			// 4.4) 推进并截断
			if (Utility.compareLt(p.end, q.end)) {
				p = p.next;
				q.start = hi;
			} else if (Utility.compareLt(q.end, p.end)) {
				q = q.next;
				p.start = hi;
			} else {
				p = p.next;
				q = q.next;
			}
		}

		// 5) 后缀：公共域之后的段直接拷贝
		while (p != null) {
			if (Utility.compareGe(p.start, commonE)) {
				res.addSegment(p.start, p.end, p.slope, p.intercept);
			}
			p = p.next;
		}
		while (q != null) {
			if (Utility.compareGe(q.start, commonE)) {
				res.addSegment(q.start, q.end, q.slope, q.intercept);
			}
			q = q.next;
		}

		// 6) 赋值回 this 并归一化
		this.head = res.head;
		this.tail = res.tail;
		normalize(Direction.FORWARD);
		Utility.debugCheckPWLFRightBound("mergeMinimum2.output", this);
	}

	/**
	 * 专门用于 mergeMinimum：把 p 上 [cur,nxt) 块替换成 slope/intercept，并保持链表连通。 prev 是 p
	 * 拆分前的前驱（可能为 null）。
	 */
	private void replaceSegment(Segment prev, Segment p, double cur, double nxt, double newSlope, double newIntercept) {
		// 拆出前半段
		if (!Utility.compareEq(p.start, cur)) {
			Segment left = SegmentPool.obtain(p.start, cur, p.slope, p.intercept);
			left.next = p;
			if (prev == null)
				head = left;
			else
				prev.next = left;
			p.start = cur;
			prev = left;
		}
		// 构造替换段
		Segment mid = SegmentPool.obtain(cur, nxt, newSlope, newIntercept);
		// 拆出后半段或直接接续
		if (Utility.compareEq(p.end, nxt)) {
			mid.next = p.next;
		} else {
			Segment right = SegmentPool.obtain(nxt, p.end, p.slope, p.intercept);
			right.next = p.next;
			mid.next = right;
		}
		if (prev == null)
			head = mid;
		else
			prev.next = mid;
		if (mid.next == null)
			tail = mid;
	}

	public double[] findMinimal(boolean convex, boolean fromLeft) {
		// 对任意一个函数，遍历寻找全局最小值及对应的 t 值。
		// convex=false 表示函数不一定凸；fromLeft=true 表示返回最左侧最小点，否则返回最右侧最小点。
		// 2026-05-13: 原实现只扫描每段 start，再单独扫描 tail.end。对连续函数这样足够，
		// 因为上一段 end 的左极限等于下一段 start 的值。并且当前 normalize() 是 forward
		// normalize，内部会调用 minimizePrefixInPlace()，函数整体单调不增；在这种语义下，
		// 旧逻辑通常也是有效的，最小值会出现在某段 start 或 tail.end。
		// 2026-05-14: backward 函数当前不走 normalize()，而是直接 minimizeSuffixInPlace()。
		// 如果 backward 函数由连续惩罚函数递推得到并保持连续、单调不减，旧逻辑一般也没问题；
		// 即便存在向上的 vertical gap，只看最小值时左侧已有更小值，通常仍能找到正确成本。
		// 但若后续对 backward label 也做合并取小、占优裁剪或其他集合包络操作，可能产生
		// vertical gap：上一段 end 的左极限比下一段 start 更小。此时风险主要不在最小成本，
		// 而在 fromRight 要找“最右侧最优时间点”，只看 start 可能返回得过早。
		// 因此这里先不区分 forward/backward，
		// 直接扫描每段 start 和按当前段计算的 end 值，优先保证端点语义稳健。
		// 若后续性能成为瓶颈，再按函数方向和凸性做加速。
		// 2026-05-14: 当前 HEU 调用基本都是在三段/两段拼接评价的最后一步使用 findMinimal，
		// 也就是函数已经构造完以后才取最小值。这里保留左右最优点缓存是安全的；但如果后续
		// 改成“先 findMinimal，再对同一个对象原地 minimize/normalize/update，再次 findMinimal”，
		// 必须在原地修改后调用 resetMinimum()，否则可能返回旧缓存。这个是使用约束，不是当前
		// findMinimal 计算逻辑的错误。
		double min = Utility.big_M;// 这里必须用 big_M，不能用 curUpperBound；部分合并逻辑比较的是成本差值。
		double leftX = 0, rightX = 0;

		if (minValuePairsLeft != null && fromLeft) {
			return minValuePairsLeft;
		}
		if (minValuePairsRight != null && !fromLeft) {
			return minValuePairsRight;
		}

		if (head == null) {
			return new double[] {min, -1};
		}
		TimerManager.start("分段线性函数最小化");
		for (Segment p = head; p != null; p = p.next) {
			double startValue = p.getValue(p.start);
			if (Utility.compareLt(startValue, min)) {
				min = startValue;
				leftX = rightX = p.start;
			} else if (Utility.compareEq(startValue, min)) {
				rightX = p.start;
			}

			double endValue = p.getValue(p.end);
			if (Utility.compareLt(endValue, min)) {
				min = endValue;
				leftX = rightX = p.end;
			} else if (Utility.compareEq(endValue, min)) {
				rightX = p.end;
			}
		}
		this.minValuePairsLeft = new double[2];
		this.minValuePairsRight = new double[2];
		minValuePairsLeft[0] = min;
		minValuePairsLeft[1] = leftX;
		minValuePairsRight[0] = min;
		minValuePairsRight[1] = rightX;
		TimerManager.end("分段线性函数最小化");
		if (fromLeft)
			return minValuePairsLeft;
		else
			return minValuePairsRight;

	}

	public void resetMinimum() {
		minValuePairsLeft = null;
		minValuePairsRight = null;
	}

}

// 如果需要限制函数的定义域，即不管如何位移定义域都是固定的，感觉不在函数里处理了？单独写个函数处理？
// 函数操作的时候整个定义域限制在[0,T], 位移以后可能超出去，不管这个超出去多少，当需要对偏移以后的函数限制定义域做dominance的时候？
// 似乎只有在dominance的时候定义域才会比较重要，其他时候影响不会很大？先这样，应该不会慢太多吧.

// 考虑处理如下：
// 1、label传递的过程中，不管前向后向，函数都是往一个方向平移，此时可能定义域超出全局上界（例如前移过程中，左端开始区间为当前执行任务的最小完成时间，右端会超出），考虑初始定义的时候给一个全局定义域，不管如何add，add后的函数超出定义域的部分被删除
// 2、一方面，当标签到达某个节点和惩罚函数相加的时候，此时该节点会有一个时间窗，通过对惩罚函数定义域的设定以及两个函数的相加，得到的函数就是在这个定义域上的
