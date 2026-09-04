# 2026-09-04 大规模 random NG-DSSR Arc 对照

本批次接在`20260903-cluster-ng-large-followup-6h`全部结束后启动。使用相同规模和set：`n80-set01/set03/set05, m=4`与`n100-set01/set03/set05, m=5`，将数据改为`random/base/zero`，使用正式`2026-08-30-v5` NG-DSSR配置和普通Arc分支。每例时限14400秒、节点上限100000、单JVM单CPLEX线程，scheduler最多并发6。

远端目录为`D:/ccx_work/考虑交付的机器调度/work1/experiments/20260904-arc-ng-large-random-4h`，部署为`20260903-c887d5cd`。本批次显式设置`enableClusterBranching=false`，不使用Cluster候选；每个random实例单独生成匹配的ALNS seed。启动前必须按远端SOP确认上一批scheduler和solver均已退出，再以前台SSH完成seed，并通过`ssh -f -n ... cmd.exe /d /c .../run-arc.cmd`只启动一次正式scheduler。

2026-09-04启动记录：本地清单核验为seed 6行、solve 6行，runId均唯一，全部为`random/base/zero`、`NG_DSSR`、`timeLimitSeconds=14400`和`enableClusterBranching=false`。上传后远端scan得到seed/solve均`selected=6,pending=6`。上一批结束且`jcmd -l`无scheduler/solver后，以前台SSH生成匹配seed，最终`6 succeeded, 0 failed`且seed错误日志为空；复扫为seed `localSuccess=6,pending=0`、solve `pending=6`。

约15:28按SOP只启动一次`run-arc.cmd`。首次核验和主动断开本地启动SSH后12秒的二次核验均显示恰好1个本批scheduler和6个唯一solver，无其他实验Java进程；六条命令均指向本批random实例和输出目录，并带`timeLimitSeconds=14400 --enableClusterBranching=false`。首批live log进一步确认`run.components.branchers=[TariffSegmentBrancher, MachineCountBrancher, ArcBrancher]`、`config.enableClusterBranching=false`、`config.structuredArcStrictTypePriority=false`，其余使用部署中的正式v5配置。本机此后可以关闭，不影响远端运行。

15:36早期检查时6例均已闭合根节点并进入搜索树，不能与上一批family仍卡根的状态混同。`n80-set01/set03/set05`已完成`35/119/132`次exact，平均单次为`10.17/1.11/1.31秒`，平均DSSR轮数为`2.11/1.60/1.76`；`n100-set01/set03/set05`完成`70/59/66`次，平均单次为`2.54/1.91/2.15秒`，平均轮数为`1.47/1.73/1.77`。对应family-Cluster最终平均exact为`163.43/109.57/148.31秒`和`350.31/198.69/318.39秒`，平均DSSR轮数为`11.75/19.36/16.39`和`15.30/10.89/11.15`。除`n80-set01`外，random目前约快`99--148`倍；该差异同时来自DSSR轮数下降和单轮labeling变轻。此处仅为启动约8分钟的中途性能快照，最终完成率和时间仍以4小时结果为准。

15:47再次检查时6例仍在运行，当前节点分别推进到`n80-set01/set03/set05=4/20/26`和`n100-set01/set03/set05=14/7/11`。按当前出队节点的伪下界与open queue顺序计算，认证的全树`LB/UB/gap`依次为：`109032.250980/111429/2.1509%`、`69600.089088/71073/2.0724%`、`122059.332297/124522/1.9777%`、`102929.269092/107790/4.5094%`、`75107.482351/77055/2.5274%`、`131597.065512/138321/4.8611%`。这不是当前节点求解中的暂态LP值，而是该节点入队时已由父节点认证且不高于其余open nodes的下界。当前累计exact平均耗时为`9.592/1.065/1.223/2.445/1.804/2.116秒`，平均DSSR轮数为`2.042/1.683/1.811/1.519/1.896/1.783`。

两批的节点数不能直接解释成“random树更难”。family批次中两例n100在6小时内始终未闭合根节点，另外4例也只处理到node 3--4；时间主要耗在每个节点分钟级、多轮DSSR的exact pricing，尚未来得及频繁分支。random每次exact只有秒级，因此相同墙钟时间内可以快速处理更多节点。另一个混杂因素是family使用严格优先的Cluster aggregate分支，而random使用Arc-only；Cluster一次约束整组边界流，Arc一次只约束一条弧，后者本来就更可能形成较深、较宽的树。当前证据只能确认random节点吞吐量高，不能据此单独断言其完整分支树一定更大。
