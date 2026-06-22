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

本次还把“dual bound”先做成诊断口径。文档中的基本公式是成立的：给定当前 RMP dual，只要 pricing 得到对应列族的全局最小 reduced cost，就可以用 RMP dual objective 加上该列族的最小违约量得到 dual bound。branch 行、`>=` 覆盖行、ng 或 completion bound 本身不会破坏这个公式，它们只是改变 reduced cost 里的 `a_q^T pi` 项，以及 pricing 是否真的证明了全局最小 reduced cost。当前 TWET 机器列是聚合的多机器列族，因此单 block 公式 `z_RMP + rc_min` 在这里应按机器数上界写成 `z_RMP + maxMachineCount * min(0, rc_min_internal)`；列化外包若有 `sum_o omega_o <= 1`，外包列族再加 `min(0, rc_min_outsourcing)`。当前日志记录 `acceptedBestRc`、`observedDualBound` 和 `filteredByOutDual`，用于判断稳定化是否仍在追逐对当前 RMP 无效的列。它暂不默认剪枝，原因是 PC 一旦某个 engine 接受列就会停止本轮 pricing，此时日志里的 `observedDualBound` 只是该 engine 已观察到的估计；只有 exact pricing 对所有相关列族完成全局最小 reduced cost 证明时，这个值才可作为节点剪枝依据。

验证：使用带 CPLEX jar 的 focused `javac` 编译 `TWETBPCConfig`、`LP`、`PC`、`GCBBFullDomainComparisonTest`、`OutsourcingModelComparisonTest` 通过；Serena diagnostics 对 `LP/PC/TWETBPCConfig` 无错误；小样例 `wet021_001_2m` 在 `maxNodes=1`、`dualStabilization=true` 下 root smoke 通过，结果 `ROOT_PROCESSED,obj=bound=6829,valid=true`。该 smoke 初始列已闭合，因此只覆盖开关和原始流程不破坏；稳定化 pricing 的效果还需要后续在 50/60 任务 tailing-off 节点上单独测试。
## 7. 2026-06-22：dual-bound pruning 实测

本次把前一节只作为日志诊断的 dual bound 进一步接入为节点剪枝开关，配置项为 `enableDualBoundPruning`，当前默认开启。实现口径是：每个 node 调用 `PC.solve(lp, incumbentCost)` 时传入当前 incumbent；非 repair、非 heuristic 的 pricing engine 在当前真实 dual 下记录本轮可证明的 `rc_min`，并计算

`DB = z_RMP + maxMachineCount * min(0, rc_min_internal)`

列化外包模式如果存在外包列族，再加 `min(0, rc_min_outsourcing)`。当记录到的最大 `DB >= incumbent - tol` 时，当前 node 可提前关闭。这个剪枝不依赖 dual stabilization，也不改变列生成的正确性；它只利用 exact pricing 已经给出的 reduced-cost 证明。启发式 pricing 不参与该证明。

在 `data/50-2/wet050_001_2m.dat` 上做无外包、无 cut、normal ng-DSSR nearestK8 测试，显式关闭 dual stabilization，开启 ALNS seed、启发式 pricing、BEST_UB、all-cycles completion bound、completion-bound arc fixing、pricingOnly subtree、midpoint probe reuse 和 dual-bound pruning。运行结果为 `obj=44383, bound=44383, total=1774.032s, exact=1161.929s/293 calls, processedNodes=17, pricingRounds=1113, addedColumns=67687`。

实测中 dual-bound pruning 确实触发，剪掉了 node `6,10,12,13,14,15,16,17` 共 8 个节点。典型例子是 node 6：当时 incumbent 已由 node 3/7 附近更新到 `44385/44383` 量级，node 6 的 observed dual bound 达到 `44397.8153846153`，因此不需要继续分支即可关闭。node 10 的最大 observed dual bound 为 `44389.0714972415`，也高于最终 incumbent `44383`。

这说明该剪枝对“restricted LP 已经高于 incumbent，但仍需继续 pricing 证明”的尾部节点有实际价值；它不是根节点加速器。root 的 observed dual bound 从 `44229.70` 逐步抬到 `44369`，始终低于当时 incumbent，因此 root 不能剪。node 4 的最大值 `44383.90` 接近但早期 incumbent 仍为 `44385`，也未触发。总体看，dual-bound pruning 能剪掉后期无望节点，但不能解决 root 和早期节点的 column generation tailing-off。

当前风险点是：这个剪枝要求 observed bound 来自已经覆盖完整列族的 exact pricing。当前无外包 50-2 配置下，内部 ng-DSSR exact pricing 是唯一相关列族，因此口径可用；若后续启用列化外包或多 exact engine，需要确认每个列族的 `rc_min` 都被记录后再用于剪枝，不能把某一个 engine 的局部结果当成完整 master dual bound。代码中已先保守处理：`lp.isColumnizedOutsourcing()` 时不启用 dual-bound pruning。因此默认开启只作用于当前已经证明口径完整的非列化外包路径；需要对照时可显式传入 `dualBoundPruning=false` 关闭。

本轮同时复查了测试数据的 setup 口径。`data/50-2/wet050_001_2m.dat` 含 `SETUP` 块，非零 setup 项为 `2496`，三角不等式违规数为 `0`、最大违规 gap 为 `0`。此前 `data/` 目录已批量做过 setup 三角闭包修正，后续默认算例应继续保持该口径。
