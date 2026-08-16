# 正式计算实验 pilot 包

这个目录先验证数据派生、共享起点、任务依赖、结果输出和服务器并发，不代表论文样本已经冻结。三种定价算法统一使用运行时 `BestBpcProfiles.VERSION` 对应的参数；每个场景先生成一次固定初始列，随后三种算法复用同一快照和 SHA-256 fingerprint。

执行：`java HEU.ExperimentBatchScheduler manifest.tsv 4`。每个子 JVM 固定 CPLEX 单线程，调度器始终最多保持 4 个独立进程。
也可以只执行 `manifests/` 下与论文实验小节对应的单独 manifest；每个子 manifest 已包含自己依赖的 seed 任务。

`pricing-comparison` 比较 n=40/50/60、m=2/3/4、相对窗口 0/2/6 倍平均处理时间以及时间尺度 1/5/10。外包模型和灵敏度不与时间尺度做全因子乘积。

已准备实例记录数：39；当前每个规模只取按文件名排序后的前 3 个 case。n=100 的 m=2/3/4/5 数据只进入 `instances.tsv`，默认不进入耗时很高的完整精确批次。

总 manifest 包含 243 个共享 seed 任务和 945 个求解任务。其中 pricing comparison=729，outsourcing formulation=54，outsourcing price=81，outsourcing discount=81。这些是场景/算法任务数，不是不同原始数据实例数。

正式批量运行前必须重新确定 `casesPerSize` 和分层抽样规则；当前“前几个文件”只适合 smoke/pilot，不能直接作为论文代表性样本。
