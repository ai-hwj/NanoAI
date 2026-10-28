package com.lyentech.nano.agent.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 联网搜索的核心业务逻辑：多引擎按顺序回退，任一引擎给出结果就结束。
 *
 * 移植自 IDEA 插件的 WebSearchTool，引擎顺序与解析选择器保持一致：
 *   必应国内版（cn.bing.com，国内可直连且速度快）
 *   -> SearXNG 公共实例（聚合多个引擎，逐个人试）
 *   -> 百度（最后兜底，且最容易触发安全验证）
 *
 * 除 jsoup 外不依赖任何 Android 组件（日志通过 [WebLog] 注入），因此解析与回退逻辑
 * 可以在宿主 JVM 上直接单测。
 */
class WebSearchClient(
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val log: WebLog = WebLog.Default,
) {

    /**
     * 搜索 [query]，返回命中的结果（未过滤域名，由调用方决定策略）。
     *
     * 单个引擎失败不影响后续引擎：每一层都自己吞掉异常并返回空列表，最后统一判定。
     */
    suspend fun search(query: String): SearchResponse = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext SearchResponse(query, emptyList(), null)

        // 1. 必应国内版
        runCatching { searchWithBingCn(trimmed) }
            .onFailure { log.warn(TAG, "Bing CN 失败", it) }
            .getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() }
            ?.let { return@withContext SearchResponse(trimmed, it, "Bing CN") }

        // 2. SearXNG 公共实例：逐个尝试，第一个给出结果的实例即采用。
        for (instance in SEARXNG_INSTANCES) {
            val results = runCatching { searchWithSearxng(trimmed, instance) }
                .onFailure { log.warn(TAG, "SearXNG $instance 失败", it) }
                .getOrDefault(emptyList())
            if (results.isNotEmpty()) {
                return@withContext SearchResponse(trimmed, results, "SearXNG ($instance)")
            }
        }

        // 3. 百度兜底
        val baidu = runCatching { searchWithBaidu(trimmed) }
            .onFailure { log.warn(TAG, "百度失败", it) }
            .getOrDefault(emptyList())
        if (baidu.isNotEmpty()) return@withContext SearchResponse(trimmed, baidu, "Baidu")

        SearchResponse(trimmed, emptyList(), null)
    }

    /** 必应国内版：结果是 li.b_algo，标题在 h2>a，摘要在 div.b_caption p。 */
    internal fun searchWithBingCn(query: String): List<SearchResult> {
        val doc = fetch("https://cn.bing.com/search?q=${query.encoded()}&count=20")
        return parseBing(doc)
    }

    /** 必应的结果块解析，单独拆出来以便脱离网络直接单测。 */
    internal fun parseBing(doc: Document): List<SearchResult> {
        val results = doc.select("li.b_algo").mapNotNull { block ->
            val titleEl = block.selectFirst("h2 > a") ?: block.selectFirst("h2 a") ?: return@mapNotNull null
            val snippetEl = block.selectFirst("div.b_caption p") ?: block.selectFirst("p")
            val title = titleEl.text().trim()
            val url = titleEl.absUrl("href")
            if (title.isEmpty() || !url.startsWith("http")) return@mapNotNull null
            SearchResult(title, url, snippetEl?.text()?.trim().orEmpty())
        }
        return results.distinctBy { it.url }
    }

    /**
     * SearXNG 实例的 JSON API。
     *
     * 需要 `format=json`，部分公共实例关闭了该输出格式，此时会返回 HTML 或 403，
     * 落到 `ignoreContentType` 后的解析失败，被上层当作"这个实例不可用"跳过。
     */
    private fun searchWithSearxng(query: String, instance: String): List<SearchResult> {
        val url = "$instance/search?q=${query.encoded()}&format=json&engines=google,bing,duckduckgo"
        return parseSearxng(fetch(url).body().text())
    }

    /** SearXNG 的 JSON 解析。用 org.json 而非 DTO：字段随版本变动，只取需要的三项更耐改。 */
    internal fun parseSearxng(body: String): List<SearchResult> {
        if (body.isBlank()) return emptyList()
        val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return emptyList()
        val array = json.optJSONArray("results") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title").trim()
            val url = item.optString("url").trim()
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
            SearchResult(title, url, item.optString("content").trim())
        }
    }

    /**
     * 百度兜底。百度对代理/机房 IP 会跳安全验证页，因此先看标题是否命中验证关键词，
     * 命中就直接放弃，避免把验证页当成搜索结果喂给模型。
     */
    internal fun searchWithBaidu(query: String): List<SearchResult> {
        val doc = fetch("https://www.baidu.com/s?wd=${query.encoded()}&rn=20")
        return parseBaidu(doc)
    }

    /** 页面标题看着像验证页就认定不可用；否则再多选择器也解析不出真实结果。 */
    internal fun isBaiduSecurityPage(title: String): Boolean =
        title.contains("验证") || title.contains("安全") || title.contains("异常")

    /** 百度结果解析。容器类名改版频繁，按可能性从高到低尝试，第一个能出结果的即采用。 */
    internal fun parseBaidu(doc: Document): List<SearchResult> {
        if (isBaiduSecurityPage(doc.title())) return emptyList()
        for (selector in BAIDU_RESULT_SELECTORS) {
            val blocks = doc.select(selector)
            if (blocks.isEmpty()) continue
            val results = blocks.mapNotNull { block ->
                val titleEl = block.selectFirst("h3 > a")
                    ?: block.selectFirst("h3 a")
                    ?: block.selectFirst("a[title]")
                    ?: return@mapNotNull null
                val snippetEl = block.selectFirst("div.c-abstract")
                    ?: block.selectFirst("div.c-span-last")
                    ?: block.selectFirst("span.content-right")
                val text = titleEl.text().trim()
                val url = titleEl.absUrl("href")
                if (text.isEmpty() || !url.startsWith("http")) return@mapNotNull null
                SearchResult(text, url, snippetEl?.text()?.trim().orEmpty())
            }.distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    /** 统一的 GET：带轮换 UA 与浏览器常用请求头，超时和跳转跟随都交给 jsoup。 */
    private fun fetch(url: String): Document =
        Jsoup.connect(url)
            .userAgent(UserAgents.next())
            .timeout(timeoutMs)
            // SearXNG 返回 JSON，jsoup 只当文本读，不做 content-type 校验。
            .ignoreContentType(true)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .followRedirects(true)
            .maxBodySize(0)
            .get()

    private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val TAG = "NanoWebSearch"
        private const val DEFAULT_TIMEOUT_MS = 15_000

        /**
         * SearXNG 公共实例。这些都是社区公益节点，可用性会随时间变化，
         * 因此逐个尝试而不是只认一个；全部失败时还有百度兜底。
         */
        private val SEARXNG_INSTANCES = listOf(
            "https://search.bus-hit.me",
            "https://searx.be",
            "https://search.sapti.me",
            "https://search.projectsegfau.lt",
            "https://northboot.xyz",
        )

        private val BAIDU_RESULT_SELECTORS = listOf(
            "div.result",
            "div.result-op",
            "div.c-container",
            "article",
        )
    }
}
