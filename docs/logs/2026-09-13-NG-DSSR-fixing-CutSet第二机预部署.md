# NG-DSSR fixing 与 CutSet 第二机预部署

2026-09-13：按用户要求只配置、不启动。第二台机器 `100.68.243.192` 的新批次放在 `D:\ccx\work1\experiments\20260913-ng-fixing-cutset-le50`，冻结求解包放在 `D:\ccx\work1\deployments\20260913-ng-cutset-supernode-v8`。旧 80/100 批次及其输出未覆盖。输入、共享 seed 和输出均指向新批次目录；每项限时 10800 秒、最多 100000 节点，正式 worker 配置为 6 个并行 JVM，每个求解器仍为单线程。当前没有启动 worker、smoke 或任何求解。

本批次只取 20/40/50 任务的 NG-DSSR。family 每个实例分别做 F-A（Cluster→Arc、每节点 scalar TI arc fixing）、F-B（Cluster→Arc、仅根节点）、F-C（Cluster→supernode CutSet→Arc、每节点）和 F-D（同 F-C、仅根节点）；random 做 R-A/R-B（Arc、每节点/仅根节点）。六组各 405 项，共 2430 项，810 份输入和 810 份 seed 两两复用。family 的 `F<m` 项排在前面。root-only 仅通过 `timeIndexedCompletionBoundNodeArcFixingMaxDepth=0` 控制主树的 scalar TI arc fixing；根 TI 预处理及 pricing 的 completion-bound 机制仍按 v8 profile 保留。Cluster 使用 setup-only 距离、`theta=0.5`，严格类型优先；因此节点若已有 Cluster 候选，CutSet 在该节点不会进入 strong trial。

主线新增默认关闭的 `cutSetSupernodeSeeds`。C/D 显式打开时，近整数相邻弧流量达到 `0.999` 的任务经并查集合并为 supernode，再按原有最大 support-affinity 逐组扩张；不启用 target、first-layer 或 size 过滤。CutSet 的聚合分支行、`Q<=0` 禁弧、dual 展开和其他 pricing 流程未改。A/B 和 random 关闭该开关，旧 singleton CutSet 行为保留。正式 runner 接受并记录此参数；命名 profile 仍默认关闭 CutSet 和 supernode。

本地 Java 21 编译、`StructuredArcFlowBranchingTest`、`BestBpcProfilesTest` 均通过。第二机上的上传包 SHA256 与本地一致；解压后静态验收逐一核对了全部 810 份输入和 810 份 seed 的 SHA256，确认六组各 405 项、jar 哈希及参数路径，`runs` 目录尚不存在，状态为 `PREPARED_NOT_STARTED`。求解行为和远端 Java/CPLEX 联合运行尚未验收；后续若启动，应先做少量 smoke，再启动正式 worker。源码工作树快照、逐 Java 文件哈希及 jar 哈希保存在部署目录。远端只读验收脚本为批次目录的 `verify-v3.ps1`；前两版脚本因路径分隔符和 PowerShell 数组表达式检查错误退出，不代表数据或求解器失败。
