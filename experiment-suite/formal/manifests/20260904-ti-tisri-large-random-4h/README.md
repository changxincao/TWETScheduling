# 2026-09-04 大规模 random TI/TI+SRI 对照

本批次复用`20260904-arc-ng-large-random-4h`的6个`random/base/zero`实例及对应ALNS seed，分别运行正式`TIME_INDEXED`和`TIME_INDEXED_SRI` profile，共12个solve。实例为`n80-set01/set03/set05, m=4`和`n100-set01/set03/set05, m=5`；全部使用普通Arc分支，显式关闭Cluster，每例时限14400秒、节点上限100000、单JVM单CPLEX线程，scheduler最多并发6。

远端目录规划为`D:/ccx_work/考虑交付的机器调度/work1/experiments/20260904-ti-tisri-large-random-4h`，继续使用已验证的`20260903-c887d5cd`部署。为避免与当前仍在运行的NG-DSSR Arc批次叠加超过6个solver，`run-after-current.cmd`只等待当前`arc-ngdssr.tsv` scheduler退出，随后调用本批scheduler；锁目录防止重复启动。

2026-09-04约17:15完成部署与启动核验。本地清单包含12个唯一run，6个TI与6个TI+SRI，所有实例均成对且参数一致。远端`scan.cmd`返回`selected=12,pending=12`、`TIME_INDEXED=6`、`TIME_INDEXED_SRI=6`。链式启动器只启动一次，已生成`deferred.lock`和`WAITING`，错误日志为空；此时原NG-DSSR批次仍有3个solver和1个scheduler运行，本批尚未提前启动solver。原scheduler退出后，本批将自动以并发6运行，结果只写入本目录的`runs/`。
