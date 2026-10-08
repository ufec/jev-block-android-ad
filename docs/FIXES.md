# 项目问题修复记录 · 2026-10-08

针对 [初始审查](PROJECT_REVIEW.md) 的 13 项问题修复，并在继续复查中补充处理了 4 类遗漏。审查基线为 `0030a78`，修复版本为 0.1.2（versionCode 3）。该版本尚未推送或发布。

## 修复对应表

| 编号 | 修复后的行为 | 主要验证 |
|---|---|---|
| 01 | 通知采集入口先检查 App 监听开关；关闭时取消该 App 的在途任务，执行撤销前再次检查 | NotificationPolicyTest：开发者模式也不能绕过监听过滤 |
| 02 | OTP 检查标题和正文的合并文本，覆盖分字段、中文连续数字和常见英文/字母数字代码 | MessagePipelineTest：多个 OTP 样本后端收到的请求列表为空 |
| 03 | 所有入口共享 OTP → 用户恢复规则 → 短信黑名单 → 模型的顺序 | 黑名单号码的 OTP 放行；普通黑名单短信本地拦截；用户恢复优先于黑名单 |
| 04 | 每个通知 key 跟踪处理版本，旧结果不能撤销更新或重新发布后的通知；相同文本的持续刷新保留在途判定 | NotificationPolicyTest：旧版本失效、相同待处理文本不替换、标题变化替换 |
| 05 | 设置初始化在协程中等待，读盘及旧凭据迁移共用五秒时限；失败释放就绪信号并显示错误 | SettingsRepositoryTest：读取异常、读取卡住、迁移卡住均可恢复后续保存 |
| 06 | Base URL 验证 HTTP(S)、主机、端口，拒绝 URL 内凭据/查询参数/片段；代理测试对配置异常返回失败 | ProxyProbeTest：无效 URL 不抛异常；设置层拒绝无效 URL |
| 07 | 短信待撤销队列最多 128 条、有效期 60 秒；通知发布与监听重连重试；数据库可重建进程丢失的未过期结果 | SmsNotificationSuppressorTest：先决策后发布仍可撤销、只撤销一次、过期结果不重建 |
| 08 | SMS 广播只采集和执行本地规则，八秒预算内持久化唯一 WorkManager 作业；模型及重试由 Worker 执行 | 流水线本地规则测试；Debug/Release 编译及 Hilt Worker 工厂生成核对 |
| 09 | 隔离区分页查看并恢复保存的消息文本；恢复后记录本地允许规则，后续相同完整内容无需模型；静默移除仅保留审计 | 数据库恢复与用户决策防覆盖测试；完整应用构建 |
| 10 | 内存修改与整份设置快照入队原子完成，单个进程级消费者按 FIFO 写盘；调用方取消不会取消已接受的写入 | SettingsRepositoryTest：快速修改、取消调用方、重新读取、写失败后恢复 |
| 11 | 属性和界面改为 backendAttempted / 后端调用尝试，明确不能证明实际上传；数据库兼容保留 uploaded 列 | 本地规则尝试数为 false；等待队列超时未获得请求名额时为 false |
| 12 | 通知去重同时包含标题与正文，通知移除时清除该 key 的去重状态 | 同正文不同标题、移除后重发测试 |
| 13 | 根 verify 运行全部模块的主机/单元测试及包含依赖的 Lint；PR、主分支与 Release 均执行 | 本地执行 verify；发布预热改为真正解析依赖并在失败时返回非零 |

## 一并处理的风险

- API Key、代理用户名和密码通过 Android Keystore 的 AES-GCM 密钥加密存储；旧明文值首次启动迁移。测试覆盖随机 IV、加密往返和篡改拒绝。Android Keystore 硬件/系统实现仍需设备验证。
- 关闭系统备份，并排除云备份和设备转移中的私有文件、数据库、偏好及外部应用文件。Release 的 BuildConfig 不嵌入本机开发者 API Key，Debug 才允许 local.properties 的回退值。
- Room 2 → 3 显式迁移保留事件历史，导出 schema，移除破坏式迁移。数据库测试保留旧正文/结果/调用标记，恢复规则在事件清理后仍存在。没有可验证的历史版本 1 schema，当前只提供基线版本 2 的升级路径。
- 每次采集清理超过 30 天的事件，最多保留 10,000 条；隔离区可分页查看，延迟统计只读最近 1,000 次决策。用户恢复规则独立保存，不随事件清理丢失。
- 模型调用最多四个并发，总预算 15 秒包含等待与重试；通知在途任务最多 64 个。超时、过载、异常保持放行，外部取消正常传播。测试覆盖并发上限与排队超时。
- 过滤 NaN/Infinity 门槛，拒绝无效置信度；设置保存后新模型请求使用当前配置，无需重启。
- 修复检查暴露的通知权限异常处理；短信仅申请 RECEIVE_SMS，声明电话硬件为可选，兼容无电话硬件设备。

## 继续复查的补充修复

| 编号 | 触发条件与问题 | 修复与验证 |
|---|---|---|
| 14 | 首次读取设置失败，内存暂为默认值；随后保存主题等无关字段，整份快照会覆盖磁盘中尚未读出的 API Key 和监听过滤等配置 | 保存前串行重读真实配置，再应用当前修改。再次读取失败时拒绝保存并显示原因；恢复后并发修改不会互相覆盖，日志等级与当前设置保持一致。SettingsRepositoryTest 新增旧凭据/监听过滤保留、持续读取失败保护、并发恢复和日志等级测试。前两项测试在修复前均失败。 |
| 15 | 通知监听断开时遍历 jobs 并取消；Main.immediate 可立即执行 finally 删除当前 entry，造成 ConcurrentModificationException。断开后残留去重缓存还会跳过被中断消息的重投 | 先复制任务列表，清空映射、失效版本和去重缓存，再逐一取消。NotificationLifecycleTest 在 Robolectric 中调用真实断开处理，验证三个自行删除 entry 的任务全部取消，以及相同内容可重投；异常场景在修复前复现。 |
| 16 | SMS 已插入数据库，但本地决策或安排 WorkManager 失败/取消；旧逻辑在重投发现主键冲突后直接跳过，记录可能一直无结果 | 未完成记录允许继续执行本地判定和安排唯一任务；已完成记录仍跳过。SmsIngestionTest 覆盖安排失败、广播取消后的重投恢复、已完成用户记录不重复判断，以及本地放行/拦截不安排模型任务。 |
| 17 | 相同短信的旧数据库记录覆盖新队列项，缩短新决策的有效期；旧回调完成时也可能删除回调期间接受的新决策 | 保留两者较晚的期限，刷新队列顺序；仅删除回调对应的同一队列项，并在每次撤销前复查是否过期/仍为当前项。测试使用可控时钟覆盖旧记录重放、回调期间刷新、处理前过期和最多 128 条队列，无需真实等待。 |

修复前验证：新增的两个设置保护测试和监听断开测试失败；修复后的专项检查通过。最终全模块验证结果见下节。
## 验证结果

已执行并通过：

```powershell
.\gradlew.bat verify :app:assembleDebug :app:assembleRelease --console=plain
```

- Gradle：`BUILD SUCCESSFUL`，退出码 0，3 分 16 秒；Release 包含 R8 压缩与资源裁剪。
- 20 个测试套件、148 项测试，失败/错误/跳过均为 0。覆盖全部有测试的模块；无测试源的模块由 verify 纳入检查但不会产生测试数。
- Lint：0 errors、26 warnings。剩余为依赖更新提示、旧 SDK 条件和图标外观/重复资源提示，未通过关闭检查或建立 baseline 隐藏错误。
- 核对 Release 生成的 Hilt 注入图包含 SmsDecisionWorker 工厂；合并清单去除了 WorkManagerInitializer，应用通过 Configuration.Provider 提供该工厂。
- 核对 Release BuildConfig 的 TYPESAFE_API_KEY 为空，APK 元数据为 0.1.2 / versionCode 3；清单仅申请 RECEIVE_SMS，电话硬件可选，备份规则已合并。
- `git diff --check` 无空白错误。

产物（路径相对仓库）：

- `app/build/outputs/apk/debug/app-debug.apk`：Debug 签名的测试包。
- `app/build/outputs/apk/release/app-release-unsigned.apk`：Release 未签名包，尚未签名或发布。
- `app/build/reports/lint-results-debug.html`：Lint 明细。
- `app/build/outputs/mapping/release/mapping.txt`：Release 反混淆映射。

## 设备验证边界

本轮执行 adb devices -l，未发现连接的设备；没有运行模拟器或安装 APK 进行设备验证。真实短信广播、Android Keystore、通知权限交互、WorkManager 在 ROM 后台限制下的调度和默认短信应用通知样式，需要安装 APK 后验证。自动检查覆盖源码、主机测试、Robolectric 数据库与通知解析、Lint、编译、Hilt 装配及打包，不能替代上述设备验证。

OTP 识别仍是启发式，无法保证覆盖所有验证码措辞。短信撤销只移除通知，不删除短信；匹配歧义或多条消息汇总时保留通知。恢复会发布 JevNoiseGate 自己的新通知，保留保存的标题/正文，无法重建原 App 的按钮和 PendingIntent。
