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
