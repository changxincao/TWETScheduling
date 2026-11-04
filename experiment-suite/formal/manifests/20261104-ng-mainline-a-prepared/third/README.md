# NG-DSSR A主线准备

本机n80共270项，只准备未启动。5个独立JVM、每模型/CPLEX线程1，family按F<m、F=m、F>m顺序优先，然后random。全节点scalar TI fixing，family Cluster→Arc、random Arc；CutSet/SuccessorSet关闭。其余逐项等同原A参数。

807项旧A只引用、不移动、不重算；3项历史失败不进入常规队列。solver.jar沿用A--D冻结包，不编译本地算法改动。

profile-family/random.properties仅为验收参考，实际由冻结JAR与solve.tsv决定。

准备阶段不得有runs、worker.lock或worker.exit-code，不运行worker。
