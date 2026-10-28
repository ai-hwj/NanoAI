package com.lyentech.nano.http.gree

/**
 * @author by jason-何伟杰，2026/9/29
 *
 * des: 极简 SSE 读取器。只处理 OpenAI 风格的 `data: <payload>` 行，不含任何 ADK 类型，
 *      这样它可以脱离网络与协程单独推理和测试。
 */

/**
 * A line-oriented reader over an SSE byte stream, mirroring the parser the upstream `openai` Java
 * SDK uses.
 *
 * It is deliberately hand-rolled rather than delegated to OkHttp's `EventSources` (an extra
 * artifact) or okio's `BufferedSource.readUtf8Line`, because the SSE spec's `/r`, `/n` and `\r\n`
 * line terminators are not all understood by a plain line reader.
 *
 * This class is stateless with respect to [scan] and stateful only for the socket path ([next]);
 * the two entry points share one notion of a "line" but not the buffer.
 */
internal class Sse {
    /**
     * The socket path: [readLine] is called for each line off the wire.
     *
     * [next] returns the next payload, null on exhaustion, and null permanently once `[DONE]` has
     * been seen.
     */
    class Stream(private val readLine: () -> String?) {
        /** The next payload, or null once the stream is exhausted or `[DONE]` was reached. */
        fun next(): String? {
            while (true) {
                val line = readLine() ?: return null
                val payload = dataPayload(line) ?: continue
                if (payload.isEmpty()) continue
                if (payload == SSE_DONE) return null
                return payload
            }
        }
    }

    companion object {
        const val SSE_DATA_PREFIX = "data:"

        /** The OpenAI-style sentinel that ends a stream; never a payload. */
        const val SSE_DONE = "[DONE]"

        /**
         * The payload of a `data:` line, or null when the line carries no data.
         *
         * The SSE spec writes the payload as `data:<payload>` but every real server (this endpoint
         * included) sends `data: <payload>` with one space for readability. That space has to be
         * stripped here: leaving it on makes `payload == "[DONE]"` compare `" [DONE]"` against
         * `"[DONE]"` and silently fail, so the sentinel would be forwarded as if it were JSON. Only
         * a single leading space is removed, since the payload itself may be indented.
         */
        private fun dataPayload(line: String): String? {
            if (!line.startsWith(SSE_DATA_PREFIX)) return null
            val rest = line.substring(SSE_DATA_PREFIX.length)
            return rest.removePrefix(" ")
        }

        /**
         * Splits [raw] into SSE lines, invoking [onPayload] for every `data:` payload.
         *
         * Lines are reassembled character by character so a `data:` line that a network read cut in
         * half is still parsed as one line; this is the reader used when the caller already holds the
         * whole body. Line terminators (`\n`, `\r\n`, `\r`) never reach a payload, and the `[DONE]`
         * sentinel stops the scan rather than being handed on as data, exactly as [Stream.next] does.
         */
        fun scan(raw: String, onPayload: (String) -> Unit) {
            var index = 0
            while (index < raw.length) {
                val end = lineEnd(raw, index)
                val line = raw.substring(index, end.contentEnd)
                index = end.next
                val payload = dataPayload(line) ?: continue
                if (payload.isEmpty()) continue
                if (payload == SSE_DONE) return
                onPayload(payload)
            }
        }

        /**
         * Where the line starting at [start] ends: [contentEnd] excludes the terminator, [next] is
         * the offset of the following line.
         */
        private fun lineEnd(raw: String, start: Int): LineEnd {
            var i = start
            while (i < raw.length) {
                when (raw[i]) {
                    '\n' -> return LineEnd(contentEnd = i, next = i + 1)
                    '\r' ->
                        // CRLF consumes both bytes; a lone CR is a terminator in its own right.
                        return if (i + 1 < raw.length && raw[i + 1] == '\n') {
                            LineEnd(contentEnd = i, next = i + 2)
                        } else {
                            LineEnd(contentEnd = i, next = i + 1)
                        }
                }
                i++
            }
            return LineEnd(contentEnd = raw.length, next = raw.length)
        }

        private data class LineEnd(val contentEnd: Int, val next: Int)
    }
}

/**
 * Builds a reader over [raw] that calls [onPayload] with every SSE payload.
 *
 * Kept as a top-level function so the parsing logic can be unit-tested with a fixture string, with
 * no HTTP call anywhere in the path.
 */
internal fun parseSsePayloads(raw: String, onPayload: (String) -> Unit) {
    Sse.scan(raw, onPayload)
}
