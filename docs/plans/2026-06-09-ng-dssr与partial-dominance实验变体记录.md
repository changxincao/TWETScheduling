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
