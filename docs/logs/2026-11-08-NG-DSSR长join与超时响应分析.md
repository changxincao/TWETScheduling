# NG-DSSR长join与超时响应分析

结论：删掉无复用收益的Boolean组对缓存解决的是无用存储和OOM风险，没有减少组对枚举、函数下界或剪枝位图构造。当前仍存在按位图最高下标扫描大量零字的实现代价，12:02现场线程位于BitSet.or；但没有独立位图计时，不能将全部join时间归给它。3小时不退出则已找到明确控制缺口：全局墙钟checker正确传入NG，join所依赖的canContinue只判断maxExactPricingColumns>0，根本不检查时间，内层预筛循环也不查时间，调度器又没有外部硬期限。本轮只分析及记录，没有改solver源码、参数、运行任务或另跑实验。

## 1. 这次具体慢在哪里

对象为06主机`n60/set02/m3/family/high-wide`，最新补跑JAR SHA-256以48313495开头，源码快照81e144e4。12:25复查已正常落盘TIME_LIMIT，实际20448.405649秒，即5小时40分48秒，仅处理1个根节点，gap20.065771%。不是大量分支节点导致。

整个运行的组件记录为：NG exact 11次共20344.978493秒，TI根预处理pricing 169次共59.485951秒，启发式pricing共17.101314秒，master LP共5.949415秒。最后一次NG exact为第11次、含23轮DSSR，其累计分解如下。

| 最后一次exact内阶段 | 秒 | 占该次exact |
| --- | ---: | ---: |
| 初始化 | 22.694 | 0.13% |
| 前向扩展 | 1608.273 | 9.20% |
| 后向扩展 | 709.142 | 4.06% |
| join前整理 | 1.976 | 0.01% |
| join | 15144.549 | 86.61% |
| 合计 | 17486.643 | 100% |

仅这次exact中的join就占整次求解总时间约74.06%。probe累计约21秒，不能解释这次几小时的异常耗时。

最后一轮保留forward 3235470、backward 1347808个label。组对预筛检查16110170167次、剪掉16038978038次，剪枝率99.5581%；仍有71192129组未被整组剪掉，函数下界查询348366791次。逻辑跳过的label对达241012943330，后续识别为non-elementary的label对仍达2146885511。即使绝大多数能剪，先枚举161.1亿组再处理结果本身也很贵。

计数口径必须区分：`initialize→resetStatistics`每轮重置label和组对计数，因此上述是最后一轮；`resetExactPhaseTiming`在整次pricing开始时重置，表中时间是23轮累计。日志的`joinEnvelopePrefilter...joinMs=12629936.693`是最后一轮整个“启用prefilter的join”约12629.937秒，包含预筛和后续标准label join，不是BitSet.or或预筛函数单独耗时。最后一轮构造envelope索引只用4.125秒，建索引不是主要耗时。

## 2. 缓存已删，为何还做这些工作

原先删除的是`forwardGroup→backwardGroup→Boolean`映射。它同一组对只写一次，没有下一次查询收益；有效复用由外层`(backwardGroup,lastJob)→BitSet`承担。该外层缓存仍保留，且已在同一terminal内完整复用、完成terminal后解除对应槽引用。terminal单调处理、不回头，因此释放不会导致同一terminal反复重算。两次缓存修正没有删除候选、换剪枝判断或改变枚举顺序，不能期望它们直接消除几十亿组对。

当前热点是`JoinEnvelopeIndex.prunedForwardIndices`：第一次处理某个backward组和lastJob时，逐个检查该lastJob的所有forward组；若某组可剪，调用`pruned.or(forwardGroup.memberIndices)`，把它的成员位置加入结果位图。以后同一backward组内其他label复用整份结果。

`memberIndices`不是TI按时间展开的禁弧BitSet。它表示这个terminal的forward候选列表下标，在`buildJoinEnvelopeIndex`中用`memberIndices.set(i)`填入。组内只存某些下标，未保证相邻；即使成员不多，最高下标也可能很大。已直接读取远端实际JDK21的BitSet.or字节码：其循环从long数组第0项扫到wordsInCommon，必要时再arraycopy剩余段，零字也照样扫描。它不是只访问值为1的那些bit。

因此，每个被剪的forward组都可能触发一次从0到最高成员下标的位图合并；不同backward组还要各自构造结果。代价随这些位图的字宽累积，而不只是随组成员数量累积。这一结构性代价与12:02线程采样相符，但目前没有组成员密度分布，也没有BitSet独立计时；只能确认它是实际执行的热点和可优化方向，不能宣称15144秒全耗在OR，或承诺换一种表示就能快多少。

更稳妥的性能方向是保持现有输出剪枝位图、外层复用和nextClearBit连续跳过能力，仅核对组成员索引的紧凑/稀疏表示，避免每剪一组都扫描大量零字；密集组仍可能适合按字OR。不能简单关闭prefilter或只按组ID逐label扫描，否则可能丢失现有一次跳过长区间的性能收益。此处未实施，也未新增自动切换阈值。

## 3. 为什么3小时没有退出

`Tree.solve`用System.nanoTime创建全局墙钟TimeLimitChecker，PC调用engine.price时传入，NG engine也明确调用gc.solve(lp,timeLimitChecker)，不是时限参数丢失、改成CPU时限或传了NONE。实际问题在NG内部：

```java
private boolean canContinue() {
    return config.maxExactPricingColumns > 0;
}
```

`joinAllForwardTerminalGroupsWithEnvelopePrefilter`、按backward label扫描和标准候选匹配都调用这个canContinue，但它只看列容量配置为正。`prunedForwardIndices`自己的forward组循环连这个检查也没有。进入join前确实调用过timeLimitChecker，但一旦进入，可能把整个join做完才在solveRelaxedRound结束处重新读时钟。前向/后向的主扩展while也使用同一个canContinue，故不宜只称为“一次BitSet指令不支持中断”。此外ExperimentBatchScheduler用无期限process.waitFor，没有3小时外部强制终止。

按累计总时间和最后一轮join时间反推，约在总耗时7818秒（2小时10分）进入最后一轮join，单轮join用了12630秒（3小时30分）；3小时期限在它内部经过，却没有让循环退出，最终到20448秒（5小时40分）才返回，超出约9648秒。该时间线是阶段计时的近似反推，不冒充逐阶段时间戳。最后返回后PC发现超时，最终结果正确标为TIME_LIMIT；NG未完成轮的reduced-cost certificate返回NaN，不把这轮当闭合证明。

从删除Boolean缓存之前的提交直接读取canContinue，函数内容已经相同。缓存删除及terminal释放没有改动该时限逻辑，因此这是原来已有的响应缺口，不能说是新缓存修改造成的。

若后续获准修复，时限响应应独立处理：让外层join循环实际检查全局时限，同时让大块预筛组循环按批检查并可向外退出；不能只在函数入口检查一次，不能缓存未完成结果冒充完整掩码，也不能把中止的pricing当exact闭合证书。原有上一次合法证书和可行解要保留。性能优化与及时退出应分别验证，不在本次分析中直接改变它们。

证据为`data/20261108-ng-join-timeout-analysis/`的analysis.json、最后一次pricing日志、组件CSV、12:02主线程采样及实际JDK21的OR字节码；源文件定位为GC的3578、4443、4554、4838、8593--8620行，PC的1486--1500行，Tree的95--101及1062--1072行，scheduler的540行。12:25采样时high已结束，未对已结束PID继续采样；medium当时仍在运行，均未被本轮启停或改写。
