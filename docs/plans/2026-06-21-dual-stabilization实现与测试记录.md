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

## 6. 2026-06-22：按 Pessoa/Wentges 口径重构稳定化

前一版 smoothing 的问题是调度太粗：稳定化 dual 下只要不断找到微弱负列，就可能长期停在 stabilized pass，迟迟不回到真实 dual 做闭合确认。根据 `Automation and Combination of Linear-Programming Based Stabilization Techniques in Column Generation` 和 Pessoa et al. 的总结，本次把逻辑改成“稳定化定价 + mispricing 回退 + 原始 RMP 最终验证”的闭环。

新的流程为：每个节点先以当前真实 dual 作为 out-point，并维护一个稳定中心。pricing 先用 `alpha * center + (1-alpha) * out` 的分离 dual；如果该 dual 下生成的列在当前 out-dual 口径下并不负，则视为 mispricing，不写入列池，并按 `alpha0, 1-2(1-alpha0), ... , 0` 逐步回退到真实 dual。只有列在 out-dual 下也达到负 reduced cost，才加入主问题。`alpha=0` 仍无列时，才说明当前 out-point 下没有负列；如果此前使用了 penalty RMP，还要清掉 penalty profile，重建原始 RMP，并用 true dual 再跑一次定价闭合确认。

Penalty stabilization 先只接 coverage 行。实现上在每个 cover 行加一对有界人工列，形成三段式 L1 penalty：中心窗口内不罚，窗口外用 `gamma` 斜率惩罚。这个选择是为了先处理当前最明显的 set-covering 退化；机器数和分支行的 dual 是自由行或强语义行，暂时不加入人工变量，避免把分支约束通过稳定化松掉。若 penalty 人工变量在无列时仍为正，就按配置放松 penalty；若人工变量为零，则回到原始 RMP 做最终 true-dual 验证。

Directional smoothing 也已接入，但只在非 mispricing 的第一轮尝试中启用。方向信息来自上一次被接受列的近似 subgradient；mispricing 回退阶段关闭方向扭转，避免因为方向项继续偏离真实 out-dual。

本次还把“dual bound”先做成诊断口径：稳定化 pricing 返回列后，会记录这些候选在当前 out-dual 下的 `acceptedBestRc` 和被过滤的 `filteredByOutDual` 数量。这个值可以帮助判断稳定化是否仍在追逐对当前 RMP 无效的列，也可以观察节点 LP 已高于 incumbent 时后续负列是否仍然存在。但当前没有把它用于节点剪枝，因为在 set-covering、机器数区间、外包变量/外包列和分支行共同存在的模型中，简单用 `RMP objective + best reduced cost` 直接 fathom 还缺少安全证明。后续若要做真正 dual-bound 剪枝，需要先明确该界覆盖所有列和所有非覆盖行的条件。

验证：使用带 CPLEX jar 的 focused `javac` 编译 `TWETBPCConfig`、`LP`、`PC`、`GCBBFullDomainComparisonTest`、`OutsourcingModelComparisonTest` 通过；Serena diagnostics 对 `LP/PC/TWETBPCConfig` 无错误；小样例 `wet021_001_2m` 在 `maxNodes=1`、`dualStabilization=true` 下 root smoke 通过，结果 `ROOT_PROCESSED,obj=bound=6829,valid=true`。该 smoke 初始列已闭合，因此只覆盖开关和原始流程不破坏；稳定化 pricing 的效果还需要后续在 50/60 任务 tailing-off 节点上单独测试。
