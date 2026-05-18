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

## 2026-05-18：启发式定价改为旧 VRP GCTabu 框架

本轮按用户要求把启发式定价进一步向旧 VRP 的 `GCTabu` 框架靠齐。旧代码的核心流程不是替代 exact pricing，而是在 exact pricing 前从当前 RMP 里挑一批低 reduced cost route 作为 seed，然后围绕 seed 做 remove/add/exchange tabu 搜索，形成一个本地负 reduced-cost route pool，排序后只加入少量优质列。当前 TWET 版本沿用这个框架：先收集当前 restricted columns，按 reduced cost 排序取 `heuristicPricingSeedColumns=30` 条种子，对应旧 `m_tabu_cg_size`；每条种子运行 `heuristicPricingTabuIterations=50` 轮 tabu，对应旧 `m_tabu_cg_iteration_number`；tenure 为 `heuristicPricingTabuTenure=30`，对应旧 `m_tabu_cg_tenure`；本地负 reduced-cost 候选池上限为 `heuristicPricingPoolSize=1000`，对应旧 `m_gen_size`；最后排序返回 `maxHeuristicPricingColumns=150` 条，对应旧 `addin_size`。邻域包括删除一个任务、插入一个未访问任务、用未访问任务替换当前任务。

和旧 VRP 不能完全照搬的地方在于 move 评价。旧 VRP 的 route cost 基本是弧成本、容量和硬时间窗状态的增量更新；TWET 的列成本来自分段线性时间惩罚、setup time、setup cost 和硬时间窗预处理，不能直接用旧的局部弧成本公式。因此这次没有再用全序列 `TWETColumnEvaluator` 重算每个候选，而是给每条 seed 建立 forward/backward 分段函数 profile。删除任务时用两段拼接 `merge2Segments` 评价；插入或替换任务时把新任务看作单点 segment，用三段拼接 `merge3Segments` 评价；setup cost 在桥接弧上显式补入。这就保留了旧 `GCTabu` 的搜索结构，同时把候选成本评价换成当前 TWET 启发式中已经验证过的分段函数拼接逻辑。

这里仍然只是启发式加速层，不承担最优性证明。候选列会先检查当前分支节点的 forbidden arc 状态，并跳过当前 RMP 已 active 的重复序列；如果找到负 reduced-cost 列就返回给 `PC`，后面 exact forward pricing 仍会继续兜底。因此它找不到列不会影响严格性，最多影响速度。当前实现还没有把旧 VRP 里更完整的 slack RMP + `FindFeasible` 定向 pricing 复制进来；required-arc 子节点现在仍主要依赖 pool 筛列和 fallback column，复杂分支状态下还需要后续继续增强。

验证方面，本轮做了 focused compile，范围为 `src/Common`、`src/Basic`、`src/HEU`、`src/Output` 和 `src/TWETBPC`，编译通过，仅有旧 API deprecation 提示。随后运行 `TWETBPC.GC.PaperDominanceGraphConsistencyTest`，结果为 `cases=200, insertions=16000` 通过；运行 `PWLFTesting.PiecewiseLinearFunctionPropertyTest`，程序正常结束，保留既有的 PWLF 警告/失败计数，没有出现本轮新增编译或运行错误。另用临时 smoke 程序构造 7 任务、2 机器小算例跑 `TWETBPCSolver`，结果为 `ROOT_PROCESSED, incumbent=23.66, bound=23.660000000000004, columns=21`，说明启发式定价接入后 BPC 主链路仍能跑通。

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

列生成停止条件在第一版中曾经不够“证明友好”。当时 `GC.solve()` 单轮最多返回 `maxExactPricingColumns` 条负 reduced-cost 列，`PC.solve()` 单节点最多执行 `maxPricingRounds` 轮 pricing；如果轮数耗尽时实际上仍存在负 reduced-cost 列，就会影响节点下界的严格性。后续已经按旧 VRP 代码思路删掉固定轮数上限，`PC` 改为迭代到没有新 active column 为止；单轮加列数上限仍保留在 pricing 侧，用于控制每次返回列的数量。

当前 pricing 输出统计也偏粗。`PC` 只记录 pricing 是否 improved、加了多少列、pool size 和文字 message；`GC` 内部没有暴露生成 label 数、扩展尝试数、时间可行性剪枝数、dominance 删除数、重复列过滤数、返回列数和耗时。后续要分析 BPC 性能时，这些统计很关键，尤其是 dominance graph 和 reachableSet 优化之前，需要知道瓶颈到底在 label 扩展、分段函数操作、dominance merge 还是 RMP 重解。

分支层还有几个严格性边界。第一，required-arc 分支节点下，`LP` 会把 required arc 写成等式约束，要求当前 RMP 中选中的列必须覆盖该弧；但如果 restricted pool 里暂时没有任何包含该弧的列，RMP 可能直接 infeasible。理论上 pricing 有机会生成满足 required arc 的列，但前提是 RMP 能先求出 dual；因此后续需要人工列、定向 pricing 兜底，或在构造节点时先补一批满足 required arc 的列。第二，当前只有 arc branching；如果分数性主要来自外包变量 `y_j` 或 tariff segment 变量，而内部列的 arc 值已经整数，`ArcBrancher` 会返回没有可分支弧，树就会停在 `closed_without_branch`。后续需要增加外包变量分支、tariff segment 分支，或者对机器数/列变量的补充分支策略。

外包 tariff 当前在 RMP 中仍是 LP relaxation。做法是用 `outsourceSegmentActive` 和 `outsourceSegmentBaseline` 表示 `G(sum b_j y_j)` 的分段线性结构，并且这些变量都是连续变量。如果 `G` 是凹分段函数，这个松弛可以作为下界，但不等于完整整数建模；分段选择可以被分数化，从而给出比真实整数外包成本更低的 master bound。当前第一版先接受这个作为松弛，后续如果要严格求整数最优，需要补 segment 选择的分支、SOS2/特殊有序结构，或其他强化约束。

动态 `H_ij` 的公式目前能跑，但还需要单独对拍。当前 `gamma = setupCostAdvantage(i,j) + min(coverDual_j, outsourcingBaseline_j)`，再按 earliness/tardiness 权重转成局部完成时间窗口。这个思路来自“如果某个完成时间已经比外包或 dual 能解释的上界更差，就不值得扩展”的剪枝逻辑。风险在边界情况：cover dual 为负时，`gamma` 可能为负并导致窗口变窄甚至为空；任务不可外包时 baseline 是 `big_M`，公式退化为主要由 dual 控制；setup-cost advantage 很大时，窗口会被放宽。当前这些情况没有系统小算例对拍，因此后续应专门构造 dual 为负、外包不可用、setup-cost advantage 较大的 pricing 单元测试，验证不会误删真实负 reduced-cost 列。

## 2026-05-17：对照论文伪代码和旧 VRP 代码后的修正判断

这里需要修正前一段分析里对 dominance graph 的表述。当前 `DominanceGraph` 能作为保守的完整占优实现使用，但它不是 `parallel_machine_scheduling_with_due_window.pdf` 里写的 dominance graph 伪代码。论文里的图不是每次全量扫描所有 eligible node，而是维护 reachable set 包含关系图：每个 node 对应一个 reachable set，保存本节点真实 label 集合的下包络 `f_u`，同时维护来自直接前驱传播下来的 `h_u` 和综合支配包络 `g_u=min(f_u,h_u)`。新 label 到来时，先通过 roots/successors 找到 terminal candidate nodes `R(L)`，用这些节点的 `g_u` 判断支配；插入新 node 后，只沿 successors 做局部传播和删除。也就是说，论文的核心是“用图结构把全量 eligible 扫描变成沿包含关系的局部查找和传播”。

当前代码的做法更简单：`insertOrDominate()` 里直接扫描当前 terminal job 下所有 node，筛出 reachable set 是当前 reachable set 超集的 node，把这些 node 的 aggregate envelope 全部 merge 成一个 envelope，再判断是否完整支配新 label。插入后，`propagateAfterInsert()` 又循环扫描其他 node，看新信息能否支配它们。这个逻辑不应该丢最优解，因为它只做完整支配，没做 partial dominance；但它和论文伪代码不一样，效率上也不对。后续要按论文重构，需要补 `nodeByReachableSet`、roots、predecessors、successors、`f_u/h_u/g_u`，以及 `FindTerminalNodes`、`InsertOrMerge`、`PropagateAndTrim` 三套流程。因为 TWET 列需要恢复具体顺序，node 里仍然要保存真实 label 集合；论文里的聚合 label 在这里应理解为支配判断用的 envelope，而不是替代真实路径。

`reachableSet` 这一点目前不需要扩大。论文里更新 reachable set 本身就是一跳过滤：从当前末端 job 出发，检查某个候选 job 是否未访问、弧是否允许、直接接上以后是否还可能落在对应的 `H_ij` 内。当前 `buildReachableSet()` 做的也是这一类一跳判断。多步可达性、time-index reachability 或 Vidal 式摘要都只是后续加速，不是这里必须补的正确性条件。

列生成停止条件已按这个判断修正。旧 VRP 代码里的 `addin_size` 对应的是“单轮最多加入多少列”，这和当前 `maxExactPricingColumns` 类似；但旧 `PC` 没有 `maxPricingRounds` 这种节点内固定轮数上限。当前实现已经删除固定轮数上限，节点内流程改为 heuristic pricing 和 exact pricing 反复跑，直到本轮没有新的 active column 被加入。这里仍要保留一个工程细节：如果 pricing 找到的是重复列，`Pool` 会返回已有列 id，`PC` 不把它计入新 active column，避免无意义空转。

分支后 restricted master infeasible 的处理也要参考旧 VRP 代码。旧 `BranchD.UpdateRouteSet()` 在子节点没有可行列时，不是直接关闭节点，而是先用 slack 暂时保持分支约束可处理，然后调用启发式和精确定价的 `FindFeasible` 生成满足该分支状态的 route，再从这些 route 中筛选一批 reduced cost 较好的列作为子节点初始列。当前 TWET 版本只把 required arc 写成 RMP 等式，如果当前 restricted pool 里没有包含该 required arc 的列，RMP 可能还没来得及 pricing 就直接 infeasible。因此后续需要做一个子节点可行列兜底：可以先做定向 pricing/构造列，强制满足 required arcs；或者临时人工列/slack 保持 RMP 可解，再用 pricing 替换。直接把这种节点判 infeasible 不符合旧代码思路，也可能错误剪掉可行分支。

外包变量分支可以先不做，但要分清两个层次。如果所有内部调度 arc 值都已经是整数，那么在覆盖约束 `internalCover_j + z_j = 1` 下，`z_j` 通常会自动变成 0 或 1：内部列对 job 的覆盖是 0/1，arc 整数意味着内部路径选择结构已经整数，任务要么被内部列覆盖，要么只能由外包变量补足。因此 arc 分支先做完以后，`z_j` 大概率会随之整数化。这里先不单独对外包变量分支是可以接受的。

但 `z_j` 整数不代表 tariff 的分段变量也会整数。当前 RMP 里的 `outsourceSegmentActive` 和 `outsourceSegmentBaseline` 是连续变量，它们表达的是 `G(sum b_j z_j)` 的 LP relaxation。即使 `q=sum b_j z_j` 已经固定为整数 baseline，总量仍然可能被分数地分配到多个 tariff segment 上；如果 `G` 是凹分段函数，这个松弛可能给出比真实单段选择更低的外包成本。因此 tariff segment 是否整数，不能靠 `z_j` 整数自动保证。后续如果要求严格整数外包成本，需要加 segment 选择分支、SOS/特殊有序结构，或者其他强化建模。

关于动态 `H_ij`，当前实现和论文公式在结构上是一致的：`gamma = B_ij + min(π_j,b_j)`，再用 earliness/tardiness 斜率把它转成 `[h_ij,\bar h_ij]`，扩展时用 `shiftX(s_ij+p_j)`、任务惩罚函数窗口外补 `big_M`、再加 setup cost、job dual 和 arc dual。只要 dual 符号、`B_ij`、任务惩罚函数和外包 baseline 的定义没有错，这个部分不应因为实现方式本身丢失最优列。后续需要对拍的是公式输入值是否一致，而不是把 `H_ij` 逻辑本身当作优先怀疑对象。

## 2026-05-17：本轮 BPC 框架修正落地

本轮按前面的修正判断先做四件事。第一，保留原来的 `DominanceGraph` 作为全量扫描版，同时新增 `PaperDominanceGraph` 和 `PaperDominanceExactPricingEngine`。新图结构按论文伪代码维护 reachable set 的包含关系，显式保存 roots、predecessors、successors，以及每个 node 上的 `f_u/h_u/g_u` 三类 envelope；但为了适配 TWET 列需要恢复具体任务顺序的事实，每个 node 仍保存真实 label 集合，聚合 envelope 只用于占优判断，不替代真实路径。这样后续可以直接比较“旧全扫版”和“论文图传播版”的效率差异。

第二，删除节点内固定 pricing 轮数上限。`PC.solve()` 现在不再使用 `maxPricingRounds`，而是反复调用 pricing，直到本轮没有新的 active column 被加入为止；单次 exact pricing 返回列数仍由 `maxExactPricingColumns` 控制，默认值改为旧 VRP 代码 `Configure.addin_size` 对应的 150。这里要注意一个细节：如果 pricing 返回的是 pool 里已经存在的重复列，`PC` 不再把它当成新列，否则可能出现“有 improved 但没有新 active column”的空转。

第三，补了 SP2 tariff segment 分支。`TWETMasterSolution` 现在会保存 master 中 `outsourceSegmentActive` 的 LP 值；`TariffSegmentBrancher` 会寻找最接近 0.5 的分数 segment 变量，生成 `z_s <= 0` 和 `z_s >= 1` 两个子节点。对应的分支状态保存在 `Node` 中，`LP` 重建变量时通过调整 `outsourceSegmentActive[s]` 的上下界实现约束。该分支只作用在 master 变量上，不改变 private-route pricing 结构，只通过新的 dual 间接影响后续定价。

第四，分支子节点加入了初始列兜底。`Tree` 在把子节点放进队列前，会先从全局 pool 中筛选所有兼容 forbidden arc 的列作为 seed；如果子节点存在 required arc 且当前 seed 中没有任何列覆盖它，就尝试根据 required arc 链构造一条简单 fallback column，并用 `TWETColumnEvaluator` 评估后加入 pool。这不是旧 VRP `UpdateRouteSet()/FindFeasible` 的完整复刻，因为它还没有调用定向 pricing 去系统生成满足分支状态的列；但它已经避免了一类最直接的问题，即 required arc 子节点因为 restricted pool 暂时没有覆盖该弧的列而立刻 RMP infeasible。

当前仍需保留的边界有三点。第一，`PaperDominanceGraph` 只是按论文结构重写了 graph propagation，仍然只做完整占优，不做 partial dominance。第二，required-arc fallback column 只是工程兜底，不是严格的分支节点可行列生成器，后续如果遇到复杂 required arc 组合，仍应补定向 pricing 或人工列/slack。第三，tariff segment 分支已经接入，但外包任务变量 `y_j` 本身暂时没有单独分支；当前优先级是先处理 tariff segment 分数性，然后机器数分支，再 arc 分支。若后续观察到 `y_j` 分数但无可分支 arc，需要再补外包变量分支。
## 2026-05-17：当前 BPC 实现复核结论

本次对照旧 VRP 代码中的 `PC/GC/BranchD` 主流程，以及 `parallel_machine_scheduling_with_due_window.pdf` 里的 SP2、forward labeling 和 dominance graph 思路，对当前 `TWETBPC` 实现做了一轮结构复核。总体判断是：当前版本作为“不带 cut、单向 forward exact pricing、带外包 tariff 分支雏形”的第一版 B&P 骨架是能跑通的，RMP 的覆盖约束、机器数约束、外包 `G(B)` 的 LP relaxation、列池、定价返回列、tariff segment 分支、arc branching 的基本接口都已经接上。但它还不能称为完整严格的 BPC，主要风险集中在 pricing 停止条件、cut 接入、分支子节点可行列兜底和 dominance graph 的等价验证上。

最需要优先处理的是 pricing 的“返回列上限”和全局列池去重之间的关系。当前 `GC` 单轮最多返回 `maxExactPricingColumns=150` 条负 reduced-cost 列，但去重只在本轮内部做；如果这些列已经存在于全局 pool 或已经 active，`PC` 会发现没有新的 active column，然后停止列生成。旧 VRP 代码是在把 route 计入本轮 add-in 数量之前就检查 `pool.Route2ID(route)==-1`，也就是说重复列不会消耗本轮加列上限。当前实现如果遇到大量重复负 reduced-cost 列，有可能在还存在未发现新列时提前停下。这不是 smoke test 一定会触发的问题，但从严格列生成逻辑上看需要修：要么让 pricing 能查询全局 pool 并在计数前跳过已知列，要么在 `PricingResult` 里显式返回“是否达到列数上限、是否完整搜索结束”的状态，让 `PC` 不把“没有新增 active column”简单等同于“没有负 reduced-cost 列”。

cut 相关目前还是框架占位，不是可用的 BPC。`LP.addCuts()` 现在只把 cut id 加进 `activeCutIds`，`buildModel()` 没有真正把 cut pool 里的 cut 写成 CPLEX 约束；`PC` 在 cut loop 里即使将来加了 cut，也只是 re-solve LP，没有像旧 VRP `PC` 那样在 cut 加入后回到 pricing。严格的 branch-price-and-cut 必须在“加 cut 后重新 pricing”，否则 cut 的 dual 不会进入 pricing，RMP 也不完整。当前 cut generator 基本是 no-op，所以现阶段的无 cut B&P 运行不受影响，但这块在接 SRI 或其他 cut 前必须补齐。

分支子节点的可行列兜底也还不够强。当前 `Tree` 在生成 child 时会从全局 pool 里筛选兼容列，并尝试为缺失的 required arc 构造一条简单 fallback column；这比完全不兜底好，但还没有达到旧 VRP `BranchD.UpdateRouteSet()` 的强度。旧代码会通过 slack/启发式/精确定价去为分支节点找可行 route，避免 restricted master 因暂时没有满足 required arc 的列而直接 infeasible。TWET 这里 required arc 可能需要前后插入其他任务才能形成可行序列，简单 required-chain fallback 不一定够。因此后续应补定向 pricing 或人工列/slack 机制，避免误关可行分支节点。

`PaperDominanceGraph` 的方向是合理的：它保留真实 label 集合用于恢复具体列，同时维护 `f_u/h_u/g_u` 这类 envelope 用于集合占优判断，这比把多条真实路径物理合并成一条 label 更适合 TWET，因为 setup time、setup cost 和最终列的 arc pattern 依赖顺序。但这部分逻辑复杂，当前还缺少和旧全扫描 `DominanceGraph` 的等价对拍。后续至少应该在小算例上同时跑 full-scan dominance 与 paper graph dominance，比较生成负列、根节点 bound 和最终列池，确认 graph propagation 没有过度删除 label。

其他边界问题相对次一级。`Tree.solve()` 目前的 status 对 node limit 和 open queue 不够精确，`processedNodes > 1` 基本就返回 `FINISHED`，不适合正式汇报；`incumbentCostFromInitial()` 在没有 `bestSolution` 时只汇总内部列，可能忽略外包初始值，虽然正常 seed 流程通常能提供 bestSolution，但这个兜底不够严谨。focused 编译已经通过，命令范围为 `src/Common, src/Basic, src/HEU, src/Output, src/TWETBPC`，只出现旧 API deprecation 提示。本次没有修改源代码，只记录审查结论。
## 2026-05-18：pricing 上限、required-arc 兜底和状态汇报修正

本轮先处理无 cut B&P 里能直接改好的三件事，cut 接入暂时不动。第一，`GC` 的列返回上限现在不再被当前 RMP 已经 active 的重复列消耗。做法是在 pricing 初始化时收集 `lp.getRestrictedColumnIds()` 对应的序列签名，`tryGenerateColumn()` 恢复出候选序列后先判断它是否已经 active；如果已经 active，就直接跳过，不计入 `generatedColumns.size()`。如果某条列在全局 pool 中存在但还没有 active，仍允许返回给 `PC`，由 `PC` 激活已有列。这和旧 VRP 代码“先查 pool，再计入 addin_size”的思路一致。这个改动主要是效率和停止条件稳健性优化；正常情况下它只是减少重复返回列，严格场景下可以避免“返回列上限被重复列耗尽”后误以为没有新列。

第二，required-arc 子节点的 fallback column 做了增强。原来只根据 required arc 的连通链构造一条裸序列，如果这条裸序列不可行，就没有进一步兜底。现在会先构造 required arc 所在的强制链，然后尝试把这条链插入全局 pool 中已有列的不同位置，只要不重复 job、满足当前节点 forbidden arc 状态、确实覆盖该 required arc，并且 `TWETColumnEvaluator` 评估不是 `big_M`，就可以作为子节点 seed column。这个做法仍然不是旧 VRP `UpdateRouteSet()/FindFeasible` 的完整复刻，因为它没有做定向 pricing，也没有人工 slack；但它已经比裸链更接近“先尽量为 required-arc 分支节点补一条可行列”的思路，能减少 restricted master 因暂时缺列而误 infeasible 的概率。

第三，树搜索最终状态不再只靠 `processedNodes` 粗判。新增 `TWETSolveStatus.NODE_LIMIT`，当达到 `config.maxNodes` 且队列中仍有未处理节点时返回该状态；如果只处理了根节点且队列已空，仍返回 `ROOT_PROCESSED`；处理多个节点并正常清空队列时返回 `FINISHED`。这只是结果汇报语义修正，不改变搜索过程。

关于 `PaperDominanceGraph`，本轮没有进一步改。当前判断是：结构方向是对的，它保留真实 label 集合，同时维护 node envelope 用于集合占优，符合 TWET 需要恢复具体序列的要求；但还没有通过和全扫描 `DominanceGraph` 的系统对拍来证明完全无误。因此它现在是“可继续使用并重点测试”的实现，不是已经严格验证完的实现。后续应补一个小算例对拍：同一实例分别用全扫描 dominance 和 paper graph dominance，比较根节点 bound、生成列集合和最终 RMP 结果。

## 2026-05-18：启发式定价、分支补列和 PaperDominanceGraph 对拍

本轮继续对照旧 VRP 代码里的 `GCTabu`、`BranchD.UpdateRouteSet()` 和当前 TWET 的 forward pricing 做了补充。旧 `GCTabu` 的核心流程不是替代精确定价，而是在 exact pricing 前先从当前 RMP 里挑一批低 reduced cost route，围绕这些 route 做 remove/add/exchange 邻域搜索，形成一个负 reduced-cost route pool，排序后只加入少量优质列。当前 TWET 版先移植这个流程，而不是直接搬旧代码里的硬时间窗 O(1) 增量数组。原因是 TWET 列成本依赖分段线性时间惩罚、setup time、setup cost 和硬时间窗预处理，直接复用 VRP 的增量公式不安全；第一版启发式定价对每个候选序列统一调用 `TWETColumnEvaluator` 重算真实列成本，再用当前 RMP dual 计算 reduced cost。这样慢一些，但语义稳，而且后面仍然有 exact forward labeling 兜底，不影响精确性。

实现上，`HeuristicPricingEngine` 已经从占位实现改成真实的启发式定价器。它先按 reduced cost 对当前 restricted columns 排序，取 `heuristicPricingSeedColumns=30` 条作为种子；然后围绕每条种子尝试 remove、add 和 exchange 三类邻域，每条种子运行 `heuristicPricingTabuIterations=50` 轮。当前已经取消额外的 candidate scan 上限，不再用扫描次数控制搜索，而是按旧 GCTabu 方式用本地负 reduced-cost 候选池 `heuristicPricingPoolSize=1000` 控制生成规模，最后排序取 `maxHeuristicPricingColumns=150` 条返回。这里刻意没有“找到 150 条就停”，因为旧 GCTabu 是先形成候选池再挑最好的 add-in 列，提前停会让早期较弱列挤掉后面更好的列。该启发式只跳过当前 RMP 已经 active 的重复列；如果某条序列在全局 pool 中已有但当前节点尚未 active，仍允许返回给 `PC` 激活。

分支子节点补列也做了一层加强。之前只对 required arc 做 fallback：如果当前 seed 中没有覆盖某条 required arc 的列，就构造 required-chain，并尝试把这条链插入已有列的不同位置。现在又补了“不能外包任务缺覆盖”的兜底：如果某个 job 的外包成本是 `big_M`，即 RMP 中对应 `y_j` 上界为 0，而当前 seed 中又没有任何兼容列覆盖它，就先尝试 singleton，再尝试把该 job 插入已有 pool 列的不同位置，选择一个兼容且成本有限的 fallback column。这个仍然不是旧 VRP `FindFeasible` 的完整 slack + 定向 pricing 版本，但能减少一类 restricted master 因暂时缺列而提前 infeasible 的情况。

`PaperDominanceGraph` 这次没有改主逻辑，但补了一个轻量一致性测试 `PaperDominanceGraphConsistencyTest`。测试做法是构造随机 reachable set 和 `[0,T]` forward frontier，把同一批 label 同步插入朴素全扫描 `DominanceGraph` 和论文式 `PaperDominanceGraph`，检查“新 label 是否被占优丢弃”的返回结果是否一致。当前测试规模为 200 组、每组 80 个 label，共 16000 次插入，测试通过。这个测试不能替代真实算例级的 bound/列池对拍，也不覆盖 partial dominance，但至少能防止 graph propagation 方向、terminal superset 查找或 predecessor/successor 维护出现明显语义错误。

本轮验证包括一次 focused compile，范围仍为 `src/Common`、`src/Basic`、`src/HEU`、`src/Output` 和 `src/TWETBPC`，编译通过，仅有旧 API deprecation 提示；随后运行 `TWETBPC.GC.PaperDominanceGraphConsistencyTest`，结果通过。剩余风险主要有两点：第一，启发式定价目前是全序列重算成本，不是旧 GCTabu 那种完全增量式 tabu，后续如果要追求速度，需要把 HEU 里已有的快速分段函数拼接和 reduced-cost 常数项结合起来做专门的增量评估；第二，分支子节点仍没有完整 slack RMP + FindFeasible 定向 pricing，复杂 required arc 组合下仍可能需要进一步补强。

## 2026-05-18：旧版 GCTabu 中 cut 项的增量处理方式

旧 VRP 的 `GCTabu` 在启发式列生成里已经考虑了 active subset-row cut。它不是每次 move 后重新扫描整条 route 来计算 cut 系数，而是在当前 route 状态中维护 `sr_count[sr]`：表示第 `sr` 个 active SR cut 在当前 route 中命中了多少个客户。初始化 seed route 时，逐个客户更新 `sr_count`，当某个 cut 的命中数从 1 变成 2 时，就在 reduced cost 中扣掉该 cut 的对偶 `sr_mu`。这对应 SR cut 在 route 上的系数从 0 变成 1。

后续 tabu move 也按这个计数做增量修正。`remove` 一个客户时，如果该客户属于某个 SR cut 且原来 `sr_count==2`，删掉后该 cut 不再被 route 触发，所以 reduced cost 要把这部分对偶加回去；`add` 一个客户时，如果该客户属于某个 SR cut 且原来 `sr_count==1`，加入后命中数达到 2，所以 reduced cost 要扣掉 `sr_mu`；`exchange` 则同时看换出客户和换入客户是否让某个 cut 的命中数跨过 2 这个阈值，分别加回或扣掉对应对偶。真正提交 move 后，再同步更新 `sr_count`，下一轮继续使用。

这个处理后续可以直接作为 TWET 启发式定价接 cut 的参考。具体做法是给 `TabuRouteState` 增加 active cut 的命中计数，例如 `srCount[]`，并提前维护 `srCustomer[sr][job]` 或等价的 cut-membership 查询。`evaluateRemove`、`evaluateAdd`、`evaluateExchange` 在现有分段函数快速评估出的真实列成本基础上，再按 `srCount` 做 reduced-cost 增量修正。这样每个候选 move 只需要额外扫描 active SR cut 数量，而不需要重算整条列。exact pricing 如果后续接 SRI，也要像旧版 `LabelEX.sr_count` 一样把 cut 计数状态带进 label，否则 dominance 和 reduced cost 都无法正确反映 cut dual。当前无 cut 版本不需要马上改代码，但这个方案应作为后续接 cut 的默认路径。

## 2026-05-18：启发式定价参数对齐旧 VRP GCTabu

前面临时加入过 `maxHeuristicPricingCandidateScans=5000`，用于防止 TWET 候选评估开销过大。但旧 VRP 的 `GCTabu` 并不是按候选扫描次数截断，而是用几组更明确的参数控制：`m_tabu_cg_size=30` 控制 seed 数，`m_tabu_cg_iteration_number=50` 控制每条 seed 的 tabu 轮数，`m_tabu_cg_tenure=30` 控制 tabu 长度，`m_gen_size=1000` 控制本地负 reduced-cost route pool 的最大规模，`addin_size=150` 控制最终加入 RMP 的列数。因此当前 TWET 版已经取消 candidate scan 上限，改为同样的参数语义。

修改后的配置对应关系为：`heuristicPricingSeedColumns=30` 对应旧 `m_tabu_cg_size`；`heuristicPricingTabuIterations=50` 对应旧 `m_tabu_cg_iteration_number`；`heuristicPricingTabuTenure=30` 对应旧 `m_tabu_cg_tenure`；`heuristicPricingPoolSize=1000` 对应旧 `m_gen_size`；`maxHeuristicPricingColumns=150` 对应旧 `addin_size`。这样控制逻辑更接近旧代码：每条 seed 完整扫描每轮 remove/add/exchange 邻域，选择当前最好 move；只要本地负 reduced-cost 候选池没满，就继续按 tabu 轮数推进；最后对候选池排序并返回前 150 条。这个改动不会改变 exact pricing 的兜底地位，只是让启发式生成列层和旧 VRP 的行为更一致。

## 2026-05-18：分支子节点改为 slack RMP + FindFeasible 修复

前一版 `Tree.prepareChildSeedColumns()` 的做法还是偏工程兜底：子节点入队前先从全局 pool 筛所有兼容列，然后对缺覆盖 job 或缺 required arc 的情况临时拼一些 fallback 序列。这和旧 VRP 的 `BranchD.UpdateRouteSet()` 不是同一套逻辑，效率也差，因为它在树层暴力枚举 pool 插入位置，而且无法利用 RMP dual 指导列生成。本轮把这块改成更接近旧代码的结构。

现在子节点入队时只做一件事：从父节点当前 RMP 的 restricted columns 中，按父节点 reduced cost 排序，挑选低于 `branchSeedReducedCostAllowance=5000` 的列，并最多保留 `branchSeedColumnLimit=1000` 条作为 child seed。这两个参数分别对应旧 VRP 的 `m_addin_red_cost` 和 `m_initial_col_number`。也就是说，子节点初始列集不再是“全局 pool 里所有兼容列”，而是像旧 `UpdateRouteSet()` 后半段那样继承父节点低 reduced-cost 的优质列。

如果这些 seed 仍然导致子节点 RMP 暂时不可行，修复逻辑现在放到 `PC` 内部。`PC.solve()` 会先解正常 RMP；如果不可行，就打开 `LP` 的 feasibility repair mode，建立带人工 slack 的同一节点 RMP。slack 加在 job 覆盖约束和 required-arc 约束上，目标系数为 `big_M`，只用于让暂时缺列的子节点先可解并产生 dual。随后 `PC` 调用现有启发式 pricing 和 exact pricing，日志名加 `[FindFeasible]`，不断把新列增量加入当前 CPLEX 模型并重新求解，直到 slack 归零，或者生成列数达到 `maxBranchRepairColumns=500`，该参数对应旧 VRP 的 `m_branch_col_number`。slack 归零后会关闭 repair mode，重建正常 RMP 继续标准列生成。

这次也把 `LP.addColumns()` 改成支持当前模型上的增量加列。只要 CPLEX 模型已经存在，新列会通过 `IloColumn` 同时接入 objective、machine constraint、coverage constraints 和 required-arc constraints，然后直接 `resolveCurrentModel()`，不再每轮都 `cplex.end()` 再重建模型。只有切换 repair mode、加入 cut 或显式重新 solve 时才重建模型。这个点是效率上的关键：如果沿用旧 VRP 的 FindFeasible 流程但每次都重建 LP，反而会比旧代码慢。

需要说明的是，这仍然不是完全复制旧 VRP 的所有细节。旧代码里的 `GCTabu.FindFeasible()` 和 `GCNGBB.FindFeasible()` 是专门的可行列修复入口；当前 TWET 版先复用已有 pricing engines，在 slack dual 下生成负 reduced-cost 列。结构上已经变成“slack RMP + 启发式/精确定价修复”，但还没有为 required arc 写专门定向的 pricing 状态，也没有输出 slack 剩余量和 repair 迭代统计。后续如果遇到复杂 required-arc 分支节点，可以继续把 `PricingEngine` 拆出专门的 `findFeasible` 接口。

## 2026-05-18：继续贴近旧 VRP 的 UpdateRouteSet 流程

前一版已经把暴力 fallback 改成了 slack RMP + pricing 修复，但还有一个关键差异：旧 VRP 的 `UpdateRouteSet()` 在 `FindFeasible()` 成功以后，会从当前子节点 LP 的 `route_index` 中按 reduced cost 重新筛选 route set，只保留 `rc < m_addin_red_cost` 且数量不超过 `m_initial_col_number` 的列。也就是说，旧代码不是“修复过程中见过多少列就全部带下去”，而是修复成功后根据当前子节点 LP 的 reduced cost 重新压缩列集。

本轮补上了这个步骤。`LP` 新增 `resetRestrictedColumnsByCurrentReducedCost(maxColumns, reducedCostAllowance)`，在 repair slack 已经归零、但还没关闭 repair mode 之前调用。此时 reduced cost 来自当前带 slack 的子节点 LP，和旧 VRP 在 slack 修复成功后读取 `lp.cplex.getReducedCost(...)` 的时机一致。筛选后再关闭 repair mode，重建正常 RMP 并继续后续列生成。这样 child 的 restricted columns 不会因为 repair 过程持续膨胀，也更接近旧 VRP 的 route-set 更新语义。

另一个差异是 `FindFeasible` 入口。之前 repair mode 只是日志上把普通 pricing 标成 `[FindFeasible]`，逻辑上仍调用 `price(lp)`。本轮在 `PricingEngine` 中增加了默认 `findFeasible(lp)` 入口，默认实现仍复用 `price(lp)`，但 `PC` 在 repair mode 下会显式调用该入口。这一步本身不改变当前定价结果，但把流程语义和旧 VRP 对齐了：后续如果 required-arc 分支节点需要更强的定向修复，可以直接在具体 pricing engine 中覆盖 `findFeasible()`，而不影响正常 pricing。

因此当前逻辑和旧 VRP 的主差异进一步缩小为两点：第一，TWET 的 slack 仍同时覆盖 job 覆盖约束和 required-arc 约束，比旧 VRP 的 branch slack 更泛，这是由外包和任务覆盖结构导致的；第二，当前具体 pricing engine 的 `findFeasible()` 还没有写专门的 required-arc 定向状态，只是有了入口和 slack dual 驱动。后续若继续追求完全同款，应优先做后者，而不是再回到树层 fallback。

## 2026-05-18：当前 BPC 框架与旧 VRP 流程的剩余差异

这次只比较框架流程，不讨论 TWET 与 VRP 在成本评估、时间窗函数、setup time/cost 上的差异。当前实现的主线已经和旧 VRP 接近：子节点先继承低 reduced-cost 列，RMP 不可行时打开 slack RMP，通过启发式和精确定价器在 slack dual 下补列，slack 归零后按当前 LP reduced cost 重筛 restricted columns，再关闭 slack 回到正常 RMP。这个流程已经对应旧 `UpdateRouteSet -> AddSlack -> GCTabu/GCNGBB.FindFeasible -> reduced-cost route set selection` 的主结构。

剩余差异主要有四个。第一，旧 VRP 的 `FindFeasible()` 自己内部反复 `solve LP -> IsNoSlack -> GetDual/GetValue -> Extend`，也就是说求解、检查 slack 和扩展列都包在具体 GC 里；当前 TWET 是 `PC` 外层统一控制这个循环，pricing engine 只负责一次 `findFeasible(lp)` 返回列。两者逻辑目标一致，但职责边界不同。如果只看算法流程，TWET 版少了“每个 GC 自己持有完整 repair loop”的写法。

第二，旧 VRP 在一次 repair 迭代里先跑启发式 `GCTabu.FindFeasible`，如果启发式产生新列，会 `gc.Reset()` 重置精确定价状态，再跑 `GCNGBB.FindFeasible`。当前 TWET 是按 `pricingEngines` 顺序逐个调用，暂时没有“启发式新增列后显式 reset exact pricing 状态”的动作。当前 exact pricing 如果本身无状态，这个差异影响不大；但如果后续 exact pricing 引入缓存、DSSR/ng-memory 之类状态，就应该补这个 reset 语义。

第三，旧 VRP 的 slack 是围绕当前分支约束加的 branch slack；当前 TWET 的 slack 加在 job 覆盖和 required-arc 约束上。这不是评估细节，而是 RMP 结构差异带来的框架差异。TWET 有外包变量和任务覆盖约束，子节点缺列可能表现为覆盖缺口或 required-arc 缺口，所以 slack 范围更泛。这个差异是当前问题结构导致的，不能简单照搬成只给某一条分支约束加 slack。

第四，旧 VRP 的 child route set 是 `UpdateRouteSet()` 的直接返回值，分支时立即赋给 left/right node；当前 TWET 是 child 入队前先继承父节点低 reduced-cost 列，真正不可行时在该 child 被弹出求解时才执行 slack repair 和 reduced-cost 重筛。也就是说，旧流程是在“生成 child 时”完成 route set 修复，TWET 当前是在“求解 child 时”完成修复。逻辑上等价地服务于 child RMP，但触发时机不同。若要更像旧版，可以把 repair 提前到 `enqueueChild` 阶段；但那会让分支阶段直接调用 LP/PC，代码耦合更重，且当前延迟修复不会改变子节点求解逻辑。

因此，如果不考虑评估细节，当前还不一致的核心是：`FindFeasible` 的 repair loop 放在 `PC` 而不是 GC 内部；启发式新增列后没有 exact pricing reset 语义；slack 约束范围比旧 VRP 更泛；child route set 修复发生在 child 求解时而不是 child 创建时。其他如增量加列、repair 后按 reduced cost 重筛列集、列数阈值和 add-in 阈值，已经基本按旧 VRP 思路对齐。

## 2026-05-18：关于 slack、机器数区间和 child 修复时机的补充说明

关于 `FindFeasible` 循环放在哪里，本质上主要是职责划分差异。旧 VRP 把 `solve LP -> IsNoSlack -> GetDual/GetValue -> Extend` 包在每个 GC 的 `FindFeasible()` 里；当前 TWET 把这层循环放在 `PC.repairInfeasibleMaster()`，pricing engine 只负责单轮补列。从算法调用顺序看，两者都是“解带 slack 的 RMP，读 dual，调用启发式/精确定价补列，再解 RMP，直到 slack 为 0 或列数上限”。因此这是封装位置不同，不是求解逻辑本质不同。当前放在 `PC` 更适合 TWET，因为多个 pricing engine 可以共享一套 repair 停止条件，避免每个 engine 复制 LP 求解和 slack 检查逻辑。

旧 VRP 中 `gc.Reset()` 的目的，是在启发式定价器产生新列以后，清理后续精确定价器可能保留的内部状态。新增列会改变 RMP，重新求解后 dual 会变化；如果 exact pricing 内部保存了上一轮的标签、bound、ng memory、DSSR 状态或其他缓存，就可能不再适合继续使用，所以旧代码在 `hgc.FindFeasible()` 产生新列后调用 `gc.Reset()`。当前 TWET exact pricing 每次调用基本重新读取 LP dual 并重新做 labeling，暂时没有这类跨调用状态，所以不需要 reset。后续如果加入 DSSR、ng-memory、双向 bound 或 label pool 复用，就应该给 `PricingEngine` 加默认空实现的 `reset()`，并在 repair mode 下前一个 engine 加列后调用后续 engine 的 reset。

关于 slack，即使有外包变量，RMP 也不保证总可行。如果每个任务都能外包、tariff segment 没有限制、机器数下界为 0、且没有 required arc，那么没有内部列时确实可以通过 `y_j=1` 让 coverage 可行。但当前模型存在几类例外：某些任务可以被设为不能外包，即 `outsourcingCost[j]` 为 `big_M` 时 `y_j` 上界为 0；required arc 必须由内部列覆盖，外包不能替代；tariff segment 分支可能禁止某些外包基准量；machine-count 分支可能要求至少使用若干内部列。因此 slack 的作用不是把不可行解当成可行解，而是让“暂时缺列”的 RMP 先可解并产生 dual，随后必须通过 `FindFeasible/pricing` 生成真实列，把 slack 压回 0。

当前机器数约束是区间形式 `minMachineCount <= sum lambda <= maxMachineCount`。根节点默认 `minMachineCount=0, maxMachineCount=m`，这意味着允许全部外包；`MachineCountBrancher` 会在分支中收紧上下界。这个建模不应该默认改成等式 `sum lambda = m`，因为在带外包或允许机器空闲的设定下，最优解不一定使用全部机器。只有当后续明确要求“所有机器必须使用”时，才应该改成等式。

关于 child 修复时机，旧 VRP 是创建 child 时直接运行 `UpdateRouteSet()`，修好 child 的 route set 后再入队。当前 TWET 是 child 入队时先继承父节点低 reduced-cost 列，真正弹出求解 child 时再根据需要做 slack repair。两者对最终 child RMP 的作用接近，但时机不同。当前延迟修复的好处是：如果 child 后续不需要处理或被 bound 剪掉，就不用提前跑 LP/pricing；`Tree` 也不需要直接调用 LP/PC，所有 RMP 不可行修复集中在 `PC`。因此当前时机更适合这个项目，暂时不建议为了形式一致而把 repair 挪回 `enqueueChild()`。

## 2026-05-18：FindFeasible 封装差异的结论补充

关于 `FindFeasible` 循环位置，当前结论是：这主要是封装差异，不是算法调用逻辑差异。旧 VRP 把 `solve LP -> check slack -> read dual -> pricing extend -> add columns` 包在各个 GC 的 `FindFeasible()` 内部；当前 TWET 把这层循环统一放在 `PC.repairInfeasibleMaster()`，pricing engine 的 `findFeasible(lp)` 只执行单轮补列。从调用顺序和信息流看，二者都是“带 slack 的 RMP 产生 dual，定价器根据 dual 补列，增量加列后重解，直到 slack 为 0 或达到列数上限”。因此这里不需要为了形式一致把循环强行搬进 GC 类，后续只要保证具体 `findFeasible()` 能在 required-arc 等场景下生成有效列即可。

## 2026-05-18：旧 VRP 中 gc.Reset() 的真实含义

重新查看旧 VRP 源码后，`BranchD.UpdateRouteSet()` 中的 `gc.Reset()` 不是在清理启发式和精确定价共享的函数信息，也不是 dominance graph。该处 `gc` 是 `GCNGBB`，其 `Reset()` 只执行 `Arrays.fill(m_low_ng_set, 0)` 和 `Arrays.fill(m_high_ng_set, 0)`。这两个数组是 `GCNGBB` 在 exact pricing 中维护的动态 ng-set，用于在发现重复访问/环以后扩展某些 customer 的 ng-memory，从而加强后续 label 扩展中的访问限制。`Extend(lp,n)` 每次会重新初始化标签队列、候选列池和 bound 数组，但不会自动清空这两个动态 ng-set；因此它们会跨多次 exact pricing 调用保留。

旧代码在启发式 `hgc.FindFeasible()` 产生新列后调用 `gc.Reset()`，含义是：RMP 增加了列并重新求解后，dual 和后续 pricing 搜索环境发生了变化，于是把 exact pricing 里此前逐步加严的动态 ng-memory 清掉，让精确定价从较松的 ng-set 状态重新开始。当前 TWET 基础版 exact pricing 没有这种跨调用维护的动态 ng-set，因此暂时不需要 reset；如果后续实现 DSSR、ng-route 动态记忆或跨轮 label/bound 缓存，再补 `reset()` 才有必要。

## 2026-05-18：关于 reset、slack 和修复时机的问答记录

本轮进一步澄清了旧 VRP `UpdateRouteSet/FindFeasible` 中几个容易混淆的点。第一，`gc.Reset()` 并不是清理启发式和精确定价共享的函数信息，也不是 dominance graph。旧代码中被 reset 的 `gc` 是 `GCNGBB`，`Reset()` 只清空 `m_low_ng_set` 和 `m_high_ng_set`，也就是 exact pricing 中动态维护的 ng-set。只有当前面的启发式 `hgc.FindFeasible()` 产生了新列时，旧代码才调用 `gc.Reset()`；如果启发式没有新增列，就不 reset。当前 TWET 基础 exact pricing 没有跨调用动态 ng-set，因此暂时不需要 reset。

第二，机器数量分支当前确实存在，机器数约束写成 `minMachineCount <= sum(lambda) <= maxMachineCount`。如果后续确认该问题的最优解必然使用全部机器，可以从 brancher 列表里关掉 `MachineCountBrancher`，甚至进一步把机器数上下界固定；但当前为了兼容外包和空机器，先保留区间建模。

第三，旧 VRP 的 slack 是加在某条分支约束上的人工变量。它只出现在那一行约束里，目标系数为 `BigNumber`，用于让“当前 route set 暂时不满足分支约束”的 LP 先可解。当前 TWET 的 required-arc slack 与旧 VRP 的 branch slack 语义最接近：required arc 约束要求 `sum(使用该弧的内部列)=1`，如果当前列集没有这类列，就临时加 `requiredArcSlack` 让该行可解，并用 slack dual 引导 pricing 生成真实列。当前 TWET 还额外对 job coverage 行加了 `coverSlack`，这是因为有些任务不能外包，或者外包/segment/机器数状态使得当前 restricted RMP 没有真实方式覆盖某个 job。这里不是给“外包变量”本身加 slack，而是给覆盖约束加 slack；如果某个 job 能正常通过 `y_j=1` 外包覆盖，这个 slack 自然不会为正。

第四，修复列逻辑与旧 VRP 的核心一致，差别是时机。旧 VRP 是创建 child 时立即运行 `UpdateRouteSet()`，必要时 slack + FindFeasible，修好 route set 后入队。当前 TWET 是 child 先入队，等实际弹出求解时由 `PC` 做 slack + FindFeasible。两者的修复机制都是“slack RMP 产生 dual -> pricing 找列 -> 加列重解 -> slack 归零后筛列”，但当前延迟修复能避免提前处理可能不会被访问的 child，也能让 `Tree` 不直接耦合 LP/pricing。

## 2026-05-18：Reset 调用点和 coverage slack 的进一步澄清

旧 VRP 里的 `Reset()` 有两类调用场景。正常列生成中，`GCNGBB.Solve(lp)` 一开始会无条件调用 `Reset()`，这是一次完整 exact pricing 求解的初始化。分支可行列修复中，`BranchA/B/C/D.UpdateRouteSet()` 创建一个 `GCTabu hgc` 和一个 `GCNGBB gc`，在 while 循环里先调用 `hgc.FindFeasible()`，如果 `old_col_number != col_number`，说明启发式新加了列，此时才调用 `gc.Reset()`，随后再调用 `gc.FindFeasible()`。`GCNGBB.FindFeasible()` 自己不 reset，因此同一个 `GCNGBB` 对象在分支修复循环中会保留动态 ng-set；所谓“跨调用”就是这些 `m_low_ng_set/m_high_ng_set` 会跨多次 `gc.FindFeasible()` 调用保留，除非正常 `Solve()` 开头或启发式新增列后显式 reset。

coverage 是否可能不可行，取决于外包变量是否能覆盖所有任务，以及 child 从父节点继承的列是否仍能覆盖所有不能外包的任务。child 只从父节点 restricted columns 中筛选低 reduced-cost 且兼容分支状态的列，筛选以后完全可能丢掉某些高 reduced-cost 但覆盖关键 job 的列；如果该 job 又不能外包，则 coverage 行会暂时不可行。即使父节点可行，child 的 forbidden/required arc 状态也可能让原来覆盖某个 job 的列不再兼容。因此 coverage slack 不是只为外包变量本身准备，而是为“当前 child restricted column set 暂时缺覆盖列”的情况准备。

## 2026-05-18：solve、FindFeasible、Reset 和 coverage slack 的细化记录

本轮继续把旧 VRP 代码和当前 TWET-BPC 代码逐项对齐，重点澄清 `solve/price` 和 `findFeasible` 的区别。正常 `solve` 或 `price` 的前提是当前 RMP 已经可行，此时 LP 能给出正常 dual，pricing 的目标是找负 reduced cost 列来改进当前节点的 LP 下界。`findFeasible` 的场景不一样：它用于分支子节点 restricted column set 暂时不够时，RMP 可能不可行。旧 VRP 的做法是先 `AddSlack`，让带人工 slack 的 LP 能解出 dual，再用启发式和精确定价在这个 dual 下补列，直到 `IsNoSlack()` 成立。也就是说，`findFeasible` 的第一目标不是证明没有负 reduced cost，而是先把子节点的列集修到可行。

旧 VRP 中启发式 `GCTabu.FindFeasible()` 本身已经会循环执行 `solve LP -> IsNoSlack -> GetDual/GetValue -> Extend`。如果启发式已经把 slack 压到 0，它会返回 `-1` 表示成功。不过旧 `BranchD.UpdateRouteSet()` 的外层 while 写法是先调用启发式，再根据是否新增列决定是否 `gc.Reset()`，然后仍然调用一次 `GCNGBB.FindFeasible()`；下一轮循环开头看到 `col_number == -1` 才退出。因此“启发式找到可行列以后为什么还调用精确的”不是因为算法上必须这么做，而是旧代码的控制流如此写。更准确地说，旧代码把启发式当作快速修复层，把 `GCNGBB` 当作进一步兜底层；如果启发式已经成功，精确层通常很快会发现 slack 已经为 0 并返回成功。

关于 `Reset()`，这次重新看了旧 `GCNGBB` 源码，确认它清理的只有 `m_low_ng_set` 和 `m_high_ng_set` 两个动态 ng-set。它不是 DSSR 的完整重启，也不是清理 dominance graph，更不是清理 LP 或 route pool。旧代码只有在 `hgc.FindFeasible()` 新增列以后才调用 `gc.Reset()`，原因是启发式补列会改变 RMP 和后续 dual，旧作者选择让后续 exact pricing 的动态 ng-memory 从较松状态重新开始。如果启发式没有新增列，就不 reset；如果 exact `FindFeasible()` 自己连续调用多次，它会保留这些 ng-set 状态继续推进。当前 TWET 的基础 exact pricing 每次调用都会重新读 dual 并重新做 forward labeling，没有跨调用的动态 ng-set，所以现在暂时没有需要 reset 的对象。后续若加入 DSSR、ng-route 动态记忆、双向 bound 缓存或跨轮 label pool，再补 `PricingEngine.reset()` 才有意义。

本轮还澄清了 child 继承列以后为什么仍可能出现 coverage 不可行。child 并不是完整继承父节点所有列，而是从父节点 restricted columns 中按 reduced cost 和分支兼容性筛一批列。这样做和旧 VRP 的 `UpdateRouteSet()` 后半段一致，目的是限制子节点列集规模。但筛选以后，某些覆盖关键 job 的高 reduced-cost 列可能被丢掉；如果该 job 又不能外包，coverage 约束就会暂时不可行。即使 job 可以外包，required arc 也不能靠外包变量满足，因为 required arc 要求的是内部加工列中出现某条弧。因此当前 TWET repair mode 同时给 coverage 行和 required-arc 行加人工 slack，是为了覆盖“暂时缺列”的 RMP，而不是为了放松最终模型。repair 成功后必须关闭 slack，并重解正常 RMP。

这里要区分两类 slack。旧 VRP 的 `AddSlack` 是加在分支约束行上的人工变量，目标系数为 `BigNumber`，只用于让当前 route set 暂时不满足分支约束时仍能求 LP 和 dual。当前 TWET 的 `requiredArcSlack` 与旧逻辑最接近，对应 `sum(columns using arc)=1` 这一类分支约束。`coverSlack` 是当前问题新增的工程兜底：如果某个 job 的 `y_j` 上界为 0，或者 child 筛列后暂时没有内部列覆盖它，coverage 行就需要人工 slack 来产生 dual。若某个 job 可以正常外包，`y_j=1` 已经能满足 coverage，这个 slack 自然不会为正。

当前实现把旧 VRP 中放在各个 GC 内部的 `FindFeasible` 循环统一放到了 `PC.repairInfeasibleMaster()`：先解带 slack 的 RMP，调用 pricing engines 的 `findFeasible(lp)`，增量加列后 `resolveCurrentModel()`，循环直到 slack 为 0 或达到列数上限。这个是封装位置差异，不是调用逻辑差异。这样做的好处是多个 pricing engine 共用一套 repair 停止条件和增量加列逻辑，避免每个 GC 都复制 LP 求解、slack 检查和列接入代码。当前 `PricingEngine.findFeasible(lp)` 默认复用 `price(lp)`，后续如果 required-arc 节点需要更强定向搜索，可以在具体 engine 里覆盖。

本轮继续澄清 repair loop 的循环语义。旧 `UpdateRouteSet()` 里的外层 `while(true)` 是一个真正的循环：每轮先调用 `GCTabu.FindFeasible()`，如果启发式新增了列，就调用 `GCNGBB.Reset()`，然后调用 `GCNGBB.FindFeasible()`。如果任一阶段把 slack 压到 0，会返回 `col_number=-1`，下一轮开头退出并认为成功。如果两者都跑完以后仍不可行，但本轮至少新增了列，则进入下一轮继续；如果本轮列数没有变化，或者超过 `m_branch_col_number`，就停止并认为修复失败。因此旧逻辑不是“Tabu 一次、NGBB 一次就结束”，而是“只要还在新增列，就交替尝试，直到 slack 为 0 或无法继续改善”。

关于 reset 触发条件，需要区分启发式新增列和 exact 自己新增列。旧代码只在 `old_col_number != col_number` 发生在 `hgc.FindFeasible()` 之后时 reset exact pricing，也就是说 reset 是为了处理“启发式新列改变了 RMP 和 dual”这个事件。如果上一轮 `GCNGBB.FindFeasible()` 自己新增了列但仍不可行，下一轮会回到 Tabu；若这次 Tabu 没新增列，就不会 reset，`GCNGBB` 会带着上一次 exact pricing 中逐步扩展出来的动态 ng-set 继续。如果 Tabu 又新增列，才 reset。`m_low_ng_set/m_high_ng_set` 什么时候非空，取决于 `GCNGBB` 在前面 exact 扩展中是否遇到了需要扩大 ng-memory 的重复访问/环结构；这些数组不是每次 `FindFeasible()` 自动清空，所以在多轮 exact repair 中可能保留。

关于 slack 的建模，本轮讨论了“只放一个公共 slack，挂到所有 coverage 和 branch 行上”的方案。结论是不建议这么做。一个公共 slack 同时出现在多条约束里，会把多个缺口强行绑成同一个人工变量：例如两个不同 job 都缺覆盖，或者 coverage 和 required-arc 同时缺列，公共 slack 只付一次 big-M 成本就能同时修补多行，这会低估人工违约代价，也会让 dual 变得不干净。更重要的是，pricing 需要知道到底是哪一类约束缺列，独立 slack 的 dual 更有指向性；公共 slack 会把多个行的影子价格混在一起，可能削弱 FindFeasible 找对应列的能力。当前更合理的方向是：只给确实可能缺列的行加独立 slack。若全部任务都允许外包，则 coverage 行通常不需要 slack；若某些任务不能外包，或外包/tariff/机器数分支会让 coverage 暂时缺真实列，则这些 coverage 行需要独立 slack。required arc、tariff segment、machine-count 下界等分支约束如果可能因为 restricted columns 不足而不可行，也应各自使用独立 slack，而不是共用一个变量。

旧 VRP 在极端情况下也不是完全没有工程风险。它在 repair 成功后按 reduced cost 重筛 route set，这个步骤可能丢掉某些当前不便宜但对后续 coverage 或分支可行性有用的列。旧代码依赖较大的保留数量和较宽的 reduced-cost allowance 来降低风险，若后续节点仍暂时不可行，再通过 `AddSlack + FindFeasible` 修复。因此它是一个实用的列池控制策略，不是严格保证“筛完以后子节点 RMP 永远可行”的证明。当前 TWET 也沿用这个思想，但因为有外包、不能外包任务、tariff segment 和 required arc，coverage slack 的必要性比纯 VRP 更明显。

进一步看旧 `UpdateRouteSet()` 的筛列代码，修复成功后它先 `lp.GetVaule()`，但真正用于传给 child 的并不是被注释掉的 `route_active_set`，而是遍历 `lp.route_index` 中所有当前 LP 变量，对每条 route 取 `cplex.getReducedCost(...)`，保留 `rc < m_addin_red_cost` 且不违反 `n.feasible_arc` 的列；如果超过 `m_initial_col_number`，再按 reduced cost 排序取前若干条。因此旧代码不是显式“保留所有基列/正值列”，而是用 reduced cost 阈值间接覆盖它们。通常正值基列 reduced cost 为 0，会被 `5000` 这种宽阈值保留下来；但如果低 reduced-cost 列太多，理论上仍可能把某些正值列挤出前 `1000`。所以这不是严格证明，只是旧代码的工程策略。当前 TWET 的 `resetRestrictedColumnsByCurrentReducedCost(...)` 采用相同思路，后续若要更稳，可以显式优先保留当前 LP 正值列，再用 reduced cost 补足容量。

关于旧代码是否使用 DSSR，需要精确区分。`GCNGBB` 文件头注释写的是“using decremental state space to iteratively update the ng-set”，并且代码中确实通过 `m_low_ng_set/m_high_ng_set`、duplicate 检测和 `UpdateNGSet()` 逐步加严 ng-memory。这是 ng-route relaxaton 上的 decremental state-space refinement 思路，和 DSSR 的“大状态空间逐步收紧”思想相近。但旧项目里也有单独的 `GCDSS` 类，`BranchD.UpdateRouteSet()` 当前实际创建的是 `GCNGBB gc = new GCNGBB(data, Double.MAX_VALUE)`，而不是 `GCDSS`。所以更准确的说法是：当前分支修复使用的是带动态 ng-set 更新的 NGBB 精确定价，不是直接调用 `GCDSS` 那个 DSS 类。

关于“一个 slack 变量覆盖所有可能行”的想法，本轮进一步明确：如果这个 slack 以同一个系数出现在多条 equality 里，它不仅不是更稳，反而可能表达能力不足。不同 coverage 行的缺口通常不相同，一个公共 slack 会强迫所有这些行使用同一个人工补充值；如果某一行本来不缺口，公共 slack 仍会改变该行左端，从而破坏那一行。除非给每行配不同系数或额外结构，但那本质上又回到了每行独立人工变量。旧 VRP 的 `AddSlack` 每次也是针对一条 branch constraint 加一个人工变量，而不是一个 slack 挂全部分支行。当前 TWET 因此仍应采用“每个可能缺列的约束行各自一个 slack”的方式；可以优化的是只给需要的行加，例如全部任务可外包时不加 coverage slack，不能外包的 job coverage 行再加。

如果父节点的所有当前基列都完整继承到 child，且这些基列在 child 分支状态下仍兼容，那么 coverage 一般不会是问题，新增分支约束才是主要不可行来源。但当前旧 VRP 和 TWET 都不是完整继承所有基列，而是按 reduced cost 和兼容性筛列；同时 child 的 forbidden/required arc 会让部分父节点列不再兼容。因此实际实现里 coverage slack 仍有存在理由。若后续改成“正值列必保留 + 所有不能外包 job 至少保留一条覆盖列 + 只按阈值补充非基列”，coverage slack 的使用范围可以进一步收窄。

本轮进一步确认了 `GCNGBB` 的内部流程和它看起来“奇怪”的 reset 语义。`GCNGBB.Solve(lp)` 是正常 pricing 入口，开头会无条件 `Reset()`，然后循环执行 `lp.Solve() -> lp.GetDual() -> Extend(lp)`，直到 `Extend` 没有继续加列。`GCNGBB.FindFeasible(lp,n,col_number)` 是分支修复入口，它不调用 `Reset()`，只执行一次 `Extend(lp,n)`，把本次生成的 route 数加到 `col_number`，然后立即解一次带 slack 的 LP 并检查 `IsNoSlack()`。因此在分支修复外层循环里，如果上一次进入 `GCNGBB` 后仍不可行，下一次再进入 `GCNGBB` 时，除非中间 Tabu 新增列触发了外层 `gc.Reset()`，否则会保留上一次 exact pricing 过程中形成的 `m_low_ng_set/m_high_ng_set`。

`Extend(lp,n)` 自身内部也不是只做一轮普通 labeling。它先 `Initialize(lp)`，计算前向/后向时间和容量 bound，然后进入一个内部 `while(true)`：每轮清空本轮最小成本和 duplicate 标记，做 `FWExtend`、`BWExtend`、`Join`，之后调用 `UpdateNGSet()`。如果 join 已经生成了负 reduced-cost route，或者本轮没有发现 duplicate cycle，就退出；如果没有生成列但发现了重复访问形成的 cycle，`UpdateNGSet()` 会把 cycle 中的 customer 加进相关节点的 ng-set，然后下一轮用更紧的 ng-memory 重新做 forward/backward/join。这个过程就是它文件头说的 decremental state-space refinement on ng-route。它和 `GCDSS` 的 DSS 类不是同一个实现，但确实是在一次 pricing 调用内部逐步扩充状态空间约束。

因此旧代码的 reset 逻辑可以理解为：`GCNGBB` 自己连续求解时，保留动态 ng-set 是刻意的，因为这些 ng-set 是它通过重复环发现逐步加严出来的信息；但如果 Tabu 在外部新加了列，RMP 和 dual 改变，旧代码选择把 exact pricing 的动态 ng-set 清掉，让它从松状态重新开始。这个设计并非唯一合理方案，因为 NGBB 自己新增列以后 LP 也会变化，但旧代码没有 reset；所以它更像经验性的状态管理，而不是严格的必要步骤。当前 TWET 还没有这种跨调用动态 ng-set，暂时不需要照搬；如果后续做 ng-route/DSSR 式动态收紧，再决定是否复现这种 reset 策略。

关于 child 筛列后的可行性，本轮也进一步明确：旧代码确实会把不满足当前分支 arc 状态的 route 删掉，筛选条件包括 `n.feasible_arc[route[k-1]][route[k]] != -1`。因此从父节点继承来的列在 child 中可能因为 forbidden/required 分支变得不兼容而被移除。再加上 reduced-cost 数量筛选，child restricted RMP 仍可能暂时不可行。这说明后续如果想减少 coverage slack 的使用，应该优先改 seed selection，例如显式保留当前 LP 正值列、保证不能外包 job 的覆盖列、再按 reduced cost 补充，而不是依赖一个公共 slack。
