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
## 四组合正确性复核

本次按 no-cut/SRI cut 与 ng-DSSR/time-indexed 四种组合重新检查。四条路径共用同一套 LP Phase-I 语义：合法内部列、合法外包列和直接建模外包变量的目标系数为 0，artificial slack 与 branch-implied 竞争列为 1；当前 node 的分支行、已有 cut 行、硬时间窗和 pricing-only 禁弧仍作为约束保留。只有 slack 与正值竞争列同时归零，才关闭 Phase-I、删除竞争列并重建真实成本 RMP。若残差仍为正，只有内部列族 exact 证书非负，且列化外包时外包列族证书也在同一未重解 dual 下非负，才能证明 child infeasible。

ng-DSSR no-cut 的 exact DSSR 返回 elementary 内部列族证书；ng-DSSR+SRI 使用 partial-list SRI 状态和 active cut dual，最终候选还由 `LP.computeReducedCost` 按完整 SRI 系数复核。普通 time-indexed 的完整前向图搜索返回 no-cut 证书；rank-1 time-indexed 在存在 active SRI 时由 exact bucket labeling 返回证书，图内 heuristic 本身不冒充闭合证明。两条 time-indexed 路径在 Phase-I 中关闭 dual profitable window，候选写入 Pool 前仍保存 evaluator 的真实目标成本。

新增 `StrongBranchingPhaseOnePricingTest`，分别验证四种组合。no-cut 两组必须找到 Phase-I 负列且列成本等于 evaluator 真实成本；SRI 两组注入足够强的负 cut dual，要求 exact pricing 不返回受 cut 排除的负列并给出非负完整证书。`StrongBranchingPhaseOnePricingTest passed`，既有 `TimeIndexedGraphOptimizationTest passed`。当前未发现有无 cut、ng-DSSR/time-indexed 之间的正确性分叉；尚未覆盖的是完整算例下四组合 repair 的性能差异，不影响本次语义结论。
