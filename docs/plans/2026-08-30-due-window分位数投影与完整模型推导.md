# 随机 due-window 并行机调度：分位数投影与完整模型推导

本文只处理当前主模型：任务特定 due window、场景自适应等待、线性 waiting cost、线性 earliness/tardiness、窗口位置成本和窗口宽度成本。等待始终允许，等待单价记为 $\eta$；免费等待就是令 $\eta=0$。这里不再先消去单个任务的等待，也不使用 $\min\{\alpha,\eta\}$ 的隔离任务公式。

整个推导按以下顺序进行：先写含 due-window 变量的完整 SAA 模型；固定实际完成时间后推出窗口端点的经验分位数；再把窗口变量从完整模型中投影掉；最后说明投影后的分段线性函数怎样精确求解。

## 1. 原始完整模型

任务集合为 $J$，SAA 场景集合为 $\Omega$，场景概率为 $p_\omega$，满足 $\sum_\omega p_\omega=1$。等概率 SAA 中 $p_\omega=1/|\Omega|$。

对任务 $j$，将 due window 写成

$$
[a_j,b_j]=[a_j,a_j+\ell_j],
$$

其中 $a_j\ge0$ 是窗口起点，$\ell_j\ge0$ 是窗口宽度。使用宽度变量 $\ell_j$ 只是为了后续对偶推导更清楚，与直接使用 $a_j,b_j$ 完全等价。

变量和成本含义如下：$x$ 表示机器分配与加工顺序；$C_{j\omega}$ 是已经包含场景等待后的实际完成时间；$W_{j\omega}\ge0$ 表示任务前的主动机器等待或相应 idle；$E_{j\omega},T_{j\omega}\ge0$ 分别表示相对 due window 的早到和晚到。窗口位置、宽度、早到、晚到和等待的单位成本分别记为 $\rho_j,\lambda_j,\alpha_j,\beta_j,\eta_j$。

若 $\eta_j>0$，所有能够把时间线向后移动的 initial idle 和任务间 idle 都必须由 $W$ 记录，timing 约束中不能另外留下未计费的自由 slack；否则模型仍可通过未计费 slack 绕开 waiting cost。下文默认 $\mathcal T$ 已满足这一计费闭合条件。

用 $\mathcal T(x)$ 表示由机器分配、顺序、场景加工时间和等待传播构成的 timing 可行域。它包含类似“后继任务开始时间等于前驱完成时间加主动等待”和“完成时间等于开始时间加场景加工时间”的约束。并行机、序列相关换型时间和不同的强位置 formulation 只会改变 $\mathcal T(x)$ 的具体写法，不改变下面的窗口投影。

完整确定性等价模型为

$$
\begin{aligned}
(P_0)\qquad
\min_{x,a,\ell,C,W,E,T}\quad
&c^{\mathrm{sch}}(x)
+\sum_{j\in J}(\rho_ja_j+\lambda_j\ell_j)\\
&+\sum_{\omega\in\Omega}p_\omega
\sum_{j\in J}
(\eta_jW_{j\omega}+\alpha_jE_{j\omega}+\beta_jT_{j\omega})\\
\text{s.t.}\quad
&(x,C,W)\in\mathcal T,\\
&E_{j\omega}\ge a_j-C_{j\omega}, &&j\in J,\omega\in\Omega,\\
&T_{j\omega}\ge C_{j\omega}-a_j-\ell_j, &&j\in J,\omega\in\Omega,\\
&a_j,\ell_j,E_{j\omega},T_{j\omega},W_{j\omega}\ge0.
\end{aligned}
$$

其中 $c^{\mathrm{sch}}(x)$ 可以是排程、换型或路径成本。若当前研究不需要某一项，只需把相应系数设为 0；特别地，免费等待就是 $\eta_j=0$。只要 $x$ 固定，$(P_0)$ 的其余部分是连续 LP。

## 2. 固定实际完成时间后的窗口子问题

现在固定一个可行的 $(x,C,W)$。此时 waiting cost 已经是常数，due-window 变量只出现在位置、宽度和 ET 成本中，而且不同任务之间完全分离。对任意任务 $j$，窗口子问题为

$$
\begin{aligned}
\Phi_j(\mathbf C_j)=
\min_{a_j,\ell_j,E_j,T_j}\quad
&\rho_ja_j+\lambda_j\ell_j
+\sum_{\omega\in\Omega}p_\omega
(\alpha_jE_{j\omega}+\beta_jT_{j\omega})\\
\text{s.t.}\quad
&E_{j\omega}\ge a_j-C_{j\omega},\\
&T_{j\omega}\ge C_{j\omega}-a_j-\ell_j,\\
&a_j,\ell_j,E_{j\omega},T_{j\omega}\ge0,
\end{aligned}
$$

其中

$$
\mathbf C_j=(C_{j\omega}:\omega\in\Omega)
$$

是任务 $j$ 的实际场景完成时间向量。由于 $E,T$ 的成本非负，它们在最优解中自动取最小可行值。因此令 $b_j=a_j+\ell_j$，可将窗口子问题写成

$$
\Phi_j(\mathbf C_j)
=\min_{0\le a_j\le b_j}
\left\{
\rho_ja_j+\lambda_j(b_j-a_j)
+\sum_{\omega\in\Omega}p_\omega
\left[
\alpha_j(a_j-C_{j\omega})^+
+\beta_j(C_{j\omega}-b_j)^+
\right]
\right\}.
$$

这一步只固定了实际完成时间，没有固定 earliest completion。等待已经通过 $C_{j\omega}$ 进入窗口子问题。

## 3. 左、右端点为什么是经验分位数

定义任务 $j$ 的加权经验分布函数

$$
\widehat F_j(z)=
\sum_{\omega\in\Omega}p_\omega
\mathbf 1\{C_{j\omega}\le z\},
$$

并用 $\widehat F_j(z^-)$ 表示严格小于 $z$ 的场景概率。

### 3.1 左端点

暂时假设 $a_j>0$ 且 $a_j<b_j$，即左端点下界和端点耦合约束均不活跃。保持 $b_j$ 不变时，与 $a_j$ 有关的项为

$$
(\rho_j-\lambda_j)a_j
+\alpha_j\sum_\omega p_\omega(a_j-C_{j\omega})^+.
$$

由于正部函数在 $a_j=C_{j\omega}$ 处不可微，其次梯度区间为

$$
\partial_{a_j}Phi_j
=
\rho_j-\lambda_j
+\alpha_j
[\widehat F_j(a_j^-),\widehat F_j(a_j)].
$$

最优条件 $0\in\partial_{a_j}\Phi_j$ 等价于

$$
\widehat F_j(a_j^-)
\le
\frac{\lambda_j-\rho_j}{\alpha_j}
\le
\widehat F_j(a_j).
$$

因此，内部最优左端点是加权经验分位数

$$
a_j^*\in
Q_j\!\left(\tau_j^a\right),
\qquad
\tau_j^a=\frac{\lambda_j-\rho_j}{\alpha_j}.
$$

这个比例的含义直接来自边际成本：把左端点向右移动会减少窗口宽度成本 $\lambda_j$，但会增加位置成本 $\rho_j$，并使位于左端点之前的场景产生更多 earliness。经验分位数条件正是这三项边际成本平衡的结果。

### 3.2 右端点

保持 $a_j$ 不变时，与 $b_j$ 有关的项为

$$
\lambda_jb_j
+\beta_j\sum_\omega p_\omega(C_{j\omega}-b_j)^+.
$$

其次梯度最优条件可写为

$$
\widehat F_j(b_j^-)
\le
1-\frac{\lambda_j}{\beta_j}
\le
\widehat F_j(b_j).
$$

因此，内部最优右端点是

$$
b_j^*\in
Q_j\!\left(\tau_j^b\right),
\qquad
\tau_j^b=1-\frac{\lambda_j}{\beta_j}.
$$

右端点的边际权衡是：右移 $b_j$ 会增加窗口宽度成本，但会减少位于右端点之后的场景 tardiness。

### 3.3 端点碰撞和边界

上述两个分位数公式要求分位水平有效，并且左端点不超过右端点。如果

$$
\tau_j^a\le\tau_j^b,
$$

则可以分别选择相应的左、右分位数；等概率 SAA 中，它们就是排序后完成时间向量中的相应 order statistics。

如果 $\tau_j^a>\tau_j^b$，两个独立端点的最优方向发生交叉，约束 $a_j\le b_j$ 将活跃，窗口收缩为点 $a_j=b_j=d_j$。此时宽度成本为 0，点窗口问题为

$$
\min_{d_j\ge0}
\left\{
\rho_jd_j
+\sum_\omega p_\omega
[\alpha_j(d_j-C_{j\omega})^+
+\beta_j(C_{j\omega}-d_j)^+]
\right\},
$$

其内部分位水平为

$$
d_j^*\in
Q_j\!\left(
\frac{\beta_j-\rho_j}{\alpha_j+\beta_j}
\right).
$$

若某个分位水平不在 $[0,1]$ 内，或者存在外生边界 $L_j\le a_j\le b_j\le U_j$，最优端点落在相应边界或端点碰撞位置。此时不应强行套内部公式，直接求解上面的窗口 LP 即可。有并列完成时间时，最优分位数可能是区间，但最优值不受影响。

### 3.4 waiting cost 为什么没有出现在分位数比例里

推导分位数时固定的是**实际完成时间** $C_{j\omega}$ 和已经发生的等待 $W_{j\omega}$。因此 $\eta_jW_{j\omega}$ 对窗口子问题是常数，不进入关于 $a_j,b_j$ 的边际条件。

这不表示 waiting cost 没有作用。它在完整模型的外层决定模型是否愿意通过等待改变 $C_{j\omega}$：$\eta_j>0$ 时，向后移动完成时间需要支付 waiting cost；$\eta_j=0$ 时，这一项直接消失。最终窗口仍然是**最优实际完成时间向量**的分位数，而实际完成时间向量会随 $\eta_j$ 改变。

因此，当前完整模型中不需要把 early 系数改成 $\min\{\alpha_j,\eta_j\}$。那个式子来自“单独消去一个不影响后续任务的等待量”，不适用于机器 idle 会向后传播的完整序列。

## 4. 消去 due-window 后，完整模型变成什么

对固定 $(x,C,W)$，不同任务的窗口子问题彼此独立，因此

$$
\min_{a,\ell,E,T}
\left\{
\sum_j(\rho_ja_j+\lambda_j\ell_j)
+\sum_{\omega,j}p_\omega
(\alpha_jE_{j\omega}+\beta_jT_{j\omega})
\right\}
=
\sum_j\Phi_j(\mathbf C_j).
$$

将这个最优值代回 $(P_0)$，得到只投影 due-window 变量后的等价模型

$$
\begin{aligned}
(P_1)\qquad
\min_{x,C,W}\quad
&c^{\mathrm{sch}}(x)
+\sum_{\omega,j}p_\omega\eta_jW_{j\omega}
+\sum_j\Phi_j(\mathbf C_j)\\
\text{s.t.}\quad
&(x,C,W)\in\mathcal T.
\end{aligned}
$$

推导 $(P_1)$ 时先固定 $C,W$ 再最小化窗口，只是对联合确定性等价模型做代数上的部分最小化，并没有改变随机决策时序。$a_j,\ell_j$ 始终没有场景下标，所有场景仍共用同一个承诺窗口；场景实现后可调整的只有 $C,W$。如果改成每个场景各有一组 $a_{j\omega},b_{j\omega}$，才会错误地把 due window 变成 wait-and-see 决策。

在 $(P_1)$ 中，$a_j,b_j,E_{j\omega},T_{j\omega}$ 已经从显式变量集合中消失，但 due-window 决策并没有被删除。它被包含在值函数 $\Phi_j$ 中。得到最优 $(x^*,C^*,W^*)$ 后，可通过分位数条件或重新求解每个任务的小型窗口 LP 恢复 $a_j^*,b_j^*$。

模型 $(P_1)$ 仍不是一个简单的任务可分问题。虽然 $\sum_j\Phi_j(\mathbf C_j)$ 在目标中按任务相加，但所有 $C_{j\omega}$ 通过 $\mathcal T$ 中的机器顺序和等待传播耦合。一次等待会改变同一机器上多个后续任务的完成时间，从而同时改变多个 $\Phi_j$。

如果进一步对固定排程 $x$ 把所有连续 timing 变量也投影掉，可定义

$$
Q(x)=
\min_{C,W:\,(x,C,W)\in\mathcal T}
\left\{
\sum_{\omega,j}p_\omega\eta_jW_{j\omega}
+\sum_j\Phi_j(\mathbf C_j)
\right\}.
$$

于是完整问题进一步写成

$$
(P_2)\qquad
\min_x
\left\{
c^{\mathrm{sch}}(x)+Q(x)
\right\}.
$$

$(P_2)$ 就是 2026 风格 projected Benders 所使用的结构：master 只处理离散排程 $x$ 和 recourse 下界变量 $\theta$，固定 $x$ 后的联合连续子问题计算 $Q(x)$。

## 5. 分位数函数是不是难建模的非线性

答案要分成“显式分位数映射”和“投影值函数”两部分。

若直接写

$$
a_j=Q_j(\tau_j^a),
\qquad
b_j=Q_j(\tau_j^b),
$$

而 $C_{j\omega}$ 又是决策变量，那么哪个场景位于第几个 order statistic 会随排程和等待改变。分位数映射是非光滑、可能集合值的；若强行把排序关系直接编码进 MILP，通常需要额外排序或选择逻辑。这种写法没有必要，也不是推荐实现。

但投影值函数 $\Phi_j(\mathbf C_j)$ 不是一般意义上的困难非线性。它是一个连续 LP 的最优值函数，因而是凸分段线性的。这个结论可以从窗口 LP 的对偶直接看出。

对任务 $j$，为约束

$$
E_{j\omega}-a_j\ge-C_{j\omega},
\qquad
T_{j\omega}+a_j+\ell_j\ge C_{j\omega}
$$

分别引入非负对偶变量 $u_{j\omega},v_{j\omega}$。窗口子问题的对偶为

$$
\begin{aligned}
\Phi_j(\mathbf C_j)=
\max_{u_j,v_j}\quad
&\sum_{\omega\in\Omega}
(v_{j\omega}-u_{j\omega})C_{j\omega}\\
\text{s.t.}\quad
&-\sum_\omega u_{j\omega}
+\sum_\omega v_{j\omega}\le\rho_j,\\
&\sum_\omega v_{j\omega}\le\lambda_j,\\
&0\le u_{j\omega}\le p_\omega\alpha_j,\\
&0\le v_{j\omega}\le p_\omega\beta_j.
\end{aligned}
$$

对偶可行域与 $\mathbf C_j$ 无关，所以 $\Phi_j$ 是一组关于 $\mathbf C_j$ 的线性函数的最大值：

$$
\Phi_j(\mathbf C_j)
=
\max_{r\in\mathcal R_j}
\left\{
\sum_\omega
(v_{j\omega}^{r}-u_{j\omega}^{r})C_{j\omega}
\right\},
$$

其中 $\mathcal R_j$ 可取对偶多面体的极点集合。因此 $\Phi_j$ 是凸分段线性函数，而不是黑箱非线性函数。

若在模型中真正消去 $a_j,b_j,E_j,T_j$，可以为 $\Phi_j$ 设置上图变量 $\theta_j$，并加入

$$
\theta_j\ge
\sum_\omega
(v_{j\omega}^{r}-u_{j\omega}^{r})C_{j\omega},
\qquad r\in\mathcal R_j.
$$

极点可能很多，因此不必预先全部枚举。给定当前完成时间向量后，求一次窗口 LP 或其对偶，得到当前最优 $(u^r,v^r)$，再加入一条切平面即可。这就是针对 $\Phi_j$ 的精确 cutting-plane/Benders 表示。

## 6. 三种精确求解方式的区别

### 6.1 直接确定性等价 MILP

保留 $(P_0)$ 中的 $a,\ell,E,T$。此时整个窗口部分完全线性，不需要显式计算或建模分位数。分位数只是最优解满足的结构性质。该模型最适合作为小规模正确性基准，也能检验 projected Benders 的最优值。

### 6.2 只投影窗口变量

使用 $(P_1)$，以 $\theta_j$ 和上面的对偶切平面表示每个 $\Phi_j$。这会真正从模型中删除显式 due-window 变量，但 timing 变量仍保留。由于每个任务的窗口 LP 本来很小，单独做这一层投影未必比直接扩展式更快；其主要价值是解释结构，而不是自动带来计算优势。

### 6.3 2026 风格联合 projected Benders

使用 $(P_2)$。master 只保留排程变量 $x$ 和 $\theta$；固定 $x$ 后，联合子问题同时优化所有场景的 $C,W$ 和共同的 due-window。实际实现时，子问题仍可保留 $a,\ell,E,T$ 作为线性辅助变量，因为它们是 $\Phi_j$ 的紧凑扩展式。这里所谓“消去 due window”是指它不在 master 中，而不是求解器内部永远不能出现这些变量。

固定 $x$ 后，联合子问题是 LP。若写成标准形式

$$
Q(x)=min_y\{q^\top y:Ay\ge h-Bx\},
$$

其对偶极点 $\pi^r$ 产生 Benders optimality cut

$$
\theta\ge(\pi^r)^\top(h-Bx).
$$

该路线保持 exact optimality proof。主要瓶颈是共同窗口把所有场景耦合在同一个 LP 中，以及不同对偶最优解可能产生强弱差异较大的 cuts。deepest Benders cuts 后续正是作用于这一层，而不是去直接编码经验分位数。

## 7. 当前建模结论

第一，窗口分位数必须基于包含等待后的实际完成时间 $C_{j\omega}$，不能预先使用 earliest completion。waiting cost 始终以 $\eta_jW_{j\omega}$ 留在 timing 目标中；$\eta_j=0$ 就得到免费等待版本。

第二，给定实际完成时间后，任务特定窗口可以逐任务投影，最优端点是经验分位数。投影后 due-window 变量从 $(P_1)$ 的显式变量中消失，但可从最优完成时间向量恢复。

第三，投影后的 $\Phi_j$ 是凸分段线性 LP 值函数。最稳妥的直接建模方式仍是保留 $a,\ell,E,T$；若希望从 master 中消去窗口，则通过 LP 对偶和 Benders cuts 表示，而不是显式建立 order-statistic 排序变量。

第四，多任务和多机器不破坏窗口投影，但 timing 仍然全局耦合。只有在任务窗口彼此独立、窗口只进入软 ET 成本、固定排程后的 timing recourse 为连续 LP 时，才能得到上述 $\sum_j\Phi_j$ 和 classical Benders 结构。若使用 common due window、跨任务窗口预算或 hard-window 可行性约束，窗口仍可联合投影，但不能再逐任务分解；若目标改为 early/tardy job 数量，固定排程后的子问题会含整数变量，不能直接使用上述 LP 对偶 cuts。

因此，当前建议是：先用 $(P_0)$ 建立确定性等价 MILP 基准，再以 $(P_2)$ 作为 2026 风格 projected Benders 主算法。不要为了使用分位数结论而显式建立排序变量；分位数用于证明和解释最优窗口，LP 扩展式与 Benders 对偶用于实际求解。
