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

### 2.1 VRP 和路由领域确实存在显式 time-expanded pricing

2026-09-06 进一步检索后，需要修正任何可能被理解为“VRP 中没有 time-indexed pricing”的过强表述。路由领域确实存在把地点复制到离散时间层，并在 time-expanded/state-space-time network 上执行定价或拉格朗日子问题的工作，只是它们主要集中在时间依赖、车辆同步、充电、取送和多趟运输等特殊结构，而不是经典 CVRP/VRPTW 的标准求解路线。

最接近当前 TI 思路的是 Hansknecht、Joormann 和 Stiller（2021）的 time-dependent TSP。该文建立 `(vertex,time)` 的 time-expanded DAG，path pricing 是该 DAG 上的最短路。若允许图中的全部路径，定价可利用 DAG 在近线性时间内完成，但路径可能在原图重复访问顶点，因此 LP 明显较弱；作者用 `k`-cycle-free pricing 在定价时间与松弛强度之间折中。这与当前 TWET TI 的核心权衡高度相似：不保存完整访问集合可显著简化定价，但会引入 non-elementary pseudo columns。其 40 点实例的 time-expanded graph 已有约 80 万至 100 万条弧；path-based formulation 的根 LP 平均约 17 秒，arc formulation 加 path pricing 约 55 秒，直接 arc formulation 约 13 分钟，但 40 点实例一小时内没有一例证明最优。最终算法采用 two-cycle-free pricing、valid inequalities、propagation、定制 branching 和 primal heuristics；20 点实例求解 46/50，40 点实例平均剩余 gap 从约 55% 降至 19%。因此该文是“显式 TI 图 + 非基本路径松弛 + 小环强化”的直接路由先例，但不是 ng-DSSR，也没有比较 SRI-strengthened TI 与最终 elementary certificate。

Sakarya 等（2025）的 two-echelon prize-collecting VRPTW with synchronization 也在 `(location,time stamp)` 网络上做 branch-and-price，并分别求解大小车辆的 pricing。这里 time expansion 主要用于表达车辆同步、等待和硬时间窗；pricing 仍按 ESPPRC labeling 保证客户最多访问一次。其结果可到 200 客户，但这不能当成弱 TI pseudo-route pricing 的性能证据，因为“显式时间展开”和“放松 elementarity”是两个独立选择。

Mahmoudi 和 Zhou（2016）的 VRPPDTW 使用三维 state-space-time network，把地点、离散时间和车辆载客/请求状态共同放入动态规划；多车问题通过拉格朗日松弛分解为单车子问题。该方法有开源教学实现 VRPLite，但不是经典 set-partitioning BPC，而且额外 carrying state 负责维持取送一致性，不能直接等同于只记录 `(job,time)` 的弱 TI 定价。多趟 VRPTW、车辆/库存调度、充电与同步路由中还有若干 time-expanded network 用法，通常都是为处理跨路线时间协调，而不是为了替代经典 ng/elementary pricing。

因此准确结论是：

> VRP/路由领域并非没有 time-indexed pricing。已有工作明确使用 time-expanded DAG 或 state-space-time network 做列生成定价，其中 TDTSP 已直接展示“全部 time-expanded paths 定价很快但松弛弱、限制短环后更强但更慢”的权衡。当前仍未检索到在同一 BPC、同一实例和共同组件控制下，直接比较 repeat-permitting TI route pricing、TI+SRI 与最终恢复 elementary certificate 的 ng-DSSR，并进一步分析 family、软 due-window 和时间尺度效应的工作。

经典 CVRP/VRPTW 很少把这一路线单独命名为 TI，原因不是它不能实现，而是常规 labeling 已把时间作为资源隐式传播；显式展开会产生约 `O(nT)` 个节点和 `O(n^2T)` 级弧，同时仍需另行处理容量和 elementarity。只有时间同步或时变成本本身必须显式耦合时，time expansion 才更自然。

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

## 5.1 检索范围进一步收紧到调度

后续首次性判断以调度文献为主，VRP只用于说明ng-route和DSSR的技术来源，不作为直接比较证据。调度侧现有工作可分为三条线：van den Akker（2000）、Pessoa等（2010）、Oliveira和Pessoa（2020）、Bulhoes等（2020）研究time/arc-time-indexed伪排程及其强化；Kowalczyk等（2024）直接比较TIF、ATIF和decision-diagram表示；Morais等（2024）在调度中使用dynamic ng-path。2026年的open-shop BCP还采用time-indexed schedule master和elementary shortest-path pricing，并将ng-route列为后续方向。

这些文献分别覆盖了TI、图表示比较、elementary pricing和ng-path，但本次检索仍未找到一项调度研究在共同代码与共同实例上直接比较纯TI pseudo-schedule、TI+SRI和最终elementary ng-DSSR。因此本文的首次性只能落在这项具体的三方受控比较及其结构机制分析上，而不是任何单项算法。

## 6. 主要文献

1. van den Akker, Hurkens, Savelsbergh (2000), *Time-Indexed Formulations for Machine Scheduling Problems: Column Generation*, INFORMS Journal on Computing 12(2), 111-124. DOI: 10.1287/ijoc.12.2.111.11896.
2. Baldacci, Mingozzi, Roberti (2011), *New Route Relaxation and Pricing Strategies for the Vehicle Routing Problem*, Operations Research 59(5), 1269-1283. DOI: 10.1287/opre.1110.0975.
3. Pessoa et al. (2010), *Exact algorithm over an arc-time-indexed formulation for parallel machine scheduling problems*, Mathematical Programming Computation 2, 259-290. DOI: 10.1007/s12532-010-0019-z.
4. Martinelli, Pecin, Poggi (2014), *Efficient elementary and restricted non-elementary route pricing*, European Journal of Operational Research 239(1), 102-111. DOI: 10.1016/j.ejor.2014.05.005.
5. Oliveira, Pessoa (2020), *An Improved Branch-Cut-and-Price Algorithm for Parallel Machine Scheduling Problems*, INFORMS Journal on Computing 32(1), 90-100. DOI: 10.1287/ijoc.2018.0854.
6. Bulhoes et al. (2020), *On the exact solution of a large class of parallel machine scheduling problems*, Journal of Scheduling 23, 411-429. DOI: 10.1007/s10951-020-00640-z.
7. Morais, Bulhoes, Subramanian (2024), *Exact and heuristic algorithms for minimizing the makespan on a single machine scheduling problem with sequence-dependent setup times and release dates*, European Journal of Operational Research 315(2), 442-453. DOI: 10.1016/j.ejor.2023.11.024.
8. Kowalczyk, Leus, Hojny, Ropke (2024), *A Flow-Based Formulation for Parallel Machine Scheduling Using Decision Diagrams*, INFORMS Journal on Computing 36(6), 1696-1714.
9. Hansknecht, Joormann, Stiller (2021), *Dynamic Shortest Paths Methods for the Time-Dependent TSP*, Algorithms 14(1), 21. DOI: 10.3390/a14010021.
10. Mahmoudi, Zhou (2016), *Finding optimal solutions for vehicle routing problem with pickup and delivery services with time windows: A dynamic programming approach based on state-space-time network representations*, Transportation Research Part B 89, 19-42. DOI: 10.1016/j.trb.2016.03.009.
11. Sakarya et al. (2025), *Two-echelon prize-collecting vehicle routing with time windows and vehicle synchronization: A branch-and-price approach*, Transportation Research Part C 171, 104987. DOI: 10.1016/j.trc.2024.104987.
