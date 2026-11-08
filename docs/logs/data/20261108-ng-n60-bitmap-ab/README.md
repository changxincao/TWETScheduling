# n60长join同状态OR/set诊断

原n60/set02/F3/m3/high/wide输入、seed、runId及求解参数保持；只改新目录中的输入/seed/output地址和JVM诊断开关。一个pricing内labeling、建组、组对判定各一次，全OR/全set消费相同可剪名单，逐位核对，只OR进入真实join。不设置稀疏边界，不改正式逻辑。

1并发、24GiB堆、CPLEX1线程，原10800秒名义预算和100000节点上限；旧join时限检查缺口未改，可能明显超时。双算会改变wall-time及probe反馈，因此不保证重现相同pricing编号/dual，只保证每次A/B同状态。原任务不停止、原输出不覆盖。本次整体时间含B及统计，不是优化后正式求解时间。

源码提交：caecd7db

部署与独立连接验收见startup-record.json。
