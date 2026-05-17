# BPC 基础 RMP 与单向 Forward Pricing 实现记录

## 当前实现

本次先把能够真正运行的第一版 BPC 主链路搭起来，目标不是一次性完成完整 branch-price-and-cut，而是先形成“RMP 能解、pricing 能生成负 reduced cost 列、列能回到 RMP、结果能输出和校验”的闭环。当前实现参考 `parallel_machine_scheduling_with_due_window.pdf` 中 set-partitioning/SP2 的建模思路，以及前面讨论过的“每个 dominance graph node 保留真实 label 集合，同时维护一个聚合 envelope 用于集合占优”的方案；旧 VRP BPC 代码里的 `GC`、`UL/TL`、按末端节点组织 label、arc branching 这些结构也尽量沿用了相近的命名和流程。

RMP 现在由内部加工列 `lambda` 和外包变量 `y_j` 组成。覆盖约束写成“包含任务 j 的内部列之和 + `y_j = 1`”，机器数量约束仍然约束内部列数量，外包成本通过 `G(sum b_j y_j)` 的分段线性变量进入目标。这里的 tariff 分段目前按 LP relaxation 方式接入，主要服务于列生成中的对偶值和下界计算；如果后续要把外包分段选择做成严格整数结构，还需要在 B&B 层补额外分支或更强建模。

pricing 先实现了最基础的单向 forward labeling，不含 SRI cut、ng-route、DSSR、双向拼接和 partial dominance。`Label` 保存真实路径信息，包括末端任务、父 label、访问集合、可达集合和分段线性 frontier；这样后续生成列时可以恢复具体任务顺序。`DominanceNode` 内部保存多个真实 label，并额外维护这些 label frontier 的下包络；`DominanceGraph` 使用这个聚合 envelope 做完整集合占优。这个设计和原文中“graph node 上有一个聚合 label”的写法不同：我们不把多条真实路径物理合并成一条 label，因为 TWET 的 setup time、setup cost 和最终列恢复都依赖顺序。聚合函数只用于占优判断，真实 label 仍然保留。

当前 pricing 的扩展逻辑是：从父 label 的 frontier 出发，按 `setup time + processing time` 做 `shiftX`，再加上当前任务的 TWET 惩罚函数和 setup cost、job dual、arc dual 等常数项，然后按 forward 方向 normalize。任务自己的硬时间窗使用 `setDomain(start, end, true)` 处理，窗口外补成 `big_M` 段，而不是物理删除定义域。这一点沿用前面分段线性函数讨论得到的约束：label 函数尽量保持右端到全局 `T`，避免后续 merge/dominance 时产生多个内部单点或定义域断裂。

分支暂时只接入最简单的 arc branching。右分支要求选中的弧出现，并按旧 VRP `BranchD` 的思路禁止同一出点的其他出弧和同一入点的其他入弧；左分支禁止该弧。这个版本足够支撑第一版框架跑通，但还没有处理“只在外包变量或 tariff 分段变量上分数”的分支情况。如果 RMP 分数解没有可分支弧，当前树搜索会把它作为暂时无法继续分支的节点处理，后续需要补外包变量分支或机器数/列分支。

## 验证结果与边界

本次做了 focused compile，编译范围为 `src/Common`、`src/Basic`、`src/HEU`、`src/Output` 和 `src/TWETBPC`，编译通过，仅有旧 API deprecation 提示。没有跑全量 `src` 编译，因为旧 `src/BPC` 参考框架仍然和当前项目不完全兼容，直接纳入会引入无关失败。

另外构造了一个 60 任务、3 台机器、带 setup cost 和外包 tariff 的 smoke 数据，用 `TWETBPCSolver` 跑根节点列生成和结果输出。结果为 `ROOT_PROCESSED`，incumbent 为 `40317.0`，best bound 为 `40316.99999999999`，生成列数为 `335`。这说明当前 RMP、exact forward pricing、列池更新、输出和外包校验链路已经能跑通。

需要明确的是，这还不是完整 BPC。当前没有 SRI cut，没有 cut-aware pricing，没有 partial dominance，没有 bidirectional labeling，也没有 ng-route/DSSR 之类的松弛强化。分支方面也只有 arc branching；如果要求严格求完整 B&P 树，后续还需要补人工列/分支节点可行性兜底、外包变量分支、tariff 分段整数性处理，以及更完整的输出统计。

## 后续计划

下一步最自然的是继续完善 pricing 的质量，而不是马上堆 cut。可以先做三件事：第一，补 pricing 过程的详细统计输出，包括 label 数、dominance 删除数、返回列数和耗时；第二，给 required-arc 分支节点补人工列或更稳的节点可行性处理，避免 restricted master 在分支后因为暂时没有列而提前 infeasible；第三，再考虑把 dominance graph 从当前的完整占优推进到 partial dominance 或更高效的 graph propagation。

## 2026-05-17：CmaxH、硬时间窗与 pricing 剪枝补充

当前如果把某些任务的外包 baseline cost 设成 `big_M`，含义是这些任务不能被外包。RMP 会把对应 `y_j` 上界固定为 0；预处理粗硬窗里 `evaluateOutsourcingCost(big_M)` 也会返回 `big_M`，因此静态硬窗基本退化为 `[0,CmaxH]`，不会因为外包成本不可用而误裁任务。需要注意的是，pricing 里仍然会根据当前 cover dual 计算动态 `H_ij`，所以“外包不可用”只是不使用外包成本收缩静态窗口，并不等于 pricing 完全没有时间窗剪枝。

本次把 `Data` 里的静态 horizon 做了一点收紧：`setPreprocessedHardWindows()` 计算完每个任务的粗硬窗以后，如果所有 `hardWindowEnd[j]` 的最大值小于当前 `CmaxH`，就把 `CmaxH` 收到这个最大右端，然后再构造 job penalty function。这个处理只利用静态外包 baseline 和 setup-cost advantage，不使用 pricing dual。这样做的原因是，窗口外本来已经会被写成 `big_M` 段，不再是有效内部加工完成时间；提前缩短全局 horizon 可以减少后续分段函数和 label 扩展维护的无效尾段。

pricing 中也补了直接时间可行性过滤。之前的逻辑是先尝试 `shiftX + setDomain + add + normalize`，如果结果为空或最小值进入 `big_M` 状态再丢弃。现在在做分段函数操作之前，会先用当前 label frontier 的最早可行完成时间、`setup time + processing time` 和动态 `H_ij` 的右端判断扩展是否必然不可行；同时新 label 的 `reachableSet` 构造也使用同一判断，把明显不可能的下一任务提前排除。这个过滤不替代分段函数求值，只是减少必然失败的扩展。

暂时没有在每轮 pricing 中根据 dual 动态更新全局 `CmaxH`。这样做理论上可能进一步缩小函数定义域，但会带来一个麻烦：同一列生成阶段的全局 horizon 可能随 dual/节点状态变化而变化，容易和分段函数的 `[a,T]` 右端统一假设冲突。当前更稳的做法是保留全局 `CmaxH`，只在扩展 `i -> j` 时动态计算局部 `H_ij`。

## 2026-05-17 BPC 当前实现的效率与严格性边界

当前确认 pricing 阶段暂时不再动态修改全局 `CmaxH`。`Data` 里已经用静态粗硬窗做过一次 horizon 收紧；进入列生成后，`GC` 只在每条扩展弧 `i -> j` 上根据当前 dual、任务外包 baseline 和 setup-cost advantage 计算局部 `H_ij`，再用 `setDomain(hStart,hEnd,true)` 把任务函数窗口外写成 `big_M`。这样做比每轮修改全局 `CmaxH` 保守一些，但可以保持分段函数统一的右端 `T` 结构，避免不同 RMP dual 下反复改变全局定义域带来的边界问题。后续如果要进一步用 dual 收紧 horizon，更适合先作为扩展级别或 label 级别的局部剪枝，而不是直接覆盖 `data.CmaxH`。

`DominanceGraph` 当前实现是正确优先、效率其次。插入新 label 时，会扫描当前 terminal job 下所有 node，筛出 reachable set 是当前 reachable set 超集的 eligible node，把这些 node 的 aggregate envelope 逐个 merge 成一个总 envelope，再判断是否完整支配新 label。若新 label 没被支配，就插入对应 reachable-key node；随后再扫描其他 node，看新加入的信息是否能删除后继 node 或其中的部分真实 label。这个逻辑符合当前“完整 set dominance + 保留真实 label 集合”的设定，但每次插入都会重复扫描 eligible node 和重复 merge envelope，后续 label 数多时会明显变慢。可以优化的方向是给 dominance graph 增加 ancestor/descendant 缓存，或者按 reachable set 的包含关系维护索引，避免每次都全表扫描。

`reachableSet` 现在只做一跳可达性过滤。具体来说，`buildReachableSet` 只检查候选任务是否未访问、当前弧是否未被禁止、以及从当前 frontier 最早完成时间直接扩展到该任务是否不超过局部 `H_ij` 和 `CmaxH`。它没有做多步可达性、time-index reachability，也没有接入 Vidal 式子序列摘要。这个设计不会漏掉可行列，因为它只是提前排除直接扩展已经明显不可能的任务；但会保留很多后续几步才发现不可行的候选，因此主要影响效率，不是当前正确性风险。

列生成停止条件目前还不够“证明友好”。`GC.solve()` 单轮最多返回 `maxExactPricingColumns` 条负 reduced-cost 列；`PC.solve()` 单节点最多执行 `maxPricingRounds` 轮 pricing。如果某一轮达到列数上限，或者总轮数耗尽时实际上仍存在负 reduced-cost 列，当前 `PricingResult` 只会告诉上层“本轮返回了若干列/没有列”，不会显式标记“因为上限停止”。这在工程上能跑，但严格 B&P 证明需要知道列生成是否真正定价完毕。后续应给 `PricingResult` 增加 stoppedByColumnLimit、stoppedByRoundLimit 或类似状态，并在节点 bound/关闭逻辑和输出里明确标记。

当前 pricing 输出统计也偏粗。`PC` 只记录 pricing 是否 improved、加了多少列、pool size 和文字 message；`GC` 内部没有暴露生成 label 数、扩展尝试数、时间可行性剪枝数、dominance 删除数、重复列过滤数、返回列数和耗时。后续要分析 BPC 性能时，这些统计很关键，尤其是 dominance graph 和 reachableSet 优化之前，需要知道瓶颈到底在 label 扩展、分段函数操作、dominance merge 还是 RMP 重解。

分支层还有几个严格性边界。第一，required-arc 分支节点下，`LP` 会把 required arc 写成等式约束，要求当前 RMP 中选中的列必须覆盖该弧；但如果 restricted pool 里暂时没有任何包含该弧的列，RMP 可能直接 infeasible。理论上 pricing 有机会生成满足 required arc 的列，但前提是 RMP 能先求出 dual；因此后续需要人工列、定向 pricing 兜底，或在构造节点时先补一批满足 required arc 的列。第二，当前只有 arc branching；如果分数性主要来自外包变量 `y_j` 或 tariff segment 变量，而内部列的 arc 值已经整数，`ArcBrancher` 会返回没有可分支弧，树就会停在 `closed_without_branch`。后续需要增加外包变量分支、tariff segment 分支，或者对机器数/列变量的补充分支策略。

外包 tariff 当前在 RMP 中仍是 LP relaxation。做法是用 `outsourceSegmentActive` 和 `outsourceSegmentBaseline` 表示 `G(sum b_j y_j)` 的分段线性结构，并且这些变量都是连续变量。如果 `G` 是凹分段函数，这个松弛可以作为下界，但不等于完整整数建模；分段选择可以被分数化，从而给出比真实整数外包成本更低的 master bound。当前第一版先接受这个作为松弛，后续如果要严格求整数最优，需要补 segment 选择的分支、SOS2/特殊有序结构，或其他强化约束。

动态 `H_ij` 的公式目前能跑，但还需要单独对拍。当前 `gamma = setupCostAdvantage(i,j) + min(coverDual_j, outsourcingBaseline_j)`，再按 earliness/tardiness 权重转成局部完成时间窗口。这个思路来自“如果某个完成时间已经比外包或 dual 能解释的上界更差，就不值得扩展”的剪枝逻辑。风险在边界情况：cover dual 为负时，`gamma` 可能为负并导致窗口变窄甚至为空；任务不可外包时 baseline 是 `big_M`，公式退化为主要由 dual 控制；setup-cost advantage 很大时，窗口会被放宽。当前这些情况没有系统小算例对拍，因此后续应专门构造 dual 为负、外包不可用、setup-cost advantage 较大的 pricing 单元测试，验证不会误删真实负 reduced-cost 列。
