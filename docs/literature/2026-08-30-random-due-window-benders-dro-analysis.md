# 随机 due-window 调度的 Benders / DRO 路线分析：口径修正

本文件此前的初步分析错误地把 overtime 作为推荐目标，并把 early/tardy work 的连续性判断得过于乐观。按当前问题的真实边界，统一结论如下：

1. due window 是一阶段决策，不是给定参数；每个任务的窗口必须在所有场景之间保持一致。
2. 两篇 TWATSP-ST 论文都没有客户特定的外生窗口位置区间；它们用固定出发时刻、班次长度 T 和正的 overtime 成本锚定自由窗口。机器没有实际加班语义时可以删除 overtime，但若仍保留场景自适应等待，就需用任务可承诺区间或非预见等待替代该锚点；若采用 earliest-start，则窗口位置可以自由，并可按完成时间经验分位数投影。
3. 目标只分析三类 ET 指标：完成时刻 earliness/tardiness、early work/tardy work、早到/迟到任务数量。
4. 完成时刻 ET 是连续凸 PWL recourse，适合 2025 two-step、2026 投影式 Benders、deepest cuts 和后续 RHS-Wasserstein。
5. 对不可抢占且 timing 可优化的调度，精确 early/tardy work 含封顶 min 函数，一般为非凸分段目标；早到/迟到任务数是阶跃目标。二者通常需要 MIP/CP recourse，应转向 LBBD、branch-and-check 或 local branching，不能直接沿用连续 LP deepest-cut 结论。

完整模型、两种 Benders 的对比方式、目标函数定义与 Wasserstein 边界统一记录在：

[决策型 due-window 随机并行机调度方案](../plans/2026-08-30-随机due-window并行机调度与Benders-DRO方案分析.md)
