package me.ethanxu.jevnoisegate.sdk.typesafe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

private val category = QuestionId(
    name = "category",
    question = choice(
        instructions = "这条消息属于哪一类",
        criteria = mapOf("广告" to "营销信息", "正常" to "用户预期内的信息"),
    ),
)

private val isOtp = QuestionId(
    name = "is_otp",
    question = noul("是否包含一次性验证码"),
)

private val urgency = QuestionId(
    name = "urgency",
    question = score(
        instructions = "打扰用户的代价有多高",
        criteria = listOf("纯噪音", "可延后", "需要立刻看到"),
    ),
)

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private fun MockRequestHandleScope.jsonResponse(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    extraHeaders: Map<String, String> = emptyMap(),
): HttpResponseData = respond(
    content = content,
    status = status,
    headers = headersOf(
        *(listOf(HttpHeaders.ContentType to listOf("application/json")) +
            extraHeaders.map { it.key to listOf(it.value) }).toTypedArray(),
    ),
)

/** 固定退避为 0，让重试测试在虚拟时间下瞬间跑完。 */
private fun testClient(
    engine: MockEngine,
    retry: RetryPolicy = RetryPolicy(backoffInitialMs = 0, backoffJitter = 0.0),
): TypeSafeClient = TypeSafeClient(
    config = TypeSafeConfig(apiKey = "test-key", retry = retry),
    http = HttpClient(engine) { expectSuccess = false },
)

class TypeSafeClientTest {

    // -----------------------------------------------------------------------
    // 正常解析
    // -----------------------------------------------------------------------

    @Test
    fun `parses choice noul and score answers`() = runTest {
        val engine = MockEngine {
            jsonResponse(
                """
                {
                  "model": "jev-latest",
                  "answers": {
                    "category": {
                      "type": "choice",
                      "choice": "广告",
                      "probabilities": {"广告": 0.97, "正常": 0.03},
                      "confidence": 0.91
                    },
                    "is_otp": {"type": "noul", "noul": 0.02},
                    "urgency": {
                      "type": "score",
                      "score": 0.14,
                      "legend": {"0": "纯噪音", "1": "可延后", "2": "需要立刻看到"},
                      "probabilities": {"0": 0.88, "1": 0.1, "2": 0.02},
                      "confidence": 0.83
                    }
                  },
                  "usage": {"input_tokens": 312, "output_tokens": 48}
                }
                """.trimIndent(),
            )
        }

        val result = testClient(engine).systemOne("【淘宝】限时优惠") {
            ask(category)
            ask(isOtp)
            ask(urgency)
        }

        assertEquals("jev-latest", result.model)
        assertEquals(312, result.usage.inputTokens)
        assertEquals(48, result.usage.outputTokens)

        // 类型安全：无需强转即拿到各自的具体类型
        val choice: ChoiceAnswer = result.answer(category)
        assertEquals("广告", choice.choice)
        assertEquals(0.91, choice.confidence, 1e-9)
        assertEquals(0.97, choice.probabilities.getValue("广告"), 1e-9)

        val noul: NoulAnswer = result.answer(isOtp)
        assertEquals(0.02, noul.noul, 1e-9)

        val scored: ScoreAnswer = result.answer(urgency)
        assertEquals(0.14, scored.score, 1e-9)
        assertEquals("可延后", scored.legend.getValue("1"))
    }

    @Test
    fun `sends the documented wire contract`() = runTest {
        var capturedBody: String? = null
        var capturedPath: String? = null
        var capturedAuth: String? = null
        var capturedSdkHeader: String? = null

        val engine = MockEngine { request: HttpRequestData ->
            capturedBody = request.body.toByteArray().decodeToString()
            capturedPath = request.url.encodedPath
            capturedAuth = request.headers[HttpHeaders.Authorization]
            capturedSdkHeader = request.headers["X-TypeSafe-SDK"]
            jsonResponse("""{"model":"jev-latest","answers":{},"usage":{"input_tokens":0,"output_tokens":0}}""")
        }

        // 结构化 state：用 JsonObject 形式传入，验证原样透传
        testClient(engine).systemOne(
            buildJsonObject { put("text", JsonPrimitive("【淘宝】限时优惠")) },
        ) { ask(category) }

        assertEquals("/v1/systemone", capturedPath)
        assertEquals("Bearer test-key", capturedAuth)
        assertEquals("typesafe-sdk-kotlin/0.1.0", capturedSdkHeader)

        val body = Json.parseToJsonElement(capturedBody!!).jsonObject
        assertEquals("jev-latest", body.getValue("model").jsonPrimitive.content)

        // state 原样透传（这里是 JSON 对象形式）
        assertEquals(
            "【淘宝】限时优惠",
            body.getValue("state").jsonObject.getValue("text").jsonPrimitive.content,
        )

        val question = body.getValue("questions").jsonObject.getValue("category").jsonObject
        assertEquals("choice", question.getValue("type").jsonPrimitive.content)
        assertEquals(
            "营销信息",
            question.getValue("criteria").jsonObject.getValue("广告").jsonPrimitive.content,
        )
    }

    @Test
    fun `lists models`() = runTest {
        val engine = MockEngine {
            jsonResponse(
                """{"models":[{"name":"jev-latest","description":"flagship","release_date":"2026-09-15"}]}""",
            )
        }
        val models = testClient(engine).models.list()
        assertEquals(1, models.size)
        assertEquals("jev-latest", models.first().name)
        assertEquals("2026-09-15", models.first().releaseDate)
    }

    // -----------------------------------------------------------------------
    // 错误映射
    // -----------------------------------------------------------------------

    @Test
    fun `maps status codes to exception types`() = runTest {
        val cases = listOf(
            HttpStatusCode.BadRequest to BadRequestException::class.java,
            HttpStatusCode.Unauthorized to AuthenticationException::class.java,
            HttpStatusCode.Forbidden to PermissionDeniedException::class.java,
            HttpStatusCode.NotFound to NotFoundException::class.java,
            HttpStatusCode.UnprocessableEntity to UnprocessableEntityException::class.java,
            HttpStatusCode.InternalServerError to InternalServerException::class.java,
        )

        cases.forEach { (status, expected) ->
            val engine = MockEngine { jsonResponse("""{"message":"nope"}""", status) }
            val error = runCatching {
                testClient(engine).systemOne("x") { ask(category) }
            }.exceptionOrNull()

            assertTrue(
                expected.isInstance(error),
                "status=$status 期望 $expected，实际 ${error?.javaClass?.name}",
            )
            assertEquals(status.value, (error as APIException).status)
        }
    }

    @Test
    fun `extracts fastapi style validation errors`() = runTest {
        val engine = MockEngine {
            jsonResponse(
                """{"detail":[{"loc":["body","questions","category"],"msg":"field required"}]}""",
                HttpStatusCode.UnprocessableEntity,
            )
        }
        val error = runCatching {
            testClient(engine).systemOne("x") { ask(category) }
        }.exceptionOrNull() as UnprocessableEntityException

        // loc 里的 "body" 被剔除，其余用点号连接
        assertTrue(error.message!!.contains("questions.category: field required"))
    }

    @Test
    fun `does not retry non-retryable status`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; jsonResponse("""{"message":"gone"}""", HttpStatusCode.NotFound) }

        runCatching { testClient(engine).systemOne("x") { ask(category) } }

        assertEquals(1, calls)
    }

    // -----------------------------------------------------------------------
    // 重试
    // -----------------------------------------------------------------------

    @Test
    fun `retries 5xx exactly maxRetries times then throws`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            jsonResponse("""{"message":"boom"}""", HttpStatusCode.InternalServerError)
        }
        val client = testClient(
            engine,
            RetryPolicy(maxRetries = 2, backoffInitialMs = 0, backoffJitter = 0.0),
        )

        val error = runCatching { client.systemOne("x") { ask(category) } }.exceptionOrNull()

        assertTrue(error is InternalServerException)
        assertEquals(3, calls) // 1 次初始 + 2 次重试
    }

    @Test
    fun `recovers when a retried attempt succeeds`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                jsonResponse("""{"message":"overloaded"}""", HttpStatusCode.ServiceUnavailable)
            } else {
                jsonResponse(
                    """
                    {"model":"jev-latest","answers":{"category":{"type":"choice",
                    "choice":"广告","probabilities":{"广告":1.0},"confidence":0.9}},
                    "usage":{"input_tokens":1,"output_tokens":1}}
                    """.trimIndent(),
                )
            }
        }
        val client = testClient(
            engine,
            RetryPolicy(maxRetries = 2, backoffInitialMs = 0, backoffJitter = 0.0),
        )

        val result = client.systemOne("x") { ask(category) }

        assertEquals("广告", result.answer(category).choice)
        assertEquals(2, calls)
    }

    @Test
    fun `retry sends the retry-count header`() = runTest {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seen += request.headers["X-TypeSafe-Retry-Count"]
            jsonResponse("""{"message":"boom"}""", HttpStatusCode.InternalServerError)
        }
        val client = testClient(
            engine,
            RetryPolicy(maxRetries = 1, backoffInitialMs = 0, backoffJitter = 0.0),
        )

        runCatching { client.systemOne("x") { ask(category) } }

        assertEquals(listOf(null, "1"), seen) // 首次不带，重试带实际次数
    }

    @Test
    fun `does not retry when maxRetries is zero`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            jsonResponse("""{"message":"boom"}""", HttpStatusCode.InternalServerError)
        }
        val client = testClient(
            engine,
            RetryPolicy(maxRetries = 0, backoffInitialMs = 0, backoffJitter = 0.0),
        )

        runCatching { client.systemOne("x") { ask(category) } }

        assertEquals(1, calls)
    }

    @Test
    fun `rate limit error carries retry-after`() = runTest {
        val engine = MockEngine {
            jsonResponse(
                """{"message":"slow down"}""",
                HttpStatusCode.TooManyRequests,
                mapOf("Retry-After" to "2"),
            )
        }
        val client = testClient(engine, RetryPolicy(maxRetries = 0))

        val error = runCatching { client.systemOne("x") { ask(category) } }.exceptionOrNull()

        assertTrue(error is RateLimitException)
        assertEquals(2000L, (error as RateLimitException).retryAfterMs)
    }

    // -----------------------------------------------------------------------
    // 结构校验
    // -----------------------------------------------------------------------

    @Test
    fun `rejects an answer for an unknown question`() = runTest {
        val engine = MockEngine {
            jsonResponse(
                """
                {"model":"jev-latest","answers":{"mystery":{"type":"noul","noul":0.5}},
                "usage":{"input_tokens":0,"output_tokens":0}}
                """.trimIndent(),
            )
        }
        val error = runCatching {
            testClient(engine).systemOne("x") { ask(category) }
        }.exceptionOrNull()

        assertTrue(error is TypeSafeException)
        assertTrue(error!!.message!!.contains("mystery"))
    }

    @Test
    fun `answer accessor rejects a mismatched question`() = runTest {
        val engine = MockEngine {
            jsonResponse(
                """
                {"model":"jev-latest","answers":{"category":{"type":"choice","choice":"广告",
                "probabilities":{"广告":1.0},"confidence":0.9}},
                "usage":{"input_tokens":0,"output_tokens":0}}
                """.trimIndent(),
            )
        }
        val result = testClient(engine).systemOne("x") { ask(category) }

        // category 是 choice，用 noul 的句柄去取必须被拦住
        val error = runCatching { result.answer(isOtp) }.exceptionOrNull()

        assertTrue(error is TypeSafeException)
        assertTrue(error!!.message!!.contains("No answer was returned"))
    }

    @Test
    fun `rejects empty question set`() = runTest {
        val engine = MockEngine { jsonResponse("{}") }
        val error = runCatching {
            testClient(engine).systemOne("x") { }
        }.exceptionOrNull()

        assertTrue(error is TypeSafeException)
        assertTrue(error!!.message!!.contains("At least one question"))
    }

    @Test
    fun `rejects score question with fewer than two criteria`() = runTest {
        val engine = MockEngine { jsonResponse("{}") }
        val error = runCatching {
            testClient(engine).systemOne("x") {
                ask("s", score("打分", listOf<String?>(null)))
            }
        }.exceptionOrNull()

        assertTrue(error is TypeSafeException)
        assertTrue(error!!.message!!.contains("at least two scores"))
    }

    @Test
    fun `rejects a blank api key`() {
        val error = runCatching { TypeSafeConfig(apiKey = "   ") }.exceptionOrNull()
        assertTrue(error is TypeSafeException)
        assertTrue(error!!.message!!.contains("No API key"))
    }

    @Test
    fun `falls back to raw text when the body is not json`() = runTest {
        val engine = MockEngine {
            respond(
                content = "<html>502 Bad Gateway</html>",
                status = HttpStatusCode.BadGateway,
                headers = jsonHeaders,
            )
        }
        val error = runCatching {
            testClient(engine, RetryPolicy(maxRetries = 0)).systemOne("x") { ask(category) }
        }.exceptionOrNull()

        assertTrue(error is InternalServerException)
        assertTrue(error!!.message!!.contains("502 Bad Gateway"))
    }
}
