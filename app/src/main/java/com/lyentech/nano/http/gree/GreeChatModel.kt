package com.lyentech.nano.http.gree

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.lyentech.nano.BuildConfig
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * @author by jason-何伟杰，2026/9/29
 *
 * des:云端模型。用公司 OpenAI 兼容的 chat-completions 接口实现 ADK 的 [Model]，
 *      所以可以直接塞进 LlmAgent(model = GreeChatModel())。
 *
 * Errors are reported by throwing, never by an error-carrying [LlmResponse]: transport failures and
 * non-2xx statuses are translated into [IOException]s with a readable message. Both of the example
 * screens (`ApiChatActivity`, `RtLlmChatActivity`) already wrap `runAsync` in a `catch (e:
 * Exception)` that renders `e.message`, so a thrown IOException surfaces to the user with no extra
 * plumbing; picking one mechanism keeps the UI path single.
 *
 * The API key is treated as a secret: it reaches exactly one place, the `Authorization` header, and
 * never a log line, an exception message or a `toString()`.
 *
 * @param apiKey the bearer credential; when blank the header is omitted entirely.
 * @param baseUrl scheme and host, e.g. `https://baitong-aiw.gree.com/openapi/cllm`.
 * @param chatPath appended to [baseUrl]; kept separate so it stays overridable per deployment.
 * @param model the model name sent in the request body and reported by [name].
 * @param authScheme the scheme prefixing [apiKey], e.g. `Bearer`; when blank the raw key is sent.
 * @param reasoningEffort UI 选择的推理强度，原样作为 `reasoning_effort` 下发；null 表示不发送该字段。
 * @param client shared HTTP client; the default is fine for a whole app and should be reused, since
 *   each instance owns its own connection pool.
 * @param log 日志出口。宿主 JVM 的活体测试要传 [GreeLog.NoOp]，否则成功路径上的那行工具调用日志
 *   会撞上 `android.util.Log not mocked`。
 */
class GreeChatModel(
    private val apiKey: String = BuildConfig.GREE_API_KEY,
    private val baseUrl: String = BuildConfig.GREE_BASE_URL,
    private val chatPath: String = BuildConfig.GREE_CHAT_PATH,
    private val model: String = BuildConfig.GREE_MODEL,
    private val authScheme: String = BuildConfig.GREE_AUTH_SCHEME,
    private val reasoningEffort: String? = null,
    private val client: OkHttpClient = DEFAULT_CLIENT,
    private val log: GreeLog = GreeLog.Default,
) : Model {

    /** Reported to the ADK; the name the endpoint knows this model by, not a display label. */
    override val name: String = model

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
        if (stream) generateContentStreaming(request) else generateContentNonStreaming(request)

    /**
     * Streams SSE, emitting one partial [LlmResponse] per non-empty delta and then exactly one final
     * `partial = false` response.
     *
     * The call is cold: nothing is sent until collection starts, and cancelling the collector aborts
     * the in-flight HTTP call. That is what `callbackFlow` plus `awaitClose` buys here, and why a
     * plain `flow { }` wrapping the blocking `execute()` would not do: it could not be interrupted.
     */
    private fun generateContentStreaming(request: LlmRequest): Flow<LlmResponse> = callbackFlow {
        val call = newCall(request, stream = true)
        val accumulated = StringBuilder()
        val toolCalls = ToolCallAccumulator()

        try {
            call.execute().use { response ->
                val body = response.requireSuccessfulBody()
                val source = body.source()
                val sse = Sse.Stream(readLine = { source.readUtf8Line() })
                while (true) {
                    // `next` returns null both at the end of the stream and at `[DONE]`; either way
                    // the answer is complete as far as the server told us, so the loop simply ends.
                    val payload = sse.next() ?: break
                    val delta = parseStreamChunk(payload) ?: continue
                    val text = delta.text
                    if (text != null) {
                        accumulated.append(text)
                        trySend(text.toPartialResponse())
                    }
                    // 工具调用分片只累积不外发：参数还没拼完就发出 partial，界面会先闪出半条
                    // 命令，ADK 也可能拿不完整的 args 去执行工具。
                    toolCalls.feed(delta.toolCalls)
                }
            }
        } catch (e: Exception) {
            // close(cause) fails the flow; the collector's catch block turns it into a message.
            close(e.toTransportFailure())
            return@callbackFlow
        }

        // Exactly one aggregated response closes the turn; its content is the full answer, because
        // that is the response the ADK (and the UI) treats as authoritative.
        emitFinal(toolCalls.calls(), accumulated.toString())
        // Buffered deltas are handed to the collector before the channel closes.
        channel.close()
        // Cancellation lands here: abort the HTTP call so the socket is not left reading.
        awaitClose { call.cancel() }
    }
        .flowOn(Dispatchers.IO)

    /** Sends the same body with `stream = false` and maps the single completion it returns. */
    private fun generateContentNonStreaming(request: LlmRequest): Flow<LlmResponse> = callbackFlow {
        try {
            val completion = newCall(request, stream = false).execute().use { response ->
                val body = response.requireSuccessfulBody()
                parseCompletion(body.string())
                    ?: throw IOException("The model returned neither an answer nor a tool call.")
            }
            emitFinal(completion.toolCalls, completion.text, completion.model)
        } catch (e: Exception) {
            close(e.toTransportFailure())
            return@callbackFlow
        }
        channel.close()
        awaitClose { }
    }
        .flowOn(Dispatchers.IO)

    /**
     * Builds the POST with the chat-completions body and the bearer header.
     *
     * A blank [apiKey] means "no credential configured" (the BuildConfig default is empty when
     * `local.properties` omits it), so the header is skipped rather than sent empty.
     */
    private fun newCall(request: LlmRequest, stream: Boolean): Call {
        val body = request.toChatRequestJson(stream, reasoningEffort)
            .put("model", model)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(baseUrl + chatPath).post(body)
        authHeader()?.let { builder.header(AUTHORIZATION, it) }
        return client.newCall(builder.build())
    }

    /** The header value, or null when there is nothing to authenticate with. */
    private fun authHeader(): String? {
        if (apiKey.isBlank()) return null
        return if (authScheme.isBlank()) apiKey else "$authScheme $apiKey"
    }

    /**
     * Turns a non-2xx response into an [IOException] before its body is parsed.
     *
     * The body never travels into the exception: a misconfigured proxy or gateway can echo the request
     * (and therefore the credential) back in an error page, and the exception message is shown to the
     * user verbatim by both chat screens. It is still worth seeing while debugging — a 400 from a
     * tool-calling request almost always names the offending field in that body — so a truncated,
     * key-scrubbed copy goes to logcat only, under `adb logcat -s NanoGreeModel`.
     */
    private fun Response.requireSuccessfulBody(): ResponseBody {
        if (isSuccessful) return body ?: throw IOException("HTTP $code with an empty body.")
        val statusLine = "$code $message"
        val preview = body?.let { runCatching { it.string().flatPreview() }.getOrNull() }
        log.warn(TAG, "HTTP failure: $statusLine body=${preview?.scrubSecret()?.ifEmpty { "\"\"" } ?: "null"}")
        throw IOException("The model request failed: $statusLine.")
    }

    /** 压成一行并截断，免得一个整页 HTML 错误把日志冲掉。 */
    private fun String.flatPreview(): String =
        replace('\n', ' ').replace('\r', ' ').trim().take(MAX_ERROR_BODY_PREVIEW)

    /** 把 apiKey 本身从日志里抹掉。网关回填请求头时，密钥就是唯一不能被看到的字段。 */
    private fun String.scrubSecret(): String =
        if (apiKey.isBlank()) this else replace(apiKey, "***")

    private fun String.toPartialResponse(): LlmResponse =
        LlmResponse(
            content = Content(role = Role.MODEL, parts = listOf(Part(text = this))),
            partial = true,
        )

    /**
     * 本轮唯一一条终结响应。
     *
     * 正文与工具调用可以并存（模型常先说一句「我查一下」再调用），所以两者一起放进同一个 content，
     * 顺序保持正文在前 —— 界面的时间线按 part 分派，两者谁先出现就画在谁前面。
     */
    private fun ProducerScope<LlmResponse>.emitFinal(
        toolCalls: List<ParsedToolCall>,
        text: String,
        modelVersion: String? = null,
    ) {
        if (toolCalls.isNotEmpty()) {
            log.info(TAG, "模型请求调用工具：${toolCalls.joinToString("、") { it.name }}")
        }
        val parts = buildList {
            if (text.isNotEmpty()) add(Part(text = text))
            toolCalls.forEach { add(Part(functionCall = it.toAdkFunctionCall())) }
            // 既没正文也没调用时仍给一个空正文 part，与纯文本实现保持一致。
            if (isEmpty()) add(Part(text = ""))
        }
        trySend(
            LlmResponse(
                content = Content(role = Role.MODEL, parts = parts),
                finishReason = FinishReason.STOP,
                modelVersion = modelVersion,
                partial = false,
            )
        )
    }

    /**
     * Normalises every failure into a readable [IOException], because that is what the screens
     * display. Cancellation is left alone: it must keep propagating so structured concurrency can
     * unwind the turn.
     */
    private fun Exception.toTransportFailure(): Exception = when (this) {
        is IOException -> IOException(message ?: "The model request failed.")
        is CancellationException -> this
        else -> IOException(message ?: "The model request failed. (${this::class.simpleName})")
    }

    // ---- TOOL CALLS ----
    //
    // 请求侧：`config.tools` 里的 functionDeclarations 被序列化成 `tools`（见 GreeToolDto 的
    // toWireTools），历史中的 functionCall / functionResponse 被还原成 assistant.tool_calls 与
    // role=tool 消息。响应侧：非流式直接读 message.tool_calls，流式按 index 累积分片
    // （ToolCallAccumulator），最后和正文一起放进唯一那条终结响应。
    //
    // 两条必须记住的配对规则：
    //  1. ADK 侧约定客户端生成的 FunctionCall.id 带 `adk-` 前缀，端点自己的 `call_xxx` 不外传
    //     （统一在 ParsedToolCall.toAdkFunctionCall 里换掉）。但历史里的这种 id 会被 ADK 的
    //     `HistoryRewriterProcessor.stripClientFunctionCallIds` 无条件置空后才交到我们手上，
    //     所以回灌用的 id 是在 List<Content>.toWireMessages 里按出现顺序重建的：调用先进队、
    //     结果出队配对，两侧写同一个 id（assistant 的 tool_calls[].id == tool 的 tool_call_id）。
    //  2. 一个调用如果还没有结果（长任务、用户确认中），就不能出现在请求里 —— 协议要求每个
    //     tool_calls[].id 后面紧跟同 id 的 tool 消息，缺一条端点就判 400。反过来，配不到调用的
    //     结果也只能丢掉。两种过滤都在 toWireMessages 里。

    private companion object {
        val DEFAULT_CLIENT: OkHttpClient = OkHttpClient()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Stable tag so `adb logcat -s NanoGreeModel` shows only this model's lines. */
        const val TAG = "NanoGreeModel"
        const val AUTHORIZATION = "Authorization"

        /** 失败响应体进日志时的长度上限，够看完一句错误说明。 */
        const val MAX_ERROR_BODY_PREVIEW = 500
    }
}
