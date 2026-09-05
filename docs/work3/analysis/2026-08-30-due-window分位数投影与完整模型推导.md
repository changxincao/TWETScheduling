# 随机 due-window 并行机调度：分位数投影与完整模型

本文只推导当前主模型：任务特定 due window、场景自适应等待、线性 waiting cost 和线性 earliness/tardiness。等待始终允许；令 \(\eta_j=0\) 即得到免费等待版本。

## 1. 符号与假设

| 符号 | 定义 |
|---|---|
| \(J\) | 任务集合，索引为 \(j\) |
| \(\Omega\) | SAA 场景集合，索引为 \(\omega\) |
| \(p_\omega\) | 场景概率，\(\sum_{\omega\in\Omega}p_\omega=1\)；等概率 SAA 中 \(p_\omega=1/|\Omega|\) |
| \(x\) | 一阶段机器分配与加工顺序变量 |
| \(\mathcal T(x)\) | 给定排程 \(x\) 后，所有场景的加工、前后继和等待传播可行域 |
| \(a_j\) | 任务 \(j\) 的窗口起点 |
| \(\ell_j\) | 窗口宽度，因而 \(b_j=a_j+\ell_j\) |
| \(w_{j\omega}\) | 场景 \(\omega\) 中任务 \(j\) 前的主动等待 |
| \(C_{j\omega}\) | 已包含全部等待后的实际完成时间 |
| \(e_{j\omega},t_{j\omega}\) | 相对窗口的 earliness 和 tardiness |
| \(\rho_j,\lambda_j\) | 窗口位置和宽度的单位成本 |
| \(\alpha_j,\beta_j,\eta_j\) | earliness、tardiness 和 waiting 的单位成本 |
| \(\mathbf C_j\) | 任务 \(j\) 的场景完成时间向量 \((C_{j\omega}:\omega\in\Omega)\) |

若 \(\eta_j>0\)，所有能推迟时间线的 initial idle 和任务间 idle 都必须计入 \(w\)；timing 约束不能另留未计费的自由 slack。并行机、序列相关换型时间或强位置 formulation 只改变 \(\mathcal T(x)\)，不改变下面的窗口投影。

## 2. 含 due-window 的完整 SAA 模型

原始模型为

\[
\begin{aligned}
(P_0)\qquad
\min\quad
&c^{\mathrm{sch}}(x)
+\sum_{j\in J}\bigl(\rho_ja_j+\lambda_j\ell_j\bigr)\\
&+\sum_{\omega\in\Omega}p_\omega
\sum_{j\in J}
\bigl(\eta_jw_{j\omega}+\alpha_je_{j\omega}+\beta_jt_{j\omega}\bigr)\\
\text{s.t.}\quad
&(x,C,w)\in\mathcal T,\\
&e_{j\omega}\ge a_j-C_{j\omega},
&&j\in J,\ \omega\in\Omega,\\
&t_{j\omega}\ge C_{j\omega}-a_j-\ell_j,
&&j\in J,\ \omega\in\Omega,\\
&a_j,\ell_j,e_{j\omega},t_{j\omega},w_{j\omega}\ge0.
\end{aligned}
\]

\(c^{\mathrm{sch}}(x)\) 表示排程、换型或路径成本。模型 \((P_0)\) 是 MILP；固定离散排程 \(x\) 后，其余部分是连续 LP。免费等待只需设置 \(\eta_j=0\)，其余模型不变。

## 3. 固定实际完成时间后，推出窗口分位数

固定一个可行的 \((x,C,w)\)。waiting cost 此时为常数，不参与窗口端点的优化。任务 \(j\) 的窗口子问题为

\[
\Phi_j(\mathbf C_j)=
\min_{0\le a_j\le b_j}
\left\{
\rho_ja_j+\lambda_j(b_j-a_j)
+\sum_{\omega\in\Omega}p_\omega
\left[
\alpha_j(a_j-C_{j\omega})^+
+\beta_j(C_{j\omega}-b_j)^+
\right]
\right\}.
\tag{1}
\]

这里 \(\Phi_j(\mathbf C_j)\) 是给定实际完成时间向量后，任务 \(j\) 的最小窗口与 ET 成本。定义加权经验分布函数

\[
\widehat F_j(z)=
\sum_{\omega\in\Omega}p_\omega
\mathbf 1\{C_{j\omega}\le z\},
\]

并以 \(\widehat F_j(z^-)\) 表示严格小于 \(z\) 的场景概率。

### 3.1 左端点

在 \(a_j>0\) 且 \(a_j<b_j\) 的内部情形下，与 \(a_j\) 有关的部分是

\[
(\rho_j-\lambda_j)a_j+
\alpha_j\sum_\omega p_\omega(a_j-C_{j\omega})^+.
\]

其次梯度最优条件为

\[
0\in
\rho_j-\lambda_j+
\alpha_j[\widehat F_j(a_j^-),\widehat F_j(a_j)].
\]

因此

\[
\widehat F_j(a_j^-)
\le
\frac{\lambda_j-\rho_j}{\alpha_j}
\le
\widehat F_j(a_j),
\]

即

\[
\boxed{
a_j^*\in Q_j(\tau_j^a),\qquad
\tau_j^a=\frac{\lambda_j-\rho_j}{\alpha_j}
}.
\tag{2}
\]

\(Q_j(\tau)\) 表示 \(\mathbf C_j\) 的加权经验 \(\tau\)-分位数集合。

### 3.2 右端点

与 \(b_j\) 有关的部分是

\[
\lambda_jb_j+
\beta_j\sum_\omega p_\omega(C_{j\omega}-b_j)^+.
\]

最优条件为

\[
\widehat F_j(b_j^-)
\le
1-\frac{\lambda_j}{\beta_j}
\le
\widehat F_j(b_j),
\]

所以

\[
\boxed{
b_j^*\in Q_j(\tau_j^b),\qquad
\tau_j^b=1-\frac{\lambda_j}{\beta_j}
}.
\tag{3}
\]

### 3.3 端点碰撞与边界

若 \(0\le\tau_j^a\le\tau_j^b\le1\)，可分别使用式 (2)–(3)。若 \(\tau_j^a>\tau_j^b\)，约束 \(a_j\le b_j\) 活跃，窗口收缩为点 \(a_j=b_j=d_j\)，且

\[
\boxed{
d_j^*\in
Q_j\!\left(
\frac{\beta_j-\rho_j}{\alpha_j+\beta_j}
\right)
}.
\tag{4}
\]

若分位水平越界，或存在 \(L_j\le a_j\le b_j\le U_j\)，端点落在相应边界。样本并列时，分位数可能是一个区间；窗口 LP 仍给出唯一最优值。

waiting cost \(\eta_jw_{j\omega}\) 没有出现在式 (2)–(4) 中，因为这些公式以实际 \(C_{j\omega}\) 为条件。系数 \(\eta_j\) 通过外层 timing 决定模型愿意支付多少等待来改变 \(C_{j\omega}\)；令 \(\eta_j=0\) 只会删除 waiting cost，不改变条件窗口子问题 (1)。

## 4. 消去 due-window 后的整体模型

对固定 \((x,C,w)\)，各任务窗口相互独立，所以窗口与 ET 部分的最优值为 \(\sum_j\Phi_j(\mathbf C_j)\)。将其代回 \((P_0)\)，得到

\[
\begin{aligned}
(P_1)\qquad
\min_{x,C,w}\quad
&c^{\mathrm{sch}}(x)
+\sum_{\omega,j}p_\omega\eta_jw_{j\omega}
+\sum_j\Phi_j(\mathbf C_j)\\
\text{s.t.}\quad
&(x,C,w)\in\mathcal T.
\end{aligned}
\tag{5}
\]

在 \((P_1)\) 中，\(a_j,b_j,e_{j\omega},t_{j\omega}\) 已从显式变量中消失。due-window 决策被编码在 \(\Phi_j\) 中；求得最优 \(\mathbf C_j^*\) 后，可用式 (2)–(4) 或小型窗口 LP 恢复 \(a_j^*,b_j^*\)。

若进一步投影所有连续 timing 变量，定义

\[
Q(x)=
\min_{C,w:\,(x,C,w)\in\mathcal T}
\left\{
\sum_{\omega,j}p_\omega\eta_jw_{j\omega}
+\sum_j\Phi_j(\mathbf C_j)
\right\},
\tag{6}
\]

则完整问题成为

\[
\boxed{
(P_2)\qquad
\min_x\{c^{\mathrm{sch}}(x)+Q(x)\}
}.
\tag{7}
\]

三层关系可概括为

\[
\underbrace{(x,a,b,C,w,e,t)}_{(P_0)}
\ \xrightarrow{\text{投影窗口}}\
\underbrace{(x,C,w,\Phi)}_{(P_1)}
\ \xrightarrow{\text{投影 timing}}\
\underbrace{(x,Q)}_{(P_2)}.
\]

这个变换不改变信息结构：\(a_j,b_j\) 始终不带场景下标，所有场景共享同一窗口。投影只是代数消元，不是把窗口改成场景决策。

### 4.1 最终 Benders 到底分解哪个模型

最终 Benders 的原始对象是完整模型 \((P_0)\)，不是分位数模型 \((P_1)\)。令

\[
y=(a,\ell,C,w,e,t)
\]

表示 \((P_0)\) 中的全部连续变量。按“离散排程 \(x\)／连续变量 \(y\)”划分后，

\[
(P_0)
=
\min_{x}
\left\{
c^{\mathrm{sch}}(x)
+
\underbrace{
\min_{y:\,(x,y)\text{ 满足 }(P_0)}
\left[
\sum_j(\rho_ja_j+\lambda_j\ell_j)
+
\sum_{\omega,j}p_\omega
(\eta_jw_{j\omega}+\alpha_je_{j\omega}+\beta_jt_{j\omega})
\right]
}_{Q(x)}
\right\}.
\]

上式正是 \((P_2)=\min_x\{c^{\mathrm{sch}}(x)+Q(x)\}\)。因此，\((P_2)\) 不是另一套模型，而是 \((P_0)\) 按 Benders 分解边界写成的值函数形式。

当前选定的 2026 风格分解为：

| 层 | 保留变量 | 含义 |
|---|---|---|
| master | 离散排程 \(x\) 和总连续成本下界 \(\Theta\) | 决定机器分配、加工顺序或位置 |
| 联合 LP 子问题 | \(a,\ell,C,w,e,t\) | 固定 \(x\) 后，同时优化共同窗口和全部场景 timing |

due-window 仍是一阶段共同决策，因为 \(a,\ell\) 没有场景下标。把它们放入固定 \(x\) 后的联合 LP，只是在算法上投影连续变量；并没有允许不同场景选择不同窗口。

分位数推导和 \((P_1)\) 只用于说明“窗口可以被投影、投影值是凸分段线性函数”。最终实现 2026 风格 Benders 时，不需要先建立 \(\Phi_j\)，也不需要显式计算分位数；直接对固定 \(x\) 的 \((P_0)\) 连续部分求解即可。

若采用 2025 风格，分解边界才改为：master 保留 \(x,a,\ell\)，固定排程和窗口后分别求各场景的 \(C,w,e,t\)。这一路线作为对照，不是当前主算法。

## 5. 最终求解路线与分位数的角色

直接写 \(a_j=Q_j(\tau_j^a)\) 会遇到决策相关排序、样本并列和集合值问题，因此不应显式编码分位数等式。式 (1) 本身是 LP，它已经是分位数函数的紧凑线性表示。

### 5.1 直接保留窗口变量

最简单的精确方法是直接求解 \((P_0)\)，保留 \(a,\ell,e,t\)。求解器自动得到满足分位数条件的窗口，无需排序变量。这是小规模正确性基准。

### 5.2 真正投影窗口：对偶与 cut

将 \(b_j\) 写成 \(a_j+\ell_j\)。窗口 LP 的两组核心约束是

\[
e_{j\omega}-a_j\ge-C_{j\omega},\qquad
t_{j\omega}+a_j+\ell_j\ge C_{j\omega}.
\]

分别以 \(u_{j\omega}\ge0\) 和 \(v_{j\omega}\ge0\) 表示这两组约束的对偶变量。定义对偶可行域

\[
\mathcal D_j=
\left\{
(u_j,v_j):
\begin{array}{l}
-\sum_\omega u_{j\omega}+\sum_\omega v_{j\omega}\le\rho_j,\\
\sum_\omega v_{j\omega}\le\lambda_j,\\
0\le u_{j\omega}\le p_\omega\alpha_j,\\
0\le v_{j\omega}\le p_\omega\beta_j
\end{array}
\right\}.
\tag{8}
\]

由 LP 强对偶，

\[
\boxed{
\Phi_j(\mathbf C_j)=
\max_{(u_j,v_j)\in\mathcal D_j}
\sum_\omega(v_{j\omega}-u_{j\omega})C_{j\omega}
}.
\tag{9}
\]

因此 \(\Phi_j\) 是线性函数的最大值，即凸分段线性函数。若希望从模型中真正删除窗口变量，在 master 中引入投影成本变量 \(z_j\)；已生成的 cuts 共同给出 \(\Phi_j(\mathbf C_j)\) 的下近似。第 \(k\) 次切平面生成时，在当前完成时间向量上求解式 (9)，得到固定对偶最优解 \((\bar u_j^k,\bar v_j^k)\)，再加入

\[
\boxed{
z_j\ge
\sum_\omega
(\bar v_{j\omega}^k-\bar u_{j\omega}^k)C_{j\omega}
}.
\tag{10}
\]

式 (10) 中：

- \(z_j\)：任务 \(j\) 的投影窗口成本变量；
- \(k\)：已生成 cut 的编号；
- 上标 \(k\)：cut 编号，不是幂；
- 横线 \(\bar{\cdot}\)：对偶子问题已经求得的固定数值；
- \(u_{j\omega}\)、\(v_{j\omega}\)：分别对应 early 和 tardy 线性化约束的对偶变量；
- \(v-u\) 中间的“\(-\)”才是减号。

原稿中的上标 \(r\) 也只是“第 \(r\) 个对偶极点/cut”的编号。为避免未定义和误读，本文统一改用定义明确的 \(k\)。

### 5.3 2026 风格 projected Benders

2026 风格直接使用 \((P_2)\)。master 只保留离散排程 \(x\) 和总 recourse 下界 \(\Theta\)；固定 \(x\) 后，联合 LP 优化全部场景的 \(C,w\) 及共同窗口。实现联合 LP 时可以保留 \(a,\ell,e,t\) 作为紧凑辅助变量。

若联合子问题写成

\[
Q(x)=\min_y\{q^\top y:Ay\ge h-Bx\},
\]

其中 \(y=(a,\ell,C,w,e,t)\)，第 \(k\) 次求得的固定对偶最优解记为 \(\bar\pi^k\)，则 master 加入

\[
\boxed{
\Theta\ge(\bar\pi^k)^\top(h-Bx)
}.
\tag{11}
\]

\(\Theta\) 是总 recourse cost 的下界变量；\(\bar\pi^k\) 是第 \(k\) 次联合 LP 的固定对偶解。数学表达式 \(Q(x)\) 已投影窗口和 timing；求值所用的 LP 重新采用这些变量作为扩展式，两者并不矛盾。

## 6. 求解路线与当前选择

| 路线 | master/完整模型保留什么 | 连续层 | 主要特点 |
|---|---|---|---|
| 直接 MILP \((P_0)\) | \(x,a,\ell,C,w,e,t\) | 不分解 | 精确、最清楚；作为小规模基准 |
| 2025 风格 | master 保留 \(x,a,\ell\) | 固定窗口后按场景求 timing LP | 场景可分，但 master 和 cuts 较多 |
| 2026 风格 \((P_2)\) | master 只保留 \(x,\Theta\) | 共同窗口与全部场景 timing 的联合 LP | master 小；场景被共同窗口耦合 |

当前建议先建立直接 MILP，再以 2026 风格 projected Benders 为主算法，并以 2025 风格作为分解边界对照。主算法直接分解 $(P_0)$，不显式建立 order-statistic 排序变量，也不计算分位数。分位数只用于解释窗口结构、分析参数和校验最优窗口；LP 扩展式与联合 LP 的对偶 cuts 才实际用于求解。不要把分位数投影列为独立算法贡献。

上述逐任务投影要求任务使用彼此独立的软 due window。若使用 common window、跨任务窗口预算或 hard-window 可行性约束，仍可联合投影，但不能写成 \(\sum_j\Phi_j(\mathbf C_j)\)。若目标改成 early/tardy job 数量，连续 LP 结构也会消失，classical Benders 对偶 cut 不再直接适用。
