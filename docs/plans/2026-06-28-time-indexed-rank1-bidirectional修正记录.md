# time-indexed rank-1 cut pricing 双向修正记录

本次修改的目标是把此前单向实验版 `TimeIndexedGraphRank1CutPricingEngine` 直接改成更贴近论文 `On the exact solution of a large class of parallel machine scheduling` 的做法，不再新增另一个 engine。核心变化包括双向 bucket labeling、论文 Algorithm 2 的 limited-memory 扩展、一行和三行 rank-1 cut 分离、graph-native bucket heuristic，以及 pseudo-schedule 下按访问次数计算 rank-1/full-SRI 系数。

## 1. pricing 流程

当没有 active subset-row pricing cut 时，`TimeIndexedGraphRank1CutPricingEngine` 仍委托原 no-cut `TimeIndexedGraphPricingEngine`，保持无 cut 路径不变。当存在 active rank-1 cut 时，engine 先运行 graph-native bucket heuristic：同一个 `(job,time)` bucket 只保留 reduced cost 最小的 label；如果没有新列，再运行 exact bucket labeling。这样 time-indexed rank1 模式不再依赖项目原有 `HeuristicPricingEngine`，`TWETBPCContext` 在该模式下也不再把旧启发式 engine 放到 exact engine 前面。

双向 labeling 的口径为：forward 从 `(0,0)` 扩展到 `t*` 之前，backward 从所有可结束的 `(job,t)` 反向扩展到 `t*` 之后，然后在同一 `(job,t)` bucket 上拼接。拼接时如果两侧 residual state 之和达到 cut 的阈值，就补一次 cut dual penalty。forward/backward 扩展和 dominance 都按论文 Algorithm 3-6 的 reduced-cost state 口径处理。

## 2. cut 生成和 memory

time-indexed rank1 模式下，`SubsetRowCutGenerator` 同时分离一行和三行 multiplier 为 `1/2` 的 rank-1 cuts。每轮最多加入 50 个一行 cut 和 75 个三行 cut；旧 partial-ng 三元 subset-row 路径仍保留原来的每轮配置上限。

limited-memory arc set 改成论文 Algorithm 2 口径：先从当前 LP 正值列构造 `Mmin`，再加入反向 pair，并加入所有正 multiplier job pair。若 cut pool 里已经有同一 multiplier 向量和 RHS 的 rank-1 cut，新 cut 不再作为重复行加入，而是合并到已有 cut 的 memory 中。

`SubsetRowCutEvaluator` 的 full coefficient 从旧的 distinct-visit 改为按 pseudo-schedule 中的访问次数累加。因此 repeated job 在 time-indexed path 中会继续贡献 rank-1 coefficient，更接近论文 DWM 口径。

## 3. cut 管理

`PC` 在 time-indexed rank1 cut 模式下增加论文式 cut 管理。每轮 column generation 收敛后，会删除 dual 为 0 的 inactive subset-row cut；随后继续求解当前 RMP。若有 incumbent，则按当前绝对 primal-dual gap 计算最近两轮 cut separation 的 gap 降幅；若连续两轮降幅都低于 2%，停止继续 cut separation，进入后续分支流程。该逻辑只在 `useTimeIndexedGraphPricing && useTimeIndexedGraphRank1CutPricing && enableSubsetRowCutsForTimeIndexedGraph` 同时开启时生效，不影响当前 ng-DSSR 主线。

## 4. 验证

focused `javac` 已覆盖以下文件并通过：

`TimeIndexedGraphRank1CutPricingEngine`、`SubsetRowCutGenerator`、`SubsetRowCutEvaluator`、`TWETCut`、`CutPool`、`LP`、`PC`、`TWETBPCContext`、`GCBBFullDomainComparisonTest`。

短 smoke 使用 `data/40-2/wet040_001_2m.dat`，配置为 `timeIndexedGraphPricing=true`、`timeIndexedGraphRank1CutPricing=true`、`enableSubsetRowCutsForTimeIndexedGraph=true`、`subsetRowCutMemoryMode=arcMemory`、`maxNodes=1`、`maxCutRounds=1`、关闭旧启发式 pricing。结果目录为 `test-results/bpc/tmp-timegraph-rank1-bidir-smoke-20260628`，结果为：

`NODE_LIMIT, incumbent=22582, bound=22501.600000, solve=55.393s, exact=26.134s/239 calls, valid=true`。

日志确认新增逻辑实际生效：cut separation 加入 79 条 rank-1 cut，后续 pricing 信息为 `Time-indexed rank-1 cut heuristic/exact bidirectional pricing`，并且 summary 中 `heuristic_s=0`，说明没有再走旧的 `HeuristicPricingEngine`。

## 5. 仍需注意

论文 DWM 的列严格是 time-indexed path，而当前项目全局 `Pool` 仍按 `SequenceSignature` 管理 `TWETColumn`。本轮没有把主问题列对象重构为 path-time identity。同一轮 pricing 内，同一 sequence 会保留 reduced cost 更低的候选；但若全局 pool 已有同一 sequence，仍按现有 sequence 口径去重。这是当前 time-indexed 对照器的工程边界。若后续要完全复现论文 DWM，需要把 time-indexed path identity 也纳入列对象、pool 去重和 master 建模。

## 6. 复查结论

2026-06-28 复查当前实现后，确认新逻辑已经真正接入双向 bucket pricing：active rank-1 cut 下先运行 graph-native bucket heuristic，再运行 exact bidirectional pricing；cut separation 会产生一行和三行 rank-1 cuts；limited-memory arc set 已补反向 pair 和所有正 multiplier job pair；inactive cut 删除和 tailing-off 只在 time-indexed rank1 模式下触发。40-2 root-only smoke 重新验证通过，日志显示 `SubsetRowCutGenerator` 加入 79 条 rank-1 cuts，后续 pricing 均为 `Time-indexed rank-1 cut heuristic/exact bidirectional pricing`。

当前仍不能称为和论文 DWM 完全一致，原因是项目全局 `Pool` 仍用 `SequenceSignature` 去重，`TWETColumn` 也只保存 job sequence 与成本，不保存 time-indexed path identity。因此同一 sequence 的不同完成时间路径不能作为不同 DWM 列长期共存。这是当前 time-indexed 对照器的结构性边界，不是双向 labeling 或 cut-state 的局部 bug。普通 ng-DSSR、partial dominance 和非 time-indexed pricing 的入口未被改动；本次新增的 cut loop 管理也有 `useTimeIndexedGraphPricing && useTimeIndexedGraphRank1CutPricing && enableSubsetRowCutsForTimeIndexedGraph` 保护。

## 7. 同 sequence 低成本版本保留

继续复查后确认，当前主问题仍按 `SequenceSignature` 管理列时，必须保证同一 job sequence 只保留已知最低成本版本。time-indexed graph 中同一序列可能在不同完成时间上被恢复出来，若旧版本先进入全局 pool，后续更低成本版本不能因为 signature 已存在而被跳过，否则会使当前 RMP 使用偏高的列成本，尤其影响 no-cut/time-indexed rank1 对照。

本次修正把该语义放在 `Pool/LP/PC` 层闭环处理：`Pool` 在同 signature 新成本更低时原地替换列对象并保留原 id；如果该列已经在当前 CPLEX 模型中，`LP` 同步更新对应变量的 objective coefficient；`PC` 将这类 active column cost improvement 视为本轮有效变化，触发后续 RMP 重解。两个 time-indexed pricing engine 不再预先跳过 active signature，而是把候选交给 `Pool` 判断是否能改进成本。

该修正没有把主问题改成 path-time identity，也不会让同一 sequence 的多个 time-indexed path 共存；它只是保证在当前 sequence-column 模型下不会保留较差成本版本。普通 ng-DSSR、partial dominance 和其他非 time-indexed pricing 若偶然返回同 signature 更低成本列，也会受益于同一条全局池语义，但不会改变它们的扩展、占优、completion bound 或 join 逻辑。验证上，focused `javac` 已通过；40-2 root-only smoke 使用 `timeIndexedGraphPricing=true`、`timeIndexedGraphRank1CutPricing=true`、`enableSubsetRowCutsForTimeIndexedGraph=true`，结果为 `NODE_LIMIT, valid=true`，目录 `test-results/bpc/tmp-timegraph-rank1-bidir-recheck-20260628`。

二次复查时进一步补齐了 `PC` 中所有使用 `GeneratedColumnIds` 的加列入口。此前普通 stabilized pricing pass 已经把 active 列成本改进计入 `addedColumns`，但 true-dual 普通 pricing、repair 和 strong-branching phase2 仍只统计新增 column id；若这些路径只发生“当前 active 列成本降低”，模型目标系数已经更新但可能不会立即重解。现已统一四个入口的口径：`addedColumns = improvedActiveInternalColumns + 新增内部列 + 新增外包列`。该修正不改变候选列生成，只保证目标系数更新后控制流会继续求解。验证：focused `javac` 通过；40-2 root-only smoke `tmp-timegraph-rank1-bidir-recheck2-20260628` 仍为 `NODE_LIMIT, valid=true`。

再次复查时同步把 trace 口径改为统计 active 列成本改进，避免日志显示“0 added”但实际已经更新了当前 RMP 的目标系数。该调整只影响日志显示，不改变求解状态。验证：40-2 root-only smoke `tmp-timegraph-rank1-bidir-recheck3-20260628` 仍为 `NODE_LIMIT, valid=true`。

## 8. time-indexed 实验线不再混用原启发式 pricing

2026-06-28 进一步对齐实验口径：只要启用 `useTimeIndexedGraphPricing`，`TWETBPCContext` 就不再把原项目的 `HeuristicPricingEngine` 加入 pricing engine 队列。no-cut time-indexed 由 `TimeIndexedGraphPricingEngine` 自己完成 column generation；rank1 cut time-indexed 由 `TimeIndexedGraphRank1CutPricingEngine` 内部先跑 graph-native bucket heuristic，再在需要时跑 exact bucket labeling。

强分支也按同一口径处理：time-indexed 模式下 two-stage strong branching 只使用 phase1 的 RMP/repair bound 评分，不再进入原机器序列启发式 pricing 的 phase2。这样 no-cut/cut time-indexed 对照不再混入当前主线的序列局部搜索启发式，后续和论文 DWM 口径对比更清楚。验证：focused `javac` 通过；40-2 no-cut time-indexed root-only smoke `tmp-timegraph-noheur-engine-check-20260628` 日志中 pricing 行均为 `TimeIndexedGraphPricing`，未出现 `HeuristicPricing`。

2026-06-28 进一步修正 strong branching 口径：no-cut time-indexed 仍只做 phase1，因为此时没有论文 cut-state 下的 graph-native heuristic；但 time-indexed rank1 cut 模式下，phase2 仍应保留，并使用 `TimeIndexedGraphRank1CutPricingEngine` 内部的 bucket heuristic 做 trial，而不是退化为 phase1，也不是混入原 `HeuristicPricingEngine`。实现上 `Tree` 只在 `useTimeIndexedGraphPricing && !useTimeIndexedGraphRank1CutPricing` 时跳过 phase2；`PC` 在 phase2 识别 rank1 graph engine 后调用 `priceHeuristicOnly()`，该方法只运行 graph-native bucket heuristic，不 fallback 到 exact labeling。这样 no-cut 和 cut 两种 time-indexed 实验线的强分支语义被分开，且仍不影响 ng-DSSR 主线。

## 9. 40-2 有 setup 的完整求解结果

2026-06-28 使用当前 time-indexed rank1/cut 实验线求解 `data/40-2/wet040_001_2m.dat`，配置为 `useTimeIndexedGraphPricing=true`、`useTimeIndexedGraphRank1CutPricing=true`、`enableSubsetRowCutsForTimeIndexedGraph=true`、`subsetRowCutMemoryMode=arcMemory`、开启 strong branching 和 dual-bound pruning，关闭 route enumeration，30 分钟全局时限。结果目录为 `test-results/bpc/tmp-timegraph-rank1-40-2-setup-full-20260628c`。

本次在 515.890s 内求解到最优：`incumbent=bound=22580`，处理 5 个节点，root time 为 192.804s，root bound 为 22560.226019。总 pricing 轮数 1307，新增列 103847，最终/峰值 pool 为 103727；`TimeIndexedGraphRank1CutPricing` 累计 287.149s / 1291 calls，master LP 累计 169.227s。cut 轮数 94，累计加入 cut 485 条，峰值 cut pool 为 1757。

从过程看，root 前半段 no-cut time-indexed graph pricing 会快速生成大量 pseudo-schedule 列，之后 active rank1 cut 下进入 graph-native heuristic/exact bidirectional pricing。rank1 cut 明显把 root bound 推高，但列池仍会膨胀到 10 万级。该结果说明当前实现对 40-2 有 setup 算例并没有出现“root 第一次迭代就爆炸到不可接受”的情况，反而可在 9 分钟内完整闭合；但它仍显著依赖大量 pseudo-schedule 列和多轮 cut/pricing，不能直接推出 60-2 上也会接近论文表格速度。

## 10. exact rank1 pricing 的 SRI-aware completion 剪枝

2026-07-04 对 active rank1 cut 下的 exact bucket pricing 增加了类似 completion bound 的正反向剪枝。原流程是先生成 forward labels，再生成 backward labels，最后统一 join；这样 forward 跨过 `t*` 的 label 即使后续不可能和任何 suffix 或 sink arc 形成负 reduced-cost route，也会先入桶，之后再在 join/end 阶段被发现无用。

当前 exact 模式改为先构造 backward suffix labels，再构造 forward labels。forward 扩展产生 `time >= t*` 的 child label 时，立即用同一 `(job,time)` bucket 上的 backward suffix labels 计算最小可完成 reduced cost，并同时考虑 rank1 residual 的 `joinShift`；如果该 child 与 sink 或任何 suffix 拼接后都不可能低于 0，就不再插入 forward bucket。等待弧跨到 `t*` 时也使用同一逻辑。该剪枝只在 exact 模式启用，graph-native heuristic 仍保持原顺序，因为 heuristic bucket 每个状态只保留一个 label，不能作为完整 suffix 证书。

这个改动不改变候选列成本、cut residual 更新或最终 join 口径，只减少 exact pricing 中确定无用的跨中点 forward labels。验证上，`TimeIndexedGraphRank1CutPricingEngine.java` focused `javac` 通过；`data/40-2/wet040_001_2m.dat` 的 `maxNodes=1` rank1 smoke 正常到 `NODE_LIMIT` 且 `valid=true`。日志中 active cut 后的 exact pricing 出现 `cbPruned` 统计，典型值约为 `9.8e4` 到 `1.01e5`，例如最终无负列轮为 `cbPruned=101011`。该 smoke 说明剪枝路径已触发并保持正确性口径，但不能单独证明总时间一定下降，后续仍需用同配置 A/B 比较。

## 11. 40-2 完整求解复验

2026-07-04 在 `data/40-2/wet040_001_2m.dat` 上使用 time-indexed rank1 cut、strong branching、dual-bound pruning、关闭 ALNS seed 和 route enumeration，重新求解到最优。结果目录为 `test-results/bpc/tmp-rank1-cbprune-full-20260704-rerun2`，最终 `FINISHED, obj=bound=22580, valid=true, solve=207.994s`，处理 7 个节点。root 用时 116.343s，root bound 为 22523.619048，root 后 RMIH 找到 incumbent 22582；node 2 将 incumbent 改进到 22581，node 7 最终得到 22580。

本次 full run 没有打开 `timeIndexedCompletionBoundCutLoopArcFixing`，因此 cut 迭代之间没有执行 pricing-only arc fixing/window tightening。日志中出现的 `cbPruned` 来自 rank1 exact pricing 内部的 SRI-aware completion 剪枝；node 1 闭合后的 `timeIndexedArcFixing.done` 是节点结束阶段的 paper time-indexed reduced-cost arc fixing，用于后续分支和子树，不是 cut-loop 中间迭代 fixing。

当前 arc fixing 需要区分三层语义。第一层是 pricing 内部剪枝，例如 rank1 exact pricing 的 `cbPruned`，它只减少本轮 label 入桶，不写 node 状态。第二层是 cut-loop pricing-only fixing，由 `timeIndexedCompletionBoundCutLoopArcFixing` 控制，在每次 pricing 收敛后、下一次 cut separation 或 inactive cut 处理之间触发；该路径不删除当前 RMP 旧列，只把固定结果作为 pricing-only 状态给同一 node 后续 cut/pricing 和子节点使用。active SRI cut 存在时，默认 `timeIndexedCompletionBoundAllowNoSriWithActiveCuts=true` 且 `timeIndexedCompletionBoundSriAwareArcFixing=false`，因此使用 no-SRI 松弛版 fixing，而不是昂贵的 SRI-aware helper。第三层是 node-close fixing，由 `timeIndexedCompletionBoundArcFixing` 控制，在节点 LP/cut/pricing 都结束、确认需要继续分支前执行，用于加强后续分支和子树。
