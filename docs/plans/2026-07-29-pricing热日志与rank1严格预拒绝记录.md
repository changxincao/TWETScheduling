# Pricing 热日志清理与 rank-1 严格预拒绝

本轮继续检查 time-indexed、rank-1 和 ng-DSSR 的高频诊断开销，并单独 A/B 两项可能改变实现顺序的优化。

1. `TimeIndexedGraphPricingEngine` 的状态数、普通弧扫描数、时空禁弧跳过数、负状态数和重复候选数改由
   `twet.bpc.timeIndexedPricingDiagnostics` 控制，默认关闭。exact 模式不再仅为重复候选计数扫描 sequence；
   pre-heuristic 仍必须执行重复任务检查，因此 elementary 过滤语义不变。
2. `TimeIndexedGraphRank1CutPricingEngine` 的旧 labels、arc scans、completion-pruned 和重复候选统计改由
   `twet.bpc.rank1PricingDiagnostics` 控制，默认关闭。deferred packed 诊断仍由独立属性控制。
3. ng-DSSR 的 trace 调用在调用点先判断 `targetTrace`，未启用目标序列 trace 时不再构造
   `F_INSERT_*`、`B_INSERT_*` 等动态字符串。只进入 message/reset 的 extension candidate、join candidate 和
   join pair/function 计数改由 `twet.bpc.ngDssrHotPathDiagnostics` 控制；Tmid 反馈依赖的 pops、kept、
   constructed、bound survivor 和 completion-pruned 计数保持原样。诊断关闭时，message 明确输出
   `hotPathDiagnostics=off`，不再把未维护的计数显示为真实的 0。
4. rank-1 exact 的 packed forward candidate 新增严格 dominance 预拒绝。它只在 completion suffix 扫描前
   检查当前同 bucket 的已有 label，并要求 corrected bound 至少严格优于 `RC_TOLERANCE`；因此该谓词是正式
   dominance 的真子集。heuristic、legacy residual 和 packed verification 路径不变。开关
   `twet.bpc.rank1StrictCompletionPreDominance` 默认开启，可显式关闭。
5. 40-2 rank-1 root 的严格预拒绝 on/off 均得到 bound `26969.611607`、pool `42177`、95 条 active cuts、
   260 次 pricing 和 `valid=true`。去掉耗时后，260 条 pricing 事件逐行零差异；开启后 84 次 exact pass
   共提前拒绝 `56,519,397` 个必然不会入桶的候选。65-cut multiword Phase-I 回归也通过。
6. SRI row 的 `addTerms()` 批量写入没有保留。80 行、20000 列、每行 5000 个非零项的交错 A/B 中，
   batch/逐项三组约为 `76.972/63.935 ms`、`72.395/66.392 ms` 和 `98.648/106.312 ms`，没有稳定收益；
   额外数组分配不值得进入主线。

编译覆盖 `Basic/Common/HEU/Output/TWETBPC` 共 173 个 Java 文件。time-indexed、Phase-I、多 word rank-1、
ng-DSSR join、source-aware dominance、SRI coefficient/posting、active-memory merge 和 cut inheritance
回归均通过。ng-DSSR hot diagnostics 的并行 wall-time 对照不能作为轨迹等价证据，因为当前 midpoint probe
本身按实测时间选择 Tmid，两个竞争进程会产生不同反馈；字段引用审计确认本轮关闭的计数只进入 message/reset。

## 二次正确性审计

再次逐字段检查后确认，被 `HOT_PATH_DIAGNOSTICS`、`PRICING_DIAGNOSTICS` 控制的字段只用于递增、清零和
日志输出，不进入 Tmid、DSSR 更新、剪枝、候选排序、定价证书或返回列判断。time-indexed pre-heuristic 的
elementary 检查仍无条件执行；只有 exact 模式下原本仅服务计数的重复序列扫描被跳过。

rank-1 严格预拒绝与正式 packed dominance 使用同一 residual 修正项。正式判断允许
`existingRC <= correctedCandidateRC + RC_TOLERANCE`，预拒绝只接受
`existingRC <= correctedCandidateRC - RC_TOLERANCE`，因此严格属于正式 dominance 的子集。exact 模式先完整
构造 backward labels，最终 concatenation 也会再次枚举保留的 forward label 与同一 suffix；被已有 label
严格支配的候选不可能改善列集合或 `bestPseudoReducedCost`。

本次还修正两个纯诊断遗漏：full-midpoint 诊断关闭热统计时不再把 `fCand=0` 当作真实统计输出；forward sink
禁弧路径在未启用目标 trace 时不再调用 trace helper。173 个主线 Java 文件重新编译通过，time-indexed、
rank-1、ng-DSSR join 和 source-aware dominance 在诊断开关关闭与开启口径下均通过回归。

## Primal 批量读取与后续建议复核

本轮继续逐项复核 primal 提取、rank-1 bucket 扫描和 ng-DSSR SRI 备用路径。

1. `LP.readColumnValues()` 改为一次 `cplex.getValues(lambdaVars)`，再按原 restricted-column 顺序筛选正值。
   40-2 纯 time-indexed root 的 scalar/batch 对照均为 bound `22487.647059`、211 次 pricing 和
   `valid=true`。210 次 after-pricing resolve 的 extract 累计由 `960.872ms` 降到 `542.580ms`，
   下降约 43.5%。跳过中间 primal 提取没有实现，因为 `HeuristicPricingEngine` 会读取当前正值列，
   该改动不能无条件推广到所有 pricing engine。
2. 尝试将 rank-1 strict/formal dominance 合并为一次分类扫描。修正 heuristic 不应进入该分类后，
   old/fused 的 275 条 pricing 事件逐行一致；exact 中确实可跳过约 353 万次第二遍 existing-dominates
   扫描。但连续同机复测 exact 为 `9.216s` 和 `9.385s`，没有净收益。原因是分类仍需继续扫描 bucket
   以区分 strict 与 formal，抵消了少一次扫描的收益，因此该实验代码已完整撤回。
3. ng-DSSR SRI label 的 `sriStateKey` 从未被任何 dominance 或 join 读取，已删除字符串字段、构造和接口；
   SRI dominance 继续直接读取不可变 `byte[] sriCounts`。trace 事件额度耗尽后，调用点也会立即停止序列
   恢复和动态字符串构造。
4. `noSriFrontier` 暂不删除。虽然代数上始终满足
   `frontier(t)=noSriFrontier(t)+sriPenalty`，但两套函数分别经过 shift/normalize；改为常数回推会改变
   浮点断点归一化轨迹，而且只影响 SRI + LIST_PARTIAL 备用路径，当前收益不足以承担该风险。
5. 其余 envelope、completion scalar 和 depth/message 计数暂不继续清理。部分字段仍用于 Tmid 或完成界
   诊断，纯展示字段的数量级收益很小；默认热路径中最明显的 trace 配额遗漏已经处理。

最终 173 个主线 Java 文件编译通过，time-indexed、strong Phase-I、ng-DSSR 边界、source-aware dominance、
外包正值列缓存和 SRI posting 回归通过。一次测试命令因包名写错未找到类，改用正确类名
`TWETBPC.LP.SubsetRowColumnPostingIndexTest` 后通过，不属于代码失败。

## 提交后正确性复核

再次沿生命周期检查后确认，`restrictedColumnIds` 与 `lambdaVars` 在完整建模时按同一顺序创建，增量加列时
只对 membership set 新接受的 ID 同步追加变量，筛列后则同时替换 ID 列表并重建模型。因此
`cplex.getValues(lambdaVars)` 的返回下标与原逐变量读取的 column ID 映射严格一致；空变量数组继续由显式
分支处理。当前所有 restricted ID getter 调用者均为只读，没有外部修改列表破坏该不变量。

同时发现第一次 trace 配额修正只覆盖了前后向扩展入口，join、候选列和 partial-trim helper 在配额耗尽后仍
可能通过 `targetTraceSequence` 恢复序列。本次将所有纯 trace 判断统一接到 `isTargetTraceActive()`：最后一个
额度仍正常写入，之后不再恢复 join/column/trim 序列或扩展 watched-label 链。显式
`targetTraceProtectTarget` 的 trim 保护判断保持独立，不因日志额度耗尽而改变。正式求解在未设置 trace 属性时
始终走短路分支，算法语义不变。

173 个主线 Java 文件重新编译通过；restricted-column membership、time-indexed、strong Phase-I、
ng-DSSR 边界、source-aware dominance、SRI posting 和 active-cut inheritance 七项回归通过。
## 2026-07-29 后续读取与候选物化审查

本轮只做静态审查，未修改算法代码。下一批值得处理的项目有三项。第一，`LP` 仍逐行读取 coverage、branch 和 cut dual，并逐变量读取外包列、逐任务外包变量和 tariff segment；`isIntegerSolution()` 又会重新读取外包列或 segment。应批量读取各行族和变量族，并把同一批原始 primal 同时用于聚合外包量、记录正值外包列和判断整数性。这里不能只用逐任务聚合外包量判断列化外包整数性，因为多条分数列可能聚合为整数覆盖。第二，列化外包筛列仍逐列读取正值和 reduced cost，可严格复用最近一次 LP 解保存的正值外包列集合，并一次读取全部外包列 reduced costs。第三，rank-1 候选在签名去重和 top-K 确认前就构造 `ColumnPattern` 和 `TWETColumn`。签名仍是判重所必需，但 sequence 副本、job bitset 和 visit counts 可延迟到最终保留候选再构造。现有日志中单轮 `negativeStates` 可达数万而最终只返回 300 条，说明该项具备真实减少分配的空间，适合独立 A/B。

其余建议暂不进入主线。`concatenateLabels()` 的序列缓存只能省 predecessor 链回溯，最终拼接列表仍需复制，而且当前只在负 reduced cost 且通过 top-K 门槛后恢复序列，应先统计同一 label 的恢复复用率。启发式 seed 收集已有分阶段计时，历史结果显示通常仅为毫秒级，主耗时仍是 move 的 PWLF 评价；跨 pricing 缓存兼容性还需要处理 node domain/pricing-only arc 变化，不值得增加失效协议。SRI `LIST_PARTIAL` 可在计算 compensation 前按原 frontier 端点短路无重叠比较，该变换严格安全但只影响备用路径，优先级很低。`refreshMinReducedCost()` 在 partial trim 后仍负责重算完整函数最小值，不能删除。

## 2026-07-29 LP 批量读取与 rank-1 候选延迟物化

本轮完成前述三项主线优化。`LP` 将 coverage、branch、adjacency 和 active-cut 行按各自原迭代顺序批量读取 dual；内部列、显式外包变量、列化外包变量和 tariff segment 也改为按变量数组一次读取。列化外包仍同时检查逐任务聚合值和每条原始外包列的整数性，避免多条分数列聚合成整数覆盖后被误判。strong-trial 外包筛列复用最近一次 LP 解记录的正值外包列，并一次读取全部 restricted outsourcing reduced costs。变量数组与 restricted ID 列表的同序不变量未改变，所有浮点累加顺序也保持原样。

rank-1 候选池现在只立即保存恢复出的 sequence、`SequenceSignature`、目标成本和 reduced cost。签名去重、候选替换、worst-first heap、top-K 阈值及 candidate ID 次序均未改变；只有最终排序后真正返回的候选才构造 `ColumnPattern`、job bitset、visit counts 和 `TWETColumn`。Phase-I 仍只对最终候选用 evaluator 刷新真实成本。三条候选入口得到的 sequence 都是新建列表，入池后不再修改，因此延迟持有不存在别名写入问题。

40-2 rank-1 root 的独立 before/after 结果完全保持 `bound=22527.007009`、275 次 pricing、46609 条列、peak cut pool 235 和 `valid=true`；rank-1 exact pricing 由 `15.163613s` 降至 `13.875196s`，约减少 8.5%，root 时间由 `37.798827s` 降至 `36.462446s`。总 solve 时间基本不变，因为该实验后半段由 strong-trial LP 主导。列化外包 SP1/SP2 三组 smoke test 的目标、bound、外包数量和内部列数逐组一致。173 个主线 Java 文件完整编译通过；strong Phase-I（含 65 条 cut 的 multiword packed 等价对拍）、time-indexed、restricted membership、active-cut inheritance 和 column-pattern sharing 回归通过。

序列恢复缓存、跨 pricing 的启发式 seed 缓存和 SRI `LIST_PARTIAL` overlap 短路均未接入。前两项没有足够热点证据且需要额外失效协议，后一项只影响当前非主线的备用 dominance store。`refreshMinReducedCost()` 继续保留。

提交后再次按正确性而非性能复核。CPLEX 批量 dual 数组与各 `HashMap` entry 快照使用同一顺序，coverage 的 `start=1,num=n` 与保留空位的 `coverRanges` 对齐；内部列和外包列变量数组分别与 restricted ID 列表同序。列化外包的整数性同时检查聚合 job value 和原始 column value，筛列使用的正值集合与 reduced cost 来自同一次 LP 解。rank-1 候选的三条 sequence 恢复入口均创建独立列表，heap 中被替换的旧候选仍按 map identity 惰性失效，Phase-I 仍只对最终 top-K 刷真实成本。40-2 before/after 日志抽取的 9 条算法轨迹逐行零差异；显式外包与列化外包三组 SP1/SP2 再次得到相同 objective、bound、外包任务数和内部列数。未发现正确性问题。

## 2026-07-29 普通 time-indexed 候选与强分支 top-K 后续判断

普通 `TimeIndexedGraphPricingEngine` 的候选延迟物化值得作为下一项独立 A/B。当前 sequence 必须立即恢复，才能执行 pre-heuristic 重复任务过滤和构造 `SequenceSignature`，因此不能省掉 predecessor 回溯；但 `ColumnPattern`、job bitset、visit counts 和 `TWETColumn` 可以延迟到最终 top-K。历史普通 time-indexed 日志中，单次 pricing 常见 `negativeStates=5.6万--6.8万`，最终只返回约 160--300 列，说明当前确实为大量后来被同签名替换或 top-K 淘汰的候选构造重对象。实现不能机械照搬 rank-1：dual-window recheck 和 Phase-I 本来就只在最终候选执行，应保持不变；dual-window best-candidate 诊断和 stabilization oracle 需要改为直接读取轻量候选的 sequence、cost 和 source。候选成本也只需在签名确认会进入或替换 active map 后计算。

强分支候选的有界堆不值得修改。`Tree` 传入 `Integer.MAX_VALUE` 是为了同时保留真实 `candidateCount`；若 brancher 只返回 top-K，就需要新增 batch/result 接口才能保留日志与评分语义。历史 49096 条 strong-branching 记录中，candidateCount 中位数 1、P90 为 115、P99 为 181、最大 275；排序最多几百个轻量对象，相比左右 trial LP 可忽略。Outsourcing brancher 最多只有 n 个候选，tariff brancher 更少。

两个 pricing heap 的 stale candidate 暂不增加统计或重建协议。旧候选只在同签名被更优值替换时滞留，随后一旦需要读取 worst candidate，较差的 stale root 会由现有 identity 检查惰性弹出；solver 结束后整套 heap 释放。普通引擎完成延迟物化后，stale candidate 也不再持有 `ColumnPattern/TWETColumn`，潜在内存成本进一步下降。最终 top-K 重建一次签名、dense `arcDual` 清零、snapshot 深拷贝及临时 map/range 数组仍属次要项，不继续增加缓存状态。

普通 time-indexed 候选延迟物化的独立 A/B 已完成并保留。sequence 仍在候选入口恢复，用于 pre-heuristic elementary 过滤和 `SequenceSignature`；active candidate 只保存 sequence、signature、图内成本、source 和 reduced cost，最终排序后才构造 `TWETColumn`。同签名旧候选不优于新值时，目标成本反推也一并跳过。dual-window recheck、Phase-I evaluator、best-candidate 诊断和 stabilization oracle 均改为读取轻量候选字段，原执行时点不变；dual-window oracle 若同时作为返回列，只物化一次。

40-2 no-cut time-indexed root 的 before/after 均得到 `bound=22487.647059`、211 次 pricing、45113 条列和 `valid=true`，9 条关键算法轨迹逐行零差异。普通 exact pricing 由 `2.678398s` 降至 `2.052505s`，约减少 23.4%；root 由 `9.091428s` 降至 `7.495770s`，约减少 17.5%；总 solve 由 `23.048539s` 降至 `21.510709s`，约减少 6.7%。173 个主线 Java 文件完整编译通过，time-indexed、Phase-I/multiword rank-1、restricted membership、active-cut inheritance、column sharing、SRI coefficient 和 posting 七项回归通过。
