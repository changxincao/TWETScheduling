# 2026-09-05 正式纯调度计算，n<=60

本批是正式结果目录，不沿用此前诊断实验的SUCCESS或计时结果。输入从原pricing-comparison.tsv筛选size<=60，包含20、40、50、60任务，每档5个任务集合，random/family、base/medium/high、zero/narrow/wide以及原manifest中的机器数全部保留。正式包没有n30这一档。共1080个物理场景、1080项共享seed、3240项求解；每种算法各1080项。

算法为NG_DSSR、TIME_INDEXED、TIME_INDEXED_SRI。统一使用BestBpcProfiles v5，普通Arc分支，显式enableClusterBranching=false、structuredArcStrictTypePriority=false，其他参数沿用正式profile。每项求解10800秒、节点上限100000，每个CPLEX一个线程，scheduler最多6个子JVM。runner开启live.log、stage heartbeat和node progress；最终状态、gap、配置快照和组件统计按原正式runner输出。

远端部署：`D:/ccx_work/考虑交付的机器调度/work1/deployments/20260905-93685d4a`。核心源码提交93685d4a8160efddb859b7da2d37f1a65eb86eb6，没有src工作区差异。全新编译正式runner与scheduler的依赖闭包，使用javac --release 21匹配远端JDK21，solver.jar为固定运行包；另存source-93685d4a.zip，不覆盖旧部署。jar的SHA256为8e6a883329e09c249a451c28d44a6bd39354615f18e04038b7717e01acbeb97e，上传后下载回读校验一致。

远端正式目录：`D:/ccx_work/考虑交付的机器调度/work1/experiments/20260905-formal-scheduling-le60`。其中seed.tsv和solve.tsv分开，seeds保存共享快照，runs/seed-*保存seed日志，runs/pricing-comparison保存正式结果，smoke只保存短程验收，scheduler-logs保存调度日志。run.cmd显式切换到本目录；所有子JVM也以manifest所在目录为工作目录。输入只读使用work1/instances/no_outsourcing/data。

生成脚本为scripts/remote/prepare-formal-le60-20260905.ps1，必须用PowerShell 7运行，不能用Windows PowerShell 5读取无BOM中文脚本。seed.tsv SHA256为61d143534682c6db5250f9cff7340d4e8df265d65286c639df18c4c581e88c7a，solve.tsv为8f81ed1aed933185af68f718de63e1969c4ff65dba4052ef6d2cc7e20d7163f0。input-sha256.tsv保存本批逐文件hash；远端逐个解析真实路径并读取1080个文件，SHA256全部一致。

启动前jcmd确认没有其他scheduler或solver。远端TimeIndexedGraphOptimizationTest、BestBpcProfilesTest通过；其旧格式测试fixture单独放在本目录data/40-2，仅供回归，不被正式manifest引用。seed scan为selected=1080、pending=1080。按既有SOP先前台运行seed-worker.cmd，完成后才验收三算法并后台启动worker.cmd。worker通过原子创建worker.lock防重复启动；未授权不得绕过锁重复启动。

用户随后明确选择远端自动接续。停止本批前台seed scheduler（PID43032）后确认相关JVM全部退出；保留333项SUCCESS，6个未完成输出通过FormalBatchGuard移入本实验目录的interrupted-seeds-before-pipeline备份，未删除历史结果。重新启动只跳过这333项已完成seed，其余747项继续生成。初次seed.log仍保留，接续写seed-resume.log，不覆盖旧调度日志。

转交前实际运行三算法smoke，3/3成功且stderr为空。NG-DSSR/TI/TI+SRI状态分别为ROOT_PROCESSED、NODE_LIMIT、ROOT_PROCESSED（smoke仅2节点）；三者incumbent均为34811，共享seed fingerprint=27f414dfc74a373dc00f24f5282fdb05cc166fd81b55aeda905477ad7660c4cf，均记录cplexThreads=1、Cluster=false。这是集成验收，不计入正式性能表。正式solve scan为3240项、每算法1080项、无外包、localSuccess=0。

自动流水线pipeline.cmd按顺序前台调用：剩余seed（6并发）→guard验证1080个SUCCESS及seed文件→三算法smoke及guard验证（已有成功则跳过）→worker.cmd正式求解（6并发）。任何阶段非零退出都会写pipeline.status=FAILED并停止；全部完成写FINISHED。pipeline.lock和worker.lock均用mkdir原子防重复。guard已实测未齐seed时抛异常、返回非零；smoke完整时返回complete=3。锁不能因日志暂未刷新而移除或绕过。

使用既有SSH `-f -n`方式只启动一次pipeline.cmd。新seed scheduler PID46552，初次确认1个scheduler和6个seed子JVM；主动结束本机启动SSH PID46848，等待12秒后，远端scheduler仍为46552、6个seed进程仍在并继续更换runId。进一步确认scheduler及父进程46988、36972均存活。因此本机关机或断开VPN不影响该流水线接续，前提是远端主机保持运行。当前验收状态为SEED_RUNNING，尚未把正式3240项描述为已经完成或已经开始定价。

后续只读检查入口为pipeline.status、scheduler-logs/seed-resume.log、scheduler-logs/seed-verify.log、scheduler-logs/solve.log及runs/pricing-comparison。不得再次启动第二个pipeline。正式计算可能持续多日，10800秒为solver软时限，仍保留此前单轮pricing超时响应不及时的风险。本批不修改算法源码，不安装软件或修改远端系统配置。

后续规模口径：本批`n<=60`保持已启动的10800秒，不在运行中修改；后续所有`n>60`正式纯调度比较统一设置为18000秒（5小时），其他并发和单线程约束不因该时间调整而改变。新建大规模manifest时必须逐行检查`timeLimitSeconds=18000`，不能直接复制本批10800秒后遗漏修改。

运行包构建：用本机javac --release 21、CPLEX/CPoptimizer jar及-sourcepath src编译HEU/Move.java、HEU/ExperimentBatchScheduler.java、Common/formal/FormalExperimentRunner.java和本次回归类，再以jar打包。FormalBatchGuard.java单独以--release 21编译打包为guard.jar，不进入solver.jar；生成清单脚本在scripts/remote/prepare-formal-le60-20260905.ps1。调度器原有SUCCESS跳过语义保留；本批seed生成、正式求解分阶段运行，solve.tsv没有跨阶段依赖，靠guard整体门槛保证seed完整。

部署内容确认：远端`20260905-93685d4a/solver.jar`不是只拿旧solver执行新回归，而是由提交93685d4a对应源码重新编译。该提交包含Node的分段/补集禁弧批量合并、TI fixing静态数据复用以及TI+SRI同次pricing元数据复用。远端jar下载回本机后的SHA256与本机构建jar完全一致，jar内存在新的`TWETBPC/LP/Node.class`和`TimeIndexedGraphPricingEngine.class`；因此本批实际求解会使用这些修改。随后提交7cefac50只增加正式manifest、接续脚本和记录，没有改变solver算法，本批部署名继续以solver源码提交93685d4a标识是有意的。

2026-09-05在途n20配对快照：正式SUCCESS为258项，其中三算法均完成的物理场景84个。只在这84个共同样本上读取core-summary的solveTimeSeconds，NG-DSSR/TI/TI+SRI均值分别为4.147/86.944/33.458秒；三者gap均为0，84组incumbent全部一致。random的54组均值为2.765/3.047/2.518秒；family的30组为6.635/237.958/89.149秒。family均值受TI的3588.491秒和TI+SRI的1802.732秒长尾影响，但即使如此，当前证据仍表现为random三者接近、family下NG-DSSR明显更稳定。该快照只覆盖84/270个n20场景，未完成困难场景尚未进入配对统计，不能作为最终n20均值。

2026-09-06在途n20配对快照：当前读取779份core summary，其中258个物理场景的NG-DSSR、TI和TI+SRI均已完成，共774份配对结果全部通过可行性和目标重算验证。NG-DSSR/TI/TI+SRI的配对平均时间为9.094/742.941/286.531秒，中位数为2.310/4.203/2.403秒，证明最优数为258/250/253。random的135个场景已完整配齐，三者平均时间为2.734/3.258/2.611秒且全部最优；family当前仅配齐123/135个场景，三者平均时间为16.074/1554.788/598.150秒，最优数为123/115/118，平均最终gap为0/0.6570%/0.6941%。family的高时间尺度和wide窗口困难项仍未全部完成，当前均值仍会低估TI和TI+SRI最终长尾，不能作为完整n20最终表。
