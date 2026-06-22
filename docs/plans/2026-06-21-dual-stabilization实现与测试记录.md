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
## 8. 2026-06-22：当前 dual stabilization 开关语义复核

当前代码里 smoothing 和 penalty stabilization 都已经实现，但总开关 `enableDualStabilization` 默认仍为 `false`。因此默认求解不会创建稳定化状态，不会设置 pricing dual override，也不会给 RMP 加 penalty 人工列；`PC.solvePricingLoop()` 会直接走真实 dual 的普通 pricing loop。

如果显式打开 `enableDualStabilization=true`，当前默认配置会同时启用两部分：一是 Wentges/Pessoa 风格 smoothing，用稳定中心和当前 out dual 混合得到 pricing dual，并在 mispricing 时逐步回退到 true dual；二是 coverage-row 的 3-piece penalty RMP，因为 `dualStabilizationUsePenalty=true`。如果只想测试 smoothing，需要额外设置 `dualStabilizationUsePenalty=false`。无论是否使用 penalty，节点闭合前都会清掉 penalty profile 并用原始 RMP + true dual 做最终 pricing 验证。

这个复核说明当前“默认关闭稳定化”和“显式打开时默认组合 smoothing+penalty”是两个不同层次，不能混淆。此前 50/60 任务长尾实验如果没有显式传入 dualStabilization 参数，就没有受到稳定化影响。

## 9. 2026-06-22：smoothing 与 penalty 的具体实现口径

当前 smoothing 采用的是 Wentges/Pessoa 风格的 interior/out-point 混合，而不是 box stabilization。每个 node 进入稳定化流程时，先把当前真实 RMP dual 复制为稳定中心 `center`，当前 RMP dual 记为 `outDual`。普通平滑点按

`pi_stab = (1-alpha) * outDual + alpha * center`

构造。代码里的 `PricingDualSnapshot.blend(outDual, center, 1-alpha)` 正是这个口径，因此 `alpha` 越大，pricing 看到的 dual 越靠近稳定中心；`alpha=0` 就退回真实 dual。默认初始 `alpha=0.5`，并被限制在 `[0,0.95]`。

稳定化 pricing 不是只试一次。若当前 `alpha` 下没有能在真实 out-dual 口径也为负的列，就按 mispricing schedule 逐步回退：

`alpha0, 1 - 2(1-alpha0), 1 - 3(1-alpha0), ... , 0`

直到找到被真实 out-dual 接受的列，或者 `alpha=0` 仍无列。pricing engine 实际用 `pi_stab` 搜列，但返回列会用 `outDual` 重新算 reduced cost；只有 `rc_out < -dualStabilizationReducedCostTolerance` 的列才真正加入主问题。这个设计保证稳定化只改变找列顺序，不改变最终闭合语义。

代码还实现了 directional smoothing。只有在第一轮 stabilized attempt、且上一轮有被接受列的 representative gradient 时启用。它用上一条被接受列的近似 subgradient 判断 `center -> outDual` 方向是否和历史有效方向一致；如果一致，就把普通混合点替换成沿该方向修正后的点。mispricing 回退阶段关闭 directional 项，只做普通线性混合，避免方向项把 dual 继续推离真实 out point。

`alpha` 的更新发生在接受列并重解 LP 后。若代表列的 gradient 与 `trueDual - center` 的内积为正，说明该方向支持从 center 往真实 dual 移动，`alpha` 会按 `alpha += 0.1 * (1-alpha)` 增大，更靠近 center；否则 `alpha -= 0.1`，更靠近真实 dual。无论哪种情况，随后稳定中心都会按

`center = 0.3 * trueDual + 0.7 * oldCenter`

向当前真实 dual 移动。这里的 `0.3` 是 `dualStabilizationCenterMoveWeight`。

Penalty stabilization 采用的是 coverage-row 的 3-piece L1 penalty RMP，只作用在 job 覆盖行，不作用在机器数、arc branch、外包列数等其他 dual 行。若 `dualStabilizationUsePenalty=true`，每个 coverage row 会加一对有界人工列 `plus/minus`，上界都是 `gamma`。当前默认使用 `explicit` 参数化：中心为 `center.jobDual[j]`，窗口半宽为 `delta`，外侧人工列成本分别使用 `max(0, center_j + delta)` 和 `-max(0, center_j - delta)`。初始 `delta = averageAbsDual / kappa`，默认 `kappa=10`；初始 `gamma=0.9`。

Penalty 参数有两类更新。若加列并重解后 penalty artificial activity 已经小于容差，说明当前 penalty 窗口没有明显阻碍主问题，于是 explicit 模式下把 `delta` 减半，收紧稳定窗口；若无列但 artificial 仍为正，则最多放松 `dualStabilizationPenaltyMaxRelaxations=3` 次，explicit 模式下把 `gamma` 除以 10。配置里还留有 `curvature` 模式：该模式会用 gradient norm 和 curvature 参数重算 `delta/gamma`，但默认不是它，默认是 `explicit`。

最终闭合仍然强制回到原始 RMP。稳定化阶段无列后，如果存在 penalty profile，会先清掉 profile、重建原始 RMP 并重解，然后用 true dual 再跑一轮完整 pricing。只有原始 RMP + true dual 也没有负列时，当前 node 才算真正闭合。

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

PC 的 stabilized pass 现在会把这个 `boundObjective` 传给生成列流程。如果本轮 pricing engine 给出了 certified `rc_min`，且当前没有 penalty profile、没有 active SRI pricing cut、也不是 directional smoothing 点，就可以用 `boundObjective + maxMachineCount*min(0,rc_min_internal)` 计算 observed dual bound；列化外包模式下仍要求同时拿到外包列族的 certified `rc_min_outsourcing`。稳定化生成的候选列仍按真实 `outDual` 重算 reduced cost 后才允许入池，因此找列语义不变。

有三类情况继续禁用 stabilized-dual bound。第一，penalty RMP 激活时，当前主问题目标已经不是原始 master 的 dual objective，直接使用会混口径。第二，SRI active 时，当前 `PricingDualSnapshot` 不混合 SRI dual，partial+SRI 下的 reduced cost 证书和 smoothing objective 不是同一套 dual。第三，directional smoothing 使用的是列 gradient 方向修正点，不是已知 RMP dual 点的凸组合，当前没有安全的 objective 标量。上述情况下 stabilized pass 仍可找列，但日志中的 `observedDualBound` 记为 `NaN`，不会触发剪枝。

配置上，`dualStabilizationUsePenalty` 默认改为 `false`，即显式开启 `enableDualStabilization` 后优先测试纯 smoothing；penalty 代码保留，但需要显式打开。验证使用 focused `javac` 编译当前 TWETBPC/HEU/Output 路径通过；全量 `src` 编译仍会在旧 `src/BPC` VRP 包失败，这是旧包与当前 TWET 数据结构不兼容导致，和本次修改无关。
