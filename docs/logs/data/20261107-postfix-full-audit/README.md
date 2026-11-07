# 补丁后全链路审查证据

生产基线为`326e6b4501e1`，时间2026-11-07。此次仅增加诊断和记录，没有改生产源码或远端。专题结论见`../../2026-11-07-三类定价与主线衔接全面复核.md`第10节。两个Java文件均为隔离诊断，不是生产engine，也不是正式性能算例。

`regressions.csv/json`与`compile.log`来自当前源代码fresh编译及61项回归，全部exit=0；每项完整日志在项目`.codex-tmp/postfix-full-audit-20261107/`。回归使用既有`../20261107-ng-endpoint-patches/run_regressions.py`，将OUT改到该临时目录，NAMES取本目录CSV的test列，没有修改原runner。Java为本机JDK22，CPLEX/CP Optimizer为本机22.1.1。

`PostfixAudit.java`提供四种模式。`oracle`对应`independent-outsourcing-oracle.log`，120组UB/LB与独立原始成本最优值一致，90组根闭合、30组非根，最多13节点；它不共享评价器硬窗作为独立oracle。`normal`对应`normal-ea.log`，默认debug通过；`debug`分别对应`debug-ea.log`和`debug-no-ea.log`，前者预期exit=1并定位重复写入断言，后者预期exit=0但重复计数；`segmented-contract`对应同名日志，只验证接口后果，不运行巨型网络。不能把这个预期断言退出混为61项正式回归失败。

`OptionalPathsAudit.java`的`stabilization`模式对应`optional-stabilization.log`：H=4受控域的显式稳定化LB=4.5超过真实可行值4，关闭组LB=4；同输入自然H=5的开关对照均正常。代码从真实RMP获取dual，没有注入dual。`enumeration`模式对应`optional-enumeration.log`，12组中3组公共finite master把非最优值标为proven，差值16/5/6；raw求解状态和公共接口结果是两次独立求解。诊断断言用于确认漏洞仍可复现，不是修复后的通过标准；未来修复后应相应改成正确性断言。

`rank1-and-fixing-oracle.log`由既有`../20261107-three-pricer-full-audit/TiAudit.java`重新编译后产生，160组受控定价快照零冲突，40组fixing低RC序列保留检查通过。不是160次真实RMP证书验证或正式SRI-aware性能比较。

编译两个新诊断及既有TiAudit时，以fresh的`.codex-tmp/postfix-full-audit-20261107/classes`、本机`cplex.jar`与`ILOG.CP.jar`为classpath，输出到同目录`audit-classes`。运行时将audit-classes放在classes之前，显式传入本机CPLEX/CP Optimizer native路径，默认加`-ea`；debug-no-ea对照及用于观察重复计数后果的segmented-contract不加。后者若加-ea，会在第二次known-absent写入时按约定断言中止，不会执行计数比较。主类分别为`TWETBPC.GC.PostfixAudit`、`TWETBPC.LP.OptionalPathsAudit`与`TWETBPC.GC.TiAudit`。未复制或汇总远端正式结果。

`enumeration-exact`是同一有限master的补充验证：先保存原默认容差结果，再对同一raw模型设置相对与绝对MIP gap为0重新求解，断言UB/LB与独立DP一致。`enumeration-zero-gap.log`记录12组均通过，原三组非最优值分别下降16/5/6；这只改隔离诊断的CPLEX参数，不改生产gap、数据或启停实验。fresh编译及TI重核输出在`.codex-tmp/confirm-enum-debug-20261107/`。
