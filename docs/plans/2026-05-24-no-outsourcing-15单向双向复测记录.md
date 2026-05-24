# 2026-05-24 no-outsourcing 15任务单向/双向 BPC 复测记录

这次复测的目标有两个。第一，重新在当前代码上比较 no-outsourcing 设定下单向 exact pricing 和双向 exact pricing 的完整 BPC root 耗时。第二，避免工程阈值和随机 seed 干扰，让结果至少能回答“最终最优解是否一致、exact labeling 本身谁更快、完整 root 时间谁更快”。

## 1. 测试入口和设置

这次新增了 `src/HEU/TanakaNoOutsourcingBPCComparisonBatch.java`。它读取 `test-results/bpc/2026-05-23-no-outsourcing-15/` 下已有的 10 个 15 任务子算例，仍然沿用 no-outsourcing 读法，也就是把 `outsourcingCost[j]` 设为 `Utility.big_M`，不允许外包。正式求解逻辑没有改，只是新加了一个本地诊断入口。

为了避免这类 full BPC 对比被工程阈值先截断，runner 里把本次诊断用的几个上限临时放大了：`maxHeuristicPricingColumns=100000`、`heuristicPricingPoolSize=100000`、`maxExactPricingColumns=100000`、`branchSeedColumnLimit=20000`、`maxNodes=20000`。这批 15 任务算例最后都远没碰到这些上限。

这次还额外修正了一个公平性问题。`HeuristicSeedProvider` 构造 root seed 时会走 `Solution.setInitSolution()`，而这里面会用到 `Utility.rng`。如果 single 和 bidirectional 两次运行各自沿用当前进程里的随机状态，那么同一 case 的初始列、初始 incumbent 可能不同，完整总时间就会混入 seed 噪声。因此 batch runner 在每个 case 开始前都按 case 名重置同一个随机种子，让 single 和 bidirectional 共享同一份 root seed 起点。复测后同一 case 的 `initial_incumbent`、heuristic 调用次数、pricing 轮数和列池规模都已经对齐。

结果文件输出到：

1. CSV：`test-results/bpc/2026-05-24-no-outsourcing-15-bidir-vs-single-rerun.csv`
2. 逐算例日志目录：`test-results/bpc/2026-05-24-no-outsourcing-15-bidir-vs-single-rerun/`

## 2. 结果概览

10 个算例全部在 root 闭合，single 和 bidirectional 的最终 incumbent 与 final bound 全部一致，没有出现目标值差异，也没有出现 bound 不一致。所有 run 的 `status` 都是 `ROOT_PROCESSED`，并且 `initial_is_optimal=false`，也就是这批例子里没有哪一个是一开始的 seed 解就已经最优。

平均完整 root 时间方面，single 为 `0.255 s`，bidirectional 为 `0.230 s`，双向约快 `10.1%`。如果只看 exact pricing 时间，single 平均 `0.051 s`，bidirectional 平均 `0.048 s`，双向约快 `6.9%`。如果把 heuristic pricing 单独拎出来看，single 平均 `0.180 s`，bidirectional 平均 `0.166 s`。这说明这批 full BPC 里，主要耗时仍然在 heuristic pricing，exact 只是最后兜底的一小部分；但在 current code 下，双向已经不是之前那种“完整 root 时间没有体现收益”的状态了。

按 case 看，双向在 10 个算例里有 7 个完整总时间更快，8 个算例的 exact 时间更快。波动不是完全单边，但平均值已经稳定偏向双向。

## 3. 关于“达到最优解的时间”

这次 CSV 里加了 `time_to_optimum_s` 和 `time_to_optimum_basis`。这里要说明一下，这不是严格的“首次 incumbent 达到最终最优值的精确时间戳”，因为当前 `BPCTraceSummary` 只记录了 incumbent update 次数，没有给每次 update 单独打时间。

对这批结果来说，所有 run 都是 root 闭合，所以这里采用的近似定义是：

1. 如果 `initial_incumbent == final incumbent`，则记为 `0`；
2. 否则，因为所有例子都在 root 结束时闭合，就把 `rootSolveTimeSeconds` 作为“达到最优解时间”的近似值，`time_to_optimum_basis=root_close`。

因此这列当前更准确的理解是“达到最终 root 最优闭合所用时间”。在这批 15 任务 no-outsourcing 算例上，这个近似是够用的，因为没有分支，也没有后续节点上的 incumbent 更新。

## 4. 阈值是否影响了结论

这次专门检查了是否存在阈值截断。新的 CSV 里最大列池规模只有九百多，远低于本次临时放大的 `100000` 上限；日志里也没有出现 `column cap reached`。因此这次对比没有被 heuristic/exact 返回列上限提前截断。

不过有一个需要保留的现象：大多数算例里 exact pricing 只调用一次，并且只是返回 0 条列做无负列证明；但 `wet015_007_2m` 里 single 和 bidirectional 都出现了“exact 先补 1 条负列，再跑一轮 exact 证明无负列”的情况。所以这一批 full BPC 里，exact 不是永远纯兜底零列证明，只是大多数情况下如此。

## 5. 当前结论

当前代码下，这 10 个 15 任务 no-outsourcing 子算例上，single 和 bidirectional 的最优目标与 final bound 完全一致；在同一 root seed 起点、放宽列池阈值后，双向在完整 root 时间和 exact 时间上都略优于单向，但优势幅度不大。更直接地说，这批例子的主耗时仍然不是 exact labeling，而是 heuristic pricing 多轮加列和 LP 重解；双向当前的收益已经能在完整 root 时间里体现出来，但还没有大到能主导整体时间。

## 6. 2026-05-24：补跑 20 / 21 任务更大规模

考虑到 15 任务 still 偏小，这次继续复用同一个 batch runner，单独挑了 `tmp-wet020_001_2m.dat` 和 `tmp-wet021_001_2m.dat` 两个更大 no-outsourcing 子算例做 full BPC 对比。为保持可比性，仍然沿用同一套设置：不允许外包、临时放大 heuristic/exact 列池阈值、并在每个 case 开始前按 case 名重置同一个随机 seed，让 single 和 bidirectional 共用 root seed 起点。结果 CSV 为 `test-results/bpc/2026-05-24-no-outsourcing-20-21-bidir-vs-single-rerun.csv`。

这两个更大算例都在 root 闭合，single 和 bidirectional 的最终 incumbent、final bound 继续完全一致。`wet020_001_2m` 上，single 总时间 `10.467s`、exact 时间 `8.905s`；bidirectional 总时间 `9.311s`、exact 时间 `8.519s`。`wet021_001_2m` 上，single 总时间 `15.954s`、exact 时间 `15.203s`；bidirectional 总时间 `14.922s`、exact 时间 `14.125s`。这里和 15 任务相比，信号已经更清楚：一旦规模上来，完整 root 总时间的主要部分几乎都被 exact pricing 吃掉，双向在这两个例子上都稳定快于单向，且节省主要就来自 exact pricing 本身，而不是 LP 重解。

这批 20/21 任务还有一个和 15 任务不同的现象：`wet020_001_2m` 里 single 和 bidirectional 都出现了 2 次 exact pricing 调用，说明 exact 不是单纯最后一次“零列证明”，而是先补了一轮负列，再做最终无负列证明。`wet021_001_2m` 则仍然只有 1 次 exact 调用，但 exact 时间已经占到总时间的 95% 左右。由此当前判断更明确：在更大的 no-outsourcing full BPC root 上，瓶颈已经非常集中到 exact pricing，双向的收益也开始更稳定地体现出来。
