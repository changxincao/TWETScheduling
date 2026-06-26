# 2026-06-26 route enumeration 与 dual bound 后续判断

## 1. 当前观察

50-2 zeroSetup 当前运行显示，dual bound 对“一个 node 继续加列但 LP bound 基本不动”的退化阶段有实际作用。它不需要完整完成列枚举，也不需要求有限整数主问题；只要 exact pricing 给出可用的 reduced-cost 证书，就能判断当前 node 即使继续闭合 LP，也不可能优于 incumbent，从而提前剪掉这类长尾节点。

route enumeration 的作用更依赖规模。40-2 上绝对 gap 接近 10 时，候选列数量还能控制在 10 万以内，枚举有机会完整结束并关闭节点；但 50-2 上同样量级的 gap 很容易触发 `routeEnumerationColumnLimit=100000`，例如 node 28 中 `finiteCols=2937`、`new=97064` 已经超过上限。这说明当前枚举不是理论上无效，而是触发条件对规模不够敏感。

## 2. 当前判断

短期不调整 route enumeration。当前保留其作为小规模或极小 gap 下的后处理增强；对 50 任务以上的实例，不能仅凭 `gap < 10` 判断值得枚举。后续若继续优化，应优先考虑 probe 或动态触发，而不是单纯提高列数上限。

dual bound 应继续保留为默认有效组件。它对长尾退化节点的收益比 route enumeration 更稳定，尤其适合那些还会产生少量负列、但主问题下界几乎不再改善的节点。

## 3. 后续方向

后续可以重点尝试异质机器实例。当前直觉是，函数占优和 ng-DSSR 这条路线在异质机器下仍应比 time-indexed pseudo-schedule pricing 更有优势，因为后者允许大量非基本路径和重复任务，规模变大后列池膨胀更严重；而当前方法仍保持 elementary/ng 路径结构和函数占优剪枝。不过这需要单独实验验证，不能直接由同质机器结果推出。

## 4. 50-2 zeroSetup OOM 复查

`tmp-wet050-001-m2-zerosetup-current-20260626` 没有正常结束，而是在 node 127 的 route enumeration 阶段 OOM。最后一个已完成节点为 node 126；node 127 在 true-dual pricing 闭合后进入枚举，当前 `incumbent=35045`、`lpObj=35036.2`，绝对 gap 约 `8.8`，当前 subtree arc fixing 没有新增固定弧，pool 规模已经约 `301518`。

OOM 栈位于 `RouteEnumerationEngine.canPruneByCompletionBound()` 调用 `PreparedBounds.canPruneForwardLabel()`，再进入 `PiecewiseLinearFunction.add()`。这说明爆内存不是 finite master 求解，也不是候选列数已经达到上限后的处理，而是枚举 child label 时反复用 `frontier + suffix bound` 构造临时分段函数。候选列上限只限制完整的 `rc < gap` 列数，不能限制中间前缀 label 数，也不能限制 completion-bound 检查里创建的临时 PWLF 段对象。

因此当前触发规则仍然偏宽。50-2 这种规模下，即使 gap 小于 10，枚举也可能先产生百万级扩展和大量 PWLF 临时对象，尚未到候选列上限就耗尽堆内存。短期更稳妥的做法是关闭 route enumeration，保留 dual-bound pruning；如果后续继续优化枚举，应优先增加轻量 probe 或动态跳过规则，例如先估计前缀扩展密度、候选列密度、completion-bound 全函数检查次数和 subtree fixing 强度，再决定是否正式枚举。另一个实现方向是让枚举里的 completion-bound 剪枝先用 scalar/cheap bound，只有极少数候选再做全函数 PWLF 检查，避免在热路径中大量构造临时函数。

2026-06-26 先做一个保守的临时参数收紧：代码中对 `n>=50` 的实例把 route enumeration 有效触发阈值改成 `min(routeEnumerationAbsoluteGapThreshold, 5.0)`。这样 40 任务已有对照不受影响，50 任务及以上只有在更小 gap 时才尝试枚举。这个改动不是最终命中策略，只是为了先避免 `gap≈8~10` 的 50 任务节点再次进入高风险枚举；后续仍应通过 probe 或动态触发规则判断是否值得枚举。
