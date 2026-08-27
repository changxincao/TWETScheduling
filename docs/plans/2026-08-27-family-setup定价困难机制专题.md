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

## 12. 进一步澄清：配对标签、加权统计和分数机器流

random 实例本身没有 family。第 2 节对 random 正值列使用的 F0/F1/F2，是把同一批 40 个任务按配对 family 实例的分组标签重新着色，仅用于检查 random 列是否也会沿这个人为分区形成块。准确表述应是“在 family 配对标签下，random 的 24 条正值列全部同时访问三个标签组”，而不是“random 同时访问三个 family”。该结果恰好说明 random setup 与这组标签无关，没有形成 family 的三个低成本子图。

加权统计均以根 LP 的列变量值 `lambda_r` 为权重，并除以总机器流 `sum_r lambda_r=2`。例如，加权不同任务数为

\[
\frac{\sum_r \lambda_r\,|\operatorname{distinct}(r)|}{2},
\]

加权重复次数为

\[
\frac{\sum_r \lambda_r\,(|r|-|\operatorname{distinct}(r)|)}{2}.
\]

因此 family 的 `13.102+6.898=20` 表示随机抽取一单位 LP 机器流时，平均列长为 20，但只含 13.102 个不同任务，另外 6.898 个位置是重复访问；random 的 `19.065+0.935=20` 则几乎是 elementary 列。

分数机器流可用一个最小例子说明。设列 `r=(1,2,1,2)`，并令 `lambda_r=0.5`。机器数行只收到 `0.5`，但任务 1 和 2 都被访问两次，所以两个覆盖行都收到 `2*0.5=1`。若列是 elementary 的 `(1,2)`，同样的 `lambda=0.5` 只能给两个任务各 `0.5` 覆盖，必须把总列权重提高到至少 1。对真实 set02 family 根解而言，并不是一条列以 `0.6--0.7` 覆盖全族，而是每个 family 的多条单族重复列混合后，总权重分别只有 `0.693/0.595/0.713`，却借助访问次数把本族每个任务的覆盖都填到 1。

“三个 family 分开至少需要 3 台机器”也应理解为 LP 结构陈述：如果所有 elementary 列都只在一个标签组内运行，那么任取该组一个任务，其覆盖最多等于该组全部列权重，因此每组列权重至少为 1，三组合计至少为 3。真实问题只有 2 台机器，所以可行整数解必须让至少一条机器路线跨组；family relaxed 根解却把三个单族流压成总权重 2，从而避开了这个跨组决策。

## 13. setup-cost 系数 20 的量级

正式数据使用 `kappa_ij=20*s_ij`。对 set02，family 的类内/类间平均 setup time 为 `7.71/36.67`，对应平均直接成本约 `154/733`；跨族相对类内的一次平均直接溢价约为

\[
(36.67-7.71)\times20=579.2.
\]

这说明系数 20 会明显强化“少跨一次 family 边界”的局部动机，但不能据此说 setup cost 已主导总目标。由当前输出的 incumbent schedule 逐任务求和，family 的 TWET penalty/setup cost 为 `75851/6500`，setup 占总目标 `82351` 的 `7.89%`；random 为 `88715/10560`，占总目标 `99275` 的 `10.64%`。直接 setup cost 是一阶但次要的目标组成，没有淹没 TWET。系数 20 的正式设计动机也是让本来较小的 setup time 产生可见直接成本，同时要求用最终解的成本占比确认其既不消失也不主导；set02 的这两个比例满足该设计目标。

当前证据仍不能把 family 困难全部归因于系数 20，因为现有 zero-setup A/B 同时清除了 setup time 和 setup cost，没有单独保持 family setup time、只把 cost coefficient 从 20 改为 0/8。严格分离两者需要这组 cost-only A/B。但不论系数取 8 还是 20，只要类内/类间 setup time 本身仍为强块结构，它通过完成时间递推也会保留一部分块效应；系数 20 主要是进一步放大，而不是创造三个低成本子图。

## 14. 弱 LB 的最深层原因

“family 分数块解导致 LB 弱”仍然只是对最优 LP 解的描述。更深层原因是四个数学语义发生了错配：真实列要求每个任务至多一次；机器是不可分的整条排程；三个低成本子图超过两台机器，真实解必须支付至少一次跨块和 due-order 折中；no-cut time-indexed 列却允许重复访问，master 又允许分数列并按访问次数计覆盖。于是原本不可分的“哪台机器跨块”决策，被 relaxation 拆成三个可分的单块流，再用重复次数补足覆盖。

random 和 zero 不会稳定利用这一错配，关键不是单条弧均值，而是低成本弧的相关拓扑。family 中，一个任务的许多同族出入弧同时便宜，组成可长期循环的大子图；把重复访问替换成族外未访问任务，通常要穿过高成本边界。random 中便宜弧只是分散的局部偶然值，不存在一个对所有成员都持续便宜的封闭子图；从当前任务转向尚未访问任务时，通常仍能找到普通成本的出口，重复不能系统性规避一笔固定式边界费。zero 中所有替换出口都同样便宜，重复更没有规避边界的收益。真正触发弱界的是“便宜弧在同一组内高度相关、组间普遍昂贵、组数又超过机器数”，而不是“setup 小”或“存在若干便宜弧”。

due 横跨全范围进一步使每个便宜子图从 horizon 前端到后端都有早、中、晚任务，单块 walk 可以长期留在块内而不立即遭遇极端时间罚；random 没有与某个低成本子图一致的成员集合，因而无法同时获得这种时间覆盖和边界规避。

## 15. witness 阻断、候选 1000 和后续保留方向

random 的 witness 也有高度重叠，但绝对工作量和所处阶段完全不同。set02 random 全树 124 次 exact 记录合计 `considered=55707, blocked=54721, updatedRoutes=986`，阻断率为 `98.23%`。其首次 exact 一轮就返回 445 条 elementary 列，因此虽然看到 2469 条 non-elementary route、保留 1000 条，控制流直接返回 elementary 列，`considered/blocked/updated` 均为 0。后续真正需要 DSSR 更新的调用通常只有 2--4 轮。family 首次困难调用则在 23 轮中扫描 22000 条保留候选，21891 条已被同轮前序更新阻断，仅 109 条路线实际更新，同时每轮 labeling 已经膨胀到几十万 labels。由此，阻断比例本身不是 family 特有现象；family 真正的问题是高阻断发生在一个没有大量 elementary 负列可直接返回、必须连续重解 23 轮的巨大 labeling 中。

`ngDssrNonElementaryRouteCandidateLimit=1000` 不是“最后保留 1000 条 master 列”，而是每个 relaxed DSSR round 在线保留 reduced cost 最低、sequence signature 不同的最多 1000 条负非基本 witness。它有三层作用：限制 witness 内存；在前序候选被同轮更新连带阻断后，仍允许继续向后扫描，尽量凑到最多 20 条真正有效的 route update；池满后以当前第 1000 名 reduced cost 作为 non-elementary witness 阈值，在已知拼接一定非基本时剪掉不可能进入 top-C 的 join，并在函数值算出后避免恢复无望 sequence。它不剪 elementary join，也不提供最终 certificate。历史 C1000/C3000/C10000 对照显示，更大池没有增加有效更新，主要只会放松末端 witness 阈值，因此当前 1000 是有界深度与候选后备之间的折中。

本轮只记录、暂不实施以下三个方向。第一，保留 reduced-cost 主池，同时在更新顺序中优先选择新增 missing pair 或重复段与已选路线重叠更少的 witness，以降低候选同质化。第二，只做有界 same-node warm start，复用同一节点近几次困难 exact 中少量高频 pair，不继承完整 final memory。第三，从 time-indexed 根 LP 的正值非基本列中提取少量高频重复 pair，经过 repeatability filter 后作为 ng-DSSR 初始提示，但绝不把这些 relaxed 列放入 elementary master。三者都必须以 total labels 和 exact wall time 验收，不能只看 DSSR rounds。

## 16. SoftCluVRP 的准确类比与限制

Hintsch and Irnich 的 SoftCluVRP 不是“成本上偏好同组”，而是硬性规定：一条路线一旦访问 cluster `h` 的任一客户，就必须由同一车辆把该 cluster 的全部客户访问完，但这些客户不要求连续。label 因而必须同时记录每个 cluster 尚未访问的客户数和逐客户访问状态。部分路径刚进入一个 cluster 时并不立即不可行，它可以先去别的 cluster，再回来补完；只有回到 depot 时尚有 cluster 未完成才被判不可行。这就是论文所谓约束很 loose：大量后来无法高效完成的部分路径在早期都不能被剪掉，dominance 还必须比较不同的未完成 cluster/客户集合，双向拼接也要匹配两侧剩余状态。DSSR 只放松并逐步恢复 elementarity，不能消掉“已选择 cluster 必须完整服务”的全局完成状态。

论文的 IP pricing 直接对单条负 reduced-cost 路线建立 edge/cluster 选择模型：二元 routing 变量决定走哪些边，cluster 变量决定选择哪些整组客户，coupling 和 capacity 约束表达整组选择，连通性/subtour 约束由 CPLEX lazy/user cuts 分离。它能直接对“选不选一个 cluster”进行整数分支，并用连通 cuts 一次排掉整类不连通解，不必枚举所有开放 cluster 状态的局部 labels。作者将该子问题解释为 cluster-profit 的 profitable/prize-collecting TSP；这类结构本来就更适合 branch-and-cut。完整 B&P 对照中，labeling 只解出 34/158 个 root LP、证明 23 个实例，branch-and-cut pricing 解出 150/158 个 root LP、证明 142 个实例；32 个筛选实例中，primal heuristic 加 branch-and-cut 的平均/几何平均 root-LP 时间为 `2.6/0.9s`，相对默认 labeling 的加速比超过 `340/66`。

对本项目的可借鉴结论有限。相似点是 family 结构让大量局部便宜路径在全局机器分配或最终 elementarity 约束下才暴露问题，局部 labeling 状态难以及早区分。不同点是 TWET family 没有“整族同机”的硬约束，exact pricing 还含 sequence-dependent setup、完成时间和 PWLF 成本；若改成 IP pricing，需要为单条排程建立 arc/order、completion time 和分段线性惩罚模型，并在每次 CG 调用求 MIP，不能由 SoftCluVRP 的结果直接推出会更快。它目前更适合作为“对困难 root exact 做一次 IP-pricing 交叉诊断”的研究线索，而不是替换主线 labeling。

普通 CVRP 的 cluster branching 解决的是 root LP 闭合之后的分支树弱，而当前 family40 的主要时间已经消耗在 root exact pricing 内部，甚至没有进入分支。因而 Cluster Branching 或 cutset branching 对当前底层瓶颈没有直接作用；必须先改善 witness 收紧或换一种 exact-pricing 表达，分支策略才有施展空间。

## 17. setup cost、setup time 与 family 数量的受控拆分

为避免继续把显式 setup cost、setup time 资源和 family/机器数量混在一起，本轮固定 `n040-set02` 的 processing、due 和权重，做了三组单因素诊断。所有根 LP 都使用 no-cut time-indexed profile、`maxNodes=1` 和各实例专用初始解；自定义 family 数实验使用同一个 random 基础有向度量、相同总平均 setup `27.5` 和平衡分组，只改变 family 数。它们是机制诊断，不替代正式 benchmark。

第一组只缩放 `SETUP_COST=kappa*s_ij` 中的 `kappa`，setup time 矩阵保持不变：

| setup 类型 | `kappa` | incumbent | 根 LB | gap | 加权不同任务数 | 加权重复次数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| family | 20 | 82351 | 78194.245 | 5.048% | 13.102 | 6.898 |
| family | 8 | 78451 | 75006.600 | 4.391% | 13.310 | 6.690 |
| family | 0 | 75851 | 72793.969 | 4.030% | 13.490 | 6.510 |
| random | 20 | 99421 | 98844.391 | 0.580% | 19.065 | 0.935 |
| random | 8 | 92893 | 92414.583 | 0.515% | 19.200 | 0.800 |
| random | 0 | 88541 | 87892.338 | 0.733% | 19.190 | 0.810 |

因此系数 20 确实放大跨族代价，但不是困难的创造者。即使 `kappa=0`，family setup time 仍进入完成时间递推和 TWET penalty，正值列仍全部非基本，重复次数只从 `6.898` 降到 `6.510`。真正把 setup time 和 setup cost 都清零后，专用 seed 的结果变为：根 gap `0.179%`，10 条正值列全部 elementary，加权不同任务数/重复次数为 `20/0`；ng-DSSR 根节点 exact pricing 总计仅 `1.626s`。这组结果严格区分了“取消显式 setup 费用”和“没有 setup 时间结构”。

第二组比较 family 数 `F` 与机器数 `m=2`。使用专用 seed 后结果为：

| `F` | family 大小 | 根 gap | 加权重复次数 | 解释 |
| ---: | --- | ---: | ---: | --- |
| 1 | 40 | 0.580% | 0.935 | 等价于无 family 分隔的 paired random 基准 |
| 2 | 20/20 | 0.450% | 3.430 | 每个块可由一台机器承担，重复存在但没有明显逃避跨块合并 |
| 3 | 14/13/13 | 7.816% | 7.250 | 块数超过机器数，分数单块流开始系统性替代跨块机器 |
| 4 | 10/10/10/10 | 7.910% | 9.830 | 更多单块分数流依靠更高重复覆盖填满任务行 |
| 5 | 8/8/8/8/8 | 8.219% | 10.870 | 平均每单位机器流只有约 9.13 个不同任务 |

第三组使用正式 3-family 数据直接比较 `m=2/3`。`m=2` 时 gap/repeat 为 `5.048%/6.898`；`m=3` 时降至约 `0.675%/0.976`。random `m=3` 的根 LP 直接为整数，重复为 0。由此可以确认：低 setup 稠密块负责制造可交换的重复 walk；`F>m` 则把这些 walk 转化为显著的 time-indexed 弱界。二者不是同一个条件。

ng-DSSR 还受另一个维度控制。自定义 `F=2,m=2` 的 time-indexed gap 只有 `0.45%`，但 ng-DSSR 根 exact 仍耗 `91.333s`，首个 pricing 调用需要 16 轮 DSSR；正式 `F=3,m=3` 的 exact 总计只有 `5.858s`。自定义 `F=5,m=2` 在 600 秒内只完成 3 次 exact、第四次到时限，exact 合计 `599.841s`，最后一次保留/占优 labels 为 `344305/2078171`。因此弱 root gap 会恶化预处理和 dual，但不是 ng-DSSR 慢的必要条件；大块内的可交换循环、时间窗口宽度和 memory 增长后的 dominance 破坏可以单独使 pricing 变慢。

## 18. 加权不同任务数和重复次数到底表示什么

设根 LP 正值列为 `r`，列变量为 `lambda_r`，总机器流为 `M=sum_r lambda_r`。一般定义应写成

\[
D=\frac{\sum_r\lambda_r|\operatorname{distinct}(r)|}{M},\qquad
R=\frac{\sum_r\lambda_r(|r|-|\operatorname{distinct}(r)|)}{M}.
\]

以前公式中的分母 2 只是因为该实例 `M=2`，不是定义的一部分。把一单位 LP 机器流看成按 `lambda_r/M` 抽取一条列，`D` 是该列平均包含多少个不同任务，`R` 是平均有多少个序列位置在重复已经出现的任务。恒有 `D+R=L`，其中 `L=sum lambda_r|r|/M` 是加权平均列长。

例如三条正值列的权重为 `0.7/0.6/0.7`，长度都为 20，不同任务数为 `14/13/13`。总机器流为 2，则 `D=(0.7*14+0.6*13+0.7*13)/2=13.35`，`R=20-13.35=6.65`。它不是说某台真实机器加工了 13.35 个任务，也不是把所有列的任务集合先求并集；它描述的是 LP 每消耗一单位机器流，实际接触了多少不同覆盖行，又用多少重复访问把覆盖系数放大。

这两个指标的用途正是拆开一个原本没有诊断价值的事实：在 40-job、2-machine 根解中，覆盖行通常紧约束，所以平均列长几乎被固定在 20。只看长度会误以为 family 与 random 相同；拆开后 family 是 `13.102+6.898`，random 是 `19.065+0.935`。对应的覆盖放大倍数 `L/D` 分别约为 `1.527` 和 `1.049`。前者说明同样一单位机器流通过重复访问产生了约 52.7% 的额外 visit-count 覆盖能力，后者接近真实 elementary 机器流。

## 19. 最核心的结构机制

当前 time-indexed 路径状态不保存完整已访问集合，而 master 覆盖行使用 `getJobVisitCount(job)`，机器数行对整列固定计 1。pricing reduced cost 又按 sequence 中的每次出现扣除一次 job dual。于是重复访问不仅是允许存在的冗余动作，还会再次获得任务 dual、再次贡献覆盖系数。

family setup 把任务图变成多个大的低 setup 稠密子图。关键不是“每个点找最近邻”，而是相关性：从块内任一任务出发，都有很多仍在块内的低 setup 入口和出口，因而能构成大量长度不同、重复 pair 不同但 reduced cost 相近的闭合 walk。random 的低 setup 弧是分散的偶然值，通常不能连续组成一个稳定封闭子图；zero setup 中所有未访问任务与重复任务同样容易到达，重复任务没有系统性优势。真实 zero-setup 对照中正值列全部 elementary，直接验证了这一点。

当 `F>m` 时，真实 elementary 解至少有一台机器跨 family。relaxed master 却可给每个 family 若干总权重小于 1 的单-family 列，再靠列内重复把每个任务覆盖补到 1；这等价于把不可分的“哪台机器跨块”决策拆成多个分数单块流。due center 与 family 独立分配，每个 family 都含早、中、晚任务，使单块 walk 从 horizon 前端到后端始终能找到时间上尚可的扩展，因而不会很快被 TWET penalty 排除。`F=m` 时每块本来就可由一单位机器流承担，重复覆盖不再能逃掉一项必付的跨块机器决策，所以根 gap 可以很小；但块内循环仍会让 exact pricing 本身变慢，这就是 `F=2,m=2` 仍需 91 秒 exact 的原因。

ng-DSSR 中，同族大小 13--20 而初始 `K=4` 只记住少数近邻。一个重复 pair 被加入 memory 后，还有大量同族未记忆任务可替换成下一条低 reduced-cost cycle。随后 memory 状态越来越细，PWLF 到达时间/envelope 又使相似前缀难以互相 dominance，因此出现两个乘法因素：需要多轮排掉可替换循环；后期每轮 labels 也更贵。novelty witness 只能减少前者，不能挽回当前一轮已经完成的 labeling 成本；`F=5` 仅 8--9 轮仍到 600 秒时限就是直接证据。

## 20. 为什么有限车辆数 CVRP 通常没有同样严重

车辆数上限本身并不能防止这个问题。标准 route-based CVRP 同样有每条路线机器/车辆系数 1 和分数 `lambda_r`；真正差异在列和资源语义。

第一，exact CVRP master 的合法列是 elementary route，客户 incidence 为 0/1。即使三个地理 cluster 超过两辆车，分数 route LP 仍可能有普通 set-partitioning gap，但不能用同一客户访问两次配合 `lambda=0.5` 产生完整覆盖。若某个 VRP 实现把 non-elementary route 以 visit-count 系数直接放进 master，它也会产生同类弱界；文献采用 ng/q-route relaxation时本来就明确接受更弱的 LP bound，DSSR/elementary pricing正是用于恢复正确的 elementary certificate。

第二，CVRP 的容量是单调硬资源，重复访问通常再次消耗需求或至少占用路线长度，硬 time window/route duration 也会迅速截断循环。当前 TWET 只有软 due penalty，重复任务只增加 processing/setup 时间，并不会立即不可行；horizon 较宽时仍可保留很长的重复 walk。

第三，度量旅行成本允许删除一个重复访问并 shortcut，通常不增加物理路线成本；未访问客户仍必须由某条 elementary route 服务。TWET 的 sequence-dependent completion cost 与 soft earliness/tardiness使“删除一个位置”会整体移动后缀完成时间，不能只靠三角不等式推出列一定更差。更重要的是，当前 relaxed reduced cost 对重复出现再次扣 job dual，这使物理冗余动作在定价空间中具有直接收益。

因此普通限车 CVRP 与当前问题相似的是“cluster 数可能超过车辆数”；不同的是它通常没有“重复一次客户就额外获得一次覆盖和 dual”的杠杆。当前 family 困难不是车辆数约束独有，而是 `F>m`、soft-time sequencing、非基本 time-indexed 路径和 visit-count master 系数四者叠加。

## 21. top-1000 witness 阈值的准确剪枝流程

每轮 labeling 在线维护最多 1000 条 sequence signature 不同的负非基本路线，按 reduced cost 从小到大排序。同一 signature 只保留更负者；池未满时直接加入，池满后只有比当前第 1000 名更负的路线才能替换它。设当前第 1000 名为 `-100`：

1. 在构造 join PWLF 前，如果 forward/backward 的访问 bit mask 已证明拼接一定非基本，则先计算乐观下界 `forward.minRC+backward.minRC+fixedJoinRC`。若该下界为 `-80`，真实 join reduced cost 只可能更高，不可能击败 `-100`，可直接剪掉。
2. 若乐观下界为 `-120`，暂时不能剪，继续做时间域相交和 PWLF 最小和。若精确 join 值最终为 `-90`，在恢复完整 sequence 前再按同一第 1000 名阈值剪一次；若为 `-130`，才恢复 sequence、去重并尝试入池。
3. 该阈值只用于已知非基本 witness。elementary join 不受它限制，target trace、返回 relaxed columns 或诊断模式也会关闭此剪枝，所以它不会参与最终无负 elementary 列的 certificate。

## 22. 基于机制的后续方向

当前不应通过把 `kappa=20` 改小来治疗算法；`kappa=0` 仍保留约 4% family gap，说明这只改变模型权重而不能消除结构瓶颈。更针对 time-indexed 弱界的候选是 family-touch cover cut：对每个结构组 `G` 加入 `sum_r 1{r touches G} lambda_r>=1`。它对真实 elementary master 是冗余有效不等式，却能阻止总权重小于 1 的单-family 重复列仅靠 visit count 填满该组。其 pricing 系数是“是否首次进入该 family”，family 数只有 3--6 时可用小 bit mask；需要先做默认关闭的 root A/B，验证 LB、fixing、总时间和正确性，尚未实施。

ng-DSSR 需要分两层处理。novelty/diversity witness 和从 time-indexed 正值重复列提取少量 pair，针对的是同质 witness 导致的多轮更新；低 setup component-aware 的小型 cycle-hitting 初始 memory 也只能小规模测试，不能把完整 family 放进 ng-set。对于 `F=5` 这种单轮 labeling 已经上百秒的情况，还必须同时改善窗口/完成界或研究困难 root 的 IP-pricing 交叉诊断。后续实验必须分别报告 DSSR rounds、每轮 labels、final memory、exact time 和 root LB，不能再用一个总时间把两类机制混在一起。

## 23. ng-DSSR 与 time-indexed 的完整求解交叉对比

为判断 `F=2,m=2` 的慢 exact pricing 是否意味着 ng-DSSR 整体不如 time-indexed，本轮对 `F=2,m=2` 自定义受控实例和正式 `F=3,m=3` 实例分别使用当前正式 profile、相同实例专用 seed、`600s` 时限和完整树求解。结果如下：

| 实例 | 方法 | 总时间 | 节点数 | 根 LB / incumbent | 主要耗时 |
| --- | --- | ---: | ---: | --- | --- |
| `F=2,m=2` | time-indexed | `36.626s` | 9 | `78157 / 78510` | master LP `28.301s`，pricing `4.484s` |
| `F=2,m=2` | ng-DSSR | `84.341s` | 1 | 根节点闭合到 `78510` | exact pricing `71.982s`，master LP `7.353s` |
| `F=3,m=3` | time-indexed | `41.351s` | 28 | `46503.179 / 46819` | master LP `32.585s`，pricing `2.617s` |
| `F=3,m=3` | ng-DSSR | `9.797s` | 1 | 根节点闭合到 `46819` | exact pricing `5.135s`，master LP `1.565s` |

因此两种方法没有固定优劣。`F=2,m=2` 上 time-indexed 比 ng-DSSR 快约 `2.30` 倍；`F=3,m=3` 上 ng-DSSR 反而比 time-indexed 快约 `4.22` 倍。真正的比较是：证明 elementary root certificate 的成本，和保留 non-elementary relaxation 后通过 RMP 与分支树补掉 gap 的成本，哪一个更低。

两个实例的 time-indexed 根绝对 gap 很接近，分别为 `353` 和 `315.821`，但 ng-DSSR exact 成本相差约 14 倍，所以 root gap 不能解释该差异。`F=2` 每个低 setup 块有 20 个任务，当前按 `0.08n` 初始化的 nearest memory 每个任务只约覆盖 3 个邻居，尚有约 16 个同族便宜替代项；`F=3` 的块只有 13--14 个任务，未记忆替代项约 9--10 个。前者能构成更多 reduced cost 接近、重复 pair 不同的可交换环。其预处理正值 relaxed 列平均重复次数为 `3.430`，而 `F=3,m=3` 仅为 `0.976`，说明这一差异已经在 exact pricing 前的 relaxed 解中出现。

时间状态进一步放大了该差异。`F=2,m=2` 的 pricing horizon 为 `1646`、平均 window 长度 `576.150`；`F=3,m=3` 分别为 `1158` 和 `395.925`，前者约大 `42%/45%`。更多同族替代环乘以更长的可达时间域，既增加前几轮待排除的 non-elementary witness，也使 memory 增大后相似 PWLF labels 更难 dominance。完整 exact 统计中，`F=2,m=2` 的 7 次调用累计 `107` 轮 DSSR、观察到 `811311` 条 non-elementary 路线，最大 forward/backward kept labels 为 `88069/11823`；`F=3,m=3` 只有 `44` 轮、`158196` 条和 `5588/3471`。这才是 `71.982s` 对 `5.135s` 的直接来源。

time-indexed 不保存 visited-memory，也不支付排除这些重复环的 certificate 成本。`F=2,m=2` 虽有较多重复 relaxed 列，但它们造成的最终 gap 只有 `0.45%`，9 个节点即可补掉；其 time-indexed pricing 共生成约 6.24 万列却只耗 `4.123s`，时间主要花在 RMP。此时接受一个很小的松弛 gap 再分支，比在根节点证明所有负 non-elementary walk 都不能产生 elementary 负列更便宜。`F=3,m=3` 则相反：elementary certificate 很便宜，而 time-indexed 仍需 28 个节点和 `32.585s` 的 master LP，ng-DSSR 在根节点闭合更有利。

当前结论不是切换默认算法，而是识别出一个可观测的交叉条件：当 relaxed gap 已很小，但正值 relaxed 列重复高、低 setup 块远大于初始 ng memory、首个 exact 调用的 DSSR 轮数和 labels 已明显膨胀时，time-indexed 可能优于 ng-DSSR；当首个 exact 很快而 time-indexed 分数结构仍需要较多节点时，ng-DSSR 更有优势。后续若做自动选择，可以利用已经执行的 time-indexed root preprocessing，再结合首个 exact 调用的耗时、轮数和 labels 做有界判断；本轮只记录条件，不改求解流程。

## 24. setup cost 不是主因，family setup time 才是结构载体

保持 family setup time 不变、只把显式 setup cost 系数从 `20` 降到 `8` 和 `0` 后，family 根 gap 仍为 `4.391%` 和 `4.030%`，加权重复次数仍为 `6.690` 和 `6.510`。因此系数 20 会放大边界偏好，却不是 family 困难的主导来源。即使 setup cost 为 0，setup time 仍进入完成时间递推：一次跨族转移增加的时间会把后续所有任务的完成时间整体后移，并通过每个任务的 earliness/tardiness PWLF 改变整个 suffix 的成本。family 结构因而不是一个可单独删掉的局部弧费用，而是会传播到整条序列的时间结构。

这也解释了为何只有把 setup time 和 setup cost 同时清零时，根 gap 才降到 `0.179%`、正值列全部 elementary、ng-DSSR exact 降到 `1.626s`。当前可确认的核心不是“20 太大”，而是 setup time 形成了稳定的块内短、块间长结构；显式费用只是附加放大项。

## 25. 从重复覆盖到三个分数单族流

对任意根 LP 解，令总机器流 `M=sum_r lambda_r`，定义

\[
D=\frac{\sum_r\lambda_r|\operatorname{distinct}(r)|}{M},\qquad
R=\frac{\sum_r\lambda_r(|r|-|\operatorname{distinct}(r)|)}{M}.
\]

`D` 表示每消耗一单位 LP 机器流平均接触多少个不同任务，`R` 表示同一单位机器流中有多少序列位置用于重复已访问任务。family 的 `20=13.102+6.898` 对应覆盖放大倍数 `20/13.102=1.527`；random 的 `20=19.065+0.935` 仅为 `1.049`。这不是对真实机器排程的解释，而是在量化 visit-count master 中一单位分数机器流能够制造多少覆盖系数。

用 `F=3,m=2` 的示意例子可把机制写清。假设每个 family 有约 13 个任务，并有一组单族 relaxed 列平均走 20 个位置，即每个不同任务平均出现约 `20/13` 次。给每个 family 的单族列混合总权重约 `13/20=0.65`，该族每个任务可得到约 `0.65*(20/13)=1` 的 visit-count 覆盖。三个 family 合计只消耗约 `3*0.65=1.95` 单位机器流，却能把全部覆盖行填满，而且没有任何一条列跨 family。真实 set02 根解的三组权重为 `0.693/0.595/0.713`，合计恰为 2；具体重复分布不是完全均匀，但数学作用相同。

如果列必须 elementary，同一任务最多出现一次。只用单族列覆盖某个 family 时，该 family 的列权重至少为 1；三个 family 分开至少需要 3 单位机器流。真实问题只有 2 台机器，所以至少一台机器必须同时加工两个 family，并承担至少一次跨族 setup time、可能的 setup cost 以及由时间后移造成的 suffix ET 变化。`F>m` 的含义正是：整数解存在不可避免的跨族合并决策，而 visit-count relaxation 可以用小于 1 的重复单族流逃掉该决策。`F<=m` 时每族本来就可以分配一台机器，重复环仍可能让 pricing 很难，但不再系统性逃避一项整数解必付的跨族合并。

family 之所以比 random/zero 更容易形成这种重复流，不是因为 setup 均值更小，而是低 setup 弧具有相关拓扑。family 的块内大量入口和出口同时较短，重复任务或换到另一个已访问同族任务可以长期留在一个低 setup 子图内；换成族外未访问任务则普遍触发较长 setup，并移动整个后缀完成时间。每个 family 又同时含早、中、晚 due 任务，使这种单块 walk 能贯穿较长 horizon。random 的短弧是分散噪声，没有一个成员集合能持续封闭地提供低 setup 替代；zero 中访问任意未访问任务与重复任务的 setup 都同为 0，重复不再规避任何边界，只剩额外 processing 和 ET 扰动。zero 因而只能在 setup 图意义上看成一个完全均匀块；random 是无稳定分层的异质图，不能严格称为一个 family。

## 26. family-touch cover cut 的准确含义

对每个已知结构组 `G`，可定义列系数

\[
q_{Gr}=\mathbf 1\{r\text{ 至少访问一次 }G\},
\]

并加入

\[
\sum_r q_{Gr}\lambda_r\ge 1.
\]

该行约束的是“触及该 family 的路线权重”，而不是访问次数。某条列无论在 `G` 中重复 1 次还是 10 次，系数都只有 1。它对目标 elementary master 是有效的：`G` 中的任务必须被覆盖，所以至少有一单位被选路线流触及 `G`。它并不要求每个 family 独占一台机器；一条跨两个 family 的路线可同时在两条 family-touch 行中计 1。以三个 family、两台机器为例，三条 touch 行迫使总计出现三次 family touch，而机器流只有 2，因此至少一单位路线流必须同时触及两个 family，正好恢复 relaxation 逃掉的跨族合并语义。

该 cut 目前只是待测方向，尚未实现。它的优点是行数少、对 family 数较小时 pricing 只需记录某个 family 是否已首次触及；风险是它只适用于数据中确有固定结构组的实例，也不能处理单轮 ng-DSSR labeling 已经爆炸的问题。它首先针对 time-indexed visit-count 弱界，不是 ng-DSSR 的直接加速器。

## 27. 初始 ng-set 为空的严格 A/B

本轮使用同一正式数据加载器、同一 `F=2,m=2` 实例、同一 seed 和完整 `BestBpcProfiles.NG_DSSR` 配置，只把每次 exact pricing 的初始模式从 `nearestK` 改为 `empty`。正式 `nearestK` 在 40 个任务下实际为每个任务 4 个其他任务。两组都在根节点闭合到 `78510`，结果验证通过。

| 初始模式 | 总时间 | exact pricing | exact calls | DSSR 总轮数 | non-elementary seen | ng updates | elementary returned |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `nearestK(4)` | `84.343s` | `71.982s` | 7 | 107 | 811311 | 359 | 58 |
| `empty` | `98.076s` | `71.747s` | 7 | 118 | 804392 | 530 | 44 |

空集的 exact 时间只少 `0.235s`，差异约 `0.3%`，没有实际加速；DSSR 轮数增加 `10.3%`，ng 更新增加 `47.6%`，返回 elementary 负列反而更少。总时间多出的约 `13.7s` 主要来自与初始 ng-set 无关的运行波动：同一 time-indexed root preprocessing 为 `25.530s` 对 `11.837s`，master LP 为 `18.216s` 对 `7.353s`。因此不能把总时间差归因于空集，但 exact 指标足以判定空初始集无收益，当前应保留 `nearestK(4)`。

普通 VRP 也会遇到 ng-relaxation 的重复路线和 DSSR 多轮问题，这正是 dynamic ng、arc memory 和 selective pricing 等工作的背景。但 TWET family 结构叠加了几个更不利因素：没有容量或硬时间窗快速截断重复；soft due 只增加代价而不立即判 infeasible；删除一次重复访问会整体移动后缀完成时间，不能简单使用度量 shortcut 证明改进；块内短、块间长的 setup time 又为大量可交换重复环提供稳定拓扑。当前 `ngDssrReturnRelaxedColumns=false`，这些非基本路线不会进入 elementary RMP，但它们仍作为 pricing witness 反复触发 memory 更新；time-indexed root relaxation 则确实会把 visit-count 非基本列放入临时 master，因而同时表现出弱 LB。两种困难共享同一 family 重复结构，但发生在算法的不同层。

## 28. family 相对 zero 的重复诱因、elementary master 和 VRP 边界

### 28.1 当前 family 矩阵是严格分层，不只是均值分离

正式生成器先取得有向 random 基础度量 `D`，对每条 job-to-job 跨族弧统一增加 `Delta=2*p_bar`，族内弧不加，然后整体缩放到相同 setup 总均值。由于基础弧本身有 `p_bar` 上限，当前数据中跨族弧和族内弧形成严格分层。对 `n040-set02` 的实际落盘矩阵重新统计，族内 setup time 范围为 `1--15`、均值 `7.7146`，跨族范围为 `30--44`、均值 `36.6660`。因此任意两个不同 family 之间的 job-to-job setup 都比任意同族 setup 大；起点到任务的 depot 弧不加 family switch penalty，不属于这一陈述。

### 28.2 family 为何会重复，而 zero 不会

需要比较的不是“重复任务”和“任意未访问任务”，而是当一条 relaxed 路线已经使用了本族大部分时间合适、dual 较高的任务后，下一次扩展的两个选择：再次访问一个本族高 dual 任务，或转向族外尚未访问任务。两者都会取得一次任务 dual；差异在物理增量。family 中前者只付 `1--15` 的 setup time，后者必付 `30--44`，并把这个额外时间传播给整个后缀的 ET penalty。只要族外任务的 dual 和时间收益不足以补偿这项边界代价，revisit 的 reduced cost 就更低。

例如当前在 family A，重复 `j in A` 的近似增量为“setup 5 + processing/ET 20 - dual 35 = -10”；访问尚未出现的 `k in B` 为“setup 35 + processing/ET 20 - dual 35 = 20”。这只是机制示意，不是实际某条列的逐项重算，但说明同样取得一次 dual 时，family 边界如何把重复变成更便宜的扩展。一个 family 只有约 13 个任务，而根 LP 每单位机器流平均需要约 20 个 visit-count 位置；当便宜且时间合适的 13 个不同任务不足以继续提供负 reduced cost 时，relaxation 可以重复其中部分任务，继续取得 dual，同时避免跨出低 setup 块。

zero setup 下上述两个选择的 setup 都为 0。重复 `j` 和访问新任务 `k` 都能取得一次 dual，但新任务不产生重复且可直接形成 elementary 覆盖；重复任务还额外消耗 processing time，并在更晚位置承受或传播 ET 变化。因此除非某个 `pi_j` 偶然极端大，重复没有稳定优势。random 中也可能偶然出现某条重复负路线，但从当前任务出发通常能在全部未访问任务中找到 setup 相近的出口，不存在“所有族外任务统一贵 20--30 个时间单位”的封闭边界，所以这种优势不会在一整个任务块中反复复制。

### 28.3 `F>m` 弱界是 visit-count relaxation 特有放大，不等于普通 LP 分数性

用户对 time-indexed 根解的概括是准确的：zero、random 或 `F<=m` 时，不存在必须由有限机器承担的额外跨族合并，relaxed 列即使分数，也更接近真实机器排程；`F>m` 时则可出现多组总权重小于 1 的单族重复列，每组都不跨族，却利用重复 visit count 填满本组覆盖，最终以总机器流 `m` 拼出一个离真实整数解很远的 LP 解。

但这一完整逃逸机制不会原样出现在当前 ng-DSSR 的 elementary master 中。若所有列 elementary 且都只触及一个 family，则对每个 family `G`，覆盖其中任一任务已经要求触及 `G` 的列权重至少为 1。三个 family 因而至少需要 3 单位机器流，不能在 `m=2` 下仅靠单族 elementary 列满足覆盖。elementary LP 仍然可能分数，也可能把若干跨族列以分数权重组合，但它必须在加权意义上承担至少一单位跨族连接，不能像 time-indexed visit-count 解那样完全省掉跨族 setup。

更形式地，令一条路线触及的 family 数为 `h_r`。每个 family 至少需要一单位 touch，所以 `sum_r h_r*lambda_r>=F`；机器数行为 `sum_r lambda_r=m`。因此

\[
\sum_r (h_r-1)\lambda_r\ge F-m.
\]

当 `F=3,m=2` 时，elementary LP 至少承担一单位“额外 family touch”，即必须存在总权重足够的跨族路线。普通 set-partitioning fractionality仍可能造成 gap，但不再具有三个 `2/3` 单族重复流这种额外的覆盖放大。

### 28.4 family-touch cut 与子环约束的关系

它与 VRP subset connectivity/capacity cut 有相同直觉：选择一个任务集合 `G`，要求解必须有路线进入并服务它。但它不是普通 arc-level subtour elimination constraint 的直接照搬。标准边界 cut 通常按路线穿越 `delta(G)` 的次数计系数；一条非基本路线多次进入 `G` 会被计多次，分数权重仍可能借重复穿越放大。family-touch cut 把每条路线的系数截成二元值：只要触及 `G` 就计 1，无论访问、进入或重复多少次。因此它更准确地说是 route-level subset cover/linking inequality，在 elementary master 中冗余有效，专门用于切掉 visit-count relaxed columns 的重复放大。family 只是当前有明确数据结构、行数很少的一组候选集合。

### 28.5 VRP 并非绝对没有该现象

标准 elementary VRP route master 与上述 elementary 证明相同：若 cluster 数超过车辆数，只使用单 cluster elementary routes 也不可能满足固定车辆数，必须有跨 cluster routes。若某个 VRP 求解器把可重复客户的 q-route/ng-route 以 visit-count 系数直接放入 master，它理论上也可能产生当前这种分数重复覆盖；这正是 q-route/ng-route lower bound 更弱、需要 DSSR或cuts恢复的原因之一。

普通 VRP 中该现象通常没有当前 family TWET 突出，原因不是单纯“车辆更多”，而是几项结构共同限制。很多 clustered VRP 的可用车辆数不小于自然 cluster 数，跨 cluster 合并本来就不是必付决策；固定车辆且 cluster 数更多时，类似压力仍会出现。其次，容量、route duration 和硬时间窗会随重复访问单调消耗资源，使一条路线难以把 13 个客户扩成 20 次访问。再次，度量旅行成本允许删除重复客户并 shortcut，通常不增加物理路径成本。TWET 没有硬容量，due 是软约束，重复仍可在宽 horizon 内保持可行；删除重复位置还会移动整个后缀完成时间，不能由三角不等式直接证明更优。因此车辆数只是条件之一，真正区别是 elementary incidence、资源单调性、硬可行性和 shortcut 性质。

### 28.6 当前 ng-DSSR 慢在哪里

当前 ng-DSSR RMP 只接收 elementary columns，所以它没有 time-indexed 的三个分数单族流弱界。它慢在 exact pricing 的证明过程：初始 nearest-K 只记住 4 个邻居，而 `F=2` 的每个低 setup 块有 20 个任务。一个已访问任务离开 ng memory 后，relaxed walk 可以在块内经由其他未记忆任务回到它；DSSR 禁掉一个 missing pair 后，还有大量同族任务可替换成新的低 setup 回路。

于是每次 exact pricing 都经历“最负路线是非基本单族 walk -> 加少量 memory -> 换一个同族重复环再次成为最负路线”的过程。`F=2,m=2` 的 7 次 exact 调用累计执行 107 轮、观察约 81.1 万条 non-elementary routes。memory 增大后，带不同 memory 状态的 labels 更难 dominance；宽时间窗和 sequence-dependent PWLF 又使相同任务集合、不同到达时间的 labels不能简单合并，所以后期单轮也越来越贵。zero 下没有统一昂贵边界，relaxed pricing 更容易直接延伸到未访问任务并较早得到 elementary 负列或无负证书；family 下则需要先排掉一个庞大的低 setup 重复路线族。

因此两类恶化要继续分开表述：`F>m + visit count` 解释 time-indexed LB 为什么弱；`块内可替换重复环 + 小初始 memory + 宽时间/PWLF状态` 解释 ng-DSSR certificate 为什么慢。前者不会原样污染当前 elementary RMP，后者即使最终 root gap 很小也仍然可能非常严重。

## 29. 用户核心直觉的原样记录与严格化

### 29.1 用户当前理解原文

> family的这种情况更倾向先访问family内的，即使存在重复，因为这样做再LP上既可以满足现有的覆盖约束，因为可以一个里边重复访问，那么就倾向于每一列不做family之间的访问，因为一旦做就会增加成本，而每一列即使不跨family去访问，由于重复访问，最终也可以满足覆盖条件。  而对于random zero的来说，找列的时候由于不存在family的概念，其实可以看成是一个family内部，那么其实就相当于说为了满足覆盖约束以及最小化成本，没有类似那种跨family的高成本，那我可能就更倾向于减少重复的访问,即把那种重复访问的job换成其他类似的setu的job，既能够提高基本列的比例，又能成本基本不变，然后还能满足覆盖约束。  大概就是这么个思路吗  即两个权衡，首先最小化成本，其次基本列，family放弃基本列能够最小化很大的成本，那就LP很弱，而random zero没有这种放弃基本列的需求，不存在一个列跨family的高成本，那就尽可能基本列 是这个思路吗

这段理解的主干是正确的：family 中“不跨块但重复”可以同时取得低 setup 路径和 visit-count 覆盖，random/zero 中则可用未访问任务替代重复而不付统一的跨块代价。需要修正的只有“首先最小化成本，其次基本列”这一句。pricing 没有 elementarity 次级目标；它只最小化 reduced cost。基本列比例更高，是替代选择的自然结果，不是算法主动偏好基本列。

### 29.2 更精细的两层机制

第一层是单次 pricing 的局部扩展。RMP 覆盖约束通过 dual `pi_j` 变成每访问一次任务 `j` 就取得一次 reduced-cost reward。family 中，当路线已经在块内时，重复本族任务和访问族外新任务都能取得 dual，但后者统一多付严格更大的跨族 setup time，并把时间差传播到后缀 ET 成本。于是 relaxed pricing 存在稳定偏好：即使牺牲 elementarity，也继续在块内重复。zero 中所有任务都没有 setup 边界；random 中也不存在一个对整组任务统一昂贵的出口。对某个重复任务，通常能从大量未访问任务中找到 processing、dual、时间位置和 setup 都相近的替代，因此 elementary extension自然更容易竞争过 revisit。

第二层是 master 的全局拼装。pricing 产生的单族重复列并不是自己“满足全部覆盖”，而是多条列以分数权重组合后，利用访问次数把本族每个覆盖行填到 1。`F>m` 时，这种组合把每个 family 压成小于一单位机器流，多个单族流合计为 `m`，从而逃掉真实解必须执行的跨族合并。random/zero 没有对应的结构边界，重复列即使偶然存在，也很难组成多个互不跨越、同时显著低估成本的分数子解，所以根极点更接近 elementary 排程。

因此最精炼且准确的表述是：

> family 的严格 setup 分层使 relaxed pricing 愿意用重复访问换取“不跨块”；visit-count master 又把这些重复转化为覆盖放大；当 `F>m` 时，多组单族分数流可完全规避真实机器必须承担的跨族合并，因而形成弱 LP。zero/random 没有统一昂贵的块边界，重复任务通常可被成本相近的未访问任务替代，所以非基本性缺少系统收益，LP 自然更接近 elementary 解。这里不存在“次级最小化重复”，只有 reduced-cost 竞争结果。

### 29.3 `q_Gr` 不是新变量，而是列的预计算属性

对固定 family `G` 和固定路线列 `r`，`q_Gr` 只是一个 0/1 系数：

\[
q_{Gr}=\begin{cases}
1,&r\text{ 的序列中至少出现一个属于 }G\text{ 的任务},\\
0,&r\text{ 完全不访问 }G.
\end{cases}
\]

例如 `G={1,4,7}`：路线 `(0,1,4,1,0)` 虽在 `G` 中访问三次，`q_Gr` 仍为 1；路线 `(0,2,5,0)` 完全不碰 `G`，系数为 0；路线 `(0,1,2,4,0)` 触及两个 `G` 中任务，系数也仍为 1。约束 `sum_r q_Gr*lambda_r>=1` 因而计算“总共有多少路线权重真正触及 G”，不计算访问了多少次。

在两个任务的示例中，`r1=(a1,a2,a1)`、`r2=(a1,a2,a2)` 的 `q_Ar1=q_Ar2=1`，但 `lambda_r1=lambda_r2=1/3`，所以 touch flow 只有 `2/3<1`，正好被该 cut 排除。它阻止的是“少于一单位路线流靠重复 visit count 覆盖整个 family”。

### 29.4 ng-DSSR 与 zero 的区别

“family ng-DSSR 慢，本质是 DSSR 轮数多且后期每轮越来越难”是准确结论。轮数多来自同一低 setup 块内有大量可替换重复环；每轮变难来自 memory 增大后 label 状态更细、dominance 变弱，再叠加宽时间域和 PWLF envelope。

zero 不会同样恶化，与第29.2节第一层是同一原因。没有跨族边界时，relaxed pricing 可以在全部未访问任务中寻找与 revisit 相近或更好的扩展，最负路线更容易直接 elementary；即使出现偶然重复环，也没有 20 个任务组成的封闭低 setup 块提供成批替代。初始 nearest-K 往往足以破坏少量短环，DSSR dynamic memory增长很小，后续 dominance不会持续恶化。历史 set01 对照中，zero 为 `42` 次 exact、合计 `50` 轮和 `16.348s`，平均每次约 `1.19` 轮；family 为 `10` 次 exact、合计 `150` 轮和 `288.116s`，平均每次 `15` 轮。这说明 zero 不是绝对没有非基本 witness，而是 witness 不形成需要连续十几轮收紧的稳定结构族。

## 30. 论文解释口径、cut 的 robust 性和数据合理性

### 30.1 一条统一的论文解释：松弛路径的边界规避

论文中不应堆叠“dual、重复、dominance、窗口、PWLF”等实现原因作为第一解释。最容易理解的统一机制是：family setup 在任务图中形成了昂贵的组间边界；当 family 数超过机器数时，真实排程至少有一台机器必须跨越边界；松弛路径却可以通过在组内循环来延迟或完全规避这次跨越。

该机制在两种算法中产生不同后果。time-indexed 松弛允许组内循环列进入临时 master，重复访问又按 visit count 贡献覆盖，所以多个分数单族流能够替代真实的跨族机器，直接造成弱 LB。ng-DSSR 的 elementary master 不接收这些循环列，但 relaxed pricing 仍会把它们识别为最便宜路线；DSSR 必须不断增加 memory，直到所有当前负 reduced-cost 的组内循环都被击中，才能找到 elementary 列或取得无负列 certificate。zero/random 没有稳定昂贵边界，将重复任务换成未访问任务通常不增加同等级转移代价，循环因而没有持续的边界规避收益。

可用于论文的压缩表述为：

> Family-dependent setups create costly boundaries between task groups. When the number of families exceeds the number of machines, a feasible schedule must cross at least one such boundary. The relaxed path spaces can avoid this decision by cycling within a family. In the time-indexed relaxation, repeated visits provide multiple coverage contributions and allow fractional within-family paths to replace the required cross-family machine. In ng-DSSR pricing, these paths are not admitted to the elementary master, but they repeatedly appear as the cheapest relaxed routes and must be eliminated through successive memory refinements. Without a persistent family boundary, a repeated task can typically be replaced by an unvisited task at a comparable transition cost, and the mechanism largely disappears.

该段先给一个原因和两个后果。`D/R`、`F>m`、107轮DSSR和PWLF弱dominance应放在后续实证或技术解释中，不要全部塞入主解释。

### 30.2 family-touch cut 不是 robust cut

`q_Gr=1{route r touches G}` 对每条路线只计一次，不能写成固定 arc 系数之和：路线第一次进入 `G` 应计 dual，后续再次进入不能重复计。pricing 因而必须记录“是否已经触及 G”的状态；多个 `G` 会增加一组 bit memory。按 branch-price 文献的通常定义，它不是 robust cut。它在任意非空任务子集 `G` 上都对 elementary 目标模型有效，所以聚类质量不影响正确性；但 `G` 选得不好只会产生弱行并增加 pricing 状态。

若真实数据没有已知 family，可从 setup-time 图或当前 relaxed LP support 中启发式寻找低内部setup、低边界流且违反 `sum q_Gr*lambda_r>=1` 的集合，但对 touch coefficient 的精确动态分离不是普通 min-cut，工程上并不低侵入。因此原 family-touch cut 不应直接作为近期实现建议。

对应的 robust 替代是有向边界 cut：

\[
\sum_{(i,j):i\notin G,j\in G}x_{ij}\ge1.
\]

它按进入 `G` 的 arc 次数计数，dual 可直接加到 arc reduced cost，不增加pricing状态，并可在聚合 LP arc flow 上通过 depot-rooted min-cut 动态寻找 `G`。它对当前“单族路线从depot进入一次后一直留在族内”的正值列也能形成相同约束；但若非基本路线多次离开再进入 `G`，重复 entry 会放大左端，因此它比 touch-once cut 弱。后续若研究cut，应先评估这种可自动分离的 robust boundary cut，而不是依赖人工 family 标签。

### 30.3 数据设计没有数值错误，但属于强结构化压力测试

当前正式数据在数学和量级上没有发现违反直觉的错误。random/family 保持相同任务、due、机器数和job-to-job总体平均 setup；setup 矩阵非负、无cap触顶、满足有向三角不等式，family类间/类内均值比在全部正式数据中为 `4.6336--5.0959`。family scheduling 文献本来就常用“同族无需或只需很小setup、换族需要显著setup”的结构，因此严格分层本身不是不合理假设。[Schaller and Gupta (2008, *EJOR*)](https://www.sciencedirect.com/science/article/pii/S0377221706008204)明确采用同族相邻时无需setup、换族时需要已知family setup的经典模型；[Kramer, Iori, and Lacomme (2021, *EJOR*)](https://www.sciencedirect.com/science/article/pii/S0377221719305703)研究并行机family setup时同样规定只有连续任务来自不同family才发生setup。当前模型保留 `1--15` 的族内sequence-dependent setup，反而比经典同族setup为0更一般。

量级也没有被setup淹没。`n040-set02` 的平均 processing为55、总processing为2200，setup总体目标均值为27.5、最大落盘setup为44，未超过单任务平均processing。当前整数解中family/random实际setup time分别约为`325/528`，只占总processing的`14.8%/24.0%`；显式setup cost占总目标`7.89%/10.64%`。系数20有可见影响但没有主导目标。family数与机器数也同时包含`F>m`、`F=m`和`F<m`档位，不是所有实验都固定在最坏关系。

需要在论文中诚实保留两个边界。第一，类间setup严格高于类内setup且比值约4--5是刻意的 strong-family 场景，不能宣称代表所有任意sequence-dependent setup；random配对实例正是必要对照。第二，due-window center按Tanaka processing workload和参考机器数构造，没有再补偿名义setup负荷，因此不能宣称保留原Tanaka实例的绝对时间松紧度。现有正文已经把setup写成在固定任务/窗口后加入的独立生产因素，这个口径是安全的。该设计不会使算例“失真到不可用”，但结论应表述为强family结构如何影响算法，而不是现实工厂中4--5倍分离的普遍频率或绝对性能。

### 30.4 当前最终决定

当前不实现 family-touch cut，也暂不继续 robust boundary cut、动态集合分离或其他 family 专用 cuts。touch-once cut 非 robust，需要额外pricing状态；robust boundary cut虽低侵入，但更弱且当前尚无增量实验依据。现阶段只保留机制分析，不改变模型、定价流程和正式配置。

正式数据继续使用。其可辩护口径为：random/family只改变setup的相关结构而保持总体均值；全部family分离比为`4.6336--5.0959`且通过cap、非负和三角审计；`n040-set02`平均processing为55、最大setup为44；当前整数解实际setup time为family/random `325/528`，占总processing `2200` 的`14.8%/24.0%`；显式setup cost占总目标`7.89%/10.64%`；实验矩阵同时包含`F>m`、`F=m`和`F<m`。这些证据说明数据是有意构造的强family压力测试，但没有被setup量级或单一最困难family/机器关系支配。
