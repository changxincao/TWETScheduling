# Time-indexed graph pricing 实验记录

## 1. 背景

2026-06-20 讨论了参考论文 `On the exact solution of a large class of parallel machine scheduling problems` 中的 time-indexed graph pricing，先实现不带 cut 的版本，用来和当前基于分段函数占优的 exact pricing 做效率和列生成效果对照。

论文中的图定价把单机调度列表示成 time-indexed DAG 上的一条路径。节点为 `(lastJob, t)`，表示当前最后一个处理任务为 `lastJob`，当前时间为 `t`；等待弧让时间增加，处理弧从 `i` 到 `j` 并把时间推进到 `t+s_ij+p_j`。无 cut 时该 pricing 是 DAG 最短路。论文允许 pseudo-schedule，即同一 job 可以在路径中重复出现。

当前 TWET-BPC 的 RMP 列覆盖系数仍是 `TWETColumn.containsJob(job)` 的 0/1 语义，而论文 DWM 对重复访问路径的列系数口径不同。因此本实现只作为实验定价器，默认关闭，不作为当前主线 exact pricing 的正确性证书。

## 2. 当前实现

新增 `TimeIndexedGraphPricingEngine`，通过 `TWETBPCConfig.useTimeIndexedGraphPricing` 打开。打开后，Context 中仍先运行现有启发式 pricing，然后用 time-indexed graph pricing 替换当前 exact pricing；默认配置不受影响。

当前建图不显式缓存所有 time-expanded 弧，而是在 DP 扫描时按 `(lastJob,t,nextJob)` 即时生成处理弧。为避免热路径反复扫分段函数和节点状态，初始化时预先缓存 job-time penalty、arc duration、普通处理弧 forbidden 状态以及结束弧 forbidden 状态。等待弧成本为 0，处理弧 reduced cost 为：

`setupCost(last,next) + penalty_next(completion) - jobDual[next] - arcDual(last,next)`，

若 `last=0`，再减去 `machineDual`。结束弧只扣 `arcDual(last,sink)`。forbidden arc、preprocessing forbidden arc、pricingOnly arc 都在处理弧或结束弧生成时跳过；required arc 仍通过 RMP 分支行和 dual 影响 reduced cost，不强制每条 pricing 路径包含该弧。

Reduced-cost arc fixing 暂按 pricing 调用内部的 suffix shortest-path bound 做：先反向计算每个状态到 sink 的最短 reduced cost；若某次 forward 扩展或等待后 `prefix + arc + suffix >= 0`，则该状态不可能产生负 reduced-cost pseudo-path，直接剪掉。这不是写回 node 的永久 arc fixing，也不依赖 completion bound。

返回列时使用 `TWETColumnEvaluator` 重算真实 TWET 成本，再以 `ColumnSource.PRICING_EXACT` 交给现有 Pool/RMP。候选列按 `SequenceSignature` 去重，同一序列只保留 reduced cost 更小的候选，最多返回 `maxExactPricingColumns` 条。

## 3. 语义风险

这个 pricing 的最短路 reduced cost 按论文 pseudo-schedule 口径每次访问 job 都扣一次 job dual；当前 RMP 覆盖行对同一列中的重复 job 只看 0/1 是否包含。因此如果 graph pricing 返回重复 job 路径，它作为当前 RMP 列使用时不是严格的论文 DWM 列，也不是当前 elementary pricing 的闭合证书。

由此当前结论是：该实现可用于实验比较图定价在时间离散 DAG 上的速度、重复 job 候选比例、以及和函数占优 pricing 的列生成差异；若要把它作为严格 exact pricing，需要后续二选一：要么修改主问题列系数以支持重复访问次数，要么在 pricing 中加入 elementary/ng/DSSR 机制，只返回能证明闭合的 elementary 列。

## 4. 验证

已对新增 `TimeIndexedGraphPricingEngine`、`TWETBPCConfig`、`TWETBPCContext` 做 focused `javac` 编译：

`javac -encoding UTF-8 -cp target/classes;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar -d target/classes ...`

编译通过。当前机器命令行没有 `mvn`，因此未运行 Maven 全量编译。后续需要在小算例上打开 `useTimeIndexedGraphPricing=true` 做运行对照，重点观察 `bestPseudoRC`、`repeatedJobCandidates`、返回列是否重复 job，以及 LP bound 是否出现明显语义偏差。
