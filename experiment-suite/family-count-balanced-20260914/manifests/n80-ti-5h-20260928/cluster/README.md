# n80 TI/TI+SRI五小时批次

本批只包含n80纯调度：135个F=4 family输入和135个原random输入，机器数3/4/5、时间尺度base/medium/high、窗口zero/narrow/wide、任务集set01--set05。分支配置为setup-only Cluster strict priority，失败时回退Arc。每项18000秒、100000节点、单CPLEX线程，调度器最多并发6。

本机新增seed 270项、新增solve 540项。已有同输入、同算法、同分支且已经证明最优的历史结果复用0项，记录在reused-results.tsv；历史时限项不复用，按5小时重新运行。旧实验目录和结果不覆盖。
