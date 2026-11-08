# NG-DSSR长join与超时响应分析

结论：删掉无复用收益的Boolean组对缓存解决的是无用存储和OOM风险，没有减少组对枚举、函数下界或剪枝位图构造。当前仍存在按位图最高下标扫描大量零字的实现代价，12:02现场线程位于BitSet.or；但该现场没有独立位图计时，不能将全部join时间归给它。3小时不退出则已找到明确控制缺口：全局墙钟checker正确传入NG，join所依赖的canContinue只判断maxExactPricingColumns>0，根本不检查时间，内层预筛循环也不查时间，调度器又没有外部硬期限。第1--5节为此前只读分析，第6节为本地分项计时，第7节为随后授权的远端单项补跑；尚未实施join算法优化。

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

## 4. 位图构造有多大的局部优化潜力

结论：不是只有省几个百分点的可能。稀疏且下标分散的组，当前OR会扫描许多零字，局部对照显示其代价可以明显降低；密集组却是OR更快。因此应先保留现有组对判定、结果BitSet、外层缓存及nextClearBit，只研究按组密度选择聚合方式，不能直接全量替换，更不能把局部提速倍数当成整个join或完整求解的倍数。本节没有修改正式solver或远端。

独立诊断`data/20261108-join-bitmap-estimate/JoinBitmapEstimate.java`只重现“已知哪些组被剪，将其成员加入结果位图”这一阶段。固定65536个候选、32个后向组；候选位置按固定seed打乱后等量分组，组内成员排序，所有组恰好组成候选全集。每个后向组剪掉约99.56%的组。比较原方式逐组BitSet.or与遍历紧凑成员下标逐个set，两者结果逐一equals，4种规模共128份位图完全一致。JDK21.0.10、512MiB初始/2GiB最大堆；每种规模先预热4次，再交替计时7次取中位数。以下时间均为一次32份结果位图的批次，不是实际pricing耗时。

| 每组成员数 | 组数 | 每组平均位图字宽 | 原OR批次ms | 按成员set批次ms | OR/set时间比 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 32768 | 683.140 | 530.443 | 5.862 | 90.49 |
| 16 | 4096 | 964.090 | 88.805 | 4.287 | 20.71 |
| 256 | 256 | 1020.324 | 1.572 | 2.932 | 0.54 |
| 4096 | 16 | 1024.000 | 0.190 | 5.917 | 0.03 |

这里的位图字宽是到最高成员下标所覆盖的64位字数，不是成员数量。前两行每组只有2或16个成员，却平均扫描683或964个字；后两行组较密集，按字OR利用了位运算和局部性，反而更好。实际组大小、下标跨度和缓存命中形态尚未统计；该诊断是合成负载，不是n60完整A/B，也没有计入紧凑索引的建立成本、组对判断和PWLF查询。因此它只证明优化机制和局部量级，不证明正式实例会提速21--90倍。

完整求解的收益还取决于OR在join中究竟占多少。该high-wide最后一次pricing的join占整次求解74.06%；条件性地说，若能把这整块join减半、其他工作完全不变，才对应总时间约减少37.03%。如果OR只占join的一小部分，实际收益也会小得多。即使消除大量位图零字扫描，最后轮161.1亿组对检查和3.48亿函数下界查询仍存在，不能据此宣称组合爆炸已经解决。

当前建议是优先验证这个局部、数学结果不变的方向，而不是关闭预筛或改DSSR/probe。下一步若获准实现，应保持同输入下剪枝位图完全一致、密集组不退化，并分开记录位图构造、组对判断、后续join及整体pricing耗时；本节尚未实施该修改。时限响应的修复独立于提速，不能将3小时及时退出当作求解效率提高。

## 5. 组和两张位图的含义

这里的组不是任务family或Cluster分支的聚类。join先固定前向终点，将该终点的label按相同ngMemorySet归组；组内序列和函数仍可不同。组的memberIndices记录其成员在整个终点候选列表中的下标。另一个pruned位图则针对某个后向组和这个前向终点，记录已证明可以跳过的前向候选。一个前向组被判可剪后，执行pruned.or(group.memberIndices)；同一后向组内其他label复用结果，未标记的候选仍进入原详细join。

所谓稀疏或密集，是成员数相对于位图覆盖跨度而言，不是ng-memory的大小。若只有下标10和50000两个成员，位图仍覆盖到50000，OR需处理至该位置的782个64位字，其中绝大多数为零；若0至50000均为成员，同样字宽却一次标记50001个成员，按字OR很划算。因此局部优化针对的是“可剪判断已完成后，如何把这些成员记入跳过名单”，不是新的剪枝算法，也不是移除最终pruned位图。按成员设置要使用预先准备的紧凑下标，不能再从原位图0起扫描来收集成员，否则仍可能重复扫描零字。正式实现尚未修改。

关于“只是下界检查，是否真的很贵”的口径：本例最后轮建组与下包络构造仅4.125秒，不能称为主要瓶颈；昂贵的可能是其后海量组对预筛及结果聚合。161.1亿次组对检查先做memory/禁弧/时间与标量下界判断，其中3.48亿次进入findMinimalShiftedTracedSum扫描两条PWLF包络；160.4亿次判可剪后各执行一次成员位图OR。同一后向组/前向终点的结果已有复用，并非同一组对无故重复检查。当前只有整个join耗时和一次OR栈采样，没有预筛、OR、后续label匹配的独立时间。因此确认join是大头，不等于确认OR是大头；局部合成负载提速不能用来宣称实际总体收益已确定。

## 6. 本地分项计时与关闭插桩的采样对照

2026-11-08按用户授权，仅增加默认关闭的`-Dtwet.bpc.ngDssrJoinPhaseTiming=true`诊断。没有改变任何组判断、函数查询、位图合并、候选顺序、缓存、DSSR/probe或时限逻辑，也未部署远端。开启时逐次测量组预筛、位图OR和预筛后的label扫描/tryJoin；每次join返回单独输出`[NG join timing]`，按本轮重置，不与整次pricing的累计时间混合。`functionWithinBoundMs`只是组预筛中PWLF查询的子项，不能重复相加。预筛的其余时间、整个join的其余时间分别保留，后者包含建索引、sink匹配、外层检查和释放缓存，不冒充已独立计时的某个阶段。

本地使用JDK21.0.10、每JVM最大4GiB堆、CPLEX单线程、每项300秒诊断预算；命名参数profile为v8，probe为4/4与5%步长、首轮双池，family采用Cluster→Arc，CutSet/SuccessorSet关闭。当前本地profile的scalar fixing深度为0，所有观察都止于根，不能把这批分项诊断当作全节点A的正式性能重跑。复用对应旧输入系列的已有seed，没有重新生成；输入、seed、有效配置和源码/字节码hash保存在本节证据目录，不混入新F3主线统计。

| 本地场景 | 运行结果 | 已返回join轮数 | 累计join秒 | 组预筛秒/占比 | 位图秒/占比 | 后续label秒/占比 | 其余秒/占比 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| n40/set04/family/base/wide/m2 | 300秒TIME_LIMIT | 112 | 28.957 | 6.747 / 23.3% | 0.272 / 0.94% | 15.276 / 52.8% | 6.662 / 23.0% |
| n50/set01/family/base/wide/m3 | 300秒TIME_LIMIT | 77 | 26.690 | 9.222 / 34.6% | 0.387 / 1.45% | 10.172 / 38.1% | 6.910 / 25.9% |
| n50/set02/family/high/wide/m4 | 250.242秒根闭合，UB=LB=162440 | 140 | 32.892 | 10.685 / 32.5% | 6.783 / 20.6% | 3.072 / 9.34% | 12.352 / 37.6% |

前两项单轮最慢分别0.591/0.860秒，没有复现大join，只能作为小规模对照。第三项有10轮join超过1秒，合计18.492秒；其中组预筛5.673秒（30.68%）、OR5.319秒（28.76%）、后续label0.059秒（0.32%），其余40.24%。最慢是第20次pricing第13轮，join3.058秒，检查12457160组对，全部组对均被预筛剪掉；组预筛0.962秒、OR0.856秒、后续label仅0.0033秒。这提供了“详细label匹配几乎已被跳过，但预筛和结果聚合仍有成本”的真实算例证据，不是先前合成位图负载的结果。

逐组计时会扰动廉价操作，不能忽略。独立空计时标定得到中位空括号27.862ns、两次读时钟的循环成本56.776ns；只作量级提示，不从真实记录强行扣除。第三项涉及约1.027亿次组检查和1.014亿次OR，因此又对同一输入、同一seed关闭分项计时，使用JFR profile低频ExecutionSample进行对照。对照运行209.641秒，同样UB=LB=162440，得到800个主线程join样本：

| 未开启分项计时的join栈类别 | 样本数 | join样本占比 |
| --- | ---: | ---: |
| 组预筛（含可行性及下界） | 333 | 41.63% |
| 位图OR | 154 | 19.25% |
| 后续label处理 | 185 | 23.13% |
| 预筛内其他 | 31 | 3.88% |
| join其他 | 97 | 12.13% |

其中164个样本在PWLF最小和查询中，是333个组预筛样本的子集。JFR表是执行栈样本占比，不是精确墙钟分解；归类栈逐条保留。两次运行的exact调用为20/22次，probe时序反馈和搜索路径不同，不能把250.242与209.641之差直接解释为计时开销，更不能当作优化收益。但不插桩样本仍显示OR约占join的两成，说明在该场景有实质占比，却不是唯一或占绝大多数的瓶颈；前两项仅约1%，也说明不能全局保证大收益。

验证方面，实际部署JAR基线（SHA-256以48313495开头，完整值见receipt）、新代码计时关闭、新代码计时开启，在专项掩码及4个固定dual的普通/Phase-I正负列场景中，掩码、组内复用、列顺序、真实成本、reduced cost和证书完全相同；1081项历史端点/CB检查、1972项traced/Phase-I端点检查及心跳测试通过。每轮计时的互斥项加和及子项范围均经脚本核验。源码只修改NG文件与原有缓存专项测试，没有修改公共PWLF、TI、配置profile或远端。

证据为`data/20261108-ng-join-phase-timing/`：rounds.csv与summary.json、计时原行、有效配置、core summary、tests、JFR分组及800条原栈；receipt.json含输入/seed/源码/class/旧JAR及完整原日志hash。完整运行输出和7.4MB原始JFR留在`.codex-tmp/ng-join-phase-timing-20261108/`。本次没有重新求解远端n60的上万秒join，因此这些比例不能直接替代那一轮的分解，也没有实施稀疏成员或其他join优化。

## 7. 远端重跑原n60长join场景

2026-11-08用户要求开启计时、在远端重跑原场景。14:00:55（北京时间）已在DESKTOP-06K8JI8（100.68.243.112）启动单项`n60/set02/F3/m3/high/wide`，目录为`D:\ccx_work\考虑交付的机器调度\work1\experiments\20261108-ng-n60-join-timing-4ac6cb5a`。只运行这一项，不覆盖20448.405649秒的旧结果，不运行另外两台或TI。runId、原输入及seed路径、10800秒名义时限、100000节点、Cluster→Arc、4/4与5%probe、首轮双池及全节点scalar fixing深度2147483647均保持原A；只改变输出目录、显式开启诊断，外层只需1并发，堆仍为24GiB、CPLEX线程1。

solver.jar从提交4ac6cb5ae3ed1ee30209c4c140db18154bfb10fd的src快照用release21全新构建，473个class均不超过major65，JAR SHA-256为`0e9756b26a4181672737154ed112401f3c1ab2bca69058f0a4fef755decf80a5`。部署前实际新JAR优先的缓存、端点/CB、traced/Phase-I及心跳回归通过。远端验收用原配置及原资产hash逐项比对，196个可比较字段一致；seed指纹为`18c2016cd56dcc40bf638b78ab134e2686c79f6cdb6719eedd180ad8c64c22f4`。启动前Java工具检查没有在途solver；验收时可见物理内存137112563712字节、空闲124981424128字节，没有为诊断缩小堆或关闭其他算法组件。

实际scheduler PID为8268、solver PID为12704。VM.command_line确认`-Dtwet.bpc.ngDssrJoinPhaseTiming=true`，VM.flags确认MaxHeapSize=25769803776。按既有SSH后台SOP只启动一次，验证后关闭本机启动SSH 43460；它因此返回非零属于主动断开，不是远端solver失败，不能据此重复启动。14:04:51独立连接复查仍为1个scheduler和1个solver，CPU由101687ms增至125734ms，live.log由146197增至155590字节，计时行已正常输出；本地再读完整行已有27条，尚处于早期pricing，未返回最终结果。

运维中PowerShell调用返回拒绝访问，但hostname、whoami、SFTP、Java及jcmd正常；没有改系统权限、服务或配置，改用现有Java验收工具。状态工具首次读取活stdout出现MalformedInputException，仅将这个诊断日志读取器改为允许替换不完整字符；配置、seed等结构化读取仍严格按UTF8。tools.jar替换前保留tools.pre-log-tail.jar，solver.jar未变。asset-hashes.tsv是准备期快照，后更新的README及状态工具hash在startup-record.json另记，不将状态工具异常算作求解失败。

本次目的是测实际大join的组预筛、OR与后续label成本，不是证明该实例无法优化。没有原运行完整的dual/label快照，probe依赖时序反馈，因此不能保证重现相同的第11次pricing第23轮；只按本次实际完整返回的join解释，不拿前几轮小join替代旧上万秒轮。已知时限检查缺口未修，加上诊断读时钟开销，仍可能明显超过3小时；这是单项诊断，不混入正式性能结果。当前结论仅为成功启动并持续推进，不是补跑完成。

本地部署清单为`experiment-suite/formal/manifests/20261108-ng-n60-join-timing-4ac6cb5a/`，准备脚本为`scripts/remote/prepare-ng-join-timing-20261108.py`。启动、196字段验收、原summary、堆及进程证据为`data/20261108-ng-join-remote/`，远端同目录README及startup-record.json已同步。复查可调用该目录status.cmd；逐轮原始计时位于`runs\pricing-n060-set02-m3-family-high-wwide-f3-ng_dssr-mainline-a\stdout.log`。

## 8. 15:20只读检查：已完成轮次的初步占比

2026-11-08 15:20:38（北京时间），DESKTOP-06K8JI8/100.68.243.112仍为SOLVE_RUNNING，solver12704存活、累计CPU2441218ms。目录、原版本及配置未改。stdout快照显示第15次pricing第21轮已完成正反向扩展并进入join，但尚未打印本轮计时；最近完整返回的是第15次pricing第20轮43.711秒。没有用活进程或没有新行直接判定卡死。

快照包含269条完整join计时，覆盖pricing1/round1到pricing15/round20，按node/pricingCall/dssrRound无重复，各分项之和与total相符。累计及当前已返回最慢轮为：

| 部分 | 已返回269轮累计 | 占累计join | 最慢完整轮 | 占该轮 |
| --- | ---: | ---: | ---: | ---: |
| 剩余label检查及拼接 | 360.602s | 53.45% | 48.423s | 48.36% |
| 组级下界预筛 | 169.518s | 25.13% | 25.511s | 25.48% |
| 位图合并 | 51.892s | 7.69% | 13.738s | 13.72% |
| 预筛其他工作 | 46.000s | 6.82% | 11.107s | 11.09% |
| join其他工作 | 46.655s | 6.92% | 1.351s | 1.35% |
| 合计 | 674.668s | 100% | 100.129s | 100% |

最慢完整轮为pricing13/DSSR21；另外pricing14/DSSR16为88.899秒，pricing9/DSSR21为79.872秒。此时最主要是label层剩余工作，其次是组级下界检查，位图并非首要项。但remainingLabels包含逐label下界、相容性等检查及tryJoin，不能把53.45%全部称为PWLF相加；bound仅统计组级预筛，不包含所有逐label下界。functionWithinBound是bound的子项，buildWithinOther是other的子项，均未重复相加。

本次尚未重现历史12629.94秒的单轮join，当前未返回的第21轮也未进入上述分母，不能用100秒以内完整轮的占比代替历史上万秒轮结论。插桩时钟和本次dual/probe轨迹仍限制外推。本节是中途统计，不是本次求解完成报告。

用户同时要求确认最新补丁：重新核对8d57dd60仅两处生产代码新增15行，工作区无新的语义源码差异；再次运行TWETColumnTimingBoundaryTest及TimeIndexedRank1CandidateHeapTest均通过，包括12000次map对照和六组堆压缩检查。此前63项回归和120组oracle的原始退出码/输出也已复核，本次没有声称重新跑完这两整套。证据在data/20261108-ng-join-timing-check-1520，含269轮CSV、状态及堆复核输出；原stdout快照仅留本地隔离目录。本轮没有修改源码、上传、停启或更改远端任务。

## 9. 16:21进展：大轮次转为位图合并主导

2026-11-08 16:21:16（北京时间），06主机仍为RUNNING、PID12704存活、累计CPU4380343ms，live.log增至290989字节，stderr仍为222字节。自14:00:55启动已约2小时20分，仍在根节点。stdout已进入pricing16/DSSR21的forward.progress，最新一次记录到160000 pops；前一轮已完整返回，没有停在该36分钟join中。

此时290轮完整计时中，最大为pricing16/DSSR20：总2190.364秒（36.51分钟），组级下界279.132秒（12.744%）、位图合并1324.933秒（60.489%）、剩余label检查/拼接157.909秒（7.209%）、预筛其他425.033秒（19.405%）、join其他3.357秒（0.153%）。组检查3275784148次，剪去3222944522组，函数下界查询119513034次。其次pricing15/DSSR21为493.223秒，位图209.499秒（约42.48%）；pricing16/DSSR19为245.925秒，位图78.655秒。

因此不能沿用第8节“小轮次label部分最大”的中途结论来概括大轮：本次已经观测到的36.5分钟大join确定由位图合并主导。历史12629.94秒单轮仍未精确重现，不将60.489%直接赋给那次旧运行。证据存data/20261108-ng-join-progress-1621；原stdout快照留在本地隔离目录。仅查询与记录，未改源码或远端。

## 10. 为什么OR慢，以及不用位图的两种含义

本轮1324.933秒不是单次BitSet操作停住，而是3222944522次可剪组对应的OR累积，平均每次约411纳秒，包含分项读钟开销。位图标的是某个terminal候选列表中的label编号，不是60个任务的访问位集，也不是TI时空禁弧集合。分组按terminal和真实ng-memory，候选则按成本排序，同一组成员不保证在候选列表中相邻。标准BitSet.or按long数组从0扫到源位图最高有效字，中间零字也处理；组内成员少并不保证位图字宽小。这种跨度代价由源码确定，但本次实际组成员数和跨度的分布仍未记录，不能断言109128个前向组全部稀疏。

每个(backwardGroup,lastJob)只构造一次结果mask，并供同组后向label复用；32亿次不是忘记缓存而对同一组对重复计算。删除无复用Boolean缓存和提前释放terminal位图只解决无效引用、内存寿命，不消除这些仍然需要的集合合并。

“不用位图”需区分：关闭enableNgDssrJoinEnvelopePrefilter会连整组下界预筛一起关闭，回到逐label join；能够省去当前预筛成本，但会增加候选检查，不能直接把1325秒当纯收益。已有JOIN_PREFILTER_SKIP_RUNS=false仅切换结果mask的消费方式，仍生成并OR位图，不能当作无位图对照。另一方向是保留预筛、结果mask、缓存与nextClearBit，用一次构造的紧凑成员编号替换稀疏组的长位图OR，直接对结果mask置位；密集组仍按字OR。这样可以保持输出mask逐位相同，避免改剪枝或候选顺序。不能在每次合并时又扫描BitSet来临时生成成员数组，否则可能保留同样的零字跨度成本。

第5节已有合成负载证据：每组2成员时OR/set批次为530.443/5.862ms，16成员为88.805/4.287ms；256成员时OR反而更快，为1.572/2.932ms。它支持按表示密度优化的机制，不是本轮n60实际负载的A/B，也不证明整轮可快21至90倍。结论是本轮有实测理由优先研究等价mask构造，直接关闭预筛是否更快尚无结论；具体阈值、真实分布与总收益需后续受控验证。此次仅核对源码、标准BitSet实现、既有诊断及历史局部对照，没有修改源码、配置或远端，也未新增性能实验。

## 11. 位图职责及稀疏/密集的判定口径

前向label先按相同terminal和真实ng-memory分组；候选数组的编号由该terminal的已排序候选表确定。组成员位图memberIndices只记录这组包含哪些候选编号，不能把这个位图等同于ng-memory。当前后向组B与前向组G被证明整体可剪时，将G的memberIndices并入pruned结果；该结果按(B,lastJob)缓存，供B内后向label复用。后续只在与B拼接时跳过这些前向候选，不从全局label表删除它们。OR本身不分组、不作占优，也不合并PWLF；建组时另有envelope.merge供组级下界使用。

若研究混合聚合，使用组成员数k与源位图实际字宽W比较，而非任务数、ng-set大小或组占整个候选表的比例。k可用group.labels.size()，W为ceil(memberIndices.length()/64)，length是最高置位编号加1，不是BitSet.size()返回的容量，也不是仅max-min的跨度。现有OR从第0字扫描，前导零字也有成本。逐成员置位成本大致随k增长，OR大致随W增长；不能每次合并都调用cardinality扫描全部位图来重新判断。

可把W>=8*k作为保守的首轮A/B起点：仅非常稀疏的组走紧凑成员下标，其余保留OR。8为待验证建议，不是新配置默认或已经确定的最优阈值；它为随机置位、边界检查等单次开销预留余量。既有合成负载中，2/16成员组的平均W/k约342/60，而256成员组约4，说明这一保守起点与已有两侧结果方向相容，但不能替代真实负载验证。判定和成员索引只在建组时计算一次，缓存结果，保持每次聚合产生相同mask。本节只作解释和方案记录，没有实施。

## 12. 混合聚合的修改与测试计划，尚未启动

用户追问如何测试。主比较A为当前全部OR，B为仅稀疏组按紧凑成员索引置位、其余继续OR。B只允许在建组时一次性准备索引并决定路径，保留组对判断、结果BitSet、后向组/terminal缓存、释放时机与nextClearBit；W>=8k先作试验起点，不立即调大量阈值。关闭整个预筛可另选一项作C辅助对照，但C会回到label检查，不能与B的逐位等价验收混为一谈，也不先全批运行C。

第一层先扩展原NgDssrJoinPrefilterCacheTest：覆盖空/单成员、63/64/65跨字、最高编号靠后但成员少、连续密集、混合剪留、全剪/不剪、多terminal、多后向组、同组复用和terminal释放。用独立成员集合构造期望mask，要求A/B结果逐位相同，输入组成员及其他terminal不被改写。普通/Phase-I和有负列/无负列四类固定对偶检查列签名、顺序、真实成本、RC及证书；补跑相关端点和CB回归，不能只看最终目标相同。

第二层在本地实际定价产生的组上看性能，而不只重跑合成分组：记录k、W、稀疏组比例及聚合工作量。冻结候选、分组及已判定可剪的组集合后，对同一负载分别执行A/B，校验mask并计入成员索引准备成本、内存及缓存复用；不导出32亿个组对，不把先运行一遍join后已改变的候选堆直接用于另一版。若比较整轮pricing，应固定相同dual、ng-set和Tmid，使用独立新引擎，关闭probe或回放同一中点仅作为双方一致的诊断控制，正式默认probe不改。预热后交替重复，报告中位数和范围；每组nanoTime插桩只用于定位，最终提速用关闭高频计时的整轮join/exact时间确认。

第三层才选少量本地n40/n50 family和matched random作完整求解A/B，保留相同输入、seed和其他配置，观察join、exact、总时间、节点、内存与最优值。完整运行保留正式probe，允许时序反馈造成路径差异，不凭单次更快认定成功。验收重点是mask/固定状态输出不变、稀疏负载收益能覆盖建组成本、密集负载无明显退化、完整join而非仅OR分项下降。达到这些条件后，才讨论在当前远端n60原任务结束以后另目录补一项同机对照；没有停止、部署或安排自动重跑当前任务。本节仅计划记录，未修改源码或启动实验。

## 13. 优先采用同一次pricing内部的成对诊断

用户提出一次pricing里两种都做，避免分别求解得到不同dual。这是更直接的首轮比较方式，应优先于分开跑完整求解；还应固定到同一DSSR轮、Tmid、候选label与组对判断。具体诊断只生成label一次、建组一次，并只执行一次canPruneGroup。每个首次构造的(backwardGroup,lastJob)缓存单元，临时记录已经可剪的组编号，分别用OR和紧凑成员编号生成独立结果mask，比较逐位相同；只将原A结果交给缓存及真实label join，不重复加列、更新DSSR或执行组级下界观察。缓存命中时仍直接复用，不为了测量反复重建。一次只保留当前单元的临时组集合及B结果，不持久保存全程32亿个组对。

稀疏/密集属于哪种聚合更便宜的工作量分界，不是先验必须固定为某个百分比。保留k和W，先按W/k区间统计实际组与合并频率，并用同档真实组组成批次，比较OR与逐成员置位的时间；不能为测每组几百纳秒而逐组读时钟。8仍只是候选起点，在真实组的稳定胜负分界和余量支持前不冻结。挑出阈值后，再在同一次pricing的完整mask构造上比较A与混合B，避免仅凭分档微基准外推整体收益。

同次比较也不自动消除先后顺序、缓存、JIT和GC影响：需要预热、交替AB/BA、相同的目标位图初始化规则，位图equals与诊断日志在计时外；成员索引的一次性构建时间和内存另记。可以只选一到几次有代表性的pricing做全量双算，其余正常运行，避免所有昂贵pricing都承担双倍诊断。本次运行总时长含双算和核对开销，不能作为优化后的正式总时间；当前状态下聚合成本可以直接成对比较，最终仍需单一路径确认整轮join收益。此处继续讨论并修订测试计划，没有改源码、启动实验或操作远端。

## 14. 同ng-memory为何仍可能有多个label，分组与合并的区别

用户追问同一任务i、同ng-memory是否已经能合并。当前确实可能有不同的可达/不可达集合：updateNgMemory仅按父memory与当前任务邻域求交再加入当前任务，时间不可达另由buildForwardChildReachability/buildBackwardChildReachability计算。正向可达检查依赖frontier.head.start、setup/加工时间和动态窗，反向依赖frontier.tail.end等；因此相同i与memory不保证reachableSet或半域extensionSet一致。joinEnvelopeGroup的key仅含同terminal下的真实ng-memory，既不要求reachableSet相等，也不要求函数/路径相同。

但不能把多个label存在完全归因于不可达集合不同。即使reachableSet相同，成本函数也可能交叉，某条原label未必全域占优，但这不阻止取下包络。经用户纠正后重新核对：正式无SRI主线实际由createDominanceStore转到IncrementalSourcedDominanceGraph，不是旧PaperDominanceGraph。该图按reachableSet组织节点，每个本地包络段保存source label；最后一个贡献段消失时会标记该来源label被支配。join分组则对getForward/BackwardJoinExtension建立带来源的TracedJoinEnvelope，group.labels仍保存候选来源。原label对象存在不等于函数没有合并，也不能以路径父指针不同推断函数不能合并。

成组的目的，是用共同ng相容性条件和组函数下包络，一次证明许多label pair不可能改进。若乐观包络已经不能形成负RC，整组均可跳过；不能排除时，当前正式prefilter仍回到原label级join。它并不以组的包络构造一个新普通label继续扩展。当前已经具有按段追踪来源的机制，不能把缺少来源当作这一实现选择的原因。已有enableNgDssrJoinEnvelopeCompression分支能用带来源包络寻找组对代表split并恢复序列；它改变了候选枚举方式，与正式prefilter不是同一策略，不能擅自开启，也不能仅凭其存在就宣称其整体性能更好。本轮只核对并纠正解释，没有确认新缺陷或修改源码/远端。

## 15. 分段来源复核与说明更正

用户指出占优数据结构每段已保存父节点/来源。准确口径为：正式profile的useIncrementalSourcedDominanceGraph=true，无SRI时使用IncrementalSourcedDominanceGraph；SourcedSegment.source记录本地Label，再沿该Label的father恢复路径。来源为占优前驱的外部贡献在本节点以null区分，不能把占优前驱node与真实路径father混为一谈。同reachableSet的函数确实已做来源感知min merge；函数交叉或前缀不同不构成不能合并的理由。上一回复引用旧PaperDominanceGraph并强调单父指针限制，未准确描述正式实现，已在第14节更正。

仍成立的区分是两个组织键不同：同任务i下，dominance node按reachableSet，而join预筛组按ngMemorySet，因此同ng-memory组可能横跨不同可达集合节点，也可能包含同一节点包络中仍有贡献的多个source。join另建带来源包络，默认只用来预筛，仍枚举原候选；这不是因为不能保存来源。source能恢复包络各段代表路径，不自动意味着下包络涵盖组内每一条候选路径。压缩join与逐label join在候选输出、真实访问profile/见证处理上需要单独比较，不能直接称数学上不能合并，也不能擅自切换。本轮仅修正文档和分析，未改代码或远端。

## 16. 从占优图到扩展队列及join的实际对象流

用户认为join应接收最终集成label，核对当前实现并非如此：ForwardLabel/BackwardLabel仍代表来源路径及自身frontier；IncrementalNode则持有按reachableSet组织的综合SourcedEnvelope。insertForward/insertBackward将新label送入占优store；若被拒绝则不入活跃表，若接受则把同一个原label对象加入activeForwardByLastJob/activeBackwardByFirstJob，forwardExtend等把这个原child放入队列。扩展时poll的是来源label，读其自身frontier，并非从图中取出g包络包装成新label。图节点包络中的每段source用来识别贡献与被覆盖来源，完全失去贡献的原label被标记isDominated；路径恢复按来源的前向/后向类型沿各自父链完成，不能将图前驱与路径父链混用。

普通正式入口明确传入DominanceBackend.PAPER，但在无SRI且useIncrementalSourcedDominanceGraph=true时实际创建IncrementalSourcedDominanceGraph(partialDominance=false)。因此source-aware整条清理启用，partial来源区间裁剪不启用：只要原label仍有贡献，它的frontier通常保留完整，不是自动重建为最终包络贡献片段。不能把“未完全支配”误称为“每一个时间点都未被支配”。partial实验入口才通过prepareLabelForUse应用延迟区间收缩，不得混进正式现状说明。

labeling结束后，compactForward/BackwardLabelsForJoin扫描活跃表，删除已标记isDominated的项并排序，普通主线不会从占优store导出每个node的综合包络作为候选。随后按terminal/ng-memory从这些幸存来源label另建join-envelope组；预筛不能排除的组合仍读取来源label做tryJoin，逐对判断相容性、真实访问profile及函数最小和，恢复列。Tmid退化单点另有SinglePointStore接收及join入口，不能以普通图的流程替代它。

所以join确实使用经过占优筛选的label，但不是用户所称每个图节点最终集成后的一个label。采用综合包络直接join是另一种候选枚举方案；当前有独立compression实验分支，不能由其可实现就断言现有路线有错误或立刻切换。本节仅解释对象流，不新改代码、测试或远端。

## 17. 回看来源标记的原始目的与落地记录

本次按用户要求查阅原记录，而非从现代码反推历史。主要来源是plans/2026-06-09-ng-dssr与partial-dominance实验变体记录第170、181至185、187、190、196至198节，以及plans/2026-07-27-dominance-graph文献对照与论文定位第3节。中间阶段曾有仅做增量传播、仍逐条检查历史label的版本，也有后来撤回的保守判断；当前解释以最后落地的source归零清理、partial惰性应用和no-h版本为准，不把早期方案当正式实现。

来源标记的主要目的确实是包络更新后的高效label清理：旧图只维护数值包络，predecessor变化后需遍历node历史labels，对每条函数重新做完整占优检查；旧normal同-key插入还存在旧label反向清理不足的到达顺序不对称。每段附本地source以后，min-merge在生成新包络时即可同时给出新candidate是否贡献、哪些旧source失去最后区间、数值真正下降的sparse delta。来源归零者标记isDominated，队列出队跳过、join前compact清除引用；不立即释放仍可能被子label父链引用的对象。代价随更新涉及的包络段和来源变化，而非重新扫描所有历史label。

本地source与external贡献分开，是为正确计算本节点还剩哪些本地活跃label；external不是本地label，不增加本地计数。本地来源还可能在数值不再下降时被外部贡献替换，因此source清理与数值delta传播是两件事：该清理的仍清理，只有真实下降才继续向后继传播。当前只持久维护综合g，不再额外持久保存一份h；删除node重连的前提是本地source已归零。

第184节明确normal只删除整条零贡献label，仍有一段贡献的label自身frontier保持完整；第187/190节才将partial统一接到source图，额外保存最新贡献区间并在扩展/join前惰性裁剪。原第181/182节也强调当时收益不只省掉逐label扫描，还来自提前删除label后少扩展、少生成子label、少做join。历史root诊断的绝对秒数和路径数量不在此当作当前正式数据性能结论。综上，source是占优清理和可选partial裁剪的维护信息，当前并没有因此把g作为一个新工作label送入扩展或join。本轮仅查阅、解释并记录，无源码/测试/远端操作。

## 18. Partial未设为默认的历史依据与证据范围

Partial已实现并保留可选入口，不是因发现其数学错误而删除。最终选择normal的主要历史理由是：source-aware normal已经获得整条零贡献label清理，partial的额外工作剩下对幸存label重建PWLF、填M、方向normalize和刷新minimum。合法等待/后缀闭包会恢复一些有限区间，因此大量裁剪不必然带来相同比例的函数或扩展减少；早期partial-list的二次扫描成本不能混作新版source-aware partial未转正的原因。

原记录第187节的40-2初版source-aware partial压力对照关闭ALNS、启发式pricing、TI预处理与强分支，只测root约300秒，两组都未闭合。partial内部556874次裁剪的段数由1363473降到1305391，约4.26%；前/后向候选相对normal少约6.2%/7.6%，但两组列/dual轨迹很快分叉，累计join pair由normal约3.678亿变为partial约4.327亿，没有稳定整体收益。不能将17.7%的pair增加解释为相同dual下partial固有地增加拼接，也不能把该诊断当成当前完整正式数据的性能证明。

第190节随后实施惰性trim，将多次source变化合并到扩展/join前应用，并进一步删除冗余统计、min扫描和持久h；因此初版300秒结果不能无条件代表现在优化后的partial。当前查到的记录仍不足以宣称它在最新正式数据上一定更慢，准确结论是未取得足够稳定收益证据，所以没有替代normal默认。现配置两种ng partial开关仍false，普通入口仍使用normal语义。Partial不等于直接以节点综合包络替代所有来源label做join，也不直接取消当前组mask的OR；不能把启用partial当成已经验证的OR瓶颈解决方案。本轮只查历史、核对配置并记录，未开关配置、改源码或运行实验。

## 19. Join可以合并函数，限制不在reachableSet本身

用户追问join不能合并是否因为reachable不同。当前tryJoin并不直接以reachableSet作为配对检查；它检查terminal/禁弧、真实ng-memory相交、时间和函数衔接，并用真实routeVisitedMask及routeElementary区分基本列与非基本见证。因此不能把扩展阶段的reachable条件直接当成join不能合并的原因。当前buildJoinEnvelopeIndex已经按terminal和真实ng-memory合并带来源函数，同组允许reachable不同；无SRI时另有enableNgDssrJoinEnvelopeCompression分支，直接用两组包络求最小和、取来源label并恢复序列。对共同相容条件下的松弛最小值，函数合并及来源恢复本身是可做的。

但一条下包络只保留各时刻的最低来源，组对的全局最小查询又只返回一个代表split，不等于枚举原组内所有有用负列。真实访问集合并不由ng-memory唯一决定：代表组合可能是非基本见证，而其他组合可能给出不同基本负列或不同循环见证；这说明列批量与DSSR更新轨迹会变化，不应据此直接声称松弛最小值错误。当前compression代码4796至4799行也明确每个group-pair只返回一个代表，并在入池前重新评价真实序列成本。

此次查到原计划记录第156节（2026-07-09，1553至1557行）明确记载：此前直接group-envelope join虽显著降低函数比较次数，但代表source-pair策略使负列批量减少、DSSR轮次及扩展时间增加，整体不一定快。因此正式保留“包络能排除则整组剪去，不能排除则原label级join”，而非因为无法合并、没有来源或reachable不同才被迫这样做。该历史诊断不充当最新正式数据的性能证明；本轮仅复核现代码和既有记录，没有切换compression、修改算法或运行测试。

## 20. 忽略reachable的含义：合并join查询，不合并扩展状态

直接包络join在labeling结束、原label经过占优清理以后建组；前向按结束任务与ng-memory，后向按起始任务与ng-memory。各原函数仍只在自身有效时间范围提供有限值，逐点取最小值时记录来源。两组端点固定，所以连接加工/setup平移及连接弧对偶相同；ng相容性也可按组判断。无SRI且只求当前ng松弛最小值时，先对来源取min再对衔接时刻取min，与遍历这些来源组合后取min是同一最小化次序交换。它没有将各reachable取并集，没有拿一个label的低成本配另一个label的扩展权限，也没有把拼成的包络送回扩展队列。某时刻取到哪段，就恢复该段的真实前后向来源，非将一条路线在多个时刻切换来源。

reachable在当前buildForward/BackwardChildReachability中包含由frontier时间边界等产生的继续扩展信息，与ng-memory不是同一个集合。若要把多条路径合成新工作label继续扩展，就必须保留各来源状态的对应关系，不能只取成本min并随意合并reachable；但当前join查询不做这种操作。严格基本路线要求的实际访问集合相容也不能仅由同ng-memory保证；当前ng-DSSR允许先得到非基本见证，直接compression按组取最小不等于只在基本来源组合中取最小。本轮澄清两种合并的语义，没有修改代码或运行实验。

## 21. 不读取reachable不等于不检查拼接可行性

当前full-domain reachable构造先排除ngMemory，再按frontier边界计算最早/最晚可扩展时间并检查动态窗口；实际半域extensionSet还另与禁弧mask相交。join不以这份预计算集合代替真正衔接，而分别检查跨接弧、两侧真实ng-memory，以及共同衔接时刻下的函数最小和。组的minReducedCost之和只是乐观预筛，不能当可行解；直接包络join仍须做带平移的完整函数查询，选出有来源的前后向函数段。不能把一个来源的有限成本与另一个来源的定义域混用，也没有通过两个独立最小值直接生成路线。

在正确生成的前后向label和现有ng更新规则下，跨接后若某任务的重复违反ng规则，即两次访问之间所有任务的ng邻域都保留它，那么它会同时留在前向末端memory和后向起点memory，交集检查将拒绝该组合。若它已在任一侧被遗忘，该重复可为当前ng松弛所允许，并不因此成为原问题可行排程。当前正式不返回松弛列时，tryGenerateColumn先判elementary；重复序列只记录non-elementary见证，不进入主问题。因而正确表述是：不额外比较reachable本身不导致借用扩展权限，但组最优代表可以是重复任务的松弛路线；不能笼统保证它就是原问题最优可行列。本轮只核对和解释上述职责，无代码、配置或远端变动。

## 22. 先统计真实组大小，再作等价mask聚合对照

用户提出先看每组大致多少label，再讨论前述优化。已保存16:21快照中pricing16/DSSR20的join为2190.364秒，OR为1324.933秒（60.49%），前向109128组、后向46576组；现有逐轮计时没有输出组成员数量或跨度分布，不能据此推断每组通常很小。现源码只有compression专用summary打印fLbl/bLbl，不能将正式prefilter日志当成已有该分布。

建议第一步只加建组完成后的轻量汇总：前后向分别记录label总数、组总数、平均/中位/P90/最大组大小，以及1、2、3至8、9至64、64以上的组数。前向另统计成员位图实际字宽W=ceil(memberIndices.length()/64)、成员数k及W/k分档；不得以BitSet容量、任务数或ng-set大小代替。只在每组建成后处理一次，每轮输出汇总，不打印十万条组记录，也不在几十亿次组对调用内读钟或扫描cardinality。前向大小/跨度直接决定OR与逐成员set的工作量；后向组大小主要帮助理解缓存复用规模，二者不能混作OR源位图大小。

第二步沿第13节同次pricing方案：每个首次构造的(backwardGroup,lastJob)单元，组对判定仅做一次，暂存本单元已可剪组，A按原OR建mask，B对稀疏组用预先建立的成员下标、密集组仍OR。分别成批计时，交替AB/BA，equals核对在计时外；只有A进入真实join。成员索引准备时间/内存单记，不把双算诊断的求解总时长当优化性能。实际应用权重也要看：大量小组若很少参与可剪合并，不代表主要OR负载；应在诊断单元中对已可剪组汇总sum(k)、sum(W)及分档合并次数，而非只看静态平均。8仍是候选分界，不当成已定参数。一次只保留当前缓存单元的诊断数据，不落盘几十亿组对，不重复生成label或候选列。

本轮只复核已保存计时、源码统计入口并确定讨论方案，未新增统计代码、测试或远端运行；直接group-envelope压缩join不启用，group判定、最终mask、缓存和逐label候选路径均要求保持一致。

## 23. 两种极端的诊断实现及六例远端安排

用户最终要求不先设稀疏阈值，直接测试全部OR与全部成员set，并统计W、k及q=W/k分布；随后明确放到远端并行六例。本地曾按要求暂停，留下未完成草稿；本节记录恢复实施后的状态，不能将第22节的“未实施”继续当作最新状态。新增默认关闭属性twet.bpc.ngDssrJoinBitmapCompare，未修改profile、公共PWLF、TI或组对判据。开关关闭时不准备成员数组、分布表或双算名单，原缓存miss流程保留。开启时，每个首次查询的后向组/terminal仅按原顺序执行一次组判断，冻结可剪组；A全部OR、B全部成员set，按miss序号交替AB/BA，分配及equals在计时外，只A写入原缓存。缓存命中不重复计数或双算。

成员数组来自完整memberIndices，而非包络当前贡献者；建组后一次准备。每轮分别输出前后向组大小的总量/中位/P90/最大值，前向q×k交叉分布和W按2次幂分布；静态每组一次、使用加权仅按真实可剪合并次数一次，sumK/sumW和次数使用long。为解释比值而增加的分档计时仅抽样前8个及后续2次幂miss，且该单元至少64组、该分档至少32组；抽样耗时、样本合并数和AB/BA次数单记，不能视作全量分档时间占比。两种方法整批采用相同原组顺序，抽样不会重复生成列或更新DSSR。成员数组仅报告4*k字节的payload，不冒充Java对象总内存。整个诊断求解时间含B、核对和抽样，不当优化后正式总时间。

Java21 focused编译通过；独立成员并集对照2560批次及分档双序、跨64位字、高位稀疏、空mask和long计数检查通过。原cache回归在关闭、开启、与原逐组phase timing同开三种模式下，与修改前baseline的mask、四种固定dual下有序列cost/RC摘要SHA及证书完全一致；组内复用和释放后重建保持不变。真实建组测试特意保留一条不贡献包络的较高函数label，确认它仍在紧凑成员索引中。端点边界、1972项traced/Phase-I及1081项历史端点检查通过。独立只读审查未发现生产决策或缓存语义变化，发现的测试反射字段错误已修为检查segments来源并重新通过；不把这一fixture错误当生产缺陷。

准备部署到DESKTOP-06K8JI8（100.68.243.112）允许根目录下的独立实验子目录，六并发、每JVM 4GiB、CPLEX一线程，每例600秒名义预算/最多1节点；这是join组件采样而非完整树性能比较。该主机当前jcmd报告约128GiB物理内存、约100GiB空闲，原长join PID12704及scheduler8268仍在，明确保留不停止。六例拟为family的n50/set02/high/wide/m4、n40/set04/base/wide/m2、n40/set02/high/wide/m4，以及random的n40/set01/base/wide/m4、n40/set01/high/wide/m4、n50/set01/high/wide/m4；输入和seed沿用已有对应文件，部署时逐项hash核对。启动与断开SSH后的验收另记，不能把本节准备安排当作已经启动。

## 24. 06主机六例已启动及断开验收

2026-11-08 19:06:47（北京时间）六例在DESKTOP-06K8JI8（100.68.243.112）独立目录D:/ccx_work/考虑交付的机器调度/work1/experiments/20261108-ng-join-bitmap-ab-caecd7db并行启动。源码为caecd7db，全新release21构建JAR SHA-256为ed774ae39b15995faee88aac36adda5ca795ac62e104fc4271f2229e5b36a63c；上传后21项静态资产hash、六例n/m/无外包口径、seed指纹均验收通过，scan-only确认为6项NG_DSSR、无依赖、无已有SUCCESS。首次传输只漏建assets子目录，随后只补该目录和缺失文件，未重传覆盖已有结果；上述完整hash验收在补齐后执行。

每个JVM4GiB、ActiveProcessorCount=2、GC线程1、CPLEX1线程；开启全OR/全set同状态诊断，原逐组phase timing未开启。family为Cluster→Arc，random为Arc，CutSet与SuccessorSet关闭；原profile其余参数不变，TI借鉴fixing深度2147483647。每例600秒名义预算、最多1节点，根节点内的强分支试探/修复仍保留。六个实际solver PID为6404、11776、8604、2736、10292、576；较小例子在首次独立查询前已结束，不能要求当时仍恰有六个活进程才承认启动。

按SOP只启动worker一次，确认状态后精确关闭本机启动SSH PID54176；19:08:27独立查询仍有本批1个scheduler、2个solver，其余4项SUCCEEDED。19:12:04再查为5项SUCCEEDED、1项运行（n40/set04/family/base/wide/m2，PID8604）；原长join solver12704/scheduler8268均保留，21项资产仍通过。SUCCEEDED仅表示本次诊断正常返回，不等于整棵B&P树已最优。运行flags已核对新属性和MaxHeapSize=4294967296；没有启动六个本地求解JVM，也未操作另两台正式批次。

首份快照显示：n50/set02/family/high/wide/m4已有199轮完整诊断，116778716次实际合并，OR累计2349.762ms、set1554.679ms，成员数组准备63.230ms，静态平均每组7.03个label、实际合并加权平均4.96个；该输入为此前诊断F4系列，不能混入当前F3主线。n40/set02/family/high/wide/m4累计OR15.801ms、set19.096ms；n40/set04/base/wide/m2尚未结束，快照181轮为OR199.528ms、set692.050ms。三个random的聚合总量都很小（OR分别约0.028/0.058/0.322ms），其时间比不足以选择稳定分界。初步支持方法胜负依赖组结构，不支持全量set或固定8阈值；尚未结束的结果不冒充最终比较。

小型验收证据、代码/数据/seed追踪表见data/20261108-ng-bitmap-ab；完整诊断stdout在远端各runs目录，本地工作快照在.codex-tmp/ng-bitmap-ab-20261108。summarize-ng-bitmap-ab-20261108.py输出每轮、每例、q×k和W分档CSV，并核对各档次数/sumK/sumW与原汇总一致；使用sumW/sumK时明确不是mean(W/k)，分档抽样时间不外推全量占比。后续最终状态另续记。

首份每轮/每例、q×k及W分布已保存到data/20261108-ng-bitmap-ab/initial-statistics，capture.json明确为5项返回、1项运行中的快照，不能当六例最终表。对应固定dual的baseline/off/on/both和实际部署JAR回归小日志以txt保留，避免通用log忽略规则漏掉验收证据。

## 25. 六例返回后的最终位图对照

19:21:51（北京时间）完成验收：六个child全部SUCCEEDED/exit0，scheduler末行为total=6 succeeded=6 skipped=0 failed=0，本批solver/scheduler均为0；原长join的12704/8268仍保留，21项静态资产hash仍匹配。六例累计578轮完整诊断、5927284次缓存miss的成对mask构造、316021136次实际可剪组聚合，所有OR/set位图比较通过。SUCCEEDED是组件诊断返回状态，不是六个完整B&P均证明最优。worker的末尾状态未落盘（experiment.status仍SOLVE_RUNNING、worker.exit-code为空），因此不将该空文件当退出码证据；在六个child状态、scheduler完成汇总与本批无JVM三项一致后，保留experiment.status.pre-finalize，仅把实验状态补为FINISHED，未修改任何solver结果。completion-record.json明确worker shell退出码未观测。

下表只统计两种方法填写位图的累计墙钟时间，分配、成员索引准备、相等核对和分档抽样均不混入。

| 场景，均wide | 完整诊断轮 | 实际合并次数 | OR ms | 全set ms | 索引准备 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| family n40/set04/base/m2 | 314 | 197626340 | 5683.821 | 7292.255 | 74.888 |
| family n50/set02/high/m4 | 199 | 116778716 | 2349.762 | 1554.679 | 63.230 |
| family n40/set02/high/m4 | 42 | 1613814 | 15.801 | 19.096 | 3.447 |
| random n40/set01/base/m4 | 3 | 32 | 0.028 | 0.016 | 0.435 |
| random n40/set01/high/m4 | 4 | 70 | 0.058 | 0.032 | 0.474 |
| random n50/set01/high/m4 | 16 | 2164 | 0.322 | 0.213 | 0.922 |

当前结论是不应全部替换set：n50 high聚合本身少约0.795秒（33.8%），但n40 family/base反而多约1.608秒；六例累计全set也没有优于全OR。n50的准备时间另约0.063秒，不能把局部1.51倍叫完整求解1.51倍。三个random的聚合不足1毫秒，冷JIT、固定计时开销及调度噪声足以影响比例，不用于选择分界。较小family/high也只有十几毫秒，不与两项较大负载等权解释。

组结构方向与预期相符：n40 family/base静态平均k=34.46、实际合并加权平均k=20.76；n50 family/high分别为7.03和4.96。对应sumW/sumK分别为2.528与4.692，这个工作量比不是mean(W/k)。q×k表保留原始静态组次、合并次数和sampledMerges，便于后续按实际调用权重分析。抽样中1<q<=2两项均OR较快，4<q<=8及8<q<=16更倾向set；2<q<=4在两项负载上胜负不同，说明不能仅凭一个粗档宣布统一阈值，仍需结合k与重复波动。未设置8或其它正式分界。

最终CSV、每轮记录和汇总在data/20261108-ng-bitmap-ab/final-statistics，原始状态及实际profile/CPLEX线程记录在final-status；先前initial-statistics保留其时间边界。完整stdout保留远端及本地隔离snapshot-final，不作大范围结果导出。本批没有重现此前n60上千秒OR量级，因此不能据几秒负载断言极端轮一定获得同倍数收益。正式OR策略和其余求解配置不变，未追加第七个算例。

## 26. 原n60慢例另目录启动同状态双算

用户随后明确要求使用原n60长join慢例，在远端独立目录做全OR/全set同次pricing对照，原任务和正式逻辑不动。2026-11-08 19:38:19（北京时间），已在DESKTOP-06K8JI8（100.68.243.112）的D:/ccx_work/考虑交付的机器调度/work1/experiments/20261108-ng-n60-join-bitmap-ab-caecd7db启动单项。使用六例已验收的同一caecd7db solver.jar，SHA-256仍为ed774ae39b15995faee88aac36adda5ca795ac62e104fc4271f2229e5b36a63c；本次不修改任何pricing源码或正式profile，只扩展只读验收工具以接受n60单项目录，汇总脚本支持原pricing前缀的日志。

输入为n60/set02/F3/m3/high/wide，复制自原诊断已保存资产；input SHA-256为aa76b25ba89e1a64b62df485b50b48d27fffe6ebeb76d19e9d6e1832feaff324，seed SHA-256为31c4a37f1c9542a72bf0b0b3239a8ef633f65c6996bb7e8371bd87245dcec3c8，均与原case-map匹配；运行seed指纹为18c2016cd56dcc40bf638b78ab134e2686c79f6cdb6719eedd180ad8c64c22f4，Data读取n=60/m=3/CmaxH=125394且无外包。原runId保留，CLI仅改变instance/seedFile/outputDir三个地址，其余13项完全一致：10800秒名义时限、100000节点上限、Cluster→Arc、CutSet关闭、theta=0.5/temporal=0、全节点TI借鉴fixing深度2147483647等均不动。没有改为六例组件测试的600秒/1节点。git逐文件比较也确认旧4ac6cb5a与caecd7db之间BestBpcProfiles、TWETBPCConfig和FormalExperimentRunner源码相同；未冒充已读取尚未生成的最终config dump做全字段核对。

JVM仍24GiB、CPLEX1线程，不沿用六例中的ActiveProcessorCount=2或GC线程限制。只把旧逐组phase timing改为默认关闭的同状态bitmapCompare诊断，避免A被逐组包钟而B仅批次计时的不对称；保留原live trace/阶段心跳。每个pricing/DSSR轮的labeling、建组及组对判断只做一次，两种极端消费同一可剪名单，计时外交叉核对，只有OR结果进入真实join；记录W/k/q静态、使用加权和解释性分档抽样，不设置稀疏切换阈值。整体求解时间含双算及统计，不能当正式优化时间；probe受时序反馈影响，不能保证走到与旧run相同的pricing编号/dual。原join时限检查缺口未修改，10800秒仍为名义限制，可能超出。

上传后12项静态资产hash和单任务scan通过；启动前确认本新目录无JVM，只启动worker一次。新solver PID11072、scheduler PID11680。主动关闭本机启动SSH PID24104后，19:40:56独立复查仍为1个scheduler/1个solver；原solver12704/scheduler8268同时保留，未停止、覆盖或改动其目录。实际VM.command_line/flags确认诊断属性、24GiB上限及原CLI，run.properties确认v8/CPLEX1/原seed指纹。此时已有9轮完整诊断并继续运行，原pricingHorizon=52290也已出现，但尚未重现历史大join，不能用早期毫秒级轮次评价优化收益。

版本、原/新CLI、资产hash、启动及断开验收在data/20261108-ng-n60-bitmap-ab，远端有同名startup-record.json。原pricing前缀的4轮启动日志解析通过，汇总脚本重新处理已完成六例也得到完全相同的summary.json。当前结论仅为正确启动并推进，未宣称单项已完成或大join已提速。

## 27. 20:11的n60同状态对照进展

2026-11-08 20:11独立读取06主机，新任务11072/11680仍运行，原任务12704/8268亦保留；12项资产hash仍匹配。已完成142轮诊断、3522073次配对mask构造、209465269次组聚合，OR累计38.628秒、全set累计14.904秒，局部时间比约2.59。索引准备累计0.084秒，核对0.135秒，解释性分档抽样0.0045秒，未混入上述OR/set计时。当前仍是根节点，第8次pricing/DSSR21已到forward.progress，取stdout快照时达到160000 pops，尚未完成本次pricing或整项求解。stderr仅有JAVA_TOOL_OPTIONS提示，已完成双算未见位图不一致。

较大单轮已出现在pricing7/DSSR21：OR36.866秒、set10.770秒，约3.42倍；该轮贡献了绝大多数累计收益，其前一轮pricing7/DSSR20却为OR0.840秒、set0.936秒。因此不能称所有轮次统一受益，仍需看组结构。较大轮有23822个前向组、722930个前向label，组大小中位15/P90为85/最大309；完整q×k/W分布在data/20261108-ng-n60-bitmap-ab/progress-2011。该快照只证明已完成轮次出现较明显位图局部收益，尚未重现旧1325秒OR量级，不外推整个join或完整求解提速。本次仅只读查询与本地记录，没有修改远端任务、配置或代码。

## 28. 20:46出现197秒OR的同状态轮次

20:46:50（北京时间）再次只读核对06主机：新11072/11680与原12704/8268均在，12项资产hash通过，约93.8GB物理内存空闲。新任务已运行约68.5分钟，仍在根节点；已完成166轮诊断，当前pricing9/DSSR23的forward.progress在取stdout快照时达到1200000 pops，相邻心跳从108万持续增加，未停在上一轮join。stderr仍只有JAVA_TOOL_OPTIONS提示，没有位图不一致退出。

166轮已完成配对统计为OR251.204秒、全set51.569秒，约4.87倍，差约199.635秒；索引准备累计0.172秒、核对0.301秒、分档抽样0.006秒另计。最大完成轮pricing9/DSSR22为OR197.332秒、set28.146秒（约7.01倍），该轮聚合598932615个可剪组、前向41723组/769451个label，组大小中位10/P90为46/最大269。该轮sumW=385609928668、sumK=10586348505，保留完整分档供后续分析，不能把其比值叫作平均q。当前结果支持大跨度位图负载下存在显著局部优化空间，但尚未重现旧1325秒OR规模，也没有测出完整join或正式求解提速。快照见data/20261108-ng-n60-bitmap-ab/progress-2046；没有修改、停止或重启任务。

## 29. 22:47当前轮已进入长join，计时尚未返回

22:47:47（北京时间）只读查询：新任务11072/11680仍运行，已启动约3小时9分钟，原12704/8268仍在，12项资产hash不变，物理空闲约88.9GB。完整诊断仍为166轮，不能把这个未更新误认成没有推进。stdout显示pricing9/DSSR23已完成正向1793090 pops与反向513039 pops，两侧队列清空，随后进入join.compact.start和join.start。

一次Thread.print采样显示主线程RUNNABLE，位于BitSet.or -> NgJoinBitmapComparison.fillOr -> measure/compare -> prunedForwardIndices；主线程累计CPU约5782秒、elapsed约11397秒。此采样证明当前处在原OR聚合路径，不是卡在前后向扩展或等待锁，但单次栈不能量化本轮OR占比或推算其已累计耗时。当前join尚未返回，因此全OR/全set的本轮计时及组分布尚未打印，最新完整大轮仍为上一轮197.332/28.146秒。

已超过10800秒名义限制，仍属于本次明确保留的join延后检查时限路径；没有擅自停止或改变任务来提前取结果。等待本轮返回才更新A/B结论，不把上一轮数据冒充当前爆炸轮。状态、stdout尾部和完整线程采样在data/20261108-ng-n60-bitmap-ab/progress-2247；本次无远端写入、重启或配置变动。

## 30. n60爆炸轮返回：OR约5736秒，set约262秒

2026-11-08 23:24:07（北京时间）只读核对DESKTOP-06K8JI8（100.68.243.112）：新诊断已于22:54:14返回，child为SUCCEEDED/exit0，scheduler为FINISHED且total=1 succeeded=1 failed=0，本批11072/11680均已退出。求解器结果明确为TIME_LIMIT，不是最优：耗时11753.946845秒、处理1节点、根节点未闭合，incumbent=358596、bestBound=280807.574575、gap=21.692497%。超出10800秒仍是此前保留的长join延后检查时限，不是新改了预算。原12704/8268在本次查询时仍存在，本次未停止或修改原任务。

目标爆炸轮为pricing9/DSSR23；下表都是同状态位图聚合本身的墙钟时间，单轮与累计分开，不含成员索引准备、相等核对和解释性分档抽样。

| 范围 | 全OR秒 | 全set秒 | OR/set |
| --- | ---: | ---: | ---: |
| pricing9/DSSR23单轮 | 5736.067425 | 261.761586 | 21.913 |
| 全部167轮累计 | 5987.271546 | 313.330987 | 19.108 |

爆炸轮OR为95.60分钟，set为4.36分钟，组件差5474.306秒（91.24分钟）。这一轮实际聚合8311882557个可剪组，前向164805组/1785621个label；组大小中位7、P90为25、最大259。按实际聚合次数加权，每组平均仅10.749个成员编号，但源BitSet平均覆盖1198.224个64位字，解释了全宽OR扫描与逐成员set工作量的明显差距。sumW=9959499585913、sumK=89343149614，sumW/sumK=111.475；这个比值不是mean(W/k)。成员索引净载荷7142484字节（不含对象头），本轮准备395.049ms、相等核对819.024ms、分档抽样17.763ms，均另计。

全部167轮累计7452637次成对mask构造、9265020055次组聚合，位图不一致0；准备0.567秒、核对1.120秒、抽样0.024秒。最后大轮主导了收益，不推翻六例中密集小跨度组OR更快的观察，也不据此直接选统一阈值。最终config dump共199项config字段，排除输出根目录、live trace路径、instanceName三个运行地址字段，其余196项与原n60配置完全相同；正式流程仍只消费A的OR结果，B仅影子计时。未修改源码、正式profile或其它任务。

当前结论：此前怀疑的长join位图聚合确有很大的局部优化空间，本例中成员set比全宽OR快约21.9倍且结果逐位相同。但没有测出优化后的完整join或完整求解时间；诊断运行还承担B双算和统计开销，wall-time反馈也可能改变后续路径，不能直接减去组件差额冒充正式求解提速。最终逐轮、q×k/W分布及汇总见data/20261108-ng-n60-bitmap-ab/final-statistics，终止状态、core/components/config与scheduler收尾在final-status，完成与配置核对摘要为completion-record.json；完整stdout仍在原远端诊断目录和本地隔离下载目录。

## 31. 同算例TI结果及两种gap口径

23:37复核n60/set02/F3/m3/high/wide的已有TI Cluster结果：从20261107-n60-ng-ti-comparison/paired.csv回到third/audit-rows.tsv中保存的core-summary和run.properties原文解码核对。TI、TI+SRI与本次NG输入SHA、seed文件SHA及seedFingerprint完全一致，均启用Cluster且theta=0.5、temporal=0、CPLEX1线程；TI历史profile为2026-09-09-v6，NG为v8，不能说三算法全部配置字段或版本完全相同。以下TI是已保存的完成输出，不是本次重新求解；四项solver状态均为TIME_LIMIT，incumbent全部为358596。

| 运行 | 总秒 | 节点 | bestBound | gap% |
| --- | ---: | ---: | ---: | ---: |
| TI + Cluster，历史完成结果 | 10801.272193 | 656 | 355366.875421 | 0.900491 |
| TI+SRI + Cluster，历史完成结果 | 10800.054025 | 1 | 338407.135775 | 5.629975 |
| NG此前已完成原跑，81e144e4 | 20448.405649 | 1 | 286640.947321 | 20.065771 |
| NG本次OR/set双算，caecd7db | 11753.946845 | 1 | 280807.574575 | 21.692497 |

本例TI根节点用72.576857秒闭合到331243.403979，之后进入树；TI+SRI仍在根处理中。NG此前原跑与本次双算的gap不同，后者大1.626726个百分点，因UB相同，对应LB低5833.372746。两次实际耗时和时序反馈路径不同，这张表不是正式位图策略A/B性能结论。

尤其“全OR和全set的gap是否相同”不能按两个独立求解回答：它们只在同一次pricing内生成两张相同掩码，只有OR进入真实join，因此这次只有一个全局gap=21.692497%，没有另一个全set求解gap。另一项14:00启动的4ac6cb5a逐组计时任务，刚才远端只读仍为RUNNING且没有core-summary，stdout停留于pricing16/DSSR21的join.start；不能把81e144e4已完成原跑的20.065771%冒充该未完成计时任务的最终gap。原任务、正式逻辑均未改动。

## 32. 纯set独立求解实现及七例对照准备

用户授权让set真正参与join，并选择6至7个同类型算例；随后明确要求OR和set分别独立求解、两边开启join分项计时。选定7个n60/F3/high/wide：set02/m3原慢例优先，另为set02/m4、set03/m4、set01/m3、set03/m3、set01/m2、set02/m2。每例同输入、同seed、同JAR独立运行OR和SET，共14项，不复用历史双算或旧版本的求解时间。使用06主机独立新目录，2并发、每JVM24GiB、CPLEX1线程、10800秒名义时限和100000节点；Cluster→Arc、CutSet关闭、全节点scalar fixing和probe等原A参数不动。旧join延后检查时限的问题仍按约定未修，因此名义3小时不等于硬退出期限。

新增默认关闭的twet.bpc.ngDssrJoinBitmapUseSet开关：建组后将forward组的完整memberIndices一次性展开为int数组，每次组判可剪后逐成员set，仍返回相同BitSet供外层缓存和nextClearBit使用。不是只保留包络贡献者，不是每次聚合重扫BitSet，也不改变组对判断、组排序、terminal释放或后续label拼接；默认分支继续原OR。同状态双算诊断优先且仍只返回OR，不能混作纯set实验。独立实验wrapper在NG类初始化之前设置OR/SET，并开启原joinPhaseTiming、关闭bitmapCompare；stdout显式记录实际模式。该wrapper只用于实验，不改正式求解入口与profile。

本地release21 focused编译通过。OR、SET、SET+分项计时、SET+双算四种模式下，专项多字掩码、缓存复用、释放后重建，以及4种固定dual的Phase-I/普通目标正负列场景，返回列顺序、真实cost/reduced cost摘要和证书完全相同；实际建组另核对不贡献包络的label也进入成员数组且数组只准备一次。2560批位图并集、join边界、1972项traced/Phase-I、1081项历史端点/12次exact、CB单点和心跳回归通过。首次扩大回归命令因PowerShell未引用-D参数而被拆词，仅为启动命令错误；引用后全套通过，未改代码规避。

包由scripts/remote/prepare-ng-pure-set-20261108.py从提交src快照全新构建，逐项检查Java major≤65、原输入/seed SHA、原非地址参数和有效seed指纹。此处记录为已实现和本地验证、准备部署，不能当已启动；实际JAR hash、远端hash验收、进程和断开SSH后存活另续记。两边逐组计时具有读时钟开销，比较总时间时保留这一口径，不以局部bitmap倍数替代整个join或求解收益。

## 33. 七例独立OR/set已按四并发启动

本批源码为83861aeb4978941b24a3a3ed50f5d1085eea4855，包含此前已提交的端点、CB/Phase-I、M修复确认、缓存释放、TI debug及SRI稳定化等主线修正，再加入默认关闭的纯set开关。solver.jar从该提交src快照全新release21构建，484个class均不高于major65，SHA-256为76340285b489cfc9738a143d46fdbad29e36c773b2d215f801bb1688d71d5b12；运维wrapper的tools.jar为a4b603aa3c84df560c64e3ab263c7114bf2a56405ea9499d5b62579df9a6265f。实际打包JAR上再次运行OR/SET分项计时回归，四种固定dual下返回列顺序、cost/RC摘要和证书一致。不是用旧81e144e4部署JAR加一个运行参数。

06主机独立目录为D:/ccx_work/考虑交付的机器调度/work1/experiments/20261108-ng-join-pure-set-83861aeb。23:48原按2并发启动，随后用户明确改为4并发；只精确停止本新批18784/6380/19588，复查相关JVM归零，核验绝对路径后把两份未完成输出、旧锁、调度日志和原2并发配置移入本目录interrupted-2parallel，不纳入14项比较。没有删除已完成原实验或停止其它主机任务。修改worker为4并发，solver/wrapper JAR、manifest、输入和seed均未改变，重新scan为14项待运行、已有SUCCESS为0。

四并发于23:51:55启动：scheduler15660，首批为set02/m3的SET6932、OR7892，以及set02/m4的OR14412、SET5268。主动关闭本机启动SSH39524后，23:53:18独立连接仍为1个scheduler/4个solver，runId无重复；主机物理内存137112563712字节，空闲116571709440字节。JVM实际system_properties确认两项UseSet=true、两项false，四项bitmapCompare=false、joinPhaseTiming=true；VM.flags确认四项MaxHeapSize=25769803776，run.properties/CLI为v8、CPLEX1、原seed和全节点fixing深度2147483647。此时m4的OR/SET分别已有11/13轮join分项计时，m3尚在根预处理，不能把初始小轮的计时当最终收益。

启动验收工具首次用严格UTF-8读取活stdout时发生MalformedInputException，只读工具退出，四项solver并未报错或受影响。将该工具的新增扫描改为与原扫描相同的容错解码，只抽取ASCII策略/计时行；当前进程已使用原tools.jar，因此不覆盖它，另置438c9b8c2726260776697f2f264e434ba8d6bc4048aa879d2b903388add966bc的audit-live.jar，并备份原audit.cmd后令验收入口优先使用该独立只读类。solver和wrapper未改、未重启，最终静态hash清单更新为38项。后续查询直接使用本目录audit.cmd，不再调用旧严格解码类。

本地data/20261108-ng-join-pure-set保存完整manifest、7组14项数据/seed追踪、JAR/资产hash、实际四进程属性、启动及断开快照和回归小日志；远端startup-record.json同样落盘。正式策略默认仍为OR，本批为独立实验，未把纯set提升为正式默认；长join超时响应缺口仍未改。此时验证范围为正确启动、实际模式和早期推进，最终总时间、join分解、最优数量与gap待完成后比较。
