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

## 当前结论

如果只讨论 no-cut pure time-indexed root，当前证据支持的结论是：6/20 `heurOff-1642b` 的确说明旧版 pure graph root 会长时间不闭合；6/28 之后变快的核心不是 CPLEX、分支或启发式，而是 time-indexed graph column 的候选成本恢复路径被改掉。更准确地说，旧版不是“最终列质量差”，而是每轮 pricing 在最终保留列之前对数万到十几万个候选 end state 做了完整 `TWETColumnEvaluator.evaluate(sequence)`，热路径过重；当前版用 reduced-cost 反推 objective，避免了这部分重复评估。root bound 本身没有变，`22487.647059` 是一致的。

因此后续再比较论文 time-indexed 方法时，应使用 6/28 之后的 pure graph 口径作为 no-cut baseline，不要再拿 6/20 `heurOff` 目录名直接判断。若要继续追问“旧 engine 具体 evaluator 调用了多少次”，需要回到旧 commit 加计数器重跑；现有日志不足以精确量化。
