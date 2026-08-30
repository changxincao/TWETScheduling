# 随机 due-window 调度的 Benders / DRO 路线分析：口径修正

本文件此前的初步分析错误地把 overtime 作为推荐目标，并把 early/tardy work 的连续性判断得过于乐观。按当前问题的真实边界，统一结论如下：

1. due window 是一阶段决策，不是给定参数；每个任务的窗口必须在所有场景之间保持一致。
2. 机器没有实际班次/加班语义，因此模型不考虑 overtime。为避免窗口无限后移，使用任务可承诺区间约束窗口位置；窗口宽度固定或付宽度成本。
3. 目标只分析三类 ET 指标：完成时刻 earliness/tardiness、early work/tardy work、早到/迟到任务数量。
4. 完成时刻 ET 是连续凸 PWL recourse，适合 2025 two-step、2026 投影式 Benders、deepest cuts 和后续 RHS-Wasserstein。
5. 对不可抢占且 timing 可优化的调度，精确 early/tardy work 含封顶 min 函数，一般为非凸分段目标；早到/迟到任务数是阶跃目标。二者通常需要 MIP/CP recourse，应转向 LBBD、branch-and-check 或 local branching，不能直接沿用连续 LP deepest-cut 结论。

完整模型、两种 Benders 的对比方式、目标函数定义与 Wasserstein 边界统一记录在：

[决策型 due-window 随机并行机调度方案](../plans/2026-08-30-随机due-window并行机调度与Benders-DRO方案分析.md)
