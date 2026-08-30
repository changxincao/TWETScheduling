# 随机 due-window 调度的 Benders / DRO 路线分析

## 背景

这次讨论围绕一个考虑 due-window 的机器调度问题展开，目标是把 2025 和 2026 两篇 TWATSP-ST 论文里的随机建模和精确分解思路迁移到调度场景，并判断是否可以从 SAA 直接走到 Benders，再进一步扩展到 Wasserstein DRO。讨论中同时参考了 2024 年关于 deepest cuts 和 local branching 的两篇 Benders 论文，以及 due-window / early-tardy scheduling 的综述文献。

## 主要判断

1. 先做 SAA 是合理的。2025 和 2026 两篇 TWATSP-ST 都是在有限场景集上做两阶段随机规划，并通过 Benders 处理 second-stage 连续 recourse。对 due-window 调度来说，SAA 可以直接作为第一版随机模型的实验基座。

2. 若想延续经典 Benders，第二阶段最好保持连续。最自然的目标是 earliness/tardiness，加上 window width 和 overtime 也都可以做成连续 recourse。`#early/#tardy` 这类计数目标、以及需要 second-stage 再做离散选择的版本，会把 recourse 往混合整数方向推，经典 Benders 的优势会明显变弱。

3. 多机器不是问题，甚至更自然。单机可以先做基线，但并行机更适合展示模型价值。若保持 first-stage 决定排程/分配，second-stage 只负责场景下的完成时间、违约量和 overtime 计算，那么仍然有希望维持连续 recourse。若引入 sequence-dependent setup、资源占用或其他组合约束，就要考虑 logic-based Benders 或 branch-and-check。

4. 2025 和 2026 的差异可以作为两条可比路线。

   - 2026 风格更像“新 MILP + 专门 Benders”，强调更强的 formulation 和更强的 separation。
   - 2025 风格更像“二步分解 + scenario retention/clustering”，强调 cut 强度和场景压缩。

   两者可以在同一 SAA 样本上对比，分离“模型强弱”和“分解强弱”两个因素。

5. deepest cuts 和 local branching 可以并行使用，不是互斥关系。

   - deepest cuts 解决的是“每次 Benders cut 怎么更深、更强”。
   - local branching 解决的是“把某个邻域整块砍掉，而不是一条条 cut 掉”。

   前者适合提升 cut 质量，后者适合控制 master 的邻域搜索和 cut 数量。

6. Wasserstein DRO 可以作为第二阶段扩展方向。若不确定性主要出现在 RHS，并且 recourse 保持线性/连续，那么 Wasserstein 球上的两阶段 DRO 通常有较好的可重写性，适合和 SAA 并列比较。

## 目标函数可行性分析

1. 先做 earliness / tardiness 及其加权版本，最稳。

这类目标和 2025 / 2026 两篇 TWATSP-ST 的结构最接近，也最容易把 second-stage 保持成连续 LP。若 first-stage 已经固定了机器分配、顺序或位置，场景下的完成时间、提前量、拖期量、宽度惩罚都可以写成连续变量上的线性或分段线性约束。这里最关键的是把组合决定放在 master，把场景下的时间代价放在 recourse。

2. overtime 可以做，而且适合和 ET 一起做。

只要把班次上界、机器可用时间或完工期限写成场景右端项，overtime 仍然是连续非负变量，和 earliness/tardiness 一样适合 SAA + Benders。它更像 TWATSP-ST 里 shift overtime 的机器版，而不是一个会破坏连续 recourse 的新难点。

3. early work / tardy work 可以做，但它和 ET 不是同一个量。

如果这里说的是“完成在 due window 前/后对应的工作量”，那它本质上是工作量型的连续分段目标，通常比 number of early/tardy jobs 更适合做 LP recourse。它和 ET 的区别在于：ET 看完成时刻偏离，early/tardy work 看的是被切到窗口前后多少加工量，语义更偏产能视角而不是交付时点。

4. number of early / tardy jobs 也能做，但它会把 recourse 往整数方向推。

这个目标在 due-window 文献里是经典的，但一旦按场景去数“有多少 job 早/晚了”，通常就要引入二元指示变量或者逻辑判断。这样 second-stage 很容易不再是纯 LP，经典 Benders 的优势会变弱。若想坚持“second-stage 连续”，这个目标不建议放在第一版主线里，更适合做成对照实验，或者放到 logic-based Benders / branch-and-check 分支里。

5. 组合目标是可以的，但要控制第一阶段和第二阶段的边界。

比如 `ET + overtime + window width` 这种组合，结构上最自然；`ET + count` 也能写，但会显著抬高分解复杂度。若目标太多，论文主线会变散。更实际的做法是：主线保留 2 到 3 个连续目标，附加一个离散目标作为扩展实验。

## 方法路线的更细分工

1. 2026 风格更适合作为主线 baseline。

它的核心不是 Benders 这个名字，而是更强的 formulation、较干净的 master / subproblem 切分，以及 scenario aggregation / cut separation 的工程化处理。若不确定性主要是 RHS 上的时长、加工时间、可用时间或班次边界，这条线最容易迁移到机器调度。

2. 2025 风格更适合作为第二条可比路线。

它强调 binary first-stage 和 continuous first-stage 的二步拆分，再加上 scenario retention / clustering。这个思路对“第一阶段同时含有分配变量和连续 due-window 参数”的调度问题很有吸引力。若想写出“不是照搬 2026，而是把二步聚合和场景保留也吸收进来了”，这条线很合适。

3. deepest cuts 和 local branching 是两条正交增强，不是替代关系。

deepest cuts 解决的是“每次 Benders cut 选哪一条更深”；local branching 解决的是“在某个邻域内别一条条 cut，直接用一个局部约束切掉整块邻域”。如果 master 是 MILP 且 recourse 还是 LP，deepest cuts 可以直接嵌进来；如果想用 CP / MIP 去搜邻域，local branching 更适合做主角。两者可以叠加，但最好先把基础 Benders 跑稳，再上 local branching。

4. 多机器值得直接做，不必先把单机做死。

从文献和方法上看，parallel machine 更像这个问题的自然主场。单机可以作为最小基线，但如果主问题本来就含有分配、排序和 due-window 设计，直接做 identical parallel machines 更容易体现模型价值。若再往 unrelated parallel machines 走，position-based 或 assignment-based master 会更自然，也更方便和 2024 的 local branching 论文接轨。

5. Wasserstein DRO 可以接在 SAA 后面。

如果不确定性主要只进 RHS，而且 second-stage 保持线性连续，那么 Wasserstein 两阶段 DRO 是合理的第二阶段扩展。它和 SAA 的关系也清楚：SAA 是经验分布下的基线，Wasserstein DRO 是在经验分布附近做分布扰动后的稳健版本。若 second-stage 含二元指示变量或序列选择，Wasserstein 仍可做，但通常会把模型推进到更重的 MIP / conic / cutting-plane 框架，不再是现在设想的“连续子问题 + Benders”主线。

## 一版可以直接开写的模型骨架

可以先把问题写成“随机 due-window 调度 with continuous recourse”的两阶段 SAA 模型：

第一阶段决定机器分配、顺序/位置，以及 due-window 的位置或宽度参数；第二阶段在每个场景下根据随机 processing time、setup time、availability 或其他 RHS 扰动，计算 completion time、earliness、tardiness、overtime 和可能的 window violation cost。这样，场景下 recourse 如果全部是连续变量，就可以用标准 Benders 或 2026 风格的 ad-hoc Benders；如果第一阶段里还想保留连续 due-window 参数，2025 风格的 two-step decomposition 会更自然。

从实现优先级看，推荐顺序是：

1. 先做单机 SAA + 标准 Benders，确认 recourse 确实是 LP。
2. 再做 parallel machine 版本，优先 identical，再扩展 unrelated。
3. 在同一 SAA 样本上比标准 Benders、2025 风格 two-step / scenario retention、2026 风格强 formulation / ad-hoc Benders。
4. 之后再加 deepest cuts 和 local branching 做增强。
5. 最后再把 Wasserstein DRO 接上去。

## 本次参考的核心文献

- Şifa Çelik et al., 2025, Transportation Science, https://doi.org/10.1287/trsc.2024.0750
- Francesco Cavaliere et al., 2026, European Journal of Operational Research, https://doi.org/10.1016/j.ejor.2025.07.034
- Hosseini & Turner, 2024, Operations Research, https://pubsonline.informs.org/doi/10.1287/opre.2021.0503
- Avgerinos et al., 2024, European Journal of Operational Research, https://www.sciencedirect.com/science/article/pii/S0377221724009512
- Janiak et al., 2015, EJOR survey on due windows, https://www.sciencedirect.com/science/article/pii/S0377221714007826
- Sterna, 2021, Omega survey on early/late work, https://doi.org/10.1016/j.omega.2021.102453
- Duque, Mehrotra & Morton, 2021, two-stage DRO with Wasserstein, https://doi.org/10.1137/20M1370227
