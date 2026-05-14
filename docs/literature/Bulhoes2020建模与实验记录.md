# Bulhões 2020 建模与实验记录

## 问题

这里主要想弄清三件事。第一，Bulhões et al. (2020) 这篇文章里的 BCP 到底是不是建立在 time-indexed 模型基础上的。第二，文章有没有直接比较“原始 time-indexed/arc-time-indexed 模型直解”和 BCP 的效率。第三，文章的实验到底在比较什么，上界对 BCP 的影响有多大，它和已有文献方法比起来又是什么结论。

## 建模思路

这篇文章不是直接拿一个普通 time-indexed MILP 外面套一个 BCP。它先建立的是一个 arc-time-indexed formulation，文中记为 `F1`。这个模型本质上还是时间扩展网络模型，只不过变量对应的是弧，而不是更常见的单点时间索引变量。弧表示某类机器上，在某个时刻，从作业 `i` 接到作业 `j` 的一次转移。然后作者把这个 `F1` 用 Dantzig-Wolfe 分解改写成主问题 `DWM`，列变量对应的是机器上的一条路径，也就是一条伪调度路径。后面的 pricing、cuts 和 branching，都是在这个分解后的主问题上展开的。所以更准确的说法是：这篇文章的 BCP 是建立在 arc-time-indexed 模型基础上的 Dantzig-Wolfe reformulation，而不是直接对原始 arc-time-indexed 模型做 branch-and-cut。

## 实验方案

文章没有做“原始 arc-time-indexed/time-indexed 模型直接交给 CPLEX”与“BCP”之间的正面效率比较。它的数值实验重点不在这里，而是在分析 BCP 自身的几个组件是否有效。具体来说，文中主要比较了有无 rank-1 cuts 时的表现，初始上界质量对 BCP 的影响，以及 BCP 对文献中已有上下界结果的改进情况。对于 `P|| Σw_j T_j` 那组实例，文章还额外比较了 root node 上 no cuts、robust cuts only、rank-1 + robust cuts 三种切割配置的效果，但这依然是 BCP 组件层面的比较，不是拿原始 time-indexed 模型直解来做基线。

## 结果

关于和已有方法的比较，文章里最直接的对象其实不是别的 exact algorithm，而是已有文献里的 heuristics 或 primal-dual 方法。对于 `R|| Σ(w'E + wT)` 这组 Şen-Bülbül 2015 实例，文章明确说这些带 earliness-tardiness 目标的实例此前没有被 exact algorithm 真正系统求解过，因此它更多是在和 Şen-Bülbül 2015 的方法比较 lower bound 质量、最优解数量和 gap。结论是它的 BCP 在这方面明显更强，很多实例是第一次被精确解出，gap 也显著更小，但代价是 CPU 时间普遍更大。文中甚至直接提到，尽管 Şen-Bülbül 2015 的 lower bound 质量更差，但他们方法报告的运行时间通常比 `UILS + BCP` 还小。也就是说，这篇 2020 的主要优势是“解得更准、能关 gap、能证最优”，而不是“时间上全面碾压已有方法”。

如果看 upper bound，本篇文章对已有文献 best known solutions 的改进其实不大。表 10 给出的结论很明确：对 `R|| Σ(w'E + wT)` 和 `R|r,s| Σ(w'E + wT)` 两组实例，平均改进幅度都只有大约 `0.05%`。这说明已有启发式方法在找好可行解这件事上已经做得相当强了，BCP 的主要价值不在于把上界一下子拉开很多，而是在于提供更强的 lower bound、减少 gap，并把不少实例真正解到最优。

关于初始上界的影响，文章做了比较系统的测试。它在表 8 和表 9 里用 Kramer and Subramanian (2019) 的 `UILS` 启发式分别跑 `2 / 5 / 10` 次迭代，拿得到的上界作为 BCP 的初始 primal bound。结论很直接：迭代次数越多，初始上界越好，BCP 的平均 gap、节点数和总时间整体上都会更好。文章原话就是，这个前置求一个高质量上界的开销，在大多数情况下是值得的。对 `R|| Σ(w'E + wT)` 那组实例，`UILS(10)` 比 `UILS(2)` 和 `UILS(5)` 的整体表现最好；对 `R|r,s| Σ(w'E + wT)` 这组更难的实例也是同样趋势。换句话说，这篇文章认为上界质量对 BCP 的影响是明显的，而且是值得专门花时间先求好一点的。

文章里实际使用的初始上界来源大致有两类。第一类是直接使用文献中已有的 best upper bounds，例如 Şen-Bülbül 2015 和 Pessoa et al. 2010 那些实例。第二类是作者自己调用 Kramer and Subramanian (2019) 的 `UILS` 启发式来生成初始上界，特别是 Pereira Lopes and Valério de Carvalho 2007、Kowalczyk and Leus 2018、Kramer 2015 这些实例。换句话说，文章并没有依赖单一上界来源，而是把“已有最好结果”和“现跑启发式”这两种方式都用上了。

## 当前判断

对我们后续做 TWET 精确算法，这篇文章最重要的启发不是“time-indexed 直解能不能打过 BCP”，因为它根本没有回答这个问题。它真正给出的信息是：第一，可以从 arc-time-indexed 模型出发，经 Dantzig-Wolfe 分解走到 BCP；第二，BCP 的表现对初始上界质量很敏感，好的上界是值得花时间先求的；第三，在 TWET 这类问题上，很多时候 heuristic 已经能把 upper bound 做得很强，exact 方法的主要价值更可能体现在 lower bound、gap closing 和 optimality proof 上，而不只是继续挤 upper bound。
