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
