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
