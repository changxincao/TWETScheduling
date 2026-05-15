# PiecewiseLinearFunction 测试分析与 BPC 定价使用约束记录

## 1. 问题背景

这份记录合并了原来的 `2026-05-13-PiecewiseLinearFunction测试分析记录.md` 和 `2026-05-14-BPC定价中分段函数使用约束.md`。这两个文档讨论的是同一件事：当前 `Common.PiecewiseLinearFunction` 这套分段线性函数操作，在启发式路径评价里哪些地方是可靠的，后续接入 BPC pricing 时又必须加哪些输入约束。

当前主要判断是：`PiecewiseLinearFunction` 不是一个通用的分段线性函数库，它更像是为当前 TWET 启发式和未来 forward labeling 定价准备的高效函数操作器。只要输入函数满足我们讨论过的结构约束，它可以继续用；如果把它当作任意定义域、任意 gap、任意单点跳变都能处理的通用库，就会出问题。

## 2. 当前主要结论

第一，后续 BPC pricing 中的 forward label 函数应统一保持 `[a,T]` 结构。这里 `a` 可以随 label 不同而变化，但右端必须同为全局最大时间 `T`。这样做的核心好处是避免在函数内部产生多个离散单点或跳变点；即使出现单点退化，也只可能发生在全局 `T` 处，可以在 pricing 层提前处理。

第二，`mergeMinimum` 只能按 forward label frontier 的语义使用，不能作为通用下包络合并器。它的有效输入应满足：两个函数右端同为 `T`，存在正长度公共区间，并且输入函数本身不退化为 `[T,T]` 单点。在这个约束下，`mergeMinimum` 的专项测试通过；如果完全不相交、右端错位、或只在一个点相交，就不属于当前允许的输入。

第三，退化为 `[T,T]` 的 label 不进入普通 `mergeMinimum`、dominance 或集合包络流程。后续 pricing 中如果遇到这种 label，应直接尝试连接终点，计算完整列的 reduced cost；如果能得到负 reduced cost，就输出列，否则丢弃或停止扩展。这样可以避免为了单点维护零长度 segment，把 `add/shift/dominance/merge` 全部复杂化。

第四，`Utility.curUpperBound` 在启发式阶段可以作为动态 big-M 使用，但 pricing 阶段必须谨慎。启发式里它代表当前最好解成本，用来减少明显不可能改进的函数段是合理的；pricing 里函数值是 reduced cost，后续扩展可能被 dual 或 cut dual 拉低，因此进入 exact pricing 前应调用 `Utility.resetCurUpperBound(Utility.big_M)`，不要继续用启发式上界截断分段函数。

第五，`normalize()` 当前只能理解为 forward normalize。它会合并相邻同线段、处理首尾 `big_M` 段，并调用 `minimizePrefixInPlace()`。因此它不适合直接用于 backward 函数，也不适合需要严格保留 horizontal gap 的通用 partial dominance 场景。

## 3. 测试与函数现状

最早的完整属性测试覆盖了 `shiftX`、`add`、`minimizePrefixInPlace`、`minimizeSuffixInPlace`、`findMinimal`、`dominates`、`mergeMinimum` 和 `updateDominatedIntervals`。这些测试说明，当前函数类不是任意输入都能稳健处理的通用实现；但在我们后续限定的 forward pricing 输入前提下，部分核心函数已经能通过专项测试。

`findMinimal` 已经修正为扫描每段 `start` 和按当前段计算的 `end` 值。旧实现只看每段 `start`，对连续函数通常没问题，因为上一段右端左极限等于下一段起点值；但如果 merge、占优或裁剪导致 vertical gap，旧实现可能漏掉端点左极限。修正后，普通多段、竖直跳变左极限、左右最优位置选择这些用例已经通过。需要注意的是，如果未来出现“先 `findMinimal`，再对同一对象原地修改函数，再次 `findMinimal`”的用法，必须在原地修改后调用 `resetMinimum()`。

`mergeMinimum` 在 `[a,T]`、右端同为 `T`、存在正长度公共区间的输入契约下通过了专项测试。当前 `merge-find-contract` 模式结果为 `passed=5, warnings=0, failed=0`。原完整测试中仍存在的失败，主要来自无效 merge 输入、`minimizeSuffixInPlace` 和 `dominates` 的语义风险，不应混同为 `mergeMinimum` 在当前契约下失败。

`updateDominatedIntervals` 当前倾向于不物理删除被占优区间，而是把区间替换为 `big_M` 水平段。这样链表仍然连续，`add` 仍可在“输入内部无物理 gap”的假设下使用。经过后续讨论，这个方向对 forward frontier 语义是可以解释通的：forward 函数表示“不晚于 t 完成时的最小成本”，所以某段被打成 `big_M` 后，如果更早时间已经有有效值，`normalize()/minimizePrefixInPlace()` 把更早的有限值延伸到该段，并不一定是错误，而是符合“不晚于 t”的闭包语义。

因此 `updateDominatedIntervals` 暂时不应再作为 partial dominance 的主要风险反复分析。真正的前提是，pricing 阶段不能继续使用启发式 `curUpperBound` 作为截断上界，而应先把 `Utility.curUpperBound` 重置为 `Utility.big_M`。否则 reduced cost 后续可能被 dual 项拉低，提前用启发式上界把某段压平或截断，就可能破坏精确定价。也就是说，partial dominance 的主要风险更应该放在“哪些 label 有资格支配、定义域和资源状态是否满足支配关系”上，而不是 `updateDominatedIntervals` 当前这种“打 big_M + forward normalize”的函数操作本身。

`add` 在当前假设下没有暴露主要问题。它要求两个输入在公共定义域上按连续 segment 扫描相加；如果未来把 dominance 改成物理删除区间，让函数内部出现断裂，那么 `add` 不能直接复用。当前我们选择用 `big_M` 段保留连续链表，就是为了先避免这个问题。

`evaluate` 当前不需要单独修改。它主要在启发式三段拼接中使用：先由 `findMinimal` 确定时间点，再回代求值。若时间点超出定义域，返回 `Utility.curUpperBound` 可以理解为该拼接不可行或足够差。pricing 中只要先把 `curUpperBound` 重置为 `Utility.big_M`，这个行为也基本等价于返回不可行大数。需要避免的是把这种返回值拿来做未经证明的 partial label 剪枝。

当前仍需要重点处理的是 `minimizeSuffixInPlace` 和 `dominates`。`minimizeSuffixInPlace` 在某些尾部递减段场景下会漏掉应保留的尾部常数最小值，导致查询得到 `Utility.curUpperBound`；如果后续继续依赖 backward 函数，这是优先修复项。`dominates` 的主要风险是定义域覆盖不严格，支配函数右端较短时仍可能返回 `true`；如果后续作为 BPC label dominance 使用，需要重新写严格版本，至少明确完整定义域覆盖和“至少一点严格更优”的语义。

## 4. 右端定义域与调试检查

这里要区分 `tail.end` 和 `domainEnd`。`tail.end` 是当前 segment 链表真实覆盖到的最右端，`domainEnd` 是函数对象保存的元数据。正常情况下二者应该一致；但 `setDomain/trimToDomain` 可能把链表裁到 `end<T`，随后又把 `domainEnd` 恢复成原来的 `T`。因此检查 `tail.end == domainEnd` 不是重复检查，而是在定位“实际函数已经被裁短、但元数据还像是完整 `[a,T]`”的情况。

更准确地说，在后续 forward pricing 约定的输入流里，如果每个输入函数都满足 `tail.end == domainEnd == T`，并且函数操作不主动调用 `setDomain(..., end<T)`，那么 `add`、`mergeMinimum/mergeMinimum2`、`normalize` 和正向 `shiftX` 都不应主动破坏这个关系。`add` 的结果右端取两个输入实际右端的公共部分，两个输入都是 `T` 时结果仍到 `T`；`mergeMinimum` 在有效输入契约下也是两个函数右端同为 `T`；`normalize` 已经改成不删除右侧 `big_M` 尾段；正向 `shiftX` 平移后超出 `T` 的部分会被裁回 `T`。

真正会主动制造 `tail.end != domainEnd` 的，是 `setDomain(start,end)` 且 `end < 原 domainEnd` 的情况。这个函数会先按较小的 `end` 裁短真实 segment 链表，然后又调用 `resetDomain(this.domainStart,this.domainEnd)` 把元数据恢复成原来的右端。也就是说，`setDomain` 当前不是“永久缩小函数对象账面定义域”，而是“生成一个实际链表被裁过、但元数据仍保留原定义域”的副本。后续 BPC pricing 若要保持 `[a,T]` 结构，应避免在 label 主流程中使用 `setDomain(..., end<T)`。

因此现在的 debug 判断是有意义的。我们真正要识别的不是 `domainEnd` 这个字段本身有没有变化，而是“函数真实 segment 链表的右端是否已经不再到全局 `T`”。在正常 forward pricing 流里，`domainEnd` 应作为账面上的全局右端 `T` 保持不变；如果某个操作导致 `tail.end != domainEnd`，就说明实际定义域右端已经短于账面右端，后续 `add/merge/dominance` 继续按 `[a,T]` 语义使用就可能出问题。这个检查可以准确抓住“过程中实际右端被裁短”这一类风险。

当前 `Configure.debugPWLFDomainCheck` 是分段函数右端定义域检查的统一开关，默认关闭，不改变现有求解流程。打开后，`setDomain`、`shiftX`、`add`、`minimizePrefixInPlace`、`minimizeSuffixInPlace`、`updateDominatedIntervals`、`normalize`、`mergeMinimum` 和 `mergeMinimum2` 的关键入口或出口会检查函数实际右端是否仍等于 `domainEnd`。

对 `mergeMinimum/mergeMinimum2`，现在额外接入了 `Utility.debugCheckPWLFMergeContract(...)`。它检查的是后续 pricing 必须遵守的输入契约：两个函数是否有正长度交集、右端是否一致、左函数是否退化成单点、右函数是否退化成单点。发现异常时会在 `Utility.debugMap` 中记录计数，并向 `System.err` 输出操作名、左右区间、公共区间、异常布尔值和两个函数对象 id。这个输出可以先定位是哪类操作、哪一对函数破坏了 `[a,T]` 结构；如果后续要定位到具体 label、节点或列，需要在 BPC 层继续传入业务 id。

## 5. 后续写 pricing 的默认流程

后续进入精确算法时，建议流程是：先运行启发式得到 incumbent 解和初始列；把这些列显式加入 BPC 的列池，并把 incumbent 目标值存到 BPC 自己的状态里；随后把 `Utility.curUpperBound` 重置为 `Utility.big_M`，再启动 pricing。pricing 中 label 的函数操作默认不使用启发式动态上界剪枝。

第一版 pricing 应优先保证函数结构简单、语义一致。也就是先坚持 `[a,T]` 右端统一、单点 label 直接收尾、全段 dominance 优先、partial dominance 暂缓。等主流程跑通后，再考虑用安全的 completion lower bound、dominance graph 或更细的数据结构压缩 label 数和函数段数。

## 6. 修改记录

2026-05-14：修正了 `mergeMinimum` 中截取 `g` 左侧前缀时的 `prevGH` 更新顺序。`SegmentPool.obtain(gh.start, start, gh.slope, gh.intercept)` 会生成一段新的截取片段，并不是直接截短原 `gh`。因此 `prevGH` 必须记录“刚刚跳过的前一段”，再移动 `gh=gh.next`；原来先移动 `gh` 再赋值 `prevGH`，会把前驱记成下一段，存在拼链错位风险。

2026-05-14：给 `PiecewiseLinearFunctionPropertyTest` 增加了 `merge-find-contract` 测试模式，只检查 `findMinimal` 和满足 `[a,T]`、右端同为 `T`、存在正长度公共区间的 `mergeMinimum` 输入。该模式下当前结果为 `passed=5, warnings=0, failed=0`。完整测试仍为 `passed=10, warnings=3, failed=16`，剩余失败属于既有风险，不是本次调试检查接入引入的新变化。

2026-05-14：把 `mergeMinimum/mergeMinimum2` 接入 `Utility.debugCheckPWLFMergeContract(...)`，并在 `Utility.debugCheckPWLFRightBound(...)` 附近补充中文注释，说明 `tail.end` 与 `domainEnd` 的区别。该检查默认关闭，仅用于后续接入 pricing 时定位是否有操作破坏 `[a,T]` 约束。

2026-05-14：进一步分析了 `minimizeSuffixInPlace()` 和 backward 函数定义域。当前 backward 函数构造时基本不提前设置较窄 domain：一维 backward 的末任务直接使用 `data.penaltyFunction[job].copy()`，递推时执行 `shiftX(-s[job][next]-p[next]) + add(data.penaltyFunction[job])`，随后调用 `minimizeSuffixInPlace()`；二维、三维 backward 辅助函数也是同一思路。`Move.getCost()` 中的 `setDomain(...)` 主要是在已经有一个具体前驱完成时间后，回推具体 completion 时裁左端，不是 backward 函数构造阶段的定义域约束。因此当前 backward 定义域问题不在“提前裁窄”，而在 `minimizeSuffixInPlace()` 是否正确把后缀最小值物化出来。

`minimizeSuffixInPlace()` 的语义应是把函数 `f(t)` 转成 `g(t)=min_{u>=t} f(u)`。当前实现从右向左扫描，用 `runningMin` 记录右侧已知最小值，用 `lastT` 记录右侧尚未接回新链表的边界。问题出在递增段发生穿越时，代码用 `if (!Utility.compareEq(lastT, tail.end) && !Utility.compareEq(runningMin, Utility.curUpperBound))` 控制是否补一段 `[tCross,lastT]` 的常数最小值。这个判断是从 prefix 最小化思路搬过来的，但对 suffix 不是严格对称的：当右侧最小值第一次来自尾部区间，`lastT` 仍然等于原 `tail.end`，该判断会跳过本来应该保留的尾部常数段，导致新函数链表缺少右侧一段，后续 `evaluate` 或 `findMinimal` 在这段上只能得到 `Utility.curUpperBound`。因此，`minimizeSuffixInPlace()` 仍是 backward 函数优先修复项；修复时应重点保证尾部最小值段被显式加入，而不是简单套用 prefix 的首端判断。

从代码注释和 prefix 版本对照看，这个判断最初大概率是为了避免在边界处插入“人工上界段”。`minimizePrefixInPlace()` 从左向右扫时，`runningMin` 初始为 `Utility.curUpperBound`，如果第一段一开始就高于上界，直接把 `[head.start,tCross]` 写成 `curUpperBound` 会相当于人为用当前上界替换真实函数值。这个处理在启发式里可以理解成剪掉明显劣段，但容易制造边界假段，所以当 `prevT == head.start` 时被跳过。suffix 版本后来按对称思路加了 `lastT != tail.end`，想避免在右边界写入类似的人工上界段；但 suffix 的右侧最小值可能首先来自真实尾部函数，而不是人工 `curUpperBound`。因此这个边界判断在 suffix 里会误伤真实尾部最小值段，这是当前错误来源。

进一步按问题结构分析，如果完全不发生 label merge 和 partial dominance，单个 job 的惩罚函数是凸函数，shift 保持凸性，凸函数相加仍为凸函数；对凸函数做 prefix minimum 会得到凸的单调不增函数，对凸函数做 suffix minimum 会得到凸的单调不减函数。因此在纯扩展链条里，`minimizePrefixInPlace()` 和 `minimizeSuffixInPlace()` 的边界误删场景基本不会触发，最多只是用 `curUpperBound` 剪掉边界劣段。真正需要小心的是 merge 和 partial dominance：两个凸函数取下包络不一定凸，partial dominance 把一段打成 `big_M` 也会破坏普通凸性。即使随后做了 prefix/suffix minimize，结果只是保证单调包络，不一定仍满足下一轮“单调函数 + 凸函数”之后的凸性或单峰性。因此，仅凭“minimize 后 forward 单调递减、backward 单调递增”不能完全证明下一轮边界补段逻辑安全；如果后续 BPC pricing 要大量使用 merge 或 partial dominance，最好仍把 prefix/suffix 的边界判断改成显式依据 `runningMin` 是否等于 `Utility.curUpperBound`，而不是只看 `prevT == head.start` 或 `lastT == tail.end`。

本次实际修正采用的就是这个判断。旧逻辑的问题是把“贴着定义域边界”近似当成“这段只是 `curUpperBound` 形成的人工上界段”。这个近似在很多启发式场景里有效，因为边界高于当前上界的段本来就不会改进当前解，跳过可以减少 segment；但它不是严格语义。真正应该跳过的是 `runningMin == Utility.curUpperBound` 的人工段，而不是所有贴着 `head.start` 或 `tail.end` 的段。如果 `runningMin != Utility.curUpperBound`，说明这个最小值已经来自真实函数值，即使它正好贴着左边界或右边界，也必须补回对应的常数最小值段。

具体到可能出错的场景，forward 的 `minimizePrefixInPlace()` 在以下情况下会漏段：左侧已经存在一个真实较小值，后面某段因为新 job 成本的 due window 右侧递增而变高，prefix 本应把左侧真实最小值延伸成 `[prevT,tCross]` 常数段，但此时如果 `prevT == head.start`，旧逻辑会误以为它是上界假段并跳过。这个场景在纯凸扩展里不常见，但如果前一轮函数经过 merge、partial dominance 或裁剪，累计函数开头正好落在某个 job 成本函数 due window 右侧递增部分，就可能出现。backward 的 `minimizeSuffixInPlace()` 是对称风险：右侧已经存在一个真实较小值，左侧某段变高，suffix 本应把右侧真实最小值延伸成 `[tCross,lastT]` 常数段，但旧逻辑会因为 `lastT == tail.end` 跳过。修正后，两个函数都只在 `runningMin` 仍等于 `Utility.curUpperBound` 时跳过边界段；真实最小值段不再因为贴边界被漏掉。

修正后重新运行 PWLF 测试：`merge-find-contract` 模式仍为 `passed=5, warnings=0, failed=0`；完整属性测试变为 `passed=13, warnings=1, failed=3`。其中 `minimizePrefixInPlace` 和 `minimizeSuffixInPlace` 的普通连续/非凸测试均已通过，之前 suffix 漏掉尾部常数最小值的问题被覆盖。剩余 3 个失败仍是既有的无效输入或严格语义问题：`dominates` 的定义域覆盖不严格，`mergeMinimum` 对完全不相交定义域不适用；这不属于本次 prefix/suffix 修正范围。

2026-05-15 又补了两个显式回归测试：`minimizePrefixInPlace: boundary real-minimum regression` 构造左边界真实最小值需要向右延伸的情况；`minimizeSuffixInPlace: boundary real-minimum regression` 构造右边界真实最小值需要向左延伸的情况。这两个用例直接覆盖旧逻辑中 `prevT == head.start` 和 `lastT == tail.end` 误跳过真实最小值段的问题。重新运行后，`merge-find-contract` 仍为 `passed=5, warnings=0, failed=0`，完整属性测试为 `passed=15, warnings=1, failed=3`。新增的两个边界用例均通过，剩余失败仍是 `dominates` 定义域覆盖和 `mergeMinimum` 完全不相交输入。

关于单点段，当前实现只能理解为“局部兜底”，不是通用零长度 segment 支持。`add()` 允许公共区间退化成一个点，主要是为了避免右端被全局时间上界裁到只剩 `[T,T]` 时直接得到空函数；`minimizePrefixInPlace()` 和 `minimizeSuffixInPlace()` 在没有生成任何 segment 时会补一条水平段，避免整函数因上界剪枝或单点退化变空；`trimToDomain()` 的右端裁剪也允许把最后一段裁成 `[T,T]`。但 `evaluate()` 只按 `[start,end)` 扫普通段，最后额外处理 `tail.end`，因此内部零长度段不是可靠的一般对象。`mergeMinimum` 也明确不接受 `[T,T]` 单点 label 作为普通输入，而要求 pricing 层直接尝试收尾到终点。因此后续设计仍应坚持：单点只允许作为全局右端 `T` 附近的退化兜底，不允许多个内部单点在函数链表里长期传播。

补充明确 `normalize()` 和普通扩展的关系：当前代码里 `normalize()` 主要由 `updateDominatedIntervals()`、`mergeMinimum()` 和 `mergeMinimum2()` 调用；普通 label 扩展链条是 `shiftX(...).add(jobPenalty)` 后直接 `minimizePrefixInPlace()` 或 `minimizeSuffixInPlace()`，并不会自动进入 `normalize()`。因此，若某次扩展因为定义域只剩全局右端而得到 `[T,T]`，`add()` 和 prefix/suffix minimize 可以兜住，不会立即出错；但这个 label 在语义上已经没有继续安排正加工时间任务的空间，后续应直接尝试接终点或丢弃，而不是继续进入 merge、dominance、partial dominance 或 normalize。另一个重要约束是：`add()` 只在两个函数实际定义域交集上相加，结果右端是两个输入实际右端的较小者。如果一个输入是 `[a,T]`，另一个输入右端小于 `T`，输出会被物理截到较短右端，而不会自动用 `big_M` 补齐到 `T`。因此在 BPC pricing 若要保持 `[a,T]` 契约，不能把右端较短的函数直接拿来相加；需要让不可行区间用 `big_M` 段覆盖到 `T`，或者在调用层明确该 label 已经离开 `[a,T]` 主流程。

2026-05-15 结合 `parallel_machine_scheduling_with_due_window.pdf` 又确认了一点：文档里的 pricing label 明确把函数定义为 `Dom(f_L)=[ell_L,T]`，并规定定义域外的函数值按 `+∞` 处理。扩展时如果引入 profitable completion window `H_ij=[h_ij,\bar h_ij]`，它只是限制本次 job `j` 的完成时间 `eta`，不应让扩展后的 label 函数右端物理变成 `\bar h_ij`。扩展后仍应是 `[ell_{L'},T]`，只是 `eta` 不在窗口内的状态应表现为 `+∞/big_M`。因此，后续 BPC pricing 如果要给 job 成本函数限制定义域，更合理的实现不是直接用当前 `setDomain(h,\bar h)` 裁成短函数再进入 `add`，而是保留 `[h,T]` 或 `[0,T]` 的右端，并把 `(\bar h,T]` 表示为 `big_M` 段。

这也说明了为什么不宜直接改 `add()` 去自动补右端。`add()` 作为底层函数相加，当前语义是取两个实际定义域的交集，这个语义清楚，也符合数学上的普通函数加法；如果在里面隐式把缺失右端补成 `big_M`，会掩盖上游把函数裁短的问题，并且可能影响启发式里 `Move.getCost()` 这种有意临时裁右端后只求最小值的查询逻辑。更稳的方向是新增或改造“限制完成时间窗口”的接口，让它显式把窗口外区间写成 `big_M`，同时保持右端到 `T`。这样 `[a,T]` 契约不会被破坏，`add/merge/dominance` 也不需要猜测调用者到底是想做普通交集相加，还是想表达窗口外不可行。

进一步讨论接口形式时，倾向于不要改掉现有 `setDomain(start,end)` 的语义。当前 `setDomain` 是物理裁剪 segment 链表，再恢复原来的 `domainStart/domainEnd` 元数据，这个行为已经被启发式的 completion 回推依赖。如果为了 pricing 窗口限制直接改它，会影响现有启发式。更稳的是新增一个语义明确的方法，例如 `setDomainWithBigM(...)`、`restrictWindowWithBigM(...)` 或类似名字：它不删除窗口外的右侧定义域，而是把窗口外区间改写成 `big_M` 段，并保持函数真实右端仍到 `T`。如果只做 Java 重载，也需要额外参数区分语义，例如 `setDomain(start,end,true)`，但从可读性看不如单独命名清楚。

最终按讨论采用了重载形式 `setDomain(start,end,fillOutsideWithBigM)`。`fillOutsideWithBigM=false` 时直接复用旧的物理裁剪逻辑；`true` 时新建函数，保留原函数实际覆盖区间的右端，把窗口左侧和右侧写成 `Utility.big_M` 水平段，窗口内部保留原 segment。这个方法主要给 BPC pricing 的 profitable window 使用。需要注意的是，当前 segment 仍按左闭右开处理，窗口右端点后接 `big_M` 段时，裸 `evaluate(end)` 会落到右侧 big_M；pricing 中该函数应随后做 prefix-min，让窗口内可行 completion 的最优值向右闭包。若后续出现只剩端点的窗口，仍建议在 label 层直接处理，不让它进入普通扩展链条。

这里说的 `evaluate(end)` 风险不是指 `evaluate()` 普遍错误，而是当前链表段的端点归属约定：普通段按 `[start,end)` 扫描，只有整条函数最后的 `tail.end` 会被特判为有效。因此如果把 profitable window 右端 `end` 后面接一段 `[end,T]` 的 `big_M`，裸调用 `evaluate(end)` 会命中右侧 `big_M` 段，而不是左侧窗口内原函数值。对 pricing 扩展来说，这通常不是主问题，因为随后要做 `minimizePrefixInPlace()`，而 prefix-min 内部会用左侧 segment 的 `seg.end` 参与计算，把窗口内直到 `end` 的可行最优值向右延伸。但如果未来有代码直接拿这个未 prefix-min 的窗口限制函数在内部断点 `end` 上求值，就需要注意这个端点语义。

验证上，新增了 `setDomain(fillOutsideWithBigM)` 的专项测试，检查窗口外值为 `big_M`、窗口内保留原函数值、`tail.end` 和 `domainEnd` 仍保持到原右端。重新编译 `PiecewiseLinearFunction.java` 和 `PiecewiseLinearFunctionPropertyTest.java` 后，`merge-find-contract` 结果为 `passed=5, warnings=0, failed=0`，完整测试为 `passed=16, warnings=1, failed=3`。剩余失败仍是之前的 `dominates` 定义域覆盖和 `mergeMinimum` 无效输入问题。

### 后续实现注意事项

这里把几个容易写错的点集中标记一下，后面写 BPC pricing 时优先按这些约束实现。

1. `setDomain(start,end)` 和 `setDomain(start,end,true)` 不是同一个语义。前者是物理裁剪 segment 链表，主要服务启发式 completion 回推；后者是把窗口外写成 `big_M`，用于 pricing 的 profitable window。不要在 pricing 主流程里误用两参数版本去裁短右端。

2. `add()` 不会自动补右端，也不应该让它自动补。它只在两个输入函数的实际公共定义域上相加；如果某个输入已经被物理裁短到 `end<T`，输出也会短到 `end`。因此 pricing 主流程要自己保证输入函数仍满足右端到 `T`。

3. `setDomain(..., true)` 后的窗口右端点存在内部断点归属问题。当前 segment 是 `[start,end)`，只有整条函数的 `tail.end` 特判；所以裸 `evaluate(hbar)` 可能返回右侧 `big_M`。正常 pricing 扩展应立刻做 prefix-min，把窗口内可行 completion 的最优值向右闭包；不要在未 prefix-min 的中间函数上直接用 `evaluate(hbar)` 做严格判断。

4. `curUpperBound` 在启发式和 pricing 里的意义不同。pricing 前要把它重置为 `Utility.big_M`，否则 prefix/suffix 取小可能把 reduced cost 后续仍有机会变好的区间提前压平或截断。

5. `[T,T]` 单点 label 只能作为右端退化兜底。pricing 中一旦扩展得到只剩全局右端的 label，应直接尝试接终点或丢弃，不要继续参与 merge、dominance、partial dominance 或普通 add 链条。
