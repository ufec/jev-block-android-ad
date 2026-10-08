**项目问题梳理 · 2026-10-08**

> 本文保留初始审查基线，问题位置及验证限制描述的是修复前状态。后续修复和构建验证见 [修复记录](FIXES.md)。

审查基线：`0030a78`（`chore: bump the version to 0.1.1`）。检查范围包括 Gradle/发布流程、通知与短信采集、决策流水线、设置持久化、Room 存储及相关 UI/测试。

结论：当前最需要修复的是监听过滤未生效、验证码保护存在绕过路径，以及异步决策可能撤销更新后的正常通知。以下列出 13 项问题，其中 6 项 P1、7 项 P2。P1 表示应优先修复的隐私、误拦或可用性问题；P2 表示特定条件下的可靠性、功能语义或验证问题。

**验证边界**

以下结论来自源码控制流与跨模块调用核对，列出的场景是复现步骤或测试输入，并非已经完成的设备实测。已尝试运行 `.\gradlew.bat :app:testDebugUnitTest --console=plain`，退出码为 1：当前环境未配置 `JAVA_HOME`，PATH 中找不到 Java。仓库没有 `local.properties`，常见 Android SDK/JDK 路径检查也未找到可用安装。因此本次未完成编译、单测、Lint 或设备验证，不能据此断言项目构建失败。没有修改业务代码。

| 编号 | 优先级 | 问题 | 直接影响 |
|---|---|---|---|
| 01 | P1 | App 监听开关未接入通知入口 | 关闭后仍采集、上传和判断 |
| 02 | P1 | OTP 闸门只检查正文 | 标题中的验证码可能被上传 |
| 03 | P1 | 短信黑名单先于 OTP 闸门 | 黑名单号码的验证码仍被判为拦截 |
| 04 | P1 | 异步撤销没有验证通知版本 | 旧广告结果可能撤掉后续正常消息 |
| 05 | P1 | 配置加载失败后启动永久等待 | 损坏配置或读取异常导致应用不可用 |
| 06 | P1 | 无效 Base URL 的代理探测异常未兜底 | 保存代理时可能崩溃 |
| 07 | P2 | 短信撤销缺少待匹配队列 | 快速黑名单决策可能撤不掉稍后出现的通知 |
| 08 | P2 | 广播生命周期内等待模型与重试 | 弱网时可能超出广播时限 |
| 09 | P2 | 隔离与静默移除没有对应功能区别 | 界面承诺的隔离恢复未实现 |
| 10 | P2 | 设置写盘没有与内存更新统一排序 | 连续改动重启后可能回到旧值 |
| 11 | P2 | uploaded 标记代表后端调用而非实际上传 | 隐私诊断统计不能证明真实外发 |
| 12 | P2 | 正文非空时去重忽略标题 | 同正文、不同标题的消息被跳过 |
| 13 | P2 | 发布流程只执行 app 模块单测 | 核心决策测试未纳入发布门禁 |

**01 · App 监听开关没有生效**

位置：[通知入口](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:111)、[设置写入](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/settings/SettingsRepository.kt:179)。

`mutedPackages` 被设置页展示和持久化，但通知服务只读取 `developerMode`，没有检查通知来源是否属于 `mutedPackages`。`NotificationExtractor.isRecordable` 也只处理自家包名。关闭某个 App 后，其消息仍会进入数据库和模型，并可能被撤销。

复现：关闭某个第三方 App 的监听，再让它发送一条新正文的通知，检查事件记录和请求。建议在去重、标签解析和入库之前检查过滤集合，并覆盖“关闭后不上送、不入库、不执行动作”。

**02 · 标题中的验证码绕过本地隐私保护**

位置：[OTP 调用](D:/project/jev-block-android-ad/core/pipeline/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/pipeline/MessagePipeline.kt:40)、[关键词规则](D:/project/jev-block-android-ad/core/pipeline/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/pipeline/OtpGate.kt:19)、[上传 state](D:/project/jev-block-android-ad/core/decision/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/decision/StateBuilder.kt:41)。

OTP 闸门只检查 `event.body`，而上传 state 同时包含 `title` 和 `body`。输入 `title="验证码：123456"`、`body="请勿向他人透露"` 时，正文不满足关键词加数字的条件，流水线会继续调用后端，将含验证码的标题交给模型。

建议在上传前检查所有实际外发的消息文本。现有规则还只支持限定关键词与连续数字，例如 `Your code is 123456`、字母数字混合码也未覆盖；对“验证码永不上送”的绝对表述，应明确识别边界并补充样本。

**03 · 黑名单绕过验证码优先放行**

位置：[黑名单提前返回](D:/project/jev-block-android-ad/core/dispatch/src/main/kotlin/me/ethanxu/jevnoisegate/core/dispatch/DecisionDispatcher.kt:48)、[OTP 闸门](D:/project/jev-block-android-ad/core/pipeline/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/pipeline/MessagePipeline.kt:40)。

README 声明执行顺序为 OTP → 黑名单 → 模型。实际调度器先检查黑名单，命中后返回 `SILENT_SUPPRESS`，完全不进入 OTP 闸门。例如把 `1069` 加入黑名单后，该前缀号码发送 `您的验证码为123456` 仍会被判为拦截；若通知已经出现且匹配成功，会被撤销。

建议统一规则入口并让 OTP 先执行。若产品意图是“黑名单覆盖验证码保护”，则必须统一文档和界面语义；当前实现与已有保护承诺不一致。

**04 · 旧决策可能撤销更新后的正常通知**

位置：[异步任务](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:142)、[按 key 撤销](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:168)。

每次新正文启动独立协程，决策结束后仅按通知 key 撤销。许多应用复用相同通知 ID/key：广告 A 开始判断，随后同 key 更新为正常消息 B；B 即使被放行，A 的迟到拦截结果仍会撤掉当前 B。

复现：让 A 的后端结果延迟，再投递同 key 的 B，先返回 B=ALLOW，后返回 A=QUARANTINE。建议为每个 key 跟踪消息代次，只有仍对应当前消息的结果可以执行撤销，并处理通知移除/重新投递。

**05 · 设置读取失败后，启动线程无法恢复**

位置：[首次加载](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/settings/SettingsRepository.kt:138)、[无限等待](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/settings/SettingsRepository.kt:147)、[启动调用](D:/project/jev-block-android-ad/app/src/main/kotlin/me/ethanxu/jevnoisegate/app/JevNoiseGateApp.kt:25)。

加载协程只有成功后才 `ready.countDown()`，没有错误路径或 `finally`。一旦 `dataStore.data.first()` 因损坏文件或 IO 错误抛出异常，信号永不释放；`Application.onCreate()` 在主线程执行 `ready.await()`，于是永久阻塞。没有配置 corruption handler。

建议显式表示加载成功/失败，保证等待者总能结束；失败时给出可恢复的降级状态，并避免在启动主线程进行无期限等待。

**06 · 无效 Base URL 可导致保存代理崩溃**

位置：[URL 自动保存](D:/project/jev-block-android-ad/app/src/main/kotlin/me/ethanxu/jevnoisegate/ui/screen/settings/JevApiSettingsScreen.kt:159)、[请求构造](D:/project/jev-block-android-ad/app/src/main/kotlin/me/ethanxu/jevnoisegate/app/ProxyProbe.kt:84)、[探测调用](D:/project/jev-block-android-ad/app/src/main/kotlin/me/ethanxu/jevnoisegate/app/SettingsViewModel.kt:228)。

Base URL 没有有效性校验，输入时直接持久化。代理探测在 `try` 之前调用 `Request.Builder().url(...)`，只在后面捕获 `IOException`；无效 URL 导致的 `IllegalArgumentException` 会逃出探测和 `viewModelScope.launch` 的处理路径。

复现：Base URL 填 `abc` 或清空，进入代理页填写合法代理地址，点击保存。建议在持久化和探测入口校验 HTTP(S) URL，并把客户端/请求构造异常转成可展示的失败结果。OkHttp 的 URL 构造器明确会对无效 URL 抛出该异常，见 [官方源码](https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Request.kt)。

**07 · 短信黑名单的快速决策存在投递竞态**

位置：[撤销桥](D:/project/jev-block-android-ad/core/common/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/common/sms/SmsNotificationSuppressor.kt:49)、[查找当前通知](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:83)。

`suppress()` 只执行一次回调；服务只扫描当前 `activeNotifications`，没有命中就返回，没有保存待处理决策。黑名单是无网络的快速路径，很可能早于短信 App 发出通知；通知稍后出现时又按普通通知走模型，其 source 不是 SMS，无法执行号码黑名单。模型失败放行时，用户会看到本应被黑名单撤销的通知。

建议保存有时限的待撤销记录，并在短信通知投递时消费。另需核对匹配身份：当前 `sender` 参数未参与匹配，只比较 `EXTRA_TEXT` 的双向包含关系，未处理多消息聚合及其他正文样式；不应仅凭共有短片段就撤掉包含正常消息的聚合通知。

**08 · SMS 广播等待网络，没有控制总处理时长**

位置：[goAsync 与模型判断](D:/project/jev-block-android-ad/feature/sms/src/main/kotlin/me/ethanxu/jevnoisegate/feature/sms/SmsReceiver.kt:64)、[完成广播](D:/project/jev-block-android-ad/feature/sms/src/main/kotlin/me/ethanxu/jevnoisegate/feature/sms/SmsReceiver.kt:93)。

`pending.finish()` 要等所有事件顺序完成模型请求后才调用，没有总超时。默认单次超时 10 秒，应用文案说明 SDK 默认还重试 2 次，加上退避或多个事件时可能超过广播时限；用户还可以增大请求超时。

Android 官方说明 `goAsync()` 不取消广播执行时限，等待 `finish()` 的时间也计入，通常为 10 秒，部分非前台广播允许约 30 秒或更久。这里应视为需要设备验证的 ANR 风险。建议广播中只完成必要采集和本地规则，将长网络任务交给合适的持久后台工作机制，或限制广播内工作的总时间。依据：[BroadcastReceiver 文档](https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync())。

**09 · “隔离后捞回”只有枚举与文案，没有完整实现**

位置：[动作定义](D:/project/jev-block-android-ad/core/model/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/model/DomainModel.kt:18)、[设置承诺](D:/project/jev-block-android-ad/app/src/main/kotlin/me/ethanxu/jevnoisegate/ui/screen/settings/UserRulesScreen.kt:324)、[实际执行](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:167)。

`QUARANTINE` 被定义为可查看、可捞回，并升级为 USER 决策；设置也声称与静默移除不同。实际两种动作均执行 `cancelNotification`，两种事件都留在同一事件表。没有对应恢复操作、用户纠正写入或指纹覆盖逻辑。事件记录只能帮助查看已保存文本，不能兑现“捞回”的行为。

建议实现隔离与纠正闭环，或先将动作和文案收敛到目前实际支持的能力，避免让用户误以为被误拦后能恢复。

**10 · 内存设置与持久化顺序可能不一致**

位置：[异步写盘](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/settings/SettingsRepository.kt:265)。

`mutate()` 同步更新内存后，为每次写入独立启动一个 IO 协程。DataStore 可以串行处理事务，但不能保证这些协程按内存更新的先后顺序进入事务。连续设置 A→B 时存在磁盘最终写成 A、内存为 B 的执行顺序；重启后表现为设置回退。写入失败也没有反馈，调用方拿到的是内存更新完成，而非持久化完成。

这是源码可见的排序缺口，尚未运行并发压力验证。建议将内存变更和持久化提交放入统一有序队列，保留 UI 即时反馈，同时记录写入结果。复测重点是连续修改同一设置、马上退出及磁盘写入失败。

**11 · “实际上送数”并非实际网络上传的观测值**

位置：[uploaded 固定为 true](D:/project/jev-block-android-ad/core/pipeline/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/pipeline/MessagePipeline.kt:59)、[请求前失败](D:/project/jev-block-android-ad/core/decision/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/decision/TypeSafeBackend.kt:26)。

只要调用 `backend.decide()` 并正常返回，流水线就标记 `uploaded=true`。但分类配置校验失败会在发送请求前返回 `NOT_CONFIGURED`，该事件仍被统计为上传；网络层尚未发送正文就失败时，流水线也没有足够信息区分。重试次数同样无法通过这个事件数推断。

建议由后端/传输层返回具有明确语义的发送状态，或者将指标命名为“后端判断尝试数”。不要用该值单独证明验证码没有外发。

**12 · 正文非空的去重忽略标题变化**

位置：[去重内容选择](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/RecentPostCache.kt:49)。

正文非空时 discriminator 只包含正文，标题只在正文为空时参与。相同 key 在 5 秒内正文不变而标题改变，会被视为重复而跳过采集/判断，即使标题是实际变化的消息内容。其滑动时间窗还可能在持续更新时延长遗漏。

复现：同 key 先发 `title="订单A"`、`body="状态已更新"`，随后改为 `title="订单B"`、同正文。建议将与分类有关的标题和正文共同作为内容比较依据，同时为通知移除后的新投递区分生命周期。

**13 · 发布单测门禁没有覆盖核心模块**

位置：[发布测试命令](D:/project/jev-block-android-ad/.github/workflows/release.yml:118)、[核心测试配置](D:/project/jev-block-android-ad/core/decision/build.gradle.kts:14)。

发布流程仅调用 `:app:testDebugUnitTest`。该模块的测试文件主要针对代理校验与指纹；`core:decision`、`core:model`、`core:fingerprint` 的测试位于各自的 commonTest，`core:data` 也有独立测试，并不会因为 app 引用了这些项目就自动作为 app 单测执行。没有发现覆盖 OTP、流水线、调度器、短信通知桥和通知撤销竞态的测试文件。

建议在配置好工具链后查看实际任务图，显式运行各核心模块 host/unit test 与 Android Lint，并补充上述关键路径测试。具体 KMP host test 任务名应以该项目的 Gradle 任务列表为准，本次未能运行确认。发布 workflow 目前也只由 tag 触发，PR 阶段没有相应门禁。

**另需明确接受或改善的设计风险**

- **敏感数据备份**：API key、代理凭据以明文保存在 Preferences DataStore；完整消息（包括本地放行的 OTP）在判断前就入库。`allowBackup=true`，没有排除凭据、数据库与运行日志的备份规则，数据可能进入系统备份或设备迁移。README 已承认凭据明文，这是现有设计取舍，不能等同于已发生泄漏。建议明确保留范围并排除敏感数据，依据：[Android Auto Backup](https://developer.android.com/identity/data/autobackup)。位置：[Manifest](D:/project/jev-block-android-ad/app/src/main/AndroidManifest.xml:27)。
- **升级清空历史**：数据库 version=2、`exportSchema=false`，使用 destructive migration；版本变化且没有迁移路径时会丢失审计记录和渠道统计。建议提供迁移与 schema 导出。位置：[数据库装配](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/DatabaseModule.kt:20)。
- **长期运行与突发请求**：事件表没有自动保留期限；延迟统计每次读取并排序全部历史记录。通知侧为不同内容无限启动任务，没有应用层并发/队列上限，弱网或通知突发时可能积压、触发限流。建议增加数据保留策略、分页/有界统计和请求并发控制。位置：[统计查询](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/ObservedEvent.kt:177)、[通知异步入口](D:/project/jev-block-android-ad/feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/NoiseGateNotificationListener.kt:142)。
- **非有限门槛值**：门槛解析用 `toFloatOrNull()?.coerceIn(...)`，未排除 `NaN`；比较器只验证模型 confidence 的有限性，未验证配置门槛。异常存储输入可能绕过门槛比较，也可能使设置页 `roundToInt()` 抛异常。UI 滑块正常输入不会产生该值，优先级低于直接可触发的问题。位置：[配置解析](D:/project/jev-block-android-ad/core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/settings/SettingsRepository.kt:330)。

**建议修复顺序**

先完成 01–04 的监听/隐私/误撤销修复，再处理 05–08 的启动、配置和短信生命周期可靠性；随后统一隔离功能语义、设置持久化与诊断指标。修复对应路径时补充行为测试，再把核心模块测试接入发布与 PR 检查。

系统能力边界仍应保留：通知监听回调发生在通知投递之后，取消通知不能撤回已发生的声音/震动；应用不是默认短信 App，短信本体保留在收件箱。这些已在项目文档明确说明，本次不将其列为实现缺陷。
