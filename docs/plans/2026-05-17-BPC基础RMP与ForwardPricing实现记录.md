# BPC 基础 RMP 与单向 Forward Pricing 实现记录

## 2026-05-25：移除 `mergeMinimum` 单点接触拼接分支

在修正 `Data.setPenaltyFunctions()` 后，重新复核了双向 pricing 的 dominance 输入不变量。当前 `GCBidirectional` 已经在 `insertForward()` 和 `insertBackward()` 入口把退化到 `T^mid` 的 single-point label 分流到专门的 single-point store，不再让这类 label 进入普通 `PaperDominanceGraph`。因此普通 forward label 入图时应满足物理定义域为 `[ell,T^mid]` 且 `ell<T^mid`，普通 backward label 入图时应满足 `[T^mid,rho]` 且 `rho>T^mid`。同一方向的 dominance envelope 合并只发生在这些普通 label 或由它们构成的 envelope 之间，所以 `mergeMinimum()` 在 BPC 双向路径里应当总有正长度公共区间。

由此，前面为 `start == end` 加的 `mergeMinimumAtTouch()` 分支不再是有效补丁。它会把两个只在边界点接触的函数按左右顺序拼成一个 envelope，反而掩盖了上游半域或 single-point 分流不变量被破坏的问题。当前已删除该分支及其私有 helper，让 `start == end` 和 `start > end` 一样触发 `mergeMinimum requires positive overlap` 异常。这样后续如果再次出现点接触，就会直接暴露为上游构造问题，而不是在 `mergeMinimum` 内部静默修补。

验证上，重新执行了 `javac -encoding UTF-8 -cp src src/Common/PiecewiseLinearFunction.java src/Basic/Data.java`，编译通过。更宽的 `GCBidirectional` 编译仍受本地 CPLEX `ilog.*` classpath 限制，不能在当前环境完整编译。

随后又用 `-sourcepath __no_such_source_path__` 绕开 `Utility.java` 对 CPLEX 源码重编的依赖，执行 `PWLFTesting.PiecewiseLinearFunctionPropertyTest` 复核 `mergeMinimum()` 行为。结果为 `passed=23, warnings=2, failed=3`：普通 overlap、`[a,T]` 同右端契约、forward/backward 随机 frontier sweep 都通过；失败项集中在完全不相交定义域 `start > end` 的旧通用风险测试和一次随机 disjoint 样例。也就是说，这次删除 `start == end` 点接触特判没有破坏 BPC 双向 dominance 所需的正重叠合并语义，但公共 `mergeMinimum()` 是否还要支持完全 disjoint union-lower-envelope 仍是另一个未统一的工具函数契约问题。当前双向 BPC 不应依赖这个 disjoint 语义；如果未来要把 `mergeMinimum()` 恢复成通用 lower-envelope union 操作，应单独重写并验证 disjoint/touch 行为，而不是在 BPC 路径里用点接触补丁掩盖 single-point 分流问题。

## 2026-05-23：复核双向 pricing 流程与旧 VRP 双向框架的一致性

本次重新对照当前 `GCBidirectional` 和旧 VRP 的 `GCBDT/GCNGBB` 后，确认当前双向 pricing 的外层流程已经按前面讨论的口径执行。当前实现先在 `initialize()` 中建立 forward/backward 两侧的未处理队列 `FWUL/BWUL` 和按端点分组的已处理表 `FWTL/BWTL`，forward 从虚拟起点 label 出发，backward 从虚拟终点 label 出发；随后 `solve()` 中交替处理两侧队列，出队时如果 label 已被后续 label 支配则直接跳过，否则先尝试生成列或 join，再做同方向扩展。扩展出的新 label 先进入同端点表做 dominance 检查，若被现有 label 支配则标记并丢弃；若能支配已有 label，则把旧 label 标记为 dominated 并从表中移除；未被支配的新 label 才进入未处理队列。

这个生命周期和旧 VRP 的双向框架是一致的。旧 `GCBDT/GCNGBB` 的核心也是两侧队列、两侧 table、同端点 dominance、被支配 label 出队跳过、两侧扩展和最后 join。当前 TWET 版本和旧 VRP 的不同主要是问题语义差异，而不是框架差异：旧 VRP 的 join 是同一个中间客户点拼接，TWET 这里按论文使用 crossing arc `(i,r)` 拼接；旧 VRP 的 label 成本是标量时间/容量资源，TWET 的 label frontier 是分段 reduced-cost 函数；旧 VRP 的可达性来自硬时间窗/容量 bitset，TWET 还要叠加动态硬时间窗、半域 `[ell,T^mid]` / `[T^mid,rho]` 和函数 dominance。这些差异不改变外层流程。

按用户强调的两个细节复核，当前代码也已经对齐。第一，forward label 的右侧边界裁到 `T^mid`，backward label 的左侧边界从 `T^mid` 开始，扩展阶段不额外给半域外补 `big_M`；新增 job 的硬窗只体现在 job penalty 的 `setDomain(hStart,hEnd,true)`。第二，backward 从虚拟终点首次扩展到真实 job 时没有 setup/processing 平移，因为当前时间变量就是这个 job 自己的完成时间；普通 backward 扩展才会按 successor 的 processing/setup 反向平移。第三，`cropToInterval()` 和 join projection 已经允许 `T^mid` 单点常数段，避免半域刚好退化到边界点时被误删。

因此当前结论是：如果不把 “join 是同点还是 crossing arc” 视为流程差异，当前 TWET 双向 pricing 的主框架已经和旧 VRP 的双向 labeling 框架一致；当前仍未纳入的是旧 `GCNGBB` 的 NG/DSSR、complete/2-cycle bound、SRI cut 等强化机制，这些属于后续增强，不影响当前 elementary no-cut 双向框架判断。

补充说明一次“normalize 后再裁到 `T^mid`”的含义。`add()` 本身只会取两个函数的公共定义域，它并不知道当前是 bounded bidirectional，也不知道 `T^mid` 是算法人为设定的半域边界。forward label 经过 `shiftX(delay) + add(jobPenalty) + fixed reduced-cost` 后，公共定义域右端可能仍然大于 `T^mid`，例如原 forward 半域 `[ell,T^mid]` 平移后会变成 `[ell+delay,T^mid+delay]`。因此双向算法还必须额外把保存域裁回 `[ell,T^mid]`。单向 `GC` 不做这个半域裁剪，它只做 `normalize(FORWARD)`，并继续保留到全局右端 `T/CmaxH` 的定义域。

这里的裁剪不是为了替代硬时间窗，也不是因为 `add()` 没有改变定义域；它只是为了让 forward/backward 分别只保存自己负责的一半时间轴。硬时间窗仍然由新增 job penalty 的 `setDomain(hStart,hEnd,true)` 表达，`add()` 之后的公共域变化只反映函数相加的数学定义域，不等于双向半域截断。

进一步澄清：这一步不能类比单向里“自然被 Cmax 卡住”。单向 forward 的 job penalty 定义域本身就是 `[0,CmaxH]`，所以前缀函数 shift 到 `[delay,CmaxH+delay]` 后，再和 job penalty 相加，公共定义域会自然回到不超过 `CmaxH`。但双向 forward 的人为半域右端是 `T^mid`，而 job penalty 仍是全局 `[0,CmaxH]` 上的函数，不是 `[0,T^mid]` 上的函数；因此相加后只能自然卡到 `CmaxH`，不能自然卡到 `T^mid`。如果不额外裁剪，forward label 会越过半域进入右半轴，破坏 bounded bidirectional 的保存域约定，也会让后续 join 的半域公式不再干净。

关于 `job -> sink` 这条虚拟终点弧，当前代码不能简单认为它没用。列的真实序列虽然只存 job 集合和顺序，但在 master 的 arc branching 语义里，`i -> sink` 表示 job `i` 是某条内部机器列的最后一个任务。`TWETColumn.visitsArc()` 明确把最后一个 job 到 `sinkId` 视为该列访问的弧；`ArcBrancher` 也会扫描包含 `sink` 的 arc 值。因此如果某个节点分支到了 `i -> sink`，RMP 会有对应 arc 分支约束，其 dual 就需要在 pricing reduced cost 里扣掉。没有这类分支时，`lp.getArcDual(i,sink)` 返回 0，所以这项不会影响根节点和普通节点。

进一步优化后，当前代码已经不再依赖“扩展后再裁一次半域”的写法。做法是把半域边界提前写进每轮动态 job penalty：forward 使用 `buildForwardHalfPenalty()`，先对 job penalty 做动态硬窗 `setDomain(hStart,hEnd,true)`，再物理裁到 `[0,T^mid]`；backward 使用 `buildBackwardHalfPenalty()`，同样先写动态硬窗，再裁到 `[T^mid,CmaxH]`。这样扩展时 `add()` 的公共定义域会自然满足半域约束：forward 不会越过 `T^mid`，backward 不会落到 `T^mid` 左侧。

这和单向“自然被 Cmax 卡住”的思路是一致的，只是双向把 Cmax 换成了方向相关的半域 job penalty。需要注意的是，动态硬窗仍保留为 `big_M` 窗外语义，而不是物理裁成 `[hStart,hEnd]`；因此 forward 仍可由 prefix-min 表达右侧等待闭包，backward 仍可由 suffix-min 表达左侧等待闭包。本次修改后，forward/backward 扩展中的额外 `cropToInterval(..., T^mid)` 被删除，半域限制由预计算好的方向化 penalty 负责。

验证上重新编译 `GCBidirectional.java`、`PricingAlgorithmComparisonTest.java` 和 `SmallBPCBatchTest.java`。`HEU.PricingAlgorithmComparisonTest` 仍保持 12/12 轮单向 exact 与双向 exact 最优 reduced cost 一致，平均时间约为 forward `2.58 ms/round`、bidirectional `2.50 ms/round`；`HEU.SmallBPCBatchTest` 仍保持 8/8 小例与 ArcFlow 对拍一致，tariff 分支诊断例通过。说明把半域边界提前写入 job penalty 后，行为与原实现一致，同时扩展阶段更接近用户要求的“像单向一样由 add 的公共定义域自然卡边界”。

## 2026-05-22：旧 VRP 中 GCNGB 与 GCNGBB 的 bound / DSS 流程区别

旧 VRP 代码里 `GCNGB` 和 `GCNGBB` 都带有 `ng-route` 思想，但它们不是同一个层次的双向算法。`GCNGB.java` 文件头写的是 `ng-route with complete bound`，并特别注明这种 complete bound 不能直接和 bounded bidirectional search 组合，因为在 bounded 双向搜索里这个 bound 不再是完整的。这里的关键不是“ng-route 不能和双向 labeling 共存”，而是“`GCNGB` 那套单向扩展阶段使用的 complete-bound 剪枝公式不能直接搬到半程双向 join 里”。

`GCNGB` 的流程更准确地说是“交替方向的完整扩展 + 对侧 complete bound 剪枝”。它维护 forward 和 backward 两套 label 队列，也会交替执行 `FWExtend()` 和 `BWExtend()`，但每次真正扩展时仍是一个方向从源到汇或从汇到源完整延伸；当 label 到达终点时可以直接生成 route。它没有像 bounded bidirectional 那样在中间点执行 `Join()`。它的 bound 表来自上一轮对侧已经生成的完整 label 集，例如 forward 扩展时用 `m_bw_bound[cid][remainingTime]` 估计从当前点继续到终点的最优剩余 reduced cost；如果当前 label 的 reduced cost 加这个对侧完整 bound 已经不可能为负，就剪掉。若发现 ng-relaxation 下的重复路径，则记录 duplicate cycle，再通过 `UpdateNGSet()` 扩大 ng-set，下一轮重新搜索。这套逻辑依赖一个前提：对侧 bound 表代表“从当前点到终点的完整补全能力”。

`GCNGBB` 才是旧代码里真正的 bounded bidirectional 版本。它同样使用 ng-route，并且用 DSSR/decremental state space 处理 ng 放松下产生的重复客户。流程是先构造多类 bound 表，包括 forward/backward time bound、forward/backward capacity bound 以及带 SRI 修正的 bound 变体；然后在每一轮 DSS 循环里执行 `FWExtend()`、`UpdateFWBound()`、`BWExtend()`、`UpdateBWBound()`，最后通过 `Join()` 在同一个中间客户点 `cid` 上拼接 forward label 和 backward label。join 时会检查访问集合冲突、容量、时间和 reduced cost；如果拼出来的是负 reduced-cost 但包含 ng 放松造成的重复客户，就不直接作为合法列接受，而是记录最优重复环并更新 ng-set，进入下一轮 DSS 收缩；如果拼出来的是合法 elementary/ng-feasible 路径，才放入本轮候选列池。

因此，`GCNGBB` 不是把 `GCNGB` 的 `m_fw_bound / m_bw_bound` 公式原样塞进双向 join。它重新组织了 bound 的来源、使用位置和 DSS 检查逻辑。bounded 双向 label 本来就只扩展到一部分资源范围，单侧 label 集并不表示“从某点到终点的完整补全”。如果照搬 `GCNGB` 的 complete-bound 剪枝，就可能把本来可以通过中间 join 得到的负 reduced-cost 路径提前剪掉；反过来，也可能把 ng 放松下的重复路径当成安全补全来估计，破坏 DSS 的判断。因此旧代码才把 `GCNGB` 和 `GCNGBB` 分成两个实现。

对当前 TWET-BPC 的含义是：如果后续做 NG + DSSR + 双向函数 label，应该参考 `GCNGBB` 的整体结构，而不是只把 `GCNGB` 的 bound 公式搬过来。TWET 的 label 还带有分段线性函数，剩余成本 bound 不能只按一个标量 reduced cost 粗暴剪枝；至少要证明它是对所有可行完成时间都安全的函数级下界，或者先只保留双向 join 和 DSSR，不加 complete-bound 剪枝。否则在半程 label 上套用完整路径 bound，会有错误剪枝风险。

需要特别避免一个误解：`GCNGBB` 并不是“不使用 complete bound”。源码头部仍然写着 `using the complete bound to cut off some labels`，并且实际维护了 `m_ft_bound/m_bt_bound/m_fc_bound/m_bc_bound` 以及带 SRI 修正的 bound 表。准确说法是：`GCNGBB` 使用了 complete-bound 思想，但它不是直接复用 `GCNGB` 的 `m_fw_bound/m_bw_bound + CheckBoundFW/BW` 公式，而是为了 bounded bidirectional search 重新构造了 forward/backward、time/capacity、SRI/no-SRI 多套 bound，并把它们放在半程 label 扩展、bound 更新和 join/DSS 检查这一整套流程中使用。

两者 bound 的核心区别可以概括为五点。第一，来源不同：`GCNGB` 的 `m_fw_bound/m_bw_bound` 主要来自上一轮另一方向已经生成的终止 label 集，表示从某个客户点继续补到终点或起点的经验最优补全；`GCNGBB` 则先用 `BoundFTExtend/BoundBTExtend/BoundFCExtend/BoundBCExtend` 构造时间和容量维度的动态规划式 bound，再用已经生成的 bounded labels 通过 `UpdateFWBound/UpdateBWBound` 进行修正。第二，含义不同：`GCNGB` 的 bound 更接近“单向完整补全 bound”，扩展当前 label 时把对侧 bound 当作到终点的完整剩余成本；`GCNGBB` 的 bound 是服务于半程 label 与中间 join 的安全估计，不单独承担恢复完整 route 的职责。第三，维度不同：`GCNGB` 主要按客户和剩余时间建表；`GCNGBB` 同时维护时间 bound、容量 bound、SRI 修正版 bound，以及二环避免用的 second-best 信息。第四，使用位置不同：`GCNGB` 在单方向扩展时直接通过 `CheckBoundFW/BW` 剪 label；`GCNGBB` 在 bounded 前后向扩展、bound 更新和 join 前后共同使用这些 bound，并且 join 后还要进入 DSSR 重复客户检查。第五，安全性前提不同：`GCNGB` 安全依赖“对侧表能代表完整补全”；`GCNGBB` 安全依赖“半程 label、bound 表、join、DSSR 更新”这一整套流程共同成立。因此不能把 `GCNGB` 的公式孤立移植到 `GCNGBB` 或当前 TWET 的双向函数 label 中。

`GCNGBB` 的 bound 计算可以理解成两层。第一层是预计算的资源维动态规划 bound：`BoundFTExtend` 从 depot 按时间向前推，得到到达客户 `i` 且消耗时间 `t` 的较好 reduced cost；`BoundBTExtend` 从 sink/depot 反向按时间推；`BoundFCExtend` 和 `BoundBCExtend` 则把时间维换成容量维。每次转移大体是“当前 bound + 距离成本 - arc dual - customer dual”，再检查时间窗、可行弧和资源容量。随后做单调化处理，使更宽松资源下的 bound 不比更紧资源差。第二层是用实际 bounded label 修正 bound：`UpdateFWBound` 和 `UpdateBWBound` 扫描已经生成的 forward/backward labels，把这些真实 label 的 reduced cost 写入对应客户和资源位置，再和预计算 bound 取一个保守组合。这样既有快速 DP bound，也有当前搜索实际生成 label 带来的更紧信息。

SRI 相关的 bound 是为了处理 subset-row cut 的 reduced cost。代码里同时维护 no-SRI 和 SRI 版本，例如 `m_bt_bound` 与 `m_btsr_bound`。扩展 forward label 时先用 `lbcost + m_bt_bound + mu[i]` 做普通 bound 检查，再用 `lbcost_nosr + m_btsr_bound + mu[i]` 做带 SRI 修正的检查；backward 和容量维也是同样结构。这样做的原因是 SRI cut 的贡献不是简单的单条弧或单个客户 dual，路径中某个集合被访问到第二个客户时才触发，因此代码需要维护 `sr_count` 和对应的 SRI bound 版本，避免 bound 检查漏掉 cut dual 的影响。

`m_sec_bound` 和 `m_bd_fid` 是为了构造 2-cycle-free bound，不是 DSSR 的 ng-set 本身。以 `BoundFTExtend` 为例，若当前状态是从 `cid` 转移到 `i`，代码会检查 `i != m_bd_fid[cid][t]`。如果最优 bound 到达 `cid` 的上一跳本来就是 `i`，那么继续走 `cid -> i` 就会形成 `i -> cid -> i` 的二环。为了避免 bound 被这种不允许或不希望使用的二环压得过低，代码在这种情况下不用最优 bound，而改用 `m_sec_bound[cid][t]`，也就是“上一跳不是 i 的第二好 bound”。这只是 bound 表内部的局部二环处理。

ng-set / DSSR 处理的是更一般的重复客户问题。label 扩展时根据 ng-set 保留有限记忆，允许某些非记忆客户在 ng-route 放松下被重复访问；join 后如果发现生成的负 reduced-cost route 中有重复客户，就把这个 route 记录为 `m_best_cycle`，然后 `UpdateNGSet()` 找出重复客户之间的环，把环内相关客户加入彼此的 ng-set。下一轮搜索时这些重复会被记忆约束禁止。也就是说，2-cycle-free bound 是为了让 bound 表不要被最简单的二环污染；ng/DSSR 是为了逐步收紧整个 labeling 的非 elementary 放松。

从实现语义上看，`BoundFTExtend/BoundBTExtend/BoundFCExtend/BoundBCExtend` 可以理解成一轮“松弛的标量 DP labeling”，但它不是正式 pricing label-setting。它不保留完整访问集合、不做 dominance graph、不恢复列，只在每个 `(客户, 时间)` 或 `(客户, 容量)` 状态上保留一个最优标量 bound，外加一个用于避免二环的 second-best。因此它比真正的 elementary/ng label 更松，作用只是给后续剪枝提供一个乐观或修正后的成本界。

双向扩展后仍然可以更新 bound，是因为 bound 表描述的是“到达某个中间状态的成本界”或“从某个中间状态继续的成本界”，不是必须已经形成完整路径。比如 `FWExtend()` 生成了一个到达客户 `cid`、时间为 `t`、载重为 `w` 的 forward bounded label，即使它还没有到终点，它也给出了一个真实前缀成本。`UpdateFWBound()` 就把这个前缀成本写入 `m_ft_bound[cid][t]` 和 `m_fc_bound[cid][w]` 的临时数组，再和原来的 DP bound 组合并单调化。`BWExtend()` 后的 `UpdateBWBound()` 同理，用 backward bounded label 去修正 backward bound。随后另一侧扩展或 join 再使用这些更新后的表。也就是说，半程 label 不能单独作为列，但可以作为某个中间状态的成本信息来收紧 bound。

`GCNGBB` 的 DSSR 停止逻辑也要区分“找到负列”和“证明没有负列”。在每一轮中，`Join()` 会拼接 forward/backward label。如果 reduced cost 为负且没有重复客户，则直接加入本地 `pool`；如果 reduced cost 为负但存在 label 自身 duplicate 或前后向访问集合冲突，则不加入 `pool`，只在它优于当前 `m_min_cost` 时记录为 `m_best_cycle`，用于后续 `UpdateNGSet()`。一轮结束后先执行 `UpdateNGSet()`，然后判断：如果 `pool.GetSize() > 0`，说明已经找到了可加入主问题的 elementary/ng-feasible 负列，就停止本次 DSSR 循环并返回这些列；如果没有 pool 且也没有 duplicate，则说明当前 ng 放松下没有负列，可以结束；如果没有 pool 但存在 duplicate，则继续扩大 ng-set 并重跑。这意味着：当松弛最优或当前发现的负 route 是非 elementary 且没有同时找到合法负列时，会继续 DSSR 收紧；但只要本轮已经找到合法负列，就不会为了证明“本轮最优 elementary route”继续收紧。

最终加入主问题的列只来自 `pool`，也就是通过重复客户检查的负 reduced-cost route。非 elementary / duplicate route 不会被加入 LP，它们只作为 DSSR 更新 ng-set 的证据。加入数量由 `data.m_configure.addin_size` 控制：如果 `pool` 大于该上限，会先排序，然后把不在全局 pool 中的 route 逐条加入 `gn_index`，直到 `gn_index.size() >= addin_size`。因此一次 pricing 不是只加最优一条，也不是加所有找到的列，而是最多加 `addin_size` 条负 reduced-cost 的合法列。

这里的“合法列”在旧 `GCNGBB` 里不是“只满足 ng-memory 的非 elementary route”。label 扩展时确实使用 ng-route 放松：`m_memory` 只保留 ng-set 内的有限记忆，所以搜索过程中可以产生真实访问重复的 label；但代码同时维护 `m_visit` 和 `m_duplicate`，用于记录实际访问过哪些客户以及是否发生真实重复。`Join()` 加列前检查 `label.m_low_duplicate / m_high_duplicate`、`lbf.m_low_duplicate / m_high_duplicate`，以及前后向 `m_visit` 的交集。只要这些检测表明 route 真实重复客户，就不会 `pool.AddRoute()`。因此，进入主问题的列必须是 elementary 意义下没有重复客户的 route；ng-feasible 但非 elementary 的 route 只用于发现重复环并更新 ng-set。

## 当前实现

本次先把能够真正运行的第一版 BPC 主链路搭起来，目标不是一次性完成完整 branch-price-and-cut，而是先形成“RMP 能解、pricing 能生成负 reduced cost 列、列能回到 RMP、结果能输出和校验”的闭环。当前实现参考 `parallel_machine_scheduling_with_due_window.pdf` 中 set-partitioning/SP2 的建模思路，以及前面讨论过的“每个 dominance graph node 保留真实 label 集合，同时维护一个聚合 envelope 用于集合占优”的方案；旧 VRP BPC 代码里的 `GC`、`UL/TL`、按末端节点组织 label、arc branching 这些结构也尽量沿用了相近的命名和流程。

RMP 现在由内部加工列 `lambda` 和外包变量 `y_j` 组成。覆盖约束写成“包含任务 j 的内部列之和 + `y_j = 1`”，机器数量约束仍然约束内部列数量，外包成本通过 `G(sum b_j y_j)` 的分段线性变量进入目标。这里的 tariff 分段目前按 LP relaxation 方式接入，主要服务于列生成中的对偶值和下界计算；如果后续要把外包分段选择做成严格整数结构，还需要在 B&B 层补额外分支或更强建模。

pricing 先实现了最基础的单向 forward labeling，不含 SRI cut、ng-route、DSSR、双向拼接和 partial dominance。`Label` 保存真实路径信息，包括末端任务、父 label、访问集合、可达集合和分段线性 frontier；这样后续生成列时可以恢复具体任务顺序。`DominanceNode` 内部保存多个真实 label，并额外维护这些 label frontier 的下包络；`DominanceGraph` 使用这个聚合 envelope 做完整集合占优。这个设计和原文中“graph node 上有一个聚合 label”的写法不同：我们不把多条真实路径物理合并成一条 label，因为 TWET 的 setup time、setup cost 和最终列恢复都依赖顺序。聚合函数只用于占优判断，真实 label 仍然保留。

当前 pricing 的扩展逻辑是：从父 label 的 frontier 出发，按 `setup time + processing time` 做 `shiftX`，再加上当前任务的 TWET 惩罚函数和 setup cost、job dual、arc dual 等常数项，然后按 forward 方向 normalize。任务自己的硬时间窗使用 `setDomain(start, end, true)` 处理，窗口外补成 `big_M` 段，而不是物理删除定义域。这一点沿用前面分段线性函数讨论得到的约束：label 函数尽量保持右端到全局 `T`，避免后续 merge/dominance 时产生多个内部单点或定义域断裂。

分支暂时只接入最简单的 arc branching。右分支要求选中的弧出现，并按旧 VRP `BranchD` 的思路禁止同一出点的其他出弧和同一入点的其他入弧；左分支禁止该弧。这个版本足够支撑第一版框架跑通，但还没有处理“只在外包变量或 tariff 分段变量上分数”的分支情况。如果 RMP 分数解没有可分支弧，当前树搜索会把它作为暂时无法继续分支的节点处理，后续需要补外包变量分支或机器数/列分支。

## 验证结果与边界

本次做了 focused compile，编译范围为 `src/Common`、`src/Basic`、`src/HEU`、`src/Output` 和 `src/TWETBPC`，编译通过，仅有旧 API deprecation 提示。没有跑全量 `src` 编译，因为旧 `src/BPC` 参考框架仍然和当前项目不完全兼容，直接纳入会引入无关失败。

另外构造了一个 60 任务、3 台机器、带 setup cost 和外包 tariff 的 smoke 数据，用 `TWETBPCSolver` 跑根节点列生成和结果输出。结果为 `ROOT_PROCESSED`，incumbent 为 `40317.0`，best bound 为 `40316.99999999999`，生成列数为 `335`。这说明当前 RMP、exact forward pricing、列池更新、输出和外包校验链路已经能跑通。

需要明确的是，这还不是完整 BPC。当前没有 SRI cut，没有 cut-aware pricing，没有 partial dominance，没有 bidirectional labeling，也没有 ng-route/DSSR 之类的松弛强化。分支方面也只有 arc branching；如果要求严格求完整 B&P 树，后续还需要补人工列/分支节点可行性兜底、外包变量分支、tariff 分段整数性处理，以及更完整的输出统计。

## 2026-05-18：启发式定价改为旧 VRP GCTabu 框架

本轮按用户要求把启发式定价进一步向旧 VRP 的 `GCTabu` 框架靠齐。旧代码的核心流程不是替代 exact pricing，而是在 exact pricing 前从当前 RMP 里挑一批低 reduced cost route 作为 seed，然后围绕 seed 做 remove/add/exchange tabu 搜索，形成一个本地负 reduced-cost route pool，排序后只加入少量优质列。当前 TWET 版本沿用这个框架：先收集当前 restricted columns，按 reduced cost 排序取 `heuristicPricingSeedColumns=30` 条种子，对应旧 `m_tabu_cg_size`；每条种子运行 `heuristicPricingTabuIterations=50` 轮 tabu，对应旧 `m_tabu_cg_iteration_number`；tenure 为 `heuristicPricingTabuTenure=30`，对应旧 `m_tabu_cg_tenure`；本地负 reduced-cost 候选池上限为 `heuristicPricingPoolSize=1000`，对应旧 `m_gen_size`；最后排序返回 `maxHeuristicPricingColumns=150` 条，对应旧 `addin_size`。邻域包括删除一个任务、插入一个未访问任务、用未访问任务替换当前任务。

和旧 VRP 不能完全照搬的地方在于 move 评价。旧 VRP 的 route cost 基本是弧成本、容量和硬时间窗状态的增量更新；TWET 的列成本来自分段线性时间惩罚、setup time、setup cost 和硬时间窗预处理，不能直接用旧的局部弧成本公式。因此这次没有再用全序列 `TWETColumnEvaluator` 重算每个候选，而是给每条 seed 建立 forward/backward 分段函数 profile。删除任务时用两段拼接 `merge2Segments` 评价；插入或替换任务时把新任务看作单点 segment，用三段拼接 `merge3Segments` 评价；setup cost 在桥接弧上显式补入。这就保留了旧 `GCTabu` 的搜索结构，同时把候选成本评价换成当前 TWET 启发式中已经验证过的分段函数拼接逻辑。

这里仍然只是启发式加速层，不承担最优性证明。候选列会先检查当前分支节点的 forbidden arc 状态，并跳过当前 RMP 已 active 的重复序列；如果找到负 reduced-cost 列就返回给 `PC`，后面 exact forward pricing 仍会继续兜底。因此它找不到列不会影响严格性，最多影响速度。当前实现还没有把旧 VRP 里更完整的 slack RMP + `FindFeasible` 定向 pricing 复制进来；required-arc 子节点现在仍主要依赖 pool 筛列和 fallback column，复杂分支状态下还需要后续继续增强。

验证方面，本轮做了 focused compile，范围为 `src/Common`、`src/Basic`、`src/HEU`、`src/Output` 和 `src/TWETBPC`，编译通过，仅有旧 API deprecation 提示。随后运行 `TWETBPC.GC.PaperDominanceGraphConsistencyTest`，结果为 `cases=200, insertions=16000` 通过；运行 `PWLFTesting.PiecewiseLinearFunctionPropertyTest`，程序正常结束，保留既有的 PWLF 警告/失败计数，没有出现本轮新增编译或运行错误。另用临时 smoke 程序构造 7 任务、2 机器小算例跑 `TWETBPCSolver`，结果为 `ROOT_PROCESSED, incumbent=23.66, bound=23.660000000000004, columns=21`，说明启发式定价接入后 BPC 主链路仍能跑通。

## 后续计划

下一步最自然的是继续完善 pricing 的质量，而不是马上堆 cut。可以先做三件事：第一，补 pricing 过程的详细统计输出，包括 label 数、dominance 删除数、返回列数和耗时；第二，给 required-arc 分支节点补人工列或更稳的节点可行性处理，避免 restricted master 在分支后因为暂时没有列而提前 infeasible；第三，再考虑把 dominance graph 从当前的完整占优推进到 partial dominance 或更高效的 graph propagation。

## 2026-05-17：CmaxH、硬时间窗与 pricing 剪枝补充

当前如果把某些任务的外包 baseline cost 设成 `big_M`，含义是这些任务不能被外包。RMP 会把对应 `y_j` 上界固定为 0；预处理粗硬窗里 `evaluateOutsourcingCost(big_M)` 也会返回 `big_M`，因此静态硬窗基本退化为 `[0,CmaxH]`，不会因为外包成本不可用而误裁任务。需要注意的是，pricing 里仍然会根据当前 cover dual 计算动态 `H_ij`，所以“外包不可用”只是不使用外包成本收缩静态窗口，并不等于 pricing 完全没有时间窗剪枝。

本次把 `Data` 里的静态 horizon 做了一点收紧：`setPreprocessedHardWindows()` 计算完每个任务的粗硬窗以后，如果所有 `hardWindowEnd[j]` 的最大值小于当前 `CmaxH`，就把 `CmaxH` 收到这个最大右端，然后再构造 job penalty function。这个处理只利用静态外包 baseline 和 setup-cost advantage，不使用 pricing dual。这样做的原因是，窗口外本来已经会被写成 `big_M` 段，不再是有效内部加工完成时间；提前缩短全局 horizon 可以减少后续分段函数和 label 扩展维护的无效尾段。

pricing 中也补了直接时间可行性过滤。之前的逻辑是先尝试 `shiftX + setDomain + add + normalize`，如果结果为空或最小值进入 `big_M` 状态再丢弃。现在在做分段函数操作之前，会先用当前 label frontier 的最早可行完成时间、`setup time + processing time` 和动态 `H_ij` 的右端判断扩展是否必然不可行；同时新 label 的 `reachableSet` 构造也使用同一判断，把明显不可能的下一任务提前排除。这个过滤不替代分段函数求值，只是减少必然失败的扩展。

暂时没有在每轮 pricing 中根据 dual 动态更新全局 `CmaxH`。这样做理论上可能进一步缩小函数定义域，但会带来一个麻烦：同一列生成阶段的全局 horizon 可能随 dual/节点状态变化而变化，容易和分段函数的 `[a,T]` 右端统一假设冲突。当前更稳的做法是保留全局 `CmaxH`，只在扩展 `i -> j` 时动态计算局部 `H_ij`。

## 2026-05-17 BPC 当前实现的效率与严格性边界

当前确认 pricing 阶段暂时不再动态修改全局 `CmaxH`。`Data` 里已经用静态粗硬窗做过一次 horizon 收紧；进入列生成后，`GC` 只在每条扩展弧 `i -> j` 上根据当前 dual、任务外包 baseline 和 setup-cost advantage 计算局部 `H_ij`，再用 `setDomain(hStart,hEnd,true)` 把任务函数窗口外写成 `big_M`。这样做比每轮修改全局 `CmaxH` 保守一些，但可以保持分段函数统一的右端 `T` 结构，避免不同 RMP dual 下反复改变全局定义域带来的边界问题。后续如果要进一步用 dual 收紧 horizon，更适合先作为扩展级别或 label 级别的局部剪枝，而不是直接覆盖 `data.CmaxH`。

`DominanceGraph` 当前实现是正确优先、效率其次。插入新 label 时，会扫描当前 terminal job 下所有 node，筛出 reachable set 是当前 reachable set 超集的 eligible node，把这些 node 的 aggregate envelope 逐个 merge 成一个总 envelope，再判断是否完整支配新 label。若新 label 没被支配，就插入对应 reachable-key node；随后再扫描其他 node，看新加入的信息是否能删除后继 node 或其中的部分真实 label。这个逻辑符合当前“完整 set dominance + 保留真实 label 集合”的设定，但每次插入都会重复扫描 eligible node 和重复 merge envelope，后续 label 数多时会明显变慢。可以优化的方向是给 dominance graph 增加 ancestor/descendant 缓存，或者按 reachable set 的包含关系维护索引，避免每次都全表扫描。

`reachableSet` 现在只做一跳可达性过滤。具体来说，`buildReachableSet` 只检查候选任务是否未访问、当前弧是否未被禁止、以及从当前 frontier 最早完成时间直接扩展到该任务是否不超过局部 `H_ij` 和 `CmaxH`。它没有做多步可达性、time-index reachability，也没有接入 Vidal 式子序列摘要。这个设计不会漏掉可行列，因为它只是提前排除直接扩展已经明显不可能的任务；但会保留很多后续几步才发现不可行的候选，因此主要影响效率，不是当前正确性风险。

列生成停止条件在第一版中曾经不够“证明友好”。当时 `GC.solve()` 单轮最多返回 `maxExactPricingColumns` 条负 reduced-cost 列，`PC.solve()` 单节点最多执行 `maxPricingRounds` 轮 pricing；如果轮数耗尽时实际上仍存在负 reduced-cost 列，就会影响节点下界的严格性。后续已经按旧 VRP 代码思路删掉固定轮数上限，`PC` 改为迭代到没有新 active column 为止；单轮加列数上限仍保留在 pricing 侧，用于控制每次返回列的数量。

当前 pricing 输出统计也偏粗。`PC` 只记录 pricing 是否 improved、加了多少列、pool size 和文字 message；`GC` 内部没有暴露生成 label 数、扩展尝试数、时间可行性剪枝数、dominance 删除数、重复列过滤数、返回列数和耗时。后续要分析 BPC 性能时，这些统计很关键，尤其是 dominance graph 和 reachableSet 优化之前，需要知道瓶颈到底在 label 扩展、分段函数操作、dominance merge 还是 RMP 重解。

分支层还有几个严格性边界。第一，required-arc 分支节点下，`LP` 会把 required arc 写成等式约束，要求当前 RMP 中选中的列必须覆盖该弧；但如果 restricted pool 里暂时没有任何包含该弧的列，RMP 可能直接 infeasible。理论上 pricing 有机会生成满足 required arc 的列，但前提是 RMP 能先求出 dual；因此后续需要人工列、定向 pricing 兜底，或在构造节点时先补一批满足 required arc 的列。第二，当前只有 arc branching；如果分数性主要来自外包变量 `y_j` 或 tariff segment 变量，而内部列的 arc 值已经整数，`ArcBrancher` 会返回没有可分支弧，树就会停在 `closed_without_branch`。后续需要增加外包变量分支、tariff segment 分支，或者对机器数/列变量的补充分支策略。

外包 tariff 当前在 RMP 中仍是 LP relaxation。做法是用 `outsourceSegmentActive` 和 `outsourceSegmentBaseline` 表示 `G(sum b_j y_j)` 的分段线性结构，并且这些变量都是连续变量。如果 `G` 是凹分段函数，这个松弛可以作为下界，但不等于完整整数建模；分段选择可以被分数化，从而给出比真实整数外包成本更低的 master bound。当前第一版先接受这个作为松弛，后续如果要严格求整数最优，需要补 segment 选择的分支、SOS2/特殊有序结构，或其他强化约束。

动态 `H_ij` 的公式目前能跑，但还需要单独对拍。当前 `gamma = setupCostAdvantage(i,j) + min(coverDual_j, outsourcingBaseline_j)`，再按 earliness/tardiness 权重转成局部完成时间窗口。这个思路来自“如果某个完成时间已经比外包或 dual 能解释的上界更差，就不值得扩展”的剪枝逻辑。风险在边界情况：cover dual 为负时，`gamma` 可能为负并导致窗口变窄甚至为空；任务不可外包时 baseline 是 `big_M`，公式退化为主要由 dual 控制；setup-cost advantage 很大时，窗口会被放宽。当前这些情况没有系统小算例对拍，因此后续应专门构造 dual 为负、外包不可用、setup-cost advantage 较大的 pricing 单元测试，验证不会误删真实负 reduced-cost 列。

## 2026-05-17：对照论文伪代码和旧 VRP 代码后的修正判断

这里需要修正前一段分析里对 dominance graph 的表述。当前 `DominanceGraph` 能作为保守的完整占优实现使用，但它不是 `parallel_machine_scheduling_with_due_window.pdf` 里写的 dominance graph 伪代码。论文里的图不是每次全量扫描所有 eligible node，而是维护 reachable set 包含关系图：每个 node 对应一个 reachable set，保存本节点真实 label 集合的下包络 `f_u`，同时维护来自直接前驱传播下来的 `h_u` 和综合支配包络 `g_u=min(f_u,h_u)`。新 label 到来时，先通过 roots/successors 找到 terminal candidate nodes `R(L)`，用这些节点的 `g_u` 判断支配；插入新 node 后，只沿 successors 做局部传播和删除。也就是说，论文的核心是“用图结构把全量 eligible 扫描变成沿包含关系的局部查找和传播”。

当前代码的做法更简单：`insertOrDominate()` 里直接扫描当前 terminal job 下所有 node，筛出 reachable set 是当前 reachable set 超集的 node，把这些 node 的 aggregate envelope 全部 merge 成一个 envelope，再判断是否完整支配新 label。插入后，`propagateAfterInsert()` 又循环扫描其他 node，看新信息能否支配它们。这个逻辑不应该丢最优解，因为它只做完整支配，没做 partial dominance；但它和论文伪代码不一样，效率上也不对。后续要按论文重构，需要补 `nodeByReachableSet`、roots、predecessors、successors、`f_u/h_u/g_u`，以及 `FindTerminalNodes`、`InsertOrMerge`、`PropagateAndTrim` 三套流程。因为 TWET 列需要恢复具体顺序，node 里仍然要保存真实 label 集合；论文里的聚合 label 在这里应理解为支配判断用的 envelope，而不是替代真实路径。

`reachableSet` 这一点目前不需要扩大。论文里更新 reachable set 本身就是一跳过滤：从当前末端 job 出发，检查某个候选 job 是否未访问、弧是否允许、直接接上以后是否还可能落在对应的 `H_ij` 内。当前 `buildReachableSet()` 做的也是这一类一跳判断。多步可达性、time-index reachability 或 Vidal 式摘要都只是后续加速，不是这里必须补的正确性条件。

列生成停止条件已按这个判断修正。旧 VRP 代码里的 `addin_size` 对应的是“单轮最多加入多少列”，这和当前 `maxExactPricingColumns` 类似；但旧 `PC` 没有 `maxPricingRounds` 这种节点内固定轮数上限。当前实现已经删除固定轮数上限，节点内流程改为 heuristic pricing 和 exact pricing 反复跑，直到本轮没有新的 active column 被加入。这里仍要保留一个工程细节：如果 pricing 找到的是重复列，`Pool` 会返回已有列 id，`PC` 不把它计入新 active column，避免无意义空转。

分支后 restricted master infeasible 的处理也要参考旧 VRP 代码。旧 `BranchD.UpdateRouteSet()` 在子节点没有可行列时，不是直接关闭节点，而是先用 slack 暂时保持分支约束可处理，然后调用启发式和精确定价的 `FindFeasible` 生成满足该分支状态的 route，再从这些 route 中筛选一批 reduced cost 较好的列作为子节点初始列。当前 TWET 版本只把 required arc 写成 RMP 等式，如果当前 restricted pool 里没有包含该 required arc 的列，RMP 可能还没来得及 pricing 就直接 infeasible。因此后续需要做一个子节点可行列兜底：可以先做定向 pricing/构造列，强制满足 required arcs；或者临时人工列/slack 保持 RMP 可解，再用 pricing 替换。直接把这种节点判 infeasible 不符合旧代码思路，也可能错误剪掉可行分支。

外包变量分支可以先不做，但要分清两个层次。如果所有内部调度 arc 值都已经是整数，那么在覆盖约束 `internalCover_j + z_j = 1` 下，`z_j` 通常会自动变成 0 或 1：内部列对 job 的覆盖是 0/1，arc 整数意味着内部路径选择结构已经整数，任务要么被内部列覆盖，要么只能由外包变量补足。因此 arc 分支先做完以后，`z_j` 大概率会随之整数化。这里先不单独对外包变量分支是可以接受的。

但 `z_j` 整数不代表 tariff 的分段变量也会整数。当前 RMP 里的 `outsourceSegmentActive` 和 `outsourceSegmentBaseline` 是连续变量，它们表达的是 `G(sum b_j z_j)` 的 LP relaxation。即使 `q=sum b_j z_j` 已经固定为整数 baseline，总量仍然可能被分数地分配到多个 tariff segment 上；如果 `G` 是凹分段函数，这个松弛可能给出比真实单段选择更低的外包成本。因此 tariff segment 是否整数，不能靠 `z_j` 整数自动保证。后续如果要求严格整数外包成本，需要加 segment 选择分支、SOS/特殊有序结构，或者其他强化建模。

关于动态 `H_ij`，当前实现和论文公式在结构上是一致的：`gamma = B_ij + min(π_j,b_j)`，再用 earliness/tardiness 斜率把它转成 `[h_ij,\bar h_ij]`，扩展时用 `shiftX(s_ij+p_j)`、任务惩罚函数窗口外补 `big_M`、再加 setup cost、job dual 和 arc dual。只要 dual 符号、`B_ij`、任务惩罚函数和外包 baseline 的定义没有错，这个部分不应因为实现方式本身丢失最优列。后续需要对拍的是公式输入值是否一致，而不是把 `H_ij` 逻辑本身当作优先怀疑对象。

## 2026-05-17：本轮 BPC 框架修正落地

本轮按前面的修正判断先做四件事。第一，保留原来的 `DominanceGraph` 作为全量扫描版，同时新增 `PaperDominanceGraph` 和 `PaperDominanceExactPricingEngine`。新图结构按论文伪代码维护 reachable set 的包含关系，显式保存 roots、predecessors、successors，以及每个 node 上的 `f_u/h_u/g_u` 三类 envelope；但为了适配 TWET 列需要恢复具体任务顺序的事实，每个 node 仍保存真实 label 集合，聚合 envelope 只用于占优判断，不替代真实路径。这样后续可以直接比较“旧全扫版”和“论文图传播版”的效率差异。

第二，删除节点内固定 pricing 轮数上限。`PC.solve()` 现在不再使用 `maxPricingRounds`，而是反复调用 pricing，直到本轮没有新的 active column 被加入为止；单次 exact pricing 返回列数仍由 `maxExactPricingColumns` 控制，默认值改为旧 VRP 代码 `Configure.addin_size` 对应的 150。这里要注意一个细节：如果 pricing 返回的是 pool 里已经存在的重复列，`PC` 不再把它当成新列，否则可能出现“有 improved 但没有新 active column”的空转。

第三，补了 SP2 tariff segment 分支。`TWETMasterSolution` 现在会保存 master 中 `outsourceSegmentActive` 的 LP 值；`TariffSegmentBrancher` 会寻找最接近 0.5 的分数 segment 变量，生成 `z_s <= 0` 和 `z_s >= 1` 两个子节点。对应的分支状态保存在 `Node` 中，`LP` 重建变量时通过调整 `outsourceSegmentActive[s]` 的上下界实现约束。该分支只作用在 master 变量上，不改变 private-route pricing 结构，只通过新的 dual 间接影响后续定价。

第四，分支子节点加入了初始列兜底。`Tree` 在把子节点放进队列前，会先从全局 pool 中筛选所有兼容 forbidden arc 的列作为 seed；如果子节点存在 required arc 且当前 seed 中没有任何列覆盖它，就尝试根据 required arc 链构造一条简单 fallback column，并用 `TWETColumnEvaluator` 评估后加入 pool。这不是旧 VRP `UpdateRouteSet()/FindFeasible` 的完整复刻，因为它还没有调用定向 pricing 去系统生成满足分支状态的列；但它已经避免了一类最直接的问题，即 required arc 子节点因为 restricted pool 暂时没有覆盖该弧的列而立刻 RMP infeasible。

当前仍需保留的边界有三点。第一，`PaperDominanceGraph` 只是按论文结构重写了 graph propagation，仍然只做完整占优，不做 partial dominance。第二，required-arc fallback column 只是工程兜底，不是严格的分支节点可行列生成器，后续如果遇到复杂 required arc 组合，仍应补定向 pricing 或人工列/slack。第三，tariff segment 分支已经接入，但外包任务变量 `y_j` 本身暂时没有单独分支；当前优先级是先处理 tariff segment 分数性，然后机器数分支，再 arc 分支。若后续观察到 `y_j` 分数但无可分支 arc，需要再补外包变量分支。
## 2026-05-17：当前 BPC 实现复核结论

本次对照旧 VRP 代码中的 `PC/GC/BranchD` 主流程，以及 `parallel_machine_scheduling_with_due_window.pdf` 里的 SP2、forward labeling 和 dominance graph 思路，对当前 `TWETBPC` 实现做了一轮结构复核。总体判断是：当前版本作为“不带 cut、单向 forward exact pricing、带外包 tariff 分支雏形”的第一版 B&P 骨架是能跑通的，RMP 的覆盖约束、机器数约束、外包 `G(B)` 的 LP relaxation、列池、定价返回列、tariff segment 分支、arc branching 的基本接口都已经接上。但它还不能称为完整严格的 BPC，主要风险集中在 pricing 停止条件、cut 接入、分支子节点可行列兜底和 dominance graph 的等价验证上。

最需要优先处理的是 pricing 的“返回列上限”和全局列池去重之间的关系。当前 `GC` 单轮最多返回 `maxExactPricingColumns=150` 条负 reduced-cost 列，但去重只在本轮内部做；如果这些列已经存在于全局 pool 或已经 active，`PC` 会发现没有新的 active column，然后停止列生成。旧 VRP 代码是在把 route 计入本轮 add-in 数量之前就检查 `pool.Route2ID(route)==-1`，也就是说重复列不会消耗本轮加列上限。当前实现如果遇到大量重复负 reduced-cost 列，有可能在还存在未发现新列时提前停下。这不是 smoke test 一定会触发的问题，但从严格列生成逻辑上看需要修：要么让 pricing 能查询全局 pool 并在计数前跳过已知列，要么在 `PricingResult` 里显式返回“是否达到列数上限、是否完整搜索结束”的状态，让 `PC` 不把“没有新增 active column”简单等同于“没有负 reduced-cost 列”。

cut 相关目前还是框架占位，不是可用的 BPC。`LP.addCuts()` 现在只把 cut id 加进 `activeCutIds`，`buildModel()` 没有真正把 cut pool 里的 cut 写成 CPLEX 约束；`PC` 在 cut loop 里即使将来加了 cut，也只是 re-solve LP，没有像旧 VRP `PC` 那样在 cut 加入后回到 pricing。严格的 branch-price-and-cut 必须在“加 cut 后重新 pricing”，否则 cut 的 dual 不会进入 pricing，RMP 也不完整。当前 cut generator 基本是 no-op，所以现阶段的无 cut B&P 运行不受影响，但这块在接 SRI 或其他 cut 前必须补齐。

分支子节点的可行列兜底也还不够强。当前 `Tree` 在生成 child 时会从全局 pool 里筛选兼容列，并尝试为缺失的 required arc 构造一条简单 fallback column；这比完全不兜底好，但还没有达到旧 VRP `BranchD.UpdateRouteSet()` 的强度。旧代码会通过 slack/启发式/精确定价去为分支节点找可行 route，避免 restricted master 因暂时没有满足 required arc 的列而直接 infeasible。TWET 这里 required arc 可能需要前后插入其他任务才能形成可行序列，简单 required-chain fallback 不一定够。因此后续应补定向 pricing 或人工列/slack 机制，避免误关可行分支节点。

`PaperDominanceGraph` 的方向是合理的：它保留真实 label 集合用于恢复具体列，同时维护 `f_u/h_u/g_u` 这类 envelope 用于集合占优判断，这比把多条真实路径物理合并成一条 label 更适合 TWET，因为 setup time、setup cost 和最终列的 arc pattern 依赖顺序。但这部分逻辑复杂，当前还缺少和旧全扫描 `DominanceGraph` 的等价对拍。后续至少应该在小算例上同时跑 full-scan dominance 与 paper graph dominance，比较生成负列、根节点 bound 和最终列池，确认 graph propagation 没有过度删除 label。

其他边界问题相对次一级。`Tree.solve()` 目前的 status 对 node limit 和 open queue 不够精确，`processedNodes > 1` 基本就返回 `FINISHED`，不适合正式汇报；`incumbentCostFromInitial()` 在没有 `bestSolution` 时只汇总内部列，可能忽略外包初始值，虽然正常 seed 流程通常能提供 bestSolution，但这个兜底不够严谨。focused 编译已经通过，命令范围为 `src/Common, src/Basic, src/HEU, src/Output, src/TWETBPC`，只出现旧 API deprecation 提示。本次没有修改源代码，只记录审查结论。
## 2026-05-18：pricing 上限、required-arc 兜底和状态汇报修正

本轮先处理无 cut B&P 里能直接改好的三件事，cut 接入暂时不动。第一，`GC` 的列返回上限现在不再被当前 RMP 已经 active 的重复列消耗。做法是在 pricing 初始化时收集 `lp.getRestrictedColumnIds()` 对应的序列签名，`tryGenerateColumn()` 恢复出候选序列后先判断它是否已经 active；如果已经 active，就直接跳过，不计入 `generatedColumns.size()`。如果某条列在全局 pool 中存在但还没有 active，仍允许返回给 `PC`，由 `PC` 激活已有列。这和旧 VRP 代码“先查 pool，再计入 addin_size”的思路一致。这个改动主要是效率和停止条件稳健性优化；正常情况下它只是减少重复返回列，严格场景下可以避免“返回列上限被重复列耗尽”后误以为没有新列。

第二，required-arc 子节点的 fallback column 做了增强。原来只根据 required arc 的连通链构造一条裸序列，如果这条裸序列不可行，就没有进一步兜底。现在会先构造 required arc 所在的强制链，然后尝试把这条链插入全局 pool 中已有列的不同位置，只要不重复 job、满足当前节点 forbidden arc 状态、确实覆盖该 required arc，并且 `TWETColumnEvaluator` 评估不是 `big_M`，就可以作为子节点 seed column。这个做法仍然不是旧 VRP `UpdateRouteSet()/FindFeasible` 的完整复刻，因为它没有做定向 pricing，也没有人工 slack；但它已经比裸链更接近“先尽量为 required-arc 分支节点补一条可行列”的思路，能减少 restricted master 因暂时缺列而误 infeasible 的概率。

第三，树搜索最终状态不再只靠 `processedNodes` 粗判。新增 `TWETSolveStatus.NODE_LIMIT`，当达到 `config.maxNodes` 且队列中仍有未处理节点时返回该状态；如果只处理了根节点且队列已空，仍返回 `ROOT_PROCESSED`；处理多个节点并正常清空队列时返回 `FINISHED`。这只是结果汇报语义修正，不改变搜索过程。

关于 `PaperDominanceGraph`，本轮没有进一步改。当前判断是：结构方向是对的，它保留真实 label 集合，同时维护 node envelope 用于集合占优，符合 TWET 需要恢复具体序列的要求；但还没有通过和全扫描 `DominanceGraph` 的系统对拍来证明完全无误。因此它现在是“可继续使用并重点测试”的实现，不是已经严格验证完的实现。后续应补一个小算例对拍：同一实例分别用全扫描 dominance 和 paper graph dominance，比较根节点 bound、生成列集合和最终 RMP 结果。

## 2026-05-18：启发式定价、分支补列和 PaperDominanceGraph 对拍

本轮继续对照旧 VRP 代码里的 `GCTabu`、`BranchD.UpdateRouteSet()` 和当前 TWET 的 forward pricing 做了补充。旧 `GCTabu` 的核心流程不是替代精确定价，而是在 exact pricing 前先从当前 RMP 里挑一批低 reduced cost route，围绕这些 route 做 remove/add/exchange 邻域搜索，形成一个负 reduced-cost route pool，排序后只加入少量优质列。当前 TWET 版先移植这个流程，而不是直接搬旧代码里的硬时间窗 O(1) 增量数组。原因是 TWET 列成本依赖分段线性时间惩罚、setup time、setup cost 和硬时间窗预处理，直接复用 VRP 的增量公式不安全；第一版启发式定价对每个候选序列统一调用 `TWETColumnEvaluator` 重算真实列成本，再用当前 RMP dual 计算 reduced cost。这样慢一些，但语义稳，而且后面仍然有 exact forward labeling 兜底，不影响精确性。

实现上，`HeuristicPricingEngine` 已经从占位实现改成真实的启发式定价器。它先按 reduced cost 对当前 restricted columns 排序，取 `heuristicPricingSeedColumns=30` 条作为种子；然后围绕每条种子尝试 remove、add 和 exchange 三类邻域，每条种子运行 `heuristicPricingTabuIterations=50` 轮。当前已经取消额外的 candidate scan 上限，不再用扫描次数控制搜索，而是按旧 GCTabu 方式用本地负 reduced-cost 候选池 `heuristicPricingPoolSize=1000` 控制生成规模，最后排序取 `maxHeuristicPricingColumns=150` 条返回。这里刻意没有“找到 150 条就停”，因为旧 GCTabu 是先形成候选池再挑最好的 add-in 列，提前停会让早期较弱列挤掉后面更好的列。该启发式只跳过当前 RMP 已经 active 的重复列；如果某条序列在全局 pool 中已有但当前节点尚未 active，仍允许返回给 `PC` 激活。

分支子节点补列也做了一层加强。之前只对 required arc 做 fallback：如果当前 seed 中没有覆盖某条 required arc 的列，就构造 required-chain，并尝试把这条链插入已有列的不同位置。现在又补了“不能外包任务缺覆盖”的兜底：如果某个 job 的外包成本是 `big_M`，即 RMP 中对应 `y_j` 上界为 0，而当前 seed 中又没有任何兼容列覆盖它，就先尝试 singleton，再尝试把该 job 插入已有 pool 列的不同位置，选择一个兼容且成本有限的 fallback column。这个仍然不是旧 VRP `FindFeasible` 的完整 slack + 定向 pricing 版本，但能减少一类 restricted master 因暂时缺列而提前 infeasible 的情况。

`PaperDominanceGraph` 这次没有改主逻辑，但补了一个轻量一致性测试 `PaperDominanceGraphConsistencyTest`。测试做法是构造随机 reachable set 和 `[0,T]` forward frontier，把同一批 label 同步插入朴素全扫描 `DominanceGraph` 和论文式 `PaperDominanceGraph`，检查“新 label 是否被占优丢弃”的返回结果是否一致。当前测试规模为 200 组、每组 80 个 label，共 16000 次插入，测试通过。这个测试不能替代真实算例级的 bound/列池对拍，也不覆盖 partial dominance，但至少能防止 graph propagation 方向、terminal superset 查找或 predecessor/successor 维护出现明显语义错误。

本轮验证包括一次 focused compile，范围仍为 `src/Common`、`src/Basic`、`src/HEU`、`src/Output` 和 `src/TWETBPC`，编译通过，仅有旧 API deprecation 提示；随后运行 `TWETBPC.GC.PaperDominanceGraphConsistencyTest`，结果通过。剩余风险主要有两点：第一，启发式定价目前是全序列重算成本，不是旧 GCTabu 那种完全增量式 tabu，后续如果要追求速度，需要把 HEU 里已有的快速分段函数拼接和 reduced-cost 常数项结合起来做专门的增量评估；第二，分支子节点仍没有完整 slack RMP + FindFeasible 定向 pricing，复杂 required arc 组合下仍可能需要进一步补强。

## 2026-05-18：旧版 GCTabu 中 cut 项的增量处理方式

旧 VRP 的 `GCTabu` 在启发式列生成里已经考虑了 active subset-row cut。它不是每次 move 后重新扫描整条 route 来计算 cut 系数，而是在当前 route 状态中维护 `sr_count[sr]`：表示第 `sr` 个 active SR cut 在当前 route 中命中了多少个客户。初始化 seed route 时，逐个客户更新 `sr_count`，当某个 cut 的命中数从 1 变成 2 时，就在 reduced cost 中扣掉该 cut 的对偶 `sr_mu`。这对应 SR cut 在 route 上的系数从 0 变成 1。

后续 tabu move 也按这个计数做增量修正。`remove` 一个客户时，如果该客户属于某个 SR cut 且原来 `sr_count==2`，删掉后该 cut 不再被 route 触发，所以 reduced cost 要把这部分对偶加回去；`add` 一个客户时，如果该客户属于某个 SR cut 且原来 `sr_count==1`，加入后命中数达到 2，所以 reduced cost 要扣掉 `sr_mu`；`exchange` 则同时看换出客户和换入客户是否让某个 cut 的命中数跨过 2 这个阈值，分别加回或扣掉对应对偶。真正提交 move 后，再同步更新 `sr_count`，下一轮继续使用。

这个处理后续可以直接作为 TWET 启发式定价接 cut 的参考。具体做法是给 `TabuRouteState` 增加 active cut 的命中计数，例如 `srCount[]`，并提前维护 `srCustomer[sr][job]` 或等价的 cut-membership 查询。`evaluateRemove`、`evaluateAdd`、`evaluateExchange` 在现有分段函数快速评估出的真实列成本基础上，再按 `srCount` 做 reduced-cost 增量修正。这样每个候选 move 只需要额外扫描 active SR cut 数量，而不需要重算整条列。exact pricing 如果后续接 SRI，也要像旧版 `LabelEX.sr_count` 一样把 cut 计数状态带进 label，否则 dominance 和 reduced cost 都无法正确反映 cut dual。当前无 cut 版本不需要马上改代码，但这个方案应作为后续接 cut 的默认路径。

## 2026-05-18：启发式定价参数对齐旧 VRP GCTabu

前面临时加入过 `maxHeuristicPricingCandidateScans=5000`，用于防止 TWET 候选评估开销过大。但旧 VRP 的 `GCTabu` 并不是按候选扫描次数截断，而是用几组更明确的参数控制：`m_tabu_cg_size=30` 控制 seed 数，`m_tabu_cg_iteration_number=50` 控制每条 seed 的 tabu 轮数，`m_tabu_cg_tenure=30` 控制 tabu 长度，`m_gen_size=1000` 控制本地负 reduced-cost route pool 的最大规模，`addin_size=150` 控制最终加入 RMP 的列数。因此当前 TWET 版已经取消 candidate scan 上限，改为同样的参数语义。

修改后的配置对应关系为：`heuristicPricingSeedColumns=30` 对应旧 `m_tabu_cg_size`；`heuristicPricingTabuIterations=50` 对应旧 `m_tabu_cg_iteration_number`；`heuristicPricingTabuTenure=30` 对应旧 `m_tabu_cg_tenure`；`heuristicPricingPoolSize=1000` 对应旧 `m_gen_size`；`maxHeuristicPricingColumns=150` 对应旧 `addin_size`。这样控制逻辑更接近旧代码：每条 seed 完整扫描每轮 remove/add/exchange 邻域，选择当前最好 move；只要本地负 reduced-cost 候选池没满，就继续按 tabu 轮数推进；最后对候选池排序并返回前 150 条。这个改动不会改变 exact pricing 的兜底地位，只是让启发式生成列层和旧 VRP 的行为更一致。

## 2026-05-18：分支子节点改为 slack RMP + FindFeasible 修复

前一版 `Tree.prepareChildSeedColumns()` 的做法还是偏工程兜底：子节点入队前先从全局 pool 筛所有兼容列，然后对缺覆盖 job 或缺 required arc 的情况临时拼一些 fallback 序列。这和旧 VRP 的 `BranchD.UpdateRouteSet()` 不是同一套逻辑，效率也差，因为它在树层暴力枚举 pool 插入位置，而且无法利用 RMP dual 指导列生成。本轮把这块改成更接近旧代码的结构。

现在子节点入队时只做一件事：从父节点当前 RMP 的 restricted columns 中，按父节点 reduced cost 排序，挑选低于 `branchSeedReducedCostAllowance=5000` 的列，并最多保留 `branchSeedColumnLimit=1000` 条作为 child seed。这两个参数分别对应旧 VRP 的 `m_addin_red_cost` 和 `m_initial_col_number`。也就是说，子节点初始列集不再是“全局 pool 里所有兼容列”，而是像旧 `UpdateRouteSet()` 后半段那样继承父节点低 reduced-cost 的优质列。

如果这些 seed 仍然导致子节点 RMP 暂时不可行，修复逻辑现在放到 `PC` 内部。`PC.solve()` 会先解正常 RMP；如果不可行，就打开 `LP` 的 feasibility repair mode，建立带人工 slack 的同一节点 RMP。slack 加在 job 覆盖约束和 required-arc 约束上，目标系数为 `big_M`，只用于让暂时缺列的子节点先可解并产生 dual。随后 `PC` 调用现有启发式 pricing 和 exact pricing，日志名加 `[FindFeasible]`，不断把新列增量加入当前 CPLEX 模型并重新求解，直到 slack 归零，或者生成列数达到 `maxBranchRepairColumns=500`，该参数对应旧 VRP 的 `m_branch_col_number`。slack 归零后会关闭 repair mode，重建正常 RMP 继续标准列生成。

这次也把 `LP.addColumns()` 改成支持当前模型上的增量加列。只要 CPLEX 模型已经存在，新列会通过 `IloColumn` 同时接入 objective、machine constraint、coverage constraints 和 required-arc constraints，然后直接 `resolveCurrentModel()`，不再每轮都 `cplex.end()` 再重建模型。只有切换 repair mode、加入 cut 或显式重新 solve 时才重建模型。这个点是效率上的关键：如果沿用旧 VRP 的 FindFeasible 流程但每次都重建 LP，反而会比旧代码慢。

需要说明的是，这仍然不是完全复制旧 VRP 的所有细节。旧代码里的 `GCTabu.FindFeasible()` 和 `GCNGBB.FindFeasible()` 是专门的可行列修复入口；当前 TWET 版先复用已有 pricing engines，在 slack dual 下生成负 reduced-cost 列。结构上已经变成“slack RMP + 启发式/精确定价修复”，但还没有为 required arc 写专门定向的 pricing 状态，也没有输出 slack 剩余量和 repair 迭代统计。后续如果遇到复杂 required-arc 分支节点，可以继续把 `PricingEngine` 拆出专门的 `findFeasible` 接口。

## 2026-05-18：继续贴近旧 VRP 的 UpdateRouteSet 流程

前一版已经把暴力 fallback 改成了 slack RMP + pricing 修复，但还有一个关键差异：旧 VRP 的 `UpdateRouteSet()` 在 `FindFeasible()` 成功以后，会从当前子节点 LP 的 `route_index` 中按 reduced cost 重新筛选 route set，只保留 `rc < m_addin_red_cost` 且数量不超过 `m_initial_col_number` 的列。也就是说，旧代码不是“修复过程中见过多少列就全部带下去”，而是修复成功后根据当前子节点 LP 的 reduced cost 重新压缩列集。

本轮补上了这个步骤。`LP` 新增 `resetRestrictedColumnsByCurrentReducedCost(maxColumns, reducedCostAllowance)`，在 repair slack 已经归零、但还没关闭 repair mode 之前调用。此时 reduced cost 来自当前带 slack 的子节点 LP，和旧 VRP 在 slack 修复成功后读取 `lp.cplex.getReducedCost(...)` 的时机一致。筛选后再关闭 repair mode，重建正常 RMP 并继续后续列生成。这样 child 的 restricted columns 不会因为 repair 过程持续膨胀，也更接近旧 VRP 的 route-set 更新语义。

另一个差异是 `FindFeasible` 入口。之前 repair mode 只是日志上把普通 pricing 标成 `[FindFeasible]`，逻辑上仍调用 `price(lp)`。本轮在 `PricingEngine` 中增加了默认 `findFeasible(lp)` 入口，默认实现仍复用 `price(lp)`，但 `PC` 在 repair mode 下会显式调用该入口。这一步本身不改变当前定价结果，但把流程语义和旧 VRP 对齐了：后续如果 required-arc 分支节点需要更强的定向修复，可以直接在具体 pricing engine 中覆盖 `findFeasible()`，而不影响正常 pricing。

因此当前逻辑和旧 VRP 的主差异进一步缩小为两点：第一，TWET 的 slack 仍同时覆盖 job 覆盖约束和 required-arc 约束，比旧 VRP 的 branch slack 更泛，这是由外包和任务覆盖结构导致的；第二，当前具体 pricing engine 的 `findFeasible()` 还没有写专门的 required-arc 定向状态，只是有了入口和 slack dual 驱动。后续若继续追求完全同款，应优先做后者，而不是再回到树层 fallback。

## 2026-05-18：当前 BPC 框架与旧 VRP 流程的剩余差异

这次只比较框架流程，不讨论 TWET 与 VRP 在成本评估、时间窗函数、setup time/cost 上的差异。当前实现的主线已经和旧 VRP 接近：子节点先继承低 reduced-cost 列，RMP 不可行时打开 slack RMP，通过启发式和精确定价器在 slack dual 下补列，slack 归零后按当前 LP reduced cost 重筛 restricted columns，再关闭 slack 回到正常 RMP。这个流程已经对应旧 `UpdateRouteSet -> AddSlack -> GCTabu/GCNGBB.FindFeasible -> reduced-cost route set selection` 的主结构。

剩余差异主要有四个。第一，旧 VRP 的 `FindFeasible()` 自己内部反复 `solve LP -> IsNoSlack -> GetDual/GetValue -> Extend`，也就是说求解、检查 slack 和扩展列都包在具体 GC 里；当前 TWET 是 `PC` 外层统一控制这个循环，pricing engine 只负责一次 `findFeasible(lp)` 返回列。两者逻辑目标一致，但职责边界不同。如果只看算法流程，TWET 版少了“每个 GC 自己持有完整 repair loop”的写法。

第二，旧 VRP 在一次 repair 迭代里先跑启发式 `GCTabu.FindFeasible`，如果启发式产生新列，会 `gc.Reset()` 重置精确定价状态，再跑 `GCNGBB.FindFeasible`。当前 TWET 是按 `pricingEngines` 顺序逐个调用，暂时没有“启发式新增列后显式 reset exact pricing 状态”的动作。当前 exact pricing 如果本身无状态，这个差异影响不大；但如果后续 exact pricing 引入缓存、DSSR/ng-memory 之类状态，就应该补这个 reset 语义。

第三，旧 VRP 的 slack 是围绕当前分支约束加的 branch slack；当前 TWET 的 slack 加在 job 覆盖和 required-arc 约束上。这不是评估细节，而是 RMP 结构差异带来的框架差异。TWET 有外包变量和任务覆盖约束，子节点缺列可能表现为覆盖缺口或 required-arc 缺口，所以 slack 范围更泛。这个差异是当前问题结构导致的，不能简单照搬成只给某一条分支约束加 slack。

第四，旧 VRP 的 child route set 是 `UpdateRouteSet()` 的直接返回值，分支时立即赋给 left/right node；当前 TWET 是 child 入队前先继承父节点低 reduced-cost 列，真正不可行时在该 child 被弹出求解时才执行 slack repair 和 reduced-cost 重筛。也就是说，旧流程是在“生成 child 时”完成 route set 修复，TWET 当前是在“求解 child 时”完成修复。逻辑上等价地服务于 child RMP，但触发时机不同。若要更像旧版，可以把 repair 提前到 `enqueueChild` 阶段；但那会让分支阶段直接调用 LP/PC，代码耦合更重，且当前延迟修复不会改变子节点求解逻辑。

因此，如果不考虑评估细节，当前还不一致的核心是：`FindFeasible` 的 repair loop 放在 `PC` 而不是 GC 内部；启发式新增列后没有 exact pricing reset 语义；slack 约束范围比旧 VRP 更泛；child route set 修复发生在 child 求解时而不是 child 创建时。其他如增量加列、repair 后按 reduced cost 重筛列集、列数阈值和 add-in 阈值，已经基本按旧 VRP 思路对齐。

## 2026-05-18：关于 slack、机器数区间和 child 修复时机的补充说明

关于 `FindFeasible` 循环放在哪里，本质上主要是职责划分差异。旧 VRP 把 `solve LP -> IsNoSlack -> GetDual/GetValue -> Extend` 包在每个 GC 的 `FindFeasible()` 里；当前 TWET 把这层循环放在 `PC.repairInfeasibleMaster()`，pricing engine 只负责单轮补列。从算法调用顺序看，两者都是“解带 slack 的 RMP，读 dual，调用启发式/精确定价补列，再解 RMP，直到 slack 为 0 或列数上限”。因此这是封装位置不同，不是求解逻辑本质不同。当前放在 `PC` 更适合 TWET，因为多个 pricing engine 可以共享一套 repair 停止条件，避免每个 engine 复制 LP 求解和 slack 检查逻辑。

旧 VRP 中 `gc.Reset()` 的目的，是在启发式定价器产生新列以后，清理后续精确定价器可能保留的内部状态。新增列会改变 RMP，重新求解后 dual 会变化；如果 exact pricing 内部保存了上一轮的标签、bound、ng memory、DSSR 状态或其他缓存，就可能不再适合继续使用，所以旧代码在 `hgc.FindFeasible()` 产生新列后调用 `gc.Reset()`。当前 TWET exact pricing 每次调用基本重新读取 LP dual 并重新做 labeling，暂时没有这类跨调用状态，所以不需要 reset。后续如果加入 DSSR、ng-memory、双向 bound 或 label pool 复用，就应该给 `PricingEngine` 加默认空实现的 `reset()`，并在 repair mode 下前一个 engine 加列后调用后续 engine 的 reset。

关于 slack，即使有外包变量，RMP 也不保证总可行。如果每个任务都能外包、tariff segment 没有限制、机器数下界为 0、且没有 required arc，那么没有内部列时确实可以通过 `y_j=1` 让 coverage 可行。但当前模型存在几类例外：某些任务可以被设为不能外包，即 `outsourcingCost[j]` 为 `big_M` 时 `y_j` 上界为 0；required arc 必须由内部列覆盖，外包不能替代；tariff segment 分支可能禁止某些外包基准量；machine-count 分支可能要求至少使用若干内部列。因此 slack 的作用不是把不可行解当成可行解，而是让“暂时缺列”的 RMP 先可解并产生 dual，随后必须通过 `FindFeasible/pricing` 生成真实列，把 slack 压回 0。

当前机器数约束是区间形式 `minMachineCount <= sum lambda <= maxMachineCount`。根节点默认 `minMachineCount=0, maxMachineCount=m`，这意味着允许全部外包；`MachineCountBrancher` 会在分支中收紧上下界。这个建模不应该默认改成等式 `sum lambda = m`，因为在带外包或允许机器空闲的设定下，最优解不一定使用全部机器。只有当后续明确要求“所有机器必须使用”时，才应该改成等式。

关于 child 修复时机，旧 VRP 是创建 child 时直接运行 `UpdateRouteSet()`，修好 child 的 route set 后再入队。当前 TWET 是 child 入队时先继承父节点低 reduced-cost 列，真正弹出求解 child 时再根据需要做 slack repair。两者对最终 child RMP 的作用接近，但时机不同。当前延迟修复的好处是：如果 child 后续不需要处理或被 bound 剪掉，就不用提前跑 LP/pricing；`Tree` 也不需要直接调用 LP/PC，所有 RMP 不可行修复集中在 `PC`。因此当前时机更适合这个项目，暂时不建议为了形式一致而把 repair 挪回 `enqueueChild()`。

## 2026-05-18：FindFeasible 封装差异的结论补充

关于 `FindFeasible` 循环位置，当前结论是：这主要是封装差异，不是算法调用逻辑差异。旧 VRP 把 `solve LP -> check slack -> read dual -> pricing extend -> add columns` 包在各个 GC 的 `FindFeasible()` 内部；当前 TWET 把这层循环统一放在 `PC.repairInfeasibleMaster()`，pricing engine 的 `findFeasible(lp)` 只执行单轮补列。从调用顺序和信息流看，二者都是“带 slack 的 RMP 产生 dual，定价器根据 dual 补列，增量加列后重解，直到 slack 为 0 或达到列数上限”。因此这里不需要为了形式一致把循环强行搬进 GC 类，后续只要保证具体 `findFeasible()` 能在 required-arc 等场景下生成有效列即可。

## 2026-05-18：旧 VRP 中 gc.Reset() 的真实含义

重新查看旧 VRP 源码后，`BranchD.UpdateRouteSet()` 中的 `gc.Reset()` 不是在清理启发式和精确定价共享的函数信息，也不是 dominance graph。该处 `gc` 是 `GCNGBB`，其 `Reset()` 只执行 `Arrays.fill(m_low_ng_set, 0)` 和 `Arrays.fill(m_high_ng_set, 0)`。这两个数组是 `GCNGBB` 在 exact pricing 中维护的动态 ng-set，用于在发现重复访问/环以后扩展某些 customer 的 ng-memory，从而加强后续 label 扩展中的访问限制。`Extend(lp,n)` 每次会重新初始化标签队列、候选列池和 bound 数组，但不会自动清空这两个动态 ng-set；因此它们会跨多次 exact pricing 调用保留。

旧代码在启发式 `hgc.FindFeasible()` 产生新列后调用 `gc.Reset()`，含义是：RMP 增加了列并重新求解后，dual 和后续 pricing 搜索环境发生了变化，于是把 exact pricing 里此前逐步加严的动态 ng-memory 清掉，让精确定价从较松的 ng-set 状态重新开始。当前 TWET 基础版 exact pricing 没有这种跨调用维护的动态 ng-set，因此暂时不需要 reset；如果后续实现 DSSR、ng-route 动态记忆或跨轮 label/bound 缓存，再补 `reset()` 才有必要。

## 2026-05-18：关于 reset、slack 和修复时机的问答记录

本轮进一步澄清了旧 VRP `UpdateRouteSet/FindFeasible` 中几个容易混淆的点。第一，`gc.Reset()` 并不是清理启发式和精确定价共享的函数信息，也不是 dominance graph。旧代码中被 reset 的 `gc` 是 `GCNGBB`，`Reset()` 只清空 `m_low_ng_set` 和 `m_high_ng_set`，也就是 exact pricing 中动态维护的 ng-set。只有当前面的启发式 `hgc.FindFeasible()` 产生了新列时，旧代码才调用 `gc.Reset()`；如果启发式没有新增列，就不 reset。当前 TWET 基础 exact pricing 没有跨调用动态 ng-set，因此暂时不需要 reset。

第二，机器数量分支当前确实存在，机器数约束写成 `minMachineCount <= sum(lambda) <= maxMachineCount`。如果后续确认该问题的最优解必然使用全部机器，可以从 brancher 列表里关掉 `MachineCountBrancher`，甚至进一步把机器数上下界固定；但当前为了兼容外包和空机器，先保留区间建模。

第三，旧 VRP 的 slack 是加在某条分支约束上的人工变量。它只出现在那一行约束里，目标系数为 `BigNumber`，用于让“当前 route set 暂时不满足分支约束”的 LP 先可解。当前 TWET 的 required-arc slack 与旧 VRP 的 branch slack 语义最接近：required arc 约束要求 `sum(使用该弧的内部列)=1`，如果当前列集没有这类列，就临时加 `requiredArcSlack` 让该行可解，并用 slack dual 引导 pricing 生成真实列。当前 TWET 还额外对 job coverage 行加了 `coverSlack`，这是因为有些任务不能外包，或者外包/segment/机器数状态使得当前 restricted RMP 没有真实方式覆盖某个 job。这里不是给“外包变量”本身加 slack，而是给覆盖约束加 slack；如果某个 job 能正常通过 `y_j=1` 外包覆盖，这个 slack 自然不会为正。

第四，修复列逻辑与旧 VRP 的核心一致，差别是时机。旧 VRP 是创建 child 时立即运行 `UpdateRouteSet()`，必要时 slack + FindFeasible，修好 route set 后入队。当前 TWET 是 child 先入队，等实际弹出求解时由 `PC` 做 slack + FindFeasible。两者的修复机制都是“slack RMP 产生 dual -> pricing 找列 -> 加列重解 -> slack 归零后筛列”，但当前延迟修复能避免提前处理可能不会被访问的 child，也能让 `Tree` 不直接耦合 LP/pricing。

## 2026-05-18：Reset 调用点和 coverage slack 的进一步澄清

旧 VRP 里的 `Reset()` 有两类调用场景。正常列生成中，`GCNGBB.Solve(lp)` 一开始会无条件调用 `Reset()`，这是一次完整 exact pricing 求解的初始化。分支可行列修复中，`BranchA/B/C/D.UpdateRouteSet()` 创建一个 `GCTabu hgc` 和一个 `GCNGBB gc`，在 while 循环里先调用 `hgc.FindFeasible()`，如果 `old_col_number != col_number`，说明启发式新加了列，此时才调用 `gc.Reset()`，随后再调用 `gc.FindFeasible()`。`GCNGBB.FindFeasible()` 自己不 reset，因此同一个 `GCNGBB` 对象在分支修复循环中会保留动态 ng-set；所谓“跨调用”就是这些 `m_low_ng_set/m_high_ng_set` 会跨多次 `gc.FindFeasible()` 调用保留，除非正常 `Solve()` 开头或启发式新增列后显式 reset。

coverage 是否可能不可行，取决于外包变量是否能覆盖所有任务，以及 child 从父节点继承的列是否仍能覆盖所有不能外包的任务。child 只从父节点 restricted columns 中筛选低 reduced-cost 且兼容分支状态的列，筛选以后完全可能丢掉某些高 reduced-cost 但覆盖关键 job 的列；如果该 job 又不能外包，则 coverage 行会暂时不可行。即使父节点可行，child 的 forbidden/required arc 状态也可能让原来覆盖某个 job 的列不再兼容。因此 coverage slack 不是只为外包变量本身准备，而是为“当前 child restricted column set 暂时缺覆盖列”的情况准备。

## 2026-05-18：solve、FindFeasible、Reset 和 coverage slack 的细化记录

本轮继续把旧 VRP 代码和当前 TWET-BPC 代码逐项对齐，重点澄清 `solve/price` 和 `findFeasible` 的区别。正常 `solve` 或 `price` 的前提是当前 RMP 已经可行，此时 LP 能给出正常 dual，pricing 的目标是找负 reduced cost 列来改进当前节点的 LP 下界。`findFeasible` 的场景不一样：它用于分支子节点 restricted column set 暂时不够时，RMP 可能不可行。旧 VRP 的做法是先 `AddSlack`，让带人工 slack 的 LP 能解出 dual，再用启发式和精确定价在这个 dual 下补列，直到 `IsNoSlack()` 成立。也就是说，`findFeasible` 的第一目标不是证明没有负 reduced cost，而是先把子节点的列集修到可行。

旧 VRP 中启发式 `GCTabu.FindFeasible()` 本身已经会循环执行 `solve LP -> IsNoSlack -> GetDual/GetValue -> Extend`。如果启发式已经把 slack 压到 0，它会返回 `-1` 表示成功。不过旧 `BranchD.UpdateRouteSet()` 的外层 while 写法是先调用启发式，再根据是否新增列决定是否 `gc.Reset()`，然后仍然调用一次 `GCNGBB.FindFeasible()`；下一轮循环开头看到 `col_number == -1` 才退出。因此“启发式找到可行列以后为什么还调用精确的”不是因为算法上必须这么做，而是旧代码的控制流如此写。更准确地说，旧代码把启发式当作快速修复层，把 `GCNGBB` 当作进一步兜底层；如果启发式已经成功，精确层通常很快会发现 slack 已经为 0 并返回成功。

关于 `Reset()`，这次重新看了旧 `GCNGBB` 源码，确认它清理的只有 `m_low_ng_set` 和 `m_high_ng_set` 两个动态 ng-set。它不是 DSSR 的完整重启，也不是清理 dominance graph，更不是清理 LP 或 route pool。旧代码只有在 `hgc.FindFeasible()` 新增列以后才调用 `gc.Reset()`，原因是启发式补列会改变 RMP 和后续 dual，旧作者选择让后续 exact pricing 的动态 ng-memory 从较松状态重新开始。如果启发式没有新增列，就不 reset；如果 exact `FindFeasible()` 自己连续调用多次，它会保留这些 ng-set 状态继续推进。当前 TWET 的基础 exact pricing 每次调用都会重新读 dual 并重新做 forward labeling，没有跨调用的动态 ng-set，所以现在暂时没有需要 reset 的对象。后续若加入 DSSR、ng-route 动态记忆、双向 bound 缓存或跨轮 label pool，再补 `PricingEngine.reset()` 才有意义。

本轮还澄清了 child 继承列以后为什么仍可能出现 coverage 不可行。child 并不是完整继承父节点所有列，而是从父节点 restricted columns 中按 reduced cost 和分支兼容性筛一批列。这样做和旧 VRP 的 `UpdateRouteSet()` 后半段一致，目的是限制子节点列集规模。但筛选以后，某些覆盖关键 job 的高 reduced-cost 列可能被丢掉；如果该 job 又不能外包，coverage 约束就会暂时不可行。即使 job 可以外包，required arc 也不能靠外包变量满足，因为 required arc 要求的是内部加工列中出现某条弧。因此当前 TWET repair mode 同时给 coverage 行和 required-arc 行加人工 slack，是为了覆盖“暂时缺列”的 RMP，而不是为了放松最终模型。repair 成功后必须关闭 slack，并重解正常 RMP。

这里要区分两类 slack。旧 VRP 的 `AddSlack` 是加在分支约束行上的人工变量，目标系数为 `BigNumber`，只用于让当前 route set 暂时不满足分支约束时仍能求 LP 和 dual。当前 TWET 的 `requiredArcSlack` 与旧逻辑最接近，对应 `sum(columns using arc)=1` 这一类分支约束。`coverSlack` 是当前问题新增的工程兜底：如果某个 job 的 `y_j` 上界为 0，或者 child 筛列后暂时没有内部列覆盖它，coverage 行就需要人工 slack 来产生 dual。若某个 job 可以正常外包，`y_j=1` 已经能满足 coverage，这个 slack 自然不会为正。

当前实现把旧 VRP 中放在各个 GC 内部的 `FindFeasible` 循环统一放到了 `PC.repairInfeasibleMaster()`：先解带 slack 的 RMP，调用 pricing engines 的 `findFeasible(lp)`，增量加列后 `resolveCurrentModel()`，循环直到 slack 为 0 或达到列数上限。这个是封装位置差异，不是调用逻辑差异。这样做的好处是多个 pricing engine 共用一套 repair 停止条件和增量加列逻辑，避免每个 GC 都复制 LP 求解、slack 检查和列接入代码。当前 `PricingEngine.findFeasible(lp)` 默认复用 `price(lp)`，后续如果 required-arc 节点需要更强定向搜索，可以在具体 engine 里覆盖。

本轮继续澄清 repair loop 的循环语义。旧 `UpdateRouteSet()` 里的外层 `while(true)` 是一个真正的循环：每轮先调用 `GCTabu.FindFeasible()`，如果启发式新增了列，就调用 `GCNGBB.Reset()`，然后调用 `GCNGBB.FindFeasible()`。如果任一阶段把 slack 压到 0，会返回 `col_number=-1`，下一轮开头退出并认为成功。如果两者都跑完以后仍不可行，但本轮至少新增了列，则进入下一轮继续；如果本轮列数没有变化，或者超过 `m_branch_col_number`，就停止并认为修复失败。因此旧逻辑不是“Tabu 一次、NGBB 一次就结束”，而是“只要还在新增列，就交替尝试，直到 slack 为 0 或无法继续改善”。

关于 reset 触发条件，需要区分启发式新增列和 exact 自己新增列。旧代码只在 `old_col_number != col_number` 发生在 `hgc.FindFeasible()` 之后时 reset exact pricing，也就是说 reset 是为了处理“启发式新列改变了 RMP 和 dual”这个事件。如果上一轮 `GCNGBB.FindFeasible()` 自己新增了列但仍不可行，下一轮会回到 Tabu；若这次 Tabu 没新增列，就不会 reset，`GCNGBB` 会带着上一次 exact pricing 中逐步扩展出来的动态 ng-set 继续。如果 Tabu 又新增列，才 reset。`m_low_ng_set/m_high_ng_set` 什么时候非空，取决于 `GCNGBB` 在前面 exact 扩展中是否遇到了需要扩大 ng-memory 的重复访问/环结构；这些数组不是每次 `FindFeasible()` 自动清空，所以在多轮 exact repair 中可能保留。

关于 slack 的建模，本轮讨论了“只放一个公共 slack，挂到所有 coverage 和 branch 行上”的方案。结论是不建议这么做。一个公共 slack 同时出现在多条约束里，会把多个缺口强行绑成同一个人工变量：例如两个不同 job 都缺覆盖，或者 coverage 和 required-arc 同时缺列，公共 slack 只付一次 big-M 成本就能同时修补多行，这会低估人工违约代价，也会让 dual 变得不干净。更重要的是，pricing 需要知道到底是哪一类约束缺列，独立 slack 的 dual 更有指向性；公共 slack 会把多个行的影子价格混在一起，可能削弱 FindFeasible 找对应列的能力。当前更合理的方向是：只给确实可能缺列的行加独立 slack。若全部任务都允许外包，则 coverage 行通常不需要 slack；若某些任务不能外包，或外包/tariff/机器数分支会让 coverage 暂时缺真实列，则这些 coverage 行需要独立 slack。required arc、tariff segment、machine-count 下界等分支约束如果可能因为 restricted columns 不足而不可行，也应各自使用独立 slack，而不是共用一个变量。

旧 VRP 在极端情况下也不是完全没有工程风险。它在 repair 成功后按 reduced cost 重筛 route set，这个步骤可能丢掉某些当前不便宜但对后续 coverage 或分支可行性有用的列。旧代码依赖较大的保留数量和较宽的 reduced-cost allowance 来降低风险，若后续节点仍暂时不可行，再通过 `AddSlack + FindFeasible` 修复。因此它是一个实用的列池控制策略，不是严格保证“筛完以后子节点 RMP 永远可行”的证明。当前 TWET 也沿用这个思想，但因为有外包、不能外包任务、tariff segment 和 required arc，coverage slack 的必要性比纯 VRP 更明显。

进一步看旧 `UpdateRouteSet()` 的筛列代码，修复成功后它先 `lp.GetVaule()`，但真正用于传给 child 的并不是被注释掉的 `route_active_set`，而是遍历 `lp.route_index` 中所有当前 LP 变量，对每条 route 取 `cplex.getReducedCost(...)`，保留 `rc < m_addin_red_cost` 且不违反 `n.feasible_arc` 的列；如果超过 `m_initial_col_number`，再按 reduced cost 排序取前若干条。因此旧代码不是显式“保留所有基列/正值列”，而是用 reduced cost 阈值间接覆盖它们。通常正值基列 reduced cost 为 0，会被 `5000` 这种宽阈值保留下来；但如果低 reduced-cost 列太多，理论上仍可能把某些正值列挤出前 `1000`。所以这不是严格证明，只是旧代码的工程策略。当前 TWET 的 `resetRestrictedColumnsByCurrentReducedCost(...)` 采用相同思路，后续若要更稳，可以显式优先保留当前 LP 正值列，再用 reduced cost 补足容量。

关于旧代码是否使用 DSSR，需要精确区分。`GCNGBB` 文件头注释写的是“using decremental state space to iteratively update the ng-set”，并且代码中确实通过 `m_low_ng_set/m_high_ng_set`、duplicate 检测和 `UpdateNGSet()` 逐步加严 ng-memory。这是 ng-route relaxaton 上的 decremental state-space refinement 思路，和 DSSR 的“大状态空间逐步收紧”思想相近。但旧项目里也有单独的 `GCDSS` 类，`BranchD.UpdateRouteSet()` 当前实际创建的是 `GCNGBB gc = new GCNGBB(data, Double.MAX_VALUE)`，而不是 `GCDSS`。所以更准确的说法是：当前分支修复使用的是带动态 ng-set 更新的 NGBB 精确定价，不是直接调用 `GCDSS` 那个 DSS 类。

关于“一个 slack 变量覆盖所有可能行”的想法，本轮进一步明确：如果这个 slack 以同一个系数出现在多条 equality 里，它不仅不是更稳，反而可能表达能力不足。不同 coverage 行的缺口通常不相同，一个公共 slack 会强迫所有这些行使用同一个人工补充值；如果某一行本来不缺口，公共 slack 仍会改变该行左端，从而破坏那一行。除非给每行配不同系数或额外结构，但那本质上又回到了每行独立人工变量。旧 VRP 的 `AddSlack` 每次也是针对一条 branch constraint 加一个人工变量，而不是一个 slack 挂全部分支行。当前 TWET 因此仍应采用“每个可能缺列的约束行各自一个 slack”的方式；可以优化的是只给需要的行加，例如全部任务可外包时不加 coverage slack，不能外包的 job coverage 行再加。

如果父节点的所有当前基列都完整继承到 child，且这些基列在 child 分支状态下仍兼容，那么 coverage 一般不会是问题，新增分支约束才是主要不可行来源。但当前旧 VRP 和 TWET 都不是完整继承所有基列，而是按 reduced cost 和兼容性筛列；同时 child 的 forbidden/required arc 会让部分父节点列不再兼容。因此实际实现里 coverage slack 仍有存在理由。若后续改成“正值列必保留 + 所有不能外包 job 至少保留一条覆盖列 + 只按阈值补充非基列”，coverage slack 的使用范围可以进一步收窄。

本轮进一步确认了 `GCNGBB` 的内部流程和它看起来“奇怪”的 reset 语义。`GCNGBB.Solve(lp)` 是正常 pricing 入口，开头会无条件 `Reset()`，然后循环执行 `lp.Solve() -> lp.GetDual() -> Extend(lp)`，直到 `Extend` 没有继续加列。`GCNGBB.FindFeasible(lp,n,col_number)` 是分支修复入口，它不调用 `Reset()`，只执行一次 `Extend(lp,n)`，把本次生成的 route 数加到 `col_number`，然后立即解一次带 slack 的 LP 并检查 `IsNoSlack()`。因此在分支修复外层循环里，如果上一次进入 `GCNGBB` 后仍不可行，下一次再进入 `GCNGBB` 时，除非中间 Tabu 新增列触发了外层 `gc.Reset()`，否则会保留上一次 exact pricing 过程中形成的 `m_low_ng_set/m_high_ng_set`。

`Extend(lp,n)` 自身内部也不是只做一轮普通 labeling。它先 `Initialize(lp)`，计算前向/后向时间和容量 bound，然后进入一个内部 `while(true)`：每轮清空本轮最小成本和 duplicate 标记，做 `FWExtend`、`BWExtend`、`Join`，之后调用 `UpdateNGSet()`。如果 join 已经生成了负 reduced-cost route，或者本轮没有发现 duplicate cycle，就退出；如果没有生成列但发现了重复访问形成的 cycle，`UpdateNGSet()` 会把 cycle 中的 customer 加进相关节点的 ng-set，然后下一轮用更紧的 ng-memory 重新做 forward/backward/join。这个过程就是它文件头说的 decremental state-space refinement on ng-route。它和 `GCDSS` 的 DSS 类不是同一个实现，但确实是在一次 pricing 调用内部逐步扩充状态空间约束。

因此旧代码的 reset 逻辑可以理解为：`GCNGBB` 自己连续求解时，保留动态 ng-set 是刻意的，因为这些 ng-set 是它通过重复环发现逐步加严出来的信息；但如果 Tabu 在外部新加了列，RMP 和 dual 改变，旧代码选择把 exact pricing 的动态 ng-set 清掉，让它从松状态重新开始。这个设计并非唯一合理方案，因为 NGBB 自己新增列以后 LP 也会变化，但旧代码没有 reset；所以它更像经验性的状态管理，而不是严格的必要步骤。当前 TWET 还没有这种跨调用动态 ng-set，暂时不需要照搬；如果后续做 ng-route/DSSR 式动态收紧，再决定是否复现这种 reset 策略。

关于 child 筛列后的可行性，本轮也进一步明确：旧代码确实会把不满足当前分支 arc 状态的 route 删掉，筛选条件包括 `n.feasible_arc[route[k-1]][route[k]] != -1`。因此从父节点继承来的列在 child 中可能因为 forbidden/required 分支变得不兼容而被移除。再加上 reduced-cost 数量筛选，child restricted RMP 仍可能暂时不可行。这说明后续如果想减少 coverage slack 的使用，应该优先改 seed selection，例如显式保留当前 LP 正值列、保证不能外包 job 的覆盖列、再按 reduced cost 补充，而不是依赖一个公共 slack。

本轮进一步澄清了旧 VRP 和当前 TWET repair loop 的差异。旧 `GCTabu.FindFeasible()` 内部会先解当前带 slack 的 LP，若 slack 未归零就读 dual、扩展启发式列、把新 route 加入 `lp.pool`，并通过 `lp.AddColumn(gn_index)` 直接接入当前 CPLEX 模型；随后它继续循环求 LP，直到 slack 归零、没有新列或达到列数上限。因此旧 Tabu 的 `FindFeasible()` 不是只返回候选列，而是自己会“解 LP、加列、重解”。旧外层再调用 `GCNGBB.FindFeasible()`，此时 NGBB 看到的可能已经是 Tabu 加列后的 LP。

当前 TWET 的实现不同：`PC.generateColumns(lp,true)` 会按顺序调用 `HeuristicPricingEngine.findFeasible(lp)` 和 exact engine 的 `findFeasible(lp)`，但这两个 engine 当前只是返回 `PricingResult`，不会在各自内部修改 CPLEX 模型。`PC` 收齐本轮所有新列后，才统一 `lp.addColumns(newColumnIds)` 并 `resolveCurrentModel()`。所以当前逻辑是“启发式和精确基于同一轮旧 dual 各找一批列，再统一加列重解”；旧 VRP 是“Tabu 可在内部加列重解，随后 NGBB 可能基于更新后的 LP 再修”。两者目标一致，但严格流程并不完全相同。当前的优点是封装干净、LP 修改集中；缺点是如果想完全模拟旧 `FindFeasible`，后续需要让 repair mode 支持 engine 内部增量加列/重解，或者在 `PC` 中做到每个 engine 加列后立即 resolve，再进入下一个 engine。

关于“什么时候判当前点不可行”，当前 TWET 是：带 slack 的 RMP 解出来后，只要 slack 未归零，就进入 repair loop；每轮如果启发式和精确都没有返回新的 active column，就停止；或者达到 `maxBranchRepairColumns` 后仍有 slack，也停止。此时返回当前节点 RMP infeasible。这个和旧 VRP 的“不再有新列或超过 `m_branch_col_number` 就停止”是同类停止准则，只是旧版的“新列”是在各 GC 内部加入，当前是由 `PC` 汇总后加入。

关于公共 slack，本轮明确了用户提出的另一种理解：如果只想知道“有缺口存在”，一个公共 slack 看起来可以作为 infeasibility flag。但在 LP repair 中，slack 不只是 flag，它还通过目标惩罚和约束 dual 引导 pricing 找列。公共 slack 挂多行时，如果多行缺口数值不同，模型可能反而不可行；如果通过其他写法把它做成“有任意缺口就打开”，那会失去每一行的 dual 指向，pricing 不知道应优先补哪个 job、哪条 required arc 或哪类分支约束。旧 VRP 也是对当前分支行单独加 slack，而不是公共 slack 挂所有分支行。因此当前仍倾向独立 slack，但后续可以减少 slack 行数：全部允许外包时 coverage 行不加；child seed selection 若保证不能外包 job 的 coverage，则这些 coverage 行也可不加。

本轮进一步确认 `maxBranchRepairColumns=500` 只是对齐旧 VRP `m_branch_col_number=500` 的工程上限，不应被解释成数学上的不可行证明。旧代码在 `col_number > m_branch_col_number` 或本轮没有新增列时停止；这是一种防止分支修复无限加列的保护。当前 TWET 也用了这个上限，但后续在严格 BPC 语义上，如果达到上限而 slack 仍为正，更合理的状态应是“repair limit / node unresolved”，而不是直接把子节点当作严格 infeasible。若用户不希望额外参数化，应至少把该上限视为旧代码参数的复刻，并在日志/状态中区分“无新列失败”和“达到上限停止”。

关于 repair 迭代流程，旧 VRP 的做法在分支修复场景下更自适应：Tabu 内部可以多轮 `solve -> add column -> resolve`，Tabu 结束后 NGBB 可能已经基于更新后的 LP 和 dual 继续补列。当前 TWET 的做法更清晰，但更粗：同一轮里启发式和精确定价都基于同一个旧 LP 返回列，然后由 `PC` 统一加列重解；如果启发式列已经足以明显改变 dual，exact 仍然没利用这次更新后的 dual，必须等外层下一轮。后续若要更贴近旧 VRP，建议在 repair mode 下改成“每个 engine 返回列后立即 add + resolve + 检查 slack”，若 slack 已归零则不再调用后续 engine；这样既能保持 PC 统一控制，也能复刻旧代码的信息流。

关于旧代码是否只修新分支约束，本轮进一步确认：旧 `AddSlack(cid,sid)` 是对当前分支相关的一行 `branch2rng` 加 slack，并没有对 coverage 行加 slack。之所以旧 VRP 还能工作，主要因为它的 set partitioning coverage 在父节点 route set 中通常已经可行，而且 child 主要新增的是当前 branch 行；但由于它后续还会按 reduced cost 和 feasible arc 过滤 route，极端情况下确实可能把维持 coverage 所需的列删掉。旧代码没有专门修 coverage，这属于工程风险。当前 TWET 因为有不能外包 job、外包变量、tariff/机器数分支等额外结构，coverage 暂时不可行的可能性更明显，所以当前更稳的设计是保留 coverage slack，但后续通过更强 child seed selection 缩小它的适用范围。

关于旧 `FindFeasible()` 加列数量，本轮确认如下：`GCTabu` 和 `GCNGBB` 都先在本地 `GCPool` 中收集负 reduced-cost route，本地池达到 `m_gen_size=1000` 时停止生成；如果本地池超过 `addin_size=150`，先排序，然后最多把前 150 条新 route 加入 LP。分支修复外层另有 `m_branch_col_number=500` 的总修复列数上限。因此旧逻辑是“启发式先找并加入最多一批优质列，LP 更新后，再让精确定价继续找”，而不是启发式和精确基于同一个旧 dual 同时找列。当前 TWET 后续如果要减少重复列并更贴近旧流程，应在 repair mode 下改为每个 pricing engine 生成列后立即 add/resolve，再进入下一个 engine。

旧 VRP 的上述潜在风险已经单独整理到 `docs/plans/2026-05-18-旧VRP-BPC潜在风险记录.md`。后续涉及分支修复、slack、子节点列继承和动态 ng-set/DSSR 类策略时，优先参考该文档。

## 2026-05-18：FindFeasible repair 流程再次贴近旧 VRP

前一版 TWET repair 的主流程虽然已经是 slack RMP + pricing 补列，但还有一个时序差异：`PC.generateColumns(lp,true)` 会先让启发式定价和精确定价都基于同一轮旧 dual 各自返回一批列，然后由 `PC` 统一加列并重解。这和旧 VRP `UpdateRouteSet()` 中的做法不完全一样。旧代码的实际信息流更接近：先用启发式 `FindFeasible` 基于当前 slack LP 补列；如果启发式真的加了列，就马上重解 LP，后续精确定价再基于新的 dual 继续修复。这样做的目的不是形式上把循环搬进 GC，而是避免后面的定价器继续使用已经过期的 dual 或内部状态。

本轮把 `PC.repairInfeasibleMaster()` 改成 repair 专用的顺序流程：每个 `PricingEngine.findFeasible(lp)` 返回新列后，立即 `lp.addColumns()` 并 `resolveCurrentModel()`；如果 slack 已经归零，就不再调用后续 engine。若前一个 engine 补列成功，会调用后续 engine 的 `reset()`，这个接口当前默认空实现，因为现有 exact pricing 没有跨调用 DSSR/ng-set 状态；后续如果接入动态 ng-route 或 DSSR，就可以在具体 engine 里覆盖该方法。正常 pricing 的 `generateColumns(lp,false)` 仍保持原来的一轮汇总逻辑，因为它不承担 repair slack 归零检查。

`maxBranchRepairColumns` 也从 500 调大到 100000。这个值保留是为了防止极端情况下 repair 无限补列，但当前语义不再把它当作判断子节点不可行的主要条件。正常情况下 repair 应该因为 `slack=0` 成功退出，或者因为一整轮没有任何 engine 返回新的 active column 而失败。达到该上限只能说明触发了工程保护，不能当成数学上的 infeasible 证明。

由此，当前 repair 流程和旧 VRP 的主要逻辑进一步对齐为：带 slack 的 RMP 先给出 dual，启发式定价先尝试补列，补列后立即重解并更新 dual，再进入后续定价器；slack 归零后再按当前 LP reduced cost 重筛 restricted columns，关闭 repair mode，回到正常 RMP。剩余差异是具体 `findFeasible()` 仍未做 required-arc 定向搜索，`reset()` 目前也只是给后续 DSSR/ng-set 预留的接口。

## 2026-05-18：修正 repair 阶段“slack 归零后是否继续调用后续定价器”的说明

复核旧 VRP `BranchD.UpdateRouteSet()` 后确认，旧流程并不是在启发式定价让 slack 归零后立刻跳过后续精确定价。其代码顺序是先调用 `hgc.FindFeasible(lp, node, col_number)`，若启发式新增列则 `gc.Reset()`，然后仍会调用一次 `gc.FindFeasible(lp, node, col_number)`；只有当 `FindFeasible` 返回 `-1` 后，下一轮 while 开头才把 `success=true` 并退出。因此如果目标是让当前 TWET repair 流程更贴近旧代码，就不应该在同一轮 engine pass 中因为 `lp.isNoSlack()` 已经为真而提前跳过后续 engine。

本轮据此调整 `PC.repairInfeasibleMaster()`：外层 while 仍然以 `!lp.isNoSlack()` 作为 repair 是否继续的判断；但内层按 engine 顺序调用时，不再把 `!lp.isNoSlack()` 放进 for 条件。这样某个 engine 补列并重解 LP 后，即使 slack 已经归零，后续 engine 仍可基于新的 dual 再执行一次。下一轮外层 while 会因为 slack 已归零而退出。这个行为更接近旧 VRP 的 `hgc -> gc -> 下一轮检查是否成功` 的控制流。

同时补充明确 `maxBranchRepairColumns=100000` 的注释：它对应旧 `m_branch_col_number`，但当前调大是为了避免 500 这个较小工程上限过早截断可修复子节点；它只用于防止极端无限补列，不作为不可行证明。正常退出原因仍应是 slack 归零或所有定价器都没有新 active column。

进一步确认当前 TWET repair 的执行顺序为：进入外层 `while (!lp.isNoSlack())` 后，先调用启发式 `findFeasible`。如果启发式返回新列，`PC` 立即把这些列加入当前 LP 并重解；随后仍然进入后续精确定价器。若精确定价器也返回新列，同样立即加入并重解。等这一轮 engine 都处理完后，外层 while 再检查 slack：如果精确那次重解后 slack 已经为 0，就不再进入下一轮 repair，直接执行 repair 成功后的 reduced-cost 列筛选并关闭 slack mode。也就是说，当前流程不是“tabu 加列重解后马上回到 tabu”，而是“tabu 加列重解后进入 exact；这一整轮结束后，如果 slack 仍未归零且确实有新增列，才回到下一轮 tabu”。这和旧 VRP `hgc -> gc -> 下一轮检查 col_number == -1` 的控制流是一致的。

## 2026-05-18：进一步修正 FindFeasible 中启发式与精确定价的先后关系

继续复核旧 VRP `GCTabu.FindFeasible()` 后，前一版理解还不够准确。旧代码不是“Tabu 找到一批列后就立刻进入精确，然后下一轮再看”，而是 `GCTabu.FindFeasible()` 自己内部就包含循环：先解当前 slack LP，如果 slack 已经为 0 就返回成功；否则读取 dual/value，执行 `Extend(lp,n)` 生成启发式列并 `lp.AddColumn(gn_index)`，然后继续重解。只要 Tabu 还能继续生成列、列数未超过 `m_branch_col_number`、且未超时，它会优先持续使用启发式修复。只有当 Tabu 找不到新列或退出后，外层 `UpdateRouteSet()` 才进入 `GCNGBB.FindFeasible()` 这一层精确定价兜底。

因此当前 TWET repair 进一步调整为：`PricingEngine` 新增 `repeatFindFeasibleUntilExhausted()`，默认 `false`；`HeuristicPricingEngine` 覆盖为 `true`。`PC.repairInfeasibleMaster()` 在 repair mode 下调用某个 engine 后，如果该 engine 生成了新列，就立即加列并重解 LP；如果该 engine 声明可以重复、且 slack 仍未归零，就继续调用同一个 engine。这样启发式能找到列时会优先持续使用启发式，而不是每补一批列就马上切到更慢的 exact。精确定价器保持默认不重复，作为启发式耗尽后的兜底；如果 exact 加列后 slack 仍未归零，控制权回到外层 repair pass，下一轮仍从启发式开始。

这个流程更接近旧 VRP 的意图：快速的启发式列生成优先用于修复子节点 RMP，可行性仍修不掉时再让精确定价补强。`resetFollowingPricingEngines()` 只在某个 engine 确实新增列后调用，用于清理后续 engine 可能依赖旧 dual 的状态；当前 exact pricing 暂无跨调用状态，因此实际为空实现，但接口保留给后续 DSSR/ng-route。

## 2026-05-18：当前 repair loop 与旧 VRP 的一致性复核

本轮重新对照当前 `PC.repairInfeasibleMaster()` 和旧 VRP `BranchD.UpdateRouteSet()`、`GCTabu.FindFeasible()`、`GCNGBB.FindFeasible()` 后，结论是：当前 repair loop 的主控制逻辑已经基本和旧 VRP 一致。这里的一致指的是流程意图一致，而不是代码封装位置完全相同。

旧 VRP 的真实流程是：进入带 slack 的子节点 LP 后，先调用 `GCTabu.FindFeasible()`。这个启发式函数内部会反复执行“解 LP、读 dual、生成启发式列、加列、再解 LP”，只要启发式还能产生列且 slack 没有归零，就优先继续用启发式。只有当 Tabu 停下来以后，外层才调用 `GCNGBB.FindFeasible()` 做精确定价兜底。如果 exact 也加了列但 slack 仍未归零，外层循环会再回到下一轮 Tabu；如果两者都没有新增列，或者达到工程列数上限，则 repair 失败。

当前 TWET 的流程现在也是这个语义：`HeuristicPricingEngine.repeatFindFeasibleUntilExhausted()` 返回 `true`，所以 repair 阶段会在启发式还能补列且 slack 未归零时持续调用启发式，并且每次补列后立即加入 LP、重解 LP、更新 dual。启发式耗尽后才进入后续 exact pricing。exact pricing 默认不重复占用这一层，如果 exact 加列后 slack 仍未归零，控制权回到外层 repair pass，下一轮仍然从启发式开始。这一点已经和旧 VRP “先尽量用快速启发式，启发式修不掉再用精确兜底”的逻辑一致。

剩下的差异主要是封装和能力差异。旧 VRP 把 `solve/add/resolve` 放在 `GCTabu.FindFeasible()` 内部；当前 TWET 由 `PC` 统一负责加列和重解，engine 只返回 `PricingResult`，这是封装差异，不改变主流程。旧 `GCNGBB` 有动态 ng-set，并且只有 Tabu 新增列后才 reset；当前 exact pricing 还没有这种跨调用动态状态，所以 `reset()` 目前是预留接口。旧 exact 的 `FindFeasible()` 本身带 NGBB/ng-route 的动态收紧；当前 exact 仍是基础 forward pricing，repair 强度还没有完全达到旧 VRP 的精确定价层。也就是说，循环逻辑已经对齐，但 exact pricing 的内部能力还可以继续补。

因此当前不需要为了“循环形式”再改 `PC.repairInfeasibleMaster()`。后续真正需要补的是两个方向：一是如果实现动态 ng-route/DSSR，就让 exact engine 的 `reset()` 真正清掉对应状态；二是如果 required-arc 分支节点需要更强修复，就覆盖 `findFeasible()` 做定向 pricing，而不是继续只复用普通 `price()`。

## 2026-05-18：筛列、FindFeasible 与 slack 建模复核

本轮继续复核旧 VRP 的 `UpdateRouteSet()` 和当前 TWET 的 repair RMP。旧 VRP 在进入 `FindFeasible` 之前会先求解一次当前子节点 LP：`success = lp.cplex.solve(); success &= lp.IsNoSlack();`。如果当前筛出来的 route set 已经可行且没有人工 slack，就不进入修复；只有 LP 不可行或 slack 非零时，才对当前分支约束调用 `AddSlack()`，再用 Tabu 和 NGBB 的 `FindFeasible()` 补列。

旧 VRP 在 `FindFeasible` 成功以后还要再筛一次列，原因不是为了重新证明可行性，而是控制 child route set 的规模。它不会把 repair 过程中见过的所有 route 都留给子节点，而是遍历当前 LP 里的 route，根据 reduced cost 阈值和 child 的 `feasible_arc` 状态筛出一批低 reduced-cost 且符合分支状态的列；如果数量过多，再按 reduced cost 取前若干条。这是一个列池压缩步骤。它的隐含假设是：当前 LP 的正值列通常 reduced cost 为 0，会被宽阈值保留下来；但严格说，如果低 reduced-cost 列太多，仍可能挤掉一些维持 coverage 所需的列，所以这是工程策略，不是可行性证明。

关于“只加一个虚拟列是否可以”的问题，需要区分两个层次。如果把虚拟列看成一个服务所有 job、满足所有 required arc、满足所有分支条件、成本为 big-M 的普通列，那么它确实可以让 RMP 形式上总可行。但它不适合作为 repair slack 的替代：第一，required arc 或互斥分支状态之间可能互相冲突，一个虚拟列同时满足所有条件未必有明确语义；第二，它会像普通列一样参与覆盖和分支约束，pricing 得到的 dual 不再能清楚指向“到底哪一行缺真实列”；第三，如果虚拟列覆盖所有 job，只要它取 1，就会一次性修掉所有 coverage 缺口，这会把多个不同缺口合并成一个大惩罚，弱化找列方向。旧 VRP 的 `AddSlack()` 并不是加一条虚拟 route，而是在具体分支约束行上加一个人工变量，并用 big-M 目标惩罚；这个人工变量只负责临时打开对应约束，后续必须被真实 route 压回 0。

因此当前 TWET 的做法仍然应该保留“按约束行加 slack”的方向。现在实现中，coverage 行有 `coverSlack_j`，required-arc 行有 `requiredArcSlack_i_j`。coverage slack 是因为 TWET 存在外包变量但不是所有任务都一定能外包，且 child restricted column set 可能暂时缺覆盖某个 job 的内部列；required-arc slack 则对应旧 VRP 当前分支行 slack 的语义，因为外包不能替代一条必须由内部列满足的弧。`isNoSlack()` 会逐个检查这些人工变量是否为 0，只有全部归零后才认为 repair 成功。

所以旧 VRP 和当前 TWET 的差别是：旧 VRP 主要只修当前 branch 行；当前 TWET 同时修 coverage 和 required arc，覆盖面更宽，但每个缺口仍是独立 slack，而不是一个公共虚拟列。这样做的好处是 dual 更有指向性，pricing 能知道应补 job 覆盖还是补 required arc；代价是 repair LP 人工变量更多，但语义更干净。

## 2026-05-18：旧 VRP 节点剪枝与 UpdateRouteSet 初始列来源

本轮进一步梳理旧 VRP 分支树中一个 node 是如何被剪掉的。旧 `Tree.Solve()` 先用 `GenRoute` 构造一个启发式整数解作为 incumbent，`best_cost` 和 `pc.upper_bound` 都来自这个解；根节点 `init_node.route_set` 直接使用这批初始 routes。之后每次从优先队列取出一个 node，先看 `node.sudo_cost > best_cost + tolerance`，如果这个伪下界已经不可能优于 incumbent，就直接 `Clear()` 并跳过。这是进入 LP 求解前的队列层剪枝。

如果 node 没被队列层剪掉，就用 `node.route_set` 新建 `Pool`，再用这个 pool 里的所有 route id 构造当前节点 LP。随后 `PC.Solve(lp)` 做列生成和 cut separation，得到当前节点 LP 松弛值。求解后如果 `lp.solution_cost + tolerance > best_cost`，则该节点由 bound 剪枝：清掉 `lp/node/pool`，不再分支。如果 LP 解已经整数，则用它更新 incumbent，然后该节点自然关闭。如果 LP 解不是整数，则依次尝试 BranchA、BranchB、BranchC、BranchD；某个分支器成功后，只有 `left_node/right_node` 非空的 child 才会加入队列。若 child 的 `route_set == null`，说明 `UpdateRouteSet()` 没有修出可行列集，这个 child 会在分支器内部被清掉，不入队。

因此旧 VRP 的剪枝/关闭位置主要有三处：取队列后用 `sudo_cost` 预剪枝；当前节点 LP 求解后用 `solution_cost >= best_cost` 做 bound 剪枝；分支生成 child 时，如果 `UpdateRouteSet()` 返回 null，则直接剪掉该 child。除此之外，整数 LP 解会更新 incumbent 并关闭当前节点；如果所有分支器都失败，说明在现有分支规则下没有可分支项，该节点也不会继续展开。

关于 `UpdateRouteSet()` 初始求解的 LP 列从哪里来，需要特别说明：它不是为 child 重新建一个 LP。以 `BranchD` 为例，父节点已经在 `Tree.Solve()` 中用 `node.route_set` 建好 LP，并经过 `PC.Solve(lp)` 完成当前节点列生成。进入 `BranchD.Branch(lp)` 后，代码在同一个 `lp` 上修改/加入对应 child 的分支约束，例如左分支调用 `lp.ForceArcValue(...,0,0)`，右分支把同一个 branch range 改成 `[1,1]`，然后调用 `UpdateRouteSet(..., childNode, lp)`。所以 `UpdateRouteSet()` 开头的 `lp.cplex.solve()` 求解的是“父节点当前 LP 的列集合 + 当前 child 分支约束”的模型。这里的初始列就是父节点当前 LP 里的 `lp.route_index`，也就是父节点初始 route set 加上在父节点 `PC.Solve()` 过程中生成并接入 LP 的列。

如果这批父节点当前列在 child 分支约束下已经可行并且 `IsNoSlack()` 为真，则不需要 `FindFeasible`。如果不可行，就在同一个 LP 上对当前分支行加 slack，然后 Tabu/NGBB 往这个 LP 里继续补列。修复成功以后，`UpdateRouteSet()` 遍历当前 LP 的 `route_index`，读取每条 route 变量的 reduced cost，并检查它是否违反 child 的 `feasible_arc`。满足 `rc < m_addin_red_cost` 且不含 forbidden arc 的 route 进入候选；候选太多则按 reduced cost 排序，最多保留 `m_initial_col_number` 条，作为 child 的 `route_set`。也就是说 child 最终拿到的不是全部父节点列，也不是全部 repair 新列，而是“当前 LP 中低 reduced cost 且 child 分支兼容的一批列”。

## 2026-05-18：child 列修复时机与当前延迟策略

本轮确认 `sudo_cost` 的来源后，可以明确旧 VRP 与当前 TWET 在 child 列修复时机上的差异。旧 VRP 在分支器生成左右 child 时，就立即调用 `UpdateRouteSet()`：它用父节点当前 LP 加上 child 分支约束测试可行性，必要时加 slack 并调用 `FindFeasible()` 补列，然后筛出 child 的 `route_set`。因此一个父节点一旦分支，就可能马上为左右两个 child 都做一次列修复，即使其中某个 child 后续因为队列顺序、时间限制或更好的 incumbent 而不一定真正被展开。

当前 TWET 选择了更延迟的策略：分支时只记录 child 的分支状态和从父节点筛出来的候选列，不在 `Tree` 里立即做完整 repair。只有当 child 真正从优先队列中弹出并进入 `PC.solve()` 时，才构造该节点 RMP；如果 restricted RMP 不可行，再打开 slack repair mode 并调用 `findFeasible()` 补列。这样做在框架上更懒，也更可能高效，因为它避免了对尚未真正展开的 child 提前做昂贵的列修复。

这个差异不改变 BPC 的逻辑语义：旧 VRP 是“生成 child 时修复 route set”，当前 TWET 是“求解 child 时修复 restricted RMP”。由于旧 `sudo_cost` 本身只是父节点 LP 值，并没有通过 child repair 得到更精确的排序 bound，所以当前延迟修复不会损失旧代码用于队列排序的那类信息。真正需要注意的是，如果后续希望在 child 入队前就用更强的 child LP 下界排序，那才需要提前做类似旧 `UpdateRouteSet()` 的修复和求解；当前阶段没有这个需求。

## 2026-05-18：旧 VRP FindFeasible/AddSlack 风险补充

本轮进一步确认旧 `UpdateRouteSet()` 的两个边界风险。第一，`FindFeasible` 是有限补列过程；如果 Tabu/NGBB 在列数和时间预算内仍没有把 slack 压回 0，child 会被返回 null 并丢弃，但这并不等价于完整列空间下严格不可行。第二，旧 `AddSlack` 只对当前新分支约束行加人工变量，但 child LP 不可行不一定只由当前分支约束造成。由于 child 列集经过 reduced cost、数量上限和分支兼容性筛选，coverage、历史分支约束或其他约束也可能因为列被筛掉而暂时不可行。

旧代码能这么做，主要依赖工程上的宽松筛列：保留下来的列通常满足之前所有分支状态，且数量和 reduced-cost 阈值足够大时，coverage 大概率不出问题。因此它把 repair 重点放在当前刚加入的分支约束上。这个判断实践上可能有效，但不是严格证明。当前 TWET 保留 coverage slack 和 required-arc slack，就是为了避免 restricted columns 暂时缺 coverage 或缺 required arc 时，把原本完整列空间可行的 child 误判为不可行。

## 2026-05-18：UpdateRouteSet 首次 LP 中分支约束的位置

本轮确认旧 VRP `UpdateRouteSet()` 的首次 LP 求解已经带上 child 分支约束。arc 分支会先 `ForceArcValue(i,j,0,0)` 或把同一条弧约束 bounds 改成 `1,1`，车辆数分支会先修改车辆数行上下界，然后才调用 `UpdateRouteSet()`。所以第一次 LP 可行时，说明父节点当前列集合已经能满足该 child 的 restricted LP；第一次 LP 不可行时，当前分支约束是触发条件，但不一定是唯一原因。restricted columns 可能缺少替代覆盖列、缺少满足历史分支的列，或者无法同时满足 coverage、历史分支和当前新分支。

这解释了为什么只给当前分支行加 slack 是一个实用近似，而不是严格可行性证明。arc 分支中的列不是先筛成“每条都满足新 arc 状态”再求 LP，而是先在 LP 中加入聚合 arc 行；修复成功后才根据 child 的 `feasible_arc` 筛选返回 route set。车辆数分支也不是单列自动满足，而是所有 route 变量之和共同满足车辆数上下界。

## 2026-05-18：车辆数分支下的列筛选

旧 VRP `BranchA` 的车辆数分支只修改车辆数行 `cus2rng[0]` 的上下界。每条 route 在车辆数行中的系数都是 1，所以单条 route 不存在“是否满足车辆数分支”的过滤条件；满足与否取决于 LP 选择了多少条 route。`UpdateRouteSet()` 成功后返回 child route set 时，只按 reduced cost 阈值和保留列数量筛选，不像 arc 分支那样再按 `feasible_arc` 过滤。

## 2026-05-18：arc 分支不可行的解释

arc 分支下，如果首次 restricted LP 不可行，更准确的解释是“新 arc 分支约束和当前受限列集合发生冲突”。父节点列通常满足历史分支状态，但新 arc 约束可能把关键列压掉，剩余列不一定还能满足 coverage 和历史约束。因此不可行既不能简单归因于当前分支行，也不能说和当前分支无关，而是旧列集合、历史分支和新 arc 状态共同作用的结果。

## 2026-05-18：旧 VRP 的列筛选次数

旧 VRP 不是在节点每次求 LP 前都筛列。节点弹出后，`Tree` 直接用 `node.route_set` 构造 LP；这个集合已经是在父节点分支时准备好的。真正的筛选发生在 `UpdateRouteSet()` 成功之后，每个 child 一次：遍历当前 `lp.route_index`，按 reduced cost 阈值和数量上限保留列；车辆数分支只按这两个条件筛，arc/customer/bid 相关分支还会额外按 `feasible_arc` 过滤 forbidden arc。

## 2026-05-18：修正 UpdateRouteSet 风险判断

本轮确认旧 VRP 的 `UpdateRouteSet()` 不是先筛选 child 列再求首次 LP，而是先把 child 分支约束写进父节点当前 LP，直接用当前 `lp.route_index` 求解；只有首次 LP 可行或 slack repair 成功后，才在末尾筛选 child 的初始 `route_set`。因此，之前把“首次 LP 前筛列导致 coverage/历史分支被筛坏”作为主要漏洞的判断不准确。

更准确的判断是：如果首次 LP 可行，当前可行基通常会被末尾筛选保留下来；如果首次 LP 不可行，当前新分支约束确实是最直接的新冲突来源，只给当前分支行加 slack 在旧代码流程里是合理修复方向。仍需保留的只是边界风险：`FindFeasible` 有有限预算，且末尾 reduced-cost/数量筛选在极端退化或列数过多时不严格保证保留一个完整可行基。

## 2026-05-18：分支 slack 与分支 dual 的当前判断

本轮基于旧 VRP 代码重新确认分支修复语义。旧 `UpdateRouteSet()` 中如果 child 分支约束加入后 restricted LP 暂时不可行，`AddSlack` 只加在当前分支约束行上；它不是给 coverage 全部加人工变量。复核后认为这在旧流程里是合理的，因为首次 LP 前没有先筛 child 列，父节点当前列集合仍在，新的不可行主要是当前 child 分支约束与现有列集合不兼容。对 TWET-BPC，如果后续希望更贴近旧 VRP，可以把 repair slack 收敛到“分支约束 slack”为主，coverage slack 只作为额外保守兜底或调试开关，而不是默认必须存在。

但 TWET 有一个额外情况：tariff segment 分支不是 route 列上的约束，而是 master 中外包分段变量 `z_s` 的 bounds/segment 选择约束。它如果导致 restricted RMP 暂时不可行，本质上也是分支约束导致的 repair 问题，可以引入对应的 tariff-branch slack 或把 segment bound 改成显式 range 以便加 slack。这个 slack 的作用和 arc/车辆数分支 slack 类似，都是让 repair LP 可解并产生有方向的 dual；区别是 tariff 分支不直接改变 private-route pricing 的结构，它主要通过 coverage、外包 baseline、segment 约束等 dual 间接影响是否需要生成更多内部列来替代外包。

旧 VRP 的分支 dual 在正式 pricing 中是会被考虑的，不是只在 `UpdateRouteSet()` 中临时使用。车辆数分支对应 `cus2rng[0]`，`GetDual()` 会把它读入 `mu[0]`，pricing 初始化时 `label.m_reduced_cost = -lp.mu[0]`，等价于每条 route 扣除一份车辆数约束 dual。arc 分支对应 `branch2rng[(i+2)*n+j]`，`GetDual()` 会把该 dual 加到 `arc_mu[i][j]`，后续所有 GC/GCTabu/GCNGBB 的弧扩展都用 `distance(i,j) - arc_mu[i][j] - mu[j]`。因此，即使 child 的列集合已经按 forbidden arc 兼容，分支约束仍必须留在真实 RMP 里，其 dual 也必须进入 pricing；否则 reduced cost 会错，列生成可能提前停止。

当前 TWET-BPC 的方向和这个是一致的：机器数 dual 通过 `lp.getMachineDual()` 在 pricing 初始化时扣除，arc required dual 通过 `lp.getArcDual(from,to)` 在扩展弧时扣除。需要后续进一步确认的是 tariff segment 分支的 dual 是否以合适方式影响外包变量和 private-route pricing；如果 tariff 分支只通过变量 bounds 表达，缺少显式 row slack 和可读 dual，后续做 repair 时可能需要改成显式约束行。

## 2026-05-18：后续 TWET 修复流程的调整判断

本轮明确后续如果要完全模仿旧 VRP，TWET-BPC 的 child repair 应该改成：在生成 child 时，先用父节点当前列集合加上 child 分支约束求一次 LP；若可行，再按 reduced cost 和 child 兼容性筛出 child seed；若不可行，再只给当前分支约束加 slack 并调用 FindFeasible 修复。按照这个流程，coverage slack 不应作为默认修复变量，因为父节点当前列集合在加入新分支前本来可行，新的不可行主要来自当前分支状态与现有列集合冲突。之前 TWET 保留 coverage slack，是因为当前实现更偏向“child 先拿筛过的 seed，弹出后再修复”，这种流程下 restricted columns 可能已经缺 coverage，coverage slack 是额外兜底。若流程改成旧 VRP 形式，coverage slack 可以删除或降级成调试开关。

对于 tariff segment 分支，建议仍保持 `z_s` 原始变量 bounds 为 `[0,1]`，分支时额外加显式约束行，而不是直接只改变量上下界。禁止分支可写成 `z_s <= 0`，修复模式下变成 `z_s - slack <= 0` 或等价的 `z_s <= slack`；强制分支可写成 `z_s >= 1`，修复模式下变成 `z_s + slack >= 1`。这样 slack 仍只挂在当前 tariff 分支约束上，语义和 arc/车辆数分支一致：临时放松当前分支行，让 repair LP 可解并产生有方向的 dual。区别是 tariff 分支不直接改变 private-route pricing 的图结构，它主要通过 master 中外包量、coverage dual 和 segment 约束的变化间接影响是否需要生成更多内部列。

旧 VRP 的 arc 分支同时做两件事：一是修改 `node.feasible_arc`，让 pricing 不再生成 forbidden arc；右分支还会禁掉相关节点的其他出入弧，使生成的新列天然符合 required arc 的结构。二是仍然在 RMP 中保留聚合 arc 分支行，并把该行 dual 加入 `arc_mu[i][j]`。这不是矛盾。修改子图只能控制后续新生成的列，不能替代当前 LP 中已有列上的聚合约束，也不能替代 RMP 的 dual 贡献。只要分支行存在于 RMP，pricing 的 reduced cost 就必须包含该行 dual；否则列生成的 reduced cost 不对应当前 RMP，可能误判没有负 reduced-cost 列。即使某些分支行在整数语义上可能被 coverage 和子图限制间接推出，旧代码仍保留它们作为 LP 行和 dual 来源，这是更稳的实现。
## 2026-05-18：按旧 VRP 流程调整 child repair 和分支 slack

本次把 TWET-BPC 的 child 列继承和 repair slack 进一步改成接近旧 VRP `UpdateRouteSet()` 的流程，但仍保留当前“child 出队时才真正修复”的时机。具体做法是：`Tree` 在生成 child 时不再提前按新分支状态或 reduced cost 筛父节点列，而是直接把父节点当前 restricted columns 传给 child；child 被弹出并进入 `PC.solve()` 后，`LP` 先用“父节点列集 + 当前 child 分支约束”求一次 restricted LP。如果第一次 LP 可行，再按当前 reduced cost 和分支兼容性筛出正式 child 列集并重建 LP；如果不可行，再进入 slack repair。

repair slack 的语义也随之收紧。之前 TWET 版本会给 coverage 行和 required-arc 行都加人工 slack，这更像额外兜底。根据重新梳理后的旧 VRP 流程，现在默认只给当前新分支行加 slack：机器数上分支用 `sum(lambda) - slack <= ub`，机器数下分支用 `sum(lambda) + slack >= lb`；forbidden arc 用 `sum(x_ij) - slack = 0`，required arc 用 `sum(x_ij) + slack = 1`；tariff segment forbidden/required 分别用 `z_s - slack <= 0` 和 `z_s + slack >= 1`。coverage 不再默认加 slack，它如果不可行，应通过 pricing/外包列修复；这和旧 VRP 只针对当前分支行做 `AddSlack` 的语义一致。

为了支持 tariff segment 分支 repair，`z_s` 的 `[0,1]` 也从变量上下界改成了显式约束行 `0 <= z_s <= 1`，分支时再额外加入 `z_s <= 0` 或 `z_s >= 1`。这样 branch row 本身可以挂 slack，也便于后续读取或分析该分支约束。arc 分支也不再只建立 required arc 行，而是 required 和 forbidden 都在 RMP 中建立聚合分支行；即使 pricing 侧已经通过子图状态避免生成 forbidden arc，这些 RMP 行仍然需要保留，因为父节点继承来的旧列需要由该行压掉，并且该行 dual 必须进入 route pricing 的 reduced cost。

当前仍有一个需要后续测试关注的边界：child 第一次 LP 可行后，按 reduced cost 重新筛正式列集可能在极端情况下删掉某些维持 coverage 的高 reduced-cost 列。旧 VRP 本身也依赖较宽的 reduced-cost allowance 和保留列数降低这个风险；当前 TWET 版本保持同样思路。如果筛列后 LP 再次不可行，会进入 repair，但由于 coverage slack 已删除，说明筛列策略可能需要进一步加“保留当前正值列/覆盖关键任务列”的保护。

## 2026-05-18：为什么正式 child pricing 阶段仍保留分支行

本轮集中讨论“如果 child 已经完成首次可行性检查，后续 restricted columns 和 pricing 新列都满足分支约束，RMP 中的分支约束行是否还需要保留”。结论是：不能统一删除。这里要区分列级兼容和解级聚合约束。

对于 forbidden arc，若 child 已经筛掉所有含禁用弧的旧列，并且 pricing 图也禁止继续生成该弧，则 `sum x_ij = 0` 在正式 child LP 中通常是冗余的。保留它主要是为了流程统一和首次 LP/repair 语义清楚；后续如果确认该行没有任何非零系数，删除也可以作为性能优化，但不是当前优先项。

对于 required arc，不能理解成“每条生成列都必须包含这条弧”。列生成中的一条列是一台机器上的一个内部加工序列，master 会选择多条列共同覆盖任务。右分支要求的是聚合变量 `sum_r a_ij^r lambda_r = 1`，即最终解中恰好有一条被选列使用该弧；它不是要求所有列都使用该弧。当前代码中的 `Node.isColumnCompatible()` 也只过滤 forbidden arc，并不会要求每条列都包含 required arc。即使右分支会把 `i` 的其他后继、`j` 的其他前驱禁掉，仍然可能存在很多不含 `i,j` 的合法列；在带外包的模型里，任务 `i,j` 还可能被外包变量覆盖。因此 required arc 行必须保留，否则 child 可以不选择任何含该弧的内部列，从而违背该分支。

机器数分支和 tariff segment 分支也都是解级约束。每条内部列在机器数行中的系数都是 1，但单条列没有“是否满足机器数上下界”的概念，只有所有被选列的总数才满足。tariff 的 `z_s` 更不是 route pricing 生成的列，它是 master 中的外包分段变量；`z_s <= 0` 或 `z_s >= 1` 必须作为 master 约束保留。

所以更准确的判断是：pricing 侧的图/状态限制只能保证“新生成列不违反列级 forbidden 状态”，不能替代 RMP 中的聚合分支行。RMP 分支行还负责约束父节点继承来的旧列、表达解级分支语义，并提供当前节点 reduced cost 所需的 dual。若删除 required arc、机器数或 tariff 分支行，当前节点的 LP 松弛就不再是该 branch node 的松弛，pricing 的 reduced cost 也不再对应真实 RMP。

## 2026-05-19：forbidden arc 行的后续冗余判断

进一步确认后，分支约束在正式 child 列集中的保留策略可以区分处理。`forbidden arc` 是列级禁用约束：如果 child 已经完成筛列，所有含该弧的旧列都被删掉，并且 pricing 图也不再生成该弧，那么 RMP 中的 `sum x_ij = 0` 行在后续正式 LP 中确实可以视为冗余。它在首次 child LP 和 repair 阶段仍然需要存在，用来压掉父节点继承来的旧列并产生 repair/dual 语义；但在正式筛列成功以后，可以作为性能优化考虑删除或不再建立。

这个结论不适用于 `required arc`、机器数和 tariff segment。`required arc` 是解级聚合约束，含义是最终选中列集合中必须有一条列使用该弧，而不是每条列都必须使用该弧；机器数约束约束的是被选内部列数量之和；tariff segment 约束的是 master 中的外包分段变量 `z_s`。这三类约束都不能被“列已经兼容当前分支状态”替代，后续正式 RMP 中仍应保留对应行，并让其 dual 进入 reduced cost 或 master 求解逻辑。
## 2026-05-19：TWET-BPC 与旧 VRP 框架流程整体复核

本次重新按旧 VRP 的 `Tree / PC / BranchD.UpdateRouteSet / GCTabu / GCNGBB` 主流程，对当前 `TWETBPC` 下的 branch-and-price 代码做了一轮整体复核。当前结论是：如果只看 no-cut 的 branch-and-price 主流程，现在的框架已经基本对齐旧 VRP 的核心逻辑；如果按完整 BPC 理解，则 cut 接入仍只是占位，不能认为已经是完整 branch-price-and-cut。

1. 主流程已经基本对齐的部分

当前 `Tree.solve()` 的节点流程是：先由启发式 seed 构造初始列和 incumbent，再从优先队列弹出节点，构造该节点 restricted master，调用 `PC.solve()` 完成列生成，得到 LP relaxation 后检查整数性、更新 incumbent、按 bound 剪枝，最后按 tariff segment、machine count、arc 的顺序分支。这个结构和旧 VRP 的 `Tree.Solve()` 对应，只是当前把 child 的列修复推迟到 child 真正出队时做，而旧 VRP 是在父节点分支时立刻调用 `UpdateRouteSet()`。这属于时机和封装差异，不改变逻辑含义，而且当前延迟修复通常更省，因为不会提前为最终未被展开的 child 做昂贵的 repair。

child 列集合的处理现在也已经按旧 VRP 的语义修正。`Tree.prepareChildSeedColumns()` 先把父节点当前 restricted columns 原样传给 child，不提前按新分支状态或 reduced cost 筛列；child 出队后，`LP.construct()` 用“父节点列集合 + 当前 child 分支行”先求一次 LP。如果可行，再调用 `resetRestrictedColumnsByCurrentReducedCost()` 筛成正式 child 列集；如果不可行，则进入 `repairInfeasibleMaster()`，只对当前新增分支行加 artificial slack，再用 heuristic pricing 和 exact pricing 补列。这和旧 VRP `UpdateRouteSet()` 的“先带 child 分支行求父节点当前 LP，必要时 AddSlack + FindFeasible，成功后再筛 route set”在流程意图上是一致的。

pricing 层也基本符合旧 VRP 的分层：当前先跑 `HeuristicPricingEngine`，再跑 `PaperDominanceExactPricingEngine` 或普通 `ExactPricingEngine`。repair 模式下，启发式 engine 会反复补列直到耗尽或 slack 归零，然后 exact engine 兜底；这和旧 VRP “优先用 GCTabu.FindFeasible，Tabu 耗尽后用 GCNGBB.FindFeasible”的思路一致。普通列生成里，当前 PC 会把 heuristic 和 exact 在同一轮 dual 下生成的列合并后统一加入，再重新求解；旧 VRP 的启发式内部可能先加列并更新 LP 后再进 exact。这是封装和效率差异，不影响正确性，因为下一轮 pricing 会基于新 dual 继续找列。

分支约束和 pricing dual 的方向也基本正确。machine count 约束的 dual 在 pricing 初始 label 中扣除；arc branch 约束的 dual 通过 `getArcDual(from,to)` 在扩展弧时扣除。required arc、machine count 和 tariff segment 都是解层面的聚合约束，必须保留在 RMP 中；forbidden arc 在正式筛列成功后理论上可能变成冗余行，但当前保留它主要是为了统一流程和处理父节点继承列，不是逻辑错误。

2. 当前仍然存在的主要问题

第一个问题是 cut 部分还不是完整 BPC。`SubsetRowCutGenerator` 当前明确是 placeholder，返回空 cut；`TWETCut` 和 `CutPool` 也只是元数据容器，`LP.addCuts()` 只是把 cut id 放进 activeCutIds，并没有在 `LP.buildModel()` 里真正添加 cut 行，也没有把 cut 系数写入已有列和新列。进一步说，`PC.solve()` 现在即使某天真的 separated 出 cuts，也只是 `addCuts()` 后重新求一次 LP，没有像旧 VRP 的 `PC.Solve()` 那样回到 price-and-cut 外层循环继续 pricing。因此当前 no-cut branch-and-price 可以评价为基本成形，但完整 BPC 还没有真正接上 cut。

第二个问题是 child 正式筛列后的可行性保护仍偏工程化。`resetRestrictedColumnsByCurrentReducedCost()` 只按 reduced cost 阈值和列数上限选择低 reduced-cost 列，没有显式强制保留当前 LP 中所有正值列或当前可行基相关列。旧 VRP 也依赖较宽的 `m_addin_red_cost` 和 `m_initial_col_number` 降低风险，但它同样不是严格证明。当前 TWET 版本在筛列后会重新求 LP；如果筛列极端情况下删掉了维持 coverage 或其他约束所需的列，而 repair slack 又只挂在当前新增分支行上，那么该 child 可能被误判为 infeasible。建议后续优化为：筛列时先无条件保留当前 LP 正值列，再按 reduced cost 补足候选列。这样更接近“保留当前可行解，再压缩列池”的安全语义。

第三个问题是求解结果里的 `bestBound` 报告还不严格。当前 `Tree.solve()` 用已处理节点 LP 值的最小值更新 `bestBound`，这更像“见过的最好下界”，不是严格的全局 open-node lower bound。旧 VRP 最后会结合队列中的 `sudo_cost` 做 lower bound 汇总。当前剪枝本身主要依赖当前节点 LP 值和 incumbent，不会因为这个字段直接错剪，但最终输出的 bound/status 可能偏乐观或不够严谨。后续如果要把 BPC 结果作为精确证明，需要把 open queue 的节点 bound、已处理节点 bound 和 node limit 状态分开记录。

第四个问题是当前 RMP 默认假设存在 outsourcing tariff function。`LP.buildOutsourcingTariffConstraints()` 总会添加 `sum z_s = 1`。如果某个数据没有外包分段函数，`outsourcingTariffSegments` 为空时会形成空表达式等于 1 的不可行模型。现在带外包的数据路径下通常有 tariff block，所以不一定触发；但如果要兼容无外包版本，需要加 dummy segment `G(0)=0` 或在无 tariff 时跳过整组外包分段约束。

3. 与旧 VRP 不一致但目前可接受的地方

当前分支顺序是 tariff segment、machine count、arc。旧 VRP 没有 tariff segment 分支；对于当前 SP2 外包分段建模，先分 `z_s` 是合理的，因为即使内部列变量和 arc 都整数，LP relaxation 中 `z_s` 仍可能是多个 tariff segment 的凸组合。machine count 再到 arc 的顺序和旧 VRP 的车辆数优先、再路由结构分支是一致的。

当前 `PaperDominanceGraph` 的设计和论文伪代码不是逐字相同，但主逻辑是对齐的：每个 reachable-set node 维护真实 label 集合和 envelope，而不是只保留一个无法恢复路径的聚合 label；插入时先用 superset node 的 `g` envelope 判断 set dominance，不能占优则插入 label，并沿 successor 传播、删除被 predecessor envelope 占优的 label。这个处理比“node 里只有一个聚合 label”更适合 TWET，因为最终列需要恢复具体序列，而序列影响 setup time/cost 和真实列成本。当前没有发现框架级错误；后续若要提高效率，可以继续缓存 ancestor/descendant 或优化 terminal node 查找，但这属于性能问题。

当前 exact pricing 的 `maxExactPricingColumns` 只是单轮返回列数量上限，不等价于“证明已经没有负 reduced-cost 列”。但 PC 会在加列、重解 LP 后继续 pricing，直到所有 engine 都没有返回新 active 列。因此只要 exact pricing 在某一轮没有被 active/duplicate 逻辑误空，单轮上限本身不是 correctness 风险。现在已经避免 active column 和本轮 duplicate 消耗返回上限，这一点和旧 VRP “计入 add-in 前查 pool”方向一致。

4. 当前建议的后续检查顺序

短期如果继续完善 no-cut branch-and-price，优先处理 `resetRestrictedColumnsByCurrentReducedCost()` 的正值列保护和 bound/status 报告，这两个不涉及大重构，能直接提高稳健性和结果可信度。cut 相关暂时可以继续保持 placeholder，但需要在文档和输出中明确“当前是 no-cut B&P，不是完整 BPC”。如果后续真的接 SRI/cut，再必须同时完成三件事：RMP cut 行建模、列的 cut 系数接入、加 cut 后回到 pricing 循环。

## 2026-05-19：关于 child 筛列是否会丢掉基解列的补充

针对 `resetRestrictedColumnsByCurrentReducedCost()` 是否会把维持可行性的基解列筛掉，本次补充澄清如下。正常情况下，用户的判断是对的：如果 child 第一次 LP 已经可行，那么当前正值基列的 reduced cost 通常为 0；当前 `branchSeedReducedCostAllowance=5000.0` 很宽，所以这些列会进入候选集合。只要 `branchSeedColumnLimit` 足够大，当前基解对应的列大概率会保留下来，从而筛列后的 restricted RMP 仍然可行。

前面说的“可能筛掉可行性列”不是指常规情况下必然会发生，而是指这个筛列步骤在代码上没有显式保证“所有当前正值列必须保留”。当前实现会先收集所有 reduced cost 小于阈值的列，然后按 reduced cost 排序，再取前 `maxColumns` 条。如果存在大量 reduced cost 为 0 或非常接近 0 的退化列，且候选数超过 `branchSeedColumnLimit`，排序的 tie-break 只按 column id 处理，那么某些当前正值列理论上可能排在截断位置之后。这个风险在当前参数下很低，但它是工程假设，不是数学保证。

因此更稳的后续修改不是改变 reduced-cost 筛列思想，而是在筛列前先把当前 LP 中 value > tolerance 的列无条件放入 selected，再用低 reduced-cost 列补足到上限。这样可以保留旧 VRP “用 reduced cost 压缩 child route set”的流程，同时把“当前可行基被筛掉”的边界风险消掉。

## 2026-05-19：`resetRestrictedColumnsByCurrentReducedCost()` 的调用时机

本次确认 `resetRestrictedColumnsByCurrentReducedCost()` 只有两个调用点，语义都是“child 已经有一个可行 LP 后，把当前列池筛成正式 child restricted column set”。第一个调用点在 `PC.solve()` 中：child 节点第一次 `lp.solveRelaxation()` 已经可行时，直接按当前 LP 的 reduced cost 和分支兼容性筛列，然后重新建 LP 求解。这对应旧 VRP `UpdateRouteSet()` 中“首次 child LP 可行后筛 route set”的路径。

第二个调用点在 `repairInfeasibleMaster()` 末尾：如果 child 第一次 LP 不可行，则先进入带 artificial slack 的 repair LP，通过 `engine.findFeasible(lp)` 反复补列；只有当 `lp.isNoSlack()` 为真，即 slack 被真实列压回 0 后，才调用该函数筛列，然后关闭 repair mode，回到正常 RMP 求解。这对应旧 VRP `FindFeasible()` 成功以后再筛 route set 的路径。

因此它不是每轮普通 pricing 后都会调用，也不是 root 节点默认调用。它只服务于 child 的初始列集压缩：要么 child 首次 LP 已经可行后筛一次，要么 findFeasible/repair 成功后筛一次。

## 2026-05-19：child 筛列增加正值列优先保留规则

根据前面的边界分析，本次把 `LP.resetRestrictedColumnsByCurrentReducedCost()` 的筛列规则改成两阶段。第一阶段先检查当前 LP 中列变量值为正的列，并在分支兼容的前提下无条件放入 child 的正式 restricted column set；第二阶段才按 reduced cost 从小到大补充其他候选列，直到达到 `branchSeedColumnLimit`。

这个修改的目的不是改变旧 VRP 的 reduced-cost 筛列思想，而是补上一个显式安全约束：child 首次 LP 可行或 repair 成功时，当前正值列构成当前 LP 解的一部分；理论上这些基列 reduced cost 通常为 0，但如果存在大量 reduced cost 为 0 的退化列，单纯排序截断仍可能把某些正值列挤掉。现在先保留正值列，就能保证“当前已经找到的可行组合”不会被筛列上限截断。`branchSeedColumnLimit` 仍然控制补充的非正值低 reduced-cost 列数量；如果正值列本身超过该上限，则以上限为软约束，优先保证可行性。

验证：针对 `Basic/Common/HEU/Output/TWETBPC` 子集运行 `javac -encoding UTF-8 -cp D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;target\classes -d target\twetbpc-compile ...` 通过，仅有历史 deprecated API 提示。

## 2026-05-19：`bestBound` 的语义和线性外包函数的影响

本次澄清 `Tree.solve()` 里 `bestBound = Math.min(bestBound, solution.getObjectiveValue())` 的问题。这里不是说某个节点的 LP relaxation 不能作为下界；对最小化问题，每个节点完整列生成后的 LP 值当然是该节点子树的下界。问题在于当前代码取的是“已经处理过节点 LP 值的最小值”，这个值通常在 root 节点就达到最小，后续子节点 LP bound 往往只会更高，因此 `bestBound` 很可能长期停留在 root LP bound，无法反映分支树收敛过程中全局下界被逐步抬高。

严格的 B&B 下界应该更接近“当前仍未关闭的 open nodes 的最小下界”。如果队列为空并且所有节点都被关闭，则最终下界应当等于 incumbent；如果达到 node limit 或 time limit，则应报告 open queue 中最小节点下界作为当前全局 LB。当前字段作为“见过的最弱有效下界”通常仍是合法下界，但太松，不能作为最终证明质量的严谨指标。

如果用户问的是目标函数本身是否线性，例如外包函数 `G(B)=B`，这和 `bestBound` 的树搜索语义是两件事。`bestBound` 的报告问题不会因为目标是线性就消失；即使 RMP 目标完全线性，root LP bound 也可能一直被当前 `min` 逻辑保留下来。另一方面，如果讨论的是外包 tariff segment 变量，`G(B)=B` 这种全局线性函数确实不需要复杂的 segment 选择分支：可以只保留一个覆盖全域的线性段，或者直接用线性成本项建模。若人为把同一条直线切成多个 segment，LP relaxation 中 `z_s` 可能分数，但目标值不会因此变错；这时继续对 `z_s` 分支只是冗余工作，不是必要的精确性条件。

## 2026-05-19：按旧 VRP 语义修正 `bestBound` 汇总

本次把 `Tree.solve()` 中的 `bestBound` 汇总逻辑改成更接近旧 VRP 的 `sudo_cost/lower_bound` 语义。原实现每处理一个节点就取 `Math.min(bestBound, solution.obj)`，这会让最终 LB 很容易停在 root LP bound。新实现中，节点处理期间的报告 bound 取“当前节点 LP bound”和“open queue 中最小 pseudoCost”的较小值，并且不允许超过 incumbent；求解结束时，如果队列已经为空，说明所有节点已经关闭，最终 LB 直接取 incumbent；如果因为节点上限停止且队列非空，则最终 LB 取 `queue.peek().pseudoCost`，即当前 open nodes 中最小的伪下界。

这个修改不是改变剪枝逻辑，而是修正输出 bound 的含义，使其更接近旧 VRP 最后用 `queue.peek().sudo_cost` 修正 lower bound 的做法。当前 `pseudoCost` 仍然是 child 入队时继承的父节点 LP bound，因此它是排序和报告用的保守伪下界，不是 child 自己完整求解后的精确 bound；但这已经比“永远保留已处理节点最小 LP 值”更符合分支树状态。后续如果要进一步严谨，可以在 child 入队前或出队后维护每个 open node 的更精确 LP bound。

同时补充外包线性函数的判断：如果 `G(B)` 是全局线性函数，例如 `G(B)=B`，只要数据中至少保留一个 tariff segment 覆盖可能的 baseline，就不会因为 segment 建模影响目标值。此时 tariff segment 分支可以视为冗余优化点，而不是精确性必需项。

验证：针对 `Basic/Common/HEU/Output/TWETBPC` 子集运行 `javac -encoding UTF-8 -cp D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;target\classes -d target\twetbpc-compile ...` 通过，仅有历史 deprecated API 提示。
## 2026-05-19：关于 lower_bound 取 min 和 incumbent 截断的补充

本次进一步澄清旧 VRP 里 `lower_bound = min(current lower_bound, queue.peek().sudo_cost)` 的含义。这里的 `min` 不是严格意义上“open nodes 全局下界的最佳更新方式”，而是旧代码的保守汇总写法。旧 VRP 在处理非整数节点时会把 `lower_bound` 临时设成当前节点 LP 值，最后如果队列非空，再用 open queue 中最小的 `sudo_cost` 修正；因为历史记录里可能还保留 root 或更早节点的较低 bound，所以继续取 `min` 会让报告值偏保守，甚至长期停在 root LP bound。这个值通常仍是合法下界，但不一定是最紧的当前 open-node 下界。

当前 TWET-BPC 没有在最终返回时继续照抄这个 `min`。现在求解结束时如果队列为空，说明所有节点已经关闭，最终 `bestBound` 直接取 incumbent；如果队列非空，说明因为节点上限等原因还有 open node，则最终 `bestBound` 取 `queue.peek().pseudoCost`，也就是当前 open queue 里最小的伪下界。运行中展示用的 bound 可以取当前节点 LP bound 和 open queue 最小伪下界的较小值，只作为状态输出，不改变剪枝逻辑。

同时澄清 `bound` 超过 incumbent 时截到 incumbent 的语义。对最小化问题，任何有效 lower bound 都不应该大于当前 upper bound/incumbent；如果报告层出现 `LB > incumbent`，通常是因为伪下界、数值误差或状态汇总方式导致的输出异常。把报告值截到 incumbent 只是为了保持输出区间合法，不表示算法可以因此直接宣告最优。真正可以宣告最优的条件仍然是 open queue 为空，或者所有 open node 的有效下界都已经不小于 incumbent 并被正常剪掉。

旧 VRP 真正用于证明和剪枝的不是末尾这个 `min`，而是搜索过程中的两处 bound 剪枝。第一处是在节点刚从队列弹出时检查 `node.sudo_cost > best_cost + tolerance`，如果成立直接跳过该节点，不再建 LP；第二处是在该节点 LP/列生成完成后，如果 `lp.solution_cost + tolerance > best_cost`，则该节点子树被 bound 剪掉。`sudo_cost` 是节点入队时继承的父节点 LP bound，通常比较弱，但它仍然是该子树的有效下界；`lp.solution_cost` 是当前节点真正求解后的更强下界。

当前 TWET-BPC 已经有第二类剪枝，即节点 LP 求解后用 `solution.getObjectiveValue()` 和 incumbent 比较并关闭节点；但还没有完全接上旧 VRP 第一类“弹出节点前按 `pseudoCost` 预剪枝”。因此当前正确性不依赖这个预剪枝，最多会多求一些本可以提前跳过的节点。如果 `PriorityQueue` 以 `pseudoCost` 从小到大排序，那么当 `queue.peek().pseudoCost >= incumbent` 时，理论上所有剩余 open nodes 的伪下界都不小于 incumbent，可以直接终止或清空队列；旧 VRP 只是逐个弹出并跳过，没有利用这一点做批量清空。后续如果要进一步优化，可以在 TWET `Tree.solve()` 弹出节点前或弹出后建 LP 前加这层判断。

进一步复核旧 VRP 后，需要修正一个表述：旧代码的 `lower_bound` 不是完全一直停在 root bound。初始化时它确实先被设为初始启发式成本，随后在每个非整数、需要继续分支的节点处会执行 `data.m_configure.lower_bound = lp.solution_cost`，调试输出里的 gap 也使用 `min(data.m_configure.lower_bound, lp.solution_cost)`。因此运行过程中它会跟随当前被分支节点的 LP 值更新。问题在于，如果搜索因时间限制或其他原因提前停止，最终 `lower_bound` 只用最后记录值和 `queue.peek().sudo_cost` 做保守汇总，并不严格等于所有 open nodes 的最紧全局下界；这种情况下 gap 可能偏大，也可能不够严谨。只有当队列为空时，旧代码才把 `lower_bound` 直接设为 `best_cost`，这时才是完整证明最优。

关于 `sudo_cost` 剪枝，旧代码的 `PriorityQueue<Node>` 通过 `Node.compareTo()` 按 `sudo_cost` 升序排列。因此一旦弹出的最小 `sudo_cost` 都已经大于 incumbent，后面所有节点的 `sudo_cost` 都不会更小，理论上可以直接结束搜索。旧代码写成逐个 `poll` 后 `continue`，不会再建 LP，但会继续把队列弹空；这是实现上不够直接，但不会多求 LP。当前 TWET-BPC 目前只有节点 LP 求解后的 incumbent 剪枝，尚未做建 LP 前的 `pseudoCost` 预剪枝，所以在这个边界下确实可能多求解本可跳过的节点。后续可以直接在 `Tree.solve()` 的 while 循环开头加：如果 `queue.peek().pseudoCost >= incumbent`，则清空队列或 break。

再进一步修正对旧 VRP 最终下界的判断：旧代码在未求完且队列非空时执行 `lower_bound = min(lower_bound, queue.peek().sudo_cost)`，这个不是明显 bug。循环中的 `lower_bound` 会被当前处理节点的 `lp.solution_cost` 覆盖，而当前节点 LP bound 可能大于队列中尚未处理节点的最小 `sudo_cost`；如果不取 `min`，报告的下界可能超过 open tree 中已知的最小合法下界。这里取 `min` 的作用正是把报告值压回安全下界。它可能仍然偏弱，因为 `sudo_cost` 只是父节点 bound 继承下来的伪下界，但作为未完成搜索时的安全 LB 是合理的。因此当前问题不是“旧 VRP 下界计算错误”，而是它的未完成 gap 可能不够紧。
## 2026-05-19：BPC 小算例整树求解对拍

本次新增 `HEU.SmallBPCBatchTest`，用于把当前 TWET-BPC 和 `ArcFlowModel` 在小规模随机算例上直接对拍。测试不依赖启发式 seed 的额外 ALNS，关闭 BPC 文件输出和 console 输出，只保留 RMP、pricing、branch tree 和 solution validator 的主流程。测试结果写入 `test-results/bpc/2026-05-19-small-bpc.csv`。

随机部分共 8 个算例，规模为 5、6、7 个任务和 2 台机器，均带 setup time、setup cost、外包成本和线性 tariff。8 个算例中 BPC 目标值、best bound 与 ArcFlow 精确值全部一致，gap 均为 0，`BPCSolutionValidator` 均返回可行。所有随机算例都在根节点闭合，状态为 `ROOT_PROCESSED`，这是当前 `Tree.finalStatus()` 的语义：只处理根节点时即返回该状态；如果此时 bound 与 incumbent 相等且队列已闭合，应视为根节点证明完成，而不是未完成。

为了检查真实分支路径，又构造了一个两段凹折扣 tariff 的诊断算例。该算例使 LP relaxation 可以分数使用第二段 tariff，因此会触发 `TariffSegmentBrancher`。测试结果为 ArcFlow=10.0、BPC=10.0、best bound=10.0，处理 3 个节点、触发 1 次分支、pricing 6 轮、validator 可行，结果写入 `test-results/bpc/2026-05-19-small-bpc-branch.csv`。这说明当前 no-cut branch-and-price 的根节点求解、tariff 分支、child 入队/出队、RMP 重建、pricing 修复和最终 incumbent/bound 汇总在该小例子上能够闭合。

当前结论是：不考虑 cut 的情况下，主框架与旧 VRP 的 branch-price 主流程已经基本一致；差异主要是问题特有的分段函数定价、外包 tariff 分支和当前延迟到 child 出队时修复列集合。该差异前面已经确认不改变主流程语义。仍需注意的是，本次诊断只触发了 tariff 分支，尚未专门构造 fractional arc 分支算例；后续如果要验证 arc branching，可继续构造禁用外包或线性外包成本下的分数 arc 根节点例子。

## 2026-05-19：40 任务中等规模 BPC 诊断

本次新增 `HEU.MediumBPCBatchTest`，用于不再依赖 ArcFlow 对拍、而是直接观察 40 任务 BPC 自身的节点、分支、pricing、上下界和最终解验证。测试入口默认只跑一个 case；如果要跑多个 40 任务算例，建议从脚本层面按 case id 启动多个独立 JVM，而不是在同一个 JVM 中连续跑很多 case。原因是 40 任务 pricing 会产生较多临时 label、分段函数和 dominance envelope；连续在同一 JVM 中跑多个 case 时，case 0 后接 case 1 曾出现内存压力，而分开启动 JVM 后三个测试 case 都能正常闭合。

测试结果如下。case 0 是线性外包成本，根节点直接闭合，目标值和下界均为 `90.10`，状态为 `ROOT_PROCESSED`，未触发分支，耗时约 `3.3s`。case 1 也是线性外包成本，但触发了真实 arc 分支，最终目标值和下界均为 `73.34`，处理节点数 `12`，分支调用 `7` 次，pricing `80` 轮，生成列 `2785` 条，耗时约 `16.7s`。case 3 使用两段凹折扣 tariff，触发了 `1` 次 tariff segment 分支和 `8` 次 arc 分支，最终目标值和下界均为 `115.20`，处理节点数 `14`，pricing `78` 轮，生成列 `1915` 条，耗时约 `10.45s`。三个 case 的 `BPCSolutionValidator` 均返回可行，gap 均为 0。

本次测试还暴露了一个和旧 VRP `sudo_cost` 预剪枝对应的缺口。case 1 在修复前曾运行到某个非根节点时，已经有 incumbent `73.34`，而队列弹出的节点 `pseudoCost=73.462895` 已经不可能改进 incumbent。旧 VRP 在这种情况下会用 `sudo_cost` 直接跳过该节点，不再建 RMP 和 pricing；当前 TWET-BPC 当时缺少这层预剪枝，仍继续构建 LP 并进入 pricing，最终在 `PaperDominanceGraph.mergeGEnvelopes` 附近触发内存膨胀。现在 `Tree.solve()` 在非根节点建模前增加 `pseudoCost >= incumbent` 的预剪枝。由于优先队列按 `pseudoCost` 升序，当前节点已经不可能改进时，剩余节点也不会更好，因此可以直接关闭队列并结束搜索。这个修改不改变正确性，只是补上旧 VRP 已有的提前关闭逻辑，并避免明显无意义的 pricing。

同时，`BPCTraceSummary.onNodePicked()` 也补了一个计数修正：被 pseudoCost 预剪枝的节点不会进入 `onMasterSolved()`，但它已经从队列中弹出并被处理，因此应当计入 processed nodes。否则 trace 中的节点数会比求解结果对象少一类预剪枝节点。

本轮有效输出文件为 `test-results/bpc/2026-05-19-medium-bpc-40-case0.csv`、`test-results/bpc/2026-05-19-medium-bpc-40-case1.csv` 和 `test-results/bpc/2026-05-19-medium-bpc-40-case3.csv`。此外，调试慢例时可以通过 `-Dtwet.bpc.verbose=true` 打开 BPC 过程输出；如果需要按“等待 10 分钟后停止”的方式人工观察，verbose 日志中能直接看到最后一次 incumbent、bound、节点和分支状态。
## 2026-05-19：再次复核 TWET-BPC 与旧 VRP 流程是否完全一致

这次重新按旧 VRP 的 `Tree / PC / Branch*.UpdateRouteSet / GCTabu / GCNGBB` 和当前 `TWETBPC` 的 `Tree / PC / LP / Node / PricingEngine` 做了一轮对照。需要修正前面的表述：当前只能说 no-cut branch-and-price 的主流程骨架已经基本对齐旧 VRP，而不是旧 VRP 的所有实现细节、剪枝时机、cut 循环和 DSSR 状态语义都已经一比一复刻。前面说“流程逻辑一样”，更准确的含义是：节点出队、建 restricted master、列生成、整数性检查、分支、child 列修复这些主干顺序已经一致；但还有若干地方属于“问题特化或暂未实现完整旧版能力”。

第一，`sudo_cost/pseudoCost` 出队前预剪枝已经补上。旧 VRP 在 `Tree` 中弹出节点后、建 LP 前会检查 `node.sudo_cost > best_cost + tolerance`，满足时直接跳过该节点。当前 TWET-BPC 之前确实缺这一层，所以 40 任务 case 中出现过已经有 incumbent 后，`pseudoCost` 明显不可能改进的节点仍继续建模和 pricing，造成无意义计算和内存压力。现在 `Tree.solve()` 已经在非根节点建模前加了 `pseudoCost >= incumbent` 的预剪枝。当前实现因为优先队列按 `pseudoCost` 升序，所以一旦队头都不可能改进，直接清空队列结束；旧 VRP 是逐个 `poll` 后 `continue`。这两者在逻辑上等价，当前写法更直接，但不是旧代码逐行同款。

第二，普通 pricing 主循环和旧 VRP 仍有轻微接口差异。旧 VRP 的 `PC` 是启发式 `GCTabu` 和精确 `GCNGBB` 交替推进，启发式找到列后会更新 LP，再进入后续精确定价；当前 TWET-BPC 的 `generateColumns()` 在一轮中先调用启发式和精确 engine，合并新列后统一加入并重解 LP。这个差异通常不影响正确性，因为下一轮会基于新 dual 继续 pricing；但从旧 VRP 的效率语义看，旧版更偏向“只要启发式能持续找到列，就尽量先用启发式”，当前封装可能让精确 engine 在某些轮次更早参与，效率上不完全相同。repair 模式中当前已经更接近旧版：启发式 engine 可以反复找列直到耗尽或 slack 归零，再由 exact engine 兜底。

第三，`UpdateRouteSet / FindFeasible` 的主语义已经对齐，但不是旧版完全同一时机。旧 VRP 在父节点分支时立即给 child 做 `UpdateRouteSet()`，即先用父节点当前列加 child 分支行求一次 LP，不可行才加当前分支行 slack 并调用 `FindFeasible()`；成功后再筛 child 的 route set。当前 TWET-BPC 把这个动作推迟到 child 真正出队后执行。这个时机差异不改变逻辑含义，反而避免为最终不会展开的 child 提前做修复。当前也已经按旧版语义收紧为只对当前新分支行加 repair slack，不再默认给 coverage 行加 slack。

第四，DSSR/ng-set 的 reset 语义当前还没有旧版对应物。旧 VRP 的 `GCNGBB.Reset()` 主要服务于 ng-route/DSSR 状态：如果启发式新加了列，精确定价中上一轮逐步扩充出来的 ng-set 状态可能不再适合作为下一次精确定价的延续，因此需要 reset。当前 TWET-BPC 的 exact pricing 是基础 forward labeling + dominance graph，没有 DSSR/ng-set 状态，所以当前 `reset()` 基本只是接口占位。也就是说，这里不是漏掉一个必须重置的缓存，而是当前 exact pricing 还没有实现旧 VRP 那套 DSSR 状态机制；后续如果加入 ng-route/DSSR，再需要恢复旧版 reset 语义。

第五，cut 部分不是完整 BPC。旧 VRP 的 `PC.Solve()` 是 price-and-cut 外层循环，分离出 cut 后会重新回到 pricing，因为新 cut 的 dual 会改变 reduced cost。当前 TWET-BPC 的 cut 类和 cut pool 仍是占位，`SubsetRowCutGenerator` 不产生真实 cut，`LP.buildModel()` 也没有真实 cut 行和列系数。即使后续接入真实 cut，当前 `PC` 也需要改成“加 cut 后重新 pricing”的外层循环。因此当前更准确的定位是 no-cut branch-and-price 框架，而不是完整 branch-price-and-cut。

第六，分支类型和顺序有问题特化差异。旧 VRP 没有 outsourcing tariff segment 分支；当前 SP2 外包分段模型必须处理 `z_s` 的分数解，因此当前顺序是 tariff segment、machine count、arc。这个和旧 VRP 不同，但属于当前问题新增结构。对于 arc 分支，当前 forbidden arc 行在正式 child 列集合筛完后理论上可能冗余，因为所有含该弧的旧列已被删掉、pricing 也不会再生成该弧；required arc、machine count 和 tariff segment 都是解层面的聚合约束，不能删。这个判断已经写入前面的专题记录。

第七，当前 exact pricing 仍是最基础 forward labeling，不等于旧 VRP 的完整 pricing 栈。旧 VRP 有多种 GC 实现、ng-route/DSSR、启发式 tabu 列生成、cut reduced-cost 快速项等。当前 TWET-BPC 已经有启发式 pricing、paper-style dominance graph 和基础 exact pricing，但还没有 NG/DSSR、双向 labeling、SRI/cut reduced-cost 项。这个不是主流程 bug，而是能力范围差异。

第八，`bestBound` 和节点状态输出已经比前面更接近旧 VRP，但仍要区分“证明用状态”和“输出摘要”。当前已经修正为队列空时 `LB=incumbent`，队列未空时用 `queue.peek().pseudoCost` 作为 open-node 伪下界；同时补了 pseudoCost 预剪枝。这和旧 VRP `sudo_cost` 的安全下界语义一致。区别是当前在 pseudoCost 已经不可能改进时直接清空队列，而旧版逐个跳过；这是实现方式差异，不是逻辑缺失。

当前结论为：如果只讨论 no-cut branch-and-price 主流程，当前 TWET-BPC 已经基本和旧 VRP 的框架一致，并且最近补上了之前确实缺失的 `sudo_cost/pseudoCost` 预剪枝。还不能说“完整 BPC 和旧 VRP 完全一样”，因为 cut 循环、DSSR/ng-set 状态、完整 pricing 栈和部分旧版效率细节还没有实现。后续如果继续追求和旧 VRP 完整版一致，优先顺序应是：先接真实 cut 后的 price-and-cut 外层循环，再做 NG/DSSR exact pricing reset 语义，最后补 cut reduced-cost 项和更强列生成统计。

## 2026-05-19：普通 pricing 循环改为旧 VRP 的启发式优先节奏

本次只处理普通 pricing 循环和旧 VRP 不完全一致的问题，不动 cut 和 DSSR/ng-set。修改前，`PC.solve()` 在每一轮调用 `generateColumns()`，会在同一轮 dual 下依次调用所有 pricing engine，把启发式和 exact 找到的列合并后统一加入 RMP，再重解 LP。这样做通常不影响正确性，但和旧 VRP 的 `PC` 节奏不同：旧版更偏向先反复使用 `GCTabu`，只要启发式仍能找到列，就先加列、重解 LP，并继续从启发式开始；只有启发式耗尽后才进入更重的 `GCNGBB`。

现在 `PC.solve()` 已改成同样的控制方式：普通 pricing 循环中按 `pricingEngines` 顺序逐个调用 engine；某个 engine 找到并成功加入新列后，立即重解当前 RMP，并从第一个 engine 重新开始。由于当前 engine 顺序为 `HeuristicPricingEngine -> PaperDominanceExactPricingEngine/ExactPricingEngine`，这意味着只要启发式还能持续补列，就不会提前调用 exact pricing。若启发式没有新列，才进入 exact；若 exact 加列，也会重解 LP 并回到启发式。这个修改主要改善效率语义和旧 VRP 框架一致性，不改变 RMP、分支、repair slack、cut placeholder 或 exact pricing 内部逻辑。

repair 模式暂时保持原有实现。它已经是启发式可反复补列、exact 兜底的结构，和旧 VRP `FindFeasible` 的主语义较接近；本次不额外调整。

验证：针对 `Basic/Common/HEU/Output/TWETBPC` 子集运行 `javac -encoding UTF-8 -cp D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar;target\classes -d target\twetbpc-compile ...` 通过，仅有历史 deprecated API 提示。随后运行 `HEU.SmallBPCBatchTest`，8 个随机小算例均与 `ArcFlowModel` 目标值一致，tariff 分支诊断例也得到 `BPC=ArcFlow=10.0`、`nodes=3`、`branches=1`、验证可行。

## 2026-05-19：只看流程框架时当前仍和旧 VRP 不同的地方

本次只按“流程和框架”复核，不讨论 TWET 分段函数、setup、外包成本、label 评估这些问题特化细节。普通 pricing 循环已经改成启发式优先、加列即重解并回到第一个 engine，因此不再作为差异项。

当前剩余的第一类差异是 cut 外层循环。旧 VRP 是完整 price-and-cut 框架：pricing 收敛后分离 cut，若加了 cut，则回到 pricing，因为 cut dual 会改变列 reduced cost。当前 `CutGenerator/CutPool/TWETCut` 仍是占位，`SubsetRowCutGenerator` 不产生真实 cut，`LP` 中也没有真实 cut 行和列系数。因此当前应表述为 no-cut branch-and-price 框架，而不是完整 BPC。后续接 cut 时，必须补“加 cut 后重新 pricing”的外层循环。

第二类差异是 DSSR/ng-route 状态。旧 VRP 的 `GCNGBB` 有动态 ng-set / DSSR 状态，`Reset()` 的意义是当启发式新加列、LP dual 改变后，精确定价的动态状态需要清理或重新开始。当前 exact pricing 没有 DSSR/ng-set，只是基础 forward labeling 加 dominance graph，因此 `reset()` 还是接口占位。这不是当前流程错误，而是旧版完整 exact pricing 能力尚未实现。

第三类差异是 child repair 的时机。旧 VRP 在分支生成 child 时立即执行 `UpdateRouteSet()`，当前 TWET-BPC 是 child 真正出队时才做首次 LP、repair 和正式列筛选。这个时机不同，但逻辑等价，而且当前更省，因为不会提前修复后来可能被 bound 剪掉的 child。只要 child 出队时仍先用父节点列集加当前分支行求 LP，不可行时只对当前分支行加 slack 修复，再筛正式列集，这个框架语义就是对齐的。

第四类差异是分支族。旧 VRP 没有 outsourcing tariff segment 分支，当前因为 SP2 外包分段变量 `z_s` 可能分数，所以多了 `TariffSegmentBrancher`，且当前顺序是 tariff segment、machine count、arc。这是当前模型需要的扩展，不是框架错误。若以后做不带外包或线性外包成本的版本，可以把 tariff 分支关闭或让它自然不触发。

第五类差异是输出和状态统计的实现方式。当前 `Tree` 在 `pseudoCost >= incumbent` 时直接清空队列结束，旧 VRP 是逐个弹出后跳过；由于队列都按伪下界升序，这两个逻辑等价，但不是逐行同款。当前 `bestBound/finalStatus/trace` 也是按 TWET 输出对象重新封装，不是旧 VRP 的控制台变量原样搬运。

因此当前严格说法是：no-cut B&P 主控流程已经和旧 VRP 基本一致；普通 pricing 循环、pseudoCost 预剪枝、child repair 语义这些之前差异较大的地方已经补齐。仍不同的部分主要是尚未实现 cut 循环、DSSR/ng-route 状态和旧版完整 pricing 栈，以及 SP2 外包分段带来的额外分支族。

## 2026-05-20：基于粗硬时间窗的静态不可行 arc 预处理
本次补上一个与旧 VRP `feasible_arc` 思路对应的静态预处理层。当前数据已经先用外包基准成本和 setup-cost advantage 得到每个任务的粗硬完成窗口 `[hardWindowStart[j], hardWindowEnd[j]]`，并进一步收紧 `CmaxH`。在这个基础上，可以安全删除一部分相邻弧：如果任务 `i` 即使取最早允许完成时间 `hardWindowStart[i]`，后接任务 `j` 后得到的最早完成时间 `hardWindowStart[i] + s[i][j] + p[j]` 仍然超过 `hardWindowEnd[j]`，那么 `i -> j` 在任何满足粗硬窗的列中都不可能出现。`0 -> j` 同理使用 `s[0][j] + p[j]` 判断。

实现上在 `Data` 中新增 `preprocessedArcForbidden`，并在构造流程和 `setPenaltyFunctions()` 前调用 `preprocessInfeasibleArcsByHardWindows()`。这里同时标记自环、回到 source、sink 出边、source 到 sink 等结构性非法弧。这个矩阵是全局过滤条件，不写入 `Node.arcState`，原因是它不是分支约束；如果把这些弧伪装成 `ARC_FORBIDDEN`，`LP.buildArcBranchConstraints()` 会为所有预处理弧额外建 forbidden-arc 行，导致 RMP 膨胀。

接入点上，`Node` 新增 `isArcForbidden(from,to)`，统一判断“显式 forbidden 分支”与“预处理不可行弧”。exact pricing 的 label 扩展、reachable set 构建、回 sink 检查，tabu heuristic pricing 的候选序列兼容性检查，以及 `ArcBrancher` 的分支候选扫描，都改用这个判断。RMP 建模仍使用 `getArcState()`，只对显式分支状态建约束行。这样后续 pricing 和 branch 都不会再考虑这些静态不可行边，但不会改变主问题的约束结构。

当前预处理只做一跳安全删除，不做多步 reachability、Vidal 式子序列摘要或动态 dual 相关 `H_ij` 收缩；这些仍属于后续性能强化。验证上，本次用 CPLEX classpath 编译 `HEU.SmallBPCBatchTest` 通过，并运行该测试：8 个随机小算例均与 ArcFlow 结果一致，额外 tariff 分支诊断例也通过。

复核时进一步确认，静态预处理禁弧不能只在新增列时过滤。`LP.addColumns()` 已经会调用 `Node.isColumnCompatible()`，因此 pricing 新生成的列不会带入静态禁弧；但 `LP.construct()` 会直接接收 root seed 或 child 从父节点继承的 restricted columns。如果历史列池、seed 列或后续调试入口里残留了包含静态禁弧的列，第一次 LP 仍可能把这些已经证明不可行的列放回 RMP。为避免这个接口漏洞，本次在 `Node` 中补充 `isColumnPreprocessingCompatible()`，只检查 `Data.preprocessedArcForbidden`，不检查当前分支状态；`LP.construct()` 只用这个静态检查过滤输入列。这样既能保证全局不可行列不进入 RMP，又不破坏旧 VRP 对齐过的 child 首次 LP 语义，即 child 仍然先继承父节点列集并带新分支行求可行性，而不是提前按分支状态筛列。

临时统计程序对 6、7、8、20、40 个任务的随机算例做了删边统计。结构性禁弧包括自环、回 source、sink 出边、source 到 sink 等固定非法弧；真正由粗硬时间窗删掉的是 job-job 弧。在测试样本中，6 任务例删掉 11/30 条 job-job 弧，7 任务例删掉 11/42 条，8 任务例删掉 20/56 条，20 任务例删掉 112/380 条，两个 40 任务例分别删掉 301/1560 和 423/1560 条。source-job 和 job-sink 在这些样本中没有被粗窗删掉，这符合当前规则：起点到第一个任务通常可通过等待进入窗口，回 sink 也没有单独硬窗。因此这个预处理在当前随机数据上确实能删掉相当一部分内部相邻边，主要收益会体现在 pricing 扩展、reachable set 构建、启发式 pricing 候选序列兼容性检查和 arc branching 候选扫描上。

进一步澄清这个预处理在 BPC 中的使用方式。`Data.preprocessedArcForbidden` 不是分支状态，也不是一个要写进 RMP 的约束集合，而是全局图过滤器。`Node.isArcForbidden(from,to)` 把两类禁弧合并起来判断：一类是 `Data` 预处理得到的静态不可行弧，另一类是当前节点显式 forbidden arc 分支。pricing 扩展 label 时先查这个函数，因此如果 `i -> j` 已经被预处理判定不可能，label 根本不会扩展到 `j`；reachable set 构建也不会把 `j` 放进去；label 回 sink 生成列时也会检查末端弧。启发式 pricing 同样在候选序列兼容性检查里调用这个判断，所以含静态禁弧的候选序列会被直接跳过。

分支里也使用同一个判断，但语义不同。`ArcBrancher` 在扫描 fractional arc 时会跳过已经被预处理禁掉的弧，因为这种弧不可能出现在任何合法列里，对它分支没有意义。如果它仍在 LP 解里出现，说明前面的列兼容性已经出错，而不是应该继续分支。相反，`LP.buildArcBranchConstraints()` 仍然只看 `Node.getArcState()`，也就是只为显式分支状态建 required/forbidden 约束行；预处理弧不会被建成 forbidden-arc 约束，否则 RMP 会因为大量静态禁弧膨胀。

因此，“删掉相应的列”只发生在两个防御性入口：`LP.addColumns()` 和 `LP.construct()`。正常情况下，pricing 已经不会生成含静态禁弧的列，所以 `addColumns()` 只是二次保险。`LP.construct()` 的过滤则是为了处理 root seed、父节点继承 restricted columns 或调试入口中可能残留的历史列。如果这些列包含粗硬时间窗已经证明不可行的弧，继续放入 RMP 会让主问题使用本不该存在的列。这里过滤的是静态预处理禁弧，不过滤当前 child 的分支状态；这样不会破坏旧 VRP 那种“child 首次 LP 先继承父节点列集并带新分支行求可行性”的流程。

关于旧 VRP 预处理是否已经全部搬过来，当前结论是没有，也不应该无差别全搬。旧 VRP `Data` 中主要有几类预处理：`TightTW0/TightTW1` 收紧 depot/customer 时间窗，`TightCapacity` 收紧车辆容量，`BuildReach` 预先构造按时间和容量索引的 forward/backward reach bitset，`BuildInRank` 为 label-setting 的入边排序提供加速。当前 TWET-BPC 已经接入的是与时间窗直接对应的部分：先用外包 baseline 和 setup-cost advantage 得到每个任务的粗 completion 硬窗，再收紧 `CmaxH`，然后基于这个粗窗删除静态不可能的相邻 arc。容量预处理在当前同质并行机模型里没有对应资源；旧 VRP 的 forward/backward time-index reach bitset 和入边 rank 属于更深一层的 label-setting 加速结构，目前基础 forward pricing 尚未实现；NG/DSSR、双向拼接和 cut reduced-cost 也属于后续强化，不是这次静态禁弧预处理的范围。

每轮 RMP 求解后的 dual 确实已经在 pricing 中用于更新“局部硬时间窗”。当前 `GC` 在扩展 `prevJob -> job` 时使用 `hWindowGamma(prevJob, job, lp)`，其中包含 setup-cost advantage、job dual 和外包 baseline，得到动态的 `hWindowStart/hWindowEnd`。随后先用 `isDirectExtensionTimeFeasible()` 做轻量过滤，再对 job penalty 调用 `setDomain(hStart,hEnd,true)`，把窗口外设为 `big_M` 并继续做分段函数运算。因此这部分不是没用，而是已经在每次 pricing 扩展时即时使用。

这类 dual 相关窗口不适合再写成全局禁弧矩阵。原因是它只对“当前 RMP dual 下寻找负 reduced-cost 列”有效，下一轮 LP 重新求解后 dual 会变；并且它通常依赖当前 label frontier 的左端时间，不只是一个固定的 `i -> j` 结构属性。即使某条弧在当前 dual 下不会产生负 reduced-cost 扩展，也不代表这条弧在原问题中不可行，更不代表后续分支不该考虑它。因此分支候选只能跳过静态预处理禁弧和显式 forbidden 分支，不能跳过动态 hard-window 过滤出来的局部禁弧。动态窗口继续留在 pricing 内部做扩展剪枝即可；若后续要加速，可以在单次 pricing 调用内部缓存一份“当前 dual 下的 pair 级快速判断”，但它只能作为本轮 pricing 的局部缓存，不能进入 `Node.arcState`，也不能影响 brancher。
## 2026-05-20：pricing 中动态硬时间窗的预计算

这次讨论集中在旧 VRP 代码里的 `BuildReach`、`BuildInRank` 和当前 TWET-BPC pricing 里动态硬时间窗的关系。旧 VRP 的 `BuildReach` 本质上是一个按资源状态预先建好的 reachability 索引：给定当前客户、时间或容量状态，可以用 bitset 快速得到仍可能到达的下一批客户，避免 label 扩展时每次都全量扫描并重复做时间窗/容量判断。它不是新的约束，而是把一部分“一定不可达”的判断提前压缩成查询表。当前 TWET 问题没有容量资源，而且时间状态由分段线性函数的定义域承载，不适合直接照搬成旧 VRP 那种离散时间/容量 bitset；但是其中“本轮 pricing 内可复用的可达性判断应缓存”的思想是可以用的。

旧 VRP 的 `BuildInRank` 是另一个加速结构，主要给 label-setting 中的入边或候选前驱排序。直观上，它把“哪些 predecessor 更有可能更早、更短或更有用”预先排好，后续 dominance 或扩展时不必每次重新排序。它同样不改变可行域，也不影响最优性，只影响搜索顺序和效率。当前基础 forward pricing 还没有引入这种入边排序；如果后续做双向 labeling、NG/DSSR 或更强 dominance graph，可以再考虑把类似 rank 结构接进来。

当前 `GC.isDirectExtensionTimeFeasible()` 的作用是轻量过滤。它在真正执行 `shift/add/normalize` 前，先用当前 label frontier 的最左端时间、`setupTime(prevJob,nextJob)` 和 `processingTime(nextJob)` 算出最早可能完成时间。如果这个最早完成时间已经超过本轮 dual 下的 `H_ij` 右端，或者超过 `CmaxH`，那么这条扩展不可能产生有效负 reduced-cost label，直接跳过。这个判断只是一层便宜的前置过滤，不能替代后面的分段函数运算，因为分段函数还要处理完整成本形状、窗口外 big_M、dual、setup cost 和 normalize。

原实现是在每次 label 扩展时重复计算 `hWindowStart/hWindowEnd`，并对 `data.penaltyFunction[j]` 的副本调用 `setDomain(hStart,hEnd,true)`。这在逻辑上是对的，但会重复做同一轮 pricing 中不变的事情。现在改成在 `GC.initialize(lp)` 中一次性预计算 `dynamicHStart[prevJob][job]`、`dynamicHEnd[prevJob][job]` 和 `dynamicJobPenalty[prevJob][job]`。由于一轮 pricing 内 RMP dual 固定，`H_ij` 也固定；即使 `setupCostAdvantage B_ij` 非零，窗口也只依赖 `(prevJob, job)`，所以按弧缓存仍然正确。若 setup cost 满足对应三角关系使 `B_ij=0`，这个缓存自然退化为“每个 job 一份窗口”的情形。

这里没有把动态硬时间窗写成全局禁弧矩阵。原因是这类窗口依赖当前 LP dual，只对本轮 pricing 的 reduced-cost 搜索有效；下一轮 RMP 重解后 dual 会变，窗口也要重新算。它也不应该影响 arc branching 候选，因为某条弧在当前 dual 下不值得扩展，并不等价于它在原问题中结构性不可行。静态粗硬窗预处理仍然放在 `Data.preprocessedArcForbidden` 中；动态 dual 硬窗只保留在 `GC` 的本轮缓存里。

本次修改后的流程为：每轮 pricing 初始化时先构造动态窗口缓存；扩展 `prevJob -> job` 时先查 `dynamicJobPenalty` 是否存在，再用 `dynamicHEnd` 做 `isDirectExtensionTimeFeasible()` 轻量过滤；通过后直接复用缓存好的 job penalty 函数，继续执行 `shift/add/normalize`。这样减少了重复计算和重复构造函数副本，同时不改变 pricing 的可行域语义。
## 2026-05-20：动态硬时间窗缓存的 job 级与 pair 级切换

在前一版实现中，`GC` 已经把每轮 pricing 的动态硬时间窗从“每次扩展时重复计算”改为“本轮 pricing 初始化时预计算”。本次进一步把缓存分成两种模式。`Data.precomputeSetupCostAdvantages()` 在计算 `B_ij` 时同步记录 `setupCostTriangleInequalitySatisfied`：只要存在某个 `B_ij > 0`，就说明 setup cost 在当前公式意义下不满足三角关系，动态窗口仍然依赖 `prevJob -> job`，必须使用二维 pair 级缓存；只有所有 `B_ij` 都为 0 时，`H_ij` 才不再依赖前驱，可以退化成 job 级一维缓存。

这不是把正确性建立在“数据应该满足三角不等式”的假设上，而是由实际预处理结果决定缓存形式。pair 级模式下，`dynamicJobPenaltyByPair[prevJob][job]` 保存每条弧对应的动态 job 函数副本；job 级模式下，`dynamicJobPenaltyByJob[job]` 只保存每个任务一份函数副本。扩展时统一通过 `getDynamicJobPenalty(prevJob, job)` 和 `getDynamicHEnd(prevJob, job)` 读取，因此后续 label 扩展逻辑不需要关心当前是哪种缓存。

这个优化的收益主要在满足三角关系或 `setupCost` 全 0 的数据上。此时每轮 pricing 不再为每个 `prevJob -> job` 重复构造同一个 `setDomain(hStart,hEnd,true)` 结果，而是每个 job 只构造一次。若数据不满足三角关系，则仍然走二维缓存，行为与前一版 pair 级实现一致，保证 `B_ij` 非零时不会错误地把前驱相关窗口合并掉。

验证上，针对 `Basic/Common/HEU/Output/TWETBPC` 子集运行 `javac -encoding UTF-8` 编译通过；随后运行 `HEU.SmallBPCBatchTest`，8 个随机小算例均与 `ArcFlowModel` 目标值一致，额外 tariff 分支诊断例也通过。

## 2026-05-20：关于 BuildReach、BuildInRank 和动态硬窗缓存的进一步澄清

这里需要澄清一个容易混淆的点：`setupCostTriangleInequalitySatisfied` 不是每次 pricing 都重新扫描所有 `B_ij` 得到的，而是在 `Data.precomputeSetupCostAdvantages()` 阶段随 `B_ij` 一次性预处理出来的实例级标记。pricing 阶段只读这个标记。若该标记为 `true`，说明所有 `B_ij=0`，动态硬窗只和任务 `j` 的 dual、外包 baseline、惩罚函数参数有关，因此每轮 pricing 只需要给每个任务构造一份 job 级动态窗口和 job penalty 函数。若该标记为 `false`，说明至少有一个 `B_ij>0`，动态硬窗仍依赖前驱 `i`，此时必须按 `prevJob -> job` 构造 pair 级表。也就是说，判断三角关系是输入数据预处理的一部分，不是 pricing 内部的重复开销。

`isDirectExtensionTimeFeasible()` 的用途也要限定清楚。它不是完整可行性判定，而是扩展前的便宜过滤。当前 label 的 `frontier.head.start` 可以看作这条部分序列最早还能落到的完成时间左端；如果再接 `nextJob` 后，`frontier.head.start + s(prev,next) + p(next)` 已经超过本轮动态硬窗右端 `H_ij^R` 或 `CmaxH`，那么后面做 `shiftX`、`setDomain`、`add`、`normalize` 一定也只会得到空函数或 `big_M` 状态，提前跳过可以省掉分段函数操作。它保守地只删“最早都来不及”的扩展，不会替代分段函数求值，也不会剪掉仍可能可行的扩展。

关于“时间藏在定义域里”的说法，更准确的表述是：TWET pricing 的 label 时间状态不是一个离散整数时刻或单一 earliest-time 标量，而是一整个分段线性 frontier 函数的有效定义域和函数形状。每轮 pricing 的动态 `H_ij` 窗口确实固定，可以预处理，这也是当前已经做的 job/pair 级缓存；但旧 VRP `BuildReach` 那种 `customer + time index -> reachable bitset` 还需要一个离散时间索引。若要在 TWET 中做类似结构，就必须先决定用哪个时间代表一个 label 状态，例如只用 `frontier.head.start` 做一跳过滤，或者离散化所有可能完成时间。前者当前已经在 `buildReachableSet()` 和 `isDirectExtensionTimeFeasible()` 中做了；后者会引入较大的内存和离散化误差/复杂度，暂时不作为基础 forward pricing 的必要部分。

`BuildInRank` 的作用是排序，不是剪枝。旧 VRP 会给每个节点的可能入边或前驱建立一个 rank，通常按距离、时间或某种便宜估计排序。label-setting 或 dominance propagation 需要扫入边/前驱时，可以优先看更可能产生好 label 的前驱，或者用 rank 辅助快速定位候选范围。它不会改变哪些弧可行，也不会影响 reduced cost 公式；收益主要是减少无效扫描和改善搜索顺序。当前 TWET forward pricing 仍是按任务编号扫描候选任务，后续如果 label 数增多，可以考虑给候选 `prevJob -> job` 按静态弧代价、setup time、粗硬窗紧张度或历史 reduced-cost 表现建 rank。

进一步复核旧 VRP 代码后需要补一句：旧 `BuildInRank()` 的具体实现是对每个目标 customer `cid`，把所有前驱 `i` 按 `GetDistance(i,cid)` 排序，然后写入 `m_in_rank[cid][i] = rank`。但在当前参考 `src` 的 Java 文件里没有检索到 `m_in_rank` 的真实使用点，说明它至少在这份代码中更像预留/历史加速结构，而不是主 pricing 流程实际依赖的剪枝条件。因此当前 TWET-BPC 暂时不接它不会影响流程正确性。

关于用 `BuildReach` 思路进一步替代 `isDirectExtensionTimeFeasible()`，可以做，但要区分两种层次。现在已经预处理了每轮 pricing 的动态窗口 `H_ij` 和 job penalty 函数；这相当于把“窗口本身”缓存了。若还想像旧 VRP 一样把“从某个末端任务、某个当前时间还能到哪些任务”也缓存，就需要再引入一个以当前完成时间为索引的 reach 表，例如 `reach[fromJob][t]`，其中包含所有满足 `t + s[from][j] + p[j] <= HEnd[from][j]` 的 `j`。这样 label 扩展时可以用 `frontier.head.start` 映射到 `t`，直接拿 bitset，再扣掉 visited set 和分支禁弧。

这个方向能减少每次 `buildReachableSet()` 里对所有 job 的循环判断，但它不是无代价的。第一，当前时间可能是 double，不一定天然对应整数下标；如果所有时间严格是整数，则可以直接用整数时间作为索引；如果存在小数时间，就不能简单用 `ceil(frontier.head.start)` 做索引，因为这会把真实时间 10.1 映射到 11，可能丢掉在 10.1 仍可行但按 11 判断不可行的候选。更安全的粗过滤方式是用 `floor(frontier.head.start)` 取一个可达集合超集，然后再做精确时间窗判断。第二，表规模是 `(n+1) * CmaxH` 级别的 bitset，若 `CmaxH` 很大，内存会明显增加。第三，分支禁弧是 node-local 的，若 reach 表只按本轮 dual 预处理，就还需要在运行时额外扣掉当前 node 的 forbidden arcs；若把 node 分支也编入 reach 表，则每个 B&B 节点都要重建一份。因此它是合理的后续性能优化，但当前 pair/job 级动态硬窗缓存已经先解决了重复计算 `H_ij` 和重复 `setDomain` 的主要开销。

旧 VRP 的 `BuildReach()` 不是只给每个 customer 存一个最早到达时间，而是枚举每个 customer 自己时间窗内的所有整数时间 `t`。forward 部分大致是：对当前 customer `cid`，从 `early_time[cid]` 到 `late_time[cid]` 逐个枚举 `t`，再扫所有候选下一个 customer `i`，判断 `t + service(cid) + distance(cid,i) <= late_time[i]`，满足则把 `i` 写入 `m_fl_reach[cid][t] / m_fh_reach[cid][t]` 这个 bitset。也就是说，它预处理的是“当前在 cid 且当前时间为 t 时还能去哪些点”，不是“cid 有哪些固定可达点”。backward reach 同理，只是把时间从反向剩余/偏移的角度编码。

如果把这个思想搬到当前 TWET pricing，效率是否会提高取决于实例规模和 `CmaxH`。当前 `buildReachableSet()` 每生成一个 label 都会扫一遍所有 job，并做一次 `earliest + setup + p <= HEnd` 的判断，复杂度大约是 `label 数 * n`。若建 `reach[from][t]`，每个 label 可以直接拿 bitset，理论上能减少扫描和比较，尤其是 label 很多、`n` 较大时会有收益。但预处理要付出 `n * CmaxH * n/wordSize` 左右的时间和 `n * CmaxH` 个 bitset 的内存；如果 `CmaxH` 很大或时间不是整数，收益可能被内存和离散化成本抵消。因此当前判断是：可以作为后续性能强化，但最好先统计 40/60/更大算例中 label 数、`CmaxH` 大小和 `buildReachableSet()` 耗时占比，再决定是否实现。

进一步分析后，这个 reach 优化必须和位集操作一起做才有意义。理想流程不是“拿到 reach 后仍然 `for job=1..n` 逐个判断”，而是 `candidate = reach[from][timeIndex]`，再做 `candidate &= ~visited`，再扣掉当前 node 的显式 forbidden arcs，最后只迭代 candidate 中为 1 的 job。如果仍然全任务扫描，只是把一个时间不等式换成一次 bitset contains，收益会很小。当前 `PackedBitSet` 只支持 `add/contains/intersects/subset/copy`，还没有 `andNot`、原地交集和 set-bit 迭代接口；因此真正要接 `BuildReach` 式优化，需要先补这些底层集合操作，否则 reach 表本身不一定能带来明显加速。

对于小数时间，使用 `floor(frontier.head.start)` 作为索引会得到一个可达集合的超集：例如真实时间是 10.9，按 10 预处理出的 reach 可能包含一些 10 可达但 10.9 已经不可达的 job。因此这时仍需要在候选集合内做一次精确的 `earliest + setup + p <= HEnd` 检查。用 `ceil` 则相反，会变成偏紧的子集，可能错误删掉真实可行扩展，所以不安全。也就是说，如果时间不是严格整数，reach 表最多先做粗过滤，最后的 `isDirectExtensionTimeFeasible()` 仍然不能完全删掉。

分支禁弧也不能完全省掉。静态预处理禁弧确实可以在构造 reach 表时先过滤进去，因为它是全局不变的；但显式 arc branching 产生的 forbidden arcs 是 node-local 的，同一轮 pricing 在不同 B&B 节点下不一样。若不想每个节点重建 reach 表，就必须在使用 reach 表后再扣掉当前 node 的 forbidden arcs。因此 reach 表只能替代一部分时间可达性判断，不能替代分支兼容性检查。

`BuildInRank()` 当前明确不做。参考旧 VRP 代码中虽然构造了 `m_in_rank`，但没有检索到真实调用点；它只是按入边距离给前驱排序，不参与可行性、reduced cost 或 dominance 正确性。后续如果需要排序加速，可以重新设计一个适合 TWET 的 rank，而不是机械搬这个未使用结构。

本次进一步确认旧 VRP 对 forbidden arc 的处理方式。旧代码里的 `BuildReach()` 是数据层/资源层的全局 reach 预处理，不会把每个分支节点上的 forbidden arc 直接揉进 reach 表。真正扩展 label 或做 tabu move 时，仍然会检查当前节点的 `feasible_arc[i][j] == -1`，命中则跳过该弧。也就是说，旧 VRP 的结构是“全局 reach 表先给出资源上可能到达的候选，再用 node-local feasible_arc 过滤分支禁弧”，不是每次分支后重建一套 reach 表。

当前 TWET-BPC 的语义与此保持一致：静态预处理禁弧可以进入全局过滤，因为它不随节点变化；显式 forbidden arc 分支必须保留为 `Node.isArcForbidden(from,to)` 这种运行时检查，因为它是 node-local 的。当前 `Label.visitedSet` 已经使用 `PackedBitSet` 存储，不是普通 `HashSet`。为了给后续 `reach[from][time]` 方案留接口，本次先补齐 `PackedBitSet` 的原地交集、并集、差集、空集/基数判断和 set-bit 迭代。后续如果实现 reach 表，理想流程应是：取出 `candidate = reach[from][timeIndex].copy()`，再 `candidate.andNotInPlace(visited)`，再扣掉当前节点 forbidden 后继集合，最后只遍历 candidate 中置 1 的 job。这样才真正减少全任务扫描；如果仍然 `for job=1..n` 逐个 `contains`，reach 表收益会很有限。

进一步核对旧 VRP 的 `GCNGBB` 后，需要补充一点：旧代码虽然构造了 `m_fl_reach[cid][t]` 这类 bitset，但在 forward 扩展里仍然是 `for (int i = 1; i < customerNumber; ++i)` 逐个枚举候选任务，然后依次判断 `node.feasible_arc[label.cid][i] == -1`、visited/memory bit、`label.m_low_reach` 或 `label.m_high_reach` 是否包含 `i`。也就是说旧 VRP 没有直接“遍历 reach bitset 中的置 1 位”来生成候选，而是用 bitset 把每个候选的 reach 判断降到 O(1)。它的收益来自少算时间窗/容量判断和 dominance 中的集合比较，而不是完全消除候选循环。因此当前如果只是复刻旧 VRP 的写法，可以保留逐 job 扫描，只把动态可达性写成 bitset contains；如果要进一步优化到只遍历置 1 的候选，则属于比旧 VRP 更激进的实现，需要配套 `PackedBitSet.forEachSetBit` 和 node-local forbidden 集合相减。

基于上述复核，当前暂时不实现旧 VRP 式 `reach[from][time]` 表。原因主要有三点。第一，旧 VRP 虽然做了 reach 预处理，但扩展时仍逐 job 扫描；在这种写法下，reach 只是把“时间窗/容量是否可达”的计算换成 bit 判断，并没有消除候选循环。第二，当前 TWET 的一跳硬窗过滤本身也是 O(1)：核心就是 `frontier.head.start + setupTime(prev,next) + processingTime(next) <= HEnd(prev,next)`，和旧 VRP 的 `t + service + distance <= late[i]` 在复杂度上差不多；如果仍然逐 job 扫描，预处理 reach 表只能减少很小的常数。第三，TWET pricing 真正更重的是后续分段函数的 `shift/add/normalize` 和 dominance 处理，而不是这一个时间窗不等式。因此当前保留 `isDirectExtensionTimeFeasible()` 这种直接 O(1) 过滤，不额外构造 `n * CmaxH` 级别的 reach bitset。后续只有在 profiling 明确显示 `buildReachableSet()` 的逐 job 扫描占主要耗时，或者决定做“只遍历置 1 候选 bit”的更激进实现时，才重新考虑接入 reach 表。

## 2026-05-20：BPC 主流程与 heuristic/exact pricing 复核

本次集中复核当前无 cut B&P 主流程、启发式定价和精确定价两个生成列入口。当前主流程是：`PC.solve()` 先解当前 restricted master；如果 LP 不可行则进入 repair；如果是非根节点且 LP 可行，则按当前 LP reduced cost 重新筛 restricted columns，并显式保留当前 LP 中取正值的列以保证筛选后的 RMP 仍保留当前基解；随后进入普通 pricing 循环。普通 pricing 现在已经改成旧 VRP 风格：按 engine 顺序尝试，启发式 engine 只要找到列就先加列、重解 LP、再从第一个 engine 重新开始；只有启发式找不到新列时才进入 exact pricing。因此从控制流上看，当前 no-cut 版本已经是“启发式优先、精确定价兜底”的 branch-and-price 框架。

启发式定价方面，`HeuristicPricingEngine` 从当前 active/restricted columns 中按 reduced cost 取 seed，再围绕 seed 做 remove/add/exchange 类型的 tabu 搜索。候选列的真实成本不是简单累加，而是使用前向/后向分段线性 profile、`merge2Segments/merge3Segments`、setup time 和 setup cost 做局部拼接评估。复核重点是它生成的列是否和标准列成本一致。本次用临时检查程序强制启发式开启、精确定价保留兜底，对最终 column pool 中来源为 `PRICING_HEURISTIC` 的列逐一调用 `TWETColumnEvaluator.evaluate(sequence)` 对拍，检查 187 条启发式生成列，最大差异为 0。因此当前启发式生成列的成本计算在测试覆盖范围内是正确的。

精确定价方面，当前默认走 `PaperDominanceExactPricingEngine`。它仍是单向 forward labeling，不带 cut、不带 DSSR/ng-route，但已经使用论文式 dominance graph 的思路：每个 graph node 保留真实 label 集合，同时维护 envelope 用于集合占优判断。label 扩展时先用 node-local/global forbidden arc、动态硬时间窗和 visited bitset 做过滤，再做 `shiftX/add/shiftY/normalizeForward` 更新 reduced-cost 函数；生成负 reduced-cost 列时再用 `TWETColumnEvaluator` 计算真实列成本写入 RMP。为了单独验证 exact pricing，本次临时关闭启发式定价，检查来源为 `PRICING_EXACT` 的 117 条列，全部和 evaluator 成本一致，最大差异为 0。`PaperDominanceGraphConsistencyTest` 也通过 200 组、16000 次随机插入对拍，说明当前 dominance graph 和保守全扫描 dominance 在这些随机场景下行为一致。

当前还需要明确一个边界：这仍然是 no-cut B&P，不是完整 BPC。`CutGenerator/CutPool/TWETCut` 仍是占位或空生成器，`SubsetRowCutGenerator` 不产生真实 cut；`PC` 的 cut loop 即使以后接入真 cut，也必须在加 cut 后回到 pricing，因为 cut dual 会改变 reduced cost。现在测试通过只能说明无 cut 框架、pricing 生成列和现有分支/外包建模在小规模样本上顺畅，不能说明 SRI cut 或其他 cut 已经正确。

本次验证命令主要包括：`HEU.SmallBPCBatchTest` 小规模 BPC/ArcFlow 对拍，8 个随机算例和 tariff 分支诊断例均通过；`TWETBPC.GC.PaperDominanceGraphConsistencyTest` 通过；临时启发式列成本检查程序通过；临时精确定价列成本检查程序通过。测试均使用 CPLEX native path `D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64`。

剩余能明显提高效率的方向有几类。第一，exact pricing 目前对每条最终生成列调用 `TWETColumnEvaluator` 重算真实成本，这是稳健但偏慢的做法；后续可以让 label 同时携带真实成本函数或用 reduced cost 加 dual 反推，但必须继续做列成本对拍。第二，`PaperDominanceGraph` 当前虽然流程正确，但 eligible node 查找、envelope merge 和 label 删除仍有较多线性扫描，后续可以加 ancestor/descendant 缓存或 bitset 索引。第三，启发式定价每次 tabu 接受 move 后会重建 profile，后续可以做局部增量更新，但这比当前实现更复杂，必须谨慎。第四，`buildReachableSet()` 仍是逐 job 扫描；旧 VRP 的 reach bitset 并不会天然消除扫描，只有配合 set-bit 迭代、visited/forbidden bitset 相减才可能有大收益，所以当前暂不实现，后续先 profiling 再决定。第五，当前还缺少系统化 pricing 统计，包括 label 数、扩展数、动态硬窗剪枝数、dominance 删除数、启发式/精确定价耗时和生成列数量，这个是后续优化前最该补的观测层。

## 2026-05-20：PiecewiseLinearFunction 对反向 labeling 的支持状态

本次检查 `PiecewiseLinearFunction` 中和反向 labeling 相关的接口。当前分段函数层已经有方向化设计：`Direction.FORWARD` 表示前向函数按前缀最小值维护 `[a,T]` 语义，`Direction.BACKWARD` 表示反向函数按后缀最小值维护 `[0,b]` 语义。核心接口包括 `normalize(Direction)`、`normalizeBackward()`、`minimizeSuffixInPlace()`、`mergeMinimum(g, Direction)` 和 `updateDominatedIntervals(g, Direction)`。其中 forward normalize 保留右侧到全局 T 的定义域，不提前删除右侧 big_M；backward normalize 与之对称，保留左侧 0/domainStart，允许删除右侧连续 big_M 尾段，然后调用 suffix-min 闭包。

因此从基础函数操作看，当前代码已经为反向函数准备了必要语义，尤其是后向闭包、后向 normalize、后向 merge/min-dominate 的方向参数都存在。测试侧 `PiecewiseLinearFunctionPropertyTest` 也覆盖了 `normalize(BACKWARD)`、`minimizeSuffixInPlace()`、backward merge/updateDominatedIntervals 的随机对拍。

但当前 BPC exact pricing 仍然是单向 forward labeling。`GC` 初始化 source label 和扩展 label 时都调用 `normalize(Direction.FORWARD)`，`DominanceGraph`、`PaperDominanceGraph` 和 `DominanceNode` 的 envelope merge 也都使用 `Direction.FORWARD`。也就是说，现有 exact pricing 没有真正构造 backward label、没有 backward dominance graph，也没有 forward/backward 双向拼接定价。当前 backward 相关函数主要被 HEU 局部搜索和 `HeuristicPricingEngine` 的 tabu pricing profile 使用：`buildBackwardProfile()` 从序列右端向左递推，使用负向 `shiftX(-s_ij-p_j)`、`add(jobPenalty)` 和 `minimizeSuffixInPlace()`，用于快速评价 remove/add/exchange 这类片段拼接。

所以当前准确结论是：PWLF 层已经具备反向 label 所需的基础操作，但 BPC exact pricing 尚未实现反向 labeling；后续如果要做 bidirectional pricing，需要新增 backward Label/queue/dominance store，定义反向扩展的 reduced-cost dual 处理和 source/sink 边处理，并在拼接时明确 forward `[a,T]` 与 backward `[0,b]` 的公共时间语义。现有 `mergeMinimum(..., BACKWARD)` 和 `normalize(BACKWARD)` 可以复用，但不能直接说明双向 exact pricing 已经完成。

## 2026-05-20：新增 no-cut 双向 labeling pricing

本次按旧 VRP 双向 GC 的框架新增了一个独立的双向 pricing 层。新的入口类是 `BidirectionalPricingEngine`，核心实现是 `GCBidirectional`。在 `TWETBPCContext` 中，当前 exact pricing 层改为二选一：如果 `enableBidirectionalPricing=true`，启发式定价之后只接双向定价；如果该开关为 `false`，才按 `usePaperDominancePricing` 选择原来的单向 forward exact / paper-dominance exact。也就是说，双向和单向不再串成顺序兜底，而是作为两种 exact pricing 实现互斥切换。

`GCBidirectional` 的流程对应旧 VRP 中的 `FWExtend / BWExtend / Join`。forward label 从虚拟起点 0 开始向后扩展，backward label 从虚拟终点 sink 开始向前扩展；两侧 label 都保存当前真实 job 序列、visited bitset 和最小 processing+setup duration。forward 侧扩展 `last -> job`，backward 侧扩展 `job -> first`，扩展时统一检查当前节点的 `Node.isArcForbidden()`，因此静态预处理禁弧和显式 arc 分支禁弧都会生效。两侧都采用半程 duration 截断，对应旧 VRP 中 `m_time < max_time/2` 的双向资源截断思想，用于控制标签规模。

拼接部分采用旧 VRP 的同中间点 join 思路：forward label 和 backward label 必须在同一个 job 上相遇，且除该公共 job 外不能有重复访问任务。拼接后得到完整序列 `forwardPrefix + backwardSuffixWithoutCommonJob`，再统一调用 `TWETColumnEvaluator.evaluate(sequence)` 重新计算真实列成本，并按当前 LP dual 扣除机器数 dual、job dual 和 arc dual 得到 reduced cost。这样做比直接把 forward/backward 分段函数拼成 reduced-cost 函数更稳健，因为当前 TWET 的动态 `H_ij` 可能依赖前驱，反向函数定义域和 endpoint gap 也更容易写错。第一版双向实现先保证生成列口径和现有列评价器一致，后续再考虑把分段函数拼接评价进一步下沉到 label 里。

`TWETBPCConfig` 只保留 `enableBidirectionalPricing` 作为双向/单向 exact pricing 的选择开关，不再设置额外的双向 label 数工程保护。双向实现仍会遵守现有 `maxExactPricingColumns`，即单轮最多返回多少条负 reduced-cost 列；但不会因为局部 label 数超过某个新增上限而提前停止。若双向层生成了列，PC 会像其他 pricing engine 一样加列重解，然后从启发式 engine 重新开始。

验证上，本次先针对新增类和接入点运行 `javac -encoding UTF-8` 编译通过；随后运行 `TWETBPC.GC.PaperDominanceGraphConsistencyTest`，200 组、16000 次随机插入通过；再运行 `HEU.SmallBPCBatchTest`，8 个随机小例均与 ArcFlow 目标值一致，额外 tariff 分支诊断例也通过。测试输出 CSV 是运行副产物，已恢复到仓库原状态，未纳入本次提交。

## 2026-05-20：双向 pricing 与旧 VRP 双向 GC 的流程一致性复核

本次重新对照旧 VRP 中 `GCNG/GCBDT` 的双向 labeling 代码，结论是：当前 `GCBidirectional` 在外层骨架上已经接近旧流程，但还不是严格同款实现。已经一致的部分包括：都有 forward 初始化和 backward 初始化；都有 `FWUL/BWUL` 待扩展队列以及按终端 customer/job 存储的 `FWTL/BWTL`；forward 从起点向后扩展，backward 从终点向前扩展；扩展时都检查已访问点、当前节点禁弧和基本时间资源；两侧都用半程资源截断控制搜索规模；join 时也采用“forward label 与 backward label 在同一个中间点相遇，再拼成完整路径/序列”的思路。

当前主要差异有四个。第一，旧 VRP 的 label 本身带 reduced cost、time、load、visit/reach/memory 等资源，并在扩展时增量更新；当前 `GCBidirectional` 的 label 只保存真实序列、visited bitset 和 processing+setup duration，完整序列生成后才调用 `TWETColumnEvaluator` 重新评价列成本并计算 reduced cost。这保证了列成本口径稳定，但效率和旧代码不同。第二，旧 VRP 在 forward/backward 两侧都有 label dominance，入表前会删掉被占优 label；当前双向版暂时没有 dominance，只靠 visited、禁弧和半程截断控制规模。第三，旧 VRP 的 reduced-cost 计算在 label 扩展和 join 时已经参与筛选，当前只在完整序列阶段筛选，因此会多生成和多评价候选。第四，旧 `GCNG` 还包含 ng-memory/DSSR 的 duplicate cycle 更新；当前按用户要求暂不考虑 ng-route、DSSR 和 SRI cut，所以这部分差异是刻意保留的。

因此，当前双向版可以看作“旧 VRP 双向框架的第一版 TWET 适配”，用于和单向 pricing 做对拍测试是可以的；但如果要求“流程完全一致”，后续至少还应补两件事：一是给 forward/backward label 增加安全的 dominance 结构，二是把 TWET 的分段函数 reduced-cost 递推下沉到 label 层，而不是只在完整序列阶段调用 evaluator。下一步做两个 pricing 对拍时，应重点比较同一轮 RMP dual 下两者找到的最小 reduced cost、返回列数量和耗时；如果出现双向最小 reduced cost 明显弱于单向，就优先检查半程截断和同点 join 是否漏掉了某些序列。
## 2026-05-20：单向与双向 exact pricing 逐轮对拍
本次新增 `HEU.PricingAlgorithmComparisonTest`，用于在同一个 RMP dual 下同时调用单向 `PaperDominanceExactPricingEngine` 和双向 `BidirectionalPricingEngine`。这个诊断类不接入正式 `PC` 流程，也不改变 `enableBidirectionalPricing` 的生产语义；它只是在每轮 LP 解出 dual 后，分别调用两个 exact pricing，计算各自返回列中的最小 reduced cost、对应序列、返回列数和耗时，然后把两边新列的并集加入当前 RMP，重解后进入下一轮。这样可以避免“两套算法各自推进后 dual 不同”导致的比较不公平。

本次测试使用 3 个随机小算例，任务数分别为 5、6、7，并把 `maxExactPricingColumns` 临时调到 100000，尽量避免单轮返回列上限先截断最优负 reduced-cost 列。结果共比较 7 个 pricing 轮次，单向和双向的最小 reduced cost 全部一致。具体结果写入 `test-results/bpc/2026-05-20-pricing-comparison.csv`。控制台汇总为：`matchedRounds=7/7`，单向平均耗时约 `2.43 ms/round`，双向平均耗时约 `62.29 ms/round`。

这个结果说明，在当前小规模测试范围内，双向 pricing 虽然返回的候选列更多、耗时明显更高，但没有漏掉单向 forward exact 能找到的最优负 reduced-cost 列。耗时更高的主要原因仍是当前双向版本没有 forward/backward dominance，也没有把 reduced-cost 分段函数递推下沉到 label 层，而是在完整序列生成后统一调用 `TWETColumnEvaluator` 重算成本。因此它现在更适合作为正确性对拍版本，而不是效率更强的替代版本。后续如果要让双向真正带来效率收益，应优先补 forward/backward label dominance 和更强的 join 前 reduced-cost 剪枝。

## 2026-05-20：双向 pricing 补充旧 VRP 风格 label 占优

根据后续复核，前一版 `GCBidirectional` 的核心缺口不是外层 `FWExtend/BWExtend/Join` 框架，而是没有像旧 VRP 双向 GC 那样在 `FWTL/BWTL` 入表阶段做 label dominance。旧 VRP 的流程中，label 出队时会跳过已经被标记 dominated 的对象，扩展生成的新 label 入 TL 前会和同一终端点表内 label 比较，若被已有 label 支配则直接丢弃；若新 label 支配旧 label，则把旧 label 标记为 dominated 并从表中移除。这样 UL 中即使残留旧对象，后续出队时也会被跳过，join 阶段也不会再用已经失效的 TL label。

本次在 `GCBidirectional` 中补上了这层生命周期：`forwardExtend()` 和 `backwardExtend()` 出队后先检查 `isDominated`；`addForwardLabel()` 和 `addBackwardLabel()` 不再直接插入 TL，而是调用 `isDominatedAndInsert()`；`joinForward()` 和 `joinBackward()` 只枚举未被 dominated 的反向/正向 label。这个流程和旧 VRP 的表结构语义一致：TL 是每个终端 job 上已经保留的有效 label 集合，UL 是待扩展队列，被后续占优删除的对象不从优先队列中物理删除，而是在出队时跳过。

当前占优条件仍是第一层保守标量版，不是最终的函数级 TWET dominance。具体规则为：两个 label 必须在同一个终端 job 的 TL 表中比较；若 label A 的 visited 集合包含于 label B，且 A 的 source duration、internal duration、当前 partial reduced-cost bound 都不劣于 B，则认为 A 支配 B。这里的 partial reduced-cost bound 通过当前真实部分序列调用 `TWETColumnEvaluator` 得到成本，再扣除机器数 dual、job dual 和已有 arc dual。这个做法能明显减少双向 label 数和 join 候选，但它还没有把 forward/backward 分段 reduced-cost frontier 下沉到 label 层，因此不能等同于 `GC` 中基于 `PiecewiseLinearFunction` envelope 的完整 set dominance。后续如果继续优化双向 pricing，下一步应把 forward label 的 reduced-cost frontier、backward label 的后向 frontier 以及方向化 dominance store 加进来，而不是长期依赖这个标量近似。

验证上，本次重新运行 `HEU.PricingAlgorithmComparisonTest`。3 个随机小算例、7 个 pricing 轮次中，单向 paper-dominance exact 和双向 exact 的最小 reduced cost 仍全部一致；加入占优后，双向平均耗时从上一版约 `62.29 ms/round` 降到约 `7.14 ms/round`，但仍慢于单向的约 `1.71 ms/round`。随后运行 `HEU.SmallBPCBatchTest`，8 个随机小算例与 ArcFlow 目标值全部一致，tariff 分支诊断例也通过；补跑 `PaperDominanceGraphConsistencyTest`，200 组、16000 次插入对拍通过。当前结论为：双向 pricing 的旧 VRP 风格 TL/UL 占优生命周期已经补齐，正确性在现有对拍范围内未发现问题；但函数级双向 dominance 和 label 层 reduced-cost 递推仍是后续效率强化点。

补充说明“函数级双向 dominance”的含义：单向 `GC` 里的 `Label` 保存的是一条 `PiecewiseLinearFunction frontier`，它表示当前 partial sequence 在不同完成时间上的 reduced-cost 函数；`DominanceGraph` 不是只比较一个数，而是把多个 label 的 frontier 做下包络，然后判断这个 envelope 是否在整个定义域上压住新 label 的 frontier。因此这里的占优是“函数对函数”的占优，也就是对所有相关完成时间都不差。当前双向 `GCBidirectional` 的 `BiLabel` 还没有保存 forward/backward 的分段函数 frontier，只保存真实序列、visited 集合、duration，以及一个用当前部分序列重算出来的 `dominanceReducedCost` 标量。所以现在的双向占优只是旧 VRP 式 TL/UL 生命周期加一个标量剪枝，不是最终的函数级 TWET dominance。

如果后续做最终版本，forward `BiLabel` 应携带类似单向 `Label.frontier` 的前向 reduced-cost 函数，扩展 `last -> job` 时通过 `shiftX/add/shiftY/normalize(FORWARD)` 增量更新；backward `BiLabel` 应携带反向 reduced-cost 函数，扩展 `job -> first` 时用反向 shift/add 和 `normalize(BACKWARD)` 或 suffix-min 语义更新。两侧入 `FWTL/BWTL` 时，不再用一个 `dominanceReducedCost` 标量比较，而是按方向使用函数 envelope 做 set dominance。这样才能和单向 paper-dominance 的语义一致，并把更多剪枝提前到 label 层和 join 前。
## 2026-05-21：旧 VRP GCTabu 启发式找列流程复核

这里讨论的是旧 VRP BPC 代码里 `BPC.GC.GCTabu` 的启发式列生成，不是 `BPC.CUT.Tabu` 里的 cut 分离 tabu。`GCTabu` 的定位是 pricing 的快速层：先用当前 RMP 的对偶信息，从已有 route 中选一些低 reduced cost 的种子列，在这些种子列附近做 tabu 局部搜索，尽量快速找到负 reduced cost route；如果启发式找不到，后面再交给精确定价兜底。

在普通列生成流程中，入口在 `PC.Solve()`。每一轮先调用 `GCTabu.Solve(lp)`，它内部会重复执行“解 LP、取 dual、选种子、tabu 扰动、加负 reduced cost 列”的循环；只要启发式还能加列，就继续用启发式推进。启发式停止后，再调用精确定价 `GCNGBB.Extend(lp)`。如果精确定价又加了列，外层循环重新回到启发式层。也就是说旧代码的策略是：能用快的 tabu 找列就优先持续用 tabu，tabu 找不到以后才用慢的 exact pricing。

`GCTabu.Extend(lp,node)` 先从当前 active route 里按 CPLEX reduced cost 选种子，数量由旧参数 `m_tabu_cg_size` 控制。对每条种子 route，`Initialize()` 会恢复当前任务序列、访问状态、载重、subset-row cut 命中计数 `sr_count`，并按当前 dual 计算该 route 的 reduced cost。随后 `UpdateSegInfo()` 为这条 route 预处理所有连续子段的 `seg_early_start / seg_late_start / seg_duration`，这些数组用于后续 remove/add/exchange move 的 O(1) 或近 O(1) 时间窗可行性判断。

tabu 搜索本体在 `Tabu(lp)`。每一轮枚举三类邻域：删除当前 route 中的一个客户、向 route 某个位置插入一个未使用客户、用未使用客户替换 route 中的一个客户。每个 move 先调用 `IsFeasible()` 检查容量、弧可行性和时间窗拼接可行性；通过后再用增量公式更新 reduced cost。增量项包括距离成本、客户 dual、arc branching dual，以及 active subset-row cut 的 `sr_mu`。如果某个 move 被 tabu 禁止，但能打破当前 best reduced cost，也可以通过 aspiration 接受。执行最优 move 后更新 route、used 集合、载重、`sr_count`、tabu tenure 和子段预处理数组。

当搜索过程中当前 route 的 reduced cost 变成负数，且全局 route pool 里没有重复 route 时，就先放入 `GCTabu` 自己的本地候选池。候选池达到旧参数 `m_gen_size` 后停止当前搜索。所有种子搜索结束后，本地池按 reduced cost 排序，最多向 LP 加入 `addin_size` 条最好的新列。旧代码这里有两层控制：`m_gen_size` 控制本地候选池规模，`addin_size` 控制本轮真正加入 RMP 的列数。

在分支节点可行性修复中，`GCTabu.FindFeasible(lp,node,col_number)` 被 `Branch*.UpdateRouteSet()` 调用。旧流程是先尝试直接解当前 child LP；如果 slack 已经为 0，则说明当前列集可行。否则先用 `GCTabu.FindFeasible()` 找可行修复列；如果启发式加了列，会 reset 精确定价的部分状态，再调用 `GCNGBB.FindFeasible()` 兜底。只要两者还能加列，就继续循环；如果两者都加不出列，或者达到分支修复列数上限，才认为修复失败。这个逻辑和普通 pricing 一样体现了“启发式优先，精确兜底”的结构。

因此旧 VRP 的 tabu 找列框架可以概括为：从当前 RMP 低 reduced cost route 取种子，围绕种子做带 tabu tenure 和 aspiration 的 remove/add/exchange 局部搜索，用预处理子段数组快速判断时间窗可行性，用 dual 增量快速评估 reduced cost，把找到的负 reduced cost route 放入本地池，再按 reduced cost 选最好的若干列加入 RMP。它不是单独求一个完整启发式解，而是服务于列生成的“负 reduced cost 列发现器”。

补充说明 tabu 的思想本身。旧 VRP 这里不是单纯做最速下降局部搜索，而是允许当前 route 接受某个邻域里的最好 move，即使这个 move 不一定让 reduced cost 立刻变好。为了避免刚移动过的客户马上被反向移动撤销，代码给相关客户设置 `tabu_tenure`，在若干轮内禁止它参与相反或重复性质的 move。这样搜索可以跳出局部最优，在一个种子 route 附近探索更大的邻域。禁忌不是绝对的：如果某个 tabu move 能得到比历史最好 `best_cost` 更低的 reduced cost，就通过 aspiration 规则放行。也就是说，tabu 的核心是“短期记忆防止来回震荡 + aspiration 允许明显更优解破禁 + 接受非改进 move 扩大搜索范围”。

更具体地说，tabu tenure 的作用不是直接判断一条 route 是否可行，也不是判断这条列是否能加入 RMP，而是控制局部搜索过程中的“下一步能不能动某个客户”。例如当前 route 是 `0-a-b-c-0`，某一轮选择 remove `b`，route 变成 `0-a-c-0`，同时把 `b` 标记为 tabu 若干轮。后面几轮即使“把 `b` 插回 `a` 和 `c` 中间”看起来是当前邻域里不错的 move，也会因为 `b` 还在 tabu 期内被禁止，从而避免刚删掉又立刻插回去。反过来，如果这个插回动作能产生全局以来最好的 reduced cost，就可以通过 aspiration 破禁。旧 VRP 的 `GCTabu` 就是用这种机制在 seed route 周围持续扰动，边走边收集所有遇到的负 reduced-cost route，而不是只保留最后停下来的 route。

旧 VRP 默认参数是 `m_tabu_cg_tenure=30`、`m_tabu_cg_iteration_number=50`。因此某个客户在第 `iter` 轮被操作后，会被设置为 `tabu_tenure[customer] = iter + 30`，后续只有当当前轮数 `iter >= tabu_tenure[customer]` 时才自然解禁；若候选 move 的 reduced cost 优于历史最好 `best_cost`，则可以提前通过 aspiration。单条 seed 的 `Tabu()` 搜索停止条件主要有三类：达到 50 轮迭代上限；某一轮没有任何可接受 move，即 `best_place == -1`；本地负 reduced-cost 候选池达到 `m_gen_size=1000`。当前 TWET 版参数也按旧 VRP 对齐为 `heuristicPricingTabuTenure=30`、`heuristicPricingTabuIterations=50`、`heuristicPricingPoolSize=1000`，并额外在最终返回 RMP 时最多取 `maxHeuristicPricingColumns=150` 条优质列。

再按执行粒度澄清一次：tabu 搜索是对每条 seed route 单独执行的，不是全局共用一个 `tabu_tenure`。每处理一条 seed，都会用这条 seed 初始化当前 route、used 集合、reduced cost 和 `tabu_tenure`；这条 seed 的搜索结束后，再换下一条 seed 重新初始化。不同 seed 之间共享的是本地负 reduced-cost 候选池，用于最后统一排序加列；不共享某个客户是否 tabu 的状态。单条 seed 的每一轮都会完整扫描 remove、add、exchange 三类邻域，过滤不可行 move，再用 tabu tenure 和 aspiration 判断是否可接受，最后选择 reduced cost 最小的那个可接受 move 更新当前 route。它不是遇到第一个非 tabu move 就更新，而是“全邻域扫描后取当前最好”。如果一整轮没有任何可接受 move，才停止当前 seed，转到下一条 seed。
## 2026-05-21：Tanaka 50 任务无外包 BPC 诊断

本次按“外包成本设为无穷大，只允许真实机器调度”的设定，尝试求解 Tanaka 扩展多机器算例 `data/50-2/wet050_001_2m.dat`。由于当前 `Data` 构造函数里仍有 `debug_set()` 会把规模改成 60/3，直接读取 50 任务 setup 文件会在读到 `SETUP` 行时报错。因此新增了一个只用于诊断的 runner `HEU.TanakaNoOutsourcingBPCTest`，手工读取该文件的 50/2 数据、setup time 矩阵，并把所有 `outsourcingCost[j]` 设为 `Utility.big_M`。这个 runner 不修改生产 `Data.java`，也不复用 base `Data` 构造时留下的旧 best solution。

该实例确实包含非零 setup time。文件中 50 条任务数据之后有 `SETUP` 块，矩阵中大量元素非零，例如 depot 到任务的首行包含 `31, 8, 21, 51, ...`。当前诊断 runner 会把该 `SETUP` 块读入 `data.s`，因此 BPC pricing、列评估和启发式 seed 都是在有 setup time 的条件下运行。这里没有额外 `SETUP_COST` 块，所以 `setupCost` 仍为 0；本次诊断只检验 setup time + no outsourcing。

测试命令使用 CPLEX native 路径：

```powershell
java -Djava.library.path=D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64 `
  -cp target/classes;D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\lib\cplex.jar `
  -Dtwet.bpc.verbose=true `
  -Dtwet.bpc.bidirectional=false `
  -Dtwet.bpc.maxNodes=20000 `
  HEU.TanakaNoOutsourcingBPCTest data/50-2/wet050_001_2m.dat
```

第一次运行失败不是算法问题，而是 `java.library.path` 没有指到 `cplex2211.dll` 所在目录。补上 `D:\软件\cplex\ILOG\CPLEX_Studio2211\cplex\bin\x64_win64` 后可以正常启动 CPLEX。

10 分钟限时内没有完成根节点求解。日志显示当前流程已进入 root node，初始列 190 条，初始 incumbent 为 144453。随后启发式 pricing 多轮生成负 reduced-cost 列，列池从 190 增长到 8885，最后启发式 pricing 报告不再找到负 reduced-cost 列。之后进入 exact pricing，但在 10 分钟限时内 exact pricing 没有返回，因此没有形成 root LP 的最终 bound，也没有进入分支。当前能确认的是：无外包 50 任务实例已经能启动并完成启发式 pricing 阶段，但现有 exact pricing 在该规模上仍然是瓶颈，至少这个算例 10 分钟内没有求解完成。

这个结果说明当前 BPC 框架在小规模对拍上可用，但面对 50 任务、无外包、真实机器调度版本时，根节点 exact pricing 还不够强。后续优先方向不是再调树搜索，而是优化 exact pricing：包括真正高效的双向函数 label dominance、NG/DSSR、pricing 统计、以及对启发式 pricing 生成列数量和 exact pricing 启动条件做更细的性能分析。

随后复查 `seedALNS=true` 的情况，前面“ALNS 可能不出结果”的判断需要修正。短限时 60 秒复现实验显示，ALNS seed 实际上可以完成，并且把初始 incumbent 从不跑 ALNS 时的 144453 降到 47396；日志随后进入 root node，并开始启发式 pricing。因此之前看起来“ALNS 没结果”，主要是因为测试 runner 默认关闭控制台输出，且只有最终完成时才写汇总；如果后续 exact pricing 没返回，就看不到中间状态。实际瓶颈仍然在 root pricing，特别是启发式 pricing 之后的 exact pricing，而不是 ALNS seed 本身。

进一步分析 root pricing 卡住的原因：它不是 LP 求解困难，也不是 ALNS seed 困难，而是 exact pricing 在证明“没有新的负 reduced-cost 列”时要做接近穷尽的 forward labeling。当前 `PC.solvePricingLoop()` 的流程是旧 VRP 风格：只要启发式 pricing 还能补列，就反复启发式补列并重解 LP；当启发式报告没有负 reduced-cost 列后，才调用 exact pricing 兜底。日志中启发式 pricing 已经把列池扩到 8885，最后返回 “found no negative reduced-cost column”，因此后续卡住的位置就是 exact forward labeling 的兜底证明。

这个子问题本身确实会很难。50 个任务、2 台机器、无外包时，pricing 要在一台机器上找任意任务子序列列，判断其 reduced cost 是否为负。本质上接近带序列相关 setup time 和分段时间惩罚函数的 elementary shortest path / scheduling pricing；没有外包时，所有任务都必须靠机器列覆盖，RMP dual 会让大量组合的 reduced cost 接近 0，启发式已经找到很多负列后，exact pricing 需要证明剩下所有组合都不负，难度明显高于“找到一条好列”。当前 exact pricing 虽然有 dominance graph，但仍是单向 forward、完整集合占优，不使用 NG/DSSR，不使用函数级双向拼接 dominance，也没有 SRI/cut reduced-cost 的增量结构；函数 label 的形状还会削弱简单标量占优。由此在 50 规模上根节点 exact pricing 爆标签是合理现象。

所以当前瓶颈判断为：启发式 pricing 负责“找列”已经能工作，但 exact pricing 负责“证明没有列”还不够强。后续如果要让 50 规模 no-outsourcing 跑动，优先级应是补强 exact pricing：真正可用的双向函数 label 与 join、NG/DSSR 放松加动态收缩、pricing 内部标签数/占优数/剪枝数/耗时统计，以及必要时先用较强启发式给出更好的 incumbent 和更紧剪枝。单纯增加树节点上限或调整分支策略对这个 root bottleneck 没直接帮助。

## 2026-05-21：Tanaka no-outsourcing 清零 setup time 对比

为判断前一轮 50 任务 no-outsourcing BPC 卡住是否主要由 setup time 引起，本次在诊断 runner `HEU.TanakaNoOutsourcingBPCTest` 中增加 `-Dtwet.bpc.zeroSetup=true` 开关。该开关只在 runner 手工读完 `SETUP` 块后把 `data.s` 清零，不修改原始数据文件，也不改生产 `Data.java` 读取流程；其目的只是做同一 Tanaka 任务数据、同一 no-outsourcing 设置下的 setup time 影响对比。

测试命令在原 no-outsourcing 命令基础上增加：
```powershell
-Dtwet.bpc.zeroSetup=true
```
日志写入 `test-results/bpc/2026-05-21-tanaka-no-outsourcing-zero-setup-bpc-run.log`。10 分钟限时内仍未完成根节点求解。清零 setup 后流程正常进入 root node，初始列仍为 190 条，初始 incumbent 从有 setup time 时的 144453 降到 98071；随后启发式 pricing 多轮补列，列池增长到 9357，最后启发式 pricing 报告找不到新的负 reduced-cost 列。之后进入 exact pricing，但 exact pricing 在 10 分钟内仍未返回，因此没有得到 root LP bound，也没有进入分支。

与非零 setup time 的同一实例对比，清零 setup 并没有让 BPC 明显变快。有 setup time 时启发式 pricing 把列池扩到 8885 后进入 exact pricing 并超时；清零 setup 后列池反而扩到 9357，说明 setup time 不是当前 50 任务 no-outsourcing 卡住的主因。更合理的解释是：无外包条件下所有任务都必须由真实机器列覆盖，RMP dual 会让大量序列 reduced cost 接近 0；启发式负责找负列还能工作，但 exact pricing 要证明没有剩余负列，仍然会遇到标签爆炸。当前瓶颈仍是 exact pricing 的证明能力，而不是 setup time 本身。

## 2026-05-21：Tanaka no-outsourcing 启发式 pricing 迭代与初始列来源

进一步复核日志后确认，前面提到的列池规模不是单轮生成，而是 root 节点内多轮 LP-启发式定价循环累计得到的。zero-setup 运行中 `HeuristicPricing` 被调用 57 次，其中 56 次成功加列，总新增 9167 条，列池从初始 190 增长到 9357；非零 setup 运行中调用 52 次，其中 51 次成功加列，总新增 8695 条，列池增长到 8885。每次启发式定价加列后，`PC.solvePricingLoop()` 会立即把列加入 RMP、重解 LP，然后重新从第一个 pricing engine 开始；因此它符合旧 VRP 风格“启发式能找列就持续用启发式，找不到后才进入 exact pricing”的流程。

启发式找列本身不是单个 O(1) 操作。当前 `HeuristicPricingEngine` 每轮先从 restricted columns 中按 reduced cost 选最多 `heuristicPricingSeedColumns=30` 条 seed；对每条 seed 做 tabu 搜索，最多 `heuristicPricingTabuIterations=50` 轮，每轮枚举 remove/add/exchange 邻域。候选 move 的机器序列成本评价使用 forward/backward 分段函数 profile 和 `merge2Segments/merge3Segments` 做快速拼接，不是每个候选都完整重建整条序列；但每次接受一个 move 后会重建当前 seed route 的 profile。因此单个候选评估基本是拼接级快速评估，整轮启发式仍然可能慢，原因是 seed 数、tabu 轮数和 add/exchange 候选规模会形成大量候选扫描。

初始列来源也已确认：`Tree.solve()` 通过 `InitialColumnBuilder.build()` 构造 root 初始列。它先由 `HeuristicSeedProvider` 生成一个 seed solution；在当前 `TanakaNoOutsourcingBPCTest` 中默认 `config.runALNSForSeed=false`，只有显式传 `-Dtwet.bpc.seedALNS=true` 时才会在初始构造解之后跑 ALNS。初始列只来自最终 seed solution：每台机器的完整序列作为 incumbent/full seed 列，再切一些短子序列列和 singleton 列；ALNS 搜索过程中曾经出现过的中间好解不会自动进入初始列池，除非它最终更新成 `data.configure.bestSolution` 并作为最终 seed 返回。

## 2026-05-21：启发式 pricing 候选复杂度与初始列生成口径补充

这里的 `O(k)` 指当前 tabu pricing 中一条候选序列长度为 `k` 时，一些非函数拼接部分仍会随序列长度线性增长。候选 move 的机器成本没有完整重算整条序列，而是用 seed route 的 forward/backward profile 和 `merge2Segments/merge3Segments` 拼接；但为了生成候选对象，代码仍会复制一份 `ArrayList`，并在计算 reduced cost 时遍历候选序列扣 job dual 与相邻 arc dual。每次 tabu 接受一个 move 后，还会为新的当前 route 重建 forward/backward profile。由此当前实现是“成本函数评价局部拼接化”，不是“整个候选评价严格 O(1)”。后续若要优化，应优先减少候选序列复制、缓存 job dual 和与 arc dual、以及对已接受 move 的 profile 做局部更新。

初始列中的“短子序列”和 singleton 也进一步澄清。`InitialColumnBuilder` 先把最终 seed solution 中每台机器的完整任务序列加入列池，作为 incumbent 对应列；随后若 `generateSubsequenceColumns=true`，会从这些完整机器序列里切连续短片段，长度最多 `maxSeedSubsequenceLength=4`，作为额外初始列；最后若 `generateSingletonColumns=true`，会给每个 job 补一条单任务列。Tanaka 50/2 诊断中 root 初始列数为 190，来源就是最终 seed 的两条完整机器列、这些机器序列中的短连续子序列，以及 50 条 singleton 的去重结果。ALNS 过程中的中间解不进入初始列池，只有最终 best seed 会被拆成这些列。

旧 VRP 的根节点初始列入口是 `HEU.GenRoute`，它先构造一个启发式可行解/route set，作为 root LP 的起始列集；后续分支节点的列继承与筛选由 `UpdateRouteSet()` 处理，参数 `m_initial_col_number=1000` 控制子节点最多保留多少条低 reduced-cost 列。当前 TWET 的 `InitialColumnBuilder` 是按这个思想做的适配，但因为 TWET 单机列比 VRP route 更依赖序列成本函数，所以额外加入短连续子序列和 singleton 作为启动稳定性补充；这不是旧 VRP 完全同款的 root 生成细节，而是当前问题上的保守初始化。

## 2026-05-21：更正 root 初始列与旧 VRP 的对应关系

进一步核对旧 VRP 代码后，需要更正前面关于 root 初始列的表述。旧 VRP 的 root 初始列来自 `Tree.Solve()` 中调用 `HEU.GenRoute.ConstructSolution()` 得到的启发式 route set，然后用 `new Node(data, gr.solution)` 直接作为 root node 的 `route_set`。也就是说，root 初始列基本就是启发式解里的 route，不会额外从这些 route 中切短子 route，也不会补 singleton route。参数 `m_initial_col_number=1000` 不是 root 初始列参数，而是各类 `Branch*.UpdateRouteSet()` 在生成 child route set 时，从已有 pool 中按 reduced cost 筛列的上限。

当前 TWET 的 `InitialColumnBuilder` 与旧 VRP root 逻辑不完全相同：它除了加入最终 seed solution 中每台机器的完整序列，还额外切长度不超过 `maxSeedSubsequenceLength=4` 的连续短子序列，并补 singleton。`maxInitialColumns=2000` 也是当前 TWET 版本新增的 root 初始列上限，不是旧 VRP root 的同名参数。这样做的初衷是让 root RMP 一开始有更多组合弹性，但从严格模仿旧 VRP root 的角度看，这确实是额外策略，不是必须项；如果要做旧 VRP 风格对照，可以关闭 `generateSubsequenceColumns` 和 `generateSingletonColumns`，只保留最终 seed 的完整机器序列作为 root 初始列。

以 Tanaka 50/2 no-outsourcing 诊断为例，当前 root 初始列为 190 条；如果按旧 VRP root 风格只放最终 seed 中每台机器的完整序列，则只有 2 条机器列。当前 190 条相当于比纯完整 seed 列多了 188 条，主要来自短连续子序列和 singleton。它们不影响可行性，因为完整 seed 列本身已经给了一个可行整数解；它们只影响 root LP 起步松弛和后续 dual/pricing 轨迹。

## 2026-05-21：root 初始列回退到旧 VRP 风格

根据进一步讨论，当前 TWET-BPC 的 root 初始列默认回退到旧 VRP 风格：只使用最终 seed solution 中每台机器的完整序列作为 root 初始列。旧 VRP 的 root 入口是 `Tree.Solve()` 调用 `GenRoute.ConstructSolution()`，随后 `new Node(data, gr.solution)`，因此 root 初始列就是启发式解中的 routes；它不会额外切短子 route，也不会补 singleton。`m_initial_col_number=1000` 是 child `UpdateRouteSet()` 的筛列上限，不是 root 初始列参数。

本次将 `TWETBPCConfig.generateSubsequenceColumns` 和 `generateSingletonColumns` 默认改为 `false`，保留开关仅用于后续诊断对比。`InitialColumnBuilder` 中补充注释，说明短子序列和 singleton 不是旧 VRP root 初始列逻辑。这样 Tanaka 50/2 no-outsourcing 诊断的 root 初始列从原来的 190 条降为 2 条，即最终 seed 解中的两台机器完整序列；短跑日志确认 `Initial columns=2, incumbent columns=2`。

同时明确启发式 pricing move 的当前实现边界：候选机器成本使用 forward/backward profile 和 `merge2Segments/merge3Segments` 快速拼接，但 `evaluateRemove/evaluateAdd/evaluateExchange` 仍会为每个候选构造新的 `ArrayList`，并在 reduced-cost 计算中遍历候选序列扣 job dual 和 arc dual。因此当前不是“每个 move 完全不构造新序列、严格 O(1)”的实现；若要进一步加速，需要单独把候选序列构造延迟到最终 best move，或者维护 dual 前缀/弧增量缓存。

验证上，`javac` 编译 `TWETBPCConfig`、`InitialColumnBuilder` 和 `SmallBPCBatchTest` 通过；`HEU.SmallBPCBatchTest` 8 个随机小例与 ArcFlow 全部一致，tariff 分支诊断例也通过。Tanaka 短跑只用于确认 root 初始列数量，未作为完整求解测试。
## 2026-05-21：删除 root 阶段额外短子序列和 singleton 初始列

前面已经确认旧 VRP 的 root 初始列来自 `GenRoute.ConstructSolution()` 得到的完整 route set，不会再从这些 route 里切短子序列，也不会额外补 singleton。为了让当前 TWET-BPC 的 root 初始化流程和旧 VRP 保持一致，本次直接删除了 `generateSubsequenceColumns`、`generateSingletonColumns`、`maxSeedSubsequenceLength` 和 `maxInitialColumns` 这些参数，以及 `InitialColumnBuilder` 中对应的短子序列和 singleton 生成代码。`ColumnSource` 中的 `HEURISTIC_SUBSEQUENCE` 和 `SINGLETON` 也同步删除。

现在 root 初始列只有最终 seed solution 中每台真实机器的完整任务序列。这样做的含义是：root LP 的起点完全依赖启发式最终解给出的完整机器列，后续组合空间交给 pricing 逐轮补列，而不是在 root 初始化阶段人为塞入短子列。这个处理和旧 VRP 框架更一致，也避免额外初始化策略影响后续效率判断。

启发式 pricing 的 tabu move 流程也重新核对如下。给定一条 seed 列后，`TabuRouteState` 会先为当前序列构造 forward/backward profile。每一轮 tabu 搜索枚举 remove/add/exchange 三类 move。单个候选 move 的机器成本不是整条序列重算，而是通过 `removeCost()` 或 `insertOrReplaceCost()` 调用 `merge2Segments/merge3Segments` 进行 profile 拼接评估；但当前实现仍会为候选构造一个新的 `ArrayList`，并在 `reducedCost()` 中遍历该候选序列扣除 job dual 和相邻 arc dual。因此当前是“机器成本函数拼接评估较快”，不是“整个 move 评估严格 O(1) 且完全不构造候选序列”。接受一个 move 后，代码会把该候选作为新的当前序列，并重建 forward/backward profile。

验证上，本次编译 `TWETBPCConfig`、`InitialColumnBuilder`、`ColumnSource` 及相关测试入口通过；`HEU.SmallBPCBatchTest` 8 个随机小算例继续与 ArcFlow 目标值一致，tariff 分支诊断例也通过。
## 2026-05-21：启发式 pricing move 改为局部增量 reduced cost

对照旧 VRP 的 `BPC.GC.GCTabu` 后，确认旧实现只在 seed route 初始化时完整遍历一次 route 得到 `reduce_cost`，后续 remove/add/exchange 候选都用局部边变化、customer dual、arc dual 和 cut dual 做增量更新，不会为每个候选构造新 route 后再从头扫描。当前 TWET 版本此前虽然已经用 forward/backward profile 和 `merge2Segments/merge3Segments` 快速评估机器真实成本，但 reduced cost 仍然为每个候选复制 `ArrayList` 并遍历扣 job dual 与 arc dual，这一点和旧 VRP 不一致。

本次修改将 `HeuristicPricingEngine.TabuRouteState` 的候选评估改成旧 VRP 风格。每个候选 move 只保存类型、位置、涉及任务、候选真实成本和候选 reduced cost；remove/add/exchange 的 reduced cost 由当前 `currentReducedCost` 加上 `candidateCost - cost`，再局部替换受影响 job dual 和两三条 arc dual 得到。候选阶段不再构造完整候选序列，也不再调用全序列 `reducedCost(...)`。只有 seed 初始化、接受 move 后加入负列池、以及最终把列交给 RMP 时，才需要使用完整序列。

为了保持分支兼容性，当前 seed 进入 tabu 前仍完整检查一次 forbidden arc；之后每个局部 move 只检查被新接上的局部弧。这个逻辑成立的前提是当前 seed 已经满足当前节点的 forbidden arc 约束，局部 move 只会改变少数弧，因此检查新弧即可。

验证上，`HeuristicPricingEngine.java` 单独编译通过；`HEU.SmallBPCBatchTest` 8 个随机小算例继续与 ArcFlow 目标值一致，tariff 分支诊断例也通过。

## 2026-05-21：旧 VRP 不带 ng-route 的双向 labeling 如何 join
本次复核旧 VRP `BPC.GC` 下不带 ng-route 的双向版本，主要看 `GCBDT.java` 和 `GCDSS.java`。结论是：旧代码的 bounded bidirectional label setting 是在同一个中间客户点 `cid` 上 join，不是按一条连接弧 `(i,j)` join。

具体证据在 `GCDSS.Join()` 中最清楚：外层遍历 `cid`，然后取 `BWTL.get(cid)` 中的 backward label，再遍历 `FWTL.get(cid)` 中的 forward label。也就是说，两边 label 的 terminal 都是同一个 `cid`。拼接 reduced cost 时用 `lbf.m_reduced_cost + label.m_reduced_cost + lp.mu[cid]`，这里加回 `mu[cid]` 是因为中间点 `cid` 同时出现在前向和后向 label 的 reduced cost 中，被扣了两次，需要补回一次。路线恢复时 `JoinRoute(lbf, label)` 先沿 forward label 恢复到 `cid`，随后从 `blb.father` 开始接 backward 路径，刻意跳过 backward label 自己的 `cid`，避免中间点重复出现。

`GCBDT` 的逻辑也是同一类点 join。它在 backward 扩展过程中，如果当前 backward label 的 `cid` 不是 sink，就临时 `ResetVisit(label, cid)`，然后只和 `FWTL.get(cid)` 的 forward labels 配对。通过容量、时间和访问集合检查后，同样用 `JoinRoute(lbf, label)` 拼接，其中 backward 部分从 `blb.father` 开始。因此它也不是枚举前向末端 `i` 和后向起点 `j`，再用弧 `(i,j)` 连接。

这对当前 TWET 双向 pricing 的含义是：如果要“按旧 VRP 框架”复刻不带 ng-route 的 bounded bidirectional join，最直接对应的是同一任务点 join。若改成 `(i,j)` 弧 join，会更像另一类 front-to-back 拼接策略，可能覆盖更多连接方式，但已经不是旧 VRP 这两个基础双向类的原始流程。后续如果要做函数级双向 label，除非明确决定升级为弧 join，否则应先按点 join 对齐旧代码，再考虑是否为了 TWET 的 setup/函数结构增加弧 join 版本做对比。

旧 VRP 里还有一条容易误读的注释：`GCNGB.java` 文件头写的是 `ng-route with complete bound`，并注明 `cannot be combined with bounded bidirectional search, because the bound is not complete`。这里不能简单理解成“ng-route 和双向 labeling 冲突”。因为同一目录下 `GCNG.java` 明确就是 `ng-route, using bounded bidirectional label setting`，`GCNGBB.java` 也写了 `ng-route, using decremental state space ... using bounded bidirectional label setting ... using the complete bound to cut off some labels`。因此更准确的理解是：旧作者认为 `GCNGB` 那种单向/完整 bound 的剪枝方式不能直接搬到 bounded bidirectional search 里使用，因为在 bounded 双向只扩到一半资源时，那个 bound 不是完整路径意义上的安全 bound；后续 `GCNGBB` 则通过 DSS、2d/2-cycle-free bound 和额外 bound checks 重新组织了组合方式。

对当前 TWET 的启示是：不要把这条注释当成“不能做 NG + 双向”。可以做 NG + 双向，但如果要加入类似 complete bound 的剪枝，必须重新证明它在 bounded forward/backward label 和 join 结构下仍然是安全下界/上界，不能直接把单向 bound 公式照搬到半程 label 上剪。
## 2026-05-21：启发式 pricing 的 singleton profile 与 seed reduced cost 缓存

本轮继续处理 `HeuristicPricingEngine` 中和旧 VRP `GCTabu` 对比后暴露出来的重复计算点。第一处是 add/exchange 候选里反复构造单任务 profile。单个 job 的前向 prefix-min 和反向 suffix-min 结果只依赖 job 自身，与当前 seed route、dual 或 tabu 状态无关，因此可以在 pricing engine 构造时预先缓存模板。实际 merge 时仍然返回 `PiecewiseLinearFunction` 副本，因为 `merge2Segments/merge3Segments` 过程可能修改传入函数对象，直接共享缓存会污染后续候选。

第二处是 seed 排序。此前 `collectSeedColumns()` 在 comparator 中调用 `reducedCost(...)`，排序过程会对同一条 seed 列重复扫描 job dual 和 arc dual。现在改为先为每条非空 restricted column 计算一次 reduced cost，保存为 `ScoredSeed` 后再排序。这个改动不改变 seed 选择规则，只减少排序阶段的重复遍历，和旧 VRP 先得到 route reduced cost 再按值选 seed 的思路一致。

验证上，`HeuristicPricingEngine.java` 单独编译通过；`HEU.SmallBPCBatchTest` 继续通过 8 个随机小算例对拍和 tariff 分支诊断。当前剩余的主要性能差异仍然是 TWET 候选真实成本必须通过分段函数 profile 拼接得到，且接受 move 后需要重建当前 route profile，这部分属于问题结构差异，不是简单缓存能完全消除。

## 2026-05-21：启发式 pricing 与旧 VRP GCTabu 流程效率复核

本次只比较启发式找列框架和计算流程，不把 TWET 分段函数评估本身视为不一致。复核旧 VRP `BPC.GC.GCTabu` 后，当前 `HeuristicPricingEngine` 的主流程已经基本对齐：先从当前 restricted columns 中选低 reduced-cost seed；对每条 seed 初始化当前 route 状态；每轮枚举 remove/add/exchange 三类邻域；候选 reduced cost 使用当前 reduced cost 加局部 job dual、arc dual 和真实成本变化做增量更新；tabu tenure 与 aspiration 规则一致；接受 move 后才修改当前 route 并重建可行性/成本摘要；发现负 reduced-cost route 后先放入本地 pool，最后排序并按 `addin_size` 风格的上限返回列。

参数语义也基本对齐。`heuristicPricingSeedColumns=30` 对应旧 `m_tabu_cg_size`，`heuristicPricingPoolSize=1000` 对应旧 `m_gen_size`，`maxHeuristicPricingColumns=150` 对应旧 `addin_size`，`heuristicPricingTabuIterations=50` 和 `heuristicPricingTabuTenure=30` 分别对应旧 tabu 迭代次数和 tenure。当前实现没有保留前面额外 root 短子序列、singleton 初始列之类的额外策略，root 初始列已经回到旧 VRP 风格。

剩余差异主要有三类。第一，旧 VRP seed 排序直接调用 CPLEX `getReducedCost(var)`，当前 TWET 仍用 `cost - dual` 手工计算 seed reduced cost；这是实现接口差异，已经缓存为每条 seed 只算一次，不再在 comparator 中重复扫描。第二，旧 VRP 的 route cost 是弧成本标量增量，TWET 的真实成本必须通过 forward/backward 分段函数 profile 拼接；这属于问题结构差异。第三，旧 VRP 带 SRI cut 时在 move 增量里维护 `sr_count`，当前 no-cut 版本暂不处理 cut dual；后续加 SRI 时应按旧代码把 cut 贡献也纳入局部增量，而不是回退到全序列扫描。

因此当前可以认为启发式 pricing 的流程和非问题特有的计算效率已经与旧 VRP 基本一致。后续如果还要进一步提速，优先方向不是再改 tabu 框架，而是减少 TWET profile 重建、优化分段函数 merge、或者在加入 SRI cut 后继续保持局部增量更新。

## 2026-05-21：merge 与 profile 重建的剩余优化空间

针对启发式 pricing 中剩下的两个 TWET 特有成本点，当前判断如下。第一，`merge2Segments/merge3Segments` 仍有优化空间，但主要是常数和对象分配层面的优化。现在 `merge2Segments` 会构造 `b2.shiftX(-shift)` 再和 `f1.add(...)` 生成临时函数，然后 `findMinimal()`；`merge3Segments` 也会构造 `merge12`、`merge23`，在第二种情形下还会连续构造多个 `shiftX/add` 临时函数。理论上可以写专门的 shifted-sum-min 扫描器，直接用双指针或多指针在原函数段上计算 `min f(t+a)+g(t+b)`，避免临时分段函数对象。这会减少 copy、add、release 和 SegmentPool 压力，但要重新处理定义域交集、big_M 段、端点极限和 gap 语义，风险比普通缓存高，必须用现有 merge 作为 oracle 做大量随机对拍。

第二，接受 tabu move 后当前会调用 `rebuild()`，从头重建整条 route 的 forward/backward profile。这一点确实还有理论优化空间：remove/add/exchange 只改变某个位置附近，forward 在该位置之后受影响，backward 在该位置之前受影响，因此可以只重建受影响前缀或后缀，甚至在同时维护局部 dirty 区间的情况下减少一半以上的重建量。但这会让状态维护明显复杂化，尤其是 add/remove 会改变数组下标，exchange 虽然长度不变但两侧 profile 仍分别受影响。旧 VRP `GCTabu` 接受 move 后也会调用 `UpdateSegInfo()` 重建整条 route 的段摘要，因此当前“接受 move 后重建 profile”的框架并不比旧 VRP 更落后，只是 TWET 的 profile 构造更重。

当前建议是：短期不再动 tabu 框架。若后续 profiling 显示启发式 pricing 仍是瓶颈，优先做 merge 的专用 shifted-sum-min 扫描器，因为它能同时服务启发式、局部搜索和后续 pricing；profile 局部重建排第二，除非确认 route 很长且接受 move 次数很多，否则它会显著增加代码复杂度。

## 2026-05-21：ALNS 历史 best 解作为 root 初始列的可行性

后续可以考虑把 ALNS/VND 过程中出现过的历史全局 best 解也转成 root 初始列。当前框架很适合接这个功能，因为 `EngineALNS` 和 `EngineVND` 更新全局最优时都会调用 `data.configure.updateBestSolution(solution)`，这个方法天然是记录历史 best 解的统一入口。只要在 `Configure` 里额外维护一个 `bestSolutionHistory`，每次更新 best solution 时复制一份加入历史池，就能捕获初始解、ALNS 每次刷新后的 best 解，以及 VND commit 中刷新出来的 best 解。

初始列构造侧也比较简单。现在 `InitialColumnBuilder.build()` 只调用 `seedProvider.getOrBuildSeed()`，然后把最终 seed solution 中每台机器的完整序列加入列池。若启用历史 best 解池，可以保持最终 seed 仍作为 incumbent，仅把历史 best 解中的机器序列额外加入 `initialColumnIds`。列池 `Pool.addColumn()` 已经用 `SequenceSignature` 做序列级判重，`InitialColumnBuilder` 也用 `LinkedHashSet` 去重，因此“历史解中重复出现的同一机器序列”不会重复进入 root RMP。外包任务不需要额外转成列，因为 RMP 已经有外包变量；历史解只贡献其中真实机器上的完整序列列。

这个功能对可行性没有风险：最终 seed 的完整机器列仍然作为 incumbent column set 返回，历史 best 只是增加 root 初始列。它和前面删除短子序列、singleton 的逻辑不冲突，因为这里加入的是 ALNS 实际到达过的完整机器序列，而不是人为切出来的组合片段。建议加一个配置开关，例如 `useBestSolutionHistoryForInitialColumns`，默认可以先打开或先用于诊断；如果后续发现 root LP 初始列过多影响速度，再限制历史池大小或只保留最近/最优若干个历史 best 解。

## 2026-05-22：v42 tex 双向 labeling 与当前 GCBidirectional 的差异判断

本次开始准备优化 exact pricing，目标是先实现 elementary 的双向 labeling。对照 `C:\Users\Changxin\Downloads\twet_outsourcing_models_revised_v42.tex` 中 “Bidirectional internal scheduling pricing” 小节后，当前主要结论是：现有 `src/TWETBPC/GC/GCBidirectional.java` 不能直接作为该算法的小修补版本，它和 v42 tex 的双向算法在 join 结构、label 状态和 dominance 语义上都有结构性差异。

当前 `GCBidirectional` 更像旧 VRP 中不带 ng-route 的点 join 框架：forward label 和 backward label 按同一个中间 job 存在 `FWTL.get(cid)` 与 `BWTL.get(cid)` 中，`tryJoin()` 也要求 `forward.jid == backward.jid`，拼接时跳过 backward 序列的第一个 job 以避免中间点重复。这个流程和前面复核过的旧 VRP `GCBDT/GCDSS` 的同点 join 是一致的。

但 v42 tex 里的 TWET 双向算法不是同点 join，而是通过一条跨越中点的连接弧 `(i,r)` join。forward label 表示到 job `i` 的 prefix，backward label 表示从 job `r` 开始的 suffix，两侧访问集合必须互不相交，并且要满足 `ell_L + s_ir + p_r <= rho_L^b`。最终 reduced cost 由 `min { f_L(t) + kappa_ir + f_b(u) } - lambda` 或等价的单时间变量形式计算，其中允许在 crossing arc 上等待。也就是说，真正的拼接点通常是两段之间的弧，而不是某个共同 job。若继续使用当前同点 join，会漏掉中点落在两个相邻 job 之间的序列，例如 prefix 结束于 `i`、suffix 从 `r` 开始、且 `i != r` 的情况；这不是单纯效率弱，而是可能影响 exact pricing 的完备性。

另一个差异是 label 成本状态。当前 `GCBidirectional` 为了避免反向函数定义域和 endpoint discontinuity 复杂性，join 后统一调用 `TWETColumnEvaluator` 对完整序列重算成本，并用 `dominanceReducedCost`、`sourceDuration`、`internalDuration` 做标量占优。v42 tex 要求 forward/backward label 都携带完成时间函数：forward 是 prefix 的 reduced-cost frontier，backward 是 “第一个 job 完成不早于 t” 时 suffix 的 reduced-cost 函数；dominance 也应比较函数和可扩展集合。当前这种标量 dominance 只能作为保守搜索控制，不能等价于论文中的函数级 dominance graph。

因此后续实现应分两步做。第一步先把双向 exact pricing 的结构改成 v42 tex 的 elementary 版本：forward/backward 都保留显式 visited set，dominance graph 仍按集合 key 做完整占优，不做 partial dominance，也不接 ng-route/SRI；join 改成枚举 crossing arc `(i,r)`，并处理 source label 与 backward label 的 `(0,r)` join，保证覆盖右半侧开始的完整列。第二步再处理函数域细节：如果暂时不动半域定义域，可以先保留全域函数并只在 join 处按 tex 的常数延拓语义取值；如果要严格按论文实现，则 forward label 存 `[ell,Tmid]`，backward label 存 `[Tmid,rho]`，extension/dominance 在半域上做，join 时使用常数延拓计算 crossing arc 的最小 reduced cost。

当前判断为：存在问题，不能直接在现有同点 join + 完整序列 evaluator 的框架上宣称已经实现 v42 tex 的 elementary 双向 labeling。下一步代码实现应以“新建或重写一个函数 label 型双向 exact pricing engine”为目标，而不是给现有标量 `GCBidirectional` 加局部判断。

## 2026-05-22：按 v42 tex 重写双向 exact pricing

基于前面的差异判断，本次直接重写了 `src/TWETBPC/GC/GCBidirectional.java`，不再保留原来那套“同一个中间 job 上 join + 标量 reduced-cost 占优”的实现。新的版本仍然沿用旧 VRP 双向 pricing 的外层控制框架，也就是 `FWUL/FWTL`、`BWUL/BWTL`、前向扩展、后向扩展、扩展后尝试 join 这套生命周期；但 label 内部状态、join 方式和函数处理都改成了更接近 v42 tex 的 TWET 语义。

这次最核心的改动有四个。第一，join 从“同点 join”改成“跨弧 join”。现在 forward label 终止于 job `i`，backward label 起始于 job `r`，真正尝试拼接时走的是 crossing arc `(i,r)`，而不是要求 `i=r`。这一步是必要改动，因为论文里的双向内部定价本来就是前缀、连接弧和后缀三部分组成；如果继续用同点 join，就会漏掉中点落在两任务之间的列。第二，forward/backward 两侧都改成函数 label。`ForwardLabel` 和 `BackwardLabel` 都保存 `PiecewiseLinearFunction frontier`、`visitedSet`、`reachableSet` 和当前最小 reduced cost，而不再只保存一条具体序列的标量值。第三，加入了 `T^mid` 半域语义。forward frontier 被限制在 `[ell, Tmid]`，backward frontier 被限制在 `[Tmid, rho]`，join 时再把 backward frontier 用论文对应的常数延拓方式投影回 forward 时间轴。第四，最终加列前仍统一调用 `TWETColumnEvaluator` 对完整序列做一次真实成本复核，避免双向递推和 join 细节如果有遗漏时把错误的 reduced-cost 列加进主问题。

join 的函数处理这次也一起改掉了。新的 `tryJoin()` 不再直接把正反向序列拼起来后重算一个标量近似，而是先取 forward frontier 在可 join 区间上的裁剪段，再构造 backward frontier 的 join 投影。这里 backward 的投影遵循当前实现里写入注释的语义：当 forward 时间 `x` 还没到 `Tmid-delta` 时，backward 侧取常数 `f_b(Tmid)`；当 `x` 进入右半边后，再取 `f_b(x+delta)` 的 shift 结果。随后把两边函数相加，再加 crossing arc 的 setup cost，最后用 `findMinimal(false, true)` 求 join 下界；只有这个函数级下界已经是负 reduced cost 时，才恢复完整序列并交给 `TWETColumnEvaluator` 做最终验证。

forward 侧的动态硬时间窗逻辑继续复用了现有单向 exact pricing 的做法。本轮定价开始时仍然根据当前 dual 预处理动态 `H_ij`，如果满足三角不等式就走 job 级缓存，否则走 pair 级缓存；forward 扩展时先做 `isDirectForwardExtensionTimeFeasible()` 的 O(1) 过滤，再用已经裁好的 `jobPenalty` 去做 `shiftX + add + fixed reduced-cost shift + normalize(FORWARD)`。backward 侧这次先保证函数递推和半域语义正确，但还没有补上完全对称的动态 `H^b_{ir}`。目前 backward extend 只使用已经写在 `data.penaltyFunction[j]` 里的静态粗硬时间窗，再做 `shiftX(-delay) + add + normalize(BACKWARD)`。这个版本会比最强实现弱一些，但不会破坏正确性；后续如果继续加强，只需要在 backward 侧也引入 pair 级动态窗口和对应裁剪即可。

占优方面，这次先没有上论文里的最终 dominance graph 版函数占优，而是保留“同终点表内的 pairwise 函数完整占优”。具体来说，forward/backward 两侧分别在同一个 `jid` 的表里比较 `reachableSet` 是否为超集，以及 `frontier.dominates(other.frontier)` 是否成立；如果成立，就删除被支配 label，并在出队、join 时跳过 `isDominated` 的对象。也就是说，这次已经从原来的标量 dominance 升级成了函数级完整占优，但还没有做 paper 里那种更强的图结构维护，更没有接 partial dominance、ng-route、DSSR 或 cut。

这次的验证分成两层。第一层是 pricing 对拍。重新编译 `GCBidirectional.java` 后，运行 `HEU.PricingAlgorithmComparisonTest`，3 个随机小例共 12 个 pricing 轮次中，单向 exact pricing 和新的双向 exact pricing 的最优 reduced cost 全部一致，结果写入 `test-results/bpc/2026-05-20-pricing-comparison.csv`。第二层是整棵小树回归。运行 `HEU.SmallBPCBatchTest` 后，8 个小规模实例全部完成，BPC 结果与 ArcFlow 对拍一致，同时 tariff 分支诊断例也通过，结果分别写入 `test-results/bpc/2026-05-19-small-bpc.csv` 和 `test-results/bpc/2026-05-19-small-bpc-branch.csv`。这说明当前版本至少在“小规模完整树 + 单轮 pricing 对拍”两个层面没有暴露错误。

当前仍然保留两个明确限制。第一，backward 侧还没有做完全对称的 pair 级动态硬时间窗收缩，因此它的剪枝强度低于前向侧，也低于论文里更完整的实现。第二，当前双向 exact pricing 仍然是 elementary、no-cut、no-partial-dominance 的基础版，后续如果要继续追求 50 任务以上实例的 exact pricing 效率，优先顺序仍然应该是：补 backward 动态窗口、把函数级 dominance 从 pairwise list 提升到更强结构、再考虑 NG/DSSR 和 cut。

## 2026-05-22：补齐 backward 侧的本轮动态硬时间窗

在上一版双向 exact pricing 里，forward 侧已经和单向 `GC` 一样，按当前 LP dual 预计算了本轮动态 `H_{ij}`，并在扩展前做 O(1) 时间窗过滤；但 backward 侧仍然只用 `data.penaltyFunction[j]` 里预先写好的静态粗硬时间窗。这虽然不破坏正确性，但会让 backward label 保留过多无效时间段，也会让 `reachableSet` 和 `tryJoin()` 前面的初筛偏弱。

这次只补这一块，不动 join、不动 dominance、不动外层流程。具体做法是把 backward 侧也改成和论文一致的 pair 级动态窗口。对于 backward 扩展中“把 job `i` 接到当前后缀最前端，后继为 `r`”这个局部结构，按 tex 中的定义使用
\[
    B_{ir}^b=\max_h[\kappa_{hr}-\kappa_{hi}-\kappa_{ir}]_+,
\]
再构造
\[
    \Gamma_{ir}^b=B_{ir}^b+\min\{\pi_i,b_i\},
\]
从而得到本轮 backward profitable completion window \(H_{ir}^b=[\underline h_{ir}^b,\bar h_{ir}^b]\)。如果当前数据满足 setup cost 三角不等式，则这些 backward setup-cost advantage 都退化为 0，此时仍然走 job 级缓存；否则就和 forward 一样，走 pair 级缓存。对于 `sink` 作为后继的根 backward label，按照 tex 的含义有 `B_{i,n+1}^b=0`，因此这里只保留 `min(jobDual, baseline outsourcing cost)` 这一部分。

实现上新增了三组 backward 缓存：一组是接虚拟终点 `sink` 时的 job 级窗口和裁剪后 penalty 函数，一组是一般 `successor` 场景下的 pair 级窗口上界/下界，一组是对应的 pair 级裁剪后 penalty 函数。`isDirectBackwardExtensionTimeFeasible()` 现在不再看静态 `hardWindowStart/hardWindowEnd`，而是直接用本轮缓存的 `H_{ir}^b` 做 O(1) 交集判断；`extendBackward()` 在 `shiftX(-delay)` 之后，也不再把 `data.penaltyFunction[i]` 直接加上去，而是改成加本轮已经裁好的 backward dynamic penalty。这样 backward frontier 的定义域、`rho` 更新和 `reachableSet` 计算就都和论文里的 `H_{ir}^b` 一致了。

这一轮改动后重新做了两类回归。第一，`HEU.PricingAlgorithmComparisonTest` 仍然保持 12/12 轮单向 exact 与双向 exact 的最优 reduced cost 完全一致；第二，`HEU.SmallBPCBatchTest` 仍然保持 8/8 个小例和 1 个 tariff 分支诊断例全部通过，BPC 与 ArcFlow 目标值一致。说明这次 backward 动态窗口增强没有破坏当前双向 pricing 的正确性基线。

到这一步，双向 exact pricing 中“只有 forward 用本轮动态硬时间窗、backward 仍用静态粗窗”的不对称已经去掉。当前剩余的主要强化点就不再是这个窗口层面，而是更强的函数级 dominance 结构、NG/DSSR 和 cut。

## 2026-05-22：把 backward setup-cost advantage 下沉到 Data 预处理

补完 backward 动态硬时间窗后，`GCBidirectional` 里还有一个明显但纯工程性的低效点：`B_{ij}^b` 仍然是在 pricing 初始化时按 pair 临时扫前驱 `h` 计算出来的。这样即使同一个实例多轮重解 RMP、重复做 pricing，backward 的 setup-cost advantage 也会被一轮一轮重复计算。

这次把这部分也和 forward 一样下沉到了 `Data`。现在 `Data.precomputeSetupCostAdvantages()` 在原有 `setupCostAdvantage[i][j]` 之外，同时预处理 `backwardSetupCostAdvantage[i][j]`，其含义正是论文里的
\[
    B_{ij}^b=\max_h[\kappa_{hj}-\kappa_{hi}-\kappa_{ij}]_+.
\]
这样 `GCBidirectional.backwardHWindowGamma()` 里就不再自己扫 `h`，而是直接调用 `data.getBackwardSetupCostAdvantage(i,j)`。这一改动不影响任何 reduced-cost 语义，只是把每轮 pricing 的重复三重循环搬到了数据初始化阶段。

回归验证继续保持通过。重新编译 `Data.java` 和 `GCBidirectional.java` 后，`HEU.PricingAlgorithmComparisonTest` 仍是 12/12 轮单向/双向最优 reduced cost 完全一致，而且这组小例上的双向平均时间从前一轮的约 `6.58 ms/round` 下降到约 `1.58 ms/round`；`HEU.SmallBPCBatchTest` 也继续保持 8/8 小例和 tariff 分支诊断例全部通过。这里的数值不应过度解读成严格 benchmark，但至少说明这次把 backward `B^b_{ij}` 下沉到 `Data` 后，没有引入行为变化，而且对当前小例的双向 pricing 常数开销是有帮助的。
## 2026-05-22：用 reachableSet 剪掉双向 join 的无效端点扫描

在前面的 crossing-arc 双向版本里，`joinFromForward()` 和 `joinFromBackward()` 功能上已经是对的，但外层仍然会把 `1..n` 或 `0..n` 的端点全部扫一遍，再去看相应的 `FWTL/BWTL` 表。这样做在小例上影响不大，但当某个 forward 或 backward label 的 `reachableSet` 已经因时间窗、`T^mid` 和禁弧收缩得很小时，这部分全量扫描就是纯常数开销。

这次只做一个低风险剪枝，不动 join 语义，也不动 dominance。`joinFromForward()` 现在直接遍历 `forward.reachableSet` 中当前仍可能作为 crossing arc 右端点的任务，不再把 `1..n` 全部扫一遍；`joinFromBackward()` 也改成先单独处理 source `0`，再遍历 `backward.reachableSet` 中当前可作为 crossing arc 左端点的任务。这样 join 前的端点枚举就和扩展阶段维护的可达集合保持了一致，不再做无意义的全集扫描。

这个改动并没有去掉任何真正的可行 join，因为 `reachableSet` 本来就是当前 label 在禁弧和时间可行性过滤后的候选端点集合；这里只是把原来“先扫全集，再在里面跳过不可能端点”的写法，改成了“直接只扫当前可达端点”。因此它属于常数级优化，不改变最终生成列的语义。

回归验证继续通过。重新编译 `GCBidirectional.java` 后，`HEU.PricingAlgorithmComparisonTest` 仍保持 12/12 轮单向 exact 与双向 exact 的最优 reduced cost 完全一致；`HEU.SmallBPCBatchTest` 也继续保持 8/8 个小例和 tariff 分支诊断例全部通过。这一步的意义主要是继续压缩双向 join 的无效候选扫描，而不是改变算法能力边界。

## 2026-05-22：修正 crossing arc join 漏扣 `arcDual(i,r)` 的 fixed reduced-cost 项

在继续看 `tryJoin()` 的时候，发现这里虽然已经把 forward frontier、backward projection 和 crossing arc 的 `setupCost(i,r)` 加了进去，但还漏掉了连接弧 `(i,r)` 在 RMP 中对应的聚合 arc dual。forward 扩展时固定项一直是 `setupCost(i,j) - jobDual(j) - arcDual(i,j)`；backward 扩展时固定项也是 `-jobDual(i) - arcDual(i,successor)`。因此到了 join 这一步，crossing arc 本身的固定 reduced-cost 项也必须是 `setupCost(i,r) - arcDual(i,r)`，而不能只加 setup cost。

如果这里少扣这一次 `arcDual(i,r)`，函数级 join 下界就会系统性偏高。它未必会直接生成错误列，因为最终真正加列前还会走一次 `TWETColumnEvaluator + reducedCost(sequence, cost, lp)` 做完整复核；但它有可能把某些本来应该被识别为负 reduced-cost 的 join 候选提前挡掉，特别是在 `arcDual(i,r)` 为正时，会出现“真实列是负的，但 join 下界被抬到非负”的漏列风险。因此这一步不是单纯的常数优化，而是一个 join reduced-cost 口径修正。

实现上只改了一行固定项：`joinCost.shiftYInPlace(...)` 现在从原来的只加 `setupCost(i,r)`，改成加 `setupCost(i,r) - lp.getArcDual(i,r)`，并在代码旁边补了中文注释说明原因。其它 join、dominance、`T^mid` 和 evaluator 复核流程都不变。

回归验证没有被破坏。重新编译 `GCBidirectional.java` 后，`HEU.PricingAlgorithmComparisonTest` 仍保持 12/12 轮单向 exact 与双向 exact 的最优 reduced cost 完全一致；`HEU.SmallBPCBatchTest` 仍保持 8/8 个小例和 tariff 分支诊断例全部通过。说明这次修正至少没有引入行为偏差，同时把 join 下界和完整 reduced-cost 定义重新对齐了。

## 2026-05-22：给双向 join 加一层标量乐观下界预剪枝

在前面的两轮优化之后，`tryJoin()` 的热点已经比较集中：先裁 forward frontier，再构造 backward projection，再做 `add + findMinimal`。这一套是必要的精确函数判断，但并不是每个 forward/backward label 对都值得走到这一步，因为很多 pair 在更粗的层面就不可能给出负 reduced cost。

这次补了一层非常便宜的 join 前置下界。`ForwardLabel` 和 `BackwardLabel` 本身都已经缓存了各自 frontier 在当前定义域上的最小值 `minReducedCost`。而对任意一个 crossing arc `(i,r)` 来说，真正 join 函数的最小值一定不小于
\[
    \min f_i + \min f_r^b + \kappa_{ir} - \alpha_{ir},
\]
也就是 forward frontier 最小值、backward frontier 最小值，再加上 crossing arc 的固定 reduced-cost 项 `setupCost(i,r) - arcDual(i,r)`。原因很直接：后面的 `cropToInterval()` 只会缩小定义域，`buildJoinBackwardProjection()` 只会把 backward frontier 取到 `T^mid` 或更靠右的位置，这两步都不可能把函数最小值压得比原 frontier 的全局最小值更低。因此这确实是一个安全的乐观下界。

实现上，`tryJoin()` 现在在做任何函数裁剪和投影之前，先计算一次 `optimisticJoinLB`。如果这个下界都已经不可能为负，就直接返回，不再进入后面的 `crop + projection + add + findMinimal`。这样做不会改变生成列语义，只是把一批显然不可能成列的 pair 提前挡在了最便宜的标量检查层。

回归验证继续通过。重新编译 `GCBidirectional.java` 后，`HEU.PricingAlgorithmComparisonTest` 仍保持 12/12 轮单向 exact 与双向 exact 的最优 reduced cost 完全一致；该组小例上的双向平均时间约为 `1.67 ms/round`。`HEU.SmallBPCBatchTest` 继续保持 8/8 个小例和 tariff 分支诊断例全部通过。说明这层 join 前置下界剪枝在当前实现里是安全的，而且能继续减少一部分无效的函数级 join 计算。

## 2026-05-22：修正双向 backward 半域的物理裁剪方式

这轮重新检查 `GCBidirectional` 后，确认上一版双向 exact pricing 在 backward 半域上还有一个不够严谨的点。旧实现会在 `extendBackward()` 里直接把 frontier 物理裁成 `[lower, rho]`，但 `buildJoinBackwardProjection()` 又默认可以在 `T^mid` 取 `backward.evaluate(T^mid)`。当 `lower > T^mid` 时，这两个假设是冲突的：一方面 `[T^mid, lower)` 这段已经被裁掉，另一方面 join 又想把 `T^mid` 当成 backward 常数延拓的锚点。

正确处理不是给 forward 左侧补 `big_M`，也不是给 backward 右侧补 `big_M`。按单向 pricing 和启发式函数操作的思路，不可行窗口应优先写在“新增 job 的 penalty 函数”里，也就是通过 `setDomain(hStart,hEnd,true)` 把窗口外变成 `big_M`；label frontier 本身只保留当前方向真正需要的半域。forward 扩展后仍按原来的方式 `normalize(FORWARD)`，再裁成 `[ell,T^mid]`。这里的左端 `ell` 是当前前缀最早可行完成时间，左侧不属于该 label 的可行域，不应该额外补回。

backward 端的关键改动是把裁剪顺序改成先裁 `[T^mid,rho]`，再执行 `normalize(BACKWARD)`。这样如果当前新增 job 的动态窗口左端 `lower` 大于 `T^mid`，那么 `[T^mid,lower)` 在裁剪后仍然存在，只是由于 job penalty 的窗口外语义暂时是 `big_M`；随后 suffix-min 会把这段自然压成 `lower` 处的常数值。也就是说，backward 左侧常数延拓由 `normalize(BACKWARD)` 产生，而不是通过额外补段硬造出来。右侧超过 `rho` 的部分不再保留，因为它已经不是当前 backward label 的可行半域。

这次同步撤掉了前一版错误加入的 `padToIntervalWithBigM`、`feasibleStart/feasibleEnd` 和对应扫描有限段的逻辑。现在 forward 仍使用 `head.start` 表示当前前缀最早可行点，backward 仍使用 `tail.end` 表示当前后缀最晚可行点，和半域物理保存方式一致。回归上重新跑了 `HEU.PricingAlgorithmComparisonTest` 和 `HEU.SmallBPCBatchTest`。结果仍然是单向 exact 与双向 exact 12/12 轮最优 reduced cost 一致，小规模整树 8/8 对拍通过，tariff 分支诊断例也继续通过。

## 2026-05-22：双向 exact pricing 后续实现计划

这次重新对照 `twet_outsourcing_models_revised_v42.tex`、当前单向 `GC`、当前 `GCBidirectional` 以及旧 VRP `GCNGBB` 后，先明确后续实现边界：不再采用“固定半域补 big_M”的方案。论文里的半域定义是 label 的存储域，forward label 存 `[ell,T^mid]`，backward label 存 `[T^mid,rho]`；不可行窗口应写进新增 job 的 penalty 函数，即通过 `setDomain(hStart,hEnd,true)` 把窗口外变成 `big_M`，而不是在 label frontier 外侧额外补一段。join 时才使用常数延拓：forward 在 `T^mid` 右侧取 `f(T^mid)`，backward 在 `T^mid` 左侧取 `f_b(T^mid)`。这些延拓只服务 crossing arc join，不写回 label。

下一步实现应按四层推进。第一层是把现有 `GCBidirectional` 的代码语义清理干净：保留已经改回的 `[ell,T^mid]` 和 `[T^mid,rho]` 存储方式，删除或修正仍然暗示“固定半域补边”的旧注释，确保 `head.start` 和 `tail.end` 分别就是 forward 的 `ell` 与 backward 的 `rho`。第二层是复核扩展公式：forward 继续沿用单向 pricing 的 `shiftX(delay) + add(dynamicPenalty) + fixedReducedCost + normalize(FORWARD) + crop[ell,Tmid]`；backward 则固定为先算 `rho'`，再构造当前 job penalty 与后缀函数的组合，随后裁 `[Tmid,rho']`，再 `normalize(BACKWARD)`。如果 `hStart > Tmid`，`[Tmid,hStart)` 不物理删除，交给 job penalty 的 `big_M` 和 suffix-min 形成常数闭包。第三层是 join：只在 `t in [ell, min(Tmid,rho-Delta)]` 上拼接，先用 `minF + minB + setupCost - arcDual` 做安全乐观下界，再构造 `f_b(max(Tmid,t+Delta))` 的临时投影，做函数相加和 `findMinimal`，最后仍用 `TWETColumnEvaluator` 对恢复出的完整序列做真实成本复核。第四层是 dominance 和验证：当前先保留同端点、reachable superset、函数完整占优的 pairwise 版本，不在这一轮引入 partial dominance、NG/DSSR 或 SRI cut。

旧 VRP `GCNGBB` 给当前实现的参考主要是外层生命周期，而不是成本公式本身。它的结构是 forward/backward 两侧队列和 table、扩展后入表占优、被支配 label 出队跳过、最后 join 生成列；TWET 应保持这个生命周期。但旧 VRP 是同一中间客户点 join，而 v42 tex 的 TWET 是 crossing arc `(i,r)` join，所以不能照搬 `JoinRoute(lbf,label)` 的同点拼接。旧 VRP 的 bound、NG 和 DSSR 也先不搬，只把流程框架和 label 生命周期对齐。

验证计划分三步。首先做 focused 编译，至少编译 `GCBidirectional.java` 以及直接依赖类。其次跑 `HEU.PricingAlgorithmComparisonTest`，要求单向 exact 与双向 exact 在同一 RMP dual 下最优 reduced cost 一致；这个测试主要防止双向漏列。最后跑 `HEU.SmallBPCBatchTest`，要求小规模 BPC 与 ArcFlow 对拍继续通过，并检查 tariff 分支诊断例不退化。若后续新增专门边界测试，应优先覆盖 `hStart > T^mid` 的 backward 扩展，确认 `evaluate(T^mid)` 在 join 投影中有定义且不是靠错误补边得到。

补充澄清：join 阶段更适合按论文写成“临时扩展两个半域函数”而不是只构造 backward 投影。具体做法是，对仍未被占优、需要参与 join 的 label，forward frontier 在右侧临时补 `[T^mid,CmaxH]` 常数段，常数值为 `f(T^mid)`；backward frontier 在左侧临时补 `[0,T^mid]` 常数段，常数值为 `f_b(T^mid)`。如果原本最右段或最左段已经是相同常数，可以直接延长该段，否则新增一段即可。这个扩展只服务 join 和剪枝，不写回 label，不再做方向化 normalize；最多只做相邻等值段合并。这样既保持 label 存储域是 `[ell,T^mid]` / `[T^mid,rho]`，又能在 join 时把两个函数放到统一时间轴上处理。

关于剪枝，当前 `FunctionLabel.minReducedCost` 已经是 label 自身函数最小值，可以作为 join 前的第一层下界。若本轮 pricing 目标是找所有负 reduced-cost 列，安全剪枝阈值应仍为 0；如果后续把双向 exact 改成“只返回当前最优一条或先找最优再入池”，才可以进一步用当前已知最优 reduced cost 作为阈值剪掉不可能更优的 join。否则用当前最优值剪枝会漏掉虽然不是最优、但仍然为负且本轮本可加入 RMP 的列。
## 2026-05-22：双向 pricing 半域、硬窗和单点语义澄清

本次重新澄清双向 exact pricing 中几个容易混淆的语义，避免后续继续把“新增 job 的硬时间窗”“label 半域保存”和“join 临时延拓”混在一起。

首先，新增 job 的函数是否已经把硬时间窗外写成 `big_M`，答案是已经处理过，但要分两层看。数据预处理层在 `Data.setPenaltyFunctions()` 中已经对每个 `data.penaltyFunction[j]` 调用 `setDomain(hardWindowStart[j], hardWindowEnd[j], true)`，因此启发式和 BPC 默认拿到的 job penalty 已经带有粗硬窗，窗外为 `big_M`，右端仍保留到 `CmaxH/T`。BPC pricing 还会在每轮 RMP dual 给定后，进一步按当前 dual 和前驱/后继计算动态 `H_ij`，再对该轮使用的 job penalty 副本调用 `setDomain(hStart, hEnd, true)`。启发式没有 LP dual，因此只使用预处理粗硬窗；它的首任务 `setDomain(s_0j+p_j, CmaxH)` 是起点完工时间限制，不是动态 `H_ij`。

其次，backward label 的变量语义不是“第一个 job 必须恰好在 t 完成”，而是“第一个 job 完成时间不早于 t 时，后缀还能取得的最小 reduced cost”。因此如果新增 job 的可行完成窗口左端为 `hStart`，且 `hStart > T^mid`，那么在 `[T^mid,hStart)` 上虽然不能真的完成该 job，但允许等到 `hStart` 再完成，所以这段应通过 `normalize(BACKWARD)` 的 suffix-min 变成等于右侧可行段的常数值，而不是被物理删掉。实现上要先保留 backward 半域 `[T^mid,rho]`，把 job penalty 的窗外 `big_M` 留在里面，然后再做 backward normalize；如果先裁成 `[hStart,rho]`，`T^mid` 处就没定义，后续 join 也无法取 `f_b(T^mid)`。

第三，`minReducedCost` 只是 join 前置剪枝需要的 label 函数最小值。严格来说它不必在每次 label 扩展时都立即重算；如果后续改成批量 join，可以在所有未占优 label 准备进入 join 前统一计算并缓存。当前代码在 label 构造时计算，写法简单但可能多做 `findMinimal`。后续如果优化，需要同时检查优先队列排序是否依赖这个值；如果队列仍按该值排序，则插入时仍需要它，否则可以延迟到 join 前。

第四，双向 label 的定义域裁剪只应该表达半域边界，不应该表达硬时间窗。硬时间窗必须只写进新增 job penalty 的 `setDomain(hStart,hEnd,true)`。forward label 保存 `[ell,T^mid]`，backward label 保存 `[T^mid,rho]`。扩展后裁到半域，是因为双向算法只保存一半时间轴，而不是为了把不可行窗口删掉。join 阶段才临时做常数延拓：forward 在右侧 `[T^mid,CmaxH]` 补 `f(T^mid)`，backward 在左侧 `[0,T^mid]` 补 `f_b(T^mid)`；该延拓只服务 join 和剪枝，不写回 label，也不做方向化 normalize。当前 `GCBidirectional` 已经没有“半域外补 big_M”的错误方案，但 join 仍主要用 backward 投影写法，后续应按这里的语义改成正反向都临时常数延拓后再拼接。

第五，分段线性函数里的单点处理不是“代码只能处理 Cmax 处单点”，但当前真正被认可的普通流程主要是右端点/尾部单点。`evaluate(t)` 对内部断点会取左右端值的较小值，对 `tail.end` 有特判；`add()` 和 `trimToDomain()` 的历史注释也主要围绕 Cmax/tail 处被裁成 `[T,T]` 的情况。三参数 `setDomain(start,end,true)` 如果遇到人为粗硬窗退化成 `[d,d]`，不会把内部单点继续传播，而是用 `WINDOW_EPSILON=0.1` 扩成一个很小的正长度区间，再把窗外写成 `big_M`。因此后续 pricing 若真的产生 `[T,T]` 这种零长度 label，仍应在 pricing 层直接收尾或跳过普通 dominance/merge，而不是让普通函数操作继续传播。

当前结论是：单向 pricing 与启发式的“新增 job 函数先带硬窗 big_M，再 shift/add/normalize”的核心思路是对的；双向 pricing 也应沿用这个思路，只额外维护 `[ell,T^mid]` / `[T^mid,rho]` 的半域保存。真正还需要继续改的是双向 join 的实现形式，把现在的投影式 join 清理成论文语义下的正反向临时常数延拓 join，并在代码注释中说明延拓不写回 label。

## 2026-05-22：双向 pricing 后续修改口径收敛

继续讨论后，进一步明确后续修改口径。此前关于 backward 在 `[T^mid,hStart)` 依赖 suffix-min 形成常数的解释，是针对当前“半域内直接保留并 normalize”的实现做出的解释；如果后续按新的 join 方案执行，即 label 扩展阶段只保存严格半域、join 前再对参与拼接的 label 做临时常数延拓，那么这段解释就不再应作为主要实现依据。新的主线应是：扩展阶段尽量和单向 pricing 保持一致，只做 `shift/add(dynamic job penalty)/fixed reduced-cost/normalize`，再裁到当前方向的半域；不在扩展阶段为了 join 去额外制造常数延拓。

`minReducedCost` 也应调整为 join 阶段的临时信息。也就是说，扩展、入队、普通 dominance 阶段先不强制计算每个 label 的函数最小值；等到真正进入 join 扫描时，只对仍未被占优、且确实需要尝试拼接的 label 计算并缓存最小值，用于 `minF + minB + setupCost - arcDual` 的乐观下界剪枝。这样可以避免在大量最终不会参与 join 的中间 label 上重复做 `findMinimal`。

join 阶段按论文语义做临时函数延拓。forward label 原始只保存 `[ell,T^mid]`，join 前临时在 `[T^mid,CmaxH]` 补 `f(T^mid)` 的常数段；backward label 原始只保存 `[T^mid,rho]`，join 前临时在 `[0,T^mid]` 补 `f_b(T^mid)` 的常数段。延拓函数只用于本次 join 和剪枝，不写回 label，不参与后续扩展，也不做方向化 normalize。若边界段本身已经是相同常数，可以直接延长；否则新增常数段即可。后续代码应把当前 `buildJoinBackwardProjection()` 这种单侧投影写法替换成这个正反向对称的临时延拓流程。

因此后续实现目标可以压缩为三点：第一，扩展逻辑继续对齐单向 pricing，新增 job 的动态硬窗只体现在 job penalty 的 `setDomain(hStart,hEnd,true)`；第二，label 保存域只表达双向半域，不额外补半域外 `big_M` 或常数；第三，所有 join 需要的常数延拓和 label 最小值都延迟到 join 阶段处理。

在流程层面，目前没有新的原则性问题。剩下的是实现对齐：把当前双向 join 的单侧 backward projection 改成正反向临时常数延拓；把 `minReducedCost` 从扩展期强制计算改成 join 前按需计算；扩展继续保持和单向 pricing 一致。除此之外，后续如果要把单向的 set dominance/dominance graph 复用到双向，只需要做方向和 label 类型适配，不改变上述主流程。

关于分段线性函数里的“单点”，当前代码语义也需要明确。这里的单点指函数物理定义域退化为一个时间点，例如 `[T,T]` 或内部窗口 `[d,d]`，不是普通分段函数的断点。当前真正允许或处理单点的入口主要有四类：一是两参数 `setDomain(start,end)` 通过 `trimToDomain()` 可能把 tail 段裁成 `[end,end]`，历史上主要是 Cmax/tail 处的闭端点；二是 `add()` 在公共区间计算中使用 `cur <= nxt`，理论上能把两个函数只有一个端点相交的情况作为零长度段传下去；三是 `evaluate(t)` 对内部相邻段断点取左右值较小者，并对 `tail.end` 单独取值；四是三参数 `setDomain(start,end,true)` 遇到人为硬窗退化成 `[d,d]` 时，不传播真单点，而是用 `WINDOW_EPSILON=0.1` 扩成小区间。相反，`mergeMinimum` 的设计前提是不让 `[T,T]` 这种单点 label 进入普通 merge/dominance；如果 pricing 真的生成零长度 label，应在 pricing 层直接收尾或跳过普通流程。

继续追溯三参数 `setDomain(start,end,true)` 的引入原因后，需要强调它的主目的不是处理单点，而是表达 pricing/硬时间窗中的“窗外不可行但右端定义域仍保留到 T”。早期讨论已经明确：两参数 `setDomain(start,end)` 会物理裁剪 segment 链表，如果用于 BPC 的 profitable completion window，会导致 job penalty 和 label 相加后输出定义域右端短于 `T`，破坏 `[a,T]` 契约。因此才新增三参数版本，把窗口外写成 `big_M`，窗口内保留原函数，`tail.end/domainEnd` 不被裁短。单点扩展只是后来针对人为粗硬窗 `[d,d]` 的保护：如果粗窗恰好退化为一个点，不把这个内部点作为零长度 segment 传播，而是放宽成 `d±0.1` 的小区间。按当前建模，真实 pricing 的动态窗口通常来自外包 baseline、dual 和 setup-cost advantage，退化成精确单点不是主路径；即使出现，也应被这个 epsilon 兜底吸收，而不是依赖普通函数操作支持内部单点。

继续复核历史日志和测试后，确认三参数 `setDomain(start,end,true)` 已经同时服务 forward 和 backward，不是只处理右侧窗口外区域。实现上它会在 `actualStart -> keepStart` 写左侧 `big_M`，在 `keepEnd -> actualEnd` 写右侧 `big_M`，中间保留原函数。forward 语义下，后续 `minimizePrefixInPlace()` 会把右侧可等待区间按前缀最优值闭包，而左侧早于窗口的不可行段仍保持 `big_M`；backward 语义下，后续 `minimizeSuffixInPlace()` 会把左侧“可以等到窗口内再完成”的部分闭包成右侧可行最小值，而右侧晚于窗口的不可行段仍保持 `big_M`。已有 `PiecewiseLinearFunctionPropertyTest` 覆盖了这两个方向：forward 检查右侧 `big_M` 被 prefix-min 闭包，backward 检查左侧 `big_M` 被 suffix-min 闭包、右侧仍不可行。

这也解释了为什么启发式之前没有因为粗硬窗出问题：`Data.setPenaltyFunctions()` 已经把粗硬窗写入 `data.penaltyFunction[j]`，而 `Solution` 和 `HeuristicPricingEngine` 的 forward/backward profile 构造在每次 `add(data.penaltyFunction[j])` 后分别调用 prefix/suffix 最小化。因此，对启发式来说，三参数 `setDomain` 给出的窗外 `big_M` 会在方向化闭包里被解释成“前向可等待的右侧闭包”或“反向可等待的左侧闭包”。需要注意的是，如果某段代码只调用三参数 `setDomain` 却不做 prefix/suffix/normalize，那么它只得到“窗外是 `big_M`”的原始窗口函数，并不会自动形成方向化闭包。
## 2026-05-22：右端 `[T,T]` 与 `normalize` 的 M 段处理语义

继续澄清右端单点和 `normalize` 的关系。这里先不考虑内部单点，只看右侧退化成 `[T,T]` 的情况。右端 `[T,T]` 通常来自可行域刚好只剩全局右端，例如平移、相加或物理裁剪后，两个函数的公共定义域只在 `T/CmaxH` 这个点相交。当前代码对这种情况有一些兜底：两参数 `setDomain(start,end)` 通过 `trimToDomain()` 可能把 tail 裁成闭端点；`add()` 的公共区间循环允许 `cur <= nxt`，因此理论上能传下零长度交点；`evaluate(T)` 对 `tail.end` 有特判；prefix/suffix 最小化也已经修过边界真实最小值被误跳过的问题。

但这些兜底不等于普通分段线性函数流程全面支持零长度 label。`mergeMinimum` 的契约仍然是不接收 `[T,T]` 这种单点 label，dominance graph/envelope 也不应依赖内部或尾部零长度段继续传播。如果 pricing 扩展后真实只剩 `[T,T]`，调用层应直接尝试连接终点生成完整列，或者判断无法继续后丢弃；不要再把它放进普通 merge/dominance 队列。这个约束和前面关于单点的结论一致：当前真正认可的是右端闭点兜底，不是通用零长度 segment 代数。

`normalize` 也不是简单地把左右两侧连续 `big_M` 都砍掉。当前已经是方向化语义。`normalize(FORWARD)` 会删除左侧连续 `big_M`，因为它表示前向 label 的不可达起点；但它不会删除右侧连续 `big_M`，而是保留下来交给 `minimizePrefixInPlace()`，这样右侧等待区间可以被前缀最小值闭包，同时保持右端到 `T`。相反，`normalize(BACKWARD)` 会保留左侧 `big_M`，因为 backward 的固定端是左端 `0/domainStart`，这些左侧区间后续可以由 `minimizeSuffixInPlace()` 闭包；它会删除右侧连续 `big_M` 尾段，因为这表示 backward 侧过晚、无法接上的完成时间。

因此后续判断时要区分两件事：`setDomain(..., true)` 负责把窗口外写成左右两侧 `big_M`，但不会自动解释这些 `big_M`；真正把它解释成 forward 等待闭包或 backward 等待闭包的是后续的 `normalize(FORWARD/BACKWARD)` 或直接的 prefix/suffix 最小化。若只调用三参数 `setDomain` 而不做方向化闭包，得到的只是“窗口外为 M、定义域仍保留”的原始窗口函数。

由此可以给 pricing 扩展层一个明确约束：如果扩展过程中 label 的函数定义域退化成右端单点 `[T,T]`，应在扩展层单独处理，也就是直接尝试连接终点并生成完整列，或者确认无法继续后丢弃。只要这个约束成立，后续的合并、完整占优、dominance graph 和 envelope 更新都可以继续假设输入有正长度定义域，不需要再专门考虑 `[T,T]` 单点 label。

这里的 `T` 在代码层最好理解成“该函数对象自己的右端定义域”，也就是正常契约下的 `domainEnd`，并且应同时满足 `tail.end == domainEnd`。对 BPC pricing 和启发式里从 `Data` 构造出来的成本函数，`domainEnd` 通常就是 `data.CmaxH`，所以这两个判断在正常情况下等价；但写扩展层特判时不建议只硬编码比较 `data.CmaxH`，更稳的条件是 `head.start == tail.end && tail.end == domainEnd`，必要时再用 `domainEnd == data.CmaxH` 做调试断言。这样可以避免两参数 `setDomain` 临时物理裁剪后出现 `tail.end < domainEnd` 时，把一个中途裁出来的单点误当成全局右端 `[T,T]`。

边界一致性也要按方向理解，而不是要求四个边界始终都一致。前向 label 的核心契约是右端一致，即正常流程中应保持 `tail.end == domainEnd == T`；左端 `head.start` 可以随着最早可行完成时间、左侧 `big_M` 删除或半域裁剪而大于 `domainStart`。反向 label 的核心契约是左端一致，即正常流程中应保持 `head.start == domainStart`；右端 `tail.end` 可以随着最晚可行完成时间、右侧 `big_M` 删除或半域裁剪而小于 `domainEnd`。只有完整 job penalty 或窗口化 job penalty 这类全域函数，才通常同时满足 `head.start == domainStart` 与 `tail.end == domainEnd`。两参数 `setDomain(start,end)` 是例外入口，它会物理裁剪真实链表，再恢复原元数据，因此可能主动制造 `tail.end != domainEnd` 或 `head.start != domainStart`，不能把这种中间对象当成普通前向/反向 label 契约内对象。
## 2026-05-22：双向 pricing 的 Tmid 单点保留与虚拟终点扩展

本次按前面确认的半域语义继续收敛 `GCBidirectional`。forward label 仍只保存 `[ell,T^mid]`，backward label 仍只保存 `[T^mid,rho]`，不在扩展阶段额外给半域外补 `big_M` 或常数段。新增 job 的硬时间窗仍然只写在 job penalty 上，也就是通过 `setDomain(hStart,hEnd,true)` 表示窗口外不可行，随后再按方向做 `normalize(FORWARD/BACKWARD)`。

这次具体修正了两个边界。第一，backward 从虚拟终点第一次扩展到真实 job 时，当前变量已经是这个 job 自己的完成时间，因此不应该再加 setup time 或 processing time 的平移，只需要扣掉该 job dual 和虚拟终点弧 dual。代码中已经在 `isSinkRoot` 分支旁补了注释，避免后续把它误改成普通 backward 扩展。第二，`cropToInterval()` 现在会保留零长度区间，例如半域刚好退化到 `T^mid` 的 `[T^mid,T^mid]`。这个点不进入普通 merge/dominance 流程，但 join 时需要能取 `T^mid` 处的常数延拓值，否则会把一个合法的边界拼接状态错误删掉。

join 侧也同步补了单点投影处理。`buildJoinBackwardProjection()` 在 `xStart == xEnd` 时不再直接返回空函数，而是根据 `x` 是否落在 `T^mid - Delta` 左侧，分别取 backward 的 `f_b(T^mid)` 常数，或取平移后的 backward 函数在该点的值。这样 forward 或 backward 在半域边界退化成单点时，仍可在 join 阶段被正确评价；该处理只服务 join，不把单点 label 继续放进普通扩展队列。

验证上重新编译 `GCBidirectional.java`、`PricingAlgorithmComparisonTest.java` 和 `SmallBPCBatchTest.java`，使用 CPLEX jar 后编译通过。随后运行 `HEU.PricingAlgorithmComparisonTest`，结果为 12/12 轮单向 exact 与双向 exact 最优 reduced cost 一致，平均时间约为 forward `1.00 ms/round`、bidirectional `0.83 ms/round`；运行 `HEU.SmallBPCBatchTest`，8 个随机小例与 ArcFlow 对拍全部一致，tariff 分支诊断例也通过。当前结论是，这次修改没有改变主流程，只是把 `T^mid` 边界单点和 backward 虚拟终点扩展语义补严。

## 2026-05-23：双向半域裁剪与 `shiftX(trimToDomain)` 的关系

继续复核双向 pricing 半域实现时，确认 `PiecewiseLinearFunction.shiftX()` 本身确实会在水平平移后调用 `trimToDomain()`。因此问题不在于 `shiftX` 不裁剪，而在于它裁剪时只读取当前函数对象的 `domainStart/domainEnd` 元数据；如果半域只是通过物理 segment 被裁到 `[0,T^mid]` 或 `[T^mid,CmaxH]`，但函数元数据仍保持默认或全域，那么 `shiftX()` 并不知道应该按 `T^mid` 裁剪，只能等后续 `add()` 通过公共物理定义域再兜底收窄。

基于这个判断，本次把 `GCBidirectional.cropToInterval()` 调整为在裁剪物理 segment 的同时同步 `domainStart/domainEnd`。这样由 `cropToInterval()` 构造出的 forward 半域函数、backward 半域函数、动态 half-domain job penalty 和 join 临时裁剪函数，在后续 `shiftX()` 中都会按自身半域元数据自然 `trimToDomain()`。这不改变双向 pricing 的数学流程，只是把半域边界从“物理 segment 兜底”提升为“元数据和物理 segment 一致”的明确契约，也减少后续理解上的歧义。

需要注意的是，三参数 `setDomain(start,end,true)` 的作用仍然是把动态硬时间窗外写成 `big_M`，不是直接表达双向半域；双向半域由 `cropToInterval()` 再裁出。`shiftX()` 会裁，但只裁到函数对象自己记录的 domain，所以凡是人为裁半域的辅助函数，都必须同步元数据。

## 2026-05-23：双向初始半域与自然裁剪保证

继续确认双向半域是否可以像单向里的 `CmaxH` 一样作为方向边界使用。当前 forward 侧已经满足这个契约：source label 由 `cropToInterval(data.penaltyFunction[0], 0, T^mid)` 构造，动态 job penalty 也由 `buildForwardHalfPenalty()` 裁成 `[0,T^mid]`，并且 `cropToInterval()` 已同步 `domainStart/domainEnd`。因此 forward 扩展中 `label.frontier.shiftX(delay)` 会先按 `[0,T^mid]` 自动 `trimToDomain()`；之后 `add(jobPenalty)` 的公共定义域仍然不超过 `[0,T^mid]`，不需要再额外做一次半域裁剪。

反向侧逻辑同理，但本次发现虚拟 sink 初始函数虽然物理 segment 是 `[T^mid,CmaxH]`，此前没有同步 domain 元数据。现在已经在构造 `sinkFrontier` 时显式 `resetDomain(T^mid,CmaxH)`。这样 backward 初始 label、backward dynamic job penalty 以及后续扩展得到的函数都会以 `[T^mid,CmaxH]` 作为自身 domain；`shiftX(-delay)` 会自然按反向半域裁剪，`add(jobPenalty)` 也只在该半域公共定义域上操作。

因此当前可以把 `T^mid` 理解成双向 pricing 中的半域版 `CmaxH`：forward 右端边界是 `T^mid`，backward 左端边界是 `T^mid`。这依赖两个条件同时成立：一是初始 label 的元数据和物理 segment 都在半域内；二是每个新增 job penalty 也先裁成同方向半域。只要这两个条件保持，扩展过程中不需要额外的 post-crop 来兜底。

## 2026-05-23：当前 BPC 与双向 pricing 求解流程复核

本次复核当前求解主流程，重点看 BPC 节点处理、pricing 调用顺序和双向 exact pricing 的函数递推。整体流程如下。`Tree.solve()` 先通过 `InitialColumnBuilder` 构造 root 初始列和 incumbent，然后用优先队列按节点 pseudo cost 处理分支节点。每个节点出队后先做 pseudo-cost 预剪枝，再构造 `LP` 并调用 `PC.solve()`。如果 RMP 不可行，`PC` 进入 repair 模式，只给当前新分支行加人工 slack，用当前 slack dual 引导启发式/精确定价补列；repair 成功后按 reduced cost 和当前正值列筛成正式子节点列集。若 RMP 可行，则进入普通 pricing 循环。

普通 pricing 循环现在与旧 VRP 的主节奏一致：按 engine 顺序调用，先启发式 pricing，再 exact pricing；只要某个 engine 加到了列，就立刻加入 LP、重解 LP，并从第一个 engine 重新开始。这样只要启发式还能补负 reduced-cost 列，就不会提前调用更重的 exact pricing。当前 `TWETBPCContext` 默认启用 `HeuristicPricingEngine` 加 `BidirectionalPricingEngine`，单向 exact 和 paper-dominance forward exact 是关闭双向后才二选一使用的备选路径。

双向 exact pricing 当前流程为：每轮 pricing 先固定 RMP dual 并预计算动态硬时间窗。若 setup cost 满足三角不等式，则 forward/backward 均用 job 级动态窗口缓存；否则用 pair 级窗口缓存。forward 初始 label 在 `[0,T^mid]` 上，backward sink 初始 label 在 `[T^mid,CmaxH]` 上；新增 job penalty 也先通过 `setDomain(hStart,hEnd,true)` 写入硬窗，再由 `cropToInterval()` 裁成对应半域并同步 domain 元数据。因此扩展时 `shiftX()` 会按自身半域自然 `trimToDomain()`，不需要额外 post-crop。forward 扩展为 `shiftX(setup+p)+add(jobPenalty)+fixed reduced-cost+normalize(FORWARD)`；backward 扩展对 sink root 特判为不加 setup/processing 平移，其余为 `shiftX(-(setup+p_successor))+add(jobPenalty)+fixed reduced-cost+normalize(BACKWARD)`。

join 阶段采用 crossing arc `(i,r)`，不是旧 VRP 的同点 join。`joinFromForward` 和 `joinFromBackward` 都通过当前 label 的 `reachableSet` 找另一侧 table，从而避免全量枚举全部节点。`tryJoin()` 先检查 visited set 不相交，再用 `forward.minReducedCost + backward.minReducedCost + setupCost(i,r) - arcDual(i,r)` 做乐观下界剪枝；通过后才构造 forward 裁剪段和 backward 投影函数，做函数级 `add + findMinimal` 判断。真正加列前仍用 `TWETColumnEvaluator` 对完整序列重新计算真实成本，再用真实 reduced cost 判定是否加入列池，这一步是安全兜底。

当前看起来没有明显会破坏正确性的流程漏洞。已修正的一个表述问题是 `Tree` 的最终结果 message 原来仍写着 forward exact pricing，现在改成 configured pricing engines，避免默认双向 pricing 时输出误导。需要注意的是，`maxExactPricingColumns=150` 仍是单轮返回列上限；这不会直接破坏列生成正确性，因为 PC 会加列后重解并重新调用 pricing，但会影响每轮返回列数量和总迭代次数。若以后要严格诊断“是否 exact pricing 被列数上限截断”，可以增加统计字段，而不是把它当作当前错误。

可以精简或提高效率的点主要有四类。第一，`FunctionLabel` 构造时立即调用 `frontier.findMinimal()` 计算 `minReducedCost`，这个值用于 PQ 排序、big_M 过滤和 join 乐观下界，因此当前不是纯多余；但它确实会让每个中间 label 都做一次函数最小值计算。若后续 profiling 显示这里是瓶颈，可以改成 lazy min 或用更便宜的排序 key，但要重新确认队列顺序和剪枝安全性。第二，`buildForwardReachableSet/buildBackwardReachableSet` 每生成一个 label 都扫描 `1..n`，复杂度是每 label `O(n)`；这比预处理 reach bitset 更简单稳健，但在大规模 pricing 中可能是瓶颈。之前讨论过旧 VRP reach 预处理，但由于 TWET 动态硬窗依赖 dual 和 pair/job 窗口，直接搬过来收益不一定大。第三，`tryJoin()` 构造 backward projection 时仍会创建临时函数并调用 `shiftX/crop/add/findMinimal`；这是当前最重的函数级 join 成本，已经有标量下界剪枝，但后续可以写专门的 shifted-sum-min 扫描器减少临时函数对象。第四，最终加列前 `tryGenerateColumn()` 会恢复完整 sequence、重新 evaluator 计算真实成本并再扫一遍 reduced cost；这一步保证安全，短期不建议删，除非以后能证明双向函数 join 的 reduced cost 和 evaluator 完全一致并有充分对拍。

本次没有发现必须立刻重构的地方。优先级建议为：短期保持当前流程，继续用小规模对拍和中规模 profiling 验证；若要优化，先统计 pricing 中 label 数、dominance 删除数、reachable 扫描次数、join 尝试次数、join 下界剪枝次数、函数级 join 耗时，再决定是优化 `findMinimal`、reachable 构造还是 join 临时函数。没有统计前不建议继续大改流程。

## 2026-05-23：双向 labeling 内部流程复核与 join 投影修正

本次按扩展、占优、dominance graph、join、函数半域和截断逐项复核 `GCBidirectional`。当前 forward 扩展已经基本符合单向递推的半域版本：source 和动态 job penalty 都被裁到 `[0,T^mid]`，且 `cropToInterval()` 会同步函数 `domainStart/domainEnd`，因此 `shiftX(delay)` 后会自然按 `T^mid` 裁剪，不需要额外 post-crop。backward 侧也已经通过 sink 初始函数和动态 job penalty 的 `[T^mid,CmaxH]` 元数据实现对称半域，sink root 第一次扩展不加 setup/processing 平移也是正确的。

发现的主要问题在 join 投影：`buildJoinBackwardProjection()` 原来直接调用 `backward.shiftX(-delta)`，但 `shiftX()` 会按 backward 原始半域 `[T^mid,CmaxH]` 做 `trimToDomain()`。join 投影实际需要的是 `f_b(x+delta)`，其 shifted 后的定义域应为 `[T^mid-delta,CmaxH-delta]`；如果继续按原 `[T^mid,CmaxH]` 裁剪，就可能误删 `x<T^mid` 但满足 `x+delta>=T^mid` 的合法投影段。已改成专门的 `shiftBackwardForJoinProjection()`：先复制 backward 函数，把元数据同步左移，再手动平移 segment。这个修改只影响 join 的临时投影，不写回 label。

当前双向实现仍有两类效率差异。第一，双向版本的 `insertForward/insertBackward` 仍是同一末端 table 内的线性扫描完整占优，没有接入 `PaperDominanceGraph`；这不破坏正确性，但和论文式 dominance graph 的高效流程还不一致。第二，扩展阶段存在一些保守的重复判断，例如 reachableSet 已经做过 direct time feasibility 过滤，`extendForward/extendBackward` 里仍会再检查一次；这属于安全冗余，不是错误。后续如果优化，应优先考虑给双向 label table 接入方向兼容的 dominance graph，而不是先删安全检查。


## 2026-05-23：双向 pricing 的 join 候选枚举与 dominance graph 复用修正

继续按前面确认的 v42 双向 pricing 语义整理 `GCBidirectional`。本次最重要的修正是 join 候选的枚举方式。之前一度把 `reachableSet` 直接用于 join 的另一侧 terminal 扫描，这个理解不严谨：在双向 bounded labeling 中，forward label 的 `reachableSet` 表示“还能在 `[0,T^mid]` 左半域继续扩展”的任务集合；但 crossing arc `(i,r)` 的右端 `r` 本来就可以落在右半域，由 backward label 表达。因此 join 候选不能只从 forward 的半域扩展集合里取，否则会漏掉“从前缀跨过 `T^mid` 后才接上后缀”的合法拼接。backward 侧同理，`backward.reachableSet` 是继续向左扩展的候选，不等价于所有可与该后缀拼接的 forward terminal。

现在 `joinFromForward()` 改成按 real job terminal 扫描 `BWTL[j]`，先过滤当前 forward 已访问任务和禁止弧，再把对应 backward graph 中仍 active 的真实 label 取出来交给 `tryJoin()`。`joinFromBackward()` 对称地扫描 `FWTL[j]`，其中 `j=0` 表示 source label。真正的完整检查仍集中在 `tryJoin()`：先看 visited set 是否相交，再用 `forward.minReducedCost + backward.minReducedCost + setupCost(i,r) - arcDual(i,r)` 做乐观下界；只有下界仍可能为负时，才临时构造 forward 右半域常数延拓和 backward 左半域常数延拓，按 crossing arc 的时间位移对齐后做函数相加与 `findMinimal()`。

join 的函数处理也同步收敛到前面讨论的实现方式：扩展阶段不再给 label 半域外补任何段；join 阶段才临时把 forward 的 `[T^mid,CmaxH]` 补成 `f(T^mid)`，把 backward 的 `[0,T^mid]` 补成 `f_b(T^mid)`。这些延拓只用于当前 join，不写回 label，也不再做方向化 normalize。为了处理边界单点和半域端点被前序 normalize 改写的情况，临时延拓取值使用 `valueAtOrNearest()`：正常情况下取 `T^mid` 处的值，若边界点因为零长段或裁剪元数据不完全落在函数物理定义域内，则退回最近端点值，避免因为 `evaluate(T^mid)` 落在定义域外而返回伪上界。

占优结构方面，双向两侧现在都复用 `PaperDominanceGraph`，但给 graph 增加了方向参数。forward graph 的 envelope 合并使用 `Direction.FORWARD`，backward graph 的 envelope 合并使用 `Direction.BACKWARD`，避免后向 label 在 set dominance envelope 中仍按 forward prefix-min 语义合并。`DominanceStore` 增加 `getActiveLabels()`，只是为了让 join 阶段从 dominance graph 中枚举尚未被支配的真实 label；它不是额外的 wrapper，也不改变 graph 负责插入、传播和占优判断的职责。

验证结果：focused GC 编译通过，命令为 `javac -encoding UTF-8 -cp src -implicit:none src/TWETBPC/GC/GCBidirectional.java src/TWETBPC/GC/GC.java src/TWETBPC/GC/DominanceStore.java src/TWETBPC/GC/DominanceGraph.java src/TWETBPC/GC/DominanceNode.java src/TWETBPC/GC/PaperDominanceGraph.java src/TWETBPC/GC/Label.java`。`PaperDominanceGraphConsistencyTest` 通过，结果为 `cases=200, insertions=16000`。尝试编译 `HEU.PricingAlgorithmComparisonTest` 时，本机 classpath 缺少 CPLEX 的 `ilog.concert/ilog.cplex` 依赖，因此未能运行单向/双向 pricing 对拍；后续如果要完整对拍，需要带上 CPLEX jar 或使用已有 IDE 配置运行。
## 2026-05-23：双向 pricing 当前一致性复核结论

按当前代码再次复核后，结论是双向 pricing 的主框架已经和旧 VRP 的双向 labeling 生命周期基本一致：都有 forward/backward 两侧未处理队列，按 label 出队扩展；每个 terminal job 维护一侧已处理 label table；新 label 进入 terminal table 时做 set dominance；两侧 label 通过 join 生成完整列；最后恢复真实序列并交给列池/RMP。TWET 的差异主要是问题细节而不是框架差异：旧 VRP 是同点 join，TWET 按 v42 文档使用 crossing arc `(i,r)` join；旧 VRP label 成本是标量资源/费用，TWET label frontier 是分段线性 reduced-cost 函数；旧 VRP 的可行性主要来自时间窗/容量，TWET 还要处理动态 H 窗口、setup time/cost、arc dual 和函数半域。

当前实现也基本符合前面确认的论文语义和实现口径。forward label 只保留 `[0,T^mid]` 半域，backward label 只保留 `[T^mid,CmaxH]` 半域；新增 job 的动态硬窗通过 `setDomain(hStart,hEnd,true)` 写到 job penalty 上，然后再裁成对应半域。扩展阶段不额外给 label 半域外补段，join 阶段才临时把 forward 的右侧补为 `f(T^mid)` 常数，把 backward 的左侧补为 `f_b(T^mid)` 常数，这些临时函数不写回 label。join 前先检查 visited set 冲突、禁止弧和乐观下界，再做函数级 `shift/add/findMinimal`，最后仍用 `TWETColumnEvaluator` 对恢复出的真实序列复核真实 reduced cost。这个安全复核略重，但当前有助于避免双向函数拼接细节出错时把伪负列加入 RMP。

当前最需要继续注意的是 dominance key 与 join 候选的理论边界。现在 dominance graph 的 node key 仍是“半域内继续扩展 reachableSet”，而 join 候选已经不再只依赖这个 set，而是按另一侧 terminal graph 扫描 active label。这避免了漏 join，但也意味着 set dominance 的严格证明需要依赖一个判断：如果一个 label 在同 terminal 下 frontier 被另一个 reachableSet 超集 label 完整支配，那么它对后续半域扩展和最终 crossing-arc join 都不会更优。这个判断在当前完整函数占优和真实序列复核下很可能成立，但后续如果引入 partial dominance、ng/DSSR 或更强剪枝，应重新检查 dominance key 是否还要加入 join-reachable 信息。短期不建议继续改逻辑，应先用单向 exact 对拍更多实例来确认。

效率上还有几个明确但非立即风险的点。第一，`joinFromForward/joinFromBackward` 现在按 terminal job 扫描另一侧 graph 的 active labels，比直接用半域 `reachableSet` 更稳，但可能更慢；后续可以维护单独的 join-reachable 候选集来减少扫描。第二，`getActiveLabels()` 每次会新建列表，join 多时会产生额外分配，可改成 `forEachActiveLabel`。第三，join 阶段为 forward/backward 常数延拓和 shifted 函数创建临时分段函数，后续若 profiling 显示这里是瓶颈，可以写专用 shifted-sum-min 扫描器。第四，label 构造时仍计算 `minReducedCost`，它目前用于队列排序、big_M 过滤和 join 乐观下界，不是纯冗余；如果以后要延迟计算，需要同时重写这些用途。

本次复核没有发现必须立刻修的明确错误。验证层面，focused GC 编译和 `PaperDominanceGraphConsistencyTest` 已通过；当前会话没有完成单向/双向 pricing 对拍，因为命令行 classpath 缺 CPLEX `ilog.*` 依赖。后续若要把“双向完全可替代单向”作为结论，应优先在 IDE 或带 CPLEX jar 的命令行下跑 `PricingAlgorithmComparisonTest` 和若干 BPC 小例。

## 2026-05-23：双向 pricing join 阶段低风险性能优化

本次只做不改变数学语义的性能优化。第一，`DominanceStore` 增加 `collectActiveLabels(buffer)`，`GCBidirectional` 在 join 枚举时复用一个 `joinCandidateBuffer`，不再每扫描一个 terminal graph 就新建一个 `ArrayList`。原有 `getActiveLabels()` 保留给测试和普通调用，但内部也复用 `collectActiveLabels()`，避免两套逻辑分叉。这个修改主要降低 join 高频枚举时的小对象分配。

第二，forward/backward label 新增 `joinExtendedFrontier` 缓存。join 阶段需要临时把 forward 右侧延拓为 `f(T^mid)`、把 backward 左侧延拓为 `f_b(T^mid)`。同一个 label 可能会和多个对侧 label 尝试拼接，原来每次 `tryJoin()` 都重新构造延拓函数；现在第一次 join 时构造，后续复用。由于 label 的 `frontier` 创建后不再修改，且 `shiftX/add` 都返回新函数，不会写回缓存对象，所以这个缓存不会改变结果。

第三，去掉 `buildBackwardReachableSet()` 里的 fake `BackwardLabel`。原来为了复用 `isDirectBackwardExtensionTimeFeasible()` 临时 new 一个 `BackwardLabel`，但 `BackwardLabel` 构造会计算 `frontier.findMinimal()`；这里实际上只需要 `firstJob/isSinkRoot/frontier` 做时间窗判断。现在改成传这三个值的轻量重载，避免每次构造 backward reachable set 时做一次无意义的函数最小值计算。

验证结果：focused GC 编译通过；`PaperDominanceGraphConsistencyTest` 继续通过，`cases=200, insertions=16000`。使用 `D:\软件\cplex\ILOG\CPLEX_Studio2211` 的 jar/native 路径运行 `PricingAlgorithmComparisonTest`，13/13 轮单向 exact 与双向 exact 最优 reduced cost 一致，平均时间约 forward `1.54 ms/round`、bidirectional `1.08 ms/round`。运行 `SmallBPCBatchTest`，8/8 个小例与 ArcFlow 对拍一致，tariff 分支诊断例也通过。当前结论是这轮优化没有改变结果，且减少了双向 join 和 backward reachable 构造中的可见重复开销。

## 2026-05-23：双向 pricing 剩余安全冗余与检测逻辑

当前剩余的安全冗余主要有三类。第一类是最终真实列复核：`tryJoin()` 通过函数级 `findMinimal()` 判断可能存在负 reduced-cost 后，并不直接把 join 函数值作为列成本，而是恢复完整 sequence，调用 `TWETColumnEvaluator.evaluate()` 重算真实成本，再重新扣 machine/job/arc dual 计算真实 reduced cost。这一步会重复一部分函数评价和 dual 扫描，但它是当前最重要的安全兜底，能防止 crossing-arc join、半域常数延拓或边界点处理有细微误差时把伪负列加入 RMP。短期不建议删除。

第二类是分支兼容性复核。join 时已经过滤了 crossing arc 是否 forbidden，扩展时也过滤了局部 forbidden arc，但 `tryGenerateColumn()` 仍会对恢复出的完整 sequence 调 `isSequenceCompatible()`，从起点弧、中间弧到终点弧全部检查一遍。正常情况下这大概率重复，但它覆盖了路径恢复、father 链和分支状态边界；相对函数 join 成本，这个 O(k) 检查不是主要瓶颈，也建议保留。

第三类是扩展前的 direct time feasibility 与扩展后的函数空/`big_M` 判断。`reachableSet` 构造时已经通过 `isDirectForwardExtensionTimeFeasible()` / `isDirectBackwardExtensionTimeFeasible()` 做一跳时间窗过滤，真正扩展后仍然要检查 `shift/add/normalize` 是否为空，以及 `minReducedCost` 是否为 `big_M`。这是两层不同语义：前者只是便宜的 O(1) 时间窗预剪枝，后者才确认分段函数公共定义域、动态硬窗、半域裁剪和方向化闭包后的真实可行性。因此这不是可以简单合并删除的重复。

用于检测的逻辑主要有两套。运行期分段函数契约检查由 `Configure.debugPWLFDomainCheck` 控制，默认关闭；打开后，`PiecewiseLinearFunction` 的 `setDomain/shiftX/add/normalize/mergeMinimum/updateDominatedIntervals` 等操作会调用 `Utility.debugCheckPWLFRightBound/LeftBound/MergeContract`，检查前向右端、反向左端和 merge 输入契约。这些检查不应在性能测试中打开。另一套是独立回归测试：`PaperDominanceGraphConsistencyTest` 比较论文式 dominance graph 与基准 dominance graph 的支配结果；`PricingAlgorithmComparisonTest` 用单向 exact 与双向 exact 对拍最优 reduced cost；`SmallBPCBatchTest` 用小规模 ArcFlow 与 BPC 对拍。当前这些检测仍是必要的回归保障，不属于生产路径开销。

后续用户进一步确认后，本次把前两类安全复核做成和分段线性函数契约检查类似的调试开关。`Configure.debugBPCPricingColumnCheck` 默认关闭，正式运行时 `GCBidirectional` 不再对每条候选列额外调用 `TWETColumnEvaluator.evaluate()`，也不再对恢复出的完整 sequence 全量重扫 forbidden arc；列的真实目标值由双向 label/join 已经得到的 reduced cost 反推出，即 `cost = reducedCost + machineDual + jobDuals + arcDuals`。这个反推只是在 reduced-cost 口径和 RMP dual 口径之间换算，不改变列本身。调试时把该开关设为 `true`，则恢复完整 sequence 兼容性检查和 evaluator 成本复核，并把 evaluator 重新算出的 reduced cost 与双向推导值比较，用来验证 join、半域常数延拓和路径恢复没有偏差。

这样处理后的含义是：生产路径信任已经对拍过的双向 pricing 递推，减少最终加列前的重复计算；debug 路径保留安全兜底，后续如果改 join、动态硬窗或 dominance graph，可以随时打开该开关做测试。扩展前 direct time filter 与扩展后函数可行性判断仍然保留，因为它们不是同一层语义：前者是便宜的一跳时间剪枝，后者确认分段函数公共定义域、动态窗口和方向化闭包后的真实可行性。

## 2026-05-23：单向与双向 exact pricing 批量速度对拍

本次把 `PricingAlgorithmComparisonTest` 改成可通过 system property 调整测试规模，便于批量比较单向 paper-dominance exact pricing 和双向 exact pricing。新增参数包括 `twet.pricing.compare.cases`、`seedBase`、`baseN`、`nStep`、`machines` 和输出 `csv` 文件名；这些只影响测试入口，不改变正式 BPC 求解逻辑。

测试分四批进行，分别覆盖 5-12、10-18、20-30 和单个 40 任务随机算例。所有 72 个 pricing 轮次中，单向与双向返回的最优 reduced cost 全部一致。整体平均耗时为：单向 23.64 ms/轮，双向 6.75 ms/轮，双向约快 3.5 倍；只看存在负 reduced-cost 列的 55 个轮次，单向 29.95 ms/轮，双向 7.55 ms/轮，双向约快 4.0 倍。各批次结果如下：5-12 任务为 3.35 vs 0.32 ms/轮，10-18 任务为 9.64 vs 1.73 ms/轮，20-30 任务为 34.15 vs 7.69 ms/轮，40 任务为 157.00 vs 56.33 ms/轮。

当前双向更快的主要原因有三点。第一，双向只在 `[0,T^mid]` 和 `[T^mid,CmaxH]` 半域上扩展 label，单次分段函数操作的定义域更短，很多路径不会被完整前向展开到全时域。第二，join 前有 `forward.minReducedCost + backward.minReducedCost + setupCost(i,r) - arcDual(i,r)` 的乐观下界剪枝，只有下界仍可能为负时才进入函数级 join。第三，前一轮已经把最终 `TWETColumnEvaluator` 复核和完整 sequence 分支兼容性复核放到 debug 开关下，正式对拍时双向直接使用 label/join 推导出的 reduced cost 反推列成本，减少了最终加列前的重复计算。40 任务批次中双向优势缩小，主要是后期负列很少或无负列时，join 扫描、active label 收集和临时函数构造的固定开销占比变高；这说明后续如果继续优化，重点仍是 join 候选缩小和函数级 join 对象分配，而不是回退到单向。

## 2026-05-23：reachableSet 置位遍历优化尝试与回退结论

本次继续检查“不大改”的加速空间，尝试把 `GCBidirectional` 的 forward/backward 扩展枚举从 `for job=1..n` 后再查 `reachableSet.contains(job)`，改成直接用 `reachableSet.nextSetBit()` 遍历已经置位的候选任务。这个想法看起来合理，因为 `reachableSet` 已经做过 visited、禁弧和一跳硬时间窗过滤，理论上可以减少扩展阶段对不可达 job 的空扫描。

实际对拍结果不稳定，因此没有保留代码修改。10-18 任务同一组随机例中，双向平均耗时从原来的约 1.73 ms/轮变成 2.59 ms/轮；20-30 任务从原来的约 7.69 ms/轮变成 10.31 ms/轮；40 任务单例则从原来的约 56.33 ms/轮降到 37.83 ms/轮。所有测试中单向/双向最优 reduced cost 仍一致，说明正确性没有问题，但性能收益只在较大规模下显现，中小规模反而因为 `nextSetBit()` 调用和循环条件复杂度变慢。

因此当前结论是：不把扩展循环改成置位遍历，继续保留简单的 `1..n` 扫描。原因是这段不是当前稳定瓶颈，而且小规模/中规模 pricing 在 BPC 中仍会大量出现，不能为了一个 40 任务单例收益引入不稳定性能变化。后续如果要重新考虑这个方向，应先加入 label 数、reachableSet 密度、扩展尝试次数和 join 尝试次数统计，再根据 reachableSet 稀疏度自适应选择扫描方式，而不是固定替换。

## 2026-05-23：BPC 分段耗时统计口径

本次给 BPC trace 增加了分段耗时记录，目的是把“完整 root 用时”拆开，避免继续只看总秒数推断单向/双向 exact pricing 的效果。当前计时只写入 `BPCTraceSink`，不参与列选择、剪枝、对偶更新或分支逻辑，因此不会改变求解结果。

现在记录三类时间。第一类是 RMP/LP 求解时间，按 `initial`、`after_column_filter`、`after_pricing`、`after_cut`、`repair_*` 等 phase 累计，并同时记录调用次数和平均耗时。第二类是 pricing engine 时间，每次调用 `HeuristicPricing`、`BidirectionalPricing` 或 `PaperDominanceExactPricing` 时单独计时，summary 中按 engine 汇总总时间、调用次数和平均时间，verbose 输出中每轮 pricing 也会直接带 `time=... ms`。第三类是 cut generator 时间，目前主要用于确认 `NoOpCutGenerator` 和 `SubsetRowCutGenerator` 的占比接近 0。

用 20 任务 no-outsourcing 子算例 `tmp-wet020_001_2m.dat` 复核后，双向版本在本次新口径下的结果为：root 闭合，目标/下界均为 6343，pricing 共 14 轮、列池 1604；`HeuristicPricing` 调用 13 次，累计约 1.467s，平均约 112.8ms；`BidirectionalPricing` 调用 1 次，约 7.995s；RMP/LP 的 `initial` 约 0.105s，12 次 `after_pricing` 合计约 0.024s；cut 合计可忽略。单向版本同例结果也闭合到 6343，`HeuristicPricing` 调用 13 次累计约 1.619s，`PaperDominanceExactPricing` 调用 1 次约 8.513s，RMP/LP 合计同样只有 0.1s 量级。

这个结果说明，20 任务 no-outsourcing 的完整 root 总时间差异主要来自最后一次 exact pricing 兜底证明和启发式 pricing 的波动；RMP 重解不是瓶颈。双向 exact 在这次复核中比单向 exact 少约 0.52s，但因为只调用一次，完整 root 总时间仍会受 JVM/JIT、控制台输出和启发式 pricing 波动影响。后续比较算法改动时，应优先看 summary 中的 `pricing time` 分项，而不是只看 runner 最后一行的总 `ms`。

进一步解释这个结果时，需要承认一个事实：对这个 20 任务 no-outsourcing 例子而言，双向 exact pricing 的边际收益确实不大。它只在最后一轮证明“没有负 reduced-cost 列”时比单向少了约 0.52s，而完整 root 总时间没有明显下降。因此这个例子不能作为双向算法显著加速的证据，只能说明双向版本在当前实现下正确、略快，但优势被启发式加列、RMP 重解、列池维护和一次性 exact 兜底的结构稀释了。

这并不等价于“双向永远没用”。前面的 `PricingAlgorithmComparisonTest` 在直接对拍单次 exact pricing 时，多个随机规模上双向平均明显快于单向，说明半域扩展和 join 下界剪枝本身是有收益的。当前这个 BPC root 例子的问题在于：启发式已经把负列基本找完，exact pricing 只做一次无负列证明；在这种调用结构下，双向能省的只是最后兜底证明的一部分，而不是整个列生成过程。后续如果要让双向在完整 BPC 时间上体现更大收益，要么需要更大的 exact pricing 占比，要么需要继续优化 exact 兜底阶段本身，例如 NG/DSSR、join 候选更强剪枝或函数级 join 专用扫描器。

## 2026-05-23：外包成本为无穷大时的具体算例诊断

本次按“外包成本为无穷大，只考虑机器调度”的场景做了具体算例测试。使用 `HEU.TanakaNoOutsourcingBPCTest`，该入口会把 `outsourcingCost[j]` 设为 `Utility.big_M`，因此 RMP 中外包变量不会被选择，求解退化为内部机器列的 branch-and-price。为了先获得可完成结果，从 `data/40-2/wet040_001_2m.dat` 截取了 20 和 30 任务子算例，保留原 setup time 子矩阵。

20 任务、2 台机器、带 setup time 的 no-outsourcing 子算例可以在 root 直接闭合。双向 pricing 结果为：`status=ROOT_PROCESSED`，`incumbent=6343`，`bound=6343`，gap 为 0，处理节点数 1，pricing 轮数 14，列池 1604，耗时约 5.15 秒，解校验可行。关闭双向、使用单向 exact pricing 跑同一 20 任务子算例，结果同样为 root 闭合，目标值 6343，耗时约 5.23 秒。这个规模下单向/双向差别不明显，主要因为 RMP/列加入/启发式 pricing 的固定开销占比较高。

继续缩小规模后，21 任务仍能 root 闭合，但耗时已经上升到约 60.65 秒，`incumbent=6829`，`bound=6829`，pricing 轮数 15，列池 1699。22 任务在 2 分钟内没有闭合，25 任务和 30 任务也都在 2 分钟内没有 root 结果。25 任务 verbose 显示，启发式 pricing 连续加列到列池约 3210 条后输出 `Tabu heuristic pricing found no negative reduced-cost column`，随后卡在 exact pricing；30 任务 verbose 中列池约 4158 条后出现同样现象。30 任务即使把 setup time 清零，2 分钟内也仍然没有闭合。这说明卡点已经从启发式 pricing 转移到双向 exact pricing 的兜底证明阶段，即需要证明在无外包覆盖下已经不存在更多负 reduced-cost 内部机器列。

因此当前判断是：外包成本为无穷大时，问题难度会明显上升，原因不是 setup time 单独造成的，而是所有任务都必须由内部机器列覆盖，root 节点 exact pricing 的 elementary 搜索空间迅速扩大。在这组前缀算例中，20 任务约 5 秒闭合，21 任务约 60 秒闭合，22 任务起 2 分钟内无法闭合，40 任务同样超过 3 分钟未完成。后续如果目标是 no-outsourcing 或外包不可用的大规模精确求解，优先方向不是再做很小的循环级微优化，而是增强 exact pricing：例如 NG/DSSR、pair/join 候选更强剪枝、函数级 join 专用扫描器、以及更详细的 label/join 统计来定位真正瓶颈。

补充复核 20 任务 verbose 后，需要注意 `5.15s vs 5.23s` 这种完整 root 总时间不能直接解释为单向/双向 exact pricing 时间。该例中启发式 pricing 连续加到 1604 条列，随后输出 `Tabu heuristic pricing found no negative reduced-cost column`，双向 exact pricing 只在最后调用一次并返回 0 条列。也就是说，20 任务下 root 总时间主要由启发式 pricing 多轮加列、RMP 反复重解、列池维护和控制台/输出开销组成；双向 exact 只承担最后的“无负列证明”。因此这个小例里单向和双向总时间接近是正常的，不能说明双向 labeling 没有减少 label 扩展。要比较双向 exact 本身，应看 `PricingAlgorithmComparisonTest` 这种在同一个 RMP dual 下直接分别调用单向/双向 exact 的对拍，或者给 `PC` 增加每个 pricing engine 的耗时统计。
## 2026-05-23：15 任务 no-outsourcing 下单向/双向 full BPC 对比

为进一步判断双向 pricing 在完整 BPC 流程中是否有实际收益，本次从 `data/40-2/wet040_001_2m.dat` 到 `wet040_010_2m.dat` 各截取前 15 个任务，并同步截取 `0..15` 的 setup time 子矩阵，构造 10 个 no-outsourcing 子算例。测试入口仍为 `HEU.TanakaNoOutsourcingBPCTest`，即把外包成本设为 `Utility.big_M`，保留原 setup time，分别用 `twet.bpc.bidirectional=false/true` 跑单向 exact 和双向 exact。详细结果已保存到 `test-results/bpc/2026-05-23-no-outsourcing-15-bidir-vs-single.csv`，每个单独日志保存在 `test-results/bpc/2026-05-23-no-outsourcing-15/`。

10 个算例中，单向和双向均在 root 节点直接闭合，目标值、下界、列数和 pricing 轮数完全一致。平均结果为：单向完整求解时间 717.0 ms，双向完整求解时间 749.4 ms；单向 exact pricing 平均 0.139 s，双向 exact pricing 平均 0.156 s；启发式 pricing 分别为 0.390 s 和 0.402 s；RMP/LP 时间基本相同。逐例看，双向只有 `wet015_006` 和 `wet015_010` 的总时间略低，其余多数持平或更慢。

由此当前结论更明确：在 15 任务 no-outsourcing 的 full BPC root 流程中，双向 exact pricing 没有体现收益，甚至因为 join 和半域处理的固定开销略慢。原因不是解不一致，而是该规模下 exact pricing 通常只在启发式 pricing 找不到负列后调用一次，主要作用是兜底证明无负 reduced-cost 列；此时单向状态空间本身已经很小，双向减少 label 扩展的收益不足以抵消 join 端的额外开销。因此，双向 pricing 是否有用不能靠 15 任务 full BPC 来证明；目前它更可能在更大规模、exact pricing 占比更高、或启发式 pricing 较弱时才体现优势。短期结论是：15/20 任务 no-outsourcing full BPC 中默认双向没有明显必要；如果追求稳定速度，可以继续保留开关，用实测决定是否打开。

复核双向实现时发现一个不影响正确性但会增加开销的问题：同一对 forward/backward label 可能分别在两侧 label 出队时各尝试一次 join，而原来的列签名去重发生在函数级 `shift/add/findMinimal` 之后，去重太晚。本次给每个双向 label 增加本轮唯一 id，并在 `tryJoin()` 开头用 label-pair 去重，避免重复做常数延拓和函数级拼接。修改后 `PricingAlgorithmComparisonTest` 仍然 13/13 轮单向/双向最优 reduced cost 一致，`PaperDominanceGraphConsistencyTest` 通过。

重新跑同一批 10 个 15 任务 no-outsourcing 算例后，CSV 已更新。新一轮平均结果为：单向完整求解时间 1052.9 ms，双向完整求解时间 1080.4 ms；单向 exact pricing 0.190 s，双向 exact pricing 0.201 s。不同轮次绝对时间有 JVM 和机器负载波动，但结论没有变化：这个规模下双向并没有稳定优势，主要瓶颈仍不是重复 join，而是 full BPC 流程里 exact pricing 只做一次兜底证明，双向 join 的固定成本较难摊薄。

## 2026-05-24：set covering 与 hybrid-B 双向 pricing 修正

本次根据 `C:\Users\Changxin\Downloads\修改内容.txt` 和 `labeling_algorithm_modification_plan.pdf` 重新核对 BPC pricing 语义，先处理必须修改的部分，completion bound 相关内容继续暂缓。主问题覆盖约束已经从 set partitioning 的 `= 1` 改为 set covering 的 `>= 1`，因此 pricing 侧按非负 job dual 使用 profitable window，`gamma_j = min(max(0, pi_j), b_j)`，不再叠加 predecessor-dependent 的 setup cost advantage。这样做的直接目的，是避免 pair-level `B_ij/H_ij` 被放进 dominance reachable set 后变成不安全的“永久不可达”信息。

单向 `GC` 和双向 `GCBidirectional` 的动态硬窗都改为 job-level 缓存，等价于当前先令 `B_ij = 0`。与此同时，dominance reachable set 不再检查 `node.isArcForbidden(i,j)`：禁弧只禁止当前 direct arc，不能说明该 job 后续不能通过其他前驱访问，因此不能进入 dominance key。实际扩展和 join 仍然单独检查 forbidden arc，所以分支约束没有放松，只是把 dominance key 恢复为只包含可安全传播的信息。

双向 exact pricing 的主流程改为更接近旧 VRP `GCNGBB` 的 hybrid-B 组织：先完整展开 forward half label，维护每个 terminal job 下仍 active 的 forward label 列表和粗粒度最小 reduced-cost/最早完成信息；随后初始化 backward sink，从 backward half 出队扩展并用 crossing arc `(i,r)` 与已生成的 forward labels join。这样避免了旧实现中 forward/backward 交替出队时反复扫描另一侧 dominance graph 的问题，也避免在尚未有完整 forward half 信息时过早 join。join 阶段先做 terminal 组级别的时间可行性与标量乐观下界过滤，只有可能产生负 reduced cost 时才进入 label-pair 级别的函数拼接。

join 所需的常数延拓仍保持 lazy：每个 label 只有第一次真正参与 join 时才构造 `joinExtendedFrontier`，后续同一 label 与其他 label 拼接时复用，不在 join 开始前全量预计算。当前仍保留“先做函数级拼接并得到 reduced cost，再做列签名去重”的列级路径，关于“是否应把列去重提前以减少函数拼接”本次只记录，不改逻辑；completion bound 也尚未接入。代码中不再保留 forward-side join 旧入口，实际 join 统一发生在 backward 出队阶段。

验证方面，已运行 focused `javac`，覆盖 `GCBidirectional.java`、`GC.java`、`LP.java` 以及相关测试入口；`PaperDominanceGraphConsistencyTest` 通过，结果为 `cases=200, insertions=16000`；`PricingAlgorithmComparisonTest` 在补充 CPLEX native path 后通过，`matchedRounds=9/9`，本轮平均单向 1.22 ms、双向 0.56 ms；`SmallBPCBatchTest` 通过，8/8 小算例与 ArcFlow 对齐，tariff 分支诊断也通过。测试会更新 `test-results/bpc/*.csv`，这些输出文件只作为本地验证产物，不纳入本次代码提交。
## 2026-05-24：job-level window 精简与 reachable set 递推

在前一版已经假设 setup time/cost 满足三角不等式、且 `B_ij=0` 后，继续保留 `useJobLevelDynamicWindowCache`、pair-level `H_ij` 数组和 pair-level penalty 缓存已经没有实际意义。本次把单向 `GC` 和双向 `GCBidirectional` 中这部分分支删掉，pricing 每轮只预计算 job-level `H_j`、forward half penalty 和 backward half penalty。`gamma` 仍按 set covering 对偶语义取 `min(max(0, pi_j), b_j)`，但不再通过 `prevJob` 传参，也不再构造任何 predecessor-dependent window。

reachable set 的计算也同步收缩。source/sink 初始 label 仍需要从 `1..n` 全量建立第一层候选；之后每次扩展 child label 时，不再重新扫描所有 job，而是从父 label 的 `reachableSet` 里遍历置位 job，删掉已访问任务后再用当前 frontier 做一步时间可行性过滤。这个递推依赖当前已经采用的三角不等式假设：父 label 中一跳已经不可达的 job，路径继续扩展后不会重新变成可达。因此新的 reachable set 是父 reachable set 的子集，能够避免大量无意义候选检查，同时保持 dominance key 的单调语义。

扩展循环本身也改为直接遍历 `label.reachableSet.nextSetBit()`，而不是 `for job=1..n` 后再查 `contains(job)`。`canExtend` 里仍保留 visited、reachable 和 forbidden arc 检查作为安全边界，其中 forbidden arc 只影响当前 direct arc，不写入 reachable set。验证结果为：focused `javac` 通过；`PaperDominanceGraphConsistencyTest` 通过；`PricingAlgorithmComparisonTest` 通过，`matchedRounds=9/9`；`SmallBPCBatchTest` 通过，8/8 小例和 tariff branch 均有效。小规模计时存在 JVM/JIT 波动，本次结论主要确认语义正确和去掉冗余路径，后续若要精确比较速度仍应看批量 profiling。

## 2026-05-24：H_j 基准窗复用与二次裁剪精简

本次继续收缩 dynamic profitable window 的计算。静态预处理阶段的 `Data.setPreprocessedHardWindows()` 已改为只用单任务外包 baseline `b_j` 构造粗硬窗，不再叠加 `max_i B_ij` 或 `G(b_j)`。这和当前 pricing 假设一致：setup time/cost 满足三角不等式，因此 `B_ij=0`，动态窗的有效阈值就是 `gamma_j = min(max(0, pi_j), b_j)`。

在此基础上，pricing 每轮不再对所有 job 无条件重建 `H_j` penalty。单向 `GC` 先读取 `data.hardWindowStart/End[j]` 和已经裁过的 `data.penaltyFunction[j]`；只有当 `max(0, pi_j) < b_j` 且得到的动态窗确实比静态窗更窄时，才再次调用 `setDomain(hStart,hEnd,true)`。若 `pi_j >= b_j`，说明 dual 不能比初始外包粗窗提供更强剪枝，直接复用原函数，避免重复裁剪。

双向 `GCBidirectional` 同步采用相同判断，并额外缓存基准 forward/backward 半域 penalty。也就是说，`pi_j` 不能进一步收紧时，forward half 直接复用 `[0,T^mid]` 的基准半域函数，backward half 直接复用 `[T^mid,CmaxH]` 的基准半域函数；只有 dual 窗更窄时才临时重做 `setDomain + cropToInterval`。这保持了半域元数据语义，又减少了每轮 pricing 中大量重复的分段函数构造。

验证方面，补入 CPLEX jar/native path 后 focused `javac` 通过；`PaperDominanceGraphConsistencyTest` 通过，`cases=200, insertions=16000`；`PricingAlgorithmComparisonTest` 通过，`matchedRounds=9/9`；`SmallBPCBatchTest` 通过，8/8 小例与 tariff branch 诊断均有效。测试刷新了本地 CSV 输出，但这些文件只作为验证产物，不纳入本次提交。

## 2026-05-24：completion bound 之外的统计补充

按“completion bound 先不动”的边界，本次没有接入 `PredList/AMin` 或 scalar completion bound，也没有调整任何剪枝条件。当前先补的是运行期可观测统计，目的是后续判断瓶颈到底来自 dominance graph 维护、join 候选枚举，还是函数级 `shift/add/findMinimal`。

`PaperDominanceGraph` 增加了本轮 pricing 聚合计数，包括保留/拒绝 label 数、创建/删除 node 数、传播删除 label 数、superset/subset 搜索调用与访问节点数、propagation 调用与访问节点数、envelope merge 次数和函数完整占优检查次数。这些统计只在 `PaperDominanceExactPricingEngine` 和双向 pricing 的 message 中输出，不参与任何 dominance 判断。单向 paper-dominance pricing 在初始化时重置统计；双向 pricing 也在每次 `initialize()` 时重置，避免跨轮累计。

`GCBidirectional` 额外记录 forward/backward label 保留与被支配数量、join terminal group 的扫描与剪枝原因、join candidate label 数、label-pair 层面的 set 冲突、重复 pair、标量下界剪枝、时间剪枝，以及真正进入函数级 join 的次数和函数级剪枝次数。这里仍然只做计数，不改变 `tryJoin()` 的执行顺序和数学判断。这样后续如果发现 `joinFunctionEvaluations` 很少但整体仍慢，应优先看 graph/envelope；如果函数级 join 次数很高，再考虑专门的 shifted-sum-min 扫描器或更强的 join 下界。

验证结果为：focused `javac` 通过；`PaperDominanceGraphConsistencyTest` 通过，`cases=200, insertions=16000`；`PricingAlgorithmComparisonTest` 通过，`matchedRounds=9/9`；`SmallBPCBatchTest` 通过，8/8 小例与 tariff branch 诊断均有效。测试会刷新本地 `test-results/bpc/*.csv`，这些仍作为验证产物，不纳入提交。

## 2026-05-24：root-only dual profitable window 修正

前一版把 `gamma_j = min(max(0, pi_j), b_j)` 的 dual profitable window 作为所有节点的 job-level 动态窗使用。进一步复核后，这个条件只在 pricing 的有效 arc cost 仍等于原始 setup cost `kappa_ij` 时有严格依据。根节点、没有 active cut、也没有分支禁弧时，可以利用 setup cost 三角不等式说明：如果 job `j` 在某个完成时间下的 penalty 已经超过可获得的 job dual prize，那么从序列中删除 `j` 并用 shortcut arc 连接不会增加 setup cost，因此该时间点不需要保留。

非根节点不能继续用这个论证。一方面，arc branching 可能让删除 `j` 后的 shortcut arc 不可行；另一方面，后续若加入带 arc-level dual 的 cut，pricing 里的 reduced arc cost 会变成 `kappa_ij - mu_ij`，即使原始 `kappa` 满足三角不等式，reduced arc cost 也不一定满足。因此 `pi_j` 窗不能作为非根节点的永久不可达资源，更不能写入 dominance reachable set。当前修正为：`GC` 和 `GCBidirectional` 只有在 `node.depth == 0` 且 `activeCutIds` 为空时启用 `pi_j` 动态收紧；其他情况下只复用 `data.penaltyFunction[j]` 已经包含的静态外包窗 `phi_j(t) <= b_j`。双向统计 message 中额外输出 `piWindow=enabled/disabled`，用于后续看当前 pricing 是否启用了 `pi_j` 动态窗。

列去重逻辑本次没有改。`activeColumnSignatures` 仍只记录当前 restricted master 中 active 的列，这是有意保留的：全局 `Pool.addColumn()` 已经按 `SequenceSignature` 去重，如果 pricing 重新发现的是历史 pool 中已有但当前不 active 的列，返回给 `PC` 后可以通过 `LP.addColumns()` 重新激活；如果提前按整个 pool 跳过，反而可能漏掉当前 dual 下应重新进入 RMP 的历史列。

同时修正了双向 pricing 的注释和 message。`GCBidirectional` 单次调用受 `maxExactPricingColumns` 限制，达到列数上限时只能说明已经找到最多 K 条负列，不能把本轮结果当作完整 exact certificate；只有未触发上限并且 forward/backward 队列耗尽时，才对应严格的无负 reduced-cost 证明。completion bound 仍保持未接入。

验证结果为：focused `javac` 通过；`PaperDominanceGraphConsistencyTest` 通过，`cases=200, insertions=16000`；`PricingAlgorithmComparisonTest` 通过，`matchedRounds=9/9`，本轮平均单向 2.89 ms、双向 1.67 ms；`SmallBPCBatchTest` 通过，8/8 小例与 ArcFlow 对齐，tariff branch 诊断有效。测试刷新了本地 CSV 输出，仍不纳入本次提交。
- 2026-05-24：继续处理 pricing 中明确的数据结构低效点。`GCBidirectional` 不再在每个 backward label join 时扫描 `0..n` 所有 terminal group，而是维护已有 forward terminal 的 `PackedBitSet`，只遍历实际出现过的 terminal；`PC.generateColumnsFromEngine()` 和 `LP.addColumns()` 的列 id 判重由 `ArrayList.contains` 改为 `HashSet`，避免 repair 大量补列时退化为线性查找；单向 `GC` 删除只写不读的 `dynamicJobHStart`，并把 forward 序列恢复从头插入改为尾插后反转。该修改不改变 pricing 的 reduced-cost 语义，只减少候选扫描和判重开销。验证：`git diff --check` 通过；`GCBidirectional`、`GC`、`PC` 在现有 `src` classpath 下 focused `javac` 通过；完整 Maven 编译因本机未安装 `mvn` 未执行，`LP` 单文件编译仍受缺失 CPLEX `ilog.*` classpath 限制。
## 2026-05-24：pricing 数据结构与 normal label 最小值优化
这次继续处理前面已经确认的几处“明确低效但不改语义”的点。`PaperDominanceGraph` 里先把 `roots` 从线性表改成集合，并把 immediate-subset 搜索中的局部去重从 `ArrayList.contains` 改成 `HashSet`，同时删掉前面 DFS 已经保证不会重复后仍保留的冗余 `result.contains(candidate)` 检查，目标是减少 dominance graph 维护时最直接的线性去重成本，但不碰更重的 predecessor/successor 邻接结构。`GCBidirectional` 里继续保留 terminal-group 粗剪枝，但在真正扫描某个 terminal 的 forward label 列表时顺手做一次 lazy compaction：把已经 `isDominated` 的 forward label 从该组物理压掉，顺带刷新该 terminal 当前活着 label 的最小 reduced cost 和最早起点；这样后续 backward join 不会反复扫同一批死 label。与此同时，移除了当前 hybrid-B 流程下已经没有实际收益的 label-pair 去重表，因为 join 现在只从 backward 侧单入口触发，不再存在两侧重复触发同一对 label 的旧情形。

另一类优化是 normal label 的 `minReducedCost` 计算。单向 `GC` 和双向 `GCBidirectional` 里的 normal forward/backward label 在进入优先队列前都已经做过方向化 normalize，因此 forward frontier 整体非增、backward frontier 整体非减，最小值分别稳定落在最右端和最左端。基于这个性质，normal label 构造时不再每次调用通用 `findMinimal()` 全段扫描，而是直接按端点 O(1) 取值；需要注意这只适用于已经方向化的 normal label，不适用于 join 时构造出的 `joinCost`，因为后者只是 `shiftedForward + backwardFull + fixed arc` 的和，没有再做方向化，最小值仍可能出现在中间。启发式这边只动了已经确认安全的一点：`singletonProfile(job)` 继续复制中间的 forward 模板，但 backward 模板改为只读复用，不再每次多复制一份。

验证方面，这次先运行 `PaperDominanceGraphConsistencyTest`，结果仍为 `cases=200, insertions=16000`；随后用仓库里现有的 `LP.class/Node.class` 做 `-sourcepath ""` 的 focused `javac` 增量编译，`GCBidirectional.java`、`GC.java`、`PaperDominanceGraph.java`、`HeuristicPricingEngine.java` 均通过。直接按源码编译整条链路仍会被本机未配置的 CPLEX `ilog.*` classpath 阻塞，因此本次保持和前面一致的局部编译验证方式。
## 2026-05-24：heuristic seed/top-K、局部重建与 paper dominance graph 再优化

这次继续处理前面已经确认但尚未落地的几处性能问题，目标仍然是不改 pricing 语义、只减少明显的结构性开销。启发式 pricing 这边，`HeuristicPricingEngine.collectSeedColumns()` 原来会把当前 restricted master 里的所有 active column 都先计算 reduced cost，再做一次完整排序，然后取前 `K` 条 seed。现在改成固定容量 `top-K heap`：顺序扫描所有 active column，只维护当前最优的 `K` 条 seed，最后再对堆里的结果做一次小规模排序以保持原有输出顺序。这样 seed 选择从 `O(R log R)` 收缩到 `O(R log K)`，不会改变最终 seed 候选集合，只是避免在列数较多时对整张 restricted pool 做全排序。

`TabuRouteState` 这边，原来每次 accepted move 后都会 `apply() -> rebuild()`，把整条 route 的 `forward/backward` profile 和 `cost` 全量重建一遍。现在改成局部重建：`REMOVE/ADD/EXCHANGE` 只更新受影响位置附近的 `sequence/used` 状态，并且只从变动位置开始向右重算 `forward`，向左重算 `backward`，未受影响的前缀或后缀 profile 直接复用旧数组内容。这里不改变 tabu neighborhood、aspiration 或 reduced-cost 增量评价公式，只把 accepted move 后的状态刷新从“整条路重建”改成“受影响区间重建”。另外，`singletonProfile(job)` 继续保留中间 `forward` 模板拷贝，但 `backward` 模板改成只读复用，因为当前 `merge3Segments()` 只会改中间的 forward 函数，不会写回 backward 模板。

`PaperDominanceGraph` 这边继续做低风险的结构优化。`connect()/disconnect()` 相关的前驱和后继邻接表从线性表改成集合，减少重复连边和断边时的 `contains/remove` 开销；`removeRedundantSubsetCandidates()` 不再对所有 candidate 做朴素两两比较，而是先按 reachable set 基数从大到小排序，再顺序保留 maximal subset frontier，只和当前已保留 frontier 比较。这样不改变“只保留 immediate subset successors”的语义，但可以减少无谓的二次比较。删点时仍然保留 predecessor 到 active successor 的重连逻辑，不过会先针对每个 predecessor 过滤出与之兼容的 maximal successor frontier，再做重连，避免把 deleted node 的所有 active successor 都无差别连回去。当前仍然没有去强行维护严格的 cover graph/Hasse 图，这一层后续如果要继续压缩图，再单独处理。

验证方面，本轮先运行 `PaperDominanceGraphConsistencyTest`，结果仍为 `cases=200, insertions=16000`；随后用现有 `src` classpath 做 focused `javac` 增量编译，`HeuristicPricingEngine.java`、`PaperDominanceGraph.java`、`GCBidirectional.java` 和 `GC.java` 均通过。完整源码链路编译仍受本机缺失 CPLEX `ilog.*` classpath 约束，这次仍保持局部编译验证方式。当前结论是：这几项修改都属于“语义不动、减少结构开销”的优化，主要收益应体现在 restricted pool 较大时的 heuristic seed 选择、tabu accepted move 后的 profile 更新，以及 exact pricing 的 dominance graph 维护成本上。
## 2026-05-25：基础 penalty 物理定义域补齐

这次回头查双向 backward 半域异常时，核心问题没有落在 `merge`、`normalize(BACKWARD)` 或 join 辅助延拓上，而是在 `Data.setPenaltyFunctions()` 最开始构造 job penalty 的地方。原实现按条件拼三段：只有 `w_e>0 且 d_e>0` 才建 early 段，只有 `d_e!=d_l` 才建中间零惩罚段，tardy 段则总是从 `d_l` 接到 `CmaxH`。这样一来，只要出现 `w_e=0`、`d_e=0` 或 `d_e=d_l` 这类情况，数学语义上虽然 `[0,d_l]` 仍应是零成本，但物理 segment 链可能直接从 `d_l` 才开始。

后续双向 pricing 里，`GCBidirectional.ensureBaseHalfPenaltyCache()` 和动态 backward penalty 都是从 `data.penaltyFunction[j]` 再做 `cropToInterval(..., Tmid, pricingHorizon)`。`cropToInterval` 只裁已有物理 segment，不会凭空补 `[Tmid,d_l)`；而 `setDomain(..., true)` 也是围绕当前物理 `head.start/tail.end` 写窗外 `big_M`，不会把缺失的左侧零成本段补回来。于是 backward sink root 第一次扩展时，`jobPenalty.copy()` 可能一出生就是 `[d_l, ...]`，不再满足“backward label 存在于 `[Tmid,\rho]` 且在 `Tmid` 处有值”的半域不变量，后面 merge 里看到的点接触只是这个上游缺口的外显结果。

本次修正只动了 `Data.setPenaltyFunctions()`：新增 `buildBasePenaltyFunction(jid)`，显式维护一个 `coveredUntil`，若 early 段没有覆盖到 `d_l`，就补一段 `[coveredUntil,d_l]` 的零成本常数段，再接原 tardy 段。这样每个 job penalty 在物理上都连续覆盖 `[0,CmaxH]`，后续静态/动态 hard window、forward/backward half-domain crop 都建立在完整函数上，不再依赖后处理补丁。

验证只做了和本次改动直接相关的最小检查：`git diff` 确认只修改 `src/Basic/Data.java`；`javac -encoding UTF-8 -cp src src/Basic/Data.java src/Common/PiecewiseLinearFunction.java` 通过。更宽的 `GCBidirectional` focused 编译仍会被仓库现有的 CPLEX `ilog.*` classpath 缺失阻断，这不是本次改动引入的问题。

## 2026-05-26：新增 GCNGBB-style 双向 pricing 框架

本次按旧 VRP `GCNGBB` 的双向框架，新建 `GCNGBBStyleBidirectional` 和对应 `GCNGBBStyleBidirectionalPricingEngine`。它复用当前 `GCBidirectional` 已经稳定下来的 half-domain 函数 label、crossing-arc join、single-point store、dynamic window 与 dominance graph 逻辑，但把外层生命周期改成旧 VRP 风格：`initialize -> initializeBackwardSink -> 完整 forward 扩展 -> 完整 backward 扩展 -> 最后统一 join`。也就是说，backward label 插入普通 graph 或 single-point store 时不再立刻 join，而是在 `joinAllBackwardLabels()` 中统一扫描最终仍 active 的 backward 普通 label 和 single-point backward label，再用 crossing arc 连接所有可行 forward 前缀。

这个版本先作为新实现保留，原 `GCBidirectional` 不删除。`TWETBPCConfig` 新增 `useGCNGBBStyleBidirectionalPricing`，默认置为 `true`，因此当前 `TWETBPCContext` 在启用双向 exact pricing 时会优先使用新框架；若需要回到前一版 hybrid-B，可把该开关置为 `false`。当前改动只调整双向定价外层组织和入口选择，不接入 NG/DSSR、PredList/AMin completion bound，也不改已有列 reduced-cost 计算、半域函数递推或分支禁弧语义。

验证方面，focused `javac` 已带 CPLEX jar 编译 `GCNGBBStyleBidirectional.java`、`GCNGBBStyleBidirectionalPricingEngine.java`、`TWETBPCConfig.java` 和 `TWETBPCContext.java`，编译通过；`PaperDominanceGraphConsistencyTest` 通过，结果为 `cases=200, insertions=16000`；带 CPLEX native path 重跑 `SmallBPCBatchTest` 通过，8/8 小算例与 ArcFlow 对齐，tariff branch 诊断也通过。第一次小批量运行时 PowerShell 曾把未加引号的 `-Djava.library.path` 解析错，改成引号包裹后正常运行。测试刷新了本地 `test-results/bpc/2026-05-19-small-bpc.csv`，该 CSV 只作为验证产物，不纳入本次提交。

## 2026-05-26：GCNGBB-style final join 枚举优化

继续复核 final join 时发现，`GCNGBBStyleBidirectional.joinAllBackwardLabels()` 为了拿到普通 backward label，会对每个 `BWTL[firstJob]` 调一次 `getActiveLabels()`。这个接口会重新遍历 dominance graph 的 active node 和 node 内 label，再把未 dominated 的 label 收集到临时列表；随后 join 又遍历一次临时列表。由于新框架已经在最后统一 join，不需要从 graph 反查所有 label，可以在 label 成功插入时直接登记。

本次为 backward 侧补了与 forward 侧对称的 `activeBackwardByFirstJob`。normal backward label 通过 `BWTL` 占优检查并保留后，立即加入对应 first-job 列表；final join 直接扫描该列表，并继续用 `isDominated` 跳过后续被 graph propagation 标记删除的 label。forward 侧原本已有 `activeForwardByLastJob`，因此这次只是补齐 backward 侧，single-point backward label 仍然走独立 store。该修改只减少 final join 前的 graph 遍历和临时 list 构造，不改变 dominance graph 的占优职责，也不改变 crossing-arc join 或 reduced-cost 计算。

验证方面，带 CPLEX jar 的 focused `javac` 通过；`PaperDominanceGraphConsistencyTest` 通过，结果为 `cases=200, insertions=16000`；带 CPLEX native path 的 `SmallBPCBatchTest` 通过，8/8 小算例与 ArcFlow 对齐，tariff branch 诊断有效。

## 2026-05-26：final join 前按 label 乐观 reduced cost 排序

在上一版已经让 forward/backward 普通 label 都有独立枚举 list 后，本次进一步把 final join 前的尝试顺序收紧。新增 `sortActiveLabelListsForJoin()`，在 `joinAllBackwardLabels()` 前对 `activeForwardByLastJob[j]` 和 `activeBackwardByFirstJob[j]` 按 label 自身的 `minReducedCost` 升序排序。这里的 `minReducedCost` 仍只是前缀或后缀 frontier 的乐观最小值，并不等于最终 crossing-arc join 后的列 reduced cost，因此该排序不提供“严格最小列优先”的保证；它的作用主要是在 `maxExactPricingColumns` 触发时，让更有希望产生负列的 label 先参与拼接。

这个排序被封装成独立方法，并且只在 final join 前调用一次。后续如果批量结果显示排序成本大于收益，可以直接注释掉 `solve()` 中的调用，不影响 dominance、label 生成或 reduced-cost 语义。验证方面，带 CPLEX jar 的 focused `javac` 通过；`PaperDominanceGraphConsistencyTest` 通过，结果为 `cases=200, insertions=16000`；带 CPLEX native path 的 `SmallBPCBatchTest` 通过，8/8 小算例与 ArcFlow 对齐，tariff branch 诊断有效。

## 2026-05-26：GCNGBB-style label 队列排序策略开关

本次给 `GCNGBBStyleBidirectional` 的 forward/backward 扩展优先队列增加排序策略开关，用于后续比较不同出队顺序对冗余扩展和 graph dominance 的影响。配置项为 `TWETBPCConfig.bidirectionalLabelQueueOrdering`，默认值保持 `reducedCost`，因此现有行为不变。可选值包括：`reducedCost`，按 label 自身乐观 `minReducedCost` 升序；`time`，forward 按最早完成时间越早越先出队，backward 按最晚完成时间越晚越先出队；`reachableSize`，按 reachable set 基数越大越先出队。

这样做的动机是，按 reduced cost 出队时，一个较早扩展的 label 仍可能被后续才出队的 label 支配，从而产生冗余扩展。`time` 策略偏向先扩展时间边界更靠外的 label；`reachableSize` 策略则偏向先扩展仍有更多候选后继/前驱的 label，希望更早形成强 dominance frontier。三种策略都只改变队列出队顺序，不改变 label 生成、dominance 判定、final join 或 reduced-cost 计算。pricing message 中会输出 `queueOrdering=...`，便于批量日志确认当前策略。验证方面，默认 `reducedCost` 下 focused `javac` 通过，`PaperDominanceGraphConsistencyTest` 通过，带 CPLEX native path 的 `SmallBPCBatchTest` 通过。

## 2026-05-26：20/21 任务双向队列策略小批量对比

新增 `HEU.BidirectionalQueueStrategyComparisonTest`，专门比较旧 hybrid-B eager-join 与当前 GCNGBB-style final-join 下三种出队策略。测试使用 `test-results/bpc/2026-05-24-no-outsourcing-20-21` 中的 `wet020_001_2m.dat` 和 `wet021_001_2m.dat`，四个模式分别为：`oldHybrid`、`gcnReducedCost`、`gcnTime`、`gcnReachableSize`。所有模式都使用 no-outsourcing loader、关闭 ALNS seed、启用双向 exact pricing，并把 heuristic/exact 列数上限放大到 100000，避免工程阈值先截断。

本轮结果全部 `valid=true`，两个算例的 incumbent 与 bound 在四个模式下完全一致。按 exact pricing 总耗时看，`wet020` 上 oldHybrid / gcnReducedCost / gcnTime / gcnReachableSize 分别为 `1.234s / 1.151s / 0.492s / 0.710s`；`wet021` 上分别为 `4.879s / 4.693s / 2.805s / 3.474s`。按两个算例平均，oldHybrid 为 `3.057s`，gcnReducedCost 为 `2.922s`，gcnTime 为 `1.649s`，gcnReachableSize 为 `2.092s`。因此这轮小样本里最好的是 `time`，其次是 `reachableSize`，当前 reduced-cost 出队和旧 eager-join 接近但都明显慢一些。

日志里的结构性指标也支持这个结论。以 `wet021` 为例，`gcnReducedCost` 两轮 exact 中每轮约保留 `6441` 个 forward label，dominanceChecks 约 `218k`，join pairs 约 `335k`；`gcnTime` 降到约 `3630` 个 forward label，dominanceChecks 约 `61k`，join pairs 约 `264k`。这说明时间顺序确实让后续 label 更早形成有效 dominance frontier，减少了冗余扩展和 join 候选，而不是单纯 JVM 波动。输出 CSV 为 `test-results/bpc/2026-05-26-bidir-queue-strategy-20-21.csv`，逐轮日志在同名目录下。

继续把同一队列排序开关接入旧 `GCBidirectional` 后，重新跑 `oldReducedCost`、`oldTime`、`oldReachableSize`、`gcnReducedCost`、`gcnTime`、`gcnReachableSize` 六种模式。第二轮结果仍全部 `valid=true`，目标和 bound 一致。按 exact pricing 时间，`wet020` 六种模式分别为 `1.352s / 0.972s / 0.616s / 0.794s / 0.609s / 0.629s`；`wet021` 分别为 `3.843s / 2.384s / 3.241s / 4.352s / 2.514s / 2.815s`。两个算例平均后，oldReducedCost `2.597s`、oldTime `1.678s`、oldReachableSize `1.928s`、gcnReducedCost `2.573s`、gcnTime `1.561s`、gcnReachableSize `1.722s`。

这轮结论比上一轮更细：队列排序的影响大于 eager-join/final-join 外层框架本身；`time` 在两个算例平均上仍是最好，GCNGBB-style + time 略优于 old + time，但差距不大。`reachableSize` 在 `wet020` 上几乎追平 `time`，但在 `wet021` 上明显落后。reduced-cost 出队在 old 和 gcn 两条路径上都偏慢，原因仍是它会让一些低局部 reduced-cost 但后续会被支配的 label 过早扩展。第二轮输出 CSV 为 `test-results/bpc/2026-05-26-bidir-queue-strategy-old-vs-gcn-20-21.csv`，逐轮日志在同名目录下。
## 2026-05-26：单向 GC label 队列排序策略开关

本次把前面在双向 pricing 中试验的出队顺序也接到单向 `GC` 上。配置项为 `TWETBPCConfig.forwardLabelQueueOrdering`，默认值保持 `reducedCost`，因此不显式改配置时仍按原来的 label 乐观 reduced cost 升序扩展。新增的两个实验值分别是 `time` 和 `reachableSize`：`time` 按 forward label 当前最早完成时间越早越先出队；`reachableSize` 按 reachable set 基数越大越先出队。三种策略都只改变优先队列的弹出顺序，不改变 label 扩展、dominance 判定、列生成或 reduced-cost 计算语义。

这样做的目的仍是减少“较早出队但随后被后续 label 支配”的冗余扩展。单向 forward 没有 backward/final join 的额外差异，因此这个开关主要用于后续对比单向 exact pricing 自身的 dominance frontier 形成速度。当前验证为：带 CPLEX jar 的 focused `javac` 编译 `GC.java`、双向 pricing 入口和 `TWETBPCConfig.java` 通过；`PaperDominanceGraphConsistencyTest` 通过，结果为 `cases=200, insertions=16000`；带 CPLEX native path 的 `SmallBPCBatchTest` 通过，8/8 小例与 ArcFlow 对齐，tariff branch 诊断也通过。默认值未变，后续若要比较单向队列策略，可在 runner 中分别设置 `forwardLabelQueueOrdering=time/reachableSize`。

随后新增本地诊断入口 `HEU.SingleBidirectionalTimeQueueComparisonTest`，默认筛选 `wet021`，并把单向 `forwardLabelQueueOrdering` 和双向 `bidirectionalLabelQueueOrdering` 都设为 `time`。在 `wet021_001_2m` no-outsourcing 算例上，两种模式均在 root 闭合，目标和 bound 都为 `6829.000000`，生成列数和 pool 规模都为 `2015`。单向 `PaperDominanceExactPricing` exact 时间为 `2.144s`，完整 solve 时间为 `2.975s`；GCNGBB-style 双向 `GCNGBBStyleBidirectionalPricing` exact 时间为 `1.272s`，完整 solve 时间为 `2.472s`。按 exact pricing 看，双向 time 约为单向 time 的 `1.69x`；按完整 solve 看约为 `1.20x`。输出 CSV 为 `test-results/bpc/2026-05-26-single-vs-bidir-time-wet021.csv`，逐轮日志在同名目录下。

继续换几个 no-outsourcing 算例后，time 出队下双向仍整体更快，但优势随算例变化很大。`wet020_001_2m` 上单向 exact `5.259s`、双向 exact `0.662s`，双向约快 `7.95x`；`tmp-wet022_001_2m` 上单向 exact `57.675s`、双向 exact `26.733s`，约快 `2.16x`；`tmp-wet025_001_2m` 上单向 exact `733.543s`、双向 exact `637.197s`，约快 `1.15x`。`wet025` 额外用 `mode=bidir` 独立复跑了一次双向，exact 为 `630.837s`，与顺序对比里的 `637.197s` 接近，说明该算例下双向确实也很重，不是被前一个单向进程拖慢造成的假象。为便于这种分开跑法，诊断入口增加 `twet.bpc.timeCompare.mode=single/bidir/both`，默认仍为 `both`。

关于“规模越大双向为什么也变慢”，从 20/22/25 的日志看，原因不是 backward 半边单独爆炸，而是当前 GCNGBB-style 仍需要完整生成 forward half 并维护 dominance graph，规模上来后 forward label 和 graph superset 搜索快速增长。`wet020` 的双向单轮约为 fw kept/dominated `2426/30227`、join candidates `183422`、superset visited `1551978`，exact 只有 `0.662s`；`tmp-wet022` 每轮约为 fw kept `8k-9k`、join candidates 接近 `1.0M`、superset visited `16M-18M`，三轮合计 exact `26.733s`；到 `tmp-wet025` 时单轮就变成 fw kept/dominated `74074/1163807`、join candidates `6972846`、superset visited `642106544`，exact 达到 `637.197s`。backward kept 仍只有几百个量级，因此瓶颈主要是 forward 状态空间、dominance graph 查询和 final join 候选规模共同放大。

这也解释了为什么双向优势在 25 上只有 `1.15x`。当前实现的 midpoint 仍是 root local horizon 下偏右的 `0.75H`，它能压住 backward，但代价是 forward 半域较宽；当算例增大且有效窗口没有强到足以大量砍掉 forward 多步组合时，forward 侧接近单向 pricing 的主要复杂度。双向相比单向仍减少了 kept label、dominanceChecks 和 join/graph 压力，但减少比例不再像 20 那么大，所以绝对时间仍很高。后续若要改善 25+，重点不应只调出队顺序，而应考虑 midpoint 自适应、forward half dry-run、join 候选更强的 lower bound、或 NG/DSSR 这类直接限制多步组合的机制。
继续在 `tmp-wet025_001_2m` 上试 midpoint ratio 后，确认 `T^mid` 对当前双向 time 出队的影响非常大。新增配置 `TWETBPCConfig.bidirectionalRootLocalHorizonMidpointRatio`，默认仍为 `0.75`，诊断 runner 可通过 `twet.bpc.timeCompare.midpointRatio` 覆盖。已完成的双向-only 结果为：`0.625` exact `465.986s`，`0.65` exact `66.898s`，`0.675` exact `28.003s`，`0.70` exact `83.862s`，`0.75` 独立复跑 exact `630.837s`；`0.80` 和 `0.60` 因明显更慢被中止。当前最好的是 `0.675`，相比默认 `0.75` 快约 `22.5x`。

结构指标解释了这个非单调现象。`0.625` 时 forward 很小（fw kept `1300`），但 backward kept 达到 `49580`，superset visited 接近 `486M`，说明 midpoint 太靠左会让 backward 后缀爆炸。`0.75` 时 backward 很小（bw kept `425`），但 forward kept 达到 `74074`、superset visited `642M`，说明 midpoint 太靠右会让 forward 半边接近单向爆炸。`0.675` 在两边之间更平衡：fw kept `10559`、bw kept `7638`、superset visited `44.8M`，虽然 join candidates 仍有约 `10.3M`，但 graph 维护压力大幅下降，所以总时间最低。这个结果说明对 25+ 算例，固定 `0.75H` 不稳，后续应考虑按 capped dry-run 或状态增长率自适应选择 `T^mid`。
进一步针对 `tmp-wet025_001_2m` 分析不同 `T^mid` 下 forward/backward label 数差异的直接原因。该算例 25 个 job 的 due date 集中在 `742..928`，平均约 `825`，processing time 平均约 `54`，setup time 平均约 `25`。而当前 `pricingHorizon=1262` 时，几个候选 midpoint 分别为：`0.625 -> 788.75`，`0.65 -> 820.30`，`0.675 -> 851.85`，`0.70 -> 883.40`，`0.75 -> 946.50`。这些点正好横穿 job due date 主密集区，因此 midpoint 每右移一段，forward 可以保留的多步前缀会快速增多；每左移一段，backward 可以保留的多步后缀会快速增多。

从代码条件看，forward 扩展要求 `earliestCompletion <= min(H_j^end,T^mid)`，所以 `T^mid` 右移相当于放宽 forward 的硬上界，允许更长前缀继续扩展。`0.75` 时 `T^mid=946.5` 已经在所有 due date 右侧，很多前缀都有足够空间继续接后继，导致 fw kept/dominated 达到 `74074/1163807`。backward 扩展则要求 `rhoPrime >= max(T^mid,H_j^start)`，所以 `T^mid` 左移相当于放宽 backward 的硬下界，允许后缀向更早时间回推并继续插前驱。`0.625` 时 `T^mid=788.75` 靠近 due date 左侧，backward 可回推空间很大，导致 bw kept/dominated 达到 `49580/879356`。`0.675` 时 `T^mid=851.85` 处在 due date 分布中部偏右，forward kept `10559`、backward kept `7638`，两侧都没有进入极端爆炸区，因此总时间最低。

用一个粗静态 arc 指标也能看到这个趋势。若用 due date 近似中间 completion，`T^mid=788.75` 时粗略 forward 可接 job 间弧只有约 `4` 条，而 backward 可回推弧约 `192` 条；`T^mid=851.85` 时 forward 约 `142`、backward 约 `21`；`T^mid=946.5` 时 forward 约 `425`、backward 几乎为 `0`。这个静态指标不能直接预测真实耗时，因为真实耗时还取决于 dual window、PWLF frontier 和 dominance graph，但它能解释方向：midpoint 横跨 due date 密集区时，半域宽度变化会被多步组合指数放大。预判 `T^mid` 时应优先看它相对 due/dynamic window 密集区的位置，而不是固定取 `0.75H`。
关于“用最早完成时间 `L` 和 `Cmax` 中点”来取 `T^mid`，在 `tmp-wet025_001_2m` 上按提出的公式计算：`min(source->j setup+p_j)=38`，原始 due-window 左端最小值为 `742`，因此 `L=max(38,742)=742`。若右端用当前双向 pricing 实际使用的 `pricingHorizon=1262`，则 `(L+pricingHorizon)/2=1002`，相当于 `0.794H`；这比当前默认 `0.75H=946.5` 还更靠右，而 `0.75H` 已经因为 forward half 太宽导致 fw kept `74074`、exact `630s`。所以这个公式如果使用原始 due-window 左端，会把 midpoint 推到更危险的 forward 爆炸区。

若右端用全局 `CmaxH=3604`，则 `(742+3604)/2=2173`，相当于全局 `0.603CmaxH`，但它已经超过当前 root profitable window 下的 `pricingHorizon=1262`，在现有 half-domain 语义下不应直接使用；即使强行截到 `pricingHorizon`，也会退化成几乎全 forward、无 backward 的划分。根本问题是：原始 due date 不是 pricing 中“最早可完成时间”。本模型允许早完并支付 earliness penalty；真正的左端应来自当前 dual 下的 profitable window `H_j^start = d_j - pi_j/w_e_j` 或静态 hard window，而不是 `d_j` 本身。在 no-outsourcing 根节点里，dual 会把 profitable window 左端推到 due date 左侧，单看 `min d_j` 会系统性高估 `L`，从而把 `T^mid` 右推。

因此，这个思路的有用部分不是直接取 `(min due + horizon)/2`，而是提醒 midpoint 应参考当前可盈利 completion 区间的有效分布。更合理的静态候选可以基于动态 `H_j^start/H_j^end` 分布，而不是原始 due：例如取动态窗口中心或上分位，再加上平均 `setup+p` 的补偿；或者更直接地用候选 `T^mid` 计算一跳/两跳 forward/backward 可扩展性，避免落到“forward 几乎全开”或“backward 几乎全开”的区域。对 `wet025` 来说，经验最优 `851.85` 更接近 due 分布中部偏右，而不是 `[L,pricingHorizon]` 的几何中点 `1002`。
按“当前硬时间窗左端”而不是原始 due 来重新计算后，`tmp-wet025_001_2m` 的结果明显更合理。为此在 GCNGBB-style 双向日志中临时补充 `dynamicHStartMin/dynamicHEndMax/earliestSourceCompletion`。`ratio=0.675` 复跑显示：`dynamicHStartMin=414.0`，`dynamicHEndMax=pricingHorizon=1262.0`，`earliestSourceCompletion=38.0`。若按公式 `L=max(min_j H_j^start, min_j setup_0j+p_j)`，则 `L=max(414,38)=414`；取 `(L+pricingHorizon)/2=(414+1262)/2=838`，相当于 `0.664H`。这和实测最好的 `0.675H=851.85` 非常接近，说明这个思路在使用“当前 root dual 下的动态 profitable hard window”时是有解释力的。

这也解释了前一版用原始 due 得到 `1002` 的问题：原始 due 左端 `742` 不是 pricing 允许的最早可盈利完成时间，root dual 会把很多 job 的 profitable window 左端推到更早，当前最小值约为 `414`。因此预判 midpoint 时，左端应优先用本轮 pricing 已经计算出的 `dynamicJobHStart` 分布；在非根节点或没有 dual profitable window 时，再退回到外包静态 hard window；若外包也没有给出有限窗口，则只能用 source earliest completion 和 setup 推导出的物理左端。右端也应使用本轮 `pricingHorizon=max_j H_j^end`，而不是全局 `CmaxH`。该规则给出的初始 midpoint 可作为候选中心，再配合 `0.65/0.675/0.70` 这类邻域 dry-run 微调。

已把上述规则落到当前双向 pricing 默认逻辑里。`bidirectionalRootLocalHorizonMidpointRatio` 现在默认取 `NaN`，表示使用动态窗口 midpoint；若后续实验需要复现固定比例，只要把该配置设为 `0..1` 内的有限数，仍会强制使用 `ratio * pricingHorizon`。动态公式为 `L=max(min_j H_j^start, min_j setup_0j+p_j)`，`T^mid=(L+pricingHorizon)/2`。实现里还额外把 `T^mid` 夹在 `(0,pricingHorizon)` 内，原因是某些 tariff branch 节点的局部窗口可能已经贴到右端，如果直接返回 `pricingHorizon`，后向半区间会退化并触发 `PiecewiseLinearFunction` 的 `start >= end` 检查。验证上，focused `javac`、`PaperDominanceGraphConsistencyTest`、`SmallBPCBatchTest` 及 tariff branch 均通过；`tmp-wet025_001_2m` 默认动态中点复跑得到 `dynamicHStartMin=414`、`pricingHorizon=1262`、`tMid=838`，双向 time 出队 exact 为 `25.017s`。

复跑 15、20、21、22 后，动态 midpoint 对 20+ 算例的收益很明显。15 个任务 10 个算例因为本身很小，single exact 合计 `0.334s`、bidir exact 合计 `0.132s`，exact 约 `2.54x`，但总 solve 只从 `3.654s` 降到 `3.154s`，约 `1.16x`，说明小规模主要被初始化、主问题和固定流程开销限制。20、21、22 上差异更清楚：`wet020_001_2m` single/bidir exact 为 `4.154s/0.406s`，exact speedup `10.23x`；`wet021_001_2m` 为 `4.553s/0.380s`，`11.98x`；`tmp-wet022_001_2m` 为 `47.489s/3.014s`，`15.76x`。对应 solve speedup 分别为 `3.37x/3.47x/8.45x`。这说明前面 `0.75` 固定切分导致 22/25 规模下 forward 半边过宽的问题已经被明显缓解；当前双向的收益开始随规模增长放大，但最终 solve 时间仍会受 master LP、启发式定价和结果校验等非 exact pricing 部分影响。

回看旧 VRP 代码后，`GCNGBB` 的双向中点没有做当前这种动态窗口调整。旧代码在构造函数中直接设 `max_time = data.m_customer.get(data.m_customer_number).m_late_time`，`time_bound` 也是同一个 late time；forward 扩展里用 `lbtime + service_i > max_time/2 + tolerance` 截断，backward 侧则用 `lb.m_time < max_time/2` 控制是否继续入队。更基础的 `GCBDT` 也是相同口径，构造函数取末端 late time 为 `max_time`，然后用 `max_time/2` 分割。也就是说，旧 VRP 的 bounded bidirectional 是按全局时间 horizon 的一半切，而不是按本轮 dual 下的 local profitable window 或 hard window 来切。

这套 `max_time/2` 规则如果遇到所有客户时间窗都集中在 horizon 后半段，确实也会出现和前面 TWET 里 `Cmax/2` 类似的问题：切分点偏左，forward 很快被截断，backward 半边承担大部分组合复杂度，双向就不再是真正平衡的 meet-in-the-middle。旧 VRP 中这个问题可能没那么突出，主要是因为它的时间资源是 VRPTW 的服务开始/消耗时间，depot early 通常从 0 开始，经典 Solomon 类实例的窗口分布也未必像当前 TWET 的 profitable window 那样整体右移；同时旧 `GCNGBB` 还有 time/capacity bound 和 NG memory 在剪标签。但从原则上讲，若窗口密集区偏离 `[0,max_time]` 的中点，更合理的 split 应该看有效窗口区间本身，例如用 `Tmid=(L+H)/2`，其中 `L` 取本轮可行/可盈利窗口的左侧有效位置，`H` 取本轮窗口右侧有效位置，而不是机械用全局 horizon 的一半。当前 TWET 的动态 midpoint 就是这个思路：左端用 `max(min_j H_j^start, min_j setup_0j+p_j)`，右端用本轮 `pricingHorizon=max_j H_j^end`。
## 2026-05-28 GCNGBB-style 双向 pricing 优化计划评估

本次阅读 `C:\Users\Changxin\Downloads\gcngbb_pricing_optimization_plan.pdf`，只对方案做代码对照评估，暂不修改 `GCNGBBStyleBidirectional.java`。文档主要讨论五类优化：final join 内部 early break、active column 与全局 pool 去重、动态窗口扫描合并、forward-only column 占用 column cap、以及 `FunctionLabel.compareTo()` 的 tie-break。

当前判断是，`compareTo()` 加 `labelId` tie-break 属于低风险清理，值得做。当前 `FunctionLabel.compareTo()` 在 `minReducedCost` 和 `jid` 相同的时候直接返回 0，而 `sortActiveLabelListsForJoin()` 会用 `Collections.sort()` 排 forward/backward active label list。虽然 Java 的稳定排序通常不会因此出错，但如果后续 join early break 依赖排序顺序，最好让比较关系严格、可复现，和现有 `compareReducedCost()` 的 tie-break 一致。

join 内部 early break 的方向也是合理的。final join 前已经按 label 自身 `minReducedCost` 升序排序；在固定 `backward` 和固定 crossing arc `(lastJob, backward.jid)` 时，`backward.minReducedCost + joinFixedReducedCost` 是常数，因此若某个 live forward label 的 `pairLB = forward.minReducedCost + backward.minReducedCost + joinFixedReducedCost` 已经不为负，后续更大的 forward label 不可能通过标量下界。这可以减少大量进入 `tryJoin()` 的次数。但实现时要小心当前 `joinFromBackward()` 同时承担 lazy compaction：如果中途 break，不能再用 `candidates.subList(liveCount, candidates.size()).clear()` 清掉未扫描尾部，否则会误删尚未被当前 backward 扫过、但可能对后续 backward 有用的 forward label。第一版应只做 early break，不在该轮做尾部 compaction，或者先单独 compact/sort 再 join。

active column 去重这一项，PDF 里的风险前提在当前代码中基本已经满足。`Pool` 是全局列池，并且已经用 `SequenceSignature -> columnId` 去重；`PC.generateColumnsFromEngine()` 会先 `pool.addColumn(...)`，再用当前 RMP 的 active column id 集合决定是否把该 id 传给 `LP.addColumns()`；`LP.addColumns()` 也只检查当前 restricted master 是否已 active。因此当前 pricing 中只用 `activeColumnSignatures` 跳过“当前 RMP 已 active 的同序列列”是合理的：如果某个序列已经在全局 pool 里但当前节点未 active，pricing 返回它以后 `Pool.addColumn()` 会复用旧 id，`LP.addColumns()` 会把旧列重新激活。这里暂不需要改核心逻辑，最多后续补统计区分“新插入 pool”和“复用已有 pool 列”。

动态窗口扫描合并更偏维护性重构。当前 `computeCurrentPricingHorizon()`、`computeCurrentPricingWindowStart()`、`precomputeJobLevelDynamicPricingWindows()` 和 `precomputeBackwardDynamicPricingWindows()` 里确实重复计算 effective hard/profitable window，后续改公式时有漂移风险。把 effective window 统一预计算成数组，再由 forward/backward 半域缓存复用，会让代码更清楚。但这不是当前最直接的性能瓶颈，且涉及 midpoint、horizon、forward/backward penalty cache 多处语义，建议排在 early break 和 tie-break 之后。

forward-only column 抢占 `maxExactPricingColumns` 是真实策略风险。当前 `solve()` 先完整跑 forward，且 `forwardExtend()` 会调用 `tryGenerateForwardColumn()`；如果 singleton/forward-only 负列先把 `generatedColumns` 填到上限，后续 backward 和 final join 会提前停止。这不影响严格性说明，因为达到 column cap 时本轮本来就不是 exact certificate，但会影响返回列质量，可能错过 reduced cost 更低的 join 列。这个问题是否值得改，应先加统计观察：forward-only 生成数、join 生成数、是否在 backward 前或 join 前触发 cap、返回列 reduced cost 分布。真正改成“先收集候选、最后按 reduced cost 统一截断”会改变当前搜索停止条件和内存占用，不适合作为第一步直接改。

因此推荐顺序是：一，先做 `compareTo()` tie-break；二，在 `joinFromBackward()` 加 pair-level early break 和对应统计，但避免中途 compaction 误删；三，补 forward-only/join column 与 cap 触发统计，先用 20/21/22/25 算例看是否真存在 forward-only 抢占；四，如果后续还要继续整理，再把 effective window 预计算合并。active column/pool 去重核心逻辑当前不需要改。

## 2026-05-28 GCNGBB-style final join 与窗口缓存调整

本次按后续讨论直接修改 `GCNGBBStyleBidirectional`。队列策略中的 `time` 现在不再只按时间和 reduced cost 排序，而是按“时间优先、reachable set 基数越大越优先、最后 reduced cost”比较；`reachableSize` 策略仍保留为 reachable set 基数优先。`FunctionLabel.compareTo()` 也复用 `compareReducedCost()`，在 reduced cost 和 job 相同后继续用 `labelId` 打破平局，保证 final join 前排序稳定可复现。完整列生成从 forward 出队阶段移到最终收尾阶段：forward label 出队和 forward single-point 插入时不再立即调用 sink 收尾列生成，而是在两侧 label table 生成完成、final join 前先清理已 dominated 的 active label list，再统一 join，最后若列数上限还有空间，再从最终存活的 forward label 生成 direct-to-sink 列。这样避免早期出队但后来被 dominance 干掉的 label 抢占 `maxExactPricingColumns`。

final join 侧新增 pair-level early break。由于每个 terminal group 已按 forward label 的 `minReducedCost` 排序，固定 backward label 和 crossing arc 后，一旦 `forward.minReducedCost + backward.minReducedCost + arcReducedCost` 不再为负，后续 forward label 的同一标量下界只会更差，可以直接停止扫描该 terminal group。为避免 break 时误删尚未扫描的尾部 label，本次把 lazy compaction 前移到 `compactAndSortActiveLabelListsForJoin()`，join 内部不再负责裁剪 candidate list。

窗口预计算也合并成一条 effective-window 路径。非根节点或已有 active cut 时，不再按 dual 收缩 profitable window，直接使用静态 hard window，并把 `pricingHorizon` 固定为全局 `data.CmaxH`，因此这些节点的 base half penalty 可以稳定复用。根节点且 no-cut 时，仍按当前 LP dual `pi_j` 重新计算本轮 effective window、局部 `pricingHorizon` 和 `tMid`；这一步必须每轮 pricing 前重算，因为根节点 RMP resolve 后 dual 会变化。`baseHalfPenaltyCache` 现在同时检查 `tMid` 和 `pricingHorizon`，避免 backward 半域 `[Tmid,pricingHorizon]` 在右端变动时复用旧缓存。

关于 pool 语义，本次也确认当前 TWET 实现中 column 和 cut 都有全局池：`Pool` 保存所有已知 column 并按 `SequenceSignature` 去重，`CutPool` 保存所有已知 cut 并按 signature 去重；当前 node/RMP 只通过 `restrictedColumnIds` 和 `activeCutIds` 激活其中一部分。因此 pricing 内的 `activeColumnSignatures` 只表示当前 RMP 已 active 的列，不表示全局 pool 中所有已存在列。验证方面，`javac -encoding UTF-8 -cp src -sourcepath src src/TWETBPC/GC/GCNGBBStyleBidirectional.java` 通过，`PaperDominanceGraphConsistencyTest` 通过（`cases=200, insertions=16000`）。

## 2026-05-28 旧 VRP GCNGBB 的候选列排序语义

复查旧 VRP `D:\软件\Trae\项目\BPC\src\BPC\GC\GCNGBB.java` 后，可以确认 bounded bidirectional + DSSR 路径不是发现负列就立刻全部加入主问题。`Join()` 中合法 elementary 负列先进入定价器本地 `GCPool`，其中 `pool.AddRoute(route, cost)` 记录的是拼接后的 reduced cost；若拼接结果包含真实重复客户，则只更新 `m_best_cycle` 供 `UpdateNGSet()` 收紧 ng-set，不加入候选池。

在 `Extend()` 结束阶段，旧代码只有当 `pool.GetSize() > addin_size` 时才调用 `pool.Sort()`，而 `GCPool.Sort()` 按 `m_reduce_cost` 升序排列。随后代码顺序遍历本地 pool，把不在当前 LP pool 中的 route 加入 `gn_index`，直到达到 `addin_size`。因此旧 `GCNGBB` 的语义是：候选数量不超过上限时按发现顺序加入；候选数量超过上限时先按 reduced cost 选更负的前若干条。旧基础双向 `GCBDT` 和单向 `GC` 则更接近发现即加，达到 `addin_size` 就提前停止，没有 `GCPool` 统一排序层。

对应到当前 TWET `GCNGBBStyleBidirectional`，现在 final join 前的 label 排序只是“更可能找到负列”的尝试顺序，不等价于旧 `GCPool` 的候选列 reduced-cost 排序；`tryGenerateColumn()` 仍是发现一条负列就加入 `generatedColumns`，达到 `maxExactPricingColumns` 即停止。若要完全贴近旧 `GCNGBB` 的超量处理，应把 join 与 sink 收尾产生的合法负列先放入一个本地候选池，记录真实/推断 reduced cost，最后按 reduced cost 升序截取 `maxExactPricingColumns`。这会增加内存和需要更多候选扫描，属于策略修改，不是简单把 `Collections.sort(labels)` 换成列排序。

## 2026-05-28 旧 VRP GCTabu 与当前启发式定价加列流程对照

旧 VRP 的启发式 pricing 对应 `D:\软件\Trae\项目\BPC\src\BPC\GC\GCTabu.java`。它先从当前 LP 的 `route_index` 中按 CPLEX reduced cost 选出若干 seed route，然后对每个 seed 执行 tabu move。每次找到负 reduced-cost route 时，先查当前 LP 的 `lp.pool.Route2ID(temp_route)`，若不是已有 route，就把候选放入定价器本地 `GCPool`：`pool.AddRoute(temp_route, reduce_cost)`。本地池达到 `m_gen_size` 会停止继续搜索。

旧 `GCTabu.Extend()` 结束时，如果本地 `pool.GetSize() > addin_size`，会调用 `pool.Sort()`，也就是按候选 route 的 reduced cost 升序排序。随后顺序遍历本地 pool，把当前 LP pool 中没有的 route 加入 `lp.pool` 并记录到 `gn_index`，直到 `gn_index.size() >= addin_size`。如果有新增列，`GCTabu` 会在定价器内部直接调用 `lp.AddColumn(gn_index)`，并返回 `true` 触发外层继续重解 LP。因此旧启发式是“本地候选池收集 -> 超量时按 reduced cost 排序 -> 最多加 `addin_size` 条 -> 定价器内部立即加列”。

当前 TWET 的 `HeuristicPricingEngine` 在这一点上基本沿用了旧语义，但加列职责被移到了 `PC`。当前先用 `collectSeedColumns(lp)` 从当前 restricted columns 中取 reduced cost 较低的 seed；tabu 搜索过程中，负 reduced-cost 且不在当前 RMP active signatures、也不是本轮重复的 sequence，会进入 `negativeCandidates`。候选数达到 `heuristicPricingPoolSize` 后停止继续搜索。最后 `negativeCandidates` 按 reduced cost 升序排序，并返回前 `maxHeuristicPricingColumns` 条 `TWETColumn`。

真正加入列池和 LP 的动作由 `PC.generateColumnsFromEngine()` 做：它遍历 engine 返回的列，调用全局 `Pool.addColumn()` 按 sequence signature 去重并拿到 column id；若该 id 当前 RMP 还未 active，就加入 `newColumnIds`。随后 `PC.solvePricingLoop()` 调用 `lp.addColumns(newColumnIds)`，增量加入当前 CPLEX 模型，并立即重解 LP，然后从第一个 pricing engine 重新开始。也就是说，当前启发式与旧 `GCTabu` 在“候选池排序截断”上是一致的，主要差别是旧代码由启发式定价器自己 `lp.AddColumn()`，当前统一由 `PC` 管理 pool 去重、active 检查、增量加列和重解。

## 2026-05-28 GCNGBB-style 候选列统一筛选修改

本次按讨论继续修改 `GCNGBBStyleBidirectional`，解决两个流程问题。第一，forward label 直接接 sink 的列不再作为 final join 之后的独立补扫，而是并入统一收尾流程：现在以 forward terminal group 为外层，先对该组扫描所有 backward normal/single-point label 做 crossing-arc join，再对同一组做 `forward->sink` 收尾。这样 sink 列和 crossing-arc join 列进入同一个候选筛选路径，不会因为处理位置靠后或靠前而绕过统一策略。

第二，exact pricing 里的列数上限不再作为“搜索停止条件”。旧实现是一发现负列就加入 `generatedColumns`，达到 `maxExactPricingColumns` 后停止后续 backward/join/sink 扫描；这会让返回列依赖发现顺序，也可能让较早发现但 reduced cost 不够好的列占满上限。本次改成容量为 `maxExactPricingColumns` 的本地候选堆：每条合法负 reduced-cost 列先生成 `PricingColumnCandidate`，堆里只保留当前 reduced cost 最小的 K 条；当新候选比堆顶当前最差候选更好时，替换堆顶。候选同时按 `SequenceSignature` 建映射，同一 sequence 后续若以更低 reduced cost 出现，会替换旧候选；若不更好则丢弃。最终返回前再把堆中候选按 reduced cost 升序排序，写回 `generatedColumns`。

这里选择“有界最大堆”而不是“list 全量收集后排序”，主要是为了控制 final join 候选很多时的内存占用。list+sort 实现更简单，也能得到同样的 top-K，但需要保存所有负列；当前 exact join 在大算例上可能产生大量候选，保留 K 个最好候选已经足够满足本轮返回列需求。代价是搜索阶段不再因收满 K 条列而提前停止，因此单轮 exact pricing 可能比发现即截断更久；但返回列质量更接近旧 VRP `GCNGBB` 的“候选池超量后按 reduced cost 选最好”的语义，也避免 forward/sink 或早期 join 列抢占上限。

验证方面，`javac -encoding UTF-8 -cp src -sourcepath src src/TWETBPC/GC/GCNGBBStyleBidirectional.java` 通过，`PaperDominanceGraphConsistencyTest` 通过（`cases=200, insertions=16000`）。当前还没有跑完整带 CPLEX 的 BPC 批量回归；后续需要重点观察 `candidatePool kept/seen/dropped` 统计，以及 top-K 选择后 root 迭代次数和总时间是否改善。

随后进一步收紧 `pi_j=0` 的任务处理。此前实现名为 `zeroDualSingletonOnlyJobs`，语义是根节点 no-cut 下仍允许 `source->j->sink` 这类 singleton 旁路，只禁止它参与多任务列。复核后确认这和当前剪枝依据不一致：如果采用“无 cut/branch dual 且三角不等式成立时，`pi_j=0` 的 job 不可能出现在负 reduced-cost 列中”的判断，就不应再保留 singleton 例外。因此本次改成 `zeroDualExcludedJobs`：这类 job 在 forward/backward reachable-set 构建阶段就被排除，`canExtendForward/canExtendBackward` 也直接拒绝；join 中的 zero-dual 检查只作为防御性过滤，避免历史 label 或边界路径漏入。验证：focused `javac` 通过，`PaperDominanceGraphConsistencyTest` 通过（`cases=200, insertions=16000`）。

## 2026-05-28 GCNGBB-style 当前版本复核

本次只做当前版本复核，不改算法代码。前面讨论的三项主要修改已经落到 `GCNGBBStyleBidirectional`：一是 `zeroDualSingletonOnlyJobs` 已完全替换为 `zeroDualExcludedJobs`，根节点 no-cut 下 `pi_j=0` 的任务在 reachable-set 构建和 forward/backward 扩展入口都被排除，join 中的同名检查只剩防御作用；二是 forward->sink 收尾列已经并入以 forward terminal group 为外层的统一 final join 流程；三是 exact pricing 不再用列数上限提前停止搜索，而是用本地 top-K 候选堆按 reduced cost 保留当前最好的 `maxExactPricingColumns` 条列，并按 `SequenceSignature` 合并同序列候选。

复核中未发现会直接漏列或 reduced-cost 计算错误的新问题。sink 收尾列没有叠加 `setupCost(lastJob,sink)`，这和当前数据结构一致：`Node.sinkId()` 为 `n+1`，而 `Data.setupCost` 只定义到真实任务 `0..n`，终点 setup cost 语义为 0；收尾只需要扣 `arcDual(lastJob,sink)`。剩余主要是维护性和效率问题：`canContinue()` 现在只表示 `maxExactPricingColumns > 0`，名字仍像“达到上限就停”；`joinTerminalGroupsEmpty` 统计已基本失效；backward active label list 当前虽然排序，但 final join 没利用该排序做 backward 侧 early break；`joinForwardGroupToBackwardLabels()` 仍按 `1..n` 扫所有 firstJob，后续可用 active backward job bitset 跳过空组。验证：`javac -encoding UTF-8 -cp src -sourcepath src src/TWETBPC/GC/GCNGBBStyleBidirectional.java` 通过，`PaperDominanceGraphConsistencyTest` 通过（`cases=200, insertions=16000`）。

随后按复核结论清理 `joinTerminalGroupsEmpty`。这条统计来自早期以 backward label 为外层、可能遇到某个 terminal job 的 forward candidate list 为空的 join 写法；当前 final join 已改成 `activeForwardTerminalJobs` 为外层，进入 `joinForwardGroupWithBackward()` 前就已经拿到了非空 forward candidate group，因此这条统计不会再自然递增，继续保留只会让日志口径误导。当前 summary 改为 `join groups scanned/arcOrVisit/timeLB/costLB`。

随后按讨论简化同一 sequence 的候选处理。当前判断是，一条完整任务序列的真实 column cost 由 sequence 本身决定；如果同一 sequence 在不同 crossing-arc split 下重复出现，理论上 reduced cost 应一致。即使由于函数下界推断、数值误差或 debug 复核开关导致 inferred reduced cost 略有差别，也不值得为本轮 top-K 候选池引入“同签名替换旧候选”的额外逻辑。因此 `GCNGBBStyleBidirectional` 现在和旧 VRP `GCPool.AddRoute()` 更接近：本轮已经见过同一 `SequenceSignature` 时，后续重复候选直接丢弃，不再比较 reduced cost，也不再从 `PriorityQueue` 中线性删除旧候选。这样牺牲的是极少数数值差异下的“保留更好推断值”，换来更简单的去重语义。

随后为评估 half-domain 裁剪本身的函数操作收益，新增一份复制版 `GCNGBBStyleBidirectionalFullDomain`，不改当前正式 `GCNGBBStyleBidirectional`。复制版只把 source/root、sink/root、base half penalty cache 以及动态 forward/backward job penalty 的裁剪区间改成 `[0, pricingHorizon]`，其余 reachable-set、dominance、join、candidate top-K、zero-dual 过滤和队列策略保持一致；`TWETBPCConfig.useGCNGBBFullDomainBidirectionalPricing` 默认为 `false`，只由比较 runner 临时打开。需要注意，这个复制版是“最小改动对照”，由于 `minReducedCost` 仍按函数 head/tail 端点取值，full-domain 支持区间变宽后不一定只影响段操作数量，也可能轻微影响 label 排序和 lower-bound 剪枝，因此实验结果应按工程对照而不是严格微基准理解。

新增 `HEU.GCNGBBFullDomainComparisonTest` 用于同一 no-outsourcing 输入下比较正式 half-domain 版本和复制版 full-domain 版本。`wet020_001_2m` 的一次 paired run 中，两版都返回 `ROOT_PROCESSED`、`obj=bound=6343`、`valid=true`，half/full exact 分别为 `0.377s/0.149s`；`wet021_001_2m` 的 paired run 中，两版都返回 `obj=bound=6829`、`valid=true`，half/full exact 分别为 `0.651s/0.587s`。考虑到 paired run 固定 half 先跑、full 后跑，另用单独 JVM 跑 `wet021`：half-only exact `0.887s`，full-only exact `0.717s`。这批小样例上 full-domain 没有变慢，甚至略快，但样本很少且受预热、LP 重解和剪枝顺序影响，当前只能说明“把函数域放宽到全域并未在 20/21 小样例上立刻造成明显效率灾难”；是否代表半域裁剪主要收益不在函数段数，还需要在 22/25 或更大样例上继续跑。

补充更多样本后，结论需要进一步收紧。10 个 15 任务算例、20、21、22、25 的 paired run 全部保持目标、bound 和 `valid=true` 一致；14 个 paired case 的平均 exact 时间 half/full 为 `1.414s/1.290s`，平均 solve 时间为 `2.038s/1.882s`。但这个平均数不能解释为 full-domain 更优，因为 15 任务 exact 时间多在毫秒级，比例噪声很大；而较大样例分歧明显：`wet022` 上 full exact 比 half 慢 `15.1%`，`wet025` paired run 上 full exact 又快约 `8.3%`，单独 JVM 下 `wet025` half/full exact 为 `18.163s/17.045s`，仍是 full 略快。

从日志统计看，full-domain 并不是少干活。`wet022` 上 full 的 join candidate 从 `108,876` 增到 `2,079,560`，function eval 从 `70,768` 增到 `1,162,022`；`wet025` 上 full 的 join candidate 从 `843,611` 增到 `27,105,684`，function eval 从 `562,421` 增到 `14,763,123`。同时 full-domain 的 dominance graph superset visited 反而下降，例如 `wet025` 从 `44,085,562` 降到 `33,835,821`，`wet022` 从 `2,559,456` 降到 `1,642,380`。因此当前 full 有时更快，主要不是因为全域函数操作更便宜，而是它改变了 label 的定义域和端点 min 语义，使 dominance graph 搜索、join lower-bound 剪枝和 label 排序路径都变了；两个大模块的耗时此消彼长，wall time 结果不能直接归因于“函数段更多/更少”。

这也说明当前复制版不是严格的“只增加函数段操作”的微基准。正式 half-domain 中 forward label 的 `minReducedCost` 取 `[ell,Tmid]` 右端，backward label 取 `[Tmid,rho]` 左端；full-domain 复制版把函数定义域放到 `[0,pricingHorizon]` 后，`forwardEndpointMin()` 和 `backwardEndpointMin()` 也随之取了全域端点，导致 lower bound 更乐观，costLB 剪枝更弱。例如 `wet025` 的 join group costLB prune 从 `167,554` 降到 `46,563`，直接放大了 join 扫描。若后续要做纯粹的段操作成本实验，需要让 full-domain 版本保留原 half-domain 的标量 min、排序键和 lower-bound 口径，只在内部函数对象上携带更宽定义域；当前版本更适合作为“放宽定义域后的整体工程行为”对照。

当前对 half-domain 与 full-domain 的工程判断如下。half-domain 的优势是语义清楚，和论文里的半域标签、`Tmid` join 常数延拓一致；它的标量 `minReducedCost`、group lower bound 和 pair lower bound 更贴近当前 join 语义，因此 costLB 剪枝更强。已有统计中 `wet025` 的 half-domain join candidates 只有 `843,611`，function eval 为 `562,421`，而 full-domain 分别涨到 `27,105,684` 和 `14,763,123`；`wet022` 也从 `108,876/70,768` 涨到 `2,079,560/1,162,022`。这说明 half-domain 在控制 join 爆炸方面更稳定，尤其当后续要证明 exact pricing 或控制大算例内存时，仍应作为正式版本。

full-domain 的优势主要体现在 dominance graph 搜索路径上。由于函数定义域更宽，reachable set 在扩展过程中不容易被半域边界切碎，很多 label 可能落到较少的 superset 搜索路径上；`wet025` 的 `superset visited` 从 half 的 `44,085,562` 降到 full 的 `33,835,821`，`wet022` 从 `2,559,456` 降到 `1,642,380`。因此 full-domain 有时 wall time 反而略快，例如 `wet025` paired run 中 exact `17.757s/16.280s`，单独 JVM 中 `18.163s/17.045s`；但 `wet022` 又变慢为 `0.832s/0.958s`。这说明 full-domain 不是单调改进，而是在 dominance graph 和 join 两个热点之间转移复杂度。

从当前样本看，两者的最终时间差并不大。14 个 paired case 的平均 exact 时间 half/full 为 `1.414s/1.290s`，平均 solve 时间为 `2.038s/1.882s`；这个平均还被 15 任务毫秒级小样例和 JVM/CPLEX 预热噪声影响。较大的 `wet022/wet025` 也只表现为十几个百分点以内的上下浮动，并且两版目标、bound、valid 都一致。因此暂时没有必要把 full-domain 继续推进成正式候选，也没有必要为它做复杂的“保留 half-domain lower bound、内部携带 full-domain 函数”的第三版微基准。后续若要继续优化，更值得先做 exact pricing 分段计时和 dominance/join 热点拆分，而不是在函数定义域口径上继续投入。
## 2026-06-01 旧 VRP dual stabilization 复核

复查旧 VRP `src/BPC/LP/LP.java` 后，代码里确实有一段标注为 `for stabilization` 的变量和方法：`vars/vars_bound/vars_value/init_value/step_size`，在 VRPTW 模式下 `Construct()` 会调用 `add_slacks()`，给每个 customer coverage 行加一个高成本人工变量。后续还定义了 `set_bounds()`、`update_slacks()` 和 `close_slacks()`：`set_bounds()` 试图把 slack 成本设为 `max(0, mu[i]-init_value)`，`update_slacks()` 在 slack 仍为正时逐步提高对应成本。这一套看起来像早期尝试的 penalty/box 型 dual stabilization 或人工变量稳定化。

但从调用关系看，旧 VRP 主流程并没有真正使用这套动态 stabilization。全仓搜索只发现 `set_bounds()`、`update_slacks()`、`close_slacks()` 的定义，没有实际调用；`GC.Solve()` 每轮只是 `lp.Solve()`、`lp.GetDual()`、再 `Extend(lp)`。因此旧 VRP 真正进入 pricing 的 dual 基本就是 CPLEX 当前 RMP dual：`mu[i]`、`arc_mu[i][j]` 和 subset-row dual。VRPTW 模式下可能会存在初始高成本 slack 列，但动态调整 slack cost 来稳定 dual 的逻辑没有接入列生成循环。

进一步按更严格口径复核后，`is_zero()` 也同样只有定义没有主流程调用；`add_slacks()` 是唯一被 `Construct()` 接上的 stabilization 相关入口，且只是在 VRPTW 下加静态高成本 artificial slack。也就是说，旧代码最多保留了一个人工可行性/极弱 dual bounding 的残留设计，不能等同于列生成文献里通常说的 stabilized pricing dual。真正的 stabilization 至少应在每轮 RMP 后维护中心 dual、box/penalty 参数或平滑 dual，并用该 dual 指导 pricing；这些更新步骤在旧 `src/BPC/GC/GC.java` 的列生成循环里不存在。

当前 TWET-BPC 没有复用旧 VRP 这套 dual stabilization。`TWETBPC/LP/LP.java` 中只保存 `jobDual`、`machineDual`、`arcDual`，每次 `solveCurrentModel()` 直接从 CPLEX `getDual(...)` 读取后供 pricing 使用；没有历史 dual center、box step、alpha smoothing、penalty slack cost update 等机制。当前和旧 VRP 有关的 slack 复用，主要是子节点 RMP 不可行时的 repair slack：只给当前新增分支行加人工 slack，用其 dual 引导补列，slack 归零后回到正常 RMP。这属于 feasibility repair，不是 dual stabilization。

因此如果后续要做真正的 dual stabilization，需要作为新功能重新设计，而不是认为已经从旧 VRP 迁移过来了。比较稳妥的方向是先记录每轮 dual 波动和 pricing 迭代次数，再决定是否引入 stabilized dual 用于 pricing；实现上要格外小心，因为当前 TWET pricing 还把 dual 用于动态 profitable window 和 completion bound，若用平滑 dual 生成列，需要保证最终仍能用真实 RMP dual 做 reduced-cost 判定，避免错过负列或生成无效窗口。

## 2026-06-02 旧 VRP 分支机制与当前 TWET-BPC 的借鉴关系

复查完整版旧 VRP `D:\软件\Trae\项目\BPC\src\BPC` 后，可以确认旧 `Tree` 中实际按顺序尝试四类分支：`BranchA -> BranchB -> BranchC -> BranchD`。`BranchA` 是车辆数分支，节点中保存 `min_vehicle_number/max_vehicle_number`，RMP 中的第 0 行是车辆数范围约束。`BranchB` 是“每个 customer 被 bid 覆盖的总数”分支，它选一个 bid 覆盖总值为分数的 customer，左支把包含该 customer 的 bid 上界设为 0，右支要求该 customer 的 bid 覆盖总数为 1，并通过禁弧排除与这些 bid 覆盖 customer 冲突的 route。`BranchC` 是单个 bid 分支，选一个分数 bid，左支禁用该 bid，右支强制该 bid，并同样用 bid/customer 关系和 `feasible_arc` 排除冲突 route。`BranchD` 是弧分支，节点用 `feasible_arc[i][j]` 记录弧状态，`0` 表示可行、`-1` 表示禁止、`1` 表示必须；LP 对被分支的弧额外加一行 `brarc,i,j`，并把该行 dual 累加到 `arc_mu[i][j]`，pricing 扩展时跳过 forbidden arc 并扣除对应 arc dual。旧节点还保存 `sudo_cost` 并按它排序，用作分支树队列的伪下界。

当前 TWET-BPC 没有直接搬旧 VRP 的分支类，而是抽成 `Brancher` 接口后重写了其中适用于 TWET 的语义。`MachineCountBrancher` 对应旧 `BranchA`：当当前 master 解的机器使用量为分数时，左子节点设 `maxMachineCount=floor(value)`，右子节点设 `minMachineCount=ceil(value)`。`ArcBrancher` 对应旧 `BranchD`：在当前解中找最接近 0.5 的分数弧，左子节点禁止该弧，右子节点要求该弧；右分支还额外禁止同一真实前驱的其他后继、同一真实后继的其他前驱，以表达调度序列中相邻关系的排他性。旧 `BranchB/BranchC` 依赖 VRP 里的 bid/customer 覆盖结构，当前 TWET 没有同构的 bid 变量，因此没有迁移这两类分支。`Tree` 中的 `pseudoCost` 则对应旧 `sudo_cost`，子节点用父节点 LP objective 作为伪下界，队列按它排序，并可在伪下界不优于 incumbent 时提前剪枝。

当前实现相对旧 VRP 做了两点明显扩展。第一，`TariffSegmentBrancher` 是 TWET 外包 tariff segment 的新分支，旧 VRP 没有对应机制；它优先处理 `z_s` 的分数值，当前分支顺序也是 tariff segment、machine count、arc。第二，当前 `Node.arcState` 只记录显式分支状态，预处理得到的全局不可行弧通过 `data.isPreprocessedArcForbidden()` 过滤，不写入分支状态，也不在 RMP 里建多余分支行。LP 层则和旧 VRP 的弧分支保持同一核心接口：required/forbidden arc 都变成 master 行，`readDuals()` 读取后写入 `arcDual[from][to]`，exact 和 heuristic pricing 都通过 `lp.getArcDual()` 把分支 dual 纳入 reduced cost。需要注意，当前仓库内的 `src/BPC` 是精简拷贝，只保留了机器数和弧分支的主要状态与接口；完整版旧 VRP 里才有独立的 `BranchB/BranchC`。

因此现在真正“借鉴旧 VRP”的不是 tariff 分支，也不是 bid 分支，而是 BPC 分支框架的三件事：节点轻量保存分支状态，RMP 用分支行表达状态并把 dual 传回 pricing，树搜索用 pseudo/sudo bound 排队和预剪枝。具体分支类型上，当前只继承了旧 `BranchA` 的机器数分支和旧 `BranchD` 的弧分支思想；旧 `BranchB/BranchC` 作为 bid 相关分支没有迁移。TWET-specific 的部分是外包 segment 分支、sink/depot 语义下的弧排他处理、以及只给当前新增分支行加 repair slack 的可行性修复。

旧 VRP 的分支对象选择确实偏向最接近 0.5 的分数对象。`Utility.FranCost(value)` 定义为 `abs(value - ((int)value + 0.5))`，`BranchB/BranchC/BranchD` 都在跳过整数值后选 `FranCost` 最小的对象；对于 0-1 的 bid、customer bid 覆盖值和 arc 值，这就是选最接近 0.5 的分数变量。`BranchD` 还有一个顺序细节：先扫描真实 customer 之间的内部弧，只有找不到可分支内部弧时，才扫描 depot/source 弧和到终点的弧。当前 TWET 的 `ArcBrancher` 和 `TariffSegmentBrancher` 也按 `abs(value-0.5)` 选择最接近 0.5 的分数弧/segment，但 `ArcBrancher` 是在同一个循环里扫描 source、job、sink 相关弧，没有保留旧 `BranchD` 的“内部弧优先，端点弧兜底”顺序。`MachineCountBrancher` 没有多对象选择问题，只要总机器使用量是分数就直接按该值分支。

结合当前 TWET-BPC 的变量结构，后续最自然的新增分支是单 job 外包变量分支。当前 LP 已显式保存 `outsourceVars[j]`，整数性检查也会检查 `outsourcingValues[j]` 是否为 0/1，但现有 brancher 顺序只处理 tariff segment、机器数和弧，没有直接处理分数 `y_j`。一个 `OutsourcingJobBrancher` 可以选择最接近 0.5 的分数 `y_j`：左支 `y_j <= 0`，表示 job j 必须由内部列覆盖；右支 `y_j >= 1`，表示 job j 外包。右支若只加 `y_j >= 1`，在 set-covering 语义下仍可能出现内部列同时覆盖该 job 的冗余组合，因此更稳妥的节点状态应同时记录“job j outsourced required”，并让 RMP 初始列过滤、pricing、heuristic pricing 都排除包含该 job 的内部列。这个分支和旧 VRP `BranchB/BranchC` 的共同点是都在处理“某个服务对象是否由某类外部/组合变量承担”，但 TWET 这里的对象是单 job 外包变量，不是 bid 集合。

第二个可考虑但成本更高的是 precedence/order 分支。当前 `ArcBrancher` 固定的是相邻弧 `i -> j`，约束很强；如果 LP 中两个 job 在不同列里呈现明显的先后顺序分歧，也可以分支为 “i 必须在 j 前” 和 “j 必须在 i 前”。这类分支比 arc 分支更弱、更全局，可能减少因为相邻弧过细导致的树膨胀；但它需要在 pricing、heuristic pricing、column compatibility 和 RMP 分支行中都支持序列先后约束，改动面明显大于 `y_j` 分支。列变量本身的 `lambda_r=0/1` 分支不建议优先做，因为它对列生成不友好：禁一条具体 route 容易导致同质 route 重新生成，强制一条 route 又过于局部，通常不如 arc/order 分支稳定。总外包数量或总外包 baseline 分支也可以定义，但当前 tariff segment 已经覆盖了一部分总量结构，优先级低于单 job 外包分支。

本次先只调整 `ArcBrancher` 的候选选择顺序，不改变左右子节点语义。旧 VRP `BranchD` 的选择逻辑是：先在真实 customer-customer 内部弧中找最接近 0.5 的分数弧；如果内部弧没有候选，再扫描 depot/source 与 sink 端点弧。当前 TWET 之前是在同一个循环里统一扫描 `0/job/sink` 相关弧，可能优先选到端点弧。现在改为两阶段扫描：第一阶段只看真实 job 之间的 `i -> j`；第二阶段兜底看 `0 -> j` 和 `j -> sink`。每一阶段内部仍跳过已分支/预处理禁止/整数弧，并按 `abs(value - 0.5)` 选最接近 0.5 的候选。这样更贴近旧 VRP 的 BranchD 经验，也更优先固定序列内部相邻关系。

旧 VRP 没有实现通常意义上的 strong branching。`Tree` 的流程是按 `BranchA -> BranchB -> BranchC -> BranchD` 顺序调用，第一个能分支的规则直接生成左右子节点；`BranchB/BranchC/BranchD` 内部先用 `FranCost` 选一个最接近 0.5 的对象，而不是拿多个候选分别做左右试探。各 `Branch*.UpdateRouteSet()` 里确实会在选定对象后修改当前 LP 分支行并调用 `cplex.solve()`，必要时再加 branch slack 和调用 heuristic/exact column generator 来修复可行列集；但这一步是为了给已选定的左右子节点准备初始 route set 和检查可行性，不会把不同候选的 probe bound 拿来比较，也不会更新候选级 pseudo-cost。子节点的 `sudo_cost` 在分支创建时直接设为父节点当前 `lp.solution_cost`，进一步说明它不是 strong branching 的子节点 bound 评分。
