# Work 3：两阶段随机工时示意图

2026-11-01 开始制作，2026-11-02 保存并修订。当前确认文件为 [work3-scenario-windows-upright.png](work3-scenario-windows-upright.png)，实际尺寸 1341×1173。用户于 2026-11-02 确认先使用本版，已与 Work 1/2 原样放入 [当前确认版目录](../confirmed-20261101/README.md)，归档副本与来源 SHA-256 一致。使用内置 `image_gen`，以 [已确认 Work 2](../confirmed-20261101/Work2-窗口决策与并行机调度.png) 为版式与配色参考，生成两轮后对首轮底稿作局部几何校准，再按批注修订区间标记、红线及三个场景窗口的正体字形。

图上用共同任务序列及工时虚影表示一阶段：机器分配、机内顺序、每项任务的窗口在场景实现前确定。工时 `p_{jω}` 是随机输入；二阶段在既定分配与顺序下调整 `S_{jω},C_{jω}`，允许等待，不把工时本身画成决策。两阶段共享的是同一任务跨场景的窗口，各任务仍有自己的窗口。中层保留窗口范围、宽度界和位置/宽度成本；总成本将只计一次的窗口费用与按场景概率加权的执行费用分开。数学依据见 [三个问题的中文数学描述](../due-window-problem-v2-20261031/三个问题的中文数学描述.md) 第4节及 [Work 2 接续思路](../work2-window-decision-20261101/README.md) 第2节。

按用户“只保留第一个 ET”的要求，下方保留连续、非减、凸分段 ET 费用及五段曲线，删除计数和 early/tardy work 两类。2026-11-02 对照原始确认记录补正：2026-10-28 已确认论文后续采用两侧凸分段费用 P_E、P_T，所有任务共同使用这两个函数，线性 ET 保留为特例；既有计算结果仍采用线性特例。设定依据见独立工程 `docs/2026-10-28-网络流重构与凸分段ET扩展-整理稿.md` 第5.3节，本轮未修改求解器实现。

三个等概率场景使用相同的 `M₁:J₁→J₃→J₅`、`M₂:J₂→J₄→J₆`，J₃ 窗口固定为 `[8,12]`，时间单位统一为 16.8 px。这些是可行示例，不是最优排程或实验结果。

| 场景 | p₃ | S₃ | C₃ | E₃ | T₃ |
|---|---:|---:|---:|---:|---:|
| ω₁ | 2 | 4 | 6 | 2 | 0 |
| ω₂ | 4 | 5 | 9 | 0 | 0 |
| ω₃ | 7 | 6 | 13 | 0 | 1 |

首轮 [work3-model-v1.png](work3-model-v1.png) 已形成所需布局，但场景条长、窗口端点、E/T 标记与刻度不一致，J₅ 窗口下标及总成本括号也需修正。第二轮 [work3-model-v2.png](work3-model-v2.png) 未稳定解决这些问题，且在 ET 红线中引入局部水平段，因此未采用。两轮提示词分别保存在 [v1](imagegen-prompt-v1.txt)、[v2](imagegen-prompt-v2.txt)，原始输出路径与哈希见 [generation-records.json](generation-records.json)。

上一初稿 [work3-two-stage-draft.png](work3-two-stage-draft.png) 使用首轮图像，局部重绘三场景排程，统一六处绿色窗口标签并校正 J₅ 下标，调整总成本两个括号。脚本为 [calibrate_work3_draft.py](calibrate_work3_draft.py)；语法、场景任务顺序与无重叠、`C=S+p`、E/T、实际粉色条长及编辑范围检查均通过。场景粉色填色跨度为 30/64/114 px，与工时 2/4/7 在同一时间尺下扣除描边后的跨度一致。该轮 ET 公式和曲线、窗口成本区域与首轮图像素不变，允许编辑框外变化像素为 0，检查见 [draft-check.json](draft-check.json)。另放大查看了 E/T 两端与窗口下标，已确认 Work 1/2 的归档文件哈希未变。

## 三处批注修订

2026-11-02。批注指向首轮生图。本次在已校准场景的初稿上补强蓝色 `C₃,₁→e₃`、红色 `d₃→C₃,₃` 的两端标记，并将端线连接到真实 C₃ 黑点，早到为 `8−6=2`，延迟为 `13−12=1`。复查同时确认原 ET 红线的 β 段末尾多出近乎水平的小段，已删除并重画为两条直线，只在 `dⱼ+v` 处折转；后段斜率比前段大，图示比例 1:2 只是一个满足正费率的例子。拐点投影同步对齐，清除旧线残影，原公式和蓝色提前费用曲线保持不变。

新图另存，不覆盖生图原稿或上一初稿。脚本 [correct_work3_intervals_and_slope.py](correct_work3_intervals_and_slope.py) 已通过语法和实际运行检查；核对区间两端、中段颜色及 C₃ 点对齐，逐列核对两条红线与目标直线的像素位置，最大中心偏差小于 0.8 px。三处允许编辑框外变化像素为 0，详见 [annotations-corrected-check.json](annotations-corrected-check.json)。原尺寸与放大图已复核。

2026-11-02 字体修订：用户指出三个场景的窗口标签又使用了斜体。已将三处 `[e₃,d₃]` 单独改为 Times New Roman Regular（`times.ttf`），主字 19 px、下标 12 px，主字及下标的字体样式均核对为 Regular，另放大检查字形。脚本 [upright_work3_scenario_windows.py](upright_work3_scenario_windows.py) 输出当前展示图；仅三处标签框发生变化，区间端线、完工点与刚校准的成本曲线像素不变，检查见 [scenario-windows-upright-check.json](scenario-windows-upright-check.json)。
