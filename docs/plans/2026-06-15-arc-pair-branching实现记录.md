# 2026-06-15 arc-pair branching 实现记录

## 1. 实现范围

本次实现 `i-j-k` 连续片段分支的首版代码，默认配置仍关闭。节点状态新增有向三连片段的 forbidden/required 状态；左支禁止片段 `i->j->k`，右支在 RMP 中加入 `sum_r a_{ijk,r} lambda_r >= 1`。分支候选直接扫描当前 LP 正值列中的连续三元片段并累计流量，选择最接近 0.5 的 fractional 片段，避免分支选择阶段枚举全部三元组再反查解值。

相关入口包括 `Node`、`LP`、`TWETColumn`、`TWETMasterSolution`、`ArcPairBrancher` 和 `TWETBPCContext`。配置项为 `enableArcPairBranching`，当前默认 `false`。

## 2. pricing 处理

主线 GCNGBB-style 三类 pricing 已接入该分支：normal、partial-dominance 和 ng-DSSR。扩展时只把 forbidden 三连片段作为局部转移禁忌，不写入 ng-memory 或资源不可达集合；join 时检查 crossing arc 两侧可能形成的两个三连片段，并把三连片段分支行 dual 加入 reduced cost。启发式 pricing 也同步检查三连片段禁忌，并在 tabu remove/add/exchange 的 reduced-cost 增量中加入三连片段 dual。

旧版 GCBB/GCBidirectional 实验引擎暂未完整接入三连片段 dual。后续如果要启用 `enableArcPairBranching`，应优先使用 GCNGBB-style 主线 pricing，避免旧引擎 reduced-cost 口径不完整。

## 3. completion bound 与 arc fixing

completion bound 和 group-level join lower bound 没有三连片段状态维度。如果 active arc-pair dual 存在，继续使用这些下界剪枝可能因为漏掉未来三连片段 dual 奖励而误剪负列。因此当前实现采用保守策略：active arc-pair dual 存在时，主线 pricing 关闭 completion-bound label 剪枝、completion-bound arc fixing、join group lower-bound 和 optimistic pair lower-bound，只保留真实扩展、真实 join 函数计算和最终 RMP 行保证正确性。

`CompletionBoundSubtreeArcEliminator` 还增加了 required arc-pair 兼容检查：如果某条普通 arc 是 required 三连片段的第一条或第二条弧，subtree/permanent/pricingOnly arc fixing 都不会把它禁止掉，避免右支 required 片段和 arc fixing 互相冲突。

## 4. 当前风险

首版实现没有跑算例测试。主要剩余风险是：active arc-pair dual 时关闭一部分下界剪枝会降低右支 pricing 效率；后续还需要在 adjacency 表现不稳的 30 任务算例上做 A/B 测试，判断该分支是否比无向 adjacency 更稳定。

## 5. 2026-06-15 统一旧 pricing 口径

在首版实现基础上，继续把仍可通过配置选中的旧 pricing 引擎补齐到同一语义，包括单向 `GC`、普通 `GCBidirectional`、`GCBBAsymmetricBidirectional`、`GCBBStyleBidirectionalFullDomain` 和 `GCBBStyleBidirectionalFullDomainNodeJoin`。现在这些引擎在扩展时都会检查 forbidden `i-j-k`，并把对应 arc-pair dual 写入 reduced cost；arc-join 会检查跨拼接边形成的三元片段，node-join 会检查拼接点两侧形成的三元片段。最终候选列入池前统一强制检查分支兼容性，不再只依赖 debug 开关。

仍然保留的保守处理是：只要 active arc-pair dual 存在，缺少三元片段状态维度的 group lower bound / optimistic join lower bound 不用于剪枝，避免因为漏掉未来三元片段 dual 奖励而误剪负列。这样不同 pricing 引擎的差别主要回到各自的核心搜索、dominance、join 方式，而不是分支约束或 reduced-cost 口径不一致。

## 6. 2026-06-15 全局复查与 ng-DSSR 修正

本次围绕两个新增内容做全局复查：一是 `i-j-k` 连续片段分支在 LP、分支器、主线/旧 exact pricing、ng-DSSR、partial dominance、启发式 pricing 和列成本回算中的一致性；二是 reduced-cost arc fixing 与 required arc-pair 的兼容性。复查确认：LP 分支行、repair slack、dual 读取、动态加列系数、pricing 扩展/拼接处的 forbidden 检查、reduced-cost dual 符号，以及 `PricingColumnCostRechecker` 的 objective cost 反推口径是一致的；`CompletionBoundSubtreeArcEliminator` 会跳过 required arc-pair 依赖的普通弧，避免 subtree/permanent/pricingOnly arc fixing 与右支 required 片段互相冲突。

复查中发现一个遗漏：`GCNGBBStyleBidirectionalNgDssr.tryGenerateColumn()` 在恢复 relaxed 序列后，原来没有像其他 exact pricing 一样在入池/记录 non-elementary route 前统一调用 `isSequenceCompatible()`。这会让 ng-DSSR 在极端情况下把违反 pricingOnly arc 或 forbidden arc-pair 的恢复序列加入候选，或者用它更新 ng-set。现已补上最终兼容性检查。该检查属于兜底验证，正常扩展和 join 已经会尽量避免产生非法序列，但 ng-DSSR 恢复序列是 relaxed 路径，保留最终检查更稳。

当前还保留两个性能上的可优化点。第一，`Node.isArcRequiredByArcPair(from,to)` 现在按 required 三元片段列表扫描，分支深时可以在 node 或 eliminator 内缓存为布尔矩阵。第二，启发式 pricing 的 remove/add/exchange 当前按完整序列重算 arc-pair dual 增量，正确但可局部化为只看受影响的最多三个三元片段。由于 arc-pair branching 默认关闭，且 30 任务序列长度较小，这两点暂不影响正确性。
