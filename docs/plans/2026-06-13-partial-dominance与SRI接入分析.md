# partial-list dominance 与 SRI 接入分析

## 1. 当前 partial-list pricing 检查

当前 `PartialListDominanceStore` 的核心逻辑是按 `reachableCardinality` 分桶。新 label 进入时，先只扫描集合大小不小于自己的 bucket，只有已有 label 的 reachable set 是新 label 的超集时，才用已有 frontier 裁剪新 frontier；如果新 label 未被裁空，再反向扫描集合大小不大于自己的 bucket，用新 label 裁剪已有 label。集合大小只是必要条件，真正的安全条件仍然是 `reachableSet.isSupersetOf()` 和函数区间支配。

这套实现目前在非 SRI 口径下基本是合理的。前一轮已经把 dominance key 与半域扩展集合拆开：用于 dominance 的 reachable/dominance set 不再混入 `Tmid` 半域限制，而实际扩展才用 extension set 控制半域。这一点很关键，因为 dominance key 应表达沿路径后续永久不可达的信息，不能把“当前方向不再扩展过去”当作资源不可达。010/011 的 ng-DSSR 三种 dominance backend 复测已经恢复一致，说明此前图/list partial 的明显结果分歧大概率来自这类语义混用。

单点 label 也已经单独处理。Tmid 单点 frontier 不进入普通 dominance store，也不继续扩展；它保存在 `SinglePointStore` 中，供 sink 收尾和双向 join 使用。单点的支配判断分两层：先查单点 store 中是否已有同 key 或更大 reachable set 且成本不高的单点，再让普通 dominance store 判断普通函数 label 是否在该单点时间上支配它。这个逻辑在无 SRI 时与普通 label 的集合语义一致；如果引入 SRI，单点比较也必须同步使用 SRI-aware 的成本补偿，否则普通 label 和单点 label 会用不同 reduced-cost 口径。

效率上，当前实现已经做了几项必要优化：三态裁剪避免 no-change 时刷新最小值；定义域无正长度 overlap 时跳过 PWLF 裁剪；cardinality bucket 避免全表扫描。仍有一个小的热路径开销是诊断用的 `countLabelsInBuckets()` 会在每次插入时扫描 bucket 统计跳过数量，这不是正确性问题，若后续 40/50 任务下 profiling 显示明显，可以把统计改成维护 bucket size 前缀或只在诊断开关打开时计算。

## 2. 旧 VRP 中 SRI 的实际流程

旧 VRP 的 subset-row cut 是 3-customer cut。分离时枚举三元组 `{i,j,k}`，对当前 LP 中所有正值 route/bid 统计“包含至少两个 cut customer”的变量值之和；若超过 1，就生成一条 `sum coeff * var <= 1` 的 cut，其中 route 系数为 1 当且仅当该 route 包含至少两个 cut customer。

LP 中 `AddSRCut()` 建 `0 <= row <= 1`，求 dual 时只把负 dual 的 SRI 加入 pricing active set。定价里 label 同时维护两套 reduced cost：`m_reduced_cost` 包含 SRI penalty，`m_nosr_redcost` 不包含 SRI。扩展一个新 customer 时，如果某个 active SRI 的访问计数从 1 变成 2，就对含 SRI 成本加上 `-dual`；由于 SRI dual 为负，这实际上是一个正 penalty。不含 SRI 成本不加这项。

旧 GCNGBB 的 dominance 不是简单比较含 SRI reduced cost。它还维护 `sr_count`、`sr_1_set` 和 `sr_1_value`，在判断 A 是否支配 B 时，对 A 中“当前恰好访问了一个 cut member、未来可能触发第二个 member”的 cut 做保守补偿，避免 A 当前看起来便宜但未来必然付 SRI penalty 的情况错误支配 B。join 时也要重新合并两边的 SRI 状态：两边都已经触发同一个 SRI 时要去掉重复计算；两边各有一个不同 member 时，合并后要补上新触发的 SRI penalty。

completion bound 在旧实现中有两个口径：不含 SRI 的基础 bound，以及考虑 SRI 状态后的 bound。至少第一版 TWET 可以先保守使用“不含 SRI prefix + 不含 SRI completion bound”做剪枝；这会弱一些，但不会因为忽略 SRI 状态而错剪。若要复刻旧 GCNGBB 的强剪枝，再引入 SRI-aware bound。

## 3. 接入 TWET 时必须改的模块

第一块是 cut 数据和 LP 行。当前 `TWETCut` 只是元数据，`SubsetRowCutGenerator` 仍是占位，`LP` 虽然保存 `activeCutIds`，但没有把 active cut 建成 CPLEX 行，也没有读取 cut dual。因此不能只在 pricing 里加 SRI 成本；必须先让 LP 真正包含 SRI row，并在 `readDuals()` 后暴露 active SRI dual。

第二块是 partial-list ng-DSSR pricing。按当前要求，SRI 只接入 partial dominance 路径，最自然的落点是 `GCNGBBStyleBidirectionalNgDssr` 的 `LIST_PARTIAL` backend，而不是普通 paper backend 或非 ng 的 partial 实验类。label 需要增加 SRI 状态：含 SRI frontier、不含 SRI frontier、active SRI count，以及等价于旧 `sr_1_set/sr_1_value` 的信息。扩展、dominance、single-point、join、候选列 reduced cost 过滤都要同步使用这套状态。

第三块是 cut separation 和节点继承。`PC` 已经有 cut round：pricing 收敛后调用 cut generator，分离出 cut 后 `lp.addCuts()` 并重解 LP。`Node.copy()` 也会复制 `activeCutIds`，所以继承框架存在。需要补的是 `SubsetRowCutGenerator` 真正枚举三元组、控制每轮最多 cut 数、控制每个 job 的出现次数，并通过 `CutPool` 去重。生成 cut 后子节点自然继承 active cut id。

第四块是配置和防误用。因为只有 partial-list ng-DSSR pricing 会考虑 SRI，如果 LP 里已经有 SRI 行，exact pricing 就必须走 SRI-aware 的 partial-list ng-DSSR；否则其他 pricing engine 会忽略 SRI dual，不能证明“没有负 reduced-cost 列”。配置上应该显式加 `enableSubsetRowCutsForPartialDominance` 之类开关，并在启用 SRI 时校验 exact pricing engine。

## 4. 关键有效性风险

最大的数学风险是：当前 TWET RMP 的覆盖约束是 `>= 1`，且存在外包变量。旧 VRP 的 3-customer SRI 是 set-partitioning 语义下的 cut。直接把 `sum routeCoeff * lambda <= 1` 加到当前 set-covering master 上，未必自动有效，因为当前 LP 允许重复覆盖 job。虽然在 setup time/cost 满足删除单调性时，重复服务通常可以删除而不变差，但这需要和外包、分支、tariff 变量一起重新确认。实现前至少要决定一种口径：要么先证明 SRI 对当前 `>=` master 仍有效；要么只在等式覆盖/整数修复模型中使用；要么把主 LP 的相关阶段改成等式口径后再加 SRI。

另一个风险是 root profitable window。当前代码已经在 `lp.getActiveCutIds().isEmpty()` 为 false 时关闭 root dual window。SRI 真正加入 active cuts 后，这条逻辑会自然关闭 pi-window；但要确保 SRI active cut id 的时机正确。只要有 active SRI cut，就不能再使用原先依赖 no-cut 三角不等式的 pi-window 和 zero-dual 排除。

## 5. 建议实施顺序

建议分三步做，不要一次性把 separation、LP、pricing、completion bound 全改完。

1. 先实现 SRI cut 数据、LP row、dual 读取和手工/诊断 cut 注入。验证 LP 行系数、dual 符号、active SRI dual 只取负 dual。
2. 再实现 `LIST_PARTIAL` ng-DSSR 的 SRI-aware pricing。先用不含 SRI 的 completion bound 做保守剪枝；扩展、dominance、single-point、join 必须一次接齐。
3. 最后实现 `SubsetRowCutGenerator` 分离和 node cut 继承测试。先限制每轮 cut 数和每个 job 出现次数，避免 root 一次性加太多 cut 导致 pricing 状态膨胀。

当前结论是：partial-list pricing 本身在无 SRI 口径下没有发现新的正确性问题，效率也可接受；SRI 接入的难点不在 cut 形式，而在 LP 有效性、SRI 状态下的 dominance/single-point/join 一致性，以及启用后必须禁用所有不懂 SRI dual 的 exact pricing 路径。
