# F<m 场景下 Cluster 后回退 CutSet 的本地对照

## 1. 测试目的与口径

此前 TWET 的 CutSet 完整树实验只覆盖旧 `data/40-2/wet040_001_2m.dat`。该四字段旧算例与当时 loader 匹配，结果对旧模型有效，但不是当前正式 family、`F<m` 场景。旧实验中，Arc baseline 为 `58.092s/10节点`，CutSet+Arc 为 `128.857s/27节点`，CutSet 明显变慢；其动态 separator 产生大量同值、嵌套集合，不能由此否定 CutSet 在 family 长树中的作用。

本次改用 `FormalExperimentRunner` 和正式五字段数据，固定 `n040-set02`、`F=3`、`m=4`，比较：

1. `Cluster -> Arc`；
2. `Cluster -> CutSet -> Arc`。

两侧均使用 NG-DSSR 正式 profile `2026-09-12-v8`、同一实例 seed、setup-only MST Cluster、`clusterMstTheta=0.5`、严格类型逐层回退、每节点 completion-bound arc fixing，单次时限 `1200s`，CPLEX 单线程。只显式改变 `enableCutSetBranching`。构建基于提交 `899db7e1d268c303fb0c60b892826df5e564e722`，实验 jar SHA-256 为 `FE2E4FB15BF819C11F5ABCC23D0AD8733CF7343456881CFFDCF0E84624E6F101`。

## 2. 结果

| 窗口 | Cluster -> Arc | Cluster -> CutSet -> Arc | 实际分支选择 | 判断 |
| --- | --- | --- | --- | --- |
| wide | `58.078s`，26节点，474轮pricing | `54.487s`，21节点，432轮pricing | 基线 Cluster 4/Arc 9；CutSet侧 Cluster 4/CutSet 7 | 节点减少19.2%，时间减少6.2%，时间幅度较小 |
| zero | `263.876s`，334节点，4412轮pricing | `138.944s`，151节点，2191轮pricing | 基线 Cluster 32/Arc 155；CutSet侧 Cluster 31/CutSet 47 | 节点减少54.8%，时间减少47.3%，明显正收益 |
| narrow | `1200s`保护内未闭合，已取169节点，Cluster 24/Arc 88 | `327.749s`，142节点，2134轮pricing，闭合最优 | CutSet侧 Cluster 23/CutSet 54 | CutSet侧闭合；基线在节点148和169反复进入重Phase-I repair，最后观测gap约0.2173% |

三组共同验证了目标值口径。wide 两侧均为 `5401`，zero 两侧均为 `33858`；narrow 的 CutSet 侧证明 `objective=bound=17685`，基线最后 incumbent 也为 `17685`，但在 repair pricing 内触发时限，没有写出完整 BPC summary，因此不把其最后节点 bound 当作正式最终证书。

## 3. 时间结构

CutSet aggregate strong trial 本身更贵。zero 中普通 strong RMP 由基线 `4.046s/178次` 增至 `42.220s/2052次`；narrow CutSet 侧普通 strong RMP 为 `47.501s/2298次`。但在两个较难实例上，树缩小抵消并超过了这部分开销：zero 的 pricing rounds 由 `4412` 降到 `2191`，正式 NG-DSSR 时间由 `52.137s` 降到 `22.510s`，repair NG-DSSR 由 `13.018s` 降到 `2.097s`。

narrow 仍暴露 aggregate 分支的修复风险。CutSet 侧虽然总时间只有 `327.749s`，其中 repair NG-DSSR 已占 `203.912s`；基线则在两个深节点出现约 `360.391s` 和 `94.602s` 的 repair exact 调用并最终超时。因此收益主要来自减少进入坏子树和坏修复侧的机会，不是 CutSet 单节点计算更便宜。

进一步拆分narrow基线后，1200秒中的 `1064.640s` 用于 `GCNGBBStyleNgDssrPricing[FindFeasible]`，占88.7%；普通NG-DSSR exact只有 `24.456s`，普通启发式为 `28.231s`，Phase-I启发式修复仅 `3.294s`。耗时几乎集中在五次exact repair：节点148的 `370.540s/360.391s`，节点169的 `194.900s/94.602s/42.980s`。节点148三次exact repair合计 `730.980s`，节点169合计 `332.480s`，所以处理节点数看起来只比CutSet侧多27个，却仍耗尽时限。

这不是Phase-I启发式没有工作。节点148的启发式修复13次中10次成功，共加入1785列；exact又先加入13列，随后仍有人工变量为正。对最终选中的`arc(37,7)`右侧，完整exact证明不存在更多Phase-I负列，故该侧在生成1798列后被证明不可行；另一个strong candidate侧也触发了同类完整证明。两次无列certificate分别执行55和53轮DSSR，join单项耗时约 `311.730s/300.420s`。节点169的启发式同样加入1429列，exact又加入12列，但下一轮在总时限处退出。由此应表述为：启发式能快速补大量容易找到的列，却既不能保证找到清除最后人工slack所需的特殊elementary列，也不能证明分支侧确实不可行；后一项必须交给exact pricing，而本例的完整DSSR certificate极重。

CutSet侧也没有消除这一类开销，其节点107的两次无列exact repair仍耗 `126.940s/66.890s`，总repair NG-DSSR为 `203.912s`。它的收益是改变树和strong candidates，减少了遇到更严重坏repair侧的次数与强度，而不是修复过程本身更快。

全部101次repair exact累计执行503轮DSSR，但时间高度集中：节点148三次调用共109轮，其中两次完整无列证明分别为53和55轮；节点169三次调用共94轮，分别为28、31和35轮，最后一次在第35轮触发总时限。五次重调用合计202轮，却占全部repair exact时间的99.9%以上。

重repair的形成机制与普通真实目标定价不同。纯Phase-I把所有合法列的目标系数、setup cost和ET penalty置为0，只保留覆盖、机器数和分支行dual；同时不能使用依赖真实目标的dual-profitable window。于是重复访问同一任务可以反复取得覆盖dual，却不再支付setup和ET目标代价，ng松弛会连续产生大量负的非elementary witness。对于真正不可行的strong side，前几十轮始终存在这种松弛负路线，但不存在可清除最后人工slack的elementary负列，DSSR只能逐轮加入memory，直到松弛也不再产生负路线。memory增大又削弱dominance，最终造成大量label和join组合：节点148两次证明各检查约217万和231万条non-elementary witness；节点169的31轮调用保留约21.3万label并检查约5.24亿个join访问。因此这里不是RMP或启发式本身慢，而是“零真实成本Phase-I + 不可行分支侧 + DSSR精确无列证明”共同构成了NG-DSSR的最坏情形。

## 4. 当前结论

旧 random-like `40-2` 的负结果仍成立，但不能迁移到正式 `F<m` family。当前三个同任务集对照表明：Cluster aggregate 已整数后，动态 CutSet 作为下一层 fallback 在zero实例中明显缩树，在wide实例中只有小幅收益；narrow结果还混入了重Phase-I repair路径差异。现有数据证明了部分实例上的性能改善，但没有证明改善来自哪一种集合结构。

证据尚不足以把 CutSet 设为所有算例默认分支。当前 separator 仍会产生嵌套集合，aggregate strong trial 和 Phase-I repair 也明显更贵。下一步若继续，应限定在静态 family partition 清晰、`F<m`、Cluster 后仍形成长树的子集，补不同 set/规模的同 seed 对照；不应据这三个实例推广到 random、`F>m` 或根 pricing 主导的算例。本次未修改候选生成或正式 profile，也未操作远端任务。

## 5. random 无 Cluster 的长算例复核

为避免前一批 `n40` random 对照只有 `15--79s`、不足以观察长树收益，补充两个 `n50/m2/random/base/zero` 完整 BPC。两组均使用正式 profile `2026-09-12-v8`、同一实例和共享 ALNS seed、强分支及 root preprocessing，单次上限 `3600s`。Cluster 明确关闭；Arc 侧关闭 CutSet，CutSet 侧采用严格 `CutSet -> Arc` 回退。配置快照逐项核对后，除运行名、输出路径、`enableCutSetBranching` 及由此装配的 brancher 外没有差异。四次运行均闭合最优，目标和排程复核通过。

| 算例 | Arc | CutSet -> Arc | 总时间变化 | pricing轮数变化 |
| --- | --- | --- | --- | --- |
| `n50-set03/m2` | `157.593s`，14节点 | `266.929s`，24节点 | `+69.4%` | `1023 -> 1525` |
| `n50-set05/m2` | `790.366s`，32节点 | `1448.208s`，60节点 | `+83.2%` | `2651 -> 4468` |

恶化不只是 aggregate strong trial 更贵。set03 的 strong-trial RMP 由 `13.573s/400次` 增至 `43.405s/520次`，set05 由 `34.689s/1000次` 增至 `150.042s/1680次`；即使扣除这部分增量，总时间仍分别多约 `80s` 和 `542s`。真正主导差距的是树和定价调用一起增加：set03 的 NG-DSSR exact 由 `72.461s/197次` 增至 `124.797s/295次`，set05 由 `520.087s/531次` 增至 `897.072s/995次`。set05 的 master LP 也由 `75.627s` 增至 `231.532s`，是更多节点、更多列和更多 strong trials 的共同结果。

因此，当前动态 CutSet 不能作为 random 的默认分支。它在两个几分钟至二十余分钟的 random 长算例上都没有缩树，反而使节点约增加 `71%--88%`。后续第6至7节从分支的实际数学语义解释该现象，不再把它归因于尚未验证的“稳定集合边界”。原始日志和完整 CSV 位于 `.codex-tmp/cutset-random-large-20260912/`，汇总另存为 `docs/logs/data/20260912-cutset-random-large.csv`。

## 6. CutSet 的实际数学语义

此前把当前 CutSet 解释为“识别由一台机器完整服务的稳定集合”，证据不足，现予以更正。实现只从每个 singleton seed 出发，反复加入与当前集合双向 LP arc flow 最大的任务，保存进入流为分数的 prefix，再按进入流距半整数的距离排序。它没有检查 `S` 是否由一台机器服务、是否形成完整 family 块、是否在重优化后稳定，也没有在构造候选时比较 child bound gain。

令 `h_j` 为任务 `j` 在当前主问题解中的总覆盖流，`y_ij` 为有向相邻 Arc 流。CutSet 的进入流满足恒等式：

\[
z(S)=\sum_{j\in S}h_j-\sum_{i,j\in S}y_{ij}.
\]

当覆盖行取紧、即 `h_j=1` 时，`z(S)=|S|-内部Arc流`。在一条 elementary 排程上，`z(S)`计数的是该排程进入 `S` 的次数，也就是 `S` 在排程中形成的连续片段数；对分数主问题，它是这些片段数的加权和。只有额外证明每条机器路线最多连续进入 `S` 一次时，它才等于“触及 `S` 的机器数”。当前 separator 没有提供这一证明。

特别地，对二元集合 `S={i,j}` 且覆盖取紧：

\[
z(S)=2-(y_{ij}+y_{ji}).
\]

因此 `z(S)<=1` 与 `z(S)>=2` 实际是“`i,j`沿任一方向相邻”与“两个方向都不相邻”的分支。它不决定 `i->j` 还是 `j->i`。对更大的小集合，分支固定的也只是内部连接数或连续片段数，不决定具体有向顺序。

## 7. random 负结果能够证明的核心原因

random set03 的13次 CutSet 中有7次集合规模不超过3；set05 的42次中有30次不超过3，21次恰好为二元集合。因此当前 random 实验中的 CutSet 多数不是大集合机器分配分支，而是“不区分方向的二元相邻分支”或小集合片段数分支。

TWET 的实际定价决策具有方向性：`i->j` 与 `j->i` 使用不同 setup，并改变任务完成时间和后缀 PWLF 成本。Arc 分支直接决定某条有向相邻关系；二元 CutSet 的一侧却仍同时允许两个方向，更大的小 CutSet 也不决定内部顺序。严格 `CutSet -> Arc` 又会在存在 CutSet 时直接返回，不让同一节点的 Arc 候选参加 strong trial 竞争。因此当前策略经常用一个更粗的“无向相邻/片段数”析取替代有向排序析取。

同根 strong trial 提供了直接证据。set03 中，最佳 Arc 两侧 gain 约为 `396/1806`，score约 `715015`；严格优先选中的 CutSet 虽然取值接近半整数，两侧 gain 只有约 `384/541`，score约 `207727`。CutSet 的弱侧与 Arc 接近，但另一侧没有 Arc 强制具体方向所产生的提升。候选接近半整数只保证分数值距离两侧相近，不保证两侧 bound gain 较大。

同时，大多数被选 CutSet 的当前值在 `1.5/2.5` 附近，`Q<=0`的直接禁弧优化不会触发；两个 child 主要只是增加 aggregate row，pricing 图中的有向 Arc 仍保留。后续仍需继续解决具体方向和顺序。结果与这一语义一致：set03 节点由 `14` 增至 `24`，set05 由 `32` 增至 `60`；NG-DSSR exact 调用分别由 `197` 增至 `295`、由 `531` 增至 `995`。这不需要假设“改变顺序成本相近”或“存在大量近成本完整排程”。

family 正结果不能据此解释为当前 CutSet 已正确找到完整 family 块。被选集合中完全位于一个真实 family 内的比例只有 `57.1%--70.2%`；这些族内集合的中位规模约为 `5.5--6`，不是完整的约13任务 family。现有 clean 证据只有 zero 场景中 `Cluster -> CutSet -> Arc` 将节点从 `334` 降到 `151`；wide 收益很小，narrow 又受重 Phase-I repair 路径影响。故目前只能说 CutSet 在 Cluster 后的某些 family 树上可能改善搜索路径，尚不能证明其改善机制是“识别一台机器服务的稳定块”。

当前最稳妥的结论是：random 上的负结果来自当前小集合 CutSet 语义过粗且严格优先排除了有向 Arc 竞争；family 上为何某些实例获益仍需同节点 strong gain 和集合连续性统计才能进一步确认，不能继续用未经验证的成本替代故事解释。
