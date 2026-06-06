# Tmid probe 与列内任务量策略记录

## 1. 当前 completion bound 与 half cache 的关系

当前 half cache 指的是 `GCNGBBStyleBidirectional` 中的 `baseForwardHalfPenaltyByJob` 和 `baseBackwardHalfPenaltyByJob`。它们把每个 job 的基础 penalty 函数预先裁成 `[0,Tmid]` 和 `[Tmid,pricingHorizon]` 两份，供正式 forward/backward label 扩展复用。这样做的目的不是改变成本语义，而是把 bounded bidirectional 的半域边界写进函数对象，后续 `shiftX/add/normalize` 时自然维持半域，不需要每次扩展都重新裁剪完整 penalty。

因此 half cache 是正式 half-domain pricing 的缓存，不是 completion bound 的缓存。当前 completion bound 另有 `completionForwardPenaltyByJob` 和 `completionBackwardPenaltyByJob`，通过 `buildCompletionBoundPenalty()` 构造，定义域是 `[0,pricingHorizon]`。它用于判断一个已经生成的半域 label 是否还能被松弛补全成负 reduced-cost 列。若 midpoint 策略为 `completionBound`，代码会先用默认 `Tmid` 建临时 half cache，以便完成窗口和动态 penalty 预计算；然后构造 completion bound；再根据 bound 的时间信息重算最终 `Tmid` 并重建 half cache。最终 completion bound 不受最终半域裁剪直接限制。

completion bound 剪枝当前发生在 child label 构造之后、进入 dominance graph 和队列之前。forward 扩展中，`extendForward()` 成功得到 child 后，先调用 `isForwardCompletionBoundPruned(child)`；若被剪掉，就增加 `completionForwardLabelsPruned`，不入表、不入队。backward 侧对称地在 `extendBackward()` 后调用 `isBackwardCompletionBoundPruned(child)`。这意味着 completion bound 是扩展阶段的 early pruning，而不是事后清理队列。

## 2. 列内任务量中间时间

当前 `columnLastAvg` 看的是好列的列末完工时间平均值。它能识别非根节点 `pricingHorizon` 太大，但仍可能被少数很晚完成的尾部任务拖右。`columnHalfAvg` 看每条列中间位置任务的完工时间，能把 `Tmid` 拉左，但每条列只贡献一个点，太粗，容易对列长度和任务顺序敏感。

新的列内任务量策略应统计当前节点较好列中的所有任务完成时间。具体做法是：先按 reduced cost 从 restricted columns 中选一批兼容列；对每条列用完整 PWLF timing 反推出列内各任务的最优完成时间；把所有完成时间放进一个样本集合。这个集合表达的是“当前好列里任务完成时间的经验分布”。如果取样本中位数作为 reference，就能得到任务量意义上的中间时间，即大约一半任务完成在它左侧，一半任务完成在它右侧。

第一版建议实现为 `columnTaskMedian` 或 `columnTaskCenter`。候选列筛选沿用当前 column 策略；样本规模最多是 `列数 * 任务数`，在 30 任务场景下直接排序即可。初始可以不做复杂权重，每个任务完成时间权重为 1。后续若需要，可以给正值列更高权重，或按 reduced cost 对列加权。

这个策略的关键判断是最终 `Tmid` 如何从 reference 得到。若目标是修正 `columnLastAvg` 偏右，直接使用任务量中位时间作为 `Tmid` 更符合动机；若担心过左，可以作为另一个配置取 `(left + reference) / 2`。两者应分成不同策略测试，不能直接覆盖已有 `columnLastAvg`。

## 3. Tmid probe 的定位

probe 不是正式 pricing，也不是证明无负列的过程。它只是在正式 exact pricing 前，用几个候选 `Tmid` 做严格限额的 dry-run，观察 forward/backward label 压力，然后选择更合适的 split point。probe 不改 RMP，不更新 dual，不把 dry-run 的 dominance graph 状态带入正式 pricing。第一阶段也不需要把 probe 中找到的负列加入主问题，避免把“选择 Tmid”实验和“生成列”混在一起。

这个思路类似 MIP 求解器中的 probing/diving：临时探索一个局部决策，收集信号，然后回滚。区别是这里不改变变量界，而是临时改变 half-domain split point。正式 exact pricing 仍必须在选定 `Tmid` 后完整执行；probe 没找到负列不代表没有负列。

## 4. probe 候选点与迭代方式

候选点不宜只围绕 `0.65/0.85/1.05 * reference` 固定测试。由于当前问题主要表现为 `Tmid` 偏右导致 forward 爆炸，第一版候选应整体偏左，同时保留一个接近 reference 的点作为保护。可以采用：

`0.45 * reference`、`0.65 * reference`、`0.85 * reference`、`1.00 * reference`

其中 reference 可以来自 `columnTaskMedian`，也可以来自当前已有 `columnLastAvg/completionBound`。每个候选点都要经过 clamp，不能贴到 0 或 `pricingHorizon`。若某个候选导致 backward 明显爆炸或半域几乎不可用，score 会自然变差。

二分法可以作为第二阶段，但不建议一开始就做完整二分。原因是每个 probe 虽然限额，仍然要初始化 label、bound 检查和 dominance，过多候选会把一次 pricing 前置成本放大。更稳的第一版是“三到四点离散试探 + score 选择”。若 score 最优点落在最左端，说明还可能偏右，下一轮再测试更左的两个点；若最优点落在最右端，说明可能过左，再测试更右的点。最多做两轮，或者达到总 pop/time 预算就停止。

停止条件可以先设为：

1. 总 probe pop 达到预算，例如 `20000`；
2. 总 probe 时间达到预算，例如 `0.1s` 或 `0.2s`；
3. 某个候选的 score 明显优于其他候选，例如小于第二名 `20%` 以上；
4. 两轮后不再继续。

这些阈值需要实验校准。核心原则是 probe 的成本必须远小于 hard exact pricing 的成本，否则它就失去意义。

## 5. imbalance score 备选

第一版可以同时实现几个 score 公式，通过配置选择或在诊断里全部输出。

`scoreA` 只看 kept：

`abs(log((fwKept + 1) / (bwKept + 1))) + alpha * log(fwKept + bwKept + 2)`

它简单直接，但如果某一侧尚未开始大量入表，只看 kept 可能反应滞后。

`scoreB` 看 kept 与队列：

`abs(log((fwKept + fwQueuePeak + 1) / (bwKept + bwQueuePeak + 1))) + alpha * log(fwKept + bwKept + fwQueuePeak + bwQueuePeak + 2)`

这个更适合有限 pop probe，因为队列峰值能提前暴露某一侧即将爆炸。

`scoreC` 看 bound 后压力：

`fwPressure = forwardExtensionBoundSurvivors + fwQueuePeak`

`bwPressure = backwardLabelsKept + bwQueuePeak`

`scoreC = abs(log((fwPressure + 1) / (bwPressure + 1))) + alpha * log(fwPressure + bwPressure + 2)`

这个更贴近当前 013 hard node 的现象：completion bound 已经剪掉大量 forward child，但 bound survivors 和队列仍然很大。第一版建议优先看 `scoreB` 和 `scoreC`，`scoreA` 作为对照。

`alpha` 不宜太大，初始可取 `0.05` 或 `0.10`。imbalance 是主项，总量惩罚只用于区分“两边差不多但都很大”和“两边差不多且都小”。

## 6. 当前推荐实现顺序

先实现 `columnTaskMedian/columnTaskCenter`，只改变 midpoint reference 计算，不引入 probe。它最小、可解释，也能直接回答“当前节点好列里任务完成时间分布到底在哪里”。

然后实现 probe 的诊断模式：在正式 pricing 前跑候选 `Tmid` dry-run，只输出每个候选的 label、队列、completion-bound 剪枝和 score，不改变最终 `Tmid`。确认 score 与 hard node 真实耗时相关后，再打开自动选择。

最后再考虑自适应两轮 probe。它应只在疑似 hard node 上触发，例如当前列数、pricingOnly 禁弧数、上一轮 exact pricing label 数或 default/reference 差距超过阈值时触发。普通节点不应默认 probe，避免把所有节点都加上额外成本。

当前没有新的未决建模问题，主要剩下实现和实验阈值选择。需要注意的是，probe 必须严格复用正式 pricing 的禁弧、completion-bound 剪枝和 dominance 逻辑，否则 probe 统计和正式 pricing 的难度不一致，score 会失真。

## 7. 2026-06-06 实现口径

本次已按“先完整实现、暂不跑算例实验”的口径完成代码接入。`GCNGBBStyleBidirectional` 新增 `columnTaskMedian` 和 `columnTaskCenter` 两个策略名，二者当前语义相同：从 reduced cost 较好的兼容 restricted columns 中取最多 `bidirectionalMidpointColumnLimit` 条列，用 `TWETColumnEvaluator.evaluateTiming()` 重算完整 PWLF timing，收集所有列内任务完成时间，取这些完成时间的中位数作为 `reference`，并直接把该 reference clamp 后作为最终 `Tmid`，不再做 `(L+U)/2`。

统计输出中补充了 `midpointColumnTasks count/minAvgMedianMax`，用于观察当前节点好列的任务完成时间分布。原有 `midpointColumns count/lastMinAvgMax/halfMinAvgMax` 保留，便于和 `columnLastAvg`、`columnHalfAvg` 对比。

probe 默认关闭，由 `bidirectionalMidpointProbe` 控制。开启后，正式 pricing 前会用当前 `reference` 乘以 `bidirectionalMidpointProbeFractions` 中的比例生成候选 `Tmid`，默认比例为 `0.45,0.65,0.85,1.0`。每个候选重新初始化一套 label store、队列、single-point store 和候选池，设置对应 `Tmid` 后重建 half-domain penalty，再进行有限 pop dry-run。dry-run 不执行 final join，不 finalize generated columns，不向 RMP 加列；它只统计 label 压力。候选之间会清空 probe 影响的统计，最后正式 pricing 也会重新初始化搜索状态，因此 probe 不会把 label 或 dominance graph 状态带进正式求解。

probe 的扩展顺序没有照正式流程“先完整 forward 再 backward”，而是每次扩展当前队列较大的一侧。这样做是为了在有限 pop 下同时观察两侧压力，否则大多数预算可能全消耗在 forward，无法估计 backward。这个顺序只用于 probe，不改变正式 exact pricing。

当前实现输出三类 score：`kept` 使用 `fwKept/bwKept`；`queue` 使用 `fwKept+fwQueuePeak` 和 `bwKept+bwQueuePeak`；`bound` 使用 `forwardExtensionBoundSurvivors+fwQueuePeak` 和 `bwKept+bwQueuePeak`。score 公式均为 `abs(log((left+1)/(right+1)))`，没有引入 alpha 总量惩罚。自动选择由 `bidirectionalMidpointProbeScore` 控制，默认 `queue`。summary 中会输出所有候选的 `kept/q/bound/cb/score`，后续实验可比较哪个 score 更贴近真实耗时。

为便于命令行测试，`GCBBFullDomainComparisonTest` 增加了 `twet.bpc.fullDomainCompare.midpointProbe`、`midpointProbePopLimit`、`midpointProbeFractions` 和 `midpointProbeScore` 系统属性读取。验证只做 focused `javac`，没有按用户要求启动算例实验。
