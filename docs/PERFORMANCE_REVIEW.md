# 当前项目性能梳理 · 2026-10-08

> 本文保留修复前的审查证据和当时的行号。九项问题现已处理，当前实现、验证结果和限制见 [性能修复记录](PERFORMANCE_FIXES.md)。历史探针输出为 `performance/sql_probe_results.json`；新脚本默认读取版本 4，修复前对照使用 `--schema-version 3 --revision e55eb15`。

审查对象是当前工作区（HEAD `e55eb15` 加已有未提交修改），不是 `PROJECT_REVIEW.md` 中的旧基线。范围覆盖通知/SMS 入口、模型请求、Room 查询、日志、设置持久化和 Compose 数据订阅。本轮仅新增分析文档和 SQL 探针，没有修改业务代码。

优先处理日志同步写盘、短信匹配的重复扫描和每次请求重建网络客户端。这三项都有明确控制流证据。实际掉帧、耗电、请求延迟的幅度仍需设备测量；本文没有把桌面探针结果当作手机性能结果。

## 问题总览

这里的 P1 表示优先优化的主线程阻塞或热路径放大；P2 表示随后处理的延迟、查询、内存与观测问题，不表示已经测得 ANR。

| 编号 | 优先级 | 问题 | 主要触发条件 |
|---|---|---|---|
| 01 | P1 | 日志到 64 条会在调用线程、持锁期间写盘 | 高频去重 DEBUG 日志或短时间大量告警 |
| 02 | P1 | 短信恢复逐条重试全队列，反复扫描活动通知 | 多条待撤销短信无法匹配、默认短信 App 发布通知 |
| 03 | P1 | 每次模型决策新建并关闭网络客户端 | 有 API Key 的每次后端判断 |
| 04 | P2 | 同一短信可能由广播和通知各判断一次 | 模型尚未完成，短信 App 已发布通知 |
| 05 | P2 | 采集热路径每次执行全表保留清理 | 事件库接近 10,000 行、通知密集 |
| 06 | P2 | 同表订阅放大统计查询，隐藏隔离区仍订阅 | 事件页/诊断页可见且持续写入 |
| 07 | P2 | 通知主线程承担冷标签查询和重复文本处理 | 首个陌生包/渠道、长正文、消息合集 |
| 08 | P2 | 输入每次变化都保存整份设置并重新加密 | API Key/模型编辑、短时间连续修改多个设置 |
| 09 | P2 | 延迟指标缺少完整链路和阶段拆分 | 用现有 P50/P95 评估撤销速度或排队问题 |

## 01 · 日志批量刷新仍会同步写盘

位置：[AppLog.kt:199](D:/project/jev-block-android-ad/core/common/src/androidMain/kotlin/me/ethanxu/jevnoisegate/core/common/log/AppLog.kt:199)、[RecentPostCache.kt:72](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/RecentPostCache.kt:72)。

`log()` 在 `synchronized(lock)` 内更新缓冲、复制最多 500 条日志，然后在 `pending.size >= 64` 时直接调用 `writeBatch()`。后者执行文件轮转和 `appendText()`。两秒定时刷新走后台协程，但达到批量阈值的分支没有切换线程。

通知去重和异常路径能在主线程调用日志，因此该分支会让主线程等磁盘；其他线程也会等同一把锁。缓冲 `toList()` 和 StateFlow 的列表相等比较还会带来随缓冲长度增长的工作。默认 INFO 下去重 DEBUG 不进入此路径，但短时间告警仍可触发。

建议由单个 IO 消费者串行执行所有写入和轮转，阈值只发送刷新信号。锁内仅管理队列；日志列表快照可按显示需要合并发布。`clear()` 的文件删除也应交给同一个消费者，避免它与刷新竞争。

验证：启用 DEBUG 并产生超过 64 条日志；用 StrictMode/Perfetto 观察通知/UI 线程是否出现文件 IO，记录写盘次数和锁等待。普通两秒刷新经过后台并不能证明该阈值分支安全。

## 02 · 短信恢复会放大队列重试与主线程扫描

位置：[NoiseGateNotificationListener.kt:168](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:168)、[SmsNotificationSuppressor.kt:62](D:/project/jev-block-android-ad/core/common/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/common/sms/SmsNotificationSuppressor.kt:62)、[通知匹配:96](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:96)。

每次默认短信 App 发布通知，监听器都查询最近 60 秒的拦截短信，最多 128 条。逐条 `suppress()` 会立即 `retryPending()`；每个队列项再调用 `cancelSmsNotification()`，读取默认短信包、获取整份 `activeNotifications` 并解析匹配。恢复循环结束又额外重试一次，且其他应用的通知也会触发队列重试。

假设初始队列为空、恢复 P 条、全部未匹配，恢复过程会调用匹配函数 `1 + 2 + … + P` 次，循环后再调用 P 次。P=128 时是 **8,384 次活动通知扫描**；若队列本来已经满载，重复恢复可达到 `128 × 128 + 128 = 16,512` 次。数字来自控制流推导，不是设备实测。若活动通知数量为 A，初次恢复约为 O(P² × A)，而且运行在 Main.immediate 协程。

Android N 起监听回调在主线程执行；项目 minSdk 29，适用这一行为。[Android API 文档](https://developer.android.com/reference/android/service/notification/NotificationListenerService)

建议批量恢复队列后只重试一次，每轮共享一个活动通知快照，避免每个项都重新获取。把进程重建与每次通知处理分开，用连接/初始化状态控制恢复，并在短信决策后增量更新队列。非短信通知没有相关变化时可跳过全队列匹配。保留歧义匹配放行、TTL、代次保护和队列上限。

恢复 SQL 也缺少按 `source + decidedAtMs` 查找的索引；其查询计划证据见后文。验证应分别记录每次通知处理的恢复查询次数、重试次数、活动通知快照次数，以及主线程阶段耗时。

## 03 · 每次模型请求重建网络客户端

位置：[BackendModule.kt:37](D:/project/jev-block-android-ad/app/src/main/kotlin/me/ethanxu/jevnoisegate/app/di/BackendModule.kt:37)。

每次 `decide()` 创建一个 `TypeSafeClient`，然后在 `finally` 关闭。核对当前缓存的 `typesafe-sdk-kotlin-android:v0.2.0` 字节码：默认构造调用 `HttpClientFactoryKt.defaultHttpClient()`，该工厂新建使用 OkHttp engine 的 Ktor `HttpClient`；`TypeSafeClient.close()` 关闭该 HttpClient。因此这里确实每条请求都创建独立网络客户端，并非轻量请求包装。

这增加初始化和资源回收开销，也妨碍跨决策复用客户端及其连接资源。新建连接/TLS 的具体次数与耗时还需网络事件实测。Ktor 官方明确建议多请求复用客户端，创建本身并不便宜。[Ktor 客户端文档](https://ktor.io/docs/client-create-and-configure.html)

建议按实际网络配置维护可复用客户端；API Key、Base URL、代理、超时等配置改变时切换版本，等待旧版本在途请求结束后再关闭。普通主题/日志设置更新不应重建客户端。需要处理并发引用，不能在另一个请求使用时直接关闭旧实例。复用客户端后继续保留四并发、取消传播及超时放行。

验证：同配置连续发送多条判断，记录客户端创建次数、连接复用率和 P50/P95；改变代理/Key 后确认新请求使用新配置，旧请求仍能安全完成。

## 04 · 同一短信可能产生两次模型请求

位置：[SmsIngestion.kt:19](D:/project/jev-block-android-ad/feature/sms/src/main/kotlin/me/ethanxu/jevnoisegate/feature/sms/SmsIngestion.kt:19)、[SmsDecisionWorker.kt:31](D:/project/jev-block-android-ad/feature/sms/src/main/kotlin/me/ethanxu/jevnoisegate/feature/sms/SmsDecisionWorker.kt:31)、[通知监听:168](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:168)。

非 OTP、非黑名单短信由 Worker 判断。默认短信 App 的通知在执行恢复/重试后，只要没有被撤销，仍会进入普通通知 `dispatcher.dispatch()`。队列只关联已经判为拦截的短信，无法关联尚未决策或已经放行的短信。广播里的 SMS 事件和通知里的 NOTIFICATION 事件具有不同 ID/来源，也不会被现有按通知 key 去重合并。

因此，同一普通短信在通知先出现或最终放行时，可能产生两次请求和两条记录，占用两个并发名额；两份请求的上下文不同，不能假设结论相同。`SmsNotificationSuppressor` 注释中“短信内容只在 SMS 侧判断”目前没有完全落实。

建议在拥有短信采集权限且已经可靠关联事件时，让短信广播拥有正文决策，通知路径只执行匹配/撤销。没有权限、没有采集记录或消息聚合时需明确降级行为，不能直接无条件跳过整个默认短信包。不要仅凭正文跨来源合并，避免混淆发件人和独立消息。

验证：让短信模型结果延迟，先投递对应通知，检查请求数；同时覆盖最终 ALLOW、未授予 SMS 权限、同正文不同发件人和汇总通知。

## 05 · 每次采集都执行全表清理

位置：[通知监听:185](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:185)、[SmsReceiver.kt:50](D:/project/jev-block-android-ad/feature/sms/src/main/kotlin/me/ethanxu/jevnoisegate/feature/sms/SmsReceiver.kt:50)、[ObservedEvent.kt:130](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/ObservedEvent.kt:130)。

通知每次先 `insert()`，再 `prune()`，才开始判断。清理 SQL 的 `timestampMs < cutoff OR id NOT IN (… LIMIT 10000)` 会扫描外层事件表、构建最近 10,000 条 ID 集合；即使没有过期/超限行也执行这些工作。索引只帮助内部按时间排序，不能消除外层全扫描。

Room suspend DAO 不意味着 SQL 在主线程执行；问题是额外数据库工作与清理写操作位于请求前，增加端到端延迟、写事务争用。桌面探针的 10,000 行“零删除”场景约执行 170,000 个 SQLite VM 指令。

建议把时间清理移到周期维护，将数量清理按新增计数/超限触发并批量执行；过期清理和容量清理可分开设计。需明确批处理允许的临时超量，若必须严格保持 10,000 行则使用有界增量淘汰，不能简单取消上限。已有用户恢复规则应继续独立保留。

## 06 · 同表订阅放大查询，隔离区分页反复读完整前缀

位置：[EventsScreen.kt:69](D:/project/jev-block-android-ad/app/src/main/kotlin/me/ethanxu/jevnoisegate/ui/screen/events/EventsScreen.kt:69)、[MainViewModel.kt:98](D:/project/jev-block-android-ad/app/src/main/kotlin/me/ethanxu/jevnoisegate/app/MainViewModel.kt:98)、[ObservedEvent.kt:122](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/ObservedEvent.kt:122)。

事件页同时订阅最近事件、分组、隔离列表、隔离计数和总数；`quarantineOnly=false` 时仍订阅隔离数据。诊断页的延迟、动作分布、后端尝试数也各有查询。事件插入和结果回填改变同一张表，活跃的 Room Flow 会重新查询；实际执行次数可被事务/调度合并，不能固定算成每条消息必定重查两轮。Room 按表变化重查，即使该变化不影响查询结果。[Room 异步查询文档](https://developer.android.com/training/data-storage/room/async-queries)

当前动作/调用计数、渠道聚合没有对应覆盖索引，探针显示全表扫描和临时 GROUP BY 排序。隔离计数也全表扫描。隔离列表有 `LIMIT`，但“加载更多”只是把上限从 100 逐步增加到 10,000，重新读取整个前缀；切回普通列表后仍保持增大的订阅。上限只能限制行数，正文大小没有对应字节预算。

建议只在对应视图订阅隔离数据，分页采用稳定排序键（时间加 ID）或 Paging，而不是扩大完整列表。按真实频率考虑 `(source, decidedAtMs)`、`(action, timestampMs)` 及 `(packageName, channelId)` 索引，补 Room 迁移并测量写入/空间成本。对于高频统计，可合并投影或维护一致的汇总表。`distinctUntilChanged()` 可减少相同结果传到 UI，不能阻止数据库重查。

## 07 · 主线程冷查询与重复文本处理

位置：[通知监听:128](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:128)、[LabelResolver.kt:27](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/LabelResolver.kt:27)、[EventEntityMapper.kt:30](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/EventEntityMapper.kt:30)。

同一通知正文会在回调去重、`handlePosted()` 去重、`extract()` 中分别提取，包含 MessagingStyle 解析和字符串拼接。实体映射又分别调用 `Fingerprint.of()` 与 `skeletonOf()`，重复执行相同的五轮正则归一化。40 字符限制在归一化最后执行，不限制前面的长正文处理成本。

这些操作在 Main.immediate 上运行。标签有缓存，但冷缺失会同步查 PackageManager/渠道；查询包名失败时，懒加载兜底还会枚举所有安装应用并解析每个标签。包过滤在异步 `handlePosted()`，所以已过滤应用的回调仍先解析文本/创建代次。

建议入口在设置已就绪时先执行便宜的包过滤；一次提取标题/正文，并复用不可变结果；一次归一化同时得到骨架和 hash。冷标签查询/纯文本 CPU 工作可离开主线程，结果返回后重新核对代次；现有缓存不是线程安全实现，迁移时必须同步或保持单线程所有权。保留主线程上最终的代次检查与撤销。

这是长正文和冷启动条件下的风险，不能仅凭重复调用断言日常通知已掉帧。验证需区分冷标签、热缓存和不同正文长度。

## 08 · 设置编辑产生无界写队列和重复加密

位置：[JevApiSettingsScreen.kt:135](D:/project/jev-block-android-ad/app/src/main/kotlin/me/ethanxu/jevnoisegate/ui/screen/settings/JevApiSettingsScreen.kt:135)、[SettingsRepository.kt:148](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/settings/SettingsRepository.kt:148)、[SettingsRepository.kt:266](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/settings/SettingsRepository.kt:266)。

API Key/模型等输入每次变化都会启动保存协程，整份设置快照放入 `Channel.UNLIMITED`。消费者顺序 `DataStore.edit()`，每次都对 Key、代理用户名和密码调用加密，即使只改变主题或日志等级。写入较慢时，中间快照和等待保存完成的 UI 协程会积压。

这些操作已在后台执行，并且 FIFO 修复保护了持久化顺序；这里的问题是多余加密/磁盘工作和队列容量，没有证据表明少量普通编辑会造成内存耗尽。输入页可在提交或失焦时保存，或对文本采用短时间防抖；合并尚未写入的快照时，要确保每个已接受调用的完成语义和取消保护仍成立。相同设置值可提前跳过；未改变凭据可保留已有密文。

验证：连续输入/快速修改设置，记录保存次数、加密次数、队列长度、最终重启读回值；用慢存储模拟确认不会无界积压。门槛滑块已有本地状态并在松手后提交，不属于逐帧写盘问题。

## 09 · 当前延迟指标无法解释完整撤销耗时

位置：[MessagePipeline.kt:67](D:/project/jev-block-android-ad/core/pipeline/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/pipeline/MessagePipeline.kt:67)、[DecisionDispatcher.kt:50](D:/project/jev-block-android-ad/core/dispatch/src/main/kotlin/me/ethanxu/jevnoisegate/core/dispatch/DecisionDispatcher.kt:50)、[Platform.kt:20](D:/project/jev-block-android-ad/core/common/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/common/Platform.kt:20)。

`latencyMs` 从进入模型段开始，包含 Semaphore 排队和后端创建/请求/重试，但不包含之前的标签、入库、清理、用户规则查询、历史统计，也不包含决策写回与最终撤销。SMS Worker 的调度等待同样缺失。本地决策记录 0ms，因此本地规则快不代表完整处理链路零成本。它不是纯网络耗时，也不是端到端撤销耗时。

流水线 15 秒 deadline 只包模型段，不覆盖数据库前处理和写回；所以 README 的“total deadline”不能理解为从收到通知到撤销最多 15 秒。四并发保护有效，但若一次判断占用名额平均 S 秒，饱和吞吐约 `4 / S` 条/秒；例如 S=3 秒、同时到达 64 条且无其他任务时，至少第六批要到 15 秒以后才能开始，后续等待者会按现有设计超时放行。这是排队模型推算，不是当前后端实测。

此外耗时使用 `System.currentTimeMillis()`，系统校时可影响差值。建议持续时间采用单调时钟，事件日期和持久化 TTL 保留墙钟。分开采集 ingress→decision→cancel、queue、backend、DB 和 Worker 调度耗时；同时记录未取得名额的超时、在途过载和被更新取消的数量。当前 P50/P95 只针对有结果的 MODEL/FALLBACK 记录，无法代表所有采集事件。

## SQL 探针及验证结果

可复现脚本：[sql_probe.py](D:/project/jev-block-android-ad/docs/performance/sql_probe.py)；完整结果：[sql_probe_results.json](D:/project/jev-block-android-ad/docs/performance/sql_probe_results.json)。脚本从当前 Room v3 导出 schema 和 DAO 抽取实际 SQL，以桌面 Python SQLite 内存数据库加载 1,000/10,000 行、每行 1 KiB 正文；SMS 占 20%，隔离占 25%，10 个分桶。它没有修改真实数据库或安装索引。

| 查询（10,000 行） | 当前计划/近似 VM 指令数 | 临时候选索引后的计划/近似 VM 指令数 |
|---|---|---|
| prune，无过期/超限行 | 外层全扫描 + 最近 ID 子查询；170,000 | 同样扫描；170,000 |
| recentSuppressedSms，最近 60 秒 | 全扫描 + 临时排序；37,100 | source/时间范围查找；200 |
| observeQuarantineCount | 全扫描；32,500 | action 覆盖索引查找；7,500 |
| observeTopChannels | 全扫描 + 临时分组/排序；140,400 | 覆盖索引扫描 + 计数排序；80,500 |
| observeBackendAttemptCount | 全扫描；40,000 | 未加对应索引，仍全扫描；40,000 |

候选索引为 `(source, decidedAtMs DESC)`、`(action, timestampMs DESC)`、`(packageName, channelId)`。VM 数以每 100 条指令的进度回调估算，精度约 100 条；JSON 还保留了每查询 20 次热运行的耗时中位数。不同 SQLite 版本、分布和设备会改变计划/耗时。这里没有衡量新增索引的写入和存储代价，也没有模拟 Android Room、Binder、磁盘、网络或多协程竞争，不能直接据此选择所有索引。

复现：

```powershell
python docs/performance/sql_probe.py > docs/performance/sql_probe_results.json
```

验证状态：

- 初次直接运行 Gradle 时，当前 shell 没有 JAVA_HOME/Java PATH；从现有 daemon 日志定位到缓存 JDK 17 与 Android SDK 后，仅设置本次进程环境变量。
- `.\gradlew.bat verify --console=plain` 成功，13 秒，340 个任务中 6 个执行、334 个为 up-to-date。单测任务复用既有成功结果，并未强制重跑；现有 XML 为 20 套、148 项、0 failure/0 error。
- SQL 探针实际运行成功，证明上述查询计划与工作量趋势。
- `adb devices -l` 无连接设备；未做 Perfetto、帧率、堆内存、耗电、实际模型/代理延迟或 Worker 调度测量。
- 本轮交付前 `git diff --check` 通过。

## 建议实施顺序与验收

1. 先补阶段计时和计数基线，同时修复 01/02：日志 IO 完全由单个后台消费者负责；短信每轮只获取一个活动通知快照，恢复过程只重试一轮。它们的验收可以直接观察调用次数和线程。
2. 处理 03/04：同配置复用客户端；可靠关联的短信只发起一次正文判断。测量连接复用和实际请求数，保留权限缺失/通知聚合的降级路径。
3. 处理 05/06：清理移出单条通知必经路径，按设备数据选索引，隔离区按需订阅并采用真正分页。保留数据库迁移、保留策略及恢复规则语义。
4. 最后处理 07/08，按主线程 trace 和写入计数判断收益。不要先围绕短文本微优化替代网络/队列/数据库热点处理。

设备基线至少覆盖：冷启动后首条通知、连续普通通知、64 条突发、128 条未匹配短信队列、10,000 条历史下打开/切换隔离区、长正文和弱网。分别记录端到端 P50/P95、主线程最长任务、数据库查询/清理次数、网络请求/连接创建次数、峰值在途/排队数量、已加载正文大小及 settings 写队列长度。帧率应针对前台界面测量；后台监听延迟用阶段计时/trace 测量。

已经存在的保护无需重复列为未解决问题：设置加载不再阻塞启动主线程；模型四并发和 15 秒模型段超时、通知 64 个在途上限、去重 256 条、短信队列 128 条/60 秒、事件 10,000 行、延迟样本 1,000 条、Release R8/资源裁剪都已在当前代码中。暂未启用模板决策缓存是设计选择，缺少命中率证据前不列为必修问题；尤其不能直接用只含 40 字符正文骨架的现有指纹复用拦截结果。
