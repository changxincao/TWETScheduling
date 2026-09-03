# 2026-09-03 大规模 family NG-DSSR Cluster 后续批次

本批次在现有`n80/m3、n100/m4`批次完成后自动接续，不与其争抢并发资源。选取`set01/set03/set05`，分别求解`n80/m4`和`n100/m5`，共6例；同一set可直接比较机器数增加后的root pricing和Cluster分支表现。

算法、部署和配置均保持不变：部署提交`c887d5cd16dba07ecbd0aa03cb7f7840f9cd58a7`、正式配置`2026-08-30-v5`、`NG_DSSR`、setup-only Cluster、严格类型优先、`theta=0.5`、时间权重0、每例21600秒、节点上限100000、scheduler最大并发6。

远端目录为`D:/ccx_work/考虑交付的机器调度/work1/experiments/20260903-cluster-ng-large-followup-6h`。`run-after-current.cmd`和`run-cluster.cmd`均通过`jcmd -l`确认旧manifest的scheduler已经退出。seed阶段返回非零时停止，全部成功后才启动正式求解。状态由目录中的`WAITING/SEEDING/RUNNING/COMPLETE/FAILED`标记文件表示，`deferred.lock`防止重复启动。

2026-09-03 20:58首次排队时，原PID版等待检查因受限账户下`tasklist /FI`返回假阴性而提前进入seed阶段，6个ALNS seed短时与当前6个solver并行。发现后立即尝试按新scheduler PID终止，但远端命令策略拒绝`taskkill`；断开启动SSH也没有终止远端Java。正式solve尚未启动，并已在seed完成前把`run-cluster.cmd`替换为基于`jcmd -l`识别完整旧manifest的二次门禁，因此后续6个正式solve仍只会在当前scheduler结束后启动。该短时seed竞争可能轻微放大当前批次相应时段的wall time，分析时必须保留这一干扰说明。此后删除无效的`start /b`启动脚本，并把主等待检查也改为同一`jcmd`机制。

20:59复核时6个seed均已成功，`succeeded=6, failed=0`，seed错误日志为空。后续目录虽已进入`RUNNING`标记，但此标记表示包装器已调用带门禁的`run-cluster.cmd`；`jcmd -l`确认旧scheduler仍在、后续solve scheduler和solver均为0，因此当前实际状态是等待旧批次结束。等待进程已脱离本地SSH，本机关闭不会取消后续接续。
