# 2026-06-14 branching 策略与 adjacency 问题记录

## 1. 当前 adjacency 分支的判断

当前 `UndirectedAdjacencyBrancher` 的右支是 `required adjacency(i,j)`，主问题中表达为“至少一条选中列包含 `i->j` 或 `j->i`”。这个分支不是直接限制 pricing 图上的一条弧，而是一个无向相邻关系的聚合行。其 dual 会同时作用到两个有向弧上，但 completion bound、label dominance 和半域拼接没有显式记录“该 adjacency 是否已经满足”的状态。因此在 setup/time window 有方向差异时，该 dual 容易让 relaxed suffix/prefix 下界过低，造成大量 partial label 被保留下来。

在当前全闭包 011/30 对照中，只关闭 undirected adjacency branching 后，严格同口径求解时间从约 `147.682s` 降到 `73.516s`，exact pricing 从 `103.545s/32 calls` 降到 `29.039s/24 calls`。node3 不再出现 required-adjacency 节点中 `fw/bw≈218965/33476` 的 forward 爆炸。当前结论是：adjacency branching 不是完全不可用，但不适合放在默认高优先级位置；后续应先作为低优先级或实验开关继续测试。

## 2. i-j-k 连续片段分支的语义

后续可考虑一种更贴近 pricing 结构的 path-fragment / follow-on 类分支。候选对象为三元组 `i-j-k`，表示某条列中连续出现两条弧 `i->j` 和 `j->k`。分支可以理解为：

1. 左支：禁止连续片段 `i->j->k`。实现上不需要禁止单条弧 `i->j` 或 `j->k`，只需要在扩展时判断当前 label 的最后一条弧是否为 `i->j`；如果是，则下一步不允许扩展到 `k`。
2. 右支：要求该连续片段至少出现一次。主问题中可加一行 `sum_r a_{ijk,r} lambda_r >= 1`，其中 `a_{ijk,r}=1` 表示列 `r` 含连续片段 `i,j,k`。pricing 可先不硬编码该 required 关系，只通过该行 dual 影响含片段的列 reduced cost；最终可行性由 RMP 分支行保证。

这个思路和 follow-on branching 接近。follow-on branching 在 routing/scheduling 中常见，基本含义是：若路径进入某个节点，则下一步必须/禁止去某个后继。它的优点是可以通过修改 subproblem 扩展规则高效实现，而不是只在 master 里加一个聚合行。

## 3. 左支是否需要改 ng-memory 或资源不可达集合

禁止 `i->j->k` 的左支只是一条局部转移禁忌，不应该改变 ng-memory 的定义，也不应该进入资源不可达集合。原因是：

1. ng-memory 描述的是 ng-relaxation 下哪些真实 job 因记忆机制暂时不能重复访问；连续片段禁忌不是重复访问限制。
2. 资源不可达集合应该表达在真实时间窗、release/due、处理时间等资源约束下后续永远不可达的 job；`i-j-k` 禁忌只依赖上一条弧和下一步选择，不代表 job `k` 在其他前驱下不可达。
3. 因此它只应作为扩展可行性检查，类似 `isArcForbidden(from,to)`，但多依赖一个上一节点 `prev`。如果把它写进 dominance key 或资源不可达集合，可能错误强化 dominance。

## 4. 右支是否需要修改 pricing

第一版右支可以只加主问题约束，不强行改 pricing 扩展结构。这样做和当前 required arc / required adjacency 类似：列是否满足 required fragment 由 RMP 行控制，pricing 通过 dual 间接偏向生成含该片段的列。区别在于 `i-j-k` 是有向连续片段，比无向 adjacency 更贴合实际 route 结构，dual 不会同时奖励两个方向。

如果后续发现右支 repair 或 pricing 很慢，再考虑给 pricing 加一个小状态 `fragmentSatisfied`，用于 completion bound 或扩展剪枝。但第一版不建议上来就加状态，否则会把所有 bidirectional / ng-DSSR / heuristic pricing 都复杂化。

## 5. 实现时的最小结构草案

如果后续实现，`Node` 可新增一个 `forbiddenArcPair[from][mid][to]` 或稀疏 `Map<pairKey(from,mid), BitSet(to)>`。扩展时需要知道当前 label 的前驱 `prev` 和 terminal `mid=label.jid`，检查 `isArcPairForbidden(prev, mid, next)`。这不影响现有单弧禁忌、pricingOnly arc、ng-memory、resource unreachable 和 LP cover 逻辑。

候选识别可以从当前 LP 解中的连续片段流量开始：统计所有正值列中每个 `i-j-k` 的权重和，选择最接近 0.5 的 fractional fragment。这里应排除 source/sink 相关片段，至少第一版只对三个真实 job 做分支，避免把虚拟端点语义引入连续片段。

## 6. 当前结论

adjacency 分支的问题主要来自“无向聚合关系”和 pricing 状态不匹配；`i-j-k` 连续片段分支更局部、更有向，左支可以直接在扩展端处理，理论上比 required adjacency 更稳。右支仍然可能带来 dual-based bound 偏差，但由于片段是有向且结构更具体，风险应低于无向 adjacency。当前先记录设计，不立即实现；后续建议在 011/30 这类已暴露 adjacency 问题的算例上做 A/B 测试。
