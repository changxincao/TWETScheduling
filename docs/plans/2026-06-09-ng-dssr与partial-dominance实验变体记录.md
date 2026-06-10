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
