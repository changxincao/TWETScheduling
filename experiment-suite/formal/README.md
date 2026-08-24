# 正式计算实验任务包

三种定价算法统一使用运行时 `BestBpcProfiles.VERSION` 对应的参数；每个场景先生成一次固定初始列，待比较方法复用同一快照和 SHA-256 fingerprint。

执行：`java HEU.ExperimentBatchScheduler manifest.tsv 4`。每个子 JVM 固定 CPLEX 单线程，调度器始终最多保持 4 个独立进程。
也可以只执行 `manifests/` 下与论文实验小节对应的单独 manifest；每个子 manifest 已包含自己依赖的 seed 任务。
调度器支持按 manifest 显式字段筛选，例如：`java HEU.ExperimentBatchScheduler manifests/pricing-comparison.tsv 4 --select=size=50 --select=setupType=family --select=scaleLevel=base --select=windowLevel=narrow --select=algorithm=NG_DSSR,TIME_INDEXED`。多个字段取交集，逗号分隔值取并集；筛选 solve 后自动补齐 seed 依赖。
正式启动前可在同一命令末尾增加 `--scan-only`。该模式只扫描本地 `SUCCESS` 并报告匹配数、依赖数、已完成数和待运行数，不创建目录或启动子进程。正式运行也会先做相同扫描，已有 `SUCCESS` 的任务直接跳过。

`pricing-comparison` 使用逐任务落盘的 zero/narrow/wide 窗口和 base/medium/high 三个尺度比较三种 BPC。medium/high 的 processing 与 due-center 倍率独立抽取，setup 按实际 processing workload 比例整体缩放。`outsourcing-performance` 使用包含完整调度与经济数据的单文件，在原时间尺度比较三档报价和 columns/masterVariables；`outsourcing-discount` 只补 n=50、中价、无折扣的完整文件。runner 不构造任何物理或经济数据。

已准备落盘实例记录数：1620；每个规模固定取 5 个任务集合，并生成 random/family 与 base/medium/high 尺度。完整抽样、倍率、逐任务窗口、setup 和外包审计见 `instances/` 下的 metadata 与三个 `post-generation-*-audit.tsv`。

总 manifest 包含 3330 个共享 seed 任务和 8190 个求解任务。其中 pricing comparison=4860，outsourcing performance=3240，outsourcing discount=90。这些是场景/方法任务数，不是不同原始数据实例数。

外包报价为 q_j=p_j*max(wE_j,wT_j)；Q1/Q2 从 n=50 不重复任务集合的报价总量中位数按 25%/50% 一次确定，并写入每条外包任务参数。
