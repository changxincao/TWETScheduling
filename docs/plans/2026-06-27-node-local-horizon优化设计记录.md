# node 级 local pricing horizon 优化设计记录

## 问题背景

当前非根节点通常不能继续使用 root 的 `pi_j` profitable window。分支、subtree 禁弧、pricingOnly 禁弧、required arc 等会改变可用后继结构，继续套用 root 的动态窗口存在误删风险。因此很多非根节点的 `pricingHorizon` 会退回全局 `CmaxH`，导致分段函数定义域、completion bound 构造和 label 扩展都偏宽。

一个可尝试方向是：在每个 node 开始 pricing 前，基于该 node 已经确定的机器侧约束，构造一个当前 node 可行的内部机器调度，并用该调度的 makespan 作为局部 horizon 候选。若该值小于全局 `CmaxH`，则可作为该 node 的 `pricingHorizon` 试验入口，从而减少函数段传播和 label 扩展范围。

这里要特别注意，上述 horizon 只对当前 node 状态有意义，不能简单说它对整个后续子树都有效。子节点会新增分支约束，可能让当前 node 的可行 schedule 在子节点不可行，甚至迫使最小可行 Cmax 变大。因此更稳的实现口径是：每个 node 单独计算 local horizon；父节点算出来的较小 horizon 不能无条件继承给子节点。

## release/no-wait 语义

当前更合理的 release 口径是用 due window 右端，而不是左端。对每个任务设 processing start release：

`r_j = max(0, d_l[j] - p[j])`

在给定机器顺序下，如果任务 `j` 接在 `i` 后面，则 processing start 为：

`start_j = max(r_j, completion_i + s[i][j])`

然后 `completion_j = start_j + p[j]`。首任务对应 `completion_i=0`、setup 为 `s[0][j]`。

这个递推表达的是：setup 可以在 release 之前执行；如果 `completion_i + s[i][j] < r_j`，机器完成 setup 后等待到 `r_j` 再开始加工。当前 `SchedulerForReleaseNoWait` 的语义正是这种 processing start release，且 `Data.setImprovedCmax()` 已经用 `d_l[j]-p[j]` 做过一次全局 Cmax 改进。因此 node 级 local horizon 可以沿用这一语义，但需要把分支/禁弧状态接入。

如果改成“setup start release”口径，例如用 `d_l[j] - p[j] - minSetupToJ`，就必须同步修改时间递推，不能和当前 `start=max(r_j, completion_i+s_ij)` 混用。若后续真的要用 `minSetupToJ`，该最小 setup 还必须只在当前 node 允许的 predecessor arcs 中取最小值。

## 需要纳入的 node 约束

local horizon 子问题只处理内部机器调度可行性，不处理 reduced cost，也不处理 SRI/cut。应纳入的约束包括：

1. 当前 node 的正式 forbidden arcs。
2. 当前 node 的 required arcs。右支 required arc 还隐含同一前驱/后继的排他禁弧，应和正式 `ArcBrancher` 口径一致。
3. 当前 node 的 pricingOnly forbidden arcs。若这个 horizon 只用于 pricing，则 pricingOnly 禁弧也应进入子问题；它不需要解释成 master 分支行。
4. 当前 node 的无向 adjacency 分支状态。如果该分支以后仍保留开关，forbidden/required pair 也要进入机器顺序约束；虽然当前主线基本不使用它。
5. 机器数上限。为了最小化 Cmax，一般直接允许最多 `maxMachineCount` 台机器即可；如果需要严格证明当前 node 可行，还要检查生成 schedule 的非空机器数是否满足 `minMachineCount`。对单纯 shrink horizon 来说，重点是得到一个满足当前 node 机器列约束的完整可行调度。
6. required outsourcing job 可以从内部机器调度子问题中删除；optional outsourcing 可以忽略，即仍把这些 job 放在机器上。这样只会让 makespan 候选偏大，不会因为少排任务而虚假收紧。

active SRI cut、dual stabilization、RMP dual、外包 tariff dual 等都不改变机器路径的物理可行性，不应进入这个 Cmax 子问题。

## 三种实现方式分析

1. 2-index arc-flow MIP

可以用 `x[i][j]` 表示任务 `i` 是否直接接到任务 `j`，再配连续时间变量 `start[j]` 和 `Cmax`。约束包括每个任务一个前驱、最多一个后继、source 后继数不超过当前 `maxMachineCount`，以及：

`start[j] >= r_j`

`start[j] >= start[i] + p[i] + s[i][j] - M * (1 - x[i][j])`

首任务则用 `start[j] >= s[0][j]`。目标最小化 `Cmax >= start[j] + p[j]`。

这种模型的优点是变量规模相对小，分支 forbidden/required arc 很容易写成 `x=0/1`。时间递推中的正处理时间也能自然排除有向环。缺点是 big-M LP 松弛弱，若要证明最优可能慢；但本用途只需要尽快拿到一个 feasible incumbent，短时间求可行解即可。

2. time-indexed 模型

可以类似当前 time-indexed pricing 图，把状态扩展为 `(lastJob,t)`，再用路径/流模型找满足所有任务覆盖的最小 Cmax。它对 forbidden time arcs 和图清理很直观，也可复用一部分 time-indexed graph 代码思路。

主要缺点是需要先有一个离散 horizon 才能建图，存在循环依赖；如果用全局 `CmaxH` 建图，节点数可能很大。它还要求时间基本整数或可安全 scale，否则离散化会引入额外误差。当前它不适合作为第一优先级。

3. CP/CP Optimizer 模型

每个任务建立 interval variable，机器上用 optional intervals + alternative 表达分配，用 sequence/noOverlap 和 transition matrix 表达 sequence-dependent setup。release 写为 `startOf(job) >= r_j`，目标最小化 `max endOf(job)`。

CP 的优势是这类调度可行解通常比 MIP 更容易快速找到，且我们只需要 incumbent，不一定要证明最优。难点是 required immediate arc 和 forbidden immediate arc 要用 CP sequence 相关表达清楚，例如要求两个任务在同一机器且相邻、或禁止某个 predecessor-successor 组合；Java CP Optimizer 接口是否方便表达需要单独确认。若接口支持，这可能是最值得先试的建模方式。

## 当前判断

总体思路是对的：它和当前全局 `setImprovedCmax()` 的 release/no-wait 思路一致，可以作为非根节点 `pricingHorizon` 过宽时的实验性收缩手段。最适合先做成一个诊断/实验开关，记录 `nodeId/depth/globalCmaxH/localHorizon/forbiddenArcCount/requiredArcCount/pricingOnlyArcCount/buildMillis/solveMillis/status`，只在成功构造当前 node 可行 schedule 且 `localHorizon < data.CmaxH` 时启用。

但它不应一开始就作为严格证明机制使用。原因有两点。第一，当前 feasible makespan 只说明存在一个满足当前 node 机器约束的调度，不自动证明所有对 TWET 最优有用的列都不需要更晚完成；这和当前全局 `CmaxH=1.1*CmaxE` 一样，本质是工程 horizon 口径，需要保守 slack 和实验验证。第二，父节点的 local horizon 不能直接继承给子节点，子节点新增分支后可能需要更大的 makespan。

实现优先级建议为：先尝试 CP 或 2-index arc-flow，只拿可行解，不强求最优；time-indexed 放后，因为它本身依赖较大的初始 horizon。第一版不要修改 root `piWindow` 逻辑，不做永久 arc fixing，不用 local horizon 证明 node infeasible，只观察它是否能降低非根节点 exact pricing 时间。

## 抽象后的 Cmax 子问题描述

把上述 node 级 horizon 计算从 TWET 语境中剥离后，可以描述为一个带释放时间和序列相关 setup 的并行机最小化 makespan 问题。

给定一组任务 `J` 和最多 `m` 台相同并行机器。每个任务 `j` 有加工时间 `p_j` 和释放时间 `r_j`。释放时间约束作用在任务的加工开始时刻上，即任务 `j` 的加工不能早于 `r_j` 开始。任意两个任务 `i,j` 如果在同一台机器上相邻加工，则从 `i` 切换到 `j` 需要序列相关 setup 时间 `s_ij`；从空机器开始加工任务 `j` 时需要初始 setup 时间 `s_0j`。

一台机器上的任务按某个顺序依次执行。若任务 `j` 是该机器上的首任务，则其加工开始时刻至少为 `max(r_j, s_0j)`。若任务 `j` 紧接在任务 `i` 后面，则机器可以在任务 `i` 完成后立即执行 `i->j` 的 setup；setup 可以发生在 `j` 的释放时间之前。任务 `j` 的加工开始时刻因此为 `max(r_j, C_i + s_ij)`，其中 `C_i` 是任务 `i` 的完成时间。也就是说，释放时间只限制加工开始，不限制 setup 开始；如果 setup 提前完成，机器可以等待到 `r_j` 再加工。

可行调度需要给每个任务分配一台机器并确定每台机器上的任务顺序，使所有任务恰好加工一次，同时满足可能给定的前后继可用性约束，例如某些相邻顺序 `i->j` 被禁止，或某些相邻顺序被强制要求。目标是最小化所有任务完成时间的最大值 `Cmax`。在当前应用里，该问题只用于获得一个较小的可行 horizon 候选，因此重点是快速找到满足约束的可行调度及其 `Cmax`，不一定要求证明该 Cmax 全局最优。

## 2026-06-27 PDF 模型核对与第一版实现

下载目录下的 `scheduling_models (1).pdf` 给出了三个模型：2-index arc-flow MIP、CP Optimizer interval/sequence 模型和 time-indexed MIP。前两个模型和上面的抽象问题是一致的，关键点都处理正确：release 约束写在加工开始时间上，首任务通过 `s_0j` 处理，普通相邻任务通过 `C_i+s_ij` 处理，因此 setup 可以在 release 前完成。2-index arc-flow 通过每个任务一个前驱、一个后继、source 后继数不超过机器数和时间递推约束排除子环；由于 `p_j>0`，不需要额外 MTZ 约束。CP 模型用 optional interval + alternative 表达机器分配，用 sequence/noOverlap transition matrix 表达 setup，也能自然表达这种 anticipatory setup。

time-indexed MIP 语义也正确，但它需要先给定离散 horizon `H` 才能建图。当前目标正是尝试得到更紧的 horizon，因此 time-indexed 版本存在循环依赖，第一版不实现，只保留为后续在已有较好 `H` 时的可选对照。

本次新增 `src/Basic/LocalHorizonCmaxSolver.java`。该类只作为实验工具，不接入 BPC 主流程。它包含两种求解方式：`solveWithCplex()` 实现 PDF 中的 2-index arc-flow MIP，支持 source arc、job-job arc、sink arc、release、initial setup、sequence setup、required arc、forbidden arc 和无向 adjacency pair；`solveWithCpOptimizer()` 实现 PDF 中的 CP Optimizer sequence 模型，通过 `typeOfNext` 处理 forbidden/required immediate successor 约束，通过 transition matrix 处理 setup。

命令行入口没有直接调用 `new Data(...)`，而是用了类内轻量 loader，只读取 header、任务行和 SETUP 矩阵。原因是当前 `Data.debug_set()` 仍会写死 `n=60,m=3,scale=3`，用它直接读取 40-2 文件会污染实验。主流程若已有正常构造完成的 `Data` 对象，仍可使用 `Problem.fromData(data)`。

短测使用 `data/40-2/wet040_001_2m.dat`，`setup=true,dueDate=true`，单线程。5 秒限制下，CPLEX arc-flow 找到 `Cmax=1881`，best bound 约 `928`；CP Optimizer 找到 `Cmax=1789`，best bound `928`。15 秒限制下，CPLEX arc-flow 找到 `Cmax=1847`，best bound 约 `928`；CP Optimizer 找到 `Cmax=1788`，best bound `928`。这个样本下 CP Optimizer 在同等时间内给出的可行 horizon 明显更好，而两者下界几乎相同，说明第一阶段比较重点应放在可行解质量和构造时间，而不是证明最优。后续若要接入 node 级 horizon，可以先优先试 CP Optimizer 版本，并只在得到的 `localHorizon` 明显小于当前 `pricingHorizon` 时启用。

补充实现并测试了 PDF 中的 time-indexed MIP。该模型需要预先给定离散 horizon，本次用当前启发式 local horizon `H=1927` 建图。即便如此，`wet040_001_2m` 已生成约 `1,680,705` 条 arc。单线程 5 秒和 15 秒限制下，CPLEX 均未找到可行解，状态为 `Unknown`，总耗时分别约 `9.95s` 和 `33.08s`，主要原因是建模和大规模 arc-time 变量本身过重。当前结论是：不加 MIP start、顶点压缩或更强图清理时，time-indexed MIP 不适合作为快速 local horizon 可行解来源；它更适合作为后续有较紧 `H` 且需要验证离散图模型时的对照。
