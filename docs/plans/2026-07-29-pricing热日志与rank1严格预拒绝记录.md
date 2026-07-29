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
