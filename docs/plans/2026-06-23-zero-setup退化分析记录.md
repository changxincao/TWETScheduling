# zero setup 算例更难求解的退化分析

## 1. 当前观察

2026-06-23 对 `data/50-2/wet050_001_2m.dat` 使用 `zeroSetup=true`、normal ng-DSSR、completion bound、completion-bound arc fixing、pricingOnly subtree、midpoint probe/reuse、ALNS/RMIH、关闭 dual stabilization 的主线配置重新求解。实时日志显示，该算例不是卡在单次函数计算异常，而是进入了明显的列生成退化尾部。

根节点耗时约 `574s`，生成 `27135` 条列；后续全局列池已经超过 `42` 万。当前 incumbent 为 `35045`，bound 从根节点的 `35032.5` 逐步推进到约 `35039.2`，gap 仍在 `0.0166%` 左右。多个深层节点需要几十到数百秒的 exact pricing，例如 node 223、228、243、254 等，虽然最终可以通过 dual bound 或 LP bound 剪掉，但剪枝前仍需要做完整定价证明。

## 2. 主要原因判断

zero setup 让单条路径的成本计算更简单，但会让不同 job 顺序之间的经济差异显著变小。原本 setup time 会给相邻 job 的选择提供强区分度；去掉 setup 后，大量不同序列变成成本相同或非常接近的替代列。

这会带来三个直接后果。

1. pricing 更容易找到很多接近 0 的负 reduced-cost 列。这些列合法，但进入 RMP 后对 LP bound 的推动很弱。

2. RMP 更退化。很多列覆盖结构相近、成本接近，LP 可以在大量近似等价列之间切换，导致每轮新增列很多，但主问题目标下降很慢。

3. arc branch 的区分度下降。禁止或要求某条 arc 后，仍然存在大量几乎等价的替代顺序，子节点更难通过少量分支快速抬高下界。

因此，这里不能简单理解为“setup 少了，问题一定更简单”。对 BPC 来说，难点不是单条列成本计算，而是证明没有更优列、没有更优整数解。zero setup 减少了路径成本项，却增加了列空间对称性和退化程度，最终使尾部证明更难。

## 3. 和有 setup 算例的差异

有 setup 时，相邻关系本身带有明显代价，completion bound、reduced-cost fixing、subtree/pricingOnly arc elimination 和 arc branching 都更容易拉开差距。zero setup 下，这些结构性剪枝仍然有效，但边际作用变弱，很多节点必须依赖 exact pricing 反复证明“虽然还有微弱负列，但不足以改善到 incumbent 以下”。

当前日志中可以看到，后续节点的 `exactStats` 仍有大量 dominated label，说明 dominance 没有失效；问题更多来自候选列空间本身过于平坦，导致即使占优和 completion bound 正常工作，也要处理大量近似等价状态。

## 4. 后续分析方向

后续如果继续分析 zero setup，应优先看以下指标。

1. 每轮新增列后 `MasterLP` objective 的下降幅度，判断是否存在大量无效或弱有效列。

2. 每个节点的 best reduced cost 分布，尤其是尾部是否长期接近 0。

3. arc branch 子节点的 bound improvement，确认 zero setup 是否系统性削弱 arc branch。

4. 列 signature 的重复/近似重复程度，判断是否需要更激进的列池控制、active-set/aging 或 tail 阶段返回列数限制。

当前结论是：zero setup 更难主要是退化和对称性问题，不是 completion bound、dominance 或函数计算实现错误。

## 5. 同质与异质机器的影响判断

当前 zero setup 的现象也说明，同规模问题不一定是同质机器更容易。对当前 BPC 来说，同质机器会放大列空间的对称性：同一条或相近的 job 序列放在不同机器上没有成本差异，很多序列之间也因为 setup 被清零而成本接近。这样 RMP 更容易退化，pricing 更容易反复生成边际改善很弱的负列，arc branch 之后也仍有大量近似替代序列。

适度异质机器反而可能降低这种退化。若不同机器在处理时间、setup、可加工任务集或成本上有差异，同一序列在不同机器上的 reduced cost 会被自然区分开，LP 基更稳定，分支后的子问题也更容易拉开下界。因此，在“同质机器 + 成本区分度弱”的场景下，异质性可能让同规模实例更容易证明。

但这个判断不是无条件成立。异质性也会增加 pricing 口径和列族数量，若机器差异引入新的复杂状态，可能抵消减少退化的收益。后续如果测试异质机器，应重点比较两类指标：一是 root 和深层节点的列池增长、MasterLP objective 下降幅度、best reduced cost 是否长期贴近 0；二是每类机器 pricing 的状态规模和总耗时。若前者明显改善而后者没有大幅恶化，才说明异质性确实缓解了当前退化。
