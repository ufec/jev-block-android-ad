# 本应用的 R8 规则。
#
# ## 原则：能不写就不写
#
# 大多数依赖自带 consumer rules（打在 AAR/jar 的 META-INF/proguard 里），由 AGP 自动合并，
# 不需要在这里重复。手写一份"看起来齐全"的 keep 清单有两个实际害处：
#   1. 它会把本来可以裁掉的代码钉住，直接抵消开 R8 的意义；
#   2. 它掩盖真正的依赖关系 —— 哪天某个库不再需要那条规则，也没人会去删。
#
# 所以这里只放两类东西：
#   - 本应用自己的代码里，R8 静态分析看不出来的反射/入口；
#   - 依赖中确实缺失、且已在真机上复现过问题的规则。
# 每一条都必须写清"为什么"，否则下一个人不敢删也不敢留。
#
# ## 当前结论：一条都不需要（2026-09-19 实测）
#
# 开 R8 + 资源裁剪后，签名装机跑过这几条链路，全部正常：
#   - 冷启动 → Hilt 注入（Application 与 NotificationListenerService 都注入了）
#   - 发通知 → 采集 → 判断 → 撤销：`action=QUARANTINE cat=广告 conf=1.0 latency=1402ms`
#     （这一段会经过 Ktor + OkHttp + kotlinx.serialization + TypeSafe SDK）
#   - 事件页 → Room 查询（事件计数、延迟统计）
#   - 代理页保存 → DataStore 写入
# 也就是说 Hilt / Room / OkHttp / Ktor / androidx 各自带的规则已经够用。
#
# ## 关于行号：不要在这里加 `-keepattributes LineNumberTable`
#
# 网上常见的 `-keepattributes SourceFile,LineNumberTable` 在这个工程里**没有可观测效果**。
# 三种测法都验不出来：加了之后最终生效的配置里确实能看到这一条
# （`app/build/outputs/mapping/release/configuration.txt`），但产物体积没变、
# 反汇编出来的行号信息也没变（有/无规则两次构建，`dexdump` 得到的行号情况一致）。
# R8 输出的是 DEX，行号走 debug_info 段，不受这个 class 文件属性的控制 ——
# 那条规则针对的是 class 文件输出，属于抄过来的。加上它只会让人误以为行号已保住。
#
# 实际后果要知道：**release 的崩溃栈只有类名 + 方法名，没有行号。**
# 请为每个 release 存档 `app/build/outputs/mapping/release/mapping.txt`，
# 用 retrace 还原到方法级；要定位到行，靠 AppLog（关键动作都记下来了）。
#
# ## 如果以后有东西被裁坏了
#
# 先看 `app/build/outputs/mapping/release/usage.txt`（被移除的代码）与 `seeds.txt`（被保留的入口），
# 确认后要加规则时写成下面这种带范围的形状，别用裸 `-keep class **`，那等于关掉裁剪：
#
#     # 为什么：<哪个类、被谁反射调用、不保留会怎样>
#     -keep class me.ethanxu.jevnoisegate.<某处>.SomeClass { <具体成员>; }
