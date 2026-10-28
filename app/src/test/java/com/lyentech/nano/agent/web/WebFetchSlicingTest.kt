package com.lyentech.nano.agent.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抓取工具的两条关键契约：
 *  1. 长页面必须能被 offset/limit 分段读，且返回的 truncated 标记要正确
 *     —— 标记错了模型会以为自己已经看到全文。
 *  2. 非 http(s) 的地址、不可达的地址必须安静地失败，不能抛异常中断整轮对话。
 */
class WebFetchSlicingTest {

    private val slicer = MarkdownSlicer()
    private val client = WebFetchClient()

    // ---- 分页 ----

    @Test
    fun fullReadIsNotTruncated() {
        val markdown = (1..20).joinToString("\n") { "第 $it 行" }
        val slice = slicer.slice(markdown, offset = null, limit = null)

        assertTrue(slice.truncated.not())
        assertEquals(20, slice.content.lines().size)
    }

    @Test
    fun limitCutsAndMarksTruncated() {
        val markdown = (1..20).joinToString("\n") { "第 $it 行" }
        val slice = slicer.slice(markdown, offset = null, limit = 5)

        assertEquals(5, slice.content.lines().size)
        assertTrue(slice.truncated)
        assertEquals(1, slice.startLine)
        assertEquals(5, slice.endLine)
    }

    @Test
    fun offsetContinuesFromWhereTheLastChunkEnded() {
        val markdown = (1..20).joinToString("\n") { "第 $it 行" }
        val slice = slicer.slice(markdown, offset = 6, limit = 5)

        assertEquals("第 6 行", slice.content.lines().first())
        assertEquals(6, slice.startLine)
        assertEquals(10, slice.endLine)
        assertTrue(slice.truncated)
    }

    @Test
    fun readingToTheEndIsNotMarkedTruncated() {
        // 已经读到最后一页就不该再说"还有后续"，否则模型会无意义地反复翻页。
        val markdown = (1..20).joinToString("\n") { "第 $it 行" }
        val slice = slicer.slice(markdown, offset = 16, limit = 100)

        assertEquals(5, slice.content.lines().size)
        assertTrue(slice.truncated.not())
    }

    @Test
    fun offsetBeyondEndYieldsEmptySliceInsteadOfCrashing() {
        val markdown = (1..3).joinToString("\n") { "第 $it 行" }
        val slice = slicer.slice(markdown, offset = 99, limit = 10)

        assertEquals("", slice.content)
    }

    @Test
    fun blankLinesAtBothEndsAreStrippedBeforeSlicing() {
        // 否则 offset 的分页边界会被首尾空行顶偏，第一页看起来是空的。
        val markdown = "\n\n第一行\n第二行\n\n\n"
        val slice = slicer.slice(markdown, offset = 1, limit = 10)

        assertEquals(2, slice.content.lines().size)
        assertEquals("第一行", slice.content.lines().first())
    }

    @Test
    fun emptyPageYieldsEmptySlice() {
        val slice = slicer.slice("", offset = null, limit = null)
        assertEquals("", slice.content)
        assertTrue(slice.truncated.not())
    }

    // ---- 失败路径 ----

    @Test
    fun rejectsNonHttpScheme() = runBlocking {
        // file:// 之类的地址既抓不到也可能读本地文件，必须在入口就挡掉。
        val outcome = client.fetch("file:///etc/passwd")
        assertTrue(outcome is WebFetchClient.FetchOutcome.Failure)
    }

    @Test
    fun rejectsEmptyUrl() = runBlocking {
        assertTrue(client.fetch("   ") is WebFetchClient.FetchOutcome.Failure)
    }

    @Test
    fun reportsFailureInsteadOfThrowingOnUnreachableHost() = runBlocking {
        // 关键：失败要作为返回值，而不是异常 —— 工具抛异常会中断整轮对话。
        val outcome = client.fetch("https://this-host-does-not-exist.invalid/page", selector = null)
        assertTrue(outcome is WebFetchClient.FetchOutcome.Failure)
    }
}
