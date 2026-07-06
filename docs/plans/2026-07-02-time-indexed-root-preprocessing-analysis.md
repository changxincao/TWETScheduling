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