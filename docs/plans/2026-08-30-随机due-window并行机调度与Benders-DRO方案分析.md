# 决策型 due-window 随机并行机调度：SAA、Benders 与 Wasserstein-DRO 方案

本文分析的新问题不是“给定 due window 后做随机调度”，而是：在随机加工时间或换型时间实现以前，同时决定任务的机器分配、加工顺序以及每个任务的 due window；场景实现以后，再调整允许的等待、开始和完成时间。本文只讨论与 early/tardy（ET）直接相关的目标，包括完成时刻的 earliness/tardiness、early work/tardy work，以及早到/迟到任务数量。机器没有明确的班次或加班语义，因此不把 overtime 放入模型。

主要参考 Çelik et al. (2025) 的 two-step Benders、Cavaliere et al. (2026) 的强位置模型与投影式 Benders、Hosseini and Turner 的 deepest Benders cuts，以及 Avgerinos et al. 的 local-branching neighbourhood supercut。本文是模型与算法方案，不表示这些随机模型或算法已经在当前代码中实现。

## 1. 问题边界与最重要的建模判断

可以直接研究相同并行机，没有必要先把单机作为最终问题。第一阶段决定每个任务由哪台机器加工、在该机器上的位置或前后继关系，以及任务特定的 due window。所有场景共享同一个排程和同一组窗口；第二阶段只根据场景调整时间线。因此，多机器增加的是第一阶段的组合难度，并不破坏两阶段结构。

不建议模仿 Cavaliere et al. 给机器添加 overtime。该项在原文中对应车辆/司机的正常工作时长，具有明确业务含义；如果机器没有班次结束、加班班次或额外人工成本，硬加一个 overtime 只会改变原问题。

不过，原文的 overtime 还隐含地限制了把所有窗口无限向后移动。当前问题去掉 overtime 后，必须用真实业务约束锚定窗口位置。否则，在允许主动等待且场景集合有界时，模型可能把每个窗口设成很晚的零宽窗口，并在较短场景中插入等待，使所有场景恰好在承诺时刻完工，从而得到“窗口宽度为零、ET 也为零”的平凡解。

推荐对每个任务给出可承诺区间 [L_j,U_j]，并要求

$$
L_j \le a_j \le b_j \le U_j,
$$

其中 [a_j,b_j] 是模型决定的 due window。若订单数据中有目标交期，也可以把可承诺区间设为目标交期附近允许调整的范围。只有一个非常宽的全局计划期 H 往往不够强，仍可能允许模型把窗口整体推到计划期末端。

窗口宽度也必须有明确语义。可以采用两种版本：

1. 固定或分档宽度：b_j=a_j+w_j，模型只决定窗口位置；
2. 可变宽度：对 b_j-a_j 收取成本，或给出上下界。

若宽度可任意扩大而没有成本，ET 可以通过扩大窗口被消除；若只惩罚宽度却不限制位置，又会出现前述“零宽窗口后移”的退化。因此，“位置可承诺范围”和“宽度控制”要同时存在。宽度成本属于 due-window assignment 本身，不是另加的生产绩效目标。

## 2. 两阶段 SAA 模型骨架

令 J 为任务集合，M 为相同并行机集合，Ω_N 为训练样本中的 N 个场景。场景 ω 给出加工时间 p_j^ω，以及需要时的序列相关换型时间 s_ij^ω。第一阶段变量包括排程变量 x 和任务窗口端点 a_j、b_j。x 可以采用弧流变量，也可以采用任务—机器—位置及弧—位置变量。

固定排程与窗口后，每个场景决定开始时间 S_jω、完成时间 C_jω、早到量 E_jω 和迟到量 T_jω：

$$
C_{j\omega}=S_{j\omega}+p_j^\omega,
$$

$$
E_{j\omega}\ge a_j-C_{j\omega},\qquad
T_{j\omega}\ge C_{j\omega}-b_j,\qquad
E_{j\omega},T_{j\omega}\ge 0.
$$

机器顺序由 x 决定，并在每个场景中满足相应的加工与换型递推。模型允许场景实现后插入主动等待。若真实生产中加工时间是逐个揭示的，这种场景特定等待使用了完整场景信息，属于两阶段 wait-and-see 近似；论文中应明确解释为班前已获得状态信息，或承认它是滚动执行策略的下界模型。

推荐的主目标为

$$
\min
\lambda\sum_{j\in J}(b_j-a_j)
+\frac{1}{N}\sum_{\omega\in\Omega_N}\sum_{j\in J}
\left(\alpha_jE_{j\omega}+\beta_jT_{j\omega}\right).
$$

若窗口宽度固定，则删除第一项。这里不加入 overtime，也不再扩展其他绩效目标。确定性的换型成本是否进入目标可按原问题语义决定，但不应把它包装成本文的目标创新。

只要采用经典 ET，且随机加工/换型时间只进入时间递推右端项，固定第一阶段决策后的场景 recourse 是连续 LP。软 ET 又通常保证相对完整 recourse，因此基础算法主要生成 Benders 最优性割。

## 3. 两条应当直接比较的 Benders 路线

### 3.1 2025 风格：窗口显式留在 master 的 two-step Benders

这条路线与本问题完全对口，因为第一阶段确实同时含有离散排程 x 和连续窗口变量 (a,b)。master 保留 x、a、b 及 recourse 估计变量。对一个候选排程 x̄，先固定 x̄，求一个跨全部场景的聚合 LP：

$$
AP(\bar x)=\min_{a,b,\text{全部场景 timing}}
\left\{\lambda\sum_j(b_j-a_j)+
\frac1N\sum_\omega ET_\omega\right\}.
$$

该问题重新优化共同窗口，得到针对排程 x̄ 的最优窗口 (â,b̂)。第二步再固定 (x̄,â,b̂)，分别求各场景 timing LP，由场景对偶生成 multi-optimality cuts。其核心价值不是“把同一子问题求两次”，而是避免用 master 中一个尚未优化好的任意窗口生成弱割。

可以进一步借鉴原文的 scenario retention：把少量代表场景的完整 timing 结构直接放进 master，其余场景仍由 Benders 处理。代表场景应通过消融比较随机选择、聚类选择和不保留场景，不能只报告最好配置。

优点是窗口含义清楚、场景在第二步可以并行，并且最接近 Çelik et al. 的原始 two-step 逻辑。缺点是 master 同时带有窗口连续变量和大量场景割，规模和数值稳定性可能较差。

### 3.2 2026 风格：把共同窗口投影到一个联合连续子问题

这条路线的 master 只保留排程/位置二进制变量 x 和总 recourse 下界 θ。对候选排程 x̄，子问题联合优化所有任务的共同窗口 (a,b) 与全部场景的 timing：

$$
Q(\bar x)=\min_{a,b,\text{全部场景 timing}}
\left\{\lambda\sum_j(b_j-a_j)+
\frac1N\sum_\omega ET_\omega\right\}.
$$

窗口仍然是一阶段决策，因为所有场景只能使用同一组 (a,b)；这里只是在算法上把连续一阶段变量从 master 中投影掉。由联合 LP 的对偶生成只含排程变量的 Benders cut。

Cavaliere et al. 的另一项关键贡献是弧—位置强模型。对机器调度，可用 z_ijmp 表示机器 m 的第 p 个转移为 i→j，使场景位置时间通过被选中的 p_j^ω 和 s_ij^ω 直接递推，尽量避免弧流时间约束中的大 M。其代价是变量数量和相同机器对称性显著增加，需要连续占位、空位置处理和对称破除。

优点是 master 小、窗口被完整投影、位置模型通常比 big-M 弧流松弛更强。缺点是所有场景因共同窗口而耦合在一个较大的 LP 中，不能像窗口已固定时那样完全按场景分解。

### 3.3 怎样比较才不会把两个因素混在一起

建议把“模型强弱”和“分解边界”拆成两个实验因素：

1. formulation：弧流 big-M 与位置/弧—位置强模型；
2. decomposition：窗口留在 master 的 2025 two-step，与窗口投影到联合子问题的 2026 风格。

两条路线必须使用相同 SAA 样本、同样的窗口可承诺区间、同样的宽度规则和同样的 ET 权重。至少报告根松弛、根节点时间、master 与 subproblem 时间、割数量、首次可行解时间、最终 gap 和最优性证书。若只比较“2025 算法+弱模型”和“2026 算法+强模型”，无法判断差异来自 formulation 还是 decomposition。

第一版实现顺序建议为：确定性等价小规模基准；2026 风格强位置模型与投影 Benders；再实现 2025 two-step 作为结构对照。这样既符合用户希望“主要模仿 2026”，也保留两种模型差异的可解释实验。

## 4. 只分析三类 ET 目标

### 4.1 完成时刻 earliness/tardiness

定义

$$
E_{j\omega}=[a_j-C_{j\omega}]^+,\qquad
T_{j\omega}=[C_{j\omega}-b_j]^+.
$$

这是最推荐的主线。它直接描述完工时刻相对承诺窗口的偏离，符合 due-window 作为交付承诺的语义；损失是凸分段线性的，场景 recourse 保持 LP，因此 ordinary Benders、deepest cuts 和 RHS-Wasserstein 扩展都有清楚基础。可比较对称/非对称、任务加权及不同 λ，但不需要再引入其他目标。

### 4.2 early work/tardy work

对不可抢占任务 [S_jω,C_jω]，窗口前和窗口后的加工量分别为

$$
EW_{j\omega}=\min\{p_j^\omega,[a_j-S_{j\omega}]^+\},
$$

$$
TW_{j\omega}=\min\{p_j^\omega,[C_{j\omega}-b_j]^+\}.
$$

它与普通 ET 不同：ET 衡量完工时刻偏离多少，early/tardy work 衡量一项加工有多少时长落在窗口以外。若 due window 表示“希望完成的时间区间”，ET 的业务语义更自然；只有当窗口表示期望的加工/占用区间时，work 指标才特别有解释力。

精确函数包含截断 min。以 tardy work 为例，它随 C 先为 0、再线性增加、最后封顶为 p，斜率为 0→1→0；作为最小化损失不是凸函数。对当前不可抢占、允许调整 timing 的模型，精确线性化通常需要区段二元变量、SOS2 或 CP 逻辑，不能直接声称子问题仍是连续 LP。若 p_j^ω 也随机，封顶值本身随场景变化，不确定性也不再只是一个简单的 ET-RHS 结构。

因此该目标可以做，但应作为独立的非凸/混合整数 recourse 变体。适合 position master + MIP/CP timing subproblem、logic-based Benders、branch-and-check 和 local branching；不适合直接套用 deepest LP cuts。只有在额外条件使 timing 唯一、或采用凸近似时，才可能恢复较简单的连续模型，但那已经改变了原问题。

### 4.3 早到/迟到任务数量

定义

$$
N^E_\omega=\sum_j w_j^E\,\mathbf 1(C_{j\omega}<a_j),\qquad
N^T_\omega=\sum_j w_j^T\,\mathbf 1(C_{j\omega}>b_j).
$$

该指标回答“多少任务违约”，而不是“违约多严重”。它适合作为服务质量或公平性指标，但阶跃函数需要场景二元变量，并且必须明确 C=a 或 C=b 时是否算违约及数值容差。因此精确 recourse 通常是 MIP，不属于 2025/2026 连续 LP-Benders 的直接适用范围。

数量目标尤其依赖窗口边界控制：若窗口宽度不受约束，可以把所有任务包含在宽窗口内；若窗口位置不受约束，只计算迟到数量时可以把窗口无限后移。因此仍需相同的可承诺区间与宽度规则。

可以研究三种 ET 内部组合：仅最小化加权窗外任务数；先最小化窗外任务数、再以总 ET 打破平局；或用 ε-constraint 限制窗外任务数并最小化总 ET。后两种更能避免“很多任务只差一点”和“一个任务差很多”被数量目标视为相同。算法上应走 LBBD/MIP-recourse 路线，不应把它作为 deepest-cut 主线的同一个子问题。

综合判断如下：

| 指标 | 精确场景 recourse | deepest LP cut | Wasserstein RHS 主线 | 推荐定位 |
|---|---|---|---|---|
| 完成时刻 ET | 连续凸 PWL/LP | 适合 | 适合 | 第一篇主模型 |
| early/tardy work | 一般非凸，精确模型常需离散逻辑 | 不直接适合 | 不宜作为首个 DRO 版本 | 独立算法扩展 |
| 早到/迟到任务数 | 阶跃型 MIP recourse | 不适合 | 明显更重 | 服务指标或 LBBD 扩展 |

## 5. deepest cuts 与 local branching 放在哪里

deepest cuts 只作用于连续 LP recourse。普通 Benders 在对偶最优解不唯一时可能得到方向很差的割；deepest separation 通过归一化距离选择对当前 master 点切得更深的有效割。它最适合主线 ET 模型，可分别嵌入 2025 的场景 multi-cut 或 2026 的联合投影 cut。

deepest separation 本身比普通取一个对偶极点更贵，因此建议先比较 ordinary cut、Pareto/Magnanti-Wong 类 cut和 l1-deepest cut，再决定是否只在根节点或下界停滞时触发。窗口位置与时间变量尺度差异较大，做 deepest cut 前必须统一变量尺度和归一化，否则“更深”可能只是单位选择造成的。

local branching 作用于排程二进制变量，而不是 ET 连续变量。对 incumbent 排程 x̄，可临时加入 d(x,x̄)≤k，完整重新优化邻域内的 due windows 和所有场景 timing，以寻找更好的排程。若只是限时或抽样搜索，它只能作为 primal heuristic。

Avgerinos et al. 式 exact neighbourhood supercut 要求整个邻域已经被完整求解，并证明其中不存在优于 incumbent 的方案，才可以加入补集约束 d(x,x̄)≥k+1。相同并行机存在机器标签对称，邻域距离必须基于规范化排程或配合对称破除；原文针对特定位置编码的邻域常数不能直接照抄。

两者可以叠加但职责不同：deepest cut 强化连续 recourse 的全局下界；local branching 在组合排程空间寻找解，或删除已经完整证明的邻域。对 early/tardy work 和数量目标，deepest LP cut 失去直接基础，local branching/LBBD 反而更重要。

## 6. Wasserstein-DRO 是否能做

对主线 ET 模型可以做。若随机加工时间和换型时间只进入固定排程后的时间递推 RHS，且二阶段保持连续线性，recourse value 是关于 RHS 不确定参数的分段线性凸值函数。due-window 仍是分布实现前的共同决策；采用 2026 风格时，它与所有场景 timing 一起在联合连续层中优化，并不变成场景决策。

但“子问题连续、随机量在 RHS”并不意味着 Wasserstein-DRO 自动变成一个小 LP。最坏分布层仍需处理 Wasserstein 对偶、支持集和 recourse 对偶极点。推荐先限定有界物理支持，例如加工时间/换型因子的区间或低维箱集，并采用训练样本上的 scaled-l1 距离；再用 CCG 或 single/multi-cut 方法逐步生成最坏支持点和 recourse affine pieces。每次只有在内层 oracle 全局闭合时，外层下界才是可证明的精确界。

这一扩展首先只配完成时刻 ET。early/tardy work 的截断非凸性和随机上限、数量目标的二元 recourse 都会破坏“连续固定 recourse + RHS-Wasserstein”的干净结构，不能与 ET 版本共用一套推导。

实验顺序应为：同一窗口规则下的 SAA 基线；用训练内部验证选择 Wasserstein 半径；用全部训练样本重求决策；最后用独立大样本 OOS 比较 ET、窗口宽度和窗外任务比例。OOS 样本不能反向用于选择半径。

## 7. 推荐研究路线

第一阶段直接做相同并行机、任务特定的决策型 due window、随机加工时间和经典加权 ET。固定或惩罚窗口宽度，并用客户可承诺区间锚定窗口位置。先建立小规模确定性等价模型作正确性基准，再以 2026 风格的强位置 formulation + 投影式 Benders 为主算法，以 2025 two-step Benders 为结构对照。

第二阶段只在这个连续 ET 主模型上加入 deepest cuts 和 selective local branching，并逐项消融。local branching 先做限时 primal heuristic；只有邻域完整闭合后才测试 exact supercut。

第三阶段在同一 ET 模型上做 Wasserstein-DRO，先限制为低维、有界、RHS 型加工/换型不确定性。SAA 与 DRO 使用同一训练/OOS 协议。

early/tardy work 和早到/迟到任务数量分别形成两个独立目标变体。它们可以共享排程和窗口定义，但求解层应转向 MIP/CP recourse、LBBD 或 branch-and-check，不应为了“目标都做”而把它们与连续 LP-Benders 主线混成一个模型。

当前还需由实际业务数据确定的只有三点：每个任务允许承诺窗口的 [L_j,U_j]；窗口宽度是固定、分档还是连续付费；加工时间是在开工前整体揭示还是逐步揭示。这三点会改变模型语义，但不会改变“无 overtime、due window 是决策、主线只做 ET”的总体方向。

## 参考文献

- Çelik, Ş., Martin, L., Schrotenboer, A. H., and Van Woensel, T. (2025). Exact Two-Step Benders Decomposition for the Time Window Assignment Traveling Salesperson Problem. Transportation Science, 59(2), 210–228. https://doi.org/10.1287/trsc.2024.0750
- Cavaliere, F., Fischetti, M., Roberti, R., and Salvagnin, D. (2026). Models and algorithms for the Time Window Assignment Traveling Salesperson Problem with stochastic travel times. European Journal of Operational Research, 329(1), 96–111. https://doi.org/10.1016/j.ejor.2025.07.034
- Hosseini, M., and Turner, J. Deepest Cuts for Benders Decomposition. Operations Research. https://doi.org/10.1287/opre.2021.0503
- Avgerinos, I., Mourtos, I., Vatikiotis, S., and Zois, G. One Benders cut to rule all schedules in the neighbourhood. European Journal of Operational Research. https://doi.org/10.1016/j.ejor.2024.12.009
- Sterna, M. (2021). Late and early work scheduling: A survey. Omega, 104, 102453. https://doi.org/10.1016/j.omega.2021.102453
- Shabtay, D., Mosheiov, G., and Oron, D. (2022). Single machine scheduling with common assignable due date/due window to minimize total weighted early and late work. European Journal of Operational Research, 303(1), 66–77. https://doi.org/10.1016/j.ejor.2022.02.017
