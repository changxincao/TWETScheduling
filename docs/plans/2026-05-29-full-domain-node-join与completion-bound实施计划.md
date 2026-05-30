# Full-Domain Node Join 与 Completion Bound 实施计划

本文基于 `docs/plans/2026-05-29-completion-bound与node-join分析记录.md` 和当前 `GCBBStyleBidirectionalFullDomain` 的实现，记录下一步实现计划。目标分成两条线：第一条是在 full-domain GCBB 双向 pricing 上复制一个 node join 实验类；第二条是单独实现 completion bound 计算器，按当前 branch-and-price 节点的 dual、分支弧约束和时间窗信息计算 all-cycles 与 2-cycle-free 两类 relaxed bound。两条线先解耦，不在第一版里把 completion bound 直接接入正式 pricing 剪枝。

## 0. 总体流程框架

这次不要直接改现有 `GCBBStyleBidirectionalFullDomain` 的正式对照逻辑，而是拆成两个独立增量。第一个增量是新增一个 full-domain node join pricing 实验类，复用现有 full-domain label 扩展、dominance、top-K 候选池和 debug 复核，只把 label 中间函数拆成“传播函数”和“拼接函数”，并把最终 crossing-arc join 改成同一 job 上的 node join。第二个增量是新增 completion bound calculator，它读取当前 branch-and-price node 的 LP dual、分支弧状态和时间窗信息，单独做 relaxed DP 并输出 bound 与统计；第一版不参与 pricing 剪枝。

因此第一阶段的主调用链保持为：

```text
TWETBPCContext
  -> GCBBStyleBidirectionalFullDomainNodeJoinPricingEngine
  -> GCBBStyleBidirectionalFullDomainNodeJoin.solve(lp)
  -> initialize / forward labeling / backward labeling
  -> nodeJoinAllGroups
  -> finalizeGeneratedColumns
```

第二阶段的 completion bound 主调用链保持独立：

```text
TWETBPCContext 或调试 runner
  -> GCBBCompletionBoundCalculator.compute(lp)
  -> computeAllCyclesBound
  -> computeNoTwoCycleBound
  -> CompletionBoundResult
```

这样安排的原因是 node join 会影响“生成列的方式”，completion bound 会影响“剪枝依据”。两者如果同时接入，后续结果变快或变慢时很难判断原因。第一版先要求 node join 自己能复现 arc join 的目标、bound 和 reduced-cost 复核；completion bound 自己能给出计算时间、状态数、函数段数和 bound 强度。等两个模块都稳定后，再讨论是否把 bound 接到 reachable set、label 插入前剪枝或 final join 剪枝。

## 1. Full-Domain Node Join Pricing 新类

### 1.1 新增类与入口

复制 `src/TWETBPC/GC/GCBBStyleBidirectionalFullDomain.java` 为一个新实验类，建议命名为 `GCBBStyleBidirectionalFullDomainNodeJoin`。该类保持 full-domain 标签、top-K 候选池、active label table、debug column check 和现有动态 pricing window 逻辑不变，只替换 label 状态字段和 final join 方式。

同步新增一个 `GCBBStyleBidirectionalFullDomainNodeJoinPricingEngine`，并在 `TWETBPCConfig` 中增加实验开关，例如：

```java
public boolean useGCBBFullDomainNodeJoinBidirectionalPricing = false;
```

`TWETBPCContext` 的双向 pricing 选择顺序建议放在 asymmetric 和 full-domain arc join 之前或之间，具体顺序为：

1. asymmetric 实验开关；
2. full-domain node join 实验开关；
3. full-domain arc join 对照开关；
4. GCNGBB-style 正式入口；
5. 原 bidirectional 入口。

这样 node join 与现有 full-domain arc join 可以独立对比，不影响默认正式流程。

### 1.2 Label 状态定义

当前 full-domain 类的 `FunctionLabel.frontier` 同时承担“扩展函数”和“join 函数”。Node join 不能继续这样用，因为同一个 join node 的惩罚成本和 job dual 会被两边重复计入。新类中保留 `frontier` 作为传播函数，同时增加拼接函数字段。

Forward label：

```text
frontier = F_state
preNodeFrontier = U_state
```

其中 `F_state` 是已经包含当前 node 成本并做过 prefix-min 的函数，用于继续向后扩展和 dominance；`U_state` 是来源 `F_parent` 平移到当前 node 后，加 incoming setup/arc reduced cost、但不加当前 node 惩罚成本和 job dual 的函数，用于 node join。

Backward label：

```text
frontier = B_state
```

第一版不额外修改 backward label。当前 backward 的 `frontier` 已经是包含当前 job 成本和 suffix-min 的后缀函数，原 crossing-arc join 也是直接使用该函数拼接。为了减少改动面，node join 第一版也先用 `backward.frontier` 与 forward 的 `preNodeFrontier` 拼接。若后续发现 suffix-min 语义导致 node join bound 过松或 reduced-cost 复核不稳定，再补 `exactSuffixFrontier/Bbar_state`。

这次只改复制出来的新类。原 `GCBBStyleBidirectionalFullDomain` 保持 crossing-arc join 对照语义不变。

### 1.3 Forward 扩展流程

当前 `extendForward()` 的核心是：

```text
shift(F_i, setup(i,j)+p_j)
+ penalty_j
+ setupCost(i,j) - jobDual(j) - arcDual(i,j)
normalize(FORWARD)
```

新类要把中间函数拆开：

```text
U_candidate_j(t)
  = shift(F_i, setup(i,j)+p_j)(t)
  + setupCost(i,j)
  - arcDual(i,j)

Fbar_candidate_j(t)
  = U_candidate_j(t)
  + penalty_j(t)
  - jobDual(j)

F_candidate_j
  = prefixMin(Fbar_candidate_j)
```

实现时注意不要重复计算平移函数。`PiecewiseLinearFunction.shiftX()` 平移后会调用 `trimToDomain()`，因此超出当前定义域右侧的物理段会被裁掉。推荐顺序是：

```text
shifted = F_i.shiftX(delay)
U_candidate = shifted 加 incoming setup reduced cost
Fbar_candidate = U_candidate + penalty_j - jobDual(j)
F_candidate = prefixMin(Fbar_candidate)
```

也就是说，先得到一次平移结果，再在这个结果上形成 `U_candidate`；随后用同一个 `U_candidate` 加当前 job 的 penalty 和 job dual 得到传播函数。这样既保证 `U_state` 和 `F_state` 口径一致，也避免对父 label frontier 做两次 `shiftX()`。

构造 `ForwardLabel` 时保存：

```text
preNodeFrontier = U_candidate_j
frontier = F_candidate_j
```

source label 的 `frontier` 仍使用当前 source frontier 并扣 machine dual；source 的 `preNodeFrontier` 可以直接指向同一函数或一个 copy，因为 source 不参与 node join 生成真实任务列。为了减少特殊分支，建议构造 source 时也填充非空 `preNodeFrontier`。

这里还有一个与 `Tmid` 有关的关键变化。当前 full-domain 对照类虽然 label 函数定义在 `[0, pricingHorizon]`，但 `isDirectForwardExtensionTimeFeasible()` 仍要求一跳后的 earliest completion 不超过 `tMid`，`buildForwardReachableSet()` 也依赖这个判断。因此现有 `reachableSet` 只覆盖“还能继续作为 forward half 扩展”的候选，不覆盖第一次跨过 `Tmid` 的 join node。

Node join 需要把“第一次跨过 `Tmid` 的 forward child”物化出来，用它的 `preNodeFrontier` 去和同 job 的 backward label 拼接。第一版建议把 forward child 分成两类：

```text
earliestCompletion <= tMid:
    插入正常 dominance table，并入队继续扩展

earliestCompletion > tMid 且 earliestCompletion <= job window/horizon:
    只作为 node-join terminal label 保存，不入队，不继续扩展
```

这样扩展深度和 dominance 主体仍接近原 half-way 逻辑，但 node join 拥有必要的跨界 join node。实现上不能只枚举 `label.reachableSet`，因为它已经被 `tMid` 裁过；需要新增一个 forward terminal candidate helper，按当前 label、visited、zero-dual、job penalty 和 direct full-domain feasibility 生成“可跨界但不扩展”的候选。

### 1.4 Backward 扩展流程

第一版 backward 扩展流程尽量不动。当前 `extendBackward()` 已经负责从 sink root 或后继 label 递推 backward `frontier`，并在最后执行 `normalize(Direction.BACKWARD)`。Node join 只需要有同 job 的 backward suffix 函数参与拼接，而当前 `backward.frontier` 已经可用。

因此暂时不新增 `exactSuffixFrontier`，也不改变 backward dominance、reachable set 和入队规则。这样 node join 新类的主要风险集中在 forward 侧 `preNodeFrontier` 和 final join 方式上，调试面更小。

### 1.5 Node Join 替换 Crossing-Arc Join

原 final join 以 forward terminal job `i` 和 backward first job `r` 枚举 crossing arc `(i,r)`。新类改为按同一个真实 job `i` 分组：

```text
for each job i:
    for each forward label ending at i:
        for each backward label starting at i:
            tryNodeJoin(forward, backward, i)
```

Node join 的函数计算为：

```text
joinCost_i(t) = forward.preNodeFrontier(t) + backward.frontier(t)
reducedCostBound = min_t joinCost_i(t)
```

这里不再加 crossing arc 的 setup cost 或 arc dual，因为 incoming arc 已经包含在 forward 的 `U_state` 中，join node 自身成本和后缀由 backward 的 `frontier` 负责。这样 join node 成本只计算一次。

### 1.6 集合兼容性与序列恢复

Node join 时 forward 和 backward 都包含 join node `i`，因此不能继续使用当前 arc join 的：

```java
forward.visitedSet.intersects(backward.visitedSet)
```

需要改成“除 join node 外不相交”。建议新增局部 helper：

```text
intersectsExceptJoin(forward.visitedSet, backward.visitedSet, joinJob)
```

分支弧兼容性仍然靠 label 扩展阶段的 `Node.isArcForbidden()` 保证。Node join 本身没有 crossing arc，所以不需要检查 `node.isArcForbidden(i,r)`。但恢复出的完整序列仍建议在 `Configure.debugBPCPricingColumnCheck` 下走现有 `isSequenceCompatible()` 做最终复核。

序列恢复时，当前 `recoverForwardSequence(forward)` 和 `recoverBackwardSequence(backward)` 都包含 join job。Node join 需要去掉 backward 序列的第一个 job：

```text
sequence = forwardSequence + backwardSequence[1..]
```

如果 backward suffix 只有 join node，则追加为空。

### 1.7 剪枝与统计

第一版先保留简单、稳妥的剪枝：

1. job 级别跳过空 forward/backward group；
2. zero-dual job 若已有统一排除逻辑，沿用；
3. pair 级别做 visited set 除 join node 外相交检查；
4. 函数为空或最小 reduced cost 非负则剪掉。

原 crossing-arc join 的 `minForwardEll + delay <= backward.tail.end`、`joinFixedReducedCost`、arc forbidden 等剪枝不再适用，不能直接搬到 node join。可以新增 node join 统计字段，例如：

```text
nodeJoinGroupsScanned
nodeJoinPairsTried
nodeJoinPairsSetPruned
nodeJoinFunctionEvaluations
nodeJoinFunctionPruned
```

第一版以正确性和可对比日志为主，不急着做复杂 lower-bound group pruning。

### 1.8 验证标准

第一阶段完成后至少验证：

1. focused `javac` 能编译新增类、engine、配置入口；
2. 在 `Configure.debugBPCPricingColumnCheck=true` 下跑一个小样例，确保 node join 推断 reduced cost 与 evaluator 复核一致；
3. 与 full-domain arc join 在 wet020 或同类小样例上比较：目标值、bound、valid 结果应一致；
4. 输出统计至少包含 node join pair 数、函数评价数和生成候选数，便于判断是否真的减少 crossing-arc 枚举。

## 2. Completion Bound 独立计算器

### 2.1 新增类范围

新增一个独立类，建议命名为：

```text
src/TWETBPC/GC/GCBBCompletionBoundCalculator.java
```

它不作为 pricing engine 返回列，也不直接修改 `LP` 或剪枝 label。第一版只负责根据当前 `LP` 和 BPC `Node` 计算 relaxed completion bound，并通过结果对象暴露统计信息。这样可以先确认函数型 DP 的语义和数值，再决定是否接入正式 pricing。

建议类接口为：

```java
public final class GCBBCompletionBoundCalculator {
    public CompletionBoundResult compute(LP lp);
}
```

结果对象包含 all-cycles 与 2-cycle-free 两类结果：

```text
forwardAll[j], backwardAll[j], nodeJoinAll[j]
forwardNoTwoCycle[h][j], backwardNoTwoCycle[j][k], nodeJoinNoTwoCycle[j]
statistics
```

具体数组形态可在实现时根据内存选择压缩，但语义上先按这个结构设计。

### 2.2 当前 BPC 节点信息的使用

计算器应使用当前 `LP` 中的信息：

1. `lp.getNode()` 的 `isArcForbidden(from,to)`，包括分支禁弧和 required arc 转化出的竞争弧禁用；
2. `lp.getJobDual(job)`；
3. `lp.getArcDual(from,to)`；
4. `lp.getMachineDual()` 是否计入 source 初始函数；
5. 当前 active cut dual 第一版不纳入 relaxed completion bound，保持与现有 no-cut pricing 分支一致；若后续要把 cut dual 纳入 bound，需要单独设计 cut-state 或保守处理。

profit window、zero-dual job 排除、full-domain penalty 裁剪可以优先复用 full-domain pricing 里的预计算逻辑，避免 completion bound 与 pricing 使用不同的有效时间域。

### 2.3 All-Cycles Bound

All-cycles 版本每个 node 一个 forward 状态、一个 backward 状态，不记录 visited set，也不记录路径。它是 relaxed DP，不生成列。

Forward 状态：

```text
U_i(t)
F_i(t) = prefixMin(U_i(t) + g_i(t))
```

扩展 `i -> j`：

```text
U_candidate_j = shift(F_i, setup(i,j)+p_j) + setupCost(i,j) - arcDual(i,j)
F_candidate_j = prefixMin(U_candidate_j + g_j - jobDual(j))
```

更新：

```text
U_j = lowerEnvelope(U_j, U_candidate_j)
F_j = lowerEnvelope(F_j, F_candidate_j)
```

只有 `F_j` 改善时，`j` 重新入队。若只有 `U_j` 改善，保留 `U_j`，但不入队。

Backward 状态同理维护：

```text
Bbar_i(t)
B_i(t) = suffixMin(Bbar_i(t))
```

`Bbar_i` 用于 node join，`B_i` 用于继续向左传播。

### 2.4 2-Cycle-Free Bound

2-cycle-free 版本使用 last-arc state。Forward 状态可以记为 `(h,i)`，表示当前在 `i`，直接前驱为 `h`。扩展到 `j` 时禁止 `j == h`：

```text
state (h,i) -> (i,j), j != h
```

每个状态仍维护：

```text
U_{h,i}(t)
F_{h,i}(t)
```

Backward 方向可对称使用 `(i,k)`，表示当前在 `i`，直接后继为 `k`，向左扩展 `h -> i` 时禁止 `h == k`。

用于 node join 的 2-cycle-free bound 有两种松弛口径：

1. 严格 no-2-cycle 拼接：forward 的前驱 `h` 与 backward 的后继 `k` 也要满足 `h != k`；
2. 更松的 pruning bound：拼接处不检查 `h != k`，只取更乐观 bound。用于正式 elementary pricing 剪枝时更松不会错误剪枝，只会弱一些。

第一版建议两种都算，统计里分别命名，例如：

```text
nodeJoinNoTwoCycleStrict
nodeJoinNoTwoCycleRelaxed
```

这样后续实验能比较严格 bound 与松弛 bound 的强度差异。

### 2.5 与 Pricing 的集成顺序

不要第一版就把 completion bound 接入 `GCBBStyleBidirectionalFullDomainNodeJoin` 的剪枝。推荐顺序：

1. 先实现 node join pricing 新类，确保生成列正确；
2. 再实现 completion bound calculator，单独在一个调试 runner 或 pricing 开始处计算并输出统计；
3. 对几个样例记录 bound 计算时间、状态数、函数段数、nodeJoin bound 强度；
4. 确认 bound 方向和数值稳定后，再决定接入哪里剪枝，例如 reachable set 构造、label 插入前 lower-bound prune、或 final join prune。

这样可以避免同时引入“join 改写”和“completion bound 剪枝”两个变量，定位问题更清楚。

## 3. 实施顺序

1. 新增 full-domain node join pricing 类和 engine，只改 final join 与 label 附加函数字段。验证目标是列 reduced cost 复核一致。
2. 增加 config/context 实验入口，默认关闭。
3. 对 node join 类补统计输出，和 full-domain arc join 在相同样例上对比。
4. 新增 completion bound calculator 的 all-cycles 版本，先只输出统计，不接入剪枝。
5. 增加 2-cycle-free 状态版本，同时输出 strict 与 relaxed node join bound。
6. 形成实验记录：比较 full-domain arc join、full-domain node join、completion bound 计算时间和 bound 强度。

## 4. 当前风险和待确认点

1. `PiecewiseLinearFunction` 是否已有稳定的 lower envelope/min 操作需要确认；如果没有，不能用字符串或手写段拼接临时代替，应在公共函数工具里补一个可靠方法。
2. `U_state` 和 `F_state` 的 dominance 关系第一版不改：dominance 仍以现有 `frontier` 传播函数为准。这样最小改动，但可能丢掉某些只对 node join 有价值、但被 `F_state` dominance 支配的 `U_state`。如果实测发现 node join bound 偏弱，需要重新审视 dominance 是否也要考虑 `U_state`。
3. cut dual 暂不进入 completion bound。若后续要在 active cuts 下使用 bound 做剪枝，必须证明忽略 cut dual 仍是安全 lower bound，或扩展状态记录 cut 相关信息。
4. Node join 减少 crossing-arc 枚举只是实验假设。实际速度取决于同一 node 上 forward/backward label 数、集合兼容检查和函数求最小的成本，需要统计验证。

## 5. Arc Join 阶段计时基线

2026-05-29 先在 `GCBBStyleBidirectionalFullDomain` 中补充 pricing 内部阶段计时，分别记录 forward 扩展、backward 扩展和最终 compact+crossing-arc join 阶段。该计时不改变搜索逻辑，只用于判断 node join 是否值得优先做。

`wet020_001_2m` 的 full-domain exact pricing 一轮总耗时约 `382.631 ms`。其中 forward 扩展 `132.840 ms`，backward 扩展 `57.345 ms`，join phase `90.676 ms`；在“扩展+join”这部分中 join 占 `32.28%`，若按整个 exact pricing 调用时间算约 `23.69%`。因此该例扩展阶段更长，主要是 forward 扩展和 dominance 相关工作。

`wet021_001_2m` 有两轮 full-domain exact pricing。第一轮总耗时约 `950.428 ms`，forward/backward 扩展合计 `628.191 ms`，join `202.080 ms`，join 在“扩展+join”中占 `24.34%`；第二轮总耗时约 `375.961 ms`，扩展合计 `270.331 ms`，join `103.230 ms`，join 占 `27.63%`。两轮合计看，join 约 `305.310 ms`，扩展约 `898.522 ms`，join 在可解释的扩展+join 阶段中约 `25.36%`，按 exact 调用总时间约 `23.02%`。

当前小样例结论为：full-domain arc join 不是唯一主耗时，扩展尤其 forward 扩展更长；但 join 已经稳定占到约四分之一到三分之一的核心 pricing 时间。node join 如果只减少 final join，不应期待数量级加速；真正有价值的情形应是它同时减少 crossing-arc pair/function evaluation，或配合 completion bound 提前剪掉一部分 label/候选。

## 6. Node Join 第一版实现记录

2026-05-29 已新增 `GCBBStyleBidirectionalFullDomainNodeJoin` 和 `GCBBStyleBidirectionalFullDomainNodeJoinPricingEngine`，并通过 `TWETBPCConfig.useGCBBFullDomainNodeJoinBidirectionalPricing` 接入 `TWETBPCContext`。比较 runner `GCBBFullDomainComparisonTest` 增加 `nodeJoin` 模式，便于和现有 full-domain arc join 单独对比。

实现中 forward label 增加 `preNodeFrontier`，对应前面定义的 `U_state`。扩展时先对父 `frontier` 做一次 `shiftX(delay)`，再原地加入 incoming setup cost 和 incoming arc dual，形成 `preNodeFrontier`；随后在同一个中间函数上加当前 job penalty 并扣 job dual，再做 forward normalize 得到继续传播用的 `frontier`。这样避免了对父函数重复平移，也让 node join 使用的拼接函数和传播函数来源一致。

2026-05-30 重新分析后，backward 侧不再保存 `exactSuffixFrontier`。原因是 node join 可以看成 crossing-arc join 的等价改写：先把 forward label 从 `i` 扩展到同一个 join job `j`，得到不含 `j` 自身 job penalty/job dual 的 `preNodeFrontier/U_j`；再与 backward label `B_j` 拼接。这里的 `B_j` 本来就是 suffix-min 后的传播函数，表示 `j` 的完成时间不早于当前时间时的最优后缀成本，正好对应原 arc join 中 backward label 的使用方式。因此 backward label 继续只维护原 `frontier`，扩展、dominance、reachable set 和入队逻辑都不额外增加字段。

关于 `Tmid`，node-join 类的 `reachableSet` 不再沿用 full-domain crossing-arc 对照版里的 half-way 裁剪口径，而是表示当前 label 的 full-domain 一跳可达候选。扩展时统一从 `reachableSet` 枚举 child，然后用 child 生成后的边界位置决定后续动作：forward child 的最早完成时间若不超过 `Tmid`，正常进入 dominance table 并入队；若已经超过 `Tmid`，只作为 terminal label 保存给 node join，不再入队。backward 侧对称处理，第一次跨到 `Tmid` 左侧的 suffix label 只参与 node join，不继续向左扩展。这样 `Tmid` 不再写入 reachable set 的构造条件，只作为“是否继续扩展”的出队边界。

final join 已从 crossing-arc join 改为同 job node join。流程按 join job 分组，只拼接 `activeForwardByLastJob[j]` 与 `activeBackwardByFirstJob[j]`，集合兼容性检查改为“除 join job 外不相交”，恢复序列时去掉 backward 序列开头重复的 join job。node join 函数用 `forward.preNodeFrontier + backward.frontier` 求最小值，不再额外加入 crossing arc setup cost 或 arc dual。

当前第一版为了保证返回列成本严格可靠，`tryGenerateColumn()` 对 node join 和 forward->sink 候选统一调用 `TWETColumnEvaluator` 复核真实成本，并用恢复序列重新计算 reduced cost 后再放入候选池。相应地，原 crossing-arc join 中基于函数最小值的 group/pair lower-bound 剪枝暂时不作为安全剪枝使用。这样做的代价是性能明显下降：此前 `wet020_001_2m` nodeJoin 安全版可得到 `obj=bound=6343` 且 `valid=true`，但 exact pricing 时间约 `20.920s`；日志中 join phase 约 `20.618s`，joinMeasuredShare 约 `98.84%`。2026-05-30 改回 `preNodeFrontier + backward.frontier` 后，用 `debugBPCPricingColumnCheck` 试跑 `wet020_001_2m` 仍出现大量 `inferred > checked` 的 reduced-cost mismatch，并因输出过多手动停止。因此当前确认 `exactSuffixFrontier` 不是必要方向，但 node join 函数值仍不能用于剪枝，下一步应查 forward `preNodeFrontier` 与原 arc join 扩展项是否完全一致。

### 6.1 对三个实现口径的补充澄清

关于 backward 是否需要 `exactSuffixFrontier`：最新判断是不需要。若把原 crossing-arc join 写成 `F_i` 先扩展到 `j`，那么得到的就是 `U_j(t)`，即“不晚于 `t` 完成 join job 前置部分”的最小成本；而 backward 的 `frontier` 是 `B_j(t)=min_{u>=t}` 的 suffix-min 后缀成本。由于等待不会增加额外成本，`U_j(t)+B_j(t)` 等价于在同一个 node `j` 上选择一个可同步的完成时间完成拼接。因此直接使用 `backward.frontier` 才是与原 arc join 对齐的口径，额外保存 normalize 前的中间函数反而引入了不必要的语义差异。

关于 terminal 候选和 `reachableSet` 的关系：更清楚的实现不是在 `reachableSet` 外再扫一遍候选，而是直接修改 node-join 类的 reachable set 语义。`reachableSet` 负责记录 full-domain 一跳可达候选；是否跨过 `Tmid` 不在这里判断。跨界限制只发生在 child 生成后：第一次跨界的 child 保留为 terminal label，且不入队，因此不会出现第二次、第三次跨界扩展。这样既保留了 node join 需要的跨界 join node，也不会把 forward/backward 搜索真正放宽到整个 horizon 多步扩展。

关于当前版本为什么慢：安全版仍保留 evaluator 复核，主要是为了在口径调整期间不让错误成本进入列池。现在已把 node join 函数恢复为 `preNodeFrontier + backward.frontier`，但 debug 试跑显示 inferred reduced cost 与 checked reduced cost 仍不一致，且多数样本为 inferred 高于 checked；这意味着若把 inferred 当作负列判断或 lower-bound 剪枝，会有漏掉真实负列的风险。后续应继续检查 `preNodeFrontier` 是否完整对应 arc join 中 `F_i` 扩展到 `j` 的那一项、node join 的时间公共域是否正确，以及 final sequence 恢复是否和函数拼接的两侧 label 完全一致，而不是继续引入 backward 侧额外状态。
### 6.2 zero-dual 排除口径统一

2026-05-29 继续统一所有 GC pricing 中的 `pi_j=0` 处理。触发条件保持为根节点且当前没有 active cuts，即 `node.depth == 0 && lp.getActiveCutIds().isEmpty()`；该条件下不再采用早期的 singleton-only 口径，而是把这些 job 当成本轮 pricing 的 excluded job。

实现口径改为：预处理阶段仍计算 `zeroDualExcludedJobs`；初始化 source label 和 sink root label 时，直接把这些 job 加入各自的 `visitedSet`。之后 reachable set 构造不再显式判断 `!isZeroDualExcludedJob(job)`，只按父 reachable set、visited 和 direct feasibility 过滤。这样 zero-dual 的排除入口统一在 source/sink，后续扩展和占优仍走普通 label 流程，避免每个扩展点重复写一套规则。

双向 join 需要额外处理一个细节：source 和 sink 都会共同预标记 excluded job，因此这些标记不是路径真实访问，不能让 `visitedSet` 交集检查误判为 forward/backward 路径冲突。普通 arc join 类改用 `visitedSetsIntersectForJoin()`，node join 类的 `intersectsExceptJoin()` 也忽略 excluded 标记；真实冲突仍然按非 excluded job 判断。当前已同步到 `GC`、`GCBidirectional`、`GCNGBBStyleBidirectional`、`GCBBStyleBidirectionalFullDomain`、`GCBBAsymmetricBidirectional` 和 `GCBBStyleBidirectionalFullDomainNodeJoin`。

### 6.3 terminal label 的占优处理

2026-05-30 按最新讨论修正 node join terminal label 的插入语义。第一次跨过 `Tmid` 的 forward/backward child 不再绕过普通 dominance table，而是和普通 label 一样先调用 `insertForward()` / `insertBackward()` 进入 `FWTL` / `BWTL` 做占优判断。若被保留，它会正常登记到 active label list，后续参与 node join；区别只在于调用方不把它加入 `FWUL` / `BWUL`，因此不会继续向后或向左扩展。

这个口径等价于“普通 label：dominance table 保留后进入 active list 并入队；terminal label：dominance table 保留后进入 active list 但不入队”。这样 terminal label 的支配关系和旧 label 逻辑保持一致，不再引入单独的旁路 store 或特殊支配规则。已有 active list 中被后续 terminal label 支配的旧 label 仍按原流程延迟清理，在 final join 前由 `compactAndSortActiveLabelListsForJoin()` 过滤。

### 6.4 node join mismatch 的分类诊断结论

2026-05-30 对 `GCBBStyleBidirectionalFullDomainNodeJoin` 的 debug mismatch 输出做了分类，不再把所有正 reduced-cost 候选逐条打印。新的统计按候选来源区分 `NODE_JOIN` 和 `FORWARD_SINK`，并把 mismatch 分为三类：两边都是负 reduced cost、符号临界不一致、两边都是非负 reduced cost。这里真正会影响列池和剪枝安全的是“符号临界不一致”，即一边认为是负列而另一边认为不是负列。

带 CPLEX classpath 对 `wet020_001_2m` 运行 `debugBPCPricingColumnCheck=true` 后，root 节点 exact pricing 完整跑完，结果仍为 `obj=bound=6343` 且 `valid=true`。本轮统计为：

```text
debugRcMismatch total/nodeJoin/forwardSink/bothNeg/signCritical/positiveOnly/maxAbs=
542786/542238/548/0/0/542786/2357.999999999989
```

因此这次刷屏的直接原因不是 node join 公式会把负列算错，而是 debug 复核在安全版里对大量不会进入列池的正候选也比较了函数推导 reduced cost 和 `TWETColumnEvaluator` 复核 reduced cost。所有 mismatch 都落在 `positiveOnly`，没有 `bothNeg`，也没有 `signCritical`。换句话说，当前证据没有发现“真实负列被 inferred 算成非负”或“inferred 负列但 evaluator 非负”的情况。

这些正候选为什么数值不完全一致，主要是口径差异造成的：pricing 侧启用了 root/no-cut 下的 dual profitable window，`dualWindow=enabled`，本轮有效时间域被收紧到 `dynamicHStartMin=512.9999999999998`、`pricingHorizon=1188.0000000000007`；而 `TWETColumnEvaluator` 是按恢复出的完整 sequence 在原始目标函数口径下重新求序列成本。profitable window 的作用是保证负 reduced-cost 的最优候选不会被删掉，不保证任意一个正 reduced-cost sequence 的函数值和原始 evaluator 值逐点一致。因此正候选上出现 `inferred > checked` 是预期内的诊断噪声，不应据此判断 node join 公式错误。

当前结论相应修正为：`preNodeFrontier + backward.frontier` 的 node join 公式在本轮 sign 层面没有暴露错误；`exactSuffixFrontier` 仍不需要恢复。短期仍保留 evaluator 复核返回列，因为函数值和 evaluator 的全量数值口径尚未做到逐候选一致，不能直接把 inferred 用作通用列成本。但如果只讨论是否会错过负列，本次分类结果显示该问题没有在 `wet020_001_2m` root 节点复现。后续若要把 inferred 接回剪枝，需要继续在更多样例上统计 `signCritical` 是否始终为 0，而不是看正候选的绝对数值差。

这里的关键区别是：dual profitable window 不是保持每一条固定 sequence 成本不变的等价变换，而是一个用于 pricing 搜索的负列保留规则。对单个 job 来说，若完成时间落在动态窗口外，则该 job 的原始惩罚成本已经不低于当前 job dual，`penalty_j(t)-pi_j` 对 reduced cost 不再提供负贡献。在 root/no-cut 且 reduced setup cost 仍满足三角不等式的假设下，这类完成时间不会构成真正负列的必要部分，因此 pricing 可以把它从函数域里裁掉，以减少搜索。这样做只保证“负 reduced-cost 最优列不被裁掉”，不保证一个已经被判定为正的固定 sequence 在裁剪前后的最优时间和 reduced cost 完全一致。

因此正候选出现偏差并不矛盾：evaluator 会在原始完整时间域上重新优化该 sequence，可能选到某些 dynamic window 外、但对这条固定正 sequence 更便宜的完成时间；pricing inferred 则只能在 effective window 内评价，所以数值可能更高。由于两边结果都仍是非负 reduced cost，这种差异不影响列池。负成本候选没有在本轮出现偏差，是因为一旦一条 sequence 真能形成负 reduced cost，它的获利完成时间应落在每个相关 job 的 profitable window 内；窗口裁剪没有改变其负列判定。这也是后续判断安全性的核心指标应看 `signCritical`，而不是看所有正候选的数值差。

### 6.5 本轮 node join 讨论的完整沉淀

本轮围绕 full-domain node join 的主要问题，是确认 crossing-arc join 是否可以改写为同一个 job 上的 node join，以及这个改写在现有 label 定义、`Tmid` 边界、dominance、reachable set 和 debug 复核口径下是否仍然安全。最终结论是：当前 `GCBBStyleBidirectionalFullDomainNodeJoin` 的拼接口径已经和原始 arc join 等价，作为实验分支可以认为列生成语义正确；但函数推导值暂时仍只作为诊断值，不直接替代 evaluator，也不直接用于剪枝。

1. node join 的公式口径

原 crossing-arc join 可以写成：

```text
F_i(t - Delta_ij) + cbar_ij + B_j(t)
```

其中 `F_i` 是 forward prefix-min 后的传播函数，`B_j` 是 backward suffix-min 后的后缀传播函数，`Delta_ij = setup(i,j) + p_j`，`cbar_ij = setupCost(i,j) - arcDual(i,j)`。如果先把 forward label 从 `i` 扩展到 `j`，但不加入 `j` 自己的 penalty 和 job dual，就得到：

```text
U_j(t) = F_i(t - Delta_ij) + cbar_ij
```

于是原来的 crossing-arc join 等价于：

```text
min_t { U_j(t) + B_j(t) }
```

当前代码中的 `ForwardLabel.preNodeFrontier` 正是这个 `U_j(t)`。`extendForward()` 里先对父 `frontier` 做一次 `shiftX(delay)`，再加入 incoming setup cost 和 incoming arc dual，形成 `preNodeFrontier`；之后才把 job penalty 和 job dual 加进去，normalize 成继续传播用的 `frontier/F_j`。这个顺序避免了 join job 的 penalty/job dual 被 forward 和 backward 两侧重复计算。

2. backward 不需要 `exactSuffixFrontier`

讨论中一度考虑过给 backward label 额外保存 normalize 前的后缀函数，但后来确认这是不必要的。node join 不是要把 backward 侧还原成“精确完成在 t 的后缀成本”，而是要复用原 arc join 中已经使用的 `B_j(t)` 语义：当 join job 的完成时间不早于当前时间界限时，后续后缀可取得的最小 reduced cost。这个函数本来就是 backward `frontier` 经 suffix-min 后的结果。

因此正确拼接就是：

```text
forward.preNodeFrontier + backward.frontier
```

不应再引入 `exactSuffixFrontier`。额外保存 normalize 前函数不仅增加状态和修改面，还会把 node join 的语义从“arc join 的等价改写”转到另一个口径上，反而更容易造成双计数或时间同步误解。当前代码已删除该字段，并保持 backward 扩展、dominance、reachable set 和入队逻辑与普通 full-domain 流程一致。

3. `Tmid` 的作用：只控制是否继续扩展，不写入 reachable set

node join 版本和原 full-domain crossing-arc 对照版的一个关键区别是，第一次越过 `Tmid` 的 label 仍然有价值。因为 node join 需要同一个 join job 上两侧都包含该 job，如果 forward child 的最早完成时间刚越过 `Tmid`，它不能继续向右扩展，但仍然应该保留为 terminal label 参与 node join；backward 侧对称，第一次跨到 `Tmid` 左侧的 suffix label 也应保留为 terminal suffix。

因此当前实现把 `reachableSet` 改成 full-domain 一跳可达候选，不再在 reachable set 构造时用 `Tmid` 裁掉第一次跨界 job。扩展流程是：从父 label 的 `reachableSet` 枚举候选，生成 child，生成后再看 child 的边界位置。如果 forward child 的 `earliestForwardCompletion(child) > tMid`，或 backward child 的 `latestBackwardCompletion(child) < tMid`，则该 child 进入 terminal 路径，只保留到 active list 供 final join 使用，不再进入 `FWUL/BWUL` 继续扩展。

这个口径避免了两类错误：一是 reachable set 过早裁剪导致缺少 node join 必需的跨界 join node；二是完全放开 full-domain 多步扩展导致 label 数量膨胀。`Tmid` 仍然存在，但语义变成“是否继续入队扩展”的边界，而不是 reachable set 的可达性判定条件。

4. terminal label 仍按普通 dominance 处理

讨论中明确 terminal label 不应绕过普通 dominance table。最终实现是：

```text
普通 label: dominance table 保留后 -> active list -> enqueue
terminal label: dominance table 保留后 -> active list -> 不 enqueue
```

也就是说，terminal label 和普通 label 使用同一套 `FWTL/BWTL` 占优规则；区别只在于保留后是否继续入队扩展。这样做的好处是 dominance 语义不分裂，terminal label 仍可以支配旧 active label，也可以被普通 label 或其他 terminal label 支配。final join 前仍通过 `compactAndSortActiveLabelListsForJoin()` 清掉已经被后续 label 支配的旧条目。

这里不需要额外引入“terminal-only store”，也不需要把 `preNodeFrontier/U_state` 放进正式 dominance 定义。正式扩展仍然只看传播函数 `frontier/F_state` 和 reachable set；`preNodeFrontier` 是 node join 辅助函数，不改变继续扩展的 dominance 口径。当前没有证据表明必须为了 `U_state` 单独维护一套支配规则。

5. visited、reachable 和 zero-dual 的关系

当前 reachable set 的更新仍从父节点的 `reachableSet` 出发做单调过滤，而不是每次重新扫描所有 job。新 child 的 reachable set 只保留父 reachable 中尚未 visited、并且满足 full-domain 一跳时间可行性的 job。visited set 记录路径已经访问过的真实 job，以及 root/no-cut 条件下预先排除的 zero-dual job。

zero-dual 的统一口径是：只有 `node.depth == 0 && lp.getActiveCutIds().isEmpty()` 时启用，预处理出 `pi_j=0` 的 job 后直接加入 source 和 sink 的初始 visited set。之后扩展点不再重复判断 zero-dual，reachable set 也不额外写一套 zero-dual 条件。双向 join 的 visited 交集检查要忽略这些 source/sink 共同预标记的 excluded job，因为它们不是路径真实访问，只是本轮 pricing 的排除标记。

6. debug mismatch 的最终解释

删除 `exactSuffixFrontier`、改回 `preNodeFrontier + backward.frontier` 后，开启 `debugBPCPricingColumnCheck` 曾出现大量 `inferred > checked` 的 reduced-cost mismatch。分类统计后确认，这些 mismatch 全部是 positive-only：

```text
debugRcMismatch total/nodeJoin/forwardSink/bothNeg/signCritical/positiveOnly/maxAbs=
542786/542238/548/0/0/542786/2357.999999999989
```

这说明没有发现“真实负列被 inferred 算成非负”，也没有发现“inferred 负但 evaluator 非负”的 sign-critical 情况。刷屏的直接原因是安全版会把大量正 reduced-cost 候选也送入 evaluator 复核，而 pricing 侧启用了 root/no-cut 下的 dual profitable window；该 window 是负列保留规则，不是固定 sequence 成本等价变换。它保证不会裁掉真正可能产生负 reduced-cost 的最优时间区域，但不保证每条正 sequence 在 effective window 内的函数值和 evaluator 在原始完整时间域上的值逐点一致。

因此当前判断是：node join 公式和代码拼接口径没有在负列符号层面暴露错误，之前的 mismatch 主要是正候选上的诊断噪声。短期仍保留 evaluator 复核返回列，避免中间函数口径在更多样例上未完全验证前污染列池；但从本轮证据看，`preNodeFrontier + backward.frontier` 是正确方向。

7. 当前代码状态和后续边界

当前 `GCBBStyleBidirectionalFullDomainNodeJoin` 可以作为 node join 实验分支继续使用。它已经完成的关键点包括：forward 保存 `preNodeFrontier/U_state`；backward 不再保存额外 suffix 状态；reachable set 改成 full-domain 一跳可达；第一次跨过 `Tmid` 的 child 作为 terminal label 参与 join；terminal label 走普通 dominance 但不入队；debug 输出按来源和符号临界性分类。

尚未完成的是性能优化。现在 final join 仍大量调用函数 add/findMinimal 和 evaluator 复核，`wet020_001_2m` debug 分类跑中 exact pricing 约 `74.784s`，join phase 占主要部分。这不是拼接公式错误，而是安全基线版本缺少可靠剪枝。后续如果要加速，应优先证明哪些 node join inferred value 可以作为安全 lower bound，或把 completion bound 作为前置筛选；在更多样例确认 `signCritical=0` 之前，不应直接用所有 inferred 数值替代 evaluator，也不应恢复不可靠的函数 lower-bound prune。

### 6.6 当前 node join 的剪枝顺序

当前 `GCBBStyleBidirectionalFullDomainNodeJoin` 的 final join 不是先用 reduced-cost 下界判断再拼接。现有流程只保留轻量的结构和时间过滤：先按同一个 `joinJob` 分组，只拿 `activeForwardByLastJob[j]` 和 `activeBackwardByFirstJob[j]` 配对；随后检查除 join job 以外的 visited set 是否相交；再检查 `forward.preNodeFrontier` 和 `backward.frontier` 的时间定义域是否有交集。通过这些检查后，代码直接执行 `forward.preNodeFrontier.add(backward.frontier)`，再对得到的 `joinCost` 调用 `findMinimal(false, true)`。

因此这里的 `reducedCostBound` 这个变量名容易误导。它不是前置剪枝用的安全 lower bound，而是函数拼接之后得到的 inferred reduced cost，只传给 `tryGenerateColumn()` 做 debug 复核口径。真正决定候选是否进入列池的是 `TWETColumnEvaluator` 对恢复序列重新计算出的 reduced cost。`joinPairsLowerBoundPruned` 目前只是保留的统计字段，当前 node-join 路径没有实际增加它；此前 `wet020_001_2m` 的统计里该项也保持为 0。

这个选择是有意的安全基线。虽然本轮 debug 分类显示 `signCritical=0`，没有发现负列符号被算错，但 dual profitable window 下大量正候选的 inferred/evaluator 数值仍不逐点一致。只要还没有证明某个 inferred value 对所有样例都是安全 lower bound，就不应把它放回 final join 的 cost-LB 剪枝。后续若要恢复类似旧 crossing-arc join 的下界判断，应先明确下界的定义域、是否受 dual window 影响、以及它是否只用于丢弃确定不可能为负的 pair。

### 6.7 恢复旧双向风格的 join 下界剪枝

2026-05-30 按性能优先的方向调整 node-join 实验类，恢复类似旧 full-domain 双向 crossing-arc join 的三层过滤。第一层是 group 级 lower bound：对同一个 join job，先用该组 forward label 中最小的 `preNodeFrontier/U_j` 端点最小值，加上 backward label 的 `minReducedCost`。如果这个乐观下界已经不小于 reduced-cost 阈值，则整组 backward suffix 不再进入 pair 扫描，并计入 `joinTerminalGroupsCostPruned`。

第二层是 pair 级 lower bound：forward active list 在 final join 前改按 `preNodeMinReducedCost` 升序排序。扫描某个 backward suffix 时，若当前 forward 的 `preNodeMinReducedCost + backward.minReducedCost` 已经不为负，则后续 forward label 的该下界也不会更好，可以像旧流程一样直接 `break`，并计入 `joinPairsLowerBoundPruned`。这一步发生在函数 add 之前，目标是减少不必要的 `preNodeFrontier.add(backward.frontier)`。

第三层是函数拼接后的精确 inferred 过滤：只有通过前两层下界的 pair 才执行 `preNodeFrontier + backward.frontier` 并调用 `findMinimal(false, true)`。若得到的 `reducedCostBound` 仍非负，则计入 `joinFunctionPruned`，不再恢复序列，也不再进入候选池。

本次也按性能诊断要求停用了 `TWETColumnEvaluator` 复核路径。`tryGenerateColumn()` 现在直接使用 join 推导的 `inferredReducedCost` 作为候选 reduced cost，并通过 `objectiveCostFromReducedCost()` 反推出列的 objective cost；原 evaluator 相关代码保留为注释，便于后续需要 debug 时恢复。这使 node join 更接近旧双向 pricing 的运行口径，但也意味着当前分支不再用 evaluator 兜底检查函数口径差异。

验证上，用 `wet020_001_2m`、`mode=nodeJoin`、`maxNodes=1` 重新跑 root 节点，结果为 `obj=bound=6343`、`valid=true`。exact pricing 时间约 `5.537s`，其中 forward 扩展约 `1.336s`，backward 扩展约 `3.768s`，join phase 约 `0.327s`，joinMeasuredShare 约 `6.01%`。本轮统计中 `joinTerminalGroupsCostPruned=44`，`joinPairsLowerBoundPruned=10850`，`joinFunctionEvaluations=937228` 且这些函数评价全部在 `findMinimal` 后被非负过滤掉，候选池没有新增负列。相比 evaluator 安全版，这说明旧双向风格的前置下界剪枝确实把 final join 成本压了下来。后续若要把该分支推广到更多样例，还需要继续关注不同实例上列有效性、BPC bound 是否一致，以及停用 evaluator 后是否会隐藏新的符号临界问题。

### 6.8 与旧双向流程的对齐检查

当前 node-join 版本与 `GCBBStyleBidirectionalFullDomain`、`GCBBAsymmetricBidirectional` 和 `GCNGBBStyleBidirectional` 的 final join 骨架已经基本一致：final join 前 compact active label；forward 候选按用于 pair lower bound 的标量排序；join 时先做 group lower bound，再做 pair lower bound，随后做集合和时间检查；只有通过这些轻量过滤后才执行函数 add 和 `findMinimal`；`findMinimal` 非负则直接剪掉；负候选再恢复序列并进入 top-K 候选池。

不一致的地方主要来自 node join 自身语义，而不是流程缺失。旧 crossing-arc join 使用 `forward.minReducedCost + backward.minReducedCost + joinFixedReducedCost` 作为 group/pair 下界，并在函数拼接时执行 `forward.shiftX(delta) + backward + joinFixedReducedCost`。node join 版本则把 `joinFixedReducedCost` 和 `shiftX(delta)` 前移到 forward 扩展阶段，存成 `preNodeFrontier/U_j`，因此下界改为 `preNodeMinReducedCost + backward.minReducedCost`，函数拼接改为 `preNodeFrontier + backward.frontier`。集合检查也从“forward/backward visited 完全不相交”改成“除 join job 外不相交”，恢复序列时删除 backward 开头重复的 join job。

当前还存在一些冗余或残留。`getForwardJoinExtension()`、`getBackwardJoinExtension()`、`valueAtOrNearest()` 和 `appendSegments()` 是 half-domain 常数延拓时期留下的工具，在当前 full-domain node join 路径中没有被调用。`minForwardReducedCostByLastJob` 仍被维护，但 node join 的 group lower bound 已改用 `minForwardPreNodeReducedCostByLastJob`，前者目前只属于旧流程残留。由于 evaluator 复核已停用，`CandidateSource`、`recordDebugReducedCostMismatch()` 以及 `debugRcMismatch` 相关统计也变成诊断残留；现在 `debugBPCPricingColumnCheck` 实际只保留序列兼容性检查，不再做成本复核。`forwardHalfEligibleByJob/backwardHalfEligibleByJob` 在 node-join full-domain 路径里也只用于统计，不参与扩展或 join 判定。

旧流程中主要的加速 trick 已经迁回：active forward 排序后 pair lower bound 可以 `break`；group lower bound 可以整组跳过；函数最小值非负时不恢复序列；top-K 候选池仍按 best reduced cost 保留。尚未迁入或暂未使用的加速方向主要不是旧流程已有 trick，而是后续可选增强，例如用当前 top-K 最差负 reduced cost 替代固定 `REDUCED_COST_TOLERANCE` 做更强剪枝，或把 completion bound 接到 label/terminal/join 前筛选。这些都需要单独证明安全性。
### 6.9 清理 full-domain node join 中的历史残留

2026-05-30 对 `GCBBStyleBidirectionalFullDomainNodeJoin` 做了一轮小清理，只处理已经确认不参与当前 node-join 主路径的残留。核心原则是不改变 `preNodeFrontier + backward.frontier` 的拼接、group/pair lower bound、函数 `findMinimal` 和 top-K 候选池逻辑，只把容易误导后续判断的旧诊断和旧半域工具从活跃代码里拿掉。

本次下线的主要残留有四类。第一类是 evaluator mismatch 诊断，包括 `CandidateSource`、`recordDebugReducedCostMismatch()` 和 `debugRcMismatch` 相关统计。它们之前出现，是因为 node join 早期仍用 `TWETColumnEvaluator` 复核每条候选，想区分 inferred reduced cost 与完整序列评价之间的口径差异。现在性能分支已经先在 join 函数层剪掉非负候选，并直接用 inferred reduced cost 反推列成本，这套来源分类和 mismatch 统计已经没有数据来源，继续保留只会让日志像还在做 evaluator 复核。

第二类是 half-domain 常数延拓工具，包括 `getForwardJoinExtension()`、`getBackwardJoinExtension()`、`valueAtOrNearest()`、`appendSegments()` 和对应的 `joinExtendedFrontier` 缓存。它们是旧 half-domain crossing-arc join 的残留：当时两侧 label 只覆盖半域，join 前需要把缺失一侧用 `Tmid` 附近的常数补齐。当前 full-domain node join 的 forward/backward label 都覆盖 `[0, pricingHorizon]`，并且最终拼的是 `forward.preNodeFrontier + backward.frontier`，不再需要任何常数延拓。

第三类是旧 crossing-arc 下界标量 `minForwardReducedCostByLastJob`。旧流程的 group lower bound 可以直接用 forward frontier 的最小 reduced cost；node join 中 join job 的 penalty/job dual 不能在两侧重复计算，因此下界必须改用未加入 join job 成本的 `preNodeMinReducedCost`。现在只维护 `minForwardPreNodeReducedCostByLastJob`。

第四类是 half-domain eligibility 统计。`forwardHalfEligibleByJob/backwardHalfEligibleByJob` 之前用于判断某个 job 是否天然落不到对应半域。full-domain node join 已经不再用它裁剪 reachable set 或扩展候选，`Tmid` 只决定 child 是否继续入队。因此这套计算只剩统计意义，且容易误导后续以为仍有半域剪枝参与当前实验分支。

保留的一个“残留说明”是：文件里仍留下了简短中文注释，解释 evaluator 复核和 half-domain 延拓为什么曾经存在、现在为什么不用。这样做比保留整段不可达代码更清楚，也能避免后续继续维护已经失效的统计口径。
