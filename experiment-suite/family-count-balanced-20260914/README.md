# 调整 family 数量的本地调度算例（2026-09-14）

本目录是独立的候选数据集，没有替换 `experiment-suite/formal/instances`，也没有修改三台远端电脑上的输入、输出或在途求解。这里只生成纯调度 `.dat` 和审计数据；未生成外包实例、求解 seed 或运行清单，尚无任何求解结果。

原正式数据在 `n50/n60/n80/n100` 的 family 数分别是 `4/4/5/6`。新数据只把这四档改为 `3/3/4/4`，机器档仍为 `n50/n60: 2,3,4` 和 `n80/n100: 3,4,5`。因此每个规模都有一档 `F>m`、一档 `F=m` 和一档 `F<m`；四个规模合计每种关系 180 个实例。每规模采用原来的 5 个任务集合、3 个时间尺度和 3 个窗口宽度，共 `4*5*3*3*3=540` 个 family 调度文件，索引在 `instances/instances.tsv`。

生成仍从原 Tanaka 数据、任务选择/置换种子、due-center、逐任务时间尺度、窗口宽度、基础 random setup 分位数出发，沿用 `FormalSetupGenerator` 的换族惩罚、总体 setup 均值校准、整数化和原 `FormalInstanceWriter` 的 `SETUP`/`SETUP_COST` 格式。family 分配使用与旧版相同的洗牌结果，但以新的 family 数执行 `index % F`；因此分组和 family setup 矩阵必然重新计算，不能把旧结果改名继续使用。新旧数据中相同 `n/set/scale/window/m` 的任务处理时间、due window、ET 权重及机器数逐文件相同。random setup 矩阵在两种 family 数下逐任务集合逐项相同；本目录没有重复写入 random 文件。

生成命令（在仓库根目录执行；新目录已存在时默认拒绝覆盖）：

```powershell
javac -encoding UTF-8 -cp target/classes -sourcepath src -d target/classes src/Common/formal/FormalFamilyCountVariantGenerator.java src/Common/formal/FormalExperimentDataGenerator.java src/Common/formal/FormalSetupGenerator.java
java -cp target/classes Common.formal.FormalFamilyCountVariantGenerator data experiment-suite/formal/instances experiment-suite/family-count-balanced-20260914
```

仅复核已有文件、不重新生成：

```powershell
java -cp target/classes Common.formal.FormalFamilyCountVariantGenerator data experiment-suite/formal/instances experiment-suite/family-count-balanced-20260914 --verifyOnly
```

2026-09-14 验收：原独立 setup 审计和时间尺度审计均覆盖 540 文件；20 个任务集合的任务、尺度、窗口 metadata 与旧版对应行相同，540 份 `.dat` 的任务行和机器数逐文件相同，20 个任务集合的 family setup 均发生变化。新 family 数为 `50/60:3`、`80/100:4`；base 层 setup 的组间/组内均值比分别为 `4.669–4.753`、`4.692–4.749`、`4.656–4.711`、`4.681–4.727`。全文件最大 setup 均值相对误差为 `0.002800` 以下，三角不等式违反数为 0，base 层无 setup cap 命中。逐文件正文 SHA-256 在 `instances/post-generation-setup-audit.tsv` 中。另在独立临时目录走了一次未改动的正式生成入口，重生成 `n50-set01` 的54份random/family文件，全部与旧正式文件SHA-256一致；临时目录已清理。

旧正式索引 `instances.tsv` 的 SHA-256 为 `3485A6CBF9B22551FA30364A01A866F0A4253B9FD04BD0BDC26BA68F8A6E248C`；新索引为 `02EAA7DC0AE6DE83D8059C57FAB4B064DC42CE2ED0BCD548F1BE034C61E3AED2`。旧文件仅用于读取比对，没有覆盖。新数据是否进入论文主表仍取决于后续统一配置下的实际求解和分层结果。
