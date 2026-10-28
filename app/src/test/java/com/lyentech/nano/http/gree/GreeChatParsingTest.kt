package com.lyentech.nano.http.gree

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side tests over hardcoded fixtures. Nothing here touches the network, so they are safe to run
 * without a credential and cannot fail for reasons outside this machine.
 */
class GreeChatParsingTest {

    // ---- request body ----

    @Test
    fun systemInstructionBecomesALeadingSystemMessage() {
        val request =
            LlmRequest(
                contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "hi")))),
                config =
                    GenerateContentConfig(
                        systemInstruction =
                            Content(parts = listOf(Part(text = "be brief"), Part(text = "be kind")))
                    ),
            )

        val body = request.toChatRequestJson(stream = false)
        val messages = body.getJSONArray("messages")

        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        // Multiline join, because the wire field is a single flat string.
        assertEquals("be brief\nbe kind", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("hi", messages.getJSONObject(1).getString("content"))
        assertEquals(false, body.getBoolean("stream"))
    }

    @Test
    fun modelRoleMapsToAssistantAndUnknownRolesFallBackToUser() {
        val request =
            LlmRequest(
                contents =
                    listOf(
                        Content(role = Role.USER, parts = listOf(Part(text = "q"))),
                        Content(role = Role.MODEL, parts = listOf(Part(text = "a"))),
                        Content(role = "something-else", parts = listOf(Part(text = "x"))),
                    )
            )

        val messages = request.toChatRequestJson(stream = true).getJSONArray("messages")

        assertEquals(3, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("user", messages.getJSONObject(2).getString("role"))
        assertTrue(request.toChatRequestJson(stream = true).getBoolean("stream"))
    }

    @Test
    fun thoughtPartsAndEmptyContentsAreDropped() {
        val request =
            LlmRequest(
                contents =
                    listOf(
                        Content(
                            role = Role.MODEL,
                            parts = listOf(Part(text = "secret", thought = true), Part(text = "shown")),
                        ),
                        // No text at all: must not become an empty message.
                        Content(role = Role.USER, parts = listOf(Part(thought = true))),
                        Content(role = Role.USER, parts = emptyList()),
                    )
            )

        val messages = request.toChatRequestJson(stream = false).getJSONArray("messages")

        assertEquals(1, messages.length())
        assertEquals("shown", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun blankSystemInstructionIsNotSent() {
        assertNull(systemInstructionText(GenerateContentConfig()))
        assertNull(
            systemInstructionText(
                GenerateContentConfig(systemInstruction = Content(parts = listOf(Part(text = " "))))
            )
        )
    }

    // ---- streaming response ----

    private val streamingFixture =
        """
        data: {"choices":[{"delta":{"role":"assistant","content":""}}],"model":"gree-chat"}

        data: {"choices":[{"delta":{"content":"Hel"}}]}

        data: {"choices":[{"delta":{"content":"lo"}}],"model":"gree-chat"}

        data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

        data: [DONE]
        """
            .trimIndent()

    @Test
    fun deltasSkipEmptyFragmentsAndStopAtTheSentinel() {
        val deltas = parseStreamDeltas(streamingFixture)

        assertEquals(2, deltas.size)
        assertEquals("Hel", deltas[0].text)
        assertEquals("lo", deltas[1].text)
        // The sentinel and the trailing braces-only chunk never became a delta.
        assertEquals("Hello", deltas.mapNotNull { it.text }.joinToString(""))
    }

    @Test
    fun carriageReturnLineTerminatorsAreHandled() {
        // CRLF fixture: the delta count must match the LF fixture above. A CR left attached to the
        // payload would make `line.startsWith("data:")` fail on the next read and silently drop
        // every remaining fragment, so this guards the line splitter specifically.
        val fixture =
            "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\r\n" +
                "\r\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\r\n" +
                "\r\n" +
                "data: [DONE]\r\n"

        assertEquals(listOf("a", "b"), parseStreamDeltas(fixture).mapNotNull { it.text })
    }

    @Test
    fun aPayloadSplitAcrossTheStreamIsStillAssembled() {
        // This exercises the byte-at-a-time `scan` path: it re-splits the whole fixture into lines
        // from scratch, so it also covers a `data:` line a network read cut in half. The exact
        // payloads are asserted, so neither a line terminator nor the `[DONE]` sentinel can leak
        // through as data.
        val payloads = mutableListOf<String>()
        parseSsePayloads(streamingFixture) { payloads.add(it) }

        assertEquals(
            listOf(
                """{"choices":[{"delta":{"role":"assistant","content":""}}],"model":"gree-chat"}""",
                """{"choices":[{"delta":{"content":"Hel"}}]}""",
                """{"choices":[{"delta":{"content":"lo"}}],"model":"gree-chat"}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            ),
            payloads,
        )
    }

    @Test
    fun unusableChunksAreSkippedRatherThanThrowing() {
        val fixture =
            "data: not-json\n\n" +
                "data: {\"choices\":[]}\n\n" +
                // A JSON null must not become a text fragment. The host `org.json` returns the
                // literal string "null" from optString for these, so a test that only asserted
                // "no crash" would still pass against a broken parser; assert on the text instead.
                "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\n" +
                "data: {\"error\":{\"message\":\"boom\"}}\n\n" +
                "data: [DONE]\n"

        assertEquals(emptyList<StreamDelta>(), parseStreamDeltas(fixture))
        // And directly, so a null fragment can never reach a response as the word "null".
        assertNull(parseStreamChunk("""{"choices":[{"delta":{"content":null}}]}"""))
        assertNull(parseStreamChunk("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
    }

    @Test
    fun aSpaceAfterTheDataColonDoesNotHideTheSentinel() {
        // Regression: `data: [DONE]` leaves a leading space that, unforgiven, makes the sentinel
        // compare as `" [DONE]"` and slip through as a payload — and the same space would then be
        // handed to the JSON parser. Both readers are checked because the socket path and the
        // whole-body path used to disagree about this.
        val fixture = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\ndata: [DONE]\n"

        assertEquals(listOf("a"), parseStreamDeltas(fixture).mapNotNull { it.text })

        val lines = fixture.lines().iterator()
        val stream = Sse.Stream(readLine = { if (lines.hasNext()) lines.next() else null })
        assertEquals("""{"choices":[{"delta":{"content":"a"}}]}""", stream.next())
        assertNull(stream.next())
    }

    @Test
    fun aPayloadWithoutTheConventionalSpaceIsStillAccepted() {
        // `data:{...}` with no space is legal SSE too, so the space must be optional rather than
        // required.
        val fixture = "data:{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\ndata:[DONE]\n"

        assertEquals(listOf("x"), parseStreamDeltas(fixture).mapNotNull { it.text })
    }

    // ---- non-streaming response ----

    @Test
    fun completionReadsTextFinishReasonAndModel() {
        val completion =
            parseCompletion(
                """
                {"choices":[{"message":{"role":"assistant","content":"Hello there."},
                "finish_reason":"stop"}],"model":"gree-chat","usage":{"total_tokens":9}}
                """
                    .trimIndent()
            )

        assertEquals("Hello there.", completion?.text)
        assertEquals("stop", completion?.finishReason)
        assertEquals("gree-chat", completion?.model)
    }

    @Test
    fun aJsonNullContentReadsAsAbsentNotAsTheWordNull() {
        assertNull(parseCompletion("""{"choices":[{"message":{"content":null}}]}"""))
    }

    @Test
    fun completionWithoutAssistantContentIsNull() {
        assertNull(parseCompletion("""{"choices":[{"message":{"content":""}}]}"""))
        assertNull(parseCompletion("""{"choices":[]}"""))
        assertNull(parseCompletion("""{"choices":[{"message":{}}]}"""))
        assertNull(parseCompletion("<html>gateway error</html>"))
    }
}
