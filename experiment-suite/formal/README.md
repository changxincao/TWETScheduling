# 正式计算实验任务包

三种定价算法统一使用运行时 `BestBpcProfiles.VERSION` 对应的参数；每个场景先生成一次固定初始列，待比较方法复用同一快照和 SHA-256 fingerprint。

每个子 JVM 固定 CPLEX 单线程，调度器始终最多保持 4 个独立进程。若从命令行启动，必须先把 `target/classes`、项目依赖和 `cplex.jar` 放入 classpath，并通过 `-Djava.library.path=<CPLEX native目录>` 配置本机动态库，再运行 `HEU.ExperimentBatchScheduler <manifest> 4`。
推荐在 Eclipse 中直接运行 `HEU.FormalExperimentBatchLauncher`：无参数时读取类顶部的 manifest、并发数、筛选条件和 scan-only 配置；传入参数时仍使用原命令行语法。首次运行默认只扫描，确认任务数量后将 `SCAN_ONLY` 改为 `false` 才会启动子进程。
也可以只执行 `manifests/` 下与论文实验小节对应的单独 manifest；每个子 manifest 已包含自己依赖的 seed 任务。
调度器支持按 manifest 显式字段筛选，例如：`java HEU.ExperimentBatchScheduler manifests/pricing-comparison.tsv 4 --select=size=50 --select=setupType=family --select=scaleLevel=base --select=windowLevel=narrow --select=algorithm=NG_DSSR,TIME_INDEXED`。多个字段取交集，逗号分隔值取并集；筛选 solve 后自动补齐 seed 依赖。
正式启动前可在同一命令末尾增加 `--scan-only`。该模式只扫描本地 `SUCCESS` 并报告匹配数、依赖数、已完成数、待运行数及所选算法/外包模型计数，不创建目录或启动子进程。正式运行也会先做相同扫描，已有 `SUCCESS` 的任务直接跳过。
每个任务成功标记保存在该任务输出目录的 `SUCCESS` 文件；seed 的快照本体保存在 `seeds/<scenario>.seed`，对应完成标记保存在 `runs/seed-<scenario>/SUCCESS`。调度器从当前 JVM 继承 Java executable、完整 classpath 和 `java.library.path` 并传给子 JVM；Eclipse 项目 classpath 提供 `cplex.jar`，launcher 无参数启动时由 `CPLEX_NATIVE_LIBRARY_PATH` 提供本机 native DLL 目录，命令行模式则使用 JVM 的 `-Djava.library.path`。

`pricing-comparison` 使用逐任务落盘的 zero/narrow/wide 窗口和 base/medium/high 三个尺度比较三种 BPC。medium/high 的 processing 与 due-center 倍率独立抽取，setup 按实际 processing workload 比例整体缩放。`outsourcing-performance` 使用包含完整调度与经济数据的单文件，在原时间尺度比较三档报价和 columns/masterVariables；`outsourcing-discount` 只补 n=50、中价、无折扣的完整文件。runner 不构造任何物理或经济数据。

已准备落盘实例记录数：1620；每个规模固定取 5 个任务集合，并生成 random/family 与 base/medium/high 尺度。完整抽样、倍率、逐任务窗口、setup 和外包审计见 `instances/` 下的 metadata 与三个 `post-generation-*-audit.tsv`。

总 manifest 包含 3330 个共享 seed 任务和 8190 个求解任务。其中 pricing comparison=4860，outsourcing performance=3240，outsourcing discount=90。这些是场景/方法任务数，不是不同原始数据实例数。

外包报价为 q_j=p_j*max(wE_j,wT_j)；统一使用固定断点 Q1=4000 和 Q2=8000。
