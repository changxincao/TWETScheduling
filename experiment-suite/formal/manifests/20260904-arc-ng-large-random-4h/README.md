# 2026-09-04 大规模 random NG-DSSR Arc 对照

本批次接在`20260903-cluster-ng-large-followup-6h`全部结束后启动。使用相同规模和set：`n80-set01/set03/set05, m=4`与`n100-set01/set03/set05, m=5`，将数据改为`random/base/zero`，使用正式`2026-08-30-v5` NG-DSSR配置和普通Arc分支。每例时限14400秒、节点上限100000、单JVM单CPLEX线程，scheduler最多并发6。

远端目录为`D:/ccx_work/考虑交付的机器调度/work1/experiments/20260904-arc-ng-large-random-4h`，部署为`20260903-c887d5cd`。本批次显式设置`enableClusterBranching=false`，不使用Cluster候选；每个random实例单独生成匹配的ALNS seed。启动前必须按远端SOP确认上一批scheduler和solver均已退出，再以前台SSH完成seed，并通过`ssh -f -n ... cmd.exe /d /c .../run-arc.cmd`只启动一次正式scheduler。

2026-09-04启动记录：本地清单核验为seed 6行、solve 6行，runId均唯一，全部为`random/base/zero`、`NG_DSSR`、`timeLimitSeconds=14400`和`enableClusterBranching=false`。上传后远端scan得到seed/solve均`selected=6,pending=6`。上一批结束且`jcmd -l`无scheduler/solver后，以前台SSH生成匹配seed，最终`6 succeeded, 0 failed`且seed错误日志为空；复扫为seed `localSuccess=6,pending=0`、solve `pending=6`。

约15:28按SOP只启动一次`run-arc.cmd`。首次核验和主动断开本地启动SSH后12秒的二次核验均显示恰好1个本批scheduler和6个唯一solver，无其他实验Java进程；六条命令均指向本批random实例和输出目录，并带`timeLimitSeconds=14400 --enableClusterBranching=false`。首批live log进一步确认`run.components.branchers=[TariffSegmentBrancher, MachineCountBrancher, ArcBrancher]`、`config.enableClusterBranching=false`、`config.structuredArcStrictTypePriority=false`，其余使用部署中的正式v5配置。本机此后可以关闭，不影响远端运行。
