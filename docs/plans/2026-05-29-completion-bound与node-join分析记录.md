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
