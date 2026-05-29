# Completion Bound 与 Node Join 设计整理

本文整理自 `docs/plans/2026-05-29-completion-bound尝试和node-join原始记录.txt`，并融合后续关于 full-domain、node join 和状态函数 correcting 的补充判断。目标不是给出最终代码，而是把当前关于 completion bound 和 node join 的核心逻辑沉淀清楚，方便后续实现时逐项对照。

## 1. 原始问题一：在原网络上计算两种松弛的 completion bound

原问题保留如下：

> 不要做扩展网络，就在原网络上，我现在要基于这两种松弛的labeling方式去做completion bound，即计算正向的和反向的到一个点i的最小成本。是一个函数。 1、允许任意环路，label应该只需要记录最早开始时间，reachable set 还有成本函数？然后应该不需要占优？到一个node的时候把两个label mergemunimum就好了。也不需要记录路径，因为我只需要一个最小成本函数。 2、不允许2-cycle的环路，这时候咋做呢？

这里的核心结论是：如果目标只是预处理 relaxed completion bound，而不是生成正式 elementary pricing 列，那么不需要构造扩展网络，也不需要保存完整路径、visited set 或 dominance graph。Spliet and Gabor 使用扩展无环图，主要是为了把 route relaxation 放到一个可拓扑扫描的辅助图上；松弛模型本身并不强制要求扩展图。在原网络上直接做也可以，只是原网络有环，因此递推顺序要改成 queue-correcting。

### 1.1 允许任意环路的 all-cycles bound

允许任意环路时，状态只需要是当前 node。对每个节点 `i` 维护一个函数：

```text
F_i(t)
```

其含义是：从 source 到 `i`，并且 `i` 的完成时间不晚于 `t` 时的最小 relaxed reduced cost。这里的“不晚于”很重要，它对应 forward 方向的 prefix-min normalize。

设 `g_j(t)` 是 job `j` 在完成时间 `t` 的节点 reduced cost，例如时间惩罚减去 job dual；设 `cbar_ij` 是弧 `i -> j` 的 fixed reduced cost，`Delta_ij = setup_ij + p_j`。从 `i` 扩展到 `j` 时，可以写成：

```text
candidate_j(t) = g_j(t) + cbar_ij + F_i(t - Delta_ij)
F_j(t) <- min_{u <= t} min(F_j(u), candidate_j(u))
```

更实现化地说，就是先把 `F_i` 按 `Delta_ij` 平移，再加上弧 reduced cost 和 `j` 的节点函数，最后取 lower envelope 并做 prefix-min。到同一个 node 的多条路径不用保留成多个 label，而是直接合并为一个 lower envelope 函数。普通 labeling 里的 dominance，在这里退化为函数之间的 envelope update。

因此 all-cycles completion bound 不需要：

1. visited set；
2. reachable set 作为状态；
3. 完整路径；
4. dominance graph。

可以保留一个临时 reachable/candidate 集合加速扫描，但它只是当前函数和时间窗下的直接可扩展候选，不应进入 DP 状态。

Backward 方向对称维护 `B_i(t)`。如果它表示从 `i` 出发到 sink 的 suffix 最小 reduced cost，那么通常会使用 suffix-min normalize，即 `B_i(t)` 表示在不早于某个时间要求下从 `i` 继续到终点的最小成本。实现上与当前 backward label frontier 的方向一致。

### 1.2 禁止 2-cycle 的 relaxed bound

如果只禁止 `i -> j -> i` 这种 2-cycle，那么状态不能只记录当前 node，因为从 `i` 扩展到 `j` 时必须知道上一跳是不是 `j`。这时 forward 状态应改为 last-arc state：

```text
F_{h,i}(t)
```

其含义是：从 source 到 `i`，最后一条弧是 `h -> i`，并且 `i` 不晚于 `t` 完成时的最小 relaxed reduced cost。扩展到后继 `j` 时，只需禁止 `j == h`：

```text
F_{i,j} <- min(F_{i,j}, Extend_{i,j}(F_{h,i}))    where j != h
```

也可以预先定义排除型 envelope：

```text
F_i^{\j}(t) = min_{h != j} F_{h,i}(t)
F_{i,j} <- Extend_{i,j}(F_i^{\j})
```

Backward 方向则记录 immediate successor：

```text
B_{i,k}(t)
```

如果要在 `i` 前面接一个 predecessor `h`，就需要使用 `k != h` 的 suffix envelope，避免形成 `h -> i -> h`。

这个版本本质上仍然不是 elementary labeling。它只是把 all-cycles 的 node state `i` 扩展成 first-order history state `(h,i)`。与经典 VRP q-route 里的关系可以理解为：all-cycles 是 `(i,q)`，2-cycle-free 是 `(h,i,q)`；当前如果没有 load 维度，就是 `i` 和 `(h,i)`。

## 2. 原始问题二：Node Join、双计数和 completion bound 是否可统一

原问题保留如下：

> OK，有道理，给我总结下无环限制的和有2-cycle限制的这个预处理的completion bound的函数该如何做。以及这里我觉得可以处理一下，不像正式labeling做的时候正向反向是用arc拼接的，这是因为不用arc的话那一半的定义域不好设置，因为如果用node做应该第一次超过一半才停止，这样的话函数定义域不好设置。即使设置为全域的，此时也还是有点问题。因为在node拼的时候，此时相当于node i的成本函数和dual都被重复记录了应该减掉，但之前的label里边定义的是min以后的函数，此时没办法还原回去加了i函数以后的那个函数了。所以直接用arc拼接。 但其实可以这么做？ 1、还是双向的labeing里边，也是可以做单点拼接的，此时像上边说的，必须使用full-domain的函数定义域，此时可以减少拼接时候的arc 的平方级的复杂度。具体做法为，现在的label里边存储的函数为到达（比如前向的）任务i的时候，不超过t完成时的最小成本。而这个东西是根据上一个label，比如在j，即j完成的不超过t的最小成本函数加上i的成本函数和相关dual得到的，此时其实是两个步骤，即先加i的成本函数得到任务i恰好在t完成时的最小成本，然后前向minimize得到不超过t的最小成本。可以把那个恰好的函数记录到label里边。 2、这样的话，我们其实就可以在一个node去做拼接了，即使用这个node上的正向label的这个恰好的函数，减去这个node的这个惩罚函数（那其实好像就是上一个label的不超过的最小函数平移那段delta时间以后的函数），那好像直接存这个也行。从而利用这个东西其实就可以和反向的在当前点的做拼接了？相当于此时node i的惩罚函数被反向的label加上了，而setup相关的由正向的去做？ 同理，那completion bound的时候也可以类似的做法，而且有cycle的和2-cycle限制的应该都可以直接使用动态规划的状态去递推？不需要labeling? 好像还是需要？不然如何递推呢？ 给我分析下

这个问题可以拆成两件事：第一，预处理 completion bound 怎么算；第二，正式 bidirectional pricing 是否可以把 crossing-arc join 改成 node join。二者都用函数，但语义不同，不能混在一起。

Completion bound 是 relaxed DP，可以只保存每个 DP 状态的最优函数，用于给正式 pricing 提供下界。正式 bidirectional pricing 需要生成真实列，因此仍然要保留 path label、visited/reachable 信息和分支兼容性。Node join 是正式 pricing join 方式的一种替代，它不等价于 relaxed completion bound。

### 2.1 为什么之前正式双向 labeling 使用 arc join

之前使用 arc join 的主要原因不是 node join 数学上不能做，而是 half-domain 双向标签下 node join 的函数边界和双计数都更难处理。

在 arc join 中，forward label 到 `i`，backward label 从 `j` 开始，中间用 crossing arc `i -> j` 拼接。这样允许 crossing arc 横跨 `Tmid`：forward 侧只需要扩展到半区间边界之前，backward 侧也只需要扩展到另一半边界之前，真正跨过中点的是 join 阶段的一条弧。因此 half-domain 版本可以把 forward/backward 函数定义域裁成各自半域，标签超过 `Tmid` 就不继续扩展，join 时通过 crossing arc 处理边界。

如果改成 node join，正反两侧要在同一个 node、同一个完成时间上相遇。这样不能简单说 forward 到 `Tmid` 停、backward 到 `Tmid` 停，因为某个节点的完成时间可能需要“第一次超过一半以后”才知道能不能和另一侧相遇。也就是说，node join 的自然边界不是一条 crossing arc，而是一个共同的时间点。half-domain 下这个共同时间点的函数定义域很难干净裁剪。

因此 node join 更适合放在 full-domain 标签上尝试，即 forward/backward 函数都定义在完整 `[0,Cmax]` 或当前 pricing horizon 上。这样每个 node 上的正反函数都有完整时间域，拼接时直接在同一时间变量上求最小值，不需要再为半域边界设计额外延拓规则。

当前 full-domain 实验还有一个支持这个方向的现象：full-domain 函数定义域没有比只取一半慢很多，一些样例甚至更快。因此可以在 full-domain GCBB 对照框架上尝试 node join，而不必先假设 full-domain 一定会把函数操作拖慢。

### 2.2 Node join 的双计数问题

如果 forward label 存的是：

```text
F_i(t) = i 不晚于 t 完成的 prefix-min reduced cost
```

backward label 存的是：

```text
B_i(t) = 从 i 出发到 sink 的 suffix-min reduced cost
```

那么直接用 `F_i(t) + B_i(t) - g_i(t)` 并不严谨。原因是 `F_i(t)` 已经做过 prefix-min，它在时间 `t` 上的值可能来自某个更早的 `u < t`，里面包含的 `g_i(u)` 不一定是 `g_i(t)`。因此不能从 `F_i(t)` 中可靠地“减掉当前 t 的 node i 成本”。

更干净的做法是把 forward 侧拆成两个函数：

```text
U_i(t)      进入 i 之前的成本，包含 incoming arc/setup，但不包含 i 的节点成本
Fbar_i(t)  i 恰好在 t 完成且已经包含 i 节点成本的成本
F_i(t)     prefix-min 后用于继续扩展的成本
```

三者关系是：

```text
Fbar_i(t) = U_i(t) + g_i(t)
F_i(t) = min_{u <= t} Fbar_i(u)
```

Backward 侧可以存：

```text
Bbar_i(t)  i 在 t 完成时，从 i 到 sink 的 suffix exact cost，包含 i 的节点成本
B_i(t)     suffix-min 后用于继续向前扩展的成本
```

那么 node join 可以写成：

```text
min_t { U_i(t) + Bbar_i(t) }
```

这里 `i` 的节点成本只由 backward 的 `Bbar_i(t)` 计算一次；incoming setup/arc 由 forward 的 `U_i(t)` 负责；outgoing setup/arc 和后续节点成本由 backward 负责。

## 3. 后续修正：状态里是否需要记录“恰好函数”

后续补充分析原文保留如下：

> 这里的话适合用动态规划状态的方式，每个状态下也是恰好的函数和不超过的函数都记录下？扩展的时候使用不超过的去扩展，扩展到一个状态更新这个状态的时候，同时更新恰好的函数以及不超过的函数。[不好不需要记录这个恰好的函数？直接记录来源的那个不超过的函数平移以后的那个就好了？只需要记录这个和这个函数加上当前node惩罚函数以后的minimize的函数，前者用于拼接计算bound，后者则用于进一步扩展。] 然后correcting的时候，判断一个状态是否需要再次入队，只需要判断这个状态下的函数被更新了没有，可以比较每个段的斜率，区间，截距等，前向扫描，不一致跳出就好了。

这段判断总体是正确的，但需要把“恰好函数”的语义说清楚。对于 node join 来说，真正需要保存的不是“已经加了当前 node 成本的 exact 函数”，而是更有用的 pre-node/arrival 函数：

```text
U_state(t) = 来源状态的不超过函数平移到当前 node 后，再加 incoming arc/setup reduced cost 的函数
```

它代表“当前 node 可以在 `t` 完成时，进入当前 node 之前的 prefix 成本”，不包含当前 node 的 `g_i(t)`。这个函数用于 node join 或 completion bound 拼接，因为它不会和 backward 侧的当前 node 成本重复。

然后再由它计算用于继续扩展的 propagation 函数：

```text
Fbar_state(t) = U_state(t) + g_i(t)
F_state(t) = prefixMin(Fbar_state)(t)
```

后续扩展只需要 `F_state`，因为扩展到下一个 node 时，使用的是“当前状态不晚于某个时间完成的最小 prefix 成本”。Node join 或 two-sided bound 拼接需要 `U_state`，因为它是未加入当前 node 的函数。

因此，状态里最实用的两类函数是：

1. `U_state`：来源的不超过函数平移以后、加 incoming arc/setup reduced cost、但不加当前 node 成本的函数，用于 node join 或 bound 拼接；
2. `F_state`：`U_state + 当前 node 成本` 后做 prefix-min 得到的函数，用于继续扩展。

中间的 `Fbar_state = U_state + g_i` 可以按需临时算。如果实现中为了避免重复计算，也可以缓存；但从状态语义上，它不是必须独立保存的核心函数。

Backward 方向同理，只是方向相反。可以保存一个“当前 node exact suffix”函数 `Bbar_state`，它包含当前 node 成本，用于和 forward 的 `U_state` 拼接；同时保存 suffix-min 后的 `B_state`，用于继续向前扩展。

这也解释了为什么 node join 虽然要求 label 里多记录函数，但不一定增加计算量。Arc join 里原本在尝试拼接时也会做 shift、add、find-minimal 之类的函数运算，只是这些中间结果没有保存在 label 状态里。Node join 的做法是把其中一部分中间函数显式留住，减少后续重复计算，并换取按 node 分组拼接的可能性。

## 4. 原始问题三：递推顺序是否应当 label-correcting

原问题保留如下：

> 递推式子是这样没错，但是递推顺序呢？这种是不是应该像label correcting那么做？递推以后如果一个状态有改进，那么让他重新进入队列？以进一步更新其他节点？如果这么做是对的话？那其实也可以直接用动态规划的状态去做递推。 不需要labeling 以及2-cycle的completion bound不需要那个判断反向和正向的是否存在2-cycle吧，现在的逻辑应该是，正向的2-cycle的completion bound是正向任意路径到点j的最小成本的下界，反向的则是elementary的到点j的最小成本，没必要做你说的那个吧？

结论是：在原网络上做函数型 completion bound，递推顺序应当是 label-correcting / Bellman-Ford-style 的状态函数迭代。它不是正式 elementary labeling，但实现形式仍然是 correcting。

All-cycles 版本的伪代码可以写成：

```text
F[i] = +infinity
F[source] = source function
Q = {source}

while Q not empty:
    i = pop(Q)
    for each successor j:
        U_candidate = shift(F[i], Delta_ij) + cbar_ij
        F_candidate = prefixMin(U_candidate + g_j)
        if lowerEnvelope(F[j], F_candidate) improves F[j]:
            update U[j] if this candidate contributes to the envelope
            update F[j]
            push j
```

2-cycle-free 版本的伪代码为：

```text
F[h][i] = +infinity
initialize source -> j states
Q = initialized states

while Q not empty:
    (h, i) = pop(Q)
    for each successor j:
        if j == h:
            continue
        U_candidate = shift(F[h][i], Delta_ij) + cbar_ij
        F_candidate = prefixMin(U_candidate + g_j)
        if lowerEnvelope(F[i][j], F_candidate) improves F[i][j]:
            update U[i][j] if this candidate contributes to the envelope
            update F[i][j]
            push (i, j)
```

这里“状态函数被更新就重新入队”是正确的。队列清空时，说明所有状态的所有出弧扩展都无法再改善后继状态，此时得到的是该 relaxed DP 的 fixed point。只要满足有限 horizon/capacity、每次扩展消耗正资源、没有零资源负 reduced-cost 环、lower-envelope 更新精确，队列清空时的函数就是对应 relaxation 下的最优 completion bound。

关于“2-cycle completion bound 是否需要检查正反拼接处的 2-cycle”，要区分用途。如果只是用于正式 elementary pricing 的剪枝，completion bound 只需要是 lower bound。拼接处即使允许更松的 2-cycle，也只会让 bound 更乐观、更小，不会错误剪枝，只会剪枝弱一些。因此用于 pruning 时，不需要强行检查 forward predecessor 和 backward successor 是否相同。

只有当目标是“严格表示一个 no-2-cycle relaxed full-route 的双向拼接 bound”时，才需要检查：

```text
forward predecessor != backward successor
```

也就是避免 `h -> i -> h`。但如果只是 completion bound for pruning，这个兼容性检查不是必要条件。

## 5. Completion Bound 的两种松弛方式如何用于 Node 拼接

### 5.1 All-cycles bound 下的 node 拼接

All-cycles 版本每个 node 一个 forward 状态和一个 backward 状态。若使用 node join，建议维护：

```text
U_i(t)      forward pre-node 函数，不含 i 的节点成本
F_i(t)      forward prefix-min 函数，用于继续扩展
Bbar_i(t)   backward exact suffix 函数，含 i 的节点成本
B_i(t)      backward suffix-min 函数，用于继续向前扩展
```

节点 `i` 上的拼接 bound 为：

```text
CB_i = min_t { U_i(t) + Bbar_i(t) }
```

对所有 node 取最小即可得到基于 node join 的 relaxed route lower bound。如果它只是给正式 label 做剪枝，也可以只用一侧当前 label 成本加另一侧的 relaxed suffix bound，不必强行构造完整 relaxed route。

### 5.2 2-cycle-free bound 下的 node 拼接

2-cycle-free 版本 forward 状态是 `(h,i)`，backward 状态是 `(i,k)`。如果要严格表示 no-2-cycle relaxed node join，则拼接时需要排除 `h == k`：

```text
CB_i = min_h min_t { U_{h,i}(t) + Bbar_i^{\h}(t) }
Bbar_i^{\h}(t) = min_{k != h} Bbar_{i,k}(t)
```

但如果只是用于剪枝，可以直接使用更松的 backward envelope：

```text
CB_i_relaxed = min_h min_t { U_{h,i}(t) + Bbar_i(t) }
Bbar_i(t) = min_k Bbar_{i,k}(t)
```

这个 bound 更弱，但仍然安全。是否使用严格排除，取决于要的是更强 bound 还是更简单实现。

## 6. Correcting 中如何判断状态是否需要再次入队

状态是否再次入队，应以“该状态的主传播函数是否被改善”为准。对 forward 来说，主传播函数是 `F_state`；对 backward 来说，是 `B_state`。`U_state` 或 `Bbar_state` 用于拼接，但它们最终也应与主函数的 envelope 贡献保持一致。

最稳的工程接口是让 lower-envelope 更新函数返回一个 `changed` 标记：

```text
newFunction = lowerEnvelope(oldFunction, candidate)
changed = !piecewiseFunctionEquals(oldFunction, newFunction)
if changed:
    state.function = newFunction
    enqueue(state)
```

函数比较可以按分段线性函数的 segment 链表前向扫描：

1. 同时遍历 old 和 new 的 segment；
2. 比较每段的 `start/end/slope/intercept`；
3. 任一项超过 tolerance 就认为函数已更新，立即返回 changed；
4. 如果一边提前结束，也认为 changed；
5. 全部一致才认为不需要重新入队。

如果 lower-envelope 操作本身能在合并时判断是否有新增有效 segment 或旧 segment 被压低，则可以直接返回 changed，避免再扫一遍。但即使做二次 segment 比较，逻辑也清楚，适合先实现正确版本。

## 7. 最终总结

当前可以使用 node join 的原因是：full-domain 标签已经把 forward/backward frontier 定义在完整 `[0,Cmax]` 或 `pricingHorizon` 上，正反两侧在同一个 node、同一个完成时间 `t` 上都有可评价的函数。只要额外保存 pre-node/exact-suffix 这类避免双计数的函数，就可以在 node 上拼接，而不必枚举所有 crossing arc。

之前没有优先使用 node join，而采用 arc join，主要是因为 half-domain 双向框架下 arc join 更自然。Arc join 允许 forward/backward label 到 `Tmid` 附近就停止扩展，中间 crossing arc 可以横跨 `Tmid`，因此函数定义域可以裁成半域。Node join 则要求同一 node 同一时间相遇，往往要等 label 第一次超过半区间后才知道是否可拼，半域边界不好定义；为了让 node join 干净成立，基本只能使用 full-domain 函数定义域。

Full-domain 的实验现象说明，它并没有比只取一半定义域慢很多，一些样例还会更快。因此，在 full-domain GCBB 上尝试 node join 是合理的实验方向，不必被“函数定义域翻倍一定很慢”的直觉限制。

Node join 的关键技术点是避免 node 成本被两侧重复加入。只存 prefix-min 的 `F_i(t)` 不够，因为它无法还原“恰好在 t 完成时加入的 `g_i(t)`”。更合适的状态记录是：forward 侧保存来源的不超过函数平移后的 pre-node 函数 `U_state`，用于拼接；同时保存 `U_state + 当前 node 成本` 后 prefix-min 得到的 `F_state`，用于继续扩展。Backward 侧保存包含当前 node 成本的 exact suffix 函数 `Bbar_state`，以及 suffix-min 后的 `B_state`。这些函数在 arc join 中其实已经以中间计算的形式出现过，只是没有作为 label/state 字段保存，因此 node join 并不必然增加函数计算量。

Completion bound 可以基于同一套状态思想实现。All-cycles 版本使用 node state；2-cycle-free 版本使用 last-arc state。二者都不是正式 elementary labeling，而是函数型 DP state correcting。状态函数被 lower envelope 改善后重新入队，继续传播到后继状态；当队列清空时，在有限 horizon、正资源消耗、无零资源负环、精确 envelope 更新的条件下，得到对应 relaxation 的最优 completion bound。用于剪枝时，正反 relaxed bound 的拼接不需要严格检查 join 处是否仍满足 2-cycle-free；不检查只会让 bound 更松，不会造成错误剪枝。只有当目标是构造严格的 no-2-cycle relaxed full-route bound 时，才需要在 node join 或 arc join 处做 predecessor/successor 兼容性检查。
