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

第二类才是 `heurOff-1642b` 与当前 pure graph root 差距的核心。旧版 time-indexed engine 从 DAG 上找到的是一条具体的时空路径，也就是带有离散完成时间的 pseudo-schedule column；但旧版在恢复候选后调用 `TWETColumnEvaluator.evaluate(sequence)`，把它重新变成“只按 job sequence 评估”的 TWET 列成本。这样会丢掉图上那条路径的具体完成时间口径，同一个 sequence 在不同 end state / 不同完成时间下的 graph objective 也被折叠掉。结果是：pricing 证明为负 reduced cost 的那条图路径，进入 RMP 时可能已经不是同一个成本口径的列，很多列对当前 RMP 的收敛贡献变弱，root 就会表现为不断加 pseudo-schedule 列但下界长尾不闭合。

6/28 的 `3ae94b26 Fix time-indexed pseudo column cost recovery` 改的是这个核心口径：不再用 evaluator 重算 sequence 成本，而是从 graph reduced cost 加回 machine/job/arc dual，反推出该时空路径本身的 objective cost。这样 RMP 里加入的列就是 pricing 刚刚证明为负 reduced cost 的那条图列，列成本和 graph pricing 口径一致。专题记录里的 commit 对照显示：旧版同口径 root-only 测试中第一轮 `TimeIndexedGraphPricing` 约 `31.668s` 且只返回 `173` 列，当前版本第一轮约 `0.18s`；状态量和 arc scan 没有拉开到这个数量级，慢点主要在候选恢复、真实成本重算和对象管理，而不是 DAG shortest path。

还需要校正一个旧说法：`negativeStates` 只是负 reduced-cost end state 数，不等于 `TWETColumnEvaluator.evaluate(sequence)` 调用次数。旧版确实比当前慢在候选恢复和成本处理，但不能直接说旧版做了十几万次 evaluator 调用，除非在旧版单独加计数器验证。

## 当前结论

如果只讨论 no-cut pure time-indexed root，当前证据支持的结论是：6/20 `heurOff-1642b` 的确说明旧版 pure graph root 会长时间不闭合；6/28 之后变快的核心不是 CPLEX、分支或启发式，而是 time-indexed graph column 的成本恢复口径被修正，并顺带去掉了 evaluator 重算的热路径开销。root bound 本身没有变，`22487.647059` 是一致的；变化主要是旧版加入了大量“由图路径发现、但进入 RMP 时被 sequence evaluator 换了成本口径”的低效列，而当前版加入的是与 graph reduced cost 对齐的列。

因此后续再比较论文 time-indexed 方法时，应使用 6/28 之后的 pure graph 口径作为 no-cut baseline，不要再拿 6/20 `heurOff` 目录名直接判断。若要继续追问“旧 engine 具体 evaluator 调用了多少次”，需要回到旧 commit 加计数器重跑；现有日志不足以精确量化。
