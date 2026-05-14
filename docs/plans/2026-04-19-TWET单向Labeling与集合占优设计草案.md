# TWET 单向 Labeling 与集合占优设计草案

这份草案只讨论第一版 exact pricing 应该怎么落，不涉及双向拼接、DSSR、ng-route，也不急着把 Luo 2016 那套完整的 dominance graph 一次性全接进来。目标很明确：先模仿原始 VRP 代码里最简单的单向 `GC`，把 TWET 的函数标签版单向 labeling 做出来，同时把后续切换到 set dominance / dominance graph 的数据结构位置预留好。

原始 `GC.java` 的框架其实非常干净：`UL` 是待扩展标签队列，`TL` 是按末端点存储的标签集合，`Label` 只保存当前末端点、资源状态、访问状态、父标签和一个标量 reduced cost。这个框架可以继续保留，真正需要换掉的是标签里“成本”的表达。对 TWET 来说，一个标签不该只带一个标量，而应该带一条函数，表示“当前这条部分序列在给定末端完成时间上界时的最优 reduced cost”。结合现有启发式里 `shiftX + add + minimizePrefixInPlace` 的语义，第一版最合适的定义是：令标签函数 `m_reduced_cost_function(C)` 表示该部分序列在“当前末端 job 的完成时间不晚于 `C`”时能够达到的最小 reduced cost。这样一来，函数天然是前向的、经过 `minimizePrefixInPlace()` 以后是单调不增的，和你现在 `PiecewiseLinearFunction` 的实现是对得上的。

按这个定义，第一版 `Label` 建议保留原始命名风格，不要另起太多新名字。核心字段可以是：`jid` 表示当前末端 job；`father` 表示父标签，专门用于最后恢复列；`m_reduced_cost_function` 存 frontier；`m_reduced_cost` 存这条 frontier 当前的最小值，作为队列排序和快速过滤用；`visit_set` 表示已经访问的 job 集；如果后面要做不可达集强化，再加一个 `reach_set` 或 `unreachable_set`。这里我不建议继续沿用旧版 `m_low_visit / m_high_visit` 那种 128 位拆开的写法，逻辑命名仍然模仿旧版，但底层直接用现在已有的 `PackedBitSet` 更合理，因为当前项目已经把位集封装好了，而且后续 TWET 的规模也未必愿意被旧版的 128 上限绑死。

扩展公式也可以直接按这个定义写死。若标签 `L_i` 结束于 job `i`，要扩展到 `j`，那么先把前驱 frontier 向右平移 `s_ij + p_j`，再把 job `j` 自己的惩罚函数加上去，然后减去对应的 dual 奖励，再做一次 `minimizePrefixInPlace()`，得到新标签 `L_j` 的 frontier。这里和 Liberatore 2011 的本质是一致的，只不过 VRP 那边是 travel time + service time + soft time window penalty，这里换成 setup + processing + TWET penalty。第一版不必再额外引入别的时间资源，函数本身已经把“时间维上的取舍”吸收掉了。终止时如果允许直接结束列，就对 `m_reduced_cost_function` 取全局最小值；如果模型里终点还带额外常数项，比如机器数对偶或其他 node/cut 对偶，就在初始化或收尾时统一加减掉。

占优这块，我的建议是结构先按 set dominance-ready 去搭，但第一版不要直接把 dominance graph 作为主逻辑。更稳的做法是分两层。第一层是真实标签层，也就是 `Label` 本身，它始终代表一条实际 partial sequence，保留 `father` 指针，不和别的路径做物理合并。第二层是节点级缓存层，可以叫 `LabelNode`、`LabelBucket` 或 `DominanceNode`，这层不代表真实路径，只代表“一组可比较标签的 envelope 缓存”。这样做的原因很现实：一旦你真的把多个不同父路径的 frontier 直接 merge 成一个 label，后面虽然更容易删状态，但会丢掉明确的路径恢复信息。pricing 最后要往主问题里加的是一条具体列，不是一条匿名 envelope，所以真实标签和 envelope 缓存最好分开。

具体到第一版占优逻辑，我建议先上“2011/2014 风格的函数删段 + 不用 graph 的 set dominance”，也就是你刚才说的前者。操作上，仍然保持 `TL[j]` 这种按末端 job 分桶的结构，但在每个 `j` 下再按可比较的离散状态建小桶。对 TWET 第一版来说，这个离散状态最自然的就是访问集，必要时再把 cut 状态并进去。于是同一个 `jid` 下，每个 exact signature bucket 对应一个 `LabelNode`，里面放若干真实 `Label`，并缓存一个 `nodeEnvelope`，表示该 bucket 内所有标签 frontier 的下包络。新标签进来时，先找所有对它“eligible”的 bucket，也就是那些访问集不比它更强、因此有资格去占优它的 bucket，把这些 `nodeEnvelope` 逐个 merge 成一个 `eligibleEnvelope`。如果 `eligibleEnvelope` 在整个定义域上都不高于新标签 frontier，那么这个新标签直接丢掉；如果只在部分区间占优，就允许用 `eligibleEnvelope` 去裁掉新标签 frontier 中被压住的那部分，剩下的 frontier 继续保留。这就是 2011 那种“标签函数不是整条保留，而是可能删掉部分区间”的思路，和你现在 `PiecewiseLinearFunction` 的 `updateDominatedIntervals` / `mergeMinimum` 方向是一致的。

这样做的好处是，第一版已经是真正的 set dominance 了，但它不依赖 graph。坏处只是每次新标签进来时，需要扫描当前 job 下所有 eligible buckets 去临时合成 envelope，速度不会特别极致。不过这一步是完全正确且容易控的，也最适合作为第一版。等这版跑通后，再把 Luo 2016 的 dominance graph 接上去，把“每次线性扫描 eligible buckets”改成“沿图查询 node-level envelope 缓存”，本质上只是优化 eligible envelope 的获取方式，而不是重写 dominance 语义。

如果后面要接 Luo 风格 dominance graph，我建议也不要把它理解成“另一套不同的占优规则”，而要理解成“同一套 set dominance 的缓存图结构实现”。图上的 node 依然只对应某个 `jid` 下某个离散状态 bucket，比如“当前末端 job 为 `j`，访问集签名为 `V`”这一类可比较状态；node 上缓存 `nodeEnvelope`，边表示“这个 node 的标签集合对另一个 node 是 eligible 的一部分”。这样 graph 的职责就很清楚了：它不是拿来替代真实标签的，而是拿来加速“所有 eligible labels 的下包络”这件事。第一版可以先把 `LabelNode` 和 `DominanceGraph` 类留出来，但只实现 scan-based 查询；后面再把 node-level cached envelope 和 ancestor/descendant 传播补进去。

所以第一版推荐顺序很明确。命名上保留原始风格，主类仍然叫 `GC` 和 `Label`，方法名仍然可以是 `Initialize`、`Extend`、`IsDominate`、`UpdateReach` 这一套；实现上先做单向 forward labeling；标签成本改成 `PiecewiseLinearFunction frontier`；真实标签不物理 merge；占优采用“按 job 分桶 + exact signature bucket + eligible envelope 扫描”的 set dominance；dominance graph 只先预留类和接口，不在第一版主流程里强依赖。这样既模仿了原始 VRP 单向 `GC` 的骨架，又没有把 2011 的函数标签、Luo 的 set dominance 和路径恢复这三件事混成一团。
