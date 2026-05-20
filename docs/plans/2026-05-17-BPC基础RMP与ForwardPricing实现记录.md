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

本轮进一步澄清了旧 VRP 和当前 TWET repair loop 的差异。旧 `GCTabu.FindFeasible()` 内部会先解当前带 slack 的 LP，若 slack 未归零就读 dual、扩展启发式列、把新 route 加入 `lp.pool`，并通过 `lp.AddColumn(gn_index)` 直接接入当前 CPLEX 模型；随后它继续循环求 LP，直到 slack 归零、没有新列或达到列数上限。因此旧 Tabu 的 `FindFeasible()` 不是只返回候选列，而是自己会“解 LP、加列、重解”。旧外层再调用 `GCNGBB.FindFeasible()`，此时 NGBB 看到的可能已经是 Tabu 加列后的 LP。

当前 TWET 的实现不同：`PC.generateColumns(lp,true)` 会按顺序调用 `HeuristicPricingEngine.findFeasible(lp)` 和 exact engine 的 `findFeasible(lp)`，但这两个 engine 当前只是返回 `PricingResult`，不会在各自内部修改 CPLEX 模型。`PC` 收齐本轮所有新列后，才统一 `lp.addColumns(newColumnIds)` 并 `resolveCurrentModel()`。所以当前逻辑是“启发式和精确基于同一轮旧 dual 各找一批列，再统一加列重解”；旧 VRP 是“Tabu 可在内部加列重解，随后 NGBB 可能基于更新后的 LP 再修”。两者目标一致，但严格流程并不完全相同。当前的优点是封装干净、LP 修改集中；缺点是如果想完全模拟旧 `FindFeasible`，后续需要让 repair mode 支持 engine 内部增量加列/重解，或者在 `PC` 中做到每个 engine 加列后立即 resolve，再进入下一个 engine。

关于“什么时候判当前点不可行”，当前 TWET 是：带 slack 的 RMP 解出来后，只要 slack 未归零，就进入 repair loop；每轮如果启发式和精确都没有返回新的 active column，就停止；或者达到 `maxBranchRepairColumns` 后仍有 slack，也停止。此时返回当前节点 RMP infeasible。这个和旧 VRP 的“不再有新列或超过 `m_branch_col_number` 就停止”是同类停止准则，只是旧版的“新列”是在各 GC 内部加入，当前是由 `PC` 汇总后加入。

关于公共 slack，本轮明确了用户提出的另一种理解：如果只想知道“有缺口存在”，一个公共 slack 看起来可以作为 infeasibility flag。但在 LP repair 中，slack 不只是 flag，它还通过目标惩罚和约束 dual 引导 pricing 找列。公共 slack 挂多行时，如果多行缺口数值不同，模型可能反而不可行；如果通过其他写法把它做成“有任意缺口就打开”，那会失去每一行的 dual 指向，pricing 不知道应优先补哪个 job、哪条 required arc 或哪类分支约束。旧 VRP 也是对当前分支行单独加 slack，而不是公共 slack 挂所有分支行。因此当前仍倾向独立 slack，但后续可以减少 slack 行数：全部允许外包时 coverage 行不加；child seed selection 若保证不能外包 job 的 coverage，则这些 coverage 行也可不加。

本轮进一步确认 `maxBranchRepairColumns=500` 只是对齐旧 VRP `m_branch_col_number=500` 的工程上限，不应被解释成数学上的不可行证明。旧代码在 `col_number > m_branch_col_number` 或本轮没有新增列时停止；这是一种防止分支修复无限加列的保护。当前 TWET 也用了这个上限，但后续在严格 BPC 语义上，如果达到上限而 slack 仍为正，更合理的状态应是“repair limit / node unresolved”，而不是直接把子节点当作严格 infeasible。若用户不希望额外参数化，应至少把该上限视为旧代码参数的复刻，并在日志/状态中区分“无新列失败”和“达到上限停止”。

关于 repair 迭代流程，旧 VRP 的做法在分支修复场景下更自适应：Tabu 内部可以多轮 `solve -> add column -> resolve`，Tabu 结束后 NGBB 可能已经基于更新后的 LP 和 dual 继续补列。当前 TWET 的做法更清晰，但更粗：同一轮里启发式和精确定价都基于同一个旧 LP 返回列，然后由 `PC` 统一加列重解；如果启发式列已经足以明显改变 dual，exact 仍然没利用这次更新后的 dual，必须等外层下一轮。后续若要更贴近旧 VRP，建议在 repair mode 下改成“每个 engine 返回列后立即 add + resolve + 检查 slack”，若 slack 已归零则不再调用后续 engine；这样既能保持 PC 统一控制，也能复刻旧代码的信息流。

关于旧代码是否只修新分支约束，本轮进一步确认：旧 `AddSlack(cid,sid)` 是对当前分支相关的一行 `branch2rng` 加 slack，并没有对 coverage 行加 slack。之所以旧 VRP 还能工作，主要因为它的 set partitioning coverage 在父节点 route set 中通常已经可行，而且 child 主要新增的是当前 branch 行；但由于它后续还会按 reduced cost 和 feasible arc 过滤 route，极端情况下确实可能把维持 coverage 所需的列删掉。旧代码没有专门修 coverage，这属于工程风险。当前 TWET 因为有不能外包 job、外包变量、tariff/机器数分支等额外结构，coverage 暂时不可行的可能性更明显，所以当前更稳的设计是保留 coverage slack，但后续通过更强 child seed selection 缩小它的适用范围。

关于旧 `FindFeasible()` 加列数量，本轮确认如下：`GCTabu` 和 `GCNGBB` 都先在本地 `GCPool` 中收集负 reduced-cost route，本地池达到 `m_gen_size=1000` 时停止生成；如果本地池超过 `addin_size=150`，先排序，然后最多把前 150 条新 route 加入 LP。分支修复外层另有 `m_branch_col_number=500` 的总修复列数上限。因此旧逻辑是“启发式先找并加入最多一批优质列，LP 更新后，再让精确定价继续找”，而不是启发式和精确基于同一个旧 dual 同时找列。当前 TWET 后续如果要减少重复列并更贴近旧流程，应在 repair mode 下改为每个 pricing engine 生成列后立即 add/resolve，再进入下一个 engine。

旧 VRP 的上述潜在风险已经单独整理到 `docs/plans/2026-05-18-旧VRP-BPC潜在风险记录.md`。后续涉及分支修复、slack、子节点列继承和动态 ng-set/DSSR 类策略时，优先参考该文档。

## 2026-05-18：FindFeasible repair 流程再次贴近旧 VRP

前一版 TWET repair 的主流程虽然已经是 slack RMP + pricing 补列，但还有一个时序差异：`PC.generateColumns(lp,true)` 会先让启发式定价和精确定价都基于同一轮旧 dual 各自返回一批列，然后由 `PC` 统一加列并重解。这和旧 VRP `UpdateRouteSet()` 中的做法不完全一样。旧代码的实际信息流更接近：先用启发式 `FindFeasible` 基于当前 slack LP 补列；如果启发式真的加了列，就马上重解 LP，后续精确定价再基于新的 dual 继续修复。这样做的目的不是形式上把循环搬进 GC，而是避免后面的定价器继续使用已经过期的 dual 或内部状态。

本轮把 `PC.repairInfeasibleMaster()` 改成 repair 专用的顺序流程：每个 `PricingEngine.findFeasible(lp)` 返回新列后，立即 `lp.addColumns()` 并 `resolveCurrentModel()`；如果 slack 已经归零，就不再调用后续 engine。若前一个 engine 补列成功，会调用后续 engine 的 `reset()`，这个接口当前默认空实现，因为现有 exact pricing 没有跨调用 DSSR/ng-set 状态；后续如果接入动态 ng-route 或 DSSR，就可以在具体 engine 里覆盖该方法。正常 pricing 的 `generateColumns(lp,false)` 仍保持原来的一轮汇总逻辑，因为它不承担 repair slack 归零检查。

`maxBranchRepairColumns` 也从 500 调大到 100000。这个值保留是为了防止极端情况下 repair 无限补列，但当前语义不再把它当作判断子节点不可行的主要条件。正常情况下 repair 应该因为 `slack=0` 成功退出，或者因为一整轮没有任何 engine 返回新的 active column 而失败。达到该上限只能说明触发了工程保护，不能当成数学上的 infeasible 证明。

由此，当前 repair 流程和旧 VRP 的主要逻辑进一步对齐为：带 slack 的 RMP 先给出 dual，启发式定价先尝试补列，补列后立即重解并更新 dual，再进入后续定价器；slack 归零后再按当前 LP reduced cost 重筛 restricted columns，关闭 repair mode，回到正常 RMP。剩余差异是具体 `findFeasible()` 仍未做 required-arc 定向搜索，`reset()` 目前也只是给后续 DSSR/ng-set 预留的接口。

## 2026-05-18：修正 repair 阶段“slack 归零后是否继续调用后续定价器”的说明

复核旧 VRP `BranchD.UpdateRouteSet()` 后确认，旧流程并不是在启发式定价让 slack 归零后立刻跳过后续精确定价。其代码顺序是先调用 `hgc.FindFeasible(lp, node, col_number)`，若启发式新增列则 `gc.Reset()`，然后仍会调用一次 `gc.FindFeasible(lp, node, col_number)`；只有当 `FindFeasible` 返回 `-1` 后，下一轮 while 开头才把 `success=true` 并退出。因此如果目标是让当前 TWET repair 流程更贴近旧代码，就不应该在同一轮 engine pass 中因为 `lp.isNoSlack()` 已经为真而提前跳过后续 engine。

本轮据此调整 `PC.repairInfeasibleMaster()`：外层 while 仍然以 `!lp.isNoSlack()` 作为 repair 是否继续的判断；但内层按 engine 顺序调用时，不再把 `!lp.isNoSlack()` 放进 for 条件。这样某个 engine 补列并重解 LP 后，即使 slack 已经归零，后续 engine 仍可基于新的 dual 再执行一次。下一轮外层 while 会因为 slack 已归零而退出。这个行为更接近旧 VRP 的 `hgc -> gc -> 下一轮检查是否成功` 的控制流。

同时补充明确 `maxBranchRepairColumns=100000` 的注释：它对应旧 `m_branch_col_number`，但当前调大是为了避免 500 这个较小工程上限过早截断可修复子节点；它只用于防止极端无限补列，不作为不可行证明。正常退出原因仍应是 slack 归零或所有定价器都没有新 active column。

进一步确认当前 TWET repair 的执行顺序为：进入外层 `while (!lp.isNoSlack())` 后，先调用启发式 `findFeasible`。如果启发式返回新列，`PC` 立即把这些列加入当前 LP 并重解；随后仍然进入后续精确定价器。若精确定价器也返回新列，同样立即加入并重解。等这一轮 engine 都处理完后，外层 while 再检查 slack：如果精确那次重解后 slack 已经为 0，就不再进入下一轮 repair，直接执行 repair 成功后的 reduced-cost 列筛选并关闭 slack mode。也就是说，当前流程不是“tabu 加列重解后马上回到 tabu”，而是“tabu 加列重解后进入 exact；这一整轮结束后，如果 slack 仍未归零且确实有新增列，才回到下一轮 tabu”。这和旧 VRP `hgc -> gc -> 下一轮检查 col_number == -1` 的控制流是一致的。

## 2026-05-18：进一步修正 FindFeasible 中启发式与精确定价的先后关系

继续复核旧 VRP `GCTabu.FindFeasible()` 后，前一版理解还不够准确。旧代码不是“Tabu 找到一批列后就立刻进入精确，然后下一轮再看”，而是 `GCTabu.FindFeasible()` 自己内部就包含循环：先解当前 slack LP，如果 slack 已经为 0 就返回成功；否则读取 dual/value，执行 `Extend(lp,n)` 生成启发式列并 `lp.AddColumn(gn_index)`，然后继续重解。只要 Tabu 还能继续生成列、列数未超过 `m_branch_col_number`、且未超时，它会优先持续使用启发式修复。只有当 Tabu 找不到新列或退出后，外层 `UpdateRouteSet()` 才进入 `GCNGBB.FindFeasible()` 这一层精确定价兜底。

因此当前 TWET repair 进一步调整为：`PricingEngine` 新增 `repeatFindFeasibleUntilExhausted()`，默认 `false`；`HeuristicPricingEngine` 覆盖为 `true`。`PC.repairInfeasibleMaster()` 在 repair mode 下调用某个 engine 后，如果该 engine 生成了新列，就立即加列并重解 LP；如果该 engine 声明可以重复、且 slack 仍未归零，就继续调用同一个 engine。这样启发式能找到列时会优先持续使用启发式，而不是每补一批列就马上切到更慢的 exact。精确定价器保持默认不重复，作为启发式耗尽后的兜底；如果 exact 加列后 slack 仍未归零，控制权回到外层 repair pass，下一轮仍从启发式开始。

这个流程更接近旧 VRP 的意图：快速的启发式列生成优先用于修复子节点 RMP，可行性仍修不掉时再让精确定价补强。`resetFollowingPricingEngines()` 只在某个 engine 确实新增列后调用，用于清理后续 engine 可能依赖旧 dual 的状态；当前 exact pricing 暂无跨调用状态，因此实际为空实现，但接口保留给后续 DSSR/ng-route。

## 2026-05-18：当前 repair loop 与旧 VRP 的一致性复核

本轮重新对照当前 `PC.repairInfeasibleMaster()` 和旧 VRP `BranchD.UpdateRouteSet()`、`GCTabu.FindFeasible()`、`GCNGBB.FindFeasible()` 后，结论是：当前 repair loop 的主控制逻辑已经基本和旧 VRP 一致。这里的一致指的是流程意图一致，而不是代码封装位置完全相同。

旧 VRP 的真实流程是：进入带 slack 的子节点 LP 后，先调用 `GCTabu.FindFeasible()`。这个启发式函数内部会反复执行“解 LP、读 dual、生成启发式列、加列、再解 LP”，只要启发式还能产生列且 slack 没有归零，就优先继续用启发式。只有当 Tabu 停下来以后，外层才调用 `GCNGBB.FindFeasible()` 做精确定价兜底。如果 exact 也加了列但 slack 仍未归零，外层循环会再回到下一轮 Tabu；如果两者都没有新增列，或者达到工程列数上限，则 repair 失败。

当前 TWET 的流程现在也是这个语义：`HeuristicPricingEngine.repeatFindFeasibleUntilExhausted()` 返回 `true`，所以 repair 阶段会在启发式还能补列且 slack 未归零时持续调用启发式，并且每次补列后立即加入 LP、重解 LP、更新 dual。启发式耗尽后才进入后续 exact pricing。exact pricing 默认不重复占用这一层，如果 exact 加列后 slack 仍未归零，控制权回到外层 repair pass，下一轮仍然从启发式开始。这一点已经和旧 VRP “先尽量用快速启发式，启发式修不掉再用精确兜底”的逻辑一致。

剩下的差异主要是封装和能力差异。旧 VRP 把 `solve/add/resolve` 放在 `GCTabu.FindFeasible()` 内部；当前 TWET 由 `PC` 统一负责加列和重解，engine 只返回 `PricingResult`，这是封装差异，不改变主流程。旧 `GCNGBB` 有动态 ng-set，并且只有 Tabu 新增列后才 reset；当前 exact pricing 还没有这种跨调用动态状态，所以 `reset()` 目前是预留接口。旧 exact 的 `FindFeasible()` 本身带 NGBB/ng-route 的动态收紧；当前 exact 仍是基础 forward pricing，repair 强度还没有完全达到旧 VRP 的精确定价层。也就是说，循环逻辑已经对齐，但 exact pricing 的内部能力还可以继续补。

因此当前不需要为了“循环形式”再改 `PC.repairInfeasibleMaster()`。后续真正需要补的是两个方向：一是如果实现动态 ng-route/DSSR，就让 exact engine 的 `reset()` 真正清掉对应状态；二是如果 required-arc 分支节点需要更强修复，就覆盖 `findFeasible()` 做定向 pricing，而不是继续只复用普通 `price()`。

## 2026-05-18：筛列、FindFeasible 与 slack 建模复核

本轮继续复核旧 VRP 的 `UpdateRouteSet()` 和当前 TWET 的 repair RMP。旧 VRP 在进入 `FindFeasible` 之前会先求解一次当前子节点 LP：`success = lp.cplex.solve(); success &= lp.IsNoSlack();`。如果当前筛出来的 route set 已经可行且没有人工 slack，就不进入修复；只有 LP 不可行或 slack 非零时，才对当前分支约束调用 `AddSlack()`，再用 Tabu 和 NGBB 的 `FindFeasible()` 补列。

旧 VRP 在 `FindFeasible` 成功以后还要再筛一次列，原因不是为了重新证明可行性，而是控制 child route set 的规模。它不会把 repair 过程中见过的所有 route 都留给子节点，而是遍历当前 LP 里的 route，根据 reduced cost 阈值和 child 的 `feasible_arc` 状态筛出一批低 reduced-cost 且符合分支状态的列；如果数量过多，再按 reduced cost 取前若干条。这是一个列池压缩步骤。它的隐含假设是：当前 LP 的正值列通常 reduced cost 为 0，会被宽阈值保留下来；但严格说，如果低 reduced-cost 列太多，仍可能挤掉一些维持 coverage 所需的列，所以这是工程策略，不是可行性证明。

关于“只加一个虚拟列是否可以”的问题，需要区分两个层次。如果把虚拟列看成一个服务所有 job、满足所有 required arc、满足所有分支条件、成本为 big-M 的普通列，那么它确实可以让 RMP 形式上总可行。但它不适合作为 repair slack 的替代：第一，required arc 或互斥分支状态之间可能互相冲突，一个虚拟列同时满足所有条件未必有明确语义；第二，它会像普通列一样参与覆盖和分支约束，pricing 得到的 dual 不再能清楚指向“到底哪一行缺真实列”；第三，如果虚拟列覆盖所有 job，只要它取 1，就会一次性修掉所有 coverage 缺口，这会把多个不同缺口合并成一个大惩罚，弱化找列方向。旧 VRP 的 `AddSlack()` 并不是加一条虚拟 route，而是在具体分支约束行上加一个人工变量，并用 big-M 目标惩罚；这个人工变量只负责临时打开对应约束，后续必须被真实 route 压回 0。

因此当前 TWET 的做法仍然应该保留“按约束行加 slack”的方向。现在实现中，coverage 行有 `coverSlack_j`，required-arc 行有 `requiredArcSlack_i_j`。coverage slack 是因为 TWET 存在外包变量但不是所有任务都一定能外包，且 child restricted column set 可能暂时缺覆盖某个 job 的内部列；required-arc slack 则对应旧 VRP 当前分支行 slack 的语义，因为外包不能替代一条必须由内部列满足的弧。`isNoSlack()` 会逐个检查这些人工变量是否为 0，只有全部归零后才认为 repair 成功。

所以旧 VRP 和当前 TWET 的差别是：旧 VRP 主要只修当前 branch 行；当前 TWET 同时修 coverage 和 required arc，覆盖面更宽，但每个缺口仍是独立 slack，而不是一个公共虚拟列。这样做的好处是 dual 更有指向性，pricing 能知道应补 job 覆盖还是补 required arc；代价是 repair LP 人工变量更多，但语义更干净。

## 2026-05-18：旧 VRP 节点剪枝与 UpdateRouteSet 初始列来源

本轮进一步梳理旧 VRP 分支树中一个 node 是如何被剪掉的。旧 `Tree.Solve()` 先用 `GenRoute` 构造一个启发式整数解作为 incumbent，`best_cost` 和 `pc.upper_bound` 都来自这个解；根节点 `init_node.route_set` 直接使用这批初始 routes。之后每次从优先队列取出一个 node，先看 `node.sudo_cost > best_cost + tolerance`，如果这个伪下界已经不可能优于 incumbent，就直接 `Clear()` 并跳过。这是进入 LP 求解前的队列层剪枝。

如果 node 没被队列层剪掉，就用 `node.route_set` 新建 `Pool`，再用这个 pool 里的所有 route id 构造当前节点 LP。随后 `PC.Solve(lp)` 做列生成和 cut separation，得到当前节点 LP 松弛值。求解后如果 `lp.solution_cost + tolerance > best_cost`，则该节点由 bound 剪枝：清掉 `lp/node/pool`，不再分支。如果 LP 解已经整数，则用它更新 incumbent，然后该节点自然关闭。如果 LP 解不是整数，则依次尝试 BranchA、BranchB、BranchC、BranchD；某个分支器成功后，只有 `left_node/right_node` 非空的 child 才会加入队列。若 child 的 `route_set == null`，说明 `UpdateRouteSet()` 没有修出可行列集，这个 child 会在分支器内部被清掉，不入队。

因此旧 VRP 的剪枝/关闭位置主要有三处：取队列后用 `sudo_cost` 预剪枝；当前节点 LP 求解后用 `solution_cost >= best_cost` 做 bound 剪枝；分支生成 child 时，如果 `UpdateRouteSet()` 返回 null，则直接剪掉该 child。除此之外，整数 LP 解会更新 incumbent 并关闭当前节点；如果所有分支器都失败，说明在现有分支规则下没有可分支项，该节点也不会继续展开。

关于 `UpdateRouteSet()` 初始求解的 LP 列从哪里来，需要特别说明：它不是为 child 重新建一个 LP。以 `BranchD` 为例，父节点已经在 `Tree.Solve()` 中用 `node.route_set` 建好 LP，并经过 `PC.Solve(lp)` 完成当前节点列生成。进入 `BranchD.Branch(lp)` 后，代码在同一个 `lp` 上修改/加入对应 child 的分支约束，例如左分支调用 `lp.ForceArcValue(...,0,0)`，右分支把同一个 branch range 改成 `[1,1]`，然后调用 `UpdateRouteSet(..., childNode, lp)`。所以 `UpdateRouteSet()` 开头的 `lp.cplex.solve()` 求解的是“父节点当前 LP 的列集合 + 当前 child 分支约束”的模型。这里的初始列就是父节点当前 LP 里的 `lp.route_index`，也就是父节点初始 route set 加上在父节点 `PC.Solve()` 过程中生成并接入 LP 的列。

如果这批父节点当前列在 child 分支约束下已经可行并且 `IsNoSlack()` 为真，则不需要 `FindFeasible`。如果不可行，就在同一个 LP 上对当前分支行加 slack，然后 Tabu/NGBB 往这个 LP 里继续补列。修复成功以后，`UpdateRouteSet()` 遍历当前 LP 的 `route_index`，读取每条 route 变量的 reduced cost，并检查它是否违反 child 的 `feasible_arc`。满足 `rc < m_addin_red_cost` 且不含 forbidden arc 的 route 进入候选；候选太多则按 reduced cost 排序，最多保留 `m_initial_col_number` 条，作为 child 的 `route_set`。也就是说 child 最终拿到的不是全部父节点列，也不是全部 repair 新列，而是“当前 LP 中低 reduced cost 且 child 分支兼容的一批列”。

## 2026-05-18：child 列修复时机与当前延迟策略

本轮确认 `sudo_cost` 的来源后，可以明确旧 VRP 与当前 TWET 在 child 列修复时机上的差异。旧 VRP 在分支器生成左右 child 时，就立即调用 `UpdateRouteSet()`：它用父节点当前 LP 加上 child 分支约束测试可行性，必要时加 slack 并调用 `FindFeasible()` 补列，然后筛出 child 的 `route_set`。因此一个父节点一旦分支，就可能马上为左右两个 child 都做一次列修复，即使其中某个 child 后续因为队列顺序、时间限制或更好的 incumbent 而不一定真正被展开。

当前 TWET 选择了更延迟的策略：分支时只记录 child 的分支状态和从父节点筛出来的候选列，不在 `Tree` 里立即做完整 repair。只有当 child 真正从优先队列中弹出并进入 `PC.solve()` 时，才构造该节点 RMP；如果 restricted RMP 不可行，再打开 slack repair mode 并调用 `findFeasible()` 补列。这样做在框架上更懒，也更可能高效，因为它避免了对尚未真正展开的 child 提前做昂贵的列修复。

这个差异不改变 BPC 的逻辑语义：旧 VRP 是“生成 child 时修复 route set”，当前 TWET 是“求解 child 时修复 restricted RMP”。由于旧 `sudo_cost` 本身只是父节点 LP 值，并没有通过 child repair 得到更精确的排序 bound，所以当前延迟修复不会损失旧代码用于队列排序的那类信息。真正需要注意的是，如果后续希望在 child 入队前就用更强的 child LP 下界排序，那才需要提前做类似旧 `UpdateRouteSet()` 的修复和求解；当前阶段没有这个需求。

## 2026-05-18：旧 VRP FindFeasible/AddSlack 风险补充

本轮进一步确认旧 `UpdateRouteSet()` 的两个边界风险。第一，`FindFeasible` 是有限补列过程；如果 Tabu/NGBB 在列数和时间预算内仍没有把 slack 压回 0，child 会被返回 null 并丢弃，但这并不等价于完整列空间下严格不可行。第二，旧 `AddSlack` 只对当前新分支约束行加人工变量，但 child LP 不可行不一定只由当前分支约束造成。由于 child 列集经过 reduced cost、数量上限和分支兼容性筛选，coverage、历史分支约束或其他约束也可能因为列被筛掉而暂时不可行。

旧代码能这么做，主要依赖工程上的宽松筛列：保留下来的列通常满足之前所有分支状态，且数量和 reduced-cost 阈值足够大时，coverage 大概率不出问题。因此它把 repair 重点放在当前刚加入的分支约束上。这个判断实践上可能有效，但不是严格证明。当前 TWET 保留 coverage slack 和 required-arc slack，就是为了避免 restricted columns 暂时缺 coverage 或缺 required arc 时，把原本完整列空间可行的 child 误判为不可行。

## 2026-05-18：UpdateRouteSet 首次 LP 中分支约束的位置

本轮确认旧 VRP `UpdateRouteSet()` 的首次 LP 求解已经带上 child 分支约束。arc 分支会先 `ForceArcValue(i,j,0,0)` 或把同一条弧约束 bounds 改成 `1,1`，车辆数分支会先修改车辆数行上下界，然后才调用 `UpdateRouteSet()`。所以第一次 LP 可行时，说明父节点当前列集合已经能满足该 child 的 restricted LP；第一次 LP 不可行时，当前分支约束是触发条件，但不一定是唯一原因。restricted columns 可能缺少替代覆盖列、缺少满足历史分支的列，或者无法同时满足 coverage、历史分支和当前新分支。

这解释了为什么只给当前分支行加 slack 是一个实用近似，而不是严格可行性证明。arc 分支中的列不是先筛成“每条都满足新 arc 状态”再求 LP，而是先在 LP 中加入聚合 arc 行；修复成功后才根据 child 的 `feasible_arc` 筛选返回 route set。车辆数分支也不是单列自动满足，而是所有 route 变量之和共同满足车辆数上下界。

## 2026-05-18：车辆数分支下的列筛选

旧 VRP `BranchA` 的车辆数分支只修改车辆数行 `cus2rng[0]` 的上下界。每条 route 在车辆数行中的系数都是 1，所以单条 route 不存在“是否满足车辆数分支”的过滤条件；满足与否取决于 LP 选择了多少条 route。`UpdateRouteSet()` 成功后返回 child route set 时，只按 reduced cost 阈值和保留列数量筛选，不像 arc 分支那样再按 `feasible_arc` 过滤。

## 2026-05-18：arc 分支不可行的解释

arc 分支下，如果首次 restricted LP 不可行，更准确的解释是“新 arc 分支约束和当前受限列集合发生冲突”。父节点列通常满足历史分支状态，但新 arc 约束可能把关键列压掉，剩余列不一定还能满足 coverage 和历史约束。因此不可行既不能简单归因于当前分支行，也不能说和当前分支无关，而是旧列集合、历史分支和新 arc 状态共同作用的结果。

## 2026-05-18：旧 VRP 的列筛选次数

旧 VRP 不是在节点每次求 LP 前都筛列。节点弹出后，`Tree` 直接用 `node.route_set` 构造 LP；这个集合已经是在父节点分支时准备好的。真正的筛选发生在 `UpdateRouteSet()` 成功之后，每个 child 一次：遍历当前 `lp.route_index`，按 reduced cost 阈值和数量上限保留列；车辆数分支只按这两个条件筛，arc/customer/bid 相关分支还会额外按 `feasible_arc` 过滤 forbidden arc。

## 2026-05-18：修正 UpdateRouteSet 风险判断

本轮确认旧 VRP 的 `UpdateRouteSet()` 不是先筛选 child 列再求首次 LP，而是先把 child 分支约束写进父节点当前 LP，直接用当前 `lp.route_index` 求解；只有首次 LP 可行或 slack repair 成功后，才在末尾筛选 child 的初始 `route_set`。因此，之前把“首次 LP 前筛列导致 coverage/历史分支被筛坏”作为主要漏洞的判断不准确。

更准确的判断是：如果首次 LP 可行，当前可行基通常会被末尾筛选保留下来；如果首次 LP 不可行，当前新分支约束确实是最直接的新冲突来源，只给当前分支行加 slack 在旧代码流程里是合理修复方向。仍需保留的只是边界风险：`FindFeasible` 有有限预算，且末尾 reduced-cost/数量筛选在极端退化或列数过多时不严格保证保留一个完整可行基。

## 2026-05-18：分支 slack 与分支 dual 的当前判断

本轮基于旧 VRP 代码重新确认分支修复语义。旧 `UpdateRouteSet()` 中如果 child 分支约束加入后 restricted LP 暂时不可行，`AddSlack` 只加在当前分支约束行上；它不是给 coverage 全部加人工变量。复核后认为这在旧流程里是合理的，因为首次 LP 前没有先筛 child 列，父节点当前列集合仍在，新的不可行主要是当前 child 分支约束与现有列集合不兼容。对 TWET-BPC，如果后续希望更贴近旧 VRP，可以把 repair slack 收敛到“分支约束 slack”为主，coverage slack 只作为额外保守兜底或调试开关，而不是默认必须存在。

但 TWET 有一个额外情况：tariff segment 分支不是 route 列上的约束，而是 master 中外包分段变量 `z_s` 的 bounds/segment 选择约束。它如果导致 restricted RMP 暂时不可行，本质上也是分支约束导致的 repair 问题，可以引入对应的 tariff-branch slack 或把 segment bound 改成显式 range 以便加 slack。这个 slack 的作用和 arc/车辆数分支 slack 类似，都是让 repair LP 可解并产生有方向的 dual；区别是 tariff 分支不直接改变 private-route pricing 的结构，它主要通过 coverage、外包 baseline、segment 约束等 dual 间接影响是否需要生成更多内部列来替代外包。

旧 VRP 的分支 dual 在正式 pricing 中是会被考虑的，不是只在 `UpdateRouteSet()` 中临时使用。车辆数分支对应 `cus2rng[0]`，`GetDual()` 会把它读入 `mu[0]`，pricing 初始化时 `label.m_reduced_cost = -lp.mu[0]`，等价于每条 route 扣除一份车辆数约束 dual。arc 分支对应 `branch2rng[(i+2)*n+j]`，`GetDual()` 会把该 dual 加到 `arc_mu[i][j]`，后续所有 GC/GCTabu/GCNGBB 的弧扩展都用 `distance(i,j) - arc_mu[i][j] - mu[j]`。因此，即使 child 的列集合已经按 forbidden arc 兼容，分支约束仍必须留在真实 RMP 里，其 dual 也必须进入 pricing；否则 reduced cost 会错，列生成可能提前停止。

当前 TWET-BPC 的方向和这个是一致的：机器数 dual 通过 `lp.getMachineDual()` 在 pricing 初始化时扣除，arc required dual 通过 `lp.getArcDual(from,to)` 在扩展弧时扣除。需要后续进一步确认的是 tariff segment 分支的 dual 是否以合适方式影响外包变量和 private-route pricing；如果 tariff 分支只通过变量 bounds 表达，缺少显式 row slack 和可读 dual，后续做 repair 时可能需要改成显式约束行。

## 2026-05-18：后续 TWET 修复流程的调整判断

本轮明确后续如果要完全模仿旧 VRP，TWET-BPC 的 child repair 应该改成：在生成 child 时，先用父节点当前列集合加上 child 分支约束求一次 LP；若可行，再按 reduced cost 和 child 兼容性筛出 child seed；若不可行，再只给当前分支约束加 slack 并调用 FindFeasible 修复。按照这个流程，coverage slack 不应作为默认修复变量，因为父节点当前列集合在加入新分支前本来可行，新的不可行主要来自当前分支状态与现有列集合冲突。之前 TWET 保留 coverage slack，是因为当前实现更偏向“child 先拿筛过的 seed，弹出后再修复”，这种流程下 restricted columns 可能已经缺 coverage，coverage slack 是额外兜底。若流程改成旧 VRP 形式，coverage slack 可以删除或降级成调试开关。

对于 tariff segment 分支，建议仍保持 `z_s` 原始变量 bounds 为 `[0,1]`，分支时额外加显式约束行，而不是直接只改变量上下界。禁止分支可写成 `z_s <= 0`，修复模式下变成 `z_s - slack <= 0` 或等价的 `z_s <= slack`；强制分支可写成 `z_s >= 1`，修复模式下变成 `z_s + slack >= 1`。这样 slack 仍只挂在当前 tariff 分支约束上，语义和 arc/车辆数分支一致：临时放松当前分支行，让 repair LP 可解并产生有方向的 dual。区别是 tariff 分支不直接改变 private-route pricing 的图结构，它主要通过 master 中外包量、coverage dual 和 segment 约束的变化间接影响是否需要生成更多内部列。

旧 VRP 的 arc 分支同时做两件事：一是修改 `node.feasible_arc`，让 pricing 不再生成 forbidden arc；右分支还会禁掉相关节点的其他出入弧，使生成的新列天然符合 required arc 的结构。二是仍然在 RMP 中保留聚合 arc 分支行，并把该行 dual 加入 `arc_mu[i][j]`。这不是矛盾。修改子图只能控制后续新生成的列，不能替代当前 LP 中已有列上的聚合约束，也不能替代 RMP 的 dual 贡献。只要分支行存在于 RMP，pricing 的 reduced cost 就必须包含该行 dual；否则列生成的 reduced cost 不对应当前 RMP，可能误判没有负 reduced-cost 列。即使某些分支行在整数语义上可能被 coverage 和子图限制间接推出，旧代码仍保留它们作为 LP 行和 dual 来源，这是更稳的实现。
## 2026-05-18：按旧 VRP 流程调整 child repair 和分支 slack

本次把 TWET-BPC 的 child 列继承和 repair slack 进一步改成接近旧 VRP `UpdateRouteSet()` 的流程，但仍保留当前“child 出队时才真正修复”的时机。具体做法是：`Tree` 在生成 child 时不再提前按新分支状态或 reduced cost 筛父节点列，而是直接把父节点当前 restricted columns 传给 child；child 被弹出并进入 `PC.solve()` 后，`LP` 先用“父节点列集 + 当前 child 分支约束”求一次 restricted LP。如果第一次 LP 可行，再按当前 reduced cost 和分支兼容性筛出正式 child 列集并重建 LP；如果不可行，再进入 slack repair。

repair slack 的语义也随之收紧。之前 TWET 版本会给 coverage 行和 required-arc 行都加人工 slack，这更像额外兜底。根据重新梳理后的旧 VRP 流程，现在默认只给当前新分支行加 slack：机器数上分支用 `sum(lambda) - slack <= ub`，机器数下分支用 `sum(lambda) + slack >= lb`；forbidden arc 用 `sum(x_ij) - slack = 0`，required arc 用 `sum(x_ij) + slack = 1`；tariff segment forbidden/required 分别用 `z_s - slack <= 0` 和 `z_s + slack >= 1`。coverage 不再默认加 slack，它如果不可行，应通过 pricing/外包列修复；这和旧 VRP 只针对当前分支行做 `AddSlack` 的语义一致。

为了支持 tariff segment 分支 repair，`z_s` 的 `[0,1]` 也从变量上下界改成了显式约束行 `0 <= z_s <= 1`，分支时再额外加入 `z_s <= 0` 或 `z_s >= 1`。这样 branch row 本身可以挂 slack，也便于后续读取或分析该分支约束。arc 分支也不再只建立 required arc 行，而是 required 和 forbidden 都在 RMP 中建立聚合分支行；即使 pricing 侧已经通过子图状态避免生成 forbidden arc，这些 RMP 行仍然需要保留，因为父节点继承来的旧列需要由该行压掉，并且该行 dual 必须进入 route pricing 的 reduced cost。

当前仍有一个需要后续测试关注的边界：child 第一次 LP 可行后，按 reduced cost 重新筛正式列集可能在极端情况下删掉某些维持 coverage 的高 reduced-cost 列。旧 VRP 本身也依赖较宽的 reduced-cost allowance 和保留列数降低这个风险；当前 TWET 版本保持同样思路。如果筛列后 LP 再次不可行，会进入 repair，但由于 coverage slack 已删除，说明筛列策略可能需要进一步加“保留当前正值列/覆盖关键任务列”的保护。

## 2026-05-18：为什么正式 child pricing 阶段仍保留分支行

本轮集中讨论“如果 child 已经完成首次可行性检查，后续 restricted columns 和 pricing 新列都满足分支约束，RMP 中的分支约束行是否还需要保留”。结论是：不能统一删除。这里要区分列级兼容和解级聚合约束。

对于 forbidden arc，若 child 已经筛掉所有含禁用弧的旧列，并且 pricing 图也禁止继续生成该弧，则 `sum x_ij = 0` 在正式 child LP 中通常是冗余的。保留它主要是为了流程统一和首次 LP/repair 语义清楚；后续如果确认该行没有任何非零系数，删除也可以作为性能优化，但不是当前优先项。

对于 required arc，不能理解成“每条生成列都必须包含这条弧”。列生成中的一条列是一台机器上的一个内部加工序列，master 会选择多条列共同覆盖任务。右分支要求的是聚合变量 `sum_r a_ij^r lambda_r = 1`，即最终解中恰好有一条被选列使用该弧；它不是要求所有列都使用该弧。当前代码中的 `Node.isColumnCompatible()` 也只过滤 forbidden arc，并不会要求每条列都包含 required arc。即使右分支会把 `i` 的其他后继、`j` 的其他前驱禁掉，仍然可能存在很多不含 `i,j` 的合法列；在带外包的模型里，任务 `i,j` 还可能被外包变量覆盖。因此 required arc 行必须保留，否则 child 可以不选择任何含该弧的内部列，从而违背该分支。

机器数分支和 tariff segment 分支也都是解级约束。每条内部列在机器数行中的系数都是 1，但单条列没有“是否满足机器数上下界”的概念，只有所有被选列的总数才满足。tariff 的 `z_s` 更不是 route pricing 生成的列，它是 master 中的外包分段变量；`z_s <= 0` 或 `z_s >= 1` 必须作为 master 约束保留。

所以更准确的判断是：pricing 侧的图/状态限制只能保证“新生成列不违反列级 forbidden 状态”，不能替代 RMP 中的聚合分支行。RMP 分支行还负责约束父节点继承来的旧列、表达解级分支语义，并提供当前节点 reduced cost 所需的 dual。若删除 required arc、机器数或 tariff 分支行，当前节点的 LP 松弛就不再是该 branch node 的松弛，pricing 的 reduced cost 也不再对应真实 RMP。

## 2026-05-19：forbidden arc 行的后续冗余判断

进一步确认后，分支约束在正式 child 列集中的保留策略可以区分处理。`forbidden arc` 是列级禁用约束：如果 child 已经完成筛列，所有含该弧的旧列都被删掉，并且 pricing 图也不再生成该弧，那么 RMP 中的 `sum x_ij = 0` 行在后续正式 LP 中确实可以视为冗余。它在首次 child LP 和 repair 阶段仍然需要存在，用来压掉父节点继承来的旧列并产生 repair/dual 语义；但在正式筛列成功以后，可以作为性能优化考虑删除或不再建立。

这个结论不适用于 `required arc`、机器数和 tariff segment。`required arc` 是解级聚合约束，含义是最终选中列集合中必须有一条列使用该弧，而不是每条列都必须使用该弧；机器数约束约束的是被选内部列数量之和；tariff segment 约束的是 master 中的外包分段变量 `z_s`。这三类约束都不能被“列已经兼容当前分支状态”替代，后续正式 RMP 中仍应保留对应行，并让其 dual 进入 reduced cost 或 master 求解逻辑。
## 2026-05-19：TWET-BPC 与旧 VRP 框架流程整体复核

本次重新按旧 VRP 的 `Tree / PC / BranchD.UpdateRouteSet / GCTabu / GCNGBB` 主流程，对当前 `TWETBPC` 下的 branch-and-price 代码做了一轮整体复核。当前结论是：如果只看 no-cut 的 branch-and-price 主流程，现在的框架已经基本对齐旧 VRP 的核心逻辑；如果按完整 BPC 理解，则 cut 接入仍只是占位，不能认为已经是完整 branch-price-and-cut。

1. 主流程已经基本对齐的部分

当前 `Tree.solve()` 的节点流程是：先由启发式 seed 构造初始列和 incumbent，再从优先队列弹出节点，构造该节点 restricted master，调用 `PC.solve()` 完成列生成，得到 LP relaxation 后检查整数性、更新 incumbent、按 bound 剪枝，最后按 tariff segment、machine count、arc 的顺序分支。这个结构和旧 VRP 的 `Tree.Solve()` 对应，只是当前把 child 的列修复推迟到 child 真正出队时做，而旧 VRP 是在父节点分支时立刻调用 `UpdateRouteSet()`。这属于时机和封装差异，不改变逻辑含义，而且当前延迟修复通常更省，因为不会提前为最终未被展开的 child 做昂贵的 repair。

child 列集合的处理现在也已经按旧 VRP 的语义修正。`Tree.prepareChildSeedColumns()` 先把父节点当前 restricted columns 原样传给 child，不提前按新分支状态或 reduced cost 筛列；child 出队后，`LP.construct()` 用“父节点列集合 + 当前 child 分支行”先求一次 LP。如果可行，再调用 `resetRestrictedColumnsByCurrentReducedCost()` 筛成正式 child 列集；如果不可行，则进入 `repairInfeasibleMaster()`，只对当前新增分支行加 artificial slack，再用 heuristic pricing 和 exact pricing 补列。这和旧 VRP `UpdateRouteSet()` 的“先带 child 分支行求父节点当前 LP，必要时 AddSlack + FindFeasible，成功后再筛 route set”在流程意图上是一致的。

pricing 层也基本符合旧 VRP 的分层：当前先跑 `HeuristicPricingEngine`，再跑 `PaperDominanceExactPricingEngine` 或普通 `ExactPricingEngine`。repair 模式下，启发式 engine 会反复补列直到耗尽或 slack 归零，然后 exact engine 兜底；这和旧 VRP “优先用 GCTabu.FindFeasible，Tabu 耗尽后用 GCNGBB.FindFeasible”的思路一致。普通列生成里，当前 PC 会把 heuristic 和 exact 在同一轮 dual 下生成的列合并后统一加入，再重新求解；旧 VRP 的启发式内部可能先加列并更新 LP 后再进 exact。这是封装和效率差异，不影响正确性，因为下一轮 pricing 会基于新 dual 继续找列。

分支约束和 pricing dual 的方向也基本正确。machine count 约束的 dual 在 pricing 初始 label 中扣除；arc branch 约束的 dual 通过 `getArcDual(from,to)` 在扩展弧时扣除。required arc、machine count 和 tariff segment 都是解层面的聚合约束，必须保留在 RMP 中；forbidden arc 在正式筛列成功后理论上可能变成冗余行，但当前保留它主要是为了统一流程和处理父节点继承列，不是逻辑错误。

2. 当前仍然存在的主要问题

第一个问题是 cut 部分还不是完整 BPC。`SubsetRowCutGenerator` 当前明确是 placeholder，返回空 cut；`TWETCut` 和 `CutPool` 也只是元数据容器，`LP.addCuts()` 只是把 cut id 放进 activeCutIds，并没有在 `LP.buildModel()` 里真正添加 cut 行，也没有把 cut 系数写入已有列和新列。进一步说，`PC.solve()` 现在即使某天真的 separated 出 cuts，也只是 `addCuts()` 后重新求一次 LP，没有像旧 VRP 的 `PC.Solve()` 那样回到 price-and-cut 外层循环继续 pricing。因此当前 no-cut branch-and-price 可以评价为基本成形，但完整 BPC 还没有真正接上 cut。

第二个问题是 child 正式筛列后的可行性保护仍偏工程化。`resetRestrictedColumnsByCurrentReducedCost()` 只按 reduced cost 阈值和列数上限选择低 reduced-cost 列，没有显式强制保留当前 LP 中所有正值列或当前可行基相关列。旧 VRP 也依赖较宽的 `m_addin_red_cost` 和 `m_initial_col_number` 降低风险，但它同样不是严格证明。当前 TWET 版本在筛列后会重新求 LP；如果筛列极端情况下删掉了维持 coverage 或其他约束所需的列，而 repair slack 又只挂在当前新增分支行上，那么该 child 可能被误判为 infeasible。建议后续优化为：筛列时先无条件保留当前 LP 正值列，再按 reduced cost 补足候选列。这样更接近“保留当前可行解，再压缩列池”的安全语义。

第三个问题是求解结果里的 `bestBound` 报告还不严格。当前 `Tree.solve()` 用已处理节点 LP 值的最小值更新 `bestBound`，这更像“见过的最好下界”，不是严格的全局 open-node lower bound。旧 VRP 最后会结合队列中的 `sudo_cost` 做 lower bound 汇总。当前剪枝本身主要依赖当前节点 LP 值和 incumbent，不会因为这个字段直接错剪，但最终输出的 bound/status 可能偏乐观或不够严谨。后续如果要把 BPC 结果作为精确证明，需要把 open queue 的节点 bound、已处理节点 bound 和 node limit 状态分开记录。

第四个问题是当前 RMP 默认假设存在 outsourcing tariff function。`LP.buildOutsourcingTariffConstraints()` 总会添加 `sum z_s = 1`。如果某个数据没有外包分段函数，`outsourcingTariffSegments` 为空时会形成空表达式等于 1 的不可行模型。现在带外包的数据路径下通常有 tariff block，所以不一定触发；但如果要兼容无外包版本，需要加 dummy segment `G(0)=0` 或在无 tariff 时跳过整组外包分段约束。

3. 与旧 VRP 不一致但目前可接受的地方

当前分支顺序是 tariff segment、machine count、arc。旧 VRP 没有 tariff segment 分支；对于当前 SP2 外包分段建模，先分 `z_s` 是合理的，因为即使内部列变量和 arc 都整数，LP relaxation 中 `z_s` 仍可能是多个 tariff segment 的凸组合。machine count 再到 arc 的顺序和旧 VRP 的车辆数优先、再路由结构分支是一致的。

当前 `PaperDominanceGraph` 的设计和论文伪代码不是逐字相同，但主逻辑是对齐的：每个 reachable-set node 维护真实 label 集合和 envelope，而不是只保留一个无法恢复路径的聚合 label；插入时先用 superset node 的 `g` envelope 判断 set dominance，不能占优则插入 label，并沿 successor 传播、删除被 predecessor envelope 占优的 label。这个处理比“node 里只有一个聚合 label”更适合 TWET，因为最终列需要恢复具体序列，而序列影响 setup time/cost 和真实列成本。当前没有发现框架级错误；后续若要提高效率，可以继续缓存 ancestor/descendant 或优化 terminal node 查找，但这属于性能问题。

当前 exact pricing 的 `maxExactPricingColumns` 只是单轮返回列数量上限，不等价于“证明已经没有负 reduced-cost 列”。但 PC 会在加列、重解 LP 后继续 pricing，直到所有 engine 都没有返回新 active 列。因此只要 exact pricing 在某一轮没有被 active/duplicate 逻辑误空，单轮上限本身不是 correctness 风险。现在已经避免 active column 和本轮 duplicate 消耗返回上限，这一点和旧 VRP “计入 add-in 前查 pool”方向一致。

4. 当前建议的后续检查顺序

短期如果继续完善 no-cut branch-and-price，优先处理 `resetRestrictedColumnsByCurrentReducedCost()` 的正值列保护和 bound/status 报告，这两个不涉及大重构，能直接提高稳健性和结果可信度。cut 相关暂时可以继续保持 placeholder，但需要在文档和输出中明确“当前是 no-cut B&P，不是完整 BPC”。如果后续真的接 SRI/cut，再必须同时完成三件事：RMP cut 行建模、列的 cut 系数接入、加 cut 后回到 pricing 循环。

## 2026-05-19：关于 child 筛列是否会丢掉基解列的补充

针对 `resetRestrictedColumnsByCurrentReducedCost()` 是否会把维持可行性的基解列筛掉，本次补充澄清如下。正常情况下，用户的判断是对的：如果 child 第一次 LP 已经可行，那么当前正值基列的 reduced cost 通常为 0；当前 `branchSeedReducedCostAllowance=5000.0` 很宽，所以这些列会进入候选集合。只要 `branchSeedColumnLimit` 足够大，当前基解对应的列大概率会保留下来，从而筛列后的 restricted RMP 仍然可行。

前面说的“可能筛掉可行性列”不是指常规情况下必然会发生，而是指这个筛列步骤在代码上没有显式保证“所有当前正值列必须保留”。当前实现会先收集所有 reduced cost 小于阈值的列，然后按 reduced cost 排序，再取前 `maxColumns` 条。如果存在大量 reduced cost 为 0 或非常接近 0 的退化列，且候选数超过 `branchSeedColumnLimit`，排序的 tie-break 只按 column id 处理，那么某些当前正值列理论上可能排在截断位置之后。这个风险在当前参数下很低，但它是工程假设，不是数学保证。

因此更稳的后续修改不是改变 reduced-cost 筛列思想，而是在筛列前先把当前 LP 中 value > tolerance 的列无条件放入 selected，再用低 reduced-cost 列补足到上限。这样可以保留旧 VRP “用 reduced cost 压缩 child route set”的流程，同时把“当前可行基被筛掉”的边界风险消掉。

## 2026-05-19：`resetRestrictedColumnsByCurrentReducedCost()` 的调用时机

本次确认 `resetRestrictedColumnsByCurrentReducedCost()` 只有两个调用点，语义都是“child 已经有一个可行 LP 后，把当前列池筛成正式 child restricted column set”。第一个调用点在 `PC.solve()` 中：child 节点第一次 `lp.solveRelaxation()` 已经可行时，直接按当前 LP 的 reduced cost 和分支兼容性筛列，然后重新建 LP 求解。这对应旧 VRP `UpdateRouteSet()` 中“首次 child LP 可行后筛 route set”的路径。

第二个调用点在 `repairInfeasibleMaster()` 末尾：如果 child 第一次 LP 不可行，则先进入带 artificial slack 的 repair LP，通过 `engine.findFeasible(lp)` 反复补列；只有当 `lp.isNoSlack()` 为真，即 slack 被真实列压回 0 后，才调用该函数筛列，然后关闭 repair mode，回到正常 RMP 求解。这对应旧 VRP `FindFeasible()` 成功以后再筛 route set 的路径。

因此它不是每轮普通 pricing 后都会调用，也不是 root 节点默认调用。它只服务于 child 的初始列集压缩：要么 child 首次 LP 已经可行后筛一次，要么 findFeasible/repair 成功后筛一次。

## 2026-05-19：child 筛列增加正值列优先保留规则

根据前面的边界分析，本次把 `LP.resetRestrictedColumnsByCurrentReducedCost()` 的筛列规则改成两阶段。第一阶段先检查当前 LP 中列变量值为正的列，并在分支兼容的前提下无条件放入 child 的正式 restricted column set；第二阶段才按 reduced cost 从小到大补充其他候选列，直到达到 `branchSeedColumnLimit`。

这个修改的目的不是改变旧 VRP 的 reduced-cost 筛列思想，而是补上一个显式安全约束：child 首次 LP 可行或 repair 成功时，当前正值列构成当前 LP 解的一部分；理论上这些基列 reduced cost 通常为 0，但如果存在大量 reduced cost 为 0 的退化列，单纯排序截断仍可能把某些正值列挤掉。现在先保留正值列，就能保证“当前已经找到的可行组合”不会被筛列上限截断。`branchSeedColumnLimit` 仍然控制补充的非正值低 reduced-cost 列数量；如果正值列本身超过该上限，则以上限为软约束，优先保证可行性。

验证：针对 `Basic/Common/HEU/Output/TWETBPC` 子集运行 `javac -encoding UTF-8 -cp D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;target\classes -d target\twetbpc-compile ...` 通过，仅有历史 deprecated API 提示。

## 2026-05-19：`bestBound` 的语义和线性外包函数的影响

本次澄清 `Tree.solve()` 里 `bestBound = Math.min(bestBound, solution.getObjectiveValue())` 的问题。这里不是说某个节点的 LP relaxation 不能作为下界；对最小化问题，每个节点完整列生成后的 LP 值当然是该节点子树的下界。问题在于当前代码取的是“已经处理过节点 LP 值的最小值”，这个值通常在 root 节点就达到最小，后续子节点 LP bound 往往只会更高，因此 `bestBound` 很可能长期停留在 root LP bound，无法反映分支树收敛过程中全局下界被逐步抬高。

严格的 B&B 下界应该更接近“当前仍未关闭的 open nodes 的最小下界”。如果队列为空并且所有节点都被关闭，则最终下界应当等于 incumbent；如果达到 node limit 或 time limit，则应报告 open queue 中最小节点下界作为当前全局 LB。当前字段作为“见过的最弱有效下界”通常仍是合法下界，但太松，不能作为最终证明质量的严谨指标。

如果用户问的是目标函数本身是否线性，例如外包函数 `G(B)=B`，这和 `bestBound` 的树搜索语义是两件事。`bestBound` 的报告问题不会因为目标是线性就消失；即使 RMP 目标完全线性，root LP bound 也可能一直被当前 `min` 逻辑保留下来。另一方面，如果讨论的是外包 tariff segment 变量，`G(B)=B` 这种全局线性函数确实不需要复杂的 segment 选择分支：可以只保留一个覆盖全域的线性段，或者直接用线性成本项建模。若人为把同一条直线切成多个 segment，LP relaxation 中 `z_s` 可能分数，但目标值不会因此变错；这时继续对 `z_s` 分支只是冗余工作，不是必要的精确性条件。

## 2026-05-19：按旧 VRP 语义修正 `bestBound` 汇总

本次把 `Tree.solve()` 中的 `bestBound` 汇总逻辑改成更接近旧 VRP 的 `sudo_cost/lower_bound` 语义。原实现每处理一个节点就取 `Math.min(bestBound, solution.obj)`，这会让最终 LB 很容易停在 root LP bound。新实现中，节点处理期间的报告 bound 取“当前节点 LP bound”和“open queue 中最小 pseudoCost”的较小值，并且不允许超过 incumbent；求解结束时，如果队列已经为空，说明所有节点已经关闭，最终 LB 直接取 incumbent；如果因为节点上限停止且队列非空，则最终 LB 取 `queue.peek().pseudoCost`，即当前 open nodes 中最小的伪下界。

这个修改不是改变剪枝逻辑，而是修正输出 bound 的含义，使其更接近旧 VRP 最后用 `queue.peek().sudo_cost` 修正 lower bound 的做法。当前 `pseudoCost` 仍然是 child 入队时继承的父节点 LP bound，因此它是排序和报告用的保守伪下界，不是 child 自己完整求解后的精确 bound；但这已经比“永远保留已处理节点最小 LP 值”更符合分支树状态。后续如果要进一步严谨，可以在 child 入队前或出队后维护每个 open node 的更精确 LP bound。

同时补充外包线性函数的判断：如果 `G(B)` 是全局线性函数，例如 `G(B)=B`，只要数据中至少保留一个 tariff segment 覆盖可能的 baseline，就不会因为 segment 建模影响目标值。此时 tariff segment 分支可以视为冗余优化点，而不是精确性必需项。

验证：针对 `Basic/Common/HEU/Output/TWETBPC` 子集运行 `javac -encoding UTF-8 -cp D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;target\classes -d target\twetbpc-compile ...` 通过，仅有历史 deprecated API 提示。
## 2026-05-19：关于 lower_bound 取 min 和 incumbent 截断的补充

本次进一步澄清旧 VRP 里 `lower_bound = min(current lower_bound, queue.peek().sudo_cost)` 的含义。这里的 `min` 不是严格意义上“open nodes 全局下界的最佳更新方式”，而是旧代码的保守汇总写法。旧 VRP 在处理非整数节点时会把 `lower_bound` 临时设成当前节点 LP 值，最后如果队列非空，再用 open queue 中最小的 `sudo_cost` 修正；因为历史记录里可能还保留 root 或更早节点的较低 bound，所以继续取 `min` 会让报告值偏保守，甚至长期停在 root LP bound。这个值通常仍是合法下界，但不一定是最紧的当前 open-node 下界。

当前 TWET-BPC 没有在最终返回时继续照抄这个 `min`。现在求解结束时如果队列为空，说明所有节点已经关闭，最终 `bestBound` 直接取 incumbent；如果队列非空，说明因为节点上限等原因还有 open node，则最终 `bestBound` 取 `queue.peek().pseudoCost`，也就是当前 open queue 里最小的伪下界。运行中展示用的 bound 可以取当前节点 LP bound 和 open queue 最小伪下界的较小值，只作为状态输出，不改变剪枝逻辑。

同时澄清 `bound` 超过 incumbent 时截到 incumbent 的语义。对最小化问题，任何有效 lower bound 都不应该大于当前 upper bound/incumbent；如果报告层出现 `LB > incumbent`，通常是因为伪下界、数值误差或状态汇总方式导致的输出异常。把报告值截到 incumbent 只是为了保持输出区间合法，不表示算法可以因此直接宣告最优。真正可以宣告最优的条件仍然是 open queue 为空，或者所有 open node 的有效下界都已经不小于 incumbent 并被正常剪掉。

旧 VRP 真正用于证明和剪枝的不是末尾这个 `min`，而是搜索过程中的两处 bound 剪枝。第一处是在节点刚从队列弹出时检查 `node.sudo_cost > best_cost + tolerance`，如果成立直接跳过该节点，不再建 LP；第二处是在该节点 LP/列生成完成后，如果 `lp.solution_cost + tolerance > best_cost`，则该节点子树被 bound 剪掉。`sudo_cost` 是节点入队时继承的父节点 LP bound，通常比较弱，但它仍然是该子树的有效下界；`lp.solution_cost` 是当前节点真正求解后的更强下界。

当前 TWET-BPC 已经有第二类剪枝，即节点 LP 求解后用 `solution.getObjectiveValue()` 和 incumbent 比较并关闭节点；但还没有完全接上旧 VRP 第一类“弹出节点前按 `pseudoCost` 预剪枝”。因此当前正确性不依赖这个预剪枝，最多会多求一些本可以提前跳过的节点。如果 `PriorityQueue` 以 `pseudoCost` 从小到大排序，那么当 `queue.peek().pseudoCost >= incumbent` 时，理论上所有剩余 open nodes 的伪下界都不小于 incumbent，可以直接终止或清空队列；旧 VRP 只是逐个弹出并跳过，没有利用这一点做批量清空。后续如果要进一步优化，可以在 TWET `Tree.solve()` 弹出节点前或弹出后建 LP 前加这层判断。

进一步复核旧 VRP 后，需要修正一个表述：旧代码的 `lower_bound` 不是完全一直停在 root bound。初始化时它确实先被设为初始启发式成本，随后在每个非整数、需要继续分支的节点处会执行 `data.m_configure.lower_bound = lp.solution_cost`，调试输出里的 gap 也使用 `min(data.m_configure.lower_bound, lp.solution_cost)`。因此运行过程中它会跟随当前被分支节点的 LP 值更新。问题在于，如果搜索因时间限制或其他原因提前停止，最终 `lower_bound` 只用最后记录值和 `queue.peek().sudo_cost` 做保守汇总，并不严格等于所有 open nodes 的最紧全局下界；这种情况下 gap 可能偏大，也可能不够严谨。只有当队列为空时，旧代码才把 `lower_bound` 直接设为 `best_cost`，这时才是完整证明最优。

关于 `sudo_cost` 剪枝，旧代码的 `PriorityQueue<Node>` 通过 `Node.compareTo()` 按 `sudo_cost` 升序排列。因此一旦弹出的最小 `sudo_cost` 都已经大于 incumbent，后面所有节点的 `sudo_cost` 都不会更小，理论上可以直接结束搜索。旧代码写成逐个 `poll` 后 `continue`，不会再建 LP，但会继续把队列弹空；这是实现上不够直接，但不会多求 LP。当前 TWET-BPC 目前只有节点 LP 求解后的 incumbent 剪枝，尚未做建 LP 前的 `pseudoCost` 预剪枝，所以在这个边界下确实可能多求解本可跳过的节点。后续可以直接在 `Tree.solve()` 的 while 循环开头加：如果 `queue.peek().pseudoCost >= incumbent`，则清空队列或 break。

再进一步修正对旧 VRP 最终下界的判断：旧代码在未求完且队列非空时执行 `lower_bound = min(lower_bound, queue.peek().sudo_cost)`，这个不是明显 bug。循环中的 `lower_bound` 会被当前处理节点的 `lp.solution_cost` 覆盖，而当前节点 LP bound 可能大于队列中尚未处理节点的最小 `sudo_cost`；如果不取 `min`，报告的下界可能超过 open tree 中已知的最小合法下界。这里取 `min` 的作用正是把报告值压回安全下界。它可能仍然偏弱，因为 `sudo_cost` 只是父节点 bound 继承下来的伪下界，但作为未完成搜索时的安全 LB 是合理的。因此当前问题不是“旧 VRP 下界计算错误”，而是它的未完成 gap 可能不够紧。
## 2026-05-19：BPC 小算例整树求解对拍

本次新增 `HEU.SmallBPCBatchTest`，用于把当前 TWET-BPC 和 `ArcFlowModel` 在小规模随机算例上直接对拍。测试不依赖启发式 seed 的额外 ALNS，关闭 BPC 文件输出和 console 输出，只保留 RMP、pricing、branch tree 和 solution validator 的主流程。测试结果写入 `test-results/bpc/2026-05-19-small-bpc.csv`。

随机部分共 8 个算例，规模为 5、6、7 个任务和 2 台机器，均带 setup time、setup cost、外包成本和线性 tariff。8 个算例中 BPC 目标值、best bound 与 ArcFlow 精确值全部一致，gap 均为 0，`BPCSolutionValidator` 均返回可行。所有随机算例都在根节点闭合，状态为 `ROOT_PROCESSED`，这是当前 `Tree.finalStatus()` 的语义：只处理根节点时即返回该状态；如果此时 bound 与 incumbent 相等且队列已闭合，应视为根节点证明完成，而不是未完成。

为了检查真实分支路径，又构造了一个两段凹折扣 tariff 的诊断算例。该算例使 LP relaxation 可以分数使用第二段 tariff，因此会触发 `TariffSegmentBrancher`。测试结果为 ArcFlow=10.0、BPC=10.0、best bound=10.0，处理 3 个节点、触发 1 次分支、pricing 6 轮、validator 可行，结果写入 `test-results/bpc/2026-05-19-small-bpc-branch.csv`。这说明当前 no-cut branch-and-price 的根节点求解、tariff 分支、child 入队/出队、RMP 重建、pricing 修复和最终 incumbent/bound 汇总在该小例子上能够闭合。

当前结论是：不考虑 cut 的情况下，主框架与旧 VRP 的 branch-price 主流程已经基本一致；差异主要是问题特有的分段函数定价、外包 tariff 分支和当前延迟到 child 出队时修复列集合。该差异前面已经确认不改变主流程语义。仍需注意的是，本次诊断只触发了 tariff 分支，尚未专门构造 fractional arc 分支算例；后续如果要验证 arc branching，可继续构造禁用外包或线性外包成本下的分数 arc 根节点例子。

## 2026-05-19：40 任务中等规模 BPC 诊断

本次新增 `HEU.MediumBPCBatchTest`，用于不再依赖 ArcFlow 对拍、而是直接观察 40 任务 BPC 自身的节点、分支、pricing、上下界和最终解验证。测试入口默认只跑一个 case；如果要跑多个 40 任务算例，建议从脚本层面按 case id 启动多个独立 JVM，而不是在同一个 JVM 中连续跑很多 case。原因是 40 任务 pricing 会产生较多临时 label、分段函数和 dominance envelope；连续在同一 JVM 中跑多个 case 时，case 0 后接 case 1 曾出现内存压力，而分开启动 JVM 后三个测试 case 都能正常闭合。

测试结果如下。case 0 是线性外包成本，根节点直接闭合，目标值和下界均为 `90.10`，状态为 `ROOT_PROCESSED`，未触发分支，耗时约 `3.3s`。case 1 也是线性外包成本，但触发了真实 arc 分支，最终目标值和下界均为 `73.34`，处理节点数 `12`，分支调用 `7` 次，pricing `80` 轮，生成列 `2785` 条，耗时约 `16.7s`。case 3 使用两段凹折扣 tariff，触发了 `1` 次 tariff segment 分支和 `8` 次 arc 分支，最终目标值和下界均为 `115.20`，处理节点数 `14`，pricing `78` 轮，生成列 `1915` 条，耗时约 `10.45s`。三个 case 的 `BPCSolutionValidator` 均返回可行，gap 均为 0。

本次测试还暴露了一个和旧 VRP `sudo_cost` 预剪枝对应的缺口。case 1 在修复前曾运行到某个非根节点时，已经有 incumbent `73.34`，而队列弹出的节点 `pseudoCost=73.462895` 已经不可能改进 incumbent。旧 VRP 在这种情况下会用 `sudo_cost` 直接跳过该节点，不再建 RMP 和 pricing；当前 TWET-BPC 当时缺少这层预剪枝，仍继续构建 LP 并进入 pricing，最终在 `PaperDominanceGraph.mergeGEnvelopes` 附近触发内存膨胀。现在 `Tree.solve()` 在非根节点建模前增加 `pseudoCost >= incumbent` 的预剪枝。由于优先队列按 `pseudoCost` 升序，当前节点已经不可能改进时，剩余节点也不会更好，因此可以直接关闭队列并结束搜索。这个修改不改变正确性，只是补上旧 VRP 已有的提前关闭逻辑，并避免明显无意义的 pricing。

同时，`BPCTraceSummary.onNodePicked()` 也补了一个计数修正：被 pseudoCost 预剪枝的节点不会进入 `onMasterSolved()`，但它已经从队列中弹出并被处理，因此应当计入 processed nodes。否则 trace 中的节点数会比求解结果对象少一类预剪枝节点。

本轮有效输出文件为 `test-results/bpc/2026-05-19-medium-bpc-40-case0.csv`、`test-results/bpc/2026-05-19-medium-bpc-40-case1.csv` 和 `test-results/bpc/2026-05-19-medium-bpc-40-case3.csv`。此外，调试慢例时可以通过 `-Dtwet.bpc.verbose=true` 打开 BPC 过程输出；如果需要按“等待 10 分钟后停止”的方式人工观察，verbose 日志中能直接看到最后一次 incumbent、bound、节点和分支状态。
## 2026-05-19：再次复核 TWET-BPC 与旧 VRP 流程是否完全一致

这次重新按旧 VRP 的 `Tree / PC / Branch*.UpdateRouteSet / GCTabu / GCNGBB` 和当前 `TWETBPC` 的 `Tree / PC / LP / Node / PricingEngine` 做了一轮对照。需要修正前面的表述：当前只能说 no-cut branch-and-price 的主流程骨架已经基本对齐旧 VRP，而不是旧 VRP 的所有实现细节、剪枝时机、cut 循环和 DSSR 状态语义都已经一比一复刻。前面说“流程逻辑一样”，更准确的含义是：节点出队、建 restricted master、列生成、整数性检查、分支、child 列修复这些主干顺序已经一致；但还有若干地方属于“问题特化或暂未实现完整旧版能力”。

第一，`sudo_cost/pseudoCost` 出队前预剪枝已经补上。旧 VRP 在 `Tree` 中弹出节点后、建 LP 前会检查 `node.sudo_cost > best_cost + tolerance`，满足时直接跳过该节点。当前 TWET-BPC 之前确实缺这一层，所以 40 任务 case 中出现过已经有 incumbent 后，`pseudoCost` 明显不可能改进的节点仍继续建模和 pricing，造成无意义计算和内存压力。现在 `Tree.solve()` 已经在非根节点建模前加了 `pseudoCost >= incumbent` 的预剪枝。当前实现因为优先队列按 `pseudoCost` 升序，所以一旦队头都不可能改进，直接清空队列结束；旧 VRP 是逐个 `poll` 后 `continue`。这两者在逻辑上等价，当前写法更直接，但不是旧代码逐行同款。

第二，普通 pricing 主循环和旧 VRP 仍有轻微接口差异。旧 VRP 的 `PC` 是启发式 `GCTabu` 和精确 `GCNGBB` 交替推进，启发式找到列后会更新 LP，再进入后续精确定价；当前 TWET-BPC 的 `generateColumns()` 在一轮中先调用启发式和精确 engine，合并新列后统一加入并重解 LP。这个差异通常不影响正确性，因为下一轮会基于新 dual 继续 pricing；但从旧 VRP 的效率语义看，旧版更偏向“只要启发式能持续找到列，就尽量先用启发式”，当前封装可能让精确 engine 在某些轮次更早参与，效率上不完全相同。repair 模式中当前已经更接近旧版：启发式 engine 可以反复找列直到耗尽或 slack 归零，再由 exact engine 兜底。

第三，`UpdateRouteSet / FindFeasible` 的主语义已经对齐，但不是旧版完全同一时机。旧 VRP 在父节点分支时立即给 child 做 `UpdateRouteSet()`，即先用父节点当前列加 child 分支行求一次 LP，不可行才加当前分支行 slack 并调用 `FindFeasible()`；成功后再筛 child 的 route set。当前 TWET-BPC 把这个动作推迟到 child 真正出队后执行。这个时机差异不改变逻辑含义，反而避免为最终不会展开的 child 提前做修复。当前也已经按旧版语义收紧为只对当前新分支行加 repair slack，不再默认给 coverage 行加 slack。

第四，DSSR/ng-set 的 reset 语义当前还没有旧版对应物。旧 VRP 的 `GCNGBB.Reset()` 主要服务于 ng-route/DSSR 状态：如果启发式新加了列，精确定价中上一轮逐步扩充出来的 ng-set 状态可能不再适合作为下一次精确定价的延续，因此需要 reset。当前 TWET-BPC 的 exact pricing 是基础 forward labeling + dominance graph，没有 DSSR/ng-set 状态，所以当前 `reset()` 基本只是接口占位。也就是说，这里不是漏掉一个必须重置的缓存，而是当前 exact pricing 还没有实现旧 VRP 那套 DSSR 状态机制；后续如果加入 ng-route/DSSR，再需要恢复旧版 reset 语义。

第五，cut 部分不是完整 BPC。旧 VRP 的 `PC.Solve()` 是 price-and-cut 外层循环，分离出 cut 后会重新回到 pricing，因为新 cut 的 dual 会改变 reduced cost。当前 TWET-BPC 的 cut 类和 cut pool 仍是占位，`SubsetRowCutGenerator` 不产生真实 cut，`LP.buildModel()` 也没有真实 cut 行和列系数。即使后续接入真实 cut，当前 `PC` 也需要改成“加 cut 后重新 pricing”的外层循环。因此当前更准确的定位是 no-cut branch-and-price 框架，而不是完整 branch-price-and-cut。

第六，分支类型和顺序有问题特化差异。旧 VRP 没有 outsourcing tariff segment 分支；当前 SP2 外包分段模型必须处理 `z_s` 的分数解，因此当前顺序是 tariff segment、machine count、arc。这个和旧 VRP 不同，但属于当前问题新增结构。对于 arc 分支，当前 forbidden arc 行在正式 child 列集合筛完后理论上可能冗余，因为所有含该弧的旧列已被删掉、pricing 也不会再生成该弧；required arc、machine count 和 tariff segment 都是解层面的聚合约束，不能删。这个判断已经写入前面的专题记录。

第七，当前 exact pricing 仍是最基础 forward labeling，不等于旧 VRP 的完整 pricing 栈。旧 VRP 有多种 GC 实现、ng-route/DSSR、启发式 tabu 列生成、cut reduced-cost 快速项等。当前 TWET-BPC 已经有启发式 pricing、paper-style dominance graph 和基础 exact pricing，但还没有 NG/DSSR、双向 labeling、SRI/cut reduced-cost 项。这个不是主流程 bug，而是能力范围差异。

第八，`bestBound` 和节点状态输出已经比前面更接近旧 VRP，但仍要区分“证明用状态”和“输出摘要”。当前已经修正为队列空时 `LB=incumbent`，队列未空时用 `queue.peek().pseudoCost` 作为 open-node 伪下界；同时补了 pseudoCost 预剪枝。这和旧 VRP `sudo_cost` 的安全下界语义一致。区别是当前在 pseudoCost 已经不可能改进时直接清空队列，而旧版逐个跳过；这是实现方式差异，不是逻辑缺失。

当前结论为：如果只讨论 no-cut branch-and-price 主流程，当前 TWET-BPC 已经基本和旧 VRP 的框架一致，并且最近补上了之前确实缺失的 `sudo_cost/pseudoCost` 预剪枝。还不能说“完整 BPC 和旧 VRP 完全一样”，因为 cut 循环、DSSR/ng-set 状态、完整 pricing 栈和部分旧版效率细节还没有实现。后续如果继续追求和旧 VRP 完整版一致，优先顺序应是：先接真实 cut 后的 price-and-cut 外层循环，再做 NG/DSSR exact pricing reset 语义，最后补 cut reduced-cost 项和更强列生成统计。

## 2026-05-19：普通 pricing 循环改为旧 VRP 的启发式优先节奏

本次只处理普通 pricing 循环和旧 VRP 不完全一致的问题，不动 cut 和 DSSR/ng-set。修改前，`PC.solve()` 在每一轮调用 `generateColumns()`，会在同一轮 dual 下依次调用所有 pricing engine，把启发式和 exact 找到的列合并后统一加入 RMP，再重解 LP。这样做通常不影响正确性，但和旧 VRP 的 `PC` 节奏不同：旧版更偏向先反复使用 `GCTabu`，只要启发式仍能找到列，就先加列、重解 LP，并继续从启发式开始；只有启发式耗尽后才进入更重的 `GCNGBB`。

现在 `PC.solve()` 已改成同样的控制方式：普通 pricing 循环中按 `pricingEngines` 顺序逐个调用 engine；某个 engine 找到并成功加入新列后，立即重解当前 RMP，并从第一个 engine 重新开始。由于当前 engine 顺序为 `HeuristicPricingEngine -> PaperDominanceExactPricingEngine/ExactPricingEngine`，这意味着只要启发式还能持续补列，就不会提前调用 exact pricing。若启发式没有新列，才进入 exact；若 exact 加列，也会重解 LP 并回到启发式。这个修改主要改善效率语义和旧 VRP 框架一致性，不改变 RMP、分支、repair slack、cut placeholder 或 exact pricing 内部逻辑。

repair 模式暂时保持原有实现。它已经是启发式可反复补列、exact 兜底的结构，和旧 VRP `FindFeasible` 的主语义较接近；本次不额外调整。

验证：针对 `Basic/Common/HEU/Output/TWETBPC` 子集运行 `javac -encoding UTF-8 -cp D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;target\classes -d target\twetbpc-compile ...` 通过，仅有历史 deprecated API 提示。随后运行 `HEU.SmallBPCBatchTest`，8 个随机小算例均与 `ArcFlowModel` 目标值一致，tariff 分支诊断例也得到 `BPC=ArcFlow=10.0`、`nodes=3`、`branches=1`、验证可行。

## 2026-05-19：只看流程框架时当前仍和旧 VRP 不同的地方

本次只按“流程和框架”复核，不讨论 TWET 分段函数、setup、外包成本、label 评估这些问题特化细节。普通 pricing 循环已经改成启发式优先、加列即重解并回到第一个 engine，因此不再作为差异项。

当前剩余的第一类差异是 cut 外层循环。旧 VRP 是完整 price-and-cut 框架：pricing 收敛后分离 cut，若加了 cut，则回到 pricing，因为 cut dual 会改变列 reduced cost。当前 `CutGenerator/CutPool/TWETCut` 仍是占位，`SubsetRowCutGenerator` 不产生真实 cut，`LP` 中也没有真实 cut 行和列系数。因此当前应表述为 no-cut branch-and-price 框架，而不是完整 BPC。后续接 cut 时，必须补“加 cut 后重新 pricing”的外层循环。

第二类差异是 DSSR/ng-route 状态。旧 VRP 的 `GCNGBB` 有动态 ng-set / DSSR 状态，`Reset()` 的意义是当启发式新加列、LP dual 改变后，精确定价的动态状态需要清理或重新开始。当前 exact pricing 没有 DSSR/ng-set，只是基础 forward labeling 加 dominance graph，因此 `reset()` 还是接口占位。这不是当前流程错误，而是旧版完整 exact pricing 能力尚未实现。

第三类差异是 child repair 的时机。旧 VRP 在分支生成 child 时立即执行 `UpdateRouteSet()`，当前 TWET-BPC 是 child 真正出队时才做首次 LP、repair 和正式列筛选。这个时机不同，但逻辑等价，而且当前更省，因为不会提前修复后来可能被 bound 剪掉的 child。只要 child 出队时仍先用父节点列集加当前分支行求 LP，不可行时只对当前分支行加 slack 修复，再筛正式列集，这个框架语义就是对齐的。

第四类差异是分支族。旧 VRP 没有 outsourcing tariff segment 分支，当前因为 SP2 外包分段变量 `z_s` 可能分数，所以多了 `TariffSegmentBrancher`，且当前顺序是 tariff segment、machine count、arc。这是当前模型需要的扩展，不是框架错误。若以后做不带外包或线性外包成本的版本，可以把 tariff 分支关闭或让它自然不触发。

第五类差异是输出和状态统计的实现方式。当前 `Tree` 在 `pseudoCost >= incumbent` 时直接清空队列结束，旧 VRP 是逐个弹出后跳过；由于队列都按伪下界升序，这两个逻辑等价，但不是逐行同款。当前 `bestBound/finalStatus/trace` 也是按 TWET 输出对象重新封装，不是旧 VRP 的控制台变量原样搬运。

因此当前严格说法是：no-cut B&P 主控流程已经和旧 VRP 基本一致；普通 pricing 循环、pseudoCost 预剪枝、child repair 语义这些之前差异较大的地方已经补齐。仍不同的部分主要是尚未实现 cut 循环、DSSR/ng-route 状态和旧版完整 pricing 栈，以及 SP2 外包分段带来的额外分支族。

## 2026-05-20：基于粗硬时间窗的静态不可行 arc 预处理
本次补上一个与旧 VRP `feasible_arc` 思路对应的静态预处理层。当前数据已经先用外包基准成本和 setup-cost advantage 得到每个任务的粗硬完成窗口 `[hardWindowStart[j], hardWindowEnd[j]]`，并进一步收紧 `CmaxH`。在这个基础上，可以安全删除一部分相邻弧：如果任务 `i` 即使取最早允许完成时间 `hardWindowStart[i]`，后接任务 `j` 后得到的最早完成时间 `hardWindowStart[i] + s[i][j] + p[j]` 仍然超过 `hardWindowEnd[j]`，那么 `i -> j` 在任何满足粗硬窗的列中都不可能出现。`0 -> j` 同理使用 `s[0][j] + p[j]` 判断。

实现上在 `Data` 中新增 `preprocessedArcForbidden`，并在构造流程和 `setPenaltyFunctions()` 前调用 `preprocessInfeasibleArcsByHardWindows()`。这里同时标记自环、回到 source、sink 出边、source 到 sink 等结构性非法弧。这个矩阵是全局过滤条件，不写入 `Node.arcState`，原因是它不是分支约束；如果把这些弧伪装成 `ARC_FORBIDDEN`，`LP.buildArcBranchConstraints()` 会为所有预处理弧额外建 forbidden-arc 行，导致 RMP 膨胀。

接入点上，`Node` 新增 `isArcForbidden(from,to)`，统一判断“显式 forbidden 分支”与“预处理不可行弧”。exact pricing 的 label 扩展、reachable set 构建、回 sink 检查，tabu heuristic pricing 的候选序列兼容性检查，以及 `ArcBrancher` 的分支候选扫描，都改用这个判断。RMP 建模仍使用 `getArcState()`，只对显式分支状态建约束行。这样后续 pricing 和 branch 都不会再考虑这些静态不可行边，但不会改变主问题的约束结构。

当前预处理只做一跳安全删除，不做多步 reachability、Vidal 式子序列摘要或动态 dual 相关 `H_ij` 收缩；这些仍属于后续性能强化。验证上，本次用 CPLEX classpath 编译 `HEU.SmallBPCBatchTest` 通过，并运行该测试：8 个随机小算例均与 ArcFlow 结果一致，额外 tariff 分支诊断例也通过。

复核时进一步确认，静态预处理禁弧不能只在新增列时过滤。`LP.addColumns()` 已经会调用 `Node.isColumnCompatible()`，因此 pricing 新生成的列不会带入静态禁弧；但 `LP.construct()` 会直接接收 root seed 或 child 从父节点继承的 restricted columns。如果历史列池、seed 列或后续调试入口里残留了包含静态禁弧的列，第一次 LP 仍可能把这些已经证明不可行的列放回 RMP。为避免这个接口漏洞，本次在 `Node` 中补充 `isColumnPreprocessingCompatible()`，只检查 `Data.preprocessedArcForbidden`，不检查当前分支状态；`LP.construct()` 只用这个静态检查过滤输入列。这样既能保证全局不可行列不进入 RMP，又不破坏旧 VRP 对齐过的 child 首次 LP 语义，即 child 仍然先继承父节点列集并带新分支行求可行性，而不是提前按分支状态筛列。

临时统计程序对 6、7、8、20、40 个任务的随机算例做了删边统计。结构性禁弧包括自环、回 source、sink 出边、source 到 sink 等固定非法弧；真正由粗硬时间窗删掉的是 job-job 弧。在测试样本中，6 任务例删掉 11/30 条 job-job 弧，7 任务例删掉 11/42 条，8 任务例删掉 20/56 条，20 任务例删掉 112/380 条，两个 40 任务例分别删掉 301/1560 和 423/1560 条。source-job 和 job-sink 在这些样本中没有被粗窗删掉，这符合当前规则：起点到第一个任务通常可通过等待进入窗口，回 sink 也没有单独硬窗。因此这个预处理在当前随机数据上确实能删掉相当一部分内部相邻边，主要收益会体现在 pricing 扩展、reachable set 构建、启发式 pricing 候选序列兼容性检查和 arc branching 候选扫描上。

进一步澄清这个预处理在 BPC 中的使用方式。`Data.preprocessedArcForbidden` 不是分支状态，也不是一个要写进 RMP 的约束集合，而是全局图过滤器。`Node.isArcForbidden(from,to)` 把两类禁弧合并起来判断：一类是 `Data` 预处理得到的静态不可行弧，另一类是当前节点显式 forbidden arc 分支。pricing 扩展 label 时先查这个函数，因此如果 `i -> j` 已经被预处理判定不可能，label 根本不会扩展到 `j`；reachable set 构建也不会把 `j` 放进去；label 回 sink 生成列时也会检查末端弧。启发式 pricing 同样在候选序列兼容性检查里调用这个判断，所以含静态禁弧的候选序列会被直接跳过。

分支里也使用同一个判断，但语义不同。`ArcBrancher` 在扫描 fractional arc 时会跳过已经被预处理禁掉的弧，因为这种弧不可能出现在任何合法列里，对它分支没有意义。如果它仍在 LP 解里出现，说明前面的列兼容性已经出错，而不是应该继续分支。相反，`LP.buildArcBranchConstraints()` 仍然只看 `Node.getArcState()`，也就是只为显式分支状态建 required/forbidden 约束行；预处理弧不会被建成 forbidden-arc 约束，否则 RMP 会因为大量静态禁弧膨胀。

因此，“删掉相应的列”只发生在两个防御性入口：`LP.addColumns()` 和 `LP.construct()`。正常情况下，pricing 已经不会生成含静态禁弧的列，所以 `addColumns()` 只是二次保险。`LP.construct()` 的过滤则是为了处理 root seed、父节点继承 restricted columns 或调试入口中可能残留的历史列。如果这些列包含粗硬时间窗已经证明不可行的弧，继续放入 RMP 会让主问题使用本不该存在的列。这里过滤的是静态预处理禁弧，不过滤当前 child 的分支状态；这样不会破坏旧 VRP 那种“child 首次 LP 先继承父节点列集并带新分支行求可行性”的流程。

关于旧 VRP 预处理是否已经全部搬过来，当前结论是没有，也不应该无差别全搬。旧 VRP `Data` 中主要有几类预处理：`TightTW0/TightTW1` 收紧 depot/customer 时间窗，`TightCapacity` 收紧车辆容量，`BuildReach` 预先构造按时间和容量索引的 forward/backward reach bitset，`BuildInRank` 为 label-setting 的入边排序提供加速。当前 TWET-BPC 已经接入的是与时间窗直接对应的部分：先用外包 baseline 和 setup-cost advantage 得到每个任务的粗 completion 硬窗，再收紧 `CmaxH`，然后基于这个粗窗删除静态不可能的相邻 arc。容量预处理在当前同质并行机模型里没有对应资源；旧 VRP 的 forward/backward time-index reach bitset 和入边 rank 属于更深一层的 label-setting 加速结构，目前基础 forward pricing 尚未实现；NG/DSSR、双向拼接和 cut reduced-cost 也属于后续强化，不是这次静态禁弧预处理的范围。

每轮 RMP 求解后的 dual 确实已经在 pricing 中用于更新“局部硬时间窗”。当前 `GC` 在扩展 `prevJob -> job` 时使用 `hWindowGamma(prevJob, job, lp)`，其中包含 setup-cost advantage、job dual 和外包 baseline，得到动态的 `hWindowStart/hWindowEnd`。随后先用 `isDirectExtensionTimeFeasible()` 做轻量过滤，再对 job penalty 调用 `setDomain(hStart,hEnd,true)`，把窗口外设为 `big_M` 并继续做分段函数运算。因此这部分不是没用，而是已经在每次 pricing 扩展时即时使用。

这类 dual 相关窗口不适合再写成全局禁弧矩阵。原因是它只对“当前 RMP dual 下寻找负 reduced-cost 列”有效，下一轮 LP 重新求解后 dual 会变；并且它通常依赖当前 label frontier 的左端时间，不只是一个固定的 `i -> j` 结构属性。即使某条弧在当前 dual 下不会产生负 reduced-cost 扩展，也不代表这条弧在原问题中不可行，更不代表后续分支不该考虑它。因此分支候选只能跳过静态预处理禁弧和显式 forbidden 分支，不能跳过动态 hard-window 过滤出来的局部禁弧。动态窗口继续留在 pricing 内部做扩展剪枝即可；若后续要加速，可以在单次 pricing 调用内部缓存一份“当前 dual 下的 pair 级快速判断”，但它只能作为本轮 pricing 的局部缓存，不能进入 `Node.arcState`，也不能影响 brancher。
## 2026-05-20：pricing 中动态硬时间窗的预计算

这次讨论集中在旧 VRP 代码里的 `BuildReach`、`BuildInRank` 和当前 TWET-BPC pricing 里动态硬时间窗的关系。旧 VRP 的 `BuildReach` 本质上是一个按资源状态预先建好的 reachability 索引：给定当前客户、时间或容量状态，可以用 bitset 快速得到仍可能到达的下一批客户，避免 label 扩展时每次都全量扫描并重复做时间窗/容量判断。它不是新的约束，而是把一部分“一定不可达”的判断提前压缩成查询表。当前 TWET 问题没有容量资源，而且时间状态由分段线性函数的定义域承载，不适合直接照搬成旧 VRP 那种离散时间/容量 bitset；但是其中“本轮 pricing 内可复用的可达性判断应缓存”的思想是可以用的。

旧 VRP 的 `BuildInRank` 是另一个加速结构，主要给 label-setting 中的入边或候选前驱排序。直观上，它把“哪些 predecessor 更有可能更早、更短或更有用”预先排好，后续 dominance 或扩展时不必每次重新排序。它同样不改变可行域，也不影响最优性，只影响搜索顺序和效率。当前基础 forward pricing 还没有引入这种入边排序；如果后续做双向 labeling、NG/DSSR 或更强 dominance graph，可以再考虑把类似 rank 结构接进来。

当前 `GC.isDirectExtensionTimeFeasible()` 的作用是轻量过滤。它在真正执行 `shift/add/normalize` 前，先用当前 label frontier 的最左端时间、`setupTime(prevJob,nextJob)` 和 `processingTime(nextJob)` 算出最早可能完成时间。如果这个最早完成时间已经超过本轮 dual 下的 `H_ij` 右端，或者超过 `CmaxH`，那么这条扩展不可能产生有效负 reduced-cost label，直接跳过。这个判断只是一层便宜的前置过滤，不能替代后面的分段函数运算，因为分段函数还要处理完整成本形状、窗口外 big_M、dual、setup cost 和 normalize。

原实现是在每次 label 扩展时重复计算 `hWindowStart/hWindowEnd`，并对 `data.penaltyFunction[j]` 的副本调用 `setDomain(hStart,hEnd,true)`。这在逻辑上是对的，但会重复做同一轮 pricing 中不变的事情。现在改成在 `GC.initialize(lp)` 中一次性预计算 `dynamicHStart[prevJob][job]`、`dynamicHEnd[prevJob][job]` 和 `dynamicJobPenalty[prevJob][job]`。由于一轮 pricing 内 RMP dual 固定，`H_ij` 也固定；即使 `setupCostAdvantage B_ij` 非零，窗口也只依赖 `(prevJob, job)`，所以按弧缓存仍然正确。若 setup cost 满足对应三角关系使 `B_ij=0`，这个缓存自然退化为“每个 job 一份窗口”的情形。

这里没有把动态硬时间窗写成全局禁弧矩阵。原因是这类窗口依赖当前 LP dual，只对本轮 pricing 的 reduced-cost 搜索有效；下一轮 RMP 重解后 dual 会变，窗口也要重新算。它也不应该影响 arc branching 候选，因为某条弧在当前 dual 下不值得扩展，并不等价于它在原问题中结构性不可行。静态粗硬窗预处理仍然放在 `Data.preprocessedArcForbidden` 中；动态 dual 硬窗只保留在 `GC` 的本轮缓存里。

本次修改后的流程为：每轮 pricing 初始化时先构造动态窗口缓存；扩展 `prevJob -> job` 时先查 `dynamicJobPenalty` 是否存在，再用 `dynamicHEnd` 做 `isDirectExtensionTimeFeasible()` 轻量过滤；通过后直接复用缓存好的 job penalty 函数，继续执行 `shift/add/normalize`。这样减少了重复计算和重复构造函数副本，同时不改变 pricing 的可行域语义。
## 2026-05-20：动态硬时间窗缓存的 job 级与 pair 级切换

在前一版实现中，`GC` 已经把每轮 pricing 的动态硬时间窗从“每次扩展时重复计算”改为“本轮 pricing 初始化时预计算”。本次进一步把缓存分成两种模式。`Data.precomputeSetupCostAdvantages()` 在计算 `B_ij` 时同步记录 `setupCostTriangleInequalitySatisfied`：只要存在某个 `B_ij > 0`，就说明 setup cost 在当前公式意义下不满足三角关系，动态窗口仍然依赖 `prevJob -> job`，必须使用二维 pair 级缓存；只有所有 `B_ij` 都为 0 时，`H_ij` 才不再依赖前驱，可以退化成 job 级一维缓存。

这不是把正确性建立在“数据应该满足三角不等式”的假设上，而是由实际预处理结果决定缓存形式。pair 级模式下，`dynamicJobPenaltyByPair[prevJob][job]` 保存每条弧对应的动态 job 函数副本；job 级模式下，`dynamicJobPenaltyByJob[job]` 只保存每个任务一份函数副本。扩展时统一通过 `getDynamicJobPenalty(prevJob, job)` 和 `getDynamicHEnd(prevJob, job)` 读取，因此后续 label 扩展逻辑不需要关心当前是哪种缓存。

这个优化的收益主要在满足三角关系或 `setupCost` 全 0 的数据上。此时每轮 pricing 不再为每个 `prevJob -> job` 重复构造同一个 `setDomain(hStart,hEnd,true)` 结果，而是每个 job 只构造一次。若数据不满足三角关系，则仍然走二维缓存，行为与前一版 pair 级实现一致，保证 `B_ij` 非零时不会错误地把前驱相关窗口合并掉。

验证上，针对 `Basic/Common/HEU/Output/TWETBPC` 子集运行 `javac -encoding UTF-8` 编译通过；随后运行 `HEU.SmallBPCBatchTest`，8 个随机小算例均与 `ArcFlowModel` 目标值一致，额外 tariff 分支诊断例也通过。

## 2026-05-20：关于 BuildReach、BuildInRank 和动态硬窗缓存的进一步澄清

这里需要澄清一个容易混淆的点：`setupCostTriangleInequalitySatisfied` 不是每次 pricing 都重新扫描所有 `B_ij` 得到的，而是在 `Data.precomputeSetupCostAdvantages()` 阶段随 `B_ij` 一次性预处理出来的实例级标记。pricing 阶段只读这个标记。若该标记为 `true`，说明所有 `B_ij=0`，动态硬窗只和任务 `j` 的 dual、外包 baseline、惩罚函数参数有关，因此每轮 pricing 只需要给每个任务构造一份 job 级动态窗口和 job penalty 函数。若该标记为 `false`，说明至少有一个 `B_ij>0`，动态硬窗仍依赖前驱 `i`，此时必须按 `prevJob -> job` 构造 pair 级表。也就是说，判断三角关系是输入数据预处理的一部分，不是 pricing 内部的重复开销。

`isDirectExtensionTimeFeasible()` 的用途也要限定清楚。它不是完整可行性判定，而是扩展前的便宜过滤。当前 label 的 `frontier.head.start` 可以看作这条部分序列最早还能落到的完成时间左端；如果再接 `nextJob` 后，`frontier.head.start + s(prev,next) + p(next)` 已经超过本轮动态硬窗右端 `H_ij^R` 或 `CmaxH`，那么后面做 `shiftX`、`setDomain`、`add`、`normalize` 一定也只会得到空函数或 `big_M` 状态，提前跳过可以省掉分段函数操作。它保守地只删“最早都来不及”的扩展，不会替代分段函数求值，也不会剪掉仍可能可行的扩展。

关于“时间藏在定义域里”的说法，更准确的表述是：TWET pricing 的 label 时间状态不是一个离散整数时刻或单一 earliest-time 标量，而是一整个分段线性 frontier 函数的有效定义域和函数形状。每轮 pricing 的动态 `H_ij` 窗口确实固定，可以预处理，这也是当前已经做的 job/pair 级缓存；但旧 VRP `BuildReach` 那种 `customer + time index -> reachable bitset` 还需要一个离散时间索引。若要在 TWET 中做类似结构，就必须先决定用哪个时间代表一个 label 状态，例如只用 `frontier.head.start` 做一跳过滤，或者离散化所有可能完成时间。前者当前已经在 `buildReachableSet()` 和 `isDirectExtensionTimeFeasible()` 中做了；后者会引入较大的内存和离散化误差/复杂度，暂时不作为基础 forward pricing 的必要部分。

`BuildInRank` 的作用是排序，不是剪枝。旧 VRP 会给每个节点的可能入边或前驱建立一个 rank，通常按距离、时间或某种便宜估计排序。label-setting 或 dominance propagation 需要扫入边/前驱时，可以优先看更可能产生好 label 的前驱，或者用 rank 辅助快速定位候选范围。它不会改变哪些弧可行，也不会影响 reduced cost 公式；收益主要是减少无效扫描和改善搜索顺序。当前 TWET forward pricing 仍是按任务编号扫描候选任务，后续如果 label 数增多，可以考虑给候选 `prevJob -> job` 按静态弧代价、setup time、粗硬窗紧张度或历史 reduced-cost 表现建 rank。

进一步复核旧 VRP 代码后需要补一句：旧 `BuildInRank()` 的具体实现是对每个目标 customer `cid`，把所有前驱 `i` 按 `GetDistance(i,cid)` 排序，然后写入 `m_in_rank[cid][i] = rank`。但在当前参考 `src` 的 Java 文件里没有检索到 `m_in_rank` 的真实使用点，说明它至少在这份代码中更像预留/历史加速结构，而不是主 pricing 流程实际依赖的剪枝条件。因此当前 TWET-BPC 暂时不接它不会影响流程正确性。

关于用 `BuildReach` 思路进一步替代 `isDirectExtensionTimeFeasible()`，可以做，但要区分两种层次。现在已经预处理了每轮 pricing 的动态窗口 `H_ij` 和 job penalty 函数；这相当于把“窗口本身”缓存了。若还想像旧 VRP 一样把“从某个末端任务、某个当前时间还能到哪些任务”也缓存，就需要再引入一个以当前完成时间为索引的 reach 表，例如 `reach[fromJob][t]`，其中包含所有满足 `t + s[from][j] + p[j] <= HEnd[from][j]` 的 `j`。这样 label 扩展时可以用 `frontier.head.start` 映射到 `t`，直接拿 bitset，再扣掉 visited set 和分支禁弧。

这个方向能减少每次 `buildReachableSet()` 里对所有 job 的循环判断，但它不是无代价的。第一，当前时间可能是 double，不一定天然对应整数下标；如果所有时间严格是整数，则可以直接用整数时间作为索引；如果存在小数时间，就不能简单用 `ceil(frontier.head.start)` 做索引，因为这会把真实时间 10.1 映射到 11，可能丢掉在 10.1 仍可行但按 11 判断不可行的候选。更安全的粗过滤方式是用 `floor(frontier.head.start)` 取一个可达集合超集，然后再做精确时间窗判断。第二，表规模是 `(n+1) * CmaxH` 级别的 bitset，若 `CmaxH` 很大，内存会明显增加。第三，分支禁弧是 node-local 的，若 reach 表只按本轮 dual 预处理，就还需要在运行时额外扣掉当前 node 的 forbidden arcs；若把 node 分支也编入 reach 表，则每个 B&B 节点都要重建一份。因此它是合理的后续性能优化，但当前 pair/job 级动态硬窗缓存已经先解决了重复计算 `H_ij` 和重复 `setDomain` 的主要开销。

旧 VRP 的 `BuildReach()` 不是只给每个 customer 存一个最早到达时间，而是枚举每个 customer 自己时间窗内的所有整数时间 `t`。forward 部分大致是：对当前 customer `cid`，从 `early_time[cid]` 到 `late_time[cid]` 逐个枚举 `t`，再扫所有候选下一个 customer `i`，判断 `t + service(cid) + distance(cid,i) <= late_time[i]`，满足则把 `i` 写入 `m_fl_reach[cid][t] / m_fh_reach[cid][t]` 这个 bitset。也就是说，它预处理的是“当前在 cid 且当前时间为 t 时还能去哪些点”，不是“cid 有哪些固定可达点”。backward reach 同理，只是把时间从反向剩余/偏移的角度编码。

如果把这个思想搬到当前 TWET pricing，效率是否会提高取决于实例规模和 `CmaxH`。当前 `buildReachableSet()` 每生成一个 label 都会扫一遍所有 job，并做一次 `earliest + setup + p <= HEnd` 的判断，复杂度大约是 `label 数 * n`。若建 `reach[from][t]`，每个 label 可以直接拿 bitset，理论上能减少扫描和比较，尤其是 label 很多、`n` 较大时会有收益。但预处理要付出 `n * CmaxH * n/wordSize` 左右的时间和 `n * CmaxH` 个 bitset 的内存；如果 `CmaxH` 很大或时间不是整数，收益可能被内存和离散化成本抵消。因此当前判断是：可以作为后续性能强化，但最好先统计 40/60/更大算例中 label 数、`CmaxH` 大小和 `buildReachableSet()` 耗时占比，再决定是否实现。

进一步分析后，这个 reach 优化必须和位集操作一起做才有意义。理想流程不是“拿到 reach 后仍然 `for job=1..n` 逐个判断”，而是 `candidate = reach[from][timeIndex]`，再做 `candidate &= ~visited`，再扣掉当前 node 的显式 forbidden arcs，最后只迭代 candidate 中为 1 的 job。如果仍然全任务扫描，只是把一个时间不等式换成一次 bitset contains，收益会很小。当前 `PackedBitSet` 只支持 `add/contains/intersects/subset/copy`，还没有 `andNot`、原地交集和 set-bit 迭代接口；因此真正要接 `BuildReach` 式优化，需要先补这些底层集合操作，否则 reach 表本身不一定能带来明显加速。

对于小数时间，使用 `floor(frontier.head.start)` 作为索引会得到一个可达集合的超集：例如真实时间是 10.9，按 10 预处理出的 reach 可能包含一些 10 可达但 10.9 已经不可达的 job。因此这时仍需要在候选集合内做一次精确的 `earliest + setup + p <= HEnd` 检查。用 `ceil` 则相反，会变成偏紧的子集，可能错误删掉真实可行扩展，所以不安全。也就是说，如果时间不是严格整数，reach 表最多先做粗过滤，最后的 `isDirectExtensionTimeFeasible()` 仍然不能完全删掉。

分支禁弧也不能完全省掉。静态预处理禁弧确实可以在构造 reach 表时先过滤进去，因为它是全局不变的；但显式 arc branching 产生的 forbidden arcs 是 node-local 的，同一轮 pricing 在不同 B&B 节点下不一样。若不想每个节点重建 reach 表，就必须在使用 reach 表后再扣掉当前 node 的 forbidden arcs。因此 reach 表只能替代一部分时间可达性判断，不能替代分支兼容性检查。

`BuildInRank()` 当前明确不做。参考旧 VRP 代码中虽然构造了 `m_in_rank`，但没有检索到真实调用点；它只是按入边距离给前驱排序，不参与可行性、reduced cost 或 dominance 正确性。后续如果需要排序加速，可以重新设计一个适合 TWET 的 rank，而不是机械搬这个未使用结构。

本次进一步确认旧 VRP 对 forbidden arc 的处理方式。旧代码里的 `BuildReach()` 是数据层/资源层的全局 reach 预处理，不会把每个分支节点上的 forbidden arc 直接揉进 reach 表。真正扩展 label 或做 tabu move 时，仍然会检查当前节点的 `feasible_arc[i][j] == -1`，命中则跳过该弧。也就是说，旧 VRP 的结构是“全局 reach 表先给出资源上可能到达的候选，再用 node-local feasible_arc 过滤分支禁弧”，不是每次分支后重建一套 reach 表。

当前 TWET-BPC 的语义与此保持一致：静态预处理禁弧可以进入全局过滤，因为它不随节点变化；显式 forbidden arc 分支必须保留为 `Node.isArcForbidden(from,to)` 这种运行时检查，因为它是 node-local 的。当前 `Label.visitedSet` 已经使用 `PackedBitSet` 存储，不是普通 `HashSet`。为了给后续 `reach[from][time]` 方案留接口，本次先补齐 `PackedBitSet` 的原地交集、并集、差集、空集/基数判断和 set-bit 迭代。后续如果实现 reach 表，理想流程应是：取出 `candidate = reach[from][timeIndex].copy()`，再 `candidate.andNotInPlace(visited)`，再扣掉当前节点 forbidden 后继集合，最后只遍历 candidate 中置 1 的 job。这样才真正减少全任务扫描；如果仍然 `for job=1..n` 逐个 `contains`，reach 表收益会很有限。

进一步核对旧 VRP 的 `GCNGBB` 后，需要补充一点：旧代码虽然构造了 `m_fl_reach[cid][t]` 这类 bitset，但在 forward 扩展里仍然是 `for (int i = 1; i < customerNumber; ++i)` 逐个枚举候选任务，然后依次判断 `node.feasible_arc[label.cid][i] == -1`、visited/memory bit、`label.m_low_reach` 或 `label.m_high_reach` 是否包含 `i`。也就是说旧 VRP 没有直接“遍历 reach bitset 中的置 1 位”来生成候选，而是用 bitset 把每个候选的 reach 判断降到 O(1)。它的收益来自少算时间窗/容量判断和 dominance 中的集合比较，而不是完全消除候选循环。因此当前如果只是复刻旧 VRP 的写法，可以保留逐 job 扫描，只把动态可达性写成 bitset contains；如果要进一步优化到只遍历置 1 的候选，则属于比旧 VRP 更激进的实现，需要配套 `PackedBitSet.forEachSetBit` 和 node-local forbidden 集合相减。

基于上述复核，当前暂时不实现旧 VRP 式 `reach[from][time]` 表。原因主要有三点。第一，旧 VRP 虽然做了 reach 预处理，但扩展时仍逐 job 扫描；在这种写法下，reach 只是把“时间窗/容量是否可达”的计算换成 bit 判断，并没有消除候选循环。第二，当前 TWET 的一跳硬窗过滤本身也是 O(1)：核心就是 `frontier.head.start + setupTime(prev,next) + processingTime(next) <= HEnd(prev,next)`，和旧 VRP 的 `t + service + distance <= late[i]` 在复杂度上差不多；如果仍然逐 job 扫描，预处理 reach 表只能减少很小的常数。第三，TWET pricing 真正更重的是后续分段函数的 `shift/add/normalize` 和 dominance 处理，而不是这一个时间窗不等式。因此当前保留 `isDirectExtensionTimeFeasible()` 这种直接 O(1) 过滤，不额外构造 `n * CmaxH` 级别的 reach bitset。后续只有在 profiling 明确显示 `buildReachableSet()` 的逐 job 扫描占主要耗时，或者决定做“只遍历置 1 候选 bit”的更激进实现时，才重新考虑接入 reach 表。

## 2026-05-20：BPC 主流程与 heuristic/exact pricing 复核

本次集中复核当前无 cut B&P 主流程、启发式定价和精确定价两个生成列入口。当前主流程是：`PC.solve()` 先解当前 restricted master；如果 LP 不可行则进入 repair；如果是非根节点且 LP 可行，则按当前 LP reduced cost 重新筛 restricted columns，并显式保留当前 LP 中取正值的列以保证筛选后的 RMP 仍保留当前基解；随后进入普通 pricing 循环。普通 pricing 现在已经改成旧 VRP 风格：按 engine 顺序尝试，启发式 engine 只要找到列就先加列、重解 LP、再从第一个 engine 重新开始；只有启发式找不到新列时才进入 exact pricing。因此从控制流上看，当前 no-cut 版本已经是“启发式优先、精确定价兜底”的 branch-and-price 框架。

启发式定价方面，`HeuristicPricingEngine` 从当前 active/restricted columns 中按 reduced cost 取 seed，再围绕 seed 做 remove/add/exchange 类型的 tabu 搜索。候选列的真实成本不是简单累加，而是使用前向/后向分段线性 profile、`merge2Segments/merge3Segments`、setup time 和 setup cost 做局部拼接评估。复核重点是它生成的列是否和标准列成本一致。本次用临时检查程序强制启发式开启、精确定价保留兜底，对最终 column pool 中来源为 `PRICING_HEURISTIC` 的列逐一调用 `TWETColumnEvaluator.evaluate(sequence)` 对拍，检查 187 条启发式生成列，最大差异为 0。因此当前启发式生成列的成本计算在测试覆盖范围内是正确的。

精确定价方面，当前默认走 `PaperDominanceExactPricingEngine`。它仍是单向 forward labeling，不带 cut、不带 DSSR/ng-route，但已经使用论文式 dominance graph 的思路：每个 graph node 保留真实 label 集合，同时维护 envelope 用于集合占优判断。label 扩展时先用 node-local/global forbidden arc、动态硬时间窗和 visited bitset 做过滤，再做 `shiftX/add/shiftY/normalizeForward` 更新 reduced-cost 函数；生成负 reduced-cost 列时再用 `TWETColumnEvaluator` 计算真实列成本写入 RMP。为了单独验证 exact pricing，本次临时关闭启发式定价，检查来源为 `PRICING_EXACT` 的 117 条列，全部和 evaluator 成本一致，最大差异为 0。`PaperDominanceGraphConsistencyTest` 也通过 200 组、16000 次随机插入对拍，说明当前 dominance graph 和保守全扫描 dominance 在这些随机场景下行为一致。

当前还需要明确一个边界：这仍然是 no-cut B&P，不是完整 BPC。`CutGenerator/CutPool/TWETCut` 仍是占位或空生成器，`SubsetRowCutGenerator` 不产生真实 cut；`PC` 的 cut loop 即使以后接入真 cut，也必须在加 cut 后回到 pricing，因为 cut dual 会改变 reduced cost。现在测试通过只能说明无 cut 框架、pricing 生成列和现有分支/外包建模在小规模样本上顺畅，不能说明 SRI cut 或其他 cut 已经正确。

本次验证命令主要包括：`HEU.SmallBPCBatchTest` 小规模 BPC/ArcFlow 对拍，8 个随机算例和 tariff 分支诊断例均通过；`TWETBPC.GC.PaperDominanceGraphConsistencyTest` 通过；临时启发式列成本检查程序通过；临时精确定价列成本检查程序通过。测试均使用 CPLEX native path `D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64`。

剩余能明显提高效率的方向有几类。第一，exact pricing 目前对每条最终生成列调用 `TWETColumnEvaluator` 重算真实成本，这是稳健但偏慢的做法；后续可以让 label 同时携带真实成本函数或用 reduced cost 加 dual 反推，但必须继续做列成本对拍。第二，`PaperDominanceGraph` 当前虽然流程正确，但 eligible node 查找、envelope merge 和 label 删除仍有较多线性扫描，后续可以加 ancestor/descendant 缓存或 bitset 索引。第三，启发式定价每次 tabu 接受 move 后会重建 profile，后续可以做局部增量更新，但这比当前实现更复杂，必须谨慎。第四，`buildReachableSet()` 仍是逐 job 扫描；旧 VRP 的 reach bitset 并不会天然消除扫描，只有配合 set-bit 迭代、visited/forbidden bitset 相减才可能有大收益，所以当前暂不实现，后续先 profiling 再决定。第五，当前还缺少系统化 pricing 统计，包括 label 数、扩展数、动态硬窗剪枝数、dominance 删除数、启发式/精确定价耗时和生成列数量，这个是后续优化前最该补的观测层。

## 2026-05-20：PiecewiseLinearFunction 对反向 labeling 的支持状态

本次检查 `PiecewiseLinearFunction` 中和反向 labeling 相关的接口。当前分段函数层已经有方向化设计：`Direction.FORWARD` 表示前向函数按前缀最小值维护 `[a,T]` 语义，`Direction.BACKWARD` 表示反向函数按后缀最小值维护 `[0,b]` 语义。核心接口包括 `normalize(Direction)`、`normalizeBackward()`、`minimizeSuffixInPlace()`、`mergeMinimum(g, Direction)` 和 `updateDominatedIntervals(g, Direction)`。其中 forward normalize 保留右侧到全局 T 的定义域，不提前删除右侧 big_M；backward normalize 与之对称，保留左侧 0/domainStart，允许删除右侧连续 big_M 尾段，然后调用 suffix-min 闭包。

因此从基础函数操作看，当前代码已经为反向函数准备了必要语义，尤其是后向闭包、后向 normalize、后向 merge/min-dominate 的方向参数都存在。测试侧 `PiecewiseLinearFunctionPropertyTest` 也覆盖了 `normalize(BACKWARD)`、`minimizeSuffixInPlace()`、backward merge/updateDominatedIntervals 的随机对拍。

但当前 BPC exact pricing 仍然是单向 forward labeling。`GC` 初始化 source label 和扩展 label 时都调用 `normalize(Direction.FORWARD)`，`DominanceGraph`、`PaperDominanceGraph` 和 `DominanceNode` 的 envelope merge 也都使用 `Direction.FORWARD`。也就是说，现有 exact pricing 没有真正构造 backward label、没有 backward dominance graph，也没有 forward/backward 双向拼接定价。当前 backward 相关函数主要被 HEU 局部搜索和 `HeuristicPricingEngine` 的 tabu pricing profile 使用：`buildBackwardProfile()` 从序列右端向左递推，使用负向 `shiftX(-s_ij-p_j)`、`add(jobPenalty)` 和 `minimizeSuffixInPlace()`，用于快速评价 remove/add/exchange 这类片段拼接。

所以当前准确结论是：PWLF 层已经具备反向 label 所需的基础操作，但 BPC exact pricing 尚未实现反向 labeling；后续如果要做 bidirectional pricing，需要新增 backward Label/queue/dominance store，定义反向扩展的 reduced-cost dual 处理和 source/sink 边处理，并在拼接时明确 forward `[a,T]` 与 backward `[0,b]` 的公共时间语义。现有 `mergeMinimum(..., BACKWARD)` 和 `normalize(BACKWARD)` 可以复用，但不能直接说明双向 exact pricing 已经完成。

## 2026-05-20：新增 no-cut 双向 labeling pricing

本次按旧 VRP 双向 GC 的框架新增了一个独立的双向 pricing 层。新的入口类是 `BidirectionalPricingEngine`，核心实现是 `GCBidirectional`。在 `TWETBPCContext` 中，当前 exact pricing 层改为二选一：如果 `enableBidirectionalPricing=true`，启发式定价之后只接双向定价；如果该开关为 `false`，才按 `usePaperDominancePricing` 选择原来的单向 forward exact / paper-dominance exact。也就是说，双向和单向不再串成顺序兜底，而是作为两种 exact pricing 实现互斥切换。

`GCBidirectional` 的流程对应旧 VRP 中的 `FWExtend / BWExtend / Join`。forward label 从虚拟起点 0 开始向后扩展，backward label 从虚拟终点 sink 开始向前扩展；两侧 label 都保存当前真实 job 序列、visited bitset 和最小 processing+setup duration。forward 侧扩展 `last -> job`，backward 侧扩展 `job -> first`，扩展时统一检查当前节点的 `Node.isArcForbidden()`，因此静态预处理禁弧和显式 arc 分支禁弧都会生效。两侧都采用半程 duration 截断，对应旧 VRP 中 `m_time < max_time/2` 的双向资源截断思想，用于控制标签规模。

拼接部分采用旧 VRP 的同中间点 join 思路：forward label 和 backward label 必须在同一个 job 上相遇，且除该公共 job 外不能有重复访问任务。拼接后得到完整序列 `forwardPrefix + backwardSuffixWithoutCommonJob`，再统一调用 `TWETColumnEvaluator.evaluate(sequence)` 重新计算真实列成本，并按当前 LP dual 扣除机器数 dual、job dual 和 arc dual 得到 reduced cost。这样做比直接把 forward/backward 分段函数拼成 reduced-cost 函数更稳健，因为当前 TWET 的动态 `H_ij` 可能依赖前驱，反向函数定义域和 endpoint gap 也更容易写错。第一版双向实现先保证生成列口径和现有列评价器一致，后续再考虑把分段函数拼接评价进一步下沉到 label 里。

`TWETBPCConfig` 只保留 `enableBidirectionalPricing` 作为双向/单向 exact pricing 的选择开关，不再设置额外的双向 label 数工程保护。双向实现仍会遵守现有 `maxExactPricingColumns`，即单轮最多返回多少条负 reduced-cost 列；但不会因为局部 label 数超过某个新增上限而提前停止。若双向层生成了列，PC 会像其他 pricing engine 一样加列重解，然后从启发式 engine 重新开始。

验证上，本次先针对新增类和接入点运行 `javac -encoding UTF-8` 编译通过；随后运行 `TWETBPC.GC.PaperDominanceGraphConsistencyTest`，200 组、16000 次随机插入通过；再运行 `HEU.SmallBPCBatchTest`，8 个随机小例均与 ArcFlow 目标值一致，额外 tariff 分支诊断例也通过。测试输出 CSV 是运行副产物，已恢复到仓库原状态，未纳入本次提交。

## 2026-05-20：双向 pricing 与旧 VRP 双向 GC 的流程一致性复核

本次重新对照旧 VRP 中 `GCNG/GCBDT` 的双向 labeling 代码，结论是：当前 `GCBidirectional` 在外层骨架上已经接近旧流程，但还不是严格同款实现。已经一致的部分包括：都有 forward 初始化和 backward 初始化；都有 `FWUL/BWUL` 待扩展队列以及按终端 customer/job 存储的 `FWTL/BWTL`；forward 从起点向后扩展，backward 从终点向前扩展；扩展时都检查已访问点、当前节点禁弧和基本时间资源；两侧都用半程资源截断控制搜索规模；join 时也采用“forward label 与 backward label 在同一个中间点相遇，再拼成完整路径/序列”的思路。

当前主要差异有四个。第一，旧 VRP 的 label 本身带 reduced cost、time、load、visit/reach/memory 等资源，并在扩展时增量更新；当前 `GCBidirectional` 的 label 只保存真实序列、visited bitset 和 processing+setup duration，完整序列生成后才调用 `TWETColumnEvaluator` 重新评价列成本并计算 reduced cost。这保证了列成本口径稳定，但效率和旧代码不同。第二，旧 VRP 在 forward/backward 两侧都有 label dominance，入表前会删掉被占优 label；当前双向版暂时没有 dominance，只靠 visited、禁弧和半程截断控制规模。第三，旧 VRP 的 reduced-cost 计算在 label 扩展和 join 时已经参与筛选，当前只在完整序列阶段筛选，因此会多生成和多评价候选。第四，旧 `GCNG` 还包含 ng-memory/DSSR 的 duplicate cycle 更新；当前按用户要求暂不考虑 ng-route、DSSR 和 SRI cut，所以这部分差异是刻意保留的。

因此，当前双向版可以看作“旧 VRP 双向框架的第一版 TWET 适配”，用于和单向 pricing 做对拍测试是可以的；但如果要求“流程完全一致”，后续至少还应补两件事：一是给 forward/backward label 增加安全的 dominance 结构，二是把 TWET 的分段函数 reduced-cost 递推下沉到 label 层，而不是只在完整序列阶段调用 evaluator。下一步做两个 pricing 对拍时，应重点比较同一轮 RMP dual 下两者找到的最小 reduced cost、返回列数量和耗时；如果出现双向最小 reduced cost 明显弱于单向，就优先检查半程截断和同点 join 是否漏掉了某些序列。
## 2026-05-20：单向与双向 exact pricing 逐轮对拍
本次新增 `HEU.PricingAlgorithmComparisonTest`，用于在同一个 RMP dual 下同时调用单向 `PaperDominanceExactPricingEngine` 和双向 `BidirectionalPricingEngine`。这个诊断类不接入正式 `PC` 流程，也不改变 `enableBidirectionalPricing` 的生产语义；它只是在每轮 LP 解出 dual 后，分别调用两个 exact pricing，计算各自返回列中的最小 reduced cost、对应序列、返回列数和耗时，然后把两边新列的并集加入当前 RMP，重解后进入下一轮。这样可以避免“两套算法各自推进后 dual 不同”导致的比较不公平。

本次测试使用 3 个随机小算例，任务数分别为 5、6、7，并把 `maxExactPricingColumns` 临时调到 100000，尽量避免单轮返回列上限先截断最优负 reduced-cost 列。结果共比较 7 个 pricing 轮次，单向和双向的最小 reduced cost 全部一致。具体结果写入 `test-results/bpc/2026-05-20-pricing-comparison.csv`。控制台汇总为：`matchedRounds=7/7`，单向平均耗时约 `2.43 ms/round`，双向平均耗时约 `62.29 ms/round`。

这个结果说明，在当前小规模测试范围内，双向 pricing 虽然返回的候选列更多、耗时明显更高，但没有漏掉单向 forward exact 能找到的最优负 reduced-cost 列。耗时更高的主要原因仍是当前双向版本没有 forward/backward dominance，也没有把 reduced-cost 分段函数递推下沉到 label 层，而是在完整序列生成后统一调用 `TWETColumnEvaluator` 重算成本。因此它现在更适合作为正确性对拍版本，而不是效率更强的替代版本。后续如果要让双向真正带来效率收益，应优先补 forward/backward label dominance 和更强的 join 前 reduced-cost 剪枝。

## 2026-05-20：双向 pricing 补充旧 VRP 风格 label 占优

根据后续复核，前一版 `GCBidirectional` 的核心缺口不是外层 `FWExtend/BWExtend/Join` 框架，而是没有像旧 VRP 双向 GC 那样在 `FWTL/BWTL` 入表阶段做 label dominance。旧 VRP 的流程中，label 出队时会跳过已经被标记 dominated 的对象，扩展生成的新 label 入 TL 前会和同一终端点表内 label 比较，若被已有 label 支配则直接丢弃；若新 label 支配旧 label，则把旧 label 标记为 dominated 并从表中移除。这样 UL 中即使残留旧对象，后续出队时也会被跳过，join 阶段也不会再用已经失效的 TL label。

本次在 `GCBidirectional` 中补上了这层生命周期：`forwardExtend()` 和 `backwardExtend()` 出队后先检查 `isDominated`；`addForwardLabel()` 和 `addBackwardLabel()` 不再直接插入 TL，而是调用 `isDominatedAndInsert()`；`joinForward()` 和 `joinBackward()` 只枚举未被 dominated 的反向/正向 label。这个流程和旧 VRP 的表结构语义一致：TL 是每个终端 job 上已经保留的有效 label 集合，UL 是待扩展队列，被后续占优删除的对象不从优先队列中物理删除，而是在出队时跳过。

当前占优条件仍是第一层保守标量版，不是最终的函数级 TWET dominance。具体规则为：两个 label 必须在同一个终端 job 的 TL 表中比较；若 label A 的 visited 集合包含于 label B，且 A 的 source duration、internal duration、当前 partial reduced-cost bound 都不劣于 B，则认为 A 支配 B。这里的 partial reduced-cost bound 通过当前真实部分序列调用 `TWETColumnEvaluator` 得到成本，再扣除机器数 dual、job dual 和已有 arc dual。这个做法能明显减少双向 label 数和 join 候选，但它还没有把 forward/backward 分段 reduced-cost frontier 下沉到 label 层，因此不能等同于 `GC` 中基于 `PiecewiseLinearFunction` envelope 的完整 set dominance。后续如果继续优化双向 pricing，下一步应把 forward label 的 reduced-cost frontier、backward label 的后向 frontier 以及方向化 dominance store 加进来，而不是长期依赖这个标量近似。

验证上，本次重新运行 `HEU.PricingAlgorithmComparisonTest`。3 个随机小算例、7 个 pricing 轮次中，单向 paper-dominance exact 和双向 exact 的最小 reduced cost 仍全部一致；加入占优后，双向平均耗时从上一版约 `62.29 ms/round` 降到约 `7.14 ms/round`，但仍慢于单向的约 `1.71 ms/round`。随后运行 `HEU.SmallBPCBatchTest`，8 个随机小算例与 ArcFlow 目标值全部一致，tariff 分支诊断例也通过；补跑 `PaperDominanceGraphConsistencyTest`，200 组、16000 次插入对拍通过。当前结论为：双向 pricing 的旧 VRP 风格 TL/UL 占优生命周期已经补齐，正确性在现有对拍范围内未发现问题；但函数级双向 dominance 和 label 层 reduced-cost 递推仍是后续效率强化点。
