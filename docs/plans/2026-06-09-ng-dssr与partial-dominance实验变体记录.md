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
