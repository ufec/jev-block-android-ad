package me.ethanxu.jevnoisegate.app

import me.ethanxu.jevnoisegate.core.data.settings.AppPreferences

/**
 * 代理链路的状态，供界面展示。
 *
 * 集中在一个函数里算出来，是因为这个状态有**四个**分支（直连 / 配置不完整 /
 * 已配置未验证 / 已验证），而它要在设置页入口、JevAPI 页的只读描述、代理页的
 * 状态区三处出现。三处各自写一个 `when` 的话，加上"已验证"这一维之后必然会出现
 * 同一个状态在两页说法不一致 —— 那种不一致比文案不漂亮难查得多。
 *
 * @property summary 一行摘要，给设置页入口的副标题。**一律以生效状态开头** ——
 *   "走代理还是直连"是这一行要回答的问题，配置内容不是。早先写成
 *   `HTTP 127.0.0.1:8888 · 未验证 · 按直连处理`，虽然字面上也说了未生效，
 *   但一眼扫过去先看到的是端点，读出来就是"配了代理"。端点只在真的生效时才值得占第一位。
 * @property detail 展开说明，给 JevAPI 页的只读链路描述与代理页状态区。
 *   同样先说结论。不重复具体的字段错误 —— 那由「网络代理」页的字段提示与按钮说明负责，
 *   三处都贴同一句话会让一整屏都在重复同一个句子。诊断归代理页，别处只报状态。
 * @property isWarning 摘要是否该以警告色显示。只有"配了但没生效"才值得警告 ——
 *   直连是正常状态，已验证也是。
 */
internal data class ProxyStatus(
    val summary: String,
    val detail: String,
    val isWarning: Boolean,
)

internal fun proxyStatus(prefs: AppPreferences): ProxyStatus {
    if (prefs.proxyType == AppPreferences.PROXY_NONE) {
        return ProxyStatus(summary = "直连", detail = "直连，未经代理", isWarning = false)
    }

    if (!isProxyUsable(prefs)) {
        return ProxyStatus(
            summary = "直连 · 代理配置不完整",
            detail = "当前按直连处理：代理配置不完整（缺主机、缺端口，或地址格式不对）",
            isWarning = true,
        )
    }

    val endpoint = "${prefs.proxyType.uppercase()} ${prefs.proxyHost}:${prefs.proxyPort}"
    val endpointWithAuth = if (prefs.proxyUsername.isNotBlank()) "$endpoint（含认证）" else endpoint

    if (!prefs.isProxyVerified) {
        return ProxyStatus(
            summary = "直连 · 代理未验证",
            // 这条分支在新模型下几乎只可能是"升级前存下的、从没测过的配置" ——
            // 现在配置只能经保存写入，而保存必然先测过。留着它是安全网：
            // 一条没人测过的代理绝不该因为版本升级就自动开始生效。
            detail = "当前按直连处理：$endpointWithAuth 尚未通过测试，重新保存一次即可启用",
            isWarning = true,
        )
    }

    return ProxyStatus(
        summary = "$endpoint · 已验证",
        // 说的是"这组配置通过过测试"，不是"现在一定通"。验证不会过期，代理却可能在
        // 验证之后失效 —— 不把话说过头，用户才不会在代理已经挂掉时反而更困惑。
        detail = "该配置已通过测试：经 $endpointWithAuth 出网；重启应用后对实际判断生效",
        isWarning = false,
    )
}

/**
 * 配置齐全**且**地址格式合法。
 *
 * 不能直接用 [AppPreferences.isProxyConfigured]：那个只判非空，于是 `256.1.1.1`
 * 会被当成"配置好了"。地址格式的校验在应用层（见 `ProxyValidation.kt` 的文件头），
 * 所以这个组合判断也只能在这里做 —— 两处分开写必然有一天会不一致。
 */
internal fun isProxyUsable(prefs: AppPreferences): Boolean =
    prefs.isProxyConfigured && proxyHostError(prefs.proxyHost) == null
