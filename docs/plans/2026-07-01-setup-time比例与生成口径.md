# setup time 比例与生成口径记录

## 问题

当前 TWET 算例中的 setup time 虽然在生成时使用了 `eta=0.5`，但生成后还会做 Floyd 三角闭包。闭包会把一部分直接 setup 弧压低成经由其他任务中转后的更短值，因此实际进入求解的数据并不等价于“平均 setup 等于平均加工时间的 50%”。以 `wet040_001_2m` 为例，平均加工时间约为 `51.625`，闭包后的真实 job-to-job 平均 setup 约为 `9.0`，实际比例只有约 `17.4%`。这说明仅看生成参数 eta 会高估当前数据里的 setup 强度。

## 文献口径

Kramer 使用的并行机 ET/T 问题是在 Şen-Bülbül 数据上补充 sequence-dependent setup。其 setup 生成参考 Cicirello and Smith 的 weighted tardiness with sequence-dependent setups benchmark：设平均加工时间为 `pbar`，平均 setup 为 `sbar = eta * pbar`，再在 `[0, 2*sbar]` 截断范围内随机生成 setup。Cicirello 的 benchmark 明确把 `eta=0.25` 称为 mild setups，把 `eta=0.75` 称为 severe setups。因此，从这个口径看，平均 setup 约为平均 processing 的 25% / 75% 是常见的弱/强 setup 两档。

更广的 sequence-dependent setup time 文献里，flow shop / no-wait flow shop 常用 Ruiz 等人的 SSD 系列 benchmark。该系列用 `SSD-10 / SSD-50 / SSD-100 / SSD-125` 表示 setup time 相对于 processing time 的比例约为 10%、50%、100%、125%。这说明 setup 与 processing 同量级，甚至 setup 大于 processing，在调度 benchmark 中是明确存在的实验设置，不是只在 VRP 里才合理。

实际生产场景中，setup time 可能来自清洗、换色、换模、换刀、温度调整、配方切换或交叉污染控制。已有文献举例包括不同颜色油漆生产中的清洗时间、塑料薄膜挤出中由产品类型和颜色决定的清洗/换型时间、制药场景中的产品族切换和清洁验证。这些场景下，如果单个 job 的加工批量很小，或者跨产品族/颜色的清洗换型很重，那么 setup time 完全可能接近甚至超过该 job 的 processing time。

## 当前生成工具

新增的 `Common.SetupRatioVariantGenerator` 不是直接给定生成前 eta，而是给定 Floyd 闭包后的目标比例：`job-to-job 平均 setup / 平均 processing`。工具读取已有 `.dat`，保留任务行和机器数，只重建 `SETUP` 块；对每个目标比例二分搜索生成前 eta，按当前项目同类 truncated-normal 方式生成 setup，再做三角闭包，直到闭包后的真实比例接近目标。

本次对 `wet040_001_2m` 试生成了三档：

1. `setupR25`：目标 `0.25`，生成前 eta 约 `0.733`，闭包后比例 `0.249469`。
2. `setupR50`：目标 `0.50`，生成前 eta 约 `1.429`，闭包后比例 `0.500292`。
3. `setupR75`：目标 `0.75`，生成前 eta 约 `2.151`，闭包后比例 `0.749587`。

这组结果也反向说明：由于三角闭包会显著压低 setup，若想得到文献意义上的 25% / 50% / 75% setup 强度，生成前 eta 必须明显大于目标比例。

## 后续实验建议

若只是对齐 Kramer/Cicirello，可以先使用 `setupR25/setupR50/setupR75` 三档，再叠加 setup cost 系数 `0/1/5/10`。若要覆盖更广的 SDST benchmark 口径，应再加入 `setupR10/setupR100/setupR125`，对应 SSD 系列中的 10%、100%、125% 场景。

如果目标是构造更困难、更贴近生产族切换的 setup 结构，纯随机 setup 再三角闭包可能不够。更合理的下一步是增加 family/cluster setup 生成模式：同族 setup 很小或为 0，跨族 setup 较大，并在族间矩阵上保证三角不等式。这样能更稳定地保留长 setup 弧，而不是被随机中转路径大量压低。

## 2026-07-01 setup cost 20 初步对照

按 time-indexed no-cut、关闭旧启发式 pricing、关闭 strong branching、关闭 SRI/rank-1 cut 的口径，对原始 40-2 和 `setupR75` 做了 setup cost 系数 `20` 的短对照。两组均使用 `twet.data.setupCostFromTimeCoefficient=20`，不修改 `.dat` 文件。

原始 `wet040_001_2m.dat` 的结果目录为 `test-results/bpc/tmp-timegraph-40-2-setupcost20-20260701`，结果为 `FINISHED,obj=bound=28110,solve=252.012s,root=15.027s,nodes=64,pool=240599`，time-indexed exact pricing 为 `185.421s/3002 calls`，总 pricing 轮数 `3016`，加入列 `240599`。

`setupR75` 的结果目录为 `test-results/bpc/tmp-timegraph-40-2-setupR75-cost20-20260701`，结果为 `FINISHED,obj=bound=55007,solve=176.524s,root=17.174s,nodes=68,pool=143414`，time-indexed exact pricing 为 `131.691s/1731 calls`，总 pricing 轮数 `1750`，加入列 `143414`。

这个结果不是“setup 更强一定更慢”。在当前 time-indexed pseudo-schedule 定价口径下，`setupR75 + cost20` 虽然 horizon 更大，单次 pricing 平均时间也略高，但它显著减少了负列数量、pricing 轮数和列池规模，因此总时间反而低于原始 setup + cost20。直观解释是：较强 setup time 与较高 setup cost 会抑制重复绕行和不自然 pseudo-schedule 的吸引力，使 LP 尾部可生成的负列减少。后续如果要判断 setup 对 time-indexed 和 ng-DSSR 的相对影响，应继续比较 `setupR25/R50/R75` 与 cost 系数 `0/1/5/10/20` 的矩阵，而不能只看单一强度。

## 2026-07-01 ng-DSSR + setup cost 20 对照

随后用主线 ng-DSSR 口径复跑同样两组，配置为 nearestK8/top10、BEST_UB、ALNS seed、启发式 pricing、allCycles completion bound、completion-bound arc fixing、pricingOnly subtree、midpoint probe/reuse、dual-bound pruning，并打开 post-node time-indexed scalar/window/arc-fixing helper；不使用 time-indexed graph pricing、SRI/rank-1 cut、partial dominance、route enumeration 和 strong branching。

原始 `wet040_001_2m.dat + cost20` 结果目录为 `test-results/bpc/tmp-ngdssr-40-2-setupcost20-20260701`，结果为 `FINISHED,obj=bound=28110,solve=325.217s,root=81.676s,nodes=34,pool=60066`。其中 heuristic pricing 为 `157.608s/868 calls`，ng-DSSR exact pricing 为 `81.739s/249 calls`，总 pricing 轮数 `1121`，加入列约 `60066`。

`setupR75 + cost20` 结果目录为 `test-results/bpc/tmp-ngdssr-40-2-setupR75-cost20-20260701`，结果为 `FINISHED,obj=bound=55007,solve=220.409s,root=83.847s,nodes=28,pool=38617`。其中 heuristic pricing 为 `86.969s/543 calls`，ng-DSSR exact pricing 为 `65.160s/184 calls`，总 pricing 轮数 `731`，加入列约 `38617`。

与同组 time-indexed no-cut 对照相比，ng-DSSR 在这两个小 horizon 算例上仍然更慢：原始 cost20 为 `325.217s` 对 `252.012s`，`setupR75 + cost20` 为 `220.409s` 对 `176.524s`。但是 ng-DSSR 的列池明显更小，原始组 `60066` 对 time-indexed 的 `240599`，`setupR75` 组 `38617` 对 time-indexed 的 `143414`。这说明 time-indexed 在小整数 horizon 下仍靠很快的离散 DAG 定价和大量 pseudo-schedule 列取胜；ng-DSSR 列更强、更少，但单次定价和启发式搜索更重。强 setup + 高 setup cost 在两种 pricing 下都减少了列数和 pricing 轮数，因此当前不能把 setup 强度简单等同于求解更难。

配置复核时发现，这次 time-indexed 与 ng-DSSR 对照不是严格“只换 exact pricing”的口径。time-indexed 组使用纯 `TimeIndexedGraphPricingEngine`，启发式 pricing、ALNS seed、completion bound、probe、time-indexed helper 和 strong branching 均关闭；ng-DSSR 组则使用主线增强配置，包含启发式 pricing、ALNS seed、`allCycles` completion bound、subtree pricingOnly、midpoint probe/reuse、`BEST_UB` join、ng-set nearestK8/top10 以及 post-node time-indexed scalar/window/arc-fixing helper。因此这次结果只能说明小 horizon 下纯 time-indexed no-cut 仍很快，不能作为严格单因素 pricing engine 对照。

日志中 dual-bound pruning 的使用也不对称：time-indexed 两组均为 `pruned by dual bound=0`，原始组和 `setupR75` 分别主要靠 incumbent 剪掉 `15/16` 个节点；ng-DSSR 两组均为 `pruned by dual bound=12`，说明 dual bound pruning 对 ng-DSSR 分支树已有实际作用。

为避免后续默认实验再次漏开主线组件，`GCBBFullDomainComparisonTest` 不再把 `runALNSForSeed` 默认覆盖为 `false`，而是沿用全局配置默认值并允许系统属性显式覆盖；`TWETBPCConfig.enableTwoStageStrongBranching` 也改为默认开启。后续如果要做消融，应显式传参关闭，而不是依赖默认值。

## 2026-07-01 strong branching + ALNS 默认开启后的复跑

按新的默认口径重新跑 40-2 原始算例和 setup-ratio 变体，均使用 setup cost 系数 `20`、strong branching 开启、ALNS seed 开启、route enumeration 关闭、dual-bound pruning 开启。time-indexed 组仍只使用 `TimeIndexedGraphPricingEngine`，不使用启发式 pricing；ng-DSSR 组使用 nearestK8/top10、`BEST_UB`、`allCycles` completion bound、subtree pricingOnly、midpoint probe/reuse 和 post-node time-indexed helper。

原始 `wet040_001_2m` 上，time-indexed 结果为 `obj=28110,solve=408.675s,root=139.079s,nodes=22,pool=164539,exact=72.960s/1178,masterLP=265.614s`；ng-DSSR 结果为 `obj=28110,solve=521.157s,root=114.462s,nodes=8,pool=49887,heuristic=89.404s/199,exact=39.309s/64,masterLP=241.836s`。相比无 strong branching/ALNS 的旧对照，节点数明显下降，但强分支与更大的 seed/RMP 使 master LP 时间显著上升，原始组总时间反而变长。

setup-ratio 变体目录这次由于 case filter 命中了 `wet040_001_2m_setupR25/R50/R75` 三个文件，三组都被跑完。time-indexed 结果分别为：`R25 obj=31893,solve=214.180s,root=111.845s,nodes=6,pool=84998`；`R50 obj=43625,solve=334.596s,root=60.035s,nodes=40,pool=160765`；`R75 obj=55007,solve=261.224s,root=88.995s,nodes=23,pool=136927`。ng-DSSR 结果分别为：`R25 ROOT_PROCESSED,obj=bound=31893,solve=131.461s,root=131.459s,nodes=1,pool=11717`；`R50 FINISHED,obj=43625,solve=258.703s,root=81.499s,nodes=5,pool=22961`；`R75 FINISHED,obj=55007,solve=386.189s,root=92.692s,nodes=11,pool=43074`。

这轮更接近“主线默认增强”口径后，结论变得更细：ng-DSSR 的列池和节点数仍明显更小，R25/R50 上总时间也优于 time-indexed；但 R75 与原始组上，ng-DSSR 的启发式和 master LP 时间抵消了 exact pricing 较少的优势。time-indexed 在小整数 horizon 下仍能靠快速 DAG pricing 处理大量 pseudo-schedule 列，但 strong branching 后 master LP 成为主要耗时之一，不能只看 exact pricing 时间。
