package me.ethanxu.jevnoisegate.feature.notification

import android.content.pm.PackageManager
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.CoroutineScope

/**
 * 解析 App 显示名与通知渠道显示名。
 *
 * 两者都做内存缓存：这些查询会走跨进程调用，而 `onNotificationPosted` 在系统的时间预算内执行。
 *
 * 关于渠道名：`NotificationListenerService` **没有** `getNotificationChannel(pkg, uid, id)`
 * 这样的单查接口，只有 [NotificationListenerService.getNotificationChannels]（按包返回整表）。
 * 因此按包缓存 `channelId → name` 映射，一个包一次 binder 调用。
 * 普通 `NotificationManager` 只能读到本 App 自己的渠道。
 */
internal class LabelResolver(private val service: NotificationListenerService, scope: CoroutineScope) {

    private val appLabelCache = AsyncLookupCache<String, String>(scope, load = ::lookupAppLabel)

    /** 兜底用的全量映射：直接查单个包失败时（包可见性受限）从这里找。 */
    private val installedLabelCache = AsyncLookupCache<Unit, Map<String, String>>(scope) {
        loadInstalledLabels().takeIf { it.isNotEmpty() }
    }

    private val channelNamesCache = AsyncLookupCache<Pair<String, Int>, Map<String, String>>(scope) { (pkg, uid) ->
        loadChannelNames(pkg, uid).takeIf { it.isNotEmpty() }
    }

    suspend fun appLabel(packageName: String): String? = appLabelCache.get(packageName)

    private suspend fun lookupAppLabel(packageName: String): String? {
        val pm = service.packageManager

        runCatching {
            val info = pm.getApplicationInfo(packageName, 0)
            return pm.getApplicationLabel(info).toString()
        }.onFailure { error ->
            Log.d(
                TAG,
                "直接查询 App 名失败: $packageName (${error.javaClass.simpleName}: ${error.message})",
            )
        }

        // 兜底：从全量列表里找。Android 11+ 的包可见性限制会让 getApplicationInfo
        // 对"未交互过"的第三方应用抛 NameNotFoundException，而 getInstalledApplications
        // 在 QUERY_ALL_PACKAGES 生效时仍能返回完整列表。
        val installed = installedLabelCache.get(Unit).orEmpty()
        installed[packageName]?.let { return it }

        if (installed.isEmpty()) {
            Log.d(TAG, "全量应用列表为空 —— QUERY_ALL_PACKAGES 可能未生效")
        }
        return null
    }

    private fun loadInstalledLabels(): Map<String, String> = runCatching {
        val pm = service.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        Log.d(TAG, "已加载 ${apps.size} 个应用的标签")
        apps.associate { info -> info.packageName to pm.getApplicationLabel(info).toString() }
    }.getOrElse { error ->
        Log.w(TAG, "加载全量应用列表失败: ${error.message}", error)
        emptyMap()
    }

    suspend fun channelLabel(packageName: String, uid: Int, channelId: String?): String? {
        if (channelId == null) return null
        return channelNamesCache.get(packageName to uid)?.get(channelId)
    }

    /**
     * 诊断用：当前能看到的全部应用数量。
     *
     * 存在的意义是把"包可见性到底有没有生效"变成一个可测量的数字。
     * 若数量只有几十（仅系统包），说明 ROM 在 AOSP 的 QUERY_ALL_PACKAGES 之上
     * 又加了一层"获取应用列表"开关，解析第三方 App 名就必然失败。
     */
    suspend fun visibleAppCount(): Int = installedLabelCache.get(Unit).orEmpty().size

    /** 诊断用：可见列表里的第三方包样本，用于判断可见性的性质。 */
    suspend fun visibleAppSamples(limit: Int): List<String> = installedLabelCache.get(Unit).orEmpty().keys
        .filterNot { it.startsWith("android") || it.startsWith("com.android") }
        .take(limit)

    private fun loadChannelNames(packageName: String, uid: Int): Map<String, String> =
        runCatching {
            service.getNotificationChannels(packageName, UserHandle.getUserHandleForUid(uid))
                .associate { channel -> channel.id to channel.name?.toString().orEmpty() }
                .filterValues { it.isNotBlank() }
        }.getOrElse { error ->
            Log.d(
                TAG,
                "无法解析渠道表: $packageName (${error.javaClass.simpleName}: ${error.message})",
            )
            emptyMap()
        }

    private companion object {
        const val TAG = "JevNoiseGate/Labels"
    }
}
