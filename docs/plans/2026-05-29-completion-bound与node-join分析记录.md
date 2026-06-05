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

为了避免符号混淆，可以把你原文里的两步拆开看。你原来写的是：先从来源状态的“不超过函数”平移到当前 node，这一步其实还没有加入当前 node 的惩罚成本；再把它加上当前 node 惩罚函数并做 minimize，得到新的“不超过函数”。这里前一步就是本文记的 `U_state`，后一步就是 `F_state`。如果中间显式写出“恰好完成且已加入当前 node 成本”的函数，它是 `Fbar_state = U_state + g_i`。

更具体地，对 forward 的一个状态 `state = i`，或者 2-cycle-free 里的 `state = (h,i)`，设当前 node 是 `i`，`g_i(t)` 表示 node `i` 在完成时间 `t` 的 reduced node cost，也就是时间窗惩罚减去 job dual。若从前驱状态 `pred` 扩展到 `i`，有：

```text
U_candidate(t) = shift(F_pred, Delta_pred,i)(t) + reducedArcCost(pred, i)
Fbar_candidate(t) = U_candidate(t) + g_i(t)
F_candidate(t) = prefixMin(Fbar_candidate)(t)
```

其中 `U_candidate(t)` 是“到达/接入 node i、但还没把 i 自己的惩罚成本算进去”的函数；`Fbar_candidate(t)` 是“node i 恰好在 t 完成、且已经包含 i 成本”的函数；`F_candidate(t)` 是“node i 不晚于 t 完成的最小 prefix 成本”。状态最终维护的是这些候选的 lower envelope：

```text
U_state = lowerEnvelope(all U_candidate)
F_state = lowerEnvelope(all F_candidate)
```

因此 `U_state` 不是一个路径意义上的完整 label frontier，它少算当前 node 的成本，专门给 node join 用；`F_state` 才是继续向后扩展时使用的完整 prefix frontier。你原文里说“不好不需要记录这个恰好的函数？直接记录来源的那个不超过的函数平移以后的那个就好了”，对应的就是记录 `U_state`，而不是记录 `Fbar_state`。

Backward 侧的对应关系反过来。`Bbar_state(t)` 表示“从当前 node `i` 在 `t` 完成开始往右走到 sink 的 exact suffix 成本”，它包含当前 node `i` 的成本；`B_state(t)` 是对 `Bbar_state` 做 suffix-min 后得到的传播函数，用来继续向左扩展。node join 时用：

```text
join_i = min_t { U_forward_state_at_i(t) + Bbar_backward_state_at_i(t) }
```

这样当前 node `i` 的成本只在 backward 的 `Bbar_state` 里算一次，forward 的 `U_state` 不再重复算 `i`。

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

之前没有优先使用 node join，而采用 arc join，最核心原因是旧 label frontier 的定义不适合在同一个 node 上扣重。当前 forward/backward frontier 都是经过 prefix/suffix normalize 后的“已包含当前 node 成本”的函数；如果直接在 node `i` 上拼，`i` 的惩罚成本和 job dual 会被两侧重复计入，而 normalize 后的 frontier 又不能稳定还原“恰好在同一时间 `t` 加入的 `g_i(t)`”。因此旧实现使用 crossing arc，把 forward 终点和 backward 起点放在两个不同 job 上，天然避开同一 node 成本扣重问题。

第二个主要原因才是 half-domain/Tmid。Arc join 允许 forward/backward label 到 `Tmid` 附近就停止扩展，中间 crossing arc 可以横跨 `Tmid`，因此函数定义域可以裁成半域。Node join 则要求同一 node 同一时间相遇，往往要等 label 第一次超过半区间后才知道是否可拼，半域边界不好定义；为了让 node join 干净成立，基本只能使用 full-domain 函数定义域。

Full-domain 的实验现象说明，它并没有比只取一半定义域慢很多，一些样例还会更快。因此，在 full-domain GCBB 上尝试 node join 是合理的实验方向，不必被“函数定义域翻倍一定很慢”的直觉限制。

Node join 的关键技术点是避免 node 成本被两侧重复加入。只存 prefix-min 的 `F_i(t)` 不够，因为它无法还原“恰好在 t 完成时加入的 `g_i(t)`”。更合适的状态记录是：forward 侧保存来源的不超过函数平移后的 pre-node 函数 `U_state`，用于拼接；同时保存 `U_state + 当前 node 成本` 后 prefix-min 得到的 `F_state`，用于继续扩展。Backward 侧保存包含当前 node 成本的 exact suffix 函数 `Bbar_state`，以及 suffix-min 后的 `B_state`。这些函数在 arc join 中其实已经以中间计算的形式出现过，只是没有作为 label/state 字段保存，因此 node join 并不必然增加函数计算量。

Completion bound 可以基于同一套状态思想实现。All-cycles 版本使用 node state；2-cycle-free 版本使用 last-arc state。二者都不是正式 elementary labeling，而是函数型 DP state correcting。状态函数被 lower envelope 改善后重新入队，继续传播到后继状态；当队列清空时，在有限 horizon、正资源消耗、无零资源负环、精确 envelope 更新的条件下，得到对应 relaxation 的最优 completion bound。用于剪枝时，正反 relaxed bound 的拼接不需要严格检查 join 处是否仍满足 2-cycle-free；不检查只会让 bound 更松，不会造成错误剪枝。只有当目标是构造严格的 no-2-cycle relaxed full-route bound 时，才需要在 node join 或 arc join 处做 predecessor/successor 兼容性检查。

## 8. 正确性复核与实现边界

上面的总体方向是正确的：completion bound 可以在原网络上用函数型 DP state correcting 做；all-cycles 使用 node state，2-cycle-free 使用 last-arc state；node join 在 full-domain 标签上成立的关键，是额外维护不会重复计入当前 node 成本的 pre-node 函数，以及包含当前 node 成本的 backward exact suffix 函数。

需要收紧的第一点是 `U_state` 和 `F_state` 的更新语义。`F_state` 是继续扩展用的主传播函数，因此 state 是否重新入队主要看 `F_state` 是否被改善。但如果 node join 需要使用 `U_state`，则 `U_state` 自己也必须维护为所有 pre-node candidate 的 lower envelope。可能出现 `U_state` 改善了，但 `U_state + g_i` 经过 prefix-min 后没有改善 `F_state` 的情况；这时不需要为了继续扩展而重新入队，但应保留更新后的 `U_state`，否则后续 `U_state + Bbar_state` 的 node join bound 会偏弱。

因此更准确的更新流程是：

```text
U_candidate = shift(F_parent, Delta) + cbar
U_changed = lowerEnvelope(U_state, U_candidate)

F_candidate = prefixMin(U_state + g_i)
F_changed = lowerEnvelope(F_state, F_candidate)

if F_changed:
    enqueue(state)
```

实际实现时可以避免每次全量重算 `prefixMin(U_state + g_i)`，但语义上要保证 `U_state` 和 `F_state` 分别代表各自的 envelope。对于 backward 方向也是同理：`Bbar_state` 用于 node join，`B_state` 用于继续向前传播；是否入队看传播函数是否改善，但 exact suffix envelope 也要保持最新。

第二点是 node join 在正式 elementary pricing 中仍然需要集合兼容性。本文讨论的 `U_i(t) + Bbar_i(t)` 只解决函数双计数问题；正式生成列时，还要保证 forward visited set 与 backward visited set 除了 join node 外没有交集，并且分支禁弧、required arc 转化出的直连禁弧等约束仍被检查。也就是说，node join 不是把正式 pricing 降成 relaxed DP，它只是替换 final join 的拼接点。

第三点是“node join 减少平方级 arc join”的说法应理解为实验假设，而不是必然结论。按 node 分组后，理论上可以避免枚举所有 crossing arc `(i,j)`，但真实复杂度还取决于每个 node 上存活 forward/backward label 数量、visited set 兼容性检查成本、函数求交和最小值计算成本。它有希望减少 join 候选组，但需要用同一批样例实测确认。

第四点是 queue-correcting 的最优性条件不能省略。只要 horizon 有界、每次扩展有正时间或正离散资源消耗、没有零资源负 reduced-cost 环，并且 lower-envelope 操作精确，队列清空时才可解释为 relaxed DP 的最优 fixed point。若后续引入零处理时间、零 setup 或特殊 big-M/退化时间段，就要重新检查是否可能出现无限改善或数值震荡。

第五点是 full-domain 比 half-domain 不慢很多只是当前实验观察，不是理论保证。它足以支持“可以在 full-domain 上尝试 node join”，但不能直接推出 node join 一定更快。后续实现时应保留 arc join/full-domain/node join 的对照开关，并至少比较标签数、join 候选数、函数求值次数和最终 exact pricing 时间。

## 9. 关于 arc join 原因与 `U_state` 更新的进一步说明

前面关于 arc join 原因的主次需要明确：**第一主因是 node join 在旧 label 函数定义下不好处理同一 node 的惩罚成本和 dual 扣重；第二主因是 half-domain/Tmid 边界。** 其他工程原因只是这两个主因的延伸。

第一，arc join 天然避免 join node 双计数。Forward 侧以 `i` 结束，backward 侧以 `j` 开始，中间显式支付 crossing arc `i -> j` 以及相应的 processing/setup 位移，`j` 的节点成本由 backward 侧负责。两侧没有共同的真实 job，因此不用额外保存 pre-node/exact-suffix 函数去处理“同一个 node 的成本算一次还是两次”。这是之前选择 arc join 的核心原因。

第二，arc join 更适配 half-domain/Tmid。它只要求 forward frontier 平移 `setup(i,j)+p_j` 后与 backward frontier 有时间交集，中间 crossing arc 可以跨过 `Tmid`；node join 则要求两侧在同一个 node 的同一个完成时间 `t` 上拼接，半域边界不好定。这是第二个主要原因。

第三，arc join 不要求两侧在同一个完成时间上同步。它只要求 forward frontier 平移后与 backward frontier 有时间交集，然后在交集上求最小 reduced cost。Node join 则要求同 node 同时间拼接；一旦 forward/backward 函数都做过 prefix/suffix normalize，就必须额外保存未被 normalize 抹掉的拼接函数，否则时间语义不够。这个问题本质上还是来自第一点的函数定义。

第四，arc join 与当前 label 状态更匹配。现有 label 只保存一个用于扩展和 dominance 的 frontier，配合 visited/reachable set 就能做 crossing arc 拼接。Node join 则需要至少多保存 `U_state` 或 `Bbar_state`，并要保证这些函数和传播 frontier 的 envelope 关系一致。这会增加状态字段、缓存失效规则和 dominance 后 active label 清理的复杂度。

第五，arc join 的集合兼容性更直接。Crossing arc join 检查的是 forward visited set 与 backward visited set 是否相交，以及 `i -> j` 是否 forbidden。Node join 允许两侧共享同一个 join node，但除 join node 外不能相交；实现上要把“允许的交集”从空集改成单点集合，并且还要保证该 node 的成本只算一次。这不是不能做，但比 arc join 更容易写错。

因此更准确的结论是：arc join 不是唯一可行方案，而是旧标签函数定义下最自然地规避 node 成本扣重问题、同时又适配 half-domain/Tmid 的方案。Node join 要成立，首先要补 `U_state/Bbar_state` 这类不重复计数的拼接函数；其次最好放在 full-domain 基础上，避免再和 `Tmid` 半域边界纠缠。

关于 `U_state` 改善但 `F_state` 不改善，可以用下面这个语义理解。`U_state` 是“进入当前 node 前”的函数，用于 node join；`F_state` 是“已经加上当前 node 成本并 prefix-min 后”的函数，用于继续扩展。继续扩展只关心 `F_state`，因为后续 job 只需要知道当前 node 不晚于某时间完成的最小 prefix 成本。但 node join 关心的是同一时间 `t` 上的 `U_state(t) + Bbar_state(t)`，因此 `U_state` 在某些时间点的改善即使没有传导成 `F_state` 改善，也可能让 node join 更好。

一个典型情形是：某个新候选让 `U_state(t0)` 变小，但 `g_i(t0)` 较大，或者 `F_state(t0)` 已经被更早时间 `u < t0` 的 prefix-min 值覆盖，所以 `F_state` 没有变化。这时没有必要把该 state 重新入队，因为继续扩展看不到新收益；但是如果 backward 的 `Bbar_state(t0)` 在 `t0` 很好，那么 `U_state(t0) + Bbar_state(t0)` 可能改进 node join。若不保存这次 `U_state` 改善，node join bound 就会偏弱。

所以实现上应区分两类更新：

```text
U_state 改善：更新 U_state，供 node join 使用；不一定入队。
F_state 改善：更新 F_state，并重新入队继续传播。
```

Backward 方向同理：

```text
Bbar_state 改善：更新 Bbar_state，供 node join 使用；不一定入队。
B_state 改善：更新 B_state，并重新入队继续向前传播。
```

这不是说 `U_state` 比 `F_state` 更重要，而是二者服务的目标不同。`F_state/B_state` 决定 relaxed DP 的传播闭包；`U_state/Bbar_state` 决定同 node 拼接时能否正确、尽量强地评价 bound。

换句话说，原始判断“记录来源的不超过函数平移后的那个，再记录它加当前 node 惩罚后 minimize 的函数”是正确方向。这里需要避免的错误不是这个定义本身，而是实现时把二者绑得太死：如果只在 `F_state` 改善时才更新 `U_state`，那么某些只改善 node join、但不改善后续扩展传播的候选会被丢掉。正确口径是 `U_state` 和 `F_state` 都维护各自的 lower envelope；重新入队只看传播函数 `F_state/B_state` 是否改善。

因此 forward 的一次 label-correcting 更新流程应理解为：当前出队状态用自己的 `F_state` 向后扩展，先得到到达后继 node 的 `U_candidate`，再由 `U_candidate + g_j` 做 prefix-min 得到 `F_candidate`。更新目标状态时，`U_state` 用 `U_candidate` 取 lower envelope，`F_state` 用 `F_candidate` 取 lower envelope。若只有 `U_state` 改善而 `F_state` 没改善，则不需要重新入队，因为继续扩展依赖的是 `F_state`；但这次 `U_state` 改善必须保留，供后续 node join 使用。若 `F_state` 改善，则目标状态需要重新入队。Backward 侧完全对称：`Bbar_state` 改善只更新拼接函数，`B_state` 改善才重新入队。

## 10. 2026-05-31 completion bound 第一版实现记录

本次把 completion bound 先落在 full-domain node-join 实验分支中，并保留默认关闭。新增配置 `bidirectionalCompletionBoundRelaxation`，取值为 `off/allCycles/twoCycle`。`allCycles` 使用按 node 聚合的 relaxed DP，正向维护不含当前 node 成本的 `U_i` 和已含当前 node 成本、可继续传播的 `F_i`，反向维护不含当前 node 成本的 `R_i` 和已含当前 node 成本、可继续传播的 `B_i`。`twoCycle` 使用二维 last-arc state：正向为 `(h,i)`，反向为 `(i,k)`，只在 DP 转移中禁止立即回跳；二维函数收敛后再对每个 job 聚合成一维 lower envelope，因此正式 label 使用补全函数时不再额外检查上一跳或下一跳兼容性。这和 2-cycle forbidden arc 没有关系。

剪枝位置放在正反向 child label 生成以后、进入 terminal/dominance table 以前。Forward label 已经包含当前 job 的 penalty/job dual，所以补全侧使用 `R_i`，下界为 `F_label + R_i` 的函数最小值；Backward label 已经包含当前 job 的后缀成本，所以补全侧使用 `U_i`，下界为 `U_i + B_label` 的函数最小值。这个口径不同于 final node join 的 `forward.preNodeFrontier + backward.frontier`，因为 completion bound 是用来判断“当前 label 是否还可能补成负列”的松弛下界，不负责生成真实列。

阈值也按扩展剪枝语义处理，而不是 final join 的 K 堆阈值。completion bound 只回答“当前 label 是否还能补成负 reduced-cost 列”，因此固定使用 0 附近的 reduced-cost cutoff。原先命名为 `bestLB` 的 join 阈值模式在最小化 reduced-cost 语义下应改为 `bestUB`，因此代码中只保留 `BEST_UB/bestUB` 口径，不再解析旧 `bestLB` 字符串，避免后续实验继续沿用错误命名。但 `bestUB/bestRecord` 只影响 final join 的 group/pair/function 过滤，不参与 completion-bound 扩展剪枝；否则会变成 record-only 语义，剪掉仍为负但不刷新当前最优记录的列，影响 top-K 加列和 exact pricing 口径。

实现时还同步修正了比较实验入口：`GCBBFullDomainComparisonTest` 透传 `twet.bpc.fullDomainCompare.completionBound`，非 `off` 时在 mode 名追加 completion-bound 后缀，方便对照 `allCycles/twoCycle`。验证使用 focused `javac` 覆盖新增修改类通过，`git diff --check` 仅提示既有 CRLF 风格；没有运行完整 BPC 样例，因为当前工作区未找到 CPLEX jar。

2026-05-31 复查后补充验证：使用本机 CPLEX jar 对 `wet021_001_2m` 做 root-only node-join 对照，`off/allCycles/twoCycle` 以及 `zero/bestUB` 组合均得到 `obj=6829, bound=6829, valid=true`。开启 completion bound 后第一轮保留 label 从 `fw/bw=10847/23574` 降到 `19/24`，`fwPruned/bwPruned=231/332`，说明确实剪掉了大量原本需要扩展的 label。当前流程的负候选只在两侧队列耗尽后的 join 阶段产生，因此 completion-bound 扩展剪枝阶段 `zero` 和 `bestUB` 实际使用同一个 0 cutoff；`bestUB` 只会继续影响后续 final join 的 group/pair lower-bound 剪枝。为避免日志误导，统计中的 completion-bound cutoff 改为记录扩展评估时实际使用的 cutoff，而不是 join 完成后的当前 best reduced cost。

2026-05-31 继续分析 all-cycles 与 2-cycle-free 的时间差异。`twoCycle` 的 bound 理论上不弱于 `allCycles`，因为它在 relaxed DP 中额外保留 last-arc state，排除了立即回跳的二环；但这个强度需要用更高的状态维度、更多函数数组、更频繁的 lower-envelope 合并和更长的 queue-correcting 收敛过程换取。当前 `wet020/wet021` 这类 root-only 样例里，`allCycles` 已经足以把主要 label 扩展子树剪掉，`twoCycle` 额外排除二环没有带来明显更多的 `fwPruned/bwPruned`，因此总时间反而更慢。由此当前实用判断是：默认优先尝试 `allCycles`，只有在日志显示 all-cycles 剪枝不足、且 two-cycle 额外剪枝明显超过构建成本时，再考虑切到 `twoCycle`。

关于 `bestUB` 参与 completion-bound 剪枝，需要区分“找更好记录”和“保留负列集合”两个目标。如果本轮已经有一个负 reduced-cost incumbent `bestGeneratedReducedCost`，那么对某个 label 计算 relaxed completion lower bound 后，若 `lowerBound >= bestGeneratedReducedCost`，该 label 后续确实不可能生成比当前 best 更好的列；但它仍可能生成负列，仍可能进入 top-K 加列集合。因此在当前 exact pricing / top-K 负列语义下，completion bound 不使用当前最优 reduced cost，而固定使用 0 cutoff。`bestUB` 可以继续作为 final join 的性能实验开关，用于减少不可能刷新当前 best 的 group/pair 扫描；但不放进扩展阶段的 completion-bound 判断。

2026-05-31 决定收紧实现口径：completion-bound cutoff 固定为 `REDUCED_COST_TOLERANCE`，不再读取 `joinBestThresholdMode` 或 `bestGeneratedReducedCost`。这样日志中的 completion-bound cutoff 始终表示“负列可补全”判断，`bestUB/bestRecord` 的影响范围限定在 final join 阶段。这个选择牺牲了一部分 record-only 剪枝潜力，但保留了每轮返回多条负列和 exact pricing certificate 的语义。

关于 `allCycles` 与 `twoCycle` 的规模效应，当前小样例上 `allCycles` 更快不应被理解为 `twoCycle` 没有价值。`twoCycle` 的额外成本主要来自二维 last-arc state、更多函数合并和更长的 correcting 收敛；收益则来自更强 lower bound 对后续 label 子树的提前剪枝。在 `wet020/wet021` 这类 pricing 已被 `allCycles` 压到很短的样例里，额外剪枝空间很小，所以 `twoCycle` 的构建成本显得不划算。若规模变大、root dual 结构导致 all-cycles bound 仍然留下大量可扩展 label，`twoCycle` 可能通过更强 bound 大幅减少后续扩展、dominance 和 join 时间，此时总时间可能反超。后续实验应按 `completionBoundBuildMs`、`fwPruned/bwPruned`、`fwExt/bwExt/join` 分项判断，而不是只看总秒数。

## 11. 2026-05-31 arc-join full-domain completion bound 试跑

本次把同一套 completion bound 先移植到 `GCBBStyleBidirectionalFullDomain`，也就是 full-domain crossing-arc join 对照类。接入口径和 node-join 分支保持一致：`off/allCycles/twoCycle` 由 `bidirectionalCompletionBoundRelaxation` 控制，正向 child label 用 `F_label + R_i` 判断是否还能补成负列，反向 child label 用 `U_i + B_label` 判断；cutoff 固定为 `REDUCED_COST_TOLERANCE`，不读取当前 best reduced cost。比较入口 `GCBBFullDomainComparisonTest` 也同步让 full-domain arc-join 模式在非 `off` 时写出 `-cb-allCycles/-cb-twoCycle` 后缀，避免和无剪枝结果混在同一个目录。

在 no-outsourcing 的 `wet020_001_2m` root-only 对照中，三组结果均 `valid=true` 且根节点 `obj=bound=6343`。无剪枝时 exact pricing 为 `0.296s`，保留 label 为 `fw/bw=1153/1395`，join function evaluation 为 `274192`。`allCycles` 后 exact pricing 降到 `0.115s`，保留 label 为 `26/13`，`fwPruned/bwPruned=295/187`，bound 构建约 `62.193ms`。`twoCycle` 的 label 更少，为 `8/11`，join function evaluation 只有 `7`，说明 bound 确实更强；但构建约 `247.479ms`，最终 exact pricing 为 `0.299s`，基本抵消了后续节省。

在 `wet021_001_2m` root-only 对照中，三组同样 `valid=true` 且 `obj=bound=6829`。无剪枝两轮 exact pricing 合计约 `0.745s`；`allCycles` 合计约 `0.202s`，第一轮 label 从 `2103/1424` 降到 `16/16`，`fwPruned/bwPruned=163/227`，构建约 `80.235ms`；第二轮构建约 `56.961ms`，几乎直接剪到 source 附近。`twoCycle` 在该算例上剪枝数和 `allCycles` 完全相同，但构建约 `338.619ms + 303.779ms`，总 exact pricing 约 `0.714s`，接近无剪枝。

当前判断是：在 full-domain arc join 上，completion bound 的收益比 node-join 更容易体现，因为它直接减少后续扩展、dominance 和 crossing-arc join 的规模；但默认更应先用 `allCycles`。`twoCycle` 在 `wet020` 上确实更强，可把 join evaluation 从 `60` 进一步压到 `7`，但当前样例的额外构建成本更大；在 `wet021` 上甚至没有额外剪枝。因此后续如果继续找 `twoCycle` 的适用场景，应优先找 all-cycles 仍留下大量 label 或 join evaluation 的实例，而不是已经被 all-cycles 压到毫秒级的 root pricing。尝试直接跑原始 `data/40-2/wet040_001_2m` 时，off 版本超过三分钟未完成，bound 版本也不适合本轮交互验证；该数据口径和当前 no-outsourcing 对照集不同，暂不纳入结论。
## 12. 2026-05-31 arc-join completion bound 在 wet025/wet030 上的 10 分钟试跑

本轮继续用 full-domain crossing-arc join 对照类试 `wet025_001` 和 `wet030_001`，仍采用 root-only、`maxExactColumns=5`、no-outsourcing 临时输入，并分别跑 `allCycles/twoCycle/off`。目标不是完整批量结论，而是看规模从 20/21 放大到 25/30 后，completion bound 的构建成本和扩展剪枝收益是否开始分化。

`wet025_001` 三组都跑完且结果一致，均为 `obj=bound=10037, valid=true`。无 completion bound 时 solve 为 `20.228s`，exact pricing 为 `18.105s`；`allCycles` 后 solve 降到 `2.554s`、exact pricing 降到 `0.353s`，第一轮日志中 completion-bound 构建约 `202.995ms`，剪掉 `fw/bw=4112/1224` 个 label。`twoCycle` 也明显快于 off，solve 为 `3.453s`、exact pricing 为 `1.401s`，但构建约 `1247.146ms`，比 all-cycles 多出的剪枝不足以抵消构建成本。

`wet030_001` 在 10 分钟预算内完成了 `allCycles` 和 `twoCycle`，`off` 启动后一直未完成，剩余预算内手动停止，因此本轮不把 off 纳入完成结果。`allCycles` 得到 `NODE_LIMIT`，`obj=46152, bound=15261.833333, valid=true`，solve 为 `9.037s`，exact pricing 合计 `3.086s`，5 次 pricing 中每次 completion-bound 构建约 `0.44-0.55s`。`twoCycle` 同样为 `NODE_LIMIT` 且目标和界一致，但 solve 增至 `38.467s`，exact pricing 合计 `31.194s`，每次构建约 `5.43-6.90s`。从日志看 two-cycle 的 label 和 join 候选确实更少，例如最后一轮保留 label 从 all-cycles 的 `fw/bw=429/125` 降到 `157/96`，join function evaluation 从 `6863` 降到 `2032`；但二维 bound 的构建成本已经远大于后续节省。

当前结论进一步支持把 `allCycles` 作为 arc-join full-domain completion bound 的默认优先项。`twoCycle` 的 bound 强度没有问题，但在 25/30 规模上仍然不是瓶颈换取成功的方向；它只有在 all-cycles 构建后仍留下非常大的扩展、dominance 或 join 残余时才值得继续试。`wet030 off` 未在本轮 10 分钟预算内完成，反过来说明 completion bound 已经从“局部优化”变成了 full-domain arc join 在较大样例上能否交互式跑完的关键开关。

## 13. 2026-05-31 wet030 root 列池整数诊断

针对 `wet030_001` root-only 输出中 `incumbent=46152`、`bound=15261.833333` 的大 gap，新增了一个窄用途诊断入口 `HEU.RootColumnIntegerDiagnostic`。该入口不改主 BPC 流程：每个 seed 先按 full-domain arc join + `allCycles` completion bound 跑 root-only pricing，然后把当前 root 列池固定下来，解一个二进制 restricted master。该整数模型只使用已有列，约束为每个 job 至少覆盖一次、列数不超过机器数；在 no-outsourcing 输入下可用于判断根节点生成的列本身是否已经能拼出较好整数解。

5 个 seed 的结果显示，初始 incumbent 对随机 seed 很敏感，范围为 `42257-61629`；但同一批 root 列池整数化后，目标稳定在 `15325-15835`，显著接近根 bound。最佳 seed `202605310304` 的 root 列池整数解为 `15325`，选中 2 条列，缺失任务数为 0、重复覆盖数为 0，因此不是 set-covering 重复覆盖造成的假解。由此可以判断，`wet030` 上之前看到的大 gap 主要来自当前 BPC 在 root LP 分数时不额外求一次 restricted master integer 来刷新 incumbent；根节点列池本身已经包含接近下界的整数列组合。

这提示后续可以考虑增加一个可选的 root/节点后处理：当 column generation 结束且 LP 仍为分数解时，用当前列池求一次受限整数主问题作为 incumbent polishing。该步骤不改变下界，也不改变 pricing 正确性，只用于把已有列组合转成更好的上界；是否默认开启需要再看额外 MIP 时间和较大列池上的稳定性。

进一步复查初始 incumbent 来源后确认，`GCBBFullDomainComparisonTest` 这类定价对照入口显式设置 `reuseConfiguredBestSolution=false` 和 `runALNSForSeed=false`。因此初始解只来自 `Solution.setInitSolution()`：先随机打乱 job 顺序，再对每个 job 随机选择一个当前不违反粗硬窗的机器插入。它只保证构造出可行机器序列，不做局部搜索、VND 或 ALNS 改进，也不按惩罚成本选择最优插入位置。`wet030` 中多个 seed 的 incumbent 差异很大正是这个随机构造造成的。后续 root pricing 生成了大量好列，但当前 BPC 只在 master LP 解本身为整数时才刷新 incumbent；如果 root LP 是分数解，这些好列不会自动组合成新的整数上界，除非额外求一次 restricted master integer。

开启 `runALNSForSeed=true` 后，同样 5 个 seed 的 incumbent 直接降到 `15325/15330/15325/15679/15571`，根列池整数化结果为 `15325/15330/15325/15403/15571`，最佳 gap 约 `0.41%`。其中多数 seed 的 root integer 直接选中初始两条 ALNS 列 `[0, 1]`，说明 ALNS 已经能构造接近根下界的 2 机整数解；个别 seed 如 `202605310303`，root pricing 后的列池整数化还能把 ALNS incumbent 从 `15679` 改到 `15403`。因此 `wet030` 的大 gap 不是数据本身或 pricing bound 异常，而是前面对照实验为了隔离 pricing 成本关闭了 ALNS，导致初始上界只代表随机可行构造质量。

为避免后续误解，`RootColumnIntegerDiagnostic` 的 CSV 增加了 `initial_cols/incumbent_cols/initial_incumbent` 三列。用 `runALNSForSeed=true` 复核 seed `202605310300` 时，`initial_cols=58`、`incumbent_cols=2`、`initial_incumbent=15325`。这说明 ALNS 过程中刷新过的历史 best 解确实已经通过 `useBestSolutionHistoryForInitialColumns` 加入 root 初始列池；但 incumbent 仍只由最终 seed 解对应的 2 条机器列表示。

## 14. 2026-06-01 two-cycle completion bound 内部计时与队列顺序

本次先只给 full-domain arc-join 的 completion bound 构造过程加内部计时和计数，不改变剪枝逻辑。新增统计把 bound 构造拆成 forward correcting、backward correcting 和 two-cycle 最后的二维到一维聚合，同时记录候选转移尝试次数、队列出队次数、`mergeFunction` 调用次数和实际改变次数。focused `javac` 已通过，随后用 `wet030_001` root-only、`maxExactColumns=5` 对 `allCycles/twoCycle` 各跑一遍。

`twoCycle` 的慢因基本确认不在最终聚合。5 次 pricing 中，构建总时间约 `6.12-6.62s`，其中 forward correcting 约 `1.29-1.70s`，backward correcting 约 `4.38-5.22s`，最后聚合只有 `44-66ms`。计数上，每轮 forward candidate 约 `15-16.7万`，backward candidate 约 `17.4-19.9万`，出队约 `5350-5965 / 6216-7046`，`mergeFunction` 调用约 `62-70万`，实际改变约 `18.3-20.0万`。相比之下，`allCycles` 每轮构建约 `0.59-0.74s`，forward 约 `0.11-0.20s`，backward 约 `0.46-0.57s`，候选尝试只有约 `0.6-0.8万` 每侧，merge 调用约 `2.7-3.0万`。因此 two-cycle 更慢主要来自二维 last-arc state 的 queue-correcting 状态数和函数 lower-envelope 合并次数，尤其 backward 侧；不是 final join，也不是最终 evaluator 复核。

当前状态队列没有按 reduced cost、时间、函数下界或状态编号排序，而是 `ArrayDeque` 的 FIFO。`allCycles` 中 forward 初始按 `job=1..n` 入队，出队一个 node 后按 `job=1..n` 扫描后继；若目标 `F_j` lower envelope 改善且该 job 不在队列中，就追加到队尾。Backward 对称，初始按 `job=1..n` 生成 `job -> sink`，出队一个 successor 后按 `prev=1..n` 扫描前驱，改善则追加队尾。`twoCycle` 也是同样的 FIFO，只是状态从 node 变成 forward `(prevPrev, prev)` 和 backward `(current, successor)`；出队一个二维状态后按自然 job 顺序扫描下一跳或前一跳，改善目标二维状态时追加队尾。也就是说，这里是 Bellman-Ford-style queue-correcting 的发现/改善顺序，不是 Dijkstra-like priority queue。

这个顺序本身不影响 fixed point 语义，但会影响收敛速度。two-cycle 的状态维度放大后，FIFO 会让同一状态在不同函数 envelope 改善后多次重新传播；再叠加当前 `mergeFunction` 为判断是否 changed 会复制旧函数并做 segment 比较，构建时间就和精确 pricing 本体同量级甚至更高。下一步若优化 two-cycle，优先方向应是减少 envelope merge 成本、给状态传播加更强的时间/成本可行性预筛，或尝试更合适的工作队列顺序；但在当前数据上，直接默认使用 `allCycles` 更稳。

## 15. 2026-06-01 completion bound 队列重复计算语义

复查代码后确认，当前 correcting 队列已经有 `inForwardQueue/inBackwardQueue` 去重，因此不会出现同一个状态在队列里同时存在 10 份的情况。无论是 all-cycles 的一维 node state，还是 two-cycle 的二维 last-arc state，只要目标传播函数 `F/B` 改善且状态已在队列中，代码只更新数组里的最新函数 envelope，不会再追加一个重复队列项。等该状态真正出队时，读取的是数组中的最新函数，而不是入队那一刻的旧快照。因此“队列中已有状态被其他状态连续改善 10 次，只需要最后一次最新状态传播”的优化，在当前实现里已经基本成立。

当前仍然存在的重复计算是另一类：一个状态出队并按当时的 `F/B` 传播完以后，后续又被其他状态进一步改善，于是会重新入队并再次扫描所有后继或前驱。这个是 label-correcting fixed point 的正常代价，不是队列重复项造成的 stale work。若不重新传播，就会漏掉后续改善对下游状态的影响。队列顺序只影响一个状态是在更多改善到达前还是之后出队，从而影响是否需要多轮传播；但因为这里是 lower-envelope merge，不是正式 label dominance，`FIFO`、按最早完成时间或按简单下界排序都不改变最终结果，可能只改变收敛轮数。

因此，two-cycle 目前慢的主要矛盾不是“队列里同一状态堆了很多份”，而是二维状态数和每次出队的全量扫描太大：每次出队都枚举一整轮 `job=1..n` 或 `prev=1..n`，每条候选又要构造分段函数、merge envelope、复制旧函数并比较是否 changed。更可能有效的优化方向是减少每次出队扫描的候选弧、降低 `mergeFunction` 判断 changed 的成本、维护增量改善片段避免整函数重传，或设计能让状态尽量在主要改善到齐后再传播的队列策略。单纯把 FIFO 换成最早完成时间队列，预计收益不会像减少 merge 次数那样稳定。

## 16. 2026-06-01 reduced-cost 优先 completion-bound 队列试验

按“右端 reduced cost 最小的状态先出队”的想法，给 full-domain arc-join 的 completion-bound correcting 队列增加了可选顺序 `bidirectionalCompletionBoundQueueOrdering`。默认仍为 `fifo`；比较入口可用 `twet.bpc.fullDomainCompare.completionBoundQueue=reducedCost` 切换，`wet020_001` 小样例烟测日志已确认输出 `completionBoundQueue=REDUCED_COST`。实现上没有让同一状态在队列中重复存在：若状态已经在队列里又被改善，`reducedCost` 模式会先从 `PriorityQueue` 删除旧项，再按当前传播函数右端值重新插入；`fifo` 模式保持原逻辑，队列中已有状态只更新数组里的函数，不调整队列位置。

`wet030_001` root-only、`allCycles` 的试验结果不支持这个方向。`reducedCost` 模式下整体 solve 为 `74.260s`，exact pricing 为 `68.318s`，而同一代码默认 `fifo` 复核为 `7.675s`、exact pricing `2.323s`。从内部统计看，`reducedCost` 不只是多了优先队列维护成本，它还明显改变了 correcting 收敛路径：第一轮 merge 调用从 FIFO 的 `29710` 增到 `603704`，后续几轮也在 `72-80万` 左右；candidate 尝试从约 `0.6-0.8万` 每侧扩大到十几万每侧。也就是说，用右端值优先出队反而让更多中间 envelope 被提前传播，导致后续又被反复改进和重传。

当前判断是：completion bound 的状态传播不是 label dominance 那类“先扩展低 reduced-cost label 更可能早剪掉别人”的过程，而是函数 lower-envelope fixed point。一个状态右端值小，不代表它的整段 envelope 已经接近稳定，也不代表先传播它能减少后续 merge；相反，过早传播局部较优但尚未稳定的函数，可能把大量中间结果推到下游，随后再被更好的 envelope 覆盖。因此 reduced-cost 出队顺序在当前口径下比 FIFO 差很多。后续若继续改队列，优先应找能减少重传的稳定性指标，而不是单点右端值；更直接的优化仍是减少候选扫描和 merge 成本。

## 17. 2026-06-01 completion bound 两份实现的同步状态

当前 completion bound 不是一个独立共享类，而是分别内嵌在 `GCBBStyleBidirectionalFullDomain` 和 `GCBBStyleBidirectionalFullDomainNodeJoin` 的 `CompletionBoundBuilder` 中。两者最初使用同一套 relaxed DP 语义：`allCycles` 是 node state，`twoCycle` 是 last-arc state，正向维护 `U/F`，反向维护 `R/B`，并在 label child 入表前用 `F_label + R_i` 或 `U_i + B_label` 做是否还能补成负列的剪枝判断。

但工程实现现在已经分叉。最近的内部计时、候选/merge 计数、`completionBoundQueue` 日志字段，以及 `fifo/reducedCost` 队列顺序实验，只加在 full-domain arc-join 对照类 `GCBBStyleBidirectionalFullDomain` 里；node-join 实验类仍保留较早的 FIFO `ArrayDeque` 版本，没有这些内部统计和队列顺序开关。也就是说，若讨论“当前试出来 reduced-cost 队列很慢”“内部 merge 次数是多少”，这些结论直接对应 arc-join 这份实现；node-join 的 completion bound 数学口径相同，但代码没有同步这些诊断改动。后续如果还要继续推进 completion bound，较合理的方向是把这套 relaxed DP 抽成共享 builder，避免两个实验分支长期手工复制后发生口径漂移。

## 18. 2026-06-01 抽取共享 CompletionBoundCalculator

本次按上述判断把 completion bound 的 relaxed DP 从两个 pricing 类中抽出为 `TWETBPC.GC.CompletionBoundCalculator`。该类只负责接收当前 pricing 轮已经预处理好的 forward/backward job penalty、zero-dual 排除集合、pricing horizon 和 LP dual，返回按 job 聚合的 `forwardUByJob/backwardRByJob`，同时返回构建阶段的内部计时和计数。`GCBBStyleBidirectionalFullDomain` 和 `GCBBStyleBidirectionalFullDomainNodeJoin` 现在只保留配置解析、输入准备、剪枝调用和日志汇总，不再各自维护一份 `CompletionBoundBuilder`。

队列顺序也统一放在共享计算器里，配置仍是 `bidirectionalCompletionBoundQueueOrdering`，可选 `fifo/reducedCost`。默认保留 `fifo`，并在代码注释里说明当前 wet030 实测 FIFO 更稳；`reducedCost` 只是对照开关，不作为默认优化方向。共享计算器的 `reducedCost` 模式仍保持“同一状态在队列中最多一份”的语义：状态已在队列中又被改善时，优先队列会删除旧项并用当前函数右端值重新插入。

验证方面，focused `javac` 覆盖共享计算器、arc full-domain、node-join、比较入口和配置类通过。随后用 `tmp-wet020_001_2m` 做 root-only smoke：arc full-domain + `allCycles/fifo` 得到 `obj=bound=6343, valid=true`，node-join + `allCycles/fifo` 同样 `obj=bound=6343, valid=true`；再用 arc full-domain + `allCycles/reducedCost` 验证队列配置入口能走通，结果仍为 `obj=bound=6343, valid=true`。这些验证只证明抽取后两条路径功能正常，wet030 上关于 reduced-cost 队列显著变慢的结论仍沿用前一节的大样例结果。

当前需要注意的是，completion bound 仍只接入了两个 full-domain GCBB 实验分支：`GCBBStyleBidirectionalFullDomain` 和 `GCBBStyleBidirectionalFullDomainNodeJoin`。如果按后续应维护的 active pricing 口径看，正式剩余的是单向 pricing、Tmid 双向 `GCNGBBStyleBidirectional`、非对称双向 `GCBBAsymmetricBidirectional`；最早的 hybrid-B `GCBidirectional` 已经在 `solve()` 入口直接返回空列并标记为 disabled，不再作为维护对象。上述三个正式 active 口径目前都还没有接入 completion bound。因此目前这个加强是“full-domain arc/node 实验分支可用”，不是所有 active pricing 的全局默认加强。启动开关是 `TWETBPCConfig.bidirectionalCompletionBoundRelaxation`，默认 `off`；设置为 `allCycles` 或 `twoCycle` 时才会构建并应用 bound，队列顺序由 `bidirectionalCompletionBoundQueueOrdering` 控制，默认 `fifo`。

## 19. 2026-06-01 Tmid 双向 completion bound 接入范围收口

本次把共享 `CompletionBoundCalculator` 接入到正式 Tmid 双向 `GCNGBBStyleBidirectional`。接入口和 full-domain 分支保持一致：读取 `bidirectionalCompletionBoundRelaxation=off/allCycles/twoCycle`，默认关闭；开启后在正反向 child label 生成后、进入 dominance table 前分别用 `F_label + R_i` 和 `U_i + B_label` 判断是否还能补成负 reduced-cost 列。统计中新增 build time、内部 forward/backward 构建计时、candidate/merge/queue 计数以及正反向剪枝数，便于后续和 full-domain 分支对齐观察。

Tmid pricing 的正式 label 仍然使用左右半域 penalty；但 completion bound 的目标是判断半域 label 是否还能补成完整列，因此这里没有把半域 penalty 直接传给 calculator，而是按本轮 effective window 单独构造完整 `[0, pricingHorizon]` 的 completion-bound penalty。否则 forward 左半域 label 与 backward 补全函数会被人为限制在半域交集上，既可能削弱剪枝，也容易让日志语义和 full-domain 分支不一致。

按当前维护判断，单向 forward pricing 和非对称双向分支后续预计不再作为性能主路径使用，因此本次不接入 completion bound，只在 `GC` 与 `GCBBAsymmetricBidirectional` 类注释中明确该范围选择，避免后续误以为遗漏。源码中未找到 `GCNGBBAsymmetricBidirectional.java`，只存在历史 `.class` 编译产物；因此可维护注释只落在现有 Java 源码上。

验证方面，focused `javac` 覆盖 `CompletionBoundCalculator`、Tmid 双向、非对称、单向入口和配置类通过。随后用 `tmp-wet020_001_2m` 做 root-only normal/Tmid 对照：`completionBound=off` 和 `completionBound=allCycles` 均得到 `obj=6343, bound=6343, valid=true`。开启 `allCycles` 后该轮 exact pricing 从约 `0.472s` 降到 `0.257s`，日志显示 `fwPruned/bwPruned=752/318`，说明 Tmid 路径已经实际使用 completion bound，且该小样例结果口径一致。

## 20. 2026-06-01 半域 penalty、pi-window 与最终列成本复核口径

Tmid 双向和 full-domain 双向在 penalty 函数定义域上的差异需要分清。Tmid `GCNGBBStyleBidirectional` 的正式标签使用半域缓存：forward job penalty 裁到 `[0, Tmid]`，backward job penalty 裁到 `[Tmid, pricingHorizon]`。如果根节点 no-cut 下启用了 pi-window，则先把 job penalty 的有效窗收紧到动态 `[hStart, hEnd]`，再分别裁到左右半域。因此半域缓存不是只记录时间可行性，而是实际进入 label 递推的分段惩罚函数定义域。

completion bound 不能直接复用这两个半域缓存。原因是它要回答“当前半域 label 是否还能补成一条完整负列”。例如 forward label 自身定义在左半域，如果把 backward completion 的 job penalty 也只定义在右半域，那么 `F_label + R_i` 做函数相加时公共定义域很可能只剩 `Tmid` 附近，甚至为空；这样算出来的下界不是完整补全下界，而是被人为限制成“必须在半域交界点附近拼上”的下界。因此 Tmid 路径现在单独构造 full-domain completion-bound penalty：仍尊重本轮 effective window / pi-window，但外层裁剪为 `[0, pricingHorizon]`。

full-domain arc/node 分支本身没有左右半域标签，正式 label 和 completion bound 输入都使用 `[0, pricingHorizon]` 上的 penalty。这里的“full-domain”不表示忽略 job 的有效时间窗；若 pi-window 生效，代码仍会先对 `data.penaltyFunction[job]` 做 `setDomain(hStart, hEnd, true)`，再裁到 `[0, pricingHorizon]`。所以 full-domain 是相对于 Tmid half-domain 的标签定义域，不是相对于 hard window 或 pi-window 的无限放宽。

最终列成本复核也要区分路径。当前带 top-K 候选堆的双向 pricing，包括 Tmid、full-domain arc、full-domain node 和非对称分支，在 `dualProfitableWindowEnabled=true` 时会在 `finalizeGeneratedColumns()` 对最终 K 条候选调用 `PricingColumnCostRechecker.evaluate(...)`，用完整 evaluator 成本写回列。该条件只在根节点且没有 active cuts 时成立，也正是 pi-window 会启用的场景；非 pi-window 场景下 inferred 成本已经按原 hard/static outsourcing window 口径计算，不额外复核。单向 `GC` 没有 K 堆最终复核流程，但它同样使用动态 job penalty 生成列；该路径后续不作为主要性能路径维护。

## 21. 2026-06-01 单向 GC 的列成本生成口径

单向 `GC` 的流程不是“先把候选列放进 K 堆，最后统一 finalize”，而是在每个 label 出队时直接尝试接到 sink。它先用 label 递推得到的 `label.minReducedCost - arcDual(last, sink)` 判断该序列是否可能是负列；这里的 `label.minReducedCost` 确实来自当前 pricing 使用的动态 penalty，根节点 no-cut 时可能包含 pi-window 收紧后的口径。

但通过负 reduced-cost 筛选后，单向 `GC` 并不会把这个 label 上的 inferred cost 直接写进 `TWETColumn`。代码会先 `recoverSequence(label)` 恢复完整 job 序列，然后调用 `evaluator.evaluate(sequence)` 重新按完整序列计算列成本，最后用这个 evaluator cost 构造 `TWETColumn`。因此单向路径不需要像 top-K 双向路径那样在 `finalizeGeneratedColumns()` 里再做一遍 `PricingColumnCostRechecker`；它没有 finalize 阶段，但每条实际返回列在加入 `generatedColumns` 前已经走了 evaluator 成本口径。这里仍要注意，单向 GC 返回的是发现顺序下的前 K 条负列，不是全局 top-K 候选堆。

## 22. 2026-06-01 completion bound 当前优化点复查

本轮复查 `CompletionBoundCalculator` 后，当前最需要优先处理的是两个确定成本点：`mergeFunction()` 为了判断 envelope 是否 changed，每次都会 `current.copy()`，再调用 `mergeMinimum()`，最后逐 segment 做 `sameFunction()`；同时 `forwardJobReducedPenalty()` 和 `backwardJobReducedPenalty()` 在每次 candidate 构造中都会复制 job penalty 并平移 job dual。这两个位置都位于 fixed-point propagation 的内层循环，比全量扫描 `job=1..n` 更值得先优化。

`mergeMinimum()` 目前返回 `void`，且内部注释也明确要求两个函数有正长度公共区间，不支持单点交集。若要从根上去掉 `mergeFunction()` 的 copy/compare，最干净的改法是让 `PiecewiseLinearFunction.mergeMinimum(...)` 返回 `boolean changed`，在替换片段、拼接左/右扩展或 normalize 后发生实际变化时置 true。这样可以省掉当前 completion bound 的整函数复制和二次扫描。不过这个函数也被 `DominanceGraph`、`DominanceNode` 和 `PaperDominanceGraph` 调用，修改签名会牵涉多个调用点；可以兼容地新增 `mergeMinimumAndReportChange(...)`，旧 `mergeMinimum(...)` 继续保留为包装方法，降低风险。cheap precheck 只能作为辅助，因为 candidate 的端点最小值不一定能证明整段 envelope 不改善，特别是分段函数有交点和方向化后下包络时，不能只看一个端点。

penalty reduced copy 的优化更局部、更安全。calculator 构造时可以预先生成 `forwardReducedPenaltyByJob[job]` 和 `backwardReducedPenaltyByJob[job]`：对输入 penalty copy 一次并 `shiftYInPlace(-lp.getJobDual(job))`。后续 `buildForwardCandidate()` / `buildBackwardCandidate()` 直接取数组传给 `add()`。如果 `add()` 不改写右参数，这样可直接复用；若后续不确定 `add()` 是否破坏输入，再保守地在数组里存 reduced penalty、调用点按需 copy，但至少能把 job dual shift 从 candidate 内层拿出去。当前看这个优化不改变数学语义，适合先做。

single-point domain 的问题需要谨慎。当前 `hasPositiveDomain()` 严格要求 `head.start < tail.end`，因此所有零长度函数都会被丢掉；`mergeMinimum()` 本身也不能处理只有一个点的 overlap。对 completion bound 来说，丢掉可行 relaxed completion 会让 `U_i/R_i` 偏高，用于剪枝时存在风险。实际影响大概率集中在右边界 `pricingHorizon` 或半域交界 `Tmid` 附近的退化点，尤其是 Tmid 正式 labeling 已经专门支持 single-point label，说明这类边界不是理论上不存在。短期更稳的做法不是强行让 completion bound merge 单点，而是补一个统计：记录 candidate 因 `head.start == tail.end` 或 overlap 单点被丢弃的次数和位置，先确认 wet020/025/030 是否真的出现。如果确实只在 `pricingHorizon` 点出现，且该点代表完整收尾边界，可以考虑专门把“单点可完成”作为剪枝保护条件：遇到这种 candidate 不参与 relaxed bound 传播，但也不能因此提高 bound 去剪 label。

FIFO 与 reduced-cost queue 的复杂度也已经能解释。FIFO 模式中，一个 state 已在队列里时再次改善不会重复入队，数组里的函数 envelope 会被更新，等原队列项出队时读取最新函数；`enqueue` 和 `poll` 都是摊还 O(1)。reduced-cost 模式用 `PriorityQueue`，为了更新已有 state 的 priority，当前调用 `priorityQueue.remove(state)`，这是 O(q) 线性删除，然后再 O(log q) 插入；`poll` 是 O(log q)。更关键的是它会改变 fixed-point 收敛路径，之前 wet030 实测 merge 次数显著增加。因此 reduced-cost 队列不只是数据结构维护更贵，也可能让更多未稳定 envelope 过早传播，当前不应作为默认。

每次出队扫描所有 job 的 O(pop*n) 循环可以后置优化。all-cycles 状态少一些，two-cycle 状态是二维 last-arc，扫描代价会放大；预处理 `availableForwardSucc[prev]` 和 `availableBackwardPred[succ]` 能减少 zero-dual、forbidden arc、自环和 2-cycle 检查，但这需要按当前 node 的 forbidden arc 每轮构造邻接表。当前主要瓶颈仍更可能来自每条候选上的函数构造、penalty copy 和 merge copy，因此邻接表优化优先级低于前两项。

## 23. 2026-06-01 completion bound 内层开销优化实现

本次按前一节的优先级做了三类低风险优化。第一，`PiecewiseLinearFunction.mergeMinimum(...)` 保留原有 `void` 入口，同时新增带 `boolean reportChanged` 参数的重载，供 completion bound 在 merge 后直接获得 changed 结果。实现上没有继续用“复制旧函数再逐段比较”的方式，而是在破坏式合并前先扫描当前函数和候选函数的公共区间：只要候选在某个正长度子区间端点严格低于当前函数，或候选扩展了当前函数定义域，就认为本次 lower envelope 会改变。这样避免了 `current.copy()` 和 `sameFunction()` 的大量对象分配，同时旧 dominance graph 调用仍走原入口，不改变既有行为。

第二，`CompletionBoundCalculator` 在构造时预先缓存 `forwardReducedPenaltyByJob` 和 `backwardReducedPenaltyByJob`，把原先每个 candidate 都会执行的 penalty copy 和 `shiftYInPlace(-jobDual)` 移到每轮 calculator 初始化阶段。由于 `PiecewiseLinearFunction.add(...)` 返回新函数且不改写右参数，candidate 构造时可以安全复用该 reduced penalty 缓存。

第三，顺手把 completion bound 出队后的全量 job 扫描收缩成当前 node 下的可用邻接表。calculator 初始化时预先构造 `forwardSuccessorsByJob` 和 `backwardPredecessorsByJob`，过滤 zero-dual excluded job、forbidden arc 和自环；two-cycle 仍在遍历时额外跳过上一跳，保持 2-cycle relaxation 语义不变。这个优化不改变 FIFO / reduced-cost 队列策略，只减少每次 pop 后的无效检查。

single-point domain 这次不扩展传播语义。当前 completion bound 是 relaxed completion 的函数下界传播；一旦 candidate 只剩一个单点，它不能继续和任意后续 job 形成正长度函数传播，最多只能表达某个边界点的收尾值。直接把单点并入 `mergeMinimum` 会牵动 PWLF 的正长度 overlap 契约，收益不明确且容易引入边界错误。因此当前仍由 `hasPositiveDomain()` 丢弃单点 completion candidate；后续若怀疑它影响剪枝安全，应先加统计确认这些单点是否只出现在 `pricingHorizon` 或 `Tmid` 边界，再决定是否做专门的“不剪枝保护”而不是普通传播。

验证方面，focused `javac` 覆盖 `PiecewiseLinearFunction`、共享 completion bound calculator、Tmid/full-domain pricing 和配置类通过；`PaperDominanceGraphConsistencyTest` 仍通过 `cases=200, insertions=16000`。`tmp-wet020_001_2m` root-only Tmid 对照中，`completionBound=allCycles/fifo` 得到 `obj=6343, bound=6343, valid=true`，exact pricing 约 `0.125s`；同轮 `completionBound=off` 为 `obj=6343, bound=6343, valid=true`，exact pricing约 `0.282s`。

2026-06-01 补充 single-point completion candidate 的代码注释。`CompletionBoundCalculator.hasPositiveDomain()` 仍只接受正长度函数；注释明确这里不是普通 label single-point 语义，而是 relaxed completion bound 的状态传播语义：单点 candidate 最多表示某个边界时刻的收尾值，不能继续作为可接后继 job 的函数状态传播。继续保持这一限制可以避免把 `mergeMinimum()` 的正长度 overlap 契约扩展到单点情形。

2026-06-01 进一步澄清 `mergeMinimum(..., true)` 的 changed 语义。原 `mergeMinimum()` 的执行流程本身不是为返回 changed 设计的：它会在 lower-envelope 合并过程中拆段、替换、拼接并最终 normalize，这些链表结构操作不等价于“函数值真的变低”。如果直接在流程中看到替换或拼接就返回 changed，会把很多纯结构重写也算成改善，导致 completion bound fixed-point 队列反复入队。当前实现不是依赖原流程中的结构操作判断 changed，而是在真正破坏式 merge 前额外做一次轻量扫描：只要候选函数在正长度公共区间某个端点严格低于当前函数，或者候选扩展了当前函数定义域，才认为 lower envelope 发生有效改善。随后仍调用原 merge 逻辑完成实际合并，因此旧调用结果不变，新返回值只服务于 completion bound 是否重入队。

这里的 `reportChanged=true` 不是免费开关，它会额外扫描一次当前函数和候选函数的公共分段。这样做的目标是替代旧的 `current.copy() -> mergeMinimum() -> sameFunction()`，也就是用一次只读数值扫描替代整函数复制、破坏式合并后的整函数比较和大量 segment 对象分配。当前实现仍然在扫描后执行原 merge，因此每次 merge 都还有正常合并成本；进一步优化可以考虑在 `changed=false` 时直接跳过实际 merge，但这需要确认该预判在所有有效输入下都等价于“合并结果完全不变”。

因此这一版优化的预期不能理解成减少一次完整函数遍历的数量级成本。它主要减少的是对象分配和合并后比较：旧流程中为了知道是否 changed，需要先复制 current，再破坏式 merge，最后对 before/current 做逐段比较；新流程改为 merge 前只读扫描，避免复制 current 和 sameFunction。由于当前即使 `changed=false` 仍继续执行原 merge，真正的 merge、右参数 `g.copy()` 和 normalize 成本还在，所以收益会受 no-op merge 比例影响。若统计显示大量 `mergeCalls` 没有 `mergeChanged`，下一步更有价值的是让 completion bound 在预判 `changed=false` 时直接跳过实际 merge。

2026-06-01 继续检查 job penalty copy。正式 pricing 的 backward sink-root 扩展仍需要 `jobPenalty.copy()`，因为后续会直接 `shiftYInPlace()` 扣 job/arc dual，不能改写缓存的 job penalty。completion bound 这边则进一步收掉一个初始化阶段冗余：若 forward/backward completion penalty 数组中的同一 job 指向同一个 `PiecewiseLinearFunction` 对象，则 reduced penalty 只复制和平移一次，另一侧直接复用该 reduced 缓存。这个场景会出现在 Tmid completion bound 使用 full-domain completion penalty 时；由于 reduced penalty 只依赖 job dual，且 `add()` 不改写右参数，复用是安全的。

继续按 `PiecewiseLinearFunction.copy()` 调用点复查后，暂未发现另一个和 completion bound 初版相同的“每个 candidate 反复复制 penalty 再平移 job dual”的冗余模式。正式 pricing 中 source frontier 的 copy 每轮只做一次；backward sink-root 的 `jobPenalty.copy()` 是为了避免后续原地扣 dual 改坏缓存；dominance graph / paper dominance graph 的 copy 主要是在保存 envelope 或 reachable key，属于状态隔离成本；heuristic pricing 的 penalty copy 位于具体 sequence 评价和 segment profile 构造里，不是 completion bound 这种 fixed-point 内层反复 reduced penalty 构造。因此本轮只保留 completion bound cache 复用这个小优化，不扩大到正式 label 扩展。

队列语义也进一步明确。FIFO 模式使用 `ArrayDeque<QueueState>` 和每个 `QueueState` 自带的 `inQueue` 布尔标记；判断 state 是否已经在队列中是 O(1)，不是扫描队列。若该 state 已在 FIFO 队列中，新的改善只更新外部 `forwardF/backwardB` 数组里的函数，不再重复入队。出队时通过 state id 读取数组中的最新函数，因此队列项本身不是函数快照。

REDUCED_COST 模式当前使用 `PriorityQueue<QueueState>`。为了让队列中的 priority 反映最新函数，若 state 已在队列中，会先 `priorityQueue.remove(state)` 再重新 add；`remove(Object)` 在 Java `PriorityQueue` 中是 O(q)，这是该模式的主要额外开销。可以改成 lazy duplicate：每次改善都 add 一个带序号的新队列项，state 上记录最新序号，poll 时若序号过期就跳过。这样每次改善从 O(q) 删除降为 O(log q) 插入，但队列会保留 stale entry，poll 端可能多做若干次 O(log q) 弹出和 O(1) 跳过。若仍保留 reduced-cost 对照，这个 lazy 方案会比线性 remove 更合理；但在 FIFO 已是默认且实测更稳的前提下，优先级不高。

进一步讨论后补充：REDUCED_COST 模式也可以像 FIFO 一样只用 `inQueue` 去重，state 已在队列中时直接 return，不做 remove/reinsert，也不做 lazy duplicate。这样每个 state 在队列里最多一个条目，入队判重是 O(1)，数据结构最简单；由于真正函数状态保存在 `forwardF/backwardB` 数组中，出队时仍会使用最新函数，因此 fixed-point 正确性不受影响。代价是 priority queue 的排序键只反映该 state 当次入队时的 priority，若后续函数又改善但 state 仍在队列里，堆内位置不会更新，出队顺序不再严格代表最新 reduced cost。直接修改 `state.priority` 也不行，因为 Java `PriorityQueue` 不会自动重建堆序。也就是说三个方案的取舍是：FIFO/inQueue 最便宜但无 priority；priority+inQueue 最便宜且保留粗略 priority，但 priority 可能 stale；lazy duplicate 或 remove/reinsert 才能维护较新的 priority，其中 lazy duplicate 避免 O(q) remove，更适合作为严格 priority 对照。

进一步收敛后，若后续还要保留 REDUCED_COST 或扩展到 time/reduced-cost 组合排序，更合适的是“lazy duplicate + 每个 state 最新入队序号”方案。每次 state 改善后都生成一个不可变队列项，记录 state 引用、priority 和递增序号；state 自身保存 `latestQueueSeq`。priority queue 按队列项 priority 排序，poll 后先检查 `entry.seq == state.latestQueueSeq`，不一致说明它已经被同 state 后续入队项覆盖，直接丢弃；一致时才把 state 当作最新状态扩展。这样对不同 state 仍尽量按设定 priority 出队，对同一 state 只让最后一次入队项生效，避免旧 entry 扩展。复杂度上，每次改善是 O(log q) 插入，过期 entry 只在 poll 时额外付一次 O(log q) 弹出和 O(1) 检查，不再有 `PriorityQueue.remove(Object)` 的 O(q) 删除。它比 priority+inQueue 的优势是 priority 不会长期停留在旧值；比 remove/reinsert 的优势是没有线性删除。

2026-06-01 已按上述方案修改 `CompletionBoundCalculator` 的 REDUCED_COST 队列。priority entry 现在保存 state、最早时间、reduced-cost priority 和入队序号；不同 state 先按最早时间、再按 reduced cost、再按 state id 排序，同一 state 按更大的入队序号优先。poll 前会跳过 `seq != state.latestQueueSeq` 的 stale entry，并统计 `priorityQueueStalePops`。FIFO 逻辑保持原样，仍用 `inQueue` 保证一个 state 至多一个队列项。

wet030 root-only allCycles 对照显示，lazy priority 只解决了 Java `PriorityQueue.remove(Object)` 的线性删除问题，没有改变 REDUCED_COST 排序本身不适合 fixed-point propagation 的结论。新 lazy priority 结果为 `solve=72.883s, exact=65.289s`，比旧 remove/reinsert 记录 `exact=68.318s` 略好，但仍远慢于 FIFO 本轮 `solve=10.961s, exact=3.237s`，也慢于早前 FIFO 记录 `exact=2.323s`。内部统计也说明原因：FIFO 第一轮约 `fCand/bCand=7251/7743, merge=29710`；lazy priority 第一轮约 `fCand/bCand=29146/171709, stale=9836, merge=388042`。因此主要问题不是 remove 的线性复杂度，而是 priority 顺序让 envelope 更新更碎、反复传播更多状态；当前默认继续保持 FIFO。

2026-06-01 继续补充 FIFO 更快的原因。completion bound 的队列不是最短路 label-setting，而是 lower-envelope fixed-point propagation；一个 state 的函数会被很多前驱/后继路径逐步改善，队列项本身也不保存函数快照，真正函数保存在 `forwardF/backwardB` 或二维数组里。FIFO 中如果 state 已经在队列里，新改善只更新数组里的最新 envelope，不重复入队；等这个 state 原来的队列项出队时，会一次性用最新 envelope 扩展。因此多个小改善会被自然合并成一次扩展，减少中间 envelope 传播。

priority 队列即使用 lazy duplicate 去掉了线性 remove，也会更倾向于把局部 priority 看起来好的 state 立刻拿出来扩展。问题是这个局部低 reduced cost 不代表该 state 的整条 envelope 已经稳定，也不代表它扩展出去后会减少总工作量；后续同一 state 或相邻 state 的 envelope 继续降低时，前面已经扩展出去的候选大多会变成中间结果，仍然增加 candidate 构造、`mergeMinimum` 和后续入队。wet030 中 backward 侧尤其明显，lazy priority 的 stale entry 虽然能跳过同 state 旧队列项，但无法撤销旧 envelope 已经向前驱传播造成的额外 merge。

因此 FIFO 快的原因可以概括为两点。第一是数据结构便宜：`ArrayDeque` 加 `inQueue`，入队判重和出队都是 O(1)，且没有 stale entry。第二更重要，是传播节奏更适合这个问题：FIFO 对同一 state 的多次改善有天然 coalescing 效果，出队时读取的是最新函数；priority 则追求局部排序新鲜度，反而破坏这种合并效果，让 fixed-point 过程产生更多临时 envelope。当前数据也支持这个判断：lazy priority 比旧 priority 略快，说明数据结构优化有效；但它仍比 FIFO 慢一个数量级，说明主导因素是传播顺序而不是 `PriorityQueue.remove(Object)`。

2026-06-01 补跑当前代码下 wet030 root-only FIFO 的 allCycles 与 twoCycle completion bound 对照。两者最终列数、incumbent、bound 和 valid 状态一致，都是 `NODE_LIMIT, obj=46152, bound=15261.833333, pool=4781, valid=true`。总体时间上，allCycles 为 `solve=7.269s, exact=2.244s`，twoCycle 为 `solve=37.682s, exact=30.492s`。差异主要来自 completion bound 构造：allCycles 5 次 exact pricing 的 `completionBound buildMs` 合计约 `1.883s`，twoCycle 合计约 `30.034s`，twoCycle 多约 `28.151s`，约为 allCycles 的 `16.0` 倍。

内部统计进一步说明 twoCycle 的成本来自状态维度膨胀。allCycles 5 轮合计约 `fCand=33,877, bCand=37,468, merge=141,530, changed=55,775`；twoCycle 合计约 `fCand=778,756, bCand=920,986, merge=3,261,696, changed=941,995`。twoCycle 虽然更强地排除了直接 2-cycle，但 relaxed completion bound 要按 `(prev,current)` 或 `(current,successor)` 状态维护二维 envelope，传播和最终聚合的 merge 数量放大到二十倍量级。因此在当前 wet030 root-only 设置下，twoCycle 的剪枝收益没有覆盖 bound 构造成本，默认 allCycles 更合适。

需要注意，上述 `GCBBFullDomainComparisonTest` 对照没有打开 ALNS seed。该 runner 中 `config.runALNSForSeed=false`，log 里的 `heuristic_s` 来自 BPC root 过程中的 `HeuristicPricingEngine` Tabu pricing，不是 ALNS/VND 初始解改进。因此 `obj=46152` 是初始 seed incumbent，log 中也显示 `initial incumbent=46152`、`incumbent updates=0`。如果要比较打开 ALNS 后的 incumbent 或 root gap，需要单独给 runner 增加开关或换用支持 seed ALNS 的入口。

twoCycle 的 `exact_s=30.492s` 是 exact pricing engine 的总耗时，里面包含 completion bound 构造。拆开看，5 次 exact call 的 twoCycle `completionBound buildMs` 合计约 `30.034s`，而 exact call 总计 `30.492s`，相差约 `0.458s`；日志中的 `timingMs fwExt/bwExt/join/extTotal/measuredTotal` 合计约 `0.322s`。也就是说，在这个设置下 twoCycle 真正 label 扩展和 join 部分确实不到 0.5 秒，总时间基本都被 completion bound 的二维 relaxed propagation 吃掉了。

2026-06-01 进一步明确后续默认选择。当前简单测试已经比较稳定地表明，completion bound 的 two-cycle relaxation 虽然更紧，但计算复杂度很难像经典 VRP 标量 bound 那样有效压缩。原因是这里的状态值不是一个 reduced-cost 数值，而是一条分段线性函数；对每个 `(prev,current)` 或 `(current,successor)` 状态都要维护完整 PWLF envelope，并在 fixed-point propagation 中反复 merge。经典 VRP 中每个节点保留最优和次优两个标量值的 trick，不能直接迁移到这里，因为不同时间点的最优前驱/后继可能不同，无法用两个标量概括整条函数下包络。因此 two-cycle 版本基本需要完整二维数组建表和迭代更新，短期没有明显的低风险复杂度优化空间。

从实验判断看，wet030 root-only 中 two-cycle bound 对 label/join 的确有剪枝，但相对 all-cycles 的最终作用不大；主要求解结果、列池规模和有效性一致，剪枝节省的 label/join 时间不到 `0.5s`，而 bound 构造多花约 `28s`。因此当前工程默认应只把 `ALL_CYCLES + FIFO` 作为基础 completion bound 组件：它构造快，剪枝效果已经接近 two-cycle，整体性价比明显更高。two-cycle 可以保留为可选对照和论文中的强化 relaxation 说明，但不应作为默认定价流程。

论文表述上可以突出两点。第一，当前函数型占优/分段线性 reduced-cost pricing 的实现里，relaxed completion bound 这类按 job 聚合的函数下界并不常见；它不依赖 elementary label 的 visited/reachable 状态，却能在 join 前对 forward/backward 函数组合做有效剪枝，这一点有独立贡献价值。第二，可以把 all-cycles 作为主算法组件，把 two-cycle 写成更强但更重的扩展 relaxation：理论上更紧，实验上在当前实例中边际收益不足以抵消构造成本。后续若实现 NG-set pricing，还可以复用这套 completion bound 思路重新测试；NG memory 本身会改变可达状态和 join 候选结构，completion bound 的相对收益可能不同，值得作为后续实验项保留。

## 24. 2026-06-01 node join 单侧越过 Tmid 对照

本轮针对 full-domain node join 做了一个更小的实验开关：`TWETBPCConfig.fullDomainNodeJoinCrossingSide`，可选 `both/forward/backward`，默认仍为 `both`，保持原有实验口径。含义是 node join 中哪一侧允许生成“第一次越过 Tmid”的 boundary terminal label；未被允许越界的一侧回到对应半域，forward 限制在 `[0,Tmid]`，backward 限制在 `[Tmid,pricingHorizon]`。这样可以测试“同一个 node join 只需要一侧跨过 Tmid，另一侧不必也做 full-domain 扩展”的判断。

在 `wet020_001_2m` 和 `wet021_001_2m` 上，completion bound 均保持 `off`，比较 full-domain arc join、原 node join 双侧越界、forward-only 越界和 backward-only 越界。结果均保持 `ROOT_PROCESSED`、目标值等于 bound、验证可行。full-domain arc join 仍最快：`wet020` 为 `solve=1.878s, exact=0.640s`，`wet021` 为 `solve=2.435s, exact=0.911s`。原 node join 双侧越界明显最慢：`wet020` 为 `solve=6.870s, exact=5.783s`，`wet021` 为 `solve=24.305s, exact=22.420s`。

单侧越界能显著降低 node join 成本。forward-only 越界得到 `wet020 solve=3.196s, exact=2.042s`，`wet021 solve=8.862s, exact=7.126s`；相对原 node join，exact 时间分别降低约 `64.7%` 和 `68.2%`。backward-only 越界也有改善，但不如 forward-only：`wet020 solve=5.917s, exact=4.569s`，`wet021 solve=15.788s, exact=14.052s`，相对原 node join exact 时间分别降低约 `21.0%` 和 `37.3%`。

从日志看，forward-only 的收益主要来自 backward 侧 label 和 join 候选大幅减少。以 `wet021` 第一轮为例，原 `both` 的 backward kept/dominated 为 `23574/107948`，boundary terminal bw 为 `13658/98686`，join function evaluation 为 `2063282`；forward-only 下 backward kept/dominated 降为 `967/10071`，boundary terminal bw 为 `0/0`，join function evaluation 降为 `57188`。forward 侧保持原 full-domain boundary，仍能覆盖“第一个跨过 Tmid 的 join job”，因此本轮没有损失最终目标和 bound。backward-only 对称地压缩 forward 侧，但由于 backward 侧本身更重，保留 backward full-domain 后整体仍明显慢于 forward-only。

当前结论是：如果继续维护 node join 分支，默认候选应优先考虑 `crossingSide=forward`，而不是原来的双侧越界。它仍慢于 full-domain arc join，但已经把 node join 的额外成本从一个数量级拉回到可比较范围；后续若要继续看 node join 的价值，应先在 20/21 以外的样例确认 forward-only 不会漏列或增加迭代轮数，再决定是否替换默认 node-join 实验口径。

## 25. 2026-06-01 node join 慢因与 full-domain/half-domain 统计解释复盘

本轮复盘两个问题。第一，node join 为什么仍明显慢于 arc join。需要先修正一个容易误说的因果：`preNodeFrontier` 不参与 `PaperDominanceGraph` 的占优判断，图里的 key 和 envelope 仍来自传播函数 `frontier`、visited/reachable set 与同端点 job；因此不能说“preNodeFrontier 让支配图变大”。node join 变慢的真实原因是为了做同点拼接，reachable set 改成 full-domain 一跳可达，第一次越过 `Tmid` 的 child 又会作为 boundary terminal label 保留下来参与 join；这会让正式入图 label 和 terminal label 都明显增多。以 `wet021` 第一轮为例，full-domain arc join 的 forward/backward kept 只有 `2103/1424`，dominance checks 为 `42269`，exact time 约 `0.478s`；原 node join 双侧越界变成 `10847/23574`，dominance checks 为 `543788`，exact time 约 `11.400s`。即使 final join measured time 只有 `0.495s`，大部分时间也已经被 `fwExt/bwExt=3.940s/6.937s` 的标签扩展和支配图维护吃掉。也就是说 node join 慢主要是 label table 变大，尤其 backward 侧 full-domain terminal 后缀膨胀，而不是单纯 join 函数评价慢。

单侧越界实验进一步支持这个判断。`crossingSide=forward` 保留 forward 侧第一次越过 `Tmid` 的 terminal label，但把 backward 侧收回半域后，`wet021` 第一轮 backward kept 从 `23574` 降到 `967`，boundary terminal bw 从 `13658/98686` 降到 `0/0`，join function evaluation 从 `2063282` 降到 `57188`，exact time 从 `11.400s` 降到 `4.014s`。但它仍慢于 arc join 的 `0.478s`，因为 forward 侧仍保持 node-join 口径下的大量 full-domain boundary label：forward kept 仍是 `10847`，显著高于 arc join 的 `2103`，dominance checks 也仍为 `159045`，高于 arc join 的 `42269`。因此 node join 的工程结论是：双侧越界肯定不合适，forward-only 是更合理的 node-join 对照；但它仍不是当前 arc join 的直接替代。

`forward-only` 中 backward kept 比 arc join 还少，也不是矛盾，因为二者 backward 侧并不是同一口径。full-domain arc join 的 source/sink root 和 job penalty 都使用完整 `[0,pricingHorizon]` 定义域，只是 direct-feasibility 仍要求 backward 继续扩展的前驱能落到 `Tmid` 右侧；而 `nodeJoin-crossforward` 因 `allowBackwardCrossing=false`，backward sink root 从 `Tmid` 开始，backward job penalty 也裁到 `[Tmid,pricingHorizon]`，并且不生成越过 `Tmid` 左侧的 boundary terminal。这个半域化的 backward 函数支持更窄，suffix-min 后标签更容易在同端点图里互相支配，所以 `bw kept=967` 可以低于 full-domain arc join 的 `1424`。它说明的是“backward 被收窄了”，不是 node join backward 比 arc join 自然更强。

第二，之前 full-domain arc join 有时比 half-domain 略快，不能理解成“全域函数更便宜”或“半域裁剪没用”。当时 `wet020/wet021` 小样例上，half/full exact 分别约为 `0.377/0.149s`、`0.651/0.587s`，单独 JVM 下 `wet021` 为 `0.887/0.717s`；扩展到 10 个 15 任务算例加 20/21/22/25 后，14 个 paired case 平均 exact 为 `1.414/1.290s`，solve 为 `2.038/1.882s`。但这个平均受小样例毫秒级噪声影响很大，且大样例并不单调：`wet022` full 比 half 慢约 `15.1%`，`wet025` full 又快约 `8.3%`，单独 JVM 下 `wet025` half/full 为 `18.163/17.045s`。

统计解释是 full-domain 把复杂度从 dominance graph 转移到了 final join。`wet022` 上 full 的 join candidate 从 `108876` 增到 `2079560`，function eval 从 `70768` 增到 `1162022`；`wet025` 上 join candidate 从 `843611` 增到 `27105684`，function eval 从 `562421` 增到 `14763123`。这说明 full-domain 并没有少做 join 工作，反而会显著放大 join 扫描。另一方面，full-domain 的 dominance graph superset visited 降了：`wet025` 从 `44085562` 降到 `33835821`，`wet022` 从 `2559456` 降到 `1642380`。因此它有时 wall time 略快，是因为支配图搜索下降抵消了 join 爆炸，而不是因为全域标签天然更优。

还要注意，这个 full-domain 复制版不是严格的“只改变函数段数”的微基准。正式 half-domain 的 `minReducedCost` 来自 `[ell,Tmid]` 或 `[Tmid,rho]` 的半域端点；full-domain 放宽到 `[0,pricingHorizon]` 后，端点 min、label 排序、group/pair lower bound 都变了，lower bound 更乐观，costLB 剪枝更弱。例如 `wet025` 的 join group costLB prune 从 `167554` 降到 `46563`，这会直接放大 join 扫描。当前判断仍是：half-domain 语义更清楚，join 爆炸控制更稳；full-domain arc join 可以保留为工程对照和诊断工具，但不是一个明确优于正式 half-domain 的默认替代。
## 26. 2026-06-02 forward-only node join 反向标签数与 full-domain dominance 解释补充

关于 `nodeJoin-crossforward` 中 backward kept 比 full-domain arc join 还少的问题，关键是两者的反向侧并不是同一个状态空间。`GCBBStyleBidirectionalFullDomainNodeJoin` 的 crossing side 为 `FORWARD` 时，`allowBackwardCrossing()` 为 false，因此 backward sink root、backward job penalty 和 backward direct feasibility 仍然保留 `Tmid` 下界：sink root 从 `[Tmid, H]` 出发，job penalty 裁到 `[Tmid, H]`，`isDirectBackwardExtensionTimeFeasible(..., requireTmid=true)` 要求补全后仍能落在右半域。与此同时，因为 backward 不允许 crossing，也不会产生 backward boundary terminal。相反，`GCBBStyleBidirectionalFullDomain` 的 backward sink root 是完整 `[0, H]`，虽然 direct reachable 仍要求能接到 `Tmid` 右侧，但 label frontier 本身可以保留更宽的完整函数定义域。因此 forward-only node join 的 backward kept 为 `967`，低于 full-domain arc join 的 `1424`，不是异常；它只是一个更窄的半域反向侧。

full-domain dominance graph “更强”也需要精确定义：不是说 full-domain 的 dominance 数学条件天然更强，而是在当前实现和样例统计中，full-domain label 的 `frontier` 定义域更宽、更连续，`canCoverDomain(envelope, label.frontier)` 更容易满足，且同一 reachable-set 节点上的 label envelope 与 predecessor envelope 能覆盖更多后续 label 的定义域，所以 `insertOrDominate()` 和 propagation 中更容易直接用已有 envelope 拒绝或删除 label。半域版本的 frontier 被裁到 `[0,Tmid]` 或 `[Tmid,H]`，再叠加 single-point/边界情形，许多 envelope 只能覆盖窄区间；即使值上有优势，也可能因为覆盖不了对方完整定义域而不能触发 dominance，于是需要保留更多 label 或走更多 superset 节点搜索。之前 wet022/wet025 里 full-domain 的 superset visited 下降，主要反映的是这个“覆盖域更完整，envelope 更容易生效”的工程现象。

但这个优势会换来 final join 侧的代价。full-domain 的 label 最小值和 group/pair lower bound 更乐观，costLB 剪枝更弱，因此更多 forward/backward pair 会进入函数 `add/findMinimal()`；半域版本虽然 dominance 图搜索更重，但 join 侧更容易被 `Tmid` 语义和半域 minReducedCost 剪掉。当前结论仍是：full-domain 常把工作量从 dominance graph 转移到 final join，是否更快取决于实例瓶颈；node join 另外引入 crossing boundary terminal，标签规模本身放大，所以不能直接拿它代表 full-domain arc join。

进一步复核后，需要把上一段的“当前实现解释”和“理想 full-domain 对照口径”分开。若实验目标是“full-domain label 始终定义在 `[0,H]`，只改变一侧是否允许继续跨过 `Tmid` 扩展”，那么非 crossing 侧不应该把 penalty/root 裁到半域；它应保持 `[0,H]`，只在 direct feasibility 或 child 入队规则上要求不过 `Tmid`。当前 `nodeJoin-crossforward` 不是这个口径，它把 backward root 和 backward penalty 都裁成 `[Tmid,H]`，因此 backward kept 少于 full-domain arc join 不能证明 full-domain 反向扩展本身更小，只说明裁剪后的半域 backward dominance 更强。

`[Tmid,H]` 裁剪会让 label 数变少的主要原因确实是 dominance 更容易成立。对同一条 backward 序列，完整 `[0,H]` frontier 在右半域可行时通常能裁成一个 `[Tmid,H]` label；但存储 label 数不是按序列一一保留，而是在每一步插入 dominance graph 前就会被支配过滤。裁掉 `[0,Tmid)` 后，两个 label 在左半域的差异被抹掉，`canCoverDomain(envelope, label.frontier)` 只需覆盖右半域，`dominates()` 也只在右半域比较，已有 envelope 更容易支配新 label。因此 sequence-level 可裁剪映射存在，不代表 table-level kept 数相同；半域裁剪会更早合并/删除一些在 full-domain 下因为左半域差异而无法互相支配的 label。
## 27. 2026-06-02 node join 与 full-domain/half-domain 的最终口径复盘

重新收敛后，node join 慢因应只按 one-sided 版本解释，不再拿 both-crossing 的最差结果作为主要论据。`wet021` 第一轮统计中，full-domain arc join 为 `fw/bw kept=2103/1424`，`fwExt/bwExt/join=266/39/168 ms`；node join forward-only 为 `10847/967`，`3936/40/24 ms`；node join backward-only 为 `1723/23574`，`290/6362/89 ms`。这说明 one-sided node join 的慢点不在 final join：forward-only 的 join 只有几十毫秒，但 forward extension/dominance 被放大；backward-only 对称地被 backward extension/dominance 放大。根因是 node join 必须把 crossing job 物化进某一侧 label，才能在同一个 node 上拼接；而 arc join 可以把 crossing job 留在 final crossing arc `(i,r)` 上处理，不需要提前生成 crossing-side label。也就是 node join 把 arc join 的一部分组合工作前移到 label extension 和 dominance table，导致那一侧 label table 膨胀。

因此，node join 的工程劣势是 crossing-side label 空间变大，而不是 join 公式本身慢。forward-only 已经比 both-crossing 好很多，因为它取消了 backward boundary terminal 和大量 backward label；但它仍保留 forward 侧 crossing terminal，所以 `fw kept=10847` 仍远大于 arc join 的 `2103`，dominance checks 也仍高于 arc join。这解释了为什么 forward-only node join 明显优于 both-crossing，但仍慢于 arc join。backward-only 更慢，是因为保留 backward crossing 侧时，该侧 label 规模在样例上更重。

full-domain 比 half-domain 偶尔更快，也不能解释成“full-domain 局部占优更强”或“扩展更便宜”。局部看，半域裁剪通常更容易占优：定义域从 `[0,H]` 缩成 `[0,Tmid]` 或 `[Tmid,H]` 后，`canCoverDomain + dominates` 只需在较小区间成立，很多左/右半域外的差异被抹掉，kept label 可以减少。`nodeJoin-crossforward` 的 backward kept 比 full-domain arc join 更少，正是这个半域裁剪占优更强的例子，而不是 full-domain 优势。

之前 full-domain arc join 有时更快，优势来自总工作结构，而不是单次 dominance 条件。统计显示 full-domain 会显著增加 final join 工作：`wet022` 的 join candidate 从 `108876` 增到 `2079560`、function eval 从 `70768` 增到 `1162022`；`wet025` 的 join candidate 从 `843611` 增到 `27105684`、function eval 从 `562421` 增到 `14763123`。这些都是 full-domain 的劣势，因为 lower bound 更乐观、costLB 剪枝更弱。另一方面，full-domain 的 dominance graph superset visited 下降：`wet025` 从 `44085562` 降到 `33835821`，`wet022` 从 `2559456` 降到 `1642380`。这个下降更像是 graph 拓扑和边界碎片减少带来的搜索总量下降：full-domain 不制造那么多半域边界、single-point 和窄定义域节点，reachable-set 图上的 superset traversal 少了一些。

所以最终结论是：half-domain 在局部占优判定和 final join 剪枝上更有理论优势，语义也更稳；full-domain 的潜在优势只是在某些实例上减少 dominance graph 的搜索路径和边界碎片，可能抵消它在扩展函数和 final join 上的额外成本。它不是稳定更优的默认路线，只是一个有时能通过降低 graph traversal 获得 wall-time 收益的工程对照。node join 则额外把 crossing arc 前移成 crossing-side label，因此即使做 one-sided，也仍然比 arc join 多承担一侧 label table 膨胀的成本。

## 28. 2026-06-02 dominance graph superset visited 的含义

`PaperDominanceGraph` 里的 `superset calls/visited` 统计的是 dominance graph 为了寻找可支配当前 label 的 reachable-set 超集节点所做的图搜索工作，不是 label 数、join pair 数，也不是成功支配次数。插入一个 label 时，如果图里已经有完全相同的 `reachableSet` 节点，就先直接用该节点的 envelope 检查；如果没有，代码会调用 `findTerminalSupersetNodes(label.reachableSet)`，从所有 `reachableKey.isSupersetOf(target)` 的根节点开始 DFS，只沿着仍然是 target 超集的后继往下走，并返回最深的 terminal superset 节点。每实际弹出并访问一个 graph node，就累加一次 `supersetNodesVisited`。single-point label 的 `dominatesSinglePoint(...)` 也会走类似的 terminal-superset 查询。

这样做的原因是 elementary dominance 里 reachable-set 是资源的一部分。一个已有 label 如果剩余可达任务集合是当前 label 的超集，说明它后续选择空间至少不比当前 label 小；再加上 visited/terminal job 兼容、frontier envelope 能覆盖当前 label 定义域并且 reduced-cost 函数不差，它就可以支配当前 label。因此 dominance graph 不是只查“同一个 reachableSet”，还要查“可能更强的 reachableSet 超集”。这一步就是 `superset visited` 的来源。

full-domain 让这个数下降，不能解释成 full-domain 的局部占优条件天然更强。更准确的解释是 graph 拓扑和查询目标变了：half-domain 在 `Tmid` 附近更容易产生窄定义域、single-point 边界 label，以及更快收缩的 `reachableSet`。目标集合越小，它的超集越多，例如目标 `{5}` 的超集远多于目标 `{5,6,7}`，DFS 就更容易走过更多 graph node。full-domain 不裁左右半域，通常减少这类边界碎片和单点查询，label 的 reachable-set 分布也可能更集中或更大，所以 terminal-superset 搜索的总访问量会下降。

这只是总工作量的结构性转移。full-domain 可能少走 dominance graph 的 superset traversal，但它的 final join 下界更乐观、costLB 剪枝更弱，导致更多 pair 进入函数拼接和 `findMinimal()`。因此之前 wet022/wet025 里看到的现象应读作：full-domain 减少了一部分 dominance graph 搜索成本，同时显著增加 final join 成本；最终快慢取决于哪一边占主导，而不是 full-domain 在占优和扩展两件事上都更省。

## 29. 2026-06-02 full-domain node join 单侧模式的定义域口径

当前 `GCBBStyleBidirectionalFullDomainNodeJoin` 的 one-sided crossing 模式并不是严格的 full-domain 对照。`allowForwardCrossing()` / `allowBackwardCrossing()` 不只控制某一侧是否允许跨过 `Tmid` 后继续扩展，还被用于 source/sink root 和 job penalty 的定义域裁剪：forward source 与 penalty 使用 `allowForwardCrossing() ? [0,H] : [0,Tmid]`，backward sink 与 penalty 使用 `allowBackwardCrossing() ? [0,H] : [Tmid,H]`。因此 `nodeJoin-crossforward` 的 backward 侧实际是半域右侧，不是完整 `[0,H]`；`nodeJoin-crossbackward` 的 forward 侧也对称地是半域左侧。

如果目标是比较“full-domain node join 下 crossing job 放在哪一侧”，更干净的口径应是：两侧 label frontier 和 job penalty 始终定义在 `[0,H]`，`fullDomainNodeJoinCrossingSide` 只控制哪一侧在第一次越过 `Tmid` 后被保留为 boundary terminal、哪一侧不继续跨界扩展。也就是说，是否 crossing 是扩展/入队规则，不应隐含改变函数定义域。这样 one-sided node join 与 full-domain arc join 的非 crossing 侧才处在同一函数状态空间中。

半域裁剪仍然可以作为一个单独工程模式保留或记录，但语义应明确标注为“裁掉另一半定义域以加强 dominance / 减少 label”的变体，而不是 full-domain。它的直觉是：如果一个 label 只在 `[Tmid,H]` 或 `[0,Tmid]` 上比较，就不需要考虑另一半函数长什么样；`canCoverDomain + dominates` 的比较区间更小，已有 envelope 更容易覆盖并支配新 label，因此 kept label 可能更少，函数拼接和占优检查也可能更快。这和正式 Tmid half-domain 路径的逻辑一致，但它不再回答 full-domain ablation 的问题。

当前代码已经考虑了 `Tmid` 单点：`insertForward()` / `insertBackward()` 会先调用 `isSinglePointFrontier()`，恰好退化到 `tMid` 的 label 进入 `SinglePointStore`，并通过 `dominatesSinglePoint(reachableSet, tMid, minReducedCost)` 与普通 dominance graph 做点支配检查。需要注意的是，这套逻辑只识别 `head.start == tail.end == tMid` 的单点。如果后续把半域裁剪做成可选模式，仍以 `Tmid` 为切分点时可以复用；若裁剪点、local horizon 或其他边界导致单点不在 `Tmid`，则需要把 single-point 判定和点支配查询参数化，否则会漏处理退化 label。

## 30. 2026-06-02 full-domain 优势与 NG memory 后的预期

full-domain 为什么会在当前 `PaperDominanceGraph` 的 superset traversal 上更省，暂时还只能解释为统计层面的 graph 拓扑差异：它减少半域边界碎片、single-point 查询和很小 reachable-set 带来的超集搜索，但具体由哪些 reachable-set 分布变化贡献最大，还没有完全拆清。这个优势不应被视为 full-domain 的稳定理论优势，因为它同时显著放大 final join candidate 和 function evaluation。

如果后续加入 NG memory，这个优势很可能会弱化。NG 会把 dominance 的资源状态从纯 elementary visited/reachable 进一步局部化，更多不可访问或可忽略的历史信息被压进小邻域记忆，reachable-set/visited-set 的差异对 dominance graph 搜索的影响会改变；full-domain 当前靠“减少半域边界碎片和 superset traversal”得到的收益，可能被 NG 本身的状态压缩与支配加强吸收掉。换句话说，NG 可能直接优化掉 full-domain 现在表现出来的一部分 graph-search 优势。

相反，half-domain 在 NG 下的相对吸引力可能更强：它的函数定义域更短，扩展时 `shift/add/normalize` 更轻；占优比较只在半域上进行，更容易成立；final join 的 `Tmid` 语义给 group/pair lower bound 更强的剪枝空间。NG 如果再减少状态爆炸，half-domain 的“强 bound、强 dominance、轻函数扩展”更可能转化成稳定收益。因此后续正式路线更应优先考虑 half-domain + NG 的组合，而不是把 full-domain 当前的 superset traversal 下降当作默认方向。

## 31. 2026-06-02 用户原文记录与 single-point 确认

用户原文如下，后续实现和实验口径应以此作为约束：

> 1、full domain node join的时候，现在单侧做了半域裁剪，这个应该改成full domain的，但可以加注释说，一半的可以类似之前Tmid裁剪的那种把定义域干掉一般，可以帮助函数join 占优更快，从而保留更少的label。即一般的定义域下更容易占优（以你为不需要考虑另一半的函数长什么样）。但是如果用半域的话要和之前那种一样，要小心单点情况。  这个先检查现在考虑没考虑单点。先不改代码
> 2、记录，full domain的还是没太想清楚为啥会在dominance graph上的效率会高，但感觉上这个优势在之后使用ng的时候是可以优化的，即full domain的优势在ng下会弱化，而半域的更强的bound，更强的占优和更轻的扩展，感觉ng下可能会更好

复查当前 one-side node join 的半域裁剪后，结论是已经考虑了 `Tmid` 单点，但只考虑这个固定切分点。`insertForward()` / `insertBackward()` 在进入普通 `PaperDominanceGraph` 前会先调用 `isSinglePointFrontier()`；该函数要求 `frontier.head.start == frontier.tail.end == tMid`。满足时，forward 走 `insertForwardSinglePoint()`，backward 走 `insertBackwardSinglePoint()`，并分别通过 `FWTL/BWTL.dominatesSinglePoint(label.reachableSet, tMid, label.minReducedCost)` 查询普通 dominance graph 中的点支配值，同时在 `SinglePointStore` 里按 reachable-set 和 superset 关系做单点标签间的支配。

因此，如果 one-side node join 继续沿用当前这种 `Tmid` 半域裁剪，退化到 `Tmid` 的单点 label 不会直接丢进普通 graph，也不会继续扩展，而是有专门 store 和 graph point-dominance 保护。风险在于这套逻辑没有参数化边界点：如果后续为了 full-domain/half-domain 对照引入其他裁剪点、局部 horizon 边界，或出现非 `Tmid` 的零长度 frontier，那么当前 `isSinglePointFrontier()` 不会识别它，仍需另行泛化。

2026-06-02 追加确认 final join 口径。`GCBBStyleBidirectionalFullDomainNodeJoin.tryJoin()` 当前直接计算 `forward.preNodeFrontier.add(backward.frontier)`，没有像 Tmid arc-join 路径那样调用 `getForwardJoinExtension()` / `getBackwardJoinExtension()` 做常数延拓。因此 one-sided 模式如果保留了半域裁剪，被裁的一侧会按半域定义域参与 `add()`，join 前不会临时补回另一半定义域。这和“半域裁剪作为加速变体”的解释一致：它确实不再是严格 full-domain join，而是让函数比较和拼接都只看保留下来的半域。

## 32. 2026-06-02 node join 中半域函数直接 add 的语义

进一步复查后，node join 和 arc join 的函数拼接差异需要分开看。arc join 是 `forward prefix + crossing arc (i,r) + backward suffix`，crossing arc 里包含 `setup(i,r) + p_r` 这段时间偏移，所以 Tmid 半域版本必须先把 forward 右侧常数延拓，再做 `shiftX(delta)`，最后和 backward 左侧常数延拓后的函数相加。node join 则已经把 crossing job 物化进某一侧 label，forward 侧保存的是到同一个 join node 之前的 `preNodeFrontier`，也就是已经 shift 到该 node 完成时间坐标前的函数；因此 final join 不需要再额外做中间 `delta` 偏移。

当前代码中 `PiecewiseLinearFunction.add()` 允许两个输入函数的物理定义域不完全一致。它先取 `start = max(this.head.start, g.head.start)` 和 `end = min(this.tail.end, g.tail.end)`，只在两者公共区间上生成相加结果；如果公共区间为空，就返回空函数。因此 node join 的半域一侧可以和对向 full-domain 函数直接 `add()`，不会因为元数据定义域不同而报错。但这个直接相加的语义是“只在公共定义域上 join”，不是把半域函数补成 full-domain 后再 join。对向若是 `[0,H]`，半域一侧若是 `[Tmid,H]`，最终 joinCost 也只覆盖 `[Tmid,H]`；这正是当前 half-domain acceleration 变体的实际口径。

单点方面，底层 `add()` 对零长度 segment 有一定支持，但这里要精确理解：它不要求两侧都刚好在端点相交，也没有任何 `Tmid` 特判。若一侧是 `[x,x]` 单点，另一侧在 `x` 附近没有跳变、只有一个明确覆盖 `x` 的函数段，`add()` 会取 `start=max(...)=x`、`end=min(...)=x`，并用当前 segment 的 slope/intercept 生成一个 `[x,x]` 的零长度结果。因此“一个 `[0,H]` 函数 + 一个 `Tmid` 单点”只是普通覆盖点情形的一个例子。需要收紧的是内部跳变点：如果另一侧函数在同一个 `x` 左右两侧存在 vertical gap，`add()` 不会像 `evaluate()` 那样显式比较左右极限并取较优值，而是按链表扫描时当前指针指到的 segment 取 slope/intercept；当 `x` 恰好等于前一段 `end` 时，跳过条件是 `p.end < start`，不是 `p.end <= start`，所以会先用左侧段生成零长度结果。由此，`add()` 不能被视为对“跳变点单点相加”的严格数学支持，它只是在普通单点接触下避免结果变空。pricing label 层另有专门处理：`isSinglePointFrontier()` 只识别 `head.start == tail.end == tMid` 的单点，随后进入 `SinglePointStore` 并用 `dominatesSinglePoint(..., tMid, ...)` 和普通 dominance graph 做点支配检查。当前风险一方面是 label 层的 single-point 逻辑只参数化到了 `Tmid`，另一方面是若单点落在对向函数的内部跳变处，底层 `add()` 的点值口径也不够严格。

## 33. 2026-06-02 PWLF domain 字段的实际作用

`PiecewiseLinearFunction` 里的 `domainStart/domainEnd` 更接近函数对象的元数据边界，而不是所有操作都会强制遵守的硬约束。构造函数和 `copy()` 会保留这两个字段；`resetDomain()` 只改元数据，不改任何 segment；`addSegment()` 中按 domain 裁剪 segment 的旧逻辑已经注释掉，因此直接追加 segment 时不会自动裁到 `[domainStart, domainEnd]`。

真正用 domain 改变物理 segment 链表的是 `trimToDomain()`，以及调用它的入口。两参数 `setDomain(start,end)` 会先复制函数、临时把复制件的 domain 改成新窗口并执行 `trimToDomain()`，物理删除或截断窗口外 segment；随后又 `resetDomain(this.domainStart, this.domainEnd)` 把元数据恢复成原对象的 domain。因此它可能造成“物理链表已经被裁短，但 domainEnd 仍是原来的 T”的情况，这也是后来加 `debugCheckPWLFRightBound()` 的原因。`shiftX(delta)` 同样先复制并平移所有 segment，再按原对象复制来的 domain 调用 `trimToDomain()`，所以水平平移后越过边界的部分会被物理裁掉。

三参数 `setDomain(start,end,true)` 是另一套语义：它创建一个保留原 domain 的新函数，不删除窗口外定义域，而是把窗口外区间写成 `big_M` segment，用于 hard window / profitable window 这类“仍保持 `[a,T]` 结构，但窗口外不可行”的定价语义。若窗口和当前函数没有正长度交集，它也会用整段 `big_M` 表示不可行，而不是返回物理空域。

`add()` 不直接看 `domainStart/domainEnd` 来求公共区间，而是用两边真实 segment 链表的 `head.start/tail.end` 求交集；输出对象的元数据来自左操作数构造 `new PiecewiseLinearFunction(domainStart, domainEnd)`，但物理 segment 只覆盖两边交集。因此定义域元数据和物理覆盖区间可能不一致，后续真正参与 join/dominance 的通常还是 `head/tail` 的物理区间。

`dominates()`、`updateDominatedIntervals()`、`mergeMinimum()` 这类 dominance / envelope 操作也主要依赖物理 `head.start/tail.end` 和 segment overlap。`dominates()` 会先要求当前函数物理覆盖被比较函数的完整物理区间；`mergeMinimum()` 要求正长度 overlap，必要时复制右参数以免破坏输入，并在公共区间上合并 lower envelope。调试函数 `debugCheckPWLFRightBound/LeftBound` 则专门用来检查物理边界和 domain 元数据是否一致，帮助定位“元数据说还到 T，但真实 segment 已经被裁短”的问题。

## 34. 2026-06-02 completion bound two-cycle 与离散预筛分析

用户本轮原文如下，作为后续实现约束保留：

> 1、执行就好了
> 2、floor(L) ceil(P)应该不会出现你说的这种问题？对反向的bound函数，他其实是单调递增的，且左侧的定义域应该会到0？那对一个L，向下取整最小就是0了，如果更小的话也应该已经被砍掉了。对应的，对正向bound函数，单调递减，右侧定义域到T，那取上界应该也不会超过T了？如果超过的话此时其实已经被砍掉了
>
> 先分析这俩吧

### 34.1 two-cycle bound 的当前取舍

当前 `CompletionBoundCalculator` 中的 two-cycle relaxation 已经按 last-arc 维度维护二维函数状态：forward 侧是 `forwardU[prev][job]` / `forwardF[prev][job]`，backward 侧是 `backwardR[job][successor]` / `backwardB[job][successor]`。这保证传播阶段确实禁止立即二环，例如 forward 从 `(prevPrev, prev)` 扩展到 `job` 时会跳过 `job == prevPrev`，backward 对称地跳过 `prev == successor`。

但当前 `Bounds` 对外只暴露 `forwardUByJob[job]` 和 `backwardRByJob[job]`，因此 `buildTwoCycle()` 的最后一步会把二维状态逐一 merge 回一维 lower envelope。这个合并是安全的，因为取所有 last-arc 状态的最小值只会让 bound 更松，不会让剪枝误删；但它确实弱化了 two-cycle 的表达能力。更强的做法是，在正式 label 端知道相邻点时使用二维状态：例如反向 label 当前在 `i` 且后一个点是 `j`，前缀 completion bound 就不必使用最后一条弧为 `j -> i` 的状态；反向方向也可以做对称的“排除上一点/下一点”查询。这样比当前一维 envelope 稍强。

不过这条路当前优先级不高。实测 two-cycle 的构造时间已经明显高于 all-cycle，剪枝收益又接近 all-cycle；如果再把二维 bound 暴露给正式 label prune，需要新增状态查询、相邻点识别和 possibly best/second-best envelope 结构，复杂度继续上升。当前工程判断仍是：two-cycle 先保留为对照和论文说明，不作为默认路径；后续如果引入 NG-set labeling，再重新评估用 NG 状态做更强 completion bound 是否值得。

### 34.2 离散 completion bound 预筛的边界修正

当前 completion bound 接入 label prune 时，每个 label 都会做一次函数拼接：

```java
completion = label.frontier.add(suffixOrPrefix);
lowerBound = completion.findMinimal(false, true)[0];
```

这一步确实重，可以先加一个离散 scalar 预筛。forward label 在 job `i` 的最早完成时间为 `L` 时，后缀 completion bound 只可能在 `t >= L` 的时间上拼接；如果预先缓存 `RAfter[i][k] = min_{t >= k} R_i(t)`，就可以先用 `label.minReducedCost + RAfter[i][floor(L)]` 做一次便宜判断。若这个值已经不小于当前 cutoff，则完整函数 `add/findMinimal` 一定也不会产生可保留负列，可以直接剪掉。backward label 对称地用当前最晚完成时间 `P` 查 `UBefore[i][ceil(P)] = min_{t <= k} U_i(t)`，再和 `label.minReducedCost` 相加。

用户关于 `floor(L)` / `ceil(P)` 的修正是合理的，尤其在当前 completion bound 使用 full-domain penalty 的口径下更成立。`backwardRByJob[i]` 是 suffix-min 归一后的 backward bound，整体应非减；forward label 的 `L` 已经来自可行 label frontier，所以真正有意义的是 `L` 之后、且落在 `R_i` 物理定义域内的整数点。forward prefix bound 对称地经 prefix-min 后整体非增；backward label 的 `P` 只需要看 `P` 之前、且落在 `U_i` 物理定义域内的整数点。`allCycles` 和 `twoCycle` 只影响上游如何得到最终的一维 `U_i/R_i` 函数，离散缓存本身只读这两个最终函数数组。

实现口径因此改成：缓存数组先整体初始化为 `big_M`，只在函数 segment 实际覆盖的整数点上填有限值，不再对 head 左侧或 tail 右侧做常数外推。正常 full-domain completion bound 下，正向 `U_i` 的右端 `T` 和反向 `R_i` 的左端 `0` 应该存在；如果某个查询落在函数物理定义域之外，scalar 方法把它记为 `unavailable` 并回退到原来的 `add/findMinimal`，不直接据此剪枝。这个回退不是算法主体，而是防止函数物理段缺失、非整数 horizon 或未来改动导致误剪。

取整方向仍按“取更小的那一侧”理解。forward label 用 `floor(L)` 查 suffix，是把可拼接时间集合从 `t >= L` 放大为 `t >= floor(L)`，得到的 `RAfter` 不大于真实后缀最小值，因此更松但安全。backward label 用 `ceil(P)` 查 prefix，是把可拼接集合从 `t <= P` 放大为 `t <= ceil(P)`，得到的 `UBefore` 也不大于真实前缀最小值，同样更松但安全。这个预筛只能在 scalar bound 已经超过 cutoff 时避免重函数拼接；如果没有超过 cutoff，仍需走原来的函数 `add/findMinimal` 做精确判断。

## 35. 2026-06-02 full-domain arc join 离散 scalar 预筛实现

本次把上一节的离散预筛先接入 `GCBBStyleBidirectionalFullDomain`，也就是 full-domain crossing-arc join 对照类。共享 `CompletionBoundCalculator.Bounds` 现在在构造完 `forwardUByJob/backwardRByJob` 后额外生成两个整数时间缓存：`forwardUBeforeByJob[job][k]` 表示 `min_{t<=k} U_i(t)`，`backwardRAfterByJob[job][k]` 表示 `min_{t>=k} R_i(t)`。由于 `U_i` 已经 prefix-min 归一、`R_i` 已经 suffix-min 归一，缓存只需要基于最终一维函数数组构造，和前面使用 `allCycles` 还是 `twoCycle` 无关。

2026-06-02 追加修正：缓存构造不能逐整数点调用 `evaluate()`，否则每个点都可能从函数头重新扫描 segment，违背离散预筛的加速目的。当前实现改为一次遍历函数的 segment 链表：先把数组初始化为 `big_M`，再对每个 segment 计算它覆盖的整数区间 `[ceil(start), floor(end)]`，直接用 `segment.getValue(time)` 写入缓存；如果相邻 segment 在同一个整数断点都覆盖该点，则取两侧值的 `min`，保持和底层 `evaluate()` 在内部断点取较小值的口径一致。segment 没覆盖到的整数点保持 `big_M`，调用方会回退到原函数拼接，不据此剪枝。

正式 label prune 的接入口仍保持保守。Forward child label 先用 `label.minReducedCost + backwardRAfterFloor(job, label.frontier.head.start)` 做 scalar 判断；Backward child label 对称地用 `label.minReducedCost + forwardUBeforeCeil(job, label.frontier.tail.end)`。只有 scalar lower bound 已经不小于 cutoff 时才直接剪枝；如果缓存不可用、返回 `big_M`，或者 scalar 仍可能小于 cutoff，则回退到原来的函数 `add/findMinimal`。因此这次实现不会用离散值替代精确函数判断，只是在明显不可能生成负列时跳过重函数拼接。

日志中新增 `completionBoundScalar check/pruned/fallback/unavailable` 四个计数，用来回答 scalar 预筛是否触发。`check` 是尝试次数，`pruned` 是直接由 scalar 剪掉、没有进入函数拼接的次数，`fallback` 是 scalar 没有剪掉而继续走 `add/findMinimal` 的次数，`unavailable` 是缓存缺失或边界不可用导致回退的次数。`completionBoundFunctionEvaluations` 现在只统计真正进入函数拼接的次数。

本机对 `wet020_001_2m` 做 root-only smoke，模式为 `fullDomain-cb-allCycles`、`maxNodes=1`，结果 `obj=bound=6343, valid=true`。本轮日志显示 `completionBoundScalar check/pruned/fallback/unavailable=521/116/405/0`，说明 scalar 预筛确实命中，直接省掉了 116 次完整函数拼接；剩余 405 次继续走精确函数判断。该单例 exact pricing 时间为 `0.180s`，completion bound 构造约 `86.694ms`。由于历史对照日志来自不同临时运行和 JVM 状态，不能据此直接断言 wall time 更快；当前只能确认它减少了重函数评价次数，是否转化为稳定提速还需要在 20/21/22/25/30 等样例上做同口径批量对照。

按 segment 扫描版复跑同一 smoke 后，结果仍为 `obj=bound=6343, valid=true`，scalar 统计仍为 `521/116/405/0`。本次 exact pricing 为 `0.188s`，completion bound 构造约 `87.129ms`。这个小例子上构造时间没有明显下降，说明缓存构造本身不是主要瓶颈；但实现口径已经从“每个整数点重复 evaluate 扫函数”改为“每个 segment 只扫自己覆盖的整数点”，后续在更大 horizon 或更多段的样例上更合理。

最终边界口径再次收紧：`UBefore/RAfter` 不再对函数物理定义域之外的整数点做 head/tail 常数填充，也不把越界查询 clamp 到最后一格；这些位置保持或返回 `big_M`。`big_M` 的语义不是“缓存缺失后保守回退”，而是“这个离散可拼接集合没有可用 completion bound 点”，因此 scalar 入口统计为 `unavailable` 后直接剪枝。复跑 `wet020_001_2m` root-only `fullDomain-cb-allCycles`，结果仍为 `obj=bound=6343, valid=true`，日志为 `completionBoundScalar check/pruned/fallback/unavailable=521/116/405/0`，说明当前 full-domain completion bound 的 `0/T` 边界覆盖正常，边界修正没有改变该样例的剪枝命中。

对这个做法的安全性判断为：作为 sufficient scalar prune 是成立的。Forward label 查询 `RAfter[floor(L)]` 时，把真实可拼接集合 `t >= L` 放大到 `t >= floor(L)`，得到的是不强于真实后缀最小值的下界；Backward label 查询 `UBefore[ceil(P)]` 时，把真实可拼接集合 `t <= P` 放大到 `t <= ceil(P)`，也只会得到更松的前缀下界。只有这个松下界已经不小于 cutoff 时才剪枝，因此不会漏掉可能为负的完整列。`allCycles/twoCycle` 的差别已经被吸收到最终一维 `U_i/R_i` 函数里，离散缓存不依赖 relaxation 类型。

进一步修正后，full-domain scalar 入口已经不再对 `big_M` 回退，而是直接剪枝并同时计入 `pruned/unavailable`。这个语义和当前整数离散预筛保持一致：如果 forward label 查询的 `RAfter[floor(L)]` 没有 finite 值，表示 `L` 之后没有可用后缀补全点；如果 backward label 查询的 `UBefore[ceil(P)]` 没有 finite 值，表示 `P` 之前没有可用前缀补全点。后续若要处理非整数时间或非 full-domain completion penalty，需要重新定义这个 `big_M` 是否仍表示“不可能补全”，否则不能混用当前直接剪枝口径。

## 36. 2026-06-02 scalar 预计算开关对照测试

本轮测试的目的是确认离散 scalar 预筛在 completion bound 已经开启的基础上是否还能进一步节省时间。为避免对照口径不干净，先给 full-domain 对照路径增加了 `bidirectionalCompletionBoundScalarPruning` 开关：开启时构建并使用 `UBefore/RAfter` 整数缓存，关闭时不仅跳过 scalar 判断，也不再构建这两个缓存。`GCBBFullDomainComparisonTest` 同步支持 `twet.bpc.fullDomainCompare.completionBoundScalar=false`，并在 mode 后缀中标记 `scalarOff`。

root-only 对照均使用 `fullDomain-cb-allCycles`、`maxNodes=1`、FIFO completion bound 队列。`wet020/wet021` 中，scalar 开启后 `wet020` exact pricing 从 `0.182s` 降到 `0.149s`，`completionBoundFunctionEvaluations` 从 `521` 降到 `405`；`wet021` 两轮 exact 合计基本持平，函数拼接次数从 `424+41` 降到 `277+23`。这说明小样例上 scalar 可以稳定减少函数级 `add/findMinimal` 次数，但 wall time 只在部分样例上转化为明显收益。

`wet022/wet025/wet030` 的严格对照显示结构性收益更清楚。`wet022` 中 scalar 让函数拼接次数从 `427` 降到 `208`，并把 backward kept 从 `11` 降到 `0`，exact pricing 从 `0.445s` 降到 `0.199s`。`wet025` 中函数拼接次数从 `5729` 降到 `4357`，backward kept 从 `72` 降到 `37`，exact pricing 从 `0.335s` 降到 `0.149s`。`wet030` 是 node-limit 样例，四轮 pricing 中 scalar 的函数拼接总次数从 `74334` 降到 `71012`，但本次 exact 总时间为 `6.129s` 对 `4.973s`，没有表现为总时间收益；该样例里 completion bound DP 构造时间波动很大，且 scalar 改变后续生成列数量和 bound，不能只按总秒数判断。

当前结论为：scalar 预筛在统计上是有效的，确实能减少 completion bound 后续的重函数拼接，并且在 `wet022/wet025` 这种中等 case 上还会通过更早剪掉后缀 label 减少 join 工作；但它不是稳定的 wall-time 保证，特别是 `wet030` 这类多轮、node-limit 且 completion bound 构造时间占主导的 case，会被 DP 构造波动和列集差异掩盖。作为默认开启的轻量预筛是合理的，但若后续优化方向是大幅降时间，重点仍应放在 completion bound 构造阶段和减少多轮重复构造，而不是只继续打磨 scalar 查询本身。

### 36.1 wet030 差异复查与 unavailable 口径修正

复查 `wet030` 后，上面关于“列集差异”的解释需要修正。若 scalar 只是 sufficient prune，它不应该改变最终列池；第 1 轮 on/off 的 `addedColumns=363`、`candidatePool=363/689/326`、label 数、join 统计完全一致，说明正常 scalar 命中只是在跳过部分 completion-bound 函数拼接。差异从第 2 轮开始出现，而 scalar-on 每轮恰好有 `unavailable=1`，因此问题来自此前把 `UBefore/RAfter` 返回 `big_M` 当成“无可补全点”直接剪枝。

这个直接剪枝口径过强。当前 pricing 和 completion bound 函数仍是连续 PWLF，effective/profitable window 和 `Tmid` 也可能是非整数；离散整数缓存没有 finite 值，只能说明整数预筛不可用，不能证明完整函数拼接没有连续时间交集。因此 `unavailable` 必须回退原来的 `add/findMinimal`，这样 scalar 才保持“只做轻量预筛，不改变列池”的语义。

修正后复跑 `tmp-wet030_001_2m` root-only `fullDomain-cb-allCycles`，四轮列池与 scalar-off 对齐：`addedColumns` 为 `363/15/1/0`，pool 最终为 `5163`，bound 同为 `15261.833333`。每轮函数拼接次数仍下降：`51020->44290`、`12371->11132`、`10574->9360`、`10369->9170`，合计从 `74334` 降到 `63952`。`unavailable=1` 的轮次现在计入 fallback，不再改变剪枝结果。

离散数组构造本身不是这里的主要耗时。当前实现对每个 bound 函数扫一次 segment 链表，只填该 segment 覆盖到的整数点；在 `wet030` 约 30 个 job、horizon 约 1343 的规模下，数组量级只有几万格。日志里的 `completionBoundBuildNanos` 包含完整 relaxed DP 的 forward/backward fixed-point 构造，不是单独的离散 cache 构造时间；on/off 中 buildMs 的差异主要来自运行波动和 DP 构造本身，不能归因于整数数组。

### 36.2 unavailable 的真正原因

继续复查后，上面 36.1 中“unavailable 必须 fallback”的判断仍然不对。问题不在 `big_M` 语义本身，而在 `UBefore/RAfter` 离散缓存的构造没有实现名字里的前缀/后缀最小值。旧实现只是把函数 segment 覆盖到的整数点写入数组，没有把 `UBefore[k] = min_{t<=k} U_i(t)` 向右传播，也没有把 `RAfter[k] = min_{t>=k} R_i(t)` 向左传播。因此当查询点 `k` 自身没有被 segment 覆盖时，即使 `k` 左侧或右侧存在可用 completion bound 点，数组仍返回 `big_M`，形成假的 unavailable。

修正后，缓存构造流程为：先按 segment 覆盖的整数点填有限值，内部断点仍取较小值；随后 `UBefore` 做一次从左到右的 prefix-min 扫描，`RAfter` 做一次从右到左的 suffix-min 扫描。这样 `big_M` 才重新表示对应前缀或后缀集合没有 finite completion bound 点，可以直接剪枝，不需要 fallback。

用当前代码复跑 `tmp-wet030_001_2m` root-only `fullDomain-cb-allCycles` 后，scalar-on 四轮 exact pricing 的 `addedColumns` 为 `363/15/1/0`，pool 最终为 `5163`，bound 为 `15261.833333`；scalar-off 同口径复跑也是同样的列数、pool 和 bound。修正后的 scalar-on 每轮 `completionBoundScalar ... unavailable` 均为 `0`，函数拼接次数仍从 scalar-off 的 `51020/12371/10574/10369` 降到 `44290/11132/9360/9170`。由此当前结论改为：不需要 fallback；需要保证离散缓存真正表达 prefix/suffix min，之后 `big_M` 才可以作为不可补全的剪枝信号。

### 36.3 旧版本 unavailable 的实际触发点

按用户要求回到旧的 direct-prune 版本 `bec835b` 做临时诊断，真实触发点不是函数中间不连续或有洞，而是 backward scalar 查询 `forwardUBeforeCeil()` 时遇到非整数右端。三次 unavailable 全部为 `dir=BW, job=13, sinkRoot=false`，对应查询分别为 `queryTime=1337.6250000000005, index=1338`、`queryTime=1333.999999999996, index=1334`、`queryTime=1334.000000000002, index=1335`。这些 label 不是 sink root，但 backward label 的 `frontier.tail` 等于当前 `pricingHorizon`，因此会用 `ceil(P)` 查到 horizon 右侧的下一个整数格。

以第一条为例，`pricingHorizon=1337.6250000000005`、`maxDiscrete=1338`，对应 forward bound 的 `boundTail=1337.6250000000005`，旧数组在 `1338` 没有 segment 覆盖，返回 `big_M`；但诊断同时显示 `nearestFiniteLeft=1337:-33.37499999998363`。也就是说 `UBefore[1338]` 按定义应表示 `min_{t<=1338} U(t)`，显然应该继承左侧 finite 值，而旧实现把它当成了 `U(1338)` 点值。第三条还暴露了浮点边界：`queryTime=1334.000000000002`、`boundTail=1334.0`，由于 `ceil()` 变成 `1335`，同样会越过真实右端一格。

因此实际 bug 更窄：`forward U` 确实到达右侧 horizon，但 horizon 非整数或有浮点微小上偏时，`ceil(P)` 会查到 segment 物理右端外的整数格。当前 prefix-min 修正能覆盖这个问题；如果后续要进一步收窄实现，也可以只对 `UBefore` 的右端 rounded-up 格做有限值延拓，并对称考虑 `RAfter` 的左端 rounded-down 格。但从数组语义上讲，`UBefore/RAfter` 本来就是前缀/后缀集合最小值，做完整 prefix/suffix 扫描仍是和命名一致的实现。

### 36.4 按实际触发点收窄实现

根据 36.3 的诊断，最终实现不再对整张离散表做 prefix/suffix min 传播。`CompletionBoundCalculator` 的 `forwardUBeforeByJob/backwardRAfterByJob` 重新只记录函数物理定义域内部真实覆盖到的整数点；`ceil/floor` 前先用 `Math.rint()` 和 `Utility.compareEq()` 把 `1334.000000000002` 这类浮点微偏吸附到最近整数，避免边界上无意义地多跳一格。

针对真实 bug，只在 `GCBBStyleBidirectionalFullDomain` 的 backward scalar 中处理：如果 backward label 的 `frontier.tail` 与 `pricingHorizon` 数值相等，则不再查 `forwardUBeforeCeil(P)`，而是直接使用预计算的 `forward U_i` 全局最小值。这个 label 不一定是 `sinkRoot`，旧诊断中三次均为 `sinkRoot=false`；原因是 sink 侧扩出的普通 backward label 经过等待/后缀归一后，右端仍会贴到 `pricingHorizon`。source root 和 sink root 本身不会走 completion bound：forward root 因 `jid<=0` 被跳过，backward sink root 因 `label.isSinkRoot` 被跳过。

复跑 `tmp-wet030_001_2m` root-only `fullDomain-cb-allCycles` 后，scalar-on 四轮 exact pricing 仍为 `addedColumns=363/15/1/0`、pool `5163`、bound `15261.833333`，每轮 unavailable 均为 `0`；scalar-off 同口径复跑得到相同 pool 和 bound。scalar-on 的函数拼接次数为 `44290/11132/9355/9170`，仍明显低于 scalar-off，其中第 3 轮比完整 prefix/suffix 版本又少 5 次，来自浮点整数吸附后不再误判边界。

2026-06-02 追加复测：在当前收窄实现下再次跑 `tmp-wet030_001_2m` root-only，同一轮 scalar-on exact pricing 总时间为 `2.428s`，scalar-off 为 `2.745s`；两者均为 `addedColumns=363/15/1/0`、pool `5163`、bound `15261.833333`。scalar-on 每轮函数拼接次数为 `44290/11132/9355/9170`，scalar-off 为 `51020/12371/10574/10369`，也就是总计从 `74334` 降到 `63947`。这个结果说明当前 scalar 预筛在 wet030 上确实能略省 exact pricing 时间，但 wall time 仍受 completion-bound DP 构造和 JVM/CPLEX 波动影响，不能只按单次秒数做强结论。

2026-06-02 继续查历史日志：当前工作区没有找到 `tmp-wet030_001_2m` 的 full-domain `completionBound=off` 有效结果。`test-results/bpc/tmp-arc-cb-wet030-off/` 目录存在但为空，也没有对应 CSV。现存 wet030 full-domain 记录主要是 `allCycles`、`twoCycle`、`allCycles-scalarOff`；其中 `scalarOff` 只是关闭 scalar 预筛，completion bound 仍然开启，不能作为 completion-bound-off 对照。若需要 wet030 关闭 completion bound 的时间，需要重新跑。

随后实际尝试运行 `tmp-wet030_001_2m` root-only `fullDomain`、`completionBound=off`。该运行超过二十分钟仍未完成，期间 Java 主进程持续占用 CPU，终止前累计 CPU 约 `1498s`、内存约 `1.86GB`，没有写出完整 CSV/log 结果。由此可以确认 wet030 full-domain 关闭 completion bound 后不是几秒级对照，而是会在 exact pricing 的 label/join 阶段严重膨胀；当前不再等待完整 off 结果。

2026-06-02 继续扩大搜索后，更正上一段“没有历史有效结果”的表述：工作区确实没有 `wet030 completionBound=off` 的完整 CSV/log，但 2026-05-31 的专题第 12 节和全局修改记录已经写过一次未完成试跑。当时 `wet030_001` 在 10 分钟预算内完成了 `allCycles=9.037s` 和 `twoCycle=38.467s` 两个 root-only 对照，`off` 启动后一直未完成并被手动停止。因此历史结论不是“没有做过”，而是“做过但没有完整完成时间”。这与本次超过二十分钟仍未完成后终止的观察一致，说明 `wet030` full-domain 关闭 completion bound 的成本已经远超当前可交互验证范围。

2026-06-02 进一步按完整 BPC 流程验证 `tmp-wet030_001_2m`。这次给 `GCBBFullDomainComparisonTest` 增加了 `twet.bpc.fullDomainCompare.runALNSForSeed` 开关，默认仍关闭 ALNS；显式打开后，初始列为 `63`，其中 incumbent 列为 `2`，初始 incumbent 已为 `15325`。使用 `fullDomain`、`completionBound=allCycles`、FIFO 队列和当前 scalar 预筛，完整流程得到 `status=FINISHED`、`obj=15325`、`bound=15325`、`gap=0`、`valid=true`。总时间 `101.666s`，root 用时 `21.360s`，处理节点数 `13`，其中整数节点 `5`，分支调用 `6` 次，最终列池 `12267`。pricing 统计为总 `168` 轮，其中 heuristic pricing `135` 次、`49.436s`，full-domain exact pricing `33` 次、`44.216s`，master LP 合计 `3.981s`。输出位于 `test-results/bpc/tmp-wet030-full-alns-cb-allCycles-current.csv` 和同名日志目录。

这个结果把前面 root-only 的 gap 解释闭合了：`wet030` 不是 bound 算错或 root 列池质量差，而是对照 runner 关闭 ALNS 时初始上界太差，root-only 又不会额外解 restricted master integer 来刷新 incumbent。打开 ALNS 后，初始上界已经等于最终最优值；BPC 后续通过 13 个节点把下界推到同一值，证明了最优性。

2026-06-02 继续比较 root 节点中 BPC heuristic pricing 的作用。新增 `twet.bpc.fullDomainCompare.enableHeuristicPricing` 开关，默认仍保持开启；在 `runALNSForSeed=true`、`maxNodes=1`、`completionBound=allCycles`、full-domain exact pricing 不变的条件下，将 BPC 过程中的 Tabu heuristic pricing 关闭，只保留 ALNS 初始好上界和 exact pricing。该运行超过 5 分钟仍未完成 root 节点，终止前 Java 进程累计 CPU 约 `368s`、内存约 `1.58GB`，没有完整 CSV/log 输出。对照上一次完整流程中 root 用时 `21.360s` 且 heuristic pricing 在 root 内先快速补了大量列，可以判断 BPC heuristic pricing 的主要作用不是改善 incumbent，而是用较低成本把 RMP 列池扩到接近稳定区，避免 exact pricing 从很稀疏的初始列池直接承担大批量负列生成。

完整流程日志进一步给出了优化方向。33 次 full-domain exact pricing 的 `exact_s=44.216s` 中，completion bound 构造合计约 `26.773s`，label 扩展和 final join 的实测合计约 `17.119s`，两者基本解释了 exact pricing 总时间。completion-bound scalar 预筛本轮检查 `2,061,568` 次，只直接剪掉 `32,662` 次，剩余 `2,028,906` 次仍回退完整函数拼接；而 final join 侧尝试 pair 约 `24,296,056`，函数评价约 `11,251,099` 次，函数剪枝约 `11,240,384` 次。这说明当前 full-flow 的两个主要优化点分别是：减少每轮 completion bound 重建成本，以及在 branch 节点上进一步压低进入函数 add/findMinimal 的 join pair 数量。可考虑的低风险方向包括：复用每个 node 下的静态可用邻接表和分段整数缓存、在 full-domain branch 节点上增强 scalar lower bound 的覆盖率、把 heuristic pricing 的后期小批量补列策略做成更可控的批量/停止规则；更激进但需要证明安全性的方向是用当前候选堆或 incumbent 信息加强 join 阈值，或者在 branch 节点构造更紧的 completion bound。

当前 branching 顺序由 `TWETBPCContext` 固定为三类：先 `TariffSegmentBrancher`，再 `MachineCountBrancher`，最后 `ArcBrancher`。`Tree` 对每个未闭合节点按这个顺序尝试，某个 brancher 成功生成左右子节点后就停止，不再尝试后面的规则。节点对象 `Node` 记录轻量状态：`depth`、`pseudoCost`、机器数上下界 `minMachineCount/maxMachineCount`、继承的 seed/incumbent column ids、active cut ids、arc 状态矩阵、tariff segment 状态数组，以及 repair 所需的“本次新增分支行”标记。Tariff 分支在 segment 变量分数时生成 `z_s<=0` 与 `z_s>=1` 两个子节点，记录在 `tariffSegmentState`；机器数分支在总机器使用量分数时生成 `machineCount<=floor(value)` 与 `>=ceil(value)`，记录上下界；arc 分支选最接近 0.5 的分数弧，左子节点 forbids 该弧，右子节点 requires 该弧，并同时禁止同一真实前驱的其他后继和同一真实后继的其他前驱，以表达相邻关系的排他性。LP 构建时再把这些节点状态翻译成 master 约束，pricing 主要直接感知 forbidden arc，required arc 通过 master 行和 dual 间接影响。

## 37. 2026-06-02 无向相邻 pair 分支接入

根据新的 branching rule，当前先在机器数分支之后、directed arc 分支之前加入 `UndirectedAdjacencyBrancher`。该规则扫描真实 job pair `(i,j)`，计算当前 RMP 解里的 `q_ij=x_ij+x_ji`，在 `0<q_ij<1` 且分数时选最接近 `0.5` 的 pair。左子节点表示两个 job 不相邻，写入 `Node` 的 `adjacencyPairState=FORBIDDEN`；`Node.isArcForbidden(i,j)` 和 `isArcForbidden(j,i)` 会因此返回 true，所以 pricing graph 会同时删掉两条方向弧。LP 侧只建一条 `x_ij+x_ji=0` 分支行，不再拆成两条 directed arc 行。

右子节点表示两个 job 必须相邻但不固定方向，写入 `adjacencyPairState=REQUIRED`。LP 构建时增加 `sum_r (b_ij^r+b_ji^r) lambda_r >= 1`，并在 `readDuals()` 中把该行 dual 同时加到 `arcDual[i][j]` 和 `arcDual[j][i]`，从而让 pricing 在两条方向弧上都反映这条 branching constraint。pricing graph 本身不删弧、不固定方向，后续如果 `q_ij` 已为 1 但 `x_ij`、`x_ji` 仍有方向性分数，才会落到原有 `ArcBrancher` 继续做 directed arc 分支。

这次没有把段落中的 full strong branching 一并接入。原因是当前 `Brancher` 接口只拿到已经求解的 `LP`，没有 `PC`、pricing engine 或安全的 probe mode；如果直接在 brancher 内临时跑完整 price-and-cut，会污染全局列池和 trace，也难以限定为 heuristic-only。后续若要实现 strong branching，建议在 `Tree` 层增加专门的 probe evaluator，候选可以先按接近 0.5 截取少量 pair，再对左右子节点用受控的 restricted LP 或 heuristic pricing 估计 `LB_0/LB_1`，最后按 `max min(LB_0,LB_1)` 选择。

## 38. 2026-06-02 completion bound scalar 预筛覆盖补齐

此前离散 scalar 预筛只接在 `GCBBStyleBidirectionalFullDomain`，也就是 full-domain arc join 路径。半域 `GCNGBBStyleBidirectional` 和 full-domain `GCBBStyleBidirectionalFullDomainNodeJoin` 虽然已经使用 `CompletionBoundCalculator` 做函数 completion bound，但 label prune 仍然每次直接执行 `frontier.add(bound).findMinimal()`，没有先查 `backwardRAfterFloor` / `forwardUBeforeCeil` 的轻量下界。

本次把同一套预筛口径补到这两条路径。forward label 先用 `label.minReducedCost + backwardRAfterFloor(job, label.frontier.head.start)` 判断；backward label 先用 `label.minReducedCost + forwardUBeforeCeil(job, label.frontier.tail.end)` 判断。若 backward label 右端贴着 `pricingHorizon`，仍沿用 full-domain arc join 中已经定位过的边界处理，直接取 `forwardUMin(job)`，避免非整数 horizon 或浮点微偏导致 ceil 到右端外一格。只有 scalar 下界仍可能小于 cutoff 时，才回到原来的完整函数拼接。

三条主路径现在都通过 `config.bidirectionalCompletionBoundScalarPruning` 控制是否构造离散缓存和是否启用 scalar prune，并在统计中输出 `completionBoundScalar check/pruned/fallback/unavailable`。验证方面，focused `javac` 已通过：`GCNGBBStyleBidirectional`、`GCBBStyleBidirectionalFullDomainNodeJoin` 和 `CompletionBoundCalculator` 均编译成功。后续需要用 20/21/22/25/30 的同口径实验确认半域和 nodejoin 上的实际收益。

2026-06-02 追加验证半域 `tmp-wet030_001_2m` root-only。测试入口使用 `GCBBFullDomainComparisonTest` 的 `mode=halfDomain`，即正式半域 `GCNGBBStyleBidirectionalPricing`，参数为 `completionBound=allCycles`、`maxNodes=1`、FIFO completion-bound 队列，分别打开和关闭 `completionBoundScalar`。两边结果完全对齐：状态均为 `NODE_LIMIT`，bound 均为 `15261.833333`，exact call 均为 `4`，加列序列均为 `147/22/3/0`，最终 pool 均为 `4896`，验证结果均为 `valid=true`。这说明当前半域 scalar 预筛没有改变 root 节点列池和下界。

本轮 scalar-on 的总时间为 `solve=7.500s`、`exact=1.972s`；scalar-off 为 `solve=7.406s`、`exact=1.940s`。单次 wall time 上 off 略快约 `0.03s`，属于 JVM/CPLEX 和 completion-bound 构造波动范围，不能说明 scalar 在半域 wet030 上稳定提速。但统计上 scalar 的作用是明确的：`completionBoundFunctionEvaluations` 从 `49739/12436/10312/10368` 降到 `35707/8747/7476/7557`，合计从 `72855` 降到 `53487`；scalar check/pruned/fallback/unavailable 分别为 `49739/14032/35707/0`、`12436/3689/8747/0`、`10312/2836/7476/0`、`10368/2811/7557/0`。本轮 `unavailable=0`，没有触发离散边界缺失剪枝，实际只是在可证明下界足够高时跳过原函数拼接。当前结论为：半域路径的 scalar 接入正确，能减少函数拼接评价次数；在 wet030 root-only 上这次没有转化为稳定 wall-time 提速，主要因为 completion-bound DP 构造仍占较大比例且运行波动足以覆盖这部分收益。

同口径补跑 full-domain arc join 后，结果仍为 `NODE_LIMIT`、bound `15261.833333`、`valid=true`，但 full-domain 为 `solve=8.673s`、`exact=2.803s`，慢于半域 scalar-on 的 `solve=7.500s`、`exact=1.972s`。full-domain 的 exact 加列序列为 `363/15/1/0`，最终 pool `5163`；半域为 `147/22/3/0`，最终 pool `4896`。两者下界相同，但 full-domain 第一轮返回更多负列，导致后续 heuristic/exact 轮次略少，列池也更大。

从内部统计看，full-domain 本轮没有在 wet030 上占优。completion-bound 函数拼接评价 full-domain 合计为 `44290+11132+9355+9170=73947`，半域为 `35707+8747+7476+7557=53487`；join 函数评价 full-domain 合计约 `129562`，半域约 `24204`。completion-bound 构造时间 full-domain 约 `2.052s`，半域约 `1.600s`。因此这次 full-domain 慢不是偶然单点 join 阶段，而是全域标签保留和 crossing-arc join 候选显著放大，虽然会生成更多列，但 root 下界没有改善。当前 wet030 root-only 默认仍应优先看半域路径；full-domain arc join 主要保留为对照和诊断路径。

2026-06-02 追加原始 40-job root-only 测试。使用 `data/40-2/wet040_001_2m.dat`，半域 `GCNGBBStyleBidirectionalPricing`、`completionBound=allCycles`、scalar 开启、`maxNodes=1`。该算例完成 root 节点耗时明显放大：`status=NODE_LIMIT`、`obj=83938`、root bound `26155.750000`、gap `68.839%`，总 `solve=225.547s`，其中 heuristic pricing `18.715s/30` 次，exact pricing `205.178s/5` 次，master LP `1.419s`，最终 pool `10239`，验证为 `valid=true`。exact 加列序列为 `612/195/47/15/0`，说明最后一轮已经完成 exact pricing 证明无新负列；`NODE_LIMIT` 只是因为 `maxNodes=1` 不允许继续分支。

该 40-job 样例说明 completion bound 构造不是当前主要瓶颈。5 轮 exact pricing 的 completion-bound buildMs 约为 `2010/2004/1919/1961/2006ms`，合计约 `9.9s`，只占 `exact=205.178s` 的一小部分；真正重的是正式半域 label/dominance 和 final join。第一轮 label kept 已达 `fw=109192`、`bw=86813`，join pairs tried `77254669`，join function eval `53234863`；后续几轮仍保持千万级函数评价。scalar 预筛本轮命中规模也很大，第一轮 `check/pruned/fallback/unavailable=6286147/1700759/4585388/0`，但 fallback 仍有数百万次，说明单靠当前 completion-bound scalar 预筛无法把 40-job root 变成秒级。后续如果要推动 40-job，优化重点应转向进一步压缩 join pair、label table 和 dominance 扫描，而不是只继续降低 completion-bound DP 构造时间。

## 39. 2026-06-02 restricted master 整数启发式上界接入

前面 root 列池整数诊断已经说明，`wet030` root pricing 后列池本身能拼出接近根下界的整数解，但主 BPC 只在 LP 松弛解本身为整数时刷新 incumbent。为了把这个诊断正式接入流程，本次新增 `TWETBPC.LP.RestrictedMasterIntegerHeuristic`，在每个节点 `pc.solve(lp)` 已经完成、当前 restricted columns 已稳定后，单独复制当前节点列池和分支状态，重新建一个小型整数 restricted master。

这个整数模型不修改原 `LP` 对象，也不参与 reduced cost 或 dual 计算。列变量、外包变量和 tariff segment active 变量取整数，coverage 约束沿用当前 RMP 的 set-covering 语义 `>=1`，机器数上下界、directed arc 分支、无向相邻 pair 分支和 tariff segment 分支都复制到这个 MIP。得到的整数解只用于刷新启发式上界；如果当前 LP 松弛仍是分数解，即使整数启发式改善了 incumbent，也不能把节点按 `integer_incumbent` 关闭，后续仍要按 LP bound 做 incumbent 剪枝或继续分支。

配置上新增 `enableRestrictedMasterIntegerHeuristic=true` 和 `restrictedMasterIntegerHeuristicTimeLimitSeconds`。默认时间限制为 `0`，表示不限制，尽量求当前 restricted master integer 的最优解；如果后续大节点发现该 MIP 成本过高，可以把时间限制设为正数，此时仍可用找到的可行整数解刷新上界，但不再保证 restricted master integer 已最优。

验证方面，相关 `Basic/Common/HEU/Output/TWETBPC` 子集已通过 `javac`。`tmp-wet020_001_2m` 半域 root-only 仍为 `ROOT_PROCESSED, obj=bound=6343, valid=true`，该例 root LP 本身已整数，因此不会触发额外整数启发式。`tmp-wet030_001_2m` 半域 root-only 在 `completionBound=allCycles`、`maxNodes=1` 下触发了新组件：初始 incumbent 为 `46152`，root LP bound 为 `15261.833333` 且不是整数节点；restricted MIP 用 `0.854s` 得到 `obj=15325`、选中 2 条列，并把 incumbent 刷新到 `15325`。最终状态仍是 `NODE_LIMIT`，因为 `maxNodes=1` 不允许继续分支；验证结果 `valid=true`，gap 约 `0.4122%`。这正好闭合了此前 root-only 大 gap 的问题：列池里已有好整数列组合，只是原主流程没有在 fractional root 后额外求一次整数 restricted master。
### 39.1 为什么 restricted integer 启发式单独复制 MIP

当前实现选择单独复制一个 restricted master integer MIP，而不是直接把原 `LP` 对象里的变量临时改成整数。这个选择主要是为了隔离语义和避免污染后续 pricing。原 `LP` 模型是当前节点的 LP relaxation，后续还要保留它的 LP 解、dual 和 reduced cost 口径，用于分支、列生成和 trace。MIP 解没有同样意义的 dual，如果在原 CPLEX 模型上临时改变量类型再改回来，容易让后续 LP 状态、basis/dual 或变量类型语义变得不清楚。

因此 restricted integer 组件只复制当前节点已经稳定的列池和分支状态，单独求一个整数 RMP，用得到的整数解刷新启发式上界；它不参与 reduced cost，不改变原 LP 的 dual，也不会因为找到整数上界就把 fractional node 直接关闭。后续若该 MIP 建模时间成为瓶颈，可以考虑缓存覆盖矩阵、列到弧/相邻 pair 的映射或 tariff segment 辅助结构，但仍建议保持独立 CPLEX model，而不是直接修改原 LP relaxation 模型。

### 39.2 restricted integer 上界正确性复核

本组件的无条件正确部分是流程隔离：它在 `pc.solve(lp)` 完成后读取当前 node 的 restricted columns 和分支状态，单独建 MIP，只用 MIP 结果刷新 incumbent，不读取 MIP dual，也不修改原 LP relaxation。因此它不会污染后续 pricing 的 dual/reduced-cost 口径，也不会改变当前 node 的 LP lower bound。当前实现也只在原 LP 解本身整数时关闭 node；restricted integer 找到上界时，fractional node 仍然按 LP bound 继续剪枝或分支，这一点是正确的。

作为“原问题可行上界”的正确性依赖两个建模假设。第一，当前列池中的每条 `TWETColumn` 本身必须是可行机器序列，且 node 上的机器数、directed arc、无向相邻 pair、tariff segment 分支约束已经被复制到 restricted integer MIP；当前实现已经覆盖这些约束。第二，coverage 继续使用 RMP 的 `>=1` set-covering 语义，因此严格上界语义依赖当前三角不等式/非负成本假设：最优整数解不会保留重复覆盖，或者重复覆盖可以不增成本地化简为不重复覆盖的排程。若后续进入不满足该性质的数据、负成本列、或某些分支约束让“删除重复访问”不再保持可行，则需要把该启发式改成 `==1` 或在接受 incumbent 前做重复覆盖校验/修复。当前 `wet030` 验证中 selected columns 无重复覆盖，validator 也通过，因此现有 no-outsourcing/TWET 实验口径下该上界可接受。

active cuts 当前没有复制进这个 MIP。只要 cuts 是原问题有效不等式，不复制 cuts 不会破坏原问题上界的可行性；它只可能让 restricted integer 解违反某些 LP 强化行。但如果未来引入的 cut 带有非全局有效或类似分支的语义，就必须同步复制，否则该启发式上界可能与当前 node 语义不一致。

## 40. 2026-06-03 completion bound arc fixing 诊断与半域接入

本次尝试利用每轮 pricing 已经构造的 completion bound 做一个本地 arc fixing。这里不能直接写入 `Node.forbidArc()`，因为 completion bound 使用当前 LP dual 构造，dual 在下一轮加列后会变化；如果把这类结论固化到 node 上，就会把某一轮 reduced-cost 口径下的判断错误带到后续轮次。因此当前实现只在 `GCNGBBStyleBidirectional.solve()` 内维护本轮局部的 `completionBoundFixedArc[from][to]`，且只处理真实 job 到真实 job 的 arc，不处理 source/sink arc。

判据使用的是 completion bound 的 relaxed lower bound。`CompletionBoundCalculator` 额外暴露 `forwardFByJob` 和 `backwardBByJob`：前者表示完整到达 job `i` 的 relaxed prefix，后者表示完整从 job `j` 出发到 sink 的 relaxed suffix。对 arc `(i,j)`，构造

`forwardF_i` 平移 `setup(i,j)+p_j` 后，加上 `backwardB_j`，再加固定项 `setupCost(i,j)-arcDual(i,j)`。

如果这个函数没有公共定义域，说明在该 relaxed completion 口径下也没有可拼接路径；如果其最小值不小于 pricing cutoff，则任何真实 elementary 列使用该 arc 都不可能成为负 reduced-cost 列。由于 relaxed bound 只会比真实 elementary completion 更乐观，这个 sufficient condition 用于剪掉负列搜索是安全的。当前开关分成两层：`bidirectionalCompletionBoundArcFixingDiagnostic` 只统计潜力，不改变扩展；`bidirectionalCompletionBoundArcFixing` 会把判掉的 job-job arc 写入本轮局部矩阵，并让 forward/backward 扩展和 final crossing join 跳过它们。

验证使用 `tmp-wet030_001_2m` 半域 root-only，`completionBound=allCycles`。只开诊断时，每轮 870 条候选 job-job arc 中，分别可判掉 `685/744/753/752` 条，其中时间域无交集为 `9/8/8/8` 条；诊断扫描本身约 `4.933/18.008/2.574/2.945 ms`。诊断不改变结果，bound 仍为 `15261.833333`、最终 pool 仍为 `4896`。

实际开启本地 arc fixing 后，结果仍与关闭时对齐：`status=NODE_LIMIT`、bound `15261.833333`、加列序列仍为 `147/22/3/0`、最终 pool `4896`、验证 `valid=true`。内部统计显示效果主要体现在减少后续函数拼接。关闭 arc fixing 的即时对照中，四轮 join function eval 为 `17425/3203/1822/1754`，开启后降为 `4563/755/450/424`；completion-bound function fallback 从 `35707/8747/7476/7557` 降为 `9531/1788/1465/1494`。本轮 wall time 受运行波动影响较大：开启后 `solve=15.767s`、`exact=3.485s`，关闭对照为 `solve=18.214s`、`exact=5.568s`，历史同口径关闭诊断曾为 `solve=7.500s`、`exact=1.972s`。因此当前更可靠的结论是：arc fixing 语义上没有改变 root 结果，并显著压低函数评价数量；真实 wall-time 收益需要更多 seed/case 复测。

后续如果要把它作为默认优化，还需要继续做三件事：一是扩展到 full-domain arc join 和 nodejoin 路径，并确认三条路径的列池不变；二是在 40-job root-only 上检查是否能压低千万级 join function eval；三是考虑把 arc-use lower bound 的函数最小化再做离散 scalar 化，避免每轮 `n(n-1)` 条 arc 都执行一次完整 `shift/add/findMinimal`。

2026-06-03 继续收紧 arc fixing 扫描。第一，时间域无交集不再等到 `shiftX/add` 后才发现，而是在扫描时先判断 `prefix.head.start + delta > suffix.tail.end`，满足时说明最早可到达 `j` 的时间已经晚于 suffix 的最晚可用时间，因此必然无法拼接。这里不能使用 `prefix.tail.end + delta < suffix.head.start` 做对称剪枝，因为 forward-normalized prefix 是 prefix-min 语义，右侧可以按水平常数延拓理解；物理 tail 早于 suffix head 不代表不能等待到后面再接。第二，给 `forwardFByJob/backwardBByJob` 各自预先计算函数最小值，先用 `min(forwardF_i)+min(backwardB_j)+setupCost(i,j)-arcDual(i,j)` 做弱 scalar 下界；若这个下界已经不小于 cutoff，则直接本地固定 arc，否则再回到完整函数拼接。

复跑 `tmp-wet030_001_2m` 半域 root-only，开启 arc fixing 后结果仍保持不变：`status=NODE_LIMIT`、bound `15261.833333`、加列序列 `147/22/3/0`、pool `4896`、`valid=true`。统计上，弱 scalar 每轮只多剪 `6` 条 arc，`funcEval` 从上一版约 `861/862/862/862` 小幅降到 `855/856/856/856`；时间无交集仍为 `9/8/8/8`。这说明 `minF+minB` 下界非常乐观，只能作为低成本前置筛，主要剪枝能力仍来自完整函数级 arc-use lower bound。

随后复核发现，上段中的时间域预判最初写成了两个方向：`prefix.head.start + delta > suffix.tail.end` 或 `prefix.tail.end + delta < suffix.head.start`。第二个条件不应使用。forward completion bound 是 prefix-min 语义，物理 tail 早于 suffix head 只说明当前函数段没有覆盖到那么晚，并不代表不能等待后再接；按论文 join 语义，forward 右侧可以做常数延拓。因此代码已删掉 `prefix.tail.end + delta < suffix.head.start`，只保留“最早到达 j 已晚于 suffix 最晚时间”的安全条件。复跑 `tmp-wet030_001_2m` 半域 root-only，结果仍为 bound `15261.833333`、加列序列 `147/22/3/0`、pool `4896`、`valid=true`；domain 计数仍为 `9/8/8/8`，说明该算例触发的 domain prune 来自保留的安全条件。

2026-06-03 继续对照 arc fixing on/off。`tmp-wet030_001_2m` 半域 root-only、`completionBound=allCycles` 下，两边结果完全一致：bound 均为 `15261.833333`，加列序列均为 `147/22/3/0`，最终 pool 均为 `4896`，验证均为 `valid=true`。这次并行运行的 wall time 不适合做严格比较，off 为 `solve=10.602s/exact=2.795s`，on 为 `solve=10.357s/exact=2.755s`；更可靠的是内部计数。

从内部统计看，arc fixing 对“最终保留下来的 label 数”几乎没有影响。四轮 exact pricing 的 labels kept/dominated 在 on/off 下完全相同：forward 为 `980/321, 344/71, 272/51, 273/52`，backward 为 `1092/156, 214/12, 166/13, 166/13`。这说明当前 arc fixing 剪掉的 child 大多本来也会在 completion-bound label prune 阶段被剪掉，不会进入 dominance table。它的主要收益是把这些 child 的重函数拼接提前省掉，并显著缩小 final join 候选。

具体数字为：completion-bound function eval 从 off 的 `35707/8747/7476/7557` 降到 on 的 `9531/1788/1465/1494`；join candidates visited 从 `30874/4260/2170/2093` 降到 `7764/924/499/472`；join pairs tried 从 `30141/4114/2072/2004` 降到 `7599/899/487/460`；join function eval 从 `17425/3203/1822/1754` 降到 `4563/755/450/424`。每轮扫描的 job-job arc 候选为 `870` 条，当前轮本地固定 `685/744/753/752` 条，其中 domain 为 `9/8/8/8`，弱 scalar 为 `6/6/6/6`，其余主要来自完整函数级 arc-use lower bound。

关于“临时删弧”和“永久 arc elimination”需要分清两层。第一层是当前实现：在当前 node 的当前 pricing 轮，使用当前 LP dual 构造 completion bound，如果某 arc 的 relaxed arc-use lower bound 已经不小于 `0`，则这一轮 pricing 中该 arc 不可能出现在负 reduced-cost 列里，可以临时从扩展和 join 中跳过。这个判断依赖当前 dual，加列后 dual 变化，因此只能每轮重算，不能写入 `Node` 的永久 forbidden arc。

第二层是经典 reduced-cost fixing / arc fixing，参考 `C:\Users\Changxin\Downloads\GPT 技术笔记\arc fixing和列枚举.pdf` 中的判据：node 的 LP relaxation 已经列生成到最优后，若某变量或某 arc 对应的任意列的 reduced-cost lower bound 不小于 `UB - LB_node`，则该变量/arc 不可能出现在任何能改进 incumbent 的整数解中，可以在该 node 的整个子树内永久删除。对 arc `(i,j)`，标准做法是用 complete forward labeling 和 complete backward labeling 得到包含该 arc 的 path-reduced-cost 下界，再与 gap `UB - LB_node` 比较。

用户提出的判断可以保留如下原意：`2.1、在当前node上，只要满足arc i,j上的bound拼接以后比0还大，那么说明这一轮pricing的时候这些arc都可以删掉了。但这个删除只能是在这一轮。这个东西感觉如果做ng和DSSR的时候可以进一步强化一下？即DSSR的后几轮用的bound函数不变，但不再和0比较，而是和前几轮的最优解比较就好了。2.2、也可以做类似那种arc elimination的经典操作，那种操作的是说，一个node上的列生成求解到最优以后，此时可以再做一次正向的labeling和反向的labeling，都是完整的，然后看这种arc左右的最小成本bound是不是已经超过了当前的UB-LB，超过了的话相应子节点上的对应arc就可以干掉了。`

对这段的补充判断是：2.1 中“和 0 比较”适用于当前 exact pricing 需要证明没有负 reduced-cost 列的场景；如果 DSSR/ng-route 某一阶段只是在寻找比当前阶段 record 更好的 relaxed column，可以用更强的 record threshold 做搜索剪枝，但如果最终目标仍是证明 elementary pricing 没有负列，最后一轮 exact/elementary 口径仍必须回到 `0`。2.2 的方向是正确的，区别只是我们当前不想再额外跑一轮完整 forward/backward labeling；可以尝试用 node 最后一轮 completion bound 代替 complete labeling 来做保守的 subtree arc elimination。由于 completion bound 是 relaxation，它给出的 arc-use lower bound 更低、更乐观，因此满足 `lowerBound >= UB - LB_node` 时仍然安全，只是能永久删除的 arc 数可能少于真正 complete elementary labeling。

推荐实现顺序为：先保留当前每轮临时 arc fixing，继续在 30/40 job root-only 上确认列池和 bound 不变；随后在 node LP 完成且 restricted integer heuristic 已刷新 UB 后，增加一个只诊断的 subtree arc elimination 统计，用最后一轮 completion bound 计算每条 arc 的 lower bound，并与 `UB - LB_node` 比较，先不写入子节点；确认统计稳定且不误伤后，再把通过判据的 arc 写入 child node 的继承 forbidden set。最后再考虑 DSSR/ng-route 场景中的 record threshold 剪枝，因为那会涉及“当前阶段搜索目标”和“最终 exact certificate”两个口径，风险高于前两步。

另一个值得记录的直觉是：对当前软时间窗 TWET 问题，双向 pricing 理论上应比单向更有优势。软时间窗很弱时，单向 label setting 很难只靠时间窗快速剪枝，label 数可能接近指数爆炸；meet-in-the-middle 把路径拆成 prefix/suffix，再用 completion bound、arc fixing 和 final join lower bound 过滤，能在结构上减少单侧深度，因此更可能显著降低 label 和 join 的组合规模。

## 41. 2026-06-03 node 后继子树 arc elimination 尝试

本次把上一节的“永久 arc elimination”先做成独立组件 `CompletionBoundSubtreeArcEliminator`，并挂到 `Tree` 的 node 后处理位置。调用时机是：当前 node 的 LP relaxation 已经通过 `pc.solve(lp)` 完成列生成，且 restricted master integer heuristic 已经尝试刷新 incumbent；如果该 node 没有被 incumbent 直接剪掉、后续准备分支，则用当前 LP dual 构造一套 hard-window completion bound，并对每条真实 job-job arc 计算 relaxed arc-use reduced-cost lower bound。若该 lower bound 不小于 `incumbent - nodeLPBound`，则该 arc 不可能出现在任何能改进当前上界的后继整数解中。

这里刻意没有复用 root pricing 中的 pi-window 和 zero-dual 排除。原因是永久 arc elimination 要继承到子节点，而子节点 dual 会变化；pi-window 本来是为了当前负列搜索收紧窗口，比较阈值也是 0。永久判据比较的是 `UB-LB_node`，若继续使用当前 dual 下的窄窗口，可能会漏掉正 reduced-cost 但仍小于 gap 的列。因此当前实现用完整 hard window `[0,CmaxH]` 构造 completion bound，牺牲一些强度来保证保守性。实现开关分两层：`bidirectionalCompletionBoundSubtreeArcEliminationDiagnostic` 只统计潜力；`bidirectionalCompletionBoundSubtreeArcElimination` 才会把判掉的 arc 写入分支产生的 child node。写入时还跳过 directed required arc 和 required adjacency pair，避免和右分支的相邻强制约束冲突。

`tmp-wet030_001_2m` 半域 root-only 诊断结果显示，root 的 gap 为 `63.166667`，870 条 job-job arc 中可用该判据判掉 `701` 条。诊断总耗时约 `0.810s`，其中 hard-window completion bound 构造 `0.804s`，真正扫描 arc 只有 `6.4ms`。同一轮 restricted integer heuristic 用 `1.527s` 得到 `obj=15325`、选中 2 条列并刷新 incumbent；这说明整数列池启发式不是零成本组件，在 wet030 root 上约为 1 到 1.5 秒级，需要继续记录耗时。

随后做了一个小规模正式继承试验：`maxNodes=3`、开启 subtree arc elimination。root 判掉 `701/870` 条并写入两个 child；Node 2 只剩 `167` 条候选 arc，subtree elimination 又判掉 `13` 条，构造耗时约 `0.140s`。但该运行整体为 `solve=70.338s`、exact pricing `48.302s`，Node 3 出现两轮很重的 exact pricing，forward labels kept 达到 `114201/112169`，completion-bound eval 达到 `480970/472550`。这次不能据此说永久删弧导致变慢，因为未继承的 `maxNodes=3` 对照也在同一节点附近长时间未完成并被终止；更合理的结论是：`maxNodes=3` 会进入一个本身很难的子节点，不适合作为快速评估永久删弧收益的对照。

当前结论是：子树 arc elimination 的数学判据和代码挂点已经打通，root 诊断显示它能判掉大量 arc，扫描本身很便宜；但正式继承写入 child 后是否能加速，还没有得到正向证据。下一步更稳的做法是默认保持正式开关关闭，只保留诊断；在完整 BPC 流程中记录每个 node 的 `gap/candidates/fixed/time`，再挑同一个具体 child 做 on/off 受控复现。若后续发现大量禁弧会让 required adjacency 或 repair 更困难，还可以只把永久 elimination 用于“无强制相邻约束的分支侧”，或先只在 child 的 pricing-local 层使用，不写入 master forbidden arc 行。

### 41.1 2026-06-03 子树 arc elimination 后续修正

本轮先修正一个分支候选问题：无向相邻 pair 分支不应在后续已经永久删除的 arc 上继续选择候选。directed `ArcBrancher` 原本已经跳过 `node.isArcForbidden(i,j)`；问题只在 `UndirectedAdjacencyBrancher` 之前只在两个方向都 forbidden 时才跳过 pair。现在改为任一方向 forbidden 就不选这个无向 pair，剩余的方向性小数流交给 directed arc 分支处理。这样可以避免一边已经由 subtree elimination 永久删除，另一边又被 pair required 分支强制“二选一相邻”造成语义别扭。

`CompletionBoundSubtreeArcEliminator` 中构造 hard-window completion penalty 时也去掉了一次冗余裁剪。`data.penaltyFunction[job]` 在 `Data` 初始化阶段已经经过 hard window `setDomain(...)`，并且 `CompletionBoundCalculator` 在减 job dual 前会复制传入的 penalty，因此 subtree elimination 这里直接复用原函数引用即可，不需要再对每个 job 做 `cropToInterval(...,0,CmaxH)`。这个调整不改变 bound 语义，只减少一次函数复制和 segment 扫描。

关于“复用最后一轮 completion bound”，当前采用保守实现。半域 `GCNGBBStyleBidirectional` 只有在 `dualProfitableWindowEnabled=false`、`pricingHorizon=CmaxH`、`zeroDualExcludedJobs=null` 且本轮确实构造了 completion bound 时，才把 bounds 作为可复用快照暴露给 pricing engine。`PC` 只在最后一次 pricing 没有加列时保留这个快照；一旦加列重解 LP 或新增 cut，就清空，因为 LP dual 已经变化。`Tree` 做 subtree elimination 时优先传入这个快照；如果没有快照，例如 root no-cut 使用了 pi-window/zero-dual，则仍按 hard-window 口径重新构造。这样能在非 root 或有 branch/cut 导致 pi-window 不启用的节点上省掉一次 completion-bound DP 构造，同时不会把 root 的窄窗口 bound 错用于永久删除。

restricted master integer heuristic 暂时不改模型，只记录后续优化方向。当前 wet030 root 上该 MIP 大约是 1 秒级组件，后续如果发现大节点耗时过高，应先在 trace 中拆分模型构造时间和 CPLEX 求解时间，并记录 restricted column 数、实际进入 MIP 的列数、选中列数和 time limit 状态。再进一步才考虑限制列数，例如只保留当前 active 列和 reduced cost 较好的列；这需要验证不会明显削弱上界质量。

`forward labels kept` 很高而 `backward labels kept=0` 的节点还需要单独诊断。当前更可能的原因是该分支节点在 `staticOutsourcingOnly` 口径下 horizon 和 `Tmid` 让 backward 半域从 sink 侧几乎长不出非支配标签，导致 exact pricing 退化成偏单向的 forward 扩展。后续应针对该具体 node 记录 depth、branch 状态、permanent forbidden arc 数、`pricingHorizon/Tmid`、backward sink root reachable size，以及 backward 首轮扩展失败原因分布，而不是只看最终 kept 计数。

2026-06-03 继续复核 `tmp-halfdomain-cb-subtree-elim-on-n3-wet030-current` 中的异常 node。日志显示 root 在完成 4 轮 pricing 后做了 subtree arc elimination，`870` 条 job-job arc 中固定 `701` 条，然后先由 `UndirectedAdjacencyBrancher` 在 pair `(1,24)` 上分支。Node 2 和 Node 3 都是 depth=1 sibling，但表现差异很大：Node 2 两轮 exact pricing 的 forward kept 为 `1335/1253`，Node 3 为 `114201/112169`；两边 backward kept 都是 `0`，且 `bwPruned=30`。这说明 backward 不是没有初始化，也不是 local arc fixing 删除了 sink 弧，而是从 sink root 出发的 30 个第一层 backward child 都在 `isBackwardCompletionBoundPruned()` 前置剪掉，未进入 backward dominance table。

Node 3 真正耗时集中在 forward label 和 dominance。第一轮 `completionBound eval=480970`、`fwPruned=340274`，但仍保留 `114201` 个 forward label；`PaperDominanceGraph` 的 `subset visited` 达到 `28590416`。因此“backward kept=0”只是让 final join 统计全部为 0，不能解释 forward 为什么变大；forward 爆炸来自该分支侧的 LP dual/required adjacency 约束和剩余可用 arc 组合，使大量 forward prefix 的 completion lower bound 仍小于 0，必须保留下来继续扩展或尝试 forward->sink 收尾。与 Node 2 对照可见，两个 sibling 在同样 `bwPruned=30` 的情况下，Node 2 只有约 1.3k forward label，说明关键差异是分支侧语义和 dual，而不是 backward 半域本身。

和之前 full-domain 最优求解日志也不能直接类比。`tmp-wet030-full-alns-cb-allCycles-current` 里的 Node 3 使用 `GCBBStyleBidirectionalFullDomainPricing`，当轮 forward kept `2796`、backward kept `618`，并且当时 branching 走的是 directed arc 路径，不是当前的半域 GCNGBB、无向相邻 pair 分支和 subtree elimination 组合。因此这个 114k 节点更像是新组合下某个 required-adjacency 子节点的局部退化。下一步如果要继续优化，应先加 node 诊断字段：当前 node 的 required/forbidden adjacency 状态、required arc 状态、继承的 permanent forbidden arc 数、backward sink root reachable size、backward first-layer prune 原因，以及 top arc dual/branch dual。只有定位是哪条 required pair 或 dual 让 forward bound 放松，才适合动算法。

本轮还清理了两个实现冗余。`CompletionBoundSubtreeArcEliminator.evaluate(lp, incumbent, lb)` 三参数入口不再重复做一遍开关、gap 和 relaxation 校验，统一交给四参数入口处理；`PC.generateColumnsFromEngine()` 也不再因为某个后续 pricing engine “无改进但没有可复用 bounds”就清空前一个 exact engine 已经留下的 hard-window completion bound。后者是一个很小但真实的缓存传递问题：如果 exact engine 已经给出可复用 bounds，后面又跑了一个不产生 bounds 的 no-improvement engine，原写法会把可复用快照误清掉，导致 subtree elimination 只能重算。修正后只有拿到新的非空 bounds 才覆盖，实际加列、cut 或 LP 重解时仍然清空。

2026-06-03 追加 directed-only 分支对照。为了判断异常是否由无向相邻 pair 分支导致，新增 `enableUndirectedAdjacencyBranching` 开关，默认保持开启；在 `GCBBFullDomainComparisonTest` 中用 `twet.bpc.fullDomainCompare.enableUndirectedAdjacencyBranching=false` 关闭该分支，让 root 后直接落到 directed `ArcBrancher`。测试口径为 `tmp-wet030_001_2m`、half-domain、`completionBound=allCycles`、本轮 arc fixing 和 subtree arc elimination 开启、`maxNodes=3`。

结果显示关闭无向 pair 分支并没有消除异常，反而更重。root 仍然固定 `701/870` 条 subtree arc，随后 `ArcBrancher` 在 `(3,4)` 上分支。Node 2 第一轮 exact pricing 已经达到 `fw kept/dominated=121210/77343`、`bw kept=0`、`completionBound eval=432867`，并生成 `69952` 条负列；Node 3 第一轮进一步达到 `fw kept/dominated=405631/305613`、`bw kept=2`、`completionBound eval=1310329`，候选堆打满 `100000`，本轮耗时约 `195.735s`。整个 `maxNodes=3` 运行 `solve=274.129s`、`exact=244.391s/11`，比有无向 pair 分支的 70 秒级对照更慢。当前结论是：异常不是无向相邻 required 分支独有，directed arc 分支同样可能让半域 pricing 在 branch node 上退化为大量 forward-only 负列枚举。

这里也澄清一个容易误解的语义：`bwPruned=30` 不是“所有可能列都已经超过当前上界”。`isBackwardCompletionBoundPruned()` 比较的是当前 reduced-cost pricing cutoff，即这个 backward suffix 加上 relaxed prefix 后是否还能构成负 reduced-cost 列；它不是和 incumbent gap 比。更重要的是，半域实现里完整列有两类生成方式：一类是 crossing-arc 的 `forward prefix + backward suffix`，另一类是完全由 forward label 通过 `forward->sink` 收尾生成。`bwPruned=30` 只能说明没有有用的真实 job backward suffix 进入 backward table，不能剪掉完全落在 forward half-domain 内、直接接 sink 的负列。directed-only 对照中 Node 2 第一轮生成 `69952` 条负列、Node 3 第一轮候选堆打满 `100000`，正说明这些 surviving forward labels 不是“无效标签”，其中大量能直接收尾成负 reduced-cost 列。

由此下一步优化方向应从“是否使用无向 pair 分支”转向“branch node 中 forward-only 负列枚举过多”。当前 exact pricing 的职责是返回最多 K 条负列，不是枚举所有负列；但 forward 扩展阶段仍需要先生成大量 label 才知道哪些能收尾进 K 堆。可以考虑的低风险方向包括：在 forward->sink 候选堆已有足够多负列时，用当前 K 堆最差 reduced cost 作为局部 record bound 做更强的 forward completion prune，但这只能用于“本轮最多返回 K 条负列”的加速，不能用于 exact certificate；或者在 branch node 的 repair/price 阶段限制单轮返回列数，避免一次 directed required/forbid 分支把列池从 4896 放大到 7 万、17 万。若要保持 exact certificate 语义，则最后一轮仍必须回到 cutoff=0，把所有可能负列排除干净。

2026-06-03 再次对照“之前 wet030 很快求到最优”的日志后，确认前后不是同一个实验口径。之前的 `tmp-wet030-full-alns-cb-allCycles-current` 使用的是 `fullDomain` arc-join exact pricing，显式打开 `runALNSForSeed` 后初始 incumbent 已经是 `15325`，初始列 `63`，root 中 heuristic pricing 先把 pool 扩到 `5797`，随后 full-domain exact pricing 只返回 `74/2/1/0` 条列。root 后仍由 directed `ArcBrancher` 在 `(3,4)` 上分支，但 Node 2 的 full-domain exact 第一轮只保留 `fw=3831,bw=1120`，Node 3 第一轮只保留 `fw=2796,bw=618`，没有出现半域路径的十万级 forward-only 标签。整个流程 `FINISHED, obj=bound=15325`，总时间 `101.666s`。

当前 directed-only 慢实验使用的是 `halfDomain` GCNGBB-style exact pricing，comparison 入口默认没有打开 ALNS，root 初始 incumbent 是 `46152`，直到 root 完成后才由 restricted integer heuristic 刷到 `15325`；同时开启了 subtree arc elimination，root 后继承了 `701` 条永久禁弧。更重要的是，branch child 中 half-domain 的 backward side 基本被 completion bound 剪空，pricing 退化成大量 forward label 直接 `forward->sink` 收尾的负列生成：Node 2 一轮返回 `69952` 条，Node 3 一轮候选堆打满 `100000`。因此这次慢不是“wet030 本来应该快但突然不快”，而是半域 branch-node 路径、subtree elimination 和大 K 返回列数共同暴露了一个新瓶颈。若要复现之前快的路线，应使用 `mode=fullDomain`、打开 `runALNSForSeed=true`，并先不启用 subtree elimination 正式写入；若要继续优化半域路线，则应针对 branch node 的 forward-only 列爆炸单独处理。

### 41.2 2026-06-03 wet030 旧配置单变量复现

为避免把多个开关混在一起，本轮按“旧快跑配置”做 `maxNodes=2` 受控复现。基准配置为 `tmp-wet030_001_2m`、`completionBound=allCycles`、`runALNSForSeed=true`、关闭无向相邻分支、关闭 subtree arc elimination 正式写入、关闭本轮 local arc fixing、`maxExactColumns=100000`。只跑到 root 和第一个 child，目的是定位第一个明显变坏的配置，而不是完整求最优。

先复现旧 full-domain 路径，结果为 `solve=34.385s`、exact `9.843s/8`，Node 2 第一轮 full-domain exact 返回 `146` 列，`fw kept=3831`、`bw kept=1120`，与此前完整最优日志的 Node 2 结构一致。随后只把 `mode` 改成 half-domain，其余不变，结果为 `solve=35.551s`、exact `7.011s/6`。这个 half-domain Node 2 的 backward 仍然被剪空，但 exact 第一轮只返回 `85` 列，`fw kept=3613`，没有出现十万级 forward-only 枚举。因此，half-domain 本身不是变坏的充分原因。

接着只在 ALNS 旧口径上加回 subtree arc elimination 正式继承，结果为 `solve=27.650s`、exact `4.847s/7`，没有变坏，反而略快。再只关闭 ALNS、但不启用 subtree，结果为 `solve=22.645s`、exact `6.278s/7`，同样没有爆炸。这说明“无 ALNS”单独也不是根因。

真正复现坏路径的是“无 ALNS + subtree arc elimination 正式继承”的组合：`solve=66.722s`、exact `46.817s/7`。在同样 root、同样 `(3,4)` directed arc 分支下，root 的 subtree elimination 把 `701/870` 条 job-job arc 写入两个 child。Node 2 的 exact 第一轮从未继承 subtree 时的 `added=45, fw kept=1442, bw kept=0`，变成继承后的 `added=69952, fw kept=121210, bw kept=0`。completion-bound 构造反而更快，从约 `673ms` 降到 `61ms`，因为图被大量删弧后 DP 状态更少；真正变慢的是 half-domain forward label/dominance 和 forward->sink 负列枚举。

这个结果说明，永久 subtree arc elimination 和本轮 local arc fixing 不是同一种优化。local arc fixing 只在当前 pricing 轮跳过不可能出现在负 reduced-cost 列中的 arc，不改变 master LP；而 subtree elimination 会把大量 arc 写进 child node 的永久 forbidden 结构，改变 child RMP 的可用列、分支行和对偶解。虽然可行路径集合变小，但 LP 对偶可能变得更退化，half-domain completion bound 又把 backward side 全部剪掉，于是大量 forward prefix 在当前 dual 下看起来仍能直接接 sink 形成负 reduced-cost 列，导致一次 exact pricing 返回近 7 万列。

当前确定的变坏原因是：在弱初始列池/无 ALNS 的 root 路径上，subtree arc elimination 正式写入 child 后改变了 child dual，使半域 pricing 退化成 forward-only 大量负列枚举。它不是 scalar 预筛问题，也不是无向相邻分支问题；有 ALNS 初始列池时，同样加回 subtree 没有复现该坏结果。后续默认策略应更保守：subtree arc elimination 继续保留诊断；正式继承只应在有稳定上界和足够初始列池的场景测试，或者先只用于 pricing-local 层，不直接写入 master forbidden arc 行。若要继续使用正式继承，还需要记录 child 构造后有多少历史列被 branch/filter 排除、LP job dual/arc dual 的极值、以及 forward->sink 候选数量，判断是不是对偶退化而非算法实现错误。

进一步解释这个现象的本质：对 pricing 子问题来说，arc 少了通常会减少扩展候选；但对 BPC 的 child node 来说，永久删弧同时改变了 restricted master 的 LP relaxation。`Node.isArcForbidden(i,j)` 会在三个层面生效：历史列进入 child 初始 RMP 时会被兼容性过滤；LP 会为 forbidden arc 建 `x_ij=0` 行；pricing 图后续也不能再生成这些 arc。因此它不是只把子问题图剪小，而是先把 RMP 的列空间和对偶价格体系重算了一遍。

在 wet030 的坏路径里，root 一次性继承 `701/870` 条 job-job forbidden arc，child 的可用历史列和可生成结构都被大幅改变。由于没有 ALNS 的较好初始列池支撑，child RMP 需要用更少、更偏的列满足覆盖和分支约束，LP 对偶就可能变得更极端。这个对偶会进入 reduced cost：`-jobDual`、`-arcDual` 和 branch row dual 都会改变 pricing 中每条路径的吸引力。于是虽然物理可走 arc 变少了，剩下路径上的 reduced cost 反而可能更负，导致大量 forward prefix 不能被 completion bound 剪掉。

半域实现还放大了这个问题。branch node 下 `dualWindow=staticOutsourcingOnly`，`pricingHorizon` 回到完整 `CmaxH`，backward 第一层真实 job label 又被 completion-bound reduced-cost cutoff 全部剪掉，crossing join 基本消失；但是 forward label 仍可以通过 `forward->sink` 直接生成完整列。这样一来，问题从“少边后的双向拼接”退化为“在剩余图上枚举大量能直接接 sink 的 forward-only 负列”。这解释了为什么 subtree 后 completion-bound DP 构造更快、arc 更少，但 final exact pricing 反而从 `45` 条负列变成 `69952` 条负列。

因此这里的反直觉并不矛盾：如果固定同一套 dual、同一套 label 状态，删 arc 一定不会增加可扩展 child 数；但 BPC 分支后的 LP dual 不是固定的。永久 arc elimination 改变了 master relaxation，可能让剩余列的 reduced cost 结构更差。当前 subtree 判据只保证“被删 arc 不会出现在能改进 incumbent 的整数解中”，并不保证“删除后 child LP 更容易”或“pricing 负列更少”。这也是后续不能把它默认正式继承的主要原因。

### 41.3 2026-06-03 subtree 变慢路径的直接数据复核

用户指出前述解释仍然偏推断，因此本轮只补诊断字段并重跑同口径对照，没有改算法逻辑。新增统计包括当前 pricing node 的 job-job forbidden arc 数、job dual 的 `min/max/sum/positiveCount`，以及 `forward->sink` 收尾时访问的 forward label 数和其中负 reduced-cost 候选数。测试仍为 `tmp-wet030_001_2m`、half-domain、`completionBound=allCycles`、`runALNSForSeed=false`、关闭无向相邻分支、关闭本轮 local arc fixing、`maxNodes=2`、`maxExactColumns=100000`，只比较 subtree arc elimination 是否正式写入 child。

结果为：subtree 关闭时 `solve=24.048s`、exact `5.567s/7`；subtree 开启时 `solve=53.948s`、exact `40.651s/7`。两者 root pricing 的列池都在 `4896`，root bound 和分支 arc 都一致，差异从 Node 2 开始出现。Node 2 第一轮 exact pricing 的关键数据如下：

`subtree=off`：`forbiddenJobArcs=1`，即只有 directed arc 分支禁掉的 `(3,4)`；heuristic pricing 先加 `840` 列，pool 到 `5736`；exact 第一轮 `time=771ms`，`fw kept/dominated=1442/42`，`bw kept=0`，`completionBound buildMs=672.835`，`completionBound eval=29313`，`forwardSink visited/negative=1441/45`，最终 `addedColumns=45`。此时 job dual 为 `min=446.0, max=2407.0, sum=33726.0, positive=30`。

`subtree=on`：root 后 `SubtreeArcElim` 把 `701/870` 条 job-job arc 写入 child，因此 Node 2 第一轮看到 `forbiddenJobArcs=702`。heuristic pricing 只加 `813` 列，pool 到 `5709`，规模与 off 基本同一量级；exact 第一轮却变成 `time=37675ms`，`fw kept/dominated=121210/77343`，`bw kept=0`，`completionBound buildMs=44.857`，`completionBound eval=446298`，`forwardSink visited/negative=121186/69952`，最终 `addedColumns=69952`。此时 job dual 为 `min=-0.0, max=2701.864, sum=33163.909, positive=28`。

这组数据说明两件事。第一，subtree 写入后 completion-bound DP 构造确实更轻，从 `673ms` 降到 `45ms`，启发式 pricing 也从数百毫秒降到二三十毫秒；所以慢点不在 bound 构造，也不是“图更大”。第二，真正的爆点是 exact pricing 中的 forward-only 收尾：`forwardSink negative` 从 `45` 变为 `69952`，并且这些候选全部进了 K 堆，没有被 dropped。也就是说，删弧后的 child LP dual 使大量剩余 forward prefix 的 reduced cost 变得足够负，可以直接接 sink 成完整列。

job dual 诊断也支持“dual 口径改变”而不是“纯 pricing 实现变慢”。subtree off 的 Node 2 第一轮 30 个 job dual 全为正，最小值约 `446`；subtree on 后只有 28 个为正，最小值为 `0`，最大值从约 `2407` 提到 `2702`，sum 略降。这不是决定性证明，但至少说明两边 pricing 使用的 reduced-cost 价格体系已经不同。结合 `forbiddenJobArcs=702`、DP 构造变快、forwardSink 负候选暴涨，可以确定当前变坏的直接原因是：大量永久禁弧改变 child RMP/dual 后，半域 pricing 在该 child 上退化为 forward-only 负列大量枚举，而不是 completion-bound scalar、arc scan 或 backward join 的问题。

因此后续判断应更精确：subtree arc elimination 的永久继承判据可以是安全的，但“安全删除 arc”不等于“LP/pricing 更快”。在当前 half-domain + 大 K + 无 ALNS 的弱列池路径下，它会让 child 一次 exact pricing 返回近 7 万列，导致列池从 `5709` 膨胀到 `75661`。后续若继续尝试该技术，应优先处理两个工程问题：一是限制 branch node 单轮 exact 返回列数或使用 K 堆当前 worst reduced cost 做 record 型加速，但最后 certificate 轮仍回到 cutoff 0；二是继续记录 child 初始 RMP 中有多少历史列因 forbidden arc 不兼容、禁弧行 dual 和 sink arc dual 分布，用数据确认到底是哪类 dual 诱发 forward->sink 列爆炸。

### 41.4 2026-06-03 进一步隔离：不是“少 arc 本身”导致爆炸

用户再次指出不能把现象简单归因于 dual。本轮补一个更直接的隔离实验：关闭 subtree arc elimination 正式继承，但开启当前 pricing 轮的 local completion-bound arc fixing。这样 pricing 图同样会跳过大量 arc，但不会把这些 arc 写进 `Node.arcState`，也不会在 master 中生成大量 `forbiddenArc_i_j` 行。测试口径仍为 `tmp-wet030_001_2m`、half-domain、`completionBound=allCycles`、`runALNSForSeed=false`、关闭无向相邻分支、`maxNodes=2`、`maxExactColumns=100000`。

结果为 `solve=17.062s`、exact `4.427s/7`，比 subtree off 的 `24.048s/5.567s` 还快，更没有出现 subtree on 的 `53.948s/40.651s`。Node 2 第一轮 exact 中，local arc fixing 扫描 `869` 条 arc，固定 `749` 条，比 subtree on 的 root 固定 `701` 条还多；但 pricing 统计仍保持在正常规模：`fw kept/dominated=1442/42`，`forwardSink visited/negative=1441/45`，`addedColumns=45`。completion-bound eval 从 subtree off 的 `29313` 降到 `4710`，说明 local arc fixing 的确减少了函数判定工作，但没有改变负列数量级。

这组对照基本排除“删 arc 或 pricing 图变稀导致 forward label 爆炸”的解释。真正有问题的是 subtree 正式继承的实现层级：当前 `CompletionBoundSubtreeArcEliminator.applyTo()` 调用 `node.forbidArc(i,j)`，这些 arc 与正常 branching arc 混在同一个 `arcState` 里。随后 `LP.buildArcBranchConstraints()` 会对每条 `ARC_FORBIDDEN` 建一条 `x_ij=0` 的 master 等式行。也就是说，701 条 reduced-cost fixing 被实现成了 701 条 branch forbidden arc 行，而不是单纯的“后继子树不再生成/使用这些 arc”。

这不是一个语义上无关的小差别。local arc fixing 证明，在同一个 LP 对偶口径下，pricing 里跳过 700 多条 arc 只会让 eval 变少，不会让负列暴涨。subtree on 的不同点是 child master 被永久加了 701 条额外等式行，并且 child 首次 `LP.construct()` 仍先继承父节点 seed columns，之后靠这些等式行和后续 `resetRestrictedColumnsByCurrentReducedCost()` 再处理兼容性。最终 subtree on 的 Node 2 一轮 exact 后 restricted columns 达到 `72002`，而 subtree off 和 local arc fixing 都只有 `4888`。这说明爆炸发生在“RMP 结构 + half-domain exact pricing 的反馈环”里，而不是 completion-bound arc 判据本身。

因此当前更准确的结论是：subtree arc elimination 的判据可以继续作为 reduced-cost fixing 候选，但不应该直接写成 `Node.arcState=ARC_FORBIDDEN` 并进入 master branch rows。更合理的实现应把“subtree eliminated arc”与真正 branch arc 分开存储：pricing 和列兼容性检查应禁止这些 arc；child 初始列集也应过滤掉包含 eliminated arc 的列；但 `LP.buildArcBranchConstraints()` 不应为它们建 `forbiddenArc` 行，也不应把它们当成需要 repair slack 的分支约束。这样才能验证“永久删除 arc 本身”是否有效，而不混入大量额外 master 行造成的反馈。

### 41.5 2026-06-03 对 subtree label 爆炸原因的再修正

前一节把“subtree fixed arc 被写成 `arcState`，从而进入 master forbidden 行”说成直接原因，这个判断需要收窄。重新检查 child 流程后可以确认：`LP.construct()` 第一次确实只按静态预处理过滤父节点 seed columns，但如果该 child 初始 LP 可行，`PC.solve()` 随后会调用 `resetRestrictedColumnsByCurrentReducedCost()`，这里使用 `Node.isColumnCompatible()` 过滤当前 forbidden arc。因此在进入正常 heuristic/exact pricing 前，正式 restricted column set 理论上已经不含这些 subtree forbidden arc。也就是说，不能简单说“701 条行左侧含旧变量直接造成爆炸”；这个说法不够精确。

当前数据能确定的是另一条链路：subtree formal inheritance 改变了 child exact pricing 的入口状态，而 label 爆炸本身发生在深度较大的 forward-only sink 列上。最新 depth 诊断显示，在同样 `runALNSForSeed=false`、关闭无向相邻分支、关闭本轮 local arc fixing、`maxNodes=2` 的口径下，`subtree=off` 的 Node 2 第一轮 exact 只有 `fw kept/dominated=1442/42`，`forwardSink visited/negative=1441/45`，负 sink 列集中在 depth `12-16`，其中 depth 15 也只有 `18` 条负列；而 `subtree=on` 的 Node 2 第一轮 exact 为 `fw kept/dominated=121210/77343`，`forwardSink visited/negative=121186/69952`，负 sink 列主要集中在 depth `12-15`，其中 depth 13 为 `21598`、depth 14 为 `31669`、depth 15 为 `12549`。这说明爆炸不是短标签，也不是 join；它是大量长 forward prefix 直接接 sink 后变成负 reduced-cost 列。

本轮 local arc fixing 对照进一步排除了“arc 少了所以 label 多”的解释。local arc fixing 在 Node 2 第一轮当前 pricing 内固定 `749/869` 条 arc，比 subtree root 继承的 `701/870` 还多，但结果仍是 `fw kept/dominated=1442/42`、`forwardSink visited/negative=1441/45`、`addedColumns=45`。因此，只在 pricing 图里跳过大量 arc 不会导致爆炸；爆炸只在把 fixed arc 正式继承进 child 后出现。

更精确的当前结论是：subtree formal inheritance 不是单纯“减少 pricing 候选 arc”，而是提前改变了 child 的可用历史列、分支过滤、heuristic pricing 状态和随后 LP 对偶；经过 `after_column_filter` 后列应已兼容 forbidden arc，但 child RMP 的列空间已经和 subtree-off/local-arcfix 路径不同。这个不同的 RMP/dual 使半域 exact pricing 在该 child 上出现大量长 forward-only 负列。数据上也能看到这一点：`subtree=off/local-arcfix` 的 Node 2 第一轮 `bestRC=-38.0`，而 `subtree=on` 为 `bestRC=-411.45`；`forwardReach` 平均从约 `21.5` 降到 `17.8`，dominance 的 `subset visited` 从 `21935` 暴涨到 `65207363`。这些都是“剩余标签更难被支配、且大量可直接接 sink 为负”的直接表现。

因此后续不要再把“master forbidden 行直接含旧列”当作定论。更合理的下一步诊断是记录 `after_column_filter` 前后的列数、因当前 forbidden arc 不兼容被删的列数、正值列保留数、low reduced-cost 候选数，以及 exact pricing 入口时的 column length histogram、job dual/arc dual/sink arc dual 分布。只有拿到这些数据，才能继续判断是列池压缩后的 LP 对偶退化、某些 sink arc dual/branch dual 异常，还是 half-domain forward completion bound 在 branch child 上过弱。当前已经确定的是：爆炸的直接形态是 depth 12-15 的 forward->sink 负列大量产生；local arc fixing 证明删弧本身不是原因；subtree formal inheritance 改变 child 入口状态是触发条件。

### 41.6 2026-06-03 arc dual 复核后的核心原因

继续按用户要求追问“为什么 arc 少了 label 反而更多”。本轮补充 exact pricing 入口诊断：restricted column 数、其中与当前 node 不兼容的列数、平均列长，以及 `lp.getArcDual()` 在 allowed job-job arc、forbidden job-job arc、job->sink arc 上的非零统计。重新跑 `tmp-wet030_001_2m`、half-domain、`completionBound=allCycles`、`runALNSForSeed=false`、关闭无向相邻分支、关闭本轮 local arc fixing、`maxNodes=2` 的 subtree off/on 对照。

结果首先排除一个可能误判：不是 arc branch dual 把剩余路径压负。Node 2 第一轮 exact 入口中，`subtree=off` 和 `subtree=on` 的 `arcDual allowedNZ`、`forbiddenNZ`、`sinkNZ` 全部为 `0`。因此，701 条 subtree fixed arc 即使写入 `arcState` 并建了 forbidden row，本轮数据里也没有通过 `lp.getArcDual()` 直接改变可走 arc 或 sink arc 的 reduced cost。之前怀疑的“forbidden row dual 直接影响 pricing”在这组数据下不成立。

真正的核心差异是 child exact pricing 入口的列集被砍瘦了。`subtree=off` 的 Node 2 第一轮 exact 入口有 `4801` 条 restricted columns，`incompat=0`，平均长度 `14.342`；`subtree=on` 只有 `2003` 条 restricted columns，`incompat=0`，平均长度 `14.947`。也就是说，`after_column_filter` 确实已经把不兼容列过滤掉了，但 subtree 继承让父节点 4896 条左右的 route set 只剩约 2000 条能在 child 上继续使用。local arc fixing 不会改 master 列集，所以同样跳过大量 arc 时仍然保留 4800 条左右的 active restricted columns，不会触发大规模补列。

这就是“arc 少了但 label 爆炸”的核心机制：pricing 不是在固定同一套 dual 和同一套 active columns 上单调比较图大小；它是在当前 RMP dual 下寻找缺失的负 reduced-cost 列。subtree 继承先把已有列池中大量使用 eliminated arcs 的列移出 child RMP，导致 exact pricing 从一个严重缺列的 residual graph LP 出发。剩下的 job-job arc 虽然只有约 169 条，但对 30-job、depth 12-15 的 forward path 来说仍然有指数级组合空间；而当前 RMP 缺少这些“不用 eliminated arcs 的替代长列”，所以 reduced-cost cutoff 和 completion bound 会让大量 forward prefix 存活并直接接 sink。最终第一轮 exact 一次性补出 `69952` 条列。

因此当前更准确的结论是：subtree arc elimination 作为 reduced-cost fixing 可能是安全的，但把它正式继承到 child 会造成“残余图列池冷启动”。根节点已有的 restricted columns 主要服务于未删弧图；一次删掉 80% job-job arc 后，child 需要的是 residual graph 上的新 route inventory。local arc fixing 快，是因为它只用于当前 pricing 轮剪函数评价，不会让 child RMP 丢掉这几千条已有列。subtree-on 慢，是因为它先丢掉大半可用 route set，再让 exact pricing 用半域 label 从头补 residual graph 的大量替代负列。

由此，后续若继续做 subtree arc elimination，核心修复方向不是继续猜 dominance 或 arc dual，而是避免 child 残余图冷启动。可选方案包括：只保留诊断不正式继承；或者正式继承时先为 child residual graph 做一轮轻量 heuristic/repair seed 扩充，再进入 exact；或者把 subtree eliminated arc 独立于 branch `arcState` 存储，并在 `prepareChildSeedColumns` 阶段显式统计和补偿被删列数；又或者限制 branch node 单轮 exact 返回列数，避免一次从 2000 列直接膨胀到 7 万列。当前数据下，最直接的坏点不是“label 为什么凭空多”，而是 child RMP 从 `4801` 列退化到 `2003` 列后，exact pricing 被迫枚举 residual graph 的替代列。

### 41.7 2026-06-03 subtree label 爆炸的 reduced-cost 组成复核

用户继续追问“列变少也只是 dual 变化，为什么对 pricing 来说 arc 少了还会更难”。本轮补上此前缺失的 `machineDual` 诊断，并重新跑同口径三组实验：`tmp-wet030_001_2m`、half-domain、`completionBound=allCycles`、`runALNSForSeed=false`、关闭无向相邻分支、`maxNodes=2`、`maxExactColumns=100000`。另外尝试 `wet020/wet025` 小算例，但两者根节点即结束，不能触发 child subtree 场景，因此不适合定位该 bug。

关键对照如下。`subtree=off` 时，Node 2 第一轮 exact 入口 `forbiddenJobArcs=1`，`machineDual=-9198.0`，`jobDual min/max/sum/pos=446.0/2407.0/33726.0/30`，`columns=4801`，`arcDual allowed/forbidden/sink` 全为 0；pricing 结果为 `fw kept/dominated=1442/42`，`forwardSink visited/negative=1441/45`，`bestRC=-38.0`，`addedColumns=45`。`subtree=on` 时，Node 2 第一轮 exact 入口 `forbiddenJobArcs=702`，`machineDual=-8916.95`，`jobDual min/max/sum/pos=0.0/2701.86/33163.91/28`，`columns=2003`，arc dual 仍全为 0；pricing 结果变为 `fw kept/dominated=121210/77343`，`forwardSink visited/negative=121186/69952`，`bestRC=-411.45`，`addedColumns=69952`。总时间也从 `solve=19.533s/exact=5.443s` 变为 `solve=58.475s/exact=42.192s`。

再看隔离实验：关闭 subtree 正式继承，但打开当前 pricing 轮 local arc fixing。Node 2 第一轮 local arc fixing 判掉 `749/869` 条 arc，比 subtree root 继承的 `701/870` 还多；但它不改变 child RMP 和 `machineDual`，入口仍为 `machineDual=-9198.0`、`columns=4801`，结果仍是 `fw kept/dominated=1442/42`、`forwardSink visited/negative=1441/45`、`addedColumns=45`，总时间反而降到 `solve=13.686s/exact=3.736s`。这组数据把“arc 少本身导致 label 爆炸”排除了。

因此本次更精确的核心原因是：subtree 正式继承不是在同一套 reduced cost 下单调删边，而是先把 child RMP 的可用列空间从约 4800 条压到约 2000 条，使 LP 对偶重新定价；其中 `machineDual` 从 `-9198.0` 变到 `-8916.95`。在当前 reduced-cost 公式里，source label 会执行 `sourceFrontier.shiftYInPlace(-machineDual)`，因此 `machineDual` 这个变化会把所有列的 reduced cost 整体压低约 `281`。再叠加 job dual 分布变化，剩余图中大量 depth 12-15 的 forward prefix 直接接 sink 后低于 0，形成近 7 万条负列。arc dual 这轮为 0，所以不是 forbidden row dual 或 sink dual 直接导致。

这解释了为什么“物理 arc 更少”仍然可能“label 更多”：如果固定 dual，删 arc 不会增加扩展；但这里 subtree formal inheritance 改变了 master LP 的列空间和 dual，尤其是机器数范围约束的 dual 全局平移了所有 route reduced cost。local arc fixing 在同一 dual 下删更多 arc 仍然更快，正好说明 pricing 图剪边本身是有效的，问题出在正式继承后的 child LP 重新定价和半域 forward-only 负列枚举。后续优化应围绕两个方向：一是保留 local arc fixing，暂不默认正式继承 subtree eliminated arcs；二是若继续正式继承，必须控制 child residual graph 冷启动后的 exact pricing 行为，例如限制单轮返回列数、先补 residual seed columns，或在 certificate 轮之前使用更保守的 top-K/record 机制。

### 41.8 2026-06-03 排除 dual 固定口径下的删弧问题，并定位到 child column filter

用户指出“列变化/dual 变化”仍然不是足够精准的核心解释，本轮按其建议做了两个更强的隔离实验。新增 debug 状态 `pricingOnlyForbiddenArc`：subtree elimination 仍在 root 后计算 `701/870` 条 arc，但不写入 `Node.arcState`，不建 master forbidden 行，不参与 `Node.isColumnCompatible()`，只在 half-domain exact pricing 的 forward/backward 扩展、crossing join 和 forward->sink 检查中额外禁弧。这样 Node 2 的初始列、heuristic pricing、LP dual 与 subtree-off 基本保持一致，只比较“同一 dual 下 exact pricing 删除这些 arc”。

结果：baseline off 为 `solve=19.443s, exact=5.399s`，Node 2 第一轮 exact 为 `columns=4801`、`machineDual=-9198.0`、`forbiddenJobArcs/pricingOnlyJobArcs=1/0`、`fw kept=1442`、`forwardSink negative=45`、`addedColumns=45`。pricing-only subtree 为 `solve=18.389s, exact=4.746s`，Node 2 第一轮 exact 为 `columns=4801`、`machineDual=-9198.0`、`forbiddenJobArcs/pricingOnlyJobArcs=1/701`、`fw kept=1244`、`forwardSink negative=16`、`addedColumns=16`。这说明在同一 RMP/dual 下，禁用这 701 条 arc 不会导致 label 爆炸，反而减少 label 和负列。

随后做第二个隔离：正式 subtree 写入 `Node.arcState`、master forbidden 行照建，但 debug 跳过 child 初始 LP 可行后的 `PC.solve()` 中 `resetRestrictedColumnsByCurrentReducedCost()`，也就是不按当前 forbidden arc 兼容性过滤 child RMP 列。该组为 `solve=18.069s, exact=7.187s`，没有正式 subtree on 的 50 秒级爆炸。Node 2 第一轮 exact 为 `columns=5108`、其中按当前 node 看 `incompat=3704`，`machineDual=-9617.5`，forbidden arc row dual 已非零：`forbiddenNZ/absSum=47/12876`；但 exact 只产生 `addedColumns=13097`，远低于正式 subtree on 的 `69952`，总时间也仍在正常范围。

四组结果放在一起后，核心触发点已经很明确：不是 completion-bound 删除 arc 的判据，也不是同一 dual 下 pricing 图变稀，更不是 master 里存在 forbidden 行本身。真正把 wet030 child 打爆的是 `after_column_filter` 这一步。正式 subtree on 的流程在 child 初始 LP 可行后调用 `resetRestrictedColumnsByCurrentReducedCost()`，该函数先执行 `if (!isColumnCompatible(column)) continue;`，把包含 701 条 subtree forbidden arc 的历史列全部排掉，使 Node 2 exact 入口列池从 off 的 `4801` 变成 `2003`。此时 master forbidden 行大多变成空行/零 dual，child RMP 进入残余图冷启动；后续 half-domain exact pricing 被迫枚举大量不用 eliminated arcs 的替代长 forward-only 列，形成 `forwardSink negative=69952` 和 `fw kept=121210`。

因此更准确的修正方向不是继续怀疑 completion bound，也不是认为“arc elimination 本身会变慢”，而是不要让 subtree elimination 和正常 branch arc 共享同一个“必须过滤历史列”的语义。subtree reduced-cost fixing 若要保留，应分成两层：pricing 层可以直接禁弧；child RMP seed/filter 层不能简单按 `Node.isColumnCompatible()` 把所有含 eliminated arcs 的列清空，至少不能在缺少 residual seed columns 时这么做。可选做法是把 subtree-eliminated arc 独立存储，不进入 `arcState`；或在正式过滤前先为 residual graph 生成足够替代 seed；或像 pricing-only 实验那样先只用于 exact pricing 禁弧，等有更稳的列池策略后再考虑永久继承。

### 41.9 2026-06-03 completion bound 是否失效的漏斗复核

用户指出如果只是 residual graph 冷启动，直觉上应表现为 pricing 次数增加，而不是单次 exact pricing 暴涨；怀疑点转向 completion bound 是否没有起作用。本轮在 half-domain pricing 中补充 forward 扩展漏斗计数：`reachableSet` 枚举候选数、被 forbidden arc 跳过数、成功构造 child 数、通过 completion bound 后进入 dominance 的数量。该计数只做诊断，不改变算法。

同口径复跑 `tmp-wet030_001_2m`、half-domain、`completionBound=allCycles`、`runALNSForSeed=false`、关闭无向相邻分支、`maxNodes=2`。`subtree=off` 的 Node 2 第一轮 exact 为 `forwardExtend candidates/arcPruned/infeasible/constructed/boundSurvivors=31083/59/0/31024/1483`，completion bound forward prune 为 `29541`，即在成功构造的 child 中约 `95.2%` 被剪掉，最终 `fw kept/dominated=1442/42`、`forwardSink negative=45`。这说明正常口径下 completion bound 非常有效。

`subtree=on` 的 Node 2 第一轮 exact 变成 `2154533/1674333/0/480200/198552`，completion bound forward prune 为 `281648`，即它仍然剪掉了 28 万多个 child，但剪枝率只有约 `58.7%`；通过 bound 的 child 从 `1483` 暴涨到 `198552`，随后 `fw kept/dominated=121210/77343`，`forwardSink negative=69952`。因此不能说 completion bound 完全没起作用，它确实在工作；但在 subtree 正式继承后的对偶口径下，大量 prefix 的 lower bound 已经低于 0，bound 按 exact pricing 语义不能剪。

这也解释了为什么单次 pricing 会暴涨，而不是只增加 pricing 次数。该 child 的第一轮 exact 并不是“缺几条列慢慢补”，而是在当前 LP dual 下真实存在大量可直接接 sink 的负 reduced-cost forward-only 列。因为 `maxExactColumns=100000`，本轮需要耗尽队列并返回所有负列以形成 exact pricing 证书；既然实际负列数达到 `69952`，单次 pricing 自然会很重。后续加完这些列后，同一 node 的第二轮 exact 立刻回到 `fw kept=3529`、`forwardSink negative=13`，说明第一轮暴涨本质上是在一次性补 residual graph 的大批负列。

另一个可独立优化的点是 forbidden arc 没有进入 dominance reachable-set key。subtree-on 下 `reachableSet` 仍会枚举大量当前 node 已禁的直连 arc，然后在 `canExtendForward()` 被跳过，导致 `arcPruned=1674333`。这部分是纯枚举开销，可以后续用 node/pricing-local forbidden matrix 在构造 reachable set 时过滤，以减少循环次数；但它不是主要原因，因为跳过这些 arc 后仍成功构造了 `480200` 个 child，并有 `198552` 个通过 completion bound。

因此本轮结论进一步收窄为：completion bound 没有失效，但 subtree 正式继承导致当前 dual 下 completion lower bound 对大量长 forward prefix 不再能证明非负，剪枝率从 `95%` 降到 `59%`；同时 reachable 候选枚举暴涨，dominance 负担随 `boundSurvivors` 暴涨。工程上应优先避免在该口径下无限制 exact 返回全部负列，或者在 subtree child 先做 residual seed/列数上限/record 型过渡，而不是把问题归因于 completion bound 构造时间或函数未调用。

### 41.10 2026-06-03 为什么不是表现为多轮 heuristic 补列

用户继续指出，如果 subtree 后只是 child 残余图缺列，理论上 heuristic pricing 应先补列，或者表现为多轮 pricing，而不应在一次 exact pricing 里暴涨。本轮基于已有日志进一步解释该现象。

wet030 subtree-on 的 Node 2 在进入第一轮 exact 前，确实已经跑过多轮 heuristic pricing：依次加了 `343/202/133/93/30/12` 条列，总计 `813` 条，然后 heuristic 报告没有负 reduced-cost 列。问题在于当前 heuristic 只是在受限局部搜索/局部池里找负列，它不是 residual graph 的完备枚举器。subtree 正式过滤以后，真正缺的是大量 depth 12-15 的长 forward-only 路径；这些路径组合数很大、且受禁弧后结构更偏，heuristic 没有覆盖到。随后 exact pricing 才发现仍有 `69952` 条负列。

这也解释了为什么不是很多轮 exact。当前 `maxExactColumns=100000`，而第一轮 exact 找到的负列数是 `69952`，没有撞到 K 上限；同时 GCNGBB-style exact pricing 的这一轮目标是耗尽 forward/backward 队列来形成 exact pricing 证书，而不是找到少量负列就停。因此它会在单轮里继续扩展，直到队列耗尽，并一次性把这 69952 条负列放入候选池。如果把 K 降低或使用 record/top-K 提前停止策略，现象可能会变成多轮 exact/CG；但当前大 K exact 口径会把 residual graph 的负列库存一次性释放出来。

因此“冷启动”不是指每轮只能补几条列，而是指 child RMP 在过滤后缺少 residual graph 上的大批替代长列；heuristic 只能补到其中很小一部分，exact 又被配置成完整枚举当前所有负列，所以单轮时间暴涨。这里的慢不是 completion-bound 构造慢，也不是 heuristic 没运行，而是 heuristic 覆盖不足加上 exact 大 K 完整证书口径共同造成的。

### 41.11 2026-06-03 backward label 为 0 不等于 pricing 已最优

用户进一步提出：如果 job 到 sink 没有时间、sink 是虚拟节点，那么 backward label 不存在是否意味着任意 forward 到 sink 的下界都已经非负，从而此轮 pricing 已经最优。这里需要区分 sink 弧时间语义和 half-domain label 定义域。

在当前 half-domain GCNGBB-style pricing 中，真实 backward label 表示右半域 suffix，完成时间定义域在 `[Tmid, pricingHorizon]`。从 sink root 向左扩展出的第一层真实 backward label `[j]->sink` 也只覆盖右半域，即 job `j` 的完成时间不早于 `Tmid` 的情形；它用于 crossing-arc join，而不是表示所有可能直接接 sink 的完整列。若这些 backward label 全部被 completion bound 剪掉，只能说明右半域 suffix/join 路径没有需要保留的负列证据，不能推出左半域内已经完成的 forward prefix 直接接 sink 都非负。

forward->sink 是单独的收尾流程：forward label 的函数定义域在 `[ell, Tmid]`，它可能代表一条完全落在左半域、最后一个 job 在 `Tmid` 之前完成的完整 route。由于 `job->sink` 没有 processing/setup time，这类 route 可以直接从 forward label 收尾，不需要也不会产生真实 backward label。因此 wet030 Node 2 会出现 `bw kept=0`，但同时仍有 `forwardSink negative=45` 或 subtree-on 的 `69952`。这不是矛盾，而是 half-domain 拆分下的正常语义。

因此 exact pricing 的完成证书不能只看 backward label 是否为 0。只有 forward/backward 队列都耗尽、final join 扫描完成、forward->sink 收尾也没有发现负列，并且没有触发列数上限截断，才可以认为本轮 pricing 没有负 reduced-cost 列。backward label 为 0 只能说明 crossing/right-half suffix 侧为空，不能替代 forward-only 完整列检查。

### 41.12 2026-06-04 subtree arc elimination 问题对外询问版摘要

当前 TWET-BPC 中，completion bound 和 subtree arc elimination 涉及的核心代码如下。`CompletionBoundCalculator` 负责构造 relaxed completion bound，输出每个 job 的 forward prefix bound `forwardUByJob` 和 backward suffix bound `backwardRByJob`，并维护离散 scalar cache。`GCNGBBStyleBidirectional` 在半域 exact pricing 中调用这些 bound：`isForwardCompletionBoundPruned()` 和 `isBackwardCompletionBoundPruned()` 用当前 label 与 completion bound 相加判断该 label 是否还能补成负列；`tryGenerateForwardColumn()` 处理 forward prefix 直接接虚拟 sink 的完整列；`forwardExtend()` 是当前 label 暴涨所在主循环，并已有 `forwardExtend candidates/arcPruned/infeasible/constructed/boundSurvivors` 漏斗统计。`CompletionBoundSubtreeArcEliminator` 是 node 求完 LP 后的 subtree arc elimination 组件：用当前 node 的 completion bound 对每条 job-job arc 估计“任何使用该 arc 的完整列”的 relaxed lower bound，若该下界已经不可能改善当前 incumbent，则把该 arc 作为后续子树候选删除。`Tree` 在 node LP 和 restricted-integer heuristic 后调用这个组件，并在分支生成左右 child 后通过 `applyTo()` 写入普通 forbidden arc，或通过 `applyToPricingOnly()` 写入只影响 pricing 的 debug 禁弧。`Node` 保存普通 `arcState` 和 debug-only `pricingOnlyForbiddenArc`。`LP.resetRestrictedColumnsByCurrentReducedCost()` 会按 `Node.isColumnCompatible()` 过滤 child 的 restricted columns，这一步是目前问题的关键触发点之一。

subtree arc elimination 的原理是 reduced-cost fixing / arc elimination。假设当前 node 的 LP lower bound 为 `LB_node`，当前全局整数上界为 `UB`，则 gap 为 `UB - LB_node`。对某条 arc `(i,j)`，用 completion bound 构造一个“任意完整 route 使用该 arc 的最小 reduced-cost 下界”：大致是 forward 到 `i` 的 relaxed prefix bound，加上 arc `(i,j)` 的固定 reduced cost，再加 backward 从 `j` 到 sink 的 relaxed suffix bound。若这个下界已经大于等于 gap，则任何使用该 arc 的列都不可能在该 node 的子树中产生足够好的整数解，因此理论上可以在后续子树中永久删除该 arc。这个操作的理论预期是：减少 pricing 图中的可走 arc，减少 label 扩展、join、函数求值和后续搜索空间，应该使后续 child pricing 更快。

当前观察到的反常现象是：只在当前 pricing 轮做 local arc fixing 时，结果符合预期。wet030 Node 2 第一轮中，local arc fixing 判掉 `749/869` 条 arc，`fw kept=1442`，`forwardSink negative=45`，求解更快。也就是说，在同一套 RMP/dual 下删 arc 本身不会让 pricing 变慢。但把 subtree elimination 正式继承到 child 后，性能反而大幅变差：Node 2 第一轮 exact 从 `fw kept=1442`、`forwardSink negative=45`、`addedColumns=45`，变成 `fw kept=121210`、`forwardSink negative=69952`、`addedColumns=69952`。总时间从十几秒级变成几十秒级。

目前已经排除和确认的点如下。第一，completion bound 没有完全失效：subtree off 时 constructed child 为 `31024`，bound 剪掉 `29541`，剪枝率约 `95.2%`；subtree on 时 constructed child 为 `480200`，bound 仍剪掉 `281648`，但剪枝率降到约 `58.7%`，最终 `198552` 个 child 通过 bound。第二，arc dual 不是直接原因：Node 2 第一轮 exact 中 allowed job-job arc、forbidden job-job arc、job-to-sink arc 的 dual 非零数都为 0。第三，backward label 为 0 不是 pricing 已最优；half-domain 下 backward label 只覆盖 `[Tmid, horizon]` 右半域 suffix，而 forward prefix 完全落在左半域时仍可直接接虚拟 sink 形成完整列。因此 `bw kept=0` 可以和 `forwardSink negative=69952` 同时出现。

目前最强的定位来自两个隔离实验。实验一是 pricing-only subtree：同样把 root 判掉的 `701` 条 arc 禁掉，但不写入 `Node.arcState`，不参与 master 行，也不触发 child column compatibility 过滤，只在 exact pricing 中额外禁弧。此时 Node 2 的 RMP/dual 基本保持 subtree-off 口径，第一轮 exact 的 `forwardSink negative` 从 `45` 降到 `16`，没有爆炸。说明“同一 dual 下少走这些 arc”确实是加速/减列的。实验二是正式 subtree 写入 `arcState`，但 debug 跳过 `resetRestrictedColumnsByCurrentReducedCost()` 中按 forbidden arc 兼容性过滤 child restricted columns。该组也没有出现 7 万负列爆炸。由此推断，真正触发慢的是正式 subtree 后的 child column filter：大量历史列因为包含 subtree-forbidden arcs 被过滤，child exact 入口列池从约 `4801` 降到 `2003`，使残余图 RMP 严重缺少替代列。

因此当前问题可以表述为：subtree arc elimination 理论上是在后续子树中删除不可能出现在优质解中的 arc；但当前实现把这些 arc 复用普通 branch forbidden arc 的语义，导致 child 进入 LP/pricing 前按 `Node.isColumnCompatible()` 清掉大量历史列。对 BPC 来说，这不只是“pricing graph 少了边”，而是把 child RMP 的列空间突然换成一个残余图冷启动状态。heuristic pricing 虽然先加了 `813` 条列，但它不是 residual graph 完备枚举器；随后 exact pricing 在 `maxExactColumns=100000` 且需要耗尽队列的口径下，一轮发现并返回 `69952` 条真实负 reduced-cost 的 forward-only 列，于是表现为单次 pricing 暴涨。

现在想请外部确认的核心问题是：对于这种基于 completion bound 的 subtree arc elimination，是否应该允许它像普通 branching forbidden arc 一样立即过滤 child RMP 中所有含该 arc 的历史列？还是应该把它区分成“pricing 层禁弧/生成层禁弧”和“RMP seed/filter 层禁弧”两套语义？如果 reduced-cost fixing 判据只保证“这些 arc 不会出现在可改善 incumbent 的整数解中”，它是否足以支持在当前 child LP 迭代过程中删除所有已有列，还是更适合先作为 pricing-only elimination 使用，等 residual graph 有足够 seed columns 后再正式继承？当前实验证据倾向于后者：pricing-only 删除是有效的，正式过滤历史列会触发 residual graph 列池冷启动和 exact pricing 暴涨。

### 41.13 2026-06-04 四组复跑的 dual 分位数和初始列来源

本轮先回答 4000 多条初始列从哪里来。root 节点最开始由 `InitialColumnBuilder.build()` 生成种子列；root 列生成过程中，heuristic pricing 和 exact pricing 会持续把负列加入 `LP.restrictedColumnIds`。生成 child 时，`Tree.prepareChildSeedColumns()` 直接把父节点当前 `restrictedColumnIds` 复制给 child 的 `seedColumnIds`。child 进入 `LP.construct()` 时先使用这些父节点传下来的列，只过滤全局 preprocessing 禁弧，不按当前 branch/subtree forbidden arc 提前筛掉列。若 child 初始 LP 可行，`PC.solve()` 后续会调用 `resetRestrictedColumnsByCurrentReducedCost()`，这一步才按 `Node.isColumnCompatible()` 过滤当前 node 不兼容列，并保留正值列和低 reduced-cost 列。因此 wet030 Node 2 看到的 `4801` 条列，不是原始初始列，而是 root RMP 已经经过多轮 pricing 后留在父节点 restricted master 里的列。

关于“多测几个 30 job 算例”，当前工作区实际可直接触发 child subtree 场景的数据有限。`test-results/bpc` 下临时构造过 `tmp-wet020/021/022/025/030`，其中 `tmp-wet020`、`tmp-wet022`、`tmp-wet025` 在同口径下根节点已经闭合，不能进入 Node 2，也就不能观察 subtree child 的列池过滤和 exact pricing 暴涨。原始 `wet040_030_2m.dat` 可以进入更大规模场景，但已有 root-only half-domain allCycles 记录约 `225.547s`，不适合在当前问题上做四组 maxNodes=2 快速对照。因此本轮结论不是“所有 30 job 都统计显著如此”，而是基于同一个可复现 wet030 child 场景做严格隔离：逐项固定数据、pricing、ALNS、无向分支、K 上限，只改变 subtree 的落地方式。

四组同口径复跑如下：`tmp-wet030_001_2m`、half-domain、`completionBound=allCycles`、关闭 ALNS 初始上界、关闭无向相邻分支、`maxNodes=2`、`maxExactColumns=100000`。关注 Node 2 第一轮 exact pricing。

1. baseline off：不做 subtree arc elimination。整体 `solve=26.540s, exact=6.333s, pricing=48`。Node 2 第一轮 exact 为 `columns=4801, incompat=0`，`forbidden/pricingOnly arcs=1/0`，`machineDual=-9198.0`，`jobDual q0/q10/q25/q50/q75/q90/q100=446/488.3/836/1111/1347.25/1577.1/2407`，`forwardExtend=31083/59/0/31024/1483`，`fw kept=1442`，`forwardSink negative=45`，本轮加列 `45`。

2. pricing-only subtree：root 判掉的 `701` 条 arc 只在 pricing 中禁用，不写入普通 `arcState`，不建 master forbidden 行，也不触发 child column filter。整体 `solve=24.963s, exact=5.840s, pricing=45`。Node 2 第一轮 exact 仍是 `columns=4801, incompat=0`，`machineDual=-9198.0`，job dual 分位数与 baseline 完全一致，但 `forbidden/pricingOnly arcs=1/701`，`forwardExtend=27351/21691/0/5660/1276`，`fw kept=1244`，`forwardSink negative=16`，本轮加列 `16`。这组最干净地说明：同一 RMP/dual 下删掉这些 arc 确实减少扩展和负列，不会导致爆炸。

3. formal subtree on：subtree arc 正式写入普通 forbidden arc，并执行 child column filter。整体 `solve=70.318s, exact=49.380s, pricing=44`。Node 2 第一轮 exact 入口变成 `columns=2003, incompat=0`，`forbidden/pricingOnly arcs=702/0`，`machineDual=-8916.955`，`jobDual q0/q10/q25/q50/q75/q90/q100=0/410.45/808.67/1024.318/1327.977/1820.836/2701.864`，`forwardExtend=2154533/1674333/0/480200/198552`，`fw kept=121210`，`forwardSink negative=69952`，本轮加列 `69952`。这里 exact 时间和列数暴涨。

4. formal subtree + skip child column filter：subtree arc 写入普通 forbidden arc，master forbidden 行存在，但 debug 跳过 `resetRestrictedColumnsByCurrentReducedCost()` 的当前 node 兼容性过滤。整体 `solve=24.743s, exact=9.152s, pricing=42`。Node 2 第一轮 exact 为 `columns=5108, incompat=3704`，`forbidden/pricingOnly arcs=702/0`，`machineDual=-9617.5`，`jobDual q0/q10/q25/q50/q75/q90/q100=87.167/431.8/828.917/1074.5/1420.667/1858.933/2723`，forbidden row dual 已非零：`forbiddenNZ/absSum=47/12876`，`forwardExtend=832279/652735/0/179544/58191`，`fw kept=43644`，`forwardSink negative=13097`，本轮加列 `13097`。它比 pricing-only 慢，说明 master forbidden 行和对应 dual 也会扰动 pricing；但它远没有 formal on 的 `69952` 那么糟，说明灾难性爆炸还需要 column filter 把 child 列池压到 `2003`。

因此本轮更精确的结论是两层的。第一层，单纯“删 arc”不是问题；pricing-only 组在同一 dual 下更快，负列也更少。第二层，formal forbidden row 不是完全无影响；skip-filter 组已经出现 `47` 个非零 forbidden row dual，第一轮 exact 从 `16/45` 级别负列增到 `13097`。但最慢的 formal-on 还额外触发 child column filter，把父节点传下来的 4000 多列压成 2000 条残余图列池，导致 LP dual 形状明显改变，completion bound 通过量从 `1483/1276` 级别暴涨到 `198552`，最终单轮 exact 枚举 `69952` 条 forward->sink 负列。也就是说，最慢组不是只因为 dual “不稳定”，而是 forbidden row dual 扰动与 child residual column filter 叠加；其中 column filter 是把问题从中等变慢推到灾难级的触发点。

后续若继续验证泛化性，需要补充更多能进入 child 的独立 30-job 数据，或者把现有大算例切出多个不同 30-job 子样本。仅靠当前 `tmp-wet020/022/025` 无法证明，因为它们根节点已闭合，不经过 subtree child。工程上当前最稳的策略仍是：subtree arc elimination 先保留为 pricing-only 或诊断；正式继承前，需要单独设计 residual seed columns 或修改 child column filter，不要把 reduced-cost fixing arc 和普通 branching forbidden arc 完全共享一套 RMP seed/filter 语义。

### 41.14 2026-06-04 进一步澄清 child 初始列、补造 30-job 样例和 machineDual 单变量诊断

用户追问 4000/2000 多列的差距到底发生在哪一步。本轮重新核对日志和代码后，流程可以更准确地写成：root 最初只有 `Initial columns=2`，随后 root heuristic pricing 把 pool 从 `2` 补到 `4677`，exact pricing 又补到 `4824/4871/4896`，root 最后 pool/restricted columns 为 `4896`。branch 时 `Tree.prepareChildSeedColumns()` 把父节点当前 `restrictedColumnIds` 原样复制给 child，因此 Node 2 日志开头显示 `seed=4896`。child `LP.construct()` 仍先用这些 seed 列建初始 LP，只过滤全局 preprocessing 禁弧，不按当前分支或 subtree arc 提前筛。

真正造成 4000/2000 差距的是 child 初始 LP 可行后的 `PC.solve()` 路径：若 depth>0 且没有打开 `debugSkipBranchColumnFilter`，会调用 `LP.resetRestrictedColumnsByCurrentReducedCost()`。这个函数先执行 `if (!isColumnCompatible(column)) continue;`，再按当前 LP reduced cost 和正值列筛正式 child restricted set。subtree-off 时，Node 2 第一轮 exact 入口仍有 `columns=4801`；formal subtree-on 时，root 判掉的 `701` 条 arc 被写入普通 `arcState`，大量父节点列变成不兼容，Node 2 第一轮 exact 入口只剩 `columns=2003`。注意 formal-on 日志中全局 pool 在 exact 前已经到 `5709`，因为 heuristic pricing 仍加了一些列；但 exact summary 的 `nodeDiag columns=2003` 统计的是当前 LP restricted columns，不是全局 pool。因此 4896 到 2003 的差距确实主要发生在 child column filter 和后续残余图补列不足这一段。

为回应“单个例子不足”的问题，本轮从 `data/40-2` 中重新截取两个 30-job 临时样例：`tmp-wet030_from040_002_2m.dat` 和 `tmp-wet030_from040_003_2m.dat`。生成方式是保留原始 40-job 文件前 30 个 job，并截取 setup 矩阵的 `0..30` 行列子矩阵；两个文件第一行均为 `30 2`，避免把 `wet040_030_2m.dat` 这种“030 是算例编号”的 40-job 文件误当成 30-job。`from040_002` 在 baseline off 下 root 直接闭合：`ROOT_PROCESSED, obj=bound=12883, solve=10.477s, exact=2.064s`，因此不能用于 child subtree 结论。`from040_003` 能进入 Node 2：baseline off 为 `NODE_LIMIT, solve=75.542s, exact=26.244s`，formal subtree-on 为 `NODE_LIMIT, solve=84.924s, exact=34.598s`。它也显示 formal-on 更慢，但没有原 `tmp-wet030_001_2m` 那种 7 万负列的灾难级放大。

`from040_003` 的 Node 2 第一轮 exact 更像一个“温和复现”：baseline off 为 `columns=4641`、`forbiddenJobArcs=1`、`machineDual=-8616.33`、`fw kept/dominated=30660/3776`、`forwardSink negative=22`、`forwardExtend=660057/8/0/660049/34435`、time `6012ms`；formal-on 为 `columns=4636`、`forbiddenJobArcs=46`、`machineDual=-8527.0`、`fw kept/dominated=26825/3104`、`forwardSink negative=38`、`forwardExtend=578185/22486/0/555699/29928`、time `5638ms`。也就是说，这个样例的 formal-on 并没有把 child restricted columns 大幅压低，反而列数接近 baseline，所以没有复现 2003 列残余冷启动；总 exact 变慢主要来自后续轮次 label/dominance 较重。由此当前结论应收窄：原 wet030 的灾难级慢确实和 child column filter 把列池压到 2003 强相关，但不是每个 30-job 子样例都会触发同样强度。

关于 machineDual，当前数据只能说明它是强敏感因素，不能单独定因。原 wet030 最慢组的 `machineDual=-8916.95`，baseline-off 是 `-9198.0`，差约 `281`。在 half-domain pricing 里 source frontier 会 `shiftYInPlace(-lp.getMachineDual())`，因此 machineDual 改变会整体平移所有 route 的 reduced cost；理论上这足以把大量接近 0 的 forward->sink 列推成负列。为了测试这一点，本轮给 `LP.getMachineDual()` 增加了 debug override：`twet.bpc.debugMachineDualOverride` 和 `twet.bpc.debugMachineDualOverrideNode`，并给半域 exact 增加 `twet.bpc.debugStopAfterGeneratedCandidates` 诊断早停开关。默认不开，不改变正常求解。

实际尝试把 baseline-off 的 Node 2 machineDual 覆盖成 formal-on 的 `-8916.954545454555` 后，完整运行超过 5 分钟仍未结束，进程终止前 CPU 约 `404s`、内存接近 `1.9GB`；把候选早停设为 `1000` 仍没有快速返回。这个实验说明 Node 2 pricing 对 machineDual 覆盖非常敏感，但它不是干净的单变量 exact 试验：override 会影响 Node 2 的 heuristic pricing、completion bound 构造和 exact pricing，而不只是第一轮 exact 的 source 常数。因此不能据此断言“就是 281 的 machineDual 差导致 69952 列”。更严谨的下一步应做一个 exact-one-shot harness：加载已知 child 的 filtered restricted columns 和 LP dual，冻结 jobDual/arcDual/列池/forbidden arcs，只替换 GCNGBB exact 内部 source/completion-bound 使用的 machineDual，再比较第一轮 label 漏斗。

当前最稳判断是：machineDual 是能造成大规模 reduced-cost 平移的候选机制；但原 wet030 的实证因果链更完整地表现为“subtree formal 写入 -> child column filter 把父列池压成残余图列池 -> LP dual 包括 machineDual/jobDual 重新定价 -> completion bound 通过量和 forward->sink 负列暴涨”。如果要把 machineDual 单独确认为主因，还需要上面的 exact-one-shot harness，而不是直接在完整 BPC 流程里覆盖 `LP.getMachineDual()`。

### 41.15 2026-06-04 child 初始 LP、列筛选和不可行 repair 的真实时序

用户指出“子节点的列不是重新挑选一部分吗”，本轮重新核对 `Tree`、`PC` 和 `LP` 的代码后，结论是：child 的列确实会重新挑选，但不是在 child 第一次 LP 建模之前，而是在第一次 child LP 解完之后。这个时序很关键。

child 生成时，`Tree.prepareChildSeedColumns()` 直接把父节点当前 `parentLp.getRestrictedColumnIds()` 复制到 `child.seedColumnIds`。child 出队后，`Tree` 用 `lp.construct(node, node.seedColumnIds)` 建模；`LP.construct()` 此时只做 `node.isColumnPreprocessingCompatible(column)` 检查，也就是只过滤全局静态 preprocessing forbidden arc，不按当前分支 forbidden arc、required arc 或 subtree arc elimination 的 forbidden arc 提前筛列。这样做的目的，是先用“父节点列集 + 新分支行”判断 child 初始 RMP 是否可行。

若 child 第一次 `solveRelaxationTimed(lp, "initial")` 可行，`PC.solve()` 才调用 `LP.resetRestrictedColumnsByCurrentReducedCost()`。这一步才是正式的 child 列筛选：先用 `isColumnCompatible(column)` 按当前 node 的 forbidden arc 过滤不兼容列，再保留当前 LP 正值列，并按 reduced cost 从低到高补到 `branchSeedColumnLimit`，条件为 reduced cost 小于 `branchSeedReducedCostAllowance`。因此 `4801 -> 2003` 这类差距发生在 child 初始 LP 可行之后、正式 pricing 之前。

若 child 初始 LP 不可行，`PC.solve()` 会进入 `repairInfeasibleMaster()`。repair 的做法是把 `lp.setFeasibilityRepairMode(true)` 打开后重建同一个 node 的 LP，此时 `LP.buildModel()` 会调用 `addFeasibilitySlacks()`。这个 slack 不是给所有约束加，而是只给当前 child 新增的 repair branch 行加：机器上下界、arc required/forbidden、无向相邻 required/forbidden 或 tariff branch。coverage 不加 repair slack，仍然依赖 pricing/外包变量修复。repair LP 解出 slack dual 后，`PC` 调用各 pricing engine 的 `findFeasible(lp)` 生成能减少 slack 的列；每加一批列就 `resolveCurrentModelTimed(lp, "repair_after_pricing")`。若 slack 归零，再调用 `resetRestrictedColumnsByCurrentReducedCost()` 筛正式 child 列集，关闭 repair mode，并用 `"repair_final"` 重解正常 RMP。若达到 `maxBranchRepairColumns` 后 slack 仍为正，则 child 判不可行。

所以完整时序是：child 先继承父节点 restricted columns；第一次 LP 不提前按当前分支筛列；可行则筛正式列后 pricing；不可行则只对当前新分支行加人工 slack，用 slack dual 引导 pricing 补列，repair 成功后再筛正式列。这个流程来自旧 VRP 的 `UpdateRouteSet/FindFeasible` 思路，但当前 subtree arc elimination 复用普通 forbidden arc 后，会在这个“repair 或初始 LP 之后的正式筛列”阶段把大量历史列过滤掉，这是 wet030 formal subtree-on 变慢链条中的关键时序点。

进一步看 `LP.construct()` 里的 `isColumnPreprocessingCompatible()`，在正常父子继承路径上确实大多是冗余检查：父节点 restricted columns 本身应该已经满足全局 preprocessing forbidden arc，pricing 新增列也会受当前定价图约束，不应包含这些全局静态不可行弧。因此如果只考虑“父节点当前 restricted columns 传给 child”这一条干净路径，重复检查没有实际筛选价值。

但它仍有防御性意义。`LP.construct()` 是 RMP 建模入口，不只表达父子继承语义；历史列池、调试运行、配置切换、初始 incumbent/外部 seed 或未来新增的列来源，都可能把旧口径下生成的列传进来。全局 preprocessing forbidden arc 是所有节点都应遵守的硬不可行信息，放在入口再过滤一次，可以避免这些 stale columns 被重新放回 RMP。当前这一步不检查普通 branch/subtree forbidden arc，因此不会破坏 child “先继承父列集带新分支行试可行性”的设计；它只兜底全局静态预处理禁弧。若后续追求极致效率，可以加诊断统计确认该过滤长期命中为 0，再考虑删除或改成断言。

关于 formal subtree-on 日志里的 `nodeDiag columns=2003`，需要更正一个容易误读的点：这个数不是 `resetRestrictedColumnsByCurrentReducedCost()` 刚筛完后的列数，而是 Node 2 第一轮 exact pricing 入口的 restricted columns 数。Node 2 在进入 exact 前，heuristic pricing 已经连续加了 `343+202+133+93+30+12=813` 条列。因此刚经过 child column filter 后的列数应约为 `2003-813=1190`，而不是 2003。这个 1190 仍然超过默认 `branchSeedColumnLimit=1000`，原因应是筛选逻辑会先无条件保留当前 LP 正值列，只有在正值列不足 `maxColumns` 时才用低 reduced-cost 候选补足。当前日志没有单独输出 positive/candidate/selected 计数，因此还不能确认 1190 中正值列的精确数量；若后续要彻底验证，应在 `resetRestrictedColumnsByCurrentReducedCost()` 增加一次性统计。

### 41.16 2026-06-04 扩充 30-job 子样例的 subtree 开关对照

为继续验证 subtree arc elimination 开关在多个 30-job 样例上的影响，本轮从 `data/40-2/wet040_004_2m.dat` 到 `wet040_008_2m.dat` 又截取了 5 个真 30-job 临时样例：`tmp-wet030_from040_004_2m.dat` 到 `tmp-wet030_from040_008_2m.dat`。生成方式仍是保留前 30 个 job，并截取 setup 矩阵 `0..30` 行列子矩阵。随后按 half-domain、`completionBound=allCycles`、scalar 开启、`maxNodes=2`、subtree 开关唯一变化的口径，完成了 `004..006` 的成对复跑；`007/008` 在 baseline 路径下几分钟内没有完成成对结果，本轮先中止，不作为结论样本。

`from040_004` 强复现了原 `tmp-wet030_001_2m` 的坏模式。subtree off 为 `solve=17.703s, exact=4.810s, pool=5975`，subtree on 变为 `solve=21.288s, exact=9.074s, pool=23083`。Node 2 第一轮 exact 入口列数从 `4709` 降到 `1355`，forbidden job arc 从 `2` 增到 `737`，machine dual 从 `-9077.0` 变为 `-8227.5`；同时 `fw kept` 从 `2795` 增到 `30701`，`forwardSink negative` 从 `37` 增到 `16898`，本轮 exact 时间从约 `584ms` 增到 `3745ms`。这组说明：当 formal subtree 明显压缩 child restricted columns，并且 dual 形状尤其 machine dual 明显变化时，会再次出现大量 forward-only 负列和 pool 暴涨。

`from040_005` 是重要反例。subtree off 为 `solve=21.345s, exact=6.020s, pool=5028`，subtree on 反而变快为 `solve=13.266s, exact=2.901s, pool=5102`。Node 2 第一轮 exact 的列数同样从 `4841` 降到 `2013`，forbidden job arc 从 `2` 增到 `634`，但 machine dual 基本稳定在 `-7871.0 -> -7884.6`；`fw kept` 从 `1893` 降到 `425`，`forwardSink negative` 保持 `38`，本轮 exact 时间从约 `833ms` 降到 `166ms`。这组说明：列数缩小本身不必然导致 label 暴涨；若 dual 没有被推到有害区域，subtree 删弧仍可能按理论预期加速。

`from040_006` 在两种开关下都根节点闭合，不适合观察 child subtree 行为。off 为 `ROOT_PROCESSED, solve=13.311s, exact=2.669s, pool=5530`，on 为 `ROOT_PROCESSED, solve=10.370s, exact=2.333s, pool=5530`。这只能说明 root-only 路径下 subtree 开销不明显，不能用于判断 child restricted column filter 的影响。

把原 `tmp-wet030_001_2m`、`from040_003` 和本轮 `from040_004/005/006` 放在一起看，结论需要比前面更精确：subtree formal-on 的效率变化不是单调的。原 `001` 和新 `004` 都表现为 child 列池被大幅压缩后，machine dual/dual 形状改变，把大量 forward->sink 路径推成负 reduced-cost 列，导致 exact pricing 和全局 pool 暴涨；`003` 只是温和变慢，因为列数几乎没降；`005` 则证明即便列数从 4841 降到 2013，只要 dual 没有发生有害平移，删弧仍会减少 label。因此当前更稳的判断是：machine dual 的 200 到 800 量级变化很可能是放大器，但不是唯一条件；灾难级变慢来自 subtree forbidden arc 继承、child column filter 和重新定价后的 dual 形状共同作用。

本轮可复现输出保存在 `test-results/bpc/tmp-wet030-from040-004-006-baseline-off-n2-half-current.csv`、`test-results/bpc/tmp-wet030-from040-004-006-formal-on-n2-half-current.csv` 及同名目录。后续若要继续扩大样本，应优先挑能进入 child 的 30-job 子样例，或者降低 `007/008` 的运行预算口径后先拿到 Node 2 第一轮 exact summary；否则 root 闭合样例不能回答 subtree child filter 的问题。

### 41.17 2026-06-04 继续扩样本后的运行分布和使用判断

用户要求继续多造 30-job 样例，并把单次运行限制放宽到 10 分钟。本轮从 `data/40-2/wet040_009_2m.dat` 到 `wet040_015_2m.dat` 又截取了 7 个 30-job 临时样例：`tmp-wet030_from040_009_2m.dat` 到 `tmp-wet030_from040_015_2m.dat`。生成方式仍是保留前 30 个 job，并截取 setup 矩阵 `0..30` 行列子矩阵。

这批样例的第一观察是：很多截样例已经明显重于前面的 `004..006`，不适合继续用同一个 `maxNodes=2` 口径快速拿成对统计。`from040_009` 在 subtree off 和 formal-on 两侧都 600 秒未完成；`from040_011` 也是两侧 600 秒未完成；`from040_013`、`from040_014`、`from040_015` 的 baseline off 都在 600 秒内没有完成，因此没有继续耗对应 formal-on。`from040_012` 本轮未继续跑，因为前面连续重样例已经说明这批数据分布偏重。这个结果本身说明，继续盲目截取 40-job 前 30 个 job 会混入很多根/子节点极重样例，难以作为快速参数对照集。

`from040_010` 是本轮唯一完成 off/on 成对的样例，但两侧 `valid=false`，因此只能作为 pricing 行为参考，不能当作严格算法优劣结论。总体结果为：subtree off `NODE_LIMIT, solve=67.376s, exact=11.453s, pool=6213, valid=false`；formal-on `NODE_LIMIT, solve=67.812s, exact=8.746s, pool=5996, valid=false`。从 Node 2 第一轮 exact 看，formal-on 把 forbidden job arcs 从 `2` 增到 `35`，machine dual 从 `-10217.0` 变到 `-10324.0`，入口列数基本相近 `5109 -> 5114`，`fw kept` 从 `3107` 降到 `2260`，`forwardSink negative` 从 `132` 降到 `12`，本轮 exact 时间从约 `1243ms` 降到 `964ms`。这组和 `from040_005` 一致：当 formal subtree 没有把 child restricted columns 压成残余冷启动，并且 machine dual 没有朝有害方向大幅平移时，删弧本身会减少 forward-only 负列和 label。

把目前所有可用样例合起来看，subtree arc elimination 的使用判断更清楚了。第一，作为纯 pricing 图删弧或在 dual 稳定的 formal-on 场景下，它确实能减少 label 和负列，例如 `005`、`010`。第二，一旦 formal-on 同时触发 child column filter 大幅压缩历史列池，并把 machine dual/其他 dual 推到有害区域，就会出现 `001`、`004` 那样的灾难级 forward->sink 负列暴涨。第三，还有一批 `009/011/013/014/015` 在当前口径下 10 分钟都不能完成 baseline，对这些样例继续谈 subtree 开关优劣意义不大，应该先改实验设计。

因此当前不建议把 subtree arc elimination 无条件作为普通 branch forbidden arc 正式继承。更稳的工程策略是分层使用：默认先作为 pricing-only elimination 或 local arc fixing 使用；只有在 child 重新筛列后 restricted columns 没有大幅下降、machine dual 相对父/无 subtree 参考没有明显有害平移、或 residual seed columns 足够覆盖被删弧后的替代路径时，才允许 formal-on 进入普通 forbidden arc 语义。若要进一步自动化，需要在 `resetRestrictedColumnsByCurrentReducedCost()` 后输出 selected/positive/candidate/incompatible 计数，并给 subtree formal-on 加一个保护条件，例如列池保留率过低或 machine dual 跳变过大时回退为 pricing-only。

### 41.18 2026-06-04 010 与 600 秒超时样例的状态区分

用户追问“求解不动”到底卡在哪里。本轮重新检查 `009/010/011/013/014/015` 的结果目录、CSV 和日志后，需要先区分两类现象。`from040_010` 不是求解不动，它两侧都有完整 CSV 和 `halfDomain.log`，日志中已经打印 `BPC Summary`，状态均为 `NODE_LIMIT`。baseline off 为 `solve=67.376s, exact=11.453s, restricted integer heuristic=35.835s`，formal-on 为 `solve=67.812s, exact=8.746s, restricted integer heuristic=39.251s`。因此 010 的主要时间不在 exact pricing 卡住，而是在每个节点后的 restricted integer heuristic；两侧都正常处理了 2 个节点后按 `maxNodes=2` 截断。

010 的 `valid=false` 是另一件事，不能和“卡死”混在一起看。日志显示 baseline 在 Node 2 的 restricted integer heuristic 找到 `obj=17060` 并更新 incumbent，formal-on 在 Node 2 的 restricted integer heuristic 只得到 `obj=17723`，没有优于 root incumbent `17599`。CSV 中两侧 `valid=false` 说明最终解校验没有通过，因此这组不能作为严格的算法优劣结论；但 pricing 行为仍有参考价值：formal-on 在 Node 2 第一轮 exact 中把 `forbiddenJobArcs` 从 `2` 增到 `35`，入口列数基本不变 `5109 -> 5114`，machine dual 只从 `-10217.0` 到 `-10324.0`，`fw kept` 从 `3107` 降到 `2260`，`forwardSink negative` 从 `132` 降到 `12`。这说明在没有残余列池冷启动、dual 没有朝有害方向大幅移动时，subtree 删弧仍可能减少 label。

真正“600 秒不动”的是 `009/011/013/014/015` 这批重样例。当前落盘证据显示这些目录大多没有正式 `halfDomain.log`，也没有 CSV；也就是说进程在外层 600 秒限制内没有完成一次可写 summary 的运行。由于当前日志只在每轮 pricing 完成后打印，而不是在 pricing/LP/MIP 开始时写 heartbeat，所以这些空目录无法精确定位到“卡在某一条 exact pricing、root restricted integer heuristic、还是某个 Java 运行阶段”。能确定的是：它们不是像 010 那样已经跑完 2 个节点后 `NODE_LIMIT`，而是完整实例级运行未返回。继续用这批样例盲跑 subtree 开关，信息密度很低。

后续若要判断“求解不动卡在哪里”，需要先改诊断口径，而不是继续扩大样本。建议在 `Tree`/`PC` 周围加开始/结束 heartbeat：node 开始、initial LP、repair、heuristic pricing、exact pricing、cut、restricted integer heuristic、subtree elimination、branch，每个阶段进入时也写一行，并刷新日志。这样即使 600 秒被杀，也能从最后一条 start heartbeat 看出卡在 exact pricing 还是 restricted integer heuristic。当前从 010 看，restricted integer heuristic 已经是显著大头；对 009/011/013/014/015，要先拿到阶段 heartbeat 后再决定是否降低 integer heuristic 频率、给 MIP 加时间上限，或只跑 root/Node2 第一轮 pricing 诊断。

### 41.19 2026-06-04 010 root restricted integer heuristic 可行性和列筛选诊断

用户追问 `RMIH` 的整数解为什么不应天然就是最终可行解。本轮新增独立诊断类 `HEU.RestrictedMasterIntegerFilterDiagnostic`，不改正式 `Tree` 和 `RestrictedMasterIntegerHeuristic` 流程，只复用 010 root LP/pricing 后的列池和 reduced cost，对 root restricted columns 做不同列筛选规模下的二进制 set covering / set partitioning MIP 对照。诊断配置复用 `GCBBAsymmetricComparisonTest` 的随机 seed 公式，因此 root LP 与历史 010 对齐：`root_lp=16148.8`，`restricted_cols=5341`，LP 正值列 `8` 条。

最关键的验证结果是：历史 root RMIH 的 `obj=17599` 可以被诊断复现，但它不是原问题意义下的可行 partition。使用 `>=` 覆盖、`rc2000 + jobK10`、`rc3000 + jobK10`、`rc4000 + jobK10` 或全量 `5341` 列时，最优解均为两条列 `[3321, 4523]`，目标值 `17599`，但重复覆盖 job `[19, 29]`，`duplicate_cover=2`。两条列分别为：

`3321:[4, 19, 25, 22, 7, 8, 1, 9, 29, 3, 11, 13, 27, 18, 5, 12]`

`4523:[17, 2, 10, 15, 20, 16, 30, 29, 14, 28, 23, 26, 19, 21, 24, 6]`

也就是说，`>=` 的 MIP 认为所有任务至少覆盖一次，因此可行；但原问题/validator 要求任务不能被两个内部列重复服务，所以该 incumbent 应拒绝。对应的 `==` 覆盖在 `rc4000 + jobK10` 和全量 `5341` 列下可行，最优解为 `[301, 2700]`，目标值 `17723`，无重复覆盖。`rc2000 + jobK10` 在 `==` 下不可行，说明只保留 2000 条低 reduced-cost 列尚不足以拼出合法 partition；但 `>=` 已经能拼出低目标的 covering 解，这正是当前 `RMIH` invalid incumbent 的来源。

这也解释了为什么“三角不等式 + set covering”不能直接保证 RMIH integer incumbent 合法。三角不等式只能支持“单条路径内部若存在绕路/重复访问，理论上可用更短路径替代”这类局部支配；它不能保证两个已经选中的固定列之间不会共享 job。RMIH 面对的是一个固定列池，不能自动把列 `3321` 或 `4523` 中的 job 19/29 删除后重新生成成本更低且时间窗可行的新列。即使这种修剪后的列在完整列空间中存在，也不一定已经在当前 restricted pool 里；如果不在池里，set covering MIP 无法用它替换。因此，LP 主问题为了对偶语义使用 `>=` 可以成立，但用于更新 incumbent 的整数启发式必须额外保证原问题 partition 语义，至少要在更新前通过 validator。

列筛选规模方面，本次 root 诊断得到一个很清晰的阈值。`rc500 + jobK10` 在 `>=/==` 下均不可行；`rc1000 + jobK10` 在 `>=` 下可行但目标 `19025` 且重复覆盖 2 个 job，`==` 不可行；`rc2000 + jobK10` 在 `>=` 下达到历史 invalid 目标 `17599`，`==` 仍不可行；`rc4000 + jobK10` 在 `==` 下首次得到合法目标 `17723`，全量 `5341` 列下的合法 `==` 目标仍是 `17723`。因此对 010 root 来说，按 reduced cost 排序保留列时，`4000 + 每 job 前 10 条` 已经能达到全量合法解质量；`2000` 太少，只能得到 covering 可行但 partition 不可行的低目标解。

求解时间也支持“先筛列，再整数化”的方向。诊断 MIP 为了隔离列选择和覆盖语义，省略了原 RMIH 中 no-outsourcing 情况下仍存在的 tariff segment/baseline 变量，因此绝对时间低于历史 `RestrictedInteger node=1 time=23.95s`，但相对趋势仍有参考价值：`rc2000 >=` 用 `1.47s`，首次 incumbent `0.42s`；`rc4000 >=` 用 `5.31s`，首次 incumbent `1.09s`；全量 `5341 >=` 用 `5.62s`，首次 incumbent `0.24s`。`==` 口径在有合法解后反而更快：`rc4000 ==` 用 `0.319s`，全量 `5341 ==` 用 `0.437s`。这说明当前慢不只是“列多”，还和覆盖模型允许重复覆盖后产生更复杂的 covering 组合有关；合法 partition 口径未必更慢。

文献上，这类 restricted master heuristic 一般也不是把所有生成列无脑丢给 MIP。Joncour 等的 column-generation primal heuristic 综述指出，restricted master heuristic 通常在列子集上解静态 IP，列子集可以来自 LP 过程生成列、启发式列或二者混合；列选择可使用 reduced cost、约束满足贡献或 LP 解值，且 restricted integer problem 常因列集不足而不可行。Cavaliere、Bendotti 和 Fischetti 的 CVRP restricted set partitioning heuristic 更具体：先选成本较好的 5000 到 10000 条 route 作为 core set，再用 reduced cost filtering，并在每个 customer 上保留 reduced cost 最小的一批 route，最终 restricted SP 用商业 MIP 求解但设置 aggressive time limit 和 polishing。这和本轮 `reduced cost 前 N + 每 job 前 K` 的方向一致。

当前工程建议因此分两层。第一层先保证 correctness：`RMIH` 用于更新 BPC incumbent 前，必须满足原问题可行性；对 no-outsourcing/内部列 partition 场景，覆盖应使用 `==1`，或者至少在 `Tree` 更新 incumbent 前调用 validator，失败则只记录诊断、不更新上界。第二层再做加速：先保留 LP 正值列、已有 incumbent 列，再按 reduced cost 升序保留前 `N` 条，并对每个 job 保留 `K=10` 条覆盖该 job 的低 reduced-cost 列；从 010 root 的结果看，`N=4000,K=10` 已达到全量合法目标，`N=2000,K=10` 不足。后续若要把该策略正式接入，应继续在更多能进入 child 的 30-job 样例上记录 `N/K/列数/合法目标/首次 incumbent 时间/总 MIP 时间`，再决定默认阈值和是否给大列数场景加时间限制。

### 41.20 2026-06-04 010 RMIH 正式口径复核与重复覆盖修复诊断

用户指出上一轮 `RMIH` 诊断把 no-outsourcing 情况下仍存在的 `y_j`、tariff segment active 和 baseline 变量省掉了，因此不能拿简化 MIP 的绝对时间解释正式 `RestrictedMasterIntegerHeuristic`。本轮已修正诊断类 `HEU.RestrictedMasterIntegerFilterDiagnostic`：整数模型恢复原 `RMIH` 口径，包含内部列二进制变量、外包 `y_j` 变量、tariff segment active 变量、baseline 变量、外包 baseline 等式、segment active 等式和 segment 上下界；同时保留 `>=` / `==` 覆盖对照。root LP 仍为 `16148.8`，restricted columns 仍为 `5341`，因此列池口径和上一轮一致。

正式口径复核后，`>=` 覆盖仍稳定复现 invalid incumbent：`rc2000+jobK10` 为 `17599`，用时 `2.505s`，首次 incumbent `0.734s`；`rc4000+jobK10` 为 `17599`，用时 `9.727s`，首次 incumbent `1.836s`；全量 `5341` 为 `17599`，用时 `10.005s`，首次 incumbent `0.475s`。三者选中的都是列 `[3321,4523]`，重复覆盖 job `[19,29]`。因此 `010 valid=false` 的主因没有变化：当前 `RMIH` 的 set covering 整数解满足 `>=1`，但不满足原问题/validator 的 partition 语义。

正式 `==` 覆盖结果也更清楚：`rc2000+jobK10` 不可行；`rc4000+jobK10` 和全量 `5341` 均可行且目标为 `17723`，用时分别为 `0.466s` 和 `0.653s`，选中列 `[301,2700]`。这说明“直接 `==` 不一定更慢”，在 010 root 上反而比 `>=` covering 更快，但列太少时确实可能不可行。`rc500+jobK10` 在 `>=/==` 下都不可行；`rc1000+jobK10` 的 `>=` 可行但目标 `19025` 且重复覆盖 job `[7,29]`，`==` 仍不可行。

用户提出的“满足三角不等式时，重复 job 可以从某条列里删掉，成本应不增；但有限列池里未必已经有删点列”这一判断被本轮修复诊断支持。诊断做法是：先解正式 `>=` MIP，找到被重复覆盖的 job；对当前 `>=` 解中包含这些重复 job 的列，枚举删除重复 job 的非空子集，用 `TWETColumnEvaluator` 重新计算删点序列成本并加入列池；随后在原筛选列加修复列上解正式 `==` MIP。结果为：`rc2000`、`rc4000`、全量 `5341` 都只需新增 `6` 条修复列，`==` 复解即可得到合法 partition，目标 `16444`，用时约 `0.20s/0.51s/0.62s`，无缺失、无重复覆盖。`rc1000` 新增 `6` 条修复列后得到更好的合法目标 `16412`，用时 `0.066s`。对应 `rc1000` 解保留列 `4455`，并新增删掉重复 job 29 和 7 后的列 `5346`。

这组结果有两个含义。第一，三角不等式不能自动让 set covering 的整数 incumbent 合法，因为 MIP 只能使用当前有限列池；但从重复覆盖解生成删点列，确实可以把 covering incumbent 转成 partition 候选，并且在 010 root 上比直接等式列池解更好。第二，列数和上界质量不是单调关系：`rc1000` 的修复结果 `16412` 优于 `rc2000/full` 的修复结果 `16444`，说明修复后仍需重新求整数 MIP，不能只按“更多 reduced-cost 列一定更好”判断。

当前工程建议调整为三步。第一，正式 `RMIH` 不能直接用 `>=` 解更新 incumbent；至少要在更新前做 validator，失败则不能刷新上界。第二，若保留 `>=` 作为寻找低成本 covering 结构的启发式，应在发现重复覆盖后生成删点修复列，并用 `==` 覆盖复解一次；这个修复比直接 `==` 更稳，因为它能补出有限列池里缺失的删点列。第三，列筛选仍建议从 LP 正值列、低 reduced-cost 前 N 条和每 job 前 K 条开始；010 root 上 `rc1000+jobK10+repair` 已能得到强合法上界，`rc500` 太少不可行，`rc4000/full` 会增加 `>=` 证明时间但修复后质量未更好。正式接入前应再把该 repair 逻辑放到更多 node 上测试，并记录 `>=` 首 incumbent 时间、修复列数、repair `==` 时间和 validator 结果。

### 41.21 2026-06-04 当前 RMIH 修正后复跑 010 的 subtree 结论

在 RMIH 改为 branch-free 全局上界启发式，并采用 `>=` 找重复结构、删点补列、最终 `==` 复解之后，重新按同口径复跑 `tmp-wet030_from040_010_2m`。配置仍为 half-domain、`completionBound=allCycles`、`maxNodes=2`，分别关闭和开启 formal subtree arc elimination。当前结果已经和旧日志明显不同：两侧均 `valid=true`，说明旧的 `valid=false` 确实来自原 RMIH 把 `>=` 重复覆盖解当作 incumbent，而不是 pricing 或 subtree 本身破坏了最终解。

baseline off 当前为 `NODE_LIMIT, incumbent=16281, bound=16148.8, gap=0.8120%, solve=23.407s, exact=7.174s, heuristic=10.224s, RMIH=4.959s, pool=6221, valid=true`。formal-on 当前为 `NODE_LIMIT, incumbent=16444, bound=16148.8, gap=1.7952%, solve=21.120s, exact=6.057s, heuristic=7.587s, RMIH=5.502s, subtree=0.823s, pool=6058, valid=true`。也就是说，在当前合法 RMIH 口径下，formal-on 仍然略省时间，主要省在 heuristic pricing 和 exact pricing；但它没有在前两个节点内找到 baseline off 的 `16281` 上界，因此如果只看 `maxNodes=2` 结果，baseline 的上界质量更好。

从 Node 2 第一轮 exact pricing 看，subtree 的行为仍符合此前判断。baseline off 的入口约为 `columns=5109`、`forbiddenJobArcs=2`、`machineDual=-10217.0`、`fw kept=3107`、`forwardSink negative=132`、本轮 exact 约 `990ms`。formal-on 继承了 root 后 `350` 条 subtree arc，Node 2 第一轮入口为 `columns=4286`、`forbiddenJobArcs=351`、`machineDual=-10099.5`、`fw kept=2894`、`forwardSink negative=72`、本轮 exact 约 `553ms`。这不是 `from040_004` 那种残余列池冷启动导致负列暴涨的坏模式；相反，subtree 这次确实减少了负 sink 列、候选列和 exact 时间。formal-on 总 exact 从 `7.174s` 降到 `6.057s`，也支持这一点。

当前 010 更准确的结论是：subtree 对 pricing 是有帮助的，但对有限节点预算下的启发式上界质量不一定有帮助。formal-on 删除 arc 后，Node 2 的候选空间更窄、总列池更小，RMIH 在 Node 2 找到的合法解为 `16818`，没有优于 root 的 `16444`；baseline off 在 Node 2 则通过修复后的 RMIH 找到 `16281` 并更新 incumbent。由于 RMIH 现在是 branch-free 全局启发式，这个差异不是因为 formal-on 加了 branch 约束，而是因为 subtree 改变了后续 pricing 生成的列池，有限 restricted pool 中可组合出的全局上界不同。

因此 010 不能再作为“subtree 导致 invalid”或“求解卡死”的例子。它现在是一个温和的 tradeoff 样例：formal-on 更快、列池更小、pricing 指标更好，但 `maxNodes=2` 内上界略差；baseline-off 慢约 `2.3s`，但找到更好的合法 incumbent。后续若要判断 subtree 是否值得默认开启，应同时记录 wall time、exact/heuristic/RMIH 时间和最终上界，而不能只看 exact pricing 秒数。若目标是尽快拿强上界，可以考虑在 subtree-on 路径下保留更多 root/Node2 低 reduced-cost 列给 branch-free RMIH，或者让 RMIH 使用一个不受 subtree 删弧过度影响的全局候选列视图。

关于 `009/011/013/014/015` 的“求解不动”，当前结论仍不变：它们和 010 不是同一类现象。010 当前和旧版都有完整 CSV/log，只是旧版上界非法；新复跑更证明它能在 20 多秒内返回。`009/011/013/014/015` 的对应目录仍基本为空，没有正式 `halfDomain.log` 和 CSV，说明外层 600 秒内没有完成一次可写 summary 的运行。这不是统计脚本漏读 010 那种问题，而是运行没有返回到写结果阶段。由于当前日志缺少阶段 start heartbeat，仍不能断言它们卡在 root exact pricing、heuristic pricing、RMIH，还是某个初始 LP/Java 阶段。30 个任务并不意味着难度接近；这些实例是从不同 40-job 数据截前 30 个 job 得到的，setup/time-window/外包结构可能让 root 列生成或 restricted MIP 难很多。下一步应先加 heartbeat 或做更小口径隔离运行，例如 `maxNodes=1`、禁用 RMIH、只跑 root exact pricing，才能判断 011 到底是哪一阶段不返回。

### 41.22 2026-06-04 011 heartbeat 隔离和 subtree 适用条件复核

按上一节建议，本轮给 `Tree` 和 `PC` 增加了默认关闭的阶段 start heartbeat，并在 `GCBBFullDomainComparisonTest` 中暴露 `stageHeartbeat`、`enableConsoleOutput`、`enableRestrictedMasterIntegerHeuristic` 和 `restrictedMasterIntegerTimeLimit` 等 JVM property。这个诊断只在阶段进入时向 console 打印当前 node、phase、restricted 列数、pool 和 cut 数，不改变 LP、pricing、RMIH 或 branching 逻辑。这样即使外层 600 秒超时导致没有 CSV，也能知道最后进入了哪一个长阶段。

对 `tmp-wet030_from040_011_2m` 做 `maxNodes=1`、subtree off、RMIH 开启的 root 诊断后，运行先经历多轮 heuristic pricing，restricted 列数从 2 增至 4777。后期 heuristic pricing 单轮已经变重，出现约 `1.1s` 到 `3.4s` 的多轮小幅补列；随后 heartbeat 显示进入 `pricing.GCNGBBStyleBidirectionalPricing.start`，之后超过 60 秒没有返回。本轮中止进程前没有进入 RMIH heartbeat，因此 011 当前“求解不动”的第一卡点不是 RMIH，也不是 subtree，而是 root exact pricing 单次调用。

为了排除“heuristic 先生成了 4777 条列导致 exact 变难”的可能，又在同一 011 root 上关闭 heuristic pricing。此时 initial LP 后直接进入 `pricing.GCNGBBStyleBidirectionalPricing.start`，restricted 仍只有 2 条列，但 60 秒内同样没有返回。这说明 011 的重不只是列池过大造成的，而是该实例 root exact pricing 本身的状态空间很难。30 个任务数量相同并不意味着定价难度接近；setup、time window、外包成本和 LP 对偶会共同决定 label 数、completion-bound 剪枝率和负 reduced-cost 路径库存。当前已有证据足以说明：`009/011/013/014/015` 那类空目录不是统计漏读，而是至少 011 在 root exact pricing 内部长时间没有完成。

这也把 010 和 011 的性质分开了。010 当前代码下能在 20 多秒返回，旧日志中主要耗时来自非法 `>=` RMIH 证明和修复前的上界口径；011 则还没到 RMIH 阶段就卡在 exact pricing。因此后续若要处理 011，应优先看 exact pricing 内部剪枝，而不是继续调 RMIH。可选方向包括：给 exact pricing 增加内部 heartbeat 或阶段计数，输出 completion-bound build、forward/backward label 扩展和 join 的开始/结束；对重样例限制 root exact 的返回列数或加入安全时间阈值；或者先用 pricing-only/local arc fixing 降低 exact 的状态空间。继续盲跑 subtree 开关意义不大，因为 subtree 只有在 root LP/pricing 和上界更新之后才会作用到 child。

当前对 subtree arc elimination 的使用判断也更清楚。它有用的情形是：删弧没有把 child restricted pool 压成冷启动，或者 dual 尤其 machine dual 没有朝有害方向大幅平移；这时 forbidden arc 能直接减少 label、负 sink 列和 exact 时间，典型例子是 `from040_005` 和当前修正后的 `from040_010`。它没用甚至有害的情形是：formal-on 通过普通 forbidden arc 语义触发 child column filter 大幅删掉历史列池，并把 dual 推到使大量 forward-only 长路径为负 reduced cost 的区域，典型例子是 `tmp-wet030_001_2m` 和 `from040_004`。还有一种中间情形是当前 010：subtree 对 pricing 更快，但有限节点预算内的全局 RMIH 上界略差，因为 formal-on 改变了后续能生成的列池。

因此 subtree 不能只按“理论上删弧减少图规模”来决定是否默认正式继承。当前更稳的口径仍是：优先作为 pricing-only/local fixing 使用；若要 formal-on，应加保护指标，例如 child column filter 后列池保留率、machine dual 跳变、Node 2 第一轮 `forwardSink negative` 和 `fw kept` 的异常阈值。若这些指标显示 residual graph 冷启动，就应回退为 pricing-only，避免把 subtree fixing 当成普通 branch forbidden arc 直接继承。

### 41.23 2026-06-04 011 求解不动的 exact pricing 内部原因

按用户要求，本轮不再把 011 简单归结为“exact pricing 没返回”，而是在 `GCNGBBStyleBidirectional` 内部补了默认关闭的 heartbeat。该 heartbeat 在 `diagnosticStageHeartbeat=true` 时输出 initialize、forward、backward、join 和 finalize 阶段边界，并在 forward/backward 扩展内部按间隔输出队列规模、已弹出 label 数、保留/支配 label 数、扩展候选数、completion-bound 剪枝数和 join pair 数。该诊断不改变 pricing 逻辑，只用于判断单次 exact pricing 卡在哪个内部环节。

`tmp-wet030_from040_011_2m` 在当前正式启发式开启、`maxNodes=1` 的 root 诊断中，先经过 18 次 heuristic pricing，restricted 列数从 `2` 增到 `4777`。前半段 heuristic pricing 每轮约 `57ms/266ms/277ms/...`，后半段只补 `88/65/26/5/4/2/2` 条列时，单轮已经升到约 `3.2s-4.4s`，说明 heuristic pricing 在接近耗尽时也有明显拖累。但真正的“求解不动”发生在 heuristic 停止之后：程序进入 root `GCNGBBStyleBidirectionalPricing` 后，内部 heartbeat 显示仍处于 forward 扩展阶段，还没进入 backward 和 join。中止前计数已经达到 `fwQueue=129397`、`fwPops=13130`、`fwKept=142628`、`fwDom=197623`、`fCand=340250`、`fBoundSurvivors=340250`、`cbFPruned=0`。这说明 011 的当前卡点是 root exact pricing 的 forward label 状态空间持续膨胀，而不是 RMIH、subtree、cut 或 master LP。

为了区分“heuristic 生成 4777 条列导致 dual/列池太难”和“011 root exact 本身就难”，又在同一 011 上关闭 heuristic pricing。该口径下 exact pricing 从 restricted `2` 条列开始，前几轮能快速返回并持续产生大量负列，pool 逐步膨胀。到 restricted `7188` 后的一轮 exact 中，完整返回耗时 `26712ms`，单轮生成达到 `100000` 列上限，内部统计为 `fw kept/dominated=12677/35322`、`bw kept/dominated=5617/9424`、`forwardExtend candidates/constructed/boundSurvivors=47998/47998/47998`、`join pairs tried=30942974`、`generated candidates=1007963`。这说明即使没有 heuristic 先扩池，011 也会在 root exact pricing 中出现千万级 join 和百万级候选列；它不是统计漏读，也不是 RMIH 卡住。

同样 30 个任务下 010 为什么能跑动，关键不是 job 数，而是 LP dual、时间窗/动态 horizon、completion-bound 剪枝率和负 reduced-cost 路径库存不同。已有当前正式 010 日志显示，root 第一轮 exact 在 `completionBound=allCycles` 下只用约 `1.4s`，`fw kept=1702`、`bw kept=2372`、`join pairs tried=177496`、`forwardExtend candidates=40273`，completion bound 构造约 `775ms`，并剪掉 `fwPruned=38261`、`bwPruned=52280`。换言之，010 的 forward/backward 状态在 completion bound 下被压住了，join 也停留在十万级；011 的诊断则显示 completion-bound 剪枝为 0 或根本没有在当前正式 off 口径下启用，状态增长能直接推到十万 label、千万 join。30 个任务只是维度相同，不能保证定价图、对偶和可行时间结构同难度。

这也解释了为什么当前应把 011 的优化优先级放在 exact pricing，而不是继续调 RMIH。RMIH 的调用点在 `Tree.solve()` 中，必须等 `PC.solve()` 完成 LP pricing loop、cut 分离后才执行；011 正式诊断还没走出 root exact pricing，因此 RMIH 根本没有机会开始。若要让 011 返回，直接方向应是：给 root exact pricing 启用 `completionBound=allCycles` 或 local/pricing-only arc fixing；给 exact pricing 增加列数/时间安全阈值，避免一次性追求耗尽队列；或者先让 heuristic pricing/RMIH 在 exact 之前刷新一个足够强的上界后再进入 exact。继续只比较 subtree formal-on/off 没有意义，因为 subtree 作用在 child，011 当前卡在 root。

补充说明：上一段中的 `cbFPruned=0` 来自内部 heartbeat 的短诊断口径，该口径没有显式传入 `twet.bpc.fullDomainCompare.completionBound=allCycles`，因此实际按配置默认值 `off` 运行。它只能说明“completion bound 未启用时 011 的 exact forward 会快速膨胀”，不能证明 all-cycles completion bound 本身无效。2026-06-04 随后显式用 `completionBound=allCycles`、half-domain、`maxNodes=1`、heuristic pricing 开启和内部 heartbeat 重跑 `tmp-wet030_from040_011_2m`。运行经过 18 轮 heuristic pricing 后进入 `pricing.GCNGBBStyleBidirectionalPricing`，只打印到 `[BPC exact heartbeat] phase=initialize.start`；约 90 秒仍没有 `initialize.done`，随后手动停止。这说明在 011 上把 completion bound 打开后，当前主要长耗时前移到 exact 初始化中的 completion-bound 构造/预处理阶段，尚未走到 forward/backward label 扩展，也就看不到 `fwPruned/bwPruned` 的实际剪枝收益。

因此更准确的判断是：011 的问题不是“completion bound 开了但完全没效果”，而是两个口径下卡点不同。`completionBound=off` 时，exact 能进入 forward，但 label 状态快速膨胀；`completionBound=allCycles` 时，理论上可能剪掉后续状态，但 all-cycles 补全下界本身在该实例和该对偶状态下构造成本很高，短时间内没有完成初始化。后续若要判断 all-cycles 是否值得，需要给 completion-bound builder 自身增加内部 heartbeat，例如 forward/backward 补全 DP 的 candidate、queue pop、merge 次数和耗时；否则只能看到 `initialize.start`，无法区分是前向补全、后向补全、聚合，还是某个函数 merge 卡住。

### 41.24 2026-06-04 011 all-cycles completion bound 构造慢的直接原因

继续给 `CompletionBoundCalculator` 增加默认关闭的 builder 内部 heartbeat 后，011 的卡点进一步明确。显式使用 half-domain、`completionBound=allCycles`、`maxNodes=1`、heuristic pricing 开启，root 在进入 exact pricing 后，completion-bound builder 能正常打印 `build.start.ALL_CYCLES` 和 `allCycles.forward.seed.done`，随后长期停留在 `allCycles.forward.loop`。这说明不是某个单次 `mergeMinimum` 调用死锁，也不是构造前置对象卡住，而是 all-cycles 前向松弛队列一直有有效改进。

011 在 60 秒附近的 builder 计数为：`queue=8`、`fPop=50589`、`fCand=1467082`、`merge=2934128`、`changed=533780`，仍没有 `allCycles.forward.done`。队列规模一直只有个位到十几个，但相同 job 状态被反复重新入队，说明函数下包络持续被新的循环路径压低。对照 010 的同口径 root 运行，all-cycles builder 很快结束：第一轮 forward completion bound 约 `280ms`，`fPop=251`、`fCand=7309`、`merge=34050`、`changed=11757`；后续几轮也只有 `fPop≈228..240`、`bPop≈313..318`，整轮构造在 `0.69..1.14s`。因此 011 和 010 的差别不是 builder 通用实现慢，而是 011 的 reduced-cost/time 结构让 all-cycles 松弛出现大量循环改进。

这也修正了“它只是简单 Bellman-Ford DP”的理解。当前 all-cycles completion bound 不是按“每个 job 最多松弛 n 轮”的标量最短路；它维护的是按 job 聚合的 piecewise-linear 下包络，且允许重复访问 job。由于 reduced cost 中 job dual 很大，某些 job-job 循环在有限时间 horizon 内可以持续降低下包络。时间 horizon 虽然有限，避免了真正的无界负无穷，但会变成按时间/函数段数量增长的伪多项式传播；在 011 上表现为几十万次有效 merge change，远超 010。当前没有证据表明是 Java 线程死循环或单个函数操作卡死，更像是 all-cycles relaxed bound 对该实例过松并遇到了负循环式改进。

由此得到的下一步判断是：若要让 011 可跑，不能简单认为 all-cycles 一定便宜。可以先尝试三类修正。第一，给 completion-bound 构造加安全阈值或超时，超过阈值时回退为 `off` 或更弱但可控的 scalar/local arc fixing，避免 exact 初始化被 bound 构造吞掉。第二，尝试 two-cycle 或更强的禁止短循环状态，但要注意当前 two-cycle 以前记录过构造更贵，不一定直接更好。第三，从理论上改成时间离散 DP 或限制循环长度/重复次数；这会改变 bound 的定义和成本，需要单独验证剪枝收益。当前最直接的工程修复方向，是保留 builder heartbeat，并在 all-cycles 构造超限时自动降级。

继续检查后，上一段“更像负循环式真实改进”的判断需要收紧。问题的直接原因不是 all-cycles 必然存在大量真实负循环改进，而是 `PiecewiseLinearFunction.mergeMinimum(candidate, direction, true)` 的 `reportChanged=true` 预判过宽：它会把数值等价或结构性合并也报成 changed，completion-bound builder 于是把同一个 job 反复重新入队。由于每次入队又会扩展 30 个后继，这个伪 changed 会被快速放大成百万级 candidate。

修正方式是在 `CompletionBoundCalculator.mergeFunction()` 中不再直接信任 `mergeMinimum(..., true)` 的 changed 返回值，而是先用 `candidateStrictlyImproves(current, candidate)` 做严格改进检查。检查逻辑只在 current/candidate 的真实重叠区间上扫描分段端点；如果 candidate 的定义域向 current 外扩展，或者在某个正长度重叠小段端点上严格低于 current，才认为它会带来新的下包络并允许入队。随后实际合并时使用 `mergeMinimum(..., false)`，避免再次触发宽松 changed 预判。这里利用的是分段线性差值在同一小段内仍为线性函数，若存在正长度内部严格改进，端点也能检测到；单点改进不会作为 completion-bound 后续传播状态。

修正后同口径复测 011：half-domain、`completionBound=allCycles`、`maxNodes=1`、heuristic pricing 开启、builder heartbeat 和 change audit 开启。root 完整返回，状态为 `NODE_LIMIT`、`valid=true`，总时间 `12.699s`，exact pricing `1.941s/4`。第一轮 all-cycles completion-bound builder 约 `488.501ms`，内部计数为 `fCand=7164`、`bCand=7975`、`fPop=246`、`bPop=275`、`merge=30268`、`changed=11727`；exact 阶段 `fwPruned=4267`、`bwPruned=11956`，最终 RMIH 找到合法 incumbent `14258`。这和修正前 60 秒仍停在 `allCycles.forward.loop`、`fPop=50589`、`fCand=1467082`、`merge=2934128` 的状态相比，说明根因就是 changed 误判导致的伪循环传播。

同口径复查 010 也正常：half-domain、`completionBound=allCycles`、`maxNodes=1`、console 关闭，结果为 `NODE_LIMIT`、incumbent `16444`、bound `16148.8`、`valid=true`，总时间 `10.948s`，exact pricing `2.661s/4`。因此当前修正没有破坏 010 的正常 completion-bound 剪枝，且把 011 从初始化阶段不返回修成可完成 root。

### 41.25 2026-06-04 mergeMinimum changed 语义问题分析

这次问题已经可以定位为 `PiecewiseLinearFunction.mergeMinimum(g, direction, reportChanged)` 的 `changed` 返回语义和 completion-bound builder 的使用需求不一致。`mergeMinimum` 本体负责把 `this` 改成 `min(this,g)`，这个合并操作本身仍然可用；真正出问题的是 `reportChanged=true` 时内部先调用 `willMergeMinimumChange(g)` 预判 changed，然后 completion-bound builder 把这个返回值当成“目标 job 的下包络真实变小，因此需要重新入队传播”。在队列型 DP 中，这个条件必须非常严格，否则一次伪 changed 会触发该 job 对所有后继的再次扩展，进而指数式放大。

`willMergeMinimumChange()` 的逻辑是：若 `g` 的物理定义域向 `this` 左右外扩，就直接返回 true；否则在公共区间上按当前两个函数的分段端点扫描，只要 `g` 在某个小段端点严格低于 `this`，也返回 true。这个判断对普通“我要知道 merge 后链表是否可能变了”比较宽松，但对 completion-bound 的队列重入不够准确。原因有三点。第一，定义域外扩并不一定意味着 completion-bound 后续传播所需的函数值下界变小，特别是在前后缀语义里，外扩出来的区间可能只是结构补齐或与后续有效时间无关。第二，`mergeMinimum` 随后还会 `normalize(direction)`，相邻同值段、前缀/后缀最小化和边界处理可能把预判中的结构差异消掉。第三，`willMergeMinimumChange()` 是合并前预判，而不是合并后比较；它不知道实际 `mergeMinimum` 是否只做了等值替换、分段切割或相邻段合并。

011 的现象正好符合这种误用：builder heartbeat 显示队列规模一直只有个位到十几个，但 `mergeChanged` 巨量增长，说明不是某个 job 一次性产生了巨大队列，而是同一批 job 被反复判为 changed 并重新入队。开启严格改进检查后，011 立刻从 60 秒仍在 `allCycles.forward.loop` 变成 root 12.699 秒完整返回，说明大量重入不是必要的真实下包络改进，而是 changed 预判过宽导致的伪传播。

因此当前代码选择不改 `mergeMinimum` 的通用语义，而是在 `CompletionBoundCalculator.mergeFunction()` 中加一层更严格的队列重入判定：只在 candidate 相对 current 有正长度区间上的严格值改进，或确实扩展了当前需要传播的定义域时，才调用 `mergeMinimum(..., false)` 并把该 job 入队。这个设计保留了 `mergeMinimum` 作为通用链表合并操作的兼容性，同时把 completion-bound builder 所需的“真实改进才传播”语义放在调用方控制。后续如果要从根上整理，可以把 `mergeMinimum` 的返回值拆成两个概念：`structureChanged` 和 `valueImproved`，避免其他调用方继续把二者混用。

进一步按用户指出的“同一 job 反复进出队可能是 changed 误判”检查，确实发现问题不在理论负循环，而在 `mergeMinimum(..., reportChanged=true)` 的返回值不能直接作为 completion-bound 队列重入条件。对 011 打开 `completionBoundChangeAudit` 后，每隔 10000 次 `mergeChanged` 复制 before/after 做分段精确比较；从 `mergeChanged=10000` 到至少 `230000` 的所有审计点都显示 before/after 没有任何严格下降，但仍被计为 changed 并触发入队。这说明前述“有效工作”的判断过宽，实际大量循环是 changed 假阳性。

修复方式没有改全局 `PiecewiseLinearFunction.mergeMinimum`，而是在 `CompletionBoundCalculator.mergeFunction()` 内部增加严格改善检查：只有 candidate 在公共定义域某个分段端点严格低于 current，或者真实扩展了定义域时，才执行 merge 并返回 changed；否则直接跳过，不入队。这样把 completion-bound 的传播语义收紧为“下包络真的变强才继续松弛”，避免把结构性等价合并当作改进。

修复后同口径重跑 `tmp-wet030_from040_011_2m`、half-domain、`completionBound=allCycles`、`maxNodes=1`。root 能完整返回，`valid=true`，总时间 `13.614s`。第一轮 all-cycles forward builder 从之前 60 秒仍未结束降为约 `203ms`，统计为 `fPop=246`、`fCand=7164`、`merge=30268`、`changed=11727`；后续 exact pricing 4 轮合计 `2.255s`，completion-bound 构造每轮约 `0.35..0.58s`，root RMIH `4.332s`，最终 root incumbent `14258`、bound `13525.866667`。这证明 011 求解不动的直接原因是 completion-bound 队列的 changed 假阳性，而不是 all-cycles bound 理论上必然很慢。

### 41.26 2026-06-04 mergeMinimum changed 返回值的最终修正

用户进一步指出，`changed` 是否真实发生变化应由 `mergeMinimum` 自身返回，而不是在 `CompletionBoundCalculator` 里额外加一层 `candidateStrictlyImproves()` 预筛。这个要求是合理的：如果调用方每次先扫一遍 candidate/current，再让 `mergeMinimum` 再扫一遍做实际合并，会把 completion-bound builder 内层最热路径变成双倍扫描；更重要的是，`mergeMinimum(..., true)` 这个 API 名义上已经承诺返回 changed，就不应让调用方再猜它到底是什么意思。

重新分析后，旧实现没有准确判断出“没发生变化”的直接原因有两个。第一，`willMergeMinimumChange()` 是合并前预判，它只看 candidate 是否可能在公共分段端点更低，或者是否扩展定义域；但实际 merge 后还会拆段、替换、相邻段合并和 `normalize(direction)`，预判里的结构差异可能被消掉。第二，实际 merge 条件使用的是 `f2 <= f1`，因此 candidate 和 current 完全等值时也会把 candidate 段替换进链表。对普通 lower envelope 结果来说这不改变函数值，但对 completion-bound 队列来说，如果这种等值替换被报成 changed，就会把同一个 job 重新入队，造成伪传播。

最终修正把 changed 判定收回到 `PiecewiseLinearFunction.mergeMinimum(g, direction, reportChanged)` 内部，并删除旧的 `willMergeMinimumChange()`。现在 `mergeMinimum` 在实际扫描小段 `[cur,nxt)` 时只在两类情况下设置 `changed=true`：其一，candidate 在该正长度小段上不劣且至少一个端点严格低于 current，此时才执行替换；其二，candidate 的物理定义域确实向 current 左侧或右侧扩展。若 candidate 和 current 只是等值，即使分段结构不同，也不替换、不置 changed。这样 `changed` 的含义变成“合并后 lower envelope 的可传播内容真的变强或定义域真的变宽”，而不是“链表可能被结构性改写”。

调用方也同步恢复为直接信任 `mergeMinimum(..., true)`：`CompletionBoundCalculator.mergeFunction()` 不再调用 `candidateStrictlyImproves()`，而是在 `current.mergeMinimum(candidate, direction, true)` 返回 true 时才增加 `mergeChanged` 并入队。为了继续排查异常，默认关闭的 `completionBoundChangeAudit` 仍保留；它只在抽样审计时复制 before/after，不属于正常路径成本。

这次还补了一个 PWLF 回归测试：等值 candidate 被拆成更多段时，`mergeMinimum(..., true)` 必须返回 false，且不能把 current 拆成更多段；严格更低的 candidate 必须返回 true。验证结果为：带 CPLEX jar 的 focused 编译通过；`PiecewiseLinearFunctionPropertyTest` 为 `passed=24, warnings=2, failed=3`，新增 changed-return 回归通过，剩余 3 个失败仍是历史已知的 disjoint `mergeMinimum` 契约外输入。另用 `tmp-wet020_001_2m` 做 root smoke，结果 `ROOT_PROCESSED`、`incumbent=bound=6343`、`valid=true`。尝试用 `TanakaNoOutsourcingBPCTest` 直接传 `-Dtwet.bpc.completionBound=allCycles` 重跑 011 时，发现该 runner 没有把该 JVM property 写入 `TWETBPCConfig.bidirectionalCompletionBoundRelaxation`，输出仍为 `completionBound=OFF`，因此这条命令不能作为 all-cycles 复核证据；前一轮 011 all-cycles 完整返回结果仍来自显式设置 config 的诊断口径。

### 41.27 2026-06-05 011 当前 HEAD 的 changed 来源复核

按用户要求，本次重新在 `tmp-wet030_from040_011_2m` 上做现场复核，而不是继续拿抽象例子解释。为避免再次混淆旧实现和当前实现，在 `CompletionBoundCalculator` 里新增默认关闭的 `twet.bpc.completionBoundChangeSource` 诊断。该诊断只在开关打开时复制 merge 前函数，并把 `mergeMinimum(..., true)` 返回 changed 的来源分成三类：公共正长度区间上有严格值下降、只发生物理定义域扩展、以及既没有严格下降也没有扩域的不可见 changed。普通运行不开这个开关，不增加热路径成本，也不改变 DP 逻辑。

用 `GCBBFullDomainComparisonTest` 显式设置 `completionBound=allCycles`、`maxNodes=1`、`mode=halfDomain` 重跑 011，当前代码完整返回：`status=NODE_LIMIT`，`valid=true`，`solve=29.923s`，exact pricing 4 次合计 `6.294s`，heuristic pricing 24 次合计 `10.832s`，RMIH 一次 `11.494s`。第一轮 all-cycles builder 统计为 `fCand=7164`、`bCand=7975`、`fPop=246`、`bPop=275`、`merge=30268`、`changed=11727`，其中 `changedClass=11607/0/0`；差额 120 次来自 forward/backward seed 阶段对空 current 的初始赋值，不进入 before/after 分类。后续三轮也分别为 `changedClass=10903/0/0`、`10864/0/0`、`10938/0/0`。这说明当前 HEAD 上，011 的 `mergeMinimum(..., true)` 没有再出现“没变化却 changed”的现场证据。

因此现在需要把两个阶段分清。旧的百万级 `fCand=1467082`、`merge=2934128`、`changed=533780` 是修复 changed 语义前或中间实现口径下的现象；它的直接原因是等值/结构性变化被当作 changed，导致同一 job 反复入队。当前 `mergeMinimum` 已经改成只在 `gNoWorse && gStrictlyBetter` 的真实严格改进小段上替换并置 changed，等值候选不会触发 changed；011 现场复核也支持这一点。也就是说，当前代码里已经不是“一个 Bellman-Ford DP 仍然迭代 100 多万次”的状态，之前的 100 多万次不能再作为当前 HEAD 的性能事实。

当前 011 仍然比 010 重，但瓶颈已经不是 completion-bound builder 的伪 changed 循环。按本次日志，root 总时间主要由 RMIH `11.494s`、heuristic pricing `10.832s`、exact pricing `6.294s` 构成；exact 内部每轮 all-cycles 构造约 `1.06s..1.81s`，并且能剪掉大量 label（第一轮 `fwPruned=4267`、`bwPruned=11956`）。如果后续继续优化 011，优先看 RMIH covering MIP 和 heuristic pricing 接近耗尽时的重复调用，其次才是 all-cycles builder 常数开销；不应再把当前 011 归因于 `mergeMinimum` changed 假阳性。

继续用旧提交 `82796d5` 的 changed 预判在 011 上复现第一条假阳性，可以看到真实错误并不是抽象构造例子。运行到 `mergeChanged=2025` 时，`job=1`、`direction=FORWARD`，旧实现返回 changed，但 before 和 after 的函数摘要完全一致。该次 `before` 的物理定义域为 `[60,1039]`，前两段为 `(60,147,a=0,b=9930.647058823535)` 和 `(147,157,a=-4,b=10126.411764705888)`；`candidate` 定义域为 `[147,1039]`，第一段为 `(147,244,a=-4,b=10126.411764705888)`。也就是说，从 `147` 开始 candidate 与 before 的右侧段完全相同，但旧 `willMergeMinimumChange()` 在定位公共域起点时使用 `while (p.end < start)`，没有跳过 `p.end == start` 的左侧段 `(60,147)`。随后它在零长度区间 `cur=nxt=147` 上比较 candidate 值 `-4*147+10126.411...=9538.411...` 和左侧常数段值 `9930.647...`，认为 candidate 更低，于是返回 true。

这个判断对 completion-bound 队列是错误的，因为 `147` 只是段交界点，不是一个可传播的正长度时间区间；在 `[147,157]` 以及后续公共正长度区间上，candidate 没有比 before 更低。实际 `mergeMinimum` 完成后，`after` 与 `before` 完全相同，却因为旧预判返回 true 触发了 job 重新入队。当前实现避免这个问题有两层：第一，changed 不再由单独的合并前 `willMergeMinimumChange()` 预判，而是在实际 merge 扫描中设置；第二，只有在 `Utility.compareLt(cur,nxt)` 的正长度小段上，且 `candidate` 不劣并至少一个端点严格更低时，才替换并置 changed。零长度边界点不会再触发 changed。

### 41.28 2026-06-05 mergeMinimum changed 与 normalize 的关系复核

继续复核用户提出的一个风险：`mergeMinimum` 在扫描阶段发现 candidate 更低并置 `changed=true`，但最后 `normalize(FORWARD/BACKWARD)` 做 prefix/suffix min 后，结果是否可能又和原函数等价。当前结论是：在 completion-bound 当前输入契约下，这种情况不应发生。原因是进入 `mergeMinimum` 的 current 本身已经按方向 normalize，forward 是 prefix-min 闭包，backward 是 suffix-min 闭包；candidate 也来自同一类 completion 函数传播，进入合并前已经是可传播的下包络函数。若在某个正长度小段上 candidate 严格低于 current，则取 `min(current,candidate)` 后该小段至少有一个端点严格低于原 current。forward normalize 只会把这个更低值向右传播，backward normalize 只会把它向左传播，不会把一个已经低于当前闭包的值抬回去。因此该严格下降不会被最终 normalize “吃掉”。

需要注意的是，这个判断依赖 current 已经是方向化闭包函数。如果 current 不是 prefix/suffix-min 后的函数，确实可能存在“局部更低但被已有更早/更晚的低值闭包覆盖”的场景；那属于 `mergeMinimum` 输入契约外。当前 completion-bound builder 中，target 函数每次 merge 后都会 normalize，后续 candidate 也由这些已闭包函数继续传播，因此满足这个前提。

本次补充了一个直接来自 011 旧假阳性的回归测试：`before` 左段 `[60,147]` 为常数高值，右段 `[147,244]` 与 `candidate` 完全相同，要求 `mergeMinimum(..., true)` 返回 `false` 且 merge 后函数值不变。这个测试覆盖的正是旧 `p.end==start` 边界点误判，而不是人工构造的等值拆段。重新运行 `PiecewiseLinearFunctionPropertyTest` 后，changed-return 回归通过；整体结果为 `passed=24, warnings=2, failed=3`，剩余失败仍是历史已知的 disjoint `mergeMinimum` 契约外输入和随机 disjoint 样例，不是 changed 判断引入的新问题。

### 41.29 2026-06-05 changed 修复后 all-cycles/two-cycle 与 30-job 卡点复测

按当前 HEAD 重新跑 `tmp-wet030_001_2m` root-only 对照，配置为 half-domain、`maxNodes=1`，只切换 `completionBound=allCycles/twoCycle`。当前 all-cycles 为 `NODE_LIMIT, valid=true, solve=13.735s, exact=3.475s/4, completion-bound buildMs 合计约 2.718s`；two-cycle 为 `NODE_LIMIT, valid=true, solve=32.089s, exact=22.098s/4, completion-bound buildMs 合计约 21.332s`。两者的 incumbent、bound 和列池规模一致，都是 `incumbent=15325, bound=15261.833333, pool=4896`。

这组结果说明 changed 假阳性修正后，two-cycle 仍然显著慢于 all-cycles。以前记录中 two-cycle build 合计约 `30.034s`，当前降到约 `21.332s`，可能确实受 changed 语义修正和运行环境波动影响；但 all-cycles 当前 build 约 `2.718s`，two-cycle 仍约为其 `7.85` 倍，solve 总时间差仍有约 `18.35s`。因此原先“two-cycle 的主要问题是二维状态传播导致 candidate/merge 数量大幅放大”的结论没有被推翻，只是差距幅度比旧日志小。当前 root 日志中 all-cycles 四轮 completion-bound internal 统计合计约 `fw=0.514s, bw=2.181s`；two-cycle 为 `fw=4.184s, bw=16.863s, agg=0.259s`，主要慢点仍在 backward/二维状态传播，而不是 final join 或 LP。

随后重跑此前没有完整返回记录的 30-job 截断样例，统一使用 half-domain、`completionBound=allCycles`、`maxNodes=2`，并显式关闭 `completionBoundSubtreeArcElimination=false` 和 `completionBoundSubtreeArcEliminationPricingOnly=false`。需要说明的是，日志里仍会出现 `subtreeArcElimination.start` heartbeat；这是 `Tree` 进入评估函数前的阶段打印，配置关闭时 `CompletionBoundSubtreeArcEliminator.evaluate()` 会直接返回不可用结果，不会把删弧正式继承到 child，也不会作为 pricing-only fixing 生效。

本轮 `009/011/013/014/015` 均能正常返回，不再复现“600 秒没有写 summary”的状态。结果分别为：`009 ROOT_PROCESSED, solve=9.454s, exact=2.402s/3, obj=bound=14065`；`011 NODE_LIMIT, solve=35.337s, exact=6.014s/6, incumbent=14258, bound=13525.866667, gap=5.135%`；`013 NODE_LIMIT, solve=18.842s, exact=8.651s/7, incumbent=14433, bound=14287.625, gap=1.007%`；`014 ROOT_PROCESSED, solve=17.598s, exact=10.858s/4, obj=bound=10288`；`015 ROOT_PROCESSED, solve=14.830s, exact=6.726s/5, obj=bound=13394`。所有结果均 `valid=true`。

从 pricing summary 看，这几个 30-job 样例仍然难度差别很大。`009` 很轻，最大 exact 规模只有 `fw kept=94, bw kept=258, join pairs tried=838`。`011` 已经明显变重，最大约为 `fw kept=461, bw kept=835, join pairs tried=20633`。`013` 的双向 label 和 join 都大，最大约为 `fw kept=2938, bw kept=3250, join pairs tried=265935`。`014` 和 `015` 的特点是 backward 侧很重，`014` 最大 `bw kept=11514, join pairs tried=244071`，`015` 最大 `bw kept=6508, join pairs tried=177822`。这解释了为什么同样是 30 个任务，运行时间和卡点完全不同：真正决定 exact pricing 难度的是 LP 对偶、时间窗、setup/processing 结构和 completion-bound 剪枝后的 label/join 状态规模，而不是 job 数本身。

当前对“之前求解不动”的判断也要更新。旧的 011/013/014/015 空目录不是统计脚本漏读，而是在旧 changed 语义或旧配置下确实没有返回到写 CSV；但在当前 changed 修复后，显式启用 all-cycles completion bound 并关闭 subtree，5 个样例都能返回，说明旧的长时间不返回很大程度上受 completion-bound builder 的 changed 假阳性影响。现在剩余的性能差异主要来自不同样例的 label/join 规模：013/014/015 仍比 009/011 重，但已经是几十秒内可控的重，而不是 DP 死循环或无限传播。

### 41.30 2026-06-05 当前 debug 清理与 subtree 四口径复测

本轮先清理前面为定位 011 和 subtree 慢点临时打开的诊断开销。`LP.getMachineDual()` 中的 `twet.bpc.debugMachineDualOverride` 覆盖入口已经删除，`GCNGBBStyleBidirectional` 中的 `twet.bpc.debugStopAfterGeneratedCandidates` 早停入口也删除。`GCNGBBStyleBidirectional` 的 nodeDiag 明细现在受 `TWETBPCConfig.diagnosticPricingSummaryDetails` 控制，默认关闭；`GCBBFullDomainComparisonTest` 只有在 `stageHeartbeat=true` 或显式 `pricingDiagnostics=true` 时才打开。`CompletionBoundCalculator` 的 changed audit/source 也改为只有开关打开时才复制 before 或调用分类函数，正常 completion-bound 构造不再有这些默认关闭诊断的热路径成本。focused `javac` 已通过。

随后用当前代码重跑 30-job 截断样例的四种 subtree 口径：`off`、`pricingOnly`、`formal`、`formalSkipFilter`。统一配置为 half-domain、`completionBound=allCycles`、`maxNodes=2`，并在本轮实验中显式打开 `pricingDiagnostics=true` 用于记录 node=2 明细。样例分两批：`004/005/006` 为补充样本，`009/010/011/013/014/015` 为前面讨论过的卡点样本。所有成功纳入结果的运行均 `valid=true`。其中 `006/009/014/015` 都在根节点闭合，因此 subtree 没有 child 可以发挥作用，这些样例上 subtree 主要表现为额外构造和检查开销：例如 `014` 从 off 的 `solve=24.077s/exact=14.300s` 变为 pricingOnly `26.645s/16.939s`、formal `26.861s/17.076s`；`015` 从 off 的 `23.155s/9.727s` 变为 pricingOnly `23.984s/10.960s`、formal `25.809s/11.140s`。

真正能判断 subtree 作用的是进入 child 的 `004/005/010/011/013`。整体结果如下。`004` 上 off 为 `solve=24.394s/exact=6.173s`，pricingOnly 为 `25.882s/5.354s`，formal 为 `29.010s/11.166s`，formalSkipFilter 为 `41.597s/17.235s`，四者 incumbent 都是 `14738`。从 node=2 第一轮看，off 只有 `n2Added=37, n2Fw=2795, n2Neg=37`；pricingOnly 在同一 RMP/dual 下把 `n2Added` 降到 `8`、`n2Fw` 降到 `983`；但 formal 变成 `n2Added=16898, n2Fw=30701`，formalSkipFilter 更是 `n2Added=59302, n2Fw=82459`。因此 004 是 formal subtree 明显有害的例子，且 skip-filter 更差，说明这里不是单纯“筛列太狠”，而是 formal forbidden arc 语义和 child dual/列池共同诱发了 forward-only 负列爆发。

`005` 则相反。off 为 `solve=30.064s/exact=8.492s/incumbent=12741`，pricingOnly 为 `27.506s/8.031s/12741`，formal 为 `23.036s/4.338s/incumbent=12740`，formalSkipFilter 为 `21.084s/5.003s/incumbent=12740`。node=2 第一轮中，off 为 `n2Added=38, n2Fw=1893`；formal 为 `n2Added=38, n2Fw=425`，formalSkipFilter 为 `n2Added=74, n2Fw=617`。这说明当 subtree 删弧没有触发有害 dual 平移和负列爆发时，formal 写入 child 可以实质降低 label 规模，同时还可能改善有限节点预算下的上界。

`010` 是典型 tradeoff。off 为 `solve=47.154s/exact=11.824s/RMIH=13.718s/incumbent=16281`，pricingOnly 为 `43.561s/10.794s/RMIH=10.320s/incumbent=16281`；formal 为 `51.137s/10.957s/RMIH=22.256s/incumbent=16444`，formalSkipFilter 为 `37.852s/7.675s/RMIH=11.874s/incumbent=16444`。node=2 第一轮中，formal/pricingOnly 的 pricing 指标不坏，formalSkipFilter 甚至端到端最快；但 formal 类结果的上界从 `16281` 变差到 `16444`。这说明当前 branch-free RMIH 虽然不加节点分支约束，但它仍依赖已经生成出来的 restricted column pool。formal subtree 改变 child pricing 和列池后，短预算内能组合出的全局上界可能变差。

`011` 当前已经不是“求解不动”样例，而是中等重的 child 样例。off 为 `solve=39.036s/exact=7.614s/RMIH=17.612s`，pricingOnly 为 `35.457s/7.152s/RMIH=13.747s`，formal 为 `44.588s/8.443s/RMIH=19.977s`，formalSkipFilter 为 `35.744s/6.715s/RMIH=15.732s`，四者 incumbent 都是 `14258`。这里 pricingOnly 和 formalSkipFilter 较好，formal 较差；主要差异不仅在 exact，也在 RMIH 时间。`013` 则强调了 skip-filter 的风险：off 为 `solve=28.324s/exact=12.359s`，pricingOnly 为 `26.551s/9.617s`，formal 为 `25.855s/9.792s`，formalSkipFilter 为 `57.935s/34.399s`。node=2 第一轮中，formal 只生成 `160` 个负列，`n2Fw=1589`；formalSkipFilter 生成 `36532` 个负列，`n2Fw=61333`，pool 从 `5674` 暴涨到 `42206`。因此 skip-filter 不是安全兜底，有些实例保留过多列会让 formal forbidden row dual 下的负列枚举直接爆炸。

从组件耗时看，subtree elimination 构造本身不是主要大头。进入 child 的样例中，subtree 扫描/应用通常约 `0.7s..1.8s`：例如 `010 formal=1.270s`、`013 formalSkipFilter=1.526s`。真正的大头取决于实例：`013 formalSkipFilter` 的大头是 exact pricing `34.399s`；`010 formal` 和 `011 formal` 的大头则是 RMIH，分别为 `22.256s` 和 `19.977s`；`004 formalSkipFilter` 同时有 exact 放大和 master LP/RMIH 小幅上升。也就是说，subtree 是否有用不能只看它固定了多少 arc，也不能只看 subtree 构造时间；要看固定 arc 写入 child 后对 dual、可用列池、forward sink 负列数和 RMIH 候选列组合的连锁影响。

当前比较清楚的结论为：`pricingOnly` 是最稳的 subtree 用法。它不改变 RMP 分支行和 child 列过滤，只在 pricing 图上禁用 subtree arc，因此一般不会破坏上界质量；在进入 child 的样例里，它对 004/005/010/011/013 的 exact pricing 多数有小到中等收益，最差也只是端到端略慢。`formal` 不是不能用，但不能默认无条件开启：它在 005、013 上明显有利，在 004、010、011 上会因为 exact/RMIH/上界质量而变差。`formalSkipFilter` 只适合作为诊断开关，不适合作为正式策略；004 和 013 已经说明跳过 child column filter 可能保留大量与 formal forbidden row dual 组合后非常负的路径，使 forward-only 负列爆发。

因此当前建议是：默认把 subtree arc elimination 作为 pricing-only/local fixing 使用；若要 formal-on，应增加保护条件。可观察指标包括 child 初始列池保留率、`nodeDiag columns/incompat`、machine dual 跳变、Node 2 第一轮 `forwardSink negative`、`fw kept`、以及 RMIH 上界是否被 formal 列池削弱。若这些指标出现 `004/013 formalSkipFilter` 这类负列暴涨，应该回退为 pricing-only。若类似 `005`，即 formal 后 `fw kept` 和 `forwardSink negative` 都显著下降且上界不差，则 formal 可以带来实质收益。根节点能直接闭合的样例不应作为 subtree 开关优劣证据，因为没有 child 继承阶段，额外开销自然更容易显得不划算。

### 41.31 2026-06-05 `PaperDominanceGraph` 关系查询爆炸原因复核

本轮继续追问 `tmp-wet030_from040_004_2m` 和 `tmp-wet030_from040_005_2m` 为什么在部分后续节点中卡入 `PaperDominanceGraph.findTerminalSupersetNodes()` / `findImmediateSubsetNodes()`。先排除一个误解：`004-off` 并不是根节点 90 秒还没做完。真实 node summary 显示 root 为 `20.601s`，node2 累计 `29.141s`，90 秒时已经处理到 node8；后续未完成的是 node9 内部的 exact pricing。线程栈两次采样分别落在 `findTerminalSupersetNodes()` 和 `findImmediateSubsetNodes()`，说明耗时点在 dominance graph 的 reachable-set 包含关系查询，而不是 CPLEX、completion-bound 构造或 RMIH。

`PaperDominanceGraph` 的设计是把同一个 terminal job 下的 label 按 `reachableSet` 组织成一个包含关系 DAG。插入新 label 时，如果没有相同 reachable key，先调用 `findTerminalSupersetNodes(target)` 从 roots 沿 successors 找所有 terminal superset candidate，用这些 candidate 的 `g` envelope 判断新 label 是否被支配；若未被支配，再创建新 node，并调用 `findImmediateSubsetNodes(newKey, predecessors)` 找 immediate subset successors，维护 DAG 的 transitive reduction。这个设计在 reachable-set 层次较清楚、每次查询访问少量 frontier node 时很快；但它没有按 cardinality、hash bucket 或 bitset trie 做索引，superset/subset 查询本质仍是图 DFS。当 active reachable key 很多、且大量 key 互相不可比较或有大量交叉包含关系时，每次插入都可能访问大量 graph node，累计复杂度会接近“插入 label 数 × 访问图节点数”。

从 `004-pricingOnly` 的 node9 可以看到该机制已经接近爆点，但仍被 pricing-only subtree 压住了。node9 的第一轮 exact pricing 有 `labels fw kept/dominated=11645/881`，`nodes created/deleted=11062/0`，`forwardExtend candidates=224392`，其中 completion bound 和 pricing-only arc 共剪掉大量扩展，但仍保留了约 1.1 万个不同 reachable key。对应 paper graph 统计为 `superset calls/visited=11866/138185`、`subset calls/visited=11062/384084`。第二轮仍有 `nodes created=10519`、`subset visited=341578`。这说明即使最终 node9 只用 `7.142s`，关系查询已经不是常数级动作，而是几十万次 bitset 包含判断加 HashSet visited 维护。`004-off` 在同一搜索区域缺少 pricing-only 禁弧压缩，后续 node9 的 reachable key 数和访问次数继续放大，于是线程长时间停在 `findTerminalSupersetNodes()` / `findImmediateSubsetNodes()`。

`005` 的现象类似但更晚暴露。`005-off` 前三节点分别为 `19.237s/12.120s/15.248s`，看起来正常，但后续节点采样落在 `findTerminalSupersetNodes()`。`005-pricingOnly` 前三节点比 off 明显改善，node2 从 `12.120s` 降到 `8.791s`，node3 从 `15.248s` 降到 `9.782s`；但后续节点仍采样到 `findImmediateSubsetNodes()`。因此 pricingOnly subtree 能减少一部分扩展和 graph node，但不能从根本上保证每个 30-job child node 都不会出现大量互不支配 reachable set。它缓解的是输入规模，不是 dominance graph 查询算法复杂度。

这也解释了为什么 formal 类策略有时更糟。正式禁弧不只是“图更少所以 bound 更强”，它还会改变 child RMP：一方面可能过滤历史列、改变 restricted column pool；另一方面 forbidden arc 行或 required adjacency 行会改变 LP dual，pricing 的 reduced cost 结构随之变化。`004-formal` 的 node2 已新增 `17066` 条 exact 列，node3 更是 `exact=123.477s/add35370`、`fw kept=139952`、`forwardSink visited=139898`，pool 到 `56383`。`formalSkipFilter` 的 node2 直接新增 `60451` 条列，pool/restricted 都到 `65843`。也就是说 formal 写入 child 后，虽然物理弧更少，但 dual 诱导出的负 reduced-cost 路径更多，且 reachable key 更分散，反而把 PaperDominanceGraph 推到更坏区域。005 的旧 maxNodes=2 结果里 formal 曾经有利，是因为该样例的 dual/列池变化没有诱发负列暴涨，反而降低了 node2 的 `fw kept`；这说明 formal 的收益高度依赖实例和节点，不适合无保护默认开启。

因此当前更客观的结论是：这不是 completion-bound DP 死循环，也不是 Java 死锁；是 exact pricing 中 paper dominance graph 的关系查询在某些节点进入了组合爆炸区。触发条件一般包括：LP dual 使大量 forward label 仍有负或接近负的潜力；completion bound/pricing-only arc 没有把扩展压到足够小；reachableSet 的包含关系分散，same-key 复用少，导致 `nodeByReachableSet` 命中少；以及 formal 分支行或列池变化让负列批量出现。后续若要继续优化，应优先考虑给 `PaperDominanceGraph` 加 finer-grained 计数和保护，例如按 cardinality 分层索引 superset/subset 查询、限制单次 exact pricing 返回列数或 label 数、对访问量异常的 node 回退为更粗的 dominance/heap top-K，或者让 subtree formal-on 受 child 列池保留率、第一轮 `fw kept/forwardSink negative/subset visited` 等指标保护。

### 41.32 2026-06-05 `PaperDominanceGraph` DFS 热点的精确优化方向

继续追问“是不是大部分时间耗在 DFS 找集合”时，需要分清全局和局部。对当前卡住的 004/005 后续节点，是的，线程采样反复落在 `findTerminalSupersetNodes()` 和 `findImmediateSubsetNodes()`，而且 004-pricingOnly node9 的完整日志已经显示，即使该节点能在 7.142s 内完成，两轮 exact pricing 也分别有 `superset visited=138185/127438`、`subset visited=384084/341578`。completion-bound 构造在同一节点只有约 `10..20ms`，因此当前卡点不是 bound DP，而是 dominance graph 为维护 reachable-set 包含关系做的大量 DFS 和 bitset 包含判断。这个判断只针对这些爆炸节点；根节点和轻节点仍可能主要耗在 heuristic pricing、RMIH 或 completion-bound 构造。

当前 `PaperDominanceGraph` 的查询没有集合索引。`findTerminalSupersetNodes(target)` 从 roots 出发，只沿 `reachableKey` 是 target 超集的 successor 往下 DFS，最后返回没有更深超集的 terminal superset；`findImmediateSubsetNodes(newKey, predecessors)` 从 roots 或 predecessor 的 successor 出发，沿 DAG 找 `reachableKey` 是 newKey 子集的节点，再用 `removeRedundantSubsetCandidates()` 保留极大子集。这个逻辑本身是精确的，但当 active reachable key 很多且交叉包含关系复杂时，每插入一个新 node 都要在 DAG 上重新搜索，subset 查询尤其容易放大。004-pricingOnly node9 第一轮 `nodes created=11062`，对应 `subset visited=384084`，已经说明平均每次插入都不是常数级操作；off/formal 在后续节点把 reachable key 数继续放大后，就会长时间停在这里。

如果要求“精确办法”，不应采用 label 上限、top-K、随机丢弃、近似 dominance 这类策略；那些只能作为保护开关，会改变列生成结果。比较稳的精确优化是保留现有 dominance 语义，只替换 superset/subset 候选枚举后端。

第一步建议做 cardinality 分层索引。每个 active `PaperDominanceNode` 按 `reachableCardinality` 放入桶。superset 查询只需要检查 cardinality 不小于 `target` 的桶，subset 查询只需要检查 cardinality 不大于 `newKey` 的桶。这个改动最小，结果完全一致，但剪枝力度有限，更多是低风险基线。

更有效的方案是给每个 terminal-job 的 `PaperDominanceGraph` 维护 inverted index：对每个 job bit `j`，维护所有 active node 中 `reachableKey` 包含 `j` 的集合。superset 查询 target 时，所有候选必须同时出现在 target 中每个 job 的 posting list 里。实现上可以用 `BitSet` 或 node id 集合，从 target 中 posting 最短的 job 开始，逐个求交，再用 `reachableKey.isSupersetOf(target)` 做最终校验。得到所有 active superset 后，再按当前语义过滤 terminal superset：若某个 candidate 存在 active successor 仍是 target 的超集，则它不是 terminal；否则保留。这样返回集合和原 DFS 一致，但枚举范围从“沿 DAG 访问大量节点”变成“posting 交集后的少量候选”。

subset 查询也可以精确索引化。`S ⊆ newKey` 等价于 S 不包含任何 `U \ newKey` 中的 job。维护 active node 全集和每个 job 的 posting 后，可先取 cardinality 不大于 `|newKey|` 的 active node，再减去所有 excluded job posting 的并集，剩下的候选再用 `isSubsetOf(newKey)` 校验。之后仍按当前 `removeRedundantSubsetCandidates()` 的语义保留极大子集，即如果候选 A 被另一个候选 B 超集覆盖，A 不作为 immediate successor。这里也可以继续用现有排序 pairwise 检查，第一版先保证正确；若候选仍大，再用同一个 superset index 在候选集合内判断是否已有 kept superset，避免 `O(k^2)`。

实现时要注意三点。第一，索引必须和 node active 状态同步；创建 node 时加入 cardinality 桶和各 job posting，删除 node 时可以急切移除，也可以 lazy 标记 inactive 并在查询过滤时跳过。第二，索引化查询初期应保留旧 DFS 作为 debug 对照开关，在小样例或抽样节点上比较旧结果和新结果的 node id 集合，确认 terminal superset 和 immediate subset 完全一致后再默认启用。第三，若 n 不超过 63，可以给 reachableKey 缓存一个 `long mask` 快速做 subset/superset；更通用的实现仍应保留 `PackedBitSet` 路径，避免后续 100 job 算例受限。

因此当前推荐的精确优化路线是：先给 `PaperDominanceGraph` 增加更细的统计，把 superset/subset 查询分别输出 calls、visited、candidate、terminal/immediate kept 和最大单次访问量；然后实现 indexed superset/subset backend，并用旧 DFS 做一致性断言；最后再复测 004/005 的 off/pricingOnly 后续节点。若索引后 node9 这类 `subset visited` 从几十万降到候选过滤级别，而 pricing 结果和列集合一致，就说明这条优化解决的是当前真实热路径。subtree、completion bound 仍然有用，但它们是减少输入规模；这个索引优化才是直接降低 dominance graph 集合查询复杂度。

### 41.33 2026-06-05 当前 DFS 方法内的低风险优化与耗时统计

用户指出上一节只用 visited 数量说明问题不够严谨，这个批评是对的。`visited` 只能说明集合关系查询规模大，不能单独证明 wall-clock 大头；真实判断应直接统计 `findTerminalSupersetNodes()`、`findImmediateSubsetNodes()` 和 single-point terminal-superset 查询内部耗时，并和 pricing summary 中的总时间、completion-bound 构造时间对比。

因此本轮没有先做倒排索引这类结构重组，而是在当前 DFS 方法内部做了三点小改。第一，新增默认关闭的 `twet.bpc.paperGraphTiming`，打开后在 pricing summary 中输出 `superset ms/max` 和 `subset ms/max`；默认关闭时不调用 `System.nanoTime()`，避免诊断本身污染热路径。第二，用 `PaperDominanceNode` 上的 `visitMark/startMark` 替代每次查询创建 `HashSet visited/startSet`，减少当前 DFS 的对象分配和哈希开销。第三，在 superset DFS 中先用 `reachableCardinality >= targetCardinality` 做必要条件，低 cardinality successor 不可能再成为 target 超集，其后继只会更小，因此可直接跳过；在 subset DFS 中只在 `reachableCardinality <= newCardinality` 时才调用 `isSubsetOf()`，避免对明显不可能的节点做 bitset 包含判断。上述三点都不改变 terminal superset / immediate subset 的语义，属于当前方法内部优化。

验证上，focused `javac` 已通过；`PaperDominanceGraphConsistencyTest` 通过 `cases=200, insertions=16000`，说明插入和支配结果未因 mark/cardinality 剪枝改变。随后用 `tmp-wet030_from040_004_2m` root-only、half-domain、`completionBound=allCycles`、`maxNodes=1`、打开 `twet.bpc.paperGraphTiming=true` 做 smoke。结果 `NODE_LIMIT, valid=true, solve=16.527s, exact=4.411s/4`。root 的第一轮 exact pricing 中，completion-bound build 为 `971.022ms`，paperGraph 为 `superset ms=8.119`、`subset ms=6.707`；后续几轮 paperGraph 查询也基本是毫秒级。这反过来说明 root 节点并不是 DFS 大头，不能用 root 的 visited 数或 paperGraph 计数推导后续卡点。

尝试继续跑 004 pricingOnly 到更多节点以采集 node9 的真实 timing 时，运行超过 2 分钟未返回；同时发现机器上仍有一个从 2026-06-04 开始的旧 Java 进程持续占 CPU。本轮没有擅自终止该旧进程，因此这次长跑不能作为严格性能对照，只能说明当前需要在干净环境下重跑卡点节点。后续要证明 004/005 后续节点是否真的大部分耗在 DFS，应使用 `twet.bpc.paperGraphTiming=true`、保留 node progress summary，并在无其他长跑 Java 进程干扰的情况下复跑到 node9 或对应卡点，再比较 `superset/subset ms` 与 exact pricing 总时间。

补充复核后确认，上述旧 Java 进程是 2026-06-04 留下的 `tmp-wet030_from040_011_2m` completion-bound/change-audit 诊断进程，不是外部程序。清掉该进程后，重新以 `tmp-wet030_from040_004_2m`、half-domain、`completionBound=allCycles`、pricing-only subtree、`maxNodes=10`、`twet.bpc.paperGraphTiming=true` 运行。由于 `GCBBFullDomainComparisonTest` 结束前才写 trace 文件，长运行过程中改用 `jcmd Thread.print` 做 JVM 栈采样。连续三次采样分别落在 `PaperDominanceGraph.findTerminalSupersetNodes()`、`PaperDominanceGraph.findImmediateSubsetNodes()`、再回到 `findTerminalSupersetNodes()`，调用链均为 `insertForward -> forwardExtend -> GCNGBBStyleBidirectional.solve -> pricing`。这给出了比 visited 数更直接的证据：当前复跑的长耗时确实发生在 exact pricing 的 forward label 插入 dominance graph 时的 superset/subset 查询，而不是 CPLEX、RMIH 或 completion-bound builder。

因此当前“求解不动”的直接机制可以更明确地表述为：某些 child 节点的 LP dual 和 pruning 结果使 forward label 大量保留；每个新 label 都要先找 terminal superset 判断是否被已有 reachable-set 支配，若未被支配还要插入新 graph node 并找 immediate subset successors 维护 transitive reduction。旧 004-pricingOnly node9 已显示 `fw kept≈1.1万`、`nodes created≈1.1万`、`forwardExtend candidates≈22万`，这会把 superset/subset 查询调用放大到一万多次。低风险 DFS 内部优化只能降低常数和无效 bitset 判断，不能改变“每个新 reachable key 都要维护包含关系 DAG”的算法阶数；若这仍不够，下一步才需要在保持语义一致的前提下给当前 graph 加更强的候选定位结构。

进一步按用户要求补充运行中时间证据。本轮给 `PaperDominanceGraph` 增加 `twet.bpc.paperGraphTimingHeartbeat`，只有同时打开 `twet.bpc.paperGraphTiming=true` 和 heartbeat 时才生效；默认不开，不进入 `System.nanoTime()` 诊断路径。heartbeat 不只在一次查询结束时打印，也会在 DFS loop 内按访问量检查时间，因此即使单次 superset/subset 查询很大、pricing 长时间不返回，也能从 console 看到累计耗时。

清空 Java 进程后，用 `tmp-wet030_from040_004_2m`、half-domain、`completionBound=allCycles`、pricing-only subtree、`maxNodes=10`、`paperGraphTimingHeartbeatIntervalMillis=2000` 复跑并手动停止。第一段长 pricing 的 heartbeat 给出了直接时间占比：`elapsedMs=24009.266` 时，`supersetSearchNanos≈9724.567ms`、`subsetSearchNanos≈9252.970ms`，两者合计约 `18.98s`，占 wall time 约 `79%`；`elapsedMs=40010.706` 时，superset 约 `22.040s`、subset 约 `10.678s`，合计约 `32.72s`，占 wall time 约 `81.8%`。后续 stats reset 后进入下一轮 pricing，2 秒 heartbeat 也显示 paperGraph 已占约 `1.22s`。这比“visited 数很多”的证据更直接，说明至少这个复跑卡点中，大部分 CPU 时间确实耗在 PaperDominanceGraph 的 superset/subset 查询上。

这同时也说明当前优化的边界：`visitMark/startMark` 和 cardinality 剪枝只是降低 DFS 常数。heartbeat 中最大单次查询已到 `superset max≈26.121ms`、`subset max≈29.036ms`，而调用次数达到十几万到二十几万级，累计时间才成为大头。后续如果仍要保持精确算法语义，真正有效的下一步不是继续微调 bitset cardinality，而是减少每次插入时需要枚举的 graph node 候选范围，例如在当前 graph 上增加可校验一致性的候选定位索引；否则只靠 DFS 内部常数优化很难把 80% 级别的时间占比压下去。

### 41.34 2026-06-05 当前 PaperDominanceGraph 还有哪些优化空间

当前还有优化空间，但需要区分两类。第一类是在现有 DFS 流程里的常数优化，例如把 `target.cardinality()` / `newKey.cardinality()` 改成从 label 或 node 传入的缓存值、减少 `LinkedHashSet` 遍历和对象分配、继续收紧明显不可能的 successor/predecessor 检查。这些改动风险低，但收益大概率有限，因为 heartbeat 已经说明大头不是单个 bitset 判断，而是大量 superset/subset 查询调用累计。

第二类是仍保持精确语义、但给当前 graph 增加候选定位索引。这不是改变 dominance 规则，而是把“从 DAG roots 做关系 DFS”换成“先用索引找可能的 superset/subset 候选，再用原有 bitset 关系和 terminal/immediate 规则校验”。比较可控的实现是按 reachable cardinality 分桶，再维护 job-bit inverted posting。superset 查询时只交集 target 中各 job 的 posting；subset 查询时从 active/cardi bucket 中排除所有不在 newKey 中的 job posting。最后仍保留旧 DFS debug 对照，抽样比较返回 node 集合完全一致后再默认启用。这个方向才可能显著降低 004/005 后续节点中 80% 级别的 paperGraph 时间占比。

因此当前建议不是继续堆更多 heartbeat 或只做微调，而是先保留 heartbeat 作为验证工具，然后实现一个默认可切换的 indexed backend。第一版目标应很明确：不改变任何列生成结果，只改变 `findTerminalSupersetNodes()` 和 `findImmediateSubsetNodes()` 的候选枚举方式；验证指标是 consistency test、旧/新 backend 抽样一致、以及 004/005 卡点节点的 superset/subset 累计耗时是否明显下降。

### 41.35 2026-06-05 indexed backend 为什么可能加速

当前 `nodeByReachableSet` 只能解决“完全相同 reachable set”的 O(1) 查找。例如新 label 的 reachable set 恰好已有 node，直接合并到同一个 node；但如果没有完全相同的 key，插入流程还必须找所有可支配它的 terminal superset node，以及插入新 node 后需要接到哪些 immediate subset successor。后两件事不是 hash map 能直接回答的，所以当前实现仍从 `roots` 或 predecessor successors 出发做 DAG DFS。

加索引的核心不是改变 dominance graph，而是给“包含关系候选”加一个快速入口。对 superset 查询，若目标集合为 S，则任何候选 R 必须包含 S 里的每个 job，并且 `|R|>=|S|`。因此可维护每个 job 对应的 active node posting list：`posting[j] = 所有 reachableKey 包含 j 的 node`。查询时先把 S 里各 job 的 posting 求交，得到的只可能是 S 的超集候选，再用 `isSupersetOf(S)` 和当前 terminal 规则做最终校验。这样原来可能从 roots 走到很多无关 node，现在先被 job-bit 交集缩到较小候选集。

对 subset 查询，若新集合为 S，则任何候选 R 必须不包含 S 外的 job，并且 `|R|<=|S|`。可从 cardinality 不大于 `|S|` 的 active node 集合开始，减去所有 `posting[j]`、其中 `j` 不在 S，然后再用 `isSubsetOf(S)` 和当前 `removeRedundantSubsetCandidates()` 的极大子集规则校验。这样不会把“候选枚举”交给 DAG DFS，而是先用集合索引排除大量显然不可能的 node。

这个方向预计能加速多少取决于 reachable set 的分布。如果目标 S 很小，比如只含 2 个 job，那么 posting 交集可能仍很大；如果 S 中 job 多一些，交集会明显收缩。004 卡点里 paperGraph 时间约占 80%，如果 indexed backend 能把 superset/subset 候选访问量降到原来的 20%..40%，端到端可能有 1.5x..2.5x 的改善；如果候选收缩到 10% 左右，则卡点节点可能从几十秒降到数秒级。但这是估计，必须用 heartbeat 对同一节点复测确认。最保守的判断是：常数微调只能带来小幅收益，索引候选定位才有机会带来数量级上的访问量下降。

在当前代码上的改动属于中等，不是推倒重写。需要新增 node id、active id 集合、cardinality bucket、job posting，并在 create/delete node 时同步维护；`findTerminalSupersetNodes()` 和 `findImmediateSubsetNodes()` 增加 indexed 分支，原 DFS 保留为 fallback 和 debug 对照；connect/disconnect 的 DAG 维护、`g/f/h` envelope、propagate/delete 语义都不用改。风险主要在删除 node 后索引同步、terminal/immediate 过滤是否和旧 DFS 完全一致。因此第一版应默认可切换，并在诊断模式下抽样同时跑旧 DFS 比较 node id 集合，确保结果一致后再用作默认。

### 41.36 2026-06-05 indexed/array backend 实现与第一轮实验

本轮按“先复制一份新实现再试”的方式新增 `IndexedPaperDominanceGraph`，并通过 `PaperDominanceGraphs` 统一创建和统计。默认仍是旧 `PaperDominanceGraph`；只有传入 `-Dtwet.bpc.paperGraphBackend=indexed` 才启用复制版。复制版中已经试过三类候选定位结构：job-bit posting / cardinality bucket、set-trie、superset cache。三者都保留为显式实验开关，但默认关闭，原因如下。

1. 对 root/轻节点，`nodeByReachableSet` 只负责完全相同 key 的查找，真正的 root 是 dominance graph 中没有 predecessor 的 maximal reachable-set node。当前 DFS 从这些 roots 往下找 target 的 terminal superset；如果某个 node 已经不是 target 的超集，它的 successors 只会更小，因此当前代码确实不会继续往下。这一点本身没有错，而且是 graph DFS 比全局索引更强的地方。

2. 无条件 job posting / cardinality bucket 在 004 多节点测试上明显失败。原因是 posting 只知道“哪些 node 包含某个 job”，不知道这些 node 是否处在当前 graph 的相关分支里；在 target 较小或 job 很常见时，候选会扩到全图。`tmp-papergraph-backend-n10-004-indexed` 手动停止前，110 秒 heartbeat 中 `superset visited` 已达 `631,054,336`、`subset visited` 达 `399,625,509`，远大于旧 DFS 口径，说明这种全局倒排索引不能无条件替代 graph DFS。

3. set-trie 是文献里处理 subset/superset containment query 的标准方向之一，但在当前 reachable-set 分布下也不能默认启用。root 004 上 set-trie 版本访问量与 DFS 相同，但 `superset ms` 明显更高；原因是我们最终仍要返回所有 terminal superset node，而 set-trie 递归枚举和最终过滤的常数大于当前 DAG DFS。该方向保留为 `twet.bpc.paperGraphUseSetTrie=true`，后续若发现某些节点 target cardinality 很大且 DFS 访问明显宽，再单独打开测试。

4. superset cache 也不适合默认开启。root 004 第一轮 cache hit/miss 只有 `26/1007`，后续轮次约 `9..12` 次命中，命中率太低；HashMap 查询、key copy 和结构失效维护的开销大于节省的 DFS。因此保留为 `twet.bpc.paperGraphSupersetCache=true` 诊断开关，默认关闭。

当前唯一保留在 indexed backend 默认路径里的改动，是把复制版 `PaperDominanceNode` 的 `predecessors/successors` 从 `LinkedHashSet` 改为 `ArrayList`，在 `connect()` 中显式去重。这个不改变 DFS 访问集合，只降低边遍历的对象和哈希开销。focused `javac` 通过；`PaperDominanceGraphConsistencyTest` 同时比较朴素全扫、旧 graph 和复制版，`cases=200, insertions=16000` 通过。

性能上，`tmp-wet030_from040_004_2m`、half-domain、allCycles、pricingOnly subtree、`maxNodes=2` 下，旧 DFS 为 `solve=27.564s, exact=9.286s`，复制版为 `solve=29.254s, exact=8.673s`；paperGraph superset+subset 合计从 `3378.926ms` 降到 `2287.956ms`，约降 `32.3%`，但端到端受其它阶段波动略慢。`011` 同口径 off subtree、`maxNodes=2` 下，旧 DFS 为 `solve=41.925s, exact=7.791s`，复制版为 `45.403s, exact=6.351s`；paperGraph 只有 `41.353ms -> 37.565ms`，不是主要大头。`013` 下旧 DFS 为 `solve=23.684s, exact=10.249s`，复制版为 `27.890s, exact=12.266s`；paperGraph 仅 `375.842ms -> 372.400ms`，几乎不变，说明 013 的变慢不是 paperGraph 自身，而是运行波动或其它 pricing 组件。

当前结论是：集合包含索引方向已经试过，但不能直接默认替换 graph DFS；当前可确认的正向结果是复制版 ArrayList 边表能降低部分节点的 paperGraph 时间，尤其 004 n2 下降约 32%，但端到端和不同算例不稳定。因此本轮不建议把 `paperGraphBackend=indexed` 设为默认，只保留为实验开关。若后续还要继续压 004/005 后续卡点，下一步更应该针对“为何某节点产生 40 万级 label”做列生成/dual/pricing 限制分析，单纯集合查询数据结构不能完全解决 label 数量爆炸。
