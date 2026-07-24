# ng-DSSR join 端点边界修复

2026-07-24 的 60-3 W100 并行实验中，ng-DSSR 在 node 29 构造 forward join envelope 时中止。异常为 `t out of domain: 1934.000001`，调用链落在 `valueAtOrNearest()` 对 forward frontier 的 `Tmid` 取值。

问题不是已确认的内部函数空档。当前直接证据是 `Tmid=1934.000001`，而半域函数端点来自整数时间窗，可能正好为 `1934.0`。原实现先用带 `EPS=1e-6` 的比较判断 `t` 是否位于整个函数首尾之间，再把原始 `t` 交给 `evaluate()`；浮点边界上，`t <= end + EPS` 和 `abs(t-end) <= EPS` 可能给出不同结果，因此外层放行、内层却找不到覆盖 segment。

修复保持原有“域外取最近首尾端点、域内直接评价”的语义，只把首尾判断改成真实数值比较：`t < head.start` 时评价 `head.start`，`t > tail.end` 时评价 `tail.end`，其余情况评价 `t`。没有修改 join envelope、半域裁剪、reduced cost 或内部空档处理。

新增 `NgDssrJoinExtensionBoundaryTest`，覆盖本次 `1934.000001` 对 `1934.0` 右端点、左端点和正常域内评价。完成 focused 编译和测试后，重新启动 60-3 W100 的 ng-DSSR/time-indexed 对照。

2026-07-25 focused 编译和回归测试通过，提交为 `581589fd`。随后复用上一轮完整 `args.txt`，仅替换实验名和输出目录，并行重启：

- ng-DSSR：`test-results/bpc/exp-60-3-W100-current-ng-endpointfix-20260725c`
- time-indexed：`test-results/bpc/exp-60-3-W100-current-ti-rerun-20260725c`

启动后已核对实际 JDK 子进程和完整命令行。两组均保持 W100、1800 秒、60 秒 ALNS、单 CPLEX 线程和 strong branching；ng-DSSR 继续使用 C1000/K20、新 dominance/group prefilter、completion bound 与 time-indexed root preprocessing/seed200，time-indexed 继续使用 dual-window exact graph pricing。

该次重启随后按用户要求立即停止。两个输出目录仅保留不完整的过程日志，不作为性能或最优性结论；端点修复本身的 focused 编译和独立回归结果不受影响。

2026-07-25 继续全局检查同类数值边界。除 ng-DSSR 主线的 join 延拓外，标准双向、partial dominance、旧双向对照实现仍保留相同的 `valueAtOrNearest()` 写法；各双向实现和 completion bound 的零长度单点裁剪也会先用 EPS 判定“在域内”，再把未经钳制的点交给严格 `evaluate()`。旧 dominance 点查询存在相同的端点风险，当前增量 source-aware dominance graph 自己按 segment 求值，不调用严格 PWLF `evaluate()`，不属于该问题。

本次把真实首尾端点钳制收敛到 `PiecewiseLinearFunction.evaluateAtClampedEndpoint()`，并将上述已确认入口统一调用该方法。距离定义域超过原有 EPS 的 dominance 查询仍返回 `big_M`；仅处于容差带内但物理上越过首尾端点的点才钳制。内部函数空档仍由严格 `evaluate()` 抛出异常，没有被端点修复掩盖。focused 编译、端点/内部空档回归、Paper dominance 一致性测试和增量 source-aware dominance 一致性测试均通过。
