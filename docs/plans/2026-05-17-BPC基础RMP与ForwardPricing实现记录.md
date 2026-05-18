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

本轮按用户要求把启发式定价进一步向旧 VRP 的 `GCTabu` 框架靠齐。旧代码的核心流程不是替代 exact pricing，而是在 exact pricing 前从当前 RMP 里挑一批低 reduced cost route 作为 seed，然后围绕 seed 做 remove/add/exchange tabu 搜索，形成一个本地负 reduced-cost route pool，排序后只加入少量优质列。当前 TWET 版本沿用这个框架：先收集当前 restricted columns，按 reduced cost 排序取 `heuristicPricingSeedColumns=30` 条种子；每条种子运行 `heuristicPricingTabuIterations=50` 轮 tabu，tenure 取旧 VRP 默认值 30；邻域包括删除一个任务、插入一个未访问任务、用未访问任务替换当前任务。所有候选进入本地池后，最后按 reduced cost 排序，最多返回 `maxHeuristicPricingColumns=30` 条。

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

实现上，`HeuristicPricingEngine` 已经从占位实现改成真实的启发式定价器。它先按 reduced cost 对当前 restricted columns 排序，取 `heuristicPricingSeedColumns=30` 条作为种子；然后围绕每条种子尝试 remove、relocate、add 和 exchange 四类邻域，最多扫描 `maxHeuristicPricingCandidateScans=5000` 个候选；所有负 reduced-cost 候选先进入本地池，最后排序取 `maxHeuristicPricingColumns=30` 条返回。这里刻意没有“找到 30 条就停”，因为旧 GCTabu 是先形成候选池再挑最好的 add-in 列，提前停会让早期较弱列挤掉后面更好的列。该启发式只跳过当前 RMP 已经 active 的重复列；如果某条序列在全局 pool 中已有但当前节点尚未 active，仍允许返回给 `PC` 激活。

分支子节点补列也做了一层加强。之前只对 required arc 做 fallback：如果当前 seed 中没有覆盖某条 required arc 的列，就构造 required-chain，并尝试把这条链插入已有列的不同位置。现在又补了“不能外包任务缺覆盖”的兜底：如果某个 job 的外包成本是 `big_M`，即 RMP 中对应 `y_j` 上界为 0，而当前 seed 中又没有任何兼容列覆盖它，就先尝试 singleton，再尝试把该 job 插入已有 pool 列的不同位置，选择一个兼容且成本有限的 fallback column。这个仍然不是旧 VRP `FindFeasible` 的完整 slack + 定向 pricing 版本，但能减少一类 restricted master 因暂时缺列而提前 infeasible 的情况。

`PaperDominanceGraph` 这次没有改主逻辑，但补了一个轻量一致性测试 `PaperDominanceGraphConsistencyTest`。测试做法是构造随机 reachable set 和 `[0,T]` forward frontier，把同一批 label 同步插入朴素全扫描 `DominanceGraph` 和论文式 `PaperDominanceGraph`，检查“新 label 是否被占优丢弃”的返回结果是否一致。当前测试规模为 200 组、每组 80 个 label，共 16000 次插入，测试通过。这个测试不能替代真实算例级的 bound/列池对拍，也不覆盖 partial dominance，但至少能防止 graph propagation 方向、terminal superset 查找或 predecessor/successor 维护出现明显语义错误。

本轮验证包括一次 focused compile，范围仍为 `src/Common`、`src/Basic`、`src/HEU`、`src/Output` 和 `src/TWETBPC`，编译通过，仅有旧 API deprecation 提示；随后运行 `TWETBPC.GC.PaperDominanceGraphConsistencyTest`，结果通过。剩余风险主要有两点：第一，启发式定价目前是全序列重算成本，不是旧 GCTabu 那种完全增量式 tabu，后续如果要追求速度，需要把 HEU 里已有的快速分段函数拼接和 reduced-cost 常数项结合起来做专门的增量评估；第二，分支子节点仍没有完整 slack RMP + FindFeasible 定向 pricing，复杂 required arc 组合下仍可能需要进一步补强。
