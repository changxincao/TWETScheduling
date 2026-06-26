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

本次把前一节只作为日志诊断的 dual bound 进一步接入为节点剪枝开关，配置项为 `enableDualBoundPruning`，当前默认关闭。实现口径是：每个 node 调用 `PC.solve(lp, incumbentCost)` 时传入当前 incumbent；非 repair、非 heuristic 的 pricing engine 在当前真实 dual 下记录本轮可证明的 `rc_min`，并计算

`DB = z_RMP + maxMachineCount * min(0, rc_min_internal)`

列化外包模式如果存在外包列族，再加 `min(0, rc_min_outsourcing)`。当记录到的最大 `DB >= incumbent - tol` 时，当前 node 可提前关闭。这个剪枝不依赖 dual stabilization，也不改变列生成的正确性；它只利用 exact pricing 已经给出的 reduced-cost 证明。启发式 pricing 不参与该证明。

在 `data/50-2/wet050_001_2m.dat` 上做无外包、无 cut、normal ng-DSSR nearestK8 测试，显式关闭 dual stabilization，开启 ALNS seed、启发式 pricing、BEST_UB、all-cycles completion bound、completion-bound arc fixing、pricingOnly subtree、midpoint probe reuse 和 dual-bound pruning。运行结果为 `obj=44383, bound=44383, total=1774.032s, exact=1161.929s/293 calls, processedNodes=17, pricingRounds=1113, addedColumns=67687`。

实测中 dual-bound pruning 确实触发，剪掉了 node `6,10,12,13,14,15,16,17` 共 8 个节点。典型例子是 node 6：当时 incumbent 已由 node 3/7 附近更新到 `44385/44383` 量级，node 6 的 observed dual bound 达到 `44397.8153846153`，因此不需要继续分支即可关闭。node 10 的最大 observed dual bound 为 `44389.0714972415`，也高于最终 incumbent `44383`。

这说明该剪枝对“restricted LP 已经高于 incumbent，但仍需继续 pricing 证明”的尾部节点有实际价值；它不是根节点加速器。root 的 observed dual bound 从 `44229.70` 逐步抬到 `44369`，始终低于当时 incumbent，因此 root 不能剪。node 4 的最大值 `44383.90` 接近但早期 incumbent 仍为 `44385`，也未触发。总体看，dual-bound pruning 能剪掉后期无望节点，但不能解决 root 和早期节点的 column generation tailing-off。

当前实现已经把这个风险收紧为显式证书口径。`PricingResult` 可以携带 certified reduced cost，PC 只使用这个证书计算 dual bound，不再用返回列里的 best reduced cost。ng-DSSR 每轮 relaxed pricing 会统计完整 relaxed route 中观察到的最小 reduced cost；因为 ng-relaxation 的可行域包含 elementary route，这个 `rc_min_ng <= rc_min_elementary`，用

`DB = z_RMP + maxMachineCount * min(0, rc_min_ng)`

得到的是更弱但安全的 lower bound。若某轮只返回 elementary top-K，也不会把 top-K best 当成 `rc_min`；真正进入 dual-bound 的是该 relaxed 轮的最小值证书。没有观察到完整 relaxed route 时不返回证书，PC 不剪枝。

列化外包模式也按列族证书处理。内部 exact/ng-DSSR 返回内部列族证书后，PC 会在同一套 dual 下绑定执行一次 `OutsourcingPricing`，拿到外包集合列族的 certified `rc_min_outsourcing`，再计算

`DB = z_RMP + maxMachineCount * min(0, rc_min_internal) + min(0, rc_min_outsourcing)`。

因此列化外包不再因为“只证明内部列族”而直接禁用 dual-bound pruning；如果任一列族缺少证书，则 observed dual bound 为 `NaN`，不会剪枝。当前 `enableDualBoundPruning` 默认开启，但实际剪枝只发生在证书齐全且 `DB >= incumbent - tol` 时。

dual stabilization 打开时，stabilized pass 下 pricing engine 的证书对应 stabilized dual，不是当前真实 RMP dual；因此 PC 只在 `acceptanceDual == null` 的真实 dual pass 使用 certified rc 计算 dual bound。stabilized pass 仍可加列，但不触发 dual-bound pruning。

验证：focused `javac` 编译 `PricingResult/OutsourcingPricingEngine/GCNGBBStyleBidirectionalNgDssr*/PC/TWETBPCConfig/TWETBPCContext` 通过；`wet021_001_2m` 在 ng-DSSR、dual-bound pruning 开启下 root smoke 有效；20 任务 case7 的 columns 外包 root smoke 也有效。

本轮同时复查了测试数据的 setup 口径。`data/50-2/wet050_001_2m.dat` 含 `SETUP` 块，非零 setup 项为 `2496`，三角不等式违规数为 `0`、最大违规 gap 为 `0`。此前 `data/` 目录已批量做过 setup 三角闭包修正，后续默认算例应继续保持该口径。
## 8. 2026-06-22：dual stabilization 开关语义复核（历史状态）

本节记录的是移除 penalty 分支之前的复核结论。该状态下 smoothing 和 penalty stabilization 都已经实现，但总开关 `enableDualStabilization` 默认仍为 `false`。因此默认求解不会创建稳定化状态，不会设置 pricing dual override，也不会给 RMP 加 penalty 人工列；`PC.solvePricingLoop()` 会直接走真实 dual 的普通 pricing loop。

当时如果显式打开 `enableDualStabilization=true`，默认配置会同时启用两部分：一是 Wentges/Pessoa 风格 smoothing，用稳定中心和当前 out dual 混合得到 pricing dual，并在 mispricing 时逐步回退到 true dual；二是 coverage-row 的 3-piece penalty RMP。这个口径已经被第 12 节取代：当前代码已删除 penalty 分支，显式开启稳定化时只使用 smoothing。

这个复核说明当前“默认关闭稳定化”和“显式打开时默认组合 smoothing+penalty”是两个不同层次，不能混淆。此前 50/60 任务长尾实验如果没有显式传入 dualStabilization 参数，就没有受到稳定化影响。

## 9. 2026-06-22：smoothing 与已废弃 penalty 的实现口径

当前 smoothing 采用的是 Wentges/Pessoa 风格的 interior/out-point 混合，而不是 box stabilization。每个 node 进入稳定化流程时，先把当前真实 RMP dual 复制为稳定中心 `center`，当前 RMP dual 记为 `outDual`。普通平滑点按

`pi_stab = (1-alpha) * outDual + alpha * center`

构造。代码里的 `PricingDualSnapshot.blend(outDual, center, 1-alpha)` 正是这个口径，因此 `alpha` 越大，pricing 看到的 dual 越靠近稳定中心；`alpha=0` 就退回真实 dual。默认初始 `alpha=0.5`，动态更新时保持 `alpha < 1`。

稳定化 pricing 不是只试一次。若当前 `alpha` 下没有能在真实 out-dual 口径也为负的列，就按 mispricing schedule 逐步回退：

`alpha0, 1 - 2(1-alpha0), 1 - 3(1-alpha0), ... , 0`

直到找到被真实 out-dual 接受的列，或者 `alpha=0` 仍无列。pricing engine 实际用 `pi_stab` 搜列，但返回列会用 `outDual` 重新算 reduced cost；只有 `rc_out < -dualStabilizationReducedCostTolerance` 的列才真正加入主问题。这个设计保证稳定化只改变找列顺序，不改变最终闭合语义。

代码还实现了 directional smoothing。只有在第一轮 stabilized attempt、且上一轮有被接受列的 representative gradient 时启用。它用上一条被接受列的近似 subgradient 判断 `center -> outDual` 方向是否和历史有效方向一致；如果一致，就把普通混合点替换成沿该方向修正后的点。mispricing 回退阶段关闭 directional 项，只做普通线性混合，避免方向项把 dual 继续推离真实 out point。

`alpha` 的更新发生在接受列并重解 LP 后。若代表列的 gradient 与 `trueDual - center` 的内积为正，说明该方向支持从 center 往真实 dual 移动，`alpha` 会按 `alpha += 0.1 * (1-alpha)` 增大，更靠近 center；否则 `alpha -= 0.1`，更靠近真实 dual。无论哪种情况，随后稳定中心都会按

`center = 0.3 * trueDual + 0.7 * oldCenter`

向当前真实 dual 移动。这里的 `0.3` 是 `dualStabilizationCenterMoveWeight`。

以下 penalty stabilization 内容是已删除分支的历史实现记录。它当时采用 coverage-row 的 3-piece L1 penalty RMP，只作用在 job 覆盖行，不作用在机器数、arc branch、外包列数等其他 dual 行。每个 coverage row 会加一对有界人工列 `plus/minus`，上界都是 `gamma`；默认使用 `explicit` 参数化，中心为 `center.jobDual[j]`，窗口半宽为 `delta`，外侧人工列成本分别使用 `max(0, center_j + delta)` 和 `-max(0, center_j - delta)`。

当时 penalty 参数有两类更新。若加列并重解后 penalty artificial activity 已经小于容差，说明当前 penalty 窗口没有明显阻碍主问题，于是 explicit 模式下把 `delta` 减半，收紧稳定窗口；若无列但 artificial 仍为正，则放松 `gamma`。这部分代码已经在第 12 节对应的修改中移除。

当前保留的 smoothing 流程仍然强制回到真实 dual 闭合。稳定化阶段无列后，用 true dual 再跑一轮完整 pricing；只有 true dual 也没有负列时，当前 node 才算真正闭合。

关于 dual-bound pruning，需要区分理论公式和当前实现。理论上 dual bound 并不要求输入一定是当前 RMP 最优 dual；只要给定的乘子满足对应行的符号语义，并且 pricing 对同一套乘子给出了相关列族的全局最小 reduced cost，就可以构造 Lagrangian/dual bound。对单列族、最多使用 `M` 条机器列的口径，形式应是

`DB(pi) = dualObjective(pi) + M * min(0, rc_min_internal(pi))`

列化外包再加一项

`+ min(0, rc_min_outsourcing(pi))`。

这里的关键是 `dualObjective(pi)` 必须用同一套 `pi` 按完整 master 行 RHS 重新计算，而不是直接拿当前 RMP 的 `z_RMP`。只有当 `pi` 正好是当前 RMP 的真实最优 dual 时，才有 `z_RMP = dualObjective(pi)`，当前代码里的 `z_RMP + correction` 才能直接成立。

因此，stabilized pass 不是理论上不能用于 dual bound，而是当前实现没有保存足够完整的 stabilized dual objective 口径。`PricingDualSnapshot` 主要服务 reduced-cost 计算，记录了 job、machine、outsourcing-column 和聚合 arc dual，但没有完整保留每条分支行、adjacency 行、range 行和显式外包/tariff 行对 dual objective 的贡献；arc/adjacency dual 还存在聚合到同一矩阵后的不可逆信息。若要用 stabilized dual 做剪枝，需要扩展 snapshot，保存或可重算 `dualObjective(pi_stab)`，并确保 internal/outsource pricing 都是在同一 `pi_stab` 下给出 certified `rc_min`。在完成这部分之前，当前代码只在 `acceptanceDual == null` 的真实 dual pass 使用 dual-bound pruning，是保守实现，而不是数学上的必要限制。

更具体地说，`dualObjective(pi)` 不是固定使用当前 `z_RMP`，而是要按当前 master 的所有约束行重新计算 `sum rhs_i * pi_i`。覆盖行 `cover_j >= 1` 的贡献是 `1 * pi_j`；机器数 range 行要按 dual 符号选端点，若 `pi_machine >= 0` 则贡献 `minMachineCount * pi_machine`，若 `pi_machine < 0` 则贡献 `maxMachineCount * pi_machine`；等式分支行按 RHS 乘 dual，RHS 为 0 的 forbidden 行贡献为 0，RHS 为 1 的 required 行贡献为对应 dual；列化外包的 `sum omega <= 1` 行若参与 reduced cost，也要贡献 `1 * pi_outsourceCount`。显式外包变量模式下，tariff segment 行、baseline 等式和 segment bound/branch 行也都要纳入同一套 objective，否则得到的不是该乘子下的有效 dual/Lagrangian bound。

因此如果后续要实现 stabilized-dual bound，最稳的做法不是从压缩后的 job/arc dual 矩阵反推，而是在 LP 读出或构造 stabilized snapshot 时同步保存一个 `dualObjective` 标量，或者保存逐行 dual 与 RHS/上下界端点，保证 reduced cost 和 dual objective 使用同一套行乘子。

## 10. 2026-06-22：只看 smoothing 时的流程与 dual-bound 位置

若暂时不考虑 penalty，当前 smoothing 流程为：每次 node 内先解当前 RMP 得到真实 dual `outDual`，并维护一个稳定中心 `center`。stabilized pricing 使用 `(1-alpha) * outDual + alpha * center` 作为 pricing dual；pricing engine 只负责在这套 dual 下找列，返回后由 PC 用真实 `outDual` 重新计算 reduced cost，只有在真实 dual 下仍为负的列才加入主问题。

若当前 `alpha` 下没有被真实 dual 接受的列，代码按 mispricing schedule 把 `alpha` 往 0 回退，直到找到可接受列或退到真实 dual。接受列后重解 RMP，并用代表列的近似 gradient 调整 `alpha`，同时按 `center = 0.3 * trueDual + 0.7 * oldCenter` 更新稳定中心。directional smoothing 只在第一轮 stabilized attempt 中使用，回退阶段关闭。

当前 dual-bound pruning 只在 `acceptanceDual == null` 的显式 true-dual pass 使用。stabilized sequence 内部即使回退到 `alpha=0`，代码仍把它视为稳定化序列的一部分，不计算 dual bound。因此在开启 smoothing 时，现有 dual-bound pruning 主要是保守的 true-dual 闭合/剪枝逻辑；它不会利用 stabilized dual 下的 `rc_min`。要让 stabilized pass 本身也提供 bound，需要额外计算同一套 stabilized dual 下的完整 `dualObjective(pi_stab)`，不能复用当前 `z_RMP`。

参数来源方面，当前实现的机制参考 Wentges/Pessoa 和自动化稳定化文献中的 center/out-point smoothing、mispricing 回退和 directional smoothing 思路；但具体默认数值 `alpha=0.5`、`alphaIncreaseFraction=0.1`、`alphaDecreaseStep=0.1`、`centerMoveWeight=0.3` 是工程实验初值，并不是严格复现某篇文章的固定调参。后续若继续使用 smoothing，需要把这些参数作为实验变量重新标定。

进一步看，显式外包变量 + tariff segment 并不从数学上阻止 stabilized-dual bound。若把完整 master dual 向量 `A` 保存下来，包括所有行 dual 以及变量上下界对应的 dual/bound 贡献，则 `A3=(1-alpha)A_true+alpha*A_center` 仍是完整 dual 的凸组合，`dualObjective(A3)` 也可由 `z_RMP` 与 `centerObj` 线性组合得到。此时显式外包和 tariff 变量已经在 RMP 中完整存在，不需要 pricing；只要 `A3` 对这些显式变量的 dual 约束仍可行，剩下只需要对缺失的机器列族加 `M*min(0,rc_min_internal(A3))` 修正。问题在当前实现：`PricingDualSnapshot` 只保存定价用到的部分 row dual，没有保存完整 dual、变量上界 dual 或显式变量 reduced-cost 可行性信息。因此当前不能直接把 `z_RMP/centerObj` 的线性组合用于 masterVariables 外包模式；若要做，应先引入完整 `DualBoundSnapshot`，或者在任意 row-dual 下显式最小化现有外包/tariff 变量的 reduced-cost bound contribution。

## 11. 2026-06-22：stabilized dual bound 的工程接入

本次把普通 smoothing 点的 dual objective 和 pricing dual 绑定到同一个对象里。`DualStabilizationState` 不再只保存 `center`，同时保存 `centerObjective`；每次真实 RMP 重解并接受列后，`center` 按 `centerMoveWeight` 向当前真实 dual 移动，`centerObjective` 也按同一个权重线性移动。这样普通 smoothing 点 `pi_stab=(1-alpha)outDual+alpha*center` 的 objective 可以同步写成 `(1-alpha)z_RMP+alpha*centerObjective`，不需要重新扫描所有 master 行。

PC 的 stabilized pass 现在会把这个 `boundObjective` 传给生成列流程。如果本轮 pricing engine 给出了 certified `rc_min`，且当前没有 active SRI pricing cut、也不是 directional smoothing 点，就可以用 `boundObjective + maxMachineCount*min(0,rc_min_internal)` 计算 observed dual bound；列化外包模式下仍要求同时拿到外包列族的 certified `rc_min_outsourcing`。稳定化生成的候选列仍按真实 `outDual` 重算 reduced cost 后才允许入池，因此找列语义不变。

有两类情况继续禁用 stabilized-dual bound。第一，SRI active 时，当前 `PricingDualSnapshot` 不混合 SRI dual，partial+SRI 下的 reduced cost 证书和 smoothing objective 不是同一套 dual。第二，directional smoothing 使用的是列 gradient 方向修正点，不是已知 RMP dual 点的凸组合，当前没有安全的 objective 标量。上述情况下 stabilized pass 仍可找列，但日志中的 `observedDualBound` 记为 `NaN`，不会触发剪枝。

第 12 节之后，代码中不再保留 penalty 分支；显式开启 `enableDualStabilization` 后只测试纯 smoothing。验证使用 focused `javac` 编译当前 TWETBPC/HEU/Output 路径通过；全量 `src` 编译仍会在旧 `src/BPC` VRP 包失败，这是旧包与当前 TWET 数据结构不兼容导致，和本次修改无关。

## 12. 2026-06-22：移除 penalty 稳定化分支，只保留 smoothing

根据后续讨论，当前阶段不再保留 coverage-row 3-piece penalty RMP。原因是 penalty 路径会改变 RMP 建模、引入人工列和 profile 重建，使稳定化流程更复杂，而当前真正要验证的是 smoothing 对 dual 抖动和 tailing-off 的影响。因此本次代码中删除了 `LP.DualPenaltyProfile`、coverage 行上的 penalty artificial 变量、PC 中 penalty profile 设置/放松/重建逻辑，以及测试入口里的 penalty 系统属性。

现在 `enableDualStabilization=true` 的含义已经收缩为纯 smoothing：每轮以当前真实 RMP dual 为 out-point，以历史 center 为稳定中心，先用 stabilized dual 做启发式、外包和精确定价；候选列必须用真实 out-dual 重新计算 reduced cost 并通过过滤后才加入主问题。若 stabilized sequence 没有可接受列，则回到 true-dual pricing 做闭合确认。默认求解仍然是 `enableDualStabilization=false`，完全不进入该流程。

dual-bound pruning 的口径也随之简化。普通 smoothing 点如果有可用的 `boundObjective` 和同一 stabilized dual 下的 certified `rc_min`，可以计算 observed dual bound；active SRI 和 directional smoothing 仍暂不使用该 bound，因为当前 snapshot 还没有完整覆盖这些状态下的 dual objective。后续若要继续增强稳定化，优先应在 PC 中把 smoothing 流程进一步模块化，而不是重新引入 penalty RMP。

## 13. 2026-06-22：按 Pessoa 2018 对齐 smoothing 参数与 center 更新

本次重新核对 `Pessoa_2018_Automation and Combination of Linear-Programming Based Stabilization Techniques in Column Generation.pdf` 后，确认当前 smoothing 的主要参数应按论文自动调度口径设置：初始 `alpha=0.5`；非 mispricing 且方向信息支持加强稳定化时，执行 `alpha = alpha + 0.1 * (1 - alpha)`；方向信息支持靠近真实 out-dual 时，执行 `alpha = max(0, alpha - 0.1)`；mispricing 序列中按 `1 - k * (1 - alpha)` 逐步靠近 out-dual，直到 `alpha=0` 回到真实 dual。当前代码保留这些数值作为默认配置。

此前实现里还有一个工程参数 `dualStabilizationCenterMoveWeight=0.3`，每次接受列后把 center 固定比例移向真实 dual。这个参数不是 Pessoa/Wentges 的参数，也会让 center objective 的语义变得不清楚，因此本次删除。现在 `PC` 会把本轮 pricing 使用的 separation dual 和同一点上的 observed dual bound 一起带回；只有当该 bound 有 certified reduced-cost 证书时，才把这个 separation point 作为新的 center/in-point。启发式 pricing 或 directional smoothing 点如果没有安全 objective 标量，则只允许找列，不更新 center，避免用编造的 objective 参与后续 stabilized-dual bound。

同时修正了 alpha 自适应判断使用的 out-point：以前在 RMP resolve 之后用新的 true dual 计算方向信号，现在改为使用本轮 pricing 前的真实 out-dual，更贴近论文中 `g_sep` 与 `pi_out - pi_in` 的判断。directional smoothing 仍保留开关和 `beta=cos(gamma)` 口径；由于当前 `PricingDualSnapshot` 没有完整保存任意 directional point 的 dual objective，它生成的点暂不用于 center 更新和 dual-bound pruning。

验证：focused `javac` 编译 `Basic/Common/TWETBPC/HEU/Output` 通过；`wet020_001_2m` root-only smoke 在 `dualStabilization=true` 下分别测试普通 smoothing 和 directional smoothing，均返回 `ROOT_PROCESSED,obj=bound=6343,valid=true`。测试输出已清理。

## 14. 2026-06-23：SRI active 时禁用 smoothing

当前先不做 SRI 与 smoothing 的组合。实现上不在 smoothing 内部混合 SRI dual 或 SRI 状态；如果当前 node 已有 active subset-row pricing cut，`PC.solvePricingLoop()` 会直接跳过 `solvePricingLoopWithDualStabilization()`，改走原始 true-dual pricing loop。这样 SRI cut 仍可由 partial pricing 的真实 dual 路径处理，但 dual stabilization 只覆盖无 SRI 的纯列生成场景。

这么做的原因是当前 `PricingDualSnapshot` 只保存定价主路径需要的 job/machine/arc/outsourcing dual，没有把 SRI cut state 与任意 stabilized point 的完整 dual objective 一起建模。若强行在 smoothing 中混用 SRI，候选列 reduced cost、observed dual bound 和 center 更新会变成不同口径。后续若要支持 SRI+smoothing，应先定义完整的 SRI-aware dual snapshot 和 bound objective，而不是在现有 smoothing 中加局部特判。

## 15. 2026-06-23：核对原文后修正 center 更新理解

重新核对 Pessoa et al. 2018 第 3-4 节后，确认原文里的 smoothing 是 in-out separation 口径。当前 RMP dual 是 out-point；稳定中心应理解为 in-point。对 Wentges smoothing，in-point 不是每次成功加列的 separation point，而是 dual incumbent，即当前已知最好 Lagrangian bound 对应的点。若 sep-point 被 cut，说明找到负列，主要更新 out-point/RMP；若 sep-point 不能被 cut，则 sep-point 被证明为新的 valid in-point，并且当它带来更好的 Lagrangian bound 时更新 dual incumbent。Neame smoothing 才更接近每轮把 in-point 往 sep-point 移动的口径。

因此当前代码中“只要某次 pass 加入列且有 certified observed dual bound，就把本次 separation dual 设为 center”的策略不是严格的 Wentges 原文实现。它是一个工程化变体，会让 center 跟随成功找列的 sep-point，而不是维护最好 bound 的 in-point。后续若继续按 Pessoa/Wentges 对齐，应把 center 更新改为：维护 best in-point/best Lagrangian bound；mis-separation 或可证明更好 bound 时更新 center；普通 cut 成功但没有改善 in-point 时不应直接替换 center。

## 16. 2026-06-23：实现 Wentges/Neame 两种 center 更新规则

本次把 smoothing center 更新从前一版工程化“成功 pass 的 separation dual 直接替换 center”改为原文两种规则，并新增配置 `dualStabilizationSmoothingRule`，默认 `wentges`。

`wentges` 规则下，center 表示 dual incumbent，也就是当前观察到的最好 Lagrangian bound 对应的 in-point。`PC` 会在 exact pricing 给出同一 dual point 下的 certified `L(pi)` 后，只有当该 bound 优于当前 `centerObjective` 时才替换 center。普通 sep-point 成功 cut/生成列不再自动成为 center。

`neame` 规则下，center 表示上一轮 smoothed/evaluated in-point。只要 exact pricing 给出该 sep-point 的 certified `L(pi)`，就把 center 更新为该 sep-point，不要求它改善历史最好 bound。这个口径更接近 Neame 的递推平滑，而不是 Wentges 的 best-bound incumbent。

为了支持这两种更新，`runPricingPass()` 现在即使没有实际加入列，也会保留“本次 separation dual + certified observed bound”的空 pass 结果；这让 mis-separation 或被过滤后无列的 stabilized pass 仍能更新 in-point。`shouldObserveDualBound()` 也不再依赖 `enableDualBoundPruning`，因为 center 更新需要 `L(pi)` 证书，即使不启用剪枝也应计算该证书；真正剪枝仍只由 `enableDualBoundPruning` 控制。

验证：focused `javac` 编译 `Basic/Common/TWETBPC/HEU/Output` 通过。当前没有跑算例对比，后续应分别测试 `wentges` 和 `neame` 在 50/60 任务 tailing-off 上的行为。

## 17. 2026-06-23：移除 directional smoothing

继续核对 smoothing 流程后，决定暂时不使用 directional smoothing。主要原因是 directional point 不是 `outDual` 与 `center` 的普通凸组合，当前没有安全的 dual objective 标量，因此不能稳定地参与 observed dual bound、center 更新和剪枝判断。保留它会让流程分成“找列可用但 bound 不可用”的特殊分支，增加调参和解释成本。

本次删除 `dualStabilizationDirectionalSmoothing` 配置项和 `PC` 中沿 accepted-column gradient 构造 directional point 的逻辑。当前 stabilized dual 统一为 `outDual` 与 `center` 的线性混合；mispricing 时只通过降低 `alpha` 回退到 true dual。代表列的 gradient 仍用于 alpha 自适应判断，但不再用于生成新的 separation point。

当前 smoothing 流程因此更简单：Wentges/Neame 只决定 center 如何更新；pricing 点始终有明确的线性组合 objective，除 active SRI 外可以继续计算 observed dual bound。

## 18. 2026-06-23：修正 mispricing schedule 中 alpha 的局部/全局语义

重新阅读 Pessoa et al. 2018 Table 1 后，确认文中的 alpha 有两层含义。右侧 dynamic schedule 里的 `alpha_t` 是跨 column generation 迭代的全局稳定化参数；左侧 mispricing sequence 里的 `tildeAlpha = [1 - k(1-alpha)]+` 是在给定当前 `pi_out/pi_in` 和初始 alpha 后生成的局部试探值。局部 `tildeAlpha` 可以逐步退到 0，从而强制回到 true dual，但它不应直接覆盖下一轮的全局 `alpha_t`。

此前 `PC.runStabilizedPricingSequence()` 在 mispricing 回退后会把本轮试到的 `alpha` 写回 `dualState.alpha`。这会导致一次局部回退到 0 后，全局 alpha 也变成 0，后续只能靠 accepted-column gradient 慢慢拉回，和原文 Table 1 的分层语义不一致。

本次把 sequence 内部变量改名为 `trialAlpha`，并且不再写回 `dualState.alpha`。全局 alpha 只在 `observeAccepted()` 中根据代表列 subgradient 信号更新；mispricing schedule 只负责当前 out-point 下的局部回退试探。

## 19. 2026-06-23：对齐原文 alpha 参数口径

继续核对 Pessoa et al. 2018 第 4 节后，确认当前 smoothing 的三个核心数值应直接采用原文口径：初始 `alpha0=0.5`，增大函数 `fincr(alpha)=alpha+0.1*(1-alpha)`，减小函数 `fdecr(alpha)=max(0,alpha-0.1)`。mispricing schedule 使用 `tildeAlpha=[1-k*(1-alpha)]+`。

此前代码额外把 alpha 硬截到 `0.95`，这是工程保护，不是原文动态规则。原文的 `fincr` 从小于 1 的初值出发会渐近 1，本身不会等于 1。因此本次删除 0.95 上界，仅保留 `1-1e-9` 的数值保护，防止外部配置直接给到 1 导致 sep-point 完全不向 outDual 移动。默认初始值和 `+10% / -0.1` 更新规则保持不变。

后续讨论后，当前工程实现仍保留一个更保守的上限 `MAX_SMOOTHING_ALPHA=0.8`。原因是本问题中 pricing 较重，alpha 过大时 mispricing sequence 最多会产生较多局部试探，且 sep-point 过度贴近 center 可能拖慢 tailing-off 阶段。该上限是当前求解器的工程参数，不是原文理论要求；后续如果要复现实验论文口径，可再放开该上限。

## 20. 2026-06-23：50-2 对照后的当前结论

本次使用 `data/50-2/wet050_001_2m.dat` 做 normal ng-DSSR nearestK8/top10、BEST_UB、ALNS/RMIH、all-cycles completion bound、completion-bound arc fixing、pricingOnly subtree 和 midpoint probe/reuse 的并行对照。no-stab 组已完整收敛，结果为 `obj=bound=44383`、`nodes=19`、`solve=2154.215s`、`exact=1588.909s/471 calls`、`heuristic=496.832s/1246 calls`。stab 组在 node5 时总时间已经超过 no-stab 完整求解时间，root 阶段尤其明显：no-stab root exact 为 `118.186s/58 calls`，stab root exact 为 `1325.676s/71 calls`，且 root bound 都是 `44353`。

这说明当前 smoothing 并没有减少有效 CG 迭代，反而让 exact pricing 明显变难，并生成更多对真实 RMP 改善较弱的列。需要特别澄清的是，completion bound 本身每次 exact pricing 都会按当前 dual 重建，换 stabilized dual 不会让 completion bound 失效；真正变化的是 stabilized dual 改变了 reduced-cost landscape，使标签、join 和剪枝压力变大，midpoint probe 的历史复用也可能不再适合当前 stabilized dual。Pessoa et al. 2018 也明确指出，smoothed/interior price vector 可能因为 dual 更稠密、更均匀而让某些 oracle 的 pricing 更难，VRP、cutting stock、vertex coloring 等问题中也观察到 pricing subproblem 变难导致收益不稳定。

因此当前结论为：dual smoothing 在本 TWET-BPC 主线中不适合作为默认组件。代码层面继续保持 `enableDualStabilization=false`，显式打开时只作为实验路径。后续若继续研究，应改成 tail-only 或受控触发：前期仍用 true dual；只有连续多轮 LP 改善极小、exact 只返回少量弱负列、或 tailing-off 明显时，才短暂尝试 smoothing，并且应优先限制在 exact pricing 阶段，不建议让启发式 pricing 全程使用 smoothing。

本节结论确认后，仍在运行的 `tmp-wet050-001-dualstab-compare-stab-20260623` 对照进程已手动中止。该 run 已经足够说明全程 smoothing 在当前配置下明显拖慢，不再继续消耗计算时间。
