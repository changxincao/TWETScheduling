# Dominance graph 文献对照与论文定位

## 1. 主要结论

当前 `IncrementalSourcedDominanceGraph` 可以明确写成对 Luo et al. (2017) set-dominance graph 的调度化改编，但不能写成对其代码或伪代码的直接复现。两者共同的核心是：按资源/可达状态组织 dominance node，在节点及其前驱上维护多个标签函数的联合下包络，从而允许一组标签共同占优一个新标签。

当前实现对这一框架做了两个实质改造。第一，时间状态不再作为一个标量 node key，而是直接进入 PWLF frontier；图节点按 terminal job、搜索方向和 reachable-set key 组织。第二，每个综合包络 segment 记录本地 label source，包络更新只传播数值下降的 sparse delta。某个 label 的最后一个来源区间消失时即可直接删除，不需要重新扫描节点的历史 label 列表。

因此，可以准确地说当前实现在 dominance 维护层面比 Luo 论文伪代码更增量、更适合当前 PWLF 定价。不能直接声称整体算法或渐进复杂度优于 Luo，因为两篇工作的状态、函数变量和定价问题不同，也没有做同问题、同实现的 A/B。

## 2. Luo et al. 的处理

Luo et al. 在每个物理顶点上建立一个有向 dominance graph。一个 node 包含具有相同最早服务时间和 reachable set 的 label 集合，并维护这些 label 成本函数的逐点下包络。图边表示状态之间可以继承的 dominance 关系。

新 label 到达后，论文先沿图搜索能够联合支配它的 node；若未被支配，则加入已有 node 或建立新 node。关键差异出现在 Algorithm 6：前驱包络更新后，算法逐个遍历受影响后继 node 的 label 集合，对每个 label 再执行一次函数支配检查并删除被占优者。因此，论文方法虽然已经利用联合下包络加强占优，但后继清理仍显式扫描 label。

## 3. 当前 source-aware 图

当前每个 node 只维护综合包络

\[
g_u(t)=\min\left\{\min_{L\in A_u}f_L(t),\ \min_{v\in pred(u)}g_v(t)\right\}.
\]

每个包络段同时记录其本地 label source；前驱来源统一记为 external。插入流程为：

1. 先用现有综合包络只读判断新 label 是否已被占优。
2. 若未被占优，将新 frontier 与综合包络做一次 source-aware segment merge。
3. merge 同时给出新 label 是否贡献包络、哪些旧本地 source 失去最后区间，以及包络数值真正下降的 sparse delta。
4. 失去最后区间的 label 直接标记 dominated；只把 sparse delta 传播给直接后继。
5. 后继包络没有数值变化时立即停止该分支；node 没有本地 active label 时删除 node 并重连 Hasse 边。
6. partial 模式只记录最新保留区间，在 label 真正出队扩展或进入 join 前才重建 frontier。

这里“不扫描 label”特指 dominance 更新和后继清理不再遍历 node 的完整历史 label 列表。实现仍然需要搜索 reachable-set Hasse 图、扫描 PWLF/envelope segments，并且被接受的 label 会经历一次只读预检查和一次实际 merge。论文正文应保留这个边界。

## 4. 论文写作调整

Literature Review 只保留 due-window scheduling、parallel-machine exact methods 和 scheduling outsourcing 三条问题文献主线。原来用于说明 PWLF、外包分解和 ng-DSSR 的 VRP 段落已从调度文献综述删除。

VRP 文献不是完全从论文删除，而是改为在算法来源出现的位置引用：Martinelli et al. 用于说明 ng-DSSR 的来源，Luo et al. 用于说明 set-dominance graph 的来源和当前 source-aware 改造。这样可以避免把算法借鉴混入调度问题综述，同时保留必要的学术归属。

## 5. 当前边界

当前 source-aware dominance graph 用于 no-SRI 的 normal/partial 主线。active SRI 仍需携带额外 residual state，当前走旧的 SRI-aware store，不能在正文中写成 source-aware 图已兼容全部 cut pricing。现有 96,000 次随机插入一致性测试和主线算例支持实现正确性，但不构成与 Luo 算法的端到端速度比较。
