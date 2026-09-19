package me.ethanxu.jevnoisegate.app

/*
 * 代理地址的格式校验。
 *
 * ## 为什么这些函数在这里，而不是在 SDK 里
 *
 * SDK 的 `ProxySpec` 收的是已拆好的 `kind` / `host` / `port`，它没法告诉你
 * "这一栏哪里填错了" —— 界面需要的正是逐字段的原因。而且把校验加进
 * `ProxySpec` 的构造，等于让一个已发布的库抛新异常，属于行为变更，
 * 得为它单独发一版。所以校验留在应用层，SDK 保持纯数据。
 *
 * ## 判错的方向是不对称的，所以一律从宽
 *
 * 把合法地址判成非法，会直接挡住一个本来能用的配置，而且用户没有任何办法绕过；
 * 把非法地址判成合法，最多是连接时失败，有清晰的报错。两者代价差很多，
 * 因此这里只排除**明显不可能成立**的形式，凡是拿不准的一律放行。
 * 最典型的体现是 IPv6：写一个完全正确的正则是出了名的容易出错，这里只做结构检查。
 *
 * ## 不查 DNS
 *
 * 全程不做名字解析。`InetAddress.getByName("不是IP的串")` 会真的发出 DNS 查询，
 * 而 `dead.beef` 这种"每个 label 恰好都是十六进制字母"的域名会被误当成 IP 字面量。
 * 所以判 IP 只看形状，不看能不能解析。
 */

/**
 * 主机栏的格式错误；`null` 表示没有可报的错。
 *
 * **留空不报错** —— 留空的语义是"不用代理"，不是"填错了"。界面用
 * [proxyAddressProblem] 去判断"要用代理但还没填齐"，两者是不同的状态。
 */
internal fun proxyHostError(host: String): String? {
    val value = host.trim()
    if (value.isEmpty()) return null

    if (value.startsWith("[") || value.endsWith("]")) {
        return "IPv6 不用写方括号，直接填 ::1 这样即可"
    }
    if (value.contains("://")) {
        return "这里只填主机，不要带 http:// 这类协议头"
    }
    if (value.any(Char::isWhitespace)) {
        return "主机里不能有空格"
    }

    if (value.contains(':')) {
        // 一个冒号不可能是合法 IPv6（IPv6 至少要两个），所以只可能是
        // 「主机:端口」被整个粘了进来。这样判也顺带避开了"取最后一个冒号右边
        // 当端口"那种写法 —— 它会把 `::1` 误判成端口 1。
        if (value.count { it == ':' } == 1) return "端口请填到端口栏，这里只填主机"
        return if (isIpv6Literal(value)) null else "IPv6 地址格式不正确"
    }

    // 纯数字加点的，意图一定是 IPv4，不能退化成域名去校验 ——
    // "256.1.1.1" 在域名规则下是几个合法 label，会被漏掉。
    if (value.all { it.isDigit() || it == '.' }) {
        return if (isIpv4Literal(value)) {
            null
        } else {
            "IPv4 地址应为四段 0–255 的数字，用点分隔"
        }
    }

    return if (isDomainName(value)) null else "既不是合法的 IP，也不是合法的域名"
}

/**
 * 端口栏的格式错误；`null` 表示没有可报的错。留空同样不报错。
 *
 * 非数字输入在界面上就被过滤掉了，所以这里主要拦范围。
 */
internal fun proxyPortError(portText: String): String? {
    val value = portText.trim()
    if (value.isEmpty()) return null
    val port = value.toIntOrNull() ?: return "端口只能是数字"
    return if (port in 1..65535) null else "端口必须在 1–65535 之间"
}

/**
 * 这组地址能不能用来连；返回不能的原因，`null` 表示可用。
 *
 * 给界面用来禁用测试按钮并说明为什么禁用 —— 按钮突然变灰而没有理由，
 * 比按钮能点但报错更让人困惑。
 */
internal fun proxyAddressProblem(host: String, portText: String): String? = when {
    host.isBlank() -> "请先填写代理主机"
    portText.isBlank() -> "请先填写代理端口"
    else -> proxyHostError(host) ?: proxyPortError(portText)
}

private val IPV4_SHAPE = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/** 含前导零的段（`010.1.1.1`）按十进制解析，放行。 */
private fun isIpv4Literal(value: String): Boolean {
    if (!IPV4_SHAPE.matches(value)) return false
    return value.split('.').all { segment ->
        val octet = segment.toIntOrNull() ?: return false
        octet in 0..255
    }
}

/**
 * IPv6 的结构检查，刻意宽松：字符集合法、冒号够多、`::` 至多一个、
 * 每段不超过 4 位十六进制、段数与压缩写法相符。
 *
 * 不追求完备。像 `1::2::3` 这种会被 `::` 计数拦下，而更冷门的非法形式
 * 宁可放过 —— 理由见文件头。
 */
private fun isIpv6Literal(value: String): Boolean {
    if (!value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
        return false
    }
    if (value.count { it == ':' } < 2) return false

    val firstDoubleColon = value.indexOf("::")
    if (firstDoubleColon >= 0 && value.indexOf("::", firstDoubleColon + 1) >= 0) return false

    // 内嵌 IPv4 的写法（`::ffff:127.0.0.1`）单独校验尾巴那一节。
    val tail = value.substringAfterLast(':')
    val hasEmbeddedIpv4 = tail.contains('.')
    if (hasEmbeddedIpv4 && !isIpv4Literal(tail)) return false

    val groups = value.split(':').filter(String::isNotEmpty)
    val hexGroups = if (hasEmbeddedIpv4) groups.dropLast(1) else groups
    if (hexGroups.any { it.length > 4 }) return false

    val fullLength = if (hasEmbeddedIpv4) 6 else 8
    return if (value.contains("::")) groups.size < fullLength else groups.size == fullLength
}

/** 允许单 label（`localhost`）与结尾的点。用 `isLetterOrDigit` 而非 ASCII 正则，好让中文域名也能过。 */
private fun isDomainName(value: String): Boolean {
    val name = value.removeSuffix(".")
    if (name.isEmpty() || name.length > 253) return false
    return name.split('.').all { label ->
        label.isNotEmpty() &&
            label.length <= 63 &&
            !label.startsWith('-') &&
            !label.endsWith('-') &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }
}
