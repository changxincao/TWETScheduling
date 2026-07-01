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
