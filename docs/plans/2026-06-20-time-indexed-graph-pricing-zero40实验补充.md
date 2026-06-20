# Time-indexed graph pricing 40-2 zero setup 实验补充

## 1. 实验设置

本轮用 time-indexed graph pricing 求解 `data/40-2/wet040_001_2m.dat` 的 zero setup 版本。配置为：`useTimeIndexedGraphPricing=true`、`zeroSetup=true`、ALNS seed 开启、启发式 pricing 开启、RMIH 4s、关闭 SRI cut、关闭无向 adjacency branching，只保留当前 machine/arc 分支。由于 time-indexed graph pricing 自身是单向 DAG，不使用 completion bound、midpoint probe 或 ng-DSSR；post-CG 使用论文口径 reduced-cost time-indexed arc fixing。

本次为了便于复现，给 `GCBBFullDomainComparisonTest` 补了两个系统属性入口：`twet.bpc.fullDomainCompare.zeroSetup` 和 `twet.bpc.fullDomainCompare.timeIndexedGraphPricing`，同时在结果 CSV 中把 exact engine 显示为 `TimeIndexedGraphPricing`，避免和普通 half-domain pricing 混淆。

## 2. 根节点结果

root-only 结果为 `NODE_LIMIT`，`incumbent=17881`，root bound `17866.666667`，gap `0.080160%`，root solve `70.013s`。其中启发式 pricing `44.466s/43 calls/add9866`，time-indexed exact pricing `1.483s/11 calls/add347`，RMIH 找到 `17881` 但没有改进已有上界。

root 后的 time-indexed arc fixing 统计为 `candidates=1314583, fixed=2261948, unavailable=665246, processFixed=582056, idleFixed=29239, endFixed=30466, cleanupFixed=1620187, reusedForward=true`，fixing 自身约 `3.546s`。日志为 `test-results/bpc/2026-06-20-timegraph-zero40-root/wet040_001_2m-halfDomain-timeGraph.log`。

## 3. 完整搜索结果

完整搜索结果为 `FINISHED, incumbent=bound=17881, nodes=17, solve=274.001s, root=55.179s, pricing=426, pool=36085, valid=true`。累计启发式 pricing 为 `192.498s/339 calls`，time-indexed exact pricing 为 `4.376s/87 calls`，master LP 为 `6.338s`。最终日志为 `test-results/bpc/2026-06-20-timegraph-zero40-full/wet040_001_2m-halfDomain-timeGraph.log`。

该结果与之前 zero setup 最优值 `17881` 一致，说明当前 DWM visit-count 口径下，time-indexed graph pricing 至少在该算例上没有造成目标值不一致。分支过程中 root bound 为 `17866.666667`，后续全局 bound 逐步提升，最后在 node 17 到达 `17881` 并闭合。

## 4. 对比判断

和同一算例最近的 normal ng-DSSR nearestK/top10 no-SRI 记录相比，time-indexed graph pricing 明显降低了 exact pricing 时间，但整体没有更快。历史记录 `tmp-wet040-001-zero-setup-current-20260616.csv` 为 `FINISHED, incumbent=bound=17881, nodes=14, solve=68.643s, root=46.906s, exact=15.987s/79, heuristic=22.404s/198, pool=29777`；本轮 time-indexed graph 为 `solve=274.001s, nodes=17, exact=4.376s/87, heuristic=192.498s/339, pool=36085`。

由此看，当前瓶颈不是 DAG exact pricing，而是 graph pricing 返回 pseudo-schedule 后导致 RMP/启发式循环次数和启发式补列时间明显增加，同时 time-indexed arc fixing 在若干节点也比 exact pricing 本身更重。no-cut time-indexed DAG 定价在单次 exact pricing 上很快，root bound 与最终最优值也合理；但在当前 `>=` master、启发式 pricing 仍优先运行、且 pseudo-schedule 列进入 RMP 的组合下，它没有超过现有 normal ng-DSSR。

后续若继续推进，应重点看两点：第一，是否限制或重排启发式 pricing 调用频率，避免 exact graph 很快但启发式占大头；第二，是否把论文式分支和 time-indexed fixing 做得更完整，以减少 pseudo-schedule 列带来的主问题迭代波动。

## 5. 关闭现有启发式 pricing 的尝试

随后按同一 40-2 zero setup 配置，只把 `enableHeuristicPricing=false`，保留 ALNS seed、RMIH、time-indexed graph exact pricing 和 time-indexed arc fixing，测试“只用精确定价”的效果。运行中日志仍会打印 `HeuristicPricing.start`，但该引擎在配置关闭时立即返回空列，实际补列来自 `TimeIndexedGraphPricing`。

该尝试没有继续跑到根节点闭合。原因是 root 节点列池快速膨胀：从初始 85 条 seed 列开始，time-indexed exact graph 连续多轮加列，约数分钟后仍停留在 node 1，restricted/pool 已增长到 7 万条以上，超过上一轮完整搜索最终 pool `36085` 的两倍，且根节点还没有进入 RMIH、arc fixing 或 branching。为避免继续消耗时间，手动中止该 run。

这个结果说明，当前“直接关闭现有启发式 pricing”不是有效提速。现有启发式虽然耗时，但它相当于限制了每轮进入 RMP 的候选列形态；直接让 time-indexed exact graph 从 root 开始大量返回 pseudo-schedule 负列，会让 RMP 规模和重解次数膨胀。后续如果要贴近原文，更合理的方向不是完全关闭启发式，而是给 time-indexed graph 自身加原文式两阶段策略：第一阶段每个 bucket 只扩展 reduced cost 最小 label、每轮最多返回约 50 条负列；第二阶段才用完整 exact pricing 证明无负列，并限制每轮返回列数，避免 root 被 pseudo-schedule 列淹没。

## 6. 每轮只加最优列的 exact-only 尝试

继续按同一 40-2 zero setup 配置测试关闭启发式 pricing，但把 `twet.bpc.fullDomainCompare.maxExactColumns=1`，即每次 `TimeIndexedGraphPricing` 只把当前最优 reduced-cost 序列加入 RMP。该设置的目的，是验证上一节 exact-only 多列返回导致 root 列池爆炸的问题能否通过“单列列生成”控制住。

这次运行没有出现 root 列池爆炸：root 能够完成并进入后续分支，列池增长基本是一轮一列的线性增长。运行到约 `380s` 后手动停止，最后完整节点摘要停在 node 30：`total=380.251s, incumbent=17881, bound=17118.953788, gap=4.2618%, queue=19, pool=2015, restricted=272`；随后 node 31 infeasible，node 32 刚开始处理时中断。由于是 Ctrl-C 中断，runner 没有写出最终 CSV，本节数据来自控制台 heartbeat/node summary。

由此可以判断，`maxExactColumns=1` 确实解决了 exact-only 默认多列返回时的列池失控，但代价是 RMP 重解和 pricing 循环次数过多。典型节点中 exact pricing 每次只加 1 列，例如 node 3 有 `pricing=38.292s/448/add223`，node 30 有 `pricing=12.827s/104/add51`。它的列池规模远小于多列 exact-only，也小于启发式开启时的完整 run，但 bound 推进太慢：380s 时 gap 仍超过 4%，明显不如启发式开启的 time-indexed graph run（274.001s 完整收敛），也远慢于当前 normal ng-DSSR zero setup 记录（68.643s 完整收敛）。

当前结论是：关闭启发式后“每轮只加最优列”可以作为诊断用的稳定版本，但不适合作为默认求解策略。它验证了问题不是 graph exact pricing 单次太慢，而是列返回策略和 RMP 循环之间需要平衡。若继续推进论文式 time-indexed pricing，更合理的方向是做原文那类两阶段/限量候选策略，例如先限制每个 bucket 或每轮返回一批质量较好的列，再用 exact 证明无负列，而不是只取 1 条或无控制地取大量 pseudo-schedule 列。
