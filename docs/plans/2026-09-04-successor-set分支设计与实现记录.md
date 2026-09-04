# Successor-set 分支设计与实现记录

## 1. 动机与当前决定

普通 Arc 分支一次只固定一条 `i->j`，当任务 `i` 在当前 LP 解中有多个可替代后继时，禁止一条 Arc 后容易由其它后继替代，左右子节点的界改善可能较弱。Successor-set 分支不改变定价问题的资源状态，而是把任务 `i` 的一组后继 `A_i` 聚合为一个整数流量，分成“实际后继不属于该组”和“实际后继属于该组”两个子节点。

该分支作为实验组件保留，默认关闭。开启后采用严格分层：既有 Tariff/Machine/Adjacency 分支仍在前；Successor-set 有合格候选时只对本层候选做 strong branching，没有合格候选才回退普通 Arc；列化外包 membership 仍在 Arc 后。

## 2. 当前 LP 后继流

对任务 `i` 和内部后继 `a`，定义当前 LP 流量：

\[
g_{ia}=\sum_r\lambda_rN_{ia}(r),
\]

其中 `a` 可以是其它任务或 `sink`，`N_{ia}(r)` 是机器列 `r` 使用 Arc `i->a` 的次数。允许外包时再把 `OUT` 看作特殊后继：显式外包模型使用 `g_{i,OUT}=y_i`；列化外包模型使用包含任务 `i` 的正值外包列之和。

总流量为：

\[
H_i=\sum_{a\in J\cup\{sink,OUT\}}g_{ia}.
\]

机器列中任务 `i` 每出现一次就有一个后继，包括最后一次的 `i->sink`，所以 `H_i` 与任务 coverage 行左端值一致。当前 master 使用 `H_i\ge1`，因此 LP 最优解理论上可能过覆盖。Successor-set 第一版不处理明显过覆盖任务，只接受：

\[
0<H_i\le1+\epsilon_H,\qquad \epsilon_H=0.01.
\]

逐节点 coverage 诊断由 `diagnosticNodeCoverage` 控制，记录每个任务的内部覆盖、外包覆盖和总覆盖，以及 `under/tight/over` 汇总。该诊断默认关闭，不改变求解。开启后记录的是每个正式节点在 `PC.solve()` 最终返回的可行 LP 解；强分支 trial、最终不可行、`NOT_SOLVED`、在返回前达到全局时限以及被 pseudo-cost 提前剪掉的节点不输出。它不是逐次 pricing engine 调用或逐轮 CG 的日志。

## 3. 构造有效后继集合

先按总覆盖标准化：

\[
p_{ia}=g_{ia}/H_i.
\]

只保留 `p_{ia}>branchingTolerance` 的后继，得到有效后继全集 `S_i`。当前 `branchingTolerance=10^{-6}`，这里只用于消除数值零流，不另设经验阈值。`i->i` 不进入候选后继；其它任务、`sink` 和正值 `OUT` 均可进入。

只有 `|S_i|\ge3` 时才继续。随后在 `S_i` 内按保留流量总和重新标准化，用于比较分散程度和构造近似二等分；分支行中的实际值始终使用未标准化流量 `g_{ia}`。

## 4. 构造分支集合 A_i

将 `S_i` 中的后继按组内标准化流量从小到大排序，从最小者开始累加。累计值第一次跨过 `0.5` 时，比较跨越前后两个前缀，选择距离 `0.5` 更近者作为初始 `A_i`。

`A_i` 不能只有一个元素。若初始 `A_i` 为单元素，则改用 `S_i\setminus A_i`；改用后仍要求：

\[
|A_i|\ge2,\qquad |A_i|<|S_i|.
\]

不满足时丢弃该任务。最后按原始流量计算：

\[
Q_i(A_i)^*=\sum_{a\in A_i}g_{ia}.
\]

只有 `branchingTolerance < Q_i(A_i)^* < 1-branchingTolerance` 时，才形成完整候选。若 `Q_i(A_i)^*>1`，不使用 `<=0/>=1` 分支；`Q_i(A_i)\le1` 属于另一类有效不等式，不在本次实现中自动加入。

## 5. 候选排序和 strong branching

每个任务最多生成一个完整候选 `(i,S_i,A_i,Q_i(A_i)^*)`。所有完整候选按以下字典序排序：

1. `|S_i|` 降序，优先处理有效替代后继更多的任务；
2. `S_i` 内标准化流量的标准差升序，优先处理后继流更均匀的任务；
3. `|Q_i(A_i)^*-0.5|` 升序，优先处理左右流量更平衡的集合；
4. 任务编号升序，保证完全确定的并列顺序。

Tree 沿用现有 `strongBranchingCandidateLimit=20`，只让排序前20个候选进入两阶段 strong branching。最终选择仍由现有左右子节点 bound gain score 决定，预排序不替代 strong trial。

## 6. 左右分支与列系数

逻辑上的左右分支固定为：

\[
Q_i(A_i)\le0,
\qquad
Q_i(A_i)\ge1.
\]

因为真实排程中任务 `i` 恰有一个后继，该析取完备且 robust；非基本列可能使单列系数大于1，但系数仍是非负整数，因此仍必然落入其中一侧。

2026-09-04 后续修改中，左支不再建立 aggregate `Q_i(A_i)<=0` 行。由于所有项非负，该条件等价于逐项禁止 `A_i` 中的 arc 和 `OUT`：内部 pricing 通过节点 forbidden-arc 域直接删除对应扩展，列化外包 pricing 直接跳过该任务，显式外包变量则设 `UB=0`。RMP 对继承历史列仍沿用既有 Arc/外包分支的逐项零流保护行，但不再存在左支 aggregate 行。左支一次可能删掉多个后继，因此 strong trial 强制使用 domain-filtered all-row repair；关闭 strong branching 时，正式 child 也先按新域过滤 seed，若不可行则进入 all-row Phase-I。

右支 `Q_i(A_i)>=1` 不能拆成具体 required arc，因为尚不知道最终选择集合中的哪一个后继，因此仍保留一条 aggregate lower row。每条机器列的系数是其中属于 `A_i` 的 `i->a` Arc 出现次数；若 `OUT\in A_i`，显式外包变量 `y_i` 的系数为1，包含 `i` 的列化外包列系数也为1。

分支行 dual 对内部机器 pricing 展开到 `A_i` 中每条 `i->a` 的既有 arc dual；`OUT` dual 累加到任务 `i` 的 outsourcing membership dual，因此列化外包 pricing 也能看到同一分支。分支行 RHS dual 同时进入完整 dual objective，保持 reduced-cost certificate 口径。

## 7. 实现范围和未验证状态

当前实现包括：候选生成、排序、分层装配、左右 child、RMP 初始行、动态新增内部/外包列系数、内部与外包 pricing dual、Phase-I aggregate-row repair，以及 route-enumeration finite master 中的同一分支行。现有 restricted-master integer heuristic 未实现 aggregate 行，因此有 aggregate 分支的节点直接跳过该启发式，避免产生违反分支的 incumbent。

初次实现时按要求未运行测试；随后在正确性复核中完成受影响源码的增量 `javac` 编译，并运行 `NodeCoverageStatsTest` 与既有 `StructuredArcFlowBranchingTest`，均通过。尚未运行实际 successor-set 算例实验。正式 profile 会主动把 `enableSuccessorSetBranching` 恢复为 `false`；后续实验需通过显式覆盖开启。

## 8. 独立审查后的边界确认

2026-09-04 由主线程和独立审查智能体共同复核。`H_i/S_i/A_i` 构造、`Q_i(A_i)<=0` 与 `>=1` 的整数析取、`sink`、显式/列化外包系数、内部与外包 pricing dual、strong trial、正式 child、Phase-I 和有限列 master 均未发现正确性断口。这里所需的性质是每条列在 aggregate 行上的系数为非负整数；非基本机器列可能使系数大于1，但仍必然落入 `<=0` 或 `>=1` 的一侧。

左支已按上述方式改为直接域限制。右支 aggregate lower row 不并入单列兼容过滤：系数为0的列仍可与另一条系数为正的列共同满足下界，不能从右支 seed 中单独删除。

修改后增量编译通过，`SuccessorSetBranchingTest`、`NodeCoverageStatsTest`、`StructuredArcFlowBranchingTest` 和 `BestBpcProfilesTest` 均通过；尚未运行实际算例。
