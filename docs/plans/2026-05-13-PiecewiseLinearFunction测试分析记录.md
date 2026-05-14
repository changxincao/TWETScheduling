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
