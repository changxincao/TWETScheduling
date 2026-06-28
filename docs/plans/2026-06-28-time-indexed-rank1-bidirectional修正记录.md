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
