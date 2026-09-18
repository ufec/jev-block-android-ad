package me.ethanxu.jevnoisegate.sdk.typesafe

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * 响应解析的安全取值工具。
 *
 * 上游是 TypeScript，反序列化后的对象字段访问没有运行期校验（字段缺失会得到 undefined 并静默传播）。
 * Kotlin 这边选择在解析点就把结构问题暴露成 TypeSafeException —— 因为本项目的调用方是
 * 自动化流水线，静默的 undefined 会变成"分类结果为空"这种更难排查的故障。
 */

internal fun JsonObject.requireDouble(key: String): Double {
    val primitive = this[key] as? JsonPrimitive
        ?: throw TypeSafeException("Response field \"$key\" is missing or not a number.")
    return primitive.doubleOrNull
        ?: throw TypeSafeException("Response field \"$key\" is not a number: ${primitive.content}")
}

internal fun JsonObject.requireString(key: String): String {
    val primitive = this[key] as? JsonPrimitive
        ?: throw TypeSafeException("Response field \"$key\" is missing or not a string.")
    if (!primitive.isString) {
        throw TypeSafeException("Response field \"$key\" is not a string: ${primitive.content}")
    }
    return primitive.content
}

internal fun JsonObject.requireDoubleMap(key: String): Map<String, Double> {
    val obj = this[key] as? JsonObject
        ?: throw TypeSafeException("Response field \"$key\" is missing or not an object.")
    return obj.mapValues { (entryKey, value) ->
        (value as? JsonPrimitive)?.doubleOrNull
            ?: throw TypeSafeException("Response field \"$key.$entryKey\" is not a number.")
    }
}

internal fun JsonObject.requireStringMap(key: String): Map<String, String> {
    val obj = this[key] as? JsonObject
        ?: throw TypeSafeException("Response field \"$key\" is missing or not an object.")
    return obj.mapValues { (entryKey, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw TypeSafeException("Response field \"$key.$entryKey\" is not a string.")
    }
}

/** 宽松取值：字段缺失或类型不符都返回 null。用于可选字段。 */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.toInt()

internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

/** 把任意 JSON 值转成可读文本，用于把 state 塞进日志。 */
internal fun JsonElement.describeForLog(maxLength: Int = 200): String {
    val text = toString()
    return if (text.length > maxLength) text.take(maxLength) + "…" else text
}
