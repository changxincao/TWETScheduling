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

## 原始 40-2 time-indexed 结果是否受 strong branching 影响

2026-06-30 继续区分原始 `wet040_001_2m` 的 time-indexed 对照组。这里不能简单说“40-2 没放大所以没影响”，因为原始 40-2 的 time-indexed 记录里同时存在 no-strong 和 strong 两类口径。

no-strong 口径 `test-results/bpc/tmp-timegraph-nocut-40-2-setup-nostrong-20260630` 的结果为 `FINISHED,obj=bound=22580,solve=46.737s,nodes=35,pool=68359`。这组不走 strong trial 的 child 构造、筛列、repair 和复用 seed 逻辑，因此不受本次 strong branching false infeasible 问题影响。

strong 口径则已经出现可疑信号：`test-results/bpc/tmp-timegraph-nocut-40-2-setup-strong-20260630` 得到 `obj=bound=22582`，`strong-noarcfix` 也是 `22582`，`strong-noarcfix-fixedswitch` 为 `22581`。这些值都高于 no-strong 的 `22580`，说明原始 40-2 的 time-indexed strong 结果也不能再当作可靠最优证明，只是误差幅度比 `timeJitterX10` 上的 `104836` 对 `104721` 小得多，更容易被忽略。`valid=true` 只能说明最终 incumbent 列自身可行并且成本复算通过，不能证明强分支没有误剪更优子树。

当前结论为：原始 40-2 的 pure time-indexed no-strong 结果仍可作为无强分支 baseline；所有开启 strong branching 的 time-indexed 结果，包括原始未放大实例，都需要按修复后的 trial 逻辑重新验证，不能直接引用为最终最优性证据。

## 旧 VRP BranchD 分支流程复核

2026-07-03 进一步对照旧 VRP 源码 `BPC/BP/BranchD.java`、`BPC/LP/LP.java`、`BPC/LP/Tree.java` 和 `BPC/LP/PC.java`。旧代码里的 `range.setBounds(1,1)` 不是新建一条右支约束，而是复用左支时由 `ForceArcValue(i,j,0,0)` 建好的同一条 branch row。该 row 的表达式是当前 LP 中所有包含弧 `(i,j)` 的 route 变量之和，左支先把上下界设成 `[0,0]`，测试“不走该弧”；随后右支把同一个 `IloRange` 的上下界改成 `[1,1]`，测试“必须走该弧”。这是旧代码为了在同一个父 LP 对象上原地试探左右支而采用的工程写法。

旧 VRP 的完整流程是：`Tree` 弹出节点后，用该节点保存的 `route_set` 建 `Pool/LP`，调用 `PC.Solve()` 做列生成和 cut；若需要分支，`BranchD` 选择最接近 0.5 的分数弧。左支直接在当前 `lp.node` 上禁掉该弧并添加 branch row `[0,0]`，然后调用 `UpdateRouteSet()`；右支先复制父节点，再把同一起点的其它出弧和同一终点的其它入弧在 `right_node.feasible_arc` 中标成不可行，把目标弧标成 required，接着把刚才那条 branch row 改成 `[1,1]`，再调用 `UpdateRouteSet()`。`UpdateRouteSet()` 先解当前 LP 并检查 slack；若不可行，则只给当前 branch row 加一个大 M slack，然后调用启发式和 `GCNGBB.FindFeasible()` 补列；若成功，则从当前 LP route 中按 reduced cost 和 `feasible_arc` 兼容性筛选最多 `m_initial_col_number` 条 route 作为 child 的初始 `route_set`。后续 child 入队，真正弹出时再用这个 `route_set` 重建 LP 并重新列生成。

因此，旧代码能说明“正式 child 在入队前会尝试 repair 并筛一批可用列”，但不能把它理解成严格数学证明。原因有三点。第一，`UpdateRouteSet()` 有 `m_branch_col_number` 和 `m_initial_col_number` 这类工程上限，repair 或筛列失败并不等价于子树真实不可行。第二，旧代码筛 route 时并没有显式无条件保留正值列，虽然实践中 reduced cost 和已有解通常会保留足够列。第三，右支 repair 调用里传给 `FindFeasible()` 的仍是 `lp.node`，而 `lp.node` 已经在左支中被原地改过；过滤时用的是 `right_node`，这说明旧实现本身就是较强的原地复用工程写法，而不是两个完全干净独立的 child LP。结论是：分支语义本身是有效的，左支 `x_ij=0`、右支 `x_ij=1` 并配合竞争弧过滤可以划分解空间；但旧实现不能作为“当前 strong trial 的 restricted RMP 一旦 infeasible 就可以直接剪掉 child”的严格依据。当前 TWET 强分支若要用 trial infeasible 剪子树，必须确认它来自完整 exact repair/phase-I 证书，而不是筛列后的临时 restricted RMP 缺列。

这里还需要明确区分 branch row 和 cut。旧 VRP 的 `branch2rng` 只是当前 `LP` 对象里保存的 CPLEX row 引用，用来在同一个父 LP 上从左支 `[0,0]` 原地切到右支 `[1,1]`；它不会像 cut pool 那样作为全局 cut 持久保存到整棵树。真正传给 child 的主要是 `Node.feasible_arc`、bid bound 和筛出来的 `route_set`。因此旧实现和当前 strong trial 的共同风险在于：如果某个 side 的 trial/repair/screening 因为临时 restricted RMP 缺列或工程上限返回 infeasible/null，却被上层当成真实子树 infeasible，就会出现和当前 `INF` 误用类似的问题。旧代码在实践上靠较宽的 repair 与 route screening 缓解，但不是严格证书。

当前 TWET 的右支口径已经和旧 VRP 的分工基本一致：`ArcBrancher` 对右支调用 `requireArc(i,j)`，因此 `LP.buildArcBranchConstraints()` 只为被选中的 `(i,j)` 建一条 `sum lambda * a_ij = 1` 的 master row；同起点其它出弧、同终点其它入弧通过 `forbidBranchImpliedArc()` 进入 `branchImpliedForbiddenArc`，不会建成额外 master row，只通过 `Node.isArcForbidden()` 被 pricing、列兼容性过滤和后续筛列消费。左支则是显式 `forbidArc(i,j)`，会建 `sum lambda * a_ij = 0` 的 master row。旧 VRP 也是同样思路：真正建 row 的只有选中的 `(i,j)`，右支的竞争弧只写入 `right_node.feasible_arc=-1`，随后在 `UpdateRouteSet()` 和后续 pricing 中过滤。

## 2026-07-04 当前 strong branching 复核补充

这次复核首先尝试在当前代码上加一个默认关闭的 `strongBranchFilterAudit` 临时诊断，用来查看 strong trial 二次筛列前，LP 正值列中是否存在违反右支 `branchImpliedForbiddenArc` 的竞争弧。该诊断在当前代码下确实抓到了这种现象：例如某些 trial 中 required arc 行的 LP 值已经等于 1，但仍有若干正值列包含同起点其它出弧或同终点其它入弧，二次筛列会把这些列删掉。这说明“required 行满足 1 并不自动排除其它竞争弧列”这一点在 set-covering 主问题下确实成立。

需要纠正的是，本次真正需要解释的是当前求解中出现的 strong branching 误剪，而不是把重点放到 2026-06-30 历史版本。今天的关键证据是：当前代码里右支只对选中弧建立 `requiredArc(i,j)=1` master row，竞争弧通过 `branchImpliedForbiddenArc` 作为 compatibility / pricing 过滤存在；但在 phase1 trial 的初始 LP 中，旧列仍可能以正值使用这些竞争弧。随后二次筛列会删掉这些正值列，如果筛后 restricted RMP 不可行，当前不能把它解释成“右支子树真实不可行”。它只能说明这批筛后的 seed 列不够，或者 trial 结果不能复用，必须重新 repair 或放弃用该 trial infeasible 剪枝。

换句话说，今天的问题不是“required arc 的 slack 没修掉其它显式 forbidden rows”。当前代码里竞争弧已经不再建成 master row，因此那条历史解释不能作为当前结论。今天的问题是筛列口径：`requiredArc(i,j)=1` 在 `>=` 覆盖主问题下并不排斥同一组列里还同时覆盖 `i->k` 或 `h->j`，这些列可能只是被二次筛列删掉；删掉后模型不可行，不等价于原 child 不可行。这个机制和前面 `timeJitterX10` 上 node 7 的 `(32,18)` 右支 false infeasible 是同一类风险。

因此当前结论应改为：strong branching trial 的 `INF` 只有在经过完整、同口径的 repair/pricing 证明后，才可以当成真实不可行；二次筛列后的 restricted RMP infeasible 不能直接剪掉 child。当前代码应避免把这类 trial infeasible 写成可复用 child 的不可行证书；如果要保留 strong branching 的加速，只能把它作为候选评分失败、重新 repair 或继续按普通分支处理。旧版本 explicit forbidden row 的问题只作为背景保留，不能再作为今天这次错误的主解释。

### 2026-07-04 当天问题配置的直接验证

随后按今天出问题的 `wet040_001_2m`、partial dominance、no-SRI、time-indexed root preprocessing、strong branching 开启的同一配置，增加临时诊断 `strongBranchFilterAudit` 复跑。诊断只记录 strong trial 二次筛列前后的 LP 正值列是否违反当前 node 的 `isArcForbidden()` 或 `branchImpliedForbiddenArc`，不改变求解逻辑。复跑目录为 `test-results/bpc/tmp-verify-current-strongfilter-20260704`，对应原始问题记录为 `test-results/bpc/tmp-partial-tiroot-40-2-nosri-20260703`。

这次复跑抓到了确定证据。node 3 的某个 trial 在二次筛列前有 `positive=34,value=2.0`，其中 `incompatible=3,branchImplied=3,incompatibleValue=0.370240870`。具体正值列包括 `col=3,val=0.265734,arc=6->23`，`col=5912,val=0.002331,arc=6->23`，以及 `col=41616,val=0.102176,arc=23->38`，这些 arc 都是 branch-implied forbidden，而不是显式 master row。紧接着二次筛列后，该 trial 的状态变成 `status=INFEASIBLE`。同一个 node 后续 strong branching 选择 `arc(6,38)` 时给出 `rightBound=INF`，并把该 INF 用进候选评分。

这说明今天配置下确实存在如下机制：初始 trial LP 仍可用父节点遗留列满足覆盖和机器数等约束，同时 required arc 行也可以满足；但由于主问题是 set-covering 口径，`sum a_ij lambda = 1` 并不排除其它正值列里还出现同起点其它出弧或同终点其它入弧。二次筛列把这些 branch-implied incompatible 的正值列删掉后，restricted RMP 可能立刻不可行。这个不可行不是子树不可行证明，而是“筛后的 seed 列不足”。因此，今天的错误不是历史版本里“竞争弧作为显式 forbidden row 且没有 slack”的问题，而是当前版本里“branch-implied forbidden 只作为兼容性过滤存在，二次筛列后 infeasible 被误当成 trial 证书”的问题。

由此当前结论更精确为：strong trial 的二次筛列可以用于准备更干净的 child seed，但筛后 infeasible 不能直接作为 `INF` 参与剪枝或最终入队判断；至少应标记为 trial seed 不可用，回退到普通 child repair，或者执行完整 Phase-I/repair 后再判断。否则 strong branching 会因为临时 restricted RMP 缺列而错误跳过可行子树。

### 2026-07-04 M 惩罚修复口径

按照这次讨论，右支 strong trial 的处理改为：第一次筛选列仍保持现有轻量筛选口径，不直接删除父节点遗留正值列中的 branch-implied 竞争弧列；但在 lightweight strong branching trial 中，对仍使用 branch-implied 禁弧的内部列临时把目标系数改成 `big_M` 并重解一次 LP。若重解后 slack 为 0 且这些 `big_M` 列的正值总量为 0，说明当前 trial seed 已经能在不依赖竞争弧列的情况下满足该分支，随后再做二次筛列。若 slack 或 `big_M` 列仍为正，则该 trial 结果不再作为可复用 child，也不作为 `INF` 证明，而是标记为 `UNUSABLE`，正式入队时回到普通 child seed/repair 流程。

这里没有把 “M 列为正” 直接解释为整个子树 infeasible。原因是 strong trial 只是在当前受限列集上做快速试探；`big_M` 列为正只能说明这批 seed 不足以干净支撑该分支，不能证明完整 pricing 空间中不存在可行列。这个口径比“直接判不可行”保守，但能避免再次把 trial seed 缺列误当作子树不可行。

实现上新增了 `Node.usesBranchImpliedForbiddenArc(column)` 来识别包含右支竞争弧的列；`LP.penalizeBranchImpliedIncompatibleColumns(big_M)` 只在 strong trial LP 内临时改这些列的目标系数。`PC.solveStrongBranchingRmpTrial()` 在 lightweight repair 路径中加入该 M 惩罚重解；如果后续二次筛列仍导致 LP infeasible，也同样返回 `UNUSABLE` 而不是 `INF`。`Tree` 对 `UNUSABLE` trial 不再给伪无穷得分，也不复用该 trial 的 seed，而是按普通分支逻辑重新准备 child seed 入队。strong branching summary 现在额外打印 `leftMsg/rightMsg`，用于区分 `rmp_trial_infeasible`、`rmp_trial_infeasible_after_filter`、`branch_implied_penalty_positive` 等来源。

验证使用 `test-results/bpc/tmp-strong-mpenalty-verify4-20260704`，同样是 `wet040_001_2m`、partial/no-SRI、time-indexed root preprocessing、strong branching 开启的短时限配置。该 run `valid=true`，153.814s 内收敛到 `22581`；日志中 M 惩罚阶段执行 102 次，master LP 统计为 `strong_branching_light_after_branch_implied_penalty=2.880s/102 calls`。strong branching summary 未再出现 `rmp_trial_infeasible_after_filter` 被选中为 `rightBound=INF` 的情况；后续仍出现的 `INF` 来源为 `rmp_trial_infeasible`，即初始 trial/repair 层面的不可行，和这次修复的“二次筛列后 false INF”不是同一条路径。该结果说明本次修复命中了已证实的问题，但若后续还要进一步收紧 strong trial 的不可行证书，需要单独分析 `rmp_trial_infeasible` 是否也可能来自受限 repair 口径。

### 2026-07-04 strong branching M/slack 语义更正

前一版把 lightweight strong trial 中 `branch-implied` 竞争弧列被临时改成 `big_M` 后仍为正、或 repair slack 仍为正的情况标成 `UNUSABLE`，让正式 child 回到普通 seed/repair 流程。这个口径已经被更正：在当前设计下，M 惩罚重解后如果 slack 或 M 列仍然为正，说明该 trial 在当前分支语义下仍依赖违反右支竞争弧的列，直接按该 side 不可行处理，不再回退成正式 child。

同时，`22581` 的错误结果说明仍有另一条假 INF 风险：lightweight seed 的普通 `rmp_trial_infeasible` 可能只是筛出来的 seed 不够，而不是完整父列空间不可行。因此当前修复把两类情况区分开。若是 M/slack 正值，直接作为 INF；若是 lightweight trial 在普通 repair 阶段报告 `rmp_trial_infeasible`，则先用完整父节点 restricted columns 重新构造一次 trial 并复验，只有完整 seed 仍 infeasible 时才把它作为 INF 参与强分支评分和入队判断。

验证使用 `test-results/bpc/tmp-strong-mpenalty-certify-20260704`，配置为 `wet040_001_2m`、`halfDomain-ngPartial-nearestK4-top10`、no-SRI、partial dominance、time-indexed root preprocessing、strong branching 与 lightweight repair 开启。结果为 `FINISHED, obj=bound=22580, solve=208.150s, nodes=18, pool=125886, exact=4.002s/109, valid=true`。这与此前 `tmp-strong-mpenalty-verify4-20260704` 的错误 `22581` 相比，说明原来的 conservative fallback 和未复验的 lightweight INF 口径都不够清楚；当前口径恢复了已知正确最优值，但代价是不能再利用那条假 INF 快速闭合。

### 2026-07-04 strong branching 二次筛列口径再修正

继续复核后确认，“lightweight `rmp_trial_infeasible` 一律用完整父列复验”的处理过度扩大了问题范围。当前已经坐实的错误点更具体：strong trial 在 repair/M 惩罚阶段得到可行 LP 后，又调用 reduced-cost 二次筛列准备 child seed；如果这一步删除了当前 LP 正值列，筛后的 restricted RMP 可能变成 infeasible。这个 infeasible 只说明二次筛列破坏了当前可行支撑，不能作为子树不可行证明，也不应进入 strong branching 的 `INF` 评分。

因此当前实现改为：strong trial 的二次筛列一律保留当前正值列，包括 lightweight repair 口径下的正值列。M 惩罚重解后若 slack 或 branch-implied M 列仍为正，仍按该 side 不可行处理；但二次筛列本身只负责减小 seed，不再承担不可行证明。原先的“完整父列复验”辅助函数已经删除，避免把筛列问题解释成另一套 fallback 流程。

验证使用 `test-results/bpc/tmp-strong-mpenalty-keep-positive-filter-20260704`，同样配置下 240s 得到 `TIME_LIMIT, obj=22582, bound=22579, valid=true`。日志中 `rmp_trial_infeasible_after_filter` 不再出现，node 2 的右支从此前的 `rightBound=INF` 变为有限 `rightBound=22947.0`；剩余唯一 INF 来源为 `rmp_trial_infeasible:Repair RMP still has positive artificial slack after generating 4907 columns`，这属于 repair/exact pricing 仍未消除 slack 的另一类证书，和本次修复的“二次筛列后假 infeasible”不同。

### 2026-07-04 M 惩罚未生效与 repair 返回旧模型的最终定位

继续按同一 `wet040_001_2m`、`halfDomain-ngPartial-nearestK4-top10`、time-indexed root preprocessing、strong branching/lightweight repair 配置加临时审计后，最终确认前面关于 M 惩罚的解释还少了两个代码层面的事实。

第一，`LP.penalizeBranchImpliedIncompatibleColumns(big_M)` 确实会在当前 CPLEX 模型里把 branch-implied 竞争弧列的 objective coefficient 改成 `big_M`，但 `PC.solveStrongBranchingRmpTrial()` 随后调用的是 `solveRelaxationTimed()`。`LP.solveRelaxation()` 每次都会调用 `buildModel()`，而 `buildModel()` 会重新创建 `branchImpliedPenaltyColumnIds`，并按 `pool.getColumn(columnId).getCost()` 重建 objective。因此原来的 M 惩罚在下一次求解前就被模型重建冲掉了。直接证据是临时审计中出现 `node=2 penalized=11` 后，二次筛列前仍有 `col=3968` 以正值使用 branch-implied 禁弧。修复方式是 M 惩罚后改用 `resolveCurrentModelTimed()`，只重解当前模型，不重建 RMP。

第二，`repairInfeasibleMaster(lp, false)` 在 repair slack 归零后会先按 reduced cost 调用 `resetRestrictedColumnsByCurrentReducedCost()` 改写 `restrictedColumnIds`，再关闭 `feasibilityRepairMode`，但因为参数为 `false`，它不会求解这个“筛后、无 slack”的正式 RMP，而是把 repair slack 模型下的旧 solution 返回给 strong trial。后续 M 惩罚和二次筛列就可能基于已经和 `restrictedColumnIds` 错位的旧 CPLEX 模型继续操作。临时审计显示，修复 M resolve 后仍有一次 `rmp_trial_infeasible_after_filter`；把 strong trial 的普通 repair 改为 `repairInfeasibleMaster(lp, true)` 后，该类 after-filter infeasible 消失，原 node 4 右支改为明确的 `branch_implied_penalty_positive,mValue=0.08070089067270195`。

当前最终修复为两点：M 惩罚后只 `resolve` 当前模型；strong trial 中普通 repair 成功后必须返回筛后、无 slack 的正式 RMP 解。验证 run `test-results/bpc/tmp-strong-repair-final-fix-audit-20260704` 得到 `FINISHED,obj=bound=22580,valid=true,solve=186.389s,nodes=15`。关键日志计数为：`rmp_trial_infeasible_after_filter count=0`，`branch_implied_penalty_positive count=3`，其中 node 4 右支从此前的 after-filter infeasible 变成了 M 惩罚正值证书。由此可以确定，今天“不对”的核心不是 required arc slack 覆盖不了竞争弧，也不是正值列保留策略本身，而是 M 惩罚被重建模型冲掉，以及 repair 返回了旧 slack 模型 solution。

### 2026-07-04 当前 strong branching 与正式 child 流程说明

当前普通分支和 strong branching 共用同一套 `BranchResult` child 构造。以 arc 分支为例，左支在 node 上记录 `forbidArc(i,j)`，右支记录 `requireArc(i,j)`，同时把 `i` 的其它后继和 `j` 的其它前驱记录为 `branchImpliedForbiddenArc`。这些 implied 禁弧不作为单独 master branch row，只通过 child 兼容性、pricing 禁弧和 lightweight trial 的 M 惩罚发挥作用。

普通 child 入队时不立即求解 LP，只准备 seed columns 后进入优先队列。出队正式求解时，`PC.solve()` 先构造 child RMP 并求一次 LP；若 infeasible 则走 repair；若 feasible 且不是 strong trial 已准备好的 child，则按当前 dual/reduced cost 和 child 兼容性筛列，再重解，之后进入完整 pricing/cut/枚举流程。因此普通 child 的真正修复和筛列发生在出队正式处理阶段。

strong branching 则在父节点分支前先试探多个候选。第一阶段对候选左右支分别构造 trial LP，先准备 seed，再求 trial RMP；若 infeasible 则 repair。arc/columnized outsourcing 在 lightweight 模式下会临时保留父节点正值列以维持 repair 起点，同时把违反 branch-implied 禁弧的列目标系数改为 `big_M` 后只 resolve 当前模型。如果 M 列或 slack 仍为正，则该 side 按 infeasible 评分；否则再做二次筛列并保留当前正值列，得到可复用 seed。第一阶段按左右 bound gain 的乘积排序。

第二阶段只对第一阶段排名靠前的候选做更深试探：如果 side 在第一阶段不可复用，则不再对它跑二阶段；可复用 side 用已准备好的 seed 重建 trial LP，然后只跑 strong-branching 允许的轻量 pricing（普通启发式、rank1 time-indexed 的内部 bucket heuristic、列化外包 pricing 等），不跑完整 exact pricing。最终选中的左右 child 如果 trial 可复用，会把 trial 后的 restricted columns 写回 child 并标记 `strongBranchingSeedPrepared=true`，正式入队后跳过初始 repair/筛列，直接基于这批 seed 求正式 LP 并进入后续完整 pricing。

#### Phase 1 trial 的普通口径和 lightweight 口径

Phase 1 的普通 trial 用于不启用 lightweight repair 的候选。它先让 child 继承父节点当前 restricted columns 作为 seed，再按 child 的分支状态构造临时 RMP 并求一次 LP。如果 LP infeasible，就调用普通 repair：加 repair slack、用完整 pricing 补列，直到 slack 为 0 或确认修不动。repair 成功后必须回到“筛列后、无 slack 的正式 RMP”再求一次 LP。之后再按当前 reduced cost 与 child 兼容性做二次筛列，并保留当前正值列，重解得到 phase 1 bound。这个 trial 的结果如果可复用，就把筛后的列集作为 child 后续入队 seed。

lightweight trial 只用于 arc 分支和列化外包 membership 分支。它准备 seed 时先过滤明显不兼容的列，但父节点当前正值内部列会临时保留，用来保证 repair 有连续起点。以 arc 右支 required `i->j` 为例，`i` 的其它后继、`j` 的其它前驱被记录为 branch-implied forbidden，但这些 implied 禁弧不建额外 master row。trial RMP 可行或 repair 成功后，会把仍违反 branch-implied forbidden 的遗留列临时改成 `big_M` 成本，并只 resolve 当前 CPLEX 模型。如果 M 列或 slack 仍为正，说明这个 side 在当前 trial 口径下还依赖这些竞争列，直接按 infeasible 评分；如果 M/slack 都为 0，才继续二次筛列。二次筛列只是减小 seed，必须保留当前正值列，不能把筛列后的 infeasible 当作子树不可行证书。

前面修复的两个 bug 都在这段 phase 1 流程里。第一，M 惩罚后不能调用会重建模型的 `solveRelaxationTimed()`，否则 `buildModel()` 会把临时 M 系数恢复成原始列成本；必须用 `resolveCurrentModelTimed()`。第二，普通 repair 成功后不能返回 repair slack 模型下的旧 solution；strong trial 后面还要做 M 惩罚和筛列，因此 repair 必须返回筛后、无 slack 的正式 RMP 解。

进一步讨论后，当前实现虽然已经修正状态一致性，但 phase 1 lightweight 仍偏绕：它先按原目标求/repair，再对 branch-implied 竞争列加 M 后 resolve。更清晰的实现应把 M 作为 trial RMP 的初始 objective 口径：lightweight seed 保留父节点正值列和 child-compatible 列，构造 trial RMP 时直接把使用 branch-implied forbidden arc 的遗留列设为 `big_M`，repair 也在同一 objective 口径下进行；修复结束时只需检查 artificial slack 是否为 0、M 列是否为 0。这样普通 trial 和 lightweight trial 的后续流程可以统一，区别只在 seed 准备方式和是否启用 M 清洗。二次筛列只负责减小 seed，并保留当前正值列；若已经确认 M 列为 0，保留正值列不会再把 branch-implied 竞争列带入可复用 seed。

### 2026-07-04 一段式 M 口径优化

按上述思路，lightweight trial 不再先按原目标 repair、再额外调用 `penalizeBranchImpliedIncompatibleColumns()` 和 `resolve`。现在 `LP` 增加 trial-only 的 `branchImpliedPenaltyObjectiveMode`，`buildObjective()`、增量加列和 objective 更新统一通过 `internalColumnObjectiveCost()` 取内部机器列目标系数；当该模式开启且列使用 branch-implied forbidden arc 时，从建模开始直接按 `big_M` 成本处理。这样初始 trial RMP、repair slack RMP、repair 后正式 RMP 和二次筛列重解都处在同一个 M objective 口径下，不再存在“原目标 repair 后再切 M”的额外状态切换。

`PC.solveStrongBranchingRmpTrial()` 现在通过 `enableStrongBranchingBranchImpliedPenalty` 统一控制该 objective 模式。开关打开时，普通 trial 和 lightweight trial 都按同一个 M 口径评分；开关关闭时，二者都只使用显式分支行，属于偏松的弱口径。旧的事后 M 惩罚方法已删除，避免未来再次误用两段式流程。正式 BPC 节点仍使用真实列成本，不把 strong-trial 的人工 big-M objective 带进最终主问题；strong trial 只负责分支评分和可复用 seed 准备。

验证使用 `test-results/bpc/tmp-strong-unified-mpenalty-20260704`，配置为 `wet040_001_2m`、`halfDomain-ngPartial-nearestK4-top10`、time-indexed root preprocessing、strong branching、lightweight repair 和统一 M 开关开启。结果 `FINISHED,obj=bound=22580,valid=true,solve=232.971s,nodes=16`；日志计数为 `rmp_trial_infeasible_after_filter=0`、`strong_branching_light_after_branch_implied_penalty=0`、`branch_implied_penalty_positive=2`。这说明统一 M 口径后不再出现二次筛列假 infeasible，也没有旧的额外 M resolve 阶段。

### 2026-07-04 construct 入口预处理过滤清理

继续检查 child RMP 建模入口时，确认 `LP.construct(node, seedColumnIds)` 不应该承担任何当前分支或全局预处理过滤语义。正常流程下，列生成、初始列和 repair 入口都已经避开 `Data.preprocessedArcForbidden`；如果某个列池或实验入口把包含静态预处理禁弧的列塞进 seed，那属于上游列生成或数据重刷问题，不应在 `construct()` 里静默吞掉，否则会把“传入 seed 是什么”和“最终建模用的 seed 是什么”混在一起，干扰 strong branching/repair 的可行性判断。

因此删除 `LP.construct()` 中对 `node.isColumnPreprocessingCompatible(column)` 的兜底过滤，并同步删除已无调用点的 `Node.isColumnPreprocessingCompatible()`。现在 `construct()` 只负责按调用方给定的 seed 建 restricted RMP，分支行、repair slack、branch-implied M、外包 membership row 等语义都留在后续 `buildModel()` 和 `PC.solve()` 流程里处理。这个改动不改变 pricing 的禁弧逻辑，只清理建模入口的隐式筛列。

### 2026-07-04 冗余过滤路径复核

继续全局检查“同一列兼容性在多个层级反复过滤”的问题。当前保留的原则是：source 层的剪枝保留，例如 ng-DSSR、启发式 pricing、time-indexed pricing 在扩展或候选恢复前就避开 forbidden arc、pricing-only arc 和 required outsourcing job；这些检查能少生成无效列，不属于冗余。LP 建模和加列入口则不再承担兜底过滤职责，否则会把上游错误静默吞掉，并干扰 repair/strong branching 判断。

本次确认并清理两处运行路径上的冗余。第一，`LP.addOutsourcingColumns()` 原来在外包 pricing 已经按 required/forbidden job 生成列后，又用 `node.isOutsourcingColumnCompatible()` 过滤一次。这个过滤如果触发，只会让 pricing 返回的列被静默丢掉，导致 repair 或 pricing 日志变成“生成了但没加入”，不利于定位错误。现在外包列和内部列一样：Pool 写入后，LP 只负责去重和加入 restricted RMP，列是否合法由 `OutsourcingPricingEngine`、route enumeration 等源头保证。

第二，`resetRestrictedColumnsByCurrentReducedCost()` 保留了一个已经没有实际调用意义的 `keepPositiveIncompatible` 分支，表面上像是 strong trial 可以选择删除当前正值不兼容列。结合前面的 branch-implied M 讨论，这个口径容易造成误解：筛 seed 的目的只是减小后续 RMP 规模，不能删掉当前可行 LP 的正值列；需要排斥的竞争列应通过 strong trial 的 M 目标处理。因此该方法现在只有一个口径：正值内部列无条件保留，非正值内部列才按当前 node 兼容性和 reduced cost 筛选；外包列也保持相同思想，正值列保留，非正值列按 membership 兼容性和 reduced cost 筛选。

仍然存在但默认关闭的实验路径包括 `enableStrongBranchingDomainRepair` 对应的 all-row slack/domain-filtered seed，以及若干 `diagnostic*`、completion-bound audit、paper graph timing、partial-list cardinality stats 等诊断开关。这些默认不会参与主线求解，不是当前运行时拖慢的主要来源；后续若不再需要 domain repair 这条实验分支，可以单独删配置和对应方法，但这次不混入主线过滤清理。

### 2026-07-04 主线冗余复查补充

继续按主线流程检查 seed 过滤、repair、pricing 候选和诊断统计后，本次只清理了一处确定冗余：`GCNGBBStyleBidirectional`、`GCNGBBStyleBidirectionalNgDssr` 和 `GCNGBBStyleBidirectionalPartialDominance` 的 midpoint timing 计算里，`selectMidpointColumnCandidates()` 已经按 `isSequenceCompatible(sequence, node)` 过滤过候选，后续 `evaluateMidpointColumnTiming()` 和 `evaluateTopLastMidpointColumnTiming()` 再检查同一条件只是重复扫描。现在只保留候选入口过滤，timing 阶段直接做 `evaluateTiming()`。这不改变候选集合和 midpoint 统计语义，只减少重复兼容性判断。

同时复查了几类看起来像冗余、但当前不应直接删除的路径。第一，普通 child 的 `after_column_filter` 重解不是单纯重复求解；筛列会改变 restricted RMP 的列集合，后续 pricing 需要新模型下的 dual，因此仍要重新解一次。第二，repair 路径里的 slack 模型和正式 RMP 不是同一个口径；repair 成功后是否需要重解取决于调用方是否要求筛后正式解，不能把 repair slack 模型的旧解直接当作所有场景的正式 bound。第三，启发式 pricing 的最终兼容性和 true-cost recheck 仍有必要：tabu 的 add/exchange/remove 会产生新序列，dual window 下的搜索成本不能直接写入 Pool。compact window 当前允许跳过 true recheck 是一个明确的实验口径，不属于遗漏的重复计算。第四，time-indexed pricing 当前已经避免旧版“每个 negative end state 都调用 evaluator”的问题，候选先按 graph reduced cost 进入 top 集合，再由 reduced cost 反推 objective cost，因此没有再发现同类大头冗余。

仍需保留讨论的点是 strong branching / repair 的实验分支。`enableStrongBranchingDomainRepair` 对应的 all-row slack 方案默认关闭，代码上仍有一套独立流程；它不是当前主线运行成本，但如果后续确认不会再用，可以单独删除。`resetRestrictedColumnsByCurrentReducedCost()` 保留正值列、非正值列才按兼容性和 reduced cost 筛选，这一点和前面 branch-implied M 的修复一致，不能再简化成“统一删不兼容列”。总体结论是：当前主线里已知危险的兜底过滤已经清掉，剩下的大部分检查属于不同语义层的保护或默认关闭诊断，不应在没有新证据时继续硬删。

### 2026-07-04 冗余检查口径修正

继续复核后明确口径：后续只把“当前主线会执行、且同一语义层重复筛选或重复重算”的代码作为冗余清理目标。默认关闭的实验分支、旧 pricing 路径和异常状态提示只作为记录，不再混入当前主线冗余判断。

`enableStrongBranchingDomainRepair` 对应的 all-row slack/domain repair 分支虽然代码较重，但默认关闭，当前主线不经过，不影响当前求解步骤，因此暂不处理。它只作为历史实验路径保留；若以后确认完全废弃，再单独删除，不和主线 cleanup 混在一起。

旧 `GCBidirectional` 路径中关于“普通路径不会在最终加列前用 evaluator 修正成本、只在 debug 下复核”的注释，属于 dormant legacy path 记录。当前 ng-DSSR 主线和 time-indexed 主线不走该路径，后续也大概率不会再启用；这里只记录该历史口径，不作为当前冗余或 bug 处理。

按这个口径重新看，当前仍可能算“冗余但未处理”的主要只有 RMIH duplicate repair 里的局部 evaluator 重算：删除重复 job 时会对相近 sequence 多次调用 `TWETColumnEvaluator.evaluate()`。不过它只在 RMIH repair fallback 中触发，不是 pricing 主线热点；是否缓存需要看实际 RMIH 日志，不宜现在为了形式清理而增加 key/cache 复杂度。其它如 child 筛列后重解、repair slack 与正式 RMP 切换、source 层 forbidden/required 过滤，都属于不同语义层，不按冗余处理。

### 2026-07-04 主线冗余复查继续

继续沿着 PC 生成列、LP 加列、RMIH fallback、time-indexed 候选和强分支 trial 路径检查后，当前没有发现新的、可以直接删除的主线重复工作。一个容易误判的点是 `PC.generateColumnsFromEngine()` 和 `LP.addColumns()/addOutsourcingColumns()` 都维护 active id 集合。这里不是同一层语义重复：PC 层的 active set 用来在同一轮 pricing 里避免重复返回、统计 active 列成本改进；LP 层的 active set 是 `addColumns` 这个公共入口的去重边界，防止其它调用方直接把已 restricted 的列再次加入当前 RMP。若为了省一次 `HashSet` 构造把 LP 层去掉，会让公共入口不再自保，收益也很小，因此不处理。

time-indexed pricing 也重新确认了一次：当前不再对所有 negative end state 做 `TWETColumnEvaluator.evaluate()`，而是先按 graph reduced cost 判断是否可能进入 top 候选，再恢复 sequence 并用 reduced cost 反推出 objective cost。启发式 pricing 的 true-cost recheck 只在 dual window 等需要回到原始目标口径时触发；compact window 跳过 recheck 是当前明确实验口径。route enumeration 的 evaluator 重算只在 `routeEnumerationUseTimeIndexedWindow=true` 时用于保护枚举列真实成本，默认不在主线。

因此当前真正还像冗余的仍然只有 RMIH duplicate repair fallback 中对相邻删除序列的局部重复评价。这个点以后如果日志显示 RMIH repair 时间占比升高，可以考虑在该 fallback 内部加小范围 sequence-cost cache；在没有这个证据前不改，因为它会增加签名/key 管理复杂度，而且不解决当前 pricing 或 LP 主耗时。

### 2026-07-04 排序比较器全序修正

继续检查“冗余/高效性/正确性”时发现一类确定的正确性风险：部分排序、优先队列和 `compareTo` 使用 `Utility.compareLt/compareGt` 这种带 epsilon 的浮点比较。epsilon 判断适合 reduced cost 阈值、是否为正值列、剪枝边界等数值语义，但不适合 Java `Comparator` 或 `Comparable`。排序比较器需要严格全序；如果出现 A 与 B 近似相等、B 与 C 近似相等、但 A 与 C 又被判为有序，就可能违反 comparator contract。此前 `HeuristicPricingEngine.collectSeedColumnsBySortedPrefix()` 已经暴露过 TimSort contract 异常，因此这不是风格问题，而是可达的稳定性问题。

本次只修改排序口径，不修改阈值口径。也就是说，候选是否进入、reduced cost 是否为负、LP 值是否为正、arc fixing 是否触发仍然使用 `Utility.compare*`；但凡是排序、优先队列、候选排名、`compareTo`，统一改为 `Double.compare`，再用 job id、column id、描述字符串等稳定字段打破平局。受影响的路径包括强分支候选排序、Tree 的强分支选择排序、RMP seed reduced-cost 排序、RMIH 列评分排序、subset-row cut 候选排序、time-indexed top 候选排序、ng-DSSR/GCBB label priority queue、completion-bound priority queue，以及通用 `Label`/`Node` 的 `compareTo`。

这个修改不会改变算法的数值接受条件，只改变同一批候选内部的确定排序和队列弹出顺序。收益是避免排序 contract 崩溃，并让强分支、label 队列和 top-k 候选在浮点接近时有稳定 tie-break。验证上，针对本次改到的源文件做了 focused `javac`，编译通过。
