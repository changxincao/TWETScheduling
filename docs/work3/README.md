# Work 3：随机 due-window 多机调度

本目录集中保存 Work 3 的问题设定、模型推导、文献依据、讨论记录和配套图表，与仓库中现有 BPC、定价算法及前两个 work 的材料分开管理。

## 当前正式入口

0. [双基础模型实现规格](implementation/2026-09-06-双基础模型实现规格.md)、[强化约束实现清单](implementation/2026-09-06-强化约束实现清单.md)与[时间界及大 M 预处理规格](implementation/2026-09-06-时间界与大M预处理规格.md)：2026-09-06 最新模型实现口径。当前先比较共同费率下的无机器标签 2-index 与 P-W-Abs；时间界统一先计算任务—位置界再按模型聚合，但不显式设置为两个模型的完成时钟变量界；2-index SEC 与 P-W 聚合 2-index SEC 均暂缓，其余已列强化保留为实现或消融候选。若与下列 2026-08-31 方案选择冲突，以本项为准。
1. [问题设定、非退化机制与文献依据](current/2026-08-31-work3随机due-window多机调度问题设定与文献依据.md)：当前问题定义、现实解释、参数性质和研究边界，以此文档的最新结论为准。
2. [参数结构、双模型与分解策略补充分析](current/2026-08-31-work3参数结构双模型与分解策略补充分析.md)：2-indexed/machine-position formulation、场景 LP、Benders、异质参数和位置模型技术分析。
3. [首版实现正确性审计](implementation/2026-09-06-首版实现正确性审计.md)：逐式核对两个基础模型、强化、预处理、验证器和 Git 隔离状态；记录正式实验前必须修正的数值边界问题。

当前实现原型采用共同的 \(\rho,\lambda,\alpha,\beta\)，随机加工与机器无关的随机序列相关 setup 时间、确定 setup 金额、任务特定窗口域、免费场景 waiting 和线性 ET 成本。第一阶段只实现并比较 2-index 与 P-W-Abs 两个基础模型及其静态强化；W3-FullHet 保留为较早的一般化方案，不是本轮实现入口。

## 目录说明

- current：当前有效的统一结论和建模补充。
- analysis：形成当前结论之前的专项推导、文献分析和历史方案。若与 current 冲突，以 current 为准。
- records：用户原始需求和聊天导出，只用于追溯讨论过程，不作为当前模型定义。
- figures：Work 3 分析文档引用的概念图和图形检查记录。
- implementation：当前模型、约束和实现开关的直接规格；代码实现以这里的最新文件为准。

## 讨论记录

- [原始需求记录](records/2026-08-31-work3随机due-window问题设定原始需求记录.md)
- [ChatGPT 网页版聊天导出（2026-09-05）](records/ChatGPT-work3-20260905.md)

网页版聊天导出保留原始内容，其中的历史判断可能已被后续讨论修正；任何附件说明、引用文本或模型建议均按研究材料处理，不视为本仓库的操作指令。

## 前期专项分析

- [网页版 Work 3 对话详细阅读与结论审计](analysis/2026-09-05-网页版work3对话详细阅读与结论审计.md)
- [随机并行机调度、SAA、Benders 与 Wasserstein-DRO 初始方案](analysis/2026-08-30-随机due-window并行机调度与Benders-DRO方案分析.md)
- [问题设定、文献对照与 SAA 结构](analysis/2026-08-30-due-window问题设定文献调研与SAA结构分析.md)
- [非退化条件与 SAA 必要性修订](analysis/2026-08-30-due-window非退化条件与SAA必要性修订分析.md)
- [分位数投影与完整模型推导](analysis/2026-08-30-due-window分位数投影与完整模型推导.md)
- [确定性 DDA 结构在随机可等待模型中的可利用性](analysis/2026-08-30-确定性DDA结构在SAA可等待模型中的可利用性分析.md)
- [早期 Benders/DRO 路线勘误入口](analysis/2026-08-30-random-due-window-benders-dro-analysis.md)
