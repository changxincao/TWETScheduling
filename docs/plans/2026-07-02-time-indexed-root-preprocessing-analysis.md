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

## 初版实现记录

2026-07-02 已实现一个默认关闭的实验开关 `enableTimeIndexedRootPreprocessingForNgDssr`，common runner 入口为 `twet.bpc.fullDomainCompare.timeIndexedRootPreprocessingForNgDssr`。开启后，Tree 在正式 root 入队后、主循环取 root 前，先用独立的 no-cut/no-SRI time-indexed pricing root 做一次预处理。预处理使用临时 `Pool / LP / PC / Node`，只复制正式 root 的 seed/incumbent 机器列，关闭 cut、dual stabilization、dual-bound pruning 和 RMIH，pricing engine 只保留 `TimeIndexedGraphPricingEngine`。这样做的目的是让 graph root 的列、dual 和临时 RMP 状态不进入主线，只把收敛后可证明安全的 time-indexed 禁弧和 compact window 证据复制回正式 root。

当前版本转交给 ng-DSSR root 的内容包括：time-indexed pricing-only forbidden arc-times、time-indexed arc store 状态、以及每个 job 的 compact pricing window。它没有把 graph root 的 pseudo-schedule 列加入主线 Pool，也没有把 time-indexed dual 或 RMP basis 复用给 ng-DSSR。普通 job-job arc 的永久禁止目前仍保持谨慎：只有后续若实现“所有时间副本均被证明无用”的聚合逻辑，才适合把普通 arc 写成 ng-DSSR 的直接 forbidden arc；当前初版主要验证 compact window / time-indexed arc 状态是否能降低 ng-DSSR root 难度。

兼容边界也先收窄：该预处理只在主线是 ng-DSSR 或其 partial/graph partial 变体、且没有启用 time-indexed graph 主 pricing、没有启用列化外包时运行。active SRI/root cut 不参与该预处理，因为当前目标是 no-cut root 先闭合再提供松弛证据；若后续要在 SRI 状态下做同等强度 fixing，需要把 SRI state 放进 time-indexed helper 或单独做 SRI-aware graph preprocessing。

验证方面，相关源码已通过带 CPLEX/CP Optimizer jar 的 focused javac 编译。`wet020_001_2m` smoke 中，日志先出现 `Pricing[TimeIndexedGraphPricing] node=0`，随后进入 `Pricing[GCNGBBStyleNgDssrPricing] node=1`，最终 `ROOT_PROCESSED` 且 `valid=true`，说明两阶段顺序和临时列池隔离是通的。`wet040_001_2m` 的 60 秒限时 smoke 后续复核发现：临时 time-indexed root 实际已经闭合，最后一轮为 `improved=false, addedColumns=0, pool=44526`；此前把没有看到 `timeIndexedRootPreprocess.done` 当作未闭合，是因为该信息属于 heartbeat，默认配置不会输出。这说明在该算例上 time-indexed 预求解确实很快，真正需要评估的是它复制回 ng-DSSR 的 fixing/window 强度是否足够。

## 40-2 smoke 结果误读修正

2026-07-02 复核后确认，`wet040_001_2m` 的 60 秒 smoke 不能解读为“time-indexed root 求解不动”。该 smoke 使用的是 ng-DSSR 主线入口，并显式设置 `runALNSForSeed=false`、`ngDssrInitialSize=3/top3`，初始列只有 2 条、初始 incumbent 为 62989。预处理阶段的最后几轮 `Pricing[TimeIndexedGraphPricing] node=0` 显示 pool 从 `44451` 增到 `44526`，最后一轮 `improved=false, addedColumns=0, bestPseudoRC≈3.4e-12`，因此临时 time-indexed root 已经无负列闭合。

历史可比的 no-cut time-indexed 记录是 `tmp-timegraph-nocut-40-2-setup-nostrong-20260630`：同一 `wet040_001_2m` 在 timeGraph 模式下 `FINISHED`，总时间 46.737s，root time 18.750s，root node 1 已分支，root final pool 约 `44528`，`TimeIndexedGraphPricing=10.607s/670 calls`，无 HeuristicPricing。由此修正当前判断：40-2 上 time-indexed root 预处理本身是快的；60 秒 smoke 后半段慢，是因为预处理闭合后主线 ng-DSSR 仍只继承 compact window / time-indexed arc-time 信息，不继承 pseudo-schedule 列，也还没有把所有时空副本均固定的普通 arc 聚合成 ng-DSSR 直接禁弧。因此主线仍要从很少的真实机器列重新开始补列，不能把这段耗时归因于 time-indexed 预处理。
## 时空弧到普通弧的聚合

2026-07-02 进一步补齐 root 预处理的信息转交：time-indexed arc fixing 删除的是 `(from,to,time)`，ng-DSSR 更直接需要的是普通 `(from,to)` pricing-only forbidden。现在在临时 time-indexed root 闭合并完成 graph/scalar fixing 后，先把 time-indexed 禁弧和 compact window 复制回正式 root，再按同一个 time-indexed graph window 扫描每条普通 process arc 和 end arc 的所有离散时间副本。只有当该普通弧在 graph window 内没有任何仍可用的时间副本时，才把它提升为正式 root 的 `pricingOnlyArc`。

这个聚合等价于“看 time-indexed 图里最终还剩哪些普通弧”：只要 `(i,j)` 还有一个可行时间点没被 fixing 删除，就不禁整条弧；如果所有时间副本都已被删或本来不可行，说明在这个已闭合的松弛图中经过该普通弧不可能改善 incumbent，因此更受限的 ng-DSSR pricing 也不需要继续扩展它。由于 `Node` 的时空弧存储可能在 forbidden set 和 allowed complement 之间切换，聚合时不直接读取底层 map，而统一调用 `isTimeIndexedPricingOnlyArcForbidden(from,to,t)`，避免存储口径改变导致误判。

本轮没有把 time-indexed pseudo-schedule 列复制进主线 Pool，也没有改变 master 分支行；聚合出来的普通弧仍按 pricing-only 口径使用，只影响后续 pricing、completion bound 和枚举，不删除当前 RMP 里已有列。


## 40-2 完整测试

2026-07-02 用 `wet040_001_2m`、`nearestK3/top3`、no-SRI、no-partial、强分支关闭、ALNS 30 秒、completion bound/all-cycles、pricing-only subtree、midpoint probe/reuse 和 dual bound pruning 口径测试 root 预处理版本。time-indexed 临时 root 完整闭合，临时 pool 到 `84133`，随后 graph/scalar fixing 固定时空弧约 `3349492`，提升普通 pricing-only 弧 `1299` 条，40 个任务窗口全部收缩，平均 hull 窗口长度约 `211.5`，相对原始 `2132` 个离散点收缩约 `90.1%`。预处理阶段耗时约 `60.584s`。

主线 ng-DSSR 随后正常求解到 `obj=bound=22580`，`valid=true`，总时间 `204.470s`，root `93.950s`，exact `41.291s/466`，heuristic `53.027s/1213`，节点数 `47`，pool `54911`。对比同配置但不做 root 预处理的近期记录 `121.924s/root 64.010s/exact 35.010s/262/nodes 45/pool 58052`，该小算例上净效果变慢。原因不是兼容性错误，而是预处理本身多花约一分钟，且复制回来的信息虽然显著缩小了 root label 空间，但没有少到足以抵消临时 time-indexed root 的额外列生成和 LP 开销。

从 label 行为看，预处理确实把主线 root 变轻了。root 第一轮 ng-DSSR 中普通弧禁用数约 `1232`，`pricingHorizon=1418`，保留标签约 `fw 568 / bw 645`，候选扩展约 `8384`，远低于未聚合前 root 可能出现的几十万级扩展。但该算例本身 `nearestK3/top3` 已经足够快，预处理适合继续放在默认关闭的实验开关下，优先在 root 极重、time-indexed root 又能快速闭合的实例上测试。

## 关掉 ALNS 的隔离测试

2026-07-02 进一步把同一配置中的 `runALNSForSeed` 关掉，只用于判断前一轮预处理为什么会花到约 60 秒。结果显示，临时 time-indexed root 恢复到和纯 time-indexed 根节点一致的规模：初始列为 `2`，临时 root 闭合到 `tempPool=44526`，`TimeIndexedGraphPricing` 最后一轮 `improved=false, addedColumns=0`，预处理总耗时约 `17.125s`，其中 graph fixing 约 `0.429s`，scalar fixing 约 `0.132s`。这说明前一轮 `tempPool=84133 / ms=60583` 的直接原因不是 graph fixing 或 scalar fixing，而是预处理临时 RMP 复制了主线 ALNS seed / 强 incumbent 后，time-indexed root 的对偶路径发生退化，吃了更多 pseudo-schedule 列。

但全局关掉 ALNS 并不是好方案。该 no-ALNS run 在主线 ng-DSSR 阶段从 2 条真实机器列开始补列，最终 300 秒达到时间限制，虽然输出 `obj=bound=22580, valid=true`，但 root 时间升到约 `133.188s`，总 `HeuristicPricing=124.986s/1346`，`GCNGBBStyleNgDssrPricing=92.536s/492`，pool 到 `79060`。因此更合理的修正方向是把两件事分开：正式 ng-DSSR 主线仍保留 ALNS seed/incumbent；但 `TimeIndexedRootPreprocessor` 内部的临时 time-indexed root 不应复制主线 ALNS seed 列，而应按纯 time-indexed root 的最小 seed 口径闭合，再只把安全的 forbidden arc/window 证据转交给主线。这样才可能同时获得快速预处理和较好的主线初始列。

## setupR 三组 current off/on 隔离测试

2026-07-02 按 `setupCostFromTimeCoefficient=20`、ALNS seed、`nearestK8/top10`、no-strong、completion bound/scalar/arc fixing/subtree、midpoint probe/reuse、dual-bound pruning 等同一配置，对 `setupR25/R50/R75` 做了当前代码下的 off/on 隔离测试。先前 7/1 的旧日志不能直接作为对照，因为当前代码已经经过若干性能修正；因此本轮重新跑了 `timeIndexedRootPreprocessingForNgDssr=false` 的 current-base，再只把该开关改为 `true` 跑预处理组。

current-base 结果为：`R25 solve=99.101s/root=99.097s/exact=10.506s/8/heuristic=50.000s/46/nodes=1/pool=9334`；`R50 solve=189.443s/root=106.968s/exact=32.861s/59/heuristic=84.650s/178/nodes=9/pool=20384`；`R75 solve=277.038s/root=80.368s/exact=102.330s/212/heuristic=93.272s/548/nodes=30/pool=33319`。预处理组结果为：`R25 solve=48.015s/root=48.012s/exact=5.384s/8/heuristic=26.590s/46/nodes=1/pool=9334`；`R50 solve=73.986s/root=39.384s/exact=16.462s/63/heuristic=31.128s/192/nodes=9/pool=20279`；`R75 solve=140.813s/root=43.072s/exact=51.644s/212/heuristic=46.436s/548/nodes=30/pool=33319`。三组目标值和有效性均一致：`R25=31893`、`R50=43625`、`R75=55007`，`valid=true`。

这次结果说明，在当前代码和 setupR 三组上，root preprocessing 与 ng-DSSR 后续 fixing/window 兼容，并且实际能显著降低运行时间。R25/R75 的节点数、pricing 次数和列池规模几乎完全一致，但 heuristic/exact/master LP 时间约减半，说明主要收益来自预处理后 root/子节点定价空间更轻，而不是搜索树结构变化。R50 的 pricing 次数略有变化，但目标和 valid 一致，差异仍在同一分支语义下。需要注意，当前日志没有把 `timeIndexedRootPreprocess.done` 的详细 summary 写进 case log，只能从开关、后续 pricing 行为和结果对照确认效果；后续应把预处理 summary 作为正式 trace/log 事件输出，方便追踪 `promotedOrdinaryArcs` 和 compact window 收缩幅度。

## 预处理日志修正与 setupR 加速来源复核

2026-07-02 复核 `setupR25/R50/R75` 的 off/on 对照后，需要修正上一节对“主要快在哪里”的表述。当前三组中，预处理组确实更快，但日志显示 R25/R75 的节点数、pricing 次数、列池规模、root/子节点的最后一轮 exactStats 基本逐项一致。例如 R75 的 node1 均为 `nodes=30` 路径下同一 root 分支结构，root pool 均为 `15109`，最后 exactStats 中 `labels fw kept/dominated=1614/2294, bw kept/dominated=362/74`、`subtree cand/fixed=1560/950` 一致；node2 的 exactStats 和 subtree 统计也一致。差别主要体现在同样调用和同样计数下的耗时：R75 root pricing 从 `55.529s` 降到 `28.609s`，exact 从 `9.545s` 降到 `4.917s`，heuristic 从 `45.983s` 降到 `23.692s`，master LP 从 `3.885s` 降到 `2.221s`。R25 也类似，root pricing 从 `60.506s` 降到 `31.974s`，exact 从 `10.506s` 降到 `5.384s`，heuristic 从 `50.000s` 降到 `26.590s`。

因此这批 setupR 日志能确认的结论是：预处理没有改变 BPC 搜索结构和最终列集合，收益主要表现为每次 pricing / LP 调用的实际耗时下降，而不是可见 label 数、join 数或 subtree fixing 数减少。由于此前 `timeIndexedRootPreprocess.done` 没有稳定进入 case log，不能仅凭旧日志断言耗时下降来自 promoted arc 或 window 收缩；它也可能包含预处理后缓存/热点路径/有效窗口上下文等导致的单次调用变轻。后续若要精确归因，必须在 case log 中记录 root preprocess summary，并在同一配置下重复 A/B，对比 `promotedOrdinaryArcs`、`avgWindowLen`、`avgShrinkRatio` 与后续 pricing 的 `dynamicHEndMax/pricingHorizon/arcPruned/label` 等指标。

本次代码层面做了两个日志修正。第一，`Tree` 中的 `timeIndexedRootPreprocess.start/done` 现在直接写入 trace，不再受 `diagnosticStageHeartbeat` 或 live trace 开关控制；短 smoke 已确认 case log 中会出现完整 `timeIndexedRootPreprocess.done`，包括 `tempPool`、`timeArcs`、`promotedOrdinaryArcs`、`windowJobs`、`avgWindowLen`、`avgShrinkRatio`、`graphFix` 和 `scalarFix`。第二，`pruned_by_dual_bound` 等没有走 `onMasterSolved` 的 close-only node summary 不再打印 `inc=Infinity/bound=Infinity/gap=NaN`，而用 `-` 表示该节点 summary 没有本地 LP 解字段。这个 `Infinity` 原本只是 node-progress reporting 的占位口径，不代表全局 incumbent 真的为无穷；dual-bound pruning 在 PC 内部使用的 incumbent 必须是有限值，最终仍以 CSV 的 incumbent/bound/valid 为准。

## 当前正确性复核

2026-07-02 对 time-indexed root preprocessing、ng-DSSR 继承 fixing/window、强分支 trial 状态隔离、启发式窗口口径和 cut-loop fixing 做了一次静态复核。当前结论是：默认开关仍保持关闭，主线不会被该实验功能自动改变；打开后，临时 time-indexed root 只复制 time-indexed pricing-only arc、compact window 和可聚合的普通 pricing-only arc，不复制临时 pseudo-schedule 列、dual、basis 或 cut 状态，因此不会污染正式 ng-DSSR 的主问题列池。

root 预处理复制回来的普通弧仍按 pricing-only 口径使用。ng-DSSR、completion bound、route enumeration、time-indexed graph pricing 和启发式 pricing 均通过 Node.isPricingOnlyArcForbidden() 或对应 time-indexed 查询消费这些状态；正式 branch row 和当前 RMP 历史列不会被直接删除。后续 root/子节点收敛后仍会继续执行 ng-DSSR 自己的 time-indexed scalar arc fixing 和 subtree arc elimination；实测日志中预处理先提升普通 pricing-only arc 1299 条，root 收敛后 subtree 又固定 3 条普通 arc，说明“预处理后继续 fixing”的路径是通的。

强分支方面，trial 调用会保存并恢复 PC 的节点级缓存和 pricing engine 状态；可复用 child 只在 trial 成功后写回 seed 并标记 strongBranchingSeedPrepared，正式出队时跳过重复 repair/筛列。普通 child 仍沿用旧流程：先继承父节点 restricted columns，出队后带新分支行求一次 LP，必要时 repair，再筛列。因此当前没有发现 strong trial 状态污染正式节点的路径。

仍需保留两个边界判断。第一，active SRI cut 下的 time-indexed helper 默认仍是 no-SRI 松弛口径，只有打开 SRI-aware 开关时才尝试带 SRI state 的 fixing；该部分成本更高，当前不作为默认主线证明。第二，启发式 pricing 对 compact window 默认不再 true-cost recheck，这是基于“compact window 是当前子树安全硬窗”的实验口径；dual profitable window 默认关闭，若显式打开则仍会强制 true-cost recheck。focused javac 已覆盖 TWETBPCConfig、Node、LP、PC、Tree、TimeIndexedRootPreprocessor、TimeIndexedGraphPricingEngine、TimeIndexedScalarCompletionBound 和 HeuristicPricingEngine 并通过；git diff --check 仅报告已有 test-results 文本换行警告，没有代码 whitespace error。

## 后续混合策略和默认开关判断

2026-07-02 继续梳理 time-indexed 与 ng-DSSR 的组合方式。当前已经实现的是“先用 time-indexed root preprocessing 生成普通 pricing-only arc 和 compact window，再进入 ng-DSSR root”。后续还可以考虑另一种方向：当 horizon 很大但 root 阶段 ng-DSSR 仍能处理时，先用 ng-DSSR 做 root；进入后续子节点后，由于分支、subtree fixing 和 pricing-only arc 已经删掉大量普通弧，此时 time-indexed 图规模可能显著下降，可以在子节点改用 time-indexed pricing 或更强的 time-indexed helper。这个方向暂不实现，只作为后续实验方案保留。

之所以把 time-indexed 嵌入 ng-DSSR 做增强，核心不是它的列更强，而是它的 arc fixing 是带时间的 `(i,j,t)`。这比普通 completion bound 直接删 `(i,j)` 更细：即使某条普通弧不能完全删除，也可能删除其中大量完成时间副本，从而压缩每个 job 的 compact window。这个窗口再反过来缩小 ng-DSSR 的函数定义域、pricing horizon 和扩展空间。因此 time-indexed helper 的主要价值是“更细粒度地缩时间”，而不是替代 ng-DSSR 的 elementary/ng 定价。

当前后续 node 建 time-indexed 图时，会考虑 node 上已经写回的普通禁弧和 pricing-only 禁弧，包括 completion-bound subtree arc elimination 写入的 `Node.forbidPricingOnlyArc()`。也就是说，如果 ng-DSSR 在 node 闭合后通过 subtree/pricing-only 方式把某个 `(i,j)` 写进 node，后续 time-indexed 图会跳过它。相反，ng-DSSR 某一轮 pricing 内部只用于本轮剪枝、没有写回 node 的 local completion-bound skip，不会被 time-indexed helper 继承；这是刻意分层，避免把没有形成 node 证书的临时剪枝当成子树状态。

当前默认开关口径为：route enumeration 总开关 `enableRouteEnumeration=false`，先保持关闭；two-stage strong branching 默认打开；`timeIndexedCompletionBoundInRoundArcFixing=false`，不在每轮 pricing 内部反复跑昂贵的 time-indexed tightening；`timeIndexedCompletionBoundCutLoopArcFixing=false`，cut-loop 间 fixing 也默认关闭，后续只在 SRI/time-indexed cut 对照里显式打开。强分支 trial 和正式 pricing 都通过 child node 的禁弧状态构造 LP/定价器，启发式 pricing 也通过 `isPricingArcForbidden()` 检查普通 forbidden 和 pricing-only arc，因此会考虑当前 node 上已有的禁弧。

## setupR cost20 旧日志误读修正

2026-07-03 重新核对 `tmp-ngdssr-40-2-setupR-all-cost20-nostrong-alns-currentbase-20260702` 和 `tmp-ngdssr-40-2-setupR-all-cost20-nostrong-alns-tirootpre-20260702b` 后，确认上一节把旧 `tirootpre` 组解释为“root preprocessing 生效后加速”是不严谨的。证据很直接：两组 `config.*` 行除了 `liveTraceLogPath` 外完全一致；`tirootpre` 组虽然打印了 `systemProperty.twet.bpc.fullDomainCompare.timeIndexedRootPreprocessingForNgDssr=true`，但日志里没有 `config.enableTimeIndexedRootPreprocessingForNgDssr=true`，也没有 `timeIndexedRootPreprocess.start/done`，更没有任何 `Pricing[TimeIndexedGraphPricing] node=0`。这说明该次运行时这个 JVM property 只是被配置快照打印出来，没有被当时的配置对象消费，也没有真正执行 `TimeIndexedRootPreprocessor`。

因此，`99.101/189.443/277.038s` 对比 `48.015/73.986/140.813s` 这组旧结果不能作为 root preprocessing 加速证据。它只能说明在当时两次看似同配置运行中，第二组的单次 LP / pricing 耗时显著更低。例如 R50 的第一轮 ng-DSSR 统计结构几乎相同，但 exact line 从约 `1020.849ms` 降到 `610.120ms`；master LP 汇总也从 `initial=7.373s, after_pricing=11.625s, after_column_filter=5.461s` 降到 `initial=2.814s, after_pricing=2.737s, after_column_filter=1.369s`。这个差异不能归因于 `timeIndexedRootPreprocessingForNgDssr`，因为当时它没有生效。

当前代码下真正执行 root preprocessing 的日志应同时满足三点：`config.enableTimeIndexedRootPreprocessingForNgDssr=true`、出现 `timeIndexedRootPreprocess.start/done`、并且在正式 node 之前出现临时 `Pricing[TimeIndexedGraphPricing] node=0`。后续比较 root preprocessing off/on 必须用这种带完整 trace 的新日志，不能再用旧 `tirootpre-20260702b` 作为该功能的效果依据。

补充代码证据：git grep 检查 `bd3d9bc3^` 时，src 下不存在 `timeIndexedRootPreprocessingForNgDssr` / `enableTimeIndexedRootPreprocessingForNgDssr`；该字段和 `TimeIndexedRootPreprocessor` 是 `bd3d9bc3 Add time-indexed root preprocessing for ng-DSSR` 才加入的。因此旧 `tirootpre-20260702b` 日志中只有 `systemProperty...=true` 而没有 `config.enable...`，不是简单的 trace 缺失，而是当时运行代码没有消费这个 property。

## 2026-07-03 当前代码默认 ALNS 60s 下的 off/on 复跑

为排除此前 ALNS 初始阶段耗时过长和旧日志误读的干扰，当前代码把 `TWETBPCConfig.alnsMaxRuntimeMillis` 默认值改为 `60000ms`，默认接受准则仍为非 SA（`alnsUseSimulatedAnnealingAcceptance=false`）。本轮没有显式传入 `twet.bpc.fullDomainCompare.alnsMaxMillis`，因此实际使用默认 60 秒上限；其他配置保持 setup cost 系数 20、`nearestK8/top10`、no-strong、ALNS seed、completion bound/scalar/arc fixing/subtree、midpoint probe/reuse、dual-bound pruning、route enumeration 关闭，只切换 `timeIndexedRootPreprocessingForNgDssr`。

当前代码 off 组结果为：R25 `81.227s/root 81.226s/exact 6.756s/heuristic 43.946s/master 2.160s/pool 9334`；R50 `74.457s/root 41.417s/exact 15.470s/heuristic 32.480s/master 7.519s/pool 19762/nodes 9`；R75 `205.720s/root 87.613s/exact 69.800s/heuristic 74.907s/master 21.035s/pool 33295/nodes 30`。三组目标分别为 `31893/43625/55007`，均 valid。

当前代码 on 组确认真正执行了临时 time-indexed root：日志中有大量 `Pricing[TimeIndexedGraphPricing] node=0`，R25 临时 pool 到约 `81900` 后再进入正式 ng-DSSR。on 组结果为：R25 `105.020s/root 105.019s/exact 4.694s/heuristic 11.670s/master 60.168s/pool 9753`；R50 `84.615s/root 66.195s/exact 10.536s/heuristic 9.355s/master 35.369s/pool 16049/nodes 9`；R75 `139.062s/root 83.010s/exact 28.198s/heuristic 14.621s/master 54.318s/pool 24127/nodes 28`。目标和 valid 与 off 组一致。

这轮结论比旧日志更清楚：root preprocessing 确实能显著减轻后续 ng-DSSR 的 pricing，尤其 heuristic/exact 时间都明显下降，R75 的总时间从 `205.720s` 降到 `139.062s`。但它也会引入很重的临时 time-indexed RMP / master LP 成本，R25/R50 上总时间反而从 `81.227/74.457s` 增加到 `105.020/84.615s`。因此该开关不是默认必开项，更适合作为 horizon/列生成尾部较重实例的实验增强；小实例或 root 本身已经很轻时，预处理 master 开销可能超过后续收益。

## 2026-07-03 旧 setupR cost20 fast run 的再次归因

再次核对 `tmp-ngdssr-40-2-setupR-all-cost20-nostrong-alns-currentbase-20260702` 和 `tmp-ngdssr-40-2-setupR-all-cost20-nostrong-alns-tirootpre-20260702b` 后，当前判断需要进一步收紧：旧 fast run 不是 time-indexed root preprocessing 生效后的收益。旧 fast 日志里确实有 `systemProperty.twet.bpc.fullDomainCompare.timeIndexedRootPreprocessingForNgDssr=true`，但只有 system property 行，没有 `config.enableTimeIndexedRootPreprocessingForNgDssr=true`，没有 `timeIndexedRootPreprocess.start/done`，也没有任何 `Pricing[TimeIndexedGraphPricing] node=0`。结合 `bd3d9bc3^` 源码中还不存在该字段的消费路径，可以确认当时这个 property 只是被打印出来，并没有真正驱动预处理。

旧 fast 与 old slow 的核心差异也不是搜索树或列集合。以 R75 为例，两组都是 `nodes=30`、`pricing rounds=760`、`added columns=33586`、root 的 `pool=15109/restricted=15109`，node1 的 label、join、completion bound 和 subtree fixing 统计也逐项一致。差异主要体现在同样调用和同样计数下的耗时几乎整体减半：R75 中 HeuristicPricing 从 `93.272s/548` 降到 `46.436s/548`，GCNGBBStyleNgDssrPricing 从 `102.330s/212` 降到 `51.644s/212`，master LP 汇总从 `27.555s` 降到 `13.456s`，RMIH 从 `21.889s` 降到 `11.373s`，subtree arc elimination 从 `5.593s` 降到 `2.768s`。R25 也类似，pricing rounds 和 added columns 完全一致，但 heuristic/exact 耗时约为 old slow 的一半。

因此这次“旧 fast 为何很快”的证据结论是：它不是某个 BPC 组件多剪了，也不是 root preprocessing 缩了窗口，而是同一算法工作量下的执行吞吐显著更高。日志没有记录足够的 JVM/CPU/系统负载/CPLEX 内部状态来继续唯一定位原因；当前可确认的是它不能作为 root preprocessing 的收益证据。后续做 off/on 对比必须使用新日志中同时出现 `config.enableTimeIndexedRootPreprocessingForNgDssr=true`、`timeIndexedRootPreprocess.start/done` 和临时 `TimeIndexedGraphPricing node=0` 的 run，且最好同一批次重复跑两次，避免把单次吞吐波动当作算法效果。

## 2026-07-03：time-indexed root preprocessing 的适用性补充

当前判断是：time-indexed root preprocessing 在一部分算例上有效，主要是因为它能较快闭合 root，并把普通 arc fixing 与 compact window 传给后续 ng-DSSR，使后续节点的扩展域明显缩小。但它不是无条件收益。若 pricing horizon 或离散时空弧规模过大，或者 pseudo-schedule root tail 很长，临时 time-indexed RMP 和最短路扫描本身会变成额外成本。

因此更合理的使用条件不是“总是开”，而是当估计的 `n^2 * pricingHorizon`、dual-window 后的有效 horizon、以及前几轮 time-indexed pricing 的列生成规模处于可控范围时再使用。另一种后续可试方案是 capped pilot：先跑少量 time-indexed root pricing 轮次，观察 arc scan、候选列、LP 时间，如果预处理明显失控则中止，回到直接 ng-DSSR。这个方向先记录，不立即修改主线。

## 2026-07-04：cut-loop fixing 与初始 time-indexed 预处理口径

这次重新核对 `wet050_001_2m` 上 direct time-indexed root 和 ng-DSSR root preprocessing 的差异后，确认初始列差异的直接来源不是 time-indexed pricing 算法，而是 ALNS 初始列历史口径。direct time-indexed 那次显式使用 `alnsMaxRuntimeMillis=600000` 和 `alnsUseSimulatedAnnealingAcceptance=true`，`InitialColumnBuilder` 在 `accepted` 模式下从 ALNS accepted history 加入了更多机器列，初始列为 53；ng-DSSR preprocessing 那次使用默认 60 秒、SA 关闭，初始列为 8。`TimeIndexedRootPreprocessor` 只是复制正式 root 的 `seedColumnIds` 到临时 pool，因此进入临时 time-indexed root 的第一轮 RMP dual 会随这些 seed 差异改变。后续比较应统一默认口径：SA 关闭、ALNS 60 秒；如需更长 ALNS 或 SA，只作为显式实验变量。

在 `ng-DSSR partial + SRI + time-indexed root preprocessing` 中，cut-loop 之间的 fixing 现在分两层处理。第一层复用刚闭合 exact pricing 留下的 ng-DSSR completion bound，按当前 `incumbent - LP bound` 做普通 `(i,j)` arc 判断，并以 pricing-only 方式写入当前 node；没有 reusable completion bound 时不额外重建 PWLF bound，避免 cut-loop 过重。第二层继续执行已有的 time-indexed scalar helper fixing，用时空图证据更新 pricing-only arc 和 compact window。active SRI cut 下默认仍使用 no-SRI relaxed fixing：这不是完整 SRI-aware 证书，但作为松弛下界是安全偏弱的；SRI-aware helper 仍保留为显式开关，不作为默认路径。

## 2026-07-04：time-indexed root 预处理后的 elementary seed 转移开关

本次在 `TimeIndexedRootPreprocessor` 上补了一个默认关闭的实验开关：`timeIndexedRootPreprocessingSeedElementaryColumns`。原来的 root preprocessing 只把 time-indexed root 闭合后得到的 pricing-only arc、time-indexed arc 状态和 compact window 证据传给正式 ng-DSSR root，不复制临时 graph 列。这个口径最干净，但也会丢掉 time-indexed root 已经发现的一些 elementary 序列信息。新的开关用于测试一个折中方案：临时 time-indexed root 收敛以后，从其最终 restricted RMP 里筛出 job 不重复的 elementary 列，按当前临时 root 最终真实 dual 下的 reduced cost 从小到大排序，最多复制 `timeIndexedRootPreprocessingSeedColumnLimit` 条到正式 ng-DSSR root 的 seed 列里，默认上限为 200。

这个处理只复制真实列对象需要的 sequence 和 cost，不复制临时 LP basis、dual、cut、pseudo-schedule 非基本列，也不改变 root preprocessing 的 arc/window 证据生成流程。选择 restricted RMP 而不是整个临时 Pool，是为了让“来自 time-indexed 收敛结果”的口径更接近最终根节点列集。由于临时 root 已经闭合，候选列不要求 reduced cost 为负；排序只表示这些 elementary 列在临时 root dual 下更接近有用。日志里的 `timeIndexedRootPreprocess.done` 现在会额外输出 `seedElementaryCols`，方便判断本次实际转移了多少条新 seed。

该功能仍应作为实验开关使用。它可能改善正式 ng-DSSR root 的初始列质量，减少前几轮 pricing 波动；也可能增加初始 RMP 规模，使 master LP 变重。因此默认保持关闭，后续需要在相同 ALNS、强分支、time-indexed preprocessing 配置下做 A/B 对比。

2026-07-04 检查补充：seed 转移顺序调整到 graph/scalar fixing 和 ordinary arc promote 之后执行，并跳过已经包含 root pricing-only 禁弧的候选列。这样开关打开时不会主动把刚由 time-indexed 预处理聚合禁止的普通弧列重新塞进 ng-DSSR root seed；仍然只按 sequence 过滤普通弧，不尝试用 time-specific arc 判断 sequence，因为同一 sequence 可能有多个完成时间版本。

## 2026-07-05：40-2 time-indexed 窗口下重复访问诊断
本次新增 `TWETBPC.LP.RepeatVisitWindowDiagnostic`，用于检查 time-indexed pricing 在给定完成时间窗口下是否仍可能重复访问同一个 job。诊断逻辑是：对每个 job j 枚举第一次完成时间 t，再枚举中间任务 k，检查 `j -> k -> j` 在当前窗口和已继承的普通弧、时空弧禁用状态下是否仍可行。这个检查不是看一个粗略的 `窗口长度 >= 最短回路时间`，而是显式检查第一次 j、k、第二次 j 的完成时间都落在当前窗口内。

在 `data/40-2/wet040_001_2m.dat` 上，若不运行 ALNS，只用很少初始列，base hard window 下 40/40 个 job 都可重复；初始 root dual window 下只有 2/40 个 job 可重复；time-indexed root compact window 单独使用时仍有 40/40 个 job 可重复；dual window 与 compact window 取交集后仍只有 2/40 个 job 可重复。若使用默认 60 秒 ALNS，base hard window 下仍是 40/40；初始 root dual window 下为 6/40；root compact window 单独使用时为 30/40；dual window 与 compact window 取交集后为 4/40。

由此当前判断是：40-2 原始算例并不是静态 hard window 本身让 time-indexed pseudo-schedule 接近 elementary；compact window 单独也不足以完全阻止重复访问。真正强的是 root dual window 与 compact window 的交集，尤其在当前 ALNS incumbent 和 root dual 下，大多数 job 已经没有足够时间完成 `j -> k -> j` 的二次访问。因此该算例中 time-indexed 的 relaxed 列和 elementary/ng 列 gap 很小，有一部分原因可能是有效窗口已经让重复访问空间极窄。这个结论只针对当前 `wet040_001_2m` 与当前 seed/dual 口径；宽 due window、放大时间尺度或不同 incumbent 下仍需要重新诊断。

实现时还确认了一点：直接用 `new Data("data/40-2/wet040_001_2m.dat", true, true)` 会触发 `Data.debug_set()` 的固定 60-3 调试口径，因此诊断复用 `TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine()` 读取 40-2 数据。为此仅把该 loader 从包内可见改为 public，不改变求解主线。

2026-07-05 补充判断：这解释了为什么该算例上 time-indexed root bound 会显得很强。time-indexed pricing 理论上允许 pseudo-schedule 和重复访问，但在当前 root dual window 与 compact window 共同作用下，绝大多数 job 已经无法完成 `j -> k -> j` 的二次访问。因此实际参与定价的负列空间接近 elementary，非基本列松弛带来的 gap 被显著压小。需要注意这不是 time-indexed 方法本身强，而是该算例、当前 incumbent/dual 和窗口收缩共同造成的局部现象；在宽 due window、时间尺度放大或窗口不够紧的算例上，这个性质可能消失。

2026-07-05 进一步推论：40-2 上 time-indexed bound 强，不只是算例窗口紧，也说明当前 ng-DSSR 的初始 ng-set 可能偏大。若在当前 dual window 与 compact window 交集下，绝大多数 job 已经无法完成二次访问，那么这些 job 的 ng memory 对防止重复访问几乎没有贡献，只会增加 label 状态、削弱占优、提高 exact pricing 成本。尤其 `nearestK=8` 这类较大的初始 ng-set，在这种窗口口径下很可能是过度加强。更合理的方向是：按当前 effective window 判断哪些 job 仍可能重复访问，只对这些 job 初始化较小 ng memory；其余 job 只保留自身，依赖时间窗不可达性天然禁止二次访问。该策略需要注意 dual window 是当前 LP dual 下的临时窗口，因此只能作为本轮 pricing 的初始化依据，不能作为跨 node 永久结论。后续可做 A/B：root 与子节点分别比较 `self-only`、小 `nearestK`、以及基于 repeatability 的动态 ng-set 初始化，观察 DSSR 迭代次数、平均 final ng-set 大小和 exact pricing 时间。

## 2026-07-05：按 effective window 过滤初始 ng-set 的试验
本次实现了实验开关 `enableNgDssrWindowRepeatabilityInitialFilter`，常用 runner 通过 `twet.bpc.fullDomainCompare.ngDssrWindowRepeatabilityFilter=true` 打开。逻辑是在 ng-DSSR 每轮 relaxed pricing 初始化后、正式扩展 label 前，根据本轮 effective window 判断某个 job 是否仍存在 `j -> k -> j` 的二次访问时间区间。如果某个 job 在当前窗口下无法重复访问，则把它从所有初始 ng-set 的被记忆成员里删除，但不清空其他 job 的整个 ng-set，因为其他 job 仍可能需要记住那些仍可重复的任务。

这个判断采用区间交集而不是逐点枚举：对 `j -> k -> j`，第一次完成 j 的时间 t 必须同时落在 j 的第一次窗口、k 的反推窗口、第二次 j 的反推窗口中。若所有 k 都没有交集，则 j 在当前窗口下视为不可重复。该判断只使用普通弧禁用和当前 effective window，不依赖时空弧逐点禁用，因此是安全偏弱、计算很轻的 O(n^2) 过滤。

在 `wet040_001_2m` 上做了两个 smoke。未开启 time-indexed root preprocessing 时，虽然 ng-DSSR 日志显示 `piWindow=enabled`，但当前 exact pricing 的 effective window 仍判断为 `repeatable40/nonRepeatable0/removedMembers0`，说明仅靠这次 LP dual window 没有删掉初始 ng memory。开启 time-indexed root preprocessing 后，root preprocessing 把平均窗口缩到约 211，正式 ng-DSSR root 中出现 `repeatable30/nonRepeatable10/removedMembers53`，repair 阶段可到 `repeatable27/nonRepeatable13`，说明 compact window 参与后过滤确实生效。

但同配置 node-limit 对照下，过滤没有明显加速 ng exact：开启过滤为 `solve=127.288s, root=104.456s, exact=1.484s/6 calls, pool=20131`；关闭过滤为 `solve=141.477s, root≈120s, exact=1.307s/6 calls, pool=20184`。两者 bound 相同，为 22490.571429。由于 root preprocessing 和 master LP 时间波动较大，且 ng exact 本身已经很轻，当前只能说明该过滤正确生效，但不能证明它在这个 40-2 root 上带来稳定收益。后续若要继续验证，应放到更大或未被 compact window 强烈压缩的节点上，并同时观察 DSSR 轮数、non-elementary route 数量和 label kept/dominated。

2026-07-05 补充：这也解释了此前 2-cycle completion bound 和当前 repeatability 过滤效果接近的原因。二者本质上都围绕 `j -> k -> j` 这种最短二次访问结构判断“一个 job 是否还有形成重复访问/短环的空间”。在当前 40-2 有效窗口下，大多数 job 已经很难形成这类环，因此无论从 2-cycle bound 还是从 initial ng-set repeatability 过滤角度看，新增信息都有限；但 2-cycle bound 需要在更多状态和函数/窗口上反复计算，成本明显更高。当前判断是：这些技术在“环本来就很少”的算例上不会成为主要加速来源，后续更应关注窗口是否足够紧、ng exact 单次 pricing 成本，以及哪些实例会真的产生大量可行重复访问。

2026-07-05 具体证据：在同一个 40-2 root preprocessing 后的 node 上，增强后的 `RepeatVisitWindowDiagnostic` 同时输出 hull 判定和 time-indexed 逐点判定。`rootCompactWindow` 下 time-indexed 逐点判断为 30/40 个 job 可重复，而 hull 判断为 32/40，其中两个 hull-only 例子都不是窗口交集错误，而是具体时空弧已被 pricing-only fixing 删光。例一：`job=15, via=9`，hull 给出第一次完成 `j15` 的可行区间 `[794,826]`，33 个整数时点都满足三次 completion window，但所有 33 个 `(15,9,t)` 时空弧都被禁掉。例二：`job=36, via=20`，hull 区间 `[594,599]` 有 6 个整数时点，第一段可走，但所有 6 个 `(20,36,t)` 回程时空弧被禁掉。`initialDualAndRootCompactWindow` 下也有类似例子：`job=8, via=7`，hull 区间 `[1227,1231]` 有 5 个整数时点，但 5 个 `(8,7,t)` 时空弧全被禁掉。由此可以确认：ng 当前的 hull 过滤偏宽，主要差距来自未消费 time-indexed 时空弧禁用信息，而不是区间交集公式本身。

2026-07-05 代码口径更新：初始 ng-set 默认不再存储任务自身。原因是当前 ng-DSSR 的 memory 更新本身会执行 `memory.add(currentJob)`，因此访问到 `j` 后 `j` 一定进入 label memory，下一步 `j -> j` 会被 extension set 构造自然排除；`j ∈ ngSet[j]` 对这个实现是冗余项。真正影响重复访问的是 `i ∈ ngSet[j], i != j`，即访问 `j` 后是否继续记住之前访问过的其他 job。同步调整后，`ngDssrInitialNgSetSize` 的语义改为“不含 self 的邻居数量”，`full` 模式也只加入其他 job，history warm-start 记录和恢复时都会忽略 self。这个修改不会放开直接重复访问，因为 `memory.add(currentJob)` 保持不变；它只减少冗余 memory 成员和统计口径中的自环项。

2026-07-05 修正：repeatability 过滤应发生在初始 ng-set 选择之前，而不是先按 nearestK/dualPair 选完再删除。原先“先选后删”会导致最近的不可重复 job 被删掉后，没有用更远但仍可重复的 job 补齐，等价于无意中把初始 ng-set 变得更小。现在 `initialize(lp)` 先计算 effective window，再首次构造 ng-set：`full`、`nearestK` 和 `dualPair` 都只把可重复 job 作为候选成员。history warm-start 当前不与 repeatability filter 混用；只要本轮 repeatability filter 生效，就跳过 history warm-start。后续 DSSR 轮不重建初始 ng-set，仍沿用上一轮根据 non-elementary route 更新后的集合。

2026-07-05 进一步调整 repeatability 判定口径。之前 ng-DSSR 只用 hull 交集判断 `j -> k -> j` 是否可能存在，这个口径只看普通 arc 和 effective window，不消费 time-indexed root preprocessing / scalar helper 留下的 `(i,j,t)` 时空禁弧，因此在 `rootCompactWindow` 下会把部分已经被时空弧完全删光的回路仍判为可重复。现在流程改成：如果实例的加工时间、setup 时间和 due window 两端都是整数，并且当前 exact pricing 有 dual profitable window，或者当前 node 已经继承了 time-indexed compact window / 时空禁弧，则使用逐整数完成时间的 time-indexed 判定；该判定先用窗口反推收窄第一段完成时间区间，再检查 `node.isTimeIndexedPricingOnlyArcForbidden(from,to,t)`，所以能识别“窗口看起来可行，但所有时空副本已被删掉”的情况。若不满足整数时间条件，或当前没有 dual/compact/time-indexed 证据，则退回原 hull 判定。日志里的 ng-DSSR summary 会输出 `ngWindowRepeatability=timeIndexed` 或 `hull`，用于区分本轮过滤口径。

这个处理对应前面的分析：dual window 是当前 LP dual 下的临时信息，所以 exact pricing 每次重新算 effective window 时可以顺手做一次精确 repeatability 判断；compact window 是 node 继承状态，在没有开启每轮 time-indexed tightening 时，同一 node 内通常不会频繁变化，因此理论上可以缓存，但当前先不引入 node 级缓存，避免把状态版本管理复杂化。对于非整数实例，当前不把 relaxed bucket 图反写为硬窗口，也不使用 `(i,j,t)` 逐点判定；这时 hull window 往往就是原始定义域，过滤自然较弱或无效。若未来要让小数实例也吃到这种精确过滤，需要先把实例统一 scale 到整数时间，而不是用向下/向外取整后的放松图直接删真实时间点。

2026-07-05 进一步把“是否为精确整数时间实例”的判断从 ng-DSSR 和 `TimeIndexedScalarCompletionBound` 内部移到 `Data`。现在 `Data.setPenaltyFunctions()` 会统一刷新 `exactIntegerTimeInstance`，判断只检查原始 processing、setup 以及 due-window 两端是否为整数；派生 hard window、dual window 和 compact window 不参与这个静态实例属性。`TimeIndexedScalarCompletionBound` 与 ng-DSSR repeatability filter 都只读取 `data.isExactIntegerTimeInstance()`，不再各自扫描一遍 `p/s/d`。这样做的原因是 Tanaka loader 和若干小测试会在 `Data` 构造后覆写 `p/s/d/s` 并重新调用 `setPenaltyFunctions()`，把刷新放在这个数据重建入口更不容易 stale，也避免在 pricing 模块里嵌套重复判断。复杂度仍是一次 `O(n^2)` 扫描，但发生在数据/成本函数重建阶段，不进入每轮 exact pricing。

2026-07-05 继续收紧 ng-DSSR repeatability filter 与 history warm-start 的关系。当前决定是：如果本轮 repeatability filter 已经基于当前 effective window 生成了可重复访问 mask，则本轮不再应用 history warm-start，也不把本轮 final ng-set 写回 history。原因是 repeatability filter 是当前 node/window/dual 口径下的初始化剪裁，而 history warm-start 是跨 pricing 的经验集合；两者如何加权、是否应该记录 filtered 口径下的 final set 还没想清楚。为避免历史样本和当前窗口剪裁语义混在一起，先在日志中标记 `ngWarmStart=skippedRepeatability`，后续如果要联用再单独设计。

2026-07-05 再次检查补充：repeatability filter 的 hull 口径必须使用原始 `setup+p` double 持续时间，不能对持续时间取 `ceil`；否则在小数实例或非 time-indexed 精确模式下会把可行二次访问误判为不可重复。当前实现已经只在整数逐点 time-indexed 判定中使用整数 duration。另一个文档口径修正是：早前“history warm-start 恢复后清理不可重复成员”的描述已不再是当前实现；当前实现是只要本轮 repeatability filter 生效，就完全跳过 history warm-start，并且不记录该轮 final ng-set。

## 2026-07-06：dual window 不能参与 root preprocessing arc fixing

本次复查确认了一个核心语义问题：root preprocessing 中的 time-indexed graph arc fixing 不能使用 dual profitable window。dual window 是当前 LP dual 下为了定价找负列而做的临时搜索收缩，它只能说明“在该 dual 下，窗口外的完成时间不会产生负 reduced cost 列”。但 root preprocessing 的 arc fixing / compact window 会写回正式 root，并传给后续 ng-DSSR pricing、枚举和子节点；这里需要证明的是某条时空弧或某个完成时间不可能出现在任何改进当前 incumbent 的可行列中，判定阈值是 `UB - LB`，不是 0。因此，用 dual window 缩图后再做 arc fixing 会把“没有负列”的证据误当成“不会改善 incumbent”的证据。

针对 40-2 的 22582 问题，临时诊断已经能复现该现象：time-indexed preprocessing root 的 LP bound 为 `22487.64705882354`，当前 incumbent 为 `22582`，真实最优 22580 的两条机器列在该 dual 下 reduced cost 分别约为 `87.8235` 和 `4.5294`，合计约 `92.3529`，小于 `incumbent - LB = 94.3529`。也就是说，这两条列不是负 reduced cost 列，但它们仍然足以把 incumbent 从 22582 改到 22580。诊断还显示 compact window 没有排除这两条列，普通 pricing-only arc 也没有排除它们；真正挡住它们的是 time-indexed graph fixing 写回的 `(0,17,0)` 和 `(0,26,0)` 时空弧。这说明错误来源不是 compact window 本身，而是 graph fixing 使用了 dual/profitable window 口径。

当前代码中风险位置主要有三类。第一，`TimeIndexedGraphPricingEngine.ArcFixingSolver` 通过默认 `computeGraphWindow(data, lp)` 建图，而该默认路径会在 root/no-cut 下启用 dual window；因此 `applyPaperReducedCostArcFixing()` 用于 root preprocessing 或 cut-loop fixing 时存在误删风险。第二，`promoteFullyForbiddenTimeIndexedArcsToPricingOnly()` 也使用同一个默认 graph window；如果该窗口已经被 dual window 缩小，那么“窗口内没有可用时空副本”不能推出普通 `(i,j)` 弧全局不可用，普通弧 promotion 也会过强。第三，`TimeIndexedGraphPricingEngine` 的 exact 模式和 `TimeIndexedGraphRank1CutPricingEngine` 会在 dual window 下用 reduced cost 反推列 objective cost；pre-heuristic 模式已经在 dual window 下用 `TWETColumnEvaluator` 回刷真实成本，但 exact graph pricing / rank-1 graph pricing 没有同样的统一回刷，因此也存在把受限窗口成本写入 Pool/RMP 的风险。

相对安全的部分也需要区分清楚。`HeuristicPricingEngine` 的 dual window 默认关闭；若显式打开，它会在返回列前用原始 objective 重算 true cost 和 true reduced cost，因此不属于这次问题。ng-DSSR / partial dominance 的 completion-bound reusable bounds 在 `dualProfitableWindowEnabled` 或 zero-dual 排除存在时会返回 null，不会把带 dual window 的 completion-bound 函数拿去做 subtree arc elimination；ng-DSSR 内部的 time-indexed scalar tightening 若只改本轮 `effectiveJobHStart/End`、不写回 node，也只是当前 pricing 的局部剪枝。但一旦某个 helper 把结果写入 node 的 pricing-only arcs、time-indexed forbidden arcs、compact window，或者用作跳过后续 exact pricing 的 certificate，就不能依赖 dual window。

后续修复口径应保持简单：所有 arc fixing、ordinary arc promotion、root preprocessing 写回 compact window / time-indexed arc 的路径，都应使用“安全 fixing window”，即基础 hard window 与已继承的安全 compact window / branch / pricing-only 禁弧，不使用当前 dual profitable window。dual window 仍可用于 pricing 内部加速，但返回到 Pool/RMP 的列必须按真实 objective 回刷；如果某个 pricing engine 想返回“内部列族无负列”的 certificate，也必须确认该 certificate 覆盖的是完整当前 pricing 图，而不是 dual-window 限制后的局部图。

2026-07-06 已按该口径做最小修正。新增 `TWETBPCConfig.enableTimeIndexedGraphDualWindow`，runner 属性为 `twet.bpc.fullDomainCompare.timeIndexedGraphDualWindow`，用于控制 time-indexed pricing 本身是否使用 root/no-cut dual profitable window。默认仍为 `true`，便于保留“小图 pricing”的实验口径；设为 `false` 时 time-indexed graph pricing / rank-1 graph pricing 都不再使用 dual window，也就不需要因 dual-window 成本口径做候选列回刷。

同时把会写回 node 的两条路径固定为 no-dual safe window：`TimeIndexedGraphPricingEngine.ArcFixingSolver` 和 `promoteFullyForbiddenTimeIndexedArcsToPricingOnly()` 现在只使用基础 hard window 与已继承的 compact window，不使用当前 dual profitable window。因此 root preprocessing 即使打开 dual-window pricing，也不会再把 dual-window 缩图下的 time arc fixing 或 ordinary arc promotion 证据写回正式 root。time-indexed no-cut exact pricing 和 rank-1 exact pricing 若本轮 graph window 使用了 dual window，则最终选中的候选列会用 `TWETColumnEvaluator` 回刷真实 TWET objective，并重新按当前 dual 计算 reduced cost，仍为负才返回；rank-1 路径的重算同时包含 active subset-row cut dual。pre-heuristic 的 no-negative certificate 暂不因 dual window 禁用，因为当前假设 dual window 对本次 pricing 的最优负列搜索是保真的；本次只修复会写回 node 或写入 Pool/RMP 的 dual-window 风险。
2026-07-06 复查补充：本轮重新扫了 time-indexed dual window 的所有入口，确认当前修改没有把 dual window 继续带入永久写回路径。`applyPaperReducedCostArcFixing()`、root preprocessing 里的 ordinary arc promotion、cut-loop time-indexed graph fixing、Tree 节点闭合后的 time-indexed graph fixing 都走 no-dual safe window；dual window 只保留在 time-indexed pricing 建图内部。rank-1 cut active 时通常不会启用 dual window，原因是 `TimeIndexedGraphRank1CutPricingEngine.canUseDualProfitableWindow()` 要求 root 且 `activeCutIds` 为空；一旦有 active subset-row/rank-1 cut，`graphWindow.dualWindow=false`，因此不会在大量 cut candidate 上反复做 evaluator 回刷。若没有 active cut，rank-1 engine 会委托 no-cut time-indexed delegate。当前效率边界为：开 dual window 时图更小，但只对最终 top 候选回刷真实成本；关 dual window 时图更大，但没有回刷成本。仍需保留的语义假设是 pre-heuristic 的 no-negative certificate 认为 dual profitable window 对本次 pricing 搜索保真；若后续需要最保守对照，可直接设置 `twet.bpc.fullDomainCompare.timeIndexedGraphDualWindow=false`。

## 2026-07-06 dual window 与 time-indexed pseudo-schedule 回刷复核

本次重新测试之前的 time-indexed dual window 问题时，完整 `ng-DSSR + time-indexed root preprocessing + dual window` 配置没有在可接受时间内写出 CSV 或 node summary，已停止，不能作为算法结果解读。但测试过程暴露了一个确定性实现问题：dual-window 成本回刷路径会把 time-indexed exact pricing 产生的 repeated pseudo-schedule 交给 `TWETColumnEvaluator.evaluate(sequence)`，而该 evaluator 只适用于普通单机 sequence；当 sequence 中有重复任务时，`Solution.calCost()` 可能访问到未构造好的节点并抛出 NPE。

修正后的口径是：dual-window 下，只有 elementary/basic sequence 才用 `TWETColumnEvaluator` 回刷真实 objective 和 reduced cost；time-indexed exact pricing 返回的 repeated pseudo-schedule 保留图上路径成本，不再调用普通序列 evaluator。rank-1 time-indexed pricing 的 dual-window 回刷也采用同一边界。这个修改不改变普通基本列的成本回刷，也不把 dual-window 产生的受限成本写入需要真实 objective 的基本列。

验证上，focused `javac` 已通过。随后用 `wet040_001_2m` 跑 direct time-indexed exact dual-window smoke，配置为 `timeIndexedGraphPricing=true`、`timeIndexedGraphDualWindow=true`、关闭启发式、单节点 120 秒限制，结果正常完成到 `NODE_LIMIT`，`valid=true`，`exact=14.950505s/227 calls`，`bound=22487.647059`，未再触发 evaluator NPE。该 smoke 只验证 dual-window exact pricing 路径已经不崩溃，不证明完整 ng-DSSR + root preprocessing 配置已经恢复到最优闭合。
## 2026-07-06：列成本 evaluator 直接函数拼接

后续复查确认，上一段“repeated pseudo-schedule 不回刷”的口径只是为了绕开旧 evaluator 的实现限制，不是算法上必须如此。非基本列本质上仍然是一条恢复出来的 job 序列，成本可以按固定序列的前向分段线性函数递推来计算：首任务使用 `penaltyFunction[j].setDomain(s_0j+p_j,T)`，后续任务做 `shiftX(s_ij+p_j)+penaltyFunction[j]`，再加 setup cost 并做 prefix-minimize。这个过程不要求 job 不重复；若重复访问导致可行域为空，则返回 `big_M`，候选列自然被丢弃。

因此本次把 `TWETColumnEvaluator.evaluate()` 从 scratch `Solution` 口径改为直接 PWLF 拼接。旧实现会新建 `Solution`、初始化所有机器函数、调用 `updateInformationM(0)`，而该函数内部已经会计算一次 `calCost(0)` 并更新局部搜索相关辅助结构，随后 `evaluate()` 又再次调用 `calCost(0)`。这些工作对“只求一条列的成本”都是冗余的。新实现只保留固定序列成本所需的 forward function 递推，并在中间函数用完后释放，避免大量 evaluator 调用时反复构造无关对象。

基于这个修改，time-indexed exact pricing 和 rank-1 time-indexed pricing 在 dual-window 下选中候选后，不再因为 sequence 中有重复 job 而跳过 true-cost recheck。只要图使用了 dual window，最终候选都会用新的 evaluator 回刷真实全域 objective，并按当前 dual/cut dual 重算 reduced cost；若不再为负或成本为 `big_M`，就不进入 Pool/RMP。这样既保留 dual window 缩图的收益，也避免把受限窗口成本写成永久列成本。

验证上，相关类 focused `javac` 通过。临时对拍 200 条随机普通序列，新的 `evaluate()` 与仍走 `Solution.calCost()` 的 `evaluateTiming().cost` 一致。临时 micro benchmark 使用 5000 条随机序列，直接 evaluator 约 47.7ms，旧 `evaluateTiming().cost` 约 482.7ms，说明该路径在大量回刷、枚举和 repair 场景下能显著降低常数成本。更宽的 RMIH 编译检查因为当前命令 classpath 未包含 CPLEX jar 而失败，属于验证环境限制，不是本次 evaluator 改动的编译错误。
## 2026-07-06：ng-DSSR exact pricing 热路径复查

本次复查 `evaluateTopLastMidpointColumnTiming(lp)` 和当前 ng-DSSR 主线的高频函数调用。`evaluateTopLastMidpointColumnTiming` 只在 `bidirectionalMidpointStrategy=columnTaskMedianTopLast` 时启用，默认 `default` midpoint 不走这条路径。它先从当前 restricted columns 中筛出与 node 兼容的内部列，按 reduced cost 从小到大排序；然后对前 `2 * bidirectionalMidpointColumnLimit` 条列调用 `TWETColumnEvaluator.evaluateTiming()`，得到最后完工时间、半程完工时间和任务完工时间样本；最后按 lastCompletion 从大到小取前 `bidirectionalMidpointColumnLimit` 条，用这些列的任务完工时间中位数作为 Tmid 的参考。这是一个 midpoint 选择启发式，目的是避免只看 reduced cost 最小的短列或早完工列，把双向 labeling 的分割点拉得过早。它不是默认主线热点。

按当前主线看，真正的耗时仍集中在四类位置。第一是 label 扩展：`extendForward/extendBackward` 每次扩展都必须构造新的 PWLF frontier，流程包括 `shiftX`、`add(jobPenalty)`、`shiftY`、normalize，以及 visited/ng-memory/dominance/extension set 更新。这里不能简单改成只求最小值，因为后续 dominance 和 join 都需要完整函数。第二是 dominance graph 维护：同一个 reachable-set key 下会保留多个 label，它们各自贡献不同时间段的下包络，导致后续扩展和 join 都被放大。这个方向若要大幅优化，需要带可追溯来源的 envelope/segment 设计，改动大且会牵涉 ng-memory 语义，暂不动。第三是 join pair 数量：当前 `tryJoin` 已经用 `PiecewiseLinearFunction.findMinimalShiftedSumValue()` 替代旧的 `shiftX + add + findMinimal`，completion-bound arc fixing 也已经使用同类直接 min-sum，因此单次函数拼接常数已经降了一截。后续若还要优化 join，重点应是减少进入 function evaluation 的 pair，而不是再优化一次 add。第四是 completion-bound 构造本身，它仍需要完整的 forward/backward bound 函数传播，不能完全替换成标量。

当前可继续尝试但风险或收益不确定的优化有三类。其一是更强的 join 前过滤，例如 group 级 best-first 或更强的 range-restricted lower bound；前者可能让有负列的中前期更早找到好列并收紧阈值，但对最后证明无负列的 certificate 轮帮助有限，后者已经有开关，但早前在 40-2 上 pruning 不明显。其二是 dominance graph 内部的同 key 多 label 压缩，这可能是数量级收益点，但需要解决函数下包络来源和 ng-memory/sequence 追溯，不能做成简单合并。其三是继续减少 evaluator 类调用；普通列成本 `evaluate()` 已经改成直接 PWLF 拼接，`evaluateTiming()` 仍只在 midpoint 统计和少量诊断路径使用，若以后把非默认 midpoint 作为主线，再考虑给它实现直接 timing 版。

2026-07-06 进一步实现了两个低风险常数优化。第一，ng-DSSR 在 SRI 未开启时不再为每个 label 单独构造 `noSriFrontier`。原逻辑中 `frontier` 和 `noSriFrontier` 会分别走一套 `shiftX + add + shiftY + normalize`，但无 SRI 时二者语义相同；现在仅在 `sriPricingEnabled=true` 时构造独立 no-SRI 函数，无 SRI 时传 `null`，由 `FunctionLabel` 构造器把 `noSriFrontier` 映射到 `frontier`，因此 completion-bound pruning 读取仍然非空。第二，在 `extendForward/extendBackward` 进入 `shiftX/add` 前增加时间窗交集检查：先用父 label 的当前函数区间、setup/processing delay、下一任务的 dynamic effective window 和当前半域 `[0,Tmid]` / `[Tmid,H]` 判断是否必然无交集，若无交集直接返回。这只是提前识别原本 `shiftX/add` 后会变成空函数的扩展，不改变 reduced cost、dominance 或生成列语义。

2026-07-06 复查上述两个优化、due window 口径和列成本 evaluator。`noSriFrontier` 优化只在无 SRI 时复用 `frontier`，SRI 开启时仍保留独立 no-SRI 函数，completion-bound pruning 读取的函数非空且语义不变。扩展前的窗口交集检查与 `shiftX()` 后按原 domain trim 的语义一致，并使用本轮 dynamic effective window 与半域窗口，只会跳过原本 `shiftX/add` 后为空的扩展。`TWETColumnEvaluator.evaluate()` 与 `evaluateTiming().cost` 在普通 40-2、setupR75 变体和 setup-cost 系数 20 三组各 500 条随机序列上对拍，最大差异均为 0，说明当前直接 PWLF 拼接没有破坏 due date/window、硬窗、setup time 和 setup cost 的成本口径。复查时还发现 `Data.debug_set()` 仍会覆盖文件头的 `n/m/scale`，导致重新编译后直接读取 40-2 数据可能按 60 任务解析并在 `SETUP` 行报错；已将该旧调试入口改为 no-op，避免正式数据读取被调试值污染。

## 2026-07-07：主线高频函数冗余复查

本次继续按当前主线从 pricing、LP 列筛选和增量建模路径复查冗余。当前没有发现新的正确性问题；主要可安全处理的是若干“明明只需要看当前列，却扫描所有 job 或所有 arc dual”的常数级低效点。

首先，`LP.computeReducedCost(TWETColumn, PricingDualSnapshot)` 原来对 job dual 扫描 `1..n`，对 arc dual 则扫描整个 dual 矩阵，并对每个非零 arc dual 调用 `column.getArcVisitCount(from,to)`。这在 branch seed 筛选、strong trial seed 筛选和新增列 reduced cost 排序中会被大量调用，且 arc 部分实际复杂度接近“列数 × arc 数 × 序列长度”。现在改为按列自身的访问 job bitset 扫 job dual，并沿非空 column sequence 顺序直接减去 `0 -> first`、内部相邻弧和 `last -> sink` 的 arc dual；空序列仍按旧 `getArcVisitCount()` 口径不计 `0 -> sink`。重复 job 或重复 arc 会在 sequence 中自然重复出现，语义等价于原来的 arc visit count 口径，但避免了整张矩阵扫描。

其次，`LP.addColumnToCurrentModel()` 和 `addOutsourcingColumnToCurrentModel()` 在增量加列到当前 CPLEX 模型时，原来覆盖行部分也按 `1..n` 扫描。现在内部机器列用 `TWETColumn.getJobs().nextSetBit()` 只遍历实际访问任务，仍用 `getJobVisitCount(job)` 写覆盖系数；外包列用 `TWETOutsourcingColumn.getJobSet().nextSetBit()` 遍历集合任务，避免如果 jobs 列表存在重复时重复加系数。strong branching M 判定里的列化外包 required job 检查也改成遍历当前列实际访问任务。

再次，`HeuristicPricingEngine` 在启用 dual-window true-cost recheck 时，原来为了得到一条候选序列的真实成本会重新构造完整 forward profile。由于 `TWETColumnEvaluator.evaluate()` 已经改成直接 PWLF 拼接并支持重复序列，本次将该路径改为直接调用 evaluator。这个改动只影响需要 true-cost recheck 的启发式窗口口径；默认不开 heuristic dual window 时不改变主线行为。

本次复查也确认了一些暂不处理的点。`buildCoverageConstraints()` 属于整模型重建，当前按覆盖行建模，若要改成列式构造需要重写较多结构，暂不动。`computeReducedCost()` 中 subset-row cut 系数仍需要按 cut 语义计算，不能简单稀疏化。PC 里稳定化 dual point 的列贡献构造仍有扫描 arc 矩阵的写法，但它不是当前大量 seed 筛选的主热点，后续若稳定化重新启用再单独处理。ng-DSSR 的主要剩余瓶颈仍是 label 扩展、dominance graph 内同 key 多 label、join pair 数量和 completion-bound 函数传播；这些属于算法结构问题，不适合用小的冗余清理来硬改。

验证上，本次无法使用 `mvn`，因为当前命令环境没有 Maven；改用项目 Eclipse classpath 中的 CPLEX/CP Optimizer jar 做 focused `javac`，覆盖 `LP.java` 与 `HeuristicPricingEngine.java`，编译通过。后续如果要量化收益，应优先观察 branch seed 筛选、strong trial phase1、动态加列时的 master/LP 时间，而不是期待 exact labeling 的 label 数量直接下降。

2026-07-07 继续检查 ng-DSSR/partial dominance 的扩展热路径时，发现 `extendForward/extendBackward` 中 `shiftX()` 产生的临时 PWLF 在 `add()` 后没有释放。`add()` 会构造新函数，不接管输入函数；`TWETColumnEvaluator.evaluate()` 的直接 PWLF 口径也已经采用“add 后释放 shifted”的写法。因此本次只释放 `shifted/shiftedNoSri` 这类临时对象，并在 normalize 后发现新 frontier 为空时释放未进入 label 的结果函数。dynamic job penalty 来自缓存数组，不能释放；进入 label 的 `nextFrontier/nextNoSriFrontier` 也不释放。该改动不改变 reduced cost、dominance key、ng memory 或生成列，只降低高频扩展里的临时 segment/对象池压力。focused `javac` 覆盖 ng-DSSR、partial dominance、LP、HeuristicPricingEngine 和 PWLF，编译通过。

## 2026-07-07：time-indexed 内部列与外包模式、以及 dual window 的 arc fixing 边界

当前需要区分两种外包建模方式。若外包仍是 `masterVariables` 显式变量，内部机器列族可以正常使用 time-indexed 相关组件：直接 time-indexed pricing、ng-DSSR 前的 time-indexed pre-heuristic、以及 ng-DSSR 的 time-indexed root preprocessing 都只作用于内部机器列，不和显式外包变量冲突。若外包是 `columns/columnized/sp1` 列化外包，当前普通 pricing engine 顺序仍可包含内部 time-indexed pre-heuristic、ng-DSSR exact 和最后的外包列 pricing；但 `TimeIndexedRootPreprocessor.shouldRun()` 明确要求 `!useColumnizedOutsourcing()`，因此 root preprocessing 暂不支持列化外包。这是为了避免临时 no-cut/no-SRI time-indexed root 只复制内部列证据，却没有同步复制外包列族、外包 membership 分支和外包列 reduced-cost 证书，导致预处理证据和正式 root 的列族不一致。后续若要支持列化外包，应把它作为独立扩展：内部 time-indexed root 只生成内部列证据，同时正式 root 仍必须单独保留/修复外包列族，不能把“内部列族闭合”误当作整个 pricing 闭合。

本次 `wet040_001_2m` 全开测试还说明了 time-indexed root preprocessing 与 ng-DSSR completion-bound fixing 的作用层次不同。preprocessing 在时空图上固定了 `1,754,589` 条时空弧，但聚合到普通 `(i,j)` pricing-only arc 时只有 `3` 条，因为普通弧只有在所有可用时间副本都被删光时才能提升为普通禁弧；只要某条普通弧还剩一个可用时间副本，就不能全局禁止。相比之下，ng-DSSR root 收敛后的 completion-bound subtree fixing 是在当前真实 ng-DSSR dual/bound 口径下直接扫描普通弧，root summary 为 `cand=1560,fixed=1207`，所以普通弧层面明显更强。两者并不矛盾：time-indexed fixing 更细粒度，主要贡献可能是缩 time window；ng-DSSR subtree fixing 更粗粒度，但在 node 闭合后用 `UB-LB` 口径能直接固定大量普通弧。

dual profitable window 的边界也进一步明确。pricing 内部可以用 dual window 缩小搜索图，因为它只需要保证“本次当前 dual 下的最优负 reduced-cost 列不丢”；但会写回 node、传给子树、用于 route enumeration 或后续 ng-DSSR 的 arc fixing / compact window 不能使用 dual window。原因是 arc fixing 判断的是“经过某个弧或某个时间点的最好完整列是否仍可能改善 incumbent”，阈值是 `UB-LB`，而 dual window 只围绕当前 dual 下的负列搜索等价，不能证明窗口外的正 reduced-cost 列不会组合出更好的整数 incumbent。40-2 的 22582/22580 诊断已经给过具体证据：真实最优改进列在某次 time-indexed preprocessing dual 下并非负 reduced-cost 列，但两条列合起来仍足以把 incumbent 从 22582 改到 22580。因此当前安全口径是：dual window 只用于本次 pricing；所有永久 fixing、ordinary arc promotion、compact window 继承都必须使用 no-dual safe window。

## 2026-07-07：time-indexed root preprocessing gap 异常定位

本次 `wet040_001_2m` 的 ng-DSSR 全开压力测试中，time-indexed root preprocessing 日志显示 `tempPool=26507`、`graphFix gap=3067.4958`，按 incumbent 22582 反推临时 time-indexed LB 约为 19514.5。这个数值本身不是日志计算错误，但它和旧 direct time-indexed root 的口径不一致：旧 direct run 的 root pool 约 82806/84133，root LB 为 22487.647 左右。

对比日志后，关键差异在列成本口径。旧 direct time-indexed root 中，`acceptedBestRc` 与 `bestPseudoRC` 基本一致，说明 time-indexed exact graph 返回的是图上 pseudo-schedule cost。当前 preprocessing 中，dual-window exact graph 会对选中的候选调用 `TWETColumnEvaluator` 回刷 sequence 成本，第一轮已经出现 `bestPseudoRC=-167420.5`、`acceptedBestRc=-280321.5` 的明显差异。这个回刷对 pre-heuristic 的 elementary 列是合理的，因为最终进入 Pool/RMP 的是真实机器列；但对 exact time-indexed relaxation 中允许重复任务的 pseudo-schedule 列，会改变原本的松弛模型口径，导致临时 LP bound 异常偏弱、列数减少，并使 ordinary arc promotion 只剩 3 条。

因此当前判断是：这次 preprocessing 的大 gap 不是 ALNS 或 seed=200 本身造成的，而是 exact time-indexed pseudo 列在 dual window 下被按真实 sequence evaluator 回刷，破坏了和旧 direct time-indexed root 可比的 relaxation 语义。后续修正方向应保持简单：time-indexed pre-heuristic/elementary 候选在使用 dual window 时继续回刷真实成本；exact time-indexed graph pricing 和 root preprocessing 的 pseudo-schedule 列应保留图上 pseudo cost，或者直接关闭 exact graph 的 dual-window 回刷。永久 arc fixing / compact window 写回仍然继续使用 no-dual safe window，不把 dual window 证据写回 node。

随后用同一 `wet040_001_2m` 配置只把 `timeIndexedGraphDualWindow=false`，并把 `maxNodes=1` 作为 root-only 验证，结果直接确认上述判断。no-dual preprocessing 中 `piWindow=disabled`，`acceptedBestRc` 与 `bestPseudoRC` 基本一致；临时 time-indexed root 闭合时 `tempPool=77858`，`graphFix gap=94.3529411765`，反推临时 LB 为 `22582 - 94.3529411765 = 22487.6470588235`，与此前 direct time-indexed root 的 `22487.647059` 对齐。该次还固定了 `3,541,282` 条时空弧，普通弧提升 `1310` 条，平均 compact window 长度降到 `216.275`，明显不同于 dual-window preprocessing 的 `tempPool=26507`、`gap=3067.5`、普通弧提升 `3` 条。因此当前结论进一步收紧为：root preprocessing 若要复制 time-indexed root 的 relaxation 证据，应禁用 dual window，或者至少不能在 exact pseudo-schedule 列上做 dual-window true-cost 回刷。

## 2026-07-07：dual-window 候选列成本与最终正值列对拍

按“dual-window 每轮最优候选列是否与 evaluator 一致、dual/no-dual 收敛后正值列序列是否一致”这个口径做了针对性诊断。诊断只增加日志，不改变定价和主问题逻辑。`wet040_001_2m`、time-indexed root preprocessing、`maxNodes=0` 下，dual-window run 的最终正值列全部满足 `storedCost` 与 `TWETColumnEvaluator.evaluate(sequence)` 一致，差异只有数值误差；no-dual run 也是如此。因此不是“最终进入 RMP 的正值列成本没有刷新”这个问题。

真正的差异出现在 dual-window pricing 的候选阶段。dual-window 下每轮图上 reduced-cost 最好的候选列，经 evaluator 回算后经常不是同一个成本口径：第一轮出现 `graphCost=135483`、`trueCost=22582`、`costDiff=112901` 的重复 pseudo-schedule；后续也出现 `graphCost=100989`、`trueCost=1.0E8` 这种 BigM 级差异。接近闭合时差异会缩小，但前中期已经足以改变加入 LP 的列集合和 root 轨迹。

dual/no-dual 收敛后的正值列序列也完全不同。dual-window run 有 39 条正值列，no-dual run 有 18 条正值列，按 sequence 字符串比较 `common=0`。对应的预处理结果也完全不同：dual-window 为 `tempPool=26507`、`promotedOrdinaryArcs=3`、`avgWindowLen=1881.475`、`graphFix gap=3067.50`、正值列目标和约 `19514.50`；no-dual 为 `tempPool=77858`、`promotedOrdinaryArcs=1310`、`avgWindowLen=216.275`、`graphFix gap=94.35`、正值列目标和约 `22487.65`。

当前结论是：dual-window 不是单纯加速同一个 time-indexed root preprocessing 过程，而是在 exact graph pseudo-schedule 列与 sequence evaluator 之间引入了成本语义差异，导致 root LP 走到另一套列集合和更弱的 fixing/window 证据。pre-heuristic 只返回 elementary 列时可以用 dual-window 后再按真实成本筛选；但 exact time-indexed root preprocessing 如果要复制 graph relaxation 的证据，不能把 dual-window 产生的 pseudo-schedule 列按 sequence evaluator 当作同一列族处理。后续安全口径仍是：永久 arc fixing 和 compact window 写回使用 no-dual 证据；dual-window 只作为本次 pricing 搜索加速，不能直接作为 root preprocessing 证据源。

### 2026-07-07 复核：dual-window recheck 的真实根因

进一步按同一条日志中的 repeated sequence 做了最小 Java 对拍，结论比前一版更明确：`evalCost=1.0E8` 不是数值溢出，而是 `TWETColumnEvaluator` 在固定序列 PWLF 递推中返回的 BigM/不可行标记；更关键的是，`evaluate(sequence)` 会受到静态 `Utility.curUpperBound` 影响。`PiecewiseLinearFunction.minimizePrefixInPlace()` 使用 `Utility.curUpperBound` 作为前缀最小初值，因此如果 ALNS/incumbent 阶段把该值留在 22582，pricing recheck 里很多真实成本高于 22582 的序列会被截成 22582。

对日志第一轮 best graph candidate 的 sequence `[5,2,5,2,...,34,20,34]`，实验结果为：`Utility.curUpperBound=22582` 时 evaluator 返回 `22582.0`；`Utility.curUpperBound=Utility.big_M` 时 evaluator 返回 `135483.0`，正好对应日志里的 `graphCost=135483.0`。对另一条日志中 `trueCost=1.0E8` 的 sequence `[32,24,32,24,...]`，实验结果为：`curUpperBound=22582` 时 evaluator 返回 `1.0E8`，而重置为 `big_M` 后返回 `100989.0`，正好对应日志里的 `graphCost=100989.0`。

因此当前根因不是 time-indexed 图和 evaluator 本质上算了不同对象，也不是 repeated sequence 必然无法 evaluate，而是 `TimeIndexedGraphPricingEngine` 在 dual-window recheck 前没有像 `HeuristicPricingEngine`、`GCNGBBStyleBidirectionalNgDssr` 那样调用 `Utility.resetCurUpperBound(Utility.big_M)`。这会把 dual-window recheck 的真实成本回刷污染成 incumbent 截断口径，导致 pseudo 列成本被低估，root LP bound 异常偏弱，后续 arc fixing / compact window 证据也随之变差。

后续修正应保持很小：在 time-indexed graph pricing 进入求解或至少进入 `maybeRecheckSelectedCandidate()` 前，临时把 `Utility.curUpperBound` 设为 `Utility.big_M`，并在 pricing 结束后恢复原值；rank-1 time-indexed graph 中使用 evaluator 的 dual-window/cut recheck 也应采用同一保护。这样可以保留 dual-window 缩图，同时避免 evaluator 使用 incumbent 截断成本。

## 2026-07-07 dual-window preprocessing LP 差异复核

本次重新复核 `wet040_001_2m` 上 time-indexed root preprocessing 的 dual-window / no-dual 差异。旧 dual-window 诊断中，第一轮 best graph candidate 为 repeated pseudo sequence，`graphCost=135483.0`，但 `TWETColumnEvaluator` 回刷得到 `trueCost=22582.0`；随后大量 repeated pseudo 列以类似方式被压成 incumbent 附近成本，导致临时 LP bound 被人为压低，`graphFix gap=3067.4957`，只推广普通 arc `3` 条，compact window 也很弱。这不是 dual-window arc fixing 写回错误，而是列成本评估被 `Utility.curUpperBound` 污染。

修正 ALNS/VND 退出时清理 `Utility.curUpperBound` 后，用同一 dual-window preprocessing 配置重跑。新的日志中同一首个候选变为 `graphCost=135483.0, trueCost=135483.0, costDiff=0.0`，不再出现 `22582` 截断。最终 dual-window preprocessing 得到 `tempPool=80407`、`graphFix gap=94.352941`、`promotedOrdinaryArcs=1301`、`avgWindowLen=232.150`；对应 no-dual 对照为 `tempPool=77858`、`graphFix gap=94.352941`、`promotedOrdinaryArcs=1310`、`avgWindowLen=216.275`。两者 bound 和 fixing 强度已经基本一致，dual-window 只改变搜索路径和列数，不再造成异常弱 LP。

当前结论：原先 “dual-window 下 LP 差很多” 的直接原因是 evaluator 使用了启发式阶段遗留的 `curUpperBound`，不是 dual-window 缩图证明本身失效。dual-window 下 exact graph 的 selected candidate 仍会做 sequence recheck，这可能使某些 sequence 成本比 graph path 成本更低，但修正后该差异是正常的完整序列重定时差异，不再是 incumbent 截断。后续若继续对比 dual-window/no-dual，应以修正后的 `tmp-dualwindow-after-curub-fix-20260707` 和 `tmp-nodual-positive-dump-20260707` 为基准。

## 2026-07-07：dual-window / no-dual-window 预处理并行对比

在修正 `Utility.curUpperBound` 污染后，又按同一当前代码和同一 `wet040_001_2m` 配置做了一次并行 A/B。两组都设置 `maxNodes=0`，因此不进入正式 ng-DSSR root，只比较 `TimeIndexedRootPreprocessor` 自身；两个 JVM 各限制 CPLEX 为 1 线程。共同配置包括 ALNS 60s、ng-DSSR `nearest3/top10`、repeatability filter、strong branching 开关保持一致、time-indexed root preprocessing 开启、复制 elementary seed 200 条。唯一差别是 `timeIndexedGraphDualWindow=true/false`。

结果为：dual-window 组 `timeIndexedRootPreprocess.done ms=70601.785`，总 `solve_s=96.232`，`tempPool=80407`，`promotedOrdinaryArcs=1301`，`avgWindowLen=232.150`，`graphFix gap=94.352941`；no-dual 组 `timeIndexedRootPreprocess.done ms=78299.386`，总 `solve_s=103.983`，`tempPool=77858`，`promotedOrdinaryArcs=1310`，`avgWindowLen=216.275`，`graphFix gap=94.352941`。两组 fixing/bound 强度已经基本一致，说明前一轮大 gap 异常确实已经被修掉。本次并行口径下 dual-window 预处理快约 7.7 秒，但它产生更多临时列、窗口略宽；no-dual 预处理略慢但普通 arc promotion 和 compact window 稍强。当前结论是：dual-window 可以作为预处理内部定价加速手段继续比较，但写回 node 的 arc fixing / compact window 仍必须保持 no-dual safe 口径。

## 2026-07-07：预处理后 root 自动禁用 dual window

继续排查 40-2 中 time-indexed root preprocessing 后，正式 ng-DSSR root 被 `TimeIndexedPreHeuristicPricing` 直接闭合到 22582 的问题。新的诊断确认，预处理写回的 ordinary pricing-only arc、time-indexed arc fixing 和 compact window 本身没有排除已知 22580 最优两条机器列；真正导致 pre-heuristic 图过窄的是正式 root 在已经继承这些缩域证据后仍继续启用 dual profitable window。此时 dual window 不再是“无禁弧 root 图”上的临时搜索加速，而是在一个已经被 fixing/window 缩过的图上再次缩域，会让 pre-heuristic 只能证明错误的局部图闭合。

本次把 `dual profitable window` 的启用条件统一收口到 `PricingCompatibility.canUseDualProfitableWindow()`：只有 depth=0、无 active cuts、且 node 没有 required/forbidden arc、branch-implied forbidden arc、pricing-only arc、time-indexed forbidden arc、time-indexed compact window、adjacency branch 时才允许启用。time-indexed graph pricing、rank-1 graph pricing、启发式 pricing，以及 ng-DSSR/full-domain 等内部 pricing 类都走同一判断。这样 root preprocessing 自身仍可在无禁弧 root 上用 dual window 做本轮 pricing 加速，但一旦 preprocessing 把 fixing/window 写回正式 root，后续 ng-DSSR 或 pre-heuristic 会自动关掉 dual window。

验证上，用 `TimeIndexedTargetColumnAudit` 对 `wet040_001_2m` 复查：预处理后 node 状态为 `pricingOnlyArc=1310`、`timePricingOnlyArc=3542132`、`timeWindowJobs=40`，已知 22580 两条目标列均通过 compact/time-indexed arc 检查；正式 root seed LP 为 22580。随后 pre-heuristic summary 显示 `piWindow=disabled`，`bestPseudoRC=-8737`，`certified=false`，不再错误返回闭合证书。因此后续会继续进入 ng-DSSR exact pricing，而不是直接把 22582 当成最优。
## 2026-07-08：ng-DSSR join 前 dominance-node envelope 压缩思路

本次讨论的是另一个降低 join 数量的方向：不在 label 扩展过程中合并同一个 dominance node 内的 label，而是在正反向扩展全部完成、进入 join 之前，对同一个 `(direction, terminal job, dominanceSet)` 下的 label 做临时 envelope 压缩。当前 `DominanceNode` 已经维护 `labelEnvelope`，因此如果只为了计算某个 dominance node 与另一侧 label 的最小 reduced cost，理论上可以用该 node 的下包络代替逐个 label 做函数拼接；为了恢复真实列，需要在 envelope 每一段记录来源 label，最终从取到最小值的 segment 回到原始 label/father chain。

这个方案和“过程内合并 label”不同。过程内合并后还要继续扩展，必须处理每一段对应的 ng memory、visited/SRI 状态和后继扩展语义，改动很大；join 前临时压缩发生在所有扩展结束后，不需要让合并对象继续扩展，所以 trace 只需要能恢复 join 产生的真实路径，复杂度明显低一些。

但它不能简单做成“同一个 dominanceSet 无条件只保留一个 envelope”。当前 join 仍会检查 `forward.ngMemorySet` 与 `backward.ngMemorySet` 是否相交；SRI 开启时还会根据两侧 SRI counts 计算 join shift。同一个 dominance node 内的 label 虽然 `jid` 和 `dominanceSet` 相同，但 `ngMemorySet`、SRI counts、father path 可能不同。如果直接用未过滤的 aggregate envelope，最小段可能来自一个与另一侧 label 不兼容的来源 label，此时真正可行的最优段可能来自另一条成本稍高的 label。

因此当前更稳的判断是：这个方向可行，但需要把“兼容性过滤”放进 join-envelope 的构造中。低风险版本可以先把 dominance-node envelope 当作更强的 join lower bound，用于提前剪掉整个 node；若要真正减少 funcEval，则应对固定另一侧 label 过滤兼容的 label 后再构造 traced envelope，或者把 `ngMemorySet` / SRI 状态纳入更细的 join-envelope key。收益可能很大，因为 W300 诊断中同一个 active dominance bucket 平均有上百个 live label；风险主要在 traced envelope 的来源记录和兼容性缓存，而不是 PWLF 下包络本身。

## 2026-07-08：dominance key 补集与 ng-memory 的关系

进一步讨论 join 前 dominance-node envelope 压缩时，`dominanceSet` 的补集能不能直接当作 ng memory 使用。剥掉 zero-dual excluded、required outsourced 等所有 label 共同项后，当前 key 的补集本质上是 `ngMemorySet ∪ 当前 frontier 下直连时间不可达 job`。因此它是实际 `ngMemorySet` 的上界，而不是等价集合。同一个 dominance node 内，某个 job 落在补集里，可能是因为它真的在某条 label 的 ng memory 里，也可能只是这条 label 从当前 frontier 再直连该 job 已经时间不可行；不同 label 对同一个 job 的原因还可能不同。

由此得到的结论是：可以把这个补集当作“有效阻塞集合”的保守上界，用于快速充分判断。若 forward node 和 backward node 的补集不相交，则两边所有真实 label 的 ngMemorySet 必然也不相交，此时 node-envelope 到 node-envelope 的拼接在 ng 兼容性上是安全的；但若两个补集相交，不能据此判定所有 label pair 都冲突，因为交集可能只是不可达项与不可达项，或者一侧 ng memory 与另一侧时间不可达项。这个情况下必须回退到更细粒度：按真实 ngMemorySet 分组构造 traced envelope，或直接走原来的 label-level join。换言之，`complement(dominanceSet)` 可作为 fast-path 的充分条件，不能作为替代 `ngMemorySet` 的精确 join key，更不能因为两个补集相交就剪掉整个 group。

### 2026-07-08：如何利用 dominance key 补集做 join 加速

当前可用的思路可以分三层推进。第一层是最低风险的 node-envelope lower bound。对一个 forward dominance node 和一个 backward dominance node，先不恢复具体 label，只用两边 `labelEnvelope` 做一次 relaxed join 下界。如果这个下界已经不可能小于当前 join threshold，就可以跳过整个 node-node 组合。这个判断不依赖真实 ngMemorySet，因为它只做下界剪枝；即使 envelope 的最优段来自互相不兼容的 label，下界只会偏低，不会把本该保留的组合误删。它的收益取决于 node-envelope 下界能剪掉多少 group，适合作为第一步加统计验证。

第二层是 dominance-key 补集不相交的 fast path。令 `blockedKey = complement(dominanceSet)`，即实际 ng memory 加上当前直连时间不可达项。如果 forward 和 backward 的 `blockedKey` 不相交，那么真实 `ngMemorySet` 必然不相交，可以直接把两个 node 的 traced envelope 拼接。这里需要 envelope segment 记录来源 label；最终最小 reduced cost 落在哪个 segment，就用对应的 forward source label 和 backward source label 恢复真实序列。这个 fast path 是安全的，但只覆盖 `blockedKey` 不相交的 node pair。

第三层是 `blockedKey` 相交时的精确压缩。此时不能直接判冲突，因为交集可能来自直连时间不可达项。更合理的做法是在每个 dominance node 内按真实 `ngMemorySet` 再分组，每组构造一个 traced envelope。group-group join 时检查真实 ngMemorySet 是否相交；不相交就拼 envelope，相交就跳过。这样仍保持当前 ng-DSSR 的 join 语义，但能把“同一个 dominance key 下几十个 label”压成若干个 ngMemory group。若同一个 dominance node 内真实 ngMemorySet 种类远少于 label 数，这个方案会直接减少 funcEval；若种类接近 label 数，收益有限但不改变正确性。

因此当前建议的实现优先级是：先加统计，记录每个 active dominance node 内 label 数、真实 ngMemorySet distinct 数，以及 node-node `blockedKey` 不相交比例；再做第一层 lower-bound filter；如果统计显示 distinct ngMemorySet 明显少于 label 数，再做第三层 traced envelope group。这个路径比过程内合并 label 风险小，因为它只发生在 join 前，不需要让合并后的对象继续扩展；难点集中在 traced envelope 的来源记录和缓存失效，而不是 dominance key 本身。

### 2026-07-08：两个“只用下包络 join”的实现方案

进一步讨论后，明确目标不是只做较弱的 lower-bound 过滤，而是希望最终 join 尽量直接使用下包络，并能从最优段追溯到真实 label 恢复列。围绕这个目标，目前有两个可行方案。

方案一是把 ng-DSSR 的 dominance key 改成真实 `ngMemorySet`，不再把当前 frontier 下直连时间不可达 job 写进 key。这样同一个 dominance node 内所有 label 的真实 ng 状态一致，node 的 traced `labelEnvelope` 可以直接用于 join：两个 node 的 `ngMemorySet` 不相交就拼接 traced envelope，最优 segment 回到 source label 恢复路径。这个方案语义最统一，join key 和 dominance key 是同一套真实 ng 状态；缺点是会削弱或改变当前 dominance graph 拓扑，因为原来 key 里利用了直连时间不可达信息。它可能减少 join，也可能增加扩展/占优压力，实验结果不容易区分是 key 变化还是 traced envelope join 带来的收益。

方案二是保持当前 dominance key 不变，但在每个 dominance node 内额外按真实 `ngMemorySet` 维护一组 traced 下包络。当前 node 的普通 `labelEnvelope/dominanceEnvelope` 仍服务于现有 dominance 流程；新增的 join envelope 只服务于 join。join 时遍历 forward/backward 的真实 ngMemory group，只有 group 的 `ngMemorySet` 不相交时才拼接 traced envelope，最优 segment 再回到 source label。这个方案不改变当前 dominance 语义，只替换 join 的执行方式，更适合作为第一版验证。需要注意的是，label 被 predecessor envelope 删除、被 partial trim 或同 node trim 后，对应真实 ngMemory group 的 traced envelope 必须同步重建，不能保留 stale source label 或旧 frontier。

当前判断是：两个方案在理论上都合理。方案一结构更简单，但会同时改变 dominance 强度和 join 方式，风险较大；方案二工程上多一层 group envelope，但能保持当前 labeling/dominance 行为不变，更适合先用于验证“同 key 多 label 的 join 压缩”是否真的带来数量级收益。后续若方案二验证有效，再考虑方案一作为对照实验。

### 2026-07-08：方案二的第一版实现口径

进一步讨论后，第一版不在 label 迭代过程中维护 join envelope。原因是迭代维护虽然看起来少一次全量扫描，但 label 后续可能被 dominance/trim 删除，已经写进某个 `ngMemorySet` key 的 envelope 就需要同步删除或重建，否则会留下 stale source label；为了解决这个问题，过程内维护反而会把 envelope 生命周期绑到 dominance graph 的增删逻辑上，改动面更大。

当前更清晰的口径是：保持现有扩展、占优和 active label 维护完全不变。等正反向 label 全部扩展完成、进入 join 之前，再扫描当前仍然 active 的 label，按 `(terminal job, true ngMemorySet)` 重新分组，为每组构造一个只用于 join 的 traced 下包络。构造下包络时使用现有 join 口径下的前向/后向函数，而不是 `dominanceEnvelope`；每个 envelope segment 记录来源 label，最终 `forward group envelope + crossing arc + backward group envelope` 取得最小值后，可以直接回到对应的 forward/backward source label 恢复真实序列。

这个版本新增的逻辑集中在 join 前后。第一，增加一个临时的 join-envelope 构造步骤，从 active forward/backward label 表扫描生成 `terminal job -> ngMemorySet -> traced envelope group`。第二，新增 traced envelope 的 lower-envelope merge 逻辑，segment 需要带 source label，不改核心 `PiecewiseLinearFunction.Segment`。第三，join 时由“forward label × backward label”改为“forward ngMemory group × backward ngMemory group”：先检查 terminal、crossing arc、真实 ngMemorySet 是否相交；不相交再拼 traced envelope；取到负 reduced cost 后用 segment source label 恢复列，并继续走原来的 elementary/non-elementary 判定和 DSSR 更新。第四，保留旧 label-level join 作为对照/回退口径，便于先做 A/B 和统计压缩率。

这个口径的核心收益点是把同一个 terminal job 下大量相同真实 `ngMemorySet` 的 label 合成一个函数包络，减少 join funcEval 次数；它不会改变前面的 dominance 过程，也不会把“直连时间不可达项”误当作 ngMemory。当前主要待验证的是：每个 terminal job 下真实 `ngMemorySet` 的 distinct 数是否显著小于 active label 数，以及 traced envelope merge 的常数成本是否低于节省掉的 join pair 成本。

### 2026-07-09：join-envelope 第一版实现与 smoke 结果

本次按方案二实现了一个默认关闭的实验开关 `enableNgDssrJoinEnvelopeCompression`，运行参数为 `twet.bpc.fullDomainCompare.ngDssrJoinEnvelopeCompression`。实现上不改现有扩展、占优、active label 维护和旧 join；只有在进入 join 前，如果该开关打开且当前没有 SRI pricing，才扫描 active forward/backward label，按 `(terminal job, true ngMemorySet)` 构造临时 traced envelope group。SRI/full-SRI/limited-memory SRI 下仍回退原 label-level join，因为 group key 尚未包含 SRI counts 和 join shift 状态。

新路径使用 `getForwardJoinExtension()` / `getBackwardJoinExtension()` 的现有 join 口径函数，不直接复用 dominance graph 的 `labelEnvelope`。`TracedJoinEnvelope` 在 merge-min 时保留每个 envelope segment 的来源 label；group-group join 时先检查 crossing arc、terminal job、真实 `ngMemorySet` 相交，再用 traced envelope 直接求最小 shifted sum。若得到负 reduced cost，就回到对应 forward/backward source label，用原来的 `recoverJoinSequence()` 和 `tryGenerateColumn()` 继续处理 elementary/non-elementary 列、DSSR 更新和入池逻辑。

这个版本和旧 join 的列生成节奏不同：旧路径会在一个 backward label 下扫描多个 forward label，并可能返回多个负列；新路径对一个 forward/backward ng-memory group pair 只返回该 pair 的最优拼接列。因此它不保证每轮返回的列数和旧路径一致，但在“证明无负列”时仍是完整的，因为 group envelope 的最小值等价于该 group pair 内所有 label pair 的最小拼接值。若所有 group pair 的 envelope min 都不为负，则该轮没有被 group 压缩漏掉的负拼接列。

初步 smoke 用 `wet040_001_2m`、no ALNS、no heuristic、no strong branching、`maxNodes=1`、`timeLimit=60` 做了 A/B。关闭压缩时为 `NODE_LIMIT, obj=22659, bound=22490, solve=47.501s, exact=34.881s/15`；打开压缩后为 `NODE_LIMIT, obj=22659, bound=22490, solve=60.915s, exact=46.240s/20`。日志显示新路径确实生效，`join candidates visited/dominated=0/0`，并记录了 `joinEnvelope fGrp/bGrp/fLbl/bLbl/seg/gPair/pruned/funcEval`。但这个小 smoke 上没有加速，原因是 traced envelope 构造和 group pair 扫描成本没有被减少的 label-level candidate 扫描抵消，同时每个 group pair 只返一个最优列导致后续 pricing 调用次数增加。当前结论是：该功能只作为实验开关保留，默认不开；后续应在 W300 这类千万级 join 的重实例上再判断是否有收益。

随后进一步复查实现，确认第一版的正确性口径是“完整证书安全，但中前期列生成偏弱”。具体来说，若某个 group-pair 的 traced envelope 最小值已经非负，则这个 group-pair 内所有 label pair 的拼接 reduced cost 都非负，因此不会漏掉负列证书；若最小值为负，则当前实现只返回该 group-pair 的一个全局最优 source-label 拼接。这个最优拼接可能是 non-elementary，旧 label-level join 仍可能继续找到同一 group-pair 内其他负 elementary 列，而第一版会先把 non-elementary 记入 DSSR 更新，导致每轮返回列数偏少、DSSR rounds 或 pricing calls 变多。这不是最优性错误，但会削弱“作为加列器”的效率。

本轮还把 `group.minReducedCost` 改成在 group envelope 完成后统一计算，避免每 merge 一个 label 就扫描一次 envelope；并增加 `joinEnvelopeMs build/join` 统计。新统计显示，在 40-2 smoke 中每轮 envelope 构造和 group join 本身通常只有几十毫秒，例如最后一轮 `build/join=17.667/15.367ms`，而整轮 ng-DSSR 仍为数秒级。这说明该小实例的主要时间仍在 label 扩展、dominance graph 和 DSSR 轮次本身，join funcEval 被压缩两个数量级后也不是总瓶颈。后续若继续优化，应优先在 W300 这类 `funcEval` 千万级且 join 确认为主瓶颈的实例上测试；若要让该路径在中前期也有效，应改成“group envelope 先做证书/预剪枝，负 group 内再返回多个 source pair 列”或在 group 最优为 non-elementary 时回退扫描该 group 内 label pair。

为便于后续判断 exact pricing 的真实耗时结构，本次又在 ng-DSSR summary 中加入 `exactPhaseMs total/init/sink/fw/bw/compact/join/finalize`。其中 `init` 覆盖 SRI/窗口/completion bound/midpoint probe 等初始化，`fw/bw` 是正反向 label 扩展，`compact` 是 join 前 active label 压缩排序，`join` 是 crossing-arc join，`finalize` 是候选列整理入队。该统计只在大阶段外层计时，不进入每个 label 扩展内层循环，避免诊断本身影响 W300 这类重实例。
### 2026-07-09：join-envelope merge/trace 正确性复核

本次重新对照代码复核了 join-envelope 的合并和追踪语义。当前实现没有把 dominance key 或其补集当作 join key 使用，而是在 join 前扫描仍然 active 的 forward/backward label，按 `(terminal job, true ngMemorySet)` 分组。每个分组里的 traced envelope 使用现有 `getForwardJoinExtension()` / `getBackwardJoinExtension()` 得到的 join 口径函数，并在 merge-min 时为每个 segment 记录 source label。group join 时先检查 crossing arc、terminal job 和真实 `ngMemorySet` 相交；通过后再对 forward envelope shift 后与 backward envelope 做最小和扫描，最小值落在哪两个 segment，就回到对应的 forward/backward source label，用原来的 father chain 恢复 sequence，并继续走原来的 elementary/non-elementary 判断、DSSR 更新和入池逻辑。

因此，从正确性上看，当前合并和追踪本身是闭合的：lower envelope 的几何合并只是在函数层面取 min，segment source 始终指向产生该段函数值的原 label；由于 group 内真实 `ngMemorySet` 完全一致，group 级 ng-memory 兼容性判断适用于组内所有 source label pair；SRI 或 limited-memory SRI 开启时该路径自动回退旧 join，避免遗漏 SRI counts / join shift 状态。当前实现不会把 envelope 对象继续扩展，因此也没有过程内合并 label 时的后续扩展状态问题。

仍需保留的限制是效率而不是最优性：一个 forward/backward group-pair 当前只返回其 traced envelope 全局最小的一个 source pair。如果该 pair 是 non-elementary，旧 label-level join 可能还能在同一 group-pair 内继续找到其他负 elementary pair，而当前第一版会先记录 non-elementary route 触发 DSSR 更新，导致本轮返回列偏少、DSSR rounds 增加。这解释了目前 A/B 中 join 时间显著下降但扩展时间可能上升的现象。后续如果继续优化，应考虑在 group 最优为 non-elementary 时回退扫描该 group-pair 内更多 source pair，或让一个 group-pair 返回多个候选，而不是修改 merge/trace 的基本语义。

### 2026-07-09：扩展与占优侧的后续优化判断

进一步检查 ng-DSSR 的扩展和占优热路径后，当前较明确的结论是：低级冗余已经不多，后续收益更可能来自减少 label 数量和减少 dominance/join 触发量，而不是再重写单个 evaluator。`extendForward/extendBackward` 已经在构造 PWLF 前做了时间窗 overlap 检查，无 SRI 时也不再构造第二套 `noSriFrontier`；动态 hard window、compact window 和 pricing horizon 已经进入半域函数和扩展可行性判断。因此扩展侧若继续优化，优先方向应是更强的构造前剪枝，例如用当前 label scalar lower bound 加 transition/job 的便宜下界和 suffix bound，先跳过明显不可能产生负列的扩展，避免进入 `shiftX + add + normalize`。但该下界必须保持保守，不能以当前局部窗口外的受限成本当真实列成本。

占优侧当前 normal graph 插入仍会合并 predecessor envelope，并在插入后通过 `propagateAndTrim()` 向后扫描 successor；partial-list 则按 cardinality bucket 扫 label 并修剪 frontier。两者都正确，但在 W300 这种 active bucket 很大的实例上，瓶颈会体现为扩展后仍保留大量 live label，随后 join 爆炸。后续更有价值的尝试包括：一是统计每个 reachable/dominance bucket 中 distinct `ngMemorySet` 数量与 live label 数量的比例，决定是否在 join 前返回多个 traced source pair；二是用更强的 time-indexed/compact-window repeatability 信息减少初始 ng-set 或 extensionSet，而不是简单调大 ng-set；三是继续改善 completion-bound 的构造前 pruning，但不要在每轮 pricing 内开启太重的 time-indexed tightening。当前不建议优先改 dominance graph 的语义或过程内合并 label，因为这会牵涉 ng memory、father chain、SRI 状态和后续扩展，风险明显高于 join 前临时压缩。
### 2026-07-09 50-3 setupR50 + W300 的 join-envelope A/B

继续用昨天讨论的 `wet050_003_3m_setupR50`、`dueWindowHalfWidth=300` 做单节点 root 对比，目的是看 join-envelope compression 在真正 join-heavy 的场景里能否加速。配置保持为 normal ng-DSSR、nearestK3/top3、ALNS 30s 且关闭 SA、time-indexed root preprocessing、root seed 200、time-indexed pre-heuristic、completion bound、pricing-only subtree、midpoint probe 和 repeatability filter，关闭 strong branching，并设置 `maxNodes=1` 只看 root/node1 的收敛代价。两组只切换 `ngDssrJoinEnvelopeCompression`。

结果如下。关闭 join-envelope 时，`NODE_LIMIT`，总时间 `299.212s`，root `294.935s`，node1 bound `1726.118711`，gap `10.0042%`，pool `8180`，pricing `227` 次，ng-DSSR exact `229.771s / 11` 次，heuristic `13.093s / 34` 次。打开 join-envelope 后，`NODE_LIMIT`，总时间 `417.910s`，root `413.443s`，node1 bound `1726.014329`，gap `10.0097%`，pool 只有 `2854`，pricing `304` 次，ng-DSSR exact `340.522s / 26` 次，heuristic `20.330s / 65` 次。

这个结果说明第一版 join-envelope 的瓶颈不在单轮 join。打开后第一轮 exact 的 join 从传统口径的 `6.262s` 降到 `0.235s`，`funcEval` 从约 `4160.8 万` 降到 `19166`；最后无负列证明轮里，传统口径 join 约 `5.144s`、`funcEval=2045446`，join-envelope 约 `1.186s`、`funcEval=108718`。单轮 join 压缩是有效的。

但整体变慢的核心原因也很明确：当前 group-envelope join 每个 group-pair 只返回全局最优的一个 source label pair。如果这个 pair 对应非基本列，或只产生少量基本负列，本轮返回的基本列会显著减少。关闭 join-envelope 的第一轮 exact 直接返回 `5000` 条列，打开后第一轮只返回 `118` 条；关闭版本总共 `11` 次 exact，打开版本变成 `26` 次 exact。虽然每轮 join 便宜很多，但每轮仍要重新做初始化、completion bound、正反向扩展、dominance/envelope merge 和 master resolve，轮次数增加后抵消并超过了 join 节省。

当前判断是：join-envelope compression 作为“减少 funcEval”的方向是对的，但第一版“每个 group-pair 只取一个 source pair”的列返回口径太窄，容易把中前期批量加列能力打掉。后续若继续做，应优先改成在每个 group-pair 内返回多个候选 source pair，或在 envelope 最优 pair 非基本/不足量时回退到该 group-pair 的 label-level 扫描；否则它更适合最后无负列证明轮，而不适合中前期需要大量加列的轮次。当前开关继续保持默认关闭。
### 2026-07-09: join-envelope OFF/ON 根节点列审计

按要求重新做了 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 的 root 对照，并在 root 收敛后分别导出 restricted/pool 列，对每条列用 `TWETColumnEvaluator.evaluate(sequence)` 重新评估真实成本。OFF 版本目录为 `test-results/bpc/tmp-w300-50-3-r50-joinenv-off-dump-20260709b`，ON 版本目录为 `test-results/bpc/tmp-w300-50-3-r50-joinenv-on-dump-20260709b`。OFF restricted 共 5966 条列，正值列 41 条；ON restricted 共 3100 条列，正值列 41 条。两边所有正值列的 stored cost 与 evaluator cost 差异都在数值误差范围内，ON/OFF 公共正值列 35 条，OFF-only 6 条，ON-only 6 条，公共列目标系数也一致。因此这次没有证据支持“列成本刷新错了”或“最终正值列目标错了”。

进一步用正值列直接重算 RMP 目标，OFF 和 ON 都是 `1726.0143289049`，与各自本次日志里的 root bound `1726.014329` 一致。此前把 `1726.118711` 与本次 dump 结果并列比较是不准确的，该数值来自更早的 A/B 记录，不是这次 `*-dump-20260709b` 的 root 目标。ON-only 正值列中 5 条其实已经存在于 OFF restricted/pool，只是在 OFF 解中取值为 0，且在 OFF dual 下 reduced cost 为数值 0；OFF-only 正值列中 4 条也同样存在于 ON 中取值为 0。这说明本次 ON/OFF 的最终 LP 目标没有矛盾，正值基差异主要是退化和替代列导致。

同时做了交叉审计：OFF 收敛后额外跑一次 envelope join，没有生成额外列；ON 收敛后额外跑标准 label-level join，共生成 520 条负 reduced cost 的 elementary 列，且这些列的 stored cost 与 evaluator cost 全部一致，最小 eval reduced cost 约为 -11.77167。这个证据说明当前第一版 join-envelope 不是等价 exact join：它在每个 `(forward ngMemory group, backward ngMemory group)` 上只取 traced envelope 的一个全局最优 source label pair。如果该 pair 已经被去重、或者不足以返回同一 group-pair 内其它负 elementary 列，当前轮就会少加列；而标准 join 在同一状态下仍能找到其它有效负列。

因此当前结论需要修正为：join-envelope 的 merge/trace 成本口径本身可以对上 evaluator，但第一版“每个 group-pair 只返回一个 source pair”的列生成口径不能作为完整 exact pricing 替代。它可以继续保留为默认关闭的实验开关；后续若要继续做，应改成只把 envelope 作为 group 下界筛选，负 group 内回退到 label-level scan，或者在一个 group-pair 内返回多个 source pair，直到达到和旧 join 等价的加列/证书口径。

对更早的 `tmp-w300-50-3-r50-joinenv-off-clean-20260709` 与 `tmp-w300-50-3-r50-joinenv-on-clean-20260709` 需要单独解释。该组确实出现 OFF `1726.118711`、ON `1726.014329`，并且两边初始 incumbent 都是 `1918`，time-indexed root preprocessing 输出也一致。因此如果 ON 的列都属于同一个 master/同一个 pricing 域，那么把 ON 的最终正值列加入 OFF 的最终 RMP 后，OFF 的 LP 目标应当不高于 `1726.014329`；按 LP 对偶互补性，OFF 最终 dual 下至少应存在一个 ON 列为负 reduced cost。也就是说，这组旧 clean 结果不能简单解释为“ON 少列但路径不同”，它意味着旧 OFF 的 no-negative certificate 不是同一完整列族下的闭合，或者 ON/ OFF 在后续 pricing-only 过滤、列成本或列兼容性口径上已经不完全相同。当前带 dump 的复跑没有复现这个 bound 差异，OFF/ON 都为 `1726.014329`，所以旧 clean 结果只能作为“需要审计当时闭合口径”的证据，不能再作为 join-envelope 正确性的正面或负面定论。
### 2026-07-09：30-3 宽窗口 join-envelope 小规模复现筛查

为避免继续在 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 这种 root 很重的实例上反复等待，本次临时从已有 50-job/60-job 数据中抽取多个 30-job/3-machine 子集，专门检查 `ngDssrJoinEnvelopeCompression` 打开和关闭时 root LP bound 是否会再次出现不一致。测试只看 root，关闭 `TimeIndexedPreHeuristicPricing` 以强制进入 ng-DSSR exact join，其他仍保留 time-indexed root preprocessing、root seed 200、completion bound、pricing-only subtree、midpoint probe、repeatability filter、ALNS 5s 等主线设置。测试目录集中在 `test-results/bpc/tmp-w300-30-3-*`、`tmp-setupcost20-30-3-*` 和 `tmp-audit-30-3-*`。

从 50-3 setupR50 抽取的 first30、mid30、last30、alt30 以及默认 first30 子集，在 `dueWindowHalfWidth=100/150/300` 下均未复现 ON/OFF root bound 差异。其中 W300 无 setup cost 时多数退化为零目标，不具诊断性；加入 `setupCostFromTimeCoefficient=20` 后，五个子集的 ON/OFF root bound 仍全部一致，例如 first30 为 `7730.250000`，alt30 为 `7678.631579`，last30 为 `6798.103448`，mid30 为 `6977.548387`。

随后又从 60-3 数据抽取 first30、mid30、last30、alt30 子集。W300 无 setup cost 仍基本退化为零目标；加入 `setupCostFromTimeCoefficient=20` 后，四个子集 ON/OFF 也全部一致，例如 alt30 为 `2058.032787`，first30 为 `2340`，last30 为 `2459.854015`，mid30 为 `2086.000000`。其中 `wet030_60alt30_3m + W300 + setupCost20` 做了交叉审计：OFF 收敛后额外跑 envelope join 得到 0 条额外列；ON 收敛后额外跑 standard join 也得到 0 条额外列，二者 root bound、pool 规模和 exact 次数均一致。

当前结论是：在这一轮较小但覆盖不同 job 子集、不同窗口宽度和 setup cost 的复现筛查中，当前 join-envelope 实现没有表现出通用的正确性问题。旧的 50-3 clean 记录里 `1726.118711` 与 `1726.014329` 的差异仍应视为特定旧状态下的闭合口径异常或写回状态差异，而不是当前代码下 envelope merge/trace 的普遍数学错误。若后续还要定位旧 50-3 差异，应回到那个具体大实例，重点审计当时 inherited pricing-only/time-indexed writeback、root preprocessing 证据和标准 join 的 no-negative certificate，而不是继续用小实例盲造。
### 2026-07-09：join-envelope 成本口径问题复核

进一步复查 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 的 ON/OFF dump 后，前面对 join-envelope 第一版“证书安全”的判断需要收紧。具体证据是 ON rerun 目录 `tmp-w300-50-3-r50-joinenv-on-rerun-dump-20260709d` 中，序列 `25 17 36 40 29 37 2 49 48 14 33 31 18 23 1 50` 被 `PRICING_EXACT` 加入后，stored cost 为 `301.79999999999893`，但 `TWETColumnEvaluator.evaluate(sequence)` 的真实成本为 `300.0`。同一序列在 OFF rerun 中以 `300.00000000000335` 的成本出现，并且 eval cost 同样为 `300.0`。这说明问题不是 evaluator 或原始目标函数，而是 join-envelope 路径把 group-envelope join 得到的 inferred reduced cost 直接反推成了恢复序列的列成本。

这里的关键区别是：标准 label-level join 的 `reducedCostBound` 来自一个具体 forward label 与一个具体 backward label 的拼接，因此在当前 no-SRI、no-dual-window、PAPER dominance 口径下通常可以直接用它反推 objective cost。join-envelope 路径则不同，它先把同一 `(terminal job, true ngMemorySet)` 下的多个 label 函数取下包络，再对两个 group envelope 求最小和。这个 envelope min 是 group 层面的下界/筛选值，并不天然等于最终通过 segment source 恢复出来的那条 concrete sequence 的全域最小 objective。当前 `tryGenerateColumn()` 沿用 `PricingColumnCostRechecker.buildInferredColumn()`，而 `requiresExactColumnCostRecovery()` 在 no-SRI、no-dual-window、PAPER dominance 下返回 false，因此坏列没有被 evaluator 回刷，最终污染了 RMP 列成本。

因此，之前“若所有 group-pair 的 envelope min 都不为负，则该轮没有被 group 压缩漏掉的负拼接列”的说法也不能直接成立。它只对 group envelope 的函数下界成立，但当前代码还要把这个下界映射回具体 sequence 并进入列池；如果不对恢复出来的 sequence 做真实成本复核，就可能出现 stored reduced cost 接近 0、但真实 eval reduced cost 仍为负的情况。本轮结论是：join-envelope 第一版可以继续作为默认关闭的实验方向，但若要再启用，至少必须对 envelope 产生的候选列统一使用 `TWETColumnEvaluator` 回刷真实 objective，并重新用当前 dual 判断 reduced cost；更完整的版本还需要把 envelope 作为 group 下界筛选，负 group 内返回多个 source pair 或回退 label-level scan，不能只返回一个代表列并把它当作完整 exact pricing 证书。

补充更正：上面说“至少必须回刷”不能理解为根因。根因不是 evaluator 是否调用，而是 join-envelope 用 group 下包络替代了 label-pair 枚举以后，丢掉了“同一 sequence 由多个 split / 多个 label pair 生成并取最低 reduced cost”的机制。标准 join 对每个具体 forward/backward label pair 都做拼接；如果同一 sequence 被多个 split 生成，`rememberGeneratedCandidate()` 会按 signature 保留 reduced cost 更小的那个。因此标准 join 可以不默认回刷，也能通过枚举所有 split 找到该 sequence 的最低成本。

join-envelope 不同。它在每个 `(terminal job, true ngMemorySet)` group 内只保留下包络上可见 segment 的 source label。某条 sequence 的最佳 timing/split 可能对应的 label 在该时间点被 group 内另一个 label 的函数压住，于是这个最佳来源不会作为 envelope segment source 暴露出来。后续 group-pair join 只能从 envelope argmin 的可见 source label 恢复一条代表 sequence；即使恢复出来的是同一条 sequence，也可能只是该 sequence 的一个非最佳 split/timing。`301.8` vs `300.0` 的例子正是这种现象：真实 evaluator 给出的最优成本为 300，但 envelope 路径入池时使用的是某个可见 envelope split 推导出的 301.8。

因此更准确的结论是：回刷只能修正“已经被 envelope 路径恢复出来”的 sequence 成本；它不能解决 envelope 没有枚举到同一 group-pair 内其它负 elementary sequence 的问题。若要让该方向正确且接近旧 join，应该把 envelope 用作 group-level lower-bound 筛选；一旦 group-pair 的 envelope min 为负，就需要在该 group-pair 内继续 label-level scan，或至少返回多个 source pair / split，并继续按 signature 保留最低 reduced cost。单纯“每个 group-pair 只取一个 envelope argmin source pair”不能作为完整 exact join 替代。

继续修正 split/group-pair 口径。更准确地说，同一条完整 sequence 的不同 split 通常对应不同 crossing arc，因此大多落在不同 `(forward terminal job, forward ngMemorySet) × (backward terminal job, backward ngMemorySet)` group-pair 中，而不是同一个 group-pair 内部。标准 label-level join 会枚举这些不同 split；如果多个 split 恢复出同一个 `SequenceSignature`，`rememberGeneratedCandidate()` 会保留 reduced cost 最低的版本。join-envelope 第一版则对每个 group-pair 只返回 envelope argmin 对应的一条代表列，因此可能出现：某个 group-pair 中该 sequence 的最好 split 不是该 group-pair 的 envelope argmin，所以没有被返回；另一个 group-pair 返回了同一 sequence 的较差 split，于是该 sequence 以较高 inferred cost 入池。`301.8` vs `300.0` 的例子更符合这个解释。

在这个口径下，返回列后用 `TWETColumnEvaluator` 重刷真实 objective 是必要的局部修复：只要 envelope 路径已经恢复出某条 concrete sequence，真实 evaluator 会给出该 sequence 在原始目标下的最优成本，从而避免把某个较差 split 的 inferred cost 永久写入 Pool/RMP。这个修复可以消除“同一 sequence 被返回但成本不是它真实最低成本”的错误，例如把 `301.8` 修正为 `300.0`。但它仍不能让 envelope 路径自动返回所有被标准 join 可能发现的负列；若某个 group-pair 的 argmin 代表列是另一条 sequence，当前第一版仍不会返回该 group-pair 内其它负 elementary sequence。因此回刷能保证已返回列的成本正确，但不能把“每 group-pair 一个代表列”变成和标准 join 完全等价的加列器。

当前可接受的工程判断是：若只把 join-envelope 作为默认关闭的实验加速，并且开启时对返回列做真实成本回刷，它不会因为 stored cost 错误污染 RMP；但它仍可能减少每轮加列数量，导致 pricing 轮次增加，不能作为默认 exact join 替代。若以后要把它做成完整替代，需要在 envelope min 为负的 group-pair 内继续枚举多个 source pair，或在代表列不足/非基本/重复时回退到 label-level scan。

### 2026-07-09：join-envelope 返回列成本回刷补丁

按前面结论做了最小补丁：`joinForwardEnvelopeGroupWithBackward()` 恢复出的 concrete sequence 不再直接用 group-envelope 的 inferred reduced cost 反推列成本，而是在进入候选堆前调用 `TWETColumnEvaluator.evaluate(sequence)` 计算真实 objective，再按当前 pricing dual 重新计算 reduced cost。普通 label-level join 仍沿用原来的 inferred-cost 路径，避免扩大影响面。

这个补丁只解决“join-envelope 已经返回的 sequence 不能带着某个代表 split 的 inferred cost 污染 Pool/RMP”的问题。例如此前 `301.8` vs `300.0` 这类 stored cost 偏高会被回刷到真实成本。它不改变第一版 join-envelope 的结构限制：每个 group-pair 仍只返回一个代表 source pair，因此仍可能少返回标准 join 能找到的其它负列。换言之，当前开关仍应视为默认关闭的实验加速路径；若以后要做完整替代，仍需要在负 group 内返回多个 source pair 或回退 label-level scan。

### 2026-07-09：halfway join 去重思路与 half-domain 口径

阅读 `C:\Users\Changxin\Downloads\halfway_join_vrp_pricing_cn.pdf` 后，核心思路可以概括为：同一条完整 route 可能由多个 forward/backward split 生成，halfway 规则用一个沿路径单调的 critical resource 选择唯一 split，从而避免同一路径重复 join。工程口径上，它不是拿理论上所有 split 做比较，而是在最终实际保留的 label pool 上补 parent/child 或 prepend-child 映射，然后 join 时只和实际存在的相邻 split 比较；若 critical resource 单调且实际 split 不断裂，局部相邻比较就能选出实际 pool 中最接近 halfway 的 split。

这个思路在当前 TWET half-domain/PWLF pricing 中不能直接作为“跳过 join funcEval”的 exact 替代。原因是我们的 label 不是一个固定资源点，而是一段关于完成时间的分段线性函数；同一条 sequence 的不同 split 可能因为 half-domain、Tmid、compact window、dynamic hard window、single-point label 和 dominance 保留状态不同，在某个 split 下函数拼接为负，而相邻 split 虽然 label 存在，却可能在对应时间域为空、非负或只暴露另一个 timing。也就是说，`childF(j)` 和 `parent(B)` 存在并不等于右移 split 一定会生成同一条负列；若在 funcEval 之前只按 `crit(F)` 与 `crit(B)` 做 halfway 判定，可能把当前唯一能生成负列的 split 提前跳过。前面 join-envelope 中 `301.8` vs `300.0` 的问题本质上也是类似提醒：压缩 split 以后，需要非常小心“代表 split”和“真实 sequence 最优成本”之间的差异。

因此当前判断是：若要求完整 exact pricing 证书，不能简单用 halfway 规则替代“全部实际 label pair join”。安全的弱版本有三类。第一类是在候选列已经生成后按 `SequenceSignature` 去重，这已经由当前候选堆完成，但它不减少主要的函数拼接成本。第二类是在 join 前只跳过已经由其它 split 明确生成过同一 signature 的候选，这需要先恢复 sequence，收益取决于顺序，且只能避免后续重复，不能作为无负列证明。第三类是把 halfway 作为非证书轮次或启发式 join 的加速：找到足够负列后提前返回可以用，但最后证明无负列的 certificate 轮仍要完整扫描，或者必须证明被跳过 split 的相邻 canonical split 在当前 PWLF/time-window 口径下确实会被处理并给出不差的真实 reduced cost。

如果后续真要尝试 halfway 版本，较稳的实现路线不是直接全局替换 join，而是新增一个默认关闭的 experimental join filter：扩展完成后扫描 active forward/backward labels，按 parent 建 `childByNextJob`，按 backward parent 建 `prependByPrevJob`；在标准 join feasibility 和 cheap bound 之后、昂贵 PWLF funcEval 之前，尝试做 conservative halfway 判定。只有当相邻 split 已经存在、兼容、并且能证明它会在当前轮被处理时才跳过当前 split；否则必须保留当前 split。这样能保证不因理论 canonical split 缺失或半域截断而漏列，但第一版收益可能有限。若要更激进，只能放在非证书加列轮或作为启发式，不应影响 final no-negative certificate。

### 2026-07-09：full-domain 函数配合 half-domain arc join 的可行性

本次继续分析一个折中思路：保留当前 half-domain 的结构，即 forward/backward 仍按 Tmid 控制扩展方向和 crossing arc join，但正反向 label 的 PWLF 不再按 `[0,Tmid]` / `[Tmid,H]` 裁剪，而是使用 full-domain 的 job penalty，只把基础 hard window、node compact window、pricing-only arc 等真正属于当前 node 的限制写入函数。这样做的直接好处是，同一条 sequence 如果由不同 split 拼接出来，理论上都应回到同一个完整序列的最小成本，不再因为 Tmid 半域截断导致某个 split 的函数没有露出真实最优时间点。

这个方向和当前实现不是完全等价的简单开关。现在 `GCNGBBStyleBidirectionalNgDssr` 和 partial dominance 里都有 `baseForwardHalfPenaltyByJob = cropToInterval(..., 0, Tmid)`、`baseBackwardHalfPenaltyByJob = cropToInterval(..., Tmid, H)`，动态窗口也会在 `buildForwardHalfPenalty()` / `buildBackwardHalfPenalty()` 里先 `setDomain(hStart,hEnd,true)` 再裁到半域。若改成 full-domain 函数，应当只去掉 Tmid 裁剪，保留 effective hard window；同时仍保持 half-domain 的可扩展性判定和 crossing arc 枚举，否则会变成另一套 full-domain bidirectional pricing，状态和证明口径都变了。

如果只考虑 no-SRI、无 dual-window 污染、且函数只包含真实 hard/compact window，那么 full-domain 函数下同一 sequence 的多个 split 成本应当一致，至少比当前半域函数更接近 `TWETColumnEvaluator.evaluate(sequence)` 的固定序列语义。这会让“同一路径只做一次 join”或 group-envelope/halfway 去重更有理论基础。但仍有几个限制：第一，compact window 虽然用于当前 node 的安全剪枝，列进入 RMP/Pool 时 objective 仍最好按原始 evaluator 回刷，避免把受限窗口成本作为永久列成本；第二，SRI 尤其 limited-memory SRI 会引入 cut state 和 memory arc 口径，不宜作为第一版一起启用；第三，full-domain 函数会扩大 label 函数定义域，可能削弱 dominance、增加 segment 和扩展成本，未必一定更快。

因此建议的实验顺序是：先新增一个默认关闭的 no-SRI/ng-DSSR normal 诊断开关，保留 half-domain 扩展和 join 结构，只把 job penalty 函数改为 full-domain effective-window 版本；返回列仍做真实成本回刷或至少做 debug 对拍。验证指标不是先看整树总时间，而是看单轮 exact pricing 中同一 sequence 多 split 的 inferred cost 是否一致、join funcEval 是否能通过 signature/halfway 去重减少、DSSR 轮数是否上升，以及 active label/segment 数是否因 full-domain 函数变大而恶化。只有这组验证通过后，才考虑把“同一路径只 join 一次”的策略接上；SRI、partial dominance、dual-window 场景暂时不作为第一版目标。

### 2026-07-09：现有 full-domain pricing 代码口径复核

继续复核现有 `GCBBStyleBidirectionalFullDomain` 后，确认它不是当前 ng-DSSR 主线里的一个局部开关，而是 2026-05-28 为比较 half-domain 与 full-domain 函数定义域单独复制出来的 no-cut GCBB-style pricing engine。类注释里已经写明：它保留 GCBB-style final join、forward->sink 收尾和 top-K 候选流程，但 forward/backward 标签函数都直接定义在 `[0, pricingHorizon]`，主要用于诊断“半域裁剪”和“完整定义域标签”的差异，不作为默认正式入口。

具体实现上，它确实采用了前面讨论的核心思想：`ensureBaseHalfPenaltyCache()` 中 forward/backward 的基础 job penalty 都是 `cropToInterval(data.penaltyFunction[job], 0, pricingHorizon)`；`buildForwardHalfPenalty()` / `buildBackwardHalfPenalty()` 在 effective window 收紧时也只做 `setDomain(hStart,hEnd,true)` 后裁到 `[0, pricingHorizon]`，不再按 Tmid 裁成 `[0,Tmid]` 或 `[Tmid,H]`。同时，扩展和终端保存仍保留 Tmid 语义：label 是否继续入队、是否作为 terminal/single-point 保存，仍会看是否跨过 Tmid；final join 仍是 crossing arc join，而不是 node join。

因此它和本次想试的“full-domain 函数 + half-domain arc join”很接近，但不能直接拿来当当前主线 ng-DSSR 的实现。主要差别是：第一，它没有 DSSR 轮次、ngMemory 更新和 non-elementary 负列只更新 ng-set 的逻辑；第二，它是普通 GCBB/elementary pricing，对当前 normal ng-DSSR 的 repeatability filter、history warm-start、ng-set 动态初始化、join-envelope 实验路径都没有接入；第三，它虽然近期补过 pricing-only arc、completion-bound subtree bounds 等兼容性，但仍是历史实验分支，不应直接替代主线。若要试当前想法，最小风险做法不是启用 `mode=full`，而是在 `GCNGBBStyleBidirectionalNgDssr` 内新增一个默认关闭的“full-domain penalty”口径，仅复用这套 penalty 构造思想，保留 ng-DSSR 其它流程不变。
## 2026-07-11：旧 source-aware label 清理导致 W300 bound 分歧的最终定位

旧版 `IncrementalSourcedDominanceGraph` 曾尝试在某个本地 label 不再贡献 node 数值下包络时立即删除该 label。该版本在 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 上得到 `root bound=1726.256114`，而 Paper dominance 和当前保留旧 active-label 语义的安全版本均为 `1726.014329`。本轮没有继续用 ng-memory 兼容性反例解释差异，而是恢复旧实现并在同一最终 LP/dual 上逐列审计正确版本的完整 pool。

审计找到了两条具体负 reduced-cost 序列。`35 22 6 45 21 9 32 29 47 37 2 49 43 12 7 41` 的真实成本为 `785`，在错误版最终 dual 下 reduced cost 为 `-2.2145625`；`35 6 45 21 9 32 29 47 37 2 49 43 12 7 41` 的真实成本为 `484`，reduced cost 同样约为 `-2.2145625`。两条序列都没有被删出 Pool，也没有违反 compact window、普通 pricing-only arc 或时空 pricing-only arc：它们在错误版 Pool 中的 id 分别为 `6557/6560`，并且都属于 restricted RMP。真正的问题是成本版本错误：错误版分别保存为 `787.2145625` 和 `487.32184375`；正确版保存为 `785` 和 `484`，其中第二条列在正确版最终 LP 中取值约为 `0.1262475765`。

根因位于“半域 split 的 inferred cost + sequence 去重”的组合，而不是 dominance key 的集合包含关系。旧 source cleanup 改变了同一 sequence 首次由哪个 forward/backward split 生成；标准 label-pair join 在 no-SRI、no-dual-window、Paper backend 下默认用 inferred reduced cost 反推 objective，没有统一调用 evaluator。较差 split 因而先把同一 sequence 以偏高成本写入 Pool。后续更优 split 再产生该 sequence 时，`tryGenerateColumn()` 在 true-cost 检查之前命中 `activeColumnSignatures.contains(signature)` 并直接返回，导致 active 列永远不能用更低真实成本刷新。Paper backend 在错误版最终 LP 上即使使用全新 midpoint 状态也返回 0，原因同样不是找不到该 sequence，而是 active signature 先于成本改进被跳过。

因果复验只对上述两条已存在的 restricted 列调用真实成本改进，不新增或删除任何 sequence，也不改变窗口、禁弧和分支状态；随后重解同一个 RMP，objective 从 `1726.256114` 精确恢复为 `1726.0143289049`。因此旧 source cleanup 本身只是改变了列生成轨迹，真正把轨迹差异放大成错误 bound 的是 active sequence 成本不能改进。当前安全 dominance 版本已通过逐状态与 Paper dominance 对拍，并在 W300 上复现相同 bound；但这只能证明 dominance 状态更新保持旧语义，不能单独兜住上述列成本问题。后续若继续完善主线，应保证 active signature 遇到更优候选时仍可做最终候选级真实成本复核并刷新 Pool，或让所有可能受半域 split 影响的最终候选统一恢复真实 sequence cost；不能仅依赖某次安全版本恰好先生成了最优成本 split。

这里还需要区分同一轮和跨轮次。单次 exact pricing 内，`generatedCandidateBySignature` 会比较同一 sequence 的多个 split 并保留 inferred reduced cost 更好的候选，因此问题不是同一候选堆把最佳 split 淘汰。问题发生在该 sequence 已经由上一轮加入 restricted RMP 后：下一轮 `activeColumnSignatures` 在 evaluator 和候选堆之前直接跳过整个 signature。半域 label 的 frontier 被 Tmid 截断，join 时又用边界常数扩展到另一半定义域；固定 sequence 的具体 label-pair inferred cost 因而会随 crossing split、Tmid 和当轮保留下来的 label 变化，不保证等于该 sequence 的全域最小成本。第一轮若只出现较差代表 split，就会先保存偏高成本；后续轮次即使出现更好 split，也没有机会进入同 signature 比较。此前“不会丢”的分析只覆盖了同一轮候选去重，并错误假设标准 label-pair join 的 inferred cost 对 split 不变，遗漏了上述跨 pricing 轮次的 active-signature 截断。

该风险并非 source-aware 新图独有。旧 Paper dominance 同样可以拒绝或删除某个具体 label；其安全性证明保证的是存在另一个状态在 reduced-cost 函数和可扩展集合上支配它，因此不会丢失全局最优 pricing 值，但支配 label 的父链可以对应另一条 prefix/sequence。它不保证某条固定 sequence 的所有 split 都保留，也不保证保留下来的 split inferred cost 等于该 sequence 的全域最小 objective。因此 dominance 正确性与列成本正确性必须分开：只要最终返回任意一个 split 后统一用 evaluator 计算固定 sequence 的真实最小成本，旧图和新图都不需要保留该 sequence 的最佳 split；反之，若继续把 split inferred cost 直接写入 sequence 列，旧 Paper 图也存在相同的路径依赖风险，只是本次 W300 的旧轨迹恰好先得到正确成本版本。

按该结论，主线已把 ng-DSSR 的真实成本恢复固定到最终候选出口。labeling、dominance、DSSR 更新、inferred top-K 排序均不变；`generatedCandidateBySignature` 排序完成后，只对最终准备返回 Master 的候选调用 `TWETColumnEvaluator`，再用当前完整 `PricingDualSnapshot` 重算 reduced cost，真实非负候选不再加入 Master。这样 SRI cut dual、分支 dual 等也包含在最终过滤中。任意保留下来的 split 只负责恢复 sequence，第一次进入 Pool 时已经是该 sequence 的全域最小成本，后续 active-signature 跳过不再依赖最佳 split 是否保留。`50-3 setupR50 + W300` root-only 复验得到 `bound=1726.014329`、Pool 7854、exact 10 次；最终 Pool dump 的 7854 条列全部满足 `storedCost=evalCost`，包括所有正值列。该轮 solve 为 `233.259s`、ng-DSSR exact 为 `169.210s`；第一轮 1864 个最终候选的 finalize 仅约 `14.3ms`，说明 evaluator 回刷没有进入千万级 join 热循环，主要耗时仍为 label 扩展和 join。另用 `wet040_001_2m` 做 `ngPartial + active SRI` root smoke，180 秒内完成一个节点，`valid=true`，确认完整 dual 过滤路径可运行。

### 2026-07-11：增量 dominance 与最终成本回刷复核

本次再次逐段核对 `IncrementalSourcedDominanceGraph`。当前生产实现没有采用此前出错的 same-key source cleanup：同 reachable key 的旧 label 不会仅因离开当前下包络就被删除，只有严格 predecessor 包络覆盖定义域并逐点支配时才删除。`h` 和 `g` 都只做单调下降的 min-merge，稀疏 delta 只携带本次真实下降区间。菱形汇合处每个 node 一轮只入队一次仍然成立：某个时间点若能降低后继的全部直接 predecessor 下包络之最小值，它必须低于每个直接 predecessor 在该点的值，因此任一到达路径携带的 delta 都包含该真正下降区间。节点删除时，其本地 label 已全部被 predecessor 包络覆盖，重连前后数值包络不需要回升。

一致性测试新增正反向的交叉分段菱形传播、节点删除后同 key 重建，并把随机覆盖从 24,000 次扩大到 96,000 次。每次插入都检查新图数值包络与全部历史 label 的 brute-force 下包络；没有发现误拒绝、误保留或漏传播。扩大样本后发现旧 `PaperDominanceGraph` 有 1 次 point query 漏剪，以及 5 次重复状态观察中旧图保留了已被严格超集 reachable-set 且完整函数支配的 label；新图对应值与 brute-force 一致。这说明此前“所有 active 状态逐项完全等同旧图”的表述过强，更准确的口径是：same-key active-label 语义保持不变，predecessor dominance 的数学条件保持不变，但新图的增量包络传播会修复旧图少量滞后导致的保守少剪。

最终列出口也重新核对。evaluator 使用原始 `data.penaltyFunction` 和 `data.CmaxH`，不读取 dual/compact window；固定 sequence 经逐任务 shift/add、setup cost 和 prefix minimization 后得到全域最小真实成本。最终只对候选堆中准备返回 Master 的列回刷，不进入 label 扩展或千万级 join 内层；随后用一次捕获的完整 true dual snapshot 重算 reduced cost，`LP.computeReducedCost()` 同时覆盖 job visit count、machine dual、arc/branch dual 和 active SRI cut dual。W300 已有 dump 再次逐行读取，Pool 与 restricted 各 7854 条列的 `storedCost-evalCost` 和 `storedRC-evalRC` 均为 0。第一轮 1864 条候选 finalize 为 `14.286ms`，相对于该轮 `30.894s` exact pricing 可忽略。当前未发现需要修改生产代码的正确性或热点问题；微型 4000-label benchmark 受 JIT/GC 影响波动较大，端到端效率仍以此前 W300 同 bound 对照为准：总时间 `211.187s -> 180.760s`，exact `171.098s -> 138.478s`。
