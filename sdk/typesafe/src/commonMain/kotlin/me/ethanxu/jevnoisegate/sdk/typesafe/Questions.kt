package me.ethanxu.jevnoisegate.sdk.typesafe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 一个类型化问题。
 *
 * 对应上游 `types.ts` 的 `NoulQuestion | ScoreQuestion | ChoiceQuestion`。
 * 与上游的关键差异：[Question] 只挑了一个方向的类型参数 —— 泛型参数 `A` 表示**答案**类型，
 * 由各个子类固定下来。这样 [SystemOneResult.answer] 可以在编译期保证取到正确类型，
 * 而上游只能靠 TS 的 `ResultFor<T>` 条件类型在类型层面推导、运行期仍然是 `Record<string, Answer>`。
 */
sealed class Question<A : Answer> {

    /** 与返回答案的 `type` 字段一致。 */
    abstract val type: String

    /** 问题本身，可以是文本、JSON 对象或数组。 */
    abstract val instructions: EntryType

    internal abstract fun toJson(): JsonObject

    internal abstract fun parseAnswer(element: JsonObject): A
}

/** noul 问题的正反两面描述。对应上游 `NoulQuestion["criteria"]`。 */
data class NoulCriteria(
    /** 回答为"是"（接近 1）的含义。 */
    val trueDescription: EntryType = null,
    /** 回答为"否"（接近 0）的含义。 */
    val falseDescription: EntryType = null,
)

/**
 * 是非题。答案是 [NoulAnswer]。
 *
 * 上游明确说明：noul 答案**不携带 confidence**，因为返回值本身就是概率。
 */
class NoulQuestion(
    override val instructions: EntryType = null,
    val criteria: NoulCriteria? = null,
) : Question<NoulAnswer>() {

    override val type: String get() = NoulAnswer.TYPE

    override fun toJson(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("instructions", instructions ?: JsonNull)
        criteria?.let { c ->
            put(
                "criteria",
                buildJsonObject {
                    put("true", c.trueDescription ?: JsonNull)
                    put("false", c.falseDescription ?: JsonNull)
                },
            )
        }
    }

    override fun parseAnswer(element: JsonObject): NoulAnswer =
        NoulAnswer(noul = element.requireDouble("noul"))
}

/**
 * 从一组具名选项中择一。答案是 [ChoiceAnswer]。
 *
 * `criteria` 的 key 就是答案空间 —— 本项目的核心做法是让用户配置的分类直接成为这里的 key。
 */
class ChoiceQuestion(
    override val instructions: EntryType,
    val criteria: Map<String, EntryType>,
) : Question<ChoiceAnswer>() {

    override val type: String get() = ChoiceAnswer.TYPE

    override fun toJson(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("instructions", instructions ?: JsonNull)
        put(
            "criteria",
            buildJsonObject {
                criteria.forEach { (label, description) ->
                    put(label, description ?: JsonNull)
                }
            },
        )
    }

    override fun parseAnswer(element: JsonObject): ChoiceAnswer = ChoiceAnswer(
        choice = element.requireString("choice"),
        confidence = element.requireDouble("confidence"),
        probabilities = element.requireDoubleMap("probabilities"),
    )
}

/**
 * 按有序量规打分。答案是 [ScoreAnswer]。
 *
 * `criteria` 是有序档位描述，**至少两项**（由 [validateQuestions] 强制）。
 */
class ScoreQuestion(
    override val instructions: EntryType,
    val criteria: List<EntryType>,
) : Question<ScoreAnswer>() {

    override val type: String get() = ScoreAnswer.TYPE

    override fun toJson(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("instructions", instructions ?: JsonNull)
        put("criteria", JsonArray(criteria.map { it ?: JsonNull }))
    }

    override fun parseAnswer(element: JsonObject): ScoreAnswer = ScoreAnswer(
        score = element.requireDouble("score"),
        confidence = element.requireDouble("confidence"),
        legend = element.requireStringMap("legend"),
        probabilities = element.requireDoubleMap("probabilities"),
    )
}

/**
 * 一个具名问题的句柄，同时绑定答案类型。
 *
 * 用法：
 * ```
 * val category = QuestionId("category", ChoiceQuestion("属于哪一类", mapOf("广告" to "…", "正常" to "…")))
 * val result = client.systemOne(state) { ask(category) }
 * val answer: ChoiceAnswer = result.answer(category)   // 编译期即确定类型
 * ```
 */
class QuestionId<A : Answer>(
    val name: String,
    val question: Question<A>,
)

// ---------------------------------------------------------------------------
// 构造器（对应上游 questions.ts 的 noul / choice / score）
// ---------------------------------------------------------------------------

/*
 * 上游对 `criteria` 传错容器类型（choice 传数组、score 传 map）会抛运行期错误；
 * Kotlin 里由参数类型在编译期保证，因此不需要那两条校验。
 *
 * 关于下面的**重载**：上游的 `EntryType = string | object | array | null`，
 * 在 Kotlin 里落成 `JsonElement?`，而 `JsonElement` 不接受裸 `String`。
 * 由于 instructions 与 criteria 描述在真实使用中几乎总是纯文本（本项目里 100% 是），
 * 这里为最常见场景补一组 `String` 重载，同时保留 `JsonElement` 版本以表达结构化内容。
 * 两组重载不会歧义：`String` 不是 `JsonElement?` 的子类型。
 */

/** 构造是非题，instructions 为纯文本。 */
fun noul(
    instructions: String,
    criteria: NoulCriteria? = null,
): NoulQuestion = NoulQuestion(JsonPrimitive(instructions), criteria)

/** 构造是非题。`instructions` 可以是 JSON 对象或数组。 */
fun noul(
    instructions: EntryType = null,
    criteria: NoulCriteria? = null,
): NoulQuestion = NoulQuestion(instructions, criteria)

/** 构造纯文本描述的 noul 正反面 criteria。 */
fun noulCriteria(
    trueDescription: String? = null,
    falseDescription: String? = null,
): NoulCriteria = NoulCriteria(
    trueDescription = trueDescription?.let(::JsonPrimitive),
    falseDescription = falseDescription?.let(::JsonPrimitive),
)

/** 构造选择题，instructions 与选项描述均为纯文本。描述传 `null` 表示不给额外说明。 */
fun choice(
    instructions: String,
    criteria: Map<String, String?>,
): ChoiceQuestion = ChoiceQuestion(
    instructions = JsonPrimitive(instructions),
    criteria = criteria.mapValues { (_, description) -> description?.let(::JsonPrimitive) },
)

/** 构造选择题。`instructions` 可以是 JSON 对象或数组，选项描述同理。 */
fun choice(
    instructions: EntryType,
    criteria: Map<String, EntryType>,
): ChoiceQuestion = ChoiceQuestion(instructions, criteria)

/** 构造打分题，instructions 与各档位描述均为纯文本。 */
fun score(
    instructions: String,
    criteria: List<String?>,
): ScoreQuestion = ScoreQuestion(
    instructions = JsonPrimitive(instructions),
    criteria = criteria.map { description -> description?.let(::JsonPrimitive) },
)

/** 构造打分题。`instructions` 可以是 JSON 对象或数组，档位描述同理。 */
fun score(
    instructions: EntryType,
    criteria: List<EntryType>,
): ScoreQuestion = ScoreQuestion(instructions, criteria)

/**
 * 渐构建构器，供 [TypeSafeClient.systemOne] 的 lambda 形式使用。
 */
class SystemOneRequestBuilder internal constructor(private val state: EntryType) {

    private val questions = LinkedHashMap<String, Question<*>>()

    /** 覆盖本次调用的模型；不设置则使用客户端的 `defaultModel`。 */
    var model: String? = null

    /** 登记一个问题，答案稍后通过同一个 [QuestionId] 取回。 */
    fun <A : Answer> ask(id: QuestionId<A>): SystemOneRequestBuilder = apply {
        questions[id.name] = id.question
    }

    /** 登记一个问题，答案稍后按 [name] 取回。 */
    fun <A : Answer> ask(name: String, question: Question<A>): SystemOneRequestBuilder = apply {
        questions[name] = question
    }

    internal fun build(): SystemOneRequest =
        SystemOneRequest(state = state, model = model, questions = questions.toMap())
}

/**
 * 校验问题集合。对应上游 `validateQuestions`。
 *
 * 只保留 Kotlin 类型系统无法表达的两条约束：非空、以及 score 至少两项。
 *
 * @throws TypeSafeException 问题集合为空，或有 score 问题的档位少于两项。
 */
internal fun validateQuestions(questions: Map<String, Question<*>>) {
    if (questions.isEmpty()) {
        throw TypeSafeException("At least one question is required.")
    }
    questions.forEach { (name, question) ->
        if (question is ScoreQuestion && question.criteria.size < 2) {
            throw TypeSafeException(
                "Score question \"$name\" has ${question.criteria.size} criteria; " +
                    "at least two scores are required.",
            )
        }
    }
}
