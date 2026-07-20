# two-stage strong branching 设计分析

## 问题背景

当前 BPC 在节点闭合、RMIH、subtree fixing 和 route enumeration 之后，按固定分支链选择第一个可分支对象。这个流程成本低，但分支对象只按局部分数值选取，不能判断两个 child 的下界提升效果。`two_stage_strong_branching.pdf` 中的思路是先用便宜的 restricted master trial 粗筛候选，再对少量候选做有限启发式列生成 trial，用近似 child bound 选择更好的分支。

## 当前可接入位置

最合适的接入点是 `Tree.solve()` 中当前 `branch.start` 之后、正式 `Brancher.branch(lp)` 入队之前。此时当前节点的 LP/CG 已经闭合，`TWETMasterSolution`、当前 restricted columns、当前 cut、subtree fixing 结果和 incumbent 都已经可用。强分支只改变“选择哪个分支对象”，不应该改变当前节点 LP、全局 pool 语义或正式 child 的后续求解流程。

## 候选生成口径

候选仍按当前分支规则顺序分层处理：在一个分支层级存在分数候选时，只在该层级内部做 strong branching，不混用后续层级。当前主要有 arc、tariff segment 和 columnized outsourcing membership；machine count 和 adjacency 若保留开关，也可以复用同一候选接口，但 2 机器主线里 machine count 基本不会触发。

每个层级先取最接近 0.5 的候选，默认上限可设为 20。arc 层级应沿用当前 `ArcBrancher` 的语义：先内部 job-job arc，再 source-job endpoint arc，不对 job-sink 做实际分支。候选生成必须使用和正式分支完全一致的 child 构造逻辑，包括 forbid/require arc 的排他禁弧、tariff segment require/forbid、outsourcing require/forbid 以及对应 repair 标记。

## 第一阶段：RMP-only trial

第一阶段对每个候选构造左右 child，并继承当前父节点 restricted columns，与正式 child 入队前 `prepareChildSeedColumns()` 的语义保持一致。若当前节点已经计算出 subtree arc fixing，trial child 也应临时应用同一结果，否则 trial bound 与正式 child 的初始模型不一致。

trial 只求 restricted LP，不做完整 column generation，也不做 cut separation。若 child 初始 LP 不可行，可以调用轻量 repair 生成少量列使分支行可行；repair 成功后直接以修复后的 restricted LP bound 作为第一阶段 bound，不再执行后续定价或重新选择初始列。第一阶段得到 `LB_L`、`LB_R` 后，用 `max(LB_L-LB, eps) * max(LB_R-LB, eps)` 作为 product score，并保留 top 4 进入第二阶段。若某一侧 infeasible，可把该侧视为极强下界；若两侧都 infeasible，则该候选本身说明当前节点可能存在状态矛盾，需要单独记录。

## 第二阶段：heuristic-CG trial

第二阶段只对第一阶段 top 4 候选执行。为避免重复构造/repair，可以保存第一阶段左右 child 的修复后列集和 bound，然后第二阶段用这些列集重建 trial LP。为了避免大量临时 CPLEX 对象泄漏，不建议长时间持有第一阶段 LP 对象本身；实现时需要给 `LP` 增加显式释放接口，trial LP 用完立即释放。

第二阶段只运行启发式 pricing，直到启发式找不到列或达到很小的 trial 预算。每次启发式加列后重解 trial LP；不调用 exact pricing，不做 cut，不做 route enumeration，不做 subtree 更新。最终用第二阶段左右 child bound 重新计算 product score，选择分支对象。这个 bound 只是分支选择依据，不能当作正式节点证明。

## 需要新增的接口

当前 `Brancher` 只有“直接返回一个分支”的接口，不够支持 strong branching。需要新增候选层接口，例如 `BranchCandidateProvider` / `BranchCandidate`，负责：提取分数候选、构造左右 child、描述候选类型和值。正式分支可以仍然使用该接口的最佳候选构造结果，避免 `ArcBrancher`、`TariffSegmentBrancher`、`OutsourcingMembershipBrancher` 各自维护一套重复逻辑。

`PC` 也需要一个 trial 求解入口，至少支持两种模式：`RMP_ONLY_WITH_REPAIR` 和 `HEURISTIC_PRICING_ONLY`。这个入口不能触发 dual-bound pruning、cut、route enumeration、正式 trace 统计或全局 center 更新；trial 生成的新列是否进入全局 pool 需要谨慎处理。较稳妥的第一版可以允许写入全局 pool，因为列本身合法且后续可能复用，但 trial LP 的 restricted column 集不要污染当前正式节点。

## trial child 复用方式

两阶段 strong branching 可以复用第一阶段 child 的修复结果，但不应该长期保留第一阶段的 `LP` / CPLEX 模型对象。原因是每个候选有左右两个 child，20 个候选就是 40 个临时模型；若直接保留 CPLEX 对象，内存和 native 资源释放都不可控，尤其当前 pricing / enumeration 已经会产生较多临时函数对象。更稳的做法是：第一阶段 trial 完成后只保存轻量结果，包括 child node、repair 后 restricted internal column ids、repair 后 outsourcing column ids、phase1 bound 和 infeasible 状态。第二阶段若该候选入选，再用这些列集重新构造 trial LP。

这个做法仍然算“复用”，因为最耗语义风险的 child 构造和 repair 结果不重复做；只是复用的是 trial 状态快照，而不是复用 CPLEX 模型。这样也方便在第二阶段启发式 pricing 后继续保存更新后的列集：如果最终该候选被选中，正式 child 入队时可以继承二阶段已经生成的启发式列，避免同一分支刚入队后又重新生成一批相同列。

## 是否需要按分支类型定制

强分支框架不需要为 arc、tariff、outsourcing membership 各写一套 trial 流程。真正与分支类型相关的只有两个动作：候选提取，以及由候选构造左右 child。child 一旦构造出来，后续 restricted LP、repair、启发式 pricing trial、bound 评分都可以走统一流程。因此更合理的设计是保留统一的 `StrongBranchingSelector`，它按当前分支顺序询问每个候选 provider；第一个存在分数候选的 provider 交出候选集合，selector 对这些候选做两阶段 trial 并返回最终 `BranchResult`。

不建议在正式 `Node` 上只加一个“strong branching 标记”然后让完整求解流程靠识别标记跳过若干步骤。原因是 trial 求解和正式求解的边界不同：trial 不应触发 cut、route enumeration、dual-bound pruning、subtree 更新、正式 incumbent 更新和全局 dual stabilization center 更新；如果把这些跳过逻辑散落到正式求解流程里，后续很容易出现某个组件忘记判断 trial 标记而污染正式状态。更干净的方式是在 `PC` 层提供明确的 trial mode，trial mode 只暴露允许的动作。

## 风险与预期

强分支可能减少搜索树节点，但会显著增加每个分支节点的 master LP 和启发式 pricing 开销。对当前 2 机器纯机器调度，主要价值在 arc 分支选择；对外包模式，tariff 和 outsourcing membership 也可能受益。第一版建议默认关闭，只在 30/40 任务或长尾节点上测试：记录 phase1/phase2 候选数、左右 bound、product score、启发式 trial 加列数、耗时、最终选择是否改变以及后续节点数变化。若 trial 成本超过后续节省，应该只在 gap 小、候选分歧明显或历史上分支质量差的节点启用。

## 最小实现口径

第一版不需要单独新增一整套 provider 层，避免为了 strong branching 改出过多类。更小的做法是继续沿用当前 `Brancher` 顺序和 `BranchResult` 语义，只给需要 strong branching 的现有 brancher 增加一个候选枚举入口。普通求解仍调用 `branch(lp)`；strong branching 开启时，`Tree` 在同一个 brancher 层级上调用 `collectCandidates(lp, limit)`，拿到该层级最接近 0.5 的若干候选，然后统一做两阶段 trial。若该 brancher 没有候选，继续走下一个 brancher。

也就是说，分支类型仍然由现有 `ArcBrancher`、`TariffSegmentBrancher`、`OutsourcingMembershipBrancher` 管理，不额外拆 provider。每个 brancher 只需要把“原来找一个 best 候选”的逻辑稍微泛化成“找 top K 候选”，并提供“按某个候选构造左右 child”的方法。这样不会改变正式分支语义，也不会把 strong branching 的 trial 流程复制到每个分支类型里。

trial 求解可以直接在 `PC` 增加 strong-branching 专用方法，逻辑类似当前 `solve()` 的瘦身版：第一阶段只做 child restricted LP 和必要 repair；第二阶段从第一阶段保存的 child 列集重建 LP，只跑启发式 pricing，启发式无列后停止。这个方法明确不执行 exact pricing、cut、route enumeration、dual-bound pruning、subtree 更新、incumbent 更新和 dual stabilization center 更新。

`Tree` 中的集成也保持最小：在当前正式分支循环里，如果 strong branching 关闭，完全走原逻辑；如果开启，则对当前 brancher 先尝试 top-K 候选强分支。选出最终候选后仍返回一个正常 `BranchResult`，后续继续走原有 `applySubtreeArcElimination()` 和 `enqueueChild()`。二阶段若生成了额外启发式列，可以把左右 child 的 seed 列表替换为 trial 快照，供正式 child 后续出队时复用。

为了效率，arc 候选枚举不能每条 arc 都调用 `solution.getArcValue()` 重新扫列。应在 `ArcBrancher` 的候选枚举里一次性从当前 active columns 累计 arc value 矩阵，再筛分数 arc。tariff 和 outsourcing membership 本身已有数组值，直接排序取 top K 即可。trial LP 用完仍必须显式释放 CPLEX 模型，强分支不长期持有 LP 对象。
## 2026-06-26 当前实现口径

已按最小改动口径接入默认关闭的 `enableTwoStageStrongBranching`。`Brancher` 只新增一个默认空实现的候选枚举接口，普通求解仍调用原来的 `branch(lp)`；`ArcBrancher`、`TariffSegmentBrancher`、`OutsourcingMembershipBrancher` 把原先“选一个最接近 0.5 的分支对象”泛化为“取 top K 候选”，并复用同一套 child 构造逻辑，避免 strong branching 和普通 branching 语义分叉。Arc 候选会先一次性从当前 active columns 累积 arc-value 矩阵，再筛选内部 job-job arc；只有内部 arc 没有分数候选时才考虑 source-job endpoint，仍不对 job-sink 做分支。

`Tree` 在正式分支循环里加了一个可选入口：开关关闭时完全不进入；开关打开且当前 brancher 返回候选时，先对每个候选的左右 child 做 RMP-only trial，应用当前节点已经算出的 subtree fixing 结果，并把 repair/筛列后的 restricted internal/outsourcing column ids 保存为 child seed 快照。随后按 product score 选 top `strongBranchingPhase2CandidateLimit` 个候选进入第二阶段；第二阶段用第一阶段保存的列集重建 trial LP，只运行启发式 pricing，启发式无列后停止，不做 exact pricing、cut、route enumeration、dual-bound pruning 或 subtree 更新。最终选中的左右 child 入队时直接使用 trial 后的 seed 快照，不再重新调用父节点 seed 准备逻辑。

`PC` 新增 strong-branching 专用 trial 入口，并在 trial 前后保存/恢复 `lastReusableSubtreeArcEliminationBounds`、`lastObservedDualBound` 和 `lastNodePrunedByDualBound`。这样 trial 可以往全局 pool 写入合法新列供后续复用，但不会污染当前正式节点的 dual-bound / subtree reusable bound 状态；trial 用完后显式释放临时 LP 的 CPLEX 模型，并重置 pricing engine 状态，避免启发式 trial 的内部缓存影响正式 pricing。当前只做编译验证，尚未跑开关打开的效率对照；后续需要在 30/40 任务上观察 phase1/phase2 试探耗时、选中分支变化和节点数变化。

2026-06-26 复查默认关闭路径时补充了 tie-break 约束。由于普通 `branch(lp)` 现在复用候选枚举取 top1，候选距离 0.5 完全相同时必须保留旧扫描顺序，否则即使 strong branching 关闭也可能改变分支树。当前 `StrongBranchingCandidate` 带有 `order` 字段，arc 候选使用原 from/to 扫描顺序，tariff 和 outsourcing membership 使用 segment/job 升序；普通分支和 strong selection 的二级排序都使用该顺序。

## 2026-06-26 分支构造与 trial 筛列口径补充

当前 `createBranchResult()` 的作用是把一个具体分支候选转换成左右两个正式 `Node`。以 arc `(i,j)` 为例，左支复制父节点后写入 `forbidArc(i,j)` 和 forbidden repair 标记；右支复制父节点后写入 `requireArc(i,j)`，并禁止同一真实 `i` 的其它后继、同一真实 `j` 的其它前驱，再写 required repair 标记。tariff segment 和 outsourcing membership 也是同样口径：候选只负责描述“分什么”，`createBranchResult()` 负责按正式分支语义生成左右 child。普通 `branch(lp)` 和 strong branching trial 都复用这个构造函数，避免两套 child 语义不一致。

如果某个分支器没有实现 strong candidate，`tryTwoStageStrongBranching()` 会返回空，`Tree` 仍继续调用该分支器原本的 `branch(lp)`。因此未实现 strong branching 的分支规则不会失效，只是不参与两阶段试探。当前已实现候选枚举的是 `ArcBrancher`、`TariffSegmentBrancher`、`OutsourcingMembershipBrancher`；未实现的是 `MachineCountBrancher` 和 `UndirectedAdjacencyBrancher`，它们在 strong branching 开启时仍走普通分支逻辑。

Phase 1 trial 的列集处理对齐正式 child 初始化口径。trial child 先继承父节点当前 restricted columns 并构造 LP；如果初始 LP 可行，则调用 `resetRestrictedColumnsByCurrentReducedCost()` 做 reduced-cost 筛列并重解一次；如果初始 LP 不可行，则进入 `repairInfeasibleMaster()`，repair 在 slack 归零后同样会调用 `resetRestrictedColumnsByCurrentReducedCost()`，再解 `repair_final`。因此一阶段返回的 bound 是筛列后的 LP bound，repair 与非 repair 路径都会筛列。Phase 2 不再重复 repair 和筛列，它只基于 Phase 1 的列集快照继续做启发式 pricing trial。

若某个 child 在 Phase 1 后不可行，strong branching 评分把该侧视为极强下界，用 `pseudoCostInf` 参与 product score。这通常是合理的，因为一个分支侧被证明不可行时，说明该候选显著缩小搜索树；但它也意味着评分会非常偏向“一侧不可行、另一侧尚可”的候选。当前这符合强分支的常见取向，后续若发现过度偏向不可行侧导致另一侧很差，可以再把 infeasible gain 从无穷大改成一个有限大惩罚/奖励。

2026-06-26 进一步确认：`MachineCountBrancher` 理论上通常只有一个候选，不需要为它单独做 strong branching；`UndirectedAdjacencyBrancher` 虽然可以枚举候选，但此前实验中无向邻接分支容易引入 arc dual 异常，进而削弱 pricing dominance 和 completion bound，因此短期不再为该分支接入 strong branching。不可行 child 继续按极强候选处理，即使用很大的 gain 进入 product score。

2026-06-26 关闭一致性与开启正确性复查：`Tree.tryTwoStageStrongBranching()` 在 `enableTwoStageStrongBranching=false` 或候选上限非正时立即返回 `null`，随后仍调用普通 `brancher.branch(lp)`；普通分支内部虽然复用了候选提取，但 tie-break 已按旧扫描顺序的 `order` 字段处理，因此关闭强分支时分支对象选择口径保持一致。开启强分支时，候选只负责调用同一套 `createBranchResult()` 构造 child，Phase 1 用临时 LP 做 RMP/repair/筛列试探并保存轻量列集快照，Phase 2 从该快照重建临时 LP 做启发式 pricing trial；trial LP 用完调用 `closeModel()`，`PC` 会恢复 subtree bound、dual-bound 状态并 reset pricing engines。trial 过程中生成的新列可能进入全局 pool，但不会加入父节点 LP；只有最终选中的 child 会把 trial 后的 restricted column ids 作为 seed 入队。当前未发现正确性问题。需要注意的只是效率口径：`strongBranchingPhase2MaxHeuristicPasses=0` 表示 Phase 2 对每个 trial 一直跑到启发式无列，开启强分支做大规模对照时建议显式设为 1 或 2，避免 strong branching 本身过重。

2026-06-27 补充外包场景下的适用性判断。强分支框架本身对外包模式仍成立，因为 trial child 复用正式 `createBranchResult()` 构造，`LP.construct()` 会同时按 node 状态筛内部机器列和外包列，trial 结果也会保存 internal / outsourcing 两类 restricted column id。显式外包变量模式下，强分支主要作用于 tariff segment 和机器侧 arc；外包 `y_j` 不是单独 membership 分支。columnized outsourcing 模式下，`OutsourcingMembershipBrancher` 已接入候选枚举，左右支分别写入 forbid/require outsourcing job，required 侧还会 seed 一个包含所有 required outsourced jobs 的外包列。需要注意的是，当前分支顺序仍是分层的：columnized outsourcing 下先 machine / adjacency / arc，只有前面层级没有分数候选时才会进入 outsourcing membership 强分支。因此外包下强分支正确性没有问题，但选择质量不保证和纯机器情形一样，尤其 Phase 2 只运行启发式机器 pricing，不会专门为 outsourcing membership trial 再跑外包 exact pricing；最终入队后的正式 node 仍会完整 pricing，所以这只影响强分支评分精度，不影响最优性。

2026-06-27 随后把 columnized outsourcing 下的 Phase 2 试探稍微补强：Phase 2 仍不运行内部 exact pricing，但允许 `OutsourcingPricingEngine` 进入 strong branching trial。这样 outsourcing membership 分支在第二阶段可以看到外包列族的精确定价反应；内部机器列仍只用启发式 pricing，避免 strong branching 本身退化成完整子节点求解。当前 child seed 筛列口径是 internal / outsourcing 两类列分别使用 `branchSeedColumnLimit`，不是合计共享一个上限。

2026-06-27 再次复查考虑外包后的正确性。当前结论是 strong branching 与外包列建模兼容：Phase 1/Phase 2 trial 都通过正式 `LP.construct()` 建模，columnized outsourcing 下会同时携带 internal seed 和 outsourcing seed，并按 node 的 required/forbidden outsourcing membership 检查外包列兼容性；trial seed 写回也同时保存两类列 id。显式外包变量模式没有 outsourcing membership 分支，只会在 tariff segment 和机器侧分支上做 strong branching。Phase 2 中允许外包 exact pricing 不会污染正式节点状态，因为 `PC` 在 strong trial 前后会保存/恢复 node 级缓存并 reset pricing engines。当前唯一需要注意的是评分精度：如果以后把 `strongBranchingPhase2MaxHeuristicPasses` 设成很小，机器启发式一旦持续加列，外包 exact 可能不会在该 trial 内执行；默认跑到启发式无列时不存在这个问题。

2026-06-27 补充 `strongBranchingPhase2MaxHeuristicPasses` 语义：它控制 Phase 2 trial 的总 pass 数，而不是单独控制某个 pricing engine 的次数。每一轮 pass 按 engine 顺序尝试允许的机器启发式 pricing 和 columnized outsourcing 下的外包 exact pricing；某个 engine 一旦加列，就解一次 trial LP 并进入下一轮。默认值 `0` 表示不设 pass 上限，一直运行到允许的 Phase 2 pricing 都无列，因此外包 exact pricing 最终会在机器启发式无列后执行。当前默认保持 0，预计对正确性无影响，只是 strong branching 成本与评分精度之间的取舍。
## 2026-06-27 后续暂缓方案记录

60-2 测试中观察到，strong branching 的 Phase 1 如果 child 初始 RMP 不可行，会进入 repair，而 repair 内部可能调用 exact pricing。对大规模或难节点，这会让 Phase 1 成本明显变高。一个可尝试方向是：Phase 1 repair 只允许启发式 pricing 补列，用更便宜的近似可行性和近似 bound 做候选筛选；但这会削弱 infeasible 判断的可信度，也会和当前“Phase 1 不可行就不进入 Phase 2 / 不入队”的流程冲突。当前先不实现，只记录为后续实验方案；若要做，需要单独定义“启发式 repair 未修好”是弱不可行、低质量候选，还是继续交给正式 child 再修复。

另一个可尝试方向是改进入队 child 的 `pseudoCost`。理论上，若 Phase 1 repair 中执行过 exact pricing，并且能够得到当前 dual 下的有效 dual bound，可以用该 dual bound 与父节点 bound 取更强值，作为入队排序的下界估计。但当前 strong branching 最终入队通常使用 Phase 2 后的 child seed，而 Phase 2 只跑启发式 pricing，不产生 exact pricing 证书；能拿到 dual bound 的场景主要出现在 repair 期间，覆盖面有限，实际帮助可能不大。当前仍保持保守做法：trial bound 只用于选择分支，不写入正式 child `pseudoCost`；后续如果发现队列排序明显受父节点 bound 过松影响，再考虑加入这一优化。

## 2026-06-30 time-indexed no-cut 强分支异常定位

在 `data/40-2/wet040_001_2m.dat` 上复查 pure time-indexed graph pricing + strong branching 的 no-cut 结果。最新 strong run 在开启 post-node time-indexed arc fixing 时收敛到 `22582`，修正开关并真正关闭该 fixing 后仍只收敛到 `22581`；而同一 no-cut time-indexed pricing 在关闭 strong branching 后可以在 `46.737s` 内闭合到历史已知最优 `22580`。因此问题不在 time-indexed pricing 本身表达不了最优列，也不只是 post-node time-indexed arc fixing。

用 `incumbentColumnAudit` 从 rank-1 run 中恢复出的 `22580` 解为两条内部列：`[17,19,25,28,21,20,6,23,27,2,15,9,29,18,24,14,22,16,8,13]`，成本 `10286`；`[26,10,11,37,33,5,36,38,34,35,31,4,3,32,40,12,30,7,1,39]`，成本 `12294`。按项目 `TWETColumnEvaluator.evaluateTiming()` 计算，二者最后完工时间分别为 `1382` 和 `1381`，均远小于该 run 的 time-indexed horizon `2132`；在真正关闭 time-indexed arc fixing 的 strong 对照中，node 2 还显示 `timeWindowJobs=0`、`timePricingOnlyArc=0`。这说明该最优解不是被硬时间窗、dual window 或 time-indexed arc fixing 直接砍掉。

真正的冲突发生在 strong branching 的 node 2。node 1 选择 `forbid(31,5)`，上述 `22580` 解满足该左支；node 2 随后选择 arc `(38,34)`，日志给出 `leftBound=23658.000000000015,rightBound=INF`。但 `22580` 的第二条机器序列包含连续片段 `36,38,34,35`，并且没有其它进入 `34` 或离开 `38` 的 arc，因此它应当属于 node 2 的 `require(38,34)` 右支。当前 `StrongBranchingTrialResult.from()` 会把 trial/repair 返回的 `INFEASIBLE` 记为 `INF`，`isReusableForQueue=false`；`Tree.enqueueStrongBranchingChild()` 对这种 child 直接不入队。于是包含已知最优解的右支被 strong trial 的假不可行结果从正式搜索树中删除，导致后续只能闭合到 `22581/22582`。

当前结论是：strong branching 中“trial 不可行即可视为正式子树不可行并不入队”的口径不安全，至少在 time-indexed no-cut pricing 下已被 `22580` 反例击穿。后续修复方向应是区分 trial infeasible 与正式 infeasible：trial 不可行可以用于评分，但不能直接作为丢弃 child 的证明；若该分支最终被选中，相关 child 应进入正式队列并由完整 node solve/repair/pricing 流程重新判定，或者至少在 exact repair 严格证明不可行后才允许剪掉。

2026-06-30 进一步澄清：如果 repair 确实是完整 Phase-I column generation，并且 exact pricing 覆盖当前分支子树的完整列族，那么“slack 仍为正且无负 reduced-cost 列”当然可以证明该 child 不可行。当前问题不在这个数学结论，而在 time-indexed strong trial 的实现没有达到这个前提。`TimeIndexedGraphPricingEngine` 没有覆盖 `findFeasible()`，repair 调用的是 `PricingEngine` 默认的普通 `price()`；该 pricing 每轮只返回 `timeIndexedGraphMaxExactPricingColumns=300` 条候选，`repeatFindFeasibleUntilExhausted()` 也不是 exact engine 自身反复耗尽。再加上 repair slack 只挂当前新增分支行，strong trial 又不是完整正式 node solve，因此 trial 返回 `rmp_trial_infeasible` 不能当成正式不可行证书。日志反例也直接说明这一点：no-strong 的同一 time-indexed pricing 在 `46.737s` 内闭合到 `22580`，而 strong node 2 把包含已知最优序列片段 `36,38,34,35` 的 `require(38,34)` 右支评为 `INF` 并丢弃。结论应表述为“当前实现的 trial repair 不可行不可信”，而不是“理论上 exact repair 还会漏可行子树”。

2026-06-30 再次校正原因表述。用户指出“如果 repair 里真的包含完整 exact pricing，那么可行 child 应该能被修复”，这个判断是对的。当前 strong branching 出错的核心不是这个数学逻辑，而是代码里的 trial repair 不是完整 Phase-I。`LP.addFeasibilitySlacks()` 只给当前新增分支行加人工 slack，例如 required arc 右支只给 `requiredArc(i,j)` 加 slack；但 arc 右支还会同时禁掉 `i` 的其它后继和 `j` 的其它前驱，初始 restricted columns 也可能导致 coverage、machine 或其它分支行不可行。这些行没有 slack 时，`repair_slack_initial` 本身可能直接 infeasible，此时 `PC.repairInfeasibleMaster()` 会立即返回，后面的 heuristic/exact pricing 根本不会启动。因此“repair 包含 exact pricing”在这条路径上并不成立。

即使 repair LP 可行，当前 time-indexed engine 的 `findFeasible()` 仍只是普通 `price()` 的默认实现，返回当前 reduced-cost 下最多 `timeIndexedGraphMaxExactPricingColumns` 条候选，再由 `PC.generateColumnsFromEngine()` 过滤 active/pool 列后加入 RMP。它可以作为正式求解中的补列过程，但不能把一次 strong trial 的 `INF` 当作子树不可行证书。对 `wet040_001_2m` 的反例已经明确：node 2 对 `(38,34)` 分支时，`require(38,34)` 右支被 trial 标成 `INF`，但历史最优 `22580` 的第二条机器序列包含片段 `36,38,34,35`，且满足 node 1 的 `forbid(31,5)`。所以该右支不是数学不可行，而是当前 trial repair 证书不足导致的 false infeasible。后续修复应把 strong trial 的 infeasible 与正式 infeasible 分开：trial infeasible 可以用于评分降权或标记风险，但不能直接阻止最终选中分支的 child 入队，除非 repair 已经用完整 Phase-I master 和完整列族 pricing 给出真正证书。

### 2026-06-30 旧 VRP arc branch 对照后的 strong trial 修正判断

对照旧 VRP `BranchD` 后进一步收紧结论。旧 VRP 的 arc 右支确实会在 `right_node.feasible_arc` 中禁止 `i` 的其它后继和 `j` 的其它前驱，但 master 层只通过 `ForceArcValue(i,j,1,1)` 处理选中弧这一条分支行；竞争弧主要通过 `UpdateRouteSet()` 筛 route 和 pricing 中的 `node.feasible_arc` 过滤生效。

当前 TWET 的 `ArcBrancher` 也会在右支写入这些竞争弧 forbidden，但 `LP.buildArcBranchConstraints()` 会对 `Node.arcState` 里的所有 required/forbidden 弧建 master 等式行。因此当前 TWET 右支并不等同于旧 VRP：它把旧 VRP 的 pricing / route_set 过滤弧升级成了大量 RMP forbidden rows。这个差异解释了为什么只有 `requiredArc(i,j)` 有 repair slack 时，trial 初始 RMP 仍可能因为竞争 forbidden rows 或覆盖行在 restricted columns 下不可行而直接返回 infeasible。后续若要完全对齐旧 VRP，右支竞争弧应考虑改为 pricing-only / compatibility 过滤，而不是全部进入 master branch rows；至少 strong trial 不能把这种 restricted-RMP infeasible 当作正式子树不可行。

### 2026-06-30 arc 右支 master 行语义修正

进一步对照旧 VRP `BranchD` 后确认，右支 `require(i,j)` 的语义应拆成两层：master 中只建立选中 arc `x_ij = 1` 这一条分支行；由此推导出的“`i` 不能接其他后继、`j` 不能有其他前驱”只应作为路径兼容性和 pricing 禁弧，不应为每条竞争弧额外建立 master 等式行。

此前 TWET 的 `ArcBrancher` 直接把这些竞争弧写入 `Node.arcState=ARC_FORBIDDEN`，而 `LP.buildArcBranchConstraints()` 会为 `arcState` 中所有 required/forbidden arc 建 master 行。这比旧 VRP 更强，也会让 strong trial 的 restricted RMP 多出一批竞争弧 forbidden rows。现在改为新增 `branchImpliedForbiddenArc`：它会被 `Node.isArcForbidden()` 看见，从而过滤历史列、限制 pricing 扩展和后续 child 兼容性；但 `LP.buildArcBranchConstraints()` 仍只看 `arcState`，因此不会为这些推导禁弧建额外 master 行。左支 forbid arc 和右支 selected required arc 保持原来的 master 分支行语义。

这个修改不改变普通 pricing 对右支竞争弧的禁止效果，只改变 master 约束建模口径，使其和旧 VRP 的 `branch2rng` / `feasible_arc` 分工一致。验证：focused `javac` 编译 `Node`、`ArcBrancher`、`LP`、`Tree` 和常用 runner 通过。

### 2026-06-30 strong trial 筛列后重解修正

右支竞争弧改为 `branchImpliedForbiddenArc` 后，它们不再进入 master 分支行，而是通过 `Node.isArcForbidden()` 参与列兼容性过滤。这带来一个新的实现要求：strong branching phase1 trial 在第一次 RMP 可行后，如果调用 `resetRestrictedColumnsByCurrentReducedCost()` 筛掉不兼容列，就必须基于筛后的列集重解一次 LP，不能继续使用筛列前的 bound。

原因是筛列前的 LP 可能暂时使用了包含推导禁弧的旧列；这些列不进入最终 child seed。若筛列后不重解，trial 返回的 bound、seed 和可复用状态就不在同一个模型口径下。现在 phase1 在非 repair 和 repair 成功后都会在筛列后重解：若重解可行，返回该 bound 和筛后的 seed；若重解不可行，则该 trial 记为不可复用。这个修正只影响 strong branching trial，不改变正式 node 的普通求解流程；正式 node 原本就在筛列后会重解。

### 2026-06-30 关于 phase1 筛列后重解的语义澄清

这里需要区分两个问题。旧实现中，arc 右支的竞争弧也写入 `arcState`，因此 master 会为这些竞争弧建立 forbidden rows；如果 trial LP 可行，违反这些竞争弧的列不可能以正值出现在当前 LP 解中，所以后续筛列通常不会造成“bound 来自一套列、seed 又是另一套列”的口径错位。也就是说，ng-DSSR 之前没有暴露这个筛列后重解问题，并不完全是运气，而是旧建模方式把竞争弧放进了 master 行，代价是右支比旧 VRP 更强、更容易让 restricted trial/repair 变重或误判。

把竞争弧改成 `branchImpliedForbiddenArc` 后，竞争弧不再进 master 行，只通过 `isArcForbidden()` 参与列兼容性和 pricing 过滤。这样 phase1 第一次 LP 可能暂时使用包含推导禁弧的旧列；随后 `resetRestrictedColumnsByCurrentReducedCost()` 会按当前 node 兼容性删掉这些列。此时必须重解一次 LP，才能保证返回的 phase1 bound 和最终 child seed 来自同一个筛后模型。这个重解是新语义下必须补的正确性动作，不是原先 `require(38,34)` trial infeasible 的直接原因。
### 2026-06-30 arc 右支错误原因最终澄清

这次 strong branching 右支的核心错误可以更直接地表述为：此前把右支 `require(i,j)` 推导出的竞争弧也建成了 master forbidden rows，而 repair slack 只挂在当前选中的 `requiredArc(i,j)=1` 这一条分支行上。由于当前主问题是 set-covering 列模型，`requiredArc(i,j)=1` 只限制被选列中 `i->j` 的总次数为 1，并不自动表达“`i` 不能再接其他后继、`j` 不能再有其他前驱”。因此如果竞争弧也作为 master rows 出现，trial restricted RMP 可能因为这些额外 forbidden rows 不可行，而 `requiredArc(i,j)` 上的 slack 救不了它们。

旧 VRP `BranchD` 的分工不是这样。旧代码只对选中的 arc 调整 `ForceArcValue(i,j,1,1)`，竞争弧写入 `feasible_arc=-1`，由 `UpdateRouteSet()` 和 pricing 扩展过滤。当前 TWET 已按这个分工修正：左支选中 forbidden arc 和右支选中 required arc 仍写入 `arcState` 并进入 master row；右支推导出的竞争弧写入 `branchImpliedForbiddenArc`，只通过 `Node.isArcForbidden()` 过滤历史列、route enumeration、time-indexed/NG pricing 和启发式 pricing，不进入 `LP.buildArcBranchConstraints()`。

因此此前 ng-DSSR 没有报错不应理解成“数学上 required arc 会自动排掉竞争列”。真正原因是旧实现把竞争弧也放进 master 约束行，trial LP 可行时这些列自然不能为正；但这偏离旧 VRP 语义，并会让 repair slack 口径不一致。现在改回旧 VRP 口径后，phase1 筛列会真正删掉这些兼容性不合法的旧列，所以筛列后重解是必须的。

本轮同步复查了其它分支类型。`MachineCountBrancher`、`TariffSegmentBrancher` 和 `UndirectedAdjacencyBrancher` 都是分支状态本身直接对应 master row，没有像 arc 右支这样额外推导一批只应过滤的竞争状态；`pricingOnlyArc` 和 time-indexed pricing-only arc 也不进入 master row，且不参与 repair slack 语义。当前未发现同类“推导过滤状态被错误建成 master row”的第二处问题。后续如果新增分支规则，需要继续遵守这个边界：真正分支对象可以建 master row；由该分支推出的路径兼容性限制，除非明确需要 dual，否则应只进入列兼容性和 pricing 过滤。
### 2026-06-30 竞争弧不删旧列与 pricing-only 口径

进一步澄清：右支推导出的竞争弧如果只是不从当前 RMP 旧列里删除，而是像 `pricingOnly` 禁弧一样只禁止后续 pricing 生成新列，这本身不一定破坏最终正确性。此时旧列仍可暂时留在 master 中，后续列生成不会再补同类列；这个口径的主要影响是 child LP bound 可能偏乐观、强分支评分偏弱，但不会因为额外约束导致 false infeasible。

真正导致此前 strong trial 出错的是另一种口径：竞争弧被建成了 master forbidden rows。这样旧列如果包含 `i->k` 或 `h->j`，就必须在 LP 中被压到 0；而 repair slack 只挂在选中的 `requiredArc(i,j)=1` 行上，没有覆盖这些竞争弧 forbidden rows。restricted RMP 在列不够时就可能直接 infeasible。当前选择 `branchImpliedForbiddenArc` 属于比 pricing-only 更干净的列兼容性口径：竞争弧不进 master row，但 child seed 会筛掉违反竞争弧的旧列；因此筛列后必须重解，保证 trial bound 和 seed 同口径。

2026-06-30 补充：当前实现采用“筛列后重解并用筛后 LP bound 做 phase1 评分”的一致性口径。另一个可行备选是 phase1 直接使用 repair/初始可行后的筛前 LP bound 作为粗评分，筛列只用于准备 phase2 或正式 child seed；这种做法更快，且只要不把 trial infeasible 当正式不可行证书，通常不会破坏最优性，但 phase1 分数和后续 seed 列集不完全同口径，评分可能更粗。当前先保留筛后重解方案，后续若 strong branching 成本过高，可把该备选作为效率实验。
### 2026-07-01 child 继承列的分支兼容性预筛分析

针对 strong branching 和普通子节点初始化中“先继承父节点 restricted columns，再带分支行求一次 LP”的成本问题，重新分析了提前筛列的可行性。结论是：arc 类分支确实可以在 child 第一次 LP 之前做兼容性预筛，从而减轻 trial RMP 和正式 child 初始 RMP 的规模；但这个预筛只能删掉明确违反分支语义的列，不能把 master 分支行完全替代掉。

对左支 `forbid(i,j)`，可以直接删除所有包含 `i->j` 的内部机器列。此时 `x_ij=0` 这条 master 行对继承列基本冗余，但仍建议保留，因为后续 repair/pricing 生成列也要在同一套分支语义下工作，保留行有助于 reduced cost 和日志口径一致。

对右支 `require(i,j)`，不能简单只保留包含 `i->j` 的列。原因是当前主问题是多机器列模型，一个可行解通常需要多条机器列；其它机器列完全可以不包含 `i` 和 `j`，但仍负责覆盖其它任务。如果把“不含 `i->j`”的列都删掉，会错误限制所有被选机器列都必须经过同一条 arc，明显过强。右支能提前删除的是违反兼容性的竞争列，例如包含 `i->k(k!=j)` 或 `h->j(h!=i)` 的列；同时必须继续保留 `requiredArc(i,j)=1` master 行，用它保证最终解中恰好有一单位选中列覆盖 `i->j`。

这也解释了当前实现中 `branchImpliedForbiddenArc` 的角色：它适合做列兼容性过滤和 pricing 禁弧，不适合额外建 master forbidden rows。若后续实现预筛，应优先在 `prepareChildSeedColumns()` 或专门的 strong trial seed 准备入口里，对 arc 分支使用 `Node.isArcForbidden()` 过滤违反真实 forbidden 和 branch-implied forbidden 的列；但不要用“列必须覆盖所有 required arcs”作为通用兼容性过滤，否则会误删其它机器列。

外包列模式也可以做类似预筛，但要看外包列语义。如果外包列表示一个完整外包集合，`OUTSOURCE_FORBIDDEN(j)` 可以删掉包含 `j` 的外包列，`OUTSOURCE_REQUIRED(j)` 可以删掉不包含 `j` 的外包列；如果未来允许多个外包列叠加选择，则 required 侧不能简单删掉不包含 `j` 的列，而应继续依赖 membership row。机器数量分支和 tariff segment 分支属于聚合变量或连续段状态，单条机器列本身没有“兼容/不兼容”的简单判定，因此不适合用这种预筛替代首次 LP。无向 adjacency 分支与 arc 类似：forbidden adjacency 可以删除含该邻接的列，required adjacency 只能删除明显竞争的列，不能删除所有不含该 pair 的列。

因此，这个方向有优化价值，尤其适合降低 ng-DSSR strong branching phase1 的 trial RMP 成本。稳妥实现路径是新增一个 child seed 预筛开关或专门方法，在构造 trial/child seed 时先过滤违反 forbidden、branch-implied forbidden、outsourcing forbidden 的列；保留 required 行和 machine/tariff 行不变。这样不会改变分支数学语义，只减少明显无用列。后续如果要实现，应记录筛前/筛后列数、phase1 LP 时间和最终节点数，验证它是否真正降低 ng-DSSR strong branching 的 LP 负担。

进一步澄清：如果把左支 `forbid(i,j)` 和右支推导出的竞争弧 `i->k(k!=j)`、`h->j(h!=i)` 都完全作为“列域限制”处理，即继承列先筛掉、后续 pricing 也不再生成，那么这些禁止类约束本身可以不再进入 master 行。它们更像 pricing-only / route-set filtering，而不是需要 dual 的 master constraint。这样能减少 RMP 行数，也避免 repair slack 只覆盖 selected required arc 时被一堆竞争 forbidden row 干扰。

但右支 selected arc `require(i,j)` 不能省掉 master 行。预筛只删除竞争列，不会强迫解中出现 `i->j`；不含 `i,j` 的其它机器列仍然必须保留，否则会错误限制所有机器列都经过该 arc。因此 `x_ij=1` 仍应作为 master branching row 存在，并在 repair 中挂 slack。换句话说，禁止类分支/推导限制可以域过滤化，强制类分支仍需要 master 行表达。

预筛后 repair 的入口会发生变化。旧做法是“父节点列集 + 新分支行”先求 LP；如果因为新增分支行不可行，repair 有比较明确的目标。预筛后则可能先删掉大量继承列，导致初始 RMP 因列不足而不可行，此时不可行不一定只来自一条分支行，而是来自筛列后的 restricted master 覆盖能力不足。正确处理方式不是回退到加一堆 forbidden row，而是允许 repair 在已经应用同一套列域限制的 pricing 下补列：左支不再补 `i->j`，右支不再补竞争弧，但仍通过 `requiredArc(i,j)` slack/row 引导生成包含 `i->j` 的列。若 repair 使用的 pricing 是完整且与该列域一致的，则 slack 归零失败才可视为该 child 真的不可行；若只是 strong trial 的近似 repair，则不能把 trial infeasible 当作正式子树不可行证明。

更具体地说，预筛后的 repair 流程可以理解为一个受分支域限制的 Phase-I 列生成。先用筛后的旧列建一个带 slack 的 repair master：覆盖约束、机器数量约束和右支 `requiredArc(i,j)=1` 等需要表达的 master 行仍然存在；被域过滤处理的 forbidden arc / 竞争 arc 不再建行，而是通过 `node.isArcForbidden()` 影响所有 pricing。若初始 repair master 可行但 slack 为正，就用当前 repair dual 调用 pricing，只允许生成满足该 child 域限制的新列。左支不会生成含 `i->j` 的列；右支可以生成含 `i->j` 的列，也可以生成完全不含 `i,j` 的其它机器列，但不会生成 `i->k(k!=j)` 或 `h->j(h!=i)` 的竞争列。每补一批列后重解 repair master，直到 slack 全部为 0、或完整 pricing 证明没有可补列。

因此，筛列后不可行并不是异常。它只是说明父节点传下来的旧列在新 child 域内不够用，需要 repair pricing 补列。真正要避免的是把 strong branching trial 中一次有限预算的 repair 失败误读为正式不可行。正式 child 求解可以从筛后的 seed 开始；如果需要 repair，就完整走当前 child 域限制下的 repair/pricing 流程。

再次校正 repair 细节：如果先做列预筛，不能只在 `requiredArc(i,j)=1` 这条分支行上加 slack。因为预筛本质是删除变量，筛掉旧列以后，即使完全不加 `requiredArc(i,j)` 这条约束，restricted master 也可能因为某些 job 覆盖不到、机器下界满足不了、外包 membership 行缺列等原因不可行。此时单独给 required arc 行加 slack 没有意义，模型仍可能在覆盖行或其它行上不可行。

正确的 repair 口径应是完整 Phase-I master：对所有可能因列不足而不可行的“必须满足”行加入人工 slack 或等价人工列，至少包括 job 覆盖行、required arc 行、需要强制外包/禁止外包的 membership 行，以及有下界语义的机器数量行。forbidden / branch-implied forbidden 不作为 master row 时不需要 slack，它们只限制 pricing 生成列。Phase-I 的目标是最小化人工 slack 总量；pricing 在当前 child 域限制下生成能够降低 Phase-I 目标的合法列。只有当完整 Phase-I pricing 证明无法再降低 slack 且 slack 仍为正时，才能说该 child 在当前列生成框架下不可行。

因此，如果后续只想做轻量 strong branching trial，有两种安全选择：一是预筛后若初始 RMP 不可行，只把该 trial 标为“未修复/弱信息”，不当正式不可行；二是实现完整 all-row Phase-I repair，再把 repair 失败作为可信 infeasible。不能采用“预筛列 + 只给 selected required arc 加 slack + trial infeasible 即丢 child”的组合。

关于“筛列后是否还需要分支约束”的进一步判断如下。若把某些分支完全改写成列域限制，则 forbidden 类约束可以不再建 master row；例如左支 `forbid(i,j)`、右支推导竞争弧、无向 forbidden adjacency，以及外包列的 forbidden membership，都可以通过删除旧列和禁止后续 pricing 生成来表达。这样 RMP 行数更少，Phase-I repair 也不会被这些禁止行干扰。

但 selected/required 语义要谨慎。对右支 `require(i,j)`，如果同时禁止 `i` 的所有其它后继和 `j` 的所有其它前驱，并且所有必须加工的任务都只能通过机器列覆盖，那么在整数 elementary 解上，覆盖 `i` 和 `j` 会迫使使用 `i->j`。这种“纯域过滤”可以作为一种较弱但更便宜的 trial 分支口径。然而在当前 set-covering LP 松弛中，它不完全等价于 `x_ij=1`：LP 可能通过重复覆盖或分数列得到比正式 required-row 更松的下界。因此正式 node 若追求更强下界，仍建议保留 `requiredArc(i,j)=1` 行；若只为了强分支快速评分，可以考虑用纯过滤版本作为近似 trial，但必须明确这是评分近似，不是正式 bound 证书。

筛列后 repair 的更稳妥流程应为：先用筛后的真实列求一次普通 LP；若可行，直接得到 phase1 bound。若不可行，则构造 Phase-I repair master，不使用原始目标作为主目标，而是最小化人工 slack 总量。人工 slack 不应只挂在分支行上，而应覆盖所有可能因为删除变量而失去可行性的必须满足行，主要是 job 覆盖行，以及仍保留为 master row 的 required arc / required adjacency / outsourcing membership / machine 下界等。原始目标可以完全不放入 Phase-I，也可以只作为很小的 tie-break；优先级必须低于 slack 总量。

Phase-I 第一次求解后，不建议只保留当前非零 slack、删除零 slack。虽然这能减少人工变量，但会把后续 Phase-I 的可行域变窄：加入新列后，最优 slack 分布可能发生变化，当前为零的行以后可能需要临时使用 slack 来换取更小的总 slack。由于 coverage slack 数量只有 `O(n)`，保留全部人工 slack 的开销很小，语义也最清楚。等 Phase-I slack 总量为 0 后，再移除人工变量或重建普通 LP，进入原来的 reduced-cost 筛列、phase2 trial 或正式 node pricing 流程。

如果 Phase-I slack 仍为正，则用 Phase-I dual 做 repair pricing，生成满足当前 child 列域限制的新列来降低 slack。这个过程可以不断重复，直到 slack 归零，或者完整 pricing 证明没有能降低 Phase-I 目标的列。只有后一种情况才能说明 child 真实不可行。对 strong branching 的轻量试探，如果不愿意做完整 Phase-I pricing，则可以把“筛后不可行/未修好”作为差的 trial 信号，但不能因此丢弃正式 child。

若采用“Phase-I 目标只最小化 slack 总量”的第一版，实现上应明确分成两段。第一段是人工可行性模型：真实列的目标系数为 0，只保留结构约束，并给可能缺列的必须满足行加人工 slack，目标为最小化 slack 总量。该模型只用于 repair，不提供分支 bound。若 slack 为正，则用该 Phase-I 模型的 dual 调用 pricing，生成能够降低人工 slack 的合法列；新列仍写入全局 pool，但在 Phase-I 模型中的目标系数应为 0。若 slack 归零，说明当前 child restricted columns 已经足以构成可行 RMP。

第二段必须切回正常 RMP：移除人工 slack 或直接用修复后的列集重建普通 LP，恢复原始 TWET objective、required arc / machine / outsourcing 等正常行，然后求解一次普通 LP。这个普通 LP 的目标值才是 strong branching phase1 bound 或正式 child 初始 bound。也就是说，Phase-I 的结果只回答“能不能修到可行、补了哪些列”，不回答“这个 child 的下界是多少”。这会比现有 repair 复杂，但语义最干净，且能避免把 slack 目标和真实目标混在一起造成评分偏差。
### 2026-07-01 强分支建模时间与求解时间拆分

为避免把临时 RMP 的构建时间和 CPLEX 求解时间混在一起，本次在 strong branching trial 的 `LP.construct(child, seedColumns)` 外侧单独增加 `MasterLPBuild` 统计。原有 `strong_branching_rmp`、`strong_branching_after_column_filter`、`strong_branching_phase2_initial` 和 `strong_branching_after_heuristic` 仍只表示 LP 求解时间，不包含建模。

在 `wet040_001_2m_setupR50`、setup cost 系数 20、ng-DSSR nearestK8、strong branching、ALNS seed、dual-bound pruning、completion bound 与 time-indexed helper 均按当前主线配置开启的验证 run 中，结果为：`master LP build time = 0.204s / 96 calls`，其中 phase1 build `0.187s / 80 calls`，phase2 build `0.017s / 16 calls`；对应的 strong branching LP solve time 为 `113.304s`，其中 phase1 初始 RMP `78.366s / 80 calls`，phase1 筛列后重解 `28.482s / 80 calls`，phase2 initial `5.663s / 16 calls`，phase2 heuristic 后重解 `0.793s / 104 calls`。同次 run 的 strong branching 启发式 pricing 为 `15.523s / 120 calls`。

因此当前证据很明确：强分支慢的主要原因不是建模，而是 trial LP 求解，尤其是 phase1 的初始 RMP 和筛列后重解。建模耗时只占 strong branching LP 求解时间约 `0.18%`，即使继续优化 `LP.construct`，对总耗时的帮助也很小；后续若要加速，应优先减少 phase1 试探次数、降低 trial RMP 列规模、减少筛列后重解次数，或者把部分 trial 改成更粗的评分口径。


## 2026-07-01 domain-filtered strong branching repair 实现记录

本次按前面讨论的“先按 child 域筛列，再 repair”的思路，新增了一个默认关闭的实验开关 `enableStrongBranchingDomainRepair`。它只作用于 arc 分支和列化外包 membership 分支；机器数量、tariff segment、无向 adjacency 等分支仍走原来的 strong branching trial 和旧 repair。这样做的目的不是替换现有 repair，而是在那些可以明确用列域表达的分支上，先删除明显不兼容的继承列，降低 trial RMP 的规模，再看是否需要补列修复。

具体流程为：strong branching Phase 1 为某个候选构造左右 child 后，如果开关打开且分支类型适用，就先从父节点当前 restricted internal columns 中保留 `child.isColumnCompatible()` 为真的列；列化外包时，也从父节点当前 restricted outsourcing columns 和 child 自带的 required outsourcing seed 中保留 `child.isOutsourcingColumnCompatible()` 为真的外包列。随后用这批筛后的 seed 构造 trial LP。若筛后 LP 可行，就继续按原 strong branching 逻辑做 reduced-cost 筛列和重解；若不可行，则进入新 all-row feasibility repair。

新 repair 和旧 repair 并存。旧 repair 仍只给当前新分支行加 slack，继续用于普通子节点和不适合 domain-filter 的分支。新 all-row repair 只在上述 strong branching trial 中使用，它给覆盖、机器数、外包列数量、arc/adjaency 分支行、SRI cut 行、tariff active/branch 行等已有核心约束的有限上下界加人工 slack。这里没有做纯“只最小化 slack 总量”的 Phase-I 模型，而是保留真实列成本并给 slack 一个 `big_M` 惩罚。原因是当前 pricing engine 的 reduced-cost 计算都基于真实列目标和原始 dual 分解；如果把真实列目标统一改成 0，需要给所有 pricing engine 额外实现 Phase-I objective 口径，改动面更大。当前版本等价于一个 big-M 人工变量 repair，语义更接近现有代码，也便于局部验证。后续如果需要严格纯 Phase-I，可以在这个入口上继续扩展。

验证方面，排除历史 `src/BPC` 旧 VRP 包后，当前主线 Java 源码 `javac -encoding UTF-8` 编译通过；`git diff --check` 对本次修改文件通过。用 `wet040_001_2m` 的 time-indexed strong branching smoke 打开 `strongBranchingDomainRepair=true` 后，日志确认进入 `strong_branching_domain_rmp_build` 和 `strong_branching_domain_rmp`，120 秒限制下结果为 `status=TIME_LIMIT, obj=22582, bound=22487.647059, valid=true`。该 smoke 没触发 all-row slack repair，说明本次试探中筛后 LP 多数已可行；早先一次同口径 smoke 曾触发 domain repair phase，说明入口可达。当前结论是：新实现提供了可对比的实验分支，旧 repair 路径未被移除，默认关闭时不改变原流程。


### 2026-07-01 domain-filtered repair smoke 对照结果

补跑 `wet040_001_2m`、time-indexed graph pricing、strong branching、`maxNodes=2`、`solveTimeLimitSeconds=120` 的开关对照。关闭 domain repair 时，run 为 `tmp-domain-repair-timeindexed-smoke-off`，结果 `TIME_LIMIT, obj=22582, bound=22487.647059, exact=14.593s/349, valid=true`；打开 domain repair 时，run 为 `tmp-domain-repair-timeindexed-smoke-rerun`，结果 `TIME_LIMIT, obj=22582, bound=22487.647059, exact=8.375s/349, valid=true`。

这个 smoke 不能证明整体求解已经变快，因为两次都在 120 秒限制附近被截断，且强分支试探路径不同。更可靠的局部信号是 strong trial LP 的单次成本下降：关闭时 `strong_branching_rmp=6.310s/4 calls`，平均约 `1.577s`，`strong_branching_after_column_filter=0.365s/3 calls`；打开时 `strong_branching_domain_rmp=21.760s/33 calls`，平均约 `0.659s`，`strong_branching_domain_after_column_filter=2.235s/32 calls`，平均约 `0.070s`。建模时间也从关闭时 `0.101s/4 calls`、平均 `25.219ms`，变为打开时 `0.215s/33 calls`、平均 `6.518ms`。因此当前结论是：预筛列确实降低了单个 trial RMP 的列规模和单次 LP 成本，但是否减少总时间要在完整 ng-DSSR 强分支场景下继续 A/B；本次 smoke 只能说明局部方向有效，不能直接说总求解已经变快。


### 2026-07-01 domain-filtered repair 完整求解对照

补做同一配置的完整求解，实例为 `wet040_001_2m`，time-indexed graph pricing，strong branching 开启，route enumeration 关闭，时间上限 1800 秒，只切换 `strongBranchingDomainRepair`。关闭时 run 为 `tmp-domain-repair-timeindexed-full-off`，结果 `FINISHED, obj=bound=22580, solve=219.165s, root=108.112s, nodes=11, pricing=694, cols=101464, exact=15.686s/613, masterLP=161.453s, valid=true`。打开时 run 为 `tmp-domain-repair-timeindexed-full-on`，结果 `FINISHED, obj=bound=22580, solve=272.984s, root=71.936s, nodes=9, pricing=1153, cols=111792, exact=12.964s/547, masterLP=134.166s, valid=true`。

完整结果说明：domain-filtered repair 在这个 time-indexed 40-2 实例上没有提升总时间，反而从 `219.165s` 变慢到 `272.984s`。它确实带来了局部好处：root 时间更短，节点数从 11 降到 9，正式 exact pricing 时间和调用次数也下降。但强分支试探中的 domain repair 额外成本较高：打开后新增 `strong_branching_domain_repair_slack_initial=2.112s/46`、`strong_branching_domain_repair_after_pricing=2.190s/585`、`strong_branching_domain_repair_final=1.690s/25`、`strong_branching_domain_repair_after_column_filter=1.194s/25`，并且 FindFeasible pricing 从关闭时 `0.262s/81` 增加到 `3.404s/606`。同时打开后列数从 101464 增加到 111792，pricing 轮数从 694 增加到 1153。当前结论是：预筛列降低了单个 trial RMP 的平均求解负担，但 all-row repair 会显著增加试探过程中的 repair/pricing 次数；在这个实例上净效果为变慢。该开关应继续默认关闭，后续若要保留，建议只作为 ng-DSSR 强分支 LP 过重时的可选实验项，并优先减少触发 domain repair 的次数或把 repair 做得更轻。


### 2026-07-01 root 时间差异和变慢原因复查

进一步复查 `tmp-domain-repair-timeindexed-full-off/on` 两个完整 run 后，确认 CSV 中的 `root_s` 不是单纯的“根节点列生成闭合时间”，而是包含 root node 完成 LP/pricing 后执行分支选择的 strong branching 试探时间。因此打开 domain-filtered repair 后，即使 root pricing 本身口径几乎相同，root_s 也会因为 root 上的 trial RMP 构造、筛列、repair 和求解方式变化而不同。

根节点定价本身两组基本一致：`node=1` 的 time-indexed pricing 都是 `349` 次，新增列都是 `84121`，root 分支前 pool 都到 `84133`，并且 root 最终都选择 arc `(5,9)`。差别主要来自 strong branching 试探。关闭时 root strong trial 使用普通继承列，打开时先按 child 域筛列，单次 trial RMP 更小，所以 root_s 从 `108.112s` 降到 `71.936s`。pricing 日志中两组同样的 root 定价调用耗时有几秒差异，这属于同工作量下的运行波动，不是算法语义差异。

总时间变慢的直接原因不是 root，而是后续 strong branching trial 的 repair 代价和额外未归类开销。打开后节点数从 `11` 降到 `9`，正式 exact pricing 从 `15.686s/613` 降到 `12.964s/547`，master LP 汇总也从 `161.453s` 降到 `134.166s`，这些局部指标看起来更好。但打开后新增了 `strong_branching_domain_repair_slack_initial=2.112s/46`、`strong_branching_domain_repair_after_pricing=2.190s/585`、`strong_branching_domain_repair_final=1.690s/25`、`strong_branching_domain_repair_after_column_filter=1.194s/25`，同时 `FindFeasible` 从 `0.262s/81` 增加到 `3.404s/606`，总 pricing 轮次从 `694` 增到 `1153`，列池从 `101464` 增到 `111792`。

此外，总时间与已汇总的 master/pricing 时间之间的差额明显扩大：关闭时约 `219.165 - 161.453 - 15.686 - 0.262 = 41.764s`，打开时约 `272.984 - 134.166 - 12.964 - 3.404 = 122.450s`。这说明新模式还有大量时间没有落在 master LP / pricing summary 里。结合代码路径，主要嫌疑是 strong branching trial 前的 `prepareDomainFilteredChildSeedColumns()` 兼容性筛选：每个 candidate 的左右 child 都要扫描父节点 restricted columns，并对每条机器列调用 `child.isColumnCompatible()` 检查完整序列；pool/restricted 很大时，这部分是纯 Java 侧开销，不在 `LP.construct` 计时内，也不在 pricing 计时内。因此当前结论是：domain-filtered repair 确实能降低单个 trial RMP 的 LP 求解压力，但引入了更多 repair/pricing 和大量 Java 侧筛列扫描，在该实例上净效果为变慢。

### 2026-07-01 domain-filtered repair 暂停结论

用户原始原因记录如下，保留原口径，作为当前暂停该方案的直接依据：

1、加入的slack变量过多，可能会导致找列的时候来回跳动？不像之前那样只在一个约束里边加入有针对性
2、同样的，过多的slack会导致目标里边过多的M，这显然不利于求解，不管是什么
3、现在用的是原始目标+M的方式，这确实可能导致求解更难，以及就算按之前说的只考虑目标里边保留slack，方案上可行，但需要来回变换模型目标，删除slack变量等等。估计效果也不会好，可能中间的操作也会吃掉时间。

结合完整 A/B 和后续原因复盘，当前 domain-filtered strong branching repair 暂停作为常用方案。核心原因不是单个 trial LP 是否能变小，而是筛列后不可行时引入了全行 slack repair：slack 变量数量明显增加，dual 会同时受覆盖、机器数、分支行等多类人工变量影响，pricing 在找可修复列时更容易来回跳动；目标中也会出现大量 `big_M` 惩罚项，使 LP 和定价的数值口径更重。当前实现采用“原始目标 + big-M slack”的形式，是为了兼容现有 pricing engine 的 reduced-cost 口径，但这本身会增加求解难度。若改成纯 slack Phase-I，理论上可行，但需要在 repair 期间切换目标、随后删除 slack 或重建模型，并为各 pricing engine 明确 Phase-I reduced-cost 口径，中间操作成本和复杂度都不小，预期收益不明确。

因此当前决定是：底层实验代码保留，方便以后复查；`enableStrongBranchingDomainRepair` 继续保持 `false`；常用 full-domain runner 不再读取 `twet.bpc.fullDomainCompare.strongBranchingDomainRepair` 系统属性，避免历史命令残留参数误开。主线强分支仍使用之前的 repair 流程。

### 2026-07-01 基于父节点正值列保留的轻量筛列 repair 思路

新的判断是，domain-filtered all-row slack repair 暂停后，仍可以考虑一个更轻的变体：子节点 trial 初始列不再完整继承父节点 restricted 列，而是先删掉明显违反 child 域的非正值列，同时无条件保留父节点当前 LP 的正值列。这样做的目的，是让“父节点正值列 + 不加新分支行”仍然提供一个覆盖/机器数可行的支撑；随后带新分支行求 trial LP，如果不可行，理论上主要就是新增分支行导致的问题，仍可走旧 repair，只给当前新增分支行挂 slack，而不需要 all-row slack。

这个思路总体是成立的，但有一个边界必须保留：右支 `require(i,j)` 不能理解成每条机器列都必须包含 `i->j`。正式约束是所有被选列对该弧的访问和等于 1；其它机器列不含 `i->j` 是正常的。因此可提前删除的是违反 forbidden / branch-implied forbidden 域的列，例如左支中包含 forbidden arc 的非正值列，右支中包含 `i->k(k!=j)` 或 `h->j(h!=i)` 的非正值列，而不是所有不含 `i->j` 的列。

更准确地说，这个方案本质上就是改变 repair 的初始列准备方式。Phase 1 初始 trial 可以保留父节点正值列，即使其中有违反 child implied forbidden 的列，用它们保证“父节点可行解 + 新分支行”这个 repair 起点仍然容易建立；随后旧 repair 只给当前新增分支行挂 slack。等 repair 成功并进入筛列阶段时，再按 child 域删除违反约束的列，最终复用到正式队列的 seed 仍然是 child-compatible 的。这样不需要 all-row slack，也不需要额外设计“不可复用 child”的分支逻辑；关键只是把“repair 起点列”和“repair 后正式 seed”分清楚。

因此若后续实现，最小改动方向不是恢复 all-row repair，而是在 strong branching trial 中新增一种“正值列保留 + 非正值域过滤”的 seed 准备方式：Phase 1 初始 trial 用它来降低列数并保持旧 repair 可行；repair/筛列完成后，复用 child 时使用筛选后的 child-compatible seed。这个方案比全行 slack repair 更贴近旧流程，预计改动也更小。

### 2026-07-01 轻量 repair seed 实现与初步结果

按上面的轻量方案新增 `enableStrongBranchingLightweightRepair`，默认关闭。常用 full-domain runner 通过 `twet.bpc.fullDomainCompare.strongBranchingLightweightRepair` 显式打开。实现只改变 strong branching Phase 1 的初始 seed：父节点当前 LP 正值机器列无条件保留，其它机器列按 child 兼容性过滤；列化外包列当前仍按 child 兼容性过滤，因为现有 `TWETMasterSolution` 只保存外包 job 聚合值，不直接保存正值外包列 id。后续 trial 仍调用旧 repair，即只给当前新增分支行挂 slack，不进入 all-row slack repair。

在 `wet040_001_2m`、time-indexed graph pricing、strong branching、route enumeration 关闭、1800 秒限制的同口径配置下，结果如下。普通 strong repair 关闭轻量/全行方案时为 `219.165s, 11 nodes, pricing=694, pool=101464, exact=15.686s/613, masterLP=161.453s`。all-row domain repair 为 `272.984s, 9 nodes, pricing=1153, pool=111792, exact=12.964s/547, masterLP=134.166s`，虽然节点少但 repair/pricing 过多而变慢。轻量 repair seed 修正最终筛列口径后为 `172.349s, 9 nodes, pricing=1041, pool=109465, exact=15.068s/510, masterLP=116.871s, valid=true`，得到同一最优值 `22580`。

从日志看，轻量方案没有引入 all-row repair phase，只出现旧的 `repair_slack_initial/repair_after_pricing`；strong trial 构造阶段记录为 `strong_branching_light_repair_rmp_build=0.499s/280 calls`，正式 master LP 中 `strong_branching_light_repair_rmp=52.784s/280`、`strong_branching_light_after_column_filter=19.138s/246`，FindFeasible 为 `2.716s/531`。因此当前初步结论是：这个变体确实比 all-row repair 更贴近旧流程，也比普通 strong repair 更快；但目前只在一个 time-indexed 40-2 算例上验证，仍应保持默认关闭，后续再在 ng-DSSR 和更大实例上复测。

### 2026-07-02 ng-DSSR top10 + bestUB 轻量 repair 对照

为了避免前一次黑盒运行被 ALNS 初始阶段干扰，本次显式把 ALNS 限制为 30 秒，并打开 live trace。两组均使用 `wet040_001_2m` 原始 setup，`ng-DSSR nearestK8/top10`、`joinBest=bestUB`、two-stage strong branching、`completionBound=allCycles`、time-indexed 硬时间窗/标量加强、dual bound pruning、pricingOnly subtree arc elimination，route enumeration 关闭。两组唯一差异是 `enableStrongBranchingLightweightRepair`。

普通 strong repair 结果为 `200.662s, 16 nodes, pricing=959, pool=79562, heuristic=44.484s/250, exact=30.309s/101, masterLP=59.561s, valid=true`。其中 strong branching 相关 LP 时间为 `strong_branching_rmp=32.267s/320`、`strong_branching_after_column_filter=18.099s/318`，phase2 heuristic 为 `34.191s/595`。

轻量 repair seed 结果为 `189.187s, 15 nodes, pricing=887, pool=78569, heuristic=47.604s/223, exact=30.453s/95, masterLP=47.359s, valid=true`。其中 `strong_branching_light_repair_rmp=21.212s/320`，相比普通 repair 的 phase1 初始 LP 降低约 11 秒；筛列后 LP 基本相近，`17.652s/315`。最终总时间降低约 11.5 秒，约 5.7%。

当前判断是：轻量 repair 在 ng-DSSR 下也有效，但收益主要来自 phase1 初始 trial LP 变小；整体收益会被启发式 pricing、exact ng-DSSR 和 phase2 heuristic 覆盖，因此没有 time-indexed 对照中那么明显。这个结果支持保留该开关继续测试，但还不足以直接默认开启，需要在 50/60 规模和放大时间算例上复测。
### 2026-07-02 普通 child 入队复用轻量 seed

前面的 light repair 实验本质上验证的是一种 child 初始列准备方式，而不是 strong branching 专属的求解机制。因此本次把它最小范围接入普通分支入队：当 `enableStrongBranchingLightweightRepair=true`，且当前分支是 arc 分支，或列化外包模式下的 outsourcing membership 分支时，child seed 不再简单继承父节点全部 restricted columns，而是保留父 LP 正值机器列作为 repair 起点，其它机器列按 child compatibility 预筛；外包列仍按 child compatibility 过滤。机器数、tariff/segment、无向 adjacency 等分支保持旧逻辑，其中无向 adjacency 理论上也可类似处理，但当前主线暂不使用，先不扩大改动范围。

这个修改只影响普通 child 初始 RMP 的列集，不改变 pricing 可生成列集合，也不改变 repair 流程。child 出队后仍先解初始 LP；若可行，继续走原来的 reduced-cost/compatibility 筛列并重解；若不可行，仍走旧 repair。关闭 `enableStrongBranchingLightweightRepair` 时普通分支路径完全回到旧的全继承 seed 逻辑。

### 2026-07-02 普通 child 轻量 seed 正确性检查

本次复查确认该修改只改变普通 child 入队时的初始 seed，不改变 pricing 可生成列集合，也不跳过 PC 的正式 repair/filter 流程。`enableStrongBranchingLightweightRepair=false` 时，`useLightweightChildSeedForBrancher()` 直接返回 false，普通分支仍走旧的全继承父 restricted 列逻辑。打开时只覆盖 `ArcBrancher` 和列化外包下的 `OutsourcingMembershipBrancher`；机器数、tariff/segment、无向 adjacency 等保持旧逻辑。

需要注意的是，columnized outsourcing 模式下 `LP.construct()` 对内部机器列使用 `node.isColumnCompatible()`，因此即使 light seed 试图保留父 LP 正值机器列，违反 child 域的机器列也会在建模入口被过滤。这和 strong trial 现有行为一致，不影响正确性，但说明“父正值列保留作为 repair 起点”的收益主要发生在非 columnized 的机器列 arc 分支。

验证上，focused `javac` 编译通过；另外用 time-indexed no-strong smoke 覆盖普通分支入队路径，`wet040_001_2m` 在 `maxNodes=2`、`enableStrongBranchingLightweightRepair=true` 下正常运行到 `NODE_LIMIT`，日志中 root 和 node2 均通过 `ArcBrancher` 分支，结果 `valid=true`。另一个 ng-DSSR 60 秒 smoke 正常到 `TIME_LIMIT/valid=true`，但停在 root，没有覆盖普通分支路径，只作为运行稳定性检查。

2026-07-07 复核 time-indexed strong branching 与 lightweight seed 的关系。lightweight 不是某个 pricing engine 的内部能力，而是 strong branching trial 建 child seed columns 的方式，因此 pure time-indexed、ng-DSSR 以及列化外包 membership 分支都可以共用这条入口；它只在 `enableStrongBranchingLightweightRepair=true` 时对 `ArcBrancher` 和列化外包下的 `OutsourcingMembershipBrancher` 生效，不作用于机器数、tariff segment 等分支。本次 `wet060_001_3m` pure time-indexed run 虽然开启了 two-stage strong branching，但日志显示 `enableStrongBranchingLightweightRepair=false`，因此 phase-1 trial 直接在 root 闭合后的 `159180` 条 restricted columns 上求 LP，单次 trial 约 5-9 秒。这不能说明 lightweight 对 60-3 无效，只说明该组配置没有启用 lightweight。后续对 time-indexed 大列池做 strong branching 对比时，应显式记录是否打开 `twet.bpc.fullDomainCompare.strongBranchingLightweightRepair=true`，并优先与 `strongBranchingBranchImpliedPenalty=true` 搭配。

2026-07-07 调整常用 full-domain runner 的 strong branching light 默认口径。前面 `wet060_001_3m` pure time-indexed root 闭合后停在 strong branching，日志显示 strong 开启但 `enableStrongBranchingLightweightRepair=false`，导致 phase-1 trial 直接在 `159180` 条 restricted columns 上求 LP，单次 5-9 秒。结合此前 40-2 time-indexed 和 ng-DSSR 对照中 light seed 均有正收益，本次只修改 `GCBBFullDomainComparisonTest` 的默认覆盖值：当 `enableTwoStageStrongBranching=true` 时，`strongBranchingLightweightRepair` 默认也为 true；仍可通过 `twet.bpc.fullDomainCompare.strongBranchingLightweightRepair=false` 显式关闭做消融。底层 `TWETBPCConfig` 默认值不变，避免影响其它 runner。该修改只改变 arc 分支和列化外包 membership 分支的 child seed 准备方式，不改变 pricing 可生成列集合和正式 repair 语义。

### 2026-07-14 strong branching 误判不可行的确定根因

40-3 时间直接放大十倍后，strong branching 版本错误闭合到 `156590`，而关闭 strong branching 后得到并验证了可行最优值 `156580`。这不是左右分支语义或 dual window 的问题。错误发生在 node 10 对 arc `(9,29)` 的右支 `require(9,29)`：strong trial 报告 `Repair RMP still has positive artificial slack after generating 308 columns`，随后把该侧记为 `INF` 并丢弃。已验证的 `156580` 最优解同时满足该节点的全部祖先分支：包含 `27->2`、不包含 `38->35`、不包含 `31->9`，并包含 `9->29`，因此该右子树实际可行，且包含全局最优解。

针对同一配置增加临时诊断后，node 4 的 `require(6,22)` 复现了完全相同的错误链条。repair LP 中人工 slack 和 branch-implied 竞争列的目标系数使用 `Utility.big_M=1e8`，由此得到 `machineDual=-2.99981125e8`、`requiredArcDual=1e8`，部分 job dual 也接近 `1e8`。completion bound 的 source 函数会加上 `-machineDual`，因此一个仍然有限、后续还能被 `-arcDual` 降低的前缀暂时达到约 `3e8`。但 PWLF 同时把 `value >= 0.5*big_M` 当成不可行 BigM；`normalizeForward()` 因而提前删除了这些有限前缀，诊断结果为 `finitePrefixCount=0, rawBest=1e8`。

随后 `completionBoundForwardSinkLowerBound()` 在没有有限 prefix 时返回 `0.0`，`tryApplyCompletionBoundPreCertificate()` 把它解释为“内部列族最小 reduced cost 非负”，直接跳过真正的 ng-DSSR labeling。repair 因此得不到补列，人工 slack 保持正值，`repairInfeasibleMaster()` 返回 infeasible；Tree 再把这个 trial 结果转换为 `pseudoCostInf`，最终丢掉一个真实可行的 child。根因是同一个有限常数 `1e8` 同时承担了两种不兼容语义：repair 的人工目标尺度，以及 PWLF 的不可行哨兵。它不是启发式没找到列，也不是 lightweight seed 本身不足，而是 exact repair pricing 在 M 量级对偶下被错误截断并给出了假证书。

下一步修复不能只关闭 completion-bound pre-certificate。ng-DSSR 的正式 frontier 也使用同一套 PWLF normalize，M 量级 dual 仍可能把有限函数误当不可行。更稳妥的最小方向是把 repair 改为数值尺度独立的 Phase-I：只最小化人工 slack/脏列质量，人工系数使用正常量级，不再把真实列目标与 `1e8` 惩罚混在一起；Phase-I 可行后再恢复真实目标。修复前，strong trial 中基于当前 M-scale repair pricing 得到的 `INF` 不能作为子树不可行证明。临时诊断代码已移除，本节只记录已经复现和确认的原因。

进一步核对确认，`5e7` 来自 `Utility.big_M=1e8` 与 `BIG_M_STATE_RATIO=0.5`。该判断在 2026-05-16 引入，原意是让 PWLF 的不可行 M 段经过普通固定成本或 dual 平移成为 `M+a`、`M-a` 后仍能被识别。它依赖的隐含前提是所有真实函数值始终远小于 `M/2`；strong repair 的 M 量级 dual 直接破坏了这一前提。单纯把比例从 `0.5` 调成 `0.9`、放大 `big_M` 或改成“接近 M”判断都不能保证正确：repair dual 会随同一个 M 一起缩放，合法值可以是数个 M，而真正的 M 段经过 dual 平移后也可能远离 M。

修复应分两层。第一层是立即保证 BPC 正确性：strong trial 一旦进入当前 M-scale repair，其失败结果只能标记为不可复用，不能作为真实子树 `INF`；仅关闭 completion-bound pre-certificate 仍不充分，因为正式 label frontier 也会经过相同 PWLF normalize。第二层是根治 repair 数值语义，建议改成独立 Phase-I：兼容真实列目标为 0，人工 slack 和保留下来的 branch-implied 脏列目标为 1，pricing 同步按 Phase-I reduced cost 找列；Phase-I 目标达到 0 后关闭 repair mode，再恢复真实列成本求正式 RMP。这样既不依赖任意大 M，也不会让 repair dual 与 PWLF 的不可行哨兵相撞。另一条更彻底但改动更大的路线是给 PWLF 增加独立的不可达表示，并把它与 `Utility.big_M`、外包禁用成本、启发式无效值完全拆开。

讨论过一个更小的工程修补：保持 PWLF BigM 语义不动，把 repair 中 branch-implied 列和 artificial slack 的目标系数都从 `1e8` 改成 `incumbent+1` 或 `2*incumbent`，从而让 repair dual 回到普通目标量级。这个办法对当前实例大概率能消除数值碰撞，但不能单独作为严格不可行证明，因为 RMP 中 slack 和脏列取值可以是任意小的分数，有限 penalty 未必强制它们归零。若采用该方案，positive slack/M 只能使 strong trial 返回 `UNUSABLE`，不能返回 `INF`；它适合作为最小安全止血和 A/B 实验，不替代严格 Phase-I。另一个细节是本次诊断中的极端 dual 已由 required-arc artificial slack 直接产生，因此只改竞争列成本仍不够，二者必须同时修改。全局 `Utility.big_M` 也不需要从 `1e8` 放大到 `1e9`，否则会影响数百处无效成本和 PWLF 判断；应新增独立 repair penalty，限制改动范围。

### 2026-07-14 有限 repair penalty 修复与回归

按当前实验口径实施最小修复：`Utility.big_M` 从 `1e8` 放大为 `1e10`，同时新增独立 `repairObjectivePenalty=50*incumbent`。该有限 penalty 同时用于 required/forbidden 分支 repair 的 artificial slack、all-row 实验 repair slack，以及 branch-implied 竞争列，避免只改其中一侧后仍产生 M 量级 dual。普通列的真实目标不变；strong trial 与正式节点 PC 都在首次建模前按各自当前 incumbent 写入 penalty。没有有限 incumbent 时才退回 `Utility.big_M`。

针对原错误实例 `wet040_001_3m_timeX10` 重新运行相同的 ng-DSSR strong branching 主线。修复后得到 `obj=bound=156580`、`valid=true`，共 17 个节点、总时间 `173.493s`、exact pricing `11.827s/57`；日志中 `positive artificial slack` 和 `rmp_trial_infeasible` 均为 0 次。该结果恢复了关闭 strong branching 时已经验证的最优值，并消除了此前错误闭合到 `156590` 的现象。focused 编译和 `LPRestrictedColumnMembershipTest` 同时通过。

当前实现保留既有的 residual 判定语义：repair 结束后若 artificial slack 或 branch-implied penalty 列仍为正，仍按该侧不可行处理。本次回归证明 `50*incumbent` 已解决已复现的数值碰撞；有限 penalty 对任意分数 LP 的严格词典序保证仍不等同于独立 Phase-I，这一理论边界保留在记录中，后续若再出现 residual 误判应直接转独立 Phase-I，而不是继续放大 penalty。

### 2026-07-14 修复后正确性复核

本次按生产控制流重新检查了 penalty 的设置、模型重建和新增列路径。正式节点在 `PC.solve()` 首次 LP 前写入 penalty；strong phase-1 在首次 trial LP 前写入。`solveRelaxation()` 每次重建 CPLEX 模型时会继续读取 LP 对象中的同一 penalty，repair 过程中 `resolveCurrentModel()` 新增的内部列也通过 `internalColumnObjectiveCost()` 使用同一口径。required/forbidden 分支 slack、all-row 实验 slack 和 branch-implied 竞争列均已覆盖，普通列成本及 strong phase-2 的正式 seed 成本没有被替换。生产 `Tree` 在 root 前必经 `HeuristicSeedProvider`，后者会建立并写入 `bestSolution`，因此正常 BPC 路径不会走“incumbent 非有限时退回 `Utility.big_M`”的 fallback。

全局 `big_M` 放大也做了交叉检查。当前 TWET 主线中没有继续硬编码 `1e8` 作为外包/PWLF 不可行值；`Data.outsourcingCost` 默认直接读取 `Utility.big_M`。相同 PWLF property test 分别在运行时设置 `big_M=1e8` 和 `1e10`，结果均为 `passed=27, warnings=2, failed=7`，说明本次放大没有新增该测试中的行为差异；这 7 项是当前测试源本身已有的报告项，不作为本次修复通过项。`OutsourcingMoveConsistencyTest` 完成 `14168` 次检查，source-aware dominance 与 PackedBitSet 随机测试也通过。

仍有两个明确边界。第一，有限 penalty 不是严格 Phase-I：列变量和 artificial slack 都是连续变量，正 residual 可以小于任意固定经验阈值；当前代码仍会把 pricing 结束后的正 residual 转成 `INFEASIBLE`，所以 `50*incumbent` 是当前数据尺度下通过完整回归的工程修复，而不是任意实例上的不可行性定理。第二，strong trial 使用的是本次 `PC.solve()` 开始时保存的 incumbent；若同一节点随后由 RMIH 改进上界，trial penalty 仍按旧的较大 incumbent 计算。它不改变这次 `156580` 结果，但严格说不是“分支瞬间最新 incumbent”。此外必须维持 `50*incumbent < 0.5*Utility.big_M`；当前回归中 penalty 约 `7.83e6`，远低于 `5e9`，不会再次进入 PWLF BigM 区间。

### 2026-07-14 strong-on 回归耗时拆分

`173.493s/17 nodes` 的 CSV 字段容易低估 strong branching 成本：其中 `HeuristicPricing=29.250s/183` 只统计正式节点启发式，不含 `HeuristicPricing[strongBranching]=84.191s/529`。强分支 master LP 另占 `22.509s`，包括 lightweight phase-1 RMP `18.303s/320`、phase-2 initial LP `2.701s/64` 和 phase-2 heuristic 后重解 `1.505s/465`。因此可直接归属于 strong branching 的时间至少为 `106.700s`，占总时间约 `61.5%`；全部启发式 pricing 合计 `113.441s`，占约 `65.4%`。CSV 的 `master LP=24.064s` 中约 `93.5%` 也来自 strong trial，正式节点普通 master LP 仅约 `1.56s`。

ng-DSSR exact 不是本次瓶颈：正式 exact 为 `11.827s/57`，约占总时间 `6.8%`。其中 56 次带详细统计的调用共 `11.471s`，initialization 为 `11.008s`，completion bound 构造为 `10.729s`；也就是说 completion bound 占 exact 约九成，但只占整次求解约 `6.2%`。总时间扣除全部 pricing 和 master LP 后约剩 `23.7s`，与 root summary 中求解前后约 `24.4s` 的差值一致，主要是 ALNS seed、初始列及框架准备成本。当前实例若继续优化总时间，首要对象应是 strong phase-2 启发式调用次数/候选数，而不是 ng-DSSR exact 或普通 master LP。

### 2026-07-17 CPLEX Barrier 对照

当前 `LP.buildModel()` 原本只设置 `cplexThreads=1`，没有指定 `RootAlgorithm`，因此实际使用 CPLEX `Auto`。本次增加 `cplexRootAlgorithm=auto/barrier` 受控开关，默认仍为 `auto`；Barrier 保留 CPLEX 默认 crossover，不关闭 dual/basis 恢复，正式 pricing 仍按原流程读取 dual。

在 `wet050_002_2m` 上严格复用 `20260717-smalltime-seeds-v1-50-002-ng` 的 ng-DSSR 配置，只把 LP 算法从 Auto 改成 Barrier。Auto 的 root node 为 `243.263s`，`lp=120.881s/189`，`pricing=116.765s/174`，其中 heuristic `96.307s/149`、exact `20.457s/25`，root pool 为 `22308`。Barrier 的 root node 为 `384.674s`，`lp=176.578s/250`，`pricing=204.558s/263`，其中 heuristic `152.333s/210`、exact `52.224s/53`，root pool 为 `23653`。Barrier 的 40 次 phase-1 lightweight trial LP 合计 `73.712s`，平均 `1.843s`，并未体现 trial 加速；两组最终都选择相同分支弧 `(9,35)`，root LP 目标同为 `39364.5`，说明差异主要来自退化最优对偶改变了列生成路径，而不是模型目标或分支语义变化。

Barrier 在 root 已比 Auto 慢约 58.1%，且进入 child 后没有出现足以抵消该差距的迹象，因此在完成 root 和第一轮 strong branching 后停止，未继续浪费完整树资源。当前结论是保持 `auto` 默认；`barrier` 仅保留为后续特殊大 RMP 的诊断开关，不能作为 ng-DSSR/strong branching 默认算法。

### 2026-07-17 保留 trial seed 后的后续优化方向

当前不再缩减第一次 lightweight strong trial 的 seed，也不调整“父节点正值列无条件保留”的规则。原因是这部分直接承担 trial 初始可行性；继续压列虽然可能减小 RMP，但会重新混入 restricted columns 不足和 repair 语义问题。`branchSeedColumnLimit` 仍只用于 trial 已经求解并修复后的 phase2/正式 child seed 准备，不作为第一次 trial LP 的列数上限。

50-job 最新日志中，strong phase1 仍是最明确的大头：`wet050_002_2m` 为 `197.877s/120`，`wet050_003_2m` 为 `301.398s/320`。此前记录的 `strong_branching_light_repair_rmp_build=0.022/0.035s` 只计量 `LP.construct()` 的 Java 列表准备，不包含 `LP.solveRelaxation()` 内部的 `buildModel()`；后者每次都会新建 `IloCplex`、重新建立变量/覆盖行/机器行/分支行，再调用 `cplex.solve()`。因此当前只能确定“Java seed 装载不是大头”，还不能把 CPLEX 建模和优化求解分开归因。下一步最小诊断应在 `LP.solveRelaxation()` 内分别统计 `buildModel` 与 `cplex.solve`，不改变任何列集和分支语义。

如果 CPLEX 求解占主要部分，优先尝试父 LP basis warm-start。CPLEX 22.1 Java API 提供 `getBasisStatuses/setBasisStatuses`；trial 仍使用完全相同的 seed 和分支行，只把父模型中同 ID 列及公共约束的 basis 状态映射到新 trial 模型。由于 lightweight seed 可能删掉零值退化 basic 列，只有在父 basis 可完整映射时才导入，否则应自动退回当前求解，不能为了 basis 修改 seed。该方向不改变 trial 可行域和评分目标，风险明显低于继续筛列。

第二个独立 A/B 是“常规启发式 pricing 只用于 root，非根正式节点直接进入 ng-DSSR exact”，但 repair 的 `findFeasible()` 仍保留启发式优先，strong phase2 也由原开关单独控制。50-2 的非根启发式为 `95.483s/474`、加列 `18336`，非根 exact 为 `49.925s/110`；50-3 的非根启发式为 `130.259s/857`、加列 `34253`，非根 exact 为 `40.988s/227`。分支禁弧和 compact window 已让非根 exact 明显轻于 root，因此该静态策略有试验价值；但启发式仍贡献大量列，关闭后 exact 调用和列批量可能增加，必须做完整 A/B，不能直接设为默认。

第三个方向是 reliability/pseudo-cost strong branching：root 仍完整试探 20 个候选，后续节点对已有足够左右分支历史的同一 arc 使用归一化 pseudo-cost 估分，只对历史不足的候选继续 strong trial。它不改变单次 trial seed，但收益取决于候选 arc 在不同节点间的重复率。当前日志只稳定记录最终选中候选，尚不足以证明覆盖率；实现前应先记录每轮全部 20 个候选、左右 gain 和历史命中率。如果重复率低，该方案不会有实质收益。

当前优先级为：先拆分 CPLEX build/solve；若 solve 为主，测试 basis warm-start；并行做 root-only heuristic 的完整 A/B；reliability branching 先只做统计。直接把候选数从 20 降到 10 暂不采用，因为最新两例实际选中 rank 出现过 11、13、15、18、20，静态截断会明显改变分支质量。

### 2026-07-19：branch-implied 候选风险口径澄清

进一步按当前生产路径复核后，需要把前述“ArcBrancher 未显式跳过 branch-implied forbidden arc”的影响说得更准确。当前最好配置开启 strong branching、lightweight seed 和 branch-implied penalty。对可复用的 strong trial child，父节点正值竞争列虽然会临时保留，但从第一次 trial 建模起即按有限 M 成本处理；只有 repair 后 artificial slack 和正值 M 列都归零，trial seed 才会复用。随后筛列保留正值列，但此时正值列已不再包含 branch-implied 竞争弧，非正值不兼容列也会被过滤。因此，在 strong trial 成功并复用 seed 的主路径中，branch-implied arc 基本不会形成正式 child RMP 的正流量，更不会成为实际分支候选；`ArcBrancher` 缺少显式状态检查主要是一个不完整的候选生成不变量，不是当前主配置的性能瓶颈。

边界仍然存在于不走 strong trial 的普通 arc 分支：lightweight child seed 会保留父节点正值列，正式节点又不启用 strong-trial M 目标；在 set-covering 覆盖行下，required arc 行并不自动排除其它正值列中的竞争入弧/出弧。因此这种弱口径下，历史 branch-implied 竞争列可以继续留在 RMP，并可能产生分数 arc flow。当前结论是暂不为主配置单独优化这一点；若后续关闭 strong branching 或复测普通分支，应把 `Node.getArcState()==FREE` 且非 branch-implied forbidden 作为候选生成的显式前置条件。

### 2026-07-19：strong trial 限预算 repair 的后续设想

60-2 的困难 strong-trial repair 曾在一次 `FindFeasible` 中执行大量 DSSR 轮次。后续可以单独实验一种只服务 strong branching 评分的限预算 repair：第一种口径只运行启发式 repair；第二种仍调用 exact ng-DSSR，但限制最多执行若干 DSSR 轮。预算内修复成功时，仍按当前 trial bound 正常评分；预算耗尽且尚未修复时，可以给该 side 一个“试探阶段未修复”的劣化评分或伪不可行评分，以避免一个候选长期占用 strong branching 时间。

该状态不能复用现有真实 `INF` 语义。预算耗尽只说明有限试探没有找到修复列，不能证明完整 child 不可行，因此不能剪掉节点，也不能跳过正式 child 的完整 repair/exact pricing。若该候选最终被选中，正式入队时必须丢弃限预算 trial 的失败结论，按普通 child 流程重新做完整可行性修复。实现时应新增独立的 `UNRESOLVED_FOR_SCORE`（或等价）状态，并明确区分三类结果：已修复且可评分、已严格证明不可行、限预算未修复。当前只记录方案，暂不修改 strong branching 主线；先观察有效更新预算 K20 和 DSSR 内周期 Tmid 对 60-2 完整求解的实际改善。

### 2026-07-20：分支 child LP 可行性判定方法

当前 repair 已经属于人工变量驱动的 Phase-I 型列生成。普通 repair 只给本次新增分支行加 artificial slack；全行 domain-repair 才给覆盖、机器数及相关分支行统一加 slack。模型目标没有切成纯 Phase-I，而是保留真实列成本，并给 artificial slack 和 branch-implied 竞争列有限大惩罚。外层先解带 slack 的 RMP，再按当前 repair dual 依次调用启发式和 exact `findFeasible()` 补列并重解；只有 exact 定价覆盖完整列族、没有超时且最终 slack/penalty 列仍为正时，才能把 child 判为不可行。仅凭最初 restricted columns 上的 LP infeasible 不能证明完整列主问题 infeasible。

更标准的第一种替代是纯 Phase-I 列生成：目标只最小化 artificial slack 总量，slack 归零后再切回真实目标。它可避免真实成本和有限大惩罚共同塑造 repair dual，语义也最清楚，但并不会消除 pricing；每轮仍必须解完整 ng-DSSR/Farkas 等价子问题，Phase-I 最优值为正也只有在 exact pricing 闭合后才是不可能修复的证明。因此它可能改善 dual 方向和数值稳定性，但不能预期从根本上绕过当前30--64轮 DSSR。

第二种是 Farkas pricing。restricted child RMP infeasible 时，从 CPLEX 取得 Farkas dual ray，再求一个“是否存在违反该射线的合法列”的定价子问题；找到列则加入并重解，找不到且 exact certificate 完整时才证明全列主问题 infeasible。当前代码没有使用 Farkas ray。它的优势是无需人为选择 slack penalty，且定价方向直接针对恢复可行性；但子问题仍是带分支域的 elementary route pricing，仍可能需要 ng-DSSR/DSSR，不能假设一定比当前 artificial-slack 路径快。

第三种是单独解紧凑 LP 或 MIP。若紧凑模型与 child 的完整 route 域严格等价，则 MIP infeasible 可以安全剪掉 child，MIP feasible 还能直接给出一组可行列；但对每个 strong side 解一次完整 MIP 通常远重于当前 LP repair，而且它判断的是整数可行性，不是用于 strong score 的 master-LP 可行性。当前 relaxed time-indexed 图允许非基本/重复访问 pseudo-route，它的可行不能证明 elementary route master 可行；只有该放松模型也 infeasible 时，才能作为单向不可行证据。由此，紧凑 LP/MIP 更适合作为困难 side 的偶发 fallback 或快速充分/必要条件，而不适合直接替代每个 trial 的 Phase-I 列生成。

分支冲突、required arc 时间可达性、required/forbidden 链冲突、机器数上下界等组合检查可以在建 LP 前排除一部分显然不可行 side，但这些只是必要条件预处理，无法覆盖多机器、覆盖约束和时间窗耦合下的完整可行性。当前阶段只形成分析结论，不修改 repair 实现。

### 2026-07-20：arc 左右支 repair 与有限 M 列流程

当前 lightweight strong trial 先从父 restricted RMP 构造 child seed：父节点正值机器列无条件保留，其他列只保留 child-compatible 列。左支增加显式 forbidden arc 行 `sum(lambda using i->j)=0`；父正值列中使用该弧的列虽被临时保留，但不属于 branch-implied M 列，第一次 LP 由显式等式直接迫使其取0。若因此 restricted LP infeasible，repair mode 只给这条 forbidden 行增加一个有方向的 artificial slack，再按当前 repair dual 补列。

右支增加显式 required arc 行 `sum(lambda using i->j)=1`，并将所有 `i->k(k!=j)`、`h->j(h!=i)` 记为 branch-implied forbidden arc。lightweight seed 会保留父节点正值竞争列以维持 repair 起点；从第一次 trial LP 建模开始，只要 `enableStrongBranchingBranchImpliedPenalty=true`，这些列的 objective coefficient 就由真实成本替换为 `50*incumbent` 的有限 repair penalty。它们不是等到 LP infeasible 后才加 M，也没有修改全局 pool 中的真实列成本。非正值且 child-incompatible 的竞争列在 lightweight seed 阶段已经过滤掉。

第一次 trial LP 有两种情况进入 repair：LP 本身 infeasible，或者 LP feasible 但仍有正值 penalty 列。repair mode 随后只给当前 required arc 行加一个 artificial slack；同一个有限 penalty 同时作用于 slack 和历史竞争列。外层依次调用启发式 `findFeasible()` 和 exact ng-DSSR `findFeasible()`，每次加列后重解当前 repair LP。pricing 按 child 域生成的新列正常不应再含 branch-implied 竞争弧；如果历史或异常新列仍不兼容，建模时仍按 penalty 成本处理。repair 成功的必要条件是 artificial slack 与正值 penalty 列同时归零，随后才按 reduced cost 筛选后续 seed。两者仍有任一正值且 exact 已耗尽时，当前实现把该 side 判为 infeasible；exact certified dual bound 达到 incumbent 时则用独立 `dual_bound_pruned` 状态按 INF 评分，不与结构 infeasible 混同。

60-2 完整日志中，36 次 exact repair 有33次来自右支 required arc，累计637.017s，占 repair exact 总时间83.6%；左支 forbidden arc只有3次，但平均41.760s，高于右支平均19.304s。正确表述应是“右支因触发频率高而构成总瓶颈”，而不是“右支每次都比左支难”。

### 2026-07-20：先用固定初始 ng-set 清除 slack/M 的可行性分析

讨论了一种 strong repair 分层方案：先不执行 DSSR，只用初始 ng-set 做一轮 relaxed pricing；若该轮找到负的 elementary 列，就加入 repair RMP 并重解，重复到 artificial slack 和 branch-implied penalty 列都归零；若仍存在负的 non-elementary witness，再进入完整 DSSR。该方案在语义上安全，前提是固定 ng-set 阶段失败时必须回退完整 DSSR，不能把“没有 elementary 列”当作 child infeasible。

当前实现事实上已经包含这一流程的核心。`ng-DSSR.findFeasible()` 每次先用当前初始 ng-set 执行第一轮 `solveRelaxedRound()`：只要找到 elementary 负列就立即返回，不更新 ng-set；外层 repair 加列、重解 RMP，并在 slack/M 仍为正时再次调用 pricing。只有第一轮没有 elementary 负列、却存在负的 non-elementary route 时，才更新 ng-set并继续 DSSR。因此另加一个“固定初始 ng-set pre-repair engine”会重复现有第一轮，不能减少已有工作。

困难 repair 的实测也说明了这个边界。60-2 node 2 的一次50轮 `FindFeasible` 中，第一轮出现 `neSeen=7022, neStored=1000, elem=0`；直到第50轮才返回63条 elementary 负列。non-elementary route 不能作为合法 RMP 列加入，第一轮没有任何列可用于重解，所以反复执行相同固定 ng-set pricing 只会得到相同 RMP、相同 dual 和相同 relaxed witness，不会把 slack/M 推到0。DSSR 在这里不是多余闭合步骤，而是排除这些 relaxed witness、暴露合法 elementary 修复列的必要过程。

另一个必须区分的点是：RMP 中不会存放 ng-DSSR 的 non-elementary route；RMP 只含合法 elementary 列、人工 slack，以及 strong trial 暂时按 penalty 成本处理的历史竞争列。是否需要 DSSR 应检查本次 pricing 的 non-elementary witness，而不是检查 RMP 是否有“非基本列”。如果 slack/M 已归零后还要继续做正式 exact pricing，应先关闭 repair/penalty 口径并重解一次干净 RMP；人工变量即使当前取0，repair 模型的 dual 也不等同于无人工变量、真实目标下的正式 dual。

因此当前不建议实现重复的固定-ng前置层。若目标是从根本上减轻M dual 对 completion bound 的破坏，更直接的后续实验是独立纯 Phase-I：第一段只最小化 artificial slack/竞争列使用量，合法真实列在 Phase-I 中成本为0；归零后重建真实目标 RMP，再做普通 pricing。这个方案能把“恢复可行性”和“优化真实目标”分开，但需要 pricing/completion bound 明确支持 Phase-I reduced-cost 口径，改动明显大于新增一个前置 engine，而且仍不能保证消除需要 DSSR/Farkas exact pricing 的困难 repair。

### 2026-07-20 纯 Phase-I strong repair 实现与 A/B

按讨论新增了独立的 strong-trial repair 实验路径，开关为 `enableStrongBranchingPhaseOneRepair`，底层默认关闭。这里没有机器固定成本。Phase-I 只改变 RMP 和 pricing 的目标口径：branch row artificial slack 与 branch-implied 竞争列的系数为 1，其余合法内部列、列化外包列以及直接外包 tariff 项的系数均为 0；机器数、覆盖、分支和 cut 等约束仍保留，因此相应 dual 仍正常进入 reduced cost。内部 pricing 将 setup cost 和任务惩罚函数置零，外包 pricing 同样使用零列成本。Pool 始终保存 evaluator 得到的真实列成本，没有覆盖或临时改写，因此不需要额外保存成本快照。

Phase-I 初始 LP 或后续 repair pricing 一旦使 artificial slack 和正值竞争列同时归零，就立即停止 Phase-I。此时先删除 restricted set 中的 branch-implied 竞争列，再关闭 repair/Phase-I 模式，按 Pool 中真实成本重建并求解一次 RMP；这次结果才作为 strong trial bound，并进入原有 seed 筛选流程。如果 Phase-I 目标仍为正，只有 ng-DSSR exact 已证明内部列族无负 reduced-cost，且列化外包时 OutsourcingPricingEngine 也在同一 dual 下给出无负列证书，才返回真实 infeasible；证书不完整属于 engine/配置契约错误，直接中止并暴露，不切换到旧 repair，也不把它误判为 child infeasible。Phase-I 中关闭 dual profitable window、subtree arc fixing 和 observed dual-bound pruning，避免把临时 0/1 目标下的证据写回正式搜索。

在 `wet040_001_2m` 上进行了严格同配置 A/B，唯一差异是该开关。旧 repair 结果为 `obj=bound=22580`、`valid=true`、16 nodes、总时间 `129.257s`；纯 Phase-I 同样为 `obj=bound=22580`、`valid=true`、16 nodes、总时间 `164.357s`。新路径将 repair exact ng-DSSR 启动次数从 24 降到 4，下降 83.3%；repair after-pricing RMP 求解从 83 次降到 40 次，但额外发生 22 次 Phase-I 归零后的真实 RMP 求解。最终 master LP 时间由 `63.415s` 增至 `87.702s`，启发式时间由 `26.407s` 增至 `33.330s`，总时间增加 `35.100s`，约慢 27.2%；列池由 45,614 降至 40,672。

结论是该设计在语义上成立，也确实消除了有限 M 对 dual/PWLF 的尺度污染，并显著减少困难 repair 内的 exact DSSR；但当前算例中零成本 Phase-I 的退化 LP 和恢复真实目标后的额外重解超过了这部分收益。代码保留作后续 A/B 和困难大 M 实例验证，默认继续使用旧 repair。实验目录分别为 `test-results/bpc/ab-strong-phase1-40-2-new-20260720b` 和 `test-results/bpc/ab-strong-phase1-40-2-old-20260720c`。
#### Phase-I A/B 时间归因

进一步按日志累计器拆分后，新方案的直接收益其实很小。repair exact `FindFeasible` 从 `0.679s/24` 降到 `0.017s/4`，只节省 `0.662s`；repair after-pricing LP 从 `0.309s/83` 降到 `0.153s/40`，再节省 `0.156s`。虽然 exact 启动次数下降83.3%，但40-2上的旧 repair exact 本来平均只有28.3ms，因此次数变化看起来很大，绝对收益合计不足1秒。

慢项主要来自 master。旧方案 master LP 共 `63.415s`，新方案为 `87.702s`，增加 `24.287s`，解释总退化35.100s的69.2%。其中相同320次 `strong_branching_light_repair_rmp` 从 `56.627s` 增至 `75.015s`，单次平均由176.96ms增至234.42ms，增加 `18.388s`，是最大单项。纯 Phase-I 自身的 `initial + after_pricing + true_rmp` 为 `2.351+0.153+3.476=5.980s`，旧 repair 的 `slack_initial + after_pricing` 为 `1.575+0.309=1.884s`，直接 repair LP 多 `4.096s`；其中22次恢复真实目标后的 RMP 重解单独占3.476s。

pricing 也因列集和后续 dual 轨迹改变而变重。普通 HeuristicPricing 从26.407s增至33.330s，增加6.923s；普通 exact 从7.103s增至8.151s，增加1.048s。repair pricing 本身反而由 `0.773+0.679=1.452s` 降至 `0.941+0.017=0.958s`，节省0.494s。新方案列池从45614降到40672，但 restricted seed/basis 更退化，后续相同数量的 strong trial LP 反而更慢；这属于 Phase-I 改变列和 dual 轨迹后的间接成本，不是 Phase-I 内部计时本身。扣除 master 和全部 pricing 后，其余框架时间还增加约3.336s。

因此当前结论不能表述为“Phase-I repair 快但被一次重建抵消”。准确说法是：它有效消除了 exact repair，但该实例的 exact repair 原本并不贵；新增真实 RMP 重解、零目标退化以及由不同 seed/basis 引起的后续 strong-trial LP 和普通 pricing 退化共同造成总时间增加。若要继续评估，应放到旧 repair exact 为数十秒的困难 child 上单独 A/B，而不是依据40-2推断其对60-2长尾无效。
#### `strong_branching_light_repair_rmp` 差异的口径修正

`strong_branching_light_repair_rmp` 是每个 strong side 进入 repair 之前的初始 child RMP，不使用纯 Phase-I 的0/1目标。本次两组都执行320次，只有24个 side 随后进入 repair。因此其 `56.627s -> 75.015s` 不能直接解释为“Phase-I零目标使这一步变慢”。Phase-I 确实会从第一次 repair 开始改变全局 Pool、后续 trial seed 的列组成和 basis 退化程度，因而可能间接影响后面的初始 trial；但现有累计日志没有记录“首次 Phase-I 前后”的逐 call 时间，不能把18.388s全部归因于该间接效应。

本次还存在明显运行级速度差异：普通 HeuristicPricing 平均调用时间由60.428ms增至77.873ms，慢28.9%；lightweight trial LP平均由176.960ms增至234.423ms，慢32.5%；多个相同node、相同restricted列数和相同LP目标的累计LP时间也普遍慢约27%--64%。这组一致放慢说明机器负载、JVM/CPLEX运行状态或缓存条件很可能贡献了大部分差异。当前只能确认纯Phase-I直接阶段比旧repair多4.096s，不能确认总时间慢27.2%都是算法退化。严格判断需要在相同环境做 old-new-old 或 new-old-new 交错复跑，并增加逐side初始LP计时，区分首次Phase-I之前和之后。
#### Phase-I residual 与 certificate 检查时机

Phase-I 每次初始求解或加列重解后都会通过 `needsStrongRepair()` 检查 artificial slack 和正值 branch-implied 竞争列是否仍存在；任一仍为正就继续 repair，二者都归零则立即成功，不再要求 exact 闭合。原因是 Phase-I 目标是非负人工项之和，已经找到目标0就已达到全局最小值。certificate 不是每次重解都用于判 infeasible：只有一整个 pricing pass 没有新增任何列、residual仍为正时，才检查当前同一dual下的内部exact证书，以及列化外包时的外包exact证书；两类均非负才判child infeasible；缺任一证书直接抛出状态错误。恢复真实成本RMP只发生在 `phaseFeasible=true` 的路径；certificate infeasible 和 time limit 都不恢复。
#### Phase-I 闭合契约与退化优化优先级

“证书不完整”不是可恢复的算法结果，而是当前 pricing engine/配置没有履行 Phase-I 闭合契约。本次40-2 A/B中该状态出现0次。可能原因包括：pricing链没有支持Phase-I目标的内部exact engine；exact因诊断/非闭合返回而没有给出有限nonnegative certificate；列化外包缺少同dual的外包certificate；或生成结果因重复/数值过滤未真正进入RMP而无法形成完整闭合证明。出现时直接中止并暴露；time limit单独返回，启发式返回空永远不算证书。 全局检查没有发现 exact BPC 主线中的第二处同类静默算法回退；completion-bound 的 scalar fallback 是预判不足时继续完整函数判断，RMIH 的 min-loss fallback 是整数启发式补列，均不改变 exact 证书语义。

若交错复跑确认纯Phase-I确有算法退化，第一优先级应处理0/1目标本身形成的大替代最优面，例如给Phase-I启发式设置更小且多样化的专用返回批量；当前日志常一次加入300/600甚至更多只服务可行性的列，容易扩大退化面。Phase-I归零后原地恢复真实目标并复用CPLEX basis只能减少模型重建成本，属于第二优先级。不能用普通有限epsilon真实成本作为主目标tie-break后直接证明infeasible，因为连续slack可任意小；严格词典序需要额外模型/dual口径。长期更干净但改动更大的方向是固定竞争列后使用Farkas pricing恢复RMP可行性。

### 2026-07-20 50-2 root-only Phase-I strong branching 观察

使用 `wet050_001_2m`、no-SRI ng-DSSR、strong phase1 candidate=20、phase2=0、lightweight seed、Phase-I repair 开启，并设置 `maxNodes=1`，只完成 root 定价和 root 强分支。结果 root bound=44353、incumbent=44383、gap=0.0676%，root nodeTime=223.141s，总时间283.360s（包含约60s ALNS）。root 常规定价为151.322s，其中启发式126.054s/223次、ng-DSSR exact 25.269s/40次。

强分支测试20个候选的左右两侧，共40次 `strong_branching_light_repair_rmp`，耗时62.200s，平均1.555s/side；seed准备仅0.225s。父Pool为19044列，lightweight后每个side仍保留3124--11252列，平均8040.2列。因此当前强分支瓶颈是大 restricted master LP，而不是建模或seed筛选。40个side全部在初始trial LP即得到可行结果，没有进入 `strong_branching_phase_one_*`，Phase-I repair启动次数为0；故该实例只能评价强分支LP成本，不能评价0/1 Phase-I repair质量，开关开/关在本轮应走相同算法路径。最终选择 `arc(10,5)`，候选按距0.5排名为16/77，左右bound分别44377.0和44647.30。

实验目录：`test-results/bpc/ab-strong-phase1-50-2-rootonly-20260720`。
