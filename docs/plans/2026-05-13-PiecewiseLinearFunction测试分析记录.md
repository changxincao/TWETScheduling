# PiecewiseLinearFunction 测试分析记录

## 问题

这次检查的是 `Common.PiecewiseLinearFunction` 里和分段线性函数操作相关的核心函数，重点看它们在启发式路径评价和后续 BPC pricing 中是否足够稳。参考背景是 Ibaraki 等关于带凸时间惩罚 VRP 的局部搜索思路：路径或片段的评价函数会不断做 `shift`、`add`、前缀/后缀最小化、函数下包络合并、占优判断等操作。因此测试没有只看单个函数能否跑通，而是用点值 oracle 和随机函数去检查这些操作是否满足它们应有的数学语义。

本次没有修改 `PiecewiseLinearFunction` 的主体实现，只新增了一个测试 package：`src/PWLFTesting`。测试入口为 `PiecewiseLinearFunctionPropertyTest`，输出放在 `pwlf_test_results` 下，主要包括 markdown 报告和 csv 失败明细。测试只单独编译 `PiecewiseLinearFunction` 和该测试类，没有跑整个 Maven 构建，避免被 CPLEX 依赖和其他模块干扰。

## 测试方式

测试覆盖了 `shiftX`、`add`、`minimizePrefixInPlace`、`minimizeSuffixInPlace`、`findMinimal`、`dominates`、`mergeMinimum` 和 `updateDominatedIntervals`。其中 `shiftX` 和 `add` 用点值比较验证；前缀/后缀最小化用离散采样 oracle 近似验证；`mergeMinimum` 先按纯逐点最小值测过，后来对照论文语义改成“下包络后前缀最小化”再测；`dominates` 单独构造定义域不一致的反例；此外还加了两组随机测试，一组是一般连续分段函数，一组是先做 prefix-minimize 后更接近路径评价前沿函数的分段函数。

当前运行结果为：`passed=6, warnings=4, failed=25`。这个数字不能简单理解为 25 个独立 bug，因为同一个函数可能在多个采样点重复失败；但它足够说明，这套实现目前不是一个通用、鲁棒的分段线性函数库。它更像是为了当前启发式流程中某些特定函数形态写的高效实现，一旦输入形态超出假设，就会出现错误值、异常，甚至在随机测试中出现疑似卡住风险。

## 主要发现

`shiftX`、`add` 和 `minimizePrefixInPlace` 在当前构造的正常连续用例中通过了点值检查，说明这些基础操作在常规路径评价场景下暂时没有暴露明显问题。`updateDominatedIntervals` 在“完全被支配”和“中间一段被支配”的简单用例中也没有异常，不过这只能说明基本链表拆分能跑通，不能证明复杂交点、多段 gap 或定义域扩展时一定安全。

风险比较明确的是 `minimizeSuffixInPlace`。测试构造了一个普通连续三段函数，后缀最小值在若干采样点的期望值为 `7.5`，实际返回 `1.0E8`，也就是全局大 M。这说明后缀最小化至少在某类单调段组合下会把本来有限的可行值处理成不可行值。结合代码里这个函数原本就有“还需要测试”的注释，后续如果反向函数或三段拼接依赖它，需要优先检查这里。

`dominates` 的定义域处理也存在明确语义风险。当前测试里，一个只覆盖 `[0,5]` 的函数会对覆盖 `[0,10]` 的函数返回占优。代码中原本把 `tail.end < g.tail.end` 的直接拒绝逻辑注释掉了，并在后面用端点值做了一个放松判断。这个做法可能是为了某些前向/反向传播下的平缓段延伸，但如果在 BPC label dominance 中把占优理解为“在被占优函数完整定义域上都不差”，这个判断是不安全的，会误删 label。

`mergeMinimum` 一开始按“纯逐点 min”测试时暴露了很多失败，但这部分需要重新解释。对照 Ibaraki 2008 的 forward minimum penalty function，它定义的是在“最后服务时间不晚于 t”的条件下的最小成本，递推里确实存在 `min_{t' <= t}` 的 minimize operation。因此 `mergeMinimum()` 末尾调用 `normalize()`，而 `normalize()` 再调用 `minimizePrefixInPlace()`，在 forward 前沿函数语义下是合理的。测试改成“先取下包络，再做前缀最小化”的 oracle 后，普通重叠定义域用例已经通过，说明这里不是单纯实现错，而是之前测试语义过于通用。

但 `mergeMinimum` 仍然不能当作通用下包络合并。完全不相交的定义域下，左侧不相交会触发 `NullPointerException`，右侧不相交会把应为 `10.0` 的值变成 `1.0`。随机测试改成 forward 前沿语义后，失败从 `499/500` 降到 `86/500`，更贴近当前前沿函数的随机测试则为 `3/500`，但仍出现了定义域错位、NPE 和疑似卡住。这和代码注释中的历史假设一致：该函数一开始假设两个函数“不可能完全没有重叠区间”，并且正向函数右侧界、反向函数左侧界往往一致。若后续 pricing 的 label 集合包络要复用它，必须先明确输入函数是否始终满足这些定义域假设；如果不能保证，就不能直接用。

`findMinimal` 暴露的是边界语义问题。测试构造了一个在相邻段交界处有竖直跳变的函数，左极限处的最小值为 `0.0`，但旧实现只检查每段 `start` 和最后 `tail.end`，会返回 `5.0`。这个问题已经在 2026-05-13 修改：现在每一段都会检查 `start` 和按当前段计算的 `end` 值，从而把上一段右端左极限也纳入候选。连续函数下原逻辑没有问题，因为上一段 `end` 的左极限等于下一段 `start`；但占优、merge、裁剪后可能产生 horizontal/vertical gap，所以先采用全段端点扫描，后续如果性能有压力，再按 forward/backward 函数方向做加速。随后又补了更细的测试，包括连续平底、竖直 gap、向下跳到平底、分离的相同最小值，以及先取 `fromRight` 再取 `fromLeft` 的缓存顺序。目前这些 `findMinimal` 定位用例都已通过，说明这次修改没有破坏 `leftX/rightX` 的基本语义。

还有一个不是 bug 但必须记住的语义依赖：前缀/后缀最小化会使用 `Utility.curUpperBound`。当 `curUpperBound=100`、原函数值为 `200` 时，prefix-minimize 后得到的是 `100`，不是数学意义上的 `200`。这在启发式里可以理解为上界剪枝或不可行截断，但在精确定价里要非常小心，因为 reduced cost 可能包含负 dual，提前用 incumbent 上界截掉函数值可能改变真实 reduced cost。

`minimizeSuffixInPlace` 当前更像真实实现问题，不只是测试语义问题。失败用例中，最后一段是递减段，后缀最小化后理论上应在尾部保留一段常数最小值；但现实现里 `lastT == tail.end` 时会跳过插入尾部常数段，后续又把 `lastT` 移到更靠左的位置，导致尾部区域没有被写入，最终查询得到 `big_M`。这和代码中 2025-05-10 加的保护条件有关，注释写的是“思路同 prefix 最小化”，但 suffix 的尾部常数段和 prefix 的头部处理并不完全对称。这个问题会影响 backward minimum penalty function，因为论文中 backward 函数定义为 `min_{t' >= t}`，代码里也大量依赖 `minimizeSuffixInPlace()`。

## 当前结论

这次测试说明，`PiecewiseLinearFunction` 当前更适合继续服务启发式中已经受控的函数流，不适合直接作为 BPC pricing 的通用函数操作内核。若后续只是在原启发式路径评价里继续使用，优先要查的是 `minimizeSuffixInPlace` 和 `mergeMinimum` 在当前真实调用路径下是否会碰到这些输入形态；如果准备把它迁移到 labeling pricing，尤其是用于集合下包络、函数占优、label dominance，就需要先重写或加固 `mergeMinimum`、`dominates` 和边界最小值语义。

暂时不直接改核心代码，原因是这些问题不一定都对应当前启发式主流程的真实 bug。有些失败来自通用数学语义，有些来自对当前函数类假设的突破。下一步更合理的做法是沿真实调用链补一组集成式测试：从单个任务惩罚函数开始，按路径拼接流程构造前向/后向函数，再检查三段拼接、邻域 move 评价和真实目标值是否一致。这样能区分“必须修的线上 bug”和“只在未来 BPC pricing 泛化时才需要重构的问题”。

## 修改记录

2026-05-14 对 `findMinimal` 的代码注释做了补充。当前 `normalize()` 实际是 forward normalize，内部固定调用 `minimizePrefixInPlace()`，因此只适合 forward 前沿函数；backward 函数当前不走 `normalize()`，而是在构造时直接调用 `minimizeSuffixInPlace()`。如果 backward 函数保持连续、单调不减，旧版 `findMinimal` 只看每段 `start` 通常也能得到正确最小成本。真正需要补充扫描每段 `end` 的原因，是未来如果 backward label 也参与合并取小、占优裁剪或集合包络，可能出现向上的 vertical gap，此时最小成本本身未必错，但 `fromRight` 要返回最右侧最优时间点，旧逻辑可能返回得过早。

同日还修正了 `mergeMinimum` 中截取 `g` 左侧前缀时的 `prevGH` 更新顺序。`SegmentPool.obtain(gh.start, start, gh.slope, gh.intercept)` 会生成一段新的截取片段，并不是直接截短原 `gh`。因此 `prevGH` 必须记录“刚刚跳过的前一段”，再移动 `gh=gh.next`；原来先移动 `gh` 再赋值 `prevGH`，会把前驱记成下一段，存在拼链错位风险。修正后普通重叠用例仍通过，但随机前沿测试的失败数量没有减少，说明 `mergeMinimum` 的定义域错位问题不只是这个指针顺序导致，后续仍需要系统处理公共区间、单点交集和左右非重叠前后缀拼接。

2026-05-14 又在 `mergeMinimum` 附近补充了更明确的输入契约说明。当前判断是，这个函数不应作为任意两个分段线性函数的通用下包络合并器使用，而应限制在 pricing/标签函数场景：输入函数的定义域应为 `[a,T]`，右端都取到全局最大时间 `T`。在这个设定下，完全左侧不相交、完全右侧不相交、右端错位这几类测试中暴露的问题不会出现在有效输入中；左端错位是正常情况，需要由当前左前缀拼接逻辑处理。唯一需要人为排除的是只在 `T` 一个点相交的单点 label，因为支持它会迫使函数结构维护零长度点段，后续 `add`、`shift`、`dominance` 都会变复杂。因此后续 pricing 中若 label 退化为 `[T,T]`，应直接尝试连接终点生成完整列，不再进入普通 `mergeMinimum` 或 dominance 流程。代码中同时预留了一个默认注释掉的调试块，后续接入 BPC pricing 时可以打开，用来统计是否存在无正长度交集、右端不一致或单点退化的异常输入。

同日进一步给 `PiecewiseLinearFunctionPropertyTest` 增加了 `merge-find-contract` 测试模式，只检查 `findMinimal` 和满足 `[a,T]`、右端同为 `T`、存在正长度公共区间的 `mergeMinimum` 输入。该模式下，`findMinimal` 的普通多段、竖直跳变左极限、左右最优位置选择均通过；`mergeMinimum` 的确定性左端错位用例通过，500 个随机 `[a,T]` forward frontier 用例也通过。当前运行结果为 `passed=5, warnings=0, failed=0`。这说明在我们刚确认的 pricing 输入前提下，`findMinimal` 和 `mergeMinimum` 暂时没有暴露问题；原整套测试里仍存在的失败主要来自无效 merge 输入、`minimizeSuffixInPlace` 和 `dominates` 的其他语义风险，不应混同为这两个函数在当前契约下失败。

继续检查其他函数后，当前最明确的真实问题仍然是 `minimizeSuffixInPlace`。测试用例中最后一段为递减段时，后缀最小化应在尾部形成一段常数最小值，但代码在 `lastT == tail.end` 时跳过了插入尾部常数段，后续又继续向左推进，导致尾部区间缺段，查询时得到 `Utility.curUpperBound`。这和 2025-05-10 为了类比 prefix 最小化加的保护条件有关，但 suffix 的尾部常数段不能这样跳过。由于启发式中的 backward 函数大量调用 `minimizeSuffixInPlace`，如果后续继续严格处理 backward 评价，这个函数应优先修。

`dominates` 的主要风险是定义域覆盖语义不严格。当前如果支配函数的右端比被支配函数短，代码没有直接拒绝，而是比较 `this.tail.end` 和 `g.tail.end` 处的值，随后只扫描共同覆盖到的部分；一旦 `p` 提前耗尽，循环结束后仍可能返回 `true`。这在“右端水平段可自然延伸”的启发式语义下也许有一定解释空间，但若 BPC label dominance 要求在被支配函数完整定义域上都不差，这就是不安全的。另外注释中说支配要求至少一点严格更优，但函数内部没有真正记录 strict 标志；如果外层资源状态已经严格更优，这可能没问题，否则函数本身会把完全相等也当成支配。

`updateDominatedIntervals` 目前只通过了很基础的“完全支配”和“中间段支配不报错”测试，不能认为已经可靠。它把被支配区间替换成 `big_M`，随后调用 `normalize()`；而 `normalize()` 会执行 forward prefix-min。这样在部分支配场景下，原本应该保留的 horizontal gap 可能被前缀最小值重新填成有限值，导致“删段”语义被破坏。2011 年论文里 dominance 后的函数可以有 horizontal gap，并不应该总被 prefix-min 抹平。因此如果后续 BPC pricing 真要做 partial dominance，这块不能直接照现在的实现用，需要重新定义 gap 是用 `big_M` 段表示，还是物理删除区间，并保证后续 `add/shift/merge` 都能识别这种表示。

还有两个工程性风险需要记住。第一，`normalize()` 本质是 forward normalize，不是通用归一化；它会删除首尾 `big_M` 段、合并相邻同线段，并调用 `minimizePrefixInPlace()`。因此它不能直接用于 backward 函数，也不能用于需要保留 horizontal gap 的一般函数。第二，`PiecewiseLinearFunction` 有 `findMinimal` 缓存，但当前这不是 `findMinimal` 的正确性问题。`Move` 和 `Solution` 里的调用基本都是在三段/两段拼接评价的最后一步使用：先构造临时函数，再直接取最小值。这个用法下缓存没问题。只有未来如果在同一个对象上先 `findMinimal`，随后又原地 `minimize/normalize/update`，再调用 `findMinimal`，才需要在修改后显式 `resetMinimum()`。

`shiftX`、`shiftY`、`add` 和 `minimizePrefixInPlace` 在当前测试覆盖的正常连续输入下没有暴露问题。需要注意的是，`add` 仍假设两个输入内部没有缺口，只是在公共定义域上逐段相加；如果未来 partial dominance 真的把函数表示成带物理 gap 的链表，`add` 不能直接复用。`setDomain/trimToDomain` 现在会允许裁出 `[T,T]` 单点，这和前面讨论一致：单点在启发式里可以暂时存在，但在 pricing 的 `mergeMinimum/dominance` 层应提前拦截。

关于 `Utility.curUpperBound`，后续需要明确区分启发式阶段和 BPC pricing 阶段。启发式阶段它可以设为当前最好解成本，在 `PiecewiseLinearFunction` 的 prefix/suffix 取小和 move 评价里起到动态 big-M 的作用，减少明显不会改进当前解的函数段。pricing 阶段不建议继续使用这个启发式上界，因为 reduced cost 会减 dual 和 cut 项，partial label 当前函数值较大，后续仍可能被对偶项拉低。第一版 exact pricing 应简单地在进入 pricing 前调用 `Utility.resetCurUpperBound(Utility.big_M)`。启发式得到的最好解仍然要保存，但应作为 BPC 的 incumbent 或初始列池传入，而不是继续通过 `curUpperBound` 截断分段函数。

`evaluate` 当前没有暴露实现问题。现有调用主要在 `Solution.merge3Segments` 和 `merge3SegmentsTest` 中，用于在 `findMinimal` 已经确定拼接时间点后回代计算函数值；另一个调用点在 `dominates` 的定义域右端比较里。启发式场景下，如果时间点不在定义域内返回 `Utility.curUpperBound`，可以理解为该拼接不可行或足够差。pricing 场景下，只要进入定价前把 `curUpperBound` 重置为 `Utility.big_M`，这个行为也基本等价于返回不可行大数。真正需要小心的是不要把 `evaluate` 的超定义域返回值和精确定价中的安全剪枝混在一起；合法时间点上的正常求值没有问题。

随后补了 `updateDominatedIntervals` 的复杂测试，专门检查交点切分、多段被支配以及“被支配区间打成 `big_M` 后再做 forward prefix 闭包”的语义。测试没有通过，说明这里还不能简单认为已经没问题。失败主要暴露两类情况：第一，若被支配区间延伸到尾部，当前 `normalize()` 会先删除尾部 `big_M` 段，再做 `minimizePrefixInPlace()`，因此不会把尾部按前缀最小值补回；如果我们希望 `big_M` 和前端最小值在 forward 闭包下等价，这个实现顺序就不一致。第二，交点端点处存在开闭区间约定问题，例如 `g` 与 `this` 在某个交点相等，代码可能保留右侧段的起点值，而测试 oracle 会把该点按被支配处理。由此当前结论应调整为：`updateDominatedIntervals` 的基础链表替换能跑，但它在 BPC partial dominance 中到底应“保留 M 段”“裁掉 M 尾部”还是“打 M 后再闭包”，需要先定清楚语义，再决定是否修改代码和测试 oracle。

2026-05-14 ? `updateDominatedIntervals` ? `normalize()` ?????????????? partial dominance?????????? `normalize()` ?????????? `big_M` ???????????? `big_M` ???????? `minimizePrefixInPlace()` ??? forward prefix ??????????????????????????????? `[a,T]` ?????????? dominated ???? `big_M` ?? normalize ???????

同时明确了一个端点约定：`replaceWithBigM` 替换的是半开区间 `[cur,nxt)`，`nxt` 这个点归右侧片段，不为单个端点维护长度为 0 的特殊段。这和后续 pricing 的约束一致：如果 label 只剩下 `[T,T]` 这类单点，就直接尝试收尾生成列，不再进入普通 merge/dominance 流程。测试 oracle 也相应调整为同时检查断点左极限和右侧半开区间，而不是把交点等值处简单当成整点被支配。

修正后，`updateDominatedIntervals` 的“完全支配”“中间段支配”“复杂 forward-closure 随机测试”均通过。完整测试当前仍有失败，但剩余失败不再来自 `updateDominatedIntervals`：主要还是已知的 `minimizeSuffixInPlace` 尾部缺段、`dominates` 定义域覆盖不严格，以及 `mergeMinimum` 在无效定义域输入下的问题。单独运行 `merge-find-contract` 模式仍为 `passed=5, warnings=0, failed=0`。

2026-05-14 ? `updateDominatedIntervals` ? `normalize()` ?????????????? partial dominance?????????? `normalize()` ?????????? `big_M` ???????????? `big_M` ???????? `minimizePrefixInPlace()` ??? forward prefix ??????????????????????????????? `[a,T]` ?????????? dominated ???? `big_M` ?? normalize ???????

2026-05-14 ???????????????? T?????????????? forward ??????????? `[a,T]`????? `T=data.CmaxH` ??`normalize()`?`minimizePrefixInPlace()`?`updateDominatedIntervals()`?`mergeMinimum()`?`add()` ??? `shiftX()` ???????????? `T`??? `normalize()` ????????? `big_M` ???`add()` ?????????? `T`??????? `T`??? `shiftX()` ??????? `T+delta`??? `trimToDomain()` ?? `T`?

???????? `setDomain(..., end<T)`?????????? forward ?????? `setDomain` ??????? `data.CmaxH`???????????????????? `Move.getCost()` ???? completion time ????????????????????????????????????? `findMinimal()` ???????????? BPC pricing ? label ????`backward`??? `shiftX()` ? `minimizeSuffixInPlace()` ?????????????????

??????????????? debug ?????? `Configure.debugPWLFDomainCheck`????????????? `Utility.debugCheckPWLFRightBound` ? `Utility.debugCheckPWLFRightBoundPair`???????? `setDomain`?`shiftX`?`add`?`minimizePrefixInPlace`?`minimizeSuffixInPlace`?`updateDominatedIntervals`?`normalize`?`mergeMinimum` ? `mergeMinimum2` ???/????????? `tail.end` ????? `domainEnd` ??????????????????? `Utility.debugMap` ????????`setDomain` ??????? `resetDomain` ??????????segment ????? end<T?? domainEnd ???????? T?????
