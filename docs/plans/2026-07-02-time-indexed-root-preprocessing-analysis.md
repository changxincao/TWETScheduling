# time-indexed root 预处理辅助 ng-DSSR 的可行性分析

## 问题

当前观察显示，ng-DSSR 在 root 上承担主要 closure 压力，而子节点由于分支、arc fixing、compact window 和 dual-bound pruning 继承信息较多，exact pricing 变得很轻。由此产生一个自然想法：先用 time-indexed pricing 在 root 上快速收敛，利用这个松弛 root 证明哪些 arc 不可能出现在最优解中；随后不沿 time-indexed 树继续分支，而是把这些 arc/window 信息喂给 ng-DSSR，从一个已经压缩过的 root 重新开始。

## 正确性判断

这个思路原则上可以做，但必须把 time-indexed root 当作一个独立的下界预处理器，而不是把它的列或 LP 状态直接混进 ng-DSSR。关键条件是：time-indexed root pricing 必须完整闭合，得到当前 time-indexed 松弛主问题下的有效 dual/lower bound；arc fixing 必须用这个 lower bound、当前 incumbent upper bound，以及 time-expanded 图上经过某条弧的最短 reduced-cost 路径来证明。

由于 time-indexed 图允许 pseudo-schedule 或重复任务，它的列空间通常是 elementary 机器列空间的超集或松弛。因此，对某条时空弧 `(i,j,t)`，如果在这个更松的图里经过它的最便宜路径都不能改善 incumbent，那么在更受限的 elementary/ng-DSSR 列空间里经过它的路径也不可能改善 incumbent。这一点给出安全性：time-indexed fixing 可以作为保守的 root 预处理；它可能弱，但不应误删 elementary 最优列。

需要区分两层 fixing。若只证明某些 `(i,j,t)` 不可能出现，只能用于 time-indexed helper 自己，或转化为 job completion time window/hull。若要把普通 arc `(i,j)` 禁给 ng-DSSR，必须证明该 arc 的所有可行时间副本都被 fixing 掉；否则只能得到时间窗缩减，不能直接禁止整个 `(i,j)`。整数时间算例可以直接用精确时空图判断；小数算例如果用向下取整 processing/setup、向外扩 due window 的离散松弛图，则仍可用于 lower-bound/scalar 或保守 fixing，但不能直接反写硬时间窗，除非先做统一 scale 并保证离散图严格覆盖原连续可行空间。

## 如何接入

更稳的接入方式是：先用当前 ALNS/RMIH 得到 incumbent；再在 root 上单独跑 time-indexed column generation 到无负列，得到 `LB_TI` 和 root dual；随后在 time-expanded 图上计算 forward/backward shortest distance，对每条时空弧判断 `LB_TI + bestPathThroughArc >= UB` 是否成立。聚合时，所有时间副本都被证明无用的普通 arc，写成 root 的 node-domain forbidden arc；只缩掉部分时间的，写成 compact completion window 或 time-indexed helper 的局部禁弧信息。最后重新启动 ng-DSSR root。初始列和全局 pool 写入当前 root RMP 时，要过滤掉已经被证明 impossible 的普通 arc；否则旧列仍可能在 LP 里正值使用，污染 dual。后续 pricing 也使用这些 forbidden arc/window。

这里不建议直接复用 time-indexed 的 RMP dual 或列作为 ng-DSSR 的初始 RMP。time-indexed 列是松弛列，和 elementary master 的列语义不完全一致；真正有价值的是它给出的安全禁弧、compact window 和 lower-bound 证据。

## 预期收益和风险

该方案最有可能在“time-indexed root 很快、但 ng-DSSR root 很重”的实例上有效。例如原始时间尺度较小、time-indexed 最短路图可快速闭合时，root 预处理成本可能低于直接 ng-DSSR root 的节省；并且如果 time-indexed fixing 能删除大量普通 arc 或显著缩小 job 时间窗，ng-DSSR root 会接近当前非根节点的轻量状态。

风险也很明确。第一，如果 time-indexed root 本身因为 horizon 大或 scale 后时间点太多而很慢，预处理会得不偿失。第二，如果 fixing 大多停留在时空弧层面，聚合成普通 arc 后很少，ng-DSSR root 未必明显变轻。第三，如果有 SRI/rank-1 cut、外包列、setup cost 等扩展，time-indexed pricing 和 fixing 必须使用同一套 reduced-cost 口径；否则只能作为 no-cut/no-SRI 的更松下界，强度会变弱，不能把未建模状态下的“强 fixing”直接当作正式证明。

因此当前结论是：这个方向理论上成立，适合作为可选的 root preprocessing 实验，不应直接替换主流程。最小可行实验可以先只在无 SRI、无外包列、整数时间、setup cost 口径一致的算例上做，并输出三类诊断：time-indexed root 时间、聚合后普通 arc 删除比例、ng-DSSR root 时间下降幅度。若普通 arc 删除比例很低，就说明它主要只能强化 time-indexed helper/window，而不能显著改造 ng-DSSR root。

## 关于“root fixing 是否只能给子节点用”的补充

arc fixing 通常在文献或代码里表现为“当前 node 收敛后，把固定信息传给子节点”，但这不是理论限制，而是使用场景。一个 node 上证明出来的安全 fixing，作用范围是该 node 对应的整个可行域；它当然也可以立即用于该 node 后续的 pricing，或者用于该 node 的全部子树。root node 的可行域就是完整问题，因此 root 上得到的安全 fixing 可以作用于整个后续求解，不要求必须先生成一个子节点。

真正需要验证的是 fixing 证据是否对目标算法的列空间有效。若 time-indexed root 是原问题的一个松弛列空间，并且 reduced-cost 口径、incumbent 和 lower bound 都一致，那么在更松的 time-indexed 图里经过某个 arc 的最好可能性都不能改善 incumbent，就能推出更受限的 elementary/ng-DSSR 列空间中经过该 arc 也不能改善 incumbent。因此它可以作为 ng-DSSR root 的预处理禁弧证据。反过来，如果 time-indexed 口径缺了某些成本、cut dual、外包语义，或者只闭合了启发式 pricing，没有形成完整 lower-bound 证书，就不能把它的 fixing 直接转给 ng-DSSR。

因此该方案不是“拿子节点 fixing 回头改 root”，而是“在 root 上先用一个更便宜的松弛定价器生成 root-domain fixing，再用这些 root-domain fixing 初始化另一个更强的定价器”。理论上成立的前提是：time-indexed 预处理给出的每条禁弧都是对 root 可行域的全局有效证明；并且普通 arc 禁止必须来自所有时空副本均被证明无用，而不是某几个时间点被删。
