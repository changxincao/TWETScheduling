# time-indexed 与 ng-DSSR 对照实验方案

## 1. 当前判断

2026-06-30 结合近期 40、50 规模的对照实验，当前可以形成一个比较清楚的初步判断：time-indexed graph pricing 和 ng-DSSR 并不是谁绝对更强，而是适用区间不同。

当整体时间尺度较小、时间离散层数不大时，time-indexed pricing 的优势很明显。它把 pricing 变成 time-expanded DAG 上的最短路问题，单轮定价非常快。虽然它生成的是 pseudo-schedule 列，列可能不是 elementary，主问题列数更多，LP bound 也可能更弱，但在当前 40-2、40-4、50-2 这类时间尺度较小的算例上，定价速度优势足以抵消列数和 master 压力，整体仍然可以很快收敛。

当同等任务规模下把时间尺度显著放大，尤其是非均匀放大 processing、due 和 setup 后，time-indexed 的缺点会快速暴露。它的状态空间直接受 horizon 和离散时间层数支配，时间层数变大后，图扫描、候选列、RMP 和 strong branching 的成本都会上升。相反，ng-DSSR 使用连续时间分段函数，不直接按每个整数时间点展开状态，因此对时间尺度放大更稳定。此前 `wet040_001_2m_timeJitterX10` 的结果已经体现这一点：time-indexed no-cut strong 在旧逻辑下虽快速闭合但存在误剪问题，修复后很难在同样时间内闭合；ng-DSSR 则能在该实例上找到更优且可行的 `104721` 解，并且总时间明显低于旧 time-indexed 对照。

此外，time-indexed 方法天然更依赖整数时间。若原始数据存在小数 processing、setup 或 due-window 端点，就必须 scale 到整数图，或者做保守离散化。前者会直接放大 horizon，后者只能作为松弛 bound，不能安全反写硬时间窗。这一点是 time-indexed 路线在通用 TWET/外包问题上的结构性限制。ng-DSSR 的连续时间函数表示在这方面更自然。

因此当前初步结论为：时间尺度小、整数 horizon 可控时，time-indexed graph pricing 可以作为强 baseline，甚至可能优于 ng-DSSR；时间尺度大、需要 scale、或小数时间较多时，ng-DSSR 更稳健，time-indexed 容易爆图。

## 2. 与论文算法的关系

`On the exact solution of a large class of parallel machine scheduling problems` 的 time-indexed 思路仍然值得作为对照算法实现和比较。当前项目中已经实现了其 no-cut time-indexed pricing 的核心思想，并进一步补充了 reduced-cost arc fixing、rank-1 cut pricing、graph-native column handling 等实验分支。近期结果说明，在某些时间尺度较小的算例上，该路线确实可能非常快。

但这不意味着论文方法在当前问题上必然全面优于 ng-DSSR。论文实验的算例结构、时间尺度、cut 处理、branching 细节、是否存在外包、是否存在小数时间，以及 setup cost 口径都和当前 TWET 外包扩展问题不同。尤其是当前数据中 setup time 主要影响时间递推，不一定直接进入 objective；如果 setup cost 较弱，pseudo-schedule 松弛列对 bound 的破坏也可能不明显，从而让 time-indexed 方法显得更有利。

因此论文方法在本文中的定位更适合写成一个对照算法：我们复现并适配其 time-indexed pricing 思路，用它和连续时间函数占优的 ng-DSSR 做公平比较。比较重点不是证明某一方永远更好，而是说明两类 pricing 在不同时间尺度、数据结构和外包设置下的适用范围。

## 3. 建议实验设计

1. 不带外包的基础对照

先在纯内部机器调度版本上比较 time-indexed 和 ng-DSSR。该组实验用于隔离 pricing 技术本身，不让外包变量、外包列、tariff 或 outsourcing branch 干扰结论。

建议至少包含三类算例：第一类是原始时间尺度的 40、50、60 规模算例；第二类是整体均匀放大的时间算例，用于观察 time-indexed 对 horizon 增大的敏感性；第三类是非均匀扰动放大的算例，用于模拟更复杂的时间结构。评价指标包括 root time、total time、root gap、节点数、列池规模、exact pricing 时间、master LP 时间、是否出现 time-indexed strong branching 误剪风险，以及最终目标是否和 ng-DSSR 交叉一致。

该组实验的预期结论是：小 horizon 下 time-indexed 可能更快；horizon 放大后 time-indexed 退化更明显；ng-DSSR 在时间尺度变化下更稳定。

2. rank-1 cut / SRI 对照

当前 no-cut 结论不能直接外推到带 cut 情况。time-indexed 的 rank-1 cut 版本理论上可以增强 root bound，但也会引入 cut state、双向 labeling、memory arc 和更多状态管理。ng-DSSR 的 SRI/partial dominance 版本此前测试过，效果并不总是好，主要问题是 label 状态爆炸。

因此该组实验应单独做，不和 no-cut 结果混在一起。建议先只做少量代表性算例，比较 no-cut、time-indexed rank-1 cut、ng-DSSR + SRI / partial dominance 三者在 root bound、root time 和总时间上的变化。当前还没有足够证据说明带 SRI 后哪条路线更优，所以文档中只能写“待测试”。

3. 外包扩展实验

本文的主要贡献方向仍然是带外包的 TWET 调度问题。因此在基础 pricing 对照之后，应切回外包模型，比较显式外包变量、外包列化、不同 tariff 函数段数，以及外包 branch 的影响。

这里可以沿用当前两个主线：一是原有显式外包变量模型，二是外包列模型。已有观察表明，外包列不一定显著增强 bound，甚至可能因为分支和列管理增加节点数；但当 tariff 函数多段、折扣更明显时，column 形式的 root bound 可能更强。后续需要系统比较 root bound 是否增强、节点数为何变化、外包分支是否频繁、以及每个 node 是否主要在找可行解还是提升下界。

4. 灵敏度分析

灵敏度分析建议围绕三个维度做。第一是时间尺度，包括原始、均匀放大、非均匀扰动放大，以及小数时间 scale。第二是 setup 结构，包括 setup/p 比例、是否加入 setup cost、是否保持三角不等式、以及是否模拟 cluster/random/RC 类结构。第三是外包结构，包括外包成本水平、tariff 段数、折扣强度、可外包任务比例和机器数量。

这部分的目的不是跑尽所有组合，而是找出 time-indexed 与 ng-DSSR 各自明显占优或明显退化的区域。最终论文里可以把结论写成：time-indexed 是小整数 horizon 下很强的离散图定价基线；ng-DSSR 是更适合连续时间、大 horizon、小数时间和复杂外包扩展的函数占优定价路线。

## 4. 当前方案是否合理

这个实验路线是合理的。它没有把当前 time-indexed 的快速结果直接当成论文算法全面优越的证据，也没有因为 pseudo-schedule 松弛就完全否定 time-indexed。更合适的写法是承认 time-indexed 在小 horizon 下很强，并把它作为一个可复现的对照算法；同时强调当 horizon 变大或需要 scale 时，它的结构性成本会变高，而 ng-DSSR 的连续时间表示更有优势。

需要注意三点风险。第一，开启 strong branching 的 time-indexed 结果必须使用修复后的逻辑重新验证，旧的 strong 结果已经证明可能误剪。第二，带 SRI/rank-1 cut 的比较还没有完成，不能用 no-cut 结果代替。第三，外包列模型是否比显式外包变量更强，目前还依赖 tariff 形状和分支策略，需要单独实验，不应提前写死结论。

当前可以作为论文实验结构的初稿：先做纯内部机器调度的 pricing 对照，再做外包模型对照，最后做时间尺度、setup 和外包成本的灵敏度分析。这样能把“我们实现并比较了论文 time-indexed 思路”和“我们提出/采用的连续时间 ng-DSSR 在更一般场景下更稳健”两个叙事连接起来。

## 5. 用户原始判断记录

2026-06-30 讨论中形成的原始判断如下，保留原话，便于后续写论文或复核实验设计时对照：

> 那这个还是快，那现在感觉上结论可能比较清晰了：
> 1、当时间整体比较小的时候，time-indexed的还是有优势的，但是当同等规模放大时间的时候，time_indexed变得显著变差，而ng-DSSR的则相对稳定。此外，time-indexed的难以处理小数的问题，必须scale，而一旦scale就会变得规模爆炸。
>
> 2、因此其实一个初步的结论就是当整体时间较小的时候可以使用time-indexed的去做，虽然列不是emelemenary的，但是由于计算很快，虽然列多，bound弱但还是可以很快的收敛。但是当时间变大的时候就不可以了。
> （带有SRI的还没测试，目前只测试了不带有SRI的，待测试）
>
> 因此，当前这个文章的话，感觉主要还是说我们做一个带外包的问题。但初步的实验可以说，我们实现了他们的算法。。。然后可以公平比较，即某些情况他们好，某些情况我们的好，然后这个实验是纯做不外包的。然后在做外包的相关的分析。最后做一个灵敏度的分析，感觉就差不多了。

## 6. 40-2 非均匀 10 倍时间扰动的 5 组补充对比

2026-06-30 对 `wet040_001_2m_timeJitterX10` 做了一轮更明确的 5 组对比。为了避免“每次 pricing 内部临时求 time-indexed 图”带来的额外干扰，本轮 ng-DSSR 三组都显式设置 `timeIndexedCompletionBoundInRoundArcFixing=false` 和 `timeIndexedCompletionBoundCutLoopArcFixing=false`。也就是说，time-indexed helper 只作为 node 收敛后的 post-node 加强使用，用于后续子节点的 scalar/window/arc-fixing 信息；不是每一轮 pricing 都临时重建 time-indexed 图。5 组实验均统一使用 `cplexThreads=1`，时间限制为 900s，因此绝对时间和历史 `cplexThreads=0` 的 run 不能直接逐秒对齐，但同一轮内的相对比较是可用的。

本轮结果如下。

1. ng-DSSR，完全关闭 time-indexed helper：`FINISHED`，目标和下界均为 `104113`，总时间 `510.258s`，root `84.324s`，处理 `18` 个节点，pricing `1038` 轮，加列 `125295`，peak pool `123244`。

2. ng-DSSR，只开 time-indexed scalar/arc-fixing，关闭 window tightening：`FINISHED`，目标和下界均为 `104113`，总时间 `528.547s`，root `84.308s`，处理 `18` 个节点，pricing `987` 轮，加列 `118614`，peak pool `116534`。这一组列数少了一些，但总时间没有改善，说明只靠 scalar/arc-fixing 的收益不稳定，甚至可能被构造 time-indexed helper 的额外成本抵消。

3. ng-DSSR，同时打开 time-indexed scalar/arc-fixing 和 window tightening：`FINISHED`，目标和下界均为 `104113`，总时间 `394.459s`，root `83.384s`，处理 `16` 个节点，pricing `869` 轮，加列 `67471`，peak pool `66016`。这是本轮最好结果。关键差异是 window tightening 明显压缩了后续节点的有效时间域，使 exact ng-DSSR pricing 时间从 off 组的 `138.805s` 降到 `81.108s`，加列数量也几乎减半。

4. time-indexed graph pricing，不加 SRI/rank-1 cut，关闭 strong branching：`TIME_LIMIT`，900s 内未收敛，root bound `102869.043478`，最终 incumbent `104836`，最终 lower bound `103195.700000`，gap `1.5646%`，处理 `15` 个节点，pricing `908` 轮，加列 `78037`，peak pool `77963`。这说明在非均匀 10 倍时间扰动后，纯 time-indexed no-SRI 路线已经明显吃力。

5. time-indexed graph rank-1/SRI pricing，关闭 strong branching：`TIME_LIMIT`，900s 内仍停留在 root，cut rounds `2`，加入 `79` 条 cut，pricing `373` 轮，加列 `60825`，peak pool `60794`，但 root 未闭合，日志中最终是 time limit。该组每轮 rank-1 cut 双向 pricing 的平均时间明显高于 no-SRI time-indexed，说明在该放大算例上 SRI/rank-1 cut 并没有改善整体收敛，反而把 root pricing 压力放大了。

因此，本轮对用户前面判断做了一个补充验证：在时间尺度被非均匀放大后，time-indexed 的优势快速下降；ng-DSSR 如果结合 post-node 的 time-indexed window tightening，反而能显著减少列数和 exact pricing 时间。更细的结论是，time-indexed helper 里真正有价值的是可继承的 job time-window tightening；单独的 scalar/arc-fixing 不足以稳定带来收益。带 SRI/rank-1 cut 的 time-indexed 版本在这个放大算例上没有看到优势，至少当前实现和配置下不适合作为大 horizon 场景的主线。

## 7. SRI 与时间尺度的初步判断补充

2026-06-30 进一步补充 SRI/rank-1 cut 的适用性判断。当前观察更倾向于：SRI 也和 time-indexed 图方法一样，可能更适合整体时间尺度较小、离散图状态规模可控的场景。原文实验中 rank-1 cut 在更大规模下相对 no-SRI 的改善更明显，这个现象可能确实存在，因为规模变大后 set-partitioning / pseudo-schedule 松弛带来的 bound 弱化会更突出，cut 更容易提升 root bound。

但这并不意味着在当前 TWET 放大时间尺度算例上也一定有效。本项目的 40-2 非均匀 10 倍时间扰动实验显示，time-indexed rank1/SRI 版本在 900s 内仍停留在 root，加入 79 条 cut 后仍未闭合；相比之下，ng-DSSR 结合 post-node time-indexed window tightening 可以在 394.459s 收敛。这说明 SRI 的收益很可能受到时间尺度和 pricing 状态空间的强烈影响：在小 horizon 下，cut 的 root-bound 收益可能大于额外状态成本；但当 horizon 放大后，带 cut 的 time-indexed 双向 pricing 本身会变重，收益可能被状态膨胀抵消。后续写实验结论时，应把“SRI 在原文较大规模下有用”与“在大时间尺度下相对 ng-DSSR 未必有用”分开表述，不能直接外推。

2026-06-30 补充查看 `wet040_001_2m_timeJitterX10` 的 ng-DSSR + time-indexed window tightening 日志。该算例原始 pricing horizon 约 `19248.9`；root 不继承 compact window。从 node 2 开始，40 个 job 全部有继承窗口，平均每 job 窗口长度约 `3582`，相当于只剩原 horizon 的 `18.6%`，缩减约 `81.4%`。更深节点中平均窗口长度继续下降，典型值为 `2122.55`、`2011.475`、`1893.075`、`1818.775`，最小记录约 `1806.3`，相当于只剩 `9.4%` 左右，缩减约 `90.6%`。这解释了为什么只开 scalar/arc-fixing 收益不明显，而打开 window tightening 后列数和 exact pricing 时间明显下降。

2026-06-30 补充区分“每轮 pricing 内部临时 time-indexed helper”和“node 闭合后继承 compact window”的作用。此前 `tmp-wet040-m2-setup-tihelper-on-20260630-rerun` 确实打开了 `timeIndexedCompletionBoundInRoundArcFixing=true`。日志中 263 次 ng-DSSR exact pricing 里有 167 次出现非零 time-indexed scalar/window 计算，累计 `buildMs=2922.561ms`，记录到 `improved=107458`、`extraPruned=31701`。这说明每轮 pricing 内部 helper 并非完全无效，在原始 40-2 上有一定额外剪枝，而且构图耗时不算大。

但这组实验同时打开了 post-node scalar/window/arc-fixing，因此不能把总时间 `148.524s` 的改善单独归因给 in-round helper。后续在 `wet040_001_2m_timeJitterX10` 的 5 组对比中显式关闭 `timeIndexedCompletionBoundInRoundArcFixing` 和 `timeIndexedCompletionBoundCutLoopArcFixing`，只保留 post-node helper，仍然看到 window tightening 组显著优于 helper off 组。这说明当前更可靠的判断是：可继承 compact window 是主要收益来源；每轮 pricing 内部临时 helper 有剪枝能力，但收益/成本需要单独 A/B，不宜默认当作核心加速手段。

2026-06-30 纠正：上一段关于每轮 pricing 内部临时 time-indexed helper 的证据只来自原始 `wet040_001_2m`，不是放大后的 `wet040_001_2m_timeJitterX10`。放大图现有 ng-DSSR 对比，包括 `tmp-ngdssr-40-2-timeJitterX10-tihelper-postnode-strong-20260630` 和 5 组 `tmp-compare-40x10-*`，均显式关闭 `timeIndexedCompletionBoundInRoundArcFixing` 与 `timeIndexedCompletionBoundCutLoopArcFixing`。因此当前只能确认 post-node compact window tightening 在放大图上有效，不能判断 in-round helper 在放大图上是否有用。若后续要测，应以 `tmp-compare-40x10-ng-ti-windowon-20260630` 为母配置，只把 `timeIndexedCompletionBoundInRoundArcFixing=true`，其余配置不变，单独做 A/B。

2026-06-30 追加 A/B：在放大图当前最好配置 `tmp-compare-40x10-ng-ti-windowon-20260630` 上，只打开 `timeIndexedCompletionBoundInRoundArcFixing=true`，其余保持不变，得到 `tmp-compare-40x10-ng-ti-windowon-inround-20260630`。结果 `FINISHED,obj=bound=104113,solve=377.506s,root=82.741s,nodes=18,pool=69901,exact=105.048s/143,heuristic=76.305s/312,masterLP=69.012s,valid=true`。对照未开 in-round 的 `394.459s,root=83.384s,nodes=16,pool=66016,exact=81.108s/121,heuristic=73.724s/287,masterLP=90.055s`，总时间约快 `16.95s`，但 exact pricing 明显变慢。日志统计 `timeIndexedScalar` 在 150 条 pricing 记录中 81 条非零，累计 `buildMs=20323.078ms`、`improved=39069`、`extraPruned=8691`、`unavailable=14`。因此放大图上 in-round helper 确实会剪枝，但它本身增加 exact 时间；本次总时间略优主要来自搜索树/主问题时间变化，不宜据此认为它是稳定主收益。当前更稳的默认策略仍是 post-node compact window tightening。

2026-06-30 复核 time-indexed 候选列和硬时间窗语义。当前 time-indexed graph pricing 在单次 pricing 内使用 `SequenceSignature -> Candidate` 保存候选，同一个任务序列如果由不同结束时间或不同状态恢复出来，只保留 reduced cost 更低的那个；候选堆只保存全局 top-K 负 reduced cost 序列，K 来自 `timeIndexedGraphMaxExactPricingColumns`，若未设置则使用 `maxExactPricingColumns`，并不是把所有负 end state 都返回。当前启发式 pricing 会把基础 hard window、可选 root dual profitable window、以及 node 继承的 time-indexed compact window 合并成局部 `HeuristicWindowContext`，用它缩短 horizon 并裁剪惩罚函数。但 PC/Pool 入口不会自动用原始 evaluator 重刷真实列成本，只会按签名保留更低的传入 cost；因此只要启发式使用了 dual window 或 compact window，安全口径上就应在入池前按原始目标重刷列成本，窗口成本只能作为搜索和候选筛选口径。对 ng-DSSR 主线，compact window 已经进入 `effectiveJobHStart/End`，随后压缩 `pricingHorizon`，并通过 `setDomain + cropToInterval` 裁剪 forward/backward/completion-bound 的分段函数；函数定义域最大不会超过当前 `pricingHorizon`，job 自身窗口之外按 BigM 处理。放大图 root 的 dual profitable window 日志显示，原始 horizon 约 `19248.9` 时，root pricing 的 `pricingHorizon` 平均缩到约 `0.7082`，即右端约缩短 `29.2%`；这只是 dual window 的全局 horizon 缩减，不是每个 job 的平均窗口长度。post-node compact window 的作用更强，典型平均 job 窗口长度从约 `3582` 继续降到约 `1806`，相对原 horizon 只剩约 `18.6%` 到 `9.4%`。

2026-06-30 复核启发式窗口成本重刷。`HeuristicPricingEngine.tryAddNegative()` 已经先用当前窗口口径判断候选是否值得考虑，再调用 `trueSequenceCost(sequence)` 回到原始 TWET 目标函数口径，随后用 true reduced cost 过滤并把 true cost 写入返回列。因此启发式并不是把 dual/compact window 下的局部成本直接写入 Pool。为便于后续确认窗口是否改变候选成本，本次新增 `heuristicCostAudit` 日志，只复用已经计算出的 trueCost，不增加 evaluator 次数。短诊断显示：打开 root heuristic dual-window 时，root 有 260 个候选被检查，其中 70 个窗口成本与真实成本不同，最大差值约 `295.2`，但没有候选因重刷后 reduced cost 变非负而被过滤；关闭 heuristic dual-window、只看 compact window 的子节点短诊断中，node 1/2 的 14524 个候选均为 `changed=0`，说明当前 40-2 口径下 compact window 没有造成启发式列成本变化。当前仍保留 true cost 重刷，因为这是 dual window 下必须的安全口径。

2026-06-30 复核 compact window 的 hull 与真实可达点比例。根据 `wet040_001_2m_timeJitterX10` 的 time-indexed window tightening 日志，未开 in-round 的 post-node helper 中 `avgOrigPts/hullPts/reachablePts` 共 8 条有效记录，平均 `reachable/hull=0.8868`，最小约 `0.8151`，最大约 `0.9745`；开 in-round 的对照中共 9 条有效记录，平均 `reachable/hull=0.8421`，最小约 `0.6976`，最大约 `0.9745`。因此 1800 左右的 hull 窗口不是完全由大量空洞拼出来的，大多数时间点仍然可达，但典型有约 10%-16% 的空洞，较深节点个别记录空洞可到约 30%。当前主线继承的是 hull 窗口而不是精确 BitSet，所以会保守保留这些空洞；如果后续继续优化，可以考虑 per-job 可达时间 BitSet 或 per-arc interval list，但会增加 node 状态复制成本。

2026-07-01 继续复核启发式 pricing 只使用 compact window、不开 heuristic dual window 时是否需要重刷真实列成本。代码层面确认 `HeuristicPricingEngine.trueSequenceCost()` 使用 `unrestrictedWindowContext()`，即原始 `data.penaltyFunction` 和 `data.CmaxH`；`TWETColumnEvaluator` 也通过原始 `Data` 构造 scratch `Solution` 计算列成本，不会使用 compact/dual 裁剪后的惩罚函数。实验层面新增 `tmp-compact-only-audit-40-20260701`，配置关闭 `enableHeuristicDualProfitableWindow`、打开 time-indexed compact window 继承，处理到 4 个节点。结果显示 108 条启发式 pricing 记录、16433 个被检查候选中，63 个候选的窗口成本与 true cost 不一致，全部发生在子节点：node 2 为 3/1175，node 3 为 60/2190，最大差值 120；但 `filteredByTrueRc=0`，即没有候选因为 true reduced cost 重刷后变成非负而被过滤。此前 `tmp-heuristic-compact-cost-audit-20260630` 的 40-2 短样本为 14524/14524 全部不变；50-2 root 短样本 `tmp-compact-only-audit-50-20260701` 在 300 秒内只停留于 root heuristic 阶段，16582 个候选也全部不变，但该样本尚未进入继承 compact window 的子节点。

由此当前判断为：compact window 的确比较“干净”，绝大多数启发式候选的窗口成本和真实成本一致，且已有样本中没有发现 true-cost 重刷会改变是否入池的结论；但它不是严格等价于原始成本，子节点上已经观察到少量候选成本变化。因此暂时不建议为了省这点成本而跳过 true-cost recheck。更稳妥的实现仍是“compact window 用于缩小启发式搜索空间，最终返回列前按原始目标重算成本”。如果后续要优化，可单独做一个 compact-only fast path，但需要先证明 compact window 是当前子树永久有效窗口，并且 Pool 中同一 sequence 的全局成本口径不会被其它节点复用语义破坏。

### 2026-07-01 compact window 启发式列成本口径调整

当前按实验口径暂时关闭启发式 pricing 在 compact window 下的 true-cost recheck，但保留 dual profitable window 下的重刷逻辑。理由是 compact window 来自当前子树可继承的硬时间窗加强，如果该窗口确实有效，则最优列不会依赖窗口外的完成时间；因此启发式在窗口内得到的较高成本可以作为当前子树口径的列成本使用，可能还会略微抬高 LP bound。这个判断不是全局序列最小成本等价证明，而是基于“compact window 已排除窗口外最优时间”的实验假设。

边界需要记录清楚：dual profitable window 是当前 dual 下的临时窗口，不能继承到全局列成本，因此仍必须回到原始 TWET 目标口径重刷；compact window 口径下跳过重刷后，同一个 sequence 在不同 node/window 下可能存在不同成本口径，后续如果出现列池复用导致异常，需要优先回到这里排查。实现上没有删除 true-cost recheck，只是在 `HeuristicWindowContext.requiresTrueCostRecheck()` 为 false 时提前返回，并在日志中记录 `skippedTrueRecheck`。

验证：`tmp-compact-no-recheck-40-20260701` 短测 120 秒，`wet040_001_2m`、ng-DSSR nearestK8/top10、关闭 heuristic dual window、打开 time-indexed compact window。日志显示启发式 pricing 行为变为 `heuristicCostAudit checked=0, skippedTrueRecheck=...`，说明 compact-only 候选不再重刷；dual-window 分支代码仍保留重刷路径。本次短测只验证行为和编译，不作为速度结论。
## 8. 小 horizon 与大 horizon 下的当前最好判断，以及 root time-indexed + 子节点 ng-DSSR 的设想

当前更合适的表述是：ng-DSSR 的目标不是在所有算例上都压过 time-indexed，而是在 time-indexed 最擅长的小整数 horizon 场景下，尽量做到同一数量级、差距不要失控；当时间尺度变大、存在小数 scale 风险、或者 time-indexed 图状态明显膨胀时，ng-DSSR 应该能够反过来占优。换句话说，最好的结果不是“ng-DSSR 永远更快”，而是“time-indexed 小时间场景下有优势但 ng-DSSR 不差太多；大 horizon 或更一般场景下 ng-DSSR 更稳，甚至更快”。这个判断和当前 40-2 非均匀 10 倍时间扰动实验是一致的：time-indexed 在小 horizon 下很快，但放大后明显吃力；ng-DSSR 结合 post-node compact window tightening 后可以收敛，并且列数、exact pricing 时间都明显下降。

由此产生一个可能的混合策略：root node 先用 time-indexed pricing 收敛，利用它在小 horizon 或根节点无分支时的快速最短路能力，尽快得到 root LP bound、负列和 time-indexed arc fixing/window tightening 信息；之后在子节点切换到 ng-DSSR，利用 root 产生的 compact window、pricing-only fixed arcs 和后续分支信息缩小连续时间函数定价空间。这个方向理论上是可行的，因为 root 收敛后得到的 time-indexed reduced-cost arc fixing 和 compact window 本来就是给子树继承使用的；子节点换成 ng-DSSR 只是换 pricing oracle，不改变 master 的列语义和分支语义。尤其是当 time-indexed root 容易闭合，而 ng-DSSR root 需要很多 exact pricing 轮次时，这个混合策略可能减少 root 长尾。

但这个方案不能直接当作已经安全的主线，需要注意几个边界。第一，root time-indexed 生成的是 pseudo-schedule / relaxed 图列，和 ng-DSSR 的 elementary/ng 列族不同；如果 master 采用 >= 覆盖，这些列作为 LP 下界列是允许的，但后续整数上界和列池解释要继续区分。第二，root 的 time-indexed compact window 必须来自可以继承的 fixing/window tightening，不能把 root dual profitable window 这类临时 dual 口径继承下去。第三，如果 root 使用 rank-1/SRI cut，则 time-indexed fixing 是否带 SRI 状态、以及 ng-DSSR 后续是否使用同一套 cut dual 口径，需要单独检查；当前 no-cut 的混合策略最容易先做。第四，root 切换 pricing oracle 后，列池会混有 time-indexed relaxed 列和 ng-DSSR 列，需要确保 duplicate sequence 成本、active column 改进和 RMP 成本口径一致，否则会出现“root 快但后续列解释不一致”的风险。

当前建议是把这个作为后续实验分支，而不是马上替代主线。实验上可以先做 no-cut、无外包或显式外包变量口径：同一算例比较三组，分别是纯 time-indexed、纯 ng-DSSR、root time-indexed 收敛后子节点 ng-DSSR。重点记录 root time、root pool、root bound、继承 compact window 的平均长度、子节点 exact pricing 次数、总列池规模、总时间和最终目标是否一致。如果 root time-indexed 的确能显著缩短 root，且子节点 ng-DSSR 能避免 time-indexed 在大 horizon 下继续膨胀，那么这会是一个很有价值的 hybrid pricing 策略。

### 2026-07-01 40-2 原始算例加入 setup cost 的 time-indexed 对照

本次重新检查发现，之前直接设置 `twet.data.setupCostFromTimeCoefficient=1.0` 的 Tanaka 入口实验不能作为 setup cost 影响判断。原因是 `TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine()` 会在 `Data` 构造后重新覆盖 `p/d/s`，并清空 `setupCost`，所以 `Data` 构造器里的默认 setup-cost 逻辑没有真正作用到最终 Tanaka 数据。已在 Tanaka loader 覆写完 setup time 后按最终 `s[i][j]` 重建 `setupCost[i][j]=coefficient*s[i][j]`。因此旧的 `tmp-timegraph-40-2-setupcost0-current-20260701` 和 `tmp-timegraph-40-2-setupcost1-current-20260701` 只说明运行噪声和 LP 路径差异，不能说明 setup cost 的真实影响。

修复后按当前较稳的 time-indexed no-cut 口径重新跑 `data/40-2/wet040_001_2m.dat`。配置为 `timeIndexedGraphPricing=true`、关闭 rank-1/SRI、关闭旧 HeuristicPricing、关闭 strong branching、打开 dual-bound pruning 和 post-node time-indexed helper，`cplexThreads=1`，时间限制 900s。setup cost 系数为 0 时，`tmp-timegraph-best-40-2-setupcost0-20260701` 结果为 `obj=bound=22580`，总时间 `160.752s`，root `124.298s`，节点 `67`，pricing `1443` 轮，列池 `131418`，TimeIndexedGraphPricing `21.745s/1352 calls`。setup cost 系数为 1 时，`tmp-timegraph-best-40-2-setupcost1-20260701` 结果为 `obj=bound=22874`，总时间 `74.305s`，root `60.765s`，节点 `30`，pricing `786` 轮，列池 `108727`，TimeIndexedGraphPricing `10.536s/770 calls`。

当前结论为：在这个 40-2 原始时间尺度算例上，加入 setup cost 后目标值确实变化，说明修复后的成本口径已经生效；但它没有让 time-indexed pricing 更难收敛，反而减少了节点、pricing 轮次和列池规模。直观原因可能是 setup cost 使一部分重复/绕行 pseudo-schedule 的吸引力下降，LP 尾部负列数量减少。这个结论只适用于当前原始小 horizon 算例，不代表放大时间尺度或更复杂 setup 结构下 setup cost 仍会加速；后续若要判断“setup cost 是否让 pseudo-schedule bound 变差”，需要继续在 timeJitterX10 或 cluster/random setup 上做同口径对照。

### 2026-07-01 setup time / setup cost 比例与文献口径复核

本次讨论的核心不是单纯把 `setupCost = coefficient * setupTime` 的系数调大，而是先确认当前 setup time 本身是否已经偏小。当前代码生成 setup 的原始设定是 `eta=0.5`，即随机生成前的平均 setup 约为平均加工时间的 50%；但随后会对包含虚拟起点 0 的完整 setup 图做 Floyd 三角闭包。这个闭包会把很多直接 setup 降成经其他点中转后的较短值，因此最终数据里的实际 setup/p 比例明显低于生成时的 eta。以 `wet040_001_2m` 为例，平均加工时间约 `51.63`，闭包后的平均 setup time 约 `9.03`，实际 `avgSetup/avgP` 只有约 `17.5%`，低于 Kramer/Cicirello 口径中的 small setup 水平。

文献口径上，`On the exact solution of a large class of parallel machine scheduling problems` 使用的 Kramer 2015 setup 实例来自 Şen-Bülbül 数据，并额外加入 small / large 两档 sequence-dependent setup。Kramer 的说明中，setup 生成参考 Cicirello and Smith (2005)，设 `\bar{s}=eta*\bar{p}`，并在 `[0,2\bar{s}]` 的截断正态范围内生成，使用的 severity 为 `eta=0.25` 和 `eta=0.75`。因此文献中的 small / large 大致对应平均 setup 为平均加工时间的 25% / 75%。如果当前数据闭包后只有 17%-25% 左右，那更接近 small setup，甚至低于 small；不能用 `eta=0.5` 的生成参数直接说明当前数据处于中等 setup 强度。

对 setup cost 的判断也要分开看。若 `setupCost` 与 `setupTime` 成正比，它主要是在目标函数中进一步强化“少走长 setup 弧”的倾向；因为 setup time 本身已经会推迟完工时间并影响 TWET penalty，所以二者并不是完全独立的权衡。真正强的冲突来自两类情形：一是 setup cost 和 setup time 不成比例，存在时间短但成本高、时间长但成本低的弧；二是 setup 结构有明显 cluster/family 差异，短 setup 弧可能导致 due-window penalty 变差。当前 `coefficient=1` 在 `wet040_001_2m` 上带来的目标增加约 `294`，相对 2.3 万量级目标只是一两个百分点，比例偏小，不足以显著改变列结构。若要做灵敏度实验，更合理的是同时调 setup time 结构和 setup cost 系数，例如先构造闭包后仍能达到 small/large 两档的 setup/p 比例，再测试 cost 系数 0/1/5/10。

和 VRP 的差异也需要写清楚。VRP 中 travel/setup 时间常常可以和 service time 同量级甚至更大，尤其客户间距离远、服务时间固定较短时，travel 会主导路径成本；调度中的 setup 更多表示机器换型、清洗、模具/刀具切换，很多场景下 setup 短于加工时间。但也存在反例，例如短作业、频繁换型、涂装/印刷颜色切换、食品/制药清洗、半导体批次切换等，setup 可以接近或超过单个作业加工时间。Kramer 的 `eta=0.75` large setup 就属于“setup 已经接近加工时间”的调度口径。

### 2026-07-01 setup ratio 变体生成工具

为避免直接覆盖现有 `data/40-2` 数据，本次新增 `Common.SetupRatioVariantGenerator`，用于从已有 `.dat` 生成不同 setup severity 的实验变体。工具不改任务行和机器数，只重建 `SETUP` 块；目标不是生成前的 `eta`，而是 Floyd 三角闭包后的真实 `job-to-job 平均 setup / 平均 processing`。这样可以直接得到文献意义上的弱/中/强 setup time 变体，而不会被闭包压低后还误以为强度足够。

使用方式示例为：先执行 javac -encoding UTF-8 -cp src -sourcepath src src/Common/SetupRatioVariantGenerator.java，再执行 java -cp src Common.SetupRatioVariantGenerator data/40-2/wet040_001_2m.dat data/setup-variants/40-2 0.25,0.50,0.75。本次对 `wet040_001_2m` 试生成三档，结果为：目标 `0.25` 需要生成前 `eta≈0.733133`，闭包后实际 `0.249469`；目标 `0.50` 需要 `eta≈1.429247`，闭包后实际 `0.500292`；目标 `0.75` 需要 `eta≈2.150611`，闭包后实际 `0.749587`。这进一步说明原始 `eta=0.5` 经过闭包后确实偏弱，不能直接拿生成前 eta 和 Kramer/Cicirello 的 small/large 口径比较。

setup cost 系数暂时不写入实例文件，仍使用运行时属性 `twet.data.setupCostFromTimeCoefficient` 控制。推荐后续实验组合为：先用 `setupR25/setupR50/setupR75` 三类 setup time 变体，再分别跑 cost 系数 `0/1/5/10`。其中 `0` 对应只让 setup time 影响完成时间；`1` 是当前“同单位成本”弱成本口径；`5/10` 用来测试 setup cost 在目标函数中达到可见比例后，time-indexed pseudo-schedule 和 ng-DSSR 的差异是否扩大。

### 2026-07-04 strong branching 分支筛列复核

本次重新检查了当前分支处理，重点是 arc branch 右支的 `branch-implied forbidden arc`、外包 membership 列分支，以及 strong branching 试探过程中的筛列和 repair。当前语义仍保持：显式 forbidden/required arc 进入 master branch row；右支 required arc 推导出的竞争入弧/出弧只用于 pricing 和列兼容性，不额外建立 master row。这样做的原因是 master 中真正分支变量仍是选中的 `x_ij`，竞争弧属于定价域收缩和历史列过滤语义。

检查发现一个需要澄清的状态解释问题：strong trial 在初始 LP 或 repair 成功后会进行二次 reduced-cost/兼容性筛列，用于准备可复用 child seed。由于当前筛列会保留当前正值机器列，且在筛列前已经检查过 branch-implied M 列是否为正，二次筛列后 RMP 理论上应继续可行。如果这里变成 infeasible，不应把它当成正常分支结果或静默 fallback，而应暴露为内部状态错误，说明“保留正值列仍保持可行”的实现假设被破坏。

修复后的口径是：`rmp_trial_infeasible_after_filter` 直接抛出异常，便于定位筛列、正值列保留或 M 惩罚口径中的真实问题。真正仍可作为不可行试探结果的是初始 LP 不可行且 repair 后仍 infeasible，或者开启 branch-implied M 惩罚时仍有正值 M 列。这两类表示当前试探在分支行和 M 口径下没有得到可用 child LP。

机器数量分支和 tariff segment 分支只改变 master 行，不依赖列兼容性筛选表达分支，因此没有这类筛列误判问题。外包 membership 在列化模式下会筛外包列，但其正值外包列同样会被保留；如果筛列后仍不可行，也应按异常路径暴露。当前 focused `javac` 已通过；尚未重新跑完整算例，本次结论主要来自静态路径检查和编译验证。

### 2026-07-04 strong branching phase1 筛列后重解精简

继续复核 phase1 trial 后确认，strong branching 里不需要把 repair 成功后的 LP 再转回正式 RMP 求一次 `repair_final`。repair mode 的作用是给不可行 RMP 加 artificial slack，从而得到一个可求解的临时 LP 和 repair dual；当 repair 结束时 slack 已经归零，当前 repair 模型的 primal objective 就等于当前列集下的正式 RMP objective。因此对 phase1 评分来说，可以直接使用这个 bound。

后续 seed 也不要求正式模型 dual 才能选。当前实现是在 repair slack 归零后，用 repair 模型已有 reduced cost 筛出 seed，然后关闭 repair mode；phase2 或正式 child 出队时会基于这个 seed 重新建 LP 并求解。因此 strong phase1 现在不再求 `repair_final`。同理，在普通机器列口径下，phase1 最后的 `after_column_filter` 也不再重解；列化外包模式暂时保留一次筛后重解，因为它同时筛内部列和外包列，组合口径更复杂，后续如需优化再单独处理。

继续修正 M 惩罚语义：初始 trial LP 可行但存在正值 branch-implied M 列时，不能直接把该 side 判失败；这和初始 LP infeasible 一样，说明当前 restricted seed 还没有修干净，应进入 repair。repair 循环现在以“artificial slack 归零且正值 M 列归零”为停止条件；只要 slack 或 M 仍为正，就继续按 repair pricing 补列。只有当 pricing 补不出列后 slack 或 M 仍为正，才把该 side 判为不可用。

### 2026-07-04 列化外包 membership 分支 repair 修正

本次按最新复核结论修正列化外包 membership 分支在 strong trial / child repair 中的列继承口径。核心问题是：如果在构造 child RMP 前就按 `required outsourced job j` 删除所有包含 `j` 的内部机器列，父节点原来的正值列可能被提前删掉，导致初始 LP infeasible 不再只由新分支行造成，repair 判断会被 seed 预筛污染。

修正后的流程是：`LP.construct()` 只过滤全局预处理禁弧，不再按当前 node 的外包 membership 状态提前删除内部列或外包列。列化外包分支改为显式 master row：`required j` 对“包含 j 的外包列之和”加下界，`forbidden j` 对同一表达式加上界。这样初始 LP / repair 看到的是“父节点列集 + 新分支行”的真实状态。

M 开关现在明确区分两种口径。`enableStrongBranchingBranchImpliedPenalty=false` 时，不惩罚父节点正值脏列，required 外包右支中旧内部列如果仍含有 `j`，可以临时留在 LP 中；这只是弱 trial 口径，不会生成新的违规内部列。`enableStrongBranchingBranchImpliedPenalty=true` 时，strong trial 从第一次建模开始就把两类 branch-implied 脏内部列目标系数设为 big-M：arc required 右支推导出的竞争弧列，以及列化外包 required job 下仍包含该 job 的内部机器列。初始 LP 可行但存在正值 M 列时，不直接丢弃 side，而是和初始 LP infeasible 一样进入 repair；repair 循环要求 artificial slack 和正值 M 列同时归零，否则补不出列后判该 side infeasible。

light seed 也同步改成同一语义：父节点正值内部列和正值外包列先保留，非正值列再按 child 域筛。repair 成功后筛 seed 时仍优先保留当前正值列，避免为了 reduced-cost 截断把当前可行基删坏。外包 pricing、route enumeration 外包枚举、dual snapshot 和 smoothing 梯度均补入 outsourcing membership branch dual，保证列化外包分支下 reduced cost 口径一致。

当前仍保持：M 只用于 strong trial / repair 评分和 seed 准备，不写入正式列成本；正式 BPC 主问题中的列成本仍是真实 TWET/outsourcing objective。机器数量和 tariff segment 分支不依赖列兼容性筛选表达分支，本次不改。验证方式为 focused `javac` 编译通过；尚未重新跑完整外包列算例。

### 2026-07-04 分支筛列可行性与 M 开关口径复核

本次进一步复核 `resetRestrictedColumnsByCurrentReducedCost()` 的实际语义。当前筛列不是“把所有不兼容列都删掉”，而是先无条件保留当前 LP 中正值的内部机器列；列化外包模式下，正值外包列也同样先保留。之后才对非正值列按当前 child 域兼容性和 reduced cost allowance 做筛选。因此，只要筛列前的 child LP 已经可行，按当前实现筛列后理论上仍应可行，因为原可行解的正值支撑列没有被删掉。正式 `PC.solve()` 中 `after_column_filter` 后如果又进入 infeasible repair，应理解为历史防御分支或数值/异常状态兜底，不是正常预期路径。

strong branching phase 1 的 seed 筛选也按同一口径处理：repair 或初始 trial 得到可行 LP 后，筛列只用于减少后续 child seed 规模，不应破坏当前正值支撑。当前 strong trial 已不再为了 seed 筛选额外重解一次 LP；phase 2 或正式 child 出队时会基于筛后的 seed 自己建模求解。

M 开关打开或关闭时，整体流程保持一致：都是先用父节点列集或 light seed 建 trial LP，不可行则进入 repair，repair 后再筛 seed。差别只在不可行/可用性的判断口径。关闭 M 时，只看 artificial slack 和显式分支行可行性；arc required 右支的竞争列、外包 required job 下仍含该 job 的内部列可以临时留在 trial LP 中，因此评分偏弱但流程简单。打开 M 时，这些 branch-implied 脏列从第一次建模开始就按 big-M objective 处理；如果初始 LP 可行但正值解仍使用 M 列，等价于 trial 还没修干净，需要进入 repair。repair 最终必须同时满足 slack 为 0 且正值 M 列为 0，否则该 side 在 strong trial 口径下判为不可用/不可行。

无向 adjacency 分支原则上也可以套用 arc 分支的 light seed、正值列保留和 M 惩罚思路，因为它同样是对机器序列域的限制。但之前实验中无向分支会明显恶化 dual/pricing 质量，当前主线也不使用该分支，因此只记录可行处理方向，不继续扩展实现。

### 2026-07-04 筛列后不可行兜底清理

上面复核后继续检查实现，确认正式 child 的 `after_column_filter` 仍保留了“筛后 infeasible 再 repair”的旧兜底。这个兜底和当前筛列实现已经不一致：`resetRestrictedColumnsByCurrentReducedCost()` 会先保留当前正值内部列，列化外包时也先保留当前正值外包列，因此筛列只应减少非正值候选列，不应破坏已有可行 LP。若筛后正式 RMP 变为 infeasible，说明正值列读取、筛列状态或模型重建存在真实错误，继续 repair 只会掩盖问题。

本次已将该路径改成显式异常：正式 child `after_column_filter` infeasible 时直接抛出错误，不再进入 repair。同类地，repair 已经确认 artificial slack 和 branch-implied M 列清零后，再切回无 slack 正式 RMP 求 `repair_final`；如果此时仍 infeasible，也直接抛出错误。默认关闭的 domain-filtered strong repair 最后切回正式 RMP 时也做同样检查。这样以后若再出现“筛列保留正值列但模型不可行”，会直接暴露具体 node 摘要和 LP 消息，而不是被当成普通不可行节点或继续修复。

同时重新检查了其它类似路径。strong branching phase 1 的 seed filter 当前不再重解，只返回筛后的 seed 给 phase 2 或正式 child；phase 2 初始不可行和加启发式列后不可行本来就是显式异常，不属于静默兜底。cut 删除或新 cut 后的 infeasible 不是筛列问题，仍按原控制流返回。domain-filtered repair 是默认关闭的实验路径，它本身就是“先按域筛再 all-row slack repair”的独立口径，这次只补最终正式 RMP 可行性断言，不改变其默认关闭状态。

### 2026-07-04 当前主线冗余与兜底复查

本次按当前常用主线再次走了一遍 `Tree -> PC -> LP -> pricing` 的分支、strong branching、repair 和筛列流程。确认 `LP.construct()` 现在只按调用方传入的 seed 建 restricted RMP，不再隐式按当前 node 的 outsourcing membership、branch-implied arc 或全局预处理弧删除列；这点是必要的，因为初始 LP/repair 的不可行性必须来自新增分支行或后续 pricing 补不出列，不能被建模入口提前筛列污染。

正式 child 路径目前是：先继承父节点 restricted columns 建 LP；若初始 LP 不可行，则走旧 repair，只给当前新增分支行加 artificial slack，并通过 pricing 补列直到 slack 清零；repair 成功后筛 seed 并切回无 slack 正式 RMP 求解。若初始 LP 可行，则先按 reduced cost 和 node 域筛 seed，但筛列函数会无条件保留当前正值内部列，列化外包时也保留当前正值外包列。因此筛后不可行不再是正常分支流程，当前已改为显式异常而不是继续 repair。

strong branching phase 1 路径也复查了一遍。普通 trial 和 lightweight trial 现在共享同一个 branch-implied M 开关口径：开启时，required arc 右支的竞争弧列、以及列化外包 required job 下仍包含该 job 的内部机器列，从第一次建 trial RMP 开始就按 big-M objective 处理；初始 LP 可行但正值 M 列仍存在时，和初始 LP 不可行一样进入 repair。repair 停止条件是 artificial slack 和正值 M 列同时清零。phase 1 结束后的筛列只用于生成后续 child seed，不再为了 seed 重解一次 LP；phase 2 或正式 child 出队时会基于这个 seed 自己建模求解。默认关闭的 `enableStrongBranchingDomainRepair` 仍保留为历史实验口径，不纳入当前主线冗余处理。

这轮真正发现并清理的实现冗余只有一个：`PC.solveStrongBranchingRmpTrial()` 里的局部变量 `repaired` 只赋值、不读取，已经删除。它不影响求解结果，但保留会误导后续阅读者以为 repair 后还有单独分支逻辑。

仍建议暂时保留的“兜底/防线”包括：正式 repair 后的 `repair_final`，因为正式节点需要回到无 slack RMP；strong trial 中的 M 正值检查，因为它是判断 trial 是否仍依赖脏竞争列的核心条件；phase 2 infeasible 的显式异常，因为 phase 1 可复用 seed 理应能建出可行 LP；`debugSkipBranchColumnFilter` 和诊断开关默认关闭，不参与常用求解。当前未处理但可后续观察的局部效率点仍是 RMIH duplicate repair fallback 中的序列 evaluator 重算，是否值得缓存应结合 RMIH 日志再判断。

### 2026-07-04 50-2 有 setup 的 time-indexed / SRI / ng-DSSR 对比

本次按 `wet050_001_2m` 有 setup 算例补做三组对比：time-indexed no-cut + strong branching、time-indexed rank-1/SRI + strong branching，以及当前 ng-DSSR 好配置 + time-indexed root preprocessing。三组是并行运行的，因此 wall time 有一定 CPU 竞争噪声；但结果都 `valid=true`，目标一致为 `44383`，可以用于判断量级和主要耗时结构。

time-indexed no-SRI strong branching 结果为 `solve=409.170s, root=187.036s, nodes=10, pool=141680, exact=30.584s/667, master_lp=254.557s`。这比此前同口径最好记录 `378.979s` 略慢，但仍处在同一量级，说明当前改动没有破坏 no-SRI time-indexed 的主要优势。该配置仍是这三组中总时间最短的一组。

time-indexed rank-1/SRI strong branching 结果为 `ROOT_PROCESSED, solve=558.132s, root=558.128s, nodes=1, pool=90688, exact=101.550s/721, master_lp=353.785s`。虽然 CSV 状态写成 `ROOT_PROCESSED`，但 incumbent 与 bound 都是 `44383`，gap 为 0，且 root 直接闭合，因此可以视为根节点收敛求最优。SRI/cut 明显强化了根节点，下界直接闭合且列池小于 no-SRI；但 cut 迭代和 master LP 开销更大，导致总时间反而慢于 no-SRI。

ng-DSSR nearestK8/top10 + time-indexed root preprocessing + strong branching 结果为 `solve=527.970s, root=364.706s, nodes=5, pool=77804, heuristic=72.639s/454, exact=43.677s/145, master_lp=238.487s`。相比此前 ng-DSSR 有 setup 最好记录 `1280.392s, nodes=5, exact=633.335s/138`，本次 root preprocessing 后 exact pricing 时间大幅下降；但预处理本身和 master LP 仍占比较大，因此总时间仍慢于纯 no-SRI time-indexed。

当前结论是：在这个 `50-2` 有 setup 实例上，time-indexed no-SRI + strong branching 仍然最快；SRI/rank-1 cut 能显著加强根节点，但额外 cut/LP 开销没有被完全抵消；ng-DSSR 接 time-indexed root preprocessing 后已经从“明显慢很多”变成“同一量级但仍偏慢”。后续如果要更公平地做论文实验时间，应该单线程串行重跑这些配置，避免并行实验导致的 wall time 波动。

补充拆分时间后，三个配置的瓶颈并不一样。no-SRI time-indexed 总时间 `409.170s`，其中 master LP 合计 `254.557s`，占比约 62%；真正的 graph pricing 只有 `30.584s/667`，repair find-feasible pricing 另有 `8.514s/742`。强分支相关 RMP LP 是最大项，`strong_branching_rmp=151.154s/280`，比 graph pricing 本身大很多。root 的 CSV `root_s=187.036s`，而 node1 summary 的 `nodeTime=206.233s`，差异主要来自 root 上分支/RMIH 等 node 内附加操作；node1 结束时 total 已到 `290.548s`，说明初始列构造/ALNS 等前置约有 80 秒量级。

SRI/rank-1 time-indexed 总时间 `558.132s`，root 直接闭合。这里没有后续分支成本，主要瓶颈变成 cut 迭代下的 master LP：`after_pricing=287.716s/707`，`after_cut=47.884s/7`，`after_inactive_cut_removal=17.934s/6`，合计 master LP `353.785s`，占比约 63%。rank-1 exact pricing 本身为 `101.550s/721`，占比约 18%；cut separation 只有 `0.837s/7`，不是瓶颈。也就是说 SRI 的慢主要不是“找 cut 慢”，而是 cut 之后反复解更大的 RMP 慢。

ng-DSSR + time-indexed root preprocessing 总时间 `527.970s`。预处理本身 `243.169s`，其中 graph fixing `1.934s`、scalar fixing `1.032s` 都很小，主要成本仍是临时 time-indexed root column generation/RMP。预处理后得到 `promotedOrdinaryArcs=2138`、`avgWindowLen=283.180`、`avgShrinkRatio=0.902`，说明它确实明显压缩了后续 ng-DSSR 的扩展域。正式 root node `nodeTime=114.921s`，root CSV 时间为 `364.706s`，基本可以理解为预处理加正式 root 求解；node1 结束 total 为 `419.212s`，差额包含初始列/ALNS 等前置成本。全局 master LP 为 `238.487s`，其中 `after_pricing=195.530s`；ng-DSSR exact pricing 只有 `43.677s/145`，启发式 pricing 为 `72.639s/454`，strong branching 中启发式试探另有 `43.697s/288`。因此这组里 ng-DSSR exact 已经不是主要瓶颈，主要时间在预处理临时 root、RMP LP 和启发式/strong-branching 试探。

由此得到的判断是：time-indexed no-SRI 快，不是因为 LP 小，而是因为 graph pricing 极便宜，即使列池很大，整体仍能靠快速 pricing 和强分支推进；SRI 更强但把成本转移到 cut 后的大 RMP；ng-DSSR 经 root preprocessing 后 exact 成本已经降到可接受，剩下主要是前置预处理和 RMP/启发式成本。

继续补跑 `ng-DSSR partial-list dominance + SRI`，配置为 nearestK8/top10、time-indexed root preprocessing、strong branching、cut-loop fixing 打开，且 active SRI 下的 time-indexed fixing 使用默认 no-SRI relaxed 口径。结果为 `ROOT_PROCESSED, obj=bound=44383, solve=358.990s, nodes=1, pool=20555, cutPool=60, pricingRounds=1144, valid=true`。它在 root 直接闭合，是目前这组 50-2 有 setup 对照里最好的记录之一，比 no-SRI time-indexed 的 `409.170s` 和 time-indexed rank1/SRI 的 `558.132s` 都快。

这组的时间拆分为：time-indexed root preprocessing `131.631s`，正式 node1 `177.846s`，总 root `358.979s`。正式 root 内部 LP `15.388s/439`，pricing `159.536s/643`，其中 heuristic `51.030s/439`，partial+SRI exact `108.507s/204`；cut separation 只有 `0.096s/6`，加入 `60` 条 cut。root preprocessing 生成的临时列仍不复制到正式 RMP，只转移普通 pricing-only arc 和 compact window；本次 `promotedOrdinaryArcs=2138`、`avgWindowLen=283.180`、`avgShrinkRatio=0.902`，和前面 ng-DSSR 预处理口径一致。

关于 preprocessing 时间，需要修正前面“约 300s”的粗略说法。旧 ng-DSSR run 中 `timeIndexedRootPreprocess.done ms=243169`，不是 300s；加上正式 root node `114.921s` 后，CSV root 才到 `364.706s`。纯 no-SRI time-indexed 的 root 为 `187.036s`，node1 summary 为 `206.233s`。两者是同一量级，但不应认为必然相等：预处理临时 root 用的是最小 seed 口径并要额外提取 fixing/window 证据，旧 run 的 tempPool 到 `124512`，而纯 no-SRI time-indexed root 当时 node1 pool 到 `86679`。本次 partial+SRI run 的预处理为 `131.631s`，说明该时间对初始 seed、dual 路径和运行时负载很敏感，只能看作“接近一次 time-indexed root 级别的额外成本”，不能固定估成 300s。

当前新的判断是：如果 partial-list + SRI 能在 root 闭合，且 time-indexed preprocessing 能把窗口压到 10% 左右，那么该组合可能比纯 time-indexed rank1/SRI 更划算。原因是它没有 time-indexed rank1 那种大 RMP cut-loop LP 压力，正式 root 的 master LP 只有 `113.142s`，远低于 time-indexed rank1/SRI 的 `353.785s`；代价是 partial-list exact 本身更慢，`108.507s/204`，但仍被 root 闭合和较小列池抵消。
### 2026-07-04 time-indexed relaxed gap 何时会和 ng-DSSR 拉开

结合前面 40-2 原始算例、setup cost 变体、setupR25/R50/R75 以及 timeJitter/放大时间实验，当前判断是：time-indexed pseudo-schedule 和 ng-DSSR elementary/ng 列之间的 gap 差距，不一定会因为“只加入 setup cost”立刻变大。更可靠的放大因素是整数时间 horizon 变大、setup 结构变得更强且更不均匀、以及重复访问 job 的 pseudo-schedule 更容易在 LP 中被利用。

40-2 原始时间尺度下，time-indexed 的表现很强，主要因为 horizon 还不大，DAG 最短路很便宜，虽然列是 relaxed/pseudo-schedule，但 root gap 和最终搜索并没有明显吃亏。加入 `setupCost = coefficient * setupTime` 后，至少在原始 40-2 和较温和的 setup 矩阵上，并没有观察到 time-indexed bound 明显变差；相反，setup cost 会惩罚一部分绕行和重复访问，可能减少尾部负列。也就是说，setup cost 本身不是单调“让 time-indexed 更差”的因素，尤其当 setup cost 和 setup time 成比例、setup 矩阵满足三角不等式、且 setup/p 比例不高时，它更多只是改变目标权重，不一定显著扩大 relaxed gap。

真正更容易拉开差距的是时间尺度和 setup 结构。前面放大时间、尤其是非均匀 jitter 放大的实验显示，time-indexed 的计算复杂度和离散时间点数直接相关，horizon 一大，图规模、end state 数、同一序列不同完成时间的候选都会膨胀；同时 pseudo-schedule 列虽然生成快，但会带来更多弱列和更长的 RMP 尾部。ng-DSSR 的函数式 pricing 对时间尺度更稳定，不会因为所有时间都乘大或变成更细的 scale 就同比例膨胀。因此在“大 horizon / 小数需要 scale / 时间离散粒度细”的场景下，time-indexed 和 ng-DSSR 的差距最容易变明显。

setup 相关的差距更可能出现在两类情况下。第一，setup time 本身接近或超过 processing time，且不是简单平滑三角闭包矩阵，而是有明显 cluster/family、长短弧差异和顺序选择冲突；这时 elementary 序列结构更重要，pseudo-schedule 的重复 job 和非基本路径更容易制造不真实的 LP 支撑。第二，setup cost 与 setup time 不完全成比例，例如时间短但成本高、时间长但成本低，或者有 family-change penalty；这种情况下目标不再只是“晚一点完工”的替代成本，而是直接对弧选择施加结构性惩罚，更可能让 relaxed 列与真实 elementary 列的质量分离。

因此后续判断 gap 差距是否明显，建议不要只看 `setupCost` 开关，而是同时看几个指标：root 下 positive pseudo-schedule 列里重复 job 的比例、time-indexed pool 规模、同一 sequence 的多完成时间候选数量、root bound 与 ng-DSSR root bound 的差值、以及最终搜索节点数是否靠 relaxed 列堆出来。实验矩阵上可以继续用 40-2/50-2/60-2，组合原始时间、3-5 倍非均匀放大、10 倍非均匀放大，再叠加 setupR25/R50/R75 和 setup cost 系数 0/1/5/20。当前最明确的经验结论是：小 horizon 下 time-indexed 很可能占优；horizon 变大或小数 scale 后，ng-DSSR 的相对优势更容易显现；setup cost 只有在比例足够大且结构足够不平滑时，才可能明显放大二者 root gap。

更具体地看 40-2 证据，`setupCost` 本身目前不是最强解释。原始 `wet040_001_2m` 在修复 setup cost 口径后，`setupCost=0` 的 time-indexed no-cut 记录为 `obj=22580, solve=160.752s, root=124.298s, nodes=67, pool=131418`；`setupCost=1` 反而是 `obj=22874, solve=74.305s, root=60.765s, nodes=30, pool=108727`。这说明至少在原始 40-2 的小 horizon、平滑三角 setup 矩阵上，加入和 setup time 成比例的弱 setup cost 没有把 time-indexed relaxed gap 放大，反而可能减少了绕行/重复访问伪列的吸引力，使尾部更轻。

真正能把差距打出来的是 `wet040_001_2m_timeJitterX10`。同一轮 900s 对照里，ng-DSSR 关闭 time-indexed helper 时 `510.258s` 收敛到 `104113`；打开 post-node window tightening 后进一步降到 `394.459s`，列池从 `123244` 降到 `66016`。而 time-indexed no-SRI 在 900s 内没有收敛，root bound 只有 `102869.043478`，最终 lower bound `103195.700000`，incumbent `104836`，gap `1.5646%`；time-indexed SRI/rank-1 甚至仍停在 root。这个对照说明：当时间尺度被非均匀放大以后，time-indexed 的劣势不只是“慢”，而是 root bound / 收敛证明也明显变差；ng-DSSR 则可以借助函数式时间表示和继承 compact window 保持稳定。

因此基于 40-2 当前证据，最可信的排序是：第一，非均匀放大时间尺度或需要整数 scale 会最明显拉开 gap；第二，强 setup time 结构可能会拉开，但需要 setupR50/R75、cluster/family 或非比例 setup cost 进一步验证；第三，单纯 `setupCost = c * setupTime` 且 c 不大，不但不能证明 gap 会变大，原始 40-2 反而显示它可能让 time-indexed 更容易收敛。

### 2026-07-04 root gap 口径修正

前面关于 gap 的讨论需要明确区分“最终收敛 gap / 时间限制 gap”和“root LP gap”。如果只看 root bound 与最终最优值之间的 gap，40-2 变体给出的结论更细：timeJitterX10 的总求解和闭合能力差距最大，但 root gap 差距不一定最大；setupR50/R75 + cost20 反而在 root bound 上更能看出 ng-DSSR 比 time-indexed 强。

按 `(opt - rootBound) / opt` 计算，当前有证据的 40-2 root gap 如下。原始 setup cost0 中，time-indexed root bound 为 `22487.647059`，ng-DSSR root bound 为 `22490.000000`，对应 root gap 约 `0.409%` 对 `0.398%`，差距只有约 `0.010` 个百分点，基本可以认为 root bound 强度接近。zeroSetup 中二者 root bound 都是 `17866.666667`，对最优 `17881` 的 root gap 约 `0.080%`，没有 relaxed gap 差异。

setup cost20 原始 40-2 中，time-indexed root bound 为 `27996.083333`，ng-DSSR 为 `28035.113636`，对最优 `28110` 的 root gap 分别约 `0.405%` 和 `0.266%`，ng-DSSR 强约 `0.139` 个百分点。setupR25 + cost20 中，time-indexed root bound 为 `31869.600000`，ng-DSSR root 直接到 `31893.000000`，root gap 分别约 `0.073%` 和 `0`。setupR50 + cost20 中，time-indexed root bound 为 `43426.375000`，ng-DSSR 为 `43573.571429`，对最优 `43625` 的 root gap 分别约 `0.455%` 和 `0.118%`，差距约 `0.337` 个百分点。setupR75 + cost20 中，time-indexed root bound 为 `54655.816092`，ng-DSSR 为 `54808.500000`，对最优 `55007` 的 root gap 分别约 `0.638%` 和 `0.361%`，差距约 `0.278` 个百分点。

timeJitterX10 中，time-indexed root bound 为 `102869.043478`，ng-DSSR root bound 为 `103000.421053`，如果按最终已知最优 `104113` 算，root gap 分别约 `1.195%` 和 `1.069%`，差距约 `0.126` 个百分点。也就是说，timeJitterX10 的绝对 root gap 都明显变大，但二者 root gap 差距没有 setupR50/R75 那么大；它更大的问题是 time-indexed 后续 900s 无法闭合，而 ng-DSSR 能收敛。

因此如果问题问的是“root gap 差距什么时候明显”，根据 40-2 证据，答案应是：`setupR50/R75 + 高 setup cost` 这类强 setup 结构下，ng-DSSR 的 elementary/函数式定价 root bound 明显强于 time-indexed pseudo-schedule；如果问题问的是“整体求解什么时候明显拉开”，则 `timeJitterX10` 这类大 horizon / 非均匀放大最明显。两者不能混为一个 gap 结论。

同时也要承认另一个重要事实：这些 40-2 变体里，time-indexed 的 root gap 大多数本身并不大。zeroSetup 是 `0.080%`，原始 setup 是 `0.409%`，setupR25 + cost20 是 `0.073%`，setupR50/R75 + cost20 也只是 `0.455%/0.638%`，只有 timeJitterX10 到 `1.195%`。这说明在当前数据结构下，pseudo-schedule 松弛虽然理论上弱于 elementary/ng 列，但 root RMP 已经很强，不能把 time-indexed 方法简单描述成“bound 很差”。更准确的表述是：小整数 horizon 和当前平滑 setup 数据下，time-indexed root bound 足够强，优势主要来自定价便宜；ng-DSSR 的 root bound 更强但提升幅度通常只有零点几个百分点，未必足以抵消连续时间函数 pricing 的成本。

因此后续论文实验如果要体现 ng-DSSR 的价值，不能只盯 40-2 小 horizon 的 root gap。更应该强调两类场景：一是 horizon 放大、小数时间 scale、外包/分段成本等 time-indexed 图会膨胀或离散化困难的场景；二是 setup 结构更复杂、pseudo-schedule 重复访问比例明显升高、或者 time-indexed 需要大量列才能维持小 root gap 的场景。当前 40-2 结果反而说明，原文 time-indexed 类方法在小整数时间实例上作为 baseline 是很强的，不能低估。

进一步形成一个待验证猜想：当前 40-2 里 time-indexed root gap 很小，可能和 due date 仍然是单点有关。即使考虑 setup time，任务完成时间被单点 due date 周围的 earliness/tardiness penalty 紧紧约束，重复访问某个 job 通常会推迟后续任务、增加时间代价，因此 pseudo-schedule 里重复 job 的收益不明显。换句话说，当前数据虽然允许 time-indexed pricing 生成非 elementary 路径，但目标函数本身已经把很多重复访问压掉了。

如果后续改成更宽的 due window，情况可能不同。due window 较宽时，一个 job 在较大时间区间内完成都没有或只有很小惩罚，pseudo-schedule 就更可能通过重复访问、绕行或同一序列多时间点来制造低 reduced-cost 列；此时 time-indexed relaxed root bound 与 ng-DSSR elementary/ng root bound 的差距可能会更明显。类似地，总体时间尺度更大、due window 更宽、时间惩罚斜率更平缓时，重复访问的时间代价下降，也更可能暴露 relaxed 图的松弛问题。setup 暂时不作为主要解释变量：在当前平滑三角 setup 和比例 setup cost 实验里，它没有稳定放大 root gap；真正需要观察的是宽 due window 和大 horizon 是否会提高 pseudo-schedule 正值列中重复 job 的比例。
### 2026-07-04 60-2 四组对比中 ng-DSSR 慢在哪里

对 `wet060_001_2m` 的四组对比中，两个 time-indexed 版本已经完成，两个 ng 版本被手动停止。已完成结果为：time-indexed no-SRI `FINISHED, obj=bound=36817, solve=1567.649s, root=527.299s, nodes=34`；time-indexed rank1/SRI `FINISHED, obj=bound=36817, solve=1692.235s, root=1362.981s, nodes=3`。

两个 ng 版本的主要慢点不是强分支。`ng-DSSR + time-indexed root preprocessing + seed200` 停止时已经出 root，卡在 node 2 的 pricing tail。累计统计为：临时 time-indexed root preprocessing 中 `TimeIndexedGraphPricing=102.973s/728`，对应 node0 master LP `333.490s/727`，预处理总计约 `460.746s`；正式 ng-DSSR exact pricing `2918.197s/166`，其中 node1 `1447.745s/82`、node2 `1470.452s/84`；启发式 pricing `515.666s/365`；强分支相关 master LP 只有约 `29.894s`，不是主瓶颈。node 2 后期 LP objective 长时间停在 `36752.000000`，每轮 exact pricing 常花十几到二十多秒但只返回 1 到几条列，说明主要问题是 ng exact pricing 的长尾退化。

`ng partial + SRI + time-indexed root preprocessing + seed200` 停止时仍在 root。累计统计为：同样的临时 time-indexed root preprocessing 约 `460.929s`；partial dominance exact pricing `3092.422s/253`，全部发生在 node1/root；启发式 pricing `728.682s/502`；root 的 after-pricing master LP 只有 `10.569s/498`，after-cut 约 `2.865s/3`。因此这个版本当前还没真正体现出 SRI cut 的整体收益，主要时间已经被 root 上大量 partial exact pricing 和启发式 pricing 消耗掉。

由此得到的判断是：在 60-2 上，ng-DSSR 的瓶颈不是 LP 也不是 strong branching，而是反复完整跑 ng exact pricing，但每次只产生很少有效列，LP bound 改善很慢。time-indexed root preprocessing 本身也不便宜，约等于一次临时 time-indexed root 的量级，其中大头是临时 RMP 反复求解而不是图最短路。后续若继续优化 ng 路线，应优先处理 root/node pricing tail、列返回策略、DSSR 更新/初始 ng-set、以及是否用 time-indexed root 结果切换后续定价器，而不是继续调整 strong branching。

进一步对比单次 pricing 成本后，问题更直接：time-indexed 图 pricing 在本次预处理中约为 `102.973s/728`，平均每次 `0.141s`；标准 ng-DSSR exact pricing 为 `2918.197s/166`，平均每次约 `17.6s`；partial+SRI root 上 partial exact pricing 为 `3092.422s/253`，平均每次约 `12.2s`。也就是说，time-indexed 一次 pricing 是 0.1 秒量级，而 ng-DSSR 一次 pricing 是十几到二十秒量级。ng-DSSR 的理论列更强，但如果一次 exact pricing 只返回少量列，且后续 LP bound 几乎不动，这个强度优势就会被单次 pricing 成本完全吃掉。

这也解释了为什么 time-indexed 即使列弱、列多，仍可能整体更快：它的 DAG 最短路/候选恢复很便宜，可以高频快速补列；ng-DSSR 则要做双向标签、函数包络、completion bound、DSSR 多轮更新和大量 join 检查，单次求解成本高很多。后续优化 ng-DSSR 时，真正要盯的是“每次 exact pricing 的平均耗时”和“每次 exact pricing 带来的 LP bound 改善/新增有效列数”，而不是只看总列数或 root gap。
### 2026-07-05 due window 宽度对 time-indexed root 的影响

本次按 `wet040_001_2m` 原始数据派生宽 due-window 实验，没有复制或修改原始 `.dat` 文件，而是在 Tanaka loader 里新增实验属性 `twet.data.dueWindowHalfWidth`。该属性只用于实验：原始文件仍按 due date 读取，若设置 `W>0`，则把每个 job 的 due window 改成 `[max(0,d_j-W), d_j+W]`，随后重新计算 `CmaxH`、静态 hard window 和 penalty function。这样 setup、processing、权重等都保持不变，只观察 due-window 宽度本身的影响。

为避免 ALNS 和启发式 pricing 干扰，本轮使用纯 time-indexed no-cut root-only 配置：`timeIndexedGraphPricing=true`、`enableHeuristicPricing=false`、`runALNSForSeed=false`、`strongBranching=false`、`maxNodes=1`、`maxExactColumns=5000`，并关闭 time-indexed completion-bound/window/arc-fixing 增强，只看 time-expanded graph pricing 自身把 root LP 收敛到无负列后的状态。状态为 `NODE_LIMIT` 是因为只允许处理 root 一个节点；root 本身已经完成 pricing 收敛。

结果如下。`W=0` 时 root bound 为 `22487.647059`，root gap 为 `64.299%`，pool 为 `46555`，exact pricing `5.347s/226`，root 正值列 `18` 条，其中 elementary 正值列 `15` 条，非 elementary 正值列 `3` 条，正值列总权重为 `2.0`。`W=100` 时 bound 降到 `11207.448276`，gap 为 `74.424%`，pool `39879`，exact `5.062s/203`，正值列 `22` 条，其中 elementary `12` 条、非 elementary `10` 条。`W=300` 时 bound 进一步降到 `1355.727273`，gap 为 `90.953%`，pool `33907`，exact `6.043s/211`，正值列 `22` 条，其中 elementary `8` 条、非 elementary `14` 条。`W=600` 时 bound 基本为 `0`，gap 为 `100%`，pool `12302`，exact `4.282s/42`，正值列 `24` 条，elementary 正值列为 `0`，全部 `24` 条正值列都是非 elementary，最大正值序列长度达到 `89`。

这组结果支持前面猜想：当前原始 due-date 算例里 time-indexed root bound 很强，很大程度上是因为单点 due date 和 dual/window 共同压制了重复访问 job 的收益；当 due window 被放宽后，pseudo-schedule 的非 elementary 列开始明显进入 root LP 正值解，root bound 迅速变弱。尤其 `W=600` 时，正值列已经全部是非 elementary，说明 relaxed time-indexed RMP 确实在利用重复 job 的伪路径结构。

需要注意，本轮关闭了 ALNS，因此 CSV 中的 incumbent 只代表该 root-only 诊断配置下的当前上界，不用于和完整 BPC 最终时间直接比较。这里真正需要看的指标是 root LP bound、正值列中 elementary/非 elementary 的比例、以及 pool/pricing 规模。后续如果要做完整求解对比，应在同一宽窗设置下分别跑 time-indexed 与 ng-DSSR 的完整配置，并重新打开一致的 ALNS/强分支策略。

随后按同一派生宽窗口径重跑了带 ALNS 的 root-only 对比，修正上面的 gap 口径。配置保持 `timeIndexedGraphPricing=true`、`enableHeuristicPricing=false`、`strongBranching=false`、`maxNodes=1`，但打开 `runALNSForSeed=true` 且 `alnsMaxMillis=60000`。因此 root gap 使用 ALNS 得到的 incumbent 计算；正值列统计仍来自 root LP 收敛后的解。新结果为：`W=0` 时 incumbent `22582`、bound `22487.647059`、gap `0.417824%`，正值列 `17` 条，其中 elementary `14` 条、非 elementary `3` 条；`W=100` 时 incumbent `11221`、bound `11207.448276`、gap `0.120771%`，正值列 `22` 条，其中 elementary `12` 条、非 elementary `10` 条；`W=300` 时 incumbent `1378`、bound `1355.727273`、gap `1.616308%`，正值列 `22` 条，其中 elementary `7` 条、非 elementary `15` 条；`W=600` 时 ALNS 已找到 `0` 成本解且 root bound 也是 `0`，问题在该宽度下基本退化，正值列只有 `2` 条且均为 elementary。

由此修正结论为：无 ALNS 的 root gap 不能引用为正式 bound 质量指标，只能说明诊断 run 的临时上界很差；正式比较应使用带 ALNS 的 gap。即使如此，宽 due window 对 time-indexed relaxed 列结构的影响仍然存在：从 `W=0` 到 `W=300`，root LP 正值列中的非 elementary 数量从 `3/17` 增加到 `15/22`，说明 pseudo-schedule 重复 job 确实更容易成为 LP 正值支撑。`W=600` 过宽导致目标退化为 0，不适合作为“gap 变差”的证据，后续更合适的宽窗测试区间应放在 `W=100` 到 `W=300` 一类仍有非零目标且重复列比例明显上升的范围。

进一步对 `W=300` 做完整 time-indexed no-cut 求解，打开 ALNS 60s 和 strong branching，仍关闭旧 HeuristicPricing。结果为 `FINISHED, obj=bound=1362, solve=115.133s, root=54.373s, nodes=6, pool=70265, valid=true`。root bound 为 `1355.727273`，root gap 为 `1.616308%`。总 exact graph pricing 为 `20.497s/699`，master LP 为 `70.864s`，其中 after-pricing LP `28.261s/694`，strong-branching RMP `41.823s/160`。这说明 `W=300` 确实让 time-indexed root 松弛变弱、正值解中非 elementary 列明显增多，但在 40-2 这个规模和当前整数 horizon 下，完整求解仍很快；真正的大头已经转向 RMP/strong branching，而不是 time-indexed shortest path 本身。

同时补充了两类统计日志。直接跑 time-indexed pricing 时，root 收敛后会输出 `timeIndexedRootSolutionColumns positiveCols=... elementaryPositiveCols=... nonElementaryPositiveCols=...`。ng-DSSR 开启 time-indexed root preprocessing 时，`timeIndexedRootPreprocess.done` 也会带上临时 time-indexed root 的 `rootSolution={...}` 统计。这里的 elementary/basic 口径指 job sequence 中没有重复 job，不是 CPLEX basis 里的 basic variable。

### 2026-07-05 非均匀 timeJitter 放大与 fixed due window 复核

前面一度用 `twet.data.timeScaleFactor` 对 processing、due 和 setup 做了统一乘 4/5 的实验，这个口径不适合回答“类似之前 timeJitterX10 的放大”问题。统一乘法本质上只是把时间轴整体缩放，如果 due window 半宽也同比例缩放，模型几乎是同构的，root gap 保持不变；如果 window 放得过宽，又会直接退化为零罚实例。因此该钩子已撤回，不再作为本轮诊断依据。

本轮改为沿已有 `wet040_001_2m_timeJitterX10.dat` 的扰动方向生成 x4/x5。具体做法是对原始文件和 x10 文件逐项插值：`new = original + (x10 - original) * (target - 1) / 9`，只作用于 processing、due date 和 setup time，权重保持原样。这样 target=1 回到原始实例，target=10 回到已有 timeJitterX10，target=4/5 则保留 x10 的非均匀扰动模式。生成目录为 `test-results/bpc/tmp-wet040-001-2m-time-jitter-x4x5-input-20260705`。统计上，x4 的平均 processing/due/setup 比例约为 `4.066/4.011/2.823`，x5 为 `5.089/5.015/3.432`；setup 比例低于 processing 是因为原 x10 中 setup 平均只放大约 `6.471` 倍。

先尝试把 due window 半宽也按 300 同比放大到 x4 的 `1200` 和 x5 的 `1500`，结果两个 root 都直接得到 `obj=bound=0`，说明窗口过宽后目标退化，没有 gap 诊断意义。更合理的对照是保持 fixed due window 半宽 `W=300`，只把时间数据做非均匀放大。该配置为：纯 time-indexed no-cut root-only，`enableHeuristicPricing=false`、`runALNSForSeed=true`、`alnsMaxMillis=60000`、`strongBranching=false`、`maxNodes=1`，并关闭 completion-bound/window/arc-fixing 增强。

fixed `W=300` 下，x4 root 结果为 `NODE_LIMIT, incumbent=23178, bound=22953.886157, root gap=0.966925%, root=167.343s, pool=76365`，其中 time-indexed exact pricing `58.133s/333`，master LP `68.010s/333`。root LP 正值列 `32` 条，其中 elementary `16` 条、非 elementary `16` 条，正值列总权重为 `2.0`。

x5 root 结果为 `NODE_LIMIT, incumbent=30289, bound=30082.283697, root gap=0.682480%, root=324.796s, pool=119548`，其中 exact pricing `128.582s/502`，master LP `156.042s/502`。root LP 正值列 `35` 条，其中 elementary `16` 条、非 elementary `19` 条。

这组结果比简单宽 due-window 实验更接近前面 timeJitterX10 的现象：非均匀放大以后，time-indexed root 仍能闭合，但 root 列数和时间明显上升，且正值解中非 elementary 列比例接近或超过一半。x4/x5 的 root gap 仍没有变得特别大，说明当前 40-2 数据即便放大到 4/5 倍，time-indexed pseudo-schedule 的 root bound 仍不算很差；真正明显的问题是 root 收敛需要的列数和 RMP/pricing 轮数持续增加。若继续沿这个方向验证，下一步应比较 x4/x5 的完整 time-indexed 与 ng-DSSR，而不是只看 root gap。

这里还需要明确一个口径：x4/x5 的 fixed `W=300` 不是相对时间尺度更宽的 due window。相反，processing 和 due date 已经放大到约 4/5 倍，而窗口半宽仍保持 300，因此相对宽度比原始 `W=300` 更窄，会继续压制一部分重复访问收益。同比放大到 `W=1200/1500` 又直接零罚退化，所以如果要继续找“更宽窗口下 relaxed gap 变大”的证据，应该在 x4/x5 上扫描介于 fixed 300 和退化阈值之间的窗口，例如先试 x4 的 `W=500/700/900` 或 x5 的 `W=600/800/1000`，而不是只看当前 fixed 300。
### 2026-07-05 60-2 上 ng-DSSR 慢点复核与 nearestK3 对照

本次重新复核 `wet060_001_2m` 的历史日志后，当前判断比较明确：60-2 上 ng-DSSR 干不过 time-indexed 的主要原因不是 strong branching，也不是 cut 管理，而是 exact pricing 单次成本过高，并且尾部每次只能补很少的有效 elementary 列。标准 ng-DSSR + time-indexed root preprocessing + seed200 的 run 在手动停止时已经出 root，卡在 node 2 的 pricing tail。临时 time-indexed root preprocessing 总计约 `460.746s`，其中 `TimeIndexedGraphPricing=102.973s/728`、临时 node0 master LP `333.490s/727`；正式 ng-DSSR exact pricing 累计 `2918.197s/166`，其中 node1 为 `1447.745s/82`、node2 为 `1470.452s/84`。启发式 pricing 为 `515.666s/365`，strong branching 相关 master LP 只有约 `29.894s`，不是主瓶颈。

更具体地看，node 2 后期 LP objective 长时间停在 `36752.000000` 附近，很多 exact pricing 调用需要十几到二十多秒，但只返回 `1` 到几条列。这说明问题不是“找不到任何列”，而是退化尾部中每一轮完整 ng-DSSR labeling/join/completion-bound 证书成本很高，带来的 bound 改善却很小。对比同一算例的 time-indexed no-SRI，后者已完成 `FINISHED, obj=bound=36817, solve=1567.649s, root=527.299s, nodes=34`，其图定价本身为 `182.889s/2921`，平均每次约 `0.063s`；而标准 ng-DSSR exact 平均每次约 `17.6s`。也就是说，time-indexed 可以用大量便宜 pseudo-schedule 列快速推进，而 ng-DSSR 虽然列更强，但在 60-2 上单次定价成本高两个数量级，优势被完全吃掉。

partial + SRI 版本也没有绕开这个瓶颈。该 run 停在 root，预处理同样约 `460.929s`，partial dominance exact pricing 为 `3092.422s/253`，全部发生在 root；启发式 pricing `728.682s/502`，after-pricing master LP 只有 `10.569s/498`，after-cut 约 `2.865s/3`。因此当前 partial+SRI 的慢点也仍是 root 上大量 exact pricing 与启发式 pricing，而不是 cut separation 或 RMP。

由此得到的暂时结论是：50 以下的小规模或中等 horizon 上，ng-DSSR 可以凭更小列池、更强列和 time-indexed preprocessing 追到 time-indexed 同一量级；时间尺度放大时，ng-DSSR 相对 time-indexed 的图规模优势会显现。但 60-2 这种规模下，如果 root/node tail 需要反复完整 exact pricing，ng-DSSR 会被单次 pricing 的函数包络、双向 label、join 和 DSSR 更新拖住。后续优化优先级应放在降低 exact pricing 单次成本、减少尾部 exact 调用次数、改进初始/更新 ng-set 以及判断何时用 time-indexed root 或 time-indexed 定价切换，而不是继续调 strong branching。

基于这个判断，已经启动一组最小变更对照：保持 60-2 标准 ng-DSSR 主线配置不变，只把 `ngDssrInitialSize=8` 改为 `3`，目录为 `test-results/bpc/tmp-60-2-001-ng-initial3-tirootpre-seed200-20260705`，配置仍为 `nearestK3/top10`、time-indexed root preprocessing、seed200、strong branching、dual-bound pruning、allCycles completion bound、pricingOnly subtree 和 midpoint probe。该组用于判断小初始 ng-set 是否能降低 exact pricing 单次 label/join 成本，还是会因为 DSSR 轮数增加而变慢。启动后仍在临时 time-indexed root preprocessing 阶段，尚未进入正式 ng-DSSR pricing。
### 2026-07-05 结合文献表 4 对 setup、规模和 due window 的判断

重新看 `On the exact solution of a large class of parallel machine scheduling` 表 4 后，可以把前面对 time-indexed root gap 的判断再收紧一些。文献中无 rank-1 cuts 时，长 setup 组的 root gap 明显高于短 setup 组，例如 `(40,2,S)` 为 `1.59%`、`(40,2,L)` 为 `3.27%`，`(60,2,S)` 为 `1.66%`、`(60,2,L)` 为 `3.31%`，`(80,4,S)` 为 `2.27%`、`(80,4,L)` 为 `5.05%`。带 rank-1 cuts 后 gap 被明显压低，但长 setup 和更大规模/更多机器仍然更难，例如 `(80,4,L)` 仍有 `3.73%` root gap，最终 gap 也达到 `1.75%`。这说明原文算例里，setup 长度和规模确实会放大 time-indexed pseudo-schedule 松弛的弱点；rank-1 cuts 主要是在 root 上补强这个松弛。

这个观察和我们当前实验并不矛盾。我们 40-2 原始/部分 setupR 变体里，time-indexed root gap 大多不大，主要原因可能是当前 due date 仍接近单点约束，任务完成时间被 earliness/tardiness penalty 压得很紧，重复访问 job 的收益被抑制。宽 due window 诊断已经显示，窗口变宽后 root LP 正值列中 non-elementary pseudo-schedule 比例明显上升，root bound 也变弱。由此更合理的压力测试方向不是单独调 setup，而是把 `n=60/80`、较长 setup time、较宽但不退化为零目标的 due window 结合起来看。

需要注意，setup 的影响分两层。第一，setup time 变大本身会增大 time-indexed 图规模和 horizon，使 DAG pricing/RMP 轮次更重；第二，setup 结构如果仍然平滑且满足三角闭包，重复访问未必总是更有利，甚至较大的 setup cost 可能抑制绕行。真正可能拉开 root gap 的，是长 setup 与宽 due window 同时存在，使得 time-indexed pseudo-schedule 有更大时间空间去重复访问或绕行，同时又不会马上被单点 due penalty 压掉。因此后续应优先构造 `60/80` 任务、长 setup、宽 due-window 的组合实例，观察 time-indexed root gap、正值列 non-elementary 比例、pool 规模和 root time，而不是只看 40-2 小 horizon 的结论。
### 2026-07-05 60-2 ng-DSSR 单次 pricing 耗时拆解

本次复核 `wet060_001_2m` 的 K=3 对照日志时，确认当前主线的 pricing-only 类加强已经按预期打开：completion-bound subtree arc elimination 使用 pricing-only 口径，time-indexed root preprocessing 会把普通弧提升为 pricing-only arc 并写入 compact window，后续 ng-DSSR pricing、completion bound、启发式 pricing 和 time-indexed helper 都通过 node 的 pricing 图口径消费这些状态。需要区分的是，`timeIndexedCompletionBoundInRoundArcFixing=false`，也就是每次 exact pricing 内部不再额外跑一轮 time-indexed 临时 arc fixing；当前主要依赖 root preprocessing、node/cut-loop 间的 fixing、completion-bound scalar 和已有 compact window。

从刚停掉的 `tmp-60-2-001-ng-initial3-tirootpre-seed200-20260705` 看，慢点主要不在 midpoint probe。node 1 的多次 ng-DSSR exact pricing 中，单次总耗时常见为 `10s~43s`，其中 midpoint probe 候选测试通常是几百毫秒到 2 秒多，少数轮次约 4 秒，占比一般为 5%~15%，尾部个别轮次会到 20% 左右。它不是零成本，但不是主瓶颈。真正重的是完整 exact run 里 forward/backward label、join 以及 PWLF 函数级 reduced-cost 检查。例如一轮 `33.417s` 的 pricing 中，join pairs tried 为 `8293076`，`funcEval=8220478`，说明绝大多数 join pair 已经走到了函数级检查；另一轮 `32.199s` 中 `funcEval=11126762`，同样是千万级函数检查。

这里的 `funcEval` 不是简单计数，也不是普通 label 数。它表示 join 阶段一个 forward label 与一个 backward label 经过集合、时间下界、scalar/completion-bound 下界等便宜过滤后，仍然需要真正做分段线性函数组合、平移、加弧/任务 reduced cost，并检查最小 reduced cost 是否可能为负。`funcPruned` 则是这些昂贵函数检查之后发现不能产生负列而被剪掉的数量。也就是说，`funcEval` 大时，说明 cheap bound 没能提前挡住大量 join pair，算法正在为大量候选做 PWLF 级别的最终判定，这就是当前 60-2 ng-DSSR 单次 pricing 慢的核心。

completion bound 构造本身有时也会成为一部分大头，但不是每轮都有。多数早期轮次 `completionBoundInternal timingMs` 为 0，说明复用已有 bound 或未在该轮重建；某个尾部轮次总耗时 `10.988s`，其中 completion-bound internal forward/backward 构造分别约 `1.682s/4.079s`，合计约 `5.76s`，这时 bound 构造和函数 join 检查共同占时。该轮只返回 1 条列，说明已经进入典型 tail：为了证明/找到极少量负列，要付出较完整的 bound 和 join 证明成本。

因此当前需要优先关注的不是 midpoint probe，而是如何减少进入函数级 join 的候选数量，或者减少 tail 阶段 exact pricing 调用次数。可观察指标应包括每轮 `funcEval/funcPruned`、`join pairs tried`、`completionBoundScalar check/pruned`、`completionBoundInternal timingMs`、`totalNonElementarySeen` 和最终返回的 elementary columns。当前新启动的 `tmp-60-2-001-ng-k3-repeatability-tirootpre-seed200-20260705` 仍在 time-indexed root preprocessing 阶段，尚未进入正式 ng-DSSR；后续若要判断 repeatability 初始 ng-set 是否有效，应重点看这些指标是否明显下降。
### 2026-07-05 dominance graph 单节点多 label 统计

这次针对 `wet060_001_2m` 的 ng-DSSR pricing 慢点，进一步关注同一个 dominance graph 节点，也就是同一个 reachableSet / dominance key 下到底保留了多少个活跃 label。这个问题很关键：如果大量耗时不是来自 key 数量太多，而是来自同一个 key 下仍保留很多 PWLF label，那么继续只调 ng-set 大小未必能解决 `funcEval` 爆炸，真正要优化的是同 key 内部的函数前沿压缩、join 前的更强下界，或者 cutoff-aware 的函数最小值计算。

从已有日志只能做粗略估算，因为此前只记录了 `labels kept/rejected/deleted` 和 `nodes created/deleted`，没有直接记录每个 active node 内的 label 分布。以 60-2 K=3 那组前几轮为例，第一轮约为 `(31747 - 3318) / (1387 - 215) = 24.3` 个活跃 label / dominance node；第二轮约为 `21.3`；第三轮约为 `25.8`。这个量级已经说明同一个 dominance key 下保留几十个 label 是真实现象，不是少数异常。

为后续诊断，`PaperDominanceGraph` 现在在统计摘要里增加 `activeLabelPerNode=nodes/labels/avg/min/max/multi`。其中 `nodes` 是仍有活跃 label 的 dominance graph node 数，`labels` 是这些 node 下未被 dominated 的活跃 label 总数，`avg/min/max` 是同一个 node 下活跃 label 数的均值、最小值和最大值，`multi` 是活跃 label 数大于 1 的 node 个数。这个统计只在输出 summary 时扫描当前 pricing 内构造过的 graph，不改变 dominance、label 扩展或列生成逻辑。

后续判断口径为：如果 `avg` 持续在 20 以上，且 `max` 很大，说明同一 reachableSet 内 PWLF 前沿过厚，需要考虑更强的同 key label 压缩或函数级 dominance；如果 `avg` 不高但 `funcEval` 仍高，则主要问题更可能是 forward/backward 两侧 label 组的 cross product 和 cheap bound 不够强，需要优先做 join 前下界或 cutoff-aware join，而不是继续压缩 dominance node 内部 label。

追加一个 root-only 诊断 run：`test-results/bpc/tmp-active-label-per-node-40-2-20260705`，口径为 `wet040_001_2m`、ng-DSSR no-SRI、nearestK3/top10、time-indexed root preprocessing、ALNS 10s、RMIH/strong branching 关闭、maxNodes=1。该 run 返回 `NODE_LIMIT`，root node 用时 `4.369s`，exact pricing `1.305s/8`，主要用于看统计量，不作为正式性能对照。

这 8 次 ng-DSSR exact pricing 的 `activeLabelPerNode` 为：`5.751/1/35`、`5.361/1/40`、`5.294/1/38`、`5.087/1/36`、`4.813/1/41`、`4.773/1/40`、`4.421/1/33`、`4.024/1/33`，其中三元组为 `avg/min/max`。汇总后，平均的 avg 约 `4.94`，最大 max 为 `41`，平均 active dominance node 数约 `189`，平均 active label 数约 `934`，8 轮合计 `funcEval=14910`、`totalNonElementarySeen=1816`、返回 elementary 列 `129`。这说明在 40-2 这个较小实例上，同一个 reachableSet 下确实会有多个 label，但平均只有约 5 个，尚不是主要瓶颈。

对照 60-2 的旧日志 `tmp-60-2-001-ng-k3-repeatability-tirootpre-seed200-20260705`，虽然旧日志没有 `min/max`，但可用 `(labels kept - labelsDeleted) / (nodes created - nodesDeleted)` 粗估同 key 活跃 label 数。node1 前 9 次 ng-DSSR exact pricing 的近似均值为 `24.257, 21.319, 25.764, 31.911, 22.345, 31.580, 29.708, 28.061, 22.233`，平均约 `26.35`，这几轮合计 `funcEval=39501481`、`totalNonElementarySeen=15727865`。因此 60-2 慢点里确实存在“同一个 dominance key 下 PWLF label 前沿过厚”的问题，不只是 dominance key 数量多或单纯 join cross product 大。

当前判断是：40-2 上 `avg≈5` 时，优化同 key label 压缩的收益可能有限；60-2 上粗估 `avg≈26` 时，这个方向值得认真考虑。后续若在 60-2 新代码上重跑，应直接看精确 `activeLabelPerNode` 的 `max` 和 `multi`。如果 max 达到数百，优先考虑同 key 内 PWLF 前沿压缩、cutoff-aware dominance 或更强的函数包络合并；如果 max 仍只是几十但 `funcEval` 很高，则重点转向 join 前下界和 forward/backward group 级剪枝。

进一步讨论后确认，`activeLabelPerNode` 大本质上说明同一个 dominance graph node 内存在多条互不全局支配的 PWLF 前沿。当前代码已经在 `PaperDominanceNode` 内维护 `labelEnvelope`，也就是同 node 下所有真实 label frontier 的点态下包络；这个 envelope 会用于支配其他 label 和向后继传播，但不会替代真实 label 参与扩展、join 和列恢复。因此它只能帮助“证明别人没用”，还不能减少本 node 内部真实 label 的扩展和拼接数量。

这里不能简单地“同一个 reachableSet 只保留一个普通 label”。原因是同一 node 内多个 label 往往是函数前沿交叉：某条 label 在较早完成时间更好，另一条在较晚完成时间更好。任意删掉其中一条，都可能删掉某个时间段上真正产生负 reduced cost 列的路径。正确的强压缩思路不是保留一个原始 label，而是构造一个“envelope label”：其 frontier 是所有 label 的下包络，同时每个函数分段要记录该段来自哪条原始 label / father 链。后续扩展或恢复列时，必须按分段来源找回对应 partial path，否则会得到一个成本函数正确但路径不可恢复或路径成本不匹配的伪 label。

这个方向理论上是有空间的，尤其在 no-SRI、无额外 cut 状态、同 terminal job 且同 reachableSet 的场景下，未来可扩展集合由 node key 决定，扩展算子主要依赖 terminal job、下一任务、setup/processing、dual 和当前 frontier。若把路径来源按分段保留下来，envelope label 可以替代一组真实 label 做更紧凑的扩展和 join。带 SRI、partial dominance、额外 memory/count 状态或更复杂分支语义时，key 必须包含所有影响未来成本和可行性的状态；否则同 key envelope 可能把不兼容的状态混在一起。

因此后续优化应分两步。第一步先加诊断，统计每个 active dominance node 中真实 label 数、`labelEnvelope` 分段数，以及真正贡献下包络的原始 label 数。如果 60-2 中平均真实 label 约 26，但 envelope contributor 只有 3 到 5 条，说明 envelope-label 压缩可能有很大收益。第二步再考虑实现 segment-owner envelope label，或者先在 join 前用 node-level envelope 做 group-level cheap bound，减少进入逐 label `funcEval` 的 pair。后者改动较小，不能完全消除 label 数，但更适合作为第一版安全优化。

补充一点：不能把“插入时没有被 envelope 完整占优”理解成“最终所有 live label 都贡献当前 node 的下包络”。在 normal paper dominance 下，新 label 插入同一个 node 时，若旧 envelope 完整支配它会被拒掉，因此它在插入时通常确实有某段更优；但之后再插入的 label 可能覆盖掉这段。当前 normal 模式不会回头清理同 node 内已经不再贡献下包络的旧 label：`propagateAndTrim()` 从 changed node 的 successors 开始传播，`removeLabelsDominatedByPredecessors()` 也只按 predecessor envelope 删除后继 node 内 label，不会用同 node 的新 `labelEnvelope` 反扫本 node。

这里需要区分 partial dominance。若 `partialDominance=true`，同 node 插入新 label 前会调用 `sameNode.trimLabelsBy(label.frontier)`，即用新 label 的 frontier 扫描并裁剪已有 label；这时如果新 label 单独完整覆盖旧 label 的残余定义域，旧 label 会被删掉。但它仍不是“用更新后的整组 labelEnvelope 统一反扫所有旧 label”：如果某个旧 label 只有被 `min(L2,L3)` 联合覆盖，而不被新来的 `L3` 单独覆盖，则这类冗余不一定被清掉。当前主线 normal ng-DSSR 更不能假设 live label 数等于 envelope contributor 数。因此 contributor 统计仍然有必要，它能区分“每条 label 都是真正的下包络分段来源”和“历史上曾有用但后来被覆盖、仍留在 active list 里继续参与扩展/join”的两类情况。

还要避免另一个误解：normal paper dominance 不是全局只做“新 label 被旧 envelope 干掉”。若新 label 形成新 dominance node，或同 node 的 envelope 被更新，`propagateAndTrim()` 会沿 dominance graph 传给后继 node；后继 node 若被 predecessor envelope 完整支配，会整点删除，或者删除其中被 predecessor envelope 完整支配的 label。因此 normal 仍然有旧/新 envelope 对后继 node 的反向清理。这里讨论的冗余只限于“同一个 reachableSet node 内部”：normal 下 `sameNode.addLabel()` 后不会用更新后的本 node `labelEnvelope` 重新扫描本 node 自己的旧 label。

当前进一步判断是，这个问题主要是效率问题，不是正确性问题。一方面，normal paper dominance graph 需要维护 reachableSet 包含图、predecessor/successor、envelope 传播和 subset/superset 搜索；在同一个 node 内 label 很多时，最终仍要扫描这些真实 label 做扩展或 join，graph 结构本身未必比直接 list partial dominance 更便宜。另一方面，partial dominance 虽然能裁剪同 node 内旧 label 的一部分区间，而且 SRI 场景下有自己的补偿逻辑，但它仍保留真实 label 作为扩展对象，不等价于把同 key 的函数下包络合成一个状态。因此二者都可能出现“多个 label 在同一个 reachableSet 下互相只支配部分时间段，结果都要继续扩展”的效率问题。

因此更核心的方向是同一个 dominance graph node 内只维护一个可扩展的下包络状态，而不是保留所有真实 label。这个下包络状态必须带分段来源，能够在生成列时恢复真实 partial path；否则成本函数正确但路径不可恢复。若实现成功，扩展次数和 join pair 数会按 envelope contributor / envelope segment 数而不是真实 label 数增长。当前先不直接改主逻辑，后续优先补统计：每个 node 下真实 label 数、envelope 段数、贡献下包络的来源 label 数，以及被标记 dominated 但仍滞留在 active list / queue 中的数量。

现有 label 生命周期如下：普通非单点 label 通过 `insertForward/insertBackward()` 成功插入后，会放入对应 terminal 的 `PaperDominanceGraph`，也会加入 `activeForwardByLastJob` 或 `activeBackwardByFirstJob`，并以 `STORED_AND_ENQUEUE` 进入 `FWUL/BWUL` 优先队列。后续出队时先检查 `label.isDominated`，若已被传播占优标记，则直接跳过，不再扩展。也就是说，被占优 label 可能仍物理留在 priority queue 或 active list 里，但逻辑上通过 `isDominated` 懒过滤。join 阶段扫描 active list 时也会跳过 `isDominated` label。

被占优 label 的去向分几类。新 label 若插入前被 dominance envelope 支配，直接 `label.isDominated=true` 并返回，不进 graph/queue。已有 label 若在 propagation 中被 predecessor envelope 完整支配，会从 node 的 `labels` 列表中移除并标记 dominated；若整个 node 被支配，node 失活，内部所有 label 标记 dominated。partial 模式下被裁剪为空的 label 也会移除并标记 dominated。由于 Java `PriorityQueue` 和 active list 不主动删除这些对象，所以它们可能继续占一点容器空间，但不会再扩展或作为有效 join 对象。

同一个 node 内新 label 对旧 label 的更新方式取决于 dominance 模式。normal 模式下，旧 label 不被同 node 的新 label 扫描；新 label 只合并进 `labelEnvelope`，重算 `dominanceEnvelope`，再向后继 node 传播。partial 模式下，`sameNode.trimLabelsBy(new.frontier)` 会在新 label 加入前扫描旧 label，用新 label 的 frontier 裁剪旧 label；裁剪后重建 `labelEnvelope`，再把新 label 加入。两种模式都没有实现“用更新后的整个同 node labelEnvelope 重新压缩本 node 所有 label”为一个 envelope-state 的逻辑。

### 2026-07-05 同 key envelope label 优化设想

当前进一步提出的核心设想是：同一个 terminal job、同一个 dominance key / reachableSet 的 label，本质上是在同一个状态点上拥有多条不同 PWLF frontier。如果只为了求当前 pricing 的最小 reduced cost，下包络 `min(L1,L2,...)` 已经足够表达这个状态的最优成本函数；当前保留 20 多条真实 label，会导致这些互相只支配部分时间段的 label 都继续扩展、进入 join，从而把扩展和 `funcEval` 放大到接近 label 数倍。因此，如果能把同 key 下所有 label 压缩为一个可扩展的 envelope label，理论上可能大幅降低 60-2 这类实例的 exact pricing 时间。

主要难点不在“成本函数取下包络”，而在列恢复和状态一致性。当前 `PiecewiseLinearFunction.Segment` 只保存 `start/end/slope/intercept/next`，没有来源信息；如果简单把多条 label 的 frontier merge 成一条函数，最后找到负 reduced cost 的最小点时，只知道成本值，不知道这段成本来自哪条 partial path，也就无法恢复真实 job sequence。因此需要在 PWLF 外层封装一层带来源的 segment，例如 `EnvelopeSegment` 记录：该时间段的线性函数、来源 label 或来源 trace、父 envelope segment、由哪个扩展 arc/job 产生，以及必要的方向信息。最终从最优 segment 反向追溯这些 trace，才能恢复完整序列。

更准确地说，仅记录“来自哪个 label”可能还不够。因为一个 envelope label 经过多轮扩展后，它自身的某个 segment 可能已经来自更早的 envelope segment；此时需要的是 segment-level parent chain，而不是 label-level father。扩展一个 envelope segment 时，新 segment 的 trace 应指向旧 segment trace，并记录本次追加/前插的 job 和 arc。`mergeMinimum` 产生下包络时，输出的每段要继承胜出的输入段 trace；发生交点切分时，左右两段分别继承各自胜出的 trace。`shiftX`、`add(jobPenalty)`、`crop/setDomain`、`prefix/suffix normalize` 也都要维护 trace，特别是 prefix/suffix minimize 产生的平段应继承产生 running minimum 的那个源 segment，而不是当前被扫过的 segment。

还要注意状态 key 是否完整。当前 graph key 主要是 reachable/dominance set，但 label 还携带 `ngMemorySet`、`visitedSet`、SRI counts、no-SRI frontier 等信息。若同 key 下不同 segment 的 hidden state 不同，扩展时不能用一个统一的 ngMemory 或 SRI state 粗暴处理。可行做法是让每个 envelope segment 的 trace 同时携带其原始状态，扩展时按 segment 来源状态更新 ngMemory/SRI/visited。这样实现复杂度明显高于“一个 node 只存一条普通 PWLF”，但语义上才不会丢状态。无 SRI、状态较简单时可以先做实验；带 SRI 或 partial dominance 时必须把 cut memory/count 纳入 trace 或 state key，否则容易把不兼容状态混在一起。

因此这个方向可行，但不适合直接一步重构。更稳的路线是三层递进。第一层先只做诊断，统计每个 node 下真实 label 数、当前 `labelEnvelope` 的 segment 数、实际贡献下包络的来源 label 数、以及被完整 envelope 覆盖但仍保留的 label 数。第二层做保守清理：仍保留真实 label，不引入 envelope label，只尝试对同 node 内“完全不贡献下包络、或被 leave-one-out envelope 完整覆盖”的 label 做删除/裁剪；这能验证效率收益，同时不改变列恢复方式。第三层才实现真正的 traced envelope label，让扩展和 join 以 envelope segment 为单位进行，并通过 segment trace 恢复最终列。

短期更推荐先做第一层和第二层。原因是如果 contributor 数接近 live label 数，那么 full envelope label 也未必能减少很多状态，只是把复杂度从 label 数转移到 segment 数；如果 contributor 数远小于 live label 数，才说明 full envelope label 值得投入。此外，第二层“同 node envelope 清理”可能已经能消掉大量历史冗余 label，改动远小于重写整套 traced PWLF 操作。

关于 segment 里到底要记录什么，当前判断如下。若只考虑最终恢复 sequence，直觉上“当前 segment 指向父 segment，并知道自己所在的 envelope node”基本能追溯路径：forward 时当前 node 的 terminal job 就是本步追加的 job，父 segment 所在 node 的 terminal job 是上一步；backward 对称处理。但这只在 segment 对象稳定、且所有扩展状态都能从父链重算时成立。当前 `PiecewiseLinearFunction.Segment` 是可变链表节点，`mergeMinimum/add/shift/normalize/crop` 会频繁复制、替换、切分 segment，而且还有 `SegmentPool` 复用；直接把父指针挂在原始 Segment 上，容易出现父 segment 被修改或复用后的语义风险。

更稳的结构是把“函数几何段”和“路径来源”分开。可以为 envelope segment 维护一个不可变 `TraceNode/TraceState` 指针，而不是直接指原始 PWLF segment。每次普通函数变换如 crop、shift、add job penalty、mergeMinimum 中某段胜出时，输出 segment 继承胜出输入段的 trace；发生 prefix/suffix normalize 的平段时，继承产生 running minimum 的那个输入段 trace；真正扩展一个 job 时，才创建新的 trace，记录 parent trace、追加或前插的 job、arc、方向、terminal job，以及必要状态。这样最终从最优 segment 的 trace 反向追，就能恢复 sequence，同时避免依赖可变 Segment 对象本身。

如果这个 envelope segment 还要继续扩展，而不只是最后恢复列，则只记录 parent trace 仍可能不够高效。扩展需要知道该 segment 对应的 `ngMemorySet`、必要时的 visited 信息、SRI counts / cut state、当前 terminal job 和方向。理论上这些都可以沿 parent trace 重新计算，但每次扩展都回溯会很慢；更实际的是在 `TraceState` 中缓存这些派生状态。无 SRI、只做 ng-relaxation 时，最小缓存可以是 terminal job、direction、ngMemorySet 和 parent trace；带 SRI 时还要缓存 SRI counts 或等价 cut state。visitedSet 是否缓存取决于后续是否要频繁判断 elementary/SRI，若只在最终恢复列时使用，可以由 trace 重建；若扩展或 cut reduced cost 需要它，则也应缓存。

因此，“父 segment + 所在 node”是恢复路径的最小直觉模型，但不建议直接这么落地。更推荐的落地口径是：envelope function 的每个几何 segment 附一个 `TraceState`；`TraceState` 保存 parent trace 和本步动作，并缓存扩展所需的少量状态。这样既能恢复序列，也能保证后续扩展时不同 segment 的 hidden state 不会混在一起。

进一步细化后，`TraceState` 不一定要设计得很重。若按用户提出的口径，每个 traced segment 记录“父 traced segment + 当前 segment 对应的 label/state”，语义上基本可以覆盖恢复 sequence 的需求：父 segment 链负责回溯，当前 label/state 提供 terminal job、方向、ngMemory/SRI 等扩展状态。此时不必单独记录 `fromJob/toJob`，它通常可由父 segment 的 label terminal 和当前 label terminal 推出；也不必每段复制完整 sequence。

真正不能直接复用的是现有 `PiecewiseLinearFunction.Segment` 引用。即使不考虑 `SegmentPool`，当前 PWLF 操作也会原地修改和重建链表：`mergeMinimum()` 会用 `replaceWithSegment()` 切分并替换 `this` 上的 segment，还会把右参数复制后的 segment 拼进链表；`updateDominatedIntervals()` 会把被支配区间替换为 big_M 并切段；`normalize()` 会裁剪、合并相邻段，并调用 prefix/suffix minimize 重建函数。若 traced segment 只是持有这些可变 Segment 对象的引用，父链和来源容易在切段、改边界或重连后失去精确含义。

因此更准确的最小结构可以是：`TracedSegment { start,end,slope,intercept,parentSegment,stateLabel }`，其中几何字段是自己的稳定副本，不直接引用 PWLF 内部 Segment；`stateLabel` 是 segment 级状态，不是整个 envelope node 共用一个 label。若为了复用代码，也可以封装一个不可变 `SegmentGeometry` 对象，但不能让它指向会被 PWLF 后续操作改写的链表节点。

采用这种结构后，受影响的不是所有全局 PWLF 逻辑，而是 traced envelope 这条新路径里涉及的函数操作必须 trace-aware：`copy/shiftY/shiftX/setDomain` 基本继承原 trace；`add(jobPenalty)` 输出段应继承左侧 envelope 段 trace，因为 job penalty 不携带路径来源；`mergeMinimum` 输出段继承获胜输入段 trace，交点切分后左右段分别继承各自来源；`prefix/suffix normalize` 产生的平段必须继承产生 running minimum 的来源段 trace；相邻段合并时，如果几何相同但 trace 不同，不能像当前 `compactAdjacentEqualSegments()` 那样直接合并，否则恢复路径会丢失分段来源。这个点是 traced envelope 的主要实现成本。

进一步讨论后，traced envelope label 虽然理论上最彻底，但改动过大，不适合作为第一步。更现实的替代方案是先不让 envelope 替代真实 label，而是只把 node-level envelope 用作 group-level lower bound / prefilter。也就是说，真实 label 仍保留并负责最终扩展与列恢复；在 join 或扩展前，先用同 node 的 `labelEnvelope` 与对侧 envelope / completion bound 做一次便宜判定，如果 envelope 级别都不可能产生负 reduced cost，就直接跳过这一组真实 label 的 cross product。只有 envelope 级别可能有用时，再展开扫描真实 label。这个方案不需要 segment trace，正确性也更容易保证，因为它只做剪枝前置过滤，不改变最终生成列的来源。

另一个可选方向是“lazy provenance / 二阶段恢复”。第一阶段用 envelope pricing 只找出最优 reduced-cost 值、terminal、时间点和可能的状态；若发现负列，再在该状态和时间附近回到原真实 label 集合中找能达到该 envelope 值的来源 label，或者重跑一次受限恢复搜索。这个方案也避免在每个 segment 上永久维护 trace，但实现上需要一个可靠的 argmin-source 查询：给定 node、time 和 envelope 值，找到对应真实 label，并能沿 father 链恢复。它适合只需要少量最优列时，但如果每轮要返回很多列，重复恢复搜索可能抵消收益。

因此当前优先级调整为：第一，统计 envelope contributor 和 group-level envelope lower bound 命中率；第二，实现 node-envelope join prefilter，尽量减少 `funcEval` 前的真实 label pair；第三，若仍不够，再考虑 lazy provenance 或 traced envelope label。直接用 traced envelope 替代真实 label 暂时不作为近期实现首选。

后续进一步讨论后，这里需要修正优先级判断：group-level prefilter 只是缓解 join，不解决同 key 多 label 都要扩展的根问题。如果 60-2 的主要负担来自同一个 dominance node 内几十个 label 共同扩展，那么只做 prefilter 属于治标不治本。真正治本仍然是让同 terminal、同 dominance key 只保留一个可扩展 envelope state。

较可行的治本方案不是改全局 `PiecewiseLinearFunction.Segment`，而是在 ng-DSSR 内部单独实现一个 `EnvelopeLabel/EnvelopeFrontier`，它维护一条 traced 下包络。每个 traced segment 只需要保存稳定的几何区间、父 traced segment 和当前 segment 对应的 state label；不需要保存完整 sequence，也不必额外保存 from/to，只要父 segment 和当前 state label 能推出本步 job/arc。这样最终从 best segment 沿 parent segment 链回溯即可恢复序列。

关键实现点是这些 traced 操作必须保持 provenance。`mergeMinimum` 按赢家继承来源，交点切分后左右段分别继承不同来源；`shift/add/crop` 基本继承输入 segment 来源；`normalize` 必须把 prefix/suffix running minimum 的来源段传给新平段；相邻几何相同但来源不同的段不能合并。这个实现可以局限在新的 envelope frontier 类中，不污染全局 PWLF，但确实需要重写 envelope 路径所需的少数 PWLF 操作。

因此更准确的近期路线应是：先做 contributor/segment 数诊断，确认真实 label 数、envelope segment 数和贡献来源数的比例；如果 envelope segment 数远小于或接近可接受水平，就直接评估 traced envelope label 原型，而不是停留在 prefilter。prefilter 可以作为低风险辅助优化，但不能作为解决同 key 多 label 膨胀的主方案。

另一个更简单的原型方案是：每个 traced segment 直接保存当前 partial sequence，而不是保存 parent segment 链。这样最终恢复列最直接，找到最优 segment 后直接取它的 sequence；`mergeMinimum`、`crop`、`shift` 只要继承获胜段的 sequence，`normalize` 的平段继承 running minimum 来源段的 sequence，扩展时 forward 在 sequence 末尾追加 job，backward 在 sequence 头部前插 job。这个方案正确性更容易调试，也便于和当前真实 label 的 sequence 做对拍。

该方案的主要代价是内存和复制。若每次扩展或切段都复制 `ArrayList<Integer>`，复杂度会从“段数”乘上“路径长度”，在 60 个任务、多个 segment、多轮 merge/normalize 下可能很重。一个折中是原型阶段先用完整 sequence，便于验证 envelope pricing 的收益和正确性；若效果明显，再把 sequence 改成持久化链表或 parent segment trace，减少复制。为了避免扩展时反复从 sequence 重算状态，segment 最好同时缓存 terminal job、ngMemorySet，带 SRI 时缓存 SRI counts；否则每次扩展都 replay sequence，会把节省下来的 label 开销吃掉。

因此完整 sequence 版本适合作为第一版实验实现，尤其建议先限制在 no-SRI / no-cut ng-DSSR 下验证：只要 sequence 和 cached ngMemory 一致，就能保证最终列恢复；若后续接 SRI，再补 SRI state 缓存和 cut reduced-cost 口径。

当前 ng-DSSR labeling 已经使用硬时间窗。初始化时 `precomputeEffectivePricingWindows()` 将基础 hard window、根节点 dual profitable window、node 继承的 time-indexed compact window 取交集，写入 `effectiveJobHStart/End`，并据此缩小 `pricingHorizon`。随后 forward/backward 的 job penalty、direct extension feasibility、half-domain eligibility、completion-bound penalty 都基于这组 effective window 预计算。因此扩展阶段不是只在最后算成本时才知道窗口，而是在构造 dominanceSet / extensionSet 和 child label 前就会用窗口剪掉不可达任务。这个结论只针对当前 ng-DSSR 主线路径；历史 legacy pricing 入口不一定完全一致。

后续进一步讨论后，对“time-indexed `(i,j,t)` 禁弧直接过滤 join pair”的优先级需要下调。ng-DSSR 当前 join 是连续 PWLF 口径，forward label 的可行完成时间不是离散时刻集合，直接拿离散 time-indexed arc-time BitSet 去过滤 `(i,j,t)` 不自然，也可能需要额外 bucket/scale 解释。当前 join 已经使用普通 pricing-only arc 过滤，因此更现实的第一步不是强行接入离散时空弧，而是在连续函数口径下加强 pair 过滤和降低函数检查成本。

连续口径下更合适的优化有两类。第一类是 range-restricted scalar lower bound：当前 pair 下界使用 `forward.minReducedCost + backward.minReducedCost + fixedCost`，但这两个最小值可能发生在不可拼接或互不重叠的时间域。更强的做法是根据 crossing arc delay 先求 shifted forward 与 backward 的共同定义域，只在该共同域内分别取 forward/backward 的区间最小值再相加。它仍然是安全下界，因为没有强制两侧在同一时刻同时达到最小，但比全局 min 明显更紧。若要高效实现，需要为 join extension PWLF 提供区间最小值查询；可以先做按 segment 数组的 prefix/sparse/rmq 或惰性扫描缓存，避免每个 pair 都完整扫函数。

第二类是直接降低每次 `funcEval` 的常数。当前 join 每个 pair 会执行 `forwardFull.shiftX(delta)`、`shiftedForward.add(backwardFull)`、`joinCost.shiftYInPlace()` 和 `joinCost.findMinimal(false,true)`，其中 `shiftX` 和 `add` 都会构造临时 PWLF。更直接的实现是增加一个只求最小值的 `minShiftedSum(forwardFull, delta, backwardFull, fixedShift)`：用双指针扫描 `forwardFull(t-delta)` 与 `backwardFull(t)` 的重叠 segment，在每个重叠区间检查端点值，直接返回最小 reduced cost 和对应时间，不生成 `shiftedForward` 和 `joinCost`。这个优化不改变 pair 数，也不改变列语义，但能减少千万级 `funcEval` 下的对象分配和链表构造。

关于缓存 shift 函数，当前判断是：单独缓存 `forward label + delta` 的 shifted function 只在同一个 forward label 与同一个 backward first job 反复拼接时有用。由于 `delta=setup(i,j)+p_j` 只依赖 crossing arc，确实可以复用，但缓存规模可能达到 `forwardLabel * firstJob`，内存风险较大，而且后续仍要做 `add + findMinimal`。因此它不如 `minShiftedSum` 直接；若做缓存，也应先只做“本轮、本 backward first job 局部缓存”，不要做全局缓存。

best-first join 顺序的含义也需要明确：当前 forward label 已按 `minReducedCost` 排序，所以同一个 backward 下可以在 scalar LB 失败时 break。但外层 terminal/backward 的扫描顺序未必先遇到最负的列。如果先处理 group lower bound 最小的 terminal/backward 组合，可能更早发现一个很负的 candidate，从而把 `joinLowerBoundThreshold()` 收紧，后续同一轮中更多 pair 会在 scalar LB 阶段被 break。这个优化只在“本轮会找到负列且阈值策略使用 best record/best UB”时有效；如果本轮是证明无负列的 certificate，最终仍要扫完所有可能 pair，所以收益有限。
### 2026-07-05 ng-DSSR 单次 exact pricing 耗时归因

这次重新梳理 60-2 的 ng-DSSR 日志后，单次 exact pricing 慢的主因可以更明确地排到几个层次。第一大头不是 midpoint probe，也不是最终列成本 evaluator 重算，而是双向 join 之后进入 PWLF 函数级 reduced-cost 检查的 pair 太多。日志里典型一轮 exact pricing 总耗时在 `10s~43s`，其中有一轮 `33.417s` 的 pricing 里 `join pairs tried=8293076`、`funcEval=8220478`，另一轮 `32.199s` 里 `funcEval=11126762`。这说明大量 forward/backward label pair 已经通过了前面的集合、时间窗、scalar/completion-bound 便宜筛选，最后必须做函数级组合和最小值判断。这里的 `funcEval` 不是 TWETColumnEvaluator 对完整 sequence 的重算，而是 join 阶段的 PWLF 函数检查。

第二层原因是同一个 dominance key 下的真实 label 前沿过厚。40-2 诊断 run 中 `activeLabelPerNode` 的平均值约 `4.94`，8 轮合计 `funcEval=14910`，exact pricing 只用 `1.305s/8`。但 60-2 旧日志粗估同 key 活跃 label 数前 9 轮平均约 `26.35`，这些轮次合计 `funcEval=39501481`、`totalNonElementarySeen=15727865`。这说明 60-2 不是单纯 dominance node 数量多，而是同一个 reachable/dominance 状态下保留了很多互不完全支配的 PWLF label。它们都会继续扩展并进入 join，导致扩展和函数检查被放大。

第三层是 completion-bound 构造在尾部会变成次要大头。很多前期轮次的 `completionBoundInternal timingMs` 接近 0，说明复用或构造成本不高；但尾部存在一轮总耗时约 `10.988s`，其中 forward/backward completion bound 构造约 `1.682s/4.079s`，合计超过 5 秒，并且最终只返回 1 条列。也就是说尾部退化时，既要花时间构造 PWLF bound，又只能补极少数有效 elementary 列，bound 改善很慢。

midpoint probe 当前不是主瓶颈。60-2 的若干轮里 probe 一般是几百毫秒到 2 秒多，少数到 4 秒左右，占单次 exact pricing 的 5% 到 15%，个别尾部轮次可能接近 20%。它值得继续控制预算，但不能解释 20 秒、40 秒级 exact pricing。

`nonElemSeen` 的含义是本轮 relaxed/ng 路径里看到的负 reduced cost 但非 elementary 的候选数量，用来推动 DSSR 更新。60-2 中该值很大，说明很多计算都花在“发现 relaxed 负列、更新 ng-set、继续证明 elementary 负列”的过程中，最终真正返回的 elementary 列却很少。这也是 tail 长的直接表现。

因此当前结论是：60-2 上 ng-DSSR 单次 exact pricing 的主要耗时来自 label frontier 过厚后的 join/function-evaluation 爆炸，completion-bound 构造在尾部是次要大头，midpoint probe 和最终列 evaluator 重算不是主要原因。后续优化优先级应放在减少进入 `funcEval` 的 pair、压缩同 key 下的 PWLF 前沿、加强 join 前 group-level bound，或设计 traced/envelope label；继续只调强分支、ALNS 或 midpoint probe 很难解决根因。

进一步检查当前 join 实现后，优化方向可以拆成两类。第一类是减少进入 `funcEval` 的 pair。当前已有的过滤顺序为：terminal group 级普通弧/访问过滤、粗时间过滤、`minForwardReducedCostByLastJob + backward.minReducedCost + joinFixedReducedCost` 的 group scalar LB、forward label 按 `minReducedCost` 排序后的 pair scalar LB、ng/visited 集合过滤、粗时间过滤；只有这些都过了，才进入 `shiftX + add + shiftY + findMinimal`。这说明现有标量 LB 已经有基本结构，但它忽略了时间对齐，`forward.minReducedCost` 和 `backward.minReducedCost` 可能发生在完全不同的时间点，所以在 60-2 上仍会放过大量 pair。

较有价值的第一步是把 time-indexed preprocessing 留下的 `(i,j,t)` 时空禁弧接入 crossing-arc join。当前 ng-DSSR join 只检查普通 `isPricingArcForbidden(i,j)`，不会在 pair 进入 `funcEval` 前判断“forward 完成 i 的可行时间中，是否还存在一个未被 time-indexed fixing 禁掉的 i->j 时空弧，并且接上 j 后能落入 backward label 的有效域”。对于整数时间实例，这可以用 node 中已有的 time-indexed arc BitSet 做区间交集判断；若某个 pair 的所有 crossing time 都已被禁掉，就可以在函数拼接前直接剪掉。这个方向和前面发现的 repeatability 差异一致：有些回路从 hull window 看可行，但对应时空弧已全部被 time-indexed fixing 删除。

第二个可做的是 range-restricted scalar LB。当前 pair LB 用的是两个 label 的全局最小值相加；更强的下界是先求 crossing 后两侧函数真正重叠的时间区间，然后只在这个重叠区间上取 forward 和 backward 的各自最小值再相加。它仍然是安全下界，因为两个最小值可以发生在不同时间点，但比全局 min 更接近真实 join。若为 join extension 函数维护一个按 segment 数组化的区间最小查询，这个判断可以做到比完整 `add + findMinimal` 便宜很多。该方案对 no-column certificate 也安全，只是剪枝强度取决于函数最小值是否经常落在重叠域外。

第三个方向是 terminal/group envelope prefilter。对同一 terminal job 下的 forward labels 和 backward labels，可以先构造或复用 group-level lower envelope；若 group envelope 与对侧 label/envelope 拼接后的最小值都不可能低于当前阈值，则整组真实 label pair 可以跳过。这个判断比当前 scalar min 强，因为它部分考虑了时间形状；同时它不改变最终列来源，只是 join 前过滤，所以正确性风险比 traced envelope label 小。缺点是如果 envelope 本身 segment 很多，构造和拼接也会有成本，因此应先统计 group envelope segment 数和命中率。

第二类是降低每次 `funcEval` 的单位成本。当前每个 pair 会对 cached 的 forward join extension 调 `shiftX(delta)`，这会复制整条 PWLF；随后 `add(backwardFull)` 再构造一条新 PWLF；然后 `shiftYInPlace()` 和 `findMinimal()` 扫描新函数。由于 joinCost 是每个 pair 新建对象，`findMinimal` 的缓存基本帮不上忙。更直接的优化是实现一个只返回最小值的 `minShiftedSum(f, delta, g, yShift)`：用双指针扫描 `f(t-delta)` 和 `g(t)` 的重叠 segment，在每个重叠区间只检查端点最小值，不再生成 shiftedForward 和 joinCost 两个临时函数。这个可以保持和 `add + findMinimal(false,true)` 一样的端点语义，但显著减少对象分配和链表构造。它不减少 pair 数，但在千万级 `funcEval` 下可能直接降低 join 阶段常数。

best-first join 顺序也有一定价值。当前外层主要按 terminal job 顺序扫描，虽然 forward labels 已按 `minReducedCost` 排序并支持 `break`，但如果最强负列出现得晚，`joinLowerBoundThreshold()` 很晚才被 best reduced cost 收紧。可以把 terminal group 或 backward label 按 group LB / optimistic LB 先处理，尽早找到更负的 record，再用 `BEST_UB/BEST_RECORD` 阈值剪掉后续 pair。这个优化只对“本轮最终会找到负列”的 pricing 有明显帮助；若本轮需要证明无负列，它仍然必须扫完整空间。

相对不优先的方向包括：缓存每个 forward label 对每个 firstJob 的 shifted function，这会产生很大的 `label * job` 内存；只调 midpoint probe，因为 probe 不是主瓶颈；以及继续扩大返回列数，它可能减少外层轮数，但会放松阈值、增加 join 扫描，不一定改善 tail。若后续实现，建议顺序为：先加时空弧 join 前过滤和统计命中率；再做直接 `minShiftedSum` 替代 `shiftX+add+findMinimal`；然后评估 range-min / group-envelope prefilter；最后再考虑 traced envelope label 这种重构级方案。

### 2026-07-05 join 区间下界扫描版与直接最小值计算

本次按“先做扫描版加统计”的口径实现了两个低风险改动。第一，增加 `minShiftedSum(f, delta, g, yShift)`，在 join 阶段直接用双指针扫描 `f(t-delta)` 和 `g(t)` 的重叠分段，返回最小 reduced cost，不再为每个 pair 构造 `shiftedForward` 和 `joinCost` 两条临时 PWLF。这个改动不改变列语义、dominance 或返回列逻辑，只降低每次 `funcEval` 的对象构造和链表扫描常数。第二，增加 `joinRangeLB` 诊断开关，在真正做函数最小值前，先在 shifted forward 与 backward 的共同定义域上分别取区间最小值，相加形成一个更强的 scalar lower bound；若该下界已经不可能低于当前阈值，则跳过最终函数检查。

实测结果说明区间下界扫描版暂时不适合作为默认主线。在 `wet040_001_2m`、ng-DSSR、`nearestK3/top10`、关闭启发式 pricing、关闭 time-indexed root preprocessing、root-only、300s 限制的诊断口径下，打开 `joinRangeLB=true` 的 run 为 `tmp-funceval-range-on-20260705`，最终 `TIME_LIMIT`，总时间 `310.625s`，exact `309.825s/23`。日志中多轮 `joinRangeLB check/pruned` 的剪枝数均为 0，例如大轮次出现 `38997188/0`、`91040923/0`。关闭 `joinRangeLB=false` 的对照 `tmp-funceval-range-off-20260705` 为 `TIME_LIMIT`，总时间 `303.655s`，exact `302.775s/24`。由于两组都在 time limit 下停止，不能把总时间差当成严格速度比，但“检查千万级 pair、剪枝为 0”已经足以说明当前扫描版 range LB 强度不足，默认开启只会增加额外区间扫描。

因此当前结论是：直接 `minShiftedSum` 保留在主线，因为它是等价替换，减少临时 PWLF 构造；`joinRangeLB` 保留为显式诊断开关，默认关闭。若后续还要继续做区间下界，应该先做更便宜的区间最小值索引或 group envelope 统计，而不是在每个 pair 上直接扫描两条函数。

当前需要澄清一点：代码里还没有实现真正的 group best-first 排序。所谓 group 在当前 join 中指一个 forward terminal job `lastJob` 下的 active forward label 列表，与一个 backward label 的 first job 进行 crossing-arc 拼接；不是一个 dominance node 上的所有 label。现有顺序仍基本是 `lastJob` 升序、backward first job 升序、backward label 当前列表顺序。已经存在的是 group 级剪枝：普通 arc / ng memory 过滤、粗时间过滤、`minForwardReducedCostByLastJob[lastJob] + backward.minReducedCost + fixedCost` 的 group scalar LB；进入组内后，forward labels 按 `minReducedCost` 排序，因此 pair scalar LB 失败时可以 `break`。这不是 group 排序，只是组内早停。

下一步较现实的优化优先级可以这样排。第一，保留已经实现的 `minShiftedSum`，继续观察它对 60-2 这类千万级 `funcEval` 的常数收益。第二，如果要减少 pair 数，优先尝试 integer 实例下的 time-indexed arc-time 过滤：当 node 已有 `(i,j,t)` 时空禁弧证据时，在 crossing arc 进入 PWLF 函数检查前判断 forward 可行完成时间与 backward 可接入时间之间是否还存在未禁掉的时空弧；若不存在，直接剪掉该 pair。这比 range LB 更贴近之前 repeatability 诊断中发现的差异，但只适合整数时间且已有 time-indexed 证据的节点。第三，可以实现真正的 group best-first：先按 group LB 或 optimistic LB 对 `(lastJob, backward)` 组合排序，让更可能产生强负列的组合先更新 `bestGeneratedReducedCost`，从而加强后续 `joinLowerBoundThreshold()`。它对有负列的中前期轮次可能有帮助，对最终无负列证书轮次帮助有限。第四，再考虑 group envelope prefilter 或 traced envelope label；前者是中等改动，后者才是解决同 dominance key 多 label 膨胀的重构级方向。

进一步检查 `funcEval` 细节后，需要修正一个直觉：当前 ng-DSSR / partial dominance 主线的 join 里已经没有 `add`。`add` 的主要残留在 label 单侧扩展、completion-bound 构造和旧 legacy pricing 路径；join `funcEval` 已经改为直接 `minShiftedSum`。因此 `funcEval` 内部的细节优化空间主要不是继续优化 `add`，而是减少每次函数最小值扫描的常数和减少进入函数扫描的 pair。本次又把主线调用改为 `findMinimalShiftedSumValue`，避免每个 `funcEval` 返回一个 `double[]` 小数组。这个是纯常数优化：千万级调用下能减少对象分配和 GC 压力，但不改变 reduced-cost 值、候选列集合或 dominance 语义。

### 2026-07-05 exact pricing 全局复查后的优化优先级

这次在 `findMinimalShiftedSumValue` 小数组优化之后重新过了一遍主线，当前判断是：继续在 join 的单次 `funcEval` 内部抠常数，收益会越来越小。2,000,000 次、每条函数约 20 个 segment 的微基准里，旧的 `shiftX + add + findMinimal` 约 `2.200s`，直接最小值扫描约 `0.938s`，约 `2.35x`。这说明等价替换确实有效，但它只降低单次函数检查常数，不减少进入函数检查的 pair 数；如果一个 node 仍有千万级 `funcEval`，总时间仍会很重。

当前还能做的低风险局部优化主要在 completion-bound 相关路径，而不是 join 本身。`GCNGBBStyleBidirectionalNgDssr`、`GCNGBBStyleBidirectionalPartialDominance` 和 `CompletionBoundSubtreeArcEliminator` 里仍有几处只需要判断“prefix/suffix 拼接后的最小值是否低于 cutoff”，但实现上仍构造 `shiftX`、`add` 后再 `findMinimal`。这些地方理论上可以复用 `findMinimalShiftedSumValue`：arc fixing 用 `prefix + delay + suffix + fixedReducedCost`，forward/backward completion pruning 用 `frontier + suffix` 或 `prefix + frontier` 的 `delta=0` 形式。正确性前提是这些调用只消费最小值，不需要保留完整 PWLF 用于后续传播；从当前代码看这些位置满足这一点。这个改动预计只能降低 completion-bound fallback/arc-fixing 的常数，不会解决 label frontier 过厚导致的 join pair 爆炸，因此应先用 focused benchmark 或日志里的 `completionBoundFunctionEvaluations`、`completionBoundArcFixingFunctionEvaluations` 验证收益。

不能这样替换的是 `CompletionBoundCalculator` 内部的 bound 构造。那里 `shiftX/add/mergeMinimum/normalize` 的结果会作为完整 PWLF 继续向前或向后传播，不能只求一个最小值。把这些地方强行替换成 scalar min 会破坏 completion bound 的函数形状，属于错误优化。

更大的效率瓶颈仍然是 pair 数和同 key 多 label。当前 dominance graph 会维护 `labelEnvelope` 用于支配判断，但真实 label 仍要保留并参与扩展、join 和列恢复；这保证正确性，但在 60-2 这类同一 dominance key 下平均二十多个 live label 的场景里，会显著放大扩展和 join。真正减少量级的方向仍是 time-indexed arc-time join 前过滤、group envelope prefilter、或带 provenance 的 traced/envelope label。前两者可以作为过滤器先做统计，后者是重构级方案，涉及列恢复和 SRI/partial 状态兼容，不能作为当前小修。

启发式 pricing 当前已经用 node compact window 缩小搜索，并且只在 dual window 下强制 true-cost recheck；compact window 下跳过 true-cost recheck 是之前明确的实验口径。这个设计能减少启发式候选成本，但如果后续要写成严格证明口径，需要单独对拍 compact-window 成本与原始 objective 成本差异。它不是这次 exact pricing 慢的主因。

强分支和 RMP seed 处理这次未发现新的明显冗余。`resetRestrictedColumnsByCurrentReducedCost` 对内部列和外包列都先保留当前正值列，再按 reduced cost 选非正值列；prepared strong child 正式出队时跳过重复 repair/筛列。继续优化强分支的空间主要是减少 phase-1 trial 次数或并行 trial，不是当前 exact pricing 的核心瓶颈。

随后按上述低风险口径完成了一版 completion-bound min-only 替换。`GCNGBBStyleBidirectionalNgDssr`、`GCNGBBStyleBidirectionalPartialDominance` 和 `CompletionBoundSubtreeArcEliminator` 中，arc fixing fallback 以及 forward/backward label completion pruning 原先会构造临时 `shiftX/add` 函数再 `findMinimal`；现在改为先判断拼接定义域是否非空，再用 `PiecewiseLinearFunction.findMinimalShiftedSumValue` 直接求最小值。空定义域仍保持原来的外层语义：arc fixing 记为 domain-pruned，label pruning 返回不剪。这样避免把空拼接误当作 BigM lower bound。 focused `javac` 通过，并用 20,000 组随机 PWLF 对拍确认直接最小值与旧 `shiftX + add + shiftY + findMinimal` 在非空交集下数值一致。
### 2026-07-05 completion-bound min-only 替换的正确性复查

复查时发现两个旧路径不能混在一起。arc fixing 和 join 原来走的是 `shiftX(delta) + add + shiftY + findMinimal`，其中 `shiftX(delta)` 会在平移后按原函数的 `domainStart/domainEnd` 调用 `trimToDomain()`，所以 `findMinimalShiftedSumValue()` 必须复刻这个裁剪。label completion pruning 原来走的是纯 `frontier.add(suffix)`，并没有调用 `shiftX(0)`，因此不能把它也强行套入 shifted-domain 裁剪口径。当前代码已拆成两个 helper：带 shift 的位置继续用 `findMinimalShiftedSumValue()`，纯 add 的 completion pruning 改用 `findMinimalSumValue()`。ng-DSSR、partial dominance 和 subtree arc eliminator 里的 join overlap / arc time-disjoint 判断也已按带 shift 的旧口径对齐。

验证方式是临时构造随机 PWLF 对拍，并故意让物理 segment 区间和 `domainStart/domainEnd` 不完全一致。带 shift 路径用旧 `shiftX + add + shiftY + findMinimal` 对比 `findMinimalShiftedSumValue`，50,000 组通过，其中 31,078 组为空交集；纯 add 路径用旧 `add + shiftY + findMinimal` 对比 `findMinimalSumValue`，50,000 组通过，其中 12,089 组为空交集。随后 focused `javac` 也通过。因此当前这版 min-only 替换只改变计算方式，不改变 completion-bound arc fixing、label completion pruning 或 join reduced-cost 判断的语义。需要注意的是，这个等价性只适用于“只消费最小值”的位置；`CompletionBoundCalculator` 内部仍然要保留完整 PWLF 传播，不能替换成 scalar min。
### 2026-07-06 time-indexed 作为 ng-DSSR 前置启发式 pricing 的方案

当前进一步考虑一种折中路线：在数据为整数时间实例时，不把 time-indexed graph pricing 作为完整 exact pricing 替换 ng-DSSR，而是在内部列生成顺序中把它放到 `HeuristicPricingEngine` 之前，作为一个更快的前置启发式 pricing。直观目标是先用 time-expanded DAG 很快找到一批负 reduced-cost 的基本列加入 RMP，改善 dual 和初始列集，然后再进入原有 tabu heuristic 和 ng-DSSR exact pricing。这样它不承担节点闭合证书，最终仍由 ng-DSSR true-dual exact pricing 证明无负基本列。

实现上不应复用 `useTimeIndexedGraphPricing`，因为该开关当前语义是“用 time-indexed pricing 替换 exact pricing”。更合适的是新增独立开关，例如 `enableTimeIndexedPreHeuristicPricing`，只在 `!useTimeIndexedGraphPricing` 且 `data.isExactIntegerTimeInstance()` 且当前走 ng-DSSR 内部列主线时，在 `TWETBPCContext` 中把一个 pre-heuristic 口径的 `TimeIndexedGraphPricingEngine` 插入到 `HeuristicPricingEngine` 前面。该 engine 每次最多返回 `K1` 条列，建议单独设置 `timeIndexedPreHeuristicColumnLimit`，不要直接占用 `maxExactPricingColumns`。返回列应默认过滤为 elementary/basic sequence，即序列内 job 不重复；否则会把 pseudo-schedule 列引入当前 elementary ng-DSSR 主线，虽然 RMP  technically 支持 visit count，但这会改变实验口径。

需要注意，当前 `TimeIndexedGraphPricingEngine` 不是严格 k-shortest path。它是在 DAG 上每个 `(lastJob,t)` 状态只保留一条最短 predecessor，然后从负 end state 中按 reduced cost 维护 top candidate sequence。因此它可以快速返回多条负列，但不是完整的 k-shortest path 枚举。如果后续发现返回列过少，可以再考虑每个状态保留多个 label 的 K-state 版本；第一版不建议做，避免把一个启发式入口做重。

关于列成本和验证：全局 `Pool.addOrImproveColumn()` 已经按 `SequenceSignature` 去重，并在同一 sequence 出现更低 cost 时原地更新，所以重复 sequence 本身不会污染 Pool。time-indexed 在同一次 pricing 内也会按 signature 保留 reduced cost 更低的候选；对同一 sequence 来说，dual 项固定，因此 reduced cost 更低等价于该离散图窗口内 objective cost 更低。若图窗口使用的是原始硬时间窗且实例时间为整数，则该 cost 可以视为该 sequence 在整数完工时间口径下的最好版本，通常不需要再用 `TWETColumnEvaluator` 重刷。若使用了 dual profitable window 或 node compact window，则它得到的是受限窗口内的最好版本，可能高于原始全域最优 sequence cost。此时作为启发式列仍然安全，因为高成本列不会破坏下界正确性，且后续 Pool 可被更低成本版本改进；但为了避免在 stabilized dual / SRI cut / 复杂窗口口径下误过滤或加入太多非负列，第一版应只在 true-dual、no-active-SRI 或使用 SRI-aware time-indexed pricing 的条件下启用，必要时由 PC 按当前 true dual 重新计算 reduced cost 再决定是否接收。

当前建议的第一版口径为：整数时间实例；ng-DSSR 主线；无 active SRI cut 时使用 no-cut `TimeIndexedGraphPricingEngine`；有 active SRI cut 时先跳过，或者后续接 `TimeIndexedGraphRank1CutPricingEngine` 的 heuristic-only 版本；只返回 elementary candidate；每轮最多返回 K1 条；不提供 dual-bound certificate；返回空列后继续走原有 HeuristicPricingEngine 和 ng-DSSR exact pricing。这样它只是一个“更快的找列器”，不改变最终最优性证明链条。

进一步修正参数和成本口径。这个功能不能复用 `useTimeIndexedGraphPricing`，因为该开关语义是“用 time-indexed 替换 exact pricing”。应新增独立的 `enableTimeIndexedPreHeuristicPricing` 一类开关，并把配置按功能分组：time-indexed 替代 exact、time-indexed 前置启发式、time-indexed root preprocessing/compact window，以及 ng-DSSR 自身的 ng-set/DSSR 更新/历史 warm-start。这样后续实验日志能直接看出当前到底是在跑 time-indexed 主线，还是在 ng-DSSR 前面多跑一个快速候选列入口。

当前 no-cut time-indexed engine 返回多列的方式不是完整 k-shortest path。它按时间展开 DAG 扫描所有有限 `(lastJob,t)` 状态，每个状态只保存一条最短 predecessor；随后从负 reduced-cost end state 中恢复 sequence，并按 `SequenceSignature` 保留最好的候选。也就是说它是“每个时空状态一条最短路径 + end state top 候选”，不是每个状态保留 K 条路径。第一版前置启发式沿用这个轻量方式即可，避免把一个找列入口做重。

前置启发式应使用当前有效窗口：基础 hard window、node 继承的 compact window，以及可用时的 dual profitable window。compact window 是当前子树继承的硬时间窗证据；dual window 是当前 dual 下的临时窗口，只能用于搜索加速。当前 `TimeIndexedGraphPricingEngine` 会用图上 reduced cost 反推 objective cost；作为前置启发式时，若本轮使用了 dual window，则只对最终选中的 elementary top-K 候选调用 `TWETColumnEvaluator` 做 true objective / true reduced-cost 重算，不能对所有 negative state 重算，也不能把 dual-window 受限成本直接当成永久列成本。若只使用 compact window，则保持窗口口径列成本，不做额外 evaluator 回刷。若 active SRI cut 存在，第一版直接跳过该前置入口，避免在 no-cut shortest path 里混入不完整的 SRI reduced-cost 语义。

随后按上述口径实现并再次收敛参数。该入口只保留两个显式配置：`enableTimeIndexedPreHeuristicPricing` 控制是否启用，`timeIndexedPreHeuristicColumnLimit` 控制每轮最多返回多少条 elementary 负列；dual window、compact window 和 dual-window 回刷都作为固定语义，不再暴露成实验开关。`TWETBPCContext` 只在 `!useTimeIndexedGraphPricing`、整数时间实例、ng-DSSR 主线下，把 `TimeIndexedPreHeuristicPricing` 插到 `HeuristicPricingEngine` 前。该入口仍使用当前 no-cut time-indexed DAG 的“每个时空状态一条最短 predecessor”候选机制，但在恢复 sequence 后丢弃重复 job，只返回 elementary/basic 候选。若本轮使用了 dual window，则最终 top-K 候选调用 `TWETColumnEvaluator` 回刷真实 objective，并用当前 true dual 重新计算 reduced cost；若回刷后不再为负，则不返回该列。现有 `useTimeIndexedGraphPricing` 主线保持原语义不变：仍作为 exact pricing 替代入口，不强制回刷。
### 2026-07-06 time-indexed 前置启发式的内部列族证书

这次进一步调整 time-indexed pre-heuristic 的定位：它仍然首先是一个快速找列器，每轮最多返回 `timeIndexedPreHeuristicColumnLimit` 条 elementary/basic 内部列，当前默认值为 300。这里的“basic”指序列内 job 不重复，不是 CPLEX basis。候选仍来自 time-expanded DAG 的负 reduced-cost end state，并按 `SequenceSignature` 去重保留较好版本。

如果该 DAG 扫描后没有返回 elementary 负列，同时图上最好的 relaxed end state reduced cost 也已经不小于 0，则它不仅是“没找到列”，而是证明在当前 time-indexed 窗口和当前 dual 口径下内部机器列族没有负 reduced-cost 列。这个证书现在会通过 `PricingResult.certifiedInternalReducedCost` 传给 `PC`。`PC` 收到非负内部列族证书后，会跳过后续内部 pricing engine，包括普通 heuristic 和 ng-DSSR；如果当前是列化外包模式，则仍然会补跑外包列 pricing，避免只证明内部列族而漏掉外包列族。也就是说，该证书只闭合内部机器列，不直接闭合整个 node。

该处理和 dual-bound pruning 不是同一个概念。dual-bound pruning 需要内部列和外包列的完整 reduced-cost 证书并结合当前 RMP 目标计算节点下界；这里新增的是 pricing 控制流优化：某个前置 engine 已经证明内部列族无负列时，不再浪费时间跑更重的内部定价器。稳定化 pass 中也只对当前 pass 生效；如果外层还需要 true-dual pass，仍由原流程继续保证最终正确性。

同时补了一个非整数时间实例下的 completion-bound pre-certificate。该入口只在 completion bound 已构建、实例不是精确整数时间、没有 active SRI cut 时启用。它使用现有 completion-bound 语义中的 forward prefix 下界做闭合检查：对每个 job j 取已缓存的 `min_t F_j(t)`，再加上直接闭合到 sink 的 reduced-cost 项 `-arcDual(j,sink)`；若所有可用 sink 收尾弧上的该值都不小于 0，则 ng-DSSR 本轮直接返回空列并携带非负内部列证书。该功能主要用于非整数连续时间口径；整数时间下优先依赖 time-indexed pre-heuristic 的图证书。
### 2026-07-07 40-2 normal ng-DSSR 好配置复跑

本次重新复核 `wet040_001_2m` 的 normal ng-DSSR 配置时，先排除了两个容易误用的口径。第一，`GCBBFullDomainComparisonTest` 的 `mode=full` 会优先进入 `GCBBStyleBidirectionalFullDomainPricingEngine`，即使同时设置 `ngDssr=true` 也不会进入 ng-DSSR；真正 ng-DSSR 应使用 `mode=normal` 或 `halfDomain`。第二，completion bound 的系统属性名是 `twet.bpc.fullDomainCompare.completionBound`，不是旧口径的 `bidirectionalCompletionBound`；subtree pricing-only 也需要显式设置 `completionBoundSubtreeArcEliminationPricingOnly=true`。

按当前确认的快配置复跑：`mode=normal`、`ngDssr=true`、`nearestK3/top3`、ALNS 30s、SA 关闭、accepted history 初始列、启发式 pricing、RMIH、dual-bound pruning、`completionBound=allCycles`、completion-bound arc fixing、subtree pricing-only、midpoint probe、`joinBestMode=bestUB`、关闭 strong branching、route enumeration、time-indexed graph pricing、time-indexed pre-heuristic 和 time-indexed root preprocessing。结果为 `FINISHED, obj=bound=22580, solve=392.265s, root=55.977s, nodes=139, pool=311255, exact=128.639s/769, heuristic=134.445s/3123, masterLP=52.117s, valid=true`，结果目录为 `test-results/bpc/tmp-ngdssr-normal-k3top3-bestUB-alns30-40-2-20260707-2228`。

该结果说明当前代码下 root 仍是正常量级，root summary 为 `nodeTime=40.092s, lpObj=22490, inc=22582, exact=11.333s/15, heuristic=26.745s/61, subtree fixed=1199/1560`；慢点主要来自后续搜索树没有像历史 `121.924s/nodes=45/pool=58052` 那样快速闭合。本次在 node 23 找到整数最优值 22580，随后继续处理到 139 个节点才完全闭合，最终列池增至 31 万。当前判断是：配置已经对齐到真正 ng-DSSR 口径，但搜索树和列池轨迹与历史快记录明显不同，需要后续再对比分支路径、dual-bound 剪枝和近期分支/repair 正确性修复是否改变了树。

进一步澄清“当前好配置”的 time-indexed 开关口径。`timeIndexedRootPreprocessingForNgDssr=true` 表示正式 ng-DSSR 前先跑一次 time-indexed root 预处理，复制 pricing-only arc、compact window 和最多 200 条 elementary seed。`timeIndexedPreHeuristicPricing=true` 表示每轮内部列 pricing 前先用 time-indexed DAG 快速找 elementary 负列。`timeIndexedCompletionBoundScalar=true`、`timeIndexedCompletionBoundWindow=true`、`timeIndexedCompletionBoundArcFixing=true` 表示在 ng-DSSR 中启用 time-indexed scalar/window 辅助，并允许 node/cut-loop 层面的 time-indexed arc/window 加强；这不等于每次 exact pricing 内部都额外做临时 zero-reduced-cost fixing。真正控制每轮 exact pricing 内部临时 fixing 的是 `timeIndexedCompletionBoundInRoundArcFixing`，当前好配置应设为 `false`，避免每次 pricing 都额外跑 time-indexed 临时 arc fixing。

因此当前用于“组件全开但不做每轮 time-indexed 临时 fixing”的口径为：`mode=normal`、`ngDssr=true`、`nearestK3`、`ngDssrRouteUpdateLimit=3`、ALNS/RMIH/启发式 pricing、`completionBound=allCycles`、completion-bound scalar/arc fixing/subtree pricing-only、midpoint probe、dual-bound pruning、strong branching、time-indexed root preprocessing、time-indexed pre-heuristic、root preprocessing seed 200、repeatability ng-set filter 均打开；`timeIndexedCompletionBoundInRoundArcFixing=false`，SRI/partial/route enumeration/dual stabilization 仍按普通 no-SRI 主线关闭。下一轮按用户要求先保持 `timeIndexedPreHeuristicInStrongBranchingPhase2=true`，即 strong branching phase2 也允许使用 time-indexed pre-heuristic。

按上述口径重新运行 `wet040_001_2m`，目录为 `test-results/bpc/tmp-wet040-001-ng-goodcfg-strong-tiroot-tipre-inroundoff-20260707`。结果为 `FINISHED, obj=bound=22580, solve=139.843s, root=57.124s, nodes=17, pool=114938, pricing=3068, exact=6.940s/85, heuristic=12.244s/267, masterLP=44.064s, valid=true`。本次 run 里 `TimeIndexedGraphPricing=7.115s/337`，对应 root preprocessing；正式 pricing 链包含 `TimeIndexedPreHeuristicPricing -> HeuristicPricing -> GCNGBBStyleNgDssrPricing`，并且 strong branching phase2 中也确实调用了 `TimeIndexedPreHeuristicPricing[strongBranching]`，该项耗时 `1.745s/1131`。主要剩余耗时不在 ng-DSSR exact，而在 master LP 与 strong branching 相关流程：`after_pricing=25.972s/600`、`strong_branching_light_repair_rmp=11.413s/348`、`strong_branching_phase2_initial=2.315s/69`、`strong_branching_after_heuristic=3.023s/1062`，此外 `HeuristicPricing[strongBranching]=34.908s/842`。因此该口径已经明显快于此前 360s/507s 的错误或压力测试口径，但在 40-2 上仍不一定优于历史 no-strong `121.924s`，主要差别来自 strong branching 和大量 heuristic trial 的成本。

### 2026-07-07 50-3 生成与 time-indexed / ng-DSSR 对照

本次按当前 `ETConverter.convertFile()` 逻辑从 `data/50-1/wet050_003.dat` 生成 `data/50-3/wet050_003_3m.dat`。该文件头为 `50 3`，即 50 个任务、3 台机器；due date 使用当前 ET 转换逻辑按 3 倍缩放，setup 由 3 台机器口径重新生成并做闭包。此前一次带 `outputDir` 的 PowerShell 启动因为 JVM 参数被拆错，实际没有进入求解，后续结果均使用 runner 默认输出目录。

pure time-indexed 口径使用 `timeIndexedGraphPricing=true`，关闭 ng-DSSR、旧启发式 pricing、rank-1 cut、root preprocessing 和 pre-heuristic。结果目录为 `test-results/bpc/tmp-wet050-003-3m-timeindexed-pure-20260707b`，结果为 `FINISHED, obj=bound=26527, solve=182.204s, root=109.917s, nodes=12, pool=77628, exact=17.184s/495, masterLP=76.059s, valid=true`。其中 time-indexed 定价本身仍然很快，主要时间在 master LP 和 strong branching 相关 LP。

当前 ng-DSSR 好配置口径使用 `mode=normal`、`nearestK3/top3`、ALNS 60s 且 SA 关闭、RMIH、启发式 pricing、allCycles completion bound、subtree pricing-only、midpoint、dual-bound pruning、strong branching、time-indexed root preprocessing、time-indexed pre-heuristic、root seed 200、repeatability filter，并关闭每轮 exact pricing 内部的 time-indexed 临时 fixing。结果目录为 `test-results/bpc/tmp-wet050-003-3m-ng-goodcfg-20260707b`，结果为 `FINISHED, obj=bound=26527, solve=381.350s, root=121.431s, nodes=10, pool=67368, exact=16.728s/44, heuristic=40.275s/122, masterLP=47.050s, valid=true`。

这次对比的主要结论是：50-3 上 ng-DSSR exact 本身并不是瓶颈，`GCNGBBStyleNgDssrPricing=16.728s/44` 和 pure time-indexed 的 `17.184s/495` 量级接近。ng-DSSR 总时间更长，主要来自 strong branching phase2 中的启发式 trial：`HeuristicPricing[strongBranching]=153.195s/529`，再加上额外的 pre-heuristic/repair/LP 流程成本。因此本算例上 time-indexed 仍然更快；ng-DSSR 的节点数略少、列池略小，但没有抵消 strong branching 与启发式 trial 的成本。

进一步拆解 `HeuristicPricing[strongBranching]` 后确认，它主要是 strong branching phase2 的 Tabu heuristic，而不是 phase1 repair。phase1 更接近 `strong_branching_light_repair_rmp=13.002s/228`、`HeuristicPricing[FindFeasible]=0.943s/4` 和 `GCNGBBStyleNgDssrPricing[FindFeasible]=6.594s/4`。phase2 中 `TimeIndexedPreHeuristicPricing[strongBranching]` 已开启，但只耗时 `5.722s/688`，且加列 `1613` 条；真正重的是 `HeuristicPricing[strongBranching]`，按节点聚合为 node1 `28.344s/76 calls/add17659`、node2 `30.381s/85/add6659`、node3 `29.696s/84/add11178`、node4 `29.691s/113/add7623`、node5 `32.319s/87/add6247`、node6 `2.763s/84/add4230`。这说明慢因更偏向 phase2 无限 pass 与 Tabu 参数偏重，而不是 pre-heuristic 或 ng-DSSR exact 本身。

### 2026-07-08 旧 VRP 启发式参数与 strong branching phase2 对照

旧 VRP 代码中 tabu column generation 的默认参数为 `m_tabu_cg_iteration_number=50`、`m_tabu_cg_tenure=30`、`m_tabu_cg_size=30`，同时 `m_gen_size=1000`、`addin_size=150`、`min_addin_size=30`、`m_branch_Iter=300`、`m_branch_col_number=500`、`m_col_coef=0.08`、`m_initial_col_number=1000`。在 `BPCTest` 的特殊实例设置里，`rc203/rc204/rc207` 会把 `addin_size` 设为 200、tabu iteration 按 `50*scale/25` 调整、tabu seed size 设为 50；`r203` 类似，但 iteration 为 `max(50,40*scale/25)`；`c*` 则仍是 iteration 50、size 30。因此当前 TWET `HeuristicPricingEngine` 的默认 `iterations=50`、`tenure=30`、`seed size=30` 与旧 VRP 默认是一致的。差别不在单次 tabu 参数，而在当前 two-stage strong branching 的 phase2 会对多个候选 child 反复运行 Tabu heuristic，这在旧 VRP 默认流程里不是同一种调用频率。

代码口径上，纯 no-cut time-indexed pricing 已经不做 phase2：`Tree` 在 `useTimeIndexedGraphPricing=true` 且 `useTimeIndexedGraphRank1CutPricing=false` 时直接返回 phase1 排名第一的候选。ng-DSSR 则默认 `strongBranchingPhase2CandidateLimit=4`，会对 phase1 选出的前 4 个候选继续做 phase2 heuristic pricing 评估。为了判断 phase2 是否值得，在 50-3 同一好配置下只把 `strongBranchingPhase2CandidateLimit` 从 4 改为 0，其他配置保持：normal ng-DSSR、nearestK3/top3、ALNS 60s 且 SA 关闭、RMIH、启发式 pricing、allCycles completion bound、subtree pricing-only、midpoint、dual-bound pruning、strong branching、time-indexed root preprocessing、time-indexed pre-heuristic、root seed 200、repeatability filter、关闭 in-round time-indexed fixing。

phase2=0 的结果目录为 `test-results/bpc/tmp-wet050-003-3m-ng-goodcfg-phase1only-20260708`，结果为 `FINISHED,obj=bound=26527,solve=352.319s,root=156.572s,nodes=14,pricingRounds=967,addedColumns=99973,exact=33.172s/58,heuristic=124.906s/270,valid=true`。对比 phase2=4 的旧结果 `solve=381.350s,root=121.431s,nodes=10,exact=16.728s/44,heuristic=40.275s/122,HeuristicPricing[strongBranching]=153.195s/529`，可以看到 phase2 确实改善了分支质量，节点数从 14 降到 10，正式 exact/heuristic pricing 也更少；但 phase2 自己的 Tabu trial 过重，抵消了这部分收益，导致总时间反而比 phase1-only 慢约 29 秒。

当前结论是：在 50-3 这类配置下，phase2 不是数学上无用，它确实选出了更好的分支路径；问题是二阶段使用无限 pass 的 Tabu heuristic 成本过高。若后续继续优化 strong branching，优先方向不是完全否定 phase2，而是限制 phase2 的 heuristic pass、只保留 time-indexed pre-heuristic 或更轻的 trial 评估，或者按实例规模/列池规模自适应关闭 phase2。当前 pure time-indexed no-cut 已经 phase1-only，不需要额外改。
### 2026-07-08 HeuristicPricingEngine 热点复查与低风险优化

在 phase2=0 的 50-3 对照中，`TimeIndexedPreHeuristicPricing=3.948s/317`，并不是瓶颈；正式求解里更重的是 `HeuristicPricing=124.906s/270`。逐项检查 `HeuristicPricingEngine` 后，确认当前流程仍是从当前 RMP 选 reduced-cost seed，然后每条 seed 做 50 轮 remove/add/exchange Tabu 搜索。每轮 add/exchange 会枚举所有未用 job 和插入/替换位置，真正重成本在 `Solution.merge3Segments()` 的 PWLF 拼接；remove 走 `merge2Segments()`。前面已经有兼容性过滤、compact/dual window 上下文和默认跳过 true-cost recheck，因此当前剩余优化空间主要是减少无效 move 进入 PWLF 拼接，以及降低两段拼接的临时对象成本。

本次只做低风险改动。第一，`TabuMove.invalid()` 改为返回单例，避免大量不可行候选反复分配无效对象。第二，`Solution.merge2Segments()` 改为使用 `PiecewiseLinearFunction.findMinimalShiftedSumValue()` 直接双指针扫描两个函数的重叠段，替代原来的 `shiftX + add + findMinimal + release`，语义仍是求同一个 shifted-sum 的最小值。第三，启发式 remove/add/exchange 在调用 `merge2Segments/merge3Segments` 之前先做便宜的函数定义域交集判断：若 shifted prefix/suffix 与对应单任务 profile 没有重叠，直接返回不可行，不再构造临时 PWLF。

没有改 `merge3Segments()` 的核心公式。原因是三段拼接不仅需要最小值，还需要 `s_h2/s_h3` 两个 argmin 决定进入哪种情形；直接替换成只返回最小值的 helper 容易改变语义。后续如果继续优化，应先给 `merge3Segments()` 增加“返回最小值和 argmin”的无临时函数版本，并做专门对拍，而不是直接删掉现有逻辑。

验证上，focused `javac` 已通过：`javac -encoding UTF-8 -cp target/classes;... -d target/classes src/HEU/Solution.java src/TWETBPC/GC/HeuristicPricingEngine.java`。同时运行 `HEU.OutsourcingMoveConsistencyTest`，结果为 `passed, checked=14168`，覆盖了大量 insert/exchange 快速评价场景，未发现 `merge2` 快路径改变 move 成本口径。

随后用临时 benchmark 做了高频拼接测试：构造 256 组、每组约 20 个 segment 的 PWLF，对 `merge2` 旧路径 `shiftX + add + findMinimal` 和新路径 `findMinimalShiftedSumValue` 各执行 200 万次。正确性对拍最大误差为 `9.09e-13`；耗时从 `1.384s` 降到 `0.630s`，约 `2.20x`。这说明两段拼接的局部常数收益明确，但整体求解收益仍取决于启发式中 `merge2/merge3` 的实际调用占比。

`merge3Segments()` 暂时不直接改。它不仅需要最小值，还依赖 `merge12/merge23` 的 argmin `s_h2/s_h3` 判断两种情形，并且当前实现会在第二种情形里临时 `f2.resetDomain(0, data.CmaxH)`。因此不能简单替换成只返回最小值的扫描 helper。若后续继续优化，应先做一个 side-effect-free 的三段拼接版本，至少返回最小值和对应时间点，并专门对拍 `merge3SegmentsTest`/move consistency；否则容易改变 add/exchange 的成本口径。

进一步测试了“add/exchange 是否可以改成两次 merge2”的想法。朴素做法是先把 prefix 和 middle-backward 拼成一个中间函数，再接 suffix；该口径 5 万次随机 PWLF 对拍中有 31835 次和当前 `merge3Segments()` 不一致，且会把成本算得过低，最大低估约 `4944.72`，不能使用。更接近当前公式的做法是把 middle 的 forward/backward 都纳入同一个连接函数，再减去 middle 自身 best cost；该口径在同一测试中和当前 `merge3Segments()` 完全一致，30 万次 benchmark 中当前 `merge3` 为 `0.313s`，该链式版本为 `0.273s`，约 `1.15x`。因此它理论上可以作为后续优化方向，但收益明显小于 `merge2`，且需要把当前 `f2.resetDomain()` 副作用改成局部无副作用实现后再接入正式代码。

进一步按“先构造 `prefix+job` 的 forward 函数，再和 suffix 做一次 merge2”的口径单独测试。该口径更接近普通动态规划递推，但仍不等价于当前 `merge3Segments()`：5 万次随机 PWLF 对拍中有 13677 次不一致，且均为 forward-first 版本高估当前 `merge3`，最大高估约 `3135.92`；50 万次 benchmark 中当前 `merge3` 为 `0.457s`，forward-first 为 `0.356s`，约 `1.28x`。原因是当前 `merge3` 允许中间 job 在 prefix 可接入时间和 suffix 可接入时间之间选择对自身最优的完成时间，而 forward-first 再 merge2 会把前半段函数压成一个单向 profile 后再接后缀，时间自由度被提前收缩。该版本不会低估，但会改变启发式 move 评价，不适合作为等价替换。

上面这条随机 PWLF 结论不能直接用于当前启发式单 job 插入热路径。按真实 `HeuristicPricingEngine.insertOrReplaceCost()` 调用口径重新对拍后，`shift1/shift2` 已经包含 setup time 和被插入 job / 后继 job 的加工时间，`merge3Segments()` 的 `duration2` 实际传入为 `0.0`。在这个口径下，用 `wet040_001_2m` 构造 512 组由 `Solution.updateFFunctions1ForMachine()` 和 `updatebFunctions1ForMachine()` 生成的真实前后缀函数，比较现有 `merge3(f1, jobForward, jobBackward, b3, duration2=0)` 与“用原始 job penalty 接到 shifted prefix 上、整体 `minimizePrefix` 后，再 `merge2(prefixWithJob,b3)`”，5 万次对拍 `mismatches=0`，最大误差为 0。若错误地用已经 `minimizePrefix` 的 job 函数再接前缀，会提前放松 job 自身时点并产生大量 mismatch，因此主线必须使用原始 job penalty。50 万次局部极限 benchmark 中，若复用预构造好的 `prefixWithJob`，现有 `merge3` 为 `0.274s`，forward-chain 为 `0.033s`，局部约 `8.37x`；更接近实际调用、每次都重新构造 `prefixWithJob` 的 5 万次 benchmark 中，`merge3=0.032655s`，forward-chain `0.011907s`，约 `2.74x`。因此当前已将启发式 ADD/EXCHANGE 的单 job 插入评价改为该等价 forward-chain 口径，同时在代码中保留原 `merge3` reference 实现用于后续对拍或回退；前面的随机 PWLF 记录只说明该等价性不能泛化到任意三段函数或多 job 片段。

随后又把 `f1.shiftX(shift1).add(jobPenalty)` 合并为 `PiecewiseLinearFunction.addShifted(f1, shift1, jobPenalty)`，直接在 shifted segment 上双指针相加，省掉一整条 `shiftedF1` 临时函数。该 helper 与旧的 `shiftX().add()` 口径对拍 5 万次 `mismatches=0`，整条 fast path 与 `merge3` reference 仍为 `mismatches=0`。更接近实际调用的 5 万次 benchmark 更新为 `merge3=0.056588s`，forward-chain `0.018230s`，约 `3.10x`。因此当前低风险常数优化已经基本集中在“少构造临时 PWLF”这一层；若继续提速，需要考虑把 `addShifted + minimizePrefix + merge2` 进一步融合为只求最小值的专用扫描器，但那会涉及 prefix-min envelope 的在线维护，正确性风险明显高于本次改动。

### 2026-07-08 40-2 ng-DSSR 好配置复跑与瓶颈复核

为验证单 job ADD/EXCHANGE fast path 对完整求解的影响，重新跑了 `wet040_001_2m` 的当前 ng-DSSR 好配置。第一次手动命令漏掉旧 run 中的 `joinBestMode=BEST_UB`、`midpointProbeScore=queue`，并把 `maxHeuristicColumns` 显式设成 2000；该组结果为 `solve=177.261s`，但不作为严格 A/B，只用于发现配置未对齐。随后按旧日志完整对齐配置：normal ng-DSSR、`nearestK3/top3`、ALNS 60s 且 SA 关闭、RMIH、启发式 pricing、`completionBound=allCycles`、subtree pricing-only、midpoint probe 且 `midpointProbeScore=queue`、dual-bound pruning、strong branching、light repair、branch-implied penalty、time-indexed root preprocessing、time-indexed pre-heuristic、seed 200、repeatability filter、`timeIndexedCompletionBoundScalar/Window/ArcFixing=true`、`timeIndexedCompletionBoundInRoundArcFixing=false`、`joinBestMode=BEST_UB`、`maxHeuristicColumns=1500`。

严格配置结果目录为 `test-results/bpc/tmp-wet040-001-ng-goodcfg-strong-tiroot-tipre-inroundoff-fastheur-exactcfg-20260708`。结果为 `FINISHED,obj=bound=22580,solve=182.129s,root=91.965s,nodes=17,pool=114938,pricing=3068,exact=8.926s/85,heuristic=11.500s/267,masterLP=67.497s,valid=true`。和旧的 `tmp-wet040-001-ng-goodcfg-strong-tiroot-tipre-inroundoff-20260707` 对比，搜索结构完全一致：`nodes=17`、`pricingRounds=3068`、`addedColumns=199223`、`peakPool=114938`、`root bound=22490` 都相同。这说明本次 fast path 没有改变分支路径、列池规模或最终证明结构。

时间变慢主要来自同一搜索结构上的 LP 和 time-indexed root preprocessing 耗时波动/膨胀，而不是 ng-DSSR exact 或启发式 fast path 变差。旧 run 的 root preprocessing summary 为 `ms=36680.071`，本次为 `58138.971`；旧 run 的 `master LP time=44.064s`，本次为 `67.497s`，其中 `after_pricing` 从 `25.972s/600` 增到 `42.147s/600`，`strong_branching_light_repair_rmp` 从 `11.413s/348` 增到 `16.314s/348`。对应地，root time 从 `57.124s` 增到 `91.965s`。这类差异发生在同样的调用次数和同样的列池轨迹上，主要应理解为 LP/预处理实现常数和运行环境/JIT/CPLEX 状态层面的时间变化，而不是数学搜索变差。

fast path 对启发式本身是正向的。普通 `HeuristicPricing` 从旧的 `12.244s/267` 降到 `11.500s/267`，`HeuristicPricing[strongBranching]` 从旧的 `34.908s/842` 降到 `31.693s/842`，`HeuristicPricing[FindFeasible]` 也从 `2.677s/83` 降到 `2.559s/83`。但这个收益只有几秒，被 `master LP` 增加约 23.4 秒、`TimeIndexedGraphPricing` 从 `7.115s/337` 增到 `11.754s/337`、ng-DSSR exact 从 `6.940s/85` 增到 `8.926s/85` 抵消。因此当前完整求解瓶颈仍然不是 HeuristicPricing 的单次 ADD/EXCHANGE 计算，而是 master LP 重解和 strong branching trial 相关 LP；fast path 属于低风险常数优化，但不能单独改变 40-2 好配置的总时间排序。

### 2026-07-08 50-3 单 job fast path 严格对齐复测

按用户要求，重新回到 `wet050_003_3m`，和前一条 50-3 好配置记录做同配置对比。历史基线为 `test-results/bpc/tmp-wet050-003-3m-ng-goodcfg-20260707b`，配置是 normal ng-DSSR、`nearestK3/top3`、ALNS 60s 且 SA 关闭、RMIH、启发式 pricing、`completionBound=allCycles`、subtree pricing-only、midpoint probe、dual-bound pruning、strong branching、light repair、branch-implied penalty、time-indexed root preprocessing、time-indexed pre-heuristic、root seed 200、repeatability filter、`timeIndexedCompletionBoundScalar/Window/ArcFixing=true`、`timeIndexedCompletionBoundInRoundArcFixing=false`，SRI/partial/route enumeration/dual stabilization 关闭。

本次 fast path 版本结果目录为 `test-results/bpc/tmp-wet050-003-3m-ng-goodcfg-fastheur-20260708`。结果为 `FINISHED, obj=bound=26527, solve=273.030s, root=77.547s, nodes=10, pool=75976, pricing=1930, exact=18.848s/45, heuristic=22.750s/124, masterLP=43.872s, valid=true`。历史基线为 `FINISHED, obj=bound=26527, solve=381.350s, root=121.431s, nodes=10, pool=67368, pricing=1772, exact=16.728s/44, heuristic=40.275s/122, masterLP=47.050s, valid=true`。

这次对比说明 fast path 在 50-3 上有明确收益，而且不是靠改变最优值或减少节点数。节点数同为 10，最终目标一致，exact ng-DSSR 时间反而略增 `16.728s -> 18.848s`，master LP 略降 `47.050s -> 43.872s`。主要收益来自启发式定价，尤其 strong branching phase2 的 Tabu heuristic：`HeuristicPricing[strongBranching]` 从 `153.195s/529 calls, avg 289.593ms` 降到 `93.274s/595 calls, avg 156.764ms`。普通 `HeuristicPricing` 也从 `40.275s/122, avg 330.126ms` 降到 `22.750s/124, avg 183.467ms`。这和单 job ADD/EXCHANGE fast path 的预期一致：调用次数相近甚至更多，但单次 move 评价常数显著下降。

需要注意的是，本次 pool 从 `67368` 增到 `75976`、pricing round 从 `1772` 增到 `1930`，说明列轨迹并非完全相同；但分支规模和证明结果一致，且耗时改善主要集中在同类启发式组件上。因此当前结论是：单 job fast path 是有效的低风险常数优化，特别能降低 strong branching phase2 中反复 Tabu trial 的成本；剩余大头仍包括 phase2 调用频率、master LP 重解和部分 repair/exact 流程，而不是 ADD/EXCHANGE 单次拼接本身。

### 2026-07-08 启发式 single-job fast path 与旧 merge3 no-prune 诊断

针对 `HeuristicPricingEngine` 中 single-job ADD/EXCHANGE fast path 和旧 `merge3Segments` reference 的 mismatch，又增加了一次只在 `heuristicPricingValidateFastMerge=true` 下触发的诊断：当旧 merge3 因 `cost23Skip` 返回 BigM、而 fast path 为有限值时，额外计算一个去掉 `cost23 + bridgeCost >= curUpperBound` 剪枝后的旧 merge3 结果 `merge3NoCost23Prune`。

在 `wet050_003_3m` 的诊断 run `tmp-wet050-003-3m-ng-merge3-noprune-audit-20260708` 中，日志共捕获 129 条带 no-prune 结果的 mismatch，`merge3NoCost23Prune` 全部仍为 `>= 9e7` 的 BigM 量级；例如第一条为 `reference=1.0E8, fast=20986.0, trueCost=20986.0, merge3NoCost23Prune=9.999976E7`。因此当前结论是：mismatch 不是单纯由旧 `cost23Skip` 早剪枝造成的；旧 merge3 把单 job 当成中间段 forward/backward 去拼，在 compact window 场景下和“先把 prefix+job 压成 envelope，再与 suffix 做 merge2”的真实单 job 插入语义不等价。fast path 和 `TWETColumnEvaluator.evaluate(sequence)` 对拍一致，后续应以 fast/evaluator 口径为准，旧 merge3 只保留作诊断，不再作为该场景的正确 reference。
#### merge3 mismatch 的进一步原因

进一步看代码后，原因不是 `merge3Segments()` 里某一个 early return 单独写错，而是旧三段拼接和当前 single-job 插入 fast path 在 compact window 下不是同一个对象。

当前 fast path 对 ADD/EXCHANGE 的单 job 插入做的是：先把 `prefix + job` 直接拼成一个前向 envelope，即 `prefixWithJob(t)` 表示“前缀加该 job 可以在不晚于 t 的某个时刻完成，之后允许等待”；然后再用 `merge2(prefixWithJob, suffix)` 接后缀。这个对象和 `TWETColumnEvaluator.evaluate(sequence)` 的固定序列递推一致。

旧 `merge3Segments(f1, single.forward, single.backward, b3, ...)` 把这个单 job 当作一个中间段，分别使用该 job 的 forward closure `F_j(t)=min_{u<=t} p_j(u)` 和 backward closure `B_j(t)=min_{u>=t} p_j(u)`。这套公式适合原来“中间是一整段 sequence”的三段拼接推导，但在 single job 且窗口外用 BigM 填充时，`F_j` 和 `B_j` 已经是两个独立的闭包对象。特别是 fallback 分支会同时把 `B_j(t)`、`F_j(t)` 和后缀函数放在同一个 t 上组合，再减去中间段的全局最小值。这样会隐含要求中间 job 在同一个边界 t 上同时满足“可不早于 t 调度”和“可不晚于 t 调度”。而真实 single-job 插入只需要 job 在某个 u<=t 完成，然后可以等到 t 再接后缀。

因此当 compact window 让 `B_j` 在较晚 t 上为 BigM、但 `prefixWithJob(t)` 仍然可以通过“job 早完成、等待到 t”保持有限时，旧 merge3 会返回 BigM，fast path 和 evaluator 仍返回有限值。诊断里常见 `sH2 > sH3` 后进入 fallback，且去掉 `cost23Skip` 后仍为 BigM，正是这个语义差异的表现。没有 compact window 的短诊断里 mismatch 为 0，也说明问题是 BigM 硬窗/compact window 与旧三段 reference 假设叠加后触发的，不是普通无窗局部搜索公式突然失效。

因此后续口径为：单 job ADD/EXCHANGE 使用 `prefix + job -> merge2 suffix` 的 fast path，并用 evaluator 对拍；旧 `merge3Segments` 仍可保留给真正的三段 sequence 拼接或诊断，但不能再作为 single-job + compact window 场景的正确 reference。

### 2026-07-08 启发式 merge3 中间段口径复核

进一步回看 `HEU.Solution.merge3Segments()`、`Solution.updateFunctions2ForMachine()` 和 `HEU.Move` 的调用后，确认旧启发式三段拼接里的中间段不是原始 job penalty，也不是未最小化的原始段成本。`f2` 是中间段的 forward envelope，构造时会做 `minimizePrefixInPlace()`；`b2` 是同一中间段的 backward envelope，构造时会做 `minimizeSuffixInPlace()`。对于单任务中间段，`HeuristicPricingEngine` 的旧 reference 也是先把该 job 的 penalty 分别复制成 singleton forward/backward，再分别做 prefix/suffix minimize 后传给 `merge3Segments()`。

因此，当前 single-job fast path 和旧 merge3 reference 的差异，不是“旧实现用原始成本、新实现用最小化成本”导致的。旧实现本来就用最小化后的中间段 envelope。真正差异在于：旧 merge3 把单 job 当成一个通用中间段，同时使用该 job 的 forward/backward closure；而 fast path 是先按固定序列递推把 `prefix + job` 合成新的 prefix envelope，再和 suffix 做 merge2。后者更贴近 `TWETColumnEvaluator.evaluate(sequence)` 的固定序列口径。在 compact window/BigM 硬窗场景下，旧 merge3 的单 job 中间段口径可能过强地要求中间段左右 closure 在同一边界口径下有效，从而返回 BigM；fast path 和 evaluator 仍能给出有限真实序列成本。

补充精确根因：compact window 下 `setDomain(..., true)` 是窗口外填 BigM，不是物理删除。`minimizePrefixInPlace()` 只把窗口右侧 BigM 转成“已在窗口内完成后等待”的有限值，窗口左侧仍为 BigM；`minimizeSuffixInPlace()` 对称地只把窗口左侧 BigM 转成有限值，窗口右侧仍为 BigM。因此 single-job 的 `forward` 和 `backward` closure 并不会让 BigM 在全域消失，它们只在相反方向表达等待。

旧 `merge3Segments()` 用单 job 作为中间段时，fallback 分支会在同一个边界时间 t 上同时使用该 job 的 `B_j(t)` 和 `F_j(t)`，即要求这个单 job 的 suffix-closure 和 prefix-closure 在同一个 t 上都有限。若真实固定序列语义是 job 在 compact window 内较早完成，然后等待到较晚 t 再接 suffix，则 `F_j(t)` 有限但 `B_j(t)` 可能已经是 BigM。于是旧 merge3 返回 BigM；而 `prefix + job -> prefix envelope -> merge2 suffix` 能表达“完成后等待”，并和 `TWETColumnEvaluator.evaluate(sequence)` 一致。

进一步分析旧 `merge3Segments` 在 compact window 下怎样才能正确。旧三段公式成立依赖一个隐含前提：中间段用两个一维 envelope `f2/b2` 足以表达和前后段的拼接，并且定义域主要只是传播时的辅助裁剪。代码注释里也写到该定理依赖函数已经 minimize，且原先基本不考虑 `f2/b2` 定义域。compact window 改变了这一点：窗口外 BigM 不再是普通大成本，而是在表达“这个完成时间不允许”。prefix-min 和 suffix-min 只分别表达单方向等待，不能把同一个中间段在两个边界上的可行性相关性压缩成两个独立一维函数后再任意相加。

因此，旧 `merge3Segments` 在 compact window 下要保持正确，不能仅靠“minimize 后 BigM 消失”。正确选择只有几类：其一，不让旧 merge3 使用 compact-window 后的 BigM 函数，继续用全域原始 penalty，这样旧公式仍处在原来的较弱口径，但失去 compact window 对启发式 move 的剪枝。其二，对单 job 中间段使用固定序列递推口径，只让该 job penalty 出现一次，而不是同时使用 `F_j` 和 `B_j`。其三，对一般多 job 中间段，需要保存或临时计算一个真正的二边界 segment transfer，即给定左边界和右边界时中间段的最优成本；仅靠 `f2(t)` 和 `b2(t)` 两个一维闭包不够。否则旧 merge3 在 fallback 中会把 `B_j(t)`、`F_j(t)` 和后缀函数放到同一边界时间上组合，可能要求一个任务既能“在 t 之后调度”又能“在 t 之前调度”，从而把真实可行的等待方案误判为 BigM。

### 2026-07-08 single-job merge3 compact window 对照诊断

按“同一路径但去掉 compact window”重新做了 single-job fast path 与旧 `merge3Segments()` 的对照。诊断只在 `heuristicPricingValidateFastMerge=true` 的 mismatch 日志里增加一个 `unrestrictedMerge3` 字段：它使用同一个候选位置、同一个插入/替换 job 和同一个旧 `merge3Segments()`，但 prefix、singleton 和 suffix 函数全部用原始 `data.penaltyFunction` 重建，不使用 node compact window 裁剪。

结果目录为 `test-results/bpc/tmp-wet050-003-3m-ng-merge3-unrestricted-audit-20260708`。该 run 后续进入 ng-DSSR 统计输出时触发已有的 `statisticsSummary` 字符串拼接过长异常，但在崩溃前已经产生 12 条带 `unrestrictedMerge3` 的 firstMismatch 记录。统计结果为：`matches=12`，`finiteUnrestricted=12`，`diffTrueBad=0`，`maxDiffFast=0`，`maxDiffTrue=0`；同时原来的 `merge3NoCost23Prune` 没有一条低于 `9e7`。典型记录为：`reference=1.0E8, fast=20986.0, trueCost=20986.0, merge3NoCost23Prune=9.999976E7, unrestrictedMerge3=20986.0`。

这个结果把问题进一步收窄：旧 `merge3Segments()` 本身在未受 compact window 限制的函数上可以回到 fast/evaluator 一致；一旦传入 compact-window 后的 singleton forward/backward，旧三段公式就会把真实可行的“job 在窗口内完成后等待到后缀可接时间”的方案误判为 BigM。也就是说，触发条件确实是 compact window 改变了旧 merge3 公式的输入语义，而不是 fast path 算错，也不是单纯删除 `cost23Skip` 就能解决。

### 2026-07-08 清理 single-job merge3 诊断代码并固定 fast path

在确认无 compact window 的旧 `merge3Segments()` 与 fast/evaluator 完全一致、而 compact-window 口径下旧 merge3 会返回 BigM 后，生产代码不再保留旧 merge3 reference 作为启发式 ADD/EXCHANGE 的可选路径。`HeuristicPricingEngine.insertOrReplaceCost()` 现在直接使用 single-job fast path：先把 `prefix + job` 按固定序列递推压成 prefix envelope，再用 `merge2` 接 suffix。这个口径和 `TWETColumnEvaluator.evaluate(sequence)` 对齐，也是当前 compact window 下正确的单 job 插入语义。

本次同步清理了临时诊断开关和运行时审计字段，包括 `heuristicPricingUseMerge3Reference`、`heuristicPricingValidateFastMerge`、`heuristicMergeAudit`、`merge3NoCost23Prune`、`unrestrictedMerge3` 相关 helper。旧命令里即使继续传 `twet.bpc.fullDomainCompare.heuristicUseMerge3Reference` 或 `twet.bpc.fullDomainCompare.heuristicValidateFastMerge`，runner 也不再读取这两个属性。

`Solution.merge3Segments()` 本身不删除，也不影响 ALNS。ALNS/旧启发式路径没有 node compact window 这种 BigM 硬窗裁剪语义，旧 merge3 在那里仍是原有三段拼接工具；本次问题只发生在 `HeuristicPricingEngine` 把 node compact window 后的 singleton forward/backward 作为 single-job 中间段传入旧 merge3 的场景。

验证：focused `javac` 编译 `TWETBPCConfig.java`、`GCBBFullDomainComparisonTest.java`、`HeuristicPricingEngine.java` 通过；`HEU.OutsourcingMoveConsistencyTest passed, checked=14168`。

### 2026-07-08 50-3 setupR25/R50 的 time-indexed 与 ng-DSSR 对比

本次在 `wet050_003_3m` 上按 `SetupRatioVariantGenerator` 生成两组 setup time 变体，目标是闭包后平均 job-to-job setup / 平均 processing time 分别接近 0.25 和 0.50。生成结果为：`setupR25` 的 `avgP=50.140000, avgSetup=12.527755, postRatio=0.249856`；`setupR50` 的 `avgP=50.140000, avgSetup=25.068571, postRatio=0.499972`。两组都使用当前 50-3 对比口径：单线程、ALNS 60s 且 SA 关闭、RMIH、强分支、pricing-only subtree、completion bound、dual-bound pruning 等保持一致。time-indexed 组使用纯 `TimeIndexedGraphPricing`；ng-DSSR 组使用 normal ng-DSSR nearestK3/top3，并打开 time-indexed root preprocessing、root seed 200、time-indexed pre-heuristic、compact window/arc fixing 辅助。中途发现 ng-DSSR 的 `statisticsSummary()` 一次性字符串拼接超过 JDK 22 的 concat slot 上限，已只把该日志汇总函数改成 `StringBuilder`，不改变定价逻辑；单文件 `javac` 已通过。

结果如下：

| instance | 方法 | obj=bound | solve(s) | root(s) | nodes | pool | exact(s/calls) | heuristic(s/calls) | masterLP(s) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| setupR25 | time-indexed | 26964 | 180.738 | 107.162 | 11 | 65727 | 12.056 / 405 | 0 / 0 | 88.954 |
| setupR25 | ng-DSSR goodcfg | 26964 | 131.850 | 92.492 | 9 | 26972 | 5.123 / 41 | 5.185 / 112 | 36.863 |
| setupR50 | time-indexed | 32237 | 82.447 | 56.011 | 5 | 41091 | 6.246 / 231 | 0 / 0 | 28.167 |
| setupR50 | ng-DSSR goodcfg | 32237 | 91.825 | 69.320 | 3 | 16372 | 7.781 / 31 | 6.284 / 128 | 11.295 |

当前观察是：在 0.25 setup ratio 下，ng-DSSR 仍然更快，主要是列池和 master LP 明显更小，exact ng-DSSR 本身只花 5.1s；在 0.50 setup ratio 下，纯 time-indexed 反而略快，虽然列池更大，但节点很少、pricing 很快，master LP 也没有膨胀到抵消优势。两组都没有表现出“setup time 增大以后 time-indexed 明显变差”的现象；至少在这个 50-3 due-window 结构下，pseudo-schedule gap 仍然很小，time-indexed 的弱列没有造成明显搜索困难。这个结果和前面猜测一致的一部分是：time-indexed 在时间域不太大、due window 较紧时很有竞争力；但仅靠 setup ratio 从 0.25 增到 0.50，暂时还不足以制造 ng-DSSR 明显优势。

### 2026-07-08 50-3 setupR25/R50 补充 SRI 对比

在上一组 no-SRI 对比基础上，继续补跑两类 SRI：time-indexed 使用 `TimeIndexedGraphRank1CutPricing` + `enableSubsetRowCutsForTimeIndexedGraph=true`，即论文 rank-1 cut 口径；ng-DSSR 使用 partial-list dominance + `enableSubsetRowCutsForPartialDominance=true`，因为当前普通 normal backend 不接 SRI state。其余配置保持当前好配置：ALNS 60s 且 SA 关闭、强分支、RMIH、pricing-only subtree、completion bound、dual-bound pruning、time-indexed root preprocessing / seed 200 / pre-heuristic 等保持一致。四组结果均 `valid=true`。

| instance | 方法 | obj=bound | status | solve(s) | nodes | pool | exact(s/calls) | heuristic(s/calls) | masterLP(s) |
|---|---|---:|---|---:|---:|---:|---:|---:|---:|
| setupR25 | time-indexed no-SRI | 26964 | FINISHED | 180.738 | 11 | 65727 | 12.056 / 405 | 0 / 0 | 88.954 |
| setupR25 | time-indexed rank1/SRI | 26964 | ROOT_PROCESSED | 143.362 | 1 | 62811 | 10.962 / 384 | 0 / 0 | 83.263 |
| setupR25 | ng-DSSR no-SRI | 26964 | FINISHED | 131.850 | 9 | 26972 | 5.123 / 41 | 5.185 / 112 | 36.863 |
| setupR25 | ng partial + SRI | 26964 | FINISHED | 147.318 | 3 | 7492 | 23.383 / 121 | 13.607 / 285 | 31.549 |
| setupR50 | time-indexed no-SRI | 32237 | FINISHED | 82.447 | 5 | 41091 | 6.246 / 231 | 0 / 0 | 28.167 |
| setupR50 | time-indexed rank1/SRI | 32237 | ROOT_PROCESSED | 96.386 | 1 | 40462 | 8.905 / 222 | 0 / 0 | 23.662 |
| setupR50 | ng-DSSR no-SRI | 32237 | FINISHED | 91.825 | 3 | 16372 | 7.781 / 31 | 6.284 / 128 | 11.295 |
| setupR50 | ng partial + SRI | 32237 | ROOT_PROCESSED | 71.742 | 1 | 3622 | 6.400 / 22 | 3.797 / 67 | 10.317 |

当前结论是：SRI 在这两个变体上的作用不一致。`setupR25` 中 time-indexed rank1/SRI 能把 root 直接闭合并从 180.7s 降到 143.4s，但仍慢于 no-SRI ng-DSSR 的 131.9s；ng partial + SRI 的列池明显更小、节点更少，但 exact/heuristic 变重，最终 147.3s，也没有超过 no-SRI ng-DSSR。`setupR50` 中 SRI 的作用更明显：time-indexed rank1/SRI 直接闭合 root，但总时间略慢于 no-SRI time-indexed；ng partial + SRI 只保留 3622 列、10 条 cut，root 直接闭合，总时间 71.7s，是这两组里最快的。由此看，setup 更强时，SRI 对 ng partial 的 root bound/列池压缩收益可能更容易抵消状态成本；但 time-indexed rank1/SRI 仍会受到 cut 后 master LP 成本影响，不一定比 no-SRI time-indexed 更快。
### 2026-07-08 SRI memory 口径、compact window 与 dual-bound 剪枝核查

本次针对 50-3 setupR25/R50 的 SRI 对比结果做了代码和日志核查。结论是：time-indexed rank1/SRI 使用的是论文式 limited arc-memory 口径，`SubsetRowCutGenerator` 在 `useTimeIndexedGraphRank1CutPricing=true` 时会走 `buildLimitedMemoryArcSet()`；但 ng-DSSR partial+SRI 本次 run 没有显式设置 `subsetRowCutMemoryMode`，因此沿用配置默认值 `full`，不是 limited-memory SRI。后续如果要让 ng partial+SRI 也按 limited 口径比较，需要显式设置 `subsetRowCutMemoryMode=arcMemory` 或 `nodeMemory/lm` 后重跑。

ng-DSSR+SRI 能使用 time-indexed root preprocessing 写回的 compact window 和 pricing-only/time-indexed arc 信息。R25 日志中 preprocessing 写回 `windowJobs=50, avgWindowLen=192.540, avgShrinkRatio=0.891, seedElementaryCols=200, promotedOrdinaryArcs=2138`；R50 写回 `windowJobs=50, avgWindowLen=311.700, avgShrinkRatio=0.836, seedElementaryCols=200, promotedOrdinaryArcs=1996`。正式 ng-DSSR pricing 日志里也能看到 `timeWindowJobs=50`、`timeWindowAvgLen`、`timePricingOnlyArc` 和 `ngWindowRepeatability=timeIndexed`，说明这些信息确实进入了后续 partial+SRI pricing。需要注意的是 root preprocessing 本身按 no-cut/no-SRI 临时 time-indexed root 执行，只复制 fixing/window/seed 证据，不复制 SRI cut 状态。

R25 time-indexed rank1 的 `pruned_by_dual_bound` 不是 LP 本身整数闭合。最后一轮 exact pricing 日志显示 `observedDualBound=26963.999999999996`，incumbent 为 `26964`；PC 中 dual-bound pruning 判定为 `observedDualBound >= incumbent - dualBoundPruningTolerance`，因此该 root 是下界在容差内达到当前上界后被剪掉。日志中的 node summary 因 close-only 节点没有常规 master solution 填充而显示 `lpObj=-/bound=-`，但 final lower bound 打印为 `26964.000000`。

### 2026-07-08 50-3 宽 due window 对 time-indexed root 松弛的影响

本次继续用 `wet050_003_3m` 检查“due window 放宽是否会让 time-indexed pseudo-schedule 松弛显著变弱”。实验没有改原始 `.dat` 的 job 行，而是使用 loader 里的 `twet.data.dueWindowHalfWidth=W`，把每个 due date 扩成 `[max(0,d_j-W), d_j+W]` 后重新计算 horizon、硬时间窗和 penalty function。所有 root-only 实验均使用纯 `TimeIndexedGraphPricing`，关闭 rank-1 cut 和旧 HeuristicPricing，`maxNodes=1`，ALNS 30s 且 SA 关闭。

原始 setup 的 50-3 上，`W=0/100/300/800/1500` 的 root-only 结果如下。`W=0` 时 root gap 为 `1.145%`，正值列中 non-elementary 为 `13/44`；`W=100` 时 root gap 升到 `2.651%`，non-elementary 为 `23/40`；`W=300` 时 root gap 升到 `15.226%`，non-elementary 为 `26/46`。继续放到 `W=800/1500` 时，目标直接变成 `0`，root 一轮闭合，正值列全部 elementary。这说明 due window 放宽不是单调制造难例：中等宽度会让 pseudo-schedule 更容易进入 LP 并削弱 root bound，但过宽会把惩罚基本抹掉，使实例退化成零罚或近零罚问题。

随后用 `SetupRatioVariantGenerator` 基于同一 50-3 生成 `setupR50/setupR75`，闭包后平均 job-to-job setup / 平均 processing 分别为 `0.499972/0.750022`。在这两个强 setup 变体上再叠加 `W=300`，time-indexed root gap 分别为 `12.209%` 和 `14.173%`；对应正值列 non-elementary 比例分别为 `29/45` 和 `31/45`，且 elementary 正值和只剩 `1.282572/3` 和 `1.223769/3`。这比原始 `W=0` 明显更偏向 pseudo-schedule 支撑 root LP，说明“强 setup + 中等宽 due window”确实能造出 time-indexed root 松弛明显变弱的实例。

为确认这不是单纯 incumbent 变差造成的，也跑了 `setupR50/setupR75, W=300` 的纯 ng-DSSR root-only 对照，关闭 time-indexed root preprocessing 和 time-indexed pre-heuristic，只保留 ng-DSSR nearestK3/top3、completion bound、dual-bound pruning、midpoint probe 与 repeatability ng-set filter。结果为：`setupR50` 的 ng-DSSR root bound `1726.014329`，time-indexed 为 `1702.274302`；`setupR75` 的 ng-DSSR root bound `3326.995658`，time-indexed 为 `3272.566507`。ng-DSSR root bound 确实更强，但提升只有几十个目标值单位；代价是 exact pricing 从 time-indexed 的 `6.606s/150`、`4.684s/93` 上升到 ng-DSSR 的 `196.055s/12`、`139.235s/8`。也就是说，这类变体已经暴露了 time-indexed pseudo-schedule 的弱 bound，但在 50 任务、整数 horizon 仍不太大的口径下，time-indexed 的便宜定价仍然很有竞争力。

当前结论可以收紧为：只放宽 due window 会先让 time-indexed root LP 更依赖 non-elementary pseudo-schedule，root bound 变弱；但窗口过宽会让目标退化为零，不是有意义难例。更有代表性的压力组合是“较强 setup time + 中等宽 due window + 仍保持非零惩罚”。如果再叠加更大的 horizon、非均匀时间放大或小数时间 scale，time-indexed 的图规模和 pseudo-schedule 尾部问题才更可能同时暴露。ng-DSSR 的 repeatability ng-set filter 在本次 ng-DSSR 日志中已生效，日志显示 `ngWindowRepeatability=timeIndexed/repeatable50/nonRepeatable0`；在该 W=300 场景下所有 job 仍可能重复访问，所以过滤没有减少初始 ng-set 成员，这也解释了为什么 ng-DSSR 没有明显因此加速。

### 2026-07-08 W300 下 time-indexed arc fixing 强弱复核

为避免把 direct time-indexed pricing 中的 `timeArcSkips=0` 误解为 arc fixing 本身无效，本次单独用 `setupR50 + dueWindowHalfWidth=300` 跑了一次 `TimeIndexedRootPreprocessor`，只看 root 预处理给后续 ng-DSSR 能留下多少 fixing/window 证据。该 run 的关键结果为：`tempPool=31039`，`graphFix candidates=5584245, fixed=4454019, unavailable=191096`，`promotedOrdinaryArcs=150`，`windowJobs=50`，`avgWindowLen=748.120`，`avgOrigPts/hullPts/reachablePts=2230.0/749.1/733.3`，`scalarFix fixed=0`。root positive columns 中 non-elementary 为 `29/45`，说明 W300 下 pseudo-schedule 对 LP 的支撑已经比较明显。

因此这里要区分两层结论。若看时空弧 `(i,j,t)`，time-indexed graph fixing 并不弱，本次约固定了 `4454019 / 5584245 = 79.8%` 的候选时空弧，并把每个 job 的可达时间 hull 从平均 2230 个点压到约 749 个点。但若看能够推广给 ng-DSSR 直接跳过的普通弧 `(i,j)`，它确实明显变弱：只有 150 条普通弧能被完全推广。对比此前较窄窗口/原始口径下 root preprocessing 里常见的 `promotedOrdinaryArcs` 约 2000 条、平均窗口长度约 200 到 300，本次 W300 的普通弧推广和 compact window 都弱了很多。

原因是 W300 给每个 job 留下了较宽的可行完成时间区间。很多普通弧虽然在大多数时间点上已经不可能或不值得用，但只要还存在少数时间点可用，就不能把整条 `(i,j)` 普通弧推广成 pricing-only 禁弧。因此，time-indexed 的细粒度 fixing 仍然强，但它的效果更多停留在时空图内部和 per-job 时间窗收缩上；传递给连续时间 ng-DSSR 的普通弧证据会明显减少。这也解释了为什么 W300 下 ng-DSSR 的 repeatability filter 日志为 `repeatable50/nonRepeatable0`：所有 job 仍可能重复访问，窗口证据不足以把初始 ng-set 大幅缩小。

当前判断为：放大 due window 后，time-indexed arc fixing 不是整体失效，而是“时空弧层面仍强、普通弧推广层面变弱”。如果后续想让 ng-DSSR 更充分利用这类证据，需要更细粒度地把 `(i,j,t)` 或 per-job 可达时间集合接入连续函数 pricing；只依赖普通弧推广和 hull window 时，W300 这类实例上的收益会被明显削弱。
### 2026-07-09：ng-DSSR 相对 time-indexed 的后续证明与优化方向

当前实验说明，不能指望 ng-DSSR 在所有当前小整数 due-date 算例上自然压过 time-indexed。time-indexed 图虽然是 pseudo-schedule/非 elementary 松弛，但在原始 40-2、50-2/50-3 这类小整数 horizon、due date 较硬的算例上，重复访问 job 的收益很小，很多正值列接近 elementary，因此 root gap 很小、图上 shortest path 又极快。此时 ng-DSSR 的强度优势只体现在零点几个百分点的 root bound 改善，而 exact pricing 需要付出大量 PWLF label、completion bound 和 join 证书成本，整体很容易输给 time-indexed。

要证明 ng-DSSR 的价值，实验口径应从“所有算例都更快”改成“识别 time-indexed 适用区间，并证明在不适合 time-indexed 的区间 ng-DSSR 更稳”。当前已有证据支持这个方向：原始和 zero-setup 40-2 中两者 root gap 接近；setupR50/R75 + setupCost20 下 root gap 差距开始扩大；timeJitterX10 这类整体时间放大后，time-indexed no-SRI 900s 未闭合而 ng-DSSR + window tightening 能闭合；W300 宽窗口和强 setup 会显著增加 time-indexed 的 non-elementary 正值列比例，但也会让 ng-DSSR exact pricing 的 join/label 证书成本暴涨。后续实验应重点报告 root gap、positive non-elementary 比例、time-indexed graph state/arc 数、ng-DSSR active label per bucket、funcEval 数和 exact/certificate 时间，而不是只看总 solve time。

算法优化上，当前最有希望降低 ng-DSSR exact 成本的方向不是 halfway 去重，而是“join-envelope 只做安全 group 下界筛选，负 group 内回退 label-level scan 或返回多个 source pair”。第一版 join-envelope 已经证明能把单轮 join funcEval 从千万级压到万级，但因为每个 group-pair 只返回一个代表列，削弱了批量加列能力，导致 DSSR 轮数增加。更合理的第二版是：先用 traced envelope 判断一个 group-pair 的最小下界；若非负，直接跳过整个 group-pair；若为负，则在该 group-pair 内执行原 label-level scan，或至少返回多个 source pair，直到达到原 join 的加列/证书口径。这样可以保留 exact certificate，同时把明显无用的 group-pair 大块剪掉。

第二个方向是减少扩展阶段的 PWLF 构造。当前 `extendForward/Backward` 已有时间窗 overlap 检查、无 SRI 时避免重复 no-SRI frontier、join 已使用直接 min-sum，低级常数优化空间有限。更有价值的是在 `shiftX + add + normalize` 之前加入保守 scalar lower bound：用 label 当前 min、transition/job cheap lower bound 和 suffix completion bound 先判断该扩展是否可能导致负完整列；若连松弛下界都不可能为负，就不构造 child PWLF。这个方向要非常保守，不能用受 dual/window 限制后会污染真实列成本的值。

第三个方向是实验层面的 hybrid。若 horizon 小且 time-indexed root 很快，应承认 time-indexed 作为主算法或 root 预处理更合适；若 horizon 大、存在小数 scale、宽 due window 或复杂 setup/外包结构，则用 time-indexed 先做 root/局部 preprocessing 可能不划算，ng-DSSR 应直接利用 completion bound、compact window 和 pricing-only fixing。后续可以设计一个规则：按 `n*T`、time-indexed graph state 数、root pseudo non-elementary 比例和 root gap 判断采用 time-indexed 主算法、time-indexed root preprocessing + ng-DSSR，还是直接 ng-DSSR。

当前论文/实验叙事可以写成：time-indexed 方法在小整数时间、紧 due-window 的实例上非常强，原因是 relaxed pseudo 列接近 elementary 且 shortest path 极快；但它对 horizon scale、宽窗口、小数时间 scale 和外包/复杂 branching 的可扩展性较差。ng-DSSR 的定位不是无条件替代 time-indexed，而是在 time-indexed 图规模或松弛 gap 变差时提供更强的 elementary pricing 和更稳定的分支定价框架。后续关键工作是降低 ng-DSSR 的 exact certificate 成本，尤其是 join group 剪枝和扩展前保守下界剪枝。

### 2026-07-09：ng-DSSR 可能赢过 time-indexed 的条件判断

当前更准确的判断是：ng-DSSR 只有在 time-indexed 的两个优势至少有一个被削弱时，才有较大机会赢。time-indexed 的优势一是图上的最短路极快，二是当前很多 due-date/小整数算例里 pseudo-schedule 和 elementary route 很接近，root gap 很小。如果这两点都成立，ng-DSSR 即使列更强，也会被 PWLF label、DSSR 多轮、completion bound 和 join 证书成本拖慢。

第一类有利于 ng-DSSR 的情况是时间尺度变大或存在小数时间。time-indexed 的复杂度直接随离散时间点数放大，整数时间整体放大、非均匀扰动放大、或者小数时间需要 scale 成整数时，图规模会迅速变大；ng-DSSR 的连续时间 PWLF 虽然也会变复杂，但不按每个离散时间点建状态，因此相对更稳定。这也是目前最清晰、最容易证明 ng-DSSR 相对优势的方向。

第二类是 pseudo-schedule 明显变弱的实例。典型特征包括中等宽度 due window、较强 setup time、较强 setup cost 或者其他会让重复访问 job 变得有吸引力的结构。前面的 50-3 W300/R50/R75 结果已经说明，正值列中 non-elementary 比例上升后，time-indexed root gap 会明显变大；但同时 ng-DSSR exact pricing 也会变重。因此这一类不能只看 root gap，还要看 gap 改善是否足以抵消 ng-DSSR 的证书成本。

第三类是 branching、外包和子树约束逐渐变复杂的场景。time-indexed 在 root 上可能很强，但一旦带大量 branch/pricing-only arc、outsourcing membership、subtree fixing 和 compact window，直接 time-indexed 图的构建和证书维护会更重；ng-DSSR 更容易在连续时间标签和 completion bound 框架里继承这些约束。反过来，如果这些约束能把 time-indexed 图大量缩小，那么 time-indexed 仍可能继续占优，所以这里需要按节点深度和约束密度分段比较。

第四类是 time-indexed 的列很多但 master bound 改善慢的情况。time-indexed relaxed 列便宜，但可能给 RMP 加入大量弱 pseudo 列；如果这些列导致列池膨胀、LP 反复重解、bound 改善小，ng-DSSR 的强列可能用更少列达到类似或更好的下界。这里应重点比较 pool size、positive non-elementary ratio、root bound、LP time 和 node 数，而不是只看单次 pricing time。

因此后续实验应避免只在原始 40/50 小整数 due-date 算例上比较“谁更快”。更合理的矩阵是：小 horizon/紧 due-date 作为 time-indexed 优势区；时间放大、小数 scale、中等宽 due window、强 setup、setup cost、外包/复杂分支作为 ng-DSSR 潜在优势区。最终叙述应是方法适用区间的比较：time-indexed 在小整数紧窗口上很强，ng-DSSR 在离散时间规模变大或 pseudo-schedule 松弛明显变弱时更有价值。

### 2026-07-09：ng-DSSR 全域标签函数诊断

前面讨论过一种可能：当前 ng-DSSR 的 forward/backward 函数按 Tmid 做半域裁剪，因此同一 sequence 的不同 split 在理论上可能出现局部成本口径差异；如果把标签函数改成完整 `[0, pricingHorizon]`，则一条路径只要被某个 split 拼出来，成本更接近全域最优口径，也可能更适合后续参考 VRP halfway join 的“一条路径只生成一次”思路。

本次曾短暂实现诊断开关 `enableNgDssrFullDomainLabelFunctions`，runner 属性为 `twet.bpc.fullDomainCompare.ngDssrFullDomainLabelFunctions`。该开关只在 no-SRI / no-limited-SRI 的 normal ng-DSSR 主线上生效：source、sink、job penalty、扩展可行性检查都从半域 `[0,Tmid]` / `[Tmid,H]` 改为完整 `[0,H]`；Tmid 仍用于搜索方向和 crossing-arc join。为了避免把诊断口径的局部成本写入 RMP，开关打开时返回列会走真实成本回刷。同时加入 `splitDup/mismatch/maxAbsDiff` 统计，检查同一 sequence 多个 split 的 reduced cost 是否一致。后续实验确认该方向计算代价过高，因此该代码开关和统计字段已取消，文档仅保留否定结果。

在 `wet040_001_2m` 的 root-only exact 诊断中，baseline 口径为 normal ng-DSSR、`nearestK3/top3`、join-envelope 开、ALNS/启发式/time-indexed 预处理/强分支均关，`maxNodes=1`。半域版本在 `101.849s` 内完成 root pricing 并到 `NODE_LIMIT`，exact 为 `69.278s/20 calls`，root bound 为 `22490`。全域标签函数版本在 `320.041s` 时间限制内没有闭合 root，exact 为 `304.611s/12 calls`，最后一次 pricing 在 forward 扩展阶段耗尽时间。

日志显示，full-domain 后 split 一致性本身没有暴露错误：多轮统计中 `splitDup` 有重复 split，但 `mismatch=0, maxAbsDiff=0`。问题主要是标签数量和扩展规模显著增加。典型首轮中，半域版本 forward/bw kept 约 `1.3万/0.6万`，full-domain 变为 `8.9万/6.7万`；forward extension candidates 从约 `42万` 增到 `238万`，`paperGraph labels kept` 从约 `1.9万` 增到 `15.5万`，`envelopeMerges` 从约 `58万` 增到 `413万`。因此耗时大头不是 join 本身，而是全域函数削弱半域裁剪后带来的扩展、dominance 和 envelope 维护成本。首轮 full-domain 的 `joinEnvelopeMs build/join=1035.895/86.870ms`，而 forward/backward 扩展分别为 `11825/8799ms`。

当前结论是：全域标签函数可以作为诊断工具，用来验证同一 sequence 多 split 的成本一致性；但直接把半域函数改成全域函数不适合作为当前 ng-DSSR 主线优化，至少在 40-2 root 上成本远大于收益。后续如果继续探索 PDF/VRP 式“一条路径只生成一次”的 join，更合理的方向不是先全域化整个 labeling，而是保留半域 labeling 的扩展剪枝优势，在 join 前做更安全的 group 下界筛选，或只对少量候选 group 做回刷/复核。
### 2026-07-09：50-3 W100 的 time-indexed root-only 复核

在前一轮 `wet050_003_3m + dueWindowHalfWidth=100` 对比中，ng-DSSR 已经完整求解到 `obj=bound=11555`，总时间约 `737.703s`，而完整 time-indexed run 长时间未结束且没有正常写出 CSV。为判断 time-indexed 的问题是否发生在 root，本次单独跑纯 `TimeIndexedGraphPricing` 的 root-only：`maxNodes=1`，关闭 strong branching，保留 ALNS 30s 和 live trace。

结果为 `NODE_LIMIT`，即 root 已处理完后因节点上限停止。root incumbent 为 `11782`，root bound 为 `11469.712548`，root gap 为 `2.6505%`；root solving time 为 `68.111s`，总 `solve=69.110s`。time-indexed exact pricing 合计 `9.227s / 231 calls`，共加入 `51576` 条列，最终 pool 为 `51592`；master LP 合计 `25.184s`，其中 after-pricing LP `230` 次、平均约 `108.719ms`。root 结束时正值列 `40` 条，其中 elementary `16` 条、non-elementary `24` 条。root 后的 time-indexed arc fixing 候选 `4665429`，固定 `4452907`，gap 约 `312.287`。

因此，本算例 W100 下 time-indexed 的 root 本身并不慢，约 69s 即闭合 root；完整 time-indexed run 长时间不结束的主要风险更可能来自 root 之后的大列池 LP、分支/强分支试探和后续节点处理，而不是 root pricing 闭合阶段。这个结果也说明 W100 确实已经让 time-indexed root bound 明显弱于 incumbent，但 root 闭合仍然很快；ng-DSSR 能完整求解更快或更慢，需要继续分清 root bound 改善、节点数和后续 LP 成本三部分。

随后用同一配置跑完整 time-indexed，并打开 live trace 持续监测。结果并没有复现“长时间不结束”，而是 `FINISHED`：`obj=bound=11555`，总时间 `217.432s`，处理 `36` 个节点，加入 `82799` 条列，最终 pool `82506`。root bound 仍为 `11469.712548`，root time `62.336s`。对比前面的 ng-DSSR W100 完整结果 `737.703s / 9 nodes / pool=27647`，本次纯 time-indexed 明显更快，但依赖更多 relaxed/pseudo 列和更多节点。

耗时拆分显示，完整 time-indexed 的主要成本不是最短路 pricing。`TimeIndexedGraphPricing=23.231s / 974 calls`，repair 中 `FindFeasible=1.059s / 54 calls`；master LP 中 after-pricing 为 `23.811s / 939 calls`，而 strong branching phase-1 的 `strong_branching_light_repair_rmp=96.848s / 760 calls`，平均约 `127ms`，是最大单项。RMIH 约 `18.170s`。root 阶段 strong branching 最重，部分 trial LP 在 `5` 万列左右求解，单次约 `0.5-1.2s`；后续节点因 child seed 被筛到几千列，trial LP 通常降到几十毫秒。由此可见，之前完整 time-indexed 没写结果那次更可能是运行被中断或监测口径问题，不应作为 time-indexed 在该 W100 算例上失败的证据。
### 2026-07-09：50-3 W100 ng-DSSR 完整耗时拆分

在 `wet050_003_3m + dueWindowHalfWidth=100` 上，当前 ng-DSSR 好配置完整求解结果为 `FINISHED,obj=bound=11555,solve=737.703s,root=216.209s,nodes=9,pool=27647`。对比同一算例的纯 time-indexed 完整 run `217.432s,nodes=36,pool=82506`，ng-DSSR 的主要问题不是节点数，而是单次 exact pricing 和强分支启发式/LP 的证书成本太重。

汇总日志中 89 次 `GCNGBBStyleNgDssrPricing` 可见，ng-DSSR exact 合计约 `330.0s`。其中 `init=195.2s` 是最大项，`fw=39.2s`、`bw=48.8s`、`join=46.4s`。`init` 不是简单对象初始化，而是 `initialize(lp)` 的完整准备阶段，包含 dynamic/compact window、completion bound、midpoint probe、label store/queue/candidate state 初始化等。需要注意的是，当前日志里的 `completionBound buildMs` 不是跨 DSSR 轮累计口径：`exactPhaseMs init` 会累计同一次 exact pricing 内多轮 DSSR 的初始化时间，但 `completionBoundBuildNanos` 会在每轮 `resetStatistics()` 时清零。因此不能把最终日志里可见的 `completionBound buildMs≈58.9s` 与 `init=195.2s` 直接相减来解释“剩余 init”。复核 `rounds>1` 的日志后，很多多轮 exact pricing 的最终行显示 `completionBound buildMs=0`，但 `init` 仍然很大，说明隐藏在 init 里的主要仍可能是前面 DSSR 轮次的 completion bound、midpoint probe、window/state 初始化等，只是当前 summary 没有累计暴露。所有 exact 调用累计 `funcEval=60412832`，正反向构造 label 分别约 `370.3万/410.7万`，DSSR 内部总轮数 `312`。

完整 BPC summary 的大头为：`GCNGBBStyleNgDssrPricing=330.230s/89`，普通 `HeuristicPricing=137.035s/274`，`HeuristicPricing[strongBranching]=145.262s/311`，`master LP=49.630s`，`RMIH=9.267s`，`TimeIndexedGraphPricing=6.391s/217`。因此当前 W100 下 ng-DSSR 的瓶颈不是单一 join，而是 `init/completion-bound/midpoint-probe`、正反向扩展、join 以及强分支启发式共同叠加；继续只优化 join 很可能不够，后续更应优先补充 `initialize(lp)` 内部累计子计时，分清 completion bound、midpoint probe、dynamic window 和 label/candidate state 初始化的真实占比，再决定是否做跨轮或跨 pricing 的复用。

同一 time-indexed 完整 run 中，RMIH 可以运行，但效果较弱。time-indexed exact pricing 全程 `974` 次调用，加入约 `75544` 条列；日志累计 `negativeStates=2418223`，其中 `repeatedJobCandidates=2246968`，约 `92.9%` 的负候选状态对应重复 job 的 pseudo-schedule。root 结束时正值列 `40` 条，其中 `24` 条非 elementary，说明列池和 LP 解都明显受 pseudo 列影响。当前 RMIH 的 `coverRepair` 口径不是先过滤掉所有重复列，而是先用筛出的列做 `>=` covering MIP，再检查重复覆盖并对选中的重复列做删点 repair，最后跑 `==` partition。这个口径在 time-indexed 下数学上可以用，但这次 33 次 RMIH 中只有 2 次 feasible、0 次 improved，总时间 `18.170s`，其中 `select=0.153s`、`coverSolve=18.016s`、`repair=0`、`partition=0`。也就是说它主要卡在第一阶段 2000 条候选列的 covering MIP 求解/不可行证明，绝大多数调用还没进入 repeated-column repair。后续如果继续在 time-indexed 上开 RMIH，应考虑只把 elementary 列送入 RMIH、或给 time-indexed 单独降低 RMIH 频率/候选规模，否则很容易用较多时间换不到 incumbent 改进。

随后按这个判断对代码做了最小处理：当 `useTimeIndexedGraphPricing=true` 时，`Tree` 不再调用 RMIH，只保留 heartbeat 说明跳过。理由是 time-indexed 主线列池大量包含 pseudo/repeated 列，当前 RMIH 的 covering MIP 在这种列池上收益很弱且会额外消耗节点时间；ng-DSSR 和其他 elementary 列主线仍保持原 RMIH 逻辑。

为定位 ng-DSSR `init` 细分，又在 `GCNGBBStyleBidirectionalNgDssr.initialize(lp)` 中补了累计口径的 `exactInitDetailMs setup/diag/sri/window/ng/cb/preCert/probe/state/fullProbe`。一次 `wet050_003_3m + W100` 短诊断使用 `maxNodes=1`、`solveTimeLimitSeconds=120`、关闭 ALNS 和强分支，仅用于拆分 root exact pricing 的初始化成本。该 run 拿到 5 次 ng-DSSR exact pricing，`exact=24.820s`，其中 `init=18.879s`；细分为 `completion bound=11.792s`、`midpoint probe=7.043s`、`state=0.032s`、`ng=0.004s`、`window=0.001s`、`setup/diag/sri/preCert/fullProbe` 合计不足 `0.01s` 量级。这个诊断说明当前 W100 下所谓 init 慢，核心不是对象创建、ng-set 初始化或动态窗口，而是 completion bound 构建和 midpoint probe 两项。

### 2026-07-13：timeJitterX10 历史 time-indexed 版本口径复核

重新对照 2026-06-30 的代码提交与 `tmp-compare-40x10-timegraph-nosri-900s-20260630` 日志后确认，该 run 已经包含 6 月 28 日完成的 top-candidate 过滤、按 sequence signature 去重和固定 top-K heap；它不会像 6 月 20 日早期版本那样，对大量通过筛选的 end state 逐条调用 `TWETColumnEvaluator`。当时 engine 从 graph reduced cost 反推路径对应的 objective cost，time-indexed exact 共 `816.875s/908` 次、平均约 `0.900s`，日志每轮典型为 `20--76` 万 states 和 `800--3000` 万 arc scans。因此该 run 的主要慢因确实是 horizon 约 `19249` 下反复扫描大离散图、经历 908 轮 pricing 并维护大列池，不是旧 evaluator 重算热点。

该历史版本仍不与当前实现完全相同。其 root 多轮日志显示 `piWindow=enabled`，但当时尚未对最终选中的 dual-window 候选统一执行真实 sequence cost 回刷；当前版本只对最终 top-K 候选做 evaluator 回刷，并在 7 月 6 日优化了 evaluator，同时修正了 dual-window pseudo-schedule 与 certificate 边界。因此旧绝对时间不能直接当作当前版本基准，正式论文对比仍应重跑；但“大 horizon 使 time-indexed 图扫描和 pricing 轮数膨胀”的原因判断不依赖早期 evaluator 问题，仍然成立。

### 2026-07-14：40-3 原尺度与直接十倍时间缩放对比

1. 数据与实验口径

本次从 `data/40-1/wet040_001.dat` 转换得到三机器实例 `data/40-3/wet040_001_3m.dat`，再将 processing time、due date 和完整 setup time 矩阵直接乘以 10，得到 `wet040_001_3m_timeX10.dat`；权重、机器数和 setup cost 口径不变，也没有加入随机扰动。因所有时间量同步放大，原实例任意固定 sequence 的目标值应严格放大 10 倍。

实验中发现当前 strong branching 路径存在确定性正确性问题：原尺度最优三条 sequence 的成本为 `5373+5948+4337=15658`，用同一 evaluator 在十倍实例上重算得到 `53730+59480+43370=156580`，但 strong branching 开启时曾错误闭合到 `156590`。关闭 strong branching 后，同一 ng-DSSR 配置能够闭合到 `obj=bound=156580`。因此下表正式比较统一采用 `strong branching=false`；先前 strong-on 结果只保留作问题证据，不能混入方法性能比较。该 strong branching 丢失最优子树的问题尚未在本轮修复。

还需注意，当前 runner 的 ALNS seed 由文件名参与哈希。原文件与 `_timeX10` 文件名不同，导致原尺度和十倍尺度的初始启发式列不完全一致，例如 time-indexed 初始 pool 分别为 `13` 和 `5`。因此节点轨迹和 incumbent 不能视为严格同随机种子；不过 root LP 下界仍从 `15611.75` 精确放大为 `156117.5`，time-indexed 图规模与定价耗时的变化仍可用于判断 horizon 敏感性。后续正式对比应将两个实例放在不同目录但使用相同文件名，或给 runner 增加显式固定 seed。

#### Root time-indexed arc fixing 比例复核

前一组 `scale-safe` 命令误将 `timeIndexedCompletionBoundArcFixing=false` 显式传给 runner，因此没有留下十倍实例的 root 永久时空弧固定统计。本次重新把原尺度和十倍数据放在不同临时目录，并统一命名为 `wet040_001_3m.dat`，从而保证初始解和 ALNS 使用相同随机种子。两组均采用纯 `TimeIndexedGraphPricing`、无 SRI、无 strong branching、无旧启发式 pricing、dual window 仅用于 pricing、永久 arc fixing 开启，并设置 `maxNodes=1`，只处理第一个 root。

| 尺度 | fixing horizon | candidates | process fixed `(i,j,t)` | idle fixed | end fixed | direct fixed | cleanup fixed | total fixed | 全部结构弧槽位 | 总固定比例 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 原尺度 | 1,385 | 2,228,678 | 2,000,556 | 43,841 | 48,016 | 2,092,413 | 203,984 | 2,296,397 | 2,329,825 | 98.5652% |
| 时间乘 10 | 13,849 | 22,270,339 | 20,013,248 | 437,569 | 479,899 | 20,930,716 | 2,025,075 | 22,955,791 | 23,281,809 | 98.5997% |

这里的全部结构弧槽位按 root fixing 实际图结构计算。40 个任务下，process 弧为 `1600(H+1)`，idle 弧为 `41H`，end 弧为 `40(H+1)`。`candidates` 只统计 reduced-cost fixing 主循环实际检查的可达候选；`cleanupFixed` 还会删除主循环后变成不可达或不能到达终点的弧，因此日志中的 `total fixed` 可以大于 `candidates`，不能直接用二者相除作为固定比例。root 初始没有继承时空禁弧，而且 cleanup 会跳过已经固定的弧，所以 `total fixed / 全部结构弧槽位` 可作为本次 root 最终固定比例。

十倍实例并不是精确固定十倍数量。全部结构弧槽位为原来的 `9.9929` 倍，process 固定弧为 `10.0038` 倍，总固定弧为 `9.9964` 倍。差异来自离散 horizon 的端点取整，以及两组 root 的列生成轮数仍有少量差异；但 root incumbent、LP bound 和绝对 gap 均严格放大十倍，比较口径是对齐的。比例上，process 弧固定率从 `90.2127%` 升到 `90.3125%`，cleanup 后总固定率从 `98.5652%` 升到 `98.5997%`，没有因为时间放大而变弱，反而分别小幅增加约 `0.10` 和 `0.0345` 个百分点。

真正的问题仍是绝对规模。cleanup 后剩余时空弧从 `33,428` 增到 `326,018`，约为 `9.75` 倍；root exact pricing 也从 `4.445s/213` 增到 `75.915s/205`。因此 time-indexed arc fixing 在两种尺度下都能删除约 98.6% 的结构弧，但固定比例近似不变，无法抵消离散时间轴扩大十倍造成的剩余图规模膨胀。这比单看固定比例更能解释十倍时间实例的求解退化。

2. 已完成和中止结果

| 时间尺度 | 方法 | 状态 | obj/incumbent | bound | gap | solve/elapsed | nodes | pool | exact pricing |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 原尺度 | ng-DSSR，无 SRI | FINISHED | 15658 | 15658 | 0 | 107.928s | 169 | 68460 | 6.432s / 458 |
| 原尺度 | time-indexed，无 SRI | FINISHED | 15658 | 15658 | 0 | 94.767s | 128 | 161935 | 53.050s / 2774 |
| 原尺度 | time-indexed + SRI | FINISHED | 15658 | 15658 | 0 | 111.351s | 12 | 53006 | 10.484s / 832 |
| 原尺度 | ng-DSSR + SRI | 主动中止 | 15658 | 15652.666667 | 0.0341% | 约 264.7s | 处理到 node 29 | 21459 | 未形成最终汇总 |
| 十倍尺度 | ng-DSSR，无 SRI | FINISHED | 156580 | 156580 | 0 | 140.565s | 113 | 61867 | 26.353s / 289 |
| 十倍尺度 | time-indexed，无 SRI | 主动中止 | 156580 | 156303.333333 | 0.1767% | 446.342s | 处理到 node 40 | 90433 | 未形成最终汇总 |

原尺度 time-indexed 无 SRI 的 root bound 为 `15611.75`，root gap 为 `0.4035%`，root 约 `29.618s`，pool 为 `48641`，root 正值列 `16` 条，其中 `15` 条 elementary、`1` 条 non-elementary。加入 SRI 后 root bound 提高到 `15639.301208`，root gap 降到 `0.2277%`，节点数由 `128` 降到 `12`；但 root 时间增至 `61.506s`，cut/master LP 成本使总时间仍从 `94.767s` 增到 `111.351s`。

十倍尺度 time-indexed 无 SRI 的 root bound 为 `156117.5`，root gap 为 `0.4734%`，root pool 增至 `63923`，正值列为 `20` 条，其中 `18` 条 elementary、`2` 条 non-elementary。图的 horizon 约为 `13849`，单轮 pricing 约扫描 `1700万--2100万` 条时空弧，常见单次耗时为 `0.25--1s`，部分节点累计 pricing 达 `5--27s`。它在 node 38、约 `433.964s` 时才找到正确 incumbent `156580`；运行到 `446.342s` 仍有 `31` 个排队节点和 `0.1767%` gap。相较原尺度在 `94.767s` 完整闭合，十倍尺度已超过 `4.7` 倍时间仍未完成，趋势已经足够明确，因此按讨论主动停止，没有继续运行十倍尺度的 time-indexed + SRI。

3. 当前结论

本次结果进一步支持已有判断：较小离散时间范围下，time-indexed 即使使用 relaxed/pseudo 列，也能依靠很便宜的图定价快速闭合；当 processing、due date 和 setup time 同步放大时，root gap 本身没有显著恶化，但状态数、时空弧扫描和后续节点的重复定价成本快速增长，整体性能明显退化。ng-DSSR 的 exact pricing 也由 `6.432s` 增到 `26.353s`，但总时间只从 `107.928s` 增到 `140.565s`，对时间尺度的敏感性明显低于 time-indexed。

上述原始实验轮次的不足也需要保留：ng-DSSR + SRI 原尺度未闭合，十倍尺度的两组 SRI 未运行；不同文件名造成 ALNS seed 不同；当时 strong branching 仍存在可复现的错误剪枝。因此该轮证据足以支持 horizon 敏感性结论，但不能直接作为四种方法在严格统一随机口径下的最终论文表格。strong repair 后续已修复，修复后的十倍 time-indexed 对照见下节。

#### Strong repair 修复后的十倍 time-indexed ±SRI 对照

前述 strong branching 错误修复后，重新按当前有效配置完整求解十倍实例。两组都开启 ALNS seed、two-stage strong branching、lightweight repair、dual-bound pruning、永久 time-indexed arc fixing 和 cut-loop fixing，关闭旧 HeuristicPricing、RMIH、route enumeration、每轮 scalar/window helper 及 in-round fixing；SRI 组仅额外开启 `TimeIndexedGraphRank1CutPricing` 和 time-indexed subset-row cuts。两组均得到 `obj=bound=156580`、`valid=true`。

| 方法 | solve | nodes | root bound | root gap | root total | root LP | root pricing | final pool | final exact |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| time-indexed，无 SRI | 238.806s | 25 | 156117.500000 | 0.4734% | 177.498s | 45.426s / 297 | 109.417s / 257 | 79885 | 126.175s / 639 |
| time-indexed + SRI | 315.723s | 7 | 156354.545455 | 0.3222% | 241.782s | 91.183s / 395 | 109.537s / 355 | 68188 | 130.772s / 616 |

SRI 的作用是明确的：root gap 降低约 `0.1512` 个百分点，节点由 `25` 降到 `7`，最终列池也减少约 `14.6%`。但它没有降低 root 的 time-indexed pricing 总时间，反而使 root LP 时间由 `45.426s` 增至 `91.183s`，root 总时间增加约 `64.3s`；最终总时间增加 `76.917s`，即慢约 `32.2%`。因此在该十倍 horizon 的 40-3 实例上，rank-1 cuts 能明显强化搜索树，但当前 cut-loop 和带 cut RMP 的额外成本仍大于节点缩减收益。实验日志分别为 `tmp-40m3-x10-ti-goodcfg-nosri-20260714` 和 `tmp-40m3-x10-ti-goodcfg-sri-20260714`。

当前无 SRI 的 `238.806s` 不能直接与前述 `446.342s` 中止记录解释为“同配置自然波动”。旧 run 同时关闭了 strong branching、lightweight repair，并误将永久 `timeIndexedCompletionBoundArcFixing` 关闭；其子节点日志一直是 `timeWindowJobs=0`，运行到 node 40 仍有 31 个排队节点。当前 run 修复 strong repair 后使用 strong branching，并在 root 闭合后把时空 arc fixing/compact window 传给子节点，最终只处理 25 个节点。因此提速来自搜索树和子节点图同时缩小，现有数据不能把收益单独分摊给 strong branching 或永久 fixing。

同一十倍实例已有两条有效 ng-DSSR 结果。关闭 strong branching 时为 `140.565s/113 nodes`，是目前最快的有效记录；strong repair 修复后开启 strong branching为 `173.493s/17 nodes`，`exact=11.827s/57`、`heuristic=29.250s/183`、`master LP=24.064s`、`pool=50145`，同样得到 `obj=bound=156580, valid=true`。强分支虽然把节点压到 17 个，但 trial 成本没有被节点缩减抵消。因此按总时间，本例 ng-DSSR 仍快于当前 time-indexed：无 strong 的最好记录快约 `41.1%`，strong-on 的同口径记录快约 `27.3%`。

### 2026-07-14：60-2 ng-DSSR node3 长尾与可选处理

60-2 最新对比中，纯 time-indexed 已闭合到 `obj=bound=36803`，总时间约 `1568.207s`，而 ng-DSSR 在 root 和 node2 后进入 node3 长尾。当前 node3 的 LP 值长期停在 `36752.000000` 附近，exact pricing 仍反复找到很小的负 elementary 列，例如 `-3.74`、`-0.418`、`-0.057`、`-0.025`、`-1.0` 等；单次 exact 多为数秒，后期可到 10 秒级，主要耗时在 forward/backward label expansion，而不是 master LP 或 join。这个现象不是明显 correctness 问题，而是 exact certificate tail：RMP 已接近闭合，但仍需要很多轮加入很小影响的负列才能严格证明无负列。

当前最直接的处理方向有三类。第一类是精确口径下的低风险调参：提高每次 exact pricing 返回的负列数量，避免数秒级 exact 只返回 1--2 条弱负列；同时在同一 node 上连续多次 heuristic pricing 失败或只产生极弱列时，临时跳过普通 heuristic pricing，让 exact pricing 承担闭合证明。第二类是近似/实验口径：当 LP objective 长时间不变且 best reduced cost 只剩很小负值时，引入 `eps` 级 early-stop 或 tail tolerance，但这只能用于启发式实验或候选配置筛选，正式精确结果必须最后用 `eps=0` 重新验证。第三类是混合策略：整数小 horizon 下 time-indexed 本身很强时，可以把 time-indexed 作为主算法或先用于给 ng-DSSR 提供 incumbent / root 信息；但 time-indexed relaxed 不能直接替代 elementary exact pricing 证书，除非明确改变算法口径。

因此，当前建议不是继续在 node3 上盲目等待，而是先做一个小的 A/B：保持当前所有正确性增强不变，只比较“exact 每轮多返回列 + node 内 heuristic tail skip”是否能减少 node3 的 pricing 轮数；若只是为了实验结论，当前已经能说明 60-2 这类实例上 time-indexed 在小整数时间下更适合，ng-DSSR 的瓶颈变成严格 elementary 证书的尾部收敛成本。

### 2026-07-14：50-3 原始算例 ng-DSSR 与 time-indexed 最新配置对比

本次用原始 `data/50-3/wet050_003_3m.dat`，分别按当前 no-SRI ng-DSSR 最好配置和纯 time-indexed 最好配置求解。第一次 `20260714a` 启动失败，原因是 `run.cmd` 被写成 ASCII 后中文路径变成 `D:\??\...`，该目录只作为失败启动证据，不作为实验结果。有效结果来自 `20260714b`，该轮使用 PowerShell 直接 `Start-Process java.exe` 传 Unicode 参数和重定向，避免 cmd 文件编码问题。

| 方法 | status | obj | bound | nodes | pricing | pool | solve | root | exact | heuristic | master LP | valid |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| time-indexed | FINISHED | 26527 | 26527 | 16 | 644 | 90156 | 187.528s | 97.255s | 16.139s / 627 | 0 | 98.060s | true |
| ng-DSSR | FINISHED | 26527 | 26527 | 10 | 1130 | 70399 | 318.659s | 107.562s | 9.601s / 53 | 30.164s / 151 | 67.971s | true |

结论是，在这个 50-3 原始小整数时间算例上，time-indexed 明显更快，虽然列池更大、master LP 时间更高，但它的图定价很便宜，整体仍只用约 187.5s。ng-DSSR 的 exact pricing 本身不重，只有约 9.6s；主要差距来自启发式、强分支和较多 pricing/LP 循环。这个结果继续支持前面的判断：对原始紧窗口、小整数时间实例，time-indexed 的 relaxed 图方法更适合；ng-DSSR 的优势更可能出现在 horizon 放大、小数 scale、宽 due window 或 pseudo-schedule gap 变差的场景。
### 2026-07-14：50-3 原始算例 ng-DSSR 耗时拆解

针对 `test-results/bpc/exp-50-3-ng-best-latest-001-20260714b`，ng-DSSR 在原始 `wet050_003_3m` 上最终 `318.659s` 闭合，`obj=bound=26527`，处理 `10` 个节点，root 用时 `107.562s`。这次耗时分布说明，当前瓶颈并不在 ng-DSSR exact pricing 本身，而主要在 strong branching 的启发式试探和 trial LP。

显式统计中，`GCNGBBStyleNgDssrPricing` 主 exact pricing 只有 `9.601s / 53` 次，约占总时间 `3.0%`；即使把 repair 中的 `GCNGBBStyleNgDssrPricing[FindFeasible]=4.259s / 5` 算上，exact 也不是主因。相反，`HeuristicPricing[strongBranching]=101.065s / 669` 是最大单项，约占总时间 `31.7%`。strong branching 相关 LP 也很重：`strong_branching_light_repair_rmp=28.684s`、`strong_branching_phase2_initial=5.721s`、`strong_branching_after_heuristic=3.201s`，合计约 `37.6s`。因此 strong branching 显式成本约 `138.7s`，约占总时间 `43.5%`。

其余主要耗时为 master LP 总计 `67.971s`，普通 `HeuristicPricing=30.164s / 151`，RMIH `22.285s / 7`。`TimeIndexedGraphPricing=5.552s / 247`，只占较小部分。显式统计合计约 `241.8s`，剩余约 `76.9s` 主要来自 ALNS seed（本配置允许最多 60s）以及 BPC 框架、列池、强分支调度等未细分开销。

从节点看，较重节点如 node5、node7 的时间主要仍是启发式和 LP：node5 `27.389s` 中 pricing `13.583s`、heuristic `12.429s`、exact `1.154s`、LP `7.515s`、RMIH `4.021s`；node7 `22.759s` 中 pricing `13.398s`、heuristic `12.531s`、exact `0.867s`、LP `3.704s`、RMIH `4.020s`。这些数字进一步说明，在该算例上继续抠 ng-DSSR exact 内部常数收益有限；更有价值的 A/B 是降低或关闭 strong branching phase2 heuristic、减少 strong trial 数量、评估 RMIH 是否值得保留，以及把 ALNS 上限从 60s 降到更小值做对照。

补充修正：上述耗时拆解中的“剩余约 76.9s”不能笼统归为 ALNS。按 node summary 的时间线反推，node1 结束时 total=150.306s、node1 自身 nodeTime=51.713s，因此进入 root 节点求解前已有约 98.593s。这里包含 time-indexed root preprocessing 的整体 `ms=39449.734`，而 summary 中的 `TimeIndexedGraphPricing=5.552s` 只统计了其中图 pricing engine 的调用时间，不覆盖预处理内的 LP 循环、图 fixing、窗口写回和 orchestration。扣除这约 39.45s 后，剩余约 59s 才主要对应 ALNS seed、initial column builder 以及未细分的启动/构造成本。节点阶段从 node1 到 node10 的 nodeTime 合计约 220.06s，二者相加与 `318.659s` 总时间吻合。因此当前 run 的完整粗拆应为：node 前初始化/预处理约 98.6s，节点求解约 220.1s；节点求解中显式最大项仍是 strong branching phase2 启发式和 strong trial LP。

### 2026-07-14：50-3 关闭 time-indexed root preprocessing 与 strong phase2 的 ng-DSSR A/B

按讨论在 `wet050_003_3m` 上做了一组最小变量 A/B。基线为 `exp-50-3-ng-best-latest-001-20260714b`：开启 time-indexed root preprocessing，并开启 two-stage strong branching 的 phase2 heuristic。新 run 为 `exp-50-3-ng-no-tiroot-phase1only-001-20260714`：仅关闭 `timeIndexedRootPreprocessingForNgDssr`，并设置 `strongBranchingPhase2CandidateLimit=0`，即 strong branching 只采用 phase1 的 trial RMP 信息，不再进入 phase2 heuristic。其余主要配置保持一致，包括 ng-DSSR、启发式 pricing、RMIH、completion bound、time-indexed scalar/window helper、midpoint probe、dual-bound pruning 和 ALNS seed。

结果如下：

| 配置 | solve | root | nodes | pricing rounds | pool | exact | heuristic | master LP | RMIH | valid |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| baseline：ti-rootpre + phase2 | 318.659s | 107.562s | 10 | 1130 | 70399 | 9.601s / 53 | 30.164s + SB heuristic 101.065s | 67.971s | 22.285s | true |
| no ti-rootpre + phase1 only | 307.572s | 103.374s | 10 | 326 | 22083 | 11.537s / 59 | 75.009s | 128.823s | 24.983s | true |

新配置确实去掉了 strong phase2 heuristic：summary 中不再出现 `HeuristicPricing[strongBranching]`，branch 日志显示 `phase=phase1`。但是一阶段 trial RMP 变得很重，`strong_branching_light_repair_rmp` 从 baseline 的 `28.684s / 268` 上升到 `123.271s / 256`，平均每次从约 `107ms` 上升到约 `482ms`。原因是关闭 root preprocessing 后，root 不再继承 compact window 和 200 条 elementary seed，虽然省掉了约 `39.45s` 的预处理，但 root 初始从 8 条列开始，普通启发式和 phase1 trial 都在更弱的窗口/列池口径下运行。root 收敛后仍能通过 node 内 time-indexed scalar arc fixing 获得时窗，但第一个 root 分支前的 trial LP 已经付出了较高成本。

因此这组 A/B 的结论不是“phase2 一定没用”，而是：在该 50-3 原始算例上，关闭 phase2 heuristic 能消除约 `101s` 的 strong heuristic 显式成本，但关闭 root preprocessing 会显著加重 phase1 trial LP 和普通 heuristic，二者抵消后只从 `318.659s` 降到 `307.572s`，约快 `3.5%`。如果要继续拆清楚，应再跑一个只关闭 phase2、保留 time-indexed root preprocessing 的 A/B；这能单独判断 phase2 heuristic 是否值得保留。

补充解释：no ti-rootpre 版本在 node1 收敛后 `avg reachablePts=604.4`，明显弱于 baseline 预处理阶段的 `392.5`，并不是因为 root LP bound 更弱。no ti-rootpre 的 node1 LP bound 实际同为 `26485.625`，且用于 fixing 的 gap 为 `300.375`，比预处理 graphFix 的 `306.651` 还略小。差异来自 fixing 口径：baseline 的 root preprocessing 先跑完整 time-indexed root，再做 paper time-indexed reduced-cost arc fixing，固定 `4,334,271` 条 raw 时空弧；随后 scalarFix 虽然 `fixed=0`，但它是在这些 raw 禁弧已经生效后的图上统计 reachable 点，因此平均 reachable 点只有 `392.5`。no ti-rootpre 版本没有这一步 paper graphFix，只在 ng-DSSR root 收敛后做 `ng-DSSR time-indexed scalar helper arc fixing`，固定 `3,969,198` 条时空弧。全局 lower bound 略强不代表每条 `(i,j,t)` 的 conditional lower bound 更强；arc fixing 需要的是“经过该时空弧”的条件下界，paper time-indexed graphFix 比 scalar helper 更细，所以能额外固定约 `365k` 条时空弧，最终窗口更窄。

strong phase1 LP 暴涨也来自同一个原因。baseline 有 root preprocessing 后，root branching 前的 restricted 列只有 `2633`，root phase1 trial 的 `restrictedCols` 常见为 `1380--2540`；no ti-rootpre 版本 root branching 前 restricted 列为 `15053`，phase1 trial 的 `restrictedCols` 常见为 `4991--12598`。因此即使关闭 phase2 heuristic，phase1 的 lightweight trial RMP 也变成大模型，`strong_branching_light_repair_rmp` 从 `28.684s/268` 上升到 `123.271s/256`。这说明 root preprocessing 的收益不只是 seed 列和预处理时间窗，还显著压低了 strong branching trial RMP 的列规模。

### 2026-07-14：补充说明 ng-DSSR root bound 与 time-indexed arc fixing 数量的关系

进一步修正一个容易误读的点：不能只用“ng-DSSR root 下界更强”推断它的 time-indexed arc fixing 一定更多。当前这组 A/B 中，baseline 预处理阶段调用的是 `TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing()`，即 paper time-indexed reduced-cost graph fixing；no ti-rootpre 版本在 ng-DSSR root 收敛后调用的是 `TimeIndexedScalarCompletionBound.applyArcFixing()`，即 scalar helper。两者不是同一个 fixing 程序。前者在完整 time-indexed relaxed graph 上用正反向最短路直接判断每条 `(i,j,t)`，后者是 ng-DSSR 主线的辅助缩窗/时空禁弧写回路径，强度和输入图口径都不同。因此这组数字更准确的解释是：time-indexed 预处理中的 graphFix 本身比后续 scalar helper 更细，先固定了大量 raw 时空弧；随后 scalarFix 是在已删图上统计 reachable 点，而不是靠 scalarFix 自己额外固定。

即使未来把两边统一成完全同一个 graph fixing，也仍然不能只看全局 LP bound 单调判断固定数量。arc fixing 判断的是“经过某条时空弧”的条件下界，形式上取决于 `forward(i,t) + arc(i,j,t) + backward(j,t')` 与当前 gap 的比较。全局 root bound 更高只说明当前 LP 对偶目标更好，不说明每条 arc 的条件 reduced-cost 下界都更高；不同 root 列族会给出不同 job dual、arc dual 和 machine dual，可能使部分局部弧的条件下界变弱。这里 no ti-rootpre 的 gap 确实更小（`300.375` 对 `306.651`），但只改善约 `6.28`，不足以抵消 dual 分布和 fixing 口径差异。后续若要验证“同一 graphFix + ng-DSSR dual 是否能固定更多”，需要新增一组明确实验：在 ng-DSSR root 收敛后也调用 paper graphFix，而不是拿当前 scalar helper 的结果和 root preprocessing 的 graphFix 直接比较。

### 2026-07-14：ng-DSSR 的 time-indexed scalar helper 与后接 paper graphFix 的可行性

`ng-DSSR time-indexed scalar helper arc fixing` 不是 paper graphFix 的别名。它在普通 ng-DSSR 节点收敛后由 `Tree.applyTimeIndexedScalarCompletionArcFixing()` 调用，入口为 `TimeIndexedScalarCompletionBound.applyArcFixing()`。该流程用当前 LP 解作为 node lower bound、当前 incumbent 作为 upper bound 得到 `gap=UB-LB`，再用原始 hard window 与 node 已继承的 compact window 取交集，按当前 LP 的 job/machine/arc dual 构造一个 time-indexed relaxed DP。它先计算 forward/backward reduced-cost 距离，再逐条检查 process/wait/end `(from,to,t)`，若 `forward + arc + backward >= gap`，则把该时空弧写入本 node 的 pricing-only forbidden set；之后重新计算可达点并把每个 job 的 reachable hull 写回 compact window。整数时间实例下会写回 raw time-indexed forbidden arc 和 compact window；非整数口径不能安全写回硬窗/时空弧。

paper graphFix 的入口是 `TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing()`，当前主要用于 time-indexed exact pricing 或 root preprocessing。它同样用当前 LP dual 和 `gap=UB-LB`，但口径是论文式 time-expanded graph fixing，并带有 cleanup 和普通弧 promotion 的后续使用方式。若要验证“ng-DSSR root dual 下 paper graphFix 是否比 time-indexed root preprocessing 更强”，技术上可以做：在 ng-DSSR root 收敛后、普通 scalar helper 前后，临时用 copied config 打开 `useTimeIndexedGraphPricing=true` 调用 paper graphFix，输入仍为当前 ng-DSSR 的 `LP` 和 incumbent。该实验应先限制在 no-SRI/no-cut root；有 active SRI cut 时 paper graphFix 当前不含 cut 状态，不能直接当完整 SRI-aware fixing 使用。这样得到的结果才与 root preprocessing 的 graphFix 同口径，能判断差异来自 dual 还是来自 fixing 程序本身。

### 2026-07-14：paper graphFix 的 cleanup 为什么会进一步缩小窗口

进一步拆分 50-3 A/B 后，确认 paper graphFix 与 scalar helper 的第一轮 reduced-cost arc fixing 其实非常接近。baseline preprocessing 的 graphFix 分项为 `processFixed=3,871,165`、`idleFixed=56,476`、`endFixed=71,175`、`cleanupFixed=335,455`；no ti-rootpre 的 scalar helper 分项为 `processFixed=3,845,475`、`idleFixed=54,259`、`endFixed=69,464`。因此直接 reduced-cost 条件固定的差距约 `29,618` 条，而总差距约 `365,073` 条，其中绝大部分来自 graphFix 的 `cleanupGraph()`。

这个 cleanup 不是只做统计或 cosmetic 后处理。它会在第一轮删弧后重新计算 forward/backward 可达性，然后删除已经没有可达 prefix/suffix 的 process arc、无 suffix 的 wait arc、不可达状态的 end arc，以及“当前已经可以结束且后续没有任何有用处理弧”的等待弧。等待弧一旦被删除，`(job,t)` 到更晚时间的可达链会断开，所以后续根据可达状态统计 compact window 时，窗口确实会进一步变窄。换句话说，paper graphFix 多出来的 cleanup 会改变后续 reachable graph；这正是 preprocessing 后 `avg reachablePts=392.5` 明显小于 no-ti-rootpre scalar helper `604.4` 的主要原因。

由此看，如果目标是在 ng-DSSR root dual 下获得同口径加强，有两个实现方向：一是 root 收敛后直接临时调用 paper graphFix，再用 scalar helper 更新 compact window；二是把 cleanupGraph 等价逻辑移植到 scalar helper。前者更利于 A/B 验证，后者更像主线代码整理。无论哪种，都应先限制在 no-SRI/no-cut root 做验证。

### 2026-07-15：scalar helper 接入 graphFix cleanup 后的验证

按前面 A/B 的判断，`TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing()` 与 `TimeIndexedScalarCompletionBound.applyArcFixing()` 的主要差异不是第一轮 reduced-cost arc fixing，而是 paper graphFix 后续的 `cleanupGraph()`。本次已把等价 cleanup 逻辑移植到 no-SRI scalar helper：第一轮 process / wait / end 时空弧固定后，重新计算 forward/backward，然后删除已经没有可达 prefix/suffix 的处理弧、等待弧、结束弧，以及“当前已经可以结束且后续没有有用处理弧”的等待弧。该 cleanup 仍写入 helper 的 local fixed bitset，最后统一通过原有 `writeLocalFixedArcsToNode()` 写回 node，因此不改变 node 存储口径；SRI-aware helper 暂不改动，只在统计中显示 `cleanup=0`。

烟测使用 `test-results/bpc/tmp-scalar-cleanup-smoke-50-3-20260714b`。在 `wet050_003_3m` 的 no-ti-rootpre 口径下，node 1 的 scalar helper 现在输出 `fixed=4305198`、`cleanup=336000`、`avg reachablePts=427.1`。修改前同一口径约为 `fixed=3969198`、`avg reachablePts=604.4`；而 root preprocessing 的 paper graphFix 口径约为 `cleanupFixed=335455`、`avg reachablePts=392.5`。因此，本次修改基本补上了 scalar helper 与 paper graphFix 之间最主要的 cleanup 差异，reachable window 已明显接近 preprocessing 口径。剩余 `427.1` 与 `392.5` 的差距仍可能来自 root preprocessing 与 ng-DSSR root 的 dual / graph 口径差异，而不是 cleanup 缺失本身。

验证方面，focused `javac` 已通过，`TimeIndexedGraphOptimizationTest` 已通过；烟测日志显示后续 node 也会继续输出 `cleanup=...`，例如 node 2 `cleanup=11033`、node 3 `cleanup=10694`。这说明 cleanup 不只是 root 的一次性统计，而是已经进入 ng-DSSR 后续 node 的 scalar helper 写回流程。

#### 多轮 cleanup 的补充测试

为确认 cleanup 是否还存在进一步传播，本次临时把 no-SRI scalar helper 改为最多 8 轮 cleanup，并在日志中输出 `cleanupRounds`，随后用同一 `wet050_003_3m` no-ti-rootpre smoke 口径测试。node 1 输出为：`cleanup=344868, cleanupRounds=336000/8868/0, fixed=4314066, avg reachablePts=427.1`。这说明第一轮 cleanup 后重新计算 forward/backward，第二轮确实还能额外删除 `8868` 条时空弧，第三轮归零。

不过这 `8868` 条只占 node 1 总时空 fixing 的很小比例，且 `avg reachablePts` 与单轮 cleanup 的 `427.1` 相同，没有进一步缩小 compact window；耗时则从单轮 smoke 的约 `365ms` 增至本次约 `450ms`。因此当前判断是：多轮 cleanup 在图结构上确实更闭合，但在该算例 root 上主要带来少量额外 raw arc 删除，对硬时间窗没有可见增益。主线暂时恢复并保留单轮 cleanup；后续如果要启用 fixed-point cleanup，建议先做成开关或最多两轮，并只在第二轮删除量足够大时继续。

#### 多轮 cleanup 保留到主线

在前述 `cleanupRounds=336000/8868/0` 的测试基础上，当前主线保留多轮 cleanup。实现上仍只作用于 no-SRI scalar helper：第一轮 reduced-cost fixing 后先重算 forward/backward，然后最多执行 8 轮 cleanup；每轮若有新增删除，就把新增时空弧写入 local fixed bitset 并重算 forward/backward；某轮为 0 时立即停止。summary 中保留 `cleanupRounds`，便于后续判断各 node 是否确实需要多轮传播。

当前选择保留的原因是第二轮确实存在传播收益，且在 50-3 smoke 中额外成本约几十毫秒量级，不会成为主要瓶颈。需要注意的是，这仍是 no-SRI relaxed 图口径的结构清理；SRI-aware helper 未接入该 cleanup，相关统计中 `cleanupRounds` 为空。
