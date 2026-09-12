# NG-DSSR时空弧fixing复核与优化

## 结论

保留正式配置中的`rootOnly`。NG-DSSR节点闭合后的scalar fixing与TI论文版graph fixing在整数时间实例上使用相同的UB-LB安全判定；逐时空弧和逐任务窗口对拍结果一致。但直接把scalar实现替换为graph solver并未加速，真实n40试跑中节点fixing分别约为2.797秒和0.932秒，因此不采用整段替换。

## 本次处理

1. scalar helper继续使用裁剪后的有效horizon和原有扫描顺序，缓存本次LP的job、arc和machine dual，避免在`O(n^2H)`热循环内反复经过LP getter；reduced-cost算术顺序保持原样。
2. no-SRI可达窗口的统计与节点写回合并为一次`O(nH)`扫描。
3. 已确认新弧不存在的fixing写入使用`setKnownAbsent`，省掉同一局部BitSet的第二次查询；断言模式下仍检查调用约定。
4. 临时TI root预处理已经执行完整graph fixing，随后再跑scalar固定0条弧、收紧0个窗口。该重复调用已删除，日志字段保留为`not-run: redundant after graphFix`，便于旧解析器继续读取。

## 验证

`TimeIndexedReuseTest`新增TI graph/scalar fixing对拍，覆盖原始窗口、窗口收紧和已有时空禁弧三种状态，逐位检查禁弧集合与窗口。真实`n040-set02/random/high/wide/m4`中两种fixing均固定108558条新时空弧、收紧17个窗口，process/idle/end/cleanup分解及窗口摘要完全一致；最终目标和bound仍为231885，节点数仍为26。

Java 21全量focused编译通过；`TimeIndexedGraphOptimizationTest`、`TimeIndexedReuseTest`、`BestBpcProfilesTest`、`StructuredArcFlowBranchingTest`和`TimeIndexedArcSetTest`通过，其中三项相关存储测试又在`-ea`断言模式下通过。正式profile版本更新为`2026-09-12-v8`，算法参数未改变。

## 性能边界

旧v7同算例的root预处理中，graph fixing之后的重复scalar调用耗时0.307秒且没有任何新增结果；本次确定消除该开销。其余优化减少固定的热循环操作，但root-only意味着每棵树只调用一次，不能把单次墙钟波动解释为总求解时间的稳定大幅提升。真正的大幅收益仍来自此前已完成的有效horizon裁剪和只在主树root执行一次。
