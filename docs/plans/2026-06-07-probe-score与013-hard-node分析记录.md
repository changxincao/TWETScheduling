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

## 10. 2026-06-07 弃用 growth/pressure 后的 queue 问题复盘

继续讨论后，决定不保留 `growth/pressure` 两个 score 的代码路径。原因不是它们完全没有信息，而是当前实测已经说明：`growth` 没能解释 node3 后续 backward 爆炸，`pressure` 又容易把 `Tmid` 拉得过左，把压力转移到 backward。保留这两个可选项只会增加配置和日志理解成本。因此代码恢复为 `kept/queue/bound/peak` 四个 score，`growth/pressure` 只作为这次负实验结论写入记录。

回到 `queue` 本身，它在 013 node3 上确实比直接使用 `completionBound` reference 更好。`completionBound` 给出的 `Tmid` 约在 `563.5`，明显偏右，forward 左半域太宽，正式 pricing 会在 forward 阶段积累大量 label，甚至还没进入 backward 就已经很重。`queue probe` 的作用是从这个偏右 reference 开始试探，把 `Tmid` 往左拉到 `325-355` 一带；虽然 full ratio 仍不均衡，但 node3 的 exact 时间已经从 completionBound reference 下的严重 forward 爆炸降到几十秒量级。也就是说，queue 的主要收益不是“准确预测最终左右比例”，而是“发现 completionBound reference 太右，并把 split 拉到一个明显更可用的区间”。

`probe ratio = 1.078/1.015/1.015/1.012` 到 `full ratio = 5.315/6.569/5.507/5.533` 的差距，核心原因是有限 pop 只观察了搜索树的浅层截面。balanced `pop=5000` 时，每侧大约只 pop 2500 次；在 node3 这种 hard node 上，两侧队列都没有耗尽，`rank=1` 只表示两边都被采样过，不表示两边的状态空间已经展开完整。此时 `queueRatio` 看到的是“当前已保留 label + 当前队列”的截面比例，而 full pricing 看到的是这个截面后面所有后代 label 的总量。若某一侧的队列虽然当前不大，但里面的 label 更容易继续扩展出大量后代，有限 pop 就会低估它的最终规模。

013 node3 的现象更像是这种结构：较小的 `Tmid` 把 forward 半域压住了，所以 probe 截面上 forward 和 backward 看起来接近；但 backward 侧剩余队列中的 label 在继续扩展时能产生很多可保留后缀，且 completion bound 没有在这些后缀早期把它们全部剪掉。于是 full expansion 结束后 backward label 数从浅层截面的接近均衡，放大成 5-6 倍。换句话说，有限 pop 的 `queueRatio` 是局部截面指标，不是剩余子树大小估计。

这也解释了为什么简单加大 pop 有改善但不划算。`pop=10000/20000` 能让 probe 更深入，full ratio 从 `5-6` 倍降到更接近 `2-4` 倍，但 probe 自身成本明显上升，端到端反而更慢。后续如果继续优化，不应再尝试单一静态 score，而应考虑更直接地估计“剩余队列的后代规模”：例如只对 queue score 最好的少数候选做更深二阶段 probe，或者在正式 pricing 的前几轮复用上一轮 full expansion 观察到的偏差来修正同一 node 后续 `Tmid`。这些都比把 `growth/pressure` 作为全局 score 更稳。

## 11. 2026-06-07 node3 probe/full ratio 差距的具体来源

继续把 `tmp-probe-balanced-013-completionBound-queue-pop5000-n4` 的 node3 四轮 selected 候选和正式 full pricing 对齐后，可以更具体地看出差距来自哪一侧。四轮 selected 的 probe 截面分别为：

1. `Tmid=325.360`：probe 左侧压力 `5725+3225=8950`，右侧压力 `6074+3575=9649`，ratio 约 `1.078`；full 为 `fw=7353,bw=39088`，ratio 约 `5.315`。
2. `Tmid=355.278`：probe 左侧压力 `6399+3899=10298`，右侧压力 `6321+3822=10143`，ratio 约 `1.015`；full 为 `fw=9504,bw=62437`，ratio 约 `6.569`。
3. `Tmid=347.405`：probe 左侧压力 `6145+3645=9790`，右侧压力 `6074+3575=9649`，ratio 约 `1.015`；full 为 `fw=8605,bw=47393`，ratio 约 `5.507`。
4. `Tmid=347.405`：probe 左侧压力 `6086+3586=9672`，右侧压力 `6027+3528=9555`，ratio 约 `1.012`；full 为 `fw=8547,bw=47292`，ratio 约 `5.533`。

这里最关键的观察是：full 的 forward kept 并没有比 probe 左侧压力大，反而同量级或略小；真正放大的是 backward。第二轮中，probe 右侧压力约 `10143`，full backward kept 到 `62437`，约为 `6.16` 倍；第三、四轮也约为 `4.9-5.0` 倍。也就是说，有限 probe 的误差主要是没有估计出 backward 队列中那些 label 的后续可扩展后代，而不是两侧同时等比例增长。

这说明 `queueRatio` 的局限不是“公式错”，而是它把每个队列 label 当成一个单位压力。对 hard node 来说，两个队列长度相近时，剩余子树规模可能完全不同：forward 队列里的 label 后续很快被时间、dominance 或 completion bound 截掉；backward 队列里的 label 虽然数量相近，但每个 label 还能向前接出更多可保留前驱，且在早期不容易被 completion bound 证明为无用。于是浅层截面看起来平衡，完整展开后 backward 明显放大。

这也解释了为什么 queue 对 completionBound reference 仍然有用。reference 附近的候选，例如第一轮 `Tmid=551`，probe 已经显示左侧压力约 `20236`、右侧约 `7545`，forward 过重；因此 queue 把 `Tmid` 往左拉是对的。它修正的是“completionBound 的 reference 太右”这个一阶错误。但拉到 `325-355` 后，queue 只能保证浅层截面接近平衡，不能保证剩余右侧子树不会继续膨胀。

因此后续若要继续优化，方向应更具体：不是换 score，而是给 `rank=1` 候选估计队列后代规模。比较实用的方式可能有两个。第一，对 queue score 最好的 1-2 个候选做二阶段 probe：第一阶段仍用 `pop=5000` 找候选，第二阶段只加深这些候选的 backward/forward 各一小段，看右侧是否开始持续放大。第二，在同一 node 的后续 pricing round 中利用上一轮 full expansion 的实际偏差，如果上轮 selected 的 `fullRatio/probeRatio` 显示 backward 被低估 5 倍，那么下一轮同类 reference 不应再仅按浅层 queueRatio 选点，而应对更靠右或更低 backward 后代风险的候选加权。前者更局部、实现更直接；后者利用真实 full 信息，但需要谨慎避免 dual 变化后误用。

## 12. 2026-06-07 小数 Tmid 与 node3 多轮 pricing 说明

继续检查 `tmp-probe-balanced-013-completionBound-queue-pop5000-n4` 后，`347.405` 出现两次的原因已经明确：这是同一个 BPC node 内的第三、第四轮 exact pricing，不是同一轮被重复计算。第三轮在 `Tmid=347.40495` 下生成了 1 条负 reduced-cost 列并加入 RMP；随后 LP 重新求解，dual 和列池状态变化，第四轮再次 exact pricing。由于 completion bound reference 仍为 `1034.0`，probe 起点仍为 `(25+1034)/2=529.5`，动态候选序列也再次选到 `347.40495`。两轮看起来 Tmid 一样，但 pricing 问题已经不同，因此 full label 数分别是 `fw=8605,bw=47393` 和 `fw=8547,bw=47292`，并不矛盾。

probe 里“label 已经比最终完整更多”的现象来自统计口径混用。probe 的 queue score 使用的是 `kept+queue` 压力，例如第三轮选中点左侧为 `6145+3645=9790`，右侧为 `6074+3575=9649`；正式 full pricing 的 `labels fw/bw` 只记录最终 kept label，队列已经耗尽。`kept+queue` 不是最终 kept 的下界或上界，只是有限 pop 截面上的压力估计。若只比较 kept，第三轮 probe 为 `6145:6074`，full 为 `8605:47393`，真正被低估的是 backward 后续子树规模。

这份日志中 completion bound 策略给出的 raw reference 和 probe 起点如下：

1. 第一轮：`midpointStrategy/ref=completionBound/1077.0`，`earliestSourceCompletion=25.0`，所以 probe 起点 `Tmid=(25+1077)/2=551.0`。该点 probe 压力为 `20236:7545`，倍数约 `2.68`；selected `325.360` 的 probe 压力为 `8950:9649`，倍数约 `1.08`，但 full 为 `7353:39088`，倍数约 `5.32`。
2. 第二轮：reference `1058.0`，probe 起点 `541.5`，probe 压力倍数约 `14912:8153=1.83`；selected `355.278` 的 probe 倍数约 `1.02`，full 倍数约 `6.57`。
3. 第三轮：reference `1034.0`，probe 起点 `529.5`，probe 压力倍数约 `15898:8129=1.96`；selected `347.405` 的 probe 倍数约 `1.02`，full 倍数约 `5.51`。
4. 第四轮：reference `1034.0`，probe 起点 `529.5`，probe 压力倍数约 `15688:8055=1.95`；selected `347.405` 的 probe 倍数约 `1.01`，full 倍数约 `5.53`。

因此 queue probe 对 completion bound reference 的作用仍然成立：它能识别出 `529-551` 一带偏右，左侧 forward 压力明显过大，并把 Tmid 拉到 `325-355` 一带。但它不能准确预测 full expansion 的最终左右 label 比例，因为 rank=1 候选只展开了有限截面，未估计队列 label 的后代规模。当前若要输出“每种策略在同一 node/同一 LP 状态下的 full label 数”，现有日志不够；不同策略单独跑会改变列生成路径和分支树，不能当作同一个 pricing 状态直接横比。真正严谨的做法是增加一个只用于诊断的 multi-Tmid replay：在同一 LP dual、同一 node、同一列池上，对若干指定 Tmid 分别 dry-run 完整 exact pricing，且不向 RMP 加列。这个诊断成本较高，适合只在 hard node 上打开。

代码层面已把 `clampCurrentMidpoint` 改成先防贴边、再尽量四舍五入为整数 Tmid。Tmid 只是半域切分点，优先取整数可以避免 `347.40495000000004` 这类 `0.9` 连乘小数污染日志和 cache key；若整数会贴到半域边界，则保留原 clamped 值。

## 13. 2026-06-07 queue-only 比例补充

继续核对 node3 的 probe 比例时，需要区分两种口径。前文写的 `ratio≈1.08` 是无方向的不平衡倍数，即 `max(left,right)/min(left,right)`；例如第一轮 selected `Tmid=325.360` 的 `kept+queue` 压力为 `8950:9649`，带方向的 `forward/backward=0.928`，无方向不平衡倍数为 `9649/8950≈1.078`。这个点不是 forward 更大，而是已经略微偏 backward。

如果只看剩余队列 `q`，第一轮候选从右到左的 queue-only 倍数为：`551.0` 为 `8868:2523≈3.52`，`495.9` 为 `2.78`，`446.31` 为 `2.64`，`401.679` 为 `2.28`，`361.511` 为 `1.68`，`325.360` 为 `3575/3225≈1.11`。第二轮为 `2.20/1.79/1.70/1.47/1.02/2.02`；第三轮为 `2.38/2.07/1.93/1.60/1.02/2.27`；第四轮为 `2.37/2.07/1.91/1.60/1.02/2.28`。在这四轮里，queue-only 和 `kept+queue` 都会选中同一类 `325-355` 候选，因此它能更直观地说明 completionBound reference 附近确实是极端偏 forward 的候选，应被排除。

但 queue-only 不能单独解决 rank=1 近似失真。原因是队列长度仍然只是当前 frontier 的数量，不包含每个 frontier label 的后代规模；013 node3 的问题正是 selected 候选当前队列接近平衡，但 backward 队列里的 label 后续能扩展出更多可保留后代。`kept` 也不是无用项，它反映 probe 已经实际产生并保留的 label 数，会影响 dominance store 和 join 压力。当前更合理的判断是：queue-only 可以作为 `rank=1` 候选的补充诊断，尤其用于识别非常极端的 reference；但默认 score 仍不宜只用 queue-only，后续若要改选择逻辑，应考虑把 queue-only 作为一级筛掉极端候选，再用 `kept+queue` 或二阶段 probe 判断剩余候选。
