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

## 2026-06-28 子节点 branch/pricingOnly 禁弧诊断

新增诊断开关，在 node 被取出、构建 LP 前计算 local horizon。该诊断只输出结果，不修改 `pricingHorizon`。构造模型时把当前 node 的 `isArcForbidden(from,to)` 与 `isPricingOnlyArcForbidden(from,to)` 合并为 forbidden arc，把 `getRequiredArcs()` 写入 required arc，机器数使用当前 node 的 `maxMachineCount`。这样可以观察 root 求解后传到子节点的 branch 约束与 pricingOnly subtree 禁弧对 local Cmax 的影响。

测试仍使用 `data/40-2/wet040_001_2m.dat`，配置为 ng-DSSR nearestK8/top10、ALNS、allCycles completion bound、pricingOnly subtree、midpoint probe/reuse、dual bound pruning、route enumeration，`maxNodes=3`。root 分支后的两个子节点诊断如下。

5 秒限制下：

1. node 2：`forbidden=1152`，其中 `pricingOnly=1151`，required arc 为 0。CPLEX 得到 `Cmax=1845`，bound 约 `934.40`；CP Optimizer 得到 `Cmax=1829`，bound `928`。
2. node 3：`forbidden=1165`，其中 `pricingOnly=1088`，required arc 为 1。CPLEX 得到 `Cmax=1838`，bound 约 `934.39`；CP Optimizer 得到 `Cmax=1820`，bound `928`。

15 秒限制下：

1. node 2：CPLEX 得到 `Cmax=1842`，bound 约 `934.43`；CP Optimizer 仍为 `Cmax=1829`，bound `928`。
2. node 3：CPLEX 得到 `Cmax=1833`，bound 约 `936.70`；CP Optimizer 得到 `Cmax=1812`，bound `928`。

和 root 无禁弧的 CP 结果 `1786~1788` 相比，子节点 CP 的可行 Cmax 明显变大，说明 pricingOnly/branch 禁弧确实收紧了可行顺序集合，不能直接把 root 的 local horizon 继承给子节点。和启发式 `1927` 相比，子节点 CP 仍能把 horizon 降到 `1812~1829`，说明该诊断方向仍有价值。CPLEX 在子节点上有时比 root 无禁弧时更容易找到较好解，主要是禁弧让搜索空间变小，但同时间内仍不如 CP。当前判断是：若后续真正接入 node local horizon，优先使用 CP Optimizer，并且必须按当前 node 的 branch/pricingOnly 状态单独计算。

## 2026-06-28 60-2 root 初始 local horizon 诊断

继续测试 `data/60-2/wet060_001_2m.dat` 的初始无分支 local horizon。注意该文件头为 `60 2`，但当前 `Data.debug_set()` 会把 `m` 和 `scale` 覆盖，不能直接用 `Data` 构造后的 `CmaxE/CmaxH` 对比 local horizon；本次对比统一使用 `LocalHorizonCmaxSolver` 的轻量读取口径，即文件头中的 2 台机器、原始整数 processing/due/setup。

按 `SchedulerForReleaseNoWait` 的构造启发式逻辑复现同口径启发式，得到 `Cmax=3300`。在 60 秒、单线程限制下，CPLEX arc-flow 找到 `Cmax=3255`，best bound 约 `2380`，相对 MIP gap 约 `26.88%`；CP Optimizer 找到 `Cmax=3096`，best bound `2380`，相对 gap 约 `23.13%`。相对启发式，CPLEX 只缩小 `45`，约 `1.36%`；CP Optimizer 缩小 `204`，约 `6.18%`。当前结论是：60-2 root 上 CP Optimizer 仍能在 1 分钟内给出比简单构造启发式更紧的 horizon，但模型 gap 仍较大，说明它更适合作为快速可行上界来源，而不是证明 horizon 最优。

后续按日志和入口代码复核，确认当前 `GCBBFullDomainComparisonTest` 的 60-2 主求解不是直接 `new Data(path,...)`，而是调用 `TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine()`。该 loader 先创建一个 base `Data` 对象，再读取目标文件头，并显式执行 `data.n=n; data.m=m`，因此会把 `Data.debug_set()` 的 `m=3` 覆盖回文件头的 `m=2`。日志也直接印证这一点：`wet060_001_2m` 多次运行 root 记录均为 `machine=[0,2]`，`Initial columns=75, incumbent columns=2`；`wet060_002_2m` root 同样为 `machine=[0,2]`。所以此前“60-2 主求解可能实际是 60-3”的判断是错误的；已运行的这些 `GCBBFullDomainComparisonTest` 60-2 日志按 2 台机器解释。仍需注意的是，若后续有代码直接用 `new Data("data/60-2/...", ...)` 而不经过 Tanaka loader，则会受到 `debug_set()` 干扰。

补充澄清 60-2 中“5000 多 horizon”的口径。这里确实出现过两个 5000 以上的数值，但含义不同。`LocalHorizonCmaxSolver` 在 60-2 root 诊断中打印的 `horizonUB=5683` 是建模时使用的保守时间上界，不是模型求出的可行 `Cmax`；同一次 60 秒求解中，CPLEX arc-flow 找到 `Cmax=3255`，CP Optimizer 找到 `Cmax=3096`。另外，BPC 主求解日志里也出现过 `pricingHorizon=5727.0`，这是某次 node 4 exact pricing heartbeat 的当前定价 horizon；而同一类配置的 root pricing 中也有 `pricingHorizon≈2659~2663` 的记录。因此后续比较 local horizon 和 BPC pricing horizon 时必须区分：`horizonUB` 是诊断模型安全上界，`pricingHorizon` 是当前定价使用的上界，CP/CPLEX 的 `Cmax` 才是 local horizon 模型实际找到的可行完工上界。

进一步复核后确认，`horizonUB=5683` 来自当前 `LocalHorizonCmaxSolver` 的粗公式 `maxRelease + totalProcessing + n * maxSetup`。在 `wet060_001_2m` 上具体为 `maxRelease=2356`、`totalProcessing=2427`、`maxSetup=15`、`n*maxSetup=900`，合计 `5683`。这个上界没有使用 `SchedulerForReleaseNoWait` 构造出的启发式 `Cmax=3300`，因此只是安全但偏松的建模上界。若后续要真正把 local horizon 接入 BPC，root 情况下应优先使用已知可行启发式上界 `min(保守上界, 启发式Cmax)`；非根节点则只有在启发式构造能够满足当前 branch/pricingOnly 禁弧与 required arc 时才能使用该 node-local 启发式上界，否则仍需回退到保守上界或重新构造可行 schedule。

进一步确认当前主求解是否使用该 local horizon。`TWETBPCConfig.diagnosticLocalHorizonAtNode` 默认是 `false`，`GCBBFullDomainComparisonTest` 只通过 `twet.bpc.fullDomainCompare.localHorizonDiag` 读取它；60-2 已跑日志的 `command.txt` 没有设置该参数。即使该诊断开关打开，`Tree.diagnoseLocalHorizonAtNode()` 也只是构造 `LocalHorizonCmaxSolver.Problem` 并打印 CPLEX/CP 结果，不会写回 `Data.CmaxH`，也不会修改 pricing engine 内部的 `pricingHorizon`。因此此前 60-2 主求解没有使用 CP/CPLEX local horizon 收缩；它使用的是数据加载后的 `Data.CmaxH`、pricing 内部的 dynamic profitable window，以及 midpoint/probe 等已有机制。

再进一步区分 `Data.setImprovedCmax()` 的启发式收缩。普通 `Data` 构造函数会在 `setCmax()` 后调用 `setImprovedCmax()`，用 `SchedulerForReleaseNoWait` 构造一个 release/no-wait 调度，并在改进时设置 `CmaxE=heuristicCmax`、`CmaxH=1.1*CmaxE`。但当前 60-2 主求解入口不是直接使用这个构造结果，而是 `TanakaNoOutsourcingBPCTest.loadTanakaMultiMachine()` 先加载 base `Data`，再覆写目标算例的任务和 setup，最后显式执行 `data.CmaxH = computeSafeHorizon(data); data.CmaxE = data.CmaxH;`。因此 base `Data` 里曾经做过的 `setImprovedCmax()` 对目标 60-2 数据已经失效，目标 60-2 本身也没有重新调用该启发式。以 `wet060_001_2m` 为例，Tanaka loader 的安全上界为 `maxDue + sumP + n*maxSetup + 20 = 2380 + 2427 + 900 + 20 = 5727`，这正对应日志中的 `pricingHorizon=5727.0`。这说明 60-2 主求解目前没有使用启发式构造得到的 `Cmax=3300` 或按当前主线习惯放宽后的 `3630`，后续若要保持和普通 `Data` 口径一致，应在 Tanaka loader 覆写完目标算例后重新调用等价的 `setImprovedCmax()`，再重建 hard window 和 penalty functions。
