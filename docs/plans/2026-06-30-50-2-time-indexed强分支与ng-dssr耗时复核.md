# 50-2 time-indexed 强分支与 ng-DSSR 耗时复核

## 问题

本次复核的目标有两个。第一，使用当前 no-cut time-indexed pricing 加强分支求解 `data/50-2/wet050_001_2m.dat`，只改变强分支开关，其余保持同口径配置，确认它在 50-2 setup 算例上的表现。第二，检查当前 ng-DSSR 主线中哪些地方还存在明显的无效耗时，解释为什么它在同类算例上比 time-indexed 路线慢很多。

## 50-2 time-indexed 强分支结果

本次有效运行目录为 `test-results/bpc/tmp-timegraph-nocut-50-2-setup-strong-sa-20260630`，CSV 为 `test-results/bpc/tmp-timegraph-nocut-50-2-setup-strong-sa-20260630.csv`。配置为 no-cut time-indexed graph pricing、关闭旧 `HeuristicPricingEngine`、开启 ALNS seed、开启 strong branching、时间限制 1800 秒。结果为 `FINISHED`，`obj=bound=44383`，总时间 `378.979s`，root 时间 `195.295s`，节点数 `10`，pricing 调用 `795` 次，列池 `129438`，exact engine 为 `TimeIndexedGraphPricing`，exact pricing 累计 `32.927s/755 calls`，master LP 累计 `282.624s`，validator 为 `valid=true`。

同口径不开强分支的记录为 `test-results/bpc/tmp-timegraph-nocut-50-2-setup-nostrong-20260630.csv`，结果同样闭合到 `44383`，但总时间 `683.052s`，root 时间 `343.866s`，节点数 `47`，exact pricing `205.645s/1917 calls`，master LP `373.846s`，列池 `225101`。因此在这个 50-2 setup 算例上，time-indexed 强分支明显有效，主要收益不是 root bound 更强，而是后续节点数、pricing 调用和列池规模都降了。

与 ng-DSSR 主线相比，差距更明显。`tmp-wet050-001-dualstab-compare-nostab-20260623` 的 normal ng-DSSR no-stab 记录为总时间 `2154.215s`、节点数 `19`、exact `1588.909s/471 calls`、heuristic `496.832s/1246 calls`、master LP `21.971s`、列池 `55581`。`tmp-wet050-001-m2-setup-best-strongbranch-20260626` 的 ng-DSSR 强分支记录为总时间 `1280.392s`、节点数 `5`、exact `633.335s/138 calls`、heuristic `354.657s/447 calls`、master LP `127.994s`、列池 `70889`。time-indexed 强分支虽然列更多、master LP 更重，但 exact pricing 极轻，所以总时间反而低很多。

## ng-DSSR 当前主要无效耗时

从 `GCNGBBStyleBidirectionalNgDssr` 代码和 50-2 日志看，当前 ng-DSSR exact pricing 的核心特点是：一轮 exact 即使已经找到负列，也会继续把 forward 队列、backward 队列和 join 基本跑完，直到形成接近 certificate 的结果。`solveRelaxedRound()` 中 forward 和 backward 都以 `canContinue() && !queue.isEmpty()` 为主循环，`canContinue()` 实际只检查 exact 列开关，没有根据“已经找到足够多负列”提前停。因此在中前期只是想给 RMP 补一批列时，它仍然按接近证明无负列的强度工作。

50-2 no-stab 的 root 第一次 ng-DSSR exact pricing 是一个典型例子：只加入 `163` 条列，却用时 `6626ms`。其中 completion bound 构造 `2530ms`，join groups scanned `89320`，join candidates visited `140236`，join function evaluations `136917`，completion-bound eval `84443`。scalar completion bound 做了 `86658` 次检查，但只剪掉 `2215` 次，剩下 `84443` 次落到 PWLF fallback。也就是说大量时间花在函数下界、join 函数求值和队列完整展开上，而不是最终真正保留下来的候选列上。

后续轮次也有同样现象。日志里多次出现只加入个位数到几十条列，却仍然扫描数万到十几万 join/function/completion-bound 候选的情况。completion bound cutoff 当前保持 near-zero 口径，以保证最终证书安全；这对“无负列证明”是必要的，但对“已经有负列、只需要先返回一批列”的轮次偏重。midpoint probe 也会在 root 或初次 exact 中消耗几百毫秒到数秒，虽然复用后会降低，但它仍是 exact 启动成本的一部分。外层 `HeuristicPricingEngine` 在 ng-DSSR 主线中也很重，50-2 no-stab 记录里累计 `496.832s/1246 calls`，强分支记录里也有 `354.657s/447 calls`，后期常出现加列少或加列为零的长尾。

当前判断是：ng-DSSR 慢的主因不是某个单独 bug，而是 exact pricing 的工作口径太强，持续做连续时间 PWLF labeling、dominance graph、completion bound、双向 join 和 DSSR 轮次；time-indexed route 则把 exact pricing 简化成离散 DAG shortest path，虽然列弱且 master 更大，但定价本体成本低很多。

## 后续优化目标

如果继续优化 ng-DSSR，最明确的方向是增加“非证书 batch exact”模式：当一轮已经找到足够数量或足够负的 elementary 列时，先返回给 RMP；只有当一轮返回 0 列时，才必须耗尽队列、使用 near-zero cutoff，并允许 dual-bound pruning。这个模式要明确区分“有列返回但未证明无负列”和“无列且有证书”，否则会破坏正确性。

第二个方向是减少 PWLF fallback。当前 scalar bound 在一些轮次只能剪很少一部分，绝大多数 completion-bound 判断仍要做函数 shift/add/findMinimal；后续可以考虑更强 scalar cache 或 time-indexed relaxed scalar 作为前置过滤。第三个方向是启发式 pricing 的尾部控制：如果连续多轮加列为 0 或很少，应更早切到 exact 或降低启发式调用强度。midpoint probe 则可以继续依赖复用，但不应在已有稳定历史时反复花大成本试探。

当前没有修改 ng-DSSR 代码；本次只是用 50-2 结果和日志定位瓶颈。结论是 time-indexed 强分支在当前 50-2 setup 算例上已经是明显更快的 baseline，而 ng-DSSR 如果不改变 exact pricing 的返回策略，很难只靠局部小优化追上。

## 算例结构与 setup 口径复核

继续对照 VRPTW/Solomon 的 C/R/RC 经验后，当前怀疑“算例本身让 time-indexed 松弛列过于好用”是合理的。VRPTW 里 C 表示 clustered，R 表示 random，RC 表示 random-clustered 混合；R/RC 通常更容易产生分散路由和组合选择压力。我们当前 TWET 数据没有真实二维坐标和欧氏 routing 几何，setup time 只是从单机 Tanaka 实例派生出的随机矩阵，再通过 Floyd 闭包强制满足有向三角不等式。这会让 setup 更像一个平滑的转移时间扰动，而不是 VRP R/RC 里由空间结构自然诱导的长短边差异。

本次对 `data/50-2/wet050_001_2m.dat` 做了数值检查：加工时间均值 `55.5`，setup time 均值 `14.6812`，setup 约为加工时间的 `26.45%`，setup 最大值 `27`。对整个 `data/50-2` 目录 125 个实例检查，平均加工时间约 `50.385`，平均 setup 约 `9.969`，setup/p 均值约 `19.80%`，最小约 `11.03%`，最大约 `26.45%`。因此 setup 并非完全可忽略，但强度偏温和，而且经过闭包后矩阵更满足“绕路不吃亏”的性质。由于当前数据没有 `SETUP_COST` 块，setup 只改变时间递推，不直接进入目标弧成本，这也会削弱 sequence-dependent arc 对 master LP bound 的破坏力。

这能解释为什么 pseudo-schedule/time-indexed 松弛列并没有把 gap 明显拉大：重复任务或非基本路径虽然理论上更松，但如果 setup time 平滑、三角化、且没有弧成本惩罚，那么松弛图里的很多低 reduced-cost 路径和真实可行机器路径在成本结构上差异不大。相反，ng-DSSR 为了保持 elementary/ng 语义要承担连续时间函数、dominance、completion bound 和 join 的完整成本，在这种实例上反而显得过重。

后续如果要验证这一点，建议构造几类更有区分度的 setup 数据：第一，保留三角不等式但增加空间/簇结构，模拟 C/R/RC 三类；第二，提高 setup/p 比例，例如均值到 `0.5p` 或更高；第三，加入非零 `SETUP_COST`，让弧选择直接影响 objective；第四，在保证算法安全前提下单独测试闭包前/闭包后差异。当前结论不是“time-indexed 方法一定更强”，而是“当前 Tanaka 派生 setup 口径可能偏向 time-indexed 松弛，不能直接代表更难的 sequence-dependent routing-like 算例”。

## setup cost 系数实验入口

继续复核后确认：当前正式数据没有 `SETUP_COST` 块时，setup time 只影响时间递推，不直接影响目标成本。代码中的 pricing、启发式、time-indexed 图、RMP 和校验器已经统一读取 `data.getSetupCost(i,j)`，因此只要数据层给出非零 setup cost，后续组件会自然使用。

为先测试“setup 直接进入目标”这一因素，新增系统属性 `twet.data.setupCostFromTimeCoefficient`。默认值为 `0.0`，保持旧行为；若实例没有显式 `SETUP_COST` 块且该系数大于 0，则读入数据后自动令 `setupCost[i][j] = coefficient * setupTime[i][j]`，对角线仍为 0。显式输入的 `SETUP_COST` 永远优先，不被该系数覆盖。常用测试方式例如：

`-Dtwet.data.setupCostFromTimeCoefficient=1.0`

表示 setup time 的每一个时间单位同步作为 1 个固定弧成本进入 objective。若想更强，可以试 `2.0` 或更高。这个入口只改变数据层的弧成本，不改变 setup time 矩阵、三角闭包、分支逻辑和 pricing 结构，适合先做敏感性实验。

本次只做了最小验证：在默认系数下 `data/60-2/wet060_001_2m.dat` 的 `s[0][1]=7.0`、`setupCost[0][1]=0.0`；设置 `-Dtwet.data.setupCostFromTimeCoefficient=1.0` 后，`setupCost[0][1]=7.0`。后续若比较 time-indexed 与 ng-DSSR，应在结果配置快照中检查 `systemProperty.twet.data.setupCostFromTimeCoefficient`，避免把不同成本口径的结果混在一起。
