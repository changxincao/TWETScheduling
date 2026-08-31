# Work 3：随机 due-window 多机调度的问题设定、非退化机制与文献依据

本文对应的原始需求与问题形成过程单独保存在：[Work 3 随机 due-window 问题设定：原始需求记录](2026-08-31-work3随机due-window问题设定原始需求记录.md)。本文给出复核、修正和文献调研后的正式结论；原始需求记录只用于追溯讨论动机。

## 1. 当前结论

Work 3 可以沿用 2025 和 2026 两篇 TWATSP-ST 的基本思路，把“客户访问顺序 + 随机旅行时间 + 可决策时间窗”改写为“机器分配与任务顺序 + 随机加工时间 + 可决策 due window”。但机器调度模型不能直接删除 TWA 中的 overtime 后保持其余设定不变。两篇 TWA 都没有任务位置成本，窗口的绝对位置主要由固定出发时刻和班次结束时间共同约束；一旦删掉 overtime，又保留免费等待和仅按窗口宽度收费，窗口就失去绝对时间锚。

当前推荐的核心问题为：考虑并行或非相关多机、任务特定 DIF due window、随机加工时间、序列相关 setup、场景自适应等待；一阶段共同决定机器分配、加工顺序和每个任务的 due window，二阶段按场景决定实际开始与完工时刻；目标包括 due-window 位置成本、宽度成本和期望 earliness/tardiness（ET）成本，不含 overtime，也不对 waiting 单独收费；同时设置任务特定的窗口位置区间和窗口长度上下界。

这个设定同时使用了两种互补机制：位置成本在可行区间内部给较晚承诺定价，有限区间则表达不可违反的合同或客户边界。二者并非数学上都必需，但现实含义不同，合用后模型更稳定，也与前两个 work 的 due-window 设定保持连续。

![Work 3 模型逻辑](../figures/work3-stochastic-due-window-model-logic.png)

图 1　从 TWA 的 overtime 锚到 Work 3 的位置成本与有限范围锚。第三种 waiting-cost 方案保留为机制对照，不进入核心模型。

## 2. TWA 删除 overtime 后为什么会退化

### 2.1 两篇 TWA 原模型的共同结构

Çelik et al. (2025) 和 Cavaliere et al. (2026) 的目标都包含四类成本：路线成本、窗口宽度成本、场景 ET 成本和驾驶员 overtime 成本。路线和窗口是一阶段决策，场景访问时刻、等待、ET 和 overtime 是连续二阶段决策。两文均允许车辆通过不等式时间递推插入等待，waiting 本身不收费；两文也都固定从 depot 出发的时间原点。

窗口宽度项代表客户服务或承诺精度，overtime 则是绝对时间的终端价格。2026 论文把路线和 overtime 归入运营成本，把窗口宽度和 ET 归入客户满意度；2025 论文的计算设置同样用独立权重平衡窗口宽度、ET 和 overtime，但这些权重是实验标度，不是经验估计的货币价格。

### 2.2 删除 overtime 后的严格结果

对于任意给定路线和有限场景集，可以沿访问顺序递归选择足够晚的共同窗口，并让较快场景等待，使各场景都在窗口内访问。此时：

1. 2026 模型只要求窗口宽度非负，因此可取点窗口，所有 ET 和宽度成本都为 0，目标只剩路线成本。
2. 2025 模型要求窗口宽度至少覆盖客户服务时间，因此可取最小宽度窗口，所有 ET 为 0，宽度项成为与路线无关的常数，目标等价于路线成本加常数。

这里不需要真的把窗口变量取成 \(+\infty\)。对每条路线都存在有限的零 ET 构造，而且还可以把窗口和相应执行时刻共同后移任意常数，形成无界的零成本平移方向。Cavaliere et al. (2026) 第 6.2.3 节正是据此说明：班次上限 \(T\) 过松时，最坏场景也能通过等待准时访问，ET 消失，实例只剩 routing cost，因此 \(T\) 必须按路线时长谨慎设置。

在机器调度中，同一构造也成立：给定每台机器的任务序列后，可以递归把任务窗口设在所有场景都能通过等待达到的位置。若没有位置成本、有限位置范围、overtime 或付费 waiting，ET 会消失，宽度取允许的最小值。此时剩下什么取决于基础模型：有序列相关 setup 成本时，问题退化为 setup/序列成本最小化；只有 setup 时间而没有其他序列成本时，甚至可能只剩常数或大量等价排程。因此，“退化成 TSP”只准确描述原车辆问题，在机器问题中应称为“退化成只含基础分配或 setup 成本的排程问题”。

## 3. 三种替代 overtime 的处理方式

### 3.1 模型一：位置成本 + 宽度成本

窗口 \([a_j,b_j]\) 的一阶段成本写为

\[
\rho_j(a_j-L_j)+\lambda_j(b_j-a_j),
\]

其中 \(L_j\) 是任务 \(j\) 最早允许的窗口起点。位置项使窗口整体后移一单位产生 \(\rho_j\) 的边际成本，宽度项使窗口扩张一单位产生 \(\lambda_j\) 的边际成本。只要 \(\rho_j>0\) 且 \(\lambda_j>0\)，窗口整体后移和无代价扩张两个方向都受到控制。initial idle 可以免费，因为窗口与排程共同平移会增加位置成本。

位置成本相对 \(L_j\) 而不是相对时间 0 计费更合适。若直接写 \(\rho_j a_j\)，区间 \([0,L_j]\) 是任务根本不能选择的时间，却仍被记入成本；写成 \(\rho_j(a_j-L_j)\) 后，\(L_j\) 对应零基准，只有超出最早可接受承诺的部分收费。两种写法在 \(L_j\) 为常数时只差一个常数，但后者的经济解释和跨任务比较更清楚。

### 3.2 模型二：有限位置区间 + 宽度成本

对每个任务设置

\[
L_j\le a_j\le b_j\le U_j<+\infty.
\]

有限的 \(U_j\) 直接截断共同平移，因而即使没有位置成本、initial idle 免费，模型也不存在向右无限移动的方向。范围约束并不一般性推出 \([U_j,U_j]\)；它只给出可行边界，最终位置仍由宽度、ET、等待传播和其他任务共同决定。

如果模型只有范围和宽度成本，窗口可能较频繁地落在边界，但这属于有限可行域内的正常最优选择，不是退化。范围过松仍会使实例近似无锚，数值实验必须使 \(U_j\) 与任务实际完成时间尺度相匹配，而不能统一取一个几乎不生效的大 \(M\)。

### 3.3 模型三：宽度成本 + waiting cost

若不设置位置成本和有限位置范围，正的 waiting cost 可以成为窗口位置的唯一价格。内部等待和首任务前 initial idle 都按相同单位成本计费时，排程整体后移会增加 waiting cost，从而消除平移自由。

如果只对相邻任务之间的等待收费、首任务前 initial idle 免费，那么模型通常不是 ET 退化，而是存在时间平移对称：对任意 \(K\ge0\)，把某台机器上所有场景时刻和所有窗口同时增加 \(K\)，ET、宽度和内部等待都不变。因此会有无界的零成本方向和无穷多个等价 timing 解。有限窗口范围可以消除该方向，但此时模型已经成为“模型二 + waiting cost”，不再是单独检验 waiting 能否替代位置锚的纯模型三。

Tan and Fu (2024) 的 affine idleness cost 只对相邻任务之间的正 idle gap 收费，并不收费首任务之前的 idle；该论文仍有固定 due dates 和外生时间区间提供绝对时间锚，不能直接用来证明“内部 waiting cost 单独足以锚定可决策窗口”。

### 3.4 当前选择

核心模型合并模型一和模型二：保留位置成本、宽度成本、有限位置区间和长度上下界；waiting 允许但免费。模型三只作为可选消融，用于研究机器空闲运营成本是否改变窗口与排程，不作为主问题。这样既保持三种机制的理论区别，又避免把 initial idle 的收费口径变成论文主设定。

## 4. 文献依据与现实含义

本次调研采用项目综述表定位文献，再以本地全文和出版社页面核对。各来源与当前模型的关系如下。

| 来源 | 论文明确采用或说明的设定 | 对当前模型的证据边界 |
| --- | --- | --- |
| Çelik et al. (2025) | 路线、窗口宽度、场景 ET、overtime；免费 waiting；窗口至少覆盖服务时间 | 支持 TWA 基线和 2025 风格分解，不支持位置成本 |
| Cavaliere et al. (2026) | 路线、非负窗口宽度、场景 ET、overtime；免费 waiting；第 6.2.3 节分析 \(T\) 过松的退化 | 直接支持 overtime 的位置锚作用和 2026 projected Benders |
| Yue and Zhou (2021) | 随机单机、任务特定 DIFW、任务特定 ET/位置/宽度成本 | 与当前成本结构最接近，但没有多机、setup 和场景 waiting |
| Janiak et al. (2015) | due-window 位置和宽度成本；公共窗口的 \(D^{\min},D^{\max}\)；单机与并行机结果 | 支持成本及长度界传统，不等于任务特定随机 DIFW |
| Shabtay (2016) | 任务特定 due date 上界；过晚承诺可能违反先期协议 | 支持有限绝对时间范围的现实动机，原文不是 due window |
| Shabtay et al. (2022) | 公共 due date/window 的位置与长度上界；early/late work | 支持 bounded window 和 work 指标，但结构比当前模型特殊 |
| Kim and Lee (2009) | due-date assignment + sequence-dependent setup；注塑换模/清洗例子 | 支持 setup 的现实性，原文为确定性单机公共 due date |

### 4.1 窗口宽度成本

Yue and Zhou (2021) 对随机加工时间下的任务特定 DIF due window 使用

\[
\mathbb E\!\left[\alpha_jE_j+\beta_jT_j+\gamma_ja_j+\delta_j(b_j-a_j)\right].
\]

论文的现实解释是：较窄窗口对客户更有吸引力，但降低制造商的生产灵活性；较宽窗口更容易满足，却可能造成客户流失，因此必须在客户吸引力和运营灵活性之间权衡。TWA 文献的解释与此一致：客户偏好更精确的服务时间，物流服务商偏好更宽的时间窗以获得执行灵活性。宽度成本并不是“制造商使用宽窗口产生的直接现金支出”，而是较模糊承诺造成的服务价值或客户满意度损失。

现实量化可来自不同承诺档位的报价差、客户对 1/2/4 小时时间槽的选择率、取消或流失概率、因宽承诺支付的折扣或补偿，以及 SLA 中不同时间精度对应的价格。若有历史订单，可把“窗口每扩大一小时导致的期望贡献毛利损失”作为 \(\lambda_j\)；没有可靠数据时，\(\lambda_j\) 只能解释为标准化权重，不能声称是实际货币成本。

### 4.2 窗口位置成本

位置成本衡量把最早可接受交付承诺推迟的代价。它可以对应较长 lead time 导致的订单流失、折扣、延迟收入、客户不满意和声誉损失。Kim and Lee (2009) 对 due-date assignment 的解释是：交期过早会增加无法按时交付的风险，交期过晚又会影响客户谈判和商誉，因此交期本身是需要付费的决策。Shabtay (2016) 进一步指出，过晚的 due date 可能违反制造商与客户的先期协议。

在当前模型中，\(\rho_j\) 是窗口左端从 \(L_j\) 向后移动一单位的边际损失。可以用报价折扣随承诺 lead time 的变化、订单接受概率或取消风险乘以贡献毛利、延迟回款的资金成本，或合同中延长承诺时间的补偿标准进行估计。若客户只在超过某个 acceptable lead time 后才产生损失，更合适的形式是分段线性成本 \(\rho_j(a_j-A_j)^+\)，而不是强行使用从 \(L_j\) 开始的单一直线；核心线性模型可先使用 \(\rho_j(a_j-L_j)\)，再把分段形式作为扩展。

### 4.3 位置区间与长度上下界

Shabtay (2016) 直接研究任务特定 due date 上界，现实理由是过晚承诺会违反先期协议。Janiak et al. (2015) 的 due-window 综述列出了同时决策窗口位置和长度、并施加

\[
D^{\min}\le b-a\le D^{\max}
\]

的单机和并行机公共窗口模型。Shabtay et al. (2022) 也对公共 due window 的位置和长度设置上界，并证明有界与无界位置会显著改变复杂度。

这些文献主要讨论公共窗口或 due date 上界，不是与当前完全相同的“多机、随机、任务特定 DIFW 四个界”。因此，\(L_j,U_j,D_j^{\min},D_j^{\max}\) 应表述为已有 restricted DDA 和 bounded common-DW 设定向任务特定 DIFW 的自然组合扩展，而不是声称已有论文采用了完全相同的模型。

现实中，\(L_j\) 可由材料最早可用时刻、客户最早接收时刻或合同起始日给出；\(U_j\) 可由客户最晚接受承诺、活动结束时刻、下游装配节点或计划期给出。\(D_j^{\min}\) 可表示时间槽最小粒度、必要验收或配送缓冲，也可以取 0 允许模型选择 due date；\(D_j^{\max}\) 表示客户能够接受的最大承诺模糊度。它们应从订单、ERP/MES、合同或服务档位直接获得，而不是用求解需要反推。

### 4.4 ET 成本

JIT 文献对 ET 的解释相对稳定。earliness 可对应成品库存、仓储、维护、保险、资金占用、变质或过早交付给客户造成的干扰；tardiness 可对应合同罚款、加急、折扣、替代采购、销售损失、客户投诉和声誉损失。Yue and Zhou (2021) 的定制服装例子中，过早完成需要存储与维护，过晚完成会导致投诉和折扣。

\(\alpha_j\) 和 \(\beta_j\) 可以按单位时间的期望边际损失估计。通常 tardiness 的业务后果可能高于 earliness，但不存在必须满足的统一比例，且当前模型是任务特定系数。实验中不应把所有 \(\rho,\lambda,\alpha,\beta\) 从同一独立均匀分布抽取后就称为现实标定；这样会无意产生大量边界支配任务。更合理的做法是先生成业务尺度，再按预定比率档位构造各任务系数，并报告每类结构条件覆盖的任务比例。

### 4.5 setup

Kim and Lee (2009) 已把序列相关 setup 引入 due-date assignment，例子是注塑生产：连续任务使用相同材料时只需换模，材料改变时还需要清洗螺杆，setup 时间取决于前后任务组合。当前 Work 3 可令 \(s_{ijm}\) 表示任务 \(i\) 后在机器 \(m\) 加工任务 \(j\) 的换型时间，并从 MES 换型日志、工艺标准或 SMED 数据估计。

setup 时间必须进入场景时间传播；setup 成本 \(c^{s}_{ijm}\) 是否进入目标取决于是否存在独立于时间的人工、材料、清洗剂、能耗或报废损失。若这些代价已经由 ET 间接体现，就不必为了“让目标更多”再加 setup cost。推荐核心目标只保留 ET、位置和宽度三类成本，setup cost 作为有实际数据时的可选一阶段项。

## 5. 推荐的两阶段 SAA 模型

### 5.1 集合、参数与决策

令 \(J\) 为任务集合，\(M\) 为机器集合，\(\Omega\) 为场景集合，\(\pi_\omega\) 为归一化场景概率。\(p_{jm\omega}\) 是任务 \(j\) 在机器 \(m\) 和场景 \(\omega\) 下的加工时间，\(s_{ijm}\) 是确定性的序列相关 setup 时间；若 setup 也不确定，可扩展为 \(s_{ijm\omega}\)。

一阶段决策包括机器分配与顺序变量 \(x_{ijm}\)，以及任务特定窗口端点 \(a_j,b_j\)。二阶段连续变量包括场景开始时刻 \(S_{j\omega}\)、完工时刻 \(C_{j\omega}\)、earliness \(E_{j\omega}\) 和 tardiness \(T_{j\omega}\)。waiting 不必单独建变量：时间递推使用“\(\ge\)”而不是等式，其松弛量就是允许的 initial 或内部 waiting。

### 5.2 目标函数

推荐核心目标为

\[
\min
\sum_{m\in M}\sum_{i,j}c^{s}_{ijm}x_{ijm}
+\sum_{j\in J}\left[\rho_j(a_j-L_j)+\lambda_j(b_j-a_j)\right]
+\sum_{\omega\in\Omega}\pi_\omega\sum_{j\in J}
\left(\alpha_jE_{j\omega}+\beta_jT_{j\omega}\right).
\]

第一项在没有独立 setup 成本时删去。核心模型不含 overtime、makespan 和 waiting cost。这样得到的权衡是：更晚的窗口降低迟到风险但增加位置成本；更宽的窗口降低 ET 但增加客户承诺成本；更早或更窄的窗口降低一阶段服务承诺成本，却迫使排程承受更多场景 ET；任务顺序和 setup 又改变所有下游任务在各场景中的完工分布。

### 5.3 主要约束

除标准的多机路径、流平衡和每个任务恰好分配一次约束外，窗口域为

\[
L_j\le a_j\le b_j\le U_j,
\qquad
D_j^{\min}\le b_j-a_j\le D_j^{\max}.
\]

若 \(i\) 紧接 \(j\) 且二者在机器 \(m\) 上，场景时间满足

\[
S_{j\omega}\ge C_{i\omega}+s_{ijm}-M_{ijm\omega}(1-x_{ijm}),
\qquad
C_{j\omega}=S_{j\omega}+p_{jm\omega}.
\]

首任务由 dummy origin 与 release time 连接，仍使用不等式，因此 initial idle 免费且允许。ET 可精确线性化为

\[
E_{j\omega}\ge a_j-C_{j\omega},\qquad
T_{j\omega}\ge C_{j\omega}-b_j,\qquad
E_{j\omega},T_{j\omega}\ge0.
\]

正成本最小化保证两变量自动等于相应正部函数。固定一阶段排程和窗口后，所有场景 timing 问题都是 LP；固定排程但把共同窗口也放入联合子问题时，仍然是跨场景共享 \(a,b\) 的 LP。

## 6. 非退化与逐任务参数边界

以下三个结论是分布无关的逐任务充分条件。它们通过在保持排程和所有场景 timing 不变时单独移动窗口端点得到，因此允许等待、setup、机器数量和随机分布都不会破坏结论。严格不等式用于获得必然边界；等号通常只产生多个等价最优解，不能直接预处理。

### 6.1 位置成本支配宽度成本

若

\[
\rho_j>\lambda_j,
\]

则降低 \(a_j\) 一单位至少节省 \(\rho_j\)，只增加 \(\lambda_j\) 的宽度成本，而且 earliness 不会增加。因此最优解满足

\[
a_j=\max\{L_j,b_j-D_j^{\max}\}.
\]

没有最大宽度约束或该约束不活跃时，结论简化为 \(a_j=L_j\)。只有当 \(L_j=0\)、最大宽度不阻挡且所有完工时刻非负时，才进一步得到该任务 earliness 必为 0；在一般有限 \(D_j^{\max}\) 下不能直接宣布 earliness 消失。

### 6.2 宽度成本支配 tardiness

若

\[
\lambda_j>\beta_j,
\]

则从右侧缩短窗口一单位节省 \(\lambda_j\)，期望 tardiness 最多增加 \(\beta_j\)，故

\[
b_j-a_j=D_j^{\min}.
\]

这里使用 \(\sum_\omega\pi_\omega=1\) 且各场景使用同一 \(\beta_j\)。若场景罚率不同，应与其概率加权上界比较。

### 6.3 宽度成本支配“位置 + earliness”

若

\[
\lambda_j>\rho_j+\alpha_j,
\]

则从左侧缩短窗口一单位节省 \(\lambda_j\)，位置成本最多增加 \(\rho_j\)，期望 earliness 最多增加 \(\alpha_j\)，同样得到

\[
b_j-a_j=D_j^{\min}.
\]

若 \(D_j^{\min}=D_j^{\max}\)，宽度已经固定，\(\lambda_j(b_j-a_j)\) 是常数，后两个关于“压缩到最小宽度”的条件失去分析对象。

### 6.4 如何用于算例而不是过度解释

这些条件都是 job-specific 的。单个任务落在边界不会使整个模型退化，也不会显著简化算法；只有条件覆盖全部或大量任务时，才会系统性消除窗口位置或宽度决策。反向条件也只是排除上述充分边界，不保证窗口一定在内部，更不保证 ET 一定为正。

理论部分应保留全部参数区间并给出上述预处理性质。主数值实验则应避免让大量任务满足

\[
\rho_j>\lambda_j,\qquad
\lambda_j>\beta_j,\qquad
\lambda_j>\rho_j+\alpha_j,
\]

并报告三类边界任务的比例、窗口下界/上界命中率、最小宽度命中率以及正 earliness/tardiness 的任务比例。另设一组不控制参数的 stress instances，检验算法在大量边界解下是否稳定。数据设计的目标是避免无意生成一个几乎没有 due-window 决策的主基准，而不是保证每个实例都出现正 ET。

## 7. 只有场景 ET 成本是否足够

在推荐模型中足够。TWA 需要 overtime，是因为其窗口没有位置成本，且班次上限是唯一重要的绝对时间终端价格；当前模型已经用 \(\rho_j(a_j-L_j)\) 和有限 \(U_j\) 替代这一功能。ET、位置和宽度三类成本可以形成完整权衡，不需要额外加入 waiting 或 overtime 才使模型成立。

免费 waiting 会让模型在有利时主动推迟任务，以消除当前任务的 earliness，但这种等待会推迟同一机器上的后续任务，可能增加其 tardiness。因此，不能从“允许免费等待”推出完整序列中所有 earliness 必为 0。机器末任务没有下游传播时，若又没有 terminal cost，其 earliness 通常可以通过等待消除；每台机器至多存在一个这样的内生末任务，这属于局部排程性质，不是模型级退化。

initial idle 免费也没有问题。位置成本和有限范围已锚定窗口，整台机器与窗口共同后移会增加位置成本或触碰 \(U_j\)，所以不存在模型三中的平移射线。若未来单独研究“宽度 + waiting cost”且删去位置成本和范围，才需要重新决定 initial idle 是否计费。

## 8. 确定性投影、随机分位数与求解价值

### 8.1 给定场景完成时刻后的窗口子问题

若所有场景的实际完工时刻 \(C_{j\omega}\) 已固定，任务 \(j\) 的窗口优化为

\[
\Phi_j(\mathbf C_j)=
\min_{a_j,b_j}
\left\{
\rho_j(a_j-L_j)+\lambda_j(b_j-a_j)
+\sum_\omega\pi_\omega
\left[\alpha_j(a_j-C_{j\omega})^+
+\beta_j(C_{j\omega}-b_j)^+\right]
\right\},
\]

并受位置和长度界约束。忽略这些边界且两个端点为内部解时，端点分别是场景完成时间分布的加权分位数：

\[
q^a_j=\frac{\lambda_j-\rho_j}{\alpha_j},
\qquad
q^b_j=1-\frac{\lambda_j}{\beta_j}.
\]

有离散概率质量时，严格条件为

\[
F_j(a_j^-)\le q^a_j\le F_j(a_j),
\qquad
F_j(b_j^-)\le q^b_j\le F_j(b_j),
\]

而不是假设唯一分位点。只有 \(0<q^a_j<q^b_j<1\) 且位置、长度界均不活跃时，才得到两个分离的内部端点；否则端点会落在分布或可行域边界，或者窗口取最小宽度。这个结论对每个任务都成立，因为给定完成时间后窗口子问题按任务分离。

### 8.2 为什么它不像确定性 DDA 那样直接简化求解

确定性 DDA 中，经常可以把 due date/window 写成单个完工时刻或序列位置的函数，再得到关于 \(C_j\) 的一元分段线性成本、排序规则、DP 或 BPC 定价结构。当前 SAA 模型不同：\(\mathbf C_j=(C_{j\omega})_{\omega\in\Omega}\) 是跨场景向量，而且在允许等待时，即使排程顺序固定，各场景的 \(C\) 仍是联合 timing 决策并向下游传播。

从数学上可以投影掉 \(a_j,b_j\)，但得到的是多场景 order-statistic 值函数，不是确定性的一元完成时间函数。显式写“第 \(k\) 个场景完工时刻”通常需要排序或秩选择结构；也可以用连续凸分段线性 LP 求分位数，但那实际上仍保留与原始 \(a,b,E,T\) 模型等价的辅助变量。原始窗口 LP 已经很小、线性且更清楚，因此显式分位数建模通常比保留窗口变量更差。

所以，分位数推导的价值是解释参数、验证最优窗口、识别边界任务和可能构造值函数 cuts，而不是当前算法的核心创新。它不能把完整 SAA 改写成现有 ng-DSSR 可直接处理的确定性完工时间目标。

### 8.3 两种合理的 Benders 分解边界

第一种是 2025 风格：master 保留排程 \(x\) 和窗口 \(a,b\)，每个场景的 timing LP 独立，生成场景 cuts。第二种是 2026 风格 projected Benders：master 只保留离散排程 \(x\) 和 \(\Theta\)，固定 \(x\) 后在一个联合 LP 中同时优化共享窗口和所有场景 timing，再由联合对偶生成关于 \(x\) 的 cuts。

后者把窗口放入子问题只是算法投影，不表示窗口变成二阶段随机决策；同一 \(a,b\) 仍由所有场景共享。两种路线都直接分解原始线性扩展模型，不需要显式计算分位数。后续可以比较两种分解边界，再在有效的基础上加入 deepest Benders cut 和 local branching；不能把分位数分析本身当成新的 Benders 方法。

## 9. ET、early/tardy work 与 early/tardy job 数量

### 9.1 ET 是最适合的核心目标

ET 是凸正部函数，使用连续变量即可精确线性化。固定一阶段决策后，二阶段仍是 LP，可以直接使用经典 Benders 对偶、2025 场景分解和 2026 联合 projected Benders。它也最直接反映完成时刻距离承诺窗口的偏差，足以形成本文需要的服务与排程权衡。

### 9.2 early work 和 tardy work

对非抢占任务的实际加工区间 \([S_{j\omega},C_{j\omega}]\)，加工时间为 \(p_{jm\omega}\)，相对于窗口 \([a_j,b_j]\) 的 early work 和 tardy work 可写为

\[
EW_{j\omega}
=\max\{0,\min(C_{j\omega},a_j)-S_{j\omega}\}
=\min\{p_{jm\omega},(a_j-S_{j\omega})^+\},
\]

\[
TW_{j\omega}
=\max\{0,C_{j\omega}-\max(S_{j\omega},b_j)\}
=\min\{p_{jm\omega},(C_{j\omega}-b_j)^+\}.
\]

这里统计的是任务加工量，不应把 setup 时间算入 job work。两个函数都是带上限的 hinge：斜率经历 \(0\to1\to0\)，一般既非凸也非凹。仅用连续下界变量并在最小化中不能强制等式成立，精确建模通常需要二元区间变量、indicator、SOS2/非凸分段线性结构，或针对固定序列设计专用 DP。于是二阶段会成为混合整数 recourse，经典 LP Benders 不能直接使用。

Shabtay et al. (2022) 证明 early/late work 在单机、公共 due date/window 的若干特殊结构下可用多项式或伪多项式算法求解，但这并不自动推广到任务特定窗口、多机、setup、多场景和可等待的联合模型。因此该目标可以作为后续独立 work 或特殊模型，不适合与 ET 一起作为本 work 的常规算例目标。

### 9.3 early/tardy jobs 数量

任务是否发生窗口违反是固定收费式指标。例如可用

\[
E_{j\omega}\le M^E_{j\omega}z^E_{j\omega},
\qquad
T_{j\omega}\le M^T_{j\omega}z^T_{j\omega},
\qquad
z^E_{j\omega},z^T_{j\omega}\in\{0,1\},
\]

并最小化 \(\sum z\)。正违反会迫使相应二元变量为 1；边界 \(C=a\) 或 \(C=b\) 应统一定义为 on-time，避免严格不等式的数值歧义。由于状态随场景 timing 决定，这些二元变量属于二阶段，得到整数 recourse。可选方法包括 logic-based Benders、integer L-shaped、branch-and-check 或直接 extensive-form MIP，但都不再享有当前连续 ET 子问题的简洁结构。

因此建议：Work 3 只做 ET 主模型；early/tardy work 和 number of early/tardy jobs 在文献综述中作为不同业务指标讨论，并明确列为需要混合整数 recourse 的后续扩展。若只是关注尾部服务风险，可以先考虑保持连续性的 CVaR-ET 或场景平均超额，而不是把计数硬塞入主模型。

## 10. setup 与无 setup 情形的性质

序列相关 setup 使当前问题与 TWA 的结构更接近：TWA 的 arc travel time 对应任务转换 setup time，访问顺序对应每台机器上的任务顺序，随机旅行时间对应随机加工或 setup 时间。固定离散顺序后，场景 timing 和窗口仍是连续 LP，setup 不破坏 Benders 的基本条件。

不考虑 setup 时，模型不会自动变得可分或得到统一的 SPT/EDD 规则。任务特定的 \(\rho,\lambda,\alpha,\beta\)、机器相关随机加工时间、共享 due-window 决策和下游 waiting 传播仍使顺序重要。能得到的主要简化是：时间递推少了 arc-dependent 常数，机器之间在固定分配后更容易分解，主问题也减少 setup arc 成本和部分异质性。只有在进一步加入同质系数、确定加工时间、无自愿 idle 或公共窗口等特殊条件时，才可能恢复交换性质或 DP。

因此，主模型保留 sequence-dependent setup；另设 matched no-setup 基准，用来衡量 setup 对算法难度、窗口位置和 ET 传播的影响。不能把 no-setup 结果直接解释为一般随机多机 DWA 的结构定理。

## 11. 建议的模型与实验层次

1. **核心模型 W3-Core**：位置成本 + 宽度成本 + 有限位置区间 + 长度上下界 + 免费 waiting + 期望 ET + sequence-dependent setup；无 overtime、无 waiting cost。
2. **位置锚消融 W3-Position**：保留 \(\rho_j(a_j-L_j)\)，把 \(U_j\) 设为经过验证的不活跃业务上界，用于观察纯价格锚；不能用极大 \(M\) 造成数值污染。
3. **范围锚消融 W3-Range**：令 \(\rho_j=0\)，保留真实有限 \([L_j,U_j]\)，用于观察纯硬边界锚。
4. **waiting 机制消融 W3-Wait**：令 \(\rho_j=0\) 且不使用范围，只以正 waiting cost 锚定位置时，必须同时收费 initial idle；若保留范围，则明确称为 W3-Range+Wait，而不是独立模型三。
5. **结构消融**：对核心模型做 setup/no-setup matched 比较。目标始终先保持 ET，不同时更换为 work 或 job-count，以免混淆模型结构和目标结构的影响。

核心算例生成后应先做结构审计：检查 \(U_j\) 与加工时间尺度是否匹配、\(D_j^{\min}\le D_j^{\max}\le U_j-L_j\)、三个参数支配条件的任务比例、最小宽度和位置边界命中率、正 ET 任务比例，以及目标各项的数量级。若出现“所有任务 ET=0”，先判断这是正常支付位置/宽度成本后的经济选择，还是由于范围过松、系数失衡或实现漏计成本造成的结构性退化。

## 12. 与后续 Wasserstein DRO 的兼容性

推荐的 ET 模型适合作为后续 Wasserstein DRO 的基础，因为随机加工时间或随机 setup 时间进入场景 timing 约束的右端，固定离散排程后 recourse 是连续 LP。可以先建立并验证 SAA 与 Benders，再在同一 recourse 上研究经验分布附近的 Wasserstein 模糊集和对偶 reformulation。

这并不意味着 DRO 会自动得到一个很小的闭式模型。机器顺序变量会改变不确定参数进入哪些时间递推约束，支持集、距离范数和二阶段相对完全 recourse 都需要单独处理。但 ET 保持连续 recourse，至少保留了 LP 对偶和 cut 生成的基础；若改用 early/tardy work 或 job counts，整数 recourse 会使 Wasserstein 对偶和精确分解同时复杂化。因此合理顺序是：ET-SAA 基线，Benders 比较，随后 ET-Wasserstein DRO；其他目标另立分支。

## 13. 最终判断

当前 Work 3 的核心创新不应表述为“把随机窗口写成分位数并消去变量”，因为最终精确求解仍会回到与 TWA 类似的联合线性窗口/timing 模型。真正清楚的问题差异是：机器调度没有自然的驾驶员班次 overtime，于是使用任务特定位置成本和有限承诺范围替代终端 overtime；同时加入多机分配、随机加工时间和 sequence-dependent setup，在允许场景等待的条件下联合设计排程和任务特定 due windows。

ET 目标足以使问题成立，并且是保留连续 recourse 与精确 Benders 的关键。位置成本、范围和长度界不仅用于防止数学退化，也分别表达 lead-time 价格、不可违反的承诺边界和客户可接受的时间精度。waiting cost、early/tardy work 和 early/tardy job counts 都有现实意义，但会改变问题含义或算法结构，当前应作为清楚标注的消融或后续扩展，而不是混入核心模型。

## 参考文献与证据来源

1. Çelik, Ş., Martin, L., Schrotenboer, A. H., and Van Woensel, T. (2025). Exact Two-Step Benders Decomposition for the Time Window Assignment Traveling Salesperson Problem. *Transportation Science*, 59(2), 210-228. https://doi.org/10.1287/trsc.2024.0750
2. Cavaliere, F., Fischetti, M., Roberti, R., and Salvagnin, D. (2026). Models and algorithms for the Time Window Assignment Traveling Salesperson Problem with stochastic travel times. *European Journal of Operational Research*, 329(1), 96-111. https://doi.org/10.1016/j.ejor.2025.07.034
3. Yue, Q., and Zhou, S. (2021). Due-window assignment scheduling problem with stochastic processing times. *European Journal of Operational Research*, 290(2), 453-468. https://doi.org/10.1016/j.ejor.2020.08.029
4. Janiak, A., Janiak, W. A., Krysiak, T., and Kwiatkowski, T. (2015). A survey on scheduling problems with due windows. *European Journal of Operational Research*, 242(2), 347-357. https://doi.org/10.1016/j.ejor.2014.09.043
5. Shabtay, D. (2016). Optimal restricted due date assignment in scheduling. *European Journal of Operational Research*, 252(1), 79-89. https://doi.org/10.1016/j.ejor.2015.12.043
6. Shabtay, D., Mosheiov, G., and Oron, D. (2022). Single machine scheduling with common assignable due date/due window to minimize total weighted early and late work. *European Journal of Operational Research*, 303(1), 66-77. https://doi.org/10.1016/j.ejor.2022.02.017
7. Kim, J.-G., and Lee, D.-H. (2009). Algorithms for common due-date assignment and sequencing on a single machine with sequence-dependent setup times. *Journal of the Operational Research Society*, 60(9), 1264-1272. https://doi.org/10.1057/jors.2008.95
8. 本地综述表：D:\重要文件\桌面备份\曹长新\同济大学\学习和生活\博士\研究生学习\研究方向\毕设相关\TWET\work2_DDA\2026.06相关分析记录\DDA_literature_final.xlsx。该表用于定位和交叉检查全文，不替代原论文作为最终证据。
