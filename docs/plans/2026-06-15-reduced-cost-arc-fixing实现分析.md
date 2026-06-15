# 2026-06-15 reduced-cost arc fixing 实现分析

## 1. 文件来源

已将下载目录中的 `arc_fixing_reduced_cost_cn.pdf` 移动到项目内：

`docs/literature/arc_fixing_reduced_cost_cn.pdf`

该文档讨论的是 arc-time indexed branch-and-price / branch-cut-and-price 中的 reduced-cost arc fixing。核心思想是：对每条 arc 计算所有经过该 arc 的完整 source-to-sink path 的最小 reduced cost。如果强制使用该 arc 后，即使采用最乐观补全也无法得到优于当前 incumbent 的整数解，则该 arc 可以固定为 0。

## 2. 文档中的核心公式

文档给出的基础设定是固定 `m` 台机器，每个整数解由 `m` 条 path 构成。记：

`Z_RMP` 为当前 RMP 下界，`UB` 为当前整数上界，`c*` 为全图最短 source-to-sink path 的 reduced cost，`hat_c_a` 为所有包含 arc `a` 的 path 中最小 reduced cost。

在该设定下，若某个整数解使用 arc `a`，则至少一条 path 的 reduced cost 不小于 `hat_c_a`，其余 `m-1` 条 path 的 reduced cost 不小于 `c*`。因此有固定条件：

`Z_RMP + hat_c_a + (m-1)c* > UB - 1`

若目标不是整数，则通常改为：

`Z_RMP + hat_c_a + (m-1)c* >= UB - eps`

`hat_c_a` 可以通过一次 forward shortest path 和一次 backward shortest path 快速计算。对 arc `a=(u,v)`：

`hat_c_a = d_plus(u) + cbar_a + d_minus(v)`

因此在 arc-time DAG 上，整体复杂度约为 `O(|V|+|A|)`。

## 3. 和当前代码已有功能的关系

当前项目已经有相近功能：`src/TWETBPC/GC/CompletionBoundSubtreeArcEliminator.java`。

它不是严格按 PDF 的 `m` 条 path 公式实现，而是使用当前 completion bound 函数估计“任意包含 arc `(i,j)` 的列的 relaxed reduced-cost 下界”，再和当前节点 gap 比较。代码里等价逻辑是：

`forced_arc_column_lower_bound >= incumbent - nodeLowerBound`

则该 arc 对后续子树没有帮助，可以在子节点中 `forbidArc()` 或 `forbidPricingOnlyArc()`。

这和 PDF 的思想一致：都在问“强制使用某条 arc 后，最乐观 reduced-cost 补全是否仍然不够好”。但当前实现更贴合本项目的列生成结构，因为它直接使用 piecewise-linear completion bound，而不是显式 arc-time DAG 的单点距离 `d_plus/d_minus`。

## 4. 为什么 PDF 公式不能原样套到当前 TWET

当前 TWET/BPC 和 PDF 的固定 `m` path 模型有几个差异：

1. 当前 master 允许 machine count 在节点区间 `[minMachineCount,maxMachineCount]` 内变化，不总是恰好选择 `m` 条内部列。
2. 当前 master 还有外包变量和 outsourcing tariff segment，整数解不一定完全由内部机器 path 构成。
3. 当前 RMP 是 set covering 口径，最终上界启发式再修 exact cover；这和固定 `m` 条 path 的 set partitioning 语义不同。
4. 当前 pricing 不是离散 arc-time shortest path，而是带 piecewise-linear 时间函数、completion bound、ng-DSSR、SRI/lm-SRI 等多个变体。

因此 `(m-1)c*` 这一项不能机械使用。若用 `data.m` 或 `node.maxMachineCount` 代替 `m`，在外包和机器数可变时可能把“剩余 path 的最乐观贡献”估错。尤其当剩余决策可以是外包，或者节点下实际使用机器数小于 `m` 时，这个项没有直接语义。

更稳妥的做法是保留当前实现的 gap 判定：只要任意包含该 arc 的真实列 reduced cost 下界已经不小于 `UB-LB`，且当前节点 pricing 已闭合、其他可加入列没有负 reduced cost，则该 arc 可在后续子树删除。这个判断不需要估计剩余 `m-1` 条 path。

## 5. 正确性条件

当前 reduced-cost arc fixing 若要安全，需要满足以下条件：

1. 使用的是当前节点 LP 闭合后的 dual。也就是说，在当前 node 上，正式 pricing 已经确认没有负 reduced-cost elementary column。
2. 用于固定的 `hat_c_ij` 必须是所有包含 arc `(i,j)` 的真实列 reduced cost 的下界。当前 completion bound 是 relaxed 下界，偏低不偏高，因此只会少删，不会误删。
3. 若 active SRI/lm-SRI cut 存在，fixing 中使用的 lower bound 不能把 SRI penalty 算得过高。当前 completion bound 使用 no-SRI 松弛成本，安全但偏弱。
4. 若 arc 已经 required，不能删除。当前 `CompletionBoundSubtreeArcEliminator` 已跳过 `ARC_REQUIRED` 和 `ADJACENCY_REQUIRED` 相关 arc。
5. 如果是 `pricingOnly` fixing，则只影响 pricing 图，不进入 RMP branch row；如果是永久 `forbidArc()`，需要确认该 arc 不可能出现在任何改善 incumbent 的后续整数解中。

这里最关键的是第 1 点。若在 column generation 尚未闭合、仍可能存在其他负 reduced-cost column 时，仅凭单条 forced arc 的 reduced-cost 下界删除 arc 可能不安全。当前 subtree arc elimination 是在节点求解后基于 node bound 和 incumbent 做，语义上更接近安全的子树剪枝。

## 6. 当前实现可以如何优化

第一阶段不建议另写一套 PDF 式 arc-time fixing，而应增强现有 `CompletionBoundSubtreeArcEliminator`：

1. 保持当前 gap 判定，不加入 `(m-1)c*`。
2. 继续复用 `CompletionBoundCalculator.Bounds`，避免重复构造 completion bound。
3. 对 scan 阶段增加更明确的诊断：记录多少 arc 因 domain disjoint 删除，多少因 scalar bound 删除，多少因完整 PWLF bound 删除。当前已有统计，可继续沿用。
4. 若后续引入 `i-j-k` path-fragment 分支，需要在 fixing 扫描时同时跳过 required fragment 相关的必要 arc，避免和分支右支冲突。
5. SRI/lm-SRI active 时，当前 no-SRI bound 安全但弱；如果后续发现 fixing 几乎没有效果，再考虑 SRI-aware completion bound，而不是先改 fixing 公式。

如果想做“当前 pricing 轮内临时 arc fixing”，则需要单独实现成 pricing-local filter：条件应更接近 `forced_arc_reduced_cost >= 0`，只用于当前 dual 下减少扩展，不应写入 `Node.forbidArc()`。这和 subtree arc elimination 是两件事，不能混在一起。

## 7. 实现位置建议

若后续实施，建议只改以下位置：

1. `CompletionBoundSubtreeArcEliminator`：保留为永久/子树级 arc fixing 主入口。
2. `Tree.applySubtreeArcElimination()`：控制 on/off、pricingOnly/permanent 两种应用方式。
3. `BPCTraceSink` 或现有日志输出：增加 fixing 效果诊断，方便和 subtree / pricingOnly 对比。

不建议直接改所有 pricing engine 的扩展逻辑来做永久 fixing。pricing 扩展只应读取 `node.isArcForbidden()` 和 `node.isPricingOnlyArcForbidden()`，保持单一事实来源。

## 8. 当前结论

PDF 中的 reduced-cost arc fixing 思路是正确的，但它的公式针对固定 path 数、arc-time shortest-path 网络。当前 TWET 项目已有的 completion-bound subtree arc elimination 是同一思想在 piecewise-linear pricing 下的保守版本，更适合当前代码结构。

后续最稳的路线不是新增一套独立 arc-time fixing，而是继续完善 `CompletionBoundSubtreeArcEliminator` 的复用、诊断和与 branching/SRI 的兼容。`(m-1)c*` 项暂不建议加入当前主线，除非后续能明确构造出“剩余机器路径/外包决策”的有效 reduced-cost 下界 `R_a`。

## 9. 2026-06-15 当前实现复核与小对照

本轮重新复核后，当前 `c_a` 的计算应保持现在的口径：对强制使用 job arc `(i,j)`，固定弧自身的 reduced cost 为

`c_a = setupCost(i,j) - arcDual(i,j)`。

任务 `j` 的 processing time 进入时间平移 `setupTime(i,j)+p_j`，任务 `j` 的 job dual 已经在 completion bound 的 job reduced penalty 中扣除，所以不能再放进 `c_a`。代码中的函数式判定等价于：

`min_t { F_i(t) + c_a + B_j(t + setupTime(i,j) + p_j) } >= gap`

其中 `F_i` 是从 source 到 `i` 的 relaxed prefix completion bound，`B_j` 是从 `j` 到 sink 的 relaxed suffix completion bound。这个正是“在 arc 两侧用 completion bound 加起来”的实现。为了省时间，代码先做 `min(F_i)+min(B_j)+c_a` 的 scalar 预判；只有 scalar 不能判掉时才做完整 PWLF shift/add/min。

这个机制和普通 completion-bound label pruning 不完全重复。普通 pruning 只在当前 labeling 过程中剪掉一批 label；局部 `completionBoundArcFixing` 会在当前 exact pricing 轮内直接禁止某些 job-job arc；`pricingOnly subtree arc elimination` 会把节点求解后识别出的 arc 写入子节点的 pricing-only 禁弧矩阵，使后续 pricing 和后续 completion bound DP 构图都跳过这些 arc。因此三者共享同一类 lower bound，但作用时点和作用范围不同。

本轮发现一个低风险冗余点：局部 `completionBoundArcFixing` 扫描候选 arc 时原来只跳过永久禁弧，未跳过已经 `pricingOnly` 禁掉的弧。已在 `GCNGBBStyleBidirectional`、`GCNGBBStyleBidirectionalPartialDominance`、`GCNGBBStyleBidirectionalNgDssr` 中同步补上 pricing-only 跳过条件。这个修改只减少重复扫描，不改变可生成列集合。

用三角化 `tmp-wet030_from040_011_2m`、`maxNodes=2`、`ngDSSR nearestK8/top10`、ALNS seed、RMIH 关闭、`completionBound=allCycles`、midpoint probe/reuse 做小对照。四组上下界一致，validator 均为 `true`：

1. baseline：`solve=34.287s`，exact `14.663s/8 calls`。
2. 只开局部 `completionBoundArcFixing`：`solve=23.108s`，exact `12.033s/8 calls`。
3. 只开 `pricingOnly subtree arc elimination`：`solve=22.344s`，exact `9.945s/8 calls`；根节点扫 `870` 条候选、固定 `354` 条 arc，子节点又固定 `6` 条。
4. 两者叠加：`solve=20.692s`，exact `8.916s/8 calls`。

当前结论是：arc fixing 在这个口径下确实有用，不是完全被普通 completion-bound pruning 包含；但局部 fixing 和 pricingOnly subtree fixing 高度相关，叠加收益小于各自单独收益之和。推荐保留两个独立开关：局部 fixing 用于当前轮快速减少扩展，pricingOnly subtree 用于把可靠禁弧传递给子节点。永久 `forbidArc()` 仍不建议默认开，因为它会改变 RMP/filter/dual 路径。
