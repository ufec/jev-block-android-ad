package me.ethanxu.jevnoisegate.sdk.typesafe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 解析错误的上下文，把散落在各处的响应元信息收拢成一个值对象。 */
data class APIErrorResponse(
    val status: Int,
    val body: JsonElement?,
    val rawBody: String?,
    /** 取自 `x-typesafe-request-id`，缺失时为 null。 */
    val requestId: String?,
    /** 响应 header（键统一小写）。用于解析 `Retry-After`。 */
    val headers: Map<String, String> = emptyMap(),
)

/** SDK 所有异常的基类。对应上游 `TypeSafeError`。 */
open class TypeSafeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 服务端返回了非 2xx 响应。对应上游 `APIError`。
 *
 * 子类严格对齐上游的状态码分派：400 / 401 / 403 / 404 / 422 / 429 / 5xx。
 */
open class APIException(
    val response: APIErrorResponse,
    message: String,
) : TypeSafeException(message) {

    val status: Int get() = response.status
    val body: JsonElement? get() = response.body
    val requestId: String? get() = response.requestId

    companion object {
        /** 上限与上游 `MAX_RAW_BODY_IN_MESSAGE` 一致。 */
        private const val MAX_RAW_BODY_IN_MESSAGE = 200

        /** 按状态码构造对应的异常子类。 */
        fun fromResponse(response: APIErrorResponse): APIException {
            val message = describe(response)
            return when (response.status) {
                400 -> BadRequestException(response, message)
                401 -> AuthenticationException(response, message)
                403 -> PermissionDeniedException(response, message)
                404 -> NotFoundException(response, message)
                422 -> UnprocessableEntityException(response, message)
                429 -> RateLimitException(
                    response = response,
                    message = message,
                    retryAfterMs = parseRetryAfter(response.headers, System.currentTimeMillis()),
                )
                in 500..599 -> InternalServerException(response, message)
                else -> APIException(response, message)
            }
        }

        /** 从响应体里尽量抠出可读信息，顺序与上游 `extractMessage` 一致。 */
        private fun describe(response: APIErrorResponse): String {
            val detail = extractMessage(response.body)
            if (detail != null) return "${response.status} $detail"

            val raw = response.rawBody
            if (raw.isNullOrEmpty()) return "${response.status} status code (no body)"
            val shown = if (raw.length > MAX_RAW_BODY_IN_MESSAGE) {
                raw.take(MAX_RAW_BODY_IN_MESSAGE) + "…"
            } else {
                raw
            }
            return "${response.status} $shown"
        }

        private fun extractMessage(body: JsonElement?): String? {
            val primitive = (body as? JsonPrimitive)?.takeIf { it.isString }
            if (primitive != null) {
                val text = primitive.content
                return text.ifEmpty { null }
            }

            val obj = body as? JsonObject ?: return null
            (obj["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
            (obj["error"] as? JsonObject)?.get("message")
                ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.let { return it.content }
            (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }

            when (val detail = obj["detail"]) {
                is JsonPrimitive -> if (detail.isString) return detail.content
                is JsonObject -> (detail["message"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.let { return it.content }
                is JsonArray -> return describeValidationErrors(detail)
                else -> Unit
            }
            return null
        }

        /** 把 FastAPI 风格的校验错误格式化成 `path: message; path: message`。 */
        private fun describeValidationErrors(errors: JsonArray): String? {
            val parts = errors.mapNotNull { entry ->
                val obj = entry.jsonObjectOrNull() ?: return@mapNotNull null
                val msg = obj["msg"]?.jsonPrimitive?.takeIf { it.isString }?.content
                    ?: return@mapNotNull null
                val loc = (obj["loc"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.takeIf { p -> p.isString }?.content }
                    ?.filter { it != "body" }
                    ?.joinToString(".")
                    .orEmpty()
                if (loc.isEmpty()) msg else "$loc: $msg"
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }

        private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    }
}

/** HTTP 400：请求不合法。 */
class BadRequestException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/** HTTP 401：认证失败。 */
class AuthenticationException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/** HTTP 403：无权访问。 */
class PermissionDeniedException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/** HTTP 404：资源不存在。 */
class NotFoundException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/** HTTP 422：请求体校验失败。 */
class UnprocessableEntityException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/** HTTP 429：超出限流。 */
class RateLimitException(
    response: APIErrorResponse,
    message: String,
    /** 服务端给出的重试延迟；缺失或非法时为 null。 */
    val retryAfterMs: Long?,
) : APIException(response, message)

/** HTTP 5xx：服务端处理失败。 */
class InternalServerException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/**
 * 请求或响应体传输失败（DNS、TLS、连接中断等）。对应上游 `APIConnectionError`。
 */
open class APIConnectionException(
    message: String,
    cause: Throwable? = null,
) : TypeSafeException(message, cause)

/** 完整响应未在超时时间内到达。对应上游 `APITimeoutError`。 */
class APITimeoutException(
    /** 配置的超时毫秒数。 */
    val timeoutMs: Long,
    cause: Throwable? = null,
) : APIConnectionException("Request timed out after ${timeoutMs}ms.", cause)

/*
 * 与上游的一处**有意分歧**：上游有 `APIUserAbortError`，表示调用方通过 AbortSignal 取消。
 *
 * Kotlin 协程里取消是控制流信号而非错误 —— `CancellationException` 必须原样向上传播，
 * 否则会破坏结构化并发（父作用域无法正确判定子任务已取消）。因此本 SDK 不把它包装成
 * 业务异常，调用方按协程惯例捕获 `kotlinx.coroutines.CancellationException` 即可。
 */
