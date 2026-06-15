# 2026-06-15 reduced-cost arc fixing 废弃记录

本文件原来记录下载文档 `arc_fixing_reduced_cost_cn.pdf` 中的 reduced-cost arc fixing 思路。讨论后决定不继续采用这条路线，也不把该文档作为后续实现依据。

核心原因是该方法的原始公式面向固定数量 path 的 arc-time pricing 网络，判定里依赖“强制使用某条 arc 的 path 下界 + 其余 path 的最乐观 reduced cost”。当前 TWET-BPC 不是这个结构：机器数量在节点中是区间，主问题还包含外包变量和 tariff segment，整数解不一定由固定数量内部 path 组成。因此把文档里的 arc fixing 公式直接改写到当前模型中，尤其在存在外包时，容易让“剩余任务/剩余机器/外包决策”的 reduced-cost 下界语义变得不清楚。

当前保留的只是原有 completion-bound pruning、局部 completion-bound arc fixing 诊断/开关，以及 pricingOnly subtree arc elimination 等历史组件。这些组件是否启用仍由配置控制，不再把下载文档中的固定 path 数公式作为增强方向。后续如需继续研究 arc fixing，应重新从当前模型的外包语义、机器数区间和 set-covering/repair 流程出发推导，而不是沿用该文档的固定 `m` path 公式。
