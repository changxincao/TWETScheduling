# Cmax 与主线正确性风险复核

本次对照下载文档与当前代码，只复核前三项，不修改算法实现；文档中的 repair join 优化暂不处理。

## 1. Cmax

当前主实验入口先在 `TanakaNoOutsourcingBPCTest.computeSafeHorizon()` 中构造
`maxDue + sumP + n * maxSetup + 20`，随后调用 `Data.setImprovedCmax()`。
后者用 `SchedulerForReleaseNoWait` 求一个 release/no-wait 辅助调度；一旦其
makespan 更小，就执行 `CmaxE=improvedCmax`、`CmaxH=1.1*CmaxE`。
缩小后的 `CmaxH` 会继续进入 penalty function、粗硬窗、永久禁弧、
time-indexed pricing、ng-DSSR 和 completion bound，因此它是 exact 搜索域，
不只是启发式参数。

该替换没有数学依据。辅助调度只证明存在一个短 makespan 可行调度，不能证明
TWET 最优解的最大完成时间不超过其 1.1 倍。下载文档给出的两任务反例计算成立：
辅助 makespan 为 250，当前 exact horizon 为 275，但原 TWET 最优序列需要最大
完成时间 300，截断后目标由 150 变为 2625。后续 hard-window 处理不能修复，
因为窗口右端已经先被当前 `CmaxH` 截断。

对当前代表算例做轻量计算，结果如下。`analytic` 是 `Data.setCmax()` 解析式，
`safe` 是当前 Tanaka runner 的粗安全式，`heuristic` 是 release/no-wait
makespan，`applied` 是实际写入 exact 的 `1.1*heuristic`。

| 算例 | analytic | safe | heuristic | applied | safe 到 applied 缩小 |
|---|---:|---:|---:|---:|---:|
| 40-2 / 001 | 2175.5 | 3773 | 1938 | 2131.8 | 43.50% |
| 50-2 / 001 | 3161.5 | 5392 | 2627 | 2889.7 | 46.41% |
| 50-3 / 003 | 1896.3 | 4474 | 1589 | 1747.9 | 60.93% |
| 60-2 / 001 | 3888.5 | 5727 | 3300 | 3630 | 36.62% |
| 60-3 / 001 | 3412.3 | 5727 | 2856 | 3141.6 | 45.14% |

若把“粗暴做法”理解为 `Data.setCmax()` 而不是 Tanaka runner 的
`safeHorizon`，上述五例中 `analytic -> applied` 只缩小约 2.0%--8.6%；
但当前 40/50/60 主实验实际使用的是 `safe -> heuristic -> applied` 链路，
缩小达到 36.6%--60.9%。这足以显著改变图规模，也足以在反例上删除真实最优解，
不能依赖 1.1 的经验余量。

当前合理方向是把 exact horizon 与 heuristic horizon 分离。启发式 horizon
可以继续用于 ALNS、启发式 pricing 或 exact pricing 的第一遍找列，但不能用于
最终闭合证书、永久 fixing 和 exact penalty domain。若要保留小图收益，可以先在
heuristic horizon 内找列；只有准备声明无负列时，再用安全 exact horizon 完整复核。

## 2. CPLEX 状态

文档判断与当前代码一致。`LP.solveRelaxation()` 和 `resolveCurrentModel()` 把
`IloException` 直接转成 `INFEASIBLE`；`solveCurrentModel()` 也把所有
`solve()==false` 统一转成 `INFEASIBLE`。当前状态枚举没有 `UNBOUNDED`、
`SOLVER_ERROR` 或 `ABORTED`。这会把数值失败、异常、中止或未知状态送入 repair，
最终仍可能按不可行关闭节点；strong branching 中还可能形成错误的 INF 评分。

必须只在 CPLEX 明确返回 infeasible 时使用 `INFEASIBLE`。其他未完成状态应单独
向上传播，正式树不得剪枝，strong trial 不得获得不可行评分。

## 3. 正式节点 LP 生命周期

文档判断同样仍成立。`Tree` 在每个正式节点创建 `LP` 后没有统一
`finally closeModel()`；当前文件中的显式关闭只覆盖 strong-branching trial。
`LP.buildModel()` 会在同一个 LP 内重建前关闭旧 CPLEX，因此问题不是每轮 pricing
都泄漏一个模型，而是每个正式节点结束后依赖 GC 回收最后一个 native model。
节点较多或同 JVM 连续运行多个实例时，可能积累 native 内存和 CPLEX 工作区，
并放大第 2 项异常误判风险。

后续修复应把单节点处理包进统一 `try/finally`，而不是在每个 `continue` 前分散
补关闭。关闭必须发生在 incumbent、fixing、branch child seed 和 trace 全部读取
完成之后。

## 当前结论

第 1 项是已确认的数学正确性问题，而且当前主实验的 horizon 缩小幅度达到约
37%--61%；先分析，不在本次修改。第 2 项是异常路径正确性问题，第 3 项是确定的
native 资源生命周期问题。第 4 项按当前决定不再继续。
