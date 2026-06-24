# 2026-06-24 route enumeration 实现记录

## 问题

本次实现的是节点 LP 完全定价闭合后的 route enumeration。它的作用不是替代 pricing，而是在已经有有限 incumbent 且当前节点 gap 很小时，枚举所有可能改进 incumbent 的机器列，然后解一个有限整数主问题。如果有限主问题被证明最优或不可行，就可以直接关闭当前节点，避免继续做分支。

这里的触发必须放在 true dual 验证之后。dual smoothing 可以参与前面的找列过程，但最终仍要回到 true dual exact pricing 确认没有负列；只有这个时刻的 `LB_node` 和 reduced cost 口径才用于判断 `rc < UB - LB_node`。

## 当前实现

新增 `RouteEnumerationEngine`、`RouteEnumerationResult` 和 `RouteEnumerationFiniteMaster` 三个类。`Tree` 在 `PC.solve()` 完成、RMIH 尝试完成、incumbent bound 剪枝未触发之后调用枚举；枚举完整后再用独立 CPLEX 模型求有限整数主问题。该有限 MIP 不改当前 LP/RMP 状态，未证明时直接回到原来的 arc fixing、subtree 和 branching 流程。

当前实现是安全保守版：

1. 默认关闭，由 `enableRouteEnumeration=false` 控制。
2. 只在 `0 < incumbent - nodeLowerBound < routeEnumerationAbsoluteGapThreshold` 时尝试，默认阈值为 `10.0`。
3. active cut 存在时跳过。也就是说 SRI/full-SRI/lm-SRI/arc-memory SRI 当前都不和 route enumeration 同时使用。
4. 列化外包模式当前跳过，因为严格证明还需要同时枚举外包列族；masterVariables 外包模式可以使用，有限 MIP 会保留原来的 `y_j + tariff segment` 变量。
5. 枚举内部机器列时使用 full-domain 单向 DFS，不使用 Tmid、不使用 dominance、不使用 dynamic profitable window。扩展阶段尊重当前节点的 forbidden arc、pricingOnly forbidden arc 和 required outsourcing job。
6. 只有枚举完整跑空且有限列数不超过 `routeEnumerationColumnLimit` 时，才提交新列到全局 `Pool` 并求有限 MIP。达到上限则返回原 BPC 流程，不关闭节点。

`Pool` 增加了只读签名查询 `getColumnIdBySignature()`，用于区分当前 RMP 已有列、全局池已有但当前 RMP 未激活的列，以及本次枚举新列。新增列来源枚举 `ColumnSource.ROUTE_ENUMERATION`，便于后续统计。

## 有限主问题口径

有限 MIP 使用当前 RMP 的 set-covering 口径，而不是 RMIH 的 exact-cover 修复口径。它包含：

1. 内部列二进制变量。
2. `cover_j >= 1`，内部列按 job visit count 计数，外包变量 `y_j` 系数为 1。
3. 机器数区间 `[node.minMachineCount, node.maxMachineCount]`。
4. 当前节点 arc branch、adjacency branch 和 tariff segment branch。
5. masterVariables 外包下的 piecewise tariff 变量和 baseline 约束。

如果有限 MIP 证明 infeasible，节点关闭；如果证明 optimal，则该节点在完整枚举列集上已闭合。若 optimal 目标优于 incumbent，则更新 incumbent；否则直接关闭节点。若 MIP 超时或没有证明，则不关闭节点。

## 风险与后续

第一版没有接入 completion bound 剪枝，因此只有在小 gap、分支状态已经很强、可枚举状态有限时才可能有效。这个选择是为了先保证证明链路清楚，不把半域 bound、SRI 状态或列化外包一起混入。

后续如果要提高可用性，优先考虑两件事：一是把 safe completion bound 加入枚举 DFS 的前缀剪枝；二是为 columnized outsourcing 同时枚举外包列族并把两类 pricing 证书一起用于有限 MIP。SRI 场景暂不建议接入，除非先明确 cut 状态如何进入枚举 reduced cost 和有限主问题证明。
