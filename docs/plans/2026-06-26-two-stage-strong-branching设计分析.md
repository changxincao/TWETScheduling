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
## 2026-06-27 后续暂缓方案记录

60-2 测试中观察到，strong branching 的 Phase 1 如果 child 初始 RMP 不可行，会进入 repair，而 repair 内部可能调用 exact pricing。对大规模或难节点，这会让 Phase 1 成本明显变高。一个可尝试方向是：Phase 1 repair 只允许启发式 pricing 补列，用更便宜的近似可行性和近似 bound 做候选筛选；但这会削弱 infeasible 判断的可信度，也会和当前“Phase 1 不可行就不进入 Phase 2 / 不入队”的流程冲突。当前先不实现，只记录为后续实验方案；若要做，需要单独定义“启发式 repair 未修好”是弱不可行、低质量候选，还是继续交给正式 child 再修复。

另一个可尝试方向是改进入队 child 的 `pseudoCost`。理论上，若 Phase 1 repair 中执行过 exact pricing，并且能够得到当前 dual 下的有效 dual bound，可以用该 dual bound 与父节点 bound 取更强值，作为入队排序的下界估计。但当前 strong branching 最终入队通常使用 Phase 2 后的 child seed，而 Phase 2 只跑启发式 pricing，不产生 exact pricing 证书；能拿到 dual bound 的场景主要出现在 repair 期间，覆盖面有限，实际帮助可能不大。当前仍保持保守做法：trial bound 只用于选择分支，不写入正式 child `pseudoCost`；后续如果发现队列排序明显受父节点 bound 过松影响，再考虑加入这一优化。
