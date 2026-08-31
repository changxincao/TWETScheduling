# 远端运行脚本

这两个脚本只面向 `codex-runner` 上的 `D:\ccx_work\考虑交付的机器调度\work1`。代码应放在 `work1\deployments\<部署编号>\TWETScheduling`，脚本会从自身位置推导部署根目录和 `work1` 根目录，不依赖远端环境变量。

`run-remote-smoke.cmd` 使用已部署的 `n020-set01/random/base/zero/m2.dat`。缺少共享 seed 时先运行三次确定性初始启发式，再用正式 `NG_DSSR` profile 做最多 120 秒、100 个节点的 smoke。输入固定来自 `work1\instances`，seed 写入 `work1\runtime\seeds`，stdout、stderr、live log 和求解结果写入 `work1\results\smoke\<部署编号>`。

`run-remote-formal.cmd` 接收 manifest 路径和最大并发数，默认读取部署包中的远端版 `pricing-comparison.tsv`，最大并发为 4。第三个参数只允许为空或 `--scan-only`；需要拆分任务时直接使用 `pricing-comparison.tsv` 和 `outsourcing-performance.tsv` 两份分块 manifest。每个子 JVM 仍由 `FormalExperimentRunner` 固定为一个 CPLEX 线程；正式时限和节点上限由 manifest 中的 `10800` 秒和 `100000` 控制。

示例扫描整份定价比较 manifest，不启动求解：

```bat
scripts\remote\run-remote-formal.cmd "experiment-suite\formal\manifests\pricing-comparison.tsv" 4 --scan-only
```

远端当前没有部署 `outsourcing-data/no-discount`，因此数量折扣灵敏度 manifest 暂不属于可运行范围。定价比较和默认折扣外包性能 manifest 使用的实例已部署并核验。
