# 2026-05-21：单向 Exact Labeling 与旧 VRP GC 对比

本次只比较当前 TWET-BPC 中单向 exact labeling 和旧 VRP 代码 `BPC.GC.GC` 的流程、数据结构和效率框架。不讨论 TWET 分段线性函数、setup time/cost、动态硬时间窗这些问题特征导致的计算差异，也不讨论 SRI cut、ng-route、DSSR 或双向 labeling。

主要结论是：当前单向 `GC` 的主流程和旧 VRP 的最简单 `GC` 是一致的，都是 `UL` 优先队列加按终点组织的 `TL`，从 source label 初始化，循环弹出 label，跳过已被占优的 label，尝试生成负 reduced-cost 列，再枚举后继节点扩展，新 label 经过占优结构后才入队。列数量控制也沿用旧 VRP `addin_size` 的语义，即单轮最多返回一定数量的负 reduced-cost 列，真正是否结束由外层 PC 重解 LP 后继续 pricing 判断。

两者最大的不同在占优结构。旧 VRP 的最简单 `GC` 在 `TL[cid]` 中直接保存 label 列表，`IsDominate()` 对同终点 label 做线性扫描，用 reduced cost、weight、time 和 reach bit mask 判断支配关系。当前 TWET 单向 `GC` 把 `TL[j]` 抽象成 `DominanceStore`，可以用全量扫描版 `DominanceGraph`，也可以用 `PaperDominanceGraph`。默认配置关闭双向时会优先走 `PaperDominanceGraph`，这是按 `parallel_machine_scheduling_with_due_window.pdf` 的 dominance graph 思路组织 reachable-set 包含关系，不是旧 VRP 最朴素的 flat list。这样做是之前为了支持 set dominance 和函数 envelope 占优，保留真实 label 集合用于恢复路径，同时用聚合 envelope 做支配判断。流程上仍是旧 VRP 的“插入前先判占优，插入后传播删除”，但数据结构更复杂，理论上减少全量扫描，实际会有维护 graph、合并 envelope 的额外开销。

可达集合的思想是一致的，但实现层次不同。旧 VRP 用两个 `long` mask 表示 visit/reach，并在 `UpdateReach()` 中按容量、时间窗、已访问状态把不可达点标记为不可达；扩展时用 `CheckReach()` 和 `node.feasible_arc` 过滤。当前 TWET 使用 `PackedBitSet` 封装 long 数组，支持超过 64 个任务；每个 label 保存 `visitedSet` 和 `reachableSet`，扩展时先查 `visitedSet`、`reachableSet` 和 forbidden arc，再进入函数扩展。效率上，旧 VRP 的裸 long 位运算常数更小；当前封装可读性和规模适应性更好，且 bitset 仍然是同一类紧凑集合表示。

列生成时机也基本一致。旧 VRP 在扩展到 sink/customer number 时，如果 reduced cost 为负，就通过 father 指针恢复 route，查 pool 去重，再加入本轮 `gn_index`。当前 TWET 没有显式生成 sink label，而是在每个非 source label 出队时检查它是否可以接 sink，并先用 `label.minReducedCost - arcDual(last,sink)` 判断是否可能为负；通过后才恢复真实序列、查 active signature 和本轮 signature，再返回给 PC。这里属于同一流程的不同封装：旧代码把 sink 当成普通扩展点，当前代码把“接 sink 成列”放在 label 出队阶段。

去重逻辑当前比旧 VRP 多了一层 active-column 跳过。旧 VRP 是 route 进入本轮加列数之前先查全局 pool，重复 route 不消耗 `addin_size`。当前 TWET 在 `GC.initialize()` 收集当前 RMP active column signature，`tryGenerateColumn()` 先跳过 active 重复，再跳过本轮重复；如果列在全局 pool 中已有但当前节点尚未 active，则仍允许返回给 PC 激活。这一点和旧 VRP 的“重复不消耗本轮返回上限”方向一致，是效率和停止条件稳健性的增强，不是流程差异。

从效率角度看，当前单向 exact labeling 已经保留了旧 VRP 的几个关键框架：优先队列按 reduced-cost 下界弹出、label 自带 father 恢复路径、label 自带 reachable set 做扩展过滤、被占优 label 只做标记并在出队时跳过、单轮返回列数按旧 `addin_size` 控制。剩余差异主要是当前 dominance graph 和分段函数 envelope 带来的对象与函数操作开销，这属于 TWET 问题适配和论文 dominance graph 选择，不是单向 labeling 框架偏离旧 VRP。

当前还需要注意一个配置事实：`TWETBPCConfig.enableBidirectionalPricing=true` 时，正式 exact pricing 会使用双向 pricing engine，而不是这里分析的单向 `GC`。只有关闭双向时，才会按 `usePaperDominancePricing` 选择 `PaperDominanceExactPricingEngine` 或普通 `ExactPricingEngine`。因此本次结论针对“单向 exact pricing 实现本身”，不是当前默认 BPC 运行时一定使用的 exact engine。

当前判断：在不考虑 cut 和 TWET 特有函数评估的前提下，单向 exact labeling 的流程与旧 VRP 最简单 `GC` 已经基本一致；主要差异是有意引入的 paper dominance graph 与 `PackedBitSet` 封装。它们不会改变列生成逻辑，只影响占优组织方式和常数效率。后续如果要做严格性能对比，可以通过关闭 `enableBidirectionalPricing`，再切换 `usePaperDominancePricing=true/false`，直接比较 paper dominance graph 和全量扫描 dominance graph 的标签数、占优删除数、pricing 耗时。
