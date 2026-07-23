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

## 5. CPLEX 状态与正式节点 LP 生命周期修复

2026-07-23 已完成前述第 3、4 节的最小修改。`LP` 现在只有在 CPLEX 明确返回 `Infeasible` 时才生成 `TWETMasterStatus.INFEASIBLE`；`IloException` 以及其他 `solve()==false` 状态统一生成 `NOT_SOLVED`，原始状态或异常信息保留在 message。正式树节点收到 `NOT_SOLVED` 后终止本次 BPC 并返回 `FAILED`，不会进入 repair、不会关闭节点，也不会继续报告最优。strong branching trial 收到同样状态时标为 `unusable`：评分增益为 0，不按 INF 处理，不复用该 trial 的 seed；若该分支最终通过其他候选逻辑被选中，正式 child 仍按正常流程求解。

`Tree` 中每个正式节点创建的 `LP` 已用统一 `try/finally` 包围。incumbent 更新、arc fixing、branch child seed 和 trace 全部读取完后执行 `lp.closeModel()`；所有 `continue`、`break` 和异常路径也会经过同一个释放点。strong trial 原有的独立 `finally closeModel()` 保持不变。

验证使用 focused `javac` 编译 `LP/PC/Tree/LPRestrictedColumnMembershipTest`，并运行 `LPRestrictedColumnMembershipTest`。测试额外确认 `NOT_SOLVED` trial 同时满足：不是 infeasible、不是 closed、不可复用 seed。

## 6. 统一 release 仍失败的具体反例

反例有两台相同机器、6 个任务，所有 release 和 due date 都为 0，setup 为 0。任务的 `(processing time, weight)` 为：`J1=(19,441)`、`J2=(18,1000)`、`J3=(17,187)`、`J4=(12,242)`、`J5=(15,797)`、`J6=(10,756)`。目标是最小化 `sum w_j C_j`，因此这里不存在不同 release 造成的等待空隙。

ECT/SPT 型启发式得到：

- M1：`J6 -> J5 -> J2`，完工时间为 `10,25,43`；目标贡献为 `756*10 + 797*25 + 1000*43 = 70485`。
- M2：`J4 -> J3 -> J1`，完工时间为 `12,29,48`；目标贡献为 `242*12 + 187*29 + 441*48 = 29495`。

因此启发式 makespan 为 48，总目标为 99980，按当前规则生成的 exact horizon 是 `1.1*48=52.8`。

穷举全部双机分配和机内顺序后，最优目标为 80854；其中最小 makespan 仍为 54。一组最优调度为：

- M1：`J6 -> J5 -> J4`，完工时间为 `10,25,37`；目标贡献为 `756*10 + 797*25 + 242*37 = 36439`。
- M2：`J2 -> J1 -> J3`，完工时间为 `18,37,54`；目标贡献为 `1000*18 + 441*37 + 187*54 = 44415`。

总目标为 `36439+44415=80854`，但 makespan 为 54。原因不是等待，而是两个目标不同：ECT 为缩短最后完工时间，会把短任务均匀铺到两台机器；加权目标则愿意让低权重 `J3` 延迟到 54，以便高权重 `J2/J5/J6` 更早完成。`52.8` 会直接删掉这组真实最优调度。因此“把 release 统一成最大值”只修掉了旧启发式中的 release 空隙，不能把启发式 makespan 变成 exact 安全上界。
## 7. 主线改为公共 release 的确定性负载上界

2026-07-23 根据公式复核，原 Tanaka runner 所谓粗糙上界并不是图 1 的逐任务 setup 负载式，而是 `maxDue + sumP + n * globalMaxSetup + 20`。旧 `Data.setCmax()` 的负载部分接近图 2，但末项使用 `max_j(d_e[j] - p_j - maxSetup_j) + 1`；随后 runner 又用 release/no-wait 启发式 makespan 覆盖，实际存在三套串联口径。

无 setup cost 的基础口径为

`q_j = p_j + max_{i != j} s_ij`

`rMax = max(0, max_j(r_j, d_l[j] - p_j))`

`Cmax = rMax + sum_j(q_j) / m + (m - 1) * max_j(q_j) / m`。

这里先把所有任务的真实 release 和辅助 release 统一为最大值 `rMax`，再把每个任务的处理长度保守替换为自身处理时间加最大进入 setup。项目支持连续时间，因此不按文献整数式向下取整。`CmaxE` 和 `CmaxH` 取同一个值，不再乘 1.1。旧 `setImprovedCmax()` 方法名仅为兼容最终数据覆盖后的重算入口，不再调用 `SchedulerForReleaseNoWait`。

三组代表算例的只加载数据对照为：40-2/001 的粗糙式、旧启发式乘 1.1、新公式分别为 3773、2131.8、2178.5；50-2/001 为 5392、2889.7、3182.5；50-3/003 为 4474、1747.9、1917.333。新公式仍比原粗糙式明显紧，但不再依赖某条启发式调度的 makespan。

验证新增可手算回归：两台机器、处理时间 `[19,18,17,12,15,10]`、零 setup 时公式得到 55；将六个任务的最大进入 setup 依次设为 3 到 8 后得到 73。focused `javac` 编译通过，`CommonReleaseSetupHorizonTest` 通过。此前专题中“统一 release 仍使用启发式 makespan”的风险结论仍成立，但当前实现已改为公式负载上界，不再使用该错误启发式。

## 8. 当前实际调用流程澄清

当前 Cmax 只使用公共最大 release 的确定性公式。对任务 `j`，先计算最大进入 setup：`sMax_j=max_{i!=j}s[i][j]`，其中包含虚拟起点 `0 -> j`；再定义膨胀处理时间 `q_j=p_j+sMax_j`。代码不修改 `data.r[j]`，但 Cmax 公式同时考虑真实 release 和辅助 release，统一取

`rMax=max(0,max_j(r_j,d_l[j]-p_j))`。

若所有 setup cost 为 0，计算

`Cmax = rMax + sum_j(q_j)/m + (m-1)max_j(q_j)/m`。

若任一 setup cost 非 0，则改用

`Cmax = rMax + sum_j(q_j)`。

并令 `CmaxE=CmaxH=Cmax`。项目允许连续时间，因此不向下取整，也不再额外乘 1.1。

普通 `Data` 在最终输入读取完成后调用一次 `setCmax()`。Tanaka runner 会在构造 `Data` 后覆盖任务、due window 和 setup，因此覆盖结束后调用旧兼容入口 `setImprovedCmax()`；该方法现在仅转调 `setCmax()`，不再运行启发式。随后才重建 hard windows 和 penalty functions，使这些结构使用新的 horizon。

当前明确不再用于主线 Cmax 的内容包括：runner 的 `maxDue+sumP+n*globalMaxSetup+20` 粗糙式、`SchedulerForReleaseNoWait`、启发式 makespan 以及 `heuristicMakespan*1.1`。旧调度器类暂时保留在仓库中，但没有 Cmax 调用方。方法名 `setImprovedCmax()` 也仅因现有 runner 兼容而保留，不能再把它理解为启发式收缩。

## 9. 最终正确性审计

2026-07-23 最后一轮审计补出两个边界。第一，公共 release 不能只取 `d_l[j]-p_j`，否则实例显式给出的更晚 `r[j]` 会被漏掉；当前已统一取两者最大值。第二，并行负载公式依赖“把末尾任务移到更早空闲机器不会恶化目标”。setup cost 非零时这个条件不成立：换机可能显著增加起始弧或连接弧成本，最优解可能主动使用 makespan 更长但 setup cost 更低的串行序列。因此 setup cost 非零时改用 `rMax+sum(q_j)` 的串行安全界。

实现仍只在原有 `O(n^2)` 最大进入 setup 扫描中顺带检测 setup cost，没有增加额外渐进复杂度。手算回归新增真实 release 和 setup-cost fallback 两项；另对无 setup cost 的两机小实例做 4300 组独立穷举对拍，未发现并行公式小于真实 TWET 最优解最小 makespan 的情况。setup cost 非零时 horizon 会比无 setup cost 版本更宽，这是保证 exact 正确性所需，不是额外的实现冗余。
