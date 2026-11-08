# n60长join单项计时

只补跑n60/set02/F3/m3/high/wide，原输入、seed、runId及求解参数不变。新JAR仅包含默认关闭的join计时补充，本worker显式开启；1并发、24GiB堆、10800秒名义时限。已知时限检查缺口未改，可能明显超时；逐项读时钟也会带来开销。旧结果不覆盖，不纳入正式性能比较。

代码提交：4ac6cb5ae3ed1ee30209c4c140db18154bfb10fd

2026-11-08 14:00:55（北京时间）启动。scheduler PID为8268，solver PID为12704；196个可比较配置字段及输入/seed哈希验收通过。VM.command_line确认计时为true，VM.flags确认MaxHeapSize=25769803776，run.properties确认CPLEX线程1。

按既有SOP只关闭本机启动SSH 43460，14:04:51独立连接复查仍为1个scheduler和1个solver；CPU时间由101687ms增至125734ms、live.log由146197字节增至155590字节，分项计时行已经输出。此次只是启动验收，尚未完成大join或整次求解。

PowerShell调用返回拒绝访问，改用既有Java验收工具与SFTP，没有调整系统权限或服务。状态工具读取活stdout时曾因不完整UTF8字符失败，后仅修该读取器，tools.jar旧副本保留为tools.pre-log-tail.jar；solver.jar未再改变。asset-hashes.tsv描述启动前资产快照，后续README及状态工具版本以startup-record.json为准。

原运行没有可完整重放的dual/label快照，本次是同场景重跑，不保证再现完全相同的pricing调用序号或探测路径。只根据实际返回的完整join轮次统计比例；不要把前几轮小join当作旧上万秒join的分解。
