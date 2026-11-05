# NG-DSSR四项join OOM分析

主要结论：4项均为Java heap space，且正反向labeling已经完成、进入join后才触发，不能归为CPU不稳定、前述Phase-I超时异常或TI禁弧BitSet整数溢出。当前join预筛索引确有一层按唯一调用链不再复用的group-pair缓存，且其规模可随前后向组对数近似乘积增长；3项直接在该缓存的IdentityHashMap扩容时OOM。另一层预筛BitSet还跨全部forward终点保留，进一步累积内存。没有失败时的heap dump，因此不能断言这两层各占堆的精确比例，也不能排除PWLF和label状态已有较大占用。本轮只分析记录，没有修改GC代码、堆参数、远端运行或重新求解。

## 1. 四项现场

均为n60、m4、family/F3、wide窗口，repair=false。单项stdout、stderr、status和run.properties副本保存在`.codex-tmp/ng-join-oom-20261105`，原输出未改动。

| 算例 | 失败时节点/pricing调用/DSSR轮 | fwKept | bwKept | OOM位置 |
| --- | --- | ---: | ---: | --- |
| set02/base/wide | 1 / 28 / 11 | 570121 | 177142 | prunedForwardIndices:8554，new BitSet |
| set02/high/wide | 6 / 118 / 11 | 587782 | 472257 | canPruneGroup:8585，IdentityHashMap.put/resize |
| set03/base/wide | 2 / 48 / 10 | 561159 | 185811 | canPruneGroup:8585，IdentityHashMap.put/resize |
| set03/high/wide | 2 / 68 / 12 | 806207 | 204145 | canPruneGroup:8585，IdentityHashMap.put/resize |

4项最后heartbeat均为join.start，FW/BW队列已空。fwKept/bwKept是本轮累计插入计数，后续可被占优删除，不等于join时仍存活的label数，更不是不同ng-memory group数，不能直接相乘当真实缓存条目数。节点仅1/2/6，也说明并非大树累计master列池是所有4项共同的必要原因；单次exact的join已足以触及堆上限。

实际冻结v8源码与当前本地GC文件SHA-256均39331329ab3f3792ce3cc5c072a37b901025f4705b341e677df0b4443255fa49。本批profile明确enableNgDssrJoinEnvelopePrefilter=true、enableNgDssrJoinEnvelopeCompression=false，走的是预筛后标准label join，不是完整envelope压缩替代join。

## 2. 第一层缓存的重复性

JoinEnvelopeIndex.pruneByGroup保存`forwardGroup -> backwardGroup -> Boolean`。组按terminal job和ngMemorySet构造，一个forwardGroup只属于一个固定lastJob，map.values转成groups后没有重复对象。

但它唯一的调用入口是prunedForwardIndices：这个方法已经在`prunedIndicesByBackwardGroup[backwardGroup][lastJob]`保存整份预筛结果。对同一个backwardGroup和lastJob，第一次调用遍历全部forwardGroups并调用canPruneGroup；以后同组其他backward labels直接拿现成BitSet，根本不会再进入canPruneGroup。仓库搜索确认canPruneGroup没有第二条调用路径。因此每个forwardGroup/backwardGroup组合只会计算一次，内层Boolean缓存没有后续重复查询收益，却一直把这些关系保存到整轮join结束。

例如若实际需要检查1万个forward group与1万个backward group，不是只保存2万个group，而是可能额外保存1亿条组对关系；这是规模解释，不是这4项实测组数。即使“不可拼接”的结论很快由集合相交等条件得到，代码仍会把true/false写入map。三项正好在这层map扩容处OOM，说明它是应优先处理的明确低效点，而不是凭异常栈任意猜测“缓存有问题”。

## 3. 第二层BitSet的生命周期过长

prunedIndicesByBackwardGroup对每个backwardGroup保存长度为n+1的BitSet数组，每个lastJob的BitSet按该终点的forward候选数预分配。这个缓存确实能让同组多个backward labels复用，不能笼统说它全部无用。

问题在于外层join按lastJob依次处理，完成某个lastJob后不再返回该终点，但旧终点对应的BitSet仍全部保留到整个join结束。如果对大部分backward groups都访问多个终点，BitSet主体存储近似为各终点`被访问后向组数 × forward候选数 / 8`之和，另加数组、对象和索引开销；只保留当前终点时可降为单终点需求的峰值，而不是跨终点累积。这是可以进一步验证的生命周期优化方向，不需要改变预筛判定或删除合法候选。

set02/base的OOM出现在new BitSet，不等于某一个BitSet本身异常巨大；也可能是已有label/PWLF、envelope、组对map和历史终点BitSet占满了堆，最后一次数组分配才触发异常。没有heap dump不能把分配栈等同于全部内存归属。

## 4. 06主机实际堆额度

从06主机同批存活solver PID11508只读执行VM.flags和VM.info：MaxHeapSize=17104371712字节，即15.93GiB，G1、compressed oops；该主机报告i9-12900、物理65224MiB，约64GiB。这是DESKTOP-06K8JI8/.112，不是此前更换内存的DESKTOP-42976CD/.192，不能据此把另一台的128降64历史移植过来，也没有据本次快照证明06存在内存识别故障。

失败进程已经退出，没有逐进程故障时VM.flags快照；它们的命令、同一Java入口和调度器均未显式设置Xmx，与同批默认额度一致。15.93GiB是当前同批实测值，不冒充4个故障现场的独立堆测量。当前VM.info空闲物理内存约46352MiB是失败释放后的快照，不能反推OOM瞬间整机空闲。单个JVM仍有独立堆上限，不会因为整机还剩内存而自动无限增大。

## 5. 优先方向

第一步应去掉可以证明不再被命中的group-pair Boolean缓存，保留现有BitSet复用及完全相同的组对判定；下一步再将BitSet缓存限制到当前lastJob的有效生命周期。两者都是join缓存存储范围优化，不是关闭ng memory、改变dominance、缩小定价集合或不完全join。但仍应对照返回列、reduced cost证书和剪枝结果，再在失败样本上低并发复核峰值，不能仅凭源码宣布4项都已能求完。

直接加Xmx可以作为独立资源诊断，但5个JVM并行时不能把每个都无条件提高到32/64GiB；也不应靠加堆掩盖组对缓存近似乘积增长。当前没有实施任何一项修改或重跑，4个OOM结果仍保留原资源失败状态。
