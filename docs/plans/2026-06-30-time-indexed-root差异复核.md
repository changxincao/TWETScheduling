# time-indexed root 求解差异复核

## 问题

2026-06-30 重新复核 pure time-indexed pricing 在 `wet040_001_2m` 根节点上的表现。前面曾观察到旧记录里 root 很久不闭合，而当前 no-cut time-indexed 可以很快闭合，因此需要确认差异到底来自 time-indexed shortest-path 本身、启发式 pricing 混入，还是后续代码修正。

## 日志口径

这次复核后需要修正前一版记录的误读：`test-results/bpc/tmp-timegraph-40-2-heurOff-20260620-1642b` 名字里有 `heurOff`，stdout heartbeat 也仍然显示 `pricing.HeuristicPricing.start`，但这只能说明当时外层仍进入了这个 pricing engine 的阶段，不等价于启发式真的加了列。该 run 的实验说明里明确是 `enableHeuristicPricing=false`，并且停在 root 时 restricted/pool 已到 `49394`，仍准备进入下一轮 `TimeIndexedGraphPricing`。因此它就是需要解释的“关闭现有启发式后，纯 graph exact 很久没有 root 闭合”的旧证据。

可比记录分三类：

1. `test-results/bpc/tmp-timegraph-40-2-heurOff-20260620-1642b`

   6/20 旧 engine，关闭现有启发式。约 `246.5s` 后手动停止，仍在 node 1，最后 heartbeat 为 `restricted=49394, pool=49394, cuts=0`，仍准备进入下一轮 `TimeIndexedGraphPricing`。

2. `test-results/bpc/tmp-timegraph-noheur-engine-check-20260628`

   root-only / node-limit，`heuristic_s=0`，`TimeIndexedGraphPricing=7.218s/211 calls/add44524`，root time `23.079s`，root bound `22487.647059`，pool `44528`。

3. `test-results/bpc/tmp-timegraph-nocut-40-2-setup-nostrong-20260630`

   full solve，但 root 上同样没有启发式 pricing，root time `18.750s`，root bound `22487.647059`。node 1 的 `TimeIndexedGraphPricing` 也是 `211` 次、加列 `44524`、root final pool `44526`。

后两条在 root 上的调用序列、返回列数和最终 root bound 基本一致，只是 6/30 的每轮图定价略快一些。因此真正的断点发生在 6/20 旧 engine 到 6/28 修正后的 engine 之间。

## 核心原因

旧慢样本主要有两层原因，不能混在一起解释。

第一类是流程口径不同。6/20 的完整 timegraph run `tmp-timegraph-40-2-current-20260620-163616` 不是 pure graph pricing，它还混入了现有 `HeuristicPricingEngine`。该 run 完整结果为 `FINISHED, obj=22580, nodes=29, solve=628.307s`，其中总 `heuristic_s=461.353s/791 calls`，而 `TimeIndexedGraphPricing` 只有 `20.950s/231 calls`。所以这类慢主要是外层启发式和 RMP 循环成本，不是 time-indexed shortest path 本身慢。

第二类才是 `heurOff-1642b` 与当前 pure graph root 差距的核心。需要校正此前“列质量差”的说法：旧版调用 `TWETColumnEvaluator.evaluate(sequence)` 后，得到的是该 job sequence 在连续 TWET 口径下的最好成本，不能简单说旧版最终列质量更差。旧版真正的问题是热路径位置不对：它在候选列形成之前，对大量负 reduced-cost end state 逐个恢复 sequence 并调用 evaluator，而不是只对最终要返回的少量列做一次成本确认。

2026-06-30 对旧 commit `98522aa7` 临时加 profiling 后，证据很直接。`wet040_001_2m`、no heuristic、root-only 的第一轮 `TimeIndexedGraphPricing` 中，`negativeStates=119753`，最终只保留 `173` 个 candidate signature，但 `TWETColumnEvaluator.evaluate(sequence)` 被调用 `119753` 次，累计 `34262.869ms`；整轮 `forward=35370.673ms`，因此绝大多数时间都在 evaluator。第二轮同样明显：`negativeStates=66299`，最终保留 `300` 个 candidate signature，`evaluate` 调用 `66299` 次，累计 `81944.725ms`，整轮 `forward=82663.916ms`。这说明旧版慢的主因是“对海量候选 end state 过早做完整 sequence evaluator”，不是 DAG shortest path 本身慢，也不是最终列一定质量差。

这里的关键不是“完全没有 topK 粗筛”，而是 topK 粗筛放错了层级。旧版确实先用 `isPotentialTopCandidate(reducedCost)` 挡掉一部分 end state，但这个阈值依赖已经进入 candidate heap 的 signature 数量。第一轮最终 unique signature 只有 173 个，小于每轮返回上限 300，因此 heap 始终没有填满，`isPotentialTopCandidate` 基本挡不住任何负 end state，119753 个负 end state 都进入 evaluator。第二轮虽然最终保留 300 个 signature，但旧版是在 `rememberCandidate()` 里才处理同 signature 替换，位置在 evaluator 之后；同一个 sequence 的多个不同 end state 仍会先重复 evaluate，再被去重或替换。因此旧版不是“最终 topK 列 evaluate”，而是“end-state 层面粗筛后、unique topK 形成前 evaluate”。

6/28 的 `3ae94b26 Fix time-indexed pseudo column cost recovery` 主要消除了这条热路径：不再对每个候选 sequence 调用 evaluator，而是从 graph reduced cost 加回 machine/job/arc dual，直接反推出该图路径对应的 objective cost。这样每轮 pricing 的耗时从“候选数 × evaluator”降到主要由 DAG DP、候选去重和少量返回列管理决定。当前版本第一轮约 `0.18s` 的数量级，与旧版第一轮 `35s`、第二轮 `82s` 的差距，主要就是这个 evaluator 热路径差异。

顺带全局检查当前 `TWETColumnEvaluator.evaluate(...)` 的调用位置后，暂未发现第二处同类“先 evaluate 海量候选、再 topK/去重”的问题。当前 time-indexed no-cut 与 rank-1 cut pricing 都用 reduced-cost 反推 objective；GCNGBB/GCBB 系列先用 inferred cost 进候选堆，只在最终候选出堆后、且确实需要恢复真实成本时调用 `PricingColumnCostRechecker.evaluate(...)`，例如 root pi-window、SRI active 或 partial dominance。启发式 pricing 使用 profile 和局部增量，不走 evaluator 热路径。仍有两类较小的重算点需要记住：`RouteEnumerationEngine` 在显式启用 time-indexed window 枚举时会对通过 gap/duplicate 过滤的新列重算真实成本，这是为了不把窗口内受限成本写进 Pool；`RestrictedMasterIntegerHeuristic` 的 duplicate repair fallback 会对删点前后 sequence 调 evaluator 估算 cost reduction，若重复 job 很多可后续加缓存，但它只在 RMIH 修复阶段触发，不是 pricing end-state 扫描。

从 40-2 setup 当前结果看，修正后的 no-cut time-indexed pricing 明显快于 ng-DSSR 主线，这个现象本身是合理的。time-indexed 图的单轮 pricing 是离散 DAG 上的动态规划，状态和弧虽然多，但没有 PWLF 函数包络、双向 label join、ng-memory/DSSR 多轮加强、dominance graph 维护等连续时间 labeling 成本；修掉 evaluator 热路径后，pricing 本体就会非常轻。代价是它生成的是 pseudo-schedule / relaxed 图列，列数和 RMP 压力可能更大，且正确性更依赖后续分支、cut、arc fixing 与列成本口径对齐。因此这个结果不能直接推出 time-indexed 在所有规模和所有配置下都优于 ng-DSSR，但至少说明在 `wet040_001_2m` 这类实例上，当前 ng-DSSR 的瓶颈主要在连续时间函数 labeling 和收敛过程，而不是 master 本身；后续如果继续比较，应把 time-indexed no-cut 的当前版本作为有效 baseline，而不是再参考旧 evaluator 热路径版本。

随后按同一 no-cut time-indexed、关闭旧启发式 pricing、关闭 strong branching 的干净口径测试 `wet040_001_4m`，并构造 3/4/5 倍时间放大副本。放大规则为 processing time、due time 和 setup time 同乘对应倍数，权重不变。结果都能很快收敛：原始 40-4 为 `obj=11460, solve=29.186s, root=21.972s, exact=4.293s/143, pool=11405, nodes=7`；3 倍为 `obj=34380, solve=18.055s, root=10.582s, exact=6.486s/155, pool=9769, nodes=7`；4 倍为 `obj=45840, solve=40.387s, root=28.123s, exact=15.846s/142, pool=13408, nodes=7`；5 倍为 `obj=57300, solve=26.595s, root=13.835s, exact=14.035s/185, pool=14932, nodes=7`。总时间不随倍数单调增加，主要是 ALNS 初始列数量、incumbent 和分支路径随缩放发生变化；但 exact pricing 时间相对原始版本确实上升。当前结论是：time-indexed 图对时间层数放大有可见成本，但在 40-4 这个例子上没有出现 horizon 放大后的爆炸，pricing 仍然很轻。

## 当前结论

如果只讨论 no-cut pure time-indexed root，当前证据支持的结论是：6/20 `heurOff-1642b` 的确说明旧版 pure graph root 会长时间不闭合；6/28 之后变快的核心不是 CPLEX、分支或启发式，而是 time-indexed graph column 的候选成本恢复路径被改掉。更准确地说，旧版不是“最终列质量差”，而是每轮 pricing 在最终保留列之前对数万到十几万个候选 end state 做了完整 `TWETColumnEvaluator.evaluate(sequence)`，热路径过重；当前版用 reduced-cost 反推 objective，避免了这部分重复评估。root bound 本身没有变，`22487.647059` 是一致的。

因此后续再比较论文 time-indexed 方法时，应使用 6/28 之后的 pure graph 口径作为 no-cut baseline，不要再拿 6/20 `heurOff` 目录名直接判断。若要继续追问“旧 engine 具体 evaluator 调用了多少次”，需要回到旧 commit 加计数器重跑；现有日志不足以精确量化。

## 非均匀时间扰动测试

2026-06-30 进一步检查“时间尺度放大”对 time-indexed 图的影响。均匀放大 processing、due 和 setup 会主要按比例增加离散时间层，但不改变相对结构、紧张程度和各 job/arc 的波动，因此它更像是在测试图层数变多，而不是测试算例结构变难。为避免这个问题，构造了一个非均匀约 10 倍扰动版本：

`test-results/bpc/tmp-wet040-001-2m-time-jitter-x10-input-20260630/wet040_001_2m_timeJitterX10.dat`。

构造规则为：`p_j` 乘以 6 到 14 之间的 job 相关因子，`d_j` 乘以 7 到 13 之间的另一组 job 相关因子，`setup_ij` 乘以 5 到 15 之间的 arc 相关因子；随后对 setup 矩阵做 Floyd 闭包以保持三角不等式。该构造不会引入 setup cost，只改变时间结构。

用 no-cut time-indexed graph pricing、关闭旧 HeuristicPricing、打开 strong branching、30 分钟限制求解该扰动算例，结果为：

`FINISHED, obj=bound=104836, solve=1003.950s, root=242.385s, nodes=26, pricing=1509, exact=662.163s/1386, master_lp=193.277s, pool=157317, valid=true`。

这个结果和原始 40-2 setup 的几十秒量级形成明显对比。root 结束前 pool 已超过 7.6 万，后续强分支和 repair 持续把 pool 推到 15.7 万；node 15 时 gap 约 `0.5646%`，node 20 时 gap 约 `0.2697%`，最后 node 25/26 才闭合。因此非均匀扰动确实会显著削弱 time-indexed 的“很快”表现，慢化来源不是单个 shortest path 极慢，而是扰动后负列和候选序列明显增多，RMP/strong branching/repair 的累计成本随之上升。

当前结论是：time-indexed 图对均匀尺度放大不一定敏感，因为结构相对关系没有变；但对非均匀时间波动更敏感，尤其会放大 pseudo-schedule 列数量和强分支 trial 成本。后续如果要比较 ng-DSSR 与 time-indexed 的鲁棒性，应优先使用这种非均匀扰动实例，而不是简单整体乘同一个倍数。

## 非均匀扰动下的 ng-DSSR 对照

2026-06-30 用同一个 `wet040_001_2m_timeJitterX10` 扰动实例继续测试 ng-DSSR 主线。配置为 half-domain ng-DSSR、nearestK8/top10、ALNS、RMIH、completion bound、pricingOnly subtree、midpoint probe/reuse、dual bound pruning 和 strong branching；关闭 time-indexed graph pricing，打开 time-indexed helper 的 post-node scalar/window/arc-fixing 加强，但关闭每次 pricing 内的 in-round/cut-loop 临时 fixing。

结果目录为 `test-results/bpc/tmp-ngdssr-40-2-timeJitterX10-tihelper-postnode-strong-20260630`。CSV 结果为：

`FINISHED, obj=bound=104721, solve=439.190s, root=46.830s, nodes=40, pricing=2473, cols=237760, pool=237760, heuristic=52.336s/602, exact=82.016s/310, master_lp=138.296s, valid=true`。

和同一扰动实例的 no-cut time-indexed graph pricing 对比，ng-DSSR 总时间从 `1003.950s` 降到 `439.190s`，root 从 `242.385s` 降到 `46.830s`。这说明非均匀时间放大后，ng-DSSR 确实不直接受到离散时间层数膨胀的支配；它的 exact pricing 主要仍是连续时间函数 labeling、completion bound 和 join，而不是扫描整个 time-expanded graph。

但这次结果也暴露两个需要继续核对的问题。第一，ng-DSSR 找到的目标为 `104721`，低于前面 time-indexed graph pricing 的 `104836`。在同一实例和同一目标口径下，这意味着前面的 time-indexed 对照不能直接当作可靠最优值；后续要么检查 time-indexed 分支/arc fixing/列成本口径是否仍有漏列，要么用已知 `104721` 解反向审计 time-indexed 路径是否被剪掉。第二，ng-DSSR 虽然更快，但节点数更多、列池更大，最终 pool 达到 `237760`。从日志看，root 后的普通节点每个 node 多数只需几秒到十几秒，主要耗时来自 strong branching trial、repair 和启发式/精确 pricing 的反复补列；例如 node 19 的 repair 中 exact ng-DSSR 曾一次生成 1253 列，后续 pool 很快超过 15 万。

本次日志没有输出单独的 `timeWindowAvgLen/timeWindowAvgShrinkRatio` 字段，因此暂时无法给出“每个 node 时间窗平均收缩多少”的直接统计。能间接看到的是各节点 `pricingHorizon` 仍多次保持在 `19247.0`，说明当前记录的 horizon 字段并未充分反映 post-node compact window 的有效收缩；如果后续要系统判断 time-indexed helper 的窗口贡献，需要补一条专门统计，至少在 node summary 中输出继承 compact window 后的平均窗口长度、收缩比例和 job 数。

当前结论是：在这个非均匀扰动实例上，ng-DSSR 明显比 no-cut time-indexed graph pricing 更抗时间层数放大，且找到更好的解；但由于目标值不一致，下一步不能只比较速度，应优先复核 time-indexed 对照的正确性。

## 104721 incumbent 可行性与 time-indexed 强分支误剪

2026-06-30 继续复核 `wet040_001_2m_timeJitterX10` 上 ng-DSSR 的 `104721` incumbent 是否真实可行。开启 `twet.bpc.fullDomainCompare.incumbentColumnAudit=true` 重跑 ng-DSSR 后，最终 incumbent 由两条机器列组成：

第一条为 `[17, 11, 39, 21, 7, 35, 14, 3, 34, 6, 37, 2, 24, 9, 33, 22, 40, 15, 1, 29, 8, 13]`，stored cost 为 `42134.999999999960`，evaluator 复算为 `42135.000000000000`。第二条为 `[26, 25, 10, 28, 4, 32, 18, 38, 31, 20, 27, 23, 19, 36, 5, 12, 30, 16]`，stored cost 与 evaluator 复算均为 `62586.000000000000`。两列合计覆盖 1 到 40 的所有任务，缺失集和重复集均为空，总成本为 `104721`。因此该 incumbent 是原问题下可行解，不是 ng-DSSR 生成了非法上界。

随后对照 no-cut time-indexed strong branching 的日志定位错误路径。root 到 node 7 前的连续左支禁弧为 `(3,27),(20,2),(3,37),(22,30),(15,30)`，这些弧均不在 `104721` 的两条序列中，因此该可行解仍在当前子树内。node 7 对 `(32,18)` 分支，而 `104721` 的第二条序列明确包含 `32 -> 18`，所以它应该进入右支。但 time-indexed 日志中该候选的右支为 `rightBound=INF`，没有入队，后续搜索只能在不含该可行解的剩余子树里证明 `104836`。

代码层面的直接原因在 strong branching phase1 trial。右支的竞争弧现在通过 `branchImpliedForbiddenArc` 进入列兼容性和后续 pricing 过滤，不再建立 master 分支行。`solveStrongBranchingRmpTrial()` 先用父节点 seed 建 trial LP，然后调用 `resetRestrictedColumnsByCurrentReducedCost()` 筛掉不兼容列并重解；如果筛列后 LP 不可行，当前直接返回 `rmp_trial_infeasible_after_filter`，没有再通过完整 repair/pricing 重新补兼容列。这样得到的 `INF` 只能说明“当前筛后 restricted RMP 暂时缺列”，不能证明该分支子树不可行。node 7 的 `(32,18)` 右支正是这个 false infeasible 的实例。

当前结论为：`104721` 更优解可行，time-indexed no-cut strong run 的 `104836` 不是可信最优值；问题不在 time-indexed 图无法表示该列，而在 strong branching trial 将筛列后的 restricted RMP 不可行误当作子树不可行，并据此跳过了包含更优解的右支。后续修复应避免把 phase1/phase2 trial infeasible 直接用于剪掉 child，或在筛列后不可行时执行能够覆盖 coverage 与分支行的完整 repair，再把成功后的 seed/bound 作为可复用 trial 结果。

进一步查看旧 run 的 node summary 后，可以解释为什么 node 7 误剪后整体会很快收敛。旧 run 中从 node 2 开始，几乎每次 strong branching 选中的分支都有一侧 `rightBound=INF`，队列长期保持 `queue=2`，实际搜索更接近沿着一条单链往下走，而不是保留完整左右子树。node 7 的 `(32,18)` 右支被误判 `INF` 后，`104721` 所在子树被直接跳过；剩余人工缩小后的树里，LP bound 随深度从 `103299.142857` 很快抬到 node 23 的 `104771.583333`、node 24 的 `104831.375000`，最后 node 25 被 incumbent 剪掉、node 26 整数闭合到 `104836`。因此“快”主要来自错误剪掉大量分支，尤其是包含更优解的右支，而不是 time-indexed pricing 已经正确证明其它候选子树都差。

只修改 `resetRestrictedColumnsByCurrentReducedCost()` 使正值列无条件保留后，复跑同一配置时队列不再维持在 2，而是很快增长到 10 以上，并且 pool 超过 14 万仍未闭合。这说明旧 run 的快速收敛确实高度依赖强分支 trial 的错误 infeasible 传播；该最小修复能改变搜索树，但是否完全修好还需要继续看最终 objective 和是否仍存在筛列后 false infeasible。

最小修复后的复跑在 1800s 时间限制下没有闭合：`TIME_LIMIT,obj=104836,bound=103915.233333,exact=1288.937s/1929,pool=225006,valid=true`。与旧 run `1003.950s` 直接证明 `104836` 相比，新 run 的 queue 一度增长到 20 以上，且下界只到 `103915.233333`。这进一步确认旧 run 的快速闭合不是正常强分支证明，而是 trial infeasible 被当作真实子树 infeasible 后错误压缩了搜索树。当前“正值列无条件保留”只能修复一部分筛列问题，仍不足以让 time-indexed strong branching 可靠闭合；后续要么不要用 trial infeasible 剪子树，要么对筛列后 infeasible 的 child 做完整 repair 后再允许复用和入队判断。
