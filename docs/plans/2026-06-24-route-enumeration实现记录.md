# 2026-06-24 route enumeration 实现记录

## 1. 问题与目标

route enumeration 放在节点已经用 true dual 完整定价闭合之后使用。它不是替代 pricing，而是在当前节点 gap 很小的时候，枚举所有可能让节点优于 incumbent 的列，然后求一个有限整数主问题。如果这个有限主问题被证明最优或不可行，就可以直接关闭当前节点，避免继续分支。

触发口径是 `rc < UB - LB_node`。因此 dual smoothing 可以参与前面的找列过程，但 route enumeration 必须等最终 true-dual exact pricing 已经确认没有负列后再做。SRI/full-SRI/lm-SRI/arc-memory SRI 当前不和 route enumeration 同时使用。

## 2. 当前实现流程

新增 `RouteEnumerationEngine`、`RouteEnumerationResult` 和 `RouteEnumerationFiniteMaster`。`Tree` 在 `PC.solve()` 完成、RMIH 尝试完成、incumbent bound 剪枝未触发之后调用枚举；枚举完整后再用独立 CPLEX 模型求有限整数主问题。该有限 MIP 不改当前 LP/RMP 状态，未证明时直接回到原来的 arc fixing、subtree 和 branching 流程。

当前触发与中止条件为：

1. `enableRouteEnumeration=false` 时跳过，默认关闭。
2. 只在 `0 < incumbent - nodeLowerBound < routeEnumerationAbsoluteGapThreshold` 时尝试，默认阈值为 `10.0`。
3. `useTimeIndexedGraphPricing=true` 时跳过，因为 time-indexed graph pricing 的列族允许重复 job，而当前枚举器只覆盖 elementary 机器序列。
4. active cut 存在时跳过，避免 SRI 状态和有限证明口径混在一起。
5. 当前 RMP 内部列和外包列总数超过 `routeEnumerationColumnLimit` 时中止。
6. 枚举过程中有限列总数超过 `routeEnumerationColumnLimit` 时中止。
7. 弹出的 label 状态数超过 `routeEnumerationStateLimit` 时中止，默认 `1000000`。
8. 有限 MIP 超过 `routeEnumerationMipTimeLimitSeconds` 或未证明时，不关闭节点。

这些中止都是证明失败，不是节点不可行；一旦触发，流程会继续回到原 BPC。

## 3. 枚举逻辑

内部机器列枚举使用 full-domain 单向 FIFO label queue，不使用 Tmid、不使用 dominance、不使用 dynamic profitable window。每个 label 保存当前前缀序列、visited 标记、前缀 reduced-cost 函数 `frontier` 以及其最小值；扩展时模仿现有 forward pricing 的递推方式，执行 `shiftX(setup+process) + job penalty + fixed reduced cost`，再做 `normalize(FORWARD)`。只有当当前序列可以接 sink、且基于 label reduced cost 判断可能进入 `rc < UB-LB` 的有限列集时，才恢复对应列；新列 objective cost 由 label reduced cost 加回当前 true dual 贡献得到，不再重新调用 `TWETColumnEvaluator`。扩展时遵守当前节点的 forbidden arc、pricingOnly forbidden arc 和 required outsourcing job。

completion bound 已接入枚举 label 剪枝。`PC` 在节点闭合时留下的 reusable bounds 会传给 `RouteEnumerationEngine`；每个 child label 生成后，按现有双向 pricing 的 forward completion-bound 剪枝语义，用 `child.frontier + backwardRByJob[child.lastJob]` 判断当前前缀是否仍可能补成 `rc < UB - LB_node` 的完整列。如果 relaxed 下界已经不小于阈值，则剪掉整个 child label，而不是对下一跳弧做 forced-arc 判断。没有可复用 bound 时只是不做这类剪枝。

2026-06-24 复查时确认：这些 reusable bounds 的导出端已经要求 `pricingHorizon == data.CmaxH`、未开 dual profitable window、未排除 zero-dual job，因此不会把半域或临时裁剪窗口下的 bound 用到 full-domain 枚举证明里。此前曾短暂尝试按 `(from,to)` 使用 `forwardF + arc + backwardB` 做扩展弧剪枝，但这属于 arc fixing/forced-arc 公式，不是 pricing label 剪枝语义；当前已改回和双向 pricing 一致的 label-level completion-bound 剪枝。

每条完整内部序列恢复后，直接使用枚举 label 给出的 true-dual reduced cost 判断是否满足 `rc < gap`。若需要写入新列，则把该 reduced cost 加回机器约束 dual、job dual 和路径 arc dual，恢复 `TWETColumn` 的 objective cost；已有 active 列、全局池已有列和本轮重复序列会分别统计，不重复加入。

## 4. 外包处理

masterVariables 外包模式保留现有 `y_j + tariff segment + baseline` 变量。有限 MIP 中 `y_j` 和 tariff segment active 是整数变量，baseline 仍是连续变量，覆盖约束仍保持当前 RMP 的 `>= 1` 口径。

columnized outsourcing 模式也支持 route enumeration。外包列枚举先把 required outsourced job 作为公共常数保存，再只对自由可外包 job 按顺序完整枚举 include / not include 状态；最终候选需要查重或入列时，再把 required/free 两个有序 job 列表合并成完整外包集合。阈值从“只找负列”改为“枚举 `rc < gap` 的外包列”。这里不能使用外包 pricing 里按 baseline/profit 的 Pareto dominance，因为 route enumeration 后续有限主问题需要的是具体覆盖集合；两个外包集合即使成本/dual profit 上有支配关系，只要覆盖 job 不同，就不能删掉其中一个作为证明列集。枚举时遵守 outsourcing required/forbidden 分支；如果中间外包 label 数超过 `routeEnumerationColumnLimit`，本次枚举直接 incomplete，不关闭节点。外包列用 `omega` 二进制变量进入有限 MIP，并保留 `sum omega <= 1` 的外包列族约束。内部列分支约束只作用于机器列，外包列不参与 arc/adjacency 分支行。

## 5. 有限主问题口径

有限 MIP 使用当前 RMP 的 set-covering 口径：

1. 内部列变量为二进制。
2. 覆盖约束为 `cover_j >= 1`，内部列按 job visit count 计数。
3. 机器数量约束为 `[node.minMachineCount, node.maxMachineCount]`。
4. 当前节点 arc branch 和 adjacency branch 只作用于内部列。
5. masterVariables 外包下使用 `y_j + tariff segment + baseline`。
6. columnized outsourcing 下使用外包列变量 `omega` 和 `sum omega <= 1`。

如果有限 MIP 证明 infeasible，当前节点可关闭；如果证明 optimal 且目标不优于 incumbent，当前节点可关闭；如果证明 optimal 且目标更好，则更新 incumbent 后关闭当前节点。

## 6. 当前风险

route enumeration 仍然是小 gap 下的证明增强，不适合大 gap 或弱分支状态。它不处理 SRI active 的场景，也不尝试把枚举列增量加入当前 RMP 后继续列生成。columnized outsourcing 已经接入，但如果外包列族后续加入更复杂的 cut 状态，需要重新确认有限主问题的 cut 系数和 pricing 证书口径。

### 2026-06-25 route enumeration 扩展口径修正

按现有 labeling 的口径修正 route enumeration 的内部枚举状态：`State` 现在保存 full-domain `reachableSet`，root 和 child 生成时只根据 visited、required outsourcing 和硬时间窗/函数定义域判断 job 是否仍可达。arc forbidden、pricingOnly forbidden 和分支禁弧不进入 `reachableSet`，只在真正尝试从当前 lastJob 扩展到 nextJob 时通过 `canUseArc()` 过滤。因此“某条边当前不可扩展”和“对应 job 后续不可达”重新分离，避免把 forbidden arc 误写进可达集合语义。

扩展函数仍沿用 forward pricing 的函数递推：`shiftX(setup+process) + job penalty + fixed reduced cost`，再 `normalize(FORWARD)`；completion bound 仍在 child label 生成后按 `child.frontier + backwardRByJob[child.lastJob]` 做 label-level 剪枝。本次没有修改既有 `GCNGBBStyleBidirectional`、partial dominance、ng-DSSR 等主 pricing 类，只把 route enumeration 这一路调整为和它们的 reachable/canExtend 分层一致。验证：当前 TWET 主线 `javac` 编译通过。

### 2026-06-25 route enumeration 列成本恢复口径修正

按普通 pricing 非 dual-window 路径修正 route enumeration 的列成本处理。枚举只在节点已经由 true-dual exact pricing 闭合后触发，因此 label 中的 reduced cost 已经是当前节点 true dual 口径；新列写入 Pool/RMP 时不再调用 `TWETColumnEvaluator` 重新求序列成本，也不再用 true dual 复核一次 reduced cost，而是直接把 pricing reduced cost 加回机器约束 dual、job dual 和路径 arc dual，恢复 `TWETColumn` 的 objective cost。已有 active/global pool 序列也直接使用枚举过程得到的 reduced cost 判断是否进入有限列集，不再额外 `computeReducedCost()`。该口径和主双向 pricing 在未开启 dual profitable window 时一致；如果以后要让 route enumeration 支持 cut、dual-window 或其他改变列成本口径的机制，需要重新检查这里的成本恢复公式。
