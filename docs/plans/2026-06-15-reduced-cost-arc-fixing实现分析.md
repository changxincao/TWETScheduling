# 2026-06-15 reduced-cost arc fixing 废弃记录

本文件原来记录下载文档 `arc_fixing_reduced_cost_cn.pdf` 中的 reduced-cost arc fixing 思路。讨论后决定不继续采用这条路线，也不把该文档作为后续实现依据。

核心原因是该方法的原始公式面向固定数量 path 的 arc-time pricing 网络，判定里依赖“强制使用某条 arc 的 path 下界 + 其余 path 的最乐观 reduced cost”。当前 TWET-BPC 不是这个结构：机器数量在节点中是区间，主问题还包含外包变量和 tariff segment，整数解不一定由固定数量内部 path 组成。因此把文档里的 arc fixing 公式直接改写到当前模型中，尤其在存在外包时，容易让“剩余任务/剩余机器/外包决策”的 reduced-cost 下界语义变得不清楚。

当前保留的只是原有 completion-bound pruning、局部 completion-bound arc fixing 诊断/开关，以及 pricingOnly subtree arc elimination 等历史组件。这些组件是否启用仍由配置控制，不再把下载文档中的固定 path 数公式作为增强方向。后续如需继续研究 arc fixing，应重新从当前模型的外包语义、机器数区间和 set-covering/repair 流程出发推导，而不是沿用该文档的固定 `m` path 公式。

## 2026-07-03：使用 observed dual bound 强化 reduced-cost arc fixing 的分析

当前 time-indexed graph fixing 和 ng-DSSR 的 time-indexed scalar helper 都仍使用 `UB - 当前 RMP LP objective` 作为 arc fixing 的 gap。`PC` 里已经维护了 observed dual bound，即在一次 exact pricing 后用当前 dual point 的 certified reduced cost 得到的节点下界，但目前它只用于 dual-bound pruning，没有传入 arc fixing。

原则上可以把这个 observed dual bound 用于 arc fixing：若某次 exact pricing 给出了和当前 dual point、当前列族一致的 reduced-cost 证书，则 `max(LP objective, observed dual bound)` 仍是当前 node 的合法下界。此时 arc fixing 的 gap 可以从 `UB - LP objective` 缩小为 `UB - effectiveLB`，从而更早固定普通弧或时空弧。这个逻辑不要求 node 完全收敛；只要 exact pricing 本身给出了 certified reduced cost，就可以作为 pricing-only arc fixing 使用，影响后续 pricing，不删除当前 RMP 已有列。

安全条件是：dual bound 必须来自 exact pricing 证书，不能来自 heuristic pricing；repair/slack pricing 不能使用；active SRI cut 下当前代码还没有完整 SRI-aware observed-bound 口径，因此不应默认使用；columnized outsourcing 时必须同时有内部机器列和外包列的 certified reduced cost，否则只证明了一个列族。ng-DSSR 若使用 relaxed rc_min 形成 observed bound，得到的是更保守的下界，安全但可能偏弱。

后续若实现，推荐只做局部小改：在 post-node 或 cut-loop pricing-only arc fixing 前取 `effectiveLB = max(lpObj, pc.lastObservedDualBound)`，把 `gap = UB - effectiveLB` 传给 `TimeIndexedGraphPricingEngine.applyPaperReducedCostArcFixing`、`TimeIndexedScalarCompletionBound.applyArcFixing` 以及 completion-bound subtree fixing 的相应入口，并在日志中输出 `lpObj / observedDualBound / gapBefore / gapAfter / fixed`。如果 `effectiveLB >= UB`，节点本身已经可由 dual-bound pruning 关闭，不需要再做 fixing。
