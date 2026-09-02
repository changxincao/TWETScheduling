# Identical machines任务数量排序性质对pricing的作用

## 问题与结论

对一个包含`n`个任务的elementary完整排程，把`M`台相同机器的任务数排序为`q1>=q2>=...>=qM`，则第`m`台机器满足`q_m<=floor(n/m)`。该结论对position-indexed多机模型很有用，因为模型显式保留了机器下标，可以把第`m`台机器的位置数缩减到`floor(n/m)`。

它对当前列生成pricing基本没有直接剪枝作用。当前master的每条内部列表示“一台未标号相同机器的完整排程”，pricing一次只生成一条列，不知道该列最终会被排成第1长、第2长还是第`m`长。以`n=40,M=3`为例，排序后可写`q1<=40,q2<=20,q3<=13`，但pricing发现一条含20个任务的路线时，它完全可能成为第1或第2条路线，不能按13剪掉。对任意单列都安全的统一上界仍只有`n`，因此不能加强当前completion bound、domain pruning或label扩展上界。

## 对ng-DSSR和time-indexed的影响

该性质中的`q_m`指最终elementary排程中不同任务的数量，不是松弛walk的访问位置数。ng-DSSR中间路线和time-indexed pseudo-schedule都可能重复访问任务，访问长度可以超过不同任务数；更重要的是，它们同样没有机器长度排名。给这些松弛路线施加`floor(n/m)`位置上界会错误删除本可作为第1至第`m-1`长路线的elementary列，因而不能用于exact pricing证书。即使ng-DSSR最终只向master返回elementary列，DSSR过程仍依赖包含这些列的单机松弛域，不能在不知道路线排名时使用机器特定上界。

当前列池和master已经消除了绝大部分机器置换对称性：列变量没有机器编号，选择同一组排程列不再因交换机器标签形成`M!`个解。因此该排序性质在显式position模型中的主要价值，在现有set-covering列模型里已经被“未标号列”自然吸收。

## 唯一可迁移方向：master计数cut

该性质可以转换成全局master不等式，而不是pricing长度上界。对任意`m=2,...,M`，令`K_m=floor(n/m)`，真实elementary排程中不可能同时选择`m`条长度大于`K_m`的路线，因此有：

```text
sum_{r: |r| > K_m} lambda_r <= m-1.
```

还可写成更一般的整除型rank-1计数cut：

```text
sum_r floor(|r|/(K_m+1)) lambda_r <= floor(n/(K_m+1)).
```

这些不等式对纯调度的真实elementary解有效，但不是免费的robust cut。pricing必须跟踪当前访问数量是否跨过阈值；整除型版本需要维护模`K_m+1`的residual，可能明显削弱dominance。对time-indexed pseudo列可按访问次数定义扩展系数，使cut仍保留所有elementary解，但会新增计数状态；对ng-DSSR也会增加每轮松弛定价复杂度。

预期强度也有限。当前family困难解中，三条单family pseudo列长度约20、权重合计约2。对`n=40,m=3`，阈值为13、右端为2，该解通常恰好满足而不是违反；对`m=2`，阈值为20，也不会切掉长度20的列。因此它不能直接解决family下的重复覆盖、弱LB和多轮DSSR问题。

## 当前决定

该性质保留给显式machine-position模型做位置缩减；不用于当前ng-DSSR或time-indexed pricing剪枝，也暂不实现master计数cut。只有以后日志显示LP经常同时选择过多超长elementary列，并且该结构对root gap有明确贡献，才值得评估上述计数cut及其pricing状态成本。
