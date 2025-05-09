// PiecewiseLinearFunction.java
package Common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.List;

import Common.Utility.TimerManager;


//假设各段之间区间连续的
public class PiecewiseLinearFunction {
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
			//感觉似乎不判断也行，使用的时候都是基于当前段的边界算的
			return slope*t+intercept;
		}
	}

	public Segment head, tail;
	public double domainStart = 0; // 全局定义域起点
	public double domainEnd = Double.POSITIVE_INFINITY; // 全局定义域终点
	public double[] minValuePairsLeft;//存储函数全局最小cost以及对应的函数取值 [0]=cost [1]=t,t取所有取最小cost的t中最左边的
	public double[] minValuePairsRight;//存储函数全局最小cost以及对应的函数取值 [0]=cost [1]=t,t取所有取最小cost的t中最右边的
	//这俩copy的时候不复制，从而对那些操作中基于一个复制的函数做操作的，不需要处理
	//比如shift\add
	//对前向取小和后向取小，暂时不处理，感觉应该不会在这里冲突
	//基本应该只会对同一个函数多次取它的最小值，变化以后取最小的应该没啥
	
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
		//TODO 这个判断后期关了吧 
		TimerManager.end("分段线性函数初始化");
	}

	public PiecewiseLinearFunction copy() {
		PiecewiseLinearFunction res = new PiecewiseLinearFunction(domainStart, domainEnd);
		if (this.head == null)
			return res;
		TimerManager.start("分段线性函数复制");
		res.head = new Segment(head.start, head.end, head.slope, head.intercept);
		Segment q = res.head;
		Segment p = head.next;
		while (p != null) {
			q.next = new Segment(p.start, p.end, p.slope, p.intercept);
			p = p.next;
			q = q.next;
		}
		res.tail = q;
		TimerManager.end("分段线性函数复制");
		return res;
	}

	// 设置定义域
	public PiecewiseLinearFunction setDomain(double domainStart, double domainEnd) {
		//不会改变当前函数，会复制一个产生新的，不需要copy()以后 
		TimerManager.start("分段线性函数设置定义域");
		PiecewiseLinearFunction res = this.copy();
		if (Utility.compareLe(domainStart, domainEnd)) {
			res.domainStart = domainStart;
			res.domainEnd = domainEnd;
			res.trimToDomain();
		} else {
			throw new IllegalArgumentException("Invalid domain: start >= end");
		}
		res.resetDomain(this.domainStart,this.domainEnd);
		//TODO 是否需要
		
		TimerManager.end("分段线性函数设置定义域");
		return res;
	}
	public void resetDomain(double domainStart,double domainEnd) {
		this.domainStart=domainStart;
		this.domainEnd=domainEnd;
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

		Segment seg = new Segment(start, end, slope, intercept);
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

	public PiecewiseLinearFunction shift(double delta) {
		TimerManager.start("分段线性函数shift");
		PiecewiseLinearFunction res = copy();
		Segment p = res.head;
		while (p != null) {
			p.start += delta;
			p.end += delta;
			p.intercept -= p.slope * delta;
			p = p.next;
		}
		res.trimToDomain();
		TimerManager.end("分段线性函数shift");
		return res;
	}

	public double evaluate(double t) {
		
		if (head == null)
			return Utility.big_M;
		if (!(Utility.compareLe(t, tail.end) && Utility.compareLe(head.start, t)))
			return Utility.big_M;
		TimerManager.start("分段线性函数evaluate");
		for (Segment cur = head; cur != null; cur = cur.next) {
			
			if (Utility.compareGe(t, cur.start) && Utility.compareLt(t, cur.end)) {
				TimerManager.end("分段线性函数evaluate");
				return cur.slope * t + cur.intercept;
			}
		}
		if (Utility.compareEq(t, tail.end)) {
			TimerManager.end("分段线性函数evaluate");
			return tail.slope * t + tail.intercept;
		}
		
		throw new IllegalArgumentException("t out of domain: " + t);
		
	}

	private double slopeAt(double t) {
		if (head == null)
			return Utility.big_M;
		if (!(Utility.compareLe(t, tail.end) && Utility.compareLe(head.start, t)))
			return Utility.big_M;

		for (Segment cur = head; cur != null; cur = cur.next) {
			if (Utility.compareGe(t, cur.start) && Utility.compareLt(t, cur.end)) {
				return cur.slope;
			}
		}
		if (Utility.compareEq(t, tail.end)) {
			return tail.slope;
		}
		throw new IllegalArgumentException("t out of domain: " + t);
	}

	public PiecewiseLinearFunction add(PiecewiseLinearFunction g) {
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
		return res;
	}
	// 未检查输入函数的定义域是否连续。

	public void minimizePrefixInPlace() {
		if (head == null)
			return;
		TimerManager.start("分段线性函数前缀最小化");
		
		double runningMin = Double.POSITIVE_INFINITY;
		double prevT = head.start;
		Segment dummy = new Segment(0, 0, 0, 0), write = dummy;

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
						write.next = new Segment(prevT, s, 0.0, runningMin);
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
						write.next = new Segment(prevT, tCross, 0.0, runningMin);
						write = write.next;
					}
					write.next = new Segment(tCross, seg.end, a, b);
					write = write.next;
					runningMin = fge;
					prevT = e;
					continue;
				} else {
					// 递减且开头比runningMin更小
					if (!Utility.compareEq(prevT, s)) {
						write.next = new Segment(prevT, s, 0.0, runningMin);
						write = write.next;
					}
					write.next = new Segment(seg.start, seg.end, seg.slope, seg.intercept);
					write = write.next;
					runningMin = fge;
					prevT = e;
				}

			}

		}
		double lastEnd = tail.end;
		if (Utility.compareLt(prevT, lastEnd)) {

			write.next = new Segment(prevT, lastEnd, 0.0, runningMin);
			write = write.next;
			
		}

		if (Utility.compareLe(prevT, lastEnd) && dummy.next == null) {
			// 只包含一个点且该段首尾相同，是个单点
			// 2025.4.23 这个地方是相对上边多加的，应该只会当一个段有一个单点的时候才会进入这里
			// 或者这段if也可以不加入，直接Cmax在算好的基础上+1也行，但不知道会不会别的地方出问题，感觉应该不会，先这样
			// 这段不能直接加在上边改成le，不然任何情况下都会在末尾产生一个单点
			write.next = new Segment(prevT, lastEnd, 0.0, runningMin);
			write = write.next;
		}

		this.head = dummy.next;
		this.tail = write;
		TimerManager.end("分段线性函数前缀最小化");
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
	//这个使用列表辅助，相当于创建了新的分段线性函数,这个经过多次验证应该是对的
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
	
	//尝试直接在当前链表修改,初步测试略微快一丢丢丢,10w次快了几毫秒
	//正确性简单验证，还需要测试
	//segment初始化变少了，因为会复用，不过在规模40上时间变化不大
	public void minimizeSuffixInPlace() {
		// 函数 反向取小
		if (head == null)
			return;
		TimerManager.start("分段线性函数后缀最小化");
		// （1）把链表扫一遍装到数组里，便于倒序访问
		List<Segment> segs = new ArrayList<>();
		for (Segment s = head; s != null; s = s.next) {
			segs.add(s);
		}
		int addTimes=0;//记录拼接段的次数
		Segment nextSeg=null;
		// （2）倒序处理，每次产出新段，先累到 newSegs（逆序）。
		double runningMin = Double.POSITIVE_INFINITY;
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
						Segment curSeg =new Segment(e, lastT, 0, runningMin);
						addTimes++;
						nextSeg=insertSegment(curSeg, nextSeg, addTimes);
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
						Segment curSeg=new Segment(tCross, lastT, 0.0, runningMin);
						addTimes++;
						nextSeg=insertSegment(curSeg, nextSeg, addTimes);
					}
//					Segment curSeg=new Segment(seg.start, tCross, a, b);
					Segment curSeg=seg; seg.end=tCross;//直接复用
					addTimes++;
					nextSeg=insertSegment(curSeg, nextSeg, addTimes);

					runningMin = fs;
					lastT = s;
					continue;
				}

				else {
					// 比起始段更高
					if (!Utility.compareEq(lastT, e)) {
						Segment curSeg=new Segment(e, lastT, 0, runningMin);
						addTimes++;
						nextSeg=insertSegment(curSeg, nextSeg, addTimes);

					}
					Segment curSeg=seg;
					addTimes++;
					nextSeg=insertSegment(curSeg, nextSeg, addTimes);
					runningMin = fs;
					lastT = s;
				}

			}
		}
		double prevStart = head.start;
		if (Utility.compareLt(prevStart, lastT)) {
			Segment curSeg=new Segment(prevStart, lastT,0.0, runningMin);
			addTimes++;
			nextSeg=insertSegment(curSeg, nextSeg, addTimes);

		}

		if (Utility.compareGe(lastT, prevStart) && nextSeg==null) {
			//suffix整个和prefix对称
			Segment curSeg=new Segment(prevStart, lastT,0.0, runningMin);
			addTimes++;
			nextSeg=insertSegment(curSeg, nextSeg, addTimes);
		}
		
		head=nextSeg;//此时curSeg和nextSeg相等
		TimerManager.end("分段线性函数后缀最小化");
	}
	public Segment insertSegment(Segment curSeg,Segment nextSeg,int addTimes) {
		
		curSeg.next=nextSeg;
		if(addTimes==1) tail=curSeg;
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
	}

	/**
	 * 检查此函数（L1）是否在 g（L2）的定义域上支配 g。 如果在 L2 的定义域上，L1(t) <= L2(t)，且至少在某一点严格小于，则 L1 支配
	 * L2。
	 *
	 * @param g 要比较的函数（L2）。
	 * @return 如果此函数支配 g，则返回 true，否则返回 false。
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
			return false;
		}
		// f定义域比g还小，肯定无法支配

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
		while (p != null && q != null && Utility.compareLe(cur, gEnd)) {
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

		// 支配要求在某点存在严格不等式
		return true;
	}

	/**
	 * 对 L2（this）进行“被 L1（g）支配区间置∞”的就地更新： 在公共定义域上，凡 L1(t) <= L2(t) 的区间， 将 L2
	 * 替换为前面段的最低值（保持非增性）。
	 */
	public void updateDominatedIntervals(PiecewiseLinearFunction g) {
		if (this.head == null) {
			// 如果 L2 为空，直接拿过来 L1 的整条链
			this.head = g.head;
			this.tail = g.tail;
			return;
		}
		if (g.head == null) {
			// 如果 L1 为空，无事可做
			return;
		}
		resetMinimum();
		// 公共定义域
		double start = Math.max(this.head.start, g.head.start);
		double end = Math.min(this.tail.end, g.tail.end);
		if (!Utility.compareLt(start, end))
			return;

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

				// 若需要拆前半段
				if (!Utility.compareEq(p.start, cur)) {
					Segment left = new Segment(p.start, cur, p.slope, p.intercept);
					left.next = p;
					if (prev == null)
						this.head = left;
					else
						prev.next = left;
					p.start = cur;
					prev = left;
				} else {
					prev = p;
				}
				// 拆前半段的 应该只可能出现在开头吧
				// 若需要拆后半段
				if (!Utility.compareEq(p.end, nxt)) {
					Segment right = new Segment(nxt, p.end, p.slope, p.intercept);
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

		normalize();

	}

	/**
	 * 1) 裁掉首尾 intercept==∞ 的“无穷”段（超出可行域的那两端） 2) 合并相邻 slope/intercept 完全相同的段 3) 再调用
	 * minimizePrefixInPlace() 保证整体非增
	 */
	public void normalize() {
		// 1) 删除头部无穷段
		while (head != null && head.intercept >= Utility.big_M) {
			head = head.next;
		}  
		if (head == null) {
			tail = null;
			return;
		}

		// 2) 删除尾部无穷段
		Segment cur = head, lastFinite = null;
		while (cur != null) {
			if (cur.intercept < Utility.big_M) {
				lastFinite = cur;
			}
			cur = cur.next;
		}
		if (lastFinite == null) {
			// 全部都是 ∞
			head = tail = null;
			return;
		}
		if (lastFinite.next != null) {
			lastFinite.next = null;
			tail = lastFinite;
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
	}

	/**
	 * 将 p 段上的 [cur,nxt) 替换成常数段 lastMin，并保持链表连通。 prev 是 p 在拆分前的前驱（可能为 null）。
	 */
	private void replaceSegment(Segment prev, Segment p, double cur, double nxt, double lastMin) {
		// 拆前半段
		if (!Utility.compareEq(p.start, cur)) {
			Segment left = new Segment(p.start, cur, p.slope, p.intercept);
			left.next = p;
			if (prev == null)
				this.head = left;
			else
				prev.next = left;
			p.start = cur;
			prev = left;
		}
		// 拆常数段
		Segment mid = new Segment(cur, nxt, 0.0, lastMin);
		// 拆后半段或直接接续
		if (Utility.compareEq(p.end, nxt)) {
			mid.next = p.next;
			if (mid.next == null)
				tail = mid;
		} else {
			Segment right = new Segment(nxt, p.end, p.slope, p.intercept);
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
			Segment left = new Segment(p.start, cur, p.slope, p.intercept);
			left.next = p;
			if (prev == null)
				head = left;
			else
				prev.next = left;
			p.start = cur;
			prev = left;
		}
		Segment mid = new Segment(cur, nxt, 0.0, Utility.big_M);
		if (Utility.compareEq(p.end, nxt)) {
			mid.next = p.next;
		} else {
			Segment right = new Segment(nxt, p.end, p.slope, p.intercept);
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
			Segment left = new Segment(p.start, cur, p.slope, p.intercept);
			left.next = p;
			if (prev == null)
				head = left;
			else
				prev.next = left;
			p.start = cur;
			prev = left;
		}
		Segment mid = new Segment(cur, nxt, newSlope, newIntercept);
		if (Utility.compareEq(p.end, nxt)) {
			mid.next = p.next;
		} else {
			Segment right = new Segment(nxt, p.end, p.slope, p.intercept);
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
	 * 将 this（L1）与 g（L2）合并，在它们的公共定义域上取逐点最小值： 如果 L2 在某段 ≤ L1，则就地用 L2 的片段替换这部分 L1。
	 */
	public void mergeMinimum(PiecewiseLinearFunction g) {
		// 函数g可以被舍弃，可随意修改，update不行
		// 如果 L1 为空，直接变成 L2 的拷贝
		if (this.head == null) {
			if (g.head != null) {
				this.head = g.head;
				this.tail = g.tail;
			}
			return;
		}
		// 如果 L2 为空，不用动
		if (g.head == null) {
			return;
		}
		
		resetMinimum();
		// 1) 定位公共定义域
		double start = Math.max(this.head.start, g.head.start);
		double end = Math.min(this.tail.end, g.tail.end);
		if (!Utility.compareLt(start, end)) {
			// 无交集无需合并
			return;
		}

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

			if (Utility.compareLe(f2s, f1s) && Utility.compareLe(f2e, f1e)) {
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
					Segment left = new Segment(p.start, cur, p.slope, p.intercept);
					left.next = p;
					if (prev == null)
						head = left;
					else
						prev.next = left;
					p.start = cur;
					prev = left;
				} else {
					prev = p;
				}
				if (!Utility.compareEq(p.end, nxt)) {
					Segment right = new Segment(nxt, p.end, p.slope, p.intercept);
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
			// 函数g末尾还有一段，需要合并进来
			q.start = end;// 此时相当于把函数g破坏了，但g也没用了
			prev.next = q;
			tail = g.tail;
			// 直接接过来
		}

		// 8) 末尾合并相邻同值同斜率片段、保持非增
		normalize();
	}

	/**
	 * 双指针扫描版本：对两个函数在全域上下做逐点最小合并， 同时保留各自非重叠区间，最后 normalize()。
	 */
	// merge1和merge2两个函数初步测试区别并不大，先这样
	public void mergeMinimum2(PiecewiseLinearFunction g) {
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
		normalize();
	}

	/**
	 * 专门用于 mergeMinimum：把 p 上 [cur,nxt) 块替换成 slope/intercept，并保持链表连通。 prev 是 p
	 * 拆分前的前驱（可能为 null）。
	 */
	private void replaceSegment(Segment prev, Segment p, double cur, double nxt, double newSlope, double newIntercept) {
		// 拆出前半段
		if (!Utility.compareEq(p.start, cur)) {
			Segment left = new Segment(p.start, cur, p.slope, p.intercept);
			left.next = p;
			if (prev == null)
				head = left;
			else
				prev.next = left;
			p.start = cur;
			prev = left;
		}
		// 构造替换段
		Segment mid = new Segment(cur, nxt, newSlope, newIntercept);
		// 拆出后半段或直接接续
		if (Utility.compareEq(p.end, nxt)) {
			mid.next = p.next;
		} else {
			Segment right = new Segment(nxt, p.end, p.slope, p.intercept);
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
	
	public double[] findMinimal(boolean convex,boolean fromLeft) {
		//对任意一个函数，遍历寻找全局最小值的成本以及对应的t值
		//convex=false表示不一定是convex，可能是convex，不冲突
		//fromLeft为true表示从左找第一个最小的 否则找最右侧的
		//TODO 此处的实现只适用于连续的函数，只扫描了每个段的一个端点
		//对于不连续的，还要进一步处理
		double min=Utility.big_M;
		double leftX=0,rightX=0;

		
		if(minValuePairsLeft!=null&&fromLeft) {
			return minValuePairsLeft;
		}
		if(minValuePairsRight!=null&&!fromLeft) {
			return minValuePairsRight;
		}
		
		
		if(head==null) {
			return new double[] {min,leftX};
		}
		TimerManager.start("分段线性函数最小化");
		for(Segment p=head;p!=null;p=p.next) {
			double leftValue=p.getValue(p.start);
			
			if(Utility.compareLt(leftValue,min)) {
				min=leftValue;
				leftX=rightX=p.start;
			}else {
				if(Utility.compareEq(leftValue,min)) {
					rightX=p.start;
				}	
			}
			
			if(Utility.compareGe(p.slope,0)&&convex) break;
			//对凸函数,到最后一个斜率<=0的地方就好了
			
		}
		double rightValue=tail.getValue(tail.end);
		
		if(Utility.compareLt(rightValue,min)) {
			min=rightValue;
			leftX=rightX=tail.end;
		}else {
			if(Utility.compareEq(rightValue,min)) {
				rightX=tail.end;
			}	
		}
		this.minValuePairsLeft=new double[2];
		this.minValuePairsRight=new double[2];
		minValuePairsLeft[0]=min;minValuePairsLeft[1]=leftX;
		minValuePairsRight[0]=min;minValuePairsRight[1]=rightX;
		TimerManager.end("分段线性函数最小化");
		if(fromLeft) return minValuePairsLeft;
		else return minValuePairsRight;
	
		
	}
	
	

	public void resetMinimum() {
		minValuePairsLeft=null;
		minValuePairsRight=null;
	}

}

// 如果需要限制函数的定义域，即不管如何位移定义域都是固定的，感觉不在函数里处理了？单独写个函数处理？
// 函数操作的时候整个定义域限制在[0,T], 位移以后可能超出去，不管这个超出去多少，当需要对偏移以后的函数限制定义域做dominance的时候？
// 似乎只有在dominance的时候定义域才会比较重要，其他时候影响不会很大？先这样，应该不会慢太多吧.

// 考虑处理如下：
// 1、label传递的过程中，不管前向后向，函数都是往一个方向平移，此时可能定义域超出全局上界（例如前移过程中，左端开始区间为当前执行任务的最小完成时间，右端会超出），考虑初始定义的时候给一个全局定义域，不管如何add，add后的函数超出定义域的部分被删除
// 2、一方面，当标签到达某个节点和惩罚函数相加的时候，此时该节点会有一个时间窗，通过对惩罚函数定义域的设定以及两个函数的相加，得到的函数就是在这个定义域上的
