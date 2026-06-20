# Time-indexed graph pricing 实验记录

## 1. 背景

2026-06-20 讨论了参考论文 `On the exact solution of a large class of parallel machine scheduling problems` 中的 time-indexed graph pricing，先实现不带 cut 的版本，用来和当前基于分段函数占优的 exact pricing 做效率和列生成效果对照。

论文中的图定价把单机调度列表示成 time-indexed DAG 上的一条路径。节点为 `(lastJob, t)`，表示当前最后一个处理任务为 `lastJob`，当前时间为 `t`；等待弧让时间增加，处理弧从 `i` 到 `j` 并把时间推进到 `t+s_ij+p_j`。无 cut 时该 pricing 是 DAG 最短路。论文允许 pseudo-schedule，即同一 job 可以在路径中重复出现。

当前 TWET-BPC 的 RMP 列覆盖系数仍是 `TWETColumn.containsJob(job)` 的 0/1 语义，而论文 DWM 对重复访问路径的列系数口径不同。因此本实现只作为实验定价器，默认关闭，不作为当前主线 exact pricing 的正确性证书。

## 2. 当前实现

新增 `TimeIndexedGraphPricingEngine`，通过 `TWETBPCConfig.useTimeIndexedGraphPricing` 打开。打开后，Context 中仍先运行现有启发式 pricing，然后用 time-indexed graph pricing 替换当前 exact pricing；默认配置不受影响。

当前建图不显式缓存所有 time-expanded 弧，而是在 DP 扫描时按 `(lastJob,t,nextJob)` 即时生成处理弧。为避免热路径反复扫分段函数和节点状态，初始化时预先缓存 job-time penalty、arc duration、普通处理弧 forbidden 状态以及结束弧 forbidden 状态。等待弧成本为 0，处理弧 reduced cost 为：

`setupCost(last,next) + penalty_next(completion) - jobDual[next] - arcDual(last,next)`，

若 `last=0`，再减去 `machineDual`。结束弧只扣 `arcDual(last,sink)`。forbidden arc、preprocessing forbidden arc、pricingOnly arc 都在处理弧或结束弧生成时跳过；required arc 仍通过 RMP 分支行和 dual 影响 reduced cost，不强制每条 pricing 路径包含该弧。

当前 time-indexed graph pricing 本身只做完整单向 DAG DP，不在 pricing 内部额外叠加 completion bound 或 suffix-bound 剪枝。等待弧和处理弧只受硬时间窗、预处理/分支 forbidden arc、pricingOnly arc，以及后续 paper reduced-cost fixing 写入的 time-indexed pricing-only arc 过滤。

返回列时使用 `TWETColumnEvaluator` 重算真实 TWET 成本，再以 `ColumnSource.PRICING_EXACT` 交给现有 Pool/RMP。候选列按 `SequenceSignature` 去重，同一序列只保留 reduced cost 更小的候选，最多返回 `maxExactPricingColumns` 条。

## 3. 语义风险

这个 pricing 的最短路 reduced cost 按论文 pseudo-schedule 口径每次访问 job 都扣一次 job dual；当前 RMP 覆盖行对同一列中的重复 job 只看 0/1 是否包含。因此如果 graph pricing 返回重复 job 路径，它作为当前 RMP 列使用时不是严格的论文 DWM 列，也不是当前 elementary pricing 的闭合证书。

由此当前结论是：该实现可用于实验比较图定价在时间离散 DAG 上的速度、重复 job 候选比例、以及和函数占优 pricing 的列生成差异；若要把它作为严格 exact pricing，需要后续二选一：要么修改主问题列系数以支持重复访问次数，要么在 pricing 中加入 elementary/ng/DSSR 机制，只返回能证明闭合的 elementary 列。

## 4. 验证

已对新增 `TimeIndexedGraphPricingEngine`、`TWETBPCConfig`、`TWETBPCContext` 做 focused `javac` 编译：

`javac -encoding UTF-8 -cp target/classes;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar -d target/classes ...`

编译通过。当前机器命令行没有 `mvn`，因此未运行 Maven 全量编译。后续需要在小算例上打开 `useTimeIndexedGraphPricing=true` 做运行对照，重点观察 `bestPseudoRC`、`repeatedJobCandidates`、返回列是否重复 job，以及 LP bound 是否出现明显语义偏差。

## 5. 2026-06-20 修正：区分 paper arc fixing 和当前 completion-bound fixing

上一版实现把 time-indexed graph pricing 内部的后向最短路当成 suffix bound，在 forward DP 扩展时用 `prefix + arc + suffix >= 0` 提前剪枝。这个做法虽然可作为 shortest-path 加速，但不是论文 Algorithm 7 的 reduced-cost arc fixing，也容易和当前项目的 completion-bound arc fixing 混在一起。因此已撤回该 pricing 内部剪枝。

论文里的 arc fixing 现在单独放在节点 column generation 已闭合之后执行。它用当前节点 LP 下界 `LB` 和已有整数上界 `UB`，重新在 full-horizon time-expanded graph 上计算 forward shortest distance 和 backward shortest distance；对每条具体处理弧 `(from,to,t)` 计算穿过该弧的最小 reduced cost `cmin`，若 `cmin >= UB - LB`，则把该具体时间弧写入 node 的 time-indexed pricing-only 禁弧集合，供子节点的 graph pricing 避开。该 fixing 不使用当前 TWET 的 completion bound，也不调用 subtree arc elimination；在 `useTimeIndexedGraphPricing=true` 时，原 completion-bound subtree elimination 路径会跳过，避免两套 fixing 语义叠加。

当前实现只固定处理弧 `(from,to,t)`，包括 source 到 job 的弧；暂不做论文里的 idle/sink arc 清理、顶点压缩和 `t*` 重算。这样先保证 no-cut graph pricing 和 paper reduced-cost arc fixing 的基本口径清楚，再决定是否补完整图压缩步骤。

## 6. 与论文细节的再次核对

再次读取论文后，当前实现和论文一致的部分是：图节点按 `(lastJob,t)` 表示；等待弧相当于论文 `A4`，成本为 0；处理弧相当于 `A1/A2`，时间推进为 `t+s_ij+p_j`，成本按 job 完工时间函数计算；机器数 dual 只在 source 到首任务弧上扣除；no-cut 情况下每个顶点只保留一个最短距离，等价于无额外 cut state 的 shortest-path pricing。reduced-cost arc fixing 也已经放到节点 column generation 闭合之后，用 `UB-LB` 和 full-horizon forward/backward shortest distance 计算穿过 `(from,to,t)` 的最小 reduced cost，这一点符合 Algorithm 7 的核心逻辑。

仍然不等同完整论文版的地方也要明确。第一，当前只实现同质机单图口径，没有按 machine type `k` 拆多张图。第二，当前 no-cut 实验不带论文的 limited-memory rank-1 cut state，因此 Algorithm 7 中两侧 label 合并时的 `S_l+S'_l>=beta_l` 修正没有实现；这和“先做无 cut 版本”的目标一致。第三，当前只 fixing 处理弧 `(from,to,t)`，没有继续删除冗余 idle arc、sink arc，也没有做图顶点压缩和 `t*` 重算。第四，论文 DWM 的列系数按路径中 job 被处理次数计数，而当前 TWETColumn 仍按 `containsJob` 的 0/1 覆盖语义进入 RMP，所以该 graph pricing 只能作为实验对照，不能直接替代当前 elementary/ng exact pricing 的正确性证明。第五，TWET 当前目标包含 setup cost 和分支 arc dual，这比论文“目标函数只定义在完工时间上、setup 主要影响时间推进”的基础公式多了问题特定项；这些项是为了和现有 TWET RMP reduced-cost 口径一致，而不是论文原式本身。

因此当前结论是：no-cut graph pricing 的 DAG 最短路骨架和 Algorithm 7 的 reduced-cost fixing 核心判定已经对齐，但不是完整论文 BCP 实现。后续若要声称“和论文完全一致”，至少还需要补 machine type 多图、DWM 路径系数口径、limited-memory rank-1 cut state、idle/sink/vertex cleanup，以及与论文相同的 branching/cut 主循环。

## 7. 2026-06-20 修正：DWM 访问次数系数和图清理

根据论文 DWM 的列定义，路径中同一 job 被处理多次时，该列在对应覆盖行里的系数应为访问次数，而不是简单的 0/1 `containsJob`。本次已把 `TWETColumn` 增加为同时保存 job visit count 和普通 arc visit count：普通 elementary/ng 列仍自然是 0/1；time-indexed pseudo-schedule 列若重复访问 job，则 RMP 覆盖行、arc 分支行、当前解 arc value 以及 RMIH 覆盖模型都会使用访问次数系数。这样 graph pricing 返回的 pseudo-schedule 不再只是“实验对照列”，而是按论文 DWM 口径进入当前 master。需要注意的是，SRI cut 识别/系数仍按当前 cut 语义单独处理，不在这次 no-cut graph pricing 修正范围内。

Algorithm 7 的 reduced-cost fixing 也补齐为三类时间弧：processing arc `(from,to,t)`、idle arc `(from,from,t)` 和 end/sink arc `(from,0,t)`。fixing 后会重算一次 forward/backward shortest distance，并做保守的图清理：删除不可达、不可共达、时间不可行的 processing/idle/end arc；对 job 顶点上的冗余 idle arc，如果该时刻以后已经没有可行 processing outgoing，且可直接走 end arc，则禁止该 idle arc。这相当于当前隐式图上的 vertex/arc compression；代码没有显式删除顶点对象，因为图本身按 DP 状态即时生成，压缩表现为把对应 time-indexed pricing-only arc 写入 node。

关于 `t*`，当前实现仍是单向 full-horizon DAG shortest path，而不是论文后续带 cut 时的 bidirectional labeling 过程，因此没有一个需要重算的中间分界 `t*`。若后续实现论文式 bidirectional time-indexed graph pricing，再把 `t*` 作为该双向定价器自己的状态重算；在当前单向 DP 版本里强行加 `t*` 没有对应语义。

本次 focused `javac` 已通过，编译范围包括 `TimeIndexedGraphPricingEngine`、`TWETColumn`、`LP`、`RestrictedMasterIntegerHeuristic`、`TWETMasterSolution`、`Node`、`Tree`、`TWETBPCConfig`、`TWETBPCContext`。后续如果打开 `useTimeIndexedGraphPricing=true` 做实验，要重点观察重复 job pseudo-schedule 对 master bound 的影响，以及 idle/end/time-indexed fixing 的数量是否符合预期。

## 8. 2026-06-20 澄清：论文四类弧、根节点 horizon 和 fixing 等价范围

论文图中弧集确实分为四类：`A1` 为 job-to-job processing arc，`A2` 为 dummy/source-to-job processing arc，`A3` 为 job-to-dummy/sink end arc，`A4` 为 idle arc。当前代码里把 `A1/A2` 共用一套 processing arc 递推实现，只在 `from=0` 时额外扣 machine dual；`A3` 对应 end candidate/end arc fixing；`A4` 对应 wait/idle arc。因此实现层面曾简称“三类操作弧”，但按论文分类应表述为四类弧，其中 `A1/A2` 只是代码复用。

当前 graph pricing 的 horizon 仍直接取 `data.CmaxH`，并只用 `hardWindowStart/End` 把 job-time penalty 表中不可行时间置为无穷大。也就是说，它没有像主线函数定价那样在根节点 no-cut 时使用 dual profitable window 进一步缩短每个 job 的有效右端和全局 `pricingHorizon`。若要严格做高效版本，根节点无 cut、无分支 dual 干扰且三角不等式假设成立时，可以按现有主线的 dual-window 逻辑构造 graph pricing 的 effective window，并把 horizon 收到这些右端的最大值；但进入 RMP 的列成本仍应按完整 sequence evaluator 重算，不能把 dual-window 下的临时 reduced-cost 口径当作永久列成本。

关于 reduced-cost arc fixing，论文 Algorithm 7 在完整 labeling 框架下运行 `ForwardLabeling(T,A)` 和 `BackwardLabeling(0,A)`，再对每条弧比较 `F(i,t)` 与 `B(j,t+t(i,j))` 中 label 对的最小拼接 reduced cost；有 active limited-memory rank-1 cuts 时，还要按两侧 cut state 做 `S_l+S'_l>=beta_l` 修正。当前 no-cut graph pricing 没有 cut state，且每个 time-expanded vertex 只保留一个最短距离，因此这个 label-pair minimum 退化为 `forwardDistance + arcReducedCost + backwardDistance`。所以当前 fixing 是 Algorithm 7 的 no-cut 最短路等价形式，不是带 cut 的完整论文版本。若后续接入 limited-memory rank-1 cuts 或论文双向 labeling，就必须回到 bucket label-pair 口径，不能继续只用单一 shortest distance。

当前 forward/backward distance 不跨 RMP 轮次缓存，因为每次加列后 LP dual 会变化，arc reduced cost 随之变化；arc fixing 只在当前节点 column generation 闭合后使用最后一组 dual，理论上可以复用最后一次“证明无负列”的 forward distance，再额外算 backward distance，但代码暂未做这项优化。fixing 删除弧并做图清理后，后续若还要继续在已删弧图上判断，又需要重新计算距离。

## 9. 2026-06-20 优化：root dual-window horizon 与 forward distance 复用

本次把 graph pricing 的离散 horizon 从固定 `data.CmaxH` 改为统一的 `GraphWindow` 计算。非根节点或已有 active cuts 时仍使用静态 hard window；root/no-cut 时复用主线 pi-window 思路，对每个 job 用 `max(hardStart, d_e - pi_j/w_e)` 和 `min(hardEnd, d_l + pi_j/w_t)` 收缩有效完工窗口，再把 graph horizon 收到所有有效右端的最大值。这样不会改最终列成本口径：DAG DP 仍只负责找负 reduced-cost pseudo-schedule，进入 RMP 前仍用 `TWETColumnEvaluator` 按完整 sequence 重算 objective cost。

arc fixing 方面新增了最后一次 graph pricing forward shortest distance 的安全复用。缓存只保存 forward distance；fingerprint 包含 node id/depth、time-indexed pricing-only 禁弧数量、restricted/active cut 数量、LP objective、machine dual、所有 job dual 和 arc dual，以及 horizon/debug 配置。只有 fingerprint 完全一致时，post-CG reduced-cost fixing 才复用该 forward distance；否则自动重算。由于 fixing 删除弧和 cleanup 后图已经变化，cleanup 内部仍会重算 forward/backward distance。

这两个优化不改变论文 no-cut reduced-cost 语义，只减少 root graph 状态数和最后一次 pricing 到 arc fixing 之间的重复 forward DP。

## 10. 2026-06-20 补充：单向 DAG DP 的停止点和 sink 处理

当前 no-cut DAG pricing 不在找到第一条负 reduced-cost 路径时停止，而是按 `t=0..horizon` 扫完整张隐式时间展开图。每个状态 `(lastJob,t)` 只保存一条最短前驱路径；当该状态可以通过 end arc 连接到 sink 时，代码直接计算 `dist(lastJob,t)+sinkArcReducedCost(lastJob)` 并把负 reduced-cost 的状态恢复成候选列。

这样做等价于考虑了论文 `A3` 的 job-to-sink 弧，但没有把所有 end arc 显式 relax 到同一个 `(0,T)` 状态。原因是如果只维护一个 sink 最短距离，最后只能恢复全局最短的一条路径；而当前实现希望一次返回多条负列，所以扫描所有可结束状态，并从每个状态恢复一条最短路径。该实现仍不是完整 k-shortest path：同一个 `(lastJob,t)` 只保留一条最短路径，其他到达同一状态的负路径会被覆盖。若后续要严格模拟论文每轮多列的 labeling 生成，应改为 bucket labels 或 k-shortest/multi-predecessor，而不是单一 `dist`。

## 11. 2026-06-20 对照原文效果的差异分析

当前 no-cut time-indexed graph pricing 与原文完整 BCP 仍有明显距离。当前实现已经具备 `(lastJob,t)` DAG 最短路、pseudo-schedule 访问次数系数、root/no-cut dual-window horizon、以及 no-cut 口径下的 reduced-cost time-indexed arc fixing；但它不是原文完整求解器。原文效果好的原因并不是单独一个 DAG pricing，而是图定价、两阶段列返回、limited-memory rank-1 cuts、图清理、分支策略、高质量初始上界和实例结构共同作用。

当前实验中，time-indexed exact pricing 单次确实很快。40-2 zero setup 完整 run 中 exact pricing 只有 `4.376s/87 calls`，但总时间 `274.001s`，其中启发式 pricing 达到 `192.498s/339 calls`，列池 `36085`。关闭启发式且不限制 exact 返回列数时，root 列池膨胀到 7 万以上仍未闭合；限制 `maxExactColumns=1` 后列池受控，但 380s 时 gap 仍为 `4.2618%`。这说明当前主要问题不是 DAG DP 本身慢，而是列返回策略和主问题迭代没有达到原文那种平衡：多列返回会淹没 RMP，单列返回又导致 LP/pricing 循环过多。

原文中至少有几类技术当前没有完整使用。第一，原文列生成不是简单“扫完整图然后把所有负状态恢复成列”，而有更强的候选控制和证明无负列的阶段区分；这能避免 root 被大量 pseudo-schedule 负列淹没。第二，原文的 rank-1/limited-memory cut 是核心组件，作用是显著提高 root lower bound 和减少树规模；我们当前 no-cut 版本自然缺少这部分 bound 提升。第三，原文 Algorithm 7 的 fixing 在完整框架中配合 idle/end arc 清理、顶点压缩、`t*`/双向 labeling 以及 cut state 修正；当前只做了隐式图上的 no-cut shortest-distance 等价 fixing，虽然已经补了 processing/idle/end 弧，但还不是完整压缩版。第四，原文有更贴合 arc-time-indexed DWM 的 branching/cut 主循环，而当前仍把 time-indexed pricing 接进已有 TWET-BPC 的 arc branching、RMIH 和启发式 pricing 流程，组件之间并非原文原生协同。第五，原文非常依赖强初始上界，实验也明确说明更好的 UILS 上界会改善 gap、节点数和时间；我们虽然有 ALNS/RMIH，但和原文实例及启发式口径不完全相同。

因此，不能直接用当前 no-cut graph pricing 的表现去判断原文方法本身。当前结果更准确地说明：DAG pricing 骨架在本项目里是快的，但若没有原文的候选控制、cut 加强和图/树压缩，它会把压力转移到 RMP、启发式和分支搜索。若继续推进，优先级应是先补原文式两阶段/限量候选返回，控制每轮进入 RMP 的 pseudo-schedule 列；其次再评估 rank-1/limited-memory cut 与 time-indexed fixing 的协同，而不是简单地关闭启发式或每轮只加一条最优列。

## 12. 2026-06-20 no-cut 原文差异与图清理补充

进一步讨论时明确：这里暂时不考虑 cut。即使只看 no-cut，当前实现和原文仍有几处会影响效率的差异。原文的 40-2 no-cut 表现好，不能只归因于“DAG 最短路”这一点；它还依赖更原生的 time-indexed 主流程、受控列返回、图压缩和与该图结构匹配的分支/上界策略。当前 TWET-BPC 把 graph pricing 插入已有流程后，DAG exact 本身很快，但大量时间转移到了现有启发式 pricing、RMP 多轮重解和 pseudo-schedule 列池管理上。

本次先补一个低风险 no-cut 图清理优化。原来的 cleanup 在判断某条 idle arc 是否冗余时，会对每个 `(job,t)` 现场向后扫描所有未来时刻和后继 job，只要未来存在任意时间可行 processing arc，就保留当前 idle。这个判断偏弱，也有重复扫描。现在改成一次反向预处理 `usefulProcessingOutgoingAtOrAfter[job][t]`：只有未来存在“当前图中未禁、完成时间可行、后缀可共达、arc reduced cost 有效”的 processing arc，才认为等待仍有意义。若当前 job 可以直接结束，且后续再等待已经无法到达任何这种有效处理弧，则该 idle arc 只会推迟同一条机器路径的结束，不会改善 reduced cost，可以安全删除。

这个修改仍不等同完整原文图压缩。当前代码没有显式维护压缩后的顶点集合，而是通过 `Node.forbidTimeIndexedPricingOnlyArc()` 在隐式图中禁掉具体 time-indexed arc；也没有把候选列生成改成原文式两阶段/限量 bucket labels。因此它只能改善 post-CG fixing/cleanup 的强度和常数，不会解决 root 中列返回策略不平衡的问题。后续 no-cut 方向更关键的差异仍是：每轮候选列返回应从“所有负状态/只取 best-1”改成原文式受控批量；branching 应尽量和 time-indexed arc 结构一致；启发式 pricing 应避免在 graph exact 很快时反而成为主要耗时。

验证方面，本次对 `TimeIndexedGraphPricingEngine.java` 做 focused `javac` 编译通过。尚未重跑 40-2 zero setup 完整实验；预期该改动主要影响 post-CG time-indexed arc fixing 的 cleanup 数量和耗时，对 root pricing 本身不会有决定性改变。

## 13. 2026-06-20 原文 no-cut 列返回策略核对

重新核对原文第 4、5、6 节后，no-cut 情况下最关键的差异不是 cut，而是 column generation 的两阶段列返回和 bidirectional bucket labeling。原文 pricing 本体是双向 labeling：forward 从 `(0,0)` 扩到阈值 `t*` 之前，backward 从 `(0,T)` 反向扩到 `t*` 之后，label 存在 bucket `F(j,t)` 和 `B(j,t)` 中；最终在同一 `(j,t)` bucket 上做 node concatenation。无 cut 时 label state 向量为空，dominance 退化为同一 `(j,t)` bucket 中 reduced cost 更小者占优，但仍然是 bucket label 结构，而不是当前代码中每个 `(lastJob,t)` 只保留一条 shortest predecessor 的单一距离表。

原文 column generation 明确分两阶段。第一阶段是 heuristic pricing：在 labeling algorithm 的每个 bucket 中，最多只扩展一个 label，也就是 reduced cost 最小的那个 label。这个阶段如果能找到负 reduced-cost column，就继续用这种便宜方式补列；当 heuristic 找不到负列时，才进入第二阶段。第二阶段才是 exact pricing，并且一直运行到 column generation 收敛。每次 column generation 迭代中，每个 pricing subproblem 在第一阶段最多生成 50 条负列，在第二阶段最多生成 300 条负列。也就是说，原文既不是“发现所有负列都加”，也不是“只加最优 1 条”，而是在 cheap heuristic 和 exact proof 之间用固定批量控制 RMP 增长。

这解释了当前实验的现象：关闭启发式且不限制 exact 返回列数时，root 被 pseudo-schedule 负列淹没；`maxExactColumns=1` 时列池受控但 RMP/pricing 循环过多；启用我们现有启发式时 exact graph 很快，但总时间被现有 tabu-style heuristic pricing 拖住。原文的 heuristic pricing 和我们当前 `HeuristicPricingEngine` 不是一回事：原文 heuristic 是同一个 time-indexed bucket labeling 的限制版，天然和 exact graph 使用同一套 reduced-cost、禁弧和状态结构；我们当前启发式是基于已有列 seed 的 tabu/repair 搜索，会生成大量候选并重算序列，不是原文 graph pricing 的第一阶段。

因此，要按 no-cut 原文继续对齐，优先事项应是：第一，实现 time-indexed graph 自己的 heuristic stage，即每个 bucket 只扩展当前最小 reduced-cost label，而不是调用现有 tabu heuristic；第二，把 exact stage 改成 bucket label/multi-label 口径，至少允许每个 `(j,t)` bucket 保留多条候选 label，用于一轮返回受控批量列，而不是单一 shortest predecessor；第三，按原文设置每个 pricing subproblem 第一阶段最多 50 列、第二阶段最多 300 列，并让第一阶段失败后才进入 exact stage；第四，post-CG arc fixing 后根据剩余顶点集合重算 `t*`，并在后续 bidirectional labeling 中使用该阈值；第五，branching 尽量转向原文的 job-machine assignment 和 immediate-precedence 聚合变量，这些约束只改 arc reduced cost，不改变 pricing 结构。当前已做的 root dual-window、no-cut reduced-cost fixing 和 idle cleanup 是有用补充，但不是列返回策略的核心。

## 14. 2026-06-20 修正：no-cut 与 cut-aware pricing 的返回策略区分

继续核对后修正上一节表述：原文第 4 节给出的 bidirectional bucket labeling 是统一的 cut-aware pricing 框架。它允许 active limited-memory rank-1 cuts 存在，因此同一个 `(j,t)` bucket 中可能有不同 cut state 的多个 label，需要 dominance、双向拼接和 cut state 修正。no-cut 情况是这个框架的特例，此时 active cut 集合为空，label state 向量为空；同一个 `(j,t)` bucket 中 reduced cost 更小的 label 会直接支配更大的 label，因此每个 bucket 实际只需要保留一个最短距离。换句话说，no-cut 下用单向或双向 shortest path DP 都可以得到 exact pricing，不应把“实现 cut-aware multi-label bucket”作为 no-cut 对齐的必要条件。

原文第 6 节提到的两阶段 column generation 也要按这个口径理解。第一阶段“每个 bucket 最多扩展一个 reduced-cost 最小 label”的 heuristic pricing，主要是在有 cut state、一个 bucket 可有多 label 时降低定价成本；no-cut 时这个限制基本退化为 exact shortest path，因为本来每个 bucket 就只有一个有效最优 label。第二阶段 exact pricing 才需要完整处理多 label/cut state，直到收敛。因此，当前 no-cut 版本与原文的关键差异，不是没有 cut-aware 双向 multi-label 本身，而是：没有按原文的 graph-native column generation 流程组织列返回与停止；没有使用原文的 dual stabilization；branching 不是原文的 job-machine assignment / immediate-precedence 聚合变量；graph cleanup/t* 更新还不完整；实例、机器类型和初始上界口径也不同。

由此后续 no-cut 对齐优先级也要调整。第一优先不应是完整 cut-aware bucket multi-label，而是先做 graph-native 的列返回控制：no-cut shortest path 每轮不要“所有负 end state 全加”，也不要只加 best-1，而应模仿原文的 column generation 批量上限，在每个 machine type/subproblem 中返回受控数量的最优负路径。由于当前单一 predecessor 表无法恢复同一 `(j,t)` 的次优路径，如果要返回一批列，需要实现 k-shortest / multi-predecessor / limited candidate label 机制，但这是为了受控批量列，不是为了 no-cut 正确性。第二，补 dual stabilization 或至少先诊断当前 dual 震荡是否导致 pseudo-schedule 大量波动。第三，补原文聚合 branching；这些 branching 只改 arc reduced cost，不改变 no-cut shortest path 结构。

## 15. 2026-06-20 实现：no-cut 单 predecessor 批量返回与 cleanup 细节

本次按 no-cut 口径只做低风险修改。第一，`TimeIndexedGraphPricingEngine` 继续采用单一 predecessor 表：每个 `(lastJob,t)` 状态只保留一条最短前驱路径，每个可结束 end state 最多恢复一条 pseudo-schedule。候选列按序列签名去重，保留 reduced cost 更小的版本，最后按 reduced cost 排序返回前 `timeIndexedGraphMaxExactPricingColumns` 条。该参数默认 300，对齐原文 exact stage 的每个 pricing subproblem 批量上限；它独立于普通 exact pricing 的 `maxExactPricingColumns`，避免影响当前 elementary/ng 定价器。

第二，当前不实现原文 cut-aware heuristic pricing。没有 active cuts 时，每个 bucket 的 cut state 为空，“每 bucket 只扩展 reduced cost 最小 label”的 heuristic stage 基本退化为 shortest path exact pricing；因此现阶段继续不新增 graph heuristic，也不改现有 `HeuristicPricingEngine`。如果后续要减少 root 的 pseudo-schedule 列波动，应优先在 graph exact 的候选返回数量和去重策略上调参，而不是把 cut-aware 多 label 框架提前引进来。

第三，图清理仍保持隐式 DP 图口径，不引入 `t*`。当前 no-cut 版本是单向 full-horizon shortest path，不存在需要重算的双向分界点。cleanup 负责的是 time-indexed pricing-only arc 的删除：processing arc、idle arc、end arc 都会在 reduced-cost fixing 后按不可达、不可共达、时间不可行和冗余等待做清理。本次只补了 end arc 的重复禁止检查，避免已经被 fixing 写入 node 的 end arc 在 cleanup 中再次计数或重复写入。更完整的显式顶点压缩可以后续再做，但不应把 `t*` 作为当前单向 DP 的任务。

第四，主问题列系数已经按访问次数接入。`TWETColumn` 保存 job visit count 和普通 arc visit count；LP 覆盖行、动态加列、arc 分支行和 RMIH 覆盖模型都使用访问次数系数。也就是说 time-indexed pseudo-schedule 若重复访问某个 job，该列在 master 中对该 job 的覆盖系数就是重复次数，而不是 0/1 `containsJob`。
