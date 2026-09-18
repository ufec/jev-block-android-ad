package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.CategoryConfig
import me.ethanxu.jevnoisegate.sdk.typesafe.ChoiceQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuestionFactoryTest {

    // -----------------------------------------------------------------------
    // 配置即答案空间
    // -----------------------------------------------------------------------

    @Test
    fun `category names become the choice criteria keys`() {
        // 这是「配置即答案空间」的核心断言：用户改配置就等于改答案空间，
        // 不需要动任何代码。
        val question = QuestionFactory.categoryQuestion(Fixtures.categories())
        val criteria = (question.question as ChoiceQuestion).criteria

        assertEquals(listOf("验证码", "广告", "正常"), criteria.keys.toList())
        assertEquals(QuestionFactory.CATEGORY_QUESTION_ID, question.name)
    }

    @Test
    fun `adding a category only adds an option`() {
        val extra = Fixtures.categories() + CategoryConfig(
            name = "账单",
            description = "信用卡与账单提醒",
            action = Action.ALLOW,
            minConfidence = 0f,
        )
        val criteria = (QuestionFactory.categoryQuestion(extra).question as ChoiceQuestion).criteria
        assertTrue("账单" in criteria)
        assertEquals(4, criteria.size)
    }

    // -----------------------------------------------------------------------
    // 提示词渲染
    // -----------------------------------------------------------------------

    @Test
    fun `keywords are rendered into the option rubric`() {
        val rendered = QuestionFactory.renderCriteria(Fixtures.categories()[1])
        assertEquals("推销商品与活动的营销信息（典型用词：优惠、促销）", rendered)
    }

    @Test
    fun `description alone is used when there are no keywords`() {
        assertEquals("用户预期内的信息", QuestionFactory.renderCriteria(Fixtures.categories()[2]))
    }

    @Test
    fun `rendered criteria are actually sent to the model`() {
        val criteria = (QuestionFactory.categoryQuestion(Fixtures.categories()).question as ChoiceQuestion)
            .criteria
        val adCriteria = criteria.getValue("广告")
        // criteria 的值是 JsonElement，最终会被序列化进请求体
        assertTrue(adCriteria.toString().contains("优惠"))
    }

    // -----------------------------------------------------------------------
    // 校验：结构约束
    // -----------------------------------------------------------------------

    @Test
    fun `empty category list is rejected`() {
        assertFailsWith<IllegalArgumentException> { QuestionFactory.categoryQuestion(emptyList()) }
    }

    @Test
    fun `blank category name is rejected`() {
        val bad = listOf(
            CategoryConfig("  ", "描述", emptyList(), Action.ALLOW, 0f),
            Fixtures.categories()[2],
        )
        assertFailsWith<IllegalArgumentException> { QuestionFactory.categoryQuestion(bad) }
    }

    @Test
    fun `duplicate category names are rejected`() {
        // 重名会在 criteria 这个 map 里静默塌缩，导致答案空间与配置不一致 ——
        // 这种错必须早点炸出来。
        val duplicated = Fixtures.categories() + CategoryConfig(
            name = "广告",
            description = "另一个广告定义",
            action = Action.ALLOW,
            minConfidence = 0f,
        )
        assertFailsWith<IllegalArgumentException> { QuestionFactory.categoryQuestion(duplicated) }
    }

    // -----------------------------------------------------------------------
    // 校验：安全护栏
    // -----------------------------------------------------------------------

    @Test
    fun `config without any ALLOW category is rejected`() {
        // 没有放行类别意味着所有消息都可能被吃掉，且用户无从恢复。
        // 这是一条硬护栏，不允许用户把自己配进这个状态。
        val allSuppressing = listOf(
            CategoryConfig("广告", "营销信息", emptyList(), Action.QUARANTINE, 0.9f),
            CategoryConfig("诈骗", "诈骗信息", emptyList(), Action.SILENT_SUPPRESS, 0.7f),
        )
        assertFailsWith<IllegalArgumentException> { QuestionFactory.categoryQuestion(allSuppressing) }
    }

    @Test
    fun `default categories satisfy every constraint`() {
        // 出厂配置必须自身合法，否则应用首次启动就会崩。
        val question = QuestionFactory.categoryQuestion(DefaultCategories.all())
        assertEquals(4, (question.question as ChoiceQuestion).criteria.size)
    }

    @Test
    fun `default config keeps the otp category at zero threshold`() {
        val otp = DefaultCategories.all().first { it.name == "验证码" }
        assertEquals(Action.ALLOW, otp.action)
        assertEquals(0f, otp.minConfidence)
    }
}
