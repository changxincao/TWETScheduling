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

二元候选占多数不是偶然。当前生成器对每个singleton seed先加入双向support flow最大的一个任务，第一次计算进入流时集合必然正好包含两个任务；随后每增加一个任务保存一次分数prefix。候选首先按`z(S)`距最近半整数的距离排序，距离相同时明确优先集合更小者。对覆盖取紧的二元集合，该排序量由`z({i,j})=2-(y_ij+y_ji)`决定，等价于寻找双向相邻总流量接近半整数的任务对。random的分数LP中这类任务对很多，而生成器没有“集合必须足够大”“必须具有机器块含义”或“预计child gain必须较高”的门槛，二元prefix因而系统性进入候选池前部。

TWET 的实际定价决策具有方向性：`i->j` 与 `j->i` 使用不同 setup，并改变任务完成时间和后缀 PWLF 成本。Arc 分支直接决定某条有向相邻关系；二元 CutSet 的一侧却仍同时允许两个方向，更大的小 CutSet 也不决定内部顺序。严格 `CutSet -> Arc` 又会在存在 CutSet 时直接返回，不让同一节点的 Arc 候选参加 strong trial 竞争。因此当前策略经常用一个更粗的“无向相邻/片段数”析取替代有向排序析取。

这一二元分支不能消除有向Arc分数性的原因是确定的。例如`y_ij=y_ji=0.5`时，两条有向Arc都为分数，但二元CutSet只看到`y_ij+y_ji=1`，已经整数，完全不会分支；若二者之和为`0.5`，进入“必须相邻”一侧后只要求总和变为1，仍允许两个方向继续按分数混合。它解决的是“相邻还是不相邻”，不是“具体采用哪个方向”。因此它可能在某些具有明确成对结构的问题上有用，但不能替代TWET最终必须解决的有向排序决策。

同根 strong trial 提供了直接证据。set03 中，最佳 Arc 两侧 gain 约为 `396/1806`，score约 `715015`；严格优先选中的 CutSet 虽然取值接近半整数，两侧 gain 只有约 `384/541`，score约 `207727`。CutSet 的弱侧与 Arc 接近，但另一侧没有 Arc 强制具体方向所产生的提升。候选接近半整数只保证分数值距离两侧相近，不保证两侧 bound gain 较大。

同时，大多数被选 CutSet 的当前值在 `1.5/2.5` 附近，`Q<=0`的直接禁弧优化不会触发；两个 child 主要只是增加 aggregate row，pricing 图中的有向 Arc 仍保留。后续仍需继续解决具体方向和顺序。结果与这一语义一致：set03 节点由 `14` 增至 `24`，set05 由 `32` 增至 `60`；NG-DSSR exact 调用分别由 `197` 增至 `295`、由 `531` 增至 `995`。这不需要假设“改变顺序成本相近”或“存在大量近成本完整排程”。

family 正结果不能据此解释为当前 CutSet 已正确找到完整 family 块。被选集合中完全位于一个真实 family 内的比例只有 `57.1%--70.2%`；这些族内集合的中位规模约为 `5.5--6`，不是完整的约13任务 family。现有 clean 证据只有 zero 场景中 `Cluster -> CutSet -> Arc` 将节点从 `334` 降到 `151`；wide 收益很小，narrow 又受重 Phase-I repair 路径影响。故目前只能说 CutSet 在 Cluster 后的某些 family 树上可能改善搜索路径，尚不能证明其改善机制是“识别一台机器服务的稳定块”。

当前最稳妥的结论是：random 上的负结果来自当前小集合 CutSet 语义过粗且严格优先排除了有向 Arc 竞争；family 上为何某些实例获益仍需同节点 strong gain 和集合连续性统计才能进一步确认，不能继续用未经验证的成本替代故事解释。

## 8. family 为何没有同样退化成二元分支

二元偏置来自同一套生成器，但实际结果表明它在family上没有占据主导。wide、zero、narrow中二元CutSet分别只有`2/7`、`9/47`、`9/54`，规模不超过3的分别为`2/7`、`13/47`、`13/54`；random set05则为二元`21/42`、不超过3的`30/42`。family三组的总体中位规模为`15/8/7.5`，完全位于单个family内的候选中位规模约为`5.5--6`。因此family正结果不能套用random的“主要是无向二元分支”解释。

其生成机制可由进入流的增量恒等式描述。向`S`加入任务`v`时：

\[
z(S\cup\{v\})-z(S)=h_v-\sum_{u\in S}(y_{uv}+y_{vu}).
\]

生成器恰好选择右侧双向support affinity最大的`v`。覆盖取紧时`h_v\approx1`：若新任务与当前集合之间已有接近1的LP相邻流，加入它几乎不改变`z(S)`，原有半整数分数部分会沿更大的prefix保留下来；若affinity明显低于1，`z(S)`会大幅移动，较大prefix更容易离开半整数。family候选规模分布说明前一种现象在实际family LP support中经常发生，random的规模分布说明其半整数性更多停留在最早的二元、三元prefix。低setup块是否完整地导致了这一support差异，目前没有逐候选affinity日志，故这一步只能作为与现象一致的机制推断，不能写成已验证因果。

较大CutSet与二元CutSet的约束能力也不同。对一个规模约6至10的`S`，`z(S)<=1`与`z(S)>=2`区分的是这些任务在整数排程中形成一个连续片段还是至少两个片段；它虽然仍不决定片段内部方向，却同时约束许多可能的边界Arc。二元集合只决定一对任务相邻与否，无法聚合这种多任务分段选择。family zero中CutSet侧将节点`334 -> 151`，与这种较大集合分支可能一次排除更多残余组合相符；但wide只有`26 -> 21`，narrow受repair路径影响，所以当前只能认定“较大族内子集是一个有证据支持的候选解释”，不能宣称它已被证明为全部收益来源。要完成因果证明，还需记录同节点被选`S`的逐步affinity、`z`增量、连续片段统计及CutSet/Arc并列strong gain。

## 9. family 与 random 的完整对比解释

### 9.1 random为何退化为二元无向分支

生成器从每个singleton seed开始，先加入与seed双向LP Arc流量最大的任务，因此第一次计算进入流时集合必然是二元集合。候选首先按`z(S)`距最近半整数的距离排序，距离相同时明确优先集合更小者；它不要求集合足够大、不判断集合是否具有机器块含义，也不在预筛阶段比较child bound gain。

覆盖取紧时，对`S={i,j}`有：

\[
z(S)=2-(y_{ij}+y_{ji}).
\]

所以筛选`z(S)`接近半整数，实际等价于从许多任务对中寻找双向相邻总流量`y_ij+y_ji`接近半整数的pair。random分数LP中只要存在这种pair，生成器就会把它排到前部；这不能证明该pair构成有意义的路线块。

二元CutSet的`z(S)<=1`与`z(S)>=2`只区分`i,j`沿任一方向相邻和两个方向都不相邻，不决定`i->j`还是`j->i`。例如`y_ij=y_ji=0.5`时，两条有向Arc都为分数，但其和已经是1，二元CutSet完全看不到这一方向分数性；若其和为0.5，进入“必须相邻”一侧后也只要求总和变为1，仍允许两个方向分数混合。

普通Arc分支直接禁止或强制`i->j`。强制侧确定方向，并通过前驱/后继限制收紧pricing域。二元CutSet的“不相邻”一侧同时禁止两个方向，可能较强；但“必须相邻”一侧仍保留两个方向，通常明显弱。set03同根strong trial中，Arc两侧gain约为`396/1806`、score约`715015`；被选CutSet只有`384/541`、score约`207727`。当前严格`CutSet -> Arc`优先又会在存在CutSet时排除Arc候选，于是算法先决定是否相邻，后续仍要决定具体方向。random set03节点由`14`增至`24`，set05由`32`增至`60`，与这种额外分支层一致。

### 9.2 family为何形成更大的CutSet

同一生成器也会在family上产生二元候选，但实际被选结果明显不同。wide、zero、narrow中二元CutSet分别只有`2/7`、`9/47`、`9/54`，规模不超过3的分别为`2/7`、`13/47`、`13/54`；random set05则为二元`21/42`、不超过3的`30/42`。family三组总体中位规模为`15/8/7.5`，完全位于单个family内的候选中位规模约为`5.5--6`。因此family与random实际分的不是同一种对象。

向`S`加入任务`v`时，进入流满足：

\[
z(S\cup\{v\})-z(S)=h_v-\sum_{u\in S}(y_{uv}+y_{vu}).
\]

生成器选择右侧双向support affinity最大的`v`。覆盖取紧时`h_v\approx1`。若新任务与当前集合已有接近1的LP相邻流，加入它几乎不改变`z(S)`，原有半整数分数部分会沿规模更大的prefix保留下来；若affinity明显低于1，`z(S)`会大幅移动，较大prefix更容易离开半整数。family候选规模分布表明前一种现象在family LP support中经常出现；random的规模分布表明其半整数性更多停留在最早的二元、三元prefix。family低setup结构是否完整导致了这一support差异，目前没有逐候选affinity日志，所以该因果链仍是与数据一致的机制推断，而不是已经直接测量的结论。

### 9.3 大集合分支为何可能比二元分支有效

对规模6至10的集合`S`，`z(S)<=1`与`z(S)>=2`在整数排程中区分这些任务共同形成一个连续片段，还是被拆成至少两个片段。它仍不决定片段内部方向，但同时约束从集合外进入多个任务的全部边界Arc。禁止某一条普通Arc后，可以换另一条边界Arc而保持相同分段方式；CutSet约束全部边界Arc之和，单纯替换具体进入Arc不能改变“一段还是多段”的分支侧。

因此，random中的二元CutSet只处理一对任务是否相邻，仍留下方向分数性；family中的较大CutSet处理多任务子集的一段或多段组织，一次覆盖许多边界Arc。family实验还先执行Cluster，只有整个cluster相关流已无分数候选时才回退CutSet，因此这些CutSet针对的是Cluster之后残留的family内部子集分段；random实验则从根节点严格优先使用CutSet，两类实验的分支层次也不同。

### 9.4 最终结论和证据边界

family zero中`Cluster -> CutSet -> Arc`将节点由`334`降到`151`，是较大集合CutSet可能一次消除更多残余组合的clean正证据；wide只有`26 -> 21`，收益很小；narrow受到重Phase-I repair路径影响。因此不能写成“family上CutSet必然有效”，更不能声称当前CutSet已经识别由一台机器完整服务的family块。

当前可采用的机制表述为：random LP中的半整数候选主要停留在二元、三元prefix，当前CutSet因此退化为不能决定方向的无向相邻分支，并在严格优先下挡住更合适的有向Arc；family LP support能够让半整数进入流沿更大的族内prefix保留，实际选中的CutSet更多对应规模5至15的子集，分支这些任务形成一个连续片段还是多个片段，因而可能一次约束许多边界Arc。要把“family support集中导致大集合”及“大集合直接带来child gain”提升为完整因果证据，仍需补逐候选affinity、`z`增量、连续片段统计以及CutSet/Arc并列strong gain。

## 10. 进入流增量公式的逐步推导

先看一条整数机器序列`depot -> A -> B -> C -> D -> E -> sink`。若`S={B,C}`，只有`A->B`从集合外进入集合内，因此`z(S)=1`；`C->D`是从集合内出去，不计入`z(S)`。把`D`加入后，`S'={B,C,D}`，原来的`A->B`仍是唯一进入弧，`C->D`从原来的出弧变成内部弧，所以`z(S')`仍为1。若改为把不与`S`相邻的`E`加入，则除`A->B`外还新增`D->E`，进入次数由1变成2。若原集合是`S={B,D}`，进入弧为`A->B`和`C->D`，所以`z(S)=2`；加入夹在两段之间的`C`以后，`B,C,D`连成一个片段，只剩`A->B`，进入次数下降为1。

一般地，把集合外任务`v`加入`S`时发生两类变化。第一，所有原来从`v`进入`S`的弧`v->u`曾被计入`z(S)`，加入后变成内部弧，必须减去。第二，所有从新集合外进入`v`的弧成为新的进入弧。`v`的总进入流为`h_v`，其中从`S`进入`v`的部分加入后属于内部弧，所以真正新增的进入流是`h_v-sum_{u in S}y_uv`。两部分合并得到：

\[
z(S\cup\{v\})-z(S)
=\left(h_v-\sum_{u\in S}y_{uv}\right)-\sum_{u\in S}y_{vu}
=h_v-\sum_{u\in S}(y_{uv}+y_{vu}).
\]

在elementary列的分数主问题中，`h_v`是所有包含`v`的列权重之和，也等于进入`v`的总Arc流。覆盖行通常取紧时`h_v`约为1。后一项不是“弧条数”，而是当前LP中`v`与`S`相邻的总权重：约为0表示`v`基本独立于`S`，加入后进入次数约增加1；约为1表示`v`通常在`S`片段的一端，加入后进入次数基本不变；约为2表示`v`通常夹在两个`S`片段之间，加入后两段合并、进入次数约减少1；若为0.5，则表示只有约一半LP流把`v`接在`S`旁边，加入后进入流约增加0.5。

因此，如果当前`z(S)=1.5`，加入一个与`S`相邻流约为1的任务后仍约为1.5，半整数性可沿更大集合保留；加入一个相邻流约为0.5的任务后则变为约2.0，候选立即失去分数性。family中被选CutSet规模明显更大，说明实际贪婪扩张经常找到令相邻流接近整数的后续任务；random中候选大量停留在二元、三元，说明这种保持半整数性的扩张较少。这里的规模结果是实测，逐步相邻流尚未单独输出，后一句仍属于由公式和规模分布共同支持、但待直接日志确认的机制解释。

## 11. 文献来源、当前执行方式与通用性边界

CutSet Branching并不是[Silva、Uchoa和Subramanian（2025）](https://doi.org/10.1287/ijoc.2024.1036)提出的新方法。经典无向VRP中定义

\[
\omega_S=\frac{1}{2}\sum_{e\in\delta(S)}x_e.
\]

整数路线跨越割集的次数为偶数，因此`omega_S`应为整数。[Lysgaard、Letchford和Eglese（2004）](https://www.lancaster.ac.uk/staff/letchfoa/articles/2004-cvrp-exact.pdf)在CVRP中选择满足`2<x(delta(S))<4`、即`1<omega_S<2`且尽量靠近`1.5`的集合，分成`x(delta(S))=2`与`x(delta(S))>=4`两侧。他们用贪婪启发式产生若干候选，按`|x(delta(S))-3|/q(S)`预排序，再逐个试算左右子节点下界；若一侧可剪枝立即采用，否则比较两侧下界，并在连续候选不再改善后停止。CVRPSep后来提供了从分数edge flow寻找目标分数CutSet的例程，[Fukasawa等（2006）](https://doi.org/10.1007/s10107-005-0644-X)、Pecin等（2017）等BCP继续采用。2025年论文的新贡献是Cluster Branching；其CutSet基线仍调用CVRPSep，并明确指出该separator只按`omega_S`的分数性找集合，不识别cluster结构。

TWET使用有向机器序列，因此当前实现采用等价的进入流：

\[
z(S)=\sum_{i\notin S,j\in S}y_{ij}.
\]

对任意一条完整机器序列，系数就是进入`S`的次数，始终为整数；在通常的depot-to-depot路线表示中，它等于无向割流的一半。因此对当前分数解的`z(S)`做`floor/ceil`分支是有效的robust branching。分支行dual可直接分摊到全部进入Arc，不增加pricing资源或label状态；整体完备性仍由最终回退的有向Arc分支保证，CutSet本身不区分`i->j`与`j->i`，不能单独保证TWET有向排序完整性。

当前候选识别流程不是CVRPSep复现，而是自定义的有向support-flow贪婪适配。每个任务都作为singleton seed；每一步加入与当前集合双向LP相邻流`sum(y_uv+y_vu)`最大的任务；每扩张一次都计算`z(S)`，仅保存分数候选；去重后先按距离最近半整数排序，再按集合规模升序打破平局，最多保留40个。若启用严格类型优先，执行顺序是`Cluster -> CutSet -> Arc`：当前层存在分数Cluster候选就只测试Cluster；没有Cluster候选但存在CutSet时只测试CutSet；两者都没有才回退Arc。若关闭严格优先，三类候选进入共享预排序。正式最佳profile默认关闭CutSet，现有CutSet结果均属于显式开启的消融实验。进入strong branching后，当前最多测试前20个候选的左右restricted LP；正式profile的后续heuristic-CG阶段目前关闭。

该实现的分支约束是通用且正确的，但集合识别器还不能称为通用高质量separator。优点是只依赖当前LP Arc flow，不依赖已知family、空间坐标、容量或硬时间窗，能够用于NG-DSSR、TI和TI+SRI；最大support affinity在覆盖取紧时也等价于尽量减小下一步进入流，具有经典贪婪边界搜索的直接解释。局限是每个seed只生成一条单调增长链，没有add/remove/swap局部改进、min-cut搜索或多样性控制；候选大量嵌套；小集合平局优先造成二元偏置；内部affinity把两个方向相加；所有半整数层级都接受，而经典CVRP主要围绕`z(S)约1.5`的一条路线/多条路线析取；前40名只按分数性产生，可能在strong trial前淘汰真正child gain更高的集合。严格CutSet优先又比2025年论文把Cluster候选加入phase-1候选表、再由strong score统一竞争的处理更激进。

因此当前实现适合作为结构清晰family树上的实验性Cluster后回退，不适合作为全部算例的默认CutSet。已有TWET数据也支持这一边界：family zero中`Cluster -> CutSet -> Arc`把节点从334降到151，但wide只从26降到21；两个无Cluster random长算例反而分别从14增至24、从32增至60。若后续要形成真正general的CutSet separator，优先项应是围绕`|z(S)-(floor(z(S))+0.5)|`执行add/remove/swap局部搜索、去除高度嵌套候选并保留Jaccard多样性、将二元候选与有向Arc放回同一strong池竞争，并分别记录集合规模、分数层级和同节点child gain。现有证据不足以先修改正式主线。

2025年论文对适用性给出的是经验结论，不是CutSet自动启用判据。纯CSB在CMT13上比Edge强，但24小时后仍只探索估计树的3.75%，总时间估计26.7天；加入Cluster后才在约2小时内闭合。大规模实验中，纯CSB在clustered和random-clustered VRPTW上优于Edge，而Edge在random类更好；超长路线CVRP中CSB相对更强。对大型完全random实例，纯Edge也可能优于CSB。论文由此说明CSB更可能受益于有结构的客户集合或长路线，但没有提出可验证的CutSet适用条件，也没有证明“接近半整数”足以产生强分支。论文明确提出的结构适用性分析主要针对其新方法Cluster Branching，而不是对CVRPSep CutSet建立新的理论分类。
