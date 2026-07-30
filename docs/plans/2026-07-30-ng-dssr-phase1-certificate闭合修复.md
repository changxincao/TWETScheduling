# ng-DSSR Phase-I certificate 闭合修复

## 问题

40-2、W=100 的 no-SRI ng-DSSR 在 strong branching Phase-I repair 中报错：
`internalClosed=false, outsourcingClosed=true`。原始追踪表明，最后一次 exact ng-DSSR 已完整耗尽正反向队列，执行两轮 DSSR 后返回 0 列，终止原因为 `relaxed pricing found no negative route`。但 `PricingResult.certifiedInternalReducedCost` 实际为 `NaN`。

问题不在 labeling、join 或 DSSR。搜索器用 `+Infinity` 初始化本轮观察到的最好 reduced cost；当所有完整路径都被安全下界剪枝、没有终端候选被实际评价时，该值保持 `+Infinity`。原 getter 无条件把无限值转换成 `NaN`，从而把“完整搜索后没有负列”与“超时或未完成、没有证书”混为同一状态。

## 修复

ng-DSSR 现在显式记录最后一轮是否完整结束。只有正反向队列耗尽并完成 join，或 completion-bound pre-certificate 已闭合时，才允许向外返回内部列族证书。

完整轮若观察到有限 reduced cost，仍返回原值；完整轮若没有评价到任何终端候选，则按 time-indexed exact pricing 的既有口径返回有限证书 `0.0`，表示已经证明不存在低于容差的负 reduced-cost 列。超时、诊断中断或禁用列生成导致的未完成轮仍返回 `NaN`，不能用于 Phase-I 不可行判定或 dual-bound 剪枝。DSSR 外层也先检查完整状态，再进入 `relaxed pricing found no negative route` 分支，避免超时轮被错误标记为闭合。

## 验证

针对原失败配置使用相同 fixed seed 重跑 `wet040_001_2m`、W=100。初始列仍为 91 条，incumbent 仍为 11221，fingerprint 仍为 `64933ae560e18033532c22ae284625b8ea3948f24ec35c31514a1b5477676694`。

原失败的 `repair=4:14->22` 最后一轮仍返回 0 列和 `relaxed pricing found no negative route`，但现在形成完整内部证书，strong trial 正确得到 `rightBound=INF` 和 `rmp_trial_infeasible: Strong branching Phase-I optimum remains positive after generating 623 columns`。完整实例最终 `obj=bound=11221`、`valid=true`、2 个处理节点，未再出现 certificate contract 异常。结果位于 `test-results/bpc/exp-40-2-base-W100-ng-certfix-20260730f`。

后续按真实调用链再次收缩实现。pricing engine 每次调用都会新建 solver，且每个 DSSR round 已在 `solveRelaxedRound()` 入口初始化证书状态，因此删除 `solve()` 入口的重复清空。证书写入来源只有初始 `Double.POSITIVE_INFINITY`、经过有限性校验的 completion bound 和正常 reduced cost，因此 getter 不再为理论上不会出现的其他非有限值增加保护；当前只保留“轮次未完成返回 `NaN`、完整轮无终端候选返回 `0.0`、否则返回实际 reduced cost”三条必要语义。focused 编译及 midpoint、DSSR 更新、Phase-I pricing 三项回归通过。

上述边界收紧后又以完全相同的 fixed seed 和 W=100 配置完整复跑一次。最终运行仍命中 `repair=4:14->22` 的闭合记录，strong trial 仍得到 `rightBound=INF` 和“Phase-I optimum remains positive after generating 623 columns”；实例最终 `obj=bound=11221`、`valid=true`、处理 2 个节点。结果位于 `test-results/bpc/exp-40-2-base-W100-ng-certfix-final-20260730g`。
