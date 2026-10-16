# 第二轮性能修复 · 2026-10-09

已实施第二轮审查的 10–17 项。工作区仍未提交；数据库升级为 Room schema 5。原问题及修复前证据保留在 [第二轮审查](PERFORMANCE_REVIEW_ROUND2.md)，本页描述当前实现。

## 实现与回归覆盖

| 编号 | 当前实现 | 验证 |
|---|---|---|
| 10 | [URL 扫描器](../core/fingerprint/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/fingerprint/UrlNormalizer.kt) 先识别连续域名字符段，再检查协议、后缀和路径，失败不从每个字符重复尝试。独立字符段正则保留平台的 `\w` 语义。裸金额分支加数字起点边界，避免长数字负例反复回退。完整正文和最后的 40 字符骨架规则保留。 | [URL 测试](../core/fingerprint/src/commonTest/kotlin/me/ethanxu/jevnoisegate/core/fingerprint/UrlNormalizerTest.kt) 对照旧正则的固定案例、300 组随机组合及 65,536 字符输入；原有指纹、金额、日期、OTP 测试通过。 |
| 11 | [事件页](../app/src/main/kotlin/me/ethanxu/jevnoisegate/ui/screen/events/EventsScreen.kt) 只订阅当前视图。普通事件和隔离页均使用 EventSummary；点击显示内容才按 ID 订阅 title/body。共享普通事件流停止超时为 0；[分组](../app/src/main/kotlin/me/ethanxu/jevnoisegate/app/MainViewModel.kt) 在 Default 执行。 | [订阅测试](../app/src/test/kotlin/me/ethanxu/jevnoisegate/app/EventSubscriptionsTest.kt) 验证隔离区无普通列表订阅、两个普通订阅共享一个 DAO 流、最后退出立即取消、详情默认不请求正文。Room 测试核对摘要和选中事件的完整正文。 |
| 12 | 增加 `(decidedBy, timestampMs, latencyMs)` 覆盖索引。MODEL 和 FALLBACK 两个有序分支用 UNION ALL 合并，整体仍按时间取最近 1,000 条，最后按耗时排序。 | 实际查询计划使用覆盖索引和有序合并。Room 测试覆盖混合类别、较旧的 1,100 条模型记录、最近新增 FALLBACK、null 耗时及清空。SQL 探针另覆盖全模型、零模型、稀疏旧模型和近期模型。 |
| 13 | [bucket_history](../core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/BucketHistory.kt) 的 INSERT/UPDATE/DELETE 触发器原子维护总数和拦截数。channelStats 按主键查询计数并扣除当前事件，不随分桶历史长度增长。 | [Room 回归](../core/data/src/test/kotlin/me/ethanxu/jevnoisegate/core/data/EventStoragePerformanceTest.kt) 每步与直接聚合比较，覆盖重复入库、决策重复写回、恢复、分桶变化、年龄清理、容量淘汰、按应用删除和清空；不存在的桶返回 0。 |
| 14 | 撤销桥仍先获得活动候选重试机会；连接/世代检查后，[完成后去重](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/SmsDecisionOwnership.kt) 位于 SMS 所有权查询之前，OWNED 和 FALLBACK 都经过该边界。 | [所有权测试](../feature/notification/src/test/kotlin/me/ethanxu/jevnoisegate/feature/notification/SmsDecisionOwnershipTest.kt) 验证有所有者只查一次、无所有者只等待一次、不同正文继续查询、移除后复用继续处理；已有晚到广播和撤销队列测试通过。 |
| 15 | [批匹配](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/SmsBatchMatcher.kt) 一次解析候选文本和号码；完整正文用哈希索引，截断预览用压缩前缀树。节点数量随候选数量增长，不逐字符分配树节点；遇到两个合法候选立即保守保留。 | [匹配测试](../feature/notification/src/test/kotlin/me/ethanxu/jevnoisegate/feature/notification/SmsBatchMatcherTest.kt) 覆盖精确/截断混合歧义、号码不符、合集、短预览、字面省略号、过期和消费一次，并以 300 组候选对照旧匹配器。继续在同一主线程轮次匹配和撤销，未引入后台旧快照撤销风险。 |
| 16 | [标签缓存](../feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/AsyncLookupCache.kt) 明确缓存 null：失败 TTL 30 秒、成功 TTL 5 分钟；短锁只保护缓存，每个 key 合并后台在途查询。App/渠道并行等待，各最多 500 ms；超时返回空标签，共享查询可以继续填缓存。缓存及在途任务有容量上限。 | [缓存测试](../feature/notification/src/test/kotlin/me/ethanxu/jevnoisegate/feature/notification/AsyncLookupCacheTest.kt) 验证并发与随后 10 次空值命中只加载一次、TTL 后重查、慢冷包不阻挡热包、等待超时不取消共享加载。 |
| 17 | [日志消费者](../core/common/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/common/log/BatchedLogWriter.kt) 空队列无限等待信号，首条待写日志才建立两秒截止时间；保留 100 ms 合并、64 条阈值和 clear 顺序。初始化发布一次已有日志。 | [虚拟时钟测试](../core/common/src/commonTest/kotlin/me/ethanxu/jevnoisegate/core/common/log/BatchedLogWriterTest.kt) 连续一小时空闲没有重复发布；之后小批量仍在两秒内落盘，落盘后再空闲一小时无重复发布；阈值、队列上限和清除顺序测试通过。 |

## 数据库升级

[4 → 5 迁移](../core/data/src/main/kotlin/me/ethanxu/jevnoisegate/core/data/DatabaseModule.kt) 创建索引和计数表，一次聚合回填已有历史并安装触发器；新安装由 Room onCreate 回调安装同样触发器。[迁移测试](../core/data/src/test/kotlin/me/ethanxu/jevnoisegate/core/data/DatabaseMigrationTest.kt) 从旧版 2 完整执行到 5，验证原正文、元数据、计数、恢复后 USER 保护和删除后的计数。迁移的历史聚合只发生一次。

未配置模型的普通消息仍会读取一次恒定成本历史查询；本轮没有改变 NOT_CONFIGURED 审计与 debug 凭据回退语义。

## 桌面探针结果

探针调用实际编译的 Kotlin 方法或实际 DAO SQL；均不代表手机耗时。原 JSON 保留，修复后另存文件。

| 工作 | 修复前 | 当前 |
|---|---:|---:|
| 16,384 字符连续 ASCII 负例完整归一化 | 1,592.1909 ms | 6.9995 ms |
| 同一输入 URL 阶段 | 1,319.1786 ms | 0.8822 ms |
| 10,000 行集中分桶历史查询 | 112,513 VM 步 | 79 VM 步 |
| 10,000 行中 100 条旧模型记录的延迟查询 | 71,118 VM 步 | 2,426 VM 步 |
| 零 MODEL/FALLBACK 的延迟查询 | 原查询遍历历史 | 28 VM 步 |
| 最近 300 条、正文各 16 KiB 的列表正文传输量 | 4,915,200 bytes | 0（摘要结果不含正文） |
| 128 × 128、16K 公共前缀的暖对象短信负例 | 3.0263 ms | 0.0856 ms，仍只获取一次活动快照 |

完整归一化 1K/4K/8K/16K 当前为 0.5294/2.4249/3.2418/6.9995 ms；分别计时的 URL 阶段为 0.1428/0.2425/0.2754/0.8822 ms。JIT、预热和机器负载会影响比例，不据此声称所有输入或手机都有相同倍数收益。

全 MODEL 的延迟查询为 24,039 VM 步、0.8910 ms，原结果为 18,023 步、0.8364 ms；混合稠密模型为 26,026 步、1.0091 ms。新增合并工作有成本，收益主要在稀疏记录下避免扫描整个事件历史。外层耗时排序仍保留。

短信暖探针复用同一批字符串，哈希可能已缓存；补充新建字符串及复制的运行，128 × 128、16K 为 5.8222 ms，包含约 4 MiB 文本的输入构造及首次哈希，不能和旧暖匹配中位数直接比较。活动通知的 Binder 和 Android 内容提取均未包含。摘要的 hasContent 表达式仍可能让 SQLite 内部读取文本，0 bytes 指返回给 Room 的正文负载，不表示磁盘完全不读正文页。

写入和空间也已记录：10,000 行模拟库分配 15,548,416 bytes，前一轮 schema 4 为 15,298,560 bytes，增加约 1.6%；入库加容量维护当前中位数 0.1610 ms，历史 schema 4 暖结果为 0.0994 ms。不同运行的绝对时间仅供成本参考。计数触发器和索引不能视为免费。

构建并行运行时，16K 负例归一化曾为 32.7926 ms，单独运行为 6.9995 ms；[构建负载干扰样本](performance/hot_path_round2_fixed_during_build.json) 也保留，表中 CPU 数值采用构建结束后的单独运行。

原始结果：[CPU 修复前](performance/hot_path_round2_results.json)、[CPU 当前及源码 SHA-256](performance/hot_path_round2_fixed.json)、[SQL 修复前](performance/sql_round2_results.json)、[SQL 当前](performance/sql_round2_fixed.json)、[写入/空间](performance/sql_round2_fixed_write_cost.json)。

## 最终验证与复现

最终串行执行 `:app:assembleDebug :app:assembleRelease verify` 成功，3 分 56 秒，602 个任务（22 执行、580 up-to-date），Release R8 完成。此前完整验证也已通过；最后补充平台 Unicode 空白回归后，重建遇到 Windows JAR 文件占用及并行 Lint/Hilt 生成文件时序问题，改用串行执行完成全部检查。共 30 套、182 项测试，失败/错误/跳过为 0；Lint 0 错误、26 警告。汇总见 [验证 JSON](performance/validation_round2_fixed.json)。原有 OTP、恢复保护、短信群组/歧义、通知世代测试均通过。

```powershell
# 配置 JDK 17 和 Android SDK 后执行；Python 使用标准库。
./gradlew :app:assembleDebug :app:assembleRelease verify :core:fingerprint:bundleAndroidMainClassesToCompileJar :feature:notification:bundleLibCompileToJarDebug --no-daemon --no-parallel --max-workers=1 --console=plain
./docs/performance/run_hot_path_probe.ps1 -SkipBuild
python docs/performance/sql_round2_probe.py > docs/performance/sql_round2_fixed.json
python docs/performance/sql_probe.py > docs/performance/sql_round2_fixed_write_cost.json
```

当前脚本默认 schema 5/修复后输出名。修复前 JSON 是历史快照，当前代码重跑不会重建旧业务实现。没有连接 Android 设备，未测真实帧率、耗电、Binder、存储及取消后的 CPU 时间。同步 Binder 不能靠协程超时强制中断；标签预算限制的是事件等待时间。短信索引、通知内容提取和撤销仍需用 Perfetto 检查实际主线程最长任务。
