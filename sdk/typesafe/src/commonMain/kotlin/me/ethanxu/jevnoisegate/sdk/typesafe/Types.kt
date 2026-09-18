package me.ethanxu.jevnoisegate.sdk.typesafe

import kotlinx.serialization.json.JsonElement

/**
 * 可传给 TypeSafe 的入口类型：纯文本、JSON 对象、JSON 数组，或 `null`。
 *
 * 对应上游 `types.ts` 的 `EntryType`。
 */
typealias EntryType = JsonElement?

/** 一次请求的 token 用量。对应上游 `Usage`。 */
data class Usage(
    val inputTokens: Int,
    val outputTokens: Int,
)

/** 账号可用的模型元信息。对应上游 `ModelCard`。 */
data class ModelCard(
    val name: String,
    val description: String,
    val releaseDate: String,
)

/**
 * 一次 `systemOne` 调用的结果。
 *
 * 对应上游 `SystemOneResult<Q>`。上游借助 TS 泛型从 questions 推导每个 answer 的类型；
 * Kotlin 侧改为在取用时通过 [QuestionId] 做类型安全访问（见 [answer]）。
 */
class SystemOneResult internal constructor(
    val model: String,
    val answers: Map<String, Answer>,
    val usage: Usage,
) {

    /**
     * 按 [QuestionId] 取出对应答案。
     *
     * 先校验 `answer.type == question.type` 再做转换，因此这次强转在运行期是有保证的。
     *
     * @throws TypeSafeException 该问题没有对应答案，或答案类型与问题类型不符。
     */
    fun <A : Answer> answer(id: QuestionId<A>): A {
        val raw = answers[id.name]
            ?: throw TypeSafeException("No answer was returned for question \"${id.name}\".")
        if (raw.type != id.question.type) {
            throw TypeSafeException(
                "Answer for question \"${id.name}\" has type \"${raw.type}\" " +
                    "but the question is of type \"${id.question.type}\".",
            )
        }
        @Suppress("UNCHECKED_CAST")
        return raw as A
    }

    override fun toString(): String =
        "SystemOneResult(model=$model, answers=${answers.keys}, usage=$usage)"
}

/**
 * 同时携带返回值与响应元信息（HTTP 状态码、request id）。
 *
 * 对应上游 `APIPromise.withResponse()`。
 */
data class TypeSafeResponse<T>(
    val data: T,
    val status: Int,
    val requestId: String?,
)

/** 一次请求的完整描述。由 [SystemOneRequestBuilder] 或 [TypeSafeClient.systemOne] 构造。 */
class SystemOneRequest internal constructor(
    val state: EntryType,
    val model: String?,
    val questions: Map<String, Question<*>>,
)
