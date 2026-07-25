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

当前 arc fixing 需要区分三层语义。第一层是 pricing 内部剪枝，例如 rank1 exact pricing 的 `cbPruned`，它只减少本轮 label 入桶，不写 node 状态。第二层是 cut-loop pricing-only fixing，由 `timeIndexedCompletionBoundCutLoopArcFixing` 控制，在每次 pricing 收敛后、下一次 cut separation 或 inactive cut 处理之间触发；该路径不删除当前 RMP 旧列，只把固定结果作为 pricing-only 状态给同一 node 后续 cut/pricing 和子节点使用。active SRI cut 存在时，默认 `timeIndexedCompletionBoundAllowNoSriWithActiveCuts=true` 且 `timeIndexedCompletionBoundSriAwareArcFixing=false`，因此使用 no-SRI 松弛版 fixing，而不是昂贵的 SRI-aware helper。按当前 SRI cut 对偶符号，忽略 SRI 对偶是一种更弱的 reduced-cost fixing 证书，安全但不如完整 SRI-aware pricing 强。第三层是 node-close fixing，由 `timeIndexedCompletionBoundArcFixing` 控制，在节点 LP/cut/pricing 都结束、确认需要继续分支前执行，用于加强后续分支和子树。

随后将 `timeIndexedCompletionBoundCutLoopArcFixing` 默认改为开启，并在同一算例上重跑 `tmp-rank1-cutloopfix-full-20260704`。结果为 `FINISHED, obj=bound=22580, valid=true, solve=182.925s, nodes=4`，相比未开启 cut-loop fixing 的 `207.994s / 7 nodes` 有改善。详细日志显示 root 内第一次 cut-loop fixing 固定约 `1.59e6` 条时空弧，后续 pricing 的 `timeArcSkips` 上升到几十万，说明 fixing 结果确实被同一 node 后续 pricing 消费。代价是 root master LP 时间上升，root node summary 从 116.343s 增至 127.657s；总体变快主要来自后续节点减少和 exact pricing 时间从 79.952s 降到 43.380s。

## 12. 60-3 W100 当前版本 cut/no-cut 公平对照

2026-07-25 启动当前 class 下的 time-indexed cut/no-cut 并行对照，实例均为 `data/60-3/wet060_001_3m.dat`、`W=100`、7200 秒、单 CPLEX 线程、60 秒 ALNS、`best` 初始历史、strong branching 和 Phase-I repair。两边只切换 `timeIndexedGraphRank1CutPricing` 与 `enableSubsetRowCutsForTimeIndexedGraph`，输出分别为：

`test-results/bpc/exp-60-3-W100-current-ti-nocut-ab-7200s-20260725g`

`test-results/bpc/exp-60-3-W100-current-ti-sri-ab-7200s-20260725g`

旧 no-cut 完整结果使用 `148` 条初始列、初始上界 `2149`；当前 cut 版第一次单独启动得到 `154` 条和 `2128`，因此该 run 已停止，不能与旧结果直接比较。重新并行启动后，两边均为 `154` 条初始列、3 条 incumbent 列、初始上界 `2128`。runner 对同一实例使用固定随机种子，两边 ALNS 和初始列参数完全相同；同时开启 root pool dump，root 完成后将继续核对 column `0..153` 的 sequence，避免只凭列数判断一致。

当前 no-cut 重跑在 `4323.568s` 求得 `obj=bound=2044`，比旧 no-cut 的 `5144.914s` 快 `821.346s`。这不是 root relaxation 变强：两次 root bound 完全相同，均为 `1973.144339`。主要差异是当前初始列/上界变化后，strong trial 的 seed 和评分发生变化；前两个 arc 分支仍相同，从 node 3 开始分支选择分叉。当前 run 处理 636 nodes、414 次 branch、16560 次 lightweight trial，旧 run 分别为 730、470、18800；pricing rounds 由 28063 降至 25198，并且 2044 incumbent 由约 3492.5 秒提前到约 3137.0 秒。由此减少普通 graph pricing 约 479.7 秒、repair pricing 约 106.0 秒、strong-trial LP 约 153.6 秒。单次调用也有约 4%--8% 波动，但不是 821 秒差异的主体。因此该加速属于不同初始列触发的搜索树路径改善，不能当作 no-cut pricing 实现本身获得了同幅度优化。

## 13. 与论文完整流程的再次对照及当前 SRI 慢点

2026-07-25 对照论文 `On the exact solution of a large class of parallel machine scheduling` 和当前 60-3 W100 cut/no-cut 日志。结论是：当前实现与论文的 rank-1 定价核心基本一致，但本次实际运行并没有启用论文用于控制 SRI 定价成本的完整外围流程，因此不能称为完整复现。

一致部分包括：limited-memory cut coefficient 的 residual state 转移；从当前正值列构造最小 memory 后补反向 pair 和正 multiplier job pair；同 multiplier cut 合并 memory；一行和三行、乘子为 `1/2` 的 rank-1 cut；每轮最多加入 50/75 条两类 cut；同 `(job,time)` bucket 的单标签启发式；带 cut-state dominance 的双向 exact labeling 和 residual join 修正；启发式/exact 每次最多返回 50/300 条负列；inactive cut 删除以及连续两轮 gap 改善低于 2% 时停止 separation。当前 `tStar` 也按各 job 可达窗口左右端点的平均值计算。

仍不一致或本次未启用的部分主要有四项。

1. 论文在每次 column generation 收敛后、分离下一轮 cuts 前执行 reduced-cost arc fixing、graph cleanup，并据缩减后的可达图重算 `tStar`。当前已有对应入口，但本次参数明确设置 `timeIndexedCompletionBoundCutLoopArcFixing=false`，节点结束 fixing 也关闭。因此 active-SRI pricing 日志始终为 `timeArcSkips=0`，每次都扫描完整 horizon 图。

2. 论文明确使用 automatic dual price smoothing stabilization。当前代码有 smoothing 实现，但本次 `enableDualStabilization=false`，所以 cut dual 变化后直接在 true dual 上反复重新定价。论文总结中将 stabilization 视为避免大量 CG 迭代的关键组件；本次运行没有使用。

3. 当前 cut 只加入正在求解的 `LP.activeCutIds`，没有同步回 `Node.activeCutIds`。分支器复制的是 node，因此日志中 node 1 到 node 18 每个节点都以 `activeCuts=0` 开始，随后重新分离 cuts；全局 `CutPool` 只负责复用 cut id 和合并 memory。该做法不影响正确性，但子节点初始松弛更弱，并重复执行整套 separation 与 SRI pricing。论文只说明每个节点求解“可能带 additional rank-1 cuts”的 DWM，没有明确展开 cut 继承细节，因此这里不能直接定性为违反论文，但它确实是当前实现与常规 BCP cut 继承流程的工程差异。

4. 论文的 DWM 列是完整 time-indexed path。当前全局 `Pool` 仍按 job sequence 去重，只保留同 sequence 的最低已知成本版本。这是模型存储层的结构差异，但它倾向于减少主问题列数，不是本次 SRI 定价变慢的原因。

当前慢点已经可以从日志直接量化。SRI run 到 node 18 的快照中，rank-1 bucket heuristic 调用 678 次、累计 `1073.304s`、平均 `1.583s`；exact 调用 959 次、累计 `4106.526s`、平均 `4.282s`，两项合计约 `5179.8s`，已经占据几乎全部墙钟时间。典型 exact pricing 有 70--100 个 nonzero-dual active cuts，保留约 43--56 万 labels、拒绝/删除约 1150--1330 万 labels，并扫描约 1170--1380 万 processing arcs；每个 label extension、dominance 和 join 还要遍历 cut residual。即使单标签 heuristic 每个 bucket 最终只留一个 label，它仍需产生并比较这些候选、复制 residual，并扫描完整图，因此仍达到 1--2 秒甚至更高。

作为对照，当前 no-cut run 虽然处理 636 个节点、执行 25198 次图定价，但 no-cut DAG pricing 平均约 `118.7ms`，pricing 总计约 `2992.0s`，最终在 `4323.568s` 结束。SRI 的单次 exact 不是比 no-cut 贵一点，而是大约贵 30--50 倍；cuts 减少的节点数尚不足以抵消该倍数。论文自己也明确指出每个 active rank-1 cut 都增加一个定价资源，cut 过多会使 pricing 很耗时，并把 arc fixing、limited memory 和 dual stabilization共同列为可扩展性的关键。

因此当前优先级不是继续修改 rank-1 residual 公式。首先应做固定实例 A/B：只开启论文式 cut-loop arc fixing，确认 `timeArcSkips`、每次 arc scans、labels 和 pricing 时间是否显著下降；随后单独开启 dual stabilization，观察 pricing 轮数和 wall time。cut 继承应另做语义清晰的实验，比较“子节点继承父节点 active cuts”和“每个节点重新分离”，不能与前两项同时修改。当前运行未证明 SRI 数学实现错误，证明的是本次配置缺少论文依赖的两个主要加速组件，并且当前 cut 生命周期会放大节点间重复工作。

## 14. 论文 SRI 配套技术和 dual stabilization 说明

论文的 SRI 不是单独使用，而是和以下技术组成完整 BCP：limited-memory rank-1 cuts；graph-native 单标签 bucket heuristic 加 exact 双向 labeling；每次 heuristic/exact 最多返回 50/300 条负列；CG 闭合后的 reduced-cost arc fixing、graph cleanup 和 `tStar` 更新；一行/三行 rank-1 cut separation 及每轮 50/75 条上限；inactive zero-dual cuts 立即删除；连续两轮 primal-dual gap 改善低于 2% 时停止 cut separation；automatic dual-price smoothing stabilization；两阶段 strong branching 和 pseudo-cost。

调度论文对 stabilization 只说明采用 Pessoa et al. (2018) 的自动 dual-price smoothing，没有展开公式或参数，也没有报告 stabilization 开/关消融。其作用是不用当前 RMP 的跳动 true dual 直接定价，而是在稳定中心和当前 dual 之间形成平滑的 separation/pricing dual，并动态调整平滑程度；若平滑 dual 产生的列在 true dual 下无效，则属于 mispricing，算法减弱平滑并最终回到 true dual exact pricing闭合。这样不改变最终正确性，主要减少 degeneracy 下 dual 来回跳动造成的 CG 轮数。Pessoa et al. (2018) 的专门论文比较了 automatic smoothing、penalty stabilization 及组合方案；调度论文表 3--6 的有 cut/无 cut 对照则都使用 stabilization，因此不能从这些表单独量化 stabilization 的贡献。

调度论文的有无 SRI 结果明确显示收益来自“更贵的 root 换更小的树”。例如无 setup 的 60-3：有 cuts 时 root time `36.5s`、root gap `0.04%`、平均 `3.0` nodes、总时间 `94.8s`；无 cuts 时为 `10.4s`、`0.27%`、`56.7` nodes、`298.0s`。带 setup 的 60-3 small setup：有 cuts 为 root `688.1s`、`5.2` nodes、总时间 `1338.3s`；无 cuts 为 root `473.8s`、`131.7` nodes、总时间 `1461.0s`。large setup 下两者总时间已经很接近，说明 SRI 并不保证更快。

当前 60-3 W100 SRI run 最终在 7200 秒达到时限，结果为 `TIME_LIMIT, incumbent=2128, bound=2027.072331`。它不是死锁；当前症状是每个 node 从 `activeCuts=0` 开始重复 separation，且 cut-loop arc fixing、node-end arc fixing 和 dual stabilization 均被本次命令显式关闭，使每轮 active-SRI exact pricing 持续扫描完整图。

## 15. 节点 fixing、cut 继承和 tStar 复核

正常默认配置下，正式 node 完成 column generation 和 cut loop、且未被 incumbent 剪枝后，会在分支前执行一次 time-indexed reduced-cost graph fixing；cut loop 每次闭合后还可执行 in-round fixing。前述 60-3 W100 SRI run 的启动参数将 `timeIndexedCompletionBoundArcFixing` 和 `timeIndexedCompletionBoundCutLoopArcFixing` 都设为 `false`，因此该次运行两类 fixing 都没有执行，不能用它评价论文式 fixing 的效果。

复核发现 cut 生命周期确有实现错误：cut loop 只更新 `LP.activeCutIds`，而所有 brancher 都从 `lp.getNode().copy()` 创建 child；父 `Node.activeCutIds` 一直停留在建模前快照，所以 child 和 strong-branch trial 都从空 cut 集开始。现已在任何 branch candidate 创建之前，把正式父 LP 最终仍 active 的 cut id 一次性写回父 Node。`Node.copy()` 原有的深复制随后负责普通 child 和 strong-trial child 的继承；已在 cut loop 中删除的 inactive cut 不会继承，child 后续仍可按自己的 dual 删除失活 cut。focused 编译通过；定向回归覆盖“父 LP 加两个 cut、删除一个、同步、复制 child”，确认 child 仅继承保留项且父子列表不共享；已有 Phase-I × ng-DSSR/time-indexed × no-cut/SRI 回归也通过。

当前 rank-1 双向定价中的 `tStar` 按当前 graph window 逐 job 计算：

`tStar = round(sum_j(ceil(windowStart_j) + floor(windowEnd_j)) / (2 * feasibleJobCount))`

并钳制到 `[0, horizon]`。这在公式形式上是各 job 时间区间中点的平均值，但当前 rank-1 engine 的输入不是论文所说的 fixing 后真实剩余顶点集：它只读取 `data.hardWindowStart/End`，允许时再叠加 dual profitable window，没有读取 `Node` 的 compact window，也没有从未被禁用的 `(job,time)` 顶点重新提取最早/最晚时间。论文则明确要求先删弧和孤立顶点，再以剩余 `R_j^k` 的 `tMin/tMax` 计算 `tStar`。因此当前实现的公式形式一致、输入口径偏弱；本次 SRI run 未使用 dual window且未执行 arc fixing，`tStar=1759` 基本只是 `horizon=3519` 的中点。即使后续打开 fixing，当前 `computeTStar()` 也不会自动利用 compact window 重新平衡双向图，这是另一个待单独处理的性能问题。

调度论文没有给 dual stabilization 的开关消融或 CG 迭代数对照。正文只说明使用 Pessoa et al. (2018) 的 automatic dual-price smoothing，结论部分直接声称其显著减少迭代；表 3--6 的有 cut/无 cut 版本都包含 stabilization，不能单独证明该组件的贡献。严格证据来自被引用的 stabilization 专门论文，其中比较了 automatic smoothing、penalty stabilization 及其组合，并讨论参数自动调整。因而当前应把“减少迭代”理解为引用既有方法的实验结论和作者实现经验，而不是这篇调度论文自身完成的独立消融。

当前工程代码还没有实现论文中的 active-SRI + smoothing 组合。`PC.solvePricingLoop()` 只有在 `activeSubsetRowPricingCutIds` 为空时才进入 dual stabilization；一旦存在真正参与 pricing 的 SRI cut，就直接使用 true-dual pricing。因此本次运行不仅在命令行上关闭了 stabilization，即使只打开总开关，active-SRI 轮次仍不会被稳定化。若后续要复现论文完整流程，需要先把 SRI dual、同一 stabilized point 的完整 dual objective 和 reduced-cost certificate 纳入统一 snapshot，而不能只改配置开关。

## 16. fixing 后 tStar 与 child cut 继承复核

2026-07-25 将论文 `tStar` 的输入口径补齐到 fixing 后剩余顶点。paper graph fixing 的 cleanup 收敛后，直接复用最终 forward/backward 可达数组，按 job 提取仍同时可从 source 到达且可到 sink 的最早、最晚时空顶点，并写入 node compact window。只有 cleanup 达到轮数上限且最后一轮仍删弧时才补算一次距离；通常零删除终止轮的距离可直接复用。rank-1 exact 图本身仍按原 hard window 和精确时空禁弧构造，不把 compact hull 当作新的 exact 图定义域；compact window 只用于

`tStar = round(sum_j(tMin_j + tMax_j) / (2 * |J_feasible|))`

的端点输入。因此本次修改只改变双向切分位置，不改变定价列族。

ng-DSSR 新增 `midpointStrategy=windowAverage`，用当前 effective windows 的左右端点平均作为 probe 初始参考；默认仍为原 `default`。40-2、关闭 root preprocessing、相同 seed/配置的 root-only 并行 A/B 中，两组均为 `bound=22490`、14 次 exact。default 为 `solve=19.351s, exact=4.710s`，windowAverage 为 `solve=19.078s, exact=4.656s`。新策略把原始参考从约 1425 改为约 820，但现有 probe 最终大多仍选到约 850，性能差异约 1%，不足以证明更优，因此只保留为实验开关。

cut 继承链再次按完整生命周期验证：正式父 LP 的 cut loop 最终得到 `LP.activeCutIds`，分支前一次性同步到 `Node.activeCutIds`；所有普通 brancher 和 strong-trial brancher 都从该 node 深拷贝 child；child `LP.construct()` 再从 child node 恢复 active cut rows。已在父 LP 中删除的 inactive cut 不会继承，child 后续仍可按自己的 dual 删除失活 cut。回归测试同时覆盖列表不共享和 child RMP 的实际恢复。

本次 60-3 W100 SRI run 仍应明确解释为“完全没有执行 time-indexed arc fixing”：参数同时关闭了 node-end、cut-loop 和 in-round fixing。默认配置并非如此；默认 node-end 和 cut-loop fixing 均开启，只是该次对照命令显式覆盖为 false。

当前 dual stabilization 的适用边界没有变化：只进入非 repair 且没有 active SRI pricing cut 的正式 pricing loop，可覆盖 no-cut time-indexed、no-SRI ng-DSSR、普通 heuristic 和列化外包 pricing；Phase-I/repair 和 active-SRI rank-1 pricing均直接使用 true dual。active cut 仅存在于 cut pool、但其 dual 为零且未进入 active pricing cut 集时，不会阻止 stabilization。

## 17. SRI 强分支、tStar 复核与 no-cut stabilization A/B

2026-07-25 再次沿当前主线检查 time-indexed + SRI 的强分支和 repair。Phase 1 始终先解左右 child 的 trial RMP；初始 RMP 不可行或仍使用 branch-implied penalty 列时进入 Phase-I repair。time-indexed 主线只注册一个图定价器：没有 active SRI dual 时直接运行 no-cut exact shortest-path pricing；存在 active SRI dual 时，rank-1 定价器先运行单标签 bucket heuristic，只有启发式未返回列时才运行带 cut-state 的双向 exact labeling。因此 repair 不是只跑 exact，也没有额外的通用 tabu heuristic。Phase 2 若开启，只调用 rank-1 图内 bucket heuristic，不回退 exact；当前主要实验配置将 `strongBranchingPhase2CandidateLimit=0`，所以实际只使用 Phase 1。

Dual stabilization 的边界符合当前要求。入口要求 `!lp.isFeasibilityRepairMode()` 且 active SRI pricing cut 为空；Phase-I repair 和旧 slack repair 都直接调用 pricing engine，不经过 stabilized pricing loop，strong phase 2 也直接调用图启发式。因此 stabilization 只影响无 active SRI cut 的正式节点求解，不进入 repair。

`tStar` 数据流再次核对如下。paper graph fixing 使用 hard window、继承 compact window、普通/时空 pricing-only 禁弧和当前分支弧构造安全图，不使用 dual profitable window。每轮 cleanup 的新增禁弧立即进入下一轮 forward/backward 可达性；最后按每个 job 同时 source-reachable 和 sink-reachable 的时空顶点提取最早、最晚时间并写回 node。rank-1 exact 图仍保持原 hard graph 定义域，只将该 compact hull 与 graph window 取交后计算端点平均值。历史 fixing 日志中的 cleanup 均在 2--3 轮内达到零删除，当前 8 轮上限没有截断已观察实例；即使极端情形达到上限，代码也会按最终已删弧重新计算距离，影响只可能是 hull 偏宽、`tStar` 较弱，不会删掉合法列。

随后对 `data/50-2/wet050_001_2m.dat` 做同配置并行 A/B，只切换默认 Wentges smoothing，均使用 60 秒 ALNS、no-cut time-indexed、strong branching、Phase-I repair、单 CPLEX 线程。两组均得到 `obj=bound=44383`、15 nodes、`valid=true`。关闭 stabilization 为 `522.518s`，root node `265.158s`、root pricing `16.219s/479`、root pool `113165`；开启后为 `739.443s`，root node `290.962s`、root pricing `36.682s/837`、root pool `118974`。完整日志分解显示，开启后执行 3755 次 stabilized pass，约 928870 条候选在 true-dual 复核时被过滤，随后仍执行 1205 次 true-dual pass；总图定价时间约 `362.685s`，关闭时约 `131.218s`。本例 stabilization 总时间退化约 41.5%，没有减少节点，因此继续保持默认关闭。

本次还修正了实验 CSV 的统计口径。此前稳定化开启时只查询精确键 `TimeIndexedGraphPricing`，导致 `[stabilized.*]` 和 `[true]` 两类正式调用被漏报为 `exact_s=0, exact_calls=0`。现在只额外汇总这两类正式后缀，不把 repair 或 strong-branching 调用混入 exact 统计。
