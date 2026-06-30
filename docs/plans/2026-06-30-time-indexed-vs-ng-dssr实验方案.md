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
