package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.EventSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 把 [DecisionInput] 组装成喂给模型的 state。
 *
 * 上游文档明确说 `state` 可以是结构化对象，并建议把判断所需的上下文一并放进去 ——
 * 这正好解决了纯文本分类的短板：只给一条正文，模型无从得知"这个渠道历史上 90% 都是广告"。
 */
object StateBuilder {

    const val SOURCE_SMS: String = "sms"
    const val SOURCE_NOTIFICATION: String = "notification"

    fun build(input: DecisionInput): JsonObject {
        val event = input.event
        val labels = input.labels
        val context = input.context

        return buildJsonObject {
            put(
                "source",
                JsonPrimitive(
                    when (event.source) {
                        EventSource.SMS -> SOURCE_SMS
                        EventSource.NOTIFICATION -> SOURCE_NOTIFICATION
                    },
                ),
            )
            // App 与渠道的显示名比包名/渠道 id 更有信息量；拿不到时退回标识符本身。
            put("app", JsonPrimitive(labels.appName ?: event.packageName))
            put("package", JsonPrimitive(event.packageName))
            put("channel", labels.channelName?.let(::JsonPrimitive) ?: JsonNull)
            put("sender", event.senderKey?.let(::JsonPrimitive) ?: JsonNull)
            put("title", event.title?.let(::JsonPrimitive) ?: JsonNull)
            put("body", JsonPrimitive(event.body))

            // 仅在短信场景提供：通知的 senderKey 是包名+groupKey，不是联系人，放进去会误导模型。
            if (event.source == EventSource.SMS) {
                put("senderIsContact", JsonPrimitive(labels.senderIsContact))
            }

            put(
                "history",
                buildJsonObject {
                    put("seen", JsonPrimitive(context.totalSeen))
                    put("suppressed", JsonPrimitive(context.suppressedCount))
                    put("restored", JsonPrimitive(context.restoredCount))
                    // 样本为 0 时 noiseRatio 是 0 —— 那是"无信息"而不是"很干净"。
                    // 一并给出 seen 让模型自行区分这两种情况。
                    put("noiseRatio", JsonPrimitive(context.noiseRatio))
                },
            )

            // 用户偏好。为空时整个字段不出现 —— 与其给模型一个空对象，
            // 不如让它完全看不到这个维度，避免它把"没有规则"误读成"用户不在意"。
            if (!input.userRules.isEmpty) {
                put(
                    "userRules",
                    buildJsonObject {
                        val allow = input.userRules.preferAllow
                        if (allow.isNotEmpty()) {
                            put(
                                "preferAllow",
                                JsonArray(allow.map(::JsonPrimitive)),
                            )
                        }
                        val block = input.userRules.preferBlock
                        if (block.isNotEmpty()) {
                            put(
                                "preferBlock",
                                JsonArray(block.map(::JsonPrimitive)),
                            )
                        }
                    },
                )
            }
        }
    }
}
