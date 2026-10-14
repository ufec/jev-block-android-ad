# 性能复审 · 第二轮 · 2026-10-09

本文保留修复前的 schema 4 审查快照。10–17 已实施，当前实现、测试与 schema 5 测量见 [第二轮修复记录](PERFORMANCE_FIXES_ROUND2.md)。

范围是 HEAD `e55eb15` 加当前未提交的性能修复工作区，Room schema 4。本轮继续审查上轮修复后的残留开销，并覆盖上轮探针缺少的数据分布；只新增审查文档与独立探针，没有再修改业务代码。编号接续 [第一轮审查](PERFORMANCE_REVIEW.md)，已实施的修改见 [修复记录](PERFORMANCE_FIXES.md)。

结论：优先处理 **10 的归一化正则**。其余问题主要是隐藏查询、数据分布导致的扫描、短信去重顺序和辅助后台工作。以下 P1/P2 是处理顺序，不代表已经在手机上测得 ANR 或掉帧。

## 本轮问题总览

| 编号 | 优先级 | 问题 | 触发条件 | 证据强度 |
|---|---|---|---|---|
| 10 | P1 | URL 负例正则出现非线性开销，40 字符骨架限制没有限制计算量 | 长连续字母/数字串、不含可识别域名 | 实际 Kotlin 方法与其 URL 正则的桌面 JVM 探针 |
| 11 | P2 | 隔离区仍读取普通事件全部正文并在主线程分组 | 打开隔离区，持续入库或决策写回 | 明确订阅路径、Room 生成代码及负载探针 |
| 12 | P2 | 模型记录稀疏时，延迟统计仍扫描所有历史 | 最近事件主要为本地决策，MODEL/FALLBACK 在较旧记录中 | 实际 DAO SQL、VM 步数与查询计划 |
| 13 | P2 | 高频单一分桶的历史统计仍逐次扫描分桶全部记录 | 同一 App/渠道或短信发送者占据大量历史 | 实际 DAO SQL、集中分桶探针 |
| 14 | P2 | 短信通知的查库/等待在完成后去重之前 | 默认短信 App 重复发布已完成处理的相同通知 | 控制流；单条所有权测试未覆盖完整监听器顺序 |
| 15 | P2 | 短信批匹配仍在主线程做 P×A 次配对 | 128 条待撤销短信、多条活动短信通知、无匹配 | 实际 Kotlin 批匹配探针与主线程调用路径 |
| 16 | P2 | App 标签失败结果没有被缓存，且标签查询共用一把锁 | 包可见性受限或已卸载 App；冷查询较慢 | 当前代码、Kotlin 标准库语义与空值缓存探针 |
| 17 | P3 | 日志队列空闲或日志 OFF 时仍每两秒运行一轮消费者 | 日志初始化后进程长时间可运行但没有新日志 | 确定的定时循环；尚无耗电测量 |

## 10 · 骨架截断在正则之后，长负例仍会消耗秒级 CPU

位置：[TextNormalizer.kt:25](../core/fingerprint/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/fingerprint/TextNormalizer.kt#L25)、[normalize:59](../core/fingerprint/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/fingerprint/TextNormalizer.kt#L59)、[EventEntityMapper.kt:22](../core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/EventEntityMapper.kt#L22)。

URL 分支含 `[\w.-]+\.(?:com|cn|...)`，未限定匹配起点。连续可匹配的字母串没有点号时，搜索会从多个起点反复尝试并回退。`normalize()` 先执行所有 `Regex.replace()`，最后才 `take(40)`，所以骨架容量保护没有保护 CPU。模型 15 秒时限只包含模型阶段，不包含之前的实体构造。

探针调用当前编译的 `TextNormalizer.normalize()`，并通过反射读取**同一个 URL_PATTERN 实例**单独测该阶段；输入是 `"a".repeat(N)`，不是一个复制后另写的算法。全局先预热 30 次，每种长度再预热 2 次、测 5 次中位数：

| 输入字符数 | 完整归一化 | 仅 URL 阶段 | 最终骨架字符数 |
|---:|---:|---:|---:|
| 1,024 | 8.4653 ms | 6.6935 ms | 40 |
| 4,096 | 94.1321 ms | 82.1315 ms | 40 |
| 8,192 | 390.0969 ms | 393.9980 ms | 40 |
| 16,384 | 1,592.1909 ms | 1,319.1786 ms | 40 |

同长度的中文正文约 7.3191 ms，短 ASCII token 加空格约 8.1256 ms。阶段分别测量，不能相加，也不能要求某次阶段中位数必然小于另一次完整中位数。前一次独立运行的 16,384 字符负例也约 1.57 秒，负例开销已可复现；毫秒数仍受桌面 JVM、JIT 和机器负载影响，不是 Android 耗时预测。

通知侧已把实体构造移到 Default，但这只改变线程，未消除计算量。取消旧通知任务也不会立刻中断正在运行的同步正则；取消后的任务可能继续占用计算线程。SMS 侧也会在实体构造时经过该归一化。[Kotlin 的取消说明](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/ensure-active.html) 要求长计算主动检查取消，单个不可中断的正则调用中间无法靠外层检查及时退出。

建议首先将 URL 识别改为可证明线性的 token 扫描，避免从连续域名字符的每个偏移重新尝试。保持完整正文用于 OTP 判断、模型输入和完整内容纠正规则；不能简单把正文截成 40 字符后判断，否则尾部验证码等保护会丢失。若引入处理预算，超限应保守放行并记录原因。

验收：同一 JVM/设备下 1K、4K、8K、16K 的长 ASCII 负例接近线性增长；同时覆盖中文、实际 URL、跨骨架边界的 URL、金额/日期及完整正文末尾 OTP。检查通知更新取消时剩余 CPU 时间，而非仅检查 Job 已标记取消。

## 11 · 隔离区停掉了隐藏隔离查询，却没有停掉隐藏普通事件查询

位置：[EventsScreen.kt:68](../app/src/main/kotlin/me/ethanxu/jevnoisegate/ui/screen/events/EventsScreen.kt#L68)、[MainViewModel.kt:90](../app/src/main/kotlin/me/ethanxu/jevnoisegate/app/MainViewModel.kt#L90)、[分组:102](../app/src/main/kotlin/me/ethanxu/jevnoisegate/app/MainViewModel.kt#L102)。

`events` 与 `eventGroups` 在读取 `quarantineOnly` 之前无条件收集。切到隔离区只增加隔离页订阅，不移除这两个订阅。它们共享同一个 `events` StateFlow，所以**不是两次独立 SQL**，但仍会读取最近 300 条 `SELECT *`，并经 `viewModelScope` 在主线程 `groupBy`，即使该分组完全没有显示。

同表的入库、决策写回和恢复等操作会使正在订阅的查询失效。当前生成的 `ObservedEventDao_Impl` 为这些 Flow 注册的是整个 `observed_event` 表；结果后的 `distinctUntilChanged()` 可以减少下游通知，不能阻止已经执行的 SQL 与实体构造。该行为也符合 [Room 可观察查询文档](https://developer.android.com/training/data-storage/room/async-queries)。写入突发可能合并失效通知，因此不能直接断言每个写入必定执行一次所有查询。

探针读取最近 300 条、每条正文 16 KiB 时，仅正文 ASCII 负载就为 4,915,200 bytes（约 4.69 MiB）；折叠内容或设置 `maxLines` 不改变读取量。当前 10,000 行样本，1 KiB 正文查询中位数 1.5929 ms，16 KiB 为 4.4514 ms，VM 步数都为 8,708：固定行数并不等于固定传输、分配成本。未计入 Android Cursor、Kotlin 对象、标题、UI 或堆峰值。

建议按当前视图订阅普通事件或隔离页，普通列表用轻量投影，展开详情再读取正文；分组计算移到 Default。仍需保留共享 Flow，避免因拆分订阅重新产生重复 SQL。

验收：隔离区持续写入时，普通 `observeRecent(300)` 没有活动订阅；正文只按详情需求加载；用 QueryCallback/Perfetto 与堆采样核对，不以界面看起来折叠作为证据。

## 12 · 延迟样本有 LIMIT，但过滤不足 1,000 条时会扫完整个表

位置：[ObservedEvent.kt:256](../core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/ObservedEvent.kt#L256)、[MainViewModel.kt:123](../app/src/main/kotlin/me/ethanxu/jevnoisegate/app/MainViewModel.kt#L123)。

`observeLatencies()` 先按 MODEL/FALLBACK 筛选，再按时间取最近 1,000 条。当前索引只能按 `timestampMs` 遍历并逐行读取 `decidedBy/latencyMs`。上轮种子全部设为 MODEL，恰好能很快凑够 LIMIT，因此掩盖了模型样本少或集中在旧记录时的扫描。

10,000 行中仅最旧 100 条为 MODEL，其余为本地 OTP_GATE：返回 100 行仍需 **71,118 个 VM 步**，而全 MODEL 样本取 1,000 条只需 18,023 步。当前机器中位数分别 4.0505 与 0.8364 ms。诊断页打开时，本地决定写回也会触发该同表查询重新计算。

探针只在临时内存库安装 `(decidedBy, timestampMs, latencyMs)` 覆盖索引，同一稀疏场景变为 2,245 步、0.1021 ms；查询仍有排序树，不能描述成完全免扫描/免排序。这个索引**没有安装到业务 schema**，尚未测它的设备写入、空间和迁移成本。

建议测量覆盖或符合固定过滤条件的索引方案，或把模型统计投影维护为较小的数据集；不要改变“最近 1,000 条模型阶段记录”为“最近 1,000 条任意事件再筛选”，二者语义不同。

验收：全 MODEL、零 MODEL、稀疏旧 MODEL、最近 MODEL 四种分布，检查实际查询计划、写入开销和百分位结果一致性。

## 13 · 单一热门分桶仍把历史扫描放进每条模型判断之前

位置：[channelStats:244](../core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/ObservedEvent.kt#L244)、[DecisionDispatcher.kt:52](../core/dispatch/src/main/kotlin/me/ethanxu/jevnoisegate/core/dispatch/DecisionDispatcher.kt#L52)。

`bucketKey` 索引加速定位分桶，随后 `COUNT/SUM` 仍遍历该分桶所有记录并读取 action。10,000 行均分为 10 桶时为 11,523 步；集中在一个桶时为 **112,513 步**，中位数从 0.3711 增到 3.7201 ms。事件库总容量上限有效，但没有令单次历史查询成本恒定。

该查询发生在模型排队之前，四个后端名额与 15 秒模型时限不限制这部分开销。缺少 API Key 的检查更晚，位于 BackendModule，因此未配置模型时普通消息也会先做这项最终用不到的历史查询。本地 OTP/黑名单/用户纠正已提前返回，不在此问题范围内。

建议评估按分桶维护增量总数/拦截数，或先测覆盖索引能否降低回表成本；聚合总数与任意历史窗口不是等价替代。若采用增量统计，入库、结果变更、恢复、年龄删除、容量淘汰和清空都要在同一事务正确更新，并继续排除当前事件。模型未配置路径可在保留本地政策和审计的前提下避开模型专用历史准备。

验收：集中桶长历史下的吞吐/数据库等待；每种删除与恢复之后统计等于直接 SQL 聚合，首条事件的历史仍为 0。

## 14 · 相同短信通知先匹配、查库甚至等一秒，之后才判断重复

位置：[NoiseGateNotificationListener.kt:225](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt#L225)、[去重:241](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt#L241)、[SmsDecisionOwnership.kt:17](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/SmsDecisionOwnership.kt#L17)。

默认短信 App 分支先 `retryPending()`，再订阅 `observeSmsOwners()` 等待最多一秒，之后才 `recentPosts.markSeen()`。入口的相同内容保护只在旧 Job 仍活跃时成立。前一任务已完成的重复通知仍会查库：有所有者直接返回，甚至从不进入完成后去重；没有所有者则可能先等满一秒才在去重处丢弃。

等待是挂起，不是把主线程堵住一秒。问题是重复等待任务、Room 同表失效查询、主线程匹配，以及不必要的回退计数；不能把这一条描述成每次必定再发一次模型请求。现有测试验证了 `awaitSmsOwner()` 的单次行为，未验证完整监听器里的前后顺序。

建议把完成后去重覆盖到所有权查询之前，区分“通知确实新到”和“已处理内容刷新”；短信撤销桥的重试仍要能被新短信决定及新候选通知触发，不能为了去重漏掉一个晚到的可撤销通知。

验收：相同通知在上一任务结束后重复投递，有/无 SMS 所有者分别记录 query 次数、等待时间、后台请求和撤销次数；不同正文、不同世代、广播晚到和队列新增项仍应正确处理。

## 15 · 一次活动快照仍对应 P×A 次主线程配对

位置：[SmsBatchMatcher.kt:17](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/SmsBatchMatcher.kt#L17)、[SmsNotificationMatcher.kt:4](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/SmsNotificationMatcher.kt#L4)、[主线程回调:87](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt#L87)。

上轮去掉了恢复循环里的重复快照，收益成立。但批匹配对每个 pending 项仍 `notifications.filter`，每对重复处理数字、trim、正文比较与截断前缀判断。无匹配时，128 项 × 128 个活动候选就是 16,384 次配对；多个短信发布还可能连续触发多轮主线程重试。

实际方法探针确认每轮快照仍只有 1 次。当前 JVM 下 128×32 的 1K 公共前缀负例约 1.8674 ms，128×128 为 2.0831 ms，16K 公共前缀为 3.0263 ms。这个结果没有证明日常已掉帧，优先级低于正则和数据库问题；它只证明残留循环和工作量，且不含 Binder 获取与解析活动快照的成本。

建议一次预处理双方文本/号码，用完整正文索引处理精确匹配，截断通知单独处理；或将纯匹配放到 Default。后台匹配完成回主线程后必须重新检查连接、通知内容/世代和过期时间，避免快照过时撤销新消息。保持同正文不同发送者歧义保护。

验收：128 项无匹配、多候选冲突、混合精确/截断、同 key 已更新；记录主线程最长任务和每轮配对次数，不能只看快照计数已经为 1。

## 16 · nullable getOrPut 会重复加载失败标签，全局 Mutex 放大等待

位置：[LabelResolver.kt:27](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/LabelResolver.kt#L27)、[fallback:47](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/LabelResolver.kt#L47)、[NoiseGateNotificationListener.kt:243](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt#L243)。

`HashMap<String,String?>.getOrPut()` 遇到已存入的 null 会再次调用 loader，当前查询失败又无全量兜底的包因此每次重新 `getApplicationInfo/getApplicationLabel`。实际标准库探针连续调用同一 key 10 次、loader 返回 null，loader 也执行了 10 次；不是空值只查一次。[Kotlin getOrPut 文档](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/get-or-put.html) 明确说明返回值必须存在且非 null 才复用。

上轮已把这些查询移到 IO，主线程同步 Binder 问题已修复。但所有包的 App/渠道查询仍在同一把 `labelLock` 内；一个冷兜底枚举或慢渠道 Binder 会让其他消息等待，即使后者本可命中缓存。等待发生在模型排队之前且没有辅助标签预算；尚未测设备上的 Binder 时间。

建议区分未查过、已查失败与成功值，失败加有限 TTL 或包变化失效。缓存管理与慢查询分开，可按包合并在途查询；标签只是辅助上下文，异常/预算超限应退回已有包名，仍要保护缓存线程安全。取消协程不能保证任意同步 Binder 调用立刻中断。

验收：同一不可见包多次消息只在 TTL/包变化后重试；某个冷包较慢时，其他热包不排在它后面。不要用“已经在 IO”作为不存在处理延迟的证明。

## 17 · 空闲日志消费者仍定时恢复，不需要数据也做一轮工作

位置：[BatchedLogWriter.kt:31](../core/common/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/common/log/BatchedLogWriter.kt#L31)、[AppLog.configure:147](../core/common/src/androidMain/kotlin/me/ethanxu/jevnoisegate/core/common/log/AppLog.kt#L147)。

消费者无条件以 `withTimeoutOrNull(wait) { signals.receive() }` 循环；即使 pending 为空，超时仍进行锁操作、计算和 `publish()`，并重新设置两秒时限。OFF 只禁止生产者入队，不停止初始化过的消费者。在进程能持续运行时，约对应每小时 1,800 次空轮询。

这里没有 wakelock，不能从协程定时轮询推断它能把休眠设备唤醒，也不能凭此计算耗电；列为 P3 的可避免调度。建议空队列无限期等信号，只有存在待写日志时启动刷新截止时间；已有日志在切 OFF 后仍需按约定落盘，clear 顺序保持串行。

验收：虚拟时钟推进一小时、无输入时没有持续消费者 tick；小批量两秒内刷新、阈值刷新、清除后写新日志和 OFF 后旧日志落盘仍成立。

## 尚需设备/产品负载决定的观察项

- **辅助 UI 查询**：`AppFilterViewModel` 先枚举所有包，再逐包 `getLaunchIntentForPackage` 与 label，重新进页面会重新加载；`rememberAppIcon/rememberAppLabel` 的缓存仅属于当前组合实例，排行榜同 App 多渠道或离开再进入会重复加载。均已异步且列表有界，未测冷加载延迟前不升为主要问题。
- **主线程正文提取**：监听回调在 64 在途上限检查前调用 `contentOf()`，合集逐项 `text !in parts`，去重采用线性列表查找；长合集的提取/复制仍要做，即便随后过载放行。这里只确认路径，未做 Android Notification/RemoteViews 对象的设备基准，不把普通短通知称为卡顿。
- **统计口径**：SMS 本地入库的 `trace.database { dao.insert(event.toEntity(...)) }` 包含实体归一化 CPU，并非纯数据库等待；后续 Worker 样本只记 Worker 侧 DB。`workerWaitMs=workerStart-ingress` 包含广播解析、本地规则、入库和 enqueue，不能解释成纯 WorkManager 队列时间。慢正则可能被本地样本错归到数据库；应加准备/持久化/入队起点才能用于下一轮定位。
- **任务积压**：唯一 SMS WorkRequest 只去除同 ID 重复，四并发只限制模型段，通知的 64 上限不限制 SMS 的持久任务总数。大批量 SMS 下需要记录 WorkManager pending/age 和事件被容量淘汰后的无效工作量；目前没有实测队列积压，不声称已出现内存泄漏。

已排除的猜测：UserRulesScreen 与 SmsBlacklistScreen 输入只改本地草稿，“添加”才保存；不能重复报告为逐字写设置。API 表单、相同配置客户端复用、日志生产者同步写盘、旧全表 prune、隔离区渐增 LIMIT 都已有代码修复，不重新列为未解决问题。

## 探针、验证和下一步

CPU：[HotPathProbe.java](performance/HotPathProbe.java)、[运行脚本](performance/run_hot_path_probe.ps1)、[原始结果与源码 SHA-256](performance/hot_path_round2_results.json)。脚本默认先生成当前 Kotlin 编译产物，再由 Java 调用实际方法；本轮先完成下列 verify/编译命令后使用 `-SkipBuild`。`getOrPut` 探针只验证当前标准库的 null 语义，未模拟 Android PackageManager。

SQL：[sql_round2_probe.py](performance/sql_round2_probe.py)、[原始结果](performance/sql_round2_results.json)。复用第一轮脚本读取实际 schema 4 与 DAO；每查询 20 次暖运行，无生产库更改。上轮 JSON 不覆盖。SQL VM 步数比跨运行毫秒差异更适合判断此次扫描趋势。

```powershell
# 使用 JDK 17、Android SDK 与 Python；JAVA_HOME/ANDROID_HOME 按本机安装设置。
./gradlew verify :core:fingerprint:bundleAndroidMainClassesToCompileJar :feature:notification:bundleLibCompileToJarDebug --console=plain
./docs/performance/run_hot_path_probe.ps1 -SkipBuild
python docs/performance/sql_round2_probe.py > docs/performance/sql_round2_results.json
```

当前命令成功：2 分 13 秒，340 个任务，6 执行、334 up-to-date。现有测试 XML 汇总为 27 套、169 项、0 失败/错误/跳过；本轮复用了已有测试成功结果，没有新增业务测试或强制重跑。Lint 为 0 错误、26 警告。探针实际执行完成；本轮没有重新构建 APK。`adb devices -l` 无设备，未测手机帧率、耗电、堆峰值、Binder 或实际模型网络耗时。

建议处理顺序：10 → 11/12 → 13/14 → 15/16 → 17。开始改动时先处理负例复杂度与订阅边界，保持 OTP、歧义短信保护、完整内容纠正规则和历史统计语义，再用设备确认索引及调度优化收益。
