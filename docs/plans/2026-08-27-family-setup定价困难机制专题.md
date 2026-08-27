# Family setup 定价困难机制专题记录

本文集中记录正式 `random/family` 配对实例中，no-cut time-indexed 松弛和 ng-DSSR 在 family setup 下同时恶化的原因。重点不是重复陈述“family 更慢”，而是说明 family 数据到底形成了什么结构、两类算法分别被什么机制击中、为什么 zero setup 不会出现同样现象，以及后续优化应针对哪里。

## 1. 当前最核心的结论

set02 family 的决定性结构不是 setup 均值更大，也不是所有 setup 都更短，而是以下四件事同时成立：

1. 40 个任务被分成 `14/13/13` 三个低成本块，而机器数只有 2；
2. 类内平均 setup 为 `7.71`，类间平均 setup 为 `36.67`，比值为 `4.75`；每个任务 setup 最近的 4 个任务全部属于同族；
3. family assignment 与 due date 独立，每个 family 都含有从 `0` 到 `333--348` 的早、中、晚任务，因而单个 family 在整个时间轴上始终有时间上相对合适的任务；
4. no-cut time-indexed 路径不记录 visited set，master 覆盖系数又使用实际访问次数。重复访问同一任务两次的列可用 `lambda=0.5` 满足一次覆盖，却只消耗 `0.5` 单位机器流。

因此，family time-indexed LP 可以让三组分数机器流分别留在三个 family 内，通过重复访问把每个 family 的全部覆盖行填满，总机器流却恰好只有 2。它完全不用支付真实 elementary 两机器排程必然要支付的跨 family 转移。这不是一般意义上的“重复路线比较便宜”，而是一个由“3 个块、2 台机器、visit-count 覆盖系数”共同形成的分数松弛弱点。它属于当前有意采用的 no-cut relaxation 语义，不是实现正确性 bug。

random 没有稳定低成本块。即使沿用 family 标签做诊断，类内、类间平均 setup 分别为 `27.52/27.49`，每个任务最近 4 个任务中平均只有 `1.20` 个同族。重复某个已访问任务并不能系统性避免一笔高额跨块 setup，因为任何未访问任务都可能有相近转移成本。zero setup 更彻底：转向任意未访问任务与留在所谓 family 内同样没有 setup 代价，重复访问只增加加工时间和时间罚，因而 family relaxation 所利用的“避免跨块转移”收益消失。

## 2. set02 的直接列级证据

2026-08-27 使用当前代码和对应固定 seed，分别对以下两个实例运行 `maxNodes=1` 的 no-cut time-indexed root，并导出根节点全部 restricted columns：

- family：`experiment-suite/formal/instances/data/n040-set02/family/base/zero/m2.dat`
- random：`experiment-suite/formal/instances/data/n040-set02/random/base/zero/m2.dat`
- family 结果：`test-results/bpc/analysis-n040-set02-family-ti-root-20260827/`
- random 结果：`test-results/bpc/analysis-n040-set02-random-ti-root-20260827/`
- 列 dump：`test-results/bpc/root-column-dump-family-analysis/`

两组重新得到与前面诊断一致的根界：

| setup | incumbent | root LB | gap | root pool | 正值列数 |
|---|---:|---:|---:|---:|---:|
| family | 82351 | 78194.245000 | 5.0476% | 31856 | 35 |
| random | 99421 | 98844.391304 | 0.5800% | 41128 | 24 |

列池大小不是根因。random 的根列池更大、root 计算也更慢，但 bound 明显更强。真正的差异在 LP 最终选中了什么列。

| 正值列结构 | family | random |
|---|---:|---:|
| `sum(lambda)` | 2.000 | 2.000 |
| elementary 正值列 | 0/35 | 9/24 |
| 只含一个 family | 35/35 | 0/24 |
| 同时含三个 family | 0/35 | 24/24 |
| lambda 加权平均长度 | 20.000 | 20.000 |
| lambda 加权平均不同任务数 | 13.102 | 19.065 |
| lambda 加权平均额外重复次数 | 6.898 | 0.935 |
| lambda 加权 family 切换次数 | 0 | 13.891 |

family 根 LP 不只是“比较喜欢同族列”，而是完全分解成三个互不跨族的流：

| family | 任务数 | 该 family 列的 `sum(lambda)` | 每个任务最终覆盖 | 加权访问总数 |
|---|---:|---:|---:|---:|
| F0 | 14 | 0.692905 | 恰好 1 | 14 |
| F1 | 13 | 0.594595 | 恰好 1 | 13 |
| F2 | 13 | 0.712500 | 恰好 1 | 13 |
| 合计 | 40 | 2.000000 | 恰好 1 | 40 |

这张表给出了最直接的数学解释。对 elementary 列而言，一个只在 family `f` 内运行的列对每个任务最多贡献 1，因此要覆盖该 family 的全部任务，该 family 的列权重之和至少为 1。三个 family 若完全分开需要至少 3 单位机器流，但这里只有 2 台机器，所以真实解必须至少有一台机器跨 family。当前 relaxed 列允许同一任务出现多次，因而 F0/F1/F2 分别只用 `0.693/0.595/0.713` 单位机器流就把本族任务全部覆盖，三者相加正好为 2，并完全省掉跨族 setup。

代码语义也与这一观察一致。`LP.buildCoverageConstraintsByColumn()` 对内部列使用 `column.getJobVisitCount(job)` 作为覆盖系数，而不是 `containsJob` 的 0/1 系数；覆盖行为 `>=1`。机器数行则对每个 `lambda` 固定使用系数 1。相关实现位于 `src/TWETBPC/LP/LP.java` 的 `982--1021` 行。这种加性系数使 time-indexed pricing 可以继续作为普通最短路处理；若改成“同一路径包含任务即只计一次”，pricing 就必须记忆 visited set，已经不再是当前 no-cut time-indexed 模型。

## 3. “family 横跨全 due 范围”到底是什么意思

set02 的 due date 范围为 `0--348`。三个 family 的 due 分布为：

| family | 数量 | min | max | mean | 标准差 |
|---|---:|---:|---:|---:|---:|
| F0 | 14 | 0 | 348 | 160.57 | 118.88 |
| F1 | 13 | 0 | 347 | 158.46 | 124.33 |
| F2 | 13 | 0 | 333 | 153.92 | 102.73 |

“横跨全范围”不是泛指 family 内 due date 有点分散，而是每个 family 都近似包含了整条时间轴上的一套早、中、晚任务。按 due date 排序的 40 个任务中，family 标签切换 24 次，说明“按 due 顺序加工”和“按 family 连续加工”不是同一种排序结构。

这对 relaxed 与 elementary 路径的影响不同：

1. 对 relaxed 单-family walk，每个 family 从时间轴前端到后端都能找到相对合适的任务，因此可以长期不离开便宜块。处理完本族不同任务后，还可重复早先任务继续取得 visit-count 覆盖系数和任务 dual 收益。
2. 对 elementary 两机器排程，每个任务只能访问一次，三个 family 又不能各占一台机器。排程必须在 due 顺序与减少换族次数之间做真正的离散权衡，至少有一台机器需要跨族。
3. 如果 family 与 due 区间对齐，例如 F0 全是早任务、F1 全是中任务、F2 全是晚任务，那么一台机器按 F0->F1->F2 只需少数自然边界切换；单-family 路径也不能在整个 horizon 内持续找到 due 合适的任务。此时 family 与时间顺序是协同的，而不是冲突的，当前分数弱点会弱得多。

因此，due 横跨全范围有两个同时发生的作用：它让真实 elementary 排程面临频繁“保 due 还是保 family”的冲突，也让 relaxed 单-family 重复 walk 在整个时间轴上始终可行。后者是此前只看 due-order 切换次数时没有说透的关键。

## 4. 为什么 random 不会复现，zero setup 也很快

不能把机制概括成“setup 越短，重复越容易”。zero setup 已经否定了这个说法。真正需要的是低成本块与高成本块边界同时存在。

### 4.1 Random setup

random 与 family 的总体平均 setup 匹配，但 random 的便宜弧分散在全图中，没有共同的块成员关系。对任意当前任务，转向一个 due 合适的未访问任务通常不比回到已访问任务系统性更贵。即使某条重复 walk 暂时有利，也很难构成三组完全分离、可长期继承的便宜子网络。

直接证据是 random 根 LP 的 24 条正值列全部混合三个 family，平均只有 `0.935` 次额外重复；family 则 35 条正值列全部单族，平均额外重复 `6.898` 次。random 的 LP 已经在做与真实两机器排程相近的“跨各种任务混合”，family LP 却在做真实问题不允许的“把三块分数装进两台机器”。

### 4.2 Zero setup

zero setup 下，跨所谓 family 与留在 family 内成本完全相同。重复任务不能避免任何离散边界成本，反而多付一次加工时间并改变后续完成时间。对 pricing dual 而言，重复 `j` 虽可再次取得 `-pi_j`，但转向尚未访问的 `k` 也能取得 `-pi_k`，且不会被一笔稳定的跨族 setup 惩罚。因此重复路线没有 family 数据中的系统优势。

此前 set01 zero-setup 对照也支持这一点：zero setup 的 exact ng-DSSR 为 `16.348s/42 calls/50 rounds`，family 为 `288.116s/10 calls/150 rounds`；zero setup 的 time-indexed 临时列池甚至更大，但没有形成 family 那种单块分数解。由此可知，family 的致难结构不是“弧便宜”，而是“块内便宜、块外昂贵，并且块数量超过机器数”。

## 5. time-indexed 为什么会差成这样

time-indexed 的状态为 `(lastJob,t)`，不保存已访问任务集合。它求解的是允许重复任务的 walk relaxation。对一般 random/zero 数据，单点 due date、加工时间和分散的转移成本通常会让重复 walk 不划算，所以这个 relaxation 虽然理论上允许非基本列，实际正值列接近 elementary，bound 较强。

family 数据恰好系统性构造出了这一 relaxation 最擅长但真实模型不允许的解：

1. 在每个低成本 family 子图内反复行走；
2. 用访问次数放大覆盖系数；
3. 用小于 1 的 `lambda` 覆盖整组任务；
4. 三组 `lambda` 相加仍为 2；
5. 不产生任何跨族 transition。

所以 set02 family 的弱 LB 不是普通 DP 没有继续找到足够多列。它已经正确闭合了一个明显比 elementary master 更松的问题。继续生成同类型 time-indexed 列只会更准确地求出这个弱 relaxation，不会自动恢复 elementarity。

随后产生二次放大：family 的临时 root gap 为 `4156.755`，random 只有 `576.609`；较大 gap 使 reduced-cost arc fixing 阈值更松，family 只推广 43 条 ordinary arcs，平均有效窗口仍为 `3311.6`，有效 horizon 保留到 `4185`。random 推广 1206 条 arcs，平均窗口压到 `798.85`，有效 horizon 压到 `2011`。因此正确因果顺序是：

> family 分数块解使 LB 弱 -> `UB-LB` 大 -> time-arc fixing 和窗口收缩弱 -> 后续状态域更宽 -> 更多重复 walk、列和分支修复。

horizon 差异是弱 bound 的放大结果，不是最初的根因。

## 6. ng-DSSR 为什么同时变慢

ng-DSSR 与 no-cut time-indexed 的慢法不同。当前 `ngDssrReturnRelaxedColumns=false`，非基本负路线不会进入正式 RMP；它们只用于逐轮加强 ng memory。正式 master 因而不会直接继承 time-indexed 的分数块弱点，但 exact pricing 必须把这些更负的非基本路线逐个排除，才能证明 elementary reduced cost 已非负。

set02 的首次 exact 表现为：

| setup | DSSR rounds | exact time | 返回 elementary 负列 |
|---|---:|---:|---:|
| random | 1 | 全树 exact 合计 10.409s | 首次 445 |
| family | 23 | 首次 732.079s | 首次 18 |

family 首次 exact 中，当前 relaxed pricing 的最强负路线大量是单-family 重复 walk。初始 `K=4` 时每个任务记住的 4 个邻居都在本族，但每个 family 还有 8--9 个同族任务未记忆。排除一个重复 pair 后，下一条路线可以换另一个未记忆的同族 pair继续重复。memory 越增越大，label 的 ng-memory 状态越细，不同状态越难互相 dominance；sequence-dependent setup 又使相似前缀在到达时间和 PWLF envelope 上不同，进一步增加保留 labels。set02 family 首次 exact 因而执行约 1586 万次 forward extension、保留约 54.7 万 forward labels，最终只返回 18 条可进入 RMP 的 elementary 列。

random 中没有可持续替换的便宜 family 子图。最负路线从第一轮开始就大多是 elementary，因而一次 relaxed labeling 就能直接返回大量有效列，不需要先用多轮 memory 把成批重复路线压下去。

## 7. witness 是什么

这里的 witness 指：在当前 ng-memory 下可行、reduced cost 为负，但包含重复任务的路线。它“见证”当前 ng relaxation 仍然过松。

例如 relaxed route 含有 `a ... b ... a`。第二次访问 `a` 说明当前 memory 没有把第一次访问 `a` 沿中间段持续保留下来。DSSR 从该重复段中找出缺失的 `(center,member)` memory pair，加入 ng-set 后重新 labeling，使这条路线或同类路线在下一轮被禁止。

witness 有三个边界：

1. 它不是合法 elementary master column；当前配置不会把它加入 RMP。
2. 它不是 lower-bound certificate；只有最终完整轮找不到任何负 relaxed route，或者已正确取得 elementary 闭合条件时，才能结束 exact pricing。
3. 它是反例和更新信号。选择哪些 witness 决定每轮能用多少新增 memory 排除多少重复路线，因此直接影响 DSSR 轮数和后续 dominance。

当前候选池主要按 reduced cost 保留。family set01 的 137689 条被考虑候选中，136720 条在轮到处理时已经被同批前序更新阻断，只新增 985 个 pair；set02 首次困难调用也出现 22000 条 considered、21891 条 blocked、只有 109 条实际产生更新。说明当前不缺“更负的 witness”，缺的是重复结构不同、能覆盖新 pair 的 witness。

## 8. warm start 是什么，当前为何没有打开

每次独立 exact pricing 调用都会重新从基础 ng-set 开始；当前正式 `K=floor(n/10)=4`。同一次调用内部，DSSR 轮次会不断增加 memory；调用结束后，动态增加部分默认不自动成为下一次调用的完整初始 memory。

当前代码已有两种默认关闭的 warm start：

1. `same-node warm start`：保存同一 BPC node、同一 active-cut 集合最近几次困难 exact 的 final ng-set，在下一次调用的基础 K 上有界追加高频成员。当前默认窗口为 3，单 job 最多追加 3 个，全局最多 25 个 pair，且上一调用至少执行 3 轮才触发。追加成员仍会经过当前 repeatability filter。
2. `history warm start`：跨调用/节点按出现频率学习一套初始 memory。它可能直接替换基础 seed；当前 repeatability filter 生效时会保守跳过，且 root 默认不使用。它的状态漂移和 dominance 代价更难控制。

warm start 的动机是避免同一 node 的多次 exact 反复发现相同 family pair。family K=8 对照显示，跨 15 次 exact 只有 83 个 unique 动态 pair，最频繁的 10 个每次都会重新加入，说明存在复用信号。但 K=8 虽把 DSSR 轮数从 150 降到 91，exact 时间却从 288.116s 增至 354.134s，因为更大的初始 memory显著削弱 dominance。由此，warm start 不能理解成“把上次 final ng-set 全带过来”；合理试验只能是同 node、频率高、数量很小的 pair 复用，并以总 labels 和总时间验收，不能只看 rounds。

## 9. 针对当前 family 结构的 ng-DSSR 优化顺序

以下方向都保持现有“relaxed witness -> 增加 memory -> 最终 exact certificate”的流程，不改变 master 只接收 elementary 列的原则。

### 9.1 第一优先级：witness novelty/diversity

保留现有 reduced-cost 主候选池，同时为每条非基本路线计算 minimum-segment 实际会新增的 pair 集合。更新时不再只从最负到较不负依次扫描，而是在候选池内贪婪选择“相对本轮已选择 witness 能覆盖最多新 pair/新重复段”的路线，reduced cost 作为次级排序。也可以并行保留一个很小的 novelty 池，再与主池合并。

目标不是每轮增加更多 pair，而是让同样的 `effectiveLimit` 覆盖更多不同重复结构，降低 `blocked/considered >99%` 的浪费。它不改变最终 certificate，只改变 DSSR 收紧顺序，是当前证据最直接支持的优化。

### 9.2 第二优先级：有界 same-node warm start

只复用同 node 最近困难 exact 中反复出现的 pair，先使用现有 `window=3/perJob=3/global=25/triggerRounds=3` 做 A/B。必须同时记录初始增加 pair 数、每轮 labels、DSSR rounds、exact calls 和总 exact time。若 rounds 降但 labels 上升更多，应判定失败。

### 9.3 第三优先级：利用 time-indexed 正值非基本列提供小型初始 witness

当前根列 dump 已直接识别出导致弱 LB 的 35 条单-family 重复列。可以只从这些列提取高频重复 pair，经过 repeatability filter 后给每个 job 最多增加 1 个、全局不超过一个很小上限。它们只能作为初始 witness 来源，不能进入 elementary master。该方案比按静态 family 把整族全部放入 memory 更贴近实际负路线。

### 9.4 不建议：全局把 K 翻倍或把整个 family 放入 memory

K=8 已经证明“轮数少”不等于“总时间少”。完整 family memory 会把每个 job 的 12--13 个同族成员都变成状态，label dominance 可能进一步恶化。若尝试 family-aware 初始化，也只能选择少数能打断不同低成本重复环的 pair，而不是把 family 标签直接等同于 ng-set。

### 9.5 time-indexed 侧的边界

将覆盖系数从 visit count 改成 contains-job 0/1 能直接堵住当前分数弱点，但定价成本不再沿弧可加，必须记录已访问集合，实质上退回 elementary/ng pricing。普通 time-indexed DP 的常数优化不能修复这一 bound 问题。可研究的仍是 rank-1/SRI 类 distinct-visit 强化，或根据“正值列是否按块分解、重复率、root gap”判断何时不应过度依赖 no-cut preprocessing；这些属于 time-indexed relaxation 强化，不是 ng-DSSR 内部优化。

## 10. 文献中的类似现象及其边界

### 10.1 调度文献

[Schutten et al. (1996, *Management Science*)](https://pubsonline.informs.org/doi/10.1287/mnsc.42.8.1165)、[Schaller and Gupta (2008, *EJOR*)](https://www.sciencedirect.com/science/article/abs/pii/S0377221706008204) 以及 [Schaller and Valente (2013, *JORS*)](https://doi.org/10.1057/jors.2012.94) 都讨论了 family batching 与任务级 due-date 目标的冲突：连续加工同族任务减少 setup，但可能使部分任务过早或过晚；拆分 family batch 能改善 earliness/tardiness，却增加 setup。它们支持“family 顺序与 due 顺序错位会增加组合权衡”，但没有分析当前的 time-indexed visit-count 松弛或 ng-DSSR witness，因此不能替代本项目的列级证据。

本轮没有检索到一篇对“同一任务集、相同 setup 总均值、random pairwise 与强 family block”做与本文完全相同配对设计，并进一步分析 BPC relaxation 的论文。现有调度研究通常比较 family batching策略、setup 幅度或不同算法，而不是把 setup 的块结构作为唯一变量。因此，不能把本项目的 `5.048%` 对 `0.580%` root gap 或 `23` 对 `1` DSSR rounds 写成已有文献的已知普遍结论。

### 10.2 Soft-clustered VRP：最接近的 labeling 类比

[Hintsch and Irnich (2020, *EJOR*)](https://www.sciencedirect.com/science/article/pii/S0377221719305831) 的 SoftCluVRP 要求一个 cluster 的全部客户由同一车辆服务。作者报告，即使使用 partial pricing、bidirectional labeling 和 DSSR，标准 SPPRC labeling 仍极其困难；改用 IP-based pricing 后快一个数量级，并求解到 400 多客户或 50 多 cluster。其根因是 cluster membership 是跨整条路线的全局耦合状态，局部 label 约束很松，难以有效压制组合爆炸。

它与 TWET 不完全相同：SoftCluVRP 的“同 cluster 同车辆”是显式硬约束，当前 family 只是成本结构。但两者共同说明，低成本/同组结构一旦与车辆或机器分配发生全局耦合，普通局部 labeling 状态可能无法快速区分大量表面相似的路径。

### 10.3 普通 CVRP 的反例：cluster 本身并不必然更难

[Uchoa et al. (2017, *EJOR*)](https://doi.org/10.1016/j.ejor.2016.08.012) 对扩展 CVRP benchmark 的统计发现，客户空间位置对其 BCP 性能影响不大，clustered 实例甚至略容易；cluster 会减少有吸引力路线的重叠。[Silva et al. (2025, *INFORMS Journal on Computing*) 的 Cluster Branching](https://pubsonline.informs.org/doi/abs/10.1287/ijoc.2024.1036) 又说明，某些高度 clustered 实例会使 edge/cutset branching 很弱，需要对 cluster aggregate flow 分支。两者不矛盾：普通聚类若与车辆路线天然对齐，会减少候选；只有聚类与机器数、容量、时间或分支结构错位时，才会制造弱 bound 或大量可替代决策。

这正是当前 family/random 对照应保留的限定：困难不是“有 family”，而是 `3 families > 2 machines`、due 全范围交错、类间 setup 高，并且 relaxation 允许分数重复覆盖。

### 10.4 固定费用网络设计：分数规避离散边界成本的模型类比

[固定费用多商品网络设计的聚合 LP](https://arxiv.org/abs/2101.03707) 中，整数解必须先支付一笔 arc 启用固定费才能送流；聚合 LP relaxation 可用分数启用变量承载分数流，因而形成较弱 bound，并需要更强 linking inequalities、disaggregation 或 cuts。当前 family time-indexed 根解的机制与此相似：真实两机器解必须“启用”跨 family 转移，relaxed master 却用小于 1 的重复列权重把三个块分数装入两单位机器流，从而规避这笔离散边界成本。

这只是模型机制类比，不表示当前问题可直接套用固定费用网络设计 cuts。当前最有针对性的证据仍是第 2 节的根正值列分解。

## 11. 当前结论的证据等级

已经确认：family 与 random 使用相同任务、机器数、窗口和总体平均 setup；set02 根界可重复；family 正值列全部单族且可精确分解为三组总计 2 的机器流；random 正值列全部跨三族且接近 elementary；zero setup 不出现相同慢速；当前 coverage 与 machine row 的代码系数确实允许该分数结构。

高度可信的机制推断：due 全范围交错让单-family relaxed walk 在整个 horizon 内保持时间可行，同时迫使 elementary 排程在 due 与 family batching 间权衡；同族可替代重复 pair 导致 DSSR 多轮，memory 增长和 sequence-dependent PWLF 又放大单轮 labels。

仍需 A/B 的优化判断：novelty witness 池能减少多少 rounds 与 total labels；有界 same-node warm start 是否净加速；从 time-indexed 正值列提取少量 pair 是否优于纯 K=4。现有证据只支持按此顺序试验，不支持直接打开完整 history warm start、扩大 K 或把整个 family 写入 ng memory。
