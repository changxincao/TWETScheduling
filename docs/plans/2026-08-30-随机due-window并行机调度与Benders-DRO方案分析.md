# 随机 due-window 并行机调度与 Benders-DRO 方案分析

本文讨论一个从当前确定性 TWET 问题延伸出来的新问题：在加工时间或换型时间不确定时，先决定任务的机器分配和加工顺序，再根据实现的场景调整等待时间与完成时间，使期望 due-window 违约、加班及其他生产成本最小。分析主要参考 Çelik et al. (2025)、Cavaliere et al. (2026)、Hosseini and Turner (2024) 和 Avgerinos et al. (2025)，同时以当前仓库已经实现的“相同并行机、任务级 due window、序列相关 setup、允许主动空闲”为基础。本文是方案分析，不表示随机模型、Benders 或 DRO 已经实现，也没有新的计算实验结果。

## 1. 主要结论

这个新问题可以直接从多机器开始，没有必要先做单机再机械扩展。当前确定性问题本身已经是相同并行机：每台机器对应一条任务序列，主问题最多选择 (m) 条序列。随机扩展后，最自然的两阶段结构是：一阶段固定任务分配与顺序，二阶段在场景实现后调整每台机器上的等待、开始和完成时间。只要不确定性只进入加工时间、setup time、release time 等时间递推右端项，且二阶段目标采用经典 earliness/tardiness、加班、最大拖期或 CVaR 等凸分段线性指标，固定一阶段排程后的场景子问题就是连续 LP，适合经典 Benders、deepest Benders cut 和 RHS-Wasserstein DRO。

当前仓库原生实现的是确定性任务级 ET：`Data.buildBasePenaltyFunction()` 构造连续三段 earliness/tardiness 损失，`ArcFlowModel` 和现有 BPC 都没有 SAA、Wasserstein、Benders 或 local branching 实现。论文草稿中对一般 PWLF 目标的抽象，不表示代码已经原生支持 early/tardy work 或数量型目标。

建议的核心研究对象为“具有任务级 due window 和序列相关 setup 的两阶段随机相同并行机调度”。第一版先以总加权 earliness/tardiness 加机器级 overtime 为主目标，以场景 SAA 为基础，比较弧流 big-(M) 模型、位置/弧位置强模型以及专门的多割 Benders。deepest cut 和 selective local branching 应当作为在基础算法正确、稳定以后逐步加入的加速模块，而不是第一版同时堆入。Wasserstein-DRO 可以继续做，但它适合作为第二阶段工作或独立扩展：RHS 不确定性确实提供了可利用结构，却不等于自动得到一个简单 LP，有限支持、完整 recourse、对偶域和内层最坏分布分离都必须单独验证。

early work/tardy work 和早到/迟到任务数量可以研究，但不能与经典 ET 视为同一难度。前者是封顶的非凸分段线性损失，后者是阶跃损失；精确建模通常使二阶段带有离散变量或不连续语义。它们适合做独立目标变体、LBBD/CP 子问题或局部邻域扩展，不适合直接宣称仍可使用 2026 年论文的 LP-Benders 和标准 RHS-Wasserstein 推导。

## 2. 四篇核心文献中真正可迁移的机制

### 2.1 Çelik et al. (2025)：two-step Benders 与场景保留

该文研究随机旅行时间下的 time-window assignment TSP。一阶段同时含路径二进制变量 (x) 和连续 time-window assignment 变量 (y)，二阶段是连续的场景违约变量。其 two-step Benders 不是把同一组场景子问题简单求两次，而是先固定二进制 (x)，在聚合 LP (AP(x)) 中联合优化连续一阶段变量 (y) 与所有场景 recourse；随后把该 (y) 固定到各单场景子问题，生成比直接使用当前 master 中任意 (y) 更强的 multi-optimality cuts。论文还把一部分代表场景直接保留在 master，并用实际场景和人工凸组合场景增强下界。

对当前拟议问题，这一机制的适用性取决于 due window 是参数还是决策。若每个任务的 ([d_j^e,d_j^l]) 已给定，一阶段只有分配和排序二进制变量，不存在与 Çelik 等人相同的连续一阶段 (y)，two-step 的主要强化来源也随之消失。此时更应借鉴的是多割、代表场景进入 master、整数 incumbent 处分离和场景聚类，而不是照搬 two-step 名称。只有在进一步研究“生产商同时承诺/设计任务交付窗口”的变体、把窗口端点或窗口宽度作为连续一阶段变量时，Çelik 等人的 (x\rightarrow AP(x)\rightarrow y\rightarrow SP(x,y,\omega)) 两步结构才真正对口。

### 2.2 Cavaliere et al. (2026)：强位置模型与专门 Benders

该文同样研究随机旅行时间下的 time-window assignment TSP，但提出了不依赖 big-(M) 的弧-位置三指标模型。变量 (x_{ij}^p) 表示弧 ((i,j)) 位于路线位置 (p)，因此场景到达时间可按位置递推，旅行时间写成 \(\sum_{ij}t_{ij}^{\omega}x_{ij}^p\)，不再使用“若选弧则激活时间约束”的大 (M)。论文的专门 Benders 将 2-index 和 3-index 路径变量留在 master，把 time-window 和所有场景时间变量放入一个连续 subproblem；运行时先分离 subtour，再对当前解支持集上不可区分的场景进行聚合，最后由 LP 对偶生成最优性割。论文的数值结果表明，3-index 分离远强于 big-(M) 分离。

这一结构对多机调度很有价值。可令 (x_{ijmp}=1) 表示机器 (m) 在位置 (p) 从任务 (i) 转到任务 (j)，或用“任务-机器-位置”变量加相邻任务变量。场景时间递推可写成选中加工时间和 setup time 的线性组合；任务的 due-window 端点也可按当前位置所对应任务的变量线性选取。代价是变量规模从弧流的 (O(mn^2)) 上升到约 (O(mn^3))，并出现相同机器的对称性。因此它最适合作为小中规模强模型和 Benders cut generator，而不应未经实验就认定能直接替代大规模算法。

当前问题比 Cavaliere 等人的一个重要简化是 due window 固定且任务特定。固定窗口不跨场景耦合，故场景子问题天然可以分解并行，不必像论文那样为了投影 time-window 变量而把所有场景放在一个 LP 中。相反，论文的 support-based scenario aggregation 是否有效取决于不确定性结构：如果所有任务加工时间在每个场景都变化，两个场景很少会在当前排程支持集上完全相同；若不确定性是局部 breakdown、少量 setup arc 扰动或少量任务延误，聚合才可能像原文一样明显。

### 2.3 Hosseini and Turner (2024)：deepest Benders cuts

deepest cut 是经典 Benders cut 的选择机制。普通 Benders 在当前 master 点 ((\bar x,\bar\theta)) 处取一个最优对偶极点，但最优对偶解可能不唯一；deepest cut 在一个归一化后的分离问题中选择距离当前点最远的支撑超平面。论文用 \(\ell_p\) 范数定义 cut depth，并证明 cut 分离与把当前点投影到 recourse epigraph 之间存在对偶关系。其 Guided Projections Algorithm 利用这一关系反复引导投影和分离；\(\ell_1\) 与 \(\ell_\infty\) 版本通常比欧氏 \(\ell_2\) 版本更便于计算。该方法可以统一处理可行性割和最优性割，并可能在经典方法产生可行性割时直接得到最优性割。

对本问题，deepest cut 只应作用于“固定排程后的连续 LP recourse”这一层。它不是 SRI/rank-1 cut，也不是 local-branching 邻域 cut；若目标换成 number of tardy jobs 或精确 early/tardy work，使二阶段成为 MIP/非凸问题，就失去直接使用 LP 对偶 deepest cut 的基础。由于经典 ET 加软 overtime 具有完整 recourse，本问题通常不会频繁需要可行性割，deepest cut 的主要价值是改善最优性割方向和早期下界，而不是修复不可行场景。

第一版实验建议只比较普通 extreme-point cut、Pareto/Magnanti-Wong 类 cut 和 \(\ell_1\)-deepest cut。deepest separation 比普通单场景 LP 更贵，不应默认对每个场景、每个节点都执行；可只在根节点、聚合割或连续若干轮下界改善过小时触发，并记录“cut 数减少”是否真的抵消“单次分离变贵”。

### 2.4 Avgerinos et al. (2025)：把 local branching 变成邻域 supercut

该文研究具有序列相关且资源受限 setup 的 unrelated parallel machine scheduling，master 为 position-based MILP，subproblem 为 CP，目标包括总完成时间和总拖期。普通 Branch-and-Check 的 no-good/Benders cut 每次只删除一个排程，作者则围绕当前整数排程构造一个与其变量编码严格对应的 4-OPT 邻域，完整求出邻域内的最优排程，然后用一条 local-branching 补集约束删除整个已被证明无改进的邻域。为控制成本，论文使用轻量松弛 domination rule 跳过不可能改善 incumbent 的候选，并提出只在部分节点启动邻域求解的 selective local branching。

这一方法可迁移到 due-window 多机问题，但 cut 与邻域必须按本问题采用的变量编码重新证明。若 master 使用任务-位置-机器变量、弧变量或弧-位置变量，同一个“交换两个任务”的 move 会改变不同数量的二进制变量，不能直接把原文的 4-OPT 常数抄过来。保持精确性的条件也很严格：被删除的邻域必须在同一个 SAA/DRO 目标下被完整求到最优；若只抽样邻居、设置未闭合的时间限或使用近似场景值，只能把 local branching 当作 primal heuristic，不能添加删除整个邻域的 exact supercut。

因此建议分两层使用。第一层是临时加入邻域约束 (d(x,\bar x)\le k)，求一个较好 incumbent 后撤掉，作为不影响全局 exactness 的启发式。第二层才是 Avgerinos 式 exact neighbourhood solve：只有邻域已经闭合，并证明其最优值不优于更新后的 incumbent，才添加 (d(x,\bar x)\ge k+1)。在多场景问题中，邻域求解本身很重，优先采用 selective 策略，并先从同机内部 swap 开始。

## 3. 建议的问题定义与两阶段信息结构

令 \(\mathcal J=\{1,\ldots,n\}\) 为任务集合，\(\mathcal M=\{1,\ldots,m\}\) 为相同并行机集合。任务 (j) 具有固定 due window \([d_j^e,d_j^l]\)、早到/迟到单位成本 \(\alpha_j,\beta_j\)。一阶段在不确定参数实现前决定任务分配和每台机器上的相对顺序。场景 \(\omega\) 实现后，二阶段决定主动等待、开始时间、完成时间和加班量。第一版可令随机向量包含 \(p_j^\omega\) 和/或 \(s_{ij}^\omega\)，而 setup cost \(\kappa_{ij}\) 仍保持确定。

该模型隐含一个必须写进论文的观察假设：二阶段 timing 决策可以基于完整场景。若真实生产中加工时间逐个揭示，场景特定的所有等待时间会使用未来信息，两阶段模型相当于“班次开始前已知当天状态”或一个 wait-and-see 近似。若要描述加工过程中的逐步揭示，需要多阶段随机规划、滚动重调度或非预见策略，不能把当前两阶段 SAA 直接称为完全动态模型。

多机器不会破坏上述结构。机器分配和顺序都在一阶段，因此不同场景共享同一排程；每个场景仅重新优化各机器时间线。为避免把研究范围一次扩得过大，第一篇工作建议先做相同并行机。unrelated machines 只需把加工时间扩展为 \(p_{jm}^\omega\)，但变量、对称性、数据和邻域结构都会明显增加，可作为后续泛化。

## 4. SAA 模型

### 4.1 弧流 big-\(M\) 基线

令 \(x_{ijm}=1\) 表示机器 (m) 上任务 (j) 紧随 (i)，以虚拟源点 0 和汇点 (n+1) 表示机器序列首尾；\(u_{jm}\) 表示任务分配，\(v_m\) 表示机器是否启用。master 包含每个任务恰有一个前驱和一个后继、源汇流、机器启用、分配链接和消除子环的约束。

对场景 \(\omega\)，二阶段可写为

\[
\begin{aligned}
Q_\omega(x)=\min\;&
\sum_{j\in\mathcal J}\left(\alpha_jE_{j\omega}+\beta_jT_{j\omega}\right)
+\sum_{m\in\mathcal M}\gamma_m O_{m\omega},\\
\text{s.t. }&S_{j\omega}\ge C_{i\omega}+s_{ij}^{\omega}
-M_{ij}^{\omega}(1-x_{ijm}),\\
&C_{j\omega}=S_{j\omega}+p_j^{\omega},\\
&E_{j\omega}\ge d_j^e-C_{j\omega},\qquad
T_{j\omega}\ge C_{j\omega}-d_j^l,\\
&R_{m\omega}\ge C_{i\omega}+s_{i,n+1}^{\omega}
-M_{i,n+1}^{\omega}(1-x_{i,n+1,m}),\\
&O_{m\omega}\ge R_{m\omega}-H_m,\\
&S,C,E,T,R,O\ge0.
\end{aligned}
\]

这里 (H_m) 为正常班次结束时间，\(O_{m\omega}\) 为机器级加班。若允许无限软加班且 ET 也是软惩罚，任何无环一阶段排程都有可行 timing，故二阶段具有完整 recourse，算法主要生成最优性割。SAA 确定性等价模型为

\[
\min_{x\in X}\left\{c^\top x+\frac1N\sum_{r=1}^NQ(x,\xi^r)\right\}.
\]

该模型最容易由当前 `ArcFlowModel` 扩展，也是必须保留的小规模正确性基准。缺点是时间递推和场景数量共同放大 big-(M) 松弛弱点，Benders cut 系数也会继承大 (M) 的数值问题。

### 4.2 位置/弧-位置强模型

为模仿 Cavaliere 等人的 3-index 思路，可令 \(x_{ijmp}=1\) 表示机器 (m) 的第 (p) 个转移为 (i\to j)。场景完成时间按机器和位置递推：

\[
C_{m,p,\omega}\ge C_{m,p-1,\omega}
+\sum_{i,j}\left(s_{ij}^{\omega}+p_j^{\omega}\right)x_{ijmp}.
\]

位置 (p) 上任务的窗口端点可由 \(\sum_{i,j}d_j^e x_{ijmp}\) 和 \(\sum_{i,j}d_j^l x_{ijmp}\) 选出，再定义位置早到和迟到变量，从而避免在时间递推中使用大 (M)。需要为不足 (n) 个任务的机器引入 dummy/empty position，或使用连续填充和对称性约束。该模型的优势是 LP relaxation 和由其生成的 Benders cut 更强，代价是变量规模、机器对称性和实现复杂度更高。

建议把两种模型明确命名为 `SAA-AF` 和 `SAA-POS`，避免与当前论文中的外包 SP1/SP2 混淆。实验先比较根松弛、节点数、数值稳定性和场景规模扩展，再决定主算法是否只保留 POS 模型。

### 4.3 专门多割 Benders

最小 master 可写为

\[
\min\ c^\top x+\frac1N\sum_{r=1}^N\theta_r,
\qquad x\in X,
\]

其中 \(\theta_r\) 低估场景 (r) 的 timing recourse。固定当前 \(\bar x\) 后，各场景 LP 返回对偶解 \(\pi_r\)，生成

\[
\theta_r\ge \pi_r^\top h_r-\pi_r^\top T_r x.
\]

基础算法先使用场景 multi-cut；当 (N) 很大时再测试 single-cut、固定场景 bundle 和动态聚类。cut 应先在根节点和整数 incumbent 处分离，记录 fractional separation 是否带来足够 bound 收益，再决定是否扩大。subtour/assignment 等纯组合约束应先分离，只有当前解满足排程结构时才求昂贵的场景 recourse，这与 Cavaliere 等人的回调顺序一致。

场景保留有两种不同含义，不能混写。一是 Çelik 等人的 partial Benders：选少量代表场景，将其完整 recourse 变量和约束放进 master，以增强初始下界；二是 Cavaliere 等人的 incumbent-support aggregation：在当前排程实际使用的随机参数上相同的场景临时合并，只减少本次 subproblem。两者都必须做消融，并与随机选场景、无保留/无聚合比较。

## 5. deepest cut 与 local branching 的组合方式

推荐算法不是把两种 cut 混成一个公式，而是让它们处理不同层次：

1. master 给出候选排程 \(\bar x\)；
2. 连续场景 recourse 由普通或 deepest Benders separation 生成全局有效最优性割；
3. 得到可行排程和真实 SAA 值后更新 incumbent；
4. 仅在触发条件满足时，围绕该排程解一个受限邻域问题；
5. 若邻域已被完整证明不含更优解，更新 incumbent 并添加 local-branching 补集 supercut。

deepest cut 负责“在许多有效 LP 对偶割中选方向更好的一个”；local-branching cut 负责“在组合排程空间中一次删除一个已完整检查的邻域”。前者是连续 recourse 的全局下界强化，后者是排程邻域的组合枚举压缩。对 classic ET/overtime 模型，可以同时存在；对 early/tardy count 等 MIP recourse，只能保留 local branching/LBBD 层，deepest LP cut 至多来自一个松弛。

selective local branching 的触发条件可先设为：当前整数解未改善 incumbent、全局 gap 已进入中后期、距离上一次邻域求解已有若干节点，并且邻域松弛下界可能改善 incumbent。邻域规模从同机内部 swap 开始，再考虑跨机 relocation 或 swap。所有 exact supercut 都要保存邻域定义、求解状态、邻域最优值和 incumbent 关系，防止时间限下误删未闭合邻域。

## 6. Wasserstein-DRO 扩展

### 6.1 基本形式

令训练样本为 \(\widehat\xi^1,\ldots,\widehat\xi^N\)，经验分布为 \(\widehat{\mathbb P}_N\)。以半径 \(\varepsilon\) 的 Wasserstein 球 \(\mathcal P_N(\varepsilon)\) 表示可能分布，则模型为

\[
\min_{x\in X}
\left\{
c^\top x+
\sup_{\mathbb P\in\mathcal P_N(\varepsilon)}
\mathbb E_{\mathbb P}[Q(x,\xi)]
\right\}.
\]

对经典 ET/overtime，若加工和 setup duration 仅进入时间递推 RHS，二阶段矩阵和目标系数保持确定，\(Q(x,\xi)\) 是一个固定 recourse LP 的最优值。这正是 RHS-Wasserstein 两阶段线性 recourse 的可利用结构。但 Esfahani and Kuhn 的有限重构需要枚举二阶段对偶多面体的顶点，顶点数一般可能指数增长；Gamboa et al. 因而针对矩形支持提出 CCG、single-cut Benders 和 multi-cut Benders，而不是把它当作一次普通 LP 就结束。

### 6.2 必须先验证的条件

第一，支持集应有物理含义。加工时间和 setup time 非负，最好采用由历史/工艺给出的有界箱或低维因子支持，而不是默认无界欧氏空间。第二，软 ET 和软 overtime 应保证相对完整 recourse；若加入硬 due window、硬班次上限或不可违约服务约束，就会出现不可行 recourse，需要可行性处理。第三，要证明或构造有限有效的对偶界，避免最坏分布 oracle 中出现无界乘子。第四，若使用 early/tardy work 且加工时间随机，其封顶值 \(p_j^\omega\) 也随机，已不再是单纯 RHS 不确定性；数量型目标更直接产生整数 recourse。

本问题的 ET 是双侧损失，不能假定“持续时间越大越坏”。更短的加工/setup 时间可能增加 earliness，更长的时间可能增加 tardiness 或 overtime；因此有界支持的最坏点必须同时考虑下端、样本附近和上端。只检查上界端点会漏掉合法的最坏场景。

### 6.3 推荐求解路线

第一版 DRO 建议固定一个已经验证的 SAA-POS/BD master，在外层保留排程 (x)，内层使用精确 Wasserstein recourse oracle。对于矩形支持和 scaled-\(\ell_1\) 距离，可先实现 CCG：给定 (x) 和 Wasserstein 对偶参数，oracle 为每个经验样本寻找最坏支持点/对偶极点，若发现违反则加入对应的 affine recourse piece。single-cut 与 multi-cut Benders 作为对照。只有 oracle 返回全局最优并给出证书时，外层 bound 才能称为 DRO 精确界。

若随机向量包含所有 \(s_{ij}\)，维度达到 (O(n^2))，Wasserstein 距离既统计稀疏又使 oracle 昂贵。更合理的第一版是随机 \(p_j\)、机器/班次公共速度因子，或少量 family/setup shock；由这些原始因子生成所有持续时间，并保留样本内相关性。不要对每个任务或每条 setup arc 各自建立独立 Wasserstein 球，否则会破坏相关结构并改变模型含义。

半径和尺度只能用训练数据选择。建议先按任务/因子尺度标准化，再用训练内部的 holdout 或交叉验证选择 \(\varepsilon\)，用全部训练样本重求最终决策，最后在独立 OOS 场景上比较 SAA 和 DRO。需要报告平均成本、标准差、CVaR/高分位、早到/窗内/迟到比例、加班分布以及求解证书；不能用同一 OOS 样本反向选择半径。

## 7. 目标函数逐项可行性

| 目标 | 精确定义示例 | 固定排程后的 recourse | 经典 Benders / deepest | RHS-Wasserstein | 建议定位 |
|---|---|---|---|---|---|
| 总加权 ET | \(\sum_j\alpha_j[d_j^e-C_j]^++\beta_j[C_j-d_j^l]^+\) | 凸 PWL，LP | 最适合 | 最适合的主目标 | 主模型 |
| 总拖期 | \(\sum_j\beta_j[C_j-d_j]^+\) | 凸 PWL，LP | 适合 | 适合 | 简化基准 |
| overtime | \(\sum_m\gamma_m[R_m-H_m]^+\) | 凸 PWL，LP | 适合 | 适合 | 建议与 ET 同时做 |
| makespan / 最大拖期 | \(\max_j C_j\)、\(\max_j[C_j-d_j]^+\) | 加 epigraph 后 LP | 适合 | 可做 | 服务/产能副指标 |
| CVaR | 场景总 ET 或 overtime 的 CVaR | 通过 \(\eta,z_\omega\) 线性化 | 可做，\(\eta\) 使场景轻度耦合 | 可做但推导更复杂 | 风险厌恶扩展 |
| early/tardy work | \(Q_j=\min\{p_j,[d_j^e-S_j]^+\}\)、\(Y_j=\min\{p_j,[C_j-d_j^l]^+\}\) | 封顶非凸 PWL；精确模型通常需 segment binary/SOS2 | 不能直接使用 LP 对偶；可 LBBD/CP | 非纯 RHS，尤其 \(p_j\) 随机时 | 独立目标扩展 |
| 早到/迟到任务数量 | \(\sum_jw_j^E\mathbf1\{C_j<d_j^e\}+w_j^T\mathbf1\{C_j>d_j^l\}\) | 阶跃、不连续，通常为 MIP recourse | 用 LBBD/no-good/邻域 cut，不是 classical/deepest | 标准期望 recourse 不直接适用；可改做 DR chance constraint | 不建议与主算法第一版混做 |
| 窗内任务数 | 最大化 \(\sum_j\mathbf1\{d_j^e\le C_j\le d_j^l\}\) | 同样需要二进制分类 | 同上 | 同上 | 管理指标或双目标 |
| time-window width | \(\sum_j(d_j^l-d_j^e)\) | 它是一阶段窗口设计成本，不是固定窗口下的 recourse | 若窗口为决策，2025 two-step 才对口 | 可做但成为另一个问题 | 后续“承诺窗口设计”变体 |

early/tardy work 的含义要特别注意。对不可抢占任务，late work 是落在 \(d_j^l\) 之后的加工量 \(\min\{p_j,[C_j-d_j^l]^+\}\)，early work 是落在 \(d_j^e\) 之前的加工量 \(\min\{p_j,[d_j^e-S_j]^+\}\)，不是普通 earliness/tardiness。其函数先线性变化、随后封顶，作为最小化损失一般不是凸函数。当前连续 PWLF evaluator 在确定性固定序列下可以数值评价这类函数，并不等于其 SAA 场景子问题仍是 LP，更不等于可以直接从 LP 对偶生成 Benders cut。

加班是最值得从 2026 年文章借用的新目标。建议以机器级正常班次 (H_m) 为界，定义最后完成时间 \(R_{m\omega}\) 和线性加班量 \(O_{m\omega}\)。如果想研究“是否启用加班班次”或分段加班费，可把班次启用放到一阶段、实际小时放到二阶段；但一旦加班涉及共享工人数量或离散班次，subproblem 可能由 LP 变为资源受限 MIP/CP，这时 local branching/LBBD 的价值会上升。

## 8. 建议的算法与实验路线

第一阶段只做 SAA-ET-overtime，并明确使用相同并行机、固定任务级 due window、随机加工时间和确定 setup；然后逐步加入随机 setup。至少比较以下方法：

1. `SAA-AF`：弧流 big-\(M\) 确定性等价模型，作为最小正确性基线；
2. `SAA-POS`：位置/弧-位置强确定性等价模型，比较模型差异；
3. `BD-Classic`：基于强模型的 ordinary multi-cut Benders；
4. `BD-Deep`：只改变 cut selection 的 \(\ell_1\)-deepest 版本；
5. `BD-LB`：在经过验证的 BD 上加入启发式 local branching；
6. `BD-SLB-Exact`：只在邻域闭合时加入 selective supercut 的精确版本。

每个增强都做逐项消融：big-\(M\) 对位置模型、single/multi/bundle cut、普通/Pareto/deepest cut、无场景保留/随机保留/聚类保留、无 local branching/启发式/selective exact。不能只与求解器默认参数比较，也不能把“cut 数少”直接等同于“算法更快”。至少记录 root bound、root time、cut 数、单次 separation 时间、master/subproblem 时间占比、incumbent 时间、最终 gap 和最优性证书。

SAA 统计验证应使用多次独立训练样本。对每个样本规模 (N) 生成若干独立 SAA replication，得到训练最优值的均值/方差和候选排程；再用一个远大于 (N) 的独立 OOS 样本评价候选，报告平均值与置信区间。Cavaliere 等人用 5000 场景完整集检查 50--1000 个样本的目标估计，并把固定一阶段解放回完整场景集重估，这一“固定决策后大样本复评”应保留；若没有可枚举的完整场景宇宙，则使用标准独立 replication/OOS 设计代替。

当前 BPC 也可作为第三类长期候选：一条机器序列的 SAA 成本等于各场景最优 timing 成本的平均值，理论上仍可作为列成本。但精确定价状态需要同时处理大量场景的 PWLF/对偶信息，可能比紧凑 Benders 更难；在没有可信的多场景 dominance 和 pricing bound 前，不建议把“把 evaluator 在所有场景上跑一遍再平均”当作完整 BPC 实现。

## 9. 推荐研究顺序与论文边界

### 9.1 第一篇/主线工作

研究“随机加工时间下、具有任务级 due window 和序列相关 setup 的相同并行机 SAA”。主目标采用 expected weighted ET + overtime。贡献集中为：强位置模型、专门多割 Benders、场景处理、deepest cut 选择和 selective local branching。即使最终 deep/local 的计算收益有限，强模型与 Benders 的结构比较仍是完整主线。

### 9.2 第二层扩展

在同一模型上做 Wasserstein-DRO，先限制为低维 RHS 因子不确定性、矩形支持和连续 recourse，比较 CCG、single-cut 和 multi-cut，并用严格 OOS 评估鲁棒性。这个扩展不应与第一版所有目标同时交叉，否则算法层和统计层都很难归因。

### 9.3 目标函数专题

将 early/tardy work 和数量型目标单列。early/tardy work 可研究 position master + CP/MIP timing subproblem + LBBD/local branching；早到/迟到任务数可研究加权窗外任务数、服务水平或 chance constraint。若想保留 LP-Benders 主线，更稳妥的做法是把这些指标作为 OOS 后处理或 epsilon-constraint 的服务约束，而不是替换主 recourse 目标。

### 9.4 不建议的第一步

不建议一开始同时做 unrelated machines、随机 \(p_{jm}\)、随机全部 \(s_{ij}\)、early/tardy count、Wasserstein-DRO、deepest cut 和 exact 8-OPT local branching。这样即使数值失败，也无法判断失败来自模型、统计维度、cut、邻域还是 recourse 非凸性。也不建议把 2025 two-step 直接命名为本问题主方法，除非确实把 due-window 端点设为连续一阶段决策。

## 10. 当前待确认事项

1. 不确定性在班次开始前一次揭示，还是随加工逐步揭示；这决定两阶段模型是否与业务语义一致。
2. 第一版随机参数是加工时间、setup time，还是低维共同因子；建议先用加工时间或共同速度因子。
3. overtime 是每台机器独立线性成本、工厂总加班，还是共享人员/离散班次；只有前两者自然保持 LP recourse。
4. due window 继续作为给定参数，还是研究同时设计承诺窗口；后者才直接对应 2025 two-step Benders。
5. 主论文是否只保留 ET + overtime；建议 early/tardy work 和数量型目标先做单独计算可行性试验，再决定是否进入同一篇论文。

## 参考文献

- Çelik, Ş., Martin, L., Schrotenboer, A. H., and Van Woensel, T. (2025). Exact Two-Step Benders Decomposition for the Time Window Assignment Traveling Salesperson Problem. *Transportation Science*, 59(2), 210--228. https://doi.org/10.1287/trsc.2024.0750
- Cavaliere, F., Fischetti, M., Roberti, R., and Salvagnin, D. (2026). Models and algorithms for the Time Window Assignment Traveling Salesperson Problem with stochastic travel times. *European Journal of Operational Research*, 329(1), 96--111. https://doi.org/10.1016/j.ejor.2025.07.034
- Hosseini, M., and Turner, J. (2025; published online 2024). Deepest Cuts for Benders Decomposition. *Operations Research*, 73(5), 2591--2609. https://doi.org/10.1287/opre.2021.0503
- Avgerinos, I., Mourtos, I., Vatikiotis, S., and Zois, G. (2025). One Benders cut to rule all schedules in the neighbourhood. *European Journal of Operational Research*, 323(1), 62--85. https://doi.org/10.1016/j.ejor.2024.12.009
- Esfahani, P. M., and Kuhn, D. (2018). Data-driven distributionally robust optimization using the Wasserstein metric: Performance guarantees and tractable reformulations. *Mathematical Programming*, 171, 115--166. https://doi.org/10.1007/s10107-017-1172-1
- Gamboa, C., Homem-de-Mello, T., Street, A., and Valladão, D. (2021). A novel solution methodology for Wasserstein-based data-driven distributionally robust problems. *Optimization Online*. https://optimization-online.org/2020/10/8069/
- Duque, D., Mehrotra, S., and Morton, D. P. (2022). Distributionally Robust Two-Stage Stochastic Programming. *SIAM Journal on Optimization*, 32(3), 1499--1522. https://doi.org/10.1137/20M1370227
- Sterna, M. (2021). Late and early work scheduling: A survey. *Omega*, 104, 102453. https://doi.org/10.1016/j.omega.2021.102453
- Shabtay, D., Mosheiov, G., and Oron, D. (2022). Single machine scheduling with common assignable due date/due window to minimize total weighted early and late work. *European Journal of Operational Research*, 303(1), 66--77. https://doi.org/10.1016/j.ejor.2022.02.017
