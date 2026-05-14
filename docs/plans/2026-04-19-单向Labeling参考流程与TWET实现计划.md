# 单向 Labeling 参考流程与 TWET 实现计划

这份文档分两部分。前一部分把参考的那版 VRP BPC 单向 `GC` 代码的流程完整顺一遍，重点说明它到底在做什么、`Label` 里每个字段承担什么职责、扩展和占优是怎么串起来的。后一部分讨论如果把这套思路搬到当前 TWET 项目里，应该怎样改写，哪些地方可以直接保留框架，哪些地方必须因为问题结构变化而重写。这里不讨论双向拼接、DSSR、ng-route、启发式定价和 branch-and-cut 的完整协同，只聚焦第一版 exact pricing 应该如何落地。

## 一、参考版本单向 GC 的完整流程

参考代码的核心是 `BPC/GC/GC.java` 和 `BPC/GC/Label.java`。它解决的是一个非常经典的单向 labeling 定价问题：从源点出发，逐步把部分路径扩展到别的客户点，维护一批还没有扩展的标签和一批已经挂在各个末端点上的标签，通过资源可行性和 dominance 规则把明显不可能生成更优列的标签尽早删掉，最后在到达终点时恢复出路径，把负 reduced cost 的路径加回主问题。

这套代码最重要的地方不在于它的数学模型有多复杂，而在于它把 pricing 的工程流程拆得很干净。`Initialize()` 负责初始化标签容器，生成源点标签并塞进队列。`Extend()` 是真正的主循环，它不断从未扩展队列里取出一个标签，尝试沿所有可行弧去扩展。每扩展出一个新标签，先更新资源，再做 dominance，如果没有被已有标签压掉，就继续放回待扩展队列。若标签已经到达终点并且 reduced cost 为负，则恢复对应路径，检查是否已在列池中存在，不存在就加入新的列。`Solve()` 再把这一轮 pricing 包在 column generation 的最外层，先解受限主问题、取对偶、再调用 `Extend()` 找新列，直到找不到改进列为止。

原始 `Label` 的定义非常朴素。它本质上表示“一条从 depot 走到当前客户 `cid` 的部分路径所对应的一个状态”。`m_reduced_cost` 是这条部分路径当前的 reduced cost 标量；`m_weight` 是已消耗容量；`m_time` 是当前标签在末端客户上的服务开始时间；`m_low_reach / m_high_reach` 和 `m_low_visit / m_high_visit` 是两段 64 位的 bit mask，用来表示哪些客户已经访问、哪些客户已经因为资源原因不可达；`father` 指向父标签，专门用于最后恢复路径；`sr_count` 则是给 subset-row cut 用的，记录当前路径在每个活动 SR cut 里命中了多少个客户。这个结构有一个非常明确的特征：它只代表一条真实的 partial path，而不是多条路径的聚合状态，所以它天然支持最后按 `father` 指针把路径恢复出来。

`Initialize()` 做的事情也很简单。它先清空并重建 `UL` 和 `TL`。`UL` 是全局的未扩展标签优先队列，`TL` 是按末端点组织的标签存储，`TL.get(i)` 里存的都是当前末端点在 `i` 的标签。然后它构造源点标签，把 `cid` 设为 0，`m_reduced_cost` 初始化为 `-mu[0]`，时间和容量都为 0，访问状态把 depot 自己标进去，再通过 `UpdateReach()` 把显然不可达的客户预先标掉。这个初始标签同时进 `UL` 和 `TL[0]`。

进入 `Extend()` 以后，代码就一直做同一件事：从 `UL` 弹出一个标签，若它已经被标记为 `is_dominate`，直接跳过；否则枚举所有候选后继点 `i`。这里先用两层很快的过滤：第一层是节点分支约束 `node.feasible_arc[label.cid][i]`，第二层是 `CheckReach(label, i)`，也就是这个客户在当前标签下是否已经被提前判成不可达。如果两者都通过，才真正构造新标签。

构造新标签时，原始代码直接拷贝和更新离散状态。新标签的 `cid` 变成新到达客户，`father` 指回旧标签，`m_reduced_cost` 在旧标签基础上加上走这条弧的 cost，再扣掉对应的 arc dual 和 vertex dual。随后更新 `sr_count`，如果这次扩展让某个 subset-row cut 的命中数从 1 变成 2，就把对应的 `sr_mu` 减掉。这里可以看出原始代码一个很重要的设计：主 reduced cost 的更新完全在“扩展瞬间”完成，后面 dominance 只是在比较已有状态，而不会重新计算路径代价。

如果新标签到达的是终点，那么它不再进队列，而是立刻检查 `m_reduced_cost < tolerance`。只要 reduced cost 为负，就沿 `father` 把路径恢复成 route，查列池里有没有，没有就加到 `gn_index`。如果累计的新列已经达到一次允许加入的数量上限，就提前退出。这部分说明原始 `GC` 的职责非常单纯：它只负责找负 reduced cost 路径，并不负责直接解主问题。

如果新标签还没到终点，就要继续更新资源状态。容量直接累计；时间按 VRPTW 的逻辑更新为“前一客户服务开始时间 + 前一客户服务时长 + 行驶时间”和“当前客户最早时间窗”二者的最大值。然后调用 `SetVisit()` 标记当前客户已访问，再调用 `UpdateReach()` 扫描所有客户，把违反 elementarity、容量约束或时间窗约束的客户标成不可达。到了这一步，一个完整的新标签才算构造完成。

接下来最关键的是 `IsDominate()`。它只在同一个末端点上比较标签，也就是只和 `TL.get(cid)` 里的已有标签比较。比较时先看几个非常硬的条件：已有标签的 reduced cost、容量、时间都不能比新标签差，而且已有标签的可达集合也不能比新标签更弱。如果这些都满足，再考虑 subset-row cut 带来的修正项，判断旧标签是否足以压掉新标签。反过来，也会检查新标签能否压掉某个旧标签；若可以，就把旧标签从 `TL` 中移掉并标记为 `is_dominate`。最终如果新标签没有被别人完全压掉，就把它自己加入 `TL[cid]`。

因此，原始 `GC` 的运行结构可以概括成一条非常清晰的链：初始化一个源点标签，按优先队列不断取出标签做弧扩展，每次扩展后立刻更新资源和可达状态，再在同终点标签集合里做 dominance，保留活下来的标签，直到没有新的待扩展标签或者已经产生足够多的负 reduced cost 列。它的优点是代码骨架稳定、路径恢复简单、和主问题的接口清晰。它的局限也很明显：标签里只有标量 reduced cost，所以一个标签只代表一个离散状态点，而不能像 soft time windows 那类问题那样，用一条 frontier 表达“一条部分路径对应的一整族时间状态”。

## 二、如果搬到 TWET，哪些地方还能保留

搬到 TWET 时，最值得保留的不是旧代码里的某个公式，而是它的组织方式。第一，主类仍然可以叫 `GC`，因为它本质上还是 column generation 的 pricing 器。第二，标签类仍然叫 `Label`，而且仍然应该代表一条真实的部分序列，不要一上来就把多个不同路径合并进一个匿名 envelope 对象。第三，`UL` 和 `TL` 这种“一个全局待扩展队列，加一个按末端点挂标签集合”的结构仍然适用，因为 TWET 的单向 pricing 也还是在不断扩展 partial sequence。第四，`Initialize / Extend / IsDominate / UpdateReach / GetRoute` 这些方法名都可以保留，便于代码层面对照原始实现。

真正必须改写的是标签里的“成本”和“资源”语义。对 TWET 来说，一个部分序列到达某个末端 job 时，不再只有一个固定 reduced cost，而是对应一条关于完成时间上界的代价函数。结合当前项目里 `PiecewiseLinearFunction` 的实现和启发式里 `shiftX + add + minimizePrefixInPlace` 的定义，第一版最自然的选择是把标签函数定义成：`m_reduced_cost_function(C)` 表示这条部分序列在“当前末端 job 完成时间不晚于 C”时能够达到的最小 reduced cost。这样一来，这个 frontier 天然是前缀最小化过的、整体非增的，和当前函数类完全匹配。

在这个定义下，当前项目里的 `Label` 应该仍然保留 `jid` 和 `father`，同时新增 `m_reduced_cost_function` 和 `m_reduced_cost`。其中 `m_reduced_cost_function` 存完整 frontier，`m_reduced_cost` 则只是 frontier 当前的全局最小值，主要用于队列排序和一些快速过滤。原始 VRP 代码里的 `m_weight` 在 TWET 上不再是核心资源，如果第一版 exact pricing 不涉及额外容量、机器类型或别的离散资源，可以先去掉这个字段，或者把它替换成更适合 TWET 的辅助资源状态。访问状态则建议继续保留，但底层不要再用旧版两段 long 拼的写法，而是直接用当前项目里已经有的 `PackedBitSet`。表面命名仍然可以叫 `visit_set`、`unreachable_set`，逻辑风格上还是贴着旧版来。

## 三、TWET 单向标签的扩展语义

在第一版 TWET 单向 pricing 里，每个标签仍然对应一条真实的 partial sequence，从某个虚拟源点开始，到当前 `jid` 结束。源点标签的构造和旧版相同，也是在 `Initialize()` 里完成。区别在于初始标签不再只有一个 `m_reduced_cost = -mu[0]`，而是有一条初始 frontier。这个初始 frontier 可以理解为“空序列接到第一个 job 前的 0 成本函数”，后面扩展到第一个 job 时再真正叠加该 job 的 penalty function 和对应 dual。也可以把 depot 作为一个特殊 job 0，令它的 penalty function 恒为 0，把第一步扩展写成和普通扩展完全相同的形式。第二种写法更统一，也更适合后面把代码和当前启发式里的函数操作对上。

若一个标签 `L_i` 要扩展到 job `j`，新的 frontier 不应再像旧版那样通过一个标量公式更新，而应该通过函数操作得到。直观地说，若 `j` 的完成时间上界是 `C_j`，那么前驱 `i` 的完成时间上界必须不晚于 `C_j - s_ij - p_j`，所以应先把前驱 frontier 向右平移 `s_ij + p_j`，再和 `j` 的 TWET penalty function 相加，最后减去 `j` 对应的 dual 奖励，以及必要的弧对偶、机器数对偶、cut 对偶等常数项。得到新 frontier 后，再做一次 `minimizePrefixInPlace()`，保证它的语义仍然是“在不晚于某个完成时间上界时的最优 reduced cost”。这一步的本质和 Liberatore 2011 的 forward extension 是一致的，只是那边的时间转移是 travel + service，这边变成了 setup + processing。

扩展后，标签的访问集也要更新。如果这一版要求 elementary sequence，那么 `visit_set` 里已经出现过的 job 不允许再次扩展。如果后面为了加速，需要像 DSSR 那样放松 elementarity，那是在更后面的版本里处理，第一版不必混进来。与此同时，还可以像 2011 那篇文章那样维护一个 `unreachable_set`，用于记录在当前标签下已经不值得再扩展的 job。对 TWET 而言，这个“不可达”的语义不是时间窗硬约束不可行，而更接近“在当前 frontier 的整个定义域上，再接这个 job 都不会产生有意义的负 reduced cost 改进”。第一版其实可以暂时不启用这一层，只依赖 `visit_set` 和基础分支限制，等主逻辑跑通以后再加。

## 四、TWET 的 dominance 应该怎么设计

这部分是最容易混乱的地方。因为原始单向 `GC` 只有 pairwise dominance，而你现在又希望第一版就尽量往集合占优靠。这里最关键的一点是，必须把“真实标签”和“用于加速 dominance 的 envelope 缓存”分开，否则最后很容易既丢路径信息，又把代码写乱。

在当前计划里，`Label` 仍然只代表一条真实的 partial sequence，它保留 `father`，将来能恢复出具体列。`TL[j]` 也仍然是末端点为 `j` 的所有标签容器。为了做更强的 dominance，可以在 `TL[j]` 下面再引入一层 bucket 或 node，把“离散状态完全相同的一组标签”挂在一起。对于 TWET 第一版，这个离散状态最自然就是访问集，也就是某个 `visit_set`。于是，`TL[j]` 下面实际上存的不是一串松散标签，而是一组按 `visit_set` 划分的 `LabelNode`。每个 `LabelNode` 里维护若干真实 `Label`，并缓存一个 `node_envelope`，表示这个 node 内所有 frontier 的下包络。

这样设计以后，dominance 就可以分成两层。第一层是 node 内部的局部处理，也就是同一个 `visit_set`、同一个 `jid` 下的标签比较。这时不需要再看离散状态，只需要看 frontier 本身。若新标签与 node 内某个已有标签的 frontier 在整个定义域上互相比较后，一条完全压住另一条，那么被压住的那条真实标签可以直接删掉；如果谁都不能整体压住谁，但一条 frontier 的某些区间被另一条压住，就可以只删掉那部分区间，保留剩下区间。这个逻辑本质上就是 Liberatore 2011 那种“部分 dominance 导致 frontier 删段”，而不是整条标签直接消失。

第二层才是 set dominance，也就是跨 node 的比较。对于一个新标签 `L`，我们不只看某一个旧标签能否压它，而是看所有对它“有资格形成支配集合”的旧标签一起，能否构成一个下包络，把它整条或部分压住。这里的“有资格”其实就是 Luo 2016 里 eligible labels 的概念。放到当前问题里，最简单的理解是：在同一个末端 job `jid` 下，若某个旧 node 的离散状态不比新标签更差，那么它里的标签 frontier 就有资格参与对新标签的 set dominance。第一版最稳的做法，是直接扫描所有 eligible nodes，把它们的 `node_envelope` 合成一个 `eligible_envelope`，再用这个 `eligible_envelope` 去比较新标签 frontier。如果 `eligible_envelope` 在整个定义域上都不高于新 frontier，那么新标签直接被 set-dominated；如果只在部分区间压住它，就把这部分 frontier 删掉，剩余部分若非空，则标签仍保留。

这种写法已经是真正的 set dominance 了，只不过它还没有上 Luo 那种 dominance graph。原因在于，dominance graph 本质上是“如何更快地维护和查询 eligible set envelope”的数据结构，不是另一种不同的 dominance 语义。也就是说，先做 scan-based set dominance 是完全合理的，逻辑上也更容易做对。等后面要提速时，再把这些 `LabelNode` 之间的可比关系组织成一张 graph，让 envelope 缓存在 node 间传播，就能从“每次新标签进来都线性扫描 eligible nodes”变成“沿 graph 查询已经维护好的 envelope 缓存”。因此，第一版最合适的安排不是一开始就把 dominance graph 写死，而是先把 `LabelNode` 这一层搭出来，让 set dominance 的逻辑先成立，再把 graph 作为后续优化接入。

## 五、第一版代码应该怎么写

结合当前项目目录，最合理的落点不是去继续修补 `src/BPC/GC` 那版半移植草稿，而是把新的单向 exact pricing 真正写进 `src/TWETBPC/GC` 这套框架里。这里已经有 `PricingEngine` 和 `ExactPricingEngine` 的接口位置，只是现在还是占位实现。后续的 `GC` 和 `Label` 可以放在 `src/TWETBPC/GC` 下，命名仍旧保持原始风格，只是所在 package 归入新的 `TWETBPC` 体系。`ExactPricingEngine` 则只负责调起这套 `GC`，拿回 `PricingResult`，不把具体 labeling 逻辑塞进接口类里。

建议的类层次可以保持比较克制。`GC` 是主流程控制类，负责初始化、扩展、dominance 检查、路径恢复和结果输出。`Label` 是真实标签对象，保存 `jid`、`father`、frontier、访问集和若干辅助信息。`LabelNode` 是同一个 `jid` 下某个离散状态 bucket 的容器，里面有若干 `Label` 和一个 envelope 缓存。`DominanceGraph` 可以先把类建起来，但第一版只保留最基础的节点登记和查询接口，不进入主流程强依赖。这样既没有把结构铺得太开，也把后续往 Luo 风格优化的接口预留出来了。

从 `GC` 的执行顺序上，第一版仍然可以严格模仿原始单向 `GC`。`Initialize()` 生成初始标签并创建 `UL`、`TL`。`Extend()` 从 `UL` 中不断弹标签，先判断是否已被淘汰，再枚举可扩展 job，构造新标签 frontier，更新访问状态，然后调用 `IsDominate()`。如果新标签 survives，就进 `UL` 和对应的 `LabelNode`。如果新标签已经形成完整 sequence 且最小 reduced cost 为负，就恢复出具体 job 序列，计算列成本，塞进 `PricingResult`。外层的 `ExactPricingEngine.price(lp)` 则像旧版 `Solve()` 那样围绕 RMP 对偶去调用它，但因为当前真正的 RMP 还没建起来，第一步可以先把 exact pricing 逻辑自身做完整，主问题接入随后再补。

## 六、当前阶段的实施顺序

如果按稳妥顺序推进，这件事应分成三个层次。第一层先把语义定死并做出最小能跑的单向版本，也就是 `Label` 定义、frontier 扩展、scan-based set dominance、路径恢复和负 reduced cost 列输出。这一层最重要的是把“frontier 的自变量到底是什么”“扩展时到底 shift 什么”“dominance 到底是删整条还是删区间”这些语义彻底稳定下来。第二层再做 `LabelNode` 的 envelope 缓存，把当前每次对真实标签逐个比较的成本降下来，并把 set dominance 从“对标签做”真正收束为“对 node 的 envelope 做”。第三层才是 dominance graph，把 eligible node 的 envelope 缓存传播和查询优化做进去。也就是说，第一版该追求的是逻辑正确、命名和骨架贴近原始 `GC`、并且以后可以自然接到 Luo 风格结构上，而不是一开始就把所有高级优化都写进去。

当前判断是，这条路线是清晰且可实施的。原始单向 `GC` 的代码足够提供主流程骨架；Liberatore 2011 足够提供函数标签和部分删段 dominance 的语义基础；Luo 2016 则更适合作为第二阶段以后对 set dominance 数据结构的加强，而不是第一版的起点。对于当前项目来说，先把这三层关系分清楚，再动手写代码，会比一边实现一边改语义稳得多。
