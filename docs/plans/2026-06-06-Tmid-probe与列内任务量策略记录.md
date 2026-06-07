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

当前实现输出三类 score：`kept` 使用 `fwKept/bwKept`；`queue` 使用 `fwKept+fwQueuePeak` 和 `bwKept+bwQueuePeak`；`bound` 使用 `forwardExtensionBoundSurvivors+fwQueuePeak` 和 `bwKept+bwQueuePeak`。score 公式均为 `abs(log((left+1)/(right+1)))`，没有引入 alpha 总量惩罚。自动选择由 `bidirectionalMidpointProbeScore` 控制，默认 `queue`。summary 中会输出所有候选的 `kept/q/bound/cb/score`，后续实验可比较哪个 score 更贴近真实耗时。

为便于命令行测试，`GCBBFullDomainComparisonTest` 增加了 `twet.bpc.fullDomainCompare.midpointProbe`、`midpointProbePopLimit`、`midpointProbeMaxCandidates`、`midpointProbeMoveRatio` 和 `midpointProbeScore` 系统属性读取。验证只做 focused `javac`，没有按用户要求启动算例实验。

## 8. 2026-06-06 013 probe-off 小测试

按用户要求清理了 `columnTaskCenter` 冗余别名，当前只保留 `columnTaskMedian`。同口径测试使用 `tmp-wet030_from040_013_2m`、half-domain、`completionBound=allCycles`、`maxNodes=2`、probe 关闭。

已有对照中，旧 `default` 为 `solve=11.370s, exact=3.984s, cols=5746`，`completionBound` 为 `solve=10.057s, exact=3.267s, cols=5653`，旧 `columnLastAvg` 为 `solve=11.643s, exact=4.672s, cols=5512`。由于本次已把 column limit 调整为 400，额外用当前代码重跑 `columnLastAvg-current400`，结果为 `solve=14.976s, exact=9.155s, cols=6511`。

`columnTaskMedian` 当前结果为 `solve=29.494s, exact=16.019s, cols=5840`，没有优于已有策略。日志显示其在 root 的 `Tmid` 约为 `462-463`，node2 约为 `450-452`；当前 400 条好列的任务完成时间中位数确实落在这个位置。但该切分点比 `columnLastAvg-current400` 的 `Tmid≈480-492` 更靠左，root 阶段的 backward label、join pairs 和 completion bound 评估量都明显上升，node2 后续轮次也没有形成足够补偿。因此在 013 两节点小测试上，直接取任务完成时间中位数偏左，不适合作为默认策略。

## 9. 2026-06-07 013 probe score 与 median 偏左分析

继续使用 013、half-domain、`completionBound=allCycles`、`maxNodes=2` 口径，额外打开 `midpointProbe=true` 测试 `columnTaskMedian`。本次结果为 `solve=44.375s, exact=29.771s, cols=6084`。由于 probe 每轮正式 exact pricing 前会试探多个候选点，计入 exact pricing 总耗时，所以该结果不能直接和 probe-off 的求解时间比较；它主要用于判断 probe score 是否能反映正式 pricing 的左右压力。

对 probe 最终选中的 `Tmid`，probe 的 `kept` score 与正式完整 pricing 用 `fwKept/bwKept` 重算的 score 完全一致；用 probe 输出的裸 `boundSurvivors:bwKept` 重算的 raw-bound score，也与正式 pricing 的 `forward boundSurvivors / bwKept` 一致。例如 root 第一轮选中 `Tmid=554.628`，probe 与正式 pricing 的 kept score 均为 `0.309`，raw-bound score 均为 `0.515`。后续几轮同样一致：`Tmid=560.230` 时 kept score 为 `0.141/0.077/0.063`，node2 的 `Tmid≈658.845/661.773` 时 kept score 为 `0.363/0.256/0.217`。这说明当前 probe 在候选点已经跑到队列耗尽时，至少 kept/raw-bound 这两个趋势是可信的。需要注意的是，probe summary 中第三个 `bound` score 当前包含 queue peak，而正式日志没有 queue peak，因此不能直接拿第三个 score 和正式 raw-bound score 比。

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

probe 的方向规则如下。每个候选 `Tmid` 跑有限 pop dry-run 后，根据 `bidirectionalMidpointProbeScore` 计算左右压力，默认 `queue` 模式使用 `left=fwKept+fwQueuePeak`、`right=bwKept+bwQueuePeak`。若 `left>right`，说明 forward 侧压力更大，下一个候选设为 `current*(1-moveRatio)`，即把 `Tmid` 左移以缩小 forward 半域；否则说明 backward 侧压力更大，下一个候选设为 `current*(1+moveRatio)`，即把 `Tmid` 右移以缩小 backward 半域。`kept` 模式只看 `fwKept/bwKept`，`bound` 模式用 `forwardBoundSurvivors+fwQueuePeak` 对 `bwKept+bwQueuePeak`。

新增 `ms` 字段后，013 上 `columnLastAvg+probe5000` 的 root 第一轮候选耗时可以直接看到。候选序列为 `492.13 -> 541.35 -> 595.48 -> 535.93 -> 589.53 -> 530.57`，单候选耗时分别约 `564/283/150/241/134/231ms`，合计约 `1.60s`。它最终选中 `541.35`，正式 pricing 第一轮 `fwKept:bwKept=2848:2712`、`joinPairs=225344`，比不开 probe 的 `columnLastAvg` 第一轮 `2342:5032`、`joinPairs=489848` 更均衡，说明方向判断有用；但总结果为 `solve=24.545s, exact=15.515s`，仍慢于不开 probe 的 `solve=14.976s, exact=9.155s`，因为 probe 自身开销和后续列生成路径变化抵消了第一轮收益。

把 pop 降到 2000 后，`columnLastAvg+probe2000` 总结果改善为 `solve=20.807s, exact=11.866s`，但仍慢于不开 probe。更重要的是，它暴露了过小 pop 的风险：root 第一轮 `Tmid=492.13` 时只跑 2000 pop，backward 侧几乎还没展开，日志为 `kept=2264:0, q=407:1`，于是 score 误判为 forward 过重，把下一轮往左移，最终选中 `438.49`，正式 pricing 第一轮变成 `fwKept:bwKept=1749:8914`、`joinPairs=605431`，比不开 probe 更差。这说明 pop 不能盲目压小；如果预算太小，probe 看到的是初始化顺序偏差，不是真实左右压力。

`completionBound+probe2000` 同样不适合作为默认。不开 probe 的 `completionBound` 是当前 013 两节点里最好的结果，`solve=10.057s, exact=3.267s`；打开 probe2000 后变为 `solve=19.174s, exact=10.055s`。第一轮 probe 从 `527.5` 出发，但 2000 pop 同样先看到 backward 不充分的问题，最终选中 `427.275`，正式 pricing 第一轮 `fwKept:bwKept=1668:9864`、`joinPairs=693485`，明显劣化。

当前建议是：正式默认仍优先使用 `completionBound` 且关闭 probe；`default` 可作为稳健对照；`columnLastAvg` 可保留为没有合适 completion-bound 时间信号时的候选，但不应默认配 probe；`columnTaskMedian` 和 `columnTaskMedianTopLast` 暂不推荐。probe 的价值主要是诊断 hard node 或后续做受控 fallback：若要用，不能把 pop 设得太小，至少要保证 forward/backward 都有足够展开；同时应考虑只在上一轮 exact pricing 明显爆炸时触发，并限制候选数，而不是所有节点默认开启。
