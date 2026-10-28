package com.lyentech.nano.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.lyentech.nano.BuildConfig
import com.lyentech.nano.global.AppTheme
import com.lyentech.nano.global.ScopedActivity
import com.lyentech.nano.global.foldTextParts
import com.lyentech.nano.global.foldThoughtParts
import com.lyentech.nano.http.gree.GreeChatModel
import com.lyentech.nano.ui.widget.ChatAuthor
import com.lyentech.nano.ui.widget.ChatMessage
import com.lyentech.nano.ui.widget.ChatScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * @author by jason-何伟杰，2026/9/29
 * des:云端模型 问答
 */
class ApiChatActivity : ScopedActivity() {
    private val sessionService = InMemorySessionService()

    // Built in onCreate; the cloud model has nothing to load, so it is ready immediately.
    private var runner: InMemoryRunner? = null

    private val messages = mutableStateListOf<ChatMessage>()
    private var inputEnabled by mutableStateOf(false)
    // Streaming is on by default: the user asked for the cloud screen to stream.
    private var streaming by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The cloud model needs no download or weights on disk, so the runner is ready immediately and
        // the input can open right away.
        runner =
            InMemoryRunner(
                agent = createAgent(),
                appName = APP_NAME,
                sessionService = sessionService,
            )
        addSystem(
            "Connected to ${endpointHost(BuildConfig.GREE_BASE_URL)} via ${BuildConfig.GREE_MODEL}."
        )
        inputEnabled = true

        setContent {
            AppTheme {
                ChatScreen(
                    title = "API chat",
                    messages = messages,
                    inputEnabled = inputEnabled,
                    onSend = ::sendToAgent,
                    onBack = ::finish,
                    streaming = streaming,
                    onStreamingChange = { streaming = it },
                )
            }
        }
    }

    /** The agent is text-only; no tools, and no device context to hand it. */
    private fun createAgent(): LlmAgent =
        LlmAgent(
            name = AGENT_NAME,
            model = GreeChatModel(),
            instruction =
                Instruction(
                    """
          You are a helpful assistant answering over a cloud API. Keep replies to one or two short
          sentences unless the user asks for more detail.
          """
                        .trimIndent()
                ),
        )

    private fun sendToAgent(text: String) {
        val useStreaming = streaming
        add(ChatAuthor.USER, text)
        // Lock the input for the duration of the turn: one conversation at a time.
        runOnUiThread { inputEnabled = false }

        // Dispatchers.IO: the model is network-bound, so this must never run on the main thread.
        scope.launch(Dispatchers.IO) {
            try {
                val events =
                    runner!!.runAsync(
                        userId = USER_ID,
                        sessionId = SESSION_ID,
                        newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
                        runConfig =
                            RunConfig(
                                streamingMode = if (useStreaming) StreamingMode.SSE else StreamingMode.NONE,
                                maxLlmCalls = MAX_LLM_CALLS,
                            ),
                    )

                val partial = StringBuilder()
                val bubble = AgentBubble()
                events.collect { event ->
                    val isAgent = event.author == AGENT_NAME
                    val chunk = if (isAgent) event.foldTextParts() else "" //过滤推理
//                    val chunk = if (isAgent) event.foldThoughtParts() else "" //过滤推理
                    val isPartial = event.partial
                    runOnUiThread {
                        if (isAgent) {
                            if (isPartial) {
                                // SSE mode: grow one bubble from the partial deltas as they arrive.
                                if (chunk.isNotEmpty()) {
                                    partial.append(chunk)
                                    bubble.show(partial.toString())
                                }
                            } else { //最后的聚合成果？
                                // The aggregated event ends the turn: prefer its authoritative text.
                                val finalText = chunk.ifBlank { partial.toString() }.trim()
                                if (finalText.isNotEmpty()) bubble.show(finalText)
                                partial.setLength(0)
                                bubble.endTurn()
                            }
                        }
                        // After the bubble, so any error line follows the text emitted alongside it.
                        event.errorMessage?.let { addSystem("Model error: $it") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addSystem("Error: ${e.message ?: e::class.simpleName}")
            } finally {
                runOnUiThread { inputEnabled = true }
            }
        }
    }

    /**
     * The reply bubble for the turn in progress: added on the first text, updated in place after. A
     * turn boundary gives the next reply its own bubble. UI thread only.
     */
    private inner class AgentBubble {
        private var index = -1

        fun show(text: String) {
            if (index < 0) {
                messages.add(ChatMessage(ChatAuthor.AGENT, text, AGENT_NAME))
                index = messages.lastIndex
            } else {
                messages[index] = messages[index].copy(text = text)
            }
        }

        fun endTurn() {
            index = -1
        }
    }

    private fun add(author: ChatAuthor, text: String) {
        val label = if (author == ChatAuthor.AGENT) AGENT_NAME else ""
        runOnUiThread { messages.add(ChatMessage(author, text, label)) }
    }

    private fun addSystem(text: String) {
        runOnUiThread { messages.add(ChatMessage(ChatAuthor.SYSTEM, text)) }
    }

    private companion object {
        const val APP_NAME = "GreeApiChatEx"
        const val USER_ID = "cloud-user"
        const val SESSION_ID = "cloud-session"
        const val AGENT_NAME = "AI"
        const val MAX_LLM_CALLS = 8

        /**
         * The host of [url], for the welcome line: enough to tell which backend is live without
         * echoing the API key or the full path. Falls back to the raw value if it is not a URL.
         */
        fun endpointHost(url: String): String =
            url.substringAfter("://", url).substringBefore('/').ifBlank { url }
    }
}
