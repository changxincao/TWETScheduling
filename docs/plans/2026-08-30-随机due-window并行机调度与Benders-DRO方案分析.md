# 决策型 due-window 随机并行机调度：SAA、Benders 与 Wasserstein-DRO 方案

本文分析的新问题不是“给定 due window 后做随机调度”，而是：在随机加工时间或换型时间实现以前，同时决定任务的机器分配、加工顺序以及每个任务的 due window；场景实现以后，再调整允许的等待、开始和完成时间。本文只讨论与 early/tardy（ET）直接相关的目标，包括完成时刻的 earliness/tardiness、early work/tardy work，以及早到/迟到任务数量。机器没有明确的班次或加班语义，因此不把 overtime 放入模型。

主要参考 Çelik et al. (2025) 的 two-step Benders、Cavaliere et al. (2026) 的强位置模型与投影式 Benders、Hosseini and Turner 的 deepest Benders cuts，以及 Avgerinos et al. 的 local-branching neighbourhood supercut。本文是模型与算法方案，不表示这些随机模型或算法已经在当前代码中实现。

关于窗口位置成本、范围约束、二阶段等待成本、宽度成本是否必要，以及局部投影如何扩展到 SAA 的专项核对，见[决策型 due window 的问题设定：文献对照、退化条件与 SAA 结构](2026-08-30-due-window问题设定文献调研与SAA结构分析.md)。从完整 SAA 模型推出经验分位数、消去窗口变量并得到 projected Benders 值函数的统一推导，见[随机 due-window 并行机调度：分位数投影与完整模型推导](2026-08-30-due-window分位数投影与完整模型推导.md)。当前研究边界已经明确：所有候选模型都允许场景内等待，只比较免费等待 FW 与付费等待 PW，不再考虑 no-voluntary-idle 版本。

## 1. 问题边界与最重要的建模判断

可以直接研究相同并行机，没有必要先把单机作为最终问题。第一阶段决定每个任务由哪台机器加工、在该机器上的位置或前后继关系，以及任务特定的 due window。所有场景共享同一个排程和同一组窗口；第二阶段只根据场景调整时间线。因此，多机器增加的是第一阶段的组合难度，并不破坏两阶段结构。

不建议模仿 Cavaliere et al. 给机器添加 overtime。该项在原文中对应车辆/司机的正常工作时长，具有明确业务含义；如果机器没有班次结束、加班班次或额外人工成本，硬加一个 overtime 只会改变原问题。

没有 overtime 本身不会造成模型错误。真正决定是否退化的是：窗口是否任务特定、场景实现后是否允许自适应等待，以及窗口位置是否完全自由。此前把“必须限制窗口位置”写成无条件结论过强，下面分三种情况说明。

### 1.1 任务特定窗口 + 场景自适应等待

在本文原先采用的两阶段 wait-and-see 语义下，多场景共享同一组窗口仍不足以产生权衡。固定一台机器上的序列 (j_1,\ldots,j_k)，可以递归选择共同目标完成时刻：

$$
d_{j_1}\ge \max_\omega p_{j_1}^\omega,
$$

$$
d_{j_r}\ge d_{j_{r-1}}+
\max_\omega\{s_{j_{r-1},j_r}^\omega+p_{j_r}^\omega\},
\qquad r=2,\ldots,k.
$$

在场景 ω 中令 S_{j_r,\omega}=d_{j_r}-p_{j_r}^\omega，并在需要时插入等待。上述递推保证前后继约束成立，而且所有场景都有 C_{j_r,\omega}=d_{j_r}。再令 a_j=b_j=d_j，就得到所有任务、所有场景的窗口宽度和 ET 同时为零。

因此，多场景之间虽然共享窗口，但各场景拥有自己的等待变量，仍可分别把时间线同步到同一个共同目标。此时对任意可行排程 x 都有 Q(x)=0；若有正的最小窗口宽度，Q(x) 也只是与排程无关的常数。Benders 只能得到 θ≥0 或常数割，随机 ET 不再影响机器分配和顺序。若只禁止初始等待、但允许后续等待，退化不会完全消失：通常只有每台机器的首任务保留场景差异，后续任务仍可逐个同步，排程决策会被大幅简化。

这时有三种不依赖 overtime、且仍允许等待的修正方式：

1. 给每个任务设置真实的可承诺区间 L_j≤a_j≤b_j≤U_j；
2. 对 initial 和任务间主动等待设置正单价 $\eta_j>0$；
3. 给窗口位置设置正成本，例如 $\rho_ja_j$，同时仍允许免费等待。

第一种只有在范围具有真实承诺含义时最自然；第二种是当前推荐的 PW 主模型；第三种形成与原 TWA 免费等待语义最接近、但用位置成本替代 overtime 的 FW 基准。主比较应让 FW 与 PW 使用相同的位置和宽度成本，只改变 $\eta=0$ 与 $\eta>0$。

### 1.2 含 waiting cost 的统一窗口投影

当前主模型直接保留场景 waiting cost $\eta_jW_{j\omega}$；免费等待只需令 $\eta_j=0$。不再先消去单个任务的等待，也不在完整模型中使用 $\min\{\alpha_j,\eta_j\}$ 替换 early 系数。

给定所有等待已经计入后的实际完成时间向量 $\mathbf C_j=(C_{j1},\ldots,C_{jN})$，任务 $j$ 的最优窗口成本为

$$
\Phi_j(\mathbf C_j)=
\min_{0\le a_j\le b_j}
\left\{
\rho_ja_j+\lambda_j(b_j-a_j)
+\frac1N\sum_\omega
\left[
\alpha_j(a_j-C_{j\omega})^+
+\beta_j(C_{j\omega}-b_j)^+
\right]
\right\}.
$$

在内部端点条件下，最优左、右端点分别是实际完成时间的加权经验分位数

$$
a_j^*\in Q_j\!\left(\frac{\lambda_j-\rho_j}{\alpha_j}\right),
\qquad
b_j^*\in Q_j\!\left(1-\frac{\lambda_j}{\beta_j}\right).
$$

若分位水平越界或交叉，则由非负边界、外生范围或 $a_j=b_j$ 的点窗口条件决定。waiting cost 不出现在上述比例里，是因为这里固定的是已经包含等待的实际完成时间；$\eta_j$ 通过外层 timing 优化改变 $\mathbf C_j$。

只要任务窗口彼此独立且不进入机器可行性约束，给定 $C$ 后该窗口投影可对所有任务同时使用。消去 $a,b,E,T$ 后，完整模型精确等价于

$$
\min_{x,\,C,\,W\in\mathcal T(x)}
\left\{
c(x)+\frac1N\sum_{\omega,j}\eta_jW_{j\omega}
+\sum_j\Phi_j(\mathbf C_j)
\right\}.
$$

$\Phi_j$ 是凸分段线性 LP 值函数，而不是需要显式编码的排序黑箱。直接确定性等价模型保留 $a,b,E,T$ 即为精确线性扩展式；2026 风格则进一步把 $a,b,C,W,E,T$ 投影成只关于排程 $x$ 的联合 recourse 值函数 $Q(x)$，再由 LP 对偶生成 Benders cuts。完整推导及窗口 LP 对偶见上述专项文档。

宽度仍需正成本、固定值或真实上界，否则可用覆盖所有实际完成时刻的宽窗口同时消掉 ET。免费等待时还需要位置成本或真实范围阻止整体时间平移。

### 1.2A 文献对照：无场景自适应等待时的分位数投影

本小节保留用于解释随机 DDA 文献的解析结构，但 no-wait 不属于当前问题的候选模型，也不进入后续实验比较。

如果固定排程 x 后，各场景完成时刻 C_j^\omega 已由 earliest-start 递推确定，那么不限制窗口位置通常没有问题。对每个任务 j，窗口优化是独立的一维/二维凸分段线性问题：

$$
\phi_j(x)=
\min_{a_j\le b_j}
\left\{
\lambda_j(b_j-a_j)
+\frac1N\sum_{\omega}
\left[
\alpha_j(a_j-C_j^\omega)^+
+\beta_j(C_j^\omega-b_j)^+
\right]
\right\}.
$$

忽略样本并列值时，若

$$
\lambda_j\le \frac{\alpha_j\beta_j}{\alpha_j+\beta_j},
$$

最优左、右端点分别是完成时间样本的经验分位数

$$
a_j^*\in Q_j\!\left(\frac{\lambda_j}{\alpha_j}\right),
\qquad
b_j^*\in Q_j\!\left(1-\frac{\lambda_j}{\beta_j}\right).
$$

若 λ_j 更大，宽度约束 a_j≤b_j 起作用，最优窗口收缩为一个点：

$$
a_j^*=b_j^*\in
Q_j\!\left(\frac{\beta_j}{\alpha_j+\beta_j}\right).
$$

有并列样本时，相应分位数可能是一个区间，但最优值仍可由排序后的完成时间直接计算。若窗口宽度固定为 w_j，则只需优化窗口起点 a_j，得到一个一维凸分段线性问题，也可通过完成时间的排序统计量求解。

因此，在无场景自适应等待的文献设定中，给定排程后 $C_j^\omega(x)$ 已经确定，可以同时把窗口和完成时间都代入，直接得到只关于排程的 $\phi_j(x)$。当前允许等待的模型仍可先投影窗口，但 $C,W$ 还要在 timing 可行域中联合优化；只有再把整个连续 timing LP 投影掉，才得到只关于排程的 $Q(x)$。

这里的“显式函数”不等于一个预先固定的线性系数。$C_j^\omega$ 随排程 $x$ 改变，各场景完成时间的排序也可能改变，所以 $\phi_j(x)$ 是由场景次序统计量形成的分段函数，并且仍然跨场景耦合。全局优化应保留窗口 LP 的线性扩展式，或使用其对偶 Benders cuts；没有必要显式建立 order-statistic 排序变量，也不能把 $N$ 个场景完全拆成彼此独立的 recourse。

宽度仍需有明确规则。若 λ_j=0 且宽度可变，任取 a_j≤min_ω C_j^ω、b_j≥max_ω C_j^ω 即可得到零 ET，只是存在许多无限宽或非唯一窗口；若宽度固定或 λ_j>0，上述分位数权衡才真正存在。

### 1.3 若所有任务共享一个 common due window

如果研究的不是任务特定窗口，而是所有任务共用同一个 [a,b]，前述“每个任务各设一个零宽目标”的构造不成立，因为同一台机器上的多个任务不可能同时完工。即使允许场景等待，任务之间仍会形成宽度—ET 权衡。不过模型可能存在整体时间平移对称：把排程和窗口一起后移不改变目标。此时固定首台启用机器的起始时刻或固定时间原点即可破除对称，不一定需要窗口上界或位置成本。

因此，是否需要 $[L_j,U_j]$ 不能脱离窗口类型和等待价格回答。当前任务特定窗口始终允许自适应等待：FW 免费等待必须配置正位置成本或真实范围；PW 付费等待在所有主动等待都计费时可由 waiting cost 提供位置斜率，但为保持 FW/PW 可比，主实验仍建议两者使用相同位置成本。

### 1.4 两篇核心论文有没有窗口位置区间

Çelik et al. (2025) 的主模型没有客户特定的外生位置区间 [L_i,U_i]。其约束 (4) 只要求窗口长度满足 y_i^e-y_i^s≥s_i，约束 (6) 只要求两个端点非负。车辆从给定时刻 t_0 出发；约束 (11) 用 o_ω≥w_jω+t_j0ω-T 计算超过班次长度 T 的 overtime，并在目标中以正权重 ψ 惩罚。因此，原文依靠 shift deadline 与 overtime 成本锚定窗口的日历位置，而不是依靠窗口上下界。

Cavaliere et al. (2026) 同样没有外生的 [L_i,U_i]。其 big-M 模型约束 (1d) 只要求 t_i^e-t_i^s≥0，端点属于非负实数；约束 (1g) 和 (1h) 分别设置 w_iω+t_i0ω≤T+o_ω 与固定出发时刻 w_0ω=0，目标中以 βo_ω 惩罚 overtime。3-index 模型也只要求窗口长度非负。

更直接的证据出现在 Cavaliere et al. 第 6.2.3 节。作者明确指出：当 driver shift duration T 足够大时，可以在访问每个客户前插入足够等待，使车辆即使在最坏场景下也恰好准时到达，从而不产生 earliness/lateness；这会形成不现实的实例，使真正相关的只剩 routing cost。因此，他们专门根据 ATSP 路线长度设置 T，避免该退化。

所以，两篇论文的逻辑是“自由窗口位置 + 自适应等待 + overtime 锚点”，不是“自由窗口位置 + 多场景本身自动形成权衡”。若机器问题删除 overtime，又保留另外两项，就已经删掉了原模型防止时间整体后移/等待同步的关键机制，必须由业务区间、正位置成本或正 waiting cost 等真实时间价值替代。

### 1.5 “设置 due window 有成本”为何仍可能退化

两篇论文都有 time-window assignment cost，但该成本是窗口宽度的正线性成本，不是“只要设置窗口就收费”，也不是“窗口越晚越贵”。Çelik et al. (2025) 的目标为

$$
\sum_{i,j}d_{ij}x_{ij}
+\sum_i\sigma(y_i^e-y_i^s)
+\sum_\omega p_\omega
\left[
\sum_i\phi(e_{i\omega}+l_{i\omega})
+\psi o_\omega
\right].
$$

四项依次为 routing/distance、窗口宽度、期望 earliness/lateness 和期望 overtime。由于约束 y_i^e-y_i^s≥s_i，最窄窗口长度为服务时间 s_i。若删除 overtime 并利用等待同步各场景，可以令窗口保持最小长度，宽度成本变成固定常数 σ∑_i s_i，随机 ET 为零，排程目标只剩 routing cost 加该常数。

Cavaliere et al. (2026) 的目标为

$$
\sum_{i,j}d_{ij}x_{ij}
+\alpha\sum_i(t_i^e-t_i^s)
+\sum_\omega p_\omega
\left[
\beta o_\omega
+\delta\sum_i(e_{i\omega}+l_{i\omega})
\right].
$$

四项同样是 routing、窗口宽度、overtime 和 ET。该模型允许零宽窗口，所以同步构造可使 width cost、ET cost 和（T 足够大时的）overtime cost全部为零，最终只剩 routing cost。这正是第 6.2.3 节所说的退化。

例如固定路线 0→1→2→0，有两个场景：

| 场景 | 0→1 | 1→2 | 2→0 |
|---|---:|---:|---:|
| ω1 | 3 | 8 | 2 |
| ω2 | 6 | 4 | 5 |

选择客户1的共同零宽窗口 [6,6]、客户2的共同零宽窗口 [14,14]。场景 ω1 在到达客户1前等待3，随后用8到达客户2；场景 ω2 准时在6到达客户1，随后旅行4并等待4。于是两个场景都在6和14访问客户，width=0、ET=0；返回仓库时间分别为16和19。若 T≥19，则 overtime=0，只剩该路线的 routing cost。若 T<19，等待同步会产生 overtime，模型才需要在较早窗口造成的ET与较晚窗口造成的overtime之间权衡。

因此，不同“窗口成本”的作用必须区分：

| 成本形式 | 能否阻止自由窗口向后移动 |
|---|---|
| 正的宽度成本 λ(b-a) | 不能；它反而鼓励零宽窗口 |
| 每个任务固定的窗口设置费 κ_j | 不能；所有任务都必须设置时只是常数 |
| 最小宽度或固定宽度 | 不能单独阻止；同步后通常只留下常数 |
| 窗口中心偏离客户目标日期的成本 | 可以 |
| 窗口位置越晚越贵的成本 | 可以 |
| 有限且有业务含义的允许区间 [L_j,U_j] | 可以 |
| 正的场景等待成本 | 可以；移动窗口会增加等待或 early 成本 |

所以，“有 due-window 成本”并不足以否定退化构造，必须看成本是否对窗口的绝对位置收费。两篇论文的 width cost 不对位置收费；真正承担位置锚定作用的是 shift duration T 和 overtime。不能反过来说作者只是为了数学上防退化才设置 overtime：它首先具有司机班次与运营成本的真实业务含义，只是在数学结构上同时避免了自由窗口与自适应等待把时间成本完全消掉。2026 年论文明确讨论了这一双重作用，2025 年论文主要按业务成本建模，但结构效果相同。

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

机器顺序由 $x$ 决定，并在每个场景中满足相应的加工与换型递推。两个候选版本都允许场景实现后插入主动等待，区别仅在价格。FW 令 $\eta_j=0$，并用正位置成本或真实范围锚定日历位置；PW 令 $\eta_j>0$，对所有能够主动后移时间线的等待计费。为形成干净的 FW/PW 比较，主实验应让两者使用相同的位置成本和宽度成本，只改变 $\eta$。若 PW 另做“无位置成本”消融，则 initial idle 也必须计费，否则每台机器仍可免费整体后移。

推荐的主目标为

$$
\min
\sum_{j\in J}\left[\rho_ja_j+\lambda_j(b_j-a_j)\right]
+\frac{1}{N}\sum_{\omega\in\Omega_N}\sum_{j\in J}
\left(\alpha_jE_{j\omega}+\beta_jT_{j\omega}+\eta_jI_{j\omega}\right).
$$

其中 $I_{j\omega}$ 为相对最早可执行时刻的主动等待。FW 取 $\eta_j=0$，PW 取 $\eta_j>0$；等待变量和 timing 约束在两个版本中完全相同。若窗口宽度固定，则删除宽度成本；若用真实承诺范围替代位置成本，可删除 $\rho_ja_j$。这里不加入 overtime。确定性的换型成本是否进入目标可按原问题语义决定，但不应把它包装成本文的目标创新。

只要采用经典 ET，且随机加工/换型时间只进入时间递推右端项，固定第一阶段决策后的场景 recourse 是连续 LP。软 ET 又通常保证相对完整 recourse，因此基础算法主要生成 Benders 最优性割。

## 3. 两条应当直接比较的 Benders 路线

### 3.1 2025 风格：窗口显式留在 master 的 two-step Benders

这条路线与本问题完全对口，因为第一阶段确实同时含有离散排程 x 和连续窗口变量 (a,b)。master 保留 x、a、b 及 recourse 估计变量。对一个候选排程 x̄，先固定 x̄，求一个跨全部场景的聚合 LP：

$$
AP(\bar x)=\min_{a,b,\text{全部场景 timing}}
\left\{\sum_j[\rho_ja_j+\lambda_j(b_j-a_j)]+
\frac1N\sum_\omega (ET_\omega+WAIT_\omega)\right\}.
$$

这里 $WAIT_\omega=\sum_j\eta_jI_{j\omega}$；FW 中其系数为 0，PW 中为正。后续 2026 风格联合子问题使用相同记号。

该问题重新优化共同窗口，得到针对排程 x̄ 的最优窗口 (â,b̂)。第二步再固定 (x̄,â,b̂)，分别求各场景 timing LP，由场景对偶生成 multi-optimality cuts。其核心价值不是“把同一子问题求两次”，而是避免用 master 中一个尚未优化好的任意窗口生成弱割。

可以进一步借鉴原文的 scenario retention：把少量代表场景的完整 timing 结构直接放进 master，其余场景仍由 Benders 处理。代表场景应通过消融比较随机选择、聚类选择和不保留场景，不能只报告最好配置。

优点是窗口含义清楚、场景在第二步可以并行，并且最接近 Çelik et al. 的原始 two-step 逻辑。缺点是 master 同时带有窗口连续变量和大量场景割，规模和数值稳定性可能较差。

### 3.2 2026 风格：把共同窗口投影到一个联合连续子问题

这条路线的 master 只保留排程/位置二进制变量 x 和总 recourse 下界 θ。对候选排程 x̄，子问题联合优化所有任务的共同窗口 (a,b) 与全部场景的 timing：

$$
Q(\bar x)=\min_{a,b,\text{全部场景 timing}}
\left\{\sum_j[\rho_ja_j+\lambda_j(b_j-a_j)]+
\frac1N\sum_\omega (ET_\omega+WAIT_\omega)\right\}.
$$

窗口仍然是一阶段决策，因为所有场景只能使用同一组 (a,b)；这里只是在算法上把连续一阶段变量从 master 中投影掉。由联合 LP 的对偶生成只含排程变量的 Benders cut。

Cavaliere et al. 的另一项关键贡献是弧—位置强模型。对机器调度，可用 z_ijmp 表示机器 m 的第 p 个转移为 i→j，使场景位置时间通过被选中的 p_j^ω 和 s_ij^ω 直接递推，尽量避免弧流时间约束中的大 M。其代价是变量数量和相同机器对称性显著增加，需要连续占位、空位置处理和对称破除。

优点是 master 小、窗口被完整投影、位置模型通常比 big-M 弧流松弛更强。缺点是所有场景因共同窗口而耦合在一个较大的 LP 中，不能像窗口已固定时那样完全按场景分解。

### 3.3 怎样比较才不会把两个因素混在一起

建议把“模型强弱”和“分解边界”拆成两个实验因素：

1. formulation：弧流 big-M 与位置/弧—位置强模型；
2. decomposition：窗口留在 master 的 2025 two-step，与窗口投影到联合子问题的 2026 风格。

两条路线必须使用相同 SAA 样本、相同的 timing 语义、同样的窗口位置/宽度规则和同样的 ET 权重。至少报告根松弛、根节点时间、master 与 subproblem 时间、割数量、首次可行解时间、最终 gap 和最优性证书。若只比较“2025 算法+弱模型”和“2026 算法+强模型”，无法判断差异来自 formulation 还是 decomposition。

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

数量目标尤其依赖窗口语义。若窗口宽度不受约束，可以把所有场景包含在宽窗口内；若只计算迟到数量且窗口位置自由，可以把窗口无限后移。若同时计算早到和迟到数量、宽度固定或付费，并对场景等待收费，则不一定需要位置上界，但目标仍是阶跃型 MIP recourse。

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

第一阶段直接做相同并行机、任务特定的决策型 due window、随机加工时间和经典加权 ET，所有版本均保留场景自适应等待。以 PW 为主模型：正位置成本、正宽度成本和正 waiting cost 同时存在；以相同模型中的 $\eta=0$ 形成 FW 免费等待基准。PW 再增加一个删除位置成本的消融，用于验证付费等待能否单独锚定位置，但该消融不与 FW 直接比较。

先建立小规模确定性等价模型作正确性基准，再以 2026 风格的强位置 formulation + 投影式 Benders 为主算法，以 2025 two-step Benders 为结构对照。主模型始终保留 $\eta W$，固定实际完成时间后投影窗口得到 $\Phi_j(\mathbf C_j)$；完整序列的等待传播和所有场景 timing 由联合 LP 处理。

第二阶段只在这个连续 ET 主模型上加入 deepest cuts 和 selective local branching，并逐项消融。local branching 先做限时 primal heuristic；只有邻域完整闭合后才测试 exact supercut。

第三阶段在同一 ET 模型上做 Wasserstein-DRO，先限制为低维、有界、RHS 型加工/换型不确定性。SAA 与 DRO 使用同一训练/OOS 协议。

early/tardy work 和早到/迟到任务数量分别形成两个独立目标变体。它们可以共享排程和窗口定义，但求解层应转向 MIP/CP recourse、LBBD 或 branch-and-check，不应为了“目标都做”而把它们与连续 LP-Benders 主线混成一个模型。

当前已经确定机器允许为了 due window 主动等待。下一步只需确定主实验的 waiting 单价档位、initial idle 是否按同一单价计费，以及窗口宽度是固定、分档还是连续付费。建议先用 $\eta/\alpha\in\{0,0.1,0.5,1,2\}$；若 PW 保留位置成本，则 initial idle 可按业务决定，若 PW 删除位置成本，则 initial idle 必须计费。这些选择不改变“无 overtime、due window 是决策、主线只做 ET”的总体方向。

## 参考文献

- Çelik, Ş., Martin, L., Schrotenboer, A. H., and Van Woensel, T. (2025). Exact Two-Step Benders Decomposition for the Time Window Assignment Traveling Salesperson Problem. Transportation Science, 59(2), 210–228. https://doi.org/10.1287/trsc.2024.0750
- Cavaliere, F., Fischetti, M., Roberti, R., and Salvagnin, D. (2026). Models and algorithms for the Time Window Assignment Traveling Salesperson Problem with stochastic travel times. European Journal of Operational Research, 329(1), 96–111. https://doi.org/10.1016/j.ejor.2025.07.034
- Hosseini, M., and Turner, J. Deepest Cuts for Benders Decomposition. Operations Research. https://doi.org/10.1287/opre.2021.0503
- Avgerinos, I., Mourtos, I., Vatikiotis, S., and Zois, G. One Benders cut to rule all schedules in the neighbourhood. European Journal of Operational Research. https://doi.org/10.1016/j.ejor.2024.12.009
- Sterna, M. (2021). Late and early work scheduling: A survey. Omega, 104, 102453. https://doi.org/10.1016/j.omega.2021.102453
- Shabtay, D., Mosheiov, G., and Oron, D. (2022). Single machine scheduling with common assignable due date/due window to minimize total weighted early and late work. European Journal of Operational Research, 303(1), 66–77. https://doi.org/10.1016/j.ejor.2022.02.017
