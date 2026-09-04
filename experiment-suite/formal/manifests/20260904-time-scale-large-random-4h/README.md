# 2026-09-04 大规模 random 时间尺度放大对照

本批用于检验 base/zero 上表现很强的 TI+SRI 是否会随离散时间尺度放大而退化。选择已经具有 base 对照的 `n80-set03/m4` 和 `n100-set03/m5`，分别使用正式 `medium-heterogeneous`（processing 加权倍率约10）和 `high-heterogeneous`（约20）实例，窗口仍为 `zero`、setup仍为 `random`。每个物理实例同时运行正式 v5 的 `NG_DSSR`、`TIME_INDEXED` 和 `TIME_INDEXED_SRI`，共4个seed和12个solve；每个solve时限14400秒、节点上限100000、普通Arc分支、单JVM单CPLEX线程，scheduler最多并发6。

远端目录为 `D:/ccx_work/考虑交付的机器调度/work1/experiments/20260904-time-scale-large-random-4h`，使用已经验证的 `20260903-c887d5cd` 部署。四个放大实例已在远端只读核验存在。本批通过 `run-after-current.cmd` 等待 `20260904-ti-tisri-large-random-4h` scheduler 完全退出，再以前台seed scheduler生成4份共享seed，全部成功后才以6并发启动12项solve；不会与当前批次叠加超过6个solver。

2026-09-04 晚间完成部署和排队。远端 scan 返回 seed `selected=4,pending=4`，solve `selected=12,pending=12`，算法数量为 `NG_DSSR=4, TIME_INDEXED=4, TIME_INDEXED_SRI=4`。自动接续器只启动一次；主动断开启动SSH并等待后，远端存在唯一 `deferred.lock` 和 `WAITING`，Java列表仍只有前一批的1个scheduler与5个solver，新批没有提前启动Java或超过6并发。前一批退出后按上述seed再solve顺序自动继续，本机关机不影响。
