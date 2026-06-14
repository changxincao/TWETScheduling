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

## 6. 2026-06-13 实现记录

本轮按旧 VRP 的 subset-row cut 路径把第一版 SRI 接进 TWET-BPC，但默认仍关闭。实现范围包括三部分。

第一部分是主问题。`LP` 现在会把 active 的 `SUBSET_ROW` cut 建成 `sum a_r lambda_r <= 1`，其中一条内部列只要包含三元组中至少两个 job，系数就是 1。LP 求解后会读取这些 cut 的 dual，只把负 dual 暴露给 pricing；`addColumnToCurrentModel()` 也同步给动态加入的列补 SRI 行系数。

第二部分是 cut 分离。`SubsetRowCutGenerator` 不再是占位类，而是枚举三元 job 组合，基于当前 LP 正值内部列计算 lhs，超过阈值时生成 `SRI3` cut。实现里保留了每轮最大 cut 数和每个 job 每轮最多参与 cut 数，用来避免一次加入过多 SRI 状态。当前开关是 `enableSubsetRowCutsForPartialDominance`，并且只允许在 `useGCNGBBStyleNgDssrPartialDominancePricing` 口径下分离。

第三部分是 partial-list ng-DSSR pricing。`GCNGBBStyleBidirectionalNgDssr` 的 label 现在维护 active SRI 的计数和 SRI state key；单侧扩展时，如果某个 SRI 的访问计数从 1 变为 2，就给带 SRI 的 frontier 加上 `-dual`。SRI 激活时，partial-list dominance store 按 SRI state 分桶，single-point store 也只允许同 SRI state 比较，避免不同 SRI 计数状态之间互相支配。join 时补了两类修正：左右半路径各自已经触发同一个 SRI 时去掉重复 penalty；左右半路径分别贡献一个不同 cut job 时补上拼接后新触发的 penalty。

验证上，Maven 在当前机器不可用；改用 `javac` 编译 `Basic/Common/HEU/Output/TWETBPC` 相关源码并通过。全源码直接编译仍会失败在旧 `src/BPC` 包的历史代码，这与本次 SRI 改动无关。当前还没有跑重型算例对比，后续若要评估 SRI 效果，需要单独打开 `enableSubsetRowCutsForPartialDominance`，并固定 partial-list ng-DSSR、completion bound、probe 和 pricing-only 等配置做对照。

## 2026-06-13 SRI 与 completion bound 闭环复查

本轮按 LP 行、cut dual、pricing reduced cost、join 修正、completion-bound 剪枝和 cut 后重新定价的顺序重新检查了 SRI 接入。当前 LP 中 `SUBSET_ROW` 行的系数口径是 distinct-job：一条内部列只要包含 cut 三元组中至少两个不同 job，系数就是 1；`SubsetRowCutGenerator` 的 violation 计算与该口径一致，pricing 侧也只读取 LP 中负 dual 的 active SRI cut。因此 SRI row、分离器和 pricing dual 的基本语义是一致的。

检查中发现两个必须修的问题。第一，原 `PC.solve()` 在加入 cut 并重解 LP 后没有重新进入 pricing loop，这会导致 SRI cut dual 已经读出，但没有被 exact pricing 用来闭合新 reduced cost。现在已在 `after_cut` 解后重新调用 `solvePricingLoop()`。第二，SRI active 时 exact pricing 中候选列的 inferred reduced cost 含 cut dual；如果直接按 machine/job/arc dual 反推目标成本，会把 SRI dual 混进 `TWETColumn` 成本。现在 `finalizeGeneratedColumns()` 在 SRI active 时强制用 `PricingColumnCostRechecker.evaluate()` 重算真实列成本，再加入列池。

completion bound 当前保持简化口径，不实现旧 VRP 每轮更新的 SRI-aware bound 表。label 内同时保存正式 frontier 和 `noSriFrontier/noSriMinReducedCost`；正式扩展和 join 使用含 SRI penalty 的 frontier，completion-bound 函数剪枝和 scalar 剪枝只使用 no-SRI frontier 加 all-cycle bound。这个下界忽略未来 SRI penalty，因此偏弱但安全：它不会高估补全 reduced cost，也不会因为 SRI penalty 把潜在负列错误剪掉。SRI active 后 root 的 dual profitable window 仍由 `activeCutIds.isEmpty()` 自动关闭。

本轮还补了 `GCBBFullDomainComparisonTest` 对 `enableSubsetRowCutsForPartialDominance`、`maxSubsetRowCutsPerRound` 和 `maxSubsetRowCutAppearancesPerJob` 的系统属性透传，否则本地对照测试无法真正打开 SRI cut。验证方面，focused `javac` 编译 `src` 下除历史 `src/BPC` 外的 Java 源码通过。两个 20 任务 no-outsourcing root 烟测均 valid，其中 `wet020_001_2m` 用时 1.109s、`wet021_001_2m` 用时 2.794s；这两个小样例 root 已经整数，没有分离出 violated SRI cut，因此运行验证覆盖了入口和 no-cut 链路，active SRI dual 分支主要由代码审查确认。后续若要做强验证，应找 root fractional 且能分离出 SRI 的样例，固定 partial-list ng-DSSR 后再比较 cut 前后 bound 和 pricing closure。

### 2026-06-13 旧 VRP price-and-cut 口径补充

进一步对照旧 `BPC.LP.PC.Solve()` 后，确认当前节点内应该是外层 price-and-cut 循环：先做启发式/精确定价直到没有负列，再在当前 LP 解上分离 cut；只要有 cut 加入，就重解 LP 并回到 pricing，直到 pricing 和 cut separation 都不再改进。当前 `PC.solve()` 的 cut loop 已按这个结构工作，本轮补充了两个旧口径细节：如果 pricing 后 LP 已经整数，则像旧 VRP 的 `if(!lp.IsInteger())` 一样不再分离 cut；cut 后重新 pricing 若得到整数解，也立即返回。

SRI 分离阈值同步复核旧 `SRCut.java` 和 `Configure.java`：每轮最多 10 条 cut、首条 cut 至少达到 1.1、后续候选低于 1.02 停止、同一 customer 在 active SRI cuts 中最多出现 20 次。本轮把 TWET 的 `maxSubsetRowCutAppearancesPerJob` 默认值从 3 改为 20，并把 appearance 限制从“本轮新增 cut 计数”改为“当前 active SRI cuts 累计计数 + 本轮新增计数”，对齐旧 `lp.sr_cus_number` 语义。

关于 SRI active 时重算候选列真实成本：pricing 里用于排序和判断负列的是 reduced cost，含 `objective - machineDual - jobDual - arcDual - SRI dual contribution`。`PricingColumnCostRechecker.buildInferredColumn()` 只能从 reduced cost 加回 machine/job/arc dual 来临时构造列成本；SRI active 时如果仍走这条反推路径，就少加回 SRI cut dual contribution，导致 `TWETColumn.cost` 不是原始调度目标成本。RMP 目标必须使用真实列成本，所以 SRI active 下最终入池前统一调用 evaluator 按 sequence 重算真实 objective cost。

验证：focused `javac` 通过；`wet020_001_2m` 与 `wet021_001_2m` 的 partial-list ng-DSSR + SRI 开关 root 烟测继续 valid，分别约 1.187s 和 1.802s。两个小样例未触发 violated SRI cut，因此仍属于 no-cut 链路烟测。

### 2026-06-13 SRI appearance 语义与影响范围复核

这里的 appearance 不是指一个 job 在某条 route/column 中出现多少次，而是指同一个 job 已经参与了多少条 active subset-row cuts。旧 `SRCut.java` 使用 `lp.sr_cus_number[c]` 在枚举三元组前过滤，并在新增 SRI cut 后对该 cut 中三个 customer 的计数各加一；默认上限来自 `Configure.m_max_appear_number=20`。当前 TWET 实现已改为相同语义：先统计 `lp.getActiveCutIds()` 中所有 active SRI cut 的 job 出现次数，再枚举候选三元组；达到 `maxSubsetRowCutAppearancesPerJob` 的 job 不再参与新 cut。候选加入时继续递增本轮计数，避免同一轮内超过上限。

效率上，原实现先枚举和计算所有 violated triples，再在加 cut 阶段过滤 appearance，结果正确但会多做 `subsetRowValue()`。本轮把 appearance 过滤前移到三重循环入口，和旧 VRP 一样提前跳过已满 job。当前分离复杂度仍是按三元组枚举并扫描当前正值内部列，和旧 VRP 的 route_customer 矩阵扫描属于同一类策略；对 30/40/50 任务规模一般可接受。后续若 SRI 分离本身成为瓶颈，再考虑按 column 反向累计 pair/triple 分数，而不是现在改动。

影响范围上，SRI 默认关闭。`SubsetRowCutGenerator` 只有在 `enableSubsetRowCutsForPartialDominance=true` 且 `useGCNGBBStyleNgDssrPartialDominancePricing=true` 时才会产生 cut；pricing 侧也只有 LIST_PARTIAL ng-DSSR 会读取 SRI dual。其他 exact pricing 策略不会生成 SRI cut，也不会读取 SRI dual。需要注意的是，如果外部手工把 SRI cut id 注入 activeCutIds，同时又切到非 SRI-aware pricing，则理论上会破坏列生成闭合；正常配置路径不会这么做。

验证：focused `javac` 通过；`wet020_001_2m` root 小样例在 partial-list ng-DSSR + SRI 开关下继续 valid，用时约 1.274s，仍未触发 violated SRI cut。

### 2026-06-13 011/30 SRI 长跑中断观察

使用三角化 `tmp-wet030_from040_011_2m` 做了一次 partial-list ng-DSSR + SRI cut 的 30 任务测试。配置包括 ALNS seed、all-cycle completion bound、pricing-only subtree、midpoint probe、`ngDssrRouteUpdateLimit=5`，并打开 `enableSubsetRowCutsForPartialDominance=true`、每轮最多 10 条 SRI cut、每个 job 在 active SRI cuts 中最多出现 20 次。

本次运行没有跑到分支阶段，而是在 root 的 price-and-cut 循环中中断。运行过程确认 SRI separation 已经真正生效：root 先完成 no-cut pricing closure，然后连续分离出多批 violated SRI cut。观察到 cutPool 依次从 20 增至 30、40、50、60，每批都是 10 条；对应 best lhs 大约为 1.296、1.342、1.286、1.277。每次加 cut 后 dual 明显变化，启发式 pricing 会再次批量生成列，exact pricing 随后继续闭合。

性能上，当前配置对 011/30 明显偏重。前几轮 exact pricing 通常在 2-12 秒之间，后续随着 active cut 和列池增加，单次 exact pricing 已经出现 30.5 秒和 43.2 秒的闭合轮；最后中断前仍处在 root，第三轮 cut 闭合后又分离了第 4 批 10 条 SRI cut，并且下一轮 heuristic 已经重新生成 160 条列。中断时列池规模已超过 9400，仍没有进入 branch。

当前结论是：SRI 接入链路已经能触发 cut、重解 LP 并重新 pricing，但默认“每轮最多 10 条、总轮数不限”的 separation 对 011/30 过于激进，短时间内主要消耗在 root 的反复 cut-and-price，而不是推进搜索树。后续如果要把 SRI 用作实际求解配置，应优先增加总 cut 轮数或总 active SRI cuts 上限，或者先用更保守的每轮 cut 数做对照；否则它更适合作为 cut 机制验证，而不是当前 30 任务主线配置。

### 2026-06-13 SRI active 后启发式 pricing 口径问题

复查 011/30 中断实验时发现，`HeuristicPricingEngine` 当前仍按 machine/job/arc dual 计算初始 reduced cost 和 remove/add/exchange 的局部增量，没有读取 `LP.getActiveSubsetRowPricingCutIds()` / `getActiveSubsetRowPricingDuals()`。因此一旦 root 加入 SRI cut，启发式生成的“负 reduced-cost 列”只是无 SRI 口径下的负列，不一定违反当前含 SRI row 的 LP dual。把这些列加入 RMP 本身不会破坏正确性，因为 LP 会给列补 SRI row 系数、目标成本也是真实成本；但它会显著放大无效列生成，解释了 011/30 中每轮 SRI cut 后 heuristic 仍批量生成 100+ 列的现象。

当前建议是：SRI active 后要么直接关闭 heuristic pricing，要么给启发式补上 SRI-aware reduced cost 与 move 增量；在补齐前，SRI 性能测试不应把 heuristic 大量出列理解为 cut 后真的存在大量 violated columns。此前完整无 SRI/普通 ng 后端对照中，三角化 011/30 已求到 `incumbent=bound=13511`；本次 SRI 中断运行没有写出最终 summary，也没有可靠当前 bound，只能确认初始上界仍为 ALNS 的 13813 且 root 尚未闭合。

### 2026-06-13 启发式 pricing 补齐 SRI reduced-cost 口径

本轮把 `HeuristicPricingEngine` 的 SRI 口径补齐，但限定在 `enableSubsetRowCutsForPartialDominance=true` 且 `useGCNGBBStyleNgDssrPartialDominancePricing=true` 的路径下启用；没有 active SRI cut 时仍完全走旧的 machine/job/arc reduced-cost 口径。实现上在每次启发式 pricing 开始时构造一个 `SriPricingContext`，预处理当前 active SRI cut 的 scope、dual penalty `-dual`，以及每个 job 关联的 cut 下标。seed 排序时先统计该列访问了哪些 SRI scope job，再把已触发 cut 的 penalty 加到 reduced cost 中。

Tabu 搜索内部新增当前序列的 SRI count 和 penalty 缓存。remove/add/exchange move 仍沿用原来的分段函数局部成本变化、job dual 和 arc dual 增量；额外只计算本次 move 对 SRI count 从 1 到 2 或从 2 到 1 的 penalty 变化。exchange move 按“先删后加”的计数语义处理，避免 removed job 和 added job 同属一个 SRI cut 时重复或漏算。由于启发式内部仍维护 `used[job]`，生成序列是 elementary 的，SRI count 口径与正式 partial-list ng-DSSR pricing 的 distinct-visit penalty 一致。

验证方面，focused `javac` 编译 `Basic/Common/HEU/Output/TWETBPC` 通过；随后用 `wet020_001_2m` 做 partial-list ng-DSSR + SRI 开关 root smoke，得到 `ROOT_PROCESSED,obj=bound=6343,valid=true`，用时约 1.8s。该小样例未触发 violated SRI cut，因此验证的是 no-active-cut 路径和配置开关未破坏；active SRI heuristic 分支主要通过代码口径对照确认与正式 pricing 的 `shift -= sriDual` 一致。后续若继续测 011/30 SRI 性能，应重新观察 heuristic 出列量是否下降。

### 2026-06-13 011/30 补齐 SRI 启发式后的受控测试

补齐启发式 pricing 的 SRI reduced-cost 口径后，重新用三角化 `tmp-wet030_from040_011_2m` 做了 180 秒 root-only 受控测试。配置沿用 partial-list ng-DSSR、ALNS seed、all-cycle completion bound、pricing-only subtree、midpoint probe、`ngDssrInitialMode=nearestK,size=8`、`ngDssrRouteUpdateLimit=5`，并打开 `enableSubsetRowCutsForPartialDominance=true`、每轮最多 10 条 SRI cut、每个 job 在 active SRI cuts 中最多出现 20 次。由于当前测试入口没有总 cut 数或总 cut 轮数限制，本次到 180 秒主动停止，未写出 summary CSV。

这次运行确认了两个现象。第一，启发式在 active SRI cut 后的批量出列明显下降：第一批 10 条 cut 后列池从 5264 增到 5853，第二批 cut 后从 5853 增到 5942；相比之前未接 SRI 的启发式在 cut 后继续大批量生成 100+ 列，当前已经不再是主要无效出列来源。第二，root 仍没有闭合，主要时间转移到 exact pricing 的反复证明上。`cuts=20` 后多轮 exact pricing 的 forward candidates 约 30 万到 38 万，`cbFPruned` 约 25 万到 32 万，backward pops 只有 18 到 20，常见结果是 `generated=0`，偶尔只有 reduced cost 很小的负列，例如 `-0.0699`、`-0.0144`。这说明当前瓶颈已经不是启发式 SRI 口径错误，而是 SRI cut 不限轮数导致 root 内反复 cut-and-price，且后期 exact pricing 每轮仍需要较重的 forward 证明。

当前结论是：启发式 SRI 修正方向有效，但默认 SRI separation 对 011/30 仍过重。若要把 SRI 用作实际求解配置，下一步更应该加总 active SRI cuts 或总 cut rounds 限制，或者先用每轮 1 条/更高 violation 阈值做对照；否则它可以验证 cut 机制，但不适合作为 30 任务默认主线配置。

关于 completion bound 的作用，本次 011 受控日志说明它仍然非常有用。107 次 exact pricing `finalize.done` 记录中，forward candidate 总数约 2438 万，`cbFPruned` 约 2073 万，平均每轮剪掉约 19.4 万，占 forward built candidate 的约 85%；后期 `cuts=20` 时单轮也常见 30 万级候选中剪掉 24 万到 29 万。也就是说，如果没有 completion bound，当前 SRI root 闭合会明显更重。当前问题不是 bound 没用，而是 SRI active 后使用的是 no-SRI 松弛 bound，安全但偏弱；同时 cut 不限轮数导致 dual 和列池反复变化，每次 still 需要做较重的 exact pricing 证明。

### 2026-06-13 每个 node 最多 10 条 SRI cut 的 root 对照

为避免 011/30 在 root 内无限 cut-and-price，本轮把 SRI cut 从“每轮最多 10 条”进一步限制为“每个 BPC node active SRI cut 总数最多 10 条”。实现上新增 `maxSubsetRowCutsPerNode`，`SubsetRowCutGenerator` 在分离前先统计当前 active subset-row triples；若达到上限则直接返回 `node cut limit reached`，否则本轮新增数量同时受 `maxSubsetRowCutsPerRound` 和剩余额度限制。`GCBBFullDomainComparisonTest` 同步增加 `twet.bpc.fullDomainCompare.maxSubsetRowCutsPerNode` 入口。

用三角化 `tmp-wet030_from040_011_2m` 做 root-only 对照，其他配置保持 partial-list ng-DSSR、ALNS seed、all-cycle completion bound、pricing-only subtree、midpoint probe、RMIH 关闭。无 SRI 基线为 `bound=13323.109589`，root solve `27.200s`，exact `4.934s`，列池 `5264`。开启 SRI 且每 node 最多 10 条 cut 后，root bound 提高到 `13413.526174`，提升 `90.416585`，相当于基线 bound 的约 `0.679%`；以 incumbent `13813` 计算，root gap 从 `3.5466%` 降到 `2.8920%`，减少约 `0.655` 个百分点。代价是 root solve 增至 `131.001s`，exact pricing `103.028s/15 calls`，列池 `5853`，cutPool `10`。

当前判断是：每 node 10 条 SRI cut 能明显抬高 root 下界，但代价较高，主要来自 cut 后 exact pricing 难度上升。相比不限轮版本，它至少能让 root 正常返回并进入 branching，因此更适合后续做完整树对照；但是否值得作为默认，还需要继续比较 `maxSubsetRowCutsPerNode=1/3/5/10` 或提高 violation 阈值后的单位时间 bound 提升。
### 2026-06-13 SRI 旧 VRP 对齐程度与慢因复核

本次重新按旧 `GCNGBB_C/GCNGB` 的核心代码复核，当前 TWET 的 SRI reduced-cost 口径与旧 VRP 主流程基本一致：扩展时只在某个 active SRI 的 distinct visit count 从 1 到 2 时加入 `-dual` penalty；join 时如果左右半路径都已经触发同一个 SRI，则去掉重复 penalty，如果左右各有一个不同 cut member，则补上拼接后新触发的 penalty；partial-list 和 single-point store 内部比较也已经复用旧 `UseSR` 思路做跨 SRI 状态补偿。换言之，当前慢不能简单归因于“没有把 SRI dual 算进去”。

但当前实现仍然不是旧 VRP 的完全等价版本。主要差异有两个。第一，旧 `GCNGB` 里明确有 `m_*sr_bound` 这类 SRI-aware bound 表，`GCNGBB_C` 也会在 bound labeling 后用 `UpdateFWBound/UpdateBWBound` 更新基础 bound；当前 TWET 只用 `noSriFrontier + all-cycle completion bound` 做安全剪枝，忽略未来 SRI penalty，因此下界安全但更弱。第二，TWET 的 label frontier 是分段线性函数，不是旧 VRP 的标量资源成本；SRI active 后只要窗口变大，partial-list 裁剪和函数拼接开销会被放大。

011 root 对照说明慢因很集中。无 SRI 时 root exact pricing 为 `4.934s/5 calls`，最后一次 exact 的 `pricingHorizon≈1027`、`dualWindow=enabled`、`forwardExtend candidates≈5506`、`boundSurvivors≈288`。每 node 最多 10 条 SRI 后，root exact pricing 变成 `103.028s/15 calls`；cut active 后 root dual profitable window 被关闭，`pricingHorizon` 直接回到 `3500`，后期 exact 单轮常见 `forwardExtend candidates≈154k-207k`、`boundSurvivors≈19k-27k`，partial-list comparisons 到 `1.1M-1.7M`，DSSR 轮数也从 no-cut 的 1 轮变成 9-14 轮。completion bound 仍然剪掉大量候选，例如 20 万级 candidate 中仍能剪掉 16 万左右，但 no-SRI 松弛 bound 已不足以把 cut 后的大域证明压回原来的规模。

因此当前结论是：SRI 的 reduced-cost、join、dominance 口径大体对齐旧 VRP；慢的主因是 SRI active 后关闭 root pi-window 导致定价域从约 1000 放大到 3500，再叠加当前没有旧 VRP 那套 SRI-aware bound 更新，导致 exact pricing 需要在更大时间域上反复证明没有负列。后续若要把 SRI 作为默认求解组件，优先方向不是继续微调 SRI dual 计数，而是测试更保守的 cut 策略，或补 SRI-aware completion bound / 重新设计 active-cut 下的安全 profitable window。
### 2026-06-13 011/30 不限制 SRI cut 次数的完整 root 收敛测试

按用户要求取消 SRI 加入次数限制后，重新跑三角化 `tmp-wet030_from040_011_2m`。配置为 partial-list ng-DSSR、`nearestK,size=8,top10`、ALNS seed、RMIH 4s、all-cycle completion bound、pricing-only subtree、midpoint probe，并把 `maxSubsetRowCutsPerRound/maxSubsetRowCutsPerNode` 都设为很大值，等价于不做人为 cut 条数截断。

这次运行在 root 直接闭合，结果为 `incumbent=bound=13511`，`gap=0`，状态 `ROOT_PROCESSED`，没有进入分支。root 总时间 `644.306s`，总求解时间 `644.310s`；pricing 共 `95` 轮，加入列 `7332`，最终列池 `7390`。其中启发式 pricing 为 `22.318s/74 calls/add6447`，exact pricing 为 `600.677s/21 calls/add885`，master LP 为 `17.282s`。SRI separation 只发生一轮，但一次性加入 `188` 条 cut，cutPool 最终为 `188`。

这说明不限制 SRI 时，011/30 的 root bound 能直接推到整数最优；但代价很高，主要来自 SRI active 后 root pi-window 被关闭，`pricingHorizon` 回到 `3500`，exact pricing 需要在更大的时间域上反复证明。后期虽然负列数量已经降到个位数甚至 0，但单轮 exact 仍可能需要十几秒到接近百秒。当前结论是：不限制 SRI 可以显著加强 root 下界，甚至本例 root 闭合；但作为默认求解配置过重，更适合做强 cut 对照或小批量验证。若要用于主线，应继续比较有限 cut 策略和不限制策略的单位时间收益。

### 2026-06-14 011/30 运行中 generated=0 后继续 initialize 的解释

复查 `tmp-011-all-components-uncapped-sri-20260613-235730` 日志和 `PC.solve()` / `GCNGBBStyleBidirectionalNgDssr.solve()` 控制流后，确认这里没有发现 cut/pricing 循环错误。日志中出现的“generated=0 后又 initialize”来自两种不同层级。

第一种是外层 price-and-cut 的正常行为。root no-cut pricing 收敛时，exact pricing 在日志第 38 行返回 `generated 0`，此时当前 LP 还不是整数解，所以 `PC.solve()` 随后进入 cut separation，第 40 行一次性加入 188 条 SRI cut。cut 改变 LP dual 和 reduced cost，因此第 90-95 行的 `after_cut` 重解 LP 后必须重新进入 pricing loop；否则 SRI dual 虽然已经读出，但没有被 pricing 用来闭合新的 reduced-cost 口径。这一轮重新 initialize 是正确的。

第二种是 ng-DSSR 内部的正常行为。一次 exact pricing 调用内，如果 relaxed ng pricing 没有 elementary 负列，但找到了 non-elementary negative route，代码会更新 ng-neighborhood 并重新跑下一轮 relaxed pricing。每一轮 relaxed pricing 都会调用一次 `initialize()`，直到找到 elementary 负列返回，或者某一轮 relaxed pricing 连 non-elementary negative route 也找不到。最终第 101 行的 `generated 0` 带有 `ng-DSSR reason=relaxed pricing found no negative route, rounds=8,totalNonElementaryRoutes=69,totalNgSetUpdates=83`，含义是前面 7 轮只发现非基本负 route 并更新 ng-set，第 8 轮证明 relaxed pricing 已经无负 route，因此整个 exact pricing 返回 no improvement。

从日志顺序看，第 101 行最终 `generated 0` 后没有再次进入 cut separation 或下一次 pricing，后面直接是 node summary 和 `ROOT_PROCESSED`。因此当前正确性判断为：`generated=0` 后继续 initialize 不是外层无限循环，而是 cut 后重定价和 DSSR 内部收紧 ng-set 的预期行为；最终 no-improvement 后节点正常结束。

### 2026-06-14 full-ng 初始化与 SRI 限制口径修正

本轮澄清了两个配置语义。第一，`full-ng` 指每个 job 的初始 ng-neighborhood 都包含所有任务点，而不是 `nearestK`。此前 `ngDssrInitialMode` 只支持 `empty/nearestK/dualPair/reducedCostPair`，现在补充 `full` 模式：初始化后对每个 `N_i` 加入 `1..n`。这种配置会让 ng-memory 初始不遗忘任何任务，更接近 elementary 口径，用于对照 ng relaxation 本身的影响。

第二，SRI cut 的限制应按旧 VRP 口径保留“每轮最多加 10 条”，而不应默认限制单个 node 内 active SRI cut 总数。此前为了避免 root 过重，临时把 `maxSubsetRowCutsPerNode` 默认设成 10；这会改变 unrestricted node cut 语义。现在默认改为 `Integer.MAX_VALUE`，即不限制单 node 总数；`maxSubsetRowCutsPerRound=10` 仍保留，命令行仍可显式传 `maxSubsetRowCutsPerNode` 做受控实验。

用三角化 `tmp-wet030_from040_011_2m` 做 `partial-list ng-DSSR + SRI + full-ng + maxNodes=1 + stageHeartbeat` 诊断。cut 前 root pricing 很快，`pricingHorizon` 约为 `1021-1023`，第一轮 exact 约 `fCand=20724, joinPairs=54784, generated=878`，后续几轮逐步降到 `generated=0`。第一次 separation 加入 10 条 SRI 后，active cut 使 root pi-window 关闭，`pricingHorizon` 直接回到 `3500`。随后的 exact pricing 单轮规模明显放大：`cuts=10` 时出现 `fCand=306122, joinPairs=7148224, generated=370`，后续仍有 `fCand=243256, joinPairs=5649043` 等；`cuts=20/30/40/50` 后仍继续 cut-and-price，单轮 forward candidate 常在 `20万-80万+`，join pairs 可达 `1000万-3000万+`。在 `cuts=50` 后某轮 forward 已到 `fCand=1358323`，队列仍未清空，因此中止。

当前结论是：full-ng 初始化本身不是这次慢的直接主因，真正放大来自 SRI active 后关闭 root pi-window，导致 pricing horizon 从约 1000 放大到 3500；同时不限制单 node SRI cut 总数会让 root 内持续追加 cut，每次 cut 后都要重新闭合更大的 pricing 问题。completion bound 仍在大量剪枝，但当前 no-SRI bound 对 SRI active 的大时间域只能保证安全，强度不够把证明规模压回 no-cut 水平。后续如果继续测试 unrestricted SRI，应优先加实时日志或限制运行时间；如果作为主要求解配置，仍需要比较 per-node cap、cut round cap、提高 violation 阈值或补 SRI-aware bound 的收益。
