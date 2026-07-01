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
