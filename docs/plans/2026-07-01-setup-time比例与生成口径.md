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
