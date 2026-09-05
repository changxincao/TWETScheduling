# ng-DSSR 与 time-indexed 定价比较的文献定位

日期：2026-09-05

## 1. 研究问题

当前需要确认的不是 time-indexed、ng-route、DSSR 或 rank-1 cut 是否分别已有，而是是否已有工作在同一个类似车辆路径的定价问题中，直接比较：

1. 允许重复访问的 time-indexed 伪排程定价；
2. 用 rank-1/SRI 强化的 time-indexed 定价；
3. 通过 ng-DSSR 最终恢复 elementary certificate 的路径定价。

截至本次检索，没有找到与上述三者、当前 TWET 目标和实验维度完全相同的直接比较。但不能据此宽泛声称“首次把 ng 用于调度”或“首次比较不同定价图”。

## 2. 为什么 VRP 文献很少写成 TI 与 ng-DSSR 的比较

VRP 的 labeling 本来就把时间作为资源状态。因此，显式 time-expanded DAG 在 VRP 中通常不会被当成一个独立的“时间模型”与 labeling 比较；真正决定松弛强弱的是该图允许什么路线。若图只记录当前位置和时间、没有完整访问集合，它实际上生成的是允许重复客户的 pseudo-route。对应的经典比较对象是 q-route、k-cycle、ng-route 和 elementary route，而不是名为“TI”的另一类定价算法。

Baldacci、Mingozzi 和 Roberti（2011）提出 ng-route relaxation；Martinelli、Pecin 和 Poggi（2014）进一步比较 elementary 与受限非 elementary route pricing。这些工作研究的是路线记忆强度和 labeling 效率，没有加入与当前 time-indexed 伪排程引擎对应的受控比较。

## 3. 调度领域的直接前史

van den Akker、Hurkens 和 Savelsbergh（2000）已经对 time-indexed formulation 做 Dantzig-Wolfe 分解，列是可能遗漏或重复任务的 pseudo-schedules。Pessoa 等（2010）、Oliveira 和 Pessoa（2020）以及 Bulhoes 等（2020）继续发展 arc-time-indexed BCP、cuts、fixing 和 stabilization。这些工作构成当前 TI/TI+SRI 路线的直接前史，但都没有与最终恢复 elementary schedule 的 ng-DSSR 做同框比较。

最接近当前问题的是 Kowalczyk、Leus、Hojny 和 Ropke（2024）的 decision-diagram flow formulation。该文在相同 CG 框架中比较 TIF、ATIF、BDDF 和只禁止连续重复的 BDDFr。它证明已有文献确实比较过较弱的 time-indexed 伪排程网络与更强的图表示，因此不能声称“首次比较 TI 和更强路径表示”。但是 BDDFr 仍允许一般重复任务，不是 ng-DSSR，也不提供 elementary certificate。

该文的 root-CG 数据也说明不同表示的规模差异：在其加权拖期基准上，n=40/50/100、m=2 时，TIF 平均约为 0.59/1.12/14.43 秒，ATIF 为 2.16/5.23/90.42 秒，BDDFr 为 1.39/2.57/27.26 秒。其重点是 formulation size、LP gap 和通用求解性能，不是 family setup、软 due window 或时间尺度对重复松弛的作用机制。

## 4. 已有 ng-path 调度工作

Morais、Bulhoes 和 Subramanian（2024）已将 dynamic ng-path relaxation 与 bidirectional labeling 用于带 release date 和 sequence-dependent setup 的单机 makespan 问题。该文将 B&P 与已有 MILP 比较，而不是在共同框架中系统比较纯 time-indexed、TI+SRI 和 ng-DSSR。因此不能声称“首次将 VRP 的 ng-relaxation 引入调度”，但当前检索也没有发现它报告本项目所做的三算法消融。

2026 年的 open-shop BCP 工作仍把 ng-route relaxation 列为后续研究方向，也侧面说明 ng 与 time-indexed 调度分解的系统结合并非成熟的标准实验范式；这只能作为领域现状证据，不能单独证明首次性。

## 5. 当前可以主张和不能主张的内容

不能主张：

1. 首次提出 time-indexed 列生成；
2. 首次在调度中使用 arc-time-indexed pseudo-schedule；
3. 首次在调度中使用 ng-path relaxation；
4. 首次比较 time-indexed 与任意更强图表示。

当前较稳妥的主张是：

> 据我们目前对 VRP route-relaxation、time/arc-time-indexed scheduling BCP、decision-diagram flow formulation 和 dynamic ng-path scheduling 文献的检索，尚未发现针对 identical-parallel-machine TWET，在统一 BPC 框架和共同实验控制下，直接比较 time-indexed pseudo-schedule pricing、SRI-strengthened time-indexed pricing 与最终恢复 elementary certificate 的 ng-DSSR pricing，并系统分析 setup family 结构、软 due-window 宽度和整数时间尺度如何改变三者相对性能的工作。

这里的潜在贡献不是三个组件本身，而是受控比较和机制结论：family 块结构会使重复伪排程形成低成本分数流并削弱 TI；时间尺度放大直接复制离散时间状态，使 TI/TI+SRI 单次定价膨胀，而连续时间/PWLF 的 ng-DSSR 不按同一比例增长；SRI 则在 master 强化和 rank-1 pricing 成本之间产生新的权衡。

正式论文宜使用“to the best of our knowledge”和明确的问题范围，不能写成无边界的 first-ever claim。投稿前还应沿 Morais（2024）和 Kowalczyk（2024）的引用与被引文献做一次最终更新检索，重点核对 Morais 全文是否存在未在摘要中体现的内部 no-ng/TI 消融。

## 6. 主要文献

1. van den Akker, Hurkens, Savelsbergh (2000), *Time-Indexed Formulations for Machine Scheduling Problems: Column Generation*, INFORMS Journal on Computing 12(2), 111-124. DOI: 10.1287/ijoc.12.2.111.11896.
2. Baldacci, Mingozzi, Roberti (2011), *New Route Relaxation and Pricing Strategies for the Vehicle Routing Problem*, Operations Research 59(5), 1269-1283. DOI: 10.1287/opre.1110.0975.
3. Pessoa et al. (2010), *Exact algorithm over an arc-time-indexed formulation for parallel machine scheduling problems*, Mathematical Programming Computation 2, 259-290. DOI: 10.1007/s12532-010-0019-z.
4. Martinelli, Pecin, Poggi (2014), *Efficient elementary and restricted non-elementary route pricing*, European Journal of Operational Research 239(1), 102-111. DOI: 10.1016/j.ejor.2014.05.005.
5. Oliveira, Pessoa (2020), *An Improved Branch-Cut-and-Price Algorithm for Parallel Machine Scheduling Problems*, INFORMS Journal on Computing 32(1), 90-100. DOI: 10.1287/ijoc.2018.0854.
6. Bulhoes et al. (2020), *On the exact solution of a large class of parallel machine scheduling problems*, Journal of Scheduling 23, 411-429. DOI: 10.1007/s10951-020-00640-z.
7. Morais, Bulhoes, Subramanian (2024), *Exact and heuristic algorithms for minimizing the makespan on a single machine scheduling problem with sequence-dependent setup times and release dates*, European Journal of Operational Research 315(2), 442-453. DOI: 10.1016/j.ejor.2023.11.024.
8. Kowalczyk, Leus, Hojny, Ropke (2024), *A Flow-Based Formulation for Parallel Machine Scheduling Using Decision Diagrams*, INFORMS Journal on Computing 36(6), 1696-1714.

