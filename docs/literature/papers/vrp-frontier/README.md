# VRP 分支定价前沿论文正文索引

本目录保存 2026-08-24 讨论涉及的公开论文正文，不含 Supporting Information（SI）。每篇论文的子目录同时保留 `manifest.json`，记录下载来源、SHA-256 和下载状态。详细方法分析见 [`2026-08-24-VRP分支定价前沿与TWET可迁移性分析.md`](../../2026-08-24-VRP分支定价前沿与TWET可迁移性分析.md)。

## 建议阅读顺序

| 顺序 | 论文正文 | 主要用途 | 当前判断 |
| --- | --- | --- | --- |
| 1 | [Cluster Branching for Vehicle Routing Problems](01-cluster-branching/PDFs/Cluster_Branching_for_Vehicle_Routing_Problems.pdf) | 聚合分支变量、cutset/cluster branching、两阶段 strong branching | 可用于评估 TWET 的结构性分支，但不直接加速 ng-DSSR |
| 2 | [Two-Stage Learning to Branch in Branch-Price-and-Cut Algorithms for Solving Vehicle Routing Problems Exactly](02-two-stage-learning-to-branch/PDFs/Two-Stage_Learning_to_Branch_in_Branch-Price-and-Cut_Algorithms_for_Solving_Vehicle_Routing_Problems_Exactly.pdf) | 2LBB、partial testing、动态 strong-trial 预算 | 非机器学习的动态预算部分最容易先做受控评估 |
| 3 | [A Bucket Graph-Based Labeling Algorithm with Application to Vehicle Routing](11-bucket-graph/PDFs/A_Bucket_Graph-Based_Labeling_Algorithm_with_Application_to_Vehicle_Routing.pdf) | bucket labeling、dominance 组织、bucket arc elimination、动态 ng-relaxation 宿主 | 与当前 ng-DSSR 定价实现效率最直接相关的基线文献之一 |
| 4 | [RouteOpt: An Open-Source Modular Exact Solver for Vehicle Routing Problems](09-routeopt/PDFs/RouteOpt_An_Open-Source_Modular_Exact_Solver_for_Vehicle_Routing_Problems.pdf) | 现代 VRP exact solver 的完整流程和模块边界 | 仅用于实现对照；现成 CVRP/VRPTW 应用不能直接求解当前 TWET |
| 5 | [A Generic Exact Solver for Vehicle Routing and Related Problems](10-vrpsolver-generic-exact/PDFs/A_Generic_Exact_Solver_for_Vehicle_Routing_and_Related_Problems.pdf) | VRPSolver/BaPCod 的 packing set、ng-route、rank-1、enumeration 与分支框架 | 仅用于逻辑对照；通用 RCSP 接口不能直接表达当前 PWLF 定价语义 |
| 6 | [Resource-Window Reduction by Reduced Costs in Path-Based Formulations for Routing and Scheduling Problems](08-arc-specific-resource-windows/PDFs/Resource-Window_Reduction_by_Reduced_Costs_in_Path-Based_Formulations_for_Routing_and_Scheduling_Problems.pdf) | reduced-cost resource windows、arc-specific fixing | 与现有 dual-window、time-arc fixing 的正确性边界和实现细节直接相关 |

## 条件性参考

| 论文正文 | 主要用途 | 当前判断 |
| --- | --- | --- |
| [DeLuxing: Deep Lagrangian Underestimate Fixing for Column-Generation-Based Exact Methods](03-deluxing/PDFs/DeLuxing_Deep_Lagrangian_Underestimate_Fixing_for_Column-Generation-Based_Exact_Methods.pdf) | 枚举列池上的多 dual 安全变量固定 | 只有完整 route enumeration 成为瓶颈后才有意义 |
| [Subset-Row Inequalities and Unreachability in Path-Based Formulations for Vehicle Routing and Scheduling Problems](04-sri-unreachability/PDFs/Subset-Row_Inequalities_and_Unreachability_in_Path-Based_Formulations_for_Vehicle_Routing_and_Scheduling_Problems.pdf) | active SRI 下利用不可达性加强 dominance | 当前重点是 no-SRI ng-DSSR，因此不进入近期修改 |
| [Branch-Price-and-Cut Accelerated with a Pricing for Integrality Heuristic](05-pricing-for-integrality/PDFs/Branch-Price-and-Cut_Accelerated_with_a_Pricing_for_Integrality_Heuristic_for_the_Electrical_Vehicle_Routing_Problem_with_Time_Windows_and_C.pdf) | 面向高质量整数解的启发式定价 | 只有 incumbent 明显滞后时值得评估，不能提供 exact certificate |
| [Column Elimination: An Iterative Approach to Solving Integer Programs](06-column-elimination-framework/PDFs/Column_Elimination_An_Iterative_Approach_to_Solving_Integer_Programs.pdf) | Column Elimination 通用框架、状态细化和 conflict refinement | 会替换当前 Dantzig-Wolfe 主线，不作为近期实现项 |
| [Column Elimination for Capacitated Vehicle Routing Problems](07-column-elimination-cvrp/PDFs/Column_Elimination_for_Capacitated_Vehicle_Routing_Problems.pdf) | Column Elimination 的 CVRP 初始版本和实验设计 | 用于理解上述通用框架的 VRP 起点 |

## 文件校验

本批共 11 篇正文。下载后逐份检查了 `%PDF` 文件签名、下载清单中的 SHA-256，并用 PDF 解析器读取页数和首页文本；所有文件均通过。2LBB 预印本的内部交叉引用表存在非致命告警，但 74 页正文可正常解析和阅读。
