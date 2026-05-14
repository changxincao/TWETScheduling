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

`updateDominatedIntervals` 当前倾向于不物理删除被占优区间，而是把区间替换为 `big_M` 水平段。这样链表仍然连续，`add` 仍可在“输入内部无物理 gap”的假设下使用。后续如果 BPC pricing 真的采用 partial dominance，需要重新确认这里的语义：被打成 `big_M` 的区间是要严格保留为 horizontal gap，还是允许被 forward prefix-min 重新闭包。第一版 exact pricing 更稳的做法是先做全段 dominance，把 partial dominance 保留为第二阶段优化。

`add` 在当前假设下没有暴露主要问题。它要求两个输入在公共定义域上按连续 segment 扫描相加；如果未来把 dominance 改成物理删除区间，让函数内部出现断裂，那么 `add` 不能直接复用。当前我们选择用 `big_M` 段保留连续链表，就是为了先避免这个问题。

`evaluate` 当前不需要单独修改。它主要在启发式三段拼接中使用：先由 `findMinimal` 确定时间点，再回代求值。若时间点超出定义域，返回 `Utility.curUpperBound` 可以理解为该拼接不可行或足够差。pricing 中只要先把 `curUpperBound` 重置为 `Utility.big_M`，这个行为也基本等价于返回不可行大数。需要避免的是把这种返回值拿来做未经证明的 partial label 剪枝。

当前仍需要重点处理的是 `minimizeSuffixInPlace` 和 `dominates`。`minimizeSuffixInPlace` 在某些尾部递减段场景下会漏掉应保留的尾部常数最小值，导致查询得到 `Utility.curUpperBound`；如果后续继续依赖 backward 函数，这是优先修复项。`dominates` 的主要风险是定义域覆盖不严格，支配函数右端较短时仍可能返回 `true`；如果后续作为 BPC label dominance 使用，需要重新写严格版本，至少明确完整定义域覆盖和“至少一点严格更优”的语义。

## 4. 右端定义域与调试检查

这里要区分 `tail.end` 和 `domainEnd`。`tail.end` 是当前 segment 链表真实覆盖到的最右端，`domainEnd` 是函数对象保存的元数据。正常情况下二者应该一致；但 `setDomain/trimToDomain` 可能把链表裁到 `end<T`，随后又把 `domainEnd` 恢复成原来的 `T`。因此检查 `tail.end == domainEnd` 不是重复检查，而是在定位“实际函数已经被裁短、但元数据还像是完整 `[a,T]`”的情况。

当前 `Configure.debugPWLFDomainCheck` 是分段函数右端定义域检查的统一开关，默认关闭，不改变现有求解流程。打开后，`setDomain`、`shiftX`、`add`、`minimizePrefixInPlace`、`minimizeSuffixInPlace`、`updateDominatedIntervals`、`normalize`、`mergeMinimum` 和 `mergeMinimum2` 的关键入口或出口会检查函数实际右端是否仍等于 `domainEnd`。

对 `mergeMinimum/mergeMinimum2`，现在额外接入了 `Utility.debugCheckPWLFMergeContract(...)`。它检查的是后续 pricing 必须遵守的输入契约：两个函数是否有正长度交集、右端是否一致、左函数是否退化成单点、右函数是否退化成单点。发现异常时会在 `Utility.debugMap` 中记录计数，并向 `System.err` 输出操作名、左右区间、公共区间、异常布尔值和两个函数对象 id。这个输出可以先定位是哪类操作、哪一对函数破坏了 `[a,T]` 结构；如果后续要定位到具体 label、节点或列，需要在 BPC 层继续传入业务 id。

## 5. 后续写 pricing 的默认流程

后续进入精确算法时，建议流程是：先运行启发式得到 incumbent 解和初始列；把这些列显式加入 BPC 的列池，并把 incumbent 目标值存到 BPC 自己的状态里；随后把 `Utility.curUpperBound` 重置为 `Utility.big_M`，再启动 pricing。pricing 中 label 的函数操作默认不使用启发式动态上界剪枝。

第一版 pricing 应优先保证函数结构简单、语义一致。也就是先坚持 `[a,T]` 右端统一、单点 label 直接收尾、全段 dominance 优先、partial dominance 暂缓。等主流程跑通后，再考虑用安全的 completion lower bound、dominance graph 或更细的数据结构压缩 label 数和函数段数。

## 6. 修改记录

2026-05-14：修正了 `mergeMinimum` 中截取 `g` 左侧前缀时的 `prevGH` 更新顺序。`SegmentPool.obtain(gh.start, start, gh.slope, gh.intercept)` 会生成一段新的截取片段，并不是直接截短原 `gh`。因此 `prevGH` 必须记录“刚刚跳过的前一段”，再移动 `gh=gh.next`；原来先移动 `gh` 再赋值 `prevGH`，会把前驱记成下一段，存在拼链错位风险。

2026-05-14：给 `PiecewiseLinearFunctionPropertyTest` 增加了 `merge-find-contract` 测试模式，只检查 `findMinimal` 和满足 `[a,T]`、右端同为 `T`、存在正长度公共区间的 `mergeMinimum` 输入。该模式下当前结果为 `passed=5, warnings=0, failed=0`。完整测试仍为 `passed=10, warnings=3, failed=16`，剩余失败属于既有风险，不是本次调试检查接入引入的新变化。

2026-05-14：把 `mergeMinimum/mergeMinimum2` 接入 `Utility.debugCheckPWLFMergeContract(...)`，并在 `Utility.debugCheckPWLFRightBound(...)` 附近补充中文注释，说明 `tail.end` 与 `domainEnd` 的区别。该检查默认关闭，仅用于后续接入 pricing 时定位是否有操作破坏 `[a,T]` 约束。
