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

## 4. 当前结论

旧 random-like `40-2` 的负结果仍成立，但不能迁移到正式 `F<m` family。当前三个同任务集对照表明：当 Cluster aggregate 已整数而 family 内仍存在分数子结构时，动态 CutSet 作为下一层 fallback 可以比逐条 Arc 更快处理集合边界，两个难例均明显缩树。

证据尚不足以把 CutSet 设为所有算例默认分支。当前 separator 仍会产生嵌套集合，aggregate strong trial 和 Phase-I repair 也明显更贵。下一步若继续，应限定在静态 family partition 清晰、`F<m`、Cluster 后仍形成长树的子集，补不同 set/规模的同 seed 对照；不应据这三个实例推广到 random、`F>m` 或根 pricing 主导的算例。本次未修改候选生成或正式 profile，也未操作远端任务。
