# Paper dominance graph 叠加 partial 占优分析

## 问题

当前已有两条独立路径。normal 路径使用 `PaperDominanceGraph`，按 reachable-set 图维护 `labelEnvelope`、`predecessorEnvelope` 和 `dominanceEnvelope`，只做完整函数占优；partial 路径使用 `PartialListDominanceStore`，完全绕开 graph，用 flat list 扫描 label，并在被支配的时间区间上把 label frontier 裁成 `big_M`。

用户提出的新方案不是现有 partial 分支，而是第三种 backend：保留 normal 的 dominance graph 结构，只把“被占优时必须完整删除”改成“可以先做部分占优裁剪，只有 frontier 被全部裁空才删除 label”。直觉是 graph envelope 仍能提供较强的聚合支配信息，同时 partial 裁剪会让 label frontier 片段变少，后续更容易被完整占优或减少 join 规模。

## 是否只是一个区别

概念上核心区别确实是这一点：`dominanceEnvelope.dominates(label.frontier)` 改成 `label.frontier.updateDominatedIntervals(dominanceEnvelope, direction)`。但工程上不是简单替换，因为 `PaperDominanceGraph` 维护了多个缓存 envelope。

新 label 插入前，如果被 predecessor/candidate 的 `dominanceEnvelope` 部分裁剪，不能直接把原始 label 放进 node；必须先刷新 `minReducedCost`，如果全为 `big_M` 就丢弃，否则把裁剪后的 label 加入。插入同 reachable-set node 后，`labelEnvelope` 要用裁剪后的 frontier 重算或增量 merge。

传播阶段也只需要替换占优动作。当前 `propagateAndTrim()` 里，如果 `predecessorEnvelope` 完整支配 `node.labelEnvelope`，就直接删除 node；否则再调用 `removeLabelsDominatedByPredecessors()` 删除被完整支配的单个 label。改成 partial 后，单个 label 被前驱包络检查时先裁剪，裁空才删除；没有裁空则保留裁剪后的 frontier。由于裁剪来源就是 `predecessorEnvelope`，当前 node 的 `dominanceEnvelope=min(labelEnvelope, predecessorEnvelope)` 已经有该支撑来源，图结构和传播框架不需要重构。

## 对 graph envelope 的影响

“dominance graph 里的函数包络基本不会变”这个判断不完全对。`predecessorEnvelope` 的来源不变，图结构也不一定变，但当前 node 的 `labelEnvelope` 会因为 label frontier 被裁剪而变大或断段；因此 `dominanceEnvelope=min(labelEnvelope, predecessorEnvelope)` 也可能变化。如果裁掉的区间原本正是 `labelEnvelope` 的最小片段，后续 envelope 会明显抬高，支配能力会变弱但更安全。

这和 flat-list partial 的一个重要差别是：flat list 没有聚合 envelope 缓存，而 graph 版本依赖聚合 envelope 做后续支配。当前实现口径下不主动重构 graph，只要求裁剪来源已经进入当前可用包络：新 label 裁旧 label 前先进入当前 node，predecessor 裁当前 label 时来源就是 `predecessorEnvelope`。

## 可行实现形态

比较合理的是新增一个独立实验 store，例如 `PaperPartialDominanceGraph`，不要直接改 normal `PaperDominanceGraph`。原因是 normal 是当前主线 exact backend，partial graph 会同时改变支配强度、label frontier、传播次数和 envelope 更新，不适合直接覆盖。

实现上应抽出局部操作 `trimLabelByEnvelope(label, envelope)`，负责 `canCoverDomain` 检查、调用 `updateDominatedIntervals()`、刷新 `minReducedCost` 并判断是否全删。label 被裁到全空时继续走原有删除和必要 rebuild 流程；只发生部分裁剪时，不额外改变 reachable-set 图结构。

single-point dominance 不需要做 partial 裁剪，因为它只回答“这个 Tmid 点是否已被支配”。它仍可以使用 graph 的 `dominanceEnvelope` 点值判断；如果 graph envelope 已正确反映 partial-trim 后的 label frontier，就不需要单独改成区间裁剪。

## 风险与验证

主要风险是 partial trim 的来源顺序。若用新 label 裁剪旧 label，必须先保证新 label 已经进入当前 node 的可用包络；若用 predecessor 裁剪当前 label，则来源应来自当前 `predecessorEnvelope`。因此第一版必须用独立开关和 root-only 对拍验证。

建议验证顺序为：先写 focused 单元/一致性测试，构造两个同 terminal、reachable superset 的 PWLF，确认 partial trim 后 envelope 会抬高且不会继续用旧片段支配；再跑三角化 010/011/40 root-only，对比 normal bound 与 partial-graph bound 是否一致，并观察 exact 时间、label kept、join funcEval、paperGraph/partialGraph 统计。只有这些通过后，再考虑进入多 node 或打开 RMIH 的完整流程对照。

## 旧流程与 partial graph 后的新流程

旧的 normal graph 流程是：新 label 到来后，先找同 terminal 下 reachable-set 的同节点或 superset 前驱候选，把这些候选 node 的 `dominanceEnvelope` 合成一个支配包络。如果这个包络在整个定义域上都不大于新 label 的 frontier，就认为新 label 完全被占优，直接丢弃；否则把它插入同 reachable-set node 或新建 node。插入后，从受影响 node 的 successors 开始传播：每个 successor 重算 `predecessorEnvelope`，如果前驱包络完整支配整个 `labelEnvelope`，就删除整个 node；否则逐个删除被前驱完整支配的 label，最后重算 `dominanceEnvelope` 并继续向后传播。

叠加 partial dominance 后，流程的图结构、reachable-set 前后继关系、superset/subset 搜索仍然可以沿用，但“完整占优就删除”的动作要改成“先裁剪，再判断是否删除”。新 label 到来时，如果候选 `dominanceEnvelope` 只支配它的一部分时间区间，就用 `updateDominatedIntervals()` 把这些区间打成不可用，再刷新该 label 的最小 reduced cost；只有裁剪后没有任何有效区间时，才把它当作 rejected。传播阶段同理：前驱包络不一定直接删除 label，而是可能只裁掉 label 的一部分区间；裁空才走原删除流程。

这样改的原因是，partial trim 改变的是 label 函数本身，不只是改变一个“是否 active”的布尔状态。但在当前实现口径下，裁剪来源本身已经在可用 envelope 中提供更低值：新 label 裁旧 label 时由新 label 支撑，predecessor 裁当前 label 时由 `predecessorEnvelope` 支撑。因此 partial graph 不是大改 reachable-set 图，而是把占优动作从“完整删除”替换为“裁剪后裁空才删除”。

这里需要区分两种情况。第一种是“新 label 在插入前被已有 envelope 裁剪”。这种情况下旧 node 的缓存本来没有包含这个新 label，只要最终插入的是裁剪后的 frontier，正常的 `addLabel()`/merge 就能得到正确 envelope。第二种是“graph 中已经存在的 label 被新 label 或 predecessorEnvelope 部分裁剪”。若新 label 已经进入 node，或者裁剪来源就是 `predecessorEnvelope`，则被裁掉区间已有更低来源支撑；因此不需要为了部分裁剪额外重建整张 graph。只有 label 被裁到全空并删除时，继续沿用已有删除和 rebuild 逻辑即可。

2026-06-11 进一步澄清：如果只修改 `insertOrDominate()` 中“新 label 被已有 candidate/predecessor envelope 判断占优”的入口，即新 label 先被 partial trim，再按原流程插入，那么旧 node 的缓存确实不需要额外重建，因为旧缓存本来没有包含这个新 label。更强版本还应裁剪 graph 中已有 label。这个方向原理上也是安全的：若新 label `B` 在某区间裁剪旧 label `A`，则该区间上 `min(A,B)` 本来就由 `B` 提供；若 predecessorEnvelope 裁剪已有 label，则 dominanceEnvelope 里本来已经有这个更低来源。实现上需要保证裁剪来源已经进入当前 node 的 envelope 或 predecessorEnvelope，并且后续 label 删除时沿用当前已有的 rebuild 逻辑，避免删除支配来源后留下 stale envelope。也就是说，问题不在 partial 裁剪原理，而在插入、裁剪、重算和传播的顺序必须和 graph 缓存一致。

因此当前收敛后的实现判断是：不需要重构 dominance graph。主要改动应集中在“完整占优删除”的几个入口，把 `dominates()` 后直接删除改为 `updateDominatedIntervals()` 后判断是否全空。图结构、reachable-set 前后继、superset/subset 搜索、single-point dominance 和大部分传播框架都应保持不变。额外机制只需要处理两件事：一是 partial trim 后刷新 label 的 `minReducedCost`；二是 label 被裁到全空时继续走原来的删除与必要 rebuild 流程。

## 2026-06-11 实现与初步验证

本次按上述最小侵入思路实现了 graph partial dominance 实验路径。代码上新增 `PaperPartialDominanceGraph` 和 `PaperPartialDominanceGraphs`，复用 `PaperDominanceGraph` 的 reachable-set 图结构，只通过构造参数打开 partial trim 行为；新增 `GCNGBBStyleBidirectionalNgDssrGraphPartialDominancePricingEngine`，复用当前 ng-DSSR 主体，仅切换 dominance store。默认求解路径不变，新入口由 `useGCNGBBStyleNgDssrGraphPartialDominancePricing` 控制，`GCBBFullDomainComparisonTest` 可通过 `twet.bpc.fullDomainCompare.ngDssrGraphPartialDominance=true` 打开。

实现细节为：新 label 被 candidate/predecessor envelope 检查时，若开启 partial，则先调用 `updateDominatedIntervals()` 裁剪被支配区间，刷新 `minReducedCost`，只有裁空或 `big_M` 时才 reject；同 reachable-set node 插入前，用新 label 的剩余 frontier 裁剪已有 label；传播阶段中，已有 label 被 `predecessorEnvelope` 检查时同样先 partial trim，裁空才删除。图结构、single-point dominance、superset/subset 搜索和 completion bound 入口没有改变。

验证：focused `javac` 已通过。用 ng-set 满集模拟 elementary 口径做 root-only smoke：`wet020_001_2m` 中普通 ng-DSSR 满集为 `obj=6343, bound=6343, exact=0.282s, valid=true`，graph partial 为 `obj=6343, bound=6343, exact=0.278s, valid=true`；`wet021_001_2m` 中普通 ng-DSSR 满集为 `obj=6829, bound=6829, exact=0.442s, valid=true`，graph partial 为 `obj=6829, bound=6829, exact=0.463s, valid=true`。两个小规模 root 结果一致，效率差异很小。尝试 `tmp-wet030_001_2m` 的满集 root 对照时，普通 ng-DSSR 满集 120s 未返回，说明该口径过重，不适合作为本轮 smoke。
