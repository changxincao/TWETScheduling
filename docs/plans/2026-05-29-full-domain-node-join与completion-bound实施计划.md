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
