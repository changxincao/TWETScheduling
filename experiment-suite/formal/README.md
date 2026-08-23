# 正式计算实验任务包

三种定价算法统一使用运行时 `BestBpcProfiles.VERSION` 对应的参数；每个场景先生成一次固定初始列，待比较方法复用同一快照和 SHA-256 fingerprint。

执行：`java HEU.ExperimentBatchScheduler manifest.tsv 4`。每个子 JVM 固定 CPLEX 单线程，调度器始终最多保持 4 个独立进程。
也可以只执行 `manifests/` 下与论文实验小节对应的单独 manifest；每个子 manifest 已包含自己依赖的 seed 任务。

`pricing-comparison` 使用 W0/W100/W300 和每个任务集合预先生成的 base/medium/high 三个尺度比较三种 BPC。medium/high 对每个任务使用配对的异质倍率，W 按名义倍率 10/20 放大；setup 按实际总 processing workload 比例整体缩放。所有数据已写入独立 `.dat`，runner 不接收时间倍率。`outsourcing-performance` 在原时间尺度完整比较三档价格和 columns/masterVariables。`outsourcing-discount` 只补 n=50、中价、无折扣任务。实验三不生成求解任务。

已准备落盘实例记录数：540；每个规模固定取 5 个任务集合，并生成 random/family 与 base/medium/high 尺度。完整抽样、倍率、逐任务放大和 setup 审计见 `instances/` 下的 metadata 与两个 `post-generation-*-audit.tsv`。

总 manifest 包含 3330 个共享 seed 任务和 8190 个求解任务。其中 pricing comparison=4860，outsourcing performance=3240，outsourcing discount=90。这些是场景/方法任务数，不是不同原始数据实例数。

外包报价为 q_j=p_j*max(wE_j,wT_j)；Q1/Q2 从 n=50 不重复任务集合的报价总量中位数按 25%/50% 一次确定，并写入每条外包任务参数。
