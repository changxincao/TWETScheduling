# 外包 TWET 论文 LaTeX 重构记录

## 1. 本次目标

本次工作基于原始压缩包
`parallel_machine_scheduling_with_due_window (2).zip` 整理一套可继续写作的
LaTeX 主稿。原始 `twet_outsourcing_models_revised_v42.tex` 和 `history/`
完整保留，新稿以
`paper/parallel_machine_scheduling_with_due_window/main.tex` 为入口。

当前只完成摘要、Introduction、Literature Review、Problem Description、两套
set-covering formulation 和求解算法。实验与结论仍保留明确占位，不写未经统一
实验确认的数值结论。

## 2. 写作结构

正文按“实际问题及耦合关系 -> 文献中的分散组成部分 -> 问题定义 -> 两种外包
分解 -> 共用求解框架及不同 pricing 路径”展开。写作方式参考本地
OR-Writing 和 WorkBuddy SCI 写作规范，并参考三篇 branch-price(-and-cut)
论文的章节组织，但没有照搬其问题结构。

两套模型统一采用覆盖约束 `>=1`，不再保留一个等式版本和一个实现版本：

1. `SC-M` 只对内部机器序列列化，任务外包变量及 tariff segment 显式留在主问题。
2. `SC-C` 同时生成内部序列列和外包任务集合列，外包集合最多选择一个。

算法章节只写当前项目中真实存在的组件。连续时间主配置使用 tabu heuristic +
双向 PWLF ng-DSSR，并说明 source-aware incremental dominance、completion
bound、adaptive midpoint、group-envelope join 和 DSSR 更新。整数时间下另写
time-indexed exact pricing；active rank-1 cut 主要由 time-indexed 变体处理，
source-aware ng-DSSR 主配置不携带 cut residual state。列化外包模式在内部 exact
后继续执行 outsourcing subset pricing，只有两个列族的证书均完整时才闭合。
强分支不可行 RMP 使用纯 Phase-I 列生成修复。

## 3. 文献综述口径

Literature Review 优先核对以下本地目录中的强相关论文：

1. `TWET/work1/TWET调度问题本身的强相关文章`
2. `TWET/work1/outsourcing`

去重后，TWET 目录形成四条证据线：due-window/TWET 定义与启发式、并行机精确
下界和分解、函数型/PWLF 时间代价、列生成和软时间窗。外包目录形成四条证据线：
order acceptance/rejection、scheduling with outsourcing、aggregate/discount
outsourcing cost、routing outsourcing formulations。

当前综述的综合结论为：已有研究分别提供了 due-window 成本、并行机精确列生成、
外包选择、聚合折扣费率和 ng-DSSR pricing，但尚未在同一精确框架内同时处理
identical parallel machines、job-dependent due windows、sequence-dependent
setup time/cost、aggregate concave outsourcing tariff 和 exact
branch-and-price。该表述是对本地强相关文献集合的综合，不把“加入外包”本身写成
唯一创新。

## 4. 总审发现及修正

总审代理按公式、实现和文献三层进行检查，发现并修正了以下具体问题：

1. time-indexed exact 模式不会先调用外部 tabu heuristic；正文已改为按连续时间
   主配置和 time-indexed 备选配置分别说明。
2. observed dual bound 使用节点当前的机器数上界 `m_u^{max}`，不是固定全局 `m`。
3. 列化外包的 membership branching dual 会进入 outsourcing item profit；算法节
   已补充 `pi_j + eta_j` 口径。
4. SRI 已区分 source-aware no-cut 主配置、支持 cut 的旧 partial-list 连续时间
   变体和 time-indexed rank-1 变体。
5. 修复 `SC-M`、`SC-C` 两处缺失右花括号的 LaTeX 结构错误。

覆盖约束与“每个任务恰好服务一次”的理论桥接仍需在最终定稿前明确。当前按用户
确定的实现口径直接写 `>=1`，算法终止结论限定为所选 set-covering formulation，
不另造一个等式版本。

## 5. 静态验证

主稿当前引用 20 个 BibTeX key，`ref.bib` 中均有定义；没有未使用条目。主稿及
sections 中共有 37 个 label，没有缺失引用或重复 label。已按文件检查未转义花括号
平衡。

本机没有 `xelatex`、`pdflatex`、`latexmk`、`tectonic`、`bibtex` 或 `chktex`，
因此本次不能生成新 PDF，只完成静态结构验证。后续在具有 LaTeX 工具链的环境中应
按 `xelatex -> bibtex -> xelatex -> xelatex` 完成正式编译和版式检查。

## 6. 当前稿件质量评估

当前版本已经完成从旧稿材料到“问题—模型—算法”论文骨架的实质重构。两种外包
表示、连续时间 ng-DSSR 定价和 time-indexed 对照路径已经形成一条可读的主线；
文献综述也不再是逐篇罗列，而是围绕 due-window、并行机精确算法、外包和 relaxed
elementary pricing 四条研究线组织。作为继续写实验部分的工作稿，结构已经可用。

当前最重要的理论缺口仍是 `>=1` 覆盖约束与原始“每个任务只执行或外包一次”语义
之间的等价性。非负列成本并不足以自动排除内部列对同一任务的重复覆盖；在没有
setup 三角不等式或删除重复任务不增成本性质时，重复任务甚至可能充当降低相邻
setup 成本的中间任务。因此，最终稿必须明确研究对象就是 set-covering 版本，或
补充能够排除重复覆盖的性质与证明，不能仅凭实现采用 `>=1` 就宣称求得原始
exact-once 问题的最优解。

其次，当前摘要没有计算结果，实验和结论仍为占位内容；文献缺口结论主要基于本地
强相关文献，尚不足以支撑无边界的“首个”声明；算法节准确但偏实现说明，仍缺少
总流程伪代码、关键正确性命题以及复杂组件之间的层次压缩。投稿前还应进一步说明
外包任务为何不考虑交付时间或服务能力，以及聚合基准金额 `b_j` 的业务含义和来源。

当前做得较好的部分包括：SC-M/SC-C 的分解差异明确；内部列成本把固定序列的最优
计时纳入列系数；内部和外包列族的闭合证书边界写清；source-aware dominance、
completion bounds、midpoint probe、join 和 Phase-I repair 与当前代码主线基本
一致；未写入尚无实验支持的数值优势。总体评价是“可作为完整论文的可靠方法稿
底稿”，但还不是可投稿终稿。

后续按当前论文口径，问题定义明确要求 setup time 满足三角不等式，并保持原始
可行解语义不变：内部机器序列两两不相交，外包集合与内部序列不相交，每个任务
恰好出现一次。两套主问题仍直接使用 `>=1` 覆盖约束，本次不调整 formulation。
