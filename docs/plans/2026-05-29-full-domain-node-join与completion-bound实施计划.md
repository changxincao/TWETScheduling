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

这些正候选为什么数值不完全一致，主要是口径差异造成的：pricing 侧启用了 root/no-cut 下的 dual profitable window，`piWindow=enabled`，本轮有效时间域被收紧到 `dynamicHStartMin=512.9999999999998`、`pricingHorizon=1188.0000000000007`；而 `TWETColumnEvaluator` 是按恢复出的完整 sequence 在原始目标函数口径下重新求序列成本。profitable window 的作用是保证负 reduced-cost 的最优候选不会被删掉，不保证任意一个正 reduced-cost sequence 的函数值和原始 evaluator 值逐点一致。因此正候选上出现 `inferred > checked` 是预期内的诊断噪声，不应据此判断 node join 公式错误。

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

### 6.10 node join 与 full-domain arc join 的 root 效率对照

2026-05-30 用 `GCBBFullDomainComparisonTest` 在 `tmp-wet020_001_2m` 和 `tmp-wet021_001_2m` 上对照 full-domain crossing-arc join 与 full-domain node join，均限制 `maxNodes=1`，只看 root pricing 行为。两组结果的结论一致：node join 的 final join 阶段占比确实被压低，但因为 full-domain node join 保留了第一次跨过 `Tmid` 的 terminal label，并且同 job node join 的 active label 规模明显更大，主要瓶颈转移到了 forward/backward 扩展和 dominance graph。

`wet020` root 上，arc join 的 exact pricing 为 `0.161s`，node join 为 `3.652s`，约慢 `22.7x`。arc join 的内部 measured time 为 `138.138ms`，其中 forward 扩展 `31.760ms`、backward 扩展 `44.507ms`、join `61.872ms`，join 占 `44.79%`。node join 的 measured time 为 `3575.100ms`，其中 forward 扩展 `840.641ms`、backward 扩展 `2455.006ms`、join `279.452ms`，join 只占 `7.82%`。也就是说 node join 的 join 本身约为 arc join 的 `4.5x`，但扩展总时间约为 `43.2x`，总时间主要被扩展阶段拖慢。

规模上，`wet020` arc join 的 forward/backward kept label 为 `1157/1431`，node join 为 `6992/15407`；paper dominance kept/rejected 从 `2588/21845` 增至 `22399/127572`，dominance checks 从 `26907` 增至 `336228`。join 侧 arc join 尝试 `522957` 对、函数评价 `296741` 次；node join 尝试 `2312798` 对、函数评价 `937228` 次。node join 的 group 数反而更少，`10894` 对 `29316`，因为它只按同 job 拼接；但每组 forward 候选更厚，导致候选访问和 pair 数上升。

`wet021` root 上有两轮 exact pricing。arc join 合计 exact pricing `1.886s`，node join 合计 `32.683s`，约慢 `17.3x`。arc join 两轮 measured time 合计 `1789.802ms`，其中 forward `1097.974ms`、backward `280.200ms`、join `411.629ms`；node join measured time 合计 `32529.682ms`，其中 forward `11861.958ms`、backward `18463.899ms`、join `2203.826ms`。这里 node join 的 join 约为 arc join 的 `5.35x`，但扩展约为 `22.0x`，仍然是扩展和 dominance 主导。

`wet021` 的规模差异也类似。arc join 两轮 forward/backward kept label 合计 `4862/3392`，node join 为 `29791/62144`；paper dominance kept/rejected 从 `8254/88775` 增至 `91935/592982`，dominance checks 从 `107218` 增至 `1594409`。函数评价从 `1132311` 增至 `7257555`，候选访问从 `2160156` 增至 `18859071`。node join 生成的负列数量略多，第一轮 candidatePool 为 `10/32/22`，arc join 为 `8/15/7`，但这个收益远不足以抵消 label 和 dominance 规模膨胀。

当前判断是：node join 的流程已经和旧双向的剪枝骨架对齐，join phase 占比也低，说明前置 group/pair/function 三层过滤生效；但 node join 版本为了保证同点拼接完整性，扩展侧保留了大量 terminal 和 full-domain 可达 label，使 dominance graph 成本显著上升。因此短期内 node join 不是更快版本，除非继续加入 completion bound 或更强的 label/terminal 前置剪枝，把扩展阶段的 label 数先压下来。后续优化不应只盯 final join，应优先看 backward terminal suffix、reachable 收缩和 completion bound 接入点。
### 6.11 node join 效率差距的实现口径复查

2026-05-30 复查 node join 与 full-domain arc join 的扩展语义后，当前判断需要修正得更精确：如果只是在 arc join 的半域扩展基础上额外保留第一次跨过 `Tmid` 的 child，那么确实可以理解为“每个 label 最多多尝试一层 terminal 扩展”。但当前 `GCBBStyleBidirectionalFullDomainNodeJoin` 的实现不只是这个语义，它把 normal label 的 `reachableSet` 本身也改成了 full-domain 一跳可达集合，并让 terminal child 继续走普通 `FWTL/BWTL` dominance 和 active list。

这会带来两层放大。第一，某个父 label 不再只生成一个跨界 terminal child，而是会对所有 full-domain 一跳可行、但半域下本应被 `Tmid` 挡掉的 job 都生成 terminal child；因此额外工作量是每个父 label 乘以跨界候选数，而不是每个 label 至多一次。第二，这些 terminal child 虽然不入 `FWUL/BWUL` 继续扩展，但仍进入普通 dominance graph 和 active join table；同时 normal label 的 reachable key 变宽后，占优关系更难成立，导致保留 label、rejected label 和 dominance checks 一起膨胀。

现有 root 对照数据支持这个解释。`wet020` 中 node join 的 boundary terminal 保留/支配为 forward `1975/34096`、backward `7471/65325`，paper dominance kept/rejected 从 arc join 的 `2588/21845` 增至 `22399/127572`，dominance checks 从 `26907` 增至 `336228`。`wet021` 两轮中 node join 的 boundary terminal 尝试规模更大，paper dominance kept/rejected 从 `8254/88775` 增至 `91935/592982`，dominance checks 从 `107218` 增至 `1594409`。因此 17x-23x 的总耗时差距主要不是 final join 公式本身，而是当前 full-domain reachable 与 terminal 存储口径已经偏离了“只多一层”的预期。

由此得到的实现判断是：当前代码没有表现出 terminal label 被反复入队的无限扩展错误，因为跨界 child 保留后确实不再入队；但如果设计目标是“相对 arc join 只额外补第一跨界 node join 候选”，那么当前实现偏宽，属于需要重新收窄的实现问题。后续若要验证这个方向，应考虑把 normal label 的 reachable/dominance 仍保持半域语义，再单独扫描或保存跨界 terminal 候选；terminal 候选也不宜直接用 full-domain reachable key 扩大普通 dominance graph，至少应评估 terminal-only store 或使用半域 continuation key。

### 6.12 node join 与 arc join 的成本交换关系

进一步讨论后，可以把当前差距概括为一个成本交换问题。full-domain arc join 在扩展阶段仍保留半域可达性判断：如果某个 child 一生成就跨过 `Tmid`，旧流程不会生成它，也不会对它做 `shift/add/normalize`、dominance 插入、active list 登记和后续 compact。相应地，arc join 把这部分组合留到 final join 里，通过 crossing arc 拼接两侧 label。它的代价是 join 阶段 pair 更多、函数拼接更多，但扩展侧 label 规模被半域截断压住。

当前 node join 正好反过来。为了让两侧在同一个 job 上拼接，它把第一次跨过 `Tmid` 的 child 也显式生成出来，并作为 terminal label 保留。这样 final join 只按同一个 `joinJob` 拼，group 数和语义都更直接，join phase 占比也明显降低；但扩展阶段要为每个父 label 枚举所有 full-domain 一跳可行的跨界 job，额外生成的 terminal child 还进入普通 dominance graph 和 active join table。因此 node join 省下的是 final join 的一部分扫描/拼接成本，付出的是大量扩展和占优成本。

从 `wet020/wet021` root 结果看，当前这笔交换不划算。node join 的 joinMeasuredShare 确实比 arc join 低，但扩展和 dominance 的放大远大于 join 阶段节省：`wet020` 中 node join 的 join phase 约 `279ms`，arc join 约 `62ms`，node join 的 join 本身甚至没有更短；真正拉开差距的是扩展总时间从 arc join 的约 `76ms` 增至 node join 的约 `3296ms`。`wet021` 两轮也类似，node join join phase 约 `2204ms`，arc join 约 `412ms`，但扩展总时间从约 `1378ms` 增至约 `30326ms`。因此当前结论不是“node join 公式一定差”，而是当前 full-domain terminal 显式化方式让扩展/占优成本超过了它在 join 端可能带来的收益。

所以短期判断应偏向继续保留 arc join 作为更好的默认方向。node join 若要继续尝试，优化重点不应再放在 final join，而应放在 terminal label 的生成和存储语义上：normal label 仍使用半域 reachable 参与普通 dominance；跨界 terminal 候选单独生成、单独轻量支配或直接面向 join 存储；避免把 full-domain reachable key 放进普通 dominance graph。只有先把这部分额外扩展和占优成本压下来，node join 才有可能靠更直接的同点拼接抵消成本。

### 6.13 对“是否为实现错误导致误判”的代码复查

2026-05-30 又按代码路径复查了一遍，当前效率判断不是由简单实现错误导致的。`GCBBStyleBidirectionalFullDomainNodeJoin` 的扩展逻辑确实是：`forwardExtend/backwardExtend` 从 full-domain `reachableSet` 枚举候选，先完整生成 child；如果 child 已经跨过 `Tmid`，则调用 `insertForwardBoundaryTerminal()` 或 `insertBackwardBoundaryTerminal()`，随后立即 `return`，不会执行 `FWUL.add(child)` 或 `BWUL.add(child)`。因此没有发现 terminal label 反复入队、继续多步扩展的错误。

但这些 terminal child 不是轻量旁路保存。`insertForwardBoundaryTerminal()` 内部仍调用普通 `insertForward()`，`insertBackwardBoundaryTerminal()` 内部仍调用普通 `insertBackward()`。也就是说，跨界 terminal label 会进入 `FWTL/BWTL` 做完整 dominance 检查，保留下来后还会进入 active join table，final join 前也会参与 compact 和排序。这正好解释了统计中 boundary terminal kept/dominated、paperGraph rejected 和 dominanceChecks 的放大。

对照 `GCBBStyleBidirectionalFullDomain`，arc join 版的 reachable 构造仍带 `isForwardHalfEligibleJob/isBackwardHalfEligibleJob`，direct feasibility 也直接以 `Tmid` 为边界。因此一跳就跨过 `Tmid` 的 child 在 arc join 扩展阶段根本不会被创建，也不会进入 dominance graph。node join 版则通过 `isDirectForwardExtensionTimeFeasibleFullDomain(..., false)` 和对应 backward full-domain 判断，把这些候选显式纳入 reachable set。两版差异是代码中真实存在的语义差异，不是日志口径或统计字段误读。

因此当前结论可以更准确地表述为：没有发现“terminal 误入队导致无限或多步扩展”的实现 bug；但如果原设计目标只是“在半域 arc join 基础上额外补第一跨界 node join 候选”，当前实现确实偏宽，因为 normal reachable/dominance 也被 full-domain 语义放大了。这个偏宽本身就是当前慢因之一，而不是性能结论的误判来源。后续若继续优化 node join，应把 normal label 的半域 continuation key 和 terminal-only 候选存储拆开验证。

### 6.14 node join、pi-window 与列成本口径的补充问题

2026-05-30 继续围绕 node join 的几个口径问题做代码层面解释。所谓“当前实现偏宽”，不是说 terminal label 会反复入队，而是说现在 normal label 的 `reachableSet` 已经从半域一跳可达变成 full-domain 一跳可达。这样每个父 label 会显式生成所有 full-domain 下可行、但半域下本来会被 `Tmid` 挡掉的跨界 child；这些 child 虽然不再入队，但仍进入普通 `FWTL/BWTL` dominance graph 和 active join table。因此额外工作量不是“每个 label 最多多一个 child”，而是“每个 label 乘以所有跨界候选 job”，并且还放大了 dominance key 和 active label 规模。

join 后出现正 reduced cost 并不奇怪。group/pair 下界使用的是 `forward.preNodeMinReducedCost + backward.minReducedCost`，这两个最小值可以来自不同完成时间；真正 node join 时必须在同一个 join job 完成时间上把 `forward.preNodeFrontier + backward.frontier` 相加。公共时间域里的函数最小值可能远高于两侧各自最优值之和，所以会出现乐观下界为负、真实拼接函数最小值为正的情况。当前代码已经在 `findMinimal` 后过滤非负值，因此这些正候选不会进入列池；completion bound 的作用更适合作为前置过滤，减少进入函数 add/findMinimal 的正候选，而不是替代最终非负检查。

`pi` 时间窗需要区分“负列保留规则”和“固定序列成本等价”。当前 root/no-cut 下的 dynamic profitable window 会收紧 job penalty 的有效区间，它的设计目标是保留可能产生负 reduced cost 的最优列，不是保证任意固定 sequence 在 restricted window 内的最优成本等于完整时间域 evaluator 成本。因此正 reduced-cost 候选出现 inferred/evaluator 不一致属于预期诊断噪声；此前 `wet020_001_2m` debug 中 mismatch 全部为 positive-only，未发现 sign-critical 或 both-negative。这个结果只能说明该样例没有暴露负列符号错误，不能单独证明所有样例上“负列成本完全一致”。若要把 evaluator 复核长期下线，需要继续多样例统计，或者给出当前 dynamic window 与 inferred reduced cost 的严格证明。

列加入主问题时的目标系数来自 `TWETColumn.cost`。`LP` 在 objective 和增量加列时都直接使用 column cost，覆盖约束只看 job 是否被该列包含，分支弧约束则看列的有序 sequence 是否访问对应 arc。当前 `Pool` 使用 `SequenceSignature` 去重，签名是完整有序 job 序列，不是 job 集合；同一 job 集合但顺序不同会作为不同列保留，因为成本和访问弧可能不同。相反，如果完全相同的 sequence 后续以不同 cost 再次生成，`Pool.addColumn()` 会直接返回已有列 id，不会更新 cost。因此列成本必须是与 sequence 固定绑定的真实原始目标成本；如果某条列的 cost 受当前 `pi` 时间窗或当前 dual 影响，就不应作为永久 objective coefficient 写入列池，至少应由 evaluator 重算真实成本，或在 pool 层明确处理同序列成本更新。

当前启发式 pricing 没有使用这套 `pi` 时间窗。`HeuristicPricingEngine` 直接用原始 `data.penaltyFunction` 和 `TWETColumnEvaluator` 同口径的序列成本，再用当前 dual 计算 reduced cost。若后续增加“把当前 RMP 变量设为整数求解上界”的启发式，它只能证明当前受限列集合里是否找到一个整数可行解；若整数不可行，不能说明原问题不可行，只能说明当前列池还不够。若列池中存在 window 口径导致的偏大 cost，用它求出的上界也会偏保守，因此任何 incumbent 最好按真实 sequence evaluator 重新计价。

### 6.15 pi-window 负列复核、列成本与旧 VRP 列标识补充

2026-05-30 按“多跑几个样例”的要求，在 node join 和 full-domain arc join 上补了 root-only debug 验证。node join 侧在 `GCBBStyleBidirectionalFullDomainNodeJoin` 里恢复了一个 debug-only 的负列复核：只有当 inferred reduced cost 已经为负、并且 `Configure.debugBPCPricingColumnCheck=true` 时，才用 `TWETColumnEvaluator` 对恢复出的 sequence 重新计算原始成本和 reduced cost；该统计不改变实际加列成本，仍然保留性能分支“用 join 推导 reduced cost 反推 objective cost”的口径。`wet020/wet021/wet022` 的 node join root 结果中，`wet021` 第一轮生成了 32 条负候选并全部通过复核，`debugNegGenerated checked/mismatch/signCritical/bothNeg/maxAbs=32/0/0/0/0.0`；`wet020` 和 `wet022` 没有生成负候选。`wet025` 的 node join 运行过慢，已改用 arc join 做同类 pi-window 验证。

arc join 侧复用 `GCBBStyleBidirectionalFullDomain` 既有 debug 复核路径，跑了 `wet020/wet021/wet022/wet025` 四个 root-only 样例。四个样例均 `valid=true`，运行过程中没有出现 inferred/evaluator mismatch 输出；其中 `wet021` 第一轮 exact pricing 的候选池统计为 `candidatePool kept/seen/dropped=8/15/7`，其余 `wet020/wet022/wet025` 没有生成负候选。当前证据说明：至少在这些 root/no-cut 且 `piWindow=enabled` 的样例上，最终生成的负列在 pi-window 下的 inferred reduced cost 与原始 evaluator reduced cost 没有观察到差异。这个结论是经验验证，不是形式证明；它支持继续用 arc join 做默认方向，也支持把 node join 的 evaluator 复核收缩到 debug-only 负列统计，但不能替代对 dynamic window 安全性的数学证明。

列加入主问题时的成本口径也重新按代码确认了一遍。`LP.readDuals()` 在每次 LP 求解后把 cover dual 写入 `jobDual[j]`，把机器数约束 dual 写入 `machineDual`，把分支弧约束 dual 写入 `arcDual[from][to]`。pricing 生成列时如果只有 reduced cost，就通过

```text
cost = reducedCost + machineDual
       + sum jobDual(job)
       + sum arcDual(prev, job)
       + arcDual(last, sink)
```

反推出 `TWETColumn.cost`。这里不是从 label 中读取 dual 累加值，而是扫描恢复出的有序 sequence，并读取 LP 当前缓存的 dual 数组。label 传播过程中的 frontier 已经包含 reduced-cost 递推项，但最终 objective coefficient 必须写成原始主问题成本，因此需要这一步反推。后续如果担心同一 sequence 在不同 pi-window 下被算出不同 cost，问题不在 LP 目标系数公式本身，而在“生成 `TWETColumn.cost` 时是否用了真实原始 sequence 成本”。当前启发式 pricing 使用 evaluator 原始成本；node join 性能分支使用 inferred reduced cost 反推，所以才需要上述负列复核继续监控。

旧 VRP 的列标识也按代码查证了。`src/BPC/LP/Pool.java` 使用 `HashMap<ArrayList<Integer>, Integer> schedule_check_map`，`AddRoute()` 直接用 `Schedule.jobSeq` 作为 key；Java 的 `ArrayList.equals/hashCode` 是顺序敏感的。因此旧 VRP 的列去重也是按完整有序 route sequence，而不是客户集合。`src/BPC/LP/LP.java` 加列时不仅把 route cost 放入目标函数，还把 route 中相邻节点登记到 `arc2schedule`，并用于分支弧约束、cut 或后续 route 筛选。由此，同一客户集合但顺序不同的 route 在同一个 node 上不能只保留一条：它们覆盖约束系数相同，但成本、访问弧、分支兼容性和 cut 响应都可能不同。只有在纯集合覆盖、成本也只依赖集合且没有弧/cut 约束的模型里，才可以把同集合不同顺序合并；当前 TWET 和旧 VRP 都不满足这个条件。

旧 VRP 中没有发现“把当前 RMP 所有变量临时设为整数求上界”的启发式。`src/BPC/LP/LP.java` 中 route 变量用 `IloNumVarType.Float` 建模，`IsInteger()` 只是检查当前 LP 解是否已经整数，并在整数时提取路线；它不是把 LP 重新改成 MIP 求一个 restricted-master incumbent。当前 TWET 的 BPC 也是类似思路：树搜索中如果 LP 解本身整数则更新 incumbent，启发式 pricing 负责找负列而不是求 integerized RMP。因此若后续要加这个上界启发式，应作为新功能单独实现，并且要把“不整数可行只说明当前列池不足”写进使用口径。

### 6.16 关于负列复核范围和 pi-window 正负不一致的进一步澄清

2026-05-30 继续确认复核范围。`GCBBStyleBidirectionalFullDomainNodeJoin.tryGenerateColumn()` 中的 debug 负列复核发生在 `rememberGeneratedCandidate()` 之前，因此覆盖的是所有通过结构检查、当前 active 列去重、且 inferred reduced cost 为负的候选，而不是最终 top-K heap 中保留下来的 K 条。换句话说，`wet021` node join 里 `debugNegGenerated checked=32` 对应的是本轮所有进入候选池逻辑前的负候选，随后其中 10 条被 top-K 保留、22 条被堆容量或重复逻辑丢弃。full-domain arc join 的 debug 复核也发生在 `rememberGeneratedCandidate()` 前；它会先用 evaluator 重算候选 sequence 的真实成本和 reduced cost，再决定是否进入候选池。

列成本当前不是只对最终 K 条计算。node join 性能分支在 `tryGenerateColumn()` 一进来就用 inferred reduced cost 反推 `cost`，随后才判断 reduced cost 是否为负、是否进入 top-K。arc join 也在候选池前构造成本；debug 开启时还会对候选 sequence 调 evaluator。这样写比较直接，但从性能角度并不是最省的。如果后续要进一步优化，可以把候选池内部从“直接持有 `TWETColumn`”改成“先持有 sequence + reducedCost + 必要的 cost 生成信息”，最终 `finalizeGeneratedColumns()` 时只对保留下来的 K 条构造 `TWETColumn.cost`。这会减少被 top-K 丢弃候选的成本反推或 evaluator 调用，但需要小心去重和 active 列过滤仍然基于 sequence。

关于“为什么正候选可能不一致而负候选目前没有观察到不一致”，当前可给出的解释是保留规则层面的，而不是对任意 sequence 的逐点等价。`pi` 时间窗本质上是用当前 job dual 推出每个 job 的 profitable completion 区间：若某个 job 在这个区间外完成，仅该 job 的 earliness/tardiness penalty 就已经足以抵掉它的 job dual 收益，按 root/no-cut 且弧 reduced cost 仍满足原始三角不等式的假设，它不应成为真正负 reduced-cost 列的最优完成时间。因此，负 reduced-cost 最优列应当能在这些 profitable window 内找到等价或更优的完成时间；这就是为什么该 window 可以作为“负列保留规则”使用。

但对正 reduced-cost 的固定 sequence，这个结论不成立。一个 sequence 在完整时间域上的最优完成时间可能落在某个 job 的 profitable window 外，完整 evaluator 会选择那个时间并得到一个正 reduced cost；pricing 侧被 window 裁掉后，只能在 restricted domain 内取另一个完成时间，数值可能更大或更小，但反正它仍然是非负候选，不影响是否加入列池。此前 `wet020` 大量 mismatch 全部是 positive-only，正是这个现象：它说明 window 改变了正候选的固定 sequence 最优值，但没有观察到负候选符号被改变。

当前测试能支持的结论应写得保守一些：在已跑的 root/no-cut 样例里，arc join 暂未观察到负候选 evaluator mismatch；node join 重新诊断后发现少量 both-negative 数值差异，但没有 sign-critical。正候选存在不一致，且已确认会被非负过滤挡掉。这不是对所有实例、所有节点、所有 cut dual 的证明。代码里也只在 `canUseDualProfitableWindow()` 返回 true 时启用该 window，即根节点且没有 active cuts；一旦进入非根或有 cut dual，当前实现回到静态窗口，避免把这个基于三角不等式和 job dual 的推理扩展到不满足条件的场景。

### 6.17 正候选下界诊断与负列成本一致性修正

2026-05-30 在 node join 性能分支中新增正函数剪枝统计，用来回答“乐观下界为负但最终拼接为正”的 pair 到底长什么样。该统计记录的是 `forward.preNodeMinReducedCost + backward.minReducedCost < 0`，但实际执行 `forward.preNodeFrontier + backward.frontier` 并 `findMinimal()` 后得到非负 reduced cost 的 pair。它们会被 `joinFunctionPruned` 剪掉，不会进入候选池。

`wet020_001_2m` root-only node join 中，正函数剪枝 pair 为 `938981` 个。它们的 pair 乐观下界范围为 `[-2456.0, -0.1000000000003638]`，平均约 `-565.7182`；实际拼接函数最小 reduced cost 范围为 `[0.0, 3607.0]`，平均约 `1064.5355`；从乐观下界抬到真实拼接值的最大幅度为 `5009.0`。这说明很多 pair 的两侧各自最小值确实很负，但这些最小值不在同一个 join completion time 上，拼接后公共时间域里的最小值完全可以变成很大的正数。

`wet021_001_2m` root-only node join 有两轮 exact pricing。第一轮正函数剪枝 pair 为 `2063268` 个，pair 下界范围 `[-2663.400000000002, -0.028571428567374824]`，平均约 `-592.9154`；实际拼接 reduced cost 范围约 `[-3.64e-12, 3814.4571]`，平均约 `1185.7535`，最大抬升 `5573.3`。第二轮正函数剪枝 pair 为 `2007005` 个，pair 下界范围 `[-2664.279999999998, -0.013333333330137975]`，平均约 `-591.9139`；实际拼接 reduced cost 范围约 `[-3.64e-12, 3813.5771]`，平均约 `1180.9370`，最大抬升仍约 `5573.3`。

这组数据说明，正候选不是“生成了一条正列再加入列池”，而是 pair lower bound 过于乐观导致它们进入了函数拼接；函数拼接后的 reduced cost 非负，所以在恢复 sequence 前就被剪掉。completion bound 若接入这里，目标应是提前识别这类 pair，减少函数 add/findMinimal，而不是替代最终的函数非负检查。

同时，本轮重新跑 `wet021` 后需要修正前面对负列 cost 一致性的表述。node join 第一轮 `debugNegGenerated checked/mismatch/signCritical/bothNeg/maxAbs=14/4/0/4/10.8`：14 个 inferred 为负的候选在进入 top-K 前都做了 evaluator 复核，其中 4 个与 evaluator 的 reduced cost 数值不一致，但二者都仍为负，没有 sign-critical。也就是说，当前样例支持的是“负候选的符号没有被 pi-window/node-join 口径改变”，不支持“负候选固定 sequence 的最小 cost 与完整时间域 evaluator 完全一致”。如果这类 inferred cost 直接反推 `TWETColumn.cost` 写入主问题，就可能带来最多约 10.8 的目标系数偏差。

因此更稳妥的结论是：`pi` 时间窗在 root/no-cut 下可作为负列保留筛选，但不是任意固定 sequence 的成本等价变换；node join 当前的 `preNodeFrontier + backward.frontier` 也只能作为寻找负候选的 reduced-cost 依据，不能在没有进一步证明时替代 evaluator 作为最终列成本。若该实验分支要作为 exact pricing 使用，最终加入 `TWETColumn` 前应恢复 evaluator 对负候选的真实成本重算，或者至少在调试统计长期证明 both-negative 数值差异为 0 后再关闭。
### 6.18 追加测试后的 pi-window 列成本判断

2026-05-30 继续补跑了 `wet015` 的 root-only 对照。full-domain arc join 在 `wet015_001_2m` 到 `wet015_010_2m` 十个算例上均 `valid=true`，均没有生成负候选，日志中也没有 inferred/evaluator mismatch 输出。node join 在同一批十个算例上同样 `valid=true`，也没有生成负候选；但每个算例都出现大量“pair 乐观下界为负、实际同点拼接函数最小值非负”的 pair。例如 `wet015_009_2m` 中这类 pair 为 `30640` 个，pair 下界范围约 `[-1925.0, -1.0]`，实际拼接 reduced cost 范围约 `[0.0, 2724.0]`，最大抬升约 `4074.57`。这进一步说明正候选不是列池污染，而是 lower bound 与同时间函数拼接之间的乐观差距，当前代码已在 `findMinimal()` 后过滤掉。

结合已有 `wet020/wet021/wet022` 结果，当前经验结论需要分开写。arc join 已跑的 root-only debug 样例中，`wet021` 生成了 15 个 inferred 负候选并保留 8 个，未观察到 evaluator mismatch；其他几个样例没有负候选。node join 则不稳定：同样是 `wet021`，一组历史输入/配置中 32 个负候选无 mismatch，另一组当前 positive-LB 诊断中 14 个负候选有 4 个 both-negative mismatch，最大 reduced-cost 差约 `10.8`，但仍没有 sign-critical。也就是说，当前证据不能支持“pi-window 下负候选固定 sequence 的最优成本一定等于完整 evaluator 成本”，只能支持“已测样例里没有出现 inferred 负而真实非负，或者真实负被 inferred 算成非负的符号错误”。

因此，用户担心的列池问题是实际存在的工程风险。`Pool.addColumn()` 按完整有序 `SequenceSignature` 去重；如果相同 sequence 已经存在，会直接返回已有 id，不更新 `TWETColumn.cost`。而 `TWETColumn.cost` 是主问题目标系数，`LP.addColumnToCurrentModel()` 会直接把它放入 objective。若某条 both-negative 但被 pi-window/node-join 口径高估的列先进入列池，后续即使在另一个 dual/window 下重新生成同一 sequence 的真实低成本版本，也不会自动修正旧 cost。这和外包硬时间窗不同：外包窗口是问题层面的全局有效剪枝，而 pi-window 只是当前 root/no-cut pricing 轮根据 dual 推出来的保留规则，不能作为永久列成本定义。

不重新计算时，很难可靠检测某个固定 sequence 是否发生了这种 cost 偏差。能做的 cheap signal 只有“该 sequence 中存在 effective window 严格小于 hard window 的 job”“拼接最优点贴近 dynamic window 边界”“piWindow=enabled 且 jobDual 小于 outsourcing baseline”等可疑条件，但这些只能提示风险，不能证明成本一致或不一致。真正可靠的检测仍然是对恢复出的 sequence 调用 `TWETColumnEvaluator.evaluate()`，再用当前 dual 重算 reduced cost。若后续要把 node join 分支作为 exact pricing 的加列来源，建议至少在构造 `TWETColumn` 前对 inferred 负候选做 evaluator 复核，并用 evaluator 的真实 cost 写入列池；若 checked reduced cost 已非负，则丢弃该候选。这样评估量只发生在 inferred 负候选上，远少于 millions 级别的 pair/function evaluation，也能避免同序列错误 cost 永久留在 pool 中。

### 6.19 用户关于 pi-window 列成本风险的原始分析

以下为 2026-05-30 讨论中用户对 `pi` 时间窗、负列成本和列池去重风险的原始分析，先原样保留，后续实现时按这里的风险点逐项对照。

```text
1、你多测试几个算例，arc join的，node join的看看是不是都会这样。以及是否有比较好的方式检测某个列发生了这样？在不重新算的情况下。 反正这个东西应该就是都是正的无所谓，pi-时间窗是正，真实是负也无所谓，即使当前这一轮没加进去但这个很关键，必然可以在某轮加入，因为不会丢掉最优解的。然后pi-时间窗负的，真实正的不可能，因为加时间窗以后的约束更严格。所以最后只有一种会有影响，即都是负数，但pi时间窗的更大，此时如果加入的话，可能导致问题。因为这个列以及他的对应的最优列成本 是有可能在最终的最优解的，pi仅仅是当前求解pricing的时候的输入，只限制本次pricing 这个列不会是最优解，但可能是下一次的最优解。但是如果本轮加入以后，下一轮虽然在最优解里边，但检测到了已经存在的话可能就会出问题了。这时候应该就不会加入了？
1.1：这里的话和b_j 外包导致的硬时间窗是不一样的，这个时间窗全局有效的，即使不加时间窗的时候这个列的真实成本确实更好，但他可以被删掉那个超出时间窗的job以后，让这个job去外包。此时组合成本是更好的。但pi那个不能这么理解。
1.2：处理方法我当前想到的几种是：
1.2.1：最终的那K个保存下来的挨个评估一次，看看是否等价，不等价的话直接把成本改掉。但我不知道这个会不会很耗时间。
1.2.2：不做评估？没想到啥好办法。不太能确定一个被高估的负列，是否可能被二次生成他准确的列成本。
```

### 6.20 inferred 值与 K 堆复核位置

这里讨论中的 `inferred` 指 pricing 函数递推或 join 函数拼接直接推导出的 reduced cost。对 node join 来说，它是 `forward.preNodeFrontier.add(backward.frontier).findMinimal(false, true)[0]` 得到的 `reducedCostBound`；对 forward-sink 收尾来说，它是 `label.minReducedCost - arcDual(last, sink)`。它不是“堆”本身，但当前 `PricingColumnCandidate` 会把这个值作为 `reducedCost` 存入 top-K 候选堆，并用它反推 `TWETColumn.cost`。因此一旦不做 evaluator 复核，K 堆里的排序值和准备写入 master 的列成本都来自 inferred 口径。

若只在最终 K 条即将加入 master 前复核，可以保证这 K 条写入列池和主问题的 objective coefficient 是真实 evaluator 成本；如果发现 both-negative mismatch，则直接改成 evaluator cost，不会丢掉这条真实负列。若发现 inferred 负但 evaluator 非负，则应丢弃该列，因为它不是当前真实主问题下的负列。这个做法能解决“错误 cost 污染永久列池”的主要风险，但它不保证 top-K 排序仍然是按真实 reduced cost 的 top-K，因为一些被 inferred 高估而提前挤出堆的候选不会被复核，也无法补回。

因此更稳的实现位置是在 `tryGenerateColumn()` 中、进入 `rememberGeneratedCandidate()` 之前复核所有 inferred 为负的候选：恢复 sequence 后调用 `TWETColumnEvaluator.evaluate()` 得到真实 cost，再用当前 dual 重算 checked reduced cost。只有 checked reduced cost 仍为负时，才用 checked cost 和 checked reduced cost 进入 K 堆。这样评估范围仍然只是 inferred 负候选，不是所有 join pair；同时 K 堆排序、最终列成本和列池去重后的永久 cost 都回到真实原始 sequence 成本口径。若为了性能只复核最终 K 条，则应把该分支视为启发式加列或近似 top-K，而不是严格的真实 top-K exact pricing 输出。

### 6.21 node join 负候选入堆前复核实现

2026-05-30 已把上述更稳的口径落到 `GCBBStyleBidirectionalFullDomainNodeJoin.tryGenerateColumn()`。当前流程为：先用 inferred reduced cost 做快速负性判断；只有 inferred 为负的 sequence 才调用 `TWETColumnEvaluator.evaluate()` 重算原始列成本，再用当前 `machineDual/jobDual/arcDual` 重算 checked reduced cost。若 checked reduced cost 不再为负，则候选直接丢弃；若仍为负，则用 checked cost 构造 `TWETColumn`，并用 checked reduced cost 进入 top-K 候选堆。`debugBPCPricingColumnCheck` 现在只负责统计 inferred 与 checked 的差异，不再额外重复 evaluator 计算，也不再决定正式列成本口径。

这样处理后，`inferred` 仍然承担“从大量 join pair 中找出可能负列”的筛选作用，但不会再作为永久列池里的 objective coefficient 来源。K 堆排序也改为真实 checked reduced cost，因此比“最终 K 条再复核”更接近 exact pricing 的 top-K 语义。仍需注意的是，若某个真实负列在 pi-window 下被算成非负，它仍不会进入复核；当前 root/no-cut profitable window 的安全性依赖前面关于负列保留规则的假设和样例证据，不是由这次 cost 复核本身证明。

验证方面，focused `javac` 已通过。随后用 `wet021_001_2m` 做 root-only node join 回归，结果为 `status=ROOT_PROCESSED`、`obj=6829`、`bound=6829`、`valid=true`，日志位于 `test-results/bpc/tmp-nodejoin-checked-cost-wet021/wet021_001_2m-nodeJoin.log`。第一轮 exact pricing 中 `debugNegGenerated checked/mismatch/signCritical/bothNeg/maxAbs=14/4/0/4/10.800000000001774`，说明 14 个 inferred 负候选都被复核，其中 4 个存在 both-negative 数值差异但没有 sign-critical；这些候选现在已用 evaluator 的 checked cost/reduced cost 入堆或被过滤。第二轮没有负候选，统计为 `0/0/0/0/0.0`。

### 6.22 arc join 负候选入堆前复核同步

2026-05-31 继续把同一列成本口径同步到 `GCBBStyleBidirectionalFullDomain`。此前 arc join 默认路径仍然用 label/join 推导出的 inferred reduced cost 反推 `TWETColumn.cost`，只有打开 `debugBPCPricingColumnCheck` 时才会用 evaluator 覆盖成本。现在 arc join 与 node join 一致：先用 inferred reduced cost 判断候选是否可能为负，只有 inferred 为负才调用 `TWETColumnEvaluator.evaluate()`，再以 checked reduced cost 判断是否进入 top-K 候选堆，并用 checked cost 构造 `TWETColumn`。

这次同步后，arc join 和 node join 都不再把 `pi` 时间窗或 join 函数口径下的 inferred cost 写入永久列池。两者仍保留 inferred 作为候选筛选依据，因此不会把 evaluator 调用扩大到所有正 pair；评估量只与 inferred 负候选数量相关。

验证方面，focused `javac` 已通过。`wet021_001_2m` root-only full-domain arc join 回归结果为 `status=ROOT_PROCESSED`、`obj=6829`、`bound=6829`、`valid=true`，输出位于 `test-results/bpc/tmp-arcjoin-checked-cost-wet021.csv` 和 `test-results/bpc/tmp-arcjoin-checked-cost-wet021/wet021_001_2m-fullDomain.log`。该次运行第一轮 exact pricing `candidatePool kept/seen/dropped=5/10/5`，并在 debug 输出中观察到 3 条 both-negative mismatch，例如同一 sequence 的 `inferred=-4.400000000000546`、`checked=-14.000000000002501`。这修正了前面对 arc join“未观察到 mismatch”的经验判断：更准确的结论是已测样例中没有 sign-critical，但 arc join 也可能出现 both-negative 数值差异，因此也应使用 evaluator 的 checked cost 写入列池。

### 6.23 复核位置修正：只复核最终 K 堆候选

2026-05-31 进一步修正前两节实现。上一版把 evaluator 复核放在 `tryGenerateColumn()`、即候选进入 K 堆之前；这会对所有 inferred 为负的候选做 evaluator，范围大于最终保留下来的 K 条，违背“先用 pi-window/inferred 维护 K 堆，K 堆固定后再校正成本”的目标。当前实现已改回：`tryGenerateColumn()` 只用 inferred reduced cost 构造候选并维护 top-K；`finalizeGeneratedColumns(lp)` 中才对已经固定的 K 堆候选调用 evaluator，并用 checked cost 构造最终 `TWETColumn`。

为避免每个类重复写成本反推和复核逻辑，新增了 `PricingColumnCostRechecker`。它提供两件事：进入 K 堆前用 inferred reduced cost 反推临时候选列成本；K 堆固定后按 sequence 调用 `TWETColumnEvaluator`，得到 checked cost。当前已接入有 top-K 候选堆的双向 pricing 类：`GCBBStyleBidirectionalFullDomain`、`GCBBStyleBidirectionalFullDomainNodeJoin`、`GCNGBBStyleBidirectional` 和 `GCBBAsymmetricBidirectional`。普通 `GCBidirectional` 没有这套 K 堆流程，暂不适用这个“final K 后复核”的位置。

验证方面，focused `javac` 已通过。`wet021_001_2m` root-only node join 结果为 `obj=bound=6829`、`valid=true`，输出在 `test-results/bpc/tmp-nodejoin-finalk-checked-wet021.csv`；第一轮 `candidatePool kept/seen/dropped=5/14/9`，而 `debugNegGenerated checked/mismatch/signCritical/bothNeg/maxAbs=5/2/0/2/10.800000000001774`，说明当前只复核最终 K 堆里的 5 条，不再复核全部 14 个 seen 候选。full-domain arc join 同样 `obj=bound=6829`、`valid=true`，输出在 `test-results/bpc/tmp-arcjoin-finalk-checked-wet021.csv`；第一轮 `candidatePool kept/seen/dropped=5/10/5`，debug 输出只对应最终 kept 候选中的 mismatch。

### 6.24 K 堆最终复核的根节点限定

2026-05-31 继续收紧最终 K 候选复核口径。复核只在 `dualProfitableWindowEnabled=true` 时执行，也就是根节点、无 active cuts、启用了 `pi` profitable window 的 pricing 轮。非根节点、cut 节点或其他未启用 `pi` 时间窗加强的 pricing 中，候选函数口径已经回到原 hard window，不需要额外用 evaluator 扫最终 K 条，`finalizeGeneratedColumns(lp)` 直接把 K 堆列输出。

同时去掉了“复核后真实 reduced cost 仍为负才加入”的二次判断。原因是 `pi` 时间窗是原 hard window 的子区间，按该窗口计算的 inferred 成本不低于完整时间域 evaluator 成本；如果 inferred reduced cost 已经小于负列阈值，则真实 reduced cost 只会更小。这里的 evaluator 复核目的不是重新判断正负，而是把最终写入列池和主问题的 `TWETColumn.cost` 修正为真实 sequence 成本，避免把带 `pi` 时间窗口径的临时成本写成永久列成本。

### 6.25 `GCBidirectional` 与 K 堆流程的差异

2026-05-31 复查 `GCBidirectional` 后确认，它不是当前 GCBB-style 的 top-K 候选堆流程。`solve()` 先完整扩展 forward half，并在每个 forward label 出队时尝试 forward-to-sink；随后扩展 backward half，每个 backward label 出队时立即与已有 forward terminal labels 做 crossing-arc join。只要某条 sequence 的 inferred reduced cost 为负，`tryGenerateColumn()` 就直接加入 `generatedColumns`；`canContinue()` 只检查 `generatedColumns.size() < maxExactPricingColumns`，达到上限后直接停止后续扩展和 join。

因此 `GCBidirectional` 在列数上限触发时返回的是“当前队列排序和扫描顺序下最先发现的 K 条负列”，不是全局 reduced cost 最小的 K 条。默认 reduced-cost 队列排序会让低 label frontier 下界的 label 更早扩展，但 crossing-arc join 候选没有最终统一排序，也没有候选堆替换最差列。只有当 forward/backward 队列都耗尽、未触发上限时，它枚举到的负列集合才可作为 exact pricing 结果；若上限触发，则语义更接近在线生成前 K 条负列。若后续要让它也使用最终 K 堆复核，需要把直接 `generatedColumns.add()` 改成候选池，并明确是“已见候选 top-K”还是继续枚举到可证明没有更好候选。

### 6.26 封存旧 `GCBidirectional` 路径

2026-05-31 按当前判断，`GCBidirectional` 已不再作为后续优化入口。代码层面保留类和旧私有实现，避免引用断裂，但 `solve()` 已改为直接返回空列并给出停用说明；类注释中明确写入该路径的问题和封存原因。

封存原因有两类。第一是前一节提到的流程问题：它在线生成负列，达到 `maxExactPricingColumns` 后停止，不能表达当前 GCBB-style 分支采用的“先维护 top-K 候选堆，再在 K 堆固定后统一 finalize”的语义。第二是 pi-window 成本问题：根节点 no-cut 下的 pi profitable window 只是当前 pricing 的负列保留规则，不是永久列成本定义。旧类默认用 inferred reduced cost 反推 `TWETColumn.cost`，只有 `debugBPCPricingColumnCheck` 打开时才用 evaluator 复核，因此在正常运行中可能把带 pi-window 口径的临时成本写入列池和主问题。继续在这个类上局部补最终 K 复核并不合适，因为它没有最终 K 堆这个统一出口。

本次同时复查了其它双向 pricing 源码。除已封存的 `GCBidirectional` 外，当前实际算法类 `GCBBStyleBidirectionalFullDomain`、`GCBBStyleBidirectionalFullDomainNodeJoin`、`GCNGBBStyleBidirectional` 和 `GCBBAsymmetricBidirectional` 都使用 `generatedColumnCandidates` 维护 top-K 候选堆，并在 `finalizeGeneratedColumns(lp)` 中统一输出。它们当前只在 `dualProfitableWindowEnabled=true`，即根节点 no-cut 且启用 pi-window 加强时，对最终 K 堆候选调用 `PricingColumnCostRechecker.evaluate()` 修正列成本；非 pi-window 轮次直接输出 K 堆列，不做额外 evaluator 扫描。

### 6.27 full-domain arc join 最终 K 复核耗时检查

2026-05-31 重新用 `GCBBFullDomainComparisonTest` 跑 `wet021_001_2m`、`mode=fullDomain`、`maxNodes=1`，检查 full-domain arc join 的最终 K 堆复核次数和耗时。当前 root 一共两轮 exact pricing。第一轮 `candidatePool kept/seen/dropped=5/10/5`，说明 10 个 inferred 负候选中最终 K 堆只保留 5 条，因此 evaluator 最终复核 5 次；第二轮 `candidatePool kept/seen/dropped=0/0/0`，复核 0 次。打开 `debugColumnCheck` 时输出了 2 条 both-negative mismatch，说明这 5 条最终候选中至少 2 条确实需要用 evaluator cost 修正。

随后在 `GCBBStyleBidirectionalFullDomain` 中把 `finalizeGeneratedColumns()` 和其中的 evaluator recheck 单独计时，避免继续用“pricing 总时间减内部计时”的差值做归因。新的关闭 debug 复跑结果为：第一轮 `time=563.830ms`，内部统计 `timingMs fwExt/bwExt/join/finalize/recheck/extTotal/measuredTotal=272.364/59.807/106.826/9.058/7.994/332.171/448.055`，`finalRecheckCount=5`；第二轮 `time=334.633ms`，`timingMs ...=234.251/20.071/77.799/0.027/0.000/254.322/332.148`，`finalRecheckCount=0`。

因此前面按差值估算 `0.1~0.3s` 的说法需要作废：那个差值混入了框架、日志、JVM 和其它未单独计量开销，不能代表最终 K 条 evaluator 复核本身。以本次精确埋点为准，`wet021_001_2m` 第一轮 5 次最终复核合计约 `7.994ms`，约占本轮内部 measured total `448.055ms` 的 `1.8%`，占该轮 pricing 输出总时间 `563.830ms` 的 `1.4%`。在这个样例和 `K=5` 下，最终 K 复核不是主要耗时；但它仍随最终 K 条数线性增长，若后续把 K 放大，需要重新打开临时 recheck 计时，而不能再用总时间差粗估。

### 6.28 关闭最终 K 复核诊断冗余

2026-05-31 在确认“最终 K 后复核并用 evaluator cost 输出”这条主流程后，关闭了前面为验证临时增加的冗余诊断。`PricingColumnCostRechecker.evaluate()` 现在只调用 `TWETColumnEvaluator` 得到真实 objective cost，不再重算 checked reduced cost，也不再按 debug 开关输出 inferred/checked mismatch。`GCBBStyleBidirectionalFullDomain` 中用于测量 `finalize/recheck/finalRecheckCount` 的临时计时字段也已撤掉，日志恢复为原来的 forward/backward/join 三段内部耗时。`GCBBStyleBidirectionalFullDomainNodeJoin` 中 final K mismatch 计数同样关闭，只保留注释说明后续如需重新诊断可在该位置恢复。

这次清理不改变加列口径：K 堆仍按 inferred reduced cost 维护；只有根节点 no-cut 且启用 `pi` profitable window 时，`finalizeGeneratedColumns(lp)` 才对最终 K 堆候选调用 evaluator；最终输出给 `PC` 和列池的 `TWETColumn.cost` 使用 evaluator 的真实 sequence cost。关闭的是诊断和重复 reduced-cost 计算，不是最终成本修正。

### 6.29 join 下界阈值与 completion bound 预计算复查

2026-05-31 继续复查当前 join 剪枝阈值。`GCBBStyleBidirectionalFullDomain` 和 `GCBBStyleBidirectionalFullDomainNodeJoin` 现在的 group lower bound、pair lower bound 以及函数正式拼接后的 `findMinimal()` 结果，都是只和 `REDUCED_COST_TOLERANCE` 比较，也就是只判断是否可能为负列。K 堆只在 `rememberGeneratedCandidate()` 里生效：候选已经恢复 sequence、构造临时列并进入候选池逻辑后，若堆已满，才用当前 K 堆最差候选决定是否替换。因此当前代码没有在 join 前用 K 堆阈值剪掉“虽为负但不可能进入最终 K 堆”的 pair。

如果要加强这层剪枝，阈值不应使用“当前最好列”的 reduced cost。当前最好列是最负的那条；一旦用它作为下界阈值，后续只有比当前最好列还好的候选才会继续保留，极容易退化成一轮只剩一条列。这和 top-K 的语义不一致。更合适的阈值是：K 堆未满时仍用 0，因为任何负列都可能用于填满 K；K 堆已满后，使用当前 K 堆中最差的 kept reduced cost，也就是 worst-first heap 的 `peek()`。因此剪枝阈值可以理解为 `threshold = heapFull ? min(0, worstKeptReducedCost) : 0`。由于 worst kept 本身应为负，实际就是“未满看 0，已满看当前第 K 好列”。只有 lower bound 已经不可能小于这个阈值时，才说明该 group/pair 不可能进入当前 top-K，可以提前剪掉。

这层加强适合放在两个位置。第一是在 group LB 和 pair LB 处，用当前阈值替代固定的 0；因为这些 lower bound 是乐观下界，若下界都不优于当前第 K 好列，则真实拼接值也不可能进入 K 堆。第二是在函数已经正式 `add/findMinimal()` 之后，若 reduced cost 为负但不优于当前满堆的最差候选，可以跳过 sequence 恢复和候选构造。这时函数计算已经付出，收益只剩少量对象构造和堆操作节省；但它不应改变“负列才更新 K 堆”的原则。需要注意，这个动态阈值只保证“已见候选的 top-K”维护更紧，不把 pricing 变成 exact certificate；若后续仍要证明不存在更好负列，仍要依赖队列耗尽和安全 lower bound。

旧 VRP 代码没有看到在 `GCNGBB` 的 bounded bidirectional join 中使用“当前最好列”或“K 堆最差列”作为动态 join 阈值。`GCNGBB` 文件头写的是先生成 1000 条负 reduced-cost route，再选最好的 30 条；实际 join 中先用 `lbf.m_nosr_redcost + label.m_reduced_cost + lp.mu[cid] > -tolerance` 这类条件按 0 过滤，算出完整 cost 后也是 `cost < -tolerance` 才加入 `GCPool`。最后若 pool 大于 `addin_size`，再排序并取前 `addin_size` 条加入主 LP。代码里的 `m_min_cost` 主要用于记录更优的 cycle / NG-memory 相关 route，服务后续 NG-set 更新，不是 join 阶段 top-K 剪枝阈值。旧 `GCPulse` 里确实存在 `best_cost` 型 bound 剪枝，但那是 pulse 路径搜索，不是 `GCNGBB` 这套 bounded bidirectional label join 的主流程。

completion bound 预计算可以直接采用 node join 思路，而且不应被当前正式 node join pricing 的高复杂度吓住。正式 node join pricing 慢，主要是 elementary label 侧把 full-domain 跨界 terminal 候选显式放进普通 dominance graph 和 active join table，导致 label、reachable key 和 dominance 检查规模膨胀；node join 的公式本身不是主要矛盾。completion bound 是 relaxed DP，不保存每条路径的 visited set，也不维护每个 node 上大量 label，而是每个 relaxed state 维护一组 lower-envelope 函数。因此它可以直接使用 `U_state + Bbar_state` 的同 node 拼接口径。

第一版 all-cycles completion bound 可以按 node state 做：forward 维护 `U_i(t)` 和 `F_i(t)`，其中 `U_i` 是到达 node `i`、尚未加入 `i` 的 job penalty/job dual 的 pre-node 函数，`F_i` 是加入当前 node 成本后做 prefix-min 的传播函数；backward 维护含当前 node 成本的 `Bbar_i(t)` 和 suffix-min 后用于继续向前传播的 `B_i(t)`。forward 扩展从 `F_i` 出发，平移 setup/processing 后加 reduced arc cost 得到后继 `U_candidate_j`，再加 `g_j(t)` 并 prefix-min 得到 `F_candidate_j`。`U_j` 和 `F_j` 都取 lower envelope；是否重新入队主要看 `F_j` 是否改善，但 `U_j` 的改善也要保留，因为它会增强后续 `min_t U_j(t)+Bbar_j(t)` 的 node join bound。backward 方向同理，`Bbar` 服务拼接，`B` 服务传播。

2-cycle-free completion bound 则把 state 扩成 last-arc 口径：forward state 为 `(h,i)`，backward state 为 `(i,k)`，转移时禁止立即回到上一点。做严格 node join 时，`(h,i)` 只能和 `k != h` 的 backward suffix 拼；工程上可以为每个 join node 预存 backward 最优和 second-best suffix envelope，类似旧 VRP 中 `m_bd_fid/m_sec_bound` 的作用，避免每次枚举全部 successor。若第一版只把 completion bound 用作剪枝 lower bound，也可以先用更松的 `min_k Bbar_{i,k}`，不检查 `k != h`；这会让 bound 更弱，但不会造成错误剪枝。

因此建议后续把 completion bound 做成独立 calculator，而不是复用已封存的 node join pricing 类。输入使用当前 pricing 轮的 reduced arc cost、job dual、machine dual、hard/effective window 和分支 forbidden arc；第一版先不处理 cut dual 或 required-arc 的强制语义，只保证 bound 对当前节点是安全偏松的。输出可以先给每个 node 或 last-arc state 的 relaxed suffix/preffix bound 和统计。接入剪枝时，阈值同样沿用上面的规则：K 堆未满按 0 剪，K 堆已满按当前第 K 好列剪。这样 completion bound 既能减少进入函数 add/findMinimal 的正 pair，也能在 K 堆已经满后避免继续处理不可能进入最终 K 的负 pair。

### 6.30 关于用当前最优负列作为 join 阈值的补充分析

2026-05-31 继续讨论“是否可以不等 K 堆满，而是每次都用当前已经生成的最优负列加强 join 下界”。先确认旧 VRP：`GCNGBB` 的 bounded bidirectional join 没有利用当前已生成 route 的最优 reduced cost 来剪 join pair。它的主流程仍是按 0 判断负 reduced-cost route，加入 `GCPool` 后再排序取 `addin_size`。旧代码里的 `m_min_cost` 记录过当前最小 cost，但主要用于 cycle/NG-memory 相关更新，不是 join 阶段的动态下界阈值。

用“当前最优负列”做阈值在逻辑上是另一种目标：它不再维护 top-K，而是在维护 record-breaking candidates。假设当前最好负列 reduced cost 为 `bestRC`，且 `bestRC < 0`。若某个 group/pair 的 lower bound 已经 `>= bestRC`，那么真实拼接 reduced cost 也不可能小于 `bestRC`，因此若目标只是找到更优的一条负列，剪掉它是安全的。函数已经正式拼接以后也一样：若 `reducedCostBound >= bestRC`，则这条候选不会更新当前最好列，可以不恢复 sequence、不入候选池。只有当 `reducedCostBound < bestRC` 时，才更新 bestRC 并记录该列。

这个策略的代价也很明确：它会丢掉所有“仍然为负、但不刷新当前最好值”的列。若一开始就碰到全局最优负列，本轮确实可能只生成一条列；若搜索顺序逐步改进，则只保留一串不断刷新 bestRC 的列。这个结果不一定错误，但它的语义更像“best-improving pricing”或“record-only pricing”，不是当前 GCBB-style 的“最多返回 K 条较好负列”。从主问题列生成角度看，多个负列即使 reduced cost 不如当前最好列，也可能提供不同 job 组合、不同分支弧结构和更好的下一轮 RMP 稳定性；只返回 record-breaking 列可能减少每轮列数，增加 CG 迭代次数。

因此如果后续要试，建议明确做成实验开关，而不是替代现有 K 堆语义。可以区分三种模式：第一种是当前模式，只和 0 比较，所有 inferred 负候选进入 K 堆竞争；第二种是 top-K-threshold 模式，K 堆未满按 0，堆满后按当前第 K 好列剪枝；第三种是 best-record 模式，只要已有负列，就用当前最优负列作为 group/pair/function 阈值，只保留能刷新 bestRC 的候选。第二种仍服务 top-K，第三种服务“尽快找一条最强负列”。

实现细节上要注意当前 `generatedColumnCandidates` 是 worst-first heap，`peek()` 返回的是当前 K 堆里最差 kept 候选，不是最好的候选。若要做 best-record 阈值，不能直接用 K 堆顶；需要单独维护 `bestGeneratedReducedCost`，只在某个候选 reduced cost 为负且严格优于当前 best 时更新。若继续使用 K 堆，则堆顶适合做“第 K 好列阈值”，不适合做“当前最好列阈值”。

在 group/pair lower bound 上使用 bestRC 是安全但激进的：只要 lower bound 不优于 bestRC，就不可能产生刷新 best 的列，可以剪。因为 lower bound 是乐观值，这个剪枝不会漏掉比 best 更好的候选。它会漏掉的是不刷新 best 的负列，这是目标选择，不是下界数学错误。函数 `add/findMinimal()` 后再和 bestRC 比较也同理；这一步节省的是 sequence 恢复、候选构造和入堆，而不是函数拼接本身。

当前判断是可以做对比实验，但应该预期两类指标：一方面 join pair、function evaluation 之后的 sequence 恢复和候选池压力会下降，尤其 bestRC 很早变得很负时；另一方面每轮返回列数可能显著减少，整体 BPC/CG 轮数可能上升。最终是否划算要看完整 root solve 时间、exact pricing 时间、每轮生成列数、RMP 迭代数和最终 bound 收敛，而不能只看单轮 join 被剪掉多少。

### 6.31 bestRC 阈值与 K 堆堆顶语义澄清

2026-05-31 继续澄清 bestRC 阈值和 K 堆堆顶的关系。当前 K 堆使用 worst-first heap，并不是因为“不能知道最好列”，而是因为 top-K 容量控制最常用的操作是：当堆已经有 K 条候选时，新候选若优于当前最差 kept 候选，就弹出最差并插入新候选；否则丢弃新候选。因此 `peek()` 放最差候选，可以让替换判断和删除最差都是 `O(log K)` 或 `O(1)+O(log K)`。如果堆顶放最好候选，则每次容量满时要找最差候选才能决定淘汰谁，反而不适合 bounded top-K。若算法还需要当前最好负列，应额外维护一个 `bestGeneratedReducedCost`，这和 worst-first K 堆不冲突。

本轮讨论的 bestRC 剪枝可以拆成两个不同模式，而不是一个模式。

第一种是“pair 下界 bestRC 预剪枝”。当已经存在当前最好负列 `bestRC < 0` 时，label pair 或 group 的乐观下界若已经 `>= bestRC`，则该 pair 不可能产生比当前最好列更优的候选，可以跳过函数拼接。这个模式只加强函数拼接前的过滤；对那些 lower bound 仍然 `< bestRC` 的 pair，后续仍可执行 `add/findMinimal()`。如果函数真实值为负但不刷新 bestRC，仍然可以按原 top-K 逻辑入候选堆。因此该模式不一定只生成 record-breaking 列，它只是用 bestRC 去减少一部分“肯定不能刷新 best 的函数拼接”。由于 lower bound 通常较松，很多最终不刷新 best 的负列仍会通过该预剪枝进入后续 K 堆，所以它比 record-only 温和。

第二种是“函数真实值 bestRC 过滤”。在 `forward + backward` 函数已经正式拼接并 `findMinimal()` 后，如果得到的真实 reduced cost 没有优于当前 `bestRC`，就不恢复 sequence、不进入候选堆；只有严格刷新 bestRC 的负候选才保留。这才是 record-only / best-improving pricing：若一开始遇到全局最优负列，本轮可能只生成一条列；若搜索过程中不断刷新 best，则只保留这串刷新记录。它节省的是 sequence 恢复、候选构造和入堆，不节省已经发生的函数拼接计算。

因此后续实验可以分清三种阈值层级：基础模式只和 0 比；bestLB 模式只在函数拼接前用 pair/group lower bound 和 bestRC 比，函数拼接后仍按负列进入 K 堆；bestRecord 模式在函数拼接前用 bestRC 预剪枝，并在函数拼接后也要求真实 reduced cost 刷新 bestRC 才保留。`bestRC` 只在出现负候选时更新；没有任何负列前，阈值仍应回退到 0，否则没有可比对象。

### 6.32 bestRC 阈值实验开关实现与小样例结果

2026-05-31 已按上面的三层语义给使用 K 堆的四个双向分支都增加实验开关 `bidirectionalJoinBestThresholdMode`，默认值为 `zero`。当前覆盖 `GCNGBBStyleBidirectional`、`GCBBAsymmetricBidirectional`、`GCBBStyleBidirectionalFullDomain` 和 `GCBBStyleBidirectionalFullDomainNodeJoin`。这些 pricing 类现在额外维护 `bestGeneratedReducedCost`，只在发现负候选并准备进入候选逻辑时更新；K 堆仍保持 worst-first，用于 top-K 容量控制，不再被误用来表示当前最好列。`bestLB` 模式用 `bestGeneratedReducedCost` 替代固定 0 作为 group/pair lower bound 的动态阈值，但函数正式拼接后仍允许所有负列进入 K 堆竞争。`bestRecord` 模式在 `bestLB` 的基础上进一步要求函数拼接后的真实最小 reduced cost 必须刷新 bestRC，否则不恢复 sequence、不进入 K 堆；`tryGenerateColumn()` 对 forward-to-sink 这类完整列也做同样的 record-only 过滤，避免模式语义不一致。

测试入口 `GCBBFullDomainComparisonTest` 增加系统参数 `twet.bpc.fullDomainCompare.joinBestMode`，输出 mode 名会在非 `zero` 时追加 `-bestLB` 或 `-bestRecord`，方便 CSV 和日志区分。

验证方面，focused `javac` 通过。随后对 `wet021_001_2m` 做 root-only full-domain arc join 对比，参数为 `maxNodes=1`、`maxExactColumns=5`。三组结果均为 `status=ROOT_PROCESSED`、`obj=bound=6829`、`valid=true`。基础 `zero` 模式 exact pricing 两轮合计约 `0.773s`，第一轮 `candidatePool kept/seen/dropped=5/10/5`，`joinBest ...=ZERO/-12.0/0/0`。`bestLB` exact pricing 两轮合计约 `0.737s`，第一轮仍为 `candidatePool kept/seen/dropped=5/10/5`，但 `joinBest ...=BEST_LB/-12.0/19551/0`，说明它确实用 bestRC 多剪了一批 group/pair lower-bound 候选，且没有减少本轮返回列数。`bestRecord` exact pricing 约 `0.965s` 且 exact call 从 2 次变成 3 次；第一轮只返回 2 列，`candidatePool kept/seen/dropped=2/2/0`，`recordPruned=8`，第二轮又返回 2 列，随后第三轮才无改进。这个小样例支持前面的判断：`bestLB` 是温和剪枝，可能有收益；`bestRecord` 会显著减少每轮加列数，可能增加 pricing/CG 轮数，不能只看单轮候选减少。

因此代码中已把 `bestRecord` 标注为激进 record-only 对照模式。它保留在开关中便于复现实验和后续对照，但默认不作为正式改进路径继续推进；后续若继续优化 join 阈值，应优先围绕 `bestLB` 或 K 堆第 K 好列阈值展开。

### 6.33 bestRC 实验入口覆盖复查

2026-05-31 继续复查 bestRC join 阈值相关改动的覆盖范围。当前源码中通过 `TWETBPCContext` 可进入、且使用最终 K 候选堆的双向 pricing 分支为 `GCNGBBStyleBidirectional`、`GCBBAsymmetricBidirectional`、`GCBBStyleBidirectionalFullDomain` 和 `GCBBStyleBidirectionalFullDomainNodeJoin`，四者均已接入 `bidirectionalJoinBestThresholdMode`、独立 `bestGeneratedReducedCost`、`joinBest` 统计以及 `zero/bestLB/bestRecord` 三种语义。旧 `GCBidirectional` 已封存，不属于 K 堆分支；目录中残留的 `GCNGBBStyleBidirectionalFullDomain`、`GCNGBBAsymmetricBidirectional` 只有历史 `.class`，当前没有对应 `.java` 源码和正式入口，因此本轮不再追改。

实验入口层面，原先只有 `GCBBFullDomainComparisonTest` 支持 `twet.bpc.fullDomainCompare.joinBestMode`。本次补齐 `GCBBAsymmetricComparisonTest`、`BidirectionalQueueStrategyComparisonTest` 和 `SingleBidirectionalTimeQueueComparisonTest` 的系统属性透传，分别使用 `twet.bpc.asymmetricCompare.joinBestMode`、`twet.bpc.queueCompare.joinBestMode` 和 `twet.bpc.timeCompare.joinBestMode`。当模式不是 `zero` 时，输出 mode/log 名会追加 `-bestLB` 或 `-bestRecord`，便于和默认结果区分。由此普通 Tmid、full-domain arc/node join 和非对称分支都可以通过本地 harness 直接做同口径对照。
