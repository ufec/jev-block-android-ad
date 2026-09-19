package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.Action
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 用户覆盖值合并到出厂分类。
 *
 * 这里有两条容易写错、且错了不会报错的规则：
 * 脏数据（非法动作名、越界门槛）必须**静默退回出厂值**而不是让判断链抛异常；
 * 覆盖只能碰动作与门槛两个字段，其余字段原样保留。
 */
class CategoryOverridesTest {

    private val defaults = DefaultCategories.all()

    @Test
    fun `没有任何覆盖时逐条等于出厂配置`() {
        assertEquals(defaults, applyCategoryOverrides(defaults))
    }

    @Test
    fun `门槛覆盖只影响指定的类别`() {
        val applied = applyCategoryOverrides(defaults, thresholds = mapOf("广告" to 0.5f))

        assertEquals(0.5f, applied.first { it.name == "广告" }.minConfidence)
        // 没被点名的类别必须保持出厂值，否则用户调一个滑块会顺带改掉另一个。
        assertEquals(
            defaults.first { it.name == "诈骗" }.minConfidence,
            applied.first { it.name == "诈骗" }.minConfidence,
        )
    }

    @Test
    fun `动作覆盖生效`() {
        val applied = applyCategoryOverrides(defaults, actions = mapOf("广告" to "SILENT_SUPPRESS"))

        assertEquals(Action.SILENT_SUPPRESS, applied.first { it.name == "广告" }.action)
    }

    @Test
    fun `非法动作名退回出厂动作而不是抛异常`() {
        // 存储层不校验枚举名（它不认识 Action），所以读取侧必须兜住。
        // 抛异常意味着"存储里有个错别字"会变成"所有通知都不再被处理"。
        val applied = applyCategoryOverrides(defaults, actions = mapOf("广告" to "NOT_AN_ACTION"))

        assertEquals(Action.QUARANTINE, applied.first { it.name == "广告" }.action)
    }

    @Test
    fun `越界的门槛被夹到合法区间`() {
        val applied = applyCategoryOverrides(
            defaults,
            thresholds = mapOf("广告" to 5f, "诈骗" to -1f),
        )

        assertEquals(1f, applied.first { it.name == "广告" }.minConfidence)
        assertEquals(0f, applied.first { it.name == "诈骗" }.minConfidence)
    }

    @Test
    fun `不认识的名字不会新增类别`() {
        // 答案空间由出厂配置唯一定义，覆盖不能凭空造出第五个类别 ——
        // 那会同时糊掉 criteria 与 validate 的前提。
        val applied = applyCategoryOverrides(
            defaults,
            thresholds = mapOf("不存在的类别" to 0.5f),
            actions = mapOf("不存在的类别" to "SILENT_SUPPRESS"),
        )

        assertEquals(defaults.size, applied.size)
        assertEquals(defaults.map { it.name }, applied.map { it.name })
    }

    @Test
    fun `覆盖不动描述与关键词`() {
        // 这两个字段构成 criteria（模型看到的答案空间），
        // 被覆盖改写会让"界面上调门槛"意外改变模型收到的提示词。
        val applied = applyCategoryOverrides(
            defaults,
            thresholds = mapOf("广告" to 0.5f),
            actions = mapOf("广告" to "SILENT_SUPPRESS"),
        )

        val before = defaults.first { it.name == "广告" }
        val after = applied.first { it.name == "广告" }
        assertEquals(before.description, after.description)
        assertEquals(before.keywords, after.keywords)
    }
}
