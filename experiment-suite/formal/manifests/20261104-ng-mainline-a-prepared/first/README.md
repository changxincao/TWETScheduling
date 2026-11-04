# NG-DSSR A / n60 / DESKTOP-06K8JI8

270个场景中4个已验证完成，原结果保留在.192且不重跑；此目录排队其余266项，含2项失败和5项中断的重跑。仅n60，不自动接n80。5个JVM并行，每模型单线程，3小时时限、100000节点、全节点scalar fixing；family Cluster→Arc、random Arc，CutSet关闭。其他参数、输入和seed沿用已验收A，不重编译算法。

Java沿用本机21.0.3，与原.192的21.0.7不同；未修改系统环境。profile文件仅供审计，实际配置由冻结JAR和solve.tsv决定。
