package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.CategoryConfig
import me.ethanxu.typesafe.sdk.ChoiceAnswer
import me.ethanxu.typesafe.sdk.QuestionId
import me.ethanxu.typesafe.sdk.choice

/**
 * 把用户的分类配置编译成 TypeSafe 问题。
 *
 * 这是**「配置即答案空间」的落地处**：分类名直接成为 `Choice.criteria` 的 key，
 * 因此增加一个分类不需要改任何代码，只多一个选项。
 *
 * 之所以还能顺手承载"提示词工程"：用户的 `keywords` 属于分类级知识
 * （"优惠/促销"是广告，"转账/账户异常"是诈骗），把它们渲染进对应选项的 rubric
 * 比放在全局 state 更精确 —— 模型看到的是"广告 = 推销信息，典型用词：优惠、促销"。
 */
object QuestionFactory {

    /** 分类问题的 id。答案也按此 id 取回。 */
    const val CATEGORY_QUESTION_ID: String = "category"

    /**
     * 分类问题的提问。
     *
     * 这里同时承担**解释 `state.userRules` 语义**的职责，而不只是问分类。
     *
     * 用户偏好规则在 state 里只是一个裸字段（`{"preferBlock":["中央宣传部"]}`），
     * 没有任何地方说过它是什么意思、该给多大权重 —— 模型收到的是这个 JSON，
     * 但没人要求它遵守，于是实际表现是"用户明明写了要拦，却仍被判正常"。
     * 规则本身没有传递失败（[StateBuilder] 确实注入了），失败的是**没有把约束讲出来**。
     *
     * 措辞上要求给"高置信度"，而不是只说"判为噪音类别"：分类动作还要过
     * `minConfidence` 这道阈值（广告类默认 0.9），只说类别不给置信度，
     * 规则命中仍会在 [DecisionMapper] 里被降级放行。
     */
    const val CATEGORY_INSTRUCTIONS: String =
        "这条消息属于哪一类？若无法确定，选择最接近「用户预期内」的那一类。" +
            "若 state 带有 userRules：preferBlock 列出的是用户点名要拦下的内容，" +
            "消息命中其中任意一条时应判为噪音类别，并给出与这条明确规则相称的高置信度；" +
            "preferAllow 列出的是用户点名要放行的内容，命中时倾向放行。"

    /**
     * 构造分类问题。
     *
     * @throws IllegalArgumentException 配置非法（见 [validate]）。
     */
    fun categoryQuestion(categories: List<CategoryConfig>): QuestionId<ChoiceAnswer> {
        validate(categories)
        return QuestionId(
            CATEGORY_QUESTION_ID,
            choice(
                instructions = CATEGORY_INSTRUCTIONS,
                criteria = categories.associate { it.name to renderCriteria(it) },
            ),
        )
    }

    /**
     * 渲染单个分类的 rubric 描述。
     *
     * 包名 `internal` 以便诊断页原样展示"模型到底看到了什么"，让配置调优可解释。
     */
    internal fun renderCriteria(category: CategoryConfig): String =
        if (category.keywords.isEmpty()) {
            category.description
        } else {
            "${category.description}（典型用词：${category.keywords.joinToString("、")}）"
        }

    /**
     * 校验分类配置。
     *
     * 前三条是结构约束，最后一条是**安全约束**：
     * 若没有任何 [Action.ALLOW] 分类，所有消息都会被当作噪音处置 ——
     * 配置错一个字就会让用户的所有通知消失。强制保留一个"放行"类别是必要的护栏。
     *
     * @throws IllegalArgumentException 任一约束不满足。
     */
    fun validate(categories: List<CategoryConfig>) {
        require(categories.isNotEmpty()) {
            "At least one category is required; the choice question would have an empty answer space."
        }
        categories.forEach { category ->
            require(category.name.isNotBlank()) {
                "Category names must not be blank."
            }
        }
        val duplicates = categories.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Category names must be unique, but found duplicates: ${duplicates.joinToString("、")}. " +
                "Duplicated names would silently collapse in the choice criteria map."
        }
        require(categories.any { it.action == Action.ALLOW }) {
            "At least one category must use Action.ALLOW as the catch-all. " +
                "Without it every message could be suppressed, which is unrecoverable for the user."
        }
    }
}
