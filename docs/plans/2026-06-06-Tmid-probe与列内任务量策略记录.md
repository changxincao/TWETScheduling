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
