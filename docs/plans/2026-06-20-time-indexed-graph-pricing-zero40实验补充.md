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
