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
