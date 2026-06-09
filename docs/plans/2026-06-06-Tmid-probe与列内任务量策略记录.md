# Tmid probe 与列内任务量策略记录

## 1. 当前 completion bound 与 half cache 的关系

当前 half cache 指的是 `GCNGBBStyleBidirectional` 中的 `baseForwardHalfPenaltyByJob` 和 `baseBackwardHalfPenaltyByJob`。它们把每个 job 的基础 penalty 函数预先裁成 `[0,Tmid]` 和 `[Tmid,pricingHorizon]` 两份，供正式 forward/backward label 扩展复用。这样做的目的不是改变成本语义，而是把 bounded bidirectional 的半域边界写进函数对象，后续 `shiftX/add/normalize` 时自然维持半域，不需要每次扩展都重新裁剪完整 penalty。

因此 half cache 是正式 half-domain pricing 的缓存，不是 completion bound 的缓存。当前 completion bound 另有 `completionForwardPenaltyByJob` 和 `completionBackwardPenaltyByJob`，通过 `buildCompletionBoundPenalty()` 构造，定义域是 `[0,pricingHorizon]`。它用于判断一个已经生成的半域 label 是否还能被松弛补全成负 reduced-cost 列。若 midpoint 策略为 `completionBound`，代码会先用默认 `Tmid` 建临时 half cache，以便完成窗口和动态 penalty 预计算；然后构造 completion bound；再根据 bound 的时间信息重算最终 `Tmid` 并重建 half cache。最终 completion bound 不受最终半域裁剪直接限制。

completion bound 剪枝当前发生在 child label 构造之后、进入 dominance graph 和队列之前。forward 扩展中，`extendForward()` 成功得到 child 后，先调用 `isForwardCompletionBoundPruned(child)`；若被剪掉，就增加 `completionForwardLabelsPruned`，不入表、不入队。backward 侧对称地在 `extendBackward()` 后调用 `isBackwardCompletionBoundPruned(child)`。这意味着 completion bound 是扩展阶段的 early pruning，而不是事后清理队列。

## 2. 列内任务量中间时间

当前 `columnLastAvg` 看的是好列的列末完工时间平均值。它能识别非根节点 `pricingHorizon` 太大，但仍可能被少数很晚完成的尾部任务拖右。`columnHalfAvg` 看每条列中间位置任务的完工时间，能把 `Tmid` 拉左，但每条列只贡献一个点，太粗，容易对列长度和任务顺序敏感。

新的列内任务量策略应统计当前节点较好列中的所有任务完成时间。具体做法是：先按 reduced cost 从 restricted columns 中选一批兼容列；对每条列用完整 PWLF timing 反推出列内各任务的最优完成时间；把所有完成时间放进一个样本集合。这个集合表达的是“当前好列里任务完成时间的经验分布”。如果取样本中位数作为 reference，就能得到任务量意义上的中间时间，即大约一半任务完成在它左侧，一半任务完成在它右侧。

第一版最终只保留 `columnTaskMedian`。候选列筛选沿用当前 column 策略；样本规模最多是 `列数 * 任务数`，在 30 任务场景下直接排序即可。初始可以不做复杂权重，每个任务完成时间权重为 1。后续若需要，可以给正值列更高权重，或按 reduced cost 对列加权。

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

先实现 `columnTaskMedian`，只改变 midpoint reference 计算，不引入 probe。它最小、可解释，也能直接回答“当前节点好列里任务完成时间分布到底在哪里”。

然后实现 probe 的诊断模式：在正式 pricing 前跑候选 `Tmid` dry-run，只输出每个候选的 label、队列、completion-bound 剪枝和 score，不改变最终 `Tmid`。确认 score 与 hard node 真实耗时相关后，再打开自动选择。

最后再考虑自适应两轮 probe。它应只在疑似 hard node 上触发，例如当前列数、pricingOnly 禁弧数、上一轮 exact pricing label 数或 default/reference 差距超过阈值时触发。普通节点不应默认 probe，避免把所有节点都加上额外成本。

当前没有新的未决建模问题，主要剩下实现和实验阈值选择。需要注意的是，probe 必须严格复用正式 pricing 的禁弧、completion-bound 剪枝和 dominance 逻辑，否则 probe 统计和正式 pricing 的难度不一致，score 会失真。

## 7. 2026-06-06 实现口径

本次已按“先完整实现、暂不跑算例实验”的口径完成代码接入。`GCNGBBStyleBidirectional` 新增 `columnTaskMedian` 策略：从 reduced cost 较好的兼容 restricted columns 中取最多 `bidirectionalMidpointColumnLimit=400` 条列，用 `TWETColumnEvaluator.evaluateTiming()` 重算完整 PWLF timing，收集所有列内任务完成时间，取这些完成时间的中位数作为 `reference`，并直接把该 reference clamp 后作为最终 `Tmid`，不再做 `(L+U)/2`。

统计输出中补充了 `midpointColumnTasks count/minAvgMedianMax`，用于观察当前节点好列的任务完成时间分布。原有 `midpointColumns count/lastMinAvgMax/halfMinAvgMax` 保留，便于和 `columnLastAvg`、`columnHalfAvg` 对比。

probe 默认关闭，由 `bidirectionalMidpointProbe` 控制。开启后，正式 pricing 前先测试 `reference` 本身，然后按上一个候选的左右压力动态移动：若左侧/forward 压力更大，则下一轮 `Tmid = current * (1 - moveRatio)`；否则 `Tmid = current * (1 + moveRatio)`。默认 `moveRatio=0.10`，最多测试 `bidirectionalMidpointProbeMaxCandidates=6` 个候选。每个候选重新初始化一套 label store、队列、single-point store 和候选池，设置对应 `Tmid` 后重建 half-domain penalty，再进行有限 pop dry-run。dry-run 不执行 final join，不 finalize generated columns，不向 RMP 加列；它只统计 label 压力。候选之间会清空 probe 影响的统计，最后正式 pricing 也会重新初始化搜索状态，因此 probe 不会把 label 或 dominance graph 状态带进正式求解。

probe 的扩展顺序没有照正式流程“先完整 forward 再 backward”，而是每次扩展当前队列较大的一侧。这样做是为了在有限 pop 下同时观察两侧压力，否则大多数预算可能全消耗在 forward，无法估计 backward。这个顺序只用于 probe，不改变正式 exact pricing。

早期实现曾输出三类 score：`kept` 使用 `fwKept/bwKept`；`queue` 曾短暂按 `fwKept+fwQueuePeak` 和 `bwKept+bwQueuePeak` 估计；`bound` 使用 `forwardExtensionBoundSurvivors+fwQueuePeak` 和 `bwKept+bwQueuePeak`。后续已经收紧为倍数口径，当前正式代码中 `queue` 使用 `fwKept+fwQueueRemaining` 和 `bwKept+bwQueueRemaining`，`peak` 只是单独可选诊断口径，不再作为默认 queue 语义。summary 中会输出候选的 `kept/q/qPeak/bound/cb/score`，后续实验可比较哪个 score 更贴近真实耗时。

为便于命令行测试，`GCBBFullDomainComparisonTest` 增加了 `twet.bpc.fullDomainCompare.midpointProbe`、`midpointProbePopLimit`、`midpointProbeMaxCandidates`、`midpointProbeMoveRatio` 和 `midpointProbeScore` 系统属性读取。验证只做 focused `javac`，没有按用户要求启动算例实验。

## 8. 2026-06-06 013 probe-off 小测试

按用户要求清理了 `columnTaskCenter` 冗余别名，当前只保留 `columnTaskMedian`。同口径测试使用 `tmp-wet030_from040_013_2m`、half-domain、`completionBound=allCycles`、`maxNodes=2`、probe 关闭。

已有对照中，旧 `default` 为 `solve=11.370s, exact=3.984s, cols=5746`，`completionBound` 为 `solve=10.057s, exact=3.267s, cols=5653`，旧 `columnLastAvg` 为 `solve=11.643s, exact=4.672s, cols=5512`。由于本次已把 column limit 调整为 400，额外用当前代码重跑 `columnLastAvg-current400`，结果为 `solve=14.976s, exact=9.155s, cols=6511`。

`columnTaskMedian` 当前结果为 `solve=29.494s, exact=16.019s, cols=5840`，没有优于已有策略。日志显示其在 root 的 `Tmid` 约为 `462-463`，node2 约为 `450-452`；当前 400 条好列的任务完成时间中位数确实落在这个位置。但该切分点比 `columnLastAvg-current400` 的 `Tmid≈480-492` 更靠左，root 阶段的 backward label、join pairs 和 completion bound 评估量都明显上升，node2 后续轮次也没有形成足够补偿。因此在 013 两节点小测试上，直接取任务完成时间中位数偏左，不适合作为默认策略。

## 9. 2026-06-07 013 probe score 与 median 偏左分析

继续使用 013、half-domain、`completionBound=allCycles`、`maxNodes=2` 口径，额外打开 `midpointProbe=true` 测试 `columnTaskMedian`。本次结果为 `solve=44.375s, exact=29.771s, cols=6084`。由于 probe 每轮正式 exact pricing 前会试探多个候选点，计入 exact pricing 总耗时，所以该结果不能直接和 probe-off 的求解时间比较；它主要用于判断 probe score 是否能反映正式 pricing 的左右压力。

对 probe 最终选中的 `Tmid`，probe 的 `kept` score 与正式完整 pricing 用 `fwKept/bwKept` 重算的 score 完全一致；用 probe 输出的裸 `boundSurvivors:bwKept` 重算的 raw-bound score，也与正式 pricing 的 `forward boundSurvivors / bwKept` 一致。例如 root 第一轮选中 `Tmid=554.628`，probe 与正式 pricing 的 kept score 均为 `0.309`，raw-bound score 均为 `0.515`。后续几轮同样一致：`Tmid=560.230` 时 kept score 为 `0.141/0.077/0.063`，node2 的 `Tmid≈658.845/661.773` 时 kept score 为 `0.363/0.256/0.217`。这说明当前 probe 在候选点已经跑到队列耗尽时，至少 kept/raw-bound 这两个趋势是可信的。需要注意的是，历史 summary 中第三个 `bound` score 曾包含 queue peak，而正式日志没有 queue peak，因此旧日志里不能直接拿第三个 score 和正式 raw-bound score 比；当前新增的 `remainingRatio` 只用于观察有限 pop 后的剩余队列压力。

root 第一轮的候选趋势也支持“median 偏左”的判断。probe 从任务完成时间中位数 `463` 开始，得到 `kept=2020:7072`、kept score `1.253`，明显右侧压力过大；动态右移到 `509.3` 后 score 降到 `0.483`；到 `554.628` 时 queue score 最小，仅 `0.045`。已有 probe-off 完整 pricing 也一致：`Tmid=463` 的 median run 第一轮 exact pricing 为 `3472ms`、join pairs `644584`；`Tmid≈492` 的 `columnLastAvg-current400` 为 `1463ms`、join pairs `489848`；`Tmid≈528` 的 default/completionBound 为约 `1425-1457ms`、join pairs 约 `261k-266k`。因此 013 root 上直接取 all-task median 过左，向右移动后正式 pricing 的左右压力和 join 规模都更合理。

本次几种 probe-off 策略的耗时差异主要来自 exact pricing。`columnTaskMedian` 相比 `columnLastAvg-current400`，总 solve 从 `14.976s` 增至 `29.494s`，exact pricing 从 `9.155s` 增至 `16.019s`，是最大增量来源。按节点看，root exact pricing 从 `3380ms` 增至 `8677ms`；root 的 `bwKept` 从 `15674` 增至 `22343`，join pairs 从 `1015732` 增至 `1477242`，completion-bound eval 从 `270448` 增至 `373619`。node2 两者都较重，median 的 `Tmid≈450` 比 `columnLastAvg≈480` 更靠左，但主要差距已经在 root 阶段形成。由此当前判断为：median 策略慢主要确实是 pricing 搜索结构变差导致，不是 RMP 或记录统计造成。

关于后续优化，单纯把所有较好列的所有任务完成时间混在一起取中位数，会受到大量早完成任务和较短列的影响，得到的 `Tmid` 可能偏左。用户提出的“两层筛选”是合理方向：先按 reduced cost 取较大的候选集，例如 `600-800` 条；对这些列完整重算 timing；再按列末完工时间 `lastCompletion` 从大到小排序，取前 `400` 条较长/较晚列；最后只用这 400 条列的所有任务完成时间取中位数。这个策略仍然基于列信息，但能减少早完工列把 median 往左拉的问题。实现上可以作为新策略单独命名，例如 `columnTaskMedianTopLast`，不应覆盖当前 `columnTaskMedian`；复杂度是一次候选列 timing 重算和一次排序，在 30 任务、几百条列规模下可以接受。风险是它可能过度偏向长列，所以应继续配合 probe 或至少输出 `topLast task median/lastAvg` 诊断后再决定是否作为默认。

## 10. 2026-06-07 columnTaskMedianTopLast 小测试

按上面“两层筛选”的想法，新增了 `columnTaskMedianTopLast`。实现口径为：先按 reduced cost 从当前 restricted columns 中顺序评价最多 `2K` 条兼容列，其中 `K=bidirectionalMidpointColumnLimit`，默认 `K=400`，因此默认先评价 800 条；然后按每条列的 `lastCompletion` 从大到小排序，取最晚的 400 条；最后只用这 400 条列内所有任务完成时间的中位数作为 `Tmid`。这个策略没有覆盖原来的 `columnTaskMedian`，因此可以直接对比。

013、half-domain、`completionBound=allCycles`、`maxNodes=2`、probe 关闭下，本次 `columnTaskMedianTopLast` 结果为 `solve=40.031s, exact=25.002s, exactCalls=8, cols=5847`。同口径已有结果为：`default` 为 `solve=11.370s, exact=3.984s`，`completionBound` 为 `solve=10.057s, exact=3.267s`，`columnLastAvg-current400` 为 `solve=14.976s, exact=9.155s`，旧 `columnTaskMedian` 为 `solve=29.494s, exact=16.019s`。因此新策略没有改善 013，反而更慢。

关键原因是新策略没有真正把 `Tmid` 推右。root 第一轮旧 `columnTaskMedian` 的统计为 `lastMinAvgMax=860/959.265/1078`，`taskMedian=463`；新 `columnTaskMedianTopLast` 的统计为 `lastMinAvgMax=958/998.628/1114`，说明二次筛选确实选到了列末更晚的列，但 `taskMedian` 仍然是 `463`。也就是说，013 上偏左不只是短列污染造成的；即使在列末较晚的长列中，大多数任务的完成时间也集中在较早位置，少数尾部任务把列末时间拉到很右。用“列内所有任务完成时间中位数”刻画任务量中心，在这个算例上天然会偏左。

从 pricing 指标看，差距主要仍来自 exact pricing。累计 exact pricing 中，`columnTaskMedianTopLast` 的 `bwKept=71777`、`joinPairs=3511471`、`completionBoundEval=1576756`、`completionBoundBuild=10.127s`，都高于旧 `columnTaskMedian` 的 `bwKept=64211`、`joinPairs=2882944`、`completionBoundEval=1381328`、`completionBoundBuild=7.218s`。root 第一轮二者完全相同，都是 `Tmid=463`、`fwKept=2020`、`bwKept=7072`、`joinPairs=644584`；新策略后续多跑一轮 exact pricing，并且 node2 仍保持 `Tmid≈461`，导致累计耗时更差。

probe 的结果继续支持这个判断。开启 probe 后，它从 `reference=463` 出发，发现 `Tmid=463` 时 `kept=2020:7072`、score 明显偏右侧压力大；动态右移到 `509.3` 后 score 降低，最终第一轮选择 `Tmid=554.628`，此时正式 pricing 的 `joinPairs` 降到 `181065`，远低于 `Tmid=463` 的 `644584`。probe 本身每个候选没有单独计时，日志只记录每个候选的 pop、kept、queue、bound 和 score；第一轮 6 个候选合计后，正式 pricing 日志中的该轮总时间为 `5311ms`，明显高于 probe-off 的单次正式 pricing，因此当前 probe 更适合做诊断或 hard-node fallback，不适合默认每轮开启。

当前结论是：`columnTaskMedianTopLast` 可以保留为实验策略，但不适合作为默认；013 上更有效的方向不是继续筛掉短列，而是把“列内任务完成时间分布”的参考点向右侧分位数或 probe 选择靠拢。若后续还要基于列信息自动定 `Tmid`，应考虑记录更高分位数，例如 60%/65% completion quantile，或者用 probe 只在疑似 hard node 上对当前 reference 做小范围右移校准。

## 11. 2026-06-07 probe 计时与策略选择

日志中的 `midpointColumns count/lastMinAvgMax/halfMinAvgMax` 是列 timing 样本的摘要。例如 `400/860.0/959.265/1078.0/352.0/443.3275/523.0` 表示本轮统计了 400 条候选列；这 400 条列的列末最优完工时间 `lastCompletion` 的最小、平均、最大分别是 `860.0/959.265/1078.0`；每条列中间位置任务的完工时间 `halfCompletion` 的最小、平均、最大分别是 `352.0/443.3275/523.0`。后面的 `midpointColumnTasks count/minAvgMedianMax` 则是把这些列内所有任务完成时间放在一起后的任务级分布。

本次修正了 probe 起点：probe 现在从当前策略已经实际算出的 `tMid` 出发，而不是无条件优先使用 `midpointColumnTaskMedian`。这个改动对 `columnTaskMedian` 没影响，因为它本身 `tMid=taskMedian`；但对 `columnLastAvg` 很关键，因为 `columnLastAvg` 的实际 `tMid=(left+lastAvg)/2`，如果仍从 task median 出发，就不是“基于 columnLastAvg 的 probe”。

probe 的方向规则如下。每个候选 `Tmid` 跑有限 pop dry-run 后，根据 `bidirectionalMidpointProbeScore` 计算左右压力。当前默认 `queue` 模式使用 `left=fwKept+fwQueueRemaining`、`right=bwKept+bwQueueRemaining`。若 `left>right`，说明 forward 侧压力更大，下一个候选设为 `current*(1-moveRatio)`，即把 `Tmid` 左移以缩小 forward 半域；否则说明 backward 侧压力更大，下一个候选设为 `current*(1+moveRatio)`，即把 `Tmid` 右移以缩小 backward 半域。`kept` 模式只看 `fwKept/bwKept`，`remaining` 模式只看 `fwQueueRemaining/bwQueueRemaining`，`peak` 模式才看 `fwKept+fwQueuePeak` 和 `bwKept+bwQueuePeak`。

新增 `ms` 字段后，013 上 `columnLastAvg+probe5000` 的 root 第一轮候选耗时可以直接看到。候选序列为 `492.13 -> 541.35 -> 595.48 -> 535.93 -> 589.53 -> 530.57`，单候选耗时分别约 `564/283/150/241/134/231ms`，合计约 `1.60s`。它最终选中 `541.35`，正式 pricing 第一轮 `fwKept:bwKept=2848:2712`、`joinPairs=225344`，比不开 probe 的 `columnLastAvg` 第一轮 `2342:5032`、`joinPairs=489848` 更均衡，说明方向判断有用；但总结果为 `solve=24.545s, exact=15.515s`，仍慢于不开 probe 的 `solve=14.976s, exact=9.155s`，因为 probe 自身开销和后续列生成路径变化抵消了第一轮收益。

把 pop 降到 2000 后，`columnLastAvg+probe2000` 总结果改善为 `solve=20.807s, exact=11.866s`，但仍慢于不开 probe。更重要的是，它暴露了过小 pop 的风险：root 第一轮 `Tmid=492.13` 时只跑 2000 pop，backward 侧几乎还没展开，日志为 `kept=2264:0, q=407:1`，于是 score 误判为 forward 过重，把下一轮往左移，最终选中 `438.49`，正式 pricing 第一轮变成 `fwKept:bwKept=1749:8914`、`joinPairs=605431`，比不开 probe 更差。这说明 pop 不能盲目压小；如果预算太小，probe 看到的是初始化顺序偏差，不是真实左右压力。

`completionBound+probe2000` 同样不适合作为默认。不开 probe 的 `completionBound` 是当前 013 两节点里最好的结果，`solve=10.057s, exact=3.267s`；打开 probe2000 后变为 `solve=19.174s, exact=10.055s`。第一轮 probe 从 `527.5` 出发，但 2000 pop 同样先看到 backward 不充分的问题，最终选中 `427.275`，正式 pricing 第一轮 `fwKept:bwKept=1668:9864`、`joinPairs=693485`，明显劣化。

当前建议需要按节点深度区分。`completionBound` 在 013 的 `maxNodes=2` 浅层测试里最好，但不能直接推广成全局默认；`default` 仍应作为稳健对照，`columnLastAvg` 可保留为没有合适 completion-bound 时间信号时的候选，但不应默认配 probe；`columnTaskMedian` 和 `columnTaskMedianTopLast` 暂不推荐。probe 的价值主要是诊断 hard node 或后续做受控 fallback：若要用，不能把 pop 设得太小，至少要保证 forward/backward 都有足够展开；同时应考虑只在上一轮 exact pricing 明显爆炸时触发，并限制候选数，而不是所有节点默认开启。

## 12. 2026-06-07 completionBound 深节点反例与 probe 搜索方向

重新检查 013 的深节点诊断日志后，需要纠正上面的浅层结论。`completionBound` 在 node1/node2 表现好，但在 013 的 node3 明确出现 forward 爆炸。该节点日志中 `tMid=563.5`、`midpointStrategy=completionBound`、`midpointRef=1102.0`、`pricingHorizon=3585.0`，正式 exact pricing 进入 forward 阶段后长时间没有开始 backward：heartbeat 显示 `fwKept` 从 `122982` 增长到 `215008`，`fCand` 从 `1302508` 增长到 `3745642`，`fBuilt` 到 `1771683`，`fBoundSurvivors` 到 `416954`，而 `bwPops` 仍为 `0`。这说明 completion bound 在该节点确实剪掉了大量 forward child，但剩余 survivor 仍足够多，且正式 pricing 当前先跑完整 forward，因此一个偏右或过宽的 `Tmid` 会在到达 backward 前就爆掉。

这也解释了为什么 `completionBound` 不能直接作为默认结论。它给出的 reference 是松弛 completion-bound 函数上的时间信号，不是左右 label 数量均衡的保证；在 root 或浅节点可能刚好合适，但在带分支、pricingOnly 禁弧和不同 dual 结构的深节点上，松弛信号可能仍偏右，使 forward 半域过大。013 node3 就是这个反例。

对 probe 的动态方向规则也要收紧。当前简单规则是：若 `left>right`，下一个候选 `Tmid=current*0.9`；否则 `Tmid=current*1.1`。这个规则不会代码层面死循环，因为有候选数上限和重复候选过滤，但算法上确实可能在最优附近左右摆动，浪费 probe 预算。更合理的后续方案是“先定方向扩张，再用 bracket 二分”：从当前 `Tmid` 出发，若 forward 压力大就持续向左试探，若 backward 压力大就持续向右试探；一旦方向反转，就得到一个左右压力符号不同的区间，后续只在该区间中点试探，而不是继续乘 `0.9/1.1` 来回震荡。最终选择所有已测候选中 score 最好的点，停止条件用候选上限、区间宽度和 score 改善幅度共同控制。

日志里某些候选 pop 不到上限，例如 `4516/4535`，不是异常。当前 `probePopLimit` 是上限，不是必须跑满的目标；如果 forward/backward 队列都提前耗尽，probe 会自然结束。对应 summary 的 `ex=FB` 表示两侧队列都 exhausted，因此 `4516 < 5000` 说明这个候选在有限 probe 语义下已经跑空了，而不是被过早截断。真正危险的是 `pop` 很小且某一侧还没展开，例如 `probe2000` 中出现 `bwKept=0` 或 `bwQueuePeak` 很小，此时 score 反映的是初始化顺序偏差，不是真实左右压力。

## 13. 2026-06-07 probe v2 参数收敛

本轮按用户要求把 probe 逻辑收紧为：`rank=0` 直接停止，不再设置额外阈值；删除原来的 `rank=2` 分层，只保留“队列耗尽”和“未耗尽”两类可靠性；score 改成直接输出左右压力倍数，默认 `queue` 口径为 `max((fwKept+fwQueueRemaining+1)/(bwKept+bwQueueRemaining+1), 反比)`，不再用 `abs(log(...))`。这样日志里的 score 就是直观的 F/B 失衡倍数，便于和完整 pricing 的 label 倍数比较。`peak` 口径仍作为单独可选项保留，用于观察 `kept+queuePeak`。

新的迭代方式是从当前策略给出的 reference 出发，按左右压力决定下一次向左或向右试探。若连续方向发生反转，则在上一点和当前点之间补一个 bracket midpoint，只做这一次二分式补测，然后从所有已测候选中选 score 最小者。若某个未耗尽候选的 score 已小于 `earlyStopRatio`，不会立刻停，而是继续做 `extraAfterThreshold` 个候选；当前默认只补 1 个，防止第一个“看起来已经均衡”的点附近还有更好的点。probe 的总候选上限默认 5。

013、`maxNodes=4`、`completionBound=allCycles`、half-domain、`NODE_LIMIT` 口径下，本轮主要结果如下。所有结果 `valid=true`，因此这些数值可以用于比较参数对节点内 pricing 成本和列生成路径的影响，但不是完整最优性对照。

| 配置 | solve(s) | exact(s) | exact calls | pricing calls | cols | 判断 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| move=0.10, early off, pop=5000 | 119.203 | 99.066 | 14 | 66 | 7396 | 无早停，候选试探过多，慢 |
| move=0.10, early=1.25, pop=5000 | 107.267 | 83.697 | 14 | 66 | 7396 | 有改善但仍慢 |
| move=0.10, early=1.50, pop=5000 | 98.755 | 81.245 | 14 | 66 | 7411 | 继续改善但仍不够 |
| move=0.15, early=1.50, pop=5000 | 70.730 | 54.828 | 14 | 63 | 6433 | 本轮最好 |
| move=0.15, early=1.50, pop=5000, reuse=true | 71.105 | 55.252 | 14 | 63 | 6433 | 复用没有收益 |
| move=0.15, early=2.00, pop=5000 | 101.837 | 85.334 | 15 | 65 | 6392 | 阈值太松，接受偏差点 |
| move=0.15, early=1.50, pop=2500 | 78.214 | 63.447 | 13 | 54 | 7185 | probe 偏浅，选点偏差变大 |
| move=0.15, early=1.50, pop=10000 | 84.394 | 67.926 | 10 | 53 | 7573 | probe 更准但自身太重 |

`move=0.05` 也试过，但在同样 013 口径下明显拖慢，运行数分钟仍未完成，已终止。原因是每次只移动 5% 时，多个候选点高度接近，probe 预算消耗在相邻点重复估计上；而 hard node 需要的是快速把过右的 reference 拉到更靠左的区间。`move=0.10` 能改善但仍慢，`move=0.15` 在本轮 013 上更有效。暂不继续放大到 0.20，原因是已有表明 0.15 可以在 direction 反转后通过 bracket 回补，步长再大可能更依赖二分补测，风险需要另开实验确认。

`earlyStopRatio=2.0` 的结果说明阈值不能太松。虽然 2 倍以内看起来仍然不算严重失衡，但在 node3 等 hard 节点上，probe 的有限 pop 倍数和完整 pricing 的真实 kept/join 压力并不完全等价；过早接受 2 倍左右的候选，会把后续完整 pricing 推到更重的路径。`earlyStopRatio=1.5` 更稳，且额外补测 1 个候选能避免在第一个刚过阈值的点直接停下。

`popLimit` 本轮最合理的是 5000。`pop=2500` 的成本低一些，但在 013 上选点偏差导致完整 exact 反而从 `54.828s` 增到 `63.447s`；`pop=10000` 的候选判断更深，exact call 数减少到 10，但 probe 本身变重，完整时间增到 `67.926s`。这支持当前判断：probe 不能太浅，否则还是初始化偏差；也不能太深，否则每轮 Tmid 选择成本接近一次小型 pricing。

同节点复用本轮不建议默认开启。`reuse=true` 的总时间 `71.105s` 略慢于 `reuse=false` 的 `70.730s`，列数和 pricing call 基本相同。日志中复用主要在同一 node 的后续 exact pricing 轮次生效，但 dual 和列池已经变化，旧 Tmid 只提供量级参考，不一定更好；在这组数据里它没有抵消复用带来的路径变化风险。因此当前默认保持 `bidirectionalMidpointProbeReuseWithinNode=false`。

当前落地默认参数仅在 probe 被显式开启时生效：`popLimit=5000`，`maxCandidates=5`，`moveRatio=0.15`，`earlyStopRatio=1.5`，`extraAfterThreshold=1`，`bracketOnDirectionChange=true`，`reuseWithinNode=false`。全局 `bidirectionalMidpointProbe` 仍保持默认关闭。这个结论不是说 probe 已经适合作为全局默认，而是说如果后续把 probe 作为 hard-node fallback 或诊断开关，这组参数比本轮试过的其它组合更稳。

## 14. 2026-06-07 probe tie-break 与 011 对照

本轮按“主指标仍用 queue，主指标接近时再看更灵敏指标”的思路，给 probe 候选选择增加了可选二级比较。新增参数为 `bidirectionalMidpointProbeTieScore` 和 `bidirectionalMidpointProbeTieTolerance`，测试入口对应 `twet.bpc.fullDomainCompare.midpointProbeTieScore` 与 `twet.bpc.fullDomainCompare.midpointProbeTieTolerance`。默认值仍为 `off/0.0`，因此不改变现有行为。当前比较顺序为：先比较候选可靠性；再比较主 score；若两个主 score 的倍数差不超过 tolerance，才用二级 score 继续比较；最后再比较总压力、pop 数、耗时和 `Tmid`。这样 peak 只在主判断很接近时打破平局，不会接管主要选择逻辑。

在 `tmp-wet030_from040_011_2m`、`halfDomain`、`completionBound=allCycles`、`maxNodes=4`、`midpointStrategy=completionBound` 下做了四组对照。不开 probe 的 baseline 在 node1/node2 分别约 `18.954s/14.586s` 后进入 node3，超过 90 秒没有返回，因此中断；这说明 011 的慢点确实在更深节点的 exact pricing/整数启发式组合，而不是 root。打开 probe 后，`queue` 主指标、无二级比较的结果为 `solve=48.521s, exact=22.558s, nodes=4, valid=true`，node3 可以正常返回，node3 exact 约 `11.622s`，完整 label 统计为 `fw kept/dominated=6996/20856, bw kept/dominated=2089/278`。这说明 probe 对 011 的 hard node 有实质作用。

二级比较没有带来收益。`queue + peak tieTolerance=0.05` 的结果为 `solve=53.697s, exact=24.567s`；`queue + peak tieTolerance=0.10` 为 `solve=52.798s, exact=25.291s`。两组 node3 的最终选点仍为 `Tmid=378.0`，完整 label 数也和无二级比较一致，差别主要是运行波动和 probe/CB 构造耗时波动。进一步看 node3 的候选序列，主 queue score 从 `726 -> 617 -> 524 -> 445 -> 378` 单调大幅下降，约为 `104 -> 36 -> 18 -> 10 -> 3.56`，相邻候选差距远大于 `0.05/0.10`。因此 tie-break 在这个 hard node 上根本不会改变选择。

当前结论为：`queue` 作为主指标仍然合理；`peak` 二级比较可以保留为诊断开关，用于以后主 score 很接近但队列峰值差异明显的节点，但不建议默认开启。`011` 的关键收益来自 probe 把 completionBound 给出的偏右 reference 一路左移到更合理的 `Tmid=378`，而不是来自 secondary score。若后续继续优化，优先方向应是减少 probe 候选次数、改进 hard-node 触发条件，或者分析 `queueScore` 已经足够好时能否提前停止；secondary tie-break 只能处理“主指标近似平局”的小问题。

## 15. 2026-06-07 remaining 作为二级 score

继续复核 tie-break 语义后，`peak` 不适合作为默认建议口径。它统计的是 probe 过程中队列曾经达到过的峰值，容易受早期扩展顺序和瞬时膨胀影响，不一定代表候选结束时仍然没处理完的压力。用户要求的二级指标更准确地说应是 `remaining`：只看 `fwQueueRemaining:bwQueueRemaining`，也就是有限 pop 结束后两边队列里还剩多少 label。这个指标不掺入 kept label，也不看峰值，只回答“如果继续跑下去，哪一边剩余压力更不均衡”。

本轮新增 `remaining` score mode。`remainingScore = imbalance(forwardQueueRemaining, backwardQueueRemaining)`，`leftPressure/rightPressure` 在该模式下也只返回 remaining queue。它可以作为主 score 使用，但更合理的用法是 `bidirectionalMidpointProbeScore=queue`，`bidirectionalMidpointProbeTieScore=remaining`，并设置较小的 `bidirectionalMidpointProbeTieTolerance`，例如 `0.05`。这样第一层仍用 `kept+remaining queue` 判断总体压力；只有两个候选总体压力差距很小时，才用“剩余队列是否还不均衡”打破平局。

需要注意，若某个候选两边队列都已经耗尽，则 `remaining=0:0`，`remainingScore=1.0`。这不是问题，而是说明 remaining 在这个候选上没有额外区分力，比较器会继续落到总压力、pop 数、耗时和 `Tmid` 等后续规则。`remaining` 主要对未耗尽的 hard node 候选有意义，尤其是主 `queue` score 很接近、但其中一个候选仍然留下明显单侧队列时。

实现验证使用 `tmp-wet030_from040_011_2m`、`maxNodes=1`、`midpointProbeTieScore=remaining`、`midpointProbeTieTolerance=0.05` 做 smoke。结果 `solve=13.657s, exact=3.457s, valid=true`，日志中已输出 `tieScoreMode=remaining` 与 `remainingRatio`。root 候选均为 `rank0`，remaining 全为 `0:0`，因此本次 smoke 只验证配置和日志口径，不用于判断策略优劣。后续若要比较策略，应在 node3 这类未耗尽 hard node 上对比 `tieScore=off` 与 `tieScore=remaining`。

## 16. 2026-06-07 remaining tie-break 的可比性规则

进一步收紧二级比较规则。当前候选选择不再把二级分不出来时继续压到总压力、pop、耗时或 `Tmid`，而是先尊重一级 score。具体规则为：先比较可靠性；若一级 `queue` score 不完全相等，则只有在一级 score 足够接近、二级 score 可比且二级确实分出高低时，才用二级结果；否则直接返回一级更小的候选。只有一级 score 本身完全相等时，才继续落到总压力、pop 数、耗时和 `Tmid`。

对 `remaining` 二级指标还增加了可比性限制。只有两个候选的 forward/backward 队列都没有耗尽时，才比较 `fwQueueRemaining:bwQueueRemaining`。如果任一候选的一侧队列已经耗尽，就不使用 remaining 二级比较，而是仍按一级 score 选择。原因是 remaining 中出现 `0` 时，其含义不再是“双方仍未展开压力的对称比较”，而是某一侧已经跑空；这时继续用 remaining 会把耗尽状态误当成更均衡或更不均衡的证据。

规则收紧后重新做了 011 root smoke：`status=NODE_LIMIT, solve=12.423s, exact=3.438s, calls=4, valid=true`。这个测试仍只是确认配置、日志和比较器流程能正常跑通，不用于证明 remaining 二级策略在 hard node 上一定更优。

## 17. 2026-06-07 probe 后续效率优化分析

当前 probe 的固定成本主要来自每个候选都调用 `rebuildHalfDomainForCurrentMidpoint()` 和 `initializeSearchState()`，然后跑有限 forward/backward 扩展。这里没有 final join，也不直接生成列，但 `initializeSearchState()` 仍会创建候选列堆、signature map、active signature set，并扫描当前 restricted columns；这些对象对 probe 本身没有直接作用，后续可以拆出一个 label-only 初始化路径，减少每个候选的固定开销。

在现有框架内，优先级较高的优化是三类。第一，减少不必要候选：当第一个候选已经 `rank0` 或一级 queue score 足够好时，允许 `extraAfterThreshold=0` 直接停止；后续也可以把 root 或动态时间窗很强的节点配置为不 probe。第二，复用同一 node 内的 probe 结果：现有 `bidirectionalMidpointProbeReuseWithinNode` 已提供入口，但是否默认打开还需要用 hard node 验证；较稳的做法是先复用，若后续 exact pricing 的真实正反比例明显恶化再重新 probe。第三，降低单候选 dry-run 成本：拆出 probe 专用初始化，跳过候选列 heap/signature 容器；若诊断 heartbeat 打开，也应避免 probe 扩展阶段输出 heartbeat。

新的策略方向主要有三个。其一是分级 probe：先用小 pop 粗扫候选，只在 best 与次优接近或方向反转时，对少数候选加深，而不是每个候选都用同一 popLimit。其二是利用上一轮 exact pricing 的真实正反 label 比例来调整下一轮 `Tmid`，这比 probe 指标更真实，且没有额外 dry-run 成本，适合同一 node 多轮 pricing。其三是改候选移动方式：当前是 `current*(1±moveRatio)`，后续可改为按 `[leftBound, pricingHorizon]` 区间移动，例如向左移动 `moveRatio*(current-leftBound)`、向右移动 `moveRatio*(pricingHorizon-current)`，这样对时间原点和 horizon 尺度更稳定。

暂不建议把 probe 改成过度简化的无 dominance 或只看 completion bound 的版本作为默认。它们会更快，但和正式 exact pricing 的 label 压力相关性可能更差，容易重新出现“probe 看起来均衡、完整 pricing 仍单侧爆炸”的问题。更现实的路线是先做低风险的固定开销削减和同 node 复用，再用 hard node 日志决定是否引入分级 probe。

## 18. 2026-06-07 probe 初始化优化与同 node 复用实验入口

本次先处理 probe 候选初始化的固定开销。原先 `runMidpointProbeCandidate()` 直接调用完整 `initializeSearchState(lp)`，会在每个候选 `Tmid` 上创建正式 pricing 用的候选列堆、signature map、active signature 集，并扫描当前 restricted columns。probe 本身只做有限 label 扩展，不做 final join，也不返回列，因此这些候选列结构对 probe 选择没有作用。现在拆成 `initializeLabelSearchState()` 和 `initializeCandidateState(lp)`，正式 pricing 仍调用完整初始化，probe 候选只调用 `initializeProbeSearchState()`，保持 label queue、dominance store、active label 表等核心逻辑一致，但跳过 active restricted column signature 扫描。

同时新增默认关闭的实验开关 `bidirectionalMidpointProbeExactFeedback`。它必须和 `bidirectionalMidpointProbeReuseWithinNode=true` 一起使用。完整 exact pricing 结束后，若 forward/backward kept label 比例超过当前 `earlyStopRatio`，则把下一轮同 node 的 probe reference 沿压力较大方向反向修正：forward 压力大则下轮 reference 左移，backward 压力大则右移。该逻辑只写入同 node reuse map，不改变本轮已经完成的 pricing。

011 root 顺序 smoke 结果如下：base probe 为 `solve=10.264s, exact=2.303s, calls=4`；只打开同 node reuse 为 `solve=10.923s, exact=2.324s`；打开 reuse+exactFeedback 为 `solve=10.695s, exact=2.343s`，三者目标与 bound 一致且 `valid=true`。这个 root 节点每轮 probe 都是 `rank0`，因此复用和 feedback 的收益不明显；feedback 在最后一轮把 `Tmid` 从 525 附近推到 604，probe 时间降到约 6.8ms，但整体 root 时间没有改善。当前结论是：probe 初始化优化可以保留；reuse/feedback 需要在 012/013 这类 hard node 上继续测，暂不建议默认打开。

## 19. 2026-06-07 011 hard node 上的 reuse/feedback 对照

按用户提醒，root 节点过于简单，不能判断 probe 复用和 feedback 是否有用。因此本轮改用 `tmp-wet030_from040_011_2m`、`halfDomain`、`completionBound=allCycles`、`maxNodes=4` 口径测试。这个口径会进入此前反复出现过压力的 node3，比 `maxNodes=1` 的 root smoke 更能反映 hard node 行为。三组测试都使用 `midpointStrategy=completionBound`、`midpointProbe=true`、`popLimit=5000`、`maxCandidates=5`、`moveRatio=0.15`、`earlyStopRatio=1.5`、`extraAfterThreshold=1`、`bracket=true`、`score=queue`、`tieScore=remaining`、`tieTolerance=0.05`。

结果如下，三组的 `incumbent=14024`、`bound=13528.866667`、`nodes=4`、`pricingCalls=64`、`exactCalls=14`、`cols=6011`、`valid=true` 都一致，说明差异主要来自 exact pricing 内部的 Tmid 选择和搜索开销，而不是分支路径或列数大幅变化：

| 配置 | solve(s) | exact(s) | heuristic(s) | 判断 |
| --- | ---: | ---: | ---: | --- |
| base probe | 48.800 | 23.009 | 11.156 | 每轮都从 completionBound reference 重新试探 |
| reuseWithinNode | 44.612 | 19.331 | 10.962 | exact 时间下降约 16.0% |
| reuseWithinNode + exactFeedback | 43.863 | 18.551 | 10.619 | exact 时间较 base 下降约 19.4%，较单独 reuse 再降约 4.0% |

node3 的日志能解释收益来源。base 的第一轮 exact pricing 从 `ref=669` 依次试探 `669 -> 569 -> 484 -> 411 -> 349`，选中 `349`，完整 pricing 为 `fwKept=3027, bwKept=2379, time=3400.747ms`。但第二、三轮又重新从新的 completionBound reference `726` 开始，最后都选到 `378`，完整 pricing 分别为 `fwKept=6858,bwKept=2059,time=4045.200ms` 和 `fwKept=6996,bwKept=2089,time=4056.319ms`。也就是说 base 在同一个 node 的后续轮次反复从明显偏右的 reference 重新左探，浪费了候选 dry-run，也让最终 `Tmid=378` 对 forward 仍偏重。

打开 `reuseWithinNode` 后，node3 第一轮仍选 `349`。第二轮不再从 `726` 重新开始，而是从上一轮的 `349` 作为 reference，试探 `349 -> 297 -> 323`，通过 bracket 选中 `323`，完整 pricing 变为 `fwKept=1536,bwKept=2462,time=2518.549ms`。第三轮继续从 `323` 开始，试探 `323 -> 371 -> 347`，选中 `347`，完整 pricing 为 `fwKept=2849,bwKept=2330,time=2738.836ms`。这说明在该 hard node 上，同一 node 内多轮 pricing 的合理 Tmid 量级确实没有剧烈变化，复用上一轮 selected Tmid 可以避免从 completionBound 偏右点重复搜索。

再打开 `exactFeedback` 后，第二轮仍从 `349` 出发并选中 `323`，但完整 pricing 的真实比例为 `fwKept:bwKept=1536:2462`，超过 `earlyStopRatio=1.5` 且表现为 backward 偏重，于是 feedback 把下一轮 reference 右移到 `371`。第三轮从 `371` 出发，试探 `371 -> 315 -> 343`，选中 `343`，完整 pricing 为 `fwKept=2580,bwKept=2352,time=2560.600ms`。相比单独 reuse 的第三轮 `Tmid=347,time=2738.836ms`，feedback 略微改善了真实左右压力和耗时。

当前结论是：root smoke 不能说明 reuse/feedback 的价值；在 011 node3 这类 hard node 上，`reuseWithinNode` 明确有收益，`exactFeedback` 也有小幅追加收益。更稳妥的落地建议是，`initializeProbeSearchState()` 这种 label-only 初始化优化可以保留；`reuseWithinNode` 可以作为 hard-node 配置优先测试；`exactFeedback` 仍保持实验开关，原因是它依赖上一轮完整 pricing 的 kept label 比例，在不同 dual/列池变化更剧烈的节点上还需要 012/013 继续验证。

## 20. 2026-06-08 Tmid 策略收敛到 probe 的当前判断

本轮讨论把 Tmid 策略的重点重新收敛到 probe。之前测试过的 `columnTaskMedian` 偏左，导致 backward label 和 join 压力上升。最初怀疑是当前列池里较短列或可直接接 sink 的列较多，把任务级 completion time 的中位数往左拉。之后又试过“先按 reduced cost 选较好列，再按列末最优完工时间从大到小取一部分列，再在这些列内任务完工时间上取中位数”的 `columnTaskMedianTopLast` 思路，但 013 上算出来的中位数几乎没有右移，效果仍然不好。

这个现象可能有两个原因。第一，很多列内部的大部分任务确实很早完成，只有少数尾部任务把列末完工时间拉到右侧；此时即使先筛选列末更晚的列，任务级中位数仍会停在较早位置。第二，任务级中位数、75 分位数等统计量可能在某些算例上有较大的平台区间，换一种列筛选方式不一定能改变最终 Tmid。也可以继续尝试按列内任务数较多的列来计算 median，但这仍然是一次性启发式设点，无法保证在每个 node 上都不左偏或右偏。因此当前不再继续把主要精力放在改 median 上，median 只保留为一种初始 reference 策略。

这和 `completionBound`、`columnLastAvg` 等策略的结论是一致的：它们都能提供一个有信息的 reference，但没有一个策略能保证每个 node 上都给出正好均衡的 Tmid。一旦 reference 偏差较大，half-domain 双向会退化成接近单向的扩展，进而出现 hard node 上 forward 或 backward label 爆炸。因此后续更合理的主线是把这些策略都当作初始 reference，再用 probe 类似强分支的方式对多个候选 Tmid 做局部试探。

当前 probe 的定位也进一步明确。有限 pop 下的正反向压力比例和完整 exact pricing 的最终比例并不完全一致，尤其是 `rank=1` 候选会低估后续队列的子孙规模；之前 013 node3 就出现过 probe 看起来近似均衡、完整扩展后仍有数倍差异的情况。但 probe 仍然有价值，因为它能筛掉极端偏右或偏左的 Tmid，把搜索从明显趋近单向扩展的区域拉回可求解区间。由于 completion bound 已经参与 probe 扩展，若某个 Tmid 在早期就明显导致单侧 survivor 队列膨胀，这种差异通常能在有限 pop 内体现出来。换句话说，probe 未必选到最快的 Tmid，但希望能选到“足够接近均衡、至少能求解动”的 Tmid。

由此形成当前工作假设：后续重点放在优化 probe，而不是继续深挖 median。具体包括候选生成、方向反转 bracket、同 node 复用、exactFeedback、低成本初始化、以及 hard-node 触发条件。median、completionBound、columnLastAvg 等策略只作为 reference 来源；如果 reference 已经很好，probe 应尽量低成本结束；如果 reference 明显偏，probe 负责把 Tmid 拉回合理区间。

还有一个待实现问题是 `rank=0` probe 结果是否可以直接复用。很多 node 上 probe 候选会把 forward/backward 队列跑空，此时从 label 扩展角度看，它已经完成了正式 pricing 的主要 labeling 阶段。如果这个候选最终被选中，理论上可以直接基于 probe 产生的 label 状态执行正式 join 和候选列生成，而不必再用同一个 Tmid 重建 half-domain 并完整跑一次 exact pricing。这个方向的收益可能很直接，尤其是 root 或简单 node 上 `rank=0` 频繁出现。但实现上要确保 probe 状态和正式状态完全一致，包括 candidate pool、signature 去重、join 所需索引、统计字段、以及不会因为 probe 省略了某些初始化而缺少正式 pricing 需要的数据。当前先记录为后续优化点，不立即改。

最后，非对称双向 labeling 仍值得重新测试。之前非对称版本效果差，主要是在没有当前 completion bound 体系参与的口径下比较，动态边界对正向较松，导致扩展出大量 label。现在如果把 completion bound 接入非对称双向，可能会改善这种过松扩展；但它和当前 half-domain probe 的机制不同，因为非对称版本不存在固定半域裁剪，相关实现和统计口径会更多。后续可以单独开一轮实验：先确认非对称双向是否完整接入 completion bound，再和当前 probe-based half-domain 在 011/012/013 hard node 上比较。如果非对称仍然不稳，主线继续放在 probe 优化上。

## 21. 2026-06-08 rank0 probe label 复用实现

本次把前面记录的 `rank=0` probe 复用点做成了保守实现。核心判断是：只有被最终选中的 probe 候选正好是当前内存里最后一次跑完的候选，并且该候选的 `reliabilityRank(...) == 0`，也就是 forward/backward 两侧队列都已经耗尽，才允许复用 probe 已生成的 label 状态直接进入正式 join。其它情况仍走原来的正式 pricing 流程：按选中的 `Tmid` 重建 half-domain，再重新初始化 source/sink 并完整 labeling。

这里没有为任意历史候选做深拷贝，也没有尝试复用不是最终选中候选的 label。原因是 probe 会连续尝试多个 `Tmid`，每次都会重建 half-domain 和 label/dominance 状态；如果最终 best 不是最后一次候选，当前内存状态已经不对应 best，直接 join 会错。因此当前实现只复用“最后一次候选就是 best 且 rank0”的状态，收益范围小一些，但状态一致性最清楚。

复用路径下，probe 已经完成正式 labeling 所需的 forward/backward label 扩展和 dominance 插入；`initialize()` 不再调用完整 `initializeSearchState()`，只补一次 `initializeCandidateState(lp)`，用于正式 join 阶段的候选列堆、signature 去重和 active restricted column signature。随后 `solve()` 跳过 `initializeBackwardSink()`、forward 扩展和 backward 扩展，直接 compact、join、finalize。这样避免同一个 `Tmid` 在 rank0 情况下被 probe 跑完一次、正式 pricing 又跑一遍。

为了避免误复用，增加了两个失效保护。第一，`resetStatistics()` 和 `runMidpointProbeIfEnabled()` 开头都会清掉 `midpointProbeLabelsReadyForJoin`。第二，如果 `runFullMidpointDiagnosticIfEnabled()` 执行，它会重建搜索状态用于诊断，所以会显式清掉该标记，避免 diagnostic 之后主流程误以为 probe label 仍可 join。日志中会在 pricing summary 里追加 `rank0LabelsReused=true`，并把 completion state 写成 `probe rank0 queues exhausted`，方便后续识别。

验证口径为 `tmp-wet030_from040_011_2m`、`halfDomain`、`completionBound=allCycles`、`maxNodes=1`、`midpointStrategy=completionBound`、`midpointProbe=true`、`popLimit=5000`、`score=queue`、`tieScore=remaining`。结果为 `NODE_LIMIT, incumbent=14258, bound=13525.866667, solve=15.176s, exact=3.233s, exactCalls=4, valid=true`。日志中 4 次 exact pricing 都出现 `probe rank0 queues exhausted` 和 `rank0LabelsReused=true`，说明 rank0 复用路径已经实际触发并能正常 join/finalize。

## 22. 2026-06-08 node 内 Tmid 复用策略再整理

继续讨论后，当前更合理的方向是把 `rank=0` probe 语义改得更直接：probe 过程中一旦某个候选把 forward/backward 两侧队列都跑空，就立即接受这个候选，不再继续试其它候选，也不再走“收集 candidates 后再选 best”的逻辑。原因是 `rank=0` 已经说明该 `Tmid` 在 probe 阶段完成了正式 labeling 的主要扩展工作；此时继续试探其它 `Tmid` 的边际收益小，而且会破坏当前内存里可直接 join 的 label 状态。保守状态检查仍然需要保留，但语义上应从“最后一次候选恰好是 best 才复用”收敛成“rank0 候选即接受并复用”。

`reuseWithinNode` 也不应继续简单写回本轮 probe 选出的 `best.tMid`。本轮 probe score 最小不等于后续 exact pricing 最快；更稳的做法是为每个 node 维护一个小的 reuse state，记录本 node 历史 exact pricing 表现最好的 `Tmid`。主准则先用完整 exact pricing 的耗时，直接用更小耗时更新，不额外加 `0.95` 这类噪声阈值。若两个 `Tmid` 的 exact 耗时接近，例如较大耗时的 30% 范围内，再用完整 pricing 后的 forward/backward kept label 均衡程度作为辅助选择。这样 reuse 的目标从“上一轮 probe 觉得好”改成“历史完整 pricing 真的跑得好”。

基于这个思路，`exactFeedback` 的方向修正暂时不作为主线。它用上一轮完整 pricing 的 forward/backward kept 比例决定下一轮 reference 左移或右移，直觉上合理，但阈值和步长都难稳定设定；如果上轮左移后下一轮又右移，还需要额外维护 bracket。相比之下，历史最优 exact 表现复用已经隐含利用了完整 pricing 反馈：耗时最小且较均衡的 `Tmid` 会自然留下来，后续 probe 只需围绕这个 reference 再局部试探。因此当前建议保留一种主 reuse：同一 node 内优先复用历史 exact 表现最好的 `Tmid`；`exactFeedback` 方向修正先保持实验开关或暂不启用。

该假设的依据是，同一 BPC node 内 branch、forbidden arc、pricing graph 等结构不变；对固定路径而言，任务最优完成时间、setup 和 release/due 约束都是常数，不随 dual 变化。因此合理 `Tmid` 的时间量级通常不会在同一 node 的相邻 pricing 轮次之间剧烈变化。dual 会改变 reduced cost、completion bound 剪枝和 survivor 分布，所以复用不是严格保证，但作为 reference 起点比每轮重新从 completionBound 偏右 reference 左探更合理。子节点继承父节点 `Tmid` 更激进，因为 branch 结构已经变化，暂时不做。

后续还可以把 probe 候选次数做成 node 内自适应。第一次进入某个 node 时没有历史 reference，可以允许 `maxCandidates=6` 之类较充分试探；一旦该 node 已有历史 best exact Tmid，后续轮次可以只试较少候选，例如最多 3 次，围绕历史 reference 做局部校准。这样能减少重复 probe 成本，同时保留在 dual 变化后微调 `Tmid` 的能力。

## 23. 2026-06-08 历史 exact best Tmid 复用实现

本次把上一节的 reuse 思路落到代码里。`rank=0` probe 现在语义上直接作为接受候选：一旦某个 probe 候选耗尽 forward/backward 两侧队列，就立即停止试探，最终 best 直接取这个候选，并复用当前 label 状态进入 join。这样避免 rank0 候选还参与后续候选选择，也避免因为继续试探覆盖掉可直接 join 的 label 状态。

同一 node 的 Tmid 复用从单个 `Double` 改成 `MidpointProbeNodeReuse` 状态。该状态只记录少量标量：历史 best exact Tmid、best exact 耗时、best F/B kept ratio、best kept label 总数，以及最近一次 exact 的对应指标。更新时不新增路径、函数或 label 计算，只复用本轮 exact pricing 已经有的 `System.nanoTime()` 耗时和 `forwardLabelsKept/backwardLabelsKept` 统计。

更新规则为：若当前 exact 耗时更小，直接替换历史 best；若当前耗时没有更小，但和历史 best 的耗时差不超过较大耗时的 `bidirectionalMidpointProbeExactTimeTieTolerance`，默认 30%，则进入二级比较；只有当前 F/B kept ratio 比历史 best 至少改善 `bidirectionalMidpointProbeExactBalanceImprovementTolerance`，默认 30%，才用当前 Tmid 替换历史 best。两个 30% 是独立参数，测试入口分别为 `twet.bpc.fullDomainCompare.midpointProbeExactTimeTieTolerance` 和 `twet.bpc.fullDomainCompare.midpointProbeExactBalanceImprovementTolerance`。

实现时还修正了一个容易削弱 reuse 的点：`GCNGBBStyleBidirectionalPricingEngine.reset()` 原来会清空 `midpointProbeReuseByNode`。但该 reset 会在前置启发式定价器加列后触发，如果清空，则 exact pricing 在同一 node 的多轮改进之间无法复用历史 Tmid。现在当 `bidirectionalMidpointProbeReuseWithinNode=true` 时，reset 只清理 reusable subtree bound，不清理 node Tmid reuse map；map 仍按 node id 区分，不跨 node 混用。

验证口径一为 `tmp-wet030_from040_011_2m,maxNodes=1`，开启 `reuseWithinNode` 和两个 30% 参数后，结果为 `NODE_LIMIT, incumbent=14258, bound=13525.866667, solve=16.249s, exact=3.247s, exactCalls=4, valid=true`。日志显示第一轮 `ref=strategy`，后续 exact pricing 均为 `ref=reuseBestExact`，并输出 `exactReuse=init/time/keep`。口径二为同算例 `maxNodes=4`，结果为 `NODE_LIMIT, incumbent=14024, bound=13528.866667, solve=54.817s, exact=20.829s, exactCalls=14, valid=true`。node3 中第一轮从 strategy reference 选到 `349`，第二轮从历史 best `349` 出发并通过 bracket 找到 rank0 `323`，第三轮从 `323` 出发继续局部试探，说明历史 best exact reuse 已在 hard node 中生效。该结果不说明它已经稳定优于 exactFeedback 方向修正，只说明新机制可运行且状态复用口径正确；后续还需继续和旧 reuse/exactFeedback 口径做多算例对照。

## 24. 2026-06-08 exact best 比较规则修正与自适应 probe 次数

继续按用户指出修正历史 exact best 的更新语义。上一版实现把“当前 exact 耗时更小”作为直接替换条件，即使当前耗时和历史 best 耗时只差在 30% 以内，也会因为微小时间波动覆盖掉原来更均衡的 `Tmid`。这和当前设计目标不一致：time tie tolerance 的含义应是“时间接近时先用二级指标判断”，而不是“只有当前更慢时才看二级指标”。因此本次把规则改为：若当前 exact 耗时和历史 best 耗时在 `bidirectionalMidpointProbeExactTimeTieTolerance` 范围内，则只在当前 F/B kept ratio 按 `bidirectionalMidpointProbeExactBalanceImprovementTolerance` 明显改善时更新；若两者耗时不接近，才由耗时支配，当前更快则更新、当前更慢则保留历史 best。当前 time tie tolerance 默认改为 10%，balance improvement tolerance 仍默认 30%；前者控制“时间是否接近”，后者控制“平衡改善是否足够大”。

同时实现了同一 node 内已有历史 exact best 后的自适应 probe 次数。第一次进入某个 node 时仍使用 `bidirectionalMidpointProbeMaxCandidates`，默认最多 5 个候选；一旦 reference 来源变成 `reuseBestExact`，候选上限改为 `min(maxCandidates, bidirectionalMidpointProbeReuseMaxCandidates)`，默认 3 个。这样后续轮次仍会围绕历史 best 做局部校准，但不会每轮都从 completionBound reference 开始做完整 5 次试探，减少重复 dry-run 成本。测试入口新增 `twet.bpc.fullDomainCompare.midpointProbeReuseMaxCandidates`。

用 `tmp-wet030_from040_011_2m`、`maxNodes=4`、`completionBound=allCycles`、`midpointStrategy=completionBound`、`midpointProbe=true`、`reuseWithinNode=true` 复测，结果为 `NODE_LIMIT, incumbent=14024, bound=13528.866667, solve=44.555s, exact=19.144s, exactCalls=14, valid=true`。这比上一版错误更新规则的 `solve=54.817s, exact=20.829s` 明显更快，也略好于此前 base probe 记录的 `solve=48.800s, exact=23.009s`，接近旧 `reuse+exactFeedback` 的 `solve=43.863s, exact=18.551s`。日志中 node3 的第三轮从历史 best `323` 出发试到 `347`，虽然 `347` 的 F/B ratio 更好，但 exact 耗时 `2843.895ms` 相比历史 best `323` 的 `1938.877ms` 已慢出 30% 以上，因此按新规则保留 `323`。这正是本次修正规则想要的行为：时间明显变差时不再为了平衡 ratio 替换 best；时间接近时才让二级平衡指标介入。

由此也解释了此前“当前版本反而比基准 probe 慢”的原因：一方面旧 reset 路径会在前置启发式加列后清空同 node reuse map，导致 reuse 实际上没有稳定跨轮保留；另一方面上一版 best 更新规则过于容易被近似时间波动带偏，再叠加后续轮次仍试 5 个候选，使复用收益被重复 probe 成本抵消。当前版本修正 reset 保留、best 更新语义和 reuse 后候选数后，011 hard-node 口径下已经回到比 base probe 更快的状态。

## 25. 2026-06-08 probe 初始化拆分与慢因复核

继续检查 probe 路径后，发现 `initialize()` 开头原来会先调用完整 `initializeSearchState(lp)`，而该方法同时初始化 label 队列、dominance table、候选列堆、signature 去重集合，并扫描当前 restricted columns。随后每个 probe candidate 又会重建自己的 label 状态；probe 结束后，正式 pricing 还会再次初始化正式 label/candidate 状态。由于前置 diagnostics、snapshot、dynamic window 和 completion bound 构造都不依赖 candidate/signature 状态，probe candidate 本身也不生成列，本次把初始化拆开：`initialize()` 开头只保留空 `generatedColumns`，probe candidate 只调用 `initializeLabelSearchState()`，正式 join 前才调用 `initializeCandidateState(lp)`。这样去掉了 probe 前一次无效的 restricted column signature 扫描，也去掉了每个 probe candidate 的无效候选堆/hash 初始化。

验证口径为 focused `javac` 和 `tmp-wet030_from040_011_2m,maxNodes=1`，开启 completionBound、probe、reuse 后得到 `solve=18.747s, exact=4.144s, valid=true`。该 smoke 主要验证状态拆分没有破坏 rank0 label 复用和 join；时间本身受 RMIH/CPLEX 波动影响，不作为速度结论。

同时对比 `tmp-probe-bestexact-reuse-011-n4-tie010` 与本次 `tmp-probe-initopt-011-n4-tie010` 的 4 节点日志。当前 run 总时间从 `49.472s` 增到 `70.114s`，但 exact pricing 只从 `20.828s` 增到 `20.982s`，几乎不是主因。真正差异主要来自 RMIH 的 cover MIP：node1 从 `4.372s` 增到 `12.749s`，node2 从 `4.666s` 增到 `10.835s`，node3 从 `2.811s` 增到 `4.947s`，node4 从 `1.838s` 增到 `2.996s`，合计约多 `17.95s`；启发式 pricing 约多 `2.08s`，master LP 约多 `0.38s`。因此这次看到的总时间变慢主要是 RMIH/CPLEX 整数启发式 coverSolve 的波动或路径成本，不是 midpoint probe 初始化拆分导致的 exact pricing 变慢。

## 26. 2026-06-08 RMIH 耗时波动与启发式控频策略

继续复核 011 四节点对比后，当前可以更明确地解释“为什么差这么多”。两次运行的节点数、pricing 次数、exact pricing 次数、列池规模和最终目标都一致：`nodes=4`、`pricingCalls=64`、`exactCalls=14`、`pool=6011`、`incumbent=14024`、`bound=13528.866667`。这说明搜索路径和列生成结果基本没有变；如果某次总时间明显变慢，首先应看各组件内部求解耗时，而不是直接归因到 `Tmid` 或 probe 选点变化。

组件拆分显示，慢因集中在 `RestrictedMasterIntegerHeuristic.solveCoverRepair()` 的第一阶段 covering MIP。该阶段先筛 `screenedCols=2000`，再用 `>=` 覆盖约束求一次二进制整数模型。如果结果有重复覆盖，再生成删点修复列并求一次 `==` partition MIP。两次对比中，`repair` 和 `partitionSolve` 都很小，真正波动的是 `coverSolve`：旧 run 四个节点合计约 `14.69s`，新 run 四个节点合计约 `30.53s`。由于当前 `restrictedMasterIntegerHeuristicTimeLimitSeconds=0.0`，RMIH 会让 CPLEX 尽量证明这个 screened covering MIP 的最优性；它是启发式上界组件，但实际在求一个 2000 列二进制 set-covering 子 MIP 的最优证明，因此同样的模型规模也可能因 CPLEX branch-and-bound、cut、内部启发式和 tie 顺序产生数秒级波动。

文献上这类做法本来就是 branch-and-price 里的 restricted master heuristic：把当前生成列或启发式生成列限制成一个较小静态整数主问题来找原问题可行整数解。它的关键取舍是子问题必须足够小、足够快，同时还要包含高质量可行解。Joncour 等关于 column generation primal heuristics 的讨论也指出，restricted master integer problem 可能不可行，需要应用相关的 repair；GCG 相关 restricted master heuristic 记录里也强调 restriction rate 不能太小，否则可行性差，不能太大，否则计算量上去。这和当前实现的 `coverRepair` 路径一致：我们用筛列控制规模，用删点修复处理 `>=` 覆盖导致的重复 job，再用 `==` 复解确保最终解合法。

因此后续优化 RMIH 的主线不应是继续微调 probe，而是给这个“上界启发式 MIP”加预算和触发规则。最直接的策略是给 `restrictedMasterIntegerHeuristicTimeLimitSeconds` 设置小正数，例如 `1-5s`；它只是上界启发式，找到可行解即可刷新 incumbent，不必每个节点都证明 screened covering MIP 最优。第二个策略是按节点触发：root 必跑，非根只在 LP bound 与 incumbent 仍有足够改进空间、或者若干节点没有改进、或者本节点新增列达到阈值时才跑。第三个策略是按深度或频率触发：root 和浅层节点跑，深层每 `K` 个节点或 incumbent 停滞时跑。第四个策略是按规模降级：非根把 `restrictedMasterIntegerHeuristicReducedCostColumnLimit=2000` 降到更小，或者只保留 LP 正值列、低 reduced cost 列和每 job 前 `K` 条列；当前 coverSolve 是大头，减少 screened MIP 规模通常比减少删点修复更有效。

如果要更保守，可以先只做配置实验，不改代码：分别测试 `enableRestrictedMasterIntegerHeuristic=false`、`timeLimit=1/2/5`、`root-only` 的模拟口径。`false` 可以判断 RMIH 对 pruning 和 incumbent 的真实贡献；小 time limit 可以判断是否能保留大部分上界收益；root-only 可以验证“只在根节点做一次”是否足够。当前 011 对比里 node4 的 RMIH 把 incumbent 改到 `14024`，因此完全 root-only 可能会丢掉这个后续改进；更稳的默认候选不是“只 root”，而是“root 必跑 + 非根限时 + 改进空间/停滞触发”。

这里还要区分两个启发式耗时来源。`HeuristicPricing` 是每轮 LP 后用于生成负 reduced-cost 列的 tabu pricing 启发式，011 四节点约 `12-15s/50 calls`，平均每次 `250-300ms`；它的收益是尽快补充列、减少 exact pricing 压力。RMIH 是节点 LP 收敛后用于刷新整数上界的 restricted integer heuristic，011 这次约 `16-32s/4 calls`，平均每次数秒，且 node2/node3 没有改进 incumbent。因此如果要先降总时间，优先控 RMIH 的时间和频率；HeuristicPricing 的优化方向则是自适应降低 seed/迭代次数、连续几轮新增列很少时跳过、或给每次调用加软时间预算。

## 27. 2026-06-08 RMIH MIP log 诊断和限时实验

为判断 RMIH covering MIP 长时间是在找上界还是证明下界，本次新增默认关闭的诊断开关 `diagnosticRestrictedIntegerMipLog`，测试入口为 `twet.bpc.fullDomainCompare.restrictedMasterIntegerMipLog`。开启后只让 `RestrictedMasterIntegerHeuristic.solveOnce()` 内部的 CPLEX 打印 MIP log，不改变默认求解逻辑。该开关用于短期诊断，不建议在批量实验中打开。

在 `tmp-wet030_from040_011_2m`、`maxNodes=1`、`completionBound=allCycles`、`midpointProbe=true` 口径下打开 MIP log 后，RMIH 的第一阶段 `coverRepair_covering` 显示：root relaxation 为 `13525.8667`；CPLEX 很快在根节点得到 covering incumbent `15003`，随后找到 `14959`；后续主要把 `Best Bound` 从约 `13642` 提升到 `14102` 一带，并继续关闭 gap。也就是说，长耗时主要发生在已有 covering 可行解之后的下界证明/最优性证明，而不是一直找不到上界。更关键的是，最终用于更新 incumbent 的 `14258` 来自后续 repair columns + `==` partition MIP，而不是 covering MIP 自己的 incumbent。因此，强行证明 covering MIP 最优对上界启发式不是必要工作。

随后测试 RMIH 时间限制。root-only 下，`timeLimit=1s` 得到 `obj=14313`，RMIH 用时约 `1.47s`；`timeLimit=2s` 得到 `obj=14258`，用时约 `2.46s`；`timeLimit=5s` 也得到 `14258`，用时约 `5.43s`；不限时诊断 run 得到同样 `14258`，但 RMIH 用时约 `8.10s`。这说明在该节点上 `1s` 略短，`2s` 已经足够保留不限时的上界质量。

进一步跑 `maxNodes=4,timeLimit=2s`，最终仍为 `incumbent=14024`、`bound=13528.866667`、`valid=true`。四次 RMIH 都以 `covering=Feasible` 返回，而不是证明 `Optimal`：node1/node2/node3/node4 的 RMIH 时间分别约 `2.43s/2.36s/2.49s/2.40s`，合计 `9.689s`。对比此前不限时新 run 的 RMIH 约 `32.30s`，限时显著降低了这部分开销；该次总时间为 `57.496s`，比慢 run 的 `70.114s` 好，但 exact pricing 本身波动到 `27.805s`，所以总时间不会完全按 RMIH 节省线性下降。

当前策略判断是：先优先测试 RMIH 小时间限制，而不是马上改成每 5 个节点跑一次。原因是限时不改变触发频率，仍保留 node4 这类非根节点改进 incumbent 的机会；而 root-only 或每 5 点一次可能直接错过关键上界刷新。建议下一轮批量比较 `restrictedMasterIntegerTimeLimit=2/3/5`，如果某些算例 `2s` 上界质量下降，再考虑非根节点用 `3-5s` 或结合“若干节点未改进才触发”的频率控制。

## 28. 2026-06-08 RMIH `2/3/5s` 小矩阵

按上一节建议，本轮用 `010/011/013` 三个 30 任务算例、`maxNodes=4`、`completionBound=allCycles`、`midpointProbe=true`、同一套 probe/reuse 参数，测试 `restrictedMasterIntegerTimeLimit=2/3/5`。这个口径不是求全局收敛，只看前 4 个节点中 RMIH 限时对上界和耗时的影响。

结果表明，`010` 三组最终都为 `incumbent=16258`、`bound=16205.5`。RMIH root 解在 `2s` 时为 `16642`，`3s/5s` 时为 `16444`，说明限时会影响 root RMIH 自身质量；但 4 节点最终 incumbent 未受影响。三组 RMIH 合计时间分别为 `3.731s/5.028s/4.558s`，其中 node2/node4 的 covering MIP 本身不可行或快速失败。`010` 上 `3s/5s` 比 `2s` 的 root RMIH 更好，但最终结果一致。

`011` 三组最终都为 `incumbent=14024`、`bound=13528.866667`。`2s`、`3s`、`5s` 的 RMIH 合计时间分别为 `9.154s/12.027s/18.841s`；node4 都能刷新到 `14024`。因此 `011` 上 `2s` 最划算，`3s/5s` 没带来更好上界，只增加 RMIH 耗时。该结果也进一步支持“先限时，而不是降低频率”：如果改成只 root 或每 5 个节点跑一次，可能错过 node4 的 `14024`。

`013` 三组最终都为 `incumbent=14433`、`bound=14322.5`。RMIH 合计时间为 `3.954s/5.258s/4.285s`，相比总时间 `112-113s` 很小；总时间大头是 exact pricing，三组 exact 分别约 `85.75s/88.73s/85.77s`。因此 `013` 的主线仍是 pricing hard node，不是 RMIH 控频；RMIH 限时不会显著改变总耗时。

综合这三个算例，当前更合理的默认实验候选是 `restrictedMasterIntegerTimeLimit=2s`。它在 `011` 保留关键非根改进，在 `010/013` 不破坏 4 节点最终 incumbent，同时显著避免 covering MIP 证明最优的长尾。`3s/5s` 可以作为保守备选，但从这轮看收益不稳定；频率控制应放在第二阶段，比如“root 必跑，非根限时 2s；若连续若干 RMIH 无改进或当前 gap 很小，再降低触发频率”。直接每 5 个节点跑一次目前证据不足，因为它会牺牲非根上界刷新机会。

## 29. 2026-06-08 RMIH 默认限时与失败语义

用户倾向先把 RMIH 默认限时设为 `4s`，因为相对 `2s` 多出的时间不大，并且 `010` root 上 `3s/5s` 的 RMIH 自身上界比 `2s` 更好。当前代码已将 `restrictedMasterIntegerHeuristicTimeLimitSeconds` 默认值改为 `4.0`，测试入口仍可用 `twet.bpc.fullDomainCompare.restrictedMasterIntegerTimeLimit` 覆盖。

RMIH 在规定时间内如果找不到任何可行整数解，或者 CPLEX 证明 infeasible，`solveOnce()` 会返回 `Attempt.notSolved`，外层 `Result.notSolved` 的 `feasible=false`、`objective=+inf`、`solution=null`。`Tree` 只在 `integerResult.isFeasible()` 且目标优于当前 `incumbentCost` 时更新 incumbent，所以这种失败只会记录一条 RMIH 事件，不会改变上界，也不会关闭节点。若 CPLEX 在限时内已有 incumbent，即使未证明最优，`cplex.solve()` 通常会返回可用的 `Feasible` 状态，当前逻辑会读取该解并继续做覆盖有效性检查。

把当前全局上界加入 RMIH 是合理的后续优化，但不适合和本次默认限时一起混改。语义上，RMIH 的目标只是刷新上界，因此可以在内部 MIP 加 `obj <= incumbentCost - eps`，或用 CPLEX cutoff，让它只找严格改进解；没有改进解时返回 notSolved 即可。潜在收益是减少“已找到不优解后继续证明”的无效搜索。实现上需要把 `Tree` 当前 `incumbentCost` 传入 `RestrictedMasterIntegerHeuristic.solve()`，并注意 outsourcing tariff 的目标表达式必须和 cutoff 用同一套 obj 口径。该优化后续可单独测试，不影响当前 `4s` 限时结论。

关于 probe 初始化优化和 RMIH 慢因，需要分清因果。旧版 `initialize()` 和每个 probe candidate 会初始化 label 状态、candidate heap、signature map、active column signatures，并扫描 restricted columns；新版 probe 只初始化 label/dominance/queue 状态，正式 join 前才初始化 candidate/signature 状态。这个改动只减少重复初始化，按设计不应改变正式 pricing 生成的列集合。之前两次 011 四节点运行的差异，日志显示主要来自 RMIH `coverRepair_covering` 的 CPLEX coverSolve 波动，而不是 exact pricing 或列路径改变：两次 exact pricing 约为 `20.828s` 和 `20.982s`，而 RMIH covering solve 从约 `14.69s` 增到 `30.53s`。结合 MIP 日志，covering MIP 往往很快找到可行上界，长尾主要在 bound 证明，因此这类差异更像 CPLEX MIP 证明阶段的运行波动，而不是 probe 初始化优化导致列不同。

013 的历史日志也支持“RMIH 不是主瓶颈”。浅层 `maxNodes=2` 中，`completionBound` 一度是最快口径；但深节点 node3 曾出现 `Tmid=563.5` 下 forward label 爆炸，180 秒内仍停在 forward 阶段，因此 013 的核心问题是 exact pricing hard node。最近 RMIH `2/3/5s` 四节点矩阵中，013 三组最终均为 `incumbent=14433`、`bound=14322.5`，总时间约 `112-113s`，exact pricing 为 `85.75-88.73s`，RMIH 只有 `3.95-5.26s`。因此 013 上继续调 RMIH 限时对总时间影响有限，后续主要还是看 Tmid/probe 或 pricing 结构。

## 30. 2026-06-08 incumbent cutoff 试验与 013 对照修正

按进一步讨论，实现了 RMIH incumbent cutoff 的实验开关 `restrictedMasterIntegerHeuristicIncumbentCutoff`，测试入口为 `twet.bpc.fullDomainCompare.restrictedMasterIntegerIncumbentCutoff`。约束口径按用户要求不加 eps，即 `obj <= incumbentCost`。实现时需要注意一个重要语义：`coverRepair_covering` 是中间 set-covering MIP，用于找含重复 job 的候选列组合并生成修复列，不是最终 `==` 解。原始 covering 解的目标可能高于 incumbent，但删重复 job 后的修复列成本可能下降，最终 partition 解可能低于 incumbent。因此 cutoff 不能加在 covering 阶段，否则会漏掉潜在修复机会；当前只在 `exactCover=true` 的 RMIH 模型上加，也就是全量 `partition` 模式和 `coverRepair_partition` 阶段。

用 `011,maxNodes=4,timeLimit=4s,completionBound=allCycles,midpointProbe=true,reuseWithinNode=true` 做了 on/off 对照。cutoff 关闭时结果为 `incumbent=14024,bound=13528.866667,solve=48.873s,exact=19.812s,RMIH=12.390s,cols=6011`；安全 cutoff 开启时为 `incumbent=14024,bound=13528.866667,solve=58.978s,exact=24.844s,RMIH=16.415s,cols=6011`。最终上界一致，但 cutoff 没有变快。日志中 safe cutoff 下 node2 的 covering 仍正常可行，partition 因 `obj <= incumbentCost` 返回 infeasible，这是符合语义的；但它没有节省覆盖阶段时间，反而增加了 partition 和整体波动。因此当前不建议默认开启 incumbent cutoff，只保留为实验开关，便于后续如果怀疑非改进 RMIH 消耗过大时单独对照。

013 的历史对照也需要修正说法：不是“之前最多只求到 node3，这次才到 node4”。之前已有多组 `maxNodes=4` 的 013 probe 实验，且比本轮 RMIH 矩阵更快。代表性结果为：`tmp-probe-balanced-013-completionBound-queue-pop5000-n4` 是 `nodes=4,incumbent=14433,bound=14322.5,solve=61.084s,exact=49.105s,heuristic=8.144s,cols=6809`；`tmp-probe-layered-rank2-013-completionBound-n4` 是 `solve=66.160s,exact=53.660s,cols=7586`。本轮 RMIH `2/3/5s` 矩阵虽然也到 `nodes=4`，但总时间约 `112-113s`、exact pricing 约 `85-89s`、列数 `7391`，明显慢于历史 `balanced pop5000`。两者上界和 bound 一致，差异主要在 Tmid/probe 版本与列生成路径，不是 node 数更深导致的。

## 31. 2026-06-08 当前代码复现 013 历史快配置

继续按历史较快配置复现 `013`：`tmp-wet030_from040_013_2m`、`halfDomain`、`completionBound=allCycles`、`midpointStrategy=completionBound`、`midpointProbe=true`、`popLimit=5000`、`maxCandidates=6`、`moveRatio=0.15`、`earlyStopRatio=1.5`、`extraAfterThreshold=1`、`bracket=true`、`reuseWithinNode=false`、`RMIH timeLimit=4s`、`incumbentCutoff=false`。当前代码结果为 `nodes=4,incumbent=14433,bound=14322.5,solve=95.635s,exact=71.800s,heuristic=18.125s,cols=6433`，仍明显慢于历史 `tmp-probe-balanced-013-completionBound-queue-pop5000-n4` 的 `solve=61.084s,exact=49.105s,heuristic=8.144s,cols=6809`。

这个复现说明，013 最近变慢不是“只因为 RMIH time limit 矩阵口径不同”。RMIH 本次只有 `2.919s`，历史较快版约 `2.533s`，差距很小；真正差距主要在 exact pricing 和 HeuristicPricing。当前复现的 HeuristicPricing 单次耗时从历史的约 `140-200ms` 增到较多 `300-700ms`，这与后续加入 seed 兼容性和 pricing 禁弧口径检查后的候选扫描成本有关。它会增加总时间，但不是 95s 的唯一大头。

exact pricing 的主要慢点仍在 node3。当前复现 node3 的三次 exact pricing 约为 `12.407s/10.102s/10.166s`，都选到 `Tmid=346`；历史较快版在同一类 node3 上也选到 `Tmid≈347.405`，但对应 exact pricing 约 `8.3-8.4s`。因此当前慢因不能简单归结为 Tmid 选错，而是同等量级 Tmid 下的列池、dual、dominance graph 和 join 压力更重。当前复现中 node3 的第一轮 `fw/bw kept=6763/19785`、join pairs tried 约 `1.49M`，后两轮 `bwKept` 进一步到 `26K+`，superset/subset visited 达百万级；历史较快版 node3 的对应轮次虽然列池更大，但具体列生成路径和 join/dominance 负担不同，整体 exact 时间更低。

当前判断为：013 的主瓶颈仍是 hard node exact pricing，而不是 RMIH。RMIH 小限时和 cutoff 只影响局部上界启发式耗时，不能解释 013 的主要慢差。后续如果继续优化 013，应优先看两条线：一是 HeuristicPricing seed 兼容性扫描是否可以缓存或减少调用频率，避免每轮几百毫秒的固定成本；二是 node3 exact pricing 的 probe 选点虽已避免极端偏右，但仍可能生成很重的 backward/join 压力，需要继续分析候选列路径、dual 变化和 dominance graph 查询规模，而不是继续调 RMIH。


## 32. 2026-06-08 删除 RMIH cutoff 与撤回 Tmid 取整

本次把 RMIH incumbent cutoff 的实验开关从代码中删除，不再保留配置项和测试入口。这个想法曾经是为了让 RMIH 只搜索不劣于当前全局上界的整数解，但实际语义有明显限制：`coverRepair_covering` 是 `>=` 的中间 covering MIP，用于先找到含重复 job 的列组合，再通过删重复 job 生成修复列；该 covering 解的目标可能高于 incumbent，但修复后的 `==` partition 解仍可能变好。因此 cutoff 不能加在 `>=` 阶段，否则会直接漏掉潜在修复列。即使只加在最终 `==` 阶段，011 对照也没有加速，反而增加了整体波动。当前结论是：cutoff 只作为历史尝试记录，不进入主线实现。

同一轮也撤回 `Tmid` 强制取整。此前取整只是为了减少 `0.9` 连乘导致的小数日志噪声，但半域切分、本地时间函数、completion bound 和列 timing 都是 double 口径；把 midpoint 四舍五入为整数会人为移动半域边界，收益不明确。当前代码只保留防贴边 clamp，`Tmid` 在 clamp 后继续保持小数值。


## 33. 2026-06-08 013 慢于历史与 011 initopt 差异复查

本次复查 `tmp-probe-balanced-013-completionBound-queue-pop5000-n4` 和 `tmp-current-repro-013-pop5000-cand6-n4-tl4`。两者最终 `incumbent=14433,bound=14322.5,nodes=4,valid=true` 一致，但历史快 run 为 `solve=61.084s, exact=49.105s, heuristic=8.144s`，当前复现为 `solve=95.635s, exact=71.800s, heuristic=18.125s`。因此差距不是 RMIH，RMIH 分别约 `2.53s` 和 `2.92s`，只差约 `0.4s`；主要差在 HeuristicPricing 多约 `10s`，exact pricing 多约 `22.7s`。

013 的 exact pricing 慢差主要集中在 node3。历史 node3 四轮 exact 约为 `8.571s/10.864s/8.586s/8.387s`，当前复现约为 `19.381s/12.407s/10.102s/10.166s`。其中当前第一轮最异常，虽然 join pair 数量比历史第一轮少，但 completion-bound build 从约 `0.70s` 增到 `1.73s`，paperGraph superset visited 从约 `2.72M` 增到 `3.73M`，并且 HeuristicPricing 平均耗时从 `154ms` 增到 `370ms`。这说明当前慢因不是单一 Tmid 选错，而是同类 Tmid 下列池、dual、completion-bound 构造和 dominance 查询负担更重，再叠加 heuristic seed/兼容性扫描成本上升。

同时复查 `011-bestexact` 与 `011-initopt` 的差异，确认用户的判断是对的：如果初始化优化只是把 candidate/signature 状态推迟到正式 join 前，那么理论上不应改变最终列生成路径。日志也支持这一点：两组都是 `nodes=4, pricing=64, cols=6011, incumbent=14024,bound=13528.866667`，exact pricing 分别为 `20.828s` 和 `20.982s`，几乎相同。真正拉开端到端时间的是 RMIH covering MIP：bestexact 的四个节点 RMIH 约 `4.600s/4.886s/3.020s/1.841s`，initopt 为 `13.090s/11.055s/5.153s/2.998s`，差距主要来自 `coverSolve`。因此不能把这组 011 慢差归因到 probe 初始化优化改变了列；它更像是同一类 screened covering MIP 在 CPLEX 证明阶段的耗时波动，或极小模型/列顺序差异放大到 MIP bound 证明时间，而不是 exact pricing 逻辑变化。

继续追 013 的偏差位置后，结论更明确：两组 branch path 完全一致，依次都是 `(3,5)`、`(1,7)`、`(5,15)`、`(1,21)`，因此不是树搜索走歪。总差距主要由 `HeuristicPricing +9.98s` 和 exact pricing `+22.70s` 构成；RMIH 只从约 `2.53s` 到 `2.92s`，不是主因。exact pricing 的节点分解约为：node1 从 `5.31s` 到 `8.12s`，node2 从 `3.59s` 到 `8.14s`，node3 从 `36.41s` 到 `52.06s`，node4 从 `3.79s` 到 `3.48s`。最大偏差在 node3。

node3 的关键分叉发生在第一轮 exact pricing。历史快 run 在 node3 第一轮选 `Tmid=325.36`，生成 `743` 条负列；当前复现选 `Tmid=338.0`，只生成 `75` 条负列。当前这一轮 forward kept 反而更多，深度分布也更深，说明不是扩展不够，而是 LP dual/列池状态已经不同，导致真正负 reduced-cost 候选明显少。随后当前第二轮又补出 `368` 条列，但已经进入另一条 dual/列池演化路径，node3 后续三轮 exact 合计仍明显慢于历史。这个偏差很可能由 root/node2 早期列生成差异逐步放大：历史到 node3 前 pool 约 `5974`，当前约 `5938`，数量只差几十，但列内容和 dual 足以改变 node3 第一轮负列结构。

因此 013 不能简单说成“纯波动”。它包含两类因素：一类是可解释的固定开销上升，例如 HeuristicPricing 平均耗时从约 `154ms` 到 `370ms`，当前 seed/兼容性扫描明显更贵；另一类是列生成路径敏感性，root/node2 轻微列差异和不同 `Tmid/probe` 候选会改变 LP dual，最终在 node3 第一轮表现为 `743` vs `75` 的负列数量差。后者不是单个组件卡死，而是 BPC 列池-对偶-定价闭环的路径依赖。

## 34. 2026-06-08 当前代码重跑 013

按当前代码重新跑 `tmp-wet030_from040_013_2m`，使用 `halfDomain`、`completionBound=allCycles`、`midpointStrategy=completionBound`、`midpointProbe=true`、`popLimit=5000`、`maxCandidates=6`、`moveRatio=0.15`、`earlyStopRatio=1.5`、`extraAfterThreshold=1`、`bracket=true`、`reuseWithinNode=false`、`RMIH timeLimit=4s`、`maxNodes=4`。

第一轮按近期讨论里的 `tieScore=remaining,tieTolerance=0.05` 跑，结果为 `nodes=4, incumbent=14433, bound=14322.5, solve=114.664s, exact=86.446s, heuristic=21.676s, cols=6434, valid=true`。日志路径为 `test-results/bpc/tmp-current-rerun-013-20260608/tmp-wet030_from040_013_2m-halfDomain.log`。这个结果比上一轮当前复现更慢，关键原因是 node3 第三次 exact pricing 在 probe 中把 `Tmid=376.594` 选为候选；该点和 `346.059` 的 probe 主指标非常接近，二级 remaining 指标略优，但正式 exact 下 label 和 join 压力更大，耗时约 `14.706s`，而同类 `346` 口径约 `10s`。

为避免把配置差异误认为代码变化，又补跑严格 `tieScore=off` 口径。结果为 `nodes=4, incumbent=14433, bound=14322.5, solve=77.792s, exact=60.204s, heuristic=13.327s, cols=6434, valid=true`，日志路径为 `test-results/bpc/tmp-current-rerun-013-20260608-tieoff/tmp-wet030_from040_013_2m-halfDomain.log`。这一轮比上一轮 current repro 的 `95.635s/71.800s` 快，但仍慢于历史 balanced 的 `61.084s/49.105s`。

按 node 汇总，`tieScore=off` 下当前 exact pricing 为：node1 `5.509s`、node2 `6.367s`、node3 `45.025s`、node4 `3.303s`。历史 balanced 分别约为 `5.311s/3.594s/36.408s/3.792s`。因此当前主要差距仍在 node2 和 node3，尤其 node3。当前 node3 四轮 exact 为 `16.285s/9.299s/9.619s/9.822s`，历史为 `8.571s/10.864s/8.586s/8.387s`。当前 node3 第一轮仍只生成 `75` 条负列，而历史第一轮生成 `743` 条负列，说明早期列池/dual 路径仍和历史不同；这不是 RMIH 问题，RMIH 本轮只约 `2.42s`。

当前结论是：如果使用二级 remaining 作为 tie-break，013 这类 hard node 会有明显选点误差风险；`tieScore=off` 当前更稳，至少能把当前代码跑回 `77.8s` 量级。013 仍慢于历史快 run 的主要原因仍是 node3 exact pricing 路径和 HeuristicPricing 固定开销，而不是 RMIH 或树搜索分支路径。

## 35. 2026-06-08 关闭 remaining 二级 tie-break 的默认使用

当前代码里 `bidirectionalMidpointProbeTieScore` 默认已经是 `off`，本次只在配置注释里补充说明：`remaining` 只能作为实验口径，不能因为个别算例直接作为全局默认。原因是 013 的两轮当前代码实验已经给出反例：在 node3 第三轮，`Tmid=346.059` 和 `376.594` 的主 `queue` 指标非常接近，`remaining` 略偏向 `376.594`，但正式 exact pricing 中 `376.594` 的 label 和 join 压力更大，导致该轮从约 `9-10s` 量级变成约 `14.7s`。这说明 remaining 可以作为诊断指标，但目前不适合默认参与最终候选选择。

与历史 `tmp-probe-balanced-013-completionBound-queue-pop5000-n4` 相比，当前 `tieScore=off` 仍慢，主要不是 RMIH 或分支路径。两者 branch path 一致，都是 `(3,5) -> (1,7) -> (5,15) -> (1,21)`；RMIH 当前约 `2.42s`，历史约 `2.53s`，反而当前略少。慢差主要来自两块：一是 HeuristicPricing，历史约 `8.14s`，当前约 `13.33s`，同样 node1 生成 `5060` 条启发式列，但当前单次 seed/兼容性扫描更贵；二是 exact pricing，历史约 `49.11s`，当前约 `60.20s`，其中 node3 从 `36.41s` 增到 `45.03s`。

配置层面也有真实差异。历史 balanced 日志中的 probe `moveRatio=0.1`，当前复现使用的是 `0.15`；历史 node3 第一轮从 `ref=551` 依次试 `551,495.9,446.31,401.679,361.511,325.360`，最终选 `325.360`，一次生成 `743` 条负列。当前 node3 第一轮从 `ref=551` 试 `551,468.35,398.10,338.38,287.63,313.00`，最终选 `338.38`，只生成 `75` 条负列。也就是说，当前并不是只慢在“同一个 Tmid 下计算更慢”，而是 probe 步长和早期列池/dual 细微差异把 node3 第一轮推到了另一条列生成路径上。后续当前第二轮补出 `368` 条负列，但已经进入不同 dual/列池演化路径，因此 node3 总耗时仍高于历史。

当前更保守的判断是：`tieScore=off` 应保持默认；`moveRatio=0.1` 比 `0.15` 在 013 历史快 run 上有更好证据，但这仍只是少量算例证据，后续若要改默认，应单独用多算例矩阵比较 `0.1/0.15`。HeuristicPricing 固定开销上升也需要单独处理，例如缓存 seed 兼容性或减少重复全池扫描，但这和 remaining tie-break 是两个问题。

## 36. 2026-06-08 Tmid 与负列数量差异的解释

013 历史 run 中 node3 第一轮 `Tmid=325.36` 生成 `743` 条负列，而当前 run 中 node3 第一轮 `Tmid=338.38` 只生成 `75` 条负列。这里不能理解为“同一套 pricing 问题只因为 Tmid 改了一点就必然少 10 倍”。更准确地说，历史 run 和当前 run 到达 node3 时，前面 root/node2 已经生成了不同列集，因此 RMP 解和 dual 也已经不同；node3 第一轮 pricing 面对的 reduced-cost 函数不是完全同一个问题。

在同一轮 probe 内，不同候选 Tmid 的 dry-run 使用的是同一套 LP dual，只是在不同半域切分下试探左右 label 压力。正式加入列时，只会用最终选中的 Tmid 做一次完整 exact pricing。不同 Tmid 会改变 forward/backward label 分布、join 组合顺序、dominance graph 压力和候选列发现路径，因此即使 dual 相同，输出的负列数量和顺序也可能不同；但历史和当前这种 `743` vs `75` 的大差异，主要还叠加了前序列池/dual 路径不同。

当前对 probe 的定位应更保守：它不是能预测最快 Tmid 的 oracle，也不能保证选中点生成最多负列。它的主要价值是用有限 pop 排除明显偏左或偏右、趋近单向 labeling 的极端 Tmid，使定价不至于落到明显爆炸的点。由于 probe 只能看浅层压力，且 score 与完整 exact pricing 的真实耗时/负列产出并不严格一致，所以它可能选到“可求解但不是最快”的点。013 说明了这一点：probe 能把 completionBound reference 附近的极端偏右点拉回来，但不保证拉到历史最快路径。

## 37. 2026-06-08 013 历史与当前差距的具体位置

把历史 balanced run 和当前 `tieScore=off` run 按 node 拆分后，差距主要集中在三处。第一，HeuristicPricing 固定开销上升：历史四个 node 合计约 `8.14s`，当前约 `13.33s`，多 `5.18s`。其中 node1 同样生成 `5060` 条启发式列，但历史约 `3.66s`，当前约 `5.01s`；node2 历史约 `2.08s`，当前约 `3.67s`。这说明当前启发式 seed/兼容性扫描成本更高。

第二，node2 exact pricing 明显更慢：历史约 `3.59s`，当前约 `6.37s`，多 `2.77s`。node2 生成列数量从历史 `157` 变成当前 `141`，数量差不大，但当前 exact 的 completion bound 和 label/dominance 过程更重。

第三，也是最大项，node3 exact pricing：历史约 `36.41s`，当前约 `45.03s`，多 `8.62s`。node3 第一轮差异最关键。历史第一轮 `Tmid=325.36`，生成 `743` 条负列，耗时 `8.57s`；当前第一轮 `Tmid=338.38`，只生成 `75` 条负列，但耗时反而 `16.29s`。这轮当前的 join pair 更少，`2.25M` 对比历史 `2.85M`，函数评估也更少，`0.92M` 对比历史 `1.34M`；但当前 completion-bound build 从 `0.70s` 到 `1.07s`，superset visited 从 `2.72M` 到 `3.73M`，forward kept/dominated 从 `7353/24774` 到 `9414/31975`。因此当前第一轮慢不是因为 join 更多，而是 forward 扩展和 dominance graph 查询更重，且最终找到的负列少得多。

node3 后三轮当前并不都更差。历史第二轮很重，`10.86s`，当前第二轮为 `9.30s`；历史第三/四轮 `8.59s/8.39s`，当前第三/四轮 `9.62s/9.82s`。所以 node3 总差距主要由第一轮 `+7.71s` 和后两轮小幅变慢构成。RMIH 不是差距来源：历史四个 node 合计约 `2.53s`，当前约 `2.42s`，当前还略少。

## 38. 2026-06-08 013 ALNS 开启且关闭 RMIH 上界启发式的中止实验

按用户要求，用当前求解配置直接跑 `tmp-wet030_from040_013_2m` 完整树，打开 ALNS seed，关闭 RMIH 上界启发式，即 `runALNSForSeed=true`、`enableRestrictedMasterIntegerHeuristic=false`。其余关键配置保持当前主线：`halfDomain`、`completionBound=allCycles`、`midpointStrategy=completionBound`、`midpointProbe=true`、`popLimit=5000`、`maxCandidates=6`、`moveRatio=0.15`、`tieScore=off`、`bracket=true`、`reuseWithinNode=false`，并打开 `nodeProgressSummary/stageHeartbeat`。实验名为 `tmp-current-013-full-alns-no-rmih-20260608`。

本次运行按用户要求在 node18 处理中手动停止，因此没有形成完整 CSV，也不能作为最优性结果。测试目录 `test-results/bpc/tmp-current-013-full-alns-no-rmih-20260608/` 已创建，但因进程被中止，程序未正常落盘结果文件；本节记录来自控制台 heartbeat 和 node summary。运行过程中未出现 RMIH 事件，node summary 里也没有 RMIH 耗时字段，说明 RMIH 上界启发式确实被关闭。ALNS seed 生效，根节点初始列池明显大于无 ALNS 的极小 seed。

中止前搜索已经推进到 node18。前几个关键节点如下：node1 用时 `9.779s`，`inc=14908`，`bound=14287.625`；node2 用时 `5.203s`；node3 是第一处明显慢点，用时 `52.381s`，其中 pricing `51.949s`、exact `49.137s`，新增 `1003` 条列。后续 node12 时 incumbent 已到 `14451`，bound 到 `14393.8`；node13 后 bound 到 `14411.969697`；node14 和 node15 又各自出现约 `45s` 的 pricing，其中 node15 找到整数 incumbent `14450`，此时 gap 约 `0.2175%`。node16、node17 被 incumbent 剪掉，node17 后 bound 到 `14427.0`，gap 约 `0.1592%`。

真正导致本次不能短时间闭合的是 node18。node18 第一轮 exact pricing 的规模异常大：`Tmid=388.705`，forward `fwPops=117693, fwKept=127120, fwDom=2139144`，backward `bwPops=923759, bwKept=943942, bwDom=2319467`，`fBuilt=2785507`，completion bound 剪枝为 `cbFPruned=519244, cbBPruned=18446937`，最终 `joinPairs=44223987`，只生成 `33` 条负列，`bestRC=-19.25`。这说明该节点并不是死循环，而是一个极重的 exact pricing 证明过程：completion bound 做了大量剪枝，但剩余状态空间仍然很大，而且生成负列很少，性价比很低。

node18 第一轮加列后，第二轮 exact pricing 仍然没有明显变轻。中止前第二轮仍在 forward 阶段，`Tmid=392.67875`，已达到约 `fwPops=77306, fwKept=133160, fwDom=1451960, fBuilt=1929579`，forward 队列仍有五万级。这说明第一轮生成的 33 条列没有显著缓解该节点的 dual/pricing 难度，后续即使继续跑，也很可能还要在 node18 消耗较长时间。

当前结论是：关闭 RMIH 后，ALNS 仍能提供可用上界，搜索也能把 incumbent 从 `14908` 推到 `14450`，并把 gap 压到约 `0.16%`，但没有在短时间内闭合到最优。主要瓶颈不在 RMIH，而在深层 node18 的 exact pricing。RMIH 关闭后可能减少上界启发式开销，但也少了后续 restricted integer heuristic 刷新 incumbent 的机会；本次已经有 ALNS 和整数 LP 解把上界推到 `14450`，但 node18 的定价证明成本仍然足以拖住完整收敛。后续若继续这个方向，应优先分析 node18 的 branch 状态、dual、Tmid probe 候选和 label 分布，而不是继续调 RMIH。

## 39. 2026-06-09 node18 pricing 爆炸原因分析

继续分析 `013` 在 ALNS 开启、RMIH 上界启发式关闭时卡住的 node18。该节点中止前 incumbent 已到 `14450`，全局 bound 已到 `14427.0`，gap 约 `0.1592%`。这说明搜索已经进入尾段证明区间：上界和下界很接近，但还必须通过 exact pricing 证明当前节点没有足够好的负 reduced-cost 列，或者继续补出少量列。此时定价不再是“快速找到明显负列”，而是“大量近零候选都不能轻易剪掉，最后只证明少数列有用”。

node18 的第一轮 exact pricing 已经能说明问题本质：`Tmid=388.705`，forward 有 `fwPops=117693, fwKept=127120, fwDom=2139144`，backward 有 `bwPops=923759, bwKept=943942, bwDom=2319467`，`joinPairs=44223987`，但最终只生成 `33` 条负列，`bestRC=-19.25`。completion bound 并不是没起作用，它已经剪掉了 `cbFPruned=519244, cbBPruned=18446937`，尤其 backward 剪枝非常多；问题是剪完以后仍有近百万 backward survivor 和四千多万 join 组合。也就是说，这不是单纯的死循环或 Tmid 选偏，而是当前节点的定价状态空间本身太大，且松弛下界不足以把这些近零 reduced-cost 的部分路径提前判死。

当前判断是：小 gap 会显著放大这种现象，但小 gap 不是唯一原因。小 gap 意味着 reduced-cost cutoff 很紧，很多标签的 relaxed completion bound 仍然可能低于 0，不能被安全剪掉；同时为了证明节点 bound，exact pricing 不能像启发式 pricing 那样“找不到就算了”。但如果该节点有大量硬禁弧、强时间窗或明显劣化的 dual 结构，也可能很快剪完。node18 的特殊点在于 hard arc pruning 并不强，主要仍是在密图上处理 elementarity、adjacency/branch dual 和 TWET 函数成本；这些因素让 dominance 很弱，许多访问集合不同、成本相近的标签互相不能支配。

completion bound 的局限也比较明确：它是 relaxed prefix/suffix 下界，用来判断“这个 partial label 是否无论怎么补都不可能负 reduced cost”。它考虑了当前禁止弧口径，但不能完全表达当前 label 的 visited set、elementarity 冲突、精确 join 后的序列结构和所有主问题退化影响。因此当 relaxed completion 仍可能为负时，标签必须保留，即使最终 elementary join 后大概率不会形成好列。node18 的 `cbBPruned=18446937` 说明 bound 很有用；`bwKept=943942` 又说明 bound 在该节点上还不够强。

这类现象和文献里的 ESPPRC pricing label explosion、column generation tailing-off/degeneracy 是一致的。BPC 尾段经常出现很多接近零 reduced cost 的列，主问题目标改善很小，但定价子问题仍要花大量时间证明不存在更好的列。当前 node18 第一轮“44M join 只换 33 条负列”就是典型症状。后续如果继续优化，应优先考虑三条线：一是保存 node18 pricing 快照，记录 branch 状态、dual、Tmid candidates、arc 数量和 label 深度分布；二是用 ng-set 或 restricted non-elementary route pricing 减弱 elementarity 状态爆炸；三是对 hard node 单独考虑更强的结构剪枝、dual stabilization、或替代 pricing proof。继续微调 Tmid/probe 只能避免极端单侧爆炸，不能根治这种两侧 survivor 都很多的尾段 proof 节点。
## 40. 2026-06-09 013 开 pricingOnly subtree 的 node18 复测

按用户要求，用当前主线配置复测 013：`tmp-wet030_from040_013_2m`，half-domain，`completionBound=allCycles`，ALNS seed 打开，RMIH 上界启发式关闭，`midpointStrategy=completionBound`，midpoint probe 打开，同时额外打开 `completionBoundSubtreeArcEliminationPricingOnly=true`，formal subtree 仍关闭。运行名为 `tmp-current-013-full-alns-no-rmih-pricingonly-20260609b`，因为 2 分钟多仍停在 node3 exact pricing，手动停止，未写出 CSV。

这次没有到达之前不加 pricingOnly 时的 node18。root 用时约 `20.856s`，subtree pricingOnly 固定 `206` 条弧，node1 summary 为 `pool=6038`、`inc=14908`、`bound=14287.625`、`exact=5.139s/3`、`heur=9.209s/26`。node2 进入时已有 `pricingOnlyArc=206`，继续固定 `66` 条弧，node2 summary 为 `pool=7212`、`restricted=5821`、`pricing=7.017s`、`exact=4.113s/4`，仍能正常返回。

真正的问题出现在 node3。node3 是 depth=1 的 required adjacency 右支，进入时 `pricingOnlyArc=206`、`adjReq=1`、`repair=8:3->5`。第一轮 exact pricing 的 probe/最终 `Tmid` 选到约 `408.723` 后，forward 侧迅速膨胀：中止前最后心跳约为 `fwQueue=71983`、`fwPops=64662`、`fwKept=138150`、`fwDom=1160597`、`bwKept=0`、`cbFPruned=0`、`fBuilt=1298746`。这说明该节点尚未进入有效 backward/join 阶段，completion bound 在这个半域和 dual 下也没有剪掉 forward child。

因此本次负结果说明：对 013 当前主线，pricingOnly subtree 没有让之前 node18 更好求解，反而因为 root 后固定了 206 条 pricing-only 弧并改变后续列池/dual/分支路径，在 node3 就产生了更早的 forward 单侧爆炸。它并不否定 pricingOnly 在 012 等算例上的作用，但说明“实际可用边更少”不是充分条件；如果 fixed arc 数量不够强，或 required adjacency dual 与 Tmid/probe 组合把状态集中到 forward direct-sink 区域，pricingOnly 仍可能更早卡住。后续若要继续比较，应该在同一个 node 快照上 replay pricing，而不是用完整树路径直接比较 node18，因为 pricingOnly 已经改变了到达的节点序列。

## 41. 2026-06-09 node3 中 cbFPruned 为 0 的解释

针对“pricingOnly 已经禁止部分弧，为什么 node3 的 completion-bound pruning 会是 0”的追问，重新核对代码路径后结论为：主 completion bound 构造确实考虑了 forbidden arc 和 pricingOnly arc。`CompletionBoundCalculator` 在构造 successor/predecessor 时调用 `isCompletionArcForbidden`，该函数同时检查 `node.isArcForbidden(from,to)` 和 `node.isPricingOnlyArcForbidden(from,to)`。因此当前现象不能解释为“completion bound 没看 pricingOnly 禁弧”。

`cbFPruned=0` 的语义是：在 forward 扩展阶段，已经构造出来的 child label 没有一个被 completion-bound 下界判定为必死。node3 的日志里 `fBuilt` 和 `fBoundSurvivors` 一直相等，最后中止前为 `1298746/1298746`，说明所有 forward child 都通过了 relaxed completion bound。它不统计硬禁弧直接跳过的候选，也不统计 dominance 删除；同一日志里 `fwDom=1160597` 已经很大，说明 dominance 仍在工作，只是 completion-bound 这一类剪枝没有命中。

更合理的解释是 bound 在这个节点的 dual/分支状态下过松。node2 仍带有 pricingOnly arc 时，completion bound 能正常剪枝，日志中有 `cbFPruned=9057`、`cbBPruned=23551` 这类计数；node3 则是 depth=1 的 required adjacency 右支，状态为 `adjReq=1`、`repair=8:3->5`。required adjacency 的分支 dual 会让很多 partial label 在松弛补全里“仍有机会通过后续补上相邻关系变成负 reduced cost”。completion bound 本身是 relaxed prefix/suffix 下界，不带当前 label 的 visited set，也不把 required adjacency 当成必须已完成/未完成的状态维度精确建模；因此它可能非常乐观，导致所有 child 都不能安全剪掉。

这也说明 probe/Tmid 在该节点上可能只是次要原因。当前 probe 选到 `Tmid≈408.723` 后，forward 先扩展且队列快速增长，确实表现为单侧爆炸；把 Tmid 往左调可能会缩短 forward 半域，但如果 completion bound 继续为 0，它只是通过半域切分限制深度，不是真正增强剪枝，风险是把压力转移到 backward 或 join。后续如果要判断 Tmid 是否是主因，应在同一个 node3 快照上固定 dual、禁弧和列池后 replay 多个 Tmid，而不是完整重跑树；完整树会改变前序列池和 dual，比较会被路径依赖污染。

## 42. 2026-06-09 013 关 pricingOnly 的 node3 对照

按相同主线重新做了一个窄对照：`tmp-wet030_from040_013_2m`，ALNS seed 打开，RMIH 上界启发式关闭，`completionBound=allCycles`，`midpointStrategy=completionBound`，midpoint probe 打开，`maxNodes=3`，但同时关闭 formal subtree 和 pricingOnly subtree。运行名为 `tmp-current-013-full-alns-no-rmih-nopricingonly-node3-20260609`。

该对照可以正常处理完 node3。root 后 node1 没有固定 pricingOnly arc，node2 是 forbidden adjacency 左支，node3 是 required adjacency 右支，进入 node3 时 `pricingOnlyArc=0`、`adjReq=1`、`repair=8:3->5`。node3 总耗时约 `50.542s`，其中 pricing `50.111s`、exact `47.600s/4 calls`，新增 exact 列 `714`，节点最终 `lpObj=14372.842105`，处理后达到 `NODE_LIMIT`。

最关键的是，关 pricingOnly 后 node3 的 completion bound 明显有效。第一轮 exact pricing 选到 `Tmid≈347.288`，forward 阶段能跑完，统计为 `fwPops=7742`、`fwKept=9023`、`fCand=fBuilt=148718`、`fBoundSurvivors=32605`、`cbFPruned=116113`；backward 阶段也跑完，`bwKept=65015`、`cbBPruned=1386747`，最终 `joinPairs=5625524`、`generated=751`。后续三轮 exact 的 `cbFPruned` 也分别约为 `99016`、`144245`、`144026`，说明 `cbFPruned=0` 不是 required adjacency 本身必然导致的现象。

这组对照把问题收窄了：pricingOnly run 中 node3 的异常不是“completion bound 完全没用”或“required adjacency 一定让 bound 失效”，而是 pricingOnly 从 root/node2 改变了列池、dual 和 midpoint reference 后，在 node3 形成了另一套更糟的定价状态。无 pricingOnly 时 node3 的 `midpointRef≈1106`、首轮 `Tmid≈347`；pricingOnly run 中同类 node3 的 `midpointRef≈1414`、最终 `Tmid≈408.7`，且 forward 在 `fBuilt≈1.30M` 时仍 `cbFPruned=0`。也就是说，禁止部分弧本身不会让 pricing 更难；真正的问题是完整 BPC 路径中，pricingOnly 禁弧改变了前序列生成和 RMP dual，导致 completion bound 的 reduced-cost 下界变得过松，并把 Tmid 推到更偏右的位置。

因此，“禁弧应该更容易”只在固定 dual、固定列池、固定 Tmid 和固定补全图的单次 pricing 子问题里近似成立。完整 BPC 中，pricingOnly arc 不进 RMP 约束，只改变 pricing 可生成列；这会反馈到后续 master LP dual 和 branching path。若要严格判断同一 node 上“少弧是否更容易”，需要保存 node3 快照后 replay：同一 dual/列池下分别使用 pricingOnly arc on/off 或不同 Tmid，而不是从 root 完整重跑。
