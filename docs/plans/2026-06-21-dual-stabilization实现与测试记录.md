# Dual stabilization 实现与测试记录

## 1. 实现口径

本次实现采用低侵入 dual provider 口径，不修改 RMP 本身。`LP` 仍保存 CPLEX 读出的真实 dual，同时增加一个只供 pricing getter 使用的临时 dual override。`PC` 的正常 pricing loop 在开关 `enableDualStabilization=true` 时先让所有 pricing engine 使用

`pi_stab = alpha * pi_true + (1 - alpha) * pi_center`

如果这一轮所有 engine 都没有新增列，再清掉 override，用真实 dual 按相同 engine 顺序重跑一次。只有真实 dual 也没有新增列，当前 RMP 才算闭合。repair slack 路径暂时保持真实 dual，避免 feasibility repair 和稳定化中心混在一起。

实现中特意只让真实 dual pass 写入 reusable subtree/completion-bound 信息。原因是 stabilized dual 下构造的 reusable bounds 不代表真实 dual 的闭合状态，不能拿去做后续节点的复用或 arc elimination 依据。SRI cut dual 当前也不做 smoothing，仍使用 LP 真实 cut dual。

## 2. 测试设置

本轮先只测试旧 `masterVariables` 外包变量模式，不启用外包列。测试入口为 `HEU.OutsourcingModelComparisonTest`，新增 `twet.bpc.outsourcingCompare.models=masterVariables` 后可以只跑外包变量模型。测试时打开 ng-DSSR、completion bound、midpoint probe 和 probe reuse。

编译检查使用：

`javac -encoding UTF-8 -cp "target/classes;D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/lib/cplex.jar" -d target/classes ...`

测试需要显式设置：

`-Djava.library.path=D:/软件/cplex/ILOG/CPLEX_Studio2211/cplex/bin/x64_win64`

## 3. 初步结果

`n=20,m=2,cases=7,8,3段 tariff` 中，不开稳定化时 case 7 为 `nodes=4, pricing=12, cols=186, time=1.582s`，case 8 为 `nodes=3, pricing=11, cols=190, time=0.210s`。默认 `alpha=0.7` 开稳定化后，case 7 变为 `nodes=3, pricing=26, cols=293, time=1.472s`，case 8 变为 `nodes=3, pricing=23, cols=252, time=0.624s`。case 7 节点少一个且时间略降，case 8 明显变慢。

`n=30,m=2,case=12,3段 tariff` 中，不开稳定化为 `nodes=7, pricing=44, cols=1263, time=3.300s`。默认 `alpha=0.7` 后为 `nodes=7, pricing=83, cols=1324, time=4.379s`；`alpha=0.5` 为 `pricing=73, cols=1441, time=8.131s`；`alpha=0.9` 为 `pricing=68, cols=1410, time=5.926s`。三组权重都没有优于 baseline。

CSV 输出：

`test-results/bpc/2026-06-21-dualstab-off-outsourcing-n20-c7-c8-seg3.csv`

`test-results/bpc/2026-06-21-dualstab-on-outsourcing-n20-c7-c8-seg3.csv`

`test-results/bpc/2026-06-21-dualstab-off-outsourcing-n30-c12-seg3.csv`

`test-results/bpc/2026-06-21-dualstab-on-outsourcing-n30-c12-seg3.csv`

`test-results/bpc/2026-06-21-dualstab-on-a05-outsourcing-n30-c12-seg3.csv`

`test-results/bpc/2026-06-21-dualstab-on-a09-outsourcing-n30-c12-seg3.csv`

## 4. 当前结论

这个 smoothing 实现的闭合语义是正确的，true pass 会在 stabilized pass 失败后完整执行，测试结果的 incumbent 和 bound 均与 baseline 一致。

但在外包变量模式的这几个样本上，dual stabilization 没有显示稳定收益。主要代价是 stabilized pass 生成更多列，随后 true pass 仍要闭合确认，pricing calls 和列池都增加。因此旧 `masterVariables` 模式不建议默认开启。

后续如果继续测试，应优先放到外包列化 `columns`、多段 tariff 或更强 dual churn 的算例上，并重点观察 pricing rounds、列池规模和每轮 true pass 的实际闭合成本。

## 5. 无外包 40-4 测试

按当前无外包主流程测试 `data/40-4/wet040_001_4m.dat`，配置为 ng-DSSR、nearestK8、BEST_UB、completion bound、pricingOnly subtree、midpoint probe reuse、ALNS seed，关闭 undirected adjacency branching 和 SRI。当前代码下不开 dual stabilization 时结果为 `obj=11460, bound=11460, nodes=7, pricing=177, cols=7639, time=55.999s, root=47.171s, exact=4.048s/48, heuristic=38.173s/129`。

开启 `enableDualStabilization=true`、默认 `alpha=0.7` 后，结果为 `obj=11460, bound=11460, nodes=7, pricing=217, cols=6987, time=53.569s, root=37.804s`。日志中 stabilized pass 的 heuristic/exact 分别为 `36.484s/167` 和 `3.647s/34`，true pass 分别为 `1.357s/8` 和 `0.568s/8`。CSV 中 `exact_s=0` 是因为统计字段没有把 `[stabilized]`、`[true]` 后缀合并回原 exact engine 名称；详细 pricing 时间应以 log summary 为准。

这组无外包 40-4 上，dual stabilization 没有改变最终目标和下界，闭合语义正常；总时间从 `55.999s` 到 `53.569s`，略快但幅度很小。它减少了列池规模和 root 时间，但增加了 pricing rounds。当前还不能作为默认开启依据，只能说明在该样本上没有明显副作用。
