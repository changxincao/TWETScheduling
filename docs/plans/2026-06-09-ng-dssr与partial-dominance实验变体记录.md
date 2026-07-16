# ng/DSSR 与 partial dominance 实验变体记录

本次修改的核心边界是：不在当前主用的 `GCNGBBStyleBidirectional` 上直接叠加实验逻辑，而是基于当前半域 GCNGBB-style pricing 复制出独立实验变体。这样后续比较 ng-relaxation、DSSR 和 partial dominance 时，可以通过配置开关单独进入，不会改变默认 elementary exact pricing 的行为。

1. 实验分支划分

第一条分支是 `GCNGBBStyleBidirectionalPartialDominance`，只替换 dominance backend。它沿用当前 elementary 半域扩展、completion bound、Tmid probe 和 final join 流程，不接 ng/DSSR/SRI。新增 `PartialListDominanceStore` 后，普通函数 label 之间不再只做整条 frontier 的完全支配，而是用 `PiecewiseLinearFunction.updateDominatedIntervals()` 裁掉被已有 label 压住的区间；如果 frontier 被裁空，则该 label 被丢弃。这个分支由 `useGCNGBBStylePartialDominancePricing=false` 默认关闭。

第二条分支是 `GCNGBBStyleBidirectionalNgDssr`，目标是第一版 ng-relaxation + DSSR。该类同样是主类复制版，默认关闭。它保留当前 all-cycle completion bound、half-domain 函数递推和 final join 框架，但把 elementary 的“已访问任务不可再扩展”改为 ng memory 约束：label 仍保留 `visitedSet` 和 father chain，用于恢复完整序列和判断是否 elementary；扩展时使用 `ngMemorySet`，按 `(parentMemory ∩ N_next) ∪ {next}` 更新。由于 ng memory 会遗忘邻域外任务，child 的可扩展集合不再保证是 parent 的子集，因此新类中重新扫描 `1..n` 构造 `reachableSet`。

2. DSSR 处理

ng/DSSR 版本的 join 阶段不再用 `visitedSetsIntersectForJoin()` 直接剪掉所有重复访问。拼接后若得到 elementary negative route，则按原候选堆逻辑加入 RMP 候选；若得到 non-elementary negative route，则不进入 `activeColumnSignatures`，也不进入 RMP candidate pool，只记录序列用于 DSSR 更新。

DSSR 更新规则采用当前计划文档中的第一版保守口径：若 relaxed route 中出现重复节点 `i ... i`，则把重复节点 `i` 加入中间节点的 ng-neighborhood。也就是对片段中每个中间任务 `j` 执行 `i in N_j`。如果一轮 relaxed pricing 只发现 non-elementary negative routes，就更新 ng-set 后重跑；如果没有任何 negative route，则按 relaxed pricing 结果退出；如果达到 `ngDssrMaxRounds` 仍不能证明，则默认回退当前 elementary `GCNGBBStyleBidirectional`，避免把非 exact 结果当作证书。

3. 配置与测试入口

新增配置项包括 `useGCNGBBStyleNgDssrPricing`、`ngDssrInitialNgSetMode`、`ngDssrInitialNgSetSize`、`ngDssrMaxRounds`、`ngDssrMaxNonElementaryRecords` 和 `ngDssrFallbackToElementaryPricing`。`GCBBFullDomainComparisonTest` 透传了 `partialDominance` 与 `ngDssr` 相关系统属性，便于后续用同一批小算例做对照。

本次验证包含 focused 编译和轻路径 smoke。编译命令使用 CPLEX jar 覆盖两个实验类、engine、配置、上下文和测试入口。`tmp-wet020_001_2m`、`maxNodes=1`、`maxExactColumns=0` 下，`ngDssr=true` 与 `partialDominance=true` 两条路径均能调用对应 engine 并返回 `valid=true`。这里的 smoke 只验证接入路径，不评价定价能力；曾尝试 partial dominance 的完整 root smoke，但耗时超过一分钟，已中止，说明该分支需要后续专门做短预算性能测试。

4. 当前风险

第一，ng/DSSR 版本当前复用 paper dominance graph 的 `reachableSet` superset 语义，实际使用的是“ng memory 下仍可扩展的 job 集合”。这与“不可用集合越小越强”的表述等价，但不是单独实现反向 unavailable graph。若后续要严格复刻旧 VRP 图结构，可以再把 dominance key 改成 unavailable set 并换用专门图结构。

第二，DSSR 更新方式按计划文档实现为“重复节点加入中间节点邻域”。旧 VRP 源码这次没有定位到可直接核对的 `GCNGBB` 实现，因此第一版先按文档语义落地。若后续找到旧实现，需要再对更新方向和初始 ng-set 口径做一次对拍。

第三，当前轻 smoke 关闭了 exact 列生成上限，只验证 engine 初始化、退出和 BPC solution validator。真正要判断 ng/DSSR 是否减少 label 或改善 hard node，需要在 20/25/30 任务上做短预算对照，并重点看每轮 DSSR 的 non-elementary route 数、ng-set 更新数、最终 fallback 频率和 exact pricing 时间。

5. 2026-06-09 正确性与速度复测

本轮先修正了 ng-DSSR join 阶段的一个低效点：group 级仍按主版本先剪掉 `backward.visitedSet` 已含 terminal job 的拼接；pair 级如果 forward/backward 的 visited set 已经重叠，则直接计入 set-pruned，不再为了记录 DSSR 重复 route 对每个重叠 pair 回溯完整 father chain。DSSR 的重复 route 记录仍保留在少量实际 relaxed negative route 上。这个改动没有改变 RMP 入列口径，只避免把大量本来应被集合剪枝的 pair 变成序列恢复成本。

小算例正常口径（heuristic pricing 开启、completionBound=allCycles、root 节点）下，`wet015_001`、`wet015_002`、`tmp-wet020_001`、`tmp-wet022_001`、`tmp-wet025_001` 的 main 与 ng nearest8 均得到相同 root bound/incumbent，`valid=true`。代表性时间为：15 任务 ng root 约 `1.038s`、20 任务 ng root 约 `2.131s`、25 任务 ng root 约 `3.073s`；25 任务 main 约 `3.271s`。因此当前没有看到 ng 变体在小 root 上破坏正确性。

需要区分的是，之前用于压力诊断的 exact-only 口径关闭了 heuristic pricing，不能和历史正常求解时间比较。在这个压力口径下，修正前 15 任务 full-ng 超过 80 秒；修正后 full-ng exact-only 约 `22.008s`，main exact-only 约 `29.325s`，nearest8 exact-only 约 `15.411s`。这说明 overlap pair 回溯确实是一个实际低效点，但 exact-only 不是主流程性能结论。

30 任务 `tmp-wet030_from040_013_2m`、`maxNodes=2` 的正常口径结果如下：main 为 `NODE_LIMIT, incumbent=14474, bound=14287.625, gap=1.287654%, solve=22.004s, exact=7.980s, exactCalls=6, valid=true`；nearest8/maxRounds3 为 `solve=62.320s, exact=41.673s, exactCalls=11, incumbent=14573`；nearest8/maxRounds1 为 `solve=53.015s, exact=26.273s, exactCalls=11, incumbent=14573`；nearest16/maxRounds2 为 `solve=51.118s, exact=26.089s, exactCalls=9, incumbent=14573`；full-ng/maxRounds1 为 `solve=38.269s, exact=15.643s, exactCalls=8, incumbent=14474`。五组均 `valid=true`，但 ng nearest8/16 在该分支节点明显慢于 main，且上界路径更差。

日志拆分显示，nearest8/16 的慢点不在 join 函数评价本身，而在 DSSR 多轮和 completion bound 重建。以 node2 为例：main exact pricing 合计约 `4.702s`、CB build 约 `1.821s`；nearest8/maxRounds3 node2 合计约 `38.637s`、CB build 约 `9.082s`，出现 3 次 fallback，non-elementary route 合计 387；nearest16/maxRounds2 node2 合计约 `24.939s`、CB build 约 `7.666s`，出现 2 次 fallback，non-elementary route 合计 1376。full-ng 没有 non-elementary route 和 fallback，但 node2 仍约 `11.944s`，因为 full-ng 更接近 elementary，保留了较多 label 和 completion bound 重算成本。

当前结论是：ng-DSSR 作为实验变体已经能在小 root 上保持正确，且 overlap 剪枝修正后没有明显异常；但 nearest ng-set 在 30 任务分支节点上不应直接作为默认 exact pricing。它可能减少部分 join/function evaluation，却会因为 relaxed route 非 elementary 而触发多轮 DSSR、重复构造 completion bound，并改变列生成路径。后续若继续测试，建议优先比较 `initialNgSetSize=16`、`maxRounds=1` 或 `2`、以及“只在 hard node fallback/诊断时启用”的口径；在没有更大样本支撑前，主线仍保留 elementary `GCNGBBStyleBidirectional`。

6. 重新核对旧 VRP GCNGBB 后的修正判断

用户追问后重新读取 `D:\软件\Trae\项目\BPC\src\BPC\GC\GCNGBB.java`，当前实验版与旧 GCNGBB 仍有几个关键偏差。旧 `Extend()` 在一次调用开头先构造 bound 表，然后在 DSSR while 循环里反复 `FWExtend -> UpdateFWBound -> BWExtend -> UpdateBWBound -> Join -> UpdateNGSet`。bound 表不是每一轮 DSSR 都从头重建；ng-set 的变化主要影响后续 label 扩展和由实际 label 修正的 bound，而不是要求重新计算所有静态 bound。当前 TWET 的 `CompletionBoundCalculator` 又没有读取 ng-set，所以在同一个 LP dual、branch 和 `Tmid` 下每轮重建 completion bound 确实是冗余的。

旧 GCNGBB 也没有“达到固定 DSSR 轮数后 fallback 到 elementary pricing”的默认语义。它的停止条件是：如果本轮找到合法 elementary 负列，则停止并返回这些列；如果没有合法负列且没有 duplicate negative route，则说明当前 ng 放松下也没有负列，可以停止；如果没有合法负列但有 duplicate negative route，则 `UpdateNGSet()` 后继续。当前实验版的 `ngDssrMaxRounds + fallbackToElementary` 是为了避免第一版测试时把“未证明”误当成“无负列”，严格说是工程兜底，不是旧流程。若后续要真正测试 ng-DSSR，应把 fallback 关掉或去掉固定轮数，改成继续 DSSR 到有合法负列或无 duplicate 证据为止；否则测试到的是“ng 若干轮 + elementary 兜底”，不是纯 ng-DSSR。

更重要的是 join 里的 duplicate 处理。旧 GCNGBB 在 join 前先检查的是 ng-memory 冲突；如果拼接 reduced cost 为负，再恢复 route，并用真实 visit/duplicate mask 判断是否非 elementary。真实重复 route 不进 pool，只更新 `m_best_cycle`，供下一轮 `UpdateNGSet()` 收紧。当前实验版为了效率在 group/pair 层用真实 `visitedSet` overlap 直接剪掉拼接，这对 elementary 主类安全，但对 ng-DSSR 不完全对：它会漏掉“ng-memory 允许、真实 visit 重复”的 negative route 证据，DSSR 因而少了应该加入 ng-set 的环。后续应改为按 `ngMemorySet` 做便宜剪枝，只有函数级 reduced cost 确认可能为负后再恢复序列，用真实 visit 重复判断是入候选池还是记录 duplicate cycle。

因此，当前 ng-DSSR 只能算第一版实验接入，不应说已经严格 follow 旧 VRP 的 GCNGBB。正确的下一步不是继续比较速度，而是先把三件事对齐：1）completion bound 在同一 LP/DSSR 调用内复用；2）取消固定轮数 fallback 作为默认行为；3）join duplicate 证据按旧逻辑从 visited-overlap 早剪改为 ng-memory 早剪、negative route 后记录真实 duplicate。


7. 2026-06-09 按旧 GCNGBB 语义修正 ng-DSSR

本轮把 `GCNGBBStyleBidirectionalNgDssr` 的外层 DSSR 流程改回更接近旧 VRP 的语义：一次 `solve()` 内不再按固定 `ngDssrMaxRounds` 截断，也不再 fallback 到 elementary `GCNGBBStyleBidirectional`。现在每轮 relaxed pricing 后，如果找到 elementary negative columns 就直接返回；如果没有任何 negative route 就退出；如果只找到 non-elementary negative route，就用该 route 更新 ng-set 后继续。由于 ng-set 单调扩张且任务数有限，这个流程本身有有限终止边界，后续性能比较也不再混入“ng 若干轮 + elementary 兜底”的结果。

completion bound 也改为一次 `solve()` 内只构造一次。当前 `CompletionBoundCalculator` 不依赖 ng-set，DSSR round 之间 LP dual、branch 状态、pricing horizon 和 midpoint reference 都不变，因此每轮重建 completion bound 是冗余的。实现上保留第一次构造出来的 `completionBounds` 和 arc-fixing 矩阵；后续 DSSR round 只复用该结果。旧 VRP 里的 `UpdateFWBound/UpdateBWBound` 这类动态更新本轮没有移植，只先去掉明显重复的静态 bound 重建。

join 阶段改为先检查 forward/backward 的 `ngMemorySet` 是否冲突，而不是用真实 `visitedSet` overlap 提前剪掉。只有 ng-memory 不冲突且 reduced-cost 函数拼接确认可能为负后，才恢复完整 sequence。恢复后如果真实 visit 无重复，则按原候选堆逻辑加入本地 pool；如果真实 visit 有重复，则不加 pool，只记录本轮最负的一条 non-elementary route，作为旧 VRP 中 `m_best_cycle` 的对应物。下一轮 `UpdateNGSet()` 只基于这一条 best cycle，把重复 job 加入中间 job 的 ng-neighborhood。

当前 ng-DSSR 类仍然沿用主 `GCNGBBStyleBidirectional` 中已有的 completion bound、Tmid strategy、midpoint probe 和 rank0 probe label 复用逻辑；本轮没有新增 probe 策略，也没有删掉原 probe 接入。join 仍保持 crossing-arc join，不改成 node join。由于固定轮数、记录多条 non-elementary route 和 fallback 已不再参与当前流程，`TWETBPCConfig` 与 `GCBBFullDomainComparisonTest` 中对应的 `ngDssrMaxRounds/ngDssrMaxNonElementaryRecords/ngDssrFallback` 配置入口也已删除，避免后续误传无效参数。

验证方面，focused `javac` 已通过。`tmp-wet020_001_2m,maxNodes=1,ngDssr=true,completionBound=allCycles,midpointProbe=true` 得到 `ROOT_PROCESSED, obj=bound=6343, exact=0.288s, valid=true`；日志显示 ng-DSSR 运行 4 轮，累计记录 3 条 non-elementary best cycle，更新 ng-set 8 次，最后以 relaxed pricing 无负 route 退出，没有 fallback。`tmp-wet030_from040_013_2m,maxNodes=1,ngDssrInitialSize=8` 得到 `NODE_LIMIT, incumbent=14573, bound=14287.625, exact=11.853s, exactCalls=10, valid=true`；其中部分 exact call 出现多轮 DSSR，例如最后一轮为 `rounds=13,totalNonElementaryRoutes=12,totalNgSetUpdates=39`，且后续 DSSR round 的 `completionBound buildMs=0`，说明 bound 复用生效。该测试只确认流程和正确性，不说明 ng-DSSR 已优于 elementary 主线。


8. 2026-06-09 当前 ng-DSSR 与 elementary 浅层对比

本轮按当前代码补了同口径浅层对比。`tmp-wet020_001_2m,maxNodes=1,completionBound=allCycles,midpointProbe=true` 下，elementary `GCNGBBStyleBidirectionalPricing` 为 `solve=1.446s, exact=0.239s, calls=1, valid=true`，ng-DSSR 为 `solve=1.926s, exact=0.346s, calls=1, valid=true`。`tmp-wet030_from040_013_2m,maxNodes=1,completionBound=allCycles,midpointProbe=true,midpointStrategy=completionBound` 下，elementary 为 `solve=20.320s, exact=7.157s, calls=3, incumbent=14474, bound=14287.625, valid=true`；ng-DSSR nearest8 为 `solve=27.976s, exact=11.853s, calls=10, incumbent=14573, bound=14287.625, valid=true`。

因此当前结论仍是：修正后的 ng-DSSR 流程语义更接近旧 VRP，但在这两个浅层口径下没有比 elementary 快，30 任务 013 上还因为 DSSR 多轮和列生成路径变化明显更慢。join 里的预判口径与 elementary 主类一致：先用 group/pair 层的 min reduced-cost 标量下界和时间下界过滤，再做 PWLF shift/add/findMinimal 的完整函数拼接；completion bound 仍主要用于 label 扩展剪枝，不是在 final join 里额外做一层 relaxed suffix 拼接。

9. 2026-06-09 再次核对 ng-DSSR 流程与速度差异

本轮重新检查当前 `GCNGBBStyleBidirectionalNgDssr` 后，判断其外层已经基本按旧 VRP GCNGBB 的 DSSR 语义处理：一次 `solve()` 内初始化 ng-neighborhood，逐轮运行 relaxed bidirectional pricing；若本轮返回 elementary negative columns，则直接返回这些列；若没有任何 negative route，则说明 relaxed pricing 下也无负列并退出；若只有 non-elementary negative route，则用记录到的 best cycle 更新 ng-set 后继续。当前已经没有固定 DSSR 轮数后的 elementary fallback，completion bound 在同一 `solve()` 内复用，后续 DSSR round 的日志中可看到 `completionBound buildMs=0`。

join 逻辑也已经按 ng-DSSR 口径修正：group 层先检查 `backward.ngMemorySet.contains(lastJob)` 和 crossing arc forbidden；pair 层先检查 `forward.ngMemorySet` 与 `backward.ngMemorySet` 是否冲突。只有 ng-memory 允许且 reduced-cost 函数拼接为负时，才恢复完整 sequence；恢复后若真实任务无重复就进入候选列池，若真实重复则不入池，只记录 non-elementary best cycle。也就是说，当前不再用真实 `visitedSet` overlap 提前剪掉本应供 DSSR 学习的 negative route。

当前初始 ng-set 口径为 `nearestK`，默认 size 为 8，且 size 包含任务自身。每个任务 `j` 的初始 `N_j` 先包含 `j`，然后按 `setupTime(j,k)+setupTime(k,j)+setupCost(j,k)+setupCost(k,j)` 的对称距离从小到大加入最近任务，直到 `|N_j|=8`。如果配置 `ngDssrInitialNgSetMode=empty`，则只保留任务自身；测试入口可用 `twet.bpc.fullDomainCompare.ngDssrInitialMode` 和 `twet.bpc.fullDomainCompare.ngDssrInitialSize` 覆盖。

本轮用当前代码补做了同口径浅层复测。`tmp-wet020_001_2m,maxNodes=1,completionBound=allCycles,midpointProbe=true` 下，elementary 为 `solve=1.734s, exact=0.286s, calls=1`，ng-DSSR nearest8 为 `solve=1.666s, exact=0.318s, calls=1`。这个小例中总时间差异属于波动，exact pricing 本身 ng 仍略慢。

`tmp-wet030_from040_013_2m,maxNodes=1,completionBound=allCycles,midpointProbe=true,midpointStrategy=completionBound` 下，elementary 为 `solve=20.779s, exact=7.991s, calls=3, incumbent=14474, bound=14287.625`，ng-DSSR nearest8 为 `solve=38.881s, exact=15.976s, calls=10, incumbent=14573, bound=14287.625`。这说明当前比较不是别的全局配置不同导致的：入口层面只切换 exact pricing engine，heuristic pricing、RMP、completion bound、midpoint probe 配置一致；但 ng-DSSR 自身会改变列生成路径。第一轮 elementary 一次 exact 生成 1229 条列，而 ng-DSSR 第一轮只生成 103 条列，导致后续需要更多启发式轮和更多 exact pricing 调用。ng 日志后段还出现 `rounds=6,totalNonElementaryRoutes=6,totalNgSetUpdates=27` 和最终 `rounds=13,totalNonElementaryRoutes=12,totalNgSetUpdates=39`，这部分就是 elementary 没有的 DSSR 额外成本。

当前结论是：流程语义已经比上一版更接近旧 VRP，但“当前 ng-DSSR 比 elementary 慢”仍成立，尤其在 30 任务 013 浅层口径下更明显。慢因不是单个 join 函数评估过多，而是 ng-relaxation 放松后产生 non-elementary route，DSSR 多轮收紧，加上初始 nearest8 较松导致每轮返回的 elementary 负列少，列池增长慢，进而让 RMP dual 和后续 pricing 路径都偏离 elementary。后续如果继续优化，应优先试更大的初始 ng-set、只在 hard node 启用、或者把 best cycle 更新和初始 ng-set 做得更强，而不是简单期待 nearest8 ng-DSSR 自动快于 elementary。

10. 2026-06-09 旧 VRP type-2 初始 ng-set 与当前入列逻辑核对

旧 `GCNGBB.java` 的 `ChooseNeighbor()` 不是按空间最近邻预置 ng-set，而是在 `data.m_type == 2` 时按当前 LP dual 计算 pair reduced cost：`c_ij - arc_mu_ij - mu_j + c_ji - arc_mu_ji - mu_i`，每次挑若干个两方向合计最负的 customer pair，把二者互相加入 ng-set。这个思路更偏向“先记住当前 dual 下最可能形成二环/重复奖励的 pair”，不是静态几何邻近。当前 TWET 默认 `nearestK,size=8` 是静态 setup-time/setup-cost 近邻，稳定但不随 dual 变化；如果改成旧 type-2 口径，应作为新的 `dualPair` 初始模式测试，不宜直接替换默认，因为它会改变每轮 LP dual 下的 ng-set 初始状态和列生成路径。

当前 `GCNGBBStyleBidirectionalNgDssr` 的入列逻辑是在每个负 reduced-cost 拼接 route 恢复 sequence 后立即判断：若 sequence 不兼容当前 pricing 禁弧则丢弃；若真实 job 重复，则不入候选池，只记录本轮 reduced cost 最负的一条 non-elementary route 作为 best cycle；若真实 elementary 且未在 active pool 中重复，则进入 top-K 候选堆。最后 `finalizeGeneratedColumns()` 只排序并输出候选堆，不再重新全量扫描基本性。根节点 no-cut 的 pi-window 口径下，候选列在最终输出前会用 `TWETColumnEvaluator` 重算真实 objective cost，避免把 pi-window 推导成本写入永久列池；非 pi-window 口径则沿用 inferred cost。

ng-set 更新也基本沿用旧 VRP 的 best-cycle 思路：每轮只保留最负 non-elementary route；更新时扫描该 route 中重复 job 的两次出现位置，把两次出现之间的中间 job 的 ng-neighborhood 加上这个重复 job。不同点主要在初始 ng-set：旧 VRP 默认空集，type-2 时可用 dual pair 预置；当前 TWET 默认 nearestK 静态预置。

11. 2026-06-09 增加 dualPair 初始 ng-set 与禁弧热路径检查

本轮按旧 VRP type-2 思路增加了 `ngDssrInitialNgSetMode=dualPair`，但按当前要求只使用 setup cost、job dual 和 arc dual，不引入 TWET job 时间函数。具体口径为对每个无序 pair `(i,j)` 计算 `setupCost(i,j)-arcDual(i,j)-pi_j + setupCost(j,i)-arcDual(j,i)-pi_i`，只保留负值 pair，按 reduced pair cost 从小到大互相加入 ng-neighborhood，且仍受 `ngDssrInitialNgSetSize` 限制。`nearestK` 与 `empty` 原模式保留。

同时删掉了 `tryGenerateColumn()` 中恢复 sequence 后再次调用 `isSequenceCompatible()` 的热路径扫描。当前 generated route 的 source/internal/crossing/sink arc 已分别在 forward/backward 扩展、forward-to-sink 和 crossing join 中检查 pricing 禁弧；恢复 sequence 后再逐弧扫描属于冗余。已有 restricted column 的 timing 统计仍保留兼容性检查，因为那不是由当前定价扩展生成的路径。

验证方面，focused `javac` 通过。`tmp-wet020_001_2m,maxNodes=1,ngDssrInitialMode=dualPair` 得到 `ROOT_PROCESSED,obj=bound=6343,exact=0.504s,valid=true`。`tmp-wet030_from040_013_2m,maxNodes=1,ngDssrInitialMode=dualPair,midpointStrategy=completionBound` 得到 `NODE_LIMIT,incumbent=14573,bound=14287.625,exact=17.518s,calls=9,valid=true`。这个浅层结果说明 dualPair 能正常运行，但没有改善当前 013 的 ng-DSSR 速度；日志中后段仍有 `rounds=16,totalNonElementaryRoutes=15,totalNgSetUpdates=63`，说明只按 setup cost 与 dual 预置 pair 仍不足以减少当前 TWET 的 DSSR 重复 route 压力。

12. 2026-06-09 ng-DSSR 与 partial dominance 当前结论

本轮按用户要求重新把当前实现、已有日志和新补的浅层测试放在一起看。ng-DSSR 当前效果一般，不是因为流程明显写错，而是因为它在这些 20/30 任务算例上没有把 elementary 状态爆炸换成更便宜的证明过程。`tmp-wet030_from040_013_2m,maxNodes=1` 的同口径结果里，elementary 第一轮 exact 能生成约 1229 条负列，而 nearest8 ng-DSSR 第一轮只有约 103 条负列；后续又出现 `rounds=6/13/16`、`totalNonElementaryRoutes=6/12/15`、`totalNgSetUpdates=27/39/63` 这类多轮 DSSR 收紧。也就是说，ng 放松确实让单轮状态更松，但这些松弛解大量是 non-elementary，不能直接进 RMP，反而造成“多轮收紧 + 列池增长慢 + dual 路径改变”的额外成本。

因此，ng-DSSR 是否能在更大规模体现作用，要看两个条件。第一，elementary 主定价的瓶颈必须主要来自 visited-set 维度，而不是 completion bound 构造、Tmid 选择、required adjacency dual 或 final join；第二，初始 ng-set 和 DSSR 更新必须足够强，使 non-elementary 轮数很少。如果任务规模继续增大到 elementary label 明显不可承受，而 ng 的 relaxed label 能保持较少轮数收紧，它可能有用；但在当前 20/30 任务和已有 completion bound/probe 体系下，证据不支持把 ng-DSSR 设为默认。短期更合理的定位是 hard-node 诊断/实验分支，或继续试更大的初始 ng-set、dualPair+nearest 混合、以及更强 best-cycle 更新。

partial dominance 当前语义上比早期担心的风险小一些。`PartialListDominanceStore` 只在 reachable set 具备超集关系时才允许一个 label 裁另一个 label 的函数区间，forward/backward 分别调用方向化的 `updateDominatedIntervals(..., Direction.FORWARD/BACKWARD)`；底层 PWLF 随机测试此前已经覆盖方向化 normalize/merge/updateDominatedIntervals，当前没有看到明显的函数方向错误。新补的当前代码浅测中，20 任务 root：normal 为 `solve=2.305s, exact=0.368s`，partial 为 `solve=2.481s, exact=0.437s`，目标和 bound 一致且 `valid=true`；30 任务 013 root：normal 为 `solve=23.301s, exact=8.443s`，partial 为 `solve=25.585s, exact=8.058s`，目标和 bound 也一致。

但 partial dominance 的性能收益还不稳定。013 第一轮 partial 日志显示它确实大量裁区间：`partialList labels kept/rejected/deleted=7090/5510/409`，`trims partial/full=574542/5919`；同时也做了 `comparisons=2403934`，这本身就是很重的 list-based 成本。partial 第一轮只生成 520 条列，而 normal 第一轮生成 1229 条列；这会改变后续 RMP dual 和启发式 pricing 路径。因此当前结论是：partial dominance 看起来没有明显正确性错误，局部 exact pricing 可能略快，但整体 BPC 路径会被改变，且 list-based 比较成本高，不应作为默认。若继续推进，应先做受控快照对拍：同一 node、同一 dual、同一 Tmid 下比较 normal 与 partial 的负列集合/最小 reduced cost，再考虑把 list store 改成带索引的 partial dominance，而不是现在这种全 list 扫描。

13. 2026-06-09 partial list dominance 的含义与 subset row cut 关系

这里的 partial list dominance 指的是：对同一类状态下的 label，不再只判断“一个 label 是否整体支配另一个 label”，而是允许一个 label 只在某些时间区间上支配另一个 label，并把被支配 label 的分段线性函数定义域裁掉一部分。剩下没有被裁掉的区间继续保留。因此它维护的是一个 list of labels/frontiers，而不是每个状态只留一个最优 label。当前实现还加了 reachable-set 超集条件，只有支配 label 后续可接任务集合至少覆盖被支配 label 时，才允许裁剪；否则即使当前函数值更低，也不能删，因为后续可扩展性不一样。

从实测看，它不是明显慢很多。20 任务 root 上，normal 为 `solve=2.305s, exact=0.368s`，partial 为 `solve=2.481s, exact=0.437s`，总时间约慢 7.6%，exact pricing 约慢 18.8%。30 任务 013 root 上，normal 为 `solve=23.301s, exact=8.443s`，partial 为 `solve=25.585s, exact=8.058s`，总时间约慢 9.8%，exact pricing 反而约快 4.6%。也就是说当前主要问题不是“完全不能用”，而是它会改变生成列路径：013 第一轮 normal 生成约 1229 条负列，partial 生成约 520 条负列，后续 RMP dual、启发式 pricing 和分支路径都会被带偏。因此只看单次 exact pricing 时间不够，必须看完整 BPC 路径。

如果后续要做 subset row cut，partial dominance 可能更值得保留为实验方向。subset row cut 会让 reduced cost 多出 cut dual 项，label 的“历史信息/已访问组合”对可否继续支配更敏感，简单全域支配更容易失效或过保守。partial dominance 的好处是可以在函数区间层面保留一部分仍有用的 label，不必因为某一段被支配就整条丢掉。但前提是状态 key 必须把 subset row cut 需要的 cut 状态或资源计数纳入支配判断，否则会有错误删 label 的风险。因此当前结论是：如果近期要上 subset row cut，可以先保留 partial list dominance 作为对照分支；但在正式打开前，需要做同一 node/dual/Tmid 的快照对拍，并检查 cut 状态是否进入 dominance key。性能上，当前 10% 左右的总时间差异还可以接受，真正要优化的是 list 扫描的比较次数，而不是先把这条路删掉。

14. 2026-06-09 ng-memory 与 dominance graph 的语义风险

本轮重新检查 `GCNGBBStyleBidirectionalNgDssr` 后，确认当前 ng-DSSR 版本存在一个比速度更重要的问题：label 已经有 `ngMemorySet`，扩展时也按 `newMemory=(oldMemory∩N_current)∪{current}` 更新，但普通 dominance graph 和 single-point store 仍然只按 `reachableSet` 做 key、superset 查询和单点支配。也就是说，`FWTL/BWTL.insertOrDominate(label)` 实际进入的是 `PaperDominanceGraph`，其节点 key 仍是 `label.reachableSet`；`SinglePointStore.bestByReachable` 也只用 reachable-set 做 HashMap key。当前没有把 `ngMemorySet` 放进 dominance 状态。

这在 ng-relaxation 下不是单纯效率问题，而是 correctness 风险。原因是 reachable-set 只表达“当前终端下这一跳能扩展到哪些 job”，不能完整表达未来 memory 如何演化。两个 label 当前 reachable-set 可以相同，但 memory 不同；它们扩展到同一个后继 k 后，若某个被记住的 job 属于 `N_k`，它会继续被保留在 memory 中，否则会被遗忘。于是下一步可行重复访问集合可能分化。当前 graph 若只看 reachable-set 和函数值，就可能删掉一个当前不优、但未来因 memory 更松而能产生负 relaxed route 或 elementary route 的 label。

因此，ng-DSSR 的 dominance 至少应先改成保守安全口径：同 terminal、同方向、同 `ngMemorySet` 的 label 才允许互相 dominance；single-point store 也要把 `ngMemorySet` 纳入 key。更强的做法是改成 unavailable-set 语义，即用 `ngMemorySet` 加上时间/资源导致的不可达集合构成支配状态，要求支配 label 的 unavailable set 不大于被支配 label。后者更强但改动更大。当前在修正前，不应把 ng-DSSR 结果当作严格可靠的 exact pricing 结论，只适合作为实验诊断。

DSSR 更新本身也有两个稳定性问题。第一，当前 `recordNonElementaryNegativeSequence()` 每轮只保留最负的一条 non-elementary negative route，这和部分旧实现思路接近，但如果 non-elementary route 很多，会使 ng-set 收紧很慢；后续可以考虑 top-K 记录。第二，如果本轮存在 non-elementary negative route，但 `updateNgNeighborhoodsFromNonElementaryRoutes()` 返回 `changed==0`，当前代码直接返回空列。这个只有在能够证明该 relaxed route 已经无法再通过 ng-set 排除、且不存在其他可更新的 non-elementary route 时才安全；当前因为只记录 top-1 route，`changed==0` 也可能只是这条 best route 无更新，而未记录的其他 route 仍可更新。因此这个分支后续应改为 fallback 到 elementary exact pricing，或扩大记录并强制更强的 cycle 更新。短期结论是：ng-DSSR 下一步优先修 dominance key 和 `changed==0` 兜底，再谈速度比较。

15. 2026-06-09 ng-DSSR dominance key 修正

本轮按更强的 ng 状态语义修改 `GCNGBBStyleBidirectionalNgDssr`。ng label 现在区分两套集合：`extensionSet` 只用于 forward/backward 扩展枚举；dominance 使用 `dominanceUnavailableSet`，其内容为当前 `ngMemorySet` 与资源/半域/时间导致的不可达 job 的并集。由于现有 `PaperDominanceGraph` 的结构方向是“key 越大越强”，实现中传给 graph 的 `label.reachableSet` 不再表示一跳 reachable set，而是 `dominanceUnavailableSet` 的可用补集 `dominanceKey`。这样 graph 的 superset 判断等价于 unavailable-set 语义下的 `D_A ⊆ D_B`：支配 label 禁掉的 job 不比被支配 label 多，才允许支配。

single-point store 同步改成 `bestByDominanceKey`，不再按旧的一跳 reachable-set 命名。单点 label 的 graph 查询仍走 `dominatesSinglePoint(label.reachableSet, ...)`，但这里的 `label.reachableSet` 已经是 dominance key，不再是扩展候选集合。扩展循环全部改为枚举 `label.extensionSet`，避免把 dominance key 误当作当前可扩展 job 集合。

`changed==0` 分支也按当前判断改为显式异常，而不是返回空列或 fallback。理由是：只要存在 non-elementary negative route，DSSR 更新应当能把重复 job 加入中间 job 的 ng-neighborhood；如果完全没有变化，说明 ng-memory、join 或 update 逻辑存在不一致，应立即暴露。当前仍保留每轮只记录最负 non-elementary route 的策略，因为这是 DSSR 收紧强度与开销之间的选择，不是 correctness 的第一优先问题。

验证结果：focused `javac -encoding UTF-8 -cp "lib/*;src" src/TWETBPC/GC/GCNGBBStyleBidirectionalNgDssr.java` 通过。带 CPLEX 跑 `wet020_001_2m,maxNodes=1,ngDssr=true,completionBound=allCycles,midpointProbe=true` 得到 `ROOT_PROCESSED,obj=bound=6343,exact=0.545s,calls=2,valid=true`。带 CPLEX 跑 `tmp-wet030_from040_013_2m,maxNodes=1,ngDssr=true,completionBound=allCycles,midpointProbe=true,midpointStrategy=completionBound` 得到 `NODE_LIMIT,incumbent=14573,bound=14287.625,exact=15.602s,calls=10,valid=true`。两个浅层测试均未触发 `changed==0` 异常。

同口径补测显示，这次修正主要是 correctness 修正，不是速度优化。当前代码下 `tmp-wet030_from040_013_2m,maxNodes=1,completionBound=allCycles,midpointProbe=true,midpointStrategy=completionBound` 中，elementary `GCNGBBStyleBidirectionalPricing` 为 `solve=29.631s,exact=9.930s,calls=3,incumbent=14474,bound=14287.625,valid=true`；ng-DSSR 为 `solve=38.759s,exact=15.892s,calls=10,incumbent=14573,bound=14287.625,valid=true`。因此 ng-DSSR 仍慢于 elementary，且上界路径也更差。原因仍是 DSSR 多轮和首轮可入 RMP 的 elementary 负列较少；新的 unavailable-set dominance key 修掉了错误支配风险，但也不会自动减少 DSSR 轮数。

16. 2026-06-09 ng dominance key 再复核

进一步复查后，需要修正上一节“修掉错误支配风险”的表述。当前实现虽然把扩展候选 `extensionSet` 和保存的 `dominanceUnavailableSet` 拆开了，但传给 `PaperDominanceGraph` 的 `dominanceKey` 实际上仍然等于当前可用集合，也就是 `extensionSet`。`dominanceUnavailableSet` 只是保存在 label 上，当前 graph 和 single-point store 并不直接读取它。因此严格说，当前实现是 extension/available-set dominance，不是显式 unavailable-set dominance，更不是“同 terminal + 同 ngMemorySet”保守 dominance。

在固定 universe 且只看当前状态时，available superset 和 unavailable subset 是互补等价的；但 ng-relaxation 的问题在于 memory 会随扩展更新：`M'=(M∩N_j)∪{j}`。两个 label 当前 available set 相同或一个包含另一个，并不能保证扩展到同一个 next job 后 memory 仍保持同样的支配关系。尤其当某个 job 在一个 label 中属于 `ngMemorySet`，而在另一个 label 中只是因为当前 direct time/resource 不可达而不可用时，这两类原因在当前 available set 里都表现为“不可用”，但下一步扩展后前者可能继续被记住，后者可能重新变可用。这样只按 extension set 支配仍可能删错 label。

因此，当前 ng-DSSR 的 dominance 语义仍存在 correctness 风险。最稳的下一版应先改为同 terminal、同方向、同 `ngMemorySet` bucket 内才做 dominance；如果要更强，也至少需要单独要求支配 label 的 `ngMemorySet` 不比被支配 label 更大，同时再比较资源/时间 unavailable 信息，而不是只比较二者并集或其补集。single-point store 也要同步使用同样的 memory-aware key。

backward ng-memory 更新公式本身暂未发现直接反向错误。当前 backward label 表示一个从当前 first job 到 sink 的 suffix，向左加入 `prevJob` 后用 `M'=(M∩N_prev)∪{prev}`，可以解释为“新 suffix 以 prev 为当前端点时应保留的 ng-memory”。join 阶段的 `backward.ngMemorySet.contains(lastJob)` 和 `forward.ngMemorySet∩backward.ngMemorySet=∅` 也能解释为当前 ng-memory 兼容性检查；真实重复仍由恢复 route 后的 non-elementary 检查交给 DSSR 更新。不过这只是语义解释，还没有和旧 VRP 双向 ng 实现做逐步对拍，因此 backward 仍应列为待验证项。

当前结论是：ng-DSSR 的 DSSR 更新、join 和 smoke valid 说明流程能跑，但 dominance 仍不能当作严格 exact pricing 结论。下一步如果继续修，应优先切到同 `ngMemorySet` 的保守 dominance，验证正确性后再讨论更强 unavailable-set dominance。

17. 2026-06-09 ng-memory 与旧 VRP 口径复核

本次继续复核后，当前真正的问题可以更明确地表述为：当前代码不是“路径扩展错了”，也不是 `ngMemorySet` 没有更新，而是 dominance graph 仍然只看传入 `Label.reachableSet` 的 key。`FunctionLabel` 构造时把 `dominanceKey` 传给父类 `Label`，而当前 `dominanceKey` 实际等于 `extensionSet`。`PaperDominanceGraph.insertOrDominate()`、`dominatesSinglePoint()` 和 single-point store 都围绕这个 key 做 superset 查询。因此，保存下来的 `dominanceUnavailableSet` 目前不参与支配判断。

这会带来 correctness 风险，因为 ng-relaxation 的记忆集合不是普通 elementary `visitedSet` 那种单调只增状态。扩展到 job `j` 后，记忆按 `M'=(M∩N_j)∪{j}` 更新。两个 label 当前可扩展集合相同，或者一个可扩展集合包含另一个，并不保证它们扩展到同一个下一个 job 后，记忆集合仍保持支配关系。当前 available/extension set 只能描述“此刻能不能一步扩展”，不能完整描述“未来记忆如何演化”。所以错误点不是“unavailable 的补集方向写反”这种简单问题，而是把 ng-memory 和资源/时间不可达原因合并成一个当前 available key 后，丢掉了 memory 转移所需的信息。

从当前代码看，forward 与 backward 的 ng-memory 更新公式是同一套：`updateNgMemory(parentMemory,currentJob)` 先取 `parentMemory∩N_currentJob`，再加入 `currentJob`。forward 从左向右扩展时这就是标准 ng-route 记忆更新；backward 若把 label 解释为“当前 first job 到 sink 的 suffix”，向左加入 `prevJob` 后用 `prevJob` 作为新的当前端点，也可以得到同样的公式。因此 backward 更新本身暂未看到明显方向性错误。

join 阶段当前先检查 crossing arc 的直接禁弧，再用 `backward.ngMemorySet.contains(lastJob)` 排除后缀仍记住前缀末端 job 的拼接，用 `forward.ngMemorySet∩backward.ngMemorySet` 排除两边当前 ng-memory 冲突；若恢复出的真实 route 仍重复，则不入列，只记录 non-elementary route 交给 DSSR 更新。这一流程和 ng-DSSR 的基本思路一致。但当前仓库没有旧 VRP 源码，只能从移植语义上判断，不能确认“和旧 VRP 逐行一致”。如果要严格确认，需要拿旧 VRP 的 GCNGBB/DSSR 源码对拍 `memory update`、`join ng-compatible` 和 `UpdateNGSet` 三个位置。

当前建议不再把现有 ng-DSSR 称作严格 exact pricing。下一步若修改，应先做保守版本：同 terminal、同方向、同 `ngMemorySet` 的 label 才进入同一个 dominance bucket；bucket 内可以继续沿用现有函数支配和 extension/resource key。single-point store 也必须把 `ngMemorySet` 纳入 key。这样会削弱 dominance、可能变慢，但能先把“不会因为 ng-memory 遗忘删错 label”这个正确性边界立住。
18. 2026-06-09 对照旧 VRP 反向 ng-memory 口径

本次按用户指定路径 `d:\重要文件\桌面备份\曹长新\同济大学\学习和生活\博士\研究生学习\研究方向\src\src` 重新搜索旧 VRP 源码，重点核对 `BPC/GC/GCNGBB.java` 和 `BPC/GC/GCNGBB_C.java`。旧代码中 forward 和 backward 的 ng-memory 更新公式一致：扩展到新 customer `i` 后，均执行 `newMemory=(oldMemory & N_i) | {i}`。其中 backward 扩展是从 suffix 当前首点向左加入前驱 `i`，旧代码在 `BWExtend` 中同样用 `i` 的 ng-set 更新 memory。因此当前 `updateNgMemory(parentMemory,currentJob)` 在 backward 中传入 `prevJob` 的方向，本身与旧 VRP 口径一致，暂未看到“反向 u/memory 更新方向写反”的证据。

旧 VRP 的 join 口径也更明确：join 前先把共同 terminal customer 从 backward label 的 visit/memory 中删掉，然后只检查 forward/backward 的 ng-memory 是否冲突；真实 visit 重复并不在 join 前直接剪掉，而是在 negative reduced-cost route 恢复后，用 visit/duplicate mask 判断是否 non-elementary。若 non-elementary，则不入 pool，只记录 best cycle 交给 `UpdateNGSet()`。当前代码中 `backward.ngMemorySet.contains(lastJob)`、`forward.ngMemorySet` 与 `backward.ngMemorySet` 交集检查，以及恢复 route 后记录 non-elementary negative sequence 的大方向，与旧 VRP join 口径一致。

需要修正的判断是 dominance。旧 `GCNGBB.java` 的 dominance 条件不是单纯 current extension set，而是显式出现了 `memory | ~reach` 口径，例如支配方 memory 必须被被支配方的 `memory` 或“不可达补集”覆盖。`GCNGBB_C.java` 中也至少要求 memory 包含关系。当前 TWET ng-DSSR 虽然保存了 `dominanceUnavailableSet`，但传给 `PaperDominanceGraph` 的 key 仍是可扩展集合 `extensionSet/dominanceKey`，graph 与 single-point store 实际仍按可用集合 superset 做判断。这和旧 VRP 的显式 `memory | ~reach` 条件不完全等价，尤其在 ng-memory 会遗忘的情况下，当前 dominance 仍有误删风险。因此当前准确结论是：反向 ng-memory 更新与旧 VRP 一致；join 的 ng-compatible 口径基本一致；但 dominance 还没有严格复刻旧 VRP 的 memory-aware 条件，下一步若继续修应优先按旧代码把 memory 条件纳入 graph/single-point 支配判断，而不是先改反向更新公式。

19. 2026-06-09 修正 ng-DSSR dominance key

本次按上述结论修正 `GCNGBBStyleBidirectionalNgDssr` 的 dominance key。实际扩展仍使用 `extensionSet`，不改变 forward/backward 枚举候选 job 的逻辑；传给 `PaperDominanceGraph` 和 single-point store 的 `Label.reachableSet` 改为组合 key：前半段编码当前一步可扩展 job，后半段编码 `not-ng-memory`。由于 `PaperDominanceGraph` 的支配方向是 key 越大越强，新的 superset 判断同时表示“支配方当前可扩展集合不小于被支配方”以及“支配方 ng-memory 不大于被支配方”。这比只看 `extensionSet` 更接近旧 VRP 的 memory-aware 条件，避免一个 label 因当前可扩展集合看起来更强、但未来 memory 遗忘语义不同而错误支配另一个 label。

为避免组合 key 污染队列排序和诊断统计，新增 `extensionCardinality` 单独记录真实扩展集合大小。`REACHABLE_SIZE` 队列策略和 `forwardReach kept avg/min/max` 统计继续使用真实 `extensionSet` 大小，而不是组合 dominance key 的 cardinality。single-point store 继续使用同一套组合 key，因此普通 graph 和 single-point 的支配语义保持一致。

验证方面，focused `javac -encoding UTF-8 -cp "lib/*;src" src/TWETBPC/GC/GCNGBBStyleBidirectionalNgDssr.java` 已通过。当前 shell 环境没有找到 `ilog.concert.IloException` 对应的 CPLEX jar，`GCBBFullDomainComparisonTest` 的 CPLEX smoke 未能运行；这不是本次代码编译错误，但后续需要在带 CPLEX classpath 的环境下补跑 20 任务 root 和 30 任务浅层 ng-DSSR 对照。当前修改属于 correctness 优先，预期可能削弱一部分 dominance、让 ng-DSSR 更保守，速度是否变快需要后续实测。

20. 2026-06-09 复核当前 dominance key 是否同时使用 memory 与资源不可达

再次检查当前代码后，需要把语义说得更精确。当前 graph/single-point 实际使用的是组合 key：前半段为 `extensionSet`，后半段为 `not-ng-memory`。其中 `extensionSet` 是同时排除了 zero-dual job、ng-memory job、半域不可行 job 和直接时间不可行 job 后的一步可扩展集合，因此资源/时间不可达信息已经通过 `extensionSet` 进入 dominance 判断；`not-ng-memory` 则显式把 ng-memory 纳入判断。因此当前支配判断确实同时受到“资源/时间可达性”和“ng-memory 集合”的约束。

但它不是直接使用 `D = ngMemory ∪ resourceUnavailable` 这个并集，也没有完全复刻旧 VRP `memory | ~reach` 的较强条件。当前条件等价于要求支配方的实际可扩展集合不小于被支配方，且支配方的 ng-memory 不大于被支配方。这是安全但偏保守的口径。旧 VRP 允许“支配方记住了某个 job，但被支配方因资源/时间已经不可达该 job”时仍然支配；当前组合 key 不利用这层放松，因为 `not-ng-memory` 是对所有 job 编码，而不是只对被支配方资源可达的 job 编码。后续若要更接近旧 VRP，可以进一步拆出 resourceReachSet：key 的第一段放 resourceReachSet，第二段只对 resourceReachSet 内的 not-memory 编码，从而表达 `memory_A ∩ reach_B ⊆ memory_B`；本次先保持当前保守实现。

21. 2026-06-09 按不可达并集补集修正 ng dominance key

根据后续复核，上一版“extensionSet + not-ng-memory”的组合 key 过于保守，不是当前希望使用的语义。当前应把 ng-memory 导致的下一跳不可达 job 与资源/半域/时间导致的不可达 job 先合并为 `D = ngUnavailable ∪ resourceUnavailable`，再把 `J \ D` 作为传给 `PaperDominanceGraph` 的 key。由于 graph 的判断方向是 key 越大越强，`key_A ⊇ key_B` 正好等价于 `D_A ⊆ D_B`，即支配 label 的不可达集合不大于被支配 label。

代码上，`buildForwardNgDominanceSets()` 和 `buildBackwardNgDominanceSets()` 本来已经在同一个 `unavailable` 判断里合并了 zero-dual、ng-memory、半域不可行和直接时间不可行四类原因，因此对应的 `extensionSet` 已经是这个不可达并集的补集。本次删除额外的 `buildNgDominanceKey(extensionSet, ngMemory)` 组合编码，直接把 `extensionSet` 作为 `dominanceKey` 传给 graph/single-point。这样同一个 job 如果在 A 中因为 memory 不可达、在 B 中因为资源/时间不可达，只要二者都不可达，就不会因为 A 的 memory 更大而额外阻止支配。

验证方面，focused `javac -encoding UTF-8 -cp "lib/*;src" src/TWETBPC/GC/GCNGBBStyleBidirectionalNgDssr.java` 已通过；对代码文件 `rg` 检查确认 `buildNgDominanceKey`、`not-memory`、组合 key 等残留已删除。该修改是 dominance 语义修正，不改变实际扩展时仍用 `extensionSet` 枚举下一跳的逻辑。

22. 2026-06-09 再分析 extensionSet dominance 在 ng-relaxation 下的安全性

进一步分析后，需要把当前第 21 节的结论收窄。`D = ngUnavailable ∪ resourceUnavailable` 的补集 `extensionSet` 能表达“当前一步可扩展能力”，但它仍然不一定能保证 ng-memory 的未来演化安全。原因是 ng-memory 的转移为 `M'=(M∩N_j)∪{j}`。两个 label 当前的 `extensionSet` 相同或一方包含另一方，并不保证它们扩展到同一个 next job 后，memory 仍保持支配关系；某个 job 当前可能在一方因 memory 不可达、在另一方因资源/时间不可达，这两种原因在当前 `extensionSet` 里都表现为不可用，但下一步之后资源不可达原因可能消失，而 memory 原因可能继续保留。

因此，当前代码 `dominanceKey=extensionSet` 只能说使用了不可达并集的可用补集，不能称为严格安全的 ng-dominance。若要保证 ng-memory 转移安全，至少需要额外要求支配 label 的 memory 不大于被支配 label，即 `M_A⊆M_B`；这样对任意后续节点 `j` 都有 `(M_A∩N_j)∪{j} ⊆ (M_B∩N_j)∪{j}`。在现有 `PaperDominanceGraph` 的 key 越大越强语义下，这个条件可以通过第二段 `not-ng-memory` 补集编码表达，也就是 `extensionSet + not-ng-memory`；或者更保守地按相同 `ngMemorySet` 分桶后再做 dominance。single-point store 也必须同步使用同一套 memory-aware key，否则 Tmid 单点 label 仍可能被误删。

当前最新判断是：第 21 节修改符合“不可达并集补集”的口径，但它不是严格安全的 ng-DSSR dominance；若下一步要以 correctness 为先，应恢复或重做 memory-aware 条件，而不是只保留 `extensionSet`。

23. 2026-06-09 对照旧 VRP 的 ng dominance 条件

继续对照旧 VRP 后，可以更明确地描述当前 TWET 与旧 `GCNGBB` 的差距。旧 `GCNGBB.java` 在 `FWIsDominate/BWIsDominate` 里不是只比较当前一步可扩展集合，而是把 reach 和 memory 分开处理。已有 label `lb` 支配新 label `label` 时，除 reduced cost、weight、time 不差以外，还要求 `lb.memory ⊆ label.memory ∪ ~label.reach`。这句话的含义是：支配方记住的每个 customer，要么被被支配方也记住，要么被被支配方在资源/时间意义上已经不可达；如果被支配方还可能到达某个 customer，而支配方却因为 memory 记住了它，那么不能支配。

旧 `GCNGBB_C.java` 使用的是更简单的条件 `lb.memory ⊆ label.memory`，不利用 `~reach` 放松；旧 `GCNGBB.java` 则更强一些，允许“支配方多记住的点在被支配方那里已经资源不可达”。二者共同点是 memory 条件始终显式参与支配判断，而不是只通过当前 extension/reach key 间接体现。

当前 TWET ng-DSSR 的 `extensionSet` 等价于 `resourceReach ∩ ~memory`。把它直接作为 graph key，只能表达 `resourceReach_A∩~M_A ⊇ resourceReach_B∩~M_B`。这个条件无法推出旧 VRP 的 `M_A ⊆ M_B ∪ ~resourceReach_B`，因为 `extensionSet` 混合了“因为 memory 不可达”和“因为资源不可达”两种原因。也就是说，当前 graph 可能看到两个 label 当前可扩展集合有包含关系，但不知道支配方是不是额外记住了一个被支配方后续仍可能到达的 job。严格对齐旧 VRP 时，需要把 resourceReach 与 memory 拆开编码；仅用 `extensionSet` 不够。

如果沿用现有 `PaperDominanceGraph` 的“key 越大越强”结构，一个接近旧 `GCNGBB.java` 的 key 可以拆成两部分：第一部分放 `resourceReach`，第二部分放 `resourceReach ∩ ~memory`，从而在 superset 判断中同时表达资源可达不弱，以及在被支配方可达范围内支配方 memory 不更多。若只想复刻旧 `GCNGBB_C.java`，第二部分可以直接用全局 `~memory`，表达 `M_A⊆M_B`。后续实现前应先决定是复刻 `GCNGBB` 的 `memory | ~reach` 放松，还是使用更保守的 `GCNGBB_C` 口径。

24. 2026-06-10 关于“并集不可达比较是否足够”的再解释

用户进一步提出，如果 dominance 的本质是“在 L1 的 ng-memory 和不可达关系下，L1 后续可以走的路径，L2 要么不可走，要么成本更高”，那么比较 ng-memory 不可达与资源不可达的并集似乎应该足够。这个目标本身是正确的，但当前并集 `D=M∪R` 只描述当前一步不可达。若 `D_1⊆D_2`，只能保证 L2 当前能选的第一跳，L1 当前也能选；它不自动保证两者走到同一个第一跳后，新 memory 与新资源可达集合仍满足包含关系。

旧 VRP 之所以不只比较 `M∪R`，是因为 ng-memory 的转移会遗忘：`M'=(M∩N_j)∪{j}`。如果 L1 多记住某个 customer `a`，但 L2 当前只是因为资源不可达 `a`，那么在当前 `D` 里二者都把 `a` 当成不可达；但扩展到下一个点后，L2 的资源不可达原因可能变化，而 L1 的 memory 可能继续保留。因此“当前并集不可达包含”不能直接推出“所有后续路径包含”。旧 `GCNGBB` 的 `M_1⊆M_2∪~Reach_2` 条件正是在处理这个差异：只有当 L1 多记住的点在 L2 那边已经资源不可达时，才允许把它视为不影响支配。

因此当前判断为：如果能证明 TWET 的 `resourceUnavailable` 对后续扩展是单调吸收的，即当前资源不可达的 job 未来永远不可达，那么并集比较可以成立；但当前 direct time/半域过滤不显然满足这个性质，所以仍需要像旧 VRP 一样把 memory 与 resource reach 拆开比较，或者通过对拍证明现有并集 key 不会误删。

25. 2026-06-10 对当前 extensionSet key 的条件式判断

继续按 ng-set 扩展语义复核后，需要修正上一节的表述强度。用户指出的关键是：如果 label A 支配 B 时，A 的不可达集合是 B 的子集，那么不可能出现“当前 A 不能访问 c、但当前 B 能访问 c”的情况；若资源不可达本身又是沿路径单调的，即当前资源不可达的 c 在继续扩展若干 job 后仍不可达，那么当前 `D=M∪R` 的并集比较就可以支持后续路径包含。

因此，`dominanceKey=extensionSet` 并不是必然错误。它是否安全取决于 `resourceUnavailable` 的语义。如果 `R` 是硬资源/时间不可达，并且满足类似 VRPTW 中“时间只会向前增加、setup 满足三角不等式、直接到不了则绕路更到不了”的单调性，那么 `D_A⊆D_B` 可以解释为 B 当前及未来能走的点 A 都能走，再配合 frontier 支配和 domain cover，当前 graph 口径是有可能成立的。旧 VRP 中 `~reach` 正是这种单调不可达集合。

当前仍需确认的是 TWET 里的 `resourceUnavailable` 是否只包含这种单调硬不可达。代码中 `extensionSet` 排除的不只是 ng-memory，还包括 zero-dual 过滤、half-domain 过滤，以及 `getDynamicForward/BackwardHStart/HEnd` 和 direct extension time feasibility。这些窗口包含 pricing/dynamic/profitable 语义，且依赖 predecessor/successor arc；如果某个 job 当前因这些动态窗口不可达，但换一个前驱后又可能可达，那么它就不是旧 VRP 意义上的单调 `~reach`，并集 key 就仍有风险。因此下一步不应直接改代码，而应先核对这些过滤是否都是安全且单调的 resource reach；若是，当前 `extensionSet` key 可以保留；若不是，需要把硬 resource reach 和动态剪枝分开，dominance key 只使用单调部分。

26. 2026-06-10 当前不可达集合与 forbidden arc 的语义复核

继续检查当前 `GCNGBBStyleBidirectionalNgDssr` 后，确认 forbidden arc 没有进入 `buildForwardNgDominanceSets()` / `buildBackwardNgDominanceSets()` 的 `unavailable` 判断。当前 dominance key 里的不可达来源只有 zero-dual 全局排除、ng-memory、half-domain eligibility，以及 direct time/window feasibility。forbidden arc 只在 `canExtendForward()`、`canExtendBackward()`、join crossing arc 和 sequence compatibility 中即时检查。这一点是合理的：某条 forbidden arc 只表示当前 terminal 到某个 job 的直连弧不能用，不表示这个 job 在后续通过其他前驱永远不能访问；如果把 forbidden arc 放进 dominance 不可达集合，会把“当前直连不可用”误当成“该 job 对后续路径不可达”，反而可能导致错误支配。

当前 dominance key 是否安全，重点仍在 direct time/window feasibility 是否具备单调不可达性质。forward 侧如果 setup time 满足三角不等式且处理时间非负，那么从当前 terminal 直接到某 job 已经太晚时，中间再插入其他 job 只会更晚，因此 direct time 不可达可以视为单调不可达。backward 侧同理，若直接把某 job 放在当前 suffix 前都放不下，中间再插入任务只会占用更多时间，也不会让它重新可达。half-domain eligibility 和 zero-dual 排除是同一侧所有 label 的全局过滤，不是 label 间相对支配差异的主要来源。由此看，当前“不把 forbidden arc 放进 dominance，不可达集合只放 ng-memory 与时间/半域/全局过滤”的方向是合理的；需要后续确认的是实例和预处理是否确实满足上述三角不等式/单调时间前提。

27. 2026-06-10 清理 ng-DSSR 冗余字段与 BEST_RECORD 语义

本次检查 `JoinBestThresholdMode.BEST_RECORD` 后确认它不是死代码。`ZERO` 口径下 join 只按负 reduced cost 保留候选；`BEST_UB` 会在已有负列记录时用当前 best reduced cost 加强 join lower-bound 剪枝，但最终仍可保留普通负列；`BEST_RECORD` 更激进，既用当前 best reduced cost 做 lower-bound 阈值，也要求实际拼接出的列必须刷新当前 best 才进入候选池。因此它本质上是 record-only 的 join 候选压缩模式，会减少每轮返回列数并改变列生成路径，适合做诊断/对照，不应按“未使用代码”删除。

代码清理方面，当前 graph/single-point 实际使用的 key 已经是 `extensionSet`，原来的 `dominanceUnavailableSet` 只在构造阶段保存，后续没有参与 `Label`、`PaperDominanceGraph` 或 single-point store 的比较；`dominanceKey` 也始终等于 `extensionSet`。本次删除 `NgDominanceSets` 包装类、`FunctionLabel.dominanceUnavailableSet` 字段和对应构造传参，改为直接构造并传递 `PackedBitSet extensionSet`。这不改变支配语义，只是把“实际用的是 extensionSet”写清楚，避免注释和字段名继续暗示 graph 同时使用另一个不可达集合。

同时更新了类头和扩展过滤注释。当前 ng-DSSR 的 label 确实维护 `ngMemorySet`，重复任务不是靠 `visitedSet` 阻止扩展，而是在恢复真实 route 后判断 elementary/non-elementary 并用 DSSR 更新 ng-set；`canExtendForward/Backward` 中即时检查的是当前直连 forbidden/pricingOnly arc。验证方面，`rg` 确认 `dominanceUnavailableSet`、`dominanceKey`、`NgDominanceSets` 等旧名字已无残留，focused `javac -encoding UTF-8 -cp "lib/*;src" src/TWETBPC/GC/GCNGBBStyleBidirectionalNgDssr.java` 通过。该修改只清理冗余和注释，不新增 dominance 条件。

28. 2026-06-10 joinBest 模式与 ng-DSSR 轮数关系

继续复核 `JoinBestThresholdMode` 后，需要修正对默认配置的直觉判断。当前 `TWETBPCConfig.bidirectionalJoinBestThresholdMode` 默认值是 `zero`，不是 `bestUB`。`GCBBFullDomainComparisonTest` 只从 `twet.bpc.fullDomainCompare.joinBestMode` 读取覆盖值；没有显式传参时仍使用默认 `zero`。近期 013 ratio 和 pricingOnly 等日志中的 exact pricing 明细也打印为 `joinBest mode=ZERO/.../recordPruned=0`，说明这些测试不是在 `BEST_RECORD` 下跑的。

因此，当前 ng-DSSR 迭代次数偏多不能归因于 `BEST_RECORD` 把非最优但仍为负的列砍掉。`BEST_RECORD` 的确会只保留刷新 bestRC 的负列，可能导致每轮加列少、RMP 迭代变多；但近期 ng-DSSR 对照没有使用它。当前更合理的解释仍是：ng-relaxation 产生较多 non-elementary negative route，这些 route 只用于更新 ng-set、不进主问题；同时每轮真正 elementary 的负列数量不足，导致 DSSR 需要多轮收紧，exact calls 增多。

从正式策略角度看，`BEST_RECORD` 不适合作为默认。若要在 join 阶段利用当前 bestRC，又不想丢掉普通负列，更合理的是 `BEST_UB`：它只用 bestRC 做 lower-bound 剪枝，未被剪掉的真实负列仍可进入候选池。后续若想比较 join 剪枝强度，应优先做 `ZERO` 与 `BEST_UB` 的同口径对照；`BEST_RECORD` 只保留为压缩候选数量的诊断模式。

29. 2026-06-10 PaperDominanceGraph 是否使用 visitedSet

本次专门检查 `PaperDominanceGraphs`、`PaperDominanceGraph`、`IndexedPaperDominanceGraph` 和 `PartialListDominanceStore`，结论是当前正在使用的 paper dominance backend 没有把 `visitedSet` 放进支配判断。`PaperDominanceGraphs.create()` 当前返回经典 `PaperDominanceGraph`；`insertOrDominate(label)` 只用 `label.reachableSet` 找同 key 节点或 terminal superset 节点，再把这些节点的 `dominanceEnvelope` 与 `label.frontier` 比较。新节点也只保存 `reachableKey = label.reachableSet.copy()`、label list 和 envelope，不保存 visited 集合。single-point 查询 `dominatesSinglePoint()` 同样只传入 reachable key、时间点和值。

`PaperDominanceGraph` 的传播裁剪、subset/superset 查找和 envelope 合并也只围绕 `reachableKey` 与 `frontier` 展开。实验保留的 `IndexedPaperDominanceGraph` 结构相同，也只保存 reachable key、labels 和 envelope；当前运行入口还没有使用 indexed backend。`PartialListDominanceStore` 也只检查 `existing.reachableSet.isSupersetOf(label.reachableSet)` 和函数裁剪，不看 `visitedSet`。

因此，就当前 ng-DSSR 路径而言，`visitedSet` 只用于恢复真实 route、join/sequence 检查以及最终判断 elementary/non-elementary，不会在 `PaperDominanceGraph.insertOrDominate()` 中造成额外的 elementary dominance 条件。剩余风险仍回到 `extensionSet/reachableSet` 本身是否是正确的 ng dominance key，而不是 graph 内部偷偷使用了真实 visited 集合。

30. 2026-06-10 当前一轮 DSSR 后的 ng-set 更新与入列流程

当前 `GCNGBBStyleBidirectionalNgDssr.solve()` 的外层循环是：初始化当前 ng-neighborhood 与可复用 completion bound，然后重复执行一轮 ng-relaxed bidirectional pricing。每轮开始时清空 `nonElementaryNegativeSequences` 和本轮 `generatedColumns`，再调用 `executeOneNgDssrPricingRound(lp)` 生成 label、做 join 和 forward-sink 收尾。

一轮内部，label 扩展使用当前 `ngNeighborhoodByJob` 更新 ng-memory，公式为 `M'=(M∩N_current)∪{current}`。扩展阶段允许真实重复访问，只要重复 job 不在当前 ng-memory 中。join 或 forward-sink 得到负 reduced-cost 序列后，`tryGenerateColumn()` 先恢复真实 sequence，再检查是否 elementary。若 sequence 无重复，则按 signature 去重、按 reduced cost 进入本地 top-K 候选堆；若 sequence 有重复，则不进主问题候选池，只用 `recordNonElementaryNegativeSequence()` 记录本轮 reduced cost 最负的 non-elementary negative route。

一轮结束后，若 `generatedColumns` 非空，说明本轮已经找到真实 elementary 负列，`solve()` 直接返回这些列给 pricing engine，后续由外层 BPC 把这些列加入 pool/RMP 并重解 LP。此时即使本轮也见过 non-elementary negative route，当前实现不会继续收紧 ng-set，而是优先把合法负列交给主问题。若 `generatedColumns` 为空但存在 non-elementary negative route，则调用 `updateNgNeighborhoodsFromNonElementaryRoutes()` 收紧 ng-set 并开始下一轮。

ng-set 更新规则是：对记录的 non-elementary route，逐个找重复 job 的两次出现位置；对两次出现之间的每个 middle job，把 repeated job 加入 `N_middleJob`。这样下一轮扩展经过 middle job 后，ng-memory 会继续记住 repeated job，从而阻止同一条重复结构再次作为 ng-feasible route 出现。当前每轮只记录 reduced cost 最负的一条 non-elementary negative route，因此一次更新可能增加多个 `N_j` 条目，但来源只有这条最负重复 route。如果发现存在 non-elementary negative route 但 update 没有改变任何 ng-set，代码会抛 `IllegalStateException`，因为按当前 DSSR 更新语义这不应发生。

最终加入主问题的列只来自 `generatedColumns`，也就是本轮确认 elementary、signature 未重复、reduced cost 为负并通过候选堆保留的列。non-elementary negative route 从不直接入 pool/RMP，只用于 DSSR 更新。`BEST_RECORD` 若开启会进一步要求 elementary 负列刷新 bestRC 才保留；但当前默认和近期 ng-DSSR 测试均为 `ZERO`，所以本流程通常保留普通负列。

31. 2026-06-10 elementary 判定与 DSSR 更新粒度

当前 `tryGenerateColumn()` 的 elementary 判定仍然是在恢复真实 sequence 后调用 `isElementarySequence(sequence)` 扫描整条序列完成。虽然 forward/backward label 已经保存了 `visitedSet`，但两个 `visitedSet` 取交集只能判断拼接两侧是否访问了同一个 job，不能判断某一侧 label 内部是否已经因为 ng-memory 遗忘而重复访问了某个 job。forward-sink 收尾也只有单侧 label，更不能靠两侧交集判断。因此在当前只保存 visited set、没有保存 duplicate mask 的实现下，扫描真实 sequence 是稳妥但略冗余的做法。

如果后续要优化这一步，应在 label 扩展时同步维护 `duplicateSet` 或 `hasDuplicate`：扩展到新 job 时，如果该 job 已在真实 `visitedSet` 中出现，就把它记为重复。这样 join 后可以用 `forward.hasDuplicate || backward.hasDuplicate || forward.visitedSet∩backward.visitedSet` 快速判断 non-elementary。即便如此，负的 non-elementary route 仍需要恢复真实 sequence，因为 DSSR 更新要知道重复 job 两次出现之间有哪些 middle jobs；elementary 负列也需要 sequence 构造 signature 和列对象。所以这个优化主要是去掉每次的 boolean 扫描和分配，不是完全取消 route 恢复。

ng-set 更新粒度方面，当前 TWET 和旧 VRP `GCNGBB` 的核心口径一致：每轮 DSSR 只保留 reduced cost 最负的一条 non-elementary negative route 作为本轮更新来源，而不是把本轮所有重复负 route 都拿来更新。但在这条 route 内，如果存在多个重复 job，或者同一个 job 多次重复，当前 `updateNgNeighborhoodsFromNonElementaryRoutes()` 会逐个处理相邻两次出现之间的区间，把 repeated job 加入所有 middle job 的 ng-neighborhood。旧 VRP 的 `m_best_cycle/UpdateNGSet()` 也是“一条 best cycle route”，但会遍历这条 route 中的重复 customer 并更新对应中间 customer 的 ng-set。因此更准确的说法是：一轮只选一条最负重复 route；这条 route 内可以一次更新多个重复环/重复段。

32. 2026-06-10 当前 ng-DSSR 状态复核结论

再次检查当前 `GCNGBBStyleBidirectionalNgDssr` 后，流程层面暂未发现新的硬错误。forward/backward 扩展均使用 `M'=(M∩N_current)∪{current}` 更新 ng-memory；真实 `visitedSet` 不阻止扩展，只用于恢复 route 和 elementary/non-elementary 判定；join 阶段先检查 crossing arc、backward memory 是否仍记住前缀末端，以及两侧 ng-memory 是否冲突，然后才做函数拼接与 reduced-cost 判断；负的 elementary route 进入候选池，负的 non-elementary route 不入主问题，只用于 DSSR 更新。

当前实现也没有把 forbidden/pricingOnly arc 混进 dominance key。`extensionSet` 中排除的是 zero-dual 全局过滤、ng-memory、半域 eligibility 和 direct time/window feasibility；直连 forbidden/pricingOnly arc 在 `canExtendForward/Backward`、join crossing arc 和必要的 sequence 检查中即时过滤。这符合之前确定的语义：禁弧只表示当前直连弧不可用，不表示该 job 对所有后续路径永久不可达。

因此当前结论是：ng-DSSR 的主流程、入列规则、DSSR 更新粒度和旧 VRP 的大方向已经基本对齐，可以作为当前实验版本继续测试。剩余风险主要有两类：第一，`extensionSet` 作为 dominance key 的严格安全性依赖 direct time/window 不可达的单调性，以及 zero-dual/half-domain 过滤不会破坏支配前提；第二，当前没有维护 duplicate mask，所以 elementary 判定仍需恢复 sequence 后扫描，速度上还有优化空间但不影响语义正确性。速度收益方面仍不能保证优于 elementary，尤其在 non-elementary negative route 多、DSSR 多轮但 elementary 负列少的节点上可能更慢。

33. 2026-06-10 DSSR 多 route 更新与初始 critical set 对照

本轮按两个方向做实验：第一，将每轮 DSSR 用于更新 ng-set 的 non-elementary negative route 从原来的 top1 扩展为可配置 topK，新增 `TWETBPCConfig.ngDssrNonElementaryRouteUpdateLimit`，测试入口为 `twet.bpc.fullDomainCompare.ngDssrRouteUpdateLimit`。默认仍为 1，以保持旧 VRP 的 best-cycle 口径。实现上每轮保留 reduced cost 最负的 K 条非 elementary route，按 sequence 去重；`updateNgNeighborhoodsFromNonElementaryRoutes()` 会遍历这些 route，并对每条 route 内的重复段更新 ng-neighborhood。第二，对初始 critical set 继续测试已有的 `nearestK` 和 `empty` 两种口径；`empty` 表示每个 `N_j` 只含自身。

focused 编译命令 `javac -encoding UTF-8 -cp "lib/*;src" src/TWETBPC/TWETBPCConfig.java src/TWETBPC/GC/GCNGBBStyleBidirectionalNgDssr.java src/HEU/GCBBFullDomainComparisonTest.java` 通过。运行时使用 CPLEX 22.11 jar 和 native path。第一批 root 对照覆盖 `tmp-wet020_001_2m`、`tmp-wet030_from040_010_2m`、`tmp-wet030_from040_011_2m`、`tmp-wet030_from040_013_2m`，口径为 `ngDssr=true, completionBound=allCycles, midpointProbe=true, maxNodes=1`。20 任务各组均 `ROOT_PROCESSED, valid=true`，exact 时间在 `0.28s-0.33s`，差异很小。30 任务 root 上，`nearestK/top10` 通常减少最大 DSSR 轮数；例如 013 中 `nearestK/top1` 为 `solve=19.002s, exact=8.225s, calls=9, maxRounds=12`，`nearestK/top10` 为 `solve=18.344s, exact=7.941s, calls=9, maxRounds=5`。010 中 `nearestK/top10` 也比 top1 略快，011 root 则 top1/top5/top10 差异不大。`empty` 初始集整体不稳，010/013 的 exact 时间通常高于 nearestK，只有个别路径会改变 incumbent。

第二批浅层对照使用 `maxNodes=2`，只跑代表配置 `nearestK/top1`、`nearestK/top10`、`empty/top10`。结果更清楚：010 中 `nearestK/top1` 为 `solve=27.164s, exact=9.300s, calls=13`，`nearestK/top10` 降到 `solve=25.989s, exact=7.675s, calls=11`，而 `empty/top10` 为 `solve=33.854s, exact=10.694s, calls=15`。011 中 `nearestK/top1` 为 `29.117s/10.628s/14 calls`，`nearestK/top10` 为 `25.840s/8.177s/12 calls`，`empty/top10` 为 `29.401s/9.043s/12 calls`。013 中 `nearestK/top1` 为 `32.481s/16.694s/16 calls`，`nearestK/top10` 为 `27.115s/12.708s/13 calls`，`empty/top10` 为 `34.054s/17.530s/17 calls`。这些结果说明 topK 更新确实能减少 DSSR 轮数和 exact calls，尤其在分支浅层更明显；但 `empty` 初始集会让 ng-relaxation 初期过松，带来更多 DSSR 收紧和更多 pricing 调用，不推荐作为默认效率策略。

当前建议是：若继续保留 ng-DSSR 实验分支，默认初始 critical set 仍用 `nearestK,size=8`；每轮更新 route 数可以从 1 调到 10 做下一轮对照，因为它在 010/011/013 的 maxNodes=2 上都减少 exact 时间和 calls。top5 在 root 上部分有效，但不如 top10 稳定；是否存在更优的 topK 需要后续再试 8/12/15。需要注意的是，本轮只证明 ng-DSSR 内部 topK 更新比 top1 更稳，不代表 ng-DSSR 已经整体优于当前 elementary 主 pricing。

34. 2026-06-10 best ng-DSSR 与 elementary 同口径对照

继续用当前最好的 ng-DSSR 配置 `nearestK,size=8,routeUpdateLimit=10` 和 elementary `GCNGBBStyleBidirectionalPricing` 做同口径对照。测试口径保持 `completionBound=allCycles, midpointProbe=true`，分别跑 root-only `maxNodes=1` 和浅层 `maxNodes=2`，算例为 `tmp-wet030_from040_010/011/013_2m`。

root-only 结果显示，ng-DSSR 不再是全面更慢。010 上 ng 明显更快：`solve/exact/calls=13.996s/2.997s/5`，elementary 为 `22.163s/6.757s/5`，incumbent 同为 `16759`。011 上 ng 也略快：`14.704s/3.352s/5`，elementary 为 `16.978s/3.661s/3`，incumbent 同为 `13935`。013 上总时间 ng 略快但质量较差：ng 为 `18.344s/7.941s/9, incumbent=14573`，elementary 为 `19.385s/6.995s/3, incumbent=14474`。这说明 ng 在 root 可能减少总时间，但 exact calls 往往更多，列生成路径也可能导致较差上界。

`maxNodes=2` 结果更能体现浅层分支效果。010 上 ng 优势明显：`25.989s/7.675s/11, incumbent=16237`，elementary 为 `38.031s/11.843s/8, incumbent=16266`。011 上 ng 总时间更快但 exact pricing 本身更慢：ng 为 `25.840s/8.177s/12`，elementary 为 `28.898s/7.169s/6`，incumbent 都是 `13935`。013 上 ng 总时间略快但 exact pricing 更慢且上界更差：ng 为 `27.115s/12.708s/13, incumbent=14573`，elementary 为 `28.417s/11.652s/6, incumbent=14474`。

当前判断是：top10 修正后，ng-DSSR 已经不是“明显慢于 elementary”的状态，在 010 上甚至明显更好；但它仍不是稳定优于 elementary 的默认方案。主要原因是 ng-DSSR 的 exact calls 明显更多，non-elementary route 收紧改变列生成路径，某些算例如 013 会得到较差 incumbent。短期更合理的定位是把 `nearestK8/top10` 作为实验候选或 hard-node 对照，而不是直接替换主线 elementary pricing。后续若要继续推进，需要比较更深 node limit 或完整收敛，以及观察 ng 是否能在真正 hard node 上减少单次 exact 爆炸。

35. 2026-06-10 013 pricingOnly + ng-DSSR 完整求解记录

按用户要求继续测试 013：启用当前认为较好的 ng-DSSR 配置 `nearestK,size=8,routeUpdateLimit=10`，打开 `completionBound=allCycles`、`midpointProbe`、ALNS seed 和 `completionBoundSubtreeArcEliminationPricingOnly=true`，关闭 RMIH 上界启发式，运行名为 `tmp-ngdssr-013-pricingonly-debug-20260610`。这次运行没有复现之前 pricingOnly 在 node3 直接卡死的现象，node3 正常完成并继续往下搜索。node3 约 `100.268s`，其中 exact `95.757s/6 calls/add30`，此时全局 `inc=14908,bound=14322.5,gap=3.9274%`；node3 的 subtree 本轮 `fixed=0`，说明该节点后续难度主要来自 pricing 证明和列生成路径，而不是本节点新增大量禁弧。

后续搜索中 node9、node12、node20、node21 都出现多轮 exact pricing。典型 heartbeat 显示每轮 forward/bwd kept 在几千级，joinPairs 大多为二十万到六十万级，生成列常为 `0` 或 `1`，说明后段主要是在证明没有足够负 reduced-cost 列，而不是单次 label 数彻底爆炸。最终 node21 找到整数解并闭合上下界：`obj=14433,bound=14433,gap=0.0000%`，总耗时 `804.814s`，exact pricing 累计 `706.620s/144 calls`，最终 `valid=true`。node22 随后被 pseudo-cost 剪掉，队列清空，状态为 `FINISHED`。

当前结论是：在这组配置下，ng-DSSR + pricingOnly 可以把 013 完整求到最优，且 hard point 没有停在之前关注的 node3；但 exact pricing 仍是绝对大头，尤其后段大量 calls 每次只生成少量列。这个结果说明 ng-DSSR/top10 对 hard-node 证明有一定实际价值，但还不能说明它稳定优于 elementary，因为列生成路径、incumbent 质量和 exact calls 都会明显改变。

从上下界演化看，这轮不是主要在“找第一个可行解”。初始 ALNS seed 已经给出 `inc=14908`，RMIH 上界启发式关闭，因此前半段主要是在根节点和浅层节点通过 pricing/branch 提高下界并补列。node11 找到整数解后 incumbent 从 `14908` 跳到 `14451`，gap 从约 `3.52%` 降到 `0.3958%`；node15 再到 `14450`，node21 到 `14433` 并闭合。因此中后段同时有可行解改进，但主要耗时仍是证明和收敛下界：总耗时 `804.814s` 中 exact pricing 为 `706.620s/144 calls`，启发式 pricing 为 `79.537s/342 calls`，LP 只有 `9.728s`。

本轮配置需要和之前实验区分清楚：formal subtree 没开，`completionBoundSubtreeArcEliminationPricingOnly=true` 打开，所以消元弧只进入 pricing/completion-bound 口径；RMIH 上界启发式关闭，整数上界来自 ALNS 初始解和后续节点 LP 自身变整数。日志中 root `fixed=205`，后续节点有 `pricingOnlyArc=205` 起步，最终 node22 前达到 `pricingOnlyArc=454`，说明 pricingOnly 弧确实沿树传播并参与后续定价。

arc fixing 数量也要区分“本节点新增”和“某条路径累计”。node summary 中有 subtree 记录的节点新增 fixed 分别为：node1 `205`、node2 `49`、node3 `0`、node4 `35`、node5 `40`、node6 `58`、node7 `18`、node8 `19`、node9 `0`、node10 `0`、node13 `231`、node14 `85`。这些新增量跨不同分支合计为 `740`，只能说明整棵树处理过程中触发过多少次新增 pricingOnly arc fixing；不能直接当作任一后续节点的累计禁弧数。对单个后续节点应看它继承的祖先链累计，终端输出里后段 node22 的 `pricingOnlyArc=454` 是这次运行观察到的路径累计量级。

36. 2026-06-10 root 节点 `Tmid≈T` 的 ng-DSSR 对照

按用户要求测试“根节点直接把 `Tmid` 设到右侧 `T`”对 ng-DSSR 的影响。先发现 `midpointRatio=1.0` 原来不会生效，因为 `GCNGBBStyleBidirectional` 和 `GCNGBBStyleBidirectionalNgDssr` 都要求 ratio 严格小于 1；本次只把上界判断从 `<1.0` 放宽为 `<=1.0`，仍保留 `clampCurrentMidpoint()`，因此实际 `Tmid` 是贴近 `pricingHorizon` 的 `T-eps`，避免半域完全退化。

对照口径为 013 root-only：`ngDssr=true, nearestK,size=8,routeUpdateLimit=10, completionBound=allCycles, midpointProbe=false, ALNS seed on, RMIH off, maxNodes=1`，不打开 pricingOnly subtree。默认 midpoint 结果为 `solve=16.327s, exact=6.526s/9 calls, heuristic=5.983s/32 calls, pool=5378`；`Tmid≈T` 结果为 `solve=15.984s, exact=4.640s/6 calls, heuristic=7.336s/40 calls, pool=5256`。上下界相同，都是 `inc=14908,bound=14287.625,gap=4.1614%`。

从日志看，`Tmid≈T` 后 backward 侧几乎只剩虚拟 sink root，`bw kept=1`、`halfWindowIneligible bw=29`，join pairs 降到几十甚至为零；负列主要来自 forward sink，因此 exact pricing 变快。但这也意味着它不再是平衡双向搜索，生成列更少，导致启发式 pricing 和 LP/pricing 轮数变多。当前结论是：root 上 `Tmid≈T` 能降低 ng exact 时间，但总时间只小幅改善，且列生成路径更偏，不能据此直接作为默认策略，需要继续看深层节点是否会因为列少或单向化而变慢。

37. 2026-06-10 root `Tmid≈T` 变快原因的解释

`Tmid≈T` 在 root 上变快并不矛盾。这个设置确实让双向几乎退化为单向：backward 侧基本只剩 sink root，第一轮日志里 `bw kept=1`、`halfWindowIneligible bw=29`。但 root 节点当前并不是 forward 标签已经爆炸、必须靠 backward 分担的场景；相反，默认 midpoint 下还要构造一批 backward label，并做大量 forward/backward join。对照第一轮 exact pricing，默认 midpoint 的 join pairs 约 `52927`，函数评估约 `47911`；`Tmid≈T` 后 join pairs 只有 `77`，函数评估 `73`，所以 exact pricing 直接变快。

代价也很明显：`Tmid≈T` 不是更好的双向切分，而是用“少做 backward 和 join”换速度。第一轮 exact 生成负列从默认的 `162` 降到 `4`，列生成路径更依赖 forward-to-sink 和后续启发式补列；总体 root 时间只从 `16.327s` 降到 `15.984s`，exact 省下的时间被更多 heuristic calls 和更多 pricing rounds 抵消了一部分。因此当前判断仍然是：root 上可作为诊断和对照，不能说明深层节点也会更稳，更不能直接替代 probe/balanced midpoint。

38. 2026-06-10 多算例 root `Tmid≈T` 对照

继续按用户要求扩大 root-only 对照，测试 `tmp-wet030_from040_010/011/012/013/014/015_2m`。口径保持一致：`ngDssr=true, nearestK,size=8,routeUpdateLimit=10, completionBound=allCycles, midpointProbe=false, ALNS seed on, RMIH off, maxNodes=1`，不打开 pricingOnly subtree。结果显示，默认双向 midpoint 在 6 个算例的总时间上全部不慢于 `Tmid≈T`，其中 010/011/012/014/015 明显更快，013 本轮也从默认 `16.752s` 对 `Tmid≈T` 的 `16.925s` 略快。汇总如下：

`010`: 默认 `solve=14.997s, exact=4.351s/8 calls, pricing=38`；`Tmid≈T` 为 `16.335s, exact=4.757s/7 calls, pricing=41`。
`011`: 默认 `11.976s, exact=3.118s/6, pricing=32`；`Tmid≈T` 为 `14.608s, exact=4.971s/10, pricing=44`。
`012`: 默认 `11.635s, exact=2.747s/4, pricing=30`；`Tmid≈T` 为 `13.902s, exact=4.161s/6, pricing=35`。
`013`: 默认 `16.752s, exact=6.425s/9, pricing=41`；`Tmid≈T` 为 `16.925s, exact=4.957s/6, pricing=46`。
`014`: 默认 `16.613s, exact=6.251s/9, pricing=45`；`Tmid≈T` 为 `18.362s, exact=7.976s/10, pricing=49`。
`015`: 默认 `10.206s, exact=2.056s/3, pricing=27`；`Tmid≈T` 为 `13.220s, exact=3.123s/7, pricing=42`。

第一轮 exact 的细节说明了为什么单次看起来会有错觉。`Tmid≈T` 下 backward 侧全部近似退化为 `bw kept=1`，join pairs 只有几十个；默认双向会做几千到五万级 join pairs，因此首轮 exact 有时 `Tmid≈T` 更快。例如 015 首轮 `Tmid≈T` 为 `0.797s`，默认为 `1.178s`。但默认双向首轮生成的负列显著更多：010 为 `30` 对 `9`，011 为 `29` 对 `3`，012 为 `41` 对 `14`，013 为 `162` 对 `4`，014 为 `71` 对 `11`，015 为 `267` 对 `45`。因此默认双向虽然每轮更“重”，但补列质量和数量更好，后续 pricing 轮数、heuristic calls 和 exact calls 通常更少，整体更快。

当前结论修正为：`Tmid≈T` 只是在个别单次 exact pricing 中通过跳过 backward/join 降低局部成本；从 root RMP 收敛过程看，默认双向 midpoint 更稳定、更快。之前 013 单点的“exact 变快”不能解读为单向化更好，只能说明该节点首轮 join 成本较高。

39. 2026-06-10 40 任务 root `Tmid≈T` 对照

继续测试真实 40 任务 2m 算例 `data/40-2/wet040_001_2m.dat`。同样使用 `ngDssr=true, nearestK,size=8,routeUpdateLimit=10, completionBound=allCycles, midpointProbe=false, ALNS seed on, RMIH off, maxNodes=1`，不打开 pricingOnly subtree。默认双向 midpoint 结果为 `status=NODE_LIMIT, inc=26319, bound=26155.75, gap=0.620274%, solve=154.802s, exact=64.274s/12 calls, heuristic=74.611s/48 calls, pricing=60, cols=10896`。`Tmid≈T` 结果为同样上下界，但 `solve=239.803s, exact=99.851s/22 calls, heuristic=98.573s/63 calls, pricing=85, cols=10860`。因此 40 任务 root 上单向化明显更慢。

首轮 exact 细节显示，`Tmid≈T` 仍然几乎消掉 backward 和 join：默认首轮 `fwKept=2040,bwKept=1420,joinPairs=69543,funcEval=67673,added=207,time=4325.944ms`；`Tmid≈T` 首轮 `fwKept=3795,bwKept=1,joinPairs=98,funcEval=96,forwardSinkNeg=133,added=171,time=4275.347ms`。两者首轮时间接近，说明 40 任务 root 下省掉 join 后，forward 单侧标签增加基本抵消了收益；同时 `Tmid≈T` 首轮负列更少，后续 exact calls 从 `12` 增到 `22`，heuristic calls 从 `48` 增到 `63`，最终总时间显著变差。

当前判断进一步加强：规模上来后，默认双向切分的价值更明显。`Tmid≈T` 可以减少 join，但会让 forward 侧承担几乎全部状态空间，并减少每轮有效补列；在 40 任务 root 上已经明显不划算。

40. 2026-06-10 恢复默认 Tmid 口径与 partial dominance 复核

在多算例 root 对照后，本次把 `GCNGBBStyleBidirectional` 和 `GCNGBBStyleBidirectionalNgDssr` 中的显式 `bidirectionalRootLocalHorizonMidpointRatio` 判断改回严格 `<1.0`。也就是说，`midpointRatio=1.0` 不再把 `Tmid` 强制推到右侧 `T`，默认仍走当前双向 half-domain/probe 口径。原因是前面的 30/40 任务 root 对照已经说明：`Tmid≈T` 只是在部分首轮 exact pricing 中省掉 backward/join，看起来局部更快；但它负列更少、pricing 轮数更多，整体不如默认双向稳定。后续若还要测试单向化，应单独新增诊断开关，而不是复用 midpoint ratio 的正常取值语义。

同时复核了 `GCNGBBStyleBidirectionalPartialDominance` 当前实现。它仍是默认关闭的独立实验 engine，只替换 half-domain elementary 分支的 dominance backend，不接入 ng/DSSR/SRI。`PartialListDominanceStore` 使用 flat list 保存 active labels：当已有 label 的 `reachableSet` 是新 label 的超集，并且已有 frontier 在某些时间区间上不劣于新 frontier 时，通过 `PiecewiseLinearFunction.updateDominatedIntervals()` 把新 frontier 的被支配区间置为 big-M；如果 frontier 被全部清空则丢弃 label，否则保留剩余未被支配区间。反向处理已有 label 时同理。single-point label 也只在普通 graph 中找 `reachableSet` 超集且该点函数值不劣的 label。

从语义上看，这个 partial-list 版本对当前“不带 ng 的 elementary half-domain”分支是合理的：`visitedSet` 不参与 dominance，支配条件仍然是 terminal job 相同、未来可扩展集合不更差、frontier 在对应时间区间不更差。`updateDominatedIntervals()` 会按方向 normalize，forward 保留右端 big-M 尾段以便 prefix-min 闭包，backward 使用对应方向 normalize。当前没有发现会直接漏掉负列的硬错误。需要注意的是，这个结论不适用于 ng-DSSR；ng-DSSR 的 memory/reach 语义是另一套问题，不能把 partial-list 直接搬过去。

主要风险仍是工程成熟度和性能，而不是默认求解正确性。partial-list 是 `O(labels^2 * function operations)` 的全扫描结构，可能在大节点比当前 paper dominance graph 更慢；它还会就地修改 label frontier，因此必须依赖 PWLF 的 big-M/normalize 语义保持稳定。验证方面，本次 focused `javac` 覆盖 `GCNGBBStyleBidirectional`、`GCNGBBStyleBidirectionalNgDssr`、`GCNGBBStyleBidirectionalPartialDominance` 和 `PartialListDominanceStore` 通过；`tmp-wet020_001_2m,maxNodes=1,partialDominance=true` smoke 返回 `ROOT_PROCESSED,obj=bound=6343,valid=true`，exact 为 `0.720s/1 call`。当前建议仍是：partial dominance 保持默认关闭，只作为诊断/对照分支；若未来要转正，必须补小算例与普通 paper graph 的 negative-column/最终 bound 对拍。

41. 2026-06-11 partial dominance 的 30 任务根节点对照

本次继续测试 `tmp-wet030_from040_010/011/012/013/014/015_2m` 六个 30 任务算例。为了尽量只比较 dominance backend，统一使用 `maxNodes=1`、ALNS seed、启发式 pricing、`completionBound=allCycles`、关闭 RMIH 和 subtree，并关闭 midpoint probe，使 normal 与 partial 从同一个固定 Tmid 口径开始。normal 使用 `PaperDominanceGraphs`，partial 使用 `PartialListDominanceStore`，其余配置一致。

| 算例 | normal solve / exact | partial solve / exact | exact 比值 | 根界 normal / partial | exact calls normal / partial |
| --- | --- | --- | --- | --- | --- |
| 010 | `20.599s / 4.798s` | `21.237s / 3.491s` | `0.728` | `16148.8 / 16148.8` | `3 / 3` |
| 011 | `14.212s / 3.163s` | `24.206s / 5.426s` | `1.715` | `13526.478261 / 13525.709677` | `3 / 5` |
| 012 | `23.310s / 4.927s` | `22.295s / 5.300s` | `1.076` | `13258.521739 / 13258.521739` | `3 / 4` |
| 013 | `29.509s / 10.984s` | `26.542s / 7.692s` | `0.700` | `14287.625 / 14287.625` | `5 / 4` |
| 014 | `41.432s / 21.321s` | `33.073s / 16.429s` | `0.771` | `10288 / 10288` | `4 / 5` |
| 015 | `29.708s / 7.674s` | `24.886s / 6.218s` | `0.810` | `13394 / 13394` | `4 / 4` |

六个算例累计 exact 时间从 normal 的 `52.867s` 降到 partial 的 `44.557s`，减少约 `15.7%`；累计总时间从 `158.770s` 降到 `152.239s`，只减少约 `4.1%`。收益并不稳定：partial 在 010、013、014、015 的 exact 阶段更快，012 基本持平，011 则慢 `71.5%`。因此不能只用平均值判断它优于 paper graph。

日志显示两种 backend 会实质改变保留的 frontier 区间和负列集合，不是单纯替换数据结构。以累计 exact 统计为例，010 的 normal/partial 新增负列为 `134/106`，013 为 `698/397`，014 为 `176/159`。partial 的 flat-list 比较量也很大：010 约 `225 万` 次，013 约 `304 万` 次，014 约 `1.19 亿` 次；014 仍然更快，说明一次 flat-list 包含判断通常比 paper graph 的 DFS、集合查询和 envelope 传播便宜，但这是算例相关的，标签规模继续增大时二次复杂度仍有爆炸风险。normal 在 014 中 superset/subset 共访问约 `1412 万` 个 graph node，也说明当前 paper graph 的图遍历本身并不便宜。

最重要的新结果是 011。normal 的 exact 过程依次加入 `49、3、0` 条列，最终根界为 `13526.478261`；partial 依次加入 `36、12、5、2、0` 条列，最终根界为 `13525.709677`。即使关闭 midpoint probe，这个差异仍然复现，因此不是 Tmid 选择偶然造成的。两边最终整数解都通过 validator，但根 LP 界不同意味着至少有一套 dominance/列生成路径没有对同一个完整定价问题给出一致的停止证明。partial 得到更低的最小化目标，可能说明经典 whole-label dominance 删除了仍有价值的局部函数区间；也可能是 partial 的就地裁剪、normalize 或 join 对裁剪 frontier 的使用存在额外语义差异。仅凭当前结果还不能判断哪一边正确。

因此需要修正第 40 节中“未发现直接漏列硬错误”的表述强度。当前可以确认的是：partial 在多数 root 算例上有速度潜力，但还没有通过 exactness 对拍，不能默认启用。下一步若继续推进，应固定 011 的同一组 dual，同时分别调用 normal 和 partial pricing，导出各自最负列及 reduced cost，再用 `TWETColumnEvaluator` 和对方 backend 交叉验证；只有两边都能在对方最终 dual 下证明不存在负列，才能把根界差异解释为数值或退化路径问题。关闭全部启发式 pricing 的纯 exact 隔离也尝试过，但 011 normal 根节点运行超过三分钟仍未完成，说明该口径不适合直接做批量性能实验。

42. 2026-06-11 011 根界差异的交叉验证与原因

继续固定 011 root 的同一实验口径，新增默认关闭的 `diagnosticCrossCheckPartialDominance` 对拍开关：当主 exact pricing 返回空列时，再用另一套 whole/partial dominance 在完全相同的 RMP 和 dual 上复查。normal-primary 的最终 dual 下，forced partial 也返回空列；partial-primary 的最终 dual 下，normal 也返回空列。这个结果说明 011 的差异不是简单的“某一套 dominance 在最终 dual 下能找到而另一套找不到”，而是更早的列生成轨迹已经分叉。

随后导出两边最终 exact pricing 调用的 job dual、machine dual 和列池，直接按根节点 reduced cost 公式 `cost - sum(pi_j) - mu` 手算。partial 路径最终列池中有 44 条 normal 没有的列，其中 10 条在 normal 最终 dual 下仍为严格负 reduced cost，最小为 `-31.652174`。代表列为 `15 21 17 4 2 29 19 22 20 13 14 23 7 6 16 9`，真实成本 `6338`。这说明 normal 路径的 `13526.478261` 不是当前完整列空间下的可靠停止界；如果把 partial 已生成的列加入 normal 的最终 RMP，normal 还应继续下降。

进一步追踪这条代表列在 partial 路径中的出现时机。它不是在 final dual 下“穿过”窗口剪枝生成的，而是在第 4 次 exact pricing 前已经进入列池。按每轮 dual 手算，该列在 partial 第 3 次 snapshot 下 reduced cost 约为 `-9.26087`，当时 job 14 的 dual window 上界约 `492.13043`，而该列真实最优完成时间中 job 14 为 `493`，只超出约 `0.86957`。由于 job 14 的 tardiness 权重为 `8`，把 job 14 压回窗口上界带来的额外代价约 `6.9565`，仍不能把该列变成非负列，因此该列能在那一轮被生成并加入池。后续 final dual 下同一列 reduced cost 约为 0，窗口上界约 `491.26613`，如果重新从零生成则会被当前窗口逻辑排除，但列池已经保留了它。

这个现象解释了为什么 partial 能生成而 normal 没生成：partial dominance 改变了中间轮次保留的 labels/frontier 区间和负列集合，使它在某个中间 dual 下碰到了这条列；normal 沿另一条列生成路径走到最终 dual 后，窗口已经更窄，无法再补出这条列。因此它不是 partial-list 本身“没被砍掉”的最终轮证明，而是列池历史保留效应导致的路径分化。

根因仍落在 dual profitable window 的理论前提。对代表列，只有 job 14 超出 normal final dual window；如果删除 job 14，序列 reduced cost 反而从 `-31.652174` 变成 `+262.304348`，不存在“删掉窗外 job 后得到更好负列”的单调性。直接检查 setup time 可见 `13->14->23` 的连接时间为 `s(13,14)+p14+s(14,23)=2+2+1=5`，而直连 `s(13,23)=31`，删除 job 14 会让后继连接增加 26。该 011 数据共有 259 组三元组违反类似 `s(i,k) <= s(i,j)+p_j+s(j,k)` 的删除单调性。因此当前这批旧算例不满足 root dual profitable window 作为 exact 剪枝所需的结构前提。

本次不修改正式算法逻辑。后续默认算例生成应直接保证 setup time/cost 满足相应三角不等式或删除单调性；在不满足该前提的历史算例上，带 dual profitable window 的 exact pricing 只能作为启发式加速口径使用，不能把最终 bound 当成严格列生成收敛证明。为了后续继续复查，保留 `diagnosticCrossCheckPartialDominance` 默认关闭开关；它只在主 pricing 返回空列后运行另一套 dominance 对拍，不影响正式求解。

43. 2026-06-11 partial dominance 二次复测

按用户要求重新选 2 个算例复测 normal 与 partial dominance。配置沿用第 41 节的 root-only 对照口径：`maxNodes=1`、ALNS seed、启发式 pricing、`completionBound=allCycles`、关闭 RMIH 和 midpoint probe。010 用于确认正常一致性，011 用于确认前面定位出的旧算例窗口前提问题是否仍然复现。

010 的 normal 结果为 `obj=16489,bound=16148.8,solve=26.716s,exact=5.373s,calls=3,valid=true`；partial 结果为 `obj=16489,bound=16148.8,solve=24.814s,exact=4.319s,calls=3,valid=true`。因此在 010 上两者根界一致，partial 的 exact 时间约快 `19.6%`，总时间约快 `7.1%`。

011 的 normal 结果为 `obj=13963,bound=13526.478261,solve=20.159s,exact=3.332s,calls=3,valid=true`；partial 结果为 `obj=13963,bound=13525.709677,solve=18.705s,exact=4.612s,calls=5,valid=true`。011 仍然复现根界不一致：partial 的总时间略快，但 exact 时间更慢且 calls 更多。该差异与第 42 节结论一致，主要来自旧 011 算例违反 setup time 删除单调性，使 root dual profitable window 不能作为严格 exact 剪枝前提；不能把它解释为 partial dominance 单独错误或已经严格优于 normal。

当前判断不变：在满足窗口前提的算例上，应继续用更多样本对拍 normal/partial 的 bound 一致性和速度；旧 011 这类不满足前提的算例只能用于说明窗口剪枝前提的重要性，不适合作为 partial dominance 正确性的反例或正例。

44. 2026-06-11 三角化算例后的 partial dominance 对照

前一轮复测仍使用旧 010/011，用户指出应先把算例改成满足三角不等式/删除单调性后再比较。本轮不覆盖原始数据，而是在 `test-results/bpc/tmp-triangle-20260611/` 下生成临时三角化版本。处理方式为对转移时间 `a_ij=s_ij+p_j` 做 Floyd-Warshall 闭包，再写回 `s_ij=max(0,a_ij-p_j)`，使 `s(i,k) <= s(i,j)+p_j+s(j,k)` 成立。生成后验证 010/011 的 deletion-monotonicity violation 均为 0。

测试口径仍为 root-only：`maxNodes=1`、ALNS seed、启发式 pricing、`completionBound=allCycles`、关闭 RMIH 和 midpoint probe。三角化 010 的 normal 结果为 `obj=16718,bound=16139.8,solve=25.843s,exact=6.490s,calls=4,valid=true`；partial 结果为 `obj=16718,bound=16139.8,solve=26.648s,exact=6.406s,calls=4,valid=true`。两者根界一致，exact 时间基本持平，partial 略快 `1.3%`，总时间略慢。

三角化 011 的 normal 结果为 `obj=13813,bound=13323.109589,solve=24.509s,exact=5.909s,calls=5,valid=true`；partial 结果为 `obj=13813,bound=13323.109589,solve=22.778s,exact=4.572s,calls=4,valid=true`。两者根界一致，partial exact 约快 `22.6%`，总时间约快 `7.1%`。这说明前面旧 011 的 bound 差异确实来自算例前提不满足，而不是 partial dominance 必然导致错误 bound。

当前更合理的结论是：在满足删除单调性的三角化版本上，两个测试算例的 normal/partial root bound 已一致；速度方面 partial 在 011 更好，在 010 基本持平。样本仍然很少，partial 还不能直接设为默认，但它作为候选 dominance backend 的正确性风险比旧 011 结果显示的要小，后续应在新生成的合规算例上继续扩大样本。
45. 2026-06-11 graph partial 与 partial-list 的 30 任务 root 对照校正

本轮重新检查“30 任务求解不动”和“normal 叠加 partial 后是否应接近 partial-list”的问题。先校正一个测试口径：前一次 `tmp-ng-full-normal-30-20260611` 使用的是 `twet.bpc.maxNodes=1`，但 `GCBBFullDomainComparisonTest` 实际读取的是 `twet.bpc.fullDomainCompare.maxNodes`，因此那次并没有真正限制为 root-only，不能作为“30 root pricing 卡住”的证据。

按正确 root-only 口径重跑 `tmp-wet030_001_2m` 后，四组都能正常返回，且 obj/bound 一致。普通 elementary normal 为 `solve=7.153s, exact=1.423s/3 calls, pool=4571`；普通 partial-list 为 `solve=7.449s, exact=1.397s/3 calls, pool=4513`；full-ng normal 为 `solve=7.364s, exact=1.475s/3 calls, pool=4571`；full-ng graph-partial 为 `solve=6.964s, exact=1.477s/3 calls, pool=4506`。因此，当前 30 root 并没有因为 partial dominance 或 full-ng 直接“求解不动”；之前的不动更可能是继续进入后续分支搜索后的现象。

从 exact 统计看，partial-list 和 graph-partial 都会减少负列数量和 join 工作量，但幅度和路径不同。普通 normal 第一轮 exact 加入 `102` 条负列，partial-list 第一轮加入 `35` 条，full-ng graph-partial 第一轮加入 `37` 条；对应 join candidates 约为 `9853 / 5095 / 5263`，函数评价约为 `6484 / 3174 / 3296`。这说明 partial 裁剪确实减少了后续 join，但不是免费收益。partial-list 需要 flat-list 两两比较，第一轮比较约 `32829` 次；graph-partial 复用 dominance graph 的 superset/subset 和 envelope 传播，第一轮 partial trim 为 `checks/partial/full=732/569/163`，图遍历也仍有成本。

当前 normal 叠加 graph partial 后，不能期待与 partial-list 完全一致。原因是两者不是同一引擎只替换一个函数：partial-list 是 `PartialListDominanceStore`，按同 terminal 的 active label 做近似全量两两裁剪；graph-partial 是 `PaperPartialDominanceGraph`，在 paper dominance graph 的节点包络、前驱包络和传播结构上做区间裁剪。前者更直接、更强地裁剪局部 frontier，但复杂度偏二次；后者更贴近 normal graph 结构，便于和 paper graph 对照，但裁剪机会受 graph 节点结构和传播顺序影响。再加上 graph-partial 当前挂在 ng-DSSR 入口上，即使 full ng-set 语义上接近 elementary，也仍多了 ng-memory、DSSR 记账和不同初始化路径，因此负列数和列池轨迹不必与 elementary partial-list 完全一致。

当前效率结论应收紧为：partial-list 在这个 30 root 上 exact 略快于 ordinary normal，但总时间略慢，说明 exact 局部收益被启发式 pricing、主问题和列生成路径差异抵消；graph-partial 在 full-ng 入口上总时间略快于 full-ng normal，但 exact 时间几乎相同，收益主要来自较少的 pricing/heuristic 轮次和列池差异，而不是单次 exact 大幅加速。二者目前都更适合作为实验对照，不宜直接宣布稳定优于 normal。

46. 2026-06-11 小规模跟踪 graph-partial 与 partial-list 的 label 差异

为解释 full-ng normal 与 full-ng graph-partial 为什么 label 数会不同，追加运行 `wet015_001_2m` root-only 小规模对照。三组结果均为 `obj=bound=3360, valid=true`：full-ng normal 为 `solve=0.560s, exact=0.148s/1 call`，full-ng graph-partial 为 `0.669s, exact=0.163s/1 call`，elementary partial-list 为 `0.564s, exact=0.155s/1 call`。

这个小例子显示差异从 dominance 插入阶段就会出现。full-ng normal 的 exact 统计为 `labels fw kept/dominated=23/2, bw=9/0`，`paperGraph labels kept/rejected=32/2`，`forwardExtend constructed=249`。graph-partial 的统计为 `labels fw kept/dominated=23/0, bw=9/0`，`paperPartialGraph labels kept/rejected=32/0`，但有 `partialTrim checks/partial/full=12/12/0`，同时 `forwardExtend constructed=233`、`infeasible=11`。也就是说，graph-partial 没有把那类 label 直接整条删掉，而是把 frontier 中被 envelope 覆盖的时间区间裁成不可行；这些裁剪区间随后会改变子 label 的可扩展时间域，使部分后续扩展不再构造或变成 infeasible。因此 label 数量不同不是 bug，也不是简单的“多个 label 占优同一个 label 应该等价”，而是 partial dominance 本身改变了 label frontier 的定义域。

同一个 15 任务上，partial-list 与 graph-partial 的外部统计基本一致：`labels fw/bw`、join groups、join pairs、candidatePool 都相同；但 partial-list 的 `trims partial/full=24/0`，graph-partial 为 `12/0`。这说明在很小规模上，两种 partial 可能最终走到同一组候选列，但裁剪来源和次数已经不同。规模稍大到 21 任务时差异开始显性化：partial-list 第一轮 `bw kept=70`、join candidates `249`、funcEval `71`，graph-partial 为 `bw kept=72`、join candidates `267`、funcEval `72`；30 任务第一轮 partial-list 返回 `35` 条负列，graph-partial 返回 `37` 条。原因是 partial-list 是同 terminal active label 的 flat-list 两两裁剪，而 graph-partial 是 paper dominance graph 上的节点包络、前驱包络和传播裁剪；后者只在 graph 结构认为可比较的 envelope 路径上裁剪，不能覆盖 flat-list 的所有两两比较机会。

当前结论是：normal、graph-partial、partial-list 三者在根节点 bound 一致时，可以认为都没有明显漏掉负列的证据；但它们保留的 frontier 区间、后续扩展、join 候选和入池负列数天然可能不同。partial-list 通常裁剪更直接、更强，但有二次比较开销；graph-partial 更贴近原 paper graph 结构，便于作为 normal 的增量实验，但不能期待与 partial-list 逐 label 等价。

47. 2026-06-11 partial 裁剪为什么不等价于简单聚合占优

进一步解释 partial-list 与 graph-partial 的差异。若只看一个静态目标函数 `L` 和同一批支配函数 `A/B`，先聚合成 `E=min(A,B)` 再裁剪 `L`，与分别用 `A`、`B` 顺序裁剪 `L`，理论上应当得到相同的剩余定义域。例如 `L(t)=10,t∈[0,10]`，`A(t)=5,t∈[0,4]`，`B(t)=5,t∈[6,10]`，则两种方式都会删掉 `[0,4]∪[6,10]`，只留下 `(4,6)`。如果当前问题只是“多个 label 同时占优同一个 label”，用户的直觉是对的。

但 labeling 里的 partial dominance 不是一次静态集合操作。一个 label 被部分裁剪后，不是立刻消失，而是带着缩小后的 frontier 继续作为后续扩展源和后续支配者。比如 `L(t)=10,t∈[0,10]`，支配函数只覆盖 `[0,6]`，partial 后 `L` 仍在 `[6,10]` 活着；如果下一步扩展需要 `t≤5`，它就不能再扩展，如果下一步扩展需要 `t≥8`，它仍然可以扩展。因此裁剪会改变后续 constructed extension、infeasible extension、join 候选和负列集合。这也是 15 任务日志中 normal `forwardExtend constructed=249`，graph-partial 降到 `233` 并出现 `11` 个 infeasible 的直接原因。

另一个差异是“进入聚合 envelope 的 label 集合”并不总是和 partial-list 的两两扫描集合完全相同。partial-list 对同 terminal active label 做 flat-list 检查，只要 existing 的 reachableSet 是目标的超集，就直接裁剪；graph-partial 则依赖 paper dominance graph 的同节点、前驱 envelope、superset/subset 结构和传播顺序。它压缩了很多比较，但也意味着 partial trim 的时机和对象不等价于 flat-list 全扫描。于是即使最终 bound 一致，标签数量、被裁剪区间、join 候选和负列数量仍可能不同。

48. 2026-06-12 partial-list 与 graph-partial 的当前取舍判断

从直觉上，paper dominance graph 叠加 partial 裁剪应该更有吸引力，因为它希望保留 graph 的 superset/subset 压缩能力，同时避免 whole-label dominance 过粗。但当前实测并没有体现这个优势。主要原因有三点：第一，当前 graph 的 DFS、node envelope、predecessor envelope 和传播维护本身不便宜，之前 normal paper graph 在部分 30/40 root 上已经出现大量 superset/subset 访问；第二，graph-partial 只在 graph 结构认为可比较的 envelope 路径上裁剪，裁剪机会少于 partial-list 的 flat-list 两两扫描；第三，当前 graph-partial 挂在 ng-DSSR 入口上，哪怕 full ng-set 语义接近 elementary，也仍有 ng-memory、DSSR 记账和不同列生成路径的额外影响。

因此当前更务实的结论是：如果目标是近期在当前 exact pricing 框架下尝试 partial dominance 提速，partial-list 比 graph-partial 更值得继续推进。它结构简单，语义直接，裁剪强度更可控；在三角化 010/011 和 30/40 root 的已有结果里，也更容易看到 exact pricing 层面的收益。它的问题是复杂度偏二次，规模继续变大时可能爆炸，并且未来接入 SRI/subset-row cut 或其他资源状态时，必须把这些状态显式纳入 dominance key，不能直接复用当前 store。

graph-partial 暂时不建议作为默认候选。它适合作为后续研究方向保留：如果将来优化了 paper graph 的集合查询、减少 envelope 传播开销，或者在更大节点上 flat-list 明显二次爆炸，graph-partial 可能重新有价值。但基于当前证据，它没有比 partial-list 更好，甚至因为裁剪机会更少和入口更复杂，收益更不稳定。

49. 2026-06-12 graph envelope 理想复杂度与当前实现差异

用户进一步指出：如果有 5 个新 label 都需要被 5 个旧 label 的下包络裁剪，partial-list 需要 `5*5=25` 次比较，而 dominance graph 若预先存了下包络，只需要对 5 个新 label 各比较一次，因此直觉上 graph 应该更高效。这个判断在静态同候选集场景下成立：若所有新 label 的可比较旧 label 集合完全相同，且旧 label 的 lower envelope 已经稳定缓存，那么用 envelope 做 5 次裁剪确实优于 flat-list 的 25 次两两裁剪。

但当前 pricing 里的 paper graph 不是这样一个全局静态 envelope。每个 label 的 dominance key/reachableSet 不同，可比较的旧 label 集合也不同；graph 需要先做 superset/subset 查询，找到同节点或前驱节点集合，再 merge 这些节点的 envelope。也就是说，所谓“1 次 envelope 比较”前面还有图搜索、节点 envelope 合并、后继传播和缓存维护成本。并且 partial trim 会改变 label frontier 的定义域，导致 node 的 labelEnvelope/dominanceEnvelope 需要更新，并可能继续影响后继节点。当前日志里这些成本体现在 superset/subset visited、propagate visited、envelopeMerges 和 dominanceChecks 上。

另一个关键差异是裁剪强度。partial-list 是同 terminal active labels 的直接两两扫描，只要 existing 的 reachableSet 是目标的超集，就尝试裁剪；graph-partial 只在 graph 结构维护的 same-node/predecessor envelope 路径上裁剪，压缩了比较次数，但也可能少做一些 flat-list 会做的裁剪。因此当前结果表现为：graph-partial 在理论上有更好的扩展方向，但在现有实现和当前规模下，隐藏维护成本较高、裁剪机会又少于 partial-list，所以没有显著快于 partial-list。

更准确的取舍应是：当每个 terminal 下 active label 数很大、可比较集合高度重叠、且 graph envelope 可以低成本复用时，graph-partial 才可能超过 partial-list；当 label 数还不极端、flat-list 两两比较常数小、且 graph 查询/传播较重时，partial-list 反而更实用。当前 TWET 的 root 对照更接近后一种情况。

50. 2026-06-12 partial-list 的 cardinality 必要条件过滤

复查 `PartialListDominanceStore` 后发现，原实现每次两两比较直接调用 `isSupersetOf()`，没有先使用 reachable-set 大小做必要条件过滤。由于 `Label` 已缓存 `reachableCardinality`，本次在两个方向的裁剪中加入安全过滤：若 existing 的 reachable cardinality 小于目标 label，则 existing 不可能是目标的超集；反向裁剪时若新 label 的 cardinality 小于 existing，则新 label 不可能支配 existing。single-point dominance 同步先计算目标 cardinality，再进入 superset 检查。

该优化不改变 dominance 语义，只减少不可能成立的 bitset superset 检查和后续函数裁剪调用。为观察效果，日志新增 `cardinalitySkips`。`wet015_001_2m` root partial-list 中，`comparisons=78`，其中 `cardinalitySkips=29`；`tmp-wet030_001_2m` root 三轮 exact 中分别为 `32829/11178`、`14894/4974`、`11354/3683`。说明 cardinality 过滤命中比例不低。需要注意，本轮 30 任务 wall time 受 completion bound 构造时间波动影响很大，不能只凭单次总耗时判断该过滤的净收益；更可靠的意义是减少后续 bitset 和 PWLF 裁剪机会，作为低风险基础优化保留。

后续还能考虑把 partial-list 按 `reachableCardinality` 分桶或排序：第一轮“旧 label 支配新 label”可在 cardinality 低于目标时提前停止，第二轮“新 label 裁剪旧 label”可跳过 cardinality 高于目标的前缀。但这种会改变 label 访问顺序，而 partial trim 是就地修改 frontier，访问顺序可能影响生成路径；因此暂不直接改，只记录为后续可控实验项。

51. 2026-06-12 partial-list cardinality 缓存化补充

前一节加入的 cardinality 必要条件过滤不是在每次比较时重新扫描 bitset。普通 label 在 `Label` 构造时已经计算并缓存 `reachableCardinality`，partial-list 两两比较直接读取该字段；因此过滤本身只是一次整数比较，只有通过必要条件后才进入 `isSupersetOf()` 和后续 PWLF 裁剪。

本次进一步把 single-point dominance 的接口也改成同时传入 `reachableCardinality`。原来 single-point 只有 `reachableSet`，`PartialListDominanceStore.dominatesSinglePoint()` 会在入口处调用一次 `reachableSet.cardinality()`；现在调用点直接传 `label.reachableCardinality`，partial-list 不再在 single-point 检查中现场计数。旧 `DominanceGraph` 节点也补了 `reachableCardinality` 缓存，paper graph / indexed graph 的 single-point 路径接收该值并复用到 superset 搜索。

这次修改不改变 dominance 语义，只减少重复 cardinality 计算和明显不可能成立的 superset 检查。验证方面，focused `javac` 覆盖 `DominanceStore`、三套 dominance backend 和主要双向 pricing 类通过；`wet015_001_2m,maxNodes=1,partialDominance=true` smoke 返回 `ROOT_PROCESSED,obj=bound=3360,valid=true`，日志中 `comparisons=78,cardinalitySkips=29`，说明过滤仍正常生效。

52. 2026-06-12 partial-list cardinality 正确性与分桶思路

继续复查当前代码后，`reachableCardinality` 缓存的正确性依赖一个简单不变式：`Label` 创建后 `reachableSet` 不再被原地修改。当前 GC 代码中 `label.reachableSet` 后续主要用于 `nextSetBit()` 遍历、`isSupersetOf()` 判断和作为 dominance key，未发现对该对象的 set/clear 原地修改；子 label 使用新构造的 reachable set。因此缓存值与集合内容保持一致，可以安全用于必要条件过滤。

cardinality 过滤本身也是严格必要条件，不会误删 label。若 `A.reachableSet` 要成为 `B.reachableSet` 的超集，则必然有 `|A| >= |B|`。当前新增逻辑只在该必要条件不满足时跳过后续 `isSupersetOf()` 和函数裁剪；满足时仍按原来的 superset + frontier partial trim 判断。因此它只减少比较成本，不改变 partial dominance 的支配集合。

partial-list 后续可以按 `reachableCardinality` 分桶，以减少无效比较。第一遍“旧 label 裁剪新 label”只需要扫描 cardinality 不小于新 label 的 bucket；第二遍“新 label 裁剪旧 label”只需要扫描 cardinality 不大于新 label 的 bucket。这样不会改变理论支配条件，但实现时要注意 partial trim 是就地修改 frontier，且当前 list 顺序会影响数值路径和生成列轨迹；因此建议先做一个保守版本：bucket 只用于缩小候选范围，每个 bucket 内仍保持插入顺序，先不排序、不提前重排 active labels。这个版本改动较小，也便于和当前 flat-list 对拍。

53. 2026-06-12 partial-list 按 cardinality bucket 扫描

按讨论进一步把 `PartialListDominanceStore` 从单个 flat `labels` 改成 `labelsByCardinality`。新 label 插入时，第一轮“旧 label 裁剪新 label”只扫描 cardinality 不小于新 label 的 bucket；第二轮“新 label 裁剪旧 label”只扫描 cardinality 不大于新 label 的 bucket；single-point dominance 也只扫描 cardinality 不小于目标点 label 的 bucket。`cardinalitySkips` 保留为诊断统计，但含义变为整 bucket 跳过的候选 label 数量，而不是逐个 label 扫到以后再跳过。

该实现不再保留全局插入顺序，bucket 内仍保持原插入顺序。由于 partial trim 是就地裁剪 frontier，生成列轨迹可能与 flat-list 略有差异，但支配条件仍是原来的 `reachableSet` 超集加 frontier 区间不劣，因此不会因为 bucket 本身放宽或加强 dominance 语义。这个版本的收益来自减少无效候选扫描，而不是改变裁剪规则。

验证结果：focused `javac` 通过。`wet015_001_2m,maxNodes=1,partialDominance=true` 返回 `ROOT_PROCESSED,obj=bound=3360,valid=true`，exact 时间 `0.188s`，日志中 `comparisons=49,cardinalitySkips=29`；同口径 flat-list 缓存版为 `comparisons=78,cardinalitySkips=29`。`tmp-wet030_001_2m,maxNodes=1,partialDominance=true` 返回 `NODE_LIMIT,obj=46152,bound=15261.833333,valid=true`，三轮 exact 的 partial-list 统计分别为 `comparisons/cardinalitySkips=19929/11241`、`9343/4995`、`7160/3709`；此前 flat-list 缓存版对应为 `32829/11178`、`14894/4974`、`11354/3683`。因此 bucket 版在不改变结果有效性的前提下明显减少了 partial-list dominance 的实际比较次数。

54. 2026-06-12 为什么 partial-list 当前可能快于 graph

当前 partial-list 更快的原因不在于它的渐进复杂度更好，而在于当前 15/30/40 任务 root 规模下，每个 terminal job 下的 active label 数还没有大到让 graph 的结构维护成本摊薄。partial-list 的一次候选比较很直接：整数 cardinality 过滤、bitset superset 判断、必要时做一次 PWLF partial trim。bucket 后，很多 cardinality 不可能满足的 label 连遍历都不进入，常数更低。

paper dominance graph 的一次插入看起来像“用已有 envelope 比一次函数”，但实际前置成本很多：先要按 reachable-set 包含关系找 terminal superset node，必要时做 DFS / index 查询；再 merge 候选节点的 `g` envelope；若插入新 node，还要找 immediate subset node、维护 predecessor/successor 边、断旧边、更新 root；插入后还要向后传播 dominance envelope，并可能重算后继 node 的 label/dominance envelope。这里每一步都涉及对象遍历、PWLF copy/merge、图边维护和缓存失效。对当前规模而言，这些固定成本往往高于直接扫一批 bucket label。

另一个实际差异是裁剪粒度。partial-list 直接拿真实旧 label frontier 去裁真实新 label frontier，也会反向用新 label 裁旧 label；它不需要先把多个 label 聚合成 graph node envelope 再传播。graph 的聚合 envelope 在理论上能复用，但复用前要付出查询和传播代价；并且 partial trim 修改 frontier 后还会触发 envelope 重建/传播，使维护成本进一步上升。因此当前看到 partial-list 快，不矛盾：它是低结构开销、强直接裁剪；graph 是高结构开销、试图复用包络，只有当 label 数更大且查询/传播能显著少于两两比较时才可能反超。

由此当前判断是：partial-list/bucket 适合继续作为实验分支，因为它简单、常数低、便于对拍；paper graph 仍有价值，但需要进一步优化 superset/subset 查询、envelope merge 和传播成本，或者等到更大规模/更高重复查询场景下才可能体现优势。

55. 2026-06-12 ng-DSSR + partial-list dominance 实验入口

本次按“优先做 ng 版本 partial-list”的要求，没有复制整套 `GCNGBBStyleBidirectionalNgDssr`，而是在现有 ng-DSSR 主体中把 dominance backend 从原来的 boolean graph partial 扩展为三种：`PAPER`、`GRAPH_PARTIAL`、`LIST_PARTIAL`。这样 ng-set 初始化、DSSR 轮次、non-elementary route 更新、completion bound、Tmid/probe 和 final join 全部沿用当前 ng-DSSR 实现，只替换 terminal dominance store。新增入口类为 `GCNGBBStyleBidirectionalNgDssrPartialDominancePricingEngine`，配置开关为 `useGCNGBBStyleNgDssrPartialDominancePricing`，测试属性为 `twet.bpc.fullDomainCompare.ngDssrPartialDominance=true`。

当前这个版本使用的是 bucket 化后的 `PartialListDominanceStore`。因此在 ng-DSSR 标签构造出的 `reachableSet/extensionSet` 语义下，它会按同一套 dominance key 做 partial-list 裁剪；这与已有 graph partial 入口并列，便于后续直接比较 `ng`、`ngGraphPartial` 和 `ngPartial`。需要注意，这仍是实验分支，不改变默认主线。

验证方面，focused `javac` 覆盖配置、context、ng-DSSR 主体、新 engine 和 `GCBBFullDomainComparisonTest` 通过。`wet015_001_2m,maxNodes=1,ngDssrPartialDominance=true,nearestK8,top5,completionBound=allCycles` 返回 `ROOT_PROCESSED,obj=bound=3360,valid=true`，exact engine 为 `GCNGBBStyleNgDssrPartialDominancePricing`，日志输出 `partialList labels kept/rejected/deleted=32/0/0, comparisons=47, cardinalitySkips=31`，说明确实走了 partial-list backend。`tmp-wet030_001_2m` 同口径返回 `NODE_LIMIT,obj=46152,bound=15261.833333,valid=true`，exact 为 `2.754s/6 calls`；普通 ng-DSSR 同口径为 `2.199s/4 calls`，bound 相同。因此当前只证明新组合可用且结果有效，不能说明它比普通 ng 更快。

56. 2026-06-12 dominance graph 后续可优化点

当前主用 `PaperDominanceGraphs` 已明确回到经典 DFS backend，`IndexedPaperDominanceGraph` 仍保留但不再通过运行参数参与主路径，说明此前 containment index / set trie / superset cache 的端到端收益不稳定。结合当前代码，graph 的主要成本集中在四块：第一，`findTerminalSupersetNodes()` 从 roots DFS 到 terminal superset node；第二，`findImmediateSubsetNodes()` 查找新 node 的 immediate subset successors 并做冗余候选过滤；第三，`mergeGEnvelopes()` 和 node 内 `recomputePredecessorEnvelope()/recomputeDominanceEnvelope()` 反复 copy/merge PWLF；第四，`propagateAndTrim()` 用队列向后传播，过程中可能删除 node、重连边并重算后继 envelope。

如果继续优化 graph，优先级较高的是“减少重复查询和重复 merge”，而不是重新设计 dominance 语义。比较可控的方向包括：1）给 node 维护更轻量的 cardinality 分层入口，superset/subset 查询先按 cardinality bucket 限定候选，再做 bitset 判断；2）对 predecessor envelope 增量维护或版本化缓存，避免每次传播都从所有 predecessors 重新 merge；3）把 propagation 的 `HashSet<PaperDominanceNode> queued` 换成 node 上的 mark 字段，降低长传播链上的对象分配；4）给 `mergeGEnvelopes()` 增加“单候选直接 copy / 空候选直接返回”的快路径，并统计候选数量分布，判断是否值得做 envelope reuse；5）在 graph partial 模式下，进一步区分“只 partial trim label”与“需要重建并传播 envelope”的场景，避免无变化时仍向后传播。

不建议现在马上重启 indexed backend 作为默认优化，因为它之前已经表现出不稳定，而且它会同时引入索引维护、cache 失效和 set trie 路径选择问题。更稳的做法是先在经典 graph 上补更细的计时字段：superset/subset 查询时间、mergeGEnvelopes 总时间和候选数、propagation 重算 predecessor/dominance envelope 时间、删除/重连次数。只有确认某一块稳定占大头后，再做针对性优化。

57. 2026-06-12 indexed backend 与当前几种 ng-DSSR dominance 策略的区别

`indexed backend` 指代码中保留的 `IndexedPaperDominanceGraph`，它不是新的 dominance 语义，而是试图给 paper dominance graph 的 reachable-set 包含关系查询加索引。相比当前默认的 `PaperDominanceGraph` 从 roots 沿 successors 做 DFS，它额外维护按 cardinality、按 job、set-trie 和 superset cache 等结构，希望更快找到 terminal superset nodes 和 immediate subset nodes。但这些索引也带来插入、删除、结构版本、cache 失效、set-trie 路径选择等维护成本。此前实验中端到端收益不稳定，所以 `PaperDominanceGraphs.create()` 已经固定回经典 DFS graph，indexed 代码只保留为实验实现。

当前 ng-DSSR 下几种策略的主流程是一样的：ng-set 初始化、label 扩展、DSSR 更新、completion bound、Tmid/probe 和 final join 都沿用 `GCNGBBStyleBidirectionalNgDssr`。差别集中在 terminal job 下的 dominance store。`PAPER` 使用普通 paper graph：按 reachable-set 节点建图，插入 label 时要找 superset predecessors、merge dominance envelope、必要时建新 node 并向后传播。`GRAPH_PARTIAL` 仍使用 graph 结构，但把完整支配扩展为 partial trim。`LIST_PARTIAL` 不建 graph，只在同 terminal 的 partial-list/bucket 中直接扫描满足 cardinality 条件的真实 labels，用 superset + frontier 区间不劣做 partial trim。

因此可以把当前三种 ng-DSSR dominance 策略理解为“同一套 ng 定价主流程 + 不同 terminal dominance 后端”。normal/paper 的优势是尝试复用 graph envelope、减少重复支配计算；代价是图查询、envelope merge、边维护和传播。partial-list 的优势是结构成本低、直接比较真实 label；代价是当同 terminal label 数很大时，两两扫描仍可能爆炸。当前小到中等 root 实验里，partial-list/bucket 的常数更低，但这不等于它在所有节点和更大规模上一定更优。

58. 2026-06-12 非 ng partial-list 当前 30 root 对照

按“不要用 ng，只测优化后的 partial-list”的口径，重新在三角化后的 30 任务 010/011 root 节点做 normal 与 partial-list 对照。统一配置为 `maxNodes=1`、`completionBound=allCycles`、启发式 pricing 开启、ALNS seed 开启、RMIH 关闭、midpoint probe 关闭、ng-DSSR 相关开关关闭。010 中 normal 为 `solve=17.051s, heuristic=8.231s/22, exact=4.037s/4, bound=16139.8`，partial-list 为 `solve=20.380s, heuristic=10.757s/22, exact=5.774s/4, bound=16139.8`。011 中 normal 为 `solve=25.784s, heuristic=11.562s/24, exact=5.640s/5, bound=13323.109589`，partial-list 为 `solve=22.550s, heuristic=10.864s/23, exact=4.453s/4, bound=13323.109589`。

这组结果说明，bucket partial-list 当前不是稳定快于 paper graph。它在 011 上减少了一轮 exact pricing 和一轮 heuristic pricing，因此总时间更好；但在 010 上虽然负列路径仍有效，partial-list 的两两裁剪本身较重，第一轮就有约 `49.7` 万次 comparisons 和 `32,267` 次 partial trim，导致 exact 与 heuristic 总体都慢。normal paper graph 在 010 上维护 graph 的成本被 label/envelope 复用摊薄得更好，partial-list 的直接扫描没有占到便宜。

当前 partial-list 插入流程是双向裁剪：先扫描 cardinality 不小于新 label 的 bucket，用已有 label 的 frontier 裁新 label；如果新 label 被完全裁空，则拒绝插入。若新 label 仍有有效区间，则再扫描 cardinality 不大于新 label 的 bucket，用新 label 裁已有 label，旧 label 被裁空就从 bucket 删除；最后把新 label 放入自己的 cardinality bucket。也就是说，它确实是逐个比较、互相裁掉被对方占优的时间区间，然后后续扩展基于被裁剪后的 frontier 继续进行。

关于 ng-DSSR 的 partial 版本，目前实现上并没有额外优化到足以成为默认。它复用同一个 `PartialListDominanceStore` 作为 `LIST_PARTIAL` backend，因此 dominance store 层面的常数低、语义直接；但 ng-DSSR 主体还有 ng-memory、DSSR 多轮、non-elementary route 记录与更新等额外成本。此前 30 root smoke 中普通 ng-DSSR 为 `exact=2.199s/4 calls`，ng + partial-list 为 `exact=2.754s/6 calls`，说明 partial-list 后端在 ng 框架下不一定减少 DSSR 轮数，反而可能改变列生成轨迹并增加 exact calls。当前结论是：非 ng partial-list 可以继续作为实验分支；ng + partial-list 目前只能算可用但未证明高效。

59. 2026-06-12 partial-list 后续真正可优化的位置

进一步区分“慢的原因”和“能动手优化的点”。当前 partial-list 的主要可优化点不应是再泛泛减少二次比较，而是降低每次比较进入 PWLF 裁剪后的无效成本。`PartialListDominanceStore.trimFrontierBy()` 现在调用 `updateDominatedIntervals()` 后总会刷新 `minReducedCost`，而 `updateDominatedIntervals()` 当前只返回“是否被裁空”，不区分“发生了部分裁剪”和“完全没有变化”。更重要的是，函数内部在非支配区间上也可能为了扫描对齐拆分 segment，最后仍执行 normalize。也就是说，即使一个 existing label 没有真正裁掉目标 label 的任何区间，也可能产生函数结构改写、normalize 和 `findMinimal()` 成本。这是当前最值得优先处理的工程优化。

更具体的优化顺序建议为：第一，把 `updateDominatedIntervals()` 改为返回三态结果，例如 `NO_CHANGE / PARTIAL / EMPTY`，并在没有任何被支配区间时不改写 segment、不 normalize、不刷新 `minReducedCost`；第二，在 partial-list 调用前加非常便宜的定义域 overlap 快速判断，公共定义域为空则直接跳过；第三，给同 terminal 下的完全相同 reachableSet 建 exact-key 小桶，先比较同 key label，因为这一类最容易发生完整或大段 partial 裁剪，若新 label 已经被裁空就不必再扫更大的 superset bucket；第四，统计用的 `countLabelsInBuckets()` 可以改为维护 bucket size 累计或诊断开关下才计算，但这只是小优化，不是主矛盾。

对于 ng + partial-list，当前可优化方向不是单独再调 partial-list，而是减少 DSSR 主体的重复成本。需要确认 completion bound、half cache 和候选状态是否在同一组 dual 下跨 DSSR 轮被复用；若仍有重复初始化，应优先消除。其次可以继续比较 topK non-elementary 更新、初始 ng-set 和 route 去重策略是否减少 DSSR 轮数。ng 版本慢时，很多时候不是 dominance store 慢，而是 DSSR 轮次和每轮重新定价次数多。

60. 2026-06-12 ng-DSSR 重复计算与冗余初始化排查

按“ng 流程本身不动，只查重复计算”的口径复核 `GCNGBBStyleBidirectionalNgDssr`。首先确认一个之前担心的点已经处理：`solve()` 中 `ngDssrReusableCompletionBounds` 和 `ngDssrReusableCompletionBoundFixedArc` 会在同一次 pricing 的 DSSR 多轮之间复用，`initialize()` 先把 `completionBounds` 指向可复用对象，只有为空时才 `buildCompletionBounds(lp)`。因此当前不是每轮 DSSR 都重建 completion bound。

当前仍可能存在的冗余主要有三类。第一，每轮 relaxed round 都会重新 `precomputeDynamicPricingWindows(lp)`，其中包括 effective window、zero-dual excluded jobs、job-level dynamic windows、backward windows、completion-bound pricing windows 和 half-domain eligibility。这里大部分只依赖当前 LP dual、node、pricing horizon 和 `tMid`；在同一次 DSSR 内 dual/node 不变，只有 ng-set 变，因此除 `tMid/probe` 可能导致的 half-domain 部分外，很多数组理论上可跨 DSSR round 复用。需要注意，如果 midpoint probe 每轮重新选出不同 `tMid`，half-domain penalty 和 eligibility 必须重建，但 effective window、zero-dual、completion-bound penalty 可以不重算。

第二，`initializeCandidateState(lp)` 每个 round 都扫描当前 restricted columns 构造 `activeColumnSignatures`，并重建 generated candidate heap/hash。对于中间 DSSR round，若这一轮最终只发现 non-elementary negative route，候选列池最后不会返回主问题；但当前仍需要候选池来保存本轮 elementary negative columns，因为一旦有 elementary negative columns 就会立即返回。可优化方向不是简单删除 candidate state，而是延迟初始化：先记录 elementary negative sequence/cost 的轻量候选，确定需要返回列时再建立 signature/heap 去重；或者把 `activeColumnSignatures` 在同一次 solve 中缓存，因为 restricted columns 在 DSSR 多轮内不变。

第三，`maybeDumpPricingSnapshot(lp)`、`recordPricingDiagnostics(lp)`、dominance diagnostic context 和若干统计数组在每轮 round 都重新初始化。默认关闭时影响很小；但开启诊断或 snapshot 时会产生明显重复 I/O 或扫描。这个不影响正式求解，但应避免在性能实验中打开。

已经不存在或不是主问题的点也要明确：rank0 midpoint probe 已经做了 label 复用，`midpointProbeLabelsReadyForJoin` 为 true 时不再重跑 forward/backward labeling，只补 `initializeCandidateState(lp)` 后 join；base half penalty 也有 `baseHalfPenaltyCacheTMid/baseHalfPenaltyCacheHorizon` 缓存，同一 `tMid/horizon` 下不会重复 crop 静态半域函数。因此当前最可做的优化是跨 DSSR round 缓存“只依赖 dual/node 的预处理”和 `activeColumnSignatures`，而不是再改 DSSR 更新流程。

61. 2026-06-12 ng-DSSR 跨 DSSR round 缓存优化

本次先按上一节确认的方向做低风险优化，不改 ng-set 更新、DSSR 停止条件、join 语义和 dominance 语义。核心判断是：同一次 `solve(lp)` 内，DSSR 多轮只改变 ng-neighborhood；当前 LP dual、node、pricing horizon、restricted column 集合都不变。因此依赖这些固定信息的预处理不应每个 DSSR round 重算。

代码层面做了两处缓存。第一，新增 `ngDssrReusablePricingWindowPrecomputeReady`，把 `effectiveJobHStart/effectiveJobHEnd`、zero-dual excluded jobs、dual profitable window 开关、completion-bound pricing window penalty 等只依赖 dual/node 的数组移到 `precomputeDssrReusablePricingWindows(lp)`，同一次 pricing 只算一次。`precomputeDynamicPricingWindows(lp)` 仍会每轮重建 job-level dynamic window、backward dynamic window 和 half-domain eligibility，因为这些会受到最终 `tMid` 影响；如果 midpoint 策略或 probe 让 `tMid` 变化，这部分必须重算，不能缓存。

正确性复核时发现这里不能只缓存数组，还必须同步缓存 `pricingHorizon`、`dynamicMinHStart`、`dynamicMaxHEnd` 和 `earliestSourceCompletion`。原因是 `initialize()` 每轮 relaxed round 开头会先把 `pricingHorizon` 重置为 `data.CmaxH`；如果后续 round 复用第一轮的 effective window 数组，却不恢复这些标量，那么 midpoint、completion bound 和 half-domain 的右端点可能与数组对应的窗口不一致。因此最终实现中增加了 scalar cache/restore，第一轮预处理后保存，后续 DSSR round 先恢复这些标量，再按当前 `tMid` 重建 half-domain。

第二，新增 `ngDssrReusableActiveColumnSignatures`，把 active restricted columns 的 signature 集合缓存到同一次 pricing 内。候选列 heap/hash 仍每轮重建，因为每一轮 DSSR 的 elementary negative columns 需要独立记录；但“当前 RMP 已 active 的列”集合在 DSSR 多轮内不变，只需第一轮扫描 restricted columns。这样可以避免每轮都重新遍历 restricted pool 构造相同的 signature set。

验证方面，focused `javac` 通过。补上 scalar restore 后，两个 smoke run 均 `valid=true`：`wet015_001_2m,maxNodes=1,ngDssr=true,nearestK8,top5` 返回 `ROOT_PROCESSED,obj=bound=3360,solve=1.468s,exact=0.212s/call=1`；三角化 30 任务 `tmp-wet030_from040_010_2m,maxNodes=1` 返回 `NODE_LIMIT,obj=16718,bound=16139.8,solve=17.304s,exact=3.522s/calls=5`。日志中多轮 DSSR 的后续 round 继续显示 completion bound build time 为 0，且 `pricingHorizon/dynamicHStartMin/dynamicHEndMax/tMid` 保持与当前 dual window 一致，说明原有 bound 复用没有被破坏。

需要注意，这次优化降低的是 DSSR round 内重复预处理成本，不会减少 DSSR 轮数，也不保证单个算例总时间一定下降。若某个节点的主要耗时仍在 label 扩展、join 或非基本 route 多轮收紧上，这次优化只能降低固定开销。后续若继续优化 ng，优先观察多轮 DSSR 中 `precompute/init` 占比是否还明显；如果不明显，就应转向减少 DSSR 轮数、减少无效 join 或改善 initial ng-set。

62. 2026-06-12 ng-DSSR 缓存修正后的正确性复核

复核时重点检查了 `tmp-ng-cache-smoke-30-010-20260612` 和 `tmp-ng-cache-check-30-010-20260612` 的差异。旧 smoke 中第三次及后续若干 exact pricing 出现了明显异常：多轮 DSSR 的后续 round 中 `pricingHorizon=4342.0`、`tMid≈2270`、`bw kept=0`、`halfWindowIneligible bw=30`，但同一轮的 `dynamicHEndMax` 仍约为 `1300`。这说明当时只复用 effective window 数组，没有恢复 `pricingHorizon` 等标量，导致后续 round 近似退化成单向 forward pricing。修正后对应日志中 `pricingHorizon≈1306`、`tMid≈748`、backward label 正常生成，说明数组与标量已重新一致。

因此“30 任务 exact calls 从 9 降到 5”不能解释为简单的性能优化收益，更准确地说是：旧版本存在标量未恢复导致的错误定价轨迹，修正后列生成批次、DSSR 轮次和 RMP 收敛路径自然改变。该差异本身反而是正确性修复生效的证据之一；后续若要评估纯性能收益，应只在修正后的同一提交上重复运行多次，不能拿修正前的 9 calls 作基准。

ng-DSSR 主流程再次核对后，当前未发现新的语义错误。初始 ng-set 仍按配置 `nearestK/dualPair/empty` 构造；memory 更新为 `(oldMemory ∩ N_current) ∪ {current}`，forward 和 backward 都按当前扩展到的真实 job 更新；join 先用 ng-memory 冲突和 forbidden arc 过滤，负 reduced-cost route 恢复后再用真实 sequence 判断 elementary。elementary 负列进入候选池；non-elementary 负 route 只用于更新 ng-set，不直接加入主问题；每轮可按 `ngDssrNonElementaryRouteUpdateLimit` 记录 topK route，更新时一个 route 内多个重复段都会处理。这个流程与当前讨论确定的 ng-DSSR 语义一致。

partial-list 也重新核对了一遍。bucket 化只用 `reachableCardinality` 做必要条件过滤，真正裁剪前仍要求 `existing.reachableSet.isSupersetOf(label.reachableSet)` 或反向的 `label.reachableSet.isSupersetOf(existing.reachableSet)`，因此不会因为 cardinality 过滤误删 label。single-point 路径同样先按 cardinality 限制候选，再做 superset 和 cost 比较。当前已知不足仍是性能/统计层面：`updateDominatedIntervals()` 只返回“是否被裁空”，不区分 no-change 和 partial trim，因此可能产生无效 normalize 和 partialTrims 统计偏高；但这不会把本不该删除的 label 当作完全删除。

63. 2026-06-12 normal / ng / partial-list 正确性复查

本轮只复查语义正确性，不比较效率。入口上，normal、partial-list、ng-DSSR、ng-DSSR + partial-list 是互斥 exact pricing 路径；partial-list 只替换 `FWTL/BWTL` 的 dominance store，ng-DSSR 则复用主双向 labeling 框架，但把 elementary 过滤放宽为 ng-memory 过滤，并在恢复完整 route 后再区分 elementary 和 non-elementary。

normal 与 partial-list 的扩展和 join 口径一致：forward/backward 扩展只枚举当前 `reachableSet`，随后即时检查 `isPricingArcForbidden()`，该函数统一包含真实 forbidden arc、pricingOnly arc 和 completion-bound 固定弧；final join 对 crossing arc 和 forward-to-sink 也使用同一个禁止弧口径。elementary 版本的 join 继续用真实 `visitedSet` 交集排除重复 job，因此不会生成重复任务列。partial-list 版本没有改这些逻辑，只把完整函数占优换为“先裁剪被支配区间，裁空后才删除 label”。`updateDominatedIntervals()` 的返回语义是“裁空返回 true，部分裁剪返回 false”，因此 partial-list 不会因为部分裁剪把新 label 当作已删除。

ng-DSSR 的语义也再次核对。label 的 `reachableSet` 在 ng 版本中实际是当前一跳 `extensionSet`，构造时排除了 zero-dual excluded job、ng-memory 中的 job、半域不可达 job 和当前资源不可达 job；forbidden arc 不进入 dominance key，只在实际扩展和 join 时检查。memory 更新为 `(oldMemory ∩ N_current) ∪ {current}`，forward/backward 都按当前扩展到的真实 job 更新。join 时只用 ng-memory 冲突过滤，不用真实 visited-set 交集提前过滤；恢复完整 sequence 后，elementary 负列进入候选池，non-elementary 负 route 只进入 DSSR 更新，不加入主问题。若存在 non-elementary negative route 但 ng-set 无法更新，当前直接抛异常，不静默 fallback。

graph partial 的 envelope 缓存也做了语义核对。已有 label 被 predecessor 或同 key 新 label 部分裁剪时，`labelEnvelope` 可能不是立刻重建；但被裁剪区间已经由 predecessor/new frontier 提供不差的下包络，后续 `dominanceEnvelope = min(labelEnvelope, predecessorEnvelope)` 不会比真实可用下包络更激进。因此这里当前判断为缓存/统计层面的保守性问题，没有发现会误删可行 label 的直接证据。若后续要彻底规整，可把 partial trim 的 no-change/partial/full 三态返回补齐，再按三态重建 envelope。

验证方面，focused `javac` 通过；`PaperDominanceGraphConsistencyTest` 通过 `cases=200, insertions=16000`；`PiecewiseLinearFunctionPropertyTest` 中 `updateDominatedIntervals` 的 full/partial/random sweep 均为 PASS，报告里的 3 个 FAIL 来自 `mergeMinimum` 无重叠定义域的诊断项，不指向本轮 partial 裁剪逻辑。15 任务 `wet015_001_2m,maxNodes=1,completionBound=allCycles,midpointProbe=false` 下，normal、partial-list、ng-DSSR、ng-DSSR + partial-list 四条路径均返回 `valid=true`，且根节点闭合到 `obj=bound=3360`。当前没有发现必须立即修复的正确性错误，剩余问题主要是性能和统计口径。

64. 2026-06-12 partial dominance 热路径优化分析

当前 `PiecewiseLinearFunction.updateDominatedIntervals()` 只返回 boolean，其中 `true` 表示函数被裁空，`false` 同时覆盖“确实部分裁剪”和“完全没有裁剪”。这会让调用侧无法区分 no-change 与 partial trim。更重要的是，当前实现是一边扫描一边对齐和拆分 segment：即使没有任何区间被支配，只要公共定义域内部存在 segment 边界不对齐，也可能拆分 `this` 的 segment，最后还会执行 `normalize()`。因此 no-change 比较也可能污染函数结构，并触发 `PartialListDominanceStore.trimFrontierBy()` 里的 `refreshMinReducedCost()` 和 `partialTrims++`。

建议第一步把接口扩成三态，优先保持兼容：在 `PiecewiseLinearFunction` 内增加 `TrimResult { NO_CHANGE, PARTIAL, EMPTY }` 和新方法，例如 `updateDominatedIntervalsDetailed(g, direction)`；原来的 boolean `updateDominatedIntervals()` 保留为 wrapper，只在 `EMPTY` 时返回 true。这样测试代码、旧 demo 和临时调用不用一次性全改，partial-list / graph partial 可以先切到三态。调用侧逻辑应变成：`NO_CHANGE` 不刷新最小值、不计 partial trim；`PARTIAL` 刷新最小值并计 partial trim；`EMPTY` 标记 dominated 并删除。

第二步应在 PWLF 内做只读预扫描。预扫描只遍历公共定义域，按当前两条线段和交点判断是否存在非零长度区间满足 `g(t) <= this(t)`。若不存在，直接返回 `NO_CHANGE`，不能拆分 segment，也不能 normalize。若存在，再走现有的替换为 big-M 的修改流程。这样风险比重写整段低，因为真正发生裁剪时仍复用原先经过测试的修改逻辑；同时能消除大量“比较但不裁剪”的结构污染。

第三步可在 dominance store 调用前做定义域 overlap 快速跳过。若 `label.frontier` 和 `dominatingFrontier` 的定义域没有正长度交集，直接返回 `NO_CHANGE`，不进入 PWLF。这个判断简单、低风险，且能减少无意义函数调用。注意边界应沿用现有半开区间语义，用 `Utility.compareLt(max(start), min(end))` 判断是否存在正长度 overlap，不建议直接裸用 `<=`，避免和当前数值容差语义不一致。

第四点“按 bucket 更细索引、避免挨个比”暂时不做。当前正确性更重要，且 main cost 更像 PWLF no-change 仍改写和 normalize，而不是 bucket 扫描本身。第五点统计可以顺手收敛：`cardinalitySkips += countLabelsInBuckets(...)` 每次 insert 都会扫 bucket，虽然不是主瓶颈，但这些统计只服务诊断。若要改，可加一个开关，只在 diagnostic 开启时计算；或者维护总数/前缀计数。不过这属于小优化，优先级低于三态和只读预扫描。

整体难度判断：定义域 overlap 快速跳过最容易；三态返回本身也不难，但需要兼容旧 boolean 调用；只读预扫描是主要工作，难度中等，因为要正确处理线段交点、零长度点段和 forward/backward 的定义域语义。建议实现顺序为：先加三态和 overlap，跑现有 PWLF + pricing smoke；再加只读预扫描，专门构造“无裁剪但 segment 边界不对齐”的回归测试，确保 no-change 后函数结构不变。
65. 2026-06-12 partial dominance 三态裁剪实现

本次按第 64 节的方案实现了热路径优化。`PiecewiseLinearFunction` 新增 `TrimResult { NO_CHANGE, PARTIAL, EMPTY }` 和 `updateDominatedIntervalsDetailed()`，原来的 boolean `updateDominatedIntervals()` 保留为兼容 wrapper，仍只在裁空时返回 `true`。详细方法在真正改写函数前先做只读扫描：如果公共定义域内不存在任何正长度 `g(t) <= this(t)` 区间，则直接返回 `NO_CHANGE`，不拆分 segment、不 normalize、不刷新最小值。这样可以避免大量“比较了但没有裁剪”的 partial dominance 调用污染 PWLF 结构。

调用侧也同步改为三态处理。`PartialListDominanceStore.trimFrontierBy()` 先做正长度定义域 overlap 快速跳过；只有 `PARTIAL/EMPTY` 才刷新 `minReducedCost`，其中 `PARTIAL` 才累计 `partialTrims`，`NO_CHANGE` 不再被误计为有效裁剪。`PaperDominanceGraph` 的 graph partial 路径同样切到三态接口，避免 no-change 时继续刷新 label。该修改不改变 dominance 语义，只减少无效 PWLF 改写和统计噪声。

验证方面，focused `javac` 通过；`PaperDominanceGraphConsistencyTest` 通过 `cases=200, insertions=16000`；`PiecewiseLinearFunctionPropertyTest` 新增的 “no-change does not rewrite frontier” 回归测试通过。该 property test 仍保留历史 `mergeMinimum` 无重叠定义域和随机 forward-closure 诊断失败，和本次三态裁剪优化无直接关系。15 任务 smoke 中，非 ng partial-list 返回 `ROOT_PROCESSED,obj=bound=3360,valid=true, exact=0.223s/call=1`；ng + partial-list 返回 `ROOT_PROCESSED,obj=bound=3360,valid=true, exact=0.265s/call=1`。当前结论是三态接口和 no-change 快速返回可用，后续再看 30/40 任务上是否能稳定降低 partial-list 的函数改写成本。

66. 2026-06-12 partial dominance 只读预扫描交点漏判修正

再次复查第 65 节实现时发现一个真实正确性风险：`hasDominatedInterval()` 在处理单个线性子区间内的交点时，原本只把 `cur` 推到交点继续扫描。这能发现“交点右侧被支配”的情况，但会漏掉“交点左侧被支配、右侧不被支配”的情况。例如 `this(t)=10-t`、`g(t)=5` 在 `[0,10]` 上相交于 `t=5`，`[0,5]` 是正长度被支配区间；旧预扫描会跳到 `t=5` 后继续看右侧，从而误报 `NO_CHANGE`。这类 false negative 会让本该执行的 partial trim 被跳过，因此不是单纯性能问题。

修正方式是：在只读预扫描发现内部交点时，如果交点两侧任一端点已经满足 `g(t) <= this(t)`，则直接返回存在被支配正长度区间。这样不需要在预扫描里真的拆分 segment，同时和原修改流程“遇到交点后回到子区间重算”的语义一致。新增 `testUpdateDominatedIntervalsDetectsLeftSideCrossing()` 覆盖上述左侧交点案例。

验证结果：focused `javac` 通过；`PiecewiseLinearFunctionPropertyTest` 中 no-change 与 crossing 两个新增测试均通过，原先 partial trim 随机 forward/directional 失败不再出现，整体从上一轮 `failed=13` 降为 `failed=3`，剩余失败均为历史 `mergeMinimum` 无重叠定义域诊断；`PaperDominanceGraphConsistencyTest` 通过 `cases=200, insertions=16000`。15 任务 smoke 中，非 ng partial-list 和 ng+partial-list 均返回 `ROOT_PROCESSED,obj=bound=3360,valid=true`。当前结论是三态裁剪的 no-change 快速返回已经修正到和原交点处理一致。

67. 2026-06-12 partial-list 与 normal 当前 HEAD root 对照

在交点漏判修正后，重新用三角化 30 任务 010/011 做 root-only 同口径对照。测试目录为 `test-results/bpc/tmp-triangle-20260611/`，配置保持 `maxNodes=1`、ALNS seed 开启、启发式 pricing 开启、`completionBound=allCycles`、RMIH 关闭、midpoint probe 关闭，不打开 subtree；normal 使用 `GCNGBBStyleBidirectionalPricing`，partial-list 使用 `GCNGBBStylePartialDominancePricing`，其余配置一致。

010 中，normal 为 `NODE_LIMIT,obj=16718,bound=16139.8,solve=26.530s,exact=6.494s/4,pricing=26,cols=5074,valid=true`；partial-list 为 `NODE_LIMIT,obj=16718,bound=16139.8,solve=25.519s,exact=5.932s/4,pricing=26,cols=4963,valid=true`。两者上界、根界和有效性一致，partial-list 总时间约快 `3.8%`，exact 时间约快 `8.7%`。

011 中，normal 为 `NODE_LIMIT,obj=13813,bound=13323.109589,solve=23.371s,exact=6.103s/5,pricing=29,cols=5657,valid=true`；partial-list 为 `NODE_LIMIT,obj=13813,bound=13323.109589,solve=21.199s,exact=4.671s/4,pricing=27,cols=5517,valid=true`。两者上界、根界和有效性一致，partial-list 总时间约快 `9.3%`，exact 时间约快 `23.5%`，并少了一轮 exact pricing 和两轮 pricing 调用。

当前结论是：在这两个三角化 30 任务 root 上，交点修复后的 partial-list 结果与 normal 一致，并且本轮都更快。不过这仍只是 root-only、两个算例的证据；此前 010 曾出现 partial-list 慢于 graph 的波动，说明 partial-list 是否稳定更快还要看节点、dual 路径和 terminal 下 label 数量。现阶段可以认为 partial-list 是可用的实验分支，但还不足以替换默认 paper graph。

68. 2026-06-12 ng-DSSR + probe + pricingOnly 完整求解 010/011

按“开启 ng，并把之前认为有帮助的策略都打开”的口径，重新求解三角化 30 任务 010/011 到闭合。测试目录为 `test-results/bpc/tmp-triangle-20260611/`，配置为 ALNS seed 开启、`completionBound=allCycles`、`midpointProbe=true`、ng-DSSR 开启、初始 ng-set 使用 `nearestK,size=8`、每轮 non-elementary route 更新上限为 10、completion-bound subtree 开启且采用 pricingOnly 固定弧，RMIH 上界启发式关闭。这样记录的是 BPC + ng-DSSR pricing 本身的闭合能力，不把 screened integer RMP 启发式耗时混进来。

010 结果为 `FINISHED,obj=16222,bound=16222,gap=0,nodes=7,pricing=190,cols=8608,pool=8608,solve=100.962s,root=25.696s,heuristic=39.614s/136,exact=48.236s/54,masterLP=5.685s,valid=true`。根节点 `lpObj=16139.8`，subtree 第一次固定 175 条 pricingOnly 弧；随后 node 3 找到整数解 16258，node 4 更新到 16222，node 7 闭合。该算例没有出现单个 ng-DSSR 节点长时间卡住，耗时主要分散在 heuristic pricing 与 exact pricing 多次调用上。

011 结果为 `FINISHED,obj=13511,bound=13511,gap=0,nodes=17,pricing=423,cols=10818,pool=10818,solve=150.125s,root=30.736s,heuristic=42.996s/297,exact=88.380s/126,masterLP=9.636s,valid=true`。根节点 `lpObj=13323.109589`，subtree 第一次固定 325 条 pricingOnly 弧；node 6 在 `73.881s` 找到整数解 13511，之后主要是在收敛剩余节点下界，node 17 闭合。011 的 exact ng-DSSR 总耗时明显高于 010，主要原因是搜索节点和 pricing 调用更多，而不是某一个节点直接求解不动。

当前结论是：在三角化 010/011 上，`ng-DSSR + allCycles completion bound + midpoint probe + pricingOnly subtree + ALNS seed` 可以闭合，且结果校验均为 `valid=true`。011 求到最优约 150 秒，010 约 101 秒。pricingOnly 固定弧没有破坏正确性；在这两个实例里，它配合 ng-DSSR 能稳定推进分支树，但 exact pricing 仍是主要耗时来源，011 尤其体现为更多 exact calls 和更多搜索节点。

69. 2026-06-12 ng-DSSR 三种 dominance 后端完整对照

上一节的 ng-DSSR 结果使用的是默认 `PAPER` 后端，也就是普通 paper dominance graph，不是 partial-list，也不是 graph-partial。本轮在相同求解配置下补测三种后端：`PAPER`、`GRAPH_PARTIAL` 和 `LIST_PARTIAL`。共同配置仍为 ALNS seed、`completionBound=allCycles`、midpoint probe、pricingOnly subtree、`nearestK,size=8`、non-elementary route top10 更新，RMIH 上界启发式关闭。

010 的结果差异很明显。`PAPER` 为 `obj=bound=16222,solve=100.962s,pricing=190,cols=8608,exact=48.236s/54,valid=true`；`LIST_PARTIAL` 也闭合到 `16222`，但为 `solve=108.621s,pricing=222,cols=8010,exact=44.155s/64,valid=true`，即 exact 时间略少但 pricing 调用和 heuristic 路径更重，总时间慢于默认 paper；`GRAPH_PARTIAL` 闭合到 `16224.125`，为 `solve=142.491s,pricing=244,cols=8343,exact=78.180s/72,valid=true`。由于同一算例同一模型下默认 paper 和 list-partial 都能得到 `16222`，graph-partial 的 `16224.125` 不能视为正确最优值，说明该后端当前存在漏列或不完备风险，不能作为可信完整求解后端。

011 中三者都闭合到 `13511`。`PAPER` 为 `solve=150.125s,pricing=423,cols=10818,exact=88.380s/126,valid=true`；`GRAPH_PARTIAL` 为 `solve=183.837s,pricing=426,cols=11046,exact=114.809s/133,valid=true`，明显慢于默认 paper；`LIST_PARTIAL` 为 `solve=100.547s,pricing=455,cols=11595,exact=53.408s/133,valid=true`，这次明显快于默认 paper。也就是说，list-partial 对 011 的 exact 扩展和列生成路径有正面作用，但在 010 上总时间没有收益。

当前结论是：默认 `PAPER` 后端仍是最稳的 ng-DSSR 完整求解后端；`LIST_PARTIAL` 是值得继续保留的实验后端，011 上加速明显，但 010 上略慢，不能简单替换默认；`GRAPH_PARTIAL` 当前不应继续用于完整求解结论，因为 010 已经出现和其他后端不一致的闭合目标。后续若要排查 graph-partial，应优先在 010 上定位它为什么漏掉能把 incumbent 从 `16224.125` 降到 `16222` 的列，而不是继续比较速度。

70. 2026-06-12 graph-partial 010 结果不一致的原因分析更正

`GRAPH_PARTIAL` 在 010 上得到 `16224.125/16224.125`，而默认 `PAPER` 和 `LIST_PARTIAL` 都得到 `16222/16222`。这个现象说明 graph-partial 不能直接作为可信完整求解后端，但现有日志还不能证明具体错误点。三组完整 BPC run 的列池和 dual 会在早期就分叉，因此 node 7 上各自“没有负列”的日志不是同一个 LP 状态，不能直接推出 graph-partial 在同一状态下漏列。

上一版把原因归结为 graph-partial 的 `labelEnvelope/dominanceEnvelope` 在 partial trim 后没有完整失效，这个结论过强。更准确地说，已有 label 被 predecessor 或同 key 新 label 部分裁剪时，被裁掉的区间本来就由裁剪方提供不更高的 frontier；只要裁剪方仍进入后续下包络，旧 labelEnvelope 中保留的低值片段不必然比真实可用下包络更激进，也不能单独推出误删。当前代码中 `trimLabelByEnvelope()` 已经使用三态结果刷新被裁 label 的 `minReducedCost`，但 `PaperDominanceGraph` 只在裁空时触发 node envelope 重建；这最多是需要继续核对的实现风险，不是已经由日志证明的根因。

当前可由日志确认的事实是：graph-partial 在 010 node 7 后段仍能连续生成负列，最后一次 exact pricing 才返回 `relaxed pricing found no negative route`；该最后一轮有 `partialTrim checks/partial/full=5966/3459/863`、`candidatePool kept=0`、`joinBest bestRC=1.0E8`。list-partial 在自己的 node 7 最后一轮也返回无负列，但它进入 node 7 时 incumbent、restricted columns 和前序列池已经不同，最终 incumbent 为 `16222`。因此现在只能说 graph-partial 的完整路径产生了更差闭合值，不能说 envelope 缓存已经导致同状态漏列。

后续正确的排查方式应改成同状态 cross-check：在 graph-partial 某次 exact pricing 返回空列时，保留同一个 `LP`、同一个 node、同一个 restricted column set 和同一组 dual，立即用 `PAPER` 和 `LIST_PARTIAL` 后端各跑一次 ng-DSSR pricing，且不把 cross-check 生成的列加入主问题。若 paper/list 在同状态找到负列，才说明 graph-partial dominance 或 join 确实漏列；再进一步 dump 这条 sequence，追踪它的 label 是否被 partial graph 裁掉。若 paper/list 同状态也找不到负列，则 `16224.125` 的差异更可能来自早期列生成路径分叉，需要把 cross-check 前移到第一次后端结果不同的 exact call。
71. 2026-06-12 node4 同状态 cross-check 对 graph-partial 问题的更新结论

在 `tmp-wet030_from040_010_2m` 上新增只用于诊断的同状态 cross-check：`GRAPH_PARTIAL` 正式返回后，不改变主问题列池，临时用同一个 LP 状态分别复跑 `PAPER` 和 `LIST_PARTIAL`。测试配置仍为三角化 010、ng-DSSR、`nearestK,size=8,top10`、`completionBound=allCycles`、`midpointProbe=true`、pricingOnly subtree、关闭 RMIH。诊断只限制在 node4，避免全树复跑过重。

这次得到的关键证据是：`GRAPH_PARTIAL` 不是唯一问题，`LIST_PARTIAL` 在同一个 node4 状态下也会漏掉 `PAPER` 找到的负列。node4 第一轮 exact 中，graph 返回 6 列，list-partial 也返回同样 6 列，而 paper 返回 15 列，其中 9 条不在 graph 返回集合中；代表缺失列 reduced cost 约为 `-24`、`-20`、`-19`。node4 后段更直接：graph 返回 0 列，list-partial 也返回 0 列，但 paper 在同一 LP 状态下仍找到 1 条负列，reduced cost 约为 `-0.125`，序列为 `[17, 27, 10, 15, 20, 16, 1, 13, 30, 3, 11, 26, 19, 21, 24, 6]`。

因此当前结论要修正为：问题不是 graph envelope 缓存单独导致的，也不是只发生在 `GRAPH_PARTIAL` 后端。更准确地说，当前 partial dominance 语义在 ng-DSSR 中还不能作为 exact pricing 的可信证明路径；它会在某些 LP 状态下比普通 `PAPER` 完整 dominance 少保留可形成负列的 label。`LIST_PARTIAL` 之前在完整 010 run 中也能得到 `16222`，只是因为列生成路径不同，恰好提前得到了足够好的列，并不能证明同状态下它没有漏列。

这也解释了为什么 graph-partial 最终闭合到 `16224.125`：它不是找到更好的模型解，而是在 partial 裁剪路径下缺少 paper 后端还能生成的负列，从而给出了不可信的闭合证明。后续除非重新证明并修正 partial dominance 的裁剪条件，否则 `GRAPH_PARTIAL` 和 `LIST_PARTIAL` 都只能作为实验或启发式加速分支，不能用于最终 exact bound 结论。默认完整求解仍应使用 `PAPER` 后端。

进一步做目标序列 trace 后，断点已经更具体。对最终 paper 独有列 `[17, 27, 10, 15, 20, 16, 1, 13, 30, 3, 11, 26, 19, 21, 24, 6]`，`PAPER` 后端在 node4 最后一轮能一路生成完整 forward 序列，depth 16 的 label 为 `min=-0.125`，随后作为 `COLUMN_CANDIDATE` 进入候选池。`GRAPH_PARTIAL` 和 `LIST_PARTIAL` 不是在 join 阶段丢掉它，也不是 top-K 候选池丢掉它，而是在 forward 前缀插入 dominance store 时已经被局部裁剪。以最后一轮为例，graph-partial 中目标前缀 `[17,27,10,15,20]` 在 `F_CONSTRUCT` 后为 `min=6845.4583, domain=[597,2185.5]`，随后立刻 `F_CB_PRUNED`；list-partial 中同一前缀也在 depth 5 被 `F_CB_PRUNED`。而 paper 同状态下 depth 5 前缀为 `min=6716.125, domain=[425,2185.5]` 并继续扩展到完整负列。

这说明当前问题的直接链条是：partial dominance 在插入早期目标前缀时裁掉了低完成时间/低 reduced-cost 的 frontier 区间，导致后续目标子路径的 completion-bound 检查变差并提前剪枝；`PAPER` 因为不做局部区间裁剪，保留了这段 frontier，最终生成负列。

为什么 ng-DSSR 下这种 partial 裁剪不安全，当前更准确的解释是：ng 的 `extensionSet` 不是 elementary 版本里的“未访问且资源可达集合”，而是“未被当前 ng-memory 记住且资源可达集合”。一个 label 可能已经真实访问过某些 job，但由于 ng-memory 遗忘，这些 job 仍会出现在 `extensionSet` 中。于是 partial dominance 可能用一个成本更低、`extensionSet` 不差的 relaxed prefix 裁掉目标 prefix 的某段 frontier；但这个 relaxed prefix 后续接上目标剩余 job 时可能形成非基本 route，不能替代被裁掉的 elementary target prefix。normal elementary 版本中 `reachableSet` 排除了全部 visited job，超集关系隐含“支配 label 至少没有访问目标后续所需 job”；ng-DSSR 里这个性质不成立。这才是 partial-list 和 graph-partial 同状态漏列的核心差异。
72. 2026-06-12 小规模 ng-DSSR partial sanity check

在继续排查 node4 同状态漏列问题前，先按“最多用 20 任务、小规模多试几个”的口径做了一轮 sanity check。第一次尝试把 heuristic pricing 关闭，只让 exact ng-DSSR 在 `wet015_001_2m` root 上补列，结果 paper 后端单 root 就用了约 `216s`，随后 list-partial 也长时间运行。这个配置不适合用来判断“小规模是否会出错”，因为它和历史快跑口径不同：没有 heuristic pricing 先补 restricted pool，exact pricing 被迫承担大量补列工作，容易把诊断变成纯枚举压力测试。

随后改回历史对照口径：`maxNodes=1`、heuristic pricing 打开、RMIH 关闭、ALNS 关闭、`ngDssr=true`、`nearestK,size=8`、`routeUpdateLimit=10`、`joinBestMode=bestUB`，只切换 ng-DSSR 的 terminal dominance 后端。`wet015_001_2m` 上，`PAPER/LIST_PARTIAL/GRAPH_PARTIAL` 均得到 `obj=bound=3360, valid=true`，exact 时间分别约 `0.174s/0.137s/0.158s`；`wet020_001_2m` 上，三者均得到 `obj=bound=6343, valid=true`，exact 时间分别约 `0.277s/0.217s/0.265s`，列数均为 `1882`。

进一步把 15 任务的 10 个算例全部按同一口径跑完。三种后端在 10 个 root 节点上全部 `valid=true`，每个算例的 incumbent、bound 和最终列数完全一致；10 个算例合计 exact 时间为：`PAPER=0.262s`、`LIST_PARTIAL=0.187s`、`GRAPH_PARTIAL=0.252s`，合计列数均为 `8379`。其中 `wet015_001_2m` 的日志显示 list-partial 和 graph-partial 都确实发生了 partial trim，但最终没有影响 root 结论。

因此当前小规模结论是：15/20 root 正常配置下没有复现 partial 后端漏列，且 list-partial 在这些小例子上略快；但这不能推翻 node4 同状态 cross-check 的结论。小规模 root 的 dual、DSSR 轮次和后续可替代路径都简单，partial 裁掉的区间没有导致最终负列缺失；而 30 任务 010 的 node4 已经证明，在特定 ng-memory 遗忘和 completion-bound 剪枝组合下，partial-list 与 graph-partial 都可能少于 paper 后端。因此 partial 后端目前仍只能作为实验/启发式后端，不能作为 exact bound 的可信默认后端。

73. 2026-06-12 list-partial 目标前缀裁剪 trace

继续沿用三角化 010 的 node4 同状态 cross-check 口径，本轮给 `LIST_PARTIAL` 增加默认关闭的裁剪 trace，只在指定目标序列 `[17, 27, 10, 15, 20, 16, 1, 13, 30, 3, 11, 26, 19, 21, 24, 6]` 的 prefix/suffix 被 partial trim 时输出裁剪者。运行目录为 `test-results/bpc/tmp-ngdssr-listpartial-trimtrace-node4-20260612`，正式 graph-partial 仍闭合到 `16224.125`，同状态 cross-check 中 `PAPER` 在 node4 后段仍能找到 reduced cost 约 `-0.125` 的目标列，而 `LIST_PARTIAL` 返回 0 列。

新的关键证据是：`LIST_PARTIAL` 中目标 prefix 从一开始就被若干 relaxed label 局部裁剪，其中多条裁剪者已经真实访问过目标后续 job，但该 job 不在当前 `ngMemorySet` 中。例如目标 prefix `[17]` 被 `[19,17]` 裁剪时记录为 `forgottenTargetJobs=[19]`；prefix `[17,27]` 被 `[19,27]`、`[4,19,27]`、`[4,19,28,27]` 等裁剪时也出现 `forgottenTargetJobs=[19]`；prefix `[17,27,10]` 和 `[17,27,10,15]` 也多次被包含 19 的 relaxed prefix 裁剪。也就是说，裁剪者在真实 route 语义下已经用过未来目标列还需要的 job 19，但 ng-memory 已经遗忘 19，所以它的 `extensionSet` 仍看起来不差。

这使之前的推断从“可能是 ng-memory 遗忘导致”变成了有日志证据的链条：`buildForwardExtensionSet()` 排除的是 `ngMemory.contains(job)`，不是 `visitedSet.contains(job)`；因此一个 non-elementary relaxed prefix 可以在 dominance key 上看起来拥有不小于目标 prefix 的可扩展集合，并用较低 frontier 裁掉目标 prefix 的低完成时间区间。但这个 relaxed prefix 后续若接上目标剩余序列会重复访问 19，不能替代 elementary target prefix。最终表现为 list-partial 中 `[17,27,10,15,20]` 的构造状态变成 `min=7006.125, domain=[597,2185.5]` 并被 `F_CB_PRUNED`；paper 同状态下同一 prefix 为 `min=6716.125, domain=[425,2185.5]`，可以继续扩展到完整负列。

当前结论因此更明确：ng-DSSR 下把 partial dominance 直接套在 `extensionSet` 语义上不安全，不是单纯 graph envelope 缓存问题，也不是 join 或候选池 top-K 丢列。`PAPER` 后端仍应作为 exact pricing 的可信默认；`LIST_PARTIAL` 和 `GRAPH_PARTIAL` 可以保留为实验/启发式加速分支，但不能用于最终下界闭合证明，除非后续把 partial trim 条件改成能保证“裁剪者的真实访问历史不会排除被裁剪 label 未来需要的 elementary job”的更强条件。

74. 2026-06-13 对 ng-DSSR partial 漏列原因的进一步修正

前一节中“relaxed prefix 后续若接上目标剩余序列会重复访问 19，不能替代 elementary target prefix”的说法仍然过强。更准确的判断是：理论上的 NG dominance 本身没有问题；如果 relaxed prefix 在 NG 状态下确实支配了目标 prefix，那么它可以在 relaxed pricing 中替代目标 prefix。但这种替代会带来一个 DSSR 层面的不变量要求：若替代后的完整 route 是 non-elementary 且 reduced cost 为负，它必须被实际生成出来并记录为 non-elementary negative route，从而触发 ng-set 更新。否则 partial 裁剪就会把 elementary 负列对应的 frontier 区间删掉，却没有留下能够更新 ng-set 的 cycle witness，后续 DSSR round 就可能错误地认为 relaxed pricing 已经没有负列。

旧 VRP 的 `GCNGBB` dominance 条件本质上是 `memory_dominator ⊆ memory_dominated ∪ unreachable_dominated`，并配合时间、容量和 reduced cost 不劣。当前 TWET 的 `extensionSet` 可以看成某种不可达并集的补集；如果其中的资源不可达确实是单调硬不可达，那么局部 NG dominance 条件并不必然错。因此本轮不再把“裁剪者真实访问过未来 job”单独当作错误证据。真正由 node4 trace 说明的问题是：目标列 `[17,27,10,15,20,16,1,13,30,3,11,26,19,21,24,6]` 的早期 prefix 多次被包含 19、但当前 ng-memory 已遗忘 19 的 relaxed label 局部裁剪；按 NG relaxation，这些 relaxed label 若继续接目标后缀，应当形成包含重复 19 的 non-elementary 负 route 或其更优替代，并被 DSSR 用于更新 ng-set。但实际 `LIST_PARTIAL/GRAPH_PARTIAL` 最后一轮返回 0 列，日志 reason 为 `relaxed pricing found no negative route`，而同状态 `PAPER` 仍找到 reduced cost 约 `-0.125` 的 elementary 负列。这才是当前 partial 后端不能作为 exact pricing 证明路径的核心矛盾。

因此当前更精确的结论是：问题不一定是 NG dominance 的数学条件本身错，而是 partial dominance 与 DSSR 更新机制之间缺少 witness 保证。完整 dominance 删除一个 label 时，替代 label 会作为一个完整状态继续扩展；partial dominance 删除的是 frontier 的若干时间区间，这些区间可能由多个 relaxed label 分段替代。如果这些替代分支没有最终 materialize 成负 route 并更新 ng-set，就会出现“被裁掉的 elementary 区间不在了，替代它的 non-elementary 证据也没进入 DSSR”的情况。后续若要把 partial 后端做成 exact，需要增加能够追踪/强制保留这类 non-elementary witness 的机制，或者只在能证明不会丢失 DSSR witness 的条件下允许 partial trim。在此之前，`PAPER` 仍是 ng-DSSR 的可信默认后端，partial 后端只用于实验对照或启发式加速。
75. 2026-06-13 对 partial 漏列原因的再次修正：关键在 join 可替代性

上一轮把问题表述为“partial trim 缺少 DSSR witness”，这个说法仍然不够准确。用户指出如果裁剪者 B 真的支配 A，那么 B 自身就携带 predecessor 路径，不存在“路径证据凭空丢失”。这一点是对的。重新按当前代码语义分析后，更准确的核心原因应改为：当前 ng-DSSR partial 后端使用的 `extensionSet` dominance key 把两类不可达原因混在了一起，而 bidirectional join 只对其中一类敏感。

在 `GCNGBBStyleBidirectionalNgDssr` 中，`extensionSet` 的构造排除了 `ngMemory.contains(job)`、half-domain 不可达和当前 frontier 下的直连时间不可达。于是两个 label 可能都无法下一步扩展到 job 19：一个是因为它已经真实访问过 19 但 ng-memory 当前遗忘或记住状态导致不可达，另一个是因为当前时间/资源下 19 不能作为下一跳。对“继续单向扩展”而言，这两者都表现为 19 不在 `extensionSet` 中；但对“和 backward suffix 做 join”而言，它们不是等价的。join 关心的是真实重复/NG-memory 冲突，而不是 19 此刻能不能作为下一跳。

这正好解释了 node4 trace：目标 elementary 列 `[17,27,10,15,20,16,1,13,30,3,11,26,19,21,24,6]` 的早期 prefix 被一些包含未来 job 19 的 relaxed prefix 裁剪。这些 relaxed prefix 在 `extensionSet` 上可能不差，frontier 也更低；但如果它们后续接上同一个包含 19 的 suffix，就不能作为目标 elementary prefix 的等价替代，至少需要进入 non-elementary route 更新流程。当前 partial trim 在 forward prefix 阶段已经删掉目标 prefix 的低时间区间，后续 completion bound 进一步剪掉目标分支；而裁剪者是否能在 join 语义下替代目标 prefix，并没有被 `extensionSet` 这个单一 key 严格保证。

因此当前更准确的结论是：问题不是“B 比 A 好却没有路径”，而是“B 在单向 extensionSet 意义下看起来不差，不代表 B 对所有 backward join suffix 也能替代 A”。`extensionSet` 把 ng-memory 不可达和资源/时间不可达合成一个补集，这对单向扩展可能足够，但对双向 join 的 exact dominance 不够。后续如果要让 ng-DSSR partial 后端变成可信 exact pricing，要么 dominance 条件显式保留足够的 ng-memory/真实访问历史关系以保证 join 可替代性，要么 partial trim 只能作为启发式加速，不能用于最终闭合证明。
76. 2026-06-13 目标前缀保护诊断

按“前面迭代保持原样，只让目标列相关前缀/后缀不参与 partial trim”的口径，给 `LIST_PARTIAL` 增加了默认关闭的 trace 保护开关：`twet.bpc.fullDomainCompare.ngDssrTraceProtectTarget=true`。测试仍使用三角化 010 的 node4，目标序列为 `[17,27,10,15,20,16,1,13,30,3,11,26,19,21,24,6]`，配置保持 `nearestK,size=8,top5`、`completionBound=allCycles`、`midpointProbe=true`、RMIH 关闭。

无保护版本中，目标前缀 `[17,27]` 在插入 dominance store 前被多个不同历史的 label 联合裁空：先被 `[27]` 和 `[19,27]` 做部分裁剪，最后被 `[2,27]` 裁成空域，随后 `F_INSERT_DOMINATED`。被跟踪的 dominator `[2,27]` 后续确实继续扩展，扩展到 1/2/3/.../30 的一批子 label；其中大多数被 completion bound 剪掉，少数进入队列，但该轮最终 `candidatePool kept/seen/dropped=0/0/0`，没有生成负列。

保护版本中，早期 partial trim 被跳过后，目标路径确实继续向后扩展：`[17] -> [17,27] -> [17,27,10] -> [17,27,10,15] -> [17,27,10,15,20] -> [17,27,10,15,20,16]` 都能够构造并插入，且每一步的下一个目标 job 均显示 `ext=true, ng=false, half=true, time=true, arcForbidden=false`。但是继续扩到 `[17,27,10,15,20,16,1]` 后，该前缀被 `F_CB_PRUNED`，没有生成完整目标列。因此这个目标列不是“只要禁止 partial trim 就一定恢复”的直接反例；保护只能证明 partial trim 确实提前杀掉了这条目标路径的一部分，不能单独证明完整目标列在当前保护路径下仍应返回。

同时重新跑了 ng-DSSR 的 `PAPER` 后端对照，确认必须使用 `twet.bpc.fullDomainCompare.ngDssr=true` 才是同一套 ng-DSSR 流程；若三个 ng 开关都关掉，会退回普通 elementary pricing，不能对照 partial-list。`PAPER` 后端在该 node4 路径下也没有稳定生成完整目标序列，后几轮甚至会在 `[17,27]` 处完整占优。因此当前结论应收敛为：这次保护实验没有直接找到“保护目标前缀即可恢复 paper 独有列”的充分证据，但它确认了 partial-list 会通过多个不同历史 label 的分段裁剪提前改变目标路径的可用 frontier；后续若继续定位 exactness 问题，应优先在同一 LP 状态下记录 paper 独有列的完整 prefix 轨迹和 partial 后端的裁剪轨迹，而不是只盯单条旧目标序列。

77. 2026-06-13 SRI partial-list dominance 改为旧 VRP 补偿口径

前一版 SRI 接入过于保守：`SriAwarePartialListDominanceStore` 按完整 SRI count signature 分桶，只有 `[0,1,0,2,...]` 和完全相同状态的 label 才互相比较。这样不会漏掉 subset-row penalty 风险，但会明显削弱 dominance。本次按旧 VRP 的 `UseSR` 思路改为跨 SRI 状态补偿式比较：普通 partial-list store 仍按 reachable superset 和函数区间裁剪工作；当支配方在某个 SRI 上计数为 1、被支配方计数为偶数，并且被支配方还能到达一个支配方没有访问过的 cut 内 job 时，说明支配方未来可能额外触发一次 SRI penalty。比较前临时把支配方 frontier 整体上移 `-dual`，再做 partial dominance。该平移只用于本次比较，不修改 label 自身保存的真实 frontier。

这等价于旧 VRP 中 `lb.m_reduced_cost - mu1 <= label.m_reduced_cost` 的函数版：`mu1` 是负的 SRI dual，`-mu1` 是支配方可能多承担的正 penalty。当前实现只在 `-dual > 0` 时补偿，因此不会把非 penalty 情形错误放宽。`DominanceStore.dominatesSinglePoint(...)` 暂时仍不做跨 SRI 状态补偿，因为该接口没有传入单点 label 的 `visitedSet/sriCounts`，无法可靠复现 `UseSR` 条件；单点之间继续在 `SinglePointStore` 中按相同 SRI key 比较。

关于 ng-relaxation 下 SRI dual 是否会重复加，本次也做了语义复核。扩展阶段 `applySriExtensionShift()` 先检查真实 `visitedSet`，同一个 job 的重复访问不会再次增加 SRI 计数；计数最多只记到 2，只有从 1 变成 2 时才加一次 `-dual`。正反向 join 阶段再按两个半路径的 count 做修正：两边都已经触发过时去掉重复 penalty，两边各有一个不同 cut 内 job 时补上一次 penalty。因此即使用 ng relaxed route，单个 subset-row 对一条完整 route 的 pricing 贡献仍是“是否覆盖至少两个不同 job”的 0/1 系数，不会因为重复访问同一个 job 而多次加 dual。completion bound 的 SRI-aware 加强本次没有改，仍维持上一版“正式 frontier 计入 SRI，completion bound 不加 SRI 状态维度”的弱 bound 口径。
78. 2026-06-13 SRI 系数与 ng-route 重复访问的语义澄清

重新核对后，需要把 SRI 的一般定义和当前 TWET 实现口径区分开。一般 subset-row inequality 来自主问题覆盖行的 Chvatal-Gomory 取整，因此某条列在 SRI 行里的系数应按该列在被选覆盖行上的主问题系数之和取整，例如三任务 SRI 常见形式为 `floor((a_i^r+a_j^r+a_k^r)/2)`。如果某个模型允许一条列对同一个覆盖行有系数大于 1，那么重复访问确实可能让 SRI 系数大于 1，pricing 中对应 cut dual 也应按这个系数计入。

但当前 TWET 主问题不是这个口径。覆盖行建模使用 `column.containsJob(job)`，SRI 行和分离也按 `containsJob` 判断三元组中有几个不同 job 被列覆盖，因此当前有效列的 SRI 系数是 distinct-job 口径：三任务 SRI 中覆盖至少两个不同 job 时系数为 1，否则为 0。ng-DSSR 中 non-elementary route 只用于松弛定价和更新 ng-set，不作为真实列加入主问题；真实进入 RMP 的 `TWETColumn` 仍按 `containsJob` 贡献 SRI 系数。因此当前代码里 `applySriExtensionShift()` 用真实 `visitedSet` 去重、只在第一个不同 cut 内 job 后又加入第二个不同 job 时触发一次 `-dual`，和当前 LP SRI 行是一致的。

后续如果改成“非基本 ng-route 也能作为列加入主问题”，或者把主问题覆盖行系数改成访问次数而不是是否覆盖，那么 SRI pricing 必须同步改为计数型系数：同一 cut 内累计访问次数从 1 到 2、3 到 4 等都要再次触发对应 dual，join 修正也要从 0/1 系数改成 `floor(total/2)` 的合并逻辑。当前没有这么做，不能把 relaxed ng-route 的重复访问次数混入现有 0/1 覆盖 master 的 SRI 系数里。
79. 2026-06-13 ng-route 下 SRI dual 计数口径的进一步澄清

进一步讨论后，需要把“当前实现是否错误”和“是否可以定义更强的 ng-walk SRI 口径”分开。ng-DSSR 的列生成过程确实在 relaxed subproblem 中搜索 ng-route，非基本 relaxed route 可能重复访问同一个 job。若把这个 relaxed route 当成一条 walk，并按访问次数定义 SRI 系数，那么三任务 SRI 的贡献应为 `floor(totalVisitsInScope/2)`，同一个 cut 内 job 重复 4 次会贡献 2 次 dual。这是一种可定义的 relaxed pricing 口径。

但当前代码和旧 VRP 迁移口径不是这个定义。旧 VRP 的 SRI 扩展在更新 `sr_count` 前会检查 `CheckVisit(label,i)`，即只在第一次访问某个 cut 内 customer 时更新计数；当前 TWET 版 `applySriExtensionShift()` 也同样使用真实 `visitedSet` 去重。这种口径把 ng-route 的重复访问看作松弛产生的循环伪影，SRI 成本只按 route 覆盖了 cut 中多少个不同 job 计算。它和当前 master 的 `containsJob` 0/1 覆盖行完全一致，并保证所有 elementary route 的 reduced cost 与 RMP 列系数一致。

从 DSSR correctness 角度看，关键条件是 relaxed pricing 的可行域包含所有 elementary route，且每条 elementary route 的 reduced cost 与主问题一致。非基本 ng-route 的 SRI 成本可以看作 relaxed subproblem 的人工延拓：按 distinct-job 计数会比 visit-count 更松，可能产生更多 non-elementary negative witness 和更多 ng-set 更新，但不会把 elementary 负列的 reduced cost 算错。若未来希望减少非基本负 route 或更贴近 walk 口径，可以改成 visit-count SRI，但那不是只删掉 `visitedSet` 判断这么简单：扩展要在累计计数 `1->2, 3->4, ...` 时重复加 `-dual`；label 需要保存不封顶的 count 或至少 floor/parity 信息；join 修正要按 `floor((countF+countB)/2)-floor(countF/2)-floor(countB/2)` 计算；dominance 补偿也要从旧 VRP 的 `count==1 && other even` 推广到奇偶/未来访问次数的补偿条件。

当前结论是：现有“只加一次”的实现不代表通用 SRI 定义只能加一次，而是选择了旧 VRP 和当前 0/1 master 一致的 distinct-job relaxed pricing 口径。这个口径偏松但语义可解释；如果后续实验发现 non-elementary SRI witness 过多或 DSSR 轮数受影响，可以单独实现并对照 visit-count 口径。
80. 2026-06-13 当前 distinct-job SRI 在 ng-relaxed pricing 中的正确性判断

继续澄清后，当前问题不应表述为“不同口径都可以”，而应判断现有 distinct-job SRI 是否能从当前 master 推导为正确的 ng-relaxed pricing。结论是：在当前 TWET 主问题中，覆盖行和 SRI 行的列系数均为 `containsJob` 的 0/1 覆盖系数，且 non-elementary ng-route 不作为列加入 RMP 的前提下，现有实现是可以推导成立的。

推导逻辑为：subset-row cut 是当前 RMP 上的 cut，真实列 `r` 的系数为 `floor(sum_{j in S} a_jr / 2)`。当前 `a_jr = 1` 当且仅当 `TWETColumn.containsJob(j)`，因此对真实 elementary column 来说，SRI 系数只取决于 cut 内不同 job 的覆盖数量，三任务 SRI 的系数最多为 1。pricing 必须保证所有真实 elementary column 的 reduced cost 与 RMP 中该列的系数一致；当前 `applySriExtensionShift()` 用真实 visitedSet 去重，只在第二个不同 cut 内 job 首次出现时加 `-dual`，正好满足这一点。

ng-DSSR 中的 non-elementary route 只是 relaxed subproblem 的状态和 DSSR witness，不是 RMP 变量。因此 relaxed ng-route 上的 SRI 成本只需要是一个对 elementary cost 一致的延拓。当前 distinct-job 延拓会把重复访问视为 ng relaxation 产生的循环，不额外增加 SRI penalty；这样会比 visit-count 延拓更松，可能产生更多 non-elementary negative route 和更多 ng-set 更新，但不会导致“无负 relaxed route”时漏掉 elementary 负列。原因是 elementary route 属于 relaxed route 集合，且其 reduced cost 在当前延拓下与 RMP 完全一致。

如果未来把 non-elementary ng-route 本身作为列加入主问题，或把主问题覆盖行改成访问次数系数，那么上述推导不再成立，SRI 必须改为 visit-count 口径。但这不是当前模型。当前模型下，visit-count SRI 是另一种更强的 relaxed-cost 延拓选择，不是正确性所必需；distinct-job SRI 是和当前 0/1 column coefficient 一致的、偏松但合法的延拓。
81. 2026-06-13 截断列系数的三行 SRI 是否 valid

本次重新从 cut validity 角度澄清：若主问题覆盖行是 set partitioning 等式 `sum_r a_ir x_r = 1`，变量为非负整数/0-1，且 `a_ir` 为非负整数，则即使某些列形式上存在 `a_ir > 1`，把 SRI 中的行系数先截断为 `b_ir = 1[a_ir > 0]` 再构造三行 SRI 仍然 valid。原因是任何整数可行解中，只要某个选中列对行 i 有 `a_ir > 0`，由于该行右端为 1 且所有系数非负，必然有 `a_ir = 1` 且没有其他选中列覆盖 i；若 `a_ir > 1`，该列根本不可能在整数可行解中被选中。因此在所有整数可行解的支持上，`b_ir` 与真实 `a_ir` 等价，标准 SRI `sum_r floor((b_1r+b_2r+b_3r)/2) x_r <= floor(3/2)=1` 有效。

但如果主问题是 set covering `sum_r a_ir x_r >= 1`，这个结论不成立。即便所有 `a_ir` 都是 0/1，三行 SRI 的 `<=1` 也一般不是 covering 可行解的 valid inequality。例如一个整数解选两条列，分别覆盖 `{1,2}` 和 `{2,3}`，它满足三行 covering 约束，但截断 SRI 左端为 2，会违反 `<=1`。因此 SRI 作为正式 cut 接入时必须明确基于等式覆盖/最终 exact-cover 语义；若仍在 `>=` RMP 上直接加，会有 validness 风险。

对 ng-DSSR pricing 的含义是：若采用等式覆盖语义下的截断 SRI，重复访问同一 job 的 relaxed ng-route 可以按 `b_ir=1[a_ir>0]` 的 distinct-job 系数延拓，这对真实整数列保持一致且偏松；如果要在访问次数系数的主问题中使用 SRI，则不能截断，必须按 `floor(totalVisitsInScope/2)` 修改扩展、join 和 dominance 补偿。
82. 2026-06-13 >= 过程 RMP 与 SRI validness 的关系

进一步澄清：当前覆盖约束用 `>=` 是列生成过程中的 set-covering RMP，并不等于最终目标问题允许重复服务。若在当前 TWET 假设下，任意重复服务的整数解都可以通过删点/替换为子序列列而不增成本，并且主问题列池或定价闭包能补出这些删点列，则目标整数最优解可以限制在 `==1` 的 exact-cover 解中。这个结论说的是“存在一个最优解满足 ==”，不是说 `>=` RMP 的每个整数可行解或 LP 最优解都天然满足 ==。

因此 SRI 的使用要分两层看。第一，作为原始 `>=` covering 多面体的 valid inequality，三行 SRI `<=1` 一般不 valid，反例是两条列分别覆盖 `{1,2}` 和 `{2,3}`。第二，如果算法目标明确是 exact-cover 整数可行域，而 `>=` 只是生成列和获得下界的过程松弛，那么只要加入的 SRI 对所有 exact-cover 整数解 valid，`covering + SRI` 仍然包含目标整数解的凸包，因而它的 LP 最优值仍是目标最优值的下界。也就是说，SRI 不必对所有被过程 RMP 放进来的重复覆盖整数解 valid，但必须对真正目标整数解 valid。

实际实现上仍要谨慎：若后续还用 `lastSolution.integer` 或 RMIH 把当前 `>=` RMP 的整数解直接当 incumbent，则需要先做去重修复/重解 `==` RMP，不能把违反 SRI 的重复覆盖解当作正式可行 incumbent。SRI active 后，root pi-window 等基于无 cut/覆盖松弛的窗口也应继续关闭或重新证明。当前结论是：`>=` 作为过程不阻止加入 exact-cover valid 的 SRI，但代码和日志必须明确 lower bound 的目标可行域是 `==` exact-cover，不是完整 set-covering 整数可行域。
83. 2026-06-13 保持旧 VRP distinct-visit SRI 口径

本轮最终决定保持旧 VRP 的 SRI 处理方式不变：在 ng-DSSR relaxed route 中，SRI 计数按 cut 内不同 job 的首次访问更新，而不是按 walk 中的重复访问次数累计。这样可以理解为在列系数可能大于 1 的理论 visit-count SRI 上做了弱化：例如一个 relaxed ng-route 中 cut 内三个 job 各重复访问两次，标准 visit-count 系数可能为 `floor(6/2)=3`，当前 distinct-visit 系数仍为 `floor(3/2)=1`。因此 relaxed pricing 中这个 cut 更弱，可能带来更多 non-elementary negative witness 和 DSSR 更新，但不会把 elementary 列的 reduced cost 算错。

保持该口径的主要原因是当前真正加入主问题的都是基本列，主问题中的覆盖系数和 SRI cut 系数均按 `containsJob` 的 0/1 覆盖语义计算。对这些真实列而言，distinct-visit SRI 与主问题 cut 行完全一致，不存在 cut 强度下降；弱化只发生在 ng-relaxation 的非基本 walk 估价上。若未来允许非基本 route 入主问题，或把主问题覆盖系数改成访问次数，则再单独实现 visit-count SRI。
84. 2026-06-13 single-point SRI dominance 是否需要继续增强

继续复核 SRI 接入后，single-point dominance 仍保持保守实现：SRI active 时，只允许相同 SRI count signature 的 single-point label 互相比较，不做旧 VRP `UseSR` 式跨状态补偿。这个不会导致误删，因为它只减少 dominance 机会；影响主要是 single-point shortcut 偏弱。

理论上可以优化。当前 `GCNGBBStyleBidirectionalNgDssr` 的 `SinglePointStore` 保存的是完整 `FunctionLabel`，因此在 `isDominatedBySinglePointStore()` 和 `removeSinglePointsDominatedBy()` 中可以拿到双方的 `visitedSet/reachableSet/sriCounts`，按 `SriAwarePartialListDominanceStore` 的同一补偿逻辑比较标量：如果支配方某个 SRI count 为 1、被支配方为偶数，且被支配方还能到达一个支配方未访问的 cut 内 job，则比较前把支配方的 single-point value 加上 `-dual`。这样可以去掉“必须 sameSriState”的限制。

当前暂不做，原因是 single-point 只是半域交界处的 shortcut，不是主要 dominance store；保守同状态比较已经正确，且跨状态补偿要把同一套 SRI compensation 抽成公共 helper，避免 partial-list store 和 single-point 各自复制一份逻辑。若后续 SRI active 后 label 数明显增加，再把该优化作为局部性能项处理即可。
85. 2026-06-13 single-point SRI dominance 补偿接入

按“改动不大就做”的要求，本次把 single-point dominance 也改为旧 VRP 式 SRI 补偿比较。实现上没有复制第二套 SRI 逻辑，而是把 `SriAwarePartialListDominanceStore` 中的 `sriDominanceCompensation()` 改为包内静态 helper；普通 partial-list frontier 裁剪和 single-point 标量比较都复用这一套补偿条件。

具体变化为：`isDominatedBySinglePointStore()` 和 `removeSinglePointsDominatedBy()` 不再要求两个 single-point label 的 `sriStateKey` 完全相同。只要 reachable superset 条件成立，就计算支配方相对被支配方的 SRI compensation；若 `dominator.minReducedCost + compensation <= dominated.minReducedCost`，则允许支配。补偿条件仍是旧 VRP `UseSR` 口径：支配方某个 SRI count 为 1，被支配方为偶数，并且被支配方还能到达一个支配方未访问过的 cut 内 job。

`bestByDominanceKey` 的 O(1) shortcut 在 SRI active 时仍关闭，因为该 map 每个 reachable key 只保存一个 label，跨 SRI 状态需要按被比较对象动态计算 compensation，不能安全压成一个全局 best。该修改只增强 live bucket 扫描中的 single-point dominance，语义上与 partial-list SRI 补偿保持一致。
86. 2026-06-13 旧 VRP completion bound 与 SRI 的关系解释

旧 VRP 的 `m_fw_bound/m_bw_bound` 不是带完整 SRI 状态的 bound 表，也不是完全不考虑 SRI。它采用的是“构造 bound 时去掉单侧已经触发的 SRI penalty，实际拼接时再按两侧 SRI count 重新合并”的口径。旧 label 的 `m_reduced_cost` 在扩展过程中已经在 `sr_count` 从 1 到 2 时扣过一次 `sr_mu`；由于 `sr_mu < 0`，这相当于给 reduced cost 加了一个正 penalty `-sr_mu`。构造 bound 表时，如果某个 label 的 `sr_count > 1`，旧代码把 `lp.sr_mu` 加回去，即去掉这条半路径自己已经付过的 SRI penalty。因此 bound 表更接近“不含已触发 SRI penalty 的基础半路径成本”。

这样做的目的，是避免 completion bound 表需要保存每个 SRI 的 count 状态。bound 表只存 terminal/time 下最便宜的基础半路径；当 forward/backward 真正拼接成完整 route 时，再用两边 label 的真实 `sr_count` 做 SRI 合并修正：两边都已经触发同一 SRI，则完整 route 只能触发一次，需要把重复付的一次去掉；两边各有一个不同 cut 内 job，则单边都没触发，但完整 route 触发一次，需要补一次 penalty。也就是说，旧 bound 计算本身只以“去 SRI penalty 后的基础成本”进入表；SRI 不是作为状态维度进入 bound，而是在真正组合两侧 label 时按 count 重新结算。

当前 TWET completion bound 暂未做这套 SRI-aware 去罚/重组逻辑。正式 label frontier 已经计入 SRI penalty，join 时也做了左右半路径合并修正；但 completion bound 用的补全函数不带 SRI count 状态，也没有把 suffix/prefix 的已触发 SRI penalty去掉后再按当前 label 状态重组。因此它应理解为不懂 SRI 状态的松弛 bound。由于 SRI penalty 是非负成本，忽略未来 SRI penalty 会让补全下界偏低，通常只会少剪，不会因为高估补全成本而误剪负列；但它也会比旧 VRP 的处理弱一些。
87. 2026-06-13 更正旧 VRP completion bound 与 SRI 的代码口径

重新核对旧 `BPC/GC/GCNGBB.java` 后，前一节把不同 bound 实现混在一起了，需要更正。`GCNGBB` 的初始 bound 不是简单的 label bound，而是先由 `BoundFTExtend/BoundBTExtend/BoundFCExtend/BoundBCExtend` 建二维 time/capacity bound，并用 `m_sec_bound` 和 `m_bd_fid` 保留 second best 来避免 2-cycle；这部分确实是 2-cycle-free bound。

但 `GCNGBB` 后续每轮 `FWExtend/BWExtend` 后还会调用 `UpdateFWBound/UpdateBWBound`，用当前已生成 labels 的 `m_nosr_redcost` 和 `m_reduced_cost` 去收紧 `m_ft_bound/m_bt_bound/m_fc_bound/m_bc_bound` 以及 `m_ftsr_bound/m_btsr_bound/m_fcsr_bound/m_bcsr_bound`。因此“bound 和 label 有关系”说的是这一步动态 tighten，而不是初始 2-cycle-free bound 的构造来源。SRI 相关地，扩展时先用不含 SRI 的 bound 检查 `lbcost + m_bt_bound`，再用含 SRI 的 tightened bound 检查 `lbcost_nosr + m_btsr_bound`；join 处仍按两边 `sr_count` 做重复触发/合并触发修正。

当前 TWET 的 `CompletionBoundCalculator` 与旧 `GCNGBB` 这套并不等价：它目前主要是基于 penalty 函数的 completion bound，没有旧 VRP 那种每轮用 labels 回写 tighten 的 `UpdateFWBound/UpdateBWBound` 机制，也没有为 SRI 单独维护 `m_*sr_bound`。所以后续讨论时要区分三件事：初始 2-cycle-free bound、用 labels 动态收紧 bound、SRI-aware 的含/不含 SRI 双 bound 表。

88. 2026-06-13 GCNGBB 中基础 bound 与 SRI bound 的具体用法

继续核对旧 `GCNGBB.java` 后，明确区分两套东西。前面误提的 `m_fw_bound/m_bw_bound` 名字主要出现在旧 `GCNGB.java`、`GCNGBB_C.java` 等变体；当前对照的 `GCNGBB.java` 使用的是 `m_ft_bound/m_bt_bound`、`m_fc_bound/m_bc_bound` 以及对应的 `m_ftsr_bound/m_btsr_bound`、`m_fcsr_bound/m_bcsr_bound`。

`GCNGBB.java` 中基础 bound 的初始计算由 `BoundFTExtend/BoundBTExtend/BoundFCExtend/BoundBCExtend` 完成。以 time 维度为例，`m_ft_bound[cid][t]` 表示从 depot 正向到达 `cid`、消耗时间状态为 `t` 的松弛最小 reduced cost；转移成本为 `distance - arc_mu - mu`。若下一点正好等于上一状态记录的 best predecessor，则使用 `m_sec_bound` 代替 best bound，避免形成 2-cycle；否则使用 best bound。`m_bt_bound` 是反向从 sink 出发的同类表。capacity 维度的 `m_fc_bound/m_bc_bound` 同理，只是状态从 time 换成 capacity。

SRI bound 初始时只是基础 bound 的拷贝：`m_ftsr_bound = m_ft_bound`、`m_btsr_bound = m_bt_bound` 等。真正区别来自每轮 label 扩展后的 `UpdateFWBound/UpdateBWBound`。更新时，基础表用当前 label 的 `m_nosr_redcost` tighten；SRI 表用当前 label 的 `m_reduced_cost` tighten。之后扩展新 label 时会先用基础 bound 检查 `lbcost + oppositeBaseBound + mu`，再用 SRI bound 检查 `lbcost_nosr + oppositeSriBound + mu`。因此 SRI 表不是完整 SRI 状态 DP，而是“由含 SRI label 成本收紧过的补全 bound”。

89. 2026-06-13 SRI active 时 completion-bound 剪枝改用 no-SRI label cost

按当前决定，TWET 暂不实现旧 `GCNGBB` 中每轮用 label 回写更新的 `m_*sr_bound`。因此 SRI active 时，completion-bound 剪枝不能使用已经计入 SRI penalty 的 `frontier/minReducedCost` 去和当前 all-cycle completion bound 相加，否则相当于把“没有 SRI 状态维度的 bound”与“含 SRI 的当前半路径”混在一起，剪枝口径会变得不清楚。

本次实现保持正式 label reduced cost、dominance、join 和候选列过滤仍使用含 SRI 的 `frontier`；只在 completion-bound 剪枝中切换为 `noSriFrontier`。同时给 `FunctionLabel` 缓存 `noSriMinReducedCost`，scalar completion-bound 预筛也使用 no-SRI min 值，避免 scalar 分支仍按含 SRI 成本提前剪枝。这样当前 completion bound 与无 SRI 时的 all-cycle bound 口径一致：它只提供不含 SRI penalty 的松弛补全下界，可能偏弱，但不会因为 SRI 状态缺失而做更激进的 SR-bound 剪枝。

验证：排除历史 `src/BPC` 包后，对当前 `src` 下 TWETBPC/Basic/Common/HEU/Output 相关 128 个 Java 文件执行 focused `javac -encoding UTF-8 -cp cplex.jar`，编译通过，仅有历史 deprecation warning。

90. 2026-06-15 40 任务 normal ng-DSSR nearestK 组件全开浅层测试

本轮先复查当前 normal ng-DSSR 相关计算路径，没有发现新的明显计算错误。`pricingOnly` 禁弧已经统一进入普通/旧 exact pricing、ng-DSSR、启发式 pricing 和 completion bound DP 构图；`ngDssrInitialNgSetMode` 当前支持 `empty/full/dualPair/reducedCostPair/nearestK`，本轮使用 `nearestK`；局部 `completionBoundArcFixing` 已跳过 pricingOnly 禁弧，避免重复扫描。focused `javac` 通过，仅有历史 deprecation warning。

随后用 `data/40-2/wet040_001_2m.dat` 做 normal ng-DSSR nearestK 浅层测试。配置为：ALNS seed 开启，RMIH 开启且 time limit 为 `4s`，`completionBound=allCycles`，局部 `completionBoundArcFixing=true`，`completionBoundSubtreeArcEliminationPricingOnly=true`，`midpointProbe=true`，同 node probe 复用开启，`joinBestMode=best_ub`，`ngDssrInitialMode=nearestK`，`ngDssrInitialSize=8`，`ngDssrRouteUpdateLimit=10`，关闭 undirected adjacency branching。

root-only 运行结果为 `NODE_LIMIT`，`incumbent=22582`，`bound=22490`，gap `0.4074%`，总时间 `135.981s`，exact pricing `62.249s/11 calls`，heuristic pricing `45.608s/47 calls`，RMIH 找到可行上界并改进到 `22582`，subtree/pricingOnly 扫 `1560` 条候选、固定 `1186` 条 arc，validator 为 `true`。

`maxNodes=2` 复跑结果为 `NODE_LIMIT`，上下界仍为 `22582/22490`，总时间 `129.126s`，exact pricing `64.247s/15 calls`，validator 为 `true`。root 是主要耗时，node2 只用 `9.749s`，其中 pricing `7.310s`，exact `5.046s/4 calls`，子节点继续固定 `79` 条 pricingOnly arc。当前结果说明这套配置在 40 任务上能够正常推进，根节点能把 gap 压到约 `0.4%`，但 root pricing 仍是大头；后续若要完整闭合 40 任务，需要继续看后续节点是否能靠 branching/RMIH 收敛，而不是只优化 root。

补跑 `maxNodes=50`，其余配置不变，外层 15 分钟硬限时内正常结束。结果为 `NODE_LIMIT`，`incumbent=22582`，`bound=22561.2`，gap `0.0921%`，总时间 `468.030s`，root 时间约 `129.013s`，exact pricing `247.927s/416 calls`，heuristic pricing `121.479s/1181 calls`，LP `27.650s`，总列池 `68841`，validator 为 `true`。节点 1 仍是最大单点耗时，后续节点通常为数秒到十余秒，node50 后队列仍有 `27` 个节点。该结果说明当前 normal ng-DSSR nearestK 组件全开后，在 40 任务上能够持续收紧下界，从 root gap `0.4074%` 降到 `0.0921%`，但小 gap 阶段需要继续处理较多 arc-branch 节点，完整闭合预计仍要明显超过 8 分钟。

91. 2026-06-15 40 任务 root 耗时原因拆解

`wet040_001_2m` root 慢的主因不是 master LP 或 RMIH，而是根节点列生成从很稀的初始池开始，需要补出大量负 reduced-cost 列。root 初始只有 `100` 条 seed columns，第一批 heuristic pricing 连续 16 轮就把 pool 从 `100` 扩到 `8894`，累计新增 `8794` 条；整个 root heuristic pricing 共 `47` 次、加 `9057` 条、耗时约 `45.6s`。这说明 root 的 LP dual 下存在大量明显 profitable 的局部列，启发式需要多轮把这些列灌进 RMP。

exact pricing 的耗时主要来自 completion bound 的反复构造和最终证明，而不是 join 本身失控。root exact pricing 共 `11` 次、加 `361` 条、耗时约 `62.2s`。早期 exact 调用中 completion bound buildMs 多在 `6.1s~6.9s`，`completionBoundInternal merge` 约 `5.7万~6.2万` 次；join pairs 虽有上万，但大量被 `joinBest` 和函数剪枝压掉。由于每轮 heuristic/exact 加列后 LP dual 都会变，root 的 completion bound 不能简单复用旧 dual 下的函数，因此这些构造成本会重复出现。后期 bound 可复用或 exact 快很多，但还需要最后一次 generated=0 的证明。

root 还没有分支禁弧或 pricingOnly 禁弧约束，早期 `nodeDiag forbiddenJobArcs/pricingOnlyJobArcs=0/0`，pricing 图基本是完整 40-job 图；只有 root 处理结束后 subtree 才固定 `1186/1560` 条 pricingOnly arc，供子节点使用。因此子节点图明显更小，node2 之后每个节点通常只需数秒到十余秒。root 后处理中的 RMIH 约 `2.3s`、subtree arc elimination 约 `6.9s`、LP 约 `2.5s`，都不是根节点 `~120s` 的主因。

92. 2026-06-15 heuristic pricing 上限对 40 任务 root 的影响

本轮专门对比了启发式定价上限。此前 `GCBBFullDomainComparisonTest` 默认把 `maxHeuristicColumns` 和 `heuristicPoolSize` 都设为 `100000`，因此 root 早期会把 heuristic 找到的大量负列一次性加入。该口径 root-only 为 `135.981s`，heuristic pricing `45.608s/47 calls/add9057`，exact pricing `62.249s/11 calls/add361`，RMIH 将 incumbent 改到 `22582`。

将同一配置改为 `maxHeuristicColumns=150, heuristicPoolSize=1000` 后，root-only 运行 `303.242s` 才结束，仍是 `NODE_LIMIT`，heuristic pricing `90.288s/80 calls/add6029`，exact pricing `173.964s/18 calls/add246`，incumbent 只到 `22584`。这说明 40 任务 root 上限列并不会让启发式更省，反而因为负列被分批加入，导致更多 LP/pricing 轮和更多 exact 证明成本。当前结论是：对 root 这种完整图、负列极多的节点，大上限 heuristic pricing 是有价值的；若要调小上限，更适合在子节点或低收益阶段做自适应，而不是统一恢复到默认 `150/1000`。

93. 2026-06-15 将 full-domain comparison 默认启发式上限改为 `1500/5000` 并完整闭合 40 任务

在前一轮 `150/1000` 过小、`100000/100000` 又过大的对照基础上，本轮先把 `GCBBFullDomainComparisonTest` 的实验入口默认值改为 `maxHeuristicColumns=1500, heuristicPoolSize=5000`，随后将 `TWETBPCConfig` 的全局默认也同步改为 `1500/5000`。原因是列多一点主要增加 RMP 规模，当前观察并不构成主要瓶颈；列少反而会把负列分批加入，增加 LP/pricing 轮数和实验波动。

用同一套 normal ng-DSSR nearestK 配置重新求解 `data/40-2/wet040_001_2m.dat` 到收敛。配置仍为 ALNS seed、RMIH 4s、`completionBound=allCycles`、`completionBoundArcFixing=true`、`completionBoundSubtreeArcEliminationPricingOnly=true`、`midpointProbe=true`、同 node probe 复用、`joinBestMode=best_ub`、`ngDssrInitialMode=nearestK`、`ngDssrInitialSize=8`、`ngDssrRouteUpdateLimit=10`，并关闭无向 adjacency branching。结果为 `FINISHED`，`incumbent=bound=22580`，总时间 `813.249s`，处理 `149` 个节点，pricing 调用 `4070` 次，总加列 `211279`，最终列池 `211279`，validator 为 `true`。

阶段表现上，root 为 `116.061s`，heuristic pricing `34.557s/47 calls/add9057`，exact pricing `62.565s/11 calls/add361`，RMIH 在 root 找到 `22582`；后续在 node 50 附近仍为 `22582/22561.2/gap≈0.0921%`，随后继续推进并在中段把 incumbent 改进到 `22580`，最终 node149 将 bound 抬到 `22580` 闭合。与 `100000/100000` 的 `maxNodes=50` 对照相比，`1500/5000` 在前 50 个节点没有明显削弱列生成，root 仍加到同样的 `9057` 条 heuristic 负列，且 root heuristic 时间更低；与 `150/1000` 相比则明显避免了 root 中负列分批过细导致的 LP/pricing 轮数膨胀。

当前结论是：`1500/5000` 比 `150/1000` 稳定得多，又比 `100000/100000` 更合理，因此已提升为当前全局默认启发式 pricing 上限。后续若在更大规模上发现 RMP 规模成为主瓶颈，再考虑按节点深度、root/child 或收益阶段做自适应收缩，而不是恢复统一小上限。

94. 2026-06-15 40 任务 partial-list ng-DSSR + full-SRI 900s 限时测试

按同一 40 任务 `wet040_001_2m` 和上一节的 normal ng-DSSR nearestK 配置，只把 exact pricing 后端改为 partial-list ng-DSSR，并打开 classical full-SRI cut。具体差异为 `ngDssr=false, ngDssrPartialDominance=true, enableSubsetRowCutsForPartialDominance=true, subsetRowCutMemoryMode=full`，其余仍保留 ALNS seed、RMIH 4s、`completionBound=allCycles`、pricingOnly subtree、midpoint probe/reuse、`joinBestMode=best_ub`、nearestK8/top10、关闭无向 adjacency branching。外层限时 900s。

本次运行先后因 PowerShell 参数拆分问题失败两次，正式运行编号为 `tmp-wet040-001-ngpartial-fullsri-900-20260615c`。正式运行启动后进入 root 内部，900s 截止时 Java 进程 CPU 约 `927s`、内存约 `1.16GB`，但 stdout/stderr 仍为 0 字节，没有输出 root node summary，也没有生成 CSV。因此该口径下截至 900s 没有可读取的 incumbent/bound 行；只能判断为 root cut-pricing closure 尚未完成。

与无 SRI 的 `1500/5000` 完整闭合结果相比，这个差异非常大：无 SRI 整棵树 `813.249s` 已闭合到 `22580`，而 full-SRI partial-list 900s 内尚未完成 root。当前结论是，40 任务上 classical full-SRI 直接全开过重，瓶颈发生在 root 内部的 cut/pricing closure，而不是后续分支树。若后续还要比较 SRI 对 bound 的贡献，应优先启用更细的 stage heartbeat 或改成受控 cut 策略，例如限制 root cut 轮数、使用 lm-SRI、只做 root 少轮 cut，或者先输出 root 内部 incumbent/relaxation 诊断；否则 900s 截止时无法得到用户关心的 bound/incumbent。

95. 2026-06-15 40 任务 partial-list ng-DSSR + lm-SRI 900s 限时测试

继续沿用第 94 节配置，只把 `subsetRowCutMemoryMode` 从 `full` 改为 `nodeMemory`，即使用 lm-SRI。运行编号为 `tmp-wet040-001-ngpartial-lmsri-900-20260615`，外层仍按 900s 左右手动截止。

lm-SRI 明显好于 full-SRI：root 在 `451.511s` 输出 summary，而 full-SRI 900s 内没有完成 root。root 结果为 `lpObj=22525.168360`，`incumbent=22584`，cutPool `80`，pricing `432.296s/225 calls/add10594`，其中 heuristic `63.353s/148 calls/add9997`，exact `368.943s/77 calls/add597`。root 后继续处理 node2，node2 用 `361.049s`，其 LP 值为 `22569.758621`，但全局 bound 仍是 root 的 `22525.168360`，incumbent 仍为 `22584`，gap `0.2605%`，队列为 `3`，cutPool 增至 `157`。900s 截止时进程仍在跑，未生成 CSV，最后可读状态来自 node2 summary。

与 no-SRI 对照相比，lm-SRI 仍然不划算：no-SRI 同配置整棵树 `813.249s` 已闭合到 `22580`，而 lm-SRI 到约 900s 只完成两个节点，当前上界 `22584`、下界 `22525.168360`。因此 lm-SRI 在 40 任务上虽然比 classical full-SRI 可运行得多，但仍显著拖慢 root 和浅层节点。当前判断是：SRI 类 cut 在该实例上确实能改变 root LP，但 cut-pricing closure 成本太高，短时限内不如 no-SRI 主线；若继续研究，应优先限制 cut 轮数或只把 lm-SRI 作为 root 少轮 bound 增强诊断，而不是默认全开。
96. 2026-06-15 40 任务 partial-list ng-DSSR + arc-memory SRI 900s 限时测试

继续沿用第 94、95 节的 40 任务 `wet040_001_2m` 配置：ALNS seed、RMIH 4s、`completionBound=allCycles`、pricingOnly subtree、midpoint probe/reuse、`joinBestMode=best_ub`、`ngDssrInitialMode=nearestK`、`ngDssrInitialSize=8`、`ngDssrRouteUpdateLimit=10`，关闭无向 adjacency branching；exact pricing 使用 partial-list ng-DSSR，SRI cut 打开。本轮只把 `subsetRowCutMemoryMode` 改为 `arcMemory`，运行名为 `tmp-wet040-001-ngpartial-arcmemory-900-20260615`，外层限时约 900s。

本轮在 900s 截止前完成了 root，并已经进入 node2。root summary 为：`nodeTime=900.567s`，`lpObj=22518.580357`，`incumbent=22582`，`bound=22518.580357`，gap `0.2808%`，队列 `2`，pool `10704`，active cutPool `80`，restricted `10702`。root pricing 总耗时 `885.215s/286 calls/add10602`，其中 heuristic pricing `248.284s/197 calls/add10030`，exact pricing `636.931s/89 calls/add572`，RMIH `1.416s/1`，LP `12.564s/197`。subtree/pricingOnly 在 root 后固定 `1009/1560` 条候选弧，validator 对 incumbent 返回 feasible。

从过程看，arc-memory SRI 能正常分离和定价：root cut 每轮仍按 10 条加入，依次到 `cuts=10,20,...,80`；多轮 exact pricing 中 completion bound 仍正常剪枝，典型最后一轮统计为 `fw kept/dominated=1720/2831`、`bw kept/dominated=1646/2266`、`fwPruned/bwPruned=37564/34690`。但 cut-pricing closure 仍很重，后期反复出现 `generated=0` 的证明轮和偶发少量负列，甚至在 `cuts=80` 后仍有一轮 `generated=32,bestRC≈-11.25`，说明新增 cut 后的 dual 会持续打开新的负列方向。

与前两种 SRI 口径相比，arc-memory 这次没有达到“比 nodeMemory 明显更轻”的预期。full-SRI 在同配置下 900s 内 root 未完成且无可读 summary；nodeMemory/lm-SRI 能在 `451.511s` 完成 root，900s 内推进到 node2，得到全局 `bound=22525.168360,incumbent=22584,gap=0.2605%`；arcMemory 虽能完成 root，但 root 用时约 `900.6s`，且截至 root 的下界 `22518.580357` 低于 nodeMemory 记录。当前判断是：arc-memory 的 cut 系数更细并不自动转化为更快的 root closure，本例下它仍引入较多 cut/pricing 尾部小负列，短时限内不如 no-SRI 主线，也不如 nodeMemory/lm-SRI 的这次表现。后续若继续研究 arc-memory，应优先看每轮 cut 的 memory arc 数、cut violation 保留强度和 cut 后负列数量，而不是只看 memory 更细这一点。

97. 2026-06-16 40-2 算例 setup time 清零对 normal ng-DSSR 的影响

按“还是那个 40-2，把 setup 全部设为 0”的要求，本次从 `data/40-2/wet040_001_2m.dat` 生成临时输入 `test-results/bpc/tmp-wet040-001-zero-setup-input-20260616/wet040_001_2m_zeroSetup.dat`，只把 `SETUP` 块的 41 行 setup time 全部改为 0；该原始文件没有额外 `SETUP_COST` 块，因此目标中的 setup cost 本来就是 0。本次没有改机器数，仍为 2 台机器。

求解配置保持第 93 节 normal ng-DSSR nearestK 主线：ALNS seed、RMIH 4s、`completionBound=allCycles`、pricingOnly subtree、midpoint probe/reuse、`joinBestMode=bestUB`、`ngDssrInitialMode=nearestK`、`ngDssrInitialSize=8`、`ngDssrRouteUpdateLimit=10`，关闭无向 adjacency branching，不使用 partial dominance 和 SRI。结果为 `FINISHED`，`incumbent=bound=17881`，总时间 `474.103s`，处理 `12` 个节点，pricing 调用 `338` 次，总加列 `129607`，最终列池 `129607`，validator 为 `true`。其中 root 为 `279.186s`，node1 summary 中 pricing `253.596s/42 calls/add9705`，heuristic `39.142s/33/add9471`，exact `214.453s/9/add234`，subtree `39.875s`；root completion bound 首次构造约 `29.222s`，内部 merge `59780` 次、changed `29068` 次。

与原始 setup 的同配置完整结果 `FINISHED, incumbent=bound=22580, solve=813.249s, nodes=149, exact=416.917s/1071, heuristic=190.739s/2987, pool=211279` 相比，setup time 清零后总体确实更快，节点数也从 `149` 降到 `12`。但它不是 root 维度的单调加速：zero-setup root `279.186s` 明显慢于原始 root `116.061s`，主要因为 root exact pricing 和 subtree/completion-bound 函数合并更重；真正节省来自后续分支树大幅变浅、总 exact/heuristic 调用数和列池规模下降。因此当前结论是：setup time 清零会让本例整体更容易闭合，但也会改变 completion-bound 函数形态，root 阶段反而可能更慢。

补充分析 root 变慢原因：zero setup 的 root 慢点几乎全部来自 all-cycles completion bound 的 PWLF merge，而不是 label 扩展或 join 爆炸。root 阶段 exact pricing 9 次、耗时 `214.453s`，其中 completion-bound build 累计 `215.633s`；原始 setup root exact pricing 11 次、耗时 `62.565s`，completion-bound build 累计仅 `42.081s`。zero setup 的前 5 次 buildMs 分别为 `21930/23494/15262/23809/19781 ms`，原始 setup 为 `4964/6991/7548/5865/6124 ms`。内部计数上，zero setup 的 merge/changed 为 `536746/268023`，原始 setup 为 `414076/194763`，次数只增加约三成，但时间增加约五倍，说明主要是单次 merge 的 PWLF 片段复杂度变重。方向上也很集中：zero setup 的 backward bound 构造累计约 `180.749s`，原始 setup 约 `37.740s`。

subtree 也验证了同一问题。root 的 subtree arc elimination 都扫描 `1560` 条候选弧，zero setup 固定 `1383` 条、原始 setup 固定 `1186` 条，但 zero setup 的 bound rebuild 为 `39.821s`，原始 setup 只有 `5.551s`。因此 setup 清零让后续分支树明显变浅，但在 root 无 forbidden/pricingOnly arc 的完整图上，all-cycles bound 要在大量“时间平移更相似、互不支配的函数”之间取下包络，PWLF envelope 更碎，`mergeMinimum/normalizeBackward` 成为主瓶颈。这个解释也和运行中线程栈一致：长时间采样落在 `PiecewiseLinearFunction.mergeMinimum -> CompletionBoundCalculator.buildAllCycles -> CompletionBoundSubtreeArcEliminator.evaluate`。
98. 2026-06-16 zero setup completion bound 优化后的可比复测

在 safe merge / 精确相邻段压缩修改后，先做了一次不可比的 full run，结果明显偏慢。复查后发现该 run 的配置与第 97 节历史结果不一致：RMIH 被关闭，`completionBoundArcFixing` 被关闭，`pricingOnly subtree` 使用了错误属性名，且没有显式打开 midpoint probe/reuse。因此该慢结果不能用于判断 completion bound 修改是否变差。

随后按第 97 节完全可比口径重跑 `wet040_001_2m_zeroSetup`：normal ng-DSSR nearestK8/top10、no partial、no SRI、ALNS、RMIH 4s、completionBound allCycles、completionBoundArcFixing、pricingOnly subtree、midpoint probe/reuse、joinBest=BEST_UB，并关闭无向 adjacency branching。结果为 `FINISHED, incumbent=bound=17881, nodes=12, pool=129607, pricing=338, valid=true`，与历史结果的搜索路径、节点数、列池规模、pricing 次数完全一致。

耗时从历史 `474.103s` 降到 `242.100s`，root 从 `279.186s` 降到 `161.640s`，exact pricing 从 `252.752s/100 calls` 降到 `122.885s/100 calls`，master LP 从 `82.272s` 降到 `37.668s`，heuristic pricing 从 `57.688s` 降到 `43.096s`。root summary 中列生成序列也对齐，早期 exact pricing 仍依次生成 `69、40、22、15、25、44、17...` 条，说明不是因为少生成列或搜索树变了。

关键差异在 completion-bound build。对两个日志抽取所有 `completionBound buildMs` 后，历史记录为 `111` 条 exact 记录、`68` 次非零 build，累计 `234.362s`，最大 `29.222s`；新记录同样为 `111/68`，累计降到 `112.040s`，最大 `20.856s`。root 最后一轮 node summary 的 buildMs 也从 `29.222s` 降到 `8.309s`。因此本次 safe merge no-change 快路径和精确相邻段压缩确实命中了 zero setup 中 PWLF merge/normalize 的瓶颈，且没有改变最终 bound、incumbent、节点数和列池规模。

当前结论为：之前“跑得更慢”的 full run 是配置错误导致的误判；在可比配置下，completion-bound 优化是正收益。后续如果继续比较 completion bound 变体，必须固定 `enableRestrictedMasterIntegerHeuristic`、`completionBoundArcFixing`、`completionBoundSubtreeArcEliminationPricingOnly`、`midpointProbe/reuse` 和 adjacency branching 等关键开关，否则总时间不可解释。

99. 2026-06-16 后续 completion bound 优化方向判断

继续拆解第 98 节结果后，可以把后续优化分成两类。第一类是 PWLF merge 本身的剩余成本。当前 safe no-change 快路径已经把“candidate 没有改小 current 但仍完整 merge”的大头砍掉了一部分；精确相邻段压缩也减少了后续片段数。由于可比运行中 merge/changed 次数仍和历史相同，时间下降主要来自单次 merge 更便宜，而不是传播次数减少。下一步若继续做，应先加轻量诊断统计：按 forward/backward 记录 no-change 快路径命中率、失败原因、target/candidate/after 段数分布、full merge 耗时分布。只有确认“changed merge 只是局部小区间改善”占比很高时，才值得做更复杂的多区间 delta merge；否则继续优化 `canSkipMergeMinimum`、候选函数压缩和 domain-extension 快路径更稳。

第三类问题是正反向 completion bound 构造严重不对称。日志累计显示，历史 zero setup 中 completion-bound 内部正向时间约 `8.285s`、反向约 `195.749s`，反向是正向 `23.6` 倍；优化后正向约 `6.860s`、反向约 `96.391s`，反向仍是正向 `14.1` 倍。也就是说本次优化同时加速了两边，但真正的剩余瓶颈仍在 backward bound。当前猜测是 zero setup 下反向从 sink 往前传播时，各 job 的后缀函数在 due/tardy 形态和可达 predecessor 组合上更容易形成互不支配的下包络，`normalizeBackward` 后片段更多，导致单次 merge 更贵。后续应优先记录 backward 的段数和 merge 失败原因，而不是盲目改传播顺序。

可尝试但需要谨慎的方向包括：1）给 `mergeMinimum` 增加 domain-extension 快路径，如果候选只是在公共定义域不优、但扩展了当前定义域，则只拼接新增边界区间；2）针对 backward candidate 在 shift/add/normalize 后做更早的精确段压缩，减少进入 merge 的片段数；3）统计每个 job 的 backward envelope 段数，定位是否少数 job 拖慢全局；4）如果发现 backward 队列中同一 `(successor,prev)` 反复产生近似相同 candidate，再考虑缓存局部 shift 结果。暂时不建议继续使用 hull delta propagation，因为已有实验说明它减少 merge 次数但总时间变慢，说明粗粒度 hull 带来的额外构造和传播不划算。

100. 2026-06-16 completion bound 后续优化中第 1/3 点复核

继续复核第 1 点后，当前判断是它可以拆成两个层次。第一层是 `mergeMinimum()` 的 no-copy 破坏式右参数合并：当前公共 `mergeMinimum()` 在 no-change 快路径失败后必然 `g.copy()`，这是为了保护 dominance graph、label frontier 和 envelope 缓存传入的右参数不被 splice 破坏。但在 `CompletionBoundCalculator` 中，`buildForwardCandidate/buildBackwardCandidate` 里的 `shiftX()` 和 `add()` 都会生成本轮局部函数，传给 `mergeFunction()` 的 candidate 基本不是缓存里的父函数。因此可以新增仅供 completion bound 使用的内部入口，允许消耗右参数，省掉 changed merge 上的大量 candidate copy。该改动不能直接改公共 `mergeMinimum()`，否则会破坏 dominance 缓存语义；正确做法是保留默认安全口径，只在 completion bound 的 `mergeFunction()` 调用显式选择 destructive-right merge，并用可比 zero-setup 日志验证 bound、节点数、列池规模不变。

第二层是 domain-extension 快路径。当前 `canSkipMergeMinimum()` 只处理“candidate 完全落在 current 定义域内且没有任何区间更优”的情况；一旦 candidate 左/右边界扩展了 current，即使公共定义域里完全没有改善，也会进入完整 copy+merge+normalize。理论上这类场景可以只拼接新增边界区间并做局部相邻段合并。但它和当前 `mergeMinimum()` 对 forward/backward 定义域契约绑定较深，尤其要分别处理左扩、右扩和 prefix/suffix normalize 后的边界语义，所以实现风险高于 no-copy。若只追求下一步收益，应优先做 completion-bound 专用 no-copy；domain-extension 快路径先通过诊断统计确认命中率后再动。

第 3 点方面，最新段数诊断说明“反向慢”不能简单归因于段数更多。zero setup root-only 诊断中，backward 的 target/candidate/after 平均段数只约为 forward 的 1.13 倍，segment samples 约为 1.16 倍，但耗时仍约为 16 倍。这说明剩余瓶颈更可能在 backward full merge 的单位成本：反向 normalize/merge 链表扫描、候选复制、缓存失效或更频繁的 changed merge，而不是少数 job 拥有极端段数。因此后续诊断要按 direction 分别记录 no-change skip 命中、changed/full merge 次数、copy 耗时和每个 job 的 envelope 段数分布。单纯“统计每个 job 段数”只能定位是否有少数 job 拖慢全局，不能解释当前 16 倍差距。

另外，subtree 复用不是缺失项。`PC` 在最后一轮 exact pricing 没有生成负列时会保存 engine 暴露的 `PreparedBounds`，`Tree` 随后传给 `CompletionBoundSubtreeArcEliminator`，而 eliminator 会在 horizon、relaxation、queueOrdering 兼容时直接复用。当前需要关注的是复用条件什么时候失效，例如 dual profitable window、zeroDualExcludedJobs、pricingHorizon 不等于 `data.CmaxH` 或某些 engine 没有暴露 bounds；不是重新设计整套 subtree 复用链路。

补充修正：下一步不应直接实现 no-copy merge，而应先加统计。虽然 candidate copy 是当前最可疑的大头，但现有证据只说明 backward 的单位 merge 成本异常高，还没有把耗时拆成 no-change 扫描、candidate copy、full merge 扫描、normalize 这几部分。因此第一阶段修改目标应是 completion-bound 专用轻量诊断：按 forward/backward 分别记录 merge 调用数、no-change skip 命中数、full merge 数、changed 数、右参数 copy 的次数和累计耗时、mergeMinimum 本体耗时、normalize 耗时（如果能低侵入拆出来）、target/candidate/after 段数分布。只有统计确认 copy 或某个子阶段占大头后，再做 no-copy 或 domain-extension 快路径。

101. 2026-06-16 completion-bound mergeMinimum 子阶段耗时诊断

按上一节决定，本次先做诊断而不是直接做 no-copy。`PiecewiseLinearFunction.mergeMinimum()` 新增默认关闭的 thread-local observer，只有设置 `twet.bpc.completionBoundMergeTiming=true` 时才记录 no-change skip、右参数 copy、merge 本体和 normalize 四段耗时；`CompletionBoundCalculator` 在构造 bound 时临时安装该 observer，并按 forward/backward 分别汇总打印。默认求解路径不安装 observer，不改变函数合并语义，也不影响普通 dominance graph。

用 40-2 zero setup root-only、normal ng-DSSR nearestK8/top10、no partial、no SRI、ALNS、RMIH 4s、allCycles completion bound、pricingOnly subtree、midpoint probe/reuse、joinBest=BEST_UB 的同口径配置做诊断，结果为 `NODE_LIMIT, incumbent=17887, bound=17866.666667, solve=250.417s, exact=160.178s/9 calls, valid=true`。该 run 额外打印 10 条 merge timing，其中 9 条来自 root exact pricing 的 completion-bound build，1 条来自 root 后 subtree elimination 的 bound rebuild。

只看 9 次 exact build，forward merge 统计为 `skip/full/changed=124640/120792/120792`，累计约 `5.775s`；backward 为 `143050/146142/146142`，累计约 `109.479s`，backward 是 forward 的约 `19.0` 倍。backward 内部分解为：skip 扫描约 `5.867s`，copy 约 `0.409s`，merge body 约 `3.310s`，normalize 约 `99.438s`。因此 backward normalize 占 backward merge timing 的约 `90.8%`，而 candidate copy 只占约 `0.4%`。

把 subtree 那次也算入 10 条记录后结论不变：backward 总计约 `121.153s`，其中 normalize 约 `109.848s`，占 `90.7%`；copy 约 `0.473s`，只占 `0.39%`。这直接推翻了“下一步优先做 completion-bound 专用 no-copy merge”的判断。no-copy 即使完全消除 copy，也只能节省不到 1 秒量级，远小于 backward normalize 的百秒量级瓶颈。

结合代码看，`normalizeBackward()` 当前流程是先裁右侧 big-M、合并相邻同段、调用 `minimizeSuffixInPlace()`、再做一次相邻同段压缩；而 `minimizeSuffixInPlace()` 会把链表扫进 `ArrayList<Segment>`，再倒序重建链表。当前最明确的下一步优化目标不是 right-argument copy，也不是粗 hull delta propagation，而是继续拆 `normalizeBackward/minimizeSuffixInPlace`：先增加内部子阶段统计，确认耗时集中在装数组、倒序 suffix-min 重建、SegmentPool.obtain/insert 还是最后 compact；再考虑在 backward 已经满足 suffix-min 或只发生局部小改动时跳过/局部执行 suffix-min。这个方向需要比 no-copy 更谨慎，因为它会直接影响 lower envelope 的方向化闭包语义。

102. 2026-06-16 normalizeBackward 内部子阶段诊断

继续沿第 101 节结论，把 observer 限定在 `mergeMinimum()` 内部调用 `normalize()` 的范围内，并把 normalize 拆成四段统计：裁 big-M 或边界 trim、前置相邻段压缩、方向化 prefix/suffix-min、后置相邻段压缩。这样可以避免把 candidate 构造阶段的其他 normalize 混入 completion-bound merge timing。默认仍由 `twet.bpc.completionBoundMergeTiming` 控制，关闭时不安装 observer。

同一个 40-2 zero setup root-only 配置复跑，结果仍为 `NODE_LIMIT, incumbent=17887, bound=17866.666667, valid=true`。本次 run 总时间 `124.891s`、exact `81.295s/9 calls`，和上一轮绝对时间不直接比较，因为 Java/JIT/缓存和诊断组合会带来波动；这里只看子阶段占比。

只看 9 次 exact build，forward normalize 累计约 `0.753s`，backward normalize 累计约 `45.526s`。backward normalize 的四段拆分为 `trim/pre/min/post=0.333s/0.304s/44.593s/0.174s`，其中 `minimizeSuffixInPlace()` 占 `97.95%`。backward merge timing 总体中，normalize 占约 `88.88%`，copy 仍只有 `0.41%`。把 root 后 subtree 那次也加入，结论也一致：backward normalize 累计约 `49.855s`，其中 suffix-min 约 `48.837s`，占 `97.96%`。

因此新的修改目标已经进一步收窄：不是 `mergeMinimum` 主体，也不是 `normalizeBackward` 的 big-M 裁剪或 compact，而是 `minimizeSuffixInPlace()` 自身。当前实现先把链表扫入 `ArrayList<Segment>`，再倒序构造新链表，并用 `SegmentPool.obtain/insertSegment` 生成水平段和保留段。下一步应优先统计或优化这三个点：1）装数组是否必要，能否用临时栈或复用数组减少分配；2）倒序 suffix-min 是否总是必须完整重建，是否能在输入已满足 suffix-min 时直接跳过；3）能否像 forward 一样更少创建新 segment，或在 backward changed 区间很小时做局部 suffix-min。相比之下，no-copy 和 domain-extension 快路径现在都不是第一优先级。

103. 2026-06-16 删除 minimizeSuffixInPlace 内层 getSegmentNum 调试扫描

继续检查第 102 节定位出的 `minimizeSuffixInPlace()` 后，发现最可疑点不是算法本身，而是主循环尾部每处理一个 segment 都调用一次 `getSegmentNum()`。`getSegmentNum()` 会从 `head` 扫整条链表，并更新 `Utility.debugMap` 中的 `segmentNum` 等统计。因此在 segment 数较多时，这一行会把 suffix-min 的线性倒序处理放大成接近二次扫描，而且该调用不参与函数值、head/tail、链表拼接或 reduced cost 计算，只是调试计数。

本次只删除 `minimizeSuffixInPlace()` 主循环内的这一次 `getSegmentNum()`，保留其他函数末尾的单次统计调用不动。这样不会改变 PWLF 语义，唯一变化是 `Utility.debugMap.segmentNum` 不再记录 suffix-min 内部每一轮的中间链表长度累加。这个统计本来就不是求解逻辑的一部分，且在当前 completion-bound 热路径中代价过高。

同一个 40-2 zero setup root-only 配置复跑，结果保持 `NODE_LIMIT, incumbent=17887, bound=17866.666667, valid=true`。总时间降为 `74.065s`，exact pricing 降为 `17.740s/9 calls`。与第 102 节的诊断 run 相比，exact 从 `81.295s` 降到 `17.740s`，约 `4.58x` 加速。

子阶段统计也对应验证了原因。10 条 timing 记录中，backward normalize 从上一轮约 `49.855s` 降到 `2.212s`，约 `22.5x`；`minimizeSuffixInPlace()` 子段从约 `48.837s` 降到约 `0.985s`。删除后 backward merge timing 的主要占比不再集中于 normalize：all-10 汇总中 backward skip 约 `4.514s`、body 约 `2.217s`、normalize 约 `2.212s`。因此当前最大单点瓶颈已经从 suffix-min 内层调试扫描转移到 no-change skip 扫描和 merge body 本身。

当前下一步不应继续大改 suffix-min。更合理的优化顺序是：1）先保留当前结果跑一个可比 full zero-setup 或至少 root+若干节点确认整体收益；2）若继续优化 completion bound，再拆 `canSkipMergeMinimum()` 的 no-change 扫描和 merge body 里的分段拆分/替换成本；3）视需要把其他 `getSegmentNum()` 末尾统计也改成显式 debug 开关控制，但它们不是当前根节点瓶颈。

102. 2026-06-16 completion-bound 是否单线程的验证

针对“单线程？”这个问题，本次先做静态搜索，再做运行期线程栈采样。静态上，`CompletionBoundCalculator`、各 `GC*Bidirectional*` pricing engine、`CompletionBoundSubtreeArcEliminator` 中没有 `parallelStream`、`Executor`、`ForkJoin` 或手动 `new Thread` 参与 completion-bound 构造；`ThreadLocal<MergeMinimumObserver>` 只是为了避免诊断 hook 泄漏到其它调用方，不表示当前算法本身并行。

运行期用 40-2 zero setup root 进程采样，第一次抓到的是 ALNS 初始解阶段，说明还没进入 bound。随后关闭 ALNS/RMIH 做短采样，在线程栈中抓到：

`"main"` 线程处于 `PiecewiseLinearFunction.minimizeSuffixInPlace -> normalizeBackward -> normalize -> mergeMinimum -> CompletionBoundCalculator.mergeFunction -> mergeBackward -> buildAllCycles -> build -> GCNGBBStyleBidirectionalNgDssr.buildCompletionBounds`。

同一栈快照里其余活跃线程是 JVM 的 Reference/Finalizer/Attach Listener/Service Thread/JIT Compiler/GC 线程，没有其它业务线程执行 `CompletionBoundCalculator`。因此当前 completion-bound DP 和 PWLF merge 是业务单线程执行；前面统计出的 backward normalize 百秒级耗时不能靠“已有并行被隐藏”解释。后续若要并行化，只能显式改造，例如按 job/state 分批传播或并行构造候选，但这会牵涉 shared envelope 合并、队列重入和确定性，风险明显高于先优化 `normalizeBackward/minimizeSuffixInPlace` 的串行热点。

104. 2026-06-16 清理真实求解热路径中的调试统计

在删除 `minimizeSuffixInPlace()` 主循环内的 `getSegmentNum()` 之后，继续全局搜索 `debugMap`、`getSegmentNum()`、`TimerManager` 和诊断输出。确认 `TimerManager` 由 `Configure.timeManage=false` 默认关闭，`Utility.debugCheckPWLF...` 也由 `debugPWLFDomainCheck=false` 控制，剩余真正会混入热路径的是两类无条件统计：一类是 `PiecewiseLinearFunction.add()`、`minimizePrefixInPlace()`、`trimToDomain()` 末尾顺手调用 `getSegmentNum()`，会扫描整条 segment 链并写 `Utility.debugMap`；另一类是 `Solution.merge2Segments/merge3Segments()` 中的 `M2S/M3S Total/Skip` 计数，会在 ALNS/VND 拼接热路径频繁写 `debugMap`。

本次新增两个默认关闭开关：`Configure.debugPWLFSegmentStats` 和 `Configure.debugAlgorithmCounters`。`getSegmentNum()` 仍保留真实计数返回值，便于显式诊断或测试调用，但只有 `debugPWLFSegmentStats=true` 时才写 `debugMap`；上述 PWLF 末尾统计改为 `recordSegmentNumIfEnabled()`，正常求解不再触发扫描。HEU 的 `M2S/M3S` 计数改为 `recordDebugCounter()`，只有 `debugAlgorithmCounters=true` 时才写 map。这样不改变任何函数值、列成本、reduced cost、分支兼容性或求解路径，只去掉默认求解中的统计副作用。

验证方面，重新搜索后，`debugMap` 写入要么位于上述开关内，要么属于测试/诊断输出；排除历史 `src/BPC` 包后，focused `javac` 通过，仅保留原有 deprecation 提示。后续若再临时加入诊断统计，必须默认关闭，并避免在 PWLF segment 级循环或 pricing label 扩展循环里无条件扫描链表或写 `HashMap`。

105. 2026-06-16 继续复查默认求解中的 debug/统计残留

继续按 `debugMap`、`debugNumPlus`、`getSegmentNum`、`System.out`、`diagnostic`、`heartbeat`、`TimerManager`、`nanoTime/currentTimeMillis` 等关键字复查当前代码。结论是上一次那类真正混进真实求解热路径的 `HashMap` 计数和 segment 链表扫描已经基本清掉：`PiecewiseLinearFunction.getSegmentNum()` 的 `debugMap` 写入受 `debugPWLFSegmentStats` 控制，`Solution` 中的 `M2S/M3S` 计数受 `debugAlgorithmCounters` 控制，paper graph timing、completion-bound heartbeat、pricing snapshot、node progress summary 等诊断均有显式开关，默认不会输出或做额外 I/O。

本次只补一个很小的残留点：`CompletionBoundCalculator.mergeFunction()` 和 `mergeFunctionWithChangeHull()` 在 segment 诊断关闭时虽然不会真的扫描段数，但仍会调用 `recordSegmentMerge(...)` 再由内部开关返回。现在改为只有 `diagnosticSegments=true` 时才计算 candidate/current 段数并进入记录函数。该修改不改变 completion bound 的函数合并、队列传播、剪枝结果或统计语义，只避免默认求解路径上残留的诊断入口调用。

剩余没有处理的主要是两类。第一类是测试、批处理、诊断入口的 `System.out`，例如 comparison test、seed diagnosis、plotter 等，这些不是正式 BPC 热路径。第二类是 pricing summary 需要的 primitive 计数，例如 forward/backward pops、mergeCalls、mergeChanged 等，它们用于正常结果摘要和阶段判断，开销只是 long 加法，不属于前面发现的链表扫描或 `HashMap` 热点。`Basic.Data` 里还有少量老的 debug 打印，但它们不是当前 root pricing/completion-bound 的计算瓶颈，本轮先不把输出口径调整和性能清理混在一起。

106. 2026-06-16 重新求解 40-2 zero setup

按第 98 节相同口径重新求解 `wet040_001_2m_zeroSetup`：normal ng-DSSR nearestK8/top10、no partial、no SRI、ALNS、RMIH 4s、`completionBound=allCycles`、`completionBoundArcFixing=true`、pricingOnly subtree、midpoint probe/reuse、`joinBest=BEST_UB`，并关闭无向 adjacency branching。运行名为 `tmp-wet040-001-zero-setup-current-20260616`，结果为 `FINISHED`，`incumbent=bound=17881`，处理 `14` 个节点，pricing `277` 轮，新增列 `29710`，最终 pool `29777`，validator 为 `true`。

本次总时间 `68.643s`，root `46.906s`，heuristic pricing `22.404s/198`，ng-DSSR exact pricing `15.987s/79`，master LP `8.956s`，RMIH `2.891s/10`，subtree arc elimination `1.434s/7`。对比之前完整 zero-setup no-SRI 记录：最早同口径为 `474.103s`、exact `252.752s`、pool `129607`；safe no-change merge 后完整记录为 `242.100s`、exact `122.885s`、pool `129607`。当前 run 进一步降到 `68.643s`，同时最终目标仍为 `17881`。

日志中 79 条 exact pricing 记录里，completion-bound build 非零 `57` 次，累计约 `12.546s`，最大单次 `1.338s`；内部正向累计约 `4.583s`、反向约 `7.826s`。这比第 98 节记录的 completion-bound build 累计 `112.040s` 和最大 `20.856s` 明显更低，说明删除 segment 级 debug 扫描、默认关闭 segment/algorithm 统计、以及本轮诊断入口收口后，zero setup 下的 completion-bound 热路径已经不再是百秒级瓶颈。当前总时间主要分布在 heuristic pricing、exact pricing、LP 和少量 RMIH 上，root 仍是最大单节点，但已经从原先的数分钟降到一分钟以内。

107. 2026-06-16 再次确认 debug/统计热路径残留

针对“是否还有类似 `getSegmentNum()` 混进真实求解热路径”的问题，本次继续搜索 `debugMap`、`debugNumPlus`、`getSegmentNum`、`System.out`、`diagnostic`、`heartbeat`、`TimerManager`、`nanoTime/currentTimeMillis` 等入口。当前结论是，normal ng-DSSR 主线中已经没有同类的链表全扫描或 `HashMap` 写入残留：`TimerManager` 默认由 `Configure.timeManage=false` 第一行返回，PWLF 段数统计由 `debugPWLFSegmentStats=false` 控制，HEU 拼接计数由 `debugAlgorithmCounters=false` 控制，completion-bound segment 统计、merge timing、paper graph timing、pricing snapshot、heartbeat 和 midpoint/full diagnostic 都需要显式系统属性或配置打开。默认真实求解路径中仍保留的只是 primitive long 计数和少量阶段耗时，用于正常 summary，不属于前面导致数量级变慢的统计副作用。

本次额外发现并修掉一个较小残留：`PartialListDominanceStore` 的 `cardinalitySkips` 统计虽然只用于 summary，但原来每次插入/单点检查都会扫描若干 cardinality bucket 计数。它不影响第 106 节的 no-partial zero-setup 结果，但会影响 partial-list/SRI 实验。现在新增 `twet.bpc.partialListCardinalitySkipStats`，默认关闭该 bucket 计数扫描；需要诊断时才打开。focused `javac` 已通过。剩余未清理的输出主要是测试类 summary、显式诊断方法和 `Basic.Data` 中少量数据预处理打印，不在当前 pricing/completion-bound 热路径中。

108. 2026-06-16 重新求解 40-2 原始 setup 算例

按第 106 节相同主线配置重新求解原始 `data/40-2/wet040_001_2m.dat`，即 normal ng-DSSR nearestK8/top10、no partial、no SRI、ALNS、RMIH 4s、`completionBound=allCycles`、`completionBoundArcFixing=true`、pricingOnly subtree、midpoint probe/reuse、`joinBest=BEST_UB`，并关闭无向 adjacency branching。运行名为 `tmp-wet040-001-setup-current-20260616`，结果为 `FINISHED`，`incumbent=bound=22580`，处理 `51` 个节点，pricing `1608` 轮，新增列 `85816`，最终 pool `85816`，validator 为 `true`。

本次总时间 `313.587s`，root `95.623s`，heuristic pricing `105.163s/1170`，ng-DSSR exact pricing `106.642s/434`，master LP `31.239s`，RMIH `47.185s/34`，subtree arc elimination `1.891s/25`，root bound 为 `22490`，初始上界 `22584`，最终上界由 RMIH 在 root 更新到 `22582`，后续更新到 `22580` 并闭合。completion-bound build 在 exact pricing 记录中非零 `231` 次，累计约 `39.032s`，最大单次 `1.664s`，内部正向累计约 `17.890s`、反向约 `20.386s`。

对比此前同配置原始 setup 的历史记录 `FINISHED, incumbent=bound=22580, nodes=149, solve=813.249s, exact=416.917s/1071, heuristic=190.739s/2987, pool=211279`，当前 run 明显更快，且节点数、列池规模和 pricing 次数都下降很多。与第 106 节 zero-setup 当前结果相比，原始 setup 仍更慢：`313.587s` 对 `68.643s`，节点 `51` 对 `14`，列池 `85816` 对 `29777`。当前判断是，前面清掉 debug/统计热路径后，原始 setup 的 completion-bound 不再表现为异常百秒瓶颈；剩余耗时主要来自更大的分支树、更多 heuristic/exact pricing 轮和 RMIH 调用，而不是单个 completion-bound build 卡住。

109. 2026-06-16 解释原始 setup 当前节点数/列池为何低于旧记录

继续复查第 93 节旧记录和第 108 节当前记录后，不能把 `813.249s -> 313.587s` 简单解释成“只优化了函数计算”。两次 root 的主指标很接近但不完全相同：旧 root 为 `pool=9520/restricted=9518`，当前 root 为 `pool=9534/restricted=9532`，root bound 都是 `22490`，第一处分支也同为 arc `(5,9)`。但是从第二个处理节点开始分支路径就已经不同：旧 node2 分支 arc `(16,8)`，当前 node2 分支 arc `(30,7)`。这说明两次 run 在 root 后的 LP 列集/基/dual 已经产生了足够差异，后续分支树不能再要求一致。

造成这种差异的主要候选不是 completion-bound 函数计算本身，而是后续代码中引入的 map-based top-K duplicate signature 处理：旧逻辑对同一 sequence signature 候选基本是先到先保留，后来的同路径更低 reduced-cost 候选会被丢掉；当前逻辑改为 lazy replacement，始终在本轮候选池中保留同 signature 的更优 reduced-cost 版本。这会改变最终加入 RMP 的列，即使根节点目标和第一处分支看起来相同，也可能改变后续 LP dual、fractional arc 排序和节点优先级，从而让节点数、列池和 pricing 次数明显下降。旧日志还打开了 node progress / pricing details 诊断，存在 `149` 条 `[BPC node summary]` 和 `1077` 处 `nodeDiag`，当前 run 这些诊断为 0；这部分主要解释额外耗时和日志开销，不应单独解释节点数变化。

因此，第 108 节的 313 秒结果应理解为“当前代码主线”的重新求解结果，而不是对第 93 节旧代码只做纯性能优化后的严格 A/B。若要严格拆分贡献，需要从旧 commit 分别 cherry-pick：只清 debug/统计、只改 duplicate signature、再组合运行；当前已有证据只能说明当前主线更快且结果正确，不能把节点数下降全部归因于 PWLF/completion-bound 加速。

110. 2026-06-16 进一步定位 813 秒旧记录与 313 秒当前记录的实际分叉点

继续对比旧日志 `tmp-wet040-001-ng-nearest-heur1500-5000-full-20260615` 和当前日志 `tmp-wet040-001-setup-current-20260616`，可以看到两次 run 在 root 前半段并不是一开始就不同。root 初始 17 轮 heuristic pricing 加列数完全一致，均为 `1036,1002,1351,806,725,701,603,504,482,457,447,257,201,167,49,6,0`；第一次 ng-DSSR exact pricing 的 label、join、completion-bound 统计也基本一致，并且同样返回 `150` 条列。差异首先出现在 exact pricing 的候选池去重统计：旧日志第一轮 exact 为 `candidatePool kept/seen/dropped=150/180/30`，当前为 `150/180/3`；第二轮 exact 旧日志为 `85/91/6`，当前为 `85/91/0`。这不是函数计算耗时差异，而是同一路径候选的保留规则已经不同。

对应代码改动是 `1496c74 Keep best duplicate pricing candidates`。旧 `rememberGeneratedCandidate()` 遇到同一个 `SequenceSignature` 已存在时直接丢弃后来的候选；如果堆满，则只拿新候选和当前最差候选比较。当前逻辑改成 `generatedCandidateBySignature` 始终指向同 signature 的当前最好 reduced-cost 候选，旧候选留在 heap 里按 stale 跳过。这样 exact pricing 返回的列数可能相同，但具体列对象、列成本/排序和后续 LP dual 都可能不同。

日志也支持这个判断：第二轮 exact 后两次后续 heuristic pricing 已经开始分叉。旧日志在 pool `9289` 后继续加 `9,16,5,8,3,6,4...`；当前日志在同样 pool `9289` 后变成 `3,7,11,8,1...`。这说明 LP 重新求解后的 dual 已变，后续不是同一棵搜索树。再往后 node2 分支 arc 也从旧 `(16,8)` 变为当前 `(30,7)`，节点数、列池和 pricing 次数下降就不再能用纯性能优化解释。

因此，当前更精确的结论是：PWLF/completion-bound 优化主要解释单次 exact pricing 和 completion-bound 构造变快；搜索树变小主要应归因于 duplicate signature 候选保留策略改变了进入 RMP 的列集。旧 run 的 nodeDiag/progress 诊断会增加额外时间，但不解释分支路径变化。若需要确认是否“改错”，最直接的验证不是再看耗时，而是在当前代码临时恢复旧 duplicate 策略跑一次；若节点数回到接近旧记录，则说明差异来自该策略而非 PWLF。

111. 2026-06-16 临时恢复旧 duplicate signature 策略的 40-2 验证

按第 110 节判断，本轮只在当前代码上临时恢复 `GCNGBBStyleBidirectionalNgDssr.rememberGeneratedCandidate()` 的旧 duplicate 处理：同一 `SequenceSignature` 已存在时直接丢弃后来的候选，不再保留 reduced-cost 更低的同路径候选。其它配置保持第 108 节当前主线不变：normal ng-DSSR nearestK8/top10、no partial、no SRI、ALNS、RMIH 4s、`completionBound=allCycles`、`completionBoundArcFixing=true`、pricingOnly subtree、midpoint probe/reuse、`joinBest=BEST_UB`，并关闭无向 adjacency branching。运行目录为 `test-results/bpc/tmp-wet040-001-oldduplicate-current-20260616`。运行结束后已把源码和 `target/classes` 重新编译回当前 lazy replacement 版本，临时代码没有保留。

结果为 `FINISHED`，`incumbent=bound=22580`，总时间 `876.994s`，处理 `105` 个节点，pricing `2985` 轮，新增列/最终 pool `173848`，root `134.790s`，heuristic pricing `205.429s/2194`，ng-DSSR exact pricing `452.928s/781`，master LP `58.589s`，validator 为 `true`。对比当前 lazy replacement 主线第 108 节 `313.587s / 51 nodes / 1608 pricing / pool 85816 / exact 106.642s/434 / heuristic 105.163s/1170`，旧 duplicate 策略确实把搜索规模和耗时显著拉回旧方向。对比第 93 节历史旧记录 `813.249s / 149 nodes / 4070 pricing / pool 211279 / exact 416.917s/1071 / heuristic 190.739s/2987`，本轮节点数和列池没有完全回到 149/211279，但量级已经接近旧记录而远离当前 313 秒记录。

更关键的是分支路径验证：本轮 root 前 17 轮 heuristic 加列仍与旧记录一致；第一次 exact 的 duplicate 统计恢复为 `candidatePool kept/seen/dropped=150/180/30`，第二次 exact 恢复为 `85/91/6`。分支上，node1 仍为 `(5,9)`，node2 恢复为旧记录中的 `(16,8)`，而当前 lazy replacement 主线 node2 是 `(30,7)`。这基本确认搜索树变化主要由 duplicate signature 候选保留策略造成，而不是 PWLF/completion-bound 函数计算优化造成。

本轮没有打开旧日志中的 node progress/nodeDiag 详细诊断，因此不能要求时间和节点数与第 93 节完全一致；此外当前代码中还有 PWLF no-change、debug gating、completion-bound 诊断关闭等后续性能改动，会影响单次 pricing 耗时。当前结论是：`1496c74` 的 lazy duplicate replacement 不是“纯提速”，它是一个会改变 RMP 入池列集的算法修正；从结果看它显著减少了后续树规模，并且最终最优值和 validator 均保持一致。

112. 2026-06-17 old-duplicate 消融为何节点更少但总时间仍高于历史 813s

继续拆解第 111 节结果后，不能只看 `nodes/pool/pricing` 总量。历史旧记录为 `149 nodes / 4070 pricing / pool 211279 / solve 813.249s`，old-duplicate 消融为 `105 nodes / 2985 pricing / pool 173848 / solve 876.994s`，表面上后者规模更小，但 exact/heuristic 的平均单次耗时更高：历史 exact 约 `416.917s/1071=0.389s/call`，old-duplicate 约 `452.928s/781=0.580s/call`；历史 heuristic 约 `190.739s/2987=0.064s/call`，old-duplicate 约 `205.429s/2194=0.094s/call`。因此矛盾点不是计数，而是 old-duplicate 这次路径里存在更重的单次 pricing。

日志定位到最明显的异常是 old-duplicate 的 `node=69`：一次 ng-DSSR exact pricing 耗时 `18066.812 ms`，生成 `40994` 条列，`candidatePool kept/seen/dropped=40994/41950/956`，`join pairs tried/set/lb/time/funcEval/funcPruned=153068/1149/993/0/151919/17270`，forward kept/dominated 为 `14362/21120`。这说明该节点出现了局部候选列爆炸。历史旧日志中的 `node=69` 不是同一个搜索状态：同名节点处多轮 exact 仅生成几十条或个位数列，例如 `33`、`11`、`9` 条，单次耗时约 `0.17-0.19s`，并且 pricingOnly/job forbidden 状态、tMid 和标签分布也明显不同。也就是说节点编号相同没有可比性，两次搜索树虽然前几个分支相同，但中后段已经走到不同状态。

因此，old-duplicate 消融的正确解释是：恢复旧 duplicate 策略足以把 early branch path 拉回旧方向，并证明 duplicate 策略是搜索树变化主因；但它没有复现完整历史代码状态，也没有保证后续每个节点状态相同。old-duplicate 这次虽然总节点数少于历史，却碰到一个局部极难节点，单点 exact pricing 产生 4 万多列，吞掉了大量时间，所以总时间反而略高。历史 813s 还混有 nodeDiag/progress 诊断成本，但它没有遇到这个 `node=69` 爆炸状态；两者总时间不能按节点数线性比较。

当前结论为：这不是组件没开，也不是单纯统计混入导致的慢，而是搜索路径差异叠加局部 pricing 爆炸。要做严格 A/B，必须 checkout 到历史 commit 或完整恢复一组历史代码状态；只恢复 `rememberGeneratedCandidate()` 的 duplicate 判断只能验证该策略对树路径的影响，不能复刻历史 813s 的每个节点。

113. 2026-06-17 old-duplicate 消融与历史配置仍不完全一致的原因

继续对比历史日志、old-duplicate 消融日志和 `1496c74^..1496c74` 的代码 diff 后，可以把问题进一步收窄。历史版本的候选池语义不是简单的“同 `SequenceSignature` duplicate 已存在就丢弃”这一条。它还在最终 `finalizeGeneratedColumns()` 时从 `generatedColumnCandidates` priority queue 取候选，再排序后加入列池；而当前 lazy replacement 版本改成了 `generatedCandidateBySignature` 保存每个 signature 当前最优候选，最终从 map values 取候选。也就是说，候选池的数据结构语义、堆中保留对象、最终输出来源都变了。

此前 old-duplicate 消融只临时恢复了 `rememberGeneratedCandidate()` 里的 duplicate 判断，即同 signature 已存在时直接丢弃后来的候选。但它没有完整恢复历史的 finalize 来源和候选池状态语义。因此这个消融可以让早期 duplicate 统计恢复到历史口径，例如第一次 exact 的 `150/180/30` 和第二次 exact 的 `85/91/6`，也可以把 node2 分支方向拉回 `(16,8)`，但它不能保证 exact 返回的具体列集合、列顺序和后续 LP dual 完全等同于历史版本。

日志对比支持这一点。历史和 old-duplicate 的前 45 个 pricing event 在 engine、add 数和 pool 数上完全一致；第一次明确分叉出现在第 68 个 pricing event：历史 node2 的一次 heuristic pricing 为 `add=7,pool=10506`，old-duplicate 为 `add=6,pool=10505`。由于 `1496c74` 没有修改 `HeuristicPricingEngine`，这个分叉不应解释为 heuristic 代码直接变化，而应解释为前一轮 exact pricing 虽然统计数量接近，但实际加入 RMP 的列已经有差异，导致 LP dual 和后续启发式列生成发生偏移。

因此当前结论是：要和第 93 节历史 813s 配置“真正一样”，不能只恢复 duplicate if 条件。严格复现有两种方式：一是直接 checkout 到历史 run 对应 commit，并使用相同输入、相同系统属性和诊断开关；二是在当前代码里做完整兼容开关，恢复 `1496c74^` 中 candidate pool 相关函数的整体语义，包括 duplicate 判断、堆满时替换逻辑、`generatedCandidateBySignature` 与 `generatedColumnCandidates` 的同步关系、以及 `finalizeGeneratedColumns()` 的候选来源。对于当前 normal ng-DSSR nearestK8/top10 这组配置，最小需要恢复的是 `GCNGBBStyleBidirectionalNgDssr` 的这组函数；若要覆盖其它 pricing engine，则还要同步恢复其它六个 engine 在 `1496c74` 中改过的同类候选池逻辑。

这也解释了为什么 old-duplicate 消融会“指标更轻但更慢”。它不是历史 run 的完整复刻，而是当前代码状态下的一个混合版本：早期路径被拉向旧策略，但中后期仍可能走到历史 run 没有经历过的难节点。node69 的 4 万列爆炸就是这种混合路径的局部结果，不能用它和历史 node69 做同编号节点对比。

114. 2026-06-17 历史 813s 代码口径去除统计污染后的重跑

按“在历史那次里把统计污染项删掉重新跑，最后代码恢复当前版本”的要求，本轮没有在当前主工作区上回退代码，而是新建隔离 worktree 到 `1eccbd8 Raise default heuristic pricing cap`。这个 commit 位于 `1496c74 Keep best duplicate pricing candidates` 之前，对应第 93 节历史 813s run 的候选池语义：同 `SequenceSignature` duplicate 仍是先到先保留，后来的同路径候选直接丢弃，finalize 也仍从 `generatedColumnCandidates` heap 中取候选。

在该历史 worktree 上只补两个统计清理：`1cea5af` 删除 `PiecewiseLinearFunction.minimizeSuffixInPlace()` 主循环内层的 `getSegmentNum()` 调用，避免每个 segment 都扫描整条链表并写 `Utility.debugMap`；`7a191fd` 给 `PiecewiseLinearFunction.getSegmentNum()` 和 `Solution.countAlgorithm()` 这类 debugMap 计数加显式开关，默认关闭。没有补 `a21c941`，因为它清理的是后续 merge timing / completion-bound segment diagnostics 引入后的 dormant 诊断结构，而 `1eccbd8` 历史基线里没有完整对应结构，硬套会改变代码上下文，不属于这次“历史配置去统计污染”的最小修改。

重跑配置保持第 93 节口径：`wet040_001_2m`，normal ng-DSSR nearestK8/top10，ALNS seed，RMIH 4s，completionBound allCycles，completionBound arc fixing，pricingOnly subtree，midpoint probe/reuse，joinBest=BEST_UB，关闭无向 adjacency branching，启发式上限 `1500/5000`。输出保存为 `test-results/bpc/tmp-wet040-001-historical-cleanstats-20260617.csv` 和同名 log。结果为 `FINISHED`，`incumbent=bound=22580`，`nodes=117`，`pricing=3409`，`pool=225574`，`solve=786.962s`，`root=77.963s`，`exact=296.134s/939`，`heuristic=201.021s/2451`，`master_lp=106.046s`，`valid=true`。

与历史第 93 节 `813.249s / 149 nodes / 4070 pricing / pool 211279 / exact 416.917s/1071 / heuristic 190.739s/2987 / master_lp 48.630s` 相比，统计清理确实有效：总时间下降约 `26.287s`，exact pricing 下降约 `120.783s`，root 从 `116.061s` 降到 `77.963s`。但 LP 时间和列池变大，且节点数也没有完全复刻成 149。这说明即使在相同历史候选池语义下，去掉热路径统计会改变运行时间分布，并可能因时序、数值退化或列池细节带来一定搜索波动；不过它没有把历史 run 变成当前主线的 313s 级别。

因此当前结论更明确：统计污染是历史 813s 中的一部分额外成本，尤其影响 completion-bound/PWLF 相关 exact pricing；但当前主线 `313.587s / 51 nodes / pool 85816` 的大幅变化主要不是统计清理造成的，而是 `1496c74` 之后同 signature 候选保留更低 reduced cost 的策略改变了进入 RMP 的列集、LP dual 和分支树。若要让当前代码“和历史那次一样”，必须完整恢复 `1496c74^` 的 candidate pool 语义；若只想评估去统计污染的收益，本节实验已经给出更干净的对照。实验结束后临时 worktree 已删除，主工作区代码仍保持当前 `e3722ff` 版本。

115. 2026-06-17 历史 clean-stats 重跑配置错配修正

继续复核第 114 节后，确认其中 `nodes=117` 的完整 run 不能作为“只清理统计污染”的严格 A/B。原因不是统计清理会改变搜索树，而是该 run 的 subtree arc elimination 开关口径错了：命令同时打开了 `completionBoundSubtreeArcElimination=true` 和 `completionBoundSubtreeArcEliminationPricingOnly=true`。当前 `Tree.applySubtreeArcElimination()` 的语义是 hard 开关优先，只要 hard 为 true 就调用 `applyTo()` 把 subtree 固定弧写成普通 forbidden arc；只有 hard 为 false 且 pricingOnly 为 true 时，才调用 `applyToPricingOnly()`。

日志证据很直接。历史 813s 记录在 node2 的诊断为 `forbiddenJobArcs/pricingOnlyJobArcs=1/1186`，说明 root subtree 固定的 1186 条弧只进入 pricingOnly 口径；而第 114 节那次 clean-stats 完整 run 和复查短跑在 node2 变成 `1187/0`，即同一批弧被硬禁。这个差异会改变 restricted columns、LP dual、启发式 seed 和 exact pricing 图，所以 node2 第一次启发式加列从历史的 `436` 变成 `518`，后续节点数变为 117 不能归因于统计清理。

为验证这一点，在同一个历史 clean worktree 上只把 subtree 配置改成 `completionBoundSubtreeArcElimination=false`、`completionBoundSubtreeArcEliminationPricingOnly=true`，并设置 `maxNodes=2` 短跑。结果 node2 回到历史口径：`pool=10545`、`restricted=9977`、`pricing add=1025`、`heur add=639`、`exact add=386`、`subtree fixed=79`，和第 93 节历史 node2 summary 对齐。因此当前修正结论为：统计热路径清理确实能减少单次函数计算耗时，但第 114 节完整 run 的节点数差异主要来自 subtree hard/pricingOnly 配置错配；不能再用 `117 nodes` 解释统计清理收益。

后续做可比实验时必须显式区分两种 subtree 口径。`completionBoundSubtreeArcElimination=true` 是永久/硬禁弧，会改变 RMP 列兼容性和分支树；`completionBoundSubtreeArcElimination=false` 且 `completionBoundSubtreeArcEliminationPricingOnly=true` 只影响 pricing/completion-bound，不把历史列从 RMP 中硬删掉，这是第 93 节历史 813s 记录对应的口径。这也说明此前“清洗统计仍导致 node 数变”这个判断是错误归因。严格评估去统计污染，应在 pricingOnly 口径下重跑完整收敛；当前短跑已经证明早期节点路径可对齐，但完整耗时还没有重新跑完。

116. 2026-06-17 subtree hard-on 缺点的进一步确认

后续在正确 pricingOnly 口径下完整重跑后，得到 `solve=597.417s, nodes=149, pool=211291, exact=193.933s, heuristic=158.576s, master_lp=49.960s`。与第 114 节那个误开 hard subtree 的完整 run 对比，hard subtree 为 `solve=786.962s, nodes=117, pool=225574, exact=296.134s, heuristic=201.021s, master_lp=106.046s`。这组数字说明 hard subtree 确实减少了处理节点数，但没有带来总时间收益，反而让 LP、pricing 和列池都变重。

当前对原因的判断是，hard subtree 把 completion-bound/subtree 推出来的 arc fixing 永久写成 forbidden arc，会直接影响 restricted column 兼容性和后续子节点继承列。这样做会让 RMP 中可继承的历史列信息变少，一些本来可作为稳定基或 seed 的列被硬删，repair 和 pricing 需要重新补更多替代列。节点数虽然减少，但每个节点的信息更不完整，LP 更难、RMIH repair 更容易介入、pricing 也更频繁地补列，因此整体更慢。

pricingOnly subtree 的语义更稳：它只让 pricing 和 completion-bound 在当前图上避开这些推断禁弧，不把它们上升为正式分支状态，也不强行删除 RMP 已有历史列。这样可以保留主问题列池和 dual 信息的连续性，同时让 pricing 图受益于删边。由此当前默认建议仍是 pricingOnly；hard subtree 只作为对照或诊断，不作为主线配置。

117. 2026-06-17 当前主线效率审计

对当前主线做静态检查后，暂未发现默认求解路径里仍有明显误开的 debug/统计污染。`PiecewiseLinearFunction.getSegmentNum()` 的 debugMap 写入已由 `Configure.debugPWLFSegmentStats` 控制，completion-bound 的 segment/merge timing 诊断也默认由系统属性关闭；partial-list dominance 的 cardinality skip 统计同样需要显式打开。当前工作区仍有若干历史未提交测试文件和 `GCBBFullDomainComparisonTest` 的 incumbent audit 开关变更，但它们不属于主求解热路径。

后续最明确的效率优化目标有三个。第一是 completion-bound/PWLF 合并：当前 `mergeMinimum()` 已有 no-change 快路径，但一旦需要合并仍会复制右函数并 normalize；40-2 当前主线日志显示 completion-bound build 累计仍约 39 秒，是 exact pricing 里的主要可控成本之一。下一步如果要继续做，应优先实现 completion-bound 专用的安全 destructive/right-consume merge 或 no-copy merge，而不是继续加粗粒度 delta hull。第二是 heuristic seed 选择：`HeuristicPricingEngine.collectSeedColumnsBySortedPrefix()` 每次 heuristic pricing 都会给当前 restricted columns 全量计算 reduced cost 并排序，heuristic 在 40-2 当前 run 中仍有 `105.163s/1170`，可以考虑在同一个 LP dual 下缓存 seed 排序，或用 bounded top-K 结构减少全排序成本。第三是 LP/RMIH 的列-约束构造：`TWETColumn.visitsArc()` 仍按序列线性扫描，LP 中构造 arc/adjacency/cut 行和增量加列时会重复调用；若以后 active branch/cut 增多，可在 `TWETColumn` 内缓存 arc bitset/adjacency key/SRI coefficient，避免反复扫序列。

短期不建议优先动 hard subtree、SRI 或新的分支策略。当前证据显示 hard subtree 会让主问题继承列信息变少而变慢；SRI/lm-SRI 在 30/40 算例上提高 bound 的同时显著增加 label 和 dominance 状态复杂度，暂不适合作为默认提速方向。当前最稳的主线仍是 normal ng-DSSR nearestK8/top10、pricingOnly subtree、completionBound allCycles、midpoint probe/reuse、lazy duplicate replacement。

118. 2026-06-17 heuristic seed 与 RMIH repair 后续优化判断

继续拆解第 117 节的第二、第三个优化点后，当前判断是 heuristic seed 选择暂时不适合作为第一优先级。现在 heuristic pricing 的 seed 不是按正值列优先，而是对当前 `restrictedColumnIds` 在当前 LP dual 下计算 reduced cost，按 reduced cost 从小到大选前若干条兼容 seed。正值列只是在 reduced cost 排名靠前时自然进入，并没有单独前置。这样做的原因是 pricing 的目标是找负 reduced-cost 邻域起点，而不是复用当前基解；每次加列后 RMP 会重新求解，dual 也会变化，所以跨 LP 轮次缓存 seed 排序并不安全。可以做的低风险优化只是把全排序改成 bounded top-K，减少排序成本，但仍必须扫描当前 restricted columns 并计算 reduced cost；如果日志没有证明排序本身是大头，收益可能有限。

RMIH repair 的删除 job 成本评估更适合做小改。当前 fallback repair 在判断“删哪个重复 job 损失最小”时，会反复调用 `TWETColumnEvaluator.evaluate()` 评估当前序列和删点后序列；后续真正加入 repair column 时还可能再次评估同一个新序列。这里可以改成 lazy cost cache：每条当前序列维护一个 current cost，原始未删时直接用 `TWETColumn.getCost()`，某一轮处理重复 job 时只对包含该 job 的当前列评估一次删点后序列，并把该 job 局部的 removed cost 暂存；真正删除后更新 current sequence 和 current cost，进入下一轮重复 job。这个改动不改变启发式选择规则，只减少重复 evaluator 调用。考虑到很多节点里重复 job 数量可能很少，不建议预先给所有列、所有可能删点做全量预处理；按重复 job 懒计算更稳。

`TWETColumn` 的 arc/adjacency 缓存暂时不作为当前优化项。它可以把 `visitsArc()` 从线性扫描变成 O(1)，但会给大列池增加额外内存和构造成本。当前默认关闭 adjacency 分支，active branch/cut 数量通常不高，LP 构造中的线性扫描还不是已经被日志确认的大头。后续如果 active cut/branch 变多，或者 LP 构造耗时在 summary 中明显上升，再考虑用轻量 packed arc key 缓存，而不是现在就给所有列加 HashSet。

119. 2026-06-17 completion-bound 专用 no-copy merge 试验结论

按第 117 节的第一个优化方向，本轮只试了 completion-bound 专用的 destructive/right-consume merge。实现方式是在 `PiecewiseLinearFunction.mergeMinimum()` 外增加仅供临时候选函数使用的入口，让 completion-bound DP 在合并临时 candidate 时不再复制右函数 segment 链；公共 `mergeMinimum()` 仍保持复制右参数的安全语义，避免 dominance graph 或 label/envelope 缓存被物理拼接破坏。为了保证语义，`targetByJob[job]` 为空时直接接管 candidate，非空时只在 completion-bound 路径调用 right-consume；诊断 `diagnosticChangeSource` 开启时仍走旧 copy 口径，避免诊断需要读取候选函数时被破坏。

验证上，focused `javac` 通过，20/30 根节点 smoke 均保持 `valid=true`。随后用隔离 worktree 在同一份 40-2 zero setup root-only 配置上做 A/B：旧 copy 口径为 `solve=82.048s, exact=24.686s`；right-consume 口径重跑为 `solve=94.167s, exact=27.237s`。两者列数、bound 和有效性一致，但 right-consume 没有稳定减少 completion-bound build 时间，部分轮次反而更慢。结合此前 merge timing 中 copy 本身占比很小的证据，当前判断是该优化点不是主瓶颈；省掉右参数复制不足以抵消链表接管后可能带来的局部性/JVM 分配行为变化。

因此本轮不保留该代码改动，`PiecewiseLinearFunction` 和 `CompletionBoundCalculator` 已恢复到原 copy merge 语义。后续若继续优化 completion-bound，应优先沿着 no-change 快路径、减少 full merge 次数、降低 backward envelope 的有效段数和避免不必要 normalize 方向继续，而不是先做 destructive merge。

120. 2026-06-17 当前效率优化阶段性判断

结合前面几轮实验，当前主线已经没有明显“改一小处就稳定提速”的低风险优化点。debug/统计污染已经清理，duplicate signature 候选保留策略已经带来主要搜索树收益，completion-bound 的 no-change 快路径和段压缩已经有效；本轮试过 no-copy/right-consume merge 后确认 copy 不是当前主要瓶颈。继续在 PWLF merge 上做更激进的修改，风险会明显高于预期收益。

剩余可能优化方向仍然存在，但都不是马上应动的项。heuristic seed 的 bounded top-K 可能减少排序成本，但还没有证据说明排序是大头；RMIH repair 的 lazy cost cache 只在重复 job 较多时可能有用；`TWETColumn` arc/adjacency 缓存需要 active branch/cut 足够多才划算；SRI/lm-SRI/arc-memory cut 能增强 bound，但在当前实现里会显著增加 pricing 状态和 label 压力，不适合作为默认提速方向。subtree hard-on 已经证明会破坏列继承信息，默认仍应使用 pricingOnly。

因此当前建议先保持 normal ng-DSSR nearestK8/top10、completionBound allCycles、pricingOnly subtree、midpoint probe/reuse、lazy duplicate replacement 这条主线。后续如果继续优化，应先通过具体日志定位新的瓶颈，再做针对性修改；不要再凭直觉加入大改动。

121. 2026-06-17 旧 VRP GCNGBB 的 ng-DSSR bound 更新机制

旧 VRP 的 `BPC/GC/GCNGBB.java` 在每次 `Extend()` 开始时先构造四套松弛 completion bound：正向/反向时间 bound `m_ft_bound/m_bt_bound`，正向/反向容量 bound `m_fc_bound/m_bc_bound`，以及对应的 SRI 口径 `m_ftsr_bound/m_btsr_bound/m_fcsr_bound/m_bcsr_bound`。这些初始 bound 是不带 ng-memory 状态的松弛 DP，按 customer + 单资源索引记录最小 reduced cost，并通过 2-cycle-free 的 second-best 处理避免同一前驱/后继立即回退。

随后每轮 DSSR 都会执行 `FWExtend()`、`UpdateFWBound()`、`BWExtend()`、`UpdateBWBound()`、`Join()`、`UpdateNGSet()`。`FWExtend/BWExtend` 用时间作为半域资源，正向只扩到 `T/2` 内，反向也只把队列继续扩展到 `T/2` 内；但保留下来的 label 同时带有时间、容量、成本、SRI 状态和 ng-memory。`UpdateFWBound/UpdateBWBound` 会按 terminal customer 扫描本轮保留下来的半域 label，把同一 terminal、同一时间或同一容量下的最小 label cost 写入临时数组，再用 `Math.max(oldBound, labelBound)` 抬高原松弛 bound，同时保持资源单调性。这里的“更新容量 bound”不是从时间 bound 推出来的，而是同一批半域 label 额外按容量维度投影得到的经验下界。

该更新是安全的直觉在于：初始 bound 是 ng-relaxation 更松的下界；DSSR 更新后的 ng-set 只会更强，后续可行补全集合只会变小。对某个 terminal 和资源消耗，当前半域 labeling 已经在同样半域限制和当前 ng-set 下给出一批真实 label，下界不能低于这些 label 的最小可达成本。旧代码用 `max(oldBound, labelMin)` 抬高，因此只加强，不会降低。时间作为半域资源只决定 label 生成范围；容量 bound 的加强来自这些 label 的容量投影，所以不会要求容量本身也作为半域切分标准。

旧实现是 node join：正向和反向在同一个 `cid` 汇合，join 时先把反向 label 中的汇合点 visit/memory 清掉，避免汇合点重复计数，然后检查 memory 冲突、容量、时间和 SRI 合并修正。bound 表不是“只存前半段供前半段用”，而是正向 label 更新正向 bound，反向 label 更新反向 bound；正向扩展时用反向 bound 剪未来 suffix，反向扩展时用正向 bound 剪未来 prefix。即使两边 label 都只扩到半域内，它们仍覆盖 node join 所需的两侧半路径成本信息，因此对剪枝有用。

122. 2026-06-17 旧 VRP 半域更新 bound 与 arc fixing 的适用边界

旧 VRP 中正向扩展比较的是新 label 在 customer `i` 完成服务后的时间：`lbtime + service_i <= max_time / 2`。其中 `lbtime` 是到达 `i` 后取 `early_i` 等待修正后的开始服务时间。反向扩展的 `lbtime` 不是正向时钟，而是从 sink 反推的 suffix 时间资源；代码先检查 `early_i + service_i + suffixTime <= sinkLate`，再用 `max(suffixTime, max_time - (late_i + service_i))` 把其转成可和半域比较的反向资源，入队时要求 `lb.m_time < max_time / 2`。因此两边比较的都是各自方向下“当前半路径占用的时间资源”，不是同一个正向完成时刻。

`UpdateFWBound/UpdateBWBound` 中用半域 label 投影更新 capacity bound，只适合当前 bounded bidirectional pricing 的局部剪枝，不能直接拿去做全局 arc fixing。原因是更新后的 `m_fc_bound/m_bc_bound` 已不再是“只给定 capacity=d 时所有可行 prefix/suffix 的纯容量松弛最小成本”，而是被当前半域 labeling、当前 ng-set 和当前方向状态过滤后的经验下界。它通过 `max(oldBound,labelMin)` 抬高原松弛 bound，在半域 join 语义下剪 label 是安全的；但如果把这个被半域条件抬高过的 capacity bound 用到 `LB(arc) >= UB` 这类永久删弧判断，就可能把那些需要另一种时间切分、但容量相同的可行 route 排除掉，从而产生误删。

因此旧 VRP 的更新 capacity bound 可以用于 `FWExtend/BWExtend` 内部的“当前 label + 另一半 bound 是否仍可能负 reduced cost”判断；若要做 arc fixing，只能使用未被半域 label 更新污染的全域松弛 bound，或重新构造显式覆盖所有时间切分情况的 arc-specific bound。不能把 `UpdateFWBound/UpdateBWBound` 后的 capacity 表直接当作全局 capacity 最小成本表。
### 2026-06-18 旧 VRP GCNGBB 的半资源扩展停止口径

复查旧 VRP `BPC/GC/GCNGBB.java` 后确认，它的 bounded bidirectional labeling 主要用时间资源做半域停止，不用 `capacity/2` 停止扩展。构造函数中 `max_time` 取 depot/sink 的 late time，`time_bound` 同样取该值，`capacity` 仍是车辆完整容量。

forward 扩展时，候选 `i` 的 `lbtime` 表示到达并开始服务 `i` 的时间。代码先要求 `lbtime <= late_i`，再取 `max(lbtime, early_i)`，随后如果 `lbtime + service_i > max_time/2 + tolerance` 就直接丢弃该候选。因此 forward label 一旦保留下来，其服务完当前点的时间不会超过半时间域；旧代码里 `lb.m_time < max_time/2` 的入队判断已被注释掉，实际停止由候选构造阶段的半时间检查完成。

backward 扩展不在候选构造时按 `max_time/2` 丢弃。它先检查把 `i` 接到当前 suffix 前面后是否仍能满足全局 sink late time，再把 `lbtime` 修正为满足 `i` 的 late time 的最小 suffix 时间。新 backward label 会先进入 dominance/table 逻辑；只有 `!BWIsDominate(lb, lp) && lb.m_time < max_time/2` 时才加入 `BWUL` 继续向前扩展。也就是说，`m_time >= max_time/2` 的 backward label 可以保留在 `BWTL` 里用于最终 node join，但不会继续扩展。

容量资源在旧实现中没有“一半容量停止”逻辑。扩展时只计算 `lbweight = label.m_weight + demand_i`，并用 capacity bound 做 lower-bound 剪枝；join 时检查 `forwardWeight + backwardWeight - demand_shared <= vehicleCapacity`。每轮 DSSR 后的 `UpdateFWBound/UpdateBWBound` 会用半时间域内生成的 label 同时投影更新时间 bound 和 capacity bound，但这个 capacity bound 是 bounded pricing 内部的辅助剪枝，不代表用容量一半截断过 labeling。

旧 VRP 的 bound 更新分两层。第一层是在 `Extend()` 开头重新按当前 dual 和当前 node 禁弧构造四套初始松弛 bound：`BoundFTExtend()`、`BoundBTExtend()`、`BoundFCExtend()`、`BoundBCExtend()`，分别表示从 source 正向到 customer 的时间/容量 bound，以及从 sink 反向到 customer 的时间/容量 bound。它们都是基于非 elementary 松弛 DP 递推得到的 lower bound，并用 `m_sec_bound/m_bd_fid` 保留第二小值以避免直接 2-cycle。构造后还做 prefix-min，使资源上限更松时 bound 不比更紧时差。SRI 版本的 `*_sr_bound` 初始复制普通 bound。

第二层是在每轮 ng-DSSR labeling 后，用真实半域 label 反向抬高这些初始 bound。`UpdateFWBound()` 扫描所有 forward label table。对每个 terminal customer，把相同时间 `t` 下的最小 `m_nosr_redcost` 写入临时 `m_lbt_bound[t]`，把相同容量 `cap` 下的最小 `m_nosr_redcost` 写入 `m_lbc_bound[cap]`；带 SRI 的临时 bound 则用 `label.m_reduced_cost`。随后用 `Math.max(oldBound, labelDerivedBound)` 抬高 `m_ft_bound/m_fc_bound`，再用相邻资源上的 prefix-min 保持单调。`UpdateBWBound()` 对 backward label table 做同样操作，更新 `m_bt_bound/m_bc_bound`。因此这里不是重新求一次 DP，而是用已经生成的 bounded labels 给原来的 relaxed bound 加强。

这种更新的语义是：半域 labeling 已经比初始松弛 DP 多考虑了 ng-memory、真实可达、dominance 后保留下来的状态和当前半时间限制。在同一个 bounded pricing 口径下，如果某个 `cid, resource` 的 label 最小 reduced cost 已经高于初始 bound，就可以把 bound 抬高到这个值；之后同轮或下一轮 DSSR 的扩展剪枝会更强。它不能被解释成全局最小补全成本，也不能直接拿来做全局 arc fixing，因为它受半时间域和当前 DSSR label 集合限制。

这里还要注意 backward label 的 `m_time` 不是 job 的服务开始时间。sink label 初始 `m_time=0`；若把 job `i` 接到当前 suffix 的最前面，先计算 `lbtime = label.m_time + service(currentCid) + dist(i,currentCid)`，这表示从 `i` 服务完成后到 sink 还需要的尾部时间，当前 suffix 首点的服务时间在这一项里补上。随后检查 `early_i + service_i + lbtime <= sinkLate`，确保 `i` 即使最早开始也能接上该 suffix；再做 `lbtime = max(lbtime, max_time - (late_i + service_i))`，把 `i` 的 late time 约束折算成尾部时间下界。因此 backward 的 `m_time` 可以理解为“从当前 cid 服务完成之后，到 sink 至少还需要预留的后缀时间”，不含当前 cid 自身服务时间。final join 中用 `forward.m_time + backward.m_time + service(sharedCid) <= max_time`，正好对应 forward 到 sharedCid 的服务开始时间，加 sharedCid 服务时间，再加 backward suffix 的尾部时间。

123. 2026-06-18 TWET 中基于半域 label 更新 completion bound 的可行性分析

沿着旧 VRP 的思路，TWET 里也可以考虑在一次 exact pricing / DSSR 轮次结束后，用已经生成的正向、反向 label 来加强 completion bound。直观做法是：对每个 terminal job `i`，把所有到达 `i` 的 forward label frontier 取下包络，得到当前半域内从 source 到 `i` 的最小 reduced-cost 函数；反向也类似，对到达 `i` 的 backward label frontier 取下包络，表示从 `i` 到 sink 的 suffix 最小函数。这个 label-derived envelope 可以和现有 relaxed completion bound 做同语义函数的逐点 `max`，因为二者都是下界，取更大的下界只会增强剪枝。

但是这个 bound 的适用范围必须限定得很窄。它依赖当前 `Tmid`、当前 pricing horizon、当前 dual、当前 node 禁弧、当前 ng-set 和当前 cut/SRI 状态；一旦 RMP 加列重解、cut 增加、DSSR 更新 ng-set 或 midpoint probe 换了 `Tmid`，上一轮 label envelope 就不能当成全局 completion bound 复用。更重要的是，它只能在“本轮 exact labeling 确实完整展开，没有被候选列上限、probe 截断或早停截断”的前提下用于证明性剪枝。否则这个 envelope 只是已有 label 的经验包络，不覆盖未生成状态，拿来 `max` 会把下界抬高到不安全的位置。

这个思路和当前 `CompletionBoundCalculator` 的四类函数语义也必须严格对齐。当前代码里 `forwardFByJob/backwardBByJob` 更像完整 prefix/suffix 函数，而 `forwardUByJob/backwardRByJob` 是经过一步转换后用于另一侧 label 剪枝的函数。若要从 label envelope 往外沿可扩展边扩展一步，不能简单说“只平移、不加 job 惩罚”就一定安全；是否加入 `jobPenalty - dual` 取决于目标 bound 对象是否已经由另一侧 label 包含该 job。尤其 `jobPenalty - dual` 可能为负，漏加负项会抬高下界，可能误剪负列。因此实现时必须复用或抽出 `CompletionBoundCalculator` 里构造 `F/U/B/R` 的同一套 helper，先明确每个函数代表的是“到 job 后”“从 job 前”“是否含当前 job reduced penalty”，再做转换。

用户提出的“一步扩展后和现有 completion bound 合并，避免每个 label 每条边都做 bound 比较”在计算上有吸引力。复杂度大致从每个 label 扫很多边，变成每轮 pricing 对每个 job 的 envelope 做一次合并，再按可扩展 arc 做至多 `O(n^2)` 次函数平移和 merge。40/60 任务下这可能划算。但要注意，构造这个增强 bound 本身需要 PWLF 的逐点 `max` 操作，而当前主要成熟的是 `mergeMinimum`；逐点 `max` 不是简单换符号，仍要处理定义域、交点和前/后缀单调化，不能把它混进现有 min-envelope 逻辑里。

这个 label-derived bound 不应直接用于 permanent/pricingOnly arc fixing。arc fixing 要求的是“所有包含该 arc 的完整列 reduced cost 下界”都已超过 gap，而且这个下界不能依赖某个半域切分。半域 label envelope 受到 `Tmid` 影响，可能漏掉另一种 split 下可行且更便宜的列；即使在当前 pricing round 内安全，也不能推出该 arc 在整个 node 中永久无用。因此第一版最多用于当前 exact pricing 内部的 label 剪枝或下一轮同 `Tmid`、同 dual、同状态的 DSSR 剪枝；如果要用于 arc fixing，必须重新构造不依赖半域切分的 arc-specific full-domain bound。

当前可行的实现路线应是实验开关而不是默认主线：先在 forward 完整展开后，用 forward label envelope 加强 backward 扩展会用到的 prefix bound；backward 完整展开后，再用 backward envelope 加强下一轮或 join 前的 suffix bound。所有更新都必须带上“本轮完整 labeling 已结束”的标记，且只在同一 pricing call 内有效。验证上应固定小算例对比开启/关闭该增强 bound 后的负 reduced-cost 列集合、最终 LP bound 和 validator；若出现剪枝导致列减少但 bound 改变，就说明语义越界。当前结论是：这个方向值得作为 completion-bound 的后续实验，但它不是无条件安全的全局 bound，也不能直接用于 UB-LB permanent arc fixing。

进一步讨论后需要修正上一段中过于保守的表述：如果 label-derived bound 保留的是 PWLF 时间函数和真实定义域，而不是像旧 VRP capacity bound 那样把半域信息压成单个资源维度的 scalar 表，那么它有机会用于当前 node 的 reduced-cost arc fixing。关键条件是只能在当前 node 最后一轮 exact pricing closure 后使用，并且只在 label 完整覆盖的时间定义域上做逐点 `max` 加强；定义域外仍保留原来的 full-domain relaxed completion bound。这样对某条 arc `i->j` 做 `min_t F_i(t)+c_ij+B_j(t+delta)` 时，如果最优 `t` 落在未加强区域，就退回原 bound；如果落在加强区域，则该区域的 label envelope 必须已经是同一半域、同一 dual、同一 ng/cut 状态下所有可行 prefix/suffix 的下界。因此它不像旧 VRP 半域 capacity bound 那样天然不能用于 fixing。

但这仍不是“任何时候都能用于 permanent fixing”。安全使用需要满足几条硬条件：第一，labeling 必须是最终 exact 轮次，没有 probe 截断、候选上限导致的状态截断，也没有尚未完成的 DSSR ng-set 更新；第二，`F/U/B/R` 的函数语义必须和现有 completion bound 完全一致，不能把是否包含 job reduced penalty、arc dual、SRI/cut dual 的口径弄混；第三，ng relaxation 下用于 fixing 的 bound 必须仍是 elementary feasible column 的松弛下界，不能因为 SRI memory 或 partial dominance 的状态丢失而抬高；第四，这个 fixing 只能针对当前 node 及其后代在当前 LP lower bound / current dual 证明下成立，不能跨 RMP 重解过程提前使用。满足这些条件时，用“原 full-domain relaxed bound + final label envelope 的定义域内加强”做当前 node 的 arc fixing，理论上可以成立；不满足时只能作为当前 pricing 内部剪枝。

124. 2026-06-18 ng-DSSR 轮内 label-derived completion bound 更新实现

本次先实现最基础的版本：每轮 ng-DSSR relaxed labeling 中，forward 队列耗尽后，用当前保留下来的 forward label 的 no-SRI frontier 按 terminal job 聚合成 envelope，再按现有 `forwardF/forwardU` 的 completion-bound 语义写回当前 bound；随后 backward 扩展可以直接使用这份加强后的 prefix bound。backward 队列耗尽后同理用 backward label 和 backward single-point label 聚合 envelope，并写回 `backwardB/backwardR`。写回方式是逐点 `max(oldBound, labelDerivedBound)`，即只抬高下界，不降低原 relaxed bound。

实现时刻意没有接 arc fixing。原因是本轮 label-derived bound 依赖当前 `Tmid`、当前 dual、当前 ng-set、当前 cut/SRI 状态以及本轮 label 是否完整生成；它先只服务于当前 pricing round 的剪枝。为了防止污染 subtree/permanent arc fixing，代码在更新前会复制一份当前 completion bound；如果发生 label-derived 加强，`reusableSubtreeArcEliminationBounds()` 仍返回原始 relaxed completion bound，而不是增强后的 bound。SRI active 时也仍使用 no-SRI frontier 更新和剪枝，保持“completion bound 不维护 SRI 状态”的松弛口径。

技术上没有改公共 `PiecewiseLinearFunction.mergeMinimum()`。由于这里需要的是逐点最大值，不能通过取负后调用 `mergeMinimum()` 简化，否则 forward/backward normalize 的方向语义会不等价。因此在 ng-DSSR 内部加了一个局部 `pointwiseMaxOnTargetDomain()`，只在现有 bound 的定义域内比较候选 label bound，避免改变全局 PWLF 语义。验证上，`javac` 单独编译 `GCNGBBStyleBidirectionalNgDssr.java` 通过；进一步编译 `src/Common`、`src/Basic`、`src/HEU`、`src/TWETBPC` 主线源码通过。全仓库编译仍被旧 `src/BPC` 包的历史 API 不兼容拦住，和本次改动无关。

125. 2026-06-19 ng-DSSR label-derived completion bound 更新收窄为 U/R

复查第 124 节实现后确认，当前 label-derived bound 真正被 label 剪枝消费的只有 `forwardUByJob` 和 `backwardRByJob`。forward label 的 completion-bound 剪枝查 `backwardRByJob[label.jid]`，backward label 的剪枝查 `forwardUByJob[label.jid]`；对应 scalar cache 也是 `forwardUMin/forwardUBefore` 和 `backwardRAfter`。`forwardFByJob/backwardBByJob` 主要服务于 completion-bound arc fixing、subtree elimination 和 argmin 诊断，而当前实现刻意不把 label-enhanced bound 写回 reusable subtree bound，且 arc fixing 的评估发生在 label-derived 更新之前，因此轮内维护 `F/B` 基本不会影响实际剪枝。

因此本次把 ng-DSSR 轮内更新收窄：forward 队列耗尽后只用 forward label envelope 沿一条可扩展弧生成 `U_j` 并逐点 `max` 加强 `forwardUByJob[j]`；backward 队列耗尽后只生成 `R_j` 并加强 `backwardRByJob[j]`。不再构造 `F_j/B_j`，不再为此复制 job penalty 函数，也不再重建 `forwardFMin/backwardBMin` cache。这样保留了当前剪枝需要的下界强化，同时减少无用 PWLF add/normalize/merge 和 cache 计算。若以后要在最终 exact closure 后做 label-derived arc fixing，再单独恢复并严格验证 `F/B` 口径，而不要和当前基础剪枝路径混在一起。

随后进一步收窄 backward 更新口径：轮内 label-derived 更新只应来自本轮实际生成的普通 job label envelope，不能为了对称额外构造 `job -> sink` 的 sink 边界候选。`job -> sink` 已经属于 base completion bound 的边界初始化，sink 本身没有普通 label，也不参与 envelope 聚合。因此当前 backward 更新只遍历普通 `job -> successor` 弧，用 `B_successor` 往前推出 `R_job`；直接到 sink 的下界仍由原始 relaxed bound 保留，不在 label-derived 更新中重复维护。

126. 2026-06-19 label-derived completion bound A/B 诊断

继续对第 125 节的 U/R 轮内更新做小规模 A/B 后，当前结论偏负面：该更新确实会改变部分 completion-bound 函数，但在已经测试的根节点样本上没有转化为实际剪枝收益，反而增加了少量 exact pricing 时间。因此它暂时不适合作为默认主线，只保留为诊断开关 `ngDssrLabelDerivedCompletionBoundUpdate`。

20 任务 `tmp-wet020_001_2m` 根节点，normal ng-DSSR nearestK8/top1、allCycles、heuristic/RMIH 打开时，关闭更新得到 `solve=1.402s, exact=0.168850s, fw/bw kept=39/24, completionBound fwPruned/bwPruned=487/354, scalar pruned=349`；开启更新后得到 `solve=1.464s, exact=0.180824s`，label、completion-bound pruned 和 scalar pruned 完全一致，只多出 `completionBoundLabelUpdate=7.825ms, fwChanged=20, bwChanged=121`。这说明函数被抬高了，但没有改变任何剪枝决策。

30 任务 `tmp-wet030_from040_010_2m` 根节点同配置下，关闭更新得到 `solve=6.208s, exact=0.947s/3 calls, bound=14318`；开启更新得到 `solve=6.452s, exact=1.109s/3 calls, bound=14318`。三次 exact pricing 的 added columns、fw/bw kept、completion-bound pruned、scalar pruned 都与关闭更新一致；仅出现 `completionBoundLabelUpdate` 开销，三轮分别约 `17.840ms/4.883ms/2.667ms`，changed 计数分别约 `30/320`、`30/320`、`30/297`。

从代码时机看，这个结果也合理。forward 队列耗尽后加强 `forwardU`，理论上只可能影响随后 backward 扩展；本次样本中没有观察到 backward label 或 pruning 变化。backward 队列耗尽后加强 `backwardR`，当前轮已经不会再回头扩展 forward，join 也不直接消费这套 `R` 剪枝，所以这部分 changed 在当前轮基本只是诊断信息。由于 `completionBoundsLabelEnhanced` 后不会把增强 bound 暴露给 subtree/permanent arc fixing，且下一次 RMP/pricing dual 变化后也不能跨轮复用，收益窗口本来就很窄。

因此当前处理是：保留实现和系统属性，默认关闭。后续只有在定位到具体难节点、并且能证明 `forwardU` 更新显著减少 backward label 或 DSSR 同轮剪枝时，再考虑重新启用；否则它只是额外 PWLF envelope 聚合和逐点 max 成本。

随后修正了一个实现层面的遗漏：从算法语义看，若同一个 pricing call 内存在多轮 DSSR，上一轮基于较松 ng-set 得到的 label-derived bound，应该可以作为下一轮加强 ng-set 后的合法下界继续使用。原实现为了避免污染 subtree/permanent arc fixing，在更新前把 `completionBounds` 从 `ngDssrReusableCompletionBounds` detach 出来，但没有把 detach 后的增强 bound 保存在本次 pricing call 内，导致下一轮 DSSR 又回到原始 base bound。这和旧 VRP 的“每轮 DSSR 后更新 bound，下一轮继续用”口径不一致。

当前修正为维护两份引用：`ngDssrReusableCompletionBounds` 仍保存原始 relaxed base bound，只供 subtree/pricingOnly arc fixing 复用；`ngDssrEnhancedCompletionBounds` 保存本次 pricing call 内的 label-derived 增强 bound，下一轮 DSSR 初始化时优先使用它。这样如果开关打开，增强 bound 会跨 DSSR 轮生效，但仍不会暴露给 subtree/permanent arc fixing。

修正后重跑 30 任务 `tmp-wet030_from040_010_2m` 根节点，开启更新得到 `solve=7.604s, exact=1.249s/3 calls, bound=14318`。第一轮 exact 有 `completionBoundLabelUpdate=26.320ms, fwChanged=30, bwChanged=320`；后两轮更新计数变为 `0/0`，说明增强 bound 已被下一轮继承。但三轮的 label 数、completion-bound pruned、scalar pruned 和生成列数仍与关闭更新一致。因此新的结论是：跨 DSSR 轮复用语义已修正，但当前样本仍没有显示实际剪枝收益，默认关闭的判断不变。

127. 2026-06-19 当前 completion bound 更新和 arc fixing 使用口径澄清

当前 ng-DSSR 内部存在两类 completion bound。第一类是 base bound，即 `buildCompletionBounds()` 用当前 LP dual、node 禁弧、pricing horizon 和 completion-bound relaxation 构造出来的全域松弛下界。这份 bound 写在 `ngDssrReusableCompletionBounds` 中，在同一次 pricing call 的 DSSR 多轮之间复用，也可以在满足条件时提供给 subtree/pricingOnly arc elimination。

第二类是 label-derived enhanced bound，即开启 `ngDssrLabelDerivedCompletionBoundUpdate` 后，用本轮已经完整生成的 forward/backward label envelope 对 `forwardUByJob/backwardRByJob` 做逐点 `max` 抬高后的 bound。这份增强 bound 写在 `ngDssrEnhancedCompletionBounds` 中，只在同一个 pricing call 内跨 DSSR 轮复用。它服务于后续 DSSR 轮的 label 剪枝，不作为 subtree/permanent arc fixing 的依据。原因是它依赖当前 Tmid、当前 ng-set、当前 cut/SRI 状态和本次 label 是否完整展开，不能直接外溢成全局 arc fixing 证据。

pricing 轮内的临时 completion-bound arc fixing 仍在 `buildCompletionBounds()` 后立即执行，使用的是当时的 `completionBounds.forwardFByJob/backwardBByJob` 和 scalar min cache。它比较的是“某条 arc 在当前 pricing 中是否还能出现在负 reduced-cost 列里”，cutoff 约为 0，因此只影响当前 exact pricing 的扩展；如果 `bidirectionalCompletionBoundArcFixing=true`，会把这些 arc 记到 `completionBoundFixedArc`，后续 `isPricingArcForbidden()` 在扩展时避开。这里没有使用 label-derived enhanced U/R，因为 label-derived 更新发生在 forward/backward labeling 之后。

subtree/pricingOnly arc elimination 是另一层。`Tree/PC` 在 node 处理后调用 `getReusableSubtreeArcEliminationBounds()` 取得 prepared bounds，再用 incumbent 和 node lower bound 的 gap 做判断。ng-DSSR 的 `reusableSubtreeArcEliminationBounds()` 会检查：如果当前 `completionBoundsLabelEnhanced=true`，则返回 base bound `ngDssrReusableCompletionBounds`，而不是 enhanced bound；如果存在 dual profitable window、zero-dual excluded jobs 或 pricing horizon 不是 `data.CmaxH`，则直接返回 null。这样可以保证 subtree/pricingOnly arc elimination 不被半域 label-derived bound 污染。

128. 2026-06-19 label-derived bound 正确性与收益测试

按“是否正确、是否有用、每次更新是否耗时”重新测试后，先发现一个实现问题：011 根节点开启 `ngDssrLabelDerivedCompletionBoundUpdate=true` 时，`aggregateForwardNoSriEnvelopeByJob()` 会把一个数值退化的 no-SRI frontier 传给 `mergeMinimum()`，报错区间为 `this=[497.0,503.9999999999993], g=[504.0,503.9999999999993]`。这说明 label-derived envelope 聚合路径不能只检查 head/tail 非空，还必须排除没有正长度定义域的 PWLF。修复方式是在该强化路径局部增加 `hasPositiveDomainFunction()`，只过滤 label-derived envelope 和 candidate 构造，不改变普通 single-point label、join 或主 pricing 的函数语义。

修复后，011 根节点开启更新可以正常跑完，结果与关闭更新一致：`status=NODE_LIMIT, incumbent=11987, bound=11502.945946, valid=true`。010 根节点同样保持 `ROOT_PROCESSED, incumbent=bound=14318, valid=true`。因此当前至少在 010/011 根节点上没有观察到开启强化导致 bound 或列验证错误。

从收益看，当前仍没有看到实际剪枝改善。010 根节点 off/on 的三轮 exact 中，`fw/bw kept`、`completionBound fwPruned/bwPruned`、`completionBoundScalar pruned`、candidate pool 和 DSSR rounds 全部一致；开启时第一轮 `completionBoundLabelUpdate=18.104ms, fwChanged=30, bwChanged=320`，后两轮因继承增强 bound 分别约 `2.147ms/0/0` 和 `2.446ms/0/0`。011 根节点修复后，开启时五轮更新耗时约 `23.544ms, 7.060ms, 6.359ms, 6.303ms, 2.548ms`，但所有轮次的 label 数、completion-bound pruned、scalar pruned 和生成列数仍与关闭更新一致。

耗时判断也要谨慎。011 第一次 off run 为 `solve=19.555s, exact=3.617s`，随后 off rerun 变为 `solve=9.832s, exact=1.648s`；on 为 `solve=11.840s, exact=1.893s`。这种差异主要来自 `completionBound buildMs` 和运行环境波动，而不是 label-derived 更新本身，因为剪枝统计没有变化。按日志直接可归因的更新开销是每次 pricing 几毫秒到二十几毫秒，规模不大，但在没有剪枝收益时就是纯额外成本。

当前结论：label-derived bound 的跨 DSSR 轮复用语义已经修正，空定义域 crash 也已修复；但在 010/011 根节点样本上，它没有减少 label、没有增加 completion-bound 剪枝、也没有改善生成列路径。默认保持关闭是合理的。后续若要继续研究，应选择已知难节点快照，观察开启后是否真的减少 backward/forward label，而不是只看 solve time。

随后进一步修正更新语义。原实现虽然先对到达每个 `prevJob/successor` 的 label 做 envelope，但往目标 `job` 扩展一步后，是把每个 `prevJob -> job` 或 `job -> successor` candidate 逐个拿去和现有 bound 做 `max`。这不符合下界语义：对于同一个目标 `job`，多个前驱/后继 candidate 表示可选补全方式，应该先对它们取下包络 `min`，得到该目标 job 的最便宜 label-derived candidate envelope，再与原 completion bound 逐点 `max`。否则一个较差前驱 candidate 也可能把 bound 抬高，理论上有误剪风险。

当前已改为两层聚合：先用当前 label 得到 `F_label[prevJob] / B_label[successor]`；再把所有扩展到同一个目标 `job` 的 candidate 按方向取 `mergeMinimum`；最后每个目标 job 只用这个聚合后的 candidate envelope 调一次 `strengthenCompletionBoundWithMax()`。因此 `fwChanged/bwChanged` 现在表示“有多少个目标 job 的聚合 candidate envelope 抬高了 bound”，不再是逐 arc candidate 的成功次数。

按修正后代码重跑开启强化：010 根节点 `ROOT_PROCESSED, incumbent=bound=14318, valid=true`，三轮更新分别为 `9.475ms/21/20`、`2.143ms/13/0`、`1.487ms/0/0`；011 根节点 `NODE_LIMIT, incumbent=11987, bound=11502.945946, valid=true`，五轮更新约为 `15.523ms/30/27`、`10.482ms/30/23`、`7.372ms/30/26`、`6.423ms/30/26`、`2.377ms/0/0`。相比旧的逐 candidate 口径，changed 计数显著下降且语义正确；但 010/011 的 label 数、completion-bound pruned、scalar pruned 和生成列数仍与关闭更新一致，暂未体现剪枝收益。

最终按同一组命令重新做 off/on A/B：010 关闭为 `solve=6.614s, exact=1.115s/3`，开启为 `solve=6.720s, exact=1.113s/3`；011 关闭为 `solve=9.292s, exact=1.794s/5`，开启为 `solve=10.534s, exact=1.867s/5`。两组的 `fw/bw kept`、`completionBound fwPruned/bwPruned`、`completionBoundScalar pruned`、candidate pool、DSSR rounds 和最终 bound 都完全一致。开启时 010 的更新耗时分别为 `9.155ms, 4.814ms, 3.638ms`，011 为 `19.953ms, 5.938ms, 9.697ms, 2.976ms, 1.424ms`。因此在这两个 30 任务根节点上，修正后的强化 bound 仍只是抬高了部分函数，没有产生可观测剪枝收益；默认关闭仍是当前最稳妥的设置。

129. 2026-06-19 011 完整收敛对照：label-derived bound 开关

按同一套当前主线配置把三角化 011 完整跑到收敛：normal ng-DSSR、nearestK8、每轮 top1 更新、allCycles completion bound、scalar bound、completion-bound arc fixing、pricingOnly subtree、ALNS、RMIH 4s、heuristic pricing、midpoint probe/reuse 和 node progress summary。两组唯一差别是 `ngDssrLabelDerivedCompletionBoundUpdate` 开关。

关闭 label-derived 更新时，结果为 `FINISHED, incumbent=bound=11546, nodes=11, pricing=244, pool=9068, solve=73.179s, root=13.556s, heuristic=14.407s/176, exact=32.394s/68, master_lp=4.983s, valid=true`。日志为 `test-results/bpc/tmp-label-bound-full-false-011/tmp-wet030_from040_011_2m-halfDomain-ng-nearestK8-top1.log`。

开启 label-derived 更新时，结果同样为 `FINISHED, incumbent=bound=11546, nodes=11, pricing=242, pool=9066, solve=85.158s, root=12.288s, heuristic=16.902s/175, exact=38.561s/67, master_lp=5.649s, valid=true`。日志为 `test-results/bpc/tmp-label-bound-full-true-011/tmp-wet030_from040_011_2m-halfDomain-ng-nearestK8-top1.log`。

从汇总统计看，开启更新后 pricing call 和 exact call 略少，DSSR rounds 从 `216` 降到 `208`，候选 kept 从 `534` 降到 `532`，forward/backward kept label 总量也略降；但 exact pricing 总耗时反而增加约 `6.17s`，heuristic 也增加约 `2.49s`。直接可归因的 `completionBoundLabelUpdate` 累计只有约 `0.283s`，说明慢点不是更新函数本身，而是增强 bound 改变了部分列生成路径、LP dual 和后续节点难度，尤其 node6 的 exact pricing 从关闭时约 `13.416s/8` 增至开启时约 `19.615s/8`。

因此完整 011 收敛实验没有支持开启该更新。它在当前实现下能保持正确性和最终 bound 一致，但没有稳定减少 label 或总耗时，反而可能通过路径扰动变慢。当前结论不变：`ngDssrLabelDerivedCompletionBoundUpdate` 保留为诊断/实验开关，默认关闭。

130. 2026-06-19 40 任务算例补充对照：label-derived bound 开关

继续按当前主线配置换两个 40 任务算例测试 `ngDssrLabelDerivedCompletionBoundUpdate`：normal ng-DSSR、nearestK8、每轮 top10 更新、allCycles completion bound、scalar bound、completion-bound arc fixing、pricingOnly subtree、ALNS、RMIH 4s、heuristic pricing、midpoint probe/reuse、`joinBest=BEST_UB`，只切换 label-derived bound 更新开关。

第一个是 40-2 zero setup 算例 `wet040_001_2m_zeroSetup`。关闭更新时结果为 `FINISHED, incumbent=bound=17881, nodes=14, pricing=277, pool=29777, solve=88.641s, root=60.902s, heuristic=32.890s/198, exact=22.880s/79, master_lp=11.756s, valid=true`；开启更新时结果仍为 `FINISHED, incumbent=bound=17881, nodes=14, pricing=277, pool=29777, solve=51.977s, root=29.994s, heuristic=17.150s/198, exact=13.637s/79, master_lp=8.164s, valid=true`。表面 wall time 差异很大，但内部搜索统计完全一致：`fw/bw kept=24512/2655`、`completionBound fw/bw pruned=33505/6578`、`scalar pruned=8441`、`candidate kept=13440`、`DSSR rounds=113`、`nonElementary=775`、`ngUpdates=160`。开启更新额外产生 `completionBoundLabelUpdate=178.997ms, fwChanged=2401, bwChanged=520`，说明函数确实被抬高，但没有改变任何可观察剪枝或列生成决策；总时间差更可能来自 JVM/缓存/运行环境波动。

第二个是 40-4 算例 `wet040_001_4m`。关闭更新时结果为 `FINISHED, incumbent=bound=8473, nodes=139, pricing=1128, pool=13328, solve=80.490s, root=19.344s, heuristic=20.619s/788, exact=5.646s/340, master_lp=9.227s, valid=true`；开启更新时结果同样为 `FINISHED, incumbent=bound=8473, nodes=139, pricing=1128, pool=13328, solve=81.933s, root=37.541s, heuristic=31.716s/788, exact=6.487s/340, master_lp=6.694s, valid=true`。内部统计同样完全一致：`fw/bw kept=18816/349`、`completionBound fw/bw pruned=21969/13310`、`scalar pruned=21631`、`candidate kept=3024`、`DSSR rounds=366`、`nonElementary=485`、`ngUpdates=37`。开启更新额外产生 `completionBoundLabelUpdate=584.299ms, fwChanged=11407, bwChanged=103`，但仍没有带来 label 数或剪枝数变化。

这两个 40 任务补充对照和前面的 010/011 结论一致：label-derived bound 更新目前语义上可以抬高部分 U/R bound，并且结果保持正确，但在已测样本中没有实际减少 label、completion-bound pruning、scalar pruning、DSSR 轮数或列池规模。它的开销本身不大，但收益窗口很窄；开启后看到的 wall time 差异主要不能当作算法收益证据。因此当前仍建议默认关闭，只在定位具体难节点时作为诊断开关使用。

随后复查发现一个会让上述更新偏弱的定义域问题：forward/backward label 的 frontier metadata 本身就是半域，例如 forward 侧接近 `[0,Tmid]`，backward 侧接近 `[Tmid,T]`；而 `PiecewiseLinearFunction.shiftX()` 在平移后会按函数自己的 `domainStart/domainEnd` 调用 `trimToDomain()`。因此在 label-derived 更新中，如果直接对半域 envelope 做 `shiftX()`，平移后的 candidate 会被原半域 metadata 再裁一次，本该用于加强 full-horizon `U/R` bound 的区间可能提前丢失。

当前修正只作用于 label-derived completion bound 更新路径：构造一跳 candidate 前先复制 label envelope，并把临时函数 metadata 重置为 `[0, pricingHorizon]`，再做 `shiftX()`、`shiftY` 和方向 normalize。这里不填充未知区间，只保留半域 label 真实覆盖区间平移后的物理 segment；最终 `pointwiseMaxOnTargetDomain()` 仍只在当前 base/enhanced bound 的定义域内比较，candidate 未覆盖的区间保持原 bound。因此这个修正不会改变正式 half-domain labeling、join、普通 completion bound，也不会扩大写回 bound 的定义域，只是避免临时 candidate 被半域 metadata 误裁。focused `javac` 通过；010 根节点开启更新的 smoke 保持 `ROOT_PROCESSED, incumbent=bound=14318, valid=true`。前面 130 节的 A/B 仍可说明旧实现下默认开启没有收益，但由于当时 candidate 偏弱，若后续要严肃评估该更新收益，应按修正后代码重新做 off/on 对照。

131. 2026-06-19 label-derived bound 的 full-horizon M gap 修正

继续复查后确认，上一段“只重置 metadata、不填充未知区间”的修正仍然不够。原因是 label envelope 的物理 segment 仍只覆盖半域，例如 forward 在 `[A,Tmid]` 上有定义；即使临时 metadata 改成 `[0,T]`，平移后也只是得到 `[A+delta,Tmid+delta]` 这一段。后续 `normalize(FORWARD)` 看不到 `Tmid+delta` 右侧的 M 段，就不能形成“该 prefix 可以等待到更晚时刻”的前向闭包；backward 方向同理，如果左侧没有 M 段，`normalize(BACKWARD)` 也不能形成向更早时刻的闭包。因此这种 candidate 仍会局限在半域平移片段内，确实很难被另一半 label 查询到。

当前代码把该路径改为 `fullHorizonWithBigMGaps()`：先构造一个 `[0,pricingHorizon]` 的临时 PWLF，把原 label envelope 的真实 segment 裁剪复制进去；对 segment 之间、左端或右端没有物理函数的区间，显式填充斜率 0、截距 `Utility.big_M` 的 M 段。随后再做 `shiftX()` 和方向 normalize。这样 forward 的右侧 M 尾段、backward 的左侧 M 头段都会参与方向闭包；超出真实可达区间的位置一开始只是 M，不会凭空给出便宜下界。最终仍然先按目标 job 对所有 candidate 取 `mergeMinimum`，再和当前 base/enhanced bound 做逐点 `max`，写回范围也仍受现有 bound 定义域约束。

这个修正只影响实验开关 `ngDssrLabelDerivedCompletionBoundUpdate` 下的 label-derived U/R 更新，不改变正式 half-domain labeling、join、普通 completion bound、subtree/pricingOnly arc fixing 或列成本验证。focused `javac` 已通过。需要注意测试属性名是 `twet.bpc.fullDomainCompare.ngDssrLabelBoundUpdate`，不是较长的字段式名称；用正确属性在 010 根节点关闭 midpoint probe 做 smoke，得到 `ROOT_PROCESSED, incumbent=bound=14318, solve=9.379s, exact=0.644s/1 call, valid=true`，且第一轮出现 `completionBoundLabelUpdate=11.307ms, fwChanged=30, bwChanged=0`。相较之前未真正启用或未补 M gap 的 smoke，这次 root 在一轮 exact 中闭合，说明 M gap 修正至少能让该增强 bound 在这个样本上真正被后续剪枝消费。不过该开关仍是实验项，是否默认开启还需要重新做完整 off/on 对照。

随后的 011 root 对照暴露了这个 smoke 结论里的隐藏问题。用同一初始 LP，关闭更新时第一轮 exact 明确生成 50 条负列，best reduced cost 约 `-34.386`；而把 forward label-derived U 在 forward 队列耗尽后立刻用于本轮 backward 剪枝时，backward label 被剪成 `0`，root bound 从关闭时的 `11502.945946` 抬到 `11512.017544`。这不是正常加强，而是同轮误剪：本轮 backward 半路径还没有生成，不能用本轮 forward envelope 抬高 `forwardU` 后立即裁掉它。旧 VRP 的思路也是一轮 labeling 完整结束后更新 bound，供后续 DSSR 轮使用，而不是用半轮结果剪另一半。

当前代码已把更新时机改为：非 probe-rank0 路径先完整耗尽 forward 和 backward 队列，再执行 `updateCompletionBoundsFromForwardLabels()` 和 `updateCompletionBoundsFromBackwardLabels()`；增强 bound 只在同一次 pricing call 的后续 DSSR 轮中复用。修正后重跑 no-probe root 对照：010 开启更新为 `ROOT_PROCESSED, incumbent=bound=14318, solve=12.708s, exact=1.654s/2 calls, valid=true`，关闭时为 `exact=0.990s/3 calls`，说明仍可能减少后续 DSSR 轮；011 开启更新为 `NODE_LIMIT, incumbent=11987, bound=11502.945946, exact=3.255s/5 calls, valid=true`，与关闭时的 root bound 和 exact call 数一致，不再出现错误的 `11512.017544`。因此当前更准确的结论是：M gap 修正本身必要，但 label-derived bound 只能作为完整 relaxed round 之后的 DSSR 轮间加强，不能作为同轮半边剪枝。

这里不能同轮剪枝的原因不是“forward label 不可信”，而是“只跑完 forward 时，它还不是整个 bidirectional relaxed pricing 的完整下界证据”。forward envelope 只覆盖已经由 source 侧半域规则生成并保留的 prefix；而当前 exact pricing 还没有生成 backward suffix，也还没有执行 join。某个完整负列可能需要一个 backward label 先生成出来，之后才能和已有 forward prefix 拼接暴露。如果在 backward 生成之前，就用本轮 forward envelope 抬高 `forwardU`，再让 backward 扩展查询这个更强的 `forwardU`，等于让半轮结果证明另一半路径不存在。这在 011 root 中已经实际发生：关闭更新时第一轮 exact 生成 50 条负列，开启同轮剪枝时 backward label 直接变为 0，root bound 被错误抬高。

从语义上说，base completion bound 是一个独立的 relaxed DP 下界，构造时没有依赖本轮还未完成的 label 搜索；因此它可以在 forward/backward 扩展过程中使用。label-derived bound 则不同，它来自当前这一轮已经保留下来的 label 集合。只有当本轮 forward 和 backward 都完整耗尽、且 join/negative route 判定所需的状态都已经被生成后，这个 label 集合才可以作为“本轮 relaxed round 的后验信息”去加强下一轮 DSSR。旧 VRP 的 `UpdateFWBound/UpdateBWBound` 也是在相应一轮 label 扩展完成后更新 bound，主要服务后续 DSSR 轮，而不是用半轮结果裁剪同一轮另一侧。
132. 2026-06-19 CPLEX 单线程对 40-4 ng-DSSR 的影响记录

为确认 CPLEX 内部并行是否影响当前 BPC 测试的稳定性，本次把主 RMP LP 和 RMIH MIP 的 CPLEX `Threads` 都固定为 1。改动只作用在 `LP.buildModel()` 和 `RestrictedMasterIntegerHeuristic.solveOnce()` 中创建 `IloCplex` 后的参数设置；GC/pricing 本身仍然是 Java 单线程流程，completion bound、ng-DSSR labeling 和启发式 pricing 不受该参数直接控制。

用 40-4 算例 `wet040_001_4m` 做了一次 no-SRI normal ng-DSSR nearestK8/top10 测试。配置包括 allCycles completion bound、pricingOnly subtree、midpoint probe/reuse、ALNS seed、RMIH 4s、关闭无向 adjacency branching、`joinBest=BEST_UB`。结果为 `FINISHED, incumbent=bound=8473, nodes=107, pricing=828, pool=11754, solve=64.668s, root=21.283s, heuristic=19.378s/590, exact=3.267s/238, master_lp=4.829s, valid=true`，日志位于 `test-results/bpc/tmp-wet040-m4-ng-nearest-nosri-cplex1-ng-20260619/`。

这个结果不能直接解释为“单线程比自动线程更快”。历史 no-SRI ng-DSSR nearestK8/top10 记录中，同一输入曾得到 `solve=91.686s, nodes=129, pricing=1147, pool=18612, exact=16.411s/350`；但当前代码已经包含后续的 PWLF 快路径、候选去重保留最小 reduced cost、调试统计清理等修改，列生成路径和节点数都变了，不是只差 CPLEX 线程数的 A/B。当前能确认的是：固定 CPLEX 单线程后，40-4 仍能正常收敛，且在当前代码状态下没有观察到明显变慢；后续若要严格比较线程影响，需要在同一 commit 下只切换 `Threads=0/1` 重跑。

133. 2026-06-30 ng-DSSR 直接返回 ng-relaxed 列的短实验

按“只用 ng 的列，不强制 elementary 列”的想法，新增默认关闭的实验开关 `ngDssrReturnRelaxedColumns`。默认口径仍保持原 DSSR：负的 elementary route 进入 RMP，负的 non-elementary route 只用于更新 ng-set；打开该开关后，负的 non-elementary ng-relaxed route 会直接进入候选列池并返回 RMP，不再只作为 DSSR 收紧证据。该开关只用于诊断对照，结果名会追加 `ngRelaxedColumns`，避免和主线 elementary ng-DSSR 混淆。

用 `wet040_001_2m` 做了一组当前配置下的测试：ALNS seed、启发式 pricing、nearestK8、non-elementary top10、`joinBest=BEST_UB`、allCycles completion bound、pricingOnly subtree、midpoint probe/reuse、dual bound、strong branching、setup cost 系数保持 `0.0`。先保持 route enumeration 打开时，运行到 node 9 后在枚举阶段出现 `OutOfMemoryError: Java heap space`，堆栈位于 `RouteEnumerationEngine.extendState()`。这说明 relaxed route 进入 RMP 后改变了后续节点和枚举触发状态，当前 route enumeration 与该实验口径不稳定，不能把这轮作为完整求解结果。

关闭 route enumeration 后，同一配置完成求解：`FINISHED, obj=bound=22580, solve=271.053s, root=75.348s, nodes=14, pricing=651, pool=61292, heuristic=40.891s/158, exact=22.268s/59, master_lp=120.623s, valid=true`，日志位于 `test-results/bpc/tmp-wet040-m2-ngrelaxed-columns-noenum-20260630/`。可比的当前 elementary ng-DSSR + time-indexed helper 记录为 `solve=148.524s, root=57.374s, nodes=51, pool=52478, heuristic=59.412s/815, exact=39.138s/263, master_lp=9.369s, valid=true`。

因此这个实验的结论比较明确：直接返回 ng-relaxed 列确实减少了 exact pricing call 和节点数，也降低了 exact pricing 总耗时；但它显著增加了 RMP/strong branching 的 LP 负担，master LP 时间从约 `9.37s` 增到约 `120.62s`，总时间反而更慢。再加上 route enumeration 打开时会触发内存问题，当前不适合作为主线。该开关保留为默认关闭的诊断选项，后续若要继续试，应该单独考虑 relaxed 列导致的列池膨胀、重复覆盖系数和枚举兼容性，而不是直接替换 elementary ng-DSSR。

134. 2026-07-02 40-2 ng-DSSR empty1/top1 诊断

按用户要求测试 `wet040_001_2m` 上最小 ng-set 口径：初始 `ngDssrInitialMode=empty`，即每个任务的 ng-set 只含自身；`ngDssrInitialSize=1`；每次 DSSR 只用 1 条最负 non-elementary route 更新 `ngDssrRouteUpdateLimit=1`。其余口径沿用当前 no-strong ng-DSSR 好配置：ALNS seed、启发式 pricing、dual-bound pruning、allCycles completion bound、scalar pruning、completion-bound arc fixing、pricingOnly subtree、midpoint probe/reuse、`joinBest=BEST_UB`、关闭 SRI/cut 和 time-indexed graph pricing。

本次为 ng-DSSR 增加了默认关闭的诊断属性 `twet.bpc.fullDomainCompare.ngDssrSetStats`。打开后，每个 DSSR round 结束会在 pricing summary 中记录当前 ng-set 的 `avg/min/max` 和本轮更新数，用于观察 DSSR 收紧速度。该诊断只读当前 `ngNeighborhoodByJob` 的 cardinality，不改变 labeling、dominance、join 或更新逻辑；默认关闭，避免正常日志膨胀。

完整求解结果为 `FINISHED, obj=bound=22580, solve=252.638s, root=131.192s, nodes=52, pricing=1370, pool=56070, heuristic=107.470s/998 calls, exact=82.249s/368 calls, master_lp=11.385s, valid=true`，日志为 `test-results/bpc/tmp-ngdssr-40-2-empty1-top1-20260702/wet040_001_2m-halfDomain-BEST_UB-ng-empty1-top1.log`。对比同算例 no-strong `nearestK8/top10` 记录 `148.524s, root=57.374s, nodes=51, pool=52478, heuristic=59.412s/815, exact=39.138s/263`，最小 ng-set 并没有减少节点，反而明显增加 root、heuristic 和 exact pricing 时间。

ng-set size 统计显示，最小口径确实保持得很小。全 run 中共解析到 841 个 DSSR round 记录，所有记录的平均 ng-set size 为 `1.155`，最小值始终为 `1`，最大值总体平均 `2.258`，全局最大只到 `6`。按 round 序号聚合看，第一轮平均 size 约 `1.040`，第二轮约 `1.120`，第三轮约 `1.211`，第八轮约 `1.498`，最多有一次 pricing call 走到第 12 轮，平均 size 约 `1.625`、max 为 `4`。这说明 top1 更新会让 ng-set 增长非常慢，DSSR 多轮收紧带来的额外 exact calls 抵消了初始 relaxation 更松可能带来的单轮标签减少。

当前结论是：在这个 40-2 原始 setup 算例上，`empty1/top1` 不是更好的默认选择。它能保持 ng-set 极小，但 root closure 和总求解都更慢；相比 `nearestK8/top10`，其主要问题是每次 DSSR 收紧过慢，导致更多 exact pricing 和更多启发式轮次，而不是最终节点数变少。后续若要继续试最小 ng-set，应更可能测试 `empty1/topK` 或 `nearestK1/topK` 这类“初始小、更新快”的折中口径，而不是 `top1`。

135. 2026-07-02 ng-DSSR 在已有 elementary 负列时是否仍更新 ng-set

当前实现采用标准偏保守口径：一轮 relaxed pricing 如果已经找到负 reduced-cost elementary 列，就直接把这些列返回 RMP；只有当没有 elementary 负列、但存在 non-elementary 负 route 时，才用这些 non-elementary route 更新 ng-set。这保证了列生成节奏简单：只要有真实负列，就先加入 master 重解；ng-set 更新只用于排除“当前 relaxed pricing 仍有负 route 但没有真实 elementary 负列”的伪负路，从而完成 exact pricing 证明。

这个口径正确且稳健，但不一定总是效率最优。它的好处是避免过早扩大 ng-set，dominance 和 label 状态不会因为无必要的 memory 增长而变重；同时每次发现真实负列都尽快交给 RMP，dual 可以及时更新。缺点是同一轮已经观察到的高质量 non-elementary 负 route 会被丢掉，下一次 RMP 重解后可能又反复遇到类似伪负路，导致 DSSR 收紧滞后。尤其当每轮只返回少量 elementary 列、而 non-elementary 负 route 很多时，当前策略可能增加 pricing call 数。

更激进但仍可能正确的改法是 hybrid：即使本轮有 elementary 负列，也顺手用本轮记录到的 top-M non-elementary 负 route 更新 ng-set，然后仍返回 elementary 列给 RMP。因为扩大 ng-set 只是收紧 ng relaxation，理论上不会删除任何 elementary 可行列；它只会减少后续 non-elementary 重复路径。风险在效率而非正确性：ng-set 变大后 memory state 更重，dominance 可能变弱、label 数可能上升；同时当前 dual 下观察到的 non-elementary route 在 RMP 重解后未必仍是最关键伪负路。因此该策略适合作为实验开关，而不应直接替换当前默认。

如果后续要试，建议先做两个小口径：第一，只在本轮 elementary 返回列数很少、且 non-elementary route reduced cost 明显更负时才顺手更新；第二，保留当前默认逻辑，只新增 `updateNgSetEvenWhenElementaryFound` 诊断开关，对比 40-2、50-2 以及放大时间算例的 exact calls、label 数、pool、root time 和总时间。若 exact calls 明显下降且 label 数不上升，再考虑是否作为默认策略。

136. 2026-07-02 40-2 ng-DSSR nearestK3/top3 诊断

继续按用户要求测试更温和的小 ng-set 口径：`ngDssrInitialMode=nearestK`、`ngDssrInitialSize=3`，即每个任务初始保留自己和最近邻直到 size 为 3；`ngDssrRouteUpdateLimit=3`，即没有 elementary 负列时用最好的 3 条 non-elementary route 更新 ng-set。其余配置沿用第 134 节同一套 no-strong ng-DSSR 好配置。

完整结果为 `FINISHED, obj=bound=22580, solve=121.924s, root=64.010s, nodes=45, pricing=1067, pool=58052, heuristic=47.207s/805 calls, exact=35.010s/262 calls, master_lp=6.215s, valid=true`，日志为 `test-results/bpc/tmp-ngdssr-40-2-nearest3-top3-20260702/wet040_001_2m-halfDomain-BEST_UB-ng-nearestK3-top3.log`。这比 `nearestK8/top10` 的 `148.524s, root=57.374s, nodes=51, pool=52478, exact=39.138s/263, heuristic=59.412s/815` 更快；也显著优于 `empty1/top1` 的 `252.638s, root=131.192s, nodes=52, exact=82.249s/368, heuristic=107.470s/998`。

ng-set 统计显示，该口径把平均 ng-set 控制在很小范围但收紧速度明显好于 `empty1/top1`。全 run 共解析 498 个 DSSR round 记录，整体平均 ng-set size 为 `3.106`，最小值始终 `3`，全局最大 `6`。按 round 聚合，第一轮平均 `3.029`，第二轮 `3.113`，第三轮 `3.175`，第六轮 `3.382`，最多到第八轮，平均 `3.375`。相比 `empty1/top1` 的 841 个 round 记录和 368 次 exact calls，`nearestK3/top3` 明显减少了 DSSR 收紧轮次和 exact calls，同时没有把 ng-set 扩大到 nearestK8 的水平。

当前初步结论是：在 `wet040_001_2m` 上，`nearestK3/top3` 是比 `nearestK8/top10` 和 `empty1/top1` 更好的折中。它保留了小 ng-set 带来的轻量 memory，同时避免 top1 更新太慢。后续值得在 50-2、放大时间算例和 setupR 系列上复测；如果仍稳定，`nearestK3/top3` 可以作为新的候选默认配置，而不是继续使用 `nearestK8/top10`。
137. 2026-07-02 40-2 nearestK3/top3 根节点与非根节点 exact pricing 差异

继续拆解第 136 节的 `nearestK3/top3` 日志后，可以看到 root 和非根节点的 exact pricing 难度差别很明显。root 上 `GCNGBBStyleNgDssrPricing` 共调用 29 次，耗时约 `22.944s`，生成 274 条 exact 列；同时启发式 pricing 调用 72 次，耗时约 `25.974s`，生成 12704 条列。44 个非根节点合计 exact pricing 调用 233 次，耗时约 `12.066s`，生成 15497 条 exact 列；平均到每个非根节点约 `0.274s` exact 时间，平均每次 exact call 约 `0.052s`。因此非根节点确实普遍很快，root 是 exact pricing 的主要重节点。

差异的主要原因不是非根节点列更少这么简单，而是分支、pricing-only/subtree arc fixing、time-indexed compact window 和 dual-bound pruning 共同缩小了后续 label 空间。root 的典型 exact pricing 中，completion bound 构造约 `0.66s-0.82s`，forward/backward bound 内部合并常在数万次 merge 量级，forward extend candidates 可到 `2万-3.5万`，join groups 也在数千到一万级。非根节点例如 node45，单次 exact pricing 约 `20ms-22ms`，completion bound 构造约 `9ms-10ms`，forward extend candidates 降到约 `5900-6900`，join groups 约 `250-400`，time-indexed scalar 还能额外剪掉约 `193-234` 个状态，forward reachable 平均也从 root 的约 `33-34` 降到约 `16-17`。

此外，这次 run 中有 22 个节点以 `pruned_by_dual_bound` 关闭，说明非根节点不一定都需要完全靠反复加列把 LP 闭合到很深。分支后的可行弧、时间窗和已有 incumbent 共同让 dual bound 更容易直接证明当前节点不可能改进。因此当前观察支持一个判断：ng-DSSR 在 root 上仍然承担最大 closure 压力；非根节点如果能继承足够强的 arc/window 缩减和 dual-bound pruning，单个节点 exact pricing 成本可以非常低。

138. 2026-07-02 DSSR ng-set 更新统计后续方向

后续可以在 ng-DSSR 里继续补一类统计：每轮 DSSR 更新后平均/最小/最大 ng-set 大小、非基本负 route 数量、最终 elementary 负列数量。这个内容属于 ng-DSSR 更新策略本身，不属于 time-indexed root preprocessing，因此记录在本专题下。当前判断是：若初始 ng-set 很小也能快速收敛，说明 ng 更新不需要一次加入很多点；若大量非基本负 route 最后没有转化为 elementary 负列，则说明激进更新可能放大 label 状态、削弱 dominance 或增加 DSSR 轮次。

这类统计可以服务于后续三个参数判断：nearestK/topK 应该取多大、`ngDssrRouteUpdateLimit` 是否需要随实例动态调整、以及是否应优先使用 reduced cost 最好的非基本 route 更新 ng-set。前面 40-2 的 `empty1/top1` 和 `nearestK3/top3` 对照已经说明，过慢更新会显著增加 DSSR 轮次和 exact calls；因此后续更值得试的是“初始小、更新不太慢”的折中口径，而不是单纯把 ng-set 压到最小。

139. 2026-07-02 setupR25/R50/R75 empty1/top1 ng-set 统计实验

按用户要求继续观察最小 ng-set 更新口径在 setupR 系列上的行为。本轮只打开诊断统计，不改变定价逻辑：`ngDssrInitialMode=empty`、`ngDssrInitialSize=1`，即每个任务初始 ng-set 只包含自己；`ngDssrRouteUpdateLimit=1`，即每轮没有 elementary 负列时只用当前 reduced cost 最好的 1 条 non-elementary negative route 更新。其余配置沿用当前 no-SRI/no-partial 的 ng-DSSR 主线加速口径：ALNS seed、启发式 pricing、`joinBest=BEST_UB`、allCycles completion bound、scalar/arc fixing/subtree/pricingOnly、midpoint probe/reuse、dual-bound pruning 和 setup cost 系数 20；强分支和 route enumeration 关闭。本轮结果目录为 `test-results/bpc/tmp-ngdssr-40-2-setupR-empty1-top1-ngstats-20260702/`。

本次同时扩展了 `twet.bpc.fullDomainCompare.ngDssrSetStats` 的输出字段。原来只记录每轮 ng-set 的 `avg/min/max/u` 和被存下来的 non-elementary route 数；现在每个 DSSR round 还会记录本轮观察到的 raw non-elementary negative route 数 `neSeen`、进入 top-K 更新池的数量 `neStored`、以及本轮最终返回的 elementary 列数 `elem`。summary 里同步给出 `totalNonElementarySeen`、`totalNonElementaryStored`、`totalElementaryReturned` 和 `totalNgSetUpdates`。这些字段只用于日志统计，不参与 dominance、join、更新或入列判断。

三组求解结果均通过 validator。R25 为 `ROOT_PROCESSED, obj=bound=31893, solve=171.158s, exact=41.654s/29 calls`；R50 为 `FINISHED, obj=bound=43625, solve=134.185s, exact=42.293s/96 calls`；R75 为 `FINISHED, obj=bound=55007, solve=274.880s, exact=116.621s/296 calls`。和之前同系列 `nearestK8/top10` 当前基线相比，R50 明显变快，R75 基本接近，R25 反而更慢；由于初始 ng-set 和更新策略都不同，这里主要用于观察 DSSR 收紧形态，不把它当成最终默认配置结论。

统计口径只看“本次 exact pricing 最终返回 elementary 负列”的 DSSR 调用。R25 中共有 28 次返回 elementary 列，DSSR round 数平均 `3.893`，最少 `2`，最多 `12`；返回 elementary 时最终 ng-set 平均大小的均值为 `1.588`，最小平均 `1.450`，最大平均 `2.075`，各 job 的最小 size 始终为 `1`，最大 size 在 `3-4`。这些调用平均每次看到 `3115.1` 条 non-elementary negative route，总计 `87223` 条；平均返回 elementary 列 `8.14` 条，总计 `228` 条。

R50 中共有 90 次返回 elementary 列，DSSR round 数平均 `3.544`，最少 `1`，最多 `14`；最终 ng-set 平均大小的均值为 `1.646`，范围 `1.000-2.875`，各 job 最大 size 可到 `8`。这些调用平均每次看到 `6838.5` 条 non-elementary negative route，总计 `615469` 条；平均返回 elementary 列 `11.72` 条，总计 `1055` 条。R75 中共有 277 次返回 elementary 列，DSSR round 数平均 `3.162`，最少 `1`，最多 `16`；最终 ng-set 平均大小的均值为 `1.674`，范围 `1.000-3.875`，各 job 最大 size 可到 `10`。这些调用平均每次看到 `9262.9` 条 non-elementary negative route，总计 `2565834` 条；平均返回 elementary 列 `14.64` 条，总计 `4055` 条。

当前判断是：`empty1/top1` 确实能把最终 ng-set 保持得非常小，R25/R50/R75 中返回 elementary 列时平均 size 大多仍在 `1.6-1.7` 附近，说明很多情况下只需要极小 memory 就能得到基本列。但 raw non-elementary negative route 数量非常大，R75 总计超过 256 万条，且尾部仍会出现 12/14/16 轮 DSSR 才返回 elementary 列的调用。这说明 top1 更新在部分节点上收紧太慢。后续更值得测试的是动态更新口径：例如前期 top1，若连续多轮无 elementary 且 `neSeen` 很高，则临时切到 top3/top5；或者采用 `nearestK3/top3` 这类“初始小、更新不太慢”的折中，而不是单纯追求最小 ng-set。
140. 2026-07-02 setupR25/R50/R75 empty1/top5 ng-set 统计实验

在第 139 节 `empty1/top1` 的基础上，本轮只把 `ngDssrRouteUpdateLimit` 从 1 改成 5，初始 ng-set 仍保持 `empty/self-only`，即每个任务初始只包含自身。其余求解配置保持同一组 setupR 诊断口径：setup cost 系数 20、ALNS seed、启发式 pricing、`joinBest=BEST_UB`、allCycles completion bound、scalar/arc fixing/subtree/pricingOnly、midpoint probe/reuse、dual-bound pruning，关闭 SRI、partial dominance、strong branching 和 route enumeration。结果目录为 `test-results/bpc/tmp-ngdssr-40-2-setupR-empty1-top5-ngstats-20260702/`。

完整求解结果为：R25 `ROOT_PROCESSED, obj=bound=31893, solve=112.231s, exact=14.503s/15 calls`；R50 `FINISHED, obj=bound=43625, solve=244.691s, exact=65.380s/81 calls`；R75 `FINISHED, obj=bound=55007, solve=663.633s, exact=200.589s/269 calls`。和 top1 相比，R25 明显变快，R50/R75 反而变慢。也就是说，把每轮更新从 1 条增大到 5 条，确实减少了 root 和部分 exact pricing 的 DSSR 轮数，但会改变列集和分支路径；在 R50/R75 上，后续节点数量、启发式调用和总 pricing 时间把早期收益抵消掉了。

只看最终返回 elementary 负列的 exact pricing 调用，R25 有 14 次，平均需要 `2.500` 轮 DSSR，最多 `4` 轮；返回时最终 ng-set 平均大小均值为 `1.595`，各 job 最大 size 全局只到 `3`。这些调用平均每次累计看到 `3061.8` 条 non-elementary negative route，总计 `42865` 条，平均每次累计存入更新池 `12.5` 条，最终共返回 elementary 列 `281` 条。

R50 有 74 次返回 elementary 列，平均需要 `2.649` 轮，最多 `6` 轮；返回时最终 ng-set 平均大小均值为 `1.906`，各 job 最大 size 全局到 `7`。这些调用平均累计看到 `4213.0` 条 non-elementary negative route，总计 `311765` 条，平均累计存入 `13.2` 条，最终共返回 elementary 列 `1597` 条。R75 有 246 次返回 elementary 列，平均需要 `2.703` 轮，最多 `10` 轮；返回时最终 ng-set 平均大小均值为 `1.959`，各 job 最大 size 全局到 `10`。这些调用平均累计看到 `4611.3` 条 non-elementary negative route，总计 `1134376` 条，平均累计存入 `13.4` 条，最终共返回 elementary 列 `3026` 条。

和 top1 的统计对比可以得到更清楚的判断。top5 把 DSSR 平均轮数从约 `3.2-3.9` 降到约 `2.5-2.7`，最大轮数也从 `12/14/16` 降到 `4/6/10`，说明“只更新 1 条”确实偏慢。但 top5 的最终 ng-set 也更大，R50/R75 平均接近 `1.9-2.0`，而且列集改变后分支树未必更好。当前更合理的方向不是直接把 top5 设为默认，而是做条件更新：只有当连续多轮没有 elementary、或者 `neSeen` 特别高时临时提高更新条数；普通轮次仍保持较小更新，避免过早扩大 ng-memory 并扰动列生成路径。
141. 2026-07-02 final ng-set 成员相似度诊断

为了判断“能否按某种规则预置初始 ng-set，从而减少 DSSR 迭代次数”，本轮在第 140 节统计基础上新增默认关闭的成员级诊断开关 `twet.bpc.fullDomainCompare.ngDssrSetMembers`。打开后，每次 ng-DSSR exact pricing 结束时会在 summary 里输出每个 job 的最终 ng-set 成员；该输出只服务于离线相似度分析，不参与求解逻辑。当前普通日志只记录 size，无法判断集合成员是否相同，因此必须补这个诊断后才能回答“最终集合差距大不大”。focused `javac` 已通过。

用 `wet040_001_2m_setupR50`、`empty1/top5`、no-strong 的同一口径跑了一次成员诊断，结果仍为 `FINISHED, obj=bound=43625, solve=248.136s, exact=63.734s/81 calls, valid=true`，与第 140 节 R50 路径基本一致。日志目录为 `test-results/bpc/tmp-ngdssr-40-2-r50-empty1-top5-members-20260702/`。这次共解析 74 个最终返回 elementary 负列的 ng-DSSR call，分布在 9 个 node 上。

同一 node 内，相邻 exact pricing call 的最终 ng-set 按 job 平均 Jaccard 相似度为 `0.878`，最小 `0.6675`，最大 `1.0`；按 job 完全相同的比例平均为 `0.7377`。若比较同一 node 内所有 pair，平均 Jaccard 仍有 `0.8031`。这说明同一个 node 的不同 DSSR call 里，最终学到的 ng-set 成员高度相似，不只是 size 相近。当前每次 pricing 都重新从 self-only 初始化，因此反复重新学习这些相似集合，确实存在冗余。

不同 node 之间，用每个 node 最后一次 elementary-return call 的最终 ng-set 对比，平均 Jaccard 为 `0.6421`，范围 `0.4542-0.9021`，按 job 完全相同的比例平均为 `0.3521`。这说明跨 node 的最终集合也不是随机的，但差异明显大于同一 node 内。由此更稳妥的优先级是：先尝试“同一 node 内复用上一轮最终 ng-set 作为下一次 exact pricing 初始 ng-set”；再考虑“child 继承 parent 的 final ng-set”或“实例级 learned seed”。

按 job 看，部分邻居出现频率非常稳定。例如 R50 这次中，job 39 的最终 ng-set 里 member 12 出现频率 `0.95`、member 1 为 `0.81`、member 21 为 `0.41`；job 21 中 member 12 为 `0.93`、member 28 为 `0.64`、member 39 为 `0.57`；job 12 中 member 39 为 `0.91`、member 21 为 `0.82`、member 1 为 `0.81`。如果按成员出现频率做 learned initial ng-set，阈值 `0.5` 时平均 size 约 `1.55`、最大 `4`；阈值 `0.4` 时平均 size 约 `1.775`、最大 `5`；阈值 `0.3` 时平均 size 约 `1.975`、最大 `5`。这说明很小的 learned seed 就可能覆盖大量最终会被 DSSR 学到的成员。

当前结论是：可以通过“最终 ng-set 成员稳定性”来指导初始 ng-set，而不是只看 nearestK 或 topK。最优先值得实现的实验方案是 node-local warm start：同一个 node 内，每次 exact pricing 结束后保存 final ng-set，下次 exact pricing 初始化时从这份集合开始，再叠加 self/nearest/dualPair 规则。正确性上这是安全的，因为扩大 ng-set 只会收紧 ng relaxation，不会删掉 elementary 可行列；风险是 ng-set 过大可能削弱 dominance、增加 label 状态。但本次 R50 的最终平均 size 仍小于 2，这个风险较小。跨 node/child 继承也有潜力，但相似度只有中等，应作为第二阶段实验，最好加上 size 上限或只继承高频成员。

142. 2026-07-02 动态 ng-set 初始化与历史统计继承方案讨论

基于第 139-141 节的诊断结果，当前可以确认一个事实：ng-DSSR 每次 exact pricing 都从很小的初始 ng-set 重新开始，会反复学习高度相似的记忆集合。`setupR50`、`empty1/top5` 成员诊断中，同一 node 内相邻 exact call 的按 job 平均 Jaccard 相似度约为 `0.878`，完全相同 job 集合比例约为 `0.738`；同一 node 内所有 pair 的平均 Jaccard 也有 `0.803`。跨 node 使用每个 node 最后一次 elementary-return call 的 final ng-set 对比，平均 Jaccard 约为 `0.642`，说明跨节点也有一定稳定性，但明显弱于同一 node 内。部分 job 的高频邻居非常稳定，例如 job 39 经常需要 `{12, 1, 21}`，job 21 经常需要 `{12, 28, 39}`，job 12 经常需要 `{39, 21, 1}`。按成员出现频率过滤时，阈值 `0.5` 附近得到的 learned seed 平均 size 约 `1.55`、最大 `4`；阈值 `0.4` 时平均约 `1.775`、最大 `5`；阈值 `0.3` 时平均约 `1.975`、最大 `5`。这说明 learned seed 并不需要很大，就可能覆盖多数重复学习到的成员。

因此，动态设置 ng-set 的基本方向是成立的：不改变 ng relaxation 的正确性，只改变每次 DSSR exact pricing 的初始记忆集合，让它少走几轮重复的 non-elementary 学习过程。这个策略和 time-indexed/compact window 强化是互补的：compact window 主要缩小时间域、弧域和函数定义域；learned ng-set 主要减少 DSSR 内部对相似 memory set 的重复学习。前者减少扩展空间，后者减少 DSSR 迭代次数和无效 non-elementary route 数量。

当前更清晰、风险更低的是 node 之间继承。root 的第一次 exact pricing 仍保持现有初始化，不引入历史偏置；非 root node 的第一次 exact pricing 可以只从直接父节点继承统计信息，不使用全祖先或全局历史。具体思路是：父节点处理结束后保存每个 job 的 final ng-set 成员频率；子节点初始化时，对每个 job 取频率大于 `0.5` 的成员，按频率排序取前 K 个，K 直接使用 `ngDssrInitialNgSetSize` 的口径，成员不足 K 时不强行用 nearest 补满。这样可以避免 ng-set 无控制膨胀，也避免跨很远节点继承过多已经不适合当前分支域的成员。该方案默认应做成关闭开关，先用于统计和对比。

同一 node 内继承更有潜力，但当前还没有一个足够干净的策略。最直接的“上一轮 final ng-set 全量作为下一轮初始 ng-set”不推荐，因为 final set 可能随 DSSR 迭代不断累积，容易单调变大，导致 dominance 变弱、label 数变多。用前 20 次 exact pricing 统计高频成员再启用也不理想：一方面早期样本少时频率不稳定，另一方面等到 20 次以后，很多重复学习的成本已经付出，而且这些 exact call 之间 dual、列池、cut 状态可能已经变化，统计含义不一定一致。

相对合理的 node 内候选方案是“first-exact anchor”。即同一 node 的第一次 exact pricing 仍按常规初始化；结束后，把这次 DSSR 更新过程中真正触发过的 memory evidence 统计成一个截断 anchor；后续同一 node 的 exact pricing 都从这个固定 anchor 初始化，而不是滚动继承上一轮 final set。anchor 的成员最好不是简单取 final ng-set，而是记录每次 non-elementary route 触发更新时的 `(middleJob, repeatedJob)` 证据次数，再按次数取前 K，tie-break 可以用 ng 距离或 job id。这样更接近“哪些成员确实解决了重复访问问题”，而不是把最终集合里的历史残留全部继承下来。问题是当前代码还没有维护这种 pair-level evidence，因此该方案需要额外实现和验证；在此之前不建议直接做 node 内继承。

关于 `ngDssrRouteUpdateLimit`，前面的 `empty1/top1` 与 `empty1/top5` 对比说明，更新更多 route 可以显著减少 elementary 返回前的 DSSR 轮数和尾部 non-elementary 数量，但求解时间并非单调变好。R25 上 top5 明显改善，R50/R75 反而变慢，原因是更快扩大 ng-set 会改变列集合、LP 退化路径和后续分支结构。也就是说，update 数量本身不是正确性风险，但它不是简单“越大越快”。后续如果继续做，应考虑把 update 数量也做成诊断驱动或节点状态驱动，而不是固定全局加大。

实现层面还要注意生命周期。`PricingEngine.reset()` 在 PC 中会因 LP、cut、pricing engine 切换等被频繁调用，它只能清临时缓存，不能作为“新 node / 新树”的边界。如果把 warm-start 历史存在 pricing engine 内部并在 reset 中清掉，同一 node 或父子 node 的继承就会失效。更合适的做法是把 learned ng-set 统计放在 node/tree 层，或由独立的 warm-start manager 按 solve 生命周期管理；只在新实例或新整棵树开始时清空。

当前结论是：先不实现 node 内动态继承。后续最值得先做的是 node-to-node 的父节点统计继承，默认关闭，并记录继承前后 DSSR 轮数、final ng-set size、non-elementary negative route 数、exact pricing 时间和最终搜索树是否变化。node 内策略等 pair-level evidence 统计清楚后再做，否则容易因为 ng-set 变大导致 dominance 变弱，抵消减少 DSSR 轮数的收益。

143. 2026-07-02 基于全历史或滑动历史的动态 ng-set 初始化设想

在第 142 节父子节点继承和 node-local warm start 之外，还讨论了一种更统一的做法：不显式区分 node 之间或 node 内部，而是在每次 ng-DSSR exact pricing 开始前，查看当前已经累计的历史 final ng-set 统计。对每个 job，先根据历史 final ng-set 的平均 size 决定本次初始 ng-set 的目标大小，再从历史成员频率最高的 job 中选择对应数量的成员作为初始集合。历史统计可以有几种口径：全局所有 pricing 的累计统计；当前 node 及其父链上的统计；或者最近若干次 ng-pricing 的滑动窗口统计。全局累计相当于滑动窗口长度无穷大，最稳定但最容易带入旧分支域的信息；滑动窗口更能适应当前 dual、branch 和 compact window，但样本少时波动更大。

这个方向在正确性上没有问题。初始 ng-set 只改变 ng relaxation 的强弱，不会删掉 elementary 可行列；即使 learned seed 选得不好，DSSR 仍可以继续通过 non-elementary route 更新集合。真正的问题是性能：如果历史集合把很多当前 node 不需要的成员带进来，ng-set 变大后 dominance 会变弱、label 状态变多，可能抵消减少 DSSR 轮数的收益。因此动态 size 不能简单按历史均值无上限增长，必须有上限、阈值和衰减。

按 job 平均 size 决定目标大小有一定合理性，因为前面的成员诊断显示不同 job 的最终 ng-set 需求不同，例如部分 job 经常只需要自身，部分 job 稳定需要 2-4 个高频邻居。但平均值口径也有两个风险：第一，少量困难 pricing 会把均值抬高，导致后续大量普通 pricing 初始集合过大；第二，历史 final ng-set 里不一定每个成员都是真正必要的，可能包含早期 DSSR 更新留下的“残留成员”。因此如果做，建议目标 size 不直接用普通均值，而是用带上限的稳健统计，例如 `targetSize_j = min(K, ceil(trimmedMeanSize_j))` 或 `min(K, ceil(EWMA_j))`，K 仍沿用 `ngDssrInitialNgSetSize` 或单独的 learned cap。成员选择也不应只按出现次数填满到 target size，而应设置频率阈值，例如频率大于 `0.5` 的成员才可进入；如果不足 target size，就保持不足，不用 nearest 强行补满。

三种历史口径的优先级判断如下。全局所有历史最简单，样本最多，适合先做统计诊断，但最可能混入与当前分支域无关的成员。当前 node 父链统计更贴近当前子树，理论上更干净，但实现上需要在 node 生命周期里维护和传递统计，且父链样本可能少。最近若干次 ng-pricing 的滑动窗口是折中方案，能自动适应近期 dual 和分支变化，也不依赖 node 层级，但需要选择窗口大小；窗口太小则不稳定，窗口太大又接近全局累计。基于前面的相似度结果，当前更值得优先试的是“滑动窗口 + 频率阈值 + 小 cap”，而不是无穷累计历史。

一个可测试的默认关闭实验方案是：维护最近 `W` 次返回 elementary 负列或完成 DSSR 收敛的 final ng-set 统计；每次 exact pricing 初始化时，对每个 job 计算历史 size 的 EWMA 或截尾均值，并取 `ceil` 得到目标 size，但不超过 K；成员按出现频率排序，过滤掉频率低于 `0.5` 的候选。root 前若没有历史，则退回当前初始化。该方案同时覆盖 node 内部和 node 之间，不需要显式判断来源；但日志必须记录本轮 learned seed 的平均 size、最大 size、命中成员数、DSSR 轮数、non-elementary route 数和 label 数，否则无法判断是减少迭代还是只是放大 label 空间。

当前结论是：这个全历史/滑动历史策略比“直接继承上一轮 final ng-set”更稳，也比严格父子继承更容易统一实现。但它仍然是性能实验，不是确定改进。最需要防止的是 learned seed 过大，因此应采用小 cap、频率阈值和滑动窗口/衰减，而不是把所有历史高频成员永久累计进每个 job 的初始 ng-set。

144. 2026-07-03 动态 ng-set 历史窗口实验口径修正

在第 143 节的基础上，本次进一步明确一个更直接的实验口径：先不区分 node 内、父子 node 或跨 node 继承，也不维护复杂父链统计；只维护当前按 node 搜索顺序产生的最近 `W` 次 ng-DSSR final ng-set 记录。`W` 很大时就近似全局历史，`W` 较小时就是滑动窗口。这样可以先验证“最近历史是否能减少 DSSR 重复学习”，不把实现复杂度放到 node 生命周期和父链管理上。

目标 size 暂时不设额外 cap，而是对每个 job 直接使用历史 final ng-set size 的平均值来决定。本轮讨论认为，已有统计里 final ng-set 平均 size 本身不大，过早设置 cap 可能会把真正稳定需要的成员截掉，反而看不出 warm-start 的潜力。为了避免平均值不断偏大，初始实验可以比较两种取整规则：默认用向下取整，更保守；如果某个 job 中频率大于 `0.8` 的成员数量较多，说明高置信成员确实稳定存在，则允许对该 job 的目标 size 向上取整。也就是说，size 主要由历史平均决定，高置信频率决定是否放宽到 ceil。

成员选择仍然要用频率阈值，不能为填满 size 强行塞低频成员。当前建议是先过滤频率大于 `0.5` 的成员，再按频率排序取目标数量；如果过滤后成员不足目标数量，就保持不足，不用 nearest 补齐。这样可以在“不设 cap”的同时保留一个防止噪声进入的约束：低频成员即使历史平均 size 较大，也不会被硬塞进初始 ng-set。

root 的处理也应分开。root 第一次 exact pricing 没有历史，仍按当前老办法初始化；root 内后续 exact pricing 是否使用已经积累的历史可以作为开关试一下。保守口径是 root 整体都不用 learned seed，因为 root 的 dual 和列池还在剧烈变化；激进口径是 root 第一次以后也允许使用最近历史，理由是前面统计显示同一 node 内 final ng-set 相似度较高，信息少时也可能帮助减少重复 DSSR 轮数。两种都可以实现，但默认建议先让 root 第一次保持老办法，后续是否启用通过单独开关控制。

当前更推荐的实验版本是：维护最近 `W` 次正式 ng-DSSR exact pricing 的 final ng-set 统计，排除 strong branching trial、repair、time-indexed 预处理等非正式求解；每次正式 exact pricing 开始时，如果历史样本数不足最小阈值，就退回老办法；样本足够后，对每个 job 用平均 size 决定目标数量，用 `>0.5` 频率过滤成员，并用 `>0.8` 的高置信成员数量决定 floor/ceil。该策略仍然只影响初始化，不改变 DSSR 正确性。必须同步记录 learned seed 的平均/最大 size、实际使用成员数、DSSR 轮数、non-elementary route 数、label 数和 exact 时间，判断它到底是在减少迭代，还是把 label 空间放大。

145. 2026-07-03 动态 ng-set 历史窗口 warm-start 实现

本次按第 144 节口径实现了默认关闭的动态 ng-set 历史窗口实验功能。实现没有改变 DSSR 的收敛逻辑，只改变每次正式 ng-DSSR exact pricing 初始化 `ngNeighborhoodByJob` 的方式：如果开关关闭、没有历史样本，或者当前是 root 且未允许 root 使用历史，则仍走原来的 `empty/full/dualPair/nearestK` 初始化；如果开关打开且历史可用，则按最近 `W` 次 final ng-set 统计生成 learned seed。

历史状态由独立的 `NgDssrHistoryWarmStart` 管理，保存在具体 ng-DSSR pricing engine 生命周期内。它只记录正式 exact pricing 结束后的 final ng-set，且三个 ng-DSSR engine 都单独覆盖了 repair 用的 `findFeasible()`，repair 阶段不向历史窗口写入；strong branching phase2 当前只走启发式 trial，诊断 cross-check 也使用独立 solver，因此不会污染主线历史。这样做的目标是避免把临时修复节点、强分支试探或诊断求解里的 ng-set 当成主线统计。

learned seed 的构造规则为：对每个 job 计算历史 final ng-set size 的平均值，默认取 floor 作为目标大小；如果频率超过高置信阈值的非自身成员数量已经超过 floor 目标，则允许取 ceil。成员选择只考虑出现频率大于 `ngDssrHistoryWarmStartFrequencyThreshold` 的 job，默认阈值为 `0.5`；候选按频率降序、job id 升序取到目标大小为止，不用 nearest 补满。高置信阈值默认 `0.8`。窗口大小 `ngDssrHistoryWarmStartWindowSize` 默认 `50`，设置很大时近似全局历史，设置较小时就是滑动窗口。root 后续 pricing 是否可用历史由 `ngDssrHistoryWarmStartUseRoot` 控制，默认关闭。

full-domain runner 暴露了对应参数：`twet.bpc.fullDomainCompare.ngDssrHistoryWarmStart`、`ngDssrHistoryWindow`、`ngDssrHistoryFrequencyThreshold`、`ngDssrHistoryHighConfidenceThreshold` 和 `ngDssrHistoryUseRoot`。打开后 mode 名会追加 `ngHistW...`，DSSR summary 中会输出 `ngWarmStart=base/learned` 和当前历史样本数，方便对比本轮是否真正用了 learned seed。

正确性判断仍是：该功能只修改初始 ng-set，不会删除 elementary 可行列；如果 learned seed 不合适，DSSR 仍可通过 non-elementary negative route 继续更新 ng-set。因此它是性能实验开关，不改变主问题语义。主要风险是 learned seed 过大导致 dominance 变弱、label 数上升；所以默认关闭，并保留频率阈值和窗口大小供后续对比。验证方面，本次 focused `javac` 已覆盖新增类、三个 ng-DSSR engine、主体 solver、配置和 runner；短 smoke 确认 full-domain runner 能解析 `ngHistW` 配置并启动到 TIME_LIMIT，但由于 20 秒限制内尚未进入 exact pricing，性能效果仍需后续正式实验判断。
146. 2026-07-03 root 加 cut 后的历史 warm-start 口径修正

本次对第 145 节实现做了一个小修正：root 初始迭代仍默认不用历史 warm-start，但 root 上一旦已经有 active cut，就允许使用历史统计初始化 ng-set。原因是加 cut 后属于同一个 root node 内的后续 price-and-cut 迭代，已经不是最初那次完全无 cut 的 pricing；这时继续从 `nearestK/empty` 等基础口径重新学相似 ng-set，会重复付出 DSSR 收紧成本。该修正只影响 `canUseHistoryWarmStart()` 的判定，不改变历史记录来源，也不让 repair、strong trial 或 cross-check 写入历史。

同时把默认历史窗口从 `50` 调整为 `100`。前面讨论认为 50 对这种频率统计偏短，容易因为近期少量节点波动导致 learned seed 频率不稳定；100 仍然足够轻量，但能更接近“最近一段搜索状态”的稳定统计。功能仍默认关闭，只有显式打开 `enableNgDssrHistoryWarmStart` 才生效。
147. 2026-07-03 nearestK4/top10 history warm-start 无效实验记录

本轮原本用于比较 `nearestK4/top10` 下 history warm-start 开关效果，测试对象为 `wet040_001_2m_setupR25/R50/R75`，setup cost 系数为 20。复核后确认，这组结果不能作为 warm-start 生效实验结论，原先写入的 baseline/warm-start 时间、DSSR size 对照和“没有收益”的判断都已经撤销。

问题的核心不是 warm-start 策略本身无效，而是该组日志没有证明新实现被实际加载。`tmp-nghist-cost20-k4-stats-warm-corrected-20260703` 的三组日志只显示 `systemProperty.twet.bpc.fullDomainCompare.ngDssrHistoryWarmStart=true`，但没有出现 `config.enableNgDssrHistoryWarmStart=true`。同时，只要源码中的 `enableNgDssrHistoryWarmStart` 真正打开，ng-DSSR pricing summary 应该输出 `ngWarmStart=base/...` 或 `ngWarmStart=learned/...`，而该组日志完全没有这个字段。因此该实验只能说明命令行属性被 JVM 枚举到了，不能说明它进入了实际配置和当前 pricing class。

进一步检查 class 文件后，风险更明确：`bin/TWETBPC/...` 仍是旧 class，而 `target/classes` 才包含新的 warm-start 字段和 `ngWarmStart` summary 字符串。若实验 classpath 走了旧 `bin` 或旧 class 目录，就会出现“systemProperty 有开关，但 config 快照和 pricing 行为仍是旧版”的现象。第 147 节之前记录的 baseline 与 warm-corrected size 完全一致，不应解释为 warm-start 生效但没有收益，而应解释为本轮没有有效验证 warm-start。

另一个需要修正的执行细节是 runner 参数名。full-domain runner 的总开关是 `twet.bpc.fullDomainCompare.ngDssrHistoryWarmStart`，但窗口、阈值和 root 口径分别是 `ngDssrHistoryWindow`、`ngDssrHistoryFrequencyThreshold`、`ngDssrHistoryHighConfidenceThreshold` 和 `ngDssrHistoryUseRoot`，不是带完整 `WarmStart` 的长名字。后续重跑必须使用当前编译后的 `target/classes`，并在日志中同时看到 `config.enableNgDssrHistoryWarmStart=true`、mode 名包含 `ngHistW...`、pricing summary 中包含 `ngWarmStart=...`，否则不能把结果当作 warm-start 对照。

当前保留这段记录的目的只是防止后续误用这组数据。`test-results/bpc/tmp-nghist-cost20-k4-stats-baseline-20260703/` 和 `test-results/bpc/tmp-nghist-cost20-k4-stats-warm-corrected-20260703/` 只能作为“无效运行/旧 classpath 风险”的证据，不再作为性能结论。下一次重跑前先做短 smoke：只跑到第一次正式 ng-DSSR exact pricing，确认 `config.enableNgDssrHistoryWarmStart=true` 和 `ngWarmStart=base/historyWarmStart=...` 或 `learned/historyWarmStart=...` 出现在同一份日志里，再做 R25/R50/R75 完整对照。
148. 2026-07-03 nearestK4/top10 history warm-start 有效重跑对比

按照第 147 节修正后的检查口径，本轮重新编译当前 `target/classes` 后重跑 `setupR25/R50/R75`，并明确要求日志里同时出现 `config.enableNgDssrHistoryWarmStart=true` 和 pricing summary 的 `ngWarmStart=...` 字段。测试仍使用 setup cost 系数 20、`nearestK4/top10`、ALNS 60s、no-SRI/no-partial、强分支关闭、time-indexed root preprocessing 打开、route enumeration 关闭；本轮额外打开 `ngDssrSetStats=true`，用于读取每次 ng-DSSR 的轮数、non-elementary route 数量和 final ng-set size。baseline 目录为 `test-results/bpc/tmp-nghist-cost20-k4-baseline-redo-stats-20260703/`，warm-start 目录为 `test-results/bpc/tmp-nghist-cost20-k4-warm-redo-stats-20260703/`。

baseline 结果为：R25 `ROOT_PROCESSED, obj=bound=31893, solve=97.010s, exact=4.902s/12, master=36.275s, pool=12198`；R50 `FINISHED, obj=bound=43625, solve=110.908s, nodes=9, exact=22.630s/69, master=19.962s, pool=17794`；R75 `FINISHED, obj=bound=55007, solve=186.945s, nodes=30, exact=45.746s/222, master=46.107s, pool=27143`。warm-start 结果为：R25 `ROOT_PROCESSED, solve=105.534s, exact=5.178s/12, pool=12198`；R50 `FINISHED, solve=123.947s, nodes=13, exact=30.722s/115, pool=22418`；R75 `FINISHED, solve=206.377s, nodes=30, exact=47.499s/207, pool=25782`。三组目标值和 valid 均一致。

这次日志确认 warm-start 确实生效。R25 是 root-only，且当前配置 `ngDssrHistoryUseRoot=false`，所以所有 12 次 ng-DSSR 都是 `ngWarmStart=base`，final size、DSSR 轮数和 baseline 完全一致，这不是 bug，而是配置语义决定的。R50 中 117 次 ng-DSSR 里有 103 次 `learned`、14 次 `base`；R75 中 209 次里有 199 次 `learned`、10 次 `base`。这说明第 147 节指出的“旧实验没有真正触发 warm-start”已经被排除，本轮是有效对比。

从 ng-DSSR 学习过程看，warm-start 确实减少了重复学习。R50 的平均 DSSR 轮数从 `2.729` 降到 `1.897`，最大轮数从 `8` 降到 `5`，平均 non-elementary route 数从 `3815.0` 降到 `2181.6`，平均更新次数从 `36.9` 降到 `12.7`；R75 的平均 DSSR 轮数从 `2.300` 降到 `1.871`，最大轮数从 `7` 降到 `6`，平均 non-elementary route 数从 `3031.0` 降到 `578.9`，平均更新次数从 `40.4` 降到 `20.9`。final ng-set size 并没有单调变小：R50 从 `4.922` 到 `4.867`，基本相当；R75 从 `5.010` 到 `5.784`，反而更大，说明 learned seed 会改变后续 DSSR 和列生成路径。

总体性能并未稳定变好。R50 warm-start 反而从 `110.908s` 变成 `123.947s`，主要因为节点数从 9 增到 13、pricing 调用从 482 增到 638、pool 从 17794 增到 22418；R75 从 `186.945s` 变成 `206.377s`，虽然 exact calls 从 222 降到 207、pool 也略小，但 heuristic 和 master LP 时间均上升，抵消了 DSSR 学习轮数减少的收益。当前结论是：历史 warm-start 确实命中了原本想优化的现象，即减少 DSSR 轮数和 non-elementary route；但它会改变列集和分支树，整体求解时间不稳定，不能作为默认策略。后续若继续做，应优先加更保守的触发条件，例如只在连续多轮 DSSR 才找到 elementary、或 non-elementary route 数特别高时启用 learned seed，而不是对所有非 root pricing 全量启用。
补充决策：该功能先保留，不删除、不改成默认启用。当前实验只能说明它在这组三个 setupR cost20 算例上整体不稳定，不能证明策略本身无用；它确实降低了 R50/R75 的 DSSR 轮数和 non-elementary route 数，说明机制命中了一个真实现象。后续如果在更大规模、不同 setup/时间尺度、带 cut 或不同分支结构下再次出现 DSSR 重复学习严重的问题，可以继续用这个开关做对照；在找到更清楚的触发条件前，主线仍保持关闭。

149. 2026-07-03 partial dominance + time-indexed root preprocessing 下 SRI 对比

本轮按当前 40-2 原始算例 `wet040_001_2m` 测试 partial dominance，并打开 time-indexed root preprocessing。先检查 SRI 下 arc fixing 的兼容性：`TimeIndexedRootPreprocessor` 自身仍按 no-cut/no-SRI 临时 root 求解，只把 time-indexed forbidden 状态、compact window 和可提升的普通 pricing-only arc 传回正式 root，不把临时 pseudo-schedule 列带入主线；这一步不依赖 active SRI cut。正式 ng-DSSR/partial 的 cut-loop fixing 在 active SRI 存在时，如果显式打开 `timeIndexedCompletionBoundSriAwareArcFixing=true`，会走 `TimeIndexedScalarCompletionBound.applySriAwareArcFixing()`；否则可通过关闭 cut-loop fixing 避免使用不完整的 active-cut fixing 证书。由此当前结论是：接口上兼容，但 SRI-aware time-indexed arc fixing 成本很高，不适合直接放在每轮 cut-loop 后默认运行。

为了先排除强分支干扰，本轮 SRI/no-SRI 对比均关闭 strong branching，其他主线配置保持一致：partial dominance、nearestK4/top10、ALNS 60s、BEST_UB join、allCycles completion bound、scalar/arc fixing/subtree/pricingOnly、midpoint probe/reuse、dual-bound pruning、time-indexed root preprocessing 打开，route enumeration 关闭。no-SRI 结果为 `FINISHED, obj=bound=22580, solve=224.782s, root=105.673s, nodes=45, pool=49506, exact=30.081s/350, heuristic=63.698s/1182, master=60.413s, valid=true`，结果目录为 `test-results/bpc/tmp-partial-tiroot-40-2-nosri-nostrong-fix-20260703/`。这条 run 也修正了一个启发式 seed 排序 bug：原来 seed comparator 使用 epsilon 比较，可能违反 Java Comparator 传递性并触发 TimSort 异常；现在改为严格 `Double.compare`，只影响排序稳定性，不改变 reduced-cost 筛选口径。

直接打开 SRI-aware cut-loop time-indexed arc fixing 的一次测试没有得到有效对比：`tmp-partial-tiroot-40-2-sri-nostrong-fix-20260703` 在 root 的 cut-loop 中触发 SRI-aware helper，单次 `cutLoopScalarArcFixing.done ng-DSSR time-indexed SRI-aware helper arc fixing` 耗时约 `753s`，最终 `TIME_LIMIT, obj=22582, bound=INF, solve=1153.573s, cut rounds=16, added cuts=80`。这说明 SRI-aware fixing 逻辑能被调用，但当前实现太重，会把小算例直接拖到超时；该结果不能作为 SRI 求解效果结论，只能作为“该 helper 需要默认关闭或重新优化”的证据。

关闭 cut-loop 的 time-indexed SRI-aware fixing 后，SRI run 能正确闭合：`tmp-partial-tiroot-40-2-sri-nostrong-no-cutloopfix-20260703` 结果为 `FINISHED, obj=bound=22580, solve=762.854s, root=189.848s, nodes=17, pool=25750, exact=362.993s/985, heuristic=202.509s/2258, master=112.056s, cut rounds=256, added cuts=1280, peak cut pool=902, valid=true`。和 no-SRI 相比，SRI 明显减少了节点数和最终 pool，但 cut-and-price 轮次、exact pricing 次数和启发式调用大幅增加，总时间从约 `225s` 增加到约 `763s`。因此在该 40-2 partial dominance 口径下，SRI 有收紧搜索树的效果，但总体不划算；当前主要瓶颈不是单次 label 爆炸，而是 cut-loop 反复加 cut、反复定价导致的长尾。

本轮还暴露一个强分支相关风险：在相同 partial + time-indexed root preprocessing 下，开启 strong branching 的 no-SRI run 曾得到 `obj=bound=22582`，而关闭 strong branching 后回到已知最优 `22580`。因此本轮 SRI 对比不使用 strong branching；后续若要把 strong branching 和 partial/root preprocessing 组合使用，必须单独复查强分支 child seed/compatibility/repair 逻辑，不能把它混入 SRI 结论。
150. 2026-07-07 wet040_001_2m 指定 k3/top10 strong 配置的组件耗时归因

本次按用户指定口径复跑 `wet040_001_2m`：`nearestK3/top10`、`ngDssrWindowRepeatabilityFilter=true`、strong branching、time-indexed root preprocessing、time-indexed pre-heuristic、`ngDssrHistoryWarmStart=false`、completion-bound subtree pricing-only、`midpointProbeScore=queue`。结果为 `FINISHED, obj=bound=22580, solve=360.471s, root=206.213s, nodes=19, pricing=1636, pool=106273, exact=5.880s/86, heuristic=22.500s/272, masterLP=194.276s, valid=true`，日志目录为 `test-results/bpc/tmp-wet040-001-m2-ng-k3-top10-strong-tirootpre-tipre-20260707-run/`。

从组件耗时看，本次瓶颈不是 ng-DSSR exact pricing。`GCNGBBStyleNgDssrPricing` 只有 `5.880s/86`，加上 find-feasible exact `0.168s/8` 也很小。最大项是 master LP，总计 `194.276s`，其中 `after_pricing=145.128s/642`，strong-branching 相关 LP 小计约 `46.698s`。pricing 侧总计约 `113.781s`，其中 `HeuristicPricing[strongBranching]=68.973s/868`、普通 `HeuristicPricing=22.500s/272`、`TimeIndexedGraphPricing=15.270s/390`。RMIH 只有 `3.170s`，cut 基本为 0。也就是说，本次 360s 的主因是强分支和大列池带来的 RMP/LP 重解压力，其次是 strong-branching trial 中的启发式 pricing，而不是 exact ng-DSSR 本身。

和历史相近配置相比，`nearestK3/top3` no-strong 好配置为 `121.924s/root 64.010s/nodes 45/pool 58052/masterLP 6.215s/exact 35.010s/heuristic 47.207s`；`nearestK8/top10` time-indexed helper on 的可比记录为 `148.524s/root 57.374s/nodes 51/pool 52478/masterLP 9.369s/exact 39.138s/heuristic 59.412s`。这两组虽然节点更多，但没有 strong-branching trial 的大规模 RMP 压力，master LP 只有 6-10s 量级，因此总时间明显更低。本次节点数降到 19，但 master LP 从个位数秒膨胀到 `194.276s`，完全抵消了节点减少和 exact 调用减少的收益。

还需要注意，本次指定命令只设置了 `midpointProbeScore=queue`，没有显式打开 `midpointProbe=true`，也没有显式设置 `completionBound=allCycles` 和 `joinBest=BEST_UB`；日志中的 actual pricing 行显示 `completionBound mode=OFF`、`midpointProbe=off`、`joinBest mode=ZERO`。因此它不能和历史最快 normal ng-DSSR 口径直接解释为“ng-DSSR 变慢”；实际是换成了 strong + preprocessing + 不完整旧加速旋钮的组合。7 月 7 日更早的“有用配置全开”压力测试为 `507.058s/root 105.153s/nodes 17/pool 198265/masterLP 213.664s`，比本次更慢，主要因为池和 pricing round 更大；但二者共同说明 strong/full-open 方向在 40-2 上不是最快配置。

当前结论保持：40-2 原始 setup 上，最快可信 normal ng-DSSR 仍是 `nearestK3/top3`、no-strong、ALNS/RMIH、allCycles completion bound、pricingOnly subtree、midpoint probe/reuse、dual-bound pruning 的 121.924s 记录。本次 k3/top10 strong 配置可作为压力和组件归因样本；若目标是求解时间，应优先回到 no-strong 快速口径，或只做单因素对照测试 strong、root preprocessing、time-indexed pre-heuristic 对 RMP/列池的影响。

151. 2026-07-07 指定 k3/top10 strong run 中 time-indexed pricing 与 strong branching 的实际关系

继续复查第 150 节的 360s run 后确认：该 run 的 `TimeIndexedGraphPricing=15.270s/390` 不是 strong branching phase2 中的前置启发式，也不是主 pricing engine 列表的一部分。日志开头显示 `run.components.pricingEngines=[GCNGBBStyleNgDssrPricing, HeuristicPricing]`，且 `config.enableTimeIndexedPreHeuristicPricing=false`；命令行传入的是 `twet.bpc.fullDomainCompare.timeIndexedPreHeuristicForNgDssr=true`，但当前 runner 读取的正式属性名是 `twet.bpc.fullDomainCompare.timeIndexedPreHeuristicPricing`，因此该开关没有进入实际配置。第 150 节 run 中记录到的 `TimeIndexedGraphPricing` 主要来自 time-indexed root preprocessing 的临时图定价过程。

strong branching 方面，light repair 确实启用了：`config.enableStrongBranchingLightweightRepair=true`，summary 中也有 `strong_branching_light_repair_rmp_build=360 calls` 和 `strong_branching_light_repair_rmp=24.194s/360`。这 360 次对应约 9 个分支节点、每个节点 phase1 测 20 个候选、左右 child 各一次。phase2 也确实运行了，`strong_branching_phase2_build=72 calls`、`strong_branching_phase2_initial=4.498s/72`，对应每个分支节点取 phase1 前 4 个候选、左右 child 重评。phase2 的主要耗时不是建模，而是 heuristic trial：本次只有 `HeuristicPricing[strongBranching]=68.973s/868`，并在 trial 中多次出现单侧加入上千列，例如 root 左右 child 分别 `passes=12/add1467` 和 `passes=16/add1087`。

代码口径上，phase2 只允许 `engine.getName().toLowerCase().contains("heuristic")` 的 pricing engine，以及少数 time-indexed rank1 / outsourcing 特例；普通 `TimeIndexedGraphPricing` 不会进入 phase2。若 `TimeIndexedPreHeuristicPricing` 被正确加入 engine 列表，因为名字包含 `heuristic`，会进入 phase2。7 月 7 日更早的全开压力测试正是这种情况，日志显示 `run.components.pricingEngines=[GCNGBBStyleNgDssrPricing, HeuristicPricing, TimeIndexedPreHeuristicPricing]`，summary 中有 `TimeIndexedPreHeuristicPricing[strongBranching]=17.644s/1378`。但该 run 总体更慢，`pool=198265`、`masterLP=213.664s`、`solve=507.058s`，说明 pre-heuristic 在 strong trial 中虽然能补列，但没有把 Tabu heuristic / RMP 长尾消掉，反而容易扩大列池和 LP 压力。

当前判断：第 150 节的 360s run 不能用来评价 time-indexed pre-heuristic 的列质量，因为它实际没启用 pre-heuristic；它反映的是 light phase1 + Tabu-only phase2 在 k3/top10 strong 下的成本。结合全开压力测试，pre-heuristic 若进入 phase2，直接耗时不大，但会额外产列并扩大 pool，是否值得需要单独做严格 A/B：只改 `timeIndexedPreHeuristicPricing=true/false`，保持 completionBound、midpointProbe、joinBest、strong/light 等完全一致，否则不能归因。

152. 2026-07-07 507s 全开 run 慢因细化：pre-heuristic 与 strong trial 的关系

继续复查 `tmp-ngdssr-40-2-allgood-20260707-105900` 后确认，507s 慢不是单纯因为 `TimeIndexedPreHeuristicPricing` 直接耗时高。该 run 中 pre-heuristic 实际启用，engine 列表含 `TimeIndexedPreHeuristicPricing`，summary 为 `TimeIndexedPreHeuristicPricing=4.676s/247`、`TimeIndexedPreHeuristicPricing[FindFeasible]=0.728s/53`、`TimeIndexedPreHeuristicPricing[strongBranching]=17.644s/1378`。它自身直接耗时约 23s，不是 507s 的主项。

强分支 phase2 中，pre-heuristic 共 `1378` 次调用，其中 `444` 次加列，合计加 `5555` 列；而 `HeuristicPricing[strongBranching]` 共 `934` 次调用，`860` 次加列，合计加 `93886` 列，耗时 `87.551s`。普通节点 pricing 中也类似：pre-heuristic 只加 `252` 列，而 Tabu heuristic 加 `13993` 列。由此可见，pre-heuristic 不能替代 Tabu；它能快速补少量 elementary 列，但大量 child trial 仍要靠 Tabu 继续加列，且 phase2 当前 `strongBranchingPhase2MaxHeuristicPasses=0` 表示直到无列为止，不设 pass 上限。

507s 的最大慢点仍在 master LP / repair trial：`strong_branching_light_repair_rmp=145.394s/400`，平均每次 `363ms`；`strong_branching_phase2_initial=29.459s/74`，平均每次 `398ms`。light repair 确实打开，但它只改变 phase1 seed/RMP，不阻止后续 repair/M/slack 退化。日志中多个被选中分支的另一侧 child 在 repair 后仍不可行或仍含 M/slack，例如 node2 右侧 `Repair RMP still uses branch-implied M columns after generating 6415 columns`，node7/node11/node12 右侧分别在生成 `3262/2936/2071` 列后仍有 positive artificial slack。这类 trial 一边大量补列，一边最终给 INF 评分，是 507s 中强分支代价高的主要现象。

因此对 pre-heuristic 的判断应更细：它不是主要直接耗时，也不是明显“烂列太多”本身拖慢；更准确是召回率/闭合能力不够，很多轮即使 `bestPseudoRC` 很负也只能找到很少 elementary 列或没有 elementary 列，无法让 phase2 提前停下，Tabu 仍继续生成大批列。它额外贡献了 `17.6s` 和约 `5.5k` strong trial 列，并可能扩大 pool，但和 Tabu 的 `93.9k` strong trial 列、repair RMP 的 `145s` 相比不是主因。

当前建议是不要把 507s 简化归因成 pre-heuristic 列质量差。更值得测试的单因素是：限制 strong phase2 pass 数、限制 phase2 中 Tabu 总加列数、或者让 phase2 只做轻量评分而不追求 child heuristic pricing 闭合；如果继续测试 pre-heuristic，应保持 strong/pass/repair 完全一致，只切 `timeIndexedPreHeuristicPricing=true/false`，并重点比较 strong trial 中 Tabu 加列数、repair INF 次数、pool 增长和 master LP 时间，而不是只看 pre-heuristic 自身耗时。

153. 2026-07-07 strong branching phase2 单独关闭 time-indexed pre-heuristic 的无效对照

为复核 507s 全开 run 中 `TimeIndexedPreHeuristicPricing[strongBranching]` 的影响，本次新增了一个窄开关：`timeIndexedPreHeuristicInStrongBranchingPhase2=false`。该开关只在 strong branching phase2 的 pricing engine 选择处过滤 `TimeIndexedPreHeuristicPricing`；普通节点 pricing、find-feasible、repair 和 root preprocessing 仍保留全局 `timeIndexedPreHeuristicPricing=true`。代码层面只改 `TWETBPCConfig`、`GCBBFullDomainComparisonTest` 和 `PC.isStrongBranchingPhase2PricingEngine()`，并通过 focused `javac` 编译。

实际运行目录为 `test-results/bpc/tmp-ngdssr-40-2-allgood-no-phase2-preheur-20260707-compare/`。启动日志确认 engine list 仍为 `[GCNGBBStyleNgDssrPricing, HeuristicPricing, TimeIndexedPreHeuristicPricing]`，且 `config.enableTimeIndexedPreHeuristicInStrongBranchingPhase2=false`、`config.enableTimeIndexedPreHeuristicPricing=true`。但是该 run 没有进入 strong branching：root preprocessing 后根节点直接以 `ROOT_PROCESSED, obj=bound=22582, solve=125.201s, nodes=1` 结束，branch calls 为 0，summary 中也没有 `TimeIndexedPreHeuristicPricing[strongBranching]` 或 `HeuristicPricing[strongBranching]`。

因此这次不能作为“只关闭 phase2 pre-heuristic”的有效 A/B。关键污染在 root preprocessing：本次 `tempPool=82806, avgWindowLen=229.225, timeArcs fixed=3541653, graphFix gap=94.352941`；而 507s baseline 为 `tempPool=26507, avgWindowLen=1881.475, timeArcs fixed=1754589, graphFix gap=3067.496`。根部前几轮 `TimeIndexedGraphPricing` 从第 2 轮开始 added columns / accepted cost 路径已经不同，导致根 LP、窗口收缩和是否进入分支全部改变。本次 125s 结果虽然更快，但最终 incumbent 为 22582，和历史 507s run 在 node 15 找到的 22580 冲突，不能视为更优配置。

当前结论是：phase2 过滤开关本身已经生效，但这次实验没有测到它的作用。若要得到干净结论，需要先固定或复现 root preprocessing 轨迹，再比较 phase2 中 `TimeIndexedPreHeuristicPricing[strongBranching]` 是否会影响 Tabu 加列数、repair INF/M/slack 次数、pool 增长和 master LP 时间；否则结果会被 root 预处理窗口和初始列路径淹没。
## 2026-07-08 启发式 pricing 单 job fast merge 与旧 merge3 对拍

本次针对 50-3 ng-DSSR 配置中启发式 fast path 导致列路径变化的问题做了诊断。做法是让 ADD/EXCHANGE 实际仍走旧 `merge3Segments`，同时用新版单 job fast path 和 `TWETColumnEvaluator.evaluate()` 对第一条不一致候选重算真实列成本。

结论是：不一致不是 fast path 把不可行列误算为可行，而是旧 `merge3Segments` 在 time-indexed root preprocessing 产生的 compact window 口径下会误返回 `curUpperBound=1.0E8`。典型证据为：`reference=1.0E8, fast=11230.0, trueCost=11230.0`，序列 `[25, 35, 40, 31, 21, 9, 10, 19, 23, 46, 8, 4, 30, 49, 28, 1, 14, 41]`。进一步的内部诊断显示旧 merge3 走到 `cost23Skip cost23=1.0E8, bridge=0.0, curUB=1.0E8, sH2=121.0, sH3=0.0`，即问题集中在 `merge23 = single.forward + shiftedB3` 的后向拼接/`findMinimal(true,false)` 口径，而不是候选序列真实不可行。

因此默认保留新版 fast path；旧 merge3 只作为诊断开关保留。之前给 removeCost 增加的额外 overlap 预判断也已去掉，避免同类保守剪枝把有效候选提前剪掉。
进一步复查旧 `merge3Segments` 的出错位置后，当前判断收紧为：问题不是 fast path 没有使用 time window，也不是候选列真实不可行。`TWETColumnEvaluator.evaluate(sequence)` 直接使用原始 `data.penaltyFunction` 和 `data.CmaxH`，不读取 node compact window；新版 single-job fast path 与旧 merge3 reference 都使用同一个 `HeuristicWindowContext`，其中 `windowContext.penalty(job)` 会合并当前启发式可用的 dual/compact window。两者输入口径一致，差异在旧 merge3 的三段通用公式。

旧 merge3 会先独立求 `merge12 = shiftedF1 + b2` 的最优点，再求 `merge23 = f2 + shiftedB3` 的最优点，并用 `cost23 + bridgeCost >= curUpperBound` 直接剪枝。单 job 插入时 `f2/b2` 只是同一个任务的 singleton forward/backward envelope；在 time-indexed root preprocessing 产生 compact window 后，这两个 envelope 带有窄定义域和 BigM 边界。此时 `merge23.findMinimal(true,false)` 可能返回 `1.0E8`，但完整“前缀 + 当前 job + 后缀”的可行 timing 仍然存在。典型证据是同一候选 `fast=11230.0`、真实 evaluator `trueCost=11230.0`，而旧 merge3 在 `merge23` 分支返回 `cost23=1.0E8`。因此旧 merge3 的问题是中间 envelope 剪枝对单 job + compact window 过强，不是窗口本身把该列正确排除。

154. 2026-07-08 ng-DSSR final join 候选列上限提前返回已回退

本节记录一次已撤销的尝试。此前曾尝试在 ng-DSSR final join 中，当 elementary negative candidate 达到列上限后提前停止扫描，并配套调整 dual-bound 证书口径。W300 difficult run 里该策略没有触发有效收益，且会让 pricing 证书、dual-bound 剪枝和 DSSR non-elementary 证据之间的语义变复杂。

当前代码已经回退这组修改：不再提供 final join 候选列上限提前停止开关；summary 也恢复为 `candidatePool kept/seen/dropped`；ng-DSSR pricing engine 重新按完整 pricing 结果返回 certified reduced cost，不再保留额外的证书完成状态判断。后续如果重新考虑 join 层提前返回，需要把“返回负列”和“证明无负列”两类状态分开设计，并单独验证 DSSR 更新证据不会被截断。
155. 2026-07-09 启发式 pricing 与旧 VRP GCTabu 的对齐复查

本次针对“ng-DSSR join 太重时，当前 Tabu 启发式 pricing 是否因为实现错误导致找不到负列”做了代码和日志复查。旧 VRP `GCTabu` 的核心流程是从当前 RMP 低 reduced cost route 中选 30 条 seed，每条 seed 做 50 轮 remove/add/exchange tabu search，过程中遇到负 reduced cost route 放入本地 pool，最后排序并加入前若干列。当前 `HeuristicPricingEngine` 的主体框架与此一致：`heuristicPricingSeedColumns=30`、`heuristicPricingTabuIterations=50`、`heuristicPricingTabuTenure=30`，move 类型也是 remove/add/exchange；它没有 relocate，这一点也与旧 `GCTabu` 一致。

当前实现相对旧 VRP 多了 TWET 必需的几层语义：PWLF 前后向 profile 评价、branch/pricing-only arc 兼容性检查、SRI penalty 增量、time-indexed compact window/dual window 的搜索窗口，以及必要时的真实成本回刷。默认 `enableHeuristicDualProfitableWindow=false`，所以一般配置下启发式找不到列不能直接归因到 dual window 裁剪。compact window 下当前仍按窗口口径直接入池，不逐条 evaluator 回刷，这是此前为了效率和“compact window 是子树硬时间窗证据”的语义所做的选择；如果后续怀疑 compact window 口径，需要单独打开 audit 做成本对拍，而不是把它和旧 VRP 对齐问题混在一起。

以 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 的代表性日志为例，启发式并不是完全失效：`tmp-w300-50-3-r50-joinenv-off-rerun-dump-20260709c` 中 `HeuristicPricing` 调用 34 次，其中 22 次加列、12 次无列，共加入 2589 条列，总耗时约 19.678s；同一 run 的 ng-DSSR exact 为 12 次、415.772s。因此该例的主要瓶颈仍是 exact/join，而不是启发式本身。但日志也显示后期经常出现“Tabu 找不到列，随后 exact 仍能找到少量负列”的情况，例如尾部多次 `HeuristicPricing improved=false` 后，ng-DSSR exact 仍返回 168、32、25、4、1 条列。这说明当前启发式在 tail 阶段确实漏掉了一些孤立或需要较大结构变化的负列。

当前判断是：没有看到明显的实现错误导致启发式系统性找不到负列；更合理的解释是旧 `GCTabu` 类型的 seed-local tabu search 本来就只能作为加速层，不能替代 exact pricing。W300 这类宽窗口/大 label 案例里，exact 负列常常来自较复杂的正反向拼接和特定 timing，未必能由当前 RMP seed 经过 50 轮单点 add/remove/exchange 稳定到达。并且如果 time-indexed pre-heuristic 已经开启，它会先吃掉一批容易的 elementary 负列，留给 Tabu 的 residual 更难。后续若继续优化启发式，应优先考虑诊断每次无列时 exact 返回列与 seed 的编辑距离、是否需要更多/更分散 seed，或引入面向 exact 返回列结构的复合 move；不应先假设当前实现与旧 VRP 不一致。

156. 2026-07-09 ng-DSSR join 优化方向的当前判断

继续复盘 W300/50-3 和 40-2 上的 join 优化尝试后，当前判断是：ng-DSSR 的 join 不是单纯某个 `funcEval` 常数太大，而是“label 数量、group 数量、DSSR 轮次、返回列批量”之间的结构性瓶颈。传统 label-level join 虽然一次扫描很重，但它能在同一轮里返回较多 elementary 负列，并且完整保留 no-negative certificate；此前直接把 join 改成 group envelope 后，`funcEval` 能从千万/亿级压到很低，但每个 group-pair 只返回一个代表 source pair，导致本轮返回负列变少、DSSR 轮次和扩展时间显著增加，最后总时间不一定下降，甚至会变慢。

因此，当前已经试过但不适合作为默认的方向包括：final join 达到候选上限就提前停止、直接用 traced group envelope 替代 label-level join、以及把 half-domain 函数改成 full-domain 后再做路径去重。前者会破坏“返回负列”和“证明无负列”的证书区分；第二个会漏掉同一 group-pair 内非 envelope 代表但能产生有效 elementary 负列的 source pair；第三个会使扩展和函数维护成本显著上升，抵消 join 侧收益。

更可行的后续方向不是“彻底替换 join”，而是把 group/envelope 当作安全筛选或局部加速层：先用 group envelope 做乐观下界，能剪掉的 group-pair 直接剪；不能剪掉的仍回退到 label-level join，或者在负 group 内返回多个 source pair 而不是一个代表。这样能保留 exact certificate 和批量加列能力，同时减少明显不可能产生负列的拼接。另一个方向是扩展前的保守 scalar lower bound 剪枝，减少进入 join 的 label 数，但这需要证明不会误剪，并且收益取决于 bound 是否足够强。

当前最重要的实验结论是：在小整数、紧 due-date 或 time-indexed pseudo-schedule 接近 elementary 的算例上，time-indexed shortest path 天然很占优，ng-DSSR 不应强行在这些场景上追求更快；ng-DSSR 更适合作为时间尺度大、小数 scale、宽 due window、外包/复杂分支或 pseudo-schedule 明显变弱时的对照方法。后续若要证明 ng-DSSR 的优势，应优先构造或筛选这些场景，而不是继续只在当前 W300 root join 上硬抠常数。

157. 2026-07-09 W300/50-3 ng-DSSR root 配置与耗时复查

本次复查对象是 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 的 root 诊断 run：`test-results/bpc/tmp-w300-50-3-r50-joinenv-off-rerun-dump-20260709c`，模式名为 `halfDomain-ng-nearestK3-top3-ngWinRep`。CSV 结果为 `NODE_LIMIT`，`nodes=1`，`incumbent=1992.000000`，`bound=1726.014329`，`gap=13.352694%`，`solve=501.969s`，`root=495.939s`，`pool=8501`。该 run 是 root-only 诊断，不是完整 BPC：`maxNodes=1`，且 `enableTwoStageStrongBranching=false`，所以不能用它评价强分支后续树搜索。

配置复查显示，主线增强基本都已打开：ALNS seed 打开且限制 30s，SA 关闭；`GCNGBBStyleNgDssrPricing`、`HeuristicPricing`、`TimeIndexedPreHeuristicPricing` 都在 pricing engine 列表中；启发式 pricing、RMIH、dual-bound pruning、midpoint probe、`allCycles` completion bound、scalar pruning、subtree arc elimination、pricing-only subtree arc elimination、time-indexed root preprocessing、time-indexed pre-heuristic、root preprocessing seed 200、repeatability ng-set filter 都已打开。SRI、partial、route enumeration、dual stabilization 关闭；外包模式为 `masterVariables`。需要注意的两个点是：`bidirectionalJoinBestThresholdMode=zero`，不是 BEST_UB；`bidirectionalJoinRangeRestrictedLowerBound=false`，range-restricted join LB 没有启用。也就是说，本次不是“关键配置漏开”导致慢，而是在当前 root-only 口径下，少数尚未启用的实验性 join 剪枝没有参与。

time-indexed root preprocessing 确实运行并生效：临时 pool 到 `32640`，写回 `seedElementaryCols=200`，固定时空弧 `4316469`，推广普通 arc `121`，所有 50 个 job 都得到 compact window，平均窗口长度 `822.440`，平均收缩比 `0.631`。但这组 W300 下 `ngWindowRepeatability=timeIndexed/repeatable50/nonRepeatable0`，说明 repeatability 过滤虽然开启，却没有排除任何 job；也就是说，W300 下所有 job 仍被判定可能重复访问，`nearestK3/top3` 的 ng 初始化并没有因为 repeatability 大幅变小。

总耗时拆分为：`exact=415.772s/12`，`heuristic=19.678s/34`，`masterLP=8.780s`。因此瓶颈非常明确：不是 master LP，也不是启发式；主要是 ng-DSSR exact pricing。启发式 34 次中 22 次加列，共加 2589 列，说明它不是完全失效，但 tail 阶段经常无列，而 exact 仍能找到少量列。

exact pricing 内部不是始终只慢在 join。第一轮 exact 用 `40.481s`，其中 `init=10.369s`、`fw=9.128s`、`bw=6.137s`、`join=14.685s`，join 是大头之一；这一轮 join 尝试约 `5193` 万 pair、`5135` 万次 funcEval，返回 5000 列。中后期返回列变少后，DSSR 多轮更新成为更严重的问题：例如返回 1 列的两轮分别用 `57.065s` 和 `81.708s`，其中第二轮 `init=24.365s`、`fw=12.183s`、`bw=36.003s`、`join=8.993s`，join 已不是最大项；最终 no-negative certificate 轮用 `135.595s`，`rounds=17`，其中 `init=36.417s`、`fw=22.010s`、`bw=62.430s`、`join=14.499s`。这说明尾部耗时主要来自多轮 DSSR 下的反复初始化、正反向扩展和 dominance/envelope 维护，join 仍重但不是唯一瓶颈。

completion bound 在早中期也有明显成本，但不是总耗时主因。早期几轮 buildMs 在 `3.6s-5.8s`，并且能做大量 scalar pruning；后期复用后 buildMs 为 0，但 exact 仍能到几十秒甚至 135s，说明后期主要不是 completion bound 构造，而是 DSSR 多轮扩展和最终证书轮。`completionBoundArcFixing` 在 root 内逐步固定普通 arc，但它更多影响后续 pricing 空间，不能单独解决当前 W300 root 的尾部收敛。

当前结论是：这组 50-3 W300 的慢，不是因为“好配置没打开”，而是由于宽 due window + setupR50 让 time-indexed preprocessing 虽然压缩了窗口和大量时空弧，但仍无法让 ng-DSSR 的 repeatability/label 空间变小；root exact pricing 后期需要很多 DSSR 轮才能证明无负列。后续优化如果继续针对这类算例，应优先看两条路：一是减少尾部 DSSR 轮和每轮扩展成本，例如更有效的 ng-set 更新或更保守但安全的扩展前 lower-bound 剪枝；二是把 group/envelope 用作安全筛选或负 group 局部多 source-pair 扫描，而不是完全替代 label-level join。

补充复查 `exactPhaseMs init` 的含义后确认，该字段对应 `GCNGBBStyleBidirectionalNgDssr.initialize(lp)` 的累计时间，不是简单对象初始化。它包含 reset 统计、trace/diagnostic 上下文、SRI 预计算、dynamic pricing window、ng-set 初始化或继承、completion bound 构造/复用、pre-certificate、midpoint probe、label store/queue/candidate state 和 forward source 初始化等。更重要的是，该时间在一次 exact pricing 的多轮 DSSR 内累计；最终 no-negative certificate 的 `init=36.417s` 是 `rounds=17` 的累计值，平均每轮约 `2.14s`。同一条 summary 里的很多计数和 `completionBound buildMs` 则更接近最后一轮口径，因此不能把 `buildMs=0` 解读为 17 轮完全没有 completion-bound 准备成本。

该 run 没有打开 `twet.bpc.fullDomainCompare.ngDssrSetStats`，所以日志没有逐轮 `ngSetSize avg/min/max/updateByRound`。能从现有日志确定的是：该次 final certificate 使用 `nearestK3` 初始化，repeatability 过滤结果为 `repeatable50/nonRepeatable0`，因此初始通常为每个 job 3 个 ng 成员；最后一轮 `totalNgSetUpdates=258`，若按 50 个 job 均初始 3 个成员计算，最终平均 ng-set 大小约为 `(50*3+258)/50 = 8.16`。但最小值和最大值在该日志中不可恢复，需要用同配置加 `ngDssrSetStats=true` 重跑到该 pricing 轮才能精确得到。

从优化角度看，`initialize()` 里最值得先动的不是 parse 参数或创建队列这类小开销，而是 midpoint probe 和多轮 DSSR 下的重复准备。该日志 final certificate 的最后一轮 probe summary 中两个 candidate 分别约 `792ms` 和 `744ms`，本身就接近每轮 `init` 平均值的大头；如果 17 轮都做类似 probe，量级可到二十多秒。此前配置 `bidirectionalMidpointProbeReuseWithinNode=false`，已有的 reuse 逻辑没有启用；后续已改为默认打开，但需要注意该机制只是用同 node 历史最佳 `tMid` 作为下一次 exact pricing 的 probe reference，仍会运行 probe，不是完全跳过。更激进的优化是同一次 exact pricing 内 DSSR 后续轮复用上一轮 `tMid` 或减少 probe 候选，但这会改变半域平衡质量，需要单独对比 exact 时间、返回列数和 DSSR 轮数。

进一步讨论后，当前更直接的判断是：同一次 ng-DSSR exact pricing 的多个 DSSR round 之间，LP dual、node 分支状态、pricing-only arc、compact window 和 completion bound 口径都不变，变化的主要是 ng-set 变紧。因此每一轮都完整跑 midpoint probe 的边际价值可能不高。理论上任意合法 `tMid` 都不改变 exact pricing 的可行列族和最优性，只影响正反向扩展平衡和 join 成本；所以在同一次 exact pricing 内复用第一轮或上一轮选出的 `tMid` 是正确性上可接受的性能策略。风险在于 ng-set 更新后正反向 label 空间可能不再平衡，如果完全不 re-probe，可能省下 probe 时间但增加 forward/backward/join 时间。较稳的实验方案是：第一轮完整 probe；后续 DSSR round 默认复用 `tMid`，只在上一轮正反向 kept/queue 比例严重失衡或每隔若干轮时再做一次轻量 probe。这样比当前跨 exact call 的 `bidirectionalMidpointProbeReuseWithinNode` 更贴近问题来源，也更可能降低 W300 tail certificate 的 `init` 成本。

本轮按上述判断先落地最小实现：新增 `bidirectionalMidpointProbeReuseWithinDssr`，默认开启；full-domain runner 属性名为 `twet.bpc.fullDomainCompare.midpointProbeReuseWithinDssr`。同一次 `GCNGBBStyleBidirectionalNgDssr.solve()` 内，第 1 轮 DSSR 仍完整执行当前 midpoint probe，且如果 `bidirectionalMidpointProbeReuseWithinNode=true`，第一轮的 probe reference 仍可来自同 BPC node 的历史 best exact `tMid`。第 2 轮及以后不再重新跑 probe，而是复用第 1 轮最终选中的 `tMid`，随后重建 half-domain 派生缓存并重置 probe 影响的统计。这里刻意不复用第 1 轮 probe 生成的 rank0 label，因为 DSSR 更新 ng-set 后旧 label 的可扩展状态已经不再适用。

该实现和已有 node 级复用的边界是：node 级复用跨 exact pricing 调用，只给下一次 exact pricing 的第一轮 probe 提供 reference；DSSR 内复用只在同一次 exact pricing 的多轮 DSSR 之间跳过重复 probe。两者可以叠加，但不共享 label，也不改变 completion bound、ng-set 更新或 final join 的证书语义。正确性上，`tMid` 只是半域分割点，任意合法 `tMid` 都不改变可生成列族；风险只在效率，即第一轮 `tMid` 在后续 ng-set 变紧后可能不再是最平衡的点，需通过 W300/50-3 这类 tail certificate 场景 A/B 观察 `init` 下降是否被 `fw/bw/join` 上升抵消。focused `javac` 已通过。

补充验证：用 `wet020_001_2m` 做 30s root-only smoke，显式传入 `twet.bpc.fullDomainCompare.midpointProbeReuseWithinDssr=true`、`midpointProbe=true`、`ngDssr=true`、`runALNSForSeed=false`、`maxNodes=1`。该小例 root 一次 ng-DSSR pricing 即闭合，`rounds=1`，所以没有触发第 2 轮复用分支；但日志确认系统属性被读取，求解 `valid=true`，说明新增开关入口和原单轮 probe 路径没有破坏。后续仍需要在 W300/50-3 这类多轮 DSSR tail case 上做真正 A/B。

复查实现时发现一处具体问题：第一版补丁中 `ngDssrFirstRoundTmidAvailable=true` 被乱码注释吞入注释行，导致第 1 轮虽然记录了 `tMid`，但 available 标记没有真正置位，第 2 轮及以后不会实际走复用分支。本轮已修正为显式赋值，并清理该文件因局部替换产生的异常行尾。focused `javac` 与 Java diff check 已通过。
158. 2026-07-09 W300/50-3 exact pricing 热点与 ng-set 更新策略复查

继续复查 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 的 ng-DSSR root-only 日志后，当前可以把 exact pricing 的慢因拆得更清楚。传统 label-level join 口径下，代表日志 `tmp-w300-50-3-r50-joinenv-off-rerun-dump-20260709c` 聚合 12 次 ng-DSSR exact pricing，总 exact 约 `415.6s`，其中 `init=151.7s`、`fw=58.5s`、`bw=151.5s`、`join=53.2s`，completion-bound build 约 `42.8s`，funcEval 约 `8968` 万次。也就是说 join 确实重，但它不是唯一瓶颈；尾部 certificate 轮次里，反向扩展和每轮初始化/准备同样很重。

join-envelope 实验口径 `tmp-w300-50-3-r50-joinenv-on-rerun-dump-20260709d` 把 join 本身从约 `53.2s` 压到约 `7.5s`，funcEval 从约 `8968` 万次降到约 `71` 万次，但 exact 总时间反而到约 `599.9s`，主要因为 exact 调用和 DSSR 轮数增加，`init=281.1s`、`bw=233.6s`。这再次说明第一版 envelope 不能直接替代 label-level join：它可以大幅减少拼接计算，但每个 group-pair 只返回少量 source pair，会削弱批量加列能力，增加后续 DSSR 轮次和扩展时间。当前更合理的方向仍是把 envelope 当作安全下界筛选或局部回退工具，而不是默认替代完整 join。

从当前 `updateNgNeighborhoodsFromNonElementaryRoutes()` 实现看，ng-set 更新确实偏粗放。代码对保留下来的 top 非基本负序列逐条扫描；只要某个 job 重复出现，就把该 repeated job 加入两次出现之间所有 middle job 的 ng-set。这个做法能消掉当前非基本序列，但没有判断后面的非基本序列是否已经会被前面选中的更新挡掉，也没有为每条非基本序列选择“最小必要更新”。在 W300 日志中，虽然 `ngDssrNonElementaryRouteUpdateLimit` 很小，但累计仍出现大量 DSSR rounds 和 ng-set updates，说明这条路径值得继续优化。

更稳的改法不是简单地说“第二好列里有同一个 i<-j 更新就跳过”，因为只加一个 middle job 通常不足以阻止重复访问；一个 repeated job 要被阻止，通常需要该 repeated job 在两次出现之间的整段 middle jobs 中都被保留在 ng memory 中。更准确的 selective update 口径是：按 reduced cost 从好到差处理已保留的非基本负序列，维护一份“本轮计划新增 ng pair”的 overlay；处理某条序列前，先检查它是否已经有某个重复段被当前 ng-set 加 overlay 完整阻断。如果已经阻断，则这条序列下一轮不会原样再出现，可以跳过它的其它更新。若尚未阻断，则在它的多个重复段中选择 missing pair 最少的一段，只加入足以阻断该序列的那组 pair，而不是把所有重复段的所有 middle pair 都加入。

这个策略的正确性来自它仍保证被处理且未被已有更新阻断的非基本负序列会被新增 ng-set 消掉；它只是避免对已经会消失的序列继续加无用 pair，并把“消掉一条序列”从全量更新改成最小阻断更新。风险是更新更保守后，可能需要更多 DSSR 轮才能消掉其它非基本序列；收益是 ng-set 增长更慢、label 空间可能更小，尤其适合 W300 这种尾部多轮 DSSR、扩展和初始化很重的场景。后续如果实现，应作为独立开关保留旧策略，并记录每轮 stored route、skipped-as-already-blocked、selected block missing pair 数、实际 updates、后续 rounds/labels 的变化。
159. 2026-07-09 W300/50-3 init/backward 热点与 ng-set pair 更新口径补充

继续复查 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 的 root-only 日志后，确认 `exactPhaseMs init` 不是单纯对象初始化，而是 `GCNGBBStyleBidirectionalNgDssr.initialize(lp)` 在同一次 ng-DSSR exact pricing 的多轮 DSSR 内累计的准备时间。它包含 dynamic pricing window、completion bound 构造或复用、ng-set 初始化、midpoint probe、半域函数重建、label store/queue/candidate state 初始化等。尾部 no-negative certificate 里的 `init=36.417s, rounds=17` 应理解为 17 轮 DSSR 的累计，平均约 2.14s/轮；其中 midpoint probe candidate 在日志里常见 0.7-0.8s/个，是 init 里最值得优先压缩的部分。当前新增的 `bidirectionalMidpointProbeReuseWithinDssr` 正是针对同一次 exact pricing 内多轮 DSSR 重复 probe 的成本，语义上只复用第一轮选出的 `tMid`，不复用 probe labels。

backward 重的原因不是一个单独 bug，而是 W300 下 compact window 后所有 job 仍被判定为 repeatable，ng-set 初始仍有较多可重复空间；同时当前 midpoint probe 只按候选队列/remaining 比例平衡正反向，不保证真实后向扩展成本最小。尾部证书轮中 `bw=62.430s` 明显高于 `fw=22.010s`，说明后向 label 扩展和 dominance/envelope 维护仍是主要成本之一。若复用第一轮 `tMid` 后 init 下降但 bw/join 上升，需要再判断是否增加轻量 re-probe 条件。

ng-set 更新口径需要补充澄清。当前 `updateNgNeighborhoodsFromNonElementaryRoutes()` 已经不会把同一个 `middleJob <- repeatedJob` pair 重复加入两次，因为加入前会检查当前 ng-set 是否已经包含该 pair。真正的冗余不是“同一个 pair 被重复写入”，而是后一条非基本路径可能已经被前面路径新增的一组 pair 完整阻断，但代码仍然继续扫描它，并可能再加入其它 pair。只因为第二条路径里含有某个已经新增过的 `i <- j` pair 就整条跳过并不严谨；对 repeated job 的一次重复访问，通常要让 repeated job 在两次出现之间的必要 middle jobs 中都被保留，才能保证该非基本路径不会原样再出现。更稳的 selective update 应按 repeated segment 判断：当前 ng-set 加本轮 overlay 是否已经完整阻断该 segment；完整阻断则跳过该路径，否则选择缺口最小的 segment 补齐 missing pairs。这样既符合用户提出的“前面更新已经能挡住后面路径就不要再更新”的方向，也避免因单个 pair 重复而过早跳过导致 DSSR 后续仍反复生成同一类非基本路径。

补充配置决定：`bidirectionalMidpointProbeReuseWithinNode` 也改为默认打开。它和 `bidirectionalMidpointProbeReuseWithinDssr` 不冲突，前者只影响同一个 BPC node 内下一次 exact pricing 的第 1 轮 probe reference，后者只影响同一次 ng-DSSR exact pricing 内第 2 轮及以后是否跳过重复 probe。两者都只改变半域平衡选择，不改变 exact pricing 的列族和最优性；风险只在效率上，即历史 `tMid` 或第一轮 `tMid` 在后续 dual/ng-set 下可能不再最平衡。
160. 2026-07-09 W300/50-3 正反向扩展细分计时

为进一步确认 ng-DSSR exact pricing 慢在正反向扩展的哪个环节，本次在 `GCNGBBStyleBidirectionalNgDssr` 中新增了诊断开关 `ngDssrExtensionTimingDiagnostics`。该开关默认关闭，只在打开时输出正反向扩展内部的细分时间，包括 arc 检查、label 函数构造、window 检查、state/ng-memory 构造、completion-bound 检查、dominance graph 插入和队列操作。它不改变求解逻辑，只用于定位耗时。

复查时发现第一版统计 reset 不完整：`extensionTimingMs` 的纳秒字段和 backward extension counters 没有在每次 exact pricing / probe reset 后完整清零，导致多次 pricing 汇总时可能重复计入旧轮次。已改为统一调用 `resetExtensionStatistics()`，同时清理 forward/backward 候选计数和所有 extension timing 字段。需要注意，日志里的 `build` 是 `extendForward/extendBackward` 外层总耗时，已经包含 `window/function/state` 三类内部子项；这些子项只能用于拆解 build，不能和 build 再相加。

修正后用 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 做 root-only 诊断，3 次 ng-DSSR exact pricing 总计约 `56.4s`，其中 `init=27.7s`、`fw=6.3s`、`bw=13.7s`、`join=8.6s`。因此这组慢因仍不是单纯 join，而是 init、backward 扩展和 join 共同构成，其中 init 是最大项。completion bound 构建累计约 `15.4s`，解释了 init 的主要部分；它同时完成约 `255.9` 万次 bound eval，剪掉 forward `78.5` 万、backward `133.7` 万扩展。

正反向扩展内部的差异也比较明显。3 次 pricing 中，forward 一共检查约 `130.0` 万个候选，实际构造约 `115.6` 万个 label，completion-bound 后保留约 `37.1` 万；backward 一共检查约 `203.1` 万个候选，实际构造约 `168.3` 万个 label，bound 后保留约 `34.6` 万。forward 的 build 总耗时约 `1.85s`，其中函数操作约 `0.36s`、状态/ng-memory/dominance-set 构造约 `1.12s`；completion-bound 检查约 `2.03s`，insert 约 `1.86s`，其中 dominance graph insert 约 `1.82s`。backward 的 build 总耗时约 `3.97s`，其中函数操作约 `0.79s`、状态/ng-memory/dominance-set 构造约 `2.61s`；completion-bound 检查约 `3.58s`，insert 约 `5.35s`，其中 dominance graph insert 约 `5.29s`。

当前判断是：dominance graph 操作确实是 backward 扩展的大头之一，但不是唯一瓶颈。backward 的候选数和构造 label 数远高于 forward，导致函数构造、completion-bound 检查和 dominance graph 插入同时变重；init 里的 completion-bound 构建也很重。后续如果继续优化，优先级应是：先减少 backward 候选和构造 label 数，其次优化 completion-bound 构建/查询，再考虑 dominance graph 插入常数项。单纯优化 join 或单纯优化 dominance graph，都不能覆盖这组 W300 case 的主要耗时。

161. 2026-07-09 当前 ng-DSSR 初始化和旧 VRP GCNGBB 的差异

本次代码口径已按旧 VRP 风格切换：默认 `ngDssrInitialNgSetMode=dualPair`，新增 `ngDssrInitialNgPairCoefficient=0.08`，在 `dualPair/reducedCostPair` 模式下按 `floor(n * coef)` 全局选择 reduced pair cost 最小且为负的 pair，并把两个方向互相加入 ng-set。这样默认行为不再按每个 job 固定填满 K 个 nearest 邻居；`ngDssrInitialNgSetSize` 只保留给 `nearestK/full/history` 等旧实验入口或显式覆盖使用。当前 pair reduced cost 沿用已有 TWET 口径：`setupCost(i,j)-arcDual(i,j)-jobDual(j) + setupCost(j,i)-arcDual(j,i)-jobDual(i)`，本次只对齐旧 VRP 的“全局少量负 pair”数量控制，不额外引入时间函数或 setup time 距离项。

复查旧 VRP `GCNGBB.java` 后确认，旧 GCNGBB 的 `Reset()` 会把 `m_low_ng_set/m_high_ng_set` 全部清空，默认初始 ng-set 是空的。只有 `data.m_type == 2` 时，`Extend()` 开始会调用 `ChooseNeighbor()`，按 `customer_number * m_col_coef` 选择一批双向 reduced-cost pair，默认 `m_col_coef=0.08`，并且选完后把 `m_col_coef` 置 0，表示这种预加只做一次。旧实现的 pair 指标是 `dist(i,j)-arcDual(i,j)-mu[j] + dist(j,i)-arcDual(j,i)-mu[i]`，只选择负 pair，并把两个方向互相加入 ng-set。

旧 VRP 的 DSSR 更新也比较保守：join 阶段只记录本轮 reduced cost 最好的非 elementary route，即 `m_best_cycle`。如果这个 route 中某个 customer 重复出现，就把该 repeated customer 加入两次出现之间所有中间 customer 的 ng-set。也就是说旧 VRP 每轮只用一个最好的非基本负路径更新，不是 topK 更新。

当前 TWET 主线默认差异较大。`TWETBPCConfig` 默认 `ngDssrInitialNgSetMode=nearestK`、`ngDssrInitialNgSetSize=8`、`ngDssrNonElementaryRouteUpdateLimit=1`。初始化时每个 job 会按 `setup(i,j)+setup(j,i)+setupCost(i,j)+setupCost(j,i)` 选最近的 K 个成员；这比旧 VRP 的默认空集强很多，也和旧 VRP type-2 的 reduced-cost pair 预加不是一个口径。当前也提供 `empty` 模式，以及 `dualPair/reducedCostPair` 模式，后者更接近旧 VRP 的 pair 预加思想，但当前实现是给每个 job 填到 target size，而旧 VRP 是全局选约 `0.08n` 对负 pair，数量和触发条件都更保守。

因此，如果问“当前默认和旧 VRP 区别大不大”，答案是大：当前默认 nearestK8 明显更大、更静态；旧 VRP 默认空，只在 type-2 上按负 reduced-cost pair 少量预加一次。若要做接近旧 VRP 的对照，应优先测试 `ngDssrInitialMode=empty, ngDssrRouteUpdateLimit=1`，或者单独做一个旧 VRP 风格的 `dualPairGlobal` 初始化：全局只选约 `0.08n` 对最负 pair，而不是每个 job 填满 target size。

补充解释：旧 VRP 区分不同类型是否预加初始 ng-set，本质是因为不同算例类型的“非 elementary 松弛风险”和“labeling 单轮成本”不一样。type-1 类算例通常资源约束更紧，时间窗或容量会自然限制路径长度和重复访问，空 ng-set 下 DSSR 往往也能较快收敛；此时预加较大 ng-set 会削弱 dominance、增加 label 数，可能得不偿失。type-2 类算例通常时间窗/容量更宽、单条 route 更长，可行弧更多，ng-relaxed pricing 更容易出现靠重复访问吃 dual 的非 elementary 负列；若从空 ng-set 开始，可能要多轮 DSSR 才能把关键重复段禁掉。因此旧 VRP 只对 type-2 做一次很小的 reduced-cost pair 预加，目的是提前挡住最可疑的双向负环或近似 2-cycle，而不是一开始就把 ng-set 做大。这个设计体现的是“按算例结构平衡松弛强度和 dominance 效率”，不是简单地认为初始集合越大越好。

162. 2026-07-10 ng-DSSR exact pricing 后续优化优先级复查

本次继续沿 `GCNGBBStyleBidirectionalNgDssr` 的真实主线复查 `initialize -> forward/backward expansion -> dominance graph -> final join`。代表证据仍采用 W300/50-3 root-only 日志 `tmp-w300-50-3-r50-joinenv-off-rerun-dump-20260709c`：12 次 exact pricing 合计约 `415.6s`，其中 `init=151.7s`、`fw=58.5s`、`bw=151.5s`、`join=53.2s`。第一轮约 `5223` 万个 join candidate 中有 `5135` 万次进入函数拼接，现有 arc/time/scalar 前置判断只挡掉约 `1.7%`；尾部 no-negative certificate 又需要 17 轮 DSSR，说明当前瓶颈既有早期 join 的笛卡尔积，也有尾部多轮扩展和 dominance 维护，不能只优化某一个 `funcEval` 常数。

`init` 方面，最近完成的 completion-bound multi-delta + time-priority 已经是当前 `ALL_CYCLES` 默认路径。40-2 对拍中非零 build 平均由约 `1207.8ms` 降到 `309.9ms`，但 W300 尚未按新默认正式重跑，因此不能直接把同样倍数套到 W300。代码层面 `dynamic window`、ng-set 初始化、active signature 和 completion bound 已在同一次 DSSR solve 内复用；同一次 exact pricing 的后续 DSSR round 也已复用第一轮 `tMid`。在这些改动之后，`init` 剩余值得关注的主要是每个 exact call 第一轮仍要做的 midpoint probe，以及带 SRI 时每轮重复建立 cut/state 辅助结构。跨 exact call 直接复用 completion bound 不成立，因为 LP dual 已变化；更合理的实验是让 midpoint probe 使用真实的正反向单位成本，而不是只平衡 label/queue 数量。W300 诊断中 backward 每个 survivor 的 dominance/state 成本明显高于 forward，因此 probe 应允许把 `tMid` 向减少 backward 工作量的方向偏移，并把预估 join 乘积纳入 score。该调整不影响列族和正确性，只改变性能轨迹。

扩展阶段最明确的低风险优化是把 completion-bound 判断前移到完整 child state 构造之前。当前顺序是先完成 PWLF、复制 visited/ng-memory、构造 dominanceSet 和 extensionSet、分配 child label，再做 completion-bound。3 次 W300 诊断中 forward 构造约 `115.6` 万个 child，bound 后只剩 `37.1` 万；backward 构造约 `168.3` 万，bound 后只剩 `34.6` 万。也就是说约四分之三的 child 在被 bound 删除前已经支付了 bitset copy、两个可达集合扫描和 label 分配。completion-bound 实际只需要 child job、no-SRI frontier/no-SRI min 和相应 prefix/suffix bound；无 SRI 时 `FunctionLabel` 构造器会把 `noSriFrontier` alias 到 `frontier`，并不存在前面一度怀疑的 null 失效问题。因此可以先构造函数并做 bound，只有 survivor 才建立 visited/ng/dominance/extension state。该改法不改变任何 bound 或 dominance 语义，预计首先改善 backward。

同一位置还有两个次级优化。第一，`buildDominanceSet` 和 `buildExtensionSet` 目前先后扫描 job，并重复做一部分时间可行性判断，可以在 survivor 上用一次 job 扫描同时填两个 bitset；dominance key 仍保持 full-domain 口径，extension set 再叠加 half-domain 条件，语义不变。第二，无 SRI 时每个 child 仍会通过 `copySriCounts()` 创建空数组，可以改为共享空状态；extensionSet 为空的 label 仍要保留供 join/回溯，但无需进入扩展队列。把当前 node 的 pricing-forbidden arc 直接过滤进 extensionSet 也可减少后续无意义 pop，不过只应影响 extensionSet，不能随意改变 dominance key。更激进的“PWLF 构造前 scalar bound”只有在能便宜取得合法区间下界时才值得做，建议先加命中统计，避免像此前 completion-bound endpoint shortcut 一样因低命中率反而变慢。

dominance graph 的最高价值候选是同 key 快路径，而不是重新启用已证明端到端不稳定的 indexed backend。第一轮日志中 `paperGraph labels kept/rejected=49839/873447`，只创建 `631` 个 graph node；最终 active graph 又只有 `347` 个 node、`43463` 个 label，平均每个 active node `125.254` 个 label。这证明同一 reachable key 下保留多 label 是普遍现象，但不能仅由 `nodesCreated` 反推出所有 rejected insert 都命中了同 key，因为一部分新 key candidate 也可能被非相等的 superset node 直接支配。实现前应先增加 `sameKey hit/miss` 计数。当前一旦命中同一 node，仍会创建 candidate list、复制该 node 的 dominance envelope 做一次 dominance check；保留 label 后还会重建 `dominanceEnvelope=min(labelEnvelope, predecessorEnvelope)`，并无条件调用 `propagateAndTrim()`。这里可以直接用同 node 的现有 dominanceEnvelope 做只读判断，省掉候选列表和 envelope copy。新增 label 时用增量 `mergeMinimum(..., reportChanged=true)` 更新 labelEnvelope 和 dominanceEnvelope；只有 dominanceEnvelope 真正变化时才向 successors 传播。label 本身仍需保留用于 join/路径恢复，即使它不贡献 envelope，也只是跳过无效传播。这个 fast path 与当前数学语义一致，实际收益取决于待补的 same-key 命中率，但从每个 active node 的 label 密度看值得优先验证。

join 方面，`findMinimalShiftedSumValue()` 已经是直接双指针扫描，不再构造 `shiftX + add` 临时 PWLF；`getForward/BackwardJoinExtension()` 也按 label 缓存。因此当前问题主要是 pair 数量，不是单次函数拼接还有明显冗余。此前完整 group-envelope 替代虽然把 join 从约 `53.2s` 降到 `7.5s`，却因为每个 group-pair 只返回一个代表 source pair 而减少批量加列，最终 exact 反而从约 `415.6s` 增至 `599.9s`。后续应改成只作过滤：按 `(terminal job, exact ngMemorySet)` 建正反向 envelope；若某个 group-pair 的 envelope 拼接最小值已经非负，则该组内所有真实 label-pair 都不可能为负，可以整组安全删除；若 group bound 为负，则回退扫描原始 labels，保持原来的列集合、批量和 certificate。现有 envelope 诊断第一轮只有 `173/173` 个正反 group，build 约 `0.221s`，`29929` 个 group-pair 中 `13433` 个可直接剪掉，说明 group 层有现实命中率；但这些 group 各自覆盖多少原始 label-pair 尚未统计，不能直接把 `44.9%` group prune 当作同等比例的 funcEval 节省。实现时应同步统计 pruned group 对应的 label cross-product。还可做 group-group -> group-label -> label-label 的分层门控，但每一层通过后都必须回到真实 label，不能再只返回 envelope contributor。

另一个可与 group filter 叠加的安全 join 优化是 top-K worst threshold。当前 W300 使用 `joinBest=ZERO`，为了批量返回负列会扫描所有负 pair；`BEST_UB` 虽剪得更狠，但只围绕当前最优 reduced cost，容易少返回大量仍有用的负列。更合适的口径是：candidate heap 未满时阈值仍为 0；达到 `maxExactPricingColumns` 后，用当前 heap 中最差的 retained reduced cost 作为阈值。若某 pair 的合法 lower bound 已不优于该阈值，它不可能进入最终 top-K，可安全跳过；在无 dual-window/SRI/partial 真成本回刷的 normal 路径下，这应返回与完整 ZERO 扫描相同的 top-K signature/cost，同时仍保留全局最优 reduced cost。需要真成本回刷的路径不能直接使用 inferred heap threshold。group 按 lower bound best-first 只有和这种会逐步收紧的阈值结合才有意义；在固定 ZERO 阈值下单独排序不会减少最终扫描量。

不建议近期优先做的方向包括：恢复 full-domain join、按 canonical split 让每条 sequence 只拼一次、直接用 group envelope 替代 labels、提前达到列数就终止 exact，以及在 linked-list PWLF 上继续增加一次同量级 range scan。这些方向要么已经实测增加扩展/DSSR 轮数，要么破坏当前 dual-bound/certificate 口径，要么只是把一次 funcEval 换成另一次近似同成本扫描。若 group filter 后 pair 数仍然很大，再考虑把 active join frontier 转成只读 primitive-array 表示，减少 linked-list 指针追踪；这属于第二阶段常数优化，不应排在减少 pair 数之前。

综合优先级建议为：第一，先按新 completion-bound 默认重跑 W300，得到新的 init 基线；第二，实现并对拍 dominance same-key fast path；第三，把 completion-bound 前移到 state 构造前，并合并 survivor 的 dominance/extension set 扫描；第四，实现只过滤不替代的分层 join envelope；第五再测试 top-K worst threshold 和 cost-aware midpoint。前三项应以“相同每轮列 signature/cost、相同 relaxed best reduced cost、相同 DSSR rounds”为验收标准；后两项允许执行顺序不同，但必须保持最终 LP bound、no-negative certificate 和完整求解目标一致。任何局部优化都要同时记录 `init/fw/bw/join`、constructed/survivor、dominance copy/propagate、group pruned/fallback pair 和 returned columns，不能只看某个局部计时。

163. 2026-07-10 后续代码修改按预期收益排序

按“端到端预期收益优先，同时避免改变批量返回列和证书语义”的口径，实施顺序调整为：第一，扩展阶段把 completion-bound 检查前移到 visited/ng/dominance/extension state 构造之前，并合并 survivor 的 dominanceSet/extensionSet 扫描；现有日志显示约四分之三的 child 会在 bound 处淘汰，这是证据最充分、风险最低的直接收益。第二，实现 dominance graph 的 exact-same-key 快路径，但先补 same-key 命中率、envelope unchanged 和 propagation 次数统计；命中时避免 candidate list、envelope copy 和无变化传播。第三，实现 group envelope 只作 join 过滤，group bound 为负时仍回退原 label-pair join；该项对数千万 pair 的理论收益最大，但需要严格验证返回列批次和 DSSR 轮数不变。第四，在 normal、无需真成本回刷的路径上增加 top-K worst threshold，并与 group lower-bound 顺序结合。第五，做 cost-aware midpoint，让评分考虑 forward/backward 单位 survivor 成本和预估 join 乘积。第六才考虑 primitive-array join frontier、空 SRI 状态共享、空 extensionSet 不入队等常数级优化。

实施前必须先用当前默认 multi-delta completion bound 重跑 W300，避免拿旧 `init=151.7s` 基线误判新版瓶颈。每项修改单独 A/B，不叠加后再归因；验收不仅比较总时间，还要比较 exact 调用数、DSSR 轮数、每轮返回列 signature/cost、best reduced cost、最终 root bound 和 no-negative certificate。若某项局部时间下降但增加 DSSR 轮数或减少批量负列，应按端到端退化撤回，不能只凭局部计时保留。

164. 2026-07-10 completion-bound 前移实现与后续三项语义复核

本次先实现第 163 节的第一项，不同时改 dominance、join 或 midpoint。`GCNGBBStyleBidirectionalNgDssr` 的正反向扩展被拆成两步：先只建立已经完成 shift/add/normalize 的 `ExtensionFrontier`，直接用其 no-SRI frontier 和 endpoint min 做原有 completion-bound 检查；只有 survivor 才复制 visited/ng-memory、建立 dominanceSet/extensionSet 并创建完整 label。正反向 survivor 的 dominanceSet 与 extensionSet 也改为一次 job 扫描同时构造，仍分别保持 full-domain dominance key 和 half-domain extension 条件。SRI 口径未改变：真实 frontier 继续包含 SRI shift，completion bound 继续读取不含 SRI penalty 的 no-SRI frontier。

两组 A/B 均使用修改前单独编译的 ng-DSSR 主类和修改后主类，其余当前 classpath、runner 配置完全相同。40-2 root-only 中，两侧均为 `obj=22582, bound=22490, exact calls=16`；16 次 exact 的每轮 addedColumns、DSSR rounds、accepted best reduced cost、forward/backward candidate/constructed/survivor 逐项一致。exact pricing 从 `9.737s` 降到 `6.665s`，下降 `31.5%`；总 solve 从 `72.139s` 降到 `59.131s`，下降 `18.0%`。W300/50-3 setupR50 root-only 中，两侧均为 `obj=1939, bound=1726.014329, exact calls=20`，20 次 exact 的上述轨迹字段也完全一致。exact 从 `251.685s` 降到 `207.330s`，下降 `17.6%`；总 solve 从 `330.902s` 降到 `281.146s`，下降 `15.0%`。W300 累计 forward state 从 `2.357s` 降到 `0.363s`，backward state 从 `8.477s` 降到 `0.725s`；forward 总扩展从 `25.618s` 降到 `18.930s`，backward 从 `137.505s` 降到 `111.611s`。join 基本不变，符合本次只减少 bound-pruned child 状态构造的预期。focused `javac` 和 `PaperDominanceGraphConsistencyTest(cases=200, insertions=16000)` 通过。

第 2 项 dominance same-key 快路径针对的不是正确性错误，而是重复工作。当前 `insertOrDominate()` 即使命中完全相同 reachable key，仍创建 candidate list，并通过 `mergeGEnvelopes()` 复制当前 node 的 dominance envelope；label 被保留后，`addLabel()` 又更新 labelEnvelope、完整重建 dominanceEnvelope，外层随后无条件调用 `propagateAndTrim()`。如果新 label 没有改变 dominance envelope，这些复制、重建和 successor propagation 都没有效果。后续应先统计 exact-same-key hit、envelope unchanged 和真实 propagation，再只对同 key 命中增加快路径；不能把同 key 下的多个 label 合并删除，因为 join 和路径恢复仍需要真实 labels。

第 3 项 group envelope 在 half-domain 下仍只能作为乐观 lower bound 过滤器，不能直接生成或替代真实 label pair。用户提出的四个 group/split 例子是成立的：同一 sequence 可能在 AB split 下取得真实最好成本，而 CD 的 group-envelope 最小值来自另一组 contributor，CD 恢复出的该 sequence 不是最好 timing。但用于过滤时只判断 `groupEnvelopeLB >= threshold`。group envelope 是组内所有 label frontier 的逐点最小值，因此其两侧拼接结果不会高于任何真实 label-pair；只要这个更乐观的下界都达不到阈值，AB、CD 等所有真实 pair 更不可能达到阈值，整组剪枝安全。反过来，group LB 低于阈值时不能只取 contributor 生成一列，必须回退原 label-level join，才能保留最好 split、批量负列和 DSSR 证据。compact window 不改变这个结论；half-domain 只会让不同 split 的 inferred timing/cost 不同，因此更要求 envelope 仅过滤、不替代。

第 4 项 top-K worst threshold 不是“找到 K 条就停止”。当前候选堆最多保留 K 条列：堆未满时仍以 0 为过滤阈值，所有可能为负的 pair 都有机会进入；堆满后，以堆中当前最差的第 K 条 reduced cost 为动态阈值。若某个 pair 的合法 lower bound 已经不优于该阈值，它即使完整拼接也不可能进入最终 top-K，可以跳过；更好的新候选进入后，阈值会继续收紧。完整扫描仍要结束，才能保留 no-negative/best reduced-cost 证书。该方案第一版只能用于 inferred reduced cost 就是最终排序成本的 normal 路径；需要 evaluator 回刷的 dual-window、SRI 或 traced-envelope 路径不能直接用回刷前阈值剪枝。

165. 2026-07-10 dominance same-key 修正判断与 BEST_UB 对照

继续检查 `PaperDominanceGraph.insertOrDominate()` 后，需要修正第 162 至 164 节对 same-key 快路径收益的乐观估计。normal dominance 下，如果同 key 的现有 dominanceEnvelope 能完整覆盖且不高于新 label，新 label 会在插入前直接被拒绝；一个真正保留下来的同-key label通常必然扩展 envelope 定义域或在某段降低 envelope，因此后继传播并非普遍无效。partial dominance 下，新 label 被旧 envelope 裁剪后仍能保留，也意味着剩余区间没有被旧 envelope 覆盖，通常同样会改变 envelope。当前确定存在的冗余主要是 same-key 命中后仍建立单元素 candidate list、调用 `mergeGEnvelopes()` 复制 dominanceEnvelope，以及 `addLabel()` 从 labelEnvelope copy 后再合并 predecessorEnvelope；这部分可以做快路径，但预期收益应下调，必须先统计 same-key 次数和对应 copy/merge 时间，不能假设大量 propagation 可以跳过。

group-envelope filter 建议分两步。第一步只做诊断：现有 traced envelope group 增加原始 label 列表，按 `(terminalJob, exact ngMemorySet)` 构造 group，统计每个可剪 group-pair 实际覆盖的 `forwardLabels * backwardLabels` 数量，但仍执行原标准 join。第二步才启用安全过滤：group envelope lower bound 达不到阈值时跳过整个 cross-product；能达到时完整回退到原 label-pair join。若第一层通过率仍高，可以增加 group-label 层：优先选 label 数较少的一侧，用一侧 group envelope 与另一侧每个真实 label 做 lower bound，再决定哪些子集进入 pair join。所有层都只做 lower bound，不从 envelope contributor 直接生成列。该层级策略保持 half-domain 下所有真实 split，并允许与后续 top-K threshold 叠加。

BEST_UB 的名称不是 BPC incumbent upper bound，而是当前已找到最小 reduced-cost 列对 pricing 最优 reduced cost 的上界。当前代码中 `BEST_UB` 只把这个 best RC 用在 join 前的 group/pair lower-bound 剪枝，函数拼接完成后的 `shouldKeepJoinedReducedCost()` 仍使用 0；但被前置 lower bound 跳过的 pair 可能仍是负列，只是不会优于当前 best，因此 BEST_UB 仍会减少本轮返回列批次。`BEST_RECORD` 更激进，连已经完成函数拼接、为负但不刷新 best record 的候选也拒绝。

为排除 30 秒 ALNS 时间截断导致初始列数量波动，本次关闭 ALNS 后对 W300/50-3 setupR50 做 ZERO/BEST_UB root-only A/B。两侧初始列均为 3，time-indexed root preprocessing 均为 `tempPool=27435, seedElementaryCols=200, bound=1726.014329`。ZERO 为 `solve=261.251s, exact=211.800s/8 calls`；BEST_UB 为 `solve=240.280s, exact=190.455s/9 calls`，总时间下降 `8.0%`，exact 下降 `10.1%`。ZERO 共返回 `3573` 列、扫描 `69.157m` pair、执行 `68.207m` funcEval、join `28.289s`；BEST_UB 返回 `2696` 列、扫描 `52.703m` pair、执行 `51.988m` funcEval、join `24.192s`，pair/funcEval 减少约 `23.8%`，join 时间减少 `14.5%`，但返回列减少 `24.5%` 并多触发 1 次 exact pricing。日志中的 `bestLbPruned=2.541m` 只统计显式 best-threshold 判断，group 级提前退出和有序列表 break 后未访问的 label-pair 不逐一计数，因此不能用该字段单独代表总剪枝量。

本例说明 BEST_UB 有实际作用，但不适合仅凭一次结果改成默认：它通过牺牲批量负列换取 join 减负，当前 W300 净收益约 8%，其它实例可能因为新增 master/pricing 轮而退化。top-K worst threshold 应设计成独立的 `TOP_K` 口径，而不是修改 BEST_UB：候选不足 K 时等同 ZERO；满 K 后使用第 K 好 reduced cost。三者强度关系为 ZERO 阈值 0、TOP_K 阈值为当前最差保留列、BEST_UB 阈值为当前最好列。TOP_K 预期剪枝弱于 BEST_UB，但应保留与 ZERO 相同的最终 top-K 列集合，减少新增 pricing 轮的风险。

进一步核对 dominance 包络语义后确认，每个 dominance node 同时维护三层函数：`labelEnvelope=f_u` 是该 exact reachable key 下全部现存 label frontier 的逐点最小值；`predecessorEnvelope=h_u` 是直接前驱 node 的 `dominanceEnvelope` 逐点最小值；`dominanceEnvelope=g_u=min(f_u,h_u)` 同时包含当前 node label 和所有前驱链传下来的支配信息。新 label 命中同 key 时，插入前直接使用该 node 的旧 `dominanceEnvelope` 检查，因此现有同-key labels 确实参与占优，而不是只看前驱。若没有同-key node，则合并 terminal superset candidate 的 `dominanceEnvelope`，其内部已经递归包含各自前驱。需要单独区分的是反向清理：normal dominance 只会拒绝被旧包络支配的新 label，不会用保留下来的新 label 反扫删除同 node 的旧 labels；新 label 只更新 `labelEnvelope/dominanceEnvelope` 并向 successors 传播。partial dominance 才会在 `addLabel()` 前调用 `trimLabelsBy(newFrontier)`，对同 node 旧 labels 做部分裁剪或删除。因此当前多 label 的效率问题来自 normal 模式缺少同-node 反向清理，而不是 dominanceEnvelope 漏掉当前 node labels。

166. 2026-07-10 dominance same-key 增量更新与来源包络方案

same-key 插入可以先做一组完全不改变 label 保留语义的快路径。命中同 key 后不再建立单元素 candidate list，也不再通过 `mergeGEnvelopes()` 复制当前 `dominanceEnvelope`，而是直接用 `sameNode.dominanceEnvelope` 做只读占优检查。新 label 保留时，当前前驱包络没有变化，因此有 `f'_u=min(f_u,F_new)`、`g'_u=min(g_u,F_new)`；可以分别对 `labelEnvelope` 和 `dominanceEnvelope` 做一次增量 `mergeMinimum(F_new)`，不再执行“copy 新 labelEnvelope，再与 predecessorEnvelope 全量 merge”的重建。该路径应先统计 same-key hit/reject/accept、两次 merge 时间和后继传播时间，确认它在 W300 热点中的占比。

不希望逐个旧 label 再做反向函数扫描时，可以把 `labelEnvelope` 改成带来源的下包络：每个输出区间除几何函数外记录当前来源 label，并维护来源 label 的区间引用数或 contributor 集合。新 frontier 与现有包络仍只做一次 min-merge；merge 过程中记录被覆盖的旧来源。某个旧 label 的来源计数降为 0，说明其它同-key labels 的集体下包络已经在其整个定义域上不高于它，可以直接把它标成 dominated 并从 node 的 active labels 中移除，不需要再把新 frontier 与所有旧 frontier 逐对比较。这不是新增更强的 dominance 条件：如果该旧 label 此刻才到达，现有 `insertOrDominate()` 本来就会用同一集体包络拒绝它，因此该处理只是把现有规则改成与到达顺序无关。

来源包络仍有两个效率口径需要实验确认。第一，被删除 label 可能对应不同真实 sequence；虽然它不再影响 pricing 最小值和证书，但可能原本能额外生成较差但仍为负的 elementary 列，或提供另一条 non-elementary DSSR 更新见证，因此可能减少 join/扩展，也可能增加 DSSR 或 master 轮次。第二，现有 `TracedJoinEnvelope` 面向一次性 join index，每次 merge 会重建 segment list，不能未经测量直接搬到高频 dominance 插入；更合适的是在 `PaperDominanceNode` 内做专用 source-aware min-merge，并只追踪本次被覆盖的 source。第一版只应作用于 normal/no-SRI paper graph，partial dominance 已有 `trimLabelsBy()`，不应同时叠加两套旧-label 裁剪。

当前后继传播流程为：同-node 新 label 更新 `f_u/g_u` 后，`propagateAndTrim(u)` 从直接 successor 开始。每个 successor `v` 先由全部直接 predecessor 的 `g` 重建 `h_v`；若 `h_v` 完整支配 `f_v`，删除整个 node 并重连图；否则 normal 模式逐 label 删除被 `h_v` 完整支配的 label，partial 模式裁剪其被支配区间，必要时重建 `f_v`，最后计算 `g_v=min(f_v,h_v)` 并继续入队 successors。来源计数为 0 的同-node label 删除不会进一步改变 `f_u/g_u`，因为它本来已不贡献下包络，所以不需要额外触发第二次传播；传播仍只由新 frontier 对 `f_u/g_u` 的实际降低驱动。

本次先只实现严格保持旧执行顺序的 same-key 快路径。命中同 key 时直接只读使用 `sameNode.dominanceEnvelope` 做占优判断，不再构造单元素 candidate list，也不再经 `mergeGEnvelopes()` 复制整条包络；`dominates()` 不修改包络，partial 路径也只裁剪新 label 自身，因此与旧“复制后只读”严格等价。新 label 保留后的 `addLabel()`、`labelEnvelope` merge、`dominanceEnvelope` 重建和 successor propagation 完全未改。没有采用 `g'_u=min(g_u,F_new)` 的直接增量更新：它数学上等价，但会改变当前带容差和 normalize 的 `mergeMinimum` 结合顺序，不符合本轮优先保证旧数值路径不变的要求。新增 same-key hit/reject/accept，以及诊断模式下 check/update/propagation 时间统计。focused `javac` 通过；`PaperDominanceGraphConsistencyTest` 对朴素全扫描、当前 paper graph 和旧 indexed graph 做 200 组、16,000 次随机插入，对拍结果一致。

167. 2026-07-10 same-key 诊断与空传播优化

使用 `wet040_001_2m`、no-ALNS、root-only、normal ng-DSSR、dualPairCoef0.08/top3 做诊断。12 次 exact pricing 的单轮 same-key 命中约 `978-2091` 次，其中接受约 `668-1195` 次；但 `sameKey check/update/prop` 通常合计只有约 `1-6ms`，最后 no-negative 轮为 `0.190/0.677/0.672ms`。相对该轮 exact `703.554ms` 和 completion-bound 约 `476.931ms`，same-key 包络操作已经不是主要瓶颈。每轮 `propagateAndTrim()` 调用约 `779-1274` 次，但 successor 实际访问经常只有 `0-254` 次，说明大量保留 label 所在 dominance node 没有 successors。

据此只补一项严格等价的小优化：`changedNode.successors` 为空时直接返回，不再为每次空传播创建 `ArrayDeque + HashSet`；非空传播使用 node 上的本轮 queue mark 去重，替代临时 `HashSet`，保持原 FIFO 入队顺序和每 node 最多处理一次的语义。相同配置重跑后，单轮 `noSuccessor` 为 propagation calls 的约 `79%-100%`，12 次 exact 的 addedColumns 与 acceptedBestRc 序列逐项一致，最终 bound 均为 `22490`。新 run exact `5.846s`、旧 run `8.209s`，但两次串行运行受 JVM/JIT 与文件缓存影响，且局部 propagation 计时本身只有毫秒级，因此不把该总时间差归因于本修改。

当前 dominance graph 内其它严格等价小优化收益已经很有限：每轮 no-same-key `envelopeMerges` 仅约 `84-131` 次，superset/subset 搜索通常低于 `1ms`，剩余 propagation 也只有毫秒级。直接增量更新 `dominanceEnvelope` 会改变 PWLF merge 结合顺序，不值得为这一级耗时承担数值路径风险；new-key 单候选延迟复制最多再省十几次 copy，同样不是主矛盾。若后续还要显著减少 dominance 成本，需要回到 source-aware labelEnvelope 删除不再贡献下包络的旧 label，但该方案会改变批量列和 DSSR 更新路径，按前述决定暂不实现。

168. 2026-07-10 same-key 与传播 queue mark 的逻辑复核

本次不只比较运行时间，而是重新核对两项修改的语义。same-key 路径原来对单个 `sameNode.dominanceEnvelope` 做 copy 后只读检查；normal 路径的 `dominates()` 不修改任一输入，partial 路径也只通过 `label.frontier.updateDominatedIntervalsDetailed(envelope)` 修改新 label 自身，不修改作为右参数的 node envelope。因此直接只读复用原包络不存在别名写入，后续 `addLabel()`、包络重建与传播顺序仍与旧实现一致。

传播去重方面，旧 `HashSet<PaperDominanceNode>` 使用对象 identity，语义是同一次 `propagateAndTrim()` 中“只要曾经入队，就不能再次入队”；node 上的 `propagationQueueMark` 完全复现该语义，并保持 `LinkedHashSet successors` 的 FIFO 加入顺序。空 successor 早退也等价于旧实现建立空队列后立即退出。删除的 node 保留旧 mark 不影响后续，因为同 key 重新建立的是新 node 对象；当前 Java pricing 主线为串行调用，静态 `nextMark()` 不引入新的并发口径。

额外检查了包含图并非等层 DAG 的情形，例如 `S-A-C` 与 `S-B-D-C`。这里一次入队仍然成立，原因是传播量只有 lower-envelope 的 `min`，插入造成的变化单调变小；长路径晚到达时新增的上游包络已经经短路径传到共同后继，中间 node 自身的 labelEnvelope 原本就在旧 predecessor envelope 中。节点被删除时还会把其有效前驱直接重连到后继，仍不会丢失更小包络。为验证实现，`PaperDominanceGraphConsistencyTest` 现在同时覆盖 forward/backward、逐次比较所有历史 label 的 `isDominated` 和 active count，并加入菱形共享后继及 2,000 组非平衡路径随机对拍。总计 32,000 次普通随机插入、2 个菱形 case 和 2,000 个非平衡 case 均与朴素 fixed-point、indexed 实现一致。

169. 2026-07-10 同 dominance node 旧 label 反向清理的可行范围

重新沿 ng-DSSR 主线检查后，需要收紧第 166 节“来源计数归零即可删除整个同-node label”的结论。当前 Paper dominance node 的 key 是 `Label.reachableSet`，在 ng-DSSR 中实际传入的是 `dominanceSet`。它把三类原因统一表现为“不可继续访问”：ng-memory 已禁止、当前 frontier 下 full-domain 时间不可达，以及全局 zero-dual/required-outsourcing 排除。因此两个 label 位于同一个 dominance node，只能说明它们的 `dominanceSet` 相同，不保证真实 `ngMemorySet`、`visitedSet`、`extensionSet` 或 SRI count 状态相同。final join 又明确用 forward/backward 的真实 `ngMemorySet` 交集判断兼容性。因此只按整个 node 的 `labelEnvelope` 删除零贡献 label，可能删除一条函数值较高、但具有不同 join 兼容性的路径。

从当前 dominance 规则自身看，whole-node collective envelope 已经用于拒绝后到的新 label；把旧 label 反向清理只是让该规则与到达顺序无关，并不是引入新的数学支配条件。但它会更频繁地使用“dominanceSet 足以代表后续状态”这一现有假设，而且会改变 join 候选、elementary 列批次和 non-elementary DSSR witness。考虑到当前主要目标是降低 join，而不是重写 dominance 语义，第一版不应直接在 graph 内物理删除或标记这些 label。

更稳的实现顺序是先做 join-only contributor filter。正反向扩展全部完成后，在每个 terminal job 内按 `(reachableSet, ngMemorySet)` 精确分组；第一版只允许 normal、no-SRI 路径。每组对实际 join extension function 建立带来源的 pointwise minimum envelope，保留至少贡献一个区间的 label，其余 label 只从本轮 join 输入中排除，原 dominance graph、扩展队列、father trace 和 DSSR 记录均不修改。相同 terminal、reachableSet、ngMemorySet 下，转移成本和 join 兼容性一致；若旧 label 在所有时间都不低于组内其它 label，则与任意兼容的另一侧函数相加后仍不可能给出更小 join 值。因此该过滤保持本轮 relaxed minimum 和 no-negative certificate，但可能减少较差但仍为负的 elementary 列以及不同 non-elementary witness，端到端轮数仍需 A/B。

现有 `TracedJoinEnvelope` 已经具备“分段函数 + 来源 label”的合并能力，但它当前服务于整组 envelope join，且每次 merge 会重建 segment list。第一步应只增加诊断：每个 dominance node 的 label 数、不同 ngMemory group 数、每组 contributor 数、过滤前后潜在 pair 数和 envelope build 时间。只有确认 `(reachableSet,ngMemorySet)` 组内仍存在大量非贡献 label，才保留 join-only filter；若多数 label 的 ngMemory 都不同，则该方向不能解决当前 join 数量问题。验证通过后，才考虑把同样的 exact-state subgroup envelope 前移到插入阶段，以减少出队扩展；SRI 模式必须把完整 SRI state 加入 subgroup key，不能直接复用 no-SRI 规则。

170. 2026-07-10 修正：whole-node 反向清理与现有 dominance 语义

继续讨论后，第 169 节把真实 `ngMemorySet` 不同直接解释成“whole-node 反向清理可能影响最优性”仍然过于保守。当前 normal Paper graph 在新 label 到达时，本来就用同 node 的集体 `dominanceEnvelope` 检查并拒绝它；该检查不额外区分真实 ngMemory。若某个早到旧 label 后来完全退出 node 的 `labelEnvelope` 下包络，那么把它放到当前时刻重新插入，现有规则必然会拒绝它。反向清理只是让既有 dominance 规则与到达顺序无关，不是新增一条更强的支配条件。若这种清理会丢最优解，那么当前“同样的 label 只是晚到就被拒绝”的逻辑已经具有同一问题。因此在接受当前 dominanceSet 语义正确的前提下，normal/no-SRI 下 whole-node 零贡献 label 清理不应影响全局最优性。

单向扩展也不存在“被删 label 能扩展、包络来源 label 不能扩展”的问题。same node 表示当前算法认定两者具有相同后续 dominance state；对旧 label frontier `F`，若其它同-node labels 的集体包络 `G` 在 `F` 整个定义域上满足 `G(t)<=F(t)`，那么对任一下一任务的相同 shift/add 转移算子 `E_j`，都有 `E_j(G)<=E_j(F)`。集体包络可在不同时间由不同 label 提供，但旧 label 某个扩展的最优出发时间 `t` 上必然存在一个具体 contributor，其成本不高于旧 label，且相同 terminal 下使用同一 setup/process/penalty 转移。half-domain `extensionSet` 只是该函数可扩展性的缓存，不会推翻这个逐点替代关系。

真实 ngMemory 不同主要说明不能保证“同一个 backward label、同一个 split 下由某一个固定 dominator 一对一替换”；但 label dominance 保证的是完整 continuation 的最优值，而不是固定 split 的候选集合完全相同。替代路径可能通过另一 contributor 或另一 split 出现。因此清理会改变 join 返回的负列批次、elementary 列多样性和 non-elementary DSSR witness，却不应改变 relaxed 最小 reduced cost 和最终 no-negative certificate。这里的风险是计算效率和 DSSR/master 轮数，而不是 no-SRI 下新增最优性风险。

据此可以直接做 whole-node source-aware `labelEnvelope` 实验，但应限定为 normal/no-SRI，并用开关隔离。每个 envelope segment 记录来源 label；新 frontier merge 后重新统计 contributor，来源完全消失的旧 label 标记 `isDominated` 并从 node active labels 移除，但对象本身不能释放，因为已有 child 的 father chain 仍可能引用它。为避免端点或 tie 的来源选择造成误删，第一版应保守保留仅在边界有贡献的 source，或在删除前确认“由其它 source 构成的 envelope”完整覆盖并不高于旧 frontier。随后按现有路径重建 `dominanceEnvelope` 并传播。验证不能要求每轮返回列完全一致，而应检查每轮 relaxed best RC、无负列证书、最终 root/节点 bound、完整最优目标和 DSSR 轮数变化。

第 169 节提出的 join-only exact-ng-memory filter 仍可作为风险更低的对照版本，但它不再是 correctness 必需条件，而是用于区分“只减少 join”和“同时减少扩展 + join”的实验基线。实现前最有价值的统计也应改成 whole-node label 数、最终 contributor 数、被清理 label 是否已出队、扩展候选减少量、join pair 减少量，以及 DSSR witness/rounds 变化。

171. 2026-07-10 dominance envelope 传播的全量刷新与增量化

当前 Paper graph 的 successor 传播仍是全量刷新。node `u` 的 `f_u=labelEnvelope`、`h_u=predecessorEnvelope`、`g_u=min(f_u,h_u)` 更新后，`propagateAndTrim(u)` 对每个直接 successor `v` 调用 `recomputePredecessorEnvelope()`，从 `v.predecessors` 的全部 `g_p` 重新 copy/merge 得到 `h_v`。随后如果 predecessor envelope 删除或裁剪了 `v` 的 label，`removeLabelsDominatedByPredecessors()` 会重建 `f_v` 并在方法内部重算一次 `g_v`；返回外层后又无条件调用一次 `recomputeDominanceEnvelope()`。因此当前至少存在一次明确的重复 `g_v` 重算。

数学上该传播和 completion-bound multi-delta 同属单调 lower-envelope fixed point，但这里更简单：一次 graph 构建期间只插入 label，`f/h/g` 只会逐点变小。简单插入中只有当前 predecessor `u` 的 `g_u` 变化，所以 successor 可直接做 `h_v <- min(h_v,g_u_new)`，不必重新扫描所有 predecessors；更进一步可以用现有 `mergeMinimum(..., reportChanged)` 或 change-hull 只在确实下降时入队。注意传播对象应是更新后的 `g_u` 或其下降 delta，不应只传原始新 label frontier，因为 `g_u` 同时包含当前 node 其它 labels 和上游 predecessor envelope。

删点重连不要求 envelope 回升。一个 node 只有在 `h_u<=f_u` 时才整 node 删除，此时 `g_u=h_u`；删除后把其 predecessors 直接连到 successors，重连前驱的集体 envelope 正好就是原 `h_u/g_u`，因此不会丢失原贡献，也不需要因为移除旧 predecessor 而把 successor 的 `h` 向上重算。被 `h_v` 删除的完整 label 或 partial 区间同样已经由 `h_v` 以不更高值覆盖：虽然精确的本地 `f_v` 需要从剩余 active labels 重建，但用于继续传播的 `g_v=min(f_v,h_v)` 不会因为这些删除而变大。source-aware whole-node 清理中，删除的是本来就不贡献 `f_v` 的 label，则连 `f_v` 都不变化。

可按风险从低到高分三步优化。第一步只去掉 `removeLabelsDominatedByPredecessors()` 内外重复的 `g_v` 重算，仍完整重建 `f_v`，数值路径最容易对拍。第二步让 `recomputeDominanceEnvelope()` 或增量 merge 返回 `g` 是否实际下降，只有下降才传播 successors；这会直接跳过“删除 label 但 `g` 已由 `h` 覆盖”的无效传播。第三步再把 `h_v` 改为由变化 predecessor 增量 merge，并使用可重复入队的 monotone worklist 或 multi-delta；不能继续沿用“本轮曾入队即永久禁止再入队”的 mark 语义，否则真正独立的多个 predecessor delta 在非等层 DAG 中可能需要第二次处理。当前 40-2 诊断里 dominance propagation 只有毫秒级，第一、二步收益可能较小；若 source-aware 清理显著增加 node 内删除，或 W300 上重新统计显示 predecessor merge/rebuild 成为热点，再实施第三步更合理。

172. 2026-07-10 修正：新 label frontier 就是唯一传播增量

进一步化简后，第 171 节“传播更新后的 `g_u`”仍保守了一层。same-key 插入时 `h_u` 不变，故 `f'_u=min(f_u,F_new)`、`g'_u=min(f'_u,h_u)=min(g_u,F_new)`；旧 `g_u` 已经在此前传播完成，本轮唯一新增信息就是 `F_new` 中低于旧 `g_u` 的区间。新建 dominance node 时，插入前其 terminal superset predecessors 已直接连接到后继，它们的集体 envelope 已存在于后继 `h` 中；新 node 插入并替换拓扑边后，`g_new=min(h_new,F_new)` 相对旧 predecessor contribution 新增的也只有 `F_new`。因此传播可以从新 label frontier 本身开始，每经过一个 successor，只保留真正改小该 successor `g` 的局部 delta。

当前 `PaperDominanceGraph` 的主要方法成本可分为四段。插入检查中，same-key 已取消单候选列表和 envelope copy；new-key 仍需 `findTerminalSupersetNodes()` 搜索 terminal predecessor，并由 `mergeGEnvelopes()` 建一次候选集体包络用于占优检查和新 node 初始 `h`，这部分语义必要且日志占比很低。拓扑插入中，`findImmediateSubsetNodes()` 搜索直接 successors，`removeRedundantSubsetCandidates()` 排序并做包含去重，然后断开 predecessor-successor 旧边并插入新 node；这是维护 Hasse 图所需，只有候选很多时二次去重才可能成为问题。

真正冗余集中在 `propagateAndTrim()` 和 `PaperDominanceNode` 包络维护。当前每访问 successor 都执行 `recomputePredecessorEnvelope()`，从全部 predecessors copy/merge 重建 `h`；随后整 node dominance 检查、逐 label dominance/partial trim；发生删除时 `removeLabelsDominatedByPredecessors()` 重建 `f` 并在内部重算 `g`，外层又重算一次 `g`；最后无论 `g` 是否变化都把全部 successors 入队。`addLabel()` 也先 merge `f`，再 copy 完整 `f` 与 `h` 重建 `g`，没有使用 `g'=min(g,F_new)`。因此当前 graph 虽然查询拓扑较轻，但 envelope 传播仍是全量刷新实现。

更直接的增量流程应为：新 label 先按旧 `g_u` 做 dominance；保留后将 `F_new` merge 进本地 source-aware `f_u`，同时做 `delta_u = F_new` 真正改善旧 `g_u` 的区间并更新 `g_u`。对 successor `v`，分别执行 `h_v<-min(h_v,delta_u)` 和 `g_v<-min(g_v,delta_u)`；若 `h_v` 没变化则该分支结束，若 `g_v` 没变化则可以做本地 label 清理但不再向下传播，只有 `g_v` 的真实下降区间继续入队。由于 predecessor 导致的 label 删除或 partial trim 都发生在已被 `h_v` 覆盖的区间，删除不会让 `g_v` 回升；whole-node source-aware 删除零贡献 label 时连 `f_v` 都不变化。删 graph node 时 `g=h`，重连只恢复原有 predecessor contribution，也不产生新的数值 delta。

第一版不必立即复制 completion-bound 的完整 sparse multi-delta。可以先传播整个 `F_new` 并用 `mergeMinimum(..., reportChanged=true)` 决定是否继续，已经能去掉全部 predecessor 重扫、重复 `g` 重建和无变化 successor 传播；确认收益后再把 change hull 升级为多离散区间。与此同时，source-aware envelope 可以进一步把“逐 label 扫描是否被集体 `h` 占优”改成来源消失判断，但这一层需要保留 endpoint/tie witness，并与 no-SRI whole-node 清理一起实现。验收应对拍每次插入返回、所有 active label 状态、每轮 relaxed best RC、DSSR rounds、返回列、最终 bound 和最优目标。

171. 2026-07-10 新 label 对 successor dominance nodes 的传播流程

Paper dominance graph 中，新 label 不会直接逐条扫描所有后代 label。`insertOrDominate()` 先检查当前已有 eligible envelope；保留下来后，若命中同一个 reachable key，就通过 `addLabel()` 把新 frontier 合入当前 node 的 `labelEnvelope`，再重建 `dominanceEnvelope=min(labelEnvelope,predecessorEnvelope)`。若是新 reachable key，则先在包含关系图中建立新 node，只连接 immediate predecessor/successor，并移除被新 node 替代的跨层边；新 node 的 predecessor envelope 直接复用插入前 dominance 检查所合并的 candidate envelope。

随后 `propagateAndTrim(changedNode)` 从当前 node 的直接 successors 开始 FIFO 向下传播。每个 successor 先重新计算 `predecessorEnvelope=min(所有直接前驱的 dominanceEnvelope)`，因此真正用于占优的不是“这一条新 label 的 frontier”，而是包含新 label 影响在内的全部前驱综合下包络。若该 predecessor envelope 覆盖 successor 的 `labelEnvelope` 整个定义域并不高于它，则 successor 整个 dominance node 被删除，其中全部 labels 标记 dominated；删除后原前驱会与仍兼容的原 successors 直接重连，并继续传播。

若综合前驱包络不能一次覆盖并占优整个 successor node，则进入逐 label 处理。normal 模式对 successor 中每条 label 检查完整函数占优，满足时标记并移除；graph-partial 模式则把被占优区间从 label frontier 裁掉，只有裁空才删除 label。若 node 的 labels 最终为空，删除 node 并重连图；否则在 labels 有变化时重建 `labelEnvelope`，再重建 `dominanceEnvelope`，然后将变化继续传给下一层 successors。same-node 旧 labels 是例外：normal 模式当前只把新 label 加入包络，不反向清理当前 node 的旧 labels；partial 模式会在 add 前调用 `trimLabelsBy(newFrontier)`。第 170 节讨论的 source-aware 清理，目标正是补 normal 模式这一处到达顺序不对称，而不改变现有 successor 传播规则。

173. 2026-07-10 完整 frontier 增量、局部 delta 与 labelEnvelope 的职责

这里需要区分两级优化。第一级是直接传播整个新 label frontier `F_new`：successor 不再扫描全部 predecessors 重建 `h`，而只执行 `h<-min(h,F_new)`；`g` 也只执行 `g<-min(g,F_new)`，并用 changed flag 截断没有数值变化的传播。这一步仍会扫描 `F_new` 与目标包络的完整 segment list，但已经去掉当前实现中的全 predecessor 重扫、`f/h` copy、重复 `g` 重建和无变化后继遍历，是预期收益最大的部分。第二级才是局部 delta：只保留 `F_new` 真正低于旧 `g` 的若干变化区间，后续 merge 只扫描这些区间。它与完整 frontier 增量得到相同结果，只在 frontier 很长而真实改善区间很窄时进一步降低 segment 扫描；若变化覆盖大部分定义域，构造 sparse delta 的成本可能抵消收益，因此不应和第一级混为一谈。

`labelEnvelope=f_u` 是 exact-key node 内现存 labels 的逐点最小值。它不是独立于 labels 的必要数学状态：只保留 labels 时，随时可以全量重建 `f_u`。但它是重要缓存和语义分层，一方面把当前 node 的本地贡献 `f_u` 与 predecessors 贡献 `h_u` 分开，使 `g_u=min(f_u,h_u)`、整 node 被 predecessor 占优检查和同-key collective dominance 可以直接完成；另一方面为后续 source-aware 清理提供“哪些 labels 仍贡献本地下包络”的依据。如果删除 `labelEnvelope`，每次需要本地包络或判断全部本地 labels 是否被 `h` 支配时都要重新扫描 node 内所有 labels。因此当前应保留 `labelEnvelope`，优化目标是避免无意义地反复重建它，而不是删除它。

174. 2026-07-10 predecessor 删除 label 后可保留滞后本地包络

进一步逐点推导表明，predecessor envelope 更新后删除或裁剪本 node labels 时，不必为了维持综合 dominance 值立即重建本地 `labelEnvelope`。设删除前 `f=min(f_R,f_D)`，其中 `f_R` 来自保留 labels，`f_D` 来自被删 labels；删除条件保证更新后的 `h'<=f_D` 且覆盖对应定义域。因此 `min(f,h')=min(f_R,f_D,h')=min(f_R,h')`，即继续保留被删 label 的旧本地贡献也不会改变 `g=min(f,h')`。partial trim 同样逐区间成立：被裁区间已由 `h'` 覆盖，未裁区间的 label 函数不变。当前 graph 的 envelope 更新只会单调变小；被整体删除的 graph node 满足 `g=h`，重连 predecessors 也不会使 successor 的 `h` 回升，因此该旧贡献以后仍被覆盖。

这种 lazy 做法保持 relaxed best value、后续 dominance 和传播正确性，但会改变 `labelEnvelope` 的表面语义：它不再严格等于 active labels 的包络，而是包含已被 predecessor 永久覆盖的历史本地贡献。进一步修正后，使用它检查整 node 删除也不是更保守。设 `f_stale=min(f_active,f_deleted)`，删除条件和后续单调性保证当前及以后始终有 `h<=f_deleted`，所以 `h<=f_stale` 当且仅当 `h<=f_active`；历史贡献不会阻止也不会错误触发 node 删除。真正不再可靠的只有 source-aware contributor 统计，因为 stale segments 的来源可能已经不是 active label。因此有两条实现路线：若只追求当前 graph 的数值传播效率，可以保留 stale `labelEnvelope`、直接维护精确 `g` 并跳过 label 删除后的 rebuild；若要实现来源包络和零贡献 label 清理，则仍需精确维护 active-label envelope，或者单独维护带来源的有效本地包络。该优化依赖“predecessor contribution 不会回升”的当前插入式生命周期，不能无条件推广到允许撤销约束或删除非支配 predecessor 的动态图。

175. 2026-07-10 completion-bound multi-delta 的直接执行差异

multi-delta 加速 fixed-point 的直接原因可以用一次局部更新说明。若某个 job 的 envelope 已经把完整 `[0,T]` 传播给全部后继，随后仅 `[a,b]` 上被新 candidate 改小，旧流程仍会把更新后的整条 `[0,T]` 函数再次经过每条后继弧执行 `shiftX/add/normalize/mergeMinimum`；其中 `[0,a)` 和 `(b,T]` 只是重复传播旧信息。multi-delta 保存上次已传播快照，识别只有 `[a,b]` 严格改小，构造区间外为 BigM 的 delta，然后只让该新增信息经过后继弧。BigM 只是让原 PWLF 运算把未变化区间视为“本轮无候选”，真正减少的是每次局部更新后沿所有邻接弧重复构造和合并整条旧 envelope。等待闭包可能让 `[a,b]` 的影响沿正向或反向继续扩散，因此 delta 构造仍要按方向 normalize；它不是简单地只复制一条水平线。

completion-bound 的 multi-delta 有效并不只是因为 BigM 段是简单水平线，而是因为 fixed-point 传播中同一个 job envelope 会多次更新，旧实现每次把已经传播过的完整 PWLF 再对所有邻接弧做 `shift/add/normalize/merge`。新实现逐点比较当前函数与上次已传播快照，只取严格改善的多个区间；区间外填 BigM 是为了继续复用现有 PWLF 的 shift/add/单侧等待闭包语义，而不是主要加速来源。真正收益来自避免旧区间反复生成候选和沿全部后继传播。dominance graph 也可复用同一原理，但当前每次插入只有一个 `F_new` 信号，第一步应先用完整 `F_new` 增量消除 predecessor 全量重建；只有实测真实改善区间明显窄于 frontier 定义域时，sparse multi-delta 才可能在此基础上继续获益。

176. 2026-07-10 partial trim 后 surviving label 不保证仍贡献本地包络

partial 模式下，“label 没有被 predecessor `h` 完全占优”不能推出它仍出现在 active-label 下包络中。两区间反例为：`L1=5`，`L2` 在左区间为 `4`、右区间较高；初始 `h` 较高时，`L1` 在右区间贡献本地包络，`L2` 在左区间贡献。后来 `h` 只在右区间降到 `4`，于是 `L1` 的右区间被 trim，但它在左区间不受 `h` 支配，仍然 active；然而左区间又始终由 `L2=4` 压住，因此 `L1` 已不贡献任何本地或综合包络。若 stale `labelEnvelope` 的右区间仍记录 `L1`，单看 `L1.isDominated=false` 会把它误认为 contributor。

这不推翻 lazy envelope 的数值正确性：右区间的 stale `L1` 已由 `h` 覆盖，综合 `g` 仍准确。它只说明 partial source-aware 不能只按 label 级 dominated 标记判断来源。可行做法是统计综合 `g=min(f_stale,h)` 的来源：stale segment 被 `h` 压住时直接记为 predecessor-covered；只有未被 `h` 覆盖且仍落在 label 当前未裁 frontier 上的 segment 才计入该 active label。另一种做法是 trim 后重建精确 active-label envelope。normal 模式整条 label 删除时，`isDominated` 已足够识别历史来源；partial 模式必须额外做区间级有效性判断。

177. 2026-07-10 仅保留 predecessor envelope 与带来源 dominance envelope 的重构方案

继续推导后，当前插入式 Paper dominance graph 在数值上可以不再保存独立 `labelEnvelope=f`。每个 node 只需保存本地 active labels、`h` 和 `g`，其中 `h` 是全部直接 predecessor 贡献的数值下包络，`g` 是 `min(h,所有本地 label frontier)`，并在 `g` 的每个 segment 上记录来源类型。对当前 node 的清理只需区分 `LOCAL(label)` 与 `EXTERNAL_PREDECESSOR`，不必把祖先的具体 label 一路带下来；祖先 node 删除和重连时其数值贡献仍由更上层 predecessor 保持，具体祖先对象不是当前 node 判断本地 label 是否有贡献所必需的状态。

same-key 新 label 可把“先 `dominates()`、再 merge”合成一次 source-aware min-merge。若新 label 没有在任何正长度区间严格改善旧 `g`，按现有 `<=` collective dominance 语义直接拒绝；若有改善，则 normal 模式保留新 label 的完整 frontier 用于后续扩展，partial 模式只保留未被旧 `g` 占优的有效区间。merge 同时更新 `g` 的 segment 来源和本地 contributor 集合；某个旧本地 label 在新 `g` 中不再贡献任何区间时，如果此刻重新插入也会被同一 collective envelope 拒绝，因此可以标记 dominated 并删除。这是现有规则的到达顺序无关化，不引入新的 dominance 条件。节点 `g` 全部来自 predecessor 时，等价于 `h` 已支配所有本地 labels，可以删除该 graph node 并按现有方式重连拓扑。

向 successor 传播时不再执行 `recomputePredecessorEnvelope()`。same-key 插入有 `g'=min(g,F_new)`；new-key 插入前旧 terminal predecessors 的贡献已存在于 successors，故两种情况唯一新增信号均来自 `F_new`。successor 直接做 `h<-min(h,delta)`；若 `h` 不变立即停止，若变化则做 `g<-min(g,delta)` 并把新来源标为 external。`g` 中失去全部贡献的本地 labels 可以删除，删除或 partial trim 后不需要重建任何包络，因为这些旧贡献已由当前 `g` 的其他来源覆盖。若 `g` 不变且始终维护“只保留 contributor labels”的不变量，该 node 也没有新的本地 label 可清理，不再访问 successors。

拓扑搜索、new-key 插点和 dominated-node 删除重连仍需保留。`findTerminalSupersetNodes()` 与 `findImmediateSubsetNodes()` 维护 reachable-set Hasse 图，现有统计也未显示它们是热点，不应和包络重构一起改。传播队列经进一步推导后不需要因为稀疏 delta 改成可重复入队：当前 `insertOrDominate()` 一次只传播同一个新 label frontier `F_new`。若 successor `v` 在时间 `t` 被 `F_new` 严格改进，则 `F_new(t)<g_v^{old}(t)<=g_p^{old}(t)` 对每个直接 predecessor `p` 都成立，所以该时间点必然包含在每个 predecessor 的变化区间中；`v` 的真实 delta 是所有上游 delta 的子集，而不是不同路径 delta 的并集。任意一条变化 predecessor 路径第一次把对应 signal 送到 `v` 时，已经足以计算 `v` 的全部可能变化，当前每轮最多处理一次仍成立。只有未来把多个彼此独立的新 label 更新批量并发到同一传播轮次时，才需要 completion-bound 式 pending-interval worklist。

这里的“稀疏 delta”具体指：对当前 node 的旧 `g` 与 `min(g,F_new)` 逐段比较，只保留 `F_new` 严格降低旧 `g` 的一个或多个不连续时间区间。dominance graph 的边上没有 completion-bound 的 `shift/add/normalize`，因此不需要构造区间外为 BigM 的完整 PWLF；直接传 `(start,end,对应 F_new 片段)` 的区间列表，并只在这些范围更新 successor `h/g` 更直接。若真实变化覆盖大部分 frontier，提取 sparse delta 可能没有收益，第一版也可先传播完整 `F_new` 并用 changed flag 截断。

实现上不建议给全局 `PiecewiseLinearFunction.Segment` 直接加 label 字段，也不需要给 `h/f/g` 三套函数全部维护具体 label 来源。更小的边界是新增 dominance 专用 sourced envelope：几何仍复用 PWLF 语义，来源只记录 `LOCAL(label)` 或 `EXTERNAL`，merge 返回 changed intervals 与失去贡献的 local sources。normal/no-SRI 与 graph-partial/no-SRI 可分别保持当前 frontier 语义；SRI 状态若未进入 dominance key，不能直接复用该清理。single-point store 继续走独立路径。验收必须逐次对拍插入返回、active labels、每个 node 的 `g`、relaxed best RC、DSSR witness/rounds、返回列、bound 与最优目标。

178. 2026-07-11 增量 sourced dominance graph 实现、错误清理口径撤回与最终结果

本次没有修改原 `PaperDominanceGraph`，而是新增独立的 `IncrementalSourcedDominanceGraph` 和创建/统计入口。新图保留原 reachable-set Hasse 拓扑，只把数值维护改为增量方式：每个 node 保存 predecessor 包络 `h` 和综合包络 `g`；新 label 先用当前 `g` 做只读完整占优判断，只有真正改善时才构造 merged envelope；merge 同时返回 `g` 真正下降的多个离散区间，successor 只在这些 sparse delta 上执行 `h<-min(h,delta)` 和 `g<-min(g,delta)`。若 `g` 没有下降则停止后续传播。稀疏区间直接保存真实直线片段，区间外不构造 BigM PWLF，因为 dominance 边没有 completion-bound 中的 shift/add/normalize。

第一版曾按来源删除“不再贡献综合 `g`”的同-key 旧 label。随机点值包络对拍全部通过，W300/50-3 的总时间也从约 `211s` 降到约 `113s`，但 root bound 从旧图的 `1726.014329` 变成 `1726.256114`。该结果确定说明第一版来源追踪和物理删除实现没有与旧图严格等价，但不能直接证明 whole-node 零贡献 label 的理论删除原则错误。为先恢复主线正确口径，最终版本撤回物理清理，不反向删除同-key 旧 labels；label 仍只在 predecessor `h` 完整占优其 frontier 时删除，与旧 Paper graph 的 active-label 语义严格一致。来源字段仅服务于本次 merge 的贡献判断和后续诊断，不再作为清理依据。

继续按当前 dominance key 的完整语义复核后，上面的 raw ng-memory 反例不成立。令 `U(L)` 为 label 的 `ngMemorySet`、当前 full-domain 直连时间不可达任务和全局排除任务的并集；代码保存的 `dominanceSet` 正是 `U(L)` 的可用补集。一个 predecessor node 能支配 `L3`，要求其可用集合包含 `L3` 的可用集合，等价于 `U(keep) ⊆ U(L3)`。因此即使 `keep` 的 raw ng-memory 多出任务 `9`，任务 `9` 也必须已经对 `L3` 永久不可达；在当前 dominance 可达性判断本身成立的前提下，任何包含 `9` 的后续扩展或 join 对 `L3` 同样不可行，不能构造出“`L3` 可 join、keep 不可 join”的有效反例。若 `L3` 的 ng-memory 和永久不可达集合都为空，支配方的对应并集也只能为空。不同 father path、elementary sequence 或 non-elementary DSSR witness 也只会改变加列批次和 DSSR 轮数，在 exact DSSR 真正闭合时不应独立改变最终 bound。

因此，W300 第一版来源清理造成 bound 变化，并不能证明 whole-node 零贡献 label 的理论删除原则错误；它只证明当时的 source-aware 实现没有与旧图严格等价。当前最可疑的是“每段只记一个来源，再按来源计数归零物理删除”的实现口径：tie、断点、窄区间或 `LOCAL/EXTERNAL` 来源切换都可能让一个仍需保留的来源从记录中消失。现有日志没有保存具体被误删 label 及其覆盖来源，不能进一步断言是哪一种来源追踪错误。最终版本继续保留旧图的 active-label 语义是正确的稳妥处理；若重启物理清理实验，必须对每个删除 label 记录完整 frontier、`U(L)`、逐区间覆盖来源，并在 W300 上定位第一条因删除而消失的 join，而不能再使用 raw ng-memory 交集作为反例。

传播队列保持一次插入、每 node 最多入队一次。这里传播的是同一个 `F_new`，图边只有 min merge。若下游 node 在时刻 `t` 被改善，则 `F_new(t)<g_v^{old}(t)<=g_p^{old}(t)` 对每个直接 predecessor 都成立，所以任意首条变化父路径已包含该 node 的全部可能下降区间。实测把逐父边事件改回单次入队后，W300 累计 propagated events 从 `791010` 降到 `632479`，source-aware merge 从 `1908459` 降到 `1591397`。只有未来一次 worklist 同时传播多个独立 label 时才需要 pending-delta 合并。

正确性测试覆盖 forward/backward、同-key 保留、predecessor tie 删除、菱形和非平衡包含拓扑，并对 24000 次随机插入逐次比较插入返回、每个历史 label 的 `isDominated`、active-label 数量以及随机 reachable-set/time 点值包络；连续多轮均与旧 `PaperDominanceGraph` 一致。真实主线中，40-2 两侧均为 `bound=22490, exact calls=12, pool=10323`，旧图 `solve=18.189s, exact=4.037s`，新图一次同环境结果为 `solve=17.562s, exact=4.002s`。W300/50-3 setupR50 两次旧图结果为 `solve=211.187/245.986s, exact=171.098/206.208s`；最终新图在相同 `bound=1726.014329, pricing=219, pool=7600, exact calls=9` 下取得 `solve=180.760s, exact=138.478s`。另一次机器整体降频的新图结果仍保持相同轨迹和 bound。20-2 完整 root 闭合为 `obj=bound=6343, exact calls=1, valid=true`。

配置 `useIncrementalSourcedDominanceGraph` 默认开启；full-domain runner 属性为 `twet.bpc.fullDomainCompare.incrementalSourcedDominance`，outsourcing runner 属性为 `twet.bpc.outsourcingCompare.incrementalSourcedDominance`。显式设为 `false` 可完整回退旧 Paper graph。新 backend 只用于 normal Paper dominance 且当前无 active SRI；partial/list backend 和 active SRI 保持原实现，避免混入区间 trim 或不完整 cut state 语义。
179. 2026-07-11 incremental dominance 主线接入复核

本轮沿 `TWETBPCContext -> pricing engine -> GCNGBBStyleBidirectionalNgDssr -> DominanceStore` 重新核对实际接线。normal ng-DSSR 的正式 `price()` 和 repair `findFeasible()` 均使用 `PAPER` backend；当 `useIncrementalSourcedDominanceGraph=true` 时，前后向每个 job 的 store 都实际创建为 `IncrementalSourcedDominanceGraph`。list-partial、graph-partial 分别继续使用 `PartialListDominanceStore` 和 `PaperPartialDominanceGraphs`；active SRI 只在 list-partial backend 建立 `SriAwarePartialListDominanceStore`。因此新图当前是 normal/no-SRI 的生产优化，partial 的兼容口径是保持原后端隔离，并不是 partial 也使用新图。

三条路径最终都汇合到 `finalizeGeneratedColumns()`：恢复出的 basic sequence 用 `TWETColumnEvaluator` 计算全域真实成本，再用同一份完整 pricing dual（包含 active SRI cut dual）重算 reduced cost，只有真实负列进入 Master。Subset-row generator 也在 `separate()` 入口检查支持模式，normal 和 graph-partial 即使误开 cut 配置也不会实际添加 SRI cut，不存在 master 带 cut 而 pricing 漏算 cut dual 的组合。

验证方面，重新编译并运行 `IncrementalSourcedDominanceGraphConsistencyTest`，96,000 次随机插入及定向菱形、删除重连用例通过，新图 active labels 与 brute-force 一致；20-2 normal root 实际日志确认 exact engine 为 `GCNGBBStyleNgDssrPricing` 且统计标签为 `incrementalSourcedGraph`。当前 SRI smoke 仍为 list-partial、添加 40 条 cuts、`valid=true`。未发现新的正确性问题。剩余效率点主要是：predecessor envelope 真正下降时仍需逐条扫描该 node 的全部历史 labels；`collectActiveLabels()` 会扫描历史 nodes/labels 并跳过 inactive 项；新 reachable key 的 Hasse 拓扑维护仍包含候选排序和包含去重。这些都属于真实但次级成本，现有 W300 日志中 dominance insert/propagate 已明显低于扩展和 join，不建议在没有新热点证据时继续增加索引或复杂状态。

180. 2026-07-11 predecessor label 扫描与 Hasse 拓扑成本口径修正

第 179 节把三项都称为“次级成本”不够准确。若看完整 exact pricing，dominance 时间通常低于扩展和 join；但若只看 dominance graph 内部，`removeLabelsDominatedByPredecessors()` 的全 node label 扫描是当前最主要的剩余低效点。传播到某 node 后，代码先把 sparse delta 分别 merge 到 `predecessorEnvelope=h` 和综合 `envelope=g`；只要 `h` 有真实下降，就遍历 `node.labels`，对每条尚未 dominated 的 label 做一次完整 `h.coversAndDominates(label.frontier)`。`node.labels` 当前不压缩，历史 dominated label 也留在列表里，只靠布尔标记快速跳过。新图已经消除了全 predecessor 重扫和完整 `g` 重建，但尚未实现最初讨论的“依据来源包络直接识别并删除全部失效 label”。来源消失清理的第一版曾因 W300 bound 分歧撤回；该分歧后来定位为最终 sequence 成本未回刷，不能继续当作来源清理理论错误的证据，但当前生产实现仍保持安全的逐 label 扫描语义，尚未重新实现和验证无扫描版本。

Hasse 拓扑维护是另一类低频成本。每个 terminal job 的 dominance store 按 reachable-set key 建包含关系 DAG；只有 `nodeByReachableSet` 没有当前 key 时，才搜索最深的已有 superset 作为直接 predecessors，再从其下方搜索应成为直接 successors 的 subset nodes。候选按 cardinality 从大到小排序，并删除已经被更近 superset 覆盖的候选，随后断开被新 node 替代的跨层边并重连。same-key label 插入不执行这些搜索和排序。W300 第一轮约 78,086 个 kept labels 只创建 886 个 graph nodes，因此它通常比逐 label predecessor 清理低频；但仍应在需要时单独计时，而不能仅凭频率断言绝对耗时很小。

181. 2026-07-11 source-aware label 归零清理正式接入

本轮按原讨论直接取消 predecessor 更新后的全历史 label 扫描。`SourcedEnvelope` 在每次 min-merge 已经构造新 segment list 的基础上，维护当前仍出现的本地 label source 集合；新旧 source 集合之差就是“最后一个有效包络段刚消失”的 labels。它们已由其余同-key labels 或 predecessor 的集体下包络在完整定义域上覆盖，可直接标记 dominated。source 集合、消失列表都惰性分配，纯 external predecessor envelope 不承担空 map/list 开销。same-key 新 label 和 predecessor sparse delta 统一使用该清理，`removeLabelsDominatedByPredecessors()` 及其逐 label PWLF dominance 扫描已经删除。partial/list/SRI backend 不接入该逻辑。

正确性测试从“必须保留旧 Paper active 状态”改为直接验证集体包络支配：96,000 次随机插入中，新图相对旧 Paper 多清理 46,502 个可由其余 eligible labels 集体支配的历史状态；每次插入后数值包络继续与 brute-force 一致，且每条 active label 都仍贡献至少一个 source segment。性能 smoke 的最终 active labels 为 Paper/new `164/143`。40-2 同配置 root A/B 均得到 `bound=22490, valid=true`：旧 Paper 为 `root=55.921s, exact=42.387s, exact calls=15`，source-aware 新图为 `root=18.487s, exact=5.251s, exact calls=18`。最后一轮 no-negative certificate 中，active label/node 平均/最大由 `58.978/466` 降至 `7.831/30`，join pairs 由约 `5.79m` 降至 `0.20m`。列池和 exact 轮数因更强的集体 dominance 改变，但最终 root bound 一致，最终候选仍统一经过 sequence evaluator 和完整 dual 回刷。

Hasse 拓扑本轮不改。当前同-key 由 hash map 直接命中，只有首次出现的新 reachable key 才沿包含 DAG 搜索 immediate supersets/subsets；遍历有 cardinality 和 bitset 包含剪枝，并在命中第一个 subset 后停止向下。可选的进一步优化是按 cardinality 建 node bucket，或用在线 maximal-subset 维护替代候选排序，但前者可能把窄 DAG 遍历变成宽 bucket bitset 扫描，后者仍有 pairwise 包含检查。现阶段 source-aware 清理已经把 active label 和 join 输入大幅压缩，Hasse 没有表现为新的主瓶颈，因此不为它增加额外索引状态。

182. 2026-07-11 source-aware 提速来源与 Hasse 可选优化

40-2 的大幅提速不是单纯省掉 `removeLabelsDominatedByPredecessors()` 扫描本身，而是 source 归零让 same-key 集体 dominance 真正删除旧 labels，形成后续乘数效应。最后一轮中 forward extension candidates 从 `485,545` 降至 `55,618`，backward 从 `150,045` 降至 `22,831`；被删除 label 不再扩展，因而进一步少生成子 labels、少触发 dominance merge，最终 join pairs 从 `5,793,255` 降至 `200,154`。新图 exact calls 反而从 15 增至 18、最终 pool 从 24,587 变为 25,460，说明它不是通过少做 pricing 或少加列取巧，而是每轮 labeling/join 显著变轻；两边 root bound 均为 22490。

Hasse 的 cardinality bucket 方案是为每个 reachable-set 大小维护 node 列表。插入大小为 k 的新 key 时，只在相关 cardinality bucket 中做 bitset superset/subset 判断，再保留包含关系下最接近新 key 的 antichain，避免从 roots 沿 DAG 遍历无关分支。它在 Hasse 图很宽、包含边剪枝差时可能有效，但若 bucket 很宽，会把当前窄路径遍历变成对大量同 cardinality nodes 的 bitset 扫描，并增加 node 删除/重建时的索引维护。

在线 maximal-subset 方案只替换当前 `candidates -> cardinality sort -> pairwise 去冗余`：候选 c 到达时，若已有 kept node 是 c 的 superset，则直接丢弃 c；否则删除所有被 c 包含的 kept nodes，再加入 c。这样不需要排序，并可提前压缩候选列表，但最坏仍为 pairwise bitset checks，收益只来自候选到达顺序较好和中间列表更短。当前 Hasse 搜索已经利用拓扑在首次命中 subset 后停止向下，source-aware 后每个 store 的 active graph 更小，因此上述两种方案都不是当前优先项。

183. 2026-07-11 source-aware 当前流程与原讨论逐项对齐

normal/no-SRI 当前流程已与最终讨论方案对齐：label 的 `reachableSet` 实际是 `dominanceSet`，表示 raw ng-memory、当前 full-domain 下永久不可达任务和全局排除任务之并集的可用补集；同 terminal job 下按该 key 建 Hasse node。same-key 新 label 先用当前综合包络 `g` 做只读完整支配检查，未被拒绝时才执行带 source 的 min-merge；这与“直接 merge 后看新 source 是否存在”语义等价，但能避免为大量立即被拒绝的 label 构造临时 merged segments。merge 后旧 source 归零即删除对应 label，新 source 保留并入队。

新 key 流程先搜索 immediate superset predecessors，合并其 `g` 得到 external `h`，若 `h` 已支配候选则拒绝；否则建立 `g=min(h,new frontier)`、插入 Hasse 并传播新 label 真正降低 `g` 的 sparse delta。successor 收到 delta 后分别执行 `h<-min(h,delta)` 和 `g<-min(g,delta)`；按新旧 segment source 集合差删除归零本地 labels。`g` 无数值下降则停止向下；本地 source 全部消失则删除 graph node并重连原 predecessors/successors。当前不再维护独立本地 `labelEnvelope`，也不再重扫全部 predecessors、重建完整 `g` 或逐 label 扫描 predecessor dominance。

被 source-aware 删除的 label 若仍在 expansion queue，出队时由 `isDominated` 直接跳过；若已写入按 terminal job 保存的 join list，则 join 前 compact 会删除。final join 仍按真实 `ngMemorySet` 交集和 branch/pricing-only arc 检查兼容性；恢复出的 sequence 在进入 Master 前统一用 evaluator 回刷全域真实成本并按完整 dual 重算 reduced cost。raw ng-memory 不同但 dominanceSet 相同不构成独立反例：差异任务若不是由 memory 禁止，就必须已经在另一 label 上由 full-domain 永久不可达或全局排除覆盖；这依赖当前 full-domain 可达性判断的单调安全语义。

两个边界保持不变。第一，source-aware 新图只用于 normal/no-SRI；list-partial、graph-partial 和 SRI 仍使用原 backend，因此“partial 兼容”是隔离兼容，不是已经获得同样优化。第二，当前验证包括 96,000 次逐插入数值/来源不变量测试和 40-2 root A/B，但最终 source-aware 版本尚未重新完成 W300 端到端复验；因此目前理论流程和已有测试对齐，W300 仍是建议补做的高压力回归，而不是已完成证据。

184. 2026-07-11 source-aware normal 与 partial dominance 的剩余区别

source-aware normal 已覆盖 partial 的 whole-label 删除能力：每条 active label 必须至少贡献一个综合包络 segment，最后一个 source segment 消失时立即删除。因此它不会像旧 normal 那样长期保留完全不贡献下包络的同-key label。但这不等于 partial 已完全无用。一个保留 label 可能只在很窄区间贡献，原始 frontier 的其余区间已经由 predecessor 或其它同-key labels 支配；source-aware normal 只记录哪些 segment 仍由它贡献，不修改该 label 自己的 frontier。该 label 出队扩展时仍对完整 frontier 做 shift/add/normalize，join 也仍携带完整函数。partial dominance 的额外能力正是把被支配区间从 label frontier 原地裁掉，只保留未被支配子域，从而减少函数 segment、扩展和后续子 label。

因此当前判断为：source-aware normal 使 partial 的主要优势从“删除整条零贡献 label”缩小为“裁剪仍有局部贡献 label 的被支配区间”，两者差距应明显缩小，但不能仅据 active label 都有 source 就断言 partial 没有额外收益。另一个现实区别是 active SRI 目前只接在 list-partial backend；如果需要 SRI，partial 仍是现有正式入口。normal 中新 label 每次插入仍会做当前 `g` 的支配检查，已保留 label 后续每次 envelope merge 也会重新经历 source 集合更新；它不是保留后便永久不再判断，只是不再逐 label 扫描。

185. 2026-07-11 删除 dead dominance node 的最后一处历史 label 扫描

继续沿生产路径复查后，确认 predecessor propagation、same-key merge 和 source 归零清理都已不再遍历 `node.labels`，但 `deleteNode()` 仍残留一段历史防御扫描：`activeLocalLabels` 已经降为 0 后，又遍历整份历史 label 列表逐个设置 dominated。该扫描没有新增语义，因为 source-aware merge 在每个本地 source 最后一个包络段消失时已经当场设置 `isDominated`、更新 `labelsRemoved` 并递减 `activeLocalLabels`；`deleteNode()` 只会在该计数为 0 时调用。

现已移除这段重复扫描，并在 dead node 删除时释放其历史 label 列表引用。正式 dominance 插入、传播和节点删除路径因此不再全扫某 node 的历史 labels。仍保留两类必要遍历：min-merge 会扫描实际 envelope segments/source 以构造下降区间和来源差；join 前会 compact 独立的 terminal active-label lists，清掉其中懒保留的 dominated 引用。`collectActiveLabels()` 和 source invariant 检查仍可扫描 active node 的 label 列表，但它们只用于一致性测试/诊断，不在 ng-DSSR 正式定价主线调用。96,000 次增量图随机/定向一致性测试和 32,000 次旧 Paper 图测试均通过。

186. 2026-07-11 弃用旧 dominance 框架并在 source-aware 图上重做 partial 的方向

结合前述代码复查和 40-2 A/B，后续不再继续优化旧 `PaperDominanceGraph`、`PaperPartialDominanceGraph` 和 `PartialListDominanceStore` 的主体框架。旧 normal graph 在 predecessor 包络变化后需要扫描 node 下全部历史 labels，并在发生删除时重新合并本地 label 包络和 dominance 包络；旧 graph-partial 在此基础上还要逐 label 做 PWLF 区间比较和裁剪；list-partial 则在每次插入时按 cardinality 两遍扫描所有可能支配新 label、或被新 label 支配的 buckets。即使继续优化单次函数比较，这些框架的工作量仍随 node/bucket 中保留的历史 label 数增长。规模越大、同 key label 越多，扫描、PWLF 比较、裁剪和重建会共同放大，无法获得当前 source-aware 方案的复杂度结构。

当前增量 source-aware 图的核心差异不是一个常数级 fast path，而是把维护对象从“node 下所有历史 labels”换成了“当前数值包络 segments 及其来源”。same-key 插入和 predecessor 传播都只做 min-merge；包络 segment 来源消失时直接定位并删除对应 label，没有 node 全量扫描，也不重建本地包络。40-2 同一 root bound 下，root/exact 从 `55.921s/42.387s` 降到 `18.487s/5.251s`，最后一轮 active label/node 平均/最大从 `58.978/466` 降到 `7.831/30`，join pairs 从约 `5.79m` 降到 `0.20m`。因此旧 normal 和旧 partial backend 在算法方向上标记为 deprecated：保留代码只用于回归对拍、历史实验和当前尚未迁移的 SRI 状态，不再作为无 SRI 主线，也不再投入局部性能优化。后续 dominance 加强统一基于 `IncrementalSourcedDominanceGraph` 实现。

partial dominance 的思想仍可保留，但应改成 source-aware partial，而不是恢复旧框架。当前每个综合包络 segment 已记录本地来源 label；一次 merge 完成后，可以在扫描 merged segments 的同时得到每个 local source 当前仍贡献的时间区间并集。若某个 label 的来源区间完全消失，继续按现逻辑整条删除。若来源仍存在但区间缩短，则只处理这个发生变化的 label：把其 frontier 限制到仍由它贡献的多个区间，其他区间置为 `BigM`，再按方向执行 forward prefix closure 或 backward suffix closure，并刷新有效函数。这里不能只取 `[minStart,maxEnd]`，因为一个 label 可能在多个不连续区间贡献下包络；取 hull 会把中间已经被其它来源支配的区域重新保留，基本失去 partial 的作用。

该裁剪在逻辑上是安全的。综合包络只会随新 label 或 predecessor delta 单调下降；某个 label 一旦在区间上失去 source，后续不可能因为其它来源变差而重新成为最优来源。并且同一个 dominance node 的 terminal job 和后续扩展规则一致，predecessor node 的 dominanceSet 又提供不弱于当前 node 的可扩展集合，因此在某个时间点压低该 label 的来源可以替代它执行相同后续扩展。置 `BigM` 后的方向 normalize 可能按等待/反向闭包语义重新得到部分有限区间，但这些值只来自仍保留区间的合法闭包，不会优于原 frontier，也不会抬高综合包络；它们可以保守保留，不需要反向重建 envelope。

实现时只应处理本轮 source 区间发生变化的 labels，不能重新扫描 `node.labels`。`SourcedEnvelope.installMergedSegments()` 已经遍历 merged segments 并构造 local-source 集合，可在同一次遍历中形成 `Label -> interval list`，再比较受影响来源的旧/新区间。整条删除沿用现有 `displacedLocalSources`；部分缩短新增 `trimmedLocalSources`，只对这些 labels 做 mask、normalize 和最小值更新。label 的 `extensionSet` 即使不重算也只会保守多枚举 job，不影响正确性；active terminal list 持有同一 label 对象，会自然读取裁剪后的 frontier。

进一步按当前 ng-DSSR 队列控制复核后，不需要为了 partial 裁剪额外引入 queue entry/version。正式 forward/backward exact round 的退出条件是队列为空、全局 time limit 或 pricing 整体关闭；代码不读取 `queue.peek()` 的最小值来提前闭合，也不因已经找到若干列而停止扩展。因而 label 入队后原地裁剪 frontier，并刷新真实 `minReducedCost`，虽然会使 Java `PriorityQueue` 的内部顺序不再严格对应新键，但不会丢失元素；无 time limit 的 certificate 轮仍会 poll 并处理全部未 dominated labels，最终最优性不受影响。time limit 下处理进度和 midpoint probe 的有限 pop 顺序可能变化，但这些路径本来不提供队列未耗尽时的 no-negative certificate，只影响效率。实现时可以按最小方案只修改 label frontier、执行方向 normalize 并刷新 minimum，不改队列结构。由 terminal job 维护的历史 scalar minimum 即使暂时偏低，也只是更弱的安全 lower bound；join 前 compact 会基于裁剪后的 labels 重新汇总。

SRI 是迁移边界，不是继续保留旧 partial 框架的性能理由。当前 SRI-aware list partial 还通过 frontier adjuster/cut state 处理不同 label 的可比性，而现有 source-aware key 和 envelope 没有包含该状态。因此第一步只应实现 normal/no-SRI 的 source-aware partial，并与当前 source-aware normal 做开关 A/B；SRI 必须等 source merge 的可比条件显式纳入 cut state 后再迁移。验证至少包括逐次 merge 后综合 envelope 数值不变、裁剪 frontier 不低于原 frontier、每条 active label 仍有 source、forward/backward 定向多区间案例，以及 40-2/W300 的 root bound、最终列真实成本、extension candidates、frontier segments 和总 exact 时间对拍。

187. 2026-07-11 source-aware partial 实现与首轮效果

现已在 `IncrementalSourcedDominanceGraph` 内实现 partial 模式，没有新增或复用旧 dominance backend。normal 和 no-SRI partial 都统一进入新图；`PAPER` backend 使用 source-aware whole-label 清理，`GRAPH_PARTIAL` 和 no-SRI `LIST_PARTIAL` 在同一逻辑上额外裁剪 label frontier。旧 `useIncrementalSourcedDominanceGraph` 配置字段保留为 deprecated 配置兼容项，但 no-SRI 主线不再允许回退旧 Paper/partial 图。active SRI 因 cut-state 可比性尚未进入 source envelope，暂时仍使用原 SRI-aware list store，这是当前唯一保留旧 dominance store 的正式路径。

partial merge 不扫描 `node.labels`。每次 min-merge 在原本生成 merged source segments 的同时，只记录两类受影响来源：新加入的候选 label，以及本轮被新 label/predecessor 替换掉部分 source 区间的旧 labels。若来源全部消失，沿用 whole-label dominated 删除；若仍有来源，则直接按该 label 在 merged envelope 中保留的多个 source 区间重建 frontier，区间外填 `BigM`，执行 forward prefix normalize 或 backward suffix normalize，并刷新 `minReducedCost`。frontier、队列、active terminal list 和后续扩展之外的结构均不改变。PriorityQueue 不重建：正式 exact round 不按队首值提前闭合，完整轮次仍耗尽队列，所以 heap 顺序变化只影响处理次序。

函数级验证新增 normal/partial 双图随机对拍。正反向各插入 2,000 个随机 PWLF labels，逐次确认两种模式的 dominated 接收结果一致、partial 图综合 envelope 与全部原始历史 labels 的 brute-force 下包络一致、每条 active label 仍贡献 source，且裁剪后的单条 frontier 从不优于原 frontier；测试同时要求实际发生 partial trim。原有 96,000 次增量图随机/定向结构测试和 32,000 次旧 Paper 图测试继续通过。

另做 40-2 五秒主线 routing smoke，日志明确显示 `GCNGBBStyleNgDssrGraphPartialDominancePricing` 内部使用 `incrementalSourcedGraph partial=true`，累计 `trims=2918, segments=9371->7515`；全局时间到达后返回 `TIME_LIMIT, valid=true`，没有把未耗尽队列误作闭合证书。

40-2 首轮压力 A/B 使用同一极少 seed 口径：关闭 ALNS、启发式 pricing、time-indexed 预处理和 strong branching，只跑 root，时限 300 秒。normal 为 `302.665s/44 exact calls/pool 10369`，partial 为 `300.280s/42 exact calls/pool 15854`，两者都在 root 未闭合，因此不能比较最终 bound。累计统计中，partial 实际裁剪 556,874 次，涉及 segments `1,363,473 -> 1,305,391`，只减少约 4.26%；forward candidates 从 normal 的 992,056 降到 930,553，约减 6.2%，backward 从 993,827 降到 918,243，约减 7.6%。但两边很快进入不同列/dual 路径，partial 累计 join pairs 为 432,671,164，高于 normal 的 367,758,663，约增 17.7%，因此总体没有显示稳定加速。

前几轮局部上 partial 有明显收益，例如第 7 次 exact 的时间约 `682ms -> 355ms`，forward candidates `4158 -> 2213`，join pairs `1.174m -> 0.712m`；但后续 dual 路径分叉后该优势没有维持。当前结论是：source-aware partial 已正确、低耦合地接入，并确实减少一部分扩展；但由于方向 normalize 会从保留区间恢复等待/后缀闭包，实际 segment 缩减只有约 4%，尚不足以稳定压低 join 总量。该模式保留用于后续在 W300/宽窗口等 frontier 更厚实例上测试，当前不能据此替代 source-aware normal 作为默认最快模式。

188. 2026-07-11 新 dominance 图完整流程与剩余冗余复核

当前 no-SRI normal/partial 的核心流程与讨论方案一致。每个 terminal job 下按 `dominanceSet/reachableSet` 建 Hasse node；同 key label 先用当前综合包络 `g` 做只读支配判断，大量立即失败候选不分配 merged envelope。候选未被支配时执行 `g <- min(g, frontier)`，每段保留 local label source 或 external predecessor source。新 key 先沿 Hasse 图找到 immediate superset predecessors，将其 `g` 合并为 predecessor envelope `h`；若 `h` 不完全支配候选，则建立 `g=min(h,frontier)`，接入 immediate predecessor/successor 边，并只传播本次真正下降的 sparse delta。

successor 收到 delta 后执行 `h <- min(h,delta)` 和 `g <- min(g,delta)`。source 最后一个 segment 消失时直接标记对应 label dominated，计数归零的 node 删除并重连 Hasse 边；没有数值下降则停止向后传播。传播队列对一次 label 插入的同一下降函数，每个 graph node 最多入队一次。被删除 label 若仍在 expansion queue，poll 时按 `isDominated` 跳过；active terminal list 在 join 前 compact。正式 dominance 传播、whole-label 删除和 partial 裁剪均不扫描 `node.labels`。

partial 只比 normal 多一层 source 区间裁剪。每次 merge 只记录新 label 和本轮丢失部分 source 的旧 labels；完整失源直接删除，部分失源按剩余多个离散 source 区间重建 label frontier，外部置 `BigM` 后做方向 normalize 并刷新 minimum。新 label 在入队前已经按最终 source 区间裁剪；尚未出队的旧 label 会使用更新后的 frontier 扩展。已经出队并生成的 children 不会回收，因此这是在线增量裁剪，不是对历史搜索树的回溯压缩。normalize 还会恢复等待/prefix 或 suffix closure，所以最终 frontier 是“source 区间产生的合法方向闭包”，不是只含裸 source segments；这与前述 BigM 后 normalize 的语义一致。

两个边界需要继续保留。第一，active SRI 的 cut-state/compensation 尚未进入 source envelope 的可比条件，因此有 active cuts 时仍切换到旧 `SriAwarePartialListDominanceStore`；无 active cut 的 partial 已使用新图。第二，旧 `PaperDominanceGraph`、graph partial 和 no-SRI list partial 虽然仍保留源码用于回归，但 no-SRI 主线已无法通过旧配置开关退回，`GRAPH_PARTIAL` 与 no-SRI `LIST_PARTIAL` 当前实际都表示同一个 source-aware partial 行为。

剩余效率问题按优先级如下。第一，partial 当前对每次 source 小变化都立即重建 PWLF、填 BigM、normalize、find minimum。40-2 压力 run 累计发生 556,874 次裁剪，但 segments 总量只从 1,363,473 降到 1,305,391，约 4.26%；这说明大量重复裁剪的收益较小，可能抵消扩展减少。更合理的后续方向是合并同一 label 的连续 source 更新，或对尚未扩展 label 在 poll 前应用最新裁剪、对已扩展 label 在 join 前只应用一次最终裁剪；这需要保存最新 source interval/version，但不应恢复 node 全 label 扫描。

第二，更新 `predecessorEnvelope h` 时仍调用完整 merge，并生成一份调用方完全不使用的 `MergeOutcome.delta`；新 node 聚合多个 predecessor envelope 时也有同样问题。应增加 no-delta/no-source-output 的 in-place min merge，只更新 `h` 几何，避免构造并丢弃 sparse delta。第三，partial 的 segment before/after 统计当前每次裁剪都各扫描一遍 PWLF，即使没有打开 timing diagnostics 也执行；正式性能模式应关闭这两次统计扫描或只在诊断开关下启用。

第四，传播中对未删除 node 会在检查 `outcome.delta.isEmpty()` 前复制整个 successor set；无数值下降时该副本立即丢弃，可以把复制移动到 delta 非空之后。第五，node 仍把所有历史 accepted labels 记录在 `node.labels`，但正式主线不读取，只供一致性接口/诊断；inactive node 也保留在 `nodes` 统计列表直到本轮 pricing 结束。这主要是引用内存和 summary 扫描开销，不是扩展热路径，可后续让诊断直接从 envelope local sources 收集 active labels，再移除历史列表。第六，accepted label 会先做一次只读 `coversAndDominates`，随后 merge 再扫描一次 envelope；但 rejected labels 通常占多数，该 fast reject 避免了 merged list/source map 分配，目前属于合理的时间换内存策略，不建议优先改。

Hasse 的 superset/subset 搜索、候选排序和 node 删除重连仍有 pairwise bitset/集合操作，但只在新 key 建立或 node 删除时发生；same-key 热插入不走这些逻辑，当前统计也没有显示它是瓶颈。因此不应先做 cardinality bucket 等额外索引。更重要的是，整体 exact pricing 的最大耗时仍常在 join：本次 partial 压力 run 即使减少正反扩展，累计 join pairs 仍达到 4.33 亿。新图后续优化应优先减少重复 partial rebuild，并观察它能否进一步减少 active labels/join pairs；只缩短 dominance 自身的常数无法单独解决整体 pricing 时间。

189. 2026-07-11 dominance 插入、partial 重建与 label 生命周期补充

`dominanceSet/reachableSet` 的构造没有随新图改变：仍由 ng-memory、完整定义域下的直接不可达任务和全局排除任务共同决定；半域限制只影响 extension set，不进入 dominance key。新图改变的是同一套 key 上的包络维护和传播方式，不是状态语义。

同 key 插入前的 `coversAndDominates()` 不是正确性所必需。理论上可以直接执行一次 source-aware min-merge，并以候选 source 是否贡献任何区间判断接受或拒绝。当前保留预检查是性能 fast path：被拒绝的 label 只扫描旧包络，不构造 merged segment list、source map 和 sparse delta；被接受的 label 才会发生第二次扫描。由于压力实验中 rejected label 明显多于 accepted label，直接删除预检查未必更快。若后续优化，应把 merge 改成“扫描到第一处真实改进后才延迟分配”的单遍实现，而不是让所有 rejected label 都完整构造 merge 结果。

partial 的当前重建过程是：按该 label 在新综合包络中仍贡献的离散 source segments，重建一条 PWLF；非贡献区间填 `BigM`；随后执行方向 normalize 和 minimum 刷新。forward normalize 恢复 prefix/waiting closure，backward normalize 恢复 suffix closure，因此 retained source 区间外仍可能出现由合法等待传播得到的有限函数值。这不是把已删区间重新放回，而是维持现有 frontier 的方向语义。当前主要冗余来自每次小范围 source 变化都立即完整重建；更有效的方向是记录最新 retained intervals/version，在 label 出队前或 join 前合并应用，而不是修改单次 normalize 的数学口径。

label 同时存在于三类容器。被接受后，它进入 graph node 的 `labels` 历史列表、forward/backward priority queue 和按 terminal job 保存的 active list。后续被 source-aware dominance 完全覆盖时，只设置 `isDominated=true` 并减少 active 计数；不会从 priority queue 或 node 历史列表中间删除。priority queue poll 时会跳过 dominated label，因此它不再扩展；join 前 active list 会 compact，dominated label 不参与 join。`node.labels` 当前不会逐条删除，只有整 node 删除时置空；正式 dominance 逻辑不扫描它，主要服务测试和诊断，因此属于引用内存冗余而非 CPU 热点。

当前队列顺序仍是时间优先：forward 按最早完成时间升序，backward 按最晚完成时间降序，并以 reachable cardinality 和 reduced cost 作后续比较。partial 可能在 label 已入堆后修改其 frontier/minimum，但完整 exact round 会耗尽队列，不依赖队首键形成提前证书，所以堆序陈旧只改变处理顺序和超时前进度，不改变完整轮次结果。已经出队扩展过的 parent 后来被裁剪或删除时，已生成 children 不回收；这些 children 仍是合法但可能冗余的状态，在线 dominance 只能避免后续新工作，不能回溯撤销已完成扩展。

本轮先处理一项确定冗余：partial 裁剪前后 segment 数的两次 PWLF 遍历改为只在 `twet.bpc.incrementalSourcedGraphTiming=true` 时执行；正式求解仍保留 O(1) 的裁剪次数统计，不再为 summary 扫描每条被裁函数。same-key 只读预检查继续保留，后续若实现延迟分配的单遍 merge 才重新评估；其预期空间已小于 partial 重建频率优化。已经扩展的 children 不撤销只会保留合法冗余状态，当前也不是优先处理项。

190. 2026-07-11 source-aware partial 惰性裁剪与无效状态清理

本轮将 partial 从“每次 source 区间变化立即重建 label PWLF”改为惰性合并。source-aware envelope merge 仍立即更新正确的综合包络，并立即删除完全失去 source 的 label；对仍有贡献但区间缩短的 label，只在 graph 内按 identity 保存最新 retained segments。后续更新直接覆盖同一 label 的旧 pending 状态，不扫描 node labels，也不累计已经过时的区间。label 从 forward/backward priority queue 出队并准备扩展前应用一次 pending trim；已经扩展过、之后又被 predecessor/source 更新继续收窄的 label，则在 join compact 前应用一次。这样扩展和 join 始终读取最新 frontier，而同一阶段内的多次 source 变化只触发一次 PWLF 重建。

惰性处理不改变队列和 dominance 语义。pending label 在真正使用前可以暂时保留偏松的旧 frontier 和旧 minimum；当前 exact round 不依赖 priority queue 队首提前形成证书，出队前会刷新，因此只可能改变处理次序。综合 dominance 判断始终使用已经更新的 source-aware envelope，不读取 pending label 的旧 frontier。forward terminal 的 scalar min/earliest completion 也会在 join compact 时基于刷新后的 live labels 统一重算。虚拟 backward sink 不进入按 terminal job 建立的 dominance store，因此 prepare hook 明确跳过 `jid=n+1`。

同时将 predecessor envelope `h` 的更新切换为 no-delta merge。`h <- min(h,input)` 仍完整更新几何包络，但不再构造调用方不会使用的 `SparseDelta`、partial source map 和 retained-source 结果；只有综合包络 `g <- min(g,input)` 记录数值下降 delta 并继续向 successors 传播。新 reachable key 聚合多个 predecessor envelope 时同样使用 no-delta 路径。当前实现仍复用统一 merge 主体并创建一个很小的 outcome 控制对象，以保持同一数值/tie 逻辑；主要去掉的是每个下降区间的 delta 对象与相关来源跟踪。

新图中的 `node.labels` 历史列表已经删除。正式 join 原本就使用 ng-DSSR 按 terminal job 维护的 active lists；graph 的 active label 集合可直接由 envelope 的 `localSources` 得到。`getActiveLabels()` 和一致性诊断已改为读取 local sources，并在返回前应用 pending trim。dominated label 仍采用 priority queue 和 terminal active list 的惰性删除，但不再被 graph node 额外长期引用。

验证包括 focused `javac`、开关关闭/打开下各 96,000 次随机结构一致性测试，以及新增 deferred-partial 测试。后者连续插入期间不执行 trim，最后统一 prepare，并核对综合包络、active source 和裁后 frontier。40-2 no-ALNS/no-heuristic/no-strong、单节点 partial root smoke 运行到时间限制但 `valid=true`，未出现列或 bound 语义错误。日志确认惰性合并实际发生：较重一轮有 `75,945` 次 partial source 更新，其中 `31,344` 次覆盖已有 pending 状态，最终实际重建 `19,138` 次；其余 pending 状态还可能在完全失源、后续恢复为覆盖当前 frontier或时间限制时被清除/未消费，不能把差值全部解释成节省的重建次数。该 smoke 的主要时间仍在千万级 join，不在 dominance。

191. 2026-07-11 dominance graph 最终冗余复核

再次沿 same-key 插入、new-key Hasse 接入、delta 传播、source 删除、pending partial、出队刷新和 join compact 检查后，处理了四处仍可严格等价删除的工作。第一，active successor 原来在确认输出 delta 非空前复制 successor set；现在空 delta 直接停止，只有需要继续传播时才复制。第二，normal 和 SRI 路径原来仍进入 partial prepare 的包装调用；现在只在 no-SRI source-aware partial 模式调用，normal 与 SRI 不再执行 cast/map 检查。第三，pending retained intervals 入 map 时已经证明不能覆盖当前 frontier，而 frontier 在 prepare 前不会由其他入口修改，因此 prepare 不再重复做第二次区间覆盖扫描。第四，predecessor envelope `h` 永远只有 external geometry；no-delta merge 安装新几何时直接替换 segment list，不再扫描 merged segments 重建必为空的 local-source map。

同时清理了 dead Hasse node 的生命周期。原实现虽然已断开 dead node 的所有拓扑边，但仍把它保留在 `nodes` 历史数组，导致其 predecessor/dominance envelope 和 segment 数组一直存活到本轮 pricing 结束。日志曾出现 `created/deleted/active=404/339/65`，说明该引用保留并非极端情况。现在 active nodes 使用 `LinkedHashSet`，delete/reconnect 完成后 O(1) 从集合移除；轮末统计仍由 created/deleted 计数给出累计值，active summary 只扫描实际存活节点。

保留的成本均有明确作用。same-key `coversAndDominates` 会让 accepted label 多扫描一次，但避免数量更多的 rejected labels 分配完整 merge/source/delta 结果；只有以后实现延迟分配的单遍 merge 才值得替换。new-key 的 superset/subset 搜索、排序和重连是维护 Hasse 拓扑所需，且不是 same-key 热路径。no-delta 仍复用统一 merge 主体并创建一个小 outcome 对象，以共享数值交点、tie 和 segment 合并语义；完全拆成第二套几何 merge 会增加重复代码和对拍面，当前收益不足。统计计数均为 O(1)，完整 node summary 每轮只执行一次并保留实验价值。

验证重新执行 focused 编译、诊断开关开/关下各 96,000 次随机一致性测试，以及 40-2 no-ALNS/no-heuristic/no-strong 的 45 秒 partial root smoke。随机测试通过；真实 smoke 为 `TIME_LIMIT, valid=true, exact calls=23`，没有结构、列或 bound 异常。该配置只用于运行期接入验证，不作为性能基准；日志中已完成轮次的主要耗时仍是百万级函数 join。

192. 2026-07-11 dominance graph 逐调用链正确性复核

继续沿真实 label 字段生命周期检查后发现，no-SRI `FunctionLabel` 仍把 `noSriFrontier` 别名为主 `frontier`。source-aware partial 重建时会释放旧主 frontier 并替换为新对象，使该字段残留指向已经交回 SegmentPool 的旧 PWLF。当前扩展代码只有在 `sriPricingEnabled=true` 时直接读取 `label.noSriFrontier`，而 active SRI 又不走新 partial 图，因此该旧引用尚未进入当前 no-SRI 正式计算；但它既是无用引用，也是未来增加通用读取时的确定风险。现已按既定语义改为：无 SRI 时字段保持 `null`，需要 no-SRI 口径的候选计算通过现有 accessor 回落到主 frontier；有 SRI 时 source/sink 和每个 child 仍传入独立副本。同步删除从未被读取的 `FunctionLabel.noSriMinReducedCost` 字段和构造参数。

partial 重建后的 minimum 刷新也做了严格等价优化。旧代码调用 `Label.refreshMinReducedCost()`，内部用通用 `findMinimal()` 扫描整条新 PWLF。当前 trim 完成后已经执行方向 normalize：forward 是 prefix closure，全局最小值位于 `tail.end`；backward 是 suffix closure，全局最小值位于 `head.start`。因此现在直接 O(1) 读取对应端点，不再为每次实际 trim 扫描全部 segments。随机测试增加了每条 active partial label 的端点值与 `minReducedCost` 对拍。

同时把 dominated 检查移动到 partial prepare 之前。已经标记 dominated 的 queue label 和 terminal active-list label 不再调用 prepare；prepare 只会收缩 active frontier，不会反向把 label 标记 dominated，因此调整严格等价。再次核对所有并行缓存：`joinExtendedFrontier` 只在全部扩展和 join compact 完成后首次构建，之后没有 dominance 更新；`extensionSet/reachableSet` 在 trim 后保持原值只会保守多尝试扩展，真实窗口检查会拒绝不可行 child；queue key 可能滞后但完整 exact round 耗尽队列，不使用队首形成证书。没有发现其他因 frontier 替换而失效的正式计算字段。

验证包括 focused 编译、诊断开关开/关下各 96,000 次随机一致性测试，以及 40-2 no-SRI partial 30 秒主线 smoke。随机测试和端点 minimum 对拍通过；真实 smoke 为 `TIME_LIMIT, valid=true, exact calls=17`，确认 `noSriFrontier=null` 后现有 no-SRI 调用均正确使用主 frontier。该短 smoke 仍只用于运行期语义验证。

193. 2026-07-11 partial 惰性裁剪严格等价性复核

本轮没有继续修改生产算法，而是加强惰性裁剪的对拍口径。测试同时建立 eager 和 lazy 两套 source-aware partial 图：两者接收完全相同的随机 label；eager 在每次插入后立即消费全部 pending trim，lazy 保留到最终使用点再统一消费。测试逐次比较插入是否被支配以及随机 reachable-set/time 点的综合包络，最终再逐 label 比较 `isDominated`、缓存 minimum 和 101 个时间点的裁后 frontier。forward/backward 共 96,000 次随机插入全部一致，说明连续 source 更新被覆盖合并后不会改变 label 状态或函数几何。

同时补充端点 minimum 的独立验证。每条 active partial frontier 在 trim 和方向 normalize 后，既按现有 O(1) 规则读取 forward tail/backward head，也用通用 `findMinimal()` 完整扫描；两者与 label 缓存的 `minReducedCost` 全部一致。因此第 192 节将 minimum 刷新从整函数扫描改为端点读取不是仅靠单调性推断，已经由随机 PWLF 对拍覆盖。

再次沿正式调用链核对后，惰性 pending 只保存最新 retained intervals，综合 dominance 始终读取已经更新的 sourced envelope；label 出队扩展和 join compact 前均会消费 pending。queue key 的暂时滞后只改变耗尽式 exact round 的处理顺序；`extensionSet/reachableSet` 未随局部 trim 重算只会保守多尝试扩展，后续窗口检查仍会拒绝无效 child；join 缓存只在 compact 完成后首次建立。当前没有发现新的正确性缺口。高频冗余也已基本清理：dominated label 不再 prepare，no-SRI 不保留旧 frontier 别名，partial minimum 不再全函数扫描，诊断 segment 扫描只在显式统计开关下执行。剩余 same-key 只读预检查、new-key Hasse 搜索和统一 merge 小对象均有明确的拒绝快路径、拓扑维护或数值一致性价值，不建议为了少量常数继续拆分实现。

194. 2026-07-11 SegmentPool 生命周期复核

前述随机一致性测试原先固定关闭 `Configure.SegmentPool`，无法主动覆盖真实求解中“旧 frontier 释放后 segment 立即被复用”的路径。本轮仅扩展测试入口，使同一套随机、拓扑、partial eager/lazy 和端点 minimum 对拍可分别在池化关闭与开启时执行。两种模式均完成 forward/backward 共 96,000 次随机插入，active labels 均为 `164/143`，旧 Paper 点查询差异和保守保留计数也完全一致。

该结果直接覆盖了 partial trim 中 `old.release()` 后的对象生命周期：sourced envelope 和 pending retained intervals 保存独立几何值，不引用 PWLF Segment；no-SRI label 不再保存旧主 frontier 别名；新 frontier 的 head/tail 和 minimum 均来自重新构建后的 segment 链。池化开启后没有出现函数值、支配状态或 active source 偏差，因此当前没有释放后悬挂引用。生产算法未修改，测试默认仍关闭池化，使用 `-Dtwet.test.segmentPool=true` 时才执行池化口径。

195. 2026-07-11 持久化 predecessor envelope 的冗余分析

再次沿图内全部引用检查后，当前每个 node 持久保存的 `predecessorEnvelope=h` 是明确重复状态。设本地 label 下包络为 `f`，当前综合包络为 `g=min(h,f)`。某个 predecessor 的综合包络只会单调下降，并以 sparse delta `d` 传播；更新后 `h'=min(h,d)`，因此 `g'=min(h',f)=min(g,d)`。也就是说 successor 直接执行一次 `g<-min(g,d)` 与当前“先更新 h、再用相同 delta 更新 g”严格等价，不需要持久保存 h。

source-aware 删除也不依赖独立 h。`g` 的每段已经区分 `LOCAL(label)` 和 `EXTERNAL`；external delta 覆盖本地 source 后，merge outcome 可直接识别归零 label。新 key 第一次出现时仍需把直接 predecessors 的 `g` 临时取小，但可直接把该临时 external envelope 作为新 node 的初始 `g`，随后 merge 本地 label，无需复制出第二个包络。node 的本地 source 归零时，其 `g` 已只含 external geometry，等于原定义的 h；删除 node 并重连 predecessor/successor 不需要提高任何包络。插入中间 Hasse node 时，successor 原有 external 值已经存在，只需传播新 node 相对 predecessors 真正降低的 local delta。

因此当前正确性流程与原讨论已经对齐，但还不能称为完全没有低效：传播路径目前每个 node 仍执行一次无返回 delta 的 h merge 和一次正式 g merge，前者仍会扫描旧 h、扫描 delta 并构造新 segment list。删除持久 h 后可把传播包络 merge 数直接从两次减为一次，同时每个 graph node 少保存一条 PWLF。该等价性依赖当前“包络只单调下降、不撤销 predecessor 贡献”的设计；若未来支持删除仍有本地 source 的 predecessor、回滚 label 或令包络上升，则必须重新引入 h 或从所有直接 predecessors 重建。active SRI 当前不使用该新图，不在本结论范围内。

196. 2026-07-11 no-h 实现与 A/B 结果

本轮按第 195 节结论实现 no-h 路径，并保留 `twet.bpc.incrementalSourcedGraphKeepPredecessorEnvelope=true` 作为旧 h 路径的严格 A/B 开关。默认新 key 将直接 predecessors 的综合包络临时取小后，把该 external envelope 直接作为初始 g，再 merge 本地 label；不再复制并持久保存第二条 h。传播时只执行 `g<-min(g,delta)`，不再先执行 `h<-min(h,delta)`。用于向 successors 传播真正下降区间的 sparse delta 仍然保留；删除 h 不等于传播完整 g，后者会放大工作量。

正确性首先在 no-h/keep-h 与 SegmentPool 关/开四种组合下运行同一套 forward/backward 随机、拓扑、partial eager/lazy 和端点 minimum 对拍。每组均完成 96,000 次随机插入，`paperPointQueryMismatches=1`、`paperRetainedDominatedStateObservations=46502`、最终 active `164/143` 完全一致。说明删除 h 不改变插入返回、综合包络、source 归零、partial 裁剪或池化生命周期。

真实 A/B 使用 `wet040_001_2m`，ALNS 关闭、启发式 pricing 开启、`maxNodes=1`、normal ng-DSSR、dualPair 0.08、top1 和 bestUB join。no-h/keep-h 均得到 `bound=22490`、`pool=22420`、`pricing=192`、exact 18 次、`valid=true`，列和搜索轨迹一致。累计 graph 统计中，两边 propagated nodes 均为 18,803；no-h 的 merge 数为 247,835，keep-h 为 266,638，正好每个 propagated node 少一次 h merge。propagation 计时由 `50.368ms` 降为 `35.922ms`，约下降 28.7%。但 insert+propagate 总计分别为 `666.824/658.506ms`，受 JVM/GC 计时波动影响没有稳定总优势；exact 为 `6.114/5.981s`，总求解为 `61.914/61.412s`，同样不能解释为 no-h 变慢或变快。结论是 no-h 明确减少传播计算和每 node 一条包络内存，但 dominance 本身已不是整体瓶颈，单独删除 h 不会显著缩短完整求解。

另一次试图关闭启发式 pricing 以隔离 exact 的实验从极弱 seed 进入长尾，超过预期 120 秒且没有形成可比较 root，已终止，不纳入性能结论。该现象来自实验配置改变列生成轨迹，不是 no-h 语义异常。

197. 2026-07-11 no-h 正式路径清理

第 196 节 A/B 已完成后，旧 h 回退路径不再具有正式运行价值。本轮删除 `incrementalSourcedGraphKeepPredecessorEnvelope` 实验属性、每个 node 中恒为 null 的 predecessor envelope 字段、传播热路径中的空判断，以及只供旧路径使用的 `mergeExternalNoDelta(SparseDelta)` 和 `copyAsExternal()`。新 key 现在只建立一条 external envelope，直接作为综合 g 并 merge 本地 label；每个传播节点无条件只执行一次 `g<-min(g,delta)`。

该清理没有改变第 196 节已经验证的 no-h 语句，只去掉不可达的旧分支和对象字段。focused 编译通过；SegmentPool 关闭和开启时各 96,000 次随机、拓扑、partial eager/lazy 对拍均通过，两组最终 active 均为 `164/143`。当前正式图中已不存在持久化 h、相关 merge 方法或运行期开关。剩余传播 delta 是 g 的真实下降区间，不能与已删除的 h 混淆，也不应改成传播完整 g。

198. 2026-07-11 no-h 删除重连传播边界复核

再次检查 no-h 后，生产算法未发现新问题，但原测试把菱形传播和 node 删除/重建分成了两个用例，没有直接覆盖最敏感的组合拓扑。本轮新增定向序列：先建立 superset 根、两个不可比中间 node 和共同 subset successor；随后让根包络下降，连续清除中间和底部本地 sources；再重建不同中间分支与底部 node；最后让旧根再次下降，验证重连后的新 successors 全部收到传播。forward/backward 均逐次与 Paper graph、历史 label brute-force 包络和 source invariant 对拍。

该组合用例在 SegmentPool 关闭和开启时均通过；连同原随机测试，每种池化口径仍完成 96,000 次随机插入，最终 active 均为 `164/143`。生产代码只修正了三处已经过时的 h 注释，没有修改计算路径。复核后确认：新 node 的 predecessor 聚合只产生 external source；local source 只由本地 label merge 引入；node 归零时 g 已纯 external；删除重连后未来 delta 可沿新的 Hasse 边继续传播。当前未发现其他有实际影响的高频冗余；新 key 的 external 聚合仍复用统一 merge 并创建小 outcome，但该路径低频且统一交点/tie 语义的价值高于拆分收益。

199. 2026-07-11 no-h 正确性与高效性最终复核

本轮进一步复核了传播 worklist 的单次入队规则。一次 label 插入只产生同一候选函数 F 的下降区间；若 F 在共同 successor `v` 的时刻 t 能降低旧 `g_v`，则 `F(t)<g_v(t)`。由于旧 `g_v` 已包含所有直接 predecessor 包络的 external minimum，有 `g_v(t)<=g_p(t)`，因此 F 在该时刻也必然降低每个能够把它传到 v 的 predecessor。也就是说 v 真正可能下降的区间包含在任意第一条到达父路径的 delta 中；同轮后续父路径不会补充新的下降区间。删除持久 h 不改变该不等式，当前“一轮 node 最多入队一次”仍然正确，不需要改成重复入队或维护 pending-delta 并集。

再次检查正式热路径后，已不存在 h 字段、h merge、回退开关或相关空判断。每个传播 node 只做一次 source-aware `g<-min(g,delta)`；空 delta 立即停止；dominated label 在 partial prepare 前跳过；partial retained intervals 只保存最新版并在出队/join 前消费；dead node 立即移出 active Hasse 集合。剩余 same-key 只读预检查面向大量 rejected labels，new-key external 聚合与 Hasse 搜索低频，统一 merge outcome 保证交点和 tie 语义，均没有明确的净收益证据支持继续拆分。O(1) 统计计数仍保留实验价值，完整 segment/timing 扫描只在显式诊断开关下执行。

验证使用实际 classpath 完成 `javac -Xlint:all`，没有源码 warning；诊断计时开启时，SegmentPool 关闭和开启各 96,000 次随机/拓扑/partial 对拍继续通过，active 均为 `164/143`，incremental performance smoke 分别约为 `12.501ms/11.766ms`。该微基准只用于确认没有新的高频退化，不作为完整求解加速比例。

200. 2026-07-11 source-aware 大幅提速的具体来源

旧 Paper 图和新图在数值上维护的综合包络本质相同，都是 `g=min(f,h)`；reachable-set key、superset/subset Hasse 关系和跨 node 可比条件也没有加强。大幅提速来自新图能够把综合包络的每段反向映射到具体 LOCAL label source，并在某条 label 的最后一个贡献区间消失时立即删除它。旧 normal Paper 图在同 key 接受新 label 后只更新 `labelEnvelope` 和 `dominanceEnvelope`，不会反向清理原有同-key labels；因此大量曾经贡献过、后来已完全退出集体下包络的历史 labels 仍会扩展和 join。这是最主要的差异。

对子 node 的数学占优关系没有新增，但物理清理也不完全相同。旧传播只用 predecessor envelope h 逐条检查本地 label，只有 h 单独完整支配该 label 时才删除；新图把 external predecessor delta 直接 merge 到综合 g，并根据 g 的 LOCAL source 是否归零清理 label。因此存在“h 单独不支配、某个同-key label 单独也不支配，但 `min(h,其他本地 labels)` 集体支配”的情况，旧 normal 会保留，新图会删除。换言之，跨 key 的可比规则未变，但 successor 内结合 predecessor 与同-key labels 的集体清理更强且不需要逐 label 扫描。

所以 40-2 的 `root 55.921s->18.487s`、`exact 42.387s->5.251s` 和 join pairs `5.79m->0.20m` 不能解释为 g 的下界变强；最终 root bound 仍相同。它来自更准确地维护“哪些 labels 仍实际贡献 g”，使失效 labels 不再扩展、生成 children 或参与 join，并形成乘数级工作量下降。

201. 2026-07-11 W300 历史结果版本口径修正

此前引用的 W300/50-3 `solve 211.187s->180.760s`、`exact 171.098s->138.478s` 不是当前完整 source-aware 图的结果。`180.760s` 对应提交 `b456e49c` 和第 178 节阶段：该版本已经使用增量 g/sparse-delta 传播，避免全 predecessor 重建，但第一版 source 归零物理清理由于当时的 bound 分歧已经撤回。传播到 child node 后仍调用 `removeLabelsDominatedByPredecessors()`，扫描该 node 的全部历史 labels，并且只在 predecessor h 单独完整支配某条 label 时删除；同-key 新 label 也不会反向清理已经退出集体下包络的旧 labels。

完整 source-aware source 归零清理在后续提交 `0d7502c7` 和第 181 节才正式接入，随后又继续删除 dead-node 历史扫描、实现 partial 惰性裁剪并最终移除持久 h。当前完整版本只做过 40-2 的严格 Paper/new A/B，尚未重新完成 W300 端到端对比；第 1861 行原记录也已明确这一点。因此 W300 的 14.4%/19.1% 只能说明“早期增量传播版本”相对旧 Paper 有一定收益，不能用于评价当前 source-aware 清理的幅度。

第一版未修正版本曾在 W300 上出现约 `211s->113s`，但同时 root bound 从 `1726.014329` 变为 `1726.256114`，后来定位到 active sequence 成本未统一回刷等口径问题。该时间不能作为有效性能结果。当前版本理论上会通过 source 归零减少 W300 labels、扩展和 join，但实际幅度必须重新跑 current-vs-Paper 同配置 A/B 后才能下结论。

202. 2026-07-12 主线端到端冗余与重耗时复核

当前 ng-DSSR 内部的 source-aware dominance、completion bound、Tmid 冻结和 join 预过滤已经基本完成一轮优化，但端到端求解仍有几块明显成本不在 exact labeling 内。以最新 `wet050_003_3m_setupR50 + W300` root-only 日志为例，总时间 `76.931s`，其中 ng-DSSR exact 为 `31.696s/10`，初始 ALNS 单独隔离后约 `28.3--29.1s`，time-indexed root preprocessing 为 `8.099s`，普通启发式 pricing 为 `6.830s/31`，正式 root 的 master LP 约 `2.628s`。因此该算例剩余最大项首先是初始 ALNS，其次才是已经优化过的 exact pricing；不能再把约 28 秒未归因时间算到 ng-DSSR 初始化或 dominance 上。

1. 当前完整树上最值得优先控制的是 two-stage strong branching。默认 `strongBranchingCandidateLimit=20`，意味着每次分支最多做 40 个左右 child phase-1 trial；随后前 4 个候选的左右 child 进入 phase 2。`strongBranchingPhase2MaxHeuristicPasses=0` 的实际语义不是关闭，而是每个 trial 一直运行到允许的 heuristic pricing 全部无列。历史 40-2 日志中，phase-1 trial LP 为 `63.750s/280`，phase-2 strong heuristic 为 `36.270s/543`；当前较好配置下仍有 `HeuristicPricing[strongBranching]=34.908s/842`。50-3 对照中，phase2=4 为 `381.350s/10 nodes`，关闭 phase 2 为 `352.319s/14 nodes`，说明 phase 2 确实改善分支质量，但节省的节点没有抵消重复 trial heuristic。最小风险的下一步不是改分支语义，而是对 `phase2CandidateLimit` 和正数 `MaxHeuristicPasses=1/2` 做完整求解 A/B。更进一步可做 reliability/pseudocost branching：仅在某条 arc 缺少可靠历史时执行完整 trial，避免每个节点固定测试 20 个候选。

2. 初始 ALNS 的主要成本不是 accepted/best history 入池。隔离测试中 history limit 为 2000、0 或 best 的总时间分别约 `29.07s/28.77s/28.30s`，差异不足 1 秒。JFR 显示真正热点是 `PiecewiseLinearFunction.add` 约 `40.6%`、`Solution.merge3Segments` 约 `8.9%`、`CrossExchangeOperator.evalDelta` 约 `7.8%`、`copy/shiftX` 约 `14.7%`，分段对象获取占主要分配压力。`InitialColumnBuilder` 确实会在 Pool 去重前评估历史重复 sequence，这是明确但次要的局部冗余。ALNS `noImprove=20/40/60/80` 的隔离时间约为 `9.0/16.4/22.9/29.1s`，对应 incumbent `2002/2002/1918/1902`，说明直接缩短 ALNS 会损失上界并影响后续 fixing，必须比较完整 BPC，而不能只看 seed 时间。若继续优化，应针对 ALNS 专用的 PWLF 拼接和 cross-exchange 候选评价做融合操作，不应贸然改通用 `PWLF.add()`。

3. time-indexed root preprocessing 不应无条件视为“好配置”。W300 本轮耗时 `8.099s`，包含 132 次 time-indexed pricing/LP 迭代，最终只把 200 条 elementary seed 交给正式 root；它的主要价值是 216 条普通 pricing-only arc、约 454 万条时空禁弧和 compact window，而不是复用临时 master。本项目已有 setupR 对照显示它在困难 R75 上明显有利，但在 R25/R50 上可能变慢。因此更合理的方向是按预计时空图规模、horizon 和历史收益决定是否预处理，而不是默认全开或全关。

4. 发现一处可以进一步验证的 completion-bound 重算。W300 最后一次 no-negative exact 在初始化中构造 completion bound 约 `598.5ms`；节点闭合后 subtree arc elimination 又重建同一 dual 下的 bounds 约 `685.6ms`，最终固定 0 条普通弧。原因已定位：ng-DSSR 只有在 `pricingHorizon == data.CmaxH` 时才导出 reusable bounds；root preprocessing 的 compact window 把 horizon 缩到 1230，而全局 `CmaxH` 为 2230，所以即使 dual window 已关闭，结果仍被判为不可复用。该 guard 对 dual profitable window 必须保留，但对已经证明安全、可继承的 compact window 可能过于保守。后续可给 prepared bounds 增加 effective-window/horizon 口径并做逐函数、逐 arc A/B；若证明安全，可省去每个闭合节点一次 completion-bound rebuild，并且复用的 bound 还保留 compact window 强度。当前不能直接删除 guard，因为 subtree scanner 现按 `data.CmaxH` 判兼容。

5. RMIH、普通 heuristic pricing 和 master re-solve 不是统一意义上的冗余。RMIH 当前在每个 fractional ng-DSSR node 调用，普通规模最多 4 秒、大规模最多 20 秒；已有日志存在多次 infeasible/不改进，因此可以考虑“root 必跑，连续失败后降频，只在 pool 明显增长或 incumbent 长时间未更新时再跑”的调度策略。普通 heuristic pricing 在 W300 为 `6.830s/31`，但历史上关闭后会让 exact 和列池显著膨胀，不能直接去掉。PC 在正常 column generation 中只在初始时 `solveRelaxation()` 建模，后续加列使用 `resolveCurrentModel()`，不存在每轮 pricing 都重建 CPLEX 模型的问题；真正重复建模集中在 strong branching 的独立 trial，这是评分成本而不是普通 PC 冗余。

当前优先顺序为：先做 strong branching phase-2 限轮/候选数完整 A/B；其次验证 compact-window completion bound 能否安全复用于 node 后处理；再评估 ALNS 专用 PWLF/CrossExchange 优化和 RMIH 自适应调用。initial history 去重、每个 pricing engine 构造 active-id HashSet、诊断 summary 等属于小项，现有数据不支持优先修改。route enumeration 默认关闭，也不构成当前主线耗时。

进一步按代码调用链澄清 completion-bound 重建口径。pricing 内部的 `evaluateCompletionBoundArcFixing()` 直接使用本轮已经构造的 completion bounds，以 0 为 cutoff 做本轮局部剪枝，本身不会额外重建。真正的第二次构造发生在节点闭合后的 subtree fixing：ng-DSSR 只有在无 dual profitable window、无 zero-dual 排除且 `pricingHorizon == data.CmaxH` 时才导出 reusable bounds。因此不仅 dual window 会阻止复用，纯 compact window 只要缩短 horizon 也会阻止复用；W300 的 `1230 < 2230` 正是此次重建原因。time-indexed 永久 arc fixing 已经单独使用 `computeSafeFixingGraphWindow(... useCompact=true, useDual=false)`，所以它会保留 compact window、排除 dual window。当前只记录 ng-DSSR prepared-bound 复用条件可能过宽，暂不修改其安全边界。

按本轮决定，将 `enableRestrictedMasterIntegerHeuristic` 全局默认值改为 `false`。常用 runner 仍读取该默认值并允许通过 `twet.bpc.fullDomainCompare.enableRestrictedMasterIntegerHeuristic=true` 显式开启，因此只改变未传参时的默认配置，不删除 RMIH 实现或实验入口。

继续追溯 `pricingHorizon == data.CmaxH` 的历史后确认，这不是针对 compact window 得出的安全性结论，而是 compact window 出现前留下的旧 guard。该条件来自 2026-06-09 的提交 `4a4d5318`。当时 `precomputeEffectivePricingWindows()` 在未启用 dual profitable window 时直接把 `pricingHorizon` 固定为 `data.CmaxH`，只有 root/no-cut 的 dual profitable window 才会产生局部 horizon；因此 `dualProfitableWindowEnabled`、`zeroDualExcludedJobs != null` 和 `pricingHorizon != data.CmaxH` 三个判断本质上共同防止 pi-window/临时裁剪后的 bounds 流入 subtree fixing 和 full-domain route enumeration。2026-06-24 的枚举记录也明确把它描述为防“半域或临时裁剪窗口”，当时还没有 node compact window。

compact window 在 2026-06-29 的提交 `4820ff93` 才接入 ng-DSSR。该提交把 node 闭合后、基于 `UB-LB` 固定时空弧后剩余的 job 可达时间 hull 写入 node，并明确作为“可继承硬窗口”供子节点 pricing 使用；但没有同步修改 6 月 9 日的 reusable-bound guard。因此当前只要 compact window 将 horizon 缩短，即使 dual window 关闭，也会被旧条件一并拒绝。这是历史条件过宽，不是 compact window 不能用于 subtree arc fixing。

正确修改不能简单无条件删除 horizon 判断，因为本轮 `timeIndexedCompletionBoundInRoundArcFixing=true` 时还可能先按 0 cutoff 固定局部时空弧，再由该局部图收紧 effective window；这种窗口只属于当前 pricing，不能用于 `UB-LB` 永久 fixing。安全复用条件应改为显式记录窗口来源：无 dual profitable window、无 zero-dual 排除、没有基于 0 的 in-round 临时 fixing；此时由基础 hard window、node 继承 compact window，以及只基于持久 branch/pricing-only 图的可达性收紧得到的 completion bounds，可以按其 compact horizon 直接用于 subtree arc fixing。`PreparedBounds` 的兼容检查也应接受该安全 compact horizon，而不是继续要求全局 `CmaxH`。这一修改还能让 subtree fixing 使用更强的 compact-window bound，而不是重建一套更宽的全局 hard-window bound。当前先完成分析记录，代码待单独实现和逐函数、逐 arc A/B。

203. 2026-07-12 compact horizon completion bound 正式复用

本次已按第 202 节的边界完成实现。当前 ng-DSSR 只有在 completion bound 不含 dual profitable window、zero-dual job 排除和本轮 `0` cutoff time-indexed 临时 arc fixing 时，才把它标记为可供 subtree 使用；由基础 hard window、node 继承 compact window和持久 branch/pricing-only 图得到的紧 horizon 不再因为 `pricingHorizon < data.CmaxH` 被拒绝。`PreparedBounds` 增加显式的窄 horizon 安全标志，旧 pricing 类仍沿用原先的全 `CmaxH` 兼容规则，没有被一并放宽。

验证分三层。首先 focused `javac` 编译通过；新增兼容性测试覆盖旧调用拒绝紧 horizon、显式安全紧 horizon 允许复用，以及 relaxation、queue ordering 和 horizon 超界仍拒绝。随后 30-job smoke 得到 `obj=bound=14318`、`valid=true`，主线未回归。最后复跑此前明确发生重建的 W300/50-3 root-only 口径：结果仍为 `incumbent=1902`、`bound=1726.014329`、`valid=true`；节点闭合后的 `SubtreeArcElim` 从历史 `bounds=rebuilt, buildMs=685.552, total=701.476ms` 改为 `bounds=reused, buildMs=0.000, total=20.288ms`。两次 run 的 ALNS/root preprocessing 随机轨迹使候选普通弧数量有 4 条差异，但最终 root bound 完全一致。当前结论是原 guard 确属历史性过度保守，紧 compact bound 可以直接复用；dual、zero-dual 和 in-round 临时 fixing 的隔离仍保留。

204. 2026-07-12 compact bound 复用实现后正确性复核

本次沿完整调用链复核了 prepared bound 的产生、保存和消费。ng-DSSR 每次 `solve()` 都会重新清空 DSSR 内 completion-bound/window 缓存；pricing engine 每次 `price()` 和 `reset()` 也会清空上次导出的 prepared bound。PC 只在某个 exact engine 返回 0 列时接收该对象，加列、加入 cut 或开始新 node 求解都会清空。因此 compact bound 不会跨 dual、cut 集合或 node 错用。normal、graph-partial 和 list-partial 三条 ng-DSSR engine 都经过同一个 `reusableSubtreeArcEliminationBounds()`，边界一致；旧 full-domain 类仍使用四参数构造器，只接受全 `CmaxH`。

窗口来源也重新核对。`precomputeEffectivePricingWindows()` 先交基础 hard window、node 继承 compact window和可选 dual window，随后普通 `tightenWindows()` 只根据当前持久 branch/pricing-only 图的前后向可达性继续收紧；这些结果对当前子树有效。dual profitable window 和 zero-dual 排除由导出条件明确拒绝；`timeIndexedCompletionBoundInRoundArcFixing=true` 时也整体拒绝，避免把当前 pricing 基于 0 cutoff 的临时时空弧固定写成 `UB-LB` 永久证据。active SRI 下 completion bound 仍采用既有 no-SRI 松弛口径，忽略非负 SRI penalty 只会使下界偏弱；本次没有改变这一边界。

消费端有两处。`CompletionBoundSubtreeArcEliminator` 用同一 node、同一 LP dual 和 `UB-LB` gap 扫描普通弧；紧窗口排除的是已经由父节点或当前节点 fixing 证明不可能属于更优解的完成时间，因此可以用更强的 compact bound。route enumeration 默认关闭；显式开启时 prepared suffix 也只用于 gap 内列剪枝，而 compact window 的证明口径正是当前子树中优于 incumbent 的解不能使用窗外完成时间，所以不要求 enumeration 自身必须同时打开窗口化 job penalty。

补充的 40-2 root-only 审查 run 实际覆盖了非零固定：root preprocessing 后 `pricingHorizon=1521 < CmaxH=2132`，最终 `SubtreeArcElim candidates/fixed=326/5`、`bounds=reused`、`buildMs=0`，求解结果为 `incumbent=22582`、`root bound=22490`、`valid=true`。`22490` 与此前多组使用旧重建口径的 40-2 root 日志一致。结合 W300 的完全相同 root bound、专项兼容性测试和 focused 编译，当前未发现误固定、错误复用或求解结果回归。
208. 2026-07-12 DSSR 轮次分布与同节点 warm-start 实验

对 W300/50-3 setupR50 完整树中已记录的 407 次 ng-DSSR exact pricing 重新统计。DSSR 轮次分位数为 P25=1、P50=1、P75=3、P90=13、P95=17、P99=22，最大 24。1 轮调用占 60.0%，但只占 exact 时间 24.4%；15 轮及以上只占调用数 8.4%，却占 exact 时间 48.2%。其中 20 轮及以上仅 2.7% 的调用便占 23.6% 时间。慢尾不仅来自轮数增加，单轮本身也更重：多数 2--14 轮调用约为 0.47--1.0 秒/轮，而 17--24 轮调用约为 1.27--2.68 秒/轮。原因是困难 dual 状态不仅需要更多 DSSR 更新，后期扩大后的 ng-set 还会增加每轮 label、扩展和 join 工作量。因此 warm-start 的评价指标必须同时看总轮数和 exact 总时间，不能只看是否减少迭代。

基于上述结果新增默认关闭的同节点最近 final ng-set warm-start。状态只保存一个 `(nodeId, activeCutIds, finalNgSets)`，node 或 active-cut 集变化即不命中；repair 和 diagnostic cross-check 不记录。首次 exact 仍使用基础初始化，后续同 node、同 cut 的 exact 直接复制上一次 final ng-set，并与当前 repeatability admissibility 取交集。该设计不使用全局无限历史，不跨 node 污染，也避免了原 history warm-start 与 repeatability filter 互斥的问题。常用初始化恢复为 `dualPair coefficient=0.08`、每轮只用最佳非基本列更新，即 50-job 初始只选 `floor(50*0.08)=4` 个全局 pair，不再使用实验性的 nearestK3/top3。

W300 root-only A/B 中，baseline 使用 `dualPair/top1` 且关闭同节点 warm-start，在 900 秒预算内仍未完成 root；已完成的 32 次 exact 共 149.391 秒、累计 175 个 DSSR round，P50=2、P75=4、P90=12、P95=25、最大 43。warm 组只打开同节点 warm-start，31 次 exact 中后 30 次命中，224.755 秒完成 root，得到 `incumbent=1902`、`bound=1726.014329`、`valid=true`；exact 共 91.716 秒、累计 73 round，P50=1、P75=2、P90=2、P95=14、最大 18。频率为 1 轮 22 次、2 轮 6 次、7/14/18 轮各 1 次。该结果坐实同 node 后续 exact 的 final ng-set 具有直接复用价值：它显著压低 20--43 轮长尾，同时没有改变已知 root bound。开关暂时保持默认关闭，待完整树和含 cut 场景继续验证后再决定是否作为主线默认。

上述 A/B 随后发现配置入口写错：实验命令使用了不存在的 `timeIndexedRootPreprocessing=true`，正确属性应为 `timeIndexedRootPreprocessingForNgDssr=true`。两组日志都没有 `timeIndexedRootPreprocess.done`，因此该结果只能说明无预处理时 warm 优于冷启动，不能与 76.931 秒的 `nearestK3/top3` 主线比较，也不能据此评价 `dualPair/top1` 的默认价值。保留数字用于追溯，但撤销“公平主线 A/B”的解释。

修正属性并打开 `ngDssrSetStats/ngDssrSetMembers` 后重跑公平 warm 组。预处理实际生成 216 条普通 pricing-only arc、4,537,954 条时空禁弧和 200 条 elementary seed，平均 compact window 长度 738.960，正式 pricing horizon 为 1230。结果仍为 `incumbent=1902, bound=1726.014329, valid=true`，root 总时间 116.536 秒，exact 63.359 秒/24 次、累计 66 个 DSSR round；普通 heuristic pricing 13.637 秒/62 次。相比 `nearestK3/top3` 的 76.931 秒、exact 31.696 秒/10 次、heuristic 6.830 秒/31 次，`dualPair/top1+warm` 仍明显较慢。

ng-set 轨迹解释了差异。warm 前 13 次 exact 的 final ng-set 平均一直只有 0.28/job、最大 4，随后依次升至 0.52、0.72、1.50、2.22、2.96、3.88、4.42，最终 no-negative 证书轮达到平均 6.44/job、最小 0、最大 14。24 次调用的 final 平均 size 再平均为 1.374，中位数只有 0.28。也就是说 warm 没有在早期把集合扩得过大，反而长期过小，导致每次只推进少量列并反复回到 heuristic/RMP/exact；最后困难证书轮才集中学习到较大的集合，并单独耗时 15.653 秒。当前结论应改为：同节点 warm 能减少重复学习，但不能弥补 `dualPair/top1` 过弱的基础初始化和更新；W300 主线仍应优先使用 `nearestK3/top3`，再单独测试在其上叠加 warm 是否有净收益。
209. 2026-07-12 同 node 空集冷启动下的 final ng-set 相似性

为避免完整继承造成 ng-set 单调膨胀，重新使用历史诊断 `tmp-ngdssr-40-2-r50-empty1-top5-members-20260702` 分析“每次 exact 都从 empty ng-set 开始”的纯统计口径。该日志覆盖 setupR50 的 9 个 node、81 次 exact，并记录每次 final ng-set 的完整成员；统计时移除了旧实现中每个 job 固定包含自身的成员。72 对同 node 相邻 exact 的 final set Jaccard 平均为 0.670，P25=0.481、中位数 0.735、P75=0.885。该相似性不是由 warm 导致，因为每次 exact 都重新从空集学习。

不同 node 的稳定性差异明显。node 7 有 11 次 exact，去除自身后的 final pair 数为 `24,19,25,26,23,23,23,24,21,24,25`，相邻 Jaccard 为 `0.79,0.76,0.96,0.81,0.84,0.84,0.96,0.88,0.88,0.88`；其中 `12->21,21->12,21->28,2->21,2->23,23->2,23->21,24->12,28->21,29->21,31->2,31->21` 等 pair 在 11/11 次出现。node 2 有 19 次 exact，早期 pair 数约 22--34，后期困难 dual 状态升至 79--99；相邻 Jaccard 从第一次跳变的 0.21 上升到多次 0.89--1.00，`12->1,12->39,1->39,2->33,23->33,33->2,39->1` 等 pair 为 19/19。node 8 更不稳定，10 次 pair 数为 `34,0,10,35,28,44,18,29,54,78`，相邻 Jaccard 包含 `0,0,0.29`，但后半段仍上升到 `0.62,0.54,0.67`；说明不能在只有一次样本时完整继承，也不能假设所有 node 都同样稳定。

成员级观察同样显示稳定核心。例如 node 1 的 job 1 在 8 次中通常为 `{12,39}`，job 12 通常为 `{1,21,39}`，job 21 通常为 `{12,28}`，job 24 通常为 `{1,12}`，job 39 通常为 `{1,12}`；只有个别 pricing 引入 13、24、27、30 等临时成员。node 7 更稳定：job 2 基本恒为 `{21,23}`，job 21 恒为 `{12,28}`，job 24 恒为 `{12}`，job 33 恒为 `{2,21,23}`。因此同 node 历史的主要价值是识别高频核心成员，而不是继承完整 final set。

当前更合理的候选策略为有界频率 warm-start。每个 node 独立维护最近若干次 final ng-set 的 pair 出现次数；第一次仍按原基础策略初始化，不跨 node 使用历史。样本不足时不学习；至少积累 2--3 次后，只选择频率严格高于 0.5 的成员，并按频率、当前 dual pair score 排序。每个 job 的初始成员数必须有上限，优先使用基础 `nearestK` 的 K，而不是历史 final size；候选不足时用基础 nearest/pair 初始化补齐，候选过多时只取前 K。这样利用 node 7/2 的稳定核心，同时不会把 node 8 的偶然大集合或后期 99 个 pair 原样带入下一次。下一步若实现，应先做离线逐次回放，比较 seed 对下一次 final set 的 precision/recall、DSSR 轮数和单轮 label 数，再决定频率阈值与最小样本数。
210. 2026-07-12 50-3 W300 空集冷启动的同 node 频率复核

按要求不再沿用 40-2，重新在 `wet050_003_3m_setupR50 + W300` 当前代码上运行诊断。配置为每个 node、每次 ng-DSSR exact 都从 empty ng-set 开始，关闭 same-node/global history warm-start，保留 top3 非基本列更新，打开完整 `ngDssrSetStats/ngDssrSetMembers`；time-indexed root preprocessing、compact window、source-aware dominance、completion bound、Tmid 复用和 join prefilter 均正常开启。获取 node 1--3 共 53 次 exact 后主动停止，避免用 empty 模式继续消耗完整树资源。

当前 50-3 的结构与旧 40-2 明显不同。node 1 有 27 次 exact，去除自身后的 final directed-pair 数为 `6,6,0,0,0,6,0,6,6,6,0,6,18,18,18,28,6,28,38,6,47,28,70,6,94,176,281`，相邻 Jaccard 平均 0.450、中位数 0.400；node 2 的 10 次为 `0,0,6,6,0,6,0,33,39,219`，平均 0.336；node 3 的 16 次为 `6,6,6,6,6,6,8,6,6,44,46,6,54,42,64,200`，平均 0.706、中位数 0.778。多数早中期 exact 从空集即可直接返回 elementary 负列，或只经 top3 route 更新形成 6 个 directed pair；接近节点闭合时才出现几十到数百 pair 的困难证书轮。完整继承会把 `176/281/219/200` 这类尾部集合带入下一次，显然不可接受；全历史无界频率也会逐渐受尾部大集合污染。

对 53 次记录做了逐次离线回放。使用最近 2 次 final set、频率严格大于 0.5、每 job 最多 3 个成员时，预测下一次 final set 的初始 directed-pair 平均为 9.09，平均逐调用 precision 0.871、recall 0.480；最近 3 次时初始平均 14.28，precision 0.820、recall 0.633；最近 5 次时初始平均 11.21，precision 0.866、recall 0.565；全历史时初始平均仅 4.47、precision 0.936、recall 0.480。最近 3 次在覆盖率和规模之间最好，但这些 recall 会受最后一次 200--281 pair 证书集合拉低；warm 的目标不是复现完整 final set，而是提前放入少量稳定 pair，减少前几轮重复学习。

据此建议下一版采用“短窗口、有界、困难触发”的同 node warm-start，而不是完整继承。每个 node 第一次 exact 仍使用现有基础策略；之后只维护最近 3 次 final set。候选成员必须至少在最近 3 次中的 2 次出现，并与当前 repeatability admissibility 取交集；每 job 最多保留 2--3 个，同时再设全局 directed-pair 预算，例如 15--25，按出现次数、最近出现时间和当前 dual pair score排序。若最近 exact 均为 1 轮，继续使用基础小集合或空集，不启用历史；只有最近一次达到例如 3 轮以上，或最近 3 次累计 DSSR round 超过阈值，才启用 bounded history seed。这样早期大量 0/6 pair 状态不会被无谓放大，接近闭合时又能提前复用稳定核心；无论运行多久，初始 ng-set 都受 per-job 和全局预算双重限制，绝不会单调增长。

建议先做三组当前 50-3 root A/B：`nearestK3/top3` 基线；最近 3 次、2/3 频率、per-job cap2/global cap15；同样策略 cap3/global cap25。比较 exact calls、DSSR rounds、每轮 label/join 和总 exact 时间。若 cap15 已明显减少 tail 且不增加单轮成本，再考虑作为默认；否则保留 nearestK3，不应仅凭 Jaccard 把 warm 接入主线。
211. 2026-07-12 nearestK3 上的固定预算同 node warm-start A/B

根据第 210 节的 50-3 empty 诊断，先实现了“最近 3 次、频率大于 0.5、固定大小替换 nearestK3”的版本。该版本每个 node 第一次 exact 与 baseline 完全一致，后续只在最近一次达到 3 个 DSSR round 时触发；每 job 和全局均有限制，且基础 cardinality 不增加。公平 root 和 3-node 对拍显示它几乎没有改变实际 seed：node 1 两次触发均为 `selected25/replaced0`，node 3 三次只替换 `1/1/5` 个，所有 exact 的 rounds、added columns 和 labels 与 baseline 基本逐项一致。3-node 总时间 `212.866s -> 218.008s`，exact `151.643s -> 156.116s`，无收益。原因不是预算，而是历史高频核心本来就已属于 nearestK3；用它替换 nearest 成员没有增加 DSSR 所缺的信息。

随后改为“保留完整 nearestK3，再从最近一次困难 exact 的 final set 固定追加少量成员”。候选必须出现在最近一次 final set 中，并按最近 3 次出现次数排序；最近一次不足 3 round 不触发。每次 exact 都从头构造 `nearestK3 + bounded extras`，不会继承累计集合；per-job cap 和 global cap 构成硬上限，因此调用次数增加时 seed 也不会增长。node 或 active-cut 集变化会清空窗口，repair/cross-check 不记录。

正确 repeatability 配置下的 fresh baseline root 为 `solve=88.835s, exact=37.691s/10`。固定追加 15 个 directed pair 后为 `solve=85.345s, exact=35.066s/10`，两次触发均实际添加 15 个，最后证书轮从 14 round 降到 13。3-node baseline 为 `solve=212.866s, exact=151.643s/27`；cap15 为 `200.823s, exact=140.777s/27`。其中 node 3 的累计 round 从 63 降到 58，序列由 `1,1,1,1,1,1,2,2,8,9,10,2,24` 变为 `1,1,1,1,1,1,2,2,8,5,9,2,24`，labels 从 348,163 降到 344,856。说明固定少量追加能够减少中后期重复 DSSR 学习，但最终 24-round certificate 尚未改善。

cap25 的 3-node结果为 `solve=179.689s, exact=128.061s/27`，表面时间最好；node 1 最后证书轮为 12，node 3 累计 round 为 61。但其 node 3 labels 为 348,297，接近 baseline，结构性改善反而弱于 cap15，因此约 21 秒额外时间优势包含明显运行波动，不能据一次结果认定 cap25 稳定优于 cap15。当前实现保留 `window/perJob/global/triggerRounds` 实验参数并继续默认关闭。后续完整树优先比较 cap15 与 cap25 的重复运行；若只按稳定工作量指标，cap15 是更保守的候选。

212. 2026-07-13 W300 完整树 DSSR 长尾与 ng-set 增长复核

继续分析 `tmp-w300-ng-best-full-rerun-20260712` 中已经完成的 407 次 ng-DSSR exact。配置为 `nearestK3/top3`、repeatability 初始过滤、time-indexed root preprocessing、source-aware dominance、completion bound、Tmid 复用和 join envelope prefilter；history 与 same-node warm-start 均关闭。407 次 exact 累计执行 1,532 个 DSSR round，exact 总时间 1,679.458 秒，累计加入 18,280 个 directed ng pair。357 次“返回 elementary 负列”的调用耗时 872.113 秒、平均 2.06 round；50 次“relaxed pricing 无负路径”的闭合调用耗时 807.345 秒、平均 15.9 round。也就是说，仅 12.3% 的 no-negative 证明调用消耗了 48.1% 的 exact 时间，困难尾部主要是闭合证明，不是正常加列轮。

按轮数分组后，1 轮调用 244 次、409.792 秒；2--3 轮 68 次、144.334 秒；4--9 轮 34 次、120.625 秒；10--14 轮 27 次、194.877 秒；15--19 轮 23 次、413.876 秒；20 轮及以上仅 11 次，却耗时 395.953 秒。最重的 node 3 调用执行 24 轮、更新 332 个 pair、耗时 90.029 秒，其中 forward/backward/join 分别为 44.166/27.114/16.946 秒；node 47 的 23 轮调用为 44.385 秒，node 7/10/21 的 21--24 轮调用均约 38--39 秒。这些 no-negative 调用中 initialization 只占约 5.1%，forward/backward/join 合计约 94.7%；因此最后闭合慢的核心已经从 completion bound 和 Tmid 转为扩大 ng-set 后的完整前后向状态穷尽和 join 证明。

本 run 初始 `nearestK3` 使每个 job 从 3 个成员开始。困难调用累计更新 245--382 个 pair，因此最终平均 ng-set 大约升至 7.9--10.64；当前长日志未打开逐轮成员统计，不能直接给出每个调用的精确最大值，但总量已经保证最重调用的最大值至少为 11。相同实例的 root 逐轮统计中，14-round 证书调用从 `avg/min/max=3/3/3` 增长到 `7.34/3/14`，说明实际最大值通常已到十几。随 ng-set 增长，单个 dominance node 的 active-label 平均数从约 21 降到约 6--7，但这是标签被拆入更多 memory/reachable key，而不是状态减少；20 轮以上调用最后一轮平均仍保留约 20,664 个 forward label 和 15,964 个 backward label，显著高于 1 轮调用的约 6,065/6,117。

当前更新逻辑每轮保留 reduced cost 最好的 3 条 non-elementary route。对某条重复段 `j ... k1 ... kr ... j`，要让 ng-memory 在第二次访问 `j` 时仍记住第一次 `j`，必须将 `j` 加入该段所有中间任务 `k1...kr` 的 ng-set；只加其中一个 pair 不能阻止该重复路径，因为记忆会在其他中间任务处丢失。因此一条 route 一轮产生十几个更新是 ng-route 语义本身，不是简单的重复更新。但是当前实现会处理一条 route 中发现的所有重复段；从排除该 route 的角度，只需选择其中一个重复段并补齐该段全部缺失 pair。后续若优化更新策略，安全方向是为每条 stored route 选择新增 pair 最少的一个重复段，或在最多 3 条 route 之间联合选择使新增 pair 并集最小的重复段组合；不能改成“每条 route 随便加一个 pair”。

同节点 bounded warm-start 仍有明显试验价值。按当前实现的触发口径，若上一同 node、同 active-cut exact 至少执行 3 轮，则后续 exact 可在 nearestK3 上追加历史成员。该完整 run 中有 63 次调用满足这一条件，合计耗时 741.327 秒、执行 624 round，其中除首轮外的重复学习为 561 round；这部分约占 exact 总时间 44%。因此优化优先级应为：先在当前 `nearestK3/top3` 完整树上对照 bounded warm cap15/cap25，而不是退回已经证明过弱的 `dualPair/top1`；随后再比较“每条 route 只选择一个最短重复段”的更新策略。评价必须同时记录 DSSR round、final avg/max ng-set、forward/backward labels 和 exact 时间，不能只看轮数，因为更大的初始集合可能减少 round，却增加每轮状态量。

213. 2026-07-13 ng-DSSR 更新策略的文献与旧 VRP 对照

Martinelli、Pecin 和 Poggi 的标准 ng-DSSR 描述与旧 VRP 实现一致。算法每轮从动态规划结果中选择 reduced cost 最小的单条路径 `R*`；若该路径不是目标 ng-route，则检查其中所有 forbidden cycles。对每个重复段 `H=(v,...,v)`，将重复任务 `v` 加入该段所有中间任务 `l∈C(H)` 的临时 ng-set。论文 Algorithm 2 的 `selectBestRoute` 和 `updateNGSets` 明确是单条最优路径，并在 `updateNGSets` 中遍历该路径的全部 forbidden cycles。论文在 elementarity 扩展中也再次说明：每轮识别 best solution 的所有 cycles，并把每个 cycle 的 repeated customer 加入该 cycle 内所有中间集合；它同时指出，将重复客户直接加入所有任务集合的更激进 Righini--Salani 口径容易使标签量失控，其实现中最大临时 ng-set 即使在 200-customer 实例上通常也不超过 20。来源为 2013 技术报告及 2014 EJOR 论文 `Efficient Elementary and Restricted Non-Elementary Route Pricing`，DOI `10.1016/j.ejor.2014.05.005`。

旧 VRP 的 `GCNGBB` 每轮只维护一个 `m_best_cycle`。forward、backward 和 join 中发现更低成本的 cyclic route 时覆盖该变量；本轮扩展结束后只调用一次 `UpdateNGSet()`。该方法用 `place[cid]` 查找每个任务的连续两次出现，并把重复任务 `cid` 加入二者之间所有任务的 ng-set，因此也是“单条最优非基本路径 + 该路径全部连续重复段”。旧代码没有保存 top-K 非基本路径，也没有在一轮内用多条 route 同时更新。其 `m_col_coef=0.08` 是初始预加 pair 的独立策略，不改变后续每轮只用一条 best cycle 的规则。

当前 TWET 主线与上述口径的差异只在 route 数量。`recordNonElementaryNegativeSequence()` 按 reduced cost 保存最多 `ngDssrNonElementaryRouteUpdateLimit` 条路径；W300 配置为 3。`updateNgNeighborhoodsFromNonElementaryRoutes()` 随后对这三条路径逐条执行与旧 VRP 相同的全部连续重复段更新。因此当前 `top3` 正确，但相当于把标准 best-route 更新并行放大到最多三条路径；它可能减少 DSSR round，也可能过快扩大 ng-set，使后续单轮状态数量上升。文献中也存在使用当前多个最优路径的应用型变体，例如有工作将参数设为 3，但这不是 Martinelli/Pecin 标准算法，也没有形成统一最优选择。

因此最应优先做的更新策略 A/B 不是新设计“每条 route 只选一个重复段”，而是先把当前 `nearestK3/top3` 与 `nearestK3/top1` 公平比较。`top1` 与论文及旧 VRP 完全对齐，代码已有参数，不需要修改算法。若 top1 round 增加但 exact 总时间下降，说明 W300 当前瓶颈确实来自 top3 过快膨胀；若 top1 更慢，再进一步测试 top2 或按当前 ng-set 大小自适应选择 route 数。只选择一条 route 中新增 pair 最少的单个重复段在正确性上可以排除该固定 route，但不是主参考算法，优先级应低于 top1/top3 直接对照。

214. 2026-07-13 多轮 DSSR 的逐轮时间与 reduced-cost 口径

现有 W300 长日志没有记录单个 DSSR round 的 wall time 或该轮最优 reduced cost 数值。`exactPhaseMs` 在一次 `solve()` 开头清零，随后跨所有 DSSR round 累加，因此 14/24-round 调用只能得到整次 exact 的累计 init/forward/backward/join 时间；`ngSetSize ... updateByRound` 只记录每轮 ng-set 平均/最小/最大、更新数、negative non-elementary seen/stored 和 elementary 返回数。不能把整次 exact 时间直接解释为最后一轮时间，也不能从 `acceptedBestRc=Infinity` 反推前面各轮的最优值。

但 reduced-cost 符号可以由控制流严格确定。每轮 `solveRelaxedRound()` 后，若找到 elementary 负列则立即返回；若没有 elementary 负列且 `nonElementaryNegativeRoutes` 为空，则以“relaxed pricing found no negative route”闭合；只有在至少存在一条 reduced cost 小于容差的 non-elementary route 时，才更新 ng-set 并进入下一轮。因此对 root 的 14-round 闭合调用，r1--r13 的 `neStored=3` 证明每轮至少有三条负 non-elementary route，r14 的 `neSeen=0/neStored=0/elem=0` 才第一次证明不存在负 relaxed route。node 3 的 24-round no-negative 调用同理：前 23 轮一定仍有负 non-elementary route，第 24 轮才闭合。由于每轮只增加 ng-memory 约束、dual 不变，松弛可行路径集合单调缩小，理论最小 reduced cost 单调不降，但现有日志没有保存其具体轨迹。

完整树按轮数组合并后的平均时间只能作为粗粒度参考：1 轮调用约 1.679 秒/round，2--3 轮为 0.937 秒/round，4--9 轮为 0.659 秒/round，10--14 轮为 0.605 秒/round，15--19 轮上升至 1.069 秒/round，20 轮以上为 1.636 秒/round。前期 1-round 调用较重是因为 column generation 早期 dual 下候选和标签很多；中间轮次可复用 completion bound/Tmid 后较轻；15 轮以后 ng-set 扩大导致状态拆分和标签增长，单轮成本再次上升。具体 root 14-round 闭合调用累计 12.999 秒，平均 0.929 秒/round；最重 node 3 的 24 轮累计 90.029 秒，平均 3.751 秒/round。最后一轮使用最大的 ng-set 且必须穷尽，但其单独耗时目前无法从日志中分离。

若要精确回答逐轮曲线，下一次诊断应在现有 `appendNgSetStatsForRound()` 中追加本轮 wall time、`lastRelaxedRoundBestReducedCost` 以及本轮 forward/backward/join 增量，而不是重新增加大段 debug 输出。这样可以直接判断 reduced cost 是平稳接近 0、少数轮跳变，还是长期保持强负值后突然闭合，并可验证 top1/top3 的轮数与单轮成本权衡。
215. 2026-07-13 24 轮 DSSR 调用的逐轮可追溯信息边界

进一步核对 W300 完整树日志中 node 3 的 24 轮 no-negative 调用。该次调用总耗时 90.029 秒，累计发现 4343 条负 reduced-cost 非基本路径候选，按每轮更新上限累计保留 67 条用于 DSSR 更新，累计新增 332 个 ng-memory pair，最终没有返回基本负列。由控制流可以确定第 1--23 轮每轮都至少存在一条负非基本路径，第 24 轮首次既无基本负列也无非基本负路径，从而完成无负列证明。

该 run 没有开启 `twet.bpc.ngDssrSetStats`，所以日志没有保存 24 轮各自的 `u/neSeen/neStored/elem`，无法从累计量反推出每一轮的新增数量。即使开启现有逐轮统计，也只会记录每轮更新 pair 数、负非基本候选 seen/stored 数以及 ng-set 的平均/最小/最大大小；`twet.bpc.ngDssrSetMembers` 只输出该次 exact 结束时的最终成员集合，并不记录每轮具体新增了哪些 `(job,member)` pair。当前同样没有保存逐轮 wall time 和逐轮最小 reduced cost。因此，若要分析 24 轮的精确增长轨迹，需要后续诊断 run 逐轮追加耗时、best reduced cost、具体新增 pair 以及现有 size/seen/stored 统计，不能把现有累计 summary 拆成伪逐轮数据。

216. 2026-07-13 现有 43 轮逐轮统计的含义与轨迹

另一个开启 `ngDssrSetStats` 的 W300 root 调用采用 `dualPairCoef0.08 + top1 + nodewarm-off`，共执行 43 轮并最终证明无负松弛路径。这里 `neSeen` 是 join 恢复出负 reduced-cost 非基本序列后、序列去重和 top-K 保留前的原始发现次数，同一 sequence 可能因不同 split 重复计数；它不是唯一负列数，也不是加入 Master 的列数。该配置每轮只保留 reduced cost 最好的 1 条非基本路径，所以第 1--42 轮均为 `neStored=1`，第 43 轮为 0。逐轮 `neSeen` 从 `16352` 快速降到 `637/490/366`，之后缓慢降到第 40--42 轮的 `3/2/1`，第 43 轮变为 0；平均 ng-set size 同时从 `0.28` 单调增长到 `6.78`，最大值从 4 增长到 13。累计新增 331 个 pair。该轨迹说明 top1 每轮只用一条 witness 更新，但一条 witness 可一次补入多个 pair；例如第 12 轮和第 41 轮分别只保留 1 条路径，却新增 24 和 11 个 pair。现有日志仍未记录这些 pair 的具体身份、逐轮 best reduced cost 或逐轮耗时。

217. 2026-07-13 相邻 DSSR 轮次最优 sequence 关系实验

新增默认关闭的 `twet.bpc.ngDssrRoundRouteRelation` 诊断。每轮对所有已观察到的负非基本 sequence 去重，同一 sequence 的不同 split 按现有 DSSR 数值容差保留最好 inferred reduced cost；同时单独记录本轮真正送入 ng-set 更新的 top1 witness、它在上一轮唯一负 sequence 集合中的 reduced-cost 名次、上一轮成本、本轮具体新增 pair。该结构完全独立于正式 top1 容器，开关关闭时不分配逐轮 map 或 pair 列表，不改变 pricing、排序和更新流程。

按历史 `dualPairCoef0.08 + top1 + nodewarm-off`、W300/50-3、root-only 配置重跑，得到 `bound=1726.014329`、`valid=true`，与历史口径一致；总时间 189.479 秒，33 次 exact 共 92.323 秒。最终 no-negative exact 共 43 轮、耗时 25.953 秒，前 42 轮均有负非基本 witness，第 43 轮无负路径。第 2 轮 top1 未在第 1 轮显式生成的 15305 条唯一负 sequence 中出现；第 3--42 轮的 40 个 top1 全部已在上一轮出现，因此除首轮外的可比较命中率为 `40/41=97.56%`。上一轮名次分布为：第 1 名 1 次、第 2 名 22 次、第 3 名 10 次、第 4 名 3 次、第 5 名 2 次、第 6/7 名各 1 次；前 3 名覆盖 `33/40=82.5%`，前 7 名覆盖全部命中。所有命中 sequence 的上一轮与本轮 reduced cost 差异均小于 `1e-7`。实际 top1 与事后观察最小值的最大差仅 `1.82e-12`，小于 `1e-6` 容差，少量 sequence 不同属于数值并列，不是 top1 选择错误。

该结果说明，相邻 DSSR 轮次并不是重新出现完全无关的最优路径。通常上一轮第 1 名被新增 ng-pair 排除后，第 2--3 名直接上升为下一轮最优，因此上一轮候选排序具有很强的预测价值。但第 2 轮仍出现了上一轮未显式生成的新 top1，说明不能只重放上一轮候选并据此给出无负列证书；可行的加速只能把上一轮前若干候选作为快速 warm candidates，先按新 ng-set 验证，未找到时仍回到完整 labeling。完整逐轮 sequence、名次和新增 pair 已写入 `test-results/bpc/tmp-w300-dualpair-top1-roundrelation2-20260713/final-no-negative-43-round-relation.csv`。

218. 2026-07-13 基本列名次与自适应 DSSR 更新分析

继续解析同一 W300 root 日志中 32 次以 `elementary negative columns returned` 结束的 exact 调用。每次调用的最终 DSSR 轮同时记录了实际返回基本列的 `acceptedBestRc` 和本轮最优负非基本 witness 的 `selectedRc`。32 次中基本列成为本轮所有已观察负路径最优者的次数为 `0`；32 次均仍存在 reduced cost 更低的非基本路径。两者 reduced-cost 差值约为 `2.73--234.55`。其中 23 次调用执行了多于一轮 DSSR，这 23 次也全部是非基本路径更负。该结果说明当前 exact 在发现可加入 RMP 的基本负列后立即返回，并不要求 relaxed 最优路径已经基本化；因此不能用“本轮出现基本列”判断 ng-set 已接近充分，也不应为了找到本轮 relaxed 最优基本列继续无意义地收紧 ng-set。

相比跨 exact 的 warm-start，更直接的更新优化应发生在同一 DSSR 轮已经生成的候选集合内。当前固定 top-K 会在更新前选定 K 条路径，其中后续路径可能已被前一条路径新增的 ng-pair 一并排除。更合理的低风险策略是按 reduced cost 排序扫描候选：先更新当前最优路径，在临时 ng-set overlay 上重新判断剩余候选；若原 top2/top3 已不再满足新的 ng-route 条件，则不再为它们增加 pair；若仍有强负候选可行，再选择当前最优存活者继续更新，直到达到 route 上限。这样简单轮次通常只更新一条，候选结构彼此独立的困难轮次才自动更新多条。第 217 节中下一轮 top1 有 82.5% 来自上一轮前 3 名，说明这种“更新最优存活候选”的方向比跨轮 warm 更直接，但仍需保留完整 labeling 作为最终无负列证书。

若目标是进一步控制 ng-set 大小，可以试验 pair-budget cycle cover。对一条存在多个重复段的路径，排除该固定路径只需要完整禁止其中一个重复段，即把该段的重复 job 加入所有中间 job 的 ng-set；不需要同时更新该路径的全部重复段。可以把标准 top1 更新全部重复段所需的新增 pair 数作为本轮预算，先为最优路径选择“新增 pair 少且能覆盖更多高排名候选”的一个重复段，再用剩余预算处理当前最优存活候选。该方案能保证本轮 pair 增长不超过标准 top1，同时可能排除多条高排名路径，但它改变了标准 DSSR 的更新轨迹，可能损失当前未显式生成路径上的泛化效果，应作为独立实验策略而不是直接替换主线。

将非基本 sequence 删除重复访问后重新评价，是另一类基本列修复启发式，不是 ng-set 更新本身。新增 pair 只会让原重复 sequence 在下一轮不可行，并不会自动把它变成删点后的基本 sequence；若要利用该思路，必须显式选择保留哪个重复 occurrence，生成删点 sequence，再用 `TWETColumnEvaluator` 计算真实成本和当前 reduced cost。若修复后仍为负，可立即把它作为基本列返回 RMP，从而跳过本次后续 DSSR 收紧；但它不能证明不存在其他负列，后续 dual 状态仍需 exact pricing。建议先做默认关闭的诊断：对每轮前 3--10 条非基本路径执行贪心删重复 repair，统计修复成功率、负列率、reduced-cost 损失和 evaluator 耗时，再决定是否接入。

下一步最有解释力的 A/B 为：固定 top1、固定 top3、更新后存活候选自适应 top3，以及 pair-budget cycle cover。每组同时记录 DSSR round、每轮新增 pair、最终 avg/max ng-set、label/join 数量、exact 时间和基本列 repair 命中率。只减少轮数但显著放大后续单轮状态量，不应视为改进。

219. 2026-07-13 同轮存活候选与删重复 repair 诊断

第 217 节所说的“提前处理上一轮 top2/top3”不是跨 exact 直接继承，也不是跳过下一轮 labeling。更准确的实现位置是当前 DSSR 轮内部：先保留例如前 10 条互异负非基本候选，选择当前最优存活路径并按现有规则全量更新它的所有重复段；更新写入临时 ng-set overlay 后，立即用新集合重新判断剩余候选是否仍满足 ng-route。已经被第一条路径新增 pair 连带排除的候选不再重复更新；仍存活的候选中再选择 reduced cost 最低者，继续执行同样的全量更新，直到达到 route 上限。该策略不缩小单条路径的更新集合，也不改变最终 exact certificate，只把固定 top3 改成最多选择 3 条“更新发生后仍存活”的路径。82.5% 的含义是下一轮最优路径经常已经位于上一轮前 3 名，因此提前检查这些高排名候选有机会在一轮内处理多个独立障碍；但若 top1 已经同时排除 top2/top3，就应避免为它们继续扩大 ng-set。正式修改前仍应先统计 top1 更新后前 10 名失效数量、首个存活名次和自适应选择后的 pair 增量。

本轮先按要求只实现默认关闭的删重复 repair 诊断，不修改小 ng-set 或 DSSR 更新策略。每轮保存 reduced cost 最低的 10 条互异负非基本 sequence；对每条路径逐步删除重复 job 的一个 occurrence，每一步枚举所有可删位置，用 `TWETColumnEvaluator` 计算真实全域成本，并按当前 LP 的完整真实 dual 选择 reduced cost 最低的删法，直到 sequence 基本化。修复后的 sequence 还必须满足当前 node 的分支、外包和 pricing-only 禁弧；reduced cost 统一通过 `LP.computeReducedCost()` 计算，因此 active SRI cut dual 也不会遗漏。诊断结果不进入 Pool/RMP、不更新 ng-set、不改变证书。top-10 容器使用固定小数组线性替换，只在轮末排序，开关关闭时不分配候选容器或执行 evaluator。

在 `wet050_003_3m_setupR50 + W300`、`dualPairCoef0.08 + top1`、root-only 的 43 轮关系实验同口径下，33 次 exact 共 152 个 DSSR round，尝试修复 1479 条 top-route。1479 条都能删除重复得到一个时间可行的基本 sequence，但真实重算后负列为 `0`，额外可加入列同样为 `0`；共调用 evaluator 117492 次，repair 主体计时 377.047 ms。151 个存在候选的 round 中，round 内最好修复 reduced cost 的最小值仍为 `+16.0746`，P10 为 `+60.0103`，中位数为 `+177.8419`，P90 为 `+1208.2698`。这说明 W300 的负松弛路径依赖重复任务带来的多次 job dual 和原路径连接结构，简单删重复会同时损失 dual 收益并改变重连 arc，无法替代后续 DSSR。该 run 仍得到历史一致的 `bound=1726.014329`、`valid=true`；诊断 run 为 `solve=201.042s, exact=101.748s/33`，但 377 ms 只统计 repair 主体，不包含热路径候选收集，且单次 wall time 存在波动，不能据此声称诊断零成本。

小例补测说明该启发式并非数学上永远无效。`wet021_001_2m` 的一次返回基本负列 round 中，10 条 repair 有 3 条重算后仍为负，其中 2 条 signature 尚未由正式候选生成；另一次 5-round 闭合调用中 16 条 repair 均不为负。因而它可以保留为默认关闭的研究诊断，后续若在其他实例上持续命中，可考虑只对极少数 top route 运行并把负基本列作为提前返回候选；但对当前 W300 重尾没有价值，不应优先接入生产主线。下一步更值得做的是“全量更新规则不变、只选择更新后仍存活的 top 候选”的纯诊断与 A/B。

220. 2026-07-13 固定 top3 与更新后存活 top3 的差异

更新后存活策略与当前固定 top3 的 labeling 主体没有差异，变化只在每轮结束后的候选保存和 ng-set 更新。当前实现保存 reduced cost 最低的 K 条路径，然后不区分前一条更新是否已让后一条失效，依次对 K 条路径全部重复段增加 pair。存活策略若允许从后续候选补位，需要把候选池从 K 扩到一个很小的固定池，例如 10；先对 top1 沿用现有全量更新，再按 sequence 顺序重放 ng-memory 递推，检查候选在更新后的 ng-set 下是否仍是合法 ng-route。已失效者跳过，仍合法且非基本者继续执行完全相同的全量更新，直到选满 3 条或候选耗尽。生产实现可以直接把已选更新写入真实 ng-set，不需要复制整个 labeling 状态；只有纯诊断才需要临时 overlay。该检查只扫描最多 10 条短 sequence，成本相对一轮 labeling 很小。

现有 43 轮 top1 关系实验不能给出“top1 同时使 top2 和 top3 失效”的完整比例，因为日志没有保存每轮前 3 条 sequence；但它已经能否定“top1 基本都会干掉 top2/top3”。40 次能在上一轮找到的下一轮 top1 中，22 次来自上一轮第 2 名，10 次来自第 3 名。这些路径能在 top1 更新后的下一轮成为最优，严格说明对应 top2/top3 没有被上一轮 top1 连带排除。因此至少 `32/40=80%` 的匹配转移中，上一轮第 2 或第 3 名里存在一条存活候选。固定 top3 的主要作用正是把这些未来最优路径提前更新，有充分依据；自适应策略的潜在收益不是进一步减少大量轮次，而是跳过另一条可能已被覆盖的候选，控制无效 pair 膨胀。

因此实现前应先加只读统计，而不是直接替换固定 top3。每轮保留 top10，模拟 top1 全量更新后记录 top2/top3 的存活状态、前10中首个存活名次、依次最多选择3条后的实际选择数、增加 pair 数，以及与固定 top3 的 pair 并集差。若 top2/top3 大多都存活，自适应与固定 top3 基本相同，没有修改价值；若经常只存活一条但固定 top3 仍增加大量额外 pair，则自适应有望保持轮数优势并减小后续单轮状态。删重复 repair 在 W300 上已经明确无效，不再作为该更新策略的组成部分。

221. 2026-07-13 当前 ng-DSSR exact 瓶颈与后续优化优先级

重新解析最新完整 W300/50-3 求解日志，共得到 409 次 exact、1535 个 DSSR round，exact 各阶段累计约 1681.948 秒。其中 initialization 为 424.000 秒，占 25.2%；forward expansion 为 449.149 秒，占 26.7%；backward expansion 为 611.771 秒，占 36.4%；join 为 193.875 秒，占 11.5%。初始化进一步拆分后，completion bound 为 261.787 秒，占 exact 总时间 15.6%；midpoint probe 为 160.181 秒，占 9.5%，其余初始化工作接近可忽略。由此可见，当前 source-aware dominance、直接 min-sum 和 group-envelope prefilter 已经把 join 从历史主瓶颈降为次要部分，当前大头是前后向扩展，其次是 completion bound 和跨节点反复进行的 midpoint probe。

当前最明确的低风险实现优化位于无 SRI 扩展热路径。代码在 SRI 未启用时仍会为大量候选创建零长度 `byte[]`，并在 materialize label 时复制 `visitedSet`。无 SRI 的正式 join 使用 `ngMemorySet`，序列恢复使用 father 链；`visitedSet` 的生产用途主要集中在 SRI extension 和 SRI halves 检查。因此可先把空 SRI count 改为共享不可变对象，并严格审计后在无 SRI 路径取消每个存活 label 的 visited bitset 复制。这类修改不改变 labeling、dominance 或列语义，直接减少高频分配和 GC，是下一步最适合先做的代码级优化；实际收益必须用当前 root/W300 A/B 测量，不能预估为固定比例。

第二个方向是 midpoint probe 的父子节点 warm-start。当前同 node 稳定 Tmid 已能冻结复用，但切换 child 或 cut context 后仍重新 probe，完整树累计耗时 160.181 秒。可以把父节点最终 exact Tmid 按 pricing horizon 比例传给 child，clamp 到 child compact horizon 后作为首个候选，只做一次完整前后向计时校验；校验失衡再回退现有 probe。Tmid 只影响双向分解效率，不改变完整扩展和 join 的列族，因此该策略正确性风险较低，理论最大可处理范围约为当前 exact 的 9.5%，但真实节省取决于父子节点 Tmid 稳定性。历史局部 probe 指标曾多次与完整 exact 的正反耗时方向不一致，因此不应再设计只依赖局部 label 数的新移动规则。

第三个方向是在构造 PWLF candidate 前增加真正 O(1) 的粗下界诊断。可使用 parent scalar minimum、当前 arc/job 固定 reduced cost、目标 job 在有效窗口内的 penalty minimum，以及预计算的 completion suffix/prefix scalar minimum求和；不同项的最优时间可以不一致，所以该值是安全但较弱的下界。第一步只统计它能在 `shiftX + add + normalize` 前排除多少扩展，不立即改变正式流程；只有命中率足以覆盖几次浮点运算和数组访问时才接入。此前已经试过逐 segment 扫描的精确 no-allocation prefilter，虽然剪掉约 198 万/259 万候选，却让 exact 变慢约 16.1%，因此不能重复采用“先做一次接近 PWLF 构造成本的预判”。

completion bound 的剩余主要成本在 F/B sparse-delta 传播。已有诊断中 F/B 约占单次构造的 76.4%，U/R 重建约占 23.2%，snapshot 和 discrete cache 很小；同一 DSSR 内复用、多区间 delta 和时间优先队列均已启用。当前 sparse delta 仍以 BigM 填充未变化区间并构造完整 PWLF，下一项可能有明显收益的改造是原生 interval-list delta，只传播真实变化区间，避免 BigM 空洞 segment 的构造和 merge。但这是较大改动，必须逐 job、逐 PWLF 与现实现对拍，不能只比较最终 scalar minimum。此前 transition-only U/R 和 endpoint shortcut 均出现语义或端到端退化，说明 completion bound 不适合再做未经完整对拍的小捷径。

其余方向暂不列为高优先级。bounded same-node warm 在 3-node 对比中把 exact 从 151.529 秒降到 140.672 秒，属于约 7% 的中等收益，但不能消除最终 no-negative certificate；固定 top3 与更新后存活 top3 在现有证据下大部分候选仍会存活，主要只能减少少量冗余 pair；join 当前只占 11.5%，继续做 sequence 去重、group 排序或改变阈值容易受半域 split 和列批次轨迹影响；Hasse 拓扑和 dominance insert 在最新代码下没有当前细分计时，若要继续优化扩展，应先开一次 `ngDssrExtensionTimingDiagnostics`，把 PWLF 构造、状态复制、dominance insert 和 queue 分开，再决定是否动图结构。当前建议顺序为：无 SRI 分配清理，父子 Tmid warm-start，O(1) extension pre-bound 只读统计，最后才考虑原生 interval delta completion bound。

222. 2026-07-13 无 SRI 扩展状态分配清理与扩展路径复核

本次先处理第 221 节中风险最低的无 SRI 高频分配。`GCNGBBStyleBidirectionalNgDssr` 原来在每次正反向扩展时都会为无 SRI 状态创建新的空 `byte[]`，并在 completion-bound survivor 物化成 label 时复制父 label 的 `visitedSet`。代码审计确认，当前 ng-DSSR 无 SRI join 的兼容性只读取 `ngMemorySet`，序列恢复只沿 father 链；`visitedSet` 的实际消费者是 SRI 扩展计数、SRI join 补偿及 SRI 诊断。因而无 SRI 下不再构造 `visitedSet`，所有空 SRI count 改为共享只读零长度数组；active SRI 下仍保留原来的逐 label visited copy 和 count clone，SRI 语义没有变化。

该修改直接减少的是高频对象数，而不是改变 labeling 数学流程。历史 W300 细分日志的一次重 pricing 中，正反向共构造约 51.2 万个 extension candidate、约 6.64 万个 completion-bound survivor；旧实现对应会创建约 51.2 万个空 count 数组，并为约 6.64 万个 survivor 复制 visited bitset。小例 `wet021_001_2m` 在相同主线 smoke 下继续得到 `obj=bound=6829`、`valid=true`，总时间 1.104 秒，exact 0.232 秒/2 次。该小例对象量较少，不能用来宣称稳定 wall-time 提升，但证明 no-SRI 实际 pricing 链路可运行且结果口径不变。

继续检查扩展实现后，剩余最明确的状态构造冗余位于 `buildForwardChildReachability()` 和 `buildBackwardChildReachability()`：每个存活 child 仍扫描全部 job，同时先调用 full-domain direct-feasibility，再对通过者调用 half-domain direct-feasibility；两次调用重复读取动态窗口并重复计算同一个 earliest completion 或 `rhoPrime`。这可以在后续独立改成每个 job 只算一次 full-domain 标量，再额外比较 `Tmid` 得到 extension bit，严格保持 dominanceSet/extensionSet 不变。当前没有合入该项，原因是它应单独 A/B；同时不能简单改成只遍历父 `dominanceSet`，因为 ng-memory 在转移时会遗忘不属于新 terminal 邻域的成员，父状态中被 memory 阻止的 job 可能在 child 重新可用。

本轮还确认了几个不应误删的步骤。`ngMemory = parentMemory ∩ N(current) + current` 至少需要产生一个 child 独立 bitset；dominanceSet 与 extensionSet 分别服务 full-domain dominance key 和半域实际扩展，不能合并为同一集合；arc/pricing-only 检查只约束当前 direct transition，不能写入永久不可达 key。最新 source-aware dominance graph 的插入仍是扩展的重要成本，但已不再采用旧图的全历史 label 反复扫描口径，当前没有发现与本次 allocation cleanup 同等级的无条件冗余。

验证包括 focused `javac`、`IncrementalSourcedDominanceGraphConsistencyTest`、`NgDssrSameNodeWarmStartTest`、`CompletionBoundPreparedBoundsCompatibilityTest`，以及上述 no-SRI root pricing smoke，均通过。一次误开 completion-bound 重诊断的长 smoke 已主动停止，未作为性能证据。

223. 2026-07-13 ng-DSSR label 集合更新逻辑复核

当前 child label 的集合更新逻辑是正确的。先计算 `childNgMemory = parentNgMemory ∩ N(currentJob) + currentJob`，再扫描任务构造两个不同口径的集合：`dominanceSet` 表示完整定义域下当前可直接到达的任务，用作 dominance key；`extensionSet` 是其中同时满足当前半域条件的任务，真正用于后续扩展。两者都使用基础 hard window、当前允许的 dual window、node 继承的 time-indexed compact window以及本轮 helper 收紧后的 effective window；`pricingHorizon` 也已按 effective window 的最大右端点缩短。不存在单独的 `unreachableSet`，任务不在 `dominanceSet` 中即隐式表示当前状态下不可用，但其原因可能是 ng-memory、时间不可达、zero-dual 排除或 required outsourcing，不能把该补集当成永久不可达任务集。

剩余最明确的严格等价优化仍是合并 full-domain 与 half-domain 时间判定。当前 forward 对同一任务重复计算 earliest completion，backward 重复计算 `rhoPrime`；可以每个任务只计算一次，再分别比较完整窗口边界和 `Tmid`，同时决定两个 bit。第二项低风险优化是预计算当前 pricing 的全局可用任务 mask 和普通 allowed-arc mask，并在构造 `extensionSet` 时直接排除 branch、pricing-only 与 completion-bound fixed arc，避免 label 出队后再由 `canExtendForward/Backward()` 做同一检查。普通禁弧不建议直接改变 `dominanceSet` 语义：它只禁止当前 direct transition，任务经过其它 terminal 后仍可能重新可达；即使同 terminal 下删去常量 bit 可能不改变偏序，也没有足够收益支持修改已经对拍过的 dominance key。

不能用父 `dominanceSet` 增量生成 child。除了 ng-memory 会遗忘任务外，setup time 不保证三角不等式，父 terminal 直达某任务时间不可行，并不能推出经过另一个任务后仍不可行。因此 child 扫描候选任务的主体需要保留；可以只遍历预计算的全局 admissible mask，但不能只遍历父集合。`ngMemorySet` 当前通过一次 bitset copy-and-intersect 构造，在 50/60-job 实例上通常只有一个 machine word，已不是值得改写的数据结构热点。

逻辑上还能更强的一项是整数实例下把 node 保存的 raw `(from,to,t)` time-indexed fixing 证据接入 `extensionSet`。安全做法不是逐时间调用 `contains`，而是在 `Node` 增加区间查询，依据当前存储的是 forbidden set 还是 allowed complement，用 `BitSet.nextSetBit/nextClearBit` 判断 label 的有限出发时间区间内是否至少存在一个允许时空弧；完全不存在时才删除该 direct extension。第一版应只收紧 `extensionSet`，不改变 `dominanceSet`，并仅用于精确整数时间实例。逐 segment 扫描 frontier 来做更精确的时间可行性预判暂不建议：此前类似 no-allocation prefilter 虽剪掉约 198 万/259 万候选，却使 exact 变慢约 16.1%，说明接近 PWLF 构造成本的预判没有端到端价值。

按当前证据，实施优先级应为：先合并 full/half 标量计算；再预计算全局任务与普通 arc mask并收紧 `extensionSet`；最后单独 A/B raw time-indexed 区间查询。验收需要逐轮比较 `dominanceSet`、`extensionSet`、扩展候选数、返回列 signature/cost、DSSR rounds 和最终 bound，不能只看集合构造局部耗时。

224. 2026-07-13 child reachability 与直接扩展禁弧预计算

按第 223 节的前两项完成了严格等价优化。forward child 对每个候选任务只计算一次 earliest completion，再分别比较完整有效窗口右端点和 `Tmid`，同时决定 `dominanceSet` 与 `extensionSet`；backward 同理只计算一次 `rhoPrime`。source 与 sink 初始化也统一走同一套 child reachability 构造，不再分别扫描 dominance 与 extension 两遍。`ngMemory = parentMemory ∩ N(current) + current`、dominance key、PWLF 和 join 均未改变。

当前 exact solve 内还会一次性预计算三类 BitSet：可参与内部机器定价的 job、每个 forward terminal 的允许后继、每个 backward successor 的允许前驱。弧掩码严格复用原 `isPricingArcForbidden()` 口径，包含 branch forbidden、pricing-only forbidden 和本次 completion-bound fixed arc；它只与 `extensionSet` 做按 word 交集，不修改 `dominanceSet`。这样保留了“当前 direct arc 被禁，但任务经过其它 terminal 后可能重新可达”的 dominance 语义，同时删除 label 出队后逐弧重复查询多层集合的操作。掩码在一次 DSSR solve 开始时清空、第一次需要 labeling 时建立，同一 dual 下的后续 DSSR 轮复用；若 completion-bound pre-certificate 已直接证明闭合，则不会建立无用掩码。

`wet021_001_2m` root A/B 在 completion bound 关闭时，旧/新均为 `obj=bound=6829`、`valid=true`，labels、DSSR rounds、join 和返回列统计逐项一致，exact 时间为 `0.209s -> 0.197s`。开启 `allCycles + scalar + arc fixing` 后，旧路径 forward/backward 分别枚举 `467/282` 个候选，再在出队阶段删除 `403/231` 个禁弧候选；新路径直接只枚举 `64/51` 个，最终 constructed、bound survivors、labels、3 轮 DSSR 和 `obj=bound=6829` 均一致。该小例单次 wall time 受 JVM/JIT 波动影响，不能据此宣称稳定端到端提速；确定减少的是每个被固定弧挡住的 label-job 组合上的循环、方法调用与集合查询。

验证包括 focused Java 21 编译、`IncrementalSourcedDominanceGraphConsistencyTest`、`CompletionBoundPreparedBoundsCompatibilityTest`、`NgDssrSameNodeWarmStartTest`、no-SRI root A/B、completion-bound arc fixing root A/B 和最终 `git diff --check`，均通过。raw `(from,to,t)` 时空禁弧尚未接入本次掩码，仍按第 223 节作为独立优化处理，避免把整数时间区间查询与本次普通弧优化混在一起。

225. 2026-07-13 child reachability 再次正确性复核

本轮从控制流和数据生命周期重新审计第 224 节实现，未发现新的误剪或状态污染。普通弧掩码只与 `extensionSet` 取交集，不写入 `dominanceSet`；因此被禁止的当前 direct transition 不会被扩展，但该任务经过其它 terminal 后仍可重新进入 child 的 full-domain 可达集合。`PackedBitSet.andInPlace()` 只修改左侧新建的 child 集合，不会反向污染复用掩码。forward 的 earliest completion 和 backward 的 `rhoPrime` 与修改前 full/half 两次判断逐条件相同，区别仅是同一标量只计算一次。

掩码生命周期也与 DSSR 一致：每次 `solve()` 开始清空，在 completion bound、effective window、zero-dual 排除和本轮 time-indexed window tightening 完成后才建立；同一 LP dual 下的后续 DSSR 轮只更新 ng-set，不改变 node branch/pricing-only 普通弧或 completion-bound fixed arc，因此可以复用。in-round time-indexed helper 写回的是 compact window 和 raw `(from,to,t)` 证据，不会在掩码建立后新增普通 `(i,j)` 禁弧。raw 时空弧本来就不属于旧 `canExtendForward/Backward()` 的检查口径，本轮没有遗漏旧语义。

`extensionSet` 的其它生产消费者只有正反向扩展，`extensionCardinality` 只参与队列排序和统计，不进入 dominance key。提前删去旧流程最终必然由 `canExtend` 拒绝的弧，可能在 `TIME/REACHABLE_SIZE` 排序下改变等价 label 的处理先后，但队列仍完整耗尽，不能改变 exact certificate；达到外部 time limit 时列批次可能不同，属于中断搜索顺序差异，不是错误闭合。日志中的 `forward/backward candidates` 现在是 arc-mask 后候选，`arcPruned` 为 0 是预检查已经前移的结果，后续比较新旧日志时不能把两项直接按原口径相加。

再次运行 focused Java 21 编译及 `IncrementalSourcedDominanceGraphConsistencyTest`（96,000 次插入）、`CompletionBoundPreparedBoundsCompatibilityTest`、`NgDssrSameNodeWarmStartTest`，全部通过。结合第 224 节 no-bound 与 completion-bound A/B，当前实现可以保留，不需要增加运行时防御检查。

226. 2026-07-13 ng-DSSR pricing 再次热点审计与 join 位集相交优化

本轮继续沿 initialization、forward/backward extension、source-aware dominance、DSSR 更新和 join 的正式调用链检查。当前 extension 的 dual、setup 和 processing-time 查询均为直接数组读取，JIT 可以内联；completion-bound survivor 之后才物化 label 和构造 child reachability，full/half 时间判定、普通禁弧 mask 以及 effective window 也已合并到一次扫描。对这些位置再增加 dense cache 或重复预判，没有明确收益。join 已有 terminal/group scalar bound、group envelope prefilter、label scalar break 和直接 min-sum，剩余最明确的无条件高频操作是每个候选 label pair 的 ng-memory 相交判断。

旧实现从左侧 `PackedBitSet` 枚举每个 set bit，逐 job 检查 zero-dual 排除数组并调用右侧 `contains()`。现在改为在 exact 初始化时建立一次 join 排除 mask，其中固定包含 source bit 0，并在 dual profitable window 生效时加入全部 zero-dual job；每次 join 直接按 long word 计算 `left & right & ~ignored`。这与旧循环从 job 1 开始、忽略 zero-dual job 的语义逐项一致，不改变 ng-route 兼容性、group prefilter 或列恢复。60-job、两侧各约 6 个 memory 成员、2000 万次判断的 microbenchmark 中，旧逐 bit 实现约为 `421--502 ms`，新 word-mask 实现约为 `56--67 ms`，局部加速约 `6.3x--8.6x`。完整 exact 收益仍取决于进入集合检查的 pair 数，不能把局部倍数直接解释为总求解倍数。

验证新增 `PackedBitSetIntersectionTest`，覆盖 bit 0、63/64、129、多 word、不同 universe 长度和排除 mask，并用固定随机种子对 200,000 组位集与旧逐 bit 参考实现对拍。focused 编译、source-aware dominance 96,000 次随机一致性测试、completion-bound prepared-bounds 兼容性测试和 same-node warm-start 测试均通过。

同时试验了把 extension 的 `shiftX + add` 替换为已有 `PiecewiseLinearFunction.addShifted()`。随机 forward/backward、正负 shift 和 BigM 分段逐点对拍全部相等，但 20-segment、500,000 次操作的 microbenchmark 中，融合实现反而慢约 `9%--29%`；原因是它把原来两个较简单的线性扫描合并成了分支更多的同步区间遍历，减少临时函数没有抵消复杂控制流。该生产修改和临时测试已经完全撤回，不作为后续方向。

当前没有发现第二个可以直接合入、与本次位集判断同等级的严格等价热点。下一项结构性潜力最大的是 50/60-job 场景的单 word 状态后端：`PackedBitSet` 目前即使只有一个 word，也会为每个集合分配一个对象和一个单元素 `long[]`；每个 label 又同时持有 ng-memory、dominance/extension 等多个集合。可以单独试验在 `n+2<=64` 时把首个 word 内联到对象，或实现专用 long-mask label/store，减少小数组、hash 和 GC；该改动会影响全部集合操作和 dominance key，必须独立随机对拍与端到端 A/B。group-first join 可以进一步减少当前 envelope prefilter 的 label-to-group identity lookup 和候选二次遍历，但会改变 pair 顺序以及达到列上限时的批次，属于实验策略而非无条件优化。completion bound 的原生 interval delta 仍可能处理 W300 的 initialization 成本，但历史 shortcut 已出现退化，继续修改必须逐 job、逐 PWLF 验证。

227. 2026-07-13 ng-DSSR 全主线低效点复核

本轮沿一次 `solve()` 的完整控制流重新检查：初始化 ng-set 后，每个 DSSR round 依次构造或复用 effective window、completion bound 和 Tmid，建立正反向状态，耗尽两侧队列，压缩 active labels 后 join；若只得到负非基本路径，则更新 ng-set 并重新开始下一轮。ng-set 改变了 memory 转移和 dominance key，因此跨 DSSR round 直接复用旧 label 图并不安全。当前复用 completion bound、窗口标量、普通禁弧 mask 和首轮 Tmid 的边界是合理的，搜索容器重建本身在历史计时中接近零，不是 initialization 的实际瓶颈。

最新完整 W300/50-3 口径仍应作为优化优先级依据：forward/backward expansion 合计约占 exact 的 63.1%，completion bound 约占 15.6%，midpoint probe 约占 9.5%，join 约占 11.5%。当前无 SRI 扩展已经做到先检查窗口、构造 PWLF、用 completion bound 剪枝，只有 survivor 才创建 ng-memory、dominanceSet、extensionSet 和 label；full/half 时间判定、普通禁弧 mask、source-aware dominance、group-envelope prefilter 和直接 min-sum 均已接入。没有再发现旧 dominance graph 那种反复扫描全部历史 label 的明显冗余，join 也已不是第一瓶颈。

当前 no-SRI 路径最明确的剩余实现成本是单 word 位集分配。50/60-job 实例的 `n+2<=64`，但 `PackedBitSet` 仍为每个集合创建一个包装对象和一个单元素 `long[]`；每个 completion-bound survivor 至少形成 child ng-memory、dominanceSet 和 extensionSet 三套位集。重轮中 survivor 达到数万时，这会形成大量短命小数组、hash 和 GC。优先方案是在 `PackedBitSet` 内增加单 word 内联后端并保留多 word fallback，而不是在 ng-DSSR 中另写一套不兼容的 mask API。该项会影响 copy、集合运算、迭代、equals/hashCode 及 dominance map key，必须先做随机逐操作对拍，再比较 W300 的 allocation、GC、extension state 时间和 exact wall time；预期收益是降低分配与 GC，不能预先声称固定倍数。

带 active SRI 时存在一项更明确的结构性差距。当前 source-aware incremental graph 只服务 no-SRI；SRI 会退回 `SriAwarePartialListDominanceStore`，按 cardinality bucket 先扫描“旧 label 支配新 label”，再扫描“新 label 裁剪旧 label”，且发生 SRI compensation 时会复制并平移支配函数。规模变大后这会重新出现 list dominance 的二次扫描成本。较稳的下一版不是立即实现完整跨 SRI 状态包络，而是先按完全相同的 SRI count state 分组，每组使用 source-aware graph；跨 state dominance 暂时不做，正确但偏弱，再用实际 label 增量判断是否值得继续。代码中 `FunctionLabel.sriStateKey` 当前还为每个 SRI label 构造逗号分隔字符串，但除接口 getter 外没有生产消费者，属于可以先删除的确定性 SRI 分配冗余。

其余候选的优先级较低。父子节点可继承父节点最终 Tmid 的 horizon 比例，clamp 后先做一次完整两侧校验，失衡才回退现有 probe；它不改变列族，风险较低，但理论可处理范围最多约为当前 9.5% 的 probe 时间。同 node bounded warm-start 已有约 7% 的局部收益证据，但会在“减少 DSSR 轮数”和“放大每轮状态”之间权衡，默认仍不应打开。backward `minimizeSuffixInPlace()` 每次用临时 `ArrayList<Segment>` 反向访问函数，可尝试复用引用缓冲区以减少分配，但不会消除 O(segment) 扫描，收益可信度低于单 word 位集；给 Segment 增加双向链或恢复 SegmentPool 都会扩大风险，现有证据不支持。

本轮同时排除了几类看似直接但已经证伪或收益不足的方向：`shiftX+add` 融合在严格对拍相等时仍慢 9%--29%；逐 segment 的 no-allocation completion prefilter 虽剪掉大量候选，却使 exact 变慢约 16.1%；继续改 group 排序、sequence 去重或 Hasse 索引的收益上限受当前 11.5% join 占比限制；原生 interval delta completion bound 仍有潜力，但属于需要逐 job、逐 PWLF 对拍的大改。后续建议顺序为：先试单 word `PackedBitSet`；若研究 SRI，则单独处理 SRI dominance 与无用 state 字符串；再评估父子 Tmid warm-start；最后才考虑 interval delta 或 backward 函数结构改写。本轮只完成代码审计和记录，没有修改生产算法。

228. 2026-07-13 单 word PackedBitSet 内联后端与 A/B

本轮在 `PackedBitSet` 内实现单 word 内联存储：当旧口径计算出的 word 数为 1 时，集合值直接保存在对象的 `long` 字段中，不再额外创建单元素 `long[]`；超过一个 word 时继续使用原数组后端。调用层 API、固定 universe 的截断规则、不同 word 长度之间的 `and/or/andNot`、迭代顺序以及 `equals/hashCode` 均保持旧数组语义。该实现只减少 50/60-job no-SRI 热路径中的小数组分配，不修改 ng-memory、dominanceSet、extensionSet 的数学含义。

固定 128 MB 堆、500 万次 survivor 模拟中，每次创建 child ng-memory、dominanceSet 和 extensionSet 三套单 word 集合。旧数组版三次平均约 `166.885 ms`，新内联版约 `62.422 ms`，即 `33.38 ns -> 12.48 ns/次`，吞吐约提高 `2.67x`；三次 Young GC 均从 `13` 次降至 `8` 次。该基准只说明位集分配热点本身明显改善，不能直接外推为完整 exact 的倍数提升。

进一步在 `wet050_003_3m_setupR50 + W300` 做关闭 ALNS、关闭 strong branching 的确定性 root A/B，两组均得到 `bound=1726.014329`、`8870` 列、`7` 次 exact、`valid=true`；累计 forward/backward survivor 也完全相同，分别为 `487765/229234`。旧版 exact 为 `64.509s`，内联版为 `62.743s`，降低约 `2.74%`。分阶段日志中 forward 为 `23.620s -> 22.857s`，backward 为 `20.576s -> 18.045s`，join 为 `5.053s -> 4.491s`，但 initialization 存在反向波动；完整 solve 因启发式计时从 `21.135s` 波动到 `32.635s`，反而为 `111.545s -> 124.901s`，因此不能声称端到端稳定加速。当前结论是：该修改对目标分配热点有明确作用，对完整 no-SRI exact 是小幅正收益，保留实现合理，但收益远小于微基准倍数。

正确性验证新增 100,000 组逐操作随机对拍，覆盖单 word、多 word、不同 universe 长度、边界 bit、容量异常、copy、集合运算、迭代、subset、equals/hashCode 和原地修改；原有 200,000 组相交测试、96,000 次 source-aware dominance 一致性、completion-bound prepared bounds 兼容性和 same-node warm-start 测试均通过。active SRI 仍回退旧 list dominance，本轮按要求不优化、不改动；其状态分组与 dominance 图接入以后单独研究，不能与本次 no-SRI 存储优化混在一起评价。

229. 2026-07-13 单 word PackedBitSet 再次正确性审计

本轮按旧数组后端的逐方法语义重新检查单 word、多 word和混合后端路径，重点覆盖固定 universe 截断、63/64 位边界、不同 word 长度的集合运算、迭代、subset、copy 及 `equals/hashCode`。当前 `wordCount()` 未改，因此 63 仍为单 word、64 仍按旧口径分配两个 word；混合后端运算也只在左侧集合已有容量内更新，与旧实现一致。进一步检查 dominance graph、single-point store 和 join envelope 的 `HashMap<PackedBitSet,...>` 调用，持久 key 均使用独立副本，未发现把后续会原地修改的 label 位集直接作为 map key 的情况。

重新执行 focused Java 21 编译、200,000 组相交对拍、100,000 组全操作对拍、96,000 次 source-aware dominance 一致性、completion-bound prepared-bounds 兼容性和 same-node warm-start 测试，全部通过。当前未发现需要修正的 correctness finding；剩余风险只在于尚未运行 Maven 全项目测试，因为本机没有 `mvn`，不影响本次已覆盖的生产调用链结论。

230. 2026-07-13 当前主线效率结论边界

当前可以认为 no-SRI ng-DSSR exact pricing 的实现层面已经完成一轮系统优化，没有继续确认到类似旧 dominance graph 全历史扫描、重复函数构造或单元素数组分配这种低风险且明显的冗余。source-aware incremental graph 和 join group-envelope prefilter 默认开启，completion bound 默认走 multi-delta 与时间优先传播，单 word `PackedBitSet` 也已成为固定后端；Tmid 的 node/DSSR 复用和稳定冻结只在外部启用 midpoint probe 时生效，基础配置中的 probe 本身仍默认关闭。

这不等于整个 BPC 主线不存在低效。最新完整 W300 统计中，exact 内部仍主要消耗在前后向 PWLF 扩展，其次是 completion bound、midpoint probe 和 join；这些成本目前主要来自 survivor/segment/label 数量，而不是已经定位到的重复实现。completion bound 的原生 interval delta、父子节点 Tmid warm-start和 DSSR 更新策略仍有算法级优化空间，但都需要新的 A/B，不能作为无条件修改。active SRI 仍回退旧 list dominance，明确不属于当前已经优化完成的 no-SRI 结论；完整求解中的 ALNS、启发式 pricing、strong branching trial LP 和 master LP 也可能超过 exact pricing，必须按具体日志分别判断。因此后续应按新算例的阶段计时触发优化，而不是继续无证据地改 no-SRI exact 热路径。

231. 2026-07-13 W300 静态 ng-set 初始化、DSSR 更新强度与固定 ng-relaxation 对照

本轮只比较静态策略，不启用 history/same-node warm-start。算例为 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300`，关闭 ALNS 和 strong branching，只求 root；其余 source-aware dominance、join envelope prefilter、completion bound、midpoint probe 复用、time-indexed root preprocessing/seed 和 repeatability filter 保持一致。为避免旧日志中 preprocessing 列池不同造成干扰，五组均在当前代码上顺序重跑，time-indexed 预处理均得到 `tempPool=27321`、`410606` 条时空禁弧和 200 条 elementary seed。

| 策略 | root 总时间 | exact 时间/调用 | DSSR 总轮数/单次最大 | ng pair 更新 | pool | root bound |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `K3 + top3` | 95.686s | 58.125s / 10 | 29 / 14 | 337 | 6815 | 1726.014329 |
| `K5 + top3` | 111.078s | 63.313s / 6 | 20 / 15 | 229 | 6907 | 1726.014329 |
| `K3 + top5` | 99.158s | 54.013s / 10 | 26 / 12 | 337 | 6815 | 1726.014329 |
| `K3 + top10` | 97.719s | 50.296s / 10 | 19 / 7 | 337 | 6815 | 1726.014329 |
| 固定 `K5`，允许 ng-relaxed 列 | 56.546s | 21.885s / 6 | 6 / 1 | 0 | 11099 | 1721.469181 |

结果首先说明，统一增强初始化不如增强困难 DSSR 轮次的更新。`K5 + top3` 虽把 exact 调用从 10 次降到 6 次，但最后一次无负列证明仍需要 15 轮，exact 和总时间都比 `K3 + top3` 更慢。原因不是 K5 不够强，而是它把所有 pricing 一开始的状态都扩大了；大量本来一轮就返回 elementary 负列的调用也承担了更大的 label 状态，而真正困难的 certificate 调用没有因此消失。相比之下，`K3 + top10` 只在已经观察到负 non-elementary witness 时更快加入约束，DSSR 总轮数由 29 降到 19、单次最大由 14 降到 7，exact 比 top3 降低约 13.5%。`K3 + top3/top5/top10` 最终都增加 337 个 pair、得到完全相同的 pool 和 root bound，说明 top10 在本 root 中主要是把最终必需的 pair 提前加入，并没有扩大最终 ng-set。当前 W300 的静态 elementary 策略应优先采用较小初始化配合较强更新，即 `K3 + top10`；是否修改全局默认，还需在另一组窗口宽度和完整 BPC 树上复核。总时间受 preprocessing 和 heuristic 波动影响，因此这次判断主要看 exact 时间和 DSSR 轮数，不用约 2s 的 wall-time 差异判断 top5/top10 优劣。

文献中的固定 ng-neighborhood 通常是较小常数邻域，例如每个客户取 8 或 10 个近邻；也有从空集合启动、再用 DSSR 动态扩张的实现。因此“每个 job 使用 n/10”不是通用规则，只能视为本例 `n=50` 时 `K=5` 的一个静态候选。当前实验已经直接否定了“W300 上把所有 job 从 K3 统一改为 K5 就会更快”，但没有否定固定 K5/K8/K10 作为纯 ng-route relaxation 的用途。

固定 `K5` 并打开 `ngDssrReturnRelaxedColumns` 属于另一种算法口径。它允许 elementary 和 non-elementary 的负 ng-feasible route 一起进入 RMP，不再通过 DSSR 把列族收紧到 elementary，因此每次 exact 只有一轮，exact 比 `K3 + top3` 降低约 62.3%，root 总时间降低约 40.9%。代价是 root bound 从 `1726.014329` 降到 `1721.469181`，弱 `4.545148`，约为 elementary bound 的 0.2633%，列池则从 6815 增至 11099。该模式不是“只加入非基本列、丢掉基本负列”；基本负列仍必须保留，非基本列是额外放松。当前 evaluator 会在返回前按固定 sequence 重算真实目标，LP 覆盖系数按实际访问次数进入，因此这是可解释的 ng-route relaxed master 下界，不是错误列成本。

由此更合理的后续实验分成两条。若目标仍是 elementary exact pricing，保持 warm-start 关闭，先比较 `K3 + top10` 在 W100/W300 和完整树上的稳定性，再决定是否采用；不建议继续统一放大初始化。若目标是更快的 root 下界，则单独比较固定 K3/K5/K8/K10 的速度、bound 和 pool 曲线，并研究“先用独立 relaxed root 得到下界、dual、arc/window 证据，再切回 elementary ng-DSSR”的两阶段方案，不能把 relaxed root bound 直接写成 elementary root bound。此前 40-2 完整树实验已经显示 relaxed 列虽减少 exact 调用和节点，却会显著放大 master/strong branching 成本，因此固定 ng-relaxation 暂不作为全树默认。用户最后提出的“只去找那些不存在这种……”条件尚未完整，具体受限列族的定义需补全后再判断其下界和完备性。

随后补测 `K1 + top3/top10`，其余配置和 preprocessing 证据保持完全一致，两组同样得到 `bound=1726.014329, valid=true`，最终 pool 均为 5759。`K1 + top3` 为 `solve=213.825s, exact=145.930s/22 calls`，DSSR 共 71 轮、单次最大 20 轮、累计更新 743 个 pair；`K1 + top10` 为 `solve=240.464s, exact=163.675s/22 calls`，DSSR 共 49 轮、单次最大 9 轮、累计更新 731 个 pair。相比 `K3 + top10` 的 `exact=50.296s/10 calls` 和 19 轮，K1 使 exact 时间扩大到约 3.25 倍。根因是初始状态过松：更多 pricing 无法直接返回足够的 elementary 负列，RMP 需要 22 次 exact 往返，最终列池反而比 K3 少 1056 条。

K1 下 top10 虽把 DSSR 轮数从 71 降到 49，但每轮 exact 平均时间由约 2.054s 增至 3.338s，增加约 62.5%，因此净时间反而更差。较强更新会更早扩大 ng-memory，使后续每轮 labeling 更重；它只有在 K3 已经过滤掉大量无关重复的前提下，才表现为净收益。由此当前静态结论进一步收敛为：K1 太松、K5 统一初始化偏重，W300 上 K3 是较好的平衡点；K3 下优先 top10，不能把“update 越强越好”脱离初始 ng-set 大小单独推广。

232. 2026-07-13 W300 的 K5/K10/K20 与 top3/top5/top10 完整矩阵

为进一步验证“初始 ng-set 太小会较早返回质量较差的 elementary 列”以及较大固定邻域的代价，本轮保持第 231 节的全部 root-only 配置不变，补齐 K5/K10/K20 与 top3/top5/top10 的静态矩阵。所有实验均得到相同 `root bound=1726.014329, valid=true`；同一 K 的三种 update 还得到相同 exact 调用数、加列序列和最终 pool，因此 update 对比没有混入不同 RMP 路径。

| 初始 K | update top | exact 时间/调用 | DSSR 总轮数/最大 | 更新 pair | pool |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 3 | 3 | 58.125s / 10 | 29 / 14 | 337 | 6815 |
| 3 | 5 | 54.013s / 10 | 26 / 12 | 337 | 6815 |
| 3 | 10 | 50.296s / 10 | 19 / 7 | 337 | 6815 |
| 5 | 3 | 63.313s / 6 | 20 / 15 | 229 | 6907 |
| 5 | 5 | 38.697s / 6 | 15 / 10 | 234 | 6907 |
| 5 | 10 | 49.965s / 6 | 12 / 7 | 234 | 6907 |
| 10 | 3 | 79.098s / 8 | 23 / 12 | 216 | 7843 |
| 10 | 5 | 46.990s / 8 | 18 / 8 | 220 | 7843 |
| 10 | 10 | 54.442s / 8 | 14 / 5 | 256 | 7843 |
| 20 | 3 | 87.980s / 5 | 14 / 10 | 97 | 10007 |
| 20 | 5 | 90.259s / 5 | 11 / 7 | 102 | 10007 |
| 20 | 10 | 110.552s / 5 | 9 / 5 | 102 | 10007 |

矩阵首先支持用户对 K1 的判断。按 top5 口径，第一次 exact 返回的 elementary 列数随 K 增长分别为：K1 为 780，K3 为 1069，K5 为 1674，K10 为 2616，K20 直接达到每轮上限 5000。K1 后续出现大量只增加 1--20 条列的小尾轮，最终需要 22 次 exact；K5 只需 6 次。也就是说 K1 慢不只是同一次 pricing 内 DSSR 轮数多，它还会让较弱 elementary 列分批进入 RMP，产生更多 LP/exact 往返。另一端 K20 虽能一次返回大量列、只需 5 次 exact，但 pool 膨胀到 10007，初始 ng-memory 使单轮 labeling 显著变重，因此同样不合适。

update 强度的结构性结果比单次 wall time更稳定。对 K3/K5/K10/K20，top3 到 top5 再到 top10 都单调减少 DSSR 轮数，而且同一 K 的最终 pool 和列序列不变，说明较强 update 主要是提前加入最终需要的 pair。wall time 存在明显机器波动：K5/top5 两次完全相同的列序列和 15 轮 DSSR，exact 分别为 38.697s 和 56.913s；K5/top10 两次同为 12 轮，exact 分别为 49.965s 和 35.397s。其 mandatory completion-bound 构造时间也同步变化，证明不能仅按单次最小时间选择 top5。两次均值为 top5 约 47.805s、top10 约 42.681s，加上 top10 稳定减少轮数，当前仍优先 top10。K20 下 top10 虽轮数最少，但状态过重，任何 update 都无法挽救过大的初始 K。

最后用相邻机器状态复跑 K3/top10 与 K5/top10：K3 为 `solve=82.161s, exact=39.872s/10 calls, 19 rounds`；K5 为 `solve=75.735s, exact=35.397s/6 calls, 12 rounds`。K5 通过更强的初始 neighborhood 返回更多 elementary 列，减少了 4 次 exact/RMP 往返，同时没有像 K10/K20 那样显著放大状态。当前 W300 的静态候选因此从 K3/top10 调整为 `K5 + top10`，但优势只有约 4.5s exact，仍需在 W100、另一组 W300 实例和完整 BPC 树上验证后才能修改全局默认；warm-start 继续关闭。

233. 2026-07-13 nearest n/10 与 dual-pair 初始化对比

根据第 232 节中 K3/K5 明显优于 K1/K10/K20 的结果，默认 nearest 初始化改为按任务规模动态取 `K=floor(n/10)`。配置中 `ngDssrInitialNgSetSize=-1` 表示该自动口径，显式设置非负 K 仍保持原有固定值语义；默认模式同时由 `dualPair` 改为 `nearestK`。因此 40/50/60 任务分别得到 K4/K5/K6，后续可以直接按规模批量运行，不再为每组实例手工设置 K。

随后在同一个 `wet050_003_3m_setupR50 + W300` root-only 口径下，统一采用 top10、time-indexed root preprocessing、200 条 elementary seed、time-indexed pre-heuristic、source-aware dominance、all-cycles completion bound、Tmid 复用、repeatability filter，关闭 ALNS、strong branching、RMIH 和 warm-start，对 nearest 与 dual-pair 做当前代码同批次比较。所有结果均为 `bound=1726.014329, valid=true`。

| 初始化 | 名义初始规模 | solve | exact 时间/调用 | DSSR 总轮数/最大 | 更新 pair | 首次/累计基本列 | pool |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| nearest auto | 每 job K5 | 105.440s | 58.071s / 11 | 19 / 6 | 334 | 2834 / 5064 | 9993 |
| dualPair 0.08 | 最多 4 个无向 pair | 192.925s | 114.275s / 22 | 56 / 10 | 815 | 189 / 909 | 5884 |
| dualPair 0.5 | 最多 25 个无向 pair | 121.031s | 63.893s / 16 | 42 / 10 | 706 | 189 / 752 | 5738 |
| dualPair 1.0 | 最多 50 个无向 pair | 135.028s | 80.114s / 22 | 53 / 10 | 741 | 194 / 1992 | 6926 |
| dualPair 2.5 | 最多 125 个无向 pair | 183.544s | 100.194s / 23 | 53 / 9 | 528 | 67 / 729 | 5766 |

旧式 `coef=0.08` 对 50 个任务只选 4 个全局 pair，平均初始成员远小于 1，明显过松。把系数提高到 0.5 后有所改善，但仍比 nearest 多 5 次 exact 和 23 个 DSSR 轮次。`coef=2.5` 的名义有向成员总数与 nearest K5 接近，但 pair 会集中在当前 dual 下最负的少数任务组合，并在每次 exact 随 dual 重选，无法提供 nearest 对每个 job 的均匀基础 memory；其首次 exact 只返回 67 条基本列，随后仍有大量小批量加列。由此本例的差异不只是 pair 总数量，而是初始化覆盖结构。

分阶段数据进一步说明 nearest 不是让单次 labeling 无条件变轻。nearest 平均每次 exact 约 5.28s，dualPair 0.5 约 3.99s；后者单次较轻，但需要 16 次 exact，而 nearest 只需 11 次。nearest 累计返回 5064 条基本列、pool 为 9993；dualPair 0.5 只返回 752 条、pool 为 5738。root-only 下 nearest 通过更大的基本列批次减少 pricing/RMP 往返而获胜；完整树中较小 pool 可能降低 master LP 和 strong branching 成本，因此 dualPair 0.5 仍值得作为批量完整树对照，不能由本次 root 结果断言全局淘汰。

代码复核还确认 `dualPair` 与 `reducedCostPair` 当前都调用同一个“按本轮 pair reduced cost 排序”的实现，只是为了兼容旧命令保留两个名称；重复运行 `reducedCostPair` 不会形成独立证据。当前 pair 分数只使用 setup cost、arc dual 和 job dual，不使用 setup time、处理时间以及具体的 `j -> k -> j` 时间可行性。若继续设计 pair 初始化，更合理的下一步是按每个中心 job 分别选 K 个 pair-score 最好的、且在 effective window 下可构成重复访问的邻居，而不是继续调全局 coefficient。

当前实验优先采用 `nearest floor(n/10) + top10` 作为静态候选，其中代码只把 nearest 与动态 n/10 改为默认，top10 仍由实验配置显式打开；pair 模式保留为实验开关。`empty` 比已明显偏弱的 K1 和 dualPair 0.08 更松，本轮不再消耗一次完整 root 实验。上述结论目前只覆盖单个 W300 root，动态 n/10 是否适合作为全局默认仍需在 40/60 任务、较窄窗口和完整 BPC 树上批量复核。

234. 2026-07-13 per-job feasible pair 初始化实验

针对第 233 节提出的改进方向，新增独立实验模式 `perJobFeasiblePair`，不替换 nearest 和全局 dualPair。该模式对每个 ng-set 行独立选择最多 K 个成员，按当前 dual 下的 pair reduced cost 从小到大处理，并要求具体重复结构在当前 effective window、普通禁弧和 time-indexed 时空禁弧下可行。这里必须按真实 ng-memory 语义检查：`N_center` 中的成员 `member` 用于在到达 `center` 时记住旧的 `member`，因此对应的是 `member -> center -> member`，而不是反方向。整数实例使用逐时间点检查，其他实例使用连续 hull 检查。候选只做排序后按需检查，选满 K 即停止。

在第 233 节完全相同的 W300/50-3 root-only 配置下，使用 `K=floor(50/10)=5` 和 top10 得到 `bound=1726.014329, valid=true`，说明该初始化仍保持 ng-DSSR 的正确闭合口径。但性能没有改善：`solve=193.778s`，exact 为 `124.510s/21 calls`，DSSR 共 53 轮、单次最多 9 轮，累计更新 640 个 pair；首次 exact 只返回 73 条基本列，累计返回 665 条，最终 pool 为 5629。对照结果如下。

| 初始化 | solve | exact 时间/调用 | DSSR 总轮数/最大 | 更新 pair | 首次/累计基本列 | pool |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| nearest auto K5 | 105.440s | 58.071s / 11 | 19 / 6 | 334 | 2834 / 5064 | 9993 |
| dualPair 0.5 | 121.031s | 63.893s / 16 | 42 / 10 | 706 | 189 / 752 | 5738 |
| perJobFeasiblePair K5 | 193.778s | 124.510s / 21 | 53 / 9 | 640 | 73 / 665 | 5629 |

负结果不是 pair-specific 检查本身太慢。21 次 exact 中 ng-set 初始化累计只有约 0.017s，远低于 labeling 时间。真正差异出现在 relaxed route 结构：新模式第一次 exact 看到约 108.9 万条 non-elementary negative witness，而 nearest 只有约 13.2 万条；新模式因此需要 2 轮 DSSR 才只返回 73 条基本列，nearest 第一轮直接返回 2834 条。后续新模式又出现大量每次只返回 1--16 条基本列的小批次，增加了 exact/RMP 往返。

原因是当前 pair score 只使用 setup cost、job dual 和 arc dual；root 无 arc dual 时，它主要偏向高 job-dual 成员。pair-specific feasibility 只回答某个重复环是否存在，不衡量 setup time、processing time、时间惩罚以及该环实际有多容易产生负 reduced cost。结果是它可能选择“理论上可重复但时间很长”的高-dual pair，却漏掉 nearest 能直接覆盖的大量短重复环。由此当前不应把 per-job pair score 取代 nearest，也不值得继续只调 K。新模式保留为默认关闭的实验入口；若继续研究，需要构造具体 `member -> center -> member` 的时间相关盈利下界或把 setup duration 明确纳入 score，而不是继续使用二元可行性过滤。

235. 2026-07-13 repeat-cost 与 nearest 混合初始化实验

在 `perJobFeasiblePair` 的负结果基础上，新增两个默认关闭的实验模式。`perJobRepeatCost` 对每个 center 分别扫描可行的 `member -> center -> member`，把两条 setup cost、arc/job dual 与 center、第二次 member 的时间惩罚一起计入排序；整数实例逐时间点精确计算，非整数实例只用安全的区间最小值作排序，不参与定价证书。`nearestRepeatHybrid` 保留 nearest 作为主体，先选 `K-1` 个 nearest，再按上述 repeat-cost 补到 K；候选若已在 nearest 中会继续向后选，因此实际目标大小不减少。

W300/50-3 的同口径 root-only 结果如下，全部得到 `bound=1726.014329, valid=true`。

| 初始化 | solve | exact 时间/调用 | DSSR 总轮数/最大 | 更新 pair | 首次/累计基本列 | pool |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| nearest K5 | 105.440s | 58.071s / 11 | 19 / 6 | 334 | 2834 / 5064 | 9993 |
| hybrid K4 | 72.120s | 40.563s / 9 | 20 / 8 | 436 | 961 / 2097 | 6958 |
| hybrid K5 | 72.497s | 41.328s / 8 | 16 / 6 | 366 | 1512 / 4426 | 9296 |
| hybrid K6 | 60.521s | 31.975s / 6 | 13 / 6 | 314 | 2953 / 3866 | 8733 |
| nearest K6 | 62.070s | 31.667s / 6 | 11 / 6 | 186 | 3132 / 5583 | 10441 |
| nearest K7 | 71.194s | 41.773s / 8 | 15 / 6 | 260 | 4349 / 8085 | 12991 |

纯 `perJobRepeatCost K5` 的首轮 exact 与 `perJobFeasiblePair K5` 完全相同：都只产生 73 条基本列并看到 1,089,360 条 non-elementary witness，因此确认行为后停止了冗余完整运行。原因是 W300 的宽零惩罚区允许 center 和第二次 member 的惩罚同时取 0，repeat-cost 退化为原 pair reduced cost。K6 的 hybrid 和 nearest 都只需 6 次 exact，时间也几乎相同；主要收益来自总 K 从 5 增至 6，而不是 repeat 信号。K7 又开始退化，说明该实例的静态合适规模在 K6 附近。

为确认时间惩罚真正参与排序时的表现，又在相同 setupR50 数据上使用原始单点 due date。全部结果为 `bound=32229, valid=true`。

| 初始化 | solve | exact 时间/调用 | DSSR 总轮数/最大 | 更新 pair | 首次/累计基本列 | pool |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| nearest K6 | 28.239s | 4.364s / 10 | 18 / 5 | 38 | 204 / 508 | 8206 |
| hybrid K6 | 25.798s | 3.684s / 6 | 12 / 5 | 27 | 219 / 424 | 8112 |
| hybrid K5 | 49.339s | 9.395s / 11 | 18 / 4 | 50 | 60 / 366 | 8087 |
| perJobRepeatCost K6 | 49.873s | 9.014s / 11 | 27 / 5 | 132 | 71 / 158 | 7900 |

单点 due date 下 hybrid K6 比 nearest K6 少 4 次 exact，总时间降低约 8.6%，说明在时间惩罚有区分度时，用一个 repeat-cost 槽位替换 nearest 可能有价值。但纯 repeat-cost 仍明显偏弱，nearest 提供的局部结构不能被 pair 排序取代。当前结论是保留 nearest 作为默认主体，两个新模式继续默认关闭；50-job 的两组结果都支持继续批量验证 K6，但不足以据此把全局自动公式从 `floor(n/10)` 改成 `round(n/8)`。下一轮应在 40/60-job 和不同 setup/window 上统一比较 nearest `floor(n/10)`、nearest `round(n/8)` 与 hybrid `round(n/8)`。

236. 2026-07-13 zero-setup 下 nearest 初始化的退化边界

当前 `nearestK` 的距离是双向 setup time 与双向 setup cost 之和，再按 job 编号打破并列。因此“nearest 当前最好”的结论只覆盖 setup 距离有区分度的实验口径。若 setup time 全为 0、setup cost 仍非零，当前策略实际按双向 setup cost 排序；若 setup time 和 setup cost 都为 0，所有候选距离完全相同，经过全局不可重复任务过滤后只按 job 编号选前 K 个，已经不再具有邻近含义。现有 repeatability filter 只判断某个 member 是否能通过任意任务重复，并不保证该 member 与当前 center 能构成具体重复环，所以也不能消除这个退化。

zero-setup 下更合理的静态回退不是继续使用任意编号，也不宜直接采用易随 dual 波动的纯 pair reduced cost。候选应先按当前 effective window、普通/pricing-only arc 和整数时空禁弧检查具体 `member -> center -> member` 是否可行，再按时间结构排序。优先候选是重复环持续时间 `p_center + p_member`；因为对固定 center 而言 `p_center` 为常数，本质上会优先记住处理时间短、最容易形成重复环的 member。可再用重复环首个完成时间的可行区间宽度、真实时间惩罚下界作次级排序。该方向可单独实现为 `temporalNearestK`，或者在检测到 setup 距离无差异时作为 nearest 的自动回退；在 zero-setup 算例完成 A/B 前不修改当前默认。

237. 2026-07-14 no-SRI ng-DSSR 实现低效再审计

本轮只沿当前实际接线的 no-SRI 主线检查：`GCNGBBStyleBidirectionalNgDssrPricingEngine -> GCNGBBStyleBidirectionalNgDssr -> IncrementalSourcedDominanceGraph`。不评价 active SRI 的 list store，也不把 strong branching、启发式 pricing 或 master LP 时间算到 exact pricing 中。重新检查后没有发现旧 dominance graph 那种扫描整节点历史 label、无 SRI 重复构造两套 frontier、join 每个 pair 构造临时 PWLF 或每轮重建 completion bound 的残留。completion bound、effective window、普通禁弧 mask、active column signatures 和首轮 Tmid 在同一次 DSSR 的后续 round 中均已复用；候选池的最新 W300 日志通常为 `seen` 仅略大于 `kept`，没有 lazy stale heap 大量膨胀的证据。

当前仍有三类已经坐实、但量级较小的实现冗余。第一，`precomputeSriPricing()` 在判断 no-SRI/非 list-partial 后端并返回之前，仍会创建多组 SRI `ArrayList`、`n+1` 个 job list 和数组容器；这些对象在当前 no-SRI round 中不会被使用。第二，`initialize()` 与 `initializeCandidateState()` 重复创建 `generatedColumns`，`initialize()` 与 `initializeLabelSearchState()` 也重复清零 dominance 统计。第三，诊断开启时，`recordPricingDiagnostics()` 会在同一次 DSSR 的每个 round 重新扫描 `n^2` 普通弧、全部 restricted columns 并计算 job-dual 分位数，而这些 LP/node 信息在 round 间不变；`diagnosticHeartbeat()` 还会在每个 label 出队后调用 `System.nanoTime()`。这些都可以严格等价地整理为 no-SRI 早返回、单点初始化、首轮诊断缓存和按固定 pop 数批量检查 heartbeat。生产默认诊断关闭，最新日志中的单次诊断约为数毫秒，因此它们不是当前主要瓶颈。

剩余最值得独立 A/B 的热路径是 child reachability。每个通过 completion bound 的 survivor 仍会创建 child ng-memory、`dominanceSet`、`extensionSet`、`ChildReachability` 和 label，并扫描 `reachabilityCandidateJobs` 中的全部任务。W300 重轮中 survivor 可达到数十万，因而仍是千万级逐任务判断。这里可以严格等价地预计算时间阈值 mask：forward 的 full-domain 可达条件可写成 `frontier.head.start <= hEnd(from,j)-setup(from,j)-p_j`，half-domain 再加入 `hStart<=Tmid` 和 `frontier.head.start<=Tmid-setup-p`；backward 可由 `frontier.tail.end` 和对应反向阈值对称构造。50/60-job 的单 word 后端下，可按阈值排序后通过二分查找取得一个 long mask，再减去 ng-memory 并与普通禁弧 mask 相交，替代逐 job 扫描。实现前必须逐 label 对拍 `dominanceSet/extensionSet`，覆盖 null penalty、source/sink、dynamic compact window 和 Tmid 边界；在 A/B 前不能宣称收益。

第二个结构候选是 source-aware envelope merge。当前每次 accepted local merge 或向后传播都会新建完整 `ArrayList<SourcedSegment>`，未变化的旧段也会重新创建对象；merge 完成后又扫描一次全部 merged segments，重建 `IdentityHashMap<Label,...>` 并找出消失 source。最新 W300 首轮可出现约 38 万次 merge，envelope 平均上百段，因此这里可能形成明显的对象分配和 GC 压力。可行方向是让 append 阶段同时维护新 source registry 和 displaced source count，消除 merge 后第二次扫描；更进一步只复制变化边界、复用不变 segment 区间，但这会碰到相邻段合并和 mutable `end`，风险明显高于 reachability mask。应先增加 JFR/分配统计或局部计时，再决定是否修改。

当前结论不是“no-SRI 已无优化空间”，而是剩余大头主要来自 survivor、segment 和 label 数量，不再是明显错误的重复流程。建议顺序为：先清理三处确定的小冗余；再做 threshold-mask child reachability 的严格集合对拍和 W300 A/B；只有前两项仍不足时，才动 source-aware envelope 的存储结构。completion bound、group-envelope join、candidate pool、active-label 压缩和 DSSR round 更新暂未发现新的可直接删除工作，不应在没有新证据时继续改写。

238. 2026-07-14 no-SRI 高频实现逐项 A/B

本轮继续沿 no-SRI 实际主线逐项检查实现成本，不修改 ng-DSSR 的数学状态、占优关系和返回列口径。测试统一采用 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 的确定性 root-only 配置：关闭 ALNS、strong branching、RMIH 和 SRI，保留启发式 pricing、time-indexed root preprocessing、200 条 elementary seed、nearestK5/top10、source-aware dominance、join envelope prefilter、all-cycles completion bound 和 Tmid 复用。此前尝试关闭启发式后 root 路径和列池发生大幅变化，运行超过 8 分钟仍未结束，不能作为实现级 A/B，已中止并从结论中排除。

第一项尝试针对 50/60-job 单 word 位集，把 child reachability 的逐 job 扫描预编译为按最早/最晚时间查询的 threshold mask。40-2 和 W300 的逐 label 对拍均严格一致；W300 中原扫描约 `264--268ms`，mask 查询约 `99ms`，另有约 `17ms` 建索引，覆盖 898,939 次查询。但放到完整 exact 中只稳定节省约 `0.15s`，约占 exact 的 `0.34%`，wall time 波动远大于收益。该实现增加了四套索引、配置和单 word 专用 API，收益不足以抵消复杂度，已完整撤回。

第二项针对 `IncrementalSourcedDominanceGraph` 的 envelope install。旧流程在 merge 已经逐 segment 构造新包络后，还会再次扫描全部 merged segments，以重建 source 集合并识别不再贡献的本地 label。W300 诊断中该二次扫描累计处理 `61,361,126` 个 segment，耗时 `1.442s/665,978 calls`，约占图计时的 29.7%。新流程在第一次 append segment 时给仍存活的 label source 写入本轮 generation mark；install 阶段只遍历 node 原有的少量 source，删除没有本轮 mark 的来源，并补入确实贡献区间的新 label。数值包络、tie 规则、delta 和 displaced-source 语义不变。partial 模式需要 retained interval，仍完整保留旧扫描路径。

随机一致性测试分别在新 mark 路径和旧扫描路径下完成 96,000 次插入，active 状态均为 `164/143`；微测时间约为 `19.521ms/34.239ms`。W300 旧扫描与新路径得到完全相同的 `bound=1726.029855`、`pool=9574`、7 次 exact 和 `valid=true`。带图诊断时 install 由 `1.443s` 降至 `0.396s`，约减少 72.6%；关闭诊断后的最终 run 为 `solve=81.730s, exact=41.024s/7`。两次旧扫描无诊断基线为 `exact=43.854s` 和 `45.153s`，平均约 `44.504s`，因此 exact 平均降低约 7.8%，总时间相对基线均值约降低 3.8%。该优化默认启用，同时保留系统属性 `twet.bpc.incrementalSourcedGraphSourceMarkInstall=false` 作为旧路径对拍入口。

此外保留一处小清理：同一次 exact pricing 的 DSSR 轮次间清空并复用 `generatedColumns`。该项不作为主要提速来源。当前结论是 source install 的二次 segment 扫描属于已确认、可稳定消除的实现冗余；threshold mask 虽正确但净收益不足，不应进入生产主线。

提交后再次按控制流审计 generation mark。每次 merge 使用新的 mark；首遍 append 只给新包络中仍存在的本地 source 写 mark，external predecessor 获胜或 tie 时旧 source 不写 mark，因此 install 对旧 `localSources` 的删除结果与原先扫描 merged segments 重建 source map 严格相同。candidate 未贡献时不会安装临时 merged 包络；partial 需要 retained intervals，`sourceMark=0` 后仍走旧完整扫描。`generatedColumns.clear()` 的两个调用入口都位于 `initialize()` 已创建容器之后，alternative join audit 会保存、替换并恢复独立容器，不存在空引用或误清主候选。新增 `SegmentPool=true` 的新旧路径测试，各完成 96,000 次随机插入并覆盖 forward/backward、partial、延迟 partial、菱形传播、删除重连和 source invariant，全部通过。当前 pricing engine 没有并行执行入口；若未来引入同 JVM 并行 pricing，需要把静态 mark seed 改成 graph-local 或线程安全计数。

239. 2026-07-16 当前主线正确性与热点再审计

本轮按当前真实接线重新检查 no-SRI 主线，并复核 latest 40-2 诊断日志。12 次 ng-DSSR exact 累计约 `6.488s`，其中初始化 `6.065s`；初始化内部 completion bound 为 `5.472s`，约占 exact 总时间 `84.3%`。forward/backward 扩展合计约 `0.316s`，join 约 `0.088s`。同一个 root 的整轮求解中，HeuristicPricing 为 `26.026s/50`，ng-DSSR exact 为 `6.641s/12`。因此当前 40-2 的第一热点是启发式固定邻域扫描，exact 内第一热点是 completion bound；source-aware dominance 和 group-envelope join 已把 join 压到次要量级，不应继续优先修改。

困难 W300/W1000 的 no-negative certificate 是另一种口径。已有 409 次 exact、1535 个 DSSR round 的统计中，initialization/forward/backward/join 分别占约 `25.2%/26.7%/36.4%/11.5%`；15 轮以上的闭合调用在 ng-set 扩大后由前后向状态穷尽主导。这里继续缩小 DSSR 轮数比常数级 join 优化更重要，已有静态实验支持小到中等 nearest 初始化配合 top10 更新，但该结论仍需跨实例验证，不能把单个 W300 的 K 值写死为全局最优。

代码层面修正两处严格边界。第一，time-indexed paper graph fixing 的增量 local bitset 必须继续服从 `debugIgnorePricingOnlyArcsAtNode`：诊断节点关闭 pricing-only arc 时，本轮刚生成的 fixing 也不得参与传播，只在结束时写回给后续节点；生产默认路径不受影响。第二，completion-bound multi-delta 队列弹出后，只有确实存在新下降区间才复制“已传播快照”；空 delta 直接跳过，避免创建一份立即丢弃的完整 PWLF。该调整不改变 current/propagated 的函数值、队列状态或 fixed point。

剩余优化优先级已经比较清楚。启发式 `findBestMove` 的 ADD/EXCHANGE 仍是整轮主热点，但现有无临时 PWLF 标量内核、primitive 只读视图、普通弧扁平表和 dual 快照已经去掉明确实现冗余；再降低时间需要改变 seed、iteration 或邻域扫描语义，必须做完整求解 A/B。completion bound 的 F/B sparse-delta 传播仍是 exact 初始化热点，真正可能有量级收益的下一步是原生 interval delta，避免用含 BigM 空洞的临时 PWLF 复用通用 shift/add/normalize；这是数据结构级修改，必须逐 job 对拍完整 F/B/U/R，不能作为小补丁。无 SRI 的容器初始化、重复统计清零、诊断开启时的全列扫描等仍有少量冗余，但默认关闭或仅为微秒/毫秒级，不值得增加主线分支。join、candidate pool、active-label 压缩和 source-aware dominance 暂未发现新的高收益等价删除项。

验证使用当前全部 `Basic/Common/HEU/Output/TWETBPC` 源码和 CPLEX/CP Optimizer jar 完整编译；`TimeIndexedGraphOptimizationTest`、`CompletionBoundPreparedBoundsCompatibilityTest`、新旧 dominance graph consistency、`NgDssrSameNodeWarmStartTest` 均通过。`SmallBPCBatchTest` 的 8/8 小规模算例与 ArcFlow 完全一致，tariff 分支例也 `valid=true`。

240. 2026-07-16 completion bound 原生 interval delta 与 DSSR top-K 跨轮关系

当前 multi-delta completion bound 只传播真正下降的多个时间区间，但旧实现仍把这些区间之间的未变化区域显式填成 BigM segment，再对该临时 PWLF 执行通用 `shiftX -> directional normalize -> add penalty -> normalize`。本轮新增原生 interval-delta 路径：delta 只保存真实下降区间，内部空洞保持隐式；对每条普通弧完成平移和定义域裁剪后，再执行 prefix/suffix minimum 并补齐对应方向的等待闭包。这里不能把 delta 在弧外预先 normalize 一次，因为 `shiftX` 会按原函数 metadata domain 裁剪，弧相关平移与等待闭包不交换。backward 新增等待段使用现有 SegmentPool，避免把节省的 segment 又变成短命对象。系统属性 `twet.bpc.completionBoundNativeIntervalDelta` 默认开启，显式设为 false 可完整回退旧 BigM-hole 路径。

正确性验证覆盖三个层次。focused 编译和 `CompletionBoundPreparedBoundsCompatibilityTest` 通过；SmallBPC 的 8/8 ArcFlow 对照与 tariff 分支均为 `valid=true`；更重要的是在 `wet050_003_3m_setupR50 + W300` 的 K5/top10 root-only 配置上打开 `completionBoundMultiDeltaCompare`，8 次 exact pricing 的每次构造都逐 job 比较完整 F/B/U/R，全部输出 `result=equal`。独立 ON/OFF A/B 的 root bound 均为 `1726.014329`，exact 调用数、DSSR 轮数、候选规模和最终有效性一致，不是只比较最终 scalar minimum。

同批次性能如下。旧 BigM-hole 路径 root 为 `58.537s`，exact `18.012s/8 calls`；原生 interval 路径为 `57.657s` 和 `17.148s/8 calls`。8 次 completion-bound build 累计由 `6534.960ms` 降至 `5959.350ms`，约减少 8.8%；F/B 传播由 `4952.416ms` 降至 `4490.801ms`，约减少 9.3%；candidate 第一遍 directional normalize 由 `788.555ms` 降至 `559.072ms`，约减少 29.1%；全部 candidate shift/normalize/add 热步骤由 `3124.868ms` 降至 `2758.260ms`，约减少 11.7%。最终 exact 约快 4.8%，root 总时间约快 1.5%。因此这项优化有稳定的中等局部收益，但不是数量级改善；主要价值是删除 BigM 空洞上的无效 segment 工作。

同时扩展了默认关闭的 `ngDssrRoundRouteRelation` 诊断：除原 top1 外，记录本轮实际选中 top-K 中有多少条已存在于上一轮候选全集，以及它们在上一轮的 reduced-cost 名次。该统计不改变生产更新逻辑。K5/top10 的最后 no-negative certificate 共 5 轮：r1 从 89 条 unique 负非基本候选中更新 49 个 pair；r2 的 top10 有 9 条来自上一轮，原名次为 `11,12,13,16,17,NA,20,21,22,23`；r3 为 10/10，原名次主要在 `11--25`；r4 为 9/10，原名次主要在 `13--22`；r5 才无负 relaxed route。平均 ng-set size 从初始 5 依次升至 `5.98, 6.82, 7.58, 8.72`，最大值升至 15。结论很明确：top10 更新本身有效，上一轮已选 top10 基本被排除；多轮的原因是上一轮第 11--25 名顺次上浮，而不是被更新的原 top10 继续残留。

为验证能否直接扩大，完全同配置只把 update limit 改为 top20。最后 certificate 从 5 轮降到 4 轮，单次困难 exact 从 `5.715s` 降至 `4.864s`；但更新 pair 从 186 增至 194，final 平均 ng-set 从 8.72 增至 8.88，完整 root 的 exact 几乎不变：top10 为 `16.057s`，top20 为 `16.052s`，总时间也只有 `54.873s -> 54.720s`。top20 的下一轮候选同样主要来自上一轮第 21--52 名，说明固定扩大只是在移动 cutoff，同时让后续 labeling 使用更大的 ng-set。当前不修改默认 top10。

若继续做更新策略，合理方向不是全局固定 top20，而是困难轮自适应的较宽 reservoir：当一轮没有 elementary 列时，先更新 top10，再在同一轮已保存的第 11--20 名中检查哪些仍未被新增 pair 排除，只对存活者补充更新，然后才重新 labeling。该方案可能消除“第 11--25 名依次上浮”，又避免所有正常一轮 exact 都使用 top20；但它要求生产候选池保留比实际更新数更宽的 reservoir，并需统计 survivor 数和新增 pair，当前单例 top20 没有净收益，暂不实现。

241. 2026-07-16 completion-bound 固定侧函数查询优化

当前 ng-DSSR 扩展剪枝会把大量动态 label frontier 与同一 job 的固定 completion-bound `U/R` 函数反复求逐点和的最小值。旧实现每次都从两条 PWLF 链表头开始推进；W300 的最终 certificate 中 scalar check 后仍有数十万次完整函数查询，固定 `U/R` 的链表定位被重复执行。本轮只优化这一查询：`Bounds` 对每个 job 的 `U/R` 延迟构造一次 immutable primitive segment view；查询时动态 label 仍走原链表，固定侧用 segment-end 二分定位首个重叠段，再以双指针计算完全相同的端点最小值。completion bound 的构造、定义域相交判断、cutoff、等待闭包和 pruning 结论均未修改，dominance 存储也未改动。

新增 `twet.bpc.completionBoundFlatFunctionQuery`，默认开启，设为 `false` 可完整回退原双链表查询。随机测试对 500000 组错位定义域、最多 30 段、BigM 段和非零纵向平移分别比较“右侧固定”和“左侧固定后交换求和顺序”，均与旧查询一致；`CompletionBoundPreparedBoundsCompatibilityTest` 同时通过。固定 view 只属于一次已构建完成的 `Bounds`，不跨 completion-bound 重建复用，因此不存在 PWLF 修改后的 stale snapshot。

实际 A/B 使用 `wet050_003_3m_setupR50 + dueWindowHalfWidth=300` 的 root-only `nearestK5/top10 + source-aware dominance + join-envelope prefilter + native interval delta` 配置，关闭 ALNS、strong branching、RMIH、SRI 和重型逐轮诊断。两轮正反顺序结果中，OFF exact 分别为 `32.803s/34.786s`，ON 为 `23.095s/24.849s`；平均由 `33.794s` 降至 `23.972s`，减少约 29.1%。平均 root 总时间由 `90.209s` 降至 `76.518s`，减少约 15.2%。四组均为 `bound=1726.014329`、`pricing=86`、`generated columns=22887`、`pool=22887`、`exact calls=11`、`valid=true`，DSSR 和入列轨迹未改变。该结果说明固定侧二分定位确实消除了高频重复链表扫描，收益足以保留。

提交后再次按控制流复核 fixed view 生命周期和边界语义。`U/R` 只在 `CompletionBoundCalculator` 构造、聚合和辅助 bound 重建阶段写入，外部 arc fixing、subtree eliminator 和 pricing 仅只读；每次 `solve()` 开头会清空 reusable bounds，同一次 DSSR 的后续轮才复用，因此 lazy view 不会跨函数修改存活。normal、list-partial 和 graph-partial pricing engine 都实例化同一个 ng-DSSR 主体，本次替换对三种 backend 的 completion-bound 查询同样生效，但不接触 dominance store。backward 查询交换了 prefix 与动态 label 的位置，只利用逐点加法交换律，定义域交集和 cutoff 仍在原位置判断。新增 hull 相交但真实 segment 不相交、一侧空函数及端点相接用例；扩大到 2000000 组随机双向 fixed-view 对拍均通过。未发现 correctness 问题，本轮只修正 `ReadOnlySegmentView` 的旧“启发式专用”注释。


242. 2026-07-16 fixed-view 逻辑的全局复用边界

本轮全局扫描了生产代码中的 PWLF 标量查询、join、completion-bound arc fixing、route enumeration prepared bound、启发式成本评估及旧 pricing 后端。该优化成立需要同时满足三个条件：一侧函数在一批查询期间不变；同一固定函数会被多次查询；调用方只需要最小值而不需要生成完整 PWLF。不能把结论简化为“数组比链表快”。如果函数会原地更新、每次只查询一次，或者后续还需要完整 segment 输出，构造 primitive snapshot 反而会增加复制和失效维护。

当前 ng-DSSR 主线中，收益最大的 completion-bound `U/R` 高频查询已经覆盖，normal、list-partial 和 graph-partial backend 共用同一个实现。完全相同且严格可复用的位置是 `CompletionBoundSubtreeArcEliminator.PreparedBounds.canPruneForwardLabel()`：route enumeration 开启 prepared completion-bound 剪枝时，动态 enumeration frontier 会反复查询固定 `R_j`，可直接复用 `Bounds.backwardRView(job)`；该组件当前默认关闭，因此属于低风险但非当前主热点的补齐项。

当前主线最值得独立 A/B 的位置是 label-level join。循环顺序是固定一个 backward label，再扫描多个 forward label；`backward.joinExtendedFrontier` 在首次构造后会缓存且 join 阶段不再修改，因此可以为 backward 侧延迟缓存 primitive view，并增加支持横向位移的“动态 forward + 固定 backward”标量求和查询。W300 重轮单次 exact 可有约 `460297` 次真实 join function evaluation，说明调用量足够大；但 group-envelope prefilter 已先删除大量 pair，最终 certificate 中真实 label join 甚至可能为 0，所以必须按完整 root A/B 决定是否保留，不能仅看微基准。相邻候选是 group-envelope prefilter：困难 certificate 可有约 `150947` 次 traced-envelope 标量拼接、约 `439ms`；可以给 traced envelope 增加不含 source 的 value-only primitive view 用于预过滤，真正生成列时仍保留原 source trace。该项局部收益上限更明确，但不会改变前后向扩展主导的困难轮。

completion-bound arc fixing 也满足“一侧可固定”的结构，但量级较小。50-job 每轮只有 `2450` 条普通弧函数复核，最新 W300 日志约 `19--35ms`；可以增加 shifted fixed-view overload 并缓存 `F/B` view，跨多节点累计可能节省少量时间，但优先级低于 join。旧 `GCNGBBStyleBidirectional`、full-domain 和旧 partial 类仍有 `shiftX/add -> findMinimal` 或 `add -> findMinimal` 的临时 PWLF 写法，改成现有标量 helper 可以严格等价地减少分配；这些类不在当前 ng-DSSR 主线，除非重新启用旧 backend，否则不应为其扩大生产改动面。

PWLF 底层其余操作不适合直接复用本次逻辑。`CompletionBoundCalculator` 的 F/B 传播、`mergeMinimum`、dominance envelope、`shiftX/add/normalize` 都必须产出或更新完整函数，而且参与运算的函数持续变化；fixed view 会立即失效，不能消除核心 segment 构造。`findMinimal()` 全局缓存也不合适，PWLF 存在多处原地 segment 修改和 SegmentPool 复用，完整失效协议的复杂度高于收益。`findMinimalInRange()` 可以对固定函数做二分起点查询，但当前 join range-LB 默认关闭，而且同一 pair 的动态侧仍需扫描，暂不应优先实现。启发式 ADD/EXCHANGE 已经使用 primitive 只读 profile 和无临时 PWLF 标量内核，不存在同类遗漏。

当前建议顺序为：先对 join 的固定 backward view 增加复用次数/segment 数统计并做完整 W300 A/B；若净收益稳定，再考虑 group-envelope value-only view。route enumeration prepared bound 和旧 backend 只在对应功能重新启用时补齐；arc fixing 的 shifted view 作为低优先级常数优化。结论是该思路还能复用，但只能用于“固定侧、多次、只求标量”这类调用，不能作为 PWLF 全局数组化或统一替换策略。


243. 2026-07-16 启发式之后的剩余热点复核

使用 fixed-view 优化后的 W300 root-only 日志重新累计 11 次 exact pricing：总计约 `24.757s`，其中初始化 `7.523s`、forward `5.954s`、backward `7.629s`、join `3.515s`。初始化几乎全部是 completion-bound 构造；困难 no-negative certificate 内 completion bound 已在 DSSR 轮间复用，后续时间转为正反向 labeling 和 join。当前 root 的启发式 pricing 仍为 `46.368s/75 calls`，但 ADD/EXCHANGE 已使用无临时 PWLF 的 primitive 标量内核，没有新的明显实现冗余。

本轮找到一处比固定 backward view 更直接的 join 冗余。group-envelope prefilter 已按 backward group 和 terminal job 生成 `BitSet`，标记全部可以整组剪掉的 forward label；但 `joinForwardGroupWithBackward()` 仍按 `i++` 扫描完整候选列表，再对每个置位元素执行一次 `BitSet.get(i)` 后跳过。最终 certificate 中 `join pairs tried=0`、真实 `funcEval=0`，但仍访问约 `3.298m` 个候选，其中约 `3.235m` 已被 group prefilter 标记，join 阶段仍耗时约 `2.007s`。可严格保持原候选顺序，使用 `BitSet.nextClearBit()` 直接跳到下一个未剪候选；这不改变 group 判定、BEST_UB 阈值、真实 pair 顺序或返回列，只需调整统计口径。该项应优先于给每个 backward frontier 建 primitive view，因为 certificate 轮根本没有进入真实函数拼接。

除该项外，剩余方向都属于中等或较小收益。completion-bound 构造仍占普通 exact 的约 30%，但原生 interval delta 和固定 U/R 查询已经覆盖最明确冗余，继续优化需要重写 F/B/U/R 的 segment 存储或传播内核。困难 certificate 的正反向扩展约占 exact 的 55%，主要成本来自更多 survivor label、source-aware envelope merge 和多轮 DSSR 状态规模，不是 queue、位集或单个函数调用；此前 threshold-mask reachability 完整 A/B 仅节省 exact 约 0.34%，已撤回。固定 backward view、group-envelope value-only view和 completion-bound arc fixing view 仍可做常数优化，但预计都低于“跳过已知 BitSet 区间”。master LP、arc fixing 和统计在该 root-only 口径下均不是当前热点。

244. 2026-07-16 group-envelope BitSet 连续剪枝跳跃

前一节确认 group-envelope prefilter 已经为每个 backward group 和 terminal job 缓存可剪 forward label 的 BitSet，但旧 join 仍逐项读取这些 label、检查 dominance 和 scalar lower bound，再执行 BitSet.get(i) 后跳过。困难 W300 certificate 中真实 funcEval=0，仍可能扫描数百万候选，因此本轮把已知可剪的连续 set-bit 区间改为 nextClearBit(i) 直接跳到下一个未剪候选。未被 BitSet 标记的候选顺序、lower-bound 检查和 tryJoin() 完全不变；该路径只在 no-SRI group-envelope prefilter 启用时生效。系统属性 twet.bpc.ngDssrJoinPrefilterSkipRuns=false 可恢复旧逐项扫描，默认启用新路径。

正确性依赖两个已经存在的不变量。第一，BitSet 中的 set bit 只来自安全的 group-envelope 非负证书，因此逐项路径本来也一定 continue。第二，forward candidates 在 join 前按 minReducedCost 升序排序；即使连续跳跃跨过了旧路径本会触发的 scalar-LB break，后面的首个 clear candidate 也不可能具有更小的 scalar lower bound，会在原检查处停止，不会额外进入 tryJoin()。BitSet 构造后 active label 列表在本轮 join 内不再变化，因此索引不会失配。新统计口径中 join candidates visited 只计真正读取的 label，逻辑上被连续跳过的数量仍完整计入 joinEnvelopePrefilter skippedPairs。

使用 wet050_003_3m_setupR50 + dueWindowHalfWidth=300、root-only、nearestK5/top10、no ALNS/no strong branching/no SRI 的相邻 A/B 共运行两轮正反顺序。四次运行的 8 次 exact 加列序列均严格为 5000/1742/1067/74/41/6/1/0，每轮 pool、DSSR rounds、accepted best reduced cost、最终 bound=1726.014329 和 valid=true 全部一致。第一轮 OFF/ON 的 exact 为 15.572s/14.864s，join phase 为 1.947s/1.719s；反序第二轮受整机负载影响，ON/OFF 的 exact 为 25.441s/18.505s，且 initialization、扩展和 join 同时整体变慢，不能据此声称稳定的端到端提速。按每轮 join phase 相对同轮 forward+backward 时间归一化，两次 OFF 平均为 24.39%，两次 ON 平均为 23.14%，约下降 5.1%。因此该修改作为代码量很小、语义等价、默认启用且可回退的常数优化保留，但不把它记录为稳定的大幅提速。

另尝试并行关闭启发式运行 20-job smoke，两个 exact 都进入不具代表性的长尾，约 70 秒后主动停止，未写入 CSV，也不纳入性能结论。focused Java 22 编译通过，W300 四次运行均通过解验证。

245. 2026-07-16 group-envelope 连续跳跃正确性复核

提交后再次沿实际控制流检查连续 BitSet 跳跃。`ZERO`、`BEST_UB` 和 `BEST_RECORD` 三种 join 口径下，动态 join threshold 只可能等于 `-1e-6` 或比它更负；group-envelope prefilter 只在 envelope 下界不小于 `-1e-6` 时置位，因此所有 set bit 对三种口径都可安全跳过。forward candidates 在建索引前已完成 dominated label 压缩和按 `minReducedCost` 升序排序，BitSet 按该固定列表下标构造，join 期间列表不再修改；缓存同时按 backward envelope group 和 terminal job 隔离，不存在跨组或排序后的索引复用。

新循环跨过旧循环可能触发 scalar-LB break 的 set-bit 区间时，也不会多生成列：若后面仍有 clear candidate，由于 forward `minReducedCost` 单调不降，它会在同一 lower-bound 检查处 break；若后面没有 clear candidate，则直接结束。SRI/full-SRI 路径由 `useJoinEnvelopePrefilter()` guard 排除，forward-to-sink 与 envelope-compression 路径也未改动。诊断口径唯一变化是连续跳过的 label 不再计入实际 `visited/dominated`，而计入 envelope potential-pruned；这不参与算法判断。使用 `target/classes` 和隔离 sourcepath 的 focused Java 22 编译通过，未发现 correctness 问题或新的冗余处理。

246. 2026-07-16 当前剩余的算法级优化方向

继续沿当前 no-SRI 主线、最新 W300 日志和 `PC` 的真实调度检查后，暂未发现新的高收益底层冗余。PWLF 标量拼接、source-aware dominance、completion-bound interval delta、单 word 位集和 group-envelope 安全预剪枝已经覆盖当前最明确的实现热点。剩余机会主要不在单次 `shift/add/merge`，而在减少“少量加列后立即重解 RMP、随后用相近 dual 再做一次完整 exact”的往返。

第一优先方向是同一 dual 内继续 DSSR 并批量累计基本列。当前 `solve()` 每轮 relaxed pricing 后，只要找到至少一条 elementary negative column，就立即返回；即使同一轮同时观察到大量负的非基本列，也不会更新 ng-set 后继续。本次 W300 日志的后期 exact 依次只返回 `74/41/6/1` 条基本列，但同轮仍分别观察到 `281/243/114/91` 条负非基本 witness；每次返回后 PC 都重解 RMP，下一次 exact 又重新构造约 `0.6--0.8s` 的 completion bound。可增加独立实验模式：在同一 LP dual 下累计已经找到的基本负列；若数量未达到固定 batch target，且本轮仍有负非基本 witness，则按现有规则更新 ng-set 并继续下一 DSSR round；达到 target、没有非基本 witness、时间到或完成证书时才返回。累计列按 sequence signature 去重，并继续沿用真实成本和当前 dual reduced-cost 复核。ng-set 增强不会删除任何 elementary route，因此之前找到的基本列始终有效；时间到时只返回已确认的负列，不输出闭合证书。该方向的收益来自把多个小批次 exact/RMP 往返合并为一次同-dual 批处理，而不是跨 dual warm-start。第一轮 A/B 应使用简单固定 target，例如 `300`，不引入复杂自适应。

第二优先方向是扩大非基本候选 reservoir，但对每条 route 只选择一个最便宜的完整重复段进行 ng 更新。当前 top-K 更新会扫描候选 route 的所有重复段，并把每个重复段中全部缺失 pair 都加入 ng-set。要禁止一个具体重复段 `i ... i`，确实必须让所有中间 job 都记住 `i`；但若一条 route 含多个重复段，只禁止其中任意一个完整重复段，就足以禁止该 route 原样再次出现。因此可保留前 `20--25` 条负非基本候选，按 reduced cost 顺序处理；对尚未被本轮 overlay 排除的 route，选择“所需新增 pair 数最少”的一个重复段，只加入该段全部缺失 pair。这样有机会提前挡住此前会从上一轮第 `11--25` 名上浮的候选，又避免 top20 旧实验中因全段更新造成 ng-set 变大、单轮 labeling 变重。实现前先做纯诊断：比较 top10 全重复段更新与 top25 最小完整段更新各自新增 pair 数、实际挡住的候选数和预测 ng-set 大小，再决定是否 A/B。只加一个 pair 不能保证禁止整条重复段，因此不采用单 pair hitting-set 口径。

第三优先方向是 PC 层固定小批次合并。当前 pricing engine 按顺序执行，某个 engine 一旦实际加列，本轮立即返回并重解 RMP。因此启发式只加 `1--10` 条列时，后面的 exact 不会在同一 dual 下继续。可以实验固定规则：启发式返回少于某个很小阈值时，继续执行 exact，并把两者列合并后只重解一次 RMP。该做法不影响列正确性，但可能在 dual 即将明显变化时浪费一次 exact，且触及整个 PC engine 调度，优先级低于只修改 ng-DSSR 内部的同-dual批处理。

已有证据不支持把 dual smoothing、历史 warm-start或继续静态放大 top-K 作为当前优先策略。50-2 全程 smoothing 曾把 root exact 从约 `118s` 放大到约 `1326s`，原因是平滑 dual 使 pricing 状态更稠密；warm-start 对变化后的 dual/window 不稳定；top20 虽把困难 certificate 从 5 轮降到 4 轮，但完整 root exact 几乎不变，因为更大的 ng-set 抵消了轮数收益。后续最合理的实验顺序是：先实现“同一 dual 累计到固定 elementary batch target”；若收益不足，再诊断“top25 + 最小完整重复段”；最后才考虑 PC 层启发式与 exact 的固定小批次合并。