# Time-indexed strong branching Phase-I repair 记录

## 当前结论

此前 60-2 等对照已经表明，ng-DSSR 的 strong-branch repair 使用纯 Phase-I 目标时，明显比旧 M 目标更容易先找到可行列组合。Phase-I 的目标不是在修复阶段同时优化真实调度成本，而是先把 artificial slack 和 branch-implied 竞争列全部压到 0；达到 0 后再恢复所有列的真实成本，重建并求解真实 trial RMP。该结论保留为当前 strong-repair 的主要实验口径。

纯 time-indexed 主线此前虽然可以进入通用 `repairStrongBranchingPhaseOne()`，但 time-indexed pricing engine 没有声明 Phase-I 支持，图上仍使用真实 setup cost 和任务惩罚，因此实际上不能完成纯 Phase-I 闭合。本次补齐普通 time-indexed 和 rank-1/SRI time-indexed 两条精确定价入口。

## 实现口径

Phase-I 打开时，time-indexed 图保留处理时间、硬时间窗、当前 node 的显式/隐含禁弧、pricing-only 时空禁弧、job/machine/arc dual，以及 rank-1 路径中的 SRI dual；只把真实 setup cost 和任务惩罚成本置为 0。dual profitable window 在 Phase-I 中关闭，避免把真实目标下的盈利窗口套到零目标定价上。

图搜索得到的候选列在写入 Pool 前仍由 `TWETColumnEvaluator` 计算并保存真实目标成本。Phase-I RMP 通过 LP 的目标模式把合法真实列系数视为 0、artificial slack 和 branch-implied 竞争列系数视为 1；关闭 Phase-I 后无需修改 Pool 中的列成本，可以直接重建真实目标 RMP。普通 no-cut time-indexed 在完整图搜索无负列时返回内部列族闭合证书；rank-1 exact 原有证书口径继续保留。

纯 time-indexed 配置没有 `HeuristicPricingEngine`。因此 repair 顺序是直接反复执行 time-indexed exact pricing、加列和重解 Phase-I RMP，直到 Phase-I 目标归零，或内部列族及列化外包列族都完整闭合后证明不可行。rank-1/SRI 模式仍先使用其图内 bucket heuristic；找不到列时再运行 exact bucket labeling 给证书。

## 验证

新增 `TimeIndexedGraphOptimizationTest` 回归项，检查普通和 rank-1 engine 都声明 Phase-I 支持；Phase-I 返回列保存 evaluator 的真实成本；LP 按零真实成本口径计算其 reduced cost；Phase-I 禁用 dual window；no-cut 完整搜索能够返回非负闭合证书。focused `javac` 编译通过，`TimeIndexedGraphOptimizationTest passed`。

当前尚未做 time-indexed strong-branch repair 的完整算例 A/B，因此只能确认接线与成本语义正确，不能先声称它一定比旧 M repair 更快。由于纯 time-indexed 没有额外启发式，实际收益要由出现 repair 的固定 side 或完整求解实验确认。