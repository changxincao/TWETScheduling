# pricing reachable set 与 Tmid 半域裁剪语义复查

本次检查的问题是：双向 pricing 中 label 的 `reachableSet`，以及 ng-DSSR 中实际传给 dominance 的 `extensionSet`，是否混入了 Tmid 半域裁剪。结论是当前主线确实混入了，且这会影响 dominance key 的语义。

在 `GCNGBBStyleBidirectional` 中，`buildForwardReachableSet()` 同时要求 `isForwardHalfEligibleJob(job)` 和 `isDirectForwardExtensionTimeFeasible()`；后者会检查 `earliestCompletion <= tMid`。backward 方向同理，`buildBackwardReachableSet()` 要求 `isBackwardHalfEligibleJob(job)`，并且 direct feasibility 中用 `rhoPrime >= max(tMid, hStart)`。`GCNGBBStyleBidirectionalPartialDominance` 复用了同样的 reachable-set 构造。ng-DSSR 中，`buildForwardExtensionSet()` / `buildBackwardExtensionSet()` 也把 `ngMemory.contains(job)`、half-domain 不可达和 direct time 不可达合并到一个 `extensionSet`，再传给 `Label.reachableSet`，因此 paper graph、partial-list 和 single-point dominance 看到的都是这个半域裁剪后的集合。

这和 dominance key 的应有含义不一致。用于支配判断的 reachable/resource-unreachable 信息应表示“沿当前路径继续扩展后仍然永远不可达”的硬信息，不能因为 Tmid 把某个 job 暂时分配到另一侧半域就从 key 中删掉。Tmid 可以用于决定当前方向是否继续扩展到该 job，也可以用于裁剪 forward/backward frontier 的定义域；但它不应作为“未来扩展能力更弱”的证据进入 dominance。否则，一个 forward label 可能因为半域原因排除了 job i，从而看起来能支配另一个 label；但被支配 label 仍可能与包含 i 的 backward suffix 拼出负列，此时支配关系对 bidirectional join 并不安全。

仓库里已有对照实现支持这个判断：`GCBBStyleBidirectionalFullDomainNodeJoin` 的注释明确写着 reachableSet 改为 full-domain 一跳可达集合，不再用 Tmid 裁掉第一次跨界的 job；Tmid 只在 child 生成后决定是否入队。当前 `GCNGBBStyleBidirectional`、`GCNGBBStyleBidirectionalPartialDominance`、`GCNGBBStyleBidirectionalNgDssr` 还没有采用这种拆分，因此存在把半域限制混入 dominance key 的风险。

后续修复方向应拆成两个集合。第一个是 Tmid 受限的扩展候选集，只用于当前方向枚举和入队。第二个是用于 dominance 的 full-domain reachable key，只排除 visited/ng-memory 和真正资源硬不可达的 job，不包含 `isForwardHalfEligibleJob()` / `isBackwardHalfEligibleJob()`，也不包含 `earliestCompletion <= tMid` 或 `rhoPrime >= tMid` 这类半域条件。forbidden arc 仍不应进入 dominance key，因为它只禁止当前直连弧，不代表 job 本身后续永远不可达。修复时还要同步处理 single-point dominance 和 parent reachableSet 的单调继承逻辑，避免继续从已经被 Tmid 裁窄的父集合向下过滤。

2026-06-13 实现记录：已把主要非 node-join 双向 pricing 类改为同时维护 `reachableSet` 和 `extensionSet`。`reachableSet` 继续传给 `Label` / dominance graph / partial-list / single-point dominance，构造时只用不含 Tmid 的 full-domain direct feasibility；`extensionSet` 只用于实际 forward/backward 扩展循环，构造时再叠加 half-domain eligibility 和 `tMid` / `dynamicHB/HF` 约束。已同步的路径包括 `GCBidirectional`、`GCBBAsymmetricBidirectional`、`GCBBStyleBidirectionalFullDomain`、`GCNGBBStyleBidirectional`、`GCNGBBStyleBidirectionalPartialDominance` 和 `GCNGBBStyleBidirectionalNgDssr`。其中 asymmetric 仍保留 `dynamicHB/dynamicHF` 对实际扩展的限制，但不再把该边界写入 dominance key；ng-DSSR 中 `ngMemory` 仍进入 dominance key，变化只是资源/时间不可达不再由 Tmid 造成。focused `javac` 已通过。

进一步分析后，非 node-join 主线不应直接照搬 node-join 的跨界机制。`GCBBStyleBidirectionalFullDomainNodeJoin` 之所以没有把 Tmid 写入 reachableSet，是因为它允许一侧第一步跨过 Tmid，然后把跨界 child 当作 boundary terminal label，不再入队继续扩展；这属于 node join 的实现需要。主线修复要保留原来的半域扩展边界，因此应该在 label 内同时维护两个集合：`extensionSet` 仍然按 Tmid 半域、硬窗口、当前 frontier 一跳可达性和 forbidden arc 之外的即时检查来控制下一步枚举；`dominanceReachableSet` 则只用于 dominance graph/list 和 single-point dominance，不能因为 Tmid 不可扩展而删除 job。

资源不可达仍然应该进入 dominance key，但这个“资源不可达”必须是不依赖 Tmid 的硬不可达。forward 方向可沿用当前 direct feasibility 的主体判断，即 `earliestCompletion <= hEnd`，但不能再加 `earliestCompletion <= tMid`，也不能用 `isForwardHalfEligibleJob()`。backward 方向可用 `rhoPrime >= hStart`，但不能用 `rhoPrime >= tMid` 或 `isBackwardHalfEligibleJob()`。如果 dual profitable window / outsourcing window 本身已经收缩了 job 的硬定义域，可以继续作为 `hStart/hEnd` 的一部分；问题只在于不能再额外用 Tmid 半域裁剪 dominance key。

实现上要注意 parent 过滤。当前 `buildForwardReachableSetFromParent()` 是从 `parent.reachableSet` 继续过滤，如果父集合已经被 Tmid 裁窄，后续永远恢复不了。因此修复时不仅要改初始构造，还要让 parent 继承使用新的 `dominanceReachableSet`。扩展循环不能再直接枚举 `label.reachableSet`，应枚举单独的 `extensionSet`，或者即时从 `dominanceReachableSet` 里再过滤出满足 Tmid 半域的候选。这样才能同时满足两个条件：Tmid 仍限制当前方向 labeling 的规模；dominance key 不把“另一侧半域负责的 job”误记为永久不可达。
