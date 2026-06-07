# probe score 与 013 hard node 分析记录

## 1. 本次要解决的问题

这次讨论的核心不是重新设计 `Tmid` 策略，而是确认 midpoint probe 的统计口径是否可靠。旧口径把 `fwKept + fwQueuePeak` 和 `bwKept + bwQueuePeak` 作为默认 queue score，其中 `QueuePeak` 是历史峰值。这个口径有明显问题：如果某一侧中途队列很大，但最终已经被处理完，历史峰值仍会把该候选点判断为不均衡。更合理的默认口径应看 probe 停止时仍需要处理的压力，即：

`left = fwKept + fwQueueRemaining`

`right = bwKept + bwQueueRemaining`

`score = abs(log((left + 1) / (right + 1)))`

历史峰值仍保留为 `peak` 对照，用于诊断，但不再作为默认。

## 2. 代码调整

`GCNGBBStyleBidirectional` 中的 probe 结果现在同时记录四类分数：`kept`、`queue`、`bound`、`peak`。其中 `queue` 改为结束时队列剩余，`peak` 才是旧历史峰值口径。日志里的 `score=kept/queue/bound/peak` 可以直接对比这四个口径。

候选点选择也改成分层选择。先比较可靠性，再比较 score。当前可靠性定义为：

1. `rank=0`：forward/backward 两侧队列都耗尽，说明这个候选点在 probe 预算内完整跑完，最可信。
2. `rank=1`：没有完全耗尽，但 probe 期间 forward 和 backward 都实际 pop 过，说明至少观测到了两侧。
3. `rank=2`：至少一侧没有真正展开。例如 `sidePop=5000:0`，说明所有 pop 都花在 forward，backward 只是保留初始队列，不能把 `bwQueue=1` 当成真实右侧压力。

为支持这个判断，日志新增 `sidePop=fw:bw`。这不改变正式 pricing，只改变 probe 诊断和选点依据。

## 3. 013 hard node 实验

实验口径统一为：`tmp-wet030_from040_013_2m`，half-domain，`completionBound=allCycles`，`midpointStrategy=completionBound`，`maxNodes=4`，打开 `midpointProbe`。对比了 `queue/peak` 以及不同 pop limit。

### 3.1 queue, pop=5000

结果为 `solve=66.160s`，node3 `nodeTime=40.555s`，node3 exact `37.894s`。node3 第一次 exact pricing 选中 `Tmid=340.417`，正式 pricing 为 `fwKept:bwKept=12319:46969`。

关键在 probe summary：node3 所有候选都是 `sidePop=5000:0, rank=2`。也就是说，5000 pop 完全没有展开 backward，probe 只看到了 forward 初期压力。此时 `queue` score 会随着 `Tmid` 左移而下降，于是选中最左侧候选。这个结果比旧 `Tmid=563.5` 的 forward 长时间爆炸好，但它不是一个可靠的双向均衡判断。

### 3.2 peak, pop=5000

结果为 `solve=78.666s`，node3 `nodeTime=51.342s`，node3 exact `47.217s`。node3 仍然选中 `Tmid=340.417/336.875`，因为在这个 hard node 上 `qPeak` 与最终剩余队列几乎同向变化，历史峰值没有提供额外有效信号。

浅层节点上 `peak` 还会引入额外偏差。例如 root 后续轮次中，`peak` 可选到更靠右的点，正式 pricing 最后一轮为 `fwKept:bwKept=1116:496`，而 `queue` 同口径为 `991:1169`，后者更均衡。

### 3.3 queue, pop=10000

结果为 `solve=119.995s`，node3 `nodeTime=85.771s`，node3 exact `81.155s`。node3 第一次 selected `Tmid=325.360`，此时 selected 候选已达到 `sidePop=7353:2647, rank=1`，说明 probe 开始摸到 backward；正式 pricing 为 `fwKept:bwKept=7353:39088`。后续轮次选中 `347.405`，最后一轮正式 pricing 为 `8547:47292`。

这个设置比 5000 更能观测两侧，但选点仍偏左，backward 和 join 压力偏大。更重要的是，probe 成本明显增加，端到端变慢。

### 3.4 queue, pop=20000

结果为 `solve=165.994s`，node3 `nodeTime=133.258s`，node3 exact `129.096s`。node3 probe 能稳定出现 `rank=1`。第一次选中 `Tmid=361.511`，probe queue score 为 `0.091`，正式 pricing 为 `fwKept:bwKept=14124:34834`。后续轮次选中 `401.632`，正式最后一轮为 `17506:25082`。

这个结果说明较大的 probe 预算确实可以让左右 label 更接近，但代价太高。相比 pop=5000，node3 exact 从 `37.894s` 放大到 `129.096s`，不适合作为默认。

## 4. 当前结论

历史峰值口径不适合作默认。它描述的是中途最大队列，不是 probe 停止时还剩多少工作；在候选已跑空时尤其容易误导。默认 score 使用 `kept + queueRemaining` 更合理，`peak` 只适合作为诊断对照。

但是，013 hard node 的主要问题不是 score 公式本身，而是有限 pop 下 probe 没有观测到两侧。`pop=5000` 时所有候选都是 `rank=2`，最低 score 只是“forward 队列随着左移变小”，不能说明正式双向 pricing 会均衡。`pop=10000/20000` 能让 probe 摸到 backward，但代价过高。

因此 probe 当前更适合做诊断或 hard-node fallback，不适合所有节点默认开启。下一步如果继续优化，应优先改 probe 调度和停止准则，而不是继续提高全局 pop：

1. 对每个候选点强制两侧至少各 pop 一小段，避免 `sidePop=N:0` 的单侧观察。
2. 如果所有候选都是 `rank=2`，不要过度相信最低 score，至少要在日志里标记为低可信。
3. 保留“方向阈值”和 bracket/二分思路，但先不实现。具体想法是：当 `left/right` 差距不大时认为当前 split 足够均衡；当连续向左后方向反转，再在左右 bracket 中试探，而不是一直乘 `0.9/1.1`。

这次实现已经把“分数口径”和“可靠性等级”拆开，后续实验能明确区分：是候选点本身不好，还是 probe 没有足够观测两侧。

## 5. 2026-06-07 平衡 pop 调度复核

继续检查后发现，上一版 probe 的 pop 调度仍有一个结构性偏差：它每次选择当前队列更大的一侧扩展。这个规则适合做“哪边已经更大就继续压测哪边”的 stress test，但不适合公平比较 forward/backward 压力。典型现象是 `sidePop=5000:0`，forward 一侧越扩越大，于是预算继续被 forward 吃掉，backward 没有机会展开。此时 score 只能说明 forward 初期增长很快，不能说明该 `Tmid` 下两侧真实压力是否均衡。

本次将 `runMidpointProbeCandidate` 改为两侧配额采样。`popLimit` 仍表示总预算，但拆成 `forwardLimit=(popLimit+1)/2` 和 `backwardLimit=popLimit/2`；先扩展 forward 到配额或队列为空，再扩展 backward 到配额或队列为空。这样 `popLimit=5000` 时，候选点一般会得到 `sidePop=2500:2500`，不会再出现一侧独占预算。由于 probe 默认关闭，但开启时原默认 `popLimit=20000` 已经偏重，本次同步把默认值调为 `5000`。

用同一 013 hard node 复测：

1. `queue, balanced pop=5000`：`solve=61.084s`，node3 `nodeTime=39.626s`，node3 exact `36.408s`，其中 probe 合计约 `9.737s`，非 probe 约 `26.671s`。node3 四次 exact 的候选均为 `sidePop=2500:2500, rank=1`，最后一轮正式 labels 为 `fw=8547, bw=47292`。相比旧 `queue pop=5000` 的 `solve=66.160s/node3 exact=37.894s/probe≈10.976s`，平衡采样略快，且诊断语义更干净。
2. `queue, balanced pop=10000`：`solve=63.192s`，node3 `nodeTime=41.212s`，node3 exact `37.906s`，其中 probe 合计约 `15.131s`，非 probe 约 `22.775s`。最后一轮正式 labels 为 `fw=11095, bw=43089`。它比 balanced `5000` 更重，没有带来端到端收益。

因此当前更明确的结论是：默认应使用平衡 pop，且默认预算保持 `5000`。继续简单增加 pop 不划算；它会让 probe 更充分，但会把大量时间花在 dry-run 上。后续如果要继续优化 probe，不应回到“队列大优先”，而应改候选迭代策略。

关于候选迭代，目前仍是从 reference 开始，若当前候选的 forward 压力大则乘 `0.9` 往左，否则乘 `1.1` 往右，最多 6 个候选。这种单向乘法能快速把 `completionBound` 偏大的 `Tmid` 往左拉，但它确实可能过于灵敏：当左右压力量级接近时仍会移动；当方向反转时也没有 bracket。当前不急着做完整二分，因为 balanced pop 后每个候选已经能公平观测两侧，先用它判断 score 是否稳定。若后续仍出现候选来回跳或明显错选，再实现 bracket 版：只有当 `left/right` 超过阈值才移动；一旦方向反转，就在左右两个候选之间做 1-2 次二分，而不是继续乘 `0.9/1.1`。

## 6. 2026-06-07 probe 简化实验

进一步围绕“原始 reference 是否足够、probe 是否可以更少”做了两个小实验，仍使用 `tmp-wet030_from040_013_2m`、half-domain、`completionBound=allCycles`、`midpointStrategy=completionBound`、`maxNodes=4`。

`balanced pop=2500,maxCandidates=6` 的结果为 `solve=73.759s`，`exact=57.828s`，比当前 `pop=5000,maxCandidates=6` 的 `61.084s/49.105s` 更慢。主要原因不是 probe 本身更慢，而是采样太浅导致 node3 选点偏左到 `336.875/340.417`，后续正式 pricing 的列生成路径更差，node3 三次 exact pricing 分别约 `15.079s/13.787s/11.975s`。这说明简单把 pop 减半会降低 score 的代表性，不能作为默认加速。

`balanced pop=5000,maxCandidates=4` 的结果为 `solve=80.821s`，`exact=64.544s`，也明显慢于当前默认。node3 因只试 4 个候选，选中 `410.792/401.679` 附近，没有继续试到 013 hard node 上更合适的 `347` 附近；正式 pricing 的 forward/join 压力显著增加，node3 三次 exact pricing 约 `21.757s/14.437s/12.455s`。这说明直接减少候选数会省掉部分 probe，但会把成本转移到正式 pricing，得不偿失。

由此当前判断是：`pop=5000,maxCandidates=6` 不是因为它最省 probe，而是因为它在这个 hard node 上能找到足够靠左且不过分偏左的 split。后续真正有价值的简化不是粗暴降低 pop 或候选数，而是：对每个节点只在第一次 exact pricing 时 probe，后续同一节点复用已选 `Tmid`，除非 LP dual 或候选列结构发生明显变化；同时增加早停规则，如果 reference 的 `rank=0` 且 `queueScore` 已低于小阈值，例如 `0.10` 或 `0.15`，直接接受 reference，如果某个候选已经达到很小 score，例如 `0.05`，也可以停止继续试探。013 node3 的 `pop=5000` 日志中 4 次 exact pricing 的 probe 合计约 `9.7s`，若只保留第一次 probe，理论上可省后续约 `6-7s` 的 dry-run 成本；而早停规则主要服务 root 或浅节点，对 reference score 很差的 hard node 不会误停。

## 7. 2026-06-07 score 倍数口径与早停阈值

当前 `queueScore` 是 `abs(log((left+1)/(right+1)))`，所以它本身不是倍数，而是左右压力倍数的对数。更直观的压力倍数可以写成 `ratio=exp(score)`。例如 `score=0.05/0.10/0.20/0.30/0.50/0.70/1.00` 分别约等于 `1.05/1.11/1.22/1.35/1.65/2.01/2.72` 倍；如果允许 4-5 倍不平衡，对应 `score=1.39-1.61`。这个阈值对当前 probe 太松，因为 013 hard node 的 reference 附近 `score≈0.67`，也只是约 1.95 倍，但后续正式 pricing 已经明显偏重；真正较好的候选通常在 `score<0.2`，甚至 node3 最好点在 `0.01-0.02`。

因此后续日志里应同时输出 `scoreRatio`，或者在分析时直接把 `score` 换成倍数看。建议初始阈值按三档理解：`score<=0.10`，即 1.11 倍以内，可以认为非常均衡；`0.10<score<=0.20`，即 1.22 倍以内，通常可以接受；`0.20<score<=0.35`，即 1.42 倍以内，属于可疑但可能够用，需要看 full pricing 是否仍轻；`score>0.50`，即 1.65 倍以上，不建议早停，除非该候选已经 `rank=0` 且完整跑空非常快。

早停规则可以先保守实现为：若 reference 的 `rank=0` 且 `score<=0.15`，直接接受 reference，不再 probe；若任意候选 `rank=0` 且 `score<=0.10`，立即停止；若候选为 `rank=1`，阈值再严格一些，例如 `score<=0.05` 才立即停止。这样不会在 013 node3 的 reference 上误停，因为它的 score 远高于这个范围；但能减少 root、浅节点或容易节点上的无意义试探。节点内复用可以单独开关测试：同一节点第一次 exact pricing 做 probe，后续 rounds 复用该节点上次选中的 `Tmid`，并记录复用后 full pricing 的 label 数和时间，判断 dual 变化是否足以破坏复用假设。

进一步把 `pop=5000,maxCandidates=6` 日志中的 selected 候选和正式 full pricing 结果对齐后，可以看到 rank 的语义很重要。`rank=0` 表示 probe 队列已经跑空，因此 probe 压力倍数和正式 `fwKept/bwKept` 倍数一致，例如 node2 三轮分别为 `1.054/1.100/1.222`，node4 三轮为 `1.435/1.617/1.608`。这类情况可以直接接受，因为 probe 已经不是近似观测，而是完整跑完了有限问题。

但 node3 的 selected 候选都是 `rank=1`，probe 压力倍数看起来很小，只有 `1.078/1.015/1.015/1.012`；正式 full pricing 结束后的 `fwKept/bwKept` 倍数却是 `5.315/6.569/5.507/5.533`。这验证了用户提出的判断：有限 pop 下即使左右只差 2 倍以内，继续完整扩展后也可能因为状态增长呈指数型而放大到 4-5 倍以上。因此早停规则不能只看 score 数值，必须要求 `rank=0`，或者对 `rank=1` 使用非常严格的阈值并继续保留诊断。对 hard node 来说，`rank=1 && score≈0.01` 只能说明“当前采样窗口内比较均衡”，不能说明 full expansion 会均衡。

## 8. 2026-06-07 rank=1 近似失真后的指标判断

继续拆 013 node3 的候选后，发现当前 `queueRatio` 近似失真的核心不是 pop 太少这么简单，而是它只看一个浅层截面。node3 中 selected 候选虽然 `probeRatio≈1.01`，但两侧队列都没空，说明双方还在增长；后续哪一侧更容易继续产生 label，不能从当前截面的静态比例直接看出来。把 `popLimit` 从 5000 提到 10000 后，失真有所缓解，full 倍数从 `5-6` 降到 `2.5-3.9`，但仍不够好，且 probe 成本上升。因此“加深所有候选”不是好方案。

已有日志中 `total pressure = left + right` 和 `unfinished = (fwQueue+bwQueue)/(fwKept+bwKept)` 有一定信息。node3 几轮中，按 total pressure 或 unfinished 排序会倾向更左的候选，例如 `312/319` 一带；这些候选的当前 ratio 往往是 `1.6-1.7`，不如 selected 的 `1.01`，但总压力更低、未完成队列比例更低。它们可能降低 full label 总量，也可能因为过左而把问题转移到另一侧，当前日志没有对应 full pricing 结果，不能直接定为默认。

`boundTotal` 当前不适合作主指标。它在 node3 上经常偏向更右的候选，例如 `529/541`，这些点的 queue ratio 和历史经验都显示会带来更重的 forward 或 join 压力。它可以保留为诊断，但不应直接替代 queue score。

更有前景的是记录 probe 的增长斜率，而不是只记录终点。做法是在每侧 probe 扩展过程中每隔固定 side-pop，例如 500，记录一次 `kept/queue/boundSurvivors`。对每个候选计算后半段增长率，例如 `growth = pressure(k_end)/pressure(k_mid)`，以及两侧增长率比值。若某个候选当前 ratio 接近 1，但某一侧 growth 明显更大，就不应把它当成稳定均衡。这个指标直接针对“浅层均衡、深层指数放大”的问题，改动也比完整加深所有候选小。后续可先只把 checkpoint/growth 写进日志，不改变选择逻辑；确认它能解释 013 node3 后，再把 score 改成 `ratio + pressure/growth` 的组合。

## 9. 2026-06-07 growth/pressure 评分实测

按上述判断，本次把 `growth` 和 `pressure` 做成可切换的 probe score，并在 013 hard node 上实测。实现上没有改变默认 `queue` 路径；`growth` 记录每侧半程压力到终点压力的增长倍数，score 为 `queueScore + growthImbalance`；`pressure` 只最小化 `fwKept+fwQueue+bwKept+bwQueue` 这个总压力。日志同步改为输出 `queueRatio`、`growth=fw:bw` 和 `selectedScore=mode:value`，避免把不同 score 的量纲混在一个 `ratio` 字段里。

测试口径仍为 `tmp-wet030_from040_013_2m`、half-domain、`completionBound=allCycles`、`midpointStrategy=completionBound`、`maxNodes=4`、`probePopLimit=5000`、`probeMaxCandidates=6`。

`growth` 结果为 `NODE_LIMIT, solve=100.012s, exact=81.884s, pricing=67, cols=6809, valid=true`。相比当前 `queue` 基线 `solve=61.084s, exact=49.105s` 明显变慢。node3 第一轮仍选到 `Tmid=325.360`，完整 pricing 为 `fw:bw=7353:39088`；后续几轮选到 `355.278/347.405`，完整比例仍在 `5.5-6.6` 倍附近。说明当前半程增长率没有可靠提前识别 backward 后续爆炸，反而增加了不稳定性。

`pressure` 结果为 `NODE_LIMIT, solve=93.527s, exact=79.616s, pricing=65, cols=7161, valid=true`，也慢于 `queue` 基线。node3 第一轮选到 `Tmid=340.417`，完整 pricing 为 `fw:bw=12141:45519`；第二轮甚至选到 `319.750`，完整比例扩大到 `6526:73716`，backward 压力显著恶化。它验证了一个负结论：只看总压力会偏向更左的候选，但更左不等于更均衡，可能把后续工作量转移到 backward。

由此当前结论是：`growth` 和 `pressure` 暂时只保留为诊断 score，不建议设为默认。`queue` 仍是当前最稳的 probe score，尽管它在 rank=1 hard node 上会低估 full imbalance。后续如果继续改 probe，重点不应是再换一个单一 score，而应做两件事：一是把 probe 的候选选择和正式 full pricing 结果对齐记录，形成可学习的 hard-node 样本；二是考虑“先用 queue 找低 imbalance 候选，再用 total pressure/growth 作为次级 tie-break 或 reject 规则”，而不是直接用 total pressure 或 growth 覆盖 queue。
