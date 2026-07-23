# Cmax 统一 release 对照与 LP 风险最小修复分析

本次只做隔离诊断，不修改当前主线 Cmax。repair join 优化不再处理。

## 1. 统一最大 release 的效果

原 `SchedulerForReleaseNoWait` 对任务 `j` 使用 `d_l[j]-p[j]` 作为辅助 release。
对照方案先计算 `R=max_j(d_l[j]-p[j])`，再把所有任务的辅助 release 统一为 `R`。
这样确实消除了不同 release 导致的机器内等待空档。

代表算例结果如下。`coarse` 是当前 Tanaka runner 的
`maxDue+sumP+n*maxSetup+20`；`common` 和 `old` 都列出乘 1.1 后的值。

| 算例 | coarse | common | old | common 相对 old |
|---|---:|---:|---:|---:|
| 40-2 / 001 | 3773 | 2317.7 | 2131.8 | +8.72% |
| 50-2 / 001 | 5392 | 3197.7 | 2889.7 | +10.66% |
| 50-3 / 003 | 4474 | 1917.3 | 1747.9 | +9.69% |
| 60-2 / 001 | 5727 | 4144.8 | 3630.0 | +14.18% |
| 60-3 / 001 | 5727 | 3656.4 | 3141.6 | +16.39% |

40-2 和 50-2 各取前 10 个 seed 后，common 相对 old 平均分别增加
10.31% 和 10.42%，范围为 7.23%--13.06%。但 common 相对 coarse 仍平均缩小
约 41%。W100/W300 下差距结构基本不变，统一 release 只消除了其中一部分偏小。

## 2. 为什么仍不能作为 exact 安全界

统一 release 后，某条 ECT 启发式调度仍只是一条以 makespan 为目标的可行调度。
TWET 最优调度优化的是加权早到/延迟，可能采用不同的机器分配和顺序，其
makespan 可以大于 ECT makespan。不存在“某条可行调度的 makespan 自动上界
另一目标函数最优解 makespan”的关系。

一个所有 release 已经相同、没有 release 空档的反例为：2 台机器，6 个任务，
处理时间为 `[19,18,17,12,15,10]`，延迟权重为
`[441,1000,187,242,797,756]`，due date 全为 0。ECT 启发式 makespan 为 48，
乘 1.1 后为 52.8；穷举全部双机分配和顺序后，加权完成时间最优值为 80854，
所有最优调度中的最小 makespan 为 54。因此即使所有 release 完全相同，
`1.1*heuristic` 仍会删除真实最优解。

统一最大 release 的思路可以用于构造更合理的启发式 horizon，也可以帮助理解
解析粗界；但 exact 安全的是经过证明的通用负载上界，不是该启发式实际得到的
makespan。本次实验代码已清除，默认主线行为未改变。

## 3. CPLEX 状态的最小修复

没有必要新增大量 master 状态。当前已有 `NOT_SOLVED`，高层已有 `FAILED`。
最小正确口径为：

1. 只有 CPLEX 明确返回 `Infeasible` 时才生成 `INFEASIBLE`。
2. 其他 `solve()==false` 和 `IloException` 统一生成 `NOT_SOLVED`，具体原始状态
   保留在 message 中。
3. 正式节点遇到 `NOT_SOLVED` 时终止本次 BPC 并返回 `FAILED`，不能关闭该节点后
   继续声称最优。
4. strong trial 遇到 `NOT_SOLVED` 时返回不可用评分，不能当 INF。

`Unbounded`、数值失败和用户中止不必分别进入业务分支；只要不被误当成
infeasible 即可。

## 4. 正式节点 LP 释放

该项有必要修改。当前同一个 LP 重建模型前会结束旧 CPLEX，因此不是每轮 pricing
泄漏；遗漏的是每个正式节点完成后的最后一个 native model。应对单节点完整处理
增加统一 `try/finally`，在 incumbent、fixing、branch seed 和 trace 均读取完成后
调用 `lp.closeModel()`。这是低风险资源生命周期修复。
