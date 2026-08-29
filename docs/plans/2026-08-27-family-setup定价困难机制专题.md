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

1. `same-node warm start`：保存同一 BPC node、同一 active-cut 集合最近几次困难 exact 的 final ng-set，在下一次调用的基础 K 上有界追加高频成员。2026-08-28 A/B 后，默认关闭的实验参数收敛为窗口 3、至少跨 2 次 exact 重复出现、单 job 最多追加 2 个、全局最多 10 个 pair，且上一调用至少执行 3 轮才触发。追加成员仍会经过当前 repeatability filter。
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

## 31. 暂定补充实验：family 数跨越机器数的阈值效应

### 31.1 实验问题和最小设计

暂定增加一个机制型补充实验，专门回答：固定任务和机器数后，family 数从不超过机器数变为超过机器数时，time-indexed relaxation 是否出现可重复的 root-gap 突变，以及该变化是否由单族重复覆盖解释。

主设计选 `n=40,m=3`，family 数取 `F={1,2,3,4,5}`。选择 `m=3` 而不是继续使用已有 `m=2`，是为了做阈值平移检验：已有单实例诊断在 `m=2` 时由 `F=2` 的 `0.450%` 跳到 `F=3` 的 `7.816%`；若机制确为 `F>m`，新实验应在 `F<=3` 时保持较小gap，并在 `F=4` 首次出现明显上升。`F=5` 用于观察超过阈值后继续增加强制跨族合并数，gap 是继续扩大还是趋于饱和。`F=1` 是无family边界控制，`F=2` 和 `F=3` 分别代表 `F<m` 与 `F=m`。

使用正式 `n040` 的5个任务集合，每个任务集合共享processing、due、权重、机器数、原始random有向度量和setup总体均值，只改变平衡family划分和由此产生的跨族项。family assignment使用每个任务集合一份固定随机排序，再按不同`F`均衡分配；所有`F`均保持平均setup约为`0.5*p_bar`、setup cost系数20、相同cap和三角审计。主实验固定base时间尺度、点due窗口`W=0`和不允许外包，避免窗口、尺度和外包同时变化。5个任务集合乘5个family档位，共25个物理实例；同一实例的算法对照共享其专用seed。

### 31.2 主要指标

主结论只依赖 no-cut time-indexed 根 LP，不要求把补充实验扩成新的完整算法矩阵。每个实例报告以下指标：

1. 以该实例已证明最优值为基准的 root relaxation gap；若个别实例未证明最优，只能标记best-known gap，不能与proven gap混合求均值。
2. 加权不同任务数 `D`、重复位置 `R` 和覆盖放大倍数 `(D+R)/D`。
3. 正值列中elementary/non-elementary比例、只触及一个family的列权重，以及每列触及family数 `h_r`。
4. 加权额外family touch `E=sum_r (h_r-1)*lambda_r`，并与elementary解必须满足的下限 `max(F-m,0)` 比较。若 `F>m` 时 relaxed根解仍有 `E< F-m`，即可直接说明它通过重复单族流逃掉了必需跨族合并。
5. root RMP列数、pricing rounds和root时间，作为计算代价背景，不把总求解时间作为该机制实验的首要结论。

ng-DSSR可在同一25个实例上附带记录root exact pricing时间、DSSR总轮数、non-elementary witness数和最大labels，用于说明family数对certificate难度的影响；但不预设其在`F=4`也发生单调突变。family数增加同时会减小每个块的规模，ng-DSSR可能出现“time-indexed gap变差但块内替代环减少”的非单调结果，这恰好能继续区分两类机制。

### 31.3 结果展示和验收条件

正文或补充材料优先使用一张双面板图。横轴均为`F`，在`F=m=3`后画竖线：面板A画5个任务集合的配对root gap及均值/中位数；面板B画重复次数`R`和额外family touch deficit `max(F-m,0)-E`。必要时另用一张小表报告ng-DSSR rounds/exact time，不把全部日志指标塞入主图。

支持当前机制的最低证据不是某一个set02再次变难，而是：大多数配对任务集合的gap上升位置从旧`m=2`实验的`F=3`平移到新`m=3`实验的`F=4`；上升同时伴随`R`增加、正值单族列权重增加和`E<F-m`。若gap随`F`平滑变化、在`F=4`没有共同变化，或gap变化不伴随上述列结构，则不能写成`F>m`阈值结论，只能报告family数的经验敏感性。

该实验当前只作为暂定补充设计记录。尚未生成25个实例、运行求解或改动正式manifest；完成小规模预跑并确认root可在合理时间闭合后，再决定是否纳入论文正式实验包。n50/m4不同时展开，只有n40/m3结果清晰后，才考虑用少量`F={3,4,5}`的n50实例检查阈值是否进一步平移到`F=5`。

## 32. `F>m` 时 family 松弛弱的最终本质解释

### 32.1 一句话结论：重复覆盖把 family 流压缩进有限机器

当 family 数 `F` 超过机器数 `m` 时，真实 elementary 排程必须把至少两个 family 合并到同一台机器，因此至少承担 `F-m` 单位的额外 family touch以及相应的跨族setup和后缀ET影响。family setup使这项合并昂贵；time-indexed visit-count松弛却允许每个family使用小于1的分数机器流，并靠同一任务的重复访问把覆盖补到1。于是LP把本应不可分的跨族机器决策替换成多个互不跨族的分数单族流，整体绕掉了真实解必须支付的边界成本。

### 32.2 为什么重复量恰好能完成这种压缩

若每个 family 只能使用 elementary 单族列，则覆盖该family至少需要1单位路线流，`F`个family合计至少需要`F`单位。当前只有`m`台机器，所以要在完全不跨family的情况下满足机器数，relaxation必须把每单位单族流的覆盖能力至少放大到

\[
\frac{F}{m}.
\]

在已有`F=3,m=2`根解中，需要的放大倍数是`3/2=1.5`。实际正值列的平均长度为20、不同任务数为13.102，visit-count覆盖放大倍数为

\[
\frac{20}{13.102}=1.527.
\]

该数值刚好超过1.5，因此三组本来各需1单位的family流可以分别压缩为约`0.693/0.595/0.713`，合计正好为2。这个对应关系比单独说“重复很多”更接近根因：重复数量不是偶然冗余，而是恰好提供了把`F`单位逻辑family流压入`m`单位机器流所需的覆盖杠杆。

### 32.3 family setup 为什么让这种不真实列成为低成本列

覆盖放大只有在重复单族walk足够便宜时才会被LP采用。当前family矩阵中任意族内job-to-job setup为`1--15`，任意跨族setup为`30--44`。对relaxed pricing而言，重复一个本族高dual任务和访问一个族外新任务都能取得一次任务dual，但前者继续使用低setup弧，后者必须跨越昂贵边界并把额外时间传播到后缀ET成本。因此非基本单族walk同时获得两项收益：重复访问带来额外覆盖/dual，留在组内又避免跨族时间和成本。

zero/random缺少第二项收益。zero中任意替换出口setup相同；random中便宜出口分散在全图，不存在所有组外任务统一昂贵的边界。重复任务通常可以换成未访问任务而不显著增加reduced cost，所以非基本性不能稳定换取边界规避，覆盖放大也难以组织成多个互不跨越的分数子解。

### 32.4 必要条件和两类算法的不同后果

当前弱界不是由“family多”单独造成，而是四个条件叠加：`F>m`使真实解必须跨族；强family setup使跨族合并昂贵；松弛列允许重复并按visit count贡献覆盖；软时间结构使重复walk仍可行。缺少任一条件，完整的分数机器压缩机制都会显著减弱。特别是`F<=m`时，每个family本来就可由一台机器承担，重复仍可能让pricing困难，却不再能逃掉一项真实解必付的跨族决策，因此root gap可以很小。

time-indexed允许这些非基本列进入临时master，所以该机制直接表现为弱LB。当前ng-DSSR master只接收elementary列，不能把三个纯单族分数流压入两台机器，因此没有同一visit-count gap；但ng-relaxed pricing仍会不断找到这些边界规避型组内循环，DSSR需要多轮memory refinement才能排除它们。两种算法共享的是“松弛路径偏向边界规避”，不同的是time-indexed把它变成下界漏洞，ng-DSSR把它变成certificate成本。

论文解释应优先使用“昂贵family边界—重复覆盖—分数机器压缩”这一条主线，再用`1.527`对`1.5`、三组分数流和DSSR轮数作为证据；不应把窗口、dual、dominance和PWLF并列成多个同等层级的根因。

### 32.5 family 结构下 ng-DSSR 的剩余优化空间

#### 32.5.1 当前真正慢在哪里

当前证据已经把主要耗时定位到 exact ng-DSSR 的 forward labeling，而不是RMP、join、候选扫描或time-indexed预处理。`n040-set02 family`第一次exact pricing耗时`732.079s`、经历23轮DSSR，只返回18条elementary列；其中forward阶段为`711.978s`，占该次exact的`97.3%`，init、backward和join分别仅为`15.698s/0.568s/3.262s`。该调用累计约`1586万`次forward extension和`54.7万`个保留forward labels。完成时间bound已经剪掉约`1050万`次扩展，但仍有约`534万`次扩展存活，因此不能把瓶颈归因于缺少现有completion bound。

困难由两个乘数共同形成。第一个是DSSR轮数：低setup块内存在大量结构等价的重复环，每轮只击中一部分后，下一批替代环仍然为负。第二个是后期单轮成本：memory扩大后label状态被进一步区分，dominance变弱；宽时间域和PWLF envelope又使大量label长期存活。`F=2,m=2`实例虽只有`0.450%`的time-indexed根gap，ng-DSSR仍累计107轮、exact耗时`91.3s`，说明弱LB不是ng-DSSR慢的必要条件；真正应优化的是“每轮工作量乘轮数”，而不是继续围绕root gap调参。

候选处理不是直接时间热点，但暴露了轮数问题。`n040-set02 family`困难调用中约22000条witness进入更新检查，21891条在轮到时已被同轮前面的pair阻断，最终只有109条路线产生更新。高blocked比例本身说明minimum-segment更新有效，不代表扫描这两万条路线很耗时；它说明top-1000 reduced-cost池高度同质，一轮labeling付出数十秒后通常只能取得少量彼此独立的结构信息，随后还要重新跑完整一轮。

#### 32.5.2 第一优先级：让 `Tmid` 跟随完整一轮的真实负载

当前双向定价的实际分割严重偏向forward。困难调用多轮的forward/backward时间比超过数百，个别轮次达到数千；例如第一轮约为`25416ms/128ms`，后续仍出现`25613ms/29ms`以及更极端的不平衡。此时继续优化join或backward没有意义，最直接的目标是把一部分路径状态转移到backward侧，使两侧最大工作量下降。

现有adaptive midpoint会在每轮前做有上限的浅层probe，但probe只弹出约万级labels，难以预测forward在宽时间域下数百万扩展的尾部爆炸。已有日志中，上一完整轮已经明确forward更重，下一轮probe仍可能把`Tmid`向更晚时间移动，导致完整轮继续失衡。这不是定价正确性错误，而是probe目标与完整工作量不一致。

近期最值得做的A/B只改变midpoint选择，不改变DSSR、dominance、候选池或返回列：用上一完整轮的forward/backward耗时、扩展数和保留label数决定下一轮允许移动的方向，forward明显更重时`Tmid`只能不增，backward明显更重时只能不减；probe在重侧达到pop cap且队列仍非空时标记为截断，不能再把当前elapsed ratio当作完整工作量估计；先测试直接采用full-round feedback seed，再测试在该seed附近做窄范围probe，不能让浅probe把分割点重新推回已证实更差的方向。

任意内部`Tmid`在forward/backward覆盖和join保持完整时只影响计算分工，不改变可生成路线集合和certificate，因此这是当前风险最低、最可能直接降低`711.978s`主体耗时的方向。验收必须同时比较每轮forward/backward时间、最大单侧时间、extensions、labels、DSSR轮数、最终最小reduced cost、root bound和certificate；只看总时间不足以判断是否稳定。

#### 32.5.3 第二优先级：固定更新预算下提高 witness 的独立性

当前每轮先按reduced cost保留top-1000 non-elementary routes，再依次用minimum missing segment更新，最多处理20条有效路线。该策略优先得到最负witness，但family块内大量路线只是在同一个重复段周围替换邻近任务，前几次更新后其余候选会一起被阻断。问题不是20条预算太小，而是付出一轮labeling后得到的候选代表性不足。

可在不改变exact pricing流程和pair预算的前提下，增加一个很小的结构代表池：对完整non-elementary候选记录其minimum-missing-pair segment、重复job和缺失pair集合的规范签名；主池仍保留reduced-cost top-1000，辅助池只为不同阻断签名各保留最负代表。更新时在虚拟ng overlay上选择仍未被已选pair阻断、且带来新缺失pair或新重复段的路线，最终仍执行现有`effectiveRouteUpdateLimit=20`和minimum-segment规则。

目标是减少下一轮次数，不是增大一轮加入的pair数。第一步只加诊断，统计每轮top-1000中的不同签名数、每个签名规模、虚拟选择后可独立更新的路线数和pair总量；确认同质性确实压缩有效更新后再实现辅助池。A/B必须固定20条有效路线和pair预算，避免把少跑轮次与大memory造成的dominance退化混在一起。

#### 32.5.4 第三优先级：同样大小下构造连贯的初始 ng memory

简单把`nearestK`从4扩大到8已有负面证据：困难调用的DSSR轮数下降，但exact时间反而从`288.116s`上升到`354.134s`。初始空集A/B也没有收益。因此后续若改初始memory，重点不能再是加大K，而应是在相同K下让限制更有结构。

标准ng更新中，重复job `j`只有在整个`j ... j`中间段的各中心节点都持续记住`j`时才会被阻断。当前nearest-K由每个中心任务独立选择邻居；在稠密family内，各行的4个最近任务可能不同，于是没有某个高风险重复job能穿过整个块持续留在memory中。可从setup图自动识别低成本块，在每个块内选少量高repeat-risk或高dual anchor，并让该块的中心任务共享这些anchor。平均ng-set大小仍为K=4，却能完整阻断少数最有吸引力的组内重复环，而不是对许多不同任务各限制一小段。

该方向必须先做静态诊断：统计困难witness中各重复job的频率，以及每个job作为member被多少同块中心的当前nearest-K包含。只有出现“高频重复job但跨中心memory覆盖很低”时，cluster-anchor假设才成立。随后单独对比`nearestK4`与同大小的`clusterAnchorK4`，不能与witness diversity或warm start同时修改。分块必须由setup矩阵自动得到，不能依赖人工family标签。

#### 32.5.5 较低优先级和研究型后备方案

有界same-node warm start已有默认关闭实现。历史小样本出现约7%的中等收益，但增大memory也有明确反例；若前三项后仍有困难，只考虑在同一node前一次exact超过轮数或label阈值时复用极少数高频pair，设置严格总cap并随简单调用立即清空。它只能作为单独A/B，不能直接打开全量历史memory。

time-indexed结果可用于提供极少量初始提示：从root正值非基本列提取高频重复pair，经repeatability检查后追加到初始ng memory，但不能让这些列进入elementary master，也不能一次导入完整重复结构。该方向仍受“大memory削弱dominance”的约束，优先级低于同大小anchor设计。

如果经过midpoint、witness和同大小memory优化后，个别family exact调用仍稳定超过数十轮或数分钟，可研究no-SRI场景下的direct elementary MIP pricing后备：用job选择、序列arc、完成时间和PWLF变量直接强制elementarity，由CPLEX最优性给出定价certificate。它可能避开DSSR反复消除组内循环，但需要新的big-M、数值稳定性、多列提取和cut dual兼容设计，属于独立研究路线，不是近期小改。

#### 32.5.6 time-indexed 的定位和实施顺序

当前不建议继续把主要精力投入完整time-indexed下界。它在`F>m`时的弱点来自visit-count relaxation允许重复覆盖和分数机器压缩，普通数据结构或常数级实现优化不能消除该模型差距；把覆盖改成0/1或记录visited jobs实质上会重新引入elementarity状态。family-touch cut非robust，rank-1/SRI会增加pricing状态，也没有现成证据表明收益超过代价。

time-indexed保留为root arc/window preprocessing、elementary seed columns、困难结构诊断和少量pair提示即可。实施顺序固定为：`Tmid full-round feedback`、`witness签名诊断/辅助池`、`同大小cluster-anchor memory`，三项逐项A/B；之后才考虑tiny same-node warm start。任何一项若不能在family困难实例降低exact时间，同时保持random/zero和普通算例无明显回退，就不进入正式profile。当前只形成优化计划，未修改算法代码、配置或实验数据，也未启动新求解。

### 32.6 后续 family 数量灵敏度分析的解释口径

后续改变family数量时，将本节机制作为待检验假设，而不是预设结论。最直观的解释是：zero/random没有稳定的昂贵组间边界，pricing通常可以用成本相近的未访问任务替代重复任务，因此更倾向继续探索新任务；family结构则形成“组内普遍便宜、组外普遍昂贵”的低成本块，当组内有吸引力的新任务逐渐耗尽后，重复组内高dual任务可能比跨族探索更便宜。只有当`F>m`时，这种局部重复偏好才能进一步在master中把多个family各压缩为小于1单位的分数机器流，并规避真实解必需的跨族合并。

灵敏度结果应按“局部列结构—全局分数拼装—最终gap”三层顺序分析。首先检查`F`增加后非基本单族列、重复位置`R`和覆盖放大倍数是否增加；其次检查单族正值流是否降到1以下、额外family touch `E`是否低于`F-m`；最后才判断这些变化是否对应root gap在`F=m+1`附近上升。若只有gap变化而重复覆盖与family-touch deficit没有同步变化，就不能归因于分数机器流压缩；若重复增加但`F<=m`时gap仍小，则应解释为pricing变难而不是下界漏洞。ng-DSSR同样只把重复结构用于解释certificate成本，不能直接套用time-indexed的visit-count gap结论。

### 32.7 双向队列配置漂移与 Tmid probe 复核

本次`n040-set02 family`困难诊断没有沿用历史上反复验证更快的`TIME`队列，而是实际使用了`REDUCED_COST`。原因不是正式比较后改回，而是旧的`GCBBFullDomainComparisonTest`在应用profile后强制把forward和bidirectional queue都设为`time`；2026-08-16建立的`BestBpcProfiles.applyNgDssrDefaults()`没有显式固定bidirectional queue，新的`FormalExperimentRunner`也不再经过旧runner的强制赋值，因此回落到`TWETBPCConfig`为兼容历史行为保留的`reducedCost`默认值。2026-05的重复A/B中，ng双向定价的`TIME`为`1.561--1.678s`，`REDUCED_COST`为`2.573--2.922s`，目标值和界一致。仓库日志也以`TIME`为绝对多数；当前困难run的配置快照则明确记录`queueOrdering=REDUCED_COST`。因此`732.079s`仍能说明family结构会制造困难，但其绝对幅度混入了队列配置漂移，不能代表当前已知最佳ng-DSSR配置。

当前未发现第二个同样有历史A/B支持、却被正式profile遗漏的核心开关。`C=1000/K=20`、nearest约`n/10`、repeatability filter、关闭warm start、best-UB/all-cycles、root preprocessing及subtree pricing-only等均与预期一致。需要注意的是，正式profile仍继承若干类默认值，后续若固定最终实验配置，应把exact cap、Tabu参数、DSSR Tmid复用、branch seed和CPLEX模式等显式写入profile并补回归断言，防止默认值继续漂移；这只是可复现性措施，目前没有证据说明这些默认值设置错误。旧midpoint配置中的若干字段已不再控制当前ng-DSSR核心，属于失效配置面而不是这次运行时误配。

当前Tmid流程是：一次pricing调用内，第一轮从有效时间域中点出发；完成一轮forward/backward labeling后，用完整轮的两侧耗时和surviving-label分布为下一轮生成seed；若重侧与轻侧耗时比为`R`，用`alpha=min(0.5,0.5(1-2/R))`截去重侧上一轮约`alpha`比例的分布。该式在`R=2`时不移动，`R=4`时移动25%，`R`趋于无穷时最多移动50%，本质是带50%上限的经验比例控制器，不是最优性公式。更新ng-set后，当前实现又从该seed执行浅probe，按整个有效时间域宽度的10%移动并做bracket；接受后复用该候选的partial state，再把两侧队列完整跑完并join。

困难run暴露出浅probe会反向覆盖更可靠的完整轮反馈。第7轮完整结果显示forward极重，seed由`893.7`降到`492.0`；在该seed附近，前5000个backward pop较慢，但这是`REDUCED_COST`队列下不同深度label的局部前缀，并不代表完整工作量。probe随后一次按全域宽度移动`416.7`到`908.7`：forward在5000 pop时尚未耗尽，backward只用1186 pop已经耗尽，但两侧局部elapsed之比为`1.173<1.5`，当前控制流仍直接接受。该比较把“全部backward工作”与“forward前缀”相比较，效率语义不成立。只要一侧耗尽，未耗尽侧就应视为更重；只有两侧都耗尽时才能直接接受，两侧都未耗尽时才可结合elapsed、queue backlog、kept labels和多深度增长率作启发式判断。

“在同一dual/ng-set快照上完整测试多个Tmid”仅指离线诊断：冻结同一node、dual、cuts、窗口、completion bounds和某一DSSR轮的ng-set，对每个候选Tmid独立跑完forward、backward、compact和join，校验最小reduced cost和certificate一致，再比较完整总耗时。它可作为评估浅probe质量的oracle，但生产中每轮这样做会把一次exact成本放大为候选数倍。当前bracket还只采用最后停留点，没有保存所有已测候选中的最佳点；若未来修正，应按可靠性和预计完整负载保留最佳候选，但复用较早候选的label状态需要重跑或保存大状态，不能直接当作零成本改动。

当前结论仅限诊断，未修改配置或算法，也未启动新求解。后续严格顺序应是：先把`TIME`显式固定进ng-DSSR正式profile并加入回归断言；再单独修正“一侧耗尽仍可接受”的probe判据；最后才评估10%固定步长、窄bracket或多深度代理。不能把队列纠正、probe修正和family结构优化一次混测。

### 32.8 TIME 单变量复跑与 probe 位移结论

已将`TWETBPCConfig`中的单向和双向label queue默认值统一改为`time`，并在`BestBpcProfiles.NG_DSSR`中再次显式固定两项`time`，profile版本升级为`2026-08-28-v3`。`BestBpcProfilesTest`同时锁定裸默认值和正式profile值；旧`GCBBFullDomainBestProfileTest`原来错误要求所有profile都开启Phase-I repair，本次将该断言移回ng-DSSR专属部分，并明确time-indexed继续使用旧M repair。除queue外，`C1000/K20`、nearest约`n/10`、repeatability filter、关闭warm start、root preprocessing、pricing-only subtree fixing和strong phase2关闭等均未发现漂移。当前ng-DSSR仍有一组失效配置面：快照中的`MoveRatio=0.15`、`MaxCandidates`、`TimeTolerance`、`TieScore`、`ExtraCandidates`、`BracketOnDirectionChange`和`HighImbalanceRatio`不控制主线，主线实际固定使用`step=0.10*width`和`bracketTolerance=0.05*width`；本轮只记录，未与queue同时修改。

使用相同`n040-set02 family,m=2`、相同seed指纹`48fb3e...c1e35`、相同`900s`时限和节点上限，只把queue从`REDUCED_COST`改为`TIME`复跑。旧run完成1次exact并在第2次中途超时，exact累计`919.580s`、完成调用生成18列；新run完成12次exact、生成29列，exact累计`882.425s`，平均每次`73.535s`。第一次exact仍为23轮DSSR，但从`732.079s`降至`97.251s`，其中forward从`711.978s`降至`59.827s`，约快11.9倍。由此确认queue误配是旧run灾难性forward长尾的主要放大因素；family结构造成的多轮DSSR仍然存在，新run 12次exact合计262轮，平均21.83轮/次，900秒内根节点仍未闭合。

`TIME`没有让浅probe成为可靠的完整负载预测器。12次exact合计`882.076s`中，probe为`284.297s`、占`32.2%`，forward完整扩展`490.905s`、backward`43.301s`、join`56.256s`。262轮平均测试2.71个probe候选，完整forward/backward耗时比平均`8.13`，214轮仍超过2。排除每次pricing没有历史反馈的第一轮后，probe只有两种实质结果：55轮原样接受完整反馈seed，完整耗时比平均`1.66`、仅8轮超过2；其余195轮全部把seed固定提高`208.35=0.05*width`，完整耗时比平均约`10.17`，195轮全部超过2。原因是seed处浅层backward略重，先向上跳`416.7`，方向反转后bracket只折半一次就停在`seed+208.35`；这个浅层平衡点在完整搜索中仍然是forward极重。

因此下一项A/B不应先把10%步长简单改小。小步长会增加walk候选数，而当前probe本身已消耗近三分之一exact时间；它只能减轻过冲，不能修复浅层方向与完整方向相反。更直接的低风险策略是让上一完整轮提供方向约束：上一轮forward重时，下一轮adaptive seed之后的probe不得再提高Tmid；backward重时不得降低；上一轮已经在阈值内时直接复用上一Tmid，不再probe。第一轮没有完整反馈时仍可保留现有probe。现有自然对照已经足以支持先测该策略，没有必要先对同一dual/ng-set完整跑多个Tmid网格。

“仅一侧耗尽”仍应作为独立的小修复，但本轮未实现。建议在`acceptableRatio`判断前处理：仅backward耗尽时强制认为forward更重，仅forward耗尽时强制认为backward更重，不能按局部elapsed比接受；两侧都未耗尽时才使用启发式elapsed比，两侧都耗尽时结果完整。它只改变probe停止和移动方向，不改变定价集合或certificate。当前bracket采用最后测试点而非历史最佳点不是主要矛盾：本轮最后的浅层score通常确实最好，但它对完整负载预测仍然错误；即使改成“浅层score最小”也很可能继续选`seed+208.35`。保存较早候选还需要重跑或保留大label状态，因此该项暂不修改，优先级低于完整轮方向约束和单侧耗尽判据。

### 32.9 TIME排序的作用机制与单侧耗尽判据修正

进一步对比相同第一次exact的内部规模后，`TIME`不只是改变Tmid轨迹。`REDUCED_COST`共保留`547289`个forward label、构造`15856151`次forward扩展，`TIME`分别降为`102895`和`2449755`，即约减少`81.2%`和`84.6%`；两者DSSR轮数同为23。当前forward队列的`TIME`顺序按最早完成时间递增，backward按最晚完成时间递减，而`REDUCED_COST`会优先扩展当前内部reduced cost最低、但可能处在任意时间层和深度的label。family内部低setup和重复取dual使后者尤其容易持续追逐深层同族重复路径，在更浅、更可能形成有效dominance envelope的时间状态建立前先生成大量后代。`TIME`更接近单调资源拓扑顺序，因此既让固定5000-pop probe样本更能反映Tmid两侧的时间域工作，也让正式labeling更早建立可支配后续状态的前沿。第一次exact的Tmid范围由`686.35--1078.05`收窄为`643.05--787.35`，完整F/B耗时比均值由约`1270.6`降为`9.65`；但TIME的23轮仍全部大于2，说明probe准确性得到数量级改善但没有恢复为可靠完整负载预测器。

此前“只要一侧耗尽就把未耗尽侧强制视为更重”的一般表述过强。若backward已在100ms完成，forward在5000-pop处用时95ms但只剩极少工作，forward最终仍可能小于100ms。可确定的判据应为：一侧耗尽且未耗尽侧当前elapsed已经不小于已耗尽侧时，未耗尽侧最终工作量必然更大；若未耗尽侧当前更快，则方向仍不确定，应只对未耗尽侧定向追加少量pop，直到它也耗尽、累计elapsed越过已耗尽侧，或达到额外预算。旧`492 -> 908.7`异常属于前一种确定情况：forward未耗尽且已用`38.660ms`，backward已耗尽且只用`32.946ms`，因此可以安全判定forward更重。若额外预算后仍不确定，应优先保留上一完整轮方向或当前seed，不宜立即做10%全域移动。

本次同时重新核对了配置读者。当前ng-DSSR真正使用`bidirectionalMidpointProbe`、`PopLimit=10000`、固定`score=time`、`EarlyStopRatio=1.5`、`ReuseWithinDssr=true`和`DssrImbalanceThreshold=2.0`；步长`0.10*width`、bracket容差`0.05*width`为核心常量。`MaxCandidates`、`MoveRatio`、`TimeTolerance`、`TieScore/TieTolerance`、`ExtraCandidatesAfterThreshold`、`BracketOnDirectionChange`和`HighImbalanceRatio`只控制旧普通双向类，出现在ng-DSSR快照中不影响本次主线。剩余一个真实配置风险是`parseQueueOrdering()`对null或未知字符串仍静默回退`REDUCED_COST`；当前默认值、正式profile、回归断言和effective快照已共同锁定`TIME`，所以本次运行不受影响，但后续宜将未知值改成fail-fast，避免拼写错误重新触发同类性能退化。`completionBoundQueueOrdering=fifo`属于独立completion-bound DP，不是遗漏的label TIME配置。

12次TIME exact合计262轮DSSR，probe累计`284.297s`，平均每次exact`23.69s`、每轮`1.085s`，不是单个候选花费21--32秒；每轮平均测试2.71个候选。大量轮次机械得到`seed+208.35`并非算法“固定选择”该点，而是先按10%宽度移动`416.7`，方向反转后取中点，再因5%宽度容差恰为`208.35`而立即停止。下一项A/B仍应先限制probe不得反向推翻上一完整轮反馈；步长变小和历史浅层best选择均排在其后。

### 32.10 TIME回退统一、elapsed语义与启发式定价判断

当前对TIME收益的解释分成两条。对probe而言，核心是固定5000-pop样本从“按reduced cost抽取任意深度label”改成按时间资源顺序展开，因此两侧前缀更接近对应时间域的有序样本，Tmid轨迹明显稳定。对完整labeling而言，核心是扩展顺序改变后，更早建立可能支配后续状态的时间前沿，减少深层同族重复label先生成大量后代、后续再被支配的冗余。两条机制相关，但不能把完整labeling的11.9倍提速全部解释成probe更准。

按用户决定，所有8个labeling实现的`parseQueueOrdering()`现已统一为：null和未知字符串回退`TIME`，显式`reducedCost/reduced_cost/reduced-cost/rc`仍保留为受控A/B入口，`reachableSize`语义不变。涉及单向GC、普通双向、asymmetric、full-domain、node-join、旧GCNGBB、ng-DSSR和partial-dominance；completion-bound自己的FIFO/REDUCED_COST解析未改。新增`LabelQueueOrderingFallbackTest`逐个验证三种口径，8个实现focused编译以及现有profile、midpoint、effective-engine三组回归测试均通过。

probe日志中的`elapsed`是墙钟运行时间，不是调度完成时间、label的时间资源，也不是剩余工作量。`forwardElapsedMillis/backwardElapsedMillis`分别由`System.nanoTime()`包围当前候选下正向/反向最多5000次queue pop的扩展循环，包含取队列、构造扩展、PWLF更新、completion-bound判断和dominance维护。若某侧提前耗尽，其elapsed是该侧完整工作时间；未耗尽侧的elapsed只是5000-pop前缀时间。因此单侧耗尽时，只有“未耗尽侧当前elapsed已经不小于已耗尽侧完整elapsed”才能严格判定未耗尽侧最终更重；否则需要定向加深，不能直接强制移动。

当前family TIME run不支持启发式定价存在实现或列质量问题。`HeuristicPricing`共94次、81次成功、加入17496条elementary列，只耗`9.896s`；master LP在214次pricing后重解中合计`3.362s`。主循环在任一启发式调用加列后立即重解并从第一个engine重启，只有当前dual下启发式返回0列才调用ng-DSSR exact。12次exact之前均可观察到启发式先失败，exact加1--7列后有时又使启发式找到少量新列，符合预期协同流程。启发式直接耗时仅约占总900秒的1.1%，也没有造成LP建模瓶颈；关闭它很可能把前期17496条便宜列转移给昂贵exact。尚不能排除大量相似启发式列通过dual退化改变后续exact轨迹，但这属于间接性能假设，必须用相同TIME基线做heuristic on/off单变量A/B，并比较首次exact出现位置、RMP列数、dual、DSSR轮数和总exact时间后才能判断，本轮不改启发式配置。

### 32.11 高频 same-node pair 复用 A/B

本轮先测试减少DSSR轮数，不修改midpoint probe。旧same-node实现虽然会统计最近final ng-set中的成员次数，但候选只来自最新快照；当历史中只有一次困难exact时，所有候选频次均为1，排序实际退化为job/member编号。直接启用旧参数因此不是“复用高频pair”，而是从上一轮final memory中按编号追加最多25个成员。新增`ngDssrSameNodeWarmStartMinimumOccurrence`后，pair只有在同一node、相同active-cut集合最近至少两次final ng-set中都出现，才允许加入下一次基础K；仍只从最新快照取候选，并保留repeatability、每job上限、全局上限和困难轮数门槛。该操作只收紧ng relaxation，不排除elementary route，不改变master或certificate语义。

使用相同`n040-set02 family,m=2`、seed指纹`48fb3e...c1e35`、`TIME`队列、300秒时限和单节点上限，比较关闭复用、旧25-pair、频次至少2且预算25/10/5五组。由于probe按墙钟elapsed选择Tmid，各独立进程的前两次exact及返回列轨迹存在波动，结果应理解为机制筛选而非最终统计结论。第三次完整exact的结果如下：

| 方案 | 初始复用 | DSSR轮数 | exact时间 | forward时间 |
| --- | ---: | ---: | ---: | ---: |
| 关闭复用 | 0 | 22 | 71.853s | 38.794s |
| 旧语义 | 25个单次成员 | 19 | 83.065s | 52.671s |
| 高频25 | 25个至少出现2次的pair | 19 | 80.927s | 48.011s |
| 高频10 | 10个至少出现2次的pair | 17 | 57.980s | 32.803s |
| 高频5 | 5个至少出现2次的pair | 22 | 78.919s | 44.567s |

结论不是“memory越多轮数越少就越快”。25-pair确实把轮数降到19，但更大的初始memory削弱dominance，forward耗时超过关闭复用；5-pair又不足以提前阻断主要稳定重复结构，轮数没有下降。10-pair在该困难实例上取得平衡：第三次exact相对关闭复用少5轮、快约19.3%，前三次完整exact合计由`253.108s`降至`229.149s`，约快9.5%；300秒内第四次已推进17轮，而关闭复用只推进13轮。总exact墙钟都接近时限，不应用该总量判断无收益，真正差异是相同时限内完成的DSSR工作量。

代码保持`enableNgDssrSameNodeWarmStart=false`，未改变正式profile。默认关闭的实验参数改为窗口3、每job最多2、全局10、最少出现2次；正式使用前仍需在普通random/zero及另一组family实例做外部验证。25和5的负结果也说明暂不继承完整final memory，不再扩大初始K。下一项若继续优化，再单独处理probe的完整轮方向约束，不能与本机制混测。

### 32.12 10-pair复用后的剩余瓶颈

10-pair的约9.5%仅是前三次完整exact的局部改善，不能理解成主瓶颈已解决。300秒内两组仍各完成3次exact并进入第4次，exact累计都约285--287秒；受墙钟probe扰动和不同返回列轨迹影响，该单次A/B只能说明10优于5/25的机制方向，尚不能证明全树稳定提速。机制至少需要两份同节点final ng-set才生效，因此10-pair run前两次exact的`99.257+71.912=171.169s`完全没有得到复用收益，已经占前三次完整exact的74.7%。

第三次exact内部仍有`32.803s/57.950s=56.6%`用于正式forward labeling，`18.929s/57.950s=32.7%`用于midpoint probe，backward和join仅为`2.205s/3.508s`。前三次完整exact合计也保持同一结构：forward约58.7%，probe约32.4%。复用把第三次轮数从22降到17，但每轮平均forward时间由约`1.76s`升到`1.93s`，累计forward labels由每轮约3.98万升到4.36万；这正是更大初始memory削弱dominance的代价。因此warm start只是用“少跑几轮”抵消“每轮更贵”，没有改变每轮labeling算法。

probe仍是最明确的剩余冗余。10-pair第三次exact第2--15轮中，上一完整轮的forward/backward耗时比为`3.708--17.406`，adaptive seed已降到约`493--546`；浅probe却每轮都测试3个候选并把最终Tmid提高到约`701--754`，即固定落在`seed+208.35`附近。对应完整轮继续保持forward重`6.329--17.406`倍。第16轮不再反向提高seed，取`tMid=542`后完整比降到`1.805`；第17轮复用同一Tmid后进一步降到`1.397`。17轮probe共执行`413064`次pop，其中第2--15轮为`351483`次；若完整轮方向约束使这些轮只测试seed的首个10000-pop候选，可少约`211483`次probe pop，约占本次probe工作量51.2%。按当前耗时近似线性估计，直接可节省约9--10秒，尚未计入更合理Tmid对正式labeling的潜在影响；该数值是日志外推，不是已完成A/B。

DSSR轮数本身仍有明显信息利用问题。第三次exact共保留16批、每批1000条non-elementary witness用于更新；`16000`条路线中`15918`条在顺序处理时已被同批前面新增的pair阻断，只有82条路线产生83个动态pair，平均每轮约5.1条有效路线，远低于route limit 20。当前top-1000按reduced cost集中保留同质路线，说明每轮labeling产生了大量重复证据。下一项可在固定新增pair预算下增加按重复段/缺失pair签名的novelty辅助池，但必须同时观测轮数和每轮labels，不能简单把20条路线全部转成20个新增pair。

当前same-node“频率”也较粗。它统计pair是否存在于最近final ng-set，而final memory会保留一次pricing内所有历史更新；只要pair在多次调用最终都存在就计为高频，不能区分它阻断了多少witness、出现于多少不同DSSR轮或重复段。由于满足两次出现的候选超过25个，同频候选最终仍按job/member编号排序。这解释了10-pair只有中等收益，也给出更准确的后续方向：在一次困难exact内统计pair的跨轮出现次数、witness支持数和重复段多样性，使第二次exact即可复用5--10个真正高支持pair；final-set跨调用频率只作为稳定性门槛。该统计必须默认关闭并单独A/B，不能与probe修正同时上线。

由此，剩余耗时优先级为：第一，midpoint probe的直接重复工作及其反向覆盖完整轮feedback；第二，family memory增长后昂贵的forward labeling；第三，top-1000 witness同质化造成每轮只取得约5个独立更新。RMP、启发式、backward和join均不是当前主要优化对象。下一次应先单变量测试“上一完整轮forward重时probe不得提高Tmid、上一轮已平衡则直接复用”的方向约束，再决定是否实现witness级pair支持度。

### 32.13 历史 A/B：固定 Tmid、扩大 witness 池和全重复段更新

本轮继续固定`n040-set02 family,m=2`、同一seed、`TIME`队列、300秒根节点预算及same-node高频pair预算10，只改变一个DSSR机制。这里两个上限必须区分：`candidateLimit=1000`是每轮保留并按reduced cost检查的非基本负路线候选数；`effectiveLimit=20`是按当前ng-memory过滤后，每轮最多允许真正触发更新的witness路线数，不存在“2000”这一设置。

本节最初测试的是仅首轮执行midpoint probe、后续DSSR轮固定复用首轮选中的`Tmid`，并非最终采用的“保留完整轮adaptive反馈、只取消后续浅探”。初版在修改`Tmid`后没有同步重建half-domain，造成错误的两轮根节点闭合；这些诊断run全部判为无效。随后曾误判非probe路径遗漏backward sink，并在`initialize()`中提前补了一次；严谨复核发现`solveRelaxedRound()`本来就会在正式扩展前统一初始化sink，日志中的`initialize.done bwQueue=1`和`backwardSink.done bwQueue=2`反而证明该补丁造成重复初始化。该重复不构成错误certificate，但会污染性能计时，因此已撤销。真正必须保留的正确性修复是变更`Tmid`后调用`rebuildHalfDomainForCurrentMidpoint()`；下述固定Tmid数字仅保留为诊断历史，不用于确定当前正式中点规则。

撤销重复sink后的干净复跑中，仅首轮probe、`candidateLimit=1000`、minimum-segment在300秒内完成7次exact，轮数为`22/23/18/18/19/21/21`，共加入20条exact列；7次完整调用合计`271.317s`，平均38.76秒。将候选池单独扩大到3000后只完成4次exact，轮数为`20/21/17/18`，同样加入20条列；4次完整调用合计`247.526s`，随后第5次又运行40.387秒但未返回列。扩大池没有改变每轮最多20条有效witness的限制，只是将top-C保留阈值从第1000名放宽到第3000名。这样可能保留更多missing-pair证据，但也使join的非基本候选阈值剪枝更晚、更弱；本次最终列产出没有增加，不能认定为优化。

恢复`candidateLimit=1000`并改用`allSegments`后，6次完整exact的轮数为`6/8/7/8/8/9`，平均7.67，确实远低于minimum-segment的20.29；但每次调用一次加入约111--173个pair，ng-memory快速膨胀，单轮dominance明显变弱。6次完整exact合计`272.607s`、平均45.43秒，只加入19条列，随后第7次运行11.160秒仍未返回列。它把“多轮、每轮较小”改成“少轮、每轮很大”，最终没有提高300秒内的列产出，也未闭合root。因此all-segments只证明轮数可压缩，不是当前有效加速方案。

本节的阶段结论只保留两点：扩大池到3000没有增加列产出；all-segments虽将DSSR轮数降低约62%，但在该n40固定Tmid单例中被单轮状态膨胀抵消。固定复用首轮`Tmid`不再作为候选方案，正式判断转入下一节的adaptive-direct多实例测试。

### 32.14 adaptive-direct实验语义及 n30 多实例矩阵

本节adaptive-direct实验语义为：同一次exact的第一轮仍从default seed执行浅层midpoint probe；从第二轮开始，先用上一完整DSSR轮的forward/backward正式扩展耗时判断失衡，再从重侧存活label的split-time分位数计算adaptive `Tmid`。后续轮直接采用该adaptive值并重建half-domain，不再运行额外浅层probe。若上一轮不失衡或没有有效分位数，则复用上一完整轮`Tmid`。因此被取消的只有浅层候选测试，完整轮反馈及其adaptive移动均保留；该语义仅用于A/B，不等于正式默认方案。

代码路径重新逐项核对如下。`dssrFeedbackProbeSeed()`在`initializeSearchState()`清空上一轮搜索状态前读取上一完整轮的active labels；只有`roundCompleted=true`才由`rememberDssrRoundMidpointFeedback()`写入可复用耗时和`Tmid`，时限中断轮不会污染下一轮。direct分支随后清除probe复用标记，将probe耗时置为`NaN`、候选数置0，按新`Tmid`调用`rebuildHalfDomainForCurrentMidpoint()`，再由统一初始化路径创建一次forward source；`solveRelaxedRound()`仍只在`midpointProbeSearchStateReady=false`时创建一次backward sink。由于direct轮不把`NaN` probe时间加入正式F/B耗时，下一轮adaptive反馈只来自完整正式扩展。未发现第二个sink、重复正式labeling或不完整轮反馈写回。

为避免继续依赖单个n40困难实例，本轮从正式n40 set01--03的family/random配对数据截取前30个任务及对应setup子矩阵，保持任务、setup和setup-cost数值，统一`m=2`、固定seed、root单节点和120秒上限，共运行21组。每个实例先用同一seed列快照，再比较adaptive-direct下的`minimum/1000`、`minimum/3000`和`allSegments/1000`；另对3个family实例增加“每轮继续浅探、minimum/1000”基线。该矩阵统一开启实验性的same-node高频pair复用，窗口3、每job 2、全局10、至少出现2次，使witness策略和probe对照保持同一条件。全部run正常结束，无超时和异常；同一实例的各配置得到相同LB、UB和gap，incumbent可行性及目标重算一致性均为true。汇总文件为`test-results/bpc/diagnostic-n030-family-witness-matrix-20260828.csv`。

3个family实例中，每轮继续浅探的平均wall/exact为`34.766/29.551s`，adaptive-direct minimum/1000降为`26.210/21.877s`，分别下降`24.6%/26.0%`；后续轮probe候选次数由246降为0。首轮probe仍保留，因此adaptive-direct仍有合计`17.324s` probe时间。该结果只能说明“取消后续浅探”在这些family实例上有正信号，不能据此改成全局默认。

由于正式profile默认关闭same-node warm start，又补做3个family实例的关闭复用配对。adaptive-direct的平均wall/exact为`29.47/25.04s`，每轮继续浅探为`50.31/45.38s`，分别下降约`41.4%/44.8%`，后续轮probe候选次数由459降为0；两组总DSSR轮数为231/227，几乎相同，说明主要节省的是浅探本身及其错误中点选择，不是靠减少DSSR轮数。分实例看，set01和set03基本持平，set02的exact由`80.80s`降至`18.46s`，因此不能声称每个实例都有同幅度提速，但可以确认正式默认条件下没有总体退化，收益也不依赖pair复用。6组均根节点闭合并保持相同LB/UB与验证结果。

候选池不应扩大。family下minimum/3000与minimum/1000的平均wall几乎相同（`26.171/26.210s`），exact也几乎相同（`21.958/21.877s`），但seen witness由`901053`增至`1903426`、stored由`167110`增至`472508`，分别约为`2.11/2.83`倍；random下minimum/3000也由`6.088/0.705s`恶化到`7.746/1.030s`。扩大池既没有减少总轮数，也显著放松top-1000 join阈值剪枝，因此全局继续保持1000。

`allSegments/1000`在family上有真实信号：3例平均wall/exact为`18.922/15.117s`，相对minimum/1000下降`27.8%/30.9%`，总DSSR轮数由183降至99，seen witness由901053降至423186；set02、set03明显获益，set01则小幅变慢。random实例本身exact很轻，minimum/1000平均wall/exact为`6.088/0.705s`，优于allSegments的`7.128/0.918s`。其机制也符合预期：allSegments一次补齐一条witness的全部连续重复段，能更快打断family内大量可替代重复环，但会更快扩大memory、削弱dominance；普通random没有足够多轮数可节省，主要承担memory代价。

因此当前不把allSegments改成全局正式默认，也不按已知`setupType`手工为family/random配置不同算法，否则正式配对比较会混入求解器调参差异。保守统一配置仍为`minimumNewPairsSegment + candidate1000`。若目标是单独构造family优化profile，现有3例支持`allSegments + 1000`继续做n40/n50和全树验证；在没有更大规模验证或结构自适应触发规则前，不作为统一最佳配置。

### 32.15 为什么不能全局关闭probe，以及应使用什么动态信号

进一步逐轮拆解关闭same-node warm start的3组n30 family对照后，原“probe时间都是冗余”的说法不准确。probe在一个候选`Tmid`上生成的label状态会被正式labeling复用；若首个候选直接acceptable、rank0或继续在同一状态完成，则这部分工作不是重算。真正明确的额外工作是改变`Tmid`后丢弃旧候选状态。每轮probe基线的196个后续DSSR轮共测试459个候选，其中82轮测试了多个候选，明确丢弃263个中间候选状态。因此需要控制的是多候选walk/bracket，而不是简单关闭全部后续probe。

adaptive-direct同样存在结构性风险。201个后续轮中有49轮根据完整轮失衡移动`Tmid`。当相对有效时间域的位移小于5%时，完整F/B耗时比均值约2.20；位移为5%--10%时均值约1.72。位移达到10%--20%或20%以上时，均值分别升到约110和416，最大值超过1200。set03曾从约297直接移动到865，下一完整轮F/B比达到107.6；其他调用还出现700--1200级过冲。继续probe把最终位移压在约8.64%以内，虽然付出了候选游走代价，却避免了这类直接过冲。因此现有数据同时否定“每轮都完整probe一定好”和“第二轮以后全部不probe一定好”。

开关也不应按输入标签`family/random`写死。同一family set01在关闭pair warm start时adaptive-direct略慢，开启后又略快；set03关闭复用时近似持平、开启后明显更快。决定行为的是当前dual、ng-memory和时间域造成的在线label负载，不是静态setup类型。正式profile因此恢复`2026-08-28-v3`和后续轮继续probe，adaptive-direct仅保留为实验分支。

下一步应测试统一的动态规则，而不是实例专用参数。第一轮仍完整probe。后续轮先根据上一完整轮计算adaptive seed：若无需移动，或建议位移不超过有效时间域的5%--10%，直接复用/采用该点，取消候选游走；若建议位移更大、方向相对前两轮反转，或本轮ng-memory新增pair很多，则必须进行浅层验证，但最多测试1--2个候选，并限制单轮`Tmid`位移，不能继续当前4--6候选的长walk/bracket。阈值5%还是10%、pair增长门槛和两候选规则必须通过n30/n40的逐轮A/B确定，本轮不写入代码。

witness策略也采用相同原则。`candidateLimit=1000`继续作为统一基础池，3000已被否定。`allSegments`不按family标签打开，而应在minimum模式连续多轮后，根据在线指标触发：top-1000连续饱和、already-blocked比例极高、每轮有效更新路线明显低于20，并且待加入的全部missing pairs受一个小pair预算约束。random通常1--2轮即返回elementary列，不会触发；family式同质witness才会自然进入加强阶段。该hybrid规则尚未实现，当前正式update mode继续保持minimum。

### 32.16 n30 family当前瓶颈与time-indexed完整对照

本轮先用正式ng-DSSR配置（后续轮继续probe、minimum/1000、same-node warm start关闭）汇总3个n30 family实例。31次exact共执行227轮DSSR，平均7.32轮；`1113409`条non-elementary witness被发现，`213331`条进入top-1000存储，`188373`条进入更新检查，其中`186993`条已被同轮前序pair阻断，只有1380条路线实际更新，平均每轮约6.1条，远低于有效路线预算20。该现象说明DSSR更新循环本身不是CPU热点，真正代价是每轮取得的独立阻断信息太少，迫使完整labeling重复227轮。因此allSegments/novelty的评价目标应是减少后续轮数和总labels，而不是只缩短pair插入代码。

按现有计时口径，3例exact合计136.13秒，其中probe记账114.93秒、join 9.51秒、completion-bound 2.89秒。probe数字包含首个候选上可被正式labeling复用的搜索，不能全部当冗余；明确可删除的是多候选切换后丢弃的状态。adaptive-direct对照将同一工作重新记到正式forward/backward，3例合计forward/backward约28.05/16.61秒，说明在减少候选游走以后，下一层瓶颈会转为memory增长后的正式label扩展与dominance。heuristic pricing合计6.37秒、master LP 2.21秒、time-indexed预处理/辅助定价2.74秒，当前均不是主要优化对象；join约占一成，可排在midpoint、DSSR信息利用和label扩展之后。

随后对相同3个实例、相同seed使用正式no-cut time-indexed profile做300秒完整求解。结果如下：

| 实例 | root LB | root gap | root列数 | 300秒best bound | 最终gap | 节点 | 生成列 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| set01 | 52566.300 | 4.0481% | 16442 | 53387.042 | 2.5499% | 226 | 778590 |
| set02 | 39473.092 | 5.7246% | 18156 | 40556.002 | 3.1383% | 162 | 885084 |
| set03 | 36471.095 | 6.9472% | 12350 | 37320.971 | 4.7789% | 170 | 705058 |

三个time-indexed根CG本身都很快：55--72次pricing，pricing约0.92--0.97秒、master约0.46--0.65秒；但根解的全部正值列都是non-elementary pseudo-schedule，elementary正值列为0。由此形成4.05%--6.95%的弱root gap并进入大树。相同实例的ng-DSSR分别在`19.95/86.72/44.26`秒根节点闭合、gap为0。因此time-indexed在这批family实例上的问题不是单次图pricing慢，而是visit-count relaxation质量差。

time-indexed三例900秒合计中，strong-trial RMP求解464.17秒、对应模型重建142.90秒；repair slack求解/重建94.43秒，`FindFeasible`定价76.53秒。当前`Tree.solveStrongBranchingRmpTrial()`仍为每个candidate side新建`LP`、construct并close，三例共发生22093次strong-trial RMP，没有父节点级reusable trial workspace。正常time-indexed pricing合计90.72秒。也就是说进入树后约八成以上时间由20候选乘左右两侧的strong trial及其repair驱动，海量列是弱松弛和大树的后果。

据此分开排序。对ng-DSSR，当前先不再设计复杂的动态witness策略；统一正式配置继续使用`minimumNewPairsSegment + candidate1000`，`allSegments + 1000`仅作为family专项固定A/B。midpoint保持当前正式口径，即每次DSSR更新后的下一轮先利用上一完整轮反馈生成adaptive seed，再继续浅层probe；后续若优化，只处理多候选切换时丢弃的搜索状态。对纯time-indexed，首先应承认其family松弛弱；若仍要优化该求解器，当前只保留父节点级reusable strong-trial LP这一项明确的实现冗余，不再把动态strong candidate预算和列池控制列为当前方案。这一工程优化只能降低树上单位节点成本，不能修复根gap；当前更合理的用途仍是作为ng-DSSR的root preprocessing和窗口/arc fixing辅助，而不是family实例的独立主求解器。

### 32.17 DSSR轮间probe、pair warm-start与原文strong branching口径澄清

当前正式配置`bidirectionalMidpointProbeAfterFirstDssrRound=true`。因此一轮relaxed pricing没有返回elementary负列、并用non-elementary witness更新ng-set后，下一轮仍会做probe：先由上一完整轮forward/backward耗时形成adaptive seed，再从该seed运行浅层probe。最终选中候选上已经生成的label状态会被正式labeling复用，真正可确认的冗余只是probe切换到其他候选后被丢弃的中间状态。实验开关设为false时才是“首轮probe，后续轮只采用adaptive midpoint而不再浅探”；该变体没有进入正式profile。

DSSR更新不再继续设计hybrid或novelty触发器。现有证据支持两个简单固定口径：统一random/family正式比较继续使用`minimumNewPairsSegment + candidate1000`；若专门研究family实例，可固定使用`allSegments + candidate1000`做独立profile。n30 family三例中allSegments相对minimum使exact平均下降30.9%、DSSR轮数由183降到99，但set01小幅变慢，且random三例minimum仍更快，因此不能把allSegments写成统一默认。candidate3000已经确认只扩大存储与join阈值，未带来稳定收益。

same-node warm-start只复用同一node、相同active-cut集合下最近若干次正式exact的final ng-set，不跨node，也不继承完整final memory。当前实验参数为最近3个snapshot、pair至少在其中2次出现、每个job最多2个、全局最多10个，并且上一exact至少执行3轮DSSR才触发；这些pair只追加到本次基础nearest-K seed，之后仍执行正常DSSR。n40困难例中全局10个高频pair使前三次exact合计由253.108秒降到229.149秒，约9.5%，但25个pair会因削弱dominance而变慢，5个又不足以减少轮数；n30尚无严格隔离的稳定收益。因此它只保留为默认关闭的family实验项，不能当成已证实的统一加速。

用户所称`On the exact`指Bulhoes等人的并行机调度BCP，而不是RouteOpt或旧Java VRP。原文使用两阶段strong branching：phase 1最多测试50个候选，预计当前分支子树较小时允许减少；非root时一半候选按branching history/pseudo-cost选出，其余候选在job-machine assignment和job-job immediate-precedence两类中按接近0.5选择；每个候选左右支只加入branch row并重解restricted master，不生成新列；按两侧LB增量乘积选3个进入phase 2，再用heuristic column generation试算，最终仍按product rule选择。原文依据见Bulhoes et al. (2020), Section 6：<https://www.math.u-bordeaux.fr/~rsadykov/papers/Bulhoes_etall_LOGIS18.pdf>。

当前TWET no-cut time-indexed正式配置并未严格复现该控制：phase 1固定截取20个候选，候选来源也没有按原文的“半数pseudo-cost历史 + 两类变量均分”组织；由于当前没有no-cut graph-native heuristic trial，time-indexed路径在phase 1后直接选最优，phase 2为0。这里不再新增“按深度、score和trial成本动态降低20候选”或树上列池阈值策略。若以后要严格对齐原文，应单独实现原文的候选构成、最多50/取3以及graph-native heuristic phase 2，而不是在当前20候选上继续叠加一套未经验证的控制。

当前唯一证据充分的重复计算是strong-trial模型生命周期。`Tree.solveStrongBranchingRmpTrial()`对每个candidate side都新建`LP`、重新建立变量、coverage/machine/branch/cut rows，求解后立即关闭；n30 family三例共发生22093次，模型重建142.90秒。Bulhoes原文的操作表述是临时加入branching constraint并resolve RMP；现代RouteOpt的对应LP testing实现也在同一个node solver中加入一条row、求左支、反转row求右支、删除row。旧Java VRP的`BranchD`同样在一个父LP中把branch row从`[0,0]`改成`[1,1]`。因此父节点级trial workspace与参考实现方向一致，而且不要求改变候选数量、评分或列池策略。

该复用不能简单改成“所有candidate共用父RMP后只换一条row”。当前right branch还包含branch-implied禁弧，不同side的seed列集也可能不同；为保持现有trial分数，workspace必须对不属于当前side精确seed的变量临时设UB为0，并完整恢复branch row、变量UB、objective和basis。若初始trial需要artificial-slack repair或补入branch-specific columns，第一版应回退现有独立LP路径，避免复杂回滚改变repair语义。这样只消除普通phase-1 RMP的重复建模，仍保留Bulhoes原文式候选控制和当前正确性边界。142.90秒是模型重建的理论上限，464.17秒CPLEX trial solve和repair/FindFeasible并不会因删除建模直接消失。

### 32.18 父节点级reusable strong-trial LP实测：不保留

本轮只测试父节点级reusable strong-trial LP，候选数、评分、repair、pricing和其它配置全部不动。实例固定为`n030-set01 family,m=2`、正式no-cut time-indexed profile、同一seed和单线程CPLEX，并先限制为3个节点。旧路径120次phase-1 trial合计`9.104s`，其中模型重建`2.800s`、CPLEX solve`6.212s`；最终处理3个节点，bound为`52653.842857`，生成19277列。

第一版严格保持每个side现有lightweight seed：父节点只建一个union model，不属于当前side seed的变量临时设UB为0，右支继续使用branch-implied penalty；首次RMP若不可行或penalty列取正值，立即回退原独立LP repair。三个节点选中的分支arc及左右bound与旧路径一致到数值误差，最终bound、节点数和列数完全一致，因此比较的是同一分支轨迹。但120次reusable RMP的solve增至`17.256s`，加上2次repair fallback后的strong RMP总计`17.477s`，相对旧路径`9.104s`慢约92%；端到端节点前缀由`15.236s`增至`29.051s`。模型重建虽被消除，几千个变量UB和目标系数的反复切换破坏了热启动，union model也始终大于多数side的精确seed。

第二版按RouteOpt式固定父列集，不再切换UB，只增删一条branch row并仅修改真正的penalty系数；任何child seed含父RMP外列或需要repair时仍回退旧路径。该版本更差：根节点父RMP有16442列，而各side旧模型通常只有约2800--6000列；120次reusable RMP的CPLEX solve达到`29.446s`，工作区一次性建模仅`0.140s`，3节点端到端前缀为`39.254s`。这证明当前问题不在Java建模接口本身，而在复用后必须让每次trial解一个远大于side-filtered RMP的模型。

因此撤回第32.16--32.17节“该项应保留为明确实现优化”的暂定判断。RouteOpt能够复用，是因为LP testing在同一个有限列集上只改变一条分支行；当前TWET strong trial先按child域形成显著不同且更小的lightweight seed，两者前提不相同。旧计时中重建只占strong RMP时间约30.8%，即使零成本消除，理论收益也有限；一旦复用使CPLEX solve增加，净收益立即转负。实验代码和配置开关已全部撤回，正式流程保持原样。除非以后先改变trial列集语义或CPLEX能够直接复制父basis到独立side模型，否则不再推进父节点级reusable workspace；这两项都会超出“只消除冗余、不改流程”的当前边界。

### 32.19 n30 family：ng-DSSR、time-indexed无cut与SRI对照

本轮继续使用第32.16节相同的3个`n=30,m=2` family实例、相同固定seed、CPLEX单线程和300秒时限。ng-DSSR采用正式`2026-08-28-v3` profile；time-indexed无cut沿用第32.16节结果；新增的加cut组使用正式`TIME_INDEXED_SRI` profile，即`TimeIndexedGraphRank1CutPricing`、arc-memory SRI、最多8轮cut-loop设置和cut-loop arc fixing。没有修改算法代码、候选控制或其它配置。

| 实例 | 方法 | 状态 | 总时间/s | 有效best bound | 最终gap | 节点 | 列池/生成列 | 根active cuts |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| set01 | ng-DSSR | 根节点最优 | 19.947 | 54784.000 | 0 | 1 | 7394 / 23742 | 0 |
| set01 | time-indexed无cut | 300秒未解完 | 300.054 | 53387.042 | 2.5499% | 226 | 712908 / 778590 | 0 |
| set01 | time-indexed+SRI | 300秒根未闭合 | 300.070 | 不可用 | 不可用 | 1 | 28584 / 28554 | 414 |
| set02 | ng-DSSR | 根节点最优 | 86.716 | 41870.000 | 0 | 1 | 6784 / 24856 | 0 |
| set02 | time-indexed无cut | 300秒未解完 | 300.037 | 40556.002 | 3.1383% | 162 | 794037 / 885084 | 0 |
| set02 | time-indexed+SRI | 300秒根未闭合 | 300.062 | 不可用 | 不可用 | 1 | 33481 / 33444 | 597 |
| set03 | ng-DSSR | 根节点最优 | 44.257 | 39194.000 | 0 | 1 | 4885 / 17019 | 0 |
| set03 | time-indexed无cut | 300秒未解完 | 300.109 | 37320.971 | 4.7789% | 170 | 658007 / 705058 | 0 |
| set03 | time-indexed+SRI | 300秒根未闭合 | 300.119 | 不可用 | 不可用 | 1 | 30205 / 30146 | 619 |

三例ng-DSSR平均`50.306s`并全部在根节点证明最优；两组time-indexed平均都达到约300秒。无cut版本的根CG只需`1.69--1.97s`，但留下`4.05%--6.95%`的弱根gap，随后在大树中仍保留`2.55%--4.78%`gap。SRI版本则走向另一个极端：三例在300秒内均未完成根cut loop，因而结果中的空root bound和空gap表示尚未重新取得完整reduced-cost certificate，不能把中途RMP目标当成有效下界。

SRI三例的rank-1 pricing分别耗时`248.358/193.104/204.373s`，master LP分别耗时`47.009/99.545/86.851s`，执行`491/663/675`次pricing。每加入一批SRI后，cut dual会诱导大量新的负非基本列，RMP又从无cut闭合时的约`1.24万--1.82万`列扩到`2.86万--3.35万`列；与此同时每个label还需携带数十个active cut residual state。说明当前SRI没有直接堵住family下“组内重复访问按visit count填覆盖”的核心松弛，却同时放大了定价状态和RMP。因此在这3个n30 family实例上，正式ng-DSSR明显优于time-indexed；给time-indexed打开当前SRI不仅没有改善300秒结果，反而使求解无法离开根节点，暂不应作为family默认配置。
