# 2026-09-03 大规模 family NG-DSSR Cluster 后续批次

本批次在现有`n80/m3、n100/m4`批次完成后自动接续，不与其争抢并发资源。选取`set01/set03/set05`，分别求解`n80/m4`和`n100/m5`，共6例；同一set可直接比较机器数增加后的root pricing和Cluster分支表现。

算法、部署和配置均保持不变：部署提交`c887d5cd16dba07ecbd0aa03cb7f7840f9cd58a7`、正式配置`2026-08-30-v5`、`NG_DSSR`、setup-only Cluster、严格类型优先、`theta=0.5`、时间权重0、每例21600秒、节点上限100000、scheduler最大并发6。

远端目录为`D:/ccx_work/考虑交付的机器调度/work1/experiments/20260903-cluster-ng-large-followup-6h`。`run-after-current.cmd`和`run-cluster.cmd`均通过`jcmd -l`确认旧manifest的scheduler已经退出。seed阶段返回非零时停止，全部成功后才启动正式求解。状态由目录中的`WAITING/SEEDING/RUNNING/COMPLETE/FAILED`标记文件表示，`deferred.lock`防止重复启动。

2026-09-03 20:58首次排队时，原PID版等待检查因受限账户下`tasklist /FI`返回假阴性而提前进入seed阶段，6个ALNS seed短时与当前6个solver并行。发现后立即尝试按新scheduler PID终止，但远端命令策略拒绝`taskkill`；断开启动SSH也没有终止远端Java。正式solve尚未启动，并已在seed完成前把`run-cluster.cmd`替换为基于`jcmd -l`识别完整旧manifest的二次门禁，因此后续6个正式solve仍只会在当前scheduler结束后启动。该短时seed竞争可能轻微放大当前批次相应时段的wall time，分析时必须保留这一干扰说明。此后删除无效的`start /b`启动脚本，并把主等待检查也改为同一`jcmd`机制。

20:59复核时6个seed均已成功，`succeeded=6, failed=0`，seed错误日志为空。后续目录虽已进入`RUNNING`标记，但此标记表示包装器已调用带门禁的`run-cluster.cmd`；`jcmd -l`确认旧scheduler仍在、后续solve scheduler和solver均为0，因此当前实际状态是等待旧批次结束。等待进程已脱离本地SSH，本机关闭不会取消后续接续。

2026-09-03 23:30按用户决定不再运行原批次队列中的`n100-set02--set05/m4`。由于scheduler仅在启动时读取manifest和检查`SUCCESS`，不能通过修改TSV或补标记改变内存中的pending队列；因此新增`skip-old-remaining.cmd`，只监控四个完整runId，任务一旦由原scheduler启动便立即按其精确PID执行`tskill`。当前首批6个solver及原scheduler均不在匹配范围内，不受影响。四个跳过任务失败退出后，原scheduler结束，已有后续门禁随即启动本批6个正式solve。

2026-09-04 09:14复核推翻了上一段的执行预期。watchdog没有在启动SSH断开后继续存活，四个`n100-set02--set05/m4`于9月4日00:00左右启动，并于06:00左右各运行满6小时后由旧scheduler记为成功；它们实际没有被跳过。后续包装器也未自动接续：运行中的`run-cluster.cmd`被原地上传覆盖后提前返回，留下`FAILED: Solve scheduler failed`，没有创建后续scheduler或任何solve目录。远端无Java进程后已清除陈旧状态标记，scan确认6个seed全部成功、6个solve全部pending，并于09:14直接启动`run-cluster.cmd`。断开本地后台SSH并等待8秒后再次核验，远端仍保持1个后续scheduler和6个唯一solver，分别为`n80-set01/set03/set05-m4`与`n100-set01/set03/set05-m5`。本轮不再依赖watchdog或修改运行中的批处理文件。

2026-09-04 10:41复核时6例均正常运行但仍停留根节点，没有root certificate或Cluster分支。`n80-set01/set03/set05-m4`完成exact `41/39/39`次，累计exact `4917/4712/4857秒`，最近一次耗时`169/101/161秒`，最好安全gap为`5.09%/3.12%/1.53%`；`n100-set01/set03/set05-m5`完成exact `14/21/19`次，累计`4554/4278/4551秒`，最近一次耗时`369/176/325秒`，最好安全gap为`8.67%/3.34%/5.15%`。最近exact仍各返回`2/1/3/4/4/6`条负列，因此不能把中途安全界写成已闭合root bound。

同一时段按最近5次完整exact汇总forward/backward比例。`n80-set01/set03/set05-m4`的时间比为`1.41/5.72/3.67`，constructed extension比为`2.75/6.03/5.07`，kept label比为`3.16/5.88/4.20`；`n100-set01/set03/set05-m5`的时间比为`2.98/1.33/0.34`，extension比为`2.64/0.93/0.81`，kept比为`2.79/1.07/0.76`。方向失衡明显依赖算例：80-set03/set05正向重，100-set05反向重，100-set03接近平衡；probe的浅层4倍接受阈值不能保证完整深层时间比低于4。

2026-09-04 11:32复核时6个solver和scheduler均仍在运行。`n80-set03/m4`已用51次root exact闭合根节点，root bound为`51386.500000`，相对初始incumbent `52279`的gap为`1.707%`；随后先后选择`clusterBoundary(...)=1.5`和`clusterPair(1->0)=0.121212`，已进入node 3。`n80-set05/m4`已用46次root exact闭合根节点，root bound为`92541.285714`，相对初始incumbent `92941`的gap为`0.430%`；随后选择`clusterBoundary(...)=2.285714`，已进入node 2。因此本批次已经确认setup-only Cluster候选在两个实例中实际被严格优先的strong branching选中，但尚无Arc对照，不能据此判断收益。

其余4例仍停留根节点。`n80-set01/m4`完成55次root exact，当前最好安全界`79318.053782`、相对incumbent的gap为`2.530%`，最近一轮耗时`258.9秒`并返回29列；`n100-set01/set03/set05-m5`分别完成`22/34/28`次root exact，当前最好安全gap为`8.115%/1.751%/4.811%`，最近一轮耗时`279.6/260.2/397.9秒`并返回`4/5/34`列。它们仍有负列，以上仅是已完成exact产生的最好安全界，不是已闭合root bound。

2026-09-04 13:39复核时，`n80-set03/m4`已完整求解，`incumbent=bound=51401`、gap为0、总时间`12730.556秒`，依次处理到node 4，其中node 4由pseudo cost直接剪掉；全程进行了2次Cluster分支。`n80-set01/m4`也已闭合根节点，root bound为`80098.352941`、root gap为`1.571%`，完成2次Cluster分支后正在node 3；`n80-set05/m4`同样已进行第2次Cluster分支并正在node 3。`n100-set03/m5`新闭合根节点，root bound为`54494`、root gap为`0.936%`，选择Cluster boundary后正在node 2。

尚未闭合根节点的只剩`n100-set01/m5`和`n100-set05/m5`。前者完成42次exact，当前最好安全gap为`6.578%`，最近一轮耗时`409.5秒`并返回14列；后者完成43次exact，最好安全gap仍为`4.811%`，最近一轮耗时`531.3秒`并返回8列。远端当前保留5个solver和1个scheduler，已完成的`n80-set03/m4`进程正常退出。

2026-09-03 23:30按用户决定不再运行原批次队列中的`n100-set02--set05/m4`。由于scheduler仅在启动时读取manifest和检查`SUCCESS`，不能通过修改TSV或补标记改变内存中的pending队列；因此新增`skip-old-remaining.cmd`，只监控四个完整runId，任务一旦由原scheduler启动便立即按其精确PID执行`tskill`。当前首批6个solver及原scheduler均不在匹配范围内，不受影响。四个跳过任务失败退出后，原scheduler结束，已有后续门禁随即启动本批6个正式solve。
