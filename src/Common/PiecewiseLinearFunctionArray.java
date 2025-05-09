package Common;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 基于 ArrayList 的分段线性函数实现，适合段数不超过200的场景。
 */
public class PiecewiseLinearFunctionArray {
    public static class Segment {
        public double start, end;
        public double slope;
        public double intercept;
        public Segment(double s, double e, double a, double b) {
            this.start = s;
            this.end = e;
            this.slope = a;
            this.intercept = b;
        }
    }

    private double domainStart = 0;
    private double domainEnd = Double.POSITIVE_INFINITY;
    private  List<Segment> segs;

    public PiecewiseLinearFunctionArray() {
        this.segs = new ArrayList<>();
    }
    public PiecewiseLinearFunctionArray(double domainStart, double domainEnd) {
        if (!Utility.compareLt(domainStart, domainEnd))
            throw new IllegalArgumentException("Invalid domain: start >= end");
        this.domainStart = domainStart;
        this.domainEnd = domainEnd;
        this.segs = new ArrayList<>();
    }
    public PiecewiseLinearFunctionArray copy() {
        PiecewiseLinearFunctionArray r = new PiecewiseLinearFunctionArray(domainStart, domainEnd);
        for (Segment s: this.segs) {
            r.segs.add(new Segment(s.start, s.end, s.slope, s.intercept));
        }
        return r;
    }
    public void addSegment(double s, double e, double a, double b) {
        segs.add(new Segment(s, e, a, b));
    }
    public void addSegment(Segment s) {
        segs.add(s);
    }
    public boolean isEmpty() { return segs.isEmpty(); }

    public PiecewiseLinearFunctionArray shift(double delta) {
        PiecewiseLinearFunctionArray r = copy();
        for (Segment s: r.segs) {
            s.start += delta;
            s.end   += delta;
            s.intercept -= s.slope * delta;
        }
        r.trimToDomain();
        return r;
    }

    public double evaluate(double t) {
        for (Segment s: segs) {
            if (Utility.compareLe(s.start, t) && Utility.compareLt(t, s.end))
                return s.slope * t + s.intercept;
        }
        // 可能落在tail端点
        if (!segs.isEmpty()) {
            Segment last = segs.get(segs.size()-1);
            if (Utility.compareEq(t, last.end))
                return last.slope * t + last.intercept;
        }
        return Utility.big_M;
    }
    public double slopeAt(double t) {
        for (Segment s: segs) {
            if (Utility.compareLe(s.start, t) && Utility.compareLt(t, s.end))
                return s.slope;
        }
        if (!segs.isEmpty()) {
            Segment last = segs.get(segs.size()-1);
            if (Utility.compareEq(t, last.end))
                return last.slope;
        }
        return Utility.big_M;
    }

    public PiecewiseLinearFunctionArray add(PiecewiseLinearFunctionArray g) {
        double s0 = Math.max(domainStart, g.domainStart);
        double e0 = Math.min(domainEnd,   g.domainEnd);
        if (Utility.compareGt(s0, e0)) return new PiecewiseLinearFunctionArray(domainStart, domainEnd);

        PiecewiseLinearFunctionArray r = new PiecewiseLinearFunctionArray(domainStart, domainEnd);
        int i=0, j=0;
        while (i<segs.size() && j<g.segs.size()) {
            Segment p = segs.get(i), q = g.segs.get(j);
            double lo = Math.max(Math.max(p.start, q.start), s0);
            double hi = Math.min(Math.min(p.end,   q.end),   e0);
            if (Utility.compareLt(lo, hi)) {
                double fLo = p.slope*lo+p.intercept;
                double fHi = p.slope*hi+p.intercept;
                double gLo = q.slope*lo+q.intercept;
                double gHi = q.slope*hi+q.intercept;
                boolean useQ = Utility.compareLe(gLo,fLo) && Utility.compareLe(gHi,fHi);
                r.addSegment(lo, hi, useQ?q.slope:p.slope, useQ?q.intercept:p.intercept);
            }
            // 推进指针
            if (Utility.compareLe(p.end, q.end)) i++;
            if (Utility.compareLe(q.end, p.end)) j++;
        }
        r.normalize();
        return r;
    }

    public void minimizePrefixInPlace() {
        double runningMin = Double.POSITIVE_INFINITY;
        double prevT = segs.get(0).start;
        List<Segment> out = new ArrayList<>();
        for (Segment s: segs) {
            double fgs = s.slope*s.start + s.intercept;
            if (Utility.compareGe(s.slope, 0)) {
                if (Utility.compareLt(fgs, runningMin)) {
                    if (!Utility.compareEq(prevT, s.start)) {
                        out.add(new Segment(prevT, s.start, 0.0, runningMin));
                    }
                    runningMin = fgs;
                }
            } else {
                double fge = s.slope*s.end + s.intercept;
                if (Utility.compareLt(fge, runningMin)) {
                    if (Utility.compareGt(fgs, runningMin)) {
                        double tCross = (runningMin - s.intercept)/s.slope;
                        if (Utility.compareLt(prevT, tCross)) {
                            out.add(new Segment(prevT, tCross, 0.0, runningMin));
                        }
                        out.add(new Segment(tCross, s.end, s.slope, s.intercept));
                        runningMin = fge;
                        prevT = s.end;
                        continue;
                    } else {
                        if (!Utility.compareEq(prevT, s.start)) {
                            out.add(new Segment(prevT, s.start, 0.0, runningMin));
                        }
                        out.add(new Segment(s.start, s.end, s.slope, s.intercept));
                        runningMin = fge;
                        prevT = s.end;
                    }
                }
            }
        }
        // 末尾
        Segment last = segs.get(segs.size()-1);
        if (Utility.compareLt(prevT, last.end)) {
            out.add(new Segment(prevT, last.end, 0.0, runningMin));
        }
        segs.clear(); segs.addAll(out);
    }

    public void trimToDomain() {
        // 删除 domain 之外的
    	//这函数被调用的时候应该不存在无穷段？
        Iterator<Segment> it = segs.iterator();
        List<Segment> tmp = new ArrayList<>();
        while (it.hasNext()) {
            Segment s = it.next();
            if (Utility.compareLt(s.end, domainStart) || Utility.compareLt(domainEnd, s.start))
                continue;
            double ss = Math.max(s.start, domainStart);
            double ee = Math.min(s.end,   domainEnd);
            tmp.add(new Segment(ss, ee, s.slope, s.intercept));
        }
        segs.clear(); segs.addAll(tmp);
        if (segs.isEmpty()) return;
    }

    public boolean dominates(PiecewiseLinearFunctionArray g) {
        if (this.segs.isEmpty()) return false;
        if (g.segs.isEmpty()) return true;
        if (Utility.compareGt(this.segs.get(0).start, g.segs.get(0).start)) return false;
        Segment t1 = this.segs.get(segs.size()-1), t2 = g.segs.get(g.segs.size()-1);
        if (Utility.compareLt(t1.end, t2.end)) return false;
        int i=0,j=0;
        double cur = Math.max(segs.get(0).start, g.segs.get(0).start);
        while (i<segs.size() && j<g.segs.size()) {
            Segment p=segs.get(i), q=g.segs.get(j);
            double nxt = Math.min(Math.min(p.end, q.end), t2.end);
            if (Utility.compareLt(cur, nxt)) {
                double fP = p.slope*cur+p.intercept;
                double fQ = q.slope*cur+q.intercept;
                double fPe= p.slope*nxt+p.intercept;
                double fQe= q.slope*nxt+q.intercept;
                if (Utility.compareGt(fP,fQ) || Utility.compareGt(fPe,fQe))
                    return false;
                cur = nxt;
            }
            if (Utility.compareEq(p.end, nxt)) i++;
            if (Utility.compareEq(q.end, nxt)) j++;
        }
        return true;
    }

    /**
     * 在 this 和 g 的公共定义域上，将被 g 支配的区间置为 ∞，其它区间保持原样。
     * 严格不修改 this.segs 或 g.segs，只往 out 列表里 append。
     */
    public void updateDominatedIntervals(PiecewiseLinearFunctionArray g) {
        // 1) 空函数处理
        if (g.segs.isEmpty()) return;
        if (this.segs.isEmpty()) {
            this.segs = new ArrayList<>(g.segs);

            return;
        }

        // 2) 计算公共定义域
        double commonS = Math.max(this.domainStart, g.domainStart);
        double commonE = Math.min(this.domainEnd,   g.domainEnd);
        if (!Utility.compareLt(commonS, commonE)) {
            // 无公共域，normalize 后直接返回
            
            return;
        }

        List<Segment> out = new ArrayList<>();
        int i = 0, j = 0;
        double fS, fE, gS, gE;
        // 3) 前缀：copy this 在 [head.start, commonS) 的段
        while (i < segs.size()) {
            Segment f0 = segs.get(i);
            if (Utility.compareLe(f0.end, commonS)) {
                out.add(f0);
                i++;
            } else {
                break;
            }
        }
        
        if(Utility.compareLt(segs.get(i).start, commonS)) {
        	out.add(new Segment(segs.get(i).start, commonS, segs.get(i).slope, segs.get(i).intercept));
        	segs.get(i).start=commonS;
        }

        // 4) 让指针 j 跳到 g 上第一个 end>commonS 的段
        while (j < g.segs.size() && Utility.compareLe(g.segs.get(j).end, commonS)) {
            j++;
        }

        // 5) 主循环：双指针在公共域 [commonS, commonE) 上扫描
        while (i < segs.size() && j < g.segs.size()) {
            // 5a) 取出当前 f, g 段的 local start/end（不修改原数据）
            Segment f0 = segs.get(i), gg0 = g.segs.get(j);
            fS = Math.max(f0.start, commonS);
            fE = Math.min(f0.end,   commonE);
            gS = Math.max(gg0.start, commonS);
            gE = Math.min(gg0.end,   commonE);

            // 如果 f 已经跑到公共域以外就停
            if (!Utility.compareLt(fS, commonE)) break;

         
            double lo = Math.max(fS, gS);
           
            // 5c) 重叠区间 [lo, hi)
            double hi = Math.min(fE, gE);
            if (Utility.compareLt(lo, hi)) {
                double fLo = f0.slope*lo + f0.intercept;
                double fHi = f0.slope*hi + f0.intercept;
                double gLo = gg0.slope*lo + gg0.intercept;
                double gHi = gg0.slope*hi + gg0.intercept;

                if (Utility.compareLe(gLo, fLo) && Utility.compareLe(gHi, fHi)) {
                    // 完全被支配
                    out.add(new Segment(lo, hi, 0.0, Utility.big_M));
                } else if (Utility.compareGt(gLo, fLo) && Utility.compareGt(gHi, fHi)) {
                    // 完全不被支配
                    out.add(new Segment(lo, hi, f0.slope, f0.intercept));
                } else {
                    // 端点不一致 → 计算交点并拆两段
                    double tCross = (gg0.intercept - f0.intercept)/(f0.slope - gg0.slope);
                    // [lo, tCross)
                    if (Utility.compareLe(gLo, fLo)) {
                        out.add(new Segment(lo, tCross, 0.0, Utility.big_M));
                    } else {
                        out.add(new Segment(lo, tCross, f0.slope, f0.intercept));
                    }
                    // [tCross, hi)
                    if (Utility.compareLe(gg0.slope*tCross+gg0.intercept, f0.slope*tCross+f0.intercept)) {
                        out.add(new Segment(tCross, hi, 0.0, Utility.big_M));
                    } else {
                        out.add(new Segment(tCross, hi, f0.slope, f0.intercept));
                    }
                }
            }

            // 5d) 推进「结束更早」的那条，另一条在下一轮继续用相同的 hi 做起点
            if (Utility.compareLt(fE, gE)) {
                // f 段先结束
                i++;
            } else if (Utility.compareLt(gE, fE)) {
                // g 段先结束
                j++;
            } else {
                // 同时结束
                i++; j++;
            }
        }

        // 6) 后缀：copy this 在 commonE 之后的所有段
        while (i < segs.size()) {
            Segment f0 = segs.get(i++);
            if (Utility.compareGe(f0.start, commonE)) {
                out.add(f0);
            }
        }

        // 7) 替换 & normalize
        this.segs = out;
        normalize();
    }


    public void mergeMinimum(PiecewiseLinearFunctionArray g) {
    	if (this.segs.size()==0) {
            if (g.segs.size() != 0) {
                this.segs=g.segs;
              
            }
            return;
        }
        // 如果 L2 为空，不用动
        if (g.segs.size() == 0) {
            return;
        }
    	
    	
    	double s0 = Math.max(this.segs.get(0).start, g.segs.get(0).start);
        double e0 = Math.min(this.segs.get(segs.size()-1).end, g.segs.get(g.segs.size()-1).end);
        PiecewiseLinearFunctionArray r = new PiecewiseLinearFunctionArray(domainStart, domainEnd);
        int i=0,j=0;
        // 前缀
        while (i<segs.size() && Utility.compareLe(segs.get(i).end, s0)) { r.addSegment(segs.get(i)); i++; }
        while (j<g.segs.size() && Utility.compareLe(g.segs.get(j).end, s0)) { r.addSegment(g.segs.get(j)); j++; }
        if (i<segs.size() && Utility.compareLt(segs.get(i).start, s0)) {
            Segment p=segs.get(i);
            r.addSegment(p.start, s0, p.slope, p.intercept);
            segs.get(i).start = s0;
        }
        if (j<g.segs.size() && Utility.compareLt(g.segs.get(j).start, s0)){
            Segment q=g.segs.get(j);
            r.addSegment(q.start, s0, q.slope, q.intercept);
            g.segs.get(j).start = s0;
        }
        // 公共域
        while (i<segs.size() && j<g.segs.size()){
            Segment p=segs.get(i), q=g.segs.get(j);
            double lo = Math.max(p.start, q.start);
            double hi = Math.min(Math.min(p.end, q.end), e0);
            // 交点
            if (!Utility.compareEq(p.slope, q.slope)){
                double tc = (q.intercept-p.intercept)/(p.slope-q.slope);
                if (Utility.compareLt(lo,tc) && Utility.compareLt(tc,hi)){
                    double fLo=p.slope*lo+p.intercept, fTc=p.slope*tc+p.intercept;
                    double gLo=q.slope*lo+q.intercept, gTc=q.slope*tc+q.intercept;
                    boolean useQ = Utility.compareLe(gLo,fLo) && Utility.compareLe(gTc,fTc);
                    r.addSegment(lo, tc, useQ?q.slope:p.slope, useQ?q.intercept:p.intercept);
                    lo = tc;
                }
            }
            double fLo=p.slope*lo+p.intercept, fHi=p.slope*hi+p.intercept;
            double gLo=q.slope*lo+q.intercept, gHi=q.slope*hi+q.intercept;
            boolean useQ = Utility.compareLe(gLo,fLo) && Utility.compareLe(gHi,fHi);
            r.addSegment(lo, hi, useQ?q.slope:p.slope, useQ?q.intercept:p.intercept);
            if (Utility.compareLt(p.end, q.end)){
                i++; q.start = hi;
            } else if (Utility.compareLt(q.end, p.end)){
                j++; p.start = hi;
            } else { i++; j++; }
        }
        // 后缀
        while (i<segs.size()){
            if (Utility.compareGe(segs.get(i).start, e0)) r.addSegment(segs.get(i));
            i++;
        }
        while (j<g.segs.size()){
            if (Utility.compareGe(g.segs.get(j).start, e0)) r.addSegment(g.segs.get(j));
            j++;
        }
        r.normalize();
        this.domainStart=r.domainStart;
        this.domainEnd=r.domainEnd;
        this.segs=r.segs;
    }
    
    
 // 设置定义域
 		public PiecewiseLinearFunctionArray setDomain(double domainStart, double domainEnd) {
 			PiecewiseLinearFunctionArray res=this.copy();
 			if (Utility.compareLt(domainStart, domainEnd)) {
 				res.domainStart = domainStart;
 				res.domainEnd = domainEnd;
 				res.trimToDomain();
 			} else {
 				throw new IllegalArgumentException("Invalid domain: start >= end");
 			}
 			return res;
 		}
 	
 	
 		public void normalize() {
 		    int n = segs.size();
 		    if (n == 0) return;

 		    // 1) 找到第一个非 ∞ 段
 		    int startIdx = 0;
 		    while (startIdx < n && segs.get(startIdx).intercept >= Utility.big_M) {
 		        startIdx++;
 		    }
 		    if (startIdx == n) {
 		        // 全部都是 ∞
 		        segs.clear();
 		        return;
 		    }

 		    // 2) 找到最后一个非 ∞ 段
 		    int endIdx = n - 1;
 		    while (endIdx >= startIdx && segs.get(endIdx).intercept >= Utility.big_M) {
 		        endIdx--;
 		    }

 		    // 3) 提取 [startIdx..endIdx] 范围内的段到新列表
 		    List<Segment> tmp = new ArrayList<>(endIdx - startIdx + 1);
 		    for (int k = startIdx; k <= endIdx; k++) {
 		        tmp.add(segs.get(k));
 		    }

 		    // 4) 合并相邻 slope/intercept 相同且相连的段
 		    List<Segment> merged = new ArrayList<>(tmp.size());
 		    Segment cur = tmp.get(0);
 		    for (int k = 1; k < tmp.size(); k++) {
 		        Segment nxt = tmp.get(k);
 		        if (Utility.compareEq(cur.slope, nxt.slope)
 		         && Utility.compareEq(cur.intercept, nxt.intercept)
 		         && Utility.compareEq(cur.end, nxt.start)) {
 		            cur.end = nxt.end;
 		        } else {
 		            merged.add(cur);
 		            cur = nxt;
 		        }
 		    }
 		    merged.add(cur);
 		    this.segs=merged;
 		    minimizePrefixInPlace();
 		  
 		    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Segment s: segs) {
            sb.append(String.format("[%.3f,%.3f): %.3f·t+%.3f\n", s.start,s.end,s.slope,s.intercept));
        }
        return sb.toString();
    }
}
