# NG-DSSR B 正式主线准备

仅准备，尚未启动。配置由冻结solver.jar中的命名profile与solve.tsv覆盖项共同决定，profile-lock仅供审计。

复用索引只引用第二机807项原结果；3项历史OOM未加入常规六并发。新任务与对应分支的TI共用输入和seed：random对Arc、family对Cluster。

本机90项；n60限10800秒，n80限18000秒，100000节点、单模型单CPLEX线程；worker以6并发调用调度器。

worker.lock、worker.exit-code和runs均不应在准备阶段出现。后续经用户确认后按既有远程后台SOP启动，不在本次执行。
