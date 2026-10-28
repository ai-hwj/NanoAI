package com.lyentech.nano.agent.web

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索结果的解析与过滤是这批工具里最容易悄悄出错的部分：选择器写错不会抛异常，只会静默
 * 返回空列表，而模型会因此认为"网上查不到"。这里用真实结构形态的 HTML/JSON 片段，
 * 直接验证 [WebSearchClient] 里真正的解析函数（非复刻实现）。
 */
class WebSearchParsingTest {

    /** 注入 NoOp 日志：android.util.Log 在宿主 JVM 上是会抛异常的桩实现。 */
    private val client = WebSearchClient(log = WebLog.NoOp)

    // ---- 必应解析 ----

    @Test
    fun parsesBingResultBlocks() {
        val html = """
            <ol id="b_results">
              <li class="b_algo">
                <h2><a href="https://example.com/one">第一条标题</a></h2>
                <div class="b_caption"><p>第一条摘要</p></div>
              </li>
              <li class="b_algo">
                <h2><a href="https://example.com/two">第二条标题</a></h2>
                <div class="b_caption"><p>第二条摘要</p></div>
              </li>
              <li class="b_ans">这是侧边栏，不是搜索结果</li>
            </ol>
        """.trimIndent()

        val parsed = client.parseBing(Jsoup.parse(html))
        assertEquals(2, parsed.size)
        assertEquals("第一条标题", parsed[0].title)
        assertEquals("https://example.com/one", parsed[0].url)
        assertEquals("第一条摘要", parsed[0].snippet)
    }

    @Test
    fun bingSkipsBlocksWithoutUsableLink() {
        // 标题没有链接、或链接不是绝对 http 地址的块都必须丢掉，
        // 否则会把空链接交给模型去抓。
        val html = """
            <li class="b_algo"><h2>没有链接的标题</h2></li>
            <li class="b_algo"><h2><a href="https://example.com/ok">正常</a></h2></li>
        """.trimIndent()

        val parsed = client.parseBing(Jsoup.parse(html))
        assertEquals(1, parsed.size)
        assertEquals("https://example.com/ok", parsed[0].url)
    }

    @Test
    fun bingDeduplicatesByUrl() {
        val html = """
            <li class="b_algo"><h2><a href="https://example.com/same">甲</a></h2></li>
            <li class="b_algo"><h2><a href="https://example.com/same">乙</a></h2></li>
        """.trimIndent()

        assertEquals(1, client.parseBing(Jsoup.parse(html)).size)
    }

    @Test
    fun bingToleratesMissingSnippet() {
        val html = """<li class="b_algo"><h2><a href="https://example.com/x">标题</a></h2></li>"""

        val parsed = client.parseBing(Jsoup.parse(html))
        assertEquals(1, parsed.size)
        assertEquals("", parsed[0].snippet)
    }

    // ---- SearXNG JSON 解析 ----

    @Test
    fun parsesSearxngJson() {
        val json = """
            {"results":[
              {"title":"A","url":"https://a.example","content":"摘要 A"},
              {"title":"B","url":"https://b.example","content":"摘要 B"}
            ]}
        """.trimIndent()

        val parsed = client.parseSearxng(json)
        assertEquals(2, parsed.size)
        assertEquals("A", parsed[0].title)
        assertEquals("https://b.example", parsed[1].url)
        assertEquals("摘要 B", parsed[1].snippet)
    }

    @Test
    fun searxngReturnsEmptyOnMalformedBody() {
        // 实例关闭了 json 输出时会返回 HTML，解析必须安静地失败而不是崩掉整次搜索。
        assertTrue(client.parseSearxng("<html><body>not json</body></html>").isEmpty())
        assertTrue(client.parseSearxng("").isEmpty())
        assertTrue(client.parseSearxng("{}").isEmpty())
    }

    @Test
    fun searxngSkipsEntriesMissingTitleOrUrl() {
        val json = """{"results":[{"title":"","url":"https://a.example"},{"title":"B","url":""}]}"""
        assertTrue(client.parseSearxng(json).isEmpty())
    }

    // ---- 百度安全验证识别 ----

    @Test
    fun detectsBaiduSecurityPage() {
        assertTrue(client.isBaiduSecurityPage("百度安全验证"))
        assertTrue(client.isBaiduSecurityPage("网络异常，请稍后重试"))
        assertFalse(client.isBaiduSecurityPage("kotlin 协程 - 搜索结果"))
    }

    @Test
    fun baiduSecurityPageYieldsNoResults() {
        // 命中验证页时必须返回空，否则"请完成验证"会被当成搜索结果交给模型。
        val doc = Jsoup.parse("<html><head><title>百度安全验证</title></head><body></body></html>")
        assertTrue(client.parseBaidu(doc).isEmpty())
    }

    @Test
    fun parsesBaiduResultBlocks() {
        val html = """
            <html><head><title>查询词 - 搜索结果</title></head><body>
              <div class="result">
                <h3><a href="https://example.com/baidu">百度结果标题</a></h3>
                <div class="c-abstract">百度结果摘要</div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = client.parseBaidu(Jsoup.parse(html))
        assertEquals(1, parsed.size)
        assertEquals("百度结果标题", parsed[0].title)
        assertEquals("百度结果摘要", parsed[0].snippet)
    }

    // ---- 域名过滤 ----

    private val results = listOf(
        SearchResult("A", "https://docs.example.com/a", ""),
        SearchResult("B", "https://blog.spam.com/b", ""),
        SearchResult("C", "https://www.example.org/c", ""),
    )

    @Test
    fun noRulesKeepsEverything() {
        assertEquals(3, results.filterByDomains(null, null).size)
    }

    @Test
    fun blockedDomainIsDropped() {
        assertEquals(listOf("A", "C"), results.filterByDomains(null, listOf("spam.com")).map { it.title })
    }

    @Test
    fun allowedDomainActsAsWhitelist() {
        assertEquals(listOf("A"), results.filterByDomains(listOf("example.com"), null).map { it.title })
    }

    @Test
    fun blockWinsOverAllow() {
        // 同时命中白名单与黑名单时必须排除，否则黑名单形同虚设。
        assertTrue(results.filterByDomains(listOf("example.com"), listOf("docs.example.com")).isEmpty())
    }

    @Test
    fun domainMatchIsCaseInsensitive() {
        // 大写的域名规则也要能匹配到小写的 URL。注意 example.org 并不包含 example.com，
        // 所以这里只有 docs.example.com 命中。
        assertEquals(listOf("A"), results.filterByDomains(listOf("EXAMPLE.COM"), null).map { it.title })
        // 反过来，大写规则也不该因为大小写差异漏掉 blog.spam.com。
        assertEquals(
            listOf("A", "C"),
            results.filterByDomains(null, listOf("SPAM.COM")).map { it.title },
        )
    }

    // ---- 结果截断 ----

    @Test
    fun shortTextIsUntouched() {
        assertEquals("hello", "hello".truncateForModel(maxChars = 100))
    }

    @Test
    fun longTextKeepsBothEnds() {
        // 只保留开头会丢掉页面结论，所以两端都要留。
        val text = "HEAD" + "x".repeat(500) + "TAIL"
        val truncated = text.truncateForModel(maxChars = 100)

        assertTrue(truncated.startsWith("HEAD"))
        assertTrue(truncated.endsWith("TAIL"))
        assertTrue(truncated.contains("..."))
        assertTrue(truncated.length < text.length)
    }

    @Test
    fun truncationNeverExceedsTheLimit() {
        // 上限是用来控制上下文占用的，必须是真的硬上限：
        // 省略号本身的长度也要算进预算，否则会超出去几个字符。
        val text = "x".repeat(10_000)
        for (max in listOf(50, 100, 999, 16_000)) {
            assertTrue(
                "limit=$max 时长度超出上限",
                text.truncateForModel(maxChars = max).length <= max,
            )
        }
    }
}
