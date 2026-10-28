package com.lyentech.nano.agent.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 联网冒烟测试：验证解析规则在当前真实页面上仍然成立。
 *
 * 默认跳过（需要网络，且搜索引擎会限流，不适合放进常规构建）。要验证时手动开：
 *
 *   ./gradlew :app:testDebugUnitTest --tests "*WebLiveSmokeTest" -Pnano.live.web
 *
 * 之所以值得单独存在：搜索解析的选择器是硬编码的，站点改版后单元测试用的静态片段
 * 仍然会全绿，只有真连一次才能发现"选择器已经失效"。
 */
class WebLiveSmokeTest {

    /**
     * 用系统属性开关，Gradle 侧通过 `-Pnano.live.web` 透传（见 build.gradle.kts）。
     *
     * 只判断"非 false"，因为命令行上常见的写法是 `-Pnano.live.web`（不带值），
     * 这种写法传进来的是空字符串而不是 "true"。
     */
    private val liveEnabled = System.getProperty("nano.live.web")?.let { it != "false" } ?: false

    private val client = WebSearchClient(log = WebLog.NoOp)
    private val fetcher = WebFetchClient()

    @Test
    fun realSearchReturnsResults() = runBlocking {
        assumeTrue("需要 -Dnano.live.web=true 才跑联网测试", liveEnabled)

        val response = client.search("Kotlin 协程 官方文档")
        println("引擎：${response.engine}，结果数：${response.results.size}")
        response.results.take(5).forEach { println(" - ${it.title} :: ${it.url}") }

        assertTrue("三个引擎都没返回结果，可能全被限流或选择器已失效", response.results.isNotEmpty())
        assertTrue("结果必须带可用链接", response.results.all { it.url.startsWith("http") })
    }

    @Test
    fun realFetchConvertsPageToMarkdown() = runBlocking {
        assumeTrue("需要 -Dnano.live.web=true 才跑联网测试", liveEnabled)

        val outcome = fetcher.fetch("https://kotlinlang.org/docs/coroutines-overview.html")
        assertTrue("抓取失败：$outcome", outcome is WebFetchClient.FetchOutcome.Success)

        val page = (outcome as WebFetchClient.FetchOutcome.Success).page
        println("标题：${page.title}，正文长度：${page.markdown.length}")
        assertTrue("正文不该为空", page.markdown.isNotBlank())
        // 转换是否真的发生了：Markdown 里不该残留 HTML 标签。
        assertTrue("正文里仍有 HTML 标签，转换可能没生效", !page.markdown.contains("<div"))
    }
}
