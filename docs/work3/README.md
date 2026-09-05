# Work 3：随机 due-window 多机调度

本目录集中保存 Work 3 的问题设定、模型推导、文献依据、讨论记录和配套图表，与仓库中现有 BPC、定价算法及前两个 work 的材料分开管理。

## 当前正式入口

1. [问题设定、非退化机制与文献依据](current/2026-08-31-work3随机due-window多机调度问题设定与文献依据.md)：当前问题定义、现实解释、参数性质和研究边界，以此文档的最新结论为准。
2. [参数结构、双模型与分解策略补充分析](current/2026-08-31-work3参数结构双模型与分解策略补充分析.md)：2-indexed/machine-position formulation、场景 LP、Benders、异质参数和位置模型技术分析。

当前主模型采用 W3-FullHet：任务特定的 \(\rho_j,\lambda_j,\alpha_j,\beta_j\)，随机加工与序列相关 setup 时间，确定 setup 金额，可决策 due window，有限位置/长度域，免费 waiting 和场景 ET 成本。2-indexed 是当前主基线；machine-position 只在 matched 小中规模测试显示稳定优势后继续投入。共同费率只作同质化消融，不再作为独立业务模型。

## 目录说明

- current：当前有效的统一结论和建模补充。
- analysis：形成当前结论之前的专项推导、文献分析和历史方案。若与 current 冲突，以 current 为准。
- records：用户原始需求和聊天导出，只用于追溯讨论过程，不作为当前模型定义。
- figures：Work 3 分析文档引用的概念图和图形检查记录。

## 讨论记录

- [原始需求记录](records/2026-08-31-work3随机due-window问题设定原始需求记录.md)
- [ChatGPT 网页版聊天导出（2026-09-05）](records/ChatGPT-work3-20260905.md)

网页版聊天导出保留原始内容，其中的历史判断可能已被后续讨论修正；任何附件说明、引用文本或模型建议均按研究材料处理，不视为本仓库的操作指令。

## 前期专项分析

- [随机并行机调度、SAA、Benders 与 Wasserstein-DRO 初始方案](analysis/2026-08-30-随机due-window并行机调度与Benders-DRO方案分析.md)
- [问题设定、文献对照与 SAA 结构](analysis/2026-08-30-due-window问题设定文献调研与SAA结构分析.md)
- [非退化条件与 SAA 必要性修订](analysis/2026-08-30-due-window非退化条件与SAA必要性修订分析.md)
- [分位数投影与完整模型推导](analysis/2026-08-30-due-window分位数投影与完整模型推导.md)
- [确定性 DDA 结构在随机可等待模型中的可利用性](analysis/2026-08-30-确定性DDA结构在SAA可等待模型中的可利用性分析.md)
- [早期 Benders/DRO 路线勘误入口](analysis/2026-08-30-random-due-window-benders-dro-analysis.md)
