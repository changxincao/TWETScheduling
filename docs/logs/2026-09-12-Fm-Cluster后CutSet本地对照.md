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

旧 random-like `40-2` 的负结果仍成立，但不能迁移到正式 `F<m` family。当前三个同任务集对照表明：当 Cluster aggregate 已整数而 family 内仍存在分数子结构时，动态 CutSet 作为下一层 fallback 可以比逐条 Arc 更快处理集合边界，两个难例均明显缩树。

证据尚不足以把 CutSet 设为所有算例默认分支。当前 separator 仍会产生嵌套集合，aggregate strong trial 和 Phase-I repair 也明显更贵。下一步若继续，应限定在静态 family partition 清晰、`F<m`、Cluster 后仍形成长树的子集，补不同 set/规模的同 seed 对照；不应据这三个实例推广到 random、`F>m` 或根 pricing 主导的算例。本次未修改候选生成或正式 profile，也未操作远端任务。

## 5. random 无 Cluster 的长算例复核

为避免前一批 `n40` random 对照只有 `15--79s`、不足以观察长树收益，补充两个 `n50/m2/random/base/zero` 完整 BPC。两组均使用正式 profile `2026-09-12-v8`、同一实例和共享 ALNS seed、强分支及 root preprocessing，单次上限 `3600s`。Cluster 明确关闭；Arc 侧关闭 CutSet，CutSet 侧采用严格 `CutSet -> Arc` 回退。配置快照逐项核对后，除运行名、输出路径、`enableCutSetBranching` 及由此装配的 brancher 外没有差异。四次运行均闭合最优，目标和排程复核通过。

| 算例 | Arc | CutSet -> Arc | 总时间变化 | pricing轮数变化 |
| --- | --- | --- | --- | --- |
| `n50-set03/m2` | `157.593s`，14节点 | `266.929s`，24节点 | `+69.4%` | `1023 -> 1525` |
| `n50-set05/m2` | `790.366s`，32节点 | `1448.208s`，60节点 | `+83.2%` | `2651 -> 4468` |

恶化不只是 aggregate strong trial 更贵。set03 的 strong-trial RMP 由 `13.573s/400次` 增至 `43.405s/520次`，set05 由 `34.689s/1000次` 增至 `150.042s/1680次`；即使扣除这部分增量，总时间仍分别多约 `80s` 和 `542s`。真正主导差距的是树和定价调用一起增加：set03 的 NG-DSSR exact 由 `72.461s/197次` 增至 `124.797s/295次`，set05 由 `520.087s/531次` 增至 `897.072s/995次`。set05 的 master LP 也由 `75.627s` 增至 `231.532s`，是更多节点、更多列和更多 strong trials 的共同结果。

因此，当前动态 CutSet 不能作为 random 的默认分支。它在两个几分钟至二十余分钟的 random 长算例上都没有缩树，反而使左右 child 更常存活，节点约增加 `71%--88%`。这与 family `F<m` 的正结果边界一致：CutSet 的价值依赖可识别的局部集合边界；random 缺少稳定 family 分区时，当前贪婪集合没有形成比单 Arc 更强的高层析取。原始日志和完整 CSV 位于 `.codex-tmp/cutset-random-large-20260912/`，汇总另存为 `docs/logs/data/20260912-cutset-random-large.csv`。

## 6. random 下 CutSet 失效的机制

当前 CutSet 对任务集合 `S` 统计进入流 `z(S)`，当 `z(S)=k+f` 时分成 `z(S)<=k` 与 `z(S)>=k+1`。这一分支只有在 `S` 对应稳定的路线块时才强：左右侧分别代表该块由较少或较多条路线进入，改变的是高层路线组织。random setup 下没有稳定块，当前 separator 只能从本轮 LP support graph 动态猜集合。它从每个 singleton seed 出发，每次加入与现集合双向 support flow 最大的任务，保存所有分数 prefix，再按进入量接近半整数排序。该 affinity 最大化的是集合内连接量，不直接最大化分支后的 child bound，也不保证集合在 dual 更新后仍有相同结构意义。

因此，random 下得到的 `S` 往往只是当前分数解中的偶然路线片段。限制其进入次数后，pricing 可以换一个边界任务、换一条进入弧或重新组合路线，继续满足 `z(S)<=k` 或 `z(S)>=k+1`，但真实成本变化很小。候选值接近 `1.5/2.5/3.5` 只说明数值上左右距离接近，不代表两侧 bound gain 都大。set03 根节点就是直接证据：Arc 的同根 strong-branching 第一阶段 RMP trial gain 为约 `396/1806`，score约 `715015`；CutSet 虽取值 `1.4954`，两侧 gain 只有约 `384/541`，score约 `207727`。它的弱侧与 Arc 类似，但强侧少了约三分之二的提升。

Arc 还具有 CutSet 没有的域传播。禁止一条 Arc 会从图中删除该弧；要求 `i->j` 会同时禁止 `i` 的其他后继和 `j` 的其他前驱，直接缩小后续 pricing 图。当前被选 CutSet 的值大多大于1，左右界通常是 `<=1/>=2` 或更高，因此不会触发仅适用于 `Q<=0` 的成员弧直接禁用；两侧基本保留完整图，只多一条 aggregate row。由此每个 child 仍需在大量替代边上重新做 exact pricing。aggregate candidate 还不能使用 Arc 的 lightweight/domain strong-trial 路径，但这只是次要放大项；扣除 strong-trial 增量后总时间仍明显增加。

两个算例的恶化程度不同，主要因为弱分支被放大的次数和单次 pricing 成本不同。set03 根 gap 约 `0.32%`，CutSet只实际分支13次；选中集合平均5.46个任务，其中7/13不超过3个任务。set05 根 gap 约 `1.02%`，CutSet分支42次；选中集合平均仅3.31个任务，30/42不超过3个任务，21个就是二元集合。`9-14`、`17-18`、`21-42`等六个集合还在不同子树重复被选，说明根分支没有消除这些相互独立的小块歧义。set05 的队列峰值因此由Arc的24升至41；同时NG-DSSR单次exact平均约 `0.90--0.98s`，明显高于set03的 `0.37--0.42s`。同样的弱分支不仅多产生28个节点，而且每个新增节点更贵，最终形成 `790s -> 1448s` 的差距。

这也解释了它与 `F<m` family 正结果的区别。family 中先经过 Cluster 后，残余 support 集合仍可能与真实低setup块或其子块一致，CutSet有机会一次约束一批可替代边，并避开坏子树或重Phase-I repair。random 中 support 集合随dual和列池变化，没有稳定成本边界；CutSet约束的是本轮分数流的形状，而不是持续存在的调度结构。严格 `CutSet -> Arc` 又使任何分数CutSet存在时都不让Arc参加同节点竞争，放大了候选质量问题。若改为混合候选，强分支大概率会过滤掉不少CutSet，能够降低当前劣化，但现有结果没有证据说明它会比纯Arc更快。

## 7. family 与 random 的正确对比

需要纠正：family 中的 Arc 替代性并不比 random 弱，通常反而更强。因此两者的区别不是“可替代 Arc 多少”，而是这些替代是否围绕一个稳定集合边界发生。family 的低setup块在dual和列池改变后仍然存在；具体进入 Arc 可以从 `u->i` 换成 `v->j`，但只要它们仍从集合外进入同一个 family 子块 `S`，都会被同一个 `z(S)` 捕获。CutSet 此时把多条具体 Arc 上的选择聚合成“这个子块由多少个路线片段进入”的整数析取，正好阻止换一条平行 Arc 就逃过局部分支。

random 中同样有许多可替代 Arc，但替代是全局弥散的。当前 support 中偶然聚在一起的 `S` 没有持续的低setup边界。施加 `z(S)` 分支后，pricing 不仅能更换进入 Arc，还能以近似成本把 `S` 中的任务换到别的路线块，使下一节点的分数结构直接变成另一个集合。因此本质对比是：**family 是稳定块内的局部 Arc 替代，CutSet 可以一次聚合整类替代；random 是连候选集合边界本身都会漂移的全局替代，当前 CutSet 只是在追赶本轮 LP 的分数形状。**

日志支持这个解释。按 setup-only MST 恢复的三个真实 family 检查被选 CutSet，wide、zero、narrow 中分别有 `4/7=57.1%`、`33/47=70.2%`、`35/54=64.8%` 完全落在单个 family 内。例如 `{10,13,18,20,22,24,30}` 和 `{2,7,17,23,25,27,35,36}` 都是族内子块，而不是跨整个任务集的随机片段。这说明 support-affinity 在 family 数据上经常能找到具有持续结构意义的集合。

还必须保留实验口径限制：family 正结果比较的是 `Cluster -> Arc` 与 `Cluster -> CutSet -> Arc`，CutSet 只在 Cluster 已无分数候选时作为第二层；random 负结果则是无 Cluster 的严格 `CutSet -> Arc`。因此现有证据支持的是“CutSet 适合处理 Cluster 之后残留的 family 子块分数性，不适合从 random 根节点起严格优先”，尚不能宣称“纯 CutSet 在 family 上必然有效”。
