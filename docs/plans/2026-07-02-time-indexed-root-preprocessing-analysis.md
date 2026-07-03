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
