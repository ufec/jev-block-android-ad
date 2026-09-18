package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.ChannelContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateBuilderTest {

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.content.toInt()
    private fun JsonObject.num(key: String): Float = getValue(key).jsonPrimitive.content.toFloat()

    @Test
    fun `notification state carries the identifying fields`() {
        val state = StateBuilder.build(
            Fixtures.input(labels = EventLabels(appName = "淘宝", channelName = "营销活动")),
        )

        assertEquals("notification", state.str("source"))
        assertEquals("com.taobao.taobao", state.str("package"))
        assertEquals("【淘宝】限时优惠，全场五折", state.str("body"))
        assertEquals("淘宝", state.str("title"))
        // channel 用的是可读名而非 channelId —— channelId 往往是 channel_promo_1
        // 这类机器标识，对模型没有信息量。
        assertEquals("营销活动", state.str("channel"))
    }

    @Test
    fun `sms state carries the sender`() {
        val state = StateBuilder.build(Fixtures.input(event = Fixtures.sms()))
        assertEquals("sms", state.str("source"))
        assertEquals("+8613800000000", state.str("sender"))
    }

    @Test
    fun `senderIsContact is omitted for notifications`() {
        // 通知的 senderKey 是包名+groupKey，不是联系人。把它当作"是否通讯录联系人"
        // 上报会让模型误判，因此只在短信场景提供该字段。
        val notification = StateBuilder.build(Fixtures.input())
        assertFalse("senderIsContact" in notification)

        val sms = StateBuilder.build(Fixtures.input(event = Fixtures.sms()))
        assertTrue("senderIsContact" in sms)
    }

    @Test
    fun `senderIsContact reflects the label`() {
        val state = StateBuilder.build(
            Fixtures.input(event = Fixtures.sms(), labels = EventLabels(senderIsContact = true)),
        )
        assertEquals("true", state.str("senderIsContact"))
    }

    @Test
    fun `appName falls back to the package name`() {
        val withoutLabel = StateBuilder.build(Fixtures.input())
        assertEquals("com.taobao.taobao", withoutLabel.str("app"))

        val withLabel = StateBuilder.build(Fixtures.input(labels = EventLabels(appName = "淘宝")))
        assertEquals("淘宝", withLabel.str("app"))
    }

    @Test
    fun `channelName falls back to null rather than the opaque id`() {
        // channelId 往往是 channel_promo_1 这类机器标识，没有可读标签时宁可不给，
        // 也不要让模型去猜一个无意义的字符串。
        val state = StateBuilder.build(Fixtures.input())
        assertEquals(JsonNull, state.getValue("channel"))
    }

    @Test
    fun `history carries the behavioural context`() {
        // 这是纯文本分类拿不到的信号：同一个渠道历史上 90% 都是噪音。
        val state = StateBuilder.build(Fixtures.input())
        val history = state.getValue("history").jsonObject

        assertEquals(30, history.int("seen"))
        assertEquals(27, history.int("suppressed"))
        assertEquals(0, history.int("restored"))
        assertTrue(history.num("noiseRatio") > 0.89f && history.num("noiseRatio") < 0.91f)
    }

    @Test
    fun `empty history is reported with zero seen so the model can tell it apart from clean`() {
        // noiseRatio 为 0 有两种含义："从没见过"和"见过但都很干净"。
        // 同时给出 seen，让模型自己能区分。
        val state = StateBuilder.build(
            Fixtures.input(context = ChannelContext("com.taobao.taobao", "marketing")),
        )
        val history = state.getValue("history").jsonObject
        assertEquals(0, history.int("seen"))
        assertEquals(0f, history.num("noiseRatio"))
    }

    @Test
    fun `state is structured with nested context not a flat string`() {
        // 上游文档明确 state 可以是结构化对象，这正是我们能把渠道统计塞进去的前提。
        // 断言嵌套结构确实存在，而不只是"body 是个字符串"。
        val state = StateBuilder.build(Fixtures.input())

        assertTrue(state.getValue("body") is JsonPrimitive)
        val history = state.getValue("history")
        assertTrue(history is JsonObject)
        assertTrue(history.jsonObject.getValue("seen") is JsonPrimitive)
    }
}
