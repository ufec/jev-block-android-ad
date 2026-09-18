package me.ethanxu.jevnoisegate.sdk.typesafe

/**
 * TypeSafe 返回的类型化答案。
 *
 * 对应上游 `types.ts` 的 `NoulResponse` / `ChoiceResponse` / `ScoreResponse`。
 * 之所以用 sealed 层级，是因为这三型的字段集合互不相同，且调用方需要穷尽分支。
 */
sealed interface Answer {
    /** 与产生它的 question 的 `type` 一致。 */
    val type: String
}

/**
 * noul（是/否）答案：`noul` 为 0–1 的概率值。
 *
 * 注意：上游明确说明 noul 答案**不带 confidence**，因为它本身就是概率。
 */
data class NoulAnswer(
    val noul: Double,
) : Answer {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "noul"
    }
}

/** choice 答案：被选中的选项、各选项的概率分布、以及由此推导的 confidence。 */
data class ChoiceAnswer(
    val choice: String,
    val confidence: Double,
    val probabilities: Map<String, Double>,
) : Answer {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "choice"
    }
}

/**
 * score 答案：概率加权得分（可落在整数档位之间）、档位回照表、以及概率分布。
 */
data class ScoreAnswer(
    val score: Double,
    val confidence: Double,
    val legend: Map<String, String>,
    val probabilities: Map<String, Double>,
) : Answer {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "score"
    }
}
