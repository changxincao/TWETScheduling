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

首版实现没有跑算例测试。主要剩余风险是：active arc-pair dual 时关闭一部分下界剪枝会降低右支 pricing 效率；旧版 pricing 引擎未完整支持三连片段 dual；后续还需要在 adjacency 表现不稳的 30 任务算例上做 A/B 测试，判断该分支是否比无向 adjacency 更稳定。
