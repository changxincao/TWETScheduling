# Join位图同状态诊断汇总

OR/set使用相同label、分组和可剪名单；只OR结果进入真实join。以下是位图聚合本身的成对计时，不是完整求解时间，也不是已部署的生产提速。

| 算例 | 状态 | 完整诊断轮 | 实际合并次数 | OR ms | set ms | OR/set | 静态平均k | 使用加权平均k |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| pricing-n060-set02-m3-family-high-wwide-f3-ng_dssr-mainline-a | SUCCEEDED | 167 | 9265020055 | 5987271.546 | 313330.987 | 19.108 | 35.24 | 12.02 |

OR/set>1表示set在本次聚合计时中更快；小工作量、冷JIT和调度噪声不能作为稳定阈值依据。静态组数是各轮组次，不是跨pricing去重组数。joint-bins.csv给出q×k的静态占比、实际合并占比和解释性抽样时间；抽样不代表全量分档时间占比。workWordMemberRatio是sum(W)/sum(k)，不得叫作平均W/k。
