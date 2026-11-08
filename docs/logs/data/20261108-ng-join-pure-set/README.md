# 七例纯set与OR独立求解

7个n60/F3/high/wide算例各运行OR与SET，共14项，不复用历史计时或旧输出。原set02/m3优先，另为set02/m4、set03/m4、set01/m3、set03/m3、set01/m2、set02/m2。每对使用同一JAR、数据和seed，其余CLI沿用原NG-A。Cluster→Arc、CutSet关闭、全节点scalar fixing、probe及完整求解流程不改。纯set仍输出原格式的剪枝BitSet，只用紧凑成员索引填充；不改变组对判断或label拼接。

四并发，每JVM24GiB、CPLEX1线程、10800秒名义预算和100000节点。两组均开原join分项计时，关闭同状态双算。逐组读时钟有开销，报告整体时间时保留此口径；旧join超时响应缺口未改，不能保证严格3小时退出。solver TIME_LIMIT与child SUCCEEDED需区分，不将本诊断并入正式总表。

源码：83861aeb4978941b24a3a3ed50f5d1085eea4855

启动和断开验收另记startup-record.json；旧任务和结果均不动。
