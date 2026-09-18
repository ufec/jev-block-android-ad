package me.ethanxu.jevnoisegate.sdk.typesafe

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random
import kotlinx.io.IOException

/**
 * TypeSafe AI 客户端。移植自 `@typesafe-ai/sdk@0.6.0`（MIT）的 `TypeSafeClient`。
 *
 * 归属与授权说明见模块根目录的 `NOTICE` 与 `LICENSE` —— 后者保留了上游版权声明，
 * 是 MIT 的合规要件，请勿删除或替换。
 *
 * 与上游的三处有意分歧，均已在对应位置注明：
 * 1. `process.env` 回退改为纯显式配置（见 [TypeSafeConfig]）。
 * 2. 取消不包装成业务异常，`CancellationException` 原样传播（见 `Errors.kt`）。
 * 3. 上游会把 request 对象上的额外属性透传；Kotlin 无此语义，故不透传。
 *
 * @param config 客户端配置。
 * @param http 便于测试注入（如 Ktor `MockEngine`）。默认使用 OkHttp 引擎。
 */
class TypeSafeClient(
    private val config: TypeSafeConfig,
    private val http: HttpClient = defaultHttpClient(config.timeoutMs, config.proxy),
) : AutoCloseable {

    private val logger: TypeSafeLogger = config.logger.atLevel(config.logLevel)

    /**
     * 请求序号，仅用于在日志里区分并发请求。对应上游 `#requestCount`。
     *
     * 刻意不用原子类型：它只影响日志可读性，并发下偶尔重号不影响任何行为，
     * 为此引入 `kotlin.concurrent.atomics` 的实验性 API 不划算。
     */
    private var requestCount = 0L

    /** 账号可用模型。对应上游 `client.models`。 */
    val models: ModelsResource = ModelsResource(::listModels)

    // -----------------------------------------------------------------------
    // 公开 API
    // -----------------------------------------------------------------------

    /**
     * 对纯文本 [state] 提问。
     *
     * 这是最常见的入口 —— state 通常就是一条消息正文。
     * 需要传结构化 state（对象或数组）时用接受 [EntryType] 的那个重载。
     */
    suspend fun systemOne(
        state: String,
        build: SystemOneRequestBuilder.() -> Unit,
    ): SystemOneResult = systemOne(JsonPrimitive(state), build)

    /**
     * 对 [state] 提问，用 lambda 形式登记问题。
     *
     * ```
     * val category = QuestionId("category", choice("属于哪一类", mapOf("广告" to null, "正常" to null)))
     * val result = client.systemOne("【淘宝】限时优惠") { ask(category) }
     * println(result.answer(category).choice)
     * ```
     *
     * @throws TypeSafeException 问题集合为空，或有 score 问题档位少于两项。
     */
    suspend fun systemOne(
        state: EntryType,
        build: SystemOneRequestBuilder.() -> Unit,
    ): SystemOneResult = systemOne(SystemOneRequestBuilder(state).apply(build).build())

    /**
     * 对 [request] 求值。
     *
     * @throws TypeSafeException 问题集合不合法。
     * @throws APIException 服务端在重试后仍返回非 2xx。
     * @throws APIConnectionException 重试后仍无法连接或超时。
     */
    suspend fun systemOne(request: SystemOneRequest): SystemOneResult =
        systemOneWithResponse(request).data

    /** 同 [systemOne]，但额外暴露 HTTP 状态码与 request id。对应上游 `withResponse()`。 */
    suspend fun systemOneWithResponse(request: SystemOneRequest): TypeSafeResponse<SystemOneResult> {
        validateQuestions(request.questions)

        val model = request.model ?: config.defaultModel
        val payload = buildJsonObject {
            put("state", request.state ?: JsonNull)
            put("model", JsonPrimitive(model))
            put(
                "questions",
                buildJsonObject {
                    request.questions.forEach { (name, question) ->
                        put(name, question.toJson())
                    }
                },
            )
        }

        val raw = send(
            tag = "POST $SYSTEM_ONE_PATH",
            method = HttpMethod.Post,
            path = SYSTEM_ONE_PATH,
            body = payload.toString(),
        )
        return TypeSafeResponse(
            data = parseSystemOneResult(raw, request, model),
            status = raw.status,
            requestId = raw.requestId,
        )
    }

    /** 列出账号可用模型。对应上游 `GET /v1/models`。 */
    suspend fun listModels(): List<ModelCard> {
        val raw = send(
            tag = "GET $MODELS_PATH",
            method = HttpMethod.Get,
            path = MODELS_PATH,
            body = null,
        )
        val obj = raw.body as? JsonObject
            ?: throw TypeSafeException(
                "Unexpected response shape from GET $MODELS_PATH; expected { models: [...] }.",
            )
        val array = obj["models"] as? JsonArray
            ?: throw TypeSafeException(
                "Unexpected response shape from GET $MODELS_PATH; expected { models: [...] }.",
            )
        return array.mapNotNull { element ->
            val card = element as? JsonObject ?: return@mapNotNull null
            val name = card.stringOrNull("name") ?: return@mapNotNull null
            ModelCard(
                name = name,
                description = card.stringOrNull("description").orEmpty(),
                releaseDate = card.stringOrNull("release_date").orEmpty(),
            )
        }
    }

    /** 释放底层 HTTP 引擎。 */
    override fun close() {
        http.close()
    }

    // -----------------------------------------------------------------------
    // 传输层
    // -----------------------------------------------------------------------

    /**
     * 发送一次请求，并按 [RetryPolicy] 重试。
     *
     * 重试循环是自建的（而非启用 Ktor 的 `HttpRequestRetry` 插件），
     * 因为需要还原上游的两处语义：`X-TypeSafe-Retry-Count` 头，以及
     * "`Retry-After` 未超出上限时优先于指数退避"的取值顺序。
     */
    private suspend fun send(
        tag: String,
        method: HttpMethod,
        path: String,
        body: String?,
    ): RawResult {
        val url = config.normalizedBaseUrl + path
        val retry = config.retry
        val tagWithId = "#${++requestCount} $tag"

        var attempt = 0
        while (true) {
            val retriesLeft = retry.maxRetries - attempt
            val headers = buildHeaders(hasBody = body != null, attempt = attempt)
            logger.debug("$tagWithId -> $url ${redactHeaders(headers)}")
            val startedAt = System.currentTimeMillis()

            try {
                val response = http.request(url) {
                    this.method = method
                    headers.forEach { (name, value) -> header(name, value) }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    timeout { requestTimeoutMillis = config.timeoutMs }
                }

                val status = response.status.value
                val requestId = response.headers[REQUEST_ID_HEADER]
                val text = response.bodyAsText()
                val parsed = parseBody(text)

                logger.info(
                    buildString {
                        append("$tagWithId <- $status in ")
                        append(System.currentTimeMillis() - startedAt)
                        append("ms")
                        if (requestId != null) append(" (request $requestId)")
                    },
                )

                if (status in 200..299) {
                    return RawResult(status = status, requestId = requestId, body = parsed)
                }

                logger.debug("$tagWithId <- error body ${parsed?.describeForLog()}")
                throw APIException.fromResponse(
                    APIErrorResponse(
                        status = status,
                        body = parsed,
                        rawBody = text,
                        requestId = requestId,
                        headers = response.headers.entries()
                            .associate { it.key.lowercase() to it.value.first() },
                    ),
                )
            } catch (e: CancellationException) {
                // 协程取消是控制流信号，不重试也不包装。
                throw e
            } catch (e: HttpRequestTimeoutException) {
                logger.info(
                    "$tagWithId timed out after ${System.currentTimeMillis() - startedAt}ms",
                )
                if (retriesLeft <= 0 || !retry.apiTimeoutError) {
                    throw APITimeoutException(config.timeoutMs, e)
                }
                backOff(tagWithId, attempt, retriesLeft, "timeout", null, retry)
            } catch (e: IOException) {
                // 含 Ktor 的 ConnectTimeoutException / SocketTimeoutException：
                // 上游语义里只有整体超时算 timeout，连接层失败算 connection error。
                logger.info("$tagWithId connection error after ${e.message}")
                if (retriesLeft <= 0 || !retry.apiConnectionError) {
                    throw APIConnectionException("Connection error: ${e.message}", e)
                }
                backOff(tagWithId, attempt, retriesLeft, e.message ?: "connection error", null, retry)
            } catch (e: APIException) {
                if (retriesLeft <= 0 || !retry.isRetryableStatus(e.status)) throw e
                backOff(
                    tagWithId,
                    attempt,
                    retriesLeft,
                    e.status.toString(),
                    (e as? RateLimitException)?.retryAfterMs,
                    retry,
                )
            }

            attempt++
        }
    }

    /** 等待后重试。对应上游 `backOff`。取消由 [delay] 直接传播。 */
    private suspend fun backOff(
        tag: String,
        attempt: Int,
        retriesLeft: Int,
        reason: String,
        retryAfterMs: Long?,
        retry: RetryPolicy,
    ) {
        val delayMs = retryDelayMs(attempt, retryAfterMs, retry, Random::nextDouble)
        val nth = attempt + 1
        val total = attempt + retriesLeft
        logger.info("$tag retrying in ${delayMs}ms (retry $nth/$total) after $reason")
        delay(delayMs)
    }

    /** 组装请求头。用户提供的 [TypeSafeConfig.defaultHeaders] 在前，SDK 自身值覆盖之。 */
    private fun buildHeaders(hasBody: Boolean, attempt: Int): Map<String, String> = buildMap {
        putAll(config.defaultHeaders)
        put(AUTHORIZATION_HEADER, "Bearer ${config.apiKey}")
        put(ACCEPT_HEADER, "application/json")
        put(USER_AGENT_HEADER, SDK_USER_AGENT)
        put(SDK_HEADER, SDK_USER_AGENT)
        put(RUNTIME_HEADER, config.runtimeDescriptor)
        if (hasBody) put(CONTENT_TYPE_HEADER, "application/json")
        if (attempt > 0) put(RETRY_COUNT_HEADER, attempt.toString())
    }

    /**
     * 宽松解析响应体：不是合法 JSON 时退回原始文本。
     * 对应上游 `parseBody` 的容错行为（服务端与代理不总是设置 content-type）。
     */
    private fun parseBody(text: String): JsonElement? {
        if (text.isEmpty()) return null
        return runCatching { wireJson.parseToJsonElement(text) }
            .getOrElse { JsonPrimitive(text) }
    }

    /**
     * 解析 `systemOne` 响应。
     *
     * 这里比上游严格：上游把 answers 当成 `Record<string, Answer>` 直接强转，
     * 本项目改为**用对应 question 解析自己的答案**，并对多出来的未知 key 直接报错。
     * 原因是流水线里静默的错误分类比一次可见的异常代价高得多。
     */
    private fun parseSystemOneResult(
        raw: RawResult,
        request: SystemOneRequest,
        requestedModel: String,
    ): SystemOneResult {
        val obj = raw.body as? JsonObject
            ?: throw TypeSafeException(
                "Unexpected response from POST $SYSTEM_ONE_PATH; expected a JSON object.",
            )
        val answersObject = obj.objectOrNull("answers")
            ?: throw TypeSafeException("Response from POST $SYSTEM_ONE_PATH is missing \"answers\".")

        val answers = LinkedHashMap<String, Answer>(answersObject.size)
        answersObject.forEach { (name, element) ->
            val answerObject = element as? JsonObject
                ?: throw TypeSafeException("Answer \"$name\" is not a JSON object.")
            val question = request.questions[name]
                ?: throw TypeSafeException(
                    "Server returned an answer for unknown question \"$name\". " +
                        "Known questions: ${request.questions.keys}.",
                )
            answers[name] = question.parseAnswer(answerObject)
        }

        val usage = obj.objectOrNull("usage")
        return SystemOneResult(
            model = obj.stringOrNull("model") ?: requestedModel,
            answers = answers,
            usage = Usage(
                inputTokens = usage?.intOrNull("input_tokens") ?: 0,
                outputTokens = usage?.intOrNull("output_tokens") ?: 0,
            ),
        )
    }

    private data class RawResult(
        val status: Int,
        val requestId: String?,
        val body: JsonElement?,
    )
}

/** 解析响应用的宽松配置。 */
private val wireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 默认 HTTP 引擎。
 *
 * `expectSuccess = false` 是关键 —— 让 4xx/5xx 以正常响应的形式返回，
 * 由 [TypeSafeClient] 自行分派异常类型（才能还原上游的状态码 → 异常映射）。
 */
private const val SYSTEM_ONE_PATH = "/v1/systemone"
private const val MODELS_PATH = "/v1/models"

private const val AUTHORIZATION_HEADER = "Authorization"
private const val ACCEPT_HEADER = "Accept"
private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val USER_AGENT_HEADER = "User-Agent"
private const val SDK_HEADER = "X-TypeSafe-SDK"
private const val RUNTIME_HEADER = "X-TypeSafe-Runtime"
private const val RETRY_COUNT_HEADER = "X-TypeSafe-Retry-Count"

/** 响应用于定位问题的请求 id。 */
private const val REQUEST_ID_HEADER = "x-typesafe-request-id"
