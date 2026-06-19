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

当前 time-indexed graph pricing 本身只做完整单向 DAG DP，不在 pricing 内部额外叠加 completion bound 或 suffix-bound 剪枝。等待弧和处理弧只受硬时间窗、预处理/分支 forbidden arc、pricingOnly arc，以及后续 paper reduced-cost fixing 写入的 time-indexed pricing-only arc 过滤。

返回列时使用 `TWETColumnEvaluator` 重算真实 TWET 成本，再以 `ColumnSource.PRICING_EXACT` 交给现有 Pool/RMP。候选列按 `SequenceSignature` 去重，同一序列只保留 reduced cost 更小的候选，最多返回 `maxExactPricingColumns` 条。

## 3. 语义风险

这个 pricing 的最短路 reduced cost 按论文 pseudo-schedule 口径每次访问 job 都扣一次 job dual；当前 RMP 覆盖行对同一列中的重复 job 只看 0/1 是否包含。因此如果 graph pricing 返回重复 job 路径，它作为当前 RMP 列使用时不是严格的论文 DWM 列，也不是当前 elementary pricing 的闭合证书。

由此当前结论是：该实现可用于实验比较图定价在时间离散 DAG 上的速度、重复 job 候选比例、以及和函数占优 pricing 的列生成差异；若要把它作为严格 exact pricing，需要后续二选一：要么修改主问题列系数以支持重复访问次数，要么在 pricing 中加入 elementary/ng/DSSR 机制，只返回能证明闭合的 elementary 列。

## 4. 验证

已对新增 `TimeIndexedGraphPricingEngine`、`TWETBPCConfig`、`TWETBPCContext` 做 focused `javac` 编译：

`javac -encoding UTF-8 -cp target/classes;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar -d target/classes ...`

编译通过。当前机器命令行没有 `mvn`，因此未运行 Maven 全量编译。后续需要在小算例上打开 `useTimeIndexedGraphPricing=true` 做运行对照，重点观察 `bestPseudoRC`、`repeatedJobCandidates`、返回列是否重复 job，以及 LP bound 是否出现明显语义偏差。

## 5. 2026-06-20 修正：区分 paper arc fixing 和当前 completion-bound fixing

上一版实现把 time-indexed graph pricing 内部的后向最短路当成 suffix bound，在 forward DP 扩展时用 `prefix + arc + suffix >= 0` 提前剪枝。这个做法虽然可作为 shortest-path 加速，但不是论文 Algorithm 7 的 reduced-cost arc fixing，也容易和当前项目的 completion-bound arc fixing 混在一起。因此已撤回该 pricing 内部剪枝。

论文里的 arc fixing 现在单独放在节点 column generation 已闭合之后执行。它用当前节点 LP 下界 `LB` 和已有整数上界 `UB`，重新在 full-horizon time-expanded graph 上计算 forward shortest distance 和 backward shortest distance；对每条具体处理弧 `(from,to,t)` 计算穿过该弧的最小 reduced cost `cmin`，若 `cmin >= UB - LB`，则把该具体时间弧写入 node 的 time-indexed pricing-only 禁弧集合，供子节点的 graph pricing 避开。该 fixing 不使用当前 TWET 的 completion bound，也不调用 subtree arc elimination；在 `useTimeIndexedGraphPricing=true` 时，原 completion-bound subtree elimination 路径会跳过，避免两套 fixing 语义叠加。

当前实现只固定处理弧 `(from,to,t)`，包括 source 到 job 的弧；暂不做论文里的 idle/sink arc 清理、顶点压缩和 `t*` 重算。这样先保证 no-cut graph pricing 和 paper reduced-cost arc fixing 的基本口径清楚，再决定是否补完整图压缩步骤。

## 6. 与论文细节的再次核对

再次读取论文后，当前实现和论文一致的部分是：图节点按 `(lastJob,t)` 表示；等待弧相当于论文 `A4`，成本为 0；处理弧相当于 `A1/A2`，时间推进为 `t+s_ij+p_j`，成本按 job 完工时间函数计算；机器数 dual 只在 source 到首任务弧上扣除；no-cut 情况下每个顶点只保留一个最短距离，等价于无额外 cut state 的 shortest-path pricing。reduced-cost arc fixing 也已经放到节点 column generation 闭合之后，用 `UB-LB` 和 full-horizon forward/backward shortest distance 计算穿过 `(from,to,t)` 的最小 reduced cost，这一点符合 Algorithm 7 的核心逻辑。

仍然不等同完整论文版的地方也要明确。第一，当前只实现同质机单图口径，没有按 machine type `k` 拆多张图。第二，当前 no-cut 实验不带论文的 limited-memory rank-1 cut state，因此 Algorithm 7 中两侧 label 合并时的 `S_l+S'_l>=beta_l` 修正没有实现；这和“先做无 cut 版本”的目标一致。第三，当前只 fixing 处理弧 `(from,to,t)`，没有继续删除冗余 idle arc、sink arc，也没有做图顶点压缩和 `t*` 重算。第四，论文 DWM 的列系数按路径中 job 被处理次数计数，而当前 TWETColumn 仍按 `containsJob` 的 0/1 覆盖语义进入 RMP，所以该 graph pricing 只能作为实验对照，不能直接替代当前 elementary/ng exact pricing 的正确性证明。第五，TWET 当前目标包含 setup cost 和分支 arc dual，这比论文“目标函数只定义在完工时间上、setup 主要影响时间推进”的基础公式多了问题特定项；这些项是为了和现有 TWET RMP reduced-cost 口径一致，而不是论文原式本身。

因此当前结论是：no-cut graph pricing 的 DAG 最短路骨架和 Algorithm 7 的 reduced-cost fixing 核心判定已经对齐，但不是完整论文 BCP 实现。后续若要声称“和论文完全一致”，至少还需要补 machine type 多图、DWM 路径系数口径、limited-memory rank-1 cut state、idle/sink/vertex cleanup，以及与论文相同的 branching/cut 主循环。
