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
