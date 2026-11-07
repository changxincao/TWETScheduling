# n60 NG-DSSR OOM补跑

源提交`81e144e45f727f992e02c1a1a753d98c19d796df`，JAR SHA-256 `483134956c345a2cf4fc77fc2a73c470466aab1ce7283706b278a41e2afae4b7`，Java release21。

只补跑原批次2项OOM，各项3小时、单线程，两并发、24GiB堆；family Cluster→Arc、random Arc，全节点fixing，其他有效参数按原A核对。原输入、seed、失败现场和已完成结果不动，新输出在本目录runs下。新旧版本结果须分开标记。

原批次：`D:/ccx/work1/experiments/20261104-ng-mainline-a-prepared`。
