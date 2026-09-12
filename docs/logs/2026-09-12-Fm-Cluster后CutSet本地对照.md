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

## 12. CVRPSep的真实候选生成与`|x(delta(S))-3|/q(S)`

Lysgaard、Letchford和Eglese（2004）的候选排序应结合分支两侧理解。候选满足`2<x(delta(S))<4`，分支为`x(delta(S))=2`和`x(delta(S))>=4`。当前值越接近3，离两侧边界越对称；若当前值为2.1，等于2的一侧几乎不改变LP，而大于等于4的一侧很远，分支明显失衡。因此分子`|x(delta(S))-3|`越小越优先。

分母`q(S)`是集合总需求。CVRPSep只允许贪婪集合满足`q(S)<=Q`，因为`x(delta(S))=2`表示由一辆车服务该集合；若需求已经超过车辆容量，这一侧本来就被容量约束排除。对同样接近3的候选，除以`q(S)`会优先较大需求集合。其结构解释是：当`q(S)`接近容量时，单车侧剩余容量更小，集合与外部客户重新组合的自由度更低；另一侧则强制至少两辆车触及该集合，因此这一析取通常比很小需求集合更具全局影响。原文没有单独证明或解释这个除法，所以这应表述为与CVRP容量结构一致的启发式解释，而不是定理。公开源码还在分子加`0.0001`后再除以需求，使多个恰好命中目标3的候选明确优先总需求更大的集合。

公开CVRPSep的`BRNCHING_GetCandidateSets`并不是简单地“不断加入内部连接最强的点”。其实际流程如下。

1. 输入当前LP中取正值的无向support edges、客户需求、车辆容量和已有的一车容量cuts。
2. 先压缩support graph：把`x_e>=0.999`的边连接成supernodes；还会利用接近整数的三点结构和紧的一车容量cut继续压缩。每个supernode的需求是内部客户需求之和。
3. 对每个supernode分别作为seed，令当前集合为`S`。候选只从当前support邻居中选择，并始终要求加入后`q(S)<=Q`。
4. 若加入相邻supernode `v`，边界按

   \[
   x(\delta(S\cup\{v\}))
   =x(\delta(S))+x(\delta(v))-2x(S,v)
   \]

   增量更新。每一步选择使新边界最接近目标3的`v`，保存该prefix，再扩充support frontier。这里已经同时考虑了`v`的外部边界和它与`S`的内部连接，不等于只最大化`x(S,v)`。
5. 展开supernodes后，仅保留需求不超过容量、规模大于2、边界严格位于约`(2,4)`并满足相应内部流范围的集合。同时把所有分数customer edge显式加入为二元集合候选，保证Edge型候选不会因贪婪扩张漏掉。
6. 所有候选按`(|x(delta(S))-3|+0.0001)/q(S)`升序排序，截取调用者要求的最大数量。

Silva、Uchoa和Subramanian（2025）的公开VRPTW代码没有重新设计CutSet separator，而是直接调用上述CVRPSep例程：目标设为3，最多请求`n`个集合，只传递当前LP中取正值的无向edge flow。CVRPSep返回集合和`x(delta(S))`后，代码计算`omega_S=x(delta(S))/2`，只接受其小数部分位于`(0.1,0.9)`的候选，并构造`0.5`乘全部cut edges的branching expression。若同时启用Cluster Branching，则删除与已知cluster完全相同的CutSet，避免重复候选；其余候选交给BaPCod的strong branching候选机制。论文中的两阶段strong branching在第一阶段粗测最多100个候选，再精测最好的少数候选；Cluster变量加入该候选表时，总候选预算不增加。

论文确实观察到VRPTW中纯CutSet在clustered和random-clustered类优于Edge，而纯Edge在完全random类更好，但没有给出纯CutSet差异的严格机制证明。作者对结构的直接分析主要是：CVRPSep只看分数性、完全不知道cluster；在CMT13根节点，它偶然找到恰好等于真实cluster `C3`的集合，获得很高score，但后续没有再次发生。作者对Cluster Branching进一步解释，大型random实例缺少稳定的natural clusters，因此聚合结构分支不利；100点random实例有时仍会偶然出现自然簇。把这套解释迁移到纯CutSet时，最稳妥的表述是：clustered数据更可能让“接近半整数”的集合同时对应有意义的路线块，random数据中同样分数的集合更可能只是当前LP的临时组合。后一句是由算法和实验共同支持的机制推断，不是论文已经证明的结论。

这与TWET目前的方向一致，但不能视为完全相同的复现。当前TWET separator没有CVRPSep的整数边压缩、容量上限、目标3导向扩张和需求归一化；它从每个任务出发，按双向support affinity最大值增长集合，再按最近半整数筛选所有层级。TWET没有CVRP的需求`q(S)`与容量`Q`，因此不能机械改成除以集合大小：那会改变原指标的容量含义，也可能进一步偏向或压制某些集合规模。当前random负结果还受到小集合偏置和严格CutSet优先挡住有向Arc候选的共同影响，不能全部归结为“random天然不适合CutSet”。

## 13. Cluster有机制分析，CutSet没有同等适用性理论

2025年论文对Cluster Branching给出了明确的设计动机。CMT13具有六个空间上清晰分离的cluster，类间edge平均成本约为类内edge的11.6倍。普通Edge Branching在两类edge上都有弱侧：分支类内低成本edge时，禁止或强制一条edge都容易由其他便宜类内edge替代；分支昂贵类间edge时，强制该edge通常很强，但禁止它可以换用同一对cluster之间的平行edge，因而另一侧很弱。Cluster Branching改为分支cluster边界总流`omega_C`或两个cluster之间的aggregate flow `psi_CD`。替换具体edge不能改变aggregate数量，因此它直接决定“多少路线片段进入cluster”或“两个cluster之间使用多少连接”，这就是论文对其可能提高两侧bound gain的核心解释。

论文也分析了Cluster在random上的边界，但仍属于结构解释而非必要充分条件。100客户的random XML实例有时会偶然形成彼此分离的natural clusters，因此Cluster可能仍小幅改善；客户更多的random X实例通常没有这种稳定空间块，Cluster变得不利。VRPTW结果中纯Edge在random类优于纯CutSet，纯CutSet在clustered和random-clustered类更好；作者还建议未来让聚类考虑时间窗，以避免把空间接近但时间窗严重错开的客户归为一组。

对CutSet本身，论文没有给出同等层次的适用性机制或启用判据。CutSet是既有基线，CVRPSep只按当前LP的分数edge flow搜索`omega_S`接近目标的集合，不检查空间cluster、路线稳定性或重优化后的集合含义。论文只给出三类相关观察：总体上长路线或超长路线实例中CutSet更可能优于Edge；VRPTW中clustered和random-clustered类的纯CutSet优于Edge；CMT13根节点的CVRPSep曾偶然命中真实cluster并取得高score，但后续没有重复。由此只能总结经验规律，不能从论文得到“满足某项静态指标就应开启CutSet”的规则。

他们实际使用Cluster时也不是先判断实例属于clustered便强制选择Cluster。聚类划分由MST、K-means、K-medoids或DBSCAN等方法预先构造；每个节点只把当前值为分数的`omega_C`和`psi_CD`加入第一阶段strong branching候选表，再用原有Edge或CutSet候选填满固定预算。第一阶段粗测候选两侧，第二阶段精测少数最好候选，最终仍由strong score选择分支类型。因此Cluster提供的是新的高层候选，不是替代strong branching的硬规则。不同聚类方法和基础分支类型通过独立配置运行后做实验比较。

## 14. 当前TWET Cluster与原文的对应关系，以及它和CutSet的粗细层次

当前TWET实现与Silva、Uchoa和Subramanian（2025）的Cluster Branching在核心数学步骤上是一致的，但属于面向有向调度的适配，不是原代码的逐项复现。原文先从实例静态数据得到客户partition，再在每个节点计算cluster boundary flow与cluster-pair flow；只把当前取分数值的aggregate变量作为strong branching候选，并最终回退到既有Edge或CutSet。当前TWET同样先建立一次静态partition，再生成`clusterBoundary`和`clusterPair`候选，对分数值执行`floor/ceil`分支，约束系数都是原有有向Arc系数，因此仍是robust branching，且三类pricing均可直接读取。

具体实现存在四项有意差异。第一，论文比较MST、K-means、K-medoids和DBSCAN等空间聚类；TWET当前只使用MST，并用`mean + theta * std`切断较长MST边。类中通用默认距离允许混合setup与due-center，但正式Cluster实验清单都显式设置`clusterTemporalWeight=0.0`，实际partition是setup-only：使用双向setup均值构造对称距离。第二，论文基于无向VRP edge；TWET的`clusterBoundary(C)`统计从集合外进入`C`的有向流，`clusterPair(C,D)`又拆成`C->D`和`D->C`两个候选，以保留不对称setup及PWLF后缀成本的方向差异。第三，论文把Cluster候选加入原strong-branching候选表并用基础Edge/CutSet填满固定预算；当前正式实验启用`structuredArcStrictTypePriority=true`，执行严格的`Cluster -> CutSet -> Arc`分层，只要当前层存在分数候选就不让下一层竞争，因此比原文更激进。第四，当前实现没有原文多种partition的横向选择，也没有按silhouette自动关闭Cluster；这些指标目前只输出供离线分析。

Cluster与CutSet的关系必须分成两部分。对cluster boundary候选，确实可以把Cluster看成静态、结构先验驱动的CutSet特例：Cluster预先规定少量明显集合`C`，每个节点只检查这些集合的进入流；CutSet则根据当前LP support动态搜索任意`S`。因此CutSet可能在完整family之后继续找到family内部更小子集、多个family的并集或其他残余分数块，而Cluster主要先处理最容易由setup结构识别的粗粒度集合。

但整个Cluster Branching并不是CutSet的特例。`clusterPair(C,D)`直接统计两个指定cluster之间的有向连接总量；一般不存在一个集合`S`，使其边界流恰好只等于`C->D`。所以Cluster还提供了CutSet没有的“两个已知结构块之间使用多少连接”这一类候选。

“CutSet可以继续找更小、可能归一台机器的分类”作为无外包场景下的直觉基本正确。当前TWET的`z(S)`统计所有机器序列进入`S`的总次数。在无外包整数解中，所有`S`内任务都必须由内部路线覆盖，所以`z(S)<=1`会强制恰好一台机器触及`S`，且`S`在该机器上形成一个连续片段；`z(S)>=2`则表示至少两次进入，既可能是多台机器分别触及，也可能是同一台机器离开后再次进入。不能保证的是动态候选生成器找到的`S`本身一定对应有意义的机器块。若允许外包且CutSet不把`OUT`计入系数，`z(S)<=1`也不能再表示整个`S`都由同一台内部机器加工。现有无外包family实验确实显示Cluster之后选中的CutSet常是family内部约5至6个任务的子集，zero场景节点由334降至151，支持“静态整family之后再动态处理内部残余块”的分层解释；但random中CutSet多退化为二元或三元无向相邻分支并明显变慢，说明动态搜索本身不保证找到有意义的细分类。

因此目前最准确的概括是：`clusterBoundary`负责由静态setup结构直接识别的粗粒度集合，动态CutSet可尝试处理这些集合内部或它们组合后的残余分数片段，最后由有向Arc解决具体顺序；`clusterPair`则额外直接处理两个结构块之间的aggregate连接。这个`Cluster -> CutSet -> Arc`层次在family数据上有清楚的结构解释，但CutSet层的正收益目前只在部分算例成立，仍不能提升为正式默认结论。

## 15. 按CVRPSep思路优化当前CutSet候选生成器

CVRPSep的车辆容量`Q`不能直接替换为机器数量`m`。`Q`是单条路线的硬承载上限，`q(S)>Q`能够严格证明集合`S`不可能由一辆车服务；`m`只是全局机器数，并不限制一台机器最多加工多少任务。TWET的due window是软约束，因此任务数、总processing或平均负载都不能作为安全容量过滤。只有基于硬horizon得到的单机可行性下界才能安全排除左支，但预计触发很少。`n/m`或总processing除以`m`最多作为候选影响力的次级排序量，不能作为正确性过滤条件。

最直接可迁移的是近整数supernode压缩。对当前LP定义无向support affinity`w_ij=y_ij+y_ji`，将`w_ij>=1-epsilon`的近整数连接先压成分量，再从分量而不是singleton开始扩张。该操作只改变候选搜索，不修改模型；即使压缩启发式不理想也不会影响完备性。它可避免同一条近确定链从多个seed重复生成相同二元、三元prefix，并使初始候选更接近真实路线片段。TWET最终有向，因此supernode只用于搜索集合，不能把内部方向从pricing或分支模型中真正收缩掉。首轮应使用CVRPSep同量级的严格阈值`0.999`，并输出分量数量、最大分量和singleton比例，避免压出巨型分量。

第二项是把扩张准则从“最大内部affinity”改成“新边界最接近目标”。当前实现总是选择最大`sum(y_uv+y_vu)`的任务，而

\[
z(S\cup\{v\})=z(S)+h_v-\sum_{u\in S}(y_{uv}+y_{vu}).
\]

最大affinity只会尽量减小`z_new`，并不保证它靠近真正希望分支的位置。最终决定第一版像CVRPSep一样重点处理“一台机器连续加工与至少两次进入”的析取：只接受`1<z(S)<2`的集合，扩张时选择使`z_new`最接近`1.5`的supernode，候选最终也按`|z-1.5|`排序。TWET没有CVRP容量条件，所以这不是“高层`2.5、3.5`候选无效”的理论结论，而是有意缩小候选语义；当没有第一层CutSet时直接回退有向Arc，分支完备性不受影响。

第三项只先排除二元CutSet，即要求`|S|>=3`。覆盖取紧时二元集合满足`z({i,j})=2-(y_ij+y_ji)`，其分支只决定两任务是否沿任一方向相邻，不能决定TWET需要的有向顺序；在严格类型优先下，它又会挡住更直接的有向Arc。补集至少含两个任务、只保存局部最好prefix、Jaccard去重和按规模保留代表集合都具有启发式合理性，但现有证据不足，第一版不加入，避免同时改变过多因素。

分支顺序按当前决定保持严格分层：`Cluster -> CutSet -> Arc`。原文采用共享strong候选池，但本轮不修改现有分层流程；先判断改进后的CutSet集合识别器能否减少弱小集合。如果仍在random上挡住明显更强的Arc，再单独讨论候选竞争，不能与supernode改造混在同一轮A/B中。

机器数量暂不进入候选生成。可以定义名义单机processing负载`Pbar=sum_j p_j/m`作为未来次级评分，但由于软时间窗、setup和机器负载不必均衡，该量既不能像CVRPSep的`q(S)<=Q`那样删候选，也没有证据说明现在加入能够改善strong gain。

cluster内定向搜索也暂不加入。supernode本身已经利用当前LP的近整数结构，先验证它能否自然形成family内部较大候选；若不能，再考虑在静态cluster内增加第二组搜索。

最终确定的第一版只有三项：一是按`y_ij+y_ji>=0.999`建立候选搜索supernode；二是扩张时直接最小化`|z_new-1.5|`，且只保存`1<z<2`的候选；三是严格CutSet阶段只接受`|S|>=3`的集合。其余候选池大小、保存该层全部分数prefix、精确去重、strong branching、严格`Cluster -> CutSet -> Arc`顺序和分支约束全部保持不变。新增统计只记录supernode数量与规模、候选`|S|/z`及最终被选类型。先在已有family zero正例和两个random负例做同seedA/B，再决定是否需要高层CutSet或后续筛选。所有这些修改只改变搜索树形状，不改变pricing集合、分支完备性或最优性证明。

## 16. 实现、A/B结果与最终保留范围

按第15节方案实现后，先测试了“近整数supernode + 扩张时目标`z=1.5` + 只保留`1<z<2` + `3<=|S|<=n-2`”。该版在family zero上为`137.964s/142节点`，与旧CutSet的`138.944s/151节点`基本持平；family wide却从旧版`54.487s/21节点`恶化到`247.047s/43节点`；random set03为`344.909s/26节点`，仍劣于旧CutSet的`266.929s/24节点`和Arc的`157.593s/14节点`。主要问题不是候选构造耗时，而是只保留第一层CutSet后，集合规模变小，原来family wide中有用的`z约2.5`高层分支被删除，一些替代候选又触发了昂贵的strong-branching Phase-I repair。

随后恢复全部分数层，仅保留supernode和面向`1.5`的扩张。family wide恢复到`70.713s/33节点`，证明高层候选不能删除；但family zero运行到275秒仍卡在节点69的repair，random同期也已超过旧版时间，说明改变贪婪扩张方向本身就会选到更差的候选链。这两个运行在结论已明确后中止，不当作完整求解结果。

因此最终撤回全部候选语义变化：不使用supernode，不改成`z=1.5`导向，不删二元或高层集合，不缩小最大集合。正式代码恢复原有singleton seed、最大support-affinity扩张、所有分数层、先按距最近半整数再按集合规模的排序及top-40候选。仅保留两项不改语义的性能优化：

1. 预计算singleton进入流，并在扩张时用`z(S∪{v})=z(S)+z({v})-sum(y_uv+y_vu)`增量更新边界；
2. 候选搜索阶段只保存job set和边界值，排序截取后只为最终top-40建立完整incoming-arc mask。

新回归在同一人工LP-flow上以旧实现作为reference，逐个核对所有候选job set和直接枚举得到的边界值，数量、集合和数值全部一致。family zero完整树的前8次分支也与旧日志的候选、左右trial bound和score逐字段一致。完整树并发运行为`174.482s/162节点`，random set03串行运行为`413.635s/22节点`；两者目标和bound均与旧版一致。总时间受启发式定价路径、并发资源争用和少数exact/repair波动支配，不能用这两个数值声称总求解加速。

候选生成器本身的隔离微基准使用`n=100`同一稀疏LP-flow快照：旧实现平均`316.575ms/次`，增量版`3.661ms/次`，约快`86.48`倍。该数字只证明CutSet候选构造的局部改动有效，不代表BPC总时间同比例下降；实际总时间仍由pricing、strong trial LP和Phase-I repair决定。正式CutSet仍默认关闭，random上不建议开启，family上仅作`Cluster -> CutSet -> Arc`实验性对照。
