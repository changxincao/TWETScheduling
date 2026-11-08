# Join位图同状态诊断汇总

OR/set使用相同label、分组和可剪名单；只OR结果进入真实join。以下是位图聚合本身的成对计时，不是完整求解时间，也不是已部署的生产提速。

| 算例 | 状态 | 完整诊断轮 | 实际合并次数 | OR ms | set ms | OR/set | 静态平均k | 使用加权平均k |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| bitmap-n040-set01-random-base-wide-m4 | SUCCEEDED | 3 | 32 | 0.028 | 0.016 | 1.689 | 2.39 | 3.47 |
| bitmap-n040-set01-random-high-wide-m4 | SUCCEEDED | 4 | 70 | 0.058 | 0.032 | 1.823 | 2.13 | 3.11 |
| bitmap-n040-set02-family-high-wide-m4 | SUCCEEDED | 42 | 1613814 | 15.801 | 19.096 | 0.827 | 5.74 | 2.99 |
| bitmap-n040-set04-family-base-wide-m2 | SUCCEEDED | 314 | 197626340 | 5683.821 | 7292.255 | 0.779 | 34.46 | 20.76 |
| bitmap-n050-set01-random-high-wide-m4 | SUCCEEDED | 16 | 2164 | 0.322 | 0.213 | 1.512 | 3.01 | 2.60 |
| bitmap-n050-set02-family-high-wide-m4 | SUCCEEDED | 199 | 116778716 | 2349.762 | 1554.679 | 1.511 | 7.03 | 4.96 |

OR/set>1表示set在本次聚合计时中更快；小工作量、冷JIT和调度噪声不能作为稳定阈值依据。静态组数是各轮组次，不是跨pricing去重组数。joint-bins.csv给出q×k的静态占比、实际合并占比和解释性抽样时间；抽样不代表全量分档时间占比。workWordMemberRatio是sum(W)/sum(k)，不得叫作平均W/k。
